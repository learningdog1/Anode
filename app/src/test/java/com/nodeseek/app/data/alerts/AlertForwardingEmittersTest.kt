package com.nodeseek.app.data.alerts

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.nodeseek.app.data.fleet.FleetAlertSink
import com.nodeseek.app.data.fleet.FleetMonitorRunner
import com.nodeseek.app.data.fleet.FleetMonitorWorker
import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.fleet.ProbePanelClient
import com.nodeseek.app.data.fleet.SoftwareAesGcmTokenCipher
import com.nodeseek.app.data.local.KeywordWatchRuleEntity
import com.nodeseek.app.data.local.RadarPreferencesStore
import com.nodeseek.app.data.local.TrackedTopicEntity
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.data.radar.FakeRadarDao
import com.nodeseek.app.data.radar.PulseRadarMonitor
import com.nodeseek.app.data.repository.FakeSettingsRepository
import com.nodeseek.app.data.repository.InboxRepositoryImpl
import com.nodeseek.app.data.repository.RadarRepositoryImpl
import com.nodeseek.app.data.repository.SessionRepositoryImpl
import com.nodeseek.app.data.restock.RestockMonitorControllerImpl
import com.nodeseek.app.data.restock.RestockNotificationHelper
import com.nodeseek.app.data.restock.RestockRepositoryImpl
import com.nodeseek.app.data.restock.StockPageAnalyzer
import com.nodeseek.app.domain.alerts.AlertForwarder
import com.nodeseek.app.domain.alerts.AlertForwardingConfig
import com.nodeseek.app.domain.alerts.ChannelDeliveryResult
import com.nodeseek.app.domain.alerts.ForwardableAlertEvent
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.radar.LocalAlertRecord
import com.nodeseek.app.domain.restock.RestockEventRecord
import com.nodeseek.app.domain.restock.RestockStockStatus
import com.nodeseek.app.domain.restock.RestockWatchItem
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class AlertForwardingEmittersTest {

    private class RecordingAlertForwarder : AlertForwarder {
        private val _configFlow = MutableStateFlow(AlertForwardingConfig(enabled = true))
        override val configFlow: StateFlow<AlertForwardingConfig> = _configFlow.asStateFlow()
        val forwarded = mutableListOf<ForwardableAlertEvent>()

        override suspend fun updateConfig(transform: (AlertForwardingConfig) -> AlertForwardingConfig) {
            _configFlow.value = transform(_configFlow.value)
        }

        override suspend fun forward(event: ForwardableAlertEvent): List<ChannelDeliveryResult> {
            forwarded += event
            return listOf(ChannelDeliveryResult("test", success = true, httpStatus = 200))
        }

        override suspend fun sendTestAlert(): List<ChannelDeliveryResult> = emptyList()
    }

    private class NoOpFleetAlertSink : FleetAlertSink {
        override fun ensureChannels() = Unit
        override fun notifyServerOffline(panelName: String, server: ProbeServerSnapshot) = Unit
        override fun notifyServerRecovered(panelName: String, server: ProbeServerSnapshot) = Unit
        override fun notifyRenewalReminder(asset: VpsAssetRecord, daysRemaining: Long, renewalCny: BigDecimal) = Unit
    }

    private lateinit var tempDir: File
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("alert_emitters_test").toFile()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        FleetMonitorWorker.externalRepositoryFactory = null
        FleetMonitorWorker.externalAlertForwarderFactory = null
        server.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun `FleetMonitorRunner and FleetMonitorWorker forward offline, recovery, and traffic alerts`() = runTest {
        val nowMillis = 1_763_560_845_000L // 2025-11-19T14:00:45Z
        val isNodeOnline = AtomicBoolean(false)
        val gib = 1024L * 1024L * 1024L
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/v1/server") {
                    val lastActive = if (isNodeOnline.get()) "2025-11-19T14:00:40Z" else "2025-11-19T13:30:00Z"
                    return MockResponse().setResponseCode(200).setBody(
                        """{"success":true,"data":[{"id":5,"name":"SG-Edge","last_active":"$lastActive","host":{"mem_total":1024,"country_code":"SG"},"state":{"cpu":5.0,"mem_used":128,"uptime":100,"net_out_transfer":${90L * gib}}}]}""",
                    )
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val repo = FleetRepositoryImpl(
            storeFile = File(tempDir, "fleet_fwd.json"),
            tokenCipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey()),
            probeClient = ProbePanelClient(nowMillisProvider = { nowMillis }),
        )
        repo.upsertPanel(
            ProbePanelConfig(
                id = "p1",
                name = "MainPanel",
                baseUrl = server.url("/").toString(),
                panelType = ProbePanelType.NEZHA_V1,
                notifyOnOffline = true,
                notifyOnRecovery = true,
            ),
        )
        repo.upsertAsset(
            VpsAssetRecord(
                id = "asset-sg",
                nickname = "SG-Edge",
                providerName = "DMIT",
                specsSummary = "1C/1G",
                region = "SG",
                renewalAmount = "10.00",
                currency = ValuationCurrency.USD,
                cyclePreset = BillingCyclePreset.MONTHLY,
                expiryDateIso = "2026-12-30",
                linkedProbeKey = "p1:5",
                monthlyTrafficQuotaGb = 100.0,
                trafficResetDay = 1,
            ),
        )
        repo.updateMonitorSettings(
            FleetMonitorSettings(
                backgroundMonitorEnabled = true,
                notifyOnRecovery = true,
            ),
        )
        // Seed online baseline via initial refresh
        isNodeOnline.set(true)
        repo.refreshAllPanels()
        // Node goes offline with 90 GiB / 100 GiB (>= 80% traffic)
        isNodeOnline.set(false)

        val forwarder = RecordingAlertForwarder()
        val runner = FleetMonitorRunner(
            fleetRepository = repo,
            alertSink = NoOpFleetAlertSink(),
            todayProvider = { LocalDate.of(2025, 11, 19) },
            nowMillisProvider = { nowMillis },
            alertForwarder = forwarder,
        )
        runner.runOnce()

        assertTrue(forwarder.forwarded.any { it is ForwardableAlertEvent.ProbeOffline && it.serverName == "SG-Edge" })
        assertTrue(forwarder.forwarded.any { it is ForwardableAlertEvent.TrafficThreshold && it.serverName == "SG-Edge" })

        // Now server recovers -> FleetMonitorWorker resolves externalAlertForwarderFactory and forwards ProbeRecovered
        isNodeOnline.set(true)
        forwarder.forwarded.clear()
        FleetMonitorWorker.externalRepositoryFactory = { repo }
        FleetMonitorWorker.externalAlertForwarderFactory = { forwarder }

        val ctx = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = tempDir
            override fun getNoBackupFilesDir(): File = tempDir
            override fun getSystemService(name: String): Any? = null
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                PackageManager.PERMISSION_DENIED
        }
        val worker = TestListenableWorkerBuilder<FleetMonitorWorker>(ctx)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = FleetMonitorWorker(appContext, workerParameters)
            })
            .build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertTrue(forwarder.forwarded.any { it is ForwardableAlertEvent.ProbeRecovered && it.serverName == "SG-Edge" })
    }

    @Test
    fun `RestockMonitorControllerImpl forwards RestockAvailable only on OUT_OF_STOCK to IN_STOCK transition`() = runTest {
        val url = server.url("/cart.php?a=add&pid=1").toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200)
                    .setBody("<html><body><h1>Cloud VPS</h1><button>Add to Cart</button><p>$12.00/yr</p></body></html>")
        }

        val repo = RestockRepositoryImpl(storeFile = File(tempDir, "restock_fwd.json"))
        repo.addOrUpdateWatch(
            RestockWatchItem(
                id = "w1",
                title = "LAX Flash",
                rawUrl = url,
                resolvedOrderUrl = url,
                stockStatus = RestockStockStatus.OUT_OF_STOCK,
                alertWaveArmed = true,
            ),
        )
        val forwarder = RecordingAlertForwarder()
        val ctx = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = tempDir
            override fun getNoBackupFilesDir(): File = tempDir
            override fun getSystemService(name: String): Any? = null
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                PackageManager.PERMISSION_DENIED
        }
        val helper = object : RestockNotificationHelper(ctx) {
            override fun ensureChannel() = Unit
            override fun hasPostNotificationsPermission(): Boolean = false
            override fun postRestockUrgentNotification(event: RestockEventRecord) = Unit
        }
        val controller = RestockMonitorControllerImpl(
            repository = repo,
            analyzer = StockPageAnalyzer(httpClient = OkHttpClient()),
            notificationHelper = helper,
            scope = this,
            alertForwarder = forwarder,
        )

        controller.runBackgroundPollPass()
        assertEquals(1, forwarder.forwarded.size)
        val ev = forwarder.forwarded.single() as ForwardableAlertEvent.RestockAvailable
        assertEquals("LAX Flash", ev.watchTitle)
        assertEquals(url, ev.resolvedOrderUrl)

        // Subsequent IN_STOCK poll is deduplicated and does not forward again
        controller.runBackgroundPollPass()
        assertEquals(1, forwarder.forwarded.size)
    }

    @Test
    fun `PulseRadarMonitor forwards RadarKeywordHit and RadarTrackedReply on newly inserted alerts`() = runTest {
        val baseUrl = server.url("/")
        val configB64 = Base64.getEncoder().encodeToString(
            """{"allCategory":[{"key":"trade","cn_text":"交易"}]}""".toByteArray(Charsets.UTF_8),
        )
        val threadConfigB64 = Base64.getEncoder().encodeToString(
            """
            {
              "allCategory":[{"key":"trade","cn_text":"交易"}],
              "postData":{
                "postId":601,
                "postPage":1,
                "postPageCount":1,
                "title":"Tracked Thread",
                "category":"trade",
                "categoryWord":"交易",
                "op":{"uid":10,"name":"OP"},
                "comments":[{"commentId":22,"floorIndex":2,"poster":{"uid":77,"name":"Buyer"},"markdown":"PM me"}]
              }
            }
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("sortBy=postTime") || path.startsWith("/rss.xml") -> MockResponse().setResponseCode(200).setBody(
                        """
                        <html><body>
                          <div id="nsk-body">
                            <div class="post-list-item">
                              <div class="post-title"><a href="/post-501-1">[出] DMIT LAX Pro</a></div>
                              <a class="post-category" href="/categories/trade">trade</a>
                              <span class="info-author"><a href="/space/10">Seller</a></span>
                              <span class="info-views"><span>88</span></span>
                              <span class="info-comments-count"><span>2</span></span>
                            </div>
                          </div>
                          <script id="temp-script" type="application/json">$configB64</script>
                        </body></html>
                        """.trimIndent(),
                    )
                    path.contains("sortBy=reply") -> MockResponse().setResponseCode(200).setBody(
                        """
                        <html><body>
                          <div id="nsk-body">
                            <div class="post-list-item">
                              <div class="post-title"><a href="/post-601-1">Tracked Thread</a></div>
                              <a class="post-category" href="/categories/trade">trade</a>
                              <span class="info-author"><a href="/space/10">OP</a></span>
                              <span class="info-views"><span>88</span></span>
                              <span class="info-comments-count"><span>2</span></span>
                            </div>
                          </div>
                          <script id="temp-script" type="application/json">$configB64</script>
                        </body></html>
                        """.trimIndent(),
                    )
                    path.startsWith("/post-601-") -> MockResponse().setResponseCode(200).setBody(
                        """
                        <html><body>
                          <div id="nsk-body"><div class="nsk-post"></div></div>
                          <script id="temp-script" type="application/json">$threadConfigB64</script>
                        </body></html>
                        """.trimIndent(),
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val cookieBridge = WebViewCookieBridge(forceInMemory = true)
            lateinit var apiClient: NodeSeekApiClient
            val settingsRepo = FakeSettingsRepository()
            val sessionRepo = SessionRepositoryImpl(apiClientProvider = { apiClient }, cookieBridge = cookieBridge)
            val inboxRepo = InboxRepositoryImpl(apiClientProvider = { apiClient }, sessionRepository = sessionRepo)
            apiClient = NodeSeekApiClient(
                httpClient = NodeSeekApiClient.createDefaultHttpClient(
                    cookieBridge = cookieBridge,
                    userAgentProvider = UserAgentProvider("Test/1.0"),
                    additionalTargetHost = baseUrl.host,
                ),
                baseOrigin = baseUrl,
                rssUrl = server.url("/rss.xml"),
                nodeImageOrigin = baseUrl,
                onConfigObserved = sessionRepo::onConfigPayloadObserved,
            )

            val fakeDao = FakeRadarDao()
            fakeDao.insertKeywordRule(
                KeywordWatchRuleEntity(
                    keyword = "DMIT",
                    matchMode = KeywordMatchMode.CONTAINS.name,
                    categorySlug = "",
                    excludeWords = "",
                    isEnabled = true,
                    createdAtEpochMillis = 1L,
                ),
            )
            fakeDao.upsertTrackedTopic(
                TrackedTopicEntity(
                    topicId = 601L,
                    title = "Tracked Thread",
                    categorySlug = "trade",
                    categoryName = "交易",
                    authorName = "OP",
                    lastKnownFloor = 1,
                    lastKnownTotalPages = 1,
                    unreadNewReplies = 0,
                    isActive = true,
                    createdAtEpochMillis = 1L,
                    lastCheckedEpochMillis = 0L,
                    lastUpdatedEpochMillis = 1L,
                ),
            )

            val prefsStore = RadarPreferencesStore(
                PreferenceDataStoreFactory.create(
                    scope = dataStoreScope,
                    produceFile = { File(tempDir, "radar_fwd.preferences_pb") },
                ),
            )
            prefsStore.updateWatermarks { it.copy(lastSeenMaxTopicId = 400L) }
            val radarRepo = RadarRepositoryImpl(fakeDao, prefsStore)
            radarRepo.updatePollingSettings {
                it.copy(
                    isEnabled = true,
                    keywordAlertsEnabled = true,
                    replyTrackingEnabled = true,
                    inboxPushEnabled = false,
                )
            }
            val ctx = object : ContextWrapper(null) {
                override fun getApplicationContext(): Context = this
            }
            val dispatcher = object : PulseNotificationDispatcher(ctx) {
                override fun ensureChannels() = Unit
                override fun postKeywordHitNotification(record: LocalAlertRecord) = Unit
                override fun postTrackedReplyNotification(record: LocalAlertRecord, newReplyCount: Int) = Unit
            }
            val forwarder = RecordingAlertForwarder()
            val monitor = PulseRadarMonitor(
                appContext = ctx,
                scope = this,
                apiClient = apiClient,
                inboxRepository = inboxRepo,
                sessionRepository = sessionRepo,
                settingsRepository = settingsRepo,
                radarRepository = radarRepo,
                notificationDispatcher = dispatcher,
                watermarkStore = prefsStore,
                alertForwarder = forwarder,
            )

            monitor.runBackgroundPollPass()
            assertTrue(forwarder.forwarded.any { it is ForwardableAlertEvent.RadarKeywordHit && it.topicId == 501L })
            assertTrue(forwarder.forwarded.any { it is ForwardableAlertEvent.RadarTrackedReply && it.topicId == 601L && it.targetFloor == 2 })
        } finally {
            dataStoreScope.cancel()
        }
    }
}
