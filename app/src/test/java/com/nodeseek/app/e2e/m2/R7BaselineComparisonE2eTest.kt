package com.nodeseek.app.e2e.m2

import com.nodeseek.app.data.baseline.BenchmarkBaselineStore
import com.nodeseek.app.data.netbench.NetBenchHistoryStore
import com.nodeseek.app.data.netbench.TracerouteProbeReply
import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.domain.backup.NodePulseFullBackupBundle
import com.nodeseek.app.domain.backup.RestoreStrategy
import com.nodeseek.app.domain.baseline.BaselineBandwidthMetrics
import com.nodeseek.app.domain.baseline.BaselineComparisonFormatter
import com.nodeseek.app.domain.baseline.BaselineDiffEngine
import com.nodeseek.app.domain.baseline.BaselineIpMetrics
import com.nodeseek.app.domain.baseline.BaselineLatencyMetrics
import com.nodeseek.app.domain.baseline.BaselineOverallVerdict
import com.nodeseek.app.domain.baseline.BaselineRoutingMetrics
import com.nodeseek.app.domain.baseline.BaselineSnapshotExtractor
import com.nodeseek.app.domain.baseline.BaselineSourceType
import com.nodeseek.app.domain.baseline.BenchmarkBaselineSnapshot
import com.nodeseek.app.domain.baseline.MetricTrend
import com.nodeseek.app.domain.baseline.StreamingChangeType
import com.nodeseek.app.domain.baseline.StreamingPlatformResult
import com.nodeseek.app.domain.baseline.StreamingUnlockStatus
import com.nodeseek.app.domain.netbench.BackboneRouteTag
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.IpOriginType
import com.nodeseek.app.domain.netbench.Iperf3Direction
import com.nodeseek.app.domain.netbench.Iperf3Result
import com.nodeseek.app.domain.netbench.NetBenchHistoryEntry
import com.nodeseek.app.domain.netbench.NetBenchReport
import com.nodeseek.app.domain.netbench.ResolvedTarget
import com.nodeseek.app.domain.netbench.TracerouteDirection
import com.nodeseek.app.domain.netbench.TracerouteHop
import com.nodeseek.app.domain.netbench.TracerouteSummary
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshPresetCatalog
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.e2e.E2eMainDispatcherRule
import com.nodeseek.app.e2e.E2eNetBenchNetworkBridge
import com.nodeseek.app.e2e.E2eSshTransport
import com.nodeseek.app.e2e.E2eTestHarness
import com.nodeseek.app.e2e.E2eTracerouteKernelOps
import com.nodeseek.app.ui.baseline.AndroidBaselineComparisonCardImageGenerator
import com.nodeseek.app.ui.netbench.NetBenchPhase
import com.nodeseek.app.ui.ssh.LiteSshViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class R7BaselineComparisonE2eTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    @Test
    fun `E2E-T1-R7-1 saves Purchase Baselines from SSH benchmark and Mobile NetBench and persists across reload`() = runTest {
        E2eTestHarness().use { harness ->
            harness.registerNetBenchGeoIpRoutes()
            val container = harness.buildAppContainer()
            val baselineRepo = container.benchmarkBaselineRepository

            val server = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "DMIT HK",
                    host = "203.0.113.88",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "pass123",
                ),
            )
            val sshTranscript = """
                Geekbench 6 Single-Core: 1420
                Geekbench 6 Multi-Core: 2890
                Download: 820.0 Mbps
                Upload: 510.0 Mbps
                Ping: 36.4 ms
                Netflix: 原生解锁 (Region: HK)
                ChatGPT: 原生解锁 (Region: US)
                电信回程: CN2 GIA (59.43.80.1)
            """.trimIndent()

            val sshTransport = E2eSshTransport(
                commandHandler = { _, _ ->
                    listOf(
                        SshStreamEvent.Connected,
                        SshStreamEvent.OutputChunk(sshTranscript + "\n"),
                        SshStreamEvent.Completed(0),
                    )
                },
            )
            val sshManager = harness.createSshSessionManager(
                transport = sshTransport,
                baselineRepo = baselineRepo,
            )
            val sshVm = harness.manage(LiteSshViewModel(sshManager, ownsSessionManagerScope = false))
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    sshVm.uiState.first { it.servers.any { s -> s.id == server.id } }
                }
            }
            sshVm.selectServer(server.id)
            sshVm.selectPreset(SshPresetCatalog.PRESET_NODE_QUALITY)
            sshVm.runSelectedPreset()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    sshVm.uiState.first { !it.isRunning && it.sessionRuns.isNotEmpty() }
                }
            }

            sshVm.openSaveBaselineDialog()
            assertTrue(sshVm.uiState.value.showSaveBaselineDialog)
            sshVm.saveCurrentAsBaseline(label = "DMIT HK 购入基线", notes = "刚开通首测")

            val afterSshSave = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    baselineRepo.baselinesFlow.first { list -> list.any { it.label == "DMIT HK 购入基线" } }
                }
            }
            val sshBaseline = afterSshSave.first { it.label == "DMIT HK 购入基线" }
            assertEquals(BaselineSourceType.SSH_BENCHMARK, sshBaseline.sourceType)
            assertEquals(server.id, sshBaseline.serverId)
            assertEquals(820.0, sshBaseline.bandwidth.downloadMbps!!, 0.1)
            assertEquals(510.0, sshBaseline.bandwidth.uploadMbps!!, 0.1)
            assertEquals(1420, sshBaseline.bandwidth.geekbenchSingle)
            assertEquals(2, sshBaseline.streamingUnlocks.size)
            assertTrue(BackboneRouteTag.CN2_GIA in sshBaseline.routing.reverseBackboneTags)

            // Also save a Mobile NetBench baseline via NetBenchSessionController
            val kernelOps = E2eTracerouteKernelOps(
                hopResponder = { ttl, seq, _ ->
                    when (ttl) {
                        1 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "59.43.80.1", seq, 12.0)
                        else -> TracerouteProbeReply(TracerouteProbeReply.Kind.ECHO_REPLY, "203.0.113.88", seq, 38.0)
                    }
                },
            )
            val controller = harness.createNetBenchController(
                networkBridge = E2eNetBenchNetworkBridge(),
                tracerouteKernelOps = kernelOps,
                baselineRepo = baselineRepo,
            )
            controller.updateTargetInput("203.0.113.88")
            controller.updateTracerouteProbesPerHop(3)
            controller.runForwardTracerouteOnly()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    controller.uiState.first { !it.isRunning && it.activePhase == NetBenchPhase.COMPLETED }
                }
            }
            controller.saveCurrentAsBaseline(label = "手机端首测基线")
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    baselineRepo.baselinesFlow.first { list -> list.any { it.label == "手机端首测基线" } }
                }
            }

            assertTrue(harness.benchmarkBaselineFile.isFile)
            val byServer = baselineRepo.findMatchingBaselines(serverId = server.id)
            assertEquals(2, byServer.size)
            assertEquals(
                setOf(BaselineSourceType.SSH_BENCHMARK, BaselineSourceType.MOBILE_NETBENCH),
                byServer.map { it.sourceType }.toSet(),
            )
            val byHost = baselineRepo.findMatchingBaselines(hostOrIp = "203.0.113.88")
            assertEquals(2, byHost.size)
        }
    }

    @Test
    fun `E2E-T1-R7-2 computes bandwidth shrinkage deltas and SIGNIFICANT_DEGRADATION verdict in Before-vs-After diff`() = runTest {
        E2eTestHarness().use { harness ->
            val beforeEpoch = harness.clock.nowMillis()
            val beforeReport = NetBenchReport(
                targetInput = "198.51.100.55",
                resolvedTarget = ResolvedTarget("198.51.100.55", "198.51.100.55", IpFamily.IPV4),
                iperfDownloadResult = Iperf3Result(
                    direction = Iperf3Direction.DOWNLOAD,
                    port = 5201,
                    resolvedTarget = null,
                    durationSec = 10.0,
                    bytesTransferred = 1_000_000_000L,
                    clientMbps = 800.0,
                    peakMbps = 850.0,
                    success = true,
                ),
                iperfUploadResult = Iperf3Result(
                    direction = Iperf3Direction.UPLOAD,
                    port = 5201,
                    resolvedTarget = null,
                    durationSec = 10.0,
                    bytesTransferred = 625_000_000L,
                    clientMbps = 510.0,
                    serverMbps = 500.0,
                    peakMbps = 520.0,
                    success = true,
                ),
            )
            val beforeSnapshot = BaselineSnapshotExtractor.fromNetBenchReport(
                report = beforeReport,
                id = "b_800",
                label = "购入首测",
                capturedAtEpochMillis = beforeEpoch,
            )
            harness.benchmarkBaselineStore.saveBaseline(beforeSnapshot)

            val afterEpoch = beforeEpoch + 30L * 86_400_000L
            val afterReport = NetBenchReport(
                targetInput = "198.51.100.55",
                resolvedTarget = ResolvedTarget("198.51.100.55", "198.51.100.55", IpFamily.IPV4),
                iperfDownloadResult = Iperf3Result(
                    direction = Iperf3Direction.DOWNLOAD,
                    port = 5201,
                    resolvedTarget = null,
                    durationSec = 10.0,
                    bytesTransferred = 300_000_000L,
                    clientMbps = 240.0,
                    peakMbps = 260.0,
                    success = true,
                ),
                iperfUploadResult = Iperf3Result(
                    direction = Iperf3Direction.UPLOAD,
                    port = 5201,
                    resolvedTarget = null,
                    durationSec = 10.0,
                    bytesTransferred = 500_000_000L,
                    clientMbps = 410.0,
                    serverMbps = 400.0,
                    peakMbps = 410.0,
                    success = true,
                ),
            )
            val afterSnapshot = BaselineSnapshotExtractor.fromNetBenchReport(
                report = afterReport,
                id = "c_240",
                label = "当前实测",
                capturedAtEpochMillis = afterEpoch,
            )

            val diff = BaselineDiffEngine.compare(beforeSnapshot, afterSnapshot)
            assertEquals(30L, diff.elapsedDays)
            assertEquals(BaselineOverallVerdict.SIGNIFICANT_DEGRADATION, diff.overallVerdict)

            val dlDelta = diff.bandwidthDeltas.first { it.metricLabel == "下行带宽" }
            assertEquals(-560.0, dlDelta.deltaMbps!!, 0.01)
            assertEquals(-70.0, dlDelta.deltaPercent!!, 0.01)
            assertEquals(MetricTrend.DEGRADED_SEVERE, dlDelta.trend)

            val ulDelta = diff.bandwidthDeltas.first { it.metricLabel == "上行带宽" }
            assertEquals(-100.0, ulDelta.deltaMbps!!, 0.01)
            assertEquals(-20.0, ulDelta.deltaPercent!!, 0.01)
            assertEquals(MetricTrend.DEGRADED_WARN, ulDelta.trend)

            assertTrue(diff.highlightBullets.any { it.contains("70%") })

            // Also verify NetBenchSessionController.openBaselineCompareSheet wires BaselineDiffEngine
            harness.registerNetBenchGeoIpRoutes()
            val controller = harness.createNetBenchController(
                networkBridge = E2eNetBenchNetworkBridge(),
                tracerouteKernelOps = E2eTracerouteKernelOps(
                    hopResponder = { _, seq, _ ->
                        TracerouteProbeReply(TracerouteProbeReply.Kind.ECHO_REPLY, "198.51.100.55", seq, 42.0)
                    },
                ),
            )
            controller.updateTargetInput("198.51.100.55")
            controller.runForwardTracerouteOnly()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { controller.uiState.first { !it.isRunning && it.hasAnyResult } }
            }
            controller.openBaselineCompareSheet("b_800")
            assertTrue(controller.uiState.value.showBaselineCompareSheet)
            assertEquals("b_800", controller.uiState.value.activeBaselineDiff?.baseline?.id)
        }
    }

    @Test
    fun `E2E-T1-R7-3 tracks streaming and AI unlock regressions and gains across SSH runs`() = runTest {
        E2eTestHarness().use { harness ->
            val server = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "Streaming VPS",
                    host = "198.51.100.60",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "secret",
                ),
            )
            val beforeOut = """
                Netflix: 原生解锁 (Region: HK)
                ChatGPT: 原生解锁 (Region: US)
                Disney+: 未解锁
            """.trimIndent()
            val afterOut = """
                Netflix: 仅自制剧
                ChatGPT: 未解锁
                Disney+: 原生解锁 (Region: HK)
            """.trimIndent()

            var runNumber = 1
            val sshTransport = E2eSshTransport(
                commandHandler = { _, _ ->
                    val out = if (runNumber++ == 1) beforeOut else afterOut
                    listOf(
                        SshStreamEvent.Connected,
                        SshStreamEvent.OutputChunk(out + "\n"),
                        SshStreamEvent.Completed(0),
                    )
                },
            )
            val manager = harness.createSshSessionManager(transport = sshTransport)
            val vm = harness.manage(LiteSshViewModel(manager, ownsSessionManagerScope = false))
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.servers.any { s -> s.id == server.id } }
                }
            }
            vm.selectServer(server.id)
            vm.selectPreset(SshPresetCatalog.PRESET_MEDIA_UNLOCK)

            vm.runSelectedPreset()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { !it.isRunning && it.sessionRuns.size == 1 } }
            }
            vm.saveCurrentAsBaseline(label = "解锁基线")
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { harness.benchmarkBaselineStore.baselinesFlow.first { it.isNotEmpty() } }
            }

            harness.clock.advanceByMillis(14L * 86_400_000L)
            vm.clearSessionRuns()
            vm.runSelectedPreset()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { !it.isRunning && it.sessionRuns.size == 1 } }
            }
            vm.openBaselineCompareSheet()

            val state = vm.uiState.value
            assertTrue(state.showBaselineCompareSheet)
            val diff = state.activeBaselineDiff!!
            val byKey = diff.streamingDiffs.associateBy { it.platformKey }
            assertEquals(StreamingChangeType.DOWNGRADED_TO_ORIGINALS, byKey["netflix"]?.changeType)
            assertEquals(StreamingChangeType.LOST_UNLOCK, byKey["chatgpt"]?.changeType)
            assertEquals(StreamingChangeType.GAINED_UNLOCK, byKey["disney"]?.changeType)
            assertTrue(diff.highlightBullets.any { it.contains("流媒体掉解锁") })
            assertTrue(diff.highlightBullets.any { it.contains("流媒体降级") })
        }
    }

    @Test
    fun `E2E-T1-R7-4 detects backbone downgrade from CN2 GIA and 9929 to 163 and Cogent US detour`() = runTest {
        val before = BenchmarkBaselineSnapshot(
            id = "b_hk_route",
            label = "HK 优质线路基线",
            targetKey = "host:203.0.113.88",
            hostDisplay = "203.0.113.88",
            resolvedIp = "203.0.113.88",
            sourceType = BaselineSourceType.MOBILE_NETBENCH,
            capturedAtEpochMillis = 1_764_000_000_000L,
            routing = BaselineRoutingMetrics(
                forwardRouteSignature = "CN2 GIA -> 9929",
                forwardBackboneTags = listOf(BackboneRouteTag.CN2_GIA, BackboneRouteTag.CU_9929),
                forwardHopCount = 5,
                forwardTerminalMs = 36.0,
                forwardPeakHopLossPercent = 0.0,
                forwardHasRealPacketLoss = false,
                isDetourSuspected = false,
            ),
            ipMetrics = BaselineIpMetrics(countryCode = "HK", originType = IpOriginType.NATIVE),
        )

        val degradedReport = NetBenchReport(
            targetInput = "203.0.113.88",
            resolvedTarget = ResolvedTarget("203.0.113.88", "203.0.113.88", IpFamily.IPV4),
            forwardTraceroute = TracerouteSummary(
                direction = TracerouteDirection.FORWARD,
                targetInput = "203.0.113.88",
                targetIp = "203.0.113.88",
                hops = listOf(
                    TracerouteHop(hopNumber = 1, ip = "202.97.33.1", countryCode = "CN", sent = 5, received = 5, avgMs = 18.0, backboneTags = listOf(BackboneRouteTag.CT_163)),
                    TracerouteHop(hopNumber = 2, ip = "219.158.3.1", countryCode = "CN", sent = 5, received = 5, avgMs = 24.0, backboneTags = listOf(BackboneRouteTag.CU_4837)),
                    TracerouteHop(hopNumber = 3, ip = "154.54.1.1", countryCode = "US", sent = 5, received = 5, avgMs = 152.0, backboneTags = listOf(BackboneRouteTag.COGENT)),
                    TracerouteHop(hopNumber = 4, ip = "203.0.113.88", countryCode = "HK", sent = 5, received = 5, avgMs = 188.0, isTargetReached = true),
                ),
                reachedTarget = true,
                totalHops = 4,
                terminalAvgMs = 188.0,
                dominantBackboneTags = listOf(BackboneRouteTag.CT_163, BackboneRouteTag.CU_4837, BackboneRouteTag.COGENT),
                routeSignature = "163 骨干 -> 4837 -> Cogent",
            ),
        )
        val after = BaselineSnapshotExtractor.fromNetBenchReport(
            report = degradedReport,
            id = "c_hk_detour",
            label = "当前实测",
            capturedAtEpochMillis = 1_764_000_000_000L + 10L * 86_400_000L,
        )

        assertTrue(after.routing.isDetourSuspected)
        val diff = BaselineDiffEngine.compare(before, after)
        assertEquals(BaselineOverallVerdict.SIGNIFICANT_DEGRADATION, diff.overallVerdict)
        val fwdShift = diff.routingShifts.first { it.directionLabel == "去程路由" }
        assertTrue(fwdShift.backboneDowngraded)
        assertTrue(fwdShift.detourIntroduced)
        assertEquals(MetricTrend.DEGRADED_SEVERE, fwdShift.trend)
    }

    @Test
    fun `E2E-T1-R7-5 exports privacy-masked Markdown and Light or Dark offscreen card presentation`() = runTest {
        val before = BenchmarkBaselineSnapshot(
            id = "b_card",
            label = "DMIT 首测",
            targetKey = "host:198.51.100.77",
            hostDisplay = "198.51.100.77:8443",
            resolvedIp = "198.51.100.77",
            sourceType = BaselineSourceType.MOBILE_NETBENCH,
            capturedAtEpochMillis = 1_764_000_000_000L,
            bandwidth = BaselineBandwidthMetrics(downloadMbps = 800.0, uploadMbps = 500.0),
            latency = BaselineLatencyMetrics(icmpAvgMs = 135.0),
            routing = BaselineRoutingMetrics(
                forwardRouteSignature = "CN2 GIA (198.51.100.77)",
                forwardBackboneTags = listOf(BackboneRouteTag.CN2_GIA),
            ),
            streamingUnlocks = listOf(
                StreamingPlatformResult("netflix", "Netflix", StreamingUnlockStatus.UNLOCKED, "US"),
            ),
        )
        val after = BenchmarkBaselineSnapshot(
            id = "c_card",
            label = "当前实测",
            targetKey = "host:198.51.100.77",
            hostDisplay = "198.51.100.77:8443",
            resolvedIp = "198.51.100.77",
            sourceType = BaselineSourceType.MOBILE_NETBENCH,
            capturedAtEpochMillis = 1_764_000_000_000L + 5L * 86_400_000L,
            bandwidth = BaselineBandwidthMetrics(downloadMbps = 240.0, uploadMbps = 320.0),
            latency = BaselineLatencyMetrics(icmpAvgMs = 185.0),
            routing = BaselineRoutingMetrics(
                forwardRouteSignature = "163 骨干 -> Cogent (198.51.100.77)",
                forwardBackboneTags = listOf(BackboneRouteTag.CT_163, BackboneRouteTag.COGENT),
                isDetourSuspected = true,
                detourReason = "检测到 198.51.100.77 绕行",
            ),
            streamingUnlocks = listOf(
                StreamingPlatformResult("netflix", "Netflix", StreamingUnlockStatus.LOCKED),
            ),
        )

        val diff = BaselineDiffEngine.compare(before, after)
        val markdown = BaselineComparisonFormatter.formatMarkdown(diff, maskTargetIp = true)
        assertTrue(markdown.contains("## 📊 Before vs After 购入基线对比卡"))
        assertTrue(markdown.contains("198.51.100.*"))
        assertFalse(markdown.contains("198.51.100.77"))
        assertTrue(markdown.contains("| 对比维度"))

        val lightModel = AndroidBaselineComparisonCardImageGenerator.buildPresentation(diff, maskTargetIp = true)
        assertTrue(lightModel.title.contains("198.51.100.*"))
        assertFalse(lightModel.title.contains("198.51.100.77"))
        assertTrue(lightModel.rows.isNotEmpty())

        val lightTexts = AndroidBaselineComparisonCardImageGenerator.extractRenderedCardTexts(
            diff = diff,
            maskTargetIp = true,
            darkTheme = false,
        )
        val darkTexts = AndroidBaselineComparisonCardImageGenerator.extractRenderedCardTexts(
            diff = diff,
            maskTargetIp = true,
            darkTheme = true,
        )
        assertTrue(lightTexts.isNotEmpty())
        assertEquals(lightTexts, darkTexts)
        assertTrue(lightTexts.none { it.contains("198.51.100.77") })
        assertTrue(lightTexts.any { it.contains("198.51.100.*") })
        assertFalse(lightModel.subtitle.contains("198.51.100.77"))
        assertTrue(lightModel.highlightLines.none { it.contains("198.51.100.77") })
        assertTrue(lightModel.rows.none { it.beforeText.contains("198.51.100.77") || it.afterText.contains("198.51.100.77") })
    }

    @Test
    fun `E2E-T2-R7-1 handles zero or null Before bandwidth without NaN or Infinity`() = runTest {
        val beforeZeroAndNull = BenchmarkBaselineSnapshot(
            id = "b_zero",
            label = "零基线",
            targetKey = "host:198.51.100.1",
            hostDisplay = "198.51.100.1",
            sourceType = BaselineSourceType.MOBILE_NETBENCH,
            capturedAtEpochMillis = 1_764_000_000_000L,
            bandwidth = BaselineBandwidthMetrics(
                downloadMbps = 0.0,
                uploadMbps = null,
            ),
        )
        val after = BenchmarkBaselineSnapshot(
            id = "c_250",
            label = "当前实测",
            targetKey = "host:198.51.100.1",
            hostDisplay = "198.51.100.1",
            sourceType = BaselineSourceType.MOBILE_NETBENCH,
            capturedAtEpochMillis = 1_764_000_000_000L + 86_400_000L,
            bandwidth = BaselineBandwidthMetrics(
                downloadMbps = 250.0,
                uploadMbps = 250.0,
            ),
        )

        val diff = BaselineDiffEngine.compare(beforeZeroAndNull, after)
        assertEquals(1, diff.bandwidthDeltas.size)
        val dl = diff.bandwidthDeltas.single()
        assertEquals("下行带宽", dl.metricLabel)
        assertEquals(250.0, dl.deltaMbps!!, 0.001)
        assertNull(dl.deltaPercent)
        assertEquals(MetricTrend.IMPROVED, dl.trend)
        val md = BaselineComparisonFormatter.formatMarkdown(diff)
        assertFalse(md.contains("NaN"))
        assertFalse(md.contains("Infinity"))
    }

    @Test
    fun `E2E-T2-R7-2 compares mismatched SSH baseline and NetBench current without fabricating missing sections`() = runTest {
        val sshBaseline = BenchmarkBaselineSnapshot(
            id = "b_ssh_only",
            label = "SSH 基线",
            targetKey = "host:203.0.113.88",
            hostDisplay = "203.0.113.88",
            sourceType = BaselineSourceType.SSH_BENCHMARK,
            capturedAtEpochMillis = 1_764_000_000_000L,
            bandwidth = BaselineBandwidthMetrics(
                downloadMbps = 500.0,
                uploadMbps = null,
                geekbenchSingle = 1200,
            ),
            latency = BaselineLatencyMetrics(icmpAvgMs = 40.0, tcpAvgMs = null),
            routing = BaselineRoutingMetrics(
                reverseRouteSignature = "CN2 GIA",
                reverseBackboneTags = listOf(BackboneRouteTag.CN2_GIA),
                isDetourSuspected = false,
            ),
            streamingUnlocks = listOf(
                StreamingPlatformResult("netflix", "Netflix", StreamingUnlockStatus.UNLOCKED, "HK"),
            ),
        )
        val netBenchCurrent = BenchmarkBaselineSnapshot(
            id = "c_nb_only",
            label = "NetBench 当前",
            targetKey = "host:203.0.113.88",
            hostDisplay = "203.0.113.88",
            sourceType = BaselineSourceType.MOBILE_NETBENCH,
            capturedAtEpochMillis = 1_764_000_000_000L + 3L * 86_400_000L,
            bandwidth = BaselineBandwidthMetrics(
                downloadMbps = 480.0,
                uploadMbps = 300.0,
            ),
            latency = BaselineLatencyMetrics(icmpAvgMs = 45.0, tcpAvgMs = 46.0),
            routing = BaselineRoutingMetrics(
                forwardRouteSignature = "163 骨干 -> Cogent",
                forwardBackboneTags = listOf(BackboneRouteTag.CT_163, BackboneRouteTag.COGENT),
                isDetourSuspected = true,
                detourReason = "检测到去程绕美",
            ),
            streamingUnlocks = emptyList(),
        )

        val diff = BaselineDiffEngine.compare(sshBaseline, netBenchCurrent)
        assertEquals(listOf("下行带宽"), diff.bandwidthDeltas.map { it.metricLabel })
        assertEquals(listOf("ICMP 平均延迟"), diff.latencyDeltas.map { it.metricLabel })
        assertTrue(diff.streamingDiffs.isEmpty())
        assertTrue(diff.routingShifts.any { it.detourIntroduced })
    }

    @Test
    fun `E2E-T2-R7-3 isolates matching baselines per serverId, overwrites same ID cleanly, and deletes selectively`() = runTest {
        E2eTestHarness().use { harness ->
            val store = harness.benchmarkBaselineStore
            val hk1 = BenchmarkBaselineSnapshot(
                id = "hk_base_1",
                label = "HK 首测 v1",
                targetKey = "server:srv_hk",
                serverId = "srv_hk",
                hostDisplay = "hk.example.com",
                resolvedIp = "203.0.113.10",
                sourceType = BaselineSourceType.SSH_BENCHMARK,
                capturedAtEpochMillis = 1_764_000_000_000L,
                bandwidth = BaselineBandwidthMetrics(downloadMbps = 300.0),
            )
            val lax1 = BenchmarkBaselineSnapshot(
                id = "lax_base_1",
                label = "LAX 首测",
                targetKey = "server:srv_lax",
                serverId = "srv_lax",
                hostDisplay = "lax.example.com",
                resolvedIp = "198.51.100.10",
                sourceType = BaselineSourceType.SSH_BENCHMARK,
                capturedAtEpochMillis = 1_764_000_100_000L,
                bandwidth = BaselineBandwidthMetrics(downloadMbps = 700.0),
            )
            store.saveBaseline(hk1)
            store.saveBaseline(lax1)

            // Overwrite same ID hk_base_1
            store.saveBaseline(hk1.copy(label = "HK 首测 v2", bandwidth = BaselineBandwidthMetrics(downloadMbps = 350.0)))

            assertEquals(2, store.baselinesFlow.value.size)
            val hkMatches = store.findMatchingBaselines(serverId = "srv_hk")
            assertEquals(1, hkMatches.size)
            assertEquals("HK 首测 v2", hkMatches.single().label)
            assertEquals(350.0, hkMatches.single().bandwidth.downloadMbps!!, 0.01)

            store.deleteBaseline("hk_base_1")
            assertEquals(1, store.baselinesFlow.value.size)
            assertEquals("lax_base_1", store.baselinesFlow.value.single().id)
        }
    }

    @Test
    fun `E2E-T2-R7-4 cold-reloads BenchmarkBaselineStore and round-trips Encrypted Full Backup v2 MERGE and OVERWRITE`() = runTest {
        E2eTestHarness().use { harness ->
            val store = harness.benchmarkBaselineStore
            val snap1 = BenchmarkBaselineSnapshot(
                id = "b_persist_1",
                label = "持久化基线 1",
                targetKey = "server:srv_1",
                serverId = "srv_1",
                hostDisplay = "198.51.100.88",
                resolvedIp = "198.51.100.88",
                sourceType = BaselineSourceType.MOBILE_NETBENCH,
                capturedAtEpochMillis = 1_764_000_000_000L,
                bandwidth = BaselineBandwidthMetrics(downloadMbps = 600.0, uploadMbps = 400.0),
                streamingUnlocks = listOf(
                    StreamingPlatformResult("netflix", "Netflix", StreamingUnlockStatus.UNLOCKED, "US"),
                ),
            )
            store.saveBaseline(snap1)

            // Cold reload on same file with deferInitialLoad = true
            val reloadedStore = BenchmarkBaselineStore(
                storeFile = harness.benchmarkBaselineFile,
                deferInitialLoad = true,
            )
            assertTrue(reloadedStore.baselinesFlow.value.isEmpty())
            reloadedStore.ensureLoaded()
            assertEquals(1, reloadedStore.baselinesFlow.value.size)
            assertEquals(snap1, reloadedStore.baselinesFlow.value.single())

            // Encrypted Full Backup v2 export -> inspect -> restore MERGE & OVERWRITE
            val backupMgr = harness.createBackupManager(baselineRepo = reloadedStore)
            val exportRes = backupMgr.exportEncryptedFullBackup(passphrase = "StrongPass!2026")
            assertTrue(exportRes.isSuccess)
            val rawEnvelope = exportRes.getOrThrow().rawEnvelopeJson

            val previewRes = backupMgr.inspectEncryptedFullBackup(rawEnvelope, "StrongPass!2026")
            assertTrue(previewRes.isSuccess)
            assertEquals(1, previewRes.getOrThrow().baselineCount)

            // Add a local-only baseline before testing MERGE vs OVERWRITE
            val localOnly = snap1.copy(id = "b_local_only", label = "本地新增")
            reloadedStore.saveBaseline(localOnly)
            assertEquals(2, reloadedStore.baselinesFlow.value.size)

            val mergeRes = backupMgr.restoreEncryptedFullBackup(
                rawContent = rawEnvelope,
                passphrase = "StrongPass!2026",
                strategy = RestoreStrategy.MERGE,
            )
            assertTrue(mergeRes.isSuccess)
            assertEquals(1, mergeRes.getOrThrow().baselinesImported)
            assertEquals(2, reloadedStore.baselinesFlow.value.size)

            val overwriteRes = backupMgr.restoreEncryptedFullBackup(
                rawContent = rawEnvelope,
                passphrase = "StrongPass!2026",
                strategy = RestoreStrategy.OVERWRITE,
            )
            assertTrue(overwriteRes.isSuccess)
            assertEquals(1, overwriteRes.getOrThrow().baselinesImported)
            assertEquals(listOf("b_persist_1"), reloadedStore.baselinesFlow.value.map { it.id })
        }
    }

    @Test
    fun `E2E-T2-R7-5 recovers from corrupt baselines JSON, restores legacy bundle without baselines, and sanitizes extreme RTTs`() = runTest {
        E2eTestHarness().use { harness ->
            // 1. Corrupt benchmark_baselines.json recovery
            val corruptFile = File(harness.tempDir, "corrupt_baselines.json")
            corruptFile.writeText("{not valid json!!!", Charsets.UTF_8)
            val recoveredStore = BenchmarkBaselineStore(storeFile = corruptFile, deferInitialLoad = false)
            assertTrue(recoveredStore.baselinesFlow.value.isEmpty())
            val backupFiles = harness.tempDir.listFiles { _, name ->
                name.startsWith("corrupt_baselines.json.corrupt.")
            }
            assertNotNull(backupFiles)
            assertTrue(backupFiles!!.isNotEmpty())

            // 2. Legacy NodePulseFullBackupBundle (missing benchmarkBaselines field) restores cleanly
            val backupMgr = harness.createBackupManager(baselineRepo = recoveredStore)
            val encryptedLegacy = backupMgr.exportEncryptedFullBackup(passphrase = "LegacyPass!99").getOrThrow()
            val strippedJson = Json.encodeToString(
                NodePulseFullBackupBundle(
                    schemaVersion = NodePulseFullBackupBundle.CURRENT_SCHEMA_VERSION,
                    appVersion = "1.0.0",
                    exportedAtEpochMillis = harness.clock.nowMillis(),
                    settings = encryptedLegacy.bundle.settings,
                    benchmarkBaselines = emptyList(),
                ),
            )
            // Verify bundle with missing benchmarkBaselines JSON key decodes & restores 0 baselines
            val jsonWithoutBaselinesKey = strippedJson.replace(",\"benchmarkBaselines\":[]", "")
            val legacyDecoded = TempScriptParser.json
                .decodeFromString(NodePulseFullBackupBundle.serializer(), jsonWithoutBaselinesKey)
            assertTrue(legacyDecoded.benchmarkBaselines.isEmpty())
            val restoreLegacyRes = backupMgr.restoreEncryptedFullBackup(
                rawContent = encryptedLegacy.rawEnvelopeJson,
                passphrase = "LegacyPass!99",
                strategy = RestoreStrategy.MERGE,
            )
            assertTrue(restoreLegacyRes.isSuccess)
            assertEquals(0, restoreLegacyRes.getOrThrow().baselinesImported)

            // 3. Legacy netbench_history.json with extreme corrupted RTT 745123913967336.8 sanitized to null
            val legacyHistoryJson = """
                {
                  "version": 2,
                  "entries": [
                    {
                      "host": "198.51.100.99",
                      "port": 443,
                      "iperfPort": 5201,
                      "resolvedIp": "198.51.100.99",
                      "ipFamily": "IPV4",
                      "lastAvgMs": 745123913967336.8,
                      "lastPingAvgMs": 745123913967336.8,
                      "lastPingLossPercent": 0.0,
                      "testedAtEpochMillis": 1764000000000
                    }
                  ]
                }
            """.trimIndent()
            harness.netBenchHistoryFile.writeText(legacyHistoryJson, Charsets.UTF_8)
            val freshHistory = NetBenchHistoryStore(historyFile = harness.netBenchHistoryFile, eagerLoadOnInit = false)
            val entries = freshHistory.reload()
            assertEquals(1, entries.size)
            assertNull(entries.single().lastAvgMs)
            assertNull(entries.single().lastPingAvgMs)
        }
    }
}
