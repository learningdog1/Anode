@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nodeseek.app.data.repository

import com.nodeseek.app.data.local.BookmarkDao
import com.nodeseek.app.data.local.CachedFeedSnapshot
import com.nodeseek.app.data.local.CategoryDao
import com.nodeseek.app.data.local.CategoryEntity
import com.nodeseek.app.data.local.FeedDiskCache
import com.nodeseek.app.data.local.ReadHistoryDao
import com.nodeseek.app.data.local.toDomain
import com.nodeseek.app.data.local.toEntity
import com.nodeseek.app.data.network.CloudflareChallengeException
import com.nodeseek.app.data.network.HomepageCategoryPrefDto
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.data.parser.CategoryDto
import com.nodeseek.app.data.parser.NodeSeekPostAccessException
import com.nodeseek.app.data.parser.ParsedFeedPageDto
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.ReactionResult
import com.nodeseek.app.domain.model.ReactionType
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Concrete [ForumRepository] orchestrating Web HTML + `temp-script` feeds, RSS fallback,
 * category Room synchronization (12h TTL), thread details, publishing/editing, and reactions.
 */
class ForumRepositoryImpl(
    private val apiClientProvider: () -> NodeSeekApiClient,
    private val sessionRepository: SessionRepositoryImpl,
    private val settingsRepository: SettingsRepository,
    private val categoryDao: CategoryDao,
    private val readHistoryDao: ReadHistoryDao,
    private val bookmarkDao: BookmarkDao,
    private val bookmarkAndHistoryRepository: BookmarkAndHistoryRepositoryImpl? = null,
    private val backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val feedDiskCache: FeedDiskCache? = null,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : ForumRepository {

    private data class FeedRequestKey(
        val accountUid: Long,
        val categorySlug: String,
        val sort: FeedSort,
        val page: Int,
        val includeBlocked: Boolean,
        val preferRssFallback: Boolean,
    )

    private val memoryFeedSnapshots = ConcurrentHashMap<String, CachedFeedSnapshot>()
    private val feedInFlightMutex = Mutex()
    private val inFlightFeedRequests = HashMap<FeedRequestKey, CompletableDeferred<Result<FeedPageResult>>>()
    private val categoryRefreshMutex = Mutex()
    private var inFlightCategoryRefresh: CompletableDeferred<Result<List<Category>>>? = null
    private var inFlightCategoryRefreshUid: Long? = null
    private val categoryMutationMutex = Mutex()
    private val categoryAuthorityEpoch = AtomicLong(0L)

    private val rssCacheMutex = Mutex()
    private var cachedRssFeed: Pair<Long, ParsedFeedPageDto>? = null

    private val commentIdToTopicId = Collections.synchronizedMap(
        object : LinkedHashMap<Long, Long>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean = size > 2000
        }
    )

    @Volatile
    private var rateLimitedUntilEpochMillis: Long = 0L

    private val currentUid: Long
        get() = sessionRepository.sessionState.value?.uid ?: 0L

    fun onEmbeddedCategoriesObserved(dtos: List<CategoryDto>, accountUid: Long) {
        val capturedGeneration = sessionRepository.currentSessionGeneration()
        backgroundScope.launch {
            runSuspendCatching {
                categoryMutationMutex.withLock {
                    if (accountUid != currentUid || capturedGeneration != sessionRepository.currentSessionGeneration()) {
                        return@withLock
                    }
                    val existing = categoryDao.getCategories(accountUid)
                    val existingBySlug = existing.associateBy { it.slug }
                    val mapped = mergeWithAllCategory(dtos, existingBySlug = existingBySlug)
                    if (mapped.size > 1) {
                        val ordered = resolveCategoryOrder(
                            categories = mapped,
                            customOrder = settingsRepository.getHomepageCategoryOrder(accountUid),
                            existingBySlug = existingBySlug,
                        )
                        val now = clockMillis()
                        val preservedSyncedAt = existing.maxOfOrNull { it.syncedAtEpochMillis } ?: now
                        val entities = ordered.mapIndexed { index, cat ->
                            cat.toEntity(
                                accountUid = accountUid,
                                sortOrder = index,
                                syncedAtEpochMillis = if (existing.isEmpty()) now else preservedSyncedAt,
                            )
                        }
                        if (existing.isEmpty() || hasCategoryMetadataChange(existing, entities)) {
                            categoryDao.replaceForAccount(accountUid, entities)
                        }
                    }
                }
            }
        }
    }

    private fun hasCategoryMetadataChange(
        existing: List<CategoryEntity>,
        updated: List<CategoryEntity>,
    ): Boolean {
        if (existing.size != updated.size) return true
        for (i in existing.indices) {
            val a = existing[i]
            val b = updated[i]
            if (a.slug != b.slug ||
                a.name != b.name ||
                a.sortOrder != b.sortOrder ||
                a.isCustomizable != b.isCustomizable ||
                a.visibleOnHome != b.visibleOnHome ||
                a.isAdminOnly != b.isAdminOnly
            ) {
                return true
            }
        }
        return false
    }

    override fun observeCategories(): Flow<List<Category>> =
        sessionRepository.sessionState
            .map { it?.uid ?: 0L }
            .distinctUntilChanged()
            .flatMapLatest { uid ->
                categoryDao.observeCategories(uid).map { entities ->
                    if (entities.isEmpty()) {
                        Category.DEFAULT_CATEGORIES
                    } else {
                        entities.map(CategoryEntity::toDomain)
                    }
                }
            }

    override suspend fun refreshCategories(force: Boolean): Result<List<Category>> {
        val uid = currentUid
        if (!force) {
            val cachedResult = categoryMutationMutex.withLock {
                val lastSync = categoryDao.getLastSyncedAtMillis(uid)
                if (lastSync != null && clockMillis() - lastSync < CATEGORY_TTL_MILLIS) {
                    val cached = categoryDao.getCategories(uid)
                    if (cached.isNotEmpty()) {
                        Result.success(cached.map(CategoryEntity::toDomain))
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
            if (cachedResult != null) {
                return cachedResult
            }
        }

        val deferred: CompletableDeferred<Result<List<Category>>>
        val isOwner: Boolean
        categoryRefreshMutex.withLock {
            val existing = inFlightCategoryRefresh
            if (existing != null && inFlightCategoryRefreshUid == uid) {
                deferred = existing
                isOwner = false
            } else {
                deferred = CompletableDeferred()
                inFlightCategoryRefresh = deferred
                inFlightCategoryRefreshUid = uid
                isOwner = true
            }
        }
        if (!isOwner) {
            return deferred.await()
        }

        return try {
            val result = runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
                val targetUid = uid
                val startGeneration = sessionRepository.currentSessionGeneration()
                val response = apiClientProvider().listCategories()
                categoryMutationMutex.withLock {
                    if (targetUid != currentUid || startGeneration != sessionRepository.currentSessionGeneration()) {
                        throw NodeSeekApiException("账号会话已切换，请重试")
                    }
                    if (!response.success || response.data.isEmpty()) {
                        val fallback = categoryDao.getCategories(targetUid).map(CategoryEntity::toDomain)
                        return@withLock fallback.ifEmpty { Category.DEFAULT_CATEGORIES }
                    }

                    val existingBySlug = categoryDao.getCategories(targetUid).associateBy { it.slug }
                    val merged = mergeWithAllCategory(response.data, existingBySlug = existingBySlug)
                    val ordered = resolveCategoryOrder(
                        categories = merged,
                        customOrder = settingsRepository.getHomepageCategoryOrder(targetUid),
                        existingBySlug = existingBySlug,
                    )
                    val now = clockMillis()
                    val entities = ordered.mapIndexed { index, cat ->
                        cat.toEntity(accountUid = targetUid, sortOrder = index, syncedAtEpochMillis = now)
                    }
                    categoryDao.replaceForAccount(targetUid, entities)
                    ordered
                }
            }
            deferred.complete(result)
            result
        } catch (c: CancellationException) {
            deferred.cancel(c)
            throw c
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            categoryRefreshMutex.withLock {
                if (inFlightCategoryRefresh === deferred) {
                    inFlightCategoryRefresh = null
                    inFlightCategoryRefreshUid = null
                }
            }
        }
    }

    override suspend fun syncHomepageCategories(): Result<List<Category>> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val targetUid = currentUid
            val startGeneration = sessionRepository.currentSessionGeneration()
            val startAuthorityEpoch = categoryAuthorityEpoch.get()
            val response = apiClientProvider().getHomepageCategories()
            if (response.success == false) {
                throw NodeSeekApiException(response.message?.takeIf { it.isNotBlank() } ?: "获取首页版块配置失败")
            }

            categoryMutationMutex.withLock {
                if (targetUid != currentUid || startGeneration != sessionRepository.currentSessionGeneration()) {
                    throw NodeSeekApiException("账号会话已切换，请重试")
                }
                if (categoryAuthorityEpoch.get() != startAuthorityEpoch) {
                    return@withLock categoryDao.getCategories(targetUid).map(CategoryEntity::toDomain)
                }

                val existing = categoryDao.getCategories(targetUid)
                val existingBySlug = existing.associateBy { it.slug }
                val baseCategories = if (existing.isNotEmpty()) {
                    existing.map(CategoryEntity::toDomain)
                } else {
                    Category.DEFAULT_CATEGORIES
                }
                val prefBySlug = response.data
                    .mapNotNull { dto ->
                        val slug = dto.category.trim()
                        if (slug.isEmpty()) null else slug to dto.showInIndex
                    }
                    .toMap()

                val updatedKnown = baseCategories.map { cat ->
                    when {
                        cat.slug.isBlank() -> cat.copy(visibleOnHome = true, isCustomizable = false)
                        cat.isCustomizable && prefBySlug.containsKey(cat.slug) ->
                            cat.copy(visibleOnHome = prefBySlug.getValue(cat.slug))
                        else -> cat
                    }
                }
                val knownSlugs = updatedKnown.mapTo(HashSet()) { it.slug }
                val extraFromServer = response.data.mapNotNull { dto ->
                    val slug = dto.category.trim()
                    if (slug.isBlank() || !knownSlugs.add(slug)) return@mapNotNull null
                    Category(
                        slug = slug,
                        name = Category.DEFAULT_CATEGORIES.firstOrNull { it.slug == slug }?.name ?: slug,
                        isCustomizable = true,
                        visibleOnHome = dto.showInIndex,
                        description = Category.defaultDescriptionFor(slug),
                    )
                }

                val customOrder = settingsRepository.getHomepageCategoryOrder(targetUid)
                val serverOrder = response.data
                    .map { it.category.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                val ordered = resolveCategoryOrder(
                    categories = updatedKnown + extraFromServer,
                    customOrder = customOrder.ifEmpty { serverOrder },
                    existingBySlug = existingBySlug,
                )

                if (customOrder.isEmpty() && serverOrder.isNotEmpty()) {
                    val persistedOrder = ordered
                        .filter { it.slug.isNotBlank() && !it.isAdminOnly && it.isCustomizable }
                        .map { it.slug }
                    if (persistedOrder.isNotEmpty()) {
                        settingsRepository.setHomepageCategoryOrder(targetUid, persistedOrder)
                    }
                }

                val now = clockMillis()
                val syncedAt = existing.maxOfOrNull { it.syncedAtEpochMillis } ?: now
                val entities = ordered.mapIndexed { index, cat ->
                    cat.toEntity(
                        accountUid = targetUid,
                        sortOrder = index,
                        syncedAtEpochMillis = syncedAt,
                    )
                }
                categoryDao.replaceForAccount(targetUid, entities)
                categoryAuthorityEpoch.incrementAndGet()
                ordered
            }
        }

    override suspend fun saveHomepageCategories(
        orderedCategories: List<Category>,
        syncToServer: Boolean,
    ): Result<Unit> {
        val customSlugs = orderedCategories
            .map { it.slug.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val startGeneration = sessionRepository.currentSessionGeneration()
        val (targetUid, mergedList) = categoryMutationMutex.withLock {
            val uid = currentUid
            settingsRepository.setHomepageCategoryOrder(uid, customSlugs)

            val existing = categoryDao.getCategories(uid)
            val baseBySlug = if (existing.isNotEmpty()) {
                existing.associate { it.slug to it.toDomain() }
            } else {
                Category.DEFAULT_CATEGORIES.associateBy { it.slug }
            }

            val resultList = buildList {
                val allCategory = baseBySlug[""] ?: Category.DEFAULT_CATEGORIES.first()
                add(allCategory.copy(visibleOnHome = true, isCustomizable = false))

                val seen = HashSet<String>().apply { add("") }
                orderedCategories.forEach { incoming ->
                    val slug = incoming.slug.trim()
                    if (slug.isNotBlank() && seen.add(slug)) {
                        val base = baseBySlug[slug]
                        add(
                            (base ?: incoming).copy(
                                slug = slug,
                                visibleOnHome = if (incoming.isCustomizable) incoming.visibleOnHome else (base?.visibleOnHome ?: true),
                            ),
                        )
                    }
                }
                baseBySlug.values.forEach { remaining ->
                    if (remaining.slug.isNotBlank() && seen.add(remaining.slug)) {
                        add(remaining)
                    }
                }
            }

            val now = clockMillis()
            val syncedAt = existing.maxOfOrNull { it.syncedAtEpochMillis } ?: now
            val entities = resultList.mapIndexed { index, cat ->
                cat.toEntity(
                    accountUid = uid,
                    sortOrder = index,
                    syncedAtEpochMillis = syncedAt,
                )
            }
            categoryDao.replaceForAccount(uid, entities)
            categoryAuthorityEpoch.incrementAndGet()
            uid to resultList
        }

        if (!syncToServer) {
            return Result.success(Unit)
        }

        return runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            if (targetUid != currentUid || startGeneration != sessionRepository.currentSessionGeneration()) {
                throw NodeSeekApiException("账号会话已切换，请重试")
            }
            val payload = mergedList
                .filter { it.slug.isNotBlank() && !it.isAdminOnly && it.isCustomizable }
                .distinctBy { it.slug }
                .map { category ->
                    HomepageCategoryPrefDto(
                        category = category.slug,
                        showInIndex = category.visibleOnHome,
                    )
                }
            val response = apiClientProvider().saveHomepageCategories(payload)
            if (response.success == false) {
                throw NodeSeekApiException(response.message?.takeIf { it.isNotBlank() } ?: "保存首页版块配置失败")
            }
        }
    }

    private fun isGuestWithoutStoredSession(): Boolean =
        sessionRepository.sessionState.value == null && !sessionRepository.hasStoredSessionCookie()

    private fun shouldPreferRssForPage1(trimmedSlug: String): Boolean =
        settingsRepository.settings.value.preferRssFallback &&
            isGuestWithoutStoredSession() &&
            trimmedSlug != "inside"

    override fun clearRssFallbackCache() {
        val iterator = memoryFeedSnapshots.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.result.isFromRssFallback) {
                iterator.remove()
            }
        }
        feedDiskCache?.removeWhere { it.result.isFromRssFallback }
    }

    override suspend fun resolveKnownTopics(topicIds: Collection<Long>): Map<Long, TopicSummary> {
        val uniqueIds = topicIds.filter { it > 0L }.toSet()
        if (uniqueIds.isEmpty()) return emptyMap()

        val resolved = LinkedHashMap<Long, TopicSummary>(uniqueIds.size)

        // 1. Check in-memory recent summary cache (populated by every feed, search, and topic detail view).
        bookmarkAndHistoryRepository?.let { repo ->
            for (id in uniqueIds) {
                repo.getCachedTopicSummary(id)
                    ?.takeIf { it.categorySlug.isNotBlank() || it.categoryName.isNotBlank() }
                    ?.let { resolved[id] = it }
            }
        }
        if (resolved.size >= uniqueIds.size) return resolved

        // 2. Check cached feed snapshots (including persisted disk snapshots for trade & home feeds).
        val uid = currentUid
        val includeBlocked = settingsRepository.settings.value.includeBlockedTopics
        runSuspendCatching {
            getOrLoadSnapshot(uid, "trade", FeedSort.LATEST_REPLY, includeBlocked)
            getOrLoadSnapshot(uid, "trade", FeedSort.NEWEST_TOPIC, includeBlocked)
            getOrLoadSnapshot(uid, "", FeedSort.LATEST_REPLY, includeBlocked)
        }
        for ((key, snapshot) in memoryFeedSnapshots) {
            val keyUid = FeedDiskCache.extractUidFromCacheKey(key)
            if (keyUid != uid) continue
            for (topic in snapshot.result.pinnedTopics + snapshot.result.topics) {
                if (topic.id in uniqueIds && !resolved.containsKey(topic.id) &&
                    (topic.categorySlug.isNotBlank() || topic.categoryName.isNotBlank())
                ) {
                    resolved[topic.id] = topic
                    if (resolved.size >= uniqueIds.size) return resolved
                }
            }
        }

        // 3. Check local read history and bookmarks in Room for any remaining topic IDs.
        val missingIds = uniqueIds.filterNot(resolved::containsKey)
        if (missingIds.isNotEmpty()) {
            runSuspendCatching {
                readHistoryDao.getByTopicIds(uid, missingIds.take(500)).forEach { entity ->
                    val slug = entity.categorySlug.trim()
                    val name = entity.categoryName.trim()
                    if (slug.isNotEmpty() || name.isNotEmpty()) {
                        resolved[entity.topicId] = TopicSummary(
                            id = entity.topicId,
                            title = entity.title,
                            categorySlug = slug,
                            categoryName = name,
                            authorName = entity.authorName,
                            authorAvatarUrl = entity.authorAvatarUrl,
                        )
                    }
                }
            }
            val stillMissing = missingIds.filterNot(resolved::containsKey)
            for (id in stillMissing) {
                val bookmark = runSuspendCatching { bookmarkDao.getBookmark(uid, id) }.getOrNull() ?: continue
                val slug = bookmark.categorySlug.trim()
                val name = bookmark.categoryName.trim()
                if (slug.isNotEmpty() || name.isNotEmpty()) {
                    resolved[id] = TopicSummary(
                        id = bookmark.topicId,
                        title = bookmark.title,
                        categorySlug = slug,
                        categoryName = name,
                        authorId = bookmark.authorId,
                        authorName = bookmark.authorName,
                        authorAvatarUrl = bookmark.authorAvatarUrl,
                        viewCount = bookmark.viewCount,
                        replyCount = bookmark.replyCount,
                        lastActivityIso = bookmark.lastActivityIso,
                        isBookmarked = true,
                    )
                }
            }
        }

        return resolved
    }

    override suspend fun getLocalCachedTopics(categorySlug: String): List<TopicSummary> {
        val trimmed = categorySlug.trim()
        val activeUid = currentUid
        val allowedUids = setOf(activeUid)
        val snapshots = ArrayList<CachedFeedSnapshot>()
        feedDiskCache?.let { disk ->
            runSuspendCatching {
                snapshots += disk.getSnapshotsForCategory(trimmed, allowedUids = allowedUids)
            }
        }
        val includeBlocked = settingsRepository.settings.value.includeBlockedTopics
        for (sort in listOf(FeedSort.LATEST_REPLY, FeedSort.NEWEST_TOPIC)) {
            runSuspendCatching {
                getOrLoadSnapshot(activeUid, trimmed, sort, includeBlocked)?.let { snapshots += it }
                getOrLoadSnapshot(activeUid, "", sort, includeBlocked)?.let { snapshots += it }
            }
        }
        for ((key, snap) in memoryFeedSnapshots) {
            val keyUid = FeedDiskCache.extractUidFromCacheKey(key)
            if (keyUid == activeUid) {
                snapshots += snap
            }
        }

        val isTradeQuery = trimmed.equals("trade", ignoreCase = true)
        val deduped = LinkedHashMap<Long, TopicSummary>()
        for (snap in snapshots.sortedByDescending { it.fetchedAtEpochMillis }) {
            for (topic in snap.result.pinnedTopics + snap.result.topics) {
                if (topic.id <= 0L) continue
                val matchesCategory = when {
                    trimmed.isEmpty() -> true
                    topic.categorySlug.equals(trimmed, ignoreCase = true) -> true
                    isTradeQuery && topic.categoryName.trim() == "交易" -> true
                    isTradeQuery && topic.categorySlug.isBlank() && topic.categoryName.isBlank() &&
                        com.nodeseek.app.domain.trust.TrustRiskEvaluator.isTradeTopic(topic) -> true
                    else -> false
                }
                if (matchesCategory && !deduped.containsKey(topic.id)) {
                    deduped[topic.id] = topic
                }
            }
        }
        return deduped.values.toList()
    }

    override fun getCachedFeed(
        categorySlug: String,
        sort: FeedSort,
    ): FeedPageResult? {
        val trimmed = categorySlug.trim()
        val includeBlocked = settingsRepository.settings.value.includeBlockedTopics
        val snap = getMemorySnapshot(
            uid = currentUid,
            categorySlug = trimmed,
            sort = sort,
            includeBlocked = includeBlocked,
        ) ?: return null
        if (snap.result.isFromRssFallback &&
            !shouldPreferRssForPage1(trimmed) &&
            !sessionRepository.challengeRequired.value
        ) {
            return null
        }
        return snap.result
    }

    override fun isFeedFresh(
        categorySlug: String,
        sort: FeedSort,
        maxAgeMillis: Long,
    ): Boolean {
        val trimmed = categorySlug.trim()
        val includeBlocked = settingsRepository.settings.value.includeBlockedTopics
        val snap = getMemorySnapshot(
            uid = currentUid,
            categorySlug = trimmed,
            sort = sort,
            includeBlocked = includeBlocked,
        ) ?: return false
        if (snap.result.isFromRssFallback) {
            val rssStillExpected = shouldPreferRssForPage1(trimmed) ||
                sessionRepository.challengeRequired.value
            if (!rssStillExpected) {
                return false
            }
        }
        val age = clockMillis() - snap.fetchedAtEpochMillis
        return age in 0L until maxAgeMillis
    }

    override suspend fun getFeed(
        categorySlug: String,
        sort: FeedSort,
        page: Int,
        includeBlocked: Boolean,
        forceRefresh: Boolean,
    ): Result<FeedPageResult> {
        val safePage = page.coerceAtLeast(1)
        if (sort == FeedSort.CURATED) {
            return getCuratedFeed(page = safePage, forceRefresh = forceRefresh)
        }

        val settings = settingsRepository.settings.value
        val effectiveIncludeBlocked = includeBlocked || settings.includeBlockedTopics
        val trimmedSlug = categorySlug.trim()
        val uid = currentUid
        val now = clockMillis()
        val usePreferredRss = safePage == 1 && shouldPreferRssForPage1(trimmedSlug)

        if (safePage == 1) {
            val cached = getOrLoadSnapshot(
                uid = uid,
                categorySlug = trimmedSlug,
                sort = sort,
                includeBlocked = effectiveIncludeBlocked,
            )
            val cacheFreshEnoughForForceCooldown = cached != null &&
                (now - cached.fetchedAtEpochMillis) in 0L until MIN_FORCE_REFRESH_INTERVAL_MS &&
                (!cached.result.isFromRssFallback || usePreferredRss)

            if (forceRefresh && cacheFreshEnoughForForceCooldown) {
                return Result.success(reEnrichFeedResult(cached!!.result))
            }
        }

        if (now < rateLimitedUntilEpochMillis) {
            if (!forceRefresh && safePage == 1) {
                getOrLoadSnapshot(
                    uid = uid,
                    categorySlug = trimmedSlug,
                    sort = sort,
                    includeBlocked = effectiveIncludeBlocked,
                )?.let { cached ->
                    return Result.success(reEnrichFeedResult(cached.result))
                }
            }
            return Result.failure(
                NodeSeekApiException(
                    message = "请求过于频繁，请稍等片刻再刷新",
                    statusCode = 429,
                ).mapToDomainThrowable(sessionRepository::reportChallengeRequired),
            )
        }

        val requestKey = FeedRequestKey(
            accountUid = uid,
            categorySlug = trimmedSlug,
            sort = sort,
            page = safePage,
            includeBlocked = effectiveIncludeBlocked,
            preferRssFallback = usePreferredRss,
        )

        return singleFlightFeed(requestKey) {
            if (requestKey.preferRssFallback) {
                return@singleFlightFeed runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
                    val result = fetchAndFilterRssFallback(
                        categorySlug = trimmedSlug,
                        sort = sort,
                        forceRefresh = forceRefresh,
                    )
                    saveSnapshot(
                        uid = uid,
                        categorySlug = trimmedSlug,
                        sort = sort,
                        result = result,
                        includeBlocked = effectiveIncludeBlocked,
                        persistToDisk = false,
                    )
                    result
                }
            }

            val serverSortQuery = when (sort) {
                FeedSort.HOT -> FeedSort.LATEST_REPLY.queryValue
                FeedSort.CURATED -> FeedSort.LATEST_REPLY.queryValue
                else -> sort.queryValue
            }

            try {
                val parsed = apiClientProvider().fetchFeedPage(
                    categorySlug = trimmedSlug,
                    sortBy = serverSortQuery,
                    page = safePage,
                    includeBlocked = effectiveIncludeBlocked,
                )
                val result = parsed.toEnrichedFeedResult(sort = sort)
                if (safePage == 1) {
                    saveSnapshot(
                        uid = currentUid,
                        categorySlug = trimmedSlug,
                        sort = sort,
                        result = result,
                        includeBlocked = effectiveIncludeBlocked,
                    )
                }
                Result.success(result)
            } catch (c: CancellationException) {
                throw c
            } catch (challenge: CloudflareChallengeException) {
                sessionRepository.reportChallengeRequired()
                val isSignedOutGuest = sessionRepository.sessionState.value == null
                if (isSignedOutGuest && safePage == 1 && trimmedSlug != "inside") {
                    val rssFallback = runSuspendCatching {
                        fetchAndFilterRssFallback(
                            categorySlug = trimmedSlug,
                            sort = sort,
                            forceRefresh = false,
                        )
                    }.getOrNull()
                    if (rssFallback != null) {
                        saveSnapshot(
                            uid = uid,
                            categorySlug = trimmedSlug,
                            sort = sort,
                            result = rssFallback,
                            includeBlocked = effectiveIncludeBlocked,
                            persistToDisk = false,
                        )
                        return@singleFlightFeed Result.success(rssFallback)
                    }
                }
                Result.failure(challenge.mapToDomainThrowable(sessionRepository::reportChallengeRequired))
            } catch (apiErr: NodeSeekApiException) {
                if (safePage > 1 && apiErr.statusCode == 404) {
                    return@singleFlightFeed Result.success(
                        FeedPageResult(
                            pinnedTopics = emptyList(),
                            topics = emptyList(),
                            currentPage = safePage,
                            hasNextPage = false,
                        ),
                    )
                }
                recordRateLimitIfNeeded(apiErr)
                Result.failure(apiErr.mapToDomainThrowable(sessionRepository::reportChallengeRequired))
            } catch (t: Throwable) {
                recordRateLimitIfNeeded(t)
                Result.failure(t.mapToDomainThrowable(sessionRepository::reportChallengeRequired))
            }
        }
    }

    override suspend fun getCuratedFeed(
        page: Int,
        forceRefresh: Boolean,
    ): Result<FeedPageResult> {
        val safePage = page.coerceAtLeast(1)
        val uid = currentUid
        val now = clockMillis()
        val includeBlocked = settingsRepository.settings.value.includeBlockedTopics

        if (safePage == 1 && forceRefresh) {
            val cached = getOrLoadSnapshot(
                uid = uid,
                categorySlug = "",
                sort = FeedSort.CURATED,
                includeBlocked = includeBlocked,
            )
            if (cached != null && (now - cached.fetchedAtEpochMillis) in 0L until MIN_FORCE_REFRESH_INTERVAL_MS) {
                return Result.success(reEnrichFeedResult(cached.result))
            }
        }

        if (now < rateLimitedUntilEpochMillis) {
            if (!forceRefresh && safePage == 1) {
                getOrLoadSnapshot(
                    uid = uid,
                    categorySlug = "",
                    sort = FeedSort.CURATED,
                    includeBlocked = includeBlocked,
                )?.let { cached ->
                    return Result.success(reEnrichFeedResult(cached.result))
                }
            }
            return Result.failure(
                NodeSeekApiException(
                    message = "请求过于频繁，请稍等片刻再刷新",
                    statusCode = 429,
                ).mapToDomainThrowable(sessionRepository::reportChallengeRequired),
            )
        }

        val requestKey = FeedRequestKey(
            accountUid = uid,
            categorySlug = "",
            sort = FeedSort.CURATED,
            page = safePage,
            includeBlocked = includeBlocked,
            preferRssFallback = false,
        )

        return singleFlightFeed(requestKey) {
            try {
                val parsed = apiClientProvider().fetchCuratedFeedPage(
                    page = safePage,
                    includeBlocked = includeBlocked,
                )
                val result = parsed.toEnrichedFeedResult(sort = FeedSort.CURATED)
                if (safePage == 1) {
                    saveSnapshot(
                        uid = currentUid,
                        categorySlug = "",
                        sort = FeedSort.CURATED,
                        result = result,
                        includeBlocked = includeBlocked,
                    )
                }
                Result.success(result)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                recordRateLimitIfNeeded(t)
                Result.failure(t.mapToDomainThrowable(sessionRepository::reportChallengeRequired))
            }
        }
    }

    override suspend fun searchTopics(
        query: String,
        categorySlug: String,
        sort: FeedSort,
        page: Int,
    ): Result<FeedPageResult> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val safePage = page.coerceAtLeast(1)
            val normalizedQuery = query.replace(SEARCH_CONTROL_WHITESPACE_REGEX, " ").trim()
            if (normalizedQuery.isEmpty()) {
                return@runDomainCatching FeedPageResult(
                    pinnedTopics = emptyList(),
                    topics = emptyList(),
                    currentPage = safePage,
                    hasNextPage = false,
                )
            }
            val cleanCategory = categorySlug.trim().takeIf {
                it.isEmpty() || VALID_CATEGORY_SLUG_REGEX.matches(it)
            }.orEmpty()
            val includeBlocked = settingsRepository.settings.value.includeBlockedTopics
            val serverSort = if (sort == FeedSort.HOT || sort == FeedSort.CURATED) {
                FeedSort.LATEST_REPLY.queryValue
            } else {
                sort.queryValue
            }
            val parsed = try {
                apiClientProvider().searchTopicsPage(
                    query = normalizedQuery,
                    categorySlug = cleanCategory,
                    sortBy = serverSort,
                    page = safePage,
                    includeBlocked = includeBlocked,
                )
            } catch (err: NodeSeekApiException) {
                if (safePage > 1 && err.statusCode == 404) {
                    return@runDomainCatching FeedPageResult(
                        pinnedTopics = emptyList(),
                        topics = emptyList(),
                        currentPage = safePage,
                        hasNextPage = false,
                    )
                }
                throw err
            }
            parsed.toEnrichedFeedResult(sort = sort)
        }

    override suspend fun getTopicDetail(
        topicId: Long,
        page: Int,
        includeBlocked: Boolean,
    ): Result<TopicDetailPage> {
        val safePage = page.coerceAtLeast(1)
        val effectiveIncludeBlocked = includeBlocked || settingsRepository.settings.value.includeBlockedTopics
        return try {
            val parsed = apiClientProvider().fetchThreadPage(
                postId = topicId,
                page = safePage,
                includeBlocked = effectiveIncludeBlocked,
            )
            val detail = parsed.toDomainDetailPage()
            detail.openingFloor?.let { commentIdToTopicId[it.commentId] = topicId }
            detail.replies.forEach { commentIdToTopicId[it.commentId] = topicId }
            cacheDetailAsSummary(detail)
            if (detail.isBookmarked == true && safePage == 1) {
                bookmarkAndHistoryRepository?.saveOfflineBookmark(
                    summary = detail.toTopicSummary(),
                    detailSnapshot = detail,
                    syncedWithServer = true,
                )
            }
            Result.success(detail)
        } catch (c: CancellationException) {
            throw c
        } catch (e: IOException) {
            if (e !is CloudflareChallengeException &&
                e !is NodeSeekPostAccessException &&
                e !is NodeSeekApiException &&
                safePage == 1
            ) {
                val offlineSnapshot = bookmarkAndHistoryRepository?.getOfflineBookmarkSnapshot(topicId)
                    ?: bookmarkDao.getBookmark(currentUid, topicId)?.decodeOfflineDetail()
                if (offlineSnapshot != null) {
                    return Result.success(offlineSnapshot)
                }
            }
            Result.failure(e.mapToDomainThrowable(sessionRepository::reportChallengeRequired))
        } catch (t: Throwable) {
            Result.failure(t.mapToDomainThrowable(sessionRepository::reportChallengeRequired))
        }
    }

    override suspend fun createTopic(
        categorySlug: String,
        title: String,
        markdownContent: String,
        requiredViewLevel: Int,
    ): Result<Unit> = createTopicWithResult(
        categorySlug = categorySlug,
        title = title,
        markdownContent = markdownContent,
        requiredViewLevel = requiredViewLevel,
    ).map { Unit }

    override suspend fun createTopicWithResult(
        categorySlug: String,
        title: String,
        markdownContent: String,
        requiredViewLevel: Int,
    ): Result<Long?> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val response = apiClientProvider().createDiscussionWithResult(
                title = title.trim(),
                markdownContent = markdownContent,
                categorySlug = categorySlug.trim(),
                requiredRank = requiredViewLevel,
            )
            invalidateCachesAfterWrite(topicId = null)
            response.resolvedPostId
        }

    override suspend fun editTopic(
        topicId: Long,
        title: String,
        markdownContent: String,
        requiredViewLevel: Int,
    ): Result<Unit> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            apiClientProvider().editDiscussion(
                postId = topicId,
                title = title.trim(),
                markdownContent = markdownContent,
                requiredRank = requiredViewLevel,
            )
            invalidateCachesAfterWrite(topicId = topicId)
        }

    override suspend fun postReply(
        topicId: Long,
        markdownContent: String,
    ): Result<Unit> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val response = apiClientProvider().createComment(
                postId = topicId,
                markdownContent = markdownContent,
            )
            response.coin?.let { sessionRepository.updateCoinBalance(it) }
            invalidateCachesAfterWrite(topicId = topicId)
        }

    override suspend fun editReply(
        commentId: Long,
        markdownContent: String,
    ): Result<Unit> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val knownTopicId = commentIdToTopicId[commentId]
            apiClientProvider().editComment(
                commentId = commentId,
                markdownContent = markdownContent,
                postId = knownTopicId,
            )
            invalidateCachesAfterWrite(topicId = knownTopicId)
        }

    override suspend fun setReplyPinned(
        commentId: Long,
        pinned: Boolean,
    ): Result<Unit> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val knownTopicId = commentIdToTopicId[commentId]
            apiClientProvider().setCommentPinned(
                commentId = commentId,
                pinned = pinned,
                postId = knownTopicId,
            )
            invalidateCachesAfterWrite(topicId = knownTopicId)
        }

    private suspend fun invalidateCachesAfterWrite(topicId: Long?) {
        memoryFeedSnapshots.clear()
        feedDiskCache?.clear()
        rssCacheMutex.withLock {
            cachedRssFeed = null
        }
        if (topicId != null && topicId > 0L) {
            runSuspendCatching {
                bookmarkDao.updateOfflineSnapshot(
                    accountUid = currentUid,
                    topicId = topicId,
                    offlineDetailJson = null,
                )
            }
        }
    }

    override suspend fun getFreeDrumstickCost(): Result<Int> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            apiClientProvider().getFreeLikeQuota().drumstickCoinCost
        }

    override suspend fun reactToComment(
        topicId: Long,
        commentId: Long,
        reaction: ReactionType,
    ): Result<ReactionResult> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val resp = apiClientProvider().reactToComment(
                commentId = commentId,
                reactionSegment = reaction.apiSegment,
                postId = topicId,
            )
            val updatedCount = resp.current
                ?: throw NodeSeekApiException("官网没有返回最新数量，请刷新帖子后检查结果。")
            resp.coin?.let { updatedBalance ->
                sessionRepository.updateCoinBalance(updatedBalance)
            }
            ReactionResult(
                updatedCount = updatedCount,
                remainingDrumsticks = resp.coin,
                message = resp.message,
            )
        }

    private suspend fun singleFlightFeed(
        requestKey: FeedRequestKey,
        block: suspend () -> Result<FeedPageResult>,
    ): Result<FeedPageResult> {
        val deferred: CompletableDeferred<Result<FeedPageResult>>
        val isOwner: Boolean
        feedInFlightMutex.withLock {
            val existing = inFlightFeedRequests[requestKey]
            if (existing != null) {
                deferred = existing
                isOwner = false
            } else {
                deferred = CompletableDeferred()
                inFlightFeedRequests[requestKey] = deferred
                isOwner = true
            }
        }
        if (!isOwner) {
            return deferred.await()
        }
        return try {
            val result = block()
            deferred.complete(result)
            result
        } catch (c: CancellationException) {
            deferred.cancel(c)
            throw c
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            feedInFlightMutex.withLock {
                if (inFlightFeedRequests[requestKey] === deferred) {
                    inFlightFeedRequests.remove(requestKey)
                }
            }
        }
    }

    private fun getMemorySnapshot(
        uid: Long,
        categorySlug: String,
        sort: FeedSort,
        includeBlocked: Boolean = false,
    ): CachedFeedSnapshot? {
        val key = FeedDiskCache.buildCacheKey(uid, categorySlug, sort, includeBlocked)
        memoryFeedSnapshots[key]?.let { return it }
        return feedDiskCache?.get(uid, categorySlug, sort, includeBlocked)?.also { loaded ->
            memoryFeedSnapshots[key] = loaded
        }
    }

    private suspend fun getOrLoadSnapshot(
        uid: Long,
        categorySlug: String,
        sort: FeedSort,
        includeBlocked: Boolean = false,
    ): CachedFeedSnapshot? {
        val key = FeedDiskCache.buildCacheKey(uid, categorySlug, sort, includeBlocked)
        memoryFeedSnapshots[key]?.let { return it }
        return feedDiskCache?.getOrLoadFromDisk(uid, categorySlug, sort, includeBlocked)?.also { loaded ->
            memoryFeedSnapshots[key] = loaded
        }
    }

    private fun saveSnapshot(
        uid: Long,
        categorySlug: String,
        sort: FeedSort,
        result: FeedPageResult,
        includeBlocked: Boolean = false,
        persistToDisk: Boolean = !result.isFromRssFallback,
    ) {
        val snapshot = CachedFeedSnapshot(
            fetchedAtEpochMillis = clockMillis(),
            result = result,
        )
        val key = FeedDiskCache.buildCacheKey(uid, categorySlug, sort, includeBlocked)
        memoryFeedSnapshots[key] = snapshot
        if (persistToDisk) {
            feedDiskCache?.put(uid, categorySlug, sort, snapshot, includeBlocked = includeBlocked)
        }
    }

    private fun recordRateLimitIfNeeded(t: Throwable) {
        if (t is NodeSeekApiException && t.statusCode == 429) {
            val backoffSeconds = (t.retryAfterSeconds ?: DEFAULT_RATE_LIMIT_BACKOFF_SECONDS).coerceIn(5L, 120L)
            rateLimitedUntilEpochMillis = clockMillis() + backoffSeconds * 1_000L
        }
    }

    private suspend fun reEnrichFeedResult(result: FeedPageResult): FeedPageResult {
        val uid = currentUid
        val allIds = buildList(result.pinnedTopics.size + result.topics.size) {
            result.pinnedTopics.forEach { add(it.id) }
            result.topics.forEach { add(it.id) }
        }.distinct().take(900)
        if (allIds.isEmpty()) return result

        val readMap = runSuspendCatching {
            readHistoryDao.getByTopicIds(uid, allIds)
                .associate { it.topicId to it.toDomain() }
        }.getOrDefault(emptyMap())
        val bookmarkedIds = runSuspendCatching {
            bookmarkDao.getBookmarkedTopicIds(uid).toSet()
        }.getOrDefault(emptySet())

        fun TopicSummary.withFreshState(): TopicSummary {
            val readEntry = readMap[id]
            return copy(
                isRead = isRead || readEntry != null,
                lastReadFloor = readEntry?.lastReadFloor ?: lastReadFloor,
                isBookmarked = id in bookmarkedIds,
            )
        }

        return result.copy(
            pinnedTopics = result.pinnedTopics.map { it.withFreshState() },
            topics = result.topics.map { it.withFreshState() },
        )
    }

    private suspend fun fetchAndFilterRssFallback(
        categorySlug: String,
        sort: FeedSort,
        forceRefresh: Boolean = false,
    ): FeedPageResult {
        val rssDto = rssCacheMutex.withLock {
            val now = clockMillis()
            val cached = cachedRssFeed
            val canServeCached = cached != null && if (forceRefresh) {
                (now - cached.first) in 0L until MIN_FORCE_REFRESH_INTERVAL_MS
            } else {
                (now - cached.first) in 0L until RSS_CACHE_TTL_MILLIS
            }
            if (canServeCached) {
                cached!!.second
            } else {
                val fresh = apiClientProvider().fetchRssFeed()
                cachedRssFeed = now to fresh
                fresh
            }
        }
        val filtered = if (categorySlug.isBlank()) {
            rssDto
        } else {
            rssDto.copy(
                topics = rssDto.topics.filter { it.categoryKey.equals(categorySlug, ignoreCase = true) },
            )
        }
        return filtered.toEnrichedFeedResult(sort = sort)
    }

    private suspend fun ParsedFeedPageDto.toEnrichedFeedResult(sort: FeedSort): FeedPageResult {
        val uid = currentUid
        val allIds = buildList(pinnedTopics.size + topics.size) {
            pinnedTopics.forEach { add(it.id) }
            topics.forEach { add(it.id) }
        }.distinct().take(900)

        val readMap = if (allIds.isEmpty()) {
            emptyMap()
        } else {
            runSuspendCatching {
                readHistoryDao.getByTopicIds(uid, allIds)
                    .associate { it.topicId to it.toDomain() }
            }.getOrDefault(emptyMap())
        }

        val bookmarkedIds = runSuspendCatching {
            bookmarkDao.getBookmarkedTopicIds(uid).toSet()
        }.getOrDefault(emptySet())

        val mappedPinned = pinnedTopics.map { dto ->
            dto.toDomainSummary(
                readEntry = readMap[dto.id],
                isBookmarked = dto.id in bookmarkedIds,
            )
        }

        val mappedTopicsRaw = topics.map { dto ->
            dto.toDomainSummary(
                readEntry = readMap[dto.id],
                isBookmarked = dto.id in bookmarkedIds,
            )
        }

        val sortedTopics = if (sort == FeedSort.HOT) {
            mappedTopicsRaw.sortedByDescending { topic ->
                topic.replyCount * 8 + topic.viewCount / 20
            }
        } else {
            mappedTopicsRaw
        }

        bookmarkAndHistoryRepository?.cacheTopicSummaries(mappedPinned + sortedTopics)

        return FeedPageResult(
            pinnedTopics = mappedPinned,
            topics = sortedTopics,
            currentPage = currentPage,
            hasNextPage = hasNextPage,
            isFromRssFallback = isFromRssFallback,
        )
    }

    private fun cacheDetailAsSummary(detail: TopicDetailPage) {
        val existing = bookmarkAndHistoryRepository?.getCachedTopicSummary(detail.topicId)
        val mergedSummary = detail.toTopicSummary(existing)
        bookmarkAndHistoryRepository?.cacheTopicSummaries(listOf(mergedSummary))
    }

    private fun TopicDetailPage.toTopicSummary(existing: TopicSummary? = null): TopicSummary {
        val isExactFloorCount = currentPage >= totalPages || totalPages <= 1
        val resolvedReplyCount = when {
            isExactFloorCount -> maxOf(estimatedTotalFloors, existing?.replyCount ?: 0)
            existing != null && existing.replyCount > 0 -> existing.replyCount
            else -> estimatedTotalFloors
        }
        val resolvedActivityIso = existing?.lastActivityIso?.takeIf { it.isNotBlank() }
            ?: replies.lastOrNull()?.createdAtIso?.takeIf { it.isNotBlank() }
            ?: createdAtIso.orEmpty()
        return TopicSummary(
            id = topicId,
            title = title,
            categorySlug = categorySlug.ifBlank { existing?.categorySlug.orEmpty() },
            categoryName = categoryName.ifBlank { existing?.categoryName.orEmpty() },
            authorId = author.uid.takeIf { it > 0L } ?: existing?.authorId,
            authorName = author.username.ifBlank { existing?.authorName.orEmpty() },
            authorAvatarUrl = author.avatarUrl.ifBlank { existing?.authorAvatarUrl.orEmpty() },
            viewCount = maxOf(viewCount, existing?.viewCount ?: 0),
            replyCount = resolvedReplyCount,
            lastActivityIso = resolvedActivityIso,
            requiredViewLevel = requiredViewLevel.takeIf { it > 0 } ?: existing?.requiredViewLevel,
            isLocked = isLocked,
            isBookmarked = isBookmarked == true,
            authorLevel = author.level ?: existing?.authorLevel,
            drumstickCount = maxOf(openingFloor?.drumstickCount ?: 0, existing?.drumstickCount ?: 0),
        )
    }

    private fun mergeWithAllCategory(
        dtos: List<CategoryDto>,
        existingBySlug: Map<String, CategoryEntity> = emptyMap(),
    ): List<Category> {
        val seen = HashSet<String>()
        val dynamic = dtos.mapNotNull { dto ->
            val mapped = dto.toDomainCategoryOrNull() ?: return@mapNotNull null
            val existing = existingBySlug[mapped.slug]
            if (existing != null) {
                mapped.copy(
                    isCustomizable = dto.customizable ?: existing.isCustomizable,
                    visibleOnHome = existing.visibleOnHome,
                    isAdminOnly = dto.adminOnly ?: existing.isAdminOnly,
                )
            } else {
                mapped
            }
        }.filter { seen.add(it.slug) }
        return listOf(Category.DEFAULT_CATEGORIES.first()) + dynamic
    }

    private fun resolveCategoryOrder(
        categories: List<Category>,
        customOrder: List<String>,
        existingBySlug: Map<String, CategoryEntity>,
    ): List<Category> {
        val bySlug = categories.associateBy { it.slug }
        val seen = HashSet<String>()
        return buildList {
            val allTab = bySlug[""] ?: Category.DEFAULT_CATEGORIES.first()
            if (seen.add("")) {
                add(allTab.copy(visibleOnHome = true, isCustomizable = false))
            }
            customOrder.forEach { rawSlug ->
                val slug = rawSlug.trim()
                val candidate = bySlug[slug]
                if (slug.isNotBlank() && candidate != null && seen.add(slug)) {
                    add(candidate)
                }
            }
            val remaining = categories
                .filter { it.slug.isNotBlank() && it.slug !in seen }
                .sortedWith(
                    compareBy<Category>(
                        { existingBySlug[it.slug]?.sortOrder ?: Int.MAX_VALUE },
                    ),
                )
            remaining.forEach { cat ->
                if (seen.add(cat.slug)) {
                    add(cat)
                }
            }
        }
    }

    companion object {
        private const val CATEGORY_TTL_MILLIS = 12L * 60L * 60L * 1000L // 12 hours
        internal const val MIN_FORCE_REFRESH_INTERVAL_MS = 4_000L
        internal const val RSS_CACHE_TTL_MILLIS = 60_000L
        private const val DEFAULT_RATE_LIMIT_BACKOFF_SECONDS = 20L
        private val SEARCH_CONTROL_WHITESPACE_REGEX = Regex("""[\u0000-\u001F\u007F\s]+""")
    }
}
