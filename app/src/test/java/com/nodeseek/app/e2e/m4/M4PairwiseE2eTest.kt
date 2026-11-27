package com.nodeseek.app.e2e.m4

import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.netbench.TracerouteProbeReply
import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.data.widget.WidgetSnapshotRepositoryImpl
import com.nodeseek.app.domain.alerts.AlertEventCategory
import com.nodeseek.app.domain.alerts.AlertForwardingConfig
import com.nodeseek.app.domain.alerts.BarkForwardConfig
import com.nodeseek.app.domain.alerts.ForwardableAlertEvent
import com.nodeseek.app.domain.alerts.TelegramForwardConfig
import com.nodeseek.app.domain.alerts.WebhookForwardConfig
import com.nodeseek.app.domain.fleet.AssetExpiryUrgency
import com.nodeseek.app.domain.fleet.FleetAssetType
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.FleetPortfolioCalculator
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.mail.OtpWatchState
import com.nodeseek.app.domain.model.UpgradeEtaStatus
import com.nodeseek.app.domain.netbench.BackboneRouteTag
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.e2e.E2eFixtures
import com.nodeseek.app.e2e.E2eFleetAlertSink
import com.nodeseek.app.e2e.E2eMainDispatcherRule
import com.nodeseek.app.e2e.E2eNetBenchNetworkBridge
import com.nodeseek.app.e2e.E2eSshTransport
import com.nodeseek.app.e2e.E2eTestHarness
import com.nodeseek.app.e2e.E2eTracerouteKernelOps
import com.nodeseek.app.notifications.OtpWatchNotificationHelper
import com.nodeseek.app.ui.netbench.NetBenchPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class M4PairwiseE2eTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    @Test
    fun `E2E-T3-11 R8 domain expiry and R12 widget nearest VPS exclusion pair with R15 Telegram and Bark forwarding`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, sessionCookie = "ns_t3_11_cookie")
            val probeFixture = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("forwarding/probe_and_restock_alerts.json"),
            ).jsonObject
            val responses = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("forwarding/telegram_bark_webhook.json"),
            ).jsonObject
            val onlineTemplate = probeFixture.getValue("nezhaOnlineServerPayload").toString()
            val offlineTemplate = probeFixture.getValue("nezhaOfflineHighTrafficServerPayload").toString()

            val isOnline = AtomicBoolean(true)
            val botToken = "311311:T311Token"

            harness.externalDispatcher.register("GET", "/api/v1/server") {
                val payload = if (isOnline.get()) {
                    onlineTemplate.replace("__ONLINE_ISO__", Instant.now().toString())
                } else {
                    offlineTemplate.replace("__OFFLINE_ISO__", Instant.now().minusSeconds(1_800L).toString())
                }
                MockResponse().setResponseCode(200).setBody(payload)
            }
            harness.externalDispatcher.register("POST", "/bot$botToken/sendMessage") {
                MockResponse().setResponseCode(200).setBody(responses.getValue("telegramSuccess").toString())
            }
            harness.externalDispatcher.register("POST", "/push") {
                MockResponse().setResponseCode(200).setBody(responses.getValue("barkSuccess").toString())
            }

            val today = harness.clock.todayShanghai()
            harness.fleetRepository.upsertPanel(
                ProbePanelConfig(
                    id = "p1",
                    name = "MainPanel",
                    baseUrl = harness.externalServer.url("/").toString(),
                    panelType = ProbePanelType.NEZHA_V1,
                    notifyOnOffline = true,
                ),
            )
            harness.fleetRepository.upsertAsset(
                VpsAssetRecord(
                    id = "dom-2d",
                    nickname = "urgent-domain.com",
                    providerName = "Cloudflare",
                    specsSummary = "Domain",
                    region = "Global",
                    renewalAmount = "10.00",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = today.plusDays(2).toString(),
                    remindDaysBeforeExpiry = 7,
                    assetType = FleetAssetType.DOMAIN_CERT,
                    domainName = "urgent-domain.com",
                ),
            )
            harness.fleetRepository.upsertAsset(
                VpsAssetRecord(
                    id = "vps-10d",
                    nickname = "SG-主力小鸡",
                    providerName = "DMIT",
                    specsSummary = "1C/1G",
                    region = "SG",
                    renewalAmount = "20.00",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = today.plusDays(10).toString(),
                    remindDaysBeforeExpiry = 14,
                    linkedProbeKey = "p1:5",
                    monthlyTrafficQuotaGb = 100.0,
                ),
            )
            harness.fleetRepository.updateMonitorSettings(FleetMonitorSettings(backgroundMonitorEnabled = true))
            harness.fleetRepository.refreshAllPanels()

            val (_, telegramForwarder) = harness.createTelegramRoutingAlertForwarder()
            telegramForwarder.updateConfig {
                AlertForwardingConfig(
                    enabled = true,
                    enabledEvents = AlertEventCategory.entries.toSet(),
                    telegram = TelegramForwardConfig(enabled = true, botToken = botToken, chatId = "-10011"),
                    bark = BarkForwardConfig(
                        enabled = true,
                        serverUrl = harness.externalServer.url("/").toString().trimEnd('/'),
                        deviceKey = "dk_t3_11",
                    ),
                )
            }

            // Cold-load WidgetSnapshotRepositoryImpl -> nearestVps selects VPS (10d), excluding DOMAIN_CERT (2d)
            val coldFleet = FleetRepositoryImpl(
                storeFile = harness.fleetStoreFile,
                tokenCipher = harness.fleetCipher,
                nowMillisProvider = harness.clock::nowMillis,
            )
            val coldWidget = WidgetSnapshotRepositoryImpl(
                fleetRepository = coldFleet,
                sessionRepository = harness.sessionRepository,
                checkInRepository = harness.checkInRepository,
                settingsRepository = harness.settingsRepository,
                radarRepository = harness.radarRepository,
                seekMailRepository = harness.seekMailRepository,
                todayProvider = harness.clock::todayShanghai,
            )
            val initialWidget = coldWidget.loadServerStatusSnapshot()
            assertEquals(1, initialWidget.onlineProbeCount)
            assertEquals("SG-主力小鸡", initialWidget.nearestVpsName)
            assertEquals(10L, initialWidget.nearestVpsDaysRemaining)
            assertEquals(AssetExpiryUrgency.WARNING, initialWidget.nearestVpsUrgency)

            // R8 FleetPortfolioCalculator still includes BOTH DOMAIN_CERT and VPS in combined cost & renewals
            val portfolio = FleetPortfolioCalculator.summarize(
                assets = coldFleet.assetsFlow.value,
                snapshots = coldFleet.snapshotsFlow.value,
                today = today,
            )
            assertEquals(2, portfolio.upcomingRenewals.size)
            assertTrue(portfolio.totalAnnualizedCostCny > portfolio.vpsAnnualizedCostCny)

            // Flip probe offline -> FleetMonitorRunner triggers renewal reminders + Telegram/Bark forwarding
            isOnline.set(false)
            val fleetSink = E2eFleetAlertSink()
            val runner = harness.createFleetMonitorRunner(
                fleetRepo = harness.fleetRepository,
                alertSink = fleetSink,
                forwarder = telegramForwarder,
            )
            runner.runOnce()

            assertEquals(1, fleetSink.offlineAlerts.size)
            assertEquals(2, fleetSink.renewalReminders.size)
            assertTrue(fleetSink.renewalReminders.any { it.asset.id == "dom-2d" })
            assertTrue(fleetSink.renewalReminders.any { it.asset.id == "vps-10d" })

            val tgReqs = harness.externalDispatcher.recordedRequests().filter { it.path == "/bot$botToken/sendMessage" }
            val barkReqs = harness.externalDispatcher.recordedRequests().filter { it.path == "/push" }
            assertEquals(2, tgReqs.size) // ProbeOffline + TrafficThreshold
            assertEquals(2, barkReqs.size)
            harness.assertNoCookiesLeakedToExternalServer()

            val coldFleetAfter = FleetRepositoryImpl(
                storeFile = harness.fleetStoreFile,
                tokenCipher = harness.fleetCipher,
                nowMillisProvider = harness.clock::nowMillis,
            )
            val coldWidgetAfter = WidgetSnapshotRepositoryImpl(
                fleetRepository = coldFleetAfter,
                sessionRepository = harness.sessionRepository,
                checkInRepository = harness.checkInRepository,
                settingsRepository = harness.settingsRepository,
                radarRepository = harness.radarRepository,
                seekMailRepository = harness.seekMailRepository,
                todayProvider = harness.clock::todayShanghai,
            )
            val afterWidget = coldWidgetAfter.loadServerStatusSnapshot()
            assertEquals(1, afterWidget.totalProbeCount)
            assertEquals(0, afterWidget.onlineProbeCount)
            assertEquals(1, afterWidget.offlineProbeCount)
        }
    }

    @Test
    fun `E2E-T3-12 R12 CopyLatestOtp App Shortcut and R14 3-min OTP watcher share SeekMailRepository cache and delete sync`() = runBlocking {
        E2eTestHarness().use { harness ->
            val apiKey = "sk_live_" + "t312".repeat(8)
            harness.settingsRepository.setSeekMailApiKey(apiKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first { it.seekMailApiKey == apiKey }
                }
            }

            val fixture = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("mail/seekmail_otp_watch_pages.json"),
            ).jsonObject
            val emptyMailsJson = """{"data":[],"pagination":{"limit":20,"offset":0,"total":0}}"""
            val newOtpSummaryOnly = """
                {
                  "data": [
                    {
                      "id": 302,
                      "mailboxId": 5,
                      "fromAddress": "security@github.com",
                      "fromName": "GitHub",
                      "toAddress": "otp@seek.li",
                      "subject": "Your login verification",
                      "bodyText": "",
                      "bodyHtml": "",
                      "verificationCode": null,
                      "receivedAt": "2026-11-26T06:01:00Z"
                    }
                  ],
                  "pagination": {"limit": 20, "offset": 0, "total": 1}
                }
            """.trimIndent()
            val otpDetailNew = fixture.getValue("otpDetailNew").toString()

            val pollIdx = AtomicInteger(0)
            harness.externalDispatcher.register("GET", "/openapi/v1/mails") {
                val body = if (pollIdx.getAndIncrement() == 0) emptyMailsJson else newOtpSummaryOnly
                MockResponse().setResponseCode(200).setBody(body)
            }
            harness.externalDispatcher.register("GET", "/openapi/v1/mails/302") {
                MockResponse().setResponseCode(200).setBody(otpDetailNew)
            }

            val vm = harness.createSeekMailViewModel(initialMailboxId = 5L, container = harness.buildAppContainer())
            val stateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.hasConfiguredKey && !it.isLoadingMails }
                }
            }

            // Reset pollIdx for watcher start
            pollIdx.set(0)
            vm.startOtpWatch()

            val foundState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.otpWatchState is OtpWatchState.Found }
                }
            }
            val found = foundState.otpWatchState as OtpWatchState.Found
            assertEquals("839201", found.code)
            assertEquals(1, harness.otpWatchNotificationSink.foundNotifications.size)
            assertEquals("一键复制 839201", OtpWatchNotificationHelper.formatCopyActionLabel(found.code))

            // App Shortcut -> CopyLatestOtp uses shared SeekMailRepository cache (both offline and 500 fallback)
            assertEquals(
                com.nodeseek.app.navigation.ShortcutCommand.CopyLatestOtp,
                com.nodeseek.app.navigation.ShortcutIntentParser.parseAction(
                    com.nodeseek.app.navigation.ShortcutIntentParser.ACTION_SHORTCUT_COPY_OTP,
                ),
            )
            val pulse = harness.widgetSnapshotRepository.loadDailyPulseSnapshot()
            assertEquals("839201", pulse.latestOtpCode)

            harness.externalDispatcher.register("GET", "/openapi/v1/mails") {
                MockResponse().setResponseCode(500).setBody("""{"error":"offline"}""")
            }
            val shortcutOtp = harness.widgetSnapshotRepository.fetchOrCachedLatestOtp()
            assertEquals("839201", shortcutOtp?.code)

            // Deleting mail locally removes OTP from both getCachedLatestOtp() and DailyPulseWidgetSnapshot
            harness.seekMailRepository.deleteMailLocally(302L)
            assertNull(harness.seekMailRepository.getCachedLatestOtp())
            assertNull(harness.widgetSnapshotRepository.loadDailyPulseSnapshot().latestOtpCode)
            harness.assertNoCookiesLeakedToExternalServer()
            stateJob.cancel()
        }
    }

    @Test
    fun `E2E-T3-13 R3 and R12 Glance widget or shortcut random check-in updates CheckInRepository DailyPulse and Lv4-to-Lv5 upgrade ETA`() = runBlocking {
        val testEpoch = Instant.parse("2026-11-25T12:00:00Z").toEpochMilli()
        E2eTestHarness(initialEpochMillis = testEpoch).use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 3, coin = 1595)
            harness.settingsRepository.setPreferRandomCheckIn(true)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first { it.preferRandomCheckIn }
                }
            }

            val attendanceFixture = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/attendance_board_states.json"),
            ).jsonObject
            val uncheckedJson = attendanceFixture.getValue("unchecked").toString()
            val checkedAfterCheckInJson = """
                {
                  "success": true,
                  "record": {"id": 8809, "member_id": 101, "day_id": 20416, "gain": 12, "created_at": "2026-11-25 12:00:00"},
                  "order": 421,
                  "total": 421,
                  "list": [
                    {"member_id": 101, "member_name": "alice101", "gain": 12, "day_id": 20416, "created_at": "2026-11-25 12:00:00"}
                  ]
                }
            """.trimIndent()

            val checkedIn = AtomicBoolean(false)
            harness.forumDispatcher.register("GET", "/api/attendance/board") {
                MockResponse().setResponseCode(200).setBody(
                    if (checkedIn.get()) checkedAfterCheckInJson else uncheckedJson,
                )
            }
            harness.forumDispatcher.register("POST", "/api/attendance") {
                checkedIn.set(true)
                MockResponse().setResponseCode(200).setBody(
                    """{"success":true,"gain":12,"current":1607,"message":"随机签到获得 12 个鸡腿"}""",
                )
            }
            harness.forumDispatcher.register("GET", "/api/account/credit/page-1") {
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "success": true,
                      "total": 4,
                      "data": [
                        [12, 1607, "每日签到奖励", "2026-11-25 12:00:00"],
                        [5, 1595, "每日签到奖励", "2026-11-24 08:00:00"],
                        [8, 1590, "每日签到奖励", "2026-11-23 08:00:00"],
                        [-1, 1582, "给评论加鸡腿", "2026-11-22 20:00:00"]
                      ]
                    }
                    """.trimIndent(),
                )
            }

            assertEquals(
                com.nodeseek.app.navigation.ShortcutCommand.PerformCheckIn,
                com.nodeseek.app.navigation.ShortcutIntentParser.parseAction(
                    com.nodeseek.app.navigation.ShortcutIntentParser.ACTION_SHORTCUT_CHECK_IN,
                ),
            )

            // Initial board & pulse before check-in
            harness.checkInRepository.getCheckInBoard(page = 1, forceRefresh = true).getOrThrow()
            val beforePulse = harness.widgetSnapshotRepository.loadDailyPulseSnapshot()
            assertFalse(beforePulse.hasCheckedInToday)
            assertTrue(beforePulse.preferRandomCheckIn)
            assertEquals(1595, beforePulse.drumsticks)

            // Execute widget/shortcut check-in respecting awaitLoadedSettings().preferRandomCheckIn
            val preferRandom = harness.settingsRepository.awaitLoadedSettings().preferRandomCheckIn
            val checkInResult = harness.checkInRepository.checkIn(randomReward = preferRandom).getOrThrow()
            assertTrue(checkInResult.hasCheckedInToday)
            assertEquals(12, checkInResult.todayGain)

            val checkInReq = harness.forumDispatcher.recordedRequests()
                .single { it.method == "POST" && it.path?.startsWith("/api/attendance") == true }
            assertEquals("true", checkInReq.requestUrl?.queryParameter("random"))

            val afterPulse = harness.widgetSnapshotRepository.loadDailyPulseSnapshot()
            assertTrue(afterPulse.hasCheckedInToday)
            assertEquals(12, afterPulse.todayGain)
            assertEquals(421, afterPulse.todayOrder)
            assertEquals(1607, afterPulse.drumsticks)

            // R3 30d credit analytics reflects new 1607 coin balance (Lv.4 unlocked -> targeting Lv.5)
            val analyticsResult = harness.communityToolsRepository.getRecentCreditAnalytics(
                currentCoins = afterPulse.drumsticks,
                nowEpochMillis = testEpoch,
            ).getOrThrow()
            val analytics = analyticsResult.analytics
            assertEquals(3, analytics.checkInCount)
            assertEquals(25, analytics.checkInTotalCoins)
            assertEquals(25.0 / 3.0, analytics.checkInAverageCoins!!, 1e-6)
            assertEquals(4, analytics.upgradeEta.currentLevel)
            assertEquals(5, analytics.upgradeEta.nextLevel)
            assertEquals(UpgradeEtaStatus.ESTIMATED, analytics.upgradeEta.status)
            assertTrue(analytics.upgradeEta.coinsRemaining > 0)
        }
    }

    @Test
    fun `E2E-T3-14 R5 NetBench route status and R15 probe alert forwarding share monitored VPS without cookie leak`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, sessionCookie = "ns_t3_14_cookie")
            harness.registerNetBenchGeoIpRoutes(clientEgressIp = "114.240.88.123")

            val target = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "SG-Edge",
                    host = "203.0.113.88",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "pass123",
                ),
            )
            val sshTransport = E2eSshTransport(
                commandHandler = { _, _ ->
                    listOf(
                        SshStreamEvent.Connected,
                        SshStreamEvent.OutputChunk(E2eFixtures.loadMtrSection("REVERSE_NEXTTRACE_CN2_GIA") + "\n"),
                        SshStreamEvent.Completed(0),
                    )
                },
            )
            val sshManager = harness.createSshSessionManager(transport = sshTransport)

            // Forward MTR with 25% loss + reverse CN2 GIA trace via NetBenchSessionController
            val kernelOps = E2eTracerouteKernelOps(
                hopResponder = { ttl, seq, roundIdx ->
                    when (ttl) {
                        1 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "202.97.33.1", seq, 18.0)
                        else -> if (roundIdx == 1) {
                            TracerouteProbeReply(TracerouteProbeReply.Kind.TIMEOUT, null, seq, null)
                        } else {
                            TracerouteProbeReply(TracerouteProbeReply.Kind.ECHO_REPLY, "203.0.113.88", seq, 92.0)
                        }
                    }
                },
            )
            val controller = harness.createNetBenchController(
                networkBridge = E2eNetBenchNetworkBridge(),
                tracerouteKernelOps = kernelOps,
                sshSessionManager = sshManager,
            )
            controller.updateTargetInput("203.0.113.88")
            controller.updateTracerouteProbesPerHop(4)
            controller.runForwardTracerouteOnly()
            val forwardDone = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    controller.uiState.first { !it.isRunning && it.activePhase == NetBenchPhase.COMPLETED }
                }
            }
            val progress = forwardDone.tracerouteProgress!!
            assertTrue(BackboneRouteTag.CT_163 in progress.summary.dominantBackboneTags)
            assertTrue(progress.hops.any { it.lossPercent > 0.0 })

            controller.runReverseTraceroute(target)
            val reverseDone = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    controller.uiState.first { !it.isRunningReverseTrace && it.reverseTracerouteResult != null }
                }
            }
            assertTrue(reverseDone.reverseTracerouteResult!!.reachedTarget)

            // Now monitor same VPS ("p1:5") with R15 AlertForwarder
            val probeFixture = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("forwarding/probe_and_restock_alerts.json"),
            ).jsonObject
            val responses = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("forwarding/telegram_bark_webhook.json"),
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
            harness.externalDispatcher.register("POST", "/hooks/anode") {
                MockResponse().setResponseCode(200).setBody(responses.getValue("webhookSuccess").toString())
            }
            harness.externalDispatcher.register("POST", "/push") {
                MockResponse().setResponseCode(200).setBody(responses.getValue("barkSuccess").toString())
            }

            harness.alertForwarder.updateConfig {
                AlertForwardingConfig(
                    enabled = true,
                    enabledEvents = AlertEventCategory.entries.toSet(),
                    bark = BarkForwardConfig(
                        enabled = true,
                        serverUrl = harness.externalServer.url("/").toString().trimEnd('/'),
                        deviceKey = "dk_t3_14",
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
                    name = "NetBenchPanel",
                    baseUrl = harness.externalServer.url("/").toString(),
                    panelType = ProbePanelType.NEZHA_V1,
                    notifyOnOffline = true,
                ),
            )
            harness.fleetRepository.upsertAsset(
                VpsAssetRecord(
                    id = "asset-sg-edge",
                    nickname = "SG-Edge",
                    providerName = "DMIT",
                    specsSummary = "1C/1G",
                    region = "SG",
                    renewalAmount = "10.00",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.MONTHLY,
                    expiryDateIso = harness.clock.todayShanghai().plusDays(20).toString(),
                    linkedProbeKey = "p1:5",
                    monthlyTrafficQuotaGb = 100.0,
                    notes = "Forward loss detected",
                ),
            )
            harness.fleetRepository.refreshAllPanels()

            isOnline.set(false)
            harness.createFleetMonitorRunner().runOnce()

            val hookPayloads = harness.externalDispatcher.recordedRequests()
                .filter { it.path == "/hooks/anode" }
                .map { TempScriptParser.json.parseToJsonElement(it.body.readUtf8()).jsonObject }
            val offlinePayload = hookPayloads.first { it.getValue("event").jsonPrimitive.content == "probe_offline" }
            val details = offlinePayload.getValue("details").jsonObject
            assertEquals("NetBenchPanel", details.getValue("panelName").jsonPrimitive.content)
            assertEquals("SG-Edge", details.getValue("serverName").jsonPrimitive.content)
            assertEquals("p1:5", details.getValue("probeKey").jsonPrimitive.content)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T3-15 R14 OTP watcher Bearer and R15 Telegram Bark forwarding execute concurrently with zero cookie or header cross-leak`() = runBlocking {
        E2eTestHarness().use { harness ->
            val seekKey = "sm_e2e_test_key_123456"
            harness.seedAuthenticatedSession(
                uid = 101L,
                sessionCookie = "ns_t3_15_cookie",
                cfClearance = "ns_t3_15_cf",
            )
            harness.settingsRepository.setSeekMailApiKey(seekKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first { it.seekMailApiKey == seekKey }
                }
            }

            val seekFixture = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("mail/seekmail_otp_and_billing.json"),
            ).jsonObject
            val fwdResponses = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("forwarding/telegram_bark_webhook.json"),
            ).jsonObject

            val emptyMailsJson = """{"data":[],"pagination":{"limit":20,"offset":0,"total":0}}"""
            val summary301Json = """
                {
                  "data": [
                    {
                      "id": 301,
                      "mailboxId": 5,
                      "fromAddress": "security@nodeseek.com",
                      "fromName": "NodeSeek Security",
                      "toAddress": "tester@seek.li",
                      "subject": "NodeSeek Security Notice",
                      "bodyText": "",
                      "bodyHtml": "",
                      "verificationCode": null,
                      "receivedAt": "2026-11-25T05:50:00Z"
                    }
                  ],
                  "pagination": {"limit": 20, "offset": 0, "total": 1}
                }
            """.trimIndent()
            val detail301Json = seekFixture.getValue("otpDetail").toString()

            val pollIdx = AtomicInteger(0)
            val botToken = "315315:T315BotToken"

            harness.externalDispatcher.register("GET", "/openapi/v1/mails") {
                val body = if (pollIdx.getAndIncrement() == 0) emptyMailsJson else summary301Json
                MockResponse().setResponseCode(200).setBody(body)
            }
            harness.externalDispatcher.register("GET", "/openapi/v1/mails/301") {
                MockResponse().setResponseCode(200).setBody(detail301Json)
            }
            harness.externalDispatcher.register("POST", "/bot$botToken/sendMessage") {
                MockResponse().setResponseCode(200).setBody(fwdResponses.getValue("telegramSuccess").toString())
            }
            harness.externalDispatcher.register("POST", "/push") {
                MockResponse().setResponseCode(200).setBody(fwdResponses.getValue("barkSuccess").toString())
            }

            val (_, telegramForwarder) = harness.createTelegramRoutingAlertForwarder()
            telegramForwarder.updateConfig {
                AlertForwardingConfig(
                    enabled = true,
                    enabledEvents = AlertEventCategory.entries.toSet(),
                    telegram = TelegramForwardConfig(enabled = true, botToken = botToken, chatId = "-100315"),
                    bark = BarkForwardConfig(
                        enabled = true,
                        serverUrl = harness.externalServer.url("/").toString().trimEnd('/'),
                        deviceKey = "dk_t3_15",
                    ),
                )
            }

            val watcher = harness.createSeekMailOtpWatcher()
            val (foundOtp, restockResults, probeResults) = coroutineScope {
                watcher.startWatch(mailboxId = 5L, mailboxAddress = "tester@seek.li")
                val watchJob = async(Dispatchers.Default) {
                    withTimeout(5_000L) {
                        watcher.state.first { it is OtpWatchState.Found } as OtpWatchState.Found
                    }
                }
                val restockJob = async {
                    telegramForwarder.forward(
                        ForwardableAlertEvent.RestockAvailable(
                            watchId = "w-t315",
                            watchTitle = "LAX Flash",
                            resolvedOrderUrl = "https://merchant.test/cart?pid=1",
                            priceHint = "$12/yr",
                        ),
                    )
                }
                val probeJob = async {
                    telegramForwarder.forward(
                        ForwardableAlertEvent.ProbeOffline(
                            panelName = "Panel",
                            serverName = "SG-Edge",
                            probeKey = "p1:5",
                        ),
                    )
                }
                Triple(watchJob.await(), restockJob.await(), probeJob.await())
            }

            assertEquals("482910", foundOtp.code)
            assertEquals(301L, foundOtp.mailId)
            assertEquals(1, harness.otpWatchNotificationSink.foundNotifications.size)

            assertEquals(2, restockResults.size)
            assertTrue(restockResults.all { it.success && it.httpStatus == 200 })
            assertEquals(2, probeResults.size)
            assertTrue(probeResults.all { it.success && it.httpStatus == 200 })

            val allExternalReqs = harness.externalDispatcher.recordedRequests()
            val mailReqs = allExternalReqs.filter { it.path?.startsWith("/openapi/v1/") == true }
            val fwdReqs = allExternalReqs.filter {
                it.path == "/bot$botToken/sendMessage" || it.path == "/push"
            }
            assertTrue(mailReqs.isNotEmpty())
            assertEquals(4, fwdReqs.size)
            mailReqs.forEach { req ->
                assertEquals("Bearer $seekKey", req.getHeader("Authorization"))
                assertNull(req.getHeader("Cookie"))
            }
            fwdReqs.forEach { req ->
                assertNull(req.getHeader("Authorization"))
                assertNull(req.getHeader("Cookie"))
            }
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }
}
