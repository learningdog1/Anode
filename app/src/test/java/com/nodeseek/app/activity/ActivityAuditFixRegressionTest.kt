package com.nodeseek.app.activity

import android.content.Context
import android.content.ContextWrapper
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequest
import androidx.work.testing.TestListenableWorkerBuilder
import com.nodeseek.app.data.activity.ActivityMonitorControllerImpl
import com.nodeseek.app.data.activity.ActivityMonitorHost
import com.nodeseek.app.data.activity.ActivityMonitorWorker
import com.nodeseek.app.data.activity.ActivityPreferencesStore
import com.nodeseek.app.data.activity.ActivityWorkManagerGateway
import com.nodeseek.app.data.activity.ActivityWorkScheduler
import com.nodeseek.app.data.activity.ForumWatchActivityApiClient
import com.nodeseek.app.data.activity.ForumWatchActivityRepositoryImpl
import com.nodeseek.app.data.activity.ForumWatchActivitySecretVault
import com.nodeseek.app.data.overseas.OverseasApiCrypto
import com.nodeseek.app.domain.activity.ActivityFeedSnapshot
import com.nodeseek.app.domain.activity.ActivityKind
import com.nodeseek.app.domain.activity.ActivityMonitorController
import com.nodeseek.app.domain.activity.ActivityPollOutcome
import com.nodeseek.app.domain.activity.ActivityRepository
import com.nodeseek.app.domain.activity.ActivityTimeStatus
import com.nodeseek.app.domain.activity.ForumWatchActivityDetail
import com.nodeseek.app.domain.activity.ForumWatchActivityItem
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityAuditFixRegressionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun secretVaultDisablesRedirectsAndCookiesAndSupportsBackupPin() {
        val validSha256B64 = Base64.getEncoder().encodeToString(ByteArray(32) { 0x42 })
        val backupPin = "sha256/$validSha256B64"
        val client = ForumWatchActivitySecretVault.buildPinnedHttpClient(
            backupSpkiPins = listOf(backupPin),
        )
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals(CookieJar.NO_COOKIES, client.cookieJar)
        assertTrue(client.certificatePinner.pins.any { it.toString().contains(validSha256B64) })
    }

    @Test
    fun apiClientDoesNotHangWhenBodyReadFailsMidResponse() = runBlocking {
        val secret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".toByteArray(Charsets.UTF_8)
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("x".repeat(8192))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        server.start()
        try {
            val client = ForumWatchActivityApiClient(
                baseUrlProvider = { server.url("/").toString() },
                masterSecretProvider = { secret.copyOf() },
                httpClient = OkHttpClient(),
                maxAttempts = 1,
                sleeper = {},
            )
            val err = withTimeout(5_000L) {
                runCatching { client.fetchActivitiesPage() }.exceptionOrNull()
            }
            assertTrue("Expected IOException, got $err", err is IOException)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun monitorDeduplicatesAutoToManualUpgradeAndDoesNotFloodOnStaleReEnable() = runTest {
        var nowMs = 1_763_820_000_000L
        val prefs = ActivityPreferencesStore(
            storeFile = File(tempFolder.root, "activity_dedup_prefs.json"),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val autoItem = ForumWatchActivityItem(
            id = "nodeseek-620001",
            title = "【抽奖】自动归集帖子",
            sourceUrl = "https://www.nodeseek.com/post-620001-1",
            status = ActivityTimeStatus.ONGOING,
            kind = ActivityKind.LOTTERY,
            sourceId = "nodeseek",
            topicId = "620001",
            luckyUrl = "https://www.nodeseek.com/lucky?post=620001&time=${nowMs + 7_200_000L}&count=1",
        )
        val manualUpgradedSameTopic = ForumWatchActivityItem(
            id = "manual-curated-620001",
            title = "【置顶精选】人工校准同帖抽奖",
            sourceUrl = "https://www.nodeseek.com/post-620001-1#floor-1",
            status = ActivityTimeStatus.ONGOING,
            kind = ActivityKind.LOTTERY,
            pinned = true,
            sourceId = "manual",
            luckyUrl = "https://www.nodeseek.com/lucky?post=620001&time=${nowMs + 7_200_000L}&count=1",
        )

        val fakeRepo = object : ActivityRepository {
            var serverSnap = ActivityFeedSnapshot(
                activities = listOf(autoItem),
                lastSyncedEpochMillis = nowMs,
                loadedFromCache = false,
            )
            private val _snapshot = MutableStateFlow(serverSnap)
            override val snapshot: StateFlow<ActivityFeedSnapshot> = _snapshot.asStateFlow()

            fun setSnapshot(snap: ActivityFeedSnapshot) {
                _snapshot.value = snap
            }

            override suspend fun loadCachedActivities() = _snapshot.value
            override suspend fun refreshActivities(force: Boolean): Result<ActivityFeedSnapshot> {
                _snapshot.value = serverSnap
                return Result.success(serverSnap)
            }
            override suspend fun getActivityDetail(id: String, forceRefresh: Boolean): Result<ForumWatchActivityDetail> =
                Result.failure(NoSuchElementException(id))
        }

        val notified = mutableListOf<ForumWatchActivityItem>()
        val dispatcher = object : PulseNotificationDispatcher(object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }) {
            override fun ensureChannels() = Unit
            override fun hasPostNotificationsPermission(): Boolean = true
            override fun postCommunityActivityNotifications(activities: List<ForumWatchActivityItem>) {
                notified += activities
            }
        }

        val controller = ActivityMonitorControllerImpl(
            repository = fakeRepo,
            preferencesStore = prefs,
            notificationDispatcher = dispatcher,
            scope = this,
            clockMillis = { nowMs },
            startupDelayMillis = 0L,
        )

        // Enable with fresh online snapshot containing autoItem -> seeds both id and ns_topic:620001
        controller.setNotificationsEnabled(true)
        advanceUntilIdle()
        assertTrue(notified.isEmpty())

        // Next poll upgrades autoItem -> manualUpgradedSameTopic (same NodeSeek topic 620001, different ID!)
        nowMs += 180_000L
        fakeRepo.serverSnap = ActivityFeedSnapshot(
            activities = listOf(manualUpgradedSameTopic),
            lastSyncedEpochMillis = nowMs,
            loadedFromCache = false,
        )
        val upgradeOutcome = controller.runBackgroundPollPass() as ActivityPollOutcome.Completed
        assertEquals(0, upgradeOutcome.newActivitiesNotified)
        assertTrue("Upgrading auto->manual for same topicId must not double-notify", notified.isEmpty())

        // Disable, wait a day while new items accumulate, re-enable with stale disk cache -> 0 flood
        controller.setNotificationsEnabled(false)
        nowMs += 86_400_000L
        val missedWhileOff = ForumWatchActivityItem(
            id = "missed-900",
            title = "【福利】关闭期间新增活动",
            sourceUrl = "https://www.nodeseek.com/post-699900-1",
            status = ActivityTimeStatus.ONGOING,
            kind = ActivityKind.GIVEAWAY,
            sourceId = "nodeseek",
            topicId = "699900",
        )
        fakeRepo.setSnapshot(
            ActivityFeedSnapshot(
                activities = listOf(manualUpgradedSameTopic),
                lastSyncedEpochMillis = nowMs - 86_400_000L,
                loadedFromCache = true,
            ),
        )
        fakeRepo.serverSnap = ActivityFeedSnapshot(
            activities = listOf(missedWhileOff, manualUpgradedSameTopic),
            lastSyncedEpochMillis = nowMs,
            loadedFromCache = false,
        )
        controller.setNotificationsEnabled(true)
        advanceUntilIdle()
        assertTrue("Re-enabling with stale disk cache must not flood missed activities", notified.isEmpty())
        assertTrue(prefs.isBaselineInitialized())
    }

    @Test
    fun repositoryLoadNextPageAppendsAndDeduplicatesActivities() = runTest {
        val secret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".toByteArray(Charsets.UTF_8)
        val aesKey = OverseasApiCrypto.deriveAesKey(secret)
        val server = MockWebServer()
        val page1 = """
            {"ok":true,"page":1,"pageSize":1,"total":2,"hasMore":true,"counts":{"all":2,"ongoing":2,"upcoming":0,"ended":0},"items":[
              {"id":"act-1","title":"活动1","sourceUrl":"https://www.nodeseek.com/post-700001-1","status":"ongoing","kind":"event","sourceId":"nodeseek","topicId":"700001"}
            ]}
        """.trimIndent()
        val page2 = """
            {"ok":true,"page":2,"pageSize":1,"total":2,"hasMore":false,"counts":{"all":2,"ongoing":2,"upcoming":0,"ended":0},"items":[
              {"id":"act-2","title":"活动2","sourceUrl":"https://www.nodeseek.com/post-700002-1","status":"ongoing","kind":"event","sourceId":"nodeseek","topicId":"700002"}
            ]}
        """.trimIndent()

        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("X-Content-Encrypted", "AES-256-GCM")
                .setBody(OverseasApiCrypto.encryptEnvelope(aesKey, page1)),
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("X-Content-Encrypted", "AES-256-GCM")
                .setBody(OverseasApiCrypto.encryptEnvelope(aesKey, page2)),
        )
        server.start()

        try {
            val client = ForumWatchActivityApiClient(
                baseUrlProvider = { server.url("/").toString() },
                masterSecretProvider = { secret.copyOf() },
                httpClient = OkHttpClient(),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                sleeper = {},
            )
            val repo = ForumWatchActivityRepositoryImpl(
                apiClient = client,
                listCacheFile = File(tempFolder.root, "act_p_list.json"),
                detailCacheFile = File(tempFolder.root, "act_p_detail.json"),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                externalScope = backgroundScope,
            )

            val snap1 = repo.refreshActivities(force = true).getOrThrow()
            assertEquals(1, snap1.activities.size)
            assertTrue(snap1.hasMore)
            assertEquals(1, snap1.currentPage)

            val snap2 = repo.loadNextPage().getOrThrow()
            assertEquals(2, snap2.activities.size)
            assertFalse(snap2.hasMore)
            assertEquals(2, snap2.currentPage)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun activityMonitorWorkerRetriesOnUninitializedPropertyAccessException() = runTest {
        val coldHostContext = object : ContextWrapper(null), ActivityMonitorHost {
            override fun getApplicationContext(): Context = this
            override val activityMonitorController: ActivityMonitorController
                get() = throw UninitializedPropertyAccessException("lateinit property container has not been initialized")
        }
        val worker = TestListenableWorkerBuilder<ActivityMonitorWorker>(coldHostContext).build()
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
    }

    @Test
    fun activityWorkSchedulerObserveAwaitsLazyDiskLoadAndNeverCancelsPersistedEnabledWork() = runTest {
        val storeFile = File(tempFolder.root, "activity_cold_start_prefs.json")
        val seedStore = ActivityPreferencesStore(
            storeFile = storeFile,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        seedStore.setNotificationsEnabled(enabled = true, seedBaselineIds = listOf("act-1"))
        assertTrue(seedStore.getSettings().notificationsEnabled)

        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val coldStore = ActivityPreferencesStore(
            storeFile = storeFile,
            ioDispatcher = ioDispatcher,
        )
        assertFalse("Fresh lazy store starts unloaded with default false", coldStore.settingsFlow.value.notificationsEnabled)

        var enqueueCount = 0
        var cancelCount = 0
        val gateway = object : ActivityWorkManagerGateway {
            override fun enqueueUniquePeriodicWork(
                uniqueWorkName: String,
                existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
                request: PeriodicWorkRequest,
            ) {
                enqueueCount++
            }

            override fun cancelUniqueWork(uniqueWorkName: String) {
                cancelCount++
            }
        }
        val schedulerScope = CoroutineScope(ioDispatcher)
        val scheduler = ActivityWorkScheduler(gateway)
        try {
            scheduler.observe(schedulerScope, coldStore.settingsFlow)
            scheduler.onAppEnteredBackground(coldStore.settingsFlow.value)
            advanceUntilIdle()

            assertEquals("observe() must never cancelUniqueWork before lazy disk load completes", 0, cancelCount)
            assertEquals(
                "observe() should enqueue periodic work directly from persisted true " +
                    "(enabled=${coldStore.settingsFlow.value.notificationsEnabled}, fileExists=${storeFile.exists()}, len=${storeFile.length()})",
                1,
                enqueueCount,
            )
            assertTrue(coldStore.settingsFlow.value.notificationsEnabled)
        } finally {
            scheduler.stopObserving()
            schedulerScope.cancel()
        }
    }
}
