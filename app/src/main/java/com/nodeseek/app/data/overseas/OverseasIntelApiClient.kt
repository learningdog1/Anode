package com.nodeseek.app.data.overseas

import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasDetailSection
import com.nodeseek.app.domain.overseas.OverseasDetailStructured
import com.nodeseek.app.domain.overseas.OverseasLabeledLink
import com.nodeseek.app.domain.overseas.OverseasMediaImage
import com.nodeseek.app.domain.overseas.OverseasOfferDetail
import com.nodeseek.app.domain.overseas.OverseasRepostContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

class OverseasApiException(
    val statusCode: Int,
    val errorCode: String = "",
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

data class OverseasOffersPageResult(
    val page: Int,
    val pageSize: Int,
    val total: Int,
    val hasMore: Boolean,
    val items: List<OverseasDealOffer>,
)

@Serializable
private data class ApiErrorDetailDto(
    val code: String = "",
    val message: String = "",
)

@Serializable
private data class ApiErrorEnvelopeDto(
    val error: ApiErrorDetailDto? = null,
)

@Serializable
private data class ApiOfferListItemDto(
    val id: String = "",
    val title: String = "",
    @SerialName("original_title") val originalTitle: String = "",
    val summary: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    @SerialName("published_at_ms") val publishedAtMs: Long = 0L,
    val url: String = "",
    val merchant: String = "",
)

@Serializable
private data class ApiOffersPageDto(
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 20,
    val total: Int = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
    val items: List<ApiOfferListItemDto> = emptyList(),
)

@Serializable
private data class ApiSectionDto(
    val header: String = "",
    val items: List<String> = emptyList(),
)

@Serializable
private data class ApiLabeledLinkDto(
    val text: String = "",
    val url: String = "",
)

@Serializable
private data class ApiMediaImageDto(
    val alt: String = "",
    val url: String = "",
)

@Serializable
private data class ApiDetailStructuredDto(
    @SerialName("title_cn") val titleCn: String = "",
    val sections: List<ApiSectionDto> = emptyList(),
    @SerialName("order_links") val orderLinks: List<ApiLabeledLinkDto> = emptyList(),
    @SerialName("promo_code") val promoCode: String = "",
    val pros: List<String> = emptyList(),
    val cons: List<String> = emptyList(),
    @SerialName("suitable_for") val suitableFor: String = "",
    @SerialName("ai_comment") val aiComment: String = "",
    @SerialName("view_count") val viewCount: String = "",
    @SerialName("comment_count") val commentCount: String = "",
    @SerialName("ai_model") val aiModel: String = "",
)

@Serializable
private data class ApiOfferDetailDto(
    val id: String = "",
    val title: String = "",
    @SerialName("original_title") val originalTitle: String = "",
    val merchant: String = "",
    val author: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    @SerialName("published_at_ms") val publishedAtMs: Long = 0L,
    val url: String = "",
    @SerialName("tg_markdown") val tgMarkdown: String = "",
    @SerialName("tg_html") val tgHtml: String = "",
    @SerialName("tg_plain_text") val tgPlainText: String = "",
    val structured: ApiDetailStructuredDto? = null,
)

@Serializable
private data class ApiOfferRepostDto(
    val id: String = "",
    val title: String = "",
    @SerialName("title_cn") val titleCn: String = "",
    val merchant: String = "",
    val author: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    @SerialName("published_at_ms") val publishedAtMs: Long = 0L,
    val url: String = "",
    @SerialName("body_markdown") val bodyMarkdown: String = "",
    @SerialName("body_text") val bodyText: String = "",
    @SerialName("body_html") val bodyHtml: String = "",
    val images: List<ApiMediaImageDto> = emptyList(),
    val links: List<ApiLabeledLinkDto> = emptyList(),
    @SerialName("suggested_category_slug") val suggestedCategorySlug: String = "info",
)

/**
 * Authenticated & encrypted HTTP client for the LowEndTalk Promotion Monitor API:
 * - Signs every `/api/v1/` request with `HMAC-SHA256` (`X-Timestamp`, `X-Nonce`, `X-Signature`)
 * - Decrypts `AES-256-GCM` response envelopes
 * - Automatically retries transient network / 5xx / 429 failures with exponential backoff + jitter
 * - Self-heals device clock skew using HTTP `Date` headers if a 401 timestamp error occurs
 */
class OverseasIntelApiClient(
    private val baseUrlProvider: () -> String = OverseasApiSecretVault::resolveBaseUrl,
    masterSecretProvider: () -> ByteArray = OverseasApiSecretVault::resolveMasterSecret,
    private val httpClient: OkHttpClient = OverseasApiSecretVault.buildPinnedHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxAttempts: Int = 3,
    private val baseRetryDelayMillis: Long = 400L,
    private val nowSecondsProvider: () -> Long = { System.currentTimeMillis() / 1000L },
    private val nonceGenerator: () -> String = { OverseasApiCrypto.generateNonce() },
    private val sleeper: suspend (Long) -> Unit = { ms -> if (ms > 0L) delay(ms) },
) {
    private val hmacKey: ByteArray by lazy {
        val raw = masterSecretProvider()
        try {
            OverseasApiCrypto.deriveHmacKey(raw)
        } finally {
            raw.fill(0)
        }
    }

    private val aesKey: ByteArray by lazy {
        val raw = masterSecretProvider()
        try {
            OverseasApiCrypto.deriveAesKey(raw)
        } finally {
            raw.fill(0)
        }
    }

    private val clockOffsetSeconds = AtomicLong(0L)

    suspend fun checkHealth(): Boolean = withContext(ioDispatcher) {
        val url = baseHttpUrl().newBuilder()
            .addPathSegment("healthz")
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .build()
        val raw = executeRaw(request)
        raw.code == 200 && raw.body.contains("ok", ignoreCase = true)
    }

    suspend fun fetchOffersPage(
        page: Int = 1,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        query: String = "",
    ): OverseasOffersPageResult = withContext(ioDispatcher) {
        val builder = baseHttpUrl().newBuilder()
            .addPathSegments("api/v1/offers")
            .addQueryParameter("page", page.coerceAtLeast(1).toString())
            .addQueryParameter("page_size", pageSize.coerceIn(1, 100).toString())
        val trimmedQuery = query.trim()
        if (trimmedQuery.isNotEmpty()) {
            builder.addQueryParameter("q", trimmedQuery)
        }
        val plaintextJson = executeSignedEncryptedGet(builder.build())
        parseOffersPageJson(plaintextJson)
    }

    suspend fun fetchOfferDetail(offerId: String): OverseasOfferDetail = withContext(ioDispatcher) {
        require(offerId.isNotBlank()) { "offerId must not be blank" }
        val url = baseHttpUrl().newBuilder()
            .addPathSegments("api/v1/offers")
            .addPathSegment(offerId.trim())
            .build()
        val plaintextJson = executeSignedEncryptedGet(url)
        parseOfferDetailJson(plaintextJson)
    }

    suspend fun fetchOfferRepost(offerId: String): OverseasRepostContent = withContext(ioDispatcher) {
        require(offerId.isNotBlank()) { "offerId must not be blank" }
        val url = baseHttpUrl().newBuilder()
            .addPathSegments("api/v1/offers")
            .addPathSegment(offerId.trim())
            .addPathSegment("repost")
            .build()
        val plaintextJson = executeSignedEncryptedGet(url)
        parseOfferRepostJson(plaintextJson)
    }

    private suspend fun executeSignedEncryptedGet(url: HttpUrl): String {
        val totalAttempts = maxAttempts.coerceAtLeast(1)
        var lastException: Throwable? = null
        var retriedClockSkew = false

        var attempt = 1
        while (attempt <= totalAttempts) {
            try {
                val request = buildSignedGetRequest(url)
                val response = executeRaw(request)
                if (response.code == 200) {
                    return OverseasApiCrypto.decryptEnvelope(
                        aesKey = aesKey,
                        rawEnvelopeJson = response.body,
                    )
                }

                // Self-heal device clock drift once if server returns 401 + Date header
                if (response.code == 401 && !retriedClockSkew && response.serverEpochSeconds != null) {
                    val localNow = nowSecondsProvider()
                    val drift = response.serverEpochSeconds - localNow
                    if (abs(drift) > 60L) {
                        clockOffsetSeconds.set(drift)
                        retriedClockSkew = true
                        continue
                    }
                }

                val apiError = parseApiError(response.code, response.body)
                if (!isRetryableStatus(response.code) || attempt >= totalAttempts) {
                    throw apiError
                }
                lastException = apiError
                val waitMs = computeRetryDelayMs(attempt, response.retryAfterSeconds)
                sleeper(waitMs)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t is OverseasApiException && !isRetryableStatus(t.statusCode)) {
                    throw t
                }
                lastException = t
                if (attempt >= totalAttempts) {
                    throw t
                }
                val waitMs = computeRetryDelayMs(attempt, retryAfterSeconds = null)
                sleeper(waitMs)
            }
            attempt++
        }

        throw (lastException ?: IOException("Overseas API request failed"))
    }

    internal fun buildSignedGetRequest(url: HttpUrl): Request {
        val method = "GET"
        val pathWithQuery = url.encodedPath + (url.encodedQuery?.let { "?$it" }.orEmpty())
        val timestampSec = (nowSecondsProvider() + clockOffsetSeconds.get()).toString()
        val nonce = nonceGenerator()
        val signature = OverseasApiCrypto.signRequest(
            hmacKey = hmacKey,
            method = method,
            pathWithQuery = pathWithQuery,
            timestampSec = timestampSec,
            nonce = nonce,
        )
        return Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header(HEADER_TIMESTAMP, timestampSec)
            .header(HEADER_NONCE, nonce)
            .header(HEADER_SIGNATURE, signature)
            .build()
    }

    private fun baseHttpUrl(): HttpUrl =
        baseUrlProvider().trim().trimEnd('/').toHttpUrl()

    private fun isRetryableStatus(code: Int): Boolean =
        code == 408 || code == 429 || code in 500..599

    private fun computeRetryDelayMs(attempt: Int, retryAfterSeconds: Long?): Long {
        if (retryAfterSeconds != null && retryAfterSeconds > 0L) {
            return (retryAfterSeconds * 1000L).coerceAtMost(MAX_RETRY_WAIT_MS)
        }
        val exp = baseRetryDelayMillis * (1L shl (attempt - 1).coerceAtMost(4))
        val jitter = if (baseRetryDelayMillis > 0L) {
            ThreadLocalRandom.current().nextLong(0L, (baseRetryDelayMillis / 2L).coerceAtLeast(1L))
        } else {
            0L
        }
        return (exp + jitter).coerceAtMost(MAX_RETRY_WAIT_MS)
    }

    private data class RawHttpResponse(
        val code: Int,
        val body: String,
        val retryAfterSeconds: Long?,
        val serverEpochSeconds: Long?,
    )

    private suspend fun executeRaw(request: Request): RawHttpResponse =
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
                        val bodyText = resp.body?.string().orEmpty()
                        val retryAfter = resp.header("Retry-After")?.trim()?.toLongOrNull()
                        val serverSec = resp.header("Date")?.let(::parseHttpDateSeconds)
                        if (continuation.isActive) {
                            continuation.resume(
                                RawHttpResponse(
                                    code = resp.code,
                                    body = bodyText,
                                    retryAfterSeconds = retryAfter,
                                    serverEpochSeconds = serverSec,
                                ),
                            )
                        }
                    }
                }
            })
        }

    companion object {
        const val HEADER_TIMESTAMP = "X-Timestamp"
        const val HEADER_NONCE = "X-Nonce"
        const val HEADER_SIGNATURE = "X-Signature"
        const val DEFAULT_PAGE_SIZE = 20
        private const val MAX_RETRY_WAIT_MS = 5_000L

        private val apiJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        internal fun parseOffersPageJson(rawJson: String): OverseasOffersPageResult {
            val dto = apiJson.decodeFromString<ApiOffersPageDto>(rawJson)
            val offers = dto.items
                .filter { it.id.isNotBlank() && (it.title.isNotBlank() || it.originalTitle.isNotBlank()) }
                .map { item ->
                    val displayTitle = item.title.trim().ifBlank { item.originalTitle.trim() }
                    val epochMs = resolveEpochMillis(item.publishedAtMs, item.publishedAt)
                    OverseasDealOffer(
                        id = item.id.trim(),
                        title = displayTitle,
                        originalTitle = item.originalTitle.trim().ifBlank { displayTitle },
                        summary = item.summary.trim(),
                        merchant = item.merchant.trim(),
                        sourceLink = item.url.trim(),
                        publishedAtEpochMillis = epochMs,
                        publishedAtIso = item.publishedAt.trim(),
                    )
                }
            return OverseasOffersPageResult(
                page = dto.page.coerceAtLeast(1),
                pageSize = dto.pageSize.coerceAtLeast(1),
                total = dto.total.coerceAtLeast(offers.size),
                hasMore = dto.hasMore,
                items = offers,
            )
        }

        internal fun parseOfferDetailJson(rawJson: String): OverseasOfferDetail {
            val dto = apiJson.decodeFromString<ApiOfferDetailDto>(rawJson)
            val structured = dto.structured?.let { st ->
                OverseasDetailStructured(
                    titleCn = st.titleCn.trim(),
                    sections = st.sections.map { sec ->
                        OverseasDetailSection(
                            header = sec.header.trim(),
                            items = sec.items.map { it.trim() }.filter { it.isNotEmpty() },
                        )
                    },
                    orderLinks = st.orderLinks
                        .filter { it.url.isNotBlank() }
                        .map { OverseasLabeledLink(it.text.trim(), it.url.trim()) },
                    promoCode = st.promoCode.trim(),
                    pros = st.pros.map { it.trim() }.filter { it.isNotEmpty() },
                    cons = st.cons.map { it.trim() }.filter { it.isNotEmpty() },
                    suitableFor = st.suitableFor.trim(),
                    aiComment = st.aiComment.trim(),
                    viewCount = st.viewCount.trim(),
                    commentCount = st.commentCount.trim(),
                    aiModel = st.aiModel.trim(),
                )
            }

            val resolvedTitle = dto.title.trim()
                .ifBlank { structured?.titleCn.orEmpty() }
                .ifBlank { dto.originalTitle.trim() }

            val resolvedMarkdown = dto.tgMarkdown.trim()
                .ifBlank { synthesizeMarkdownFromStructuredOrFallback(resolvedTitle, dto.url.trim(), structured, dto.tgHtml, dto.tgPlainText) }

            return OverseasOfferDetail(
                id = dto.id.trim(),
                title = resolvedTitle,
                originalTitle = dto.originalTitle.trim(),
                merchant = dto.merchant.trim(),
                author = dto.author.trim(),
                publishedAtEpochMillis = resolveEpochMillis(dto.publishedAtMs, dto.publishedAt),
                publishedAtIso = dto.publishedAt.trim(),
                sourceLink = dto.url.trim(),
                tgMarkdown = resolvedMarkdown,
                tgHtml = dto.tgHtml,
                tgPlainText = dto.tgPlainText,
                structured = structured,
            )
        }

        internal fun parseOfferRepostJson(rawJson: String): OverseasRepostContent {
            val dto = apiJson.decodeFromString<ApiOfferRepostDto>(rawJson)
            return OverseasRepostContent(
                id = dto.id.trim(),
                title = dto.title.trim(),
                titleCn = dto.titleCn.trim(),
                merchant = dto.merchant.trim(),
                author = dto.author.trim(),
                publishedAtEpochMillis = resolveEpochMillis(dto.publishedAtMs, dto.publishedAt),
                publishedAtIso = dto.publishedAt.trim(),
                sourceLink = dto.url.trim(),
                bodyMarkdown = dto.bodyMarkdown.trim(),
                bodyText = dto.bodyText.trim(),
                bodyHtml = dto.bodyHtml.trim(),
                images = dto.images
                    .filter { it.url.isNotBlank() }
                    .map { OverseasMediaImage(it.alt.trim(), it.url.trim()) },
                links = dto.links
                    .filter { it.url.isNotBlank() }
                    .map { OverseasLabeledLink(it.text.trim(), it.url.trim()) },
                suggestedCategorySlug = dto.suggestedCategorySlug.trim().ifBlank { "info" },
            )
        }

        private fun synthesizeMarkdownFromStructuredOrFallback(
            title: String,
            sourceUrl: String,
            structured: OverseasDetailStructured?,
            tgHtml: String,
            tgPlainText: String,
        ): String {
            if (structured != null && (structured.sections.isNotEmpty() || structured.aiComment.isNotBlank())) {
                return buildString {
                    if (title.isNotBlank()) {
                        appendLine("## $title")
                        appendLine()
                    }
                    structured.sections.forEach { section ->
                        if (section.header.isNotBlank()) {
                            appendLine("### ${section.header}")
                        }
                        section.items.forEach { item ->
                            val clean = item.removePrefix("•").trim()
                            appendLine("- $clean")
                        }
                        appendLine()
                    }
                    if (structured.orderLinks.isNotEmpty()) {
                        appendLine("### 购买链接")
                        structured.orderLinks.forEachIndexed { idx, link ->
                            appendLine("- [${link.text.ifBlank { "下单链接 ${idx + 1}" }}](${link.url})")
                        }
                        appendLine()
                    }
                    if (structured.promoCode.isNotBlank()) {
                        appendLine("**优惠码**：`${structured.promoCode}`")
                        appendLine()
                    }
                    structured.pros.forEach { appendLine("- **优点**：$it") }
                    structured.cons.forEach { appendLine("- **缺点**：$it") }
                    if (structured.suitableFor.isNotBlank()) appendLine("- **适合**：${structured.suitableFor}")
                    if (structured.aiComment.isNotBlank()) appendLine("- **AI锐评**：${structured.aiComment}")
                    if (sourceUrl.isNotBlank()) {
                        appendLine()
                        appendLine("---")
                        appendLine("[查看原帖]($sourceUrl)")
                    }
                }.trim()
            }

            if (tgPlainText.isNotBlank()) {
                return tgPlainText.trim()
            }

            if (tgHtml.isNotBlank()) {
                return tgHtml
                    .replace(Regex("""<a\s+href=["']([^"']+)["'][^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE), "[$2]($1)")
                    .replace(Regex("""<(?:b|strong)>(.*?)</(?:b|strong)>""", RegexOption.IGNORE_CASE), "**$1**")
                    .replace(Regex("""<code>(.*?)</code>""", RegexOption.IGNORE_CASE), "`$1`")
                    .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                    .replace(Regex("""<[^>]+>"""), "")
                    .trim()
            }

            return title
        }

        internal fun resolveEpochMillis(epochMs: Long, isoString: String): Long {
            if (epochMs > 10_000_000_000L) return epochMs
            if (epochMs > 0L) return epochMs * 1000L
            val trimmed = isoString.trim()
            if (trimmed.isEmpty()) return 0L
            return runCatching { Instant.parse(trimmed).toEpochMilli() }
                .recoverCatching { OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
                .getOrDefault(0L)
        }

        private fun parseHttpDateSeconds(rawDate: String): Long? =
            runCatching {
                ZonedDateTime.parse(rawDate.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toEpochSecond()
            }.getOrNull()

        private fun parseApiError(statusCode: Int, rawBody: String): OverseasApiException {
            val dto = runCatching {
                if (rawBody.isBlank()) null else apiJson.decodeFromString<ApiErrorEnvelopeDto>(rawBody)
            }.getOrNull()?.error

            val code = dto?.code?.trim().orEmpty()
            val serverMessage = dto?.message?.trim().orEmpty()
            val friendlyMessage = when {
                statusCode == 429 -> "请求过于频繁，请稍后重试"
                serverMessage.isNotBlank() -> serverMessage
                statusCode == 401 -> "接口鉴权失败（HTTP 401）"
                statusCode == 404 -> "未找到对应的促销记录"
                statusCode in 500..599 -> "促销服务暂时不可用（HTTP $statusCode）"
                else -> "请求失败（HTTP $statusCode）"
            }
            return OverseasApiException(
                statusCode = statusCode,
                errorCode = code,
                message = friendlyMessage,
            )
        }
    }
}
