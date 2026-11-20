package com.nodeseek.app.data.overseas

import android.content.Context
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasDetailSection
import com.nodeseek.app.domain.overseas.OverseasDetailStructured
import com.nodeseek.app.domain.overseas.OverseasDisplayMode
import com.nodeseek.app.domain.overseas.OverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasIntelSnapshot
import com.nodeseek.app.domain.overseas.OverseasLabeledLink
import com.nodeseek.app.domain.overseas.OverseasMediaImage
import com.nodeseek.app.domain.overseas.OverseasOfferDetail
import com.nodeseek.app.domain.overseas.OverseasOfferFilterConfig
import com.nodeseek.app.domain.overseas.OverseasOfferQualityFilter
import com.nodeseek.app.domain.overseas.OverseasRepostContent
import com.nodeseek.app.domain.overseas.OverseasSyncStatus
import com.nodeseek.app.domain.overseas.OverseasTranslationConfig
import com.nodeseek.app.domain.overseas.TranslationEngineKind
import com.nodeseek.app.domain.overseas.TranslationTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import java.io.IOException

@Serializable
private data class CachedOfferItemDto(
    val id: String,
    val title: String,
    val originalTitle: String = "",
    val summary: String,
    val merchant: String = "",
    val sourceLink: String,
    val publishedAtEpochMillis: Long = 0L,
    val publishedAtIso: String = "",
)

@Serializable
private data class OverseasListDiskCachePayload(
    val lastSyncedEpochMillis: Long = 0L,
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
    val currentPage: Int = 1,
    val offers: List<CachedOfferItemDto> = emptyList(),
    val etag: String? = null,
    val lastModified: String? = null,
)

@Serializable
private data class CachedDetailSectionDto(
    val header: String = "",
    val items: List<String> = emptyList(),
)

@Serializable
private data class CachedLabeledLinkDto(
    val text: String = "",
    val url: String = "",
)

@Serializable
private data class CachedMediaImageDto(
    val alt: String = "",
    val url: String = "",
)

@Serializable
private data class CachedStructuredDto(
    val titleCn: String = "",
    val sections: List<CachedDetailSectionDto> = emptyList(),
    val orderLinks: List<CachedLabeledLinkDto> = emptyList(),
    val promoCode: String = "",
    val pros: List<String> = emptyList(),
    val cons: List<String> = emptyList(),
    val suitableFor: String = "",
    val aiComment: String = "",
    val viewCount: String = "",
    val commentCount: String = "",
    val aiModel: String = "",
)

@Serializable
private data class CachedOfferDetailDto(
    val id: String,
    val title: String,
    val originalTitle: String = "",
    val merchant: String = "",
    val author: String = "",
    val publishedAtEpochMillis: Long = 0L,
    val publishedAtIso: String = "",
    val sourceLink: String,
    val tgMarkdown: String,
    val tgHtml: String = "",
    val tgPlainText: String = "",
    val structured: CachedStructuredDto? = null,
    val translatedMarkdown: String? = null,
    val translationTierName: String = TranslationTier.NONE.name,
    val cachedAtEpochMillis: Long = 0L,
    val etag: String? = null,
    val lastModified: String? = null,
)

@Serializable
private data class CachedRepostDto(
    val id: String,
    val title: String,
    val titleCn: String = "",
    val merchant: String = "",
    val author: String = "",
    val publishedAtEpochMillis: Long = 0L,
    val publishedAtIso: String = "",
    val sourceLink: String,
    val bodyMarkdown: String,
    val bodyText: String = "",
    val bodyHtml: String = "",
    val images: List<CachedMediaImageDto> = emptyList(),
    val links: List<CachedLabeledLinkDto> = emptyList(),
    val suggestedCategorySlug: String = "info",
    val cachedAtEpochMillis: Long = 0L,
    val etag: String? = null,
    val lastModified: String? = null,
)

@Serializable
private data class OverseasDetailRepostCachePayload(
    val details: Map<String, CachedOfferDetailDto> = emptyMap(),
    val reposts: Map<String, CachedRepostDto> = emptyMap(),
)

@Serializable
private data class OverseasTranslatorPrefsPayload(
    val engineKindName: String = TranslationEngineKind.AUTO_GTX_WITH_GLOSSARY.name,
    val customEndpointUrl: String = "",
    val customApiKey: String = "",
    val customModel: String = OverseasTranslationConfig.DEFAULT_OPENAI_MODEL,
    val autoTranslateOnRefresh: Boolean = false,
    val displayModeName: String = OverseasDisplayMode.ORIGINAL.name,
    val filterEnabled: Boolean = true,
    val excludeGenericMarketingBuzz: Boolean = true,
    val excludeNonServerServices: Boolean = true,
    val requireMinimumHostingSignal: Boolean = true,
    val blockedMerchants: List<String> = emptyList(),
    val extraBlockedKeywords: List<String> = emptyList(),
)

/**
 * Default [OverseasIntelRepository] backed by:
 * - [OverseasIntelApiClient] (HMAC-SHA256 request signing + AES-256-GCM response decryption + retry)
 * - `filesDir/overseas_intel_offers_v2.json` for instant cold-start and offline feed display
 * - `filesDir/overseas_intel_details_v2.json` for offline detail & repost caching
 * - `filesDir/overseas_translator_prefs.json` + [OverseasTranslator] for detail translation
 */
class OverseasIntelRepositoryImpl(
    private val listCacheFile: File,
    private val detailCacheFile: File,
    private val prefsFile: File,
    private val apiClient: OverseasIntelApiClient = OverseasIntelApiClient(),
    private val translator: OverseasTranslator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pageSize: Int = OverseasIntelApiClient.DEFAULT_PAGE_SIZE,
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
    private val minRefreshCooldownMillis: Long = DEFAULT_MIN_REFRESH_COOLDOWN_MS,
    private val detailCacheTtlMillis: Long = DEFAULT_DETAIL_CACHE_TTL_MS,
    private val pendingDetailCacheTtlMillis: Long = DEFAULT_PENDING_DETAIL_TTL_MS,
    private val loadMoreErrorCooldownMillis: Long = DEFAULT_LOAD_MORE_ERROR_COOLDOWN_MS,
) : OverseasIntelRepository {

    constructor(
        context: Context,
        apiClient: OverseasIntelApiClient = OverseasIntelApiClient(),
        translator: OverseasTranslator = OverseasTranslator(context),
    ) : this(
        listCacheFile = File((context.applicationContext ?: context).filesDir, LIST_CACHE_FILE_NAME),
        detailCacheFile = File((context.applicationContext ?: context).filesDir, DETAIL_CACHE_FILE_NAME),
        prefsFile = File((context.applicationContext ?: context).filesDir, TRANSLATOR_PREFS_FILE_NAME),
        apiClient = apiClient,
        translator = translator,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val ioMutex = Mutex()
    private val detailMutex = Mutex()
    private val inFlightMutex = Mutex()
    private val inFlightRequests = HashMap<String, Deferred<Result<Any?>>>()
    private val sharedInFlightScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private var diskLoaded = false
    private var detailsDiskLoaded = false
    private var rawOffers: List<OverseasDealOffer> = emptyList()
    private var listEtag: String? = null
    private var listLastModified: String? = null
    private var lastRefreshAttemptMillis: Long = 0L
    private var lastLoadMoreErrorKey: String? = null
    private var lastLoadMoreErrorAtMillis: Long = 0L
    private var lastLoadMoreError: Throwable? = null
    private var filterConfig: OverseasOfferFilterConfig = OverseasOfferFilterConfig()

    private val detailMemoryCache = LinkedHashMap<String, CachedOfferDetailDto>()
    private val repostMemoryCache = LinkedHashMap<String, CachedRepostDto>()

    private val _snapshot = MutableStateFlow(OverseasIntelSnapshot())
    override val snapshot: StateFlow<OverseasIntelSnapshot> = _snapshot.asStateFlow()

    override suspend fun loadFromCache(): List<OverseasDealOffer> = withContext(ioDispatcher) {
        ioMutex.withLock {
            if (!diskLoaded) {
                diskLoaded = true
                val (transPrefs, loadedFilterConfig) = readPrefsLocked()
                filterConfig = loadedFilterConfig
                val cachedList = readListCacheLocked()
                rawOffers = cachedList.offers.map { it.toDomain() }
                listEtag = cachedList.etag?.takeIf { it.isNotBlank() }
                listLastModified = cachedList.lastModified?.takeIf { it.isNotBlank() }
                val filteredOffers = OverseasOfferQualityFilter.filter(rawOffers, filterConfig)
                val filteredCount = (rawOffers.size - filteredOffers.size).coerceAtLeast(0)
                _snapshot.value = OverseasIntelSnapshot(
                    offers = filteredOffers,
                    translationConfig = transPrefs,
                    syncStatus = OverseasSyncStatus(
                        lastSyncedEpochMillis = cachedList.lastSyncedEpochMillis,
                        currentPage = cachedList.currentPage.coerceAtLeast(1),
                        totalCount = cachedList.totalCount.coerceAtLeast(filteredOffers.size),
                        hasMore = cachedList.hasMore,
                        loadedFromCache = filteredOffers.isNotEmpty(),
                    ),
                    filterConfig = filterConfig,
                    filteredOutCount = filteredCount,
                )
            }
            _snapshot.value.offers
        }
    }

    override suspend fun refreshIfStale(
        query: String,
        maxAgeMillis: Long,
    ): Result<List<OverseasDealOffer>> = withContext(ioDispatcher) {
        loadFromCache()
        val trimmed = query.trim()
        val lastSynced = _snapshot.value.syncStatus.lastSyncedEpochMillis
        val now = nowMillisProvider()
        if (trimmed.isEmpty() &&
            rawOffers.isNotEmpty() &&
            lastSynced > 0L &&
            (now - lastSynced) < maxAgeMillis.coerceAtLeast(0L)
        ) {
            return@withContext Result.success(_snapshot.value.offers)
        }
        refresh(trimmed)
    }

    override suspend fun refresh(query: String): Result<List<OverseasDealOffer>> = withContext(ioDispatcher) {
        loadFromCache()
        val trimmedQuery = query.trim()
        val now = nowMillisProvider()

        if (trimmedQuery.isEmpty() &&
            minRefreshCooldownMillis > 0L &&
            lastRefreshAttemptMillis > 0L &&
            (now - lastRefreshAttemptMillis) < minRefreshCooldownMillis &&
            rawOffers.isNotEmpty() &&
            _snapshot.value.syncStatus.errorMessage == null
        ) {
            return@withContext Result.success(_snapshot.value.offers)
        }

        coalesceInFlight("list:$trimmedQuery") {
            executeRefresh(trimmedQuery)
        }
    }

    private suspend fun executeRefresh(trimmedQuery: String): Result<List<OverseasDealOffer>> {
        if (trimmedQuery.isEmpty()) {
            lastRefreshAttemptMillis = nowMillisProvider()
        }

        _snapshot.update { current ->
            current.copy(
                syncStatus = current.syncStatus.copy(
                    isRefreshing = true,
                    errorMessage = null,
                ),
            )
        }

        val ifNoneMatch = if (trimmedQuery.isEmpty()) listEtag else null
        val ifModifiedSince = if (trimmedQuery.isEmpty()) listLastModified else null

        val condResult = try {
            apiClient.fetchOffersPageConditional(
                page = 1,
                pageSize = pageSize,
                query = trimmedQuery,
                ifNoneMatch = ifNoneMatch,
                ifModifiedSince = ifModifiedSince,
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val fallbackOffers = _snapshot.value.offers
            val errMsg = t.message?.takeIf { it.isNotBlank() } ?: "网络连接异常，无法同步最新促销情报"
            _snapshot.update { current ->
                current.copy(
                    syncStatus = current.syncStatus.copy(
                        isRefreshing = false,
                        errorMessage = if (fallbackOffers.isNotEmpty()) {
                            "$errMsg（当前显示离线缓存）"
                        } else {
                            errMsg
                        },
                        loadedFromCache = fallbackOffers.isNotEmpty(),
                    ),
                )
            }
            return Result.failure(t)
        }

        val syncedAt = nowMillisProvider()
        if (condResult.notModified && trimmedQuery.isEmpty() && rawOffers.isNotEmpty()) {
            val activeFilter = filterConfig
            val filtered = OverseasOfferQualityFilter.filter(rawOffers, activeFilter)
            val filteredOut = (rawOffers.size - filtered.size).coerceAtLeast(0)
            listEtag = condResult.etag ?: listEtag
            listLastModified = condResult.lastModified ?: listLastModified

            val currentSync = _snapshot.value.syncStatus
            _snapshot.update { current ->
                current.copy(
                    offers = filtered,
                    syncStatus = current.syncStatus.copy(
                        isRefreshing = false,
                        isLoadingMore = false,
                        lastSyncedEpochMillis = syncedAt,
                        errorMessage = null,
                        loadedFromCache = false,
                    ),
                    filterConfig = activeFilter,
                    filteredOutCount = filteredOut,
                )
            }

            persistListCache(
                offers = rawOffers,
                syncedAt = syncedAt,
                totalCount = currentSync.totalCount,
                hasMore = currentSync.hasMore,
                currentPage = currentSync.currentPage,
                etag = listEtag,
                lastModified = listLastModified,
            )
            return Result.success(filtered)
        }

        val pageResult = condResult.data
            ?: return Result.failure(IOException("Empty offers response from server"))

        val activeFilter = filterConfig
        rawOffers = pageResult.items
        val filteredOffers = OverseasOfferQualityFilter.filter(rawOffers, activeFilter)
        val filteredCount = (rawOffers.size - filteredOffers.size).coerceAtLeast(0)
        if (trimmedQuery.isEmpty()) {
            listEtag = condResult.etag
            listLastModified = condResult.lastModified
            lastLoadMoreErrorKey = null
            lastLoadMoreError = null
        }

        val updatedStatus = OverseasSyncStatus(
            isRefreshing = false,
            isLoadingMore = false,
            lastSyncedEpochMillis = syncedAt,
            currentPage = pageResult.page,
            totalCount = pageResult.total,
            hasMore = pageResult.hasMore,
            errorMessage = null,
            loadedFromCache = false,
        )

        _snapshot.update { current ->
            current.copy(
                offers = filteredOffers,
                syncStatus = updatedStatus,
                filterConfig = activeFilter,
                filteredOutCount = filteredCount,
            )
        }

        if (trimmedQuery.isEmpty()) {
            persistListCache(
                offers = rawOffers,
                syncedAt = syncedAt,
                totalCount = pageResult.total,
                hasMore = pageResult.hasMore,
                currentPage = pageResult.page,
                etag = listEtag,
                lastModified = listLastModified,
            )
        }

        return Result.success(filteredOffers)
    }

    override suspend fun loadNextPage(query: String): Result<List<OverseasDealOffer>> = withContext(ioDispatcher) {
        val current = _snapshot.value
        if (current.syncStatus.isRefreshing || current.syncStatus.isLoadingMore || !current.syncStatus.hasMore) {
            return@withContext Result.success(current.offers)
        }

        val nextPage = current.syncStatus.currentPage + 1
        val trimmedQuery = query.trim()
        val pageKey = "page:$nextPage:$trimmedQuery"
        val now = nowMillisProvider()

        if (loadMoreErrorCooldownMillis > 0L &&
            lastLoadMoreErrorKey == pageKey &&
            (now - lastLoadMoreErrorAtMillis) < loadMoreErrorCooldownMillis
        ) {
            return@withContext Result.failure(
                lastLoadMoreError ?: IOException("加载更多冷却中，请稍后重试"),
            )
        }

        coalesceInFlight(pageKey) {
            executeLoadNextPage(nextPage, trimmedQuery, pageKey)
        }
    }

    private suspend fun executeLoadNextPage(
        nextPage: Int,
        trimmedQuery: String,
        pageKey: String,
    ): Result<List<OverseasDealOffer>> {
        _snapshot.update {
            it.copy(
                syncStatus = it.syncStatus.copy(
                    isLoadingMore = true,
                    errorMessage = null,
                ),
            )
        }

        val pageResult = try {
            apiClient.fetchOffersPage(
                page = nextPage,
                pageSize = pageSize,
                query = trimmedQuery,
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            lastLoadMoreErrorKey = pageKey
            lastLoadMoreErrorAtMillis = nowMillisProvider()
            lastLoadMoreError = t
            _snapshot.update {
                it.copy(
                    syncStatus = it.syncStatus.copy(
                        isLoadingMore = false,
                        errorMessage = t.message?.takeIf { m -> m.isNotBlank() } ?: "加载更多失败，请重试",
                    ),
                )
            }
            return Result.failure(t)
        }

        lastLoadMoreErrorKey = null
        lastLoadMoreError = null

        val activeFilter = filterConfig
        rawOffers = (rawOffers + pageResult.items).distinctBy { it.id }
        val filteredMerged = OverseasOfferQualityFilter.filter(rawOffers, activeFilter)
        val filteredCount = (rawOffers.size - filteredMerged.size).coerceAtLeast(0)

        _snapshot.update { state ->
            state.copy(
                offers = filteredMerged,
                syncStatus = state.syncStatus.copy(
                    isLoadingMore = false,
                    currentPage = pageResult.page,
                    totalCount = pageResult.total.coerceAtLeast(filteredMerged.size),
                    hasMore = pageResult.hasMore,
                    errorMessage = null,
                ),
                filterConfig = activeFilter,
                filteredOutCount = filteredCount,
            )
        }

        if (trimmedQuery.isEmpty()) {
            persistListCache(
                offers = rawOffers,
                syncedAt = _snapshot.value.syncStatus.lastSyncedEpochMillis,
                totalCount = pageResult.total.coerceAtLeast(filteredMerged.size),
                hasMore = pageResult.hasMore,
                currentPage = pageResult.page,
                etag = listEtag,
                lastModified = listLastModified,
            )
        }

        return Result.success(filteredMerged)
    }

    override suspend fun getOfferDetail(
        offerId: String,
        forceRefresh: Boolean,
    ): Result<OverseasOfferDetail> = withContext(ioDispatcher) {
        loadFromCache()
        val key = offerId.trim()
        if (key.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("促销 ID 不能为空"))
        }

        val cachedDto = readCachedDetailDto(key)
        if (!forceRefresh && cachedDto != null) {
            val domainDetail = cachedDto.toDomain()
            val effectiveTtl = if (domainDetail.isPendingAiHydration) {
                pendingDetailCacheTtlMillis
            } else {
                detailCacheTtlMillis
            }
            val ageMs = nowMillisProvider() - cachedDto.cachedAtEpochMillis
            if (effectiveTtl <= 0L || ageMs < effectiveTtl) {
                return@withContext Result.success(domainDetail)
            }
        }

        coalesceInFlight("detail:$key") {
            executeFetchOfferDetail(key)
        }
    }

    private suspend fun executeFetchOfferDetail(key: String): Result<OverseasOfferDetail> {
        val cachedDto = readCachedDetailDto(key)
        val cachedDetail = cachedDto?.toDomain()

        val condResult = try {
            apiClient.fetchOfferDetailConditional(
                offerId = key,
                ifNoneMatch = cachedDto?.etag,
                ifModifiedSince = cachedDto?.lastModified,
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (cachedDetail != null) {
                return Result.success(cachedDetail)
            }
            return Result.failure(t)
        }

        if (condResult.notModified && cachedDetail != null) {
            saveCachedDetail(
                detail = cachedDetail,
                etag = condResult.etag ?: cachedDto.etag,
                lastModified = condResult.lastModified ?: cachedDto.lastModified,
            )
            return Result.success(cachedDetail)
        }

        val freshDetail = condResult.data
            ?: return cachedDetail?.let { Result.success(it) }
                ?: Result.failure(IOException("Empty offer detail response"))

        val mergedWithPrevTranslation = if (
            cachedDetail != null &&
            cachedDetail.tgMarkdown == freshDetail.tgMarkdown &&
            !cachedDetail.translatedMarkdown.isNullOrBlank()
        ) {
            freshDetail.copy(
                translatedMarkdown = cachedDetail.translatedMarkdown,
                translationTier = cachedDetail.translationTier,
            )
        } else {
            freshDetail
        }

        val config = _snapshot.value.translationConfig
        val finalDetail = if (config.autoTranslateOnRefresh && mergedWithPrevTranslation.translatedMarkdown.isNullOrBlank()) {
            val trans = runCatching {
                translator.translateMarkdown(
                    markdown = mergedWithPrevTranslation.tgMarkdown,
                    config = config,
                    forceRefresh = false,
                )
            }.getOrNull()
            if (trans != null && trans.translatedText.isNotBlank()) {
                mergedWithPrevTranslation.copy(
                    translatedMarkdown = trans.translatedText,
                    translationTier = trans.tier,
                )
            } else {
                mergedWithPrevTranslation
            }
        } else {
            mergedWithPrevTranslation
        }

        saveCachedDetail(
            detail = finalDetail,
            etag = condResult.etag,
            lastModified = condResult.lastModified,
        )
        return Result.success(finalDetail)
    }

    override suspend fun translateOfferDetail(
        offerId: String,
        forceRefresh: Boolean,
    ): Result<OverseasOfferDetail> = withContext(ioDispatcher) {
        val baseDetailResult = getOfferDetail(offerId = offerId, forceRefresh = false)
        val detail = baseDetailResult.getOrElse { return@withContext Result.failure(it) }

        val config = _snapshot.value.translationConfig
        return@withContext try {
            val transResult = translator.translateMarkdown(
                markdown = detail.tgMarkdown,
                config = config,
                forceRefresh = forceRefresh,
            )
            val updated = detail.copy(
                translatedMarkdown = transResult.translatedText,
                translationTier = transResult.tier,
            )
            val existingDto = readCachedDetailDto(detail.id)
            saveCachedDetail(
                detail = updated,
                etag = existingDto?.etag,
                lastModified = existingDto?.lastModified,
            )
            Result.success(updated)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Result.failure(t)
        }
    }

    override suspend fun getRepostContent(
        offerId: String,
        forceRefresh: Boolean,
    ): Result<OverseasRepostContent> = withContext(ioDispatcher) {
        val key = offerId.trim()
        if (key.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("促销 ID 不能为空"))
        }

        val cachedDto = readCachedRepostDto(key)
        if (!forceRefresh && cachedDto != null) {
            val ageMs = nowMillisProvider() - cachedDto.cachedAtEpochMillis
            if (detailCacheTtlMillis <= 0L || ageMs < detailCacheTtlMillis) {
                return@withContext Result.success(cachedDto.toDomain())
            }
        }

        coalesceInFlight("repost:$key") {
            executeFetchRepost(key)
        }
    }

    private suspend fun executeFetchRepost(key: String): Result<OverseasRepostContent> {
        val cachedDto = readCachedRepostDto(key)
        val cachedRepost = cachedDto?.toDomain()

        val condResult = try {
            apiClient.fetchOfferRepostConditional(
                offerId = key,
                ifNoneMatch = cachedDto?.etag,
                ifModifiedSince = cachedDto?.lastModified,
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (cachedRepost != null) {
                return Result.success(cachedRepost)
            }
            return Result.failure(t)
        }

        if (condResult.notModified && cachedRepost != null) {
            saveCachedRepost(
                repost = cachedRepost,
                etag = condResult.etag ?: cachedDto.etag,
                lastModified = condResult.lastModified ?: cachedDto.lastModified,
            )
            return Result.success(cachedRepost)
        }

        val fresh = condResult.data
            ?: return cachedRepost?.let { Result.success(it) }
                ?: Result.failure(IOException("Empty repost response"))

        saveCachedRepost(
            repost = fresh,
            etag = condResult.etag,
            lastModified = condResult.lastModified,
        )
        return Result.success(fresh)
    }

    override suspend fun updateTranslationConfig(config: OverseasTranslationConfig) = withContext(ioDispatcher) {
        ioMutex.withLock {
            writePrefsLocked(config, filterConfig)
            _snapshot.update { it.copy(translationConfig = config) }
        }
    }

    override suspend fun updateDisplayMode(mode: OverseasDisplayMode) = withContext(ioDispatcher) {
        ioMutex.withLock {
            val updated = _snapshot.value.translationConfig.copy(displayMode = mode)
            writePrefsLocked(updated, filterConfig)
            _snapshot.update { it.copy(translationConfig = updated) }
        }
    }

    override suspend fun updateFilterConfig(config: OverseasOfferFilterConfig) = withContext(ioDispatcher) {
        loadFromCache()
        ioMutex.withLock {
            filterConfig = config
            writePrefsLocked(_snapshot.value.translationConfig, config)
            val filtered = OverseasOfferQualityFilter.filter(rawOffers, config)
            val filteredOut = (rawOffers.size - filtered.size).coerceAtLeast(0)
            _snapshot.update {
                it.copy(
                    offers = filtered,
                    filterConfig = config,
                    filteredOutCount = filteredOut,
                )
            }
        }
    }

    override suspend fun testTranslationConfig(config: OverseasTranslationConfig): Result<String> =
        translator.testConnection(config)

    override suspend fun clearTranslationCache() = withContext(ioDispatcher) {
        translator.clearCache()
        detailMutex.withLock {
            ensureDetailsLoadedLocked()
            val cleared = detailMemoryCache.mapValues { (_, dto) ->
                dto.copy(translatedMarkdown = null, translationTierName = TranslationTier.NONE.name)
            }
            detailMemoryCache.clear()
            detailMemoryCache.putAll(cleared)
            persistDetailCacheLocked()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> coalesceInFlight(
        key: String,
        block: suspend () -> Result<T>,
    ): Result<T> {
        val deferred: Deferred<Result<T>> = inFlightMutex.withLock {
            val existing = inFlightRequests[key] as? Deferred<Result<T>>
            if (existing != null && existing.isActive) {
                existing
            } else {
                var holder: Deferred<Result<Any?>>? = null
                val created = sharedInFlightScope.async {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        block() as Result<Any?>
                    } finally {
                        withContext(NonCancellable) {
                            inFlightMutex.withLock {
                                if (inFlightRequests[key] === holder) {
                                    inFlightRequests.remove(key)
                                }
                            }
                        }
                    }
                }
                holder = created
                inFlightRequests[key] = created
                created as Deferred<Result<T>>
            }
        }
        return deferred.await()
    }

    private suspend fun persistListCache(
        offers: List<OverseasDealOffer>,
        syncedAt: Long,
        totalCount: Int,
        hasMore: Boolean,
        currentPage: Int,
        etag: String?,
        lastModified: String?,
    ) {
        ioMutex.withLock {
            val payload = OverseasListDiskCachePayload(
                lastSyncedEpochMillis = syncedAt,
                totalCount = totalCount,
                hasMore = hasMore,
                currentPage = currentPage,
                offers = offers.take(MAX_CACHED_OFFERS).map { it.toDto() },
                etag = etag,
                lastModified = lastModified,
            )
            writeJsonAtomically(listCacheFile, json.encodeToString(payload), restrictPermissions = false)
        }
    }

    private fun readListCacheLocked(): OverseasListDiskCachePayload {
        if (!listCacheFile.exists()) return OverseasListDiskCachePayload()
        return runCatching {
            val content = listCacheFile.readText(Charsets.UTF_8)
            if (content.isBlank()) OverseasListDiskCachePayload()
            else json.decodeFromString<OverseasListDiskCachePayload>(content)
        }.getOrDefault(OverseasListDiskCachePayload())
    }

    private suspend fun readCachedDetailDto(offerId: String): CachedOfferDetailDto? =
        detailMutex.withLock {
            ensureDetailsLoadedLocked()
            detailMemoryCache[offerId]
        }

    private suspend fun saveCachedDetail(
        detail: OverseasOfferDetail,
        etag: String? = null,
        lastModified: String? = null,
    ) {
        detailMutex.withLock {
            ensureDetailsLoadedLocked()
            detailMemoryCache[detail.id] = detail.toDto(
                cachedAt = nowMillisProvider(),
                etag = etag,
                lastModified = lastModified,
            )
            trimMapLocked(detailMemoryCache, MAX_CACHED_DETAILS) { it.cachedAtEpochMillis }
            persistDetailCacheLocked()
        }
    }

    private suspend fun readCachedRepostDto(offerId: String): CachedRepostDto? =
        detailMutex.withLock {
            ensureDetailsLoadedLocked()
            repostMemoryCache[offerId]
        }

    private suspend fun saveCachedRepost(
        repost: OverseasRepostContent,
        etag: String? = null,
        lastModified: String? = null,
    ) {
        detailMutex.withLock {
            ensureDetailsLoadedLocked()
            repostMemoryCache[repost.id] = repost.toDto(
                cachedAt = nowMillisProvider(),
                etag = etag,
                lastModified = lastModified,
            )
            trimMapLocked(repostMemoryCache, MAX_CACHED_DETAILS) { it.cachedAtEpochMillis }
            persistDetailCacheLocked()
        }
    }

    private fun ensureDetailsLoadedLocked() {
        if (detailsDiskLoaded) return
        detailsDiskLoaded = true
        if (!detailCacheFile.exists()) return
        val payload = runCatching {
            val raw = detailCacheFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) null else json.decodeFromString<OverseasDetailRepostCachePayload>(raw)
        }.getOrNull() ?: return

        detailMemoryCache.clear()
        detailMemoryCache.putAll(payload.details)
        repostMemoryCache.clear()
        repostMemoryCache.putAll(payload.reposts)
    }

    private fun <V> trimMapLocked(
        map: LinkedHashMap<String, V>,
        maxEntries: Int,
        timestampSelector: (V) -> Long,
    ) {
        if (map.size <= maxEntries) return
        val kept = map.entries
            .sortedByDescending { timestampSelector(it.value) }
            .take(maxEntries)
        map.clear()
        kept.forEach { (k, v) -> map[k] = v }
    }

    private fun persistDetailCacheLocked() {
        val payload = OverseasDetailRepostCachePayload(
            details = detailMemoryCache.toMap(),
            reposts = repostMemoryCache.toMap(),
        )
        writeJsonAtomically(detailCacheFile, json.encodeToString(payload), restrictPermissions = false)
    }

    private fun readPrefsLocked(): Pair<OverseasTranslationConfig, OverseasOfferFilterConfig> {
        if (!prefsFile.exists()) return OverseasTranslationConfig() to OverseasOfferFilterConfig()
        val dto = runCatching {
            val content = prefsFile.readText(Charsets.UTF_8)
            if (content.isBlank()) null
            else json.decodeFromString<OverseasTranslatorPrefsPayload>(content)
        }.getOrNull() ?: return OverseasTranslationConfig() to OverseasOfferFilterConfig()

        val translation = OverseasTranslationConfig(
            engineKind = runCatching { TranslationEngineKind.valueOf(dto.engineKindName) }
                .getOrDefault(TranslationEngineKind.AUTO_GTX_WITH_GLOSSARY),
            customEndpointUrl = dto.customEndpointUrl,
            customApiKey = dto.customApiKey,
            customModel = dto.customModel.ifBlank { OverseasTranslationConfig.DEFAULT_OPENAI_MODEL },
            autoTranslateOnRefresh = dto.autoTranslateOnRefresh,
            displayMode = runCatching { OverseasDisplayMode.valueOf(dto.displayModeName) }
                .getOrDefault(OverseasDisplayMode.ORIGINAL),
        )
        val filter = OverseasOfferFilterConfig(
            enabled = dto.filterEnabled,
            excludeGenericMarketingBuzz = dto.excludeGenericMarketingBuzz,
            excludeNonServerServices = dto.excludeNonServerServices,
            requireMinimumHostingSignal = dto.requireMinimumHostingSignal,
            blockedMerchants = dto.blockedMerchants.toSet(),
            extraBlockedKeywords = dto.extraBlockedKeywords.toSet(),
        )
        return translation to filter
    }

    private fun writePrefsLocked(
        translation: OverseasTranslationConfig,
        filter: OverseasOfferFilterConfig,
    ) {
        val dto = OverseasTranslatorPrefsPayload(
            engineKindName = translation.engineKind.name,
            customEndpointUrl = translation.customEndpointUrl.trim(),
            customApiKey = translation.customApiKey.trim(),
            customModel = translation.customModel.trim().ifBlank { OverseasTranslationConfig.DEFAULT_OPENAI_MODEL },
            autoTranslateOnRefresh = translation.autoTranslateOnRefresh,
            displayModeName = translation.displayMode.name,
            filterEnabled = filter.enabled,
            excludeGenericMarketingBuzz = filter.excludeGenericMarketingBuzz,
            excludeNonServerServices = filter.excludeNonServerServices,
            requireMinimumHostingSignal = filter.requireMinimumHostingSignal,
            blockedMerchants = filter.blockedMerchants.map { it.trim() }.filter { it.isNotEmpty() },
            extraBlockedKeywords = filter.extraBlockedKeywords.map { it.trim() }.filter { it.isNotEmpty() },
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

    private fun OverseasDealOffer.toDto(): CachedOfferItemDto = CachedOfferItemDto(
        id = id,
        title = title,
        originalTitle = originalTitle,
        summary = summary,
        merchant = merchant,
        sourceLink = sourceLink,
        publishedAtEpochMillis = publishedAtEpochMillis,
        publishedAtIso = publishedAtIso,
    )

    private fun CachedOfferItemDto.toDomain(): OverseasDealOffer = OverseasDealOffer(
        id = id,
        title = title,
        originalTitle = originalTitle,
        summary = summary,
        merchant = merchant,
        sourceLink = sourceLink,
        publishedAtEpochMillis = publishedAtEpochMillis,
        publishedAtIso = publishedAtIso,
    )

    private fun OverseasOfferDetail.toDto(
        cachedAt: Long,
        etag: String? = null,
        lastModified: String? = null,
    ): CachedOfferDetailDto = CachedOfferDetailDto(
        id = id,
        title = title,
        originalTitle = originalTitle,
        merchant = merchant,
        author = author,
        publishedAtEpochMillis = publishedAtEpochMillis,
        publishedAtIso = publishedAtIso,
        sourceLink = sourceLink,
        tgMarkdown = tgMarkdown,
        tgHtml = tgHtml,
        tgPlainText = tgPlainText,
        structured = structured?.let { st ->
            CachedStructuredDto(
                titleCn = st.titleCn,
                sections = st.sections.map { CachedDetailSectionDto(it.header, it.items) },
                orderLinks = st.orderLinks.map { CachedLabeledLinkDto(it.text, it.url) },
                promoCode = st.promoCode,
                pros = st.pros,
                cons = st.cons,
                suitableFor = st.suitableFor,
                aiComment = st.aiComment,
                viewCount = st.viewCount,
                commentCount = st.commentCount,
                aiModel = st.aiModel,
            )
        },
        translatedMarkdown = translatedMarkdown,
        translationTierName = translationTier.name,
        cachedAtEpochMillis = cachedAt,
        etag = etag,
        lastModified = lastModified,
    )

    private fun CachedOfferDetailDto.toDomain(): OverseasOfferDetail = OverseasOfferDetail(
        id = id,
        title = title,
        originalTitle = originalTitle,
        merchant = merchant,
        author = author,
        publishedAtEpochMillis = publishedAtEpochMillis,
        publishedAtIso = publishedAtIso,
        sourceLink = sourceLink,
        tgMarkdown = tgMarkdown,
        tgHtml = tgHtml,
        tgPlainText = tgPlainText,
        structured = structured?.let { st ->
            OverseasDetailStructured(
                titleCn = st.titleCn,
                sections = st.sections.map { OverseasDetailSection(it.header, it.items) },
                orderLinks = st.orderLinks.map { OverseasLabeledLink(it.text, it.url) },
                promoCode = st.promoCode,
                pros = st.pros,
                cons = st.cons,
                suitableFor = st.suitableFor,
                aiComment = st.aiComment,
                viewCount = st.viewCount,
                commentCount = st.commentCount,
                aiModel = st.aiModel,
            )
        },
        translatedMarkdown = translatedMarkdown,
        translationTier = runCatching { TranslationTier.valueOf(translationTierName) }
            .getOrDefault(TranslationTier.NONE),
    )

    private fun OverseasRepostContent.toDto(
        cachedAt: Long,
        etag: String? = null,
        lastModified: String? = null,
    ): CachedRepostDto = CachedRepostDto(
        id = id,
        title = title,
        titleCn = titleCn,
        merchant = merchant,
        author = author,
        publishedAtEpochMillis = publishedAtEpochMillis,
        publishedAtIso = publishedAtIso,
        sourceLink = sourceLink,
        bodyMarkdown = bodyMarkdown,
        bodyText = bodyText,
        bodyHtml = bodyHtml,
        images = images.map { CachedMediaImageDto(it.alt, it.url) },
        links = links.map { CachedLabeledLinkDto(it.text, it.url) },
        suggestedCategorySlug = suggestedCategorySlug,
        cachedAtEpochMillis = cachedAt,
        etag = etag,
        lastModified = lastModified,
    )

    private fun CachedRepostDto.toDomain(): OverseasRepostContent = OverseasRepostContent(
        id = id,
        title = title,
        titleCn = titleCn,
        merchant = merchant,
        author = author,
        publishedAtEpochMillis = publishedAtEpochMillis,
        publishedAtIso = publishedAtIso,
        sourceLink = sourceLink,
        bodyMarkdown = bodyMarkdown,
        bodyText = bodyText,
        bodyHtml = bodyHtml,
        images = images.map { OverseasMediaImage(it.alt, it.url) },
        links = links.map { OverseasLabeledLink(it.text, it.url) },
        suggestedCategorySlug = suggestedCategorySlug,
    )

    companion object {
        const val LIST_CACHE_FILE_NAME = "overseas_intel_offers_v2.json"
        const val DETAIL_CACHE_FILE_NAME = "overseas_intel_details_v2.json"
        const val TRANSLATOR_PREFS_FILE_NAME = "overseas_translator_prefs.json"
        private const val MAX_CACHED_OFFERS = 160
        private const val MAX_CACHED_DETAILS = 80
        const val DEFAULT_MIN_REFRESH_COOLDOWN_MS = 8_000L
        const val DEFAULT_DETAIL_CACHE_TTL_MS = 15L * 60_000L
        const val DEFAULT_PENDING_DETAIL_TTL_MS = 10_000L
        const val DEFAULT_LOAD_MORE_ERROR_COOLDOWN_MS = 5_000L
    }
}
