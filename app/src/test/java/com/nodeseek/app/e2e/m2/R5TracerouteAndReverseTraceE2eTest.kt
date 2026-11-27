package com.nodeseek.app.e2e.m2

import com.nodeseek.app.data.netbench.IpIntelligenceClient
import com.nodeseek.app.data.netbench.NetBenchTargetResolver
import com.nodeseek.app.data.netbench.ReverseDnsLookup
import com.nodeseek.app.data.netbench.TracerouteProbeReply
import com.nodeseek.app.data.netbench.TracerouteRunner
import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.domain.netbench.BackboneRouteTag
import com.nodeseek.app.domain.netbench.BackboneRouteTagger
import com.nodeseek.app.domain.netbench.ClientCarrier
import com.nodeseek.app.domain.netbench.ClientNetworkInfo
import com.nodeseek.app.domain.netbench.HopLossClassification
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.NetBenchMarkdownFormatter
import com.nodeseek.app.domain.netbench.NetBenchMarkdownOptions
import com.nodeseek.app.domain.netbench.NetBenchMarkdownStyle
import com.nodeseek.app.domain.netbench.NetBenchReport
import com.nodeseek.app.domain.netbench.NetBenchReportSanitizer
import com.nodeseek.app.domain.netbench.ResolvedTarget
import com.nodeseek.app.domain.netbench.ReverseTracerouteCodec
import com.nodeseek.app.domain.netbench.TracerouteConfig
import com.nodeseek.app.domain.netbench.TracerouteDirection
import com.nodeseek.app.domain.netbench.TracerouteHop
import com.nodeseek.app.domain.netbench.TracerouteSummary
import com.nodeseek.app.domain.ssh.HostKeyInfo
import com.nodeseek.app.domain.ssh.HostKeyTrustPolicy
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.e2e.E2eFixtures
import com.nodeseek.app.e2e.E2eMainDispatcherRule
import com.nodeseek.app.e2e.E2eNetBenchNetworkBridge
import com.nodeseek.app.e2e.E2eSshTransport
import com.nodeseek.app.e2e.E2eTestHarness
import com.nodeseek.app.e2e.E2eTracerouteKernelOps
import com.nodeseek.app.ui.netbench.NetBenchPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class R5TracerouteAndReverseTraceE2eTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    @Test
    fun `E2E-T1-R5-1 multi-hop forward MTR and reverse traceroute codecs compute accurate per-hop stats`() = runTest {
        E2eTestHarness().use { harness ->
            harness.registerNetBenchGeoIpRoutes()

            val hopIps = mapOf(
                1 to "192.168.1.1",
                2 to "100.64.0.1",
                3 to "59.43.80.1",
                4 to "59.43.246.10",
                5 to "154.17.12.88",
            )
            val hopRtts = mapOf(
                1 to listOf(1.1, 1.3, 1.2, 1.6, 1.4),
                2 to listOf(4.2, 4.5, 4.8, 5.1, 4.4),
                3 to listOf(11.9, 12.3, 12.6, 13.2, 12.5),
                4 to listOf(137.8, 138.4, 139.1, 141.0, 138.7),
                5 to listOf(141.6, 142.1, 142.5, 143.9, 142.4),
            )
            val kernelOps = E2eTracerouteKernelOps(
                hopResponder = { ttl, seq, roundIdx ->
                    val ip = hopIps[ttl]
                    val rtt = hopRtts[ttl]?.getOrNull(roundIdx) ?: 10.0
                    when (ttl) {
                        in 1..4 -> TracerouteProbeReply(
                            kind = TracerouteProbeReply.Kind.TIME_EXCEEDED,
                            responderIp = ip,
                            sequence = seq,
                            rttMs = rtt,
                        )
                        5 -> TracerouteProbeReply(
                            kind = TracerouteProbeReply.Kind.ECHO_REPLY,
                            responderIp = ip,
                            sequence = seq,
                            rttMs = rtt,
                        )
                        else -> TracerouteProbeReply(
                            kind = TracerouteProbeReply.Kind.TIMEOUT,
                            responderIp = null,
                            sequence = seq,
                            rttMs = null,
                        )
                    }
                },
            )
            val runner = TracerouteRunner(
                networkBridge = E2eNetBenchNetworkBridge(),
                kernelOps = kernelOps,
                ipIntelligenceClient = harness.createIpIntelligenceClient(),
                reverseDnsLookup = ReverseDnsLookup { null },
                clockMillis = harness.clock::nowMillis,
            )

            val finalProgress = runner.run(
                TracerouteConfig(target = "154.17.12.88", probesPerHop = 5, maxHops = 15),
            ).last()

            assertTrue(finalProgress.isFinished)
            assertNull(finalProgress.errorMessage)
            val summary = finalProgress.summary
            assertTrue(summary.reachedTarget)
            assertEquals(5, summary.totalHops)
            assertEquals(142.5, summary.terminalAvgMs!!, 0.05)

            for (hop in summary.hops) {
                assertEquals(5, hop.sent)
                assertEquals(5, hop.received)
                assertEquals(0.0, hop.lossPercent, 0.001)
                assertNotNull(hop.minMs)
                assertNotNull(hop.avgMs)
                assertNotNull(hop.maxMs)
                val minMs = hop.minMs!!
                val avgMs = hop.avgMs!!
                val maxMs = hop.maxMs!!
                assertTrue(minMs <= avgMs && avgMs <= maxMs)
                assertNotNull(hop.p95Ms)
                assertNotNull(hop.jitterMdevMs)
            }
            assertTrue(summary.hops.last().isTargetReached)

            // Verify ReverseTracerouteCodec across mtr, nexttrace, traceroute, and tracepath fixtures
            val mtrReverse = ReverseTracerouteCodec.parseOutput(
                plainOutput = E2eFixtures.loadMtrSection("MTR_9929_TYO"),
                targetIp = "114.242.10.20",
                sourceLabel = "TYO-VPS",
                testedAtEpochMillis = harness.clock.nowMillis(),
            )
            assertEquals("mtr", mtrReverse.toolName)
            assertTrue(mtrReverse.reachedTarget)
            assertEquals(4, mtrReverse.totalHops)
            assertTrue(BackboneRouteTag.CU_9929 in mtrReverse.dominantBackboneTags)

            val ntReverse = ReverseTracerouteCodec.parseOutput(
                plainOutput = E2eFixtures.loadMtrSection("REVERSE_NEXTTRACE_CN2_GIA"),
                targetIp = "114.240.88.123",
                sourceLabel = "LAX-CN2",
                testedAtEpochMillis = harness.clock.nowMillis(),
            )
            assertEquals("nexttrace", ntReverse.toolName)
            assertTrue(ntReverse.reachedTarget)
            assertTrue(BackboneRouteTag.CN2_GIA in ntReverse.dominantBackboneTags)

            val trReverse = ReverseTracerouteCodec.parseOutput(
                plainOutput = E2eFixtures.loadMtrSection("REVERSE_TRACEROUTE_4837"),
                targetIp = "114.240.88.123",
                sourceLabel = "SEA-4837",
                testedAtEpochMillis = harness.clock.nowMillis(),
            )
            assertEquals("traceroute", trReverse.toolName)
            assertTrue(trReverse.reachedTarget)
            assertTrue(BackboneRouteTag.CU_4837 in trReverse.dominantBackboneTags)

            val tpReverse = ReverseTracerouteCodec.parseOutput(
                plainOutput = E2eFixtures.loadMtrSection("REVERSE_TRACEPATH_SOFTBANK"),
                targetIp = "114.240.88.123",
                sourceLabel = "TYO-SB",
                testedAtEpochMillis = harness.clock.nowMillis(),
            )
            assertEquals("tracepath", tpReverse.toolName)
            assertTrue(tpReverse.reachedTarget)
            assertTrue(BackboneRouteTag.SOFTBANK in tpReverse.dominantBackboneTags)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T1-R5-2 auto-labels all six backbones and distinguishes CN2 GIA from overseas 163 CN2 GT`() = runTest {
        E2eTestHarness().use { harness ->
            harness.registerNetBenchGeoIpRoutes()

            // Verify all 6 required backbones from IP prefix & GeoIP/ASN
            assertTrue(BackboneRouteTag.CN2_GIA in BackboneRouteTagger.classifyHop("59.43.80.1", asn = "AS4809"))
            assertTrue(BackboneRouteTag.CU_9929 in BackboneRouteTagger.classifyHop("218.105.2.1", asn = "AS9929"))
            assertTrue(BackboneRouteTag.CMIN2 in BackboneRouteTagger.classifyHop("223.119.26.1", asn = "AS58807"))
            assertTrue(BackboneRouteTag.CU_4837 in BackboneRouteTagger.classifyHop("219.158.3.1", asn = "AS4837"))
            assertTrue(BackboneRouteTag.IIJ in BackboneRouteTagger.classifyHop("202.232.1.1", asn = "AS2497"))
            assertTrue(BackboneRouteTag.SOFTBANK in BackboneRouteTagger.classifyHop("210.175.1.1", asn = "AS17676"))

            // Path 1: Domestic CN 202.97.33.1 -> 59.43.80.1 -> 154.17.12.88 stays CN2 GIA
            val giaKernel = E2eTracerouteKernelOps(
                hopResponder = { ttl, seq, _ ->
                    when (ttl) {
                        1 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "202.97.33.1", seq, 10.0)
                        2 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "59.43.80.1", seq, 14.0)
                        else -> TracerouteProbeReply(TracerouteProbeReply.Kind.ECHO_REPLY, "154.17.12.88", seq, 140.0)
                    }
                },
            )
            val giaSummary = TracerouteRunner(
                networkBridge = E2eNetBenchNetworkBridge(),
                kernelOps = giaKernel,
                ipIntelligenceClient = harness.createIpIntelligenceClient(),
                reverseDnsLookup = ReverseDnsLookup { null },
                clockMillis = harness.clock::nowMillis,
            ).run(TracerouteConfig(target = "154.17.12.88", probesPerHop = 3)).last().summary

            assertTrue(BackboneRouteTag.CN2_GIA in giaSummary.dominantBackboneTags)
            assertFalse(BackboneRouteTag.CN2_GT in giaSummary.dominantBackboneTags)

            // Path 2: 59.43.80.1 + overseas US 202.97.50.1 downgrades path to CN2 GT
            val gtKernel = E2eTracerouteKernelOps(
                hopResponder = { ttl, seq, _ ->
                    when (ttl) {
                        1 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "59.43.80.1", seq, 14.0)
                        2 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "202.97.50.1", seq, 130.0)
                        else -> TracerouteProbeReply(TracerouteProbeReply.Kind.ECHO_REPLY, "154.17.12.88", seq, 145.0)
                    }
                },
            )
            val gtSummary = TracerouteRunner(
                networkBridge = E2eNetBenchNetworkBridge(),
                kernelOps = gtKernel,
                ipIntelligenceClient = harness.createIpIntelligenceClient(),
                reverseDnsLookup = ReverseDnsLookup { null },
                clockMillis = harness.clock::nowMillis,
            ).run(TracerouteConfig(target = "154.17.12.88", probesPerHop = 3)).last().summary

            assertTrue(BackboneRouteTag.CN2_GT in gtSummary.dominantBackboneTags)
            assertFalse(BackboneRouteTag.CN2_GIA in gtSummary.dominantBackboneTags)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T1-R5-3 detects peak hour window and separates intermediate rate-limiting from real packet loss`() = runTest {
        E2eTestHarness().use { harness ->
            harness.registerNetBenchGeoIpRoutes()
            val peakEpoch = ZonedDateTime.of(2026, 11, 25, 21, 30, 0, 0, ZoneId.of("Asia/Shanghai"))
                .toInstant().toEpochMilli()
            harness.clock.setEpochMillis(peakEpoch)

            // Case A: Hop 2 has 40% loss (2/5 timeout), Hop 3 has 0% loss -> INTERMEDIATE_RATE_LIMIT
            val rateLimitKernel = E2eTracerouteKernelOps(
                hopResponder = { ttl, seq, roundIdx ->
                    when (ttl) {
                        1 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "192.168.1.1", seq, 1.2)
                        2 -> if (roundIdx < 2) {
                            TracerouteProbeReply(TracerouteProbeReply.Kind.TIMEOUT, null, seq, null)
                        } else {
                            TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "59.43.80.1", seq, 15.0)
                        }
                        else -> TracerouteProbeReply(TracerouteProbeReply.Kind.ECHO_REPLY, "154.17.12.88", seq, 140.0)
                    }
                },
            )
            val rateLimitSummary = TracerouteRunner(
                networkBridge = E2eNetBenchNetworkBridge(),
                kernelOps = rateLimitKernel,
                ipIntelligenceClient = harness.createIpIntelligenceClient(),
                reverseDnsLookup = ReverseDnsLookup { null },
                clockMillis = harness.clock::nowMillis,
            ).run(TracerouteConfig(target = "154.17.12.88", probesPerHop = 5)).last().summary

            assertTrue(rateLimitSummary.isPeakHourWindow)
            assertTrue(rateLimitSummary.timeWindowLabel.contains("晚高峰"))
            assertEquals(HopLossClassification.INTERMEDIATE_RATE_LIMIT, rateLimitSummary.hops[1].lossClassification)
            assertEquals(40.0, rateLimitSummary.hops[1].lossPercent, 0.1)
            assertTrue(rateLimitSummary.hasIntermediateRateLimitOnly)
            assertFalse(rateLimitSummary.hasRealPacketLoss)

            // Case B: Terminal hop 4 has 40% loss -> REAL_PACKET_LOSS
            val realLossKernel = E2eTracerouteKernelOps(
                hopResponder = { ttl, seq, roundIdx ->
                    when (ttl) {
                        1 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "192.168.1.1", seq, 1.2)
                        2 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "59.43.80.1", seq, 14.0)
                        3 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "59.43.246.10", seq, 136.0)
                        else -> if (roundIdx in 1..2) {
                            TracerouteProbeReply(TracerouteProbeReply.Kind.TIMEOUT, null, seq, null)
                        } else {
                            TracerouteProbeReply(TracerouteProbeReply.Kind.ECHO_REPLY, "154.17.12.88", seq, 145.0)
                        }
                    }
                },
            )
            val realLossSummary = TracerouteRunner(
                networkBridge = E2eNetBenchNetworkBridge(),
                kernelOps = realLossKernel,
                ipIntelligenceClient = harness.createIpIntelligenceClient(),
                reverseDnsLookup = ReverseDnsLookup { null },
                clockMillis = harness.clock::nowMillis,
            ).run(TracerouteConfig(target = "154.17.12.88", probesPerHop = 5)).last().summary

            assertEquals(HopLossClassification.REAL_PACKET_LOSS, realLossSummary.hops.last().lossClassification)
            assertTrue(realLossSummary.hasRealPacketLoss)
            assertEquals(40.0, realLossSummary.peakHopLossPercent, 0.1)
            assertEquals(4, realLossSummary.peakLossHopNumber)

            val md = NetBenchMarkdownFormatter.format(
                NetBenchReport(
                    targetInput = "154.17.12.88",
                    forwardTraceroute = realLossSummary,
                ),
            )
            assertTrue(md.contains("晚高峰"))
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T1-R5-4 refreshes client real egress IP and executes one-tap SSH reverse trace without cookie leaks`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(
                uid = 101L,
                username = "alice101",
                sessionCookie = "secret_nodeseek_cookie",
            )
            harness.registerNetBenchGeoIpRoutes(clientEgressIp = "114.240.88.123")

            val profile = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "DMIT LAX",
                    host = "154.17.12.88",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "pass123",
                ),
            )

            val nexttraceFixture = E2eFixtures.loadMtrSection("REVERSE_NEXTTRACE_CN2_GIA")
            val sshTransport = E2eSshTransport(
                commandHandler = { _, cmd ->
                    assertTrue(cmd.contains("TARGET='114.240.88.123'"))
                    listOf(
                        SshStreamEvent.Connected,
                        SshStreamEvent.OutputChunk(nexttraceFixture + "\n"),
                        SshStreamEvent.Completed(0),
                    )
                },
            )
            val sshManager = harness.createSshSessionManager(transport = sshTransport)
            val vm = harness.createNetBenchViewModel(
                networkBridge = E2eNetBenchNetworkBridge(),
                sshSessionManager = sshManager,
            )

            vm.refreshClientEgress()
            val afterEgress = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.collectUntil { !it.isRefreshingEgress && it.clientEgress?.directNetwork?.rawIp == "114.240.88.123" }
                }
            }
            assertEquals("114.240.88.123", afterEgress.clientEgress?.directNetwork?.rawIp)

            vm.runReverseTraceroute(profile)
            val afterReverse = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.collectUntil {
                        !it.isRunningReverseTrace && (it.reverseTracerouteResult != null || it.reverseTraceError != null)
                    }
                }
            }

            assertNull("reverseTraceError=${afterReverse.reverseTraceError}", afterReverse.reverseTraceError)
            assertNotNull(afterReverse.reverseTracerouteResult)
            val rev = afterReverse.reverseTracerouteResult!!
            assertEquals(TracerouteDirection.REVERSE, rev.direction)
            assertEquals("nexttrace", rev.toolName)
            assertTrue(rev.reachedTarget)
            assertTrue(BackboneRouteTag.CN2_GIA in rev.dominantBackboneTags)
            assertEquals(1, sshTransport.recordedRequests.size)
            assertFalse(afterReverse.generatedMarkdown.contains("114.240.88.123"))
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T1-R5-5 renders forward and reverse traces in Markdown with IPv4 and IPv6 privacy masking`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.registerNetBenchGeoIpRoutes(clientEgressIp = "114.240.88.123")

            val forwardKernel = E2eTracerouteKernelOps(
                hopResponder = { ttl, seq, _ ->
                    when (ttl) {
                        1 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "192.168.1.1", seq, 1.0)
                        2 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "59.43.80.1", seq, 12.0)
                        else -> TracerouteProbeReply(TracerouteProbeReply.Kind.ECHO_REPLY, "154.17.12.88", seq, 142.0)
                    }
                },
            )
            val sshTransport = E2eSshTransport(
                commandHandler = { _, _ ->
                    listOf(
                        SshStreamEvent.Connected,
                        SshStreamEvent.OutputChunk(E2eFixtures.loadMtrSection("REVERSE_NEXTTRACE_CN2_GIA") + "\n"),
                        SshStreamEvent.Completed(0),
                    )
                },
            )
            val sshManager = harness.createSshSessionManager(transport = sshTransport)
            val profile = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "DMIT LAX",
                    host = "154.17.12.88",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "pass123",
                ),
            )

            val vm = harness.createNetBenchViewModel(
                networkBridge = E2eNetBenchNetworkBridge(),
                tracerouteKernelOps = forwardKernel,
                sshSessionManager = sshManager,
            )
            vm.updateTargetInput("154.17.12.88")
            vm.updateTracerouteProbesPerHop(3)
            vm.runForwardTracerouteOnly()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.collectUntil { !it.isRunning && it.tracerouteProgress?.isFinished == true }
                }
            }

            vm.runReverseTraceroute(profile)
            val combinedState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.collectUntil {
                        !it.isRunningReverseTrace && (it.reverseTracerouteResult != null || it.reverseTraceError != null)
                    }
                }
            }
            assertNull("reverseTraceError=${combinedState.reverseTraceError}", combinedState.reverseTraceError)
            assertNotNull(combinedState.reverseTracerouteResult)

            val fullMasked = combinedState.generatedMarkdown
            assertTrue(fullMasked.contains("去程"))
            assertTrue(fullMasked.contains("回程"))
            assertTrue(fullMasked.contains("CN2 GIA"))
            assertTrue(fullMasked.contains("154.17.12.*"))
            assertFalse(fullMasked.contains("154.17.12.88"))
            assertFalse(fullMasked.contains("114.240.88.123"))
            assertFalse(fullMasked.contains("114.240.*.*"))

            vm.toggleMaskTargetIp(false)
            val fullUnmasked = vm.uiState.value.generatedMarkdown
            assertTrue(fullUnmasked.contains("154.17.12.88"))
            assertFalse(fullUnmasked.contains("114.240.88.123"))

            vm.selectMarkdownStyle(NetBenchMarkdownStyle.REPLY_CARD)
            val replyCard = vm.uiState.value.generatedMarkdown
            assertTrue(replyCard.contains("去程路由"))
            assertTrue(replyCard.contains("回程路由"))
            assertFalse(replyCard.contains("114.240.88.123"))

            // Also verify IPv6 target masking & client IPv6 scrubbing
            val ipv6Report = NetBenchReport(
                targetInput = "2606:4700:4700::1111",
                resolvedTarget = ResolvedTarget(
                    displayHost = "2606:4700:4700::1111",
                    resolvedIp = "2606:4700:4700::1111",
                    ipFamily = IpFamily.IPV6,
                ),
                clientNetwork = ClientNetworkInfo(
                    carrier = ClientCarrier.CHINA_UNICOM,
                    ipFamily = IpFamily.IPV6,
                    rawIp = "2408:8207:1234::1",
                    maskedIp = "2408:8207:1234::*",
                ),
                forwardTraceroute = TracerouteSummary(
                    direction = TracerouteDirection.FORWARD,
                    targetInput = "2606:4700:4700::1111",
                    targetIp = "2606:4700:4700::1111",
                    ipFamily = IpFamily.IPV6,
                    hops = listOf(
                        TracerouteHop(
                            hopNumber = 1,
                            ip = "2606:4700:4700::1111",
                            sent = 3,
                            received = 3,
                            lossPercent = 0.0,
                            avgMs = 15.0,
                            backboneTags = listOf(BackboneRouteTag.CLOUDFLARE),
                            lossClassification = HopLossClassification.ZERO_LOSS,
                            isTargetReached = true,
                        ),
                    ),
                    reachedTarget = true,
                    totalHops = 1,
                    terminalAvgMs = 15.0,
                    dominantBackboneTags = listOf(BackboneRouteTag.CLOUDFLARE),
                    routeSignature = "Cloudflare",
                ),
            )
            val v6Md = NetBenchMarkdownFormatter.format(ipv6Report, NetBenchMarkdownOptions(maskTargetIp = true))
            assertFalse(v6Md.contains("2606:4700:4700::1111"))
            assertFalse(v6Md.contains("2408:8207:1234::1"))
            assertTrue(v6Md.contains("2606:4700:4700::*"))
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T2-R5-1 handles intermediate 100-percent timeout hops and trims trailing timeout streak`() = runTest {
        E2eTestHarness().use { harness ->
            harness.registerNetBenchGeoIpRoutes()

            // Hop 1: 192.168.1.1 (reply)
            // Hop 2: timeout (100% loss)
            // Hop 3: 59.43.80.1 (reply)
            // Hops 4..9: consecutive timeouts (trailing streak -> trimmed cleanly)
            val kernelOps = E2eTracerouteKernelOps(
                hopResponder = { ttl, seq, _ ->
                    when (ttl) {
                        1 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "192.168.1.1", seq, 1.0)
                        3 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "59.43.80.1", seq, 14.0)
                        else -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIMEOUT, null, seq, null)
                    }
                },
            )
            val summary = TracerouteRunner(
                networkBridge = E2eNetBenchNetworkBridge(),
                kernelOps = kernelOps,
                ipIntelligenceClient = harness.createIpIntelligenceClient(),
                reverseDnsLookup = ReverseDnsLookup { null },
                clockMillis = harness.clock::nowMillis,
            ).run(TracerouteConfig(target = "154.17.12.88", probesPerHop = 3, maxHops = 20)).last().summary

            assertFalse(summary.reachedTarget)
            assertEquals(3, summary.totalHops)
            assertNull(summary.hops[1].ip)
            assertEquals(HopLossClassification.TIMEOUT_NO_REPLY, summary.hops[1].lossClassification)
            assertEquals("59.43.80.1", summary.hops[2].ip)
            assertTrue(harness.externalDispatcher.recordedRequests().none { it.path?.contains("null") == true })

            // Also verify IPv6 MTR fixture with ??? 100% loss hop
            val v6Summary = ReverseTracerouteCodec.parseOutput(
                plainOutput = E2eFixtures.loadMtrSection("MTR_IPV6_CN2_GIA"),
                targetIp = "2408:8207:1234::1",
            )
            assertEquals(4, v6Summary.totalHops)
            assertNull(v6Summary.hops[2].ip)
            assertEquals(HopLossClassification.TIMEOUT_NO_REPLY, v6Summary.hops[2].lossClassification)
            assertTrue(v6Summary.reachedTarget)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T2-R5-2 skips GeoIP for private or CGNAT hops and rejects invalid or injection client IPs`() = runTest {
        E2eTestHarness().use { harness ->
            harness.registerNetBenchGeoIpRoutes()

            val kernelOps = E2eTracerouteKernelOps(
                hopResponder = { ttl, seq, _ ->
                    when (ttl) {
                        1 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "192.168.1.1", seq, 0.8)
                        2 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "100.64.0.1", seq, 3.2)
                        3 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "10.0.0.1", seq, 4.1)
                        4 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "::ffff:192.168.1.1", seq, 5.0)
                        else -> TracerouteProbeReply(TracerouteProbeReply.Kind.ECHO_REPLY, "154.17.12.88", seq, 140.0)
                    }
                },
            )
            val summary = TracerouteRunner(
                networkBridge = E2eNetBenchNetworkBridge(),
                kernelOps = kernelOps,
                ipIntelligenceClient = harness.createIpIntelligenceClient(),
                reverseDnsLookup = ReverseDnsLookup { null },
                clockMillis = harness.clock::nowMillis,
            ).run(TracerouteConfig(target = "154.17.12.88", probesPerHop = 3)).last().summary

            assertTrue(summary.hops[0].isPrivateOrCgnat)
            assertTrue(summary.hops[1].isPrivateOrCgnat)
            assertTrue(summary.hops[2].isPrivateOrCgnat)
            assertTrue(summary.hops[3].isPrivateOrCgnat)
            assertFalse(summary.hops[4].isPrivateOrCgnat)

            val recordedPaths = harness.externalDispatcher.recordedRequests().mapNotNull { it.path }
            assertFalse(recordedPaths.any { it.contains("192.168.1.1") || it.contains("100.64.0.1") || it.contains("10.0.0.1") })

            val rejectedIps = listOf(
                null,
                "",
                "198.18.0.5",
                "192.168.1.10",
                "100.64.10.1",
                "127.0.0.1",
                "fe80::1%eth0",
                "2001:db8::1",
                "fc00::1",
                "1.1.1.1; rm -rf /",
                "8.8.8.8$(id)",
                "example.com",
            )
            for (bad in rejectedIps) {
                assertTrue("Expected failure for $bad", ReverseTracerouteCodec.validateClientPublicIp(bad).isFailure)
            }
            assertEquals("114.240.88.123", ReverseTracerouteCodec.validateClientPublicIp(" 114.240.88.123 ").getOrNull())
            assertEquals("2408:8207:1234::1", ReverseTracerouteCodec.validateClientPublicIp("[2408:8207:1234::1]").getOrNull())
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T2-R5-3 GeoIP falls back from ipwho-is HTTP 500 to ipinfo-io and ipapi-co without cookie leaks`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, sessionCookie = "ns_cookie_guard")
            val root = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("netbench/geoip_and_rdap_hops.json"),
            ).jsonObject
            val ipInfoBody = root.getValue("ipInfoFallback").toString()
            val ipApiBody = root.getValue("ipApiFallback").toString()

            harness.externalDispatcher.registerPrefix("GET", "/ipwhois/") {
                MockResponse().setResponseCode(500).setBody("""{"error":"upstream 500"}""")
            }
            harness.externalDispatcher.register("GET", "/ipinfo/59.43.80.1") {
                MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(ipInfoBody)
            }
            harness.externalDispatcher.register("GET", "/ipinfo/202.232.1.1") {
                MockResponse().setResponseCode(500).setBody("""{"error":"rate limited"}""")
            }
            harness.externalDispatcher.register("GET", "/ipapi/202.232.1.1") {
                MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(ipApiBody)
            }

            val client = harness.createIpIntelligenceClient()
            val cn2Intel = client.lookupTargetIp("59.43.80.1")
            assertEquals("ipinfo.io", cn2Intel.geoSource)
            assertEquals("AS4809", cn2Intel.asn)
            assertEquals("CN", cn2Intel.countryCode)

            val iijIntel = client.lookupTargetIp("202.232.1.1")
            assertEquals("ipapi.co", iijIntel.geoSource)
            assertEquals("AS2497", iijIntel.asn)
            assertEquals("JP", iijIntel.countryCode)

            val kernelOps = E2eTracerouteKernelOps(
                hopResponder = { ttl, seq, _ ->
                    when (ttl) {
                        1 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "59.43.80.1", seq, 12.0)
                        else -> TracerouteProbeReply(TracerouteProbeReply.Kind.ECHO_REPLY, "202.232.1.1", seq, 42.0)
                    }
                },
            )
            val summary = TracerouteRunner(
                networkBridge = E2eNetBenchNetworkBridge(),
                kernelOps = kernelOps,
                ipIntelligenceClient = client,
                reverseDnsLookup = ReverseDnsLookup { null },
            ).run(TracerouteConfig(target = "202.232.1.1", probesPerHop = 3)).last().summary

            assertTrue(BackboneRouteTag.CN2_GIA in summary.dominantBackboneTags)
            assertTrue(BackboneRouteTag.IIJ in summary.dominantBackboneTags)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T2-R5-4 enforces VPN lockdown guard and refuses to leak proxy IP for reverse trace`() = runTest {
        E2eTestHarness().use { harness ->
            harness.registerNetBenchGeoIpRoutes(clientEgressIp = "172.67.1.10")
            val lockdownBridge = E2eNetBenchNetworkBridge.vpnLockdownNoDirect()
            val client = harness.createIpIntelligenceClient(networkBridge = lockdownBridge)

            val egress = client.detectClientEgress()
            assertTrue(egress.directBindFailed)
            assertNull(egress.directNetwork)
            assertNotNull(egress.proxyNetwork)

            val runner = TracerouteRunner(
                resolver = NetBenchTargetResolver(networkBridge = lockdownBridge),
                networkBridge = lockdownBridge,
                kernelOps = E2eTracerouteKernelOps(),
                ipIntelligenceClient = client,
                reverseDnsLookup = ReverseDnsLookup { null },
            )
            val blockedProgress = runner.run(TracerouteConfig(target = "154.17.12.88", useProxy = false)).last()
            assertTrue(blockedProgress.isFinished)
            assertTrue(blockedProgress.errorMessage?.contains("经由代理测试") == true)

            val sshTransport = E2eSshTransport()
            val sshManager = harness.createSshSessionManager(transport = sshTransport)
            val profile = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "HK VPS",
                    host = "203.0.113.88",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "pass",
                ),
            )
            val vm = harness.createNetBenchViewModel(
                networkBridge = lockdownBridge,
                ipIntelligenceClient = client,
                sshSessionManager = sshManager,
            )
            vm.runReverseTraceroute(profile)
            val state = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.collectUntil { !it.isRunningReverseTrace && it.reverseTraceError != null }
                }
            }
            assertNotNull(state.reverseTraceError)
            assertNull(state.reverseTracerouteResult)
            assertTrue(sshTransport.recordedRequests.isEmpty())
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T2-R5-5 preserves forward trace on SSH failure or tool=none and recovers after TOFU host key confirmation`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.registerNetBenchGeoIpRoutes(clientEgressIp = "114.240.88.123")

            val forwardKernel = E2eTracerouteKernelOps(
                hopResponder = { ttl, seq, _ ->
                    when (ttl) {
                        1 -> TracerouteProbeReply(TracerouteProbeReply.Kind.TIME_EXCEEDED, "59.43.80.1", seq, 12.0)
                        else -> TracerouteProbeReply(TracerouteProbeReply.Kind.ECHO_REPLY, "154.17.12.88", seq, 140.0)
                    }
                },
            )

            val oldFp = HostKeyTrustPolicy.computeSha256Fingerprint("old-key".toByteArray())
            val newKey = HostKeyInfo(
                host = "154.17.12.88",
                port = 22,
                algorithm = "ssh-ed25519",
                fingerprintSha256 = HostKeyTrustPolicy.computeSha256Fingerprint("new-key".toByteArray()),
            )
            val savedProfile = harness.sshVaultRepository.saveServer(
                SshServerDraft(
                    alias = "LAX-VPS",
                    host = "154.17.12.88",
                    port = 22,
                    username = "root",
                    authMethod = SshAuthMethod.PASSWORD,
                    secret = "pass",
                ),
            )
            harness.sshVaultRepository.updateHostKeyFingerprint(savedProfile.id, oldFp, "ssh-ed25519")
            val profileWithOldFp = harness.sshVaultRepository.serversFlow.value.single()

            var returnToolNone = false
            val sshTransport = E2eSshTransport(
                hostKeyByServerOrHost = mapOf(savedProfile.id to newKey),
                commandHandler = { _, _ ->
                    val out = if (returnToolNone) {
                        E2eFixtures.loadMtrSection("REVERSE_TOOL_NONE")
                    } else {
                        E2eFixtures.loadMtrSection("REVERSE_NEXTTRACE_CN2_GIA")
                    }
                    listOf(
                        SshStreamEvent.Connected,
                        SshStreamEvent.OutputChunk(out + "\n"),
                        SshStreamEvent.Completed(if (returnToolNone) 127 else 0),
                    )
                },
            )
            val sshManager = harness.createSshSessionManager(transport = sshTransport)
            val vm = harness.createNetBenchViewModel(
                networkBridge = E2eNetBenchNetworkBridge(),
                tracerouteKernelOps = forwardKernel,
                sshSessionManager = sshManager,
            )

            vm.updateTargetInput("154.17.12.88")
            vm.updateTracerouteProbesPerHop(3)
            vm.runForwardTracerouteOnly()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.collectUntil { !it.isRunning && it.activePhase == NetBenchPhase.COMPLETED }
                }
            }
            val forwardSnapshot = vm.uiState.value.tracerouteProgress
            assertNotNull(forwardSnapshot)

            // 1. Trigger TOFU mismatch -> forward trace stays intact, mismatch prompt shown
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.collectUntil { it.vaultServers.any { s -> s.id == savedProfile.id } }
                }
            }
            vm.runReverseTraceroute(profileWithOldFp)
            val mismatchState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.collectUntil {
                        !it.isRunningReverseTrace && it.pendingReverseTraceHostKeyMismatch != null
                    }
                }
            }
            assertEquals(forwardSnapshot, mismatchState.tracerouteProgress)
            assertEquals(newKey.fingerprintSha256, mismatchState.pendingReverseTraceHostKeyMismatch?.actualHostKey?.fingerprintSha256)

            // 2. Confirm trust -> updates vault and completes reverse trace
            vm.confirmTrustReverseTraceHostKey()
            val recoveredState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.collectUntil {
                        !it.isRunningReverseTrace &&
                            it.pendingReverseTraceHostKeyMismatch == null &&
                            (it.reverseTracerouteResult != null || (it.reverseTraceError != null && it.reverseTraceError != "检测到主机密钥变更，已阻断连接"))
                    }
                }
            }
            assertNull("reverseTraceError=${recoveredState.reverseTraceError}", recoveredState.reverseTraceError)
            assertNotNull(recoveredState.reverseTracerouteResult)
            assertEquals(forwardSnapshot, recoveredState.tracerouteProgress)
            assertTrue(recoveredState.reverseTracerouteResult?.reachedTarget == true)
            assertEquals(
                newKey.fingerprintSha256,
                harness.sshVaultRepository.serversFlow.value.single().knownHostKeyFingerprint,
            )

            // 3. Tool=none preserves forward trace and reports actionable error
            returnToolNone = true
            val trustedProfile = harness.sshVaultRepository.serversFlow.value.single()
            vm.runReverseTraceroute(trustedProfile)
            val toolNoneState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.collectUntil {
                        !it.isRunningReverseTrace && it.reverseTracerouteResult?.toolName == "none"
                    }
                }
            }
            assertEquals(forwardSnapshot, toolNoneState.tracerouteProgress)
            assertNotNull(toolNoneState.reverseTraceError)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }
}

private suspend fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectUntil(predicate: (T) -> Boolean): T {
    var matched: T? = null
    try {
        collect { value ->
            if (predicate(value)) {
                matched = value
                throw StopCollectingSignal()
            }
        }
    } catch (_: StopCollectingSignal) {
        // expected
    }
    return matched ?: value
}

private class StopCollectingSignal : RuntimeException()
