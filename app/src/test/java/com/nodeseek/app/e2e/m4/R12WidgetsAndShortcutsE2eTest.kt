package com.nodeseek.app.e2e.m4

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.nodeseek.app.data.fleet.FleetAlertNotifier
import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.local.SettingsRepositoryImpl
import com.nodeseek.app.data.local.SettingsSecretCipher
import com.nodeseek.app.data.mail.SeekMailApiClient
import com.nodeseek.app.data.mail.SeekMailDiskCache
import com.nodeseek.app.data.mail.SeekMailRepositoryImpl
import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.data.widget.WidgetSnapshotRepositoryImpl
import com.nodeseek.app.domain.fleet.AssetExpiryUrgency
import com.nodeseek.app.domain.fleet.FleetAssetType
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.mail.LatestSeekMailOtp
import com.nodeseek.app.domain.mail.SeekMailPage
import com.nodeseek.app.domain.mail.SeekMailSummary
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.CheckInStatus
import com.nodeseek.app.domain.radar.LocalAlertKind
import com.nodeseek.app.domain.radar.LocalAlertRecord
import com.nodeseek.app.domain.radar.RadarPollingSettings
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.domain.widget.WidgetSnapshotAggregator
import com.nodeseek.app.e2e.E2eFixtures
import com.nodeseek.app.e2e.E2eMainDispatcherRule
import com.nodeseek.app.e2e.E2eTestHarness
import com.nodeseek.app.navigation.CheckInBoardRoute
import com.nodeseek.app.navigation.LiteSshRoute
import com.nodeseek.app.navigation.MyServersRoute
import com.nodeseek.app.navigation.PulseRadarRoute
import com.nodeseek.app.navigation.SeekMailRoute
import com.nodeseek.app.navigation.ShortcutCommand
import com.nodeseek.app.navigation.ShortcutIntentParser
import com.nodeseek.app.widget.CheckInActionCallback
import com.nodeseek.app.widget.DailyPulseGlanceWidgetReceiver
import com.nodeseek.app.widget.RefreshServerStatusActionCallback
import com.nodeseek.app.widget.ServerStatusGlanceWidgetReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class R12WidgetsAndShortcutsE2eTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    private val appModuleDir: File by lazy {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        when {
            File(cwd, "src/main/AndroidManifest.xml").isFile -> cwd
            File(cwd, "app/src/main/AndroidManifest.xml").isFile -> File(cwd, "app")
            else -> error("Unable to locate app module directory from ${cwd.absolutePath}")
        }
    }

    private fun parseAppXml(relativePath: String): Document {
        val file = File(appModuleDir, relativePath)
        assertTrue("Missing XML file: ${file.absolutePath}", file.isFile)
        return DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
    }

    private fun Element.androidAttr(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)
            .ifEmpty { getAttribute("android:$name") }

    private fun elementsByTag(doc: Document, tag: String): List<Element> {
        val nodes = doc.getElementsByTagName(tag)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private class PassThroughSettingsCipher : SettingsSecretCipher {
        override fun isEncryptedEnvelope(storedValue: String): Boolean = storedValue.startsWith("enc:")
        override fun encrypt(plainText: String): String = if (plainText.isEmpty()) "" else "enc:$plainText"
        override fun decrypt(storedValue: String): String = storedValue.removePrefix("enc:")
    }

    @Test
    fun `E2E-T1-R12-1 cold-start ServerStatusWidgetSnapshot loads fleet store selects primary server and excludes DOMAIN_CERT from nearest VPS`() = runBlocking {
        E2eTestHarness().use { harness ->
            val nowIso = Instant.now().toString()
            val staleIso = Instant.now().minusSeconds(3_600L).toString()
            val gib = 1024L * 1024L * 1024L

            harness.externalDispatcher.register("GET", "/api/v1/server") {
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "success": true,
                      "data": [
                        {
                          "id": 1,
                          "name": "JP-Backup",
                          "last_active": "$nowIso",
                          "host": {"mem_total": 1073741824, "country_code": "JP"},
                          "state": {"cpu": 10.0, "mem_used": 268435456, "uptime": 3600, "net_in_transfer": ${5L * gib}, "net_out_transfer": ${10L * gib}}
                        },
                        {
                          "id": 2,
                          "name": "LAX-主力",
                          "last_active": "$nowIso",
                          "host": {"mem_total": 2147483648, "country_code": "US"},
                          "state": {"cpu": 25.0, "mem_used": 536870912, "uptime": 7200, "net_in_transfer": ${25L * gib}, "net_out_transfer": ${50L * gib}}
                        },
                        {
                          "id": 3,
                          "name": "SG-Offline",
                          "last_active": "$staleIso",
                          "host": {"mem_total": 1073741824, "country_code": "SG"},
                          "state": {"cpu": 0.0, "mem_used": 0, "uptime": 0, "net_in_transfer": 0, "net_out_transfer": 0}
                        }
                      ]
                    }
                    """.trimIndent(),
                )
            }

            val today = harness.clock.todayShanghai()
            val domainExpiryIso = today.plusDays(1).toString()
            val vpsNearExpiryIso = today.plusDays(4).toString()
            val vpsFarExpiryIso = today.plusDays(30).toString()

            harness.fleetRepository.upsertPanel(
                ProbePanelConfig(
                    id = "p1",
                    name = "NezhaPrimary",
                    baseUrl = harness.externalServer.url("/").toString(),
                    panelType = ProbePanelType.NEZHA_V1,
                ),
            )
            harness.fleetRepository.upsertAsset(
                VpsAssetRecord(
                    id = "dom-soon",
                    nickname = "example.com",
                    providerName = "Cloudflare",
                    specsSummary = "Domain",
                    region = "Global",
                    renewalAmount = "10.00",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = domainExpiryIso,
                    assetType = FleetAssetType.DOMAIN_CERT,
                    domainName = "example.com",
                ),
            )
            harness.fleetRepository.upsertAsset(
                VpsAssetRecord(
                    id = "vps-near",
                    nickname = "DMIT-Near",
                    providerName = "DMIT",
                    specsSummary = "1C/1G",
                    region = "US",
                    renewalAmount = "29.90",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = vpsNearExpiryIso,
                    linkedProbeKey = "p1:1",
                    monthlyTrafficQuotaGb = 500.0,
                ),
            )
            harness.fleetRepository.upsertAsset(
                VpsAssetRecord(
                    id = "vps-primary",
                    nickname = "LAX-主力节点",
                    providerName = "Bandwagon",
                    specsSummary = "2C/2G",
                    region = "US",
                    renewalAmount = "49.99",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = vpsFarExpiryIso,
                    linkedProbeKey = "p1:2",
                    monthlyTrafficQuotaGb = 100.0,
                    tags = listOf("主力", "CN2 GIA"),
                ),
            )
            harness.fleetRepository.refreshAllPanels()

            // Re-instantiate cold-start FleetRepositoryImpl + WidgetSnapshotRepositoryImpl on same fleet_store.json
            val coldFleetRepo = FleetRepositoryImpl(
                storeFile = harness.fleetStoreFile,
                tokenCipher = harness.fleetCipher,
                nowMillisProvider = harness.clock::nowMillis,
            )
            val coldWidgetRepo = WidgetSnapshotRepositoryImpl(
                fleetRepository = coldFleetRepo,
                sessionRepository = harness.sessionRepository,
                checkInRepository = harness.checkInRepository,
                settingsRepository = harness.settingsRepository,
                radarRepository = harness.radarRepository,
                seekMailRepository = harness.seekMailRepository,
                todayProvider = harness.clock::todayShanghai,
            )

            assertTrue(coldFleetRepo.snapshotsFlow.value.isEmpty())
            val snapshot = coldWidgetRepo.loadServerStatusSnapshot()

            assertEquals(3, snapshot.totalProbeCount)
            assertEquals(2, snapshot.onlineProbeCount)
            assertEquals(1, snapshot.offlineProbeCount)
            assertTrue(snapshot.hasProbes)
            assertEquals("LAX-主力节点", snapshot.primaryServerName)
            assertEquals("🇺🇸", snapshot.primaryServerFlag)
            assertEquals(75L * gib, snapshot.primaryTrafficUsedBytes)
            assertEquals(100L * gib, snapshot.primaryTrafficLimitBytes)
            assertEquals(0.75f, snapshot.primaryTrafficRatio!!, 0.001f)

            assertTrue(snapshot.hasNearestVps)
            assertEquals("DMIT-Near", snapshot.nearestVpsName)
            assertEquals(4L, snapshot.nearestVpsDaysRemaining)
            assertEquals(AssetExpiryUrgency.WARNING, snapshot.nearestVpsUrgency)
            assertEquals(vpsNearExpiryIso, snapshot.nearestVpsExpiryIso)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T1-R12-2 cold-start DailyPulseWidgetSnapshot awaits settings prefers unread radar alert and loads cached OTP`() = runBlocking {
        E2eTestHarness(
            initialEpochMillis = Instant.parse("2026-11-25T12:00:00Z").toEpochMilli(),
        ).use { harness ->
            val seekMailKey = "sk_live_" + "a".repeat(32)
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4, coin = 1650)

            val attendanceFixture = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/attendance_board_states.json"),
            ).jsonObject
            harness.forumDispatcher.register("GET", "/api/attendance/board") {
                MockResponse().setResponseCode(200).setBody(attendanceFixture.getValue("fiveGainChecked").toString())
            }
            val checkInBoard = harness.checkInRepository.getCheckInBoard(page = 1, forceRefresh = true).getOrThrow()
            assertTrue(checkInBoard.hasCheckedInToday)

            // Configure settings in DataStore and wait until persisted
            harness.settingsRepository.setSeekMailApiKey(seekMailKey)
            harness.settingsRepository.setPreferRandomCheckIn(true)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first {
                        it.seekMailApiKey == seekMailKey && it.preferRandomCheckIn
                    }
                }
            }

            // Seed Radar alerts: newer read alert vs slightly older unread alert
            harness.radarRepository.updatePollingSettings { it.copy(isEnabled = true) }
            harness.radarRepository.recordAlertIfAbsent(
                LocalAlertRecord(
                    dedupKey = "r12:unread:1",
                    kind = LocalAlertKind.KEYWORD_HIT,
                    topicId = 701L,
                    topicTitle = "Unread DMIT Deal",
                    matchedKeyword = "DMIT",
                    isRead = false,
                    createdAtEpochMillis = 1_764_000_000_000L,
                ),
            )
            harness.radarRepository.recordAlertIfAbsent(
                LocalAlertRecord(
                    dedupKey = "r12:read:2",
                    kind = LocalAlertKind.KEYWORD_HIT,
                    topicId = 702L,
                    topicTitle = "Newer Already Read Deal",
                    matchedKeyword = "VPS",
                    isRead = true,
                    createdAtEpochMillis = 1_764_000_050_000L,
                ),
            )

            // Seed cached OTP across mb_0 and mb_42
            harness.seekMailDiskCache.saveDefaultMailsPage(
                apiKey = seekMailKey,
                mailboxId = null,
                page = SeekMailPage(
                    items = listOf(
                        SeekMailSummary(
                            id = 10L,
                            mailboxId = 1L,
                            fromAddress = "old@auth.test",
                            fromName = "OldAuth",
                            toAddress = "otp@seek.li",
                            subject = "Old OTP 111222",
                            snippet = "Code is 111222",
                            verificationCode = "111222",
                            receivedAt = "2026-11-25T08:00:00Z",
                        ),
                    ),
                    limit = 20,
                    offset = 0,
                    total = 1,
                    hasMore = false,
                ),
                fetchedAtEpochMillis = 1_000L,
            )
            harness.seekMailDiskCache.saveDefaultMailsPage(
                apiKey = seekMailKey,
                mailboxId = 42L,
                page = SeekMailPage(
                    items = listOf(
                        SeekMailSummary(
                            id = 25L,
                            mailboxId = 42L,
                            fromAddress = "security@github.com",
                            fromName = "GitHub",
                            toAddress = "gh@seek.li",
                            subject = "Login code",
                            snippet = "您的验证码为 482-910，请勿泄露",
                            verificationCode = null,
                            receivedAt = "2026-11-26T09:00:00Z",
                        ),
                    ),
                    limit = 20,
                    offset = 0,
                    total = 1,
                    hasMore = false,
                ),
                fetchedAtEpochMillis = 2_000L,
            )

            val pulse = harness.widgetSnapshotRepository.loadDailyPulseSnapshot()
            assertTrue(pulse.isSignedIn)
            assertEquals("alice101", pulse.username)
            assertEquals(1650, pulse.drumsticks)
            assertTrue(pulse.hasCheckedInToday)
            assertEquals(5, pulse.todayGain)
            assertEquals(421, pulse.todayOrder)
            assertTrue(pulse.preferRandomCheckIn)
            assertTrue(pulse.radarEnabled)
            assertEquals(1, pulse.unreadAlertCount)
            assertEquals("Unread DMIT Deal", pulse.latestAlertTitle)
            assertEquals("DMIT", pulse.latestAlertKeyword)
            assertEquals("482910", pulse.latestOtpCode)
            assertEquals("GitHub", pulse.latestOtpSender)
            assertTrue(pulse.hasSeekMailKey)
        }
    }

    @Test
    fun `E2E-T1-R12-3 fetchOrCachedLatestOtp fetches fresh OTP with Bearer and zero cookies then falls back to cache on 500 or timeout`() = runBlocking {
        E2eTestHarness().use { harness ->
            val seekMailKey = "sk_live_" + "b".repeat(32)
            harness.seedAuthenticatedSession(uid = 101L, sessionCookie = "r12_sess_cookie", cfClearance = "r12_cf")
            harness.settingsRepository.setSeekMailApiKey(seekMailKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first { it.seekMailApiKey == seekMailKey }
                }
            }

            val watchPages = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("mail/seekmail_otp_watch_pages.json"),
            ).jsonObject
            val baselineJson = watchPages.getValue("baselineMailsPage").toString()

            // 1. Fresh online fetch populates disk cache with 111222 and returns it
            harness.externalDispatcher.enqueueNext(
                method = "GET",
                path = "/openapi/v1/mails",
                response = MockResponse().setResponseCode(200).setBody(baselineJson),
            )
            val firstOtp = harness.widgetSnapshotRepository.fetchOrCachedLatestOtp()
            assertNotNull(firstOtp)
            assertEquals("111222", firstOtp!!.code)
            assertEquals(300L, firstOtp.mailId)

            val firstReq = harness.externalDispatcher.recordedRequests().single()
            assertEquals("Bearer $seekMailKey", firstReq.getHeader("Authorization"))
            harness.assertNoCookiesLeakedToExternalServer()

            // 2. HTTP 500 falls back to disk-cached 111222
            harness.externalDispatcher.enqueueNext(
                method = "GET",
                path = "/openapi/v1/mails",
                response = MockResponse().setResponseCode(500).setBody("""{"error":"down"}"""),
            )
            val fallbackOn500 = harness.widgetSnapshotRepository.fetchOrCachedLatestOtp()
            assertNotNull(fallbackOn500)
            assertEquals("111222", fallbackOn500!!.code)

            // 3. Timeout falls back to disk-cached 111222
            harness.externalDispatcher.enqueueNext(
                method = "GET",
                path = "/openapi/v1/mails",
                response = MockResponse()
                    .setResponseCode(200)
                    .setBody(baselineJson)
                    .setBodyDelay(250L, TimeUnit.MILLISECONDS),
            )
            val fallbackOnTimeout = harness.widgetSnapshotRepository.fetchOrCachedLatestOtp(timeoutMs = 30L)
            assertNotNull(fallbackOnTimeout)
            assertEquals("111222", fallbackOnTimeout!!.code)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T1-R12-4 ShortcutIntentParser maps all App Shortcuts and Widget actions accurately`() {
        assertEquals(
            ShortcutCommand.PerformCheckIn,
            ShortcutIntentParser.parseAction(ShortcutIntentParser.ACTION_SHORTCUT_CHECK_IN),
        )
        assertEquals(
            ShortcutCommand.CopyLatestOtp,
            ShortcutIntentParser.parseAction(ShortcutIntentParser.ACTION_SHORTCUT_COPY_OTP),
        )
        assertEquals(
            ShortcutCommand.Navigate(LiteSshRoute),
            ShortcutIntentParser.parseAction(ShortcutIntentParser.ACTION_SHORTCUT_OPEN_SSH),
        )
        assertEquals(
            ShortcutCommand.Navigate(CheckInBoardRoute),
            ShortcutIntentParser.parseAction(ShortcutIntentParser.ACTION_WIDGET_OPEN_CHECKIN_BOARD),
        )
        assertEquals(
            ShortcutCommand.Navigate(PulseRadarRoute(initialTab = 0)),
            ShortcutIntentParser.parseAction(ShortcutIntentParser.ACTION_WIDGET_OPEN_RADAR),
        )
        assertEquals(
            ShortcutCommand.Navigate(SeekMailRoute(initialMailboxId = 0L, initialMailId = 0L)),
            ShortcutIntentParser.parseAction(ShortcutIntentParser.ACTION_WIDGET_OPEN_SEEK_MAIL),
        )
        assertEquals(
            ShortcutCommand.Navigate(MyServersRoute(initialTab = 0, highlightAssetId = "")),
            ShortcutIntentParser.parseAction(
                action = FleetAlertNotifier.ACTION_OPEN_FLEET,
                fleetTab = FleetAlertNotifier.EXTRA_TAB_PROBES,
            ),
        )
        assertEquals(
            ShortcutCommand.Navigate(MyServersRoute(initialTab = 1, highlightAssetId = "asset-42")),
            ShortcutIntentParser.parseAction(
                action = FleetAlertNotifier.ACTION_OPEN_FLEET,
                fleetTab = FleetAlertNotifier.EXTRA_TAB_ASSETS,
                assetId = "  asset-42 ",
            ),
        )
        assertEquals(
            ShortcutCommand.Navigate(MyServersRoute(initialTab = 1, highlightAssetId = "vps-9")),
            ShortcutIntentParser.parseAction(
                action = FleetAlertNotifier.ACTION_OPEN_FLEET,
                fleetTab = null,
                assetId = "vps-9",
            ),
        )

        listOf(
            ShortcutIntentParser.ACTION_SHORTCUT_CHECK_IN,
            ShortcutIntentParser.ACTION_SHORTCUT_OPEN_SSH,
            ShortcutIntentParser.ACTION_SHORTCUT_COPY_OTP,
            ShortcutIntentParser.ACTION_WIDGET_OPEN_CHECKIN_BOARD,
            ShortcutIntentParser.ACTION_WIDGET_OPEN_RADAR,
            ShortcutIntentParser.ACTION_WIDGET_OPEN_SEEK_MAIL,
            FleetAlertNotifier.ACTION_OPEN_FLEET,
        ).forEach { action ->
            assertTrue("Expected isKnownAction($action) == true", ShortcutIntentParser.isKnownAction(action))
        }
    }

    @Test
    fun `E2E-T1-R12-5 Manifest shortcuts XML widget providers strings ProGuard and Glance zero-arg constructors are valid`() {
        val manifest = parseAppXml("src/main/AndroidManifest.xml")
        val mainActivity = elementsByTag(manifest, "activity")
            .firstOrNull { it.androidAttr("name") == ".MainActivity" }
        assertNotNull(mainActivity)
        assertEquals("singleTop", mainActivity!!.androidAttr("launchMode"))

        val shortcutsMeta = (0 until mainActivity.getElementsByTagName("meta-data").length)
            .mapNotNull { mainActivity.getElementsByTagName("meta-data").item(it) as? Element }
            .firstOrNull { it.androidAttr("name") == "android.app.shortcuts" }
        assertNotNull(shortcutsMeta)
        assertEquals("@xml/shortcuts", shortcutsMeta!!.androidAttr("resource"))

        val receivers = elementsByTag(manifest, "receiver")
        listOf(
            ".widget.ServerStatusGlanceWidgetReceiver" to "@xml/widget_server_status_info",
            ".widget.DailyPulseGlanceWidgetReceiver" to "@xml/widget_daily_pulse_info",
        ).forEach { (receiverName, providerRes) ->
            val receiver = receivers.firstOrNull { it.androidAttr("name") == receiverName }
            assertNotNull("Missing receiver $receiverName", receiver)
            assertEquals("true", receiver!!.androidAttr("exported"))
            val actions = (0 until receiver.getElementsByTagName("action").length)
                .mapNotNull { receiver.getElementsByTagName("action").item(it) as? Element }
                .map { it.androidAttr("name") }
            assertTrue("android.appwidget.action.APPWIDGET_UPDATE" in actions)
            val providerMeta = (0 until receiver.getElementsByTagName("meta-data").length)
                .mapNotNull { receiver.getElementsByTagName("meta-data").item(it) as? Element }
                .firstOrNull { it.androidAttr("name") == "android.appwidget.provider" }
            assertEquals(providerRes, providerMeta?.androidAttr("resource"))
        }

        val shortcutsDoc = parseAppXml("src/main/res/xml/shortcuts.xml")
        val shortcuts = elementsByTag(shortcutsDoc, "shortcut")
        assertEquals(3, shortcuts.size)
        val byId = shortcuts.associateBy { it.androidAttr("shortcutId") }
        mapOf(
            "shortcut_check_in" to ShortcutIntentParser.ACTION_SHORTCUT_CHECK_IN,
            "shortcut_lite_ssh" to ShortcutIntentParser.ACTION_SHORTCUT_OPEN_SSH,
            "shortcut_copy_otp" to ShortcutIntentParser.ACTION_SHORTCUT_COPY_OTP,
        ).forEach { (id, action) ->
            val el = byId.getValue(id)
            assertEquals("true", el.androidAttr("enabled"))
            val intentEl = el.getElementsByTagName("intent").item(0) as? Element
            assertNotNull(intentEl)
            assertEquals(action, intentEl!!.androidAttr("action"))
            assertEquals("com.nodeseek.app", intentEl.androidAttr("targetPackage"))
            assertEquals("com.nodeseek.app.MainActivity", intentEl.androidAttr("targetClass"))
        }

        listOf(
            "src/main/res/xml/widget_server_status_info.xml",
            "src/main/res/xml/widget_daily_pulse_info.xml",
        ).forEach { xmlPath ->
            val doc = parseAppXml(xmlPath)
            assertEquals("appwidget-provider", doc.documentElement.tagName)
            assertEquals("home_screen", doc.documentElement.androidAttr("widgetCategory"))
            assertTrue((doc.documentElement.androidAttr("updatePeriodMillis").toLongOrNull() ?: 0L) >= 1_800_000L)
        }

        val stringsDoc = parseAppXml("src/main/res/values/strings.xml")
        val stringNames = elementsByTag(stringsDoc, "string").map { it.getAttribute("name") }.toSet()
        listOf(
            "shortcut_check_in_short",
            "shortcut_check_in_long",
            "shortcut_lite_ssh_short",
            "shortcut_lite_ssh_long",
            "shortcut_copy_otp_short",
            "shortcut_copy_otp_long",
            "widget_server_status_name",
            "widget_server_status_description",
            "widget_daily_pulse_name",
            "widget_daily_pulse_description",
        ).forEach { key ->
            assertTrue("Missing string resource $key", key in stringNames)
        }

        val proguardText = File(appModuleDir, "proguard-rules.pro").readText()
        assertTrue(proguardText.contains("androidx.glance.appwidget.action.ActionCallback"))

        // Public zero-arg instantiation required by Android AppWidgetProvider & Glance ActionCallback
        assertNotNull(ServerStatusGlanceWidgetReceiver().glanceAppWidget)
        assertNotNull(DailyPulseGlanceWidgetReceiver().glanceAppWidget)
        assertNotNull(RefreshServerStatusActionCallback())
        assertNotNull(CheckInActionCallback())
    }

    @Test
    fun `E2E-T2-R12-1 cold DataStore awaitLoadedSettings suspends on delayed hydration and completes safely on IOException`() = runBlocking {
        E2eTestHarness().use { harness ->
            val dsFile = File(harness.tempDir, "delayed_r12_settings.preferences_pb")
            val seedDataStore = PreferenceDataStoreFactory.create(
                scope = harness.dataStoreScope,
                produceFile = { dsFile },
            )
            val seedRepo = SettingsRepositoryImpl(
                dataStore = seedDataStore,
                externalScope = harness.dataStoreScope,
                secretCipher = PassThroughSettingsCipher(),
            )
            val persistedKey = "sk_live_" + "c".repeat(32)
            seedRepo.setSeekMailApiKey(persistedKey)
            seedRepo.setPreferRandomCheckIn(true)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    seedRepo.settings.first { it.seekMailApiKey == persistedKey && it.preferRandomCheckIn }
                }
            }

            val delayedDataStore = object : DataStore<Preferences> {
                override val data: Flow<Preferences> = seedDataStore.data.onStart {
                    delay(60L)
                }

                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                    seedDataStore.updateData(transform)
            }
            val coldSettingsRepo = SettingsRepositoryImpl(
                dataStore = delayedDataStore,
                externalScope = harness.dataStoreScope,
                secretCipher = PassThroughSettingsCipher(),
            )
            val coldMailRepo = SeekMailRepositoryImpl(
                apiClient = SeekMailApiClient(baseUrl = harness.externalServer.url("/openapi/v1")),
                settingsRepository = coldSettingsRepo,
                diskCache = harness.seekMailDiskCache,
                externalScope = harness.backgroundScope,
            )
            harness.seekMailDiskCache.saveDefaultMailsPage(
                apiKey = persistedKey,
                mailboxId = null,
                page = SeekMailPage(
                    items = listOf(
                        SeekMailSummary(
                            id = 55L,
                            mailboxId = 1L,
                            fromAddress = "auth@cold.test",
                            fromName = "ColdAuth",
                            toAddress = "box@seek.li",
                            subject = "Verification code 918273",
                            snippet = "Code: 918273",
                            verificationCode = "918273",
                            receivedAt = "2026-11-26T10:00:00Z",
                        ),
                    ),
                    limit = 20,
                    offset = 0,
                    total = 1,
                    hasMore = false,
                ),
            )
            val coldWidgetRepo = WidgetSnapshotRepositoryImpl(
                fleetRepository = harness.fleetRepository,
                sessionRepository = harness.sessionRepository,
                checkInRepository = harness.checkInRepository,
                settingsRepository = coldSettingsRepo,
                radarRepository = harness.radarRepository,
                seekMailRepository = coldMailRepo,
                todayProvider = harness.clock::todayShanghai,
            )

            // Before hydration completes, settings.value is still default empty
            assertEquals("", coldSettingsRepo.settings.value.seekMailApiKey)
            assertFalse(coldSettingsRepo.settings.value.preferRandomCheckIn)

            val pulse = coldWidgetRepo.loadDailyPulseSnapshot()
            assertTrue(pulse.preferRandomCheckIn)
            assertTrue(pulse.hasSeekMailKey)
            assertEquals("918273", pulse.latestOtpCode)
            assertEquals(persistedKey, coldSettingsRepo.settings.value.seekMailApiKey)

            // Failing DataStore IOException resolves cleanly to AppSettings() without hanging
            val brokenDataStore = object : DataStore<Preferences> {
                override val data: Flow<Preferences> = flow {
                    throw IOException("Simulated disk corruption")
                }

                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                    throw IOException("Simulated disk corruption")
            }
            val brokenRepo = SettingsRepositoryImpl(
                dataStore = brokenDataStore,
                externalScope = harness.dataStoreScope,
                secretCipher = PassThroughSettingsCipher(),
            )
            assertEquals(AppSettings(), brokenRepo.awaitLoadedSettings())
        }
    }

    @Test
    fun `E2E-T2-R12-2 SeekMailRepository getCachedLatestOtp isolates keys handles deletions non-OTP mails and detail fallback`() = runBlocking {
        E2eTestHarness().use { harness ->
            val keyA = "sk_live_" + "d".repeat(32)
            val keyB = "sk_live_" + "e".repeat(32)

            harness.settingsRepository.setSeekMailApiKey(keyA)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first { it.seekMailApiKey == keyA }
                }
            }
            assertNull(harness.seekMailRepository.getCachedLatestOtp())

            val oldOtpSummary = SeekMailSummary(
                id = 101L,
                mailboxId = 1L,
                fromAddress = "old@example.com",
                fromName = "OldService",
                toAddress = "box@seek.li",
                subject = "Old OTP",
                snippet = "Code 111222",
                verificationCode = "111222",
                receivedAt = "2026-11-25T08:00:00Z",
            )
            val detailOnlyOtpSummary = SeekMailSummary(
                id = 102L,
                mailboxId = 1L,
                fromAddress = "detail@example.com",
                fromName = "DetailAuth",
                toAddress = "box@seek.li",
                subject = "Login security alert",
                snippet = "Please read full email",
                verificationCode = null,
                receivedAt = "2026-11-26T08:00:00Z",
            )
            val newerNonOtpSummary = SeekMailSummary(
                id = 103L,
                mailboxId = 1L,
                fromAddress = "news@example.com",
                fromName = "Newsletter",
                toAddress = "box@seek.li",
                subject = "Weekly updates",
                snippet = "Enjoy our latest changelog",
                verificationCode = null,
                receivedAt = "2026-11-26T09:00:00Z",
            )

            harness.seekMailDiskCache.saveDefaultMailsPage(
                apiKey = keyA,
                mailboxId = null,
                page = SeekMailPage(
                    items = listOf(oldOtpSummary, detailOnlyOtpSummary, newerNonOtpSummary),
                    limit = 20,
                    offset = 0,
                    total = 3,
                    hasMore = false,
                ),
                fetchedAtEpochMillis = 1_000L,
            )
            harness.seekMailDiskCache.saveMailDetail(
                apiKey = keyA,
                mailId = 102L,
                detail = com.nodeseek.app.domain.mail.SeekMailDetail(
                    summary = detailOnlyOtpSummary,
                    bodyText = "您的验证码是 778-990，请在 5 分钟内输入。",
                ),
            )

            // Skips newer non-OTP (103), falls back to detail for 102
            val firstResolved = harness.seekMailRepository.getCachedLatestOtp()
            assertNotNull(firstResolved)
            assertEquals(102L, firstResolved!!.mailId)
            assertEquals("778990", firstResolved.code)

            // Newer per-mailbox mb_7 page beats older mb_0 page
            harness.seekMailDiskCache.saveDefaultMailsPage(
                apiKey = keyA,
                mailboxId = 7L,
                page = SeekMailPage(
                    items = listOf(
                        SeekMailSummary(
                            id = 205L,
                            mailboxId = 7L,
                            fromAddress = "mb7@example.com",
                            fromName = "Mailbox7Auth",
                            toAddress = "mb7@seek.li",
                            subject = "验证码 654321",
                            snippet = "您的验证码是 654321",
                            verificationCode = null,
                            receivedAt = "2026-11-26T10:00:00Z",
                        ),
                    ),
                    limit = 20,
                    offset = 0,
                    total = 1,
                    hasMore = false,
                ),
                fetchedAtEpochMillis = 2_000L,
            )
            val fromMb7 = harness.seekMailRepository.getCachedLatestOtp()
            assertEquals(205L, fromMb7?.mailId)
            assertEquals("654321", fromMb7?.code)

            // Local delete falls back cleanly to 102L
            harness.seekMailRepository.deleteMailLocally(205L)
            val afterDelete = harness.seekMailRepository.getCachedLatestOtp()
            assertEquals(102L, afterDelete?.mailId)
            assertEquals("778990", afterDelete?.code)

            // Switching to keyB isolates cache completely
            harness.settingsRepository.setSeekMailApiKey(keyB)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first { it.seekMailApiKey == keyB }
                }
            }
            assertNull(harness.seekMailRepository.getCachedLatestOtp())
        }
    }

    @Test
    fun `E2E-T2-R12-3 WidgetSnapshotAggregator buildServerStatus handles empty fleet offline fallback missing limit over-100 clamp and blank VPS names`() {
        val today = java.time.LocalDate.of(2026, 11, 26)
        val gib = 1024L * 1024L * 1024L

        // 1. Empty fleet
        val empty = WidgetSnapshotAggregator.buildServerStatus(
            assets = emptyList(),
            snapshots = emptyList(),
            today = today,
        )
        assertFalse(empty.hasProbes)
        assertFalse(empty.hasNearestVps)
        assertNull(empty.primaryServerName)
        assertNull(empty.nearestVpsName)

        // 2. Only DOMAIN_CERT assets -> hasNearestVps == false
        val domainOnly = WidgetSnapshotAggregator.buildServerStatus(
            assets = listOf(
                VpsAssetRecord(
                    id = "d1",
                    nickname = "only-domain.com",
                    providerName = "CF",
                    specsSummary = "Domain",
                    region = "Global",
                    renewalAmount = "10",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = today.plusDays(2).toString(),
                    assetType = FleetAssetType.DOMAIN_CERT,
                ),
            ),
            snapshots = emptyList(),
            today = today,
        )
        assertFalse(domainOnly.hasNearestVps)

        // 3. All offline + missing traffic limit + blank VPS nickname falls back to providerName -> "VPS #id" -> "VPS"
        val offlineNoLimit = WidgetSnapshotAggregator.buildServerStatus(
            assets = listOf(
                VpsAssetRecord(
                    id = "42",
                    nickname = "   ",
                    providerName = "",
                    specsSummary = "1C",
                    region = "US",
                    renewalAmount = "10",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = today.plusDays(3).toString(),
                ),
            ),
            snapshots = listOf(
                ProbeServerSnapshot(
                    panelId = "p1",
                    remoteServerId = "9",
                    name = "OfflineNode",
                    isOnline = false,
                    monthlyTransferOutBytes = 5L * gib,
                    trafficLimitBytes = null,
                ),
            ),
            today = today,
        )
        assertEquals("OfflineNode", offlineNoLimit.primaryServerName)
        assertNull(offlineNoLimit.primaryTrafficLimitBytes)
        assertNull(offlineNoLimit.primaryTrafficRatio)
        assertEquals("VPS #42", offlineNoLimit.nearestVpsName)

        // Blank id + blank provider + blank nickname -> "VPS"; providerName fallback when non-blank
        val blankProviderFallback = WidgetSnapshotAggregator.buildServerStatus(
            assets = listOf(
                VpsAssetRecord(
                    id = "id-99",
                    nickname = "",
                    providerName = "RackNerd",
                    specsSummary = "1C",
                    region = "US",
                    renewalAmount = "10",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = today.plusDays(3).toString(),
                ),
            ),
            snapshots = emptyList(),
            today = today,
        )
        assertEquals("RackNerd", blankProviderFallback.nearestVpsName)

        val blankIdFallback = WidgetSnapshotAggregator.buildServerStatus(
            assets = listOf(
                VpsAssetRecord(
                    id = "   ",
                    nickname = "",
                    providerName = "",
                    specsSummary = "1C",
                    region = "US",
                    renewalAmount = "10",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = today.plusDays(3).toString(),
                ),
            ),
            snapshots = emptyList(),
            today = today,
        )
        assertEquals("VPS", blankIdFallback.nearestVpsName)

        // 4. Over-100% traffic clamps primaryTrafficRatio to 1.0f
        val overQuota = WidgetSnapshotAggregator.buildServerStatus(
            assets = emptyList(),
            snapshots = listOf(
                ProbeServerSnapshot(
                    panelId = "p1",
                    remoteServerId = "1",
                    name = "OverQuota",
                    isOnline = true,
                    monthlyTransferInBytes = 80L * gib,
                    monthlyTransferOutBytes = 70L * gib,
                    trafficLimitBytes = 100L * gib,
                ),
            ),
            today = today,
        )
        assertEquals(1.0f, overQuota.primaryTrafficRatio!!, 0.0001f)
    }

    @Test
    fun `E2E-T2-R12-4 fetchOrCachedLatestOtp skips network without API key filters deleted OTP and signed-out DailyPulse strips check-in info`() = runBlocking {
        E2eTestHarness().use { harness ->
            // 1. Blank Seek Mail API key -> 0 network requests
            assertNull(harness.widgetSnapshotRepository.fetchOrCachedLatestOtp())
            assertTrue(harness.externalDispatcher.recordedRequests().isEmpty())

            // 2. Signed-out DailyPulse strips todayGain / todayOrder and forces hasCheckedInToday = false
            val signedOutPulse = WidgetSnapshotAggregator.buildDailyPulse(
                session = null,
                settings = AppSettings(preferRandomCheckIn = true),
                checkInStatus = CheckInStatus(
                    hasCheckedInToday = true,
                    todayGain = 15,
                    todayOrder = 12,
                    totalCheckedInUsers = 500,
                    leaderboard = emptyList(),
                    hasNextPage = false,
                ),
                radarPollingSettings = RadarPollingSettings(isEnabled = false),
                unreadAlertCount = -3,
                latestAlert = null,
                latestOtp = LatestSeekMailOtp(
                    mailId = 1L,
                    code = "  ",
                    sender = " ",
                    subject = "",
                    receivedAt = "",
                ),
            )
            assertFalse(signedOutPulse.isSignedIn)
            assertFalse(signedOutPulse.hasCheckedInToday)
            assertNull(signedOutPulse.todayGain)
            assertNull(signedOutPulse.todayOrder)
            assertEquals(0, signedOutPulse.unreadAlertCount)
            assertNull(signedOutPulse.latestOtpCode)

            // 3. Locally deleted fresh OTP is excluded by fetchOrCachedLatestOtp()
            val seekMailKey = "sk_live_" + "f".repeat(32)
            harness.settingsRepository.setSeekMailApiKey(seekMailKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first { it.seekMailApiKey == seekMailKey }
                }
            }
            val watchPages = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("mail/seekmail_otp_watch_pages.json"),
            ).jsonObject
            harness.externalDispatcher.register("GET", "/openapi/v1/mails") {
                MockResponse().setResponseCode(200).setBody(watchPages.getValue("baselineMailsPage").toString())
            }
            harness.seekMailRepository.deleteMailLocally(300L)
            assertNull(harness.widgetSnapshotRepository.fetchOrCachedLatestOtp())
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T2-R12-5 ShortcutIntentParser rejects null blank and unknown intent actions`() {
        listOf(
            null,
            "",
            "   ",
            "android.intent.action.MAIN",
            "com.nodeseek.app.action.UNKNOWN",
            "com.example.OTHER_ACTION",
        ).forEach { invalid ->
            assertNull("Expected null command for $invalid", ShortcutIntentParser.parseAction(invalid))
            assertFalse("Expected isKnownAction == false for $invalid", ShortcutIntentParser.isKnownAction(invalid))
        }
    }
}
