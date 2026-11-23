package com.nodeseek.app.activity

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import com.nodeseek.app.data.activity.ActivityMonitorControllerImpl
import com.nodeseek.app.data.activity.ActivityMonitorHost
import com.nodeseek.app.data.activity.ActivityPreferencesStore
import com.nodeseek.app.data.activity.ActivityWorkManagerGateway
import com.nodeseek.app.data.activity.ActivityWorkScheduler
import com.nodeseek.app.data.activity.ForumWatchActivityApiClient
import com.nodeseek.app.data.activity.ForumWatchActivityRepositoryImpl
import com.nodeseek.app.data.activity.ForumWatchActivitySecretVault
import com.nodeseek.app.data.activity.ForumWatchApiException
import com.nodeseek.app.data.overseas.OverseasApiCrypto
import com.nodeseek.app.domain.activity.ActivityFeedSnapshot
import com.nodeseek.app.domain.activity.ActivityKind
import com.nodeseek.app.domain.activity.ActivityMonitorController
import com.nodeseek.app.domain.activity.ActivityMonitorSettings
import com.nodeseek.app.domain.activity.ActivityPollOutcome
import com.nodeseek.app.domain.activity.ActivityRepository
import com.nodeseek.app.domain.activity.ActivityTimeStatus
import com.nodeseek.app.domain.activity.ForumWatchActivityDetail
import com.nodeseek.app.domain.activity.ForumWatchActivityItem
import com.nodeseek.app.navigation.ActivityRoute
import com.nodeseek.app.navigation.TopicDetailRoute
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import com.nodeseek.app.ui.activity.ActivityToggleDecision
import com.nodeseek.app.ui.activity.resolveActivityToggleDecision
import com.nodeseek.app.ui.activity.resolveEffectiveActivityNotificationsEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalCoroutinesApi::class)
class ForumWatchActivityClientAndMonitorTest {

    private lateinit var tempDir: File
    private lateinit var mockServer: MockWebServer

    private val testSecretBytes = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        .toByteArray(Charsets.UTF_8)
    private val testHmacKey = OverseasApiCrypto.deriveHmacKey(testSecretBytes)
    private val testAesKey = OverseasApiCrypto.deriveAesKey(testSecretBytes)

    private class FakeActivityAppContext(
        private val controllerProvider: (() -> ActivityMonitorController)? = null,
    ) : ContextWrapper(null), ActivityMonitorHost {
        override fun getApplicationContext(): Context = this
        override val activityMonitorController: ActivityMonitorController
            get() = controllerProvider?.invoke() ?: error("No activityMonitorController registered")
    }

    private class RecordingActivityNotificationDispatcher(
        var permissionGranted: Boolean = true,
    ) : PulseNotificationDispatcher(FakeActivityAppContext()) {
        val dispatchedBatches = mutableListOf<List<ForumWatchActivityItem>>()
        val dispatchedItems = mutableListOf<ForumWatchActivityItem>()

        override fun ensureChannels() = Unit
        override fun hasPostNotificationsPermission(): Boolean = permissionGranted

        override fun postCommunityActivityNotifications(activities: List<ForumWatchActivityItem>) {
            dispatchedBatches.add(activities)
            dispatchedItems.addAll(activities)
        }
    }

    private class RecordingActivityWorkGateway : ActivityWorkManagerGateway {
        var enqueueCount = 0
        var cancelCount = 0
        var lastWorkName: String? = null
        var lastPolicy: ExistingPeriodicWorkPolicy? = null
        var isCurrentlyEnqueued = false

        override fun enqueueUniquePeriodicWork(
            uniqueWorkName: String,
            existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
            request: PeriodicWorkRequest,
        ) {
            enqueueCount++
            lastWorkName = uniqueWorkName
            lastPolicy = existingPeriodicWorkPolicy
            isCurrentlyEnqueued = true
        }

        override fun cancelUniqueWork(uniqueWorkName: String) {
            cancelCount++
            lastWorkName = uniqueWorkName
            isCurrentlyEnqueued = false
        }
    }

    private class FakeActivityRepository(
        initialSnapshot: ActivityFeedSnapshot = ActivityFeedSnapshot(),
    ) : ActivityRepository {
        private val _snapshot = MutableStateFlow(initialSnapshot)
        override val snapshot: StateFlow<ActivityFeedSnapshot> = _snapshot.asStateFlow()

        var nextRefreshResult: Result<ActivityFeedSnapshot>? = null
        var refreshCallCount = 0

        fun setActivities(items: List<ForumWatchActivityItem>, updatedAtMs: Long = 1_763_800_000_000L) {
            val snap = ActivityFeedSnapshot(
                activities = items,
                updatedAtEpochMillis = updatedAtMs,
                lastSyncedEpochMillis = updatedAtMs,
                totalCount = items.size,
            )
            _snapshot.value = snap
        }

        override suspend fun loadCachedActivities(): ActivityFeedSnapshot = _snapshot.value

        override suspend fun refreshActivities(force: Boolean): Result<ActivityFeedSnapshot> {
            refreshCallCount++
            nextRefreshResult?.let { custom ->
                custom.onSuccess { _snapshot.value = it }
                return custom
            }
            return Result.success(_snapshot.value)
        }

        override suspend fun getActivityDetail(
            id: String,
            forceRefresh: Boolean,
        ): Result<ForumWatchActivityDetail> {
            val found = _snapshot.value.activities.firstOrNull { it.id == id }
                ?: return Result.failure(NoSuchElementException(id))
            return Result.success(ForumWatchActivityDetail(item = found, bodyMarkdown = found.summary))
        }
    }

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("forumwatch_activity_test").toFile()
        mockServer = MockWebServer()
        mockServer.start()
    }

    @After
    fun tearDown() {
        runCatching { mockServer.shutdown() }
        tempDir.deleteRecursively()
    }

    @Test
    fun `vault decodes pinned endpoint, SPKI pin, master secret, and X509 certificate SHA256 integrity`() {
        val baseUrl = ForumWatchActivitySecretVault.resolveBaseUrl()
        assertTrue(baseUrl.startsWith("https://"))
        val parsedUrl = baseUrl.toHttpUrl()
        assertTrue(parsedUrl.host.isNotBlank())
        assertTrue(parsedUrl.port in 1..65535)

        val spkiPin = ForumWatchActivitySecretVault.resolveSpkiPin()
        assertTrue(spkiPin.startsWith("sha256/"))
        assertEquals(51, spkiPin.length)

        val secret = ForumWatchActivitySecretVault.resolveMasterSecret()
        assertEquals(64, secret.size)
        val derivedHmac = OverseasApiCrypto.deriveHmacKey(secret)
        val derivedAes = OverseasApiCrypto.deriveAesKey(secret)
        assertEquals(32, derivedHmac.size)
        assertEquals(32, derivedAes.size)
        assertFalse(derivedHmac.contentEquals(derivedAes))

        val cert = ForumWatchActivitySecretVault.resolvePinnedCertificate()
        assertTrue(cert.subjectX500Principal.name.contains(parsedUrl.host))

        val actualSha256 = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        assertTrue(actualSha256.contentEquals(ForumWatchActivitySecretVault.resolveExpectedCertSha256()))

        val client = ForumWatchActivitySecretVault.buildPinnedHttpClient()
        assertFalse(client.retryOnConnectionFailure)
    }

    @Test
    fun `api client signs HMAC headers, decrypts AES-256-GCM list and wrapped detail payloads, and handles ETag 304`() = runTest {
        val nowSec = 1_763_820_000L
        val client = ForumWatchActivityApiClient(
            baseUrlProvider = { mockServer.url("/").toString() },
            masterSecretProvider = { testSecretBytes.copyOf() },
            httpClient = OkHttpClient(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            nowSecondsProvider = { nowSec },
            sleeper = {},
        )

        val listPlainJson = """
            {
              "ok": true,
              "updatedAtEpochMillis": 1763820000000,
              "serverTimeEpochMillis": 1763820000123,
              "page": 1,
              "pageSize": 50,
              "page_size": 50,
              "total": 1,
              "hasMore": false,
              "has_more": false,
              "counts": { "all": 1, "ongoing": 1, "upcoming": 0, "ended": 0 },
              "items": [
                {
                  "id": "nodeseek-619999",
                  "title": "【抽奖】DMIT 东京年付小鸡一台",
                  "summary": "回帖参与抽奖",
                  "sourceUrl": "https://www.nodeseek.com/post-619999-1",
                  "organizer": "dmit_bot",
                  "organizerUrl": "https://www.nodeseek.com/space/100",
                  "startsAtEpochMillis": 1763800000000,
                  "endsAtEpochMillis": 1763900000000,
                  "status": "ongoing",
                  "kind": "lottery",
                  "pinned": true,
                  "sourceId": "nodeseek",
                  "topicId": "619999",
                  "category": "日常",
                  "categorySlug": "daily",
                  "luckyUrl": "https://lucky.nodeseek.com/?post=619999",
                  "drawFloor": 188,
                  "prize": "TYO Pro Tiny × 1",
                  "participation": "评论区留言",
                  "tags": ["抽奖", "VPS"],
                  "createdAtEpochMillis": 1763800000000,
                  "updatedAtEpochMillis": 1763810000000
                }
              ]
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"fw-rev1\"")
                .setHeader("X-Content-Encrypted", "AES-256-GCM")
                .setBody(OverseasApiCrypto.encryptEnvelope(testAesKey, listPlainJson)),
        )

        val firstPage = client.fetchActivitiesPageConditional(page = 1, pageSize = 50)
        assertFalse(firstPage.notModified)
        assertEquals("\"fw-rev1\"", firstPage.etag)
        val pageData = checkNotNull(firstPage.data)
        assertEquals(1, pageData.items.size)
        val item = pageData.items.single()
        assertEquals("nodeseek-619999", item.id)
        assertEquals(ActivityKind.LOTTERY, item.kind)
        assertEquals(ActivityTimeStatus.ONGOING, item.status)
        assertEquals(619999L, item.resolvedNodeSeekTopicId())
        assertEquals(188, item.drawFloor)

        val recordedListReq = mockServer.takeRequest()
        val tsHeader = checkNotNull(recordedListReq.getHeader("X-Timestamp"))
        val nonceHeader = checkNotNull(recordedListReq.getHeader("X-Nonce"))
        val sigHeader = checkNotNull(recordedListReq.getHeader("X-Signature"))
        val expectedSig = OverseasApiCrypto.signRequest(
            hmacKey = testHmacKey,
            method = "GET",
            pathWithQuery = "/api/v1/activities?page=1&page_size=50&status=all",
            timestampSec = tsHeader,
            nonce = nonceHeader,
        )
        assertEquals(expectedSig, sigHeader)

        // Conditional ETag 304 check
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(304)
                .setHeader("ETag", "\"fw-rev1\""),
        )
        val secondCond = client.fetchActivitiesPageConditional(
            page = 1,
            pageSize = 50,
            ifNoneMatch = "\"fw-rev1\"",
        )
        assertTrue(secondCond.notModified)
        assertNull(secondCond.data)
        assertEquals("\"fw-rev1\"", secondCond.etag)
        val recordedCondReq = mockServer.takeRequest()
        assertEquals("\"fw-rev1\"", recordedCondReq.getHeader("If-None-Match"))

        // Wrapped detail endpoint { ok, updatedAtEpochMillis, serverTimeEpochMillis, item: { ... } }
        val detailPlainJson = """
            {
              "ok": true,
              "updatedAtEpochMillis": 1763810000000,
              "serverTimeEpochMillis": 1763820005000,
              "item": {
                "id": "nodeseek-619999",
                "title": "【抽奖】DMIT 东京年付小鸡一台",
                "summary": "回帖参与抽奖",
                "sourceUrl": "https://www.nodeseek.com/post-619999-1",
                "organizer": "dmit_bot",
                "status": "ongoing",
                "kind": "lottery",
                "pinned": true,
                "sourceId": "nodeseek",
                "topicId": "619999",
                "bodyMarkdown": "## 活动规则\n评论即可参与开奖！",
                "externalLinks": [
                  { "text": "官方开奖链接", "url": "https://lucky.nodeseek.com/?post=619999" }
                ]
              }
            }
        """.trimIndent()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"fw-detail-1\"")
                .setHeader("X-Content-Encrypted", "AES-256-GCM")
                .setBody(OverseasApiCrypto.encryptEnvelope(testAesKey, detailPlainJson)),
        )

        val detail = client.fetchActivityDetail("nodeseek-619999")
        assertEquals("nodeseek-619999", detail.item.id)
        assertTrue(detail.bodyMarkdown.contains("评论即可参与开奖"))
        assertEquals(1, detail.externalLinks.size)
        assertEquals("https://lucky.nodeseek.com/?post=619999", detail.externalLinks.single().url)
    }

    @Test
    fun `api client self-heals 401 clock skew from HTTP Date header and retries 502 with fresh nonce`() = runTest {
        val localSkewedSec = 1_763_800_000L
        val realServerSec = localSkewedSec + 600L
        val serverDateHeader = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            ZonedDateTime.ofInstant(Instant.ofEpochSecond(realServerSec), ZoneOffset.UTC),
        )

        var nonceCounter = 0
        val client = ForumWatchActivityApiClient(
            baseUrlProvider = { mockServer.url("/").toString() },
            masterSecretProvider = { testSecretBytes.copyOf() },
            httpClient = OkHttpClient(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            nowSecondsProvider = { localSkewedSec },
            nonceGenerator = { "nonce-${++nonceCounter}".padEnd(32, '0') },
            sleeper = {},
        )

        val emptyPagePlain = """
            {"ok":true,"updatedAtEpochMillis":0,"serverTimeEpochMillis":0,"page":1,"pageSize":50,"total":0,"hasMore":false,"counts":{"all":0,"ongoing":0,"upcoming":0,"ended":0},"items":[]}
        """.trimIndent()

        // 1st response: 401 due to skewed local clock, with authoritative HTTP Date header
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Date", serverDateHeader)
                .setBody("""{"ok":false,"error":{"code":"TIMESTAMP_EXPIRED","message":"skew"}}"""),
        )
        // 2nd response: transient 502 Bad Gateway
        mockServer.enqueue(MockResponse().setResponseCode(502).setBody("Bad Gateway"))
        // 3rd response: 200 OK
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-Content-Encrypted", "AES-256-GCM")
                .setBody(OverseasApiCrypto.encryptEnvelope(testAesKey, emptyPagePlain)),
        )

        val result = client.fetchActivitiesPage()
        assertEquals(0, result.total)

        val req1 = mockServer.takeRequest()
        val req2 = mockServer.takeRequest()
        val req3 = mockServer.takeRequest()

        assertEquals(localSkewedSec.toString(), req1.getHeader("X-Timestamp"))
        assertEquals(realServerSec.toString(), req2.getHeader("X-Timestamp"))
        assertEquals(realServerSec.toString(), req3.getHeader("X-Timestamp"))
        assertNotEquals(req1.getHeader("X-Nonce"), req2.getHeader("X-Nonce"))
        assertNotEquals(req2.getHeader("X-Nonce"), req3.getHeader("X-Nonce"))
    }

    @Test
    fun `api client respects large 429 Retry-After cooldown immediately`() = runTest {
        var nowSec = 1_763_820_000L
        val client = ForumWatchActivityApiClient(
            baseUrlProvider = { mockServer.url("/").toString() },
            masterSecretProvider = { testSecretBytes.copyOf() },
            httpClient = OkHttpClient(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            nowSecondsProvider = { nowSec },
            sleeper = {},
        )

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "45")
                .setBody("""{"ok":false,"error":{"code":"RATE_LIMITED","message":"Too many requests"}}"""),
        )

        val err1 = runCatching { client.fetchActivitiesPage() }.exceptionOrNull() as? ForumWatchApiException
        assertNotNull(err1)
        assertEquals(429, err1?.statusCode)
        assertEquals(1, mockServer.requestCount)

        // Second call within cooldown window fails fast locally without hitting network
        nowSec += 10L
        val err2 = runCatching { client.fetchActivitiesPage() }.exceptionOrNull() as? ForumWatchApiException
        assertNotNull(err2)
        assertEquals(429, err2?.statusCode)
        assertEquals(1, mockServer.requestCount)
    }

    @Test
    fun `repository caches ETag for 304, bypasses ETag on force refresh, deduplicates same NodeSeek post, and resolves live expiration`() = runTest {
        var clockMs = 1_763_820_000_000L
        val apiClient = ForumWatchActivityApiClient(
            baseUrlProvider = { mockServer.url("/").toString() },
            masterSecretProvider = { testSecretBytes.copyOf() },
            httpClient = OkHttpClient(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            nowSecondsProvider = { clockMs / 1000L },
            sleeper = {},
        )
        val listCacheFile = File(tempDir, "activities_list.json")
        val detailCacheFile = File(tempDir, "activities_detail.json")

        val repo = ForumWatchActivityRepositoryImpl(
            apiClient = apiClient,
            listCacheFile = listCacheFile,
            detailCacheFile = detailCacheFile,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            clockMillis = { clockMs },
            externalScope = backgroundScope,
        )

        // Payload has duplicate entries for NodeSeek post 620001 (one auto-collected, one manual pinned)
        val initialPayload = """
            {
              "ok": true,
              "updatedAtEpochMillis": 1763820000000,
              "serverTimeEpochMillis": 1763820000000,
              "page": 1,
              "pageSize": 50,
              "total": 2,
              "hasMore": false,
              "counts": { "all": 2, "ongoing": 2, "upcoming": 0, "ended": 0 },
              "items": [
                {
                  "id": "nodeseek-620001",
                  "title": "自动归集标题",
                  "summary": "自动摘要",
                  "sourceUrl": "https://www.nodeseek.com/post-620001-1",
                  "organizer": "author_a",
                  "endsAtEpochMillis": 1763820060000,
                  "status": "ongoing",
                  "kind": "lottery",
                  "pinned": false,
                  "sourceId": "nodeseek",
                  "topicId": "620001",
                  "luckyUrl": "https://www.nodeseek.com/lucky?post=620001&time=1763820060000&count=1&start=1&duplicate=false&mode=view",
                  "updatedAtEpochMillis": 1763819000000
                },
                {
                  "id": "manual-curated-1",
                  "title": "【置顶精选】人工校准标题",
                  "summary": "人工精选摘要",
                  "sourceUrl": "https://www.nodeseek.com/post-620001-1#floor-1",
                  "organizer": "author_a",
                  "endsAtEpochMillis": 1763820060000,
                  "status": "ongoing",
                  "kind": "lottery",
                  "pinned": true,
                  "sourceId": "manual",
                  "luckyUrl": "https://www.nodeseek.com/lucky?post=620001&time=1763820060000&count=1&start=1&duplicate=false&mode=view",
                  "updatedAtEpochMillis": 1763820000000
                }
              ]
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"etag-v1\"")
                .setHeader("X-Content-Encrypted", "AES-256-GCM")
                .setBody(OverseasApiCrypto.encryptEnvelope(testAesKey, initialPayload)),
        )

        val snap1 = repo.refreshActivities(force = false).getOrThrow()
        assertEquals(1, snap1.activities.size)
        assertEquals("manual-curated-1", snap1.activities.single().id)
        assertEquals(620001L, snap1.activities.single().resolvedNodeSeekTopicId())
        assertEquals(1, snap1.counts.ongoing)
        assertNull(mockServer.takeRequest().getHeader("If-None-Match"))

        // Advance clock past endsAtEpochMillis (60s later) and receive 304 Not Modified
        clockMs += 120_000L
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(304)
                .setHeader("ETag", "\"etag-v1\""),
        )
        val snap2 = repo.refreshActivities(force = false).getOrThrow()
        assertEquals("\"etag-v1\"", mockServer.takeRequest().getHeader("If-None-Match"))
        assertEquals(1, snap2.activities.size)
        // Live status transitions to ENDED even on a 304 cache hit!
        assertEquals(ActivityTimeStatus.ENDED, snap2.activities.single().resolveLiveStatus(clockMs))
        assertEquals(0, snap2.counts.ongoing)
        assertEquals(1, snap2.counts.ended)

        // Manual pull-to-refresh (force = true) bypasses If-None-Match
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"etag-v2\"")
                .setHeader("X-Content-Encrypted", "AES-256-GCM")
                .setBody(OverseasApiCrypto.encryptEnvelope(testAesKey, initialPayload)),
        )
        repo.refreshActivities(force = true).getOrThrow()
        assertNull(mockServer.takeRequest().getHeader("If-None-Match"))

        // Cold restart loads persisted disk cache immediately without network call
        val coldRepo = ForumWatchActivityRepositoryImpl(
            apiClient = apiClient,
            listCacheFile = listCacheFile,
            detailCacheFile = detailCacheFile,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            clockMillis = { clockMs },
            externalScope = backgroundScope,
        )
        val coldCached = coldRepo.loadCachedActivities()
        assertTrue(coldCached.loadedFromCache)
        assertEquals(1, coldCached.activities.size)
        assertEquals("manual-curated-1", coldCached.activities.single().id)
    }

    @Test
    fun `monitor defaults to false, cancels WorkManager when disabled, seeds baseline without spam, and notifies only for new active items`() = runTest {
        var nowMs = 1_763_820_000_000L
        val prefsFile = File(tempDir, "activity_monitor_prefs.json")
        val prefs = ActivityPreferencesStore(
            storeFile = prefsFile,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val fakeRepo = FakeActivityRepository()
        val notifSpy = RecordingActivityNotificationDispatcher(permissionGranted = true)
        val workGateway = RecordingActivityWorkGateway()
        val scheduler = ActivityWorkScheduler(workGateway)

        // 1. Default settings MUST have notificationsEnabled == false
        assertEquals(false, ActivityMonitorSettings().notificationsEnabled)
        assertEquals(false, prefs.settingsFlow.value.notificationsEnabled)

        scheduler.sync(prefs.settingsFlow.value)
        assertFalse(workGateway.isCurrentlyEnqueued)
        assertEquals(1, workGateway.cancelCount)

        val controller = ActivityMonitorControllerImpl(
            repository = fakeRepo,
            preferencesStore = prefs,
            notificationDispatcher = notifSpy,
            scope = backgroundScope,
            clockMillis = { nowMs },
            startupDelayMillis = 0L,
        )

        // While disabled, checkNow skips immediately without touching network
        val disabledOutcome = controller.checkNow()
        assertEquals(
            ActivityPollOutcome.Skipped(ActivityPollOutcome.Skipped.SkipReason.DISABLED),
            disabledOutcome,
        )
        assertEquals(0, fakeRepo.refreshCallCount)

        // 2. Preload historical activity on screen, then user toggles switch ON -> seeds baseline with 0 spam!
        val historicalItem = ForumWatchActivityItem(
            id = "hist-100",
            title = "已有历史活动",
            sourceUrl = "https://www.nodeseek.com/post-610100-1",
            status = ActivityTimeStatus.ONGOING,
            kind = ActivityKind.LOTTERY,
            sourceId = "nodeseek",
            topicId = "610100",
            luckyUrl = "https://www.nodeseek.com/lucky?post=610100&time=${nowMs + 7_200_000L}&count=1",
        )
        fakeRepo.setActivities(listOf(historicalItem), updatedAtMs = nowMs)

        controller.setNotificationsEnabled(true)
        scheduler.sync(prefs.settingsFlow.value)
        assertTrue(prefs.settingsFlow.value.notificationsEnabled)
        assertTrue(workGateway.isCurrentlyEnqueued)

        nowMs += 30_000L
        val firstEnabledPass = controller.checkNow(manualTrigger = true)
        assertTrue(firstEnabledPass is ActivityPollOutcome.Completed)
        assertEquals(0, notifSpy.dispatchedItems.size)

        // 3. Subsequent poll discovers:
        //    - 1 new ONGOING explicit-link lottery -> notifies as LOTTERY
        //    - 1 new non-lottery post mislabeled LOTTERY without link (even with ENDED status) -> normalized to EVENT & notifies
        //    - 3 already-ENDED explicit-link lotteries (server ENDED, forceEnded, draw-time expired) -> skipped
        nowMs += 180_000L
        val brandNewOngoing = ForumWatchActivityItem(
            id = "new-201",
            title = "【新抽奖】搬瓦工限量款免单抽奖",
            sourceUrl = "https://www.nodeseek.com/post-620201-1",
            status = ActivityTimeStatus.ONGOING,
            kind = ActivityKind.LOTTERY,
            sourceId = "nodeseek",
            topicId = "620201",
            luckyUrl = "https://www.nodeseek.com/lucky?post=620201&time=${nowMs + 3_600_000L}&count=1",
            endsAtEpochMillis = nowMs + 3_600_000L,
        )
        val brandNewAlreadyEnded = ForumWatchActivityItem(
            id = "new-ended-202",
            title = "【已开奖】锁帖或人工提前结案活动（无截止时间）",
            sourceUrl = "https://www.nodeseek.com/post-620202-1",
            startsAtEpochMillis = nowMs - 300_000L,
            endsAtEpochMillis = null,
            status = ActivityTimeStatus.ENDED,
            kind = ActivityKind.LOTTERY,
            sourceId = "nodeseek",
            topicId = "620202",
            luckyUrl = "https://lucky.nodeseek.com/?post=620202",
        )
        val brandNewForceEndedFutureDeadline = ForumWatchActivityItem(
            id = "new-force-ended-203",
            title = "【已提前开奖】人工 forceEnded 结案但原截止时间在未来",
            sourceUrl = "https://www.nodeseek.com/post-620203-1",
            startsAtEpochMillis = nowMs - 300_000L,
            endsAtEpochMillis = nowMs + 7_200_000L,
            status = ActivityTimeStatus.ENDED,
            kind = ActivityKind.LOTTERY,
            sourceId = "nodeseek",
            topicId = "620203",
            luckyUrl = "https://www.nodeseek.com/lucky?post=620203&time=${nowMs + 7_200_000L}&count=1",
        )
        val brandNewDrawTimePassed = ForumWatchActivityItem(
            id = "new-draw-passed-204",
            title = "【已到点开奖】URL 中开奖时间已过",
            sourceUrl = "https://www.nodeseek.com/post-620204-1",
            startsAtEpochMillis = nowMs - 7_200_000L,
            endsAtEpochMillis = null,
            status = ActivityTimeStatus.ONGOING,
            kind = ActivityKind.LOTTERY,
            sourceId = "nodeseek",
            topicId = "620204",
            luckyUrl = "https://www.nodeseek.com/lucky?post=620204&time=${nowMs - 60_000L}&count=1",
        )
        val brandNewNonLotteryMislabeled = ForumWatchActivityItem(
            id = "new-nonlottery-205",
            title = "抽奖器好像有bug，大家讨论下",
            sourceUrl = "https://www.nodeseek.com/post-620205-1",
            status = ActivityTimeStatus.ENDED,
            kind = ActivityKind.LOTTERY,
            sourceId = "nodeseek",
            topicId = "620205",
            luckyUrl = null,
        )
        assertEquals(ActivityTimeStatus.ENDED, brandNewAlreadyEnded.resolveLotteryStatusOrNull(nowMs))
        assertEquals(ActivityTimeStatus.ENDED, brandNewForceEndedFutureDeadline.resolveLotteryStatusOrNull(nowMs))
        assertEquals(ActivityTimeStatus.ENDED, brandNewDrawTimePassed.resolveLotteryStatusOrNull(nowMs))
        assertNull(brandNewNonLotteryMislabeled.resolveLotteryStatusOrNull(nowMs))

        fakeRepo.setActivities(
            listOf(
                brandNewOngoing,
                brandNewAlreadyEnded,
                brandNewForceEndedFutureDeadline,
                brandNewDrawTimePassed,
                brandNewNonLotteryMislabeled,
                historicalItem,
            ),
            updatedAtMs = nowMs,
        )

        val secondPass = controller.runBackgroundPollPass() as ActivityPollOutcome.Completed
        assertEquals(2, secondPass.newActivitiesNotified)
        assertEquals(listOf("new-201", "new-nonlottery-205"), notifSpy.dispatchedItems.map { it.id })
        assertEquals(ActivityKind.LOTTERY, notifSpy.dispatchedItems[0].kind)
        assertEquals(ActivityKind.EVENT, notifSpy.dispatchedItems[1].kind)

        // Re-running poll does NOT duplicate notifications
        nowMs += 180_000L
        val thirdPass = controller.runBackgroundPollPass() as ActivityPollOutcome.Completed
        assertEquals(0, thirdPass.newActivitiesNotified)
        assertEquals(2, notifSpy.dispatchedItems.size)

        // 4. Turning switch OFF cancels WorkManager immediately
        controller.setNotificationsEnabled(false)
        scheduler.sync(prefs.settingsFlow.value)
        assertFalse(prefs.settingsFlow.value.notificationsEnabled)
        assertFalse(workGateway.isCurrentlyEnqueued)
    }

    @Test
    fun `notification tap target routes NodeSeek activities to TopicDetailRoute and general or summary alerts to ActivityRoute`() {
        val nsEncoded = PulseNotificationDispatcher.encodeCommunityActivityTarget(
            activityId = "nodeseek-618001",
            topicId = 618001L,
            title = "DMIT 抽奖活动",
            sourceUrl = "https://www.nodeseek.com/post-618001-1",
        )
        val nsDest = PulseNotificationDispatcher.decodeCommunityActivityTarget(nsEncoded)
        assertEquals(
            TopicDetailRoute(topicId = 618001L, initialFloor = 0, titleHint = "DMIT 抽奖活动"),
            nsDest,
        )

        val externalEncoded = PulseNotificationDispatcher.encodeCommunityActivityTarget(
            activityId = "discourse-42",
            topicId = 0L,
            title = "外部社区活动",
            sourceUrl = "https://linux.do/t/topic/42",
        )
        assertEquals(ActivityRoute, PulseNotificationDispatcher.decodeCommunityActivityTarget(externalEncoded))

        val summaryEncoded = PulseNotificationDispatcher.encodeCommunityActivityTarget(activityId = null)
        assertEquals(ActivityRoute, PulseNotificationDispatcher.decodeCommunityActivityTarget(summaryEncoded))
    }

    @Test
    fun `permission toggle helper requests POST_NOTIFICATIONS on Android 13+ and guides to settings when blocked`() {
        assertFalse(resolveEffectiveActivityNotificationsEnabled(preferenceEnabled = false, hasPostNotificationsPermission = true))
        assertFalse(resolveEffectiveActivityNotificationsEnabled(preferenceEnabled = true, hasPostNotificationsPermission = false))
        assertTrue(resolveEffectiveActivityNotificationsEnabled(preferenceEnabled = true, hasPostNotificationsPermission = true))

        assertEquals(
            ActivityToggleDecision.RequestRuntimePostNotificationsPermission,
            resolveActivityToggleDecision(
                targetEnabled = true,
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                runtimePermissionGranted = false,
                systemNotificationsEnabled = false,
            ),
        )
        assertEquals(
            ActivityToggleDecision.GuideToSystemNotificationSettings,
            resolveActivityToggleDecision(
                targetEnabled = true,
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                runtimePermissionGranted = true,
                systemNotificationsEnabled = false,
            ),
        )
        assertEquals(
            ActivityToggleDecision.EnableNotificationsImmediately,
            resolveActivityToggleDecision(
                targetEnabled = true,
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                runtimePermissionGranted = true,
                systemNotificationsEnabled = true,
            ),
        )
        assertEquals(
            ActivityToggleDecision.DisableNotifications,
            resolveActivityToggleDecision(
                targetEnabled = false,
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                runtimePermissionGranted = true,
                systemNotificationsEnabled = true,
            ),
        )
    }

    @Test
    fun `only explicit-link LOTTERY items expose time status badges and drive two-tier ViewModel filters`() {
        val mainDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
        try {
            runTest(mainDispatcher) {
                val nowMs = 1_763_820_000_000L
                val lotteryOngoing = ForumWatchActivityItem(
                    id = "lot-ongoing",
                    title = "【抽奖】搬瓦工免单抽奖",
                    status = ActivityTimeStatus.ONGOING,
                    kind = ActivityKind.LOTTERY,
                    startsAtEpochMillis = nowMs - 3_600_000L,
                    endsAtEpochMillis = nowMs + 3_600_000L,
                    luckyUrl = "https://www.nodeseek.com/lucky?post=619001&time=${nowMs + 3_600_000L}&count=1",
                )
                val lotteryUpcoming = ForumWatchActivityItem(
                    id = "lot-upcoming",
                    title = "【预告抽奖】整点抽小鸡",
                    status = ActivityTimeStatus.UPCOMING,
                    kind = ActivityKind.LOTTERY,
                    startsAtEpochMillis = nowMs + 7_200_000L,
                    luckyUrl = "https://lucky.nodeseek.com/?post=619002",
                )
                val lotteryEnded = ForumWatchActivityItem(
                    id = "lot-ended",
                    title = "【已开奖】昨日抽奖",
                    status = ActivityTimeStatus.ONGOING,
                    kind = ActivityKind.LOTTERY,
                    endsAtEpochMillis = nowMs - 60_000L,
                    luckyUrl = "https://www.nodeseek.com/lucky?post=619003&time=${nowMs - 60_000L}&count=3",
                )
                val falsePositiveLotteryNoLink = ForumWatchActivityItem(
                    id = "no-link-discussion",
                    title = "抽奖器好像有bug，奖品数量大于评论数不打乱顺序",
                    status = ActivityTimeStatus.ONGOING,
                    kind = ActivityKind.LOTTERY,
                    luckyUrl = null,
                )
                val giveawayItem = ForumWatchActivityItem(
                    id = "giveaway-1",
                    title = "【福利】免费兑换码派送",
                    status = ActivityTimeStatus.ONGOING,
                    kind = ActivityKind.GIVEAWAY,
                    startsAtEpochMillis = nowMs - 1_800_000L,
                )
                val eventItem = ForumWatchActivityItem(
                    id = "event-1",
                    title = "【社区活动】测速大赛",
                    status = ActivityTimeStatus.ENDED,
                    kind = ActivityKind.EVENT,
                )
                val promoItem = ForumWatchActivityItem(
                    id = "promo-1",
                    title = "【特惠】黑五闪购",
                    status = ActivityTimeStatus.UPCOMING,
                    kind = ActivityKind.PROMO,
                    startsAtEpochMillis = nowMs + 3_600_000L,
                )

                assertEquals(ActivityTimeStatus.ONGOING, lotteryOngoing.resolveLotteryStatusOrNull(nowMs))
                assertEquals(ActivityTimeStatus.UPCOMING, lotteryUpcoming.resolveLotteryStatusOrNull(nowMs))
                assertEquals(ActivityTimeStatus.ENDED, lotteryEnded.resolveLotteryStatusOrNull(nowMs))
                assertNull(falsePositiveLotteryNoLink.resolveLotteryStatusOrNull(nowMs))
                assertNull(giveawayItem.resolveLotteryStatusOrNull(nowMs))
                assertNull(eventItem.resolveLotteryStatusOrNull(nowMs))
                assertNull(promoItem.resolveLotteryStatusOrNull(nowMs))

                val allItems = listOf(
                    lotteryOngoing,
                    lotteryUpcoming,
                    lotteryEnded,
                    falsePositiveLotteryNoLink,
                    giveawayItem,
                    eventItem,
                    promoItem,
                )
                val fakeRepo = FakeActivityRepository()
                fakeRepo.setActivities(allItems, updatedAtMs = nowMs)

                val vm = com.nodeseek.app.ui.activity.ActivityViewModel(
                    repository = fakeRepo,
                    clockMillis = { nowMs },
                    enableClockTicker = false,
                )
                val collector = backgroundScope.launch(mainDispatcher) {
                    vm.uiState.collect {}
                }

                // Counts cleanly partition into 3 explicit-link lotteries + 4 other activities = 7 total
                assertEquals(7, vm.uiState.value.counts.all)
                assertEquals(3, vm.uiState.value.counts.lottery)
                assertEquals(4, vm.uiState.value.counts.other)
                assertEquals(1, vm.uiState.value.counts.ongoing)
                assertEquals(1, vm.uiState.value.counts.upcoming)
                assertEquals(1, vm.uiState.value.counts.ended)
                assertFalse(vm.uiState.value.showLotteryStatusFilters)
                assertEquals(7, vm.uiState.value.filteredActivities.size)

                // Selecting OTHER shows non-lottery items including downgraded no-link discussion (never dumped into ENDED)
                vm.selectCategory(com.nodeseek.app.ui.activity.ActivityCategoryFilter.OTHER)
                assertFalse(vm.uiState.value.showLotteryStatusFilters)
                assertEquals(
                    listOf("no-link-discussion", "giveaway-1", "event-1", "promo-1"),
                    vm.uiState.value.filteredActivities.map { it.id },
                )
                assertEquals(
                    ActivityKind.EVENT,
                    vm.uiState.value.filteredActivities.first { it.id == "no-link-discussion" }.kind,
                )

                // Selecting LOTTERY reveals draw-status sub-filters and lists all 3 explicit lotteries
                vm.selectCategory(com.nodeseek.app.ui.activity.ActivityCategoryFilter.LOTTERY)
                assertTrue(vm.uiState.value.showLotteryStatusFilters)
                assertEquals(
                    com.nodeseek.app.ui.activity.LotteryStatusFilter.ALL,
                    vm.uiState.value.selectedLotteryStatus,
                )
                assertEquals(
                    listOf("lot-ongoing", "lot-upcoming", "lot-ended"),
                    vm.uiState.value.filteredActivities.map { it.id },
                )

                vm.selectLotteryStatus(com.nodeseek.app.ui.activity.LotteryStatusFilter.ONGOING)
                assertEquals(listOf("lot-ongoing"), vm.uiState.value.filteredActivities.map { it.id })

                vm.selectLotteryStatus(com.nodeseek.app.ui.activity.LotteryStatusFilter.UPCOMING)
                assertEquals(listOf("lot-upcoming"), vm.uiState.value.filteredActivities.map { it.id })

                vm.selectLotteryStatus(com.nodeseek.app.ui.activity.LotteryStatusFilter.ENDED)
                assertEquals(listOf("lot-ended"), vm.uiState.value.filteredActivities.map { it.id })

                vm.resetFilters()
                assertFalse(vm.uiState.value.showLotteryStatusFilters)
                assertEquals(7, vm.uiState.value.filteredActivities.size)

                collector.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
