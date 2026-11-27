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
import com.nodeseek.app.domain.model.AccountSlotStatus
import com.nodeseek.app.domain.model.AccountSlotSummary
import com.nodeseek.app.domain.model.AccountSwitchResult
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.BookmarkedTopic
import com.nodeseek.app.domain.model.CheckInStatus
import com.nodeseek.app.domain.model.DailyQuotaProgress
import com.nodeseek.app.domain.model.DirectMessage
import com.nodeseek.app.domain.model.DirectMessageSummary
import com.nodeseek.app.domain.model.NotificationItem
import com.nodeseek.app.domain.model.NotificationKind
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
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
import com.nodeseek.app.domain.repository.MultiAccountManager
import com.nodeseek.app.domain.repository.NoOpMultiAccountManager
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
class PulseHubMultiAccountTest {

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
    fun `PulseHubViewModel exposes saved accounts, triggers switch challenge callback, and cancels pending native add account`() = runTest(testDispatcher) {
        val alice = AccountSession(uid = 1001L, username = "Alice", drumsticks = 400)
        val bob = AccountSession(uid = 2002L, username = "Bob", drumsticks = 900)

        val sessionFlow = MutableStateFlow<AccountSession?>(alice)
        val accountsFlow = MutableStateFlow(
            listOf(
                AccountSlotSummary(1001L, "Alice", "", 2, 400, 5, true, AccountSlotStatus.ACTIVE, 200L),
                AccountSlotSummary(2002L, "Bob", "", 3, 900, 10, false, AccountSlotStatus.READY, 100L),
            ),
        )
        val pendingAddFlow = MutableStateFlow(false)
        var cancelCalled = 0

        val fakeMultiAccount = object : MultiAccountManager {
            override val accountsFlow: StateFlow<List<AccountSlotSummary>> = accountsFlow
            override val isSwitchingFlow: StateFlow<Boolean> = MutableStateFlow(false)
            override val pendingAddAccountFlow: StateFlow<Boolean> = pendingAddFlow

            override suspend fun initializeAndSyncActiveAccount() = Unit
            override suspend fun switchAccount(targetUid: Long): AccountSwitchResult {
                sessionFlow.value = bob
                return AccountSwitchResult.ChallengeRequired(bob)
            }
            override suspend fun beginAddAccount(): Result<Unit> {
                pendingAddFlow.value = true
                sessionFlow.value = null
                return Result.success(Unit)
            }
            override suspend fun cancelAddAccountIfUnauthenticated(): AccountSession? {
                cancelCalled++
                pendingAddFlow.value = false
                sessionFlow.value = alice
                return alice
            }
            override suspend fun removeAccount(uid: Long): Result<Unit> {
                accountsFlow.value = accountsFlow.value.filterNot { it.uid == uid }
                return Result.success(Unit)
            }
            override suspend fun onActiveAccountSignedOut(signedOutUid: Long?) = Unit
        }

        val container = createHubContainer(sessionFlow = sessionFlow, multiAccountManager = fakeMultiAccount)
        val vm = PulseHubViewModel(container)
        val events = mutableListOf<String>()
        val uiJob = launch { vm.uiState.collect {} }
        val eventJob = launch { vm.events.collect { events += it } }
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.accounts.size)

        var challengeTriggered = false
        vm.switchAccount(2002L) { challengeTriggered = true }
        advanceUntilIdle()
        assertTrue(challengeTriggered)
        assertTrue(events.last().contains("安全验证"))

        vm.beginAddAccountWithNativeSignIn()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.nativeSignIn.isVisible)
        assertTrue(pendingAddFlow.value)

        vm.dismissNativeSignInDialog(cancelPendingAddAccount = true)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.nativeSignIn.isVisible)
        assertEquals(1, cancelCalled)
        assertTrue(events.last().contains("Alice"))

        vm.removeSavedAccount(2002L)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.accounts.size)

        uiJob.cancel()
        eventJob.cancel()
    }

    @Test
    fun `direct A to B account switch clears Alice bookmarks and dailyQuota even when Bob fetches fail`() = runTest(testDispatcher) {
        val alice = AccountSession(uid = 101L, username = "Alice", drumsticks = 500)
        val bob = AccountSession(uid = 202L, username = "Bob", drumsticks = 800)
        val sessionFlow = MutableStateFlow<AccountSession?>(alice)

        val checkInStatusFlow = MutableStateFlow<CheckInStatus?>(null)
        val checkInRepo = object : CheckInRepository {
            override val checkInStatus: StateFlow<CheckInStatus?> = checkInStatusFlow
            override fun clearCachedStatus() {
                checkInStatusFlow.value = null
            }
            override suspend fun getCheckInBoard(page: Int, forceRefresh: Boolean): Result<CheckInStatus> =
                if (sessionFlow.value?.uid == 101L) {
                    CheckInStatus(hasCheckedInToday = true, todayGain = 5).also { checkInStatusFlow.value = it }.let { Result.success(it) }
                } else {
                    Result.failure(RuntimeException("Bob check-in failed"))
                }
            override suspend fun checkIn(randomReward: Boolean): Result<CheckInStatus> =
                Result.failure(UnsupportedOperationException())
            override suspend fun getDailyProgress(): Result<DailyQuotaProgress> =
                if (sessionFlow.value?.uid == 101L) {
                    Result.success(DailyQuotaProgress(postBonusEarned = 2, replyBonusEarned = 3, freeDrumsticksUsed = 1))
                } else {
                    Result.failure(RuntimeException("Bob quota failed"))
                }
        }
        val bookmarkRepo = object : BookmarkAndHistoryRepository {
            override suspend fun setTopicBookmarked(topicId: Long, bookmarked: Boolean): Result<Unit> = Result.success(Unit)
            override suspend fun getServerBookmarks(page: Int): Result<Pair<List<TopicSummary>, Boolean>> =
                if (sessionFlow.value?.uid == 101L) {
                    Result.success(listOf(TopicSummary(id = 9001L, title = "Alice Bookmark", categorySlug = "daily", categoryName = "日常", authorName = "Alice")) to false)
                } else {
                    Result.failure(RuntimeException("Bob bookmarks failed"))
                }
            override fun observeLocalBookmarks(): Flow<List<BookmarkedTopic>> = flowOf(emptyList())
            override fun observeBookmarkedIds(): Flow<Set<Long>> = flowOf(emptySet())
            override fun observeReadHistory(): Flow<List<ReadHistoryEntry>> = flowOf(emptyList())
            override fun observeReadStateMap(topicIds: Collection<Long>): Flow<Map<Long, ReadHistoryEntry>> = flowOf(emptyMap())
            override suspend fun recordTopicVisit(entry: ReadHistoryEntry) = Unit
            override suspend fun clearReadHistory() = Unit
        }

        val container = createHubContainer(
            sessionFlow = sessionFlow,
            multiAccountManager = NoOpMultiAccountManager,
            checkInRepositoryOverride = checkInRepo,
            bookmarkRepositoryOverride = bookmarkRepo,
        )
        val vm = PulseHubViewModel(container)
        val uiJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(listOf(9001L), vm.uiState.value.bookmarks.map { it.topicId })
        assertEquals(2, vm.uiState.value.dailyQuota?.postBonusEarned)

        // Direct A -> B switch where Bob's telemetry fetches fail
        sessionFlow.value = bob
        advanceUntilIdle()

        assertEquals(bob.uid, vm.uiState.value.session?.uid)
        assertTrue("Alice bookmarks must not leak into Bob on failure", vm.uiState.value.bookmarks.isEmpty())
        assertNull("Alice dailyQuota must not leak into Bob on failure", vm.uiState.value.dailyQuota)
        assertNull(vm.uiState.value.checkInStatus)

        uiJob.cancel()
    }

    @Test
    fun `in-flight Alice refreshAll and loadMoreServerBookmarks cannot overwrite Bob state after A to B switch`() = runTest(testDispatcher) {
        val alice = AccountSession(uid = 101L, username = "Alice", drumsticks = 500)
        val bob = AccountSession(uid = 202L, username = "Bob", drumsticks = 800)
        val sessionFlow = MutableStateFlow<AccountSession?>(alice)

        val aliceRefreshGate = CompletableDeferred<Result<Pair<List<TopicSummary>, Boolean>>>()
        val alicePage2Gate = CompletableDeferred<Result<Pair<List<TopicSummary>, Boolean>>>()
        val aliceQuotaGate = CompletableDeferred<Result<DailyQuotaProgress>>()
        var useAliceGates = false
        var gateLoadMoreOnly = false

        val checkInRepo = object : CheckInRepository {
            override val checkInStatus: StateFlow<CheckInStatus?> = MutableStateFlow(null)
            override fun clearCachedStatus() = Unit
            override suspend fun getCheckInBoard(page: Int, forceRefresh: Boolean): Result<CheckInStatus> =
                Result.success(CheckInStatus(hasCheckedInToday = false))
            override suspend fun checkIn(randomReward: Boolean): Result<CheckInStatus> =
                Result.success(CheckInStatus(hasCheckedInToday = true))
            override suspend fun getDailyProgress(): Result<DailyQuotaProgress> = when {
                useAliceGates && sessionFlow.value?.uid == 101L -> aliceQuotaGate.await()
                sessionFlow.value?.uid == 101L -> Result.success(DailyQuotaProgress(postBonusEarned = 1))
                else -> Result.success(DailyQuotaProgress(postBonusEarned = 4))
            }
        }
        val bookmarkRepo = object : BookmarkAndHistoryRepository {
            override suspend fun setTopicBookmarked(topicId: Long, bookmarked: Boolean): Result<Unit> = Result.success(Unit)
            override suspend fun getServerBookmarks(page: Int): Result<Pair<List<TopicSummary>, Boolean>> = when {
                gateLoadMoreOnly && page == 2 -> alicePage2Gate.await()
                useAliceGates && page == 1 -> aliceRefreshGate.await()
                sessionFlow.value?.uid == 101L -> Result.success(listOf(TopicSummary(id = 9001L, title = "Alice P1", categorySlug = "daily", categoryName = "日常", authorName = "Alice")) to true)
                else -> Result.success(listOf(TopicSummary(id = 9002L, title = "Bob P1", categorySlug = "daily", categoryName = "日常", authorName = "Bob")) to false)
            }
            override fun observeLocalBookmarks(): Flow<List<BookmarkedTopic>> = flowOf(emptyList())
            override fun observeBookmarkedIds(): Flow<Set<Long>> = flowOf(emptySet())
            override fun observeReadHistory(): Flow<List<ReadHistoryEntry>> = flowOf(emptyList())
            override fun observeReadStateMap(topicIds: Collection<Long>): Flow<Map<Long, ReadHistoryEntry>> = flowOf(emptyMap())
            override suspend fun recordTopicVisit(entry: ReadHistoryEntry) = Unit
            override suspend fun clearReadHistory() = Unit
        }

        val container = createHubContainer(
            sessionFlow = sessionFlow,
            multiAccountManager = NoOpMultiAccountManager,
            checkInRepositoryOverride = checkInRepo,
            bookmarkRepositoryOverride = bookmarkRepo,
        )
        val vm = PulseHubViewModel(container)
        val uiJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(listOf(9001L), vm.uiState.value.bookmarks.map { it.topicId })
        assertTrue(vm.uiState.value.hasMoreServerBookmarks)

        // 1. Start Alice loadMoreServerBookmarks (page 2) in flight, then switch to Bob
        gateLoadMoreOnly = true
        vm.loadMoreServerBookmarks()
        testScheduler.runCurrent()

        sessionFlow.value = bob
        advanceUntilIdle()
        assertEquals(listOf(9002L), vm.uiState.value.bookmarks.map { it.topicId })
        assertFalse(vm.uiState.value.hasMoreServerBookmarks)

        // Complete Alice's stale page-2 call: must NOT append 9003L to Bob's bookmarks!
        alicePage2Gate.complete(Result.success(listOf(TopicSummary(id = 9003L, title = "Alice P2", categorySlug = "daily", categoryName = "日常", authorName = "Alice")) to false))
        advanceUntilIdle()
        assertEquals(listOf(9002L), vm.uiState.value.bookmarks.map { it.topicId })
        assertFalse(vm.uiState.value.hasMoreServerBookmarks)

        // 2. Switch back to Alice, start refreshAll() in flight, then switch to Bob before Alice completes
        gateLoadMoreOnly = false
        sessionFlow.value = alice
        advanceUntilIdle()
        assertEquals(listOf(9001L), vm.uiState.value.bookmarks.map { it.topicId })

        useAliceGates = true
        vm.refreshAll()
        testScheduler.runCurrent()
        assertTrue(vm.uiState.value.isRefreshing)

        sessionFlow.value = bob
        useAliceGates = false
        advanceUntilIdle()
        assertEquals(listOf(9002L), vm.uiState.value.bookmarks.map { it.topicId })
        assertEquals(4, vm.uiState.value.dailyQuota?.postBonusEarned)
        assertFalse(vm.uiState.value.isRefreshing)

        // Complete Alice's stale refreshAll() responses: must NOT overwrite Bob's bookmarks or quota!
        aliceRefreshGate.complete(Result.success(listOf(TopicSummary(id = 9001L, title = "Alice Stale Refresh", categorySlug = "daily", categoryName = "日常", authorName = "Alice")) to true))
        aliceQuotaGate.complete(Result.success(DailyQuotaProgress(postBonusEarned = 1)))
        advanceUntilIdle()
        assertEquals(listOf(9002L), vm.uiState.value.bookmarks.map { it.topicId })
        assertEquals(4, vm.uiState.value.dailyQuota?.postBonusEarned)
        assertFalse(vm.uiState.value.isRefreshing)

        uiJob.cancel()
    }

    private fun createHubContainer(
        sessionFlow: MutableStateFlow<AccountSession?>,
        multiAccountManager: MultiAccountManager,
        checkInRepositoryOverride: CheckInRepository? = null,
        bookmarkRepositoryOverride: BookmarkAndHistoryRepository? = null,
    ): AppContainer {
        val settingsFlow = MutableStateFlow(AppSettings())
        val settingsRepo = Proxy.newProxyInstance(
            SettingsRepository::class.java.classLoader,
            arrayOf(SettingsRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getSettings" -> settingsFlow
                "getRecentSearchQueries" -> MutableStateFlow(emptyList<String>())
                else -> Unit
            }
        } as SettingsRepository

        return object : AppContainer {
            override val sessionRepository: SessionRepository = object : SessionRepository {
                override val sessionState: StateFlow<AccountSession?> = sessionFlow
                override val challengeRequired: StateFlow<Boolean> = MutableStateFlow(false)
                override val authVerifiedEpoch: StateFlow<Long> = MutableStateFlow(0L)
                override suspend fun refreshSession(): Result<AccountSession?> = Result.success(sessionFlow.value)
                override suspend fun signIn(username: String, password: String, turnstileToken: String): Result<SignInStepResult> =
                    Result.success(SignInStepResult.Authenticated)
                override suspend fun verifyTwoFactor(otpCode: String, otpSession: String): Result<Unit> = Result.success(Unit)
                override suspend fun onWebViewAuthCompleted(): Result<AccountSession?> = Result.success(sessionFlow.value)
                override suspend fun onWebViewDismissed(): Result<AccountSession?> = Result.success(sessionFlow.value)
                override suspend fun signOut(): Result<Unit> = Result.success(Unit)
            }
            override val multiAccountManager: MultiAccountManager = multiAccountManager
            override val settingsRepository: SettingsRepository = settingsRepo
            override val checkInRepository: CheckInRepository = checkInRepositoryOverride ?: object : CheckInRepository {
                override val checkInStatus: StateFlow<CheckInStatus?> = MutableStateFlow(null)
                override fun clearCachedStatus() = Unit
                override suspend fun getCheckInBoard(page: Int, forceRefresh: Boolean): Result<CheckInStatus> =
                    Result.success(CheckInStatus(hasCheckedInToday = false))
                override suspend fun checkIn(randomReward: Boolean): Result<CheckInStatus> =
                    Result.success(CheckInStatus(hasCheckedInToday = true))
                override suspend fun getDailyProgress(): Result<DailyQuotaProgress> =
                    Result.success(DailyQuotaProgress())
            }
            override val inboxRepository: InboxRepository = object : InboxRepository {
                override val unreadTotalCount: StateFlow<Int> = MutableStateFlow(0)
                override val replyNotifications: StateFlow<List<NotificationItem>> = MutableStateFlow(emptyList())
                override val mentionNotifications: StateFlow<List<NotificationItem>> = MutableStateFlow(emptyList())
                override val messageThreads: StateFlow<List<DirectMessageSummary>> = MutableStateFlow(emptyList())
                override val inAppPopupEvents: Flow<InAppMessagePopup> = emptyFlow()
                override val unreadIncreaseEvents: Flow<Int> = emptyFlow()
                override suspend fun refreshUnreadCount(): Result<Int> = Result.success(0)
                override suspend fun syncForegroundInbox(forceListSync: Boolean): Result<Unit> = Result.success(Unit)
                override suspend fun getNotifications(kind: NotificationKind): Result<List<NotificationItem>> = Result.success(emptyList())
                override suspend fun markNotificationsRead(kind: NotificationKind, ids: List<Long>?): Result<Unit> = Result.success(Unit)
                override suspend fun getMessageThreads(): Result<List<DirectMessageSummary>> = Result.success(emptyList())
                override suspend fun getConversation(peerUid: Long): Result<List<DirectMessage>> = Result.success(emptyList())
                override suspend fun markThreadRead(peerUid: Long): Result<Unit> = Result.success(Unit)
                override suspend fun markMessagesRead(messageIds: List<Long>?): Result<Unit> = Result.success(Unit)
                override suspend fun sendDirectMessage(receiverUid: Long, content: String, isMarkdown: Boolean): Result<Unit> = Result.success(Unit)
            }
            override val bookmarkAndHistoryRepository: BookmarkAndHistoryRepository = bookmarkRepositoryOverride ?: object : BookmarkAndHistoryRepository {
                override suspend fun setTopicBookmarked(topicId: Long, bookmarked: Boolean): Result<Unit> = Result.success(Unit)
                override suspend fun getServerBookmarks(page: Int): Result<Pair<List<TopicSummary>, Boolean>> = Result.success(emptyList<TopicSummary>() to false)
                override fun observeLocalBookmarks(): Flow<List<BookmarkedTopic>> = flowOf(emptyList())
                override fun observeBookmarkedIds(): Flow<Set<Long>> = flowOf(emptySet())
                override fun observeReadHistory(): Flow<List<ReadHistoryEntry>> = flowOf(emptyList())
                override fun observeReadStateMap(topicIds: Collection<Long>): Flow<Map<Long, ReadHistoryEntry>> = flowOf(emptyMap())
                override suspend fun recordTopicVisit(entry: ReadHistoryEntry) = Unit
                override suspend fun clearReadHistory() = Unit
            }
            override val forumRepository: ForumRepository get() = unused()
            override val userRepository: UserRepository get() = unused()
            override val draftRepository: DraftRepository get() = unused()
            override val imageHostRepository: ImageHostRepository get() = unused()
            override val seekMailRepository: SeekMailRepository get() = unused()
            override val radarRepository: RadarRepository get() = unused()
            override val radarMonitorController: RadarMonitorController get() = unused()
            override val contentFilterRepository: ContentFilterRepository get() = unused()
            override val notificationDispatcher: PulseNotificationDispatcher get() = error("unused")
            override val appUpdateManager: AppUpdateManager get() = unused()
            override val backupManager: BackupManager get() = unused()
            override val exchangeRateRepository: ExchangeRateRepository get() = error("unused")
            override val restockRepository: RestockRepository get() = unused()
            override val restockMonitorController: RestockMonitorController get() = unused()
            override val restockNotificationHelper: RestockNotificationHelper get() = error("unused")
            override val flareSolverrClient: FlareSolverrClient get() = error("unused")
            override val activityRepository: ActivityRepository get() = unused()
            override val sellerTrustRepository: SellerTrustRepository get() = unused()
            override val fleetRepository: FleetRepository get() = unused()
            override val fleetWorkScheduler: FleetWorkScheduler get() = error("unused")
            override val netBenchHistoryStore: NetBenchHistoryStore get() = error("unused")
            override val sshVaultRepository: SshVaultRepository get() = unused()
            override val sshSessionManager: LiteSshSessionManager get() = error("unused")
            override val overseasIntelRepository: OverseasIntelRepository get() = unused()
        }
    }

    private inline fun <reified T : Any> unused(): T =
        Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ ->
            throw UnsupportedOperationException("Unused stub call: ${T::class.java.simpleName}.${method.name}")
        } as T
}
