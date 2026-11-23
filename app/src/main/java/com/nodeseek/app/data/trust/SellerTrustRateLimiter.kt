package com.nodeseek.app.data.trust

import com.nodeseek.app.data.network.NodeSeekApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Raw HTTP status error raised by the trust HTTP governance interceptor when an upstream server
 * returns `429 Too Many Requests` or `503 Service Unavailable`, preserving parsed `Retry-After`.
 */
internal class SellerTrustHttpStatusException(
    val statusCode: Int,
    val host: String,
    val retryAfterMillis: Long? = null,
    val serverMessage: String? = null,
    message: String = defaultStatusMessage(statusCode),
) : IOException(message) {
    companion object {
        private fun defaultStatusMessage(code: Int): String = when (code) {
            429 -> "请求过于频繁，触发官网限流保护 (429)"
            503 -> "官网服务繁忙，暂时不可用 (503)"
            else -> "官网请求异常 ($code)"
        }
    }
}

/**
 * Raised when a target host is inside an active rate-limit / overload cooldown window, or just
 * entered cooldown due to HTTP `429` / `503`.
 */
internal class SellerTrustRateLimitedException(
    val host: String,
    val statusCode: Int = 429,
    val cooldownUntilEpochMillis: Long,
    val retryAfterMillis: Long? = null,
    message: String = "请求过于频繁触发限流保护，请稍后重试",
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Raised when repeated request timeouts exhaust transient retries and place the host into a short
 * protective cooldown so subsequent clicks return immediately instead of hanging on sockets.
 */
internal class SellerTrustTimeoutException(
    val host: String,
    val cooldownUntilEpochMillis: Long,
    message: String = "请求官网超时，网络较慢或服务繁忙，请稍后重试",
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Parses RFC 9110 `Retry-After` header values in either delta-seconds (`"30"`) or RFC-1123 HTTP-date
 * (`"Fri, 20 Nov 2026 04:36:30 GMT"`) format into positive milliseconds.
 */
internal fun parseRetryAfterMillis(
    rawValue: String?,
    nowEpochMillis: Long,
): Long? {
    val trimmed = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    trimmed.toLongOrNull()?.let { seconds ->
        return (seconds.coerceAtLeast(1L)) * 1_000L
    }
    return runCatching {
        val targetMillis = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant()
            .toEpochMilli()
        (targetMillis - nowEpochMillis).coerceAtLeast(1_000L)
    }.getOrNull()
}

/**
 * Governs all outbound Seller Trust remote requests with:
 * - Global concurrency ceiling ([maxGlobalConcurrency])
 * - Per-site minimum interval / token-bucket pacing ([minSiteIntervalMillis])
 * - Transient retry with exponential backoff + jitter for timeouts & `503` without `Retry-After`
 * - Progressive exponential-backoff + jitter site cooldown respecting `Retry-After` on `429` / `503`
 */
internal class SellerTrustRateLimiter(
    val maxGlobalConcurrency: Int = DEFAULT_MAX_GLOBAL_CONCURRENCY,
    val minSiteIntervalMillis: Long = DEFAULT_MIN_SITE_INTERVAL_MILLIS,
    val maxTransientRetries: Int = DEFAULT_MAX_TRANSIENT_RETRIES,
    val retryBaseDelayMillis: Long = DEFAULT_RETRY_BASE_DELAY_MILLIS,
    val retryMaxDelayMillis: Long = DEFAULT_RETRY_MAX_DELAY_MILLIS,
    val baseCooldownMillis: Long = DEFAULT_BASE_COOLDOWN_MILLIS,
    val overloadBaseCooldownMillis: Long = DEFAULT_OVERLOAD_COOLDOWN_MILLIS,
    val maxCooldownMillis: Long = DEFAULT_MAX_COOLDOWN_MILLIS,
    val strikeResetAfterHealthyMillis: Long = DEFAULT_STRIKE_RESET_HEALTHY_MILLIS,
    val maxJitterRatio: Double = DEFAULT_MAX_JITTER_RATIO,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val jitterFraction: () -> Double = { ThreadLocalRandom.current().nextDouble() },
) {
    private class SiteState {
        var nextAllowedAtEpochMillis: Long = 0L
        var cooldownUntilEpochMillis: Long = 0L
        var consecutiveStrikes: Int = 0
        var lastCooldownStatusCode: Int = 429
    }

    private val globalSemaphore = Semaphore(maxGlobalConcurrency.coerceAtLeast(1))
    private val siteMutex = Mutex()
    private val sites = HashMap<String, SiteState>()

    private val activeCount = AtomicInteger(0)
    private val peakCount = AtomicInteger(0)

    val peakConcurrencyForTest: Int
        get() = peakCount.get()

    suspend fun getActiveCooldownUntil(
        host: String,
        nowMillis: Long = clockMillis(),
    ): Long {
        val key = normalizeHost(host)
        return siteMutex.withLock {
            val state = sites[key] ?: return@withLock 0L
            if (state.cooldownUntilEpochMillis > nowMillis) state.cooldownUntilEpochMillis else 0L
        }
    }

    suspend fun <T> execute(
        host: String,
        extraMinIntervalMillis: Long = 0L,
        block: suspend () -> T,
    ): T {
        val normalizedHost = normalizeHost(host)
        var attempt = 0

        while (true) {
            throwIfCoolingDown(normalizedHost)

            var pendingRetryBackoffMs: Long? = null

            globalSemaphore.acquire()
            try {
                val waitMs = reserveSlotOrThrowCooldown(
                    normalizedHost = normalizedHost,
                    extraMinIntervalMillis = extraMinIntervalMillis,
                )
                if (waitMs > 0L) {
                    delay(waitMs)
                }
                throwIfCoolingDown(normalizedHost)

                val currentActive = activeCount.incrementAndGet()
                peakCount.accumulateAndGet(currentActive, ::maxOf)
                try {
                    val result = block()
                    resetStrikesOnSuccess(normalizedHost)
                    return result
                } finally {
                    activeCount.decrementAndGet()
                }
            } catch (c: CancellationException) {
                throw c
            } catch (alreadyLimited: SellerTrustRateLimitedException) {
                throw alreadyLimited
            } catch (t: Throwable) {
                when (val classification = classifyFailure(t)) {
                    is FailureKind.RateLimited429 -> {
                        val until = recordCooldownStrike(
                            normalizedHost = normalizedHost,
                            statusCode = 429,
                            retryAfterMillis = classification.retryAfterMillis,
                            baseMillis = baseCooldownMillis,
                        )
                        throw SellerTrustRateLimitedException(
                            host = normalizedHost,
                            statusCode = 429,
                            cooldownUntilEpochMillis = until,
                            retryAfterMillis = classification.retryAfterMillis,
                            message = buildCooldownMessage(429, until, clockMillis()),
                            cause = t,
                        )
                    }

                    is FailureKind.ServiceUnavailable503 -> {
                        if (classification.retryAfterMillis != null || attempt >= maxTransientRetries) {
                            val until = recordCooldownStrike(
                                normalizedHost = normalizedHost,
                                statusCode = 503,
                                retryAfterMillis = classification.retryAfterMillis,
                                baseMillis = baseCooldownMillis,
                            )
                            throw SellerTrustRateLimitedException(
                                host = normalizedHost,
                                statusCode = 503,
                                cooldownUntilEpochMillis = until,
                                retryAfterMillis = classification.retryAfterMillis,
                                message = buildCooldownMessage(503, until, clockMillis()),
                                cause = t,
                            )
                        } else {
                            pendingRetryBackoffMs = computeBackoffWithJitter(
                                baseMillis = retryBaseDelayMillis,
                                attempt = attempt,
                                maxMillis = retryMaxDelayMillis,
                            )
                        }
                    }

                    FailureKind.Timeout -> {
                        if (attempt < maxTransientRetries) {
                            pendingRetryBackoffMs = computeBackoffWithJitter(
                                baseMillis = retryBaseDelayMillis,
                                attempt = attempt,
                                maxMillis = retryMaxDelayMillis,
                            )
                        } else {
                            val until = recordCooldownStrike(
                                normalizedHost = normalizedHost,
                                statusCode = 504,
                                retryAfterMillis = null,
                                baseMillis = overloadBaseCooldownMillis,
                            )
                            throw SellerTrustTimeoutException(
                                host = normalizedHost,
                                cooldownUntilEpochMillis = until,
                                cause = t,
                            )
                        }
                    }

                    FailureKind.Other -> throw t
                }
            } finally {
                globalSemaphore.release()
            }

            val sleepMs = pendingRetryBackoffMs ?: error("Unreachable retry state")
            if (sleepMs > 0L) {
                delay(sleepMs)
            }
            attempt++
        }
    }

    internal fun computeBackoffWithJitter(
        baseMillis: Long,
        attempt: Int,
        maxMillis: Long,
    ): Long {
        if (baseMillis <= 0L) return 0L
        val shift = attempt.coerceIn(0, 10)
        val rawExp = (baseMillis * (1L shl shift)).coerceAtMost(maxMillis)
        val jitter = computeJitterMillis(rawExp)
        return min(maxMillis, rawExp + jitter)
    }

    private fun computeJitterMillis(referenceMillis: Long): Long {
        if (referenceMillis <= 0L || maxJitterRatio <= 0.0) return 0L
        val fraction = jitterFraction().coerceIn(0.0, 1.0)
        return (referenceMillis.toDouble() * maxJitterRatio * fraction).roundToLong().coerceAtLeast(0L)
    }

    private suspend fun reserveSlotOrThrowCooldown(
        normalizedHost: String,
        extraMinIntervalMillis: Long,
    ): Long = siteMutex.withLock {
        val state = sites.getOrPut(normalizedHost) { SiteState() }
        val now = clockMillis()
        if (now < state.cooldownUntilEpochMillis) {
            throw SellerTrustRateLimitedException(
                host = normalizedHost,
                statusCode = state.lastCooldownStatusCode,
                cooldownUntilEpochMillis = state.cooldownUntilEpochMillis,
                message = buildCooldownMessage(state.lastCooldownStatusCode, state.cooldownUntilEpochMillis, now),
            )
        }
        val interval = (minSiteIntervalMillis + extraMinIntervalMillis).coerceAtLeast(0L)
        val scheduledAt = maxOf(now, state.nextAllowedAtEpochMillis)
        state.nextAllowedAtEpochMillis = scheduledAt + interval
        (scheduledAt - now).coerceAtLeast(0L)
    }

    private suspend fun throwIfCoolingDown(normalizedHost: String) {
        siteMutex.withLock {
            val state = sites[normalizedHost] ?: return@withLock
            val now = clockMillis()
            if (now < state.cooldownUntilEpochMillis) {
                throw SellerTrustRateLimitedException(
                    host = normalizedHost,
                    statusCode = state.lastCooldownStatusCode,
                    cooldownUntilEpochMillis = state.cooldownUntilEpochMillis,
                    message = buildCooldownMessage(state.lastCooldownStatusCode, state.cooldownUntilEpochMillis, now),
                )
            }
        }
    }

    private suspend fun resetStrikesOnSuccess(normalizedHost: String) {
        siteMutex.withLock {
            val state = sites[normalizedHost] ?: return@withLock
            if (state.consecutiveStrikes <= 0) return@withLock
            val now = clockMillis()
            // Keep strike counter alive right after cooldown ends so a lightweight /api/account/getInfo
            // success does not wipe strike history right before /search hits 429 again.
            if (now >= state.cooldownUntilEpochMillis + strikeResetAfterHealthyMillis) {
                state.consecutiveStrikes = 0
            }
        }
    }

    private suspend fun recordCooldownStrike(
        normalizedHost: String,
        statusCode: Int,
        retryAfterMillis: Long?,
        baseMillis: Long,
    ): Long = siteMutex.withLock {
        val state = sites.getOrPut(normalizedHost) { SiteState() }
        val now = clockMillis()
        state.consecutiveStrikes = (state.consecutiveStrikes + 1).coerceAtMost(8)
        state.lastCooldownStatusCode = statusCode

        val strikeIndex = (state.consecutiveStrikes - 1).coerceAtLeast(0)
        val expBase = (baseMillis.coerceAtLeast(1_000L) * (1L shl strikeIndex)).coerceAtMost(maxCooldownMillis)

        val coreDuration = if (retryAfterMillis != null && retryAfterMillis > 0L) {
            val clampedRetryAfter = retryAfterMillis.coerceIn(MIN_COOLDOWN_MILLIS, maxCooldownMillis)
            if (state.consecutiveStrikes == 1) {
                clampedRetryAfter
            } else {
                maxOf(clampedRetryAfter, expBase)
            }
        } else {
            expBase
        }

        val withJitter = min(maxCooldownMillis, coreDuration + computeJitterMillis(coreDuration))
        val targetUntil = maxOf(state.cooldownUntilEpochMillis, now + withJitter)
        state.cooldownUntilEpochMillis = targetUntil
        state.nextAllowedAtEpochMillis = maxOf(state.nextAllowedAtEpochMillis, targetUntil)
        targetUntil
    }

    private sealed interface FailureKind {
        data class RateLimited429(val retryAfterMillis: Long?) : FailureKind
        data class ServiceUnavailable503(val retryAfterMillis: Long?) : FailureKind
        data object Timeout : FailureKind
        data object Other : FailureKind
    }

    private fun classifyFailure(t: Throwable): FailureKind {
        when (t) {
            is SellerTrustHttpStatusException -> {
                if (t.statusCode == 429) return FailureKind.RateLimited429(t.retryAfterMillis)
                if (t.statusCode == 503) return FailureKind.ServiceUnavailable503(t.retryAfterMillis)
            }

            is NodeSeekApiException -> {
                val retryAfterMs = t.retryAfterSeconds?.takeIf { it > 0L }?.let { it * 1_000L }
                if (t.statusCode == 429) return FailureKind.RateLimited429(retryAfterMs)
                if (t.statusCode == 503) return FailureKind.ServiceUnavailable503(retryAfterMs)
            }

            is SocketTimeoutException -> return FailureKind.Timeout
            is InterruptedIOException -> return FailureKind.Timeout
        }

        val cause = t.cause
        if (cause != null && cause !== t) {
            val nested = classifyFailure(cause)
            if (nested != FailureKind.Other) return nested
        }

        if (t is IOException) {
            val msg = t.message.orEmpty().lowercase()
            if ("timeout" in msg || "timed out" in msg) {
                return FailureKind.Timeout
            }
        }
        return FailureKind.Other
    }

    private fun normalizeHost(raw: String): String =
        raw.trim().lowercase().ifEmpty { DEFAULT_SITE_HOST }

    private fun buildCooldownMessage(
        statusCode: Int,
        untilMillis: Long,
        nowMillis: Long,
    ): String {
        val remainingSeconds = ((untilMillis - nowMillis + 999L) / 1_000L).coerceAtLeast(1L)
        return if (statusCode == 503) {
            "官网服务繁忙（503），保护冷却中，请约 ${remainingSeconds} 秒后重试"
        } else {
            "请求过于频繁触发官网限流（429），请约 ${remainingSeconds} 秒后重试"
        }
    }

    companion object {
        const val DEFAULT_SITE_HOST: String = "www.nodeseek.com"
        const val DEFAULT_MAX_GLOBAL_CONCURRENCY: Int = 2
        const val DEFAULT_MIN_SITE_INTERVAL_MILLIS: Long = 350L
        const val DEFAULT_MAX_TRANSIENT_RETRIES: Int = 1
        const val DEFAULT_RETRY_BASE_DELAY_MILLIS: Long = 500L
        const val DEFAULT_RETRY_MAX_DELAY_MILLIS: Long = 4_000L
        const val DEFAULT_BASE_COOLDOWN_MILLIS: Long = 15_000L
        const val DEFAULT_OVERLOAD_COOLDOWN_MILLIS: Long = 10_000L
        const val DEFAULT_MAX_COOLDOWN_MILLIS: Long = 300_000L
        const val DEFAULT_STRIKE_RESET_HEALTHY_MILLIS: Long = 120_000L
        const val DEFAULT_MAX_JITTER_RATIO: Double = 0.25
        private const val MIN_COOLDOWN_MILLIS: Long = 1_000L
    }
}

/**
 * Cancellation-safe single-flight request coalescer keyed by [K].
 *
 * Concurrent callers requesting the same key share one underlying execution; cancelling one
 * caller cancels only that caller unless *all* waiting callers have cancelled.
 */
internal class KeyedSingleFlight<K, V>(
    dispatcher: CoroutineDispatcher,
) {
    private class Flight<V>(
        val deferred: Deferred<V>,
        var waiters: Int = 1,
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private val flights = HashMap<K, Flight<V>>()

    suspend fun execute(
        key: K,
        block: suspend () -> V,
    ): V {
        val flight: Flight<V> = mutex.withLock {
            val existing = flights[key]
            if (existing != null && existing.deferred.isActive) {
                existing.waiters++
                existing
            } else {
                lateinit var created: Flight<V>
                val deferred = scope.async {
                    try {
                        block()
                    } finally {
                        withContext(NonCancellable) {
                            mutex.withLock {
                                if (flights[key] === created) {
                                    flights.remove(key)
                                }
                            }
                        }
                    }
                }
                created = Flight(deferred = deferred, waiters = 1)
                flights[key] = created
                created
            }
        }

        return try {
            flight.deferred.await()
        } catch (c: CancellationException) {
            var shouldCancelShared = false
            withContext(NonCancellable) {
                mutex.withLock {
                    if (flights[key] === flight) {
                        flight.waiters--
                        if (flight.waiters <= 0) {
                            flights.remove(key)
                            shouldCancelShared = true
                        }
                    }
                }
            }
            if (shouldCancelShared) {
                flight.deferred.cancel(c)
            }
            throw c
        }
    }
}
