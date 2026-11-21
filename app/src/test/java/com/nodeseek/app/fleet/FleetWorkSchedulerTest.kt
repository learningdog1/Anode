package com.nodeseek.app.fleet

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.fleet.FleetWorkManagerGateway
import com.nodeseek.app.data.fleet.FleetWorkScheduler
import com.nodeseek.app.data.fleet.SoftwareAesGcmTokenCipher
import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.domain.fleet.FleetMonitorCheckReport
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.FleetRefreshSummary
import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.fleet.ProbeConnectivityResult
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class FleetWorkSchedulerTest {

    private lateinit var tempDir: File
    private lateinit var cipher: SoftwareAesGcmTokenCipher

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
        tempDir = Files.createTempDirectory("fleet_work_scheduler_test").toFile()
        cipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `cold start with persisted renewal-enabled asset never cancels periodic work, loads disk state, and schedules periodic work`() =
        runTest {
            val storeFile = File(tempDir, "cold_start_fleet_store.json")

            // Previous session persisted 1 asset with renewal reminder enabled (no probe panels needed).
            val writerRepo = FleetRepositoryImpl(
                storeFile = storeFile,
                tokenCipher = cipher,
            )
            writerRepo.upsertAsset(
                VpsAssetRecord(
                    id = "asset-renewal-1",
                    nickname = "DMIT LAX Pro",
                    providerName = "DMIT",
                    specsSummary = "1C / 1G",
                    region = "洛杉矶",
                    renewalAmount = "39.90",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = "2026-11-26",
                    remindDaysBeforeExpiry = 7,
                ),
            )

            // Simulate app cold start before MyServersScreen is ever opened:
            // separate dispatchers let us observe the window before disk I/O completes.
            val appScheduler = TestCoroutineScheduler()
            val ioScheduler = TestCoroutineScheduler()
            val appDispatcher = StandardTestDispatcher(appScheduler)
            val slowIoDispatcher = StandardTestDispatcher(ioScheduler)
            val appScope = CoroutineScope(SupervisorJob() + appDispatcher)

            try {
                val freshColdStartRepo = FleetRepositoryImpl(
                    storeFile = storeFile,
                    tokenCipher = cipher,
                    ioDispatcher = slowIoDispatcher,
                )
                assertFalse(freshColdStartRepo.isPersistedStateLoadedFlow.value)
                assertTrue(freshColdStartRepo.assetsFlow.value.isEmpty())

                val gateway = RecordingWorkGateway()
                val workScheduler = FleetWorkScheduler(gateway)

                workScheduler.observeRepository(
                    scope = appScope,
                    repository = freshColdStartRepo,
                )

                // Run appScope collector before slowIoDispatcher has finished reading disk:
                // must NOT cancel unique periodic work while state is still unloaded!
                appScheduler.runCurrent()
                assertFalse(freshColdStartRepo.isPersistedStateLoadedFlow.value)
                assertTrue(
                    "Cold start must NEVER cancel periodic work before persisted state is loaded",
                    gateway.cancelled.isEmpty(),
                )
                assertTrue(gateway.enqueuedPeriodic.isEmpty())

                // Now let disk load complete and deliver updated flows back to appScope.
                ioScheduler.runCurrent()
                appScheduler.runCurrent()

                assertTrue(freshColdStartRepo.isPersistedStateLoadedFlow.value)
                assertEquals(1, freshColdStartRepo.assetsFlow.value.size)
                assertTrue(
                    "Cold start must not have cancelled periodic work at any point",
                    gateway.cancelled.isEmpty(),
                )
                assertEquals(1, gateway.enqueuedPeriodic.size)
                assertEquals(
                    FleetWorkScheduler.UNIQUE_PERIODIC_WORK_NAME,
                    gateway.enqueuedPeriodic.single().first,
                )

                // Removing the last target after state is loaded cancels periodic work as expected.
                val deleteDone = kotlinx.coroutines.CompletableDeferred<Unit>()
                val deleter = kotlinx.coroutines.CoroutineScope(appDispatcher).launch {
                    freshColdStartRepo.deleteAsset("asset-renewal-1")
                    deleteDone.complete(Unit)
                }
                appScheduler.runCurrent()
                ioScheduler.runCurrent()
                appScheduler.runCurrent()
                assertTrue(deleteDone.isCompleted)
                deleter.cancel()
                assertEquals(
                    listOf(FleetWorkScheduler.UNIQUE_PERIODIC_WORK_NAME),
                    gateway.cancelled,
                )
            } finally {
                appScope.cancel()
            }
        }

    @Test
    fun `cold start with empty persisted store waits until disk state is loaded before cancelling periodic work`() =
        runTest {
            val emptyStoreFile = File(tempDir, "nonexistent_fleet_store.json")
            val appScheduler = TestCoroutineScheduler()
            val ioScheduler = TestCoroutineScheduler()
            val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(appScheduler))
            val slowIoDispatcher = StandardTestDispatcher(ioScheduler)

            try {
                val freshEmptyRepo = FleetRepositoryImpl(
                    storeFile = emptyStoreFile,
                    tokenCipher = cipher,
                    ioDispatcher = slowIoDispatcher,
                )
                val gateway = RecordingWorkGateway()
                val workScheduler = FleetWorkScheduler(gateway)

                workScheduler.observeRepository(
                    scope = appScope,
                    repository = freshEmptyRepo,
                )

                // While unloaded, even an empty repository must NOT cancel yet.
                appScheduler.runCurrent()
                assertFalse(freshEmptyRepo.isPersistedStateLoadedFlow.value)
                assertTrue(gateway.cancelled.isEmpty())

                // Once persisted state is confirmed loaded with zero monitoring targets, cancellation runs.
                ioScheduler.runCurrent()
                appScheduler.runCurrent()
                assertTrue(freshEmptyRepo.isPersistedStateLoadedFlow.value)
                assertEquals(
                    listOf(FleetWorkScheduler.UNIQUE_PERIODIC_WORK_NAME),
                    gateway.cancelled,
                )
                assertTrue(gateway.enqueuedPeriodic.isEmpty())
            } finally {
                appScope.cancel()
            }
        }

    @Test
    fun `cold start does not cancel existing periodic work when loadPersistedState fails transiently`() =
        runTest {
            val appScheduler = TestCoroutineScheduler()
            val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(appScheduler))
            val unloadedFailingRepo = object : FleetRepository {
                private val loaded = MutableStateFlow(false)
                override val isPersistedStateLoadedFlow: StateFlow<Boolean> = loaded.asStateFlow()
                override val panelsFlow: StateFlow<List<ProbePanelConfig>> =
                    MutableStateFlow(emptyList<ProbePanelConfig>()).asStateFlow()
                override val snapshotsFlow: StateFlow<List<ProbeServerSnapshot>> =
                    MutableStateFlow(emptyList<ProbeServerSnapshot>()).asStateFlow()
                override val assetsFlow: StateFlow<List<VpsAssetRecord>> =
                    MutableStateFlow(emptyList<VpsAssetRecord>()).asStateFlow()
                override val monitorSettingsFlow: StateFlow<FleetMonitorSettings> =
                    MutableStateFlow(FleetMonitorSettings()).asStateFlow()
                override val lastSyncEpochMillisFlow: StateFlow<Long> =
                    MutableStateFlow(0L).asStateFlow()
                override val isRefreshingFlow: StateFlow<Boolean> =
                    MutableStateFlow(false).asStateFlow()
                override val panelErrorsFlow: StateFlow<Map<String, String>> =
                    MutableStateFlow(emptyMap<String, String>()).asStateFlow()

                override suspend fun loadPersistedState() {
                    throw IOException("Simulated cold-start storage read failure")
                }

                override suspend fun upsertPanel(config: ProbePanelConfig): ProbePanelConfig = config
                override suspend fun deletePanel(panelId: String) = Unit
                override suspend fun testPanelConnection(config: ProbePanelConfig): ProbeConnectivityResult =
                    ProbeConnectivityResult(success = false)
                override suspend fun refreshAllPanels(): FleetRefreshSummary =
                    FleetRefreshSummary(0, 0, emptyMap(), emptyList(), 0L)
                override suspend fun refreshPanel(panelId: String): Result<List<ProbeServerSnapshot>> =
                    Result.success(emptyList())
                override suspend fun upsertAsset(asset: VpsAssetRecord): VpsAssetRecord = asset
                override suspend fun deleteAsset(assetId: String) = Unit
                override suspend fun linkAssetToProbe(assetId: String, probeKey: String?) = Unit
                override suspend fun updateMonitorSettings(settings: FleetMonitorSettings) = Unit
                override suspend fun evaluateAlerts(
                    today: LocalDate,
                    nowEpochMillis: Long,
                    fxRatesState: FxRatesState?,
                    checkedPanels: Int?,
                    failedPanels: Int,
                ): FleetMonitorCheckReport = FleetMonitorCheckReport()
            }

            try {
                val gateway = RecordingWorkGateway()
                val workScheduler = FleetWorkScheduler(gateway)
                workScheduler.observeRepository(scope = appScope, repository = unloadedFailingRepo)

                appScheduler.runCurrent()
                assertTrue(gateway.cancelled.isEmpty())
                assertTrue(gateway.enqueuedPeriodic.isEmpty())
            } finally {
                appScope.cancel()
            }
        }
}
