package com.nodeseek.app.data.overseas

import android.content.Context
import com.nodeseek.app.domain.overseas.OverseasTranslationConfig
import com.nodeseek.app.domain.overseas.TranslationEngineKind
import com.nodeseek.app.domain.overseas.TranslationTier
import com.nodeseek.app.domain.overseas.VpsGlossaryTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Single translation output paired with the tier that produced it.
 */
data class TranslationResult(
    val translatedText: String,
    val tier: TranslationTier,
    val fromCache: Boolean = false,
)

@Serializable
private data class TranslationCacheEntry(
    val translatedText: String,
    val tierName: String,
    val updatedAtEpochMillis: Long,
)

@Serializable
private data class TranslationCachePayload(
    val entries: Map<String, TranslationCacheEntry> = emptyMap(),
)

/**
 * 3-Tier VPS-Domain-Aware Chinese Translation Engine for Overseas Promotion Details with persistent
 * content-hash cache (`filesDir/overseas_translations.json`):
 * - Tier 1 (Optional Custom Provider): OpenAI-compatible (`/v1/chat/completions`) or DeepLX (`/translate`)
 * - Tier 2 (Zero-Config Free Online): Google GTX (`translate.googleapis.com/translate_a/single`)
 * - Tier 3 (Always-Available Offline Fallback): [VpsGlossaryTranslator]
 */
class OverseasTranslator(
    private val cacheFile: File,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val gtxBaseUrl: String = DEFAULT_GTX_BASE_URL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
) {
    constructor(context: Context) : this(
        cacheFile = File((context.applicationContext ?: context).filesDir, TRANSLATIONS_CACHE_FILE_NAME),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val cacheMutex = Mutex()
    private val networkSemaphore = Semaphore(permits = 2)
    private var cacheLoaded = false
    private val memoryCache = LinkedHashMap<String, TranslationCacheEntry>()

    /**
     * Translates Markdown notification content while preserving Markdown headings, bullets,
     * fenced code blocks, inline code, and link targets.
     */
    suspend fun translateMarkdown(
        markdown: String,
        config: OverseasTranslationConfig,
        forceRefresh: Boolean = false,
    ): TranslationResult = translateText(
        text = markdown,
        config = config,
        forceRefresh = forceRefresh,
        preserveMarkdown = true,
        offlineFallback = { VpsGlossaryTranslator.translateMarkdownOffline(markdown) },
    )

    /**
     * Translates a piece of text through the configured tier waterfall + SHA-256 disk cache.
     */
    suspend fun translateText(
        text: String,
        config: OverseasTranslationConfig,
        forceRefresh: Boolean = false,
        preserveMarkdown: Boolean = false,
        offlineFallback: () -> String = { VpsGlossaryTranslator.translateTitleOffline(text) },
    ): TranslationResult = withContext(ioDispatcher) {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            return@withContext TranslationResult("", TranslationTier.NONE)
        }

        val contentKey = sha256Hex(if (preserveMarkdown) "md:$normalized" else normalized)

        if (!forceRefresh) {
            val cached = readCacheEntry(contentKey)
            if (cached != null) {
                val cachedTier = runCatching { TranslationTier.valueOf(cached.tierName) }
                    .getOrDefault(TranslationTier.OFFLINE_GLOSSARY)
                val cacheAcceptable = when (config.engineKind) {
                    TranslationEngineKind.OFFLINE_GLOSSARY_ONLY -> true
                    TranslationEngineKind.AUTO_GTX_WITH_GLOSSARY ->
                        cachedTier == TranslationTier.GOOGLE_GTX || cachedTier == TranslationTier.CUSTOM_API
                    TranslationEngineKind.OPENAI_COMPATIBLE,
                    TranslationEngineKind.DEEPLX,
                    -> cachedTier == TranslationTier.CUSTOM_API
                }
                if (cacheAcceptable) {
                    return@withContext TranslationResult(
                        translatedText = cached.translatedText,
                        tier = cachedTier,
                        fromCache = true,
                    )
                }
            }
        }

        // Tier 1: Optional Custom Provider (OpenAI-compatible or DeepLX)
        if (config.engineKind == TranslationEngineKind.OPENAI_COMPATIBLE && config.hasValidCustomEndpoint) {
            val customResult = runCatching {
                networkSemaphore.withPermit {
                    translateViaOpenAiCompatible(normalized, config, preserveMarkdown)
                }
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()

            if (!customResult.isNullOrBlank()) {
                val polished = polishOutput(customResult, preserveMarkdown)
                writeCacheEntry(contentKey, polished, TranslationTier.CUSTOM_API)
                return@withContext TranslationResult(polished, TranslationTier.CUSTOM_API, fromCache = false)
            }
        } else if (config.engineKind == TranslationEngineKind.DEEPLX && config.hasValidCustomEndpoint) {
            val customResult = runCatching {
                networkSemaphore.withPermit {
                    translateViaDeepLx(normalized, config)
                }
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()

            if (!customResult.isNullOrBlank()) {
                val polished = polishOutput(customResult, preserveMarkdown)
                writeCacheEntry(contentKey, polished, TranslationTier.CUSTOM_API)
                return@withContext TranslationResult(polished, TranslationTier.CUSTOM_API, fromCache = false)
            }
        }

        // Tier 2: Zero-Config Free Google GTX (when not restricted to offline-only)
        if (config.engineKind != TranslationEngineKind.OFFLINE_GLOSSARY_ONLY) {
            val gtxResult = runCatching {
                networkSemaphore.withPermit {
                    translateViaGoogleGtx(normalized)
                }
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()

            if (!gtxResult.isNullOrBlank()) {
                val polished = polishOutput(gtxResult, preserveMarkdown)
                writeCacheEntry(contentKey, polished, TranslationTier.GOOGLE_GTX)
                return@withContext TranslationResult(polished, TranslationTier.GOOGLE_GTX, fromCache = false)
            }
        }

        // Tier 3: Always-Available Offline VPS Terminology Glossary Engine
        val offlineText = offlineFallback().ifBlank { normalized }
        writeCacheEntry(contentKey, offlineText, TranslationTier.OFFLINE_GLOSSARY)
        TranslationResult(offlineText, TranslationTier.OFFLINE_GLOSSARY, fromCache = false)
    }

    /**
     * Tests connectivity and output of [config] using a representative VPS deal phrase without touching cache.
     */
    suspend fun testConnection(config: OverseasTranslationConfig): Result<String> = withContext(ioDispatcher) {
        val sample = "Special Offer: 2 vCPU 4GB RAM NVMe VPS in Tokyo - \$14.99/yr Recurring"
        when (config.engineKind) {
            TranslationEngineKind.OFFLINE_GLOSSARY_ONLY -> {
                Result.success("离线术语引擎就绪：${VpsGlossaryTranslator.translateTitleOffline(sample)}")
            }
            TranslationEngineKind.OPENAI_COMPATIBLE -> {
                if (!config.hasValidCustomEndpoint) {
                    return@withContext Result.failure(IllegalArgumentException("请填写有效的 OpenAI 兼容 API 地址（http/https）"))
                }
                runCatching {
                    val out = translateViaOpenAiCompatible(sample, config, preserveMarkdown = false)
                    "OpenAI 兼容接口测试成功：${VpsGlossaryTranslator.enhanceMachineTranslation(out)}"
                }
            }
            TranslationEngineKind.DEEPLX -> {
                if (!config.hasValidCustomEndpoint) {
                    return@withContext Result.failure(IllegalArgumentException("请填写有效的 DeepLX 接口地址（http/https）"))
                }
                runCatching {
                    val out = translateViaDeepLx(sample, config)
                    "DeepLX 接口测试成功：${VpsGlossaryTranslator.enhanceMachineTranslation(out)}"
                }
            }
            TranslationEngineKind.AUTO_GTX_WITH_GLOSSARY -> {
                val gtxAttempt = runCatching { translateViaGoogleGtx(sample) }
                if (gtxAttempt.isSuccess) {
                    Result.success("Google GTX 在线翻译可用：${VpsGlossaryTranslator.enhanceMachineTranslation(gtxAttempt.getOrThrow())}")
                } else {
                    Result.success(
                        "Google GTX 当前不可达（${gtxAttempt.exceptionOrNull()?.message ?: "超时"}），已自动启用离线 VPS 术语引擎：" +
                            VpsGlossaryTranslator.translateTitleOffline(sample),
                    )
                }
            }
        }
    }

    suspend fun clearCache() = withContext(ioDispatcher) {
        cacheMutex.withLock {
            memoryCache.clear()
            cacheLoaded = true
            runCatching {
                if (cacheFile.exists()) cacheFile.delete()
            }
        }
    }

    suspend fun cacheSize(): Int = withContext(ioDispatcher) {
        cacheMutex.withLock {
            ensureCacheLoadedLocked()
            memoryCache.size
        }
    }

    internal fun parseGoogleGtxResponse(rawJson: String): String? {
        if (rawJson.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(rawJson)
            if (root !is JsonArray || root.isEmpty()) return null
            val sentences = root[0] as? JsonArray ?: return null
            val builder = StringBuilder()
            for (segment in sentences) {
                val pair = segment as? JsonArray ?: continue
                val piece = (pair.firstOrNull() as? JsonPrimitive)?.contentOrNull
                if (!piece.isNullOrEmpty()) {
                    builder.append(piece)
                }
            }
            builder.toString().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    internal fun parseOpenAiResponse(rawJson: String): String? {
        if (rawJson.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val choices = root["choices"]?.jsonArray ?: return null
            val firstChoice = choices.firstOrNull()?.jsonObject ?: return null
            val message = firstChoice["message"]?.jsonObject
            val content = message?.get("content")?.jsonPrimitive?.contentOrNull
                ?: firstChoice["text"]?.jsonPrimitive?.contentOrNull
            content?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    internal fun parseDeepLxResponse(rawJson: String): String? {
        if (rawJson.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val data = root["data"]?.jsonPrimitive?.contentOrNull
            if (!data.isNullOrBlank()) return@runCatching data.trim()
            val alternatives = root["alternatives"]?.jsonArray
            alternatives?.firstOrNull()?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    internal fun resolveOpenAiCompletionsUrl(rawEndpoint: String): String {
        val trimmed = rawEndpoint.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions", ignoreCase = true) -> trimmed
            trimmed.endsWith("/v1", ignoreCase = true) -> "$trimmed/chat/completions"
            trimmed.contains("/chat/completions", ignoreCase = true) -> trimmed
            else -> "$trimmed/v1/chat/completions"
        }
    }

    private fun polishOutput(rawText: String, preserveMarkdown: Boolean): String {
        if (!preserveMarkdown) {
            return VpsGlossaryTranslator.enhanceMachineTranslation(rawText)
        }
        return rawText.lines().joinToString("\n") { line ->
            if (line.isBlank()) "" else VpsGlossaryTranslator.enhanceMachineTranslation(line)
        }.trim()
    }

    private suspend fun translateViaGoogleGtx(text: String): String {
        val base = gtxBaseUrl.toHttpUrlOrNull()
            ?: throw IOException("Invalid Google GTX URL")
        val url = base.newBuilder()
            .setQueryParameter("client", "gtx")
            .setQueryParameter("sl", "auto")
            .setQueryParameter("tl", "zh-CN")
            .setQueryParameter("dt", "t")
            .setQueryParameter("q", text.take(2800))
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept", "application/json")
            .build()

        val body = executeCall(request)
        return parseGoogleGtxResponse(body) ?: throw IOException("Google GTX 响应格式无效")
    }

    private suspend fun translateViaOpenAiCompatible(
        text: String,
        config: OverseasTranslationConfig,
        preserveMarkdown: Boolean,
    ): String {
        val endpoint = resolveOpenAiCompletionsUrl(config.customEndpointUrl)
        val modelName = config.customModel.trim().ifBlank { OverseasTranslationConfig.DEFAULT_OPENAI_MODEL }

        val systemInstruction = if (preserveMarkdown) {
            "你是资深 VPS/主机社区翻译助手。请将输入的 VPS 促销通知内容翻译成地道中文，严格保留原有的 Markdown 标题、列表、代码块、优惠码和所有 URL 链接不变；" +
                "将 Recurring 译为「续费同价」，Unmetered 译为「不限流量」，Storage VPS 译为「大盘鸡」。只输出译文 Markdown，不要附加解释。"
        } else {
            "你是资深 VPS/主机社区翻译助手。请将英文 VPS 促销标题或摘要翻译成简洁地道的中文：" +
                "保留所有价格、货币符号、优惠码、CPU/内存/硬盘/流量规格；将 Recurring 译为「续费同价」，" +
                "Unmetered 译为「不限流量」，Storage VPS 译为「大盘鸡」。只输出译文，不要附加解释。"
        }

        val payload = buildJsonObject {
            put("model", modelName)
            put("temperature", 0.2)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", systemInstruction)
                        },
                    )
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", text.take(3600))
                        },
                    )
                },
            )
        }

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
        if (config.customApiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${config.customApiKey.trim()}")
        }

        val body = executeCall(requestBuilder.build())
        return parseOpenAiResponse(body) ?: throw IOException("OpenAI 兼容接口未返回译文")
    }

    private suspend fun translateViaDeepLx(
        text: String,
        config: OverseasTranslationConfig,
    ): String {
        val endpoint = config.customEndpointUrl.trim()
        val payload = buildJsonObject {
            put("text", text.take(3600))
            put("source_lang", "EN")
            put("target_lang", "ZH")
        }

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
        if (config.customApiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${config.customApiKey.trim()}")
        }

        val body = executeCall(requestBuilder.build())
        return parseDeepLxResponse(body) ?: throw IOException("DeepLX 接口未返回有效译文")
    }

    private suspend fun executeCall(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(IOException("HTTP ${resp.code}"))
                            }
                            return
                        }
                        val responseText = resp.body?.string().orEmpty()
                        if (continuation.isActive) {
                            continuation.resume(responseText)
                        }
                    }
                }
            })
        }

    private suspend fun readCacheEntry(key: String): TranslationCacheEntry? =
        cacheMutex.withLock {
            ensureCacheLoadedLocked()
            memoryCache[key]
        }

    private suspend fun writeCacheEntry(key: String, translated: String, tier: TranslationTier) {
        cacheMutex.withLock {
            ensureCacheLoadedLocked()
            memoryCache[key] = TranslationCacheEntry(
                translatedText = translated,
                tierName = tier.name,
                updatedAtEpochMillis = nowMillisProvider(),
            )
            trimCacheLocked()
            persistCacheLocked()
        }
    }

    private fun ensureCacheLoadedLocked() {
        if (cacheLoaded) return
        cacheLoaded = true
        if (!cacheFile.exists()) return
        val payload = runCatching {
            val content = cacheFile.readText(Charsets.UTF_8)
            if (content.isBlank()) null else json.decodeFromString<TranslationCachePayload>(content)
        }.getOrNull()
        if (payload != null) {
            memoryCache.clear()
            memoryCache.putAll(payload.entries)
        }
    }

    private fun trimCacheLocked() {
        if (memoryCache.size <= MAX_CACHE_ENTRIES) return
        val kept = memoryCache.entries
            .sortedByDescending { it.value.updatedAtEpochMillis }
            .take(MAX_CACHE_ENTRIES)
        memoryCache.clear()
        kept.forEach { (k, v) -> memoryCache[k] = v }
    }

    private fun persistCacheLocked() {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val encoded = json.encodeToString(TranslationCachePayload(entries = memoryCache.toMap()))
            val tmpFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            tmpFile.writeText(encoded, Charsets.UTF_8)
            if (!tmpFile.renameTo(cacheFile)) {
                cacheFile.writeText(encoded, Charsets.UTF_8)
                tmpFile.delete()
            }
        }
    }

    internal fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_GTX_BASE_URL = "https://translate.googleapis.com/translate_a/single"
        const val TRANSLATIONS_CACHE_FILE_NAME = "overseas_translations.json"
        private const val MAX_CACHE_ENTRIES = 600
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
