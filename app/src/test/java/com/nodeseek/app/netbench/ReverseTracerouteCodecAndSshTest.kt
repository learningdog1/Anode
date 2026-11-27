package com.nodeseek.app.netbench

import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.domain.netbench.BackboneRouteTag
import com.nodeseek.app.domain.netbench.HopLossClassification
import com.nodeseek.app.domain.netbench.ReverseTracerouteCodec
import com.nodeseek.app.domain.netbench.TracerouteDirection
import com.nodeseek.app.domain.ssh.HostKeyInfo
import com.nodeseek.app.domain.ssh.InMemoryTestCipher
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshConnectionMode
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshHostKeyMismatchException
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.domain.ssh.SshTransport
import com.nodeseek.app.ui.ssh.LiteSshSessionManager
import com.nodeseek.app.ui.ssh.SshForegroundServiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ReverseTracerouteCodecAndSshTest {

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
    fun `validateClientPublicIp rejects fake-ip private loopback domains and shell injection`() {
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp(null).isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("example.com").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("198.18.0.5").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("fc00::1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("127.0.0.1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("::1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("192.168.1.1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("100.64.0.10").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("1.1.1.1; rm -rf /").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("2408:8207::1%eth0").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("2408:8207::1%';id;'").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("2408:8207::1%\$(touch /tmp/pwn)").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("[2408:8207::1%rmnet0]").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("[2408:8207::1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("[1.1.1.1]").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("::ffff:192.168.1.1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("::ffff:10.0.0.1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("::ffff:172.16.0.1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("::ffff:100.64.0.1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("::ffff:169.254.1.1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("0000:0000:0000:0000:0000:ffff:c0a8:0101").isFailure)
        // Non-routable special-purpose IPv6 prefixes
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("::192.168.1.1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("::127.0.0.1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("::10.0.0.1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("::100.64.0.1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("::169.254.1.1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("::7f00:1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("2001:db8::1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("100::1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("2001:0::1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("2001:2::1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("2001:10::1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("2001:20::1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("2002:c0a8:0101::1").isFailure)
        assertTrue(ReverseTracerouteCodec.validateClientPublicIp("2002:7f00:0001::1").isFailure)

        assertEquals("114.240.88.123", ReverseTracerouteCodec.validateClientPublicIp("114.240.88.123").getOrThrow())
        assertEquals("2408:8207:1234::1", ReverseTracerouteCodec.validateClientPublicIp("[2408:8207:1234::1]").getOrThrow())
        assertEquals("2001:4860:4860::8888", ReverseTracerouteCodec.validateClientPublicIp("2001:4860:4860::8888").getOrThrow())
        assertEquals(
            "2408:8207::1",
            ReverseTracerouteCodec.validateClientPublicIp("2408:8207:0000:0000:0000:0000:0000:0001").getOrThrow(),
        )
    }

    @Test
    fun `parses nexttrace partial stars mtr waiting for reply and tracepath hostname with IP`() {
        val nextTracePartialStars = """
            __ANODE_RTRACE_TOOL__=nexttrace
            1   10.0.0.1      AS137872  RFC1918   0.50 ms
            2   * * 59.43.138.1  12.3 ms
            3   * 114.240.88.123 10.1 ms *
        """.trimIndent()
        val ntSummary = ReverseTracerouteCodec.parseOutput(nextTracePartialStars, targetIp = "114.240.88.123")
        assertEquals(3, ntSummary.totalHops)
        assertEquals("59.43.138.1", ntSummary.hops[1].ip)
        assertEquals(12.3, ntSummary.hops[1].avgMs!!, 0.01)
        assertEquals(3, ntSummary.hops[1].sent)
        assertEquals(1, ntSummary.hops[1].received)
        assertTrue(BackboneRouteTag.CN2_GIA in ntSummary.hops[1].backboneTags)
        assertEquals("114.240.88.123", ntSummary.hops[2].ip)
        assertTrue(ntSummary.reachedTarget)

        val mtrWaitingOutput = """
            __ANODE_RTRACE_TOOL__=mtr
            HOST: vps                         Loss%   Snt   Last   Avg  Best  Wrst StDev
              1.|-- core.ctgnet.cn             0.0%     5   1.2   1.3   1.1   1.5   0.1
              2.|-- 59.43.138.1                0.0%     5  10.0  10.2   9.9  10.8   0.3
              3.|-- (waiting for reply)      100.0%     5   0.0   0.0   0.0   0.0   0.0
              4.|-- (waiting for reply)
              5.|-- 114.240.88.123             0.0%     5  120.5 121.0 119.8 122.4   0.9
        """.trimIndent()
        val mtrSummary = ReverseTracerouteCodec.parseOutput(mtrWaitingOutput, targetIp = "114.240.88.123")
        assertEquals(5, mtrSummary.totalHops)
        assertEquals("core.ctgnet.cn", mtrSummary.hops[0].hostname)
        assertEquals(null, mtrSummary.hops[2].ip)
        assertEquals(HopLossClassification.TIMEOUT_NO_REPLY, mtrSummary.hops[2].lossClassification)
        assertEquals(null, mtrSummary.hops[3].ip)
        assertEquals(HopLossClassification.TIMEOUT_NO_REPLY, mtrSummary.hops[3].lossClassification)
        assertEquals("114.240.88.123", mtrSummary.hops[4].ip)

        val tracepathWithHostname = """
            __ANODE_RTRACE_TOOL__=tracepath
             1:  gw.ctgnet.cn (59.43.138.1)  1.234ms
             2:  no reply
             3:  114.240.88.123 (114.240.88.123) 45.678ms reached
        """.trimIndent()
        val tpSummary = ReverseTracerouteCodec.parseOutput(tracepathWithHostname, targetIp = "114.240.88.123")
        assertEquals(3, tpSummary.totalHops)
        assertEquals("59.43.138.1", tpSummary.hops[0].ip)
        assertEquals("gw.ctgnet.cn", tpSummary.hops[0].hostname)
        assertEquals(1.234, tpSummary.hops[0].avgMs!!, 0.001)
        assertTrue(BackboneRouteTag.CN2_GIA in tpSummary.hops[0].backboneTags)
        assertTrue(tpSummary.reachedTarget)
    }

    @Test
    fun `parses nexttrace mtr traceroute and tracepath outputs accurately`() {
        val nextTraceOutput = """
            __ANODE_RTRACE_TOOL__=nexttrace
            1   10.0.0.1      AS137872  RFC1918                         0.45 ms
            2   59.43.244.1   AS4809    美国 洛杉矶  CTGNet             1.12 ms / 1.25 ms / 1.18 ms
            3   59.43.138.2   AS4809    中国 上海    CN2                132.40 ms / 133.10 ms / 132.80 ms
            4   114.240.88.123 AS4134   中国 北京    ChinaNet           145.20 ms / * / 146.10 ms
        """.trimIndent()
        val ntSummary = ReverseTracerouteCodec.parseOutput(
            plainOutput = nextTraceOutput,
            targetIp = "114.240.88.123",
            sourceLabel = "LAX-DMIT",
        )
        assertEquals(TracerouteDirection.REVERSE, ntSummary.direction)
        assertEquals("nexttrace", ntSummary.toolName)
        assertTrue(ntSummary.reachedTarget)
        assertEquals(4, ntSummary.totalHops)
        assertTrue(BackboneRouteTag.CN2_GIA in ntSummary.dominantBackboneTags)

        val mtrOutput = """
            __ANODE_RTRACE_TOOL__=mtr
            HOST: vps                         Loss%   Snt   Last   Avg  Best  Wrst StDev
              1.|-- 219.158.100.1              0.0%     5   10.1  10.2   9.8  10.9   0.4
              2.|-- 218.105.2.5               40.0%     5   20.1  21.0  19.5  22.1   1.0
              3.|-- ???                      100.0%     5    0.0   0.0   0.0   0.0   0.0
              4.|-- 114.240.88.123             0.0%     5  120.5 121.0 119.8 122.4   0.9
        """.trimIndent()
        val mtrSummary = ReverseTracerouteCodec.parseOutput(mtrOutput, targetIp = "114.240.88.123")
        assertEquals("mtr", mtrSummary.toolName)
        assertEquals(4, mtrSummary.totalHops)
        assertEquals(HopLossClassification.INTERMEDIATE_RATE_LIMIT, mtrSummary.hops[1].lossClassification)
        assertEquals(HopLossClassification.TIMEOUT_NO_REPLY, mtrSummary.hops[2].lossClassification)
        assertTrue(BackboneRouteTag.CU_9929 in mtrSummary.dominantBackboneTags)

        val tracerouteOutput = """
            __ANODE_RTRACE_TOOL__=traceroute
            traceroute to 114.240.88.123 (114.240.88.123), 25 hops max
             1  223.119.26.1  1.23 ms  1.45 ms  *
             2  * * *
             3  114.240.88.123  98.1 ms  99.2 ms  97.8 ms
        """.trimIndent()
        val trSummary = ReverseTracerouteCodec.parseOutput(tracerouteOutput, targetIp = "114.240.88.123")
        assertEquals("traceroute", trSummary.toolName)
        assertTrue(trSummary.reachedTarget)
        assertEquals(3, trSummary.totalHops)
        assertTrue(BackboneRouteTag.CMIN2 in trSummary.dominantBackboneTags)

        val tracepathOutput = """
            __ANODE_RTRACE_TOOL__=tracepath
             1:  210.130.1.1    0.412ms
             2:  no reply
             3:  114.240.88.123  42.100ms reached
        """.trimIndent()
        val tpSummary = ReverseTracerouteCodec.parseOutput(tracepathOutput, targetIp = "114.240.88.123")
        assertEquals("tracepath", tpSummary.toolName)
        assertTrue(tpSummary.reachedTarget)
        assertEquals(3, tpSummary.totalHops)
        assertTrue(BackboneRouteTag.IIJ in tpSummary.dominantBackboneTags)
    }

    @Test
    fun `LiteSshSessionManager runOneShotCommand is isolated from console session and handles timeout and mismatch`() = runTest {
        val vault = SshServerVault(
            vaultFile = File(tempFolder.root, "oneshot_vault_${System.nanoTime()}.json"),
            cipher = InMemoryTestCipher(),
            ioDispatcher = testDispatcher,
            clock = { 1_750_200_000_000L },
        )
        val server = vault.saveServer(
            SshServerDraft(
                alias = "TYO-Edge",
                host = "203.0.113.10",
                port = 22,
                username = "root",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "secret",
            ),
        )

        var shouldTimeout = false
        var shouldMismatch = false
        var fgsRunningCalls = 0
        val fgs = object : SshForegroundServiceController {
            override fun onSessionRunning(serverAlias: String, presetName: String, statusLine: String) {
                fgsRunningCalls++
            }
            override fun onSessionFinished() = Unit
        }
        val transport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                if (shouldMismatch) {
                    throw SshHostKeyMismatchException(
                        expectedFingerprint = "SHA256:old",
                        actualHostKey = HostKeyInfo(request.host, request.port, "ssh-ed25519", "SHA256:new"),
                        serverId = request.serverId,
                        serverAlias = request.alias,
                    )
                }
                if (shouldTimeout) {
                    delay(5_000L)
                }
                emit(SshStreamEvent.Connected)
                emit(SshStreamEvent.OutputChunk("\u001B[32m__ANODE_RTRACE_TOOL__=mtr\u001B[0m\n  1.|-- 59.43.138.1  0.0%  5  1.1 1.2 1.0 1.4 0.1\n"))
                emit(SshStreamEvent.Completed(exitCode = 0))
            }
        }

        val manager = LiteSshSessionManager(
            vaultRepository = vault,
            executor = LiteSshExecutor(transport = transport),
            externalScope = CoroutineScope(SupervisorJob() + testDispatcher),
            foregroundServiceController = fgs,
            enableElapsedTicker = false,
        )

        try {
            val ok = manager.runOneShotCommand(server, "echo hi").getOrThrow()
            assertEquals(0, ok.exitCode)
            assertEquals(SshConnectionMode.DIRECT_SSH, ok.connectionMode)
            assertFalse(ok.plainOutput.contains("\u001B["))
            assertTrue(ok.plainOutput.contains("59.43.138.1"))
            assertFalse(manager.uiState.value.isRunning)
            assertTrue(manager.uiState.value.sessionRuns.isEmpty())
            assertTrue(manager.uiState.value.recentRuns.isEmpty())
            assertEquals(0, fgsRunningCalls)

            shouldTimeout = true
            val timedOut = manager.runOneShotCommand(server, "sleep 10", timeoutMillis = 50L)
            assertTrue(timedOut.isFailure)

            shouldTimeout = false
            shouldMismatch = true
            val mismatch = manager.runOneShotCommand(server, "echo hi")
            assertTrue(mismatch.isFailure)
            assertTrue(mismatch.exceptionOrNull() is SshHostKeyMismatchException)
        } finally {
            manager.disposeOwnedScopeForTests()
        }
    }

    @Test
    fun `buildShellCommand includes multi-protocol fallback waterfall and valid POSIX syntax`() {
        val v4Cmd = ReverseTracerouteCodec.buildShellCommand("114.240.88.123")
        assertTrue(v4Cmd.contains("TARGET='114.240.88.123'"))
        assertTrue(v4Cmd.contains("has_hops()"))
        assertTrue(v4Cmd.contains("nexttrace -M"))
        assertTrue(v4Cmd.contains("nexttrace -T"))
        assertTrue(v4Cmd.contains("mtr -n -r"))
        assertTrue(v4Cmd.contains("mtr -T"))
        assertTrue(v4Cmd.contains("besttrace"))
        assertTrue(v4Cmd.contains("traceroute -I"))
        assertTrue(v4Cmd.contains("traceroute -T"))
        assertTrue(v4Cmd.contains("tracepath"))

        val v6Cmd = ReverseTracerouteCodec.buildShellCommand("2408:8207:1234::1")
        assertTrue(v6Cmd.contains("TARGET='2408:8207:1234::1'"))
        assertTrue(v6Cmd.contains("mtr -6"))
        assertTrue(v6Cmd.contains("traceroute6"))
        assertTrue(v6Cmd.contains("tracepath6"))

        // Validate POSIX sh syntax via `sh -n`
        for (cmd in listOf(v4Cmd, v6Cmd)) {
            val proc = ProcessBuilder("/bin/sh", "-n", "-c", cmd).redirectErrorStream(true).start()
            val stderr = proc.inputStream.bufferedReader().readText()
            assertEquals("POSIX sh -n failed: $stderr", 0, proc.waitFor())
        }

        // Extract and execute the generated `has_hops` function in real `/bin/sh` for v4 & v6
        fun extractHasHopsDef(script: String): String {
            val start = script.indexOf("has_hops() {")
            val end = script.indexOf("\n}", start)
            assertTrue("has_hops definition missing", start >= 0 && end > start)
            return script.substring(start, end + 2)
        }
        fun runHasHops(script: String, sample: String): Boolean {
            val fn = extractHasHopsDef(script)
            val testCmd = "$fn\nhas_hops \"\$1\""
            val proc = ProcessBuilder("/bin/sh", "-c", testCmd, "_", sample).start()
            return proc.waitFor() == 0
        }

        // IPv4 has_hops: accepts real hop lines, rejects timestamps/errors/stars
        assertTrue(runHasHops(v4Cmd, " 1  59.43.138.1  10.2 ms"))
        assertTrue(runHasHops(v4Cmd, "  1.|-- 59.43.138.1   0.0%   3   1.2"))
        assertTrue(runHasHops(v4Cmd, "  1. |-- 59.43.138.1   0.0%   3   1.2"))
        assertTrue(runHasHops(v4Cmd, " 1:  210.130.1.1  0.412ms"))
        assertTrue(runHasHops(v4Cmd, "1|59.43.138.1|1.2|*"))
        assertFalse(runHasHops(v4Cmd, "2026-11-27 12:34:56 Error"))
        assertFalse(runHasHops(v4Cmd, "12:34:56 Error"))
        assertFalse(runHasHops(v4Cmd, "1  12:34:56 Error"))
        assertFalse(runHasHops(v4Cmd, " 1  * * *"))
        assertFalse(runHasHops(v4Cmd, "  1.|-- ???  100.0%  3  0.0"))
        assertFalse(runHasHops(v4Cmd, "traceroute to 114.240.88.123 (114.240.88.123), 25 hops max"))

        // IPv6 has_hops: accepts compressed & full IPv6, rejects timestamps/errors/stars
        assertTrue(runHasHops(v6Cmd, "1  2408::1  10.2 ms"))
        assertTrue(runHasHops(v6Cmd, "  2.|-- 2001:4860:4860::8888  0.0%  3  5.1"))
        assertTrue(runHasHops(v6Cmd, " 3:  2402:4f00:0:0:0:0:0:1  8.0ms"))
        assertTrue(runHasHops(v6Cmd, "1|2408:8207::1|3.1|*"))
        assertFalse(runHasHops(v6Cmd, "2026-11-27 12:34:56 Error"))
        assertFalse(runHasHops(v6Cmd, "12:34:56 Error"))
        assertFalse(runHasHops(v6Cmd, "1  12:34:56 Error"))
        assertFalse(runHasHops(v6Cmd, " 1  * * *"))

        // Verify timeout exit-code guard skips redundant same-protocol flag retries
        assertTrue(v4Cmd.contains("is_tmo()"))
        assertTrue(v4Cmd.contains("! is_tmo"))
        assertTrue(v4Cmd.contains("-G 2"))
    }

    @Test
    fun `parses real multi-line nexttrace ASN star placeholder, raw pipe output, and trims trailing timeouts`() {
        val realNextTrace = """
            __ANODE_RTRACE_TOOL__=nexttrace
            NextTrace v1.3.7 2025-02-01T00:00:00Z
            [NextTrace API] preferred API IP - 45.88.195.154 - 2.34ms
            traceroute to 114.240.88.123, 25 hops max, 52 bytes payload, ICMP mode
            1   10.0.0.1        *   RFC1918
                                            0.31 ms / 0.28 ms / 0.25 ms
            2   59.43.244.1     AS4809  [CN2-BACKBONE]    美国 洛杉矶  chinatelecom.com.cn
                                            1.12 ms / 1.25 ms / 1.18 ms
            3   *
            4   59.43.138.2     AS4809  [CN2-BACKBONE]    中国 上海  chinatelecom.com.cn
                                            132.40 ms / 133.10 ms / 132.80 ms
            5   *
            6   *
            7   *
        """.trimIndent()
        val summary = ReverseTracerouteCodec.parseOutput(realNextTrace, targetIp = "114.240.88.123")
        // Trailing timeout hops 5..7 are trimmed; intermediate hop 3 stays intact
        assertEquals(4, summary.totalHops)
        assertEquals("10.0.0.1", summary.hops[0].ip)
        assertEquals(3, summary.hops[0].sent)
        assertEquals(3, summary.hops[0].received)
        assertEquals("ASN '*' placeholder must NOT count as packet loss", 0.0, summary.hops[0].lossPercent, 0.01)
        assertEquals(HopLossClassification.TIMEOUT_NO_REPLY, summary.hops[2].lossClassification)
        assertEquals("CN", summary.hops[3].countryCode)
        assertTrue(BackboneRouteTag.CN2_GIA in summary.dominantBackboneTags)

        val rawPipeNextTrace = """
            __ANODE_RTRACE_TOOL__=nexttrace
            1|10.0.0.1|0.42|*|*|RFC1918||||
            2|218.105.2.5|14.50|gw.cuii.cn|AS9929|中国|北京|北京||China Unicom
            3|114.240.88.123|18.20|*|AS4134|中国|北京|北京||China Telecom
        """.trimIndent()
        val rawSummary = ReverseTracerouteCodec.parseOutput(rawPipeNextTrace, targetIp = "114.240.88.123")
        assertEquals(3, rawSummary.totalHops)
        assertTrue(rawSummary.reachedTarget)
        assertEquals("AS9929", rawSummary.hops[1].asn)
        assertEquals("CN", rawSummary.hops[1].countryCode)
        assertTrue(BackboneRouteTag.CU_9929 in rawSummary.dominantBackboneTags)

        val mtrWithAsnAndSpace = """
            __ANODE_RTRACE_TOOL__=mtr
            HOST: vps                         Loss%   Snt   Last   Avg  Best  Wrst StDev
              1. |-- AS4809 59.43.138.1        0.0%     5   1.2   1.3   1.1   1.5   0.1
              2. |-- gw.example.cn (114.240.88.123) 0.0% 5 120.5 121.0 119.8 122.4   0.9
              3. |-- ???                      100.0%    5   0.0   0.0   0.0   0.0   0.0
              4. |-- ???                      100.0%    5   0.0   0.0   0.0   0.0   0.0
        """.trimIndent()
        val mtrSummary = ReverseTracerouteCodec.parseOutput(mtrWithAsnAndSpace, targetIp = "114.240.88.123")
        assertEquals(2, mtrSummary.totalHops)
        assertEquals("59.43.138.1", mtrSummary.hops[0].ip)
        assertEquals("114.240.88.123", mtrSummary.hops[1].ip)
        assertEquals("gw.example.cn", mtrSummary.hops[1].hostname)
        assertTrue(mtrSummary.reachedTarget)
    }
}
