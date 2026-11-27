package com.nodeseek.app.e2e.m2

import com.nodeseek.app.data.netbench.TracerouteProbeReply
import com.nodeseek.app.domain.baseline.BaselineComparisonFormatter
import com.nodeseek.app.domain.baseline.BaselineOverallVerdict
import com.nodeseek.app.domain.baseline.MetricTrend
import com.nodeseek.app.domain.netbench.BackboneRouteTag
import com.nodeseek.app.domain.ssh.HostKeyInfo
import com.nodeseek.app.domain.ssh.HostKeyTrustPolicy
import com.nodeseek.app.domain.ssh.HostKeyVerificationResult
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshPresetCatalog
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.e2e.E2eFixtures
import com.nodeseek.app.e2e.E2eMainDispatcherRule
import com.nodeseek.app.e2e.E2eNetBenchNetworkBridge
import com.nodeseek.app.e2e.E2eSftpClient
import com.nodeseek.app.e2e.E2eSftpTransport
import com.nodeseek.app.e2e.E2eSshTransport
import com.nodeseek.app.e2e.E2eTestHarness
import com.nodeseek.app.e2e.E2eTracerouteKernelOps
import com.nodeseek.app.ui.baseline.AndroidBaselineComparisonCardImageGenerator
import com.nodeseek.app.ui.netbench.NetBenchPhase
import com.nodeseek.app.ui.ssh.LiteSshViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class M2PairwiseE2eTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    @Test
    fun `E2E-T3-04 R5 and R6 share SshServerProfile JumpHost TOFU keys across reverse traceroute and SFTP config edit`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, sessionCookie = "ns_t3_04_cookie")
            harness.registerNetBenchGeoIpRoutes(clientEgressIp = "114.240.88.123")

            val bastion = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "HK Bastion",
                    host = "203.0.113.1",
                    port = 22,
                    username = "jump",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "jump-pass",
                ),
            )
            val target = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "LAX Target",
                    host = "154.17.12.88",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "target-pass",
                    jumpHostId = bastion.id,
                ),
            )

            val bastionKey = HostKeyInfo(
                host = "203.0.113.1",
                port = 22,
                algorithm = "ssh-ed25519",
                fingerprintSha256 = HostKeyTrustPolicy.computeSha256Fingerprint("bastion-t3-04".toByteArray()),
            )
            val targetKey = HostKeyInfo(
                host = "154.17.12.88",
                port = 22,
                algorithm = "ssh-ed25519",
                fingerprintSha256 = HostKeyTrustPolicy.computeSha256Fingerprint("target-t3-04".toByteArray()),
            )
            val keyMap = mapOf(
                bastion.id to bastionKey,
                target.id to targetKey,
            )

            val sshTransport = E2eSshTransport(
                hostKeyByServerOrHost = keyMap,
                commandHandler = { _, _ ->
                    listOf(
                        SshStreamEvent.Connected,
                        SshStreamEvent.OutputChunk(E2eFixtures.loadMtrSection("REVERSE_NEXTTRACE_CN2_GIA") + "\n"),
                        SshStreamEvent.Completed(0),
                    )
                },
            )
            val originalSingBox = """{"inbounds":[],"outbounds":[{"type":"direct"}]}"""
            val sftpClient = E2eSftpClient(
                homeDirectory = "/root",
                initialFiles = mapOf("/etc/sing-box/config.json" to originalSingBox.toByteArray()),
            )
            val sftpVerifications = CopyOnWriteArrayList<HostKeyVerificationResult>()
            val baseSftpTransport = E2eSftpTransport(
                hostKeyByServerOrHost = keyMap,
                clientFactory = { sftpClient },
            )
            val recordingSftpTransport = object : com.nodeseek.app.domain.ssh.SshSftpTransport {
                override suspend fun openSftpSession(
                    request: com.nodeseek.app.domain.ssh.SshConnectionRequest,
                    onHostKeyObserved: (HostKeyVerificationResult, String?) -> Unit,
                ) = baseSftpTransport.openSftpSession(request) { verification, serverId ->
                    sftpVerifications.add(verification)
                    onHostKeyObserved(verification, serverId)
                }
            }

            val sharedSshManager = harness.createSshSessionManager(
                transport = sshTransport,
                sftpTransport = recordingSftpTransport,
            )
            val netBenchVm = harness.createNetBenchViewModel(
                networkBridge = E2eNetBenchNetworkBridge(),
                sshSessionManager = sharedSshManager,
            )
            val sshVm = harness.manage(LiteSshViewModel(sharedSshManager, ownsSessionManagerScope = false))
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    sshVm.uiState.first { it.servers.any { s -> s.id == target.id } }
                }
            }

            // 1. Run R5 reverse traceroute via JumpHost -> records TOFU fingerprints for bastion + target
            netBenchVm.runReverseTraceroute(target)
            val afterReverse = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    netBenchVm.uiState.first {
                        !it.isRunningReverseTrace && (it.reverseTracerouteResult != null || it.reverseTraceError != null)
                    }
                }
            }
            assertNull("reverseTraceError=${afterReverse.reverseTraceError}", afterReverse.reverseTraceError)
            assertNotNull(afterReverse.reverseTracerouteResult)
            assertTrue(afterReverse.reverseTracerouteResult!!.reachedTarget)
            assertTrue(afterReverse.generatedMarkdown.contains("回程"))
            assertFalse(afterReverse.generatedMarkdown.contains("114.240.88.123"))

            val trustedBastion = harness.sshVaultRepository.serversFlow.value.first { it.id == bastion.id }
            val trustedTarget = harness.sshVaultRepository.serversFlow.value.first { it.id == target.id }
            assertEquals(bastionKey.fingerprintSha256, trustedBastion.knownHostKeyFingerprint)
            assertEquals(targetKey.fingerprintSha256, trustedTarget.knownHostKeyFingerprint)

            // 2. Open R6 SFTP drawer on target -> reuses persisted Trusted TOFU keys for both hops
            sshVm.openSftpDrawer(target.id)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    sshVm.uiState.first { it.sftpDrawer.isConnected && !it.sftpDrawer.isConnecting }
                }
            }
            assertEquals(2, sftpVerifications.size)
            assertTrue(sftpVerifications.all { it is HostKeyVerificationResult.Trusted })

            sshVm.sftpOpenFile("/etc/sing-box/config.json", preferEditMode = true)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    sshVm.uiState.first { !it.sftpDrawer.isOperating && it.sftpDrawer.openFile?.isEditMode == true }
                }
            }
            val editedSingBox = """{"inbounds":[],"outbounds":[{"type":"direct","tag":"tuned"}]}"""
            sshVm.sftpUpdateEditorContent(editedSingBox)
            sshVm.sftpSaveFileWithBak()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    sshVm.uiState.first { !it.sftpDrawer.isOperating && it.sftpDrawer.openFile?.isDirty == false }
                }
            }
            assertEquals(originalSingBox, sftpClient.readRawFileBytes("/etc/sing-box/config.json.bak")?.toString(Charsets.UTF_8))
            assertEquals(editedSingBox, sftpClient.readRawFileBytes("/etc/sing-box/config.json")?.toString(Charsets.UTF_8))
            assertNotNull(netBenchVm.uiState.value.reverseTracerouteResult)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T3-05 R5 and R7 reflect CN2 GIA to 163 plus Cogent detour shift in Before-vs-After diff card`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, sessionCookie = "ns_t3_05_cookie")
            harness.registerNetBenchGeoIpRoutes()

            var isDegradedRun = false
            val kernelOps = E2eTracerouteKernelOps(
                hopResponder = { ttl, seq, roundIdx ->
                    if (!isDegradedRun) {
                        when (ttl) {
                            1 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "59.43.80.1", seq, 12.0)
                            else -> TracerouteProbeReply(TracerouteProbeReply.Kind.ECHO_REPLY, "203.0.113.88", seq, 35.0)
                        }
                    } else {
                        when (ttl) {
                            1 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "202.97.33.1", seq, 18.0)
                            2 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "154.54.1.1", seq, 155.0)
                            else -> if (roundIdx == 1) {
                                TracerouteProbeReply(TracerouteProbeReply.Kind.TIMEOUT, null, seq, null)
                            } else {
                                TracerouteProbeReply(TracerouteProbeReply.Kind.ECHO_REPLY, "203.0.113.88", seq, 185.0)
                            }
                        }
                    }
                },
            )

            val controller = harness.createNetBenchController(
                networkBridge = E2eNetBenchNetworkBridge(),
                tracerouteKernelOps = kernelOps,
                baselineRepo = harness.benchmarkBaselineStore,
            )

            // 1. Initial CN2 GIA run & save Purchase Baseline
            controller.updateTargetInput("203.0.113.88")
            controller.updateTracerouteProbesPerHop(5)
            controller.runForwardTracerouteOnly()
            val giaState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    controller.uiState.first { !it.isRunning && it.activePhase == NetBenchPhase.COMPLETED }
                }
            }
            assertTrue(BackboneRouteTag.CN2_GIA in giaState.tracerouteProgress!!.summary.dominantBackboneTags)

            controller.saveCurrentAsBaseline(label = "购入首测 CN2 GIA")
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.benchmarkBaselineStore.baselinesFlow.first { it.isNotEmpty() }
                }
            }

            // 2. Simulate route shift -> 163 + Cogent US detour + loss
            harness.clock.advanceByMillis(15L * 86_400_000L)
            isDegradedRun = true
            controller.runForwardTracerouteOnly()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    controller.uiState.first {
                        !it.isRunning &&
                            it.activePhase == NetBenchPhase.COMPLETED &&
                            it.tracerouteProgress?.summary?.dominantBackboneTags?.contains(BackboneRouteTag.CT_163) == true
                    }
                }
            }

            controller.openBaselineCompareSheet()
            val compareState = controller.uiState.value
            assertTrue(compareState.showBaselineCompareSheet)
            val diff = compareState.activeBaselineDiff!!
            assertEquals(BaselineOverallVerdict.SIGNIFICANT_DEGRADATION, diff.overallVerdict)

            val shift = diff.routingShifts.first { it.directionLabel == "去程路由" }
            assertTrue(shift.backboneDowngraded)
            assertTrue(shift.detourIntroduced)
            assertEquals(MetricTrend.DEGRADED_SEVERE, shift.trend)

            val md = BaselineComparisonFormatter.formatMarkdown(diff, maskTargetIp = true)
            assertTrue(md.contains("203.0.113.*"))
            assertFalse(md.contains("203.0.113.88"))
            assertTrue(md.contains("163") || md.contains("Cogent"))

            val cardTexts = AndroidBaselineComparisonCardImageGenerator.extractRenderedCardTexts(diff, maskTargetIp = true)
            assertTrue(cardTexts.none { it.contains("203.0.113.88") })
            assertTrue(cardTexts.any { it.contains("203.0.113.*") })
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T3-06 R6 and R7 SFTP config tweak with bak backup followed by re-bench improves vs Purchase Baseline`() = runTest {
        E2eTestHarness().use { harness ->
            val fp = HostKeyTrustPolicy.computeSha256Fingerprint("tuned-vps-key".toByteArray())
            val savedServer = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "Tuneable VPS",
                    host = "198.51.100.77",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "pass",
                ),
            )
            harness.sshVaultRepository.updateHostKeyFingerprint(savedServer.id, fp, "ssh-ed25519")
            val hostKey = HostKeyInfo("198.51.100.77", 22, "ssh-ed25519", fp)

            val run1Output = """
                Download: 180.0 Mbps
                Upload: 120.0 Mbps
                Ping: 140.0 ms
                电信回程: 163 骨干
            """.trimIndent()
            val run2Output = """
                Download: 520.0 Mbps
                Upload: 380.0 Mbps
                Ping: 42.0 ms
                电信回程: CN2 GIA
            """.trimIndent()

            var runCounter = 0
            val sshTransport = E2eSshTransport(
                hostKeyByServerOrHost = mapOf(savedServer.id to hostKey),
                commandHandler = { _, _ ->
                    runCounter += 1
                    val out = if (runCounter == 1) run1Output else run2Output
                    listOf(
                        SshStreamEvent.Connected,
                        SshStreamEvent.OutputChunk(out + "\n"),
                        SshStreamEvent.Completed(0),
                    )
                },
            )
            val originalConfig = """{"congestion":"cubic","mtu":1500}"""
            val sftpClient = E2eSftpClient(
                homeDirectory = "/root",
                initialFiles = mapOf("/etc/sing-box/config.json" to originalConfig.toByteArray()),
            )
            val sftpTransport = E2eSftpTransport(
                hostKeyByServerOrHost = mapOf(savedServer.id to hostKey),
                clientFactory = { sftpClient },
            )
            val manager = harness.createSshSessionManager(
                transport = sshTransport,
                sftpTransport = sftpTransport,
                baselineRepo = harness.benchmarkBaselineStore,
            )
            val vm = harness.manage(LiteSshViewModel(manager, ownsSessionManagerScope = false))
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.servers.any { s -> s.id == savedServer.id } }
                }
            }
            vm.selectServer(savedServer.id)
            vm.selectPreset(SshPresetCatalog.PRESET_NODE_QUALITY)

            // 1. Run 1 (degraded) and save as Purchase Baseline
            vm.runSelectedPreset()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { !it.isRunning && it.sessionRuns.size == 1 } }
            }
            vm.saveCurrentAsBaseline(label = "调优前购入基线")
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { harness.benchmarkBaselineStore.baselinesFlow.first { it.isNotEmpty() } }
            }

            // 2. Open SFTP drawer, edit /etc/sing-box/config.json, save with .bak
            vm.openSftpDrawer(savedServer.id)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.sftpDrawer.isConnected } }
            }
            vm.sftpOpenFile("/etc/sing-box/config.json", preferEditMode = true)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { !it.sftpDrawer.isOperating && it.sftpDrawer.openFile?.isEditMode == true } }
            }
            val tunedConfig = """{"congestion":"bbr","mtu":1420}"""
            vm.sftpUpdateEditorContent(tunedConfig)
            vm.sftpSaveFileWithBak()
            val afterSave = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.sftpDrawer.isOperating && it.sftpDrawer.openFile?.isDirty == false }
                }
            }
            assertEquals(originalConfig, sftpClient.readRawFileBytes("/etc/sing-box/config.json.bak")?.toString(Charsets.UTF_8))
            assertEquals(tunedConfig, sftpClient.readRawFileBytes("/etc/sing-box/config.json")?.toString(Charsets.UTF_8))
            assertTrue(afterSave.sftpDrawer.lastBackupBanner?.contains(".bak") == true)

            // 3. Advance clock by 7 days, clear runs, re-bench, and compare vs Purchase Baseline
            harness.clock.advanceByMillis(7L * 86_400_000L)
            vm.clearSessionRuns()
            vm.runSelectedPreset()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { !it.isRunning && it.sessionRuns.size == 1 } }
            }
            vm.openBaselineCompareSheet()

            val state = vm.uiState.value
            assertTrue(state.showBaselineCompareSheet)
            val diff = state.activeBaselineDiff!!
            assertEquals(7L, diff.elapsedDays)
            assertEquals(BaselineOverallVerdict.IMPROVED, diff.overallVerdict)

            val dlDelta = diff.bandwidthDeltas.first { it.metricLabel == "下行带宽" }
            assertEquals(340.0, dlDelta.deltaMbps!!, 0.01)
            assertEquals(188.89, dlDelta.deltaPercent!!, 0.1)
            assertEquals(MetricTrend.IMPROVED, dlDelta.trend)
            assertTrue(diff.routingShifts.any { it.trend == MetricTrend.IMPROVED })
            assertEquals(originalConfig, sftpClient.readRawFileBytes("/etc/sing-box/config.json.bak")?.toString(Charsets.UTF_8))
        }
    }
}
