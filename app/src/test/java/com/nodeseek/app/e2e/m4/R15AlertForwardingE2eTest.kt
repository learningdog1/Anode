package com.nodeseek.app.e2e.m4

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.nodeseek.app.data.alerts.AlertForwarderImpl
import com.nodeseek.app.data.alerts.AlertForwardingStore
import com.nodeseek.app.data.fleet.FleetMonitorWorker
import com.nodeseek.app.data.local.RadarPreferencesStore
import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.data.radar.PulseRadarMonitor
import com.nodeseek.app.data.repository.InboxRepositoryImpl
import com.nodeseek.app.data.restock.RestockMonitorControllerImpl
import com.nodeseek.app.data.restock.RestockNotificationHelper
import com.nodeseek.app.data.restock.RestockRepositoryImpl
import com.nodeseek.app.data.restock.StockPageAnalyzer
import com.nodeseek.app.domain.alerts.AlertEventCategory
import com.nodeseek.app.domain.alerts.AlertForwardingConfig
import com.nodeseek.app.domain.alerts.AlertUrlValidator
import com.nodeseek.app.domain.alerts.BarkForwardConfig
import com.nodeseek.app.domain.alerts.ForwardableAlertEvent
import com.nodeseek.app.domain.alerts.TelegramForwardConfig
import com.nodeseek.app.domain.alerts.WebhookFormatter
import com.nodeseek.app.domain.alerts.WebhookForwardConfig
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.radar.LocalAlertRecord
import com.nodeseek.app.domain.restock.RestockEventRecord
import com.nodeseek.app.domain.restock.RestockStockStatus
import com.nodeseek.app.domain.restock.RestockWatchItem
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.e2e.E2eFixtures
import com.nodeseek.app.e2e.E2eFleetAlertSink
import com.nodeseek.app.e2e.E2eMainDispatcherRule
import com.nodeseek.app.e2e.E2eTestHarness
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class R15AlertForwardingE2eTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    private fun computeHmacSha256Hex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b) }
    }

    private fun testAppContext(filesDir: File): Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = filesDir
        override fun getNoBackupFilesDir(): File = filesDir
        override fun getSystemService(name: String): Any? = null
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            PackageManager.PERMISSION_DENIED
    }

    @Test
    fun `E2E-T1-R15-1 AlertForwardingStore encrypts credentials on disk round-trips on reload and stays excluded from v1 backup`() = runBlocking {
        E2eTestHarness().use { harness ->
            val botToken = "8123456:SuperSecretTelegramToken_R15"
            val barkKey = "SuperSecretBarkDeviceKey_R15"
            val hookUrl = "https://hooks.example.test/secret-webhook-endpoint"
            val hookSecret = "SuperSecretHmacKey_R15"

            harness.alertForwarder.updateConfig {
                AlertForwardingConfig(
                    enabled = true,
                    enabledEvents = setOf(
                        AlertEventCategory.PROBE_OFFLINE,
                        AlertEventCategory.RESTOCK_AVAILABLE,
                    ),
                    telegram = TelegramForwardConfig(
                        enabled = true,
                        botToken = botToken,
                        chatId = "-10099887766",
                        disableNotification = true,
                    ),
                    bark = BarkForwardConfig(
                        enabled = true,
                        serverUrl = "https://bark.example.test",
                        deviceKey = barkKey,
                        sound = "alarm",
                        group = "AnodeProd",
                        isArchive = false,
                    ),
                    webhook = WebhookForwardConfig(
                        enabled = true,
                        url = hookUrl,
                        secretHeaderName = "X-Custom-Token",
                        secretValue = hookSecret,
                        customHeaders = mapOf("X-Env" to "prod"),
                    ),
                )
            }

            val rawDisk = harness.alertForwardingStoreFile.readText(Charsets.UTF_8)
            assertFalse(rawDisk.contains(botToken))
            assertFalse(rawDisk.contains(barkKey))
            assertFalse(rawDisk.contains(hookUrl))
            assertFalse(rawDisk.contains(hookSecret))
            assertTrue(rawDisk.contains("v1:sw:"))

            // Reload into a fresh AlertForwardingStore using the same cipher
            val reloadedStore = AlertForwardingStore(
                storeFile = harness.alertForwardingStoreFile,
                secretCipher = harness.alertForwardingCipher,
            )
            val reloaded = reloadedStore.ensureLoaded()
            assertTrue(reloaded.enabled)
            assertEquals(
                setOf(AlertEventCategory.PROBE_OFFLINE, AlertEventCategory.RESTOCK_AVAILABLE),
                reloaded.enabledEvents,
            )
            assertEquals(botToken, reloaded.telegram.botToken)
            assertEquals("-10099887766", reloaded.telegram.chatId)
            assertTrue(reloaded.telegram.disableNotification)
            assertEquals(barkKey, reloaded.bark.deviceKey)
            assertEquals("alarm", reloaded.bark.sound)
            assertEquals("AnodeProd", reloaded.bark.group)
            assertFalse(reloaded.bark.isArchive)
            assertEquals(hookUrl, reloaded.webhook.url)
            assertEquals("X-Custom-Token", reloaded.webhook.secretHeaderName)
            assertEquals(hookSecret, reloaded.webhook.secretValue)
            assertEquals(mapOf("X-Env" to "prod"), reloaded.webhook.customHeaders)

            // Plaintext v1 backup MUST exclude all forwarding secrets & URLs
            val backupJson = harness.createBackupManager().exportBackupJson(includeApiKeys = true).getOrThrow()
            assertFalse(backupJson.contains(botToken))
            assertFalse(backupJson.contains(barkKey))
            assertFalse(backupJson.contains(hookUrl))
            assertFalse(backupJson.contains(hookSecret))
            assertFalse(backupJson.contains("-10099887766"))
        }
    }

    @Test
    fun `E2E-T1-R15-2 end-to-end Telegram Bot Bark and Webhook deliver all event categories with valid HMAC and zero cookies`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(
                uid = 101L,
                sessionCookie = "r15_forum_sess",
                cfClearance = "r15_forum_cf",
            )

            val responses = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("forwarding/telegram_bark_webhook.json"),
            ).jsonObject
            val tgOk = responses.getValue("telegramSuccess").toString()
            val barkOk = responses.getValue("barkSuccess").toString()
            val hookOk = responses.getValue("webhookSuccess").toString()

            val botToken = "778899:Telegram_Token_R15"
            val hmacSecret = "my-hmac-key"

            harness.externalDispatcher.register("POST", "/bot$botToken/sendMessage") {
                MockResponse().setResponseCode(200).setBody(tgOk)
            }
            harness.externalDispatcher.register("POST", "/push") {
                MockResponse().setResponseCode(200).setBody(barkOk)
            }
            harness.externalDispatcher.register("POST", "/hooks/anode") {
                MockResponse().setResponseCode(200).setBody(hookOk)
            }

            val (_, forwarder) = harness.createTelegramRoutingAlertForwarder()
            forwarder.updateConfig {
                AlertForwardingConfig(
                    enabled = true,
                    enabledEvents = AlertEventCategory.entries.toSet(),
                    telegram = TelegramForwardConfig(
                        enabled = true,
                        botToken = botToken,
                        chatId = "-100555666",
                        disableNotification = true,
                    ),
                    bark = BarkForwardConfig(
                        enabled = true,
                        serverUrl = harness.externalServer.url("/").toString().trimEnd('/'),
                        deviceKey = "bark_dev_key",
                        sound = "minuet",
                        group = "AnodeE2E",
                        isArchive = true,
                    ),
                    webhook = WebhookForwardConfig(
                        enabled = true,
                        url = harness.externalServer.url("/hooks/anode").toString(),
                        secretHeaderName = "X-Custom-Secret",
                        secretValue = hmacSecret,
                        customHeaders = mapOf("X-Env" to "e2e"),
                    ),
                )
            }

            val events = listOf(
                ForwardableAlertEvent.ProbeOffline("Panel1", "SG-Edge", "p1:5", "🇸🇬", 1_764_000_001_000L),
                ForwardableAlertEvent.TrafficThreshold("Panel1", "SG-Edge", "p1:5", "🇸🇬", 90_000L, 100_000L, 0.90, "2026-12-01", "2 days", 1_764_000_002_000L),
                ForwardableAlertEvent.RestockAvailable("w1", "DMIT LAX.Pro", "https://merchant.test/cart?pid=1", "$36.9/yr", "WHMCS", "Add to Cart", 1_764_000_003_000L),
                ForwardableAlertEvent.RadarKeywordHit(501L, "[出] DMIT LAX", "DMIT", "Seller", "交易", "CN2 GIA", "https://www.nodeseek.com/post-501-1", 1_764_000_004_000L),
                ForwardableAlertEvent.RadarTrackedReply(601L, "Tracked Thread", 3, 2, "Buyer", "交易", "PM sent", "https://www.nodeseek.com/post-601-3", 1_764_000_005_000L),
            )
            events.forEach { ev ->
                val results = forwarder.forward(ev)
                assertEquals(3, results.size)
                assertTrue("Expected all 3 channels to succeed for $ev", results.all { it.success && it.httpStatus == 200 })
            }
            val testResults = forwarder.sendTestAlert()
            assertEquals(3, testResults.size)
            assertTrue(testResults.all { it.success && it.httpStatus == 200 })

            val recorded = harness.externalDispatcher.recordedRequests()
            val tgReqs = recorded.filter { it.path == "/bot$botToken/sendMessage" }
            val barkReqs = recorded.filter { it.path == "/push" }
            val hookReqs = recorded.filter { it.path == "/hooks/anode" }
            assertEquals(6, tgReqs.size)
            assertEquals(6, barkReqs.size)
            assertEquals(6, hookReqs.size)

            // Verify Telegram payload structure
            val tgObj = TempScriptParser.json.parseToJsonElement(tgReqs.first().body.readUtf8()).jsonObject
            assertEquals("-100555666", tgObj.getValue("chat_id").jsonPrimitive.content)
            assertTrue(tgObj.getValue("disable_web_page_preview").jsonPrimitive.boolean)
            assertTrue(tgObj.getValue("disable_notification").jsonPrimitive.boolean)
            assertTrue(tgObj.getValue("text").jsonPrimitive.content.contains("探针离线"))

            // Verify Bark payload with targetUrl on RestockAvailable
            val barkRestock = barkReqs.map { TempScriptParser.json.parseToJsonElement(it.body.readUtf8()).jsonObject }
                .first { it["title"]?.jsonPrimitive?.content?.contains("补货有货") == true }
            assertEquals("bark_dev_key", barkRestock.getValue("device_key").jsonPrimitive.content)
            assertEquals("AnodeE2E", barkRestock.getValue("group").jsonPrimitive.content)
            assertEquals(1, barkRestock.getValue("isArchive").jsonPrimitive.int)
            assertEquals("minuet", barkRestock.getValue("sound").jsonPrimitive.content)
            assertEquals("https://merchant.test/cart?pid=1", barkRestock.getValue("url").jsonPrimitive.content)

            // Verify Webhook headers, JSON fields, and independent HMAC-SHA256 signature
            hookReqs.forEach { req ->
                assertEquals("e2e", req.getHeader("X-Env"))
                assertEquals(hmacSecret, req.getHeader("X-Custom-Secret"))
                val rawBody = req.body.readUtf8()
                val expectedSig = "sha256=${computeHmacSha256Hex(hmacSecret, rawBody)}"
                assertEquals(expectedSig, req.getHeader(WebhookFormatter.HEADER_SIGNATURE))
                val hookObj = TempScriptParser.json.parseToJsonElement(rawBody).jsonObject
                assertTrue(hookObj.containsKey("event"))
                assertTrue(hookObj.containsKey("category"))
                assertTrue(hookObj.containsKey("details"))
                assertTrue(hookObj.containsKey("timestamp"))
            }

            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T1-R15-3 FleetMonitorRunner and FleetMonitorWorker forward ProbeOffline TrafficThreshold and ProbeRecovered`() = runBlocking {
        E2eTestHarness().use { harness ->
            val fixture = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("forwarding/probe_and_restock_alerts.json"),
            ).jsonObject
            val onlineTemplate = fixture.getValue("nezhaOnlineServerPayload").toString()
            val offlineHighTrafficTemplate = fixture.getValue("nezhaOfflineHighTrafficServerPayload").toString()
            val responses = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("forwarding/telegram_bark_webhook.json"),
            ).jsonObject
            val barkOk = responses.getValue("barkSuccess").toString()
            val hookOk = responses.getValue("webhookSuccess").toString()

            val isOnline = AtomicBoolean(true)
            val receivedWebhookEvents = CopyOnWriteArrayList<String>()

            harness.externalDispatcher.register("GET", "/api/v1/server") {
                val payload = if (isOnline.get()) {
                    onlineTemplate.replace("__ONLINE_ISO__", Instant.now().toString())
                } else {
                    offlineHighTrafficTemplate.replace("__OFFLINE_ISO__", Instant.now().minusSeconds(1_800L).toString())
                }
                MockResponse().setResponseCode(200).setBody(payload)
            }
            harness.externalDispatcher.register("POST", "/push") {
                MockResponse().setResponseCode(200).setBody(barkOk)
            }
            harness.externalDispatcher.register("POST", "/hooks/anode") { req ->
                val body = req.body.clone().readUtf8()
                val evName = TempScriptParser.json.parseToJsonElement(body).jsonObject
                    .getValue("event").jsonPrimitive.content
                receivedWebhookEvents.add(evName)
                MockResponse().setResponseCode(200).setBody(hookOk)
            }

            harness.alertForwarder.updateConfig {
                AlertForwardingConfig(
                    enabled = true,
                    enabledEvents = AlertEventCategory.entries.toSet(),
                    bark = BarkForwardConfig(
                        enabled = true,
                        serverUrl = harness.externalServer.url("/").toString().trimEnd('/'),
                        deviceKey = "fleet_bark",
                    ),
                    webhook = WebhookForwardConfig(
                        enabled = true,
                        url = harness.externalServer.url("/hooks/anode").toString(),
                    ),
                )
            }

            harness.fleetRepository.upsertPanel(
                ProbePanelConfig(
                    id = "p1",
                    name = "MainPanel",
                    baseUrl = harness.externalServer.url("/").toString(),
                    panelType = ProbePanelType.NEZHA_V1,
                    notifyOnOffline = true,
                    notifyOnRecovery = true,
                ),
            )
            harness.fleetRepository.upsertAsset(
                VpsAssetRecord(
                    id = "asset-sg",
                    nickname = "SG-Edge",
                    providerName = "DMIT",
                    specsSummary = "1C/1G",
                    region = "SG",
                    renewalAmount = "10.00",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.MONTHLY,
                    expiryDateIso = harness.clock.todayShanghai().plusDays(30).toString(),
                    linkedProbeKey = "p1:5",
                    monthlyTrafficQuotaGb = 100.0,
                    trafficResetDay = 1,
                ),
            )
            harness.fleetRepository.updateMonitorSettings(
                FleetMonitorSettings(
                    backgroundMonitorEnabled = true,
                    notifyOnRecovery = true,
                ),
            )

            // Seed online baseline
            harness.fleetRepository.refreshAllPanels()

            // Transition to offline with 90 GiB / 100 GiB traffic
            isOnline.set(false)
            val fleetSink = E2eFleetAlertSink()
            val runner = harness.createFleetMonitorRunner(alertSink = fleetSink)
            runner.runOnce()

            assertEquals(1, fleetSink.offlineAlerts.size)
            assertEquals(1, fleetSink.trafficAlerts.size)
            assertTrue("probe_offline" in receivedWebhookEvents)
            assertTrue("traffic_threshold" in receivedWebhookEvents)

            // Recover online -> run FleetMonitorWorker via public 2-arg constructor + static factories
            isOnline.set(true)
            receivedWebhookEvents.clear()
            FleetMonitorWorker.externalRepositoryFactory = { harness.fleetRepository }
            FleetMonitorWorker.externalAlertForwarderFactory = { harness.alertForwarder }
            try {
                val worker = TestListenableWorkerBuilder<FleetMonitorWorker>(testAppContext(harness.tempDir))
                    .setWorkerFactory(object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker = FleetMonitorWorker(appContext, workerParameters)
                    })
                    .build()
                assertEquals(ListenableWorker.Result.success(), worker.doWork())
            } finally {
                FleetMonitorWorker.externalRepositoryFactory = null
                FleetMonitorWorker.externalAlertForwarderFactory = null
            }

            assertTrue("probe_recovered" in receivedWebhookEvents)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T1-R15-4 RestockMonitorControllerImpl forwards RestockAvailable once on OUT_OF_STOCK to IN_STOCK transition`() = runBlocking {
        E2eTestHarness().use { harness ->
            val probeRestockFixture = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("forwarding/probe_and_restock_alerts.json"),
            ).jsonObject
            val restockHtml = probeRestockFixture.getValue("restockInStockHtml").jsonPrimitive.content
            val responses = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("forwarding/telegram_bark_webhook.json"),
            ).jsonObject

            val orderUrl = harness.externalServer.url("/cart.php?a=add&pid=1").toString()
            harness.externalDispatcher.register("GET", "/cart.php") {
                MockResponse().setResponseCode(200).setBody(restockHtml)
            }
            harness.externalDispatcher.register("POST", "/push") {
                MockResponse().setResponseCode(200).setBody(responses.getValue("barkSuccess").toString())
            }
            harness.externalDispatcher.register("POST", "/hooks/anode") {
                MockResponse().setResponseCode(200).setBody(responses.getValue("webhookSuccess").toString())
            }

            harness.alertForwarder.updateConfig {
                AlertForwardingConfig(
                    enabled = true,
                    enabledEvents = setOf(AlertEventCategory.RESTOCK_AVAILABLE),
                    bark = BarkForwardConfig(
                        enabled = true,
                        serverUrl = harness.externalServer.url("/").toString().trimEnd('/'),
                        deviceKey = "restock_bark",
                    ),
                    webhook = WebhookForwardConfig(
                        enabled = true,
                        url = harness.externalServer.url("/hooks/anode").toString(),
                    ),
                )
            }

            val restockRepo = RestockRepositoryImpl(
                storeFile = File(harness.tempDir, "restock_r15.json"),
                clockMillis = harness.clock::nowMillis,
            )
            restockRepo.addOrUpdateWatch(
                RestockWatchItem(
                    id = "w-lax",
                    title = "LAX Flash",
                    rawUrl = orderUrl,
                    resolvedOrderUrl = orderUrl,
                    stockStatus = RestockStockStatus.OUT_OF_STOCK,
                    alertWaveArmed = true,
                ),
            )
            val ctx = testAppContext(harness.tempDir)
            val notifHelper = object : RestockNotificationHelper(ctx) {
                override fun ensureChannel() = Unit
                override fun hasPostNotificationsPermission(): Boolean = false
                override fun postRestockUrgentNotification(event: RestockEventRecord) = Unit
            }
            val controller = RestockMonitorControllerImpl(
                repository = restockRepo,
                analyzer = StockPageAnalyzer(httpClient = harness.externalCookieFreeHttpClient),
                notificationHelper = notifHelper,
                scope = harness.backgroundScope,
                clockMillis = harness.clock::nowMillis,
                alertForwarder = harness.alertForwarder,
            )

            controller.runBackgroundPollPass()
            val firstHookReqs = harness.externalDispatcher.recordedRequests().filter { it.path == "/hooks/anode" }
            val firstBarkReqs = harness.externalDispatcher.recordedRequests().filter { it.path == "/push" }
            assertEquals(1, firstHookReqs.size)
            assertEquals(1, firstBarkReqs.size)

            val hookJson = TempScriptParser.json.parseToJsonElement(firstHookReqs.single().body.readUtf8()).jsonObject
            assertEquals("restock_available", hookJson.getValue("event").jsonPrimitive.content)
            val details = hookJson.getValue("details").jsonObject
            assertEquals(orderUrl, details.getValue("orderUrl").jsonPrimitive.content)
            assertEquals("$12.00/年", details.getValue("priceHint").jsonPrimitive.content)

            val barkJson = TempScriptParser.json.parseToJsonElement(firstBarkReqs.single().body.readUtf8()).jsonObject
            assertEquals(orderUrl, barkJson.getValue("url").jsonPrimitive.content)

            // Second poll while still IN_STOCK is deduplicated -> zero additional forwarding requests
            controller.runBackgroundPollPass()
            assertEquals(1, harness.externalDispatcher.recordedRequests().count { it.path == "/hooks/anode" })
            assertEquals(1, harness.externalDispatcher.recordedRequests().count { it.path == "/push" })
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T1-R15-5 PulseRadarMonitor emits RadarKeywordHit and RadarTrackedReply via public runBackgroundPollPass with zero cookies leaked`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(
                uid = 101L,
                sessionCookie = "r15_radar_cookie",
                cfClearance = "r15_radar_cf",
            )
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

            harness.forumDispatcher.register("GET", "/") { req ->
                val sort = req.requestUrl?.queryParameter("sortBy").orEmpty()
                if (sort == "reply") {
                    MockResponse().setResponseCode(200).setBody(
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
                } else {
                    MockResponse().setResponseCode(200).setBody(
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
                }
            }
            harness.forumDispatcher.register("GET", "/post-601-1") {
                MockResponse().setResponseCode(200).setBody(
                    """
                    <html><body>
                      <div id="nsk-body"><div class="nsk-post"></div></div>
                      <script id="temp-script" type="application/json">$threadConfigB64</script>
                    </body></html>
                    """.trimIndent(),
                )
            }

            val hookOk = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("forwarding/telegram_bark_webhook.json"),
            ).jsonObject.getValue("webhookSuccess").toString()
            harness.externalDispatcher.register("POST", "/hooks/anode") {
                MockResponse().setResponseCode(200).setBody(hookOk)
            }

            harness.alertForwarder.updateConfig {
                AlertForwardingConfig(
                    enabled = true,
                    enabledEvents = setOf(AlertEventCategory.RADAR_HIT),
                    webhook = WebhookForwardConfig(
                        enabled = true,
                        url = harness.externalServer.url("/hooks/anode").toString(),
                    ),
                )
            }

            harness.radarRepository.addKeywordRule(
                keyword = "DMIT",
                categorySlug = "",
                matchMode = KeywordMatchMode.CONTAINS,
            ).getOrThrow()
            harness.radarRepository.trackTopic(
                topicId = 601L,
                title = "Tracked Thread",
                categorySlug = "trade",
                categoryName = "交易",
                authorName = "OP",
                currentMaxFloor = 1,
                totalPages = 1,
            )
            harness.radarRepository.updatePollingSettings {
                it.copy(
                    isEnabled = true,
                    keywordAlertsEnabled = true,
                    replyTrackingEnabled = true,
                    inboxPushEnabled = false,
                )
            }

            val prefsStore = RadarPreferencesStore(
                PreferenceDataStoreFactory.create(
                    scope = harness.dataStoreScope,
                    produceFile = { File(harness.tempDir, "radar_r15.preferences_pb") },
                ),
            )
            prefsStore.updateWatermarks { it.copy(lastSeenMaxTopicId = 400L) }

            val ctx = testAppContext(harness.tempDir)
            val dispatcher = object : PulseNotificationDispatcher(ctx) {
                override fun ensureChannels() = Unit
                override fun postKeywordHitNotification(record: LocalAlertRecord) = Unit
                override fun postTrackedReplyNotification(record: LocalAlertRecord, newReplyCount: Int) = Unit
            }
            val inboxRepo = InboxRepositoryImpl(
                apiClientProvider = { harness.apiClient },
                sessionRepository = harness.sessionRepository,
                clockMillis = harness.clock::nowMillis,
                watermarkStore = prefsStore,
            )

            val monitor = PulseRadarMonitor(
                appContext = ctx,
                scope = harness.backgroundScope,
                apiClient = harness.apiClient,
                inboxRepository = inboxRepo,
                sessionRepository = harness.sessionRepository,
                settingsRepository = harness.settingsRepository,
                radarRepository = harness.radarRepository,
                notificationDispatcher = dispatcher,
                watermarkStore = prefsStore,
                clockMillis = harness.clock::nowMillis,
                isUnmeteredNetwork = { true },
                currentMinuteOfDay = { 12 * 60 },
                alertForwarder = harness.alertForwarder,
            )

            monitor.runBackgroundPollPass()

            val hookPayloads = harness.externalDispatcher.recordedRequests()
                .filter { it.path == "/hooks/anode" }
                .map { TempScriptParser.json.parseToJsonElement(it.body.readUtf8()).jsonObject }
            val eventKeys = hookPayloads.map { it.getValue("event").jsonPrimitive.content }.toSet()
            assertEquals(setOf("radar_keyword_hit", "radar_tracked_reply"), eventKeys)

            val kwPayload = hookPayloads.first { it.getValue("event").jsonPrimitive.content == "radar_keyword_hit" }
            assertEquals("https://www.nodeseek.com/post-501-1", kwPayload.getValue("url").jsonPrimitive.content)

            val replyPayload = hookPayloads.first { it.getValue("event").jsonPrimitive.content == "radar_tracked_reply" }
            assertEquals("https://www.nodeseek.com/post-601-2", replyPayload.getValue("url").jsonPrimitive.content)

            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T2-R15-1 master switch category filtering and multi-channel partial failure isolation work accurately`() = runBlocking {
        E2eTestHarness().use { harness ->
            val responses = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("forwarding/telegram_bark_webhook.json"),
            ).jsonObject
            val tg401 = responses.getValue("telegramUnauthorized").toString()
            val barkOk = responses.getValue("barkSuccess").toString()

            val botToken = "112233:FailToken"
            harness.externalDispatcher.register("POST", "/bot$botToken/sendMessage") {
                MockResponse().setResponseCode(401).setBody(tg401)
            }
            harness.externalDispatcher.register("POST", "/push") {
                MockResponse().setResponseCode(200).setBody(barkOk)
            }
            harness.externalDispatcher.register("POST", "/hooks/anode") {
                MockResponse().setResponseCode(500).setBody("""{"error":"down"}""")
            }

            val (_, forwarder) = harness.createTelegramRoutingAlertForwarder()
            val probeOffline = ForwardableAlertEvent.ProbeOffline("Panel", "SG-1", "p1:1")

            // 1. Master switch disabled -> 0 HTTP requests
            assertTrue(forwarder.forward(probeOffline).isEmpty())
            assertTrue(harness.externalDispatcher.recordedRequests().isEmpty())

            // 2. Category filter excludes TRAFFIC_THRESHOLD -> 0 HTTP requests
            forwarder.updateConfig {
                AlertForwardingConfig(
                    enabled = true,
                    enabledEvents = setOf(AlertEventCategory.PROBE_OFFLINE),
                    telegram = TelegramForwardConfig(enabled = true, botToken = botToken, chatId = "99"),
                    bark = BarkForwardConfig(
                        enabled = true,
                        serverUrl = harness.externalServer.url("/").toString().trimEnd('/'),
                        deviceKey = "dk",
                    ),
                    webhook = WebhookForwardConfig(
                        enabled = true,
                        url = harness.externalServer.url("/hooks/anode").toString(),
                    ),
                )
            }
            val skipped = forwarder.forward(
                ForwardableAlertEvent.TrafficThreshold("Panel", "SG-1", "p1:1", usedBytes = 85L, limitBytes = 100L, usageRatio = 0.85),
            )
            assertTrue(skipped.isEmpty())
            assertTrue(harness.externalDispatcher.recordedRequests().isEmpty())

            // 3. Enabled category isolates 401 Telegram + 200 Bark + 500 Webhook concurrently
            val results = forwarder.forward(probeOffline)
            assertEquals(3, results.size)
            val tgRes = results.first { it.channelName == AlertForwarderImpl.CHANNEL_TELEGRAM }
            val barkRes = results.first { it.channelName == AlertForwarderImpl.CHANNEL_BARK }
            val hookRes = results.first { it.channelName == AlertForwarderImpl.CHANNEL_WEBHOOK }

            assertFalse(tgRes.success)
            assertEquals(401, tgRes.httpStatus)
            assertEquals("Unauthorized", tgRes.errorMessage)

            assertTrue(barkRes.success)
            assertEquals(200, barkRes.httpStatus)

            assertFalse(hookRes.success)
            assertEquals(500, hookRes.httpStatus)
            assertEquals("down", hookRes.errorMessage)
        }
    }

    @Test
    fun `E2E-T2-R15-2 Telegram HTTP 200 ok=false preserves description and long payloads are clamped safely`() = runBlocking {
        E2eTestHarness().use { harness ->
            val responses = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("forwarding/telegram_bark_webhook.json"),
            ).jsonObject
            val tg200Error = responses.getValue("telegramRateLimited").toString()
            val barkOk = responses.getValue("barkSuccess").toString()

            val botToken = "445566:BusinessErrToken"
            harness.externalDispatcher.register("POST", "/bot$botToken/sendMessage") {
                MockResponse().setResponseCode(200).setBody(tg200Error)
            }
            harness.externalDispatcher.register("POST", "/push") {
                MockResponse().setResponseCode(200).setBody(barkOk)
            }

            val (_, forwarder) = harness.createTelegramRoutingAlertForwarder()
            forwarder.updateConfig {
                AlertForwardingConfig(
                    enabled = true,
                    enabledEvents = AlertEventCategory.entries.toSet(),
                    telegram = TelegramForwardConfig(enabled = true, botToken = botToken, chatId = "42"),
                    bark = BarkForwardConfig(
                        enabled = true,
                        serverUrl = harness.externalServer.url("/").toString().trimEnd('/'),
                        deviceKey = "dk_clamp",
                    ),
                )
            }

            val longHit = ForwardableAlertEvent.RadarKeywordHit(
                topicId = 808L,
                topicTitle = "Long Payload Test",
                matchedKeyword = "DMIT",
                authorName = "tester",
                summarySnippet = "A".repeat(6_000),
            )
            val results = forwarder.forward(longHit)
            val tgRes = results.first { it.channelName == AlertForwarderImpl.CHANNEL_TELEGRAM }
            val barkRes = results.first { it.channelName == AlertForwarderImpl.CHANNEL_BARK }

            assertFalse(tgRes.success)
            assertEquals(200, tgRes.httpStatus)
            assertEquals("Too Many Requests: retry after 15", tgRes.errorMessage)
            assertTrue(barkRes.success)

            val tgReq = harness.externalDispatcher.recordedRequests().first { it.path == "/bot$botToken/sendMessage" }
            val tgText = TempScriptParser.json.parseToJsonElement(tgReq.body.readUtf8()).jsonObject
                .getValue("text").jsonPrimitive.content
            assertTrue("Expected Telegram text <= 4096 chars, got ${tgText.length}", tgText.length <= 4096)

            val barkReq = harness.externalDispatcher.recordedRequests().first { it.path == "/push" }
            val barkBody = TempScriptParser.json.parseToJsonElement(barkReq.body.readUtf8()).jsonObject
                .getValue("body").jsonPrimitive.content
            assertTrue("Expected Bark body <= 3500 chars, got ${barkBody.length}", barkBody.length <= 3500)
        }
    }

    @Test
    fun `E2E-T2-R15-3 AlertForwarderImpl strips NodeSeek cookies and interceptors and blocks 302 redirects`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(
                uid = 101L,
                sessionCookie = "secret_sess_t2",
                cfClearance = "secret_cf_t2",
            )
            harness.forumDispatcher.register("GET", "/api/account/credit/page-1") {
                MockResponse().setResponseCode(200).setBody("""{"success":true,"total":0,"data":[]}""")
            }
            // Confirm forumOkHttp actually attaches NodeSeek Cookie + User-Agent when calling forumServer
            harness.apiClient.getCreditLedger(1)
            val forumReq = harness.forumDispatcher.recordedRequests().single()
            assertTrue(forumReq.getHeader("Cookie")?.contains("session=secret_sess_t2") == true)
            assertEquals(harness.testUserAgent, forumReq.getHeader("User-Agent"))

            harness.externalDispatcher.register("POST", "/hook-redirect") {
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "/should-not-follow")
            }
            harness.externalDispatcher.register("POST", "/should-not-follow") {
                MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
            }

            harness.alertForwarder.updateConfig {
                AlertForwardingConfig(
                    enabled = true,
                    webhook = WebhookForwardConfig(
                        enabled = true,
                        url = harness.externalServer.url("/hook-redirect").toString(),
                    ),
                )
            }
            val results = harness.alertForwarder.sendTestAlert()
            assertEquals(1, results.size)
            assertFalse(results.single().success)
            assertEquals(302, results.single().httpStatus)

            val externalReqs = harness.externalDispatcher.recordedRequests()
            assertEquals(1, externalReqs.size)
            assertEquals("/hook-redirect", externalReqs.single().path)
            assertNull(externalReqs.single().getHeader("Cookie"))
            assertNull(externalReqs.single().getHeader("x-csrf-token"))
            assertNull(externalReqs.single().getHeader("x-dynamic-sign"))
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T2-R15-4 AlertUrlValidator rejects invalid URLs and traversal bot tokens while CRLF headers are sanitized`() = runBlocking {
        // URL validation
        listOf(
            "",
            "   ",
            "ftp://example.com/hook",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "ws://example.com/ws",
            "https://a.com/a b",
            "https://",
        ).forEach { badUrl ->
            assertFalse("Expected invalid URL: $badUrl", AlertUrlValidator.isValidHttpUrl(badUrl))
        }
        assertTrue(AlertUrlValidator.isValidHttpUrl("http://127.0.0.1:8080/hook"))
        assertTrue(AlertUrlValidator.isValidHttpUrl("https://api.day.app"))

        // Telegram Bot token validation
        listOf(
            "",
            "123:abc/def",
            "123:abc?x=1",
            "123:abc\\def",
            "123:abc%2fdef",
            ":abc",
            "123:",
            "1:2:3",
            "nocolon",
        ).forEach { badToken ->
            assertFalse("Expected invalid bot token: $badToken", AlertUrlValidator.isValidTelegramBotToken(badToken))
        }
        assertTrue(AlertUrlValidator.isValidTelegramBotToken("123456:ABC-def_789"))

        // CRLF sanitization in WebhookForwardConfig headers & HMAC secret
        E2eTestHarness().use { harness ->
            val hookOk = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("forwarding/telegram_bark_webhook.json"),
            ).jsonObject.getValue("webhookSuccess").toString()
            harness.externalDispatcher.register("POST", "/hooks/anode") {
                MockResponse().setResponseCode(200).setBody(hookOk)
            }

            harness.alertForwarder.updateConfig {
                AlertForwardingConfig(
                    enabled = true,
                    webhook = WebhookForwardConfig(
                        enabled = true,
                        url = harness.externalServer.url("/hooks/anode").toString(),
                        secretHeaderName = "X-Sig\r\nEvil",
                        secretValue = "sec\r\nInjected: 2",
                        customHeaders = mapOf("X-A\nB" to "val\r\nX: y"),
                    ),
                )
            }
            val results = harness.alertForwarder.sendTestAlert()
            assertEquals(1, results.size)
            assertTrue("Expected CRLF-sanitized webhook success, got ${results.single()}", results.single().success)

            val req = harness.externalDispatcher.recordedRequests().single()
            assertEquals("secInjected: 2", req.getHeader("X-SigEvil"))
            assertEquals("valX: y", req.getHeader("X-AB"))
            assertNull(req.getHeader("Evil"))
            assertNull(req.getHeader("Injected"))
            val body = req.body.readUtf8()
            val expectedSig = "sha256=${computeHmacSha256Hex("secInjected: 2", body)}"
            assertEquals(expectedSig, req.getHeader(WebhookFormatter.HEADER_SIGNATURE))
        }
    }

    @Test
    fun `E2E-T2-R15-5 emitters remain resilient when forwarding encounters ConnectException on closed port`() = runBlocking {
        E2eTestHarness().use { harness ->
            val closedPort = ServerSocket(0).use { it.localPort }
            harness.alertForwarder.updateConfig {
                AlertForwardingConfig(
                    enabled = true,
                    enabledEvents = AlertEventCategory.entries.toSet(),
                    webhook = WebhookForwardConfig(
                        enabled = true,
                        url = "http://127.0.0.1:$closedPort/unreachable-hook",
                    ),
                )
            }

            // 1. FleetMonitorRunner completes cleanly
            val probeFixture = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("forwarding/probe_and_restock_alerts.json"),
            ).jsonObject
            val onlineTemplate = probeFixture.getValue("nezhaOnlineServerPayload").toString()
            val offlineTemplate = probeFixture.getValue("nezhaOfflineHighTrafficServerPayload").toString()
            val isOnline = AtomicBoolean(true)
            harness.externalDispatcher.register("GET", "/api/v1/server") {
                val body = if (isOnline.get()) {
                    onlineTemplate.replace("__ONLINE_ISO__", Instant.now().toString())
                } else {
                    offlineTemplate.replace("__OFFLINE_ISO__", Instant.now().minusSeconds(1_800L).toString())
                }
                MockResponse().setResponseCode(200).setBody(body)
            }
            harness.fleetRepository.upsertPanel(
                ProbePanelConfig(
                    id = "p1",
                    name = "Panel",
                    baseUrl = harness.externalServer.url("/").toString(),
                    panelType = ProbePanelType.NEZHA_V1,
                ),
            )
            harness.fleetRepository.refreshAllPanels()
            isOnline.set(false)
            val fleetSink = E2eFleetAlertSink()
            val fleetReport = harness.createFleetMonitorRunner(alertSink = fleetSink).runOnce()
            assertEquals(1, fleetReport.offlineAlerts.size)
            assertEquals(1, fleetSink.offlineAlerts.size)

            // 2. RestockMonitorControllerImpl completes cleanly and updates local stock status
            val restockHtml = probeFixture.getValue("restockInStockHtml").jsonPrimitive.content
            val orderUrl = harness.externalServer.url("/cart.php?a=add&pid=1").toString()
            harness.externalDispatcher.register("GET", "/cart.php") {
                MockResponse().setResponseCode(200).setBody(restockHtml)
            }
            val restockRepo = RestockRepositoryImpl(
                storeFile = File(harness.tempDir, "restock_resilient.json"),
                clockMillis = harness.clock::nowMillis,
            )
            restockRepo.addOrUpdateWatch(
                RestockWatchItem(
                    id = "w-resilient",
                    title = "Resilient Watch",
                    rawUrl = orderUrl,
                    resolvedOrderUrl = orderUrl,
                    stockStatus = RestockStockStatus.OUT_OF_STOCK,
                    alertWaveArmed = true,
                ),
            )
            val ctx = testAppContext(harness.tempDir)
            val restockController = RestockMonitorControllerImpl(
                repository = restockRepo,
                analyzer = StockPageAnalyzer(httpClient = harness.externalCookieFreeHttpClient),
                notificationHelper = object : RestockNotificationHelper(ctx) {
                    override fun ensureChannel() = Unit
                    override fun hasPostNotificationsPermission(): Boolean = false
                    override fun postRestockUrgentNotification(event: RestockEventRecord) = Unit
                },
                scope = harness.backgroundScope,
                clockMillis = harness.clock::nowMillis,
                alertForwarder = harness.alertForwarder,
            )
            restockController.runBackgroundPollPass()
            assertEquals(RestockStockStatus.IN_STOCK, restockRepo.watchItemsFlow.value.single().stockStatus)

            // 3. PulseRadarMonitor completes cleanly and persists local alert
            val configB64 = Base64.getEncoder().encodeToString(
                """{"allCategory":[{"key":"trade","cn_text":"交易"}]}""".toByteArray(Charsets.UTF_8),
            )
            harness.forumDispatcher.register("GET", "/") {
                MockResponse().setResponseCode(200).setBody(
                    """
                    <html><body>
                      <div id="nsk-body">
                        <div class="post-list-item">
                          <div class="post-title"><a href="/post-701-1">DMIT Resilient Deal</a></div>
                          <a class="post-category" href="/categories/trade">trade</a>
                          <span class="info-author"><a href="/space/10">Seller</a></span>
                          <span class="info-views"><span>10</span></span>
                          <span class="info-comments-count"><span>0</span></span>
                        </div>
                      </div>
                      <script id="temp-script" type="application/json">$configB64</script>
                    </body></html>
                    """.trimIndent(),
                )
            }
            harness.radarRepository.addKeywordRule("DMIT")
            harness.radarRepository.updatePollingSettings {
                it.copy(isEnabled = true, keywordAlertsEnabled = true, replyTrackingEnabled = false, inboxPushEnabled = false)
            }
            val prefsStore = RadarPreferencesStore(
                PreferenceDataStoreFactory.create(
                    scope = harness.dataStoreScope,
                    produceFile = { File(harness.tempDir, "radar_resilient.preferences_pb") },
                ),
            )
            prefsStore.updateWatermarks { it.copy(lastSeenMaxTopicId = 400L) }
            val radarMonitor = PulseRadarMonitor(
                appContext = ctx,
                scope = harness.backgroundScope,
                apiClient = harness.apiClient,
                inboxRepository = InboxRepositoryImpl({ harness.apiClient }, harness.sessionRepository),
                sessionRepository = harness.sessionRepository,
                settingsRepository = harness.settingsRepository,
                radarRepository = harness.radarRepository,
                notificationDispatcher = object : PulseNotificationDispatcher(ctx) {
                    override fun ensureChannels() = Unit
                    override fun postKeywordHitNotification(record: LocalAlertRecord) = Unit
                },
                watermarkStore = prefsStore,
                clockMillis = harness.clock::nowMillis,
                isUnmeteredNetwork = { true },
                currentMinuteOfDay = { 12 * 60 },
                alertForwarder = harness.alertForwarder,
            )
            radarMonitor.runBackgroundPollPass()
            assertEquals(1, harness.radarRepository.unreadAlertCountFlow.first())
        }
    }

    @Test
    fun `E2E-T2-R15-6 AlertUrlValidator and WebhookFormatter enforce RFC 7230 tchar header names and X-Anode-Secret fallback`() = runBlocking {
        // Direct RFC 7230 tchar boundary checks
        assertTrue(AlertUrlValidator.isValidHeaderName("X-Valid_Token.1"))
        assertTrue(AlertUrlValidator.isValidHeaderName("X-Flag!#$"))
        listOf("", "   ", "Space Header", "Colon:Name", "X-Sig\r\nEvil: 1", "Ctrl\u0007Key").forEach { badName ->
            assertFalse("Expected invalid header name: $badName", AlertUrlValidator.isValidHeaderName(badName))
            assertNull("Expected sanitizeHeaderName null for: $badName", AlertUrlValidator.sanitizeHeaderName(badName))
        }

        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, sessionCookie = "r15_t6_sess")
            val hookOk = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("forwarding/telegram_bark_webhook.json"),
            ).jsonObject.getValue("webhookSuccess").toString()
            harness.externalDispatcher.register("POST", "/hooks/anode") {
                MockResponse().setResponseCode(200).setBody(hookOk)
            }

            harness.alertForwarder.updateConfig {
                AlertForwardingConfig(
                    enabled = true,
                    webhook = WebhookForwardConfig(
                        enabled = true,
                        url = harness.externalServer.url("/hooks/anode").toString(),
                        secretHeaderName = "X-Sig\r\nEvil: 1",
                        secretValue = "hmac_secret_r15_6",
                        customHeaders = mapOf(
                            "X-Valid_Token.1" to "ok1",
                            "X-Flag!#$" to "ok2",
                            "X-A\r\nEvilKey: foo" to "bad1",
                            "Space Header" to "bad2",
                            "Colon:Name" to "bad3",
                            "Ctrl\u0007Key" to "bad4",
                            "" to "bad5",
                        ),
                    ),
                )
            }

            val results = harness.alertForwarder.sendTestAlert()
            assertEquals(1, results.size)
            assertTrue("Expected RFC 7230 sanitized webhook HTTP 200, got ${results.single()}", results.single().success)
            assertEquals(200, results.single().httpStatus)

            val req = harness.externalDispatcher.recordedRequests().single()
            assertEquals("hmac_secret_r15_6", req.getHeader(WebhookForwardConfig.DEFAULT_SECRET_HEADER))
            val body = req.body.readUtf8()
            val expectedSig = "sha256=${computeHmacSha256Hex("hmac_secret_r15_6", body)}"
            assertEquals(expectedSig, req.getHeader(WebhookFormatter.HEADER_SIGNATURE))
            assertEquals("ok1", req.getHeader("X-Valid_Token.1"))
            assertEquals("ok2", req.getHeader("X-Flag!#$"))

            val headerNamesLower = req.headers.names().map { it.lowercase() }.toSet()
            assertFalse("space header" in headerNamesLower)
            assertFalse("colon" in headerNamesLower)
            assertFalse("x-sigevil" in headerNamesLower)
            assertFalse("evil" in headerNamesLower)
            assertTrue(req.headers.names().all { AlertUrlValidator.isValidHeaderName(it) })
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }
}
