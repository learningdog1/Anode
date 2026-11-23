package com.nodeseek.app.fleet

import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.fleet.SoftwareAesGcmTokenCipher
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.domain.fleet.FleetMonitorCheckReport
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.FleetRefreshSummary
import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.fleet.ProbeConnectivityResult
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.ui.fleet.MyServersTab
import com.nodeseek.app.ui.fleet.MyServersViewModel
import com.nodeseek.app.ui.fleet.PanelManagerSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class MyServersViewModelTest {

    private lateinit var tempDir: File
    private lateinit var cipher: SoftwareAesGcmTokenCipher
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempDir = Files.createTempDirectory("my_servers_vm_test").toFile()
        cipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.walkBottomUp().forEach { file ->
            file.setReadable(true)
            file.setWritable(true)
        }
        tempDir.deleteRecursively()
    }

    @Test
    fun `saveAsset keeps editor open with user draft and emits Chinese error instead of success when disk write fails`() =
        runTest(testDispatcher) {
            val blockerFile = File(tempDir, "unwritable_parent").apply {
                writeText("blocker", Charsets.UTF_8)
            }
            val unwritableStoreFile = File(blockerFile, "fleet_store.json")
            val fleetRepo = FleetRepositoryImpl(
                storeFile = unwritableStoreFile,
                tokenCipher = cipher,
                ioDispatcher = testDispatcher,
            )
            val fxRepo = ExchangeRateRepository(
                cacheFile = File(tempDir, "fx_cache.json"),
                ioDispatcher = testDispatcher,
            )
            val vm = MyServersViewModel(
                fleetRepository = fleetRepo,
                exchangeRateRepository = fxRepo,
                todayProvider = { LocalDate.of(2026, 11, 20) },
                initialTab = MyServersTab.ASSETS,
            )

            val snackbars = mutableListOf<String>()
            val collectorJob = backgroundScope.launch(testDispatcher) {
                vm.snackbarMessages.collect { snackbars += it }
            }
            advanceUntilIdle()

            vm.openAddAssetSheet()
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.assetEditorDraft)

            val submittedAsset = VpsAssetRecord(
                id = "draft-generated-uuid",
                nickname = "瓦工 HK85 主力鸡",
                providerName = "BandwagonHost",
                specsSummary = "2C / 2G / 40G NVMe",
                region = "香港",
                routeTag = "CMI",
                renewalAmount = "89.90",
                currency = ValuationCurrency.USD,
                cyclePreset = BillingCyclePreset.ANNUAL,
                expiryDateIso = "2027-11-20",
                purchaseUrl = "https://bwh81.net/order",
                panelManageUrl = "https://kiwivm.64clouds.com",
                monthlyTrafficQuotaGb = 1000.0,
                remindDaysBeforeExpiry = 15,
                autoRenewNote = "循环 93 折优惠码",
                notes = "核心主力线路，切勿丢弃草稿",
            )

            vm.saveAsset(submittedAsset)
            advanceUntilIdle()

            // 1. In-memory assets remain empty and match a freshly reopened repository
            assertTrue(vm.uiState.value.assets.isEmpty())
            assertTrue(fleetRepo.assetsFlow.value.isEmpty())
            val reopenedRepo = FleetRepositoryImpl(
                storeFile = unwritableStoreFile,
                tokenCipher = cipher,
                ioDispatcher = testDispatcher,
            )
            reopenedRepo.loadPersistedState()
            assertEquals(fleetRepo.assetsFlow.value, reopenedRepo.assetsFlow.value)

            // 2. Editor stays open with all user input preserved (and still in "add" mode: editingId == null)
            val preservedDraft = vm.uiState.value.assetEditorDraft
            assertNotNull("Asset editor must stay open when save fails", preservedDraft)
            preservedDraft!!
            assertNull(preservedDraft.editingId)
            assertEquals("瓦工 HK85 主力鸡", preservedDraft.nickname)
            assertEquals("BandwagonHost", preservedDraft.providerName)
            assertEquals("2C / 2G / 40G NVMe", preservedDraft.specsSummary)
            assertEquals("香港", preservedDraft.region)
            assertEquals("CMI", preservedDraft.routeTag)
            assertEquals("89.90", preservedDraft.renewalAmount)
            assertEquals(ValuationCurrency.USD, preservedDraft.currency)
            assertEquals(BillingCyclePreset.ANNUAL, preservedDraft.cyclePreset)
            assertEquals("2027-11-20", preservedDraft.expiryDateIso)
            assertEquals("https://bwh81.net/order", preservedDraft.purchaseUrl)
            assertEquals("https://kiwivm.64clouds.com", preservedDraft.panelManageUrl)
            assertEquals("1000", preservedDraft.monthlyTrafficQuotaGb)
            assertEquals(15, preservedDraft.remindDaysBeforeExpiry)
            assertEquals("循环 93 折优惠码", preservedDraft.autoRenewNote)
            assertEquals("核心主力线路，切勿丢弃草稿", preservedDraft.notes)

            // 3. Emits Chinese error instead of "已保存资产"
            assertNotNull(vm.uiState.value.assetSaveError)
            assertTrue(vm.uiState.value.assetSaveError!!.contains("保存资产失败"))
            assertFalse(
                "Must NEVER emit false success snackbar on persistence failure",
                snackbars.any { it.contains("已保存资产") },
            )
            assertTrue(
                "Must emit Chinese failure message on persistence failure",
                snackbars.any { it.contains("保存资产失败") },
            )

            collectorJob.cancel()
        }

    @Test
    fun `editing existing asset and other mutations surface Chinese errors without corrupting state when ledger is read-only`() =
        runTest(testDispatcher) {
            val storeFile = File(tempDir, "readonly_vm_store.json")
            val fleetRepo = FleetRepositoryImpl(
                storeFile = storeFile,
                tokenCipher = cipher,
                ioDispatcher = testDispatcher,
            )
            val existingAsset = fleetRepo.upsertAsset(
                VpsAssetRecord(
                    id = "existing-1",
                    nickname = "DMIT LAX",
                    providerName = "DMIT",
                    specsSummary = "1C / 1G",
                    region = "洛杉矶",
                    renewalAmount = "39.90",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = "2027-06-01",
                ),
            )
            val existingPanel = fleetRepo.upsertPanel(
                ProbePanelConfig(
                    id = "panel-1",
                    name = "哪吒面板",
                    baseUrl = "https://probe.example.com",
                    panelType = ProbePanelType.NEZHA_V1,
                ),
            )

            val fxRepo = ExchangeRateRepository(
                cacheFile = File(tempDir, "fx_cache_2.json"),
                ioDispatcher = testDispatcher,
            )
            val vm = MyServersViewModel(
                fleetRepository = fleetRepo,
                exchangeRateRepository = fxRepo,
                todayProvider = { LocalDate.of(2026, 11, 20) },
                initialTab = MyServersTab.ASSETS,
            )

            val snackbars = mutableListOf<String>()
            val collectorJob = backgroundScope.launch(testDispatcher) {
                vm.snackbarMessages.collect { snackbars += it }
            }
            advanceUntilIdle()
            snackbars.clear()

            // Make store file read-only to trigger persistence failures
            assertTrue(storeFile.setWritable(false))

            vm.openEditAssetSheet(existingAsset)
            vm.saveAsset(existingAsset.copy(nickname = "DMIT LAX 改名", renewalAmount = "49.90"))
            vm.deleteAsset(existingAsset.id)
            vm.openProbeBinder(existingAsset)
            vm.bindAssetToProbe(existingAsset.id, "panel-1:1")
            vm.savePanel(existingPanel.copy(name = "新探针名"))
            vm.deletePanel(existingPanel.id)
            vm.updateMonitorSettings(FleetMonitorSettings(backgroundMonitorEnabled = false))
            advanceUntilIdle()

            // Original asset remains untouched in UI state, while edit draft & probe binder remain open
            assertEquals(listOf(existingAsset), vm.uiState.value.assets)
            assertEquals(listOf(existingPanel), vm.uiState.value.panels)
            assertEquals("existing-1", vm.uiState.value.assetEditorDraft?.editingId)
            assertEquals("DMIT LAX 改名", vm.uiState.value.assetEditorDraft?.nickname)
            assertEquals("49.90", vm.uiState.value.assetEditorDraft?.renewalAmount)
            assertNotNull(vm.uiState.value.bindingProbeAsset)

            // None of the false-positive success messages were emitted
            assertFalse(snackbars.any { it.contains("已保存资产") })
            assertFalse(snackbars.any { it.contains("已删除服务器资产") })
            assertFalse(snackbars.any { it.contains("已绑定探针节点") })
            assertFalse(snackbars.any { it.contains("已保存探针面板") })
            assertFalse(snackbars.any { it.contains("已移除探针面板") })
            assertFalse(snackbars.any { it.contains("后台巡检已关闭") })

            // Clear Chinese error messages were emitted for each failed mutation
            assertTrue(snackbars.any { it.contains("保存资产失败") })
            assertTrue(snackbars.any { it.contains("删除服务器资产失败") })
            assertTrue(snackbars.any { it.contains("更新探针绑定失败") })
            assertTrue(snackbars.any { it.contains("保存探针面板失败") })
            assertTrue(snackbars.any { it.contains("移除探针面板失败") })
            assertTrue(snackbars.any { it.contains("保存后台巡检设置失败") })

            collectorJob.cancel()
        }

    @Test
    fun `runForegroundProbeAutoRefresh updates online counts and telemetry automatically without manual refresh or snackbar spam`() =
        runTest(testDispatcher) {
            val mockServer = okhttp3.mockwebserver.MockWebServer()
            mockServer.start()
            try {
                var tick = 0
                mockServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse =
                        when (request.path) {
                            "/api/nodes" -> okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody(
                                """
                                {
                                  "status": "success",
                                  "data": [
                                    { "uuid": "n-1", "name": "Node-1", "region": "JP", "mem_total": 1073741824, "disk_total": 10737418240 },
                                    { "uuid": "n-2", "name": "Node-2", "region": "US", "mem_total": 1073741824, "disk_total": 10737418240 }
                                  ]
                                }
                                """.trimIndent(),
                            )
                            "/api/rpc2" -> {
                                tick++
                                val secondOnline = tick >= 2
                                val cpu1 = if (tick == 1) 12.0 else 48.5
                                val speed1 = if (tick == 1) 10240L else 512000L
                                okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody(
                                    """
                                    {
                                      "jsonrpc": "2.0",
                                      "id": 1,
                                      "result": {
                                        "n-1": { "client": "n-1", "online": true, "cpu": $cpu1, "ram": 536870912, "disk": 2147483648, "net_in": $speed1, "net_out": 20480, "uptime": 3600 },
                                        "n-2": { "client": "n-2", "online": $secondOnline, "cpu": 15.0, "ram": 268435456, "disk": 1073741824, "net_in": 4096, "net_out": 4096, "uptime": 1800 }
                                      }
                                    }
                                    """.trimIndent(),
                                )
                            }
                            else -> okhttp3.mockwebserver.MockResponse().setResponseCode(404)
                        }
                }

                var nowMs = 1_763_560_845_000L
                val storeFile = File(tempDir, "auto_refresh_store.json")
                val probeClient = com.nodeseek.app.data.fleet.ProbePanelClient(
                    ioDispatcher = testDispatcher,
                    nowMillisProvider = { nowMs },
                    allowLoopbackHttpWithCredentials = true,
                )
                val fleetRepo = FleetRepositoryImpl(
                    storeFile = storeFile,
                    probeClient = probeClient,
                    tokenCipher = cipher,
                    ioDispatcher = testDispatcher,
                    nowMillisProvider = { nowMs },
                )
                fleetRepo.upsertPanel(
                    ProbePanelConfig(
                        id = "komari-auto",
                        name = "Komari Live",
                        baseUrl = mockServer.url("/").toString(),
                        panelType = ProbePanelType.KOMARI,
                    ),
                )
                val fxRepo = ExchangeRateRepository(
                    cacheFile = File(tempDir, "fx_cache_auto.json"),
                    ioDispatcher = testDispatcher,
                )
                val vm = MyServersViewModel(
                    fleetRepository = fleetRepo,
                    exchangeRateRepository = fxRepo,
                    todayProvider = { LocalDate.of(2026, 11, 20) },
                    nowMillisProvider = { nowMs },
                )

                val snackbars = mutableListOf<String>()
                val snackJob = backgroundScope.launch(testDispatcher) {
                    vm.snackbarMessages.collect { snackbars += it }
                }
                suspend fun awaitCondition(predicate: () -> Boolean) {
                    val deadline = System.nanoTime() + 3_000_000_000L
                    while (!predicate() && System.nanoTime() < deadline) {
                        testScheduler.runCurrent()
                        Thread.sleep(10)
                    }
                    testScheduler.runCurrent()
                }

                awaitCondition { vm.uiState.value.snapshots.size == 2 }
                snackbars.clear()

                // Initial state after first sync: 1 online, 1 offline, cpu = 12.0
                assertEquals(1, vm.uiState.value.portfolioSummary.onlineProbeCount)
                assertEquals(1, vm.uiState.value.portfolioSummary.offlineProbeCount)
                assertEquals(12.0, vm.uiState.value.snapshots.first { it.remoteServerId == "n-1" }.cpuPercent, 0.01)

                // Start foreground auto-refresh loop using default 2 s interval without manual refresh
                assertEquals(2_000L, MyServersViewModel.DEFAULT_FOREGROUND_POLL_INTERVAL_MS)
                val pollJob = backgroundScope.launch(testDispatcher) {
                    vm.runForegroundProbeAutoRefresh()
                }
                testScheduler.runCurrent()
                nowMs += 2_000L
                testScheduler.advanceTimeBy(2_100L)
                awaitCondition { vm.uiState.value.portfolioSummary.onlineProbeCount == 2 }

                assertEquals(2, vm.uiState.value.portfolioSummary.onlineProbeCount)
                assertEquals(0, vm.uiState.value.portfolioSummary.offlineProbeCount)
                val refreshedN1 = vm.uiState.value.snapshots.first { it.remoteServerId == "n-1" }
                assertEquals(48.5, refreshedN1.cpuPercent, 0.01)
                assertEquals(512000L, refreshedN1.netInSpeedBps)
                assertFalse(vm.uiState.value.isRefreshing)
                assertTrue("Silent foreground poll must not emit snackbars", snackbars.isEmpty())

                pollJob.cancel()
                snackJob.cancel()
            } finally {
                mockServer.shutdown()
            }
        }

    @Test
    fun `foreground auto-refresh never overlaps in-flight requests and stops cleanly when cancelled`() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            val inFlight = AtomicInteger(0)
            val maxConcurrent = AtomicInteger(0)
            val refreshCalls = AtomicInteger(0)

            val fakeRepo = object : FleetRepository {
                private val _loaded = MutableStateFlow(true)
                private val _panels = MutableStateFlow(
                    listOf(
                        ProbePanelConfig(
                            id = "p1",
                            name = "Panel 1",
                            baseUrl = "https://probe.example.com",
                            panelType = ProbePanelType.NEZHA_V1,
                            enabled = true,
                        ),
                    ),
                )
                private val _snapshots = MutableStateFlow(
                    listOf(
                        ProbeServerSnapshot(panelId = "p1", remoteServerId = "b", name = "SameName", isOnline = true),
                        ProbeServerSnapshot(panelId = "p1", remoteServerId = "a", name = "SameName", isOnline = true),
                    ),
                )
                private val _assets = MutableStateFlow<List<VpsAssetRecord>>(emptyList())
                private val _settings = MutableStateFlow(FleetMonitorSettings())
                private val _lastSync = MutableStateFlow(1_000_000L)
                private val _refreshing = MutableStateFlow(false)
                private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())

                override val isPersistedStateLoadedFlow: StateFlow<Boolean> = _loaded.asStateFlow()
                override val panelsFlow: StateFlow<List<ProbePanelConfig>> = _panels.asStateFlow()
                override val snapshotsFlow: StateFlow<List<ProbeServerSnapshot>> = _snapshots.asStateFlow()
                override val assetsFlow: StateFlow<List<VpsAssetRecord>> = _assets.asStateFlow()
                override val monitorSettingsFlow: StateFlow<FleetMonitorSettings> = _settings.asStateFlow()
                override val lastSyncEpochMillisFlow: StateFlow<Long> = _lastSync.asStateFlow()
                override val isRefreshingFlow: StateFlow<Boolean> = _refreshing.asStateFlow()
                override val panelErrorsFlow: StateFlow<Map<String, String>> = _errors.asStateFlow()

                override suspend fun loadPersistedState() = Unit
                override suspend fun upsertPanel(config: ProbePanelConfig): ProbePanelConfig = config
                override suspend fun deletePanel(panelId: String) = Unit
                override suspend fun testPanelConnection(config: ProbePanelConfig) = ProbeConnectivityResult(true)
                override suspend fun refreshPanel(panelId: String) = Result.success(_snapshots.value)
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
                ) = FleetMonitorCheckReport()

                override suspend fun refreshAllPanels(): FleetRefreshSummary = refreshAllPanels(silent = false)

                override suspend fun refreshAllPanels(silent: Boolean): FleetRefreshSummary {
                    val callIndex = refreshCalls.incrementAndGet()
                    val current = inFlight.incrementAndGet()
                    maxConcurrent.updateAndGet { maxOf(it, current) }
                    try {
                        // Block the second call (first silent foreground tick) until test releases gate.
                        if (silent && callIndex == 2) {
                            gate.await()
                        }
                        return FleetRefreshSummary(
                            refreshedPanelCount = 1,
                            succeededPanelCount = 1,
                            failedPanelErrors = emptyMap(),
                            snapshots = _snapshots.value,
                            syncedAtEpochMillis = _lastSync.value,
                        )
                    } finally {
                        inFlight.decrementAndGet()
                    }
                }
            }

            val fxRepo = ExchangeRateRepository(
                cacheFile = File(tempDir, "fx_cache_overlap.json"),
                endpointUrl = "http://127.0.0.1:1/fx",
                ioDispatcher = testDispatcher,
            )
            var nowMs = 1_000_000L
            val vm = MyServersViewModel(
                fleetRepository = fakeRepo,
                exchangeRateRepository = fxRepo,
                todayProvider = { LocalDate.of(2026, 11, 20) },
                nowMillisProvider = { nowMs },
            )
            advanceUntilIdle()
            assertEquals(1, refreshCalls.get())

            // Verify deterministic probe tie-breaking by probeKey when names match
            assertEquals(
                listOf("p1:a", "p1:b"),
                vm.uiState.value.filteredSnapshots.map { it.probeKey },
            )

            // Verify distinct panel manager sections
            vm.openMonitorSettings()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.showPanelManagerSheet)
            assertEquals(PanelManagerSection.MONITOR_SETTINGS, vm.uiState.value.panelManagerInitialSection)
            vm.openPanelManager()
            advanceUntilIdle()
            assertEquals(PanelManagerSection.PANELS, vm.uiState.value.panelManagerInitialSection)
            vm.closePanelManager()
            advanceUntilIdle()

            // Launch foreground auto-refresh and trigger first 2s tick which suspends on `gate`
            val pollJob = backgroundScope.launch(testDispatcher) {
                vm.runForegroundProbeAutoRefresh()
            }
            testScheduler.advanceTimeBy(2_000L)
            testScheduler.runCurrent()
            assertEquals(2, refreshCalls.get())
            assertEquals(1, inFlight.get())

            // Concurrent silent refresh attempts while first is in-flight must be skipped cleanly
            vm.refreshProbesSilently()
            vm.refreshProbesSilently()
            testScheduler.runCurrent()
            assertEquals(2, refreshCalls.get())
            assertEquals(1, maxConcurrent.get())

            // Release gate, let next 2s tick fire, then cancel foreground polling (leaving foreground)
            gate.complete(Unit)
            testScheduler.runCurrent()
            assertEquals(0, inFlight.get())

            nowMs += 2_000L
            testScheduler.advanceTimeBy(2_000L)
            testScheduler.runCurrent()
            assertEquals(3, refreshCalls.get())

            pollJob.cancel()
            testScheduler.runCurrent()

            // Advancing time after foreground cancellation must trigger zero additional requests
            nowMs += 10_000L
            testScheduler.advanceTimeBy(10_000L)
            testScheduler.runCurrent()
            assertEquals(3, refreshCalls.get())
            assertEquals(1, maxConcurrent.get())
        }

    @Test
    fun `markAssetRenewed advances expiry date, auto-rolls autoRenew assets on init, and preserves tags in editor draft`() =
        runTest(testDispatcher) {
            val storeFile = File(tempDir, "renewal_vm_store.json")
            val fleetRepo = FleetRepositoryImpl(
                storeFile = storeFile,
                tokenCipher = cipher,
                ioDispatcher = testDispatcher,
            )
            val overdueAsset = fleetRepo.upsertAsset(
                VpsAssetRecord(
                    id = "overdue-monthly",
                    nickname = "HK Monthly",
                    providerName = "ClawCloud",
                    specsSummary = "2C / 2G",
                    region = "香港",
                    tags = listOf("CN2 GIA", "优化直连"),
                    renewalAmount = "4.00",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.MONTHLY,
                    expiryDateIso = "2026-09-15",
                    autoRenew = false,
                ),
            )
            fleetRepo.upsertAsset(
                VpsAssetRecord(
                    id = "auto-annual",
                    nickname = "JP Auto",
                    providerName = "DMIT",
                    specsSummary = "1C / 1G",
                    region = "东京",
                    tags = listOf("BGP"),
                    renewalAmount = "39.90",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = "2026-11-10",
                    autoRenew = true,
                ),
            )

            val fxRepo = ExchangeRateRepository(
                cacheFile = File(tempDir, "fx_cache_renewal.json"),
                endpointUrl = "http://127.0.0.1:1/fx",
                ioDispatcher = testDispatcher,
            )
            val vm = MyServersViewModel(
                fleetRepository = fleetRepo,
                exchangeRateRepository = fxRepo,
                todayProvider = { LocalDate.of(2026, 11, 20) },
                initialTab = MyServersTab.ASSETS,
            )
            val snackbars = mutableListOf<String>()
            val snackJob = backgroundScope.launch(testDispatcher) {
                vm.snackbarMessages.collect { snackbars += it }
            }
            advanceUntilIdle()

            // 1. Auto-renew expired asset rolled forward automatically during init
            val rolledAuto = vm.uiState.value.assets.first { it.id == "auto-annual" }
            assertEquals("2027-11-10", rolledAuto.expiryDateIso)

            // 2. Editor draft preserves multi-select tags and autoRenew flag
            vm.openEditAssetSheet(rolledAuto)
            advanceUntilIdle()
            val draft = vm.uiState.value.assetEditorDraft
            assertNotNull(draft)
            assertEquals(listOf("BGP"), draft!!.tags)
            assertTrue(draft.autoRenew)
            vm.closeAssetEditor()

            // 3. markAssetRenewed rolls overdue monthly asset past 2026-11-20 (3 cycles -> 2026-12-15)
            snackbars.clear()
            vm.markAssetRenewed(overdueAsset.id)
            advanceUntilIdle()

            val renewed = vm.uiState.value.assets.first { it.id == overdueAsset.id }
            assertEquals("2026-12-15", renewed.expiryDateIso)
            assertTrue(
                "Expected renewal confirmation snackbar with new date and cycle count, got $snackbars",
                snackbars.any { it.contains("2026-12-15") && it.contains("3 个周期") },
            )

            // 4. Read-only store surfaces Chinese error without corrupting state
            assertTrue(storeFile.setWritable(false))
            snackbars.clear()
            vm.markAssetRenewed(overdueAsset.id)
            testScheduler.runCurrent()
            advanceUntilIdle()
            assertEquals("2026-12-15", vm.uiState.value.assets.first { it.id == overdueAsset.id }.expiryDateIso)
            assertTrue(
                "Expected failure snackbar, got: $snackbars",
                snackbars.any { it.contains("标记已续费失败") },
            )

            snackJob.cancel()
        }
}
