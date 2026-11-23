package com.nodeseek.app.ui.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.AwayDigestTriggerMode
import com.nodeseek.app.domain.model.BookmarkedTopic
import com.nodeseek.app.domain.model.CheckInStatus
import com.nodeseek.app.domain.model.DailyQuotaProgress
import com.nodeseek.app.domain.model.DirectMessageSummary
import com.nodeseek.app.domain.model.NotificationItem
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.repository.SignInStepResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    val seekMailboxCount: Int = 0,
    val seekMailPrimaryAddress: String = "",
    val seekMailUnreadIds: Set<Long> = emptySet(),
    val seekMailHasMoreUnread: Boolean = false,
    val seekMailLoading: Boolean = false,
    val seekMailError: String? = null,
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
    val seekMailConfigured: Boolean = false,
    val seekMailboxCount: Int = 0,
    val seekMailPrimaryAddress: String = "",
    val seekMailUnreadCount: Int = 0,
    val seekMailHasMoreUnread: Boolean = false,
    val seekMailLoading: Boolean = false,
    val seekMailError: String? = null,
    val unreadNotificationCount: Int = 0,
    val unreadDirectMessageCount: Int = 0,
)

class PulseHubViewModel(
    private val container: AppContainer,
) : ViewModel() {

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
        container.seekMailRepository.locallyReadMailIds,
    ) { base, transient, sharedCheckInStatus, locallyReadMailIds ->
        val mergedBookmarks = mergeBookmarks(
            local = base.localBookmarks,
            server = transient.serverBookmarks,
            history = base.history,
        )
        val seekMailConfigured = base.settings.seekMailApiKey.isNotBlank()
        val effectiveSeekMailUnreadCount = if (seekMailConfigured) {
            transient.seekMailUnreadIds.count { it !in locallyReadMailIds }
        } else {
            0
        }
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
            seekMailConfigured = seekMailConfigured,
            seekMailboxCount = if (seekMailConfigured) transient.seekMailboxCount else 0,
            seekMailPrimaryAddress = if (seekMailConfigured) transient.seekMailPrimaryAddress else "",
            seekMailUnreadCount = effectiveSeekMailUnreadCount,
            seekMailHasMoreUnread = seekMailConfigured && transient.seekMailHasMoreUnread,
            seekMailLoading = seekMailConfigured && transient.seekMailLoading,
            seekMailError = if (seekMailConfigured) transient.seekMailError else null,
            unreadNotificationCount = notifUnread,
            unreadDirectMessageCount = dmUnread,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = PulseHubUiState(),
    )

    private var lastObservedUid: Long? = null
    private var lastTelemetryRefreshedAtMs: Long = 0L
    private var lastTelemetryBeijingDate: LocalDate? = null

    init {
        viewModelScope.launch {
            container.sessionRepository.sessionState.collect { session ->
                val uid = session?.uid
                if (uid != lastObservedUid) {
                    lastObservedUid = uid
                    if (uid != null) {
                        loadSignedInTelemetry(
                            resetBookmarks = true,
                            forceCheckInRefresh = true,
                        )
                    } else {
                        container.checkInRepository.clearCachedStatus()
                        lastTelemetryRefreshedAtMs = 0L
                        lastTelemetryBeijingDate = null
                        transientState.update {
                            it.copy(
                                isLoadingCheckInStatus = false,
                                dailyQuota = null,
                                serverBookmarks = emptyList(),
                                serverBookmarksPage = 1,
                                hasMoreServerBookmarks = false,
                            )
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            container.sessionRepository.authVerifiedEpoch.collect { epoch ->
                if (epoch > 0L && container.sessionRepository.sessionState.value != null) {
                    loadSignedInTelemetry(
                        resetBookmarks = false,
                        forceCheckInRefresh = true,
                    )
                }
            }
        }
    }

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
            viewModelScope.launch {
                loadSignedInTelemetry(
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
        viewModelScope.launch {
            transientState.update { it.copy(isRefreshing = true) }
            container.sessionRepository.refreshSession()
            if (container.sessionRepository.sessionState.value != null) {
                loadSignedInTelemetry(
                    resetBookmarks = true,
                    forceCheckInRefresh = true,
                )
            }
            transientState.update { it.copy(isRefreshing = false) }
        }
    }

    private suspend fun loadSignedInTelemetry(
        resetBookmarks: Boolean,
        forceCheckInRefresh: Boolean,
    ) {
        val now = System.currentTimeMillis()
        lastTelemetryRefreshedAtMs = now
        lastTelemetryBeijingDate = Instant.ofEpochMilli(now).atZone(BEIJING_ZONE).toLocalDate()

        if (container.checkInRepository.checkInStatus.value == null) {
            transientState.update { it.copy(isLoadingCheckInStatus = true) }
        }
        val checkInJob = viewModelScope.launch {
            try {
                container.checkInRepository.getCheckInBoard(
                    page = 1,
                    forceRefresh = forceCheckInRefresh,
                )
            } finally {
                transientState.update { it.copy(isLoadingCheckInStatus = false) }
            }
        }
        val quotaJob = viewModelScope.launch {
            container.checkInRepository.getDailyProgress()
                .onSuccess { quota ->
                    transientState.update { it.copy(dailyQuota = quota) }
                }
        }
        val inboxJob = viewModelScope.launch {
            container.inboxRepository.refreshUnreadCount()
        }
        if (resetBookmarks) {
            loadServerBookmarks(page = 1, append = false)
        }
        checkInJob.join()
        quotaJob.join()
        inboxJob.join()
    }

    fun loadMoreServerBookmarks() {
        val snapshot = transientState.value
        if (snapshot.isLoadingServerBookmarks || !snapshot.hasMoreServerBookmarks) return
        viewModelScope.launch {
            loadServerBookmarks(page = snapshot.serverBookmarksPage + 1, append = true)
        }
    }

    private suspend fun loadServerBookmarks(page: Int, append: Boolean) {
        if (container.sessionRepository.sessionState.value == null) return
        transientState.update { it.copy(isLoadingServerBookmarks = true) }
        container.bookmarkAndHistoryRepository.getServerBookmarks(page = page)
            .onSuccess { (items, hasNext) ->
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
                transientState.update { it.copy(isLoadingServerBookmarks = false) }
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

        val randomMode = container.settingsRepository.settings.value.preferRandomCheckIn
        viewModelScope.launch {
            transientState.update { it.copy(isCheckingIn = true) }
            container.checkInRepository.checkIn(randomReward = randomMode)
                .onSuccess { status ->
                    transientState.update { it.copy(isCheckingIn = false) }
                    val gainLabel = when (val gain = status.todayGain) {
                        null -> "签到完成"
                        0 -> "0 鸡腿"
                        else -> "+$gain 鸡腿"
                    }
                    _events.trySend(status.message?.takeIf { it.isNotBlank() } ?: "今日签到成功：$gainLabel")
                    container.sessionRepository.refreshSession()
                    container.checkInRepository.getDailyProgress()
                        .onSuccess { quota -> transientState.update { it.copy(dailyQuota = quota) } }
                }
                .onFailure { error ->
                    transientState.update { it.copy(isCheckingIn = false) }
                    _events.trySend(error.message ?: "签到请求失败，请稍后重试")
                }
        }
    }

    fun removeBookmark(topicId: Long) {
        viewModelScope.launch {
            container.bookmarkAndHistoryRepository.removeLocalBookmark(topicId)
            if (container.sessionRepository.sessionState.value != null) {
                container.bookmarkAndHistoryRepository.setTopicBookmarked(topicId, bookmarked = false)
            }
            transientState.update { state ->
                state.copy(
                    serverBookmarks = state.serverBookmarks.filterNot { it.id == topicId },
                )
            }
            _events.trySend("已从收藏移除")
        }
    }

    fun toggleHistoryBookmark(entry: ReadHistoryEntry) {
        val currentlyBookmarked = uiState.value.bookmarks.any { it.topicId == entry.topicId }
        if (currentlyBookmarked) {
            removeBookmark(entry.topicId)
            return
        }
        viewModelScope.launch {
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
            if (container.sessionRepository.sessionState.value != null) {
                container.bookmarkAndHistoryRepository.setTopicBookmarked(entry.topicId, bookmarked = true)
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

    fun dismissNativeSignInDialog() {
        transientState.update {
            it.copy(nativeSignIn = NativeSignInDialogState(isVisible = false))
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
                        loadSignedInTelemetry(
                            resetBookmarks = true,
                            forceCheckInRefresh = true,
                        )
                    }
                }
        }
    }

    private fun mergeBookmarks(
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
