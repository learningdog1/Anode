package com.nodeseek.app.domain.overseas

import kotlinx.coroutines.flow.StateFlow

/**
 * Repository contract for Overseas VPS Promotions powered by the LowEndTalk monitor API:
 * - Paginated promotion list (`GET /api/v1/offers`) with offline disk cache
 * - Telegram notification detail (`GET /api/v1/offers/{id}`) with optional translation
 * - Comment-free main post OP repost payload (`GET /api/v1/offers/{id}/repost`)
 */
interface OverseasIntelRepository {

    val snapshot: StateFlow<OverseasIntelSnapshot>

    /**
     * Loads disk-cached offers and translation preferences for instant cold-start or offline display.
     */
    suspend fun loadFromCache(): List<OverseasDealOffer>

    /**
     * Refreshes page 1 of promotions from the signed & encrypted API (with retry and offline cache fallback).
     */
    suspend fun refresh(query: String = ""): Result<List<OverseasDealOffer>>

    /**
     * Appends the next page of promotions when [OverseasSyncStatus.hasMore] is true.
     */
    suspend fun loadNextPage(query: String = ""): Result<List<OverseasDealOffer>>

    /**
     * Loads the Telegram notification detail for [offerId], serving cached content when offline.
     */
    suspend fun getOfferDetail(
        offerId: String,
        forceRefresh: Boolean = false,
    ): Result<OverseasOfferDetail>

    /**
     * Translates (or re-translates) the loaded detail Markdown for [offerId] using the active translator config.
     */
    suspend fun translateOfferDetail(
        offerId: String,
        forceRefresh: Boolean = true,
    ): Result<OverseasOfferDetail>

    /**
     * Fetches the main-post (OP) repost payload for [offerId] (excluding all comments).
     */
    suspend fun getRepostContent(
        offerId: String,
        forceRefresh: Boolean = false,
    ): Result<OverseasRepostContent>

    /**
     * Persists updated translation engine settings for detail translation.
     */
    suspend fun updateTranslationConfig(config: OverseasTranslationConfig)

    /**
     * Persists only the detail display mode toggle.
     */
    suspend fun updateDisplayMode(mode: OverseasDisplayMode)

    /**
     * Verifies a candidate [OverseasTranslationConfig] by running a sample VPS phrase through it.
     */
    suspend fun testTranslationConfig(config: OverseasTranslationConfig): Result<String>

    /**
     * Refreshes page 1 of promotions only if cached data is missing or older than [maxAgeMillis].
     */
    suspend fun refreshIfStale(
        query: String = "",
        maxAgeMillis: Long = DEFAULT_STALE_REFRESH_MILLIS,
    ): Result<List<OverseasDealOffer>> = refresh(query)

    /**
     * Updates the domain-layer spam/low-quality offer filter configuration and re-evaluates cached items.
     */
    suspend fun updateFilterConfig(config: OverseasOfferFilterConfig) = Unit

    /**
     * Clears persisted translation cache on disk.
     */
    suspend fun clearTranslationCache()

    companion object {
        const val DEFAULT_STALE_REFRESH_MILLIS = 10L * 60_000L
    }
}
