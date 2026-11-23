package com.nodeseek.app.data.valuation

import android.content.Context
import com.nodeseek.app.domain.valuation.FxRateSource
import com.nodeseek.app.domain.valuation.ValuationCurrency
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Resolved exchange rate quote for a single [ValuationCurrency] expressed as `1 <CODE> = X CNY`.
 */
data class CurrencyRateQuote(
    val currency: ValuationCurrency,
    val rateToCny: BigDecimal,
    val source: FxRateSource,
    val onlineRateToCny: BigDecimal? = null,
    val hasManualOverride: Boolean = false,
)

/**
 * Immutable UI/domain snapshot of all supported currency rates and sync status.
 */
data class FxRatesState(
    val quotes: Map<ValuationCurrency, CurrencyRateQuote>,
    val fetchedAtEpochMillis: Long = 0L,
    val providerDate: String? = null,
    val isRefreshing: Boolean = false,
    val isCacheExpired: Boolean = false,
    val lastSyncError: String? = null,
) {
    fun quoteFor(currency: ValuationCurrency): CurrencyRateQuote =
        quotes[currency] ?: CurrencyRateQuote(
            currency = currency,
            rateToCny = currency.defaultCnyRate,
            source = FxRateSource.OFFLINE_DEFAULT,
        )

    fun rateFor(currency: ValuationCurrency): BigDecimal = quoteFor(currency).rateToCny
}

/**
 * Outcome returned by [ExchangeRateRepository.ensureFreshRates] / [ExchangeRateRepository.refreshOnline].
 */
sealed interface FxRefreshOutcome {
    data class Success(val state: FxRatesState, val fromCache: Boolean) : FxRefreshOutcome
    data class Fallback(val reason: String, val state: FxRatesState) : FxRefreshOutcome
}

@Serializable
private data class ValuationFxCachePayload(
    val fetchedAtEpochMillis: Long = 0L,
    val providerDate: String? = null,
    val onlineRatesToCny: Map<String, String> = emptyMap(),
    val manualOverridesToCny: Map<String, String> = emptyMap(),
)

internal data class ParsedFrankfurterRates(
    val providerDate: String?,
    val ratesToCny: Map<String, String>,
)

/**
 * Self-contained exchange rate service backed by `filesDir/valuation_fx_cache.json`.
 *
 * Features:
 * - Built-in sensible offline default CNY rates for all 13 [ValuationCurrency] entries
 * - Online refresh from `https://api.frankfurter.dev/v1/latest?base=CNY`, converting `1 / rateFromCny`
 *   using [BigDecimal] with [RoundingMode.HALF_UP]
 * - 24-hour disk cache TTL with automatic fallback to cached/default rates on failure
 * - Persistent per-currency user manual overrides
 */
class ExchangeRateRepository(
    private val cacheFile: File,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val endpointUrl: String = FRANKFURTER_LATEST_CNY_URL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
) {
    constructor(context: Context) : this(
        cacheFile = File((context.applicationContext ?: context).filesDir, CACHE_FILE_NAME),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()
    private var diskLoaded = false
    private var cachedPayload = ValuationFxCachePayload()
    private var inFlightRefresh: CompletableDeferred<FxRefreshOutcome?>? = null

    private val _state = MutableStateFlow(
        buildFxRatesState(
            payload = ValuationFxCachePayload(),
            isOnlineFresh = false,
            isRefreshing = false,
            lastSyncError = null,
        )
    )
    val state: StateFlow<FxRatesState> = _state.asStateFlow()

    /**
     * Loads any persisted cache & manual overrides from disk without performing network calls.
     */
    suspend fun loadPersistedState(): FxRatesState = withContext(ioDispatcher) {
        mutex.withLock {
            ensureDiskLoadedLocked()
            val next = buildFxRatesState(
                payload = cachedPayload,
                isOnlineFresh = isCacheFreshLocked(nowMillisProvider()),
                isRefreshing = inFlightRefresh != null,
                lastSyncError = _state.value.lastSyncError,
            )
            _state.value = next
            next
        }
    }

    /**
     * Ensures exchange rates are reasonably fresh (< 24h). Uses persisted disk cache when still
     * within TTL; otherwise fetches from Frankfurter (coalescing concurrent calls into a single
     * in-flight request) and gracefully falls back on failure.
     */
    suspend fun ensureFreshRates(force: Boolean = false): FxRefreshOutcome = withContext(ioDispatcher) {
        var resolvedOutcome: FxRefreshOutcome? = null
        while (resolvedOutcome == null) {
            val now = nowMillisProvider()
            var earlyState: FxRatesState? = null
            var existingDeferred: CompletableDeferred<FxRefreshOutcome?>? = null
            var ownDeferred: CompletableDeferred<FxRefreshOutcome?>? = null

            mutex.withLock {
                ensureDiskLoadedLocked()
                val fresh = isCacheFreshLocked(now)
                if (!force && fresh) {
                    val currentState = buildFxRatesState(
                        payload = cachedPayload,
                        isOnlineFresh = true,
                        isRefreshing = inFlightRefresh != null,
                        lastSyncError = _state.value.lastSyncError,
                    )
                    _state.value = currentState
                    earlyState = currentState
                } else if (inFlightRefresh != null) {
                    existingDeferred = inFlightRefresh
                } else {
                    val created = CompletableDeferred<FxRefreshOutcome?>()
                    inFlightRefresh = created
                    ownDeferred = created
                    _state.value = buildFxRatesState(
                        payload = cachedPayload,
                        isOnlineFresh = fresh,
                        isRefreshing = true,
                        lastSyncError = if (force) null else _state.value.lastSyncError,
                    )
                }
            }

            if (earlyState != null) {
                resolvedOutcome = FxRefreshOutcome.Success(state = earlyState!!, fromCache = true)
                continue
            }

            val joinTarget = existingDeferred
            if (joinTarget != null) {
                val joinedOutcome = joinTarget.await()
                if (joinedOutcome != null) {
                    resolvedOutcome = joinedOutcome
                }
                // If previous leader was cancelled before completion (null), loop and retry cleanly.
                continue
            }

            val leaderDeferred = ownDeferred!!
            var completedOutcome: FxRefreshOutcome? = null
            try {
                val outcome = fetchAndPersistOnlineRates()
                completedOutcome = outcome
                resolvedOutcome = outcome
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (inFlightRefresh === leaderDeferred) {
                            inFlightRefresh = null
                        }
                        if (completedOutcome == null && _state.value.isRefreshing && inFlightRefresh == null) {
                            _state.value = buildFxRatesState(
                                payload = cachedPayload,
                                isOnlineFresh = isCacheFreshLocked(nowMillisProvider()),
                                isRefreshing = false,
                                lastSyncError = _state.value.lastSyncError,
                            )
                        }
                    }
                    leaderDeferred.complete(completedOutcome)
                }
            }
        }
        resolvedOutcome
    }

    /**
     * Forces an online sync against `https://api.frankfurter.dev/v1/latest?base=CNY`.
     */
    suspend fun refreshOnline(): FxRefreshOutcome = ensureFreshRates(force = true)

    /**
     * Persists a manual `1 [currency] = [rateToCny] CNY` override locally.
     */
    suspend fun setManualOverride(
        currency: ValuationCurrency,
        rateToCny: BigDecimal,
    ): FxRatesState = withContext(ioDispatcher) {
        require(currency != ValuationCurrency.CNY) { "CNY is the base currency and cannot be overridden." }
        require(rateToCny > BigDecimal.ZERO) { "Exchange rate must be greater than zero." }

        mutex.withLock {
            ensureDiskLoadedLocked()
            val normalized = rateToCny.stripTrailingZeros().let {
                if (it.scale() < 0) it.setScale(0, RoundingMode.HALF_UP) else it
            }.toPlainString()

            val updatedOverrides = cachedPayload.manualOverridesToCny.toMutableMap().apply {
                put(currency.code, normalized)
            }
            cachedPayload = cachedPayload.copy(manualOverridesToCny = updatedOverrides)
            writeCacheToDiskLocked(cachedPayload)

            val next = buildFxRatesState(
                payload = cachedPayload,
                isOnlineFresh = isCacheFreshLocked(nowMillisProvider()),
                isRefreshing = _state.value.isRefreshing,
                lastSyncError = _state.value.lastSyncError,
            )
            _state.value = next
            next
        }
    }

    /**
     * Removes a single currency's manual override, reverting to online/cached or offline default rate.
     */
    suspend fun clearManualOverride(currency: ValuationCurrency): FxRatesState = withContext(ioDispatcher) {
        mutex.withLock {
            ensureDiskLoadedLocked()
            if (!cachedPayload.manualOverridesToCny.containsKey(currency.code)) {
                return@withLock _state.value
            }
            val updatedOverrides = cachedPayload.manualOverridesToCny.toMutableMap().apply {
                remove(currency.code)
            }
            cachedPayload = cachedPayload.copy(manualOverridesToCny = updatedOverrides)
            writeCacheToDiskLocked(cachedPayload)

            val next = buildFxRatesState(
                payload = cachedPayload,
                isOnlineFresh = isCacheFreshLocked(nowMillisProvider()),
                isRefreshing = _state.value.isRefreshing,
                lastSyncError = _state.value.lastSyncError,
            )
            _state.value = next
            next
        }
    }

    private suspend fun fetchAndPersistOnlineRates(): FxRefreshOutcome {
        return try {
            val request = Request.Builder()
                .url(endpointUrl)
                .get()
                .header("Accept", "application/json")
                .build()
            val body = executeRequest(request)
            val parsed = parseFrankfurterResponse(body)
                ?: return handleSyncFailure("汇率响应解析异常，已使用缓存/参考汇率")

            val updatedState = mutex.withLock {
                ensureDiskLoadedLocked()
                val mergedOnline = cachedPayload.onlineRatesToCny.toMutableMap().apply {
                    putAll(parsed.ratesToCny)
                }
                cachedPayload = cachedPayload.copy(
                    fetchedAtEpochMillis = nowMillisProvider(),
                    providerDate = parsed.providerDate ?: cachedPayload.providerDate,
                    onlineRatesToCny = mergedOnline,
                )
                writeCacheToDiskLocked(cachedPayload)
                val next = buildFxRatesState(
                    payload = cachedPayload,
                    isOnlineFresh = true,
                    isRefreshing = false,
                    lastSyncError = null,
                )
                _state.value = next
                next
            }
            FxRefreshOutcome.Success(state = updatedState, fromCache = false)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            val reason = t.message?.takeIf { it.isNotBlank() } ?: "网络不可用"
            handleSyncFailure("汇率同步失败（$reason），已使用本地汇率")
        }
    }

    private suspend fun handleSyncFailure(reason: String): FxRefreshOutcome.Fallback {
        val fallbackState = mutex.withLock {
            ensureDiskLoadedLocked()
            val next = buildFxRatesState(
                payload = cachedPayload,
                isOnlineFresh = isCacheFreshLocked(nowMillisProvider()),
                isRefreshing = false,
                lastSyncError = reason,
            )
            _state.value = next
            next
        }
        return FxRefreshOutcome.Fallback(reason = reason, state = fallbackState)
    }

    private suspend fun executeRequest(request: Request): String =
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
                        val bodyStr = resp.body?.string().orEmpty()
                        if (continuation.isActive) {
                            continuation.resume(bodyStr)
                        }
                    }
                }
            })
        }

    internal fun parseFrankfurterResponse(rawJson: String): ParsedFrankfurterRates? {
        if (rawJson.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val providerDate = root["date"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val ratesElement = root["rates"]?.jsonObject ?: return null
            val converted = LinkedHashMap<String, String>()

            ValuationCurrency.entries.forEach { currency ->
                if (currency == ValuationCurrency.CNY) return@forEach
                val rawRateStr = ratesElement[currency.code]?.jsonPrimitive?.contentOrNull
                val rateFromCny = rawRateStr?.toBigDecimalOrNull()
                if (rateFromCny != null && rateFromCny > BigDecimal.ZERO) {
                    // Frankfurter `base=CNY` reports `1 CNY = rateFromCny <CODE>`, so `1 <CODE> = 1 / rateFromCny CNY`.
                    val rateToCny = BigDecimal.ONE.divide(rateFromCny, 6, RoundingMode.HALF_UP)
                    if (rateToCny > BigDecimal.ZERO) {
                        converted[currency.code] = rateToCny.toPlainString()
                    }
                }
            }

            if (converted.isEmpty()) null
            else ParsedFrankfurterRates(providerDate = providerDate, ratesToCny = converted)
        }.getOrNull()
    }

    private fun ensureDiskLoadedLocked() {
        if (diskLoaded) return
        diskLoaded = true
        if (!cacheFile.exists()) return
        val loaded = runCatching {
            val content = cacheFile.readText(Charsets.UTF_8)
            if (content.isBlank()) null else json.decodeFromString<ValuationFxCachePayload>(content)
        }.getOrNull()
        if (loaded != null) {
            cachedPayload = loaded
        }
    }

    private fun writeCacheToDiskLocked(payload: ValuationFxCachePayload) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val encoded = json.encodeToString(payload)
            val tmpFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            tmpFile.writeText(encoded, Charsets.UTF_8)
            if (!tmpFile.renameTo(cacheFile)) {
                cacheFile.writeText(encoded, Charsets.UTF_8)
                tmpFile.delete()
            }
        }
    }

    private fun isCacheFreshLocked(nowEpochMillis: Long): Boolean {
        val fetchedAt = cachedPayload.fetchedAtEpochMillis
        if (fetchedAt <= 0L || cachedPayload.onlineRatesToCny.isEmpty()) return false
        val age = nowEpochMillis - fetchedAt
        return age in 0L until CACHE_TTL_MILLIS
    }

    private fun buildFxRatesState(
        payload: ValuationFxCachePayload,
        isOnlineFresh: Boolean,
        isRefreshing: Boolean,
        lastSyncError: String?,
    ): FxRatesState {
        val quotes = LinkedHashMap<ValuationCurrency, CurrencyRateQuote>(ValuationCurrency.entries.size)
        ValuationCurrency.entries.forEach { currency ->
            if (currency == ValuationCurrency.CNY) {
                quotes[currency] = CurrencyRateQuote(
                    currency = currency,
                    rateToCny = BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP),
                    source = FxRateSource.OFFLINE_DEFAULT,
                )
                return@forEach
            }

            val onlineRate = payload.onlineRatesToCny[currency.code]
                ?.toBigDecimalOrNull()
                ?.takeIf { it > BigDecimal.ZERO }
            val manualRate = payload.manualOverridesToCny[currency.code]
                ?.toBigDecimalOrNull()
                ?.takeIf { it > BigDecimal.ZERO }

            val effectiveRate = manualRate ?: onlineRate ?: currency.defaultCnyRate
            val source = when {
                manualRate != null -> FxRateSource.MANUAL_OVERRIDE
                onlineRate != null -> if (isOnlineFresh) FxRateSource.ONLINE_FRESH else FxRateSource.ONLINE_CACHED
                else -> FxRateSource.OFFLINE_DEFAULT
            }

            quotes[currency] = CurrencyRateQuote(
                currency = currency,
                rateToCny = effectiveRate,
                source = source,
                onlineRateToCny = onlineRate,
                hasManualOverride = manualRate != null,
            )
        }

        val isCacheExpired = payload.fetchedAtEpochMillis > 0L &&
            payload.onlineRatesToCny.isNotEmpty() &&
            !isOnlineFresh

        return FxRatesState(
            quotes = quotes,
            fetchedAtEpochMillis = payload.fetchedAtEpochMillis,
            providerDate = payload.providerDate,
            isRefreshing = isRefreshing,
            isCacheExpired = isCacheExpired,
            lastSyncError = lastSyncError,
        )
    }

    companion object {
        const val FRANKFURTER_LATEST_CNY_URL = "https://api.frankfurter.dev/v1/latest?base=CNY"
        const val CACHE_FILE_NAME = "valuation_fx_cache.json"
        const val CACHE_TTL_MILLIS = 24L * 60L * 60L * 1000L

        private val sharedInstances = ConcurrentHashMap<String, ExchangeRateRepository>()

        /**
         * Returns a process-scoped [ExchangeRateRepository] sharing in-memory state for `filesDir/valuation_fx_cache.json`.
         */
        fun forContext(context: Context): ExchangeRateRepository {
            val appCtx = context.applicationContext ?: context
            val file = File(appCtx.filesDir, CACHE_FILE_NAME)
            val key = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            return sharedInstances.getOrPut(key) {
                ExchangeRateRepository(cacheFile = file)
            }
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
