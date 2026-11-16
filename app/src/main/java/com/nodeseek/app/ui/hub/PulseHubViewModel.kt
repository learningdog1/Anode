package com.nodeseek.app.ui.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.BookmarkedTopic
import com.nodeseek.app.domain.model.CheckInStatus
import com.nodeseek.app.domain.model.DailyQuotaProgress
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.repository.SignInStepResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
)

class PulseHubViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val transientState = MutableStateFlow(PulseHubTransientState())

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val baseDataFlow = combine(
        container.sessionRepository.sessionState,
        container.settingsRepository.settings,
        container.inboxRepository.unreadTotalCount,
        container.bookmarkAndHistoryRepository.observeLocalBookmarks(),
        container.bookmarkAndHistoryRepository.observeReadHistory(),
    ) { session, settings, unreadCount, localBookmarks, history ->
        BaseHubData(
            session = session,
            settings = settings,
            unreadCount = unreadCount,
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
        )
        val seekMailConfigured = base.settings.seekMailApiKey.isNotBlank()
        val effectiveSeekMailUnreadCount = if (seekMailConfigured) {
            transient.seekMailUnreadIds.count { it !in locallyReadMailIds }
        } else {
            0
        }
        PulseHubUiState(
            session = base.session,
            settings = base.settings,
            unreadInboxCount = if (base.session != null) base.unreadCount else 0,
            selectedTab = transient.selectedTab,
            isRefreshing = transient.isRefreshing,
            isLoadingCheckInStatus = transient.isLoadingCheckInStatus,
            checkInStatus = if (base.session != null) sharedCheckInStatus else null,
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
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = PulseHubUiState(),
    )

    private var lastObservedUid: Long? = null
    private var lastTelemetryRefreshedAtMs: Long = 0L
    private var lastTelemetryBeijingDate: LocalDate? = null
    private var lastObservedSeekMailKey: String = ""
    private var lastSeekMailRefreshedAtMs: Long = 0L
    private var seekMailRefreshJob: Job? = null

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

        viewModelScope.launch {
            container.settingsRepository.settings
                .map { it.seekMailApiKey.trim() }
                .distinctUntilChanged()
                .collect { apiKey ->
                    if (apiKey.isEmpty()) {
                        seekMailRefreshJob?.cancel()
                        lastObservedSeekMailKey = ""
                        lastSeekMailRefreshedAtMs = 0L
                        transientState.update {
                            it.copy(
                                seekMailboxCount = 0,
                                seekMailPrimaryAddress = "",
                                seekMailUnreadIds = emptySet(),
                                seekMailHasMoreUnread = false,
                                seekMailLoading = false,
                                seekMailError = null,
                            )
                        }
                    } else {
                        if (lastObservedSeekMailKey != apiKey) {
                            seekMailRefreshJob?.cancel()
                            lastObservedSeekMailKey = apiKey
                            lastSeekMailRefreshedAtMs = 0L
                            transientState.update {
                                it.copy(
                                    seekMailboxCount = 0,
                                    seekMailPrimaryAddress = "",
                                    seekMailUnreadIds = emptySet(),
                                    seekMailHasMoreUnread = false,
                                    seekMailError = null,
                                )
                            }
                        }
                        launchSeekMailRefresh(
                            expectedKey = apiKey,
                            forceRefresh = false,
                        )
                    }
                }
        }
    }

    fun onHubVisible() {
        val now = System.currentTimeMillis()
        val seekMailKey = container.settingsRepository.settings.value.seekMailApiKey.trim()
        if (seekMailKey.isNotEmpty() &&
            !transientState.value.seekMailLoading &&
            (lastSeekMailRefreshedAtMs == 0L || (now - lastSeekMailRefreshedAtMs) >= HUB_VISIBLE_REFRESH_THROTTLE_MS)
        ) {
            launchSeekMailRefresh(
                expectedKey = seekMailKey,
                forceRefresh = false,
            )
        }

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
            val seekMailKey = container.settingsRepository.settings.value.seekMailApiKey.trim()
            val seekMailJob = if (seekMailKey.isNotEmpty()) {
                seekMailRefreshJob?.cancel()
                viewModelScope.launch {
                    loadSeekMailSummary(
                        expectedKey = seekMailKey,
                        forceRefresh = true,
                    )
                }.also { seekMailRefreshJob = it }
            } else {
                null
            }
            container.sessionRepository.refreshSession()
            if (container.sessionRepository.sessionState.value != null) {
                loadSignedInTelemetry(
                    resetBookmarks = true,
                    forceCheckInRefresh = true,
                )
            }
            seekMailJob?.join()
            transientState.update { it.copy(isRefreshing = false) }
        }
    }

    private fun launchSeekMailRefresh(
        expectedKey: String,
        forceRefresh: Boolean,
    ) {
        if (expectedKey.isEmpty()) return
        if (!forceRefresh && seekMailRefreshJob?.isActive == true && lastObservedSeekMailKey == expectedKey) {
            return
        }
        seekMailRefreshJob?.cancel()
        seekMailRefreshJob = viewModelScope.launch {
            loadSeekMailSummary(
                expectedKey = expectedKey,
                forceRefresh = forceRefresh,
            )
        }
    }

    private suspend fun loadSeekMailSummary(
        expectedKey: String,
        forceRefresh: Boolean,
    ) {
        if (expectedKey.isEmpty()) return
        lastSeekMailRefreshedAtMs = System.currentTimeMillis()
        transientState.update { it.copy(seekMailLoading = true) }
        try {
            coroutineScope {
                val mailboxesDeferred = async {
                    container.seekMailRepository.getMailboxes(forceRefresh = forceRefresh)
                }
                val unreadDeferred = async {
                    container.seekMailRepository.getUnreadMails(limit = 20)
                }
                val mailboxesResult = mailboxesDeferred.await()
                val unreadResult = unreadDeferred.await()

                val activeKey = container.settingsRepository.settings.value.seekMailApiKey.trim()
                if (activeKey != expectedKey) return@coroutineScope

                val mailboxes = mailboxesResult.getOrNull()
                val unreadPage = unreadResult.getOrNull()
                val primaryAddress = mailboxes
                    ?.let { list -> list.firstOrNull { it.isPinned } ?: list.firstOrNull() }
                    ?.address
                    .orEmpty()
                val firstError = mailboxesResult.exceptionOrNull() ?: unreadResult.exceptionOrNull()

                transientState.update { state ->
                    state.copy(
                        seekMailboxCount = mailboxes?.size ?: state.seekMailboxCount,
                        seekMailPrimaryAddress = if (mailboxes != null) primaryAddress else state.seekMailPrimaryAddress,
                        seekMailUnreadIds = unreadPage?.items?.filterNot { it.isRead }?.map { it.id }?.toSet()
                            ?: state.seekMailUnreadIds,
                        seekMailHasMoreUnread = unreadPage?.hasMore ?: state.seekMailHasMoreUnread,
                        seekMailLoading = false,
                        seekMailError = if (mailboxes == null && unreadPage == null) {
                            firstError?.message?.takeIf { it.isNotBlank() } ?: "Seek 邮箱连接暂不可用"
                        } else {
                            null
                        },
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } finally {
            val activeKey = container.settingsRepository.settings.value.seekMailApiKey.trim()
            if (activeKey == expectedKey) {
                transientState.update { it.copy(seekMailLoading = false) }
            }
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
                    nativeSignIn = it.nativeSignIn.copy(errorMessage = "请先完成下方 Cloudflare 人机验证"),
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
    ): List<HubBookmarkDisplayItem> {
        val byId = LinkedHashMap<Long, HubBookmarkDisplayItem>()
        for (item in local) {
            byId[item.topicId] = HubBookmarkDisplayItem(
                topicId = item.topicId,
                summary = item.summary.copy(isBookmarked = true),
                hasOfflineSnapshot = item.hasOfflineSnapshot,
                savedAtEpochMillis = item.savedAtEpochMillis,
            )
        }
        for (summary in server) {
            val existing = byId[summary.id]
            if (existing == null) {
                byId[summary.id] = HubBookmarkDisplayItem(
                    topicId = summary.id,
                    summary = summary.copy(isBookmarked = true),
                    hasOfflineSnapshot = false,
                    savedAtEpochMillis = null,
                )
            } else {
                byId[summary.id] = existing.copy(
                    summary = summary.copy(
                        isBookmarked = true,
                        isRead = existing.summary.isRead || summary.isRead,
                        lastReadFloor = existing.summary.lastReadFloor ?: summary.lastReadFloor,
                    ),
                )
            }
        }
        return byId.values.toList()
    }

    private data class BaseHubData(
        val session: AccountSession?,
        val settings: AppSettings,
        val unreadCount: Int,
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
