package com.nodeseek.app.ui.explore

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nodeseek.app.di.AppContainer
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
    val errorMessage: String? = null,
    val isChallengeOrAuthRequired: Boolean = false,
    val nextPageError: String? = null,
    val session: AccountSession? = null,
    val snackbarMessage: String? = null,
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
    initialCategorySlug: String = "",
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

    val uiState: StateFlow<ExploreUiState> = combine(
        _streamState,
        categoriesFlow,
        settingsRepository.settings,
        sessionRepository.sessionState,
        combine(bookmarkedIdsFlow, readStateMapFlow) { bookmarks, reads -> bookmarks to reads },
    ) { raw, categories, settings, session, (bookmarkedIds, readMap) ->
        val challengeActive = sessionRepository.challengeRequired.value
        val bySlug = categories.associateBy { it.slug }
        val orderedTabs = buildOrderedTabs(
            categories = categories,
            pinnedCategorySlugs = settings.pinnedCategorySlugs,
            selectedCategorySlug = raw.selectedCategorySlug,
        )

        val enrichedSlices = buildMap<String, ExploreBoardSliceUiState>(orderedTabs.size + 1) {
            orderedTabs.forEach { tab ->
                val tabSort = effectiveSortForCategory(tab.slug, raw.selectedSort)
                val slice = raw.slices[FeedSliceKey(tab.slug, tabSort)] ?: BoardPageSlice()
                put(
                    tab.slug,
                    ExploreBoardSliceUiState(
                        categorySlug = tab.slug,
                        sort = tabSort,
                        pinnedTopics = slice.rawPinnedTopics.map { it.enrichWithLocalState(bookmarkedIds, readMap) },
                        topics = slice.rawTopics.map { it.enrichWithLocalState(bookmarkedIds, readMap) },
                        isInitialLoading = slice.isInitialLoading,
                        isRefreshing = slice.isRefreshing,
                        isManualRefreshing = slice.isManualRefreshing,
                        isLoadingNextPage = slice.isLoadingNextPage,
                        currentPage = slice.currentPage,
                        hasNextPage = slice.hasNextPage,
                        isFromRssFallback = slice.isFromRssFallback,
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
            errorMessage = activeSlice.errorMessage,
            isChallengeOrAuthRequired = activeSlice.isChallengeOrAuthRequired,
            nextPageError = activeSlice.nextPageError,
            session = session,
            snackbarMessage = raw.snackbarMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, ExploreUiState())

    init {
        val initialKey = _streamState.value.activeKey
        val diskCached = forumRepository.getCachedFeed(initialKey.categorySlug, initialKey.sort)
        if (diskCached != null) {
            val fresh = forumRepository.isFeedFresh(
                categorySlug = initialKey.categorySlug,
                sort = initialKey.sort,
                maxAgeMillis = BOARD_CACHE_TTL_MS,
            )
            val seededSlice = BoardPageSlice(
                rawPinnedTopics = diskCached.pinnedTopics,
                rawTopics = diskCached.topics,
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

        // Reload active feed when WebView security verification or login succeeds
        viewModelScope.launch {
            sessionRepository.authVerifiedEpoch.collect { epoch ->
                if (epoch > 0L) {
                    val activeKey = _streamState.value.activeKey
                    val slice = _streamState.value.slices[activeKey]
                    if (slice == null ||
                        slice.isChallengeOrAuthRequired ||
                        slice.isFromRssFallback ||
                        slice.errorMessage != null ||
                        slice.rawTopics.isEmpty()
                    ) {
                        loadFeed(key = activeKey, reset = true, isPullRefresh = false)
                    }
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
                    _streamState.update { it.copy(slices = emptyMap()) }
                    loadFeed(key = _streamState.value.activeKey, reset = true, isPullRefresh = false)
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
            !slice.hasNextPage
        ) {
            return
        }
        loadFeed(key = key, reset = false, isPullRefresh = false)
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
                    _streamState.update {
                        it.copy(snackbarMessage = err.message ?: "云端收藏同步未完成，已保存在本机")
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

    private fun buildOrderedTabs(
        categories: List<Category>,
        pinnedCategorySlugs: Set<String>,
        selectedCategorySlug: String,
    ): List<Category> {
        val bySlug = categories.associateBy { it.slug }
        return buildList {
            add(bySlug[""] ?: Category(slug = "", name = "全部"))
            pinnedCategorySlugs.forEach { slug ->
                if (slug.isNotBlank()) {
                    add(bySlug[slug] ?: Category(slug = slug, name = slug))
                }
            }
            if (selectedCategorySlug.isNotBlank() && none { it.slug == selectedCategorySlug }) {
                add(bySlug[selectedCategorySlug] ?: Category(slug = selectedCategorySlug, name = selectedCategorySlug))
            }
        }
    }

    private fun ensureCategoryLoaded(normalized: String, nextSort: FeedSort) {
        val key = FeedSliceKey(normalized, nextSort)
        val existing = _streamState.value.slices[key]
        val now = System.currentTimeMillis()
        val isMemoryFresh = existing != null &&
            existing.rawTopics.isNotEmpty() &&
            forumRepository.isFeedFresh(normalized, nextSort, BOARD_CACHE_TTL_MS) &&
            (now - existing.updatedAtMs) in 0L until BOARD_CACHE_TTL_MS

        if (isMemoryFresh) {
            scheduleNeighborPrefetch(activeSlug = normalized, sort = nextSort)
            return
        }

        val repoCached = forumRepository.getCachedFeed(normalized, nextSort)
        if (repoCached != null) {
            val isRepoFresh = forumRepository.isFeedFresh(normalized, nextSort, BOARD_CACHE_TTL_MS)
            val cachedSlice = BoardPageSlice(
                rawPinnedTopics = repoCached.pinnedTopics,
                rawTopics = repoCached.topics,
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
            val allFeedTopics = _streamState.value.slices[FeedSliceKey("", nextSort)]?.rawTopics
                ?: forumRepository.getCachedFeed("", nextSort)?.topics
            val previewMatches = allFeedTopics
                ?.filter { it.categorySlug.equals(normalized, ignoreCase = true) }
                .orEmpty()
            if (previewMatches.isNotEmpty()) {
                val previewSlice = BoardPageSlice(
                    rawTopics = previewMatches,
                    isInitialLoading = false,
                    isRefreshing = true,
                    isManualRefreshing = false,
                    currentPage = 1,
                    hasNextPage = true,
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
                        val pinnedIds = pageResult.pinnedTopics.asSequence().map { it.id }.toHashSet()
                        val regular = pageResult.topics.filterNot { it.id in pinnedIds }
                        val prefetchedSlice = BoardPageSlice(
                            rawPinnedTopics = pageResult.pinnedTopics,
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
                    forceRefresh = isPullRefresh,
                )
            } else {
                forumRepository.getFeed(
                    categorySlug = key.categorySlug,
                    sort = key.sort,
                    page = targetPage,
                    includeBlocked = includeBlocked,
                    forceRefresh = isPullRefresh,
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
                            pageResult.pinnedTopics
                        } else {
                            (curSlice.rawPinnedTopics + pageResult.pinnedTopics).distinctBy { it.id }
                        }
                        val pinnedIds = mergedPinned.asSequence().map { it.id }.toHashSet()
                        val incomingRegular = pageResult.topics.filterNot { it.id in pinnedIds }
                        val mergedTopics = if (reset) {
                            incomingRegular
                        } else {
                            (curSlice.rawTopics + incomingRegular).distinctBy { it.id }
                        }
                        val nextSlice = curSlice.copy(
                            rawPinnedTopics = mergedPinned,
                            rawTopics = mergedTopics,
                            currentPage = targetPage,
                            hasNextPage = pageResult.hasNextPage,
                            isFromRssFallback = pageResult.isFromRssFallback,
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
                        val hasExistingTopics = curSlice.rawTopics.isNotEmpty()
                        val failedSlice = if (reset) {
                            curSlice.copy(
                                isInitialLoading = false,
                                isRefreshing = false,
                                isManualRefreshing = false,
                                isLoadingNextPage = false,
                                errorMessage = if (hasExistingTopics) null else msg,
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
                            snackbarMessage = if ((isPullRefresh || silentRevalidate) && hasExistingTopics) msg else state.snackbarMessage,
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
                    initialCategorySlug = initialCategorySlug,
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
