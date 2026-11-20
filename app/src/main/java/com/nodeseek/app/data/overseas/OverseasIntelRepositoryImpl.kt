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
import com.nodeseek.app.domain.overseas.OverseasRepostContent
import com.nodeseek.app.domain.overseas.OverseasSyncStatus
import com.nodeseek.app.domain.overseas.OverseasTranslationConfig
import com.nodeseek.app.domain.overseas.TranslationEngineKind
import com.nodeseek.app.domain.overseas.TranslationTier
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
    private var diskLoaded = false
    private var detailsDiskLoaded = false
    private val detailMemoryCache = LinkedHashMap<String, CachedOfferDetailDto>()
    private val repostMemoryCache = LinkedHashMap<String, CachedRepostDto>()

    private val _snapshot = MutableStateFlow(OverseasIntelSnapshot())
    override val snapshot: StateFlow<OverseasIntelSnapshot> = _snapshot.asStateFlow()

    override suspend fun loadFromCache(): List<OverseasDealOffer> = withContext(ioDispatcher) {
        ioMutex.withLock {
            if (!diskLoaded) {
                diskLoaded = true
                val prefs = readPrefsLocked()
                val cachedList = readListCacheLocked()
                val restoredOffers = cachedList.offers.map { it.toDomain() }
                _snapshot.value = OverseasIntelSnapshot(
                    offers = restoredOffers,
                    translationConfig = prefs,
                    syncStatus = OverseasSyncStatus(
                        lastSyncedEpochMillis = cachedList.lastSyncedEpochMillis,
                        currentPage = cachedList.currentPage.coerceAtLeast(1),
                        totalCount = cachedList.totalCount.coerceAtLeast(restoredOffers.size),
                        hasMore = cachedList.hasMore,
                        loadedFromCache = restoredOffers.isNotEmpty(),
                    ),
                )
            }
            _snapshot.value.offers
        }
    }

    override suspend fun refresh(query: String): Result<List<OverseasDealOffer>> = withContext(ioDispatcher) {
        loadFromCache()
        val trimmedQuery = query.trim()

        _snapshot.update { current ->
            current.copy(
                syncStatus = current.syncStatus.copy(
                    isRefreshing = true,
                    errorMessage = null,
                ),
            )
        }

        val pageResult = try {
            apiClient.fetchOffersPage(
                page = 1,
                pageSize = pageSize,
                query = trimmedQuery,
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
            return@withContext Result.failure(t)
        }

        val syncedAt = nowMillisProvider()
        val freshOffers = pageResult.items
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
                offers = freshOffers,
                syncStatus = updatedStatus,
            )
        }

        if (trimmedQuery.isEmpty()) {
            persistListCache(
                offers = freshOffers,
                syncedAt = syncedAt,
                totalCount = pageResult.total,
                hasMore = pageResult.hasMore,
                currentPage = pageResult.page,
            )
        }

        Result.success(freshOffers)
    }

    override suspend fun loadNextPage(query: String): Result<List<OverseasDealOffer>> = withContext(ioDispatcher) {
        val current = _snapshot.value
        if (current.syncStatus.isRefreshing || current.syncStatus.isLoadingMore || !current.syncStatus.hasMore) {
            return@withContext Result.success(current.offers)
        }

        val nextPage = current.syncStatus.currentPage + 1
        val trimmedQuery = query.trim()
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
            _snapshot.update {
                it.copy(
                    syncStatus = it.syncStatus.copy(
                        isLoadingMore = false,
                        errorMessage = t.message?.takeIf { m -> m.isNotBlank() } ?: "加载更多失败，请重试",
                    ),
                )
            }
            return@withContext Result.failure(t)
        }

        val merged = (_snapshot.value.offers + pageResult.items)
            .distinctBy { it.id }

        _snapshot.update { state ->
            state.copy(
                offers = merged,
                syncStatus = state.syncStatus.copy(
                    isLoadingMore = false,
                    currentPage = pageResult.page,
                    totalCount = pageResult.total.coerceAtLeast(merged.size),
                    hasMore = pageResult.hasMore,
                    errorMessage = null,
                ),
            )
        }

        if (trimmedQuery.isEmpty()) {
            persistListCache(
                offers = merged,
                syncedAt = _snapshot.value.syncStatus.lastSyncedEpochMillis,
                totalCount = pageResult.total.coerceAtLeast(merged.size),
                hasMore = pageResult.hasMore,
                currentPage = pageResult.page,
            )
        }

        Result.success(merged)
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

        val cachedDetail = readCachedDetail(key)
        if (!forceRefresh && cachedDetail != null) {
            return@withContext Result.success(cachedDetail)
        }

        val freshDetail = try {
            apiClient.fetchOfferDetail(key)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (cachedDetail != null) {
                return@withContext Result.success(cachedDetail)
            }
            return@withContext Result.failure(t)
        }

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

        saveCachedDetail(finalDetail)
        Result.success(finalDetail)
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
            saveCachedDetail(updated)
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

        val cachedRepost = readCachedRepost(key)
        if (!forceRefresh && cachedRepost != null) {
            return@withContext Result.success(cachedRepost)
        }

        return@withContext try {
            val fresh = apiClient.fetchOfferRepost(key)
            saveCachedRepost(fresh)
            Result.success(fresh)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (cachedRepost != null) {
                Result.success(cachedRepost)
            } else {
                Result.failure(t)
            }
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

    private suspend fun persistListCache(
        offers: List<OverseasDealOffer>,
        syncedAt: Long,
        totalCount: Int,
        hasMore: Boolean,
        currentPage: Int,
    ) {
        ioMutex.withLock {
            val payload = OverseasListDiskCachePayload(
                lastSyncedEpochMillis = syncedAt,
                totalCount = totalCount,
                hasMore = hasMore,
                currentPage = currentPage,
                offers = offers.take(MAX_CACHED_OFFERS).map { it.toDto() },
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

    private suspend fun readCachedDetail(offerId: String): OverseasOfferDetail? =
        detailMutex.withLock {
            ensureDetailsLoadedLocked()
            detailMemoryCache[offerId]?.toDomain()
        }

    private suspend fun saveCachedDetail(detail: OverseasOfferDetail) {
        detailMutex.withLock {
            ensureDetailsLoadedLocked()
            detailMemoryCache[detail.id] = detail.toDto(nowMillisProvider())
            trimMapLocked(detailMemoryCache, MAX_CACHED_DETAILS) { it.cachedAtEpochMillis }
            persistDetailCacheLocked()
        }
    }

    private suspend fun readCachedRepost(offerId: String): OverseasRepostContent? =
        detailMutex.withLock {
            ensureDetailsLoadedLocked()
            repostMemoryCache[offerId]?.toDomain()
        }

    private suspend fun saveCachedRepost(repost: OverseasRepostContent) {
        detailMutex.withLock {
            ensureDetailsLoadedLocked()
            repostMemoryCache[repost.id] = repost.toDto(nowMillisProvider())
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
                .getOrDefault(OverseasDisplayMode.ORIGINAL),
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

    private fun OverseasOfferDetail.toDto(cachedAt: Long): CachedOfferDetailDto = CachedOfferDetailDto(
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

    private fun OverseasRepostContent.toDto(cachedAt: Long): CachedRepostDto = CachedRepostDto(
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
    }
}
