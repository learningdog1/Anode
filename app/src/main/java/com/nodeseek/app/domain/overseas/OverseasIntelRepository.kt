package com.nodeseek.app.domain.overseas

import com.nodeseek.app.data.valuation.FxRefreshOutcome
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository contract for Feature 7: Overseas VPS Intelligence Aggregator (LowEndTalk & LowEndBox),
 * multi-currency CNY conversion, and 3-tier VPS-domain Chinese translation.
 */
interface OverseasIntelRepository {

    val snapshot: StateFlow<OverseasIntelSnapshot>

    /**
     * Loads disk-cached offers, translations, and user preferences for instant cold-start rendering.
     */
    suspend fun loadFromCache(): List<OverseasDealOffer>

    /**
     * Refreshes LET & LEB feeds, updates FX rates if stale, converts prices to CNY,
     * runs translation when enabled, and persists the fresh snapshot to disk.
     */
    suspend fun refresh(forceTranslate: Boolean = false): Result<List<OverseasDealOffer>>

    /**
     * Translates (or re-translates) a single offer by [offerId] using the active translation config.
     */
    suspend fun translateOffer(offerId: String, forceRefresh: Boolean = false): OverseasDealOffer?

    /**
     * Batch-translates all currently loaded offers according to the active translation config.
     */
    suspend fun translateAllLoaded(forceRefresh: Boolean = false)

    /**
     * Persists updated translation engine settings (custom OpenAI / DeepLX endpoint, API key, model, mode).
     */
    suspend fun updateTranslationConfig(config: OverseasTranslationConfig)

    /**
     * Persists only the display mode toggle (`中文机翻` / `双语对照` / `英文原文`).
     */
    suspend fun updateDisplayMode(mode: OverseasDisplayMode)

    /**
     * Forces a real-time exchange rate refresh via `ExchangeRateRepository` and updates CNY prices.
     */
    suspend fun refreshExchangeRates(): FxRefreshOutcome

    /**
     * Verifies a candidate [OverseasTranslationConfig] by running a small VPS sample phrase through it.
     */
    suspend fun testTranslationConfig(config: OverseasTranslationConfig): Result<String>

    /**
     * Clears persisted translation cache on disk so subsequent translations re-run cleanly.
     */
    suspend fun clearTranslationCache()
}
