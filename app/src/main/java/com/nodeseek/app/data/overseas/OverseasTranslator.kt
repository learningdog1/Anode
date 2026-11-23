package com.nodeseek.app.data.overseas

import android.content.Context
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasForumPostFormatter
import com.nodeseek.app.domain.overseas.OverseasLabeledLink
import com.nodeseek.app.domain.overseas.OverseasRepostContent
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
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Provenance tier of a translated repost segment.
 */
enum class TranslationTier {
    NONE,
    GOOGLE_GTX,
    OFFLINE_GLOSSARY,
}

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
    val tierName: String = TranslationTier.NONE.name,
    val updatedAtEpochMillis: Long = 0L,
)

@Serializable
private data class TranslationCachePayload(
    val entries: Map<String, TranslationCacheEntry> = emptyMap(),
)

/**
 * Zero-config VPS-Domain-Aware Chinese Translation Engine for One-Click Overseas Forum Reposting (`一键搬贴`)
 * with persistent content-hash cache (`filesDir/overseas_translations.json`):
 * - Online: Free Google GTX (`translate.googleapis.com/translate_a/single`) + [VpsGlossaryTranslator] polish
 * - Automatic Offline Fallback: [VpsGlossaryTranslator] when offline or GTX is unreachable
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

    private sealed interface MaskedSpan {
        data class Verbatim(val raw: String) : MaskedSpan
        data class LinkUrl(val url: String) : MaskedSpan
    }

    private sealed interface MarkdownSegment {
        data class Passthrough(val text: String) : MarkdownSegment
        data class TranslatableBlock(val rawMarkdown: String) : MarkdownSegment
    }

    /**
     * Automatically translates an [OverseasRepostContent] (title + structured OP main-post Markdown
     * + unembedded link labels) into natural, forum-ready Chinese while strictly protecting code blocks,
     * inline code, coupon codes, image URLs, and hyperlink URLs.
     */
    suspend fun translateRepostContent(
        repost: OverseasRepostContent,
        fallbackOffer: OverseasDealOffer? = null,
    ): OverseasRepostContent = withContext(ioDispatcher) {
        val rawTitleCn = OverseasForumPostFormatter.decodeHtmlEntities(repost.titleCn.trim())
        val fallbackTitle = OverseasForumPostFormatter.decodeHtmlEntities(fallbackOffer?.title?.trim().orEmpty())
        val sourceTitle = OverseasForumPostFormatter.decodeHtmlEntities(
            repost.title.trim()
                .ifBlank { fallbackOffer?.originalTitle?.trim().orEmpty() }
                .ifBlank { fallbackTitle },
        )

        val resolvedTitleCn = when {
            rawTitleCn.isNotBlank() && VpsGlossaryTranslator.containsCjk(rawTitleCn) -> rawTitleCn
            fallbackTitle.isNotBlank() && VpsGlossaryTranslator.containsCjk(fallbackTitle) -> fallbackTitle
            sourceTitle.isNotBlank() -> {
                translateText(
                    text = sourceTitle,
                    forceRefresh = false,
                    preserveMarkdown = false,
                ).translatedText.ifBlank { sourceTitle }
            }
            else -> ""
        }

        val rawBody = OverseasForumPostFormatter.cleanAndNormalizeMarkdown(
            OverseasForumPostFormatter.extractRawMainBody(repost),
        )
        val translatedBody = if (rawBody.isNotBlank() && needsChineseTranslation(rawBody)) {
            translateMarkdown(
                markdown = rawBody,
                forceRefresh = false,
            ).translatedText.ifBlank { rawBody }
        } else {
            rawBody
        }

        val translatedLinks = repost.links.map { link ->
            val label = OverseasForumPostFormatter.decodeHtmlEntities(link.text.trim())
            if (label.isNotBlank() &&
                !label.startsWith("http://", ignoreCase = true) &&
                !label.startsWith("https://", ignoreCase = true) &&
                !VpsGlossaryTranslator.containsCjk(label) &&
                needsChineseTranslation(label)
            ) {
                val zhLabel = VpsGlossaryTranslator.translateTitleOffline(label)
                val finalLabel = if (VpsGlossaryTranslator.containsCjk(zhLabel)) {
                    zhLabel
                } else {
                    translateText(label, forceRefresh = false, preserveMarkdown = false)
                        .translatedText
                        .ifBlank { label }
                }
                OverseasLabeledLink(text = finalLabel, url = link.url.trim())
            } else {
                link.copy(text = label, url = link.url.trim())
            }
        }

        repost.copy(
            titleCn = resolvedTitleCn,
            bodyMarkdown = translatedBody,
            links = translatedLinks,
        )
    }

    /**
     * Translates structured Markdown content in chunks while strictly preserving Markdown headings,
     * list/quote prefixes, tables, fenced code blocks, inline code, promo codes, images, and link URLs.
     */
    suspend fun translateMarkdown(
        markdown: String,
        forceRefresh: Boolean = false,
    ): TranslationResult = withContext(ioDispatcher) {
        val normalized = OverseasForumPostFormatter.cleanAndNormalizeMarkdown(markdown)
        if (normalized.isEmpty()) {
            return@withContext TranslationResult("", TranslationTier.NONE)
        }

        val contentKey = sha256Hex("md_v2:$normalized")
        if (!forceRefresh) {
            val cached = readAcceptableCacheEntry(contentKey)
            if (cached != null) return@withContext cached
        }

        val segments = splitMarkdownIntoSegments(normalized)
        val hasTranslatable = segments.any { it is MarkdownSegment.TranslatableBlock }
        if (!hasTranslatable) {
            return@withContext TranslationResult(normalized, TranslationTier.NONE)
        }

        var dominantTier = TranslationTier.OFFLINE_GLOSSARY
        var usedOnlineTier = false
        val renderedSegments = ArrayList<String>(segments.size)

        for (segment in segments) {
            when (segment) {
                is MarkdownSegment.Passthrough -> renderedSegments += segment.text
                is MarkdownSegment.TranslatableBlock -> {
                    val blockRes = translateProtectedMarkdownChunk(segment.rawMarkdown)
                    if (!usedOnlineTier && blockRes.tier == TranslationTier.GOOGLE_GTX) {
                        dominantTier = blockRes.tier
                        usedOnlineTier = true
                    }
                    renderedSegments += blockRes.translatedText
                }
            }
        }

        val finalMarkdown = OverseasForumPostFormatter.cleanAndNormalizeMarkdown(
            renderedSegments.joinToString("\n"),
        )
        writeCacheEntry(contentKey, finalMarkdown, dominantTier)
        TranslationResult(finalMarkdown, dominantTier, fromCache = false)
    }

    /**
     * Translates a single piece of text (such as a thread title) through Google GTX + offline glossary fallback + SHA-256 disk cache.
     */
    suspend fun translateText(
        text: String,
        forceRefresh: Boolean = false,
        preserveMarkdown: Boolean = false,
        offlineFallback: () -> String = { VpsGlossaryTranslator.translateTitleOffline(text) },
    ): TranslationResult = withContext(ioDispatcher) {
        if (preserveMarkdown) {
            return@withContext translateMarkdown(
                markdown = text,
                forceRefresh = forceRefresh,
            )
        }

        val normalized = OverseasForumPostFormatter.decodeHtmlEntities(text).trim()
        if (normalized.isEmpty()) {
            return@withContext TranslationResult("", TranslationTier.NONE)
        }

        val contentKey = sha256Hex(normalized)
        if (!forceRefresh) {
            val cached = readAcceptableCacheEntry(contentKey)
            if (cached != null) return@withContext cached
        }

        val spans = ArrayList<MaskedSpan>()
        val masked = maskInlineSpans(normalized, spans)

        val rawTranslated = translateRawChunkThroughWaterfall(
            maskedText = masked,
            offlineFallback = { VpsGlossaryTranslator.translateTitleOffline(masked) },
        )

        val polishedMasked = VpsGlossaryTranslator.enhanceMachineTranslation(rawTranslated.translatedText)
        val restored = unmaskInlineSpans(polishedMasked, spans)
            .replace(Regex("""[ \t]{2,}"""), " ")
            .trim()
            .ifBlank { offlineFallback().ifBlank { normalized } }

        writeCacheEntry(contentKey, restored, rawTranslated.tier)
        TranslationResult(restored, rawTranslated.tier, fromCache = false)
    }

    private suspend fun translateProtectedMarkdownChunk(
        chunkMarkdown: String,
    ): TranslationResult {
        val lines = chunkMarkdown.lines()
        val spans = ArrayList<MaskedSpan>()
        val nonBlankLineIndices = ArrayList<Int>(lines.size)
        val nonBlankPrefixes = ArrayList<String>(lines.size)
        val maskedNonBlankBodies = ArrayList<String>(lines.size)

        for ((index, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            val (prefix, body) = splitMarkdownLinePrefix(line)
            nonBlankLineIndices += index
            nonBlankPrefixes += prefix
            maskedNonBlankBodies += maskInlineSpans(body, spans)
        }

        if (maskedNonBlankBodies.isEmpty()) {
            return TranslationResult(chunkMarkdown, TranslationTier.NONE, fromCache = false)
        }

        val joinedForTranslation = maskedNonBlankBodies.joinToString("\n")
        val rawOut = translateRawChunkThroughWaterfall(
            maskedText = joinedForTranslation,
            offlineFallback = { VpsGlossaryTranslator.translateMarkdownOffline(joinedForTranslation) },
        )

        val translatedNonBlank = rawOut.translatedText
            .lines()
            .filter { it.isNotBlank() }

        val rebuilt = if (translatedNonBlank.size == nonBlankLineIndices.size) {
            val outputLines = MutableList(lines.size) { "" }
            for (k in nonBlankLineIndices.indices) {
                val targetLineIdx = nonBlankLineIndices[k]
                val polished = VpsGlossaryTranslator.enhanceMachineTranslation(translatedNonBlank[k])
                val restored = unmaskInlineSpans(polished, spans)
                outputLines[targetLineIdx] = "${nonBlankPrefixes[k]}${restored.trimStart()}"
            }
            outputLines.joinToString("\n")
        } else {
            val polished = rawOut.translatedText.lines().joinToString("\n") { ln ->
                if (ln.isBlank()) "" else VpsGlossaryTranslator.enhanceMachineTranslation(ln)
            }
            unmaskInlineSpans(polished, spans)
        }

        return TranslationResult(
            translatedText = repairMarkdownLineSyntax(rebuilt),
            tier = rawOut.tier,
            fromCache = false,
        )
    }

    private suspend fun translateRawChunkThroughWaterfall(
        maskedText: String,
        offlineFallback: () -> String,
    ): TranslationResult {
        val gtxResult = runCatching {
            networkSemaphore.withPermit {
                translateViaGoogleGtx(maskedText)
            }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()

        if (!gtxResult.isNullOrBlank()) {
            return TranslationResult(gtxResult, TranslationTier.GOOGLE_GTX, fromCache = false)
        }

        val offlineText = offlineFallback().ifBlank { maskedText }
        return TranslationResult(offlineText, TranslationTier.OFFLINE_GLOSSARY, fromCache = false)
    }

    private fun splitMarkdownIntoSegments(markdown: String): List<MarkdownSegment> {
        val segments = ArrayList<MarkdownSegment>()
        val currentTranslatable = ArrayList<String>()
        var currentChars = 0

        fun flushTranslatable() {
            if (currentTranslatable.isNotEmpty()) {
                segments += MarkdownSegment.TranslatableBlock(currentTranslatable.joinToString("\n"))
                currentTranslatable.clear()
                currentChars = 0
            }
        }

        val lines = markdown.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // 1. Fenced code block -> passthrough verbatim
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                flushTranslatable()
                val fenceMarker = if (trimmed.startsWith("```")) "```" else "~~~"
                val codeBlock = ArrayList<String>()
                codeBlock += line
                i++
                while (i < lines.size) {
                    val codeLine = lines[i]
                    codeBlock += codeLine
                    if (codeLine.trimStart().startsWith(fenceMarker)) {
                        i++
                        break
                    }
                    i++
                }
                segments += MarkdownSegment.Passthrough(codeBlock.joinToString("\n"))
                continue
            }

            // 2. Blank lines stay inside active translatable chunk to avoid splitting paragraphs into separate HTTP calls
            if (trimmed.isEmpty()) {
                if (currentTranslatable.isNotEmpty()) {
                    currentTranslatable += ""
                } else {
                    segments += MarkdownSegment.Passthrough("")
                }
                i++
                continue
            }

            // 3. Horizontal rules, standalone images, table separator rows, or already-Chinese lines -> passthrough
            if (HORIZONTAL_RULE_REGEX.matches(trimmed) ||
                STANDALONE_IMAGE_REGEX.matches(trimmed) ||
                TABLE_SEPARATOR_REGEX.matches(trimmed) ||
                !needsChineseTranslation(trimmed)
            ) {
                flushTranslatable()
                segments += MarkdownSegment.Passthrough(line)
                i++
                continue
            }

            // 4. Translatable line -> batch up to MAX_CHUNK_CHARS
            if (currentChars + line.length + 1 > MAX_CHUNK_CHARS && currentTranslatable.isNotEmpty()) {
                flushTranslatable()
            }
            currentTranslatable += line
            currentChars += line.length + 1
            i++
        }

        flushTranslatable()
        return segments
    }

    private fun splitMarkdownLinePrefix(line: String): Pair<String, String> {
        val match = LINE_STRUCTURAL_PREFIX_REGEX.find(line) ?: return "" to line
        val prefix = match.value
        val body = line.substring(prefix.length)
        return prefix to body
    }

    private fun maskInlineSpans(input: String, spans: MutableList<MaskedSpan>): String {
        if (input.isBlank()) return input

        // Order matters: inline code -> Markdown image -> Markdown link -> bare URL
        var working = INLINE_CODE_REGEX.replace(input) { m ->
            val id = spans.size
            spans += MaskedSpan.Verbatim(m.value)
            placeholderToken(id)
        }

        working = INLINE_IMAGE_REGEX.replace(working) { m ->
            val id = spans.size
            spans += MaskedSpan.Verbatim(m.value)
            placeholderToken(id)
        }

        working = INLINE_LINK_REGEX.replace(working) { m ->
            val label = m.groupValues[1]
            val url = m.groupValues[2].trim()
            val labelNeedsTranslation = !label.startsWith("http", ignoreCase = true) &&
                !VpsGlossaryTranslator.containsCjk(label) &&
                Regex("""[A-Za-z]{2,}""").containsMatchIn(label)
            if (labelNeedsTranslation) {
                val id = spans.size
                spans += MaskedSpan.LinkUrl(url)
                "[$label]${placeholderToken(id)}"
            } else {
                val id = spans.size
                spans += MaskedSpan.Verbatim(m.value)
                placeholderToken(id)
            }
        }

        working = BARE_URL_REGEX.replace(working) { m ->
            val id = spans.size
            spans += MaskedSpan.Verbatim(m.value)
            placeholderToken(id)
        }

        return working
    }

    private fun unmaskInlineSpans(input: String, spans: List<MaskedSpan>): String {
        if (spans.isEmpty() || input.isEmpty()) return input
        var current = input
        for (idx in spans.indices.reversed()) {
            when (val span = spans[idx]) {
                is MaskedSpan.Verbatim -> {
                    val tokenRegex = Regex("""(?i)KPH\s*${idx}\s*KPH""")
                    current = tokenRegex.replace(current) { Regex.escapeReplacement(span.raw) }
                }
                is MaskedSpan.LinkUrl -> {
                    val bracketedRegex = Regex("""[\[【]\s*([^\]】\n]+?)\s*[\]】]\s*(?i:KPH\s*${idx}\s*KPH)""")
                    current = if (bracketedRegex.containsMatchIn(current)) {
                        bracketedRegex.replace(current) { m ->
                            val cleanLabel = m.groupValues[1].trim()
                            "[$cleanLabel](${span.url})"
                        }
                    } else {
                        Regex("""(?i)KPH\s*${idx}\s*KPH""").replace(current) {
                            Regex.escapeReplacement("(${span.url})")
                        }
                    }
                }
            }
        }
        return current
    }

    private fun repairMarkdownLineSyntax(markdown: String): String {
        return markdown.lines().joinToString("\n") { line ->
            if (line.isBlank()) {
                ""
            } else {
                line
                    .replace(Regex("""^(\s*#{1,6})\s*"""), "$1 ")
                    .replace(Regex("""\*\*\s+([^*\n]+?)\s+\*\*"""), "**$1**")
                    .trimEnd()
            }
        }
    }

    private fun needsChineseTranslation(text: String): Boolean {
        val strippedCodeAndUrls = text
            .replace(INLINE_CODE_REGEX, " ")
            .replace(INLINE_IMAGE_REGEX, " ")
            .replace(BARE_URL_REGEX, " ")
        val englishMatches = Regex("""\b[A-Za-z]{2,}\b""")
            .findAll(strippedCodeAndUrls)
            .map { it.value.lowercase() }
            .filter { it !in TECHNICAL_PASSTHROUGH_TOKENS }
            .count()
        if (englishMatches == 0) return false
        val cjkCount = strippedCodeAndUrls.count { ch ->
            ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF'
        }
        return cjkCount == 0 || englishMatches >= 2
    }

    private fun placeholderToken(index: Int): String = "KPH${index}KPH"

    private suspend fun readAcceptableCacheEntry(contentKey: String): TranslationResult? {
        val cached = readCacheEntry(contentKey) ?: return null
        val cachedTier = runCatching { TranslationTier.valueOf(cached.tierName) }.getOrNull()
        return if (cachedTier == TranslationTier.GOOGLE_GTX && cached.translatedText.isNotBlank()) {
            TranslationResult(
                translatedText = cached.translatedText,
                tier = cachedTier,
                fromCache = true,
            )
        } else {
            null
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
                    try {
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
                    } catch (t: Throwable) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(t)
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
        private const val MAX_CHUNK_CHARS = 1800

        private val HORIZONTAL_RULE_REGEX = Regex("""^(?:-{3,}|\*{3,}|_{3,})$""")
        private val STANDALONE_IMAGE_REGEX = Regex("""^!\[[^\]]*\]\([^\)\s]+\)$""")
        private val TABLE_SEPARATOR_REGEX = Regex("""^\|?(?:\s*:?-{2,}:?\s*\|)+\s*:?-{2,}:?\s*\|?$""")
        private val LINE_STRUCTURAL_PREFIX_REGEX = Regex("""^\s*(?:#{1,6}\s+|(?:>\s*)+|(?:[-*+]|\d+\.)\s+)""")

        private val INLINE_CODE_REGEX = Regex("""`[^`\n]+`""")
        private val INLINE_IMAGE_REGEX = Regex("""!\[[^\]]*\]\([^\)\s]+\)""")
        private val INLINE_LINK_REGEX = Regex("""(?<!!)\[([^\]\n]+)\]\(([^\)\s]+)\)""")
        private val BARE_URL_REGEX = Regex("""https?://[^\s<>)\]]+""")

        private val TECHNICAL_PASSTHROUGH_TOKENS = setOf(
            "vps", "kvm", "lxc", "nvme", "ssd", "hdd", "vcpu", "cpu", "ecc", "ddr3", "ddr4", "ddr5",
            "gb", "mb", "tb", "gbps", "mbps", "gbit", "ipv4", "ipv6", "bgp", "asn", "ddos", "raid",
            "amd", "epyc", "ryzen", "intel", "xeon", "let", "leb", "usd", "eur", "gbp", "cny",
        )

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
