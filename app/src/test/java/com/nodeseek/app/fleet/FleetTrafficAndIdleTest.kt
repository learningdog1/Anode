package com.nodeseek.app.fleet

import com.nodeseek.app.data.fleet.FleetAlertNotifier
import com.nodeseek.app.data.fleet.FleetAlertSink
import com.nodeseek.app.data.fleet.FleetMonitorRunner
import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.fleet.ProbePanelClient
import com.nodeseek.app.data.fleet.SoftwareAesGcmTokenCipher
import com.nodeseek.app.data.valuation.CurrencyRateQuote
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.domain.fleet.FleetAlertEvaluator
import com.nodeseek.app.domain.fleet.FleetAlertTrackerState
import com.nodeseek.app.domain.fleet.FleetAlertTransition
import com.nodeseek.app.domain.fleet.FleetIdleThresholdConfig
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.FleetSalePostOutcome
import com.nodeseek.app.domain.fleet.FleetTrafficAndIdleAnalyzer
import com.nodeseek.app.domain.fleet.IdleClassificationStatus
import com.nodeseek.app.domain.fleet.ProbeDailyCpuBucket
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.TrafficResetDaySource
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.FxRateSource
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.ui.fleet.MyServersTab
import com.nodeseek.app.ui.fleet.MyServersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class FleetTrafficAndIdleTest {

    private lateinit var tempDir: File
    private lateinit var cipher: SoftwareAesGcmTokenCipher
    private val testDispatcher = StandardTestDispatcher()

    private class TrafficRecordingAlertSink : FleetAlertSink {
        val offlineEvents = mutableListOf<Pair<String, ProbeServerSnapshot>>()
        val recoveryEvents = mutableListOf<Pair<String, ProbeServerSnapshot>>()
        val renewalEvents = mutableListOf<Triple<VpsAssetRecord, Long, BigDecimal>>()
        val trafficEvents = mutableListOf<FleetAlertTransition.TrafficThresholdReached>()

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

        override fun notifyTrafficWarning(alert: FleetAlertTransition.TrafficThresholdReached) {
            trafficEvents += alert
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempDir = Files.createTempDirectory("fleet_traffic_idle_test").toFile()
        cipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    @Test
    fun `reset day clamps safely to shorter months and resolves fallback hierarchy`() {
        // 1. Month-end clamping for reset day 31 across non-leap Feb, leap Feb, 30-day Apr, and 31-day Mar
        assertEquals(
            LocalDate.of(2026, 2, 28),
            FleetTrafficAndIdleAnalyzer.clampResetDateInMonth(YearMonth.of(2026, 2), 31),
        )
        assertEquals(
            LocalDate.of(2028, 2, 29),
            FleetTrafficAndIdleAnalyzer.clampResetDateInMonth(YearMonth.of(2028, 2), 31),
        )
        assertEquals(
            LocalDate.of(2026, 4, 30),
            FleetTrafficAndIdleAnalyzer.clampResetDateInMonth(YearMonth.of(2026, 4), 31),
        )
        assertEquals(
            LocalDate.of(2026, 5, 31),
            FleetTrafficAndIdleAnalyzer.clampResetDateInMonth(YearMonth.of(2026, 5), 31),
        )

        // 2. Cycle bounds remain invariant across all days inside the clamped cycle
        // Non-leap Feb with reset day 31: [2026-01-31, 2026-02-28) then [2026-02-28, 2026-03-31)
        assertEquals(
            LocalDate.of(2026, 1, 31) to LocalDate.of(2026, 2, 28),
            FleetTrafficAndIdleAnalyzer.resolveTrafficCycleBounds(LocalDate.of(2026, 2, 27), 31),
        )
        assertEquals(
            LocalDate.of(2026, 2, 28) to LocalDate.of(2026, 3, 31),
            FleetTrafficAndIdleAnalyzer.resolveTrafficCycleBounds(LocalDate.of(2026, 2, 28), 31),
        )
        assertEquals(
            LocalDate.of(2026, 2, 28) to LocalDate.of(2026, 3, 31),
            FleetTrafficAndIdleAnalyzer.resolveTrafficCycleBounds(LocalDate.of(2026, 3, 15), 31),
        )
        // Leap Feb with reset day 31: [2028-01-31, 2028-02-29) then [2028-02-29, 2028-03-31)
        assertEquals(
            LocalDate.of(2028, 1, 31) to LocalDate.of(2028, 2, 29),
            FleetTrafficAndIdleAnalyzer.resolveTrafficCycleBounds(LocalDate.of(2028, 2, 28), 31),
        )
        assertEquals(
            LocalDate.of(2028, 2, 29) to LocalDate.of(2028, 3, 31),
            FleetTrafficAndIdleAnalyzer.resolveTrafficCycleBounds(LocalDate.of(2028, 2, 29), 31),
        )

        // 3. Reset day fallback priority: explicit -> asset expiry day -> 1st of month
        val explicitAsset = sampleAsset(expiryDateIso = "2027-06-25", trafficResetDay = 12)
        assertEquals(
            12 to TrafficResetDaySource.EXPLICIT_ASSET_CONFIG,
            FleetTrafficAndIdleAnalyzer.resolveTrafficResetDay(explicitAsset),
        )

        val expiryFallbackAsset = sampleAsset(expiryDateIso = "2027-06-25", trafficResetDay = null)
        assertEquals(
            25 to TrafficResetDaySource.ASSET_EXPIRY_DAY_FALLBACK,
            FleetTrafficAndIdleAnalyzer.resolveTrafficResetDay(expiryFallbackAsset),
        )

        val unparsedExpiryAsset = sampleAsset(expiryDateIso = "", trafficResetDay = null)
        assertEquals(
            1 to TrafficResetDaySource.FIRST_OF_MONTH_DEFAULT,
            FleetTrafficAndIdleAnalyzer.resolveTrafficResetDay(unparsedExpiryAsset),
        )
        assertEquals(
            1 to TrafficResetDaySource.FIRST_OF_MONTH_DEFAULT,
            FleetTrafficAndIdleAnalyzer.resolveTrafficResetDay(null),
        )
    }

    @Test
    fun `analyzeTraffic predicts current-month and next-month exhaustion dates and guards micro-elapsed window`() {
        val gib = 1024L * 1024L * 1024L
        val limit1000Gib = 1000L * gib

        // Case 1: Current month exhaustion ("预计本月 20 号流量会用完")
        // Reset day = 1 (cycle 2026-11-01 .. 2026-12-01), evaluated at 2026-11-10 12:00 (9.5 days elapsed),
        // 500 GiB / 1000 GiB used -> remaining 9.5 days -> 2026-11-20 00:00
        val assetReset1 = sampleAsset(trafficResetDay = 1, monthlyTrafficQuotaGb = 1000.0)
        val snapHalfUsed = ProbeServerSnapshot(
            panelId = "p1",
            remoteServerId = "10",
            name = "HK-BGP",
            monthlyTransferInBytes = 200L * gib,
            monthlyTransferOutBytes = 300L * gib,
            trafficLimitBytes = limit1000Gib,
            isOnline = true,
        )
        val forecastSameMonth = FleetTrafficAndIdleAnalyzer.analyzeTraffic(
            snapshot = snapHalfUsed,
            linkedAsset = assetReset1,
            nowEpochMillis = shanghaiEpochMillis(2026, 11, 10, 12, 0),
        )
        assertEquals(0.50, forecastSameMonth.usageRatio!!, 0.001)
        assertTrue(forecastSameMonth.willExhaustBeforeReset)
        assertEquals(LocalDate.of(2026, 11, 20), forecastSameMonth.predictedExhaustionDate)
        assertEquals("预计本月 20 号流量会用完", forecastSameMonth.exhaustionForecastText)
        assertFalse(forecastSameMonth.isAtOrAboveAlertThreshold)

        // Case 2: Billing cycle crosses into next calendar month ("预计下月 10 号流量会用完")
        // Reset day = 20 (cycle 2026-11-20 .. 2026-12-20), evaluated at 2026-11-25 00:00 (5 days elapsed),
        // 250 GiB / 1000 GiB used (50 GiB/day) -> 15 days left -> 2026-12-10
        val assetReset20 = sampleAsset(trafficResetDay = 20, monthlyTrafficQuotaGb = 1000.0)
        val snapCrossMonth = snapHalfUsed.copy(
            monthlyTransferInBytes = 100L * gib,
            monthlyTransferOutBytes = 150L * gib,
        )
        val forecastNextMonth = FleetTrafficAndIdleAnalyzer.analyzeTraffic(
            snapshot = snapCrossMonth,
            linkedAsset = assetReset20,
            nowEpochMillis = shanghaiEpochMillis(2026, 11, 25, 0, 0),
        )
        assertTrue(forecastNextMonth.willExhaustBeforeReset)
        assertEquals(LocalDate.of(2026, 12, 10), forecastNextMonth.predictedExhaustionDate)
        assertEquals("预计下月 10 号流量会用完", forecastNextMonth.exhaustionForecastText)

        // Case 3: Healthy burn rate that will not exhaust before next reset
        val snapHealthy = snapHalfUsed.copy(
            monthlyTransferInBytes = 50L * gib,
            monthlyTransferOutBytes = 50L * gib, // 100 GiB in 15 days -> ~200 GiB total < 1000 GiB
        )
        val forecastHealthy = FleetTrafficAndIdleAnalyzer.analyzeTraffic(
            snapshot = snapHealthy,
            linkedAsset = assetReset1,
            nowEpochMillis = shanghaiEpochMillis(2026, 11, 16, 0, 0),
        )
        assertFalse(forecastHealthy.willExhaustBeforeReset)
        assertNull(forecastHealthy.predictedExhaustionDate)
        assertNull(forecastHealthy.exhaustionForecastText)
        assertEquals(0.20, forecastHealthy.projectedCycleUsageRatio!!, 0.01)

        // Case 4: Noisy micro-window right after reset (15 minutes after midnight on reset day) -> suppressed
        val forecastMicroWindow = FleetTrafficAndIdleAnalyzer.analyzeTraffic(
            snapshot = snapHealthy.copy(monthlyTransferInBytes = 10L * gib, monthlyTransferOutBytes = 10L * gib),
            linkedAsset = sampleAsset(trafficResetDay = 10),
            nowEpochMillis = shanghaiEpochMillis(2026, 11, 10, 0, 15),
        )
        assertFalse(forecastMicroWindow.willExhaustBeforeReset)
        assertNull(forecastMicroWindow.predictedExhaustionDate)
        assertNull(forecastMicroWindow.exhaustionForecastText)

        // Case 5: Already >= 100% exhausted
        val forecastExhausted = FleetTrafficAndIdleAnalyzer.analyzeTraffic(
            snapshot = snapHalfUsed.copy(monthlyTransferInBytes = 600L * gib, monthlyTransferOutBytes = 450L * gib),
            linkedAsset = assetReset1,
            nowEpochMillis = shanghaiEpochMillis(2026, 11, 22, 10, 0),
        )
        assertTrue(forecastExhausted.isExhausted)
        assertTrue(forecastExhausted.isAtOrAboveAlertThreshold)
        assertEquals("本周期流量已用完", forecastExhausted.exhaustionForecastText)
    }

    @Test
    fun `80 percent traffic alert includes exhaustion forecast, deduplicates per cycle, and re-arms on next cycle or reset`() {
        val gib = 1024L * 1024L * 1024L
        val panel = ProbePanelConfig(
            id = "p1",
            name = "主力哪吒",
            baseUrl = "https://probe.example.com",
            panelType = ProbePanelType.NEZHA_V1,
        )
        val asset = sampleAsset(
            id = "asset-jp",
            nickname = "DMIT 东京 Pro",
            linkedProbeKey = "p1:1",
            monthlyTrafficQuotaGb = 1000.0,
            trafficResetDay = 1,
        )
        val baseSnap = ProbeServerSnapshot(
            panelId = "p1",
            remoteServerId = "1",
            name = "JP-TYO",
            countryCode = "JP",
            isOnline = true,
            trafficLimitBytes = 1000L * gib,
        )

        // Pass 1: 75% used on Nov 16 -> below 80%, no alert
        val step1 = FleetAlertEvaluator.evaluate(
            previousTracker = FleetAlertTrackerState(),
            panels = listOf(panel),
            snapshots = listOf(baseSnap.copy(monthlyTransferOutBytes = 750L * gib)),
            assets = listOf(asset),
            monitorSettings = FleetMonitorSettings(),
            today = LocalDate.of(2026, 11, 16),
            nowEpochMillis = shanghaiEpochMillis(2026, 11, 16, 0, 0),
        )
        assertTrue(step1.report.trafficAlerts.isEmpty())

        // Pass 2: 80% used (800 GiB in 16 days = 50 GiB/day -> remaining 200 GiB = 4 days -> Nov 21) -> fires!
        val step2 = FleetAlertEvaluator.evaluate(
            previousTracker = step1.updatedTracker,
            panels = listOf(panel),
            snapshots = listOf(baseSnap.copy(monthlyTransferOutBytes = 800L * gib)),
            assets = listOf(asset),
            monitorSettings = FleetMonitorSettings(),
            today = LocalDate.of(2026, 11, 17),
            nowEpochMillis = shanghaiEpochMillis(2026, 11, 17, 0, 0),
        )
        assertEquals(1, step2.report.trafficAlerts.size)
        val alert = step2.report.trafficAlerts.single()
        assertEquals(0.80, alert.usageRatio, 0.001)
        assertEquals("2026-11-01", alert.cycleStartDateIso)
        assertEquals("2026-12-01", alert.nextResetDateIso)
        assertEquals("预计本月 21 号流量会用完", alert.exhaustionForecastText)

        // Verify notification copy formatting includes 80% usage and exhaustion date forecast
        val (notifTitle, notifSummary, notifBigBody) = FleetAlertNotifier.formatTrafficWarningCopy(alert)
        assertTrue(notifTitle.contains("DMIT 东京 Pro"))
        assertTrue(notifTitle.contains("80%"))
        assertTrue(notifSummary.contains("预计本月 21 号流量会用完"))
        assertTrue(notifBigBody.contains("下次重置日：2026-12-01"))

        // Pass 3: 88% used on Nov 18 (same cycle 2026-11-01) -> deduplicated!
        val step3 = FleetAlertEvaluator.evaluate(
            previousTracker = step2.updatedTracker,
            panels = listOf(panel),
            snapshots = listOf(baseSnap.copy(monthlyTransferOutBytes = 880L * gib)),
            assets = listOf(asset),
            monitorSettings = FleetMonitorSettings(),
            today = LocalDate.of(2026, 11, 18),
            nowEpochMillis = shanghaiEpochMillis(2026, 11, 18, 12, 0),
        )
        assertTrue(step3.report.trafficAlerts.isEmpty())

        // Pass 4: Manual traffic reset package bought mid-cycle -> drops to 5% -> re-arms!
        val step4 = FleetAlertEvaluator.evaluate(
            previousTracker = step3.updatedTracker,
            panels = listOf(panel),
            snapshots = listOf(baseSnap.copy(monthlyTransferOutBytes = 50L * gib)),
            assets = listOf(asset),
            monitorSettings = FleetMonitorSettings(),
            today = LocalDate.of(2026, 11, 19),
            nowEpochMillis = shanghaiEpochMillis(2026, 11, 19, 12, 0),
        )
        assertTrue(step4.report.trafficAlerts.isEmpty())
        assertTrue(step4.updatedTracker.notifiedTrafficCycleKeys.isEmpty())

        // Climbing back to 85% in same cycle fires again after reset drop
        val step5 = FleetAlertEvaluator.evaluate(
            previousTracker = step4.updatedTracker,
            panels = listOf(panel),
            snapshots = listOf(baseSnap.copy(monthlyTransferOutBytes = 850L * gib)),
            assets = listOf(asset),
            monitorSettings = FleetMonitorSettings(),
            today = LocalDate.of(2026, 11, 25),
            nowEpochMillis = shanghaiEpochMillis(2026, 11, 25, 12, 0),
        )
        assertEquals(1, step5.report.trafficAlerts.size)

        // Pass 6: Next monthly cycle (2026-12-01 .. 2027-01-01) hits 84% on Dec 18 -> fires fresh alert for new cycle!
        val step6 = FleetAlertEvaluator.evaluate(
            previousTracker = step5.updatedTracker,
            panels = listOf(panel),
            snapshots = listOf(baseSnap.copy(monthlyTransferOutBytes = 840L * gib)),
            assets = listOf(asset),
            monitorSettings = FleetMonitorSettings(),
            today = LocalDate.of(2026, 12, 18),
            nowEpochMillis = shanghaiEpochMillis(2026, 12, 18, 12, 0),
        )
        assertEquals(1, step6.report.trafficAlerts.size)
        assertEquals("2026-12-01", step6.report.trafficAlerts.single().cycleStartDateIso)
        assertEquals(setOf("p1:1:2026-12-01"), step6.updatedTracker.notifiedTrafficCycleKeys)
    }

    @Test
    fun `FleetRepositoryImpl persists trafficResetDay and rolling 30-day CPU buckets while pruning older days`() = runTest {
        val mockServer = MockWebServer()
        mockServer.start()
        try {
            val currentMillis = AtomicLong(shanghaiEpochMillis(2026, 10, 1, 12, 0))
            val cpuValue = AtomicReference(3.0)
            val transferBytes = AtomicLong(10L * 1024L * 1024L * 1024L)
            val limitBytes = 1000L * 1024L * 1024L * 1024L

            mockServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/api/v1/server") {
                        return MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "success": true,
                              "data": [
                                {
                                  "id": 1,
                                  "name": "Idle-LA",
                                  "online": true,
                                  "traffic_limit": $limitBytes,
                                  "host": {"mem_total": 1073741824},
                                  "state": {
                                    "cpu": ${cpuValue.get()},
                                    "mem_used": 268435456,
                                    "net_in_transfer": 0,
                                    "net_out_transfer": ${transferBytes.get()},
                                    "uptime": 864000
                                  }
                                }
                              ]
                            }
                            """.trimIndent(),
                        )
                    }
                    return MockResponse().setResponseCode(404)
                }
            }

            val storeFile = File(tempDir, "rolling_30d_store.json")
            val probeClient = ProbePanelClient(nowMillisProvider = currentMillis::get)
            val repo = FleetRepositoryImpl(
                storeFile = storeFile,
                probeClient = probeClient,
                tokenCipher = cipher,
                nowMillisProvider = currentMillis::get,
            )

            repo.upsertPanel(
                ProbePanelConfig(
                    id = "p-roll",
                    name = "Roll Panel",
                    baseUrl = mockServer.url("/").toString(),
                    panelType = ProbePanelType.NEZHA_V1,
                ),
            )
            repo.upsertAsset(
                sampleAsset(
                    id = "asset-roll",
                    nickname = "LA 传家宝",
                    linkedProbeKey = "p-roll:1",
                    monthlyTrafficQuotaGb = 1000.0,
                    trafficResetDay = 31,
                ),
            )

            // Simulate 35 consecutive days (2026-10-01 through 2026-11-04)
            val startDate = LocalDate.of(2026, 10, 1)
            for (dayOffset in 0 until 35) {
                val date = startDate.plusDays(dayOffset.toLong())
                currentMillis.set(shanghaiEpochMillis(date.year, date.monthValue, date.dayOfMonth, 10, 0))
                cpuValue.set(2.0)
                repo.refreshAllPanels()

                if (dayOffset == 34) {
                    // Second poll on the final day to verify intra-day incremental average (2.0 & 6.0 -> avg 4.0, max 6.0)
                    currentMillis.set(shanghaiEpochMillis(date.year, date.monthValue, date.dayOfMonth, 18, 0))
                    cpuValue.set(6.0)
                    repo.refreshAllPanels()
                }
            }

            val buckets = repo.cpuHistoryByProbeKeyFlow.value["p-roll:1"]
            assertNotNull(buckets)
            assertEquals("Must retain rolling 30-day window and prune days 1..5", 30, buckets!!.size)
            assertEquals("2026-10-06", buckets.first().dateIso)
            assertEquals("2026-11-04", buckets.last().dateIso)
            assertEquals(2, buckets.last().sampleCount)
            assertEquals(4.0, buckets.last().avgCpuPercent, 0.001)
            assertEquals(6.0, buckets.last().maxCpuPercent, 0.001)

            // Reopen from disk and verify both trafficResetDay and 30-day CPU history survived serialization
            val reopened = FleetRepositoryImpl(
                storeFile = storeFile,
                probeClient = probeClient,
                tokenCipher = cipher,
                nowMillisProvider = currentMillis::get,
            )
            reopened.loadPersistedState()
            assertEquals(31, reopened.assetsFlow.value.single().trafficResetDay)
            assertEquals(30, reopened.cpuHistoryByProbeKeyFlow.value["p-roll:1"]?.size)

            // Spike traffic to 85% and run background FleetMonitorRunner -> dispatches traffic alert once
            transferBytes.set(850L * 1024L * 1024L * 1024L)
            val sink = TrafficRecordingAlertSink()
            val runner = FleetMonitorRunner(
                fleetRepository = reopened,
                alertSink = sink,
                todayProvider = { LocalDate.of(2026, 11, 4) },
                nowMillisProvider = currentMillis::get,
            )
            runner.runOnce()
            assertEquals(1, sink.trafficEvents.size)
            assertEquals(0.85, sink.trafficEvents.single().usageRatio, 0.001)

            // Immediate second run in same cycle -> deduplicated
            runner.runOnce()
            assertEquals(1, sink.trafficEvents.size)
        } finally {
            mockServer.shutdown()
        }
    }

    @Test
    fun `assessIdle enforces cold-start guard and classifies low-CPU plus low-traffic servers accurately`() {
        val gib = 1024L * 1024L * 1024L
        val evalMillis = shanghaiEpochMillis(2026, 11, 20, 12, 0)
        val asset = sampleAsset(
            id = "a-idle",
            nickname = "RN 吃灰小鸡",
            linkedProbeKey = "p1:1",
            monthlyTrafficQuotaGb = 1000.0,
            trafficResetDay = 1,
            expiryDateIso = "2027-05-20",
        )
        val lowTrafficSnap = ProbeServerSnapshot(
            panelId = "p1",
            remoteServerId = "1",
            name = "RN-Idle",
            cpuPercent = 2.0,
            monthlyTransferOutBytes = 45L * gib, // 4.5% of 1000 GiB over 19.5 days
            trafficLimitBytes = 1000L * gib,
            isOnline = true,
        )
        val lowForecast = FleetTrafficAndIdleAnalyzer.analyzeTraffic(
            snapshot = lowTrafficSnap,
            linkedAsset = asset,
            nowEpochMillis = evalMillis,
        )

        // 1. Cold start: only 2 days of samples -> COLD_START_OBSERVING (never prematurely flagged idle!)
        val twoDaysBuckets = (0 until 2).map { offset ->
            ProbeDailyCpuBucket(
                dateIso = LocalDate.of(2026, 11, 19).plusDays(offset.toLong()).toString(),
                avgCpuPercent = 1.5,
                sampleCount = 1,
            )
        }
        val coldAssessment = FleetTrafficAndIdleAnalyzer.assessIdle(
            snapshot = lowTrafficSnap,
            linkedAsset = asset,
            cpuBuckets = twoDaysBuckets,
            trafficForecast = lowForecast,
            nowEpochMillis = evalMillis,
        )
        assertEquals(IdleClassificationStatus.COLD_START_OBSERVING, coldAssessment.status)
        assertFalse(coldAssessment.isIdle)

        // 2. Sufficient 30-day history (14 days, avg CPU 2.5% <= 8%, traffic 4.5% <= 15%) -> IDLE!
        val idleBuckets = (0 until 14).map { offset ->
            ProbeDailyCpuBucket(
                dateIso = LocalDate.of(2026, 11, 7).plusDays(offset.toLong()).toString(),
                avgCpuPercent = 2.5,
                sampleCount = 4,
            )
        }
        val idleAssessment = FleetTrafficAndIdleAnalyzer.assessIdle(
            snapshot = lowTrafficSnap,
            linkedAsset = asset,
            cpuBuckets = idleBuckets,
            trafficForecast = lowForecast,
            nowEpochMillis = evalMillis,
        )
        assertEquals(IdleClassificationStatus.IDLE, idleAssessment.status)
        assertTrue(idleAssessment.isIdle)
        assertEquals("30天平均 CPU 2.5% · 流量仅用 4.5%，建议出掉回血", idleAssessment.recommendationText)

        // 3. Low CPU (2.5%) + high traffic (30% > 15%) -> ACTIVE (not idle)
        val busyTrafficSnap = lowTrafficSnap.copy(monthlyTransferOutBytes = 300L * gib)
        val busyTrafficForecast = FleetTrafficAndIdleAnalyzer.analyzeTraffic(
            snapshot = busyTrafficSnap,
            linkedAsset = asset,
            nowEpochMillis = evalMillis,
        )
        val busyTrafficAssessment = FleetTrafficAndIdleAnalyzer.assessIdle(
            snapshot = busyTrafficSnap,
            linkedAsset = asset,
            cpuBuckets = idleBuckets,
            trafficForecast = busyTrafficForecast,
            nowEpochMillis = evalMillis,
        )
        assertEquals(IdleClassificationStatus.ACTIVE, busyTrafficAssessment.status)
        assertFalse(busyTrafficAssessment.isIdle)

        // 4. High 30-day CPU (24.0% > 8%) + low traffic (4.5%) -> ACTIVE (not idle)
        val busyCpuBuckets = idleBuckets.map { it.copy(avgCpuPercent = 24.0) }
        val busyCpuAssessment = FleetTrafficAndIdleAnalyzer.assessIdle(
            snapshot = lowTrafficSnap,
            linkedAsset = asset,
            cpuBuckets = busyCpuBuckets,
            trafficForecast = lowForecast,
            nowEpochMillis = evalMillis,
        )
        assertEquals(IdleClassificationStatus.ACTIVE, busyCpuAssessment.status)
        assertFalse(busyCpuAssessment.isIdle)

        // 5. Already-expired asset -> NOT recommended as idle sale
        val expiredAsset = asset.copy(expiryDateIso = "2026-11-01")
        val expiredAssessment = FleetTrafficAndIdleAnalyzer.assessIdle(
            snapshot = lowTrafficSnap,
            linkedAsset = expiredAsset,
            cpuBuckets = idleBuckets,
            trafficForecast = lowForecast,
            nowEpochMillis = evalMillis,
        )
        assertFalse(expiredAssessment.isIdle)
    }

    @Test
    fun `MyServersViewModel identifies idle server and completes FleetSalePostBuilder closed loop`() =
        runTest(testDispatcher) {
            val mockServer = MockWebServer()
            mockServer.start()
            try {
                val gib = 1024L * 1024L * 1024L
                val currentMillis = AtomicLong(shanghaiEpochMillis(2026, 11, 10, 12, 0))

                mockServer.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path == "/api/v1/server") {
                            return MockResponse().setResponseCode(200).setBody(
                                """
                                {
                                  "success": true,
                                  "data": [
                                    {
                                      "id": 101,
                                      "name": "RN-DC02-Idle",
                                      "country_code": "US",
                                      "online": true,
                                      "traffic_limit": ${1000L * gib},
                                      "host": {"platform": "Debian", "platform_version": "12", "cpu": ["AMD EPYC"], "mem_total": ${1L * gib}, "disk_total": ${20L * gib}},
                                      "state": {
                                        "cpu": 2.2,
                                        "mem_used": ${256L * 1024L * 1024L},
                                        "disk_used": ${4L * gib},
                                        "net_in_transfer": ${10L * gib},
                                        "net_out_transfer": ${30L * gib},
                                        "uptime": 1728000
                                      }
                                    },
                                    {
                                      "id": 102,
                                      "name": "HK-Prod-Busy",
                                      "country_code": "HK",
                                      "online": true,
                                      "traffic_limit": ${1000L * gib},
                                      "host": {"mem_total": ${2L * gib}},
                                      "state": {
                                        "cpu": 38.5,
                                        "mem_used": ${1L * gib},
                                        "net_in_transfer": ${250L * gib},
                                        "net_out_transfer": ${350L * gib},
                                        "uptime": 1728000
                                      }
                                    },
                                    {
                                      "id": 103,
                                      "name": "SG-Unlinked-Idle",
                                      "country_code": "SG",
                                      "online": true,
                                      "traffic_limit": ${500L * gib},
                                      "host": {"mem_total": ${1L * gib}},
                                      "state": {
                                        "cpu": 1.4,
                                        "mem_used": ${128L * 1024L * 1024L},
                                        "net_in_transfer": ${5L * gib},
                                        "net_out_transfer": ${5L * gib},
                                        "uptime": 1728000
                                      }
                                    }
                                  ]
                                }
                                """.trimIndent(),
                            )
                        }
                        return MockResponse().setResponseCode(404)
                    }
                }

                val storeFile = File(tempDir, "vm_closed_loop_store.json")
                val probeClient = ProbePanelClient(
                    ioDispatcher = testDispatcher,
                    nowMillisProvider = currentMillis::get,
                )
                val fleetRepo = FleetRepositoryImpl(
                    storeFile = storeFile,
                    probeClient = probeClient,
                    tokenCipher = cipher,
                    ioDispatcher = testDispatcher,
                    nowMillisProvider = currentMillis::get,
                )
                fleetRepo.upsertPanel(
                    ProbePanelConfig(
                        id = "p-main",
                        name = "主力面板",
                        baseUrl = mockServer.url("/").toString(),
                        panelType = ProbePanelType.NEZHA_V1,
                    ),
                )
                val idleLinkedAsset = fleetRepo.upsertAsset(
                    VpsAssetRecord(
                        id = "asset-rn-idle",
                        nickname = "RackNerd DC02 吃灰鸡",
                        providerName = "RackNerd",
                        specsSummary = "1C / 1G / 20G SSD / 1TB",
                        region = "洛杉矶 DC02",
                        renewalAmount = "10.00",
                        currency = ValuationCurrency.USD,
                        cyclePreset = BillingCyclePreset.ANNUAL,
                        expiryDateIso = "2027-05-20", // 182 days from 2026-11-19
                        linkedProbeKey = "p-main:101",
                        monthlyTrafficQuotaGb = 1000.0,
                        trafficResetDay = 1,
                    ),
                )

                // Record 10 days of probe snapshots (2026-11-10 .. 2026-11-19)
                for (day in 10..19) {
                    currentMillis.set(shanghaiEpochMillis(2026, 11, day, 12, 0))
                    fleetRepo.refreshAllPanels()
                }

                val fxRepo = ExchangeRateRepository(
                    cacheFile = File(tempDir, "vm_closed_loop_fx.json"),
                    ioDispatcher = testDispatcher,
                )
                val vm = MyServersViewModel(
                    fleetRepository = fleetRepo,
                    exchangeRateRepository = fxRepo,
                    todayProvider = { LocalDate.of(2026, 11, 19) },
                    nowMillisProvider = currentMillis::get,
                    initialTab = MyServersTab.ASSETS,
                )
                advanceUntilIdle()

                // 1. Both idle nodes (101 linked, 103 unlinked) are flagged IDLE; busy node 102 is ACTIVE
                val state = vm.uiState.value
                assertEquals(2, state.idleServers.size)
                assertEquals(listOf("p-main:101", "p-main:103"), state.idleServers.map { it.probeKey })
                assertFalse(state.idleAssessmentsByProbeKey["p-main:102"]!!.isIdle)

                val rnIdle = state.idleAssessmentsByProbeKey["p-main:101"]!!
                assertTrue(rnIdle.isIdle)
                assertEquals("30天平均 CPU 2.2% · 流量仅用 4.0%，建议出掉回血", rnIdle.recommendationText)

                // 2. One-tap closed loop on linked idle server: opens FleetSalePostSheet & builds sale post
                vm.openIdleSalePost("p-main:101")
                advanceUntilIdle()
                assertEquals(idleLinkedAsset.id, vm.uiState.value.salePostAsset?.id)

                val saleOutcome = vm.generateIdleSalePost("p-main:101")
                assertTrue(saleOutcome is FleetSalePostOutcome.Success)
                val saleResult = (saleOutcome as FleetSalePostOutcome.Success).result
                assertTrue(saleResult.title.startsWith("[出] RackNerd 洛杉矶 DC02"))
                assertTrue(saleResult.markdownBody.contains("探针实时运行参考"))
                assertTrue(saleResult.markdownBody.contains("CPU 2.2%"))
                assertTrue(saleResult.markdownBody.contains("净剩余价值"))

                vm.closeSalePostSheet()
                advanceUntilIdle()
                assertNull(vm.uiState.value.salePostAsset)

                // 3. One-tap closed loop on unlinked idle probe: opens prefilled AssetEditor with openSalePostAfterSave,
                //    then automatically transitions straight into FleetSalePostSheet once saved!
                vm.openIdleSalePost("p-main:103")
                advanceUntilIdle()
                val prefilledDraft = vm.uiState.value.assetEditorDraft
                assertNotNull(prefilledDraft)
                assertTrue(prefilledDraft!!.openSalePostAfterSave)
                assertEquals("p-main:103", prefilledDraft.linkedProbeKey)

                vm.saveAsset(
                    VpsAssetRecord(
                        id = "asset-sg-new",
                        nickname = prefilledDraft.nickname,
                        providerName = "ClawCloud",
                        specsSummary = "1C / 1G",
                        region = "新加坡",
                        renewalAmount = "18.00",
                        currency = ValuationCurrency.USD,
                        cyclePreset = BillingCyclePreset.ANNUAL,
                        expiryDateIso = "2027-08-01",
                        linkedProbeKey = prefilledDraft.linkedProbeKey,
                        monthlyTrafficQuotaGb = 500.0,
                    ),
                )
                advanceUntilIdle()

                assertNull(vm.uiState.value.assetEditorDraft)
                assertEquals("asset-sg-new", vm.uiState.value.salePostAsset?.id)
                val sgSaleOutcome = vm.generateIdleSalePost("p-main:103")
                assertTrue(sgSaleOutcome is FleetSalePostOutcome.Success)
            } finally {
                mockServer.shutdown()
            }
        }

    @Test
    fun `legacy fleet_store json without trafficResetDay or cpuHistory deserializes cleanly with safe defaults`() =
        runTest {
            val legacyStoreFile = File(tempDir, "legacy_v1_fleet_store.json")
            legacyStoreFile.writeText(
                """
                {
                  "version": 1,
                  "panels": [],
                  "snapshots": [],
                  "assets": [
                    {
                      "id": "legacy-1",
                      "nickname": "Old Schema Box",
                      "providerName": "RackNerd",
                      "specsSummary": "1C / 1G",
                      "region": "洛杉矶",
                      "renewalAmount": "10.98",
                      "currency": "USD",
                      "cyclePreset": "ANNUAL",
                      "expiryDateIso": "2027-03-31"
                    }
                  ],
                  "monitorSettings": {"backgroundMonitorEnabled": true, "pollIntervalMinutes": 15},
                  "alertTracker": {
                    "lastKnownOnlineByProbeKey": {},
                    "activeOutageNotifiedKeys": [],
                    "notifiedRenewalKeys": []
                  },
                  "lastSyncEpochMillis": 1000
                }
                """.trimIndent(),
                Charsets.UTF_8,
            )

            val repo = FleetRepositoryImpl(storeFile = legacyStoreFile, tokenCipher = cipher)
            repo.loadPersistedState()

            val loadedAsset = repo.assetsFlow.value.single()
            assertEquals("Old Schema Box", loadedAsset.nickname)
            assertNull(loadedAsset.trafficResetDay)
            assertTrue(repo.cpuHistoryByProbeKeyFlow.value.isEmpty())
            assertEquals(
                31 to TrafficResetDaySource.ASSET_EXPIRY_DAY_FALLBACK,
                FleetTrafficAndIdleAnalyzer.resolveTrafficResetDay(loadedAsset),
            )
        }

    private fun shanghaiEpochMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long = LocalDateTime.of(year, month, day, hour, minute)
        .atZone(VALUATION_ZONE_ID)
        .toInstant()
        .toEpochMilli()

    private fun sampleAsset(
        id: String = "asset-1",
        nickname: String = "Test VPS",
        linkedProbeKey: String? = "p1:1",
        monthlyTrafficQuotaGb: Double? = 1000.0,
        trafficResetDay: Int? = null,
        expiryDateIso: String = "2027-06-15",
    ) = VpsAssetRecord(
        id = id,
        nickname = nickname,
        providerName = "DMIT",
        specsSummary = "2C / 2G / 40G NVMe",
        region = "东京",
        renewalAmount = "49.90",
        currency = ValuationCurrency.USD,
        cyclePreset = BillingCyclePreset.ANNUAL,
        expiryDateIso = expiryDateIso,
        linkedProbeKey = linkedProbeKey,
        monthlyTrafficQuotaGb = monthlyTrafficQuotaGb,
        trafficResetDay = trafficResetDay,
        remindDaysBeforeExpiry = 7,
    )
}
