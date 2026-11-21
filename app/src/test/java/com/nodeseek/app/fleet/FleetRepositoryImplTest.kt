package com.nodeseek.app.fleet

import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.fleet.ProbePanelClient
import com.nodeseek.app.data.fleet.SoftwareAesGcmTokenCipher
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class FleetRepositoryImplTest {

    private lateinit var tempDir: File
    private lateinit var cipher: SoftwareAesGcmTokenCipher

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("fleet_repo_impl_test").toFile()
        cipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
    }

    @After
    fun tearDown() {
        tempDir.walkBottomUp().forEach { file ->
            file.setReadable(true)
            file.setWritable(true)
        }
        tempDir.deleteRecursively()
    }

    @Test
    fun `upsertAsset throws IOException when ledger path is unwritable and keeps memory matching reopened repo`() = runTest {
        // Parent path is a regular file, so creating/writing fleet_store.json beneath it must fail on disk.
        val blockerFile = File(tempDir, "not_a_directory").apply {
            writeText("occupied", Charsets.UTF_8)
        }
        val unwritableStoreFile = File(blockerFile, "fleet_store.json")
        val repo = FleetRepositoryImpl(
            storeFile = unwritableStoreFile,
            tokenCipher = cipher,
        )

        try {
            repo.upsertAsset(sampleAsset(id = "asset-1", nickname = "瓦工 HK85"))
            fail("Expected IOException when saving asset to unwritable ledger path")
        } catch (_: IOException) {
            // expected
        }

        assertTrue("In-memory assets must remain empty on disk failure", repo.assetsFlow.value.isEmpty())

        val reopenedRepo = FleetRepositoryImpl(
            storeFile = unwritableStoreFile,
            tokenCipher = cipher,
        )
        reopenedRepo.loadPersistedState()
        assertEquals(repo.assetsFlow.value, reopenedRepo.assetsFlow.value)
    }

    @Test
    fun `failed mutations on read-only ledger preserve existing persisted state in memory and on reopen`() = runTest {
        val storeFile = File(tempDir, "fleet_store.json")
        val repo = FleetRepositoryImpl(
            storeFile = storeFile,
            tokenCipher = cipher,
        )

        val savedPanel = repo.upsertPanel(
            ProbePanelConfig(
                id = "panel-1",
                name = "主力哪吒",
                baseUrl = "https://probe.example.com",
                panelType = ProbePanelType.NEZHA_V1,
                apiToken = "secret-token",
            ),
        )
        val savedAsset = repo.upsertAsset(sampleAsset(id = "asset-1", nickname = "DMIT LAX Pro"))
        val initialSettings = FleetMonitorSettings(
            backgroundMonitorEnabled = true,
            pollIntervalMinutes = 30,
            wifiOnlyInBackground = true,
        )
        repo.updateMonitorSettings(initialSettings)

        // Lock store file read-only so subsequent disk writes fail while reads still succeed.
        assertTrue(storeFile.setWritable(false))

        assertThrowsIoException {
            repo.upsertAsset(sampleAsset(id = "asset-2", nickname = "New Unsaved Box"))
        }
        assertThrowsIoException {
            repo.deleteAsset(savedAsset.id)
        }
        assertThrowsIoException {
            repo.linkAssetToProbe(savedAsset.id, "panel-1:99")
        }
        assertThrowsIoException {
            repo.upsertPanel(savedPanel.copy(name = "Modified Name"))
        }
        assertThrowsIoException {
            repo.deletePanel(savedPanel.id)
        }
        assertThrowsIoException {
            repo.updateMonitorSettings(initialSettings.copy(backgroundMonitorEnabled = false))
        }
        assertThrowsIoException {
            repo.evaluateAlerts(today = LocalDate.of(2026, 11, 20), nowEpochMillis = 50_000L)
        }

        assertEquals(listOf(savedAsset), repo.assetsFlow.value)
        assertEquals(listOf(savedPanel), repo.panelsFlow.value)
        assertEquals(initialSettings, repo.monitorSettingsFlow.value)
        assertFalse(
            "Temporary .tmp file must be cleaned up on write failure",
            File(tempDir, "fleet_store.json.tmp").exists(),
        )

        val reopenedRepo = FleetRepositoryImpl(
            storeFile = storeFile,
            tokenCipher = cipher,
        )
        reopenedRepo.loadPersistedState()
        assertEquals(repo.assetsFlow.value, reopenedRepo.assetsFlow.value)
        assertEquals(repo.panelsFlow.value, reopenedRepo.panelsFlow.value)
        assertEquals(repo.monitorSettingsFlow.value, reopenedRepo.monitorSettingsFlow.value)
    }

    @Test
    fun `refreshAllPanels and refreshPanel do not mutate in-memory snapshots when disk cache write fails`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val nowMillis = 1_763_560_845_000L
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"success":true,"data":[{"id":1,"name":"HK-Edge","last_active":"2025-11-19T14:00:40Z","host":{"mem_total":1024},"state":{"cpu":10.0,"mem_used":256,"uptime":500}}]}""",
                ),
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"success":true,"data":[{"id":1,"name":"HK-Edge","last_active":"2025-11-19T14:00:40Z","host":{"mem_total":1024},"state":{"cpu":10.0,"mem_used":256,"uptime":500}}]}""",
                ),
            )

            val storeFile = File(tempDir, "refresh_store.json")
            val repo = FleetRepositoryImpl(
                storeFile = storeFile,
                probeClient = ProbePanelClient(nowMillisProvider = { nowMillis }),
                tokenCipher = cipher,
                nowMillisProvider = { nowMillis },
            )
            repo.upsertPanel(
                ProbePanelConfig(
                    id = "panel-live",
                    name = "Live Panel",
                    baseUrl = server.url("/").toString(),
                    panelType = ProbePanelType.NEZHA_V1,
                ),
            )

            assertTrue(storeFile.setWritable(false))

            assertThrowsIoException {
                repo.refreshAllPanels()
            }
            assertFalse(repo.isRefreshingFlow.value)
            assertTrue(repo.snapshotsFlow.value.isEmpty())

            val singleOutcome = repo.refreshPanel("panel-live")
            assertTrue(singleOutcome.isFailure)
            assertTrue(repo.snapshotsFlow.value.isEmpty())

            val reopenedRepo = FleetRepositoryImpl(
                storeFile = storeFile,
                tokenCipher = cipher,
            )
            reopenedRepo.loadPersistedState()
            assertEquals(repo.snapshotsFlow.value, reopenedRepo.snapshotsFlow.value)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `isPersistedStateLoadedFlow starts false on fresh instance and turns true after loadPersistedState`() = runTest {
        val storeFile = File(tempDir, "loaded_flag_store.json")
        val writerRepo = FleetRepositoryImpl(storeFile = storeFile, tokenCipher = cipher)
        writerRepo.upsertAsset(sampleAsset(id = "a1", nickname = "JP Tokyo"))

        val coldRepo = FleetRepositoryImpl(storeFile = storeFile, tokenCipher = cipher)
        assertFalse(coldRepo.isPersistedStateLoadedFlow.value)
        assertTrue(coldRepo.assetsFlow.value.isEmpty())

        coldRepo.loadPersistedState()
        assertTrue(coldRepo.isPersistedStateLoadedFlow.value)
        assertEquals(1, coldRepo.assetsFlow.value.size)
        assertEquals("JP Tokyo", coldRepo.assetsFlow.value.single().nickname)
    }

    private suspend fun assertThrowsIoException(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected IOException to be thrown")
        } catch (_: IOException) {
            // expected
        }
    }

    private fun sampleAsset(
        id: String,
        nickname: String,
    ) = VpsAssetRecord(
        id = id,
        nickname = nickname,
        providerName = "DMIT",
        specsSummary = "2C / 2G / 40G NVMe",
        region = "洛杉矶",
        routeTag = "CN2 GIA",
        renewalAmount = "49.90",
        currency = ValuationCurrency.USD,
        cyclePreset = BillingCyclePreset.ANNUAL,
        expiryDateIso = "2026-11-25",
        remindDaysBeforeExpiry = 7,
        notes = "测试备注",
    )
}
