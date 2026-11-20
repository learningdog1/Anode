package com.nodeseek.app.netbench

import com.nodeseek.app.data.netbench.IcmpPingParser
import com.nodeseek.app.data.netbench.IcmpPingRunner
import com.nodeseek.app.data.netbench.NetBenchNetworkBridge
import com.nodeseek.app.data.netbench.NetBenchSocketChannel
import com.nodeseek.app.data.netbench.NetBenchTargetResolver
import com.nodeseek.app.data.netbench.PingExecutionEvent
import com.nodeseek.app.data.netbench.PingProcessExecutor
import com.nodeseek.app.data.netbench.PingProcessRequest
import com.nodeseek.app.data.netbench.ResolvedNetworkBinding
import com.nodeseek.app.domain.netbench.IcmpNetworkBindingMode
import com.nodeseek.app.domain.netbench.IcmpPingConfig
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.NetBenchNetworkSelector
import com.nodeseek.app.domain.netbench.NetBenchTransportKind
import com.nodeseek.app.domain.netbench.NetworkDescriptor
import com.nodeseek.app.domain.netbench.PreferredIpFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
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
}
