package com.nodeseek.app.ssh

import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.fleet.ProbeVaultSyncClient
import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.ssh.InMemoryTestCipher
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.domain.ssh.ProbeSyncMachine
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshConnectionMode
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshProbeSyncMerger
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.domain.ssh.SshStdinCallbackContext
import com.nodeseek.app.domain.ssh.SshStdinWriter
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.domain.ssh.SshTransport
import com.nodeseek.app.domain.ssh.SshVaultCatalogFilter
import com.nodeseek.app.ui.ssh.LiteSshSessionManager
import com.nodeseek.app.ui.ssh.LiteSshViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SshProbeSyncAndCollapseTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `merge syncs probe machines dedupes on resync and preserves user credentials and customizations`() {
        val manualMatch = SshServerProfile(
            id = "manual-jp",
            alias = "My Custom Tokyo",
            host = "203.0.113.10",
            port = 2222,
            username = "ubuntu",
            authMethod = SshAuthMethod.PRIVATE_KEY,
            encryptedSecret = "enc:MY_PRIVATE_KEY",
            encryptedPassphrase = "enc:MY_PASSPHRASE",
            knownHostKeyFingerprint = "SHA256:existingFp1",
            knownHostKeyAlgorithm = "ssh-ed25519",
            lastConnectedEpochMillis = 1_700_000_000_000L,
            createdAtEpochMillis = 1_690_000_000_000L,
            jumpHostId = "bastion-1",
            groupTag = "主力",
        )
        val unrelatedManual = SshServerProfile(
            id = "bastion-1",
            alias = "[香港] Bastion",
            host = "198.51.100.1",
            encryptedSecret = "enc:BASTION_PW",
            createdAtEpochMillis = 1_680_000_000_000L,
        )

        val incoming = listOf(
            // Matches manualMatch by IPv4 -> should adopt & link without creating duplicate
            ProbeSyncMachine(
                panelId = "panel-nz1",
                panelName = "哪吒主力",
                panelType = ProbePanelType.NEZHA_V1,
                remoteServerId = "101",
                name = "JP-SoftBank-01",
                ipv4 = "203.0.113.10",
                countryCode = "JP",
                groupName = "日本",
                tags = listOf("软银", "CN2"),
                isOnline = true,
                hasPanelToken = true,
            ),
            // New Nezha v1 machine with token -> defaults to PANEL_TERMINAL
            ProbeSyncMachine(
                panelId = "panel-nz1",
                panelName = "哪吒主力",
                panelType = ProbePanelType.NEZHA_V1,
                remoteServerId = "102",
                name = "SG-Cera",
                ipv4 = "198.51.100.44",
                countryCode = "SG",
                groupName = "新加坡",
                tags = listOf("新加坡"),
                isOnline = true,
                hasPanelToken = true,
            ),
            // Duplicate of 102 in same batch -> must be deduplicated
            ProbeSyncMachine(
                panelId = "panel-nz1",
                panelName = "哪吒主力",
                panelType = ProbePanelType.NEZHA_V1,
                remoteServerId = "102",
                name = "SG-Cera",
                ipv4 = "198.51.100.44",
                countryCode = "SG",
                groupName = "新加坡",
                tags = listOf("直连"),
                isOnline = true,
                hasPanelToken = true,
            ),
            // New Komari guest machine (no IP, no token) -> still synced, defaults to DIRECT_SSH
            ProbeSyncMachine(
                panelId = "panel-km",
                panelName = "Komari 公开",
                panelType = ProbePanelType.KOMARI,
                remoteServerId = "uuid-km-1",
                name = "HK-Edge",
                ipv4 = "",
                countryCode = "HK",
                groupName = "",
                tags = listOf("HKT"),
                isOnline = true,
                hasPanelToken = false,
            ),
        )

        var seq = 0
        val first = SshProbeSyncMerger.merge(
            existingServers = listOf(manualMatch, unrelatedManual),
            incomingMachines = incoming,
            nowEpochMillis = 1_750_000_000_000L,
            idGenerator = { "gen-${++seq}" },
        )

        assertEquals(3, first.totalSyncedMachines)
        assertEquals(2, first.addedCount)
        assertEquals(1, first.linkedManualCount)
        assertEquals(0, first.updatedCount)
        assertEquals(4, first.mergedServers.size)

        // Adopted manual entry keeps ALL credentials, port, username, alias, group, jumpHost, and DIRECT_SSH mode
        val adopted = first.mergedServers.first { it.id == "manual-jp" }
        assertEquals("My Custom Tokyo", adopted.alias)
        assertEquals("主力", adopted.resolvedGroup)
        assertEquals("203.0.113.10", adopted.host)
        assertEquals(2222, adopted.port)
        assertEquals("ubuntu", adopted.username)
        assertEquals(SshAuthMethod.PRIVATE_KEY, adopted.authMethod)
        assertEquals("enc:MY_PRIVATE_KEY", adopted.encryptedSecret)
        assertEquals("enc:MY_PASSPHRASE", adopted.encryptedPassphrase)
        assertEquals("SHA256:existingFp1", adopted.knownHostKeyFingerprint)
        assertEquals("bastion-1", adopted.jumpHostId)
        assertEquals(SshConnectionMode.DIRECT_SSH, adopted.connectionMode)
        assertTrue(adopted.isProbeSynced)
        assertEquals("panel-nz1:101", adopted.probeKey)
        assertTrue(adopted.probeRegion.contains("JP"))

        // New Nezha v1 entry with token defaults to PANEL_TERMINAL
        val sgNode = first.mergedServers.first { it.probeServerId == "102" }
        assertEquals(SshConnectionMode.PANEL_TERMINAL, sgNode.connectionMode)
        assertEquals("新加坡", sgNode.resolvedGroup)
        assertEquals("SG-Cera", sgNode.displayName)
        assertEquals("198.51.100.44", sgNode.host)
        assertTrue(sgNode.probeTags.contains("直连"))

        // New Komari guest entry without IP/token defaults to DIRECT_SSH and still syncs
        val hkNode = first.mergedServers.first { it.probeServerId == "uuid-km-1" }
        assertEquals(SshConnectionMode.DIRECT_SSH, hkNode.connectionMode)
        assertEquals("", hkNode.host)
        assertEquals("HKT", hkNode.resolvedGroup)
        assertTrue(hkNode.displayAddress.contains("Komari 公开"))

        // Re-sync with identical data is completely idempotent (0 added, 0 updated, 3 unchanged)
        val second = SshProbeSyncMerger.merge(
            existingServers = first.mergedServers,
            incomingMachines = incoming,
            nowEpochMillis = 1_750_000_060_000L,
            idGenerator = { "gen-${++seq}" },
        )
        assertEquals(4, second.mergedServers.size)
        assertEquals(0, second.addedCount)
        assertEquals(0, second.updatedCount)
        assertEquals(0, second.linkedManualCount)
        assertEquals(3, second.unchangedCount)

        // Now user enters credentials + TOFU key on sgNode, and panel renames & migrates sgNode's IP
        val withUserCreds = second.mergedServers.map { srv ->
            if (srv.probeServerId == "102") {
                srv.copy(
                    encryptedSecret = "enc:SG_USER_PW",
                    port = 22022,
                    knownHostKeyFingerprint = "SHA256:oldSgFp",
                )
            } else {
                srv
            }
        }
        val migratedIncoming = listOf(
            ProbeSyncMachine(
                panelId = "panel-nz1",
                panelName = "哪吒主力",
                panelType = ProbePanelType.NEZHA_V1,
                remoteServerId = "102",
                name = "SG-Cera-V2",
                ipv4 = "198.51.100.99",
                countryCode = "SG",
                groupName = "新加坡",
                isOnline = true,
                hasPanelToken = true,
            ),
        )
        val third = SshProbeSyncMerger.merge(
            existingServers = withUserCreds,
            incomingMachines = migratedIncoming,
            nowEpochMillis = 1_750_000_120_000L,
            idGenerator = { "gen-${++seq}" },
        )
        assertEquals(4, third.mergedServers.size)
        assertEquals(1, third.updatedCount)
        val updatedSg = third.mergedServers.first { it.probeServerId == "102" }
        assertEquals("SG-Cera-V2", updatedSg.displayName)
        assertEquals("198.51.100.99", updatedSg.host)
        // User credentials & port MUST be preserved, while stale TOFU fingerprint resets due to IP migration
        assertEquals("enc:SG_USER_PW", updatedSg.encryptedSecret)
        assertEquals(22022, updatedSg.port)
        assertNull(updatedSg.knownHostKeyFingerprint)
    }

    @Test
    fun `resync preserves manually overridden host and locally renamed alias on synced server`() {
        val initial = SshProbeSyncMerger.merge(
            existingServers = emptyList(),
            incomingMachines = listOf(
                ProbeSyncMachine(
                    panelId = "p1",
                    panelName = "Komari",
                    panelType = ProbePanelType.KOMARI,
                    remoteServerId = "u1",
                    name = "OldPanelName",
                    ipv4 = "203.0.113.5",
                    countryCode = "US",
                    hasPanelToken = true,
                ),
            ),
            nowEpochMillis = 1_000L,
            idGenerator = { "srv-1" },
        ).mergedServers

        // User overrides alias to "Custom Local Alias" and host to a custom DDNS domain
        val customized = initial.map {
            it.copy(
                alias = "[自定义分组] Custom Local Alias",
                groupTag = "自定义分组",
                host = "my-ddns.example.com",
            )
        }

        val resynced = SshProbeSyncMerger.merge(
            existingServers = customized,
            incomingMachines = listOf(
                ProbeSyncMachine(
                    panelId = "p1",
                    panelName = "Komari",
                    panelType = ProbePanelType.KOMARI,
                    remoteServerId = "u1",
                    name = "NewPanelName",
                    ipv4 = "203.0.113.88",
                    countryCode = "US",
                    hasPanelToken = true,
                ),
            ),
            nowEpochMillis = 2_000L,
            idGenerator = { "srv-2" },
        ).mergedServers

        val server = resynced.single()
        assertEquals("Custom Local Alias", server.displayName)
        assertEquals("自定义分组", server.resolvedGroup)
        assertEquals("my-ddns.example.com", server.host)
    }

    @Test
    fun `SshServerVault persists collapse state probe metadata and loads legacy v1 vault JSON cleanly`() = runTest {
        val vaultFile = File(tempFolder.root, "legacy_v1_vault.json")
        val cipher = InMemoryTestCipher()

        // Write a legacy schemaVersion=1 JSON file without any probe or collapseState fields
        val legacyJson = """
            {
              "schemaVersion": 1,
              "servers": [
                {
                  "id": "legacy-1",
                  "alias": "[香港] Legacy HK",
                  "host": "203.0.113.20",
                  "port": 22,
                  "username": "root",
                  "authMethod": "PASSWORD",
                  "encryptedSecret": "${cipher.encrypt("legacy-pw")}"
                }
              ],
              "customPresets": [],
              "recentRuns": []
            }
        """.trimIndent()
        vaultFile.writeText(legacyJson, Charsets.UTF_8)

        val vault = SshServerVault(
            vaultFile = vaultFile,
            cipher = cipher,
            ioDispatcher = testDispatcher,
            clock = { 1_760_000_000_000L },
        )

        // Legacy server deserialized safely with defaults
        assertEquals(1, vault.serversFlow.value.size)
        val legacy = vault.serversFlow.value.single()
        assertFalse(legacy.isProbeSynced)
        assertEquals(SshConnectionMode.DIRECT_SSH, legacy.connectionMode)
        assertFalse(vault.collapseStateFlow.value.isVaultListCollapsed)

        // Sync a probe machine + persist collapse state (list collapsed, group collapsed, server expanded)
        val syncOutcome = vault.syncProbeMachines(
            listOf(
                ProbeSyncMachine(
                    panelId = "p1",
                    panelName = "哪吒",
                    panelType = ProbePanelType.NEZHA_V1,
                    remoteServerId = "9",
                    name = "Tokyo Node",
                    ipv4 = "",
                    countryCode = "JP",
                    groupName = "日本",
                    hasPanelToken = true,
                ),
            ),
        )
        assertEquals(1, syncOutcome.addedCount)
        val synced = vault.serversFlow.value.first { it.isProbeSynced }
        assertEquals(SshConnectionMode.PANEL_TERMINAL, synced.connectionMode)

        // Editing probe-synced server in PANEL_TERMINAL mode allows empty host and preserves probe link
        val editedSynced = vault.saveServer(
            SshServerDraft(
                id = synced.id,
                alias = "[日本] Tokyo Node Edited",
                host = "",
                port = 22,
                username = "root",
                connectionMode = SshConnectionMode.PANEL_TERMINAL,
                groupTag = "日本",
                groupEdited = true,
            ),
        )
        assertEquals("p1:9", editedSynced.probeKey)
        assertEquals(SshConnectionMode.PANEL_TERMINAL, editedSynced.connectionMode)

        vault.updateCollapseState { state ->
            state
                .toggleVaultListCollapsed()
                .toggleGroupCollapsed("日本")
                .toggleServerExpanded(synced.id)
        }
        vault.updateServerConnectionMode(synced.id, SshConnectionMode.DIRECT_SSH)

        // Reload fresh SshServerVault from disk and verify full persistence
        val reloaded = SshServerVault(
            vaultFile = vaultFile,
            cipher = cipher,
            ioDispatcher = testDispatcher,
        )
        assertEquals(2, reloaded.serversFlow.value.size)
        val reloadedSynced = reloaded.serversFlow.value.first { it.id == synced.id }
        assertEquals("p1:9", reloadedSynced.probeKey)
        assertEquals(SshConnectionMode.DIRECT_SSH, reloadedSynced.connectionMode)

        val reloadedCollapse = reloaded.collapseStateFlow.value
        assertTrue(reloadedCollapse.isVaultListCollapsed)
        assertTrue(reloadedCollapse.isGroupCollapsed("日本"))
        assertTrue(reloadedCollapse.isServerExpanded(synced.id))

        // Deleting a server prunes it from expandedServerIds
        reloaded.deleteServer(synced.id)
        assertFalse(reloaded.collapseStateFlow.value.isServerExpanded(synced.id))
    }

    @Test
    fun `groupServersForCollapsibleVault partitions groups pins selected group and matches probe search tokens`() {
        val servers = listOf(
            SshServerProfile(
                id = "s1",
                alias = "[日本] TYO-1",
                host = "203.0.113.1",
                groupTag = "日本",
                probePanelId = "p1",
                probePanelName = "Nezha-Main",
                probePanelType = "NEZHA_V1",
                probeServerId = "10",
                probeRegion = "🇯🇵 JP",
                probeTags = listOf("软银", "日本"),
            ),
            SshServerProfile(
                id = "s2",
                alias = "[香港] HK-1",
                host = "203.0.113.2",
                groupTag = "香港",
            ),
            SshServerProfile(
                id = "s3",
                alias = "Standalone",
                host = "203.0.113.3",
            ),
        )

        val groups = SshVaultCatalogFilter.groupServersForCollapsibleVault(
            filteredServers = servers,
            selectedServerId = "s2",
        )
        assertEquals(listOf("香港", "日本", "未分组"), groups.map { it.label })

        // Search matches probe panel name, tags, region, and "探针"
        val byTag = SshVaultCatalogFilter.filterAndSortServers(
            servers = servers,
            selectedServerId = "s2",
            searchQuery = "软银 探针",
        )
        assertEquals(listOf("s1"), byTag.map { it.id })
    }

    @Test
    fun `SessionManager syncs Nezha v1 and Komari panels via HTTP persists collapse state and routes panel terminal`() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/nz/api/v1/server" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """
                            {
                              "success": true,
                              "data": [
                                {
                                  "id": 7,
                                  "name": "Nezha-JP",
                                  "geoip": {
                                    "country_code": "JP",
                                    "ip": { "ipv4_addr": "198.51.100.70" }
                                  },
                                  "state": { "uptime": 3600 }
                                }
                              ]
                            }
                            """.trimIndent(),
                        )
                    "/nz/api/v1/server-group" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """
                            {
                              "success": true,
                              "data": [
                                { "group": { "id": 1, "name": "日韩精品" }, "servers": [7] }
                              ]
                            }
                            """.trimIndent(),
                        )
                    "/km/api/admin/client/list" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """
                            {
                              "status": "success",
                              "data": [
                                {
                                  "uuid": "km-uuid-9",
                                  "name": "Komari-SG",
                                  "ipv4": "203.0.113.90",
                                  "region": "🇸🇬",
                                  "group": "新加坡",
                                  "tags": "CN2;直连"
                                }
                              ]
                            }
                            """.trimIndent(),
                        )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val fleetFile = File(tempFolder.root, "fleet_sync_test.json")
            val fleetRepo = FleetRepositoryImpl(
                storeFile = fleetFile,
                ioDispatcher = testDispatcher,
            )
            fleetRepo.upsertPanel(
                ProbePanelConfig(
                    id = "nz-1",
                    name = "哪吒面板",
                    baseUrl = server.url("/nz").toString().trimEnd('/'),
                    panelType = ProbePanelType.NEZHA_V1,
                    apiToken = "nz-jwt-secret",
                ),
            )
            fleetRepo.upsertPanel(
                ProbePanelConfig(
                    id = "km-1",
                    name = "Komari 面板",
                    baseUrl = server.url("/km").toString().trimEnd('/'),
                    panelType = ProbePanelType.KOMARI,
                    apiToken = "km-admin-key",
                ),
            )

            val vaultFile = File(tempFolder.root, "ssh_sync_vm.json")
            val vault = SshServerVault(
                vaultFile = vaultFile,
                cipher = InMemoryTestCipher(),
                ioDispatcher = testDispatcher,
            )

            val panelRequests = mutableListOf<SshConnectionRequest>()
            val capturedStdin = mutableListOf<String>()
            val panelGate = CompletableDeferred<Unit>()
            val fakePanelTransport = object : SshTransport {
                override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = callbackFlow {
                    panelRequests += request
                    trySend(SshStreamEvent.Connected)
                    coroutineContext[SshStdinCallbackContext]?.onStdinReady?.invoke(
                        SshStdinWriter { text -> capturedStdin += text },
                    )
                    trySend(SshStreamEvent.OutputChunk("root@nezha-jp:~# "))
                    panelGate.await()
                    trySend(SshStreamEvent.Completed(0))
                    close()
                }
            }
            val fakeDirectTransport = object : SshTransport {
                override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                    emit(SshStreamEvent.Connected)
                    emit(SshStreamEvent.Completed(0))
                }
            }

            val manager = LiteSshSessionManager(
                vaultRepository = vault,
                executor = LiteSshExecutor(transport = fakeDirectTransport),
                externalScope = CoroutineScope(SupervisorJob() + testDispatcher),
                enableElapsedTicker = false,
                fleetRepository = fleetRepo,
                probeSyncClientOverride = ProbeVaultSyncClient(
                    ioDispatcher = testDispatcher,
                    allowLoopbackHttpWithCredentials = true,
                ),
                panelTerminalExecutorOverride = LiteSshExecutor(transport = fakePanelTransport),
            )
            val viewModel = LiteSshViewModel(sessionManager = manager, ownsSessionManagerScope = true)

            assertEquals(2, viewModel.uiState.value.configuredProbePanelCount)

            // 1. Sync from both probe panels (await real MockWebServer HTTP responses)
            viewModel.syncFromProbePanels()
            withTimeout(5_000L) {
                viewModel.uiState.first { !it.isSyncingProbes && it.lastProbeSyncSummary != null }
            }
            val syncedServers = viewModel.uiState.value.servers
            assertEquals("Sync summary was: ${viewModel.uiState.value.lastProbeSyncSummary}", 2, syncedServers.size)
            val nzServer = syncedServers.first { it.probePanelId == "nz-1" }
            val kmServer = syncedServers.first { it.probePanelId == "km-1" }
            assertEquals("Nezha-JP", nzServer.displayName)
            assertEquals("198.51.100.70", nzServer.host)
            assertEquals("日韩精品", nzServer.resolvedGroup)
            assertEquals(SshConnectionMode.PANEL_TERMINAL, nzServer.connectionMode)

            assertEquals("Komari-SG", kmServer.displayName)
            assertEquals("203.0.113.90", kmServer.host)
            assertEquals("新加坡", kmServer.resolvedGroup)
            assertTrue(kmServer.probeTags.contains("CN2"))
            assertEquals(SshConnectionMode.PANEL_TERMINAL, kmServer.connectionMode)

            // Re-syncing is idempotent (still 2 servers)
            viewModel.syncFromProbePanels()
            withTimeout(5_000L) {
                viewModel.uiState.first { !it.isSyncingProbes && it.lastProbeSyncSummary?.contains("已是最新") == true }
            }
            assertEquals(2, viewModel.uiState.value.servers.size)

            // 2. Toggle persisted collapse states via ViewModel
            viewModel.toggleVaultGroupCollapsed("新加坡")
            viewModel.toggleVaultServerExpanded(nzServer.id)
            assertTrue(viewModel.uiState.value.collapseState.isGroupCollapsed("新加坡"))
            assertTrue(viewModel.uiState.value.collapseState.isServerExpanded(nzServer.id))
            assertTrue(vault.collapseStateFlow.value.isGroupCollapsed("新加坡"))

            // 3. Open interactive Panel Terminal on Nezha node and send stdin command
            viewModel.openInteractiveTerminal(nzServer.id)
            assertTrue(viewModel.uiState.value.isRunning)
            assertTrue(viewModel.uiState.value.isInteractiveSession)
            assertEquals(SshConnectionMode.PANEL_TERMINAL, viewModel.uiState.value.activeConnectionMode)
            assertEquals(1, panelRequests.size)
            assertEquals("nz-1", panelRequests.single().probePanelId)
            assertEquals("7", panelRequests.single().probeServerId)
            assertTrue(panelRequests.single().interactiveShell)

            viewModel.sendTerminalInput("uname -a", appendNewline = true)
            // Drain IO write briefly
            Thread.sleep(50)
            assertTrue(capturedStdin.any { it == "uname -a\r" })

            panelGate.complete(Unit)
            assertFalse(viewModel.uiState.value.isRunning)
            manager.disposeOwnedScopeForTests()
        } finally {
            server.shutdown()
        }
    }
}
