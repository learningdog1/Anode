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
import com.nodeseek.app.BuildConfig
import com.nodeseek.app.R
import com.nodeseek.app.data.activity.PreviewForumWatchActivityRepository
import com.nodeseek.app.data.backup.BackupManagerImpl
import com.nodeseek.app.data.backup.ContentFilterRepositoryBackupBridge
import com.nodeseek.app.data.fleet.FleetWorkScheduler
import com.nodeseek.app.data.local.InMemoryBlockedUsersCache
import com.nodeseek.app.data.netbench.NetBenchHistoryStore
import com.nodeseek.app.data.overseas.PreviewOverseasIntelRepository
import com.nodeseek.app.data.restock.FlareSolverrClient
import com.nodeseek.app.data.restock.RestockNotificationHelper
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.activity.ActivityMonitorController
import com.nodeseek.app.domain.activity.ActivityMonitorRuntimeStatus
import com.nodeseek.app.domain.activity.ActivityMonitorSettings
import com.nodeseek.app.domain.activity.ActivityPollOutcome
import com.nodeseek.app.domain.activity.ActivityRepository
import com.nodeseek.app.domain.backup.AppSettingsBackupDto
import com.nodeseek.app.domain.backup.BackupManager
import com.nodeseek.app.domain.filter.ContentBlockRule
import com.nodeseek.app.domain.filter.ContentFilterConfig
import com.nodeseek.app.domain.filter.ContentFilterEngine
import com.nodeseek.app.domain.filter.ContentFilterRepository
import com.nodeseek.app.domain.fleet.FleetMonitorCheckReport
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.FleetRefreshSummary
import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.fleet.ProbeConnectivityResult
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.VpsAssetRecord
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
import com.nodeseek.app.domain.overseas.OverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasPromoMonitorController
import com.nodeseek.app.domain.overseas.OverseasPromoMonitorSettings
import com.nodeseek.app.domain.overseas.OverseasPromoPollOutcome
import com.nodeseek.app.domain.overseas.OverseasPromoRuntimeStatus
import com.nodeseek.app.domain.restock.RestockBackgroundPollOutcome
import com.nodeseek.app.domain.restock.RestockEventRecord
import com.nodeseek.app.domain.restock.RestockMonitorController
import com.nodeseek.app.domain.restock.RestockMonitorSettings
import com.nodeseek.app.domain.restock.RestockRepository
import com.nodeseek.app.domain.restock.RestockRuntimeStatus
import com.nodeseek.app.domain.restock.RestockStockStatus
import com.nodeseek.app.domain.restock.RestockWatchItem
import com.nodeseek.app.domain.restock.StockAnalysisResult
import com.nodeseek.app.domain.ssh.CustomSshPreset
import com.nodeseek.app.domain.ssh.DecryptedSshCredentials
import com.nodeseek.app.domain.ssh.SshExecutionRecord
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.domain.ssh.SshVaultRepository
import com.nodeseek.app.domain.trust.LocalSellerNote
import com.nodeseek.app.domain.trust.SellerTrustReport
import com.nodeseek.app.domain.trust.SellerTrustRepository
import com.nodeseek.app.domain.trust.TrustRiskAssessment
import com.nodeseek.app.domain.trust.TrustRiskLevel
import com.nodeseek.app.domain.trust.TrustTag
import java.io.File
import java.time.LocalDate
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.AwayDigestTriggerMode
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
import com.nodeseek.app.domain.radar.RadarBackgroundPollOutcome
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
import com.nodeseek.app.domain.update.AppUpdateManager
import com.nodeseek.app.domain.update.AppUpdateState
import com.nodeseek.app.domain.update.UpdateCheckResult
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import com.nodeseek.app.ui.components.LocalPreviewAvatarResResolver
import com.nodeseek.app.ui.components.PulseBottomDockOverlay
import com.nodeseek.app.ui.components.PulseNavTab
import com.nodeseek.app.ui.theme.NodePulseTheme
import com.nodeseek.app.ui.theme.PulseThemeMode
import com.nodeseek.app.ui.trust.LocalSellerTrustRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update

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
        override suspend fun setHomeCardScale(scale: Float) = Unit
        override suspend fun setPreferRandomCheckIn(preferRandom: Boolean) = Unit
        override suspend fun setPinnedCategorySlugs(slugs: Set<String>) = Unit
        override suspend fun togglePinnedCategory(slug: String) = Unit
        override suspend fun setIncludeBlockedTopics(include: Boolean) = Unit
        override suspend fun setPreferRssFallback(preferRss: Boolean) = Unit
        override suspend fun setNodeImageApiKey(apiKey: String) = Unit
        override suspend fun setSeekMailApiKey(apiKey: String) = Unit
        override suspend fun setAutoCheckUpdates(enabled: Boolean) = Unit
        override suspend fun setIncludePrereleaseUpdates(enabled: Boolean) = Unit
        override suspend fun setIgnoredUpdateTag(tag: String) = Unit
        override suspend fun setAwayDigestEnabled(enabled: Boolean) = Unit
        override suspend fun setAwayDigestTriggerMode(mode: AwayDigestTriggerMode) = Unit
        override suspend fun setAwayDigestThresholdMinutes(minutes: Int) = Unit
        override suspend fun addRecentSearchQuery(query: String) = Unit
        override suspend fun removeRecentSearchQuery(query: String) = Unit
        override suspend fun clearRecentSearchQueries() = Unit
        override suspend fun exportSettingsForBackup(includeApiKeys: Boolean): AppSettingsBackupDto {
            val s = state.value
            return AppSettingsBackupDto(
                themeMode = s.themeMode,
                purePitchAmoled = s.purePitchAmoled,
                useDynamicColor = s.useDynamicColor,
                fontScale = s.fontScale,
                homeCardScale = s.homeCardScale,
                preferRandomCheckIn = s.preferRandomCheckIn,
                pinnedCategorySlugs = s.pinnedCategorySlugs,
                includeBlockedTopics = s.includeBlockedTopics,
                preferRssFallback = s.preferRssFallback,
                nodeImageApiKey = if (includeApiKeys) s.nodeImageApiKey else "",
                seekMailApiKey = if (includeApiKeys) s.seekMailApiKey else "",
                autoCheckUpdates = s.autoCheckUpdates,
                includePrereleaseUpdates = s.includePrereleaseUpdates,
                ignoredUpdateTag = s.ignoredUpdateTag,
                awayDigestEnabled = s.awayDigestEnabled,
                awayDigestTriggerMode = s.awayDigestTriggerMode,
                awayDigestThresholdMinutes = s.awayDigestThresholdMinutes,
                recentSearchQueries = recentSearchQueries.value,
            )
        }
        override suspend fun restoreSettingsFromBackup(backup: AppSettingsBackupDto, merge: Boolean) = Unit
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
        override suspend fun runBackgroundPollPass() = RadarBackgroundPollOutcome.Completed()
    }

    override val contentFilterRepository: ContentFilterRepository = object : ContentFilterRepository {
        private val state = MutableStateFlow(
            ContentFilterConfig(
                isEnabled = true,
                mutedCategorySlugs = setOf("meaningless"),
                rules = listOf(
                    ContentFilterEngine.CURATED_PRESETS[0].toRule(createdAtEpochMillis = 1_700_000_000_000L),
                    ContentFilterEngine.CURATED_PRESETS[1].toRule(createdAtEpochMillis = 1_700_000_001_000L),
                ),
            ),
        )
        override val config: StateFlow<ContentFilterConfig> = state.asStateFlow()
        override suspend fun setEnabled(enabled: Boolean) {
            state.update { it.copy(isEnabled = enabled) }
        }
        override suspend fun upsertRule(rule: ContentBlockRule) {
            state.update { ContentFilterEngine.upsertRule(it, rule) }
        }
        override suspend fun removeRule(ruleId: String) {
            state.update { ContentFilterEngine.removeRule(it, ruleId) }
        }
        override suspend fun setRuleEnabled(ruleId: String, enabled: Boolean) {
            state.update { ContentFilterEngine.setRuleEnabled(it, ruleId, enabled) }
        }
        override suspend fun toggleMutedCategory(categorySlug: String) {
            state.update { ContentFilterEngine.toggleMutedCategory(it, categorySlug) }
        }
        override suspend fun setMutedCategories(categorySlugs: Set<String>) {
            state.update { ContentFilterEngine.setMutedCategories(it, categorySlugs) }
        }
        override suspend fun replaceConfig(config: ContentFilterConfig) {
            state.value = ContentFilterEngine.normalizeConfig(config)
        }
        override suspend fun mergeConfig(config: ContentFilterConfig) {
            state.update { ContentFilterEngine.mergeConfigs(it, config) }
        }
    }

    override val notificationDispatcher: PulseNotificationDispatcher =
        PreviewNotificationDispatcher(context)

    override val appUpdateManager: AppUpdateManager = object : AppUpdateManager {
        override val state: StateFlow<AppUpdateState> = MutableStateFlow(
            AppUpdateState(
                currentVersionName = BuildConfig.VERSION_NAME,
                currentVersionCode = BuildConfig.VERSION_CODE,
            ),
        ).asStateFlow()

        override fun checkForUpdatesOnLaunch() = Unit
        override suspend fun checkForUpdates(manual: Boolean) = UpdateCheckResult.UpToDate(BuildConfig.VERSION_NAME)
        override fun openUpdateDialog() = Unit
        override fun dismissUpdatePrompt() = Unit
        override fun ignoreRelease(tagName: String) = Unit
        override fun startDownloadAndInstall() = Unit
        override fun cancelDownload() = Unit
        override fun installReadyApk(): Boolean = false
        override fun openUnknownSourcesSettings(): Boolean = false
        override fun onAppResumed() = Unit
    }

    override val backupManager: BackupManager = BackupManagerImpl(
        settingsRepository = settingsRepository,
        radarRepository = radarRepository,
        bookmarkAndHistoryRepository = bookmarkAndHistoryRepository,
        draftRepository = draftRepository,
        userRepository = userRepository,
        sessionRepository = sessionRepository,
        blockedUsersCache = InMemoryBlockedUsersCache(),
        contentFilterBackupBridge = ContentFilterRepositoryBackupBridge(contentFilterRepository),
    )

    override val exchangeRateRepository: ExchangeRateRepository = ExchangeRateRepository(
        cacheFile = File(context.cacheDir, "preview_valuation_fx_cache.json"),
    )

    override val restockNotificationHelper: RestockNotificationHelper =
        object : RestockNotificationHelper(context) {
            override fun ensureChannel() = Unit
            override fun hasPostNotificationsPermission(): Boolean = true
        }

    override val restockRepository: RestockRepository = object : RestockRepository {
        private val watches = MutableStateFlow<List<RestockWatchItem>>(emptyList())
        private val events = MutableStateFlow<List<RestockEventRecord>>(emptyList())
        private val monitorSettings = MutableStateFlow(RestockMonitorSettings())
        override val watchItemsFlow: StateFlow<List<RestockWatchItem>> = watches.asStateFlow()
        override val eventHistoryFlow: StateFlow<List<RestockEventRecord>> = events.asStateFlow()
        override val settingsFlow: StateFlow<RestockMonitorSettings> = monitorSettings.asStateFlow()
        override suspend fun getWatchItemsSnapshot(): List<RestockWatchItem> = watches.value
        override suspend fun getEventHistorySnapshot(): List<RestockEventRecord> = events.value
        override suspend fun getSettingsSnapshot(): RestockMonitorSettings = monitorSettings.value
        override suspend fun addOrUpdateWatch(item: RestockWatchItem): RestockWatchItem {
            watches.update { list -> list.filterNot { it.id == item.id } + item }
            return item
        }
        override suspend fun removeWatch(id: String) {
            watches.update { list -> list.filterNot { it.id == id } }
        }
        override suspend fun setWatchEnabled(id: String, enabled: Boolean) = Unit
        override suspend fun updateWatchPollInterval(id: String, intervalMinutes: Int) = Unit
        override suspend fun recordCheckResult(watchId: String, analysis: StockAnalysisResult): RestockEventRecord? = null
        override suspend fun markEventRead(eventId: String) = Unit
        override suspend fun markAllEventsRead() = Unit
        override suspend fun deleteEvent(eventId: String) = Unit
        override suspend fun clearEventHistory() = Unit
        override suspend fun updateSettings(transform: (RestockMonitorSettings) -> RestockMonitorSettings): RestockMonitorSettings {
            val next = transform(monitorSettings.value)
            monitorSettings.value = next
            return next
        }
    }

    override val flareSolverrClient: FlareSolverrClient = FlareSolverrClient()

    override val restockMonitorController: RestockMonitorController = object : RestockMonitorController {
        override val runtimeStatus: StateFlow<RestockRuntimeStatus> =
            MutableStateFlow(RestockRuntimeStatus()).asStateFlow()
        override fun start() = Unit
        override fun stop() = Unit
        override fun onForegroundChanged(inForeground: Boolean) = Unit
        override fun triggerImmediateCheckAll() = Unit
        override fun triggerCheckSingle(watchId: String) = Unit
        override suspend fun probeUrl(
            rawUrl: String,
            customInStockKeyword: String,
            customOutOfStockKeyword: String,
        ): StockAnalysisResult = StockAnalysisResult(
            rawUrl = rawUrl,
            resolvedOrderUrl = rawUrl,
            finalUrl = rawUrl,
            stockStatus = RestockStockStatus.OUT_OF_STOCK,
        )
        override suspend fun checkWatchNow(watchId: String): StockAnalysisResult? = null
        override suspend fun checkAllNow(onlyEnabled: Boolean): List<StockAnalysisResult> = emptyList()
        override suspend fun runBackgroundPollPass(): RestockBackgroundPollOutcome =
            RestockBackgroundPollOutcome.Completed()
    }

    override val activityRepository: ActivityRepository = PreviewForumWatchActivityRepository()

    override val activityMonitorController: ActivityMonitorController =
        object : ActivityMonitorController {
            private val settingsState = MutableStateFlow(ActivityMonitorSettings())
            override val settingsFlow: StateFlow<ActivityMonitorSettings> = settingsState.asStateFlow()
            override val runtimeStatus: StateFlow<ActivityMonitorRuntimeStatus> =
                MutableStateFlow(ActivityMonitorRuntimeStatus(isRunningInForeground = true)).asStateFlow()

            override fun start() = Unit
            override fun stop() = Unit
            override fun onForegroundChanged(inForeground: Boolean) = Unit
            override fun triggerImmediateCheck() = Unit
            override suspend fun setNotificationsEnabled(enabled: Boolean) {
                settingsState.update { it.copy(notificationsEnabled = enabled) }
            }
            override suspend fun updateSettings(
                transform: (ActivityMonitorSettings) -> ActivityMonitorSettings,
            ): ActivityMonitorSettings {
                val next = transform(settingsState.value).normalized()
                settingsState.value = next
                return next
            }
            override suspend fun checkNow(manualTrigger: Boolean): ActivityPollOutcome =
                ActivityPollOutcome.Completed()
            override suspend fun runBackgroundPollPass(): ActivityPollOutcome =
                ActivityPollOutcome.Completed()
        }

    override val sellerTrustRepository: SellerTrustRepository = object : SellerTrustRepository {
        private val notes = MutableStateFlow<Map<Long, LocalSellerNote>>(emptyMap())
        override suspend fun inspectSeller(uid: Long, usernameHint: String, forceRefresh: Boolean): SellerTrustReport =
            SellerTrustReport(
                uid = uid,
                username = usernameHint.ifBlank { "用户 #$uid" },
                rankLevel = 4,
                localNote = notes.value[uid],
                riskAssessment = TrustRiskAssessment(
                    level = TrustRiskLevel.NORMAL_CLEAR,
                    headline = "未见明显风险记录",
                ),
            )
        override fun observeLocalNote(uid: Long): Flow<LocalSellerNote?> = flowOf(notes.value[uid])
        override suspend fun saveLocalNote(uid: Long, username: String, tag: TrustTag, noteText: String) {
            notes.update { map ->
                map + (uid to LocalSellerNote(uid = uid, username = username, tag = tag, noteText = noteText))
            }
        }
        override suspend fun getAllNotes(): List<LocalSellerNote> = notes.value.values.toList()
    }

    override val fleetRepository: FleetRepository = object : FleetRepository {
        override val panelsFlow: StateFlow<List<ProbePanelConfig>> = MutableStateFlow(emptyList<ProbePanelConfig>()).asStateFlow()
        override val snapshotsFlow: StateFlow<List<ProbeServerSnapshot>> = MutableStateFlow(emptyList<ProbeServerSnapshot>()).asStateFlow()
        override val assetsFlow: StateFlow<List<VpsAssetRecord>> = MutableStateFlow(emptyList<VpsAssetRecord>()).asStateFlow()
        override val monitorSettingsFlow: StateFlow<FleetMonitorSettings> = MutableStateFlow(FleetMonitorSettings()).asStateFlow()
        override val lastSyncEpochMillisFlow: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
        override val isRefreshingFlow: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override val panelErrorsFlow: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap<String, String>()).asStateFlow()
        override suspend fun loadPersistedState() = Unit
        override suspend fun upsertPanel(config: ProbePanelConfig): ProbePanelConfig = config
        override suspend fun deletePanel(panelId: String) = Unit
        override suspend fun testPanelConnection(config: ProbePanelConfig): ProbeConnectivityResult =
            ProbeConnectivityResult(success = true)
        override suspend fun refreshAllPanels(): FleetRefreshSummary = FleetRefreshSummary(
            refreshedPanelCount = 0,
            succeededPanelCount = 0,
            failedPanelErrors = emptyMap(),
            snapshots = emptyList(),
            syncedAtEpochMillis = 0L,
        )
        override suspend fun refreshPanel(panelId: String): Result<List<ProbeServerSnapshot>> = Result.success(emptyList())
        override suspend fun upsertAsset(asset: VpsAssetRecord): VpsAssetRecord = asset
        override suspend fun deleteAsset(assetId: String) = Unit
        override suspend fun linkAssetToProbe(assetId: String, probeKey: String?) = Unit
        override suspend fun updateMonitorSettings(settings: FleetMonitorSettings) = Unit
        override suspend fun evaluateAlerts(
            today: LocalDate,
            nowEpochMillis: Long,
            fxRatesState: FxRatesState?,
            checkedPanels: Int?,
            failedPanels: Int,
        ): FleetMonitorCheckReport = FleetMonitorCheckReport()
    }

    override val fleetWorkScheduler: FleetWorkScheduler = FleetWorkScheduler(context)

    override val netBenchHistoryStore: NetBenchHistoryStore = NetBenchHistoryStore(
        historyFile = File(context.cacheDir, "preview_netbench_history.json"),
    )

    override val sshVaultRepository: SshVaultRepository = object : SshVaultRepository {
        override val serversFlow: StateFlow<List<SshServerProfile>> = MutableStateFlow(emptyList<SshServerProfile>()).asStateFlow()
        override val customPresetsFlow: StateFlow<List<CustomSshPreset>> = MutableStateFlow(emptyList<CustomSshPreset>()).asStateFlow()
        override val recentRunsFlow: StateFlow<List<SshExecutionRecord>> = MutableStateFlow(emptyList<SshExecutionRecord>()).asStateFlow()
        override suspend fun saveServer(draft: SshServerDraft): SshServerProfile =
            SshServerProfile(
                id = draft.id ?: "preview",
                alias = draft.alias,
                host = draft.host,
                port = draft.port,
                username = draft.username,
                authMethod = draft.authMethod,
                encryptedSecret = "",
            )
        override suspend fun deleteServer(serverId: String) = Unit
        override suspend fun decryptCredentials(profile: SshServerProfile): DecryptedSshCredentials =
            DecryptedSshCredentials(secret = "")
        override suspend fun updateHostKeyFingerprint(serverId: String, fingerprintSha256: String, algorithm: String?) = Unit
        override suspend fun markServerConnected(serverId: String, timestampMillis: Long) = Unit
        override suspend fun saveCustomPreset(preset: CustomSshPreset): CustomSshPreset = preset
        override suspend fun deleteCustomPreset(presetId: String) = Unit
        override suspend fun saveRunRecord(record: SshExecutionRecord) = Unit
        override suspend fun deleteRunRecord(runId: String) = Unit
        override suspend fun clearRunRecords() = Unit
    }

    override val sshSessionManager: com.nodeseek.app.ui.ssh.LiteSshSessionManager by lazy {
        com.nodeseek.app.ui.ssh.LiteSshSessionManager(
            vaultRepository = sshVaultRepository,
            executor = com.nodeseek.app.domain.ssh.LiteSshExecutor(
                transport = com.nodeseek.app.data.ssh.JSchSshTransport(),
            ),
            externalScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
            ),
            enableElapsedTicker = false,
        )
    }

    override val overseasIntelRepository: OverseasIntelRepository = PreviewOverseasIntelRepository()

    override val overseasPromoMonitorController: OverseasPromoMonitorController =
        object : OverseasPromoMonitorController {
            private val settingsState = MutableStateFlow(OverseasPromoMonitorSettings())
            override val settingsFlow: StateFlow<OverseasPromoMonitorSettings> = settingsState.asStateFlow()
            override val runtimeStatus: StateFlow<OverseasPromoRuntimeStatus> =
                MutableStateFlow(OverseasPromoRuntimeStatus(isRunningInForeground = true)).asStateFlow()

            override fun start() = Unit
            override fun stop() = Unit
            override fun onForegroundChanged(inForeground: Boolean) = Unit
            override fun triggerImmediateCheck() = Unit
            override suspend fun setNotificationsEnabled(enabled: Boolean) {
                settingsState.update { it.copy(notificationsEnabled = enabled) }
            }
            override suspend fun updateSettings(
                transform: (OverseasPromoMonitorSettings) -> OverseasPromoMonitorSettings,
            ): OverseasPromoMonitorSettings {
                val next = transform(settingsState.value).normalized()
                settingsState.value = next
                return next
            }
            override suspend fun checkNow(manualTrigger: Boolean): OverseasPromoPollOutcome =
                OverseasPromoPollOutcome.Completed()
            override suspend fun runBackgroundPollPass(): OverseasPromoPollOutcome =
                OverseasPromoPollOutcome.Completed()
        }
}

private val SHOWCASE_AVATAR_POOL = intArrayOf(
    R.drawable.showcase_avatar_01,
    R.drawable.showcase_avatar_02,
    R.drawable.showcase_avatar_03,
    R.drawable.showcase_avatar_04,
    R.drawable.showcase_avatar_05,
    R.drawable.showcase_avatar_06,
    R.drawable.showcase_avatar_07,
    R.drawable.showcase_avatar_08,
    R.drawable.showcase_avatar_09,
)

internal fun resolveShowcaseAvatarRes(username: String): Int {
    return when (username.trim()) {
        "latency_hunter" -> R.drawable.showcase_avatar_01
        "bgp_artisan" -> R.drawable.showcase_avatar_02
        "bgp_watcher" -> R.drawable.showcase_avatar_03
        "cloud_nomad" -> R.drawable.showcase_avatar_04
        "compose_craft" -> R.drawable.showcase_avatar_05
        "tokyo_core" -> R.drawable.showcase_avatar_06
        "ipv6_pilot" -> R.drawable.showcase_avatar_07
        "mtr_scanner" -> R.drawable.showcase_avatar_08
        else -> {
            val hash = username.trim().hashCode() and 0x7FFFFFFF
            SHOWCASE_AVATAR_POOL[hash % SHOWCASE_AVATAR_POOL.size]
        }
    }
}

@Composable
internal fun PreviewPhoneFrame(
    themeMode: PulseThemeMode,
    purePitchAmoled: Boolean = false,
    dynamicColor: Boolean = false,
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
        LocalSellerTrustRepository provides container.sellerTrustRepository,
        LocalViewModelStoreOwner provides vmStoreOwner,
        LocalOnBackPressedDispatcherOwner provides backDispatcherOwner,
        LocalActivityResultRegistryOwner provides activityResultRegistryOwner,
        LocalPreviewAvatarResResolver provides ::resolveShowcaseAvatarRes,
    ) {
        NodePulseTheme(
            themeMode = themeMode,
            purePitchAmoled = purePitchAmoled,
            dynamicColor = dynamicColor,
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
