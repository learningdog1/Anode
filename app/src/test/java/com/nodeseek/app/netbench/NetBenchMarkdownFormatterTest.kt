package com.nodeseek.app.netbench

import com.nodeseek.app.domain.netbench.ClientCarrier
import com.nodeseek.app.domain.netbench.ClientNetworkInfo
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetBenchMarkdownFormatterTest {

    private fun sampleReport(
        clientRawIp: String = "114.240.88.123",
        clientMaskedIp: String = "114.240.*.*",
        clientFamily: IpFamily = IpFamily.IPV4,
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
                orgName = "Vultr",
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
    fun `reply card format renders compact blockquote and NEVER leaks client home IP`() {
        val report = sampleReport(
            clientRawIp = "114.240.88.123",
            clientMaskedIp = "114.240.*.*",
        )
        val md = NetBenchMarkdownFormatter.format(
            report = report,
            options = NetBenchMarkdownOptions(
                style = NetBenchMarkdownStyle.REPLY_CARD,
                timestampLabel = "2026-11-19 22:00",
            ),
        )

        assertTrue(md.startsWith("> 📡 **本机网络实测（中国电信 · IPv4）**"))
        assertTrue(md.contains("tokyo.example.com"))
        assertTrue(md.contains("45.12.34.56:443"))
        assertTrue(md.contains("38.4 ms"))
        assertTrue(md.contains("抖动 `2.8 ms`"))
        assertTrue(md.contains("丢包 `0.0%`"))
        assertTrue(md.contains("下载 **432.0 Mbps**"))
        assertTrue(md.contains("上传 **88.0 Mbps**"))
        assertTrue(md.contains("AS20473"))
        assertTrue(md.contains("广播 IP (注册地 US -> 播放在 JP"))

        // Critical privacy check: user's home IP must never leak
        assertFalse("Client raw IP leaked into reply card!", md.contains("114.240.88.123"))
        assertFalse("Client masked IP leaked into reply card!", md.contains("114.240.*.*"))
    }

    @Test
    fun `full table format renders GFM table hides IPv6 home IP and supports masking target IP`() {
        val report = sampleReport(
            clientRawIp = "240e:3b0:1234::5678",
            clientMaskedIp = "240e:3b0::****",
            clientFamily = IpFamily.IPV6,
        )
        val md = NetBenchMarkdownFormatter.format(
            report = report,
            options = NetBenchMarkdownOptions(
                style = NetBenchMarkdownStyle.FULL_TABLE,
                maskTargetIp = true,
                timestampLabel = "2026-11-19 22:05",
            ),
        )

        assertTrue(md.contains("### 📡 移动端网络实测报告"))
        assertTrue(md.contains("| 测试项目 | 实测结果 | 备注 |"))
        assertTrue(md.contains("中国电信 · IPv6"))
        assertTrue(md.contains("45.12.34.*:443"))
        assertFalse("Target last octet should be masked when maskTargetIp=true", md.contains("45.12.34.56"))
        assertTrue(md.contains("iperf3 下载 (-R)"))
        assertTrue(md.contains("iperf3 上传"))
        assertTrue(md.contains("IP 原生鉴定"))
        assertTrue(md.contains("**广播 IP**"))

        // Critical privacy check for IPv6 client egress IP
        assertFalse("Client IPv6 raw IP leaked into full table!", md.contains("240e:3b0:1234::5678"))
        assertFalse("Client IPv6 masked IP leaked into full table!", md.contains("240e:3b0::****"))
    }
}
