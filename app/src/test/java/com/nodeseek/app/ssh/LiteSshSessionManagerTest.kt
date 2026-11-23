package com.nodeseek.app.ssh

import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.domain.ssh.HostKeyInfo
import com.nodeseek.app.domain.ssh.InMemoryTestCipher
import com.nodeseek.app.domain.ssh.Iperf3RemoteServerCommands
import com.nodeseek.app.domain.ssh.Iperf3ServiceOwnership
import com.nodeseek.app.domain.ssh.LiteSshExecutionEvent
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshHostKeyMismatchException
import com.nodeseek.app.domain.ssh.SshPresetCatalog
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshStdinWriter
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.domain.ssh.SshTransport
import com.nodeseek.app.ui.ssh.LiteSshSessionManager
import com.nodeseek.app.ui.ssh.SshConnectionPhase
import com.nodeseek.app.ui.ssh.SshForegroundServiceController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    fun `starting a second run while first run cancels does not stop foreground service or clobber new run`() = runTest {
        val vault = createVault()
        vault.saveServer(
            SshServerDraft(
                alias = "Race VPS",
                host = "203.0.113.55",
                secret = "pw",
            ),
        )

        val run2Gate = CompletableDeferred<Unit>()
        val callCount = AtomicInteger(0)
        val mockTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                val n = callCount.incrementAndGet()
                emit(SshStreamEvent.Connected)
                if (n == 1) {
                    emit(SshStreamEvent.OutputChunk("run1-started\n"))
                    awaitCancellation()
                } else {
                    emit(SshStreamEvent.OutputChunk("run2-started\n"))
                    run2Gate.await()
                    emit(SshStreamEvent.Completed(exitCode = 0))
                }
            }
        }

        val finishedCount = AtomicInteger(0)
        val runningCount = AtomicInteger(0)
        val fgController = object : SshForegroundServiceController {
            override fun onSessionRunning(serverAlias: String, presetName: String, statusLine: String) {
                runningCount.incrementAndGet()
            }

            override fun onSessionFinished() {
                finishedCount.incrementAndGet()
            }
        }

        val manager = LiteSshSessionManager(
            vaultRepository = vault,
            executor = LiteSshExecutor(transport = mockTransport, outputThrottleMillis = 0L),
            externalScope = CoroutineScope(SupervisorJob() + testDispatcher),
            foregroundServiceController = fgController,
            enableElapsedTicker = false,
        )

        manager.runSelectedPreset()
        assertTrue(manager.uiState.value.isRunning)

        // Immediately start Run #2 while Run #1 is still active
        manager.runSelectedPreset()
        assertTrue("Run #2 must remain active", manager.uiState.value.isRunning)
        assertEquals(
            "Cancelled Run #1 finally block must NOT call onSessionFinished while Run #2 is active",
            0,
            finishedCount.get(),
        )
        assertTrue(manager.globalBarState.value.isRunning)

        // Cancel Run #2 — should work because executionJob was NOT clobbered to null by Run #1
        manager.cancelExecution()
        assertFalse(manager.uiState.value.isRunning)
        assertEquals(1, finishedCount.get())

        manager.disposeOwnedScopeForTests()
    }

    @Test
    fun `confirmTrustChangedHostKey persists approved key and retries strictly rejecting a third swapped key`() = runTest {
        val vault = createVault()
        val server = vault.saveServer(
            SshServerDraft(
                alias = "TOFU Strict VPS",
                host = "203.0.113.71",
                secret = "pw",
            ),
        )
        val fpA = "SHA256:keyAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val fpB = "SHA256:keyBBBBBBBBBBBBBBBBBBBBBBBBBBB"
        val fpC = "SHA256:keyCCCCCCCCCCCCCCCCCCCCCCCCCCC"
        vault.updateHostKeyFingerprint(server.id, fpA, "ssh-ed25519")

        val seenRequests = mutableListOf<SshConnectionRequest>()
        val mockTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                seenRequests += request
                val presentedFp = if (seenRequests.size == 1) fpB else fpC
                if (request.expectedHostKeyFingerprint != presentedFp && !request.allowHostKeyMismatchOverride) {
                    throw SshHostKeyMismatchException(
                        expectedFingerprint = request.expectedHostKeyFingerprint.orEmpty(),
                        actualHostKey = HostKeyInfo(
                            host = request.host,
                            port = request.port,
                            algorithm = "ssh-ed25519",
                            fingerprintSha256 = presentedFp,
                        ),
                        serverId = request.serverId,
                        serverAlias = request.alias,
                    )
                }
                emit(SshStreamEvent.Connected)
                emit(SshStreamEvent.Completed(exitCode = 0))
            }
        }

        val manager = LiteSshSessionManager(
            vaultRepository = vault,
            executor = LiteSshExecutor(transport = mockTransport, outputThrottleMillis = 0L),
            externalScope = CoroutineScope(SupervisorJob() + testDispatcher),
            enableElapsedTicker = false,
        )

        // First attempt hits key B mismatch
        manager.runSelectedPreset()
        val firstPrompt = manager.uiState.value.pendingHostKeyMismatch
        assertNotNull(firstPrompt)
        assertEquals(fpB, firstPrompt!!.actualHostKey.fingerprintSha256)

        // User trusts key B, but MITM swaps to key C on retry -> MUST be rejected!
        manager.confirmTrustChangedHostKey()
        assertEquals(2, seenRequests.size)
        assertFalse(
            "Retry after trusting key B must NOT disable strict host-key verification",
            seenRequests[1].allowHostKeyMismatchOverride,
        )
        assertEquals(fpB, seenRequests[1].expectedHostKeyFingerprint)
        assertEquals(SshConnectionPhase.FAILED, manager.uiState.value.connectionPhase)
        val secondPrompt = manager.uiState.value.pendingHostKeyMismatch
        assertNotNull("Swapped key C must trigger a new host-key mismatch prompt", secondPrompt)
        assertEquals(fpB, secondPrompt!!.expectedFingerprint)
        assertEquals(fpC, secondPrompt.actualHostKey.fingerprintSha256)

        manager.disposeOwnedScopeForTests()
    }

    @Test
    fun `sendTerminalInput preserves strict FIFO order under rapid burst writes`() = runBlocking {
        val vault = createVault()
        val server = vault.saveServer(
            SshServerDraft(
                alias = "FIFO Shell",
                host = "203.0.113.80",
                secret = "pw",
            ),
        )

        val totalChunks = 40
        val received = Collections.synchronizedList(mutableListOf<String>())
        val allReceivedLatch = CountDownLatch(totalChunks)
        val sessionGate = CompletableDeferred<Unit>()
        val mockTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> =
                execute(request, command) {}

            override fun execute(
                request: SshConnectionRequest,
                command: String,
                onStdinReady: (SshStdinWriter) -> Unit,
            ): Flow<SshStreamEvent> = flow {
                emit(SshStreamEvent.Connected)
                onStdinReady(
                    SshStdinWriter { chunk ->
                        received += chunk
                        allReceivedLatch.countDown()
                    },
                )
                sessionGate.await()
                emit(SshStreamEvent.Completed(exitCode = 0))
            }
        }

        val manager = LiteSshSessionManager(
            vaultRepository = vault,
            executor = LiteSshExecutor(transport = mockTransport, outputThrottleMillis = 0L),
            externalScope = CoroutineScope(SupervisorJob() + testDispatcher),
            enableElapsedTicker = false,
        )

        manager.openInteractiveTerminal(server.id)
        val expected = (0 until totalChunks).map { idx -> "k$idx" }
        expected.forEach { manager.sendTerminalInput(it) }

        assertTrue(
            "Timed out waiting for FIFO stdin chunks (got ${received.size}/$totalChunks)",
            allReceivedLatch.await(5, TimeUnit.SECONDS),
        )
        assertEquals(expected, received.toList())

        sessionGate.complete(Unit)
        manager.disposeOwnedScopeForTests()
    }

    @Test
    fun `cancelExecution and exportableRuns retain active server and preset metadata after UI selection changes`() = runTest {
        val vault = createVault()
        val serverA = vault.saveServer(SshServerDraft(id = "srv-a", alias = "Host A", host = "198.51.100.1", secret = "pw"))
        val serverB = vault.saveServer(SshServerDraft(id = "srv-b", alias = "Host B", host = "198.51.100.2", secret = "pw"))

        val gate = CompletableDeferred<Unit>()
        val mockTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                emit(SshStreamEvent.Connected)
                emit(SshStreamEvent.OutputChunk("partial benchmark line\n"))
                gate.await()
                emit(SshStreamEvent.Completed(exitCode = 0))
            }
        }

        val manager = LiteSshSessionManager(
            vaultRepository = vault,
            executor = LiteSshExecutor(transport = mockTransport, outputThrottleMillis = 0L),
            externalScope = CoroutineScope(SupervisorJob() + testDispatcher),
            enableElapsedTicker = false,
        )

        val presetA = SshPresetCatalog.findBuiltIn(SshPresetCatalog.PRESET_YABS)!!
        manager.selectServer(serverA.id)
        manager.selectPreset(presetA.id)
        manager.runSelectedPreset()

        // While Host A + YABS is running, user switches UI selection to Host B + Quick Check
        manager.selectServer(serverB.id)
        manager.selectPreset(SshPresetCatalog.PRESET_QUICK_CHECK)

        val liveExport = manager.uiState.value.exportableRuns.single()
        assertEquals(serverA.id, liveExport.serverId)
        assertEquals(presetA.id, liveExport.presetId)
        assertEquals(presetA.tabTitle, liveExport.tabTitle)

        // Cancel preserves Host A + YABS metadata on the cancelled session record
        manager.cancelExecution()
        val cancelledRecord = manager.uiState.value.sessionRuns.single()
        assertEquals(serverA.id, cancelledRecord.serverId)
        assertEquals(presetA.id, cancelledRecord.presetId)
        assertEquals(presetA.tabTitle, cancelledRecord.tabTitle)

        manager.disposeOwnedScopeForTests()
    }

    @Test
    fun `LiteSshExecutor throttle loop delivers trailing chunks arriving across throttle windows without stalling`() = runBlocking {
        val mockTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                emit(SshStreamEvent.Connected)
                emit(SshStreamEvent.OutputChunk("chunk-1\n"))
                delay(5)
                emit(SshStreamEvent.OutputChunk("chunk-2\n"))
                delay(30)
                emit(SshStreamEvent.OutputChunk("chunk-3\n"))
                delay(35)
                emit(SshStreamEvent.OutputChunk("chunk-4\n"))
                delay(40)
                emit(SshStreamEvent.Completed(exitCode = 0))
            }
        }

        val executor = LiteSshExecutor(
            transport = mockTransport,
            outputThrottleMillis = 20L,
        )
        val events = executor.runCommand(
            request = SshConnectionRequest(host = "203.0.113.10", username = "root"),
            command = "echo test",
        ).toList()

        val outputUpdates = events.filterIsInstance<LiteSshExecutionEvent.OutputUpdated>()
        assertTrue("Expected multiple throttled OutputUpdated events", outputUpdates.size >= 2)
        val lastUpdateText = outputUpdates.last().bufferSnapshot.fullAnsiText
        assertTrue("Expected trailing chunk-4 to be flushed before Completed, got:\n$lastUpdateText", lastUpdateText.contains("chunk-4"))
    }
}
