package com.nodeseek.app.data.activity

import com.nodeseek.app.data.overseas.ConditionalFetchResult
import com.nodeseek.app.data.overseas.OverseasApiCrypto
import com.nodeseek.app.domain.activity.ActivityCounts
import com.nodeseek.app.domain.activity.ActivityKind
import com.nodeseek.app.domain.activity.ActivityLabeledLink
import com.nodeseek.app.domain.activity.ActivityLotteryClassifier
import com.nodeseek.app.domain.activity.ActivityTimeStatus
import com.nodeseek.app.domain.activity.ForumWatchActivityDetail
import com.nodeseek.app.domain.activity.ForumWatchActivityItem
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
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

class ForumWatchApiException(
    val statusCode: Int,
    val errorCode: String = "",
    message: String,
    cause: Throwable? = null,
    val retryAfterSeconds: Long? = null,
) : IOException(message, cause)

data class ForumWatchActivitiesPageResult(
    val updatedAtEpochMillis: Long,
    val serverTimeEpochMillis: Long,
    val page: Int,
    val pageSize: Int,
    val total: Int,
    val hasMore: Boolean,
    val counts: ActivityCounts,
    val items: List<ForumWatchActivityItem>,
)

@Serializable
private data class ApiErrorDetailDto(
    val code: String = "",
    val message: String = "",
)

@Serializable
private data class ApiErrorEnvelopeDto(
    val ok: Boolean = false,
    val error: ApiErrorDetailDto? = null,
)

@Serializable
private data class ApiActivityCountsDto(
    val all: Int = 0,
    val ongoing: Int = 0,
    val upcoming: Int = 0,
    val ended: Int = 0,
)

@Serializable
private data class ApiActivityLabeledLinkDto(
    val text: String = "",
    val url: String = "",
)

@Serializable
private data class ApiActivityItemDto(
    val id: String = "",
    val title: String = "",
    val summary: String = "",
    val sourceUrl: String = "",
    val organizer: String = "",
    val organizerUrl: String? = null,
    val startsAtEpochMillis: Long? = null,
    val endsAtEpochMillis: Long? = null,
    val status: String = "ongoing",
    val kind: String = "event",
    val pinned: Boolean = false,
    val sourceId: String = "",
    val topicId: String? = null,
    val category: String = "",
    val categorySlug: String = "",
    val luckyUrl: String? = null,
    val drawFloor: Int? = null,
    val prize: String? = null,
    val participation: String? = null,
    val tags: List<String> = emptyList(),
    val createdAtEpochMillis: Long = 0L,
    val updatedAtEpochMillis: Long = 0L,
    val bodyMarkdown: String = "",
    val externalLinks: List<ApiActivityLabeledLinkDto> = emptyList(),
)

@Serializable
private data class ApiActivitiesPageDto(
    val ok: Boolean = true,
    val updatedAtEpochMillis: Long = 0L,
    val serverTimeEpochMillis: Long = 0L,
    val page: Int = 1,
    val pageSize: Int = 0,
    @SerialName("page_size") val pageSizeSnake: Int = 50,
    val total: Int = 0,
    val hasMore: Boolean? = null,
    @SerialName("has_more") val hasMoreSnake: Boolean = false,
    val counts: ApiActivityCountsDto = ApiActivityCountsDto(),
    val items: List<ApiActivityItemDto> = emptyList(),
)

@Serializable
private data class ApiActivityDetailWrapperDto(
    val ok: Boolean = true,
    val updatedAtEpochMillis: Long = 0L,
    val serverTimeEpochMillis: Long = 0L,
    val item: ApiActivityItemDto? = null,
)

/**
 * Authenticated & encrypted HTTP client for the ForumWatch Activity API (`v0.12.0+`):
 * - Signs every `/api/v1/activities` request with `HMAC-SHA256` (`X-Timestamp`, `X-Nonce`, `X-Signature`)
 * - Decrypts `AES-256-GCM` response envelopes via [OverseasApiCrypto]
 * - Supports `ETag` (`If-None-Match` -> `304 Not Modified`) zero-byte conditional polling
 * - Self-heals device clock skew strictly from HTTP `Date` headers (never from cached `serverTimeEpochMillis`)
 * - Automatically retries transient network / `408` / `429` / `5xx` errors with fresh `X-Nonce` & jitter
 */
class ForumWatchActivityApiClient(
    private val baseUrlProvider: () -> String = ForumWatchActivitySecretVault::resolveBaseUrl,
    masterSecretProvider: () -> ByteArray = ForumWatchActivitySecretVault::resolveMasterSecret,
    private val httpClient: OkHttpClient = ForumWatchActivitySecretVault.sharedPinnedHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val requireEncryptedResponse: Boolean = true,
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
    private val rateLimitedUntilSeconds = AtomicLong(0L)

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

    suspend fun fetchActivitiesPage(
        page: Int = 1,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        status: String = "all",
        kind: String? = null,
        sourceId: String? = null,
        query: String = "",
        updatedAfter: Long? = null,
    ): ForumWatchActivitiesPageResult =
        checkNotNull(
            fetchActivitiesPageConditional(
                page = page,
                pageSize = pageSize,
                status = status,
                kind = kind,
                sourceId = sourceId,
                query = query,
                updatedAfter = updatedAfter,
            ).data,
        )

    suspend fun fetchActivitiesPageConditional(
        page: Int = 1,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        status: String = "all",
        kind: String? = null,
        sourceId: String? = null,
        query: String = "",
        updatedAfter: Long? = null,
        ifNoneMatch: String? = null,
        ifModifiedSince: String? = null,
    ): ConditionalFetchResult<ForumWatchActivitiesPageResult> = withContext(ioDispatcher) {
        val builder = baseHttpUrl().newBuilder()
            .addPathSegments("api/v1/activities")
            .addQueryParameter("page", page.coerceAtLeast(1).toString())
            .addQueryParameter("page_size", pageSize.coerceIn(1, 100).toString())
            .addQueryParameter("status", status.trim().ifBlank { "all" })
        kind?.trim()?.takeIf { it.isNotEmpty() }?.let {
            builder.addQueryParameter("kind", it)
        }
        sourceId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            builder.addQueryParameter("source_id", it)
        }
        query.trim().takeIf { it.isNotEmpty() }?.let {
            builder.addQueryParameter("q", it)
        }
        if (updatedAfter != null && updatedAfter > 0L) {
            builder.addQueryParameter("updated_after", updatedAfter.toString())
        }

        val cond = executeSignedGet(
            url = builder.build(),
            ifNoneMatch = ifNoneMatch,
            ifModifiedSince = ifModifiedSince,
        )
        ConditionalFetchResult(
            data = cond.plaintextJson?.let(::parseActivitiesPageJson),
            notModified = cond.notModified,
            etag = cond.etag,
            lastModified = cond.lastModified,
        )
    }

    suspend fun fetchActivityDetail(activityId: String): ForumWatchActivityDetail =
        checkNotNull(fetchActivityDetailConditional(activityId = activityId).data)

    suspend fun fetchActivityDetailConditional(
        activityId: String,
        ifNoneMatch: String? = null,
        ifModifiedSince: String? = null,
    ): ConditionalFetchResult<ForumWatchActivityDetail> = withContext(ioDispatcher) {
        val cleanId = activityId.trim()
        require(cleanId.isNotEmpty()) { "activityId must not be blank" }
        val url = baseHttpUrl().newBuilder()
            .addPathSegments("api/v1/activities")
            .addPathSegment(cleanId)
            .build()
        val cond = executeSignedGet(
            url = url,
            ifNoneMatch = ifNoneMatch,
            ifModifiedSince = ifModifiedSince,
        )
        ConditionalFetchResult(
            data = cond.plaintextJson?.let(::parseActivityDetailJson),
            notModified = cond.notModified,
            etag = cond.etag,
            lastModified = cond.lastModified,
        )
    }

    private data class RawConditionalResponse(
        val notModified: Boolean,
        val plaintextJson: String?,
        val etag: String?,
        val lastModified: String?,
    )

    private suspend fun executeSignedGet(
        url: HttpUrl,
        ifNoneMatch: String? = null,
        ifModifiedSince: String? = null,
    ): RawConditionalResponse {
        val nowSec = nowSecondsProvider()
        val cooldownUntil = rateLimitedUntilSeconds.get()
        if (nowSec < cooldownUntil) {
            val remaining = (cooldownUntil - nowSec).coerceAtLeast(1L)
            throw ForumWatchApiException(
                statusCode = 429,
                errorCode = "RATE_LIMITED",
                message = "请求过于频繁，请 ${remaining} 秒后重试",
                retryAfterSeconds = remaining,
            )
        }

        val totalAttempts = maxAttempts.coerceAtLeast(1)
        var lastException: Throwable? = null
        var retriedClockSkew = false

        var attempt = 1
        while (attempt <= totalAttempts) {
            try {
                val request = buildSignedGetRequest(
                    url = url,
                    ifNoneMatch = ifNoneMatch,
                    ifModifiedSince = ifModifiedSince,
                )
                val response = executeRaw(request)

                // Calibrate clock offset strictly from live HTTP Date header when drift > 60s
                if (response.serverEpochSeconds != null) {
                    val localNow = nowSecondsProvider()
                    val drift = response.serverEpochSeconds - localNow
                    if (abs(drift) > 60L) {
                        clockOffsetSeconds.set(drift)
                    }
                }

                if (response.code == 304) {
                    rateLimitedUntilSeconds.set(0L)
                    return RawConditionalResponse(
                        notModified = true,
                        plaintextJson = null,
                        etag = response.etag ?: ifNoneMatch?.trim()?.takeIf { it.isNotEmpty() },
                        lastModified = response.lastModified ?: ifModifiedSince?.trim()?.takeIf { it.isNotEmpty() },
                    )
                }

                if (response.code == 200) {
                    rateLimitedUntilSeconds.set(0L)
                    val shouldDecrypt = requireEncryptedResponse ||
                        response.encryptedHeader.equals("AES-256-GCM", ignoreCase = true)
                    val plaintext = if (shouldDecrypt) {
                        OverseasApiCrypto.decryptEnvelope(
                            aesKey = aesKey,
                            rawEnvelopeJson = response.body,
                        )
                    } else {
                        response.body
                    }
                    return RawConditionalResponse(
                        notModified = false,
                        plaintextJson = plaintext,
                        etag = response.etag,
                        lastModified = response.lastModified,
                    )
                }

                // Self-heal device clock drift once if server returns 401 + HTTP Date header
                if (response.code == 401 && !retriedClockSkew && response.serverEpochSeconds != null) {
                    val localNow = nowSecondsProvider()
                    val drift = response.serverEpochSeconds - localNow
                    if (abs(drift) > 60L) {
                        clockOffsetSeconds.set(drift)
                        retriedClockSkew = true
                        continue
                    }
                }

                val apiError = parseApiError(response.code, response.body, response.retryAfterSeconds)
                if (response.code == 429 && response.retryAfterSeconds != null &&
                    response.retryAfterSeconds > MAX_INLINE_RETRY_AFTER_SEC
                ) {
                    updateRateLimitCooldown(response.retryAfterSeconds)
                    throw apiError
                }

                if (!isRetryableStatus(response.code) || attempt >= totalAttempts) {
                    if (response.code == 429) {
                        updateRateLimitCooldown(response.retryAfterSeconds ?: DEFAULT_RATE_LIMIT_COOLDOWN_SEC)
                    }
                    throw apiError
                }
                lastException = apiError
                val waitMs = computeRetryDelayMs(attempt, response.retryAfterSeconds)
                sleeper(waitMs)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t is ForumWatchApiException && (!isRetryableStatus(t.statusCode) ||
                        (t.statusCode == 429 && (t.retryAfterSeconds ?: 0L) > MAX_INLINE_RETRY_AFTER_SEC))
                ) {
                    throw t
                }
                lastException = t
                if (attempt >= totalAttempts) {
                    if (t is ForumWatchApiException && t.statusCode == 429) {
                        updateRateLimitCooldown(t.retryAfterSeconds ?: DEFAULT_RATE_LIMIT_COOLDOWN_SEC)
                    }
                    throw t
                }
                val waitMs = computeRetryDelayMs(attempt, retryAfterSeconds = null)
                sleeper(waitMs)
            }
            attempt++
        }

        throw (lastException ?: IOException("ForumWatch Activity API request failed"))
    }

    private fun updateRateLimitCooldown(retryAfterSeconds: Long) {
        val targetUntil = nowSecondsProvider() + retryAfterSeconds.coerceIn(1L, MAX_COOLDOWN_CAP_SEC)
        rateLimitedUntilSeconds.updateAndGet { prev -> maxOf(prev, targetUntil) }
    }

    internal fun buildSignedGetRequest(
        url: HttpUrl,
        ifNoneMatch: String? = null,
        ifModifiedSince: String? = null,
    ): Request {
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
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header(HEADER_TIMESTAMP, timestampSec)
            .header(HEADER_NONCE, nonce)
            .header(HEADER_SIGNATURE, signature)
        if (!ifNoneMatch.isNullOrBlank()) {
            builder.header("If-None-Match", ifNoneMatch.trim())
        }
        if (!ifModifiedSince.isNullOrBlank()) {
            builder.header("If-Modified-Since", ifModifiedSince.trim())
        }
        return builder.build()
    }

    private fun baseHttpUrl(): HttpUrl =
        baseUrlProvider().trim().trimEnd('/').toHttpUrl()

    private fun isRetryableStatus(code: Int): Boolean =
        code == 408 || code == 429 || code in 500..599

    private fun computeRetryDelayMs(attempt: Int, retryAfterSeconds: Long?): Long {
        if (retryAfterSeconds != null && retryAfterSeconds > 0L) {
            return (retryAfterSeconds * 1000L).coerceAtMost(MAX_RETRY_WAIT_MS)
        }
        if (baseRetryDelayMillis <= 0L) return 0L
        val exp = (baseRetryDelayMillis * (1L shl (attempt - 1).coerceAtMost(5)))
            .coerceAtMost(MAX_RETRY_WAIT_MS)
        val half = (exp / 2L).coerceAtLeast(1L)
        val jitter = ThreadLocalRandom.current().nextLong(0L, half + 1L)
        return (half + jitter).coerceAtMost(MAX_RETRY_WAIT_MS)
    }

    private data class RawHttpResponse(
        val code: Int,
        val body: String,
        val retryAfterSeconds: Long?,
        val serverEpochSeconds: Long?,
        val etag: String?,
        val lastModified: String?,
        val encryptedHeader: String?,
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
                        val etag = resp.header("ETag")?.trim()?.takeIf { it.isNotEmpty() }
                        val lastModified = resp.header("Last-Modified")?.trim()?.takeIf { it.isNotEmpty() }
                        val encryptedHeader = resp.header("X-Content-Encrypted")?.trim()?.takeIf { it.isNotEmpty() }
                        if (continuation.isActive) {
                            continuation.resume(
                                RawHttpResponse(
                                    code = resp.code,
                                    body = bodyText,
                                    retryAfterSeconds = retryAfter,
                                    serverEpochSeconds = serverSec,
                                    etag = etag,
                                    lastModified = lastModified,
                                    encryptedHeader = encryptedHeader,
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
        const val DEFAULT_PAGE_SIZE = 50
        private const val MAX_RETRY_WAIT_MS = 10_000L
        private const val MAX_INLINE_RETRY_AFTER_SEC = 10L
        private const val DEFAULT_RATE_LIMIT_COOLDOWN_SEC = 5L
        private const val MAX_COOLDOWN_CAP_SEC = 120L

        private val apiJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        internal fun parseActivitiesPageJson(rawJson: String): ForumWatchActivitiesPageResult {
            val dto = apiJson.decodeFromString<ApiActivitiesPageDto>(rawJson)
            val items = dto.items
                .filter { it.id.isNotBlank() && it.title.isNotBlank() }
                .map(::mapActivityItemDto)
            val resolvedPageSize = (if (dto.pageSize > 0) dto.pageSize else dto.pageSizeSnake).coerceAtLeast(1)
            val resolvedHasMore = dto.hasMore ?: dto.hasMoreSnake
            val referenceNow = dto.serverTimeEpochMillis.takeIf { it > 0L }
                ?: dto.updatedAtEpochMillis.takeIf { it > 0L }
                ?: System.currentTimeMillis()
            val resolvedCounts = if (items.isNotEmpty()) {
                var ongoing = 0
                var upcoming = 0
                var ended = 0
                var other = 0
                for (item in items) {
                    when (item.resolveLotteryStatusOrNull(referenceNow)) {
                        ActivityTimeStatus.ONGOING -> ongoing++
                        ActivityTimeStatus.UPCOMING -> upcoming++
                        ActivityTimeStatus.ENDED -> ended++
                        null -> other++
                    }
                }
                val lotteryTotal = ongoing + upcoming + ended
                val totalAll = dto.counts.all.coerceAtLeast(items.size)
                ActivityCounts(
                    all = totalAll,
                    ongoing = ongoing,
                    upcoming = upcoming,
                    ended = ended,
                    lottery = lotteryTotal,
                    other = (totalAll - lotteryTotal).coerceAtLeast(other),
                )
            } else {
                val ongoing = dto.counts.ongoing.coerceAtLeast(0)
                val upcoming = dto.counts.upcoming.coerceAtLeast(0)
                val ended = dto.counts.ended.coerceAtLeast(0)
                val lotteryTotal = ongoing + upcoming + ended
                val totalAll = dto.counts.all.coerceAtLeast(0)
                ActivityCounts(
                    all = totalAll,
                    ongoing = ongoing,
                    upcoming = upcoming,
                    ended = ended,
                    lottery = lotteryTotal,
                    other = (totalAll - lotteryTotal).coerceAtLeast(0),
                )
            }
            return ForumWatchActivitiesPageResult(
                updatedAtEpochMillis = dto.updatedAtEpochMillis.coerceAtLeast(0L),
                serverTimeEpochMillis = dto.serverTimeEpochMillis.coerceAtLeast(0L),
                page = dto.page.coerceAtLeast(1),
                pageSize = resolvedPageSize,
                total = dto.total.coerceAtLeast(items.size),
                hasMore = resolvedHasMore,
                counts = resolvedCounts,
                items = items,
            )
        }

        internal fun parseActivityDetailJson(rawJson: String): ForumWatchActivityDetail {
            val wrapper = apiJson.decodeFromString<ApiActivityDetailWrapperDto>(rawJson)
            val detailDto = wrapper.item ?: apiJson.decodeFromString<ApiActivityItemDto>(rawJson)
            if (detailDto.id.isBlank()) {
                throw ForumWatchApiException(
                    statusCode = 404,
                    errorCode = "NOT_FOUND",
                    message = "未找到对应的活动详情",
                )
            }
            val links = detailDto.externalLinks
                .filter { it.url.isNotBlank() }
                .map { ActivityLabeledLink(text = it.text.trim(), url = it.url.trim()) }
            val item = mapActivityItemDto(detailDto, preMappedLinks = links)
            return ActivityLotteryClassifier.normalizeDetail(
                ForumWatchActivityDetail(
                    item = item,
                    bodyMarkdown = detailDto.bodyMarkdown.trim(),
                    externalLinks = links,
                ),
            )
        }

        private fun mapActivityItemDto(
            dto: ApiActivityItemDto,
            preMappedLinks: List<ActivityLabeledLink>? = null,
        ): ForumWatchActivityItem {
            val links = preMappedLinks ?: dto.externalLinks
                .filter { it.url.isNotBlank() }
                .map { ActivityLabeledLink(text = it.text.trim(), url = it.url.trim()) }
            val rawItem = ForumWatchActivityItem(
                id = dto.id.trim(),
                title = dto.title.trim(),
                summary = dto.summary.trim(),
                sourceUrl = dto.sourceUrl.trim(),
                organizer = dto.organizer.trim(),
                startsAtEpochMillis = dto.startsAtEpochMillis?.takeIf { it > 0L },
                endsAtEpochMillis = dto.endsAtEpochMillis?.takeIf { it > 0L },
                organizerUrl = dto.organizerUrl?.trim()?.takeIf { it.isNotEmpty() },
                status = ActivityTimeStatus.fromWire(dto.status),
                kind = ActivityKind.fromWire(dto.kind),
                pinned = dto.pinned,
                sourceId = dto.sourceId.trim(),
                topicId = dto.topicId?.trim()?.takeIf { it.isNotEmpty() },
                category = dto.category.trim(),
                categorySlug = dto.categorySlug.trim(),
                luckyUrl = dto.luckyUrl?.trim()?.takeIf { it.isNotEmpty() },
                drawFloor = dto.drawFloor?.takeIf { it > 0 },
                prize = dto.prize?.trim()?.takeIf { it.isNotEmpty() },
                participation = dto.participation?.trim()?.takeIf { it.isNotEmpty() },
                tags = dto.tags.map { it.trim() }.filter { it.isNotEmpty() },
                createdAtEpochMillis = dto.createdAtEpochMillis.coerceAtLeast(0L),
                updatedAtEpochMillis = dto.updatedAtEpochMillis.coerceAtLeast(0L),
            )
            return ActivityLotteryClassifier.normalizeItem(
                item = rawItem,
                bodyMarkdown = dto.bodyMarkdown,
                externalLinks = links,
            )
        }

        private fun parseHttpDateSeconds(rawDate: String): Long? =
            runCatching {
                ZonedDateTime.parse(rawDate.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toEpochSecond()
            }.getOrNull()

        private fun parseApiError(
            statusCode: Int,
            rawBody: String,
            retryAfterSeconds: Long? = null,
        ): ForumWatchApiException {
            val dto = runCatching {
                if (rawBody.isBlank()) null else apiJson.decodeFromString<ApiErrorEnvelopeDto>(rawBody)
            }.getOrNull()?.error

            val code = dto?.code?.trim().orEmpty()
            val serverMessage = dto?.message?.trim().orEmpty()
            val friendlyMessage = when {
                statusCode == 429 && retryAfterSeconds != null && retryAfterSeconds > 0L ->
                    "请求过于频繁，请 ${retryAfterSeconds} 秒后重试"
                statusCode == 429 -> "请求过于频繁，请稍后重试"
                serverMessage.isNotBlank() -> serverMessage
                statusCode == 401 -> "活动接口鉴权失败（HTTP 401）"
                statusCode == 404 -> "未找到对应的活动记录"
                statusCode in 500..599 -> "活动聚合服务暂时不可用（HTTP $statusCode）"
                else -> "活动数据请求失败（HTTP $statusCode）"
            }
            return ForumWatchApiException(
                statusCode = statusCode,
                errorCode = code,
                message = friendlyMessage,
                retryAfterSeconds = retryAfterSeconds,
            )
        }
    }
}
