package com.nodeseek.app.ui.topic

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.EditorDraft
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.FloorComment
import com.nodeseek.app.domain.model.ReactionType
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.TopicAccessException
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.radar.RadarRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
sealed interface TopicAccessUiError {
    data object SignInRequired : TopicAccessUiError
    data object CloudflareChallengeRequired : TopicAccessUiError
    data class LevelRequired(val minimumLevel: Int?) : TopicAccessUiError
    data object PrivateTopic : TopicAccessUiError
    data class NetworkOrGeneric(val message: String) : TopicAccessUiError
}

@Immutable
sealed interface PendingTopicPaidAction {
    val coinCost: Int

    data class DrumstickReaction(
        val commentId: Long,
        val floorNumber: Int,
        val authorName: String,
        override val coinCost: Int = 1,
    ) : PendingTopicPaidAction

    data class DislikeReaction(
        val commentId: Long,
        val floorNumber: Int,
        val authorName: String,
        override val coinCost: Int = 2,
    ) : PendingTopicPaidAction

    data class PinReply(
        val commentId: Long,
        val floorNumber: Int,
        val authorName: String,
        override val coinCost: Int = 5,
    ) : PendingTopicPaidAction
}

@Immutable
data class QuotedFloorContext(
    val username: String,
    val floorNumber: Int,
)

enum class ReplyEditorTab(val label: String) {
    WRITE("编辑"),
    PREVIEW("预览"),
}

@Immutable
data class ReplyComposerUiState(
    val isSheetOpen: Boolean = false,
    val editingComment: FloorComment? = null,
    val quotedContext: QuotedFloorContext? = null,
    val activeTab: ReplyEditorTab = ReplyEditorTab.WRITE,
    val draftMarkdown: String = "",
    val composerRevision: Int = 0,
    val isSubmitting: Boolean = false,
    val isUploadingImage: Boolean = false,
    val showStickerPicker: Boolean = false,
    val showApiKeyDialog: Boolean = false,
) {
    val isEditMode: Boolean
        get() = editingComment != null

    val hasUnsentDraft: Boolean
        get() = !isEditMode && draftMarkdown.isNotBlank()
}

sealed interface TopicDetailUiEvent {
    data class ShowMessage(
        val message: String,
        val actionLabel: String? = null,
        val webViewUrl: String? = null,
    ) : TopicDetailUiEvent
    data class NavigateToUser(val uid: Long, val username: String) : TopicDetailUiEvent
    data class RequestWebViewAuth(val url: String) : TopicDetailUiEvent
    data class InsertUploadedMarkdown(val snippet: String) : TopicDetailUiEvent
}

@Immutable
data class TopicDetailUiState(
    val topicId: Long,
    val titleHint: String = "",
    val session: AccountSession? = null,
    val settings: AppSettings = AppSettings(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isPageSwitching: Boolean = false,
    val isAppendingNextPage: Boolean = false,
    val isPrependingPreviousPage: Boolean = false,
    val pageData: TopicDetailPage? = null,
    val openingFloor: FloorComment? = null,
    val displayedReplies: List<FloorComment> = emptyList(),
    val currentPage: Int = 1,
    val loadedStartPage: Int = 1,
    val loadedEndPage: Int = 1,
    val totalPages: Int = 1,
    val estimatedTotalFloors: Int = 0,
    val currentVisibleFloor: Int = 0,
    val opOnly: Boolean = false,
    val reverseOrder: Boolean = false,
    val isOpExpandedOnSubPage: Boolean = false,
    val isBookmarked: Boolean = false,
    val effectiveBookmarkCount: Int = 0,
    val accessError: TopicAccessUiError? = null,
    val pendingScrollFloor: Int? = null,
    val highlightedFloor: Int? = null,
    val busyCommentIds: Set<Long> = emptySet(),
    val pendingPaidAction: PendingTopicPaidAction? = null,
    val showFloorJumpSheet: Boolean = false,
    val replyComposer: ReplyComposerUiState = ReplyComposerUiState(),
    val isTrackingReplies: Boolean = false,
    val trackedBaselineFloor: Int = -1,
) {
    val canEditTopic: Boolean
        get() = session != null && pageData != null && session.uid == pageData.author.uid

    val isViewerTopicAuthor: Boolean
        get() = canEditTopic

    val hasPreviousUnloadedPages: Boolean
        get() = loadedStartPage > 1

    val hasMoreNextPages: Boolean
        get() = loadedEndPage < totalPages
}

private data class InternalReaderState(
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isPageSwitching: Boolean = false,
    val isAppendingNextPage: Boolean = false,
    val isPrependingPreviousPage: Boolean = false,
    val currentPage: Int = 1,
    val loadedStartPage: Int = 1,
    val loadedEndPage: Int = 1,
    val pagesCache: Map<Int, TopicDetailPage> = emptyMap(),
    val cachedOpeningFloor: FloorComment? = null,
    val currentVisibleFloor: Int = 0,
    val highestVisitedFloor: Int = 0,
    val opOnly: Boolean = false,
    val reverseOrder: Boolean = false,
    val isOpExpandedOnSubPage: Boolean = false,
    val optimisticBookmarked: Boolean? = null,
    val bookmarkCountDelta: Int = 0,
    val accessError: TopicAccessUiError? = null,
    val pendingScrollFloor: Int? = null,
    val highlightedFloor: Int? = null,
    val busyCommentIds: Set<Long> = emptySet(),
    val pendingPaidAction: PendingTopicPaidAction? = null,
    val showFloorJumpSheet: Boolean = false,
    val replyComposer: ReplyComposerUiState = ReplyComposerUiState(),
)

private data class PendingUploadPayload(
    val bytes: ByteArray,
    val mimeType: String,
    val fileName: String? = null,
)

class TopicDetailViewModel(
    private val container: AppContainer,
    val topicId: Long,
    private val initialFloor: Int = 0,
    private val titleHint: String = "",
    private val radarRepository: RadarRepository? = null,
) : ViewModel() {

    private val initialStartPage = if (initialFloor > 0) TopicDetailPage.pageForFloor(initialFloor) else 1

    private val internalState = MutableStateFlow(
        InternalReaderState(
            currentPage = initialStartPage,
            loadedStartPage = initialStartPage,
            loadedEndPage = initialStartPage,
            currentVisibleFloor = initialFloor.coerceAtLeast(0),
            highestVisitedFloor = initialFloor.coerceAtLeast(0),
        )
    )

    private val _visibleFloor = MutableStateFlow(initialFloor.coerceAtLeast(0))
    val visibleFloor: StateFlow<Int> = _visibleFloor.asStateFlow()
    private var highestVisitedFloorState: Int = initialFloor.coerceAtLeast(0)

    private val _events = Channel<TopicDetailUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var saveDraftJob: Job? = null
    private var historyRecordJob: Job? = null
    private var highlightClearJob: Job? = null
    private var pendingImageUpload: PendingUploadPayload? = null
    private var lastHandledRefreshTrigger: Long = 0L

    val uiState: StateFlow<TopicDetailUiState> = combine(
        internalState,
        container.sessionRepository.sessionState,
        container.settingsRepository.settings,
        container.bookmarkAndHistoryRepository.observeBookmarkedIds(),
        radarRepository?.observeTrackedTopic(topicId) ?: flowOf(null),
    ) { state, session, settings, localBookmarkedIds, trackedTopic ->
        val pageData = state.pagesCache[state.currentPage]
            ?: state.pagesCache[state.loadedStartPage]
            ?: state.pagesCache.values.firstOrNull()
        val openingFloor = pageData?.openingFloor ?: state.cachedOpeningFloor
        val windowStart = state.loadedStartPage.coerceAtLeast(1)
        val windowEnd = state.loadedEndPage.coerceAtLeast(windowStart)
        val seenKeys = HashSet<Long>()
        val rawReplies = buildList {
            for (p in windowStart..windowEnd) {
                val replies = state.pagesCache[p]?.replies.orEmpty()
                for (reply in replies) {
                    val dedupKey = if (reply.commentId > 0L) reply.commentId else -reply.floorNumber.toLong()
                    if (seenKeys.add(dedupKey)) {
                        add(reply)
                    }
                }
            }
        }.sortedBy { it.floorNumber }

        val filteredReplies = if (state.opOnly && pageData != null) {
            rawReplies.filter { reply ->
                reply.author.isTopicAuthor || reply.author.uid == pageData.author.uid
            }
        } else {
            rawReplies
        }

        val orderedReplies = if (state.reverseOrder) {
            filteredReplies.sortedByDescending { it.floorNumber }
        } else {
            filteredReplies
        }

        val baseBookmarked = localBookmarkedIds.contains(topicId) || (pageData?.isBookmarked == true)
        val isBookmarked = state.optimisticBookmarked ?: baseBookmarked
        val effectiveBookmarkCount = ((pageData?.bookmarkCount ?: 0) + state.bookmarkCountDelta).coerceAtLeast(0)

        val maxCachedTotalFloors = state.pagesCache.values.maxOfOrNull { it.estimatedTotalFloors } ?: 0
        val estimatedTotalFloors = maxOf(pageData?.estimatedTotalFloors ?: 0, maxCachedTotalFloors)
        val totalPages = maxOf(
            pageData?.totalPages ?: 1,
            state.pagesCache.values.maxOfOrNull { it.totalPages } ?: 1,
        )

        TopicDetailUiState(
            topicId = topicId,
            titleHint = titleHint,
            session = session,
            settings = settings,
            isInitialLoading = state.isInitialLoading,
            isRefreshing = state.isRefreshing,
            isPageSwitching = state.isPageSwitching,
            isAppendingNextPage = state.isAppendingNextPage,
            isPrependingPreviousPage = state.isPrependingPreviousPage,
            pageData = pageData,
            openingFloor = openingFloor,
            displayedReplies = orderedReplies,
            currentPage = state.currentPage,
            loadedStartPage = state.loadedStartPage,
            loadedEndPage = state.loadedEndPage,
            totalPages = totalPages,
            estimatedTotalFloors = estimatedTotalFloors,
            currentVisibleFloor = state.currentVisibleFloor,
            opOnly = state.opOnly,
            reverseOrder = state.reverseOrder,
            isOpExpandedOnSubPage = state.isOpExpandedOnSubPage,
            isBookmarked = isBookmarked,
            effectiveBookmarkCount = effectiveBookmarkCount,
            accessError = state.accessError,
            pendingScrollFloor = state.pendingScrollFloor,
            highlightedFloor = state.highlightedFloor,
            busyCommentIds = state.busyCommentIds,
            pendingPaidAction = state.pendingPaidAction,
            showFloorJumpSheet = state.showFloorJumpSheet,
            replyComposer = state.replyComposer,
            isTrackingReplies = trackedTopic?.isActive == true,
            trackedBaselineFloor = trackedTopic?.lastKnownFloor ?: -1,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TopicDetailUiState(
            topicId = topicId,
            titleHint = titleHint,
            currentPage = initialStartPage,
            loadedStartPage = initialStartPage,
            loadedEndPage = initialStartPage,
            currentVisibleFloor = initialFloor.coerceAtLeast(0),
        ),
    )

    init {
        restoreReplyDraft()
        val startPage = if (initialFloor > 0) TopicDetailPage.pageForFloor(initialFloor) else 1
        loadPage(
            page = startPage,
            forceNetwork = false,
            targetFloor = initialFloor.takeIf { it > 0 },
            isInitial = true,
        )
        if (startPage > 1) {
            prefetchOpeningPageIfNeeded()
        }
        viewModelScope.launch {
            container.sessionRepository.authVerifiedEpoch.collect { epoch ->
                if (epoch > 0L) {
                    val cur = internalState.value
                    if (!cur.isInitialLoading && (cur.accessError != null || cur.pagesCache.isEmpty())) {
                        retryLoad()
                    }
                }
            }
        }
    }

    private fun restoreReplyDraft() {
        viewModelScope.launch {
            val uid = container.sessionRepository.sessionState.value?.uid ?: 0L
            val saved = container.draftRepository.getDraft(uid, EditorDraft.replyKey(topicId))
            if (saved != null && saved.markdownContent.isNotBlank()) {
                internalState.update { state ->
                    if (state.replyComposer.draftMarkdown.isBlank() && !state.replyComposer.isEditMode) {
                        state.copy(
                            replyComposer = state.replyComposer.copy(
                                draftMarkdown = saved.markdownContent,
                                composerRevision = state.replyComposer.composerRevision + 1,
                            )
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    private fun prefetchOpeningPageIfNeeded() {
        viewModelScope.launch {
            val includeBlocked = container.settingsRepository.settings.value.includeBlockedTopics
            container.forumRepository.getTopicDetail(
                topicId = topicId,
                page = 1,
                includeBlocked = includeBlocked,
            ).onSuccess { page1 ->
                internalState.update { state ->
                    val freshOpening = page1.openingFloor ?: state.cachedOpeningFloor
                    val updatedCache = state.pagesCache.toMutableMap()
                    updatedCache[1] = page1
                    val activePage = state.currentPage
                    if (activePage > 1) {
                        updatedCache[activePage]?.let { subPage ->
                            updatedCache[activePage] = subPage.copy(
                                title = page1.title.ifBlank { subPage.title },
                                requiredViewLevel = page1.requiredViewLevel,
                                openingFloor = freshOpening,
                            )
                        }
                    }
                    state.copy(
                        pagesCache = updatedCache,
                        cachedOpeningFloor = freshOpening,
                    )
                }
            }
        }
    }

    fun refreshCurrentPage() {
        val page = internalState.value.loadedStartPage.coerceAtLeast(1)
        internalState.update { state ->
            val retained = state.pagesCache[page]?.let { mapOf(page to it) } ?: emptyMap()
            state.copy(
                pagesCache = retained,
                currentPage = page,
                loadedStartPage = page,
                loadedEndPage = page,
                isAppendingNextPage = false,
                isPrependingPreviousPage = false,
            )
        }
        loadPage(page = page, forceNetwork = true, targetFloor = null, isInitial = false, isPullRefresh = true)
        if (page > 1) {
            prefetchOpeningPageIfNeeded()
        }
    }

    fun onExternalRefreshTriggered(trigger: Long) {
        if (trigger <= 0L || trigger == lastHandledRefreshTrigger) return
        lastHandledRefreshTrigger = trigger
        val cur = internalState.value
        if (cur.accessError != null || cur.pagesCache.isEmpty()) {
            retryLoad()
        } else {
            refreshCurrentPage()
        }
    }

    fun retryLoad() {
        val page = internalState.value.currentPage
        loadPage(
            page = page,
            forceNetwork = true,
            targetFloor = initialFloor.takeIf { it > 0 && internalState.value.pagesCache.isEmpty() },
            isInitial = true,
        )
    }

    fun onWebViewAuthCompleted() {
        viewModelScope.launch {
            container.sessionRepository.onWebViewAuthCompleted()
            retryLoad()
        }
    }

    fun onWebViewDismissed() {
        viewModelScope.launch {
            container.sessionRepository.onWebViewDismissed()
            val cur = internalState.value
            if (cur.accessError != null || cur.pagesCache.isEmpty()) {
                retryLoad()
            } else {
                refreshCurrentPage()
            }
        }
    }

    fun loadPreviousPage() = prependPreviousPage()

    fun loadNextPage() = appendNextPage()

    fun appendNextPage() {
        val state = internalState.value
        val activePage = state.pagesCache[state.currentPage]
            ?: state.pagesCache[state.loadedStartPage]
            ?: state.pagesCache.values.firstOrNull()
        if (activePage?.isOfflineSnapshot == true) return
        val totalPages = maxOf(
            activePage?.totalPages ?: 1,
            state.pagesCache.values.maxOfOrNull { it.totalPages } ?: 1,
        )
        if (state.isInitialLoading || state.isPageSwitching || state.isAppendingNextPage) return
        if (state.loadedEndPage >= totalPages) return

        val nextPage = state.loadedEndPage + 1
        val cachedNext = state.pagesCache[nextPage]
        if (cachedNext != null && !cachedNext.isOfflineSnapshot) {
            internalState.update { s ->
                s.copy(
                    loadedEndPage = maxOf(s.loadedEndPage, nextPage),
                    currentPage = nextPage,
                )
            }
            notifyTailPageSeenIfNeeded(cachedNext)
            return
        }

        viewModelScope.launch {
            internalState.update { it.copy(isAppendingNextPage = true) }
            val includeBlocked = container.settingsRepository.settings.value.includeBlockedTopics
            val result = container.forumRepository.getTopicDetail(
                topicId = topicId,
                page = nextPage,
                includeBlocked = includeBlocked,
            )
            result.fold(
                onSuccess = { detail ->
                    internalState.update { s ->
                        val updatedOpening = detail.openingFloor ?: s.cachedOpeningFloor
                        val mergedDetail = if (detail.openingFloor == null && updatedOpening != null) {
                            detail.copy(openingFloor = updatedOpening)
                        } else {
                            detail
                        }
                        s.copy(
                            isAppendingNextPage = false,
                            pagesCache = s.pagesCache + (nextPage to mergedDetail),
                            cachedOpeningFloor = updatedOpening,
                            loadedEndPage = maxOf(s.loadedEndPage, nextPage),
                            currentPage = nextPage,
                        )
                    }
                    refreshOfflineSnapshotIfBookmarked(detail)
                    notifyTailPageSeenIfNeeded(detail)
                },
                onFailure = { error ->
                    internalState.update { it.copy(isAppendingNextPage = false) }
                    _events.trySend(
                        TopicDetailUiEvent.ShowMessage(
                            error.message?.takeIf { it.isNotBlank() } ?: "加载第 $nextPage 页失败，请稍后重试"
                        )
                    )
                },
            )
        }
    }

    fun prependPreviousPage() {
        val state = internalState.value
        val activePage = state.pagesCache[state.currentPage]
            ?: state.pagesCache[state.loadedStartPage]
            ?: state.pagesCache.values.firstOrNull()
        if (activePage?.isOfflineSnapshot == true) return
        if (state.isInitialLoading || state.isPageSwitching || state.isPrependingPreviousPage) return
        if (state.loadedStartPage <= 1) return

        val prevPage = state.loadedStartPage - 1
        val cachedPrev = state.pagesCache[prevPage]
        if (cachedPrev != null && !cachedPrev.isOfflineSnapshot) {
            internalState.update { s ->
                val updatedOpening = if (prevPage == 1) {
                    cachedPrev.openingFloor ?: s.cachedOpeningFloor
                } else {
                    cachedPrev.openingFloor ?: s.cachedOpeningFloor
                }
                s.copy(
                    loadedStartPage = minOf(s.loadedStartPage, prevPage),
                    cachedOpeningFloor = updatedOpening,
                )
            }
            return
        }

        viewModelScope.launch {
            internalState.update { it.copy(isPrependingPreviousPage = true) }
            val includeBlocked = container.settingsRepository.settings.value.includeBlockedTopics
            val result = container.forumRepository.getTopicDetail(
                topicId = topicId,
                page = prevPage,
                includeBlocked = includeBlocked,
            )
            result.fold(
                onSuccess = { detail ->
                    internalState.update { s ->
                        val updatedOpening = detail.openingFloor ?: s.cachedOpeningFloor
                        val mergedDetail = if (detail.openingFloor == null && updatedOpening != null) {
                            detail.copy(openingFloor = updatedOpening)
                        } else {
                            detail
                        }
                        s.copy(
                            isPrependingPreviousPage = false,
                            pagesCache = s.pagesCache + (prevPage to mergedDetail),
                            cachedOpeningFloor = updatedOpening,
                            loadedStartPage = minOf(s.loadedStartPage, prevPage),
                        )
                    }
                    refreshOfflineSnapshotIfBookmarked(detail)
                },
                onFailure = { error ->
                    internalState.update { it.copy(isPrependingPreviousPage = false) }
                    _events.trySend(
                        TopicDetailUiEvent.ShowMessage(
                            error.message?.takeIf { it.isNotBlank() } ?: "加载第 $prevPage 页失败，请稍后重试"
                        )
                    )
                },
            )
        }
    }

    fun selectPage(page: Int) {
        val safePage = page.coerceAtLeast(1)
        internalState.update { it.copy(showFloorJumpSheet = false) }
        val state = internalState.value
        val cachedTarget = state.pagesCache[safePage]
        if (safePage in state.loadedStartPage..state.loadedEndPage && cachedTarget != null) {
            val targetFloor = if (safePage == 1) 0 else cachedTarget.replies.firstOrNull()?.floorNumber
            if (targetFloor != null) {
                _visibleFloor.value = targetFloor
                highestVisitedFloorState = maxOf(highestVisitedFloorState, targetFloor)
            }
            internalState.update { s ->
                s.copy(
                    currentPage = safePage,
                    currentVisibleFloor = targetFloor ?: s.currentVisibleFloor,
                    pendingScrollFloor = targetFloor,
                )
            }
            if (targetFloor != null && targetFloor > 0) {
                triggerFloorHighlight(targetFloor)
            }
            return
        }
        loadPage(page = safePage, forceNetwork = false, targetFloor = if (safePage == 1) 0 else null)
    }

    fun jumpToFloor(floorNumber: Int) {
        val clampedFloor = floorNumber.coerceAtLeast(0)
        val targetPage = if (clampedFloor == 0) 1 else TopicDetailPage.pageForFloor(clampedFloor)
        internalState.update { it.copy(showFloorJumpSheet = false) }

        val currentState = internalState.value
        val inWindow = targetPage in currentState.loadedStartPage..currentState.loadedEndPage
        val targetPageData = currentState.pagesCache[targetPage]
        val isFloorInLoadedWindow = when {
            clampedFloor == 0 -> currentState.loadedStartPage == 1 && currentState.pagesCache[1] != null
            inWindow && targetPageData != null -> targetPageData.replies.any { it.floorNumber == clampedFloor }
            else -> (currentState.loadedStartPage..currentState.loadedEndPage).any { p ->
                currentState.pagesCache[p]?.replies?.any { it.floorNumber == clampedFloor } == true
            }
        }

        if (isFloorInLoadedWindow) {
            val matchedPage = (currentState.loadedStartPage..currentState.loadedEndPage).firstOrNull { p ->
                if (clampedFloor == 0) p == 1
                else currentState.pagesCache[p]?.replies?.any { it.floorNumber == clampedFloor } == true
            } ?: targetPage
            val matchedPageData = currentState.pagesCache[matchedPage]
            val topicAuthorUid = matchedPageData?.author?.uid ?: currentState.pagesCache.values.firstOrNull()?.author?.uid
            val matchedReply = if (clampedFloor > 0) {
                (currentState.loadedStartPage..currentState.loadedEndPage)
                    .asSequence()
                    .flatMap { p -> currentState.pagesCache[p]?.replies.orEmpty().asSequence() }
                    .firstOrNull { it.floorNumber == clampedFloor }
            } else {
                null
            }
            val targetHiddenByOpFilter = currentState.opOnly &&
                clampedFloor > 0 &&
                matchedReply != null &&
                !matchedReply.author.isTopicAuthor &&
                matchedReply.author.uid != topicAuthorUid

            _visibleFloor.value = clampedFloor
            highestVisitedFloorState = maxOf(highestVisitedFloorState, clampedFloor)
            internalState.update { state ->
                state.copy(
                    currentPage = matchedPage,
                    opOnly = if (targetHiddenByOpFilter) false else state.opOnly,
                    currentVisibleFloor = clampedFloor,
                    highestVisitedFloor = maxOf(state.highestVisitedFloor, clampedFloor),
                    pendingScrollFloor = clampedFloor,
                )
            }
            triggerFloorHighlight(clampedFloor)
        } else {
            loadPage(page = targetPage, forceNetwork = false, targetFloor = clampedFloor)
        }
    }

    fun toggleReplyTracking() {
        val repo = radarRepository ?: return
        val snapshot = uiState.value
        val pageData = snapshot.pageData
        val totalPages = snapshot.totalPages.coerceAtLeast(1)

        viewModelScope.launch {
            if (snapshot.isTrackingReplies) {
                repo.untrackTopic(topicId)
                _events.trySend(TopicDetailUiEvent.ShowMessage("已关闭此帖的新回复追踪"))
                return@launch
            }

            val onTailPage = snapshot.currentPage >= totalPages &&
                pageData != null &&
                !pageData.isOfflineSnapshot
            val currentMaxFloor = if (onTailPage) {
                pageData?.replies?.maxOfOrNull { it.floorNumber } ?: 0
            } else {
                -1
            }

            repo.trackTopic(
                topicId = topicId,
                title = pageData?.title ?: snapshot.titleHint.ifBlank { "主题 #$topicId" },
                categorySlug = pageData?.categorySlug.orEmpty(),
                categoryName = pageData?.categoryName.orEmpty(),
                authorName = pageData?.author?.username.orEmpty(),
                currentMaxFloor = currentMaxFloor,
                totalPages = totalPages,
            )

            _events.trySend(
                TopicDetailUiEvent.ShowMessage(
                    if (currentMaxFloor >= 0) {
                        "已开启追踪新回复（当前基线 #$currentMaxFloor 楼）"
                    } else {
                        "已开启追踪新回复，正在后台同步末页最新楼层…"
                    },
                )
            )

            if (currentMaxFloor < 0) {
                val includeBlocked = container.settingsRepository.settings.value.includeBlockedTopics
                container.forumRepository.getTopicDetail(
                    topicId = topicId,
                    page = totalPages,
                    includeBlocked = includeBlocked,
                ).onSuccess { tailDetail ->
                    if (!tailDetail.isOfflineSnapshot) {
                        val tailMaxFloor = tailDetail.replies.maxOfOrNull { it.floorNumber } ?: 0
                        repo.onTopicFloorSeen(
                            topicId = topicId,
                            maxSeenFloor = tailMaxFloor,
                            totalPages = tailDetail.totalPages.coerceAtLeast(1),
                        )
                    }
                }
            }
        }
    }

    private fun notifyTailPageSeenIfNeeded(detail: TopicDetailPage) {
        val repo = radarRepository ?: return
        if (detail.isOfflineSnapshot) return
        val safeTotalPages = detail.totalPages.coerceAtLeast(1)
        if (detail.currentPage < safeTotalPages) return
        val maxFloor = detail.replies.maxOfOrNull { it.floorNumber } ?: 0
        viewModelScope.launch {
            repo.onTopicFloorSeen(
                topicId = topicId,
                maxSeenFloor = maxFloor,
                totalPages = safeTotalPages,
            )
        }
    }

    private fun loadPage(
        page: Int,
        forceNetwork: Boolean,
        targetFloor: Int?,
        isInitial: Boolean = false,
        isPullRefresh: Boolean = false,
        preserveWindowIfContiguous: Boolean = false,
    ) {
        val safePage = page.coerceAtLeast(1)
        val cached = internalState.value.pagesCache[safePage]
        if (!forceNetwork && cached != null && !cached.isOfflineSnapshot) {
            val nextFloor = targetFloor ?: if (safePage > 1) {
                cached.replies.firstOrNull()?.floorNumber ?: 0
            } else {
                0
            }
            _visibleFloor.value = nextFloor
            highestVisitedFloorState = maxOf(highestVisitedFloorState, nextFloor)
            internalState.update { state ->
                val newStart = if (preserveWindowIfContiguous) minOf(state.loadedStartPage, safePage) else safePage
                val newEnd = if (preserveWindowIfContiguous) maxOf(state.loadedEndPage, safePage) else safePage
                state.copy(
                    currentPage = safePage,
                    loadedStartPage = newStart,
                    loadedEndPage = newEnd,
                    isInitialLoading = false,
                    isRefreshing = false,
                    isPageSwitching = false,
                    isAppendingNextPage = false,
                    isPrependingPreviousPage = false,
                    accessError = null,
                    isOpExpandedOnSubPage = false,
                    currentVisibleFloor = nextFloor,
                    highestVisitedFloor = maxOf(state.highestVisitedFloor, nextFloor),
                    pendingScrollFloor = targetFloor ?: if (safePage > 1) {
                        cached.replies.firstOrNull()?.floorNumber
                    } else {
                        0
                    },
                )
            }
            if (targetFloor != null && targetFloor > 0) {
                triggerFloorHighlight(targetFloor)
            }
            scheduleHistorySave(cached, targetFloor ?: _visibleFloor.value)
            notifyTailPageSeenIfNeeded(cached)
            return
        }

        viewModelScope.launch {
            internalState.update { state ->
                state.copy(
                    isInitialLoading = isInitial && state.pagesCache.isEmpty(),
                    isRefreshing = isPullRefresh,
                    isPageSwitching = !isInitial && !isPullRefresh,
                    accessError = if (state.pagesCache.isEmpty()) null else state.accessError,
                )
            }

            val includeBlocked = container.settingsRepository.settings.value.includeBlockedTopics
            val result = container.forumRepository.getTopicDetail(
                topicId = topicId,
                page = safePage,
                includeBlocked = includeBlocked,
            )

            result.fold(
                onSuccess = { detail ->
                    internalState.update { state ->
                        val updatedOpening = detail.openingFloor ?: state.cachedOpeningFloor
                        val mergedDetail = if (detail.openingFloor == null && updatedOpening != null) {
                            detail.copy(openingFloor = updatedOpening)
                        } else {
                            detail
                        }
                        val targetHiddenByOpFilter = state.opOnly &&
                            targetFloor != null &&
                            targetFloor > 0 &&
                            mergedDetail.replies.firstOrNull { it.floorNumber == targetFloor }?.let {
                                !it.author.isTopicAuthor && it.author.uid != mergedDetail.author.uid
                            } == true

                        val initialVisibleFloor = targetFloor
                            ?: if (preserveWindowIfContiguous) {
                                mergedDetail.replies.lastOrNull()?.floorNumber ?: 0
                            } else {
                                mergedDetail.replies.firstOrNull()?.floorNumber?.takeIf { safePage > 1 } ?: 0
                            }

                        _visibleFloor.value = initialVisibleFloor
                        highestVisitedFloorState = maxOf(highestVisitedFloorState, initialVisibleFloor)

                        val newStart = if (preserveWindowIfContiguous) {
                            minOf(state.loadedStartPage, safePage)
                        } else {
                            safePage
                        }
                        val newEnd = if (preserveWindowIfContiguous) {
                            maxOf(state.loadedEndPage, safePage)
                        } else {
                            safePage
                        }

                        state.copy(
                            isInitialLoading = false,
                            isRefreshing = false,
                            isPageSwitching = false,
                            isAppendingNextPage = false,
                            isPrependingPreviousPage = false,
                            currentPage = safePage,
                            loadedStartPage = newStart,
                            loadedEndPage = newEnd,
                            pagesCache = state.pagesCache + (safePage to mergedDetail),
                            cachedOpeningFloor = updatedOpening,
                            accessError = null,
                            opOnly = if (targetHiddenByOpFilter) false else state.opOnly,
                            isOpExpandedOnSubPage = false,
                            currentVisibleFloor = initialVisibleFloor,
                            highestVisitedFloor = maxOf(state.highestVisitedFloor, initialVisibleFloor),
                            pendingScrollFloor = targetFloor ?: if (preserveWindowIfContiguous) {
                                mergedDetail.replies.lastOrNull()?.floorNumber
                            } else if (!isPullRefresh && safePage > 1) {
                                mergedDetail.replies.firstOrNull()?.floorNumber
                            } else if (!isPullRefresh && !isInitial) {
                                0
                            } else {
                                null
                            },
                        )
                    }

                    if (targetFloor != null && targetFloor > 0) {
                        triggerFloorHighlight(targetFloor)
                    }

                    val loadedPage = internalState.value.pagesCache[safePage] ?: detail
                    scheduleHistorySave(
                        page = loadedPage,
                        floor = targetFloor ?: _visibleFloor.value,
                        immediate = true,
                    )

                    // Keep offline bookmark snapshot fresh if already bookmarked locally
                    refreshOfflineSnapshotIfBookmarked(loadedPage)
                    notifyTailPageSeenIfNeeded(loadedPage)
                },
                onFailure = { error ->
                    val classified = classifyError(error)
                    // Attempt offline bookmark snapshot fallback on network failures
                    val offlineSnapshot = if (classified is TopicAccessUiError.NetworkOrGeneric) {
                        runCatching {
                            container.bookmarkAndHistoryRepository.getOfflineBookmarkSnapshot(topicId)
                        }.getOrNull()
                    } else {
                        null
                    }

                    if (offlineSnapshot != null && internalState.value.pagesCache.isEmpty()) {
                        val markedOffline = offlineSnapshot.copy(isOfflineSnapshot = true)
                        internalState.update { state ->
                            state.copy(
                                isInitialLoading = false,
                                isRefreshing = false,
                                isPageSwitching = false,
                                isAppendingNextPage = false,
                                isPrependingPreviousPage = false,
                                currentPage = markedOffline.currentPage,
                                loadedStartPage = markedOffline.currentPage,
                                loadedEndPage = markedOffline.currentPage,
                                pagesCache = mapOf(markedOffline.currentPage to markedOffline),
                                cachedOpeningFloor = markedOffline.openingFloor,
                                accessError = null,
                                pendingScrollFloor = targetFloor,
                            )
                        }
                        _events.trySend(
                            TopicDetailUiEvent.ShowMessage("网络不可用，已为您加载本地离线收藏快照")
                        )
                    } else {
                        internalState.update { state ->
                            state.copy(
                                isInitialLoading = false,
                                isRefreshing = false,
                                isPageSwitching = false,
                                accessError = if (state.pagesCache.isEmpty()) classified else null,
                            )
                        }
                        if (internalState.value.pagesCache.isNotEmpty()) {
                            _events.trySend(
                                TopicDetailUiEvent.ShowMessage(
                                    error.message?.takeIf { it.isNotBlank() } ?: "加载页面失败，请稍后重试"
                                )
                            )
                        }
                    }
                },
            )
        }
    }

    private fun triggerFloorHighlight(floorNumber: Int) {
        highlightClearJob?.cancel()
        internalState.update { it.copy(highlightedFloor = floorNumber) }
        highlightClearJob = viewModelScope.launch {
            delay(2_200)
            internalState.update { state ->
                if (state.highlightedFloor == floorNumber) {
                    state.copy(highlightedFloor = null)
                } else {
                    state
                }
            }
        }
    }

    fun consumePendingScrollFloor() {
        internalState.update { it.copy(pendingScrollFloor = null) }
    }

    fun onVisibleFloorChanged(floorNumber: Int) {
        val safeFloor = floorNumber.coerceAtLeast(0)
        if (_visibleFloor.value == safeFloor) return
        _visibleFloor.value = safeFloor
        highestVisitedFloorState = maxOf(highestVisitedFloorState, safeFloor)

        val current = internalState.value
        val targetPageNum = if (safeFloor <= 0) 1 else TopicDetailPage.pageForFloor(safeFloor)
        val pageData = current.pagesCache[targetPageNum]
            ?: current.pagesCache[current.currentPage]
            ?: current.pagesCache[current.loadedStartPage]
        if (pageData != null) {
            scheduleHistorySave(page = pageData, floor = safeFloor, immediate = false)
        }
    }

    private fun scheduleHistorySave(
        page: TopicDetailPage,
        floor: Int,
        immediate: Boolean = false,
    ) {
        if (page.isOfflineSnapshot) return
        historyRecordJob?.cancel()
        historyRecordJob = viewModelScope.launch {
            if (!immediate) {
                delay(650)
            }
            val uid = container.sessionRepository.sessionState.value?.uid ?: 0L
            val entry = ReadHistoryEntry(
                topicId = page.topicId,
                title = page.title,
                categoryName = page.categoryName,
                authorName = page.author.username,
                authorAvatarUrl = page.author.avatarUrl,
                lastReadPage = page.currentPage,
                lastReadFloor = floor.coerceAtLeast(0),
                visitedAtEpochMillis = System.currentTimeMillis(),
                categorySlug = page.categorySlug,
                accountUid = uid,
            )
            runCatching {
                container.bookmarkAndHistoryRepository.recordTopicVisit(entry)
            }
        }
    }

    private suspend fun refreshOfflineSnapshotIfBookmarked(detail: TopicDetailPage) {
        val isCurrentlyBookmarked = uiState.value.isBookmarked
        if (!isCurrentlyBookmarked || detail.isOfflineSnapshot) return
        val opening = internalState.value.cachedOpeningFloor ?: detail.openingFloor
        val snapshot = detail.copy(openingFloor = opening, isOfflineSnapshot = true)
        runCatching {
            container.bookmarkAndHistoryRepository.saveOfflineBookmark(
                summary = detail.toTopicSummary(isBookmarked = true),
                detailSnapshot = snapshot,
                syncedWithServer = container.sessionRepository.sessionState.value != null,
            )
        }
    }

    fun toggleOpOnly() {
        internalState.update { it.copy(opOnly = !it.opOnly) }
    }

    fun toggleReverseOrder() {
        internalState.update { it.copy(reverseOrder = !it.reverseOrder) }
    }

    fun toggleOpExpandedOnSubPage() {
        internalState.update { it.copy(isOpExpandedOnSubPage = !it.isOpExpandedOnSubPage) }
    }

    fun setFloorJumpSheetVisible(visible: Boolean) {
        internalState.update { it.copy(showFloorJumpSheet = visible) }
    }

    fun toggleBookmark() {
        val currentUi = uiState.value
        val page = currentUi.pageData ?: return
        val targetBookmarked = !currentUi.isBookmarked
        val deltaAdjustment = if (targetBookmarked) 1 else -1

        internalState.update { state ->
            state.copy(
                optimisticBookmarked = targetBookmarked,
                bookmarkCountDelta = state.bookmarkCountDelta + deltaAdjustment,
            )
        }

        viewModelScope.launch {
            val isSignedIn = container.sessionRepository.sessionState.value != null
            if (targetBookmarked) {
                val opening = internalState.value.cachedOpeningFloor ?: page.openingFloor
                val offlineSnapshot = page.copy(openingFloor = opening, isOfflineSnapshot = true)
                val summary = page.toTopicSummary(isBookmarked = true)

                var serverSynced = false
                if (isSignedIn) {
                    container.bookmarkAndHistoryRepository.setTopicBookmarked(topicId, true)
                        .onSuccess { serverSynced = true }
                        .onFailure { err ->
                            _events.trySend(
                                TopicDetailUiEvent.ShowMessage(
                                    err.message?.let { "已离线收藏（云端同步稍后重试：$it）" }
                                        ?: "已保存离线收藏快照"
                                )
                            )
                        }
                }
                runCatching {
                    container.bookmarkAndHistoryRepository.saveOfflineBookmark(
                        summary = summary,
                        detailSnapshot = offlineSnapshot,
                        syncedWithServer = serverSynced,
                    )
                }
                if (serverSynced || !isSignedIn) {
                    _events.trySend(
                        TopicDetailUiEvent.ShowMessage(
                            if (serverSynced) "已同步收藏并保存离线阅读快照" else "已保存本地离线收藏快照"
                        )
                    )
                }
            } else {
                runCatching { container.bookmarkAndHistoryRepository.removeLocalBookmark(topicId) }
                if (isSignedIn) {
                    container.bookmarkAndHistoryRepository.setTopicBookmarked(topicId, false)
                        .onFailure { err ->
                            _events.trySend(
                                TopicDetailUiEvent.ShowMessage(
                                    err.message ?: "取消云端收藏失败，已移除本地收藏"
                                )
                            )
                        }
                }
                _events.trySend(TopicDetailUiEvent.ShowMessage("已取消收藏"))
            }
        }
    }

    // ── @username Mention Resolution ──────────────────────────────────────────

    fun resolveAndNavigateToUser(rawUsername: String) {
        val cleaned = rawUsername.trim().removePrefix("@")
        if (cleaned.isEmpty()) return

        // 1. Check loaded authors first (instant, zero network cost)
        val state = internalState.value
        val allLoadedAuthors = buildList {
            state.pagesCache.values.forEach { page ->
                add(page.author)
                page.openingFloor?.author?.let(::add)
                page.replies.forEach { add(it.author) }
            }
        }
        val localMatch = allLoadedAuthors.firstOrNull {
            it.username.equals(cleaned, ignoreCase = true) && it.uid > 0L
        }
        if (localMatch != null) {
            _events.trySend(TopicDetailUiEvent.NavigateToUser(localMatch.uid, localMatch.username))
            return
        }

        // 2. Query userRepository.searchUsers(username)
        viewModelScope.launch {
            container.userRepository.searchUsers(cleaned)
                .onSuccess { candidates ->
                    val exact = candidates.firstOrNull { it.username.equals(cleaned, ignoreCase = true) }
                        ?: candidates.firstOrNull()
                    if (exact != null && exact.uid > 0L) {
                        _events.trySend(TopicDetailUiEvent.NavigateToUser(exact.uid, exact.username))
                    } else {
                        _events.trySend(TopicDetailUiEvent.ShowMessage("未找到用户 @$cleaned"))
                    }
                }
                .onFailure {
                    _events.trySend(TopicDetailUiEvent.ShowMessage("查询用户 @$cleaned 失败"))
                }
        }
    }

    // ── Comment Reactions & OP Pinning ────────────────────────────────────────

    fun onUpvoteClicked(comment: FloorComment) {
        if (!ensureAuthenticatedForAction()) return
        if (comment.commentId <= 0L) {
            _events.trySend(TopicDetailUiEvent.ShowMessage("回复正在同步楼层信息，请稍后再试"))
            return
        }
        if (comment.hasUpvoted) {
            _events.trySend(TopicDetailUiEvent.ShowMessage("您已为该楼层点过赞"))
            return
        }
        if (internalState.value.busyCommentIds.contains(comment.commentId)) return
        executeReaction(comment.commentId, ReactionType.UPVOTE)
    }

    fun onDrumstickClicked(comment: FloorComment) {
        if (!ensureAuthenticatedForAction()) return
        if (comment.commentId <= 0L) {
            _events.trySend(TopicDetailUiEvent.ShowMessage("回复正在同步楼层信息，请稍后再试"))
            return
        }
        if (comment.hasGivenDrumstick) {
            _events.trySend(TopicDetailUiEvent.ShowMessage("您已为该楼层加过鸡腿"))
            return
        }
        if (internalState.value.busyCommentIds.contains(comment.commentId)) return

        viewModelScope.launch {
            markCommentBusy(comment.commentId, true)
            val costResult = container.forumRepository.getFreeDrumstickCost()
            markCommentBusy(comment.commentId, false)

            val cost = costResult.getOrElse { 1 }
            if (cost <= 0) {
                executeReaction(comment.commentId, ReactionType.DRUMSTICK)
            } else {
                internalState.update {
                    it.copy(
                        pendingPaidAction = PendingTopicPaidAction.DrumstickReaction(
                            commentId = comment.commentId,
                            floorNumber = comment.floorNumber,
                            authorName = comment.author.username,
                            coinCost = cost,
                        )
                    )
                }
            }
        }
    }

    fun onDislikeClicked(comment: FloorComment) {
        if (!ensureAuthenticatedForAction()) return
        if (comment.commentId <= 0L) {
            _events.trySend(TopicDetailUiEvent.ShowMessage("回复正在同步楼层信息，请稍后再试"))
            return
        }
        if (comment.hasDisliked) {
            _events.trySend(TopicDetailUiEvent.ShowMessage("您已反对过该楼层"))
            return
        }
        if (internalState.value.busyCommentIds.contains(comment.commentId)) return

        internalState.update {
            it.copy(
                pendingPaidAction = PendingTopicPaidAction.DislikeReaction(
                    commentId = comment.commentId,
                    floorNumber = comment.floorNumber,
                    authorName = comment.author.username,
                    coinCost = 2,
                )
            )
        }
    }

    fun onTogglePinReplyClicked(comment: FloorComment) {
        if (!ensureAuthenticatedForAction()) return
        if (comment.commentId <= 0L) {
            _events.trySend(TopicDetailUiEvent.ShowMessage("回复正在同步楼层信息，请稍后再试"))
            return
        }
        if (!uiState.value.isViewerTopicAuthor || comment.floorNumber <= 0) return
        if (internalState.value.busyCommentIds.contains(comment.commentId)) return

        if (comment.isPinned) {
            // Unpinning is free
            executeSetReplyPinned(comment.commentId, pinned = false)
        } else {
            // Pinning costs 5 coins — confirm first!
            internalState.update {
                it.copy(
                    pendingPaidAction = PendingTopicPaidAction.PinReply(
                        commentId = comment.commentId,
                        floorNumber = comment.floorNumber,
                        authorName = comment.author.username,
                        coinCost = 5,
                    )
                )
            }
        }
    }

    fun dismissPendingPaidAction() {
        internalState.update { it.copy(pendingPaidAction = null) }
    }

    fun confirmPendingPaidAction() {
        val pending = internalState.value.pendingPaidAction ?: return
        internalState.update { it.copy(pendingPaidAction = null) }

        when (pending) {
            is PendingTopicPaidAction.DrumstickReaction -> {
                executeReaction(pending.commentId, ReactionType.DRUMSTICK)
            }
            is PendingTopicPaidAction.DislikeReaction -> {
                executeReaction(pending.commentId, ReactionType.DISLIKE)
            }
            is PendingTopicPaidAction.PinReply -> {
                executeSetReplyPinned(pending.commentId, pinned = true)
            }
        }
    }

    private fun executeReaction(commentId: Long, reactionType: ReactionType) {
        viewModelScope.launch {
            markCommentBusy(commentId, true)
            val result = container.forumRepository.reactToComment(
                topicId = topicId,
                commentId = commentId,
                reaction = reactionType,
            )
            markCommentBusy(commentId, false)

            result.fold(
                onSuccess = { reactionResult ->
                    updateCommentAcrossCaches(commentId) { floor ->
                        when (reactionType) {
                            ReactionType.UPVOTE -> floor.copy(
                                upvoteCount = reactionResult.updatedCount,
                                hasUpvoted = true,
                            )
                            ReactionType.DRUMSTICK -> floor.copy(
                                drumstickCount = reactionResult.updatedCount,
                                hasGivenDrumstick = true,
                            )
                            ReactionType.DISLIKE -> floor.copy(
                                dislikeCount = reactionResult.updatedCount,
                                hasDisliked = true,
                            )
                        }
                    }
                    val toast = reactionResult.message?.takeIf { it.isNotBlank() } ?: when (reactionType) {
                        ReactionType.UPVOTE -> "点赞成功"
                        ReactionType.DRUMSTICK -> "已投喂鸡腿 🍗"
                        ReactionType.DISLIKE -> "已提交反对"
                    }
                    _events.trySend(TopicDetailUiEvent.ShowMessage(toast))
                },
                onFailure = { err ->
                    handleActionFailure(err, fallbackMessage = "互动操作失败，请稍后重试")
                },
            )
        }
    }

    private fun executeSetReplyPinned(commentId: Long, pinned: Boolean) {
        viewModelScope.launch {
            markCommentBusy(commentId, true)
            val result = container.forumRepository.setReplyPinned(commentId = commentId, pinned = pinned)
            markCommentBusy(commentId, false)

            result.fold(
                onSuccess = {
                    updateCommentAcrossCaches(commentId) { it.copy(isPinned = pinned) }
                    _events.trySend(
                        TopicDetailUiEvent.ShowMessage(if (pinned) "已置顶该回复" else "已取消置顶")
                    )
                    refreshCurrentPage()
                },
                onFailure = { err ->
                    handleActionFailure(err, fallbackMessage = if (pinned) "置顶回复失败" else "取消置顶失败")
                },
            )
        }
    }

    private fun markCommentBusy(commentId: Long, busy: Boolean) {
        internalState.update { state ->
            state.copy(
                busyCommentIds = if (busy) {
                    state.busyCommentIds + commentId
                } else {
                    state.busyCommentIds - commentId
                }
            )
        }
    }

    private fun updateCommentAcrossCaches(
        commentId: Long,
        transform: (FloorComment) -> FloorComment,
    ) {
        internalState.update { state ->
            val updatedOpening = state.cachedOpeningFloor?.let {
                if (it.commentId == commentId) transform(it) else it
            }
            val updatedPages = state.pagesCache.mapValues { (_, page) ->
                page.copy(
                    openingFloor = page.openingFloor?.let {
                        if (it.commentId == commentId) transform(it) else it
                    },
                    replies = page.replies.map { reply ->
                        if (reply.commentId == commentId) transform(reply) else reply
                    },
                )
            }
            state.copy(
                cachedOpeningFloor = updatedOpening,
                pagesCache = updatedPages,
            )
        }
    }

    // ── Reply & Edit Reply Composer ───────────────────────────────────────────

    fun openReplySheet(quoteComment: FloorComment? = null) {
        if (!ensureAuthenticatedForAction()) return
        val page = uiState.value.pageData
        if (page?.isLocked == true) {
            _events.trySend(TopicDetailUiEvent.ShowMessage("该主题已锁定，无法发布新回复"))
            return
        }

        viewModelScope.launch {
            val uid = container.sessionRepository.sessionState.value?.uid ?: 0L
            val savedDraft = container.draftRepository.getDraft(uid, EditorDraft.replyKey(topicId))
            val baseText = internalState.value.replyComposer.let { composer ->
                if (!composer.isEditMode && composer.draftMarkdown.isNotBlank()) {
                    composer.draftMarkdown
                } else {
                    savedDraft?.markdownContent.orEmpty()
                }
            }

            val quotePrefix = if (quoteComment != null) {
                "> @${quoteComment.author.username} #${quoteComment.floorNumber}\n\n"
            } else {
                ""
            }

            val mergedMarkdown = when {
                quotePrefix.isEmpty() -> baseText
                baseText.isBlank() -> quotePrefix
                baseText.contains("@${quoteComment!!.author.username} #${quoteComment.floorNumber}") -> baseText
                else -> "$quotePrefix$baseText"
            }

            internalState.update { state ->
                state.copy(
                    replyComposer = ReplyComposerUiState(
                        isSheetOpen = true,
                        editingComment = null,
                        quotedContext = quoteComment?.let {
                            QuotedFloorContext(username = it.author.username, floorNumber = it.floorNumber)
                        },
                        activeTab = ReplyEditorTab.WRITE,
                        draftMarkdown = mergedMarkdown,
                        composerRevision = state.replyComposer.composerRevision + 1,
                    )
                )
            }

            if (quotePrefix.isNotEmpty()) {
                scheduleReplyDraftSave(mergedMarkdown, editingCommentId = null)
            }
        }
    }

    fun openEditReplySheet(comment: FloorComment) {
        if (!ensureAuthenticatedForAction()) return
        if (comment.commentId <= 0L) {
            _events.trySend(TopicDetailUiEvent.ShowMessage("回复正在同步楼层信息，请稍后再试"))
            return
        }
        viewModelScope.launch {
            val uid = container.sessionRepository.sessionState.value?.uid ?: 0L
            val editDraft = container.draftRepository.getDraft(uid, EditorDraft.editReplyKey(comment.commentId))
            val initialText = editDraft?.markdownContent?.takeIf { it.isNotBlank() } ?: comment.markdownBody

            internalState.update { state ->
                state.copy(
                    replyComposer = ReplyComposerUiState(
                        isSheetOpen = true,
                        editingComment = comment,
                        quotedContext = null,
                        activeTab = ReplyEditorTab.WRITE,
                        draftMarkdown = initialText,
                        composerRevision = state.replyComposer.composerRevision + 1,
                    )
                )
            }
        }
    }

    fun closeReplySheet() {
        internalState.update { state ->
            val editingWasActive = state.replyComposer.isEditMode
            state.copy(
                replyComposer = state.replyComposer.copy(
                    isSheetOpen = false,
                    showStickerPicker = false,
                )
            ).also {
                if (editingWasActive) {
                    restoreReplyDraft()
                }
            }
        }
    }

    fun clearQuotedContext() {
        val current = internalState.value.replyComposer
        val quote = current.quotedContext ?: return
        val prefix = "> @${quote.username} #${quote.floorNumber}\n\n"
        val updatedText = if (current.draftMarkdown.startsWith(prefix)) {
            current.draftMarkdown.removePrefix(prefix)
        } else {
            current.draftMarkdown
        }
        internalState.update { state ->
            state.copy(
                replyComposer = state.replyComposer.copy(
                    quotedContext = null,
                    draftMarkdown = updatedText,
                    composerRevision = state.replyComposer.composerRevision + 1,
                )
            )
        }
        scheduleReplyDraftSave(updatedText, editingCommentId = current.editingComment?.commentId)
    }

    fun setReplyEditorTab(tab: ReplyEditorTab) {
        internalState.update { state ->
            state.copy(
                replyComposer = state.replyComposer.copy(
                    activeTab = tab,
                    showStickerPicker = if (tab == ReplyEditorTab.PREVIEW) false else state.replyComposer.showStickerPicker,
                )
            )
        }
    }

    fun toggleReplyStickerPicker() {
        internalState.update { state ->
            state.copy(
                replyComposer = state.replyComposer.copy(
                    showStickerPicker = !state.replyComposer.showStickerPicker,
                    activeTab = ReplyEditorTab.WRITE,
                )
            )
        }
    }

    fun onReplyDraftChanged(markdown: String) {
        val editingId = internalState.value.replyComposer.editingComment?.commentId
        internalState.update { state ->
            state.copy(
                replyComposer = state.replyComposer.copy(draftMarkdown = markdown)
            )
        }
        scheduleReplyDraftSave(markdown, editingCommentId = editingId)
    }

    private fun scheduleReplyDraftSave(markdown: String, editingCommentId: Long?) {
        saveDraftJob?.cancel()
        saveDraftJob = viewModelScope.launch {
            delay(450)
            val uid = container.sessionRepository.sessionState.value?.uid ?: 0L
            val draftKey = if (editingCommentId != null) {
                EditorDraft.editReplyKey(editingCommentId)
            } else {
                EditorDraft.replyKey(topicId)
            }
            if (markdown.isBlank()) {
                container.draftRepository.deleteDraft(uid, draftKey)
            } else {
                container.draftRepository.saveDraft(
                    EditorDraft(
                        draftKey = draftKey,
                        accountUid = uid,
                        markdownContent = markdown,
                        categorySlug = uiState.value.pageData?.categorySlug ?: "daily",
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    fun submitReply() {
        if (!ensureAuthenticatedForAction()) return
        val composer = internalState.value.replyComposer
        val content = composer.draftMarkdown.trim()
        if (content.isEmpty()) {
            _events.trySend(TopicDetailUiEvent.ShowMessage("请输入回复内容"))
            return
        }
        if (composer.isSubmitting) return

        viewModelScope.launch {
            saveDraftJob?.cancel()
            internalState.update { state ->
                state.copy(replyComposer = state.replyComposer.copy(isSubmitting = true))
            }

            val uid = container.sessionRepository.sessionState.value?.uid ?: 0L
            val editingComment = composer.editingComment

            val result = if (editingComment != null) {
                container.forumRepository.editReply(
                    commentId = editingComment.commentId,
                    markdownContent = content,
                )
            } else {
                container.forumRepository.postReply(
                    topicId = topicId,
                    markdownContent = content,
                )
            }

            result.fold(
                onSuccess = {
                    val draftKey = if (editingComment != null) {
                        EditorDraft.editReplyKey(editingComment.commentId)
                    } else {
                        EditorDraft.replyKey(topicId)
                    }
                    runCatching { container.draftRepository.deleteDraft(uid, draftKey) }

                    internalState.update { state ->
                        state.copy(
                            replyComposer = ReplyComposerUiState(isSheetOpen = false),
                        )
                    }

                    if (editingComment != null) {
                        val targetEditPage = internalState.value.pagesCache.entries
                            .firstOrNull { (_, page) -> page.replies.any { it.commentId == editingComment.commentId } }
                            ?.key
                            ?: internalState.value.currentPage
                        updateCommentAcrossCaches(editingComment.commentId) {
                            it.copy(markdownBody = content)
                        }
                        _events.trySend(TopicDetailUiEvent.ShowMessage("回复修改已保存"))
                        syncEditedReplyPage(
                            targetPage = targetEditPage,
                            commentId = editingComment.commentId,
                            expectedMarkdown = content,
                            preEditMarkdown = editingComment.markdownBody,
                        )
                        restoreReplyDraft()
                    } else {
                        _events.trySend(TopicDetailUiEvent.ShowMessage("回复已发布"))
                        val session = container.sessionRepository.sessionState.value
                        val snapshotState = internalState.value
                        val snapshotUi = uiState.value
                        val basePage = snapshotUi.pageData
                            ?: snapshotState.pagesCache.values.firstOrNull { !it.isOfflineSnapshot }
                        val currentTotalPages = snapshotUi.totalPages.coerceAtLeast(1)
                        val maxCachedFloor = snapshotState.pagesCache.values
                            .flatMap { it.replies }
                            .maxOfOrNull { it.floorNumber }
                            ?: 0
                        val nextFloor = maxOf(snapshotUi.estimatedTotalFloors, maxCachedFloor) + 1
                        val expectedTailPage = maxOf(
                            currentTotalPages,
                            TopicDetailPage.pageForFloor(nextFloor),
                        )
                        val previousReplyIds = snapshotState.pagesCache
                            .values
                            .flatMap { it.replies }
                            .mapNotNull { it.commentId.takeIf { id -> id > 0L } }
                            .toSet()
                        val isViewerOp = basePage?.author?.uid != null && basePage.author.uid == uid

                        val optimisticComment = FloorComment(
                            commentId = -(System.nanoTime().coerceAtLeast(1L)),
                            floorNumber = nextFloor,
                            author = FloorAuthor(
                                uid = uid,
                                username = session?.username?.ifBlank { null } ?: "我",
                                avatarUrl = session?.avatarUrl.orEmpty(),
                                isTopicAuthor = isViewerOp,
                                level = session?.level,
                            ),
                            createdAtIso = "刚刚",
                            markdownBody = content,
                        )

                        val hadExistingTailPage = snapshotState.pagesCache.containsKey(expectedTailPage) &&
                            snapshotState.pagesCache[expectedTailPage]?.isOfflineSnapshot != true

                        _visibleFloor.value = nextFloor
                        highestVisitedFloorState = maxOf(highestVisitedFloorState, nextFloor)

                        internalState.update { state ->
                            val cleanedPages = state.pagesCache.mapValues { (_, page) ->
                                val stripped = page.replies.filter { it.commentId > 0L }
                                val bumpTotalPages = maxOf(page.totalPages, expectedTailPage)
                                if (stripped.size != page.replies.size || bumpTotalPages != page.totalPages) {
                                    page.copy(replies = stripped, totalPages = bumpTotalPages)
                                } else {
                                    page
                                }
                            }
                            val existingTail = cleanedPages[expectedTailPage]?.takeIf { !it.isOfflineSnapshot }
                            val updatedTail = when {
                                existingTail != null -> existingTail.copy(
                                    totalPages = maxOf(existingTail.totalPages, expectedTailPage),
                                    replies = existingTail.replies + optimisticComment,
                                )
                                basePage != null -> basePage.copy(
                                    currentPage = expectedTailPage,
                                    totalPages = expectedTailPage,
                                    openingFloor = if (expectedTailPage == 1) {
                                        state.cachedOpeningFloor ?: basePage.openingFloor
                                    } else {
                                        null
                                    },
                                    replies = listOf(optimisticComment),
                                    isOfflineSnapshot = false,
                                )
                                else -> null
                            }
                            val nextPagesCache = if (updatedTail != null) {
                                cleanedPages + (expectedTailPage to updatedTail)
                            } else {
                                cleanedPages
                            }
                            val contiguous = expectedTailPage <= state.loadedEndPage + 1
                            state.copy(
                                isPageSwitching = true,
                                currentPage = expectedTailPage,
                                loadedStartPage = if (contiguous) state.loadedStartPage else expectedTailPage,
                                loadedEndPage = if (contiguous) {
                                    maxOf(state.loadedEndPage, expectedTailPage)
                                } else {
                                    expectedTailPage
                                },
                                pagesCache = nextPagesCache,
                                opOnly = if (isViewerOp) state.opOnly else false,
                                isOpExpandedOnSubPage = false,
                                currentVisibleFloor = nextFloor,
                                highestVisitedFloor = maxOf(state.highestVisitedFloor, nextFloor),
                                pendingScrollFloor = nextFloor,
                            )
                        }

                        runCatching {
                            radarRepository?.onTopicFloorSeen(
                                topicId = topicId,
                                maxSeenFloor = nextFloor,
                                totalPages = expectedTailPage,
                            )
                        }

                        syncTailPageAfterPostReply(
                            expectedPage = expectedTailPage,
                            hadExistingTargetPage = hadExistingTailPage,
                            optimisticComment = optimisticComment,
                            previousReplyIds = previousReplyIds,
                            sessionUid = uid,
                        )
                    }
                },
                onFailure = { err ->
                    internalState.update { state ->
                        state.copy(replyComposer = state.replyComposer.copy(isSubmitting = false))
                    }
                    handleActionFailure(
                        err,
                        fallbackMessage = if (editingComment != null) "修改回复失败" else "发送回复失败",
                    )
                },
            )
        }
    }

    private suspend fun syncEditedReplyPage(
        targetPage: Int,
        commentId: Long,
        expectedMarkdown: String,
        preEditMarkdown: String,
    ) {
        val safePage = targetPage.coerceAtLeast(1)
        val includeBlocked = container.settingsRepository.settings.value.includeBlockedTopics
        var detail = container.forumRepository.getTopicDetail(
            topicId = topicId,
            page = safePage,
            includeBlocked = includeBlocked,
        ).getOrNull() ?: return

        val fetchedBeforeRetry = detail.replies.firstOrNull { it.commentId == commentId }
        if (fetchedBeforeRetry != null &&
            fetchedBeforeRetry.markdownBody.trim() == preEditMarkdown.trim() &&
            preEditMarkdown.trim() != expectedMarkdown.trim()
        ) {
            delay(450)
            container.forumRepository.getTopicDetail(
                topicId = topicId,
                page = safePage,
                includeBlocked = includeBlocked,
            ).getOrNull()?.let { retried ->
                detail = retried
            }
        }

        internalState.update { state ->
            val updatedOpening = detail.openingFloor ?: state.cachedOpeningFloor
            val sanitizedReplies = detail.replies.map { reply ->
                if (reply.commentId == commentId &&
                    reply.markdownBody.trim() == preEditMarkdown.trim() &&
                    preEditMarkdown.trim() != expectedMarkdown.trim()
                ) {
                    reply.copy(markdownBody = expectedMarkdown)
                } else {
                    reply
                }
            }
            val mergedDetail = detail.copy(
                openingFloor = if (detail.openingFloor == null && updatedOpening != null) {
                    updatedOpening
                } else {
                    detail.openingFloor
                },
                replies = sanitizedReplies,
            )
            state.copy(
                pagesCache = state.pagesCache + (safePage to mergedDetail),
                cachedOpeningFloor = updatedOpening,
            )
        }
        val refreshedPage = internalState.value.pagesCache[safePage] ?: detail
        refreshOfflineSnapshotIfBookmarked(refreshedPage)
    }

    private suspend fun syncTailPageAfterPostReply(
        expectedPage: Int,
        hadExistingTargetPage: Boolean,
        optimisticComment: FloorComment,
        previousReplyIds: Set<Long>,
        sessionUid: Long,
    ) {
        val includeBlocked = container.settingsRepository.settings.value.includeBlockedTopics
        val firstResult = container.forumRepository.getTopicDetail(
            topicId = topicId,
            page = expectedPage,
            includeBlocked = includeBlocked,
        )
        var detail = firstResult.getOrNull()
        if (detail == null) {
            internalState.update { it.copy(isPageSwitching = false) }
            return
        }

        val initialServerTotal = detail.totalPages.coerceAtLeast(1)
        if (initialServerTotal != expectedPage) {
            container.forumRepository.getTopicDetail(
                topicId = topicId,
                page = initialServerTotal,
                includeBlocked = includeBlocked,
            ).getOrNull()?.let { redirected ->
                detail = redirected
            }
        }

        var matchedReply = findPostedReply(detail!!, previousReplyIds, sessionUid, optimisticComment)
        if (matchedReply == null) {
            delay(480)
            val targetRetryPage = detail!!.totalPages.coerceAtLeast(detail!!.currentPage).coerceAtLeast(1)
            container.forumRepository.getTopicDetail(
                topicId = topicId,
                page = targetRetryPage,
                includeBlocked = includeBlocked,
            ).getOrNull()?.let { retried ->
                detail = retried
                val nextTotal = retried.totalPages.coerceAtLeast(1)
                if (nextTotal > retried.currentPage) {
                    container.forumRepository.getTopicDetail(
                        topicId = topicId,
                        page = nextTotal,
                        includeBlocked = includeBlocked,
                    ).getOrNull()?.let { rolledOver ->
                        detail = rolledOver
                    }
                }
                matchedReply = findPostedReply(detail!!, previousReplyIds, sessionUid, optimisticComment)
            }
        }

        val committedDetail = detail!!
        val committedPage = committedDetail.currentPage
            .coerceAtLeast(committedDetail.totalPages)
            .coerceAtLeast(1)
        val landedFloor = matchedReply?.floorNumber ?: maxOf(
            optimisticComment.floorNumber,
            (committedDetail.replies.maxOfOrNull { it.floorNumber } ?: 0) + 1,
        )

        _visibleFloor.value = landedFloor
        highestVisitedFloorState = maxOf(highestVisitedFloorState, landedFloor)

        internalState.update { state ->
            val updatedOpening = committedDetail.openingFloor ?: state.cachedOpeningFloor
            val resolvedReplies = if (matchedReply != null) {
                committedDetail.replies
            } else {
                committedDetail.replies + optimisticComment.copy(floorNumber = landedFloor)
            }
            val mergedDetail = committedDetail.copy(
                openingFloor = if (committedDetail.openingFloor == null && updatedOpening != null) {
                    updatedOpening
                } else {
                    committedDetail.openingFloor
                },
                replies = resolvedReplies,
            )

            val cleanedPages = buildMap {
                for ((pageNum, cachedPage) in state.pagesCache) {
                    if (pageNum == expectedPage && committedPage != expectedPage && !hadExistingTargetPage) {
                        continue
                    }
                    val stripped = cachedPage.replies.filter { it.commentId > 0L }
                    put(
                        pageNum,
                        cachedPage.copy(
                            totalPages = maxOf(cachedPage.totalPages, committedDetail.totalPages),
                            replies = stripped,
                        ),
                    )
                }
                put(committedPage, mergedDetail)
            }

            val maxLoadedAfterRemoval = cleanedPages.keys
                .filter { it >= state.loadedStartPage && it <= committedPage }
                .maxOrNull()
                ?: committedPage
            val contiguous = committedPage <= maxLoadedAfterRemoval + 1 &&
                committedPage >= state.loadedStartPage
            val newStart = if (contiguous) minOf(state.loadedStartPage, committedPage) else committedPage
            val newEnd = if (contiguous) maxOf(state.loadedEndPage.coerceAtMost(committedPage), committedPage) else committedPage

            state.copy(
                isPageSwitching = false,
                currentPage = committedPage,
                loadedStartPage = newStart,
                loadedEndPage = newEnd,
                pagesCache = cleanedPages,
                cachedOpeningFloor = updatedOpening,
                accessError = null,
                currentVisibleFloor = landedFloor,
                highestVisitedFloor = maxOf(state.highestVisitedFloor, landedFloor),
                pendingScrollFloor = landedFloor,
            )
        }

        if (landedFloor > 0) {
            triggerFloorHighlight(landedFloor)
        }

        val loadedPage = internalState.value.pagesCache[committedPage] ?: committedDetail
        scheduleHistorySave(
            page = loadedPage,
            floor = landedFloor,
            immediate = true,
        )
        refreshOfflineSnapshotIfBookmarked(loadedPage)
        notifyTailPageSeenIfNeeded(loadedPage)
    }

    private fun findPostedReply(
        detail: TopicDetailPage,
        previousReplyIds: Set<Long>,
        sessionUid: Long,
        optimisticComment: FloorComment,
    ): FloorComment? {
        val candidates = detail.replies.filter { reply ->
            reply.commentId > 0L && reply.commentId !in previousReplyIds
        }
        if (candidates.isEmpty()) return null
        val bySession = if (sessionUid > 0L) {
            candidates.filter { it.author.uid == sessionUid }
        } else {
            emptyList()
        }
        val pool = bySession.ifEmpty { candidates }
        val expectedNormalized = optimisticComment.markdownBody.replace(Regex("\\s+"), "")
        val expectedImageUrls = Regex("!\\[[^\\]]*]\\(([^)\\s]+)").findAll(optimisticComment.markdownBody)
            .map { it.groupValues[1] }
            .toList()

        return pool.lastOrNull { reply ->
            reply.markdownBody.replace(Regex("\\s+"), "") == expectedNormalized
        } ?: pool.lastOrNull { reply ->
            expectedImageUrls.isNotEmpty() &&
                expectedImageUrls.all { url -> reply.markdownBody.contains(url) }
        } ?: bySession.lastOrNull { reply ->
            reply.floorNumber >= optimisticComment.floorNumber
        }
    }

    // ── NodeImage Photo Upload ────────────────────────────────────────────────

    fun onReplyPhotoReadError(message: String) {
        _events.trySend(TopicDetailUiEvent.ShowMessage(message))
    }

    fun onPhotoSelectedForReply(bytes: ByteArray, mimeType: String, fileName: String? = null) {
        val apiKey = container.settingsRepository.settings.value.nodeImageApiKey.trim()
        if (apiKey.isEmpty()) {
            pendingImageUpload = PendingUploadPayload(
                bytes = bytes,
                mimeType = mimeType,
                fileName = fileName,
            )
            internalState.update { state ->
                state.copy(replyComposer = state.replyComposer.copy(showApiKeyDialog = true))
            }
            return
        }
        performReplyImageUpload(bytes = bytes, mimeType = mimeType, fileName = fileName)
    }

    fun dismissApiKeyDialog() {
        pendingImageUpload = null
        internalState.update { state ->
            state.copy(replyComposer = state.replyComposer.copy(showApiKeyDialog = false))
        }
    }

    fun saveNodeImageApiKeyAndContinue(rawKey: String) {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            _events.trySend(TopicDetailUiEvent.ShowMessage("请输入有效的 NodeImage API Key"))
            return
        }
        viewModelScope.launch {
            container.settingsRepository.setNodeImageApiKey(trimmed)
            internalState.update { state ->
                state.copy(replyComposer = state.replyComposer.copy(showApiKeyDialog = false))
            }
            val pending = pendingImageUpload
            pendingImageUpload = null
            if (pending != null) {
                performReplyImageUpload(
                    bytes = pending.bytes,
                    mimeType = pending.mimeType,
                    fileName = pending.fileName,
                )
            }
        }
    }

    private fun performReplyImageUpload(
        bytes: ByteArray,
        mimeType: String,
        fileName: String? = null,
    ) {
        viewModelScope.launch {
            internalState.update { state ->
                state.copy(replyComposer = state.replyComposer.copy(isUploadingImage = true))
            }
            val result = container.imageHostRepository.uploadImage(
                bytes = bytes,
                mimeType = mimeType,
                fileName = fileName,
            )
            internalState.update { state ->
                state.copy(replyComposer = state.replyComposer.copy(isUploadingImage = false))
            }
            result.fold(
                onSuccess = { imageUrl ->
                    val snippet = "\n![image]($imageUrl)\n"
                    val composer = internalState.value.replyComposer
                    if (composer.isSheetOpen) {
                        _events.trySend(TopicDetailUiEvent.InsertUploadedMarkdown(snippet))
                    } else {
                        val updatedDraft = buildString {
                            append(composer.draftMarkdown)
                            if (composer.draftMarkdown.isNotEmpty() && !composer.draftMarkdown.endsWith("\n")) {
                                append('\n')
                            }
                            append(snippet.trimStart('\n'))
                        }
                        internalState.update { state ->
                            state.copy(
                                replyComposer = state.replyComposer.copy(
                                    draftMarkdown = updatedDraft,
                                    composerRevision = state.replyComposer.composerRevision + 1,
                                )
                            )
                        }
                        scheduleReplyDraftSave(
                            markdown = updatedDraft,
                            editingCommentId = composer.editingComment?.commentId,
                        )
                    }
                    _events.trySend(TopicDetailUiEvent.ShowMessage("图片上传成功"))
                },
                onFailure = { err ->
                    _events.trySend(
                        TopicDetailUiEvent.ShowMessage(err.message ?: "图片上传失败，请检查 NodeImage API Key")
                    )
                },
            )
        }
    }

    // ── Auth & Error Helpers ──────────────────────────────────────────────────

    private fun ensureAuthenticatedForAction(): Boolean {
        val session = container.sessionRepository.sessionState.value
        if (session == null) {
            _events.trySend(TopicDetailUiEvent.ShowMessage("请先登录 NodeSeek 账号"))
            _events.trySend(TopicDetailUiEvent.RequestWebViewAuth("https://www.nodeseek.com/signIn.html"))
            return false
        }
        return true
    }

    private fun handleActionFailure(error: Throwable, fallbackMessage: String) {
        val apiErr = (error as? NodeSeekApiException) ?: (error.cause as? NodeSeekApiException)
        if (apiErr?.isHighRiskAction == true) {
            _events.trySend(
                TopicDetailUiEvent.ShowMessage(
                    message = apiErr.message?.takeIf { it.isNotBlank() }
                        ?: "当前操作触发了社区高风险风控保护，可在官方网页操作",
                    actionLabel = "网页操作",
                    webViewUrl = "https://www.nodeseek.com/post-$topicId-1#manual",
                )
            )
            return
        }

        when (val classified = classifyError(error)) {
            TopicAccessUiError.SignInRequired -> {
                _events.trySend(TopicDetailUiEvent.ShowMessage("登录状态已失效，请重新验证登录"))
                _events.trySend(TopicDetailUiEvent.RequestWebViewAuth("https://www.nodeseek.com/signIn.html"))
            }
            TopicAccessUiError.CloudflareChallengeRequired -> {
                _events.trySend(TopicDetailUiEvent.ShowMessage("需要完成浏览器安全验证后继续"))
                _events.trySend(TopicDetailUiEvent.RequestWebViewAuth("https://www.nodeseek.com/post-$topicId-1"))
            }
            is TopicAccessUiError.LevelRequired -> {
                _events.trySend(
                    TopicDetailUiEvent.ShowMessage(
                        classified.minimumLevel?.let { "需要达到 Lv.$it 才能执行此操作" } ?: "账号等级不足"
                    )
                )
            }
            TopicAccessUiError.PrivateTopic -> {
                _events.trySend(TopicDetailUiEvent.ShowMessage("该主题为私有帖，无法执行此操作"))
            }
            is TopicAccessUiError.NetworkOrGeneric -> {
                _events.trySend(
                    TopicDetailUiEvent.ShowMessage(classified.message.ifBlank { fallbackMessage })
                )
            }
        }
    }

    private fun classifyError(error: Throwable): TopicAccessUiError {
        val candidates = listOfNotNull(error, error.cause)
        for (t in candidates) {
            when (t) {
                is TopicAccessException.SignInRequired -> return TopicAccessUiError.SignInRequired
                is TopicAccessException.CloudflareChallengeRequired -> {
                    return TopicAccessUiError.CloudflareChallengeRequired
                }
                is TopicAccessException.LevelRequired -> {
                    return TopicAccessUiError.LevelRequired(t.minimumLevel)
                }
                is TopicAccessException.PrivateTopic -> return TopicAccessUiError.PrivateTopic
            }
            val simpleName = t::class.simpleName.orEmpty()
            if (simpleName.contains("CloudflareChallenge", ignoreCase = true)) {
                return TopicAccessUiError.CloudflareChallengeRequired
            }
            if (simpleName.contains("SignInRequired", ignoreCase = true)) {
                return TopicAccessUiError.SignInRequired
            }
            if (simpleName.contains("PrivatePost", ignoreCase = true)) {
                return TopicAccessUiError.PrivateTopic
            }
        }
        if (container.sessionRepository.challengeRequired.value) {
            return TopicAccessUiError.CloudflareChallengeRequired
        }
        return TopicAccessUiError.NetworkOrGeneric(
            error.message?.takeIf { it.isNotBlank() } ?: "网络连接异常，请检查网络后重试"
        )
    }

    private fun TopicDetailPage.toTopicSummary(isBookmarked: Boolean): TopicSummary = TopicSummary(
        id = topicId,
        title = title,
        categorySlug = categorySlug,
        categoryName = categoryName,
        authorId = author.uid,
        authorName = author.username,
        authorAvatarUrl = author.avatarUrl,
        viewCount = viewCount,
        replyCount = estimatedTotalFloors,
        lastActivityIso = replies.lastOrNull()?.createdAtIso
            ?: openingFloor?.createdAtIso
            ?: createdAtIso.orEmpty(),
        requiredViewLevel = requiredViewLevel.takeIf { it > 0 },
        isRead = true,
        lastReadFloor = _visibleFloor.value,
        isLocked = isLocked,
        isBookmarked = isBookmarked,
        authorLevel = author.level,
    )

    companion object {
        fun factory(
            container: AppContainer,
            topicId: Long,
            initialFloor: Int = 0,
            titleHint: String = "",
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return TopicDetailViewModel(
                    container = container,
                    topicId = topicId,
                    initialFloor = initialFloor,
                    titleHint = titleHint,
                    radarRepository = container.radarRepository,
                ) as T
            }
        }
    }
}
