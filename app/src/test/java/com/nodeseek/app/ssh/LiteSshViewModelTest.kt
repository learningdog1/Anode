package com.nodeseek.app.ssh

import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.domain.ssh.HostKeyInfo
import com.nodeseek.app.domain.ssh.HostKeyTrustPolicy
import com.nodeseek.app.domain.ssh.HostKeyVerificationResult
import com.nodeseek.app.domain.ssh.InMemoryTestCipher
import com.nodeseek.app.domain.ssh.Iperf3RemoteServerCommands
import com.nodeseek.app.domain.ssh.LinuxDistroFamily
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshHostKeyMismatchException
import com.nodeseek.app.domain.ssh.SshPresetCatalog
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.domain.ssh.SshTransport
import com.nodeseek.app.ui.ssh.LiteSshViewModel
import com.nodeseek.app.ui.ssh.SshConnectionPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LiteSshViewModelTest {

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

    private fun createVault(): SshServerVault = SshServerVault(
        vaultFile = File(tempFolder.root, "test_vault_${System.nanoTime()}.json"),
        cipher = InMemoryTestCipher(),
        ioDispatcher = testDispatcher,
        clock = { 1_750_000_000_000L },
    )

    @Test
    fun `full execution flow records TOFU fingerprint, sanitizes ANSI stream, masks IP, and builds multi-tab report`() = runTest {
        val vault = createVault()
        val observedRequests = mutableListOf<SshConnectionRequest>()
        val observedCommands = mutableListOf<String>()
        val serverFingerprint = HostKeyTrustPolicy.computeSha256Fingerprint("mock-key-v1".toByteArray())

        val mockTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                observedRequests += request
                observedCommands += command
                val hostKey = HostKeyInfo(request.host, request.port, "ssh-ed25519", serverFingerprint)
                val verification = HostKeyTrustPolicy.verify(request.expectedHostKeyFingerprint, hostKey)
                emit(SshStreamEvent.HostKeyObserved(verification))
                emit(SshStreamEvent.Connected)
                if (command.contains("yabs.sh")) {
                    emit(SshStreamEvent.OutputChunk("Running fio 10%\rRunning fio 100%\r\n"))
                    emit(SshStreamEvent.OutputChunk("\u001B[?25l\u001B[32mYABS IP: 203.0.113.55\u001B[0m\u001B[?25h\n"))
                } else {
                    emit(SshStreamEvent.OutputChunk("\u001B[36mNextTrace Hop 59.43.130.8\u001B[0m\n"))
                }
                emit(SshStreamEvent.Completed(exitCode = 0))
            }
        }

        val viewModel = LiteSshViewModel(
            vaultRepository = vault,
            executor = LiteSshExecutor(transport = mockTransport, clock = { 1_750_000_005_000L }),
            clock = { 1_750_000_005_000L },
            enableElapsedTicker = false,
        )

        // 1. Add a server to vault
        viewModel.saveServer(
            SshServerDraft(
                alias = "LA CN2 GIA",
                host = "203.0.113.55",
                port = 22,
                username = "root",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "top-secret-pw",
            ),
        )
        assertEquals(1, viewModel.uiState.value.servers.size)

        // 2. Select YABS + toggle -f (skip fio) and run
        viewModel.selectPreset(SshPresetCatalog.PRESET_YABS)
        viewModel.togglePresetFlag(SshPresetCatalog.PRESET_YABS, "yabs_skip_fio")
        viewModel.runSelectedPreset()

        val stateAfterFirst = viewModel.uiState.value
        assertFalse(stateAfterFirst.isRunning)
        assertEquals(SshConnectionPhase.COMPLETED, stateAfterFirst.connectionPhase)
        assertEquals(0, stateAfterFirst.exitCode)
        assertEquals(1, stateAfterFirst.sessionRuns.size)
        assertEquals(1, vault.recentRunsFlow.value.size)

        // Verify TOFU fingerprint was persisted on first connect
        val updatedServer = vault.serversFlow.value.single()
        assertEquals(serverFingerprint, updatedServer.knownHostKeyFingerprint)
        assertNotNull(updatedServer.lastConnectedEpochMillis)

        // Verify YABS command included headless env + -5 -f flags
        assertTrue(observedCommands.first().contains("TERM=xterm-256color"))
        assertTrue(observedCommands.first().contains("-5 -f"))

        // Verify ANSI stream sanitization + default-ON IP masking in console
        assertFalse(stateAfterFirst.displayedConsoleOutput.contains("Running fio 10%"))
        assertTrue(stateAfterFirst.displayedConsoleOutput.contains("Running fio 100%"))
        assertFalse(stateAfterFirst.displayedConsoleOutput.contains("\u001B[?25l"))
        assertTrue(stateAfterFirst.displayedConsoleOutput.contains("\u001B[32mYABS IP: 203.0.*.*\u001B[0m"))
        assertFalse(stateAfterFirst.displayedConsoleOutput.contains("203.0.113.55"))

        // Toggling IP mask OFF reveals raw IP immediately
        viewModel.toggleMaskServerIp(false)
        assertTrue(viewModel.uiState.value.displayedConsoleOutput.contains("203.0.113.55"))
        viewModel.toggleMaskServerIp(true)

        // 3. Run NextTrace as second script in the same session -> auto-combines into :::: tabs
        viewModel.selectPreset(SshPresetCatalog.PRESET_NEXT_TRACE)
        viewModel.runSelectedPreset()

        assertEquals(2, viewModel.uiState.value.sessionRuns.size)
        // Second request carried the persisted TOFU fingerprint
        assertEquals(serverFingerprint, observedRequests[1].expectedHostKeyFingerprint)

        val formatted = viewModel.buildExportPayload()
        assertNotNull(formatted)
        assertTrue(formatted!!.usesTabsLayout)
        assertEquals(2, formatted.runCount)
        assertEquals("review", formatted.categorySlug)
        assertTrue(formatted.markdownContent.contains(":::: tabs"))
        assertTrue(formatted.markdownContent.contains("::: tab-item YABS 跑分"))
        assertTrue(formatted.markdownContent.contains("::: tab-item NextTrace 回程"))
        assertTrue(formatted.markdownContent.contains("203.0.*.*"))
        assertTrue(formatted.markdownContent.contains("59.43.*.*"))
    }

    @Test
    fun `host key mismatch blocks execution until user explicitly trusts new fingerprint`() = runTest {
        val vault = createVault()
        val oldFp = HostKeyTrustPolicy.computeSha256Fingerprint("old-host-key".toByteArray())
        val newFp = HostKeyTrustPolicy.computeSha256Fingerprint("reinstalled-host-key".toByteArray())

        val saved = vault.saveServer(
            SshServerDraft(
                alias = "JP IIJ",
                host = "198.51.100.20",
                port = 22,
                username = "root",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "pw123",
            ),
        )
        vault.updateHostKeyFingerprint(saved.id, oldFp, "ssh-ed25519")

        val mockTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                val actualInfo = HostKeyInfo(request.host, request.port, "ssh-ed25519", newFp)
                val verification = HostKeyTrustPolicy.verify(request.expectedHostKeyFingerprint, actualInfo)
                if (verification is HostKeyVerificationResult.KeyChangedWarning &&
                    !request.allowHostKeyMismatchOverride
                ) {
                    throw SshHostKeyMismatchException(
                        expectedFingerprint = verification.expectedFingerprint,
                        actualHostKey = verification.actualHostKey,
                    )
                }
                emit(SshStreamEvent.HostKeyObserved(verification))
                emit(SshStreamEvent.Connected)
                emit(SshStreamEvent.OutputChunk("\u001B[32mReconnected OK\u001B[0m\n"))
                emit(SshStreamEvent.Completed(exitCode = 0))
            }
        }

        val viewModel = LiteSshViewModel(
            vaultRepository = vault,
            executor = LiteSshExecutor(transport = mockTransport),
            enableElapsedTicker = false,
        )

        // First attempt triggers TOFU mismatch prompt and does NOT execute command
        viewModel.runSelectedPreset()

        val mismatchPrompt = viewModel.uiState.value.pendingHostKeyMismatch
        assertNotNull(mismatchPrompt)
        assertEquals(oldFp, mismatchPrompt!!.expectedFingerprint)
        assertEquals(newFp, mismatchPrompt.actualHostKey.fingerprintSha256)
        assertEquals(SshConnectionPhase.FAILED, viewModel.uiState.value.connectionPhase)
        assertTrue(viewModel.uiState.value.sessionRuns.isEmpty())

        // User confirms trusting new fingerprint -> updates vault & succeeds
        viewModel.confirmTrustChangedHostKey()

        assertNull(viewModel.uiState.value.pendingHostKeyMismatch)
        assertEquals(SshConnectionPhase.COMPLETED, viewModel.uiState.value.connectionPhase)
        assertEquals(newFp, vault.serversFlow.value.single().knownHostKeyFingerprint)
        assertTrue(viewModel.uiState.value.displayedConsoleOutput.contains("Reconnected OK"))
    }

    @Test
    fun `cancelExecution immediately aborts streaming SSH session and preserves partial output`() = runTest {
        val vault = createVault()
        vault.saveServer(
            SshServerDraft(
                alias = "SG VPS",
                host = "203.0.113.88",
                port = 22,
                username = "root",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "pw",
            ),
        )

        val streamGate = CompletableDeferred<Unit>()
        val mockTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                emit(SshStreamEvent.Connected)
                emit(SshStreamEvent.OutputChunk("\u001B[33mPartial benchmark line...\u001B[0m\n"))
                streamGate.await()
                emit(SshStreamEvent.Completed(exitCode = 0))
            }
        }

        val viewModel = LiteSshViewModel(
            vaultRepository = vault,
            executor = LiteSshExecutor(transport = mockTransport),
            enableElapsedTicker = false,
        )

        viewModel.runSelectedPreset()
        assertTrue(viewModel.uiState.value.isRunning)
        assertTrue(viewModel.uiState.value.displayedConsoleOutput.contains("Partial benchmark line..."))

        viewModel.cancelExecution()

        assertFalse(viewModel.uiState.value.isRunning)
        assertEquals(SshConnectionPhase.CANCELLED, viewModel.uiState.value.connectionPhase)
        assertTrue(viewModel.uiState.value.displayedConsoleOutput.contains("Partial benchmark line..."))
    }

    @Test
    fun `iperf3 server manager prompts on missing binary installs via distro command starts daemon and stops cleanly`() = runTest {
        val vault = createVault()
        vault.saveServer(
            SshServerDraft(
                alias = "Rocky9 Node",
                host = "198.51.100.77",
                port = 22,
                username = "ubuntu",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "pw",
            ),
        )

        val executedCommands = mutableListOf<String>()
        var installed = false

        val mockTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                executedCommands += command
                emit(SshStreamEvent.Connected)
                when {
                    !installed && command.contains(Iperf3RemoteServerCommands.MISSING_MARKER_PREFIX) -> {
                        emit(
                            SshStreamEvent.OutputChunk(
                                "⚠ 未检测到 iperf3 命令（系统：Rocky Linux 9.4）\n" +
                                    "__ANODE_IPERF3_MISSING__|id=rocky|id_like=rhel centos fedora|pretty=Rocky Linux 9.4|is_root=0|has_dnf=1|has_sudo=1\n",
                            ),
                        )
                        emit(SshStreamEvent.Completed(exitCode = Iperf3RemoteServerCommands.EXIT_CODE_NOT_INSTALLED))
                    }
                    command.contains("dnf install -y iperf3") -> {
                        installed = true
                        emit(SshStreamEvent.OutputChunk("✓ iperf3 安装完成\n✓ iperf3 服务端已在后台守护运行（TCP/UDP 端口 15201）\n"))
                        emit(SshStreamEvent.Completed(exitCode = 0))
                    }
                    command.contains("已停止 VPS 上的 iperf3 服务端") -> {
                        emit(SshStreamEvent.OutputChunk("✓ 已停止 VPS 上的 iperf3 服务端（端口 15201）\n"))
                        emit(SshStreamEvent.Completed(exitCode = 0))
                    }
                    else -> {
                        emit(SshStreamEvent.OutputChunk("OK\n"))
                        emit(SshStreamEvent.Completed(exitCode = 0))
                    }
                }
            }
        }

        val viewModel = LiteSshViewModel(
            vaultRepository = vault,
            executor = LiteSshExecutor(transport = mockTransport),
            enableElapsedTicker = false,
        )

        // 1. Open dialog pre-matched to host & custom port 15201
        viewModel.openIperf3ServerDialog(preferredHost = "198.51.100.77", preferredPort = 15201)
        assertTrue(viewModel.uiState.value.showIperf3ServerDialog)
        assertEquals("15201", viewModel.uiState.value.iperf3ServerPortText)

        // 2. Attempt start -> detects missing iperf3 and exposes PendingIperf3InstallPrompt
        viewModel.toggleIperf3AutoOpenFirewall(true)
        viewModel.startIperf3Server()

        val prompt = viewModel.uiState.value.pendingIperf3InstallPrompt
        assertNotNull(prompt)
        assertEquals(15201, prompt!!.port)
        assertTrue(prompt.autoOpenFirewall)
        assertEquals(LinuxDistroFamily.CENTOS_RHEL, prompt.probe.distroFamily)
        assertTrue(prompt.probe.installCommand.contains("sudo dnf install -y iperf3"))
        assertTrue(prompt.probe.installCommand.contains("epel-release"))

        // 3. Confirm 1-click install & start -> installs and starts daemon on port 15201
        viewModel.confirmInstallAndStartIperf3Server()
        assertNull(viewModel.uiState.value.pendingIperf3InstallPrompt)
        assertTrue(installed)
        assertTrue(executedCommands.last().contains("PORT=15201"))
        // Service utility run should not be auto-checked for NodeSeek benchmark export
        assertTrue(viewModel.uiState.value.selectedRunIdsForExport.isEmpty())

        // 4. Stop iperf3 server
        viewModel.stopIperf3Server()
        assertTrue(executedCommands.last().contains("PORT=15201"))
        assertTrue(executedCommands.last().contains("pkill"))
    }
}
