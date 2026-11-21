package com.nodeseek.app.netbench

import com.nodeseek.app.data.netbench.HttpLatencyRunner
import com.nodeseek.app.domain.netbench.ClientCarrier
import com.nodeseek.app.domain.netbench.ClientNetworkInfo
import com.nodeseek.app.domain.netbench.HttpLatencyResult
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
import com.nodeseek.app.domain.netbench.NetBenchReportCardKind
import com.nodeseek.app.domain.netbench.NetBenchReportSanitizer
import com.nodeseek.app.domain.netbench.ResolvedTarget
import com.nodeseek.app.domain.netbench.TcpPingSample
import com.nodeseek.app.domain.netbench.TcpPingSummary
import com.nodeseek.app.ui.netbench.AndroidNetBenchCardImageGenerator
import com.nodeseek.app.ui.netbench.IpIntelligenceCardModel
import com.nodeseek.app.ui.netbench.Iperf3CardModel
import com.nodeseek.app.ui.netbench.LatencyCardModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class NetBenchReportCardImageRendererTest {

    private fun buildFullAdversarialReport(
        resolvedTarget: ResolvedTarget,
        httpResult: HttpLatencyResult,
        ipLiteralForIntel: String,
        ipFamily: IpFamily,
    ): NetBenchReport = NetBenchReport(
        targetInput = "${resolvedTarget.displayHost}:8443",
        resolvedTarget = resolvedTarget,
        tcpPort = 8443,
        clientNetwork = ClientNetworkInfo(
            carrier = ClientCarrier.CHINA_TELECOM,
            carrierDisplayName = "中国电信",
            ipFamily = IpFamily.IPV4,
            regionName = "上海",
            asn = "AS4134",
            maskedIp = "180.168.*.*",
            rawIp = "180.168.12.88",
            transportLabel = "Wi-Fi",
        ),
        icmpPingSummary = IcmpPingSummary(
            resolvedTarget = resolvedTarget,
            sent = 4,
            received = 0,
            lossPercent = 100.0,
            minMs = null,
            avgMs = null,
            maxMs = null,
            jitterMdevMs = null,
            boundInterfaceName = "wlan0 ($ipLiteralForIntel)",
            samples = listOf(
                IcmpPingSample(1, false, errorMessage = "From 203.0.113.1 Destination Host Unreachable ($ipLiteralForIntel)"),
                IcmpPingSample(2, false, errorMessage = "From 203.0.113.1 Destination Host Unreachable ($ipLiteralForIntel)"),
                IcmpPingSample(3, false, errorMessage = "From 203.0.113.1 Destination Host Unreachable ($ipLiteralForIntel)"),
                IcmpPingSample(4, false, errorMessage = "From 203.0.113.1 Destination Host Unreachable ($ipLiteralForIntel)"),
            ),
            errorMessage = "目标网络不可达 ($ipLiteralForIntel)",
        ),
        tcpPingSummary = TcpPingSummary(
            resolvedTarget = resolvedTarget,
            port = 8443,
            sent = 4,
            received = 4,
            lossPercent = 0.0,
            minMs = 31.2,
            avgMs = 34.5,
            maxMs = 39.0,
            jitterMdevMs = 2.4,
            samples = listOf(
                TcpPingSample(1, true, 31.2),
                TcpPingSample(2, true, 33.8),
                TcpPingSample(3, true, 39.0),
                TcpPingSample(4, true, 34.0),
            ),
        ),
        httpLatencyResult = httpResult,
        ipIntelligence = IpIntelligenceReport(
            ip = ipLiteralForIntel,
            ipFamily = ipFamily,
            countryCode = "HK",
            countryName = "Hong Kong",
            regionName = "Central",
            cityName = "Hong Kong",
            asn = "AS4809",
            orgName = "CN2 GIA ($ipLiteralForIntel)",
            ispName = "DMIT ($ipLiteralForIntel)",
            usageType = IpUsageType.HOSTING_DATACENTER,
            originType = IpOriginType.NATIVE,
            rirCountryCode = "HK",
            rirAuthority = "APNIC ($ipLiteralForIntel)",
            originEvidence = "原生 IP ($ipLiteralForIntel · PTR ec2-203-0-113-42.compute.amazonaws.com · 42.113.0.203.in-addr.arpa)",
            geoSource = "ipwho.is",
            whoisSource = "RIPEstat",
        ),
        iperfDownloadResult = Iperf3Result(
            direction = Iperf3Direction.DOWNLOAD,
            port = 15201,
            resolvedTarget = resolvedTarget,
            durationSec = 4.0,
            bytesTransferred = 100_000_000L,
            clientMbps = 200.0,
            serverMbps = 201.5,
            peakMbps = 215.0,
            retransmits = 0L,
            success = true,
            parallelStreams = 4,
            intervals = listOf(
                Iperf3IntervalSample(1, 0.0, 1.0, 25_000_000L, 200.0, streams = 4),
                Iperf3IntervalSample(2, 1.0, 2.0, 25_000_000L, 200.0, streams = 4),
                Iperf3IntervalSample(3, 2.0, 3.0, 26_875_000L, 215.0, streams = 4),
                Iperf3IntervalSample(4, 3.0, 4.0, 23_125_000L, 185.0, streams = 4),
            ),
        ),
        iperfUploadResult = Iperf3Result(
            direction = Iperf3Direction.UPLOAD,
            port = 15201,
            resolvedTarget = resolvedTarget,
            durationSec = 4.0,
            bytesTransferred = 50_000_000L,
            clientMbps = 100.0,
            peakMbps = 108.0,
            retransmits = 1L,
            success = true,
            parallelStreams = 4,
            intervals = listOf(
                Iperf3IntervalSample(1, 0.0, 2.0, 25_000_000L, 100.0, streams = 4),
                Iperf3IntervalSample(2, 2.0, 4.0, 25_000_000L, 100.0, streams = 4),
            ),
        ),
    )

    @Test
    fun `all 4 card presentation models and Canvas draw pipelines share sanitized data with Markdown and leak zero IPv4 addresses`() {
        val targetV4 = ResolvedTarget(
            displayHost = "node.example.com",
            resolvedIp = "203.0.113.42",
            ipFamily = IpFamily.IPV4,
            allResolvedIps = listOf("203.0.113.42", "198.51.100.99"),
            dnsDurationMs = 10.5,
        )

        val exceptionCases = listOf(
            ConnectException("Failed to connect to node.example.com/203.0.113.42:443") to "连接被拒绝",
            ConnectException("Failed to connect to /203.0.113.42:443") to "连接被拒绝",
            SocketTimeoutException("failed to connect to /203.0.113.42 (port 443) after 5000ms") to "连接超时",
            SSLHandshakeException("Handshake failed on https://203.0.113.42:8443/") to "TLS 握手失败",
            UnknownHostException("Unable to resolve host \"node.example.com\"") to "DNS 解析失败",
            NoRouteToHostException("No route to host: /203.0.113.42:8443") to "网络不可达",
            SocketException("Connection reset on 203.0.113.42:8443") to "连接已重置",
        )

        for ((throwable, expectedCategoryLabel) in exceptionCases) {
            val httpRes = HttpLatencyRunner.classifyProbeException(
                url = "https://node.example.com:8443/",
                throwable = throwable,
            )
            val report = buildFullAdversarialReport(
                resolvedTarget = targetV4,
                httpResult = httpRes,
                ipLiteralForIntel = "203.0.113.42",
                ipFamily = IpFamily.IPV4,
            )

            // Shared SanitizedNetBenchReport consumed by BOTH Markdown and Card Image Generator
            val sharedSanitized = NetBenchReportSanitizer.sanitize(
                report = report,
                maskTargetIp = true,
                includeClientNetwork = true,
            )
            val markdown = NetBenchMarkdownFormatter.formatSanitized(
                sanitized = sharedSanitized,
                rawReportForDefenseInDepth = report,
                options = NetBenchMarkdownOptions(
                    style = NetBenchMarkdownStyle.FULL_TABLE,
                    maskTargetIp = true,
                    includeClientNetwork = true,
                    timestampLabel = "2026-11-21 21:00",
                ),
            )
            val cards = AndroidNetBenchCardImageGenerator.buildAllCardPresentations(sharedSanitized)
            assertEquals("All 4 card kinds must be generated", 4, cards.size)

            val tcpCard = cards.first { it.kind == NetBenchReportCardKind.TCP_PING } as LatencyCardModel
            assertEquals("HTTP 探测", tcpCard.httpFooterTitle)
            assertEquals(expectedCategoryLabel, tcpCard.httpFooterValueText)
            assertTrue(tcpCard.httpFooterIsError)
            assertTrue("Markdown must share exact error label '$expectedCategoryLabel'", markdown.contains(expectedCategoryLabel))

            val icmpCard = cards.first { it.kind == NetBenchReportCardKind.ICMP_PING } as LatencyCardModel
            assertEquals("网络不可达", icmpCard.stats.first().value)

            val ipCard = cards.first { it.kind == NetBenchReportCardKind.IP_INTELLIGENCE } as IpIntelligenceCardModel
            assertEquals("203.0.113.* · IPv4", ipCard.subtitle)

            val iperfCard = cards.first { it.kind == NetBenchReportCardKind.IPERF3 } as Iperf3CardModel
            assertTrue(iperfCard.subtitle.contains("已隐藏端口"))

            val forbiddenTokens = listOf(
                "203.0.113.42",
                "198.51.100.99",
                "203.0.113.1",
                "203-0-113-42",
                "42.113.0.203",
                "180.168.12.88",
                "180.168.*.*",
                "8443",
                "15201",
                "Failed to connect",
            )

            for (card in cards) {
                val modelTexts = card.allDisplayTexts()
                val drawnLightTexts = AndroidNetBenchCardImageGenerator.extractRenderedCardTexts(
                    kind = card.kind,
                    report = report,
                    maskTargetIp = true,
                    includeClientNetwork = true,
                    darkTheme = false,
                )
                val drawnDarkTexts = AndroidNetBenchCardImageGenerator.extractRenderedCardTexts(
                    kind = card.kind,
                    report = report,
                    maskTargetIp = true,
                    includeClientNetwork = true,
                    darkTheme = true,
                )
                assertTrue("Canvas text extraction must capture drawn strings for ${card.kind}", drawnLightTexts.isNotEmpty())
                assertEquals(drawnLightTexts, drawnDarkTexts)

                val combined = (modelTexts + drawnLightTexts + listOf(card.fingerprintPayload(false))).joinToString("\n")
                for (secret in forbiddenTokens) {
                    assertFalse(
                        "Card ${card.kind} leaked '$secret' under ${throwable.javaClass.simpleName}:\n$combined",
                        combined.contains(secret, ignoreCase = true),
                    )
                }
            }
            for (secret in forbiddenTokens) {
                assertFalse("Markdown leaked '$secret':\n$markdown", markdown.contains(secret, ignoreCase = true))
            }
        }
    }

    @Test
    fun `card renderer scrubs raw legacy HttpLatencyResult errorMessage and masks all IPv6 target addresses`() {
        val v6Compressed = "2001:db8:85a3::8a2e:370:7334"
        val v6Uncompressed = "2001:0db8:85a3:0000:0000:8a2e:0370:7334"
        val targetV6 = ResolvedTarget(
            displayHost = "v6-node.example.com",
            resolvedIp = "2001:db8:85a3:0:0:8a2e:370:7334",
            ipFamily = IpFamily.IPV6,
            allResolvedIps = listOf("2001:db8:85a3:0:0:8a2e:370:7334", "::ffff:203.0.113.42"),
        )
        // Construct HttpLatencyResult directly with raw OkHttp message (no explicit errorCategory)
        val rawHttpFailure = HttpLatencyResult(
            url = "https://[$v6Compressed]:8443/",
            success = false,
            errorMessage = "Failed to connect to v6-node.example.com/[$v6Uncompressed]:8443",
        )
        val report = buildFullAdversarialReport(
            resolvedTarget = targetV6,
            httpResult = rawHttpFailure,
            ipLiteralForIntel = v6Compressed,
            ipFamily = IpFamily.IPV6,
        )

        val cards = AndroidNetBenchCardImageGenerator.buildAllCardPresentations(
            report = report,
            maskTargetIp = true,
            includeClientNetwork = false,
        )
        assertEquals(4, cards.size)

        val ipCard = cards.first { it.kind == NetBenchReportCardKind.IP_INTELLIGENCE } as IpIntelligenceCardModel
        assertEquals("2001:db8:85a3::**** · IPv6", ipCard.subtitle)
        assertNull("includeClientNetwork=false must omit client footer on IP card", ipCard.clientFooterText)

        val tcpCard = cards.first { it.kind == NetBenchReportCardKind.TCP_PING } as LatencyCardModel
        assertEquals("连接被拒绝", tcpCard.httpFooterValueText)

        for (kind in NetBenchReportCardKind.entries) {
            val drawnTexts = AndroidNetBenchCardImageGenerator.extractRenderedCardTexts(
                kind = kind,
                report = report,
                maskTargetIp = true,
                includeClientNetwork = false,
            ).joinToString("\n")
            for (forbidden in listOf(
                v6Compressed,
                v6Uncompressed,
                "2001:db8:85a3:0:0:8a2e:370:7334",
                "8a2e",
                "7334",
                "203.0.113.42",
                "8443",
                "15201",
                "中国电信",
                "180.168.12.88",
            )) {
                assertFalse("Card $kind leaked '$forbidden':\n$drawnTexts", drawnTexts.contains(forbidden, ignoreCase = true))
            }
        }

        // Verify TCP_PING card cache key rotates when HTTP probe outcome changes (e.g. refused vs TLS failure)
        val tlsFailureReport = report.copy(
            httpLatencyResult = HttpLatencyResult(
                url = "https://[$v6Compressed]:8443/",
                success = false,
                errorMessage = "SSLHandshakeException: Handshake failed",
            ),
        )
        val refusedKey = AndroidNetBenchCardImageGenerator.planCards(
            report = report,
            maskTargetIp = true,
            includeClientNetwork = false,
            darkTheme = false,
        ).toMap()[NetBenchReportCardKind.TCP_PING]
        val tlsKey = AndroidNetBenchCardImageGenerator.planCards(
            report = tlsFailureReport,
            maskTargetIp = true,
            includeClientNetwork = false,
            darkTheme = false,
        ).toMap()[NetBenchReportCardKind.TCP_PING]
        assertNotNull(refusedKey)
        assertNotNull(tlsKey)
        assertNotEquals("TCP_PING cache key must rotate when HTTP probe error category changes", refusedKey, tlsKey)
    }
}
