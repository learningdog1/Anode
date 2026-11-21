package com.nodeseek.app.ssh

import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.domain.ssh.InMemoryTestCipher
import com.nodeseek.app.domain.ssh.Iperf3RemoteServerCommands
import com.nodeseek.app.domain.ssh.Iperf3ServiceOwnership
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.domain.ssh.SshTransport
import com.nodeseek.app.ui.ssh.LiteSshSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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
class LiteSshSessionManagerTest {

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
        vaultFile = File(tempFolder.root, "session_mgr_vault_${System.nanoTime()}.json"),
        cipher = InMemoryTestCipher(),
        ioDispatcher = testDispatcher,
        clock = { 1_750_100_000_000L },
    )

    @Test
    fun `app-started iperf3 on default port 5201 records PID and start_time and stops strictly that instance`() = runTest {
        val vault = createVault()
        val server = vault.saveServer(
            SshServerDraft(
                alias = "TYO Pro",
                host = "198.51.100.12",
                port = 22,
                username = "root",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "pw",
            ),
        )

        val executedCommands = mutableListOf<String>()
        val mockTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                executedCommands += command
                emit(SshStreamEvent.Connected)
                when {
                    command.contains(Iperf3RemoteServerCommands.STOP_MARKER_PREFIX) -> {
                        emit(
                            SshStreamEvent.OutputChunk(
                                "__ANODE_IPERF3_STOP__|result=stopped|port=5201|pid=18420\n" +
                                    "✓ 已停止 VPS 上的 iperf3 服务端（本应用实例，端口 5201，PID 18420）\n",
                            ),
                        )
                        emit(SshStreamEvent.Completed(exitCode = 0))
                    }
                    else -> {
                        emit(
                            SshStreamEvent.OutputChunk(
                                "__ANODE_IPERF3_STATE__|mode=started|port=5201|pid=18420|start_time=44556677\n" +
                                    "✓ iperf3 服务端已在后台守护运行（TCP/UDP 端口 5201，PID 18420）\n",
                            ),
                        )
                        emit(SshStreamEvent.Completed(exitCode = 0))
                    }
                }
            }
        }

        val manager = LiteSshSessionManager(
            vaultRepository = vault,
            executor = LiteSshExecutor(transport = mockTransport),
            externalScope = CoroutineScope(SupervisorJob() + testDispatcher),
            enableElapsedTicker = false,
        )

        manager.startIperf3Server(portOverride = 5201)

        assertEquals(1, executedCommands.size)
        val startCommand = executedCommands.single()
        assertTrue(startCommand.contains("PORT=5201"))
        assertTrue(startCommand.contains("""iperf3 -s -p "${'$'}PORT""""))

        val attachment = manager.uiState.value.iperf3AttachmentFor(serverId = server.id, port = 5201)
        assertNotNull(attachment)
        assertEquals(Iperf3ServiceOwnership.APP_STARTED, attachment!!.ownership)
        assertTrue(attachment.isAppStarted)
        assertFalse(attachment.isExternalReused)
        assertEquals(18420L, attachment.ownedInstance?.pid)
        assertEquals("44556677", attachment.ownedInstance?.startTime)
        assertEquals(5201, attachment.ownedInstance?.port)
        assertTrue(manager.uiState.value.statusMessage.orEmpty().contains("PID 18420"))

        manager.stopIperf3Server(portOverride = 5201)

        assertEquals(2, executedCommands.size)
        val stopCommand = executedCommands.last()
        assertTrue(stopCommand.contains("PORT=5201"))
        assertTrue(stopCommand.contains("""CLIENT_PID="18420""""))
        assertTrue(stopCommand.contains("CLIENT_START='44556677'"))
        assertFalse(stopCommand.contains("pkill"))
        assertFalse(stopCommand.contains("killall"))
        assertFalse(stopCommand.contains("pgrep"))
        assertNull(manager.uiState.value.iperf3AttachmentFor(serverId = server.id, port = 5201))
        assertTrue(manager.uiState.value.statusMessage.orEmpty().contains("本应用启动"))

        manager.disposeOwnedScopeForTests()
    }

    @Test
    fun `reused external iperf3 service disconnects locally without sending any remote SSH kill command`() = runTest {
        val vault = createVault()
        val server = vault.saveServer(
            SshServerDraft(
                alias = "HK Shared",
                host = "203.0.113.66",
                port = 22,
                username = "debian",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "pw",
            ),
        )

        val executedCommands = mutableListOf<String>()
        val mockTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                executedCommands += command
                emit(SshStreamEvent.Connected)
                emit(
                    SshStreamEvent.OutputChunk(
                        "__ANODE_IPERF3_STATE__|mode=external_reused|port=5201\n" +
                            "✓ 检测到端口 5201 已有外部 iperf3 服务端正在运行，已直接复用\n",
                    ),
                )
                emit(SshStreamEvent.Completed(exitCode = 0))
            }
        }

        val manager = LiteSshSessionManager(
            vaultRepository = vault,
            executor = LiteSshExecutor(transport = mockTransport),
            externalScope = CoroutineScope(SupervisorJob() + testDispatcher),
            enableElapsedTicker = false,
        )

        // 1. Start detects external iperf3 on port 5201 -> marks EXTERNAL_REUSED
        manager.startIperf3Server(portOverride = 5201)
        assertEquals(1, executedCommands.size)

        val externalAttachment = manager.uiState.value.iperf3AttachmentFor(serverId = server.id, port = 5201)
        assertNotNull(externalAttachment)
        assertEquals(Iperf3ServiceOwnership.EXTERNAL_REUSED, externalAttachment!!.ownership)
        assertTrue(externalAttachment.isExternalReused)
        assertFalse(externalAttachment.isAppStarted)
        assertNull(externalAttachment.ownedInstance)
        assertTrue(manager.uiState.value.statusMessage.orEmpty().contains("外部 iperf3 服务"))

        // 2. Explicit disconnect clears local attachment and sends ZERO remote SSH commands
        manager.disconnectExternalIperf3Server(portOverride = 5201)
        assertEquals("Disconnect must NOT execute any remote SSH command", 1, executedCommands.size)
        assertNull(manager.uiState.value.iperf3AttachmentFor(serverId = server.id, port = 5201))
        val disconnectMsg = manager.uiState.value.statusMessage.orEmpty()
        assertTrue("Expected Chinese disconnect message, got: $disconnectMsg", disconnectMsg.contains("已断开"))
        assertTrue("Expected non-termination note, got: $disconnectMsg", disconnectMsg.contains("未终止远程进程"))

        // 3. Re-attach external service and verify even calling stopIperf3Server() directly NEVER sends a remote kill
        manager.startIperf3Server(portOverride = 5201)
        assertEquals(2, executedCommands.size)
        assertTrue(manager.uiState.value.iperf3AttachmentFor(serverId = server.id, port = 5201)?.isExternalReused == true)

        manager.stopIperf3Server(portOverride = 5201)
        assertEquals(
            "stopIperf3Server on external reused service must NOT execute any remote SSH kill script",
            2,
            executedCommands.size,
        )
        assertNull(manager.uiState.value.iperf3AttachmentFor(serverId = server.id, port = 5201))
        assertTrue(manager.uiState.value.statusMessage.orEmpty().contains("已断开"))

        manager.disposeOwnedScopeForTests()
    }

    @Test
    fun `per-port attachments isolate external 5201 service from app-started 15201 daemon on same server`() = runTest {
        val vault = createVault()
        val server = vault.saveServer(
            SshServerDraft(
                alias = "US MultiPort",
                host = "198.51.100.88",
                port = 22,
                username = "root",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "pw",
            ),
        )

        val executedCommands = mutableListOf<String>()
        val mockTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                executedCommands += command
                emit(SshStreamEvent.Connected)
                when {
                    command.contains(Iperf3RemoteServerCommands.STOP_MARKER_PREFIX) -> {
                        emit(
                            SshStreamEvent.OutputChunk(
                                "__ANODE_IPERF3_STOP__|result=stopped|port=15201|pid=31337\n",
                            ),
                        )
                        emit(SshStreamEvent.Completed(exitCode = 0))
                    }
                    command.contains("PORT=5201") -> {
                        emit(SshStreamEvent.OutputChunk("__ANODE_IPERF3_STATE__|mode=external_reused|port=5201\n"))
                        emit(SshStreamEvent.Completed(exitCode = 0))
                    }
                    else -> {
                        emit(
                            SshStreamEvent.OutputChunk(
                                "__ANODE_IPERF3_STATE__|mode=started|port=15201|pid=31337|start_time=887766\n",
                            ),
                        )
                        emit(SshStreamEvent.Completed(exitCode = 0))
                    }
                }
            }
        }

        val manager = LiteSshSessionManager(
            vaultRepository = vault,
            executor = LiteSshExecutor(transport = mockTransport),
            externalScope = CoroutineScope(SupervisorJob() + testDispatcher),
            enableElapsedTicker = false,
        )

        manager.startIperf3Server(portOverride = 5201)
        manager.startIperf3Server(portOverride = 15201)
        assertEquals(2, executedCommands.size)

        assertTrue(manager.uiState.value.iperf3AttachmentFor(server.id, 5201)?.isExternalReused == true)
        assertTrue(manager.uiState.value.iperf3AttachmentFor(server.id, 15201)?.isAppStarted == true)

        // Disconnecting external port 5201 leaves app-started port 15201 intact and runs 0 SSH commands
        manager.disconnectExternalIperf3Server(portOverride = 5201)
        assertEquals(2, executedCommands.size)
        assertNull(manager.uiState.value.iperf3AttachmentFor(server.id, 5201))
        assertNotNull(manager.uiState.value.iperf3AttachmentFor(server.id, 15201))

        // Stopping app-started port 15201 sends scoped stop script for PID 31337 + start_time 887766 + port 15201
        manager.stopIperf3Server(portOverride = 15201)
        assertEquals(3, executedCommands.size)
        val stopCmd = executedCommands.last()
        assertTrue(stopCmd.contains("PORT=15201"))
        assertTrue(stopCmd.contains("""CLIENT_PID="31337""""))
        assertTrue(stopCmd.contains("CLIENT_START='887766'"))
        assertNull(manager.uiState.value.iperf3AttachmentFor(server.id, 15201))

        manager.disposeOwnedScopeForTests()
    }

    @Test
    fun `recycled PID mismatch outcome clears stale local attachment and reports safe refusal message`() = runTest {
        val vault = createVault()
        val server = vault.saveServer(
            SshServerDraft(
                alias = "SG Edge",
                host = "203.0.113.100",
                port = 22,
                username = "root",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "pw",
            ),
        )

        val mockTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                emit(SshStreamEvent.Connected)
                if (command.contains(Iperf3RemoteServerCommands.STOP_MARKER_PREFIX)) {
                    emit(
                        SshStreamEvent.OutputChunk(
                            "__ANODE_IPERF3_STOP__|result=stale_mismatch|port=5201|pid=4500\n",
                        ),
                    )
                } else {
                    emit(
                        SshStreamEvent.OutputChunk(
                            "__ANODE_IPERF3_STATE__|mode=started|port=5201|pid=4500|start_time=102030\n",
                        ),
                    )
                }
                emit(SshStreamEvent.Completed(exitCode = 0))
            }
        }

        val manager = LiteSshSessionManager(
            vaultRepository = vault,
            executor = LiteSshExecutor(transport = mockTransport),
            externalScope = CoroutineScope(SupervisorJob() + testDispatcher),
            enableElapsedTicker = false,
        )

        manager.startIperf3Server(portOverride = 5201)
        assertNotNull(manager.uiState.value.iperf3AttachmentFor(server.id, 5201))

        manager.stopIperf3Server(portOverride = 5201)
        assertNull(manager.uiState.value.iperf3AttachmentFor(server.id, 5201))
        val msg = manager.uiState.value.statusMessage.orEmpty()
        assertTrue("Expected PID reuse refusal message, got: $msg", msg.contains("PID 已复用") && msg.contains("已拒绝终止"))

        manager.disposeOwnedScopeForTests()
    }
}
