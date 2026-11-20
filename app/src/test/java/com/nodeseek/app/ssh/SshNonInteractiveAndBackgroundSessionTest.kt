package com.nodeseek.app.ssh

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.domain.radar.InAppMessageKind
import com.nodeseek.app.domain.radar.InAppMessagePopup
import com.nodeseek.app.domain.radar.InboxTargetResolver
import com.nodeseek.app.domain.radar.NotificationTapTarget
import com.nodeseek.app.domain.ssh.InMemoryTestCipher
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshAutoRespondConfig
import com.nodeseek.app.domain.ssh.SshCommandRewriter
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshExecutionRecord
import com.nodeseek.app.domain.ssh.SshHistoryGrouping
import com.nodeseek.app.domain.ssh.SshPresetCatalog
import com.nodeseek.app.domain.ssh.SshPromptAutoResponder
import com.nodeseek.app.domain.ssh.SshRunStatus
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.domain.ssh.SshStageDetector
import com.nodeseek.app.domain.ssh.SshStdinWriter
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.domain.ssh.SshTransport
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import com.nodeseek.app.ui.ssh.LiteSshSessionManager
import com.nodeseek.app.ui.ssh.LiteSshViewModel
import com.nodeseek.app.ui.ssh.SshConnectionPhase
import com.nodeseek.app.ui.ssh.SshForegroundServiceController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class SshNonInteractiveAndBackgroundSessionTest {

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

    private fun createVault(clock: () -> Long = { 1_750_000_000_000L }): SshServerVault = SshServerVault(
        vaultFile = File(tempFolder.root, "sshbench_vault_${System.nanoTime()}.json"),
        cipher = InMemoryTestCipher(),
        ioDispatcher = testDispatcher,
        clock = clock,
    )

    // ── 1. Non-interactive Script Command Rewriting & Built-In Presets ──────────────────────────

    @Test
    fun `SshCommandRewriter rewrites interactive NodeQuality, RegionRestrictionCheck, NextTrace, GoECS, legacy ECS, and package managers idempotently`() {
        // 1. Legacy NodeQuality invocation with invalid -n / -l CLI flags -> converted to 4-line stdin + valid getopts only
        val rewrittenNq = SshCommandRewriter.rewriteToNonInteractive(
            "bash <(curl -sL https://run.NodeQuality.com) -n -l -4",
        )
        assertTrue(rewrittenNq.startsWith(SshCommandRewriter.HEADLESS_ENV_PREFIX))
        assertTrue(rewrittenNq.contains("printf 'y\\ny\\nl\\ny\\n' | bash <(curl -sL https://run.NodeQuality.com) -4"))
        assertFalse(rewrittenNq.contains(" -n"))
        assertFalse(rewrittenNq.endsWith("-l"))

        // 2. RegionRestrictionCheck / check.unlock.media without -R -> appends -R 0 to skip inputOptions()
        val rewrittenUnlock = SshCommandRewriter.rewriteToNonInteractive(
            "bash <(curl -Ls check.unlock.media) -M 4",
        )
        assertTrue(rewrittenUnlock.contains("-M 4 -R 0"))

        // Existing -R 66 is preserved without duplicate -R
        val preservedUnlock = SshCommandRewriter.rewriteToNonInteractive(
            "bash <(curl -Ls check.unlock.media) -M 6 -R 66",
        )
        assertTrue(preservedUnlock.contains("-M 6 -R 66"))
        assertFalse(preservedUnlock.contains("-R 0"))

        // 3. NextTrace --fast-trace without piped stdin -> injects 2-line IPv4/IPv6 + ISP choice stdin
        val rewrittenNtV4 = SshCommandRewriter.rewriteToNonInteractive("nexttrace --fast-trace --tcp")
        assertTrue(rewrittenNtV4.contains("printf '1\\n1\\n' | nexttrace --fast-trace --tcp"))

        val rewrittenNtV6 = SshCommandRewriter.rewriteToNonInteractive("nexttrace --fast-trace --ipv6")
        assertTrue(rewrittenNtV6.contains("printf '2\\n1\\n' | nexttrace --fast-trace --ipv6"))

        // 4. GoECS binary without -menu=false -> appends -menu=false while leaving goecs.sh install intact
        val rewrittenGoEcs = SshCommandRewriter.rewriteToNonInteractive(
            "./goecs.sh install && goecs -l zh",
        )
        assertTrue(rewrittenGoEcs.contains("noninteractive=true"))
        assertTrue(rewrittenGoEcs.contains("./goecs.sh install && goecs -l zh -menu=false"))

        // 5. Legacy spiritLHLS/ecs shell script -> appends -m 1 when missing
        val rewrittenLegacyEcs = SshCommandRewriter.rewriteToNonInteractive("bash ecs.sh")
        assertTrue(rewrittenLegacyEcs.contains("bash ecs.sh -m 1"))
        val explicitMenuEcs = SshCommandRewriter.rewriteToNonInteractive("bash ecs.sh -m 2")
        assertTrue(explicitMenuEcs.contains("bash ecs.sh -m 2"))
        assertFalse(explicitMenuEcs.contains("-m 1"))

        // 6. Package manager install commands -> injects -y
        val rewrittenApt = SshCommandRewriter.rewriteToNonInteractive("apt-get install fio iperf3 && yum install -y curl")
        assertTrue(rewrittenApt.contains("apt-get -y install fio iperf3"))
        assertTrue(rewrittenApt.contains("yum install -y curl"))

        // 7. Piped YABS / Bench.sh -> injects conditional stdbuf -oL -eL line-buffering
        val rewrittenYabs = SshCommandRewriter.rewriteToNonInteractive("curl -sL yabs.sh | bash -s -- -5")
        assertTrue(rewrittenYabs.contains("stdbuf -oL -eL"))
        assertTrue(rewrittenYabs.contains("bash -s -- -5"))

        // 8. Idempotence across all built-in presets and rewritten commands
        for (preset in SshPresetCatalog.builtInPresets) {
            val built = preset.buildCommand()
            assertEquals(
                "Preset ${preset.id} buildCommand must be idempotent under rewriteToNonInteractive",
                built,
                SshCommandRewriter.rewriteToNonInteractive(built),
            )
        }
        assertEquals(rewrittenNq, SshCommandRewriter.rewriteToNonInteractive(rewrittenNq))
        assertEquals(rewrittenNtV6, SshCommandRewriter.rewriteToNonInteractive(rewrittenNtV6))
        assertEquals(rewrittenGoEcs, SshCommandRewriter.rewriteToNonInteractive(rewrittenGoEcs))
    }

    // ── 2. PTY Prompt Auto-Responder & Anti-Misfire Safety ──────────────────────────────────────

    @Test
    fun `SshPromptAutoResponder answers y-n and press-enter prompts across split chunks without false positives`() {
        val responder = SshPromptAutoResponder(SshAutoRespondConfig(enabled = true, maxResponsesPerRun = 6))

        // Normal output and CLI help text mentioning [y/N] must NOT misfire
        assertNull(responder.onOutputChunk("  -y, --yes    Automatic yes to [y/N] prompts\n"))
        assertNull(responder.onOutputChunk("Usage: script.sh [options] [y/N]\n"))
        assertNull(responder.onOutputChunk("\u001B[32mNetflix: Yes (Region: JP)\u001B[0m\n"))

        // Password, passphrase, sudo, host-key fingerprint, and destructive prompts must NEVER misfire
        assertNull(responder.onOutputChunk("[sudo] password for ubuntu: "))
        responder.onOutputChunk("\n")
        assertNull(responder.onOutputChunk("Enter passphrase for key '/root/.ssh/id_ed25519': "))
        responder.onOutputChunk("\n")
        assertNull(responder.onOutputChunk("Are you sure you want to continue connecting (yes/no/[fingerprint])? "))
        responder.onOutputChunk("\n")
        assertNull(responder.onOutputChunk("Format partition /dev/vda1 now? [y/N]: "))
        responder.onOutputChunk("\n")
        assertNull(responder.onOutputChunk("确认删除全部测试文件并重启？[y/N]："))
        responder.onOutputChunk("\n")
        assertEquals(0, responder.responseCount)

        // Split-chunk [y/N] prompt -> fires once when prompt completes, does NOT double-fire before newline
        assertNull(responder.onOutputChunk("\u001B[33mUse China mirror for installation? ([y]/"))
        val d1 = responder.onOutputChunk("n) \u001B[0m")
        assertNotNull(d1)
        assertEquals("y\n", d1!!.response)
        assertEquals(1, d1.responseNumber)
        // Trailing space before echo newline must NOT trigger duplicate reply
        assertNull(responder.onOutputChunk(" "))

        // Next line: NodeQuality [y/f/v/n] prompt
        val d2 = responder.onOutputChunk("y\r\n运行 HardwareQuality 测试？（回车默认 'y'）[y/f/v/n]：")
        assertNotNull(d2)
        assertEquals("y\n", d2!!.response)
        assertEquals(2, d2.responseNumber)

        // Press Enter inline prompt -> sends "\n"
        val d3 = responder.onOutputChunk("y\r\n请输入正确数字或直接按回车:")
        assertNotNull(d3)
        assertEquals("\n", d3!!.response)
        assertEquals(3, d3.responseNumber)

        // Standalone echo-before-read Press Enter line -> sends "\n"
        val d4 = responder.onOutputChunk("\r\nPress ENTER to continue...\r\n")
        assertNotNull(d4)
        assertEquals("\n", d4!!.response)
        assertEquals(4, d4.responseNumber)

        // Menu default choice prompt -> sends "\n"
        val d5 = responder.onOutputChunk("1. 北京三网\n2. 上海三网\n请选择选项：")
        assertNotNull(d5)
        assertEquals("\n", d5!!.response)
        assertEquals(5, d5.responseNumber)

        // 6th response reaches maxResponsesPerRun = 6; 7th prompt is safely ignored
        val d6 = responder.onOutputChunk("\nContinue next step? (Y/n): ")
        assertNotNull(d6)
        assertEquals("y\n", d6!!.response)
        assertNull(responder.onOutputChunk("\nContinue seventh step? [y/N]: "))
        assertEquals(6, responder.responseCount)

        // Disabled config never responds
        val disabled = SshPromptAutoResponder(SshAutoRespondConfig(enabled = false))
        assertNull(disabled.onOutputChunk("Continue? [y/N]: "))
    }

    @Test
    fun `LiteSshExecutor automatically unblocks multi-prompt interactive PTY script via SshStdinWriter`() = runTest {
        val writtenStdin = mutableListOf<String>()
        val stdinChannel = Channel<String>(Channel.UNLIMITED)

        val interactiveTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> =
                error("Expected 3-arg execute with onStdinReady")

            override fun execute(
                request: SshConnectionRequest,
                command: String,
                onStdinReady: (SshStdinWriter) -> Unit,
            ): Flow<SshStreamEvent> = flow {
                onStdinReady { input ->
                    writtenStdin += input
                    stdinChannel.trySend(input)
                }
                emit(SshStreamEvent.Connected)
                emit(SshStreamEvent.OutputChunk("Install benchmark helper? [y/N]: "))
                val r1 = stdinChannel.receive()
                emit(SshStreamEvent.OutputChunk("$r1\nPlease Input the Correct Number or Press ENTER:"))
                val r2 = stdinChannel.receive()
                emit(SshStreamEvent.OutputChunk("$r2\n\u001B[32mAll checks finished!\u001B[0m\n"))
                emit(SshStreamEvent.Completed(exitCode = 0))
            }
        }

        val executor = LiteSshExecutor(transport = interactiveTransport, clock = { 1_750_000_000_000L })
        val events = mutableListOf<com.nodeseek.app.domain.ssh.LiteSshExecutionEvent>()
        executor.runCommand(
            request = SshConnectionRequest(host = "203.0.113.9"),
            command = "custom_bench.sh",
            autoRespondConfig = SshAutoRespondConfig(enabled = true),
        ).collect { events += it }

        assertEquals(listOf("y\n", "\n"), writtenStdin)
        val completed = events.filterIsInstance<com.nodeseek.app.domain.ssh.LiteSshExecutionEvent.Completed>().single()
        assertEquals(0, completed.exitCode)
        assertTrue(completed.sanitizedAnsiOutput.contains("All checks finished!"))

        // Also verify 2-arg SshTransport implementations (like JSchSshTransport) receive SshStdinCallbackContext via flowOn
        val contextStdin = mutableListOf<String>()
        val contextGate = Channel<String>(Channel.UNLIMITED)
        val twoArgTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                val callback = kotlin.coroutines.coroutineContext[com.nodeseek.app.domain.ssh.SshStdinCallbackContext]?.onStdinReady
                assertNotNull("SshStdinCallbackContext must propagate into 2-arg execute()", callback)
                callback?.invoke { input ->
                    contextStdin += input
                    contextGate.trySend(input)
                }
                emit(SshStreamEvent.Connected)
                emit(SshStreamEvent.OutputChunk("Proceed with benchmark? (Y/n): "))
                val reply = contextGate.receive()
                emit(SshStreamEvent.OutputChunk("$reply\nDone!\n"))
                emit(SshStreamEvent.Completed(exitCode = 0))
            }
        }
        val twoArgEvents = mutableListOf<com.nodeseek.app.domain.ssh.LiteSshExecutionEvent>()
        LiteSshExecutor(transport = twoArgTransport).runCommand(
            request = SshConnectionRequest(host = "203.0.113.10"),
            command = "test.sh",
        ).collect { twoArgEvents += it }
        assertEquals(listOf("y\n"), contextStdin)
    }

    // ── 3. Long-Silence Progress & Stage Detection ──────────────────────────────────────────────

    @Test
    fun `SshStageDetector infers YABS fio, iperf3, and Geekbench phases and flags long silence`() {
        val startedAt = 1_750_000_000_000L

        // Phase 1: fio disk benchmark silent for 12s
        val fioProgress = SshStageDetector.detect(
            presetId = SshPresetCatalog.PRESET_YABS,
            command = "curl -sL https://yabs.sh | bash -s -- -5",
            sanitizedOutput = "Basic System Information:\nPreparing system for disk tests...",
            isConnecting = false,
            startedAtEpochMillis = startedAt,
            lastOutputEpochMillis = startedAt + 3_000L,
            nowEpochMillis = startedAt + 15_000L,
            silenceThresholdMillis = 8_000L,
        )
        assertTrue(fioProgress.isSilentLong)
        assertEquals(12_000L, fioProgress.silenceMillis)
        assertEquals("fio 磁盘读写测试阶段", fioProgress.currentStageLabel)
        assertEquals("脚本仍在运行（fio 磁盘读写测试阶段可能需要数分钟）", fioProgress.silentRunningBannerText)

        // Phase 2: iperf3 output just arrived 2s ago -> not silent, stage updated to iperf3
        val iperfProgress = SshStageDetector.detect(
            presetId = SshPresetCatalog.PRESET_YABS,
            command = "curl -sL https://yabs.sh | bash -s -- -5",
            sanitizedOutput = "fio Disk Speed Tests (Mixed R/W 50/50):\niperf3 Network Speed Tests (IPv4):",
            isConnecting = false,
            startedAtEpochMillis = startedAt,
            lastOutputEpochMillis = startedAt + 60_000L,
            nowEpochMillis = startedAt + 62_000L,
            silenceThresholdMillis = 8_000L,
        )
        assertFalse(iperfProgress.isSilentLong)
        assertEquals("iperf3 网络测速阶段", iperfProgress.currentStageLabel)

        // Phase 3: Geekbench running silently for 45s
        val gbProgress = SshStageDetector.detect(
            presetId = SshPresetCatalog.PRESET_YABS,
            command = "curl -sL https://yabs.sh | bash -s -- -5",
            sanitizedOutput = "fio Disk Speed Tests\niperf3 Network Speed Tests\nPerforming Geekbench 5 benchmark test...",
            isConnecting = false,
            startedAtEpochMillis = startedAt,
            lastOutputEpochMillis = startedAt + 90_000L,
            nowEpochMillis = startedAt + 135_000L,
            silenceThresholdMillis = 8_000L,
        )
        assertTrue(gbProgress.isSilentLong)
        assertEquals("Geekbench CPU 跑分阶段", gbProgress.currentStageLabel)
        assertEquals("脚本仍在运行（Geekbench CPU 跑分阶段可能需要数分钟）", gbProgress.silentRunningBannerText)
    }

    // ── 4. Background Session Survival, Foreground Service & Completion Notifications ───────────

    @Test
    fun `LiteSshSessionManager survives ViewModel onCleared when leaving screen, updates foreground service, and emits completion notices`() = runTest {
        val nowMillis = AtomicLong(1_750_000_000_000L)
        val vault = createVault(clock = nowMillis::get)
        vault.saveServer(
            SshServerDraft(
                alias = "瓦工 DC6 主力",
                host = "198.51.100.66",
                port = 22,
                username = "root",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "pw-dc6",
            ),
        )

        val finishGate = CompletableDeferred<Unit>()
        val mockTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                emit(SshStreamEvent.Connected)
                emit(SshStreamEvent.OutputChunk("Basic System Information:\nPerforming Geekbench 5 benchmark test...\n"))
                finishGate.await()
                emit(SshStreamEvent.OutputChunk("\u001B[32mGeekbench 5 Single: 1350 | Multi: 4820\u001B[0m\n"))
                emit(SshStreamEvent.Completed(exitCode = 0))
            }
        }

        val fgsUpdates = mutableListOf<Triple<String, String, String>>()
        var fgsFinishedCount = 0
        val fakeFgsController = object : SshForegroundServiceController {
            override fun onSessionRunning(serverAlias: String, presetName: String, statusLine: String) {
                fgsUpdates += Triple(serverAlias, presetName, statusLine)
            }

            override fun onSessionFinished() {
                fgsFinishedCount += 1
            }
        }

        val completedNotifications = mutableListOf<SshExecutionRecord>()
        val appScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val popupEvents = mutableListOf<InAppMessagePopup>()

        try {
            val sessionManager = LiteSshSessionManager(
                vaultRepository = vault,
                executor = LiteSshExecutor(transport = mockTransport, clock = nowMillis::get),
                externalScope = appScope,
                foregroundServiceController = fakeFgsController,
                onBenchmarkCompleted = { record, _ -> completedNotifications += record },
                clock = nowMillis::get,
                enableElapsedTicker = false,
                silenceThresholdMillis = 8_000L,
            )

            val popupJob = appScope.launch {
                sessionManager.completionPopupEvents.collect { popupEvents += it }
            }

            // Screen 1 opens -> creates LiteSshViewModel in NavBackStackEntry's ViewModelStore
            val store1 = ViewModelStore()
            val vm1 = ViewModelProvider(
                store1,
                LiteSshViewModel.factory(sessionManager = sessionManager),
            )[LiteSshViewModel::class.java]

            vm1.selectPreset(SshPresetCatalog.PRESET_YABS)
            vm1.runSelectedPreset()
            assertTrue(vm1.uiState.value.isRunning)
            assertTrue(fgsUpdates.isNotEmpty())
            assertEquals("瓦工 DC6 主力", fgsUpdates.first().first)

            // User navigates away from LiteSshScreen -> NavBackStackEntry clears ViewModelStore!
            store1.clear()

            // Session MUST still be running in background despite ViewModel.onCleared()!
            assertTrue(sessionManager.uiState.value.isRunning)
            assertEquals(0, fgsFinishedCount)

            // Advance clock by 20s during Geekbench silence -> verify "仍在运行" banner & FGS update
            nowMillis.addAndGet(20_000L)
            sessionManager.refreshLiveProgress(nowMillis.get())
            assertTrue(sessionManager.uiState.value.liveProgress.isSilentLong)
            assertEquals(
                "脚本仍在运行（Geekbench CPU 跑分阶段可能需要数分钟）",
                sessionManager.uiState.value.liveProgress.silentRunningBannerText,
            )
            assertTrue(fgsUpdates.last().third.contains("脚本仍在运行"))

            // Benchmark finishes while user is still on another screen
            nowMillis.addAndGet(5_000L)
            finishGate.complete(Unit)

            assertFalse(sessionManager.uiState.value.isRunning)
            assertEquals(SshConnectionPhase.COMPLETED, sessionManager.uiState.value.connectionPhase)
            assertEquals(1, fgsFinishedCount)
            assertEquals(1, completedNotifications.size)
            assertEquals("瓦工 DC6 主力", completedNotifications.single().serverAlias)
            assertEquals(1, popupEvents.size)
            assertEquals(InAppMessageKind.SSH_BENCHMARK_COMPLETED, popupEvents.single().kind)
            assertEquals(
                NotificationTapTarget.OpenLiteSsh,
                InboxTargetResolver.toTapTarget(popupEvents.single()),
            )
            assertEquals(
                NotificationTapTarget.OpenLiteSsh,
                PulseNotificationDispatcher.decodeTapTarget(
                    PulseNotificationDispatcher.encodeTapTarget(NotificationTapTarget.OpenLiteSsh),
                ),
            )

            // User taps "返回测机界面" / notification -> new ViewModel attaches and sees completed report
            val store2 = ViewModelStore()
            val vm2 = ViewModelProvider(
                store2,
                LiteSshViewModel.factory(sessionManager = sessionManager),
            )[LiteSshViewModel::class.java]
            assertEquals(1, vm2.uiState.value.sessionRuns.size)
            assertTrue(vm2.uiState.value.displayedConsoleOutput.contains("Geekbench 5 Single: 1350"))
            store2.clear()
            popupJob.cancel()
        } finally {
            appScope.cancel()
        }
    }

    // ── 5. Local History Grouping by Machine Remark Name ────────────────────────────────────────

    @Test
    fun `SshHistoryGrouping buckets runs by server remark name, reflects renamed aliases, and orders newest first`() {
        val servers = listOf(
            SshServerProfile(
                id = "srv-dc6",
                alias = "瓦工 DC6 新备注",
                host = "198.51.100.10",
            ),
            SshServerProfile(
                id = "srv-tyo",
                alias = "东京软银",
                host = "203.0.113.20",
            ),
        )

        val runs = listOf(
            sampleRecord(
                id = "r1",
                serverId = "srv-dc6",
                serverAlias = "瓦工 DC6 旧名",
                serverHost = "198.51.100.10",
                presetName = "系统快检",
                startedAt = 1_000L,
            ),
            sampleRecord(
                id = "r2",
                serverId = "srv-tyo",
                serverAlias = "东京软银",
                serverHost = "203.0.113.20",
                presetName = "NextTrace 回程",
                startedAt = 3_000L,
            ),
            sampleRecord(
                id = "r3",
                serverId = "srv-dc6",
                serverAlias = "瓦工 DC6 新备注",
                serverHost = "198.51.100.10",
                presetName = "YABS 综合跑分",
                startedAt = 5_000L,
            ),
            sampleRecord(
                id = "r4",
                serverId = "deleted-srv",
                serverAlias = "",
                serverHost = "192.0.2.88",
                presetName = "流媒体解锁",
                startedAt = 2_000L,
            ),
        )

        val groups = SshHistoryGrouping.groupRunsByServerRemark(runs = runs, servers = servers)
        assertEquals(3, groups.size)

        // Group 0: 瓦工 DC6 新备注 (latest run at 5000L, merges old & new alias via serverId)
        assertEquals("瓦工 DC6 新备注", groups[0].serverAlias)
        assertEquals(listOf("r3", "r1"), groups[0].runs.map { it.id })

        // Group 1: 东京软银 (latest run at 3000L)
        assertEquals("东京软银", groups[1].serverAlias)
        assertEquals(listOf("r2"), groups[1].runs.map { it.id })

        // Group 2: deleted server with blank alias falls back to host
        assertEquals("192.0.2.88", groups[2].serverAlias)
        assertEquals(listOf("r4"), groups[2].runs.map { it.id })
    }

    private fun sampleRecord(
        id: String,
        serverId: String,
        serverAlias: String,
        serverHost: String,
        presetName: String,
        startedAt: Long,
    ) = SshExecutionRecord(
        id = id,
        serverId = serverId,
        serverAlias = serverAlias,
        serverHost = serverHost,
        presetId = "preset_$id",
        presetName = presetName,
        tabTitle = presetName,
        command = "echo $id",
        sanitizedAnsiOutput = "Output $id",
        exitCode = 0,
        status = SshRunStatus.SUCCEEDED,
        startedAtEpochMillis = startedAt,
        durationMillis = 2_500L,
    )
}
