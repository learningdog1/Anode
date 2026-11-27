package com.nodeseek.app.ui.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AccountSlotSummary
import com.nodeseek.app.domain.model.AccountSwitchResult
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.AwayDigestTriggerMode
import com.nodeseek.app.domain.model.BookmarkedTopic
import com.nodeseek.app.domain.model.CheckInStatus
import com.nodeseek.app.domain.model.DailyQuotaProgress
import com.nodeseek.app.domain.model.DirectMessageSummary
import com.nodeseek.app.domain.model.NotificationItem
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.repository.MultiAccountManager
import com.nodeseek.app.domain.repository.NoOpMultiAccountManager
import com.nodeseek.app.domain.repository.SignInStepResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class HubCollectionTab {
    BOOKMARKS,
    HISTORY,
}

data class HubBookmarkDisplayItem(
    val topicId: Long,
    val summary: TopicSummary,
    val hasOfflineSnapshot: Boolean,
    val savedAtEpochMillis: Long?,
)

data class NativeSignInDialogState(
    val isVisible: Boolean = false,
    val isSubmitting: Boolean = false,
    val otpSession: String? = null,
    val errorMessage: String? = null,
    val turnstileResetNonce: Int = 0,
)

data class PulseHubTransientState(
    val selectedTab: HubCollectionTab = HubCollectionTab.BOOKMARKS,
    val isRefreshing: Boolean = false,
    val isLoadingCheckInStatus: Boolean = false,
    val dailyQuota: DailyQuotaProgress? = null,
    val isCheckingIn: Boolean = false,
    val serverBookmarks: List<TopicSummary> = emptyList(),
    val serverBookmarksPage: Int = 1,
    val hasMoreServerBookmarks: Boolean = false,
    val isLoadingServerBookmarks: Boolean = false,
    val nativeSignIn: NativeSignInDialogState = NativeSignInDialogState(),
)

data class PulseHubUiState(
    val session: AccountSession? = null,
    val settings: AppSettings = AppSettings(),
    val unreadInboxCount: Int = 0,
    val selectedTab: HubCollectionTab = HubCollectionTab.BOOKMARKS,
    val isRefreshing: Boolean = false,
    val isLoadingCheckInStatus: Boolean = false,
    val checkInStatus: CheckInStatus? = null,
    val dailyQuota: DailyQuotaProgress? = null,
    val isCheckingIn: Boolean = false,
    val bookmarks: List<HubBookmarkDisplayItem> = emptyList(),
    val hasMoreServerBookmarks: Boolean = false,
    val isLoadingServerBookmarks: Boolean = false,
    val readHistory: List<ReadHistoryEntry> = emptyList(),
    val nativeSignIn: NativeSignInDialogState = NativeSignInDialogState(),
    val unreadNotificationCount: Int = 0,
    val unreadDirectMessageCount: Int = 0,
    val accounts: List<AccountSlotSummary> = emptyList(),
    val isSwitchingAccount: Boolean = false,
)

class PulseHubViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val multiAccountManager: MultiAccountManager by lazy {
        runCatching { container.multiAccountManager }.getOrDefault(NoOpMultiAccountManager)
    }

    private val transientState = MutableStateFlow(PulseHubTransientState())

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val inboxDataFlow = combine(
        container.inboxRepository.unreadTotalCount,
        container.inboxRepository.replyNotifications,
        container.inboxRepository.mentionNotifications,
        container.inboxRepository.messageThreads,
    ) { unreadTotal, replies, mentions, threads ->
        InboxHubData(
            unreadTotal = unreadTotal,
            replies = replies,
            mentions = mentions,
            threads = threads,
        )
    }

    private val baseDataFlow = combine(
        container.sessionRepository.sessionState,
        container.settingsRepository.settings,
        inboxDataFlow,
        container.bookmarkAndHistoryRepository.observeLocalBookmarks(),
        container.bookmarkAndHistoryRepository.observeReadHistory(),
    ) { session, settings, inboxData, localBookmarks, history ->
        BaseHubData(
            session = session,
            settings = settings,
            inboxData = inboxData,
            localBookmarks = localBookmarks,
            history = history,
        )
    }

    val uiState: StateFlow<PulseHubUiState> = combine(
        baseDataFlow,
        transientState.asStateFlow(),
        container.checkInRepository.checkInStatus,
        multiAccountManager.accountsFlow,
        multiAccountManager.isSwitchingFlow,
    ) { base, transient, sharedCheckInStatus, accounts, isSwitchingAccount ->
        val mergedBookmarks = mergeHubBookmarks(
            local = base.localBookmarks,
            server = transient.serverBookmarks,
            history = base.history,
        )
        val isSignedIn = base.session != null
        val unreadTotal = if (isSignedIn) base.inboxData.unreadTotal else 0
        val dmUnread = if (isSignedIn) {
            base.inboxData.threads.sumOf { if (it.isRead) 0 else it.unreadCount.coerceAtLeast(1) }
        } else {
            0
        }
        val loadedNotifUnread = if (isSignedIn) {
            base.inboxData.replies.count { !it.isRead } + base.inboxData.mentions.count { !it.isRead }
        } else {
            0
        }
        val notifUnread = if (isSignedIn) {
            maxOf(loadedNotifUnread, (unreadTotal - dmUnread).coerceAtLeast(0))
        } else {
            0
        }
        PulseHubUiState(
            session = base.session,
            settings = base.settings,
            unreadInboxCount = unreadTotal,
            selectedTab = transient.selectedTab,
            isRefreshing = transient.isRefreshing,
            isLoadingCheckInStatus = transient.isLoadingCheckInStatus,
            checkInStatus = if (isSignedIn) sharedCheckInStatus else null,
            dailyQuota = transient.dailyQuota,
            isCheckingIn = transient.isCheckingIn,
            bookmarks = mergedBookmarks,
            hasMoreServerBookmarks = transient.hasMoreServerBookmarks,
            isLoadingServerBookmarks = transient.isLoadingServerBookmarks,
            readHistory = base.history,
            nativeSignIn = transient.nativeSignIn,
            unreadNotificationCount = notifUnread,
            unreadDirectMessageCount = dmUnread,
            accounts = accounts,
            isSwitchingAccount = isSwitchingAccount,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = PulseHubUiState(),
    )

    private var lastObservedUid: Long? = null
    private var telemetryGeneration: Long = 0L
    private var activeTelemetryJob: Job? = null
    private var activeAuthTelemetryJob: Job? = null
    private var activeRefreshJob: Job? = null
    private var activeLoadMoreBookmarksJob: Job? = null
    private var activeHubVisibleJob: Job? = null
    private var lastTelemetryRefreshedAtMs: Long = 0L
    private var lastTelemetryBeijingDate: LocalDate? = null

    private val cachedLocalBookmarkedIds = MutableStateFlow(
        (container.bookmarkAndHistoryRepository.observeLocalBookmarks() as? StateFlow<List<BookmarkedTopic>>)?.value
            ?.mapTo(HashSet()) { it.topicId }
            ?: emptySet(),
    )
    private val cachedRepoBookmarkedIds = MutableStateFlow(
        (container.bookmarkAndHistoryRepository.observeBookmarkedIds() as? StateFlow<Set<Long>>)?.value
            ?: emptySet(),
    )

    @Volatile
    private var hasReceivedLocalBookmarks =
        container.bookmarkAndHistoryRepository.observeLocalBookmarks() is StateFlow<*>

    @Volatile
    private var hasReceivedRepoBookmarks =
        container.bookmarkAndHistoryRepository.observeBookmarkedIds() is StateFlow<*>

    private suspend fun isTopicBookmarkedWithoutUiState(topicId: Long): Boolean {
        if (transientState.value.serverBookmarks.any { it.id == topicId }) return true
        if (topicId in cachedLocalBookmarkedIds.value || topicId in cachedRepoBookmarkedIds.value) return true
        val liveLocal = (container.bookmarkAndHistoryRepository.observeLocalBookmarks() as? StateFlow<List<BookmarkedTopic>>)?.value
        if (liveLocal?.any { it.topicId == topicId } == true) return true
        val liveIds = (container.bookmarkAndHistoryRepository.observeBookmarkedIds() as? StateFlow<Set<Long>>)?.value
        if (liveIds?.contains(topicId) == true) return true
        if (!hasReceivedRepoBookmarks) {
            val ids = container.bookmarkAndHistoryRepository.observeBookmarkedIds().firstOrNull()
            if (ids != null) {
                cachedRepoBookmarkedIds.value = ids
                hasReceivedRepoBookmarks = true
                if (topicId in ids) return true
            }
        }
        if (!hasReceivedLocalBookmarks) {
            val localList = container.bookmarkAndHistoryRepository.observeLocalBookmarks().firstOrNull()
            if (localList != null) {
                val localIds = localList.mapTo(HashSet(localList.size)) { it.topicId }
                cachedLocalBookmarkedIds.value = localIds
                hasReceivedLocalBookmarks = true
                if (topicId in localIds) return true
            }
        }
        return container.bookmarkAndHistoryRepository.getOfflineBookmarkSnapshot(topicId) != null
    }

    init {
        viewModelScope.launch {
            container.bookmarkAndHistoryRepository.observeLocalBookmarks().collect { list ->
                cachedLocalBookmarkedIds.value = list.mapTo(HashSet(list.size)) { it.topicId }
                hasReceivedLocalBookmarks = true
            }
        }
        viewModelScope.launch {
            container.bookmarkAndHistoryRepository.observeBookmarkedIds().collect { ids ->
                cachedRepoBookmarkedIds.value = ids
                hasReceivedRepoBookmarks = true
            }
        }
        viewModelScope.launch {
            container.sessionRepository.sessionState.collect { session ->
                val uid = session?.uid
                if (uid != lastObservedUid) {
                    lastObservedUid = uid
                    telemetryGeneration++
                    val expectedGen = telemetryGeneration
                    cancelActiveTelemetryJobs()
                    container.checkInRepository.clearCachedStatus()
                    lastTelemetryRefreshedAtMs = 0L
                    lastTelemetryBeijingDate = null
                    transientState.update {
                        it.copy(
                            isRefreshing = false,
                            isLoadingCheckInStatus = false,
                            dailyQuota = null,
                            isCheckingIn = false,
                            serverBookmarks = emptyList(),
                            serverBookmarksPage = 1,
                            hasMoreServerBookmarks = false,
                            isLoadingServerBookmarks = false,
                        )
                    }
                    if (uid != null) {
                        activeTelemetryJob = viewModelScope.launch {
                            loadSignedInTelemetry(
                                expectedUid = uid,
                                expectedGen = expectedGen,
                                resetBookmarks = true,
                                forceCheckInRefresh = true,
                            )
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            container.sessionRepository.authVerifiedEpoch.collect { epoch ->
                val session = container.sessionRepository.sessionState.value
                if (epoch > 0L && session != null) {
                    val expectedUid = session.uid
                    val expectedGen = telemetryGeneration
                    activeAuthTelemetryJob?.cancel()
                    activeAuthTelemetryJob = viewModelScope.launch {
                        loadSignedInTelemetry(
                            expectedUid = expectedUid,
                            expectedGen = expectedGen,
                            resetBookmarks = false,
                            forceCheckInRefresh = true,
                        )
                    }
                }
            }
        }
    }

    private fun cancelActiveTelemetryJobs() {
        activeTelemetryJob?.cancel()
        activeTelemetryJob = null
        activeAuthTelemetryJob?.cancel()
        activeAuthTelemetryJob = null
        activeRefreshJob?.cancel()
        activeRefreshJob = null
        activeLoadMoreBookmarksJob?.cancel()
        activeLoadMoreBookmarksJob = null
        activeHubVisibleJob?.cancel()
        activeHubVisibleJob = null
    }

    private fun isCurrentTelemetryContext(expectedUid: Long, expectedGen: Long): Boolean =
        container.sessionRepository.sessionState.value?.uid == expectedUid &&
            telemetryGeneration == expectedGen

    fun onHubVisible() {
        val now = System.currentTimeMillis()
        val session = container.sessionRepository.sessionState.value ?: return
        if (session.uid <= 0L) return

        val todayBeijing = Instant.ofEpochMilli(now).atZone(BEIJING_ZONE).toLocalDate()
        val dateRolledOver = lastTelemetryBeijingDate != null && lastTelemetryBeijingDate != todayBeijing
        if (dateRolledOver) {
            container.checkInRepository.clearCachedStatus()
            transientState.update { it.copy(dailyQuota = null) }
        }

        val currentStatus = container.checkInRepository.checkInStatus.value
        val shouldRefresh = dateRolledOver ||
            currentStatus == null ||
            (now - lastTelemetryRefreshedAtMs) >= HUB_VISIBLE_REFRESH_THROTTLE_MS

        if (shouldRefresh && !transientState.value.isRefreshing) {
            val expectedUid = session.uid
            val expectedGen = telemetryGeneration
            activeHubVisibleJob?.cancel()
            activeHubVisibleJob = viewModelScope.launch {
                loadSignedInTelemetry(
                    expectedUid = expectedUid,
                    expectedGen = expectedGen,
                    resetBookmarks = false,
                    forceCheckInRefresh = true,
                )
            }
        }
    }

    fun selectCollectionTab(tab: HubCollectionTab) {
        transientState.update { it.copy(selectedTab = tab) }
    }

    fun refresh() = refreshAll()

    fun refreshAll() {
        val initialUid = container.sessionRepository.sessionState.value?.uid
        val initialGen = telemetryGeneration
        activeRefreshJob?.cancel()
        activeRefreshJob = viewModelScope.launch {
            transientState.update { it.copy(isRefreshing = true) }
            try {
                container.sessionRepository.refreshSession()
                val currentSession = container.sessionRepository.sessionState.value
                if (currentSession != null) {
                    val targetUid = currentSession.uid
                    val targetGen = if (targetUid == initialUid && telemetryGeneration == initialGen) {
                        initialGen
                    } else if (targetUid == lastObservedUid) {
                        telemetryGeneration
                    } else {
                        return@launch
                    }
                    loadSignedInTelemetry(
                        expectedUid = targetUid,
                        expectedGen = targetGen,
                        resetBookmarks = true,
                        forceCheckInRefresh = true,
                    )
                }
            } finally {
                if (telemetryGeneration == initialGen) {
                    transientState.update { it.copy(isRefreshing = false) }
                }
            }
        }
    }

    private suspend fun loadSignedInTelemetry(
        expectedUid: Long,
        expectedGen: Long,
        resetBookmarks: Boolean,
        forceCheckInRefresh: Boolean,
    ) {
        if (!isCurrentTelemetryContext(expectedUid, expectedGen)) return
        val now = System.currentTimeMillis()
        lastTelemetryRefreshedAtMs = now
        lastTelemetryBeijingDate = Instant.ofEpochMilli(now).atZone(BEIJING_ZONE).toLocalDate()

        if (container.checkInRepository.checkInStatus.value == null) {
            transientState.update { it.copy(isLoadingCheckInStatus = true) }
        }
        coroutineScope {
            val checkInJob = launch {
                try {
                    container.checkInRepository.getCheckInBoard(
                        page = 1,
                        forceRefresh = forceCheckInRefresh,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                } finally {
                    if (isCurrentTelemetryContext(expectedUid, expectedGen)) {
                        transientState.update { it.copy(isLoadingCheckInStatus = false) }
                    }
                }
            }
            val quotaJob = launch {
                try {
                    container.checkInRepository.getDailyProgress()
                        .onSuccess { quota ->
                            if (isCurrentTelemetryContext(expectedUid, expectedGen)) {
                                transientState.update { it.copy(dailyQuota = quota) }
                            }
                        }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                }
            }
            val inboxJob = launch {
                try {
                    container.inboxRepository.refreshUnreadCount()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                }
            }
            if (resetBookmarks) {
                loadServerBookmarks(
                    expectedUid = expectedUid,
                    expectedGen = expectedGen,
                    page = 1,
                    append = false,
                )
            }
            checkInJob.join()
            quotaJob.join()
            inboxJob.join()
        }
    }

    fun loadMoreServerBookmarks() {
        val expectedUid = container.sessionRepository.sessionState.value?.uid ?: return
        val expectedGen = telemetryGeneration
        val snapshot = transientState.value
        if (snapshot.isLoadingServerBookmarks || !snapshot.hasMoreServerBookmarks) return
        activeLoadMoreBookmarksJob?.cancel()
        activeLoadMoreBookmarksJob = viewModelScope.launch {
            loadServerBookmarks(
                expectedUid = expectedUid,
                expectedGen = expectedGen,
                page = snapshot.serverBookmarksPage + 1,
                append = true,
            )
        }
    }

    private suspend fun loadServerBookmarks(
        expectedUid: Long,
        expectedGen: Long,
        page: Int,
        append: Boolean,
    ) {
        if (!isCurrentTelemetryContext(expectedUid, expectedGen)) return
        transientState.update { it.copy(isLoadingServerBookmarks = true) }
        try {
            container.bookmarkAndHistoryRepository.getServerBookmarks(page = page)
                .onSuccess { (items, hasNext) ->
                    if (!isCurrentTelemetryContext(expectedUid, expectedGen)) return@onSuccess
                    transientState.update { state ->
                        val combined = if (append) {
                            (state.serverBookmarks + items).distinctBy { it.id }
                        } else {
                            items
                        }
                        state.copy(
                            serverBookmarks = combined,
                            serverBookmarksPage = page,
                            hasMoreServerBookmarks = hasNext,
                            isLoadingServerBookmarks = false,
                        )
                    }
                }
                .onFailure {
                    if (!isCurrentTelemetryContext(expectedUid, expectedGen)) return@onFailure
                    transientState.update { it.copy(isLoadingServerBookmarks = false) }
                }
        } finally {
            if (isCurrentTelemetryContext(expectedUid, expectedGen)) {
                transientState.update {
                    if (it.isLoadingServerBookmarks) it.copy(isLoadingServerBookmarks = false) else it
                }
            }
        }
    }

    fun setPreferRandomCheckIn(preferRandom: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setPreferRandomCheckIn(preferRandom)
        }
    }

    fun setAwayDigestEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setAwayDigestEnabled(enabled)
        }
    }

    fun setAwayDigestTriggerMode(mode: AwayDigestTriggerMode) {
        viewModelScope.launch {
            container.settingsRepository.setAwayDigestTriggerMode(mode)
        }
    }

    fun setAwayDigestThresholdMinutes(minutes: Int) {
        viewModelScope.launch {
            container.settingsRepository.setAwayDigestThresholdMinutes(minutes)
        }
    }

    fun performCheckIn() {
        val currentSession = container.sessionRepository.sessionState.value
        if (currentSession == null) {
            _events.trySend("请先登录 NodeSeek 账号后再领取每日鸡腿")
            return
        }
        if (transientState.value.isCheckingIn) return

        val expectedUid = currentSession.uid
        val expectedGen = telemetryGeneration
        val randomMode = container.settingsRepository.settings.value.preferRandomCheckIn
        viewModelScope.launch {
            transientState.update { it.copy(isCheckingIn = true) }
            try {
                container.checkInRepository.checkIn(randomReward = randomMode)
                    .onSuccess { status ->
                        if (!isCurrentTelemetryContext(expectedUid, expectedGen)) return@onSuccess
                        transientState.update { it.copy(isCheckingIn = false) }
                        val gainLabel = when (val gain = status.todayGain) {
                            null -> "签到完成"
                            0 -> "0 鸡腿"
                            else -> "+$gain 鸡腿"
                        }
                        _events.trySend(status.message?.takeIf { it.isNotBlank() } ?: "今日签到成功：$gainLabel")
                        container.sessionRepository.refreshSession()
                        if (!isCurrentTelemetryContext(expectedUid, expectedGen)) return@onSuccess
                        container.checkInRepository.getDailyProgress()
                            .onSuccess { quota ->
                                if (isCurrentTelemetryContext(expectedUid, expectedGen)) {
                                    transientState.update { it.copy(dailyQuota = quota) }
                                }
                            }
                    }
                    .onFailure { error ->
                        if (!isCurrentTelemetryContext(expectedUid, expectedGen)) return@onFailure
                        transientState.update { it.copy(isCheckingIn = false) }
                        _events.trySend(error.message ?: "签到请求失败，请稍后重试")
                    }
            } finally {
                if (isCurrentTelemetryContext(expectedUid, expectedGen)) {
                    transientState.update {
                        if (it.isCheckingIn) it.copy(isCheckingIn = false) else it
                    }
                }
            }
        }
    }

    fun removeBookmark(topicId: Long) {
        viewModelScope.launch {
            removeBookmarkInternal(topicId)
        }
    }

    private suspend fun removeBookmarkInternal(topicId: Long) {
        val isSignedIn = container.sessionRepository.sessionState.value != null
        if (isSignedIn) {
            val remoteResult = container.bookmarkAndHistoryRepository
                .setTopicBookmarked(topicId, bookmarked = false)
            if (remoteResult.isFailure) {
                val err = remoteResult.exceptionOrNull()
                _events.trySend(
                    err?.message?.takeIf { it.isNotBlank() }
                        ?: "取消云端收藏失败，请稍后重试",
                )
                return
            }
        }
        container.bookmarkAndHistoryRepository.removeLocalBookmark(topicId)
        cachedLocalBookmarkedIds.update { it - topicId }
        cachedRepoBookmarkedIds.update { it - topicId }
        transientState.update { state ->
            state.copy(
                serverBookmarks = state.serverBookmarks.filterNot { it.id == topicId },
            )
        }
        _events.trySend("已从收藏移除")
    }

    fun toggleHistoryBookmark(entry: ReadHistoryEntry) {
        viewModelScope.launch {
            if (isTopicBookmarkedWithoutUiState(entry.topicId)) {
                removeBookmarkInternal(entry.topicId)
                return@launch
            }
            val summary = TopicSummary(
                id = entry.topicId,
                title = entry.title,
                categorySlug = entry.categorySlug,
                categoryName = entry.categoryName,
                authorName = entry.authorName,
                authorAvatarUrl = entry.authorAvatarUrl,
                lastActivityIso = Instant.ofEpochMilli(entry.visitedAtEpochMillis).toString(),
                lastReadFloor = entry.lastReadFloor.takeIf { it > 0 },
                isBookmarked = true,
            )
            container.bookmarkAndHistoryRepository.saveOfflineBookmark(
                summary = summary,
                detailSnapshot = null,
                syncedWithServer = false,
            )
            cachedLocalBookmarkedIds.update { it + entry.topicId }
            if (container.sessionRepository.sessionState.value != null) {
                container.bookmarkAndHistoryRepository.setTopicBookmarked(entry.topicId, bookmarked = true)
                    .onSuccess {
                        container.bookmarkAndHistoryRepository.saveOfflineBookmark(
                            summary = summary,
                            detailSnapshot = null,
                            syncedWithServer = true,
                        )
                        _events.trySend("已加入我的收藏")
                    }
                    .onFailure { err ->
                        _events.trySend(err.message ?: "云端收藏同步未完成，已保存在本机")
                    }
            } else {
                _events.trySend("已加入我的收藏")
            }
        }
    }

    fun clearReadHistory() {
        viewModelScope.launch {
            container.bookmarkAndHistoryRepository.clearReadHistory()
            _events.trySend("浏览记录已清空")
        }
    }

    fun openNativeSignInDialog() {
        transientState.update {
            it.copy(
                nativeSignIn = NativeSignInDialogState(isVisible = true),
            )
        }
    }

    fun dismissNativeSignInDialog(cancelPendingAddAccount: Boolean = true) {
        transientState.update {
            it.copy(nativeSignIn = NativeSignInDialogState(isVisible = false))
        }
        if (cancelPendingAddAccount && multiAccountManager.pendingAddAccountFlow.value) {
            cancelPendingAddAccountIfUnauthenticated()
        }
    }

    fun switchAccount(
        targetUid: Long,
        onChallengeRequired: () -> Unit = {},
    ) {
        viewModelScope.launch {
            when (val result = multiAccountManager.switchAccount(targetUid)) {
                is AccountSwitchResult.Switched -> {
                    _events.trySend("已切换至账号：${result.session.username}")
                }
                is AccountSwitchResult.ChallengeRequired -> {
                    _events.trySend("已切换至 ${result.session.username}，请先完成安全验证")
                    onChallengeRequired()
                }
                is AccountSwitchResult.OfflineUsingSnapshot -> {
                    _events.trySend("离线模式：已使用本地快照切换至 ${result.session.username}")
                }
                is AccountSwitchResult.TargetExpiredRolledBack -> {
                    val rollbackHint = result.restoredSession?.let { "，已恢复当前账号 ${it.username}" } ?: ""
                    _events.trySend("账号 ${result.expiredUsername} 登录已过期，请重新登录$rollbackHint")
                }
                is AccountSwitchResult.Failure -> {
                    _events.trySend(result.message)
                }
            }
        }
    }

    fun beginAddAccountWithWebView(onReadyToOpenWebView: () -> Unit) {
        viewModelScope.launch {
            multiAccountManager.beginAddAccount()
                .onSuccess { onReadyToOpenWebView() }
                .onFailure { err ->
                    _events.trySend(err.message ?: "无法准备添加账号")
                }
        }
    }

    fun beginAddAccountWithNativeSignIn() {
        viewModelScope.launch {
            multiAccountManager.beginAddAccount()
                .onSuccess { openNativeSignInDialog() }
                .onFailure { err ->
                    _events.trySend(err.message ?: "无法准备添加账号")
                }
        }
    }

    fun cancelPendingAddAccountIfUnauthenticated() {
        viewModelScope.launch {
            val restored = multiAccountManager.cancelAddAccountIfUnauthenticated()
            if (restored != null) {
                _events.trySend("已取消添加账号，恢复至 ${restored.username}")
            }
        }
    }

    fun removeSavedAccount(uid: Long) {
        viewModelScope.launch {
            multiAccountManager.removeAccount(uid)
                .onSuccess {
                    _events.trySend("已移除该账号的本地登录凭证")
                }
                .onFailure { err ->
                    _events.trySend(err.message ?: "移除账号失败")
                }
        }
    }

    fun returnToCredentialsStep() {
        transientState.update { state ->
            state.copy(
                nativeSignIn = state.nativeSignIn.copy(
                    isSubmitting = false,
                    otpSession = null,
                    errorMessage = null,
                    turnstileResetNonce = state.nativeSignIn.turnstileResetNonce + 1,
                ),
            )
        }
    }

    fun submitCredentials(
        username: String,
        password: String,
        turnstileToken: String,
    ) {
        val trimmedUser = username.trim()
        val trimmedToken = turnstileToken.trim()
        if (trimmedUser.isEmpty() || password.isEmpty()) {
            transientState.update {
                it.copy(
                    nativeSignIn = it.nativeSignIn.copy(errorMessage = "请输入用户名与密码"),
                )
            }
            return
        }
        if (trimmedToken.isEmpty()) {
            transientState.update {
                it.copy(
                    nativeSignIn = it.nativeSignIn.copy(errorMessage = "正在等待 Cloudflare 安全验证完成，请稍候或点击验证框刷新重试"),
                )
            }
            return
        }
        viewModelScope.launch {
            transientState.update {
                it.copy(
                    nativeSignIn = it.nativeSignIn.copy(isSubmitting = true, errorMessage = null),
                )
            }
            container.sessionRepository.signIn(
                username = trimmedUser,
                password = password,
                turnstileToken = trimmedToken,
            ).onSuccess { result ->
                when (result) {
                    SignInStepResult.Authenticated -> {
                        transientState.update {
                            it.copy(nativeSignIn = NativeSignInDialogState(isVisible = false))
                        }
                        _events.trySend("欢迎回来，$trimmedUser")
                    }
                    is SignInStepResult.TwoFactorRequired -> {
                        transientState.update {
                            it.copy(
                                nativeSignIn = it.nativeSignIn.copy(
                                    isSubmitting = false,
                                    otpSession = result.otpSession,
                                    errorMessage = null,
                                ),
                            )
                        }
                    }
                }
            }.onFailure { error ->
                transientState.update { state ->
                    state.copy(
                        nativeSignIn = state.nativeSignIn.copy(
                            isSubmitting = false,
                            errorMessage = error.message
                                ?: "登录失败，请检查账号密码或改用「WebView 官方网页登录」",
                            turnstileResetNonce = state.nativeSignIn.turnstileResetNonce + 1,
                        ),
                    )
                }
            }
        }
    }

    fun submitTwoFactorCode(otpCode: String) {
        val otpSession = transientState.value.nativeSignIn.otpSession ?: return
        val cleaned = otpCode.trim()
        if (cleaned.isEmpty()) {
            transientState.update {
                it.copy(nativeSignIn = it.nativeSignIn.copy(errorMessage = "请输入两步验证码"))
            }
            return
        }
        viewModelScope.launch {
            transientState.update {
                it.copy(nativeSignIn = it.nativeSignIn.copy(isSubmitting = true, errorMessage = null))
            }
            container.sessionRepository.verifyTwoFactor(
                otpCode = cleaned,
                otpSession = otpSession,
            ).onSuccess {
                transientState.update {
                    it.copy(nativeSignIn = NativeSignInDialogState(isVisible = false))
                }
                _events.trySend("两步验证通过，已成功登录")
            }.onFailure { error ->
                val isExpired = (error as? NodeSeekApiException)?.isOtpExpired == true ||
                    error.message?.contains("已过期") == true ||
                    error.message?.contains("OTP_EXPIRED_OR_NOT_EXIST", ignoreCase = true) == true
                transientState.update { state ->
                    state.copy(
                        nativeSignIn = state.nativeSignIn.copy(
                            isSubmitting = false,
                            otpSession = if (isExpired) null else state.nativeSignIn.otpSession,
                            turnstileResetNonce = if (isExpired) {
                                state.nativeSignIn.turnstileResetNonce + 1
                            } else {
                                state.nativeSignIn.turnstileResetNonce
                            },
                            errorMessage = if (isExpired) {
                                "两步验证会话已过期，请重新完成人机验证并登录"
                            } else {
                                error.message ?: "验证码校验失败，请重试"
                            },
                        ),
                    )
                }
            }
        }
    }

    fun onWebViewAuthCompleted() {
        viewModelScope.launch {
            container.sessionRepository.onWebViewAuthCompleted()
                .onSuccess { session ->
                    if (session != null) {
                        _events.trySend("已连接账号：${session.username}")
                        val expectedUid = session.uid
                        val expectedGen = telemetryGeneration
                        if (isCurrentTelemetryContext(expectedUid, expectedGen)) {
                            activeTelemetryJob?.cancel()
                            activeTelemetryJob = viewModelScope.launch {
                                loadSignedInTelemetry(
                                    expectedUid = expectedUid,
                                    expectedGen = expectedGen,
                                    resetBookmarks = true,
                                    forceCheckInRefresh = true,
                                )
                            }
                        }
                    }
                }
        }
    }

    private data class InboxHubData(
        val unreadTotal: Int,
        val replies: List<NotificationItem>,
        val mentions: List<NotificationItem>,
        val threads: List<DirectMessageSummary>,
    )

    private data class BaseHubData(
        val session: AccountSession?,
        val settings: AppSettings,
        val inboxData: InboxHubData,
        val localBookmarks: List<BookmarkedTopic>,
        val history: List<ReadHistoryEntry>,
    )

    companion object {
        private val BEIJING_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        private const val HUB_VISIBLE_REFRESH_THROTTLE_MS = 4_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PulseHubViewModel(container) as T
                }
            }
    }
}

internal fun mergeHubBookmarks(
    local: List<BookmarkedTopic>,
    server: List<TopicSummary>,
    history: List<ReadHistoryEntry>,
): List<HubBookmarkDisplayItem> {
    val historyById = history.associateBy { it.topicId }
    val byId = LinkedHashMap<Long, HubBookmarkDisplayItem>()
    for (item in local) {
        val hist = historyById[item.topicId]
        val mergedSummary = item.summary.copy(
            isBookmarked = true,
            categorySlug = item.summary.categorySlug.ifBlank { hist?.categorySlug.orEmpty() },
            categoryName = item.summary.categoryName.ifBlank { hist?.categoryName.orEmpty() },
            authorName = item.summary.authorName.ifBlank { hist?.authorName.orEmpty() },
            authorAvatarUrl = item.summary.authorAvatarUrl.ifBlank { hist?.authorAvatarUrl.orEmpty() },
            lastReadFloor = item.summary.lastReadFloor ?: hist?.lastReadFloor?.takeIf { it > 0 },
        )
        byId[item.topicId] = HubBookmarkDisplayItem(
            topicId = item.topicId,
            summary = mergedSummary,
            hasOfflineSnapshot = item.hasOfflineSnapshot,
            savedAtEpochMillis = item.savedAtEpochMillis,
        )
    }
    for (summary in server) {
        val existing = byId[summary.id]
        val hist = historyById[summary.id]
        if (existing == null) {
            byId[summary.id] = HubBookmarkDisplayItem(
                topicId = summary.id,
                summary = summary.copy(
                    isBookmarked = true,
                    categorySlug = summary.categorySlug.ifBlank { hist?.categorySlug.orEmpty() },
                    categoryName = summary.categoryName.ifBlank { hist?.categoryName.orEmpty() },
                    authorName = summary.authorName.ifBlank { hist?.authorName.orEmpty() },
                    authorAvatarUrl = summary.authorAvatarUrl.ifBlank { hist?.authorAvatarUrl.orEmpty() },
                    lastReadFloor = summary.lastReadFloor ?: hist?.lastReadFloor?.takeIf { it > 0 },
                ),
                hasOfflineSnapshot = false,
                savedAtEpochMillis = null,
            )
        } else {
            val prev = existing.summary
            byId[summary.id] = existing.copy(
                summary = summary.copy(
                    categorySlug = summary.categorySlug.ifBlank { prev.categorySlug },
                    categoryName = summary.categoryName.ifBlank { prev.categoryName },
                    authorId = summary.authorId ?: prev.authorId,
                    authorName = summary.authorName.ifBlank { prev.authorName },
                    authorAvatarUrl = summary.authorAvatarUrl.ifBlank { prev.authorAvatarUrl },
                    authorLevel = summary.authorLevel ?: prev.authorLevel,
                    viewCount = if (summary.viewCount > 0) summary.viewCount else prev.viewCount,
                    replyCount = if (summary.replyCount > 0) summary.replyCount else prev.replyCount,
                    lastActivityIso = summary.lastActivityIso.ifBlank { prev.lastActivityIso },
                    requiredViewLevel = summary.requiredViewLevel ?: prev.requiredViewLevel,
                    isBookmarked = true,
                    isRead = prev.isRead || summary.isRead,
                    lastReadFloor = prev.lastReadFloor ?: summary.lastReadFloor ?: hist?.lastReadFloor?.takeIf { it > 0 },
                ),
            )
        }
    }
    return byId.values.toList()
}
