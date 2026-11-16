package com.nodeseek.app.ui.preview

import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.mail.SeekMailAttachment
import com.nodeseek.app.domain.mail.SeekMailConnectionStatus
import com.nodeseek.app.domain.mail.SeekMailContact
import com.nodeseek.app.domain.mail.SeekMailDetail
import com.nodeseek.app.domain.mail.SeekMailPage
import com.nodeseek.app.domain.mail.SeekMailRateLimit
import com.nodeseek.app.domain.mail.SeekMailRepository
import com.nodeseek.app.domain.mail.SeekMailTag
import com.nodeseek.app.domain.mail.SeekMailUnreadPage
import com.nodeseek.app.domain.mail.SeekMailbox
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.BookmarkedTopic
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.CheckInStatus
import com.nodeseek.app.domain.model.DailyQuotaProgress
import com.nodeseek.app.domain.model.DirectMessage
import com.nodeseek.app.domain.model.DirectMessageSummary
import com.nodeseek.app.domain.model.EditorDraft
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.NotificationItem
import com.nodeseek.app.domain.model.NotificationKind
import com.nodeseek.app.domain.model.ReactionResult
import com.nodeseek.app.domain.model.ReactionType
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.ThemeMode
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.model.UserProfile
import com.nodeseek.app.domain.model.UserReplyExcerpt
import com.nodeseek.app.domain.radar.InAppMessagePopup
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.radar.KeywordWatchRule
import com.nodeseek.app.domain.radar.LocalAlertRecord
import com.nodeseek.app.domain.radar.RadarMonitorController
import com.nodeseek.app.domain.radar.RadarPollingSettings
import com.nodeseek.app.domain.radar.RadarRepository
import com.nodeseek.app.domain.radar.RadarRuntimeStatus
import com.nodeseek.app.domain.radar.TrackedTopic
import com.nodeseek.app.domain.repository.BookmarkAndHistoryRepository
import com.nodeseek.app.domain.repository.CheckInRepository
import com.nodeseek.app.domain.repository.DraftRepository
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.repository.ImageHostRepository
import com.nodeseek.app.domain.repository.InboxRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SettingsRepository
import com.nodeseek.app.domain.repository.SignInStepResult
import com.nodeseek.app.domain.repository.UserRepository
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import com.nodeseek.app.ui.components.PulseBottomDockOverlay
import com.nodeseek.app.ui.components.PulseNavTab
import com.nodeseek.app.ui.theme.NodePulseTheme
import com.nodeseek.app.ui.theme.PulseThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

internal class PreviewNotificationDispatcher(
    context: Context,
) : PulseNotificationDispatcher(context) {
    override fun ensureChannels() = Unit
    override fun hasPostNotificationsPermission(): Boolean = true
    override fun isIgnoringBatteryOptimizations(): Boolean = true
    override fun isSystemDoNotDisturbActive(): Boolean = false
}

internal class PreviewAppContainer(
    context: Context,
) : AppContainer {
    private val sampleSession = AccountSession(
        uid = 1024L,
        username = "latency_hunter",
        avatarUrl = "",
        drumsticks = 2860,
        stardust = 42,
        topicCount = 28,
        replyCount = 314,
        bookmarkCount = 19,
        permissionRank = 5,
        unreadNotificationCount = 3,
        canCustomizeCss = true,
        isTelegramBound = true,
    )

    override val sessionRepository: SessionRepository = object : SessionRepository {
        override val sessionState: StateFlow<AccountSession?> = MutableStateFlow(sampleSession).asStateFlow()
        override val challengeRequired: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override val authVerifiedEpoch: StateFlow<Long> = MutableStateFlow(1L).asStateFlow()
        override suspend fun refreshSession() = Result.success(sampleSession)
        override suspend fun signIn(username: String, password: String, turnstileToken: String) =
            Result.success(SignInStepResult.Authenticated)
        override suspend fun verifyTwoFactor(otpCode: String, otpSession: String) = Result.success(Unit)
        override suspend fun onWebViewAuthCompleted() = Result.success(sampleSession)
        override suspend fun onWebViewDismissed() = Result.success(sampleSession)
        override suspend fun signOut() = Result.success(Unit)
    }

    override val forumRepository: ForumRepository = object : ForumRepository {
        override fun observeCategories(): Flow<List<Category>> = flowOf(Category.DEFAULT_CATEGORIES)
        override suspend fun refreshCategories(force: Boolean) = Result.success(Category.DEFAULT_CATEGORIES)
        override suspend fun getFeed(
            categorySlug: String,
            sort: FeedSort,
            page: Int,
            includeBlocked: Boolean,
            forceRefresh: Boolean,
        ) = Result.success(FeedPageResult(topics = emptyList()))

        override suspend fun getCuratedFeed(page: Int, forceRefresh: Boolean) =
            Result.success(FeedPageResult(topics = emptyList()))

        override suspend fun searchTopics(query: String, categorySlug: String, sort: FeedSort, page: Int) =
            Result.success(FeedPageResult(topics = emptyList()))

        override suspend fun getTopicDetail(topicId: Long, page: Int, includeBlocked: Boolean) =
            Result.failure<TopicDetailPage>(UnsupportedOperationException("preview"))

        override suspend fun createTopic(
            categorySlug: String,
            title: String,
            markdownContent: String,
            requiredViewLevel: Int,
        ) = Result.success(Unit)

        override suspend fun editTopic(
            topicId: Long,
            title: String,
            markdownContent: String,
            requiredViewLevel: Int,
        ) = Result.success(Unit)

        override suspend fun postReply(topicId: Long, markdownContent: String) = Result.success(Unit)
        override suspend fun editReply(commentId: Long, markdownContent: String) = Result.success(Unit)
        override suspend fun setReplyPinned(commentId: Long, pinned: Boolean) = Result.success(Unit)
        override suspend fun getFreeDrumstickCost() = Result.success(0)
        override suspend fun reactToComment(topicId: Long, commentId: Long, reaction: ReactionType) =
            Result.success(ReactionResult(updatedCount = 1))
    }

    override val checkInRepository: CheckInRepository = object : CheckInRepository {
        override val checkInStatus: StateFlow<CheckInStatus?> = MutableStateFlow(
            CheckInStatus(
                hasCheckedInToday = true,
                todayGain = 12,
                todayOrder = 18,
                totalCheckedInUsers = 4820,
            ),
        ).asStateFlow()

        override fun clearCachedStatus() = Unit
        override suspend fun getCheckInBoard(page: Int, forceRefresh: Boolean) =
            Result.success(checkInStatus.value!!)
        override suspend fun checkIn(randomReward: Boolean) = Result.success(checkInStatus.value!!)
        override suspend fun getDailyProgress() = Result.success(
            DailyQuotaProgress(
                postBonusEarned = 5,
                maxPostBonus = 5,
                replyBonusEarned = 3,
                maxReplyBonus = 5,
                freeDrumsticksUsed = 2,
                maxFreeDrumsticks = 5,
            ),
        )
    }

    override val inboxRepository: InboxRepository = object : InboxRepository {
        private val popupFlow = MutableSharedFlow<InAppMessagePopup>()
        private val unreadIncrease = MutableSharedFlow<Int>()
        override val unreadTotalCount: StateFlow<Int> = MutableStateFlow(3).asStateFlow()
        override val replyNotifications: StateFlow<List<NotificationItem>> =
            MutableStateFlow(emptyList<NotificationItem>()).asStateFlow()
        override val mentionNotifications: StateFlow<List<NotificationItem>> =
            MutableStateFlow(emptyList<NotificationItem>()).asStateFlow()
        override val messageThreads: StateFlow<List<DirectMessageSummary>> =
            MutableStateFlow(emptyList<DirectMessageSummary>()).asStateFlow()
        override val inAppPopupEvents: Flow<InAppMessagePopup> = popupFlow.asSharedFlow()
        override val unreadIncreaseEvents: Flow<Int> = unreadIncrease.asSharedFlow()
        override suspend fun refreshUnreadCount() = Result.success(3)
        override suspend fun syncForegroundInbox(forceListSync: Boolean) = Result.success(Unit)
        override suspend fun getNotifications(kind: NotificationKind) = Result.success(emptyList<NotificationItem>())
        override suspend fun markNotificationsRead(kind: NotificationKind, ids: List<Long>?) = Result.success(Unit)
        override suspend fun getMessageThreads() = Result.success(emptyList<DirectMessageSummary>())
        override suspend fun getConversation(peerUid: Long) = Result.success(emptyList<DirectMessage>())
        override suspend fun markThreadRead(peerUid: Long) = Result.success(Unit)
        override suspend fun markMessagesRead(messageIds: List<Long>?) = Result.success(Unit)
        override suspend fun sendDirectMessage(receiverUid: Long, content: String, isMarkdown: Boolean) =
            Result.success(Unit)
    }

    override val userRepository: UserRepository = object : UserRepository {
        override suspend fun getUserProfile(uid: Long, includeEditableSignature: Boolean) =
            Result.failure<UserProfile>(UnsupportedOperationException("preview"))
        override suspend fun updateOwnProfile(bio: String, signature: String, readme: String) = Result.success(Unit)
        override suspend fun getUserTopics(uid: Long, page: Int) =
            Result.success(emptyList<TopicSummary>() to false)
        override suspend fun getUserReplies(uid: Long, page: Int) =
            Result.success(emptyList<UserReplyExcerpt>() to false)
        override suspend fun searchUsers(keyword: String) = Result.success(emptyList<FloorAuthor>())
        override suspend fun setFollowed(targetUid: Long, follow: Boolean) = Result.success(Unit)
        override suspend fun getConnections(following: Boolean) = Result.success(emptyList<FloorAuthor>())
        override suspend fun getBlockedUsers() = Result.success(emptyList<FloorAuthor>())
        override suspend fun blockUserByName(username: String) = Result.success(Unit)
        override suspend fun unblockUserById(targetUid: Long) = Result.success(Unit)
    }

    override val bookmarkAndHistoryRepository: BookmarkAndHistoryRepository = object : BookmarkAndHistoryRepository {
        override suspend fun setTopicBookmarked(topicId: Long, bookmarked: Boolean) = Result.success(Unit)
        override suspend fun getServerBookmarks(page: Int) = Result.success(emptyList<TopicSummary>() to false)
        override fun observeLocalBookmarks(): Flow<List<BookmarkedTopic>> = flowOf(emptyList())
        override fun observeBookmarkedIds(): Flow<Set<Long>> = flowOf(emptySet())
        override fun observeReadHistory(): Flow<List<ReadHistoryEntry>> = flowOf(emptyList())
        override fun observeReadStateMap(topicIds: Collection<Long>): Flow<Map<Long, ReadHistoryEntry>> = flowOf(emptyMap())
        override suspend fun recordTopicVisit(entry: ReadHistoryEntry) = Unit
        override suspend fun clearReadHistory() = Unit
    }

    override val settingsRepository: SettingsRepository = object : SettingsRepository {
        private val state = MutableStateFlow(
            AppSettings(
                themeMode = ThemeMode.PAPER_LIGHT,
                nodeImageApiKey = "ni_live_84a2f09c41d3a8e190b7",
                seekMailApiKey = "sk_mail_e91f38b7c042a16f981c",
            ),
        )
        override val settings: StateFlow<AppSettings> = state.asStateFlow()
        override val recentSearchQueries: StateFlow<List<String>> = MutableStateFlow(
            listOf("东京 IIJ", "DMIT TYO", "YABS", "NodeImage"),
        ).asStateFlow()

        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override suspend fun setPurePitchAmoled(enabled: Boolean) = Unit
        override suspend fun setUseDynamicColor(enabled: Boolean) = Unit
        override suspend fun setFontScale(scale: Float) = Unit
        override suspend fun setPreferRandomCheckIn(preferRandom: Boolean) = Unit
        override suspend fun setPinnedCategorySlugs(slugs: Set<String>) = Unit
        override suspend fun togglePinnedCategory(slug: String) = Unit
        override suspend fun setIncludeBlockedTopics(include: Boolean) = Unit
        override suspend fun setPreferRssFallback(preferRss: Boolean) = Unit
        override suspend fun setNodeImageApiKey(apiKey: String) = Unit
        override suspend fun setSeekMailApiKey(apiKey: String) = Unit
        override suspend fun addRecentSearchQuery(query: String) = Unit
        override suspend fun removeRecentSearchQuery(query: String) = Unit
        override suspend fun clearRecentSearchQueries() = Unit
    }

    override val draftRepository: DraftRepository = object : DraftRepository {
        override suspend fun getDraft(accountUid: Long, draftKey: String): EditorDraft? = null
        override suspend fun saveDraft(draft: EditorDraft) = Unit
        override suspend fun deleteDraft(accountUid: Long, draftKey: String) = Unit
        override fun observeDrafts(accountUid: Long): Flow<List<EditorDraft>> = flowOf(emptyList())
        override fun observeDraft(accountUid: Long, draftKey: String): Flow<EditorDraft?> = flowOf(null)
    }

    override val imageHostRepository: ImageHostRepository = object : ImageHostRepository {
        override suspend fun uploadImage(bytes: ByteArray, mimeType: String, deleteAfterDays: Int?, fileName: String?) =
            Result.success("https://img.nodeimage.com/demo/preview.png")
    }

    override val seekMailRepository: SeekMailRepository = object : SeekMailRepository {
        override val locallyReadMailIds: StateFlow<Set<Long>> = MutableStateFlow(emptySet<Long>()).asStateFlow()
        override val latestRateLimit: StateFlow<SeekMailRateLimit?> =
            MutableStateFlow(SeekMailRateLimit(limit = 60, remaining = 56, resetEpochSeconds = 0L)).asStateFlow()
        override suspend fun verifyApiKey(candidateKey: String?) =
            Result.success(SeekMailConnectionStatus(mailboxes = emptyList()))
        override suspend fun getMailboxes(forceRefresh: Boolean) = Result.success(emptyList<SeekMailbox>())
        override suspend fun getMailbox(id: Long) =
            Result.failure<SeekMailbox>(UnsupportedOperationException("preview"))
        override suspend fun checkMailboxAvailable(address: String) = Result.success(true)
        override suspend fun createMailbox(address: String) =
            Result.failure<SeekMailbox>(UnsupportedOperationException("preview"))
        override suspend fun getMails(
            mailboxId: Long?,
            search: String?,
            tagId: Long?,
            limit: Int,
            offset: Int,
            forceRefresh: Boolean,
        ) = Result.success(SeekMailPage(items = emptyList(), limit = limit, offset = offset, total = 0, hasMore = false))
        override suspend fun getUnreadMails(mailboxId: Long?, limit: Int, cursor: String?) =
            Result.success(SeekMailUnreadPage(items = emptyList(), limit = limit, hasMore = false))
        override suspend fun getMailDetail(mailId: Long, forceRefresh: Boolean) =
            Result.failure<SeekMailDetail>(UnsupportedOperationException("preview"))
        override suspend fun getRawMailEml(mailId: Long) = Result.success("")
        override suspend fun getAttachments(mailId: Long) = Result.success(emptyList<SeekMailAttachment>())
        override suspend fun downloadAttachmentBytes(mailId: Long, attachmentId: String) =
            Result.success(ByteArray(0))
        override suspend fun getTags(forceRefresh: Boolean) = Result.success(emptyList<SeekMailTag>())
        override suspend fun getContacts(limit: Int, forceRefresh: Boolean) =
            Result.success(emptyList<SeekMailContact>())
        override suspend fun markMailReadLocally(mailId: Long) = Unit
        override suspend fun clearCache() = Unit
    }

    override val radarRepository: RadarRepository = object : RadarRepository {
        override val pollingSettingsFlow: Flow<RadarPollingSettings> = flowOf(RadarPollingSettings())
        override val keywordRulesFlow: Flow<List<KeywordWatchRule>> = flowOf(
            listOf(
                KeywordWatchRule(id = 1L, keyword = "DMIT TYO", categorySlug = "trade", isEnabled = true),
                KeywordWatchRule(id = 2L, keyword = "CN2 GIA", categorySlug = "info", isEnabled = true),
            ),
        )
        override val trackedTopicsFlow: Flow<List<TrackedTopic>> = flowOf(
            listOf(
                TrackedTopic(topicId = 884210L, title = "东京 Tier-1 BGP 实测", lastKnownFloor = 58),
            ),
        )
        override val alertRecordsFlow: Flow<List<LocalAlertRecord>> = flowOf(emptyList())
        override val unreadAlertCountFlow: Flow<Int> = flowOf(0)
        override fun observeTrackedTopic(topicId: Long): Flow<TrackedTopic?> = flowOf(null)
        override suspend fun getPollingSettings() = RadarPollingSettings()
        override suspend fun updatePollingSettings(transform: (RadarPollingSettings) -> RadarPollingSettings) = Unit
        override suspend fun addKeywordRule(
            keyword: String,
            excludeWords: String,
            categorySlug: String,
            matchMode: KeywordMatchMode,
        ) = Result.failure<KeywordWatchRule>(UnsupportedOperationException("preview"))
        override suspend fun toggleKeywordRule(ruleId: Long, enabled: Boolean) = Unit
        override suspend fun deleteKeywordRule(ruleId: Long) = Unit
        override suspend fun trackTopic(
            topicId: Long,
            title: String,
            categorySlug: String,
            categoryName: String,
            authorName: String,
            currentMaxFloor: Int,
            totalPages: Int,
        ) = TrackedTopic(topicId = topicId, title = title)
        override suspend fun untrackTopic(topicId: Long) = Unit
        override suspend fun toggleTrackTopic(
            topicId: Long,
            title: String,
            categorySlug: String,
            categoryName: String,
            authorName: String,
            currentMaxFloor: Int,
            totalPages: Int,
        ) = true
        override suspend fun onTopicFloorSeen(topicId: Long, maxSeenFloor: Int, totalPages: Int) = Unit
        override suspend fun recordAlertIfAbsent(record: LocalAlertRecord) = false
        override suspend fun markAlertRead(alertId: Long, read: Boolean) = Unit
        override suspend fun markAlertReadByDedupKey(dedupKey: String) = Unit
        override suspend fun markAlertsReadForTopic(topicId: Long) = Unit
        override suspend fun markAllAlertsRead() = Unit
        override suspend fun deleteAlert(alertId: Long) = Unit
        override suspend fun clearReadAlerts() = Unit
        override suspend fun clearAllAlerts() = Unit
    }

    override val radarMonitorController: RadarMonitorController = object : RadarMonitorController {
        override val runtimeStatus: StateFlow<RadarRuntimeStatus> =
            MutableStateFlow(RadarRuntimeStatus(isRunningInForeground = true)).asStateFlow()
        override fun start() = Unit
        override fun stop() = Unit
        override fun onForegroundChanged(inForeground: Boolean) = Unit
        override fun triggerImmediateCheck() = Unit
    }

    override val notificationDispatcher: PulseNotificationDispatcher =
        PreviewNotificationDispatcher(context)
}

@Composable
internal fun PreviewPhoneFrame(
    themeMode: PulseThemeMode,
    purePitchAmoled: Boolean = false,
    bottomDockTab: PulseNavTab? = null,
    showFab: Boolean = bottomDockTab == PulseNavTab.EXPLORE,
    unreadCount: Int = 3,
    hasPendingCheckIn: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val container = remember(context) { PreviewAppContainer(context) }
    val vmStoreOwner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = ViewModelStore()
        }
    }
    val backDispatcherOwner = remember(lifecycleOwner) {
        object : OnBackPressedDispatcherOwner {
            override val lifecycle: Lifecycle = lifecycleOwner.lifecycle
            override val onBackPressedDispatcher: OnBackPressedDispatcher = OnBackPressedDispatcher()
        }
    }
    val activityResultRegistryOwner = remember {
        object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) = Unit
            }
        }
    }

    CompositionLocalProvider(
        LocalAppContainer provides container,
        LocalViewModelStoreOwner provides vmStoreOwner,
        LocalOnBackPressedDispatcherOwner provides backDispatcherOwner,
        LocalActivityResultRegistryOwner provides activityResultRegistryOwner,
    ) {
        NodePulseTheme(
            themeMode = themeMode,
            purePitchAmoled = purePitchAmoled,
            dynamicColor = false,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                PreviewAndroidStatusBar()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    content()

                    if (bottomDockTab != null) {
                        PulseBottomDockOverlay(
                            selectedTab = bottomDockTab,
                            onSelectTab = {},
                            onFabClick = {},
                            showFab = showFab,
                            fabExpanded = true,
                            unreadNotificationCount = unreadCount,
                            hasPendingCheckIn = hasPendingCheckIn,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }

                PreviewNavigationGestureBar()
            }
        }
    }
}

@Composable
private fun PreviewAndroidStatusBar() {
    val fg = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.88f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "09:41",
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
            ),
            color = fg,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.SignalCellularAlt,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(14.dp),
            )
            Icon(
                imageVector = Icons.Rounded.Wifi,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "96%",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = fg,
            )
            Icon(
                imageVector = Icons.Rounded.BatteryFull,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun PreviewNavigationGestureBar() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 4.dp, bottom = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            modifier = Modifier
                .width(108.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.24f)),
        )
    }
}
