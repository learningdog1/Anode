package com.nodeseek.app.ui.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.data.network.NodeSeekUrlNormalizer
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.TopicAccessException
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.model.UserProfile
import com.nodeseek.app.domain.model.UserReplyExcerpt
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ProfileActivityTab {
    TOPICS,
    REPLIES,
}

data class UserProfileUiState(
    val uid: Long,
    val usernameHint: String,
    val isOwnProfile: Boolean = false,
    val isSignedIn: Boolean = false,
    val profile: UserProfile? = null,
    val selectedTab: ProfileActivityTab = ProfileActivityTab.TOPICS,
    val topics: List<TopicSummary> = emptyList(),
    val topicsPage: Int = 1,
    val hasMoreTopics: Boolean = false,
    val isLoadingMoreTopics: Boolean = false,
    val replies: List<UserReplyExcerpt> = emptyList(),
    val repliesPage: Int = 1,
    val hasMoreReplies: Boolean = false,
    val isLoadingMoreReplies: Boolean = false,
    val isRefreshing: Boolean = true,
    val isUpdatingRelation: Boolean = false,
    val isSavingProfile: Boolean = false,
    val showEditDialog: Boolean = false,
    val connectionsDialogTitle: String? = null,
    val connectionsList: List<FloorAuthor> = emptyList(),
    val isLoadingConnections: Boolean = false,
    val errorMessage: String? = null,
    val isChallengeError: Boolean = false,
)

class UserProfileViewModel(
    private val container: AppContainer,
    private val uid: Long,
    private val initialUsernameHint: String,
) : ViewModel() {

    private data class InternalState(
        val profile: UserProfile? = null,
        val selectedTab: ProfileActivityTab = ProfileActivityTab.TOPICS,
        val topics: List<TopicSummary> = emptyList(),
        val topicsPage: Int = 1,
        val hasMoreTopics: Boolean = false,
        val isLoadingMoreTopics: Boolean = false,
        val replies: List<UserReplyExcerpt> = emptyList(),
        val repliesPage: Int = 1,
        val hasMoreReplies: Boolean = false,
        val isLoadingMoreReplies: Boolean = false,
        val isRefreshing: Boolean = true,
        val isUpdatingRelation: Boolean = false,
        val isSavingProfile: Boolean = false,
        val showEditDialog: Boolean = false,
        val connectionsDialogTitle: String? = null,
        val connectionsList: List<FloorAuthor> = emptyList(),
        val isLoadingConnections: Boolean = false,
        val errorMessage: String? = null,
        val isChallengeError: Boolean = false,
    )

    private val internalState = MutableStateFlow(InternalState())
    private var refreshJob: Job? = null

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val uiState: StateFlow<UserProfileUiState> = combine(
        container.sessionRepository.sessionState,
        internalState,
        container.bookmarkAndHistoryRepository.observeBookmarkedIds(),
        container.bookmarkAndHistoryRepository.observeReadHistory(),
    ) { session, state, bookmarkedIds, readHistory ->
        val resolvedName = state.profile?.username?.takeIf { it.isNotBlank() }
            ?: initialUsernameHint
        val resolvedAvatar = state.profile?.avatarUrl?.takeIf { it.isNotBlank() }
            ?: if (uid > 0L) "${NodeSeekUrlNormalizer.ORIGIN}/avatar/$uid.png" else ""
        val resolvedLevel = state.profile?.level
        val historyById = readHistory.associateBy { it.topicId }

        UserProfileUiState(
            uid = uid,
            usernameHint = resolvedName,
            isOwnProfile = session?.uid == uid,
            isSignedIn = session != null,
            profile = state.profile,
            selectedTab = state.selectedTab,
            topics = state.topics.map { topic ->
                val historyEntry = historyById[topic.id]
                topic.enrichForMember(
                    fallbackName = resolvedName,
                    fallbackAvatarUrl = resolvedAvatar,
                    fallbackLevel = resolvedLevel,
                ).copy(
                    isBookmarked = topic.id in bookmarkedIds,
                    isRead = topic.isRead || historyEntry != null,
                    lastReadFloor = historyEntry?.lastReadFloor?.takeIf { it > 0 } ?: topic.lastReadFloor,
                )
            },
            topicsPage = state.topicsPage,
            hasMoreTopics = state.hasMoreTopics,
            isLoadingMoreTopics = state.isLoadingMoreTopics,
            replies = state.replies,
            repliesPage = state.repliesPage,
            hasMoreReplies = state.hasMoreReplies,
            isLoadingMoreReplies = state.isLoadingMoreReplies,
            isRefreshing = state.isRefreshing,
            isUpdatingRelation = state.isUpdatingRelation,
            isSavingProfile = state.isSavingProfile,
            showEditDialog = state.showEditDialog,
            connectionsDialogTitle = state.connectionsDialogTitle,
            connectionsList = state.connectionsList,
            isLoadingConnections = state.isLoadingConnections,
            errorMessage = state.errorMessage,
            isChallengeError = state.isChallengeError,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UserProfileUiState(uid = uid, usernameHint = initialUsernameHint),
    )

    init {
        refresh()
        viewModelScope.launch {
            container.sessionRepository.authVerifiedEpoch
                .drop(1)
                .distinctUntilChanged()
                .collect {
                    val current = internalState.value
                    if (current.profile == null ||
                        current.errorMessage != null ||
                        container.sessionRepository.sessionState.value?.uid == uid
                    ) {
                        refresh()
                    }
                }
        }
    }

    fun selectTab(tab: ProfileActivityTab) {
        internalState.update { it.copy(selectedTab = tab) }
    }

    fun toggleBookmark(topic: TopicSummary) {
        viewModelScope.launch {
            val targetBookmarked = !topic.isBookmarked
            val isSignedIn = container.sessionRepository.sessionState.value != null
            if (targetBookmarked) {
                container.bookmarkAndHistoryRepository.saveOfflineBookmark(
                    summary = topic.copy(isBookmarked = true),
                    detailSnapshot = null,
                    syncedWithServer = false,
                )
            } else {
                container.bookmarkAndHistoryRepository.removeLocalBookmark(topic.id)
            }
            if (isSignedIn) {
                container.bookmarkAndHistoryRepository.setTopicBookmarked(
                    topicId = topic.id,
                    bookmarked = targetBookmarked,
                ).onFailure { err ->
                    _events.trySend(err.message ?: "云端收藏同步未完成，已保存在本机")
                }
            } else {
                _events.trySend(if (targetBookmarked) "已加入我的收藏" else "已移除收藏")
            }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            internalState.update {
                it.copy(
                    isRefreshing = true,
                    errorMessage = null,
                    isChallengeError = false,
                )
            }
            val isSelf = container.sessionRepository.sessionState.value?.uid == uid

            val (profileResult, topicsResult, repliesResult) = coroutineScope {
                val profileDeferred = async {
                    container.userRepository.getUserProfile(
                        uid = uid,
                        includeEditableSignature = isSelf,
                    )
                }
                val topicsDeferred = async {
                    container.userRepository.getUserTopics(uid = uid, page = 1)
                }
                val repliesDeferred = async {
                    container.userRepository.getUserReplies(uid = uid, page = 1)
                }
                Triple(
                    profileDeferred.await(),
                    topicsDeferred.await(),
                    repliesDeferred.await(),
                )
            }

            internalState.update { state ->
                val resolvedProfile = profileResult.getOrNull()?.let { fetched ->
                    if (fetched.username.isBlank() && initialUsernameHint.isNotBlank()) {
                        fetched.copy(username = initialUsernameHint)
                    } else {
                        fetched
                    }
                } ?: state.profile

                val (rawTopics, rawHasMoreTopics) = topicsResult.getOrDefault(state.topics to false)
                val (rawReplies, rawHasMoreReplies) = repliesResult.getOrDefault(state.replies to false)
                val deduplicatedTopics = rawTopics.distinctBy { it.id }

                val totalTopicCap = resolvedProfile?.topicCount
                val hasMoreTopics = if (totalTopicCap != null && deduplicatedTopics.size >= totalTopicCap) {
                    false
                } else {
                    rawHasMoreTopics
                }

                val totalReplyCap = resolvedProfile?.replyCount
                val hasMoreReplies = if (totalReplyCap != null && rawReplies.size >= totalReplyCap) {
                    false
                } else {
                    rawHasMoreReplies
                }

                val failure = profileResult.exceptionOrNull()
                    ?: topicsResult.exceptionOrNull()?.takeIf { deduplicatedTopics.isEmpty() }
                val isChallenge = failure is TopicAccessException.CloudflareChallengeRequired

                state.copy(
                    profile = resolvedProfile,
                    topics = deduplicatedTopics,
                    topicsPage = 1,
                    hasMoreTopics = hasMoreTopics,
                    replies = rawReplies,
                    repliesPage = 1,
                    hasMoreReplies = hasMoreReplies,
                    isRefreshing = false,
                    errorMessage = if (resolvedProfile == null) {
                        profileResult.exceptionOrNull()?.message ?: "无法加载用户主页，请下拉重试"
                    } else {
                        null
                    },
                    isChallengeError = resolvedProfile == null && isChallenge,
                )
            }
        }
    }

    fun loadMoreTopics() {
        val current = internalState.value
        if (current.isLoadingMoreTopics || !current.hasMoreTopics) return
        val nextPage = current.topicsPage + 1
        viewModelScope.launch {
            internalState.update { it.copy(isLoadingMoreTopics = true) }
            container.userRepository.getUserTopics(uid = uid, page = nextPage)
                .onSuccess { (items, hasNext) ->
                    internalState.update { s ->
                        val merged = (s.topics + items).distinctBy { it.id }
                        val totalCap = s.profile?.topicCount
                        val canLoadMore = when {
                            items.isEmpty() -> false
                            merged.size == s.topics.size -> false
                            totalCap != null && merged.size >= totalCap -> false
                            else -> hasNext
                        }
                        s.copy(
                            topics = merged,
                            topicsPage = nextPage,
                            hasMoreTopics = canLoadMore,
                            isLoadingMoreTopics = false,
                        )
                    }
                }
                .onFailure {
                    internalState.update { it.copy(isLoadingMoreTopics = false) }
                }
        }
    }

    fun loadMoreReplies() {
        val current = internalState.value
        if (current.isLoadingMoreReplies || !current.hasMoreReplies) return
        val nextPage = current.repliesPage + 1
        viewModelScope.launch {
            internalState.update { it.copy(isLoadingMoreReplies = true) }
            container.userRepository.getUserReplies(uid = uid, page = nextPage)
                .onSuccess { (items, hasNext) ->
                    internalState.update { s ->
                        val merged = s.replies + items
                        val totalCap = s.profile?.replyCount
                        val canLoadMore = when {
                            items.isEmpty() -> false
                            totalCap != null && merged.size >= totalCap -> false
                            else -> hasNext
                        }
                        s.copy(
                            replies = merged,
                            repliesPage = nextPage,
                            hasMoreReplies = canLoadMore,
                            isLoadingMoreReplies = false,
                        )
                    }
                }
                .onFailure {
                    internalState.update { it.copy(isLoadingMoreReplies = false) }
                }
        }
    }

    fun toggleFollow() {
        val profile = internalState.value.profile ?: return
        if (internalState.value.isUpdatingRelation) return
        val targetFollow = !(profile.isFollowedByViewer ?: false)

        viewModelScope.launch {
            internalState.update { it.copy(isUpdatingRelation = true) }
            container.userRepository.setFollowed(targetUid = uid, follow = targetFollow)
                .onSuccess {
                    internalState.update { s ->
                        val p = s.profile
                        val updatedCount = p?.followerCount?.let { c ->
                            (c + if (targetFollow) 1 else -1).coerceAtLeast(0)
                        }
                        s.copy(
                            profile = p?.copy(
                                isFollowedByViewer = targetFollow,
                                followerCount = updatedCount,
                            ),
                            isUpdatingRelation = false,
                        )
                    }
                    _events.trySend(if (targetFollow) "已关注 ${profile.username}" else "已取消关注")
                }
                .onFailure { error ->
                    internalState.update { it.copy(isUpdatingRelation = false) }
                    _events.trySend(error.message ?: "关注状态更新失败")
                }
        }
    }

    fun toggleBlock() {
        val profile = internalState.value.profile ?: return
        if (internalState.value.isUpdatingRelation) return
        val currentlyBlocked = profile.isBlockedByViewer

        viewModelScope.launch {
            internalState.update { it.copy(isUpdatingRelation = true) }
            val result = if (currentlyBlocked) {
                container.userRepository.unblockUserById(targetUid = uid)
            } else {
                container.userRepository.blockUserByName(username = profile.username)
            }
            result.onSuccess {
                internalState.update { s ->
                    s.copy(
                        profile = s.profile?.copy(isBlockedByViewer = !currentlyBlocked),
                        isUpdatingRelation = false,
                    )
                }
                _events.trySend(
                    if (currentlyBlocked) {
                        "已解除对 ${profile.username} 的屏蔽"
                    } else {
                        "已屏蔽 ${profile.username}"
                    },
                )
            }.onFailure { error ->
                internalState.update { it.copy(isUpdatingRelation = false) }
                _events.trySend(error.message ?: "更新屏蔽状态失败")
            }
        }
    }

    fun openEditProfileDialog() {
        internalState.update { it.copy(showEditDialog = true) }
    }

    fun dismissEditProfileDialog() {
        internalState.update { it.copy(showEditDialog = false) }
    }

    fun saveOwnProfile(bio: String, signature: String, readme: String) {
        if (internalState.value.isSavingProfile) return
        viewModelScope.launch {
            internalState.update { it.copy(isSavingProfile = true) }
            container.userRepository.updateOwnProfile(
                bio = bio,
                signature = signature,
                readme = readme,
            ).onSuccess {
                internalState.update { s ->
                    s.copy(
                        profile = s.profile?.copy(
                            bio = bio,
                            signatureMarkdown = signature,
                            readmeMarkdown = readme,
                        ),
                        isSavingProfile = false,
                        showEditDialog = false,
                    )
                }
                _events.trySend("个人资料与签名已更新")
            }.onFailure { error ->
                internalState.update { it.copy(isSavingProfile = false) }
                _events.trySend(error.message ?: "保存个人资料失败")
            }
        }
    }

    fun showOwnConnections(following: Boolean) {
        if (container.sessionRepository.sessionState.value?.uid != uid) return
        viewModelScope.launch {
            internalState.update {
                it.copy(
                    connectionsDialogTitle = if (following) "我的关注" else "我的粉丝",
                    connectionsList = emptyList(),
                    isLoadingConnections = true,
                )
            }
            container.userRepository.getConnections(following = following)
                .onSuccess { users ->
                    internalState.update {
                        it.copy(connectionsList = users, isLoadingConnections = false)
                    }
                }
                .onFailure {
                    internalState.update { it.copy(isLoadingConnections = false) }
                }
        }
    }

    fun dismissConnectionsDialog() {
        internalState.update { it.copy(connectionsDialogTitle = null) }
    }

    companion object {
        fun factory(
            container: AppContainer,
            uid: Long,
            usernameHint: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return UserProfileViewModel(container, uid, usernameHint) as T
            }
        }
    }
}

private fun TopicSummary.enrichForMember(
    fallbackName: String,
    fallbackAvatarUrl: String,
    fallbackLevel: Int?,
): TopicSummary = copy(
    authorName = authorName.ifBlank { fallbackName },
    authorAvatarUrl = authorAvatarUrl.ifBlank { fallbackAvatarUrl },
    authorLevel = authorLevel ?: fallbackLevel,
)
