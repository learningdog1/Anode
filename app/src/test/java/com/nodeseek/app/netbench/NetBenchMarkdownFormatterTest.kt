package com.nodeseek.app.netbench

import com.nodeseek.app.domain.netbench.ClientCarrier
import com.nodeseek.app.domain.netbench.ClientNetworkInfo
import com.nodeseek.app.domain.netbench.EgressRouteKind
import com.nodeseek.app.domain.netbench.HttpLatencyResult
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.IpIntelligenceReport
import com.nodeseek.app.domain.netbench.IpOriginType
import com.nodeseek.app.domain.netbench.IpUsageType
import com.nodeseek.app.domain.netbench.Iperf3Direction
import com.nodeseek.app.domain.netbench.Iperf3Result
import com.nodeseek.app.domain.netbench.NetBenchMarkdownFormatter
import com.nodeseek.app.domain.netbench.NetBenchMarkdownOptions
import com.nodeseek.app.domain.netbench.NetBenchMarkdownStyle
import com.nodeseek.app.domain.netbench.NetBenchReport
import com.nodeseek.app.domain.netbench.ResolvedTarget
import com.nodeseek.app.domain.netbench.TcpPingSummary
import com.nodeseek.app.ui.components.MdBlock
import com.nodeseek.app.ui.components.parseNodeSeekMarkdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetBenchMarkdownFormatterTest {

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
                peakMbps = 460.5,
                success = true,
            ),
            iperfUploadResult = Iperf3Result(
                direction = Iperf3Direction.UPLOAD,
                port = 5201,
                resolvedTarget = target,
                durationSec = 5.0,
                bytesTransferred = 55_000_000L,
                clientMbps = 88.0,
                peakMbps = 95.2,
                retransmits = 1L,
                success = true,
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

    @Test
    fun `default options mask target IP and port while showing client network environment`() {
        val defaultOptions = NetBenchMarkdownOptions(timestampLabel = "2026-11-20 17:00")
        assertTrue(defaultOptions.maskTargetIp)
        assertTrue(defaultOptions.includeClientNetwork)

        val report = sampleReport()
        val replyMd = NetBenchMarkdownFormatter.format(report = report, options = defaultOptions)

        assertTrue(replyMd.startsWith("> 📡 **本机网络实测（中国电信 · IPv4）**"))
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

        // Critical privacy check: user's home IP must never leak
        assertFalse("Client raw IP leaked into reply card!", replyMd.contains("114.240.88.123"))
        assertFalse("Client masked IP leaked into reply card!", replyMd.contains("114.240.*.*"))
    }

    @Test
    fun `disabling maskTargetIp reveals target IP and port while still protecting client IP`() {
        val report = sampleReport(
            clientRawIp = "114.240.88.123",
            clientMaskedIp = "114.240.*.*",
        )
        val md = NetBenchMarkdownFormatter.format(
            report = report,
            options = NetBenchMarkdownOptions(
                style = NetBenchMarkdownStyle.REPLY_CARD,
                maskTargetIp = false,
                timestampLabel = "2026-11-19 22:00",
            ),
        )
        assertTrue(md.contains("45.12.34.56:443"))
        assertFalse(md.contains("114.240.88.123"))
    }

    @Test
    fun `includeClientNetwork toggle controls client network info in both reply card and full table`() {
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

        val tableWithoutEnv = NetBenchMarkdownFormatter.format(
            report = report,
            options = NetBenchMarkdownOptions(
                style = NetBenchMarkdownStyle.FULL_TABLE,
                includeClientNetwork = false,
                timestampLabel = "2026-11-20 17:05",
            ),
        )
        assertFalse(tableWithoutEnv.contains("本机网络环境"))
        assertFalse(tableWithoutEnv.contains("中国电信"))
    }

    @Test
    fun `full table format produces aligned 3-column GFM table with uniform units placeholders and clamped long fields`() {
        val report = sampleReport(
            clientRawIp = "240e:3b0:1234::5678",
            clientMaskedIp = "240e:3b0::****",
            clientFamily = IpFamily.IPV6,
            orgName = "The Constant Company, LLC dba Vultr Holdings Corporation Global Infrastructure Division",
        ).copy(
            httpLatencyResult = HttpLatencyResult(
                url = "https://tokyo.example.com/",
                success = false,
                errorMessage = "SSLHandshakeException: java.security.cert.CertPathValidatorException: Trust anchor for certification path not found | detail",
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

        assertTrue(md.contains("### 📡 移动端网络实测报告"))
        assertTrue(md.contains("中国电信 · IPv6"))
        assertTrue(md.contains("45.12.34.*"))
        assertFalse("Target last octet should be masked", md.contains("45.12.34.56"))
        assertFalse("Target port should be hidden", md.contains(":443"))
        assertTrue(md.contains("耗时 5.0 s"))
        assertTrue(md.contains("iperf3 下载 (-R)"))
        assertTrue(md.contains("iperf3 上传"))
        assertTrue(md.contains("IP 原生鉴定"))
        assertTrue(md.contains("**广播 IP**"))
        assertFalse("Client IPv6 raw IP leaked into full table!", md.contains("240e:3b0:1234::5678"))
        assertFalse("Client IPv6 masked IP leaked into full table!", md.contains("240e:3b0::****"))

        // Verify parsed GFM table block structure & strict 3-column alignment
        val blocks = parseNodeSeekMarkdownBlocks(md)
        val table = blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("测试项目", "实测结果", "备注"), table.headers)
        assertTrue(table.rows.isNotEmpty())
        for (row in table.rows) {
            assertEquals("Row column count mismatch: $row", 3, row.size)
            assertTrue("Empty cell found in row: $row", row.all { it.isNotBlank() })
        }

        // Verify raw Markdown lines have identical monospace CJK display width across all table lines
        val tableLines = md.lines().filter { it.trimStart().startsWith("|") }
        assertTrue(tableLines.size >= 8)
        val expectedLineDisplayWidth = NetBenchMarkdownFormatter.displayWidth(tableLines.first())
        for (line in tableLines) {
            assertEquals(
                "Monospace CJK display width mismatch on line: $line",
                expectedLineDisplayWidth,
                NetBenchMarkdownFormatter.displayWidth(line),
            )
        }
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
        assertTrue(fallbackMd.contains("代理出口(直连受限)"))
        assertFalse(fallbackMd.contains("45.76.200.10"))

        val viaProxyMd = NetBenchMarkdownFormatter.format(
            report = base.copy(testedViaProxy = true, proxyNetwork = proxyNet),
            options = NetBenchMarkdownOptions(style = NetBenchMarkdownStyle.FULL_TABLE, timestampLabel = "2026-11-20 17:00"),
        )
        assertTrue(viaProxyMd.contains("经由代理"))
        assertFalse(viaProxyMd.contains("114.240.88.123"))
        assertFalse(viaProxyMd.contains("45.76.200.10"))
    }
}
