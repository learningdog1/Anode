package com.nodeseek.app.data.radar

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.nodeseek.app.data.local.RadarPreferencesStore
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.data.repository.FakeSettingsRepository
import com.nodeseek.app.data.repository.InboxRepositoryImpl
import com.nodeseek.app.data.repository.RadarRepositoryImpl
import com.nodeseek.app.data.repository.SessionRepositoryImpl
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.backup.BackupManager
import com.nodeseek.app.domain.filter.ContentFilterRepository
import com.nodeseek.app.domain.mail.SeekMailRepository
import com.nodeseek.app.domain.radar.InAppMessagePopup
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.radar.RadarBackgroundPollOutcome
import com.nodeseek.app.domain.radar.RadarMonitorController
import com.nodeseek.app.domain.radar.RadarRepository
import com.nodeseek.app.domain.radar.RadarRuntimeStatus
import com.nodeseek.app.domain.repository.BookmarkAndHistoryRepository
import com.nodeseek.app.domain.repository.CheckInRepository
import com.nodeseek.app.domain.repository.DraftRepository
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.repository.ImageHostRepository
import com.nodeseek.app.domain.repository.InboxRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SettingsRepository
import com.nodeseek.app.domain.repository.UserRepository
import com.nodeseek.app.domain.update.AppUpdateManager
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class PulseRadarWorkerTest {

    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var radarDao: FakeRadarDao
    private lateinit var preferencesStore: RadarPreferencesStore
    private lateinit var radarRepository: RadarRepositoryImpl
    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var sessionRepository: SessionRepositoryImpl
    private lateinit var inboxRepository: InboxRepositoryImpl
    private lateinit var apiClient: NodeSeekApiClient
    private lateinit var dispatcher: RecordingNotificationDispatcher

    private var fakeClockMillis: Long = 1_760_000_000_000L
    private var fakeMinuteOfDay: Int = 12 * 60
    private var fakeUnmeteredNetwork: Boolean = true

    private class TestContext(
        private val controllerProvider: (() -> RadarMonitorController)? = null,
    ) : ContextWrapper(null), AppContainerHost {
        override fun getApplicationContext(): Context = this

        override val container: AppContainer by lazy {
            object : AppContainer {
                override val sessionRepository: SessionRepository get() = error("unused")
                override val forumRepository: ForumRepository get() = error("unused")
                override val checkInRepository: CheckInRepository get() = error("unused")
                override val inboxRepository: InboxRepository get() = error("unused")
                override val userRepository: UserRepository get() = error("unused")
                override val bookmarkAndHistoryRepository: BookmarkAndHistoryRepository get() = error("unused")
                override val settingsRepository: SettingsRepository get() = error("unused")
                override val draftRepository: DraftRepository get() = error("unused")
                override val imageHostRepository: ImageHostRepository get() = error("unused")
                override val seekMailRepository: SeekMailRepository get() = error("unused")
                override val radarRepository: RadarRepository get() = error("unused")
                override val radarMonitorController: RadarMonitorController
                    get() = controllerProvider?.invoke() ?: error("no controller provided")
                override val contentFilterRepository: ContentFilterRepository get() = error("unused")
                override val notificationDispatcher: PulseNotificationDispatcher get() = error("unused")
                override val appUpdateManager: AppUpdateManager get() = error("unused")
                override val backupManager: BackupManager get() = error("unused")
            }
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("pulse_worker_test").toFile()
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val baseUrl = server.url("/")
        val cookieBridge = WebViewCookieBridge(forceInMemory = true)
        val okHttp = NodeSeekApiClient.createDefaultHttpClient(
            cookieBridge = cookieBridge,
            userAgentProvider = UserAgentProvider("NodePulseWorkerTest/1.0"),
            additionalTargetHost = baseUrl.host,
        )

        settingsRepository = FakeSettingsRepository()
        sessionRepository = SessionRepositoryImpl(
            apiClientProvider = { apiClient },
            cookieBridge = cookieBridge,
        )
        radarDao = FakeRadarDao()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(tempDir, "pulse_worker_prefs_test.preferences_pb") },
        )
        preferencesStore = RadarPreferencesStore(dataStore)
        inboxRepository = InboxRepositoryImpl(
            apiClientProvider = { apiClient },
            sessionRepository = sessionRepository,
            watermarkStore = preferencesStore,
        )
        apiClient = NodeSeekApiClient(
            httpClient = okHttp,
            baseOrigin = baseUrl,
            rssUrl = server.url("/rss.xml"),
            nodeImageOrigin = baseUrl,
            onConfigObserved = sessionRepository::onConfigPayloadObserved,
        )
        radarRepository = RadarRepositoryImpl(
            radarDao = radarDao,
            preferencesStore = preferencesStore,
            clockMillis = { fakeClockMillis },
        )
        dispatcher = RecordingNotificationDispatcher()
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        server.shutdown()
        tempDir.deleteRecursively()
    }

    private fun createMonitor(scope: CoroutineScope): PulseRadarMonitor = PulseRadarMonitor(
        appContext = TestContext(),
        scope = scope,
        apiClient = apiClient,
        inboxRepository = inboxRepository,
        sessionRepository = sessionRepository,
        settingsRepository = settingsRepository,
        radarRepository = radarRepository,
        notificationDispatcher = dispatcher,
        watermarkStore = preferencesStore,
        clockMillis = { fakeClockMillis },
        isUnmeteredNetwork = { fakeUnmeteredNetwork },
        currentMinuteOfDay = { fakeMinuteOfDay },
    )

    private fun encodeConfig(json: String): String =
        Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

    private fun buildFeedHtml(
        topics: List<Triple<Long, String, String>>,
        userJson: String? = null,
    ): String {
        val userSection = if (userJson != null) "\"user\": $userJson," else ""
        val configB64 = encodeConfig(
            """
            {
              $userSection
              "allCategory": [
                { "key": "trade", "cn_text": "交易" }
              ]
            }
            """.trimIndent(),
        )
        val itemsHtml = topics.joinToString("\n") { (id, slug, title) ->
            """
            <div class="post-list-item">
              <div class="post-title"><a href="/post-$id-1">$title</a></div>
              <a class="post-category" href="/categories/$slug">$slug</a>
              <span class="info-author"><a href="/space/301">Poster$id</a></span>
              <span class="info-views"><span>88</span></span>
              <span class="info-comments-count"><span>2</span></span>
            </div>
            """.trimIndent()
        }
        return """
            <html><body>
              <div id="nsk-body">$itemsHtml</div>
              <script id="temp-script" type="application/json">$configB64</script>
            </body></html>
        """.trimIndent()
    }

    @Test
    fun `worker executes full background poll pass, emits OS notifications without in-app popups, and returns success`() = runTest {
        radarRepository.addKeywordRule(
            keyword = "DMIT",
            matchMode = KeywordMatchMode.CONTAINS,
        ).getOrThrow()

        var step = 1
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val userJson = """{"member_id":101,"member_name":"Me","coin":100}"""
                return when {
                    path.contains("sortBy=postTime") && step == 1 -> MockResponse()
                        .setResponseCode(200)
                        .setBody(buildFeedHtml(listOf(Triple(100L, "trade", "DMIT 旧帖")), userJson))

                    path.contains("sortBy=postTime") && step == 2 -> MockResponse()
                        .setResponseCode(200)
                        .setBody(buildFeedHtml(listOf(Triple(105L, "trade", "DMIT Pro 补货")), userJson))

                    path == "/api/notification/reply-to-me/list" && step == 1 -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"data":[{"id":1,"viewed":1,"post_id":600,"title":"Old","floor_id":1,"commenter_id":202,"commenter_name":"A","created_at":"2026-11-15T08:00:00Z"}]}""")

                    path == "/api/notification/reply-to-me/list" && step == 2 -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"data":[{"id":2,"viewed":0,"post_id":700,"title":"新回复主题","floor_id":4,"commenter_id":202,"commenter_name":"Alice","created_at":"2026-11-15T09:00:00Z"}]}""")

                    path == "/api/notification/message/list" && step == 1 -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"msgArray":[{"id":10,"sender_id":888,"sender_name":"Bob","receiver_id":101,"receiver_name":"Me","content":"Hi","is_markdown":0,"created_at":"2026-11-15T08:00:00Z","viewed":1}]}""")

                    path == "/api/notification/message/list" && step == 2 -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"success":true,"msgArray":[{"id":11,"sender_id":888,"sender_name":"Bob","receiver_id":101,"receiver_name":"Me","content":"后台私信送达","is_markdown":0,"created_at":"2026-11-15T09:05:00Z","viewed":0}]}""")

                    else -> MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[],"msgArray":[]}""")
                }
            }
        }

        val monitor = createMonitor(this)
        val context = TestContext(controllerProvider = { monitor })

        // Even if an active UI collector exists on inAppPopupEvents, background worker pass must suppress popups.
        val popups = mutableListOf<InAppMessagePopup>()
        val popupJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            inboxRepository.inAppPopupEvents.collect { popups.add(it) }
        }
        try {
            val worker1 = TestListenableWorkerBuilder<PulseRadarWorker>(context).build()
            assertEquals(ListenableWorker.Result.success(), worker1.doWork())
            assertTrue(dispatcher.keywordHits.isEmpty())
            assertTrue(dispatcher.directMessages.isEmpty())

            step = 2
            fakeClockMillis += 60_000L
            val worker2 = TestListenableWorkerBuilder<PulseRadarWorker>(context).build()
            assertEquals(ListenableWorker.Result.success(), worker2.doWork())

            assertEquals(1, dispatcher.keywordHits.size)
            assertEquals(105L, dispatcher.keywordHits.single().topicId)
            assertEquals(1, dispatcher.interactions.size)
            assertEquals(700L, dispatcher.interactions.single().topicId)
            assertEquals(1, dispatcher.directMessages.size)
            assertEquals(11L, dispatcher.directMessages.single().messageId)
            assertTrue(popups.isEmpty())
            assertEquals(1, radarRepository.alertRecordsFlow.first().size)
        } finally {
            popupJob.cancel()
        }
    }

    @Test
    fun `worker returns retry on transient network failure and success on auth-skip or Cloudflare challenge`() = runTest {
        radarRepository.addKeywordRule(keyword = "RN").getOrThrow()

        // 1. Auth-skip when signed out (keywordAlertsEnabled=false, replyTrackingEnabled=false, inboxPushEnabled=true)
        radarRepository.updatePollingSettings {
            it.copy(
                keywordAlertsEnabled = false,
                replyTrackingEnabled = false,
                inboxPushEnabled = true,
            )
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse()
                    .setResponseCode(200)
                    .setBody(buildFeedHtml(emptyList(), userJson = null))
            }
        }
        val monitor = createMonitor(this)
        val context = TestContext(controllerProvider = { monitor })
        val authSkipWorker = TestListenableWorkerBuilder<PulseRadarWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), authSkipWorker.doWork())

        // 2. Transient HTTP 503 server failure -> Result.retry()
        radarRepository.updatePollingSettings { it.copy(keywordAlertsEnabled = true) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(503).setBody("Service Unavailable")
        }
        val http503Worker = TestListenableWorkerBuilder<PulseRadarWorker>(context).build()
        assertEquals(ListenableWorker.Result.retry(), http503Worker.doWork())

        // 3. Transient socket disconnect IOException -> Result.retry()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        val ioFailureWorker = TestListenableWorkerBuilder<PulseRadarWorker>(context).build()
        assertEquals(ListenableWorker.Result.retry(), ioFailureWorker.doWork())

        // 4. Cloudflare challenge (requires interactive WebView) -> Result.success() (no blind retry loop)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setResponseCode(403)
                    .addHeader("cf-mitigated", "challenge")
                    .setBody("<html><title>Just a moment...</title></html>")
        }
        val cfWorker = TestListenableWorkerBuilder<PulseRadarWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), cfWorker.doWork())
    }

    @Test
    fun `worker maps RadarBackgroundPollOutcome variants cleanly via WorkerFactory`() = runTest {
        val context = TestContext()

        suspend fun runWithOutcome(outcome: RadarBackgroundPollOutcome): ListenableWorker.Result {
            val worker = TestListenableWorkerBuilder<PulseRadarWorker>(context)
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker = PulseRadarWorker(
                            appContext = appContext,
                            workerParams = workerParameters,
                            backgroundPollRunner = { outcome },
                        )
                    },
                )
                .build()
            return worker.doWork()
        }

        assertEquals(
            ListenableWorker.Result.success(),
            runWithOutcome(RadarBackgroundPollOutcome.Completed(keywordAlertsTriggered = 2)),
        )
        assertEquals(
            ListenableWorker.Result.success(),
            runWithOutcome(
                RadarBackgroundPollOutcome.Skipped(RadarBackgroundPollOutcome.Skipped.SkipReason.DISABLED),
            ),
        )
        assertEquals(
            ListenableWorker.Result.retry(),
            runWithOutcome(RadarBackgroundPollOutcome.Retry("SocketTimeoutException")),
        )

        // Fake controller path verifying direct RadarMonitorController delegation
        val fakeController = object : RadarMonitorController {
            override val runtimeStatus: StateFlow<RadarRuntimeStatus> =
                MutableStateFlow(RadarRuntimeStatus()).asStateFlow()
            override fun start() = Unit
            override fun stop() = Unit
            override fun onForegroundChanged(inForeground: Boolean) = Unit
            override fun triggerImmediateCheck() = Unit
            override suspend fun runBackgroundPollPass(): RadarBackgroundPollOutcome =
                RadarBackgroundPollOutcome.Retry("Gateway timeout")
        }
        val hostWorker = TestListenableWorkerBuilder<PulseRadarWorker>(
            TestContext(controllerProvider = { fakeController }),
        ).build()
        assertEquals(ListenableWorker.Result.retry(), hostWorker.doWork())
    }
}
