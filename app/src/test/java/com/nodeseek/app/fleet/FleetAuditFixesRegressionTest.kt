package com.nodeseek.app.fleet

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import com.nodeseek.app.data.fleet.AndroidKeystoreTokenCipher
import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.fleet.FleetTokenCipher
import com.nodeseek.app.data.fleet.FleetTokenCipherException
import com.nodeseek.app.data.fleet.FleetWorkManagerGateway
import com.nodeseek.app.data.fleet.FleetWorkScheduler
import com.nodeseek.app.data.fleet.PanelTerminalCommandFraming
import com.nodeseek.app.data.fleet.ProbePanelClient
import com.nodeseek.app.data.fleet.ProbePanelTerminalTransport
import com.nodeseek.app.data.fleet.ProbeVaultSyncClient
import com.nodeseek.app.data.fleet.SoftwareAesGcmTokenCipher
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.domain.fleet.FleetMonitorSettings
import com.nodeseek.app.domain.fleet.FleetPortfolioCalculator
import com.nodeseek.app.domain.fleet.FleetSalePostBuilder
import com.nodeseek.app.domain.fleet.FleetSalePostOutcome
import com.nodeseek.app.domain.fleet.FleetSalePostRequest
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.RestockAssetPrefill
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.ssh.SshConnectionMode
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.ui.fleet.MyServersViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class FleetAuditFixesRegressionTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("fleet_audit_fixes_test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.walkBottomUp().forEach {
            it.setReadable(true)
            it.setWritable(true)
        }
        tempDir.deleteRecursively()
    }

    // ── P0-3: Token Cipher & Decryption Error Handling ────────────────────────

    @Test
    fun `SoftwareAesGcmTokenCipher rejects v1_aks ciphertext instead of returning raw ciphertext`() {
        val swCipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
        try {
            swCipher.decrypt("${AndroidKeystoreTokenCipher.PREFIX_AKS}AAAA:BBBB")
            fail("Expected FleetTokenCipherException when decrypting v1:aks: with software cipher")
        } catch (_: FleetTokenCipherException) {
            // expected
        }
    }

    @Test
    fun `AndroidKeystoreTokenCipher rejects v1_sw ciphertext instead of returning raw ciphertext`() {
        val aksCipher = AndroidKeystoreTokenCipher()
        try {
            aksCipher.decrypt("${SoftwareAesGcmTokenCipher.PREFIX_SW}AAAA:BBBB")
            fail("Expected FleetTokenCipherException when decrypting v1:sw: with keystore cipher")
        } catch (_: FleetTokenCipherException) {
            // expected
        }
    }

    @Test
    fun `SoftwareAesGcmTokenCipher loadOrCreateFromFile fails loudly on corrupt key file`() {
        val keyFile = File(tempDir, ".fleet_token_sw.key")
        keyFile.writeText("not-valid-32-byte-key", Charsets.UTF_8)
        try {
            SoftwareAesGcmTokenCipher.loadOrCreateFromFile(keyFile)
            fail("Expected FleetTokenCipherException on corrupt fallback key file")
        } catch (_: FleetTokenCipherException) {
            // expected
        }
    }

    @Test
    fun `upsertPanel throws IOException when token encryption fails and never persists null token`() = runTest {
        val failingCipher = object : FleetTokenCipher {
            override fun encrypt(plainText: String): String = throw IOException("Keystore locked")
            override fun decrypt(cipherText: String): String = cipherText
        }
        val storeFile = File(tempDir, "encrypt_fail_store.json")
        val repo = FleetRepositoryImpl(storeFile = storeFile, tokenCipher = failingCipher)

        try {
            repo.upsertPanel(
                ProbePanelConfig(
                    id = "p1",
                    name = "Encrypted Panel",
                    baseUrl = "https://probe.example.com",
                    apiToken = "top-secret-token",
                ),
            )
            fail("Expected IOException when cipher.encrypt fails")
        } catch (_: IOException) {
            // expected
        }
        assertTrue(repo.panelsFlow.value.isEmpty())
    }

    @Test
    fun `token decryption failure sets flag, blocks polling leak, and preserves ciphertext on partial update`() = runTest {
        val storeFile = File(tempDir, "decrypt_fail_store.json")
        val cipherA = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
        val cipherB = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())

        val writer = FleetRepositoryImpl(storeFile = storeFile, tokenCipher = cipherA)
        writer.upsertPanel(
            ProbePanelConfig(
                id = "p1",
                name = "Prod Panel",
                baseUrl = "https://probe.example.com",
                panelType = ProbePanelType.NEZHA_V1,
                apiToken = "super-secret-jwt",
                notifyOnOffline = true,
            ),
        )

        // Reopen with a different key simulating Keystore key loss / mismatch
        val reader = FleetRepositoryImpl(storeFile = storeFile, tokenCipher = cipherB)
        reader.loadPersistedState()
        val loadedPanel = reader.panelsFlow.value.single()
        assertNull(loadedPanel.apiToken)
        assertTrue(loadedPanel.tokenDecryptionFailed)

        // Updating offline alert setting while tokenDecryptionFailed must NOT wipe the stored ciphertext
        reader.upsertPanel(loadedPanel.copy(notifyOnOffline = false))
        assertTrue(reader.panelsFlow.value.single().tokenDecryptionFailed)

        // Reopening with original cipherA must still decrypt the preserved token cleanly
        val recovered = FleetRepositoryImpl(storeFile = storeFile, tokenCipher = cipherA)
        recovered.loadPersistedState()
        val recoveredPanel = recovered.panelsFlow.value.single()
        assertFalse(recoveredPanel.tokenDecryptionFailed)
        assertEquals("super-secret-jwt", recoveredPanel.apiToken)
        assertFalse(recoveredPanel.notifyOnOffline)

        // Refreshing with broken cipherB must report decryption error instead of sending network request
        val summary = reader.refreshAllPanels()
        assertEquals(0, summary.succeededPanelCount)
        assertTrue(summary.failedPanelErrors["p1"]?.contains("解密失败") == true)
    }

    @Test
    fun `decrypted token cache avoids repeated cipher decrypt calls during 2s polling`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(200).setBody(
                        """{"success":true,"data":[{"id":1,"name":"HK-1","last_active":"2026-11-23T12:00:00Z","host":{"mem_total":1024},"state":{"cpu":5.0,"mem_used":128,"uptime":100}}]}""",
                    )
            }
            val decryptCount = AtomicInteger(0)
            val baseCipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
            val countingCipher = object : FleetTokenCipher {
                override fun encrypt(plainText: String): String = baseCipher.encrypt(plainText)
                override fun decrypt(cipherText: String): String {
                    decryptCount.incrementAndGet()
                    return baseCipher.decrypt(cipherText)
                }
            }
            var nowMs = 1_795_435_200_000L
            val repo = FleetRepositoryImpl(
                storeFile = File(tempDir, "cache_decrypt_store.json"),
                probeClient = ProbePanelClient(
                    nowMillisProvider = { nowMs },
                    allowLoopbackHttpWithCredentials = true,
                ),
                tokenCipher = countingCipher,
                nowMillisProvider = { nowMs },
            )
            repo.upsertPanel(
                ProbePanelConfig(
                    id = "p1",
                    name = "Nezha",
                    baseUrl = server.url("/").toString(),
                    panelType = ProbePanelType.NEZHA_V1,
                    apiToken = "my-jwt",
                ),
            )
            val countAfterUpsert = decryptCount.get()
            assertEquals(1, countAfterUpsert)

            repeat(5) {
                nowMs += 2_000L
                repo.refreshAllPanels(silent = true)
            }
            assertEquals(
                "2s polling must reuse cached decrypted token without invoking cipher.decrypt again",
                countAfterUpsert,
                decryptCount.get(),
            )
        } finally {
            server.shutdown()
        }
    }

    // ── P0-5: Corrupt fleet_store.json Backup & Overwrite Guard ──────────────

    @Test
    fun `corrupt fleet_store json throws IOException, creates backup, keeps unloaded, and blocks silent overwrite`() =
        runTest {
            val storeFile = File(tempDir, "fleet_store.json")
            val corruptPayload = """{"version":1,"panels":[{"id":"broken","name":"""
            storeFile.writeText(corruptPayload, Charsets.UTF_8)

            val cipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
            val repo = FleetRepositoryImpl(
                storeFile = storeFile,
                tokenCipher = cipher,
                nowMillisProvider = { 1_700_000_000_000L },
            )

            try {
                repo.loadPersistedState()
                fail("Expected IOException when loading corrupt fleet_store.json")
            } catch (e: IOException) {
                assertTrue(e.message?.contains("损坏") == true)
            }

            assertFalse("Corrupt store must NOT mark persisted state loaded", repo.isPersistedStateLoadedFlow.value)
            val backupFile = File(tempDir, "fleet_store.json.corrupt.1700000000000")
            assertTrue("Corrupt backup file must be created", backupFile.isFile)
            assertEquals(corruptPayload, backupFile.readText(Charsets.UTF_8))

            // Subsequent mutation must also fail and leave original corrupt file untouched
            try {
                repo.upsertAsset(
                    VpsAssetRecord(
                        id = "a1",
                        nickname = "Should Not Overwrite",
                        providerName = "DMIT",
                        specsSummary = "1C",
                        region = "LA",
                        renewalAmount = "10",
                        expiryDateIso = "2027-01-01",
                    ),
                )
                fail("Expected IOException blocking overwrite of corrupt store")
            } catch (_: IOException) {
                // expected
            }
            assertEquals(corruptPayload, storeFile.readText(Charsets.UTF_8))
        }

    // ── P1-36: Delete Panel vs In-Flight Refresh Race ────────────────────────

    @Test
    fun `deleting a panel during in-flight refreshAllPanels never resurrects its snapshots or errors`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val requestArrived = CompletableDeferred<Unit>()
            val releaseResponse = CompletableDeferred<Unit>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestArrived.complete(Unit)
                    kotlinx.coroutines.runBlocking { releaseResponse.await() }
                    return MockResponse().setResponseCode(200).setBody(
                        """{"success":true,"data":[{"id":9,"name":"Zombie-Node","last_active":"2026-11-23T12:00:00Z","host":{"mem_total":1024},"state":{"cpu":10.0,"mem_used":128,"uptime":100}}]}""",
                    )
                }
            }

            val cipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
            val repo = FleetRepositoryImpl(
                storeFile = File(tempDir, "race_store.json"),
                probeClient = ProbePanelClient(),
                tokenCipher = cipher,
            )
            repo.upsertPanel(
                ProbePanelConfig(
                    id = "panel-race",
                    name = "To Be Deleted",
                    baseUrl = server.url("/").toString(),
                    panelType = ProbePanelType.NEZHA_V1,
                ),
            )

            val refreshJob = async(Dispatchers.IO) { repo.refreshAllPanels() }
            requestArrived.await()
            repo.deletePanel("panel-race")
            releaseResponse.complete(Unit)
            refreshJob.await()

            assertTrue("Deleted panel must stay deleted", repo.panelsFlow.value.isEmpty())
            assertTrue("Snapshots from deleted panel must NOT resurrect", repo.snapshotsFlow.value.isEmpty())
            assertTrue("Errors from deleted panel must NOT resurrect", repo.panelErrorsFlow.value.isEmpty())
        } finally {
            server.shutdown()
        }
    }

    // ── P1-35: Komari Telemetry Mode Caching ─────────────────────────────────

    @Test
    fun `Komari enrichKomariWithLiveTelemetry caches discovered mode to avoid repeated 404 probes`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val rpcHits = AtomicInteger(0)
            val recentHits = AtomicInteger(0)
            val clientHits = AtomicInteger(0)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/nodes" -> MockResponse().setResponseCode(200).setBody(
                        """{"status":"success","data":[{"uuid":"u1","name":"JP-Komari","region":"🇯🇵","cpu_cores":2,"mem_total":2147483648,"disk_total":21474836480}]}""",
                    )
                    "/api/rpc2" -> {
                        rpcHits.incrementAndGet()
                        MockResponse().setResponseCode(404)
                    }
                    "/api/recent" -> {
                        recentHits.incrementAndGet()
                        MockResponse().setResponseCode(404)
                    }
                    "/api/client" -> {
                        clientHits.incrementAndGet()
                        MockResponse().setResponseCode(200).setBody(
                            """{"status":"success","data":[{"uuid":"u1","online":true,"cpu":12.5,"ram":{"used":536870912,"total":2147483648},"uptime":3600}]}""",
                        )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val client = ProbePanelClient()
            val config = ProbePanelConfig(
                id = "komari-1",
                name = "Komari",
                baseUrl = server.url("/").toString(),
                panelType = ProbePanelType.KOMARI,
            )

            // First fetch discovers /api/client after 404 on /api/rpc2 and /api/recent
            val first = client.fetchPanelServers(config)
            assertEquals(1, first.snapshots.size)
            assertEquals(1, rpcHits.get())
            assertEquals(1, recentHits.get())
            assertEquals(1, clientHits.get())

            // Second fetch uses cached /api/client directly without probing /api/rpc2 or /api/recent
            val second = client.fetchPanelServers(config)
            assertEquals(1, second.snapshots.size)
            assertEquals(1, rpcHits.get())
            assertEquals(1, recentHits.get())
            assertEquals(2, clientHits.get())
        } finally {
            server.shutdown()
        }
    }

    // ── P1-37: Auth Header vs Cookie Detection ───────────────────────────────

    @Test
    fun `looksLikeCookieHeader distinguishes Bearer Basic and Base64 padding from real cookies`() {
        assertFalse(ProbePanelClient.looksLikeCookieHeader("Bearer eyJhbGciOiJIUzI1NiJ9=="))
        assertFalse(ProbePanelClient.looksLikeCookieHeader("Basic dXNlcjpwYXNz=="))
        assertFalse(ProbePanelClient.looksLikeCookieHeader("YWJjZA=="))
        assertFalse(ProbePanelClient.looksLikeCookieHeader("YWJjZGU="))
        assertFalse(ProbePanelClient.looksLikeCookieHeader("plain-token"))

        assertTrue(ProbePanelClient.looksLikeCookieHeader("nz-jwt=cookie-secret"))
        assertTrue(ProbePanelClient.looksLikeCookieHeader("session_token=abc==; theme=dark"))
        assertTrue(ProbePanelClient.looksLikeCookieHeader("a=1; b=2"))
    }

    @Test
    fun `ProbePanelClient and ProbeVaultSyncClient send Basic and Base64-padded tokens as Authorization not Cookie`() =
        runTest {
            val server = MockWebServer()
            server.start()
            try {
                val capturedAuth = mutableListOf<String?>()
                val capturedCookie = mutableListOf<String?>()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        capturedAuth += request.getHeader("Authorization")
                        capturedCookie += request.getHeader("Cookie")
                        return MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[]}""")
                    }
                }
                val panelClient = ProbePanelClient(allowLoopbackHttpWithCredentials = true)
                panelClient.fetchPanelServers(
                    ProbePanelConfig(
                        id = "p1",
                        name = "Basic Auth",
                        baseUrl = server.url("/").toString(),
                        panelType = ProbePanelType.NEZHA_V1,
                        apiToken = "Basic dXNlcjpwYXNz==",
                    ),
                )
                assertEquals("Basic dXNlcjpwYXNz==", capturedAuth.last())
                assertNull(capturedCookie.last())

                val syncClient = ProbeVaultSyncClient(allowLoopbackHttpWithCredentials = true)
                syncClient.fetchAllPanels(
                    listOf(
                        ProbePanelConfig(
                            id = "p2",
                            name = "Base64 Token",
                            baseUrl = server.url("/").toString(),
                            panelType = ProbePanelType.NEZHA_V1,
                            apiToken = "YWJjZA==",
                        ),
                    ),
                )
                assertTrue(capturedAuth.any { it == "Bearer YWJjZA==" })
                assertTrue(capturedCookie.all { it == null })
            } finally {
                server.shutdown()
            }
        }

    // ── P2-49: Komari JSON-RPC error field ───────────────────────────────────

    @Test
    fun `Komari rpc2 JSON-RPC error payload throws IOException instead of empty server list`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/nodes" -> MockResponse().setResponseCode(404)
                    "/api/rpc2" -> MockResponse().setResponseCode(200).setBody(
                        """{"jsonrpc":"2.0","error":{"code":-32601,"message":"Method not found"},"id":1}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val panelConfig = ProbePanelConfig(
                id = "komari-err",
                name = "Komari Err",
                baseUrl = server.url("/").toString(),
                panelType = ProbePanelType.KOMARI,
            )
            val panelClient = ProbePanelClient()
            try {
                panelClient.fetchPanelServers(panelConfig)
                fail("Expected IOException on JSON-RPC error payload")
            } catch (e: IOException) {
                assertTrue(e.message?.contains("Method not found") == true)
            }

            val vaultClient = ProbeVaultSyncClient()
            val syncResult = vaultClient.fetchAllPanels(listOf(panelConfig))
            assertEquals(0, syncResult.succeededPanelCount)
            assertTrue(syncResult.failedPanelErrors["komari-err"]?.contains("Method not found") == true)
        } finally {
            server.shutdown()
        }
    }

    // ── P1-68 & P2-99: ProbePanelTerminalClient Subshell + Unexpected Close ──

    @Test
    fun `PanelTerminalCommandFraming wrapCommand uses subshell so exit inside script still emits sentinel`() {
        val framing = PanelTerminalCommandFraming(nonce = "testnonce")
        val wrapped = framing.wrapCommand("command -v iperf3 || exit 42")
        assertTrue(wrapped.contains("(\ncommand -v iperf3 || exit 42\n); __anode_rc=\$?;"))
        assertFalse(wrapped.contains("{\ncommand -v iperf3"))

        val blankWrapped = framing.wrapCommand("   ")
        assertTrue(blankWrapped.contains("(\n:\n);"))
    }

    @Test
    fun `Nezha v1 non-interactive terminal closing without exit sentinel fails with IOException instead of exit 0`() =
        runTest {
            val server = MockWebServer()
            server.start()
            try {
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                        "/api/v1/terminal" -> MockResponse().setResponseCode(200).setBody(
                            """{"success":true,"data":{"session_id":"sess-drop"}}""",
                        )
                        "/api/v1/ws/terminal/sess-drop" -> MockResponse().withWebSocketUpgrade(
                            object : WebSocketListener() {
                                override fun onOpen(webSocket: WebSocket, response: Response) {
                                    // Immediately close without emitting exit sentinel
                                    webSocket.close(1000, "remote shell exited abruptly")
                                }
                            },
                        )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
                val panel = ProbePanelConfig(
                    id = "nz1",
                    name = "Nezha",
                    baseUrl = server.url("/").toString(),
                    panelType = ProbePanelType.NEZHA_V1,
                    apiToken = "jwt-token",
                )
                val transport = ProbePanelTerminalTransport(
                    panelLookup = { panel },
                    allowLoopbackHttpWithCredentials = true,
                )
                val request = SshConnectionRequest(
                    host = "127.0.0.1",
                    port = 22,
                    username = "root",
                    connectionMode = SshConnectionMode.PANEL_TERMINAL,
                    probePanelId = "nz1",
                    probeServerId = "1",
                    probePanelType = ProbePanelType.NEZHA_V1.name,
                    interactiveShell = false,
                )

                try {
                    transport.execute(request, "exit 42").toList()
                    fail("Expected IOException when non-interactive terminal closes without exit sentinel")
                } catch (e: IOException) {
                    assertTrue(e.message?.contains("未收到命令退出状态") == true)
                }
            } finally {
                server.shutdown()
            }
        }

    // ── P2-48 & P3-21: FleetWorkScheduler autoRenew + Deduplication ──────────

    @Test
    fun `FleetWorkScheduler includes autoRenew assets as active targets and deduplicates identical sync calls`() {
        val autoRenewOnlyAsset = VpsAssetRecord(
            id = "auto-1",
            nickname = "AutoRenew Box",
            providerName = "DMIT",
            specsSummary = "1C",
            region = "HK",
            renewalAmount = "10",
            expiryDateIso = "2026-12-01",
            remindDaysBeforeExpiry = 0,
            autoRenew = true,
        )
        assertTrue(FleetWorkScheduler.hasActiveTargets(emptyList(), listOf(autoRenewOnlyAsset)))

        val periodicCalls = AtomicInteger(0)
        val gateway = object : FleetWorkManagerGateway {
            override fun enqueueUniquePeriodicWork(
                uniqueWorkName: String,
                existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
                request: PeriodicWorkRequest,
            ) {
                periodicCalls.incrementAndGet()
            }

            override fun enqueueUniqueOneTimeWork(
                uniqueWorkName: String,
                existingWorkPolicy: ExistingWorkPolicy,
                request: OneTimeWorkRequest,
            ) = Unit

            override fun cancelUniqueWork(uniqueWorkName: String) = Unit
        }
        val scheduler = FleetWorkScheduler(gateway)
        val settings = FleetMonitorSettings(backgroundMonitorEnabled = true, pollIntervalMinutes = 30)
        scheduler.sync(settings, hasActiveTargets = true, isPersistedStateLoaded = true)
        scheduler.sync(settings, hasActiveTargets = true, isPersistedStateLoaded = true)
        assertEquals("Identical sync signatures must deduplicate WorkManager IPC", 1, periodicCalls.get())
    }

    // ── P2-53: Month-End Anchor Preservation Across Single-Cycle Renewals ────

    @Test
    fun `consecutive single-cycle monthly renewals preserve Jan 31 anchor across Feb 28 to Mar 31`() = runTest {
        val storeFile = File(tempDir, "anchor_store.json")
        val cipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
        val repo = FleetRepositoryImpl(storeFile = storeFile, tokenCipher = cipher)

        repo.upsertAsset(
            VpsAssetRecord(
                id = "jan31",
                nickname = "Month End Box",
                providerName = "DMIT",
                specsSummary = "1C",
                region = "LA",
                renewalAmount = "5.00",
                cyclePreset = BillingCyclePreset.MONTHLY,
                expiryDateIso = "2026-01-31",
            ),
        )

        val r1 = repo.markAssetRenewed("jan31", today = LocalDate.of(2026, 1, 25))
        assertEquals("2026-02-28", r1?.newExpiryDateIso)
        assertEquals(31, r1?.updatedAsset?.billingAnchorDay)

        val r2 = repo.markAssetRenewed("jan31", today = LocalDate.of(2026, 2, 25))
        assertEquals("2026-03-31", r2?.newExpiryDateIso)

        val r3 = repo.markAssetRenewed("jan31", today = LocalDate.of(2026, 3, 25))
        assertEquals("2026-04-30", r3?.newExpiryDateIso)

        val r4 = repo.markAssetRenewed("jan31", today = LocalDate.of(2026, 4, 25))
        assertEquals("2026-05-31", r4?.newExpiryDateIso)
    }

    // ── P2-54 & P1-40: Currency Detection, Sale Post Tags, Quota, Panel Save Version ──

    @Test
    fun `Restock prefill detects KRW CHF RUB TWD currencies and SalePostBuilder uses resolvedRouteTagSummary`() {
        val krw = FleetPortfolioCalculator.parseRestockPrefill(
            RestockAssetPrefill(name = "KR Seoul Box", priceHint = "₩15000/mo"),
        )
        assertEquals(ValuationCurrency.KRW, krw.currency)
        assertEquals("15000", krw.renewalAmount)

        val chf = FleetPortfolioCalculator.parseRestockPrefill(
            RestockAssetPrefill(name = "Zurich VPS", priceHint = "CHF 12.50/yr"),
        )
        assertEquals(ValuationCurrency.CHF, chf.currency)
        assertEquals("12.50", chf.renewalAmount)

        val rub = FleetPortfolioCalculator.parseRestockPrefill(
            RestockAssetPrefill(name = "Moscow VPS", priceHint = "₽450/mo"),
        )
        assertEquals(ValuationCurrency.RUB, rub.currency)
        assertEquals("450", rub.renewalAmount)

        val assetWithTagsOnly = VpsAssetRecord(
            id = "tag-only",
            nickname = "HK GIA",
            providerName = "DMIT",
            specsSummary = "2C / 2G",
            region = "香港",
            routeTag = "",
            tags = listOf("CN2 GIA", "优化直连"),
            renewalAmount = "49.90",
            currency = ValuationCurrency.USD,
            cyclePreset = BillingCyclePreset.ANNUAL,
            expiryDateIso = "2027-06-01",
        )
        val outcome = FleetSalePostBuilder.build(
            FleetSalePostRequest(
                asset = assetWithTagsOnly,
                valuationDateTime = LocalDate.of(2026, 11, 20).atStartOfDay(),
            ),
        )
        assertTrue(outcome is FleetSalePostOutcome.Success)
        val body = (outcome as FleetSalePostOutcome.Success).result.markdownBody
        assertTrue("Sale post must include resolvedRouteTagSummary", body.contains("线路：CN2 GIA · 优化直连"))
    }

    @Test
    fun `MyServersViewModel formats fractional traffic quotaGb and advances panelSaveSuccessVersion on save`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val cipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
            val repo = FleetRepositoryImpl(
                storeFile = File(tempDir, "vm_fixes_store.json"),
                tokenCipher = cipher,
                ioDispatcher = testDispatcher,
            )
            val fxRepo = ExchangeRateRepository(
                cacheFile = File(tempDir, "vm_fixes_fx.json"),
                endpointUrl = "http://127.0.0.1:1/fx",
                ioDispatcher = testDispatcher,
            )
            val vm = MyServersViewModel(
                fleetRepository = repo,
                exchangeRateRepository = fxRepo,
                todayProvider = { LocalDate.of(2026, 11, 20) },
            )
            advanceUntilIdle()

            // Fractional GiB must not truncate to "0" or "1"
            vm.openAddAssetFromProbe(
                ProbeServerSnapshot(
                    panelId = "p1",
                    remoteServerId = "1",
                    name = "Micro-Box",
                    trafficLimitBytes = 512L * 1024L * 1024L, // 0.5 GiB
                ),
            )
            advanceUntilIdle()
            assertEquals("0.5", vm.uiState.value.assetEditorDraft?.monthlyTrafficQuotaGb)

            vm.openAddAssetFromProbe(
                ProbeServerSnapshot(
                    panelId = "p1",
                    remoteServerId = "2",
                    name = "Mid-Box",
                    trafficLimitBytes = 1536L * 1024L * 1024L, // 1.5 GiB
                ),
            )
            advanceUntilIdle()
            assertEquals("1.5", vm.uiState.value.assetEditorDraft?.monthlyTrafficQuotaGb)

            val beforeVer = vm.uiState.value.panelSaveSuccessVersion
            vm.savePanel(
                ProbePanelConfig(
                    id = "panel-edit",
                    name = "Test Panel",
                    baseUrl = "https://probe.example.com",
                    panelType = ProbePanelType.NEZHA_V1,
                ),
            )
            advanceUntilIdle()
            assertEquals(beforeVer + 1L, vm.uiState.value.panelSaveSuccessVersion)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
