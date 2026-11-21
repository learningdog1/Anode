package com.nodeseek.app.netbench

import com.nodeseek.app.data.netbench.HttpLatencyRunner
import com.nodeseek.app.domain.netbench.ClientCarrier
import com.nodeseek.app.domain.netbench.ClientNetworkInfo
import com.nodeseek.app.domain.netbench.HttpLatencyResult
import com.nodeseek.app.domain.netbench.IcmpNetworkBindingMode
import com.nodeseek.app.domain.netbench.IcmpPingSample
import com.nodeseek.app.domain.netbench.IcmpPingSummary
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.IpIntelligenceReport
import com.nodeseek.app.domain.netbench.IpOriginType
import com.nodeseek.app.domain.netbench.IpUsageType
import com.nodeseek.app.domain.netbench.Iperf3Direction
import com.nodeseek.app.domain.netbench.Iperf3ErrorKind
import com.nodeseek.app.domain.netbench.Iperf3IntervalSample
import com.nodeseek.app.domain.netbench.Iperf3Result
import com.nodeseek.app.domain.netbench.NetBenchErrorCategory
import com.nodeseek.app.domain.netbench.NetBenchMarkdownFormatter
import com.nodeseek.app.domain.netbench.NetBenchMarkdownOptions
import com.nodeseek.app.domain.netbench.NetBenchMarkdownStyle
import com.nodeseek.app.domain.netbench.NetBenchReport
import com.nodeseek.app.domain.netbench.NetBenchReportSanitizer
import com.nodeseek.app.domain.netbench.ResolvedTarget
import com.nodeseek.app.domain.netbench.TcpPingSample
import com.nodeseek.app.domain.netbench.TcpPingSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException

class NetBenchReportFormatterTest {

    private fun buildAdversarialIpv4Report(httpResult: HttpLatencyResult): NetBenchReport {
        val target = ResolvedTarget(
            displayHost = "node.example.com",
            resolvedIp = "203.0.113.42",
            ipFamily = IpFamily.IPV4,
            allResolvedIps = listOf("203.0.113.42", "198.51.100.77"),
            dnsDurationMs = 9.6,
        )
        return NetBenchReport(
            targetInput = "node.example.com:8443",
            resolvedTarget = target,
            tcpPort = 8443,
            clientNetwork = ClientNetworkInfo(
                carrier = ClientCarrier.CHINA_TELECOM,
                carrierDisplayName = "中国电信",
                ipFamily = IpFamily.IPV4,
                countryCode = "CN",
                regionName = "上海",
                asn = "AS4134",
                ispName = "ChinaNet (180.168.12.88)",
                maskedIp = "180.168.*.*",
                rawIp = "180.168.12.88",
                transportLabel = "Wi-Fi",
            ),
            icmpPingSummary = IcmpPingSummary(
                resolvedTarget = target,
                sent = 4,
                received = 0,
                lossPercent = 100.0,
                minMs = null,
                avgMs = null,
                maxMs = null,
                jitterMdevMs = null,
                bindingMode = IcmpNetworkBindingMode.INTERFACE_BOUND,
                boundInterfaceName = "wlan0 (gw 203.0.113.1)",
                samples = listOf(
                    IcmpPingSample(1, false, errorMessage = "sendto 203.0.113.42 failed: ENETUNREACH"),
                    IcmpPingSample(2, false, errorMessage = "sendto 203.0.113.42 failed: ENETUNREACH"),
                    IcmpPingSample(3, false, errorMessage = "sendto 203.0.113.42 failed: ENETUNREACH"),
                    IcmpPingSample(4, false, errorMessage = "sendto 203.0.113.42 failed: ENETUNREACH"),
                ),
                errorMessage = "目标网络不可达 (From 203.0.113.1 icmp_seq=1 Destination Host Unreachable for 203.0.113.42)",
            ),
            tcpPingSummary = TcpPingSummary(
                resolvedTarget = target,
                port = 8443,
                sent = 4,
                received = 0,
                lossPercent = 100.0,
                minMs = null,
                avgMs = null,
                maxMs = null,
                jitterMdevMs = null,
                samples = listOf(
                    TcpPingSample(1, false, errorMessage = "Failed to connect to node.example.com/203.0.113.42:8443"),
                    TcpPingSample(2, false, errorMessage = "Failed to connect to node.example.com/203.0.113.42:8443"),
                    TcpPingSample(3, false, errorMessage = "Failed to connect to node.example.com/203.0.113.42:8443"),
                    TcpPingSample(4, false, errorMessage = "Failed to connect to node.example.com/203.0.113.42:8443"),
                ),
                errorMessage = "failed to connect to /203.0.113.42 (port 8443) from /180.168.12.88 (port 54321): ECONNREFUSED",
            ),
            httpLatencyResult = httpResult,
            ipIntelligence = IpIntelligenceReport(
                ip = "203.0.113.42",
                ipFamily = IpFamily.IPV4,
                countryCode = "SG",
                countryName = "Singapore",
                regionName = "Singapore",
                cityName = "Singapore",
                asn = "AS13335",
                orgName = "Example Cloud (203.0.113.42)",
                ispName = "ExampleNet",
                usageType = IpUsageType.HOSTING_DATACENTER,
                originType = IpOriginType.NATIVE,
                rirCountryCode = "SG",
                rirAuthority = "APNIC",
                originEvidence = "原生 IP (203.0.113.42 · PTR ec2-203-0-113-42.ap-southeast-1.compute.amazonaws.com · 42.113.0.203.in-addr.arpa)",
                geoSource = "ipwho.is",
                whoisSource = "RDAP",
            ),
            iperfDownloadResult = Iperf3Result(
                direction = Iperf3Direction.DOWNLOAD,
                port = 15201,
                resolvedTarget = target,
                durationSec = 0.0,
                bytesTransferred = 0L,
                clientMbps = 0.0,
                success = false,
                errorKind = Iperf3ErrorKind.IO_ERROR,
                errorMessage = "网络测速中断（端口 15201）：Connection reset by peer 203.0.113.42:15201",
            ),
            iperfUploadResult = Iperf3Result(
                direction = Iperf3Direction.UPLOAD,
                port = 15201,
                resolvedTarget = target,
                durationSec = 2.0,
                bytesTransferred = 25_000_000L,
                clientMbps = 100.0,
                peakMbps = 110.0,
                success = true,
                intervals = listOf(
                    Iperf3IntervalSample(1, 0.0, 1.0, 12_500_000L, 100.0),
                    Iperf3IntervalSample(2, 1.0, 2.0, 12_500_000L, 100.0),
                ),
            ),
            proxyNetwork = ClientNetworkInfo(
                carrier = ClientCarrier.OVERSEAS,
                carrierDisplayName = "海外网络",
                ipFamily = IpFamily.IPV6,
                maskedIp = "240e:3b0::****",
                rawIp = "240e:3b0:1234::5678",
            ),
        )
    }

    @Test
    fun `markdown formatter and shared sanitizer scrub OkHttp ConnectException target IPv4 PTRs ports and client IPs in both styles`() {
        val classifiedHttp = HttpLatencyRunner.classifyProbeException(
            url = "https://node.example.com:8443/",
            throwable = ConnectException("Failed to connect to node.example.com/203.0.113.42:8443"),
        )
        assertEquals(NetBenchErrorCategory.CONNECTION_REFUSED, classifiedHttp.errorCategory)
        assertEquals("连接被拒绝", classifiedHttp.errorMessage)
        assertTrue(classifiedHttp.rawDiagnostic!!.contains("203.0.113.42"))

        // Also test legacy/direct HttpLatencyResult constructed with raw OkHttp message and null errorCategory
        val rawHttp = HttpLatencyResult(
            url = "https://203.0.113.42:8443/",
            success = false,
            errorMessage = "Failed to connect to node.example.com/203.0.113.42:8443",
        )

        for (httpInput in listOf(classifiedHttp, rawHttp)) {
            val report = buildAdversarialIpv4Report(httpInput)
            val sanitized = NetBenchReportSanitizer.sanitize(
                report = report,
                maskTargetIp = true,
                includeClientNetwork = true,
            )

            assertEquals("连接被拒绝", sanitized.httpProbe?.publicErrorMessage)
            assertEquals("连接被拒绝", sanitized.tcpPing?.publicErrorMessage)
            assertEquals("网络不可达", sanitized.icmpPing?.publicErrorMessage)
            assertEquals("连接已重置", sanitized.iperfDownload?.publicErrorMessage)
            assertNull(sanitized.target.exposedPort)
            assertNull(sanitized.tcpPing?.exposedPort)
            assertNull(sanitized.iperfDownload?.exposedPort)

            val fullMd = NetBenchMarkdownFormatter.format(
                report = report,
                options = NetBenchMarkdownOptions(
                    style = NetBenchMarkdownStyle.FULL_TABLE,
                    maskTargetIp = true,
                    includeClientNetwork = true,
                    timestampLabel = "2026-11-21 20:15",
                ),
            )
            val replyMd = NetBenchMarkdownFormatter.format(
                report = report,
                options = NetBenchMarkdownOptions(
                    style = NetBenchMarkdownStyle.REPLY_CARD,
                    maskTargetIp = true,
                    includeClientNetwork = true,
                    timestampLabel = "2026-11-21 20:15",
                ),
            )

            assertTrue(fullMd.contains("连接被拒绝"))
            assertTrue(fullMd.contains("网络不可达"))
            assertTrue(fullMd.contains("连接已重置"))
            assertTrue(fullMd.contains("203.0.113.*"))
            assertTrue(replyMd.contains("失败 (连接被拒绝)"))
            assertTrue(replyMd.contains("失败 (连接已重置)"))

            val forbiddenTokens = listOf(
                "203.0.113.42",
                "198.51.100.77",
                "203.0.113.1",
                "203-0-113-42",
                "42.113.0.203",
                "8443",
                "15201",
                "180.168.12.88",
                "180.168.*.*",
                "240e:3b0:1234::5678",
                "240e:3b0::****",
                "Failed to connect",
                "ECONNREFUSED",
                "Destination Host Unreachable",
            )
            for (secret in forbiddenTokens) {
                assertFalse("FULL_TABLE leaked '$secret':\n$fullMd", fullMd.contains(secret, ignoreCase = true))
                assertFalse("REPLY_CARD leaked '$secret':\n$replyMd", replyMd.contains(secret, ignoreCase = true))
            }
        }
    }

    @Test
    fun `markdown formatter masks compressed uncompressed and IPv4-mapped IPv6 target addresses across all fields`() {
        // Simulate OpenJDK Inet6Address.getHostAddress() returning uncompressed form while input was compressed
        val targetV6Compressed = "2001:db8:85a3::8a2e:370:7334"
        val targetV6JavaShort = "2001:db8:85a3:0:0:8a2e:370:7334"
        val targetV6FullPadded = "2001:0db8:85a3:0000:0000:8a2e:0370:7334"

        val resolved = ResolvedTarget(
            displayHost = targetV6Compressed,
            resolvedIp = targetV6JavaShort,
            ipFamily = IpFamily.IPV6,
            allResolvedIps = listOf(targetV6JavaShort, "::ffff:203.0.113.42"),
        )
        assertFalse("IPv6 literal with compressed vs uncompressed mismatch must NOT be treated as a domain", resolved.isDomain)

        val report = NetBenchReport(
            targetInput = "[$targetV6Compressed]:9443",
            resolvedTarget = resolved,
            tcpPort = 9443,
            httpLatencyResult = HttpLatencyResult(
                url = "https://[$targetV6Compressed]:9443/",
                success = false,
                errorMessage = "SSLHandshakeException: Hostname [$targetV6FullPadded]:9443 not verified",
            ),
            ipIntelligence = IpIntelligenceReport(
                ip = targetV6Compressed,
                ipFamily = IpFamily.IPV6,
                countryCode = "DE",
                countryName = "Germany",
                regionName = "Hesse",
                cityName = "Frankfurt",
                asn = "AS24940",
                orgName = "Hetzner ($targetV6FullPadded)",
                ispName = "Hetzner Online",
                usageType = IpUsageType.HOSTING_DATACENTER,
                originType = IpOriginType.NATIVE,
                rirCountryCode = "DE",
                rirAuthority = "RIPE",
                originEvidence = "原生 IPv6 ($targetV6Compressed / $targetV6FullPadded / ::ffff:203.0.113.42 / 4.3.3.7.0.7.3.0.e.2.a.8.0.0.0.0.0.0.0.0.3.a.5.8.8.b.d.0.1.0.0.2.ip6.arpa)",
            ),
            iperfDownloadResult = Iperf3Result(
                direction = Iperf3Direction.DOWNLOAD,
                port = 5201,
                resolvedTarget = resolved,
                durationSec = 2.0,
                bytesTransferred = 50_000_000L,
                clientMbps = 200.0,
                peakMbps = 210.0,
                success = true,
                intervals = listOf(
                    Iperf3IntervalSample(1, 0.0, 1.0, 25_000_000L, 200.0),
                    Iperf3IntervalSample(2, 1.0, 2.0, 25_000_000L, 200.0),
                ),
            ),
        )

        for (style in NetBenchMarkdownStyle.entries) {
            val md = NetBenchMarkdownFormatter.format(
                report = report,
                options = NetBenchMarkdownOptions(
                    style = style,
                    maskTargetIp = true,
                    includeClientNetwork = true,
                    timestampLabel = "2026-11-21 20:30",
                ),
            )
            assertTrue(md.contains("2001:db8:85a3::****"))
            assertTrue(md.contains("TLS 握手失败"))
            for (forbidden in listOf(
                targetV6Compressed,
                targetV6JavaShort,
                targetV6FullPadded,
                "8a2e",
                "7334",
                "203.0.113.42",
                "9443",
                "5201",
                "4.3.3.7.0.7.3.0",
            )) {
                assertFalse("$style leaked IPv6/port '$forbidden':\n$md", md.contains(forbidden, ignoreCase = true))
            }
        }
    }

    @Test
    fun `sanitizer masks reverse-DNS PTR target hostnames and respects includeClientNetwork false`() {
        val ptrHost = "ec2-203-0-113-42.ap-east-1.compute.amazonaws.com"
        val resolved = ResolvedTarget(
            displayHost = ptrHost,
            resolvedIp = "203.0.113.42",
            ipFamily = IpFamily.IPV4,
            dnsDurationMs = 11.2,
        )
        val report = NetBenchReport(
            targetInput = ptrHost,
            resolvedTarget = resolved,
            tcpPort = 443,
            clientNetwork = ClientNetworkInfo(
                carrier = ClientCarrier.CHINA_UNICOM,
                carrierDisplayName = "中国联通",
                ipFamily = IpFamily.IPV4,
                regionName = "北京",
                asn = "AS4837",
                maskedIp = "123.125.*.*",
                rawIp = "123.125.10.20",
            ),
            tcpPingSummary = TcpPingSummary(
                resolvedTarget = resolved,
                port = 443,
                sent = 4,
                received = 4,
                lossPercent = 0.0,
                minMs = 40.0,
                avgMs = 42.0,
                maxMs = 45.0,
                jitterMdevMs = 1.5,
            ),
        )

        val sanitizedNoClient = NetBenchReportSanitizer.sanitize(
            report = report,
            maskTargetIp = true,
            includeClientNetwork = false,
        )
        assertNull(sanitizedNoClient.clientEnv)
        assertEquals("ec2-203-0-113-*.ap-east-1.compute.amazonaws.com", sanitizedNoClient.target.displayHost)

        val md = NetBenchMarkdownFormatter.formatSanitized(
            sanitized = sanitizedNoClient,
            rawReportForDefenseInDepth = report,
        )
        assertTrue(md.contains("ec2-203-0-113-*"))
        assertFalse(md.contains("203-0-113-42"))
        assertFalse(md.contains("203.0.113.42"))
        assertFalse(md.contains("中国联通"))
        assertFalse(md.contains("AS4837"))
        assertFalse(md.contains("123.125.10.20"))
    }
}
