package com.nodeseek.app.data.overseas

import android.content.Context
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.data.valuation.FxRefreshOutcome
import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.domain.overseas.ConvertedPriceTag
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasDisplayMode
import com.nodeseek.app.domain.overseas.OverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasIntelSnapshot
import com.nodeseek.app.domain.overseas.OverseasPriceConverter
import com.nodeseek.app.domain.overseas.OverseasPriceCycle
import com.nodeseek.app.domain.overseas.OverseasSource
import com.nodeseek.app.domain.overseas.OverseasSyncStatus
import com.nodeseek.app.domain.overseas.OverseasTranslationConfig
import com.nodeseek.app.domain.overseas.TranslationEngineKind
import com.nodeseek.app.domain.overseas.TranslationTier
import com.nodeseek.app.domain.overseas.VpsGlossaryTranslator
import com.nodeseek.app.domain.valuation.ValuationCurrency
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.math.BigDecimal

@Serializable
private data class CachedPriceTagDto(
    val rawText: String,
    val originalAmount: String,
    val currencyCode: String,
    val cycleName: String,
)

@Serializable
private data class CachedOfferDto(
    val id: String,
    val sourceName: String,
    val originalTitle: String,
    val translatedTitle: String? = null,
    val originalSummary: String,
    val translatedSummary: String? = null,
    val authorOrProvider: String,
    val sourceLink: String,
    val orderLinks: List<String> = emptyList(),
    val couponCodes: List<String> = emptyList(),
    val priceMatches: List<CachedPriceTagDto> = emptyList(),
    val specHighlights: List<String> = emptyList(),
    val publishedAtEpochMillis: Long = 0L,
    val isFlashOrBlackFriday: Boolean = false,
    val isOutOfStockHint: Boolean = false,
    val translationTierName: String = TranslationTier.NONE.name,
)

@Serializable
private data class OverseasIntelDiskCachePayload(
    val lastSyncedEpochMillis: Long = 0L,
    val offers: List<CachedOfferDto> = emptyList(),
)

@Serializable
private data class OverseasTranslatorPrefsPayload(
    val engineKindName: String = TranslationEngineKind.AUTO_GTX_WITH_GLOSSARY.name,
    val customEndpointUrl: String = "",
    val customApiKey: String = "",
    val customModel: String = OverseasTranslationConfig.DEFAULT_OPENAI_MODEL,
    val autoTranslateOnRefresh: Boolean = true,
    val displayModeName: String = OverseasDisplayMode.BILINGUAL.name,
)

/**
 * Default [OverseasIntelRepository] backed by:
 * - `filesDir/overseas_intel_cache.json` for instant cold-start rendering
 * - `filesDir/overseas_translations.json` via [OverseasTranslator]
 * - `filesDir/overseas_translator_prefs.json` (private owner-only permissions) for translation provider prefs
 * - Shared [ExchangeRateRepository] for real-time CNY conversion
 */
class OverseasIntelRepositoryImpl(
    private val cacheFile: File,
    private val prefsFile: File,
    private val exchangeRateRepository: ExchangeRateRepository,
    private val feedFetcher: OverseasFeedFetcher = OverseasFeedFetcher(),
    private val translator: OverseasTranslator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
) : OverseasIntelRepository {

    constructor(
        context: Context,
        exchangeRateRepository: ExchangeRateRepository = ExchangeRateRepository.forContext(context),
        feedFetcher: OverseasFeedFetcher = OverseasFeedFetcher(),
        translator: OverseasTranslator = OverseasTranslator(context),
    ) : this(
        cacheFile = File((context.applicationContext ?: context).filesDir, INTEL_CACHE_FILE_NAME),
        prefsFile = File((context.applicationContext ?: context).filesDir, TRANSLATOR_PREFS_FILE_NAME),
        exchangeRateRepository = exchangeRateRepository,
        feedFetcher = feedFetcher,
        translator = translator,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val ioMutex = Mutex()
    private var diskLoaded = false

    private val _snapshot = MutableStateFlow(
        OverseasIntelSnapshot(
            offers = emptyList(),
            fxRatesState = exchangeRateRepository.state.value,
            translationConfig = OverseasTranslationConfig(),
            syncStatus = OverseasSyncStatus(),
        ),
    )
    override val snapshot: StateFlow<OverseasIntelSnapshot> = _snapshot.asStateFlow()

    override suspend fun loadFromCache(): List<OverseasDealOffer> = withContext(ioDispatcher) {
        val fxState = exchangeRateRepository.loadPersistedState()
        ioMutex.withLock {
            if (!diskLoaded) {
                diskLoaded = true
                val prefs = readPrefsLocked()
                val cachedPayload = readIntelCacheLocked()
                val restoredOffers = cachedPayload.offers.mapNotNull { it.toDomain(fxState) }
                _snapshot.value = OverseasIntelSnapshot(
                    offers = restoredOffers,
                    fxRatesState = fxState,
                    translationConfig = prefs,
                    syncStatus = OverseasSyncStatus(
                        lastSyncedEpochMillis = cachedPayload.lastSyncedEpochMillis,
                        loadedFromCache = restoredOffers.isNotEmpty(),
                    ),
                )
            } else {
                val reconverted = OverseasPriceConverter.reconvertOffers(_snapshot.value.offers, fxState)
                _snapshot.update { it.copy(offers = reconverted, fxRatesState = fxState) }
            }
            _snapshot.value.offers
        }
    }

    override suspend fun refresh(forceTranslate: Boolean): Result<List<OverseasDealOffer>> = withContext(ioDispatcher) {
        // Ensure disk cache & prefs are initialized first so cached data stays visible while refreshing
        loadFromCache()

        _snapshot.update { current ->
            current.copy(
                syncStatus = current.syncStatus.copy(
                    isRefreshing = true,
                    sourceErrors = emptyMap(),
                ),
            )
        }

        // Best-effort ensure exchange rates are reasonably fresh (< 24h)
        val fxState = try {
            when (val outcome = exchangeRateRepository.ensureFreshRates(force = false)) {
                is FxRefreshOutcome.Success -> outcome.state
                is FxRefreshOutcome.Fallback -> outcome.state
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            exchangeRateRepository.state.value
        }

        val fetchResult = try {
            feedFetcher.fetchAll(fxRatesState = fxState)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _snapshot.update { current ->
                current.copy(
                    fxRatesState = fxState,
                    syncStatus = current.syncStatus.copy(
                        isRefreshing = false,
                        sourceErrors = mapOf(
                            OverseasSource.LET to (t.message ?: "拉取失败"),
                            OverseasSource.LEB to (t.message ?: "拉取失败"),
                        ),
                    ),
                )
            }
            return@withContext Result.failure(t)
        }

        if (fetchResult.offers.isEmpty() && fetchResult.sourceErrors.isNotEmpty()) {
            val existingReconverted = OverseasPriceConverter.reconvertOffers(_snapshot.value.offers, fxState)
            _snapshot.update { current ->
                current.copy(
                    offers = existingReconverted,
                    fxRatesState = fxState,
                    syncStatus = current.syncStatus.copy(
                        isRefreshing = false,
                        sourceErrors = fetchResult.sourceErrors,
                    ),
                )
            }
            return@withContext Result.failure(
                IllegalStateException(fetchResult.sourceErrors.values.joinToString("；")),
            )
        }

        // Merge with any already-translated offers to preserve existing translations immediately
        val previousById = _snapshot.value.offers.associateBy { it.id }
        val config = _snapshot.value.translationConfig
        val initialOffers = fetchResult.offers.map { fresh ->
            val prev = previousById[fresh.id]
            if (!forceTranslate && prev != null && prev.originalTitle == fresh.originalTitle && !prev.translatedTitle.isNullOrBlank()) {
                fresh.copy(
                    translatedTitle = prev.translatedTitle,
                    translatedSummary = prev.translatedSummary,
                    translationTier = prev.translationTier,
                )
            } else {
                // Instant offline glossary preview so Chinese users never see raw untranslated cards while network translates
                fresh.copy(
                    translatedTitle = VpsGlossaryTranslator.translateTitleOffline(fresh.originalTitle),
                    translatedSummary = VpsGlossaryTranslator.translateSummaryOffline(
                        englishSummary = fresh.originalSummary,
                        specHighlights = fresh.specHighlights,
                        priceMatches = fresh.priceMatches,
                        couponCodes = fresh.couponCodes,
                    ),
                    translationTier = TranslationTier.OFFLINE_GLOSSARY,
                )
            }
        }

        val syncedAt = nowMillisProvider()
        val shouldAutoTranslate = config.autoTranslateOnRefresh || forceTranslate

        _snapshot.update { current ->
            current.copy(
                offers = initialOffers,
                fxRatesState = fxState,
                syncStatus = OverseasSyncStatus(
                    isRefreshing = false,
                    isTranslating = shouldAutoTranslate,
                    lastSyncedEpochMillis = syncedAt,
                    sourceErrors = fetchResult.sourceErrors,
                    loadedFromCache = false,
                ),
            )
        }
        persistIntelCache(initialOffers, syncedAt)

        val finalOffers = if (shouldAutoTranslate) {
            try {
                val translated = translator.translateBatch(
                    offers = initialOffers,
                    config = config,
                    forceRefresh = forceTranslate,
                )
                _snapshot.update { current ->
                    current.copy(
                        offers = translated,
                        syncStatus = current.syncStatus.copy(isTranslating = false),
                    )
                }
                persistIntelCache(translated, syncedAt)
                translated
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _snapshot.update { current ->
                    current.copy(syncStatus = current.syncStatus.copy(isTranslating = false))
                }
                initialOffers
            }
        } else {
            initialOffers
        }

        Result.success(finalOffers)
    }

    override suspend fun translateOffer(
        offerId: String,
        forceRefresh: Boolean,
    ): OverseasDealOffer? = withContext(ioDispatcher) {
        val current = _snapshot.value
        val target = current.offers.firstOrNull { it.id == offerId } ?: return@withContext null
        val translated = translator.translateOffer(
            offer = target,
            config = current.translationConfig,
            forceRefresh = forceRefresh,
        )
        val updatedList = _snapshot.value.offers.map { if (it.id == offerId) translated else it }
        _snapshot.update { it.copy(offers = updatedList) }
        persistIntelCache(updatedList, _snapshot.value.syncStatus.lastSyncedEpochMillis)
        translated
    }

    override suspend fun translateAllLoaded(forceRefresh: Boolean) = withContext(ioDispatcher) {
        val current = _snapshot.value
        if (current.offers.isEmpty()) return@withContext
        _snapshot.update { it.copy(syncStatus = it.syncStatus.copy(isTranslating = true)) }
        try {
            val translated = translator.translateBatch(
                offers = current.offers,
                config = current.translationConfig,
                forceRefresh = forceRefresh,
            )
            _snapshot.update {
                it.copy(
                    offers = translated,
                    syncStatus = it.syncStatus.copy(isTranslating = false),
                )
            }
            persistIntelCache(translated, _snapshot.value.syncStatus.lastSyncedEpochMillis)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _snapshot.update { it.copy(syncStatus = it.syncStatus.copy(isTranslating = false)) }
        }
    }

    override suspend fun updateTranslationConfig(config: OverseasTranslationConfig) = withContext(ioDispatcher) {
        ioMutex.withLock {
            writePrefsLocked(config)
            _snapshot.update { it.copy(translationConfig = config) }
        }
    }

    override suspend fun updateDisplayMode(mode: OverseasDisplayMode) = withContext(ioDispatcher) {
        ioMutex.withLock {
            val updated = _snapshot.value.translationConfig.copy(displayMode = mode)
            writePrefsLocked(updated)
            _snapshot.update { it.copy(translationConfig = updated) }
        }
    }

    override suspend fun refreshExchangeRates(): FxRefreshOutcome = withContext(ioDispatcher) {
        val outcome = exchangeRateRepository.refreshOnline()
        val updatedFx = when (outcome) {
            is FxRefreshOutcome.Success -> outcome.state
            is FxRefreshOutcome.Fallback -> outcome.state
        }
        val reconverted = OverseasPriceConverter.reconvertOffers(_snapshot.value.offers, updatedFx)
        _snapshot.update { it.copy(offers = reconverted, fxRatesState = updatedFx) }
        outcome
    }

    override suspend fun testTranslationConfig(config: OverseasTranslationConfig): Result<String> =
        translator.testConnection(config)

    override suspend fun clearTranslationCache() {
        translator.clearCache()
    }

    private suspend fun persistIntelCache(offers: List<OverseasDealOffer>, syncedAt: Long) {
        ioMutex.withLock {
            val payload = OverseasIntelDiskCachePayload(
                lastSyncedEpochMillis = syncedAt,
                offers = offers.take(MAX_CACHED_OFFERS).map { it.toDto() },
            )
            writeJsonAtomically(cacheFile, json.encodeToString(payload), restrictPermissions = false)
        }
    }

    private fun readIntelCacheLocked(): OverseasIntelDiskCachePayload {
        if (!cacheFile.exists()) return OverseasIntelDiskCachePayload()
        return runCatching {
            val content = cacheFile.readText(Charsets.UTF_8)
            if (content.isBlank()) OverseasIntelDiskCachePayload()
            else json.decodeFromString<OverseasIntelDiskCachePayload>(content)
        }.getOrDefault(OverseasIntelDiskCachePayload())
    }

    private fun readPrefsLocked(): OverseasTranslationConfig {
        if (!prefsFile.exists()) return OverseasTranslationConfig()
        val dto = runCatching {
            val content = prefsFile.readText(Charsets.UTF_8)
            if (content.isBlank()) null
            else json.decodeFromString<OverseasTranslatorPrefsPayload>(content)
        }.getOrNull() ?: return OverseasTranslationConfig()

        return OverseasTranslationConfig(
            engineKind = runCatching { TranslationEngineKind.valueOf(dto.engineKindName) }
                .getOrDefault(TranslationEngineKind.AUTO_GTX_WITH_GLOSSARY),
            customEndpointUrl = dto.customEndpointUrl,
            customApiKey = dto.customApiKey,
            customModel = dto.customModel.ifBlank { OverseasTranslationConfig.DEFAULT_OPENAI_MODEL },
            autoTranslateOnRefresh = dto.autoTranslateOnRefresh,
            displayMode = runCatching { OverseasDisplayMode.valueOf(dto.displayModeName) }
                .getOrDefault(OverseasDisplayMode.BILINGUAL),
        )
    }

    private fun writePrefsLocked(config: OverseasTranslationConfig) {
        val dto = OverseasTranslatorPrefsPayload(
            engineKindName = config.engineKind.name,
            customEndpointUrl = config.customEndpointUrl.trim(),
            customApiKey = config.customApiKey.trim(),
            customModel = config.customModel.trim().ifBlank { OverseasTranslationConfig.DEFAULT_OPENAI_MODEL },
            autoTranslateOnRefresh = config.autoTranslateOnRefresh,
            displayModeName = config.displayMode.name,
        )
        writeJsonAtomically(prefsFile, json.encodeToString(dto), restrictPermissions = true)
    }

    private fun writeJsonAtomically(
        targetFile: File,
        encoded: String,
        restrictPermissions: Boolean,
    ) {
        runCatching {
            targetFile.parentFile?.mkdirs()
            val tmpFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
            tmpFile.writeText(encoded, Charsets.UTF_8)
            if (restrictPermissions) {
                tmpFile.setReadable(false, false)
                tmpFile.setReadable(true, true)
                tmpFile.setWritable(false, false)
                tmpFile.setWritable(true, true)
            }
            if (!tmpFile.renameTo(targetFile)) {
                targetFile.writeText(encoded, Charsets.UTF_8)
                if (restrictPermissions) {
                    targetFile.setReadable(false, false)
                    targetFile.setReadable(true, true)
                }
                tmpFile.delete()
            }
        }
    }

    private fun OverseasDealOffer.toDto(): CachedOfferDto = CachedOfferDto(
        id = id,
        sourceName = source.name,
        originalTitle = originalTitle,
        translatedTitle = translatedTitle,
        originalSummary = originalSummary,
        translatedSummary = translatedSummary,
        authorOrProvider = authorOrProvider,
        sourceLink = sourceLink,
        orderLinks = orderLinks,
        couponCodes = couponCodes,
        priceMatches = priceMatches.map { tag ->
            CachedPriceTagDto(
                rawText = tag.rawText,
                originalAmount = tag.originalAmount.toPlainString(),
                currencyCode = tag.currency.code,
                cycleName = tag.cycle.name,
            )
        },
        specHighlights = specHighlights,
        publishedAtEpochMillis = publishedAtEpochMillis,
        isFlashOrBlackFriday = isFlashOrBlackFriday,
        isOutOfStockHint = isOutOfStockHint,
        translationTierName = translationTier.name,
    )

    private fun CachedOfferDto.toDomain(fxState: FxRatesState): OverseasDealOffer? {
        val source = runCatching { OverseasSource.valueOf(sourceName) }.getOrNull() ?: return null
        val convertedPrices: List<ConvertedPriceTag> = priceMatches.mapNotNull { dto ->
            val amount = dto.originalAmount.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: return@mapNotNull null
            val currency = ValuationCurrency.fromCode(dto.currencyCode) ?: return@mapNotNull null
            val cycle = runCatching { OverseasPriceCycle.valueOf(dto.cycleName) }
                .getOrDefault(OverseasPriceCycle.UNKNOWN)
            OverseasPriceConverter.convertSingle(
                rawText = dto.rawText,
                amount = amount,
                currency = currency,
                cycle = cycle,
                fxRatesState = fxState,
            )
        }
        return OverseasDealOffer(
            id = id,
            source = source,
            originalTitle = originalTitle,
            translatedTitle = translatedTitle,
            originalSummary = originalSummary,
            translatedSummary = translatedSummary,
            authorOrProvider = authorOrProvider,
            sourceLink = sourceLink,
            orderLinks = orderLinks,
            couponCodes = couponCodes,
            priceMatches = convertedPrices,
            specHighlights = specHighlights,
            publishedAtEpochMillis = publishedAtEpochMillis,
            isFlashOrBlackFriday = isFlashOrBlackFriday,
            isOutOfStockHint = isOutOfStockHint,
            translationTier = runCatching { TranslationTier.valueOf(translationTierName) }
                .getOrDefault(TranslationTier.NONE),
        )
    }

    companion object {
        const val INTEL_CACHE_FILE_NAME = "overseas_intel_cache.json"
        const val TRANSLATOR_PREFS_FILE_NAME = "overseas_translator_prefs.json"
        private const val MAX_CACHED_OFFERS = 120
    }
}
