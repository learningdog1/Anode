package com.nodeseek.app.ssh

import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.domain.ssh.CustomSshPreset
import com.nodeseek.app.domain.ssh.InMemoryTestCipher
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshPresetCatalog
import com.nodeseek.app.domain.ssh.SshPresetCategory
import com.nodeseek.app.domain.ssh.SshPresetFilter
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshServerGroupOption
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.domain.ssh.SshTransport
import com.nodeseek.app.domain.ssh.SshVaultCatalogFilter
import com.nodeseek.app.ui.ssh.LiteSshViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SshVaultCatalogFilterTest {

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
    fun `parseAliasGroup and encodeAliasWithGroup handle bracket tags explicit groupTag and plain aliases`() {
        val bracketed = SshServerProfile(
            id = "s1",
            alias = "[香港] ClawCloud 优化",
            host = "203.0.113.10",
        )
        assertEquals("香港", bracketed.resolvedGroup)
        assertEquals("ClawCloud 优化", bracketed.displayName)

        val fullWidthBracketed = SshServerProfile(
            id = "s2",
            alias = "【美西】瓦工 DC6",
            host = "198.51.100.8",
        )
        assertEquals("美西", fullWidthBracketed.resolvedGroup)
        assertEquals("瓦工 DC6", fullWidthBracketed.displayName)

        // Slashes inside specs like 2C/2G must NOT be misparsed as group tags
        val slashSpecs = SshServerProfile(
            id = "s3",
            alias = "2C/2G HK Mini",
            host = "198.51.100.9",
        )
        assertNull(slashSpecs.resolvedGroup)
        assertEquals("2C/2G HK Mini", slashSpecs.displayName)

        // Encoding and clearing group tags round-trip cleanly without double brackets
        val encoded = SshVaultCatalogFilter.encodeAliasWithGroup(
            alias = "[旧分组] DMIT Pro",
            groupTag = "日本",
            fallbackHost = "198.51.100.20",
        )
        assertEquals("[日本] DMIT Pro", encoded)

        val cleared = SshVaultCatalogFilter.encodeAliasWithGroup(
            alias = encoded,
            groupTag = "",
            fallbackHost = "198.51.100.20",
        )
        assertEquals("DMIT Pro", cleared)
    }

    @Test
    fun `buildServerGroupOptions hides chips when ungrouped and builds counted chips when groups exist`() {
        val ungroupedOnly = listOf(
            SshServerProfile(id = "1", alias = "VPS A", host = "192.0.2.1"),
            SshServerProfile(id = "2", alias = "VPS B", host = "192.0.2.2"),
        )
        assertTrue(SshVaultCatalogFilter.buildServerGroupOptions(ungroupedOnly).isEmpty())

        val mixedServers = listOf(
            SshServerProfile(id = "1", alias = "[香港] HK-1", host = "192.0.2.1"),
            SshServerProfile(id = "2", alias = "[香港] HK-2", host = "192.0.2.2"),
            SshServerProfile(id = "3", alias = "[香港] HK-3", host = "192.0.2.3"),
            SshServerProfile(id = "4", alias = "【日本】TYO-1", host = "192.0.2.4"),
            SshServerProfile(id = "5", alias = "Standalone Node", host = "192.0.2.5"),
        )
        val options = SshVaultCatalogFilter.buildServerGroupOptions(mixedServers)
        assertEquals(4, options.size)
        assertEquals(SshServerGroupOption(SshServerGroupOption.KEY_ALL, "全部", 5), options[0])
        assertEquals("香港", options[1].label)
        assertEquals(3, options[1].count)
        assertEquals("日本", options[2].label)
        assertEquals(1, options[2].count)
        assertEquals(SshServerGroupOption(SshServerGroupOption.KEY_UNGROUPED, "未分组", 1), options[3])

        // Vanished group key falls back to ALL automatically
        assertEquals(
            SshServerGroupOption.KEY_ALL,
            SshVaultCatalogFilter.resolveEffectiveServerGroupKey("美西", options),
        )
        assertEquals(
            "香港",
            SshVaultCatalogFilter.resolveEffectiveServerGroupKey("香港", options),
        )
    }

    @Test
    fun `filterAndSortServers supports 60+ VPS instant search group filter selected-first pinning and folding`() {
        val servers = (1..60).map { idx ->
            val group = when {
                idx % 3 == 0 -> "香港"
                idx % 3 == 1 -> "美西"
                else -> "日本"
            }
            SshServerProfile(
                id = "srv-$idx",
                alias = "[$group] Node-$idx",
                host = "198.51.100.$idx",
                port = if (idx == 42) 22222 else 22,
                username = if (idx == 42) "debian" else "root",
            )
        }

        // Selected server #42 is pinned to index 0 even in a 60-server vault
        val defaultSorted = SshVaultCatalogFilter.filterAndSortServers(
            servers = servers,
            selectedServerId = "srv-42",
        )
        assertEquals(60, defaultSorted.size)
        assertEquals("srv-42", defaultSorted.first().id)

        // Inline collapsed mode shows at most 4 items with selected server at #0
        val collapsed = SshVaultCatalogFilter.visibleServersForInlineSection(
            filteredServers = defaultSorted,
            expanded = false,
            searchQuery = "",
        )
        assertEquals(SshVaultCatalogFilter.DEFAULT_COLLAPSED_SERVER_COUNT, collapsed.size)
        assertEquals("srv-42", collapsed.first().id)

        // Expanded mode returns all 60 items (rendered inside height-bounded LazyColumn)
        val expanded = SshVaultCatalogFilter.visibleServersForInlineSection(
            filteredServers = defaultSorted,
            expanded = true,
            searchQuery = "",
        )
        assertEquals(60, expanded.size)

        // Multi-token search matches username + port + group simultaneously
        val byUserAndPort = SshVaultCatalogFilter.filterAndSortServers(
            servers = servers,
            selectedServerId = "srv-1",
            searchQuery = "debian 22222 香港",
        )
        assertEquals(listOf("srv-42"), byUserAndPort.map { it.id })

        // Group filter isolates all 20 US-West nodes and pins selected US-West node first
        val usWest = SshVaultCatalogFilter.filterAndSortServers(
            servers = servers,
            selectedServerId = "srv-55", // 55 % 3 == 1 -> 美西
            selectedGroupKey = "美西",
        )
        assertEquals(20, usWest.size)
        assertEquals("srv-55", usWest.first().id)
        assertTrue(usWest.all { it.resolvedGroup == "美西" })
    }

    @Test
    fun `filterAndSortPresets searches name description command flags and filters by category and custom source`() {
        val customPresets = (1..15).map { idx ->
            CustomSshPreset(
                id = "custom-$idx",
                name = "私有脚本 #$idx",
                description = if (idx == 9) "BBRv3 内核调优与 mtr 回程诊断" else "自定义日常巡检 $idx",
                command = if (idx == 9) "curl -fsSL https://example.com/mtr_bbr.sh | bash" else "uname -a && free -m",
                category = if (idx == 9) SshPresetCategory.NETWORK else SshPresetCategory.CUSTOM,
            )
        }
        val allPresets = SshPresetCatalog.allPresets(customPresets)
        assertEquals(7 + 15, allPresets.size)

        val filterOptions = SshVaultCatalogFilter.buildPresetFilterOptions(allPresets)
        val countsByFilter = filterOptions.associate { it.filter to it.count }
        assertEquals(22, countsByFilter[SshPresetFilter.ALL])
        assertEquals(15, countsByFilter[SshPresetFilter.CUSTOM])
        assertEquals(4, countsByFilter[SshPresetFilter.BENCHMARK])
        // 1 built-in NextTrace + 1 custom categorized as NETWORK
        assertEquals(2, countsByFilter[SshPresetFilter.NETWORK])
        assertEquals(1, countsByFilter[SshPresetFilter.UNLOCK])
        assertEquals(1, countsByFilter[SshPresetFilter.DIAGNOSTIC])

        // Command keyword search finds both built-in and custom scripts
        val mtrMatches = SshVaultCatalogFilter.filterAndSortPresets(
            presets = allPresets,
            selectedPresetId = SshPresetCatalog.PRESET_QUICK_CHECK,
            searchQuery = "mtr_bbr.sh",
        )
        assertEquals(listOf("custom-9"), mtrMatches.map { it.id })

        // CLI flag search finds YABS (-5 / fio)
        val yabsMatches = SshVaultCatalogFilter.filterAndSortPresets(
            presets = allPresets,
            selectedPresetId = SshPresetCatalog.PRESET_QUICK_CHECK,
            searchQuery = "geekbench fio",
        )
        assertEquals(listOf(SshPresetCatalog.PRESET_YABS), yabsMatches.map { it.id })

        // Custom filter returns all 15 custom presets (including custom-9 categorized as NETWORK)
        // and pins selected custom-12 first
        val customFiltered = SshVaultCatalogFilter.filterAndSortPresets(
            presets = allPresets,
            selectedPresetId = "custom-12",
            filter = SshPresetFilter.CUSTOM,
        )
        assertEquals(15, customFiltered.size)
        assertEquals("custom-12", customFiltered.first().id)

        // Collapsed inline slice keeps top 4 with selected preset pinned at #0
        val collapsedInline = SshVaultCatalogFilter.visiblePresetsForInlineSection(
            filteredPresets = customFiltered,
            expanded = false,
            searchQuery = "",
        )
        assertEquals(SshVaultCatalogFilter.DEFAULT_COLLAPSED_PRESET_COUNT, collapsedInline.size)
        assertEquals("custom-12", collapsedInline.first().id)
    }

    @Test
    fun `ViewModel persists server group tags and custom preset categories and updates reactive filtered state`() = runTest {
        val vaultFile = File(tempFolder.root, "vm_filter_vault.json")
        val cipher = InMemoryTestCipher()
        val vault = SshServerVault(
            vaultFile = vaultFile,
            cipher = cipher,
            ioDispatcher = testDispatcher,
        )
        val noopTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = emptyFlow()
        }
        val viewModel = LiteSshViewModel(
            vaultRepository = vault,
            executor = LiteSshExecutor(transport = noopTransport),
            enableElapsedTicker = false,
        )

        viewModel.saveServer(
            SshServerDraft(
                alias = "Claw HK",
                host = "203.0.113.11",
                username = "root",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "pw1",
                groupTag = "香港",
                groupEdited = true,
            ),
        )
        viewModel.saveServer(
            SshServerDraft(
                alias = "DMIT LAX",
                host = "198.51.100.22",
                username = "ubuntu",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "pw2",
                groupTag = "美西",
                groupEdited = true,
            ),
        )

        assertEquals(2, viewModel.uiState.value.servers.size)
        assertEquals(3, viewModel.uiState.value.serverGroupOptions.size) // 全部 + 美西 + 香港

        // Filter by group "香港"
        viewModel.selectServerGroup("香港")
        assertEquals(1, viewModel.uiState.value.filteredServers.size)
        assertEquals("Claw HK", viewModel.uiState.value.filteredServers.single().displayName)

        // Reset group & search by username
        viewModel.selectServerGroup(SshServerGroupOption.KEY_ALL)
        viewModel.updateServerSearchQuery("ubuntu")
        assertEquals(1, viewModel.uiState.value.filteredServers.size)
        assertEquals("DMIT LAX", viewModel.uiState.value.filteredServers.single().displayName)
        viewModel.clearServerSearchQuery()
        assertEquals(2, viewModel.uiState.value.filteredServers.size)

        // Reload fresh SshServerVault from disk to verify groupTag persisted cleanly in JSON
        val reloadedVault = SshServerVault(
            vaultFile = vaultFile,
            cipher = cipher,
            ioDispatcher = testDispatcher,
        )
        val reloadedGroups = reloadedVault.serversFlow.value.mapNotNull { it.resolvedGroup }.toSet()
        assertEquals(setOf("香港", "美西"), reloadedGroups)

        // Save categorized custom preset & filter by NETWORK and CUSTOM
        viewModel.saveCustomPreset(
            id = null,
            name = "BestTrace TCP 回程",
            tabTitle = "BestTrace",
            description = "自定义三网 TCP 路由追踪",
            command = "besttrace -q 1 -T 113.108.81.189",
            category = SshPresetCategory.NETWORK,
        )
        viewModel.selectPresetFilter(SshPresetFilter.NETWORK)
        assertEquals(2, viewModel.uiState.value.filteredPresets.size)
        assertEquals("BestTrace TCP 回程", viewModel.uiState.value.filteredPresets.first().name)

        viewModel.selectPresetFilter(SshPresetFilter.CUSTOM)
        assertEquals(1, viewModel.uiState.value.filteredPresets.size)
        assertFalse(viewModel.uiState.value.isPresetCatalogExpanded)
        viewModel.togglePresetCatalogExpanded()
        assertTrue(viewModel.uiState.value.isPresetCatalogExpanded)
    }
}
