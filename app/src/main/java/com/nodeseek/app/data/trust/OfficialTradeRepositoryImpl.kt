package com.nodeseek.app.data.trust

import android.content.Context
import com.nodeseek.app.domain.trust.OfficialTradeLookup
import com.nodeseek.app.domain.trust.OfficialTradeRepository
import com.nodeseek.app.domain.trust.OfficialTradeUserStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.io.File

/**
 * Production [OfficialTradeRepository] coordinating:
 * - Encrypted local API Key persistence ([OfficialTradeKeyStore])
 * - Two-tier memory + disk caching ([OfficialTradeCacheStore]: 6h positive, 10m NotFound)
 * - Per-username cancellation-safe single-flight coalescing ([KeyedSingleFlight])
 * - Client token-bucket rate limiting (≤ 50 req/min) & HTTP 429 cooldown ([OfficialTradeRateLimiter])
 * - Duplicate-username resolution capped at the first [MAX_DUPLICATE_USER_STATS] matches
 */
class OfficialTradeRepositoryImpl internal constructor(
    private val apiClient: OfficialTradeApiClient,
    private val keyStore: OfficialTradeKeyStore,
    private val cacheStore: OfficialTradeCacheStore,
    private val rateLimiter: OfficialTradeRateLimiter = OfficialTradeRateLimiter(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : OfficialTradeRepository {

    private val singleFlight = KeyedSingleFlight<String, OfficialTradeLookup>(ioDispatcher)

    override val isConfigured: StateFlow<Boolean>
        get() = keyStore.isConfigured

    override suspend fun lookup(
        username: String,
        forceRefresh: Boolean,
    ): OfficialTradeLookup {
        val apiKey = keyStore.currentApiKey()
            ?: return OfficialTradeLookup.NotConfigured

        val cleanUsername = OfficialTradeCacheStore.normalizeCleanUsername(username)
        val normalizedKey = OfficialTradeCacheStore.normalizeUsernameKey(cleanUsername)
        if (normalizedKey.isEmpty()) {
            return OfficialTradeLookup.Failed("用户名不能为空")
        }

        if (!forceRefresh) {
            cacheStore.getFresh(cleanUsername, clockMillis())?.let { cached ->
                return cached
            }
        }

        rateLimiter.getActiveCooldownSeconds(clockMillis())?.let { remainingSeconds ->
            return OfficialTradeLookup.RateLimited(retryAfterSeconds = remainingSeconds)
        }

        return singleFlight.execute(normalizedKey) {
            performNetworkLookup(apiKey = apiKey, cleanUsername = cleanUsername)
        }
    }

    override suspend fun getCachedOnly(username: String): OfficialTradeLookup.Found? =
        cacheStore.getFreshPositiveOnly(username = username, nowMillis = clockMillis())

    override fun observeCachedOnly(username: String): Flow<OfficialTradeLookup.Found?> =
        cacheStore.observeFreshPositiveOnly(username = username)

    override suspend fun saveApiKey(key: String) {
        val changed = keyStore.saveApiKey(key)
        if (changed) {
            rateLimiter.resetForKeyChange()
        }
    }

    override suspend fun clearApiKey() {
        keyStore.clearApiKey()
        rateLimiter.resetForKeyChange()
    }

    override suspend fun verifyApiKey(key: String): OfficialTradeLookup {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            return OfficialTradeLookup.Failed("请输入 API Key")
        }
        if (!OfficialTradeKeyStore.isValidKeyFormat(trimmed)) {
            return OfficialTradeLookup.Failed("API Key 格式无效，必须以 ntk_ 开头")
        }

        rateLimiter.getActiveCooldownSeconds(clockMillis())?.let { remainingSeconds ->
            return OfficialTradeLookup.RateLimited(retryAfterSeconds = remainingSeconds)
        }

        when (val permit = rateLimiter.tryAcquirePermit(clockMillis())) {
            is OfficialTradeRateLimiter.PermitResult.RateLimited -> {
                return OfficialTradeLookup.RateLimited(retryAfterSeconds = permit.retryAfterSeconds)
            }
            OfficialTradeRateLimiter.PermitResult.Granted -> Unit
        }

        return try {
            // Single lightweight authenticated call; HTTP 200 proves the Bearer token is valid
            // without consuming extra quota or polluting seller lookup caches.
            apiClient.searchUsersByUsername(apiKey = trimmed, username = VERIFY_PROBE_USERNAME)
            OfficialTradeLookup.Found(
                matches = emptyList(),
                fetchedAtMillis = clockMillis(),
                fromCache = false,
            )
        } catch (c: CancellationException) {
            throw c
        } catch (apiEx: OfficialTradeApiException) {
            mapApiExceptionToLookup(apiEx)
        } catch (t: Throwable) {
            OfficialTradeLookup.Failed("测试连接失败，请稍后重试")
        }
    }

    override fun maskedApiKey(): String? = keyStore.maskedApiKey()

    private suspend fun performNetworkLookup(
        apiKey: String,
        cleanUsername: String,
    ): OfficialTradeLookup {
        when (val searchPermit = rateLimiter.tryAcquirePermit(clockMillis())) {
            is OfficialTradeRateLimiter.PermitResult.RateLimited -> {
                return OfficialTradeLookup.RateLimited(retryAfterSeconds = searchPermit.retryAfterSeconds)
            }
            OfficialTradeRateLimiter.PermitResult.Granted -> Unit
        }

        val summaries = try {
            apiClient.searchUsersByUsername(apiKey = apiKey, username = cleanUsername)
        } catch (c: CancellationException) {
            throw c
        } catch (apiEx: OfficialTradeApiException) {
            if (apiEx.kind == OfficialTradeErrorKind.NOT_FOUND) {
                cacheStore.putNotFound(cleanUsername, clockMillis())
                return OfficialTradeLookup.NotFound
            }
            return mapApiExceptionToLookup(apiEx)
        } catch (t: Throwable) {
            return OfficialTradeLookup.Failed("查询官方交易记录失败，请稍后重试")
        }

        if (summaries.isEmpty()) {
            cacheStore.putNotFound(cleanUsername, clockMillis())
            return OfficialTradeLookup.NotFound
        }

        val candidates = summaries.take(MAX_DUPLICATE_USER_STATS)
        val resolvedStats = ArrayList<OfficialTradeUserStats>(candidates.size)

        for (candidate in candidates) {
            when (val statsPermit = rateLimiter.tryAcquirePermit(clockMillis())) {
                is OfficialTradeRateLimiter.PermitResult.RateLimited -> {
                    return OfficialTradeLookup.RateLimited(retryAfterSeconds = statsPermit.retryAfterSeconds)
                }
                OfficialTradeRateLimiter.PermitResult.Granted -> Unit
            }

            try {
                val stats = apiClient.fetchUserStats(apiKey = apiKey, userId = candidate.id)
                resolvedStats += stats.copy(
                    username = stats.username.ifBlank { candidate.username.ifBlank { cleanUsername } },
                    avatarUrl = stats.avatarUrl ?: candidate.avatarUrl?.takeIf { it.isNotBlank() },
                )
            } catch (c: CancellationException) {
                throw c
            } catch (apiEx: OfficialTradeApiException) {
                if (apiEx.kind == OfficialTradeErrorKind.NOT_FOUND) {
                    // User deleted between /users list and /stats; skip and check remaining matches.
                    continue
                }
                return mapApiExceptionToLookup(apiEx)
            } catch (t: Throwable) {
                return OfficialTradeLookup.Failed("获取用户交易详情失败，请稍后重试")
            }
        }

        val fetchedAt = clockMillis()
        if (resolvedStats.isEmpty()) {
            cacheStore.putNotFound(cleanUsername, fetchedAt)
            return OfficialTradeLookup.NotFound
        }

        cacheStore.putFound(
            username = cleanUsername,
            matches = resolvedStats,
            nowMillis = fetchedAt,
        )
        return OfficialTradeLookup.Found(
            matches = resolvedStats,
            fetchedAtMillis = fetchedAt,
            fromCache = false,
        )
    }

    private suspend fun mapApiExceptionToLookup(apiEx: OfficialTradeApiException): OfficialTradeLookup {
        return when (apiEx.kind) {
            OfficialTradeErrorKind.UNAUTHORIZED -> OfficialTradeLookup.Unauthorized
            OfficialTradeErrorKind.NOT_FOUND -> OfficialTradeLookup.NotFound
            OfficialTradeErrorKind.RATE_LIMITED -> {
                val cooldownSeconds = rateLimiter.recordServerRateLimited(
                    retryAfterMillis = apiEx.retryAfterMillis,
                    nowMillis = clockMillis(),
                )
                OfficialTradeLookup.RateLimited(retryAfterSeconds = cooldownSeconds)
            }
            OfficialTradeErrorKind.NETWORK,
            OfficialTradeErrorKind.SERVER,
            -> OfficialTradeLookup.Failed(apiEx.message)
        }
    }

    companion object {
        const val MAX_DUPLICATE_USER_STATS: Int = 5
        private const val VERIFY_PROBE_USERNAME: String = "solaireh3"

        fun forContext(
            context: Context,
            baseHttpClient: OkHttpClient = OkHttpClient(),
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): OfficialTradeRepositoryImpl {
            val appContext = context.applicationContext ?: context
            return OfficialTradeRepositoryImpl(
                apiClient = OfficialTradeApiClient(baseHttpClient = baseHttpClient),
                keyStore = OfficialTradeKeyStore.forContext(
                    context = appContext,
                    ioDispatcher = ioDispatcher,
                ),
                cacheStore = OfficialTradeCacheStore(
                    cacheFile = File(appContext.filesDir, OfficialTradeCacheStore.CACHE_FILE_NAME),
                    ioDispatcher = ioDispatcher,
                ),
                rateLimiter = OfficialTradeRateLimiter(),
                ioDispatcher = ioDispatcher,
            )
        }
    }
}
