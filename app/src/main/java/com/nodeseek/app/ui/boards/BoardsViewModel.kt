package com.nodeseek.app.ui.boards

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.filter.ContentFilterConfig
import com.nodeseek.app.domain.filter.ContentFilterEngine
import com.nodeseek.app.domain.filter.ContentFilterRepository
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.repository.BookmarkAndHistoryRepository
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SettingsRepository
import com.nodeseek.app.domain.repository.UserRepository
import com.nodeseek.app.ui.explore.enrichWithLocalState
import com.nodeseek.app.ui.explore.isWebVerificationNeeded
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import java.net.URLEncoder

enum class SearchScope(val label: String) {
    TOPICS("找帖子"),
    USERS("找用户"),
}

@Immutable
data class BoardsUiState(
    val categories: List<Category> = Category.DEFAULT_CATEGORIES,
    val pinnedCategorySlugs: Set<String> = AppSettings.DEFAULT_PINNED_CATEGORY_SLUGS,
    val recentSearchQueries: List<String> = emptyList(),
    val isSignedIn: Boolean = false,
    // Search & filter state
    val queryInput: String = "",
    val submittedQuery: String = "",
    val isSearchModeActive: Boolean = false,
    val searchScope: SearchScope = SearchScope.TOPICS,
    val searchCategoryFilterSlug: String = "",
    val searchSort: FeedSort = FeedSort.LATEST_REPLY,
    val searchTopics: List<TopicSummary> = emptyList(),
    val searchUsers: List<FloorAuthor> = emptyList(),
    val isSearching: Boolean = false,
    val isSearchingNextPage: Boolean = false,
    val searchPage: Int = 1,
    val searchHasNextPage: Boolean = false,
    val searchErrorMessage: String? = null,
    val isSearchChallengeRequired: Boolean = false,
    // Inline board sub-view state (when tapping a Bento tile inside Boards tab)
    val activeInlineBoardSlug: String? = null,
    val inlineBoardSort: FeedSort = FeedSort.LATEST_REPLY,
    val inlineBoardPinnedTopics: List<TopicSummary> = emptyList(),
    val inlineBoardTopics: List<TopicSummary> = emptyList(),
    val isInlineBoardLoading: Boolean = false,
    val isInlineBoardRefreshing: Boolean = false,
    val isInlineBoardLoadingMore: Boolean = false,
    val inlineBoardPage: Int = 1,
    val inlineBoardHasNextPage: Boolean = false,
    val inlineBoardError: String? = null,
    val inlineBoardChallengeRequired: Boolean = false,
    val snackbarMessage: String? = null,
) {
    val filteredBentoCategories: List<Category>
        get() {
            val visible = categories.filterNot { it.isAdminOnly }
            val kw = queryInput.trim().lowercase()
            if (kw.isEmpty()) return visible
            return visible.filter { cat ->
                cat.name.lowercase().contains(kw) ||
                    cat.slug.lowercase().contains(kw) ||
                    cat.description.lowercase().contains(kw)
            }
        }

    val googleSiteSearchUrl: String
        get() {
            val target = queryInput.trim().ifEmpty { submittedQuery.trim() }
            val q = if (target.isEmpty()) "site:www.nodeseek.com" else "site:www.nodeseek.com $target"
            val encoded = runCatching { URLEncoder.encode(q, "UTF-8") }.getOrDefault(q)
            return "https://www.google.com/search?q=$encoded"
        }
}

private data class InternalBoardsState(
    val queryInput: String = "",
    val submittedQuery: String = "",
    val isSearchModeActive: Boolean = false,
    val searchScope: SearchScope = SearchScope.TOPICS,
    val searchCategoryFilterSlug: String = "",
    val searchSort: FeedSort = FeedSort.LATEST_REPLY,
    val rawSearchTopics: List<TopicSummary> = emptyList(),
    val searchUsers: List<FloorAuthor> = emptyList(),
    val isSearching: Boolean = false,
    val isSearchingNextPage: Boolean = false,
    val searchPage: Int = 1,
    val searchHasNextPage: Boolean = false,
    val searchErrorMessage: String? = null,
    val isSearchChallengeRequired: Boolean = false,
    val activeInlineBoardSlug: String? = null,
    val inlineBoardSort: FeedSort = FeedSort.LATEST_REPLY,
    val rawInlineBoardPinnedTopics: List<TopicSummary> = emptyList(),
    val rawInlineBoardTopics: List<TopicSummary> = emptyList(),
    val isInlineBoardLoading: Boolean = false,
    val isInlineBoardRefreshing: Boolean = false,
    val isInlineBoardLoadingMore: Boolean = false,
    val inlineBoardPage: Int = 1,
    val inlineBoardHasNextPage: Boolean = false,
    val inlineBoardIsRssFallback: Boolean = false,
    val inlineBoardError: String? = null,
    val inlineBoardChallengeRequired: Boolean = false,
    val snackbarMessage: String? = null,
)

private data class BoardsLocalOverlay(
    val session: AccountSession?,
    val bookmarkedIds: Set<Long>,
    val readMap: Map<Long, ReadHistoryEntry>,
    val filterConfig: ContentFilterConfig,
)

@OptIn(ExperimentalCoroutinesApi::class)
class BoardsViewModel(
    private val forumRepository: ForumRepository,
    private val userRepository: UserRepository,
    private val sessionRepository: SessionRepository,
    private val bookmarkAndHistoryRepository: BookmarkAndHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val contentFilterRepository: ContentFilterRepository,
    initialCategorySlug: String = "",
    autoFocusSearch: Boolean = false,
) : ViewModel() {

    private val _state = MutableStateFlow(
        InternalBoardsState(
            isSearchModeActive = autoFocusSearch,
            activeInlineBoardSlug = initialCategorySlug.takeIf { it.isNotBlank() },
        )
    )

    private var searchJob: Job? = null
    private var inlineBoardJob: Job? = null

    private val categoriesFlow = forumRepository.observeCategories()
        .map { list -> list.ifEmpty { Category.DEFAULT_CATEGORIES } }
        .stateIn(viewModelScope, SharingStarted.Lazily, Category.DEFAULT_CATEGORIES)

    private val bookmarkedIdsFlow = bookmarkAndHistoryRepository.observeBookmarkedIds()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    private val readStateMapFlow = _state
        .map { s ->
            buildSet {
                s.rawSearchTopics.forEach { add(it.id) }
                s.rawInlineBoardPinnedTopics.forEach { add(it.id) }
                s.rawInlineBoardTopics.forEach { add(it.id) }
            }
        }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyMap())
            else bookmarkAndHistoryRepository.observeReadStateMap(ids)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    val uiState: StateFlow<BoardsUiState> = combine(
        _state,
        categoriesFlow,
        settingsRepository.settings,
        settingsRepository.recentSearchQueries,
        combine(
            sessionRepository.sessionState,
            bookmarkedIdsFlow,
            readStateMapFlow,
            contentFilterRepository.config,
        ) { session, bookmarks, reads, filterConfig ->
            BoardsLocalOverlay(
                session = session,
                bookmarkedIds = bookmarks,
                readMap = reads,
                filterConfig = filterConfig,
            )
        },
    ) { s, categories, settings, recentQueries, overlay ->
        val includeBlocked = settings.includeBlockedTopics
        val filterConfig = overlay.filterConfig
        val activeInlineSlug = s.activeInlineBoardSlug.orEmpty()
        BoardsUiState(
            categories = categories,
            pinnedCategorySlugs = settings.pinnedCategorySlugs,
            recentSearchQueries = recentQueries,
            isSignedIn = overlay.session != null,
            queryInput = s.queryInput,
            submittedQuery = s.submittedQuery,
            isSearchModeActive = s.isSearchModeActive,
            searchScope = s.searchScope,
            searchCategoryFilterSlug = s.searchCategoryFilterSlug,
            searchSort = s.searchSort,
            searchTopics = ContentFilterEngine.filterTopics(
                topics = s.rawSearchTopics,
                config = filterConfig,
                includeBlockedTopics = includeBlocked,
                activeBoardSlug = s.searchCategoryFilterSlug,
            ).map { it.enrichWithLocalState(overlay.bookmarkedIds, overlay.readMap) },
            searchUsers = s.searchUsers,
            isSearching = s.isSearching,
            isSearchingNextPage = s.isSearchingNextPage,
            searchPage = s.searchPage,
            searchHasNextPage = s.searchHasNextPage,
            searchErrorMessage = s.searchErrorMessage,
            isSearchChallengeRequired = s.isSearchChallengeRequired,
            activeInlineBoardSlug = s.activeInlineBoardSlug,
            inlineBoardSort = s.inlineBoardSort,
            inlineBoardPinnedTopics = ContentFilterEngine.filterTopics(
                topics = s.rawInlineBoardPinnedTopics,
                config = filterConfig,
                includeBlockedTopics = includeBlocked,
                activeBoardSlug = activeInlineSlug,
            ).map { it.enrichWithLocalState(overlay.bookmarkedIds, overlay.readMap) },
            inlineBoardTopics = ContentFilterEngine.filterTopics(
                topics = s.rawInlineBoardTopics,
                config = filterConfig,
                includeBlockedTopics = includeBlocked,
                activeBoardSlug = activeInlineSlug,
            ).map { it.enrichWithLocalState(overlay.bookmarkedIds, overlay.readMap) },
            isInlineBoardLoading = s.isInlineBoardLoading,
            isInlineBoardRefreshing = s.isInlineBoardRefreshing,
            isInlineBoardLoadingMore = s.isInlineBoardLoadingMore,
            inlineBoardPage = s.inlineBoardPage,
            inlineBoardHasNextPage = s.inlineBoardHasNextPage,
            inlineBoardError = s.inlineBoardError,
            inlineBoardChallengeRequired = s.inlineBoardChallengeRequired,
            snackbarMessage = s.snackbarMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, BoardsUiState())

    init {
        viewModelScope.launch {
            runCatching { forumRepository.refreshCategories(force = false) }
        }
        if (initialCategorySlug.isNotBlank()) {
            loadInlineBoard(reset = true, isRefresh = false)
        }
        viewModelScope.launch {
            sessionRepository.authVerifiedEpoch.collect { epoch ->
                if (epoch > 0L) {
                    val cur = _state.value
                    if (cur.isSearchChallengeRequired && cur.submittedQuery.isNotBlank()) {
                        executeSearch(reset = true)
                    }
                    if (!cur.activeInlineBoardSlug.isNullOrBlank() &&
                        (cur.inlineBoardChallengeRequired || cur.inlineBoardIsRssFallback || cur.inlineBoardError != null)
                    ) {
                        loadInlineBoard(reset = true, isRefresh = false)
                    }
                }
            }
        }
    }

    // ── Search & Pin Controls ────────────────────────────────────────────────────────────────

    fun setSearchModeActive(active: Boolean) {
        _state.update { current ->
            if (!active) {
                current.copy(
                    isSearchModeActive = false,
                    queryInput = "",
                    submittedQuery = "",
                    rawSearchTopics = emptyList(),
                    searchUsers = emptyList(),
                    searchErrorMessage = null,
                    isSearchChallengeRequired = false,
                )
            } else {
                current.copy(isSearchModeActive = true)
            }
        }
    }

    fun updateQueryInput(newQuery: String) {
        _state.update { current ->
            current.copy(
                queryInput = newQuery,
                isSearchModeActive = current.isSearchModeActive || newQuery.isNotBlank(),
            )
        }
    }

    fun selectSearchScope(scope: SearchScope) {
        if (_state.value.searchScope == scope) return
        _state.update { it.copy(searchScope = scope, searchErrorMessage = null) }
        if (_state.value.queryInput.isNotBlank()) {
            submitSearch()
        }
    }

    fun selectSearchCategoryFilter(slug: String) {
        if (_state.value.searchCategoryFilterSlug == slug) return
        _state.update { it.copy(searchCategoryFilterSlug = slug) }
        if (_state.value.queryInput.isNotBlank() && _state.value.searchScope == SearchScope.TOPICS) {
            submitSearch()
        }
    }

    fun selectSearchSort(sort: FeedSort) {
        if (_state.value.searchSort == sort) return
        _state.update { it.copy(searchSort = sort) }
        if (_state.value.queryInput.isNotBlank() && _state.value.searchScope == SearchScope.TOPICS) {
            submitSearch()
        }
    }

    fun submitSearch(overrideQuery: String? = null) {
        val q = (overrideQuery ?: _state.value.queryInput).trim()
        if (q.isEmpty()) return

        _state.update {
            it.copy(
                queryInput = q,
                submittedQuery = q,
                isSearchModeActive = true,
                activeInlineBoardSlug = null,
            )
        }
        viewModelScope.launch {
            settingsRepository.addRecentSearchQuery(q)
        }
        executeSearch(reset = true)
    }

    fun loadNextSearchPage() {
        val s = _state.value
        if (s.searchScope != SearchScope.TOPICS ||
            s.isSearching ||
            s.isSearchingNextPage ||
            !s.searchHasNextPage ||
            s.submittedQuery.isBlank()
        ) {
            return
        }
        executeSearch(reset = false)
    }

    fun removeRecentQuery(query: String) {
        viewModelScope.launch { settingsRepository.removeRecentSearchQuery(query) }
    }

    fun clearRecentQueries() {
        viewModelScope.launch { settingsRepository.clearRecentSearchQueries() }
    }

    fun togglePinCategory(slug: String) {
        if (slug.isBlank()) return
        viewModelScope.launch {
            val wasPinned = slug in settingsRepository.settings.value.pinnedCategorySlugs
            settingsRepository.togglePinnedCategory(slug)
            _state.update {
                it.copy(
                    snackbarMessage = if (wasPinned) "已从首页版块栏移除" else "已固定到首页版块栏",
                )
            }
        }
    }

    // ── Inline Board Feed Sub-View Controls ──────────────────────────────────────────────────

    fun openInlineBoard(slug: String) {
        val sort = _state.value.inlineBoardSort
        val cached = forumRepository.getCachedFeed(slug, sort)
        val fresh = cached != null && forumRepository.isFeedFresh(slug, sort, 90_000L)
        _state.update {
            it.copy(
                activeInlineBoardSlug = slug,
                isSearchModeActive = false,
                rawInlineBoardPinnedTopics = cached?.pinnedTopics.orEmpty(),
                rawInlineBoardTopics = cached?.topics.orEmpty(),
                isInlineBoardLoading = cached == null,
                isInlineBoardRefreshing = false,
                inlineBoardPage = 1,
                inlineBoardHasNextPage = cached?.hasNextPage ?: false,
                inlineBoardIsRssFallback = cached?.isFromRssFallback ?: false,
                inlineBoardError = null,
            )
        }
        if (!fresh) {
            loadInlineBoard(reset = true, isRefresh = false)
        }
    }

    fun closeInlineBoard() {
        inlineBoardJob?.cancel()
        _state.update {
            it.copy(
                activeInlineBoardSlug = null,
                rawInlineBoardTopics = emptyList(),
                rawInlineBoardPinnedTopics = emptyList(),
                inlineBoardError = null,
            )
        }
    }

    fun selectInlineBoardSort(sort: FeedSort) {
        val slug = _state.value.activeInlineBoardSlug ?: return
        if (_state.value.inlineBoardSort == sort) return
        val cached = forumRepository.getCachedFeed(slug, sort)
        val fresh = cached != null && forumRepository.isFeedFresh(slug, sort, 90_000L)
        _state.update {
            it.copy(
                inlineBoardSort = sort,
                rawInlineBoardPinnedTopics = cached?.pinnedTopics.orEmpty(),
                rawInlineBoardTopics = cached?.topics.orEmpty(),
                isInlineBoardLoading = cached == null,
                isInlineBoardRefreshing = false,
                inlineBoardPage = 1,
                inlineBoardHasNextPage = cached?.hasNextPage ?: false,
                inlineBoardIsRssFallback = cached?.isFromRssFallback ?: false,
                inlineBoardError = null,
            )
        }
        if (!fresh) {
            loadInlineBoard(reset = true, isRefresh = false)
        }
    }

    fun refreshInlineBoard() {
        loadInlineBoard(reset = true, isRefresh = true)
    }

    fun loadNextInlineBoardPage() {
        val s = _state.value
        if (s.activeInlineBoardSlug == null ||
            s.isInlineBoardLoading ||
            s.isInlineBoardRefreshing ||
            s.isInlineBoardLoadingMore ||
            !s.inlineBoardHasNextPage
        ) {
            return
        }
        loadInlineBoard(reset = false, isRefresh = false)
    }

    fun toggleBookmark(topic: TopicSummary) {
        viewModelScope.launch {
            val currentlyBookmarked = topic.id in bookmarkedIdsFlow.value || topic.isBookmarked
            val targetBookmarked = !currentlyBookmarked
            if (targetBookmarked) {
                bookmarkAndHistoryRepository.saveOfflineBookmark(
                    summary = topic.copy(isBookmarked = true),
                    detailSnapshot = null,
                    syncedWithServer = false,
                )
            } else {
                bookmarkAndHistoryRepository.removeLocalBookmark(topic.id)
            }
            if (sessionRepository.sessionState.value != null) {
                bookmarkAndHistoryRepository.setTopicBookmarked(topic.id, targetBookmarked)
            }
        }
    }

    fun onWebViewAuthCompleted() {
        viewModelScope.launch {
            runCatching { sessionRepository.onWebViewAuthCompleted() }
            if (_state.value.activeInlineBoardSlug != null) {
                loadInlineBoard(reset = true, isRefresh = true)
            } else if (_state.value.submittedQuery.isNotBlank()) {
                executeSearch(reset = true)
            }
        }
    }

    fun consumeSnackbar() {
        _state.update { it.copy(snackbarMessage = null) }
    }

    private fun executeSearch(reset: Boolean) {
        if (reset) searchJob?.cancel()
        val snap = _state.value
        val query = snap.submittedQuery
        val scope = snap.searchScope
        val targetPage = if (reset) 1 else snap.searchPage + 1

        searchJob = viewModelScope.launch {
            _state.update {
                if (reset) {
                    it.copy(
                        isSearching = true,
                        searchErrorMessage = null,
                        isSearchChallengeRequired = false,
                    )
                } else {
                    it.copy(isSearchingNextPage = true)
                }
            }

            if (scope == SearchScope.USERS) {
                userRepository.searchUsers(query).fold(
                    onSuccess = { users ->
                        _state.update {
                            it.copy(
                                searchUsers = users,
                                isSearching = false,
                                isSearchingNextPage = false,
                                searchHasNextPage = false,
                                searchErrorMessage = null,
                            )
                        }
                    },
                    onFailure = { err ->
                        _state.update {
                            it.copy(
                                isSearching = false,
                                isSearchingNextPage = false,
                                searchErrorMessage = err.message ?: "搜索用户失败，请重试",
                                isSearchChallengeRequired = err.isWebVerificationNeeded(),
                            )
                        }
                    },
                )
            } else {
                forumRepository.searchTopics(
                    query = query,
                    categorySlug = snap.searchCategoryFilterSlug,
                    sort = snap.searchSort,
                    page = targetPage,
                ).fold(
                    onSuccess = { result ->
                        _state.update { cur ->
                            val merged = if (reset) {
                                result.topics
                            } else {
                                (cur.rawSearchTopics + result.topics).distinctBy { it.id }
                            }
                            cur.copy(
                                rawSearchTopics = merged,
                                searchPage = targetPage,
                                searchHasNextPage = result.hasNextPage,
                                isSearching = false,
                                isSearchingNextPage = false,
                                searchErrorMessage = null,
                                isSearchChallengeRequired = false,
                            )
                        }
                    },
                    onFailure = { err ->
                        _state.update { cur ->
                            cur.copy(
                                isSearching = false,
                                isSearchingNextPage = false,
                                searchErrorMessage = err.message ?: "站内搜索受限，可使用下方 Google 站内搜索",
                                isSearchChallengeRequired = err.isWebVerificationNeeded(),
                            )
                        }
                    },
                )
            }
        }
    }

    private fun loadInlineBoard(reset: Boolean, isRefresh: Boolean) {
        val snap = _state.value
        val slug = snap.activeInlineBoardSlug ?: return
        if (reset) inlineBoardJob?.cancel()
        val targetPage = if (reset) 1 else snap.inlineBoardPage + 1
        val includeBlocked = settingsRepository.settings.value.includeBlockedTopics

        inlineBoardJob = viewModelScope.launch {
            _state.update {
                when {
                    isRefresh -> it.copy(isInlineBoardRefreshing = true, inlineBoardError = null)
                    reset -> it.copy(
                        isInlineBoardLoading = it.rawInlineBoardTopics.isEmpty(),
                        inlineBoardError = null,
                    )
                    else -> it.copy(isInlineBoardLoadingMore = true)
                }
            }

            forumRepository.getFeed(
                categorySlug = slug,
                sort = snap.inlineBoardSort,
                page = targetPage,
                includeBlocked = includeBlocked,
                forceRefresh = isRefresh,
            ).fold(
                onSuccess = { res ->
                    _state.update { cur ->
                        val mergedPinned = if (reset) {
                            res.pinnedTopics
                        } else {
                            (cur.rawInlineBoardPinnedTopics + res.pinnedTopics).distinctBy { it.id }
                        }
                        val pinnedIds = mergedPinned.asSequence().map { it.id }.toHashSet()
                        val regular = res.topics.filterNot { it.id in pinnedIds }
                        val mergedTopics = if (reset) {
                            regular
                        } else {
                            (cur.rawInlineBoardTopics + regular).distinctBy { it.id }
                        }
                        cur.copy(
                            rawInlineBoardPinnedTopics = mergedPinned,
                            rawInlineBoardTopics = mergedTopics,
                            inlineBoardPage = targetPage,
                            inlineBoardHasNextPage = res.hasNextPage,
                            inlineBoardIsRssFallback = res.isFromRssFallback,
                            isInlineBoardLoading = false,
                            isInlineBoardRefreshing = false,
                            isInlineBoardLoadingMore = false,
                            inlineBoardError = null,
                            inlineBoardChallengeRequired = false,
                        )
                    }
                },
                onFailure = { err ->
                    _state.update { cur ->
                        cur.copy(
                            isInlineBoardLoading = false,
                            isInlineBoardRefreshing = false,
                            isInlineBoardLoadingMore = false,
                            inlineBoardError = err.message ?: "无法读取该版块内容",
                            inlineBoardChallengeRequired = err.isWebVerificationNeeded(),
                        )
                    }
                },
            )
        }
    }

    companion object {
        fun factory(
            container: AppContainer,
            initialCategorySlug: String = "",
            autoFocusSearch: Boolean = false,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BoardsViewModel(
                    forumRepository = container.forumRepository,
                    userRepository = container.userRepository,
                    sessionRepository = container.sessionRepository,
                    bookmarkAndHistoryRepository = container.bookmarkAndHistoryRepository,
                    settingsRepository = container.settingsRepository,
                    contentFilterRepository = container.contentFilterRepository,
                    initialCategorySlug = initialCategorySlug,
                    autoFocusSearch = autoFocusSearch,
                )
            }
        }
    }
}
