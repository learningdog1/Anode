package com.nodeseek.app.netbench

import com.nodeseek.app.data.netbench.NetBenchHistoryStore
import com.nodeseek.app.domain.netbench.Iperf3Config
import com.nodeseek.app.domain.ssh.CustomSshPreset
import com.nodeseek.app.domain.ssh.DecryptedSshCredentials
import com.nodeseek.app.domain.ssh.SshExecutionRecord
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshServerGroupOption
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.domain.ssh.SshVaultRepository
import com.nodeseek.app.ui.netbench.NetBenchSessionController
import com.nodeseek.app.ui.netbench.filterVaultServersForNetBench
import com.nodeseek.app.ui.netbench.formatVaultEndpointForFeedback
import com.nodeseek.app.ui.netbench.normalizeHostForVaultMatch
import com.nodeseek.app.ui.netbench.toVaultHostSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultHostPickerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeSshVaultRepository(
        initialServers: List<SshServerProfile> = emptyList(),
    ) : SshVaultRepository {
        private val _servers = MutableStateFlow(initialServers)
        override val serversFlow: StateFlow<List<SshServerProfile>> = _servers.asStateFlow()
        override val customPresetsFlow: StateFlow<List<CustomSshPreset>> =
            MutableStateFlow<List<CustomSshPreset>>(emptyList()).asStateFlow()
        override val recentRunsFlow: StateFlow<List<SshExecutionRecord>> =
            MutableStateFlow<List<SshExecutionRecord>>(emptyList()).asStateFlow()

        fun emitServers(next: List<SshServerProfile>) {
            _servers.value = next
        }

        override suspend fun saveServer(draft: SshServerDraft): SshServerProfile =
            throw UnsupportedOperationException()

        override suspend fun deleteServer(serverId: String) =
            throw UnsupportedOperationException()

        override suspend fun decryptCredentials(profile: SshServerProfile): DecryptedSshCredentials =
            throw UnsupportedOperationException()

        override suspend fun updateHostKeyFingerprint(
            serverId: String,
            fingerprintSha256: String,
            algorithm: String?,
        ) = Unit

        override suspend fun markServerConnected(serverId: String, timestampMillis: Long) = Unit
        override suspend fun saveCustomPreset(preset: CustomSshPreset): CustomSshPreset =
            throw UnsupportedOperationException()

        override suspend fun deleteCustomPreset(presetId: String) = Unit
        override suspend fun saveRunRecord(record: SshExecutionRecord) = Unit
        override suspend fun deleteRunRecord(runId: String) = Unit
        override suspend fun clearRunRecords() = Unit
    }

    @Test
    fun `toVaultHostSelection extracts ipv4 ipv6 domain embedded port and probeReportedHost`() {
        val v4 = SshServerProfile(
            id = "s1",
            alias = "[香港] DMIT HK",
            host = "103.117.100.1",
            port = 2222,
            groupTag = "香港",
        )
        val selV4 = v4.toVaultHostSelection()
        assertNotNull(selV4)
        assertEquals("103.117.100.1", selV4!!.host)
        assertEquals(2222, selV4.port)
        assertEquals("DMIT HK", selV4.displayName)

        val v6 = SshServerProfile(
            id = "s2",
            alias = "Cloudflare v6",
            host = "[2606:4700:4700::1111]",
            port = 22,
        )
        val selV6 = v6.toVaultHostSelection()
        assertNotNull(selV6)
        assertEquals("2606:4700:4700::1111", selV6!!.host)
        assertEquals(22, selV6.port)
        assertEquals("[2606:4700:4700::1111]:22", formatVaultEndpointForFeedback(selV6.host, selV6.port))

        val embeddedPort = SshServerProfile(
            id = "s3",
            alias = "Custom",
            host = "lg.example.com:8443",
            port = 22,
        )
        val selEmbedded = embeddedPort.toVaultHostSelection()
        assertNotNull(selEmbedded)
        assertEquals("lg.example.com", selEmbedded!!.host)
        assertEquals(8443, selEmbedded.port)

        val probeFallback = SshServerProfile(
            id = "s4",
            alias = "Nezha Node",
            host = "",
            probeReportedHost = "45.67.89.10",
            port = 22,
        )
        val selProbe = probeFallback.toVaultHostSelection()
        assertNotNull(selProbe)
        assertEquals("45.67.89.10", selProbe!!.host)
        assertEquals(22, selProbe.port)

        val noHost = SshServerProfile(
            id = "s5",
            alias = "Panel Only",
            host = "   ",
            probeReportedHost = "",
            port = 22,
        )
        assertNull(noHost.toVaultHostSelection())
    }

    @Test
    fun `filterVaultServersForNetBench pins current target filters by group and puts missing host last`() {
        val noIp = SshServerProfile(
            id = "no_ip",
            alias = "无地址探针节点",
            host = "",
            groupTag = "香港",
        )
        val hk = SshServerProfile(
            id = "hk1",
            alias = "[香港] ClawCloud",
            host = "47.76.1.10",
            port = 22022,
            groupTag = "香港",
            probeTags = listOf("CN2", "HK"),
        )
        val us = SshServerProfile(
            id = "us1",
            alias = "[美西] Bandwagon DC6",
            host = "198.51.100.2",
            port = 22,
            groupTag = "美西",
        )

        val allOrdered = filterVaultServersForNetBench(
            servers = listOf(noIp, hk, us),
            currentTargetInput = "198.51.100.2:443",
            searchQuery = "",
            selectedGroupKey = SshServerGroupOption.KEY_ALL,
        )
        assertEquals(listOf("us1", "hk1", "no_ip"), allOrdered.map { it.id })

        val hkGroup = filterVaultServersForNetBench(
            servers = listOf(noIp, hk, us),
            currentTargetInput = "",
            searchQuery = "",
            selectedGroupKey = "香港",
        )
        assertEquals(listOf("hk1", "no_ip"), hkGroup.map { it.id })

        val byTag = filterVaultServersForNetBench(
            servers = listOf(noIp, hk, us),
            currentTargetInput = "",
            searchQuery = "CN2",
            selectedGroupKey = SshServerGroupOption.KEY_ALL,
        )
        assertEquals(listOf("hk1"), byTag.map { it.id })
        assertEquals("2606:4700::1111", normalizeHostForVaultMatch("[2606:4700::1111]:443"))
    }

    @Test
    fun `session controller observes vault servers read-only and applies selected host and port`() = runBlocking {
        val hk = SshServerProfile(
            id = "hk1",
            alias = "[香港] DMIT",
            host = "203.0.113.42",
            port = 2222,
            groupTag = "香港",
        )
        val noIp = SshServerProfile(
            id = "panel_only",
            alias = "仅探针终端",
            host = "",
        )
        val fakeVault = FakeSshVaultRepository(initialServers = listOf(hk))
        val historyStore = NetBenchHistoryStore(File(tempFolder.root, "netbench_history.json"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = NetBenchSessionController(
                scope = scope,
                historyStore = historyStore,
                sshVaultRepository = fakeVault,
            )

            assertEquals(listOf(hk), controller.uiState.value.vaultServers)

            fakeVault.emitServers(listOf(hk, noIp))
            delay(50L)
            assertEquals(2, controller.uiState.value.vaultServers.size)

            assertTrue(controller.applyVaultServer(hk))
            val applied = controller.uiState.value
            assertEquals("203.0.113.42", applied.targetInput)
            assertEquals("2222", applied.tcpPortText)
            assertEquals(Iperf3Config.DEFAULT_PORT.toString(), applied.iperfPortText)
            assertNull(applied.errorMessage)
            val snackbar = controller.snackbarMessages.first()
            assertTrue(snackbar.contains("DMIT"))
            assertTrue(snackbar.contains("203.0.113.42:2222"))

            assertFalse(controller.applyVaultServer(noIp))
            assertEquals("203.0.113.42", controller.uiState.value.targetInput)
            assertEquals("2222", controller.uiState.value.tcpPortText)
        } finally {
            scope.cancel()
        }
    }
}
