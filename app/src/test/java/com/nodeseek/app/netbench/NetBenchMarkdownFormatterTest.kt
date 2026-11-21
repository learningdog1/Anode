package com.nodeseek.app.netbench

import com.nodeseek.app.domain.netbench.ClientCarrier
import com.nodeseek.app.domain.netbench.ClientNetworkInfo
import com.nodeseek.app.domain.netbench.EgressRouteKind
import com.nodeseek.app.domain.netbench.HttpLatencyResult
import com.nodeseek.app.domain.netbench.IcmpNetworkBindingMode
import com.nodeseek.app.domain.netbench.IcmpPingSample
import com.nodeseek.app.domain.netbench.IcmpPingSummary
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.IpIntelligenceReport
import com.nodeseek.app.domain.netbench.IpOriginType
import com.nodeseek.app.domain.netbench.IpUsageType
import com.nodeseek.app.domain.netbench.Iperf3Direction
import com.nodeseek.app.domain.netbench.Iperf3IntervalSample
import com.nodeseek.app.domain.netbench.Iperf3Result
import com.nodeseek.app.domain.netbench.NetBenchMarkdownFormatter
import com.nodeseek.app.domain.netbench.NetBenchMarkdownOptions
import com.nodeseek.app.domain.netbench.NetBenchMarkdownStyle
import com.nodeseek.app.domain.netbench.NetBenchReport
import com.nodeseek.app.domain.netbench.ResolvedTarget
import com.nodeseek.app.domain.netbench.TcpPingSample
import com.nodeseek.app.domain.netbench.TcpPingSummary
import com.nodeseek.app.ui.components.MdBlock
import com.nodeseek.app.ui.components.parseNodeSeekMarkdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetBenchMarkdownFormatterTest {

    private fun sampleDownloadIntervals(streams: Int = 1): List<Iperf3IntervalSample> = listOf(
        Iperf3IntervalSample(1, 0.0, 1.0, 51_125_000L, 409.0, streams = streams),
        Iperf3IntervalSample(2, 1.0, 2.0, 55_000_000L, 440.0, streams = streams),
        Iperf3IntervalSample(3, 2.0, 3.0, 54_375_000L, 435.0, streams = streams),
        Iperf3IntervalSample(4, 3.0, 4.0, 57_562_500L, 460.5, streams = streams),
        Iperf3IntervalSample(5, 4.0, 5.0, 51_937_500L, 415.5, streams = streams),
    )

    private fun sampleUploadIntervals(streams: Int = 1): List<Iperf3IntervalSample> = listOf(
        Iperf3IntervalSample(1, 0.0, 1.0, 10_300_000L, 82.4, streams = streams),
        Iperf3IntervalSample(2, 1.0, 2.0, 11_112_500L, 88.9, streams = streams),
        Iperf3IntervalSample(3, 2.0, 3.0, 11_900_000L, 95.2, streams = streams),
        Iperf3IntervalSample(4, 3.0, 4.0, 10_900_000L, 87.2, streams = streams),
        Iperf3IntervalSample(5, 4.0, 5.0, 10_787_500L, 86.3, streams = streams),
    )

    private fun sampleReport(
        clientRawIp: String = "114.240.88.123",
        clientMaskedIp: String = "114.240.*.*",
        clientFamily: IpFamily = IpFamily.IPV4,
        orgName: String? = "Vultr",
    ): NetBenchReport {
        val target = ResolvedTarget(
            displayHost = "tokyo.example.com",
            resolvedIp = "45.12.34.56",
            ipFamily = IpFamily.IPV4,
            dnsDurationMs = 12.4,
        )
        return NetBenchReport(
            targetInput = "tokyo.example.com",
            resolvedTarget = target,
            tcpPort = 443,
            clientNetwork = ClientNetworkInfo(
                carrier = ClientCarrier.CHINA_TELECOM,
                carrierDisplayName = "中国电信",
                ipFamily = clientFamily,
                countryCode = "CN",
                regionName = "Beijing",
                asn = "AS4134",
                ispName = "ChinaNet",
                maskedIp = clientMaskedIp,
                rawIp = clientRawIp,
                source = "ipwho.is",
                transportLabel = "Wi-Fi",
            ),
            tcpPingSummary = TcpPingSummary(
                resolvedTarget = target,
                port = 443,
                sent = 10,
                received = 10,
                lossPercent = 0.0,
                minMs = 35.1,
                avgMs = 38.4,
                maxMs = 44.2,
                jitterMdevMs = 2.8,
            ),
            iperfDownloadResult = Iperf3Result(
                direction = Iperf3Direction.DOWNLOAD,
                port = 5201,
                resolvedTarget = target,
                durationSec = 5.0,
                bytesTransferred = 270_000_000L,
                clientMbps = 432.0,
                serverMbps = 435.2,
                peakMbps = 460.5,
                retransmits = 0L,
                success = true,
                intervals = sampleDownloadIntervals(),
            ),
            iperfUploadResult = Iperf3Result(
                direction = Iperf3Direction.UPLOAD,
                port = 5201,
                resolvedTarget = target,
                durationSec = 5.0,
                bytesTransferred = 55_000_000L,
                clientMbps = 88.0,
                serverMbps = 87.6,
                peakMbps = 95.2,
                retransmits = 1L,
                success = true,
                intervals = sampleUploadIntervals(),
            ),
            ipIntelligence = IpIntelligenceReport(
                ip = "45.12.34.56",
                ipFamily = IpFamily.IPV4,
                countryCode = "JP",
                countryName = "Japan",
                regionName = "Tokyo",
                cityName = "Tokyo",
                asn = "AS20473",
                orgName = orgName,
                ispName = "The Constant Company",
                usageType = IpUsageType.HOSTING_DATACENTER,
                originType = IpOriginType.BROADCAST,
                rirCountryCode = "US",
                rirAuthority = "ARIN",
                originEvidence = "广播 IP (注册地 US -> 播放在 JP · ARIN)",
                geoSource = "ipwho.is",
                whoisSource = "RIPEstat",
            ),
        )
    }

    private fun extractContiguousPipeTables(markdown: String): List<List<String>> {
        val tables = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        for (line in markdown.lines()) {
            if (line.trimStart().startsWith("|")) {
                current += line
            } else if (current.isNotEmpty()) {
                tables += current
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) tables += current
        return tables
    }

    @Test
    fun `default options render full report with iperf3 per-second intervals while masking target IP and protecting client IP`() {
        val defaultOptions = NetBenchMarkdownOptions(timestampLabel = "2026-11-20 17:30")
        assertEquals(NetBenchMarkdownStyle.FULL_TABLE, defaultOptions.style)
        assertTrue(defaultOptions.maskTargetIp)
        assertTrue(defaultOptions.includeClientNetwork)

        val report = sampleReport().copy(tcpPort = 30)
        val md = NetBenchMarkdownFormatter.format(report = report, options = defaultOptions)

        // Core sections & headings
        assertTrue(md.contains("## 📡 移动端网络实测报告"))
        assertTrue(md.contains("### 🌐 目标节点与 IP 质量鉴定"))
        assertTrue(md.contains("### ⚡ 网络延迟与链路稳定性分析"))
        assertTrue(md.contains("### 🚀 iperf3 带宽吞吐实测全过程"))
        assertTrue(md.contains("### 🩺 综合线路体检结论"))
        assertTrue(md.contains("NodePulse 移动端网络实测"))

        // Environment summary card + timestamp integrity (port=30 must not corrupt 17:30!)
        assertTrue(md.contains("2026-11-20 17:30"))
        assertTrue(md.contains("中国电信"))
        assertTrue(md.contains("Beijing"))
        assertTrue(md.contains("AS4134"))
        assertTrue(md.contains("DNS 解析耗时 `12.4 ms`"))

        // Per-second iperf3 interval logs for both Download (-R) and Upload
        assertTrue(md.contains("[ ID] Interval           Transfer      Bitrate           Trend"))
        assertTrue(md.contains("[  1]  0.00-1.00  sec"))
        assertTrue(md.contains("[  1]  1.00-2.00  sec"))
        assertTrue(md.contains("[  1]  2.00-3.00  sec"))
        assertTrue(md.contains("[  1]  3.00-4.00  sec"))
        assertTrue(md.contains("[  1]  4.00-5.00  sec"))
        assertTrue(md.contains("█"))
        assertTrue(md.contains("[SUM]  0.00-5.00  sec"))
        assertTrue(md.contains("sender (server)"))
        assertTrue(md.contains("receiver (client)"))
        assertTrue(md.contains("1 Retr"))
        assertTrue(md.contains("sender (client)"))
        assertTrue(md.contains("receiver (server)"))

        // Masking & privacy red-line checks
        assertTrue(md.contains("tokyo.example.com"))
        assertTrue(md.contains("45.12.34.*"))
        assertFalse("Full target IP must be masked across tables and iperf3 logs", md.contains("45.12.34.56"))
        assertFalse("iperf3 port must be hidden when maskTargetIp=true", md.contains("5201"))
        assertFalse("Client raw IP leaked into full report!", md.contains("114.240.88.123"))
        assertFalse("Client masked IP leaked into full report!", md.contains("114.240.*.*"))
    }

    @Test
    fun `reply card format renders rich blockquote plus iperf3 interval log while protecting client IP`() {
        val report = sampleReport()
        val replyMd = NetBenchMarkdownFormatter.format(
            report = report,
            options = NetBenchMarkdownOptions(
                style = NetBenchMarkdownStyle.REPLY_CARD,
                timestampLabel = "2026-11-20 17:00",
            ),
        )

        assertTrue(replyMd.startsWith("> 📡 **本机网络实测（中国电信 · Beijing · IPv4）**"))
        assertTrue(replyMd.contains("tokyo.example.com"))
        assertTrue(replyMd.contains("45.12.34.*"))
        assertFalse("Full target IP should be masked by default", replyMd.contains("45.12.34.56"))
        assertFalse("Target port should be hidden by default", replyMd.contains(":443"))
        assertTrue(replyMd.contains("38.4 ms"))
        assertTrue(replyMd.contains("抖动 `2.8 ms`"))
        assertTrue(replyMd.contains("丢包 `0.0%`"))
        assertTrue(replyMd.contains("下载 **432.0 Mbps**"))
        assertTrue(replyMd.contains("上传 **88.0 Mbps**"))
        assertTrue(replyMd.contains("AS20473"))
        assertTrue(replyMd.contains("广播 IP (注册地 US -> 播放在 JP"))
        assertTrue(replyMd.contains("综合简评"))
        // Reply card also includes the fenced iperf3 per-second interval process
        assertTrue(replyMd.contains("```text"))
        assertTrue(replyMd.contains("[  1]  0.00-1.00  sec"))
        assertTrue(replyMd.contains("[SUM]  0.00-5.00  sec"))

        assertFalse("Client raw IP leaked into reply card!", replyMd.contains("114.240.88.123"))
        assertFalse("Client masked IP leaked into reply card!", replyMd.contains("114.240.*.*"))
    }

    @Test
    fun `disabling maskTargetIp reveals target IP and port while still protecting client IP`() {
        val report = sampleReport(
            clientRawIp = "114.240.88.123",
            clientMaskedIp = "114.240.*.*",
        )
        val replyMd = NetBenchMarkdownFormatter.format(
            report = report,
            options = NetBenchMarkdownOptions(
                style = NetBenchMarkdownStyle.REPLY_CARD,
                maskTargetIp = false,
                timestampLabel = "2026-11-19 22:00",
            ),
        )
        assertTrue(replyMd.contains("45.12.34.56:443"))
        assertTrue(replyMd.contains("port 5201"))
        assertFalse(replyMd.contains("114.240.88.123"))
        assertFalse(replyMd.contains("114.240.*.*"))

        val fullMd = NetBenchMarkdownFormatter.format(
            report = report,
            options = NetBenchMarkdownOptions(
                style = NetBenchMarkdownStyle.FULL_TABLE,
                maskTargetIp = false,
                timestampLabel = "2026-11-19 22:00",
            ),
        )
        assertTrue(fullMd.contains("45.12.34.56:443"))
        assertTrue(fullMd.contains("TCP 443"))
        assertTrue(fullMd.contains("port 5201"))
        assertFalse(fullMd.contains("114.240.88.123"))
        assertFalse(fullMd.contains("114.240.*.*"))
    }

    @Test
    fun `includeClientNetwork toggle controls client network info in both reply card and full report`() {
        val report = sampleReport()

        val replyWithoutEnv = NetBenchMarkdownFormatter.format(
            report = report,
            options = NetBenchMarkdownOptions(
                style = NetBenchMarkdownStyle.REPLY_CARD,
                includeClientNetwork = false,
                timestampLabel = "2026-11-20 17:05",
            ),
        )
        assertTrue(replyWithoutEnv.startsWith("> 📡 **移动端网络实测**"))
        assertFalse(replyWithoutEnv.contains("中国电信"))
        assertFalse(replyWithoutEnv.contains("AS4134"))

        val fullWithoutEnv = NetBenchMarkdownFormatter.format(
            report = report,
            options = NetBenchMarkdownOptions(
                style = NetBenchMarkdownStyle.FULL_TABLE,
                includeClientNetwork = false,
                timestampLabel = "2026-11-20 17:05",
            ),
        )
        assertFalse(fullWithoutEnv.contains("本机网络环境"))
        assertFalse(fullWithoutEnv.contains("测试终端网络"))
        assertFalse(fullWithoutEnv.contains("中国电信"))
        assertFalse(fullWithoutEnv.contains("AS4134"))
    }

    @Test
    fun `full report produces clean AST blocks and strictly aligned CJK monospace widths across every GFM table`() {
        val report = sampleReport(
            clientRawIp = "240e:3b0:1234::5678",
            clientMaskedIp = "240e:3b0::****",
            clientFamily = IpFamily.IPV6,
            orgName = "The Constant Company, LLC dba Vultr Holdings Corporation Global Infrastructure Division",
        ).copy(
            httpLatencyResult = HttpLatencyResult(
                url = "https://45.12.34.56:443/",
                success = false,
                errorMessage = "SSLHandshakeException on 45.12.34.56:443 | CertPathValidatorException: Trust anchor not found",
            ),
        )

        val md = NetBenchMarkdownFormatter.format(
            report = report,
            options = NetBenchMarkdownOptions(
                style = NetBenchMarkdownStyle.FULL_TABLE,
                maskTargetIp = true,
                includeClientNetwork = true,
                timestampLabel = "2026-11-19 22:05",
            ),
        )

        assertTrue(md.contains("## 📡 移动端网络实测报告"))
        assertTrue(md.contains("中国电信"))
        assertTrue(md.contains("IPv6"))
        assertTrue(md.contains("45.12.34.*"))
        assertFalse("Target last octet should be masked even in error cells", md.contains("45.12.34.56"))
        assertFalse("Target port should be hidden even in error cells", md.contains(":443"))
        assertTrue(md.contains("耗时 5.0 s"))
        assertTrue(md.contains("iperf3 下载 (-R)"))
        assertTrue(md.contains("iperf3 上传"))
        assertTrue(md.contains("IP 原生鉴定"))
        assertTrue(md.contains("**广播 IP**"))
        assertFalse("Client IPv6 raw IP leaked into full report!", md.contains("240e:3b0:1234::5678"))
        assertFalse("Client IPv6 masked IP leaked into full report!", md.contains("240e:3b0::****"))

        // Verify MarkdownRenderer AST structure: headings, blockquote, 3 GFM tables, code fences, verdict list, HR
        val blocks = parseNodeSeekMarkdownBlocks(md)
        assertTrue(blocks.any { it is MdBlock.Heading })
        assertTrue(blocks.any { it is MdBlock.Blockquote })
        assertTrue(blocks.any { it is MdBlock.ListContainer })
        assertTrue(blocks.any { it is MdBlock.HorizontalRule })

        val codeFences = blocks.filterIsInstance<MdBlock.CodeFence>()
        assertEquals("Expected 2 iperf3 interval code blocks (DL + UL)", 2, codeFences.size)
        assertTrue(codeFences.all { it.language == "text" })

        val tables = blocks.filterIsInstance<MdBlock.Table>()
        assertEquals("Expected 3 structured GFM tables (Target/IP, Latency, iperf3)", 3, tables.size)
        for (table in tables) {
            val expectedCols = table.headers.size
            assertTrue(table.rows.isNotEmpty())
            for (row in table.rows) {
                assertEquals("Row column count mismatch in ${table.headers}: $row", expectedCols, row.size)
                assertTrue("Empty cell found in row: $row", row.all { it.isNotBlank() })
            }
        }

        // Verify raw Markdown lines have identical monospace CJK display width within each table
        val rawTableGroups = extractContiguousPipeTables(md)
        assertEquals(3, rawTableGroups.size)
        for (group in rawTableGroups) {
            assertTrue(group.size >= 3)
            val expectedWidth = NetBenchMarkdownFormatter.displayWidth(group.first())
            for (line in group) {
                assertEquals(
                    "Monospace CJK display width mismatch on line: $line",
                    expectedWidth,
                    NetBenchMarkdownFormatter.displayWidth(line),
                )
            }
        }
    }

    @Test
    fun `latency deep analysis renders percentiles range TTL per-packet sequence waveform and HTTP TLS TTFB`() {
        val base = sampleReport()
        val pingSamples = listOf(
            IcmpPingSample(seq = 1, success = true, rttMs = 28.4, ttl = 54),
            IcmpPingSample(seq = 2, success = true, rttMs = 30.1, ttl = 54),
            IcmpPingSample(seq = 3, success = true, rttMs = 29.8, ttl = 54),
            IcmpPingSample(seq = 4, success = true, rttMs = 33.9, ttl = 54),
            IcmpPingSample(seq = 5, success = true, rttMs = 28.8, ttl = 54),
        )
        val tcpSamples = listOf(
            TcpPingSample(seq = 1, success = true, rttMs = 35.1),
            TcpPingSample(seq = 2, success = true, rttMs = 37.8),
            TcpPingSample(seq = 3, success = false, rttMs = null, errorMessage = "timeout"),
            TcpPingSample(seq = 4, success = true, rttMs = 44.2),
            TcpPingSample(seq = 5, success = true, rttMs = 36.5),
        )
        val rich = base.copy(
            icmpPingSummary = IcmpPingSummary(
                resolvedTarget = base.resolvedTarget,
                sent = 5,
                received = 5,
                lossPercent = 0.0,
                minMs = 28.4,
                avgMs = 30.2,
                maxMs = 33.9,
                jitterMdevMs = 1.9,
                samples = pingSamples,
                bindingMode = IcmpNetworkBindingMode.INTERFACE_BOUND,
                boundInterfaceName = "wlan0",
            ),
            tcpPingSummary = TcpPingSummary(
                resolvedTarget = base.resolvedTarget,
                port = 443,
                sent = 5,
                received = 4,
                lossPercent = 20.0,
                minMs = 35.1,
                avgMs = 38.4,
                maxMs = 44.2,
                jitterMdevMs = 3.4,
                samples = tcpSamples,
            ),
            httpLatencyResult = HttpLatencyResult(
                url = "https://tokyo.example.com/",
                statusCode = 200,
                totalMs = 118.6,
                ttfbMs = 82.4,
                tlsHandshakeMs = 39.2,
                protocol = "h2",
                success = true,
            ),
        )

        val md = NetBenchMarkdownFormatter.format(
            report = rich,
            options = NetBenchMarkdownOptions(
                style = NetBenchMarkdownStyle.FULL_TABLE,
                timestampLabel = "2026-11-20 19:50",
            ),
        )

        assertTrue(md.contains("极差 5.5 ms"))
        assertTrue(md.contains("P50"))
        assertTrue(md.contains("P95"))
        assertTrue(md.contains("TTL 54"))
        assertTrue(md.contains("wlan0"))
        assertTrue(md.contains("较 Ping +8.2 ms"))
        assertTrue(md.contains("HTTP 200"))
        assertTrue(md.contains("TLS 握手 `39.2 ms`"))
        assertTrue(md.contains("TTFB `82.4 ms`"))
        assertTrue(md.contains("协议 `h2`"))
        assertTrue(md.contains("[ICMP Ping 逐包序列]"))
        assertTrue(md.contains("[TCPing 逐包序列]"))
        assertTrue(md.contains("#03  TIMEOUT"))
        assertTrue(md.contains("✕"))
    }

    @Test
    fun `labels proxy-tested and proxy-fallback routes while scrubbing both real and proxy client IPs`() {
        val base = sampleReport()
        val proxyNet = ClientNetworkInfo(
            carrier = ClientCarrier.OVERSEAS,
            carrierDisplayName = "海外网络 (日本)",
            ipFamily = IpFamily.IPV4,
            countryCode = "JP",
            maskedIp = "45.76.*.*",
            rawIp = "45.76.200.10",
            routeKind = EgressRouteKind.PROXY_FALLBACK,
        )

        val fallbackMd = NetBenchMarkdownFormatter.format(
            report = base.copy(clientNetwork = proxyNet, testedViaProxy = false),
            options = NetBenchMarkdownOptions(style = NetBenchMarkdownStyle.REPLY_CARD, timestampLabel = "2026-11-20 17:00"),
        )
        assertTrue(fallbackMd.contains("代理出口(直连受限"))
        assertFalse(fallbackMd.contains("45.76.200.10"))
        assertFalse(fallbackMd.contains("45.76.*.*"))

        val viaProxyMd = NetBenchMarkdownFormatter.format(
            report = base.copy(testedViaProxy = true, proxyNetwork = proxyNet),
            options = NetBenchMarkdownOptions(style = NetBenchMarkdownStyle.FULL_TABLE, timestampLabel = "2026-11-20 17:00"),
        )
        assertTrue(viaProxyMd.contains("经由代理"))
        assertFalse(viaProxyMd.contains("114.240.88.123"))
        assertFalse(viaProxyMd.contains("45.76.200.10"))
        assertFalse(viaProxyMd.contains("45.76.*.*"))
    }

    @Test
    fun `places ICMP Ping row before TCPing row in both reply card and aligned full report`() {
        val base = sampleReport()
        val withPing = base.copy(
            icmpPingSummary = IcmpPingSummary(
                resolvedTarget = base.resolvedTarget,
                sent = 10,
                received = 10,
                lossPercent = 0.0,
                minMs = 28.4,
                avgMs = 30.2,
                maxMs = 33.9,
                jitterMdevMs = 1.6,
                bindingMode = IcmpNetworkBindingMode.UNBOUND_SUBPROCESS_FALLBACK,
            ),
        )

        val replyMd = NetBenchMarkdownFormatter.format(
            report = withPing,
            options = NetBenchMarkdownOptions(style = NetBenchMarkdownStyle.REPLY_CARD, timestampLabel = "2026-11-20 19:50"),
        )
        val replyPingIdx = replyMd.indexOf("**Ping (10次)**")
        val replyTcpIdx = replyMd.indexOf("**TCPing (10次)**")
        assertTrue("Ping line missing in reply card:\n$replyMd", replyPingIdx >= 0)
        assertTrue("TCPing line missing in reply card:\n$replyMd", replyTcpIdx >= 0)
        assertTrue("Ping line must precede TCPing line", replyPingIdx < replyTcpIdx)
        assertTrue(replyMd.contains("30.2 ms"))
        assertTrue(replyMd.contains("抖动 `1.6 ms`"))

        val tableMd = NetBenchMarkdownFormatter.format(
            report = withPing,
            options = NetBenchMarkdownOptions(style = NetBenchMarkdownStyle.FULL_TABLE, timestampLabel = "2026-11-20 19:50"),
        )
        val tablePingIdx = tableMd.indexOf("Ping 延迟")
        val tableTcpIdx = tableMd.indexOf("TCPing 延迟")
        assertTrue("Ping row missing in full report:\n$tableMd", tablePingIdx >= 0)
        assertTrue("TCPing row missing in full report:\n$tableMd", tableTcpIdx >= 0)
        assertTrue("Ping row must precede TCPing row", tablePingIdx < tableTcpIdx)
        assertTrue(tableMd.contains("默认路由"))

        val blocks = parseNodeSeekMarkdownBlocks(tableMd)
        val latencyTable = blocks.filterIsInstance<MdBlock.Table>()[1]
        val pingRowIdx = latencyTable.rows.indexOfFirst { it.first() == "Ping 延迟" }
        val tcpRowIdx = latencyTable.rows.indexOfFirst { it.first() == "TCPing 延迟" }
        assertTrue(pingRowIdx >= 0 && tcpRowIdx > pingRowIdx)
    }

    @Test
    fun `notes iperf3 parallel stream count only when greater than 1 and uses SUM interval rows for multi-stream`() {
        val base = sampleReport()

        val singleReply = NetBenchMarkdownFormatter.format(
            report = base,
            options = NetBenchMarkdownOptions(style = NetBenchMarkdownStyle.REPLY_CARD, timestampLabel = "2026-11-20 20:25"),
        )
        assertFalse("Single-stream report must not display -P flag", singleReply.contains("-P "))
        assertTrue(singleReply.contains("[  1]  0.00-1.00  sec"))

        val singleTable = NetBenchMarkdownFormatter.format(
            report = base,
            options = NetBenchMarkdownOptions(style = NetBenchMarkdownStyle.FULL_TABLE, timestampLabel = "2026-11-20 20:25"),
        )
        assertFalse("Single-stream full report must not display -P flag", singleTable.contains("-P "))
        assertTrue(singleTable.contains("[  1]  0.00-1.00  sec"))

        val multi = base.copy(
            iperfDownloadResult = base.iperfDownloadResult!!.copy(
                parallelStreams = 4,
                intervals = sampleDownloadIntervals(streams = 4),
            ),
            iperfUploadResult = base.iperfUploadResult!!.copy(
                parallelStreams = 4,
                intervals = sampleUploadIntervals(streams = 4),
            ),
        )

        val multiReply = NetBenchMarkdownFormatter.format(
            report = multi,
            options = NetBenchMarkdownOptions(style = NetBenchMarkdownStyle.REPLY_CARD, timestampLabel = "2026-11-20 20:25"),
        )
        assertTrue(multiReply.contains("**iperf3 测速 (-P 4)**"))
        assertTrue(multiReply.contains("[SUM]  0.00-1.00  sec"))

        val multiTable = NetBenchMarkdownFormatter.format(
            report = multi,
            options = NetBenchMarkdownOptions(style = NetBenchMarkdownStyle.FULL_TABLE, timestampLabel = "2026-11-20 20:25"),
        )
        assertTrue(multiTable.contains("-P 4 并发"))
        assertTrue(multiTable.contains("-P 4 parallel streams"))
        assertTrue(multiTable.contains("[SUM]  0.00-1.00  sec"))

        for (group in extractContiguousPipeTables(multiTable)) {
            val expectedWidth = NetBenchMarkdownFormatter.displayWidth(group.first())
            for (line in group) {
                assertEquals("Table line width mismatch: $line", expectedWidth, NetBenchMarkdownFormatter.displayWidth(line))
            }
        }

        val boundReport = base.copy(
            icmpPingSummary = IcmpPingSummary(
                resolvedTarget = base.resolvedTarget,
                sent = 4,
                received = 4,
                lossPercent = 0.0,
                minMs = 15.2,
                avgMs = 16.5,
                maxMs = 18.1,
                jitterMdevMs = 1.1,
                samples = (1..4).map { IcmpPingSample(seq = it, success = true, rttMs = 16.5, ttl = 58) },
                bindingMode = IcmpNetworkBindingMode.PHYSICAL_NETWORK_BOUND,
                boundInterfaceName = "wlan0",
            ),
        )
        val boundMd = NetBenchMarkdownFormatter.format(
            report = boundReport,
            options = NetBenchMarkdownOptions(style = NetBenchMarkdownStyle.FULL_TABLE, timestampLabel = "2026-11-20 20:25"),
        )
        assertTrue(boundMd.contains("物理网卡直连 `wlan0`"))
    }
}
