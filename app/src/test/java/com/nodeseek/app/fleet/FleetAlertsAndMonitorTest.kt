package com.nodeseek.app.fleet

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.nodeseek.app.data.fleet.FleetAlertNotifier
import com.nodeseek.app.data.fleet.FleetAlertSink
import com.nodeseek.app.data.fleet.FleetMonitorRunner
import com.nodeseek.app.data.fleet.FleetMonitorWorker
import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.fleet.FleetWorkManagerGateway
import com.nodeseek.app.data.fleet.FleetWorkScheduler
import com.nodeseek.app.data.fleet.ProbePanelClient
import com.nodeseek.app.data.fleet.SoftwareAesGcmTokenCipher
import com.nodeseek.app.domain.fleet.FleetAlertEvaluator
import com.nodeseek.app.domain.fleet.FleetAlertTrackerState
import com.nodeseek.app.domain.fleet.FleetAssetType
import com.nodeseek.app.domain.fleet.FleetMonitorCheckReport
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class FleetAlertsAndMonitorTest {

    private lateinit var tempDir: File
    private lateinit var server: MockWebServer

    private class RecordingAlertSink : FleetAlertSink {
        val offlineEvents = mutableListOf<Pair<String, ProbeServerSnapshot>>()
        val recoveryEvents = mutableListOf<Pair<String, ProbeServerSnapshot>>()
        val renewalEvents = mutableListOf<Triple<VpsAssetRecord, Long, BigDecimal>>()

        override fun ensureChannels() = Unit

        override fun notifyServerOffline(panelName: String, server: ProbeServerSnapshot) {
            offlineEvents += panelName to server
        }

        override fun notifyServerRecovered(panelName: String, server: ProbeServerSnapshot) {
            recoveryEvents += panelName to server
        }

        override fun notifyRenewalReminder(asset: VpsAssetRecord, daysRemaining: Long, renewalCny: BigDecimal) {
            renewalEvents += Triple(asset, daysRemaining, renewalCny)
        }
    }

    private class RecordingWorkGateway : FleetWorkManagerGateway {
        val enqueuedPeriodic = mutableListOf<Triple<String, ExistingPeriodicWorkPolicy, PeriodicWorkRequest>>()
        val enqueuedOneTime = mutableListOf<Triple<String, ExistingWorkPolicy, OneTimeWorkRequest>>()
        val cancelled = mutableListOf<String>()

        override fun enqueueUniquePeriodicWork(
            uniqueWorkName: String,
            existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
            request: PeriodicWorkRequest,
        ) {
            enqueuedPeriodic += Triple(uniqueWorkName, existingPeriodicWorkPolicy, request)
        }

        override fun enqueueUniqueOneTimeWork(
            uniqueWorkName: String,
            existingWorkPolicy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        ) {
            enqueuedOneTime += Triple(uniqueWorkName, existingWorkPolicy, request)
        }

        override fun cancelUniqueWork(uniqueWorkName: String) {
            cancelled += uniqueWorkName
        }
    }

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("fleet_alerts_test").toFile()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun `offline alert triggers once per outage, deduplicates while offline, and re-arms after recovery`() {
        val panel = ProbePanelConfig(
            id = "p1",
            name = "主力哪吒",
            baseUrl = "https://probe.example.com",
            panelType = ProbePanelType.NEZHA_V1,
            notifyOnOffline = true,
            notifyOnRecovery = true,
        )
        val onlineSnap = ProbeServerSnapshot(
            panelId = "p1",
            remoteServerId = "1",
            name = "JP-Tokyo",
            isOnline = true,
        )
        val offlineSnap = onlineSnap.copy(isOnline = false)
        val today = LocalDate.of(2026, 11, 19)
        val settings = FleetMonitorSettings(notifyOnRecovery = false)

        // Pass 1: Baseline ONLINE -> no alert
        val step1 = FleetAlertEvaluator.evaluate(
            previousTracker = FleetAlertTrackerState(),
            panels = listOf(panel),
            snapshots = listOf(onlineSnap),
            assets = emptyList(),
            monitorSettings = settings,
            today = today,
            nowEpochMillis = 1000L,
        )
        assertTrue(step1.report.offlineAlerts.isEmpty())
        assertTrue(step1.report.recoveryAlerts.isEmpty())

        // Pass 2: ONLINE -> OFFLINE -> triggers 1 offline alert
        val step2 = FleetAlertEvaluator.evaluate(
            previousTracker = step1.updatedTracker,
            panels = listOf(panel),
            snapshots = listOf(offlineSnap),
            assets = emptyList(),
            monitorSettings = settings,
            today = today,
            nowEpochMillis = 2000L,
        )
        assertEquals(1, step2.report.offlineAlerts.size)
        assertEquals("JP-Tokyo", step2.report.offlineAlerts.first().server.name)

        // Pass 3: OFFLINE -> OFFLINE (still down) -> deduplicated, 0 alerts
        val step3 = FleetAlertEvaluator.evaluate(
            previousTracker = step2.updatedTracker,
            panels = listOf(panel),
            snapshots = listOf(offlineSnap),
            assets = emptyList(),
            monitorSettings = settings,
            today = today,
            nowEpochMillis = 3000L,
        )
        assertTrue(step3.report.offlineAlerts.isEmpty())
        assertTrue(step3.report.recoveryAlerts.isEmpty())

        // Pass 4: OFFLINE -> ONLINE -> emits recovery alert & re-arms outage tracker
        val step4 = FleetAlertEvaluator.evaluate(
            previousTracker = step3.updatedTracker,
            panels = listOf(panel),
            snapshots = listOf(onlineSnap),
            assets = emptyList(),
            monitorSettings = settings,
            today = today,
            nowEpochMillis = 4000L,
        )
        assertTrue(step4.report.offlineAlerts.isEmpty())
        assertEquals(1, step4.report.recoveryAlerts.size)

        // Pass 5: ONLINE -> OFFLINE again (new outage) -> fires fresh alert!
        val step5 = FleetAlertEvaluator.evaluate(
            previousTracker = step4.updatedTracker,
            panels = listOf(panel),
            snapshots = listOf(offlineSnap),
            assets = emptyList(),
            monitorSettings = settings,
            today = today,
            nowEpochMillis = 5000L,
        )
        assertEquals(1, step5.report.offlineAlerts.size)
    }

    @Test
    fun `renewal reminders fire inside reminder window, deduplicate per cycle, and re-arm on next cycle`() {
        val today = LocalDate.of(2026, 11, 19)
        val dueSoonAsset = VpsAssetRecord(
            id = "a1",
            nickname = "DMIT LAX",
            providerName = "DMIT",
            specsSummary = "1C / 1G",
            region = "洛杉矶",
            renewalAmount = "49.90",
            currency = ValuationCurrency.USD,
            cyclePreset = BillingCyclePreset.ANNUAL,
            expiryDateIso = "2026-11-24", // 5 days away <= 7
            remindDaysBeforeExpiry = 7,
        )
        val farAsset = VpsAssetRecord(
            id = "a2",
            nickname = "RackNerd DC02",
            providerName = "RackNerd",
            specsSummary = "2C / 2G",
            region = "洛杉矶",
            renewalAmount = "10.98",
            currency = ValuationCurrency.USD,
            cyclePreset = BillingCyclePreset.ANNUAL,
            expiryDateIso = "2026-12-20", // 31 days away > 7
            remindDaysBeforeExpiry = 7,
        )
        val mutedAsset = dueSoonAsset.copy(
            id = "a3",
            nickname = "Muted Box",
            remindDaysBeforeExpiry = 0,
        )

        // First evaluation: only a1 fires
        val pass1 = FleetAlertEvaluator.evaluate(
            previousTracker = FleetAlertTrackerState(),
            panels = emptyList(),
            snapshots = emptyList(),
            assets = listOf(dueSoonAsset, farAsset, mutedAsset),
            monitorSettings = FleetMonitorSettings(),
            today = today,
            nowEpochMillis = 10_000L,
        )
        assertEquals(1, pass1.report.renewalReminders.size)
        assertEquals("a1", pass1.report.renewalReminders.first().asset.id)
        assertEquals(5L, pass1.report.renewalReminders.first().daysRemaining)

        // Second evaluation on next day (still same cycle expiryDateIso): deduplicated!
        val pass2 = FleetAlertEvaluator.evaluate(
            previousTracker = pass1.updatedTracker,
            panels = emptyList(),
            snapshots = emptyList(),
            assets = listOf(dueSoonAsset, farAsset, mutedAsset),
            monitorSettings = FleetMonitorSettings(),
            today = today.plusDays(1),
            nowEpochMillis = 20_000L,
        )
        assertTrue(pass2.report.renewalReminders.isEmpty())

        // User renews machine for next year -> next cycle expiryDateIso approaches -> re-arms!
        val renewedAsset = dueSoonAsset.copy(expiryDateIso = "2027-11-24")
        val pass3 = FleetAlertEvaluator.evaluate(
            previousTracker = pass2.updatedTracker,
            panels = emptyList(),
            snapshots = emptyList(),
            assets = listOf(renewedAsset),
            monitorSettings = FleetMonitorSettings(),
            today = LocalDate.of(2027, 11, 20),
            nowEpochMillis = 30_000L,
        )
        assertEquals(1, pass3.report.renewalReminders.size)
        assertEquals(4L, pass3.report.renewalReminders.first().daysRemaining)
    }

    @Test
    fun `FleetRepositoryImpl encrypts API token at rest in fleet_store json and reloads cleanly`() = runTest {
        val storeFile = File(tempDir, "fleet_store.json")
        val cipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
        val secretToken = "super-sensitive-nezha-api-token-987654"

        val repo1 = FleetRepositoryImpl(
            storeFile = storeFile,
            tokenCipher = cipher,
        )
        repo1.upsertPanel(
            ProbePanelConfig(
                id = "enc-panel",
                name = "Encrypted Panel",
                baseUrl = "https://probe.example.com",
                panelType = ProbePanelType.NEZHA_V1,
                apiToken = secretToken,
            ),
        )

        assertTrue(storeFile.exists())
        val rawDiskJson = storeFile.readText(Charsets.UTF_8)
        assertFalse("Plaintext token must NEVER appear on disk", rawDiskJson.contains(secretToken))
        assertTrue("Encrypted AES-GCM prefix must be persisted", rawDiskJson.contains(SoftwareAesGcmTokenCipher.PREFIX_SW))

        // Reload in a brand-new repository instance using same cipher
        val repo2 = FleetRepositoryImpl(
            storeFile = storeFile,
            tokenCipher = cipher,
        )
        repo2.loadPersistedState()
        assertEquals(1, repo2.panelsFlow.value.size)
        assertEquals(secretToken, repo2.panelsFlow.value.first().apiToken)
    }

    @Test
    fun `FleetMonitorRunner detects live MockWebServer outage and dispatches through FleetAlertSink`() = runTest {
        val isNodeOnline = AtomicBoolean(true)
        val nowMillis = 1_763_560_845_000L
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/v1/server") {
                    val lastActive = if (isNodeOnline.get()) "2025-11-19T14:00:40Z" else "2025-11-19T13:40:00Z"
                    return MockResponse().setResponseCode(200).setBody(
                        """{"success":true,"data":[{"id":1,"name":"HK-Prod","last_active":"$lastActive","host":{"mem_total":1024},"state":{"cpu":12.0,"mem_used":256,"uptime":1000}}]}""",
                    )
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val storeFile = File(tempDir, "runner_fleet_store.json")
        val cipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
        val client = ProbePanelClient(nowMillisProvider = { nowMillis })
        val repo = FleetRepositoryImpl(storeFile = storeFile, probeClient = client, tokenCipher = cipher)
        val sink = RecordingAlertSink()

        repo.upsertPanel(
            ProbePanelConfig(
                id = "p-live",
                name = "Prod Panel",
                baseUrl = server.url("/").toString(),
                panelType = ProbePanelType.NEZHA_V1,
                notifyOnOffline = true,
            ),
        )
        repo.upsertAsset(
            VpsAssetRecord(
                id = "a-due",
                nickname = "HK-Prod-Asset",
                providerName = "DMIT",
                specsSummary = "1C / 1G",
                region = "香港",
                renewalAmount = "100",
                currency = ValuationCurrency.CNY,
                cyclePreset = BillingCyclePreset.ANNUAL,
                expiryDateIso = "2026-11-22",
                remindDaysBeforeExpiry = 5,
            ),
        )

        val runner = FleetMonitorRunner(
            fleetRepository = repo,
            alertSink = sink,
            todayProvider = { LocalDate.of(2026, 11, 19) },
            nowMillisProvider = { nowMillis },
        )

        // Pass 1: Node is online -> baseline recorded, renewal reminder fires once
        runner.runOnce()
        assertEquals(0, sink.offlineEvents.size)
        assertEquals(1, sink.renewalEvents.size)

        // Pass 2: Node goes offline -> offline alert fires once, renewal reminder deduplicated
        isNodeOnline.set(false)
        runner.runOnce()
        assertEquals(1, sink.offlineEvents.size)
        assertEquals("HK-Prod", sink.offlineEvents.first().second.name)
        assertEquals(1, sink.renewalEvents.size)

        // Pass 3: Still offline -> both offline and renewal alerts deduplicated
        runner.runOnce()
        assertEquals(1, sink.offlineEvents.size)
        assertEquals(1, sink.renewalEvents.size)
    }

    @Test
    fun `foreground refresh seeds online baseline so first background outage still alerts`() = runTest {
        val isNodeOnline = AtomicBoolean(true)
        val nowMillis = 1_763_560_845_000L
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/v1/server") {
                    val lastActive = if (isNodeOnline.get()) "2025-11-19T14:00:40Z" else "2025-11-19T13:30:00Z"
                    return MockResponse().setResponseCode(200).setBody(
                        """{"success":true,"data":[{"id":5,"name":"SG-Edge","last_active":"$lastActive","host":{"mem_total":1024},"state":{"cpu":5.0,"mem_used":128,"uptime":100}}]}""",
                    )
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val repo = FleetRepositoryImpl(
            storeFile = File(tempDir, "fg_seed_store.json"),
            probeClient = ProbePanelClient(nowMillisProvider = { nowMillis }),
            tokenCipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey()),
        )
        repo.upsertPanel(
            ProbePanelConfig(
                id = "p-fg",
                name = "FG Panel",
                baseUrl = server.url("/").toString(),
                panelType = ProbePanelType.NEZHA_V1,
                notifyOnOffline = true,
            ),
        )

        // Foreground refresh sees node online
        repo.refreshAllPanels()

        // Node goes offline before the very first background worker run
        isNodeOnline.set(false)
        val sink = RecordingAlertSink()
        FleetMonitorRunner(
            fleetRepository = repo,
            alertSink = sink,
            todayProvider = { LocalDate.of(2026, 11, 19) },
            nowMillisProvider = { nowMillis },
        ).runOnce()

        assertEquals(1, sink.offlineEvents.size)
        assertEquals("SG-Edge", sink.offlineEvents.first().second.name)
    }

    @SuppressLint("RestrictedApi")
    @Test
    fun `FleetWorkScheduler enforces 15-minute floor, maps constraints, and cancels when disabled`() {
        val gateway = RecordingWorkGateway()
        val scheduler = FleetWorkScheduler(gateway)

        val settings = FleetMonitorSettings(
            backgroundMonitorEnabled = true,
            pollIntervalMinutes = 5, // should clamp to 15
            wifiOnlyInBackground = true,
            requireBatteryNotLowInBackground = true,
        )
        scheduler.sync(settings, hasActiveTargets = true)

        assertEquals(1, gateway.enqueuedPeriodic.size)
        val (name, policy, req) = gateway.enqueuedPeriodic.first()
        assertEquals(FleetWorkScheduler.UNIQUE_PERIODIC_WORK_NAME, name)
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, policy)
        assertEquals(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS, req.workSpec.intervalDuration)
        assertEquals(NetworkType.UNMETERED, req.workSpec.constraints.requiredNetworkType)
        assertTrue(req.workSpec.constraints.requiresBatteryNotLow())

        scheduler.sync(settings.copy(backgroundMonitorEnabled = false), hasActiveTargets = true)
        assertEquals(listOf(FleetWorkScheduler.UNIQUE_PERIODIC_WORK_NAME), gateway.cancelled)
    }

    @Test
    fun `FleetMonitorWorker returns success on clean run and retry when all panels fail`() = runTest {
        val testContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }

        val okWorker = TestListenableWorkerBuilder<FleetMonitorWorker>(testContext)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = FleetMonitorWorker(
                    appContext = appContext,
                    workerParams = workerParameters,
                    customRunner = {
                        FleetMonitorCheckReport(checkedPanels = 2, failedPanels = 0, totalNodes = 3, onlineNodes = 3)
                    },
                )
            })
            .build()
        assertEquals(ListenableWorker.Result.success(), okWorker.doWork())

        val retryWorker = TestListenableWorkerBuilder<FleetMonitorWorker>(testContext)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = FleetMonitorWorker(
                    appContext = appContext,
                    workerParams = workerParameters,
                    customRunner = {
                        FleetMonitorCheckReport(checkedPanels = 2, failedPanels = 2, totalNodes = 0, onlineNodes = 0)
                    },
                )
            })
            .build()
        assertEquals(ListenableWorker.Result.retry(), retryWorker.doWork())
    }

    @Test
    fun `FleetAlertNotifier routes domain renewal reminders to domains tab and uses domain copy`() {
        val vpsAsset = VpsAssetRecord(
            id = "vps_1",
            nickname = "HK Box",
            providerName = "DMIT",
            specsSummary = "1C/1G",
            region = "HK",
            renewalAmount = "10",
            expiryDateIso = "2026-11-20",
            assetType = FleetAssetType.VPS,
        )
        val domainAsset = VpsAssetRecord(
            id = "dom_1",
            nickname = "0ne.icu",
            providerName = "Spaceship, Inc.",
            specsSummary = "",
            region = "",
            renewalAmount = "10",
            expiryDateIso = "2026-11-20",
            assetType = FleetAssetType.DOMAIN_CERT,
            domainName = "0ne.icu",
        )

        assertEquals(FleetAlertNotifier.EXTRA_TAB_ASSETS, FleetAlertNotifier.resolveRenewalTargetTab(vpsAsset))
        assertEquals(FleetAlertNotifier.EXTRA_TAB_DOMAINS, FleetAlertNotifier.resolveRenewalTargetTab(domainAsset))

        val (_, _, vpsBody) = FleetAlertNotifier.formatRenewalReminderCopy(vpsAsset, daysRemaining = -1L, renewalCny = BigDecimal("70.00"))
        val (_, _, domBody) = FleetAlertNotifier.formatRenewalReminderCopy(domainAsset, daysRemaining = -1L, renewalCny = BigDecimal("70.00"))
        assertTrue(vpsBody.contains("进入资产账本"))
        assertTrue(domBody.contains("进入域名资产"))
    }
}
