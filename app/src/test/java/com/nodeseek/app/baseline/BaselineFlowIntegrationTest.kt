package com.nodeseek.app.baseline

import com.nodeseek.app.data.baseline.BenchmarkBaselineStore
import com.nodeseek.app.data.netbench.NetBenchHistoryStore
import com.nodeseek.app.data.netbench.NetBenchNetworkBridge
import com.nodeseek.app.data.netbench.NetBenchSocketChannel
import com.nodeseek.app.data.netbench.NetBenchTargetResolver
import com.nodeseek.app.data.netbench.ResolvedNetworkBinding
import com.nodeseek.app.data.netbench.TracerouteKernelOps
import com.nodeseek.app.data.netbench.TracerouteProbeReply
import com.nodeseek.app.data.netbench.TracerouteRunner
import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.domain.baseline.BaselineOverallVerdict
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.NetBenchNetworkSelector
import com.nodeseek.app.domain.netbench.NetBenchTransportKind
import com.nodeseek.app.domain.netbench.NetworkDescriptor
import com.nodeseek.app.domain.ssh.InMemoryTestCipher
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshPresetCatalog
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.domain.ssh.SshTransport
import com.nodeseek.app.ui.netbench.NetBenchSessionController
import com.nodeseek.app.ui.ssh.LiteSshSessionManager
import com.nodeseek.app.ui.ssh.NoOpSshForegroundServiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

@OptIn(ExperimentalCoroutinesApi::class)
class BaselineFlowIntegrationTest {

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
    fun `LiteSshSessionManager saves baseline from NodeQuality run and compares degraded run`() = runBlocking {
        val vault = SshServerVault(
            vaultFile = File(tempFolder.root, "vault.json"),
            cipher = InMemoryTestCipher(),
            ioDispatcher = testDispatcher,
        )
        val server = vault.saveServer(
            SshServerDraft(
                alias = "HK-GIA",
                host = "203.0.113.50",
                port = 22,
                username = "root",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "pw",
            ),
        )
        val baselineStore = BenchmarkBaselineStore(
            storeFile = File(tempFolder.root, "baselines.json"),
            ioDispatcher = testDispatcher,
        )

        var runNumber = 0
        val fakeTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                runNumber++
                emit(SshStreamEvent.Connected)
                val output = if (runNumber == 1) {
                    """
                        正在运行 IP 质量测试...
                        原生IP | 机房 IDC | AS4809
                        Netflix: Yes (Region: HK)
                        正在运行网络质量测试...
                        上海电信 | ↑ 500.0 Mbps | ↓ 600.0 Mbps | 35.0 ms | 0.0%
                        正在运行回程路由追踪...
                        电信: CN2 GIA
                    """.trimIndent()
                } else {
                    """
                        正在运行 IP 质量测试...
                        广播IP | 机房 IDC
                        Netflix: Originals Only
                        正在运行网络质量测试...
                        上海电信 | ↑ 150.0 Mbps | ↓ 180.0 Mbps | 160.0 ms | 8.0%
                        正在运行回程路由追踪...
                        电信: 163 骨干 (绕美)
                    """.trimIndent()
                }
                emit(SshStreamEvent.OutputChunk(output))
                emit(SshStreamEvent.Completed(0))
            }
        }

        var nowClock = 1_700_000_000_000L
        val mgr = LiteSshSessionManager(
            vaultRepository = vault,
            executor = LiteSshExecutor(fakeTransport, processingDispatcher = testDispatcher),
            externalScope = CoroutineScope(SupervisorJob() + testDispatcher),
            foregroundServiceController = NoOpSshForegroundServiceController,
            clock = { nowClock },
            enableElapsedTicker = false,
            benchmarkBaselineRepository = baselineStore,
        )
        try {
            mgr.selectServer(server.id)
            mgr.selectPreset(SshPresetCatalog.PRESET_NODE_QUALITY)
            mgr.runSelectedPreset()

            val deadline1 = System.currentTimeMillis() + 3_000L
            while (mgr.uiState.value.isRunning && System.currentTimeMillis() < deadline1) {
                Thread.sleep(20)
            }
            assertTrue(mgr.uiState.value.exportableRuns.isNotEmpty())

            mgr.saveCurrentAsBaseline(label = "HK-GIA 购入首测", notes = "新开机")
            assertEquals(1, baselineStore.baselinesFlow.value.size)

            // Run 30 days later with degraded output
            nowClock += 30L * 86_400_000L
            mgr.clearSessionRuns()
            mgr.runSelectedPreset()
            val deadline2 = System.currentTimeMillis() + 3_000L
            while (mgr.uiState.value.isRunning && System.currentTimeMillis() < deadline2) {
                Thread.sleep(20)
            }

            mgr.openBaselineCompareSheet()
            val state = mgr.uiState.value
            assertTrue(state.showBaselineCompareSheet)
            val diff = state.activeBaselineDiff
            assertNotNull(diff)
            assertEquals(30L, diff!!.elapsedDays)
            assertEquals(BaselineOverallVerdict.SIGNIFICANT_DEGRADATION, diff.overallVerdict)
        } finally {
            mgr.disposeOwnedScopeForTests()
        }
    }

    @Test
    fun `NetBenchSessionController runs traceroute, saves baseline, and compares later run`() = runBlocking {
        val historyStore = NetBenchHistoryStore(
            historyFile = File(tempFolder.root, "nb_history.json"),
            ioDispatcher = testDispatcher,
        )
        val baselineStore = BenchmarkBaselineStore(
            storeFile = File(tempFolder.root, "nb_baselines.json"),
            ioDispatcher = testDispatcher,
        )
        val bridge = object : NetBenchNetworkBridge {
            override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
                val ch = object : NetBenchSocketChannel {
                    override val networkId = "wifi-1"
                    override val transportLabel = "Wi-Fi"
                    override val isVpn = false
                    override val isDirectPhysical = true
                    override val bypassSystemHttpProxy = true
                    override val supportsFileDescriptorBind = false
                    override val socketFactory: SocketFactory = SocketFactory.getDefault()
                    override fun createSocket(): Socket = Socket()
                    override fun resolveAllByName(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
                    override fun bindDatagramFileDescriptor(fd: FileDescriptor) = Unit
                }
                val desc = NetworkDescriptor<NetBenchSocketChannel>(
                    token = ch,
                    networkId = "wifi-1",
                    isVpn = false,
                    hasInternet = true,
                    isValidated = true,
                    isActiveDefault = true,
                    transports = setOf(NetBenchTransportKind.WIFI),
                    interfaceName = "wlan0",
                )
                return ResolvedNetworkBinding(NetBenchNetworkSelector.selectNetworkPlan(listOf(desc)), ch, ch)
            }
        }
        var currentTtl = 0
        val tracerouteRunner = TracerouteRunner(
            resolver = NetBenchTargetResolver(networkBridge = bridge, ioDispatcher = testDispatcher),
            networkBridge = bridge,
            kernelOps = object : TracerouteKernelOps {
                override fun isSupported(): Boolean = true
                override fun openBoundSocket(ipFamily: IpFamily, channel: NetBenchSocketChannel?) = FileDescriptor()
                override fun sendEchoWithTtl(fd: FileDescriptor, targetAddress: InetAddress, ipFamily: IpFamily, ttl: Int, identifier: Int, sequence: Int) {
                    currentTtl = ttl
                }
                override fun awaitReplyOrError(
                    fd: FileDescriptor,
                    targetAddress: InetAddress,
                    ipFamily: IpFamily,
                    identifier: Int,
                    expectedSequence: Int,
                    timeoutMs: Int,
                ): TracerouteProbeReply =
                    if (currentTtl == 1) {
                        TracerouteProbeReply(
                            kind = TracerouteProbeReply.Kind.TIME_EXCEEDED,
                            responderIp = "59.43.138.1",
                            sequence = expectedSequence,
                            rttMs = 12.0,
                        )
                    } else {
                        TracerouteProbeReply(
                            kind = TracerouteProbeReply.Kind.ECHO_REPLY,
                            responderIp = "127.0.0.1",
                            sequence = expectedSequence,
                            rttMs = 28.0,
                        )
                    }
                override fun closeQuietly(fd: FileDescriptor) = Unit
            },
            ioDispatcher = testDispatcher,
        )

        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        try {
            val controller = NetBenchSessionController(
                scope = scope,
                historyStore = historyStore,
                networkBridge = bridge,
                tracerouteRunner = tracerouteRunner,
                ioDispatcher = testDispatcher,
                benchmarkBaselineRepository = baselineStore,
            )
            controller.updateTargetInput("127.0.0.1")
            controller.updateTracerouteProbesPerHop(1)
            controller.runForwardTracerouteOnly()

            val deadline = System.currentTimeMillis() + 3_000L
            while ((controller.uiState.value.isRunning || controller.uiState.value.tracerouteProgress?.isFinished != true) &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(20)
            }
            assertTrue(controller.uiState.value.hasAnyResult)

            controller.saveCurrentAsBaseline(label = "手机测机基线", notes = "初次测试")
            assertEquals(1, baselineStore.baselinesFlow.value.size)

            controller.openBaselineCompareSheet()
            assertTrue(controller.uiState.value.showBaselineCompareSheet)
            assertNotNull(controller.uiState.value.activeBaselineDiff)
        } finally {
            scope.cancel()
        }
    }
}
