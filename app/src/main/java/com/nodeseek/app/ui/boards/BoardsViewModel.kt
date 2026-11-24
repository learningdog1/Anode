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
    val recentSearchQueries: List<String> = emptyList(),
    val isSignedIn: Boolean = false,
    // Search & filter state
    val queryInput: String = "",
    val submittedQuery: String = "",
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
    val searchNextPageError: String? = null,
    val isSearchChallengeRequired: Boolean = false,
    val snackbarMessage: String? = null,
) {
    val matchingCategories: List<Category>
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
    val searchNextPageError: String? = null,
    val isSearchChallengeRequired: Boolean = false,
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
) : ViewModel() {

    private val _state = MutableStateFlow(InternalBoardsState())

    private var searchJob: Job? = null

    private val categoriesFlow = forumRepository.observeCategories()
        .map { list -> list.ifEmpty { Category.DEFAULT_CATEGORIES } }
        .stateIn(viewModelScope, SharingStarted.Lazily, Category.DEFAULT_CATEGORIES)

    private val bookmarkedIdsFlow = bookmarkAndHistoryRepository.observeBookmarkedIds()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    private val readStateMapFlow = _state
        .map { s -> s.rawSearchTopics.mapTo(HashSet()) { it.id } }
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
        BoardsUiState(
            categories = categories,
            recentSearchQueries = recentQueries,
            isSignedIn = overlay.session != null,
            queryInput = s.queryInput,
            submittedQuery = s.submittedQuery,
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
            searchNextPageError = s.searchNextPageError,
            isSearchChallengeRequired = s.isSearchChallengeRequired,
            snackbarMessage = s.snackbarMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, BoardsUiState())

    init {
        viewModelScope.launch {
            runCatching { forumRepository.refreshCategories(force = false) }
        }
        var lastHandledAuthEpoch = sessionRepository.authVerifiedEpoch.value
        viewModelScope.launch {
            sessionRepository.authVerifiedEpoch.collect { epoch ->
                if (epoch == lastHandledAuthEpoch) return@collect
                lastHandledAuthEpoch = epoch
                if (epoch > 0L) {
                    forumRepository.clearRssFallbackCache()
                    val cur = _state.value
                    if (cur.isSearchChallengeRequired && cur.submittedQuery.isNotBlank()) {
                        if (cur.searchNextPageError != null && cur.rawSearchTopics.isNotEmpty()) {
                            executeSearch(reset = false)
                        } else {
                            executeSearch(reset = true)
                        }
                    }
                }
            }
        }
    }

    // ── Search Controls ──────────────────────────────────────────────────────────────────────

    fun updateQueryInput(newQuery: String) {
        _state.update { current ->
            current.copy(queryInput = newQuery)
        }
    }

    fun selectSearchScope(scope: SearchScope) {
        if (_state.value.searchScope == scope) return
        _state.update {
            it.copy(
                searchScope = scope,
                searchErrorMessage = null,
                searchNextPageError = null,
            )
        }
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
                bookmarkAndHistoryRepository.setTopicBookmarked(topic.id, targetBookmarked)
                    .onFailure { err ->
                        val fallbackMsg = if (targetBookmarked) {
                            "云端收藏同步未完成，已保存在本机"
                        } else {
                            "取消云端收藏同步未完成，已从本机移除"
                        }
                        _state.update {
                            it.copy(snackbarMessage = err.message ?: fallbackMsg)
                        }
                    }
            } else {
                _state.update {
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
            if (_state.value.submittedQuery.isNotBlank()) {
                val cur = _state.value
                if (cur.searchNextPageError != null && cur.rawSearchTopics.isNotEmpty()) {
                    executeSearch(reset = false)
                } else {
                    executeSearch(reset = true)
                }
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
                        isSearchingNextPage = false,
                        searchErrorMessage = null,
                        searchNextPageError = null,
                        isSearchChallengeRequired = false,
                    )
                } else {
                    it.copy(
                        isSearchingNextPage = true,
                        searchNextPageError = null,
                    )
                }
            }

            if (scope == SearchScope.USERS) {
                val cleanUserQuery = query.removePrefix("@").trim()
                if (cleanUserQuery.isEmpty()) {
                    _state.update {
                        it.copy(
                            searchUsers = emptyList(),
                            isSearching = false,
                            isSearchingNextPage = false,
                            searchHasNextPage = false,
                            searchErrorMessage = null,
                            searchNextPageError = null,
                        )
                    }
                    return@launch
                }
                userRepository.searchUsers(cleanUserQuery).fold(
                    onSuccess = { users ->
                        _state.update {
                            it.copy(
                                searchUsers = users.distinctBy { member -> member.uid },
                                isSearching = false,
                                isSearchingNextPage = false,
                                searchHasNextPage = false,
                                searchErrorMessage = null,
                                searchNextPageError = null,
                            )
                        }
                    },
                    onFailure = { err ->
                        _state.update {
                            it.copy(
                                isSearching = false,
                                isSearchingNextPage = false,
                                searchErrorMessage = err.message ?: "搜索用户失败，请重试",
                                searchNextPageError = null,
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
                                result.topics.distinctBy { it.id }
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
                                searchNextPageError = null,
                                isSearchChallengeRequired = false,
                            )
                        }
                    },
                    onFailure = { err ->
                        val msg = err.message ?: "站内搜索受限，可使用下方 Google 站内搜索"
                        val needsWeb = err.isWebVerificationNeeded()
                        _state.update { cur ->
                            if (reset || cur.rawSearchTopics.isEmpty()) {
                                cur.copy(
                                    isSearching = false,
                                    isSearchingNextPage = false,
                                    searchErrorMessage = msg,
                                    searchNextPageError = null,
                                    isSearchChallengeRequired = needsWeb,
                                )
                            } else {
                                cur.copy(
                                    isSearching = false,
                                    isSearchingNextPage = false,
                                    searchNextPageError = msg,
                                    isSearchChallengeRequired = needsWeb,
                                )
                            }
                        }
                    },
                )
            }
        }
    }

    companion object {
        fun factory(
            container: AppContainer,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BoardsViewModel(
                    forumRepository = container.forumRepository,
                    userRepository = container.userRepository,
                    sessionRepository = container.sessionRepository,
                    bookmarkAndHistoryRepository = container.bookmarkAndHistoryRepository,
                    settingsRepository = container.settingsRepository,
                    contentFilterRepository = container.contentFilterRepository,
                )
            }
        }
    }
}
