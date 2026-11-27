package com.nodeseek.app.ui.hub

import com.nodeseek.app.data.fleet.FleetWorkScheduler
import com.nodeseek.app.data.netbench.NetBenchHistoryStore
import com.nodeseek.app.data.restock.FlareSolverrClient
import com.nodeseek.app.data.restock.RestockNotificationHelper
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.activity.ActivityRepository
import com.nodeseek.app.domain.backup.BackupManager
import com.nodeseek.app.domain.filter.ContentFilterRepository
import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.mail.SeekMailRepository
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.BookmarkedTopic
import com.nodeseek.app.domain.model.CheckInStatus
import com.nodeseek.app.domain.model.DailyQuotaProgress
import com.nodeseek.app.domain.model.DirectMessage
import com.nodeseek.app.domain.model.DirectMessageSummary
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.NotificationItem
import com.nodeseek.app.domain.model.NotificationKind
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.model.UserProfile
import com.nodeseek.app.domain.model.UserReplyExcerpt
import com.nodeseek.app.domain.overseas.OverseasIntelRepository
import com.nodeseek.app.domain.radar.InAppMessagePopup
import com.nodeseek.app.domain.radar.RadarMonitorController
import com.nodeseek.app.domain.radar.RadarRepository
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
import com.nodeseek.app.domain.restock.RestockMonitorController
import com.nodeseek.app.domain.restock.RestockRepository
import com.nodeseek.app.domain.ssh.SshVaultRepository
import com.nodeseek.app.domain.trust.SellerTrustRepository
import com.nodeseek.app.domain.update.AppUpdateManager
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import com.nodeseek.app.ui.ssh.LiteSshSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class HubAuditRegressionTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `normalizeDirectMessages deduplicates positive ids and sorts ascending`() {
        val list = listOf(
            DirectMessage(id = 30L, senderUid = 2L, receiverUid = 1L, body = "third", createdAtIso = "2026-11-23T10:02:00Z"),
            DirectMessage(id = 10L, senderUid = 1L, receiverUid = 2L, body = "first-old", createdAtIso = "2026-11-23T10:00:00Z"),
            DirectMessage(id = 10L, senderUid = 1L, receiverUid = 2L, body = "first-new", createdAtIso = "2026-11-23T10:00:01Z"),
            DirectMessage(id = 20L, senderUid = 2L, receiverUid = 1L, body = "second", createdAtIso = "2026-11-23T10:01:00Z"),
        )
        val normalized = normalizeDirectMessages(list)
        assertEquals(listOf(10L, 20L, 30L), normalized.map { it.id })
        assertEquals("first-new", normalized.first().body)
    }

    @Test
    fun `UserProfileViewModel deduplicates overlapping paginated replies and halts pagination`() = runTest(testDispatcher) {
        val r1 = UserReplyExcerpt(topicId = 100L, topicTitle = "Topic 100", floorNumber = 2, plainTextExcerpt = "hello", createdAtIso = "2026-11-23T01:00:00Z")
        val r2 = UserReplyExcerpt(topicId = 101L, topicTitle = "Topic 101", floorNumber = 5, plainTextExcerpt = "world", createdAtIso = "2026-11-23T02:00:00Z")
        val fakeUserRepo = object : BaseFakeUserRepository() {
            override suspend fun getUserProfile(uid: Long, includeEditableSignature: Boolean): Result<UserProfile> =
                Result.success(UserProfile(uid = uid, username = "alice", replyCount = 20))

            override suspend fun getUserTopics(uid: Long, page: Int): Result<Pair<List<TopicSummary>, Boolean>> =
                Result.success(emptyList<TopicSummary>() to false)

            override suspend fun getUserReplies(uid: Long, page: Int): Result<Pair<List<UserReplyExcerpt>, Boolean>> =
                when (page) {
                    1 -> Result.success(listOf(r1, r2, r1) to true)
                    else -> Result.success(listOf(r1, r2) to true)
                }
        }
        val container = HubTestAppContainer(userRepository = fakeUserRepo)

        val vm = UserProfileViewModel(container = container, uid = 42L, initialUsernameHint = "alice")
        val collector = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.replies.size)
        assertTrue(vm.uiState.value.hasMoreReplies)

        vm.loadMoreReplies()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.replies.size)
        assertFalse("Pagination should stop when merged page adds zero new replies", vm.uiState.value.hasMoreReplies)
        collector.cancel()
    }

    @Test
    fun `InboxViewModel searchUsers debounces rapid input and surfaces failure error`() = runTest(testDispatcher) {
        val queried = mutableListOf<String>()
        val fakeUserRepo = object : BaseFakeUserRepository() {
            override suspend fun searchUsers(keyword: String): Result<List<FloorAuthor>> {
                queried += keyword
                return if (keyword == "fail") {
                    Result.failure(IllegalStateException("网络超时"))
                } else {
                    Result.success(listOf(FloorAuthor(uid = 99L, username = keyword)))
                }
            }
        }
        val container = HubTestAppContainer(userRepository = fakeUserRepo)

        val vm = InboxViewModel(container = container)
        val collector = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.searchUsers("a")
        advanceTimeBy(100L)
        vm.searchUsers("al")
        advanceTimeBy(100L)
        vm.searchUsers("alice")
        advanceTimeBy(InboxViewModel.USER_SEARCH_DEBOUNCE_MS + 50L)
        advanceUntilIdle()

        assertEquals(listOf("alice"), queried)
        assertEquals(1, vm.uiState.value.userSearchResults.size)
        assertNull(vm.uiState.value.userSearchError)

        vm.searchUsers("fail")
        advanceTimeBy(InboxViewModel.USER_SEARCH_DEBOUNCE_MS + 50L)
        advanceUntilIdle()

        assertEquals("网络超时", vm.uiState.value.userSearchError)
        assertTrue(vm.uiState.value.userSearchResults.isEmpty())
        collector.cancel()
    }

    @Test
    fun `PulseHubViewModel surfaces remote unbookmark error and confirms signed-in history bookmark`() = runTest(testDispatcher) {
        val fakeSessionRepo = BaseFakeSessionRepository(
            initialSession = AccountSession(uid = 1001L, username = "tester", permissionRank = 3),
        )

        var unbookmarkShouldFail = true
        val savedBookmarks = MutableStateFlow(
            listOf(
                BookmarkedTopic(
                    topicId = 777L,
                    summary = TopicSummary(
                        id = 777L,
                        title = "Keep me on failure",
                        categorySlug = "tech",
                        categoryName = "技术",
                        authorName = "author",
                    ),
                    savedAtEpochMillis = 1000L,
                    isSyncedWithServer = true,
                ),
            ),
        )
        val fakeBookmarkRepo = object : BaseFakeBookmarkRepository(savedBookmarks) {
            override suspend fun setTopicBookmarked(topicId: Long, bookmarked: Boolean): Result<Unit> {
                if (!bookmarked && unbookmarkShouldFail) {
                    return Result.failure(IllegalStateException("服务端取消收藏失败"))
                }
                return Result.success(Unit)
            }
        }

        val container = HubTestAppContainer(
            sessionRepository = fakeSessionRepo,
            bookmarkAndHistoryRepository = fakeBookmarkRepo,
        )

        val vm = PulseHubViewModel(container)
        val events = mutableListOf<String>()
        val uiCollector = launch { vm.uiState.collect {} }
        val eventCollector = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        // 1. Remote unbookmark failure must NOT delete local bookmark or emit fake success
        vm.removeBookmark(777L)
        advanceUntilIdle()
        assertEquals(1, savedBookmarks.value.size)
        assertTrue(events.last().contains("服务端取消收藏失败"))

        // 2. Signed-in toggleHistoryBookmark updates isSyncedWithServer=true and emits confirmation
        val entry = ReadHistoryEntry(
            topicId = 888L,
            title = "History Topic",
            categorySlug = "trade",
            categoryName = "交易",
            authorName = "seller",
            visitedAtEpochMillis = 3000L,
        )
        vm.toggleHistoryBookmark(entry)
        advanceUntilIdle()
        val added = savedBookmarks.value.first { it.topicId == 888L }
        assertTrue(added.isSyncedWithServer)
        assertEquals("已加入我的收藏", events.last())

        uiCollector.cancel()
        eventCollector.cancel()
    }

    @Test
    fun `HubCollectionViewModel does not trigger check-in or inbox requests on launch`() = runTest(testDispatcher) {
        val fakeSessionRepo = BaseFakeSessionRepository(
            initialSession = AccountSession(uid = 2002L, username = "collector", permissionRank = 4),
        )
        var checkInCalled = false
        var dailyProgressCalled = false
        var inboxUnreadCalled = false
        var serverBookmarksCalled = false

        val fakeCheckInRepo = object : BaseFakeCheckInRepository() {
            override suspend fun getCheckInBoard(page: Int, forceRefresh: Boolean): Result<CheckInStatus> {
                checkInCalled = true
                return Result.success(CheckInStatus(hasCheckedInToday = true))
            }

            override suspend fun getDailyProgress(): Result<DailyQuotaProgress> {
                dailyProgressCalled = true
                return Result.success(DailyQuotaProgress())
            }
        }
        val fakeInboxRepo = object : BaseFakeInboxRepository() {
            override suspend fun refreshUnreadCount(): Result<Int> {
                inboxUnreadCalled = true
                return Result.success(0)
            }
        }
        val fakeBookmarkRepo = object : BaseFakeBookmarkRepository() {
            override suspend fun getServerBookmarks(page: Int): Result<Pair<List<TopicSummary>, Boolean>> {
                serverBookmarksCalled = true
                return Result.success(emptyList<TopicSummary>() to false)
            }
        }

        val container = HubTestAppContainer(
            sessionRepository = fakeSessionRepo,
            checkInRepository = fakeCheckInRepo,
            inboxRepository = fakeInboxRepo,
            bookmarkAndHistoryRepository = fakeBookmarkRepo,
        )

        val vm = HubCollectionViewModel(container)
        val collector = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertTrue(serverBookmarksCalled)
        assertFalse("HubCollectionViewModel must not call getCheckInBoard", checkInCalled)
        assertFalse("HubCollectionViewModel must not call getDailyProgress", dailyProgressCalled)
        assertFalse("HubCollectionViewModel must not call refreshUnreadCount", inboxUnreadCalled)
        collector.cancel()
    }

    @Test
    fun `headless PulseHubViewModel and HubCollectionViewModel toggleHistoryBookmark remove existing local bookmarks without uiState collector`() = runTest(testDispatcher) {
        val existingBookmark = BookmarkedTopic(
            topicId = 555L,
            summary = TopicSummary(
                id = 555L,
                title = "Already Bookmarked",
                categorySlug = "tech",
                categoryName = "技术",
                authorName = "author",
                isBookmarked = true,
            ),
            savedAtEpochMillis = 1000L,
            isSyncedWithServer = false,
        )
        val savedBookmarks = MutableStateFlow(listOf(existingBookmark))
        val fakeBookmarkRepo = BaseFakeBookmarkRepository(localBookmarks = savedBookmarks)
        val container = HubTestAppContainer(
            sessionRepository = BaseFakeSessionRepository(initialSession = null),
            bookmarkAndHistoryRepository = fakeBookmarkRepo,
        )

        val entry = ReadHistoryEntry(
            topicId = 555L,
            title = "Already Bookmarked",
            categorySlug = "tech",
            categoryName = "技术",
            authorName = "author",
            visitedAtEpochMillis = 2000L,
        )

        // 1. PulseHubViewModel without uiState collector removes existing bookmark on toggle
        val hubVm = PulseHubViewModel(container)
        hubVm.toggleHistoryBookmark(entry)
        advanceUntilIdle()
        assertTrue("Headless toggleHistoryBookmark must remove existing bookmark", savedBookmarks.value.isEmpty())

        // Toggling again adds it back
        hubVm.toggleHistoryBookmark(entry)
        advanceUntilIdle()
        assertEquals(listOf(555L), savedBookmarks.value.map { it.topicId })

        // 2. HubCollectionViewModel without uiState collector also removes existing bookmark on toggle
        val collectionVm = HubCollectionViewModel(container)
        collectionVm.toggleHistoryBookmark(entry)
        advanceUntilIdle()
        assertTrue("Headless HubCollectionViewModel toggleHistoryBookmark must remove existing bookmark", savedBookmarks.value.isEmpty())
    }

    @Test
    fun `cold-start toggleHistoryBookmark waits for delayed cold Room Flow before assuming unbookmarked`() = runTest(testDispatcher) {
        val existingBookmark = BookmarkedTopic(
            topicId = 555L,
            summary = TopicSummary(
                id = 555L,
                title = "Cold Room Bookmarked",
                categorySlug = "tech",
                categoryName = "技术",
                authorName = "author",
                isBookmarked = true,
            ),
            savedAtEpochMillis = 1000L,
            isSyncedWithServer = false,
        )
        val entry555 = ReadHistoryEntry(
            topicId = 555L,
            title = "Cold Room Bookmarked",
            categorySlug = "tech",
            categoryName = "技术",
            authorName = "author",
            visitedAtEpochMillis = 2000L,
        )
        val entry666 = ReadHistoryEntry(
            topicId = 666L,
            title = "Cold Room Unbookmarked",
            categorySlug = "tech",
            categoryName = "技术",
            authorName = "author",
            visitedAtEpochMillis = 2500L,
        )

        class ColdRoomBookmarkRepository : BaseFakeBookmarkRepository() {
            var stored: MutableMap<Long, BookmarkedTopic> = linkedMapOf(555L to existingBookmark)
            val removedIds = mutableListOf<Long>()
            val savedIds = mutableListOf<Long>()

            // Cold delayed Flows (not StateFlow) to mirror Room's async initial query window
            override fun observeLocalBookmarks(): Flow<List<BookmarkedTopic>> = flow {
                delay(50L)
                emit(stored.values.toList())
            }

            override fun observeBookmarkedIds(): Flow<Set<Long>> = flow {
                delay(50L)
                emit(stored.keys.toSet())
            }

            override suspend fun saveOfflineBookmark(
                summary: TopicSummary,
                detailSnapshot: TopicDetailPage?,
                syncedWithServer: Boolean,
            ) {
                savedIds += summary.id
                stored[summary.id] = BookmarkedTopic(
                    topicId = summary.id,
                    summary = summary,
                    offlineDetailSnapshot = detailSnapshot,
                    savedAtEpochMillis = 2000L,
                    isSyncedWithServer = syncedWithServer,
                )
            }

            override suspend fun removeLocalBookmark(topicId: Long) {
                removedIds += topicId
                stored.remove(topicId)
            }
        }

        // 1. PulseHubViewModel cold start before init collectors emit
        val hubRepo = ColdRoomBookmarkRepository()
        val hubVm = PulseHubViewModel(
            HubTestAppContainer(
                sessionRepository = BaseFakeSessionRepository(initialSession = null),
                bookmarkAndHistoryRepository = hubRepo,
            ),
        )
        hubVm.toggleHistoryBookmark(entry555)
        hubVm.toggleHistoryBookmark(entry666)
        advanceUntilIdle()
        assertEquals(listOf(555L), hubRepo.removedIds)
        assertEquals(listOf(666L), hubRepo.savedIds)
        assertFalse(hubRepo.stored.containsKey(555L))
        assertTrue(hubRepo.stored.containsKey(666L))

        // 2. HubCollectionViewModel cold start before init collectors emit
        val colRepo = ColdRoomBookmarkRepository()
        val collectionVm = HubCollectionViewModel(
            HubTestAppContainer(
                sessionRepository = BaseFakeSessionRepository(initialSession = null),
                bookmarkAndHistoryRepository = colRepo,
            ),
        )
        collectionVm.toggleHistoryBookmark(entry555)
        collectionVm.toggleHistoryBookmark(entry666)
        advanceUntilIdle()
        assertEquals(listOf(555L), colRepo.removedIds)
        assertEquals(listOf(666L), colRepo.savedIds)
        assertFalse(colRepo.stored.containsKey(555L))
        assertTrue(colRepo.stored.containsKey(666L))
    }
}

private inline fun <reified T : Any> unusedStub(): T =
    Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ ->
        throw UnsupportedOperationException("Unused stub call: ${T::class.java.simpleName}.${method.name}")
    } as T

private class BaseFakeSessionRepository(
    initialSession: AccountSession? = null,
) : SessionRepository {
    override val sessionState: StateFlow<AccountSession?> = MutableStateFlow(initialSession)
    override val challengeRequired: StateFlow<Boolean> = MutableStateFlow(false)
    override val authVerifiedEpoch: StateFlow<Long> = MutableStateFlow(0L)
    override suspend fun refreshSession(): Result<AccountSession?> = Result.success(sessionState.value)
    override suspend fun signIn(username: String, password: String, turnstileToken: String): Result<SignInStepResult> =
        Result.success(SignInStepResult.Authenticated)
    override suspend fun verifyTwoFactor(otpCode: String, otpSession: String): Result<Unit> = Result.success(Unit)
    override suspend fun onWebViewAuthCompleted(): Result<AccountSession?> = Result.success(sessionState.value)
    override suspend fun onWebViewDismissed(): Result<AccountSession?> = Result.success(sessionState.value)
    override suspend fun signOut(): Result<Unit> = Result.success(Unit)
}

private open class BaseFakeUserRepository : UserRepository {
    override suspend fun getUserProfile(uid: Long, includeEditableSignature: Boolean): Result<UserProfile> =
        Result.success(UserProfile(uid = uid, username = "user_$uid"))
    override suspend fun updateOwnProfile(bio: String, signature: String, readme: String): Result<Unit> =
        Result.success(Unit)
    override suspend fun getUserTopics(uid: Long, page: Int): Result<Pair<List<TopicSummary>, Boolean>> =
        Result.success(emptyList<TopicSummary>() to false)
    override suspend fun getUserReplies(uid: Long, page: Int): Result<Pair<List<UserReplyExcerpt>, Boolean>> =
        Result.success(emptyList<UserReplyExcerpt>() to false)
    override suspend fun searchUsers(keyword: String): Result<List<FloorAuthor>> =
        Result.success(emptyList())
    override suspend fun setFollowed(targetUid: Long, follow: Boolean): Result<Unit> = Result.success(Unit)
    override suspend fun getConnections(following: Boolean): Result<List<FloorAuthor>> = Result.success(emptyList())
    override suspend fun getBlockedUsers(): Result<List<FloorAuthor>> = Result.success(emptyList())
    override suspend fun blockUserByName(username: String): Result<Unit> = Result.success(Unit)
    override suspend fun unblockUserById(targetUid: Long): Result<Unit> = Result.success(Unit)
}

private open class BaseFakeBookmarkRepository(
    private val localBookmarks: MutableStateFlow<List<BookmarkedTopic>> = MutableStateFlow(emptyList()),
    private val history: MutableStateFlow<List<ReadHistoryEntry>> = MutableStateFlow(emptyList()),
) : BookmarkAndHistoryRepository {
    override suspend fun setTopicBookmarked(topicId: Long, bookmarked: Boolean): Result<Unit> = Result.success(Unit)
    override suspend fun getServerBookmarks(page: Int): Result<Pair<List<TopicSummary>, Boolean>> =
        Result.success(emptyList<TopicSummary>() to false)
    override fun observeLocalBookmarks(): Flow<List<BookmarkedTopic>> = localBookmarks
    override fun observeBookmarkedIds(): Flow<Set<Long>> = flowOf(emptySet())
    override suspend fun saveOfflineBookmark(
        summary: TopicSummary,
        detailSnapshot: TopicDetailPage?,
        syncedWithServer: Boolean,
    ) {
        localBookmarks.value = localBookmarks.value.filterNot { it.topicId == summary.id } +
            BookmarkedTopic(
                topicId = summary.id,
                summary = summary,
                offlineDetailSnapshot = detailSnapshot,
                savedAtEpochMillis = 2000L,
                isSyncedWithServer = syncedWithServer,
            )
    }
    override suspend fun removeLocalBookmark(topicId: Long) {
        localBookmarks.value = localBookmarks.value.filterNot { it.topicId == topicId }
    }
    override fun observeReadHistory(): Flow<List<ReadHistoryEntry>> = history
    override fun observeReadStateMap(topicIds: Collection<Long>): Flow<Map<Long, ReadHistoryEntry>> =
        flowOf(emptyMap())
    override suspend fun recordTopicVisit(entry: ReadHistoryEntry) = Unit
    override suspend fun clearReadHistory() {
        history.value = emptyList()
    }
}

private open class BaseFakeCheckInRepository : CheckInRepository {
    override val checkInStatus: StateFlow<CheckInStatus?> = MutableStateFlow(null)
    override fun clearCachedStatus() = Unit
    override suspend fun getCheckInBoard(page: Int, forceRefresh: Boolean): Result<CheckInStatus> =
        Result.success(CheckInStatus(hasCheckedInToday = false))
    override suspend fun checkIn(randomReward: Boolean): Result<CheckInStatus> =
        Result.success(CheckInStatus(hasCheckedInToday = true))
    override suspend fun getDailyProgress(): Result<DailyQuotaProgress> =
        Result.success(DailyQuotaProgress())
}

private open class BaseFakeInboxRepository : InboxRepository {
    override val unreadTotalCount: StateFlow<Int> = MutableStateFlow(0)
    override val replyNotifications: StateFlow<List<NotificationItem>> = MutableStateFlow(emptyList())
    override val mentionNotifications: StateFlow<List<NotificationItem>> = MutableStateFlow(emptyList())
    override val messageThreads: StateFlow<List<DirectMessageSummary>> = MutableStateFlow(emptyList())
    override val inAppPopupEvents: Flow<InAppMessagePopup> = emptyFlow()
    override val unreadIncreaseEvents: Flow<Int> = emptyFlow()
    override suspend fun refreshUnreadCount(): Result<Int> = Result.success(0)
    override suspend fun syncForegroundInbox(forceListSync: Boolean): Result<Unit> = Result.success(Unit)
    override suspend fun getNotifications(kind: NotificationKind): Result<List<NotificationItem>> =
        Result.success(emptyList())
    override suspend fun markNotificationsRead(kind: NotificationKind, ids: List<Long>?): Result<Unit> =
        Result.success(Unit)
    override suspend fun getMessageThreads(): Result<List<DirectMessageSummary>> = Result.success(emptyList())
    override suspend fun getConversation(peerUid: Long): Result<List<DirectMessage>> = Result.success(emptyList())
    override suspend fun markThreadRead(peerUid: Long): Result<Unit> = Result.success(Unit)
    override suspend fun markMessagesRead(messageIds: List<Long>?): Result<Unit> = Result.success(Unit)
    override suspend fun sendDirectMessage(receiverUid: Long, content: String, isMarkdown: Boolean): Result<Unit> =
        Result.success(Unit)
}

private class HubTestAppContainer(
    override val sessionRepository: SessionRepository = BaseFakeSessionRepository(),
    override val checkInRepository: CheckInRepository = BaseFakeCheckInRepository(),
    override val inboxRepository: InboxRepository = BaseFakeInboxRepository(),
    override val userRepository: UserRepository = BaseFakeUserRepository(),
    override val bookmarkAndHistoryRepository: BookmarkAndHistoryRepository = BaseFakeBookmarkRepository(),
) : AppContainer {
    private val settingsFlow = MutableStateFlow(AppSettings())
    override val settingsRepository: SettingsRepository = Proxy.newProxyInstance(
        SettingsRepository::class.java.classLoader,
        arrayOf(SettingsRepository::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getSettings" -> settingsFlow
            "getRecentSearchQueries" -> MutableStateFlow(emptyList<String>())
            else -> Unit
        }
    } as SettingsRepository

    override val forumRepository: ForumRepository get() = unusedStub()
    override val draftRepository: DraftRepository get() = unusedStub()
    override val imageHostRepository: ImageHostRepository get() = unusedStub()
    override val seekMailRepository: SeekMailRepository get() = unusedStub()
    override val radarRepository: RadarRepository get() = unusedStub()
    override val radarMonitorController: RadarMonitorController get() = unusedStub()
    override val contentFilterRepository: ContentFilterRepository get() = unusedStub()
    override val notificationDispatcher: PulseNotificationDispatcher get() = error("unused")
    override val appUpdateManager: AppUpdateManager get() = unusedStub()
    override val backupManager: BackupManager get() = unusedStub()
    override val exchangeRateRepository: ExchangeRateRepository get() = error("unused")
    override val restockRepository: RestockRepository get() = unusedStub()
    override val restockMonitorController: RestockMonitorController get() = unusedStub()
    override val restockNotificationHelper: RestockNotificationHelper get() = error("unused")
    override val flareSolverrClient: FlareSolverrClient get() = error("unused")
    override val activityRepository: ActivityRepository get() = unusedStub()
    override val sellerTrustRepository: SellerTrustRepository get() = unusedStub()
    override val fleetRepository: FleetRepository get() = unusedStub()
    override val fleetWorkScheduler: FleetWorkScheduler get() = error("unused")
    override val netBenchHistoryStore: NetBenchHistoryStore get() = error("unused")
    override val sshVaultRepository: SshVaultRepository get() = unusedStub()
    override val sshSessionManager: LiteSshSessionManager get() = error("unused")
    override val overseasIntelRepository: OverseasIntelRepository get() = unusedStub()
}
