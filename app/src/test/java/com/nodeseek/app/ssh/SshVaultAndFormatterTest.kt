package com.nodeseek.app.ssh

import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.domain.ssh.CustomSshPreset
import com.nodeseek.app.domain.ssh.HostKeyInfo
import com.nodeseek.app.domain.ssh.HostKeyTrustPolicy
import com.nodeseek.app.domain.ssh.HostKeyVerificationResult
import com.nodeseek.app.domain.ssh.InMemoryTestCipher
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshBenchmarkPostFormatter
import com.nodeseek.app.domain.ssh.SshExecutionRecord
import com.nodeseek.app.domain.ssh.SshPresetCatalog
import com.nodeseek.app.domain.ssh.SshRunStatus
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshVaultCorruptedException
import com.nodeseek.app.ui.components.MdBlock
import com.nodeseek.app.ui.components.parseNodeSeekMarkdownBlocks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SshVaultAndFormatterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `SshServerVault encrypts secrets on disk and round-trips across instances`() = runTest {
        val vaultFile = File(tempFolder.root, SshServerVault.VAULT_FILE_NAME)
        val cipher = InMemoryTestCipher()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)

        val vault = SshServerVault(
            vaultFile = vaultFile,
            cipher = cipher,
            ioDispatcher = dispatcher,
            clock = { 1_700_000_000_000L },
        )

        val rawPrivateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nSECRET_KEY_BODY_12345\n-----END OPENSSH PRIVATE KEY-----"
        val rawPassphrase = "super-secret-passphrase!"

        val savedKeyServer = vault.saveServer(
            SshServerDraft(
                alias = "Tokyo SoftBank",
                host = "203.0.113.10",
                port = 2222,
                username = "ubuntu",
                authMethod = SshAuthMethod.PRIVATE_KEY,
                secret = rawPrivateKey,
                passphrase = rawPassphrase,
            ),
        )
        val savedPwServer = vault.saveServer(
            SshServerDraft(
                alias = "BWG DC6",
                host = "198.51.100.7",
                port = 22,
                username = "root",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "MyRootP@ssw0rd!",
            ),
        )

        // Ensure persisted JSON file does NOT contain any plaintext secrets
        assertTrue(vaultFile.isFile)
        val diskJson = vaultFile.readText(Charsets.UTF_8)
        assertFalse(diskJson.contains("SECRET_KEY_BODY_12345"))
        assertFalse(diskJson.contains("super-secret-passphrase!"))
        assertFalse(diskJson.contains("MyRootP@ssw0rd!"))
        assertTrue(diskJson.contains(InMemoryTestCipher.PREFIX))

        // Record TOFU host key & custom preset & recent run
        vault.updateHostKeyFingerprint(savedKeyServer.id, "SHA256:abc123fingerprint==", "ssh-ed25519")
        vault.saveCustomPreset(
            CustomSshPreset(
                id = "",
                name = "自定义 Geekbench",
                command = "curl -sL https://yabs.sh | bash -s -- -f -i",
            ),
        )
        vault.saveRunRecord(sampleRun(id = "run-1", presetName = "YABS 综合跑分", tabTitle = "💻 YABS", output = "Score 1000"))

        // Reload a fresh vault instance from disk
        val reloaded = SshServerVault(
            vaultFile = vaultFile,
            cipher = cipher,
            ioDispatcher = dispatcher,
        )

        assertEquals(2, reloaded.serversFlow.value.size)
        val restoredKeyServer = reloaded.serversFlow.value.first { it.id == savedKeyServer.id }
        assertEquals("SHA256:abc123fingerprint", restoredKeyServer.knownHostKeyFingerprint)
        assertEquals("ssh-ed25519", restoredKeyServer.knownHostKeyAlgorithm)

        val decryptedKey = reloaded.decryptCredentials(restoredKeyServer)
        assertEquals(rawPrivateKey, decryptedKey.secret)
        assertEquals(rawPassphrase, decryptedKey.passphrase)

        val restoredPwServer = reloaded.serversFlow.value.first { it.id == savedPwServer.id }
        assertEquals("MyRootP@ssw0rd!", reloaded.decryptCredentials(restoredPwServer).secret)

        // Updating alias without re-entering secret keeps existing secret and fingerprint
        val editedAlias = reloaded.saveServer(
            SshServerDraft(
                id = savedKeyServer.id,
                alias = "Tokyo SB Renamed",
                host = "203.0.113.10",
                port = 2222,
                username = "ubuntu",
                authMethod = SshAuthMethod.PRIVATE_KEY,
                secret = "",
                keepExistingSecret = true,
                keepExistingPassphrase = true,
            ),
        )
        assertEquals("SHA256:abc123fingerprint", editedAlias.knownHostKeyFingerprint)
        assertEquals(rawPrivateKey, reloaded.decryptCredentials(editedAlias).secret)

        // Changing host resets TOFU host key fingerprint
        val changedHost = reloaded.saveServer(
            SshServerDraft(
                id = savedKeyServer.id,
                alias = "Tokyo SB Renamed",
                host = "203.0.113.99",
                port = 2222,
                username = "ubuntu",
                authMethod = SshAuthMethod.PRIVATE_KEY,
                secret = "",
                keepExistingSecret = true,
                keepExistingPassphrase = true,
            ),
        )
        assertNull(changedHost.knownHostKeyFingerprint)
        assertEquals(1, reloaded.customPresetsFlow.value.size)
        assertEquals(1, reloaded.recentRunsFlow.value.size)
    }

    @Test
    fun `HostKeyTrustPolicy handles TOFU first-use, trusted match, and key change warning`() {
        val rawKeyA = "ssh-ed25519-server-key-A".toByteArray()
        val rawKeyB = "ssh-ed25519-server-key-B".toByteArray()
        val fpA = HostKeyTrustPolicy.computeSha256Fingerprint(rawKeyA)
        val fpB = HostKeyTrustPolicy.computeSha256Fingerprint(rawKeyB)

        assertTrue(fpA.startsWith("SHA256:"))
        assertFalse(fpA.endsWith("="))

        val infoA = HostKeyInfo("203.0.113.10", 22, "ssh-ed25519", fpA)
        val infoB = HostKeyInfo("203.0.113.10", 22, "ssh-ed25519", fpB)

        val firstUse = HostKeyTrustPolicy.verify(expectedFingerprint = null, actual = infoA)
        assertTrue(firstUse is HostKeyVerificationResult.FirstUseTrust)

        val trusted = HostKeyTrustPolicy.verify(expectedFingerprint = fpA, actual = infoA)
        assertTrue(trusted is HostKeyVerificationResult.Trusted)

        val changed = HostKeyTrustPolicy.verify(expectedFingerprint = fpA, actual = infoB)
        assertTrue(changed is HostKeyVerificationResult.KeyChangedWarning)
        val warning = changed as HostKeyVerificationResult.KeyChangedWarning
        assertEquals(fpA, warning.expectedFingerprint)
        assertEquals(fpB, warning.actualHostKey.fingerprintSha256)
    }

    @Test
    fun `SshPresetCatalog builds non-interactive commands with configurable flags`() {
        assertEquals(7, SshPresetCatalog.builtInPresets.size)

        val yabs = SshPresetCatalog.findBuiltIn(SshPresetCatalog.PRESET_YABS)!!
        val defaultCmd = yabs.buildCommand()
        assertTrue(defaultCmd.startsWith(SshPresetCatalog.HEADLESS_ENV_PREFIX))
        assertTrue(defaultCmd.contains("https://yabs.sh"))
        assertTrue(defaultCmd.contains("stdbuf -oL -eL"))
        assertTrue(defaultCmd.contains("-5"))

        val fastYabsCmd = yabs.buildCommand(setOf("yabs_gb5", "yabs_skip_fio", "yabs_skip_iperf"))
        assertTrue(fastYabsCmd.contains("-5 -f -i"))

        val ecs = SshPresetCatalog.findBuiltIn(SshPresetCatalog.PRESET_ECS)!!
        assertTrue(ecs.buildCommand().contains("noninteractive=true"))
        assertTrue(ecs.buildCommand().contains("-menu=false"))

        val bench = SshPresetCatalog.findBuiltIn(SshPresetCatalog.PRESET_BENCH)!!
        assertTrue(bench.buildCommand().contains("https://bench.sh"))

        val nq = SshPresetCatalog.findBuiltIn(SshPresetCatalog.PRESET_NODE_QUALITY)!!
        assertTrue(nq.buildCommand().contains("https://run.NodeQuality.com"))
        assertTrue(nq.buildCommand().contains("printf '%s\\ny\\n%s\\ny\\n'"))
        assertFalse(nq.buildCommand().contains("run.NodeQuality.com) -n"))

        val nextTrace = SshPresetCatalog.findBuiltIn(SshPresetCatalog.PRESET_NEXT_TRACE)!!
        assertTrue(nextTrace.buildCommand(setOf("nt_tcp")).contains("nexttrace --fast-trace --tcp"))

        val media = SshPresetCatalog.findBuiltIn(SshPresetCatalog.PRESET_MEDIA_UNLOCK)!!
        assertTrue(media.buildCommand().contains("-M 4"))
        assertTrue(media.buildCommand().contains("-R 0"))
        assertTrue(media.buildCommand(setOf("media_v6", "media_region_all")).contains("-M 6 -R 66"))
    }

    @Test
    fun `SshBenchmarkPostFormatter emits single ansi fence and multi-run tabs compatible with MarkdownRenderer`() {
        val run1 = sampleRun(
            id = "r1",
            presetName = "YABS 综合跑分",
            tabTitle = "💻 YABS 综合跑分",
            output = "\u001B[32mGeekbench 5 Single: 1420\u001B[0m\nServer IP: 203.0.113.42",
        )
        val run2 = sampleRun(
            id = "r2",
            presetName = "NextTrace 三网回程路由",
            tabTitle = "📍 NextTrace 三网回程",
            output = "\u001B[36m59.43.130.1 CN2 GIA\u001B[0m",
        )
        val run3 = sampleRun(
            id = "r3",
            presetName = "流媒体 & AI 解锁检测",
            tabTitle = "🎬 流媒体 & AI 解锁",
            output = "\u001B[32mNetflix: Yes\u001B[0m",
        )

        // Single run -> ```ansi code block with IP masked
        val singleFormatted = SshBenchmarkPostFormatter.format(listOf(run1), maskIp = true)
        assertFalse(singleFormatted.usesTabsLayout)
        assertEquals("review", singleFormatted.categorySlug)
        assertTrue(singleFormatted.suggestedTitle.contains("Tokyo VPS"))
        assertTrue(singleFormatted.markdownContent.startsWith("```ansi\n"))
        assertTrue(singleFormatted.markdownContent.contains("203.0.*.*"))
        assertFalse(singleFormatted.markdownContent.contains("203.0.113.42"))

        val singleBlocks = parseNodeSeekMarkdownBlocks(singleFormatted.markdownContent)
        assertEquals(1, singleBlocks.size)
        val singleFence = singleBlocks.single() as MdBlock.CodeFence
        assertEquals("ansi", singleFence.language)
        assertTrue(singleFence.code.contains("\u001B[32mGeekbench 5 Single: 1420\u001B[0m"))

        // Multi-run -> NodeSeek native :::: tabs / ::: tab-item layout parsed by Anode MarkdownRenderer
        val multiFormatted = SshBenchmarkPostFormatter.format(listOf(run1, run2, run3), maskIp = true)
        assertTrue(multiFormatted.usesTabsLayout)
        assertEquals(3, multiFormatted.runCount)
        assertTrue(multiFormatted.markdownContent.startsWith(":::: tabs\n"))
        assertTrue(multiFormatted.markdownContent.contains("::: tab-item 💻 YABS 综合跑分"))
        assertTrue(multiFormatted.markdownContent.contains("::: tab-item 📍 NextTrace 三网回程"))
        assertTrue(multiFormatted.markdownContent.contains("::: tab-item 🎬 流媒体 & AI 解锁"))
        assertTrue(multiFormatted.markdownContent.endsWith("::::"))

        val multiBlocks = parseNodeSeekMarkdownBlocks(multiFormatted.markdownContent)
        assertEquals(1, multiBlocks.size)
        val tabsContainer = multiBlocks.single() as MdBlock.TabsContainer
        assertEquals(3, tabsContainer.tabs.size)
        assertEquals("💻 YABS 综合跑分", tabsContainer.tabs[0].title)
        assertEquals("📍 NextTrace 三网回程", tabsContainer.tabs[1].title)
        assertEquals("🎬 流媒体 & AI 解锁", tabsContainer.tabs[2].title)

        val tab0Fence = tabsContainer.tabs[0].children.single() as MdBlock.CodeFence
        assertEquals("ansi", tab0Fence.language)
        assertTrue(tab0Fence.code.contains("203.0.*.*"))

        // maskIp = false preserves original IP
        val unmasked = SshBenchmarkPostFormatter.format(listOf(run1), maskIp = false)
        assertTrue(unmasked.markdownContent.contains("203.0.113.42"))
    }

    @Test
    fun `corrupted vault JSON creates backup, surfaces load error, blocks overwrite and recovers via reset`() = runTest {
        val vaultFile = File(tempFolder.root, "corrupt_vault.json")
        val corruptRaw = "{ \"schemaVersion\": 2, \"servers\": [ BROKEN_JSON"
        vaultFile.writeText(corruptRaw, Charsets.UTF_8)

        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val vault = SshServerVault(
            vaultFile = vaultFile,
            cipher = InMemoryTestCipher(),
            ioDispatcher = dispatcher,
            clock = { 1_760_000_000_000L },
        )

        // Error is surfaced and corrupt backup is created
        val loadError = vault.vaultLoadErrorFlow.value
        assertTrue("Expected vaultLoadErrorFlow to be populated", !loadError.isNullOrBlank())
        val backupFile = File(tempFolder.root, "corrupt_vault.json.corrupt.1760000000000")
        assertTrue("Expected .corrupt backup file to exist", backupFile.isFile)
        assertEquals(corruptRaw, backupFile.readText(Charsets.UTF_8))

        // Collapse state update must NOT overwrite corrupted vault file
        vault.updateCollapseState { it.toggleVaultListCollapsed() }
        assertEquals(corruptRaw, vaultFile.readText(Charsets.UTF_8))

        // User-initiated saveServer must fail with SshVaultCorruptedException and keep original file intact
        var threwCorrupted = false
        try {
            vault.saveServer(
                SshServerDraft(
                    alias = "Should Fail",
                    host = "203.0.113.5",
                    secret = "pw",
                ),
            )
        } catch (_: SshVaultCorruptedException) {
            threwCorrupted = true
        }
        assertTrue("Expected SshVaultCorruptedException when saving to corrupted vault", threwCorrupted)
        assertEquals(corruptRaw, vaultFile.readText(Charsets.UTF_8))
        assertTrue(vault.serversFlow.value.isEmpty())

        // Retry still reports false while file remains corrupt
        assertFalse(vault.retryLoadVault())
        assertTrue(!vault.vaultLoadErrorFlow.value.isNullOrBlank())

        // Explicit user reset backs up and initializes a clean writable vault
        vault.resetCorruptedVault()
        assertNull(vault.vaultLoadErrorFlow.value)
        val saved = vault.saveServer(
            SshServerDraft(
                alias = "Recovered VPS",
                host = "203.0.113.88",
                secret = "pw-ok",
            ),
        )
        assertEquals("Recovered VPS", saved.alias)
        assertEquals(1, vault.serversFlow.value.size)
        // Backup file must still remain intact after reset
        assertEquals(corruptRaw, backupFile.readText(Charsets.UTF_8))
    }

    @Test
    fun `recentRuns are persisted in sibling runs file and legacy inline runs migrate without bloating primary vault`() = runTest {
        val vaultFile = File(tempFolder.root, "split_vault.json")
        val runsFile = File(tempFolder.root, "split_vault_runs.json")
        val cipher = InMemoryTestCipher()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)

        // Write a legacy single-file vault containing an inline recentRun
        val legacyVaultJson = """
            {
              "schemaVersion": 2,
              "servers": [
                {
                  "id": "srv-legacy",
                  "alias": "Legacy Node",
                  "host": "198.51.100.1",
                  "port": 22,
                  "username": "root",
                  "authMethod": "PASSWORD",
                  "encryptedSecret": "${cipher.encrypt("pw")}"
                }
              ],
              "customPresets": [],
              "recentRuns": [
                {
                  "id": "legacy-run-1",
                  "serverId": "srv-legacy",
                  "serverAlias": "Legacy Node",
                  "serverHost": "198.51.100.1",
                  "presetId": "quick_check",
                  "presetName": "Quick Check",
                  "tabTitle": "Quick",
                  "command": "uname -a",
                  "sanitizedAnsiOutput": "LEGACY_LARGE_OUTPUT_PAYLOAD_MARKER",
                  "exitCode": 0,
                  "status": "SUCCEEDED",
                  "startedAtEpochMillis": 1700000000000,
                  "durationMillis": 1000
                }
              ]
            }
        """.trimIndent()
        vaultFile.writeText(legacyVaultJson, Charsets.UTF_8)

        val vault = SshServerVault(vaultFile = vaultFile, cipher = cipher, ioDispatcher = dispatcher)
        assertEquals(1, vault.serversFlow.value.size)
        assertEquals(1, vault.recentRunsFlow.value.size)
        assertEquals("legacy-run-1", vault.recentRunsFlow.value.single().id)

        // Updating collapse state migrates legacy runs into sibling _runs.json and strips them from vaultFile
        vault.updateCollapseState { it.toggleVaultListCollapsed() }
        assertTrue("Expected sibling runs file to be created", runsFile.isFile)
        assertTrue(runsFile.readText(Charsets.UTF_8).contains("LEGACY_LARGE_OUTPUT_PAYLOAD_MARKER"))
        assertFalse(
            "Primary vault file should no longer duplicate large run outputs",
            vaultFile.readText(Charsets.UTF_8).contains("LEGACY_LARGE_OUTPUT_PAYLOAD_MARKER"),
        )
        // Compact JSON (prettyPrint = false) has no pretty indentation
        assertFalse(vaultFile.readText(Charsets.UTF_8).contains("\n    "))

        // Reload restores both servers and split runs
        val reloaded = SshServerVault(vaultFile = vaultFile, cipher = cipher, ioDispatcher = dispatcher)
        assertEquals(1, reloaded.serversFlow.value.size)
        assertEquals(1, reloaded.recentRunsFlow.value.size)
        assertEquals("legacy-run-1", reloaded.recentRunsFlow.value.single().id)
    }

    @Test
    fun `deferInitialLoad skips synchronous disk IO in constructor and loads on ensureLoaded`() = runTest {
        val vaultFile = File(tempFolder.root, "deferred_vault.json")
        val cipher = InMemoryTestCipher()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)

        val writer = SshServerVault(vaultFile = vaultFile, cipher = cipher, ioDispatcher = dispatcher)
        writer.saveServer(SshServerDraft(alias = "Deferred Host", host = "203.0.113.77", secret = "pw"))

        val lazyVault = SshServerVault(
            vaultFile = vaultFile,
            cipher = cipher,
            ioDispatcher = dispatcher,
            deferInitialLoad = true,
        )
        // Before ensureLoaded(), no disk read has occurred yet
        assertTrue(lazyVault.serversFlow.value.isEmpty())

        lazyVault.ensureLoaded()
        assertEquals(1, lazyVault.serversFlow.value.size)
        assertEquals("Deferred Host", lazyVault.serversFlow.value.single().alias)
    }

    private fun sampleRun(
        id: String,
        presetName: String,
        tabTitle: String,
        output: String,
    ) = SshExecutionRecord(
        id = id,
        serverId = "srv-1",
        serverAlias = "Tokyo VPS",
        serverHost = "203.0.113.42",
        presetId = "preset-$id",
        presetName = presetName,
        tabTitle = tabTitle,
        command = "echo ok",
        sanitizedAnsiOutput = output,
        exitCode = 0,
        status = SshRunStatus.SUCCEEDED,
        startedAtEpochMillis = 1_700_000_000_000L,
        durationMillis = 4_200L,
    )
}
