package com.nodeseek.app.ui.topic

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.carpool.CarpoolStatusUpdater
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.EditorDraft
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.FloorComment
import com.nodeseek.app.domain.model.PollDetail
import com.nodeseek.app.domain.model.ReactionType
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.StardustTipTarget
import com.nodeseek.app.domain.model.TopicAccessException
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.radar.RadarRepository
import com.nodeseek.app.domain.radar.TrackedTopic
import com.nodeseek.app.domain.repository.PollAndStardustRepository
import com.nodeseek.app.domain.trade.TradePrivacyOptions
import com.nodeseek.app.domain.trade.TradePrivacySanitizer
import com.nodeseek.app.domain.trade.TradeTitleFormatter
import com.nodeseek.app.domain.trust.TrustRiskEvaluator
import com.nodeseek.app.domain.valuation.InferredListingPricing
import com.nodeseek.app.domain.valuation.MarketSearchStatus
import com.nodeseek.app.domain.valuation.TradeListingPriceParser
import com.nodeseek.app.domain.valuation.TradeMarketPremiumAnalyzer
import com.nodeseek.app.domain.valuation.TradeMarketReferenceRepository
import com.nodeseek.app.domain.valuation.TradeMarketReferenceState
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.domain.valuation.VpsValuationEngine
import com.nodeseek.app.ui.components.NoOpNsAppWidgetController
import com.nodeseek.app.ui.components.NsAppWidgetController
import com.nodeseek.app.ui.components.PollCardUiState
import com.nodeseek.app.ui.components.StardustCardUiState
import com.nodeseek.app.ui.composer.ImagePrivacyBitmapCodec
import com.nodeseek.app.ui.composer.MosaicSupportStatus
import com.nodeseek.app.ui.composer.PendingComposerImage
import com.nodeseek.app.ui.valuation.extractMarketQueryWithContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    val pendingPrivacyImage: PendingComposerImage? = null,
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
    val stardustTipDialog: StardustTipDialogUiState? = null,
    val isTrackingReplies: Boolean = false,
    val trackedBaselineFloor: Int = -1,
    val isMutatingTopic: Boolean = false,
    val marketReference: TradeMarketReferenceState? = null,
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
    val stardustTipDialog: StardustTipDialogUiState? = null,
    val isMutatingTopic: Boolean = false,
    val marketReference: TradeMarketReferenceState? = null,
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
    private val pollAndStardustRepositoryOverride: PollAndStardustRepository? = null,
    private val tradeMarketReferenceRepository: TradeMarketReferenceRepository? = null,
) : ViewModel() {

    private val initialStartPage = if (initialFloor > 0) TopicDetailPage.pageForFloor(initialFloor) else 1

    private val stateLock = Any()
    private val _internalState = MutableStateFlow(
        InternalReaderState(
            currentPage = initialStartPage,
            loadedStartPage = initialStartPage,
            loadedEndPage = initialStartPage,
            currentVisibleFloor = initialFloor.coerceAtLeast(0),
            highestVisitedFloor = initialFloor.coerceAtLeast(0),
        )
    )
    private val internalState = SyncedInternalState()

    private inner class SyncedInternalState {
        val value: InternalReaderState
            get() = _internalState.value

        fun update(transform: (InternalReaderState) -> InternalReaderState) {
            synchronized(stateLock) {
                val next = transform(_internalState.value)
                _internalState.value = next
                _uiState.value = buildUiState(state = next)
            }
        }
    }

    private val _visibleFloor = MutableStateFlow(initialFloor.coerceAtLeast(0))
    val visibleFloor: StateFlow<Int> = _visibleFloor.asStateFlow()
    private var highestVisitedFloorState: Int = initialFloor.coerceAtLeast(0)

    private val _events = Channel<TopicDetailUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var saveDraftJob: Job? = null
    @Volatile
    private var pendingDraftSave: Pair<String, Long?>? = null
    private var historyRecordJob: Job? = null
    private var highlightClearJob: Job? = null
    private var pageLoadJob: Job? = null
    private var appendNextPageJob: Job? = null
    private val isSubmittingReplyGuard = AtomicBoolean(false)
    private val submitReplySeq = AtomicLong(0L)
    private val activeTailSyncSeq = AtomicLong(0L)
    private var prependPreviousPageJob: Job? = null
    private var userResolveJob: Job? = null
    private var marketRefJob: Job? = null
    private var marketRefRequestSeq: Long = 0L
    private var lastAnalyzedMarketSignature: String? = null
    private var hasUserCustomMarketQuery: Boolean = false
    private val autoRemoteTriggeredMarketQueries: MutableSet<String> = mutableSetOf()
    private var pageWindowGeneration: Long = 0L
    private var pendingImageUpload: PendingUploadPayload? = null
    private var lastHandledRefreshTrigger: Long = 0L
    private val pollEpoch = AtomicLong(0L)
    private val pollSessionLock = Any()
    private var lastObservedPollSessionUid: Long? = container.sessionRepository.sessionState.value?.uid
    private val pollLoadJobs = ConcurrentHashMap<Long, Job>()
    private val pollCardStates = ConcurrentHashMap<Long, MutableStateFlow<PollCardUiState>>()
    private val stardustCardStates = ConcurrentHashMap<String, MutableStateFlow<StardustCardUiState>>()

    val nsAppWidgetController: NsAppWidgetController = object : NsAppWidgetController {
        override val currentViewerUid: Long?
            get() = container.sessionRepository.sessionState.value?.uid

        override fun pollStateFor(voteId: Long): StateFlow<PollCardUiState> =
            pollStateFlow(voteId).asStateFlow()

        override fun stardustStateFor(widgetKey: String): StateFlow<StardustCardUiState> =
            stardustStateFlow(widgetKey).asStateFlow()

        override fun ensureVoteLoaded(voteId: Long) {
            loadVoteInternal(voteId = voteId, force = false)
        }

        override fun reloadVote(voteId: Long) {
            loadVoteInternal(voteId = voteId, force = true)
        }

        override fun togglePollOption(voteId: Long, optionId: Long, isMultiple: Boolean) {
            synchronized(pollSessionLock) {
                val flow = pollStateFlow(voteId)
                val current = flow.value
                val poll = current.poll ?: return
                if (current.isSubmitting || poll.hasCurrentUserVoted || poll.isLocked) return
                val nextSelected = if (isMultiple) {
                    if (optionId in current.selectedOptionIds) {
                        current.selectedOptionIds - optionId
                    } else {
                        current.selectedOptionIds + optionId
                    }
                } else {
                    if (optionId in current.selectedOptionIds) emptySet() else setOf(optionId)
                }
                flow.value = current.copy(selectedOptionIds = nextSelected, errorMessage = null)
            }
        }

        override fun submitVote(voteId: Long) {
            if (!ensureAuthenticatedForAction()) return
            val flow = pollStateFlow(voteId)
            var epochAtSubmit = 0L
            var chosenIds: List<Long>? = null
            synchronized(pollSessionLock) {
                epochAtSubmit = pollEpoch.get()
                val current = flow.value
                if (!current.isSubmitting && current.selectedOptionIds.isNotEmpty()) {
                    chosenIds = current.selectedOptionIds.toList()
                    flow.value = current.copy(isSubmitting = true, errorMessage = null)
                }
            }
            val idsToCast = chosenIds ?: return
            viewModelScope.launch {
                try {
                    resolvePollAndStardustRepo().castVote(voteId = voteId, voteItemIds = idsToCast).fold(
                        onSuccess = { updated ->
                            val applied = synchronized(pollSessionLock) {
                                if (pollEpoch.get() == epochAtSubmit) {
                                    flow.value = flow.value.copy(
                                        isSubmitting = false,
                                        poll = updated,
                                        selectedOptionIds = emptySet(),
                                        errorMessage = null,
                                    )
                                    true
                                } else {
                                    false
                                }
                            }
                            if (applied) {
                                _events.trySend(TopicDetailUiEvent.ShowMessage("投票已提交"))
                            }
                        },
                        onFailure = { err ->
                            val applied = synchronized(pollSessionLock) {
                                if (pollEpoch.get() == epochAtSubmit) {
                                    flow.value = flow.value.copy(
                                        isSubmitting = false,
                                        errorMessage = err.message?.takeIf { m -> m.isNotBlank() } ?: "提交投票失败",
                                    )
                                    true
                                } else {
                                    false
                                }
                            }
                            if (applied) {
                                handleActionFailure(err, "提交投票失败")
                            }
                        },
                    )
                } finally {
                    synchronized(pollSessionLock) {
                        if (pollEpoch.get() == epochAtSubmit && flow.value.isSubmitting) {
                            flow.value = flow.value.copy(isSubmitting = false)
                        }
                    }
                }
            }
        }

        override fun lockVote(voteId: Long) {
            if (!ensureAuthenticatedForAction()) return
            val flow = pollStateFlow(voteId)
            var epochAtSubmit = 0L
            var existingPollSnapshot: PollDetail? = null
            synchronized(pollSessionLock) {
                epochAtSubmit = pollEpoch.get()
                val current = flow.value
                val existingPoll = current.poll
                val viewerUid = container.sessionRepository.sessionState.value?.uid
                if (existingPoll != null &&
                    viewerUid != null &&
                    viewerUid == existingPoll.creatorUid &&
                    !existingPoll.isLocked &&
                    !current.isLocking
                ) {
                    existingPollSnapshot = existingPoll
                    flow.value = current.copy(isLocking = true, errorMessage = null)
                }
            }
            val existingPoll = existingPollSnapshot ?: return
            viewModelScope.launch {
                try {
                    resolvePollAndStardustRepo().lockVote(voteId).fold(
                        onSuccess = { updated ->
                            val applied = synchronized(pollSessionLock) {
                                if (pollEpoch.get() == epochAtSubmit) {
                                    val merged = if (updated.options.isEmpty() && existingPoll.options.isNotEmpty()) {
                                        existingPoll.copy(isLocked = true)
                                    } else {
                                        updated
                                    }
                                    flow.value = flow.value.copy(
                                        isLocking = false,
                                        poll = merged,
                                        selectedOptionIds = emptySet(),
                                        errorMessage = null,
                                    )
                                    true
                                } else {
                                    false
                                }
                            }
                            if (applied) {
                                _events.trySend(TopicDetailUiEvent.ShowMessage("投票已锁定"))
                            }
                        },
                        onFailure = { err ->
                            val applied = synchronized(pollSessionLock) {
                                if (pollEpoch.get() == epochAtSubmit) {
                                    flow.value = flow.value.copy(
                                        isLocking = false,
                                        errorMessage = err.message?.takeIf { m -> m.isNotBlank() } ?: "锁定投票失败",
                                    )
                                    true
                                } else {
                                    false
                                }
                            }
                            if (applied) {
                                handleActionFailure(err, "锁定投票失败")
                            }
                        },
                    )
                } finally {
                    synchronized(pollSessionLock) {
                        if (pollEpoch.get() == epochAtSubmit && flow.value.isLocking) {
                            flow.value = flow.value.copy(isLocking = false)
                        }
                    }
                }
            }
        }

        override fun openStardustTip(target: StardustTipTarget) {
            this@TopicDetailViewModel.openStardustTip(target)
        }
    }

    private var stardustPrepareSeq: Long = 0L
    private var stardustPrepareJob: Job? = null
    private val cachedBookmarkedIds = MutableStateFlow(
        (container.bookmarkAndHistoryRepository.observeBookmarkedIds() as? StateFlow<Set<Long>>)?.value ?: emptySet()
    )
    private val cachedTrackedTopic = MutableStateFlow<TrackedTopic?>(
        (radarRepository?.observeTrackedTopic(topicId) as? StateFlow<TrackedTopic?>)?.value
    )

    private data class DerivedTopicSnapshot(
        val pageData: TopicDetailPage?,
        val openingFloor: FloorComment?,
        val totalPages: Int,
        val estimatedTotalFloors: Int,
        val canEditTopic: Boolean,
    )

    private fun deriveTopicSnapshot(
        state: InternalReaderState = internalState.value,
        session: AccountSession? = container.sessionRepository.sessionState.value,
    ): DerivedTopicSnapshot {
        val pageData = state.pagesCache[state.currentPage]
            ?: state.pagesCache[state.loadedStartPage]
            ?: state.pagesCache.values.firstOrNull()
        val openingFloor = pageData?.openingFloor ?: state.cachedOpeningFloor
        val totalPages = maxOf(
            pageData?.totalPages ?: 1,
            state.pagesCache.values.maxOfOrNull { it.totalPages } ?: 1,
        )
        val estimatedTotalFloors = resolveTotalFloors(state.pagesCache.values, totalPages)
        val canEditTopic = session != null && pageData != null && session.uid == pageData.author.uid
        return DerivedTopicSnapshot(
            pageData = pageData,
            openingFloor = openingFloor,
            totalPages = totalPages,
            estimatedTotalFloors = estimatedTotalFloors,
            canEditTopic = canEditTopic,
        )
    }

    private fun resolveIsBookmarked(
        state: InternalReaderState,
        pageData: TopicDetailPage?,
        localBookmarkedIds: Set<Long> = cachedBookmarkedIds.value,
    ): Boolean {
        val baseBookmarked = localBookmarkedIds.contains(topicId) || (pageData?.isBookmarked == true)
        return state.optimisticBookmarked ?: baseBookmarked
    }

    private fun buildUiState(
        state: InternalReaderState = _internalState.value,
        session: AccountSession? = container.sessionRepository.sessionState.value,
        settings: AppSettings = container.settingsRepository.settings.value,
        localBookmarkedIds: Set<Long> = cachedBookmarkedIds.value,
        trackedTopic: TrackedTopic? = cachedTrackedTopic.value,
    ): TopicDetailUiState {
        val derived = deriveTopicSnapshot(state = state, session = session)
        val pageData = derived.pageData
        val openingFloor = derived.openingFloor
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

        val isBookmarked = resolveIsBookmarked(
            state = state,
            pageData = pageData,
            localBookmarkedIds = localBookmarkedIds,
        )
        val effectiveBookmarkCount = ((pageData?.bookmarkCount ?: 0) + state.bookmarkCountDelta).coerceAtLeast(0)

        val totalPages = derived.totalPages
        val estimatedTotalFloors = derived.estimatedTotalFloors

        return TopicDetailUiState(
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
            stardustTipDialog = state.stardustTipDialog,
            isTrackingReplies = trackedTopic?.isActive == true,
            trackedBaselineFloor = trackedTopic?.lastKnownFloor ?: -1,
            isMutatingTopic = state.isMutatingTopic,
            marketReference = state.marketReference,
        )
    }

    private val _uiState = MutableStateFlow(buildUiState())
    val uiState: StateFlow<TopicDetailUiState> = _uiState.asStateFlow()

    private fun refreshUiState() {
        synchronized(stateLock) {
            _uiState.value = buildUiState()
        }
    }

    init {
        viewModelScope.launch {
            container.bookmarkAndHistoryRepository.observeBookmarkedIds().collect { ids ->
                cachedBookmarkedIds.value = ids
                refreshUiState()
            }
        }
        if (radarRepository != null) {
            viewModelScope.launch {
                radarRepository.observeTrackedTopic(topicId).collect { tracked ->
                    cachedTrackedTopic.value = tracked
                    refreshUiState()
                }
            }
        }
        viewModelScope.launch {
            container.sessionRepository.sessionState.collect {
                refreshUiState()
                syncPollSessionChangeIfNeeded()
            }
        }
        viewModelScope.launch {
            container.settingsRepository.settings.collect {
                refreshUiState()
            }
        }
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
                            val resolvedAuthor = if (subPage.author.uid > 0L) subPage.author else page1.author
                            updatedCache[activePage] = subPage.copy(
                                title = page1.title.ifBlank { subPage.title },
                                categorySlug = subPage.categorySlug.ifBlank { page1.categorySlug },
                                categoryName = subPage.categoryName.ifBlank { page1.categoryName },
                                author = resolvedAuthor,
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
                refreshOfflineSnapshotIfBookmarked(page1)
                maybeAnalyzeLocalMarketReference(page1)
            }
        }
    }

    fun refreshCurrentPage() {
        pageLoadJob?.cancel()
        appendNextPageJob?.cancel()
        prependPreviousPageJob?.cancel()
        pageWindowGeneration++
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

        val generation = pageWindowGeneration
        appendNextPageJob?.cancel()
        appendNextPageJob = viewModelScope.launch {
            internalState.update { it.copy(isAppendingNextPage = true) }
            val includeBlocked = container.settingsRepository.settings.value.includeBlockedTopics
            val result = container.forumRepository.getTopicDetail(
                topicId = topicId,
                page = nextPage,
                includeBlocked = includeBlocked,
            )
            if (generation != pageWindowGeneration) return@launch
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
                val updatedOpening = cachedPrev.openingFloor ?: s.cachedOpeningFloor
                s.copy(
                    loadedStartPage = minOf(s.loadedStartPage, prevPage),
                    cachedOpeningFloor = updatedOpening,
                )
            }
            return
        }

        val generation = pageWindowGeneration
        prependPreviousPageJob?.cancel()
        prependPreviousPageJob = viewModelScope.launch {
            internalState.update { it.copy(isPrependingPreviousPage = true) }
            val includeBlocked = container.settingsRepository.settings.value.includeBlockedTopics
            val result = container.forumRepository.getTopicDetail(
                topicId = topicId,
                page = prevPage,
                includeBlocked = includeBlocked,
            )
            if (generation != pageWindowGeneration) return@launch
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
        val state = internalState.value
        val snapshot = deriveTopicSnapshot(state)
        val pageData = snapshot.pageData
        val totalPages = snapshot.totalPages.coerceAtLeast(1)
        val isTrackingReplies = cachedTrackedTopic.value?.isActive == true

        viewModelScope.launch {
            if (isTrackingReplies) {
                repo.untrackTopic(topicId)
                _events.trySend(TopicDetailUiEvent.ShowMessage("已关闭此帖的新回复追踪"))
                return@launch
            }

            val onTailPage = state.currentPage >= totalPages &&
                pageData != null &&
                !pageData.isOfflineSnapshot
            val currentMaxFloor = if (onTailPage) {
                pageData?.replies?.maxOfOrNull { it.floorNumber } ?: 0
            } else {
                -1
            }

            repo.trackTopic(
                topicId = topicId,
                title = pageData?.title ?: titleHint.ifBlank { "主题 #$topicId" },
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

        if (!preserveWindowIfContiguous) {
            appendNextPageJob?.cancel()
            prependPreviousPageJob?.cancel()
            pageWindowGeneration++
        }
        val generation = pageWindowGeneration
        pageLoadJob?.cancel()
        pageLoadJob = viewModelScope.launch {
            internalState.update { state ->
                state.copy(
                    isInitialLoading = isInitial && state.pagesCache.isEmpty(),
                    isRefreshing = isPullRefresh,
                    isPageSwitching = !isInitial && !isPullRefresh,
                    isAppendingNextPage = if (preserveWindowIfContiguous) state.isAppendingNextPage else false,
                    isPrependingPreviousPage = if (preserveWindowIfContiguous) state.isPrependingPreviousPage else false,
                    accessError = if (state.pagesCache.isEmpty()) null else state.accessError,
                )
            }

            val includeBlocked = container.settingsRepository.settings.value.includeBlockedTopics
            val result = container.forumRepository.getTopicDetail(
                topicId = topicId,
                page = safePage,
                includeBlocked = includeBlocked,
            )
            if (generation != pageWindowGeneration) return@launch
            syncPollSessionChangeIfNeeded()

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
                    maybeAnalyzeLocalMarketReference(loadedPage)
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
                        maybeAnalyzeLocalMarketReference(markedOffline, allowAutoRemote = false)
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
        // A programmatic jump/resume scroll is in flight: _visibleFloor already holds its target,
        // so ignore list-position reports (still showing the old position) until it lands.
        if (internalState.value.pendingScrollFloor != null) return
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
        val state = internalState.value
        val isCurrentlyBookmarked = resolveIsBookmarked(state, deriveTopicSnapshot(state).pageData)
        if (!isCurrentlyBookmarked || detail.isOfflineSnapshot) return
        // Only persist Page 1 with a valid opening floor so subpage loads never overwrite the offline OP snapshot
        if (detail.currentPage != 1) return
        val opening = internalState.value.cachedOpeningFloor ?: detail.openingFloor ?: return
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
        val state = internalState.value
        val page = deriveTopicSnapshot(state).pageData ?: return
        val targetBookmarked = !resolveIsBookmarked(state, page)
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
                val baseSnapshotPage = internalState.value.pagesCache[1]?.takeUnless { it.isOfflineSnapshot } ?: page
                val opening = internalState.value.cachedOpeningFloor
                    ?: baseSnapshotPage.openingFloor
                    ?: page.openingFloor
                val offlineSnapshot = baseSnapshotPage.copy(openingFloor = opening, isOfflineSnapshot = true)
                val summary = baseSnapshotPage.toTopicSummary(isBookmarked = true)

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
                var remoteFailed = false
                if (isSignedIn) {
                    container.bookmarkAndHistoryRepository.setTopicBookmarked(topicId, false)
                        .onFailure { err ->
                            remoteFailed = true
                            _events.trySend(
                                TopicDetailUiEvent.ShowMessage(
                                    err.message ?: "取消云端收藏失败，已移除本地收藏"
                                )
                            )
                        }
                }
                if (!remoteFailed) {
                    _events.trySend(TopicDetailUiEvent.ShowMessage("已取消收藏"))
                }
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

        // 2. Query userRepository.searchUsers(username) with exact-match guard (never fuzzy jump to wrong user)
        userResolveJob?.cancel()
        userResolveJob = viewModelScope.launch {
            container.userRepository.searchUsers(cleaned)
                .onSuccess { candidates ->
                    val exact = candidates.firstOrNull { it.username.equals(cleaned, ignoreCase = true) }
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
        if (!deriveTopicSnapshot().canEditTopic || comment.floorNumber <= 0) return
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
        val page = deriveTopicSnapshot().pageData
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
        flushReplyDraftNow()
        val editingWasActive = internalState.value.replyComposer.isEditMode
        internalState.update { state ->
            state.copy(
                replyComposer = state.replyComposer.copy(
                    isSheetOpen = false,
                    showStickerPicker = false,
                    editingComment = if (editingWasActive) null else state.replyComposer.editingComment,
                    draftMarkdown = if (editingWasActive) "" else state.replyComposer.draftMarkdown,
                    composerRevision = if (editingWasActive) {
                        state.replyComposer.composerRevision + 1
                    } else {
                        state.replyComposer.composerRevision
                    },
                )
            )
        }
        if (editingWasActive) {
            restoreReplyDraft()
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
        pendingDraftSave = markdown to editingCommentId
        saveDraftJob?.cancel()
        saveDraftJob = viewModelScope.launch {
            delay(450)
            pendingDraftSave = null
            persistReplyDraft(markdown = markdown, editingCommentId = editingCommentId)
        }
    }

    fun flushReplyDraftNow() {
        val pending = pendingDraftSave ?: return
        pendingDraftSave = null
        saveDraftJob?.cancel()
        nonCancellableDraftScope.launch {
            runCatching {
                persistReplyDraft(markdown = pending.first, editingCommentId = pending.second)
            }
        }
    }

    private suspend fun persistReplyDraft(markdown: String, editingCommentId: Long?) {
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
                    categorySlug = deriveTopicSnapshot().pageData?.categorySlug ?: "daily",
                    updatedAtMillis = System.currentTimeMillis(),
                )
            )
        }
    }

    override fun onCleared() {
        flushReplyDraftNow()
        val jobsToCancel = synchronized(pollSessionLock) {
            pollEpoch.incrementAndGet()
            val jobs = pollLoadJobs.values.toList()
            pollLoadJobs.clear()
            jobs
        }
        jobsToCancel.forEach { it.cancel() }
        super.onCleared()
    }

    fun submitReply() {
        if (!ensureAuthenticatedForAction()) return
        val precheck = internalState.value.replyComposer
        if (precheck.draftMarkdown.trim().isEmpty()) {
            _events.trySend(TopicDetailUiEvent.ShowMessage("请输入回复内容"))
            return
        }
        if (precheck.isSubmitting || !isSubmittingReplyGuard.compareAndSet(false, true)) return
        val mySeq = submitReplySeq.incrementAndGet()

        var launched = false
        try {
            var submittedContent = ""
            var editingCommentSnapshot: FloorComment? = null
            var canProceed = false
            internalState.update { state ->
                val cur = state.replyComposer
                val trimmed = cur.draftMarkdown.trim()
                if (cur.isSubmitting || trimmed.isEmpty()) {
                    state
                } else {
                    canProceed = true
                    submittedContent = trimmed
                    editingCommentSnapshot = cur.editingComment
                    state.copy(replyComposer = cur.copy(isSubmitting = true))
                }
            }
            if (!canProceed) return

            saveDraftJob?.cancel()
            pendingDraftSave = null

            val content = submittedContent
            val editingComment = editingCommentSnapshot

            viewModelScope.launch {
                try {
                    val uid = container.sessionRepository.sessionState.value?.uid ?: 0L

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
                            if (submitReplySeq.get() == mySeq) {
                                isSubmittingReplyGuard.set(false)
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
                                val editWindowGen = pageWindowGeneration
                                syncEditedReplyPage(
                                    targetPage = targetEditPage,
                                    commentId = editingComment.commentId,
                                    expectedMarkdown = content,
                                    preEditMarkdown = editingComment.markdownBody,
                                    expectedSeq = mySeq,
                                    expectedWindowGen = editWindowGen,
                                )
                                restoreReplyDraft()
                            } else {
                                _events.trySend(TopicDetailUiEvent.ShowMessage("回复已发布"))
                                val session = container.sessionRepository.sessionState.value
                                val snapshotState = internalState.value
                                val snapshotDerived = deriveTopicSnapshot(state = snapshotState, session = session)
                                val basePage = snapshotDerived.pageData
                                    ?: snapshotState.pagesCache.values.firstOrNull { !it.isOfflineSnapshot }
                                val currentTotalPages = snapshotDerived.totalPages.coerceAtLeast(1)
                                val maxCachedFloor = snapshotState.pagesCache.values
                                    .flatMap { it.replies }
                                    .maxOfOrNull { it.floorNumber }
                                    ?: 0
                                val nextFloor = maxOf(snapshotDerived.estimatedTotalFloors, maxCachedFloor) + 1
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

                                val tailSyncWindowGen = pageWindowGeneration
                                activeTailSyncSeq.set(mySeq)
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
                                    expectedSeq = mySeq,
                                    expectedWindowGen = tailSyncWindowGen,
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
                } finally {
                    if (submitReplySeq.get() == mySeq) {
                        isSubmittingReplyGuard.set(false)
                        internalState.update { state ->
                            if (state.replyComposer.isSubmitting) {
                                state.copy(replyComposer = state.replyComposer.copy(isSubmitting = false))
                            } else {
                                state
                            }
                        }
                    }
                }
            }
            launched = true
        } finally {
            if (!launched && submitReplySeq.get() == mySeq) {
                isSubmittingReplyGuard.set(false)
                internalState.update { state ->
                    if (state.replyComposer.isSubmitting) {
                        state.copy(replyComposer = state.replyComposer.copy(isSubmitting = false))
                    } else {
                        state
                    }
                }
            }
        }
    }

    private fun isTailSyncCurrent(expectedSeq: Long, expectedWindowGen: Long): Boolean =
        submitReplySeq.get() == expectedSeq && pageWindowGeneration == expectedWindowGen

    private fun clearPageSwitchingIfSuperseded(expectedSeq: Long, expectedWindowGen: Long) {
        if (pageWindowGeneration == expectedWindowGen && activeTailSyncSeq.compareAndSet(expectedSeq, 0L)) {
            internalState.update { state ->
                if (state.isPageSwitching && pageWindowGeneration == expectedWindowGen && activeTailSyncSeq.get() == 0L) {
                    state.copy(isPageSwitching = false)
                } else {
                    state
                }
            }
        }
    }

    private suspend fun syncEditedReplyPage(
        targetPage: Int,
        commentId: Long,
        expectedMarkdown: String,
        preEditMarkdown: String,
        expectedSeq: Long,
        expectedWindowGen: Long,
    ) {
        if (!isTailSyncCurrent(expectedSeq, expectedWindowGen)) return
        val safePage = targetPage.coerceAtLeast(1)
        val includeBlocked = container.settingsRepository.settings.value.includeBlockedTopics
        var detail = container.forumRepository.getTopicDetail(
            topicId = topicId,
            page = safePage,
            includeBlocked = includeBlocked,
        ).getOrNull() ?: return
        if (!isTailSyncCurrent(expectedSeq, expectedWindowGen)) return

        val fetchedBeforeRetry = detail.replies.firstOrNull { it.commentId == commentId }
        if (fetchedBeforeRetry != null &&
            fetchedBeforeRetry.markdownBody.trim() == preEditMarkdown.trim() &&
            preEditMarkdown.trim() != expectedMarkdown.trim()
        ) {
            delay(450)
            if (!isTailSyncCurrent(expectedSeq, expectedWindowGen)) return
            container.forumRepository.getTopicDetail(
                topicId = topicId,
                page = safePage,
                includeBlocked = includeBlocked,
            ).getOrNull()?.let { retried ->
                detail = retried
            }
        }
        if (!isTailSyncCurrent(expectedSeq, expectedWindowGen)) return

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
        expectedSeq: Long,
        expectedWindowGen: Long,
    ) {
        if (!isTailSyncCurrent(expectedSeq, expectedWindowGen)) {
            clearPageSwitchingIfSuperseded(expectedSeq, expectedWindowGen)
            return
        }
        val includeBlocked = container.settingsRepository.settings.value.includeBlockedTopics
        val firstResult = container.forumRepository.getTopicDetail(
            topicId = topicId,
            page = expectedPage,
            includeBlocked = includeBlocked,
        )
        var detail = firstResult.getOrNull()
        if (detail == null) {
            if (isTailSyncCurrent(expectedSeq, expectedWindowGen)) {
                activeTailSyncSeq.compareAndSet(expectedSeq, 0L)
                internalState.update { it.copy(isPageSwitching = false) }
            } else {
                clearPageSwitchingIfSuperseded(expectedSeq, expectedWindowGen)
            }
            return
        }
        if (!isTailSyncCurrent(expectedSeq, expectedWindowGen)) {
            clearPageSwitchingIfSuperseded(expectedSeq, expectedWindowGen)
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
            if (!isTailSyncCurrent(expectedSeq, expectedWindowGen)) {
                clearPageSwitchingIfSuperseded(expectedSeq, expectedWindowGen)
                return
            }
        }

        var matchedReply = findPostedReply(detail!!, previousReplyIds, sessionUid, optimisticComment)
        if (matchedReply == null) {
            delay(480)
            if (!isTailSyncCurrent(expectedSeq, expectedWindowGen)) {
                clearPageSwitchingIfSuperseded(expectedSeq, expectedWindowGen)
                return
            }
            val targetRetryPage = detail!!.totalPages.coerceAtLeast(detail!!.currentPage).coerceAtLeast(1)
            container.forumRepository.getTopicDetail(
                topicId = topicId,
                page = targetRetryPage,
                includeBlocked = includeBlocked,
            ).getOrNull()?.let { retried ->
                detail = retried
                val nextTotal = retried.totalPages.coerceAtLeast(1)
                if (nextTotal > retried.currentPage) {
                    if (!isTailSyncCurrent(expectedSeq, expectedWindowGen)) {
                        clearPageSwitchingIfSuperseded(expectedSeq, expectedWindowGen)
                        return
                    }
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

        if (!isTailSyncCurrent(expectedSeq, expectedWindowGen)) {
            clearPageSwitchingIfSuperseded(expectedSeq, expectedWindowGen)
            return
        }
        activeTailSyncSeq.compareAndSet(expectedSeq, 0L)

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
        routeReplyImageThroughPrivacyEditor(bytes = bytes, mimeType = mimeType, fileName = fileName)
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
                routeReplyImageThroughPrivacyEditor(
                    bytes = pending.bytes,
                    mimeType = pending.mimeType,
                    fileName = pending.fileName,
                )
            }
        }
    }

    private fun routeReplyImageThroughPrivacyEditor(
        bytes: ByteArray,
        mimeType: String,
        fileName: String?,
    ) {
        when (val support = ImagePrivacyBitmapCodec.inspectMosaicSupport(bytes, mimeType, fileName)) {
            is MosaicSupportStatus.SupportedStatic -> {
                internalState.update { state ->
                    state.copy(
                        replyComposer = state.replyComposer.copy(
                            pendingPrivacyImage = PendingComposerImage(
                                bytes = bytes,
                                mimeType = mimeType,
                                fileName = fileName,
                            ),
                        ),
                    )
                }
            }
            is MosaicSupportStatus.UnsupportedAnimatedOrVector -> {
                _events.trySend(TopicDetailUiEvent.ShowMessage(support.reason))
                performReplyImageUpload(bytes = bytes, mimeType = mimeType, fileName = fileName)
            }
            is MosaicSupportStatus.Unreadable -> {
                _events.trySend(TopicDetailUiEvent.ShowMessage(support.reason))
                performReplyImageUpload(bytes = bytes, mimeType = mimeType, fileName = fileName)
            }
        }
    }

    fun confirmReplyPrivacyImageUpload(
        bytes: ByteArray,
        mimeType: String,
        fileName: String?,
    ) {
        internalState.update { state ->
            state.copy(replyComposer = state.replyComposer.copy(pendingPrivacyImage = null))
        }
        performReplyImageUpload(bytes = bytes, mimeType = mimeType, fileName = fileName)
    }

    fun skipReplyPrivacyEditAndUpload() {
        val pending = internalState.value.replyComposer.pendingPrivacyImage ?: return
        internalState.update { state ->
            state.copy(replyComposer = state.replyComposer.copy(pendingPrivacyImage = null))
        }
        performReplyImageUpload(
            bytes = pending.bytes,
            mimeType = pending.mimeType,
            fileName = pending.fileName,
        )
    }

    fun dismissReplyPrivacyEditor() {
        internalState.update { state ->
            state.copy(replyComposer = state.replyComposer.copy(pendingPrivacyImage = null))
        }
    }

    // ── R16 Part 1 Magazine Long-Image Share ─────────────────────────────────

    val imageFetchClient: okhttp3.OkHttpClient?
        get() = runCatching { container.coilOkHttpClient }.getOrNull()

    suspend fun resolveOpeningFloorForShare(): FloorComment? {
        val current = deriveTopicSnapshot().openingFloor
        if (current != null) return current
        val includeBlocked = container.settingsRepository.settings.value.includeBlockedTopics
        val fetched = container.forumRepository.getTopicDetail(
            topicId = topicId,
            page = 1,
            includeBlocked = includeBlocked,
        )
        return fetched.getOrNull()?.let { page1 ->
            val freshOpening = page1.openingFloor
            if (freshOpening != null) {
                internalState.update { state ->
                    val updatedCache = state.pagesCache.toMutableMap()
                    updatedCache[1] = page1
                    state.copy(
                        pagesCache = updatedCache,
                        cachedOpeningFloor = freshOpening,
                    )
                }
            }
            freshOpening
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

    private fun resolvePollAndStardustRepo(): PollAndStardustRepository =
        pollAndStardustRepositoryOverride ?: container.pollAndStardustRepository

    private fun pollStateFlow(voteId: Long): MutableStateFlow<PollCardUiState> =
        pollCardStates.computeIfAbsent(voteId) { MutableStateFlow(PollCardUiState()) }

    private fun stardustStateFlow(widgetKey: String): MutableStateFlow<StardustCardUiState> =
        stardustCardStates.computeIfAbsent(widgetKey) { MutableStateFlow(StardustCardUiState()) }

    private data class PollReloadDispatchPlan(
        val jobsToCancel: List<Job> = emptyList(),
        val jobsToStart: List<Job> = emptyList(),
    ) {
        fun executeOutsideLock() {
            jobsToCancel.forEach { it.cancel() }
            jobsToStart.forEach { it.start() }
        }
    }

    private fun syncPollSessionChangeIfNeeded() {
        val plan = synchronized(pollSessionLock) {
            val currentUid = container.sessionRepository.sessionState.value?.uid
            if (currentUid != lastObservedPollSessionUid) {
                lastObservedPollSessionUid = currentUid
                invalidateAndReloadPollStatesForSessionChangeLocked()
            } else {
                PollReloadDispatchPlan()
            }
        }
        plan.executeOutsideLock()
    }

    private fun invalidateAndReloadPollStatesForSessionChangeLocked(): PollReloadDispatchPlan {
        val nextEpoch = pollEpoch.incrementAndGet()
        val jobsToCancel = pollLoadJobs.values.toList()
        pollLoadJobs.clear()

        val jobsToStart = mutableListOf<Job>()
        for ((voteId, flow) in pollCardStates) {
            val prev = flow.value
            if (prev.poll != null || prev.isLoading) {
                flow.value = PollCardUiState(isLoading = true)
                val job = createPollLoadJobLocked(
                    voteId = voteId,
                    flow = flow,
                    epochAtLoad = nextEpoch,
                )
                pollLoadJobs[voteId] = job
                jobsToStart += job
            } else {
                flow.value = PollCardUiState()
            }
        }
        return PollReloadDispatchPlan(
            jobsToCancel = jobsToCancel,
            jobsToStart = jobsToStart,
        )
    }

    private fun loadVoteInternal(voteId: Long, force: Boolean) {
        val plan = synchronized(pollSessionLock) {
            val jobsToCancel = mutableListOf<Job>()
            val jobsToStart = mutableListOf<Job>()
            val currentUid = container.sessionRepository.sessionState.value?.uid
            if (currentUid != lastObservedPollSessionUid) {
                lastObservedPollSessionUid = currentUid
                val invalidationPlan = invalidateAndReloadPollStatesForSessionChangeLocked()
                jobsToCancel += invalidationPlan.jobsToCancel
                jobsToStart += invalidationPlan.jobsToStart
            }

            val flow = pollStateFlow(voteId)
            val current = flow.value
            val shouldStart = if (force) {
                !current.isLoading
            } else {
                current.poll == null && !current.isLoading
            }
            if (shouldStart) {
                pollLoadJobs.remove(voteId)?.let { jobsToCancel += it }
                flow.value = current.copy(isLoading = true, errorMessage = null)
                val epochAtLoad = pollEpoch.get()
                val job = createPollLoadJobLocked(
                    voteId = voteId,
                    flow = flow,
                    epochAtLoad = epochAtLoad,
                )
                pollLoadJobs[voteId] = job
                jobsToStart += job
            }
            PollReloadDispatchPlan(
                jobsToCancel = jobsToCancel,
                jobsToStart = jobsToStart,
            )
        }
        plan.executeOutsideLock()
    }

    private fun createPollLoadJobLocked(
        voteId: Long,
        flow: MutableStateFlow<PollCardUiState>,
        epochAtLoad: Long,
    ): Job {
        lateinit var createdJob: Job
        createdJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                resolvePollAndStardustRepo().getVote(voteId).fold(
                    onSuccess = { detail ->
                        val plan = synchronized(pollSessionLock) {
                            val currentUid = container.sessionRepository.sessionState.value?.uid
                            if (currentUid != lastObservedPollSessionUid) {
                                lastObservedPollSessionUid = currentUid
                                invalidateAndReloadPollStatesForSessionChangeLocked()
                            } else if (pollEpoch.get() == epochAtLoad && pollLoadJobs[voteId] === createdJob) {
                                flow.value = flow.value.copy(
                                    isLoading = false,
                                    poll = detail,
                                    errorMessage = null,
                                )
                                PollReloadDispatchPlan()
                            } else {
                                PollReloadDispatchPlan()
                            }
                        }
                        plan.executeOutsideLock()
                    },
                    onFailure = { err ->
                        val plan = synchronized(pollSessionLock) {
                            val currentUid = container.sessionRepository.sessionState.value?.uid
                            if (currentUid != lastObservedPollSessionUid) {
                                lastObservedPollSessionUid = currentUid
                                invalidateAndReloadPollStatesForSessionChangeLocked()
                            } else if (pollEpoch.get() == epochAtLoad && pollLoadJobs[voteId] === createdJob) {
                                flow.value = flow.value.copy(
                                    isLoading = false,
                                    errorMessage = err.message?.takeIf { m -> m.isNotBlank() } ?: "加载投票失败",
                                )
                                PollReloadDispatchPlan()
                            } else {
                                PollReloadDispatchPlan()
                            }
                        }
                        plan.executeOutsideLock()
                    },
                )
            } finally {
                synchronized(pollSessionLock) {
                    if (pollLoadJobs[voteId] === createdJob) {
                        pollLoadJobs.remove(voteId, createdJob)
                        if (pollEpoch.get() == epochAtLoad && flow.value.isLoading) {
                            flow.value = flow.value.copy(isLoading = false)
                        }
                    }
                }
            }
        }
        return createdJob
    }

    fun openStardustTipForComment(comment: FloorComment) {
        if (comment.author.uid <= 0L) return
        openStardustTip(
            StardustTipTarget(
                receiverUid = comment.author.uid,
                receiverNameHint = comment.author.username,
                refId = topicId.coerceAtLeast(0L),
            ),
        )
    }

    fun openStardustTip(target: StardustTipTarget) {
        if (!ensureAuthenticatedForAction()) return
        val viewerUid = container.sessionRepository.sessionState.value?.uid
        if (viewerUid != null && viewerUid == target.receiverUid) {
            _events.trySend(TopicDetailUiEvent.ShowMessage("不能给自己投喂星尘"))
            return
        }
        val seq = ++stardustPrepareSeq
        stardustPrepareJob?.cancel()
        internalState.update {
            it.copy(
                stardustTipDialog = StardustTipDialogUiState(
                    target = target,
                    isPreparing = true,
                    resolvedReceiverName = target.receiverNameHint,
                    amountInput = (target.suggestedAmount ?: 10).toString(),
                ),
            )
        }
        prepareStardustTipInternal(target.receiverUid, seq)
    }

    fun retryStardustPrepare() {
        val dialog = internalState.value.stardustTipDialog ?: return
        val seq = ++stardustPrepareSeq
        stardustPrepareJob?.cancel()
        internalState.update {
            it.copy(
                stardustTipDialog = dialog.copy(
                    isPreparing = true,
                    prepareError = null,
                ),
            )
        }
        prepareStardustTipInternal(dialog.target.receiverUid, seq)
    }

    private fun prepareStardustTipInternal(receiverUid: Long, seq: Long) {
        stardustPrepareJob = viewModelScope.launch {
            resolvePollAndStardustRepo().prepareStardustTransfer(receiverUid).fold(
                onSuccess = { preview ->
                    if (seq != stardustPrepareSeq) return@fold
                    internalState.update { state ->
                        val dialog = state.stardustTipDialog ?: return@update state
                        if (dialog.target.receiverUid != receiverUid) return@update state
                        state.copy(
                            stardustTipDialog = dialog.copy(
                                isPreparing = false,
                                resolvedReceiverName = preview.receiverName,
                                prepareError = null,
                            ),
                        )
                    }
                },
                onFailure = { err ->
                    if (seq != stardustPrepareSeq) return@fold
                    internalState.update { state ->
                        val dialog = state.stardustTipDialog ?: return@update state
                        if (dialog.target.receiverUid != receiverUid) return@update state
                        state.copy(
                            stardustTipDialog = dialog.copy(
                                isPreparing = false,
                                prepareError = err.message?.takeIf { m -> m.isNotBlank() } ?: "核验接收者失败",
                            ),
                        )
                    }
                },
            )
        }
    }

    fun updateStardustTipAmount(rawInput: String) {
        internalState.update { state ->
            val dialog = state.stardustTipDialog ?: return@update state
            state.copy(
                stardustTipDialog = dialog.copy(
                    amountInput = rawInput.filter(Char::isDigit).take(6),
                    sendError = null,
                ),
            )
        }
    }

    fun dismissStardustTipDialog() {
        stardustPrepareSeq++
        stardustPrepareJob?.cancel()
        stardustPrepareJob = null
        internalState.update { it.copy(stardustTipDialog = null) }
    }

    fun submitStardustTip() {
        if (!ensureAuthenticatedForAction()) return
        val session = container.sessionRepository.sessionState.value
        var validatedDialog: StardustTipDialogUiState? = null
        var validatedAmount = 0

        internalState.update { state ->
            val dialog = state.stardustTipDialog ?: return@update state
            if (dialog.isPreparing || dialog.isSending) return@update state

            val amount = dialog.amountInput.trim().toIntOrNull()
            if (amount == null || amount <= 0) {
                return@update state.copy(
                    stardustTipDialog = dialog.copy(sendError = "请输入大于 0 的星尘数量"),
                )
            }
            if (session != null && session.uid == dialog.target.receiverUid) {
                return@update state.copy(
                    stardustTipDialog = dialog.copy(sendError = "不能给自己投喂星尘"),
                )
            }
            if (session != null && amount > session.stardust) {
                return@update state.copy(
                    stardustTipDialog = dialog.copy(sendError = "星尘余额不足（当前可用 ${session.stardust}）"),
                )
            }

            validatedDialog = dialog
            validatedAmount = amount
            state.copy(stardustTipDialog = dialog.copy(isSending = true, sendError = null))
        }

        val dialog = validatedDialog ?: return
        val amount = validatedAmount

        viewModelScope.launch {
            try {
                resolvePollAndStardustRepo().sendStardust(
                    memberId = dialog.target.receiverUid,
                    amount = amount,
                    refId = dialog.target.refId,
                ).fold(
                    onSuccess = {
                        val receiverName = dialog.resolvedReceiverName.ifBlank { "UID ${dialog.target.receiverUid}" }
                        dialog.target.widgetKey?.let { key ->
                            stardustStateFlow(key).value = StardustCardUiState(
                                lastTippedAmount = amount,
                                isTipped = true,
                            )
                        }
                        internalState.update { it.copy(stardustTipDialog = null) }
                        _events.trySend(TopicDetailUiEvent.ShowMessage("已向 $receiverName 投喂 $amount 星尘 ✨"))
                        viewModelScope.launch {
                            runCatching { container.sessionRepository.refreshSession() }
                        }
                    },
                    onFailure = { err ->
                        internalState.update { state ->
                            val cur = state.stardustTipDialog ?: return@update state
                            state.copy(
                                stardustTipDialog = cur.copy(
                                    isSending = false,
                                    sendError = err.message?.takeIf { m -> m.isNotBlank() } ?: "投喂星尘失败",
                                ),
                            )
                        }
                        handleActionFailure(err, "投喂星尘失败")
                    },
                )
            } finally {
                internalState.update { state ->
                    val cur = state.stardustTipDialog ?: return@update state
                    if (cur.isSending) {
                        state.copy(stardustTipDialog = cur.copy(isSending = false))
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun appendLuckyLinkToTopicOp(luckyMarkdown: String) {
        if (!ensureAuthenticatedForAction()) return
        val snapshot = deriveTopicSnapshot()
        val pageData = snapshot.pageData ?: return
        val opening = snapshot.openingFloor ?: return
        if (!snapshot.canEditTopic) return
        val cleanSnippet = luckyMarkdown.trim()
        if (cleanSnippet.isEmpty()) return

        val updatedMarkdown = if (opening.markdownBody.isBlank()) {
            cleanSnippet
        } else {
            "${opening.markdownBody.trimEnd()}\n\n$cleanSnippet"
        }

        viewModelScope.launch {
            container.forumRepository.editTopic(
                topicId = topicId,
                title = pageData.title,
                markdownContent = updatedMarkdown,
                requiredViewLevel = pageData.requiredViewLevel,
            ).fold(
                onSuccess = {
                    _events.trySend(TopicDetailUiEvent.ShowMessage("抽奖链接已追加到主楼正文"))
                    refreshCurrentPage()
                },
                onFailure = { err ->
                    handleActionFailure(err, "更新主楼抽奖链接失败")
                },
            )
        }
    }

    // ── R9 Trade Closure, R10 Market Reference & R11 Carpool Status ───────────

    private fun isTradeCategory(page: TopicDetailPage): Boolean =
        page.categorySlug.equals("trade", ignoreCase = true) || page.categoryName.trim() == "交易"

    private fun isTradeTopicForMarketReference(
        page: TopicDetailPage,
        openingFloor: FloorComment? = page.openingFloor,
    ): Boolean {
        if (isTradeCategory(page)) return true
        val slug = page.categorySlug.trim().lowercase(Locale.ROOT)
        val name = page.categoryName.trim()
        if (name.contains("交易")) return true

        val hasExplicitCategory = slug.isNotEmpty() && slug != "all" && slug != "index" && slug != "unknown"
        val hasExplicitCategoryName = name.isNotEmpty() && name != "全部"
        if (hasExplicitCategory || hasExplicitCategoryName) {
            return false
        }

        val title = page.title.trim().ifBlank { titleHint.trim() }
        if (title.isEmpty()) return false
        if (MARKET_REF_EXPOSURE_TITLE_REGEX.containsMatchIn(title)) return false

        val summary = TopicSummary(
            id = page.topicId,
            title = title,
            categorySlug = page.categorySlug,
            categoryName = page.categoryName,
            authorName = page.author.username,
        )
        if (TrustRiskEvaluator.isTradeTopic(summary)) return true
        if (MARKET_REF_TRADE_TITLE_SIGNAL_REGEX.containsMatchIn(title)) return true

        val body = openingFloor?.markdownBody?.trim().orEmpty()
        if (body.isNotEmpty() && MARKET_REF_TRADE_BODY_SIGNAL_REGEX.containsMatchIn(body)) {
            return true
        }
        return false
    }

    private fun isCarpoolCategory(page: TopicDetailPage): Boolean =
        page.categorySlug.equals("carpool", ignoreCase = true) || page.categoryName.trim() == "拼车"

    private suspend fun ensurePageOneForOpEdit(): Result<TopicDetailPage> {
        val cachedPage1 = internalState.value.pagesCache[1]
            ?.takeUnless { it.isOfflineSnapshot }
            ?.takeIf { it.openingFloor != null }
        if (cachedPage1 != null) {
            return Result.success(cachedPage1)
        }
        val includeBlocked = container.settingsRepository.settings.value.includeBlockedTopics
        val fetched = container.forumRepository.getTopicDetail(
            topicId = topicId,
            page = 1,
            includeBlocked = includeBlocked,
        )
        return fetched.fold(
            onSuccess = { page1 ->
                val opening = page1.openingFloor
                if (page1.isOfflineSnapshot || opening == null) {
                    Result.failure(IllegalStateException("未能获取主楼正文，请刷新后重试"))
                } else {
                    internalState.update { state ->
                        val updatedCache = state.pagesCache.toMutableMap()
                        updatedCache[1] = page1
                        val activePage = state.currentPage
                        if (activePage > 1) {
                            updatedCache[activePage]?.let { sub ->
                                val resolvedAuthor = if (sub.author.uid > 0L) sub.author else page1.author
                                updatedCache[activePage] = sub.copy(
                                    title = page1.title.ifBlank { sub.title },
                                    categorySlug = sub.categorySlug.ifBlank { page1.categorySlug },
                                    categoryName = sub.categoryName.ifBlank { page1.categoryName },
                                    author = resolvedAuthor,
                                    requiredViewLevel = page1.requiredViewLevel,
                                    openingFloor = opening,
                                )
                            }
                        }
                        state.copy(
                            pagesCache = updatedCache,
                            cachedOpeningFloor = opening,
                        )
                    }
                    Result.success(page1)
                }
            },
            onFailure = { err -> Result.failure(err) },
        )
    }

    private fun applyOpEditToLocalState(newTitle: String, newMarkdownBody: String) {
        internalState.update { state ->
            val updatedCache = state.pagesCache.mapValues { (_, page) ->
                page.copy(
                    title = newTitle,
                    openingFloor = page.openingFloor?.copy(markdownBody = newMarkdownBody),
                )
            }
            val updatedOpening = state.cachedOpeningFloor?.copy(markdownBody = newMarkdownBody)
            state.copy(
                pagesCache = updatedCache,
                cachedOpeningFloor = updatedOpening,
                isMutatingTopic = false,
            )
        }
    }

    private fun clearMutatingTopic() {
        internalState.update { state ->
            if (state.isMutatingTopic) state.copy(isMutatingTopic = false) else state
        }
    }

    fun closeTradeTopic(options: TradePrivacyOptions = TradePrivacyOptions()) {
        if (!ensureAuthenticatedForAction()) return
        val snapshot = deriveTopicSnapshot()
        val pageData = snapshot.pageData ?: return
        if (!snapshot.canEditTopic) return
        if (!isTradeCategory(pageData)) return
        if (internalState.value.isMutatingTopic) return

        var launched = false
        internalState.update { it.copy(isMutatingTopic = true) }
        try {
            viewModelScope.launch {
                try {
                    val page1Result = ensurePageOneForOpEdit()
                    val page1 = page1Result.getOrElse { err ->
                        clearMutatingTopic()
                        handleActionFailure(err, "获取主楼正文失败，请刷新后重试")
                        return@launch
                    }
                    val opening = page1.openingFloor
                    if (opening == null) {
                        clearMutatingTopic()
                        _events.trySend(TopicDetailUiEvent.ShowMessage("未能获取主楼正文，无法执行结帖"))
                        return@launch
                    }

                    val updatedTitle = TradeTitleFormatter.applySoldPrefix(page1.title)
                    val sanitizeResult = TradePrivacySanitizer.sanitize(opening.markdownBody, options)

                    container.forumRepository.editTopic(
                        topicId = topicId,
                        title = updatedTitle,
                        markdownContent = sanitizeResult.sanitizedMarkdown,
                        requiredViewLevel = page1.requiredViewLevel,
                    ).fold(
                        onSuccess = {
                            applyOpEditToLocalState(updatedTitle, sanitizeResult.sanitizedMarkdown)
                            val msg = if (sanitizeResult.redactionCount > 0) {
                                "已标记 [已出]，并脱敏 ${sanitizeResult.redactionCount} 处敏感信息"
                            } else {
                                "已标记为 [已出]"
                            }
                            _events.trySend(TopicDetailUiEvent.ShowMessage(msg))
                            refreshCurrentPage()
                        },
                        onFailure = { err ->
                            clearMutatingTopic()
                            handleActionFailure(err, "标记已出失败")
                        },
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (err: Throwable) {
                    clearMutatingTopic()
                    handleActionFailure(err, "标记已出失败")
                } finally {
                    clearMutatingTopic()
                }
            }
            launched = true
        } finally {
            if (!launched) {
                clearMutatingTopic()
            }
        }
    }

    fun updateCarpoolStatus(
        statusToken: String,
        occupiedSeats: Int? = null,
        totalSeats: Int? = null,
        updateBody: Boolean = true,
    ) {
        if (!ensureAuthenticatedForAction()) return
        val snapshot = deriveTopicSnapshot()
        val pageData = snapshot.pageData ?: return
        if (!snapshot.canEditTopic) return
        if (!isCarpoolCategory(pageData)) return
        val cleanToken = statusToken.trim()
        if (cleanToken.isEmpty() || internalState.value.isMutatingTopic) return

        var launched = false
        internalState.update { it.copy(isMutatingTopic = true) }
        try {
            viewModelScope.launch {
                try {
                    val page1Result = ensurePageOneForOpEdit()
                    val page1 = page1Result.getOrElse { err ->
                        clearMutatingTopic()
                        handleActionFailure(err, "获取主楼正文失败，请刷新后重试")
                        return@launch
                    }
                    val opening = page1.openingFloor
                    if (opening == null) {
                        clearMutatingTopic()
                        _events.trySend(TopicDetailUiEvent.ShowMessage("未能获取主楼正文，无法更新车位"))
                        return@launch
                    }

                    val updatedTitle = CarpoolStatusUpdater.updateTitle(page1.title, cleanToken)
                    val updatedBody = if (updateBody) {
                        CarpoolStatusUpdater.updateMarkdownStatus(
                            markdown = opening.markdownBody,
                            newStatusToken = cleanToken,
                            occupiedSeats = occupiedSeats,
                            totalSeats = totalSeats,
                        )
                    } else {
                        opening.markdownBody
                    }

                    container.forumRepository.editTopic(
                        topicId = topicId,
                        title = updatedTitle,
                        markdownContent = updatedBody,
                        requiredViewLevel = page1.requiredViewLevel,
                    ).fold(
                        onSuccess = {
                            applyOpEditToLocalState(updatedTitle, updatedBody)
                            _events.trySend(TopicDetailUiEvent.ShowMessage("拼车状态已更新为 [$cleanToken]"))
                            refreshCurrentPage()
                        },
                        onFailure = { err ->
                            clearMutatingTopic()
                            handleActionFailure(err, "更新拼车状态失败")
                        },
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (err: Throwable) {
                    clearMutatingTopic()
                    handleActionFailure(err, "更新拼车状态失败")
                } finally {
                    clearMutatingTopic()
                }
            }
            launched = true
        } finally {
            if (!launched) {
                clearMutatingTopic()
            }
        }
    }

    private fun normalizedMarketQueryKey(query: String): String {
        val tokens = TradeMarketPremiumAnalyzer.tokenizeQuery(query)
        return if (tokens.isNotEmpty()) tokens.joinToString(" ") else query.trim().lowercase(Locale.ROOT)
    }

    private suspend fun computeCurrentTopicPricing(
        title: String,
        openingMarkdown: String?,
    ): InferredListingPricing? {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty() && openingMarkdown.isNullOrBlank()) return null
        val fxRepo = runCatching { container.exchangeRateRepository }.getOrNull()
        val fxState = if (fxRepo != null) {
            runCatching { fxRepo.loadPersistedState() }.getOrElse { fxRepo.state.value }
        } else {
            null
        }
        val fxToCny: (String) -> java.math.BigDecimal? = { rawCode ->
            val currency = ValuationCurrency.fromCode(rawCode)
            when {
                currency == null -> TradeListingPriceParser.defaultFxToCny(rawCode)
                currency == ValuationCurrency.CNY -> java.math.BigDecimal.ONE
                fxState != null -> fxState.rateFor(currency)
                else -> currency.defaultCnyRate
            }
        }
        return TradeListingPriceParser.inferSpread(
            title = cleanTitle,
            bodySnippet = openingMarkdown,
            fxToCny = fxToCny,
            now = VpsValuationEngine.todayInShanghai(),
        )
    }

    private fun currentTopicTitleAndBody(): Pair<String, String?> {
        val snapshot = deriveTopicSnapshot()
        val pageTitle = snapshot.pageData?.title?.ifBlank { titleHint } ?: titleHint
        return pageTitle.trim() to snapshot.openingFloor?.markdownBody
    }

    private fun maybeAnalyzeLocalMarketReference(
        page: TopicDetailPage,
        allowAutoRemote: Boolean = !page.isOfflineSnapshot,
    ) {
        val repo = tradeMarketReferenceRepository ?: return
        val resolvedOpening = page.openingFloor ?: internalState.value.cachedOpeningFloor
        if (!isTradeTopicForMarketReference(page, resolvedOpening)) return

        val cleanTitle = page.title.trim().ifBlank { titleHint.trim() }
        val openingBody = resolvedOpening?.markdownBody
        if (!openingBody.isNullOrBlank()) {
            repo.cacheTopicBody(topicId, openingBody)
        }
        if (hasUserCustomMarketQuery) {
            viewModelScope.launch {
                val pricing = computeCurrentTopicPricing(cleanTitle, openingBody)
                internalState.update { cur ->
                    val ref = cur.marketReference ?: return@update cur
                    if (ref.currentTopicPricing == pricing) cur else cur.copy(marketReference = ref.copy(currentTopicPricing = pricing))
                }
            }
            return
        }

        val suggested = extractMarketQueryWithContext(
            title = cleanTitle,
            openingMarkdown = openingBody,
        )

        val hasOpeningBody = !openingBody.isNullOrBlank()
        val signature = "$cleanTitle|$hasOpeningBody|$suggested"
        if (signature == lastAnalyzedMarketSignature) return

        val currentRef = internalState.value.marketReference
        if (suggested.isBlank()) {
            lastAnalyzedMarketSignature = signature
            viewModelScope.launch {
                val pricing = computeCurrentTopicPricing(cleanTitle, openingBody)
                internalState.update { cur ->
                    val existing = cur.marketReference
                    if (existing == null) {
                        cur.copy(marketReference = TradeMarketReferenceState(query = "", currentTopicPricing = pricing))
                    } else if (existing.query.isBlank() && existing.currentTopicPricing != pricing) {
                        cur.copy(marketReference = existing.copy(currentTopicPricing = pricing))
                    } else {
                        cur
                    }
                }
            }
            return
        }

        val suggestedKey = normalizedMarketQueryKey(suggested)
        val currentKey = currentRef?.query?.takeIf { it.isNotBlank() }?.let(::normalizedMarketQueryKey)
        if (currentRef != null && currentKey == suggestedKey) {
            if (currentRef.searchStatus == MarketSearchStatus.LOADING) {
                lastAnalyzedMarketSignature = signature
                viewModelScope.launch {
                    val pricing = computeCurrentTopicPricing(cleanTitle, openingBody)
                    internalState.update { cur ->
                        val ref = cur.marketReference ?: return@update cur
                        if (ref.currentTopicPricing == pricing) cur else cur.copy(marketReference = ref.copy(currentTopicPricing = pricing))
                    }
                }
                return
            }
            val prevTitle = lastAnalyzedMarketSignature?.substringBefore('|')
            if (currentRef.hasRemoteResults && cleanTitle == prevTitle) {
                lastAnalyzedMarketSignature = signature
                viewModelScope.launch {
                    val pricing = computeCurrentTopicPricing(cleanTitle, openingBody)
                    internalState.update { cur ->
                        val ref = cur.marketReference ?: return@update cur
                        if (ref.currentTopicPricing == pricing) cur else cur.copy(marketReference = ref.copy(currentTopicPricing = pricing))
                    }
                }
                return
            }
        }

        lastAnalyzedMarketSignature = signature
        refreshLocalMarketReferenceInternal(
            repo = repo,
            query = suggested,
            allowAutoRemote = allowAutoRemote,
            sourceTitle = cleanTitle,
            sourceOpeningBody = openingBody,
        )
    }

    fun refreshLocalMarketReference(customQuery: String? = null) {
        val repo = tradeMarketReferenceRepository ?: return
        val trimmedCustom = customQuery?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedCustom != null) {
            hasUserCustomMarketQuery = true
        }
        val (pageTitle, openingBody) = currentTopicTitleAndBody()
        val query = trimmedCustom
            ?: internalState.value.marketReference?.query?.takeIf { it.isNotBlank() }
            ?: extractMarketQueryWithContext(pageTitle, openingBody)
        if (query.isBlank()) return
        refreshLocalMarketReferenceInternal(
            repo = repo,
            query = query,
            allowAutoRemote = false,
            sourceTitle = pageTitle,
            sourceOpeningBody = openingBody,
        )
    }

    private fun refreshLocalMarketReferenceInternal(
        repo: TradeMarketReferenceRepository,
        query: String,
        allowAutoRemote: Boolean,
        sourceTitle: String,
        sourceOpeningBody: String?,
    ) {
        val seq = ++marketRefRequestSeq
        marketRefJob?.cancel()
        marketRefJob = viewModelScope.launch {
            val currentPricing = computeCurrentTopicPricing(sourceTitle, sourceOpeningBody)
            val localState = try {
                repo.analyzeLocal(
                    query = query,
                    excludeTopicId = topicId,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                TradeMarketReferenceState(
                    query = query,
                    searchStatus = MarketSearchStatus.FAILED,
                    statusMessage = t.message?.takeIf { it.isNotBlank() } ?: "本地参考分析失败",
                )
            }.copy(currentTopicPricing = currentPricing)
            if (seq != marketRefRequestSeq) return@launch
            internalState.update { it.copy(marketReference = localState) }

            val peerSampleCount = localState.distribution?.observations?.count { it.topicId != topicId } ?: 0
            val normalizedKey = normalizedMarketQueryKey(query)
            val shouldAutoRemote = allowAutoRemote &&
                !localState.hasRemoteResults &&
                peerSampleCount == 0 &&
                localState.cooldownRemainingSeconds <= 0 &&
                localState.searchStatus == MarketSearchStatus.IDLE &&
                TradeMarketPremiumAnalyzer.tokenizeQuery(query).isNotEmpty() &&
                autoRemoteTriggeredMarketQueries.add(normalizedKey)

            if (shouldAutoRemote) {
                internalState.update { cur ->
                    if (seq != marketRefRequestSeq) return@update cur
                    val base = cur.marketReference ?: localState
                    cur.copy(
                        marketReference = base.copy(
                            query = query,
                            searchStatus = MarketSearchStatus.LOADING,
                            statusMessage = null,
                            currentTopicPricing = currentPricing,
                        ),
                    )
                }
                val merged = try {
                    repo.searchRemoteAndMerge(
                        query = query,
                        excludeTopicId = topicId,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    localState.copy(
                        query = query,
                        searchStatus = MarketSearchStatus.FAILED,
                        statusMessage = t.message?.takeIf { it.isNotBlank() } ?: "搜索交易版失败，已保留本地参考",
                    )
                }.copy(currentTopicPricing = currentPricing)
                if (seq != marketRefRequestSeq) return@launch
                internalState.update { it.copy(marketReference = merged) }
            }
        }
    }

    fun searchTradeMarketReference(customQuery: String? = null) {
        val repo = tradeMarketReferenceRepository ?: return
        val trimmedCustom = customQuery?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedCustom != null) {
            hasUserCustomMarketQuery = true
        }
        val currentQuery = internalState.value.marketReference?.query.orEmpty()
        val (pageTitle, openingBody) = currentTopicTitleAndBody()
        val query = trimmedCustom
            ?: currentQuery.takeIf { it.isNotBlank() }
            ?: extractMarketQueryWithContext(pageTitle, openingBody)
        if (query.isBlank()) return

        val seq = ++marketRefRequestSeq
        autoRemoteTriggeredMarketQueries.add(normalizedMarketQueryKey(query))
        marketRefJob?.cancel()
        marketRefJob = viewModelScope.launch {
            val currentPricing = computeCurrentTopicPricing(pageTitle, openingBody)
            internalState.update { cur ->
                val base = cur.marketReference ?: TradeMarketReferenceState(query = query)
                val sameQuery = base.query.equals(query, ignoreCase = true)
                cur.copy(
                    marketReference = base.copy(
                        query = query,
                        distribution = if (sameQuery) base.distribution else null,
                        searchStatus = MarketSearchStatus.LOADING,
                        statusMessage = null,
                        currentTopicPricing = currentPricing,
                    ),
                )
            }
            val merged = try {
                repo.searchRemoteAndMerge(
                    query = query,
                    excludeTopicId = topicId,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val fallback = runCatching {
                    repo.analyzeLocal(
                        query = query,
                        excludeTopicId = topicId,
                    )
                }.getOrDefault(internalState.value.marketReference ?: TradeMarketReferenceState(query = query))
                fallback.copy(
                    query = query,
                    searchStatus = MarketSearchStatus.FAILED,
                    statusMessage = t.message?.takeIf { it.isNotBlank() } ?: "搜索交易版失败，已保留本地参考",
                )
            }.copy(currentTopicPricing = currentPricing)
            if (seq != marketRefRequestSeq) return@launch
            internalState.update { it.copy(marketReference = merged) }
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
        private val nonCancellableDraftScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val MARKET_REF_EXPOSURE_TITLE_REGEX = Regex(
            """^(?:[\[\【(（「『]\s*)?(?:曝光|挂人|避雷|维权|仲裁|被骗|骗子)""",
            RegexOption.IGNORE_CASE,
        )
        private val MARKET_REF_TRADE_TITLE_SIGNAL_REGEX = Regex(
            """(?:溢价|折价|平出|原价出|原价转|剩余价值|剩价|残值|明盘|一口价|自刀|小刀|秒出|急出|慢出|急收|慢收|诚收|诚心收|求购|包\s*push|不包\s*push|改邮|原邮|原油|带原油|带原邮|给原油|给原邮|带邮|出鸡|收鸡|出小鸡|收小鸡|出传家宝|\[(?:出|收|转|售|已出|已售|求购)]|【(?:出|收|转|售|已出|已售|求购)】|^(?:继续|再|长期|诚心?|急|慢|高价|原价|溢价|折价)?[出收](?!\s*(?:bug|error|问题|故障|事|错|状况|email|mail|sms|验证码|邮件|信件))(?:[\s:：/\-]|[A-Za-z0-9]|一台|一个|一只|个|台|只|瓦工|搬瓦工|大妈|绿云|斯巴达|爪云|甲骨文|龟壳|咸鱼|爱丽丝|御坂|火山|库伦|阿里|腾讯|美西|香港|日本|东京|新加坡|软银))""",
            RegexOption.IGNORE_CASE,
        )
        private val MARKET_REF_TRADE_BODY_SIGNAL_REGEX = Regex(
            """(?:剩余价值|净残值|溢价|折价|平出|原价出|明盘|一口价|包\s*push|不包\s*push|工单\s*push|改邮|带原油|带原邮|给原油|给原邮|原邮出|原油出|带邮出|出让挂牌价|过户定价|走中介|中介费|先款|急出|慢出|急收|慢收|诚收|求购|出鸡|收鸡|(?:到期[\s\S]{0,60}续费|续费[\s\S]{0,60}到期)[\s\S]{0,80}(?:明盘|一口价|剩余价值|溢价|折价|平出|包\s*push|push|改邮|原邮|原油|出鸡|转让|出让|自刀|\d+\s*[rR元块]))""",
            RegexOption.IGNORE_CASE,
        )

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
                    pollAndStardustRepositoryOverride = container.pollAndStardustRepository,
                    tradeMarketReferenceRepository = container.tradeMarketReferenceRepository,
                ) as T
            }
        }
    }
}
