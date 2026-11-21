package com.nodeseek.app.data.trust

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Client-side token-bucket rate limiter (default ≤ 50 requests / 60s) and HTTP 429 cooldown
 * governor for `https://agent-api.nodeseek.org` (whose server limit is 60 requests / minute / key).
 *
 * Guarantees:
 * - At most [maxTokensPerMinute] outbound HTTP calls in any 60-second window, with full burst
 *   support up to [maxTokensPerMinute] so multi-match `/stats` lookups incur zero artificial delay.
 * - On HTTP 429, enters cooldown for `Retry-After` (or [defaultCooldownMillis] = 60s when absent).
 * - While in cooldown or when all tokens are exhausted, immediately returns [PermitResult.RateLimited]
 *   with `retryAfterSeconds` without issuing any network request.
 */
internal class OfficialTradeRateLimiter(
    private val maxTokensPerMinute: Int = DEFAULT_MAX_TOKENS_PER_MINUTE,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val defaultCooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
    private val maxCooldownMillis: Long = DEFAULT_MAX_COOLDOWN_MILLIS,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    sealed interface PermitResult {
        data object Granted : PermitResult
        data class RateLimited(val retryAfterSeconds: Long) : PermitResult
    }

    private val mutex = Mutex()
    private val consumedTokenTimestamps = ArrayDeque<Long>()
    private var cooldownUntilEpochMillis: Long = 0L

    init {
        require(maxTokensPerMinute >= 1) { "maxTokensPerMinute must be >= 1" }
        require(windowMillis >= 1_000L) { "windowMillis must be >= 1000" }
    }

    /**
     * Checks active 429 cooldown without consuming a token.
     */
    suspend fun getActiveCooldownSeconds(nowMillis: Long = clockMillis()): Long? = mutex.withLock {
        if (nowMillis < cooldownUntilEpochMillis) {
            millisToCeilSeconds(cooldownUntilEpochMillis - nowMillis)
        } else {
            null
        }
    }

    /**
     * Attempts to acquire 1 outbound request token. Never blocks on network or sleeps; returns
     * [PermitResult.RateLimited] immediately if in 429 cooldown or if [maxTokensPerMinute] tokens
     * have already been consumed within the current [windowMillis].
     */
    suspend fun tryAcquirePermit(nowMillis: Long = clockMillis()): PermitResult = mutex.withLock {
        if (nowMillis < cooldownUntilEpochMillis) {
            return@withLock PermitResult.RateLimited(
                retryAfterSeconds = millisToCeilSeconds(cooldownUntilEpochMillis - nowMillis),
            )
        }

        pruneExpiredTokensLocked(nowMillis)
        if (consumedTokenTimestamps.size >= maxTokensPerMinute) {
            val oldest = consumedTokenTimestamps.first()
            val availableAt = oldest + windowMillis
            val waitMillis = (availableAt - nowMillis).coerceAtLeast(1_000L)
            return@withLock PermitResult.RateLimited(
                retryAfterSeconds = millisToCeilSeconds(waitMillis),
            )
        }

        consumedTokenTimestamps.addLast(nowMillis)
        PermitResult.Granted
    }

    /**
     * Records an HTTP 429 rate-limit response from the server, activating a cooldown for
     * [retryAfterMillis] (clamped) or [defaultCooldownMillis] (60s) when `Retry-After` is absent.
     *
     * @return Remaining cooldown duration in whole seconds (>= 1).
     */
    suspend fun recordServerRateLimited(
        retryAfterMillis: Long?,
        nowMillis: Long = clockMillis(),
    ): Long = mutex.withLock {
        val durationMillis = (retryAfterMillis?.takeIf { it > 0L } ?: defaultCooldownMillis)
            .coerceIn(MIN_COOLDOWN_MILLIS, maxCooldownMillis)
        val targetUntil = maxOf(cooldownUntilEpochMillis, nowMillis + durationMillis)
        cooldownUntilEpochMillis = targetUntil
        millisToCeilSeconds(targetUntil - nowMillis)
    }

    /**
     * Clears active cooldown and per-key token usage when the user switches or clears their API Key.
     */
    suspend fun resetForKeyChange() = mutex.withLock {
        cooldownUntilEpochMillis = 0L
        consumedTokenTimestamps.clear()
    }

    private fun pruneExpiredTokensLocked(nowMillis: Long) {
        val cutoff = nowMillis - windowMillis
        while (consumedTokenTimestamps.isNotEmpty() && consumedTokenTimestamps.first() <= cutoff) {
            consumedTokenTimestamps.removeFirst()
        }
    }

    companion object {
        const val DEFAULT_MAX_TOKENS_PER_MINUTE: Int = 50
        const val DEFAULT_WINDOW_MILLIS: Long = 60_000L
        const val DEFAULT_COOLDOWN_MILLIS: Long = 60_000L
        const val DEFAULT_MAX_COOLDOWN_MILLIS: Long = 3_600_000L
        private const val MIN_COOLDOWN_MILLIS: Long = 1_000L

        internal fun millisToCeilSeconds(durationMillis: Long): Long =
            ((durationMillis.coerceAtLeast(1L) + 999L) / 1_000L).coerceAtLeast(1L)
    }
}
