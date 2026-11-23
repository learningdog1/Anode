package com.nodeseek.app.ui.explore

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.digest.AwayDigestRepository
import com.nodeseek.app.domain.digest.AwayDigestState
import com.nodeseek.app.domain.digest.NoOpAwayDigestRepository
import com.nodeseek.app.domain.filter.ContentFilterEngine
import com.nodeseek.app.domain.filter.ContentFilterRepository
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.TopicAccessException
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.repository.BookmarkAndHistoryRepository
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Immutable
data class ExploreBoardSliceUiState(
    val categorySlug: String = "",
    val sort: FeedSort = FeedSort.LATEST_REPLY,
    val pinnedTopics: List<TopicSummary> = emptyList(),
    val topics: List<TopicSummary> = emptyList(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isManualRefreshing: Boolean = false,
    val isLoadingNextPage: Boolean = false,
    val currentPage: Int = 1,
    val hasNextPage: Boolean = false,
    val isFromRssFallback: Boolean = false,
    val isPreviewFromAllFeed: Boolean = false,
    val errorMessage: String? = null,
    val isChallengeOrAuthRequired: Boolean = false,
    val nextPageError: String? = null,
)

@Immutable
data class ExploreUiState(
    val selectedCategorySlug: String = "",
    val selectedSort: FeedSort = FeedSort.LATEST_REPLY,
    val categories: List<Category> = Category.DEFAULT_CATEGORIES,
    val pinnedCategorySlugs: Set<String> = AppSettings.DEFAULT_PINNED_CATEGORY_SLUGS,
    val orderedBoardTabs: List<Category> = listOf(Category(slug = "", name = "全部")),
    val slicesBySlug: Map<String, ExploreBoardSliceUiState> = emptyMap(),
    val pinnedTopics: List<TopicSummary> = emptyList(),
    val topics: List<TopicSummary> = emptyList(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isManualRefreshing: Boolean = false,
    val isLoadingNextPage: Boolean = false,
    val currentPage: Int = 1,
    val hasNextPage: Boolean = false,
    val isFromRssFallback: Boolean = false,
    val preferRssFallback: Boolean = false,
    val errorMessage: String? = null,
    val isChallengeOrAuthRequired: Boolean = false,
    val nextPageError: String? = null,
    val session: AccountSession? = null,
    val snackbarMessage: String? = null,
    val awayDigest: AwayDigestState = AwayDigestState(),
    val isAwayDigestExpanded: Boolean = false,
) {
    val activeCategoryName: String
        get() = if (selectedCategorySlug.isBlank()) {
            "全部"
        } else {
            categories.firstOrNull { it.slug == selectedCategorySlug }?.name ?: selectedCategorySlug
        }

    fun sliceFor(slug: String): ExploreBoardSliceUiState =
        slicesBySlug[slug] ?: ExploreBoardSliceUiState(
            categorySlug = slug,
            sort = effectiveSortForCategory(slug, selectedSort),
            isInitialLoading = true,
        )
}

private data class FeedSliceKey(
    val categorySlug: String,
    val sort: FeedSort,
)

private data class BoardPageSlice(
    val rawPinnedTopics: List<TopicSummary> = emptyList(),
    val rawTopics: List<TopicSummary> = emptyList(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isManualRefreshing: Boolean = false,
    val isLoadingNextPage: Boolean = false,
    val currentPage: Int = 1,
    val hasNextPage: Boolean = false,
    val isFromRssFallback: Boolean = false,
    val isPreviewFromAllFeed: Boolean = false,
    val errorMessage: String? = null,
    val isChallengeOrAuthRequired: Boolean = false,
    val nextPageError: String? = null,
    val updatedAtMs: Long = 0L,
)

private data class FeedRawStreamState(
    val selectedCategorySlug: String = "",
    val selectedSort: FeedSort = FeedSort.LATEST_REPLY,
    val slices: Map<FeedSliceKey, BoardPageSlice> = emptyMap(),
    val snackbarMessage: String? = null,
    val isAwayDigestExpanded: Boolean = false,
) {
    val activeKey: FeedSliceKey
        get() = FeedSliceKey(
            categorySlug = selectedCategorySlug,
            sort = effectiveSortForCategory(selectedCategorySlug, selectedSort),
        )

    val activeSlice: BoardPageSlice
        get() = slices[activeKey] ?: BoardPageSlice()
}

private fun effectiveSortForCategory(slug: String, requestedSort: FeedSort): FeedSort =
    if (slug.isNotBlank() && requestedSort == FeedSort.CURATED) {
        FeedSort.LATEST_REPLY
    } else {
        requestedSort
    }

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModel(
    private val forumRepository: ForumRepository,
    private val sessionRepository: SessionRepository,
    private val bookmarkAndHistoryRepository: BookmarkAndHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val contentFilterRepository: ContentFilterRepository,
    initialCategorySlug: String = "",
    private val awayDigestRepository: AwayDigestRepository = NoOpAwayDigestRepository,
) : ViewModel() {

    private val _streamState = MutableStateFlow(
        FeedRawStreamState(selectedCategorySlug = initialCategorySlug),
    )

    private val activeFeedJobs = mutableMapOf<FeedSliceKey, Job>()
    private var neighborPrefetchJob: Job? = null
    private val prefetchMutex = Mutex()
    @Volatile
    private var lastPrefetchCompletedAtMs: Long = 0L
    private var lastObservedIncludeBlocked: Boolean? = null
    private var lastObservedPreferRss: Boolean? = null

    private val categoriesFlow = forumRepository.observeCategories()
        .map { list -> list.ifEmpty { Category.DEFAULT_CATEGORIES } }
        .stateIn(viewModelScope, SharingStarted.Lazily, Category.DEFAULT_CATEGORIES)

    private val bookmarkedIdsFlow = bookmarkAndHistoryRepository.observeBookmarkedIds()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    private val readStateMapFlow = _streamState
        .map { state ->
            buildSet {
                state.slices.values.forEach { slice ->
                    slice.rawPinnedTopics.forEach { add(it.id) }
                    slice.rawTopics.forEach { add(it.id) }
                }
            }
        }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                bookmarkAndHistoryRepository.observeReadStateMap(ids)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    private data class LocalEnrichmentBundle(
        val bookmarkedIds: Set<Long>,
        val readMap: Map<Long, ReadHistoryEntry>,
        val filterConfig: com.nodeseek.app.domain.filter.ContentFilterConfig,
        val awayDigest: AwayDigestState,
        val challengeRequired: Boolean,
    )

    private data class FilteredSliceCacheEntry(
        val rawPinned: List<TopicSummary>,
        val rawTopics: List<TopicSummary>,
        val filterConfig: com.nodeseek.app.domain.filter.ContentFilterConfig,
        val includeBlocked: Boolean,
        val filteredPinned: List<TopicSummary>,
        val filteredTopics: List<TopicSummary>,
    )

    private val filteredSliceCache = HashMap<FeedSliceKey, FilteredSliceCacheEntry>()

    val uiState: StateFlow<ExploreUiState> = combine(
        _streamState,
        categoriesFlow,
        settingsRepository.settings,
        sessionRepository.sessionState,
        combine(
            bookmarkedIdsFlow,
            readStateMapFlow,
            contentFilterRepository.config,
            awayDigestRepository.state,
            sessionRepository.challengeRequired,
        ) { bookmarks, reads, filterConfig, awayDigest, challengeRequired ->
            LocalEnrichmentBundle(bookmarks, reads, filterConfig, awayDigest, challengeRequired)
        },
    ) { raw, categories, settings, session, enrichment ->
        val bookmarkedIds = enrichment.bookmarkedIds
        val readMap = enrichment.readMap
        val filterConfig = enrichment.filterConfig
        val awayDigest = enrichment.awayDigest
        val challengeActive = enrichment.challengeRequired
        val includeBlocked = settings.includeBlockedTopics
        val orderedTabs = buildOrderedTabs(
            categories = categories,
            pinnedCategorySlugs = settings.pinnedCategorySlugs,
            selectedCategorySlug = raw.selectedCategorySlug,
        )
        if (filteredSliceCache.size > 40) {
            filteredSliceCache.clear()
        }

        val enrichedSlices = buildMap<String, ExploreBoardSliceUiState>(orderedTabs.size + 1) {
            orderedTabs.forEach { tab ->
                val tabSort = effectiveSortForCategory(tab.slug, raw.selectedSort)
                val sliceKey = FeedSliceKey(tab.slug, tabSort)
                val slice = raw.slices[sliceKey]
                if (slice == null) {
                    put(
                        tab.slug,
                        ExploreBoardSliceUiState(
                            categorySlug = tab.slug,
                            sort = tabSort,
                            isChallengeOrAuthRequired = challengeActive,
                        ),
                    )
                    return@forEach
                }

                val cachedFilter = filteredSliceCache[sliceKey]
                val filteredEntry = if (cachedFilter != null &&
                    cachedFilter.rawPinned === slice.rawPinnedTopics &&
                    cachedFilter.rawTopics === slice.rawTopics &&
                    cachedFilter.filterConfig == filterConfig &&
                    cachedFilter.includeBlocked == includeBlocked
                ) {
                    cachedFilter
                } else {
                    val fPinned = if (slice.rawPinnedTopics.isEmpty()) {
                        emptyList()
                    } else {
                        ContentFilterEngine.filterTopics(
                            topics = slice.rawPinnedTopics,
                            config = filterConfig,
                            includeBlockedTopics = includeBlocked,
                            activeBoardSlug = tab.slug,
                        )
                    }
                    val fTopics = if (slice.rawTopics.isEmpty()) {
                        emptyList()
                    } else {
                        ContentFilterEngine.filterTopics(
                            topics = slice.rawTopics,
                            config = filterConfig,
                            includeBlockedTopics = includeBlocked,
                            activeBoardSlug = tab.slug,
                        )
                    }
                    FilteredSliceCacheEntry(
                        rawPinned = slice.rawPinnedTopics,
                        rawTopics = slice.rawTopics,
                        filterConfig = filterConfig,
                        includeBlocked = includeBlocked,
                        filteredPinned = fPinned,
                        filteredTopics = fTopics,
                    ).also { filteredSliceCache[sliceKey] = it }
                }

                val visiblePinned = if (filteredEntry.filteredPinned.isEmpty()) {
                    emptyList()
                } else {
                    filteredEntry.filteredPinned.map { it.enrichWithLocalState(bookmarkedIds, readMap) }
                }
                val visibleTopics = if (filteredEntry.filteredTopics.isEmpty()) {
                    emptyList()
                } else {
                    filteredEntry.filteredTopics.map { it.enrichWithLocalState(bookmarkedIds, readMap) }
                }
                put(
                    tab.slug,
                    ExploreBoardSliceUiState(
                        categorySlug = tab.slug,
                        sort = tabSort,
                        pinnedTopics = visiblePinned,
                        topics = visibleTopics,
                        isInitialLoading = slice.isInitialLoading,
                        isRefreshing = slice.isRefreshing,
                        isManualRefreshing = slice.isManualRefreshing,
                        isLoadingNextPage = slice.isLoadingNextPage,
                        currentPage = slice.currentPage,
                        hasNextPage = slice.hasNextPage,
                        isFromRssFallback = slice.isFromRssFallback,
                        isPreviewFromAllFeed = slice.isPreviewFromAllFeed,
                        errorMessage = slice.errorMessage,
                        isChallengeOrAuthRequired = slice.isChallengeOrAuthRequired || challengeActive,
                        nextPageError = slice.nextPageError,
                    ),
                )
            }
        }

        val activeSlice = enrichedSlices[raw.selectedCategorySlug] ?: ExploreBoardSliceUiState(
            categorySlug = raw.selectedCategorySlug,
            sort = effectiveSortForCategory(raw.selectedCategorySlug, raw.selectedSort),
        )

        ExploreUiState(
            selectedCategorySlug = raw.selectedCategorySlug,
            selectedSort = activeSlice.sort,
            categories = categories,
            pinnedCategorySlugs = settings.pinnedCategorySlugs,
            orderedBoardTabs = orderedTabs,
            slicesBySlug = enrichedSlices,
            pinnedTopics = activeSlice.pinnedTopics,
            topics = activeSlice.topics,
            isInitialLoading = activeSlice.isInitialLoading,
            isRefreshing = activeSlice.isRefreshing,
            isManualRefreshing = activeSlice.isManualRefreshing,
            isLoadingNextPage = activeSlice.isLoadingNextPage,
            currentPage = activeSlice.currentPage,
            hasNextPage = activeSlice.hasNextPage,
            isFromRssFallback = activeSlice.isFromRssFallback,
            preferRssFallback = settings.preferRssFallback,
            errorMessage = activeSlice.errorMessage,
            isChallengeOrAuthRequired = activeSlice.isChallengeOrAuthRequired,
            nextPageError = activeSlice.nextPageError,
            session = session,
            snackbarMessage = raw.snackbarMessage,
            awayDigest = awayDigest,
            isAwayDigestExpanded = raw.isAwayDigestExpanded && awayDigest.isVisible,
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, ExploreUiState())

    init {
        awayDigestRepository.refreshCurrentSessionDigest(forceReload = false)
        val initialKey = _streamState.value.activeKey
        val diskCached = forumRepository.getCachedFeed(initialKey.categorySlug, initialKey.sort)
        if (diskCached != null) {
            val fresh = forumRepository.isFeedFresh(
                categorySlug = initialKey.categorySlug,
                sort = initialKey.sort,
                maxAgeMillis = BOARD_CACHE_TTL_MS,
            )
            val dedupPinned = diskCached.pinnedTopics.distinctBy { it.id }
            val pinnedIds = dedupPinned.asSequence().map { it.id }.toHashSet()
            val seededSlice = BoardPageSlice(
                rawPinnedTopics = dedupPinned,
                rawTopics = diskCached.topics.filterNot { it.id in pinnedIds }.distinctBy { it.id },
                isInitialLoading = false,
                isRefreshing = true,
                isManualRefreshing = false,
                currentPage = diskCached.currentPage,
                hasNextPage = diskCached.hasNextPage,
                isFromRssFallback = diskCached.isFromRssFallback,
                updatedAtMs = if (fresh) System.currentTimeMillis() else 0L,
            )
            _streamState.update { it.copy(slices = it.slices + (initialKey to seededSlice)) }
            loadFeed(
                key = initialKey,
                reset = true,
                isPullRefresh = false,
                silentRevalidate = true,
            )
        } else {
            loadFeed(
                key = initialKey,
                reset = true,
                isPullRefresh = false,
                silentRevalidate = false,
            )
        }

        viewModelScope.launch {
            delay(2_500L)
            if (categoriesFlow.value.size <= Category.DEFAULT_CATEGORIES.size &&
                !sessionRepository.challengeRequired.value
            ) {
                runCatching { forumRepository.refreshCategories(force = false) }
            }
        }

        // Reload active feed & purge stale RSS/challenge slices when WebView verification or login succeeds
        viewModelScope.launch {
            var handledInitial = false
            sessionRepository.authVerifiedEpoch.collect { epoch ->
                if (!handledInitial) {
                    handledInitial = true
                    if (epoch <= 0L) return@collect
                    val hasStaleRssOrChallenge = _streamState.value.slices.values.any {
                        it.isFromRssFallback || it.isChallengeOrAuthRequired
                    }
                    if (!hasStaleRssOrChallenge) return@collect
                }
                if (epoch > 0L) {
                    clearRssSlicesAndReloadActive(forceNetwork = true)
                }
            }
        }

        // Purge cached slices when signed-in account UID changes (sign-in / sign-out / account switch)
        viewModelScope.launch {
            var previousUid: Long? = sessionRepository.sessionState.value?.uid
            sessionRepository.sessionState
                .map { it?.uid }
                .distinctUntilChanged()
                .drop(1)
                .collect { newUid ->
                    val prev = previousUid
                    previousUid = newUid
                    forumRepository.clearRssFallbackCache()
                    val activeKey = _streamState.value.activeKey
                    val curActiveSlice = _streamState.value.slices[activeKey]
                    val hydratedDuringActiveHtmlLoad = prev == null && newUid != null && (
                        activeFeedJobs[activeKey]?.isActive == true ||
                            (curActiveSlice != null &&
                                !curActiveSlice.isFromRssFallback &&
                                !curActiveSlice.isPreviewFromAllFeed &&
                                curActiveSlice.rawTopics.isNotEmpty() &&
                                (System.currentTimeMillis() - curActiveSlice.updatedAtMs) in 0L..1_500L)
                        )
                    if (hydratedDuringActiveHtmlLoad) {
                        activeFeedJobs.keys
                            .filter { it != activeKey }
                            .forEach { key -> activeFeedJobs.remove(key)?.cancel() }
                        _streamState.update { it.copy(slices = it.slices.filterKeys { key -> key == activeKey }) }
                    } else {
                        activeFeedJobs.values.forEach { it.cancel() }
                        activeFeedJobs.clear()
                        _streamState.update { it.copy(slices = emptyMap()) }
                        loadFeed(
                            key = activeKey,
                            reset = true,
                            isPullRefresh = false,
                            forceNetwork = true,
                        )
                    }
                }
        }

        // Reload active feed when user toggles blocked-topics or RSS-fallback preferences
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                val prevBlocked = lastObservedIncludeBlocked
                val prevRss = lastObservedPreferRss
                lastObservedIncludeBlocked = settings.includeBlockedTopics
                lastObservedPreferRss = settings.preferRssFallback
                if ((prevBlocked != null && prevBlocked != settings.includeBlockedTopics) ||
                    (prevRss != null && prevRss != settings.preferRssFallback)
                ) {
                    forumRepository.clearRssFallbackCache()
                    activeFeedJobs.values.forEach { it.cancel() }
                    activeFeedJobs.clear()
                    _streamState.update { it.copy(slices = emptyMap()) }
                    loadFeed(
                        key = _streamState.value.activeKey,
                        reset = true,
                        isPullRefresh = false,
                        forceNetwork = true,
                    )
                }
            }
        }
    }

    fun selectCategory(slug: String) {
        val normalized = slug.trim()
        val prevState = _streamState.value
        val nextSort = effectiveSortForCategory(normalized, prevState.selectedSort)

        _streamState.update {
            it.copy(
                selectedCategorySlug = normalized,
                selectedSort = nextSort,
            )
        }

        ensureCategoryLoaded(normalized = normalized, nextSort = nextSort)
    }

    fun selectSort(sort: FeedSort) {
        val currentSlug = _streamState.value.selectedCategorySlug
        val effectiveSort = effectiveSortForCategory(currentSlug, sort)

        _streamState.update {
            it.copy(selectedSort = effectiveSort)
        }

        ensureCategoryLoaded(normalized = currentSlug, nextSort = effectiveSort)
    }

    fun refresh(categorySlug: String = _streamState.value.selectedCategorySlug) {
        val normalized = categorySlug.trim()
        val sort = effectiveSortForCategory(normalized, _streamState.value.selectedSort)
        loadFeed(key = FeedSliceKey(normalized, sort), reset = true, isPullRefresh = true)
    }

    fun loadNextPage(categorySlug: String = _streamState.value.selectedCategorySlug) {
        val normalized = categorySlug.trim()
        val sort = effectiveSortForCategory(normalized, _streamState.value.selectedSort)
        val key = FeedSliceKey(normalized, sort)
        val slice = _streamState.value.slices[key] ?: return
        if (slice.isInitialLoading ||
            slice.isRefreshing ||
            slice.isLoadingNextPage ||
            slice.isPreviewFromAllFeed ||
            slice.isFromRssFallback ||
            !slice.hasNextPage
        ) {
            return
        }
        loadFeed(key = key, reset = false, isPullRefresh = false)
    }

    fun disablePreferRssAndRefresh() {
        viewModelScope.launch {
            forumRepository.clearRssFallbackCache()
            if (settingsRepository.settings.value.preferRssFallback) {
                settingsRepository.setPreferRssFallback(false)
            } else {
                clearRssSlicesAndReloadActive(forceNetwork = true)
            }
        }
    }

    private fun clearRssSlicesAndReloadActive(forceNetwork: Boolean) {
        forumRepository.clearRssFallbackCache()
        val activeKey = _streamState.value.activeKey
        val staleKeys = _streamState.value.slices.entries
            .filter { (_, slice) ->
                slice.isFromRssFallback || slice.isChallengeOrAuthRequired || slice.isPreviewFromAllFeed
            }
            .map { it.key }
        staleKeys.forEach { key ->
            if (key != activeKey) {
                activeFeedJobs.remove(key)?.cancel()
            }
        }
        if (staleKeys.isNotEmpty()) {
            _streamState.update { state ->
                state.copy(slices = state.slices.filterKeys { it !in staleKeys })
            }
        }
        val activeSlice = _streamState.value.slices[activeKey]
        if (activeSlice == null ||
            activeSlice.isFromRssFallback ||
            activeSlice.isChallengeOrAuthRequired ||
            activeSlice.isPreviewFromAllFeed ||
            activeSlice.errorMessage != null ||
            activeSlice.rawTopics.isEmpty()
        ) {
            loadFeed(
                key = activeKey,
                reset = true,
                isPullRefresh = false,
                forceNetwork = forceNetwork,
            )
        }
    }

    fun toggleBookmark(topic: TopicSummary) {
        viewModelScope.launch {
            val currentlyBookmarked = topic.id in bookmarkedIdsFlow.value || topic.isBookmarked
            val targetBookmarked = !currentlyBookmarked
            val isSignedIn = sessionRepository.sessionState.value != null

            if (targetBookmarked) {
                bookmarkAndHistoryRepository.saveOfflineBookmark(
                    summary = topic.copy(isBookmarked = true),
                    detailSnapshot = null,
                    syncedWithServer = false,
                )
            } else {
                bookmarkAndHistoryRepository.removeLocalBookmark(topic.id)
            }

            if (isSignedIn) {
                val remoteResult = bookmarkAndHistoryRepository.setTopicBookmarked(
                    topicId = topic.id,
                    bookmarked = targetBookmarked,
                )
                remoteResult.onFailure { err ->
                    val fallbackMsg = if (targetBookmarked) {
                        "云端收藏同步未完成，已保存在本机"
                    } else {
                        "取消云端收藏同步未完成，已从本机移除"
                    }
                    _streamState.update {
                        it.copy(snackbarMessage = err.message ?: fallbackMsg)
                    }
                }
            } else {
                _streamState.update {
                    it.copy(
                        snackbarMessage = if (targetBookmarked) "已加入我的收藏" else "已移除收藏",
                    )
                }
            }
        }
    }

    fun onWebViewAuthCompleted() {
        viewModelScope.launch {
            runCatching { sessionRepository.onWebViewAuthCompleted() }
            loadFeed(key = _streamState.value.activeKey, reset = true, isPullRefresh = true)
        }
    }

    fun consumeSnackbar() {
        _streamState.update { it.copy(snackbarMessage = null) }
    }

    fun dismissAwayDigest() {
        _streamState.update { it.copy(isAwayDigestExpanded = false) }
        awayDigestRepository.dismissCurrentSession()
    }

    fun toggleAwayDigestExpanded() {
        _streamState.update { it.copy(isAwayDigestExpanded = !it.isAwayDigestExpanded) }
    }

    fun onAwayDigestTopicClicked(topic: TopicSummary) {
        awayDigestRepository.markTopicVisited(topic.id)
        viewModelScope.launch {
            val uid = sessionRepository.sessionState.value?.uid ?: 0L
            runCatching {
                bookmarkAndHistoryRepository.recordTopicVisit(
                    ReadHistoryEntry(
                        topicId = topic.id,
                        title = topic.title,
                        categoryName = topic.categoryName,
                        authorName = topic.authorName,
                        authorAvatarUrl = topic.authorAvatarUrl,
                        lastReadPage = 1,
                        lastReadFloor = topic.lastReadFloor ?: 0,
                        visitedAtEpochMillis = System.currentTimeMillis(),
                        categorySlug = topic.categorySlug,
                        accountUid = uid,
                    ),
                )
            }
        }
    }

    private fun buildOrderedTabs(
        categories: List<Category>,
        pinnedCategorySlugs: Set<String>,
        selectedCategorySlug: String,
    ): List<Category> {
        val bySlug = categories.associateBy { it.slug }
        val defaultBySlug = Category.DEFAULT_CATEGORIES.associateBy { it.slug }
        val seen = HashSet<String>()
        return buildList {
            // 1. Always place "" ("全部") first
            val allTab = bySlug[""] ?: defaultBySlug[""] ?: Category(slug = "", name = "全部")
            if (seen.add(allTab.slug)) {
                add(allTab)
            }

            // 2. Priority board tabs first (matching editorial reference when using default pinned set)
            val prioritySlugs = if (pinnedCategorySlugs == AppSettings.DEFAULT_PINNED_CATEGORY_SLUGS) {
                DEFAULT_EXPLORE_CATEGORY_ORDER
            } else {
                pinnedCategorySlugs
            }
            prioritySlugs.forEach { rawSlug ->
                val slug = rawSlug.trim()
                if (slug.isNotBlank()) {
                    val candidate = bySlug[slug] ?: defaultBySlug[slug] ?: Category(slug = slug, name = slug)
                    if (!candidate.isAdminOnly && seen.add(slug)) {
                        add(candidate)
                    }
                }
            }

            // 3. Append all remaining non-admin categories so every board stays reachable via strip & pager
            categories.forEach { category ->
                if (!category.isAdminOnly && category.slug.isNotBlank() && seen.add(category.slug)) {
                    add(category)
                }
            }

            // 4. Ensure an externally selected slug is never dropped
            val normalizedSelected = selectedCategorySlug.trim()
            if (normalizedSelected.isNotBlank() && seen.add(normalizedSelected)) {
                add(
                    bySlug[normalizedSelected]
                        ?: defaultBySlug[normalizedSelected]
                        ?: Category(slug = normalizedSelected, name = normalizedSelected),
                )
            }
        }
    }

    private fun ensureCategoryLoaded(normalized: String, nextSort: FeedSort) {
        val key = FeedSliceKey(normalized, nextSort)
        val existing = _streamState.value.slices[key]
        val now = System.currentTimeMillis()
        val rssExpected = (settingsRepository.settings.value.preferRssFallback &&
            sessionRepository.sessionState.value == null &&
            normalized != "inside") ||
            sessionRepository.challengeRequired.value
        val isMemoryFresh = existing != null &&
            !existing.isPreviewFromAllFeed &&
            existing.rawTopics.isNotEmpty() &&
            (!existing.isFromRssFallback || rssExpected) &&
            forumRepository.isFeedFresh(normalized, nextSort, BOARD_CACHE_TTL_MS) &&
            (now - existing.updatedAtMs) in 0L until BOARD_CACHE_TTL_MS

        if (isMemoryFresh) {
            scheduleNeighborPrefetch(activeSlug = normalized, sort = nextSort)
            return
        }

        val repoCached = forumRepository.getCachedFeed(normalized, nextSort)
        if (repoCached != null && (!repoCached.isFromRssFallback || rssExpected)) {
            val isRepoFresh = forumRepository.isFeedFresh(normalized, nextSort, BOARD_CACHE_TTL_MS)
            val dedupPinned = repoCached.pinnedTopics.distinctBy { it.id }
            val pinnedIds = dedupPinned.asSequence().map { it.id }.toHashSet()
            val cachedSlice = BoardPageSlice(
                rawPinnedTopics = dedupPinned,
                rawTopics = repoCached.topics.filterNot { it.id in pinnedIds }.distinctBy { it.id },
                isInitialLoading = false,
                isRefreshing = !isRepoFresh,
                isManualRefreshing = false,
                currentPage = repoCached.currentPage,
                hasNextPage = repoCached.hasNextPage,
                isFromRssFallback = repoCached.isFromRssFallback,
                updatedAtMs = if (isRepoFresh) now else 0L,
            )
            _streamState.update { state ->
                state.copy(slices = state.slices + (key to cachedSlice))
            }
            if (isRepoFresh) {
                scheduleNeighborPrefetch(activeSlug = normalized, sort = nextSort)
            } else if (activeFeedJobs[key]?.isActive != true) {
                loadFeed(key = key, reset = true, isPullRefresh = false, silentRevalidate = true)
            }
            return
        }

        if (normalized.isNotBlank() && nextSort != FeedSort.CURATED) {
            val allMemorySlice = _streamState.value.slices[FeedSliceKey("", nextSort)]
            val allRepoCached = if (allMemorySlice == null) forumRepository.getCachedFeed("", nextSort) else null
            val sourceIsRss = allMemorySlice?.isFromRssFallback ?: allRepoCached?.isFromRssFallback ?: false
            val allFeedTopics = allMemorySlice?.rawTopics ?: allRepoCached?.topics
            val previewMatches = if (!sourceIsRss) {
                allFeedTopics
                    ?.filter { it.categorySlug.equals(normalized, ignoreCase = true) }
                    .orEmpty()
            } else {
                emptyList()
            }
            if (previewMatches.isNotEmpty()) {
                val previewSlice = BoardPageSlice(
                    rawTopics = previewMatches,
                    isInitialLoading = false,
                    isRefreshing = true,
                    isManualRefreshing = false,
                    currentPage = 1,
                    hasNextPage = false,
                    isPreviewFromAllFeed = true,
                    updatedAtMs = 0L,
                )
                _streamState.update { state ->
                    state.copy(slices = state.slices + (key to previewSlice))
                }
                if (activeFeedJobs[key]?.isActive != true) {
                    loadFeed(key = key, reset = true, isPullRefresh = false, silentRevalidate = true)
                }
                return
            }
        }

        if (activeFeedJobs[key]?.isActive != true) {
            loadFeed(key = key, reset = true, isPullRefresh = false, silentRevalidate = false)
        }
    }

    private fun scheduleNeighborPrefetch(activeSlug: String, sort: FeedSort) {
        neighborPrefetchJob?.cancel()
        neighborPrefetchJob = viewModelScope.launch {
            delay(NEIGHBOR_PREFETCH_SETTLE_MS)
            if (sessionRepository.challengeRequired.value) return@launch

            val orderedTabs = buildOrderedTabs(
                categories = categoriesFlow.value,
                pinnedCategorySlugs = settingsRepository.settings.value.pinnedCategorySlugs,
                selectedCategorySlug = _streamState.value.selectedCategorySlug,
            )
            val activeIndex = orderedTabs.indexOfFirst { it.slug == activeSlug }
            val nextTab = if (activeIndex >= 0) orderedTabs.getOrNull(activeIndex + 1) else null
            if (nextTab == null) return@launch
            if (nextTab.slug == "inside" && sessionRepository.sessionState.value == null) return@launch

            val nextSort = effectiveSortForCategory(nextTab.slug, sort)
            val nextKey = FeedSliceKey(nextTab.slug, nextSort)
            if (forumRepository.isFeedFresh(nextTab.slug, nextSort, BOARD_CACHE_TTL_MS) ||
                activeFeedJobs[nextKey]?.isActive == true
            ) {
                return@launch
            }
            val existingNext = _streamState.value.slices[nextKey]
            if (existingNext != null &&
                existingNext.rawTopics.isNotEmpty() &&
                (System.currentTimeMillis() - existingNext.updatedAtMs) in 0L until BOARD_CACHE_TTL_MS
            ) {
                return@launch
            }

            prefetchMutex.withLock {
                val sinceLast = System.currentTimeMillis() - lastPrefetchCompletedAtMs
                if (sinceLast in 0L until MIN_PREFETCH_GAP_MS) {
                    delay(MIN_PREFETCH_GAP_MS - sinceLast)
                }
                if (sessionRepository.challengeRequired.value ||
                    activeFeedJobs[nextKey]?.isActive == true
                ) {
                    return@withLock
                }

                val includeBlocked = settingsRepository.settings.value.includeBlockedTopics
                val result = if (nextSort == FeedSort.CURATED) {
                    forumRepository.getCuratedFeed(page = 1, forceRefresh = false)
                } else {
                    forumRepository.getFeed(
                        categorySlug = nextTab.slug,
                        sort = nextSort,
                        page = 1,
                        includeBlocked = includeBlocked,
                        forceRefresh = false,
                    )
                }
                lastPrefetchCompletedAtMs = System.currentTimeMillis()

                result.onSuccess { pageResult ->
                    val completedAt = lastPrefetchCompletedAtMs
                    _streamState.update { state ->
                        if (activeFeedJobs[nextKey]?.isActive == true) {
                            return@update state
                        }
                        val dedupPinned = pageResult.pinnedTopics.distinctBy { it.id }
                        val pinnedIds = dedupPinned.asSequence().map { it.id }.toHashSet()
                        val regular = pageResult.topics.filterNot { it.id in pinnedIds }.distinctBy { it.id }
                        val prefetchedSlice = BoardPageSlice(
                            rawPinnedTopics = dedupPinned,
                            rawTopics = regular,
                            currentPage = 1,
                            hasNextPage = pageResult.hasNextPage,
                            isFromRssFallback = pageResult.isFromRssFallback,
                            isInitialLoading = false,
                            isRefreshing = false,
                            isManualRefreshing = false,
                            isLoadingNextPage = false,
                            errorMessage = null,
                            isChallengeOrAuthRequired = false,
                            updatedAtMs = completedAt,
                        )
                        state.copy(slices = state.slices + (nextKey to prefetchedSlice))
                    }
                }
            }
        }
    }

    private fun loadFeed(
        key: FeedSliceKey,
        reset: Boolean,
        isPullRefresh: Boolean,
        silentRevalidate: Boolean = false,
        forceNetwork: Boolean = isPullRefresh,
    ) {
        if (reset) {
            activeFeedJobs[key]?.cancel()
        } else if (activeFeedJobs[key]?.isActive == true) {
            return
        }

        val existingSlice = _streamState.value.slices[key] ?: BoardPageSlice()
        val targetPage = if (reset) 1 else existingSlice.currentPage + 1
        val includeBlocked = settingsRepository.settings.value.includeBlockedTopics

        activeFeedJobs[key] = viewModelScope.launch {
            val startedAtMs = System.currentTimeMillis()
            _streamState.update { state ->
                val slice = state.slices[key] ?: BoardPageSlice()
                val updatedSlice = when {
                    isPullRefresh -> slice.copy(
                        isRefreshing = true,
                        isManualRefreshing = true,
                        errorMessage = null,
                        nextPageError = null,
                    )
                    reset -> slice.copy(
                        isInitialLoading = !silentRevalidate && slice.rawTopics.isEmpty(),
                        isRefreshing = silentRevalidate || slice.rawTopics.isNotEmpty(),
                        isManualRefreshing = false,
                        errorMessage = null,
                        nextPageError = null,
                    )
                    else -> slice.copy(
                        isLoadingNextPage = true,
                        nextPageError = null,
                    )
                }
                state.copy(slices = state.slices + (key to updatedSlice))
            }

            val result = if (key.sort == FeedSort.CURATED) {
                forumRepository.getCuratedFeed(
                    page = targetPage,
                    forceRefresh = forceNetwork,
                )
            } else {
                forumRepository.getFeed(
                    categorySlug = key.categorySlug,
                    sort = key.sort,
                    page = targetPage,
                    includeBlocked = includeBlocked,
                    forceRefresh = forceNetwork,
                )
            }

            if (isPullRefresh) {
                val elapsed = System.currentTimeMillis() - startedAtMs
                if (elapsed in 0L until MIN_PULL_REFRESH_SETTLE_MS) {
                    delay(MIN_PULL_REFRESH_SETTLE_MS - elapsed)
                }
            }

            result.fold(
                onSuccess = { pageResult ->
                    val now = System.currentTimeMillis()
                    _streamState.update { state ->
                        val curSlice = state.slices[key] ?: BoardPageSlice()
                        val mergedPinned = if (reset) {
                            pageResult.pinnedTopics.distinctBy { it.id }
                        } else {
                            (curSlice.rawPinnedTopics + pageResult.pinnedTopics).distinctBy { it.id }
                        }
                        val pinnedIds = mergedPinned.asSequence().map { it.id }.toHashSet()
                        val incomingRegular = pageResult.topics.filterNot { it.id in pinnedIds }
                        val mergedTopics = if (reset) {
                            incomingRegular.distinctBy { it.id }
                        } else {
                            (curSlice.rawTopics + incomingRegular).distinctBy { it.id }
                        }
                        val nextSlice = curSlice.copy(
                            rawPinnedTopics = mergedPinned,
                            rawTopics = mergedTopics,
                            currentPage = targetPage,
                            hasNextPage = pageResult.hasNextPage,
                            isFromRssFallback = pageResult.isFromRssFallback,
                            isPreviewFromAllFeed = false,
                            isInitialLoading = false,
                            isRefreshing = false,
                            isManualRefreshing = false,
                            isLoadingNextPage = false,
                            errorMessage = null,
                            isChallengeOrAuthRequired = false,
                            nextPageError = null,
                            updatedAtMs = now,
                        )
                        state.copy(slices = state.slices + (key to nextSlice))
                    }
                    if (reset && _streamState.value.selectedCategorySlug == key.categorySlug) {
                        scheduleNeighborPrefetch(activeSlug = key.categorySlug, sort = key.sort)
                    }
                },
                onFailure = { error ->
                    val needsWeb = error.isWebVerificationNeeded()
                    val msg = error.message?.takeIf { it.isNotBlank() } ?: "无法读取帖子列表，请检查网络后重试"
                    _streamState.update { state ->
                        val curSlice = state.slices[key] ?: BoardPageSlice()
                        val hasRealTopics = curSlice.rawTopics.isNotEmpty() && !curSlice.isPreviewFromAllFeed
                        val failedSlice = if (reset) {
                            curSlice.copy(
                                rawTopics = if (curSlice.isPreviewFromAllFeed) emptyList() else curSlice.rawTopics,
                                hasNextPage = if (curSlice.isPreviewFromAllFeed) false else curSlice.hasNextPage,
                                isPreviewFromAllFeed = false,
                                isInitialLoading = false,
                                isRefreshing = false,
                                isManualRefreshing = false,
                                isLoadingNextPage = false,
                                errorMessage = if (hasRealTopics) null else msg,
                                isChallengeOrAuthRequired = needsWeb,
                            )
                        } else {
                            curSlice.copy(
                                isLoadingNextPage = false,
                                nextPageError = msg,
                                isChallengeOrAuthRequired = needsWeb,
                            )
                        }
                        state.copy(
                            slices = state.slices + (key to failedSlice),
                            snackbarMessage = if ((isPullRefresh || silentRevalidate) && hasRealTopics) msg else state.snackbarMessage,
                        )
                    }
                },
            )
        }
    }

    companion object {
        private const val BOARD_CACHE_TTL_MS = 90_000L
        private const val NEIGHBOR_PREFETCH_SETTLE_MS = 450L
        private const val MIN_PREFETCH_GAP_MS = 800L
        private const val MIN_PULL_REFRESH_SETTLE_MS = 120L
        private val DEFAULT_EXPLORE_CATEGORY_ORDER = listOf(
            "tech",
            "review",
            "info",
            "trade",
            "daily",
            "dev",
        )

        fun factory(
            container: AppContainer,
            initialCategorySlug: String = "",
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ExploreViewModel(
                    forumRepository = container.forumRepository,
                    sessionRepository = container.sessionRepository,
                    bookmarkAndHistoryRepository = container.bookmarkAndHistoryRepository,
                    settingsRepository = container.settingsRepository,
                    contentFilterRepository = container.contentFilterRepository,
                    initialCategorySlug = initialCategorySlug,
                    awayDigestRepository = container.awayDigestRepository,
                )
            }
        }
    }
}

internal fun TopicSummary.enrichWithLocalState(
    bookmarkedIds: Set<Long>,
    readMap: Map<Long, ReadHistoryEntry>,
): TopicSummary {
    val history = readMap[id]
    return copy(
        isBookmarked = isBookmarked || (id in bookmarkedIds),
        isRead = isRead || (history != null),
        lastReadFloor = history?.lastReadFloor?.takeIf { it > 0 } ?: lastReadFloor,
    )
}

internal fun Throwable.isWebVerificationNeeded(): Boolean =
    this is TopicAccessException.CloudflareChallengeRequired ||
        this is TopicAccessException.SignInRequired ||
        this::class.simpleName == "CloudflareChallengeException" ||
        (cause?.let {
            it is TopicAccessException.CloudflareChallengeRequired ||
                it is TopicAccessException.SignInRequired ||
                it::class.simpleName == "CloudflareChallengeException"
        } == true)
