package com.nodeseek.app.ui.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.model.DirectMessageSummary
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.NotificationItem
import com.nodeseek.app.domain.model.NotificationKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class InboxTab(val index: Int, val label: String) {
    REPLIES(0, "回复我"),
    MENTIONS(1, "@我"),
    DIRECT_MESSAGES(2, "私信");

    companion object {
        fun fromIndex(index: Int): InboxTab =
            entries.firstOrNull { it.index == index } ?: REPLIES
    }
}

data class InboxUiState(
    val selectedTab: InboxTab = InboxTab.REPLIES,
    val replies: List<NotificationItem> = emptyList(),
    val mentions: List<NotificationItem> = emptyList(),
    val messageThreads: List<DirectMessageSummary> = emptyList(),
    val isRefreshing: Boolean = false,
    val isMarkingAllRead: Boolean = false,
    val errorMessage: String? = null,
    val showNewMessageDialog: Boolean = false,
    val userSearchResults: List<FloorAuthor> = emptyList(),
    val isSearchingUsers: Boolean = false,
    val userSearchError: String? = null,
    val serverUnreadTotalCount: Int = 0,
) {
    val unreadRepliesCount: Int get() = replies.count { !it.isRead }
    val unreadMentionsCount: Int get() = mentions.count { !it.isRead }
    val unreadMessagesCount: Int
        get() = messageThreads.sumOf { thread ->
            if (thread.isRead) 0 else thread.unreadCount.coerceAtLeast(1)
        }
    val totalUnreadCount: Int
        get() = maxOf(
            unreadRepliesCount + unreadMentionsCount + unreadMessagesCount,
            serverUnreadTotalCount,
        )
}

class InboxViewModel(
    private val container: AppContainer,
    initialTabIndex: Int = 0,
) : ViewModel() {

    private data class InboxLocalState(
        val selectedTab: InboxTab,
        val isRefreshing: Boolean = false,
        val isMarkingAllRead: Boolean = false,
        val errorMessage: String? = null,
        val showNewMessageDialog: Boolean = false,
        val userSearchResults: List<FloorAuthor> = emptyList(),
        val isSearchingUsers: Boolean = false,
        val userSearchError: String? = null,
    )

    private var searchUsersJob: Job? = null

    private val localState = MutableStateFlow(
        InboxLocalState(
            selectedTab = InboxTab.fromIndex(initialTabIndex),
            isRefreshing = true,
        ),
    )

    val uiState: StateFlow<InboxUiState> = combine(
        localState,
        container.inboxRepository.replyNotifications,
        container.inboxRepository.mentionNotifications,
        container.inboxRepository.messageThreads,
        container.inboxRepository.unreadTotalCount,
    ) { local, replies, mentions, threads, unreadTotal ->
        InboxUiState(
            selectedTab = local.selectedTab,
            replies = replies,
            mentions = mentions,
            messageThreads = threads,
            isRefreshing = local.isRefreshing,
            isMarkingAllRead = local.isMarkingAllRead,
            errorMessage = local.errorMessage,
            showNewMessageDialog = local.showNewMessageDialog,
            userSearchResults = local.userSearchResults,
            isSearchingUsers = local.isSearchingUsers,
            userSearchError = local.userSearchError,
            serverUnreadTotalCount = unreadTotal,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = InboxUiState(
            selectedTab = InboxTab.fromIndex(initialTabIndex),
            isRefreshing = true,
        ),
    )

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var refreshJob: Job? = null
    private var lastRefreshCompletedAtMs: Long = 0L

    init {
        refreshAllTabs(silent = false)
    }

    fun selectTab(tab: InboxTab) {
        localState.update { it.copy(selectedTab = tab) }
    }

    fun refreshAllTabs(silent: Boolean = false) {
        if (silent && refreshJob?.isActive == true) return
        if (!silent) {
            refreshJob?.cancel()
        }
        refreshJob = viewModelScope.launch {
            if (!silent) {
                localState.update { it.copy(isRefreshing = true, errorMessage = null) }
            }

            val (repliesResult, mentionsResult, threadsResult) = coroutineScope {
                val repliesDeferred = async {
                    container.inboxRepository.getNotifications(NotificationKind.REPLIES)
                }
                val mentionsDeferred = async {
                    container.inboxRepository.getNotifications(NotificationKind.MENTIONS)
                }
                val threadsDeferred = async {
                    container.inboxRepository.getMessageThreads()
                }
                val countDeferred = async {
                    container.inboxRepository.refreshUnreadCount()
                }
                val replies = repliesDeferred.await()
                val mentions = mentionsDeferred.await()
                val threads = threadsDeferred.await()
                countDeferred.await()
                Triple(replies, mentions, threads)
            }

            lastRefreshCompletedAtMs = System.currentTimeMillis()
            val firstError = repliesResult.exceptionOrNull()
                ?: mentionsResult.exceptionOrNull()
                ?: threadsResult.exceptionOrNull()

            localState.update { state ->
                state.copy(
                    isRefreshing = false,
                    errorMessage = if (
                        !silent &&
                        repliesResult.isFailure &&
                        mentionsResult.isFailure &&
                        threadsResult.isFailure
                    ) {
                        firstError?.message ?: "无法加载消息收件箱"
                    } else if (silent) {
                        state.errorMessage
                    } else {
                        null
                    },
                )
            }
        }
    }

    fun refreshSilentlyOnResume() {
        val elapsed = System.currentTimeMillis() - lastRefreshCompletedAtMs
        if (elapsed >= RESUME_REFRESH_THROTTLE_MS) {
            refreshAllTabs(silent = true)
        }
    }

    fun markCurrentTabAllRead() {
        val currentTab = localState.value.selectedTab
        if (localState.value.isMarkingAllRead) return

        viewModelScope.launch {
            localState.update { it.copy(isMarkingAllRead = true) }
            val result = when (currentTab) {
                InboxTab.REPLIES ->
                    container.inboxRepository.markNotificationsRead(NotificationKind.REPLIES, ids = null)
                InboxTab.MENTIONS ->
                    container.inboxRepository.markNotificationsRead(NotificationKind.MENTIONS, ids = null)
                InboxTab.DIRECT_MESSAGES ->
                    container.inboxRepository.markMessagesRead(messageIds = null)
            }
            localState.update { it.copy(isMarkingAllRead = false) }
            result.onSuccess {
                _events.trySend("已将「${currentTab.label}」全部标为已读")
            }.onFailure { error ->
                _events.trySend(error.message ?: "标记已读失败")
            }
        }
    }

    fun markNotificationRead(item: NotificationItem) {
        if (item.isRead) return
        viewModelScope.launch {
            container.inboxRepository.markNotificationsRead(item.kind, ids = listOf(item.id))
        }
    }

    fun markThreadRead(peerUid: Long) {
        viewModelScope.launch {
            container.inboxRepository.markThreadRead(peerUid)
        }
    }

    fun openNewMessageDialog() {
        searchUsersJob?.cancel()
        searchUsersJob = null
        localState.update {
            it.copy(
                showNewMessageDialog = true,
                userSearchResults = emptyList(),
                isSearchingUsers = false,
                userSearchError = null,
            )
        }
    }

    fun dismissNewMessageDialog() {
        searchUsersJob?.cancel()
        searchUsersJob = null
        localState.update {
            it.copy(
                showNewMessageDialog = false,
                isSearchingUsers = false,
                userSearchError = null,
            )
        }
    }

    fun searchUsers(keyword: String) {
        searchUsersJob?.cancel()
        searchUsersJob = null
        val trimmed = keyword.trim().removePrefix("@")
        if (trimmed.isEmpty()) {
            localState.update {
                it.copy(
                    userSearchResults = emptyList(),
                    isSearchingUsers = false,
                    userSearchError = null,
                )
            }
            return
        }
        searchUsersJob = viewModelScope.launch {
            localState.update { it.copy(isSearchingUsers = true, userSearchError = null) }
            try {
                delay(USER_SEARCH_DEBOUNCE_MS)
                container.userRepository.searchUsers(trimmed)
                    .onSuccess { users ->
                        localState.update {
                            it.copy(
                                userSearchResults = users,
                                isSearchingUsers = false,
                                userSearchError = null,
                            )
                        }
                    }
                    .onFailure { error ->
                        localState.update {
                            it.copy(
                                userSearchResults = emptyList(),
                                isSearchingUsers = false,
                                userSearchError = error.message?.takeIf { msg -> msg.isNotBlank() }
                                    ?: "搜索成员失败，请检查网络或重试",
                            )
                        }
                    }
            } catch (ce: CancellationException) {
                throw ce
            }
        }
    }

    companion object {
        private const val RESUME_REFRESH_THROTTLE_MS = 1_200L
        internal const val USER_SEARCH_DEBOUNCE_MS = 300L

        fun factory(
            container: AppContainer,
            initialTabIndex: Int = 0,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return InboxViewModel(container, initialTabIndex) as T
            }
        }
    }
}
