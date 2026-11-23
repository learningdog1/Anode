package com.nodeseek.app.netbench

import android.os.Build
import com.nodeseek.app.data.netbench.AndroidOsIcmpKernelDatagramOps
import com.nodeseek.app.data.netbench.IcmpEchoPacketCodec
import com.nodeseek.app.data.netbench.IcmpKernelDatagramOps
import com.nodeseek.app.data.netbench.IcmpPingParser
import com.nodeseek.app.data.netbench.IcmpPingRunner
import com.nodeseek.app.data.netbench.IcmpPosixException
import com.nodeseek.app.data.netbench.IcmpPosixSyscalls
import com.nodeseek.app.data.netbench.IcmpRawDatagram
import com.nodeseek.app.data.netbench.KernelBoundIcmpDirectProber
import com.nodeseek.app.data.netbench.NetBenchNetworkBridge
import com.nodeseek.app.data.netbench.NetBenchSocketChannel
import com.nodeseek.app.data.netbench.NetBenchTargetResolver
import com.nodeseek.app.data.netbench.PingExecutionEvent
import com.nodeseek.app.data.netbench.PingProcessExecutor
import com.nodeseek.app.data.netbench.PingProcessRequest
import com.nodeseek.app.data.netbench.PosixErrnoTable
import com.nodeseek.app.data.netbench.ResolvedNetworkBinding
import com.nodeseek.app.data.netbench.SystemPingProcessExecutor
import com.nodeseek.app.domain.netbench.IcmpNetworkBindingMode
import com.nodeseek.app.domain.netbench.IcmpPingConfig
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.NetBenchNetworkSelector
import com.nodeseek.app.domain.netbench.NetBenchTransportKind
import com.nodeseek.app.domain.netbench.NetworkDescriptor
import com.nodeseek.app.domain.netbench.PreferredIpFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileDescriptor
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.math.sqrt

class IcmpPingRunnerTest {

    @Test
    fun `buildCommand produces expected flags for IPv4 IPv6 and interface binding`() {
        val v4Cmd = IcmpPingParser.buildCommand(
            targetIp = "1.1.1.1",
            ipFamily = IpFamily.IPV4,
            count = 10,
            intervalMs = 250L,
            timeoutSec = 2,
        )
        assertEquals(
            listOf("/system/bin/ping", "-n", "-c", "10", "-i", "0.25", "-W", "2", "1.1.1.1"),
            v4Cmd,
        )

        val v6Ping6Cmd = IcmpPingParser.buildCommand(
            targetIp = "2606:4700:4700::1111",
            ipFamily = IpFamily.IPV6,
            count = 6,
            intervalMs = 1000L,
            timeoutSec = 3,
            interfaceName = "wlan0",
            preferPing6Binary = true,
        )
        assertEquals(
            listOf("/system/bin/ping6", "-n", "-c", "6", "-i", "1", "-W", "3", "-I", "wlan0", "2606:4700:4700::1111"),
            v6Ping6Cmd,
        )

        val v6Dash6Cmd = IcmpPingParser.buildCommand(
            targetIp = "2606:4700:4700::1111",
            ipFamily = IpFamily.IPV6,
            count = 4,
            intervalMs = 500L,
            timeoutSec = 2,
            preferPing6Binary = false,
        )
        assertEquals(
            listOf("/system/bin/ping", "-6", "-n", "-c", "4", "-i", "0.5", "-W", "2", "2606:4700:4700::1111"),
            v6Dash6Cmd,
        )
    }

    @Test
    fun `pure parser handles iputils toybox busybox IPv6 and timeout lines`() {
        // Header & divider lines must be ignored
        assertNull(IcmpPingParser.parseLine("PING 1.1.1.1 (1.1.1.1) 56(84) bytes of data."))
        assertNull(IcmpPingParser.parseLine("--- 1.1.1.1 ping statistics ---"))
        assertNull(IcmpPingParser.parseLine("10 packets transmitted, 10 received, 0% packet loss, time 2256ms"))

        val v4 = IcmpPingParser.parseLine("64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=12.3 ms")
        assertNotNull(v4)
        assertTrue(v4!!.success)
        assertEquals(1, v4.seq)
        assertEquals(57, v4.ttl)
        assertEquals(12.3, v4.rttMs!!, 0.001)

        val v6Toybox = IcmpPingParser.parseLine("64 bytes from 2606:4700:4700::1111, icmp_seq=2, hlim=56, time=18.9 ms")
        assertNotNull(v6Toybox)
        assertTrue(v6Toybox!!.success)
        assertEquals(2, v6Toybox.seq)
        assertEquals(56, v6Toybox.ttl)
        assertEquals(18.9, v6Toybox.rttMs!!, 0.001)

        val busyboxZeroSeq = IcmpPingParser.parseLine("64 bytes from 127.0.0.1: seq=0 ttl=64 time<1 ms")
        assertNotNull(busyboxZeroSeq)
        assertTrue(busyboxZeroSeq!!.success)
        assertEquals(0, busyboxZeroSeq.seq)
        assertEquals(64, busyboxZeroSeq.ttl)
        assertEquals(0.5, busyboxZeroSeq.rttMs!!, 0.001)

        val timeout = IcmpPingParser.parseLine("Request timeout for icmp_seq 3")
        assertNotNull(timeout)
        assertFalse(timeout!!.success)
        assertEquals(3, timeout.seq)

        val noAnswer = IcmpPingParser.parseLine("no answer yet for icmp_seq=4")
        assertNotNull(noAnswer)
        assertFalse(noAnswer!!.success)
        assertEquals(4, noAnswer.seq)

        val icmpErr = IcmpPingParser.parseLine("From 192.168.1.1 icmp_seq=5 Destination Host Unreachable")
        assertNotNull(icmpErr)
        assertFalse(icmpErr!!.success)
        assertEquals(5, icmpErr.seq)
        assertEquals("目标主机不可达", icmpErr.errorMessage)
    }

    @Test
    fun `pure summary parsers handle packet loss and rtt lines across implementations`() {
        val iputilsPkt = IcmpPingParser.parsePacketStatsLine("10 packets transmitted, 8 received, +2 errors, 20% packet loss, time 3456ms")
        assertNotNull(iputilsPkt)
        assertEquals(10, iputilsPkt!!.sent)
        assertEquals(8, iputilsPkt.received)
        assertEquals(20.0, iputilsPkt.lossPercent, 0.001)

        val bsdPkt = IcmpPingParser.parsePacketStatsLine("5 packets transmitted, 3 packets received, 40.0% packet loss")
        assertNotNull(bsdPkt)
        assertEquals(5, bsdPkt!!.sent)
        assertEquals(3, bsdPkt.received)
        assertEquals(40.0, bsdPkt.lossPercent, 0.001)

        val iputilsRtt = IcmpPingParser.parseRttStatsLine("rtt min/avg/max/mdev = 11.234/14.567/19.890/2.345 ms, pipe 2")
        assertNotNull(iputilsRtt)
        assertEquals(11.234, iputilsRtt!!.minMs, 0.001)
        assertEquals(14.567, iputilsRtt.avgMs, 0.001)
        assertEquals(19.890, iputilsRtt.maxMs, 0.001)
        assertEquals(2.345, iputilsRtt.mdevMs!!, 0.001)

        val toyboxRtt = IcmpPingParser.parseRttStatsLine("round-trip min/avg/max/stddev = 20.0/40.0/60.0/16.330 ms")
        assertNotNull(toyboxRtt)
        assertEquals(16.330, toyboxRtt!!.mdevMs!!, 0.001)

        val busyboxRttNoMdev = IcmpPingParser.parseRttStatsLine("round-trip min/avg/max = 20.0/40.0/60.0 ms")
        assertNotNull(busyboxRttNoMdev)
        assertNull(busyboxRttNoMdev!!.mdevMs)
    }

    @Test
    fun `parseOutput reconciles dropped seq gaps and computes mdev when omitted`() {
        val lines = listOf(
            "PING 45.12.34.56 (45.12.34.56) 56(84) bytes of data.",
            "64 bytes from 45.12.34.56: icmp_seq=1 ttl=53 time=20.0 ms",
            "64 bytes from 45.12.34.56: icmp_seq=3 ttl=53 time=40.0 ms",
            "64 bytes from 45.12.34.56: icmp_seq=5 ttl=53 time=60.0 ms",
            "--- 45.12.34.56 ping statistics ---",
            "5 packets transmitted, 3 received, 40% packet loss, time 2005ms",
            "round-trip min/avg/max = 20.0/40.0/60.0 ms",
        )

        val summary = IcmpPingParser.parseOutput(lines)
        assertEquals(5, summary.sent)
        assertEquals(3, summary.received)
        assertEquals(40.0, summary.lossPercent, 0.001)
        assertEquals(20.0, summary.minMs!!, 0.001)
        assertEquals(40.0, summary.avgMs!!, 0.001)
        assertEquals(60.0, summary.maxMs!!, 0.001)
        assertEquals(sqrt(800.0 / 3.0), summary.jitterMdevMs!!, 0.001)

        assertEquals(5, summary.samples.size)
        assertTrue(summary.samples[0].success)
        assertFalse(summary.samples[1].success)
        assertTrue(summary.samples[2].success)
        assertFalse(summary.samples[3].success)
        assertTrue(summary.samples[4].success)
    }

    @Test
    fun `runner streams live progress and relaxes fractional interval when ping rejects sub-second -i`() = runBlocking {
        val requests = mutableListOf<PingProcessRequest>()
        val executor = PingProcessExecutor { req ->
            requests += req
            flow {
                if ("-i" in req.command && req.command[req.command.indexOf("-i") + 1] == "0.25") {
                    emit(PingExecutionEvent.StderrLine("ping: cannot flood; minimal interval allowed for user is 1000ms"))
                    emit(PingExecutionEvent.Exited(2))
                } else {
                    emit(PingExecutionEvent.StdoutLine("PING 1.1.1.1 (1.1.1.1) 56(84) bytes of data."))
                    emit(PingExecutionEvent.StdoutLine("64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=10.0 ms"))
                    emit(PingExecutionEvent.StdoutLine("64 bytes from 1.1.1.1: icmp_seq=2 ttl=57 time=12.0 ms"))
                    emit(PingExecutionEvent.StdoutLine("64 bytes from 1.1.1.1: icmp_seq=3 ttl=57 time=14.0 ms"))
                    emit(PingExecutionEvent.StdoutLine("64 bytes from 1.1.1.1: icmp_seq=4 ttl=57 time=12.0 ms"))
                    emit(PingExecutionEvent.StdoutLine("4 packets transmitted, 4 received, 0% packet loss, time 3004ms"))
                    emit(PingExecutionEvent.StdoutLine("rtt min/avg/max/mdev = 10.000/12.000/14.000/1.414 ms"))
                    emit(PingExecutionEvent.Exited(0))
                }
            }
        }

        val runner = IcmpPingRunner(executor = executor, ioDispatcher = Dispatchers.IO)
        val emissions = runner.run(
            IcmpPingConfig(target = "1.1.1.1", probeCount = 4, intervalMs = 250L, timeoutMs = 2000),
        ).toList()

        assertEquals(2, requests.size)
        assertTrue(requests[0].command.contains("0.25"))
        assertTrue(requests[1].command.contains("1"))

        assertEquals(5, emissions.size)
        assertFalse(emissions[0].isFinished)
        assertEquals(1, emissions[0].samples.size)
        assertTrue(emissions.last().isFinished)

        val summary = emissions.last().summary
        assertEquals(4, summary.sent)
        assertEquals(4, summary.received)
        assertEquals(0.0, summary.lossPercent, 0.001)
        assertEquals(12.0, summary.avgMs!!, 0.001)
        assertEquals(1.414, summary.jitterMdevMs!!, 0.001)
        assertEquals(IcmpNetworkBindingMode.SYSTEM_DEFAULT, summary.bindingMode)
    }

    @Test
    fun `runner degrades cleanly when VPN is active and ping -I interface bind is rejected`() = runBlocking {
        val requests = mutableListOf<List<String>>()
        val directDnsCalls = AtomicInteger(0)

        val directChannel = fakeChannel(
            id = "wifi-101",
            label = "Wi-Fi",
            isVpn = false,
            onDns = {
                directDnsCalls.incrementAndGet()
                listOf(InetAddress.getByName("45.12.34.56"))
            },
        )
        val vpnChannel = fakeChannel(
            id = "vpn-200",
            label = "VPN 代理",
            isVpn = true,
            onDns = { listOf(InetAddress.getByName("198.18.0.5")) },
        )
        val bridge = object : NetBenchNetworkBridge {
            override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
                val plan = NetBenchNetworkSelector.selectNetworkPlan(
                    listOf(
                        NetworkDescriptor(vpnChannel, "vpn-200", isVpn = true, hasInternet = true, isActiveDefault = true, transports = setOf(NetBenchTransportKind.VPN), interfaceName = "tun0"),
                        NetworkDescriptor(directChannel, "wifi-101", isVpn = false, hasInternet = true, isValidated = true, transports = setOf(NetBenchTransportKind.WIFI), interfaceName = "wlan0"),
                    ),
                )
                return ResolvedNetworkBinding(plan, directChannel, vpnChannel)
            }
        }

        val executor = PingProcessExecutor { req ->
            requests += req.command
            flow {
                if ("-I" in req.command) {
                    emit(PingExecutionEvent.StderrLine("ping: setsockopt(SO_BINDTODEVICE): Operation not permitted"))
                    emit(PingExecutionEvent.Exited(2))
                } else {
                    emit(PingExecutionEvent.StdoutLine("64 bytes from 45.12.34.56: icmp_seq=1 ttl=53 time=35.0 ms"))
                    emit(PingExecutionEvent.StdoutLine("64 bytes from 45.12.34.56: icmp_seq=2 ttl=53 time=37.0 ms"))
                    emit(PingExecutionEvent.StdoutLine("64 bytes from 45.12.34.56: icmp_seq=3 ttl=53 time=39.0 ms"))
                    emit(PingExecutionEvent.StdoutLine("64 bytes from 45.12.34.56: icmp_seq=4 ttl=53 time=37.0 ms"))
                    emit(PingExecutionEvent.StdoutLine("4 packets transmitted, 4 received, 0% packet loss, time 755ms"))
                    emit(PingExecutionEvent.StdoutLine("rtt min/avg/max/mdev = 35.0/37.0/39.0/1.414 ms"))
                    emit(PingExecutionEvent.Exited(0))
                }
            }
        }

        val resolver = NetBenchTargetResolver(ioDispatcher = Dispatchers.IO, networkBridge = bridge)
        val runner = IcmpPingRunner(resolver = resolver, executor = executor, ioDispatcher = Dispatchers.IO, networkBridge = bridge)

        val emissions = runner.run(
            IcmpPingConfig(target = "vps.example.com", probeCount = 4, useProxy = false),
        ).toList()

        assertEquals(1, directDnsCalls.get())
        assertEquals(2, requests.size)
        assertTrue(requests[0].contains("-I"))
        assertTrue(requests[0].contains("wlan0"))
        assertFalse(requests[1].contains("-I"))
        assertEquals("45.12.34.56", requests[1].last())

        val finalSummary = emissions.last().summary
        assertEquals(4, finalSummary.received)
        assertEquals(IcmpNetworkBindingMode.UNBOUND_SUBPROCESS_FALLBACK, finalSummary.bindingMode)
        assertNotNull(finalSummary.networkBindingNote)
        assertTrue(finalSummary.networkBindingNote!!.contains("wlan0"))
        assertTrue(finalSummary.networkBindingNote!!.contains("TCPing"))
    }

    @Test
    fun `runner blocks when VPN is active without direct underlying network and handles IPv6 ping6 fallback`() = runBlocking {
        val vpnChannel = fakeChannel("vpn-200", "VPN 代理", isVpn = true) { listOf(InetAddress.getByName("127.0.0.1")) }
        val blockedBridge = object : NetBenchNetworkBridge {
            override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
                val plan = NetBenchNetworkSelector.selectNetworkPlan(
                    listOf(
                        NetworkDescriptor(vpnChannel, "vpn-200", isVpn = true, hasInternet = true, isActiveDefault = true, transports = setOf(NetBenchTransportKind.VPN)),
                    ),
                )
                return ResolvedNetworkBinding(plan, directChannel = null, proxyOrDefaultChannel = vpnChannel)
            }
        }

        var execCount = 0
        val blockedRunner = IcmpPingRunner(
            resolver = NetBenchTargetResolver(ioDispatcher = Dispatchers.IO, networkBridge = blockedBridge),
            executor = PingProcessExecutor {
                execCount++
                flow { emit(PingExecutionEvent.Exited(0)) }
            },
            ioDispatcher = Dispatchers.IO,
            networkBridge = blockedBridge,
        )
        val blockedResult = blockedRunner.run(IcmpPingConfig(target = "1.1.1.1", probeCount = 4, useProxy = false)).toList().last()
        assertEquals(0, execCount)
        assertNotNull(blockedResult.errorMessage)
        assertTrue(blockedResult.errorMessage!!.contains("经由代理测试"))

        // IPv6 ping6 fallback to ping -6
        val v6Commands = mutableListOf<List<String>>()
        val v6Executor = PingProcessExecutor { req ->
            v6Commands += req.command
            flow {
                if (req.command.first() == IcmpPingParser.DEFAULT_PING6_BINARY) {
                    emit(PingExecutionEvent.StderrLine("No such file or directory"))
                    emit(PingExecutionEvent.Exited(-1))
                } else {
                    emit(PingExecutionEvent.StdoutLine("64 bytes from 2606:4700:4700::1111: icmp_seq=1 ttl=57 time=20.0 ms"))
                    emit(PingExecutionEvent.StdoutLine("64 bytes from 2606:4700:4700::1111: icmp_seq=2 ttl=57 time=22.0 ms"))
                    emit(PingExecutionEvent.StdoutLine("64 bytes from 2606:4700:4700::1111: icmp_seq=3 ttl=57 time=24.0 ms"))
                    emit(PingExecutionEvent.StdoutLine("64 bytes from 2606:4700:4700::1111: icmp_seq=4 ttl=57 time=22.0 ms"))
                    emit(PingExecutionEvent.StdoutLine("4 packets transmitted, 4 received, 0% packet loss"))
                    emit(PingExecutionEvent.StdoutLine("rtt min/avg/max/mdev = 20.0/22.0/24.0/1.414 ms"))
                    emit(PingExecutionEvent.Exited(0))
                }
            }
        }
        val v6Runner = IcmpPingRunner(
            executor = v6Executor,
            ioDispatcher = Dispatchers.IO,
            ping6BinaryAvailable = { true },
        )
        val v6Result = v6Runner.run(
            IcmpPingConfig(target = "2606:4700:4700::1111", probeCount = 4, preferredFamily = PreferredIpFamily.IPV6_ONLY),
        ).toList().last()

        assertEquals(2, v6Commands.size)
        assertEquals(IcmpPingParser.DEFAULT_PING6_BINARY, v6Commands[0].first())
        assertEquals(IcmpPingParser.DEFAULT_PING_BINARY, v6Commands[1].first())
        assertTrue(v6Commands[1].contains("-6"))
        assertEquals(4, v6Result.summary.received)
        assertEquals(IpFamily.IPV6, v6Result.resolvedTarget?.ipFamily)
    }

    @Test
    fun `runner surfaces clear errors for 100 percent loss and permission denied without redundant retries`() = runBlocking {
        val lossCalls = AtomicInteger(0)
        val lossRunner = IcmpPingRunner(
            executor = PingProcessExecutor {
                lossCalls.incrementAndGet()
                flow {
                    emit(PingExecutionEvent.StdoutLine("PING 1.1.1.1 (1.1.1.1) 56(84) bytes of data."))
                    emit(PingExecutionEvent.StdoutLine("4 packets transmitted, 0 received, 100% packet loss, time 3000ms"))
                    emit(PingExecutionEvent.Exited(1))
                }
            },
            ioDispatcher = Dispatchers.IO,
        )
        val lossResult = lossRunner.run(IcmpPingConfig(target = "1.1.1.1", probeCount = 4)).toList().last()
        assertEquals(1, lossCalls.get())
        assertEquals(4, lossResult.summary.sent)
        assertEquals(0, lossResult.summary.received)
        assertEquals(100.0, lossResult.summary.lossPercent, 0.001)
        assertEquals(4, lossResult.samples.size)
        assertTrue(lossResult.errorMessage!!.contains("100%"))

        val permRunner = IcmpPingRunner(
            executor = PingProcessExecutor {
                flow {
                    emit(PingExecutionEvent.StderrLine("ping: icmp open socket: Operation not permitted"))
                    emit(PingExecutionEvent.Exited(2))
                }
            },
            ioDispatcher = Dispatchers.IO,
        )
        val permResult = permRunner.run(IcmpPingConfig(target = "1.1.1.1", probeCount = 4)).toList().last()
        assertEquals(0, permResult.summary.sent)
        assertTrue(permResult.errorMessage!!.contains("TCPing"))
    }

    @Test
    fun `filters emulator DUP wrong data byte overflow RTTs and caps waveform to planned count`() = runBlocking {
        // Exact malformed iputils-s20121221 output pattern observed on Android emulator NAT
        val emulatorOutput = listOf(
            "PING 1.1.1.1 (1.1.1.1) 56(84) bytes of data.",
            "64 bytes from 1.1.1.1: icmp_seq=0 ttl=255 time=242151963561072 ms (DUP!)",
            "wrong data byte #16 should be 0x10 but was 0x36",
            "64 bytes from 1.1.1.1: icmp_seq=1 ttl=255 time=20.0 ms",
            "64 bytes from 1.1.1.1: icmp_seq=2 ttl=255 time=976923069293943 ms",
            "64 bytes from 1.1.1.1: icmp_seq=3 ttl=255 time=30.0 ms",
            "64 bytes from 1.1.1.1: icmp_seq=4 ttl=255 time=40.0 ms",
            "64 bytes from 1.1.1.1: icmp_seq=5 ttl=255 time=25.0 ms",
            "64 bytes from 1.1.1.1: icmp_seq=6 ttl=255 time=35.0 ms",
            "64 bytes from 1.1.1.1: icmp_seq=7 ttl=255 time=22.0 ms",
            "64 bytes from 1.1.1.1: icmp_seq=8 ttl=255 time=28.0 ms",
            "64 bytes from 1.1.1.1: icmp_seq=10 ttl=255 time=24.0 ms",
            "--- 1.1.1.1 ping statistics ---",
            "10 packets transmitted, 8 received, +2 duplicates, 20% packet loss, time 4514ms",
            "rtt min/avg/max/mdev = 0.000/24215196356279.303/242151963561072.068/-6325746227839160.-365 ms, pipe 11",
        )

        // Pure parser checks
        assertNull(IcmpPingParser.parseLine("64 bytes from 1.1.1.1: icmp_seq=0 ttl=255 time=242151963561072 ms (DUP!)"))
        assertNull(IcmpPingParser.parseLine("wrong data byte #16 should be 0x10 but was 0x36"))
        assertNull(
            IcmpPingParser.parseRttStatsLine(
                "rtt min/avg/max/mdev = 0.000/24215196356279.303/242151963561072.068/-6325746227839160.-365 ms, pipe 11",
            ),
        )

        val executor = PingProcessExecutor {
            flow {
                emulatorOutput.forEach { emit(PingExecutionEvent.StdoutLine(it)) }
                emit(PingExecutionEvent.Exited(1))
            }
        }
        val runner = IcmpPingRunner(executor = executor, ioDispatcher = Dispatchers.IO)
        val emissions = runner.run(IcmpPingConfig(target = "1.1.1.1", probeCount = 10)).toList()
        val finalProgress = emissions.last()
        val summary = finalProgress.summary

        assertTrue(finalProgress.isFinished)
        assertEquals("Waveform samples must not exceed 10 (no 11/10 overflow)", 10, summary.samples.size)
        assertEquals(10, summary.sent)
        assertEquals(8, summary.received)
        assertEquals(20.0, summary.lossPercent, 0.001)
        // Valid RTTs are [20, 30, 40, 25, 35, 22, 28, 24] -> min=20, max=40, avg=28.0
        assertEquals(20.0, summary.minMs!!, 0.001)
        assertEquals(28.0, summary.avgMs!!, 0.001)
        assertEquals(40.0, summary.maxMs!!, 0.001)
        assertTrue(summary.jitterMdevMs!! < 10.0)
        // seq=2 had corrupted RTT (received, rttMs=null)
        assertTrue(summary.samples[1].success)
        assertNull(summary.samples[1].rttMs)
        // seq=9 was genuinely lost (success=false)
        assertFalse(summary.samples[8].success)
    }

    @Test
    fun `system process executor and runner survive watchdog force-close and stream IOException cleanly`() = runBlocking {
        // Real subprocess that outputs one packet then stalls past the 1s watchdog timeout
        val events = SystemPingProcessExecutor.execute(
            PingProcessRequest(
                command = listOf("sh", "-c", "echo '64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=12.3 ms'; sleep 5"),
                timeoutMs = 1_000L,
            ),
        ).toList()

        assertTrue(events.any { it is PingExecutionEvent.StdoutLine && it.line.contains("12.3 ms") })
        assertTrue(events.any { it is PingExecutionEvent.Exited })

        // Runner also degrades cleanly if executor throws IOException mid-stream after receiving samples
        val throwingExecutor = PingProcessExecutor {
            flow {
                emit(PingExecutionEvent.StdoutLine("64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=15.0 ms"))
                throw IOException("read interrupted by close() on another thread")
            }
        }
        val runner = IcmpPingRunner(executor = throwingExecutor, ioDispatcher = Dispatchers.IO)
        val result = runner.run(IcmpPingConfig(target = "1.1.1.1", probeCount = 4)).toList().last()
        assertTrue(result.isFinished)
        assertEquals(15.0, result.summary.avgMs!!, 0.001)

        // If executor throws IOException before any sample arrives, runner emits finished error rather than throwing
        val immediateThrowExecutor = PingProcessExecutor {
            flow {
                throw IOException("read interrupted by close() on another thread")
            }
        }
        val failRunner = IcmpPingRunner(executor = immediateThrowExecutor, ioDispatcher = Dispatchers.IO)
        val failResult = failRunner.run(IcmpPingConfig(target = "1.1.1.1", probeCount = 4)).toList().last()
        assertTrue(failResult.isFinished)
        assertNotNull(failResult.errorMessage)
    }

    @Test
    fun `IcmpEchoPacketCodec encodes valid ICMPv4 and ICMPv6 Echo Requests verifies checksums decodes replies and extracts TTL`() {
        val v4Req = IcmpEchoPacketCodec.encodeEchoRequest(
            ipFamily = IpFamily.IPV4,
            identifier = 0xBEEF,
            sequence = 0x8005,
            payloadSize = 56,
            timestampNanos = 123_456_789L,
        )
        assertEquals(64, v4Req.size)
        assertEquals(IcmpEchoPacketCodec.ICMPV4_ECHO_REQUEST_TYPE, v4Req[0].toInt() and 0xFF)
        assertEquals(0, v4Req[1].toInt() and 0xFF)
        assertTrue("ICMPv4 RFC 1071 checksum must validate", IcmpEchoPacketCodec.verifyIpv4Checksum(v4Req))

        // Corrupting a payload byte must invalidate RFC 1071 checksum
        val corrupted = v4Req.copyOf().also { it[16] = (it[16].toInt() xor 0x55).toByte() }
        assertFalse(IcmpEchoPacketCodec.verifyIpv4Checksum(corrupted))

        // Echo Request (Type=8) must NOT be accepted as an Echo Reply
        assertNull(IcmpEchoPacketCodec.decodeEchoReply(v4Req, v4Req.size, IpFamily.IPV4))

        // Build matching IPv4 Echo Reply (Type=0, Code=0) without outer IP header (Linux SOCK_DGRAM format)
        val v4ReplyBytes = buildEchoReplyBytes(IpFamily.IPV4, identifier = 0xBEEF, sequence = 0x8005, payloadSize = 56)
        val v4Decoded = IcmpEchoPacketCodec.decodeEchoReply(v4ReplyBytes, v4ReplyBytes.size, IpFamily.IPV4)
        assertNotNull(v4Decoded)
        assertEquals(0xBEEF, v4Decoded!!.identifier)
        assertEquals(0x8005, v4Decoded.sequence)
        assertEquals(56, v4Decoded.payloadBytes)
        assertNull("IPv4 reply must not match IPv6 expectation", IcmpEchoPacketCodec.decodeEchoReply(v4ReplyBytes, v4ReplyBytes.size, IpFamily.IPV6))

        // ICMPv6 Echo Request (Type=128) & Echo Reply (Type=129)
        val v6Req = IcmpEchoPacketCodec.encodeEchoRequest(
            ipFamily = IpFamily.IPV6,
            identifier = 0x1234,
            sequence = 7,
            payloadSize = 32,
        )
        assertEquals(40, v6Req.size)
        assertEquals(IcmpEchoPacketCodec.ICMPV6_ECHO_REQUEST_TYPE, v6Req[0].toInt() and 0xFF)
        assertNull(IcmpEchoPacketCodec.decodeEchoReply(v6Req, v6Req.size, IpFamily.IPV6))

        val v6ReplyBytes = buildEchoReplyBytes(IpFamily.IPV6, identifier = 0x1234, sequence = 7, payloadSize = 32)
        val v6Decoded = IcmpEchoPacketCodec.decodeEchoReply(v6ReplyBytes, v6ReplyBytes.size, IpFamily.IPV6)
        assertNotNull(v6Decoded)
        assertEquals(IcmpEchoPacketCodec.ICMPV6_ECHO_REPLY_TYPE, v6Decoded!!.type)
        assertEquals(0x1234, v6Decoded.identifier)
        assertEquals(7, v6Decoded.sequence)
        assertEquals(32, v6Decoded.payloadBytes)

        // Reject truncated buffers and non-zero ICMP codes
        assertNull(IcmpEchoPacketCodec.decodeEchoReply(v4ReplyBytes, 7, IpFamily.IPV4))
        val nonZeroCode = v4ReplyBytes.copyOf().also { it[1] = 1 }
        assertNull(IcmpEchoPacketCodec.decodeEchoReply(nonZeroCode, nonZeroCode.size, IpFamily.IPV4))

        // Ancillary cmsg TTL extraction
        val le57 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(57).array()
        assertEquals(57, IcmpEchoPacketCodec.extractTtlFromCmsgData(le57, ByteOrder.LITTLE_ENDIAN))
        assertEquals(128, IcmpEchoPacketCodec.extractTtlFromCmsgData(byteArrayOf(128.toByte())))
        assertNull(IcmpEchoPacketCodec.extractTtlFromCmsgData(byteArrayOf(0)))
    }

    @Test
    fun `KernelBoundIcmpDirectProber binds FD to physical Network bypasses VPN subprocess filters stale packets and tracks loss`() = runBlocking {
        val kernelFd = FileDescriptor()
        val boundFds = mutableListOf<FileDescriptor>()
        val closedFds = mutableListOf<FileDescriptor>()
        val sentSeqs = mutableListOf<Int>()
        val subprocessCalls = AtomicInteger(0)

        val directWifiChannel = object : NetBenchSocketChannel {
            override val networkId: String = "wifi-101"
            override val transportLabel: String = "Wi-Fi"
            override val isVpn: Boolean = false
            override val isDirectPhysical: Boolean = true
            override val bypassSystemHttpProxy: Boolean = true
            override val supportsFileDescriptorBind: Boolean = true
            override val socketFactory: SocketFactory = SocketFactory.getDefault()
            override fun createSocket(): Socket = Socket()
            override fun bindDatagramFileDescriptor(fd: FileDescriptor) {
                assertTrue("Network.bindSocket(fd) must run before any ICMP packet is sent", sentSeqs.isEmpty())
                boundFds += fd
            }
            override fun resolveAllByName(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByName("45.12.34.56"))
        }
        val vpnChannel = fakeChannel("vpn-200", "VPN 代理", isVpn = true) {
            listOf(InetAddress.getByName("198.18.0.9"))
        }
        val bridge = object : NetBenchNetworkBridge {
            override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
                val plan = NetBenchNetworkSelector.selectNetworkPlan(
                    listOf(
                        NetworkDescriptor(vpnChannel, "vpn-200", isVpn = true, hasInternet = true, isActiveDefault = true, transports = setOf(NetBenchTransportKind.VPN), interfaceName = "tun0"),
                        NetworkDescriptor(directWifiChannel, "wifi-101", isVpn = false, hasInternet = true, isValidated = true, transports = setOf(NetBenchTransportKind.WIFI), interfaceName = "wlan0"),
                    ),
                )
                return ResolvedNetworkBinding(plan, directWifiChannel, vpnChannel)
            }
        }

        // Scripted kernel socket ops:
        // seq=1 -> normal reply (TTL 56)
        // seq=2 -> first delivers unrelated Echo Request (Type=8), then stale seq=1 reply, then valid seq=2 reply (TTL 55)
        // seq=3 -> timeout (packet lost)
        // seq=4 -> normal reply (TTL 56)
        val rxQueue = ArrayDeque<Pair<ByteArray, Int?>>()
        val fakeOps = object : IcmpKernelDatagramOps {
            override fun openIcmpDatagramSocket(ipFamily: IpFamily): FileDescriptor {
                assertEquals(IpFamily.IPV4, ipFamily)
                return kernelFd
            }

            override fun enableReceiveTtlBestEffort(fd: FileDescriptor, ipFamily: IpFamily): Boolean = true

            override fun sendTo(fd: FileDescriptor, packet: ByteArray, address: InetAddress) {
                assertSame(kernelFd, fd)
                assertEquals("45.12.34.56", address.hostAddress)
                assertTrue(IcmpEchoPacketCodec.verifyIpv4Checksum(packet))
                val seq = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)
                sentSeqs += seq
                rxQueue.clear()
                when (seq) {
                    1 -> rxQueue.addLast(buildEchoReplyBytes(IpFamily.IPV4, 0x1001, 1) to 56)
                    2 -> {
                        rxQueue.addLast(packet.copyOf() to 64) // Unrelated Type=8 Echo Request
                        rxQueue.addLast(buildEchoReplyBytes(IpFamily.IPV4, 0x1001, 1) to 56) // Late/stale seq=1
                        rxQueue.addLast(buildEchoReplyBytes(IpFamily.IPV4, 0x1001, 2) to 55) // Matching seq=2!
                    }
                    3 -> Unit // Dropped in transit -> poll times out
                    4 -> rxQueue.addLast(buildEchoReplyBytes(IpFamily.IPV4, 0x1001, 4) to 56)
                }
            }

            override fun pollReadable(fd: FileDescriptor, timeoutMs: Int): Boolean {
                if (rxQueue.isNotEmpty()) {
                    Thread.sleep(2L)
                    return true
                }
                Thread.sleep(timeoutMs.coerceAtMost(25).toLong())
                return false
            }

            override fun receivePacket(
                fd: FileDescriptor,
                buffer: ByteArray,
                ipFamily: IpFamily,
                tryReadTtl: Boolean,
            ): IcmpRawDatagram? {
                val (bytes, ttl) = rxQueue.pollFirst() ?: return null
                System.arraycopy(bytes, 0, buffer, 0, bytes.size)
                return IcmpRawDatagram(length = bytes.size, ttl = if (tryReadTtl) ttl else null)
            }

            override fun closeQuietly(fd: FileDescriptor) {
                closedFds += fd
            }
        }

        val runner = IcmpPingRunner(
            resolver = NetBenchTargetResolver(ioDispatcher = Dispatchers.IO, networkBridge = bridge),
            executor = PingProcessExecutor {
                subprocessCalls.incrementAndGet()
                flow { emit(PingExecutionEvent.Exited(0)) }
            },
            ioDispatcher = Dispatchers.IO,
            networkBridge = bridge,
            directProber = KernelBoundIcmpDirectProber(ops = fakeOps, pollSliceMs = 20L),
        )

        val emissions = runner.run(
            IcmpPingConfig(
                target = "hk.node.example.com",
                probeCount = 4,
                timeoutMs = 200,
                intervalMs = 200L,
                useProxy = false,
            ),
        ).toList()

        assertEquals("Subprocess /system/bin/ping must NOT be invoked when kernel bound prober runs", 0, subprocessCalls.get())
        assertEquals(1, boundFds.size)
        assertSame(kernelFd, boundFds.single())
        assertEquals(1, closedFds.size)
        assertSame(kernelFd, closedFds.single())
        assertEquals(listOf(1, 2, 3, 4), sentSeqs)

        // 4 live emissions + 1 final summary emission
        assertEquals(5, emissions.size)
        assertFalse(emissions[0].isFinished)
        assertTrue(emissions.last().isFinished)

        val summary = emissions.last().summary
        assertEquals(IcmpNetworkBindingMode.PHYSICAL_NETWORK_BOUND, summary.bindingMode)
        assertEquals("wlan0", summary.boundInterfaceName)
        assertNull(summary.networkBindingNote)
        assertNull(summary.errorMessage)
        assertEquals(4, summary.sent)
        assertEquals(3, summary.received)
        assertEquals(25.0, summary.lossPercent, 0.001)
        assertNotNull(summary.avgMs)
        assertTrue(summary.samples[0].success)
        assertEquals(56, summary.samples[0].ttl)
        assertTrue(summary.samples[1].success)
        assertEquals(55, summary.samples[1].ttl)
        assertFalse(summary.samples[2].success)
        assertTrue(summary.samples[3].success)
    }

    @Test
    fun `multi-stage fallback chain degrades from kernel socket to ping -I and blocks tun0 leak under strict VPN lockdown`() = runBlocking {
        val directChannel = object : NetBenchSocketChannel {
            override val networkId: String = "wifi-101"
            override val transportLabel: String = "Wi-Fi"
            override val isVpn: Boolean = false
            override val isDirectPhysical: Boolean = true
            override val bypassSystemHttpProxy: Boolean = true
            override val supportsFileDescriptorBind: Boolean = true
            override val socketFactory: SocketFactory = SocketFactory.getDefault()
            override fun createSocket(): Socket = Socket()
            override fun bindDatagramFileDescriptor(fd: FileDescriptor) = Unit
            override fun resolveAllByName(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByName("45.12.34.56"))
        }
        val vpnChannel = fakeChannel("vpn-200", "VPN 代理", isVpn = true)
        val bridge = object : NetBenchNetworkBridge {
            override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
                val plan = NetBenchNetworkSelector.selectNetworkPlan(
                    listOf(
                        NetworkDescriptor(vpnChannel, "vpn-200", isVpn = true, hasInternet = true, isActiveDefault = true, transports = setOf(NetBenchTransportKind.VPN), interfaceName = "tun0"),
                        NetworkDescriptor(directChannel, "wifi-101", isVpn = false, hasInternet = true, isValidated = true, transports = setOf(NetBenchTransportKind.WIFI), interfaceName = "wlan0"),
                    ),
                )
                return ResolvedNetworkBinding(plan, directChannel, vpnChannel)
            }
        }

        // Case A: Kernel disables ping_group_range (openIcmpDatagramSocket throws) -> falls back to Stage 2 `ping -I wlan0`
        val disabledPingGroupOps = object : IcmpKernelDatagramOps {
            override fun openIcmpDatagramSocket(ipFamily: IpFamily): FileDescriptor =
                throw SocketException("socket failed: EACCES (ping_group_range disabled)")
            override fun enableReceiveTtlBestEffort(fd: FileDescriptor, ipFamily: IpFamily): Boolean = false
            override fun sendTo(fd: FileDescriptor, packet: ByteArray, address: InetAddress) = Unit
            override fun pollReadable(fd: FileDescriptor, timeoutMs: Int): Boolean = false
            override fun receivePacket(fd: FileDescriptor, buffer: ByteArray, ipFamily: IpFamily, tryReadTtl: Boolean): IcmpRawDatagram? = null
            override fun closeQuietly(fd: FileDescriptor) = Unit
        }
        val stage2Commands = mutableListOf<List<String>>()
        val stage2Runner = IcmpPingRunner(
            resolver = NetBenchTargetResolver(ioDispatcher = Dispatchers.IO, networkBridge = bridge),
            executor = PingProcessExecutor { req ->
                stage2Commands += req.command
                flow {
                    emit(PingExecutionEvent.StdoutLine("64 bytes from 45.12.34.56: icmp_seq=1 ttl=54 time=28.0 ms"))
                    emit(PingExecutionEvent.StdoutLine("64 bytes from 45.12.34.56: icmp_seq=2 ttl=54 time=30.0 ms"))
                    emit(PingExecutionEvent.StdoutLine("64 bytes from 45.12.34.56: icmp_seq=3 ttl=54 time=32.0 ms"))
                    emit(PingExecutionEvent.StdoutLine("64 bytes from 45.12.34.56: icmp_seq=4 ttl=54 time=30.0 ms"))
                    emit(PingExecutionEvent.StdoutLine("4 packets transmitted, 4 received, 0% packet loss"))
                    emit(PingExecutionEvent.Exited(0))
                }
            },
            ioDispatcher = Dispatchers.IO,
            networkBridge = bridge,
            directProber = KernelBoundIcmpDirectProber(ops = disabledPingGroupOps),
        )
        val stage2Final = stage2Runner.run(IcmpPingConfig(target = "45.12.34.56", probeCount = 4, useProxy = false)).toList().last()
        assertEquals(1, stage2Commands.size)
        assertTrue(stage2Commands.single().contains("-I"))
        assertTrue(stage2Commands.single().contains("wlan0"))
        assertEquals(IcmpNetworkBindingMode.INTERFACE_BOUND, stage2Final.summary.bindingMode)
        assertEquals("wlan0", stage2Final.summary.boundInterfaceName)

        // Case B: Strict VPN Lockdown (`Network.bindSocket(fd)` throws EPERM AND `ping -I wlan0` throws EPERM)
        // Must NOT fall back to unbound `ping` (which would hit VPN tun0 and fake 1ms RTT)!
        val lockdownChannel = object : NetBenchSocketChannel by directChannel {
            override fun bindDatagramFileDescriptor(fd: FileDescriptor) {
                throw SocketException("bindSocket failed: EPERM (Operation not permitted)")
            }
        }
        val lockdownBridge = object : NetBenchNetworkBridge {
            override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
                val plan = NetBenchNetworkSelector.selectNetworkPlan(
                    listOf(
                        NetworkDescriptor(vpnChannel, "vpn-200", isVpn = true, hasInternet = true, isActiveDefault = true, transports = setOf(NetBenchTransportKind.VPN), interfaceName = "tun0"),
                        NetworkDescriptor(lockdownChannel, "wifi-101", isVpn = false, hasInternet = true, isValidated = true, transports = setOf(NetBenchTransportKind.WIFI), interfaceName = "wlan0"),
                    ),
                )
                return ResolvedNetworkBinding(plan, lockdownChannel, vpnChannel)
            }
        }
        val lockdownSubprocessCmds = mutableListOf<List<String>>()
        val workingSocketOps = object : IcmpKernelDatagramOps by disabledPingGroupOps {
            override fun openIcmpDatagramSocket(ipFamily: IpFamily): FileDescriptor = FileDescriptor()
        }
        val lockdownRunner = IcmpPingRunner(
            resolver = NetBenchTargetResolver(ioDispatcher = Dispatchers.IO, networkBridge = lockdownBridge),
            executor = PingProcessExecutor { req ->
                lockdownSubprocessCmds += req.command
                flow {
                    if ("-I" in req.command) {
                        emit(PingExecutionEvent.StderrLine("ping: SO_BINDTODEVICE: Operation not permitted"))
                        emit(PingExecutionEvent.Exited(2))
                    } else {
                        // Simulated fake 1ms TUN reply that MUST NEVER be reached!
                        emit(PingExecutionEvent.StdoutLine("64 bytes from 45.12.34.56: icmp_seq=1 ttl=64 time=0.8 ms"))
                        emit(PingExecutionEvent.Exited(0))
                    }
                }
            },
            ioDispatcher = Dispatchers.IO,
            networkBridge = lockdownBridge,
            directProber = KernelBoundIcmpDirectProber(ops = workingSocketOps),
        )
        val lockdownFinal = lockdownRunner.run(IcmpPingConfig(target = "45.12.34.56", probeCount = 4, useProxy = false)).toList().last()
        assertEquals("Must only attempt -I wlan0 once and never run unbound tun0 ping", 1, lockdownSubprocessCmds.size)
        assertEquals(0, lockdownFinal.summary.received)
        assertTrue(lockdownFinal.errorMessage!!.contains("经由代理测试"))
    }

    @Test
    fun `KernelBoundIcmpDirectProber supports IPv6 and re-binds seamlessly on mid-test Wi-Fi to Cellular handoff`() = runBlocking {
        val wifiDropped = AtomicBoolean(false)
        val boundHistory = mutableListOf<String>()
        val openedFds = AtomicInteger(0)
        val closedFds = AtomicInteger(0)
        var pendingSeqReply: Int? = null

        val wifiChannel: NetBenchSocketChannel = object : NetBenchSocketChannel {
            override val networkId: String = "wifi-101"
            override val transportLabel: String = "Wi-Fi"
            override val isVpn: Boolean = false
            override val isDirectPhysical: Boolean = true
            override val bypassSystemHttpProxy: Boolean = true
            override val supportsFileDescriptorBind: Boolean = true
            override val socketFactory: SocketFactory = SocketFactory.getDefault()
            override fun createSocket(): Socket = Socket()
            override fun bindDatagramFileDescriptor(fd: FileDescriptor) {
                boundHistory += "wifi-101"
            }
            override fun resolveAllByName(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByName("2606:4700:4700::1111"))
        }
        val cellularChannel: NetBenchSocketChannel = object : NetBenchSocketChannel {
            override val networkId: String = "cell-102"
            override val transportLabel: String = "蜂窝移动"
            override val isVpn: Boolean = false
            override val isDirectPhysical: Boolean = true
            override val bypassSystemHttpProxy: Boolean = true
            override val supportsFileDescriptorBind: Boolean = true
            override val socketFactory: SocketFactory = SocketFactory.getDefault()
            override fun createSocket(): Socket = Socket()
            override fun bindDatagramFileDescriptor(fd: FileDescriptor) {
                boundHistory += "cell-102"
            }
            override fun resolveAllByName(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByName("2606:4700:4700::1111"))
        }
        val vpnChannel = fakeChannel("vpn-200", "VPN 代理", isVpn = true)

        val bridge = object : NetBenchNetworkBridge {
            override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
                val physical = if (wifiDropped.get()) {
                    NetworkDescriptor(cellularChannel, "cell-102", isVpn = false, hasInternet = true, isValidated = true, transports = setOf(NetBenchTransportKind.CELLULAR), interfaceName = "rmnet_data0")
                } else {
                    NetworkDescriptor(wifiChannel, "wifi-101", isVpn = false, hasInternet = true, isValidated = true, transports = setOf(NetBenchTransportKind.WIFI), interfaceName = "wlan0")
                }
                val plan = NetBenchNetworkSelector.selectNetworkPlan(
                    listOf(
                        NetworkDescriptor(vpnChannel, "vpn-200", isVpn = true, hasInternet = true, isActiveDefault = true, transports = setOf(NetBenchTransportKind.VPN), interfaceName = "tun0"),
                        physical,
                    ),
                )
                return ResolvedNetworkBinding(plan, physical.token, vpnChannel)
            }
        }

        val wifiFd = FileDescriptor()
        val cellFd = FileDescriptor()
        val handoffOps = object : IcmpKernelDatagramOps {
            override fun openIcmpDatagramSocket(ipFamily: IpFamily): FileDescriptor {
                assertEquals(IpFamily.IPV6, ipFamily)
                return if (openedFds.incrementAndGet() == 1) wifiFd else cellFd
            }

            override fun enableReceiveTtlBestEffort(fd: FileDescriptor, ipFamily: IpFamily): Boolean = true

            override fun sendTo(fd: FileDescriptor, packet: ByteArray, address: InetAddress) {
                val seq = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)
                if (seq == 2 && fd === wifiFd) {
                    wifiDropped.set(true)
                    throw SocketException("sendto failed: ENETUNREACH (Network is unreachable)")
                }
                pendingSeqReply = seq
            }

            override fun pollReadable(fd: FileDescriptor, timeoutMs: Int): Boolean = pendingSeqReply != null

            override fun receivePacket(fd: FileDescriptor, buffer: ByteArray, ipFamily: IpFamily, tryReadTtl: Boolean): IcmpRawDatagram? {
                val seq = pendingSeqReply ?: return null
                pendingSeqReply = null
                val reply = buildEchoReplyBytes(IpFamily.IPV6, 0x2222, seq)
                System.arraycopy(reply, 0, buffer, 0, reply.size)
                return IcmpRawDatagram(length = reply.size, ttl = 58)
            }

            override fun closeQuietly(fd: FileDescriptor) {
                closedFds.incrementAndGet()
            }
        }

        val runner = IcmpPingRunner(
            resolver = NetBenchTargetResolver(ioDispatcher = Dispatchers.IO, networkBridge = bridge),
            ioDispatcher = Dispatchers.IO,
            networkBridge = bridge,
            directProber = KernelBoundIcmpDirectProber(ops = handoffOps, pollSliceMs = 15L),
        )
        val result = runner.run(
            IcmpPingConfig(
                target = "v6.node.example.com",
                probeCount = 4,
                preferredFamily = PreferredIpFamily.IPV6_ONLY,
                useProxy = false,
            ),
        ).toList().last()

        assertEquals(listOf("wifi-101", "cell-102"), boundHistory)
        assertEquals(2, openedFds.get())
        assertEquals("Both Wi-Fi FD and Cellular FD must be closed without leaks", 2, closedFds.get())
        assertEquals(4, result.summary.received)
        assertEquals(IcmpNetworkBindingMode.PHYSICAL_NETWORK_BOUND, result.summary.bindingMode)
        assertEquals("rmnet_data0", result.summary.boundInterfaceName)
    }

    @Test
    fun `IcmpPingRunner never sends direct ICMP to Clash 198_18 Fake-IP and uses clean fallback IP when available`() = runBlocking {
        val pingedIps = mutableListOf<String>()
        val poisonedWifi: NetBenchSocketChannel = object : NetBenchSocketChannel {
            override val networkId: String = "wifi-101"
            override val transportLabel: String = "Wi-Fi"
            override val isVpn: Boolean = false
            override val isDirectPhysical: Boolean = true
            override val bypassSystemHttpProxy: Boolean = true
            override val supportsFileDescriptorBind: Boolean = true
            override val socketFactory: SocketFactory = SocketFactory.getDefault()
            override fun createSocket(): Socket = Socket()
            override fun bindDatagramFileDescriptor(fd: FileDescriptor) = Unit
            override fun resolveAllByName(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByName("198.18.0.108"))
        }
        val bridge = object : NetBenchNetworkBridge {
            override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
                val plan = NetBenchNetworkSelector.selectNetworkPlan(
                    listOf(
                        NetworkDescriptor(poisonedWifi, "wifi-101", isVpn = false, hasInternet = true, isValidated = true, transports = setOf(NetBenchTransportKind.WIFI), interfaceName = "wlan0"),
                    ),
                )
                return ResolvedNetworkBinding(plan, poisonedWifi, poisonedWifi)
            }
        }

        var lastSeq = 0
        val recordingOps = object : IcmpKernelDatagramOps {
            override fun openIcmpDatagramSocket(ipFamily: IpFamily): FileDescriptor = FileDescriptor()
            override fun enableReceiveTtlBestEffort(fd: FileDescriptor, ipFamily: IpFamily): Boolean = false
            override fun sendTo(fd: FileDescriptor, packet: ByteArray, address: InetAddress) {
                pingedIps += address.hostAddress.orEmpty()
                lastSeq = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)
            }
            override fun pollReadable(fd: FileDescriptor, timeoutMs: Int): Boolean = true
            override fun receivePacket(fd: FileDescriptor, buffer: ByteArray, ipFamily: IpFamily, tryReadTtl: Boolean): IcmpRawDatagram {
                val rep = buildEchoReplyBytes(IpFamily.IPV4, 1, lastSeq)
                System.arraycopy(rep, 0, buffer, 0, rep.size)
                return IcmpRawDatagram(rep.size, 52)
            }
            override fun closeQuietly(fd: FileDescriptor) = Unit
        }

        // 1. Without clean fallback: blocks 198.18.0.108 before any ICMP packet leaves
        val blockedRunner = IcmpPingRunner(
            resolver = NetBenchTargetResolver(
                ioDispatcher = Dispatchers.IO,
                networkBridge = bridge,
                cleanDnsFallback = { _, _, _ -> emptyList() },
            ),
            ioDispatcher = Dispatchers.IO,
            networkBridge = bridge,
            directProber = KernelBoundIcmpDirectProber(ops = recordingOps),
        )
        val blocked = blockedRunner.run(IcmpPingConfig(target = "jp.vps.example.com", probeCount = 4, useProxy = false)).toList().last()
        assertTrue(pingedIps.isEmpty())
        assertEquals(0, blocked.summary.sent)
        assertTrue(blocked.errorMessage!!.contains("198.18.0.108"))
        assertTrue(blocked.errorMessage!!.contains("Fake-IP"))

        // 2. With clean fallback: resolves 203.0.113.77 over physical channel and pings real IP
        val cleanRunner = IcmpPingRunner(
            resolver = NetBenchTargetResolver(
                ioDispatcher = Dispatchers.IO,
                networkBridge = bridge,
                cleanDnsFallback = { _, ch, _ ->
                    assertSame(poisonedWifi, ch)
                    listOf(InetAddress.getByName("203.0.113.77"))
                },
            ),
            ioDispatcher = Dispatchers.IO,
            networkBridge = bridge,
            directProber = KernelBoundIcmpDirectProber(ops = recordingOps),
        )
        val cleanFinal = cleanRunner.run(IcmpPingConfig(target = "jp.vps.example.com", probeCount = 4, useProxy = false)).toList().last()
        assertEquals(4, cleanFinal.summary.received)
        assertEquals("203.0.113.77", cleanFinal.resolvedTarget?.resolvedIp)
        assertTrue(pingedIps.all { it == "203.0.113.77" })
        assertEquals(IcmpNetworkBindingMode.PHYSICAL_NETWORK_BOUND, cleanFinal.summary.bindingMode)
    }

    @Test
    fun `non-blocking recvPacket surfaces POLLERR reachability errors immediately without blocking or spinning to timeout`() = runBlocking {
        // 1. Verify AndroidOsIcmpKernelDatagramOps.classifyRecvMsgErrno never falls back to recvfrom on sk_err network errors
        assertEquals(
            AndroidOsIcmpKernelDatagramOps.RecvMsgErrnoAction.RETURN_NULL,
            AndroidOsIcmpKernelDatagramOps.classifyRecvMsgErrno(errno = 11, eagain = 11, eintr = 4, einval = 22, eopnotsupp = 95, enosys = 38),
        )
        assertEquals(
            AndroidOsIcmpKernelDatagramOps.RecvMsgErrnoAction.FALLBACK_RECVFROM,
            AndroidOsIcmpKernelDatagramOps.classifyRecvMsgErrno(errno = 22, eagain = 11, eintr = 4, einval = 22, eopnotsupp = 95, enosys = 38),
        )
        // EHOSTUNREACH (113), ENETUNREACH (101), ECONNREFUSED (111), EPERM (1) must all RETHROW
        for (netErr in listOf(113, 101, 111, 1, 13)) {
            assertEquals(
                AndroidOsIcmpKernelDatagramOps.RecvMsgErrnoAction.RETHROW,
                AndroidOsIcmpKernelDatagramOps.classifyRecvMsgErrno(errno = netErr, eagain = 11, eintr = 4, einval = 22, eopnotsupp = 95, enosys = 38),
            )
        }

        // 2. Verify KernelBoundIcmpDirectProber immediately fails each probe on POLLERR async socket errors
        //    (without spinning out the 5_000ms timeout!) and survives spurious EAGAIN null reads cleanly.
        val directWifi: NetBenchSocketChannel = object : NetBenchSocketChannel {
            override val networkId: String = "wifi-101"
            override val transportLabel: String = "Wi-Fi"
            override val isVpn: Boolean = false
            override val isDirectPhysical: Boolean = true
            override val bypassSystemHttpProxy: Boolean = true
            override val supportsFileDescriptorBind: Boolean = true
            override val socketFactory: SocketFactory = SocketFactory.getDefault()
            override fun createSocket(): Socket = Socket()
            override fun bindDatagramFileDescriptor(fd: FileDescriptor) = Unit
            override fun resolveAllByName(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByName("203.0.113.10"))
        }
        val bridge = object : NetBenchNetworkBridge {
            override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
                val plan = NetBenchNetworkSelector.selectNetworkPlan(
                    listOf(
                        NetworkDescriptor(directWifi, "wifi-101", isVpn = false, hasInternet = true, isValidated = true, transports = setOf(NetBenchTransportKind.WIFI), interfaceName = "wlan0"),
                    ),
                )
                return ResolvedNetworkBinding(plan, directWifi, directWifi)
            }
        }

        var currentSeq = 0
        var seq4SpuriousEmptyRead = true
        val pollErrOps = object : IcmpKernelDatagramOps {
            override fun openIcmpDatagramSocket(ipFamily: IpFamily): FileDescriptor = FileDescriptor()
            override fun enableReceiveTtlBestEffort(fd: FileDescriptor, ipFamily: IpFamily): Boolean = true
            override fun sendTo(fd: FileDescriptor, packet: ByteArray, address: InetAddress) {
                currentSeq = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)
            }
            override fun pollReadable(fd: FileDescriptor, timeoutMs: Int): Boolean = true // POLLERR / POLLIN immediately ready
            override fun receivePacket(fd: FileDescriptor, buffer: ByteArray, ipFamily: IpFamily, tryReadTtl: Boolean): IcmpRawDatagram? {
                return when (currentSeq) {
                    1 -> throw SocketException("recvmsg failed: EHOSTUNREACH (No route to host)")
                    2 -> throw SocketException("recvmsg failed: ENETUNREACH (Network is unreachable)")
                    3 -> throw SocketException("recvmsg failed: ECONNREFUSED (Connection refused)")
                    else -> {
                        if (seq4SpuriousEmptyRead) {
                            // Simulates dropped bad-checksum packet where non-blocking recv returns EAGAIN -> null
                            seq4SpuriousEmptyRead = false
                            null
                        } else {
                            val reply = buildEchoReplyBytes(IpFamily.IPV4, 0x3333, 4)
                            System.arraycopy(reply, 0, buffer, 0, reply.size)
                            IcmpRawDatagram(reply.size, 59)
                        }
                    }
                }
            }
            override fun closeQuietly(fd: FileDescriptor) = Unit
        }

        val runner = IcmpPingRunner(
            resolver = NetBenchTargetResolver(ioDispatcher = Dispatchers.IO, networkBridge = bridge),
            ioDispatcher = Dispatchers.IO,
            networkBridge = bridge,
            directProber = KernelBoundIcmpDirectProber(ops = pollErrOps, pollSliceMs = 20L),
        )

        val startMs = System.currentTimeMillis()
        val finalProgress = runner.run(
            IcmpPingConfig(
                target = "203.0.113.10",
                probeCount = 4,
                timeoutMs = 5_000, // Huge timeout: if receive errors spun to timeout, 3 probes would take >15s!
                intervalMs = 200L,
                useProxy = false,
            ),
        ).toList().last()
        val elapsedMs = System.currentTimeMillis() - startMs

        assertTrue("POLLERR errors must return immediately instead of spinning to 5s timeout (took ${elapsedMs}ms)", elapsedMs < 2_500L)
        val samples = finalProgress.summary.samples
        assertEquals(4, samples.size)
        assertFalse(samples[0].success)
        assertEquals("目标主机不可达", samples[0].errorMessage)
        assertFalse(samples[1].success)
        assertEquals("底层网络不可达", samples[1].errorMessage)
        assertFalse(samples[2].success)
        assertEquals("目标拒绝或过滤 ICMP 请求", samples[2].errorMessage)
        assertTrue(samples[3].success)
        assertEquals(59, samples[3].ttl)
    }

    @Test
    fun `SDK 26 29 30 and 31 normal execution gates socket flags fcntlInt and recvmsg strictly by API level`() = runBlocking {
        for (sdk in listOf(
            Build.VERSION_CODES.O, // API 26
            Build.VERSION_CODES.Q, // API 29
            Build.VERSION_CODES.R, // API 30
            Build.VERSION_CODES.S, // API 31
        )) {
            val syscalls = RecordingSdkGatedSyscalls(simulatedSdkInt = sdk, simulatedTtl = 57)
            val ops = AndroidOsIcmpKernelDatagramOps(sdkInt = sdk, syscalls = syscalls)
            val bridge = singleDirectWifiBridge("203.0.113.88")
            val subprocessCalls = AtomicInteger(0)

            val runner = IcmpPingRunner(
                resolver = NetBenchTargetResolver(ioDispatcher = Dispatchers.IO, networkBridge = bridge),
                executor = PingProcessExecutor {
                    subprocessCalls.incrementAndGet()
                    flow { emit(PingExecutionEvent.Exited(0)) }
                },
                ioDispatcher = Dispatchers.IO,
                networkBridge = bridge,
                directProber = KernelBoundIcmpDirectProber(
                    ops = ops,
                    pollSliceMs = 15L,
                    sdkInt = sdk,
                ),
                sdkInt = sdk,
            )

            val result = runner.run(
                IcmpPingConfig(
                    target = "203.0.113.88",
                    probeCount = 4,
                    timeoutMs = 250,
                    intervalMs = 50L,
                    useProxy = false,
                ),
            ).toList().last()

            assertEquals("sdk=$sdk must use direct physical socket without subprocess fallback", 0, subprocessCalls.get())
            assertEquals("sdk=$sdk", IcmpNetworkBindingMode.PHYSICAL_NETWORK_BOUND, result.summary.bindingMode)
            assertEquals("sdk=$sdk", 4, result.summary.sent)
            assertEquals("sdk=$sdk", 4, result.summary.received)
            assertEquals("sdk=$sdk", 0.0, result.summary.lossPercent, 0.001)
            assertNotNull("sdk=$sdk must compute valid RTT", result.summary.avgMs)
            assertEquals("sdk=$sdk must close FD once", 1, syscalls.closedFds.size)

            when (sdk) {
                Build.VERSION_CODES.O -> {
                    assertEquals(1, syscalls.openDatagramCalls)
                    assertEquals(0, syscalls.openNonBlockingApi29Calls)
                    assertEquals(0, syscalls.setRcvTimeoApi29Calls)
                    assertEquals(0, syscalls.fcntlApi30Calls)
                    assertEquals(0, syscalls.setRecvTtlOptionCalls)
                    assertEquals(0, syscalls.recvMsgApi31Calls)
                    assertEquals(4, syscalls.recvFromCalls)
                    assertTrue(result.summary.samples.all { it.success && it.ttl == null })
                }

                Build.VERSION_CODES.Q -> {
                    assertEquals(0, syscalls.openDatagramCalls)
                    assertEquals(1, syscalls.openNonBlockingApi29Calls)
                    assertEquals(1, syscalls.setRcvTimeoApi29Calls)
                    assertEquals(0, syscalls.fcntlApi30Calls)
                    assertEquals(0, syscalls.setRecvTtlOptionCalls)
                    assertEquals(0, syscalls.recvMsgApi31Calls)
                    assertEquals(4, syscalls.recvFromCalls)
                    assertTrue(result.summary.samples.all { it.success && it.ttl == null })
                }

                Build.VERSION_CODES.R -> {
                    assertEquals(0, syscalls.openDatagramCalls)
                    assertEquals(1, syscalls.openNonBlockingApi29Calls)
                    assertEquals(0, syscalls.setRcvTimeoApi29Calls)
                    assertEquals(1, syscalls.fcntlApi30Calls)
                    assertEquals(0, syscalls.setRecvTtlOptionCalls)
                    assertEquals(0, syscalls.recvMsgApi31Calls)
                    assertEquals(4, syscalls.recvFromCalls)
                    assertTrue(result.summary.samples.all { it.success && it.ttl == null })

                    // Even if caller passes tryReadTtl = true directly on API 30, recvMsgApi31 must NEVER be invoked!
                    val extraBuf = ByteArray(128)
                    syscalls.enqueueReply(IpFamily.IPV4, seq = 99)
                    val pkt = ops.receivePacket(FileDescriptor(), extraBuf, IpFamily.IPV4, tryReadTtl = true)
                    assertNotNull(pkt)
                    assertNull(pkt!!.ttl)
                    assertEquals(0, syscalls.recvMsgApi31Calls)
                }

                Build.VERSION_CODES.S -> {
                    assertEquals(0, syscalls.openDatagramCalls)
                    assertEquals(1, syscalls.openNonBlockingApi29Calls)
                    assertEquals(0, syscalls.setRcvTimeoApi29Calls)
                    assertEquals(1, syscalls.fcntlApi30Calls)
                    assertEquals(1, syscalls.setRecvTtlOptionCalls)
                    assertEquals(4, syscalls.recvMsgApi31Calls)
                    assertEquals(0, syscalls.recvFromCalls)
                    assertTrue(result.summary.samples.all { it.success && it.ttl == 57 })
                }
            }
        }
    }

    @Test
    fun `SDK 26 29 30 and 31 fallback paths handle EINTR EINVAL EAGAIN and socket setup degradation cleanly`() = runBlocking {
        // 1. API 26 fallback: EINTR during pollReadable + MSG_DONTWAIT rejected with EINVAL -> falls back to flags=0 recvFrom
        val api26Syscalls = object : RecordingSdkGatedSyscalls(simulatedSdkInt = Build.VERSION_CODES.O, simulatedTtl = null) {
            private var pollAttempts = 0
            override fun pollReadableOnce(fd: FileDescriptor, timeoutMs: Int): Boolean {
                if (pollAttempts++ == 0) {
                    throw IcmpPosixException(PosixErrnoTable.LINUX_EINTR, "poll interrupted by signal")
                }
                return super.pollReadableOnce(fd, timeoutMs)
            }

            override fun recvFrom(fd: FileDescriptor, buffer: ByteArray, flags: Int): Int {
                if (flags == AndroidOsIcmpKernelDatagramOps.LINUX_MSG_DONTWAIT) {
                    throw IcmpPosixException(PosixErrnoTable.LINUX_EINVAL, "MSG_DONTWAIT unsupported")
                }
                assertEquals(0, flags)
                return super.recvFrom(fd, buffer, flags)
            }
        }
        val api26Ops = AndroidOsIcmpKernelDatagramOps(sdkInt = Build.VERSION_CODES.O, syscalls = api26Syscalls)
        val fd26 = api26Ops.openIcmpDatagramSocket(IpFamily.IPV4)
        assertFalse(api26Ops.enableReceiveTtlBestEffort(fd26, IpFamily.IPV4))
        api26Syscalls.enqueueReply(IpFamily.IPV4, seq = 1)
        assertTrue(api26Ops.pollReadable(fd26, timeoutMs = 100))
        val buf26 = ByteArray(128)
        val dgram26 = api26Ops.receivePacket(fd26, buf26, IpFamily.IPV4, tryReadTtl = false)
        assertNotNull(dgram26)
        assertNull(dgram26!!.ttl)
        api26Ops.closeQuietly(fd26)

        // 2. API 29 fallback: SOCK_NONBLOCK rejected with EINVAL -> falls back to openDatagramSocket + SO_RCVTIMEO
        val api29Syscalls = object : RecordingSdkGatedSyscalls(simulatedSdkInt = Build.VERSION_CODES.Q, simulatedTtl = null) {
            override fun openNonBlockingDatagramSocketApi29(ipFamily: IpFamily): FileDescriptor {
                super.openNonBlockingDatagramSocketApi29(ipFamily)
                throw IcmpPosixException(PosixErrnoTable.LINUX_EINVAL, "SOCK_NONBLOCK rejected")
            }
        }
        val api29Ops = AndroidOsIcmpKernelDatagramOps(sdkInt = Build.VERSION_CODES.Q, syscalls = api29Syscalls)
        val fd29 = api29Ops.openIcmpDatagramSocket(IpFamily.IPV6)
        assertEquals(1, api29Syscalls.openNonBlockingApi29Calls)
        assertEquals(1, api29Syscalls.openDatagramCalls)
        assertEquals(1, api29Syscalls.setRcvTimeoApi29Calls)
        assertEquals(0, api29Syscalls.fcntlApi30Calls)
        api29Ops.closeQuietly(fd29)

        // 3. API 30 fallback: SOCK_NONBLOCK rejected (EINVAL) AND fcntlInt rejected (EPERM) -> falls back to SO_RCVTIMEO without leaking FD
        val api30Syscalls = object : RecordingSdkGatedSyscalls(simulatedSdkInt = Build.VERSION_CODES.R, simulatedTtl = null) {
            override fun openNonBlockingDatagramSocketApi29(ipFamily: IpFamily): FileDescriptor {
                super.openNonBlockingDatagramSocketApi29(ipFamily)
                throw IcmpPosixException(PosixErrnoTable.LINUX_EINVAL, "SOCK_NONBLOCK rejected")
            }

            override fun ensureNonBlockingApi30(fd: FileDescriptor) {
                super.ensureNonBlockingApi30(fd)
                throw IcmpPosixException(PosixErrnoTable.LINUX_EPERM, "fcntl F_SETFL blocked by seccomp")
            }
        }
        val api30Ops = AndroidOsIcmpKernelDatagramOps(sdkInt = Build.VERSION_CODES.R, syscalls = api30Syscalls)
        val fd30 = api30Ops.openIcmpDatagramSocket(IpFamily.IPV4)
        assertEquals(1, api30Syscalls.openNonBlockingApi29Calls)
        assertEquals(1, api30Syscalls.openDatagramCalls)
        assertEquals(1, api30Syscalls.fcntlApi30Calls)
        assertEquals(1, api30Syscalls.setRcvTimeoApi29Calls)
        assertTrue("FD must remain open when fallback succeeds", api30Syscalls.closedFds.isEmpty())
        api30Ops.closeQuietly(fd30)
        assertEquals(1, api30Syscalls.closedFds.size)

        // 4. API 31 fallback:
        //    - seq 1: recvMsgWithTtlApi31 throws EAGAIN -> returns null without calling recvFrom, then succeeds on retry with HopLimit 61
        //    - seq 2: recvMsgWithTtlApi31 throws ENOSYS -> falls back cleanly to recvFrom (ttl = null)
        //    - seq 3: recvMsgWithTtlApi31 throws EHOSTUNREACH -> rethrows immediately and surfaces "目标主机不可达"
        var seq1FirstRead = true
        var activeSeq = 0
        val api31FallbackSyscalls = object : RecordingSdkGatedSyscalls(simulatedSdkInt = Build.VERSION_CODES.S, simulatedTtl = 61) {
            override fun sendTo(fd: FileDescriptor, packet: ByteArray, address: InetAddress) {
                activeSeq = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)
                super.sendTo(fd, packet, address)
            }

            override fun pollReadableOnce(fd: FileDescriptor, timeoutMs: Int): Boolean = true

            override fun recvMsgWithTtlApi31(
                fd: FileDescriptor,
                buffer: ByteArray,
                ipFamily: IpFamily,
                flags: Int,
            ): IcmpRawDatagram? {
                return when (activeSeq) {
                    1 -> {
                        if (seq1FirstRead) {
                            seq1FirstRead = false
                            recvMsgApi31Calls++
                            throw IcmpPosixException(PosixErrnoTable.LINUX_EAGAIN, "spurious wakeup")
                        }
                        super.recvMsgWithTtlApi31(fd, buffer, ipFamily, flags)
                    }
                    2 -> {
                        recvMsgApi31Calls++
                        throw IcmpPosixException(PosixErrnoTable.LINUX_ENOSYS, "recvmsg disabled")
                    }
                    3 -> {
                        recvMsgApi31Calls++
                        throw IcmpPosixException(PosixErrnoTable.LINUX_EINVAL, "cmsg control rejected")
                    }
                    else -> {
                        recvMsgApi31Calls++
                        throw IcmpPosixException(PosixErrnoTable.LINUX_EHOSTUNREACH, "host unreachable")
                    }
                }
            }
        }
        val v6Bridge = singleDirectWifiBridge("2606:4700:4700::1111")
        val api31Runner = IcmpPingRunner(
            resolver = NetBenchTargetResolver(ioDispatcher = Dispatchers.IO, networkBridge = v6Bridge),
            ioDispatcher = Dispatchers.IO,
            networkBridge = v6Bridge,
            directProber = KernelBoundIcmpDirectProber(
                ops = AndroidOsIcmpKernelDatagramOps(sdkInt = Build.VERSION_CODES.S, syscalls = api31FallbackSyscalls),
                pollSliceMs = 15L,
                sdkInt = Build.VERSION_CODES.S,
            ),
            sdkInt = Build.VERSION_CODES.S,
        )
        val api31Result = api31Runner.run(
            IcmpPingConfig(
                target = "2606:4700:4700::1111",
                probeCount = 4,
                timeoutMs = 250,
                intervalMs = 30L,
                preferredFamily = PreferredIpFamily.IPV6_ONLY,
                useProxy = false,
            ),
        ).toList().last()

        val s = api31Result.summary.samples
        assertEquals(4, s.size)
        assertTrue(s[0].success)
        assertEquals(61, s[0].ttl)
        assertTrue(s[1].success)
        assertNull("Fallback to Os.recvfrom on ENOSYS must succeed with null TTL", s[1].ttl)
        assertTrue(s[2].success)
        assertNull("Fallback to Os.recvfrom on EINVAL must succeed with null TTL", s[2].ttl)
        assertFalse(s[3].success)
        assertEquals("目标主机不可达", s[3].errorMessage)
        assertEquals("seq=2 ENOSYS and seq=3 EINVAL must fall back to recvFrom", 2, api31FallbackSyscalls.recvFromCalls)

        // 5. API 31 setsockoptInt(IP_RECVTTL) failure -> enableReceiveTtlBestEffort returns false and receivePacket uses recvFrom
        val api31NoTtlOptSyscalls = object : RecordingSdkGatedSyscalls(simulatedSdkInt = Build.VERSION_CODES.S, simulatedTtl = 57) {
            override fun setReceiveTtlOption(fd: FileDescriptor, ipFamily: IpFamily) {
                super.setReceiveTtlOption(fd, ipFamily)
                throw IcmpPosixException(PosixErrnoTable.LINUX_EOPNOTSUPP, "IP_RECVTTL unsupported")
            }
        }
        val api31NoTtlOps = AndroidOsIcmpKernelDatagramOps(sdkInt = Build.VERSION_CODES.S, syscalls = api31NoTtlOptSyscalls)
        val fd31NoTtl = api31NoTtlOps.openIcmpDatagramSocket(IpFamily.IPV4)
        assertFalse(api31NoTtlOps.enableReceiveTtlBestEffort(fd31NoTtl, IpFamily.IPV4))
        api31NoTtlOptSyscalls.enqueueReply(IpFamily.IPV4, seq = 1)
        val noTtlPkt = api31NoTtlOps.receivePacket(fd31NoTtl, ByteArray(128), IpFamily.IPV4, tryReadTtl = false)
        assertNotNull(noTtlPkt)
        assertNull(noTtlPkt!!.ttl)
        assertEquals(0, api31NoTtlOptSyscalls.recvMsgApi31Calls)
        assertEquals(1, api31NoTtlOptSyscalls.recvFromCalls)
        api31NoTtlOps.closeQuietly(fd31NoTtl)

        // 6. API 30 unexpected non-POSIX failure during socket setup -> closes FD immediately without leaking
        val fatalSetupSyscalls = object : RecordingSdkGatedSyscalls(simulatedSdkInt = Build.VERSION_CODES.R, simulatedTtl = null) {
            override fun ensureNonBlockingApi30(fd: FileDescriptor) {
                throw IllegalStateException("unexpected runtime failure")
            }
        }
        val fatalSetupOps = AndroidOsIcmpKernelDatagramOps(sdkInt = Build.VERSION_CODES.R, syscalls = fatalSetupSyscalls)
        val thrown = runCatching { fatalSetupOps.openIcmpDatagramSocket(IpFamily.IPV4) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals("Failed setup must close allocated FD", 1, fatalSetupSyscalls.closedFds.size)

        // 7. API 26 socket EACCES (ping_group_range disabled) -> degrades smoothly to /system/bin/ping subprocess with parsed TTL
        val api26DisabledSyscalls = object : RecordingSdkGatedSyscalls(simulatedSdkInt = Build.VERSION_CODES.O, simulatedTtl = null) {
            override fun openDatagramSocket(ipFamily: IpFamily): FileDescriptor {
                throw IcmpPosixException(PosixErrnoTable.LINUX_EACCES, "ping_group_range disabled")
            }
        }
        val v4Bridge = singleDirectWifiBridge("203.0.113.44")
        val api26SubprocessRunner = IcmpPingRunner(
            resolver = NetBenchTargetResolver(ioDispatcher = Dispatchers.IO, networkBridge = v4Bridge),
            executor = PingProcessExecutor {
                flow {
                    emit(PingExecutionEvent.StdoutLine("64 bytes from 203.0.113.44: icmp_seq=1 ttl=54 time=21.5 ms"))
                    emit(PingExecutionEvent.StdoutLine("64 bytes from 203.0.113.44: icmp_seq=2 ttl=54 time=23.5 ms"))
                    emit(PingExecutionEvent.StdoutLine("2 packets transmitted, 2 received, 0% packet loss"))
                    emit(PingExecutionEvent.Exited(0))
                }
            },
            ioDispatcher = Dispatchers.IO,
            networkBridge = v4Bridge,
            directProber = KernelBoundIcmpDirectProber(
                ops = AndroidOsIcmpKernelDatagramOps(sdkInt = Build.VERSION_CODES.O, syscalls = api26DisabledSyscalls),
                sdkInt = Build.VERSION_CODES.O,
            ),
            sdkInt = Build.VERSION_CODES.O,
        )
        val api26FallbackResult = api26SubprocessRunner.run(
            IcmpPingConfig(target = "203.0.113.44", probeCount = 2, useProxy = false),
        ).toList().last()
        assertEquals(2, api26FallbackResult.summary.received)
        assertEquals(54, api26FallbackResult.summary.samples.first().ttl)
    }

    private open class RecordingSdkGatedSyscalls(
        private val simulatedSdkInt: Int,
        private val simulatedTtl: Int?,
    ) : IcmpPosixSyscalls {
        var openDatagramCalls = 0
        var openNonBlockingApi29Calls = 0
        var setRcvTimeoApi29Calls = 0
        var fcntlApi30Calls = 0
        var setRecvTtlOptionCalls = 0
        var recvMsgApi31Calls = 0
        var recvFromCalls = 0
        val closedFds = mutableListOf<FileDescriptor>()
        private val rxFrames = ArrayDeque<ByteArray>()

        fun enqueueReply(ipFamily: IpFamily, seq: Int, id: Int = 0x4E50) {
            rxFrames.addLast(buildStaticEchoReply(ipFamily, id, seq))
        }

        override fun ensureRuntimeAvailable() = Unit

        override fun openDatagramSocket(ipFamily: IpFamily): FileDescriptor {
            openDatagramCalls++
            return FileDescriptor()
        }

        override fun openNonBlockingDatagramSocketApi29(ipFamily: IpFamily): FileDescriptor {
            if (simulatedSdkInt < Build.VERSION_CODES.Q) {
                throw AssertionError("API 29 SOCK_NONBLOCK called on sdkInt=$simulatedSdkInt")
            }
            openNonBlockingApi29Calls++
            return FileDescriptor()
        }

        override fun setReceiveTimeoutApi29(fd: FileDescriptor, timeoutMs: Long) {
            if (simulatedSdkInt < Build.VERSION_CODES.Q) {
                throw AssertionError("API 29 setsockoptTimeval called on sdkInt=$simulatedSdkInt")
            }
            setRcvTimeoApi29Calls++
        }

        override fun ensureNonBlockingApi30(fd: FileDescriptor) {
            if (simulatedSdkInt < Build.VERSION_CODES.R) {
                throw AssertionError("API 30 Os.fcntlInt called on sdkInt=$simulatedSdkInt")
            }
            fcntlApi30Calls++
        }

        override fun setReceiveTtlOption(fd: FileDescriptor, ipFamily: IpFamily) {
            if (simulatedSdkInt < Build.VERSION_CODES.O) {
                throw AssertionError("API 26 Os.setsockoptInt called on sdkInt=$simulatedSdkInt")
            }
            setRecvTtlOptionCalls++
        }

        override fun sendTo(fd: FileDescriptor, packet: ByteArray, address: InetAddress) {
            val ipFamily = if (address.address.size == 16) IpFamily.IPV6 else IpFamily.IPV4
            val id = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
            val seq = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)
            rxFrames.addLast(buildStaticEchoReply(ipFamily, id, seq))
        }

        override fun pollReadableOnce(fd: FileDescriptor, timeoutMs: Int): Boolean = rxFrames.isNotEmpty()

        override fun recvFrom(fd: FileDescriptor, buffer: ByteArray, flags: Int): Int {
            recvFromCalls++
            val frame = rxFrames.pollFirst() ?: throw IcmpPosixException(PosixErrnoTable.LINUX_EAGAIN, "EAGAIN")
            System.arraycopy(frame, 0, buffer, 0, frame.size)
            return frame.size
        }

        override fun recvMsgWithTtlApi31(
            fd: FileDescriptor,
            buffer: ByteArray,
            ipFamily: IpFamily,
            flags: Int,
        ): IcmpRawDatagram? {
            if (simulatedSdkInt < Build.VERSION_CODES.S) {
                throw AssertionError("API 31 Os.recvmsg / StructMsghdr / StructCmsghdr called on sdkInt=$simulatedSdkInt")
            }
            recvMsgApi31Calls++
            val frame = rxFrames.pollFirst() ?: throw IcmpPosixException(PosixErrnoTable.LINUX_EAGAIN, "EAGAIN")
            System.arraycopy(frame, 0, buffer, 0, frame.size)
            return IcmpRawDatagram(length = frame.size, ttl = simulatedTtl)
        }

        override fun closeQuietly(fd: FileDescriptor) {
            closedFds += fd
        }

        companion object {
            private fun buildStaticEchoReply(ipFamily: IpFamily, identifier: Int, sequence: Int): ByteArray {
                val bytes = ByteArray(IcmpEchoPacketCodec.ICMP_HEADER_BYTES + 56)
                bytes[0] = (
                    if (ipFamily == IpFamily.IPV6) {
                        IcmpEchoPacketCodec.ICMPV6_ECHO_REPLY_TYPE
                    } else {
                        IcmpEchoPacketCodec.ICMPV4_ECHO_REPLY_TYPE
                    }
                    ).toByte()
                bytes[1] = 0
                bytes[4] = ((identifier ushr 8) and 0xFF).toByte()
                bytes[5] = (identifier and 0xFF).toByte()
                bytes[6] = ((sequence ushr 8) and 0xFF).toByte()
                bytes[7] = (sequence and 0xFF).toByte()
                val csum = IcmpEchoPacketCodec.computeChecksum(bytes)
                bytes[2] = ((csum ushr 8) and 0xFF).toByte()
                bytes[3] = (csum and 0xFF).toByte()
                return bytes
            }
        }
    }

    private fun singleDirectWifiBridge(resolvedIp: String): NetBenchNetworkBridge {
        val directWifi: NetBenchSocketChannel = object : NetBenchSocketChannel {
            override val networkId: String = "wifi-101"
            override val transportLabel: String = "Wi-Fi"
            override val isVpn: Boolean = false
            override val isDirectPhysical: Boolean = true
            override val bypassSystemHttpProxy: Boolean = true
            override val supportsFileDescriptorBind: Boolean = true
            override val socketFactory: SocketFactory = SocketFactory.getDefault()
            override fun createSocket(): Socket = Socket()
            override fun bindDatagramFileDescriptor(fd: FileDescriptor) = Unit
            override fun resolveAllByName(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByName(resolvedIp))
        }
        return object : NetBenchNetworkBridge {
            override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
                val plan = NetBenchNetworkSelector.selectNetworkPlan(
                    listOf(
                        NetworkDescriptor(
                            token = directWifi,
                            networkId = "wifi-101",
                            isVpn = false,
                            hasInternet = true,
                            isValidated = true,
                            transports = setOf(NetBenchTransportKind.WIFI),
                            interfaceName = "wlan0",
                        ),
                    ),
                )
                return ResolvedNetworkBinding(plan, directWifi, directWifi)
            }
        }
    }

    private fun buildEchoReplyBytes(
        ipFamily: IpFamily,
        identifier: Int,
        sequence: Int,
        payloadSize: Int = 56,
    ): ByteArray {
        val bytes = ByteArray(IcmpEchoPacketCodec.ICMP_HEADER_BYTES + payloadSize)
        bytes[0] = (if (ipFamily == IpFamily.IPV6) IcmpEchoPacketCodec.ICMPV6_ECHO_REPLY_TYPE else IcmpEchoPacketCodec.ICMPV4_ECHO_REPLY_TYPE).toByte()
        bytes[1] = 0
        bytes[4] = ((identifier ushr 8) and 0xFF).toByte()
        bytes[5] = (identifier and 0xFF).toByte()
        bytes[6] = ((sequence ushr 8) and 0xFF).toByte()
        bytes[7] = (sequence and 0xFF).toByte()
        val csum = IcmpEchoPacketCodec.computeChecksum(bytes)
        bytes[2] = ((csum ushr 8) and 0xFF).toByte()
        bytes[3] = (csum and 0xFF).toByte()
        return bytes
    }

    private fun fakeChannel(
        id: String,
        label: String,
        isVpn: Boolean,
        onDns: (String) -> List<InetAddress> = { listOf(InetAddress.getByName("127.0.0.1")) },
    ): NetBenchSocketChannel = object : NetBenchSocketChannel {
        override val networkId: String = id
        override val transportLabel: String = label
        override val isVpn: Boolean = isVpn
        override val isDirectPhysical: Boolean = !isVpn
        override val bypassSystemHttpProxy: Boolean = !isVpn
        override val socketFactory: SocketFactory = SocketFactory.getDefault()
        override fun createSocket(): Socket = Socket()
        override fun resolveAllByName(hostname: String): List<InetAddress> = onDns(hostname)
    }

    @Test
    fun `SystemPingProcessExecutor destroys blocking subprocess immediately on coroutine cancellation`() = runBlocking {
        val startedAt = System.nanoTime()
        val job = launch(Dispatchers.IO) {
            SystemPingProcessExecutor.execute(
                PingProcessRequest(
                    command = listOf("sh", "-c", "exec sleep 30"),
                    timeoutMs = 30_000L,
                ),
            ).collect {}
        }
        delay(150L)
        job.cancel()
        job.join()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        assertTrue(
            "Expected cancelled ping subprocess to terminate within 2500ms, took ${elapsedMs}ms",
            elapsedMs < 2_500L,
        )
    }

    @Test
    fun `KernelBoundIcmpDirectProber short-circuits on VPN lockdown EPERM without probing remaining count`() = runBlocking {
        val sendCalls = AtomicInteger(0)
        val epermOps = object : IcmpKernelDatagramOps {
            override fun openIcmpDatagramSocket(ipFamily: IpFamily): FileDescriptor = FileDescriptor()
            override fun enableReceiveTtlBestEffort(fd: FileDescriptor, ipFamily: IpFamily): Boolean = false
            override fun sendTo(fd: FileDescriptor, packet: ByteArray, address: InetAddress) {
                sendCalls.incrementAndGet()
                throw java.net.SocketException("sendto failed: EPERM (Operation not permitted)")
            }
            override fun pollReadable(fd: FileDescriptor, timeoutMs: Int): Boolean = false
            override fun receivePacket(fd: FileDescriptor, buffer: ByteArray, ipFamily: IpFamily, tryReadTtl: Boolean): IcmpRawDatagram? = null
            override fun closeQuietly(fd: FileDescriptor) {}
        }

        val prober = KernelBoundIcmpDirectProber(ops = epermOps)
        val wifiChannel: NetBenchSocketChannel = object : NetBenchSocketChannel {
            override val networkId: String = "wifi-1"
            override val transportLabel: String = "Wi-Fi"
            override val isVpn: Boolean = false
            override val isDirectPhysical: Boolean = true
            override val bypassSystemHttpProxy: Boolean = true
            override val supportsFileDescriptorBind: Boolean = true
            override val socketFactory: SocketFactory = SocketFactory.getDefault()
            override fun createSocket(): Socket = Socket()
            override fun bindDatagramFileDescriptor(fd: FileDescriptor) = Unit
            override fun resolveAllByName(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
        }
        val vpnChannel: NetBenchSocketChannel = fakeChannel(id = "vpn-1", label = "VPN", isVpn = true)
        val bridge = object : NetBenchNetworkBridge {
            override fun inspectCurrentNetworks(): ResolvedNetworkBinding {
                val plan = NetBenchNetworkSelector.selectNetworkPlan(
                    listOf(
                        NetworkDescriptor(vpnChannel, "vpn-1", isVpn = true, hasInternet = true, isActiveDefault = true, transports = setOf(NetBenchTransportKind.VPN)),
                        NetworkDescriptor(wifiChannel, "wifi-1", isVpn = false, hasInternet = true, isValidated = true, transports = setOf(NetBenchTransportKind.WIFI), interfaceName = "wlan0"),
                    ),
                )
                return ResolvedNetworkBinding(plan, wifiChannel, vpnChannel)
            }
        }

        val runner = IcmpPingRunner(
            directProber = prober,
            networkBridge = bridge,
            ioDispatcher = Dispatchers.IO,
        )
        val finalProgress = runner.run(
            IcmpPingConfig(
                target = "127.0.0.1",
                probeCount = 10,
                timeoutMs = 1_500,
                intervalMs = 500L,
                useProxy = false,
            ),
        ).toList().last()

        assertTrue(finalProgress.isFinished)
        assertEquals("Expected immediate short-circuit on packet #1 EPERM", 1, sendCalls.get())
        assertEquals(1, finalProgress.samples.size)
        assertEquals(1, finalProgress.summary.sent)
        assertEquals(0, finalProgress.summary.received)
        assertNotNull(finalProgress.errorMessage)
    }
}
