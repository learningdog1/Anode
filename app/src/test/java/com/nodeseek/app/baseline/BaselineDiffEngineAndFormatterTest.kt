package com.nodeseek.app.baseline

import com.nodeseek.app.domain.baseline.BaselineBandwidthMetrics
import com.nodeseek.app.domain.baseline.BaselineComparisonFormatter
import com.nodeseek.app.domain.baseline.BaselineDiffEngine
import com.nodeseek.app.domain.baseline.BaselineIpMetrics
import com.nodeseek.app.domain.baseline.BaselineLatencyMetrics
import com.nodeseek.app.domain.baseline.BaselineOverallVerdict
import com.nodeseek.app.domain.baseline.BaselineRoutingMetrics
import com.nodeseek.app.domain.baseline.BaselineSourceType
import com.nodeseek.app.domain.baseline.BenchmarkBaselineSnapshot
import com.nodeseek.app.domain.baseline.MetricTrend
import com.nodeseek.app.domain.baseline.StreamingChangeType
import com.nodeseek.app.domain.baseline.StreamingPlatformResult
import com.nodeseek.app.domain.baseline.StreamingUnlockStatus
import com.nodeseek.app.domain.netbench.BackboneRouteTag
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.IpOriginType
import com.nodeseek.app.ui.baseline.sanitizeBaselineComparisonCardDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineDiffEngineAndFormatterTest {

    @Test
    fun `compare detects severe bandwidth shrinkage, streaming unlock loss, routing downgrade, and US detour`() {
        val before = BenchmarkBaselineSnapshot(
            id = "base_1",
            label = "购入首测",
            targetKey = "host:hk.example.com",
            hostDisplay = "203.0.113.42",
            resolvedIp = "203.0.113.42",
            sourceType = BaselineSourceType.SSH_BENCHMARK,
            capturedAtEpochMillis = 1_700_000_000_000L,
            bandwidth = BaselineBandwidthMetrics(downloadMbps = 500.0, uploadMbps = 400.0),
            latency = BaselineLatencyMetrics(icmpAvgMs = 35.0, icmpLossPercent = 0.0),
            routing = BaselineRoutingMetrics(
                reverseRouteSignature = "CN2 GIA + 9929",
                reverseBackboneTags = listOf(BackboneRouteTag.CN2_GIA, BackboneRouteTag.CU_9929),
                reverseHopCount = 9,
                reverseTerminalMs = 35.0,
                reversePeakHopLossPercent = 0.0,
                reverseHasRealPacketLoss = false,
                threeNetBackrouteSummary = mapOf("电信" to "CN2 GIA", "联通" to "9929"),
                isDetourSuspected = false,
            ),
            ipMetrics = BaselineIpMetrics(ipFamily = IpFamily.IPV4, originType = IpOriginType.NATIVE),
            streamingUnlocks = listOf(
                StreamingPlatformResult("netflix", "Netflix", StreamingUnlockStatus.UNLOCKED, "US"),
                StreamingPlatformResult("chatgpt", "ChatGPT", StreamingUnlockStatus.UNLOCKED, "US"),
                StreamingPlatformResult("disney", "Disney+", StreamingUnlockStatus.LOCKED),
            ),
        )

        val after = before.copy(
            id = "cur_1",
            label = "三个月后复测",
            capturedAtEpochMillis = 1_700_000_000_000L + 90L * 86_400_000L,
            bandwidth = BaselineBandwidthMetrics(downloadMbps = 150.0, uploadMbps = 360.0),
            latency = BaselineLatencyMetrics(icmpAvgMs = 175.0, icmpLossPercent = 12.0),
            routing = BaselineRoutingMetrics(
                reverseRouteSignature = "163 骨干 + 4837 + Cogent",
                reverseBackboneTags = listOf(BackboneRouteTag.CT_163, BackboneRouteTag.CU_4837, BackboneRouteTag.COGENT),
                reverseHopCount = 15,
                reverseTerminalMs = 175.0,
                reversePeakHopLossPercent = 12.0,
                reverseHasRealPacketLoss = true,
                threeNetBackrouteSummary = mapOf("电信" to "163 骨干 (绕美)", "联通" to "4837"),
                isDetourSuspected = true,
                detourReason = "回程经由美国 (US) 绕路",
            ),
            ipMetrics = BaselineIpMetrics(ipFamily = IpFamily.IPV4, originType = IpOriginType.BROADCAST),
            streamingUnlocks = listOf(
                StreamingPlatformResult("netflix", "Netflix", StreamingUnlockStatus.ORIGINALS_ONLY),
                StreamingPlatformResult("chatgpt", "ChatGPT", StreamingUnlockStatus.LOCKED),
                StreamingPlatformResult("disney", "Disney+", StreamingUnlockStatus.UNLOCKED, "HK"),
            ),
        )

        val diff = BaselineDiffEngine.compare(before, after)

        assertEquals(BaselineOverallVerdict.SIGNIFICANT_DEGRADATION, diff.overallVerdict)
        assertEquals(90L, diff.elapsedDays)
        assertTrue(diff.ipOriginChanged)

        val dlDelta = diff.bandwidthDeltas.first { it.metricLabel == "下行带宽" }
        assertEquals(-350.0, dlDelta.deltaMbps!!, 0.01)
        assertEquals(-70.0, dlDelta.deltaPercent!!, 0.01)
        assertEquals(MetricTrend.DEGRADED_SEVERE, dlDelta.trend)

        val reverseShift = diff.routingShifts.first { it.directionLabel == "回程路由" }
        assertTrue(reverseShift.detourIntroduced)
        assertTrue(reverseShift.backboneDowngraded)
        assertEquals(MetricTrend.DEGRADED_SEVERE, reverseShift.trend)

        val netflixDiff = diff.streamingDiffs.first { it.platformKey == "netflix" }
        assertEquals(StreamingChangeType.DOWNGRADED_TO_ORIGINALS, netflixDiff.changeType)
        val gptDiff = diff.streamingDiffs.first { it.platformKey == "chatgpt" }
        assertEquals(StreamingChangeType.LOST_UNLOCK, gptDiff.changeType)
        val disneyDiff = diff.streamingDiffs.first { it.platformKey == "disney" }
        assertEquals(StreamingChangeType.GAINED_UNLOCK, disneyDiff.changeType)

        assertTrue(diff.highlightBullets.any { it.contains("缩水") && it.contains("-70%") })
        assertTrue(diff.highlightBullets.any { it.contains("Netflix") && it.contains("自制剧") })
        assertTrue(diff.highlightBullets.any { it.contains("ChatGPT") })

        val mdMasked = BaselineComparisonFormatter.formatMarkdown(diff, maskTargetIp = true)
        assertFalse("Raw IPv4 must be masked", mdMasked.contains("203.0.113.42"))
        assertTrue(mdMasked.contains("203.0.113.*") || mdMasked.contains("203.0.*.*"))
        assertTrue(mdMasked.contains("Before vs After"))
    }

    @Test
    fun `compare guards zero baseline denominators and handles cross-pipeline partial overlap`() {
        val sshBefore = BenchmarkBaselineSnapshot(
            id = "ssh_1",
            label = "SSH 基线",
            targetKey = "server:s1",
            hostDisplay = "2001:db8:abcd:1234::1",
            resolvedIp = "2001:db8:abcd:1234::1",
            sourceType = BaselineSourceType.SSH_BENCHMARK,
            capturedAtEpochMillis = 1_700_000_000_000L,
            bandwidth = BaselineBandwidthMetrics(downloadMbps = 0.0, geekbenchSingle = 1200),
            latency = BaselineLatencyMetrics(icmpAvgMs = 40.0),
        )
        val netbenchAfter = BenchmarkBaselineSnapshot(
            id = "nb_1",
            label = "手机测机",
            targetKey = "server:s1",
            hostDisplay = "2001:db8:abcd:1234::1",
            resolvedIp = "2001:db8:abcd:1234::1",
            sourceType = BaselineSourceType.MOBILE_NETBENCH,
            capturedAtEpochMillis = 1_700_000_000_000L + 5L * 86_400_000L,
            bandwidth = BaselineBandwidthMetrics(downloadMbps = 250.0, uploadMbps = 180.0),
            latency = BaselineLatencyMetrics(icmpAvgMs = 42.0, tcpAvgMs = 45.0),
        )

        val diff = BaselineDiffEngine.compare(sshBefore, netbenchAfter)
        val dlDelta = diff.bandwidthDeltas.first { it.metricLabel == "下行带宽" }
        assertNull(dlDelta.deltaPercent)
        assertEquals(MetricTrend.IMPROVED, dlDelta.trend)
        // Upload only exists on netbenchAfter -> must not emit bogus delta
        assertFalse(diff.bandwidthDeltas.any { it.metricLabel == "上行带宽" })
        // TCP only exists on netbenchAfter -> must not emit bogus delta
        assertFalse(diff.latencyDeltas.any { it.metricLabel == "TCP 平均延迟" })

        val md = BaselineComparisonFormatter.formatMarkdown(diff, maskTargetIp = true)
        assertFalse("Raw IPv6 must not leak", md.contains("2001:db8:abcd:1234::1"))
    }

    @Test
    fun `city names like ShenZhen Direct, Chengdu, and Hefei do not false-trigger HE detour`() {
        val before = BenchmarkBaselineSnapshot(
            id = "city_1",
            label = "基线",
            targetKey = "server:c1",
            hostDisplay = "hk.example.com",
            sourceType = BaselineSourceType.SSH_BENCHMARK,
            capturedAtEpochMillis = 1_700_000_000_000L,
            routing = BaselineRoutingMetrics(
                threeNetBackrouteSummary = mapOf(
                    "电信" to "GuangZhou Direct",
                    "联通" to "ShangHai Direct",
                    "移动" to "BeiJing Direct",
                ),
                isDetourSuspected = false,
            ),
        )
        val after = before.copy(
            id = "city_2",
            capturedAtEpochMillis = 1_700_000_000_000L + 7L * 86_400_000L,
            routing = BaselineRoutingMetrics(
                threeNetBackrouteSummary = mapOf(
                    "电信" to "ShenZhen Direct",
                    "联通" to "Chengdu Direct",
                    "移动" to "Hefei Direct",
                ),
                isDetourSuspected = false,
            ),
        )

        val diff = BaselineDiffEngine.compare(before, after)
        assertTrue(
            "City names containing 'he' must NOT set detourIntroduced=true",
            diff.routingShifts.none { it.detourIntroduced },
        )
        assertEquals(BaselineOverallVerdict.STABLE, diff.overallVerdict)

        // Genuine HE.net token must still trigger detour
        val afterRealHe = after.copy(
            routing = BaselineRoutingMetrics(
                threeNetBackrouteSummary = mapOf("电信" to "HE.net (AS6939)"),
                isDetourSuspected = false,
            ),
        )
        val diffHe = BaselineDiffEngine.compare(before, afterRealHe)
        assertTrue(diffHe.routingShifts.any { it.directionLabel == "电信 回程" && it.detourIntroduced })
        assertEquals(BaselineOverallVerdict.SIGNIFICANT_DEGRADATION, diffHe.overallVerdict)
    }

    @Test
    fun `cross-pipeline SSH baseline vs Mobile NetBench forward-only US detour surfaces SIGNIFICANT_DEGRADATION`() {
        val sshBaseline = BenchmarkBaselineSnapshot(
            id = "ssh_base",
            label = "SSH 购入基线",
            targetKey = "server:sg1",
            hostDisplay = "203.0.113.10",
            resolvedIp = "203.0.113.10",
            sourceType = BaselineSourceType.SSH_BENCHMARK,
            capturedAtEpochMillis = 1_700_000_000_000L,
            bandwidth = BaselineBandwidthMetrics(downloadMbps = 300.0),
            latency = BaselineLatencyMetrics(icmpAvgMs = 55.0),
            routing = BaselineRoutingMetrics(
                reverseRouteSignature = "CN2 GIA",
                reverseBackboneTags = listOf(BackboneRouteTag.CN2_GIA),
                isDetourSuspected = false,
            ),
        )
        val netbenchCurrent = BenchmarkBaselineSnapshot(
            id = "nb_curr",
            label = "手机测机复测",
            targetKey = "server:sg1",
            hostDisplay = "203.0.113.10",
            resolvedIp = "203.0.113.10",
            sourceType = BaselineSourceType.MOBILE_NETBENCH,
            capturedAtEpochMillis = 1_700_000_000_000L + 30L * 86_400_000L,
            bandwidth = BaselineBandwidthMetrics(downloadMbps = 290.0),
            latency = BaselineLatencyMetrics(icmpAvgMs = 60.0),
            routing = BaselineRoutingMetrics(
                forwardRouteSignature = "Cogent (US)",
                forwardBackboneTags = listOf(BackboneRouteTag.COGENT),
                isDetourSuspected = true,
                detourReason = "去程经由美国 (US) 绕路",
            ),
        )

        val diff = BaselineDiffEngine.compare(sshBaseline, netbenchCurrent)
        assertEquals(BaselineOverallVerdict.SIGNIFICANT_DEGRADATION, diff.overallVerdict)
        assertTrue(diff.routingShifts.any { it.detourIntroduced && it.trend == MetricTrend.DEGRADED_SEVERE })
        assertTrue(diff.highlightBullets.any { it.contains("绕") || it.contains("US") })
    }

    @Test
    fun `BaselineComparisonCard display sanitizer masks host_port, highlightBullets, and routingShifts`() {
        val before = BenchmarkBaselineSnapshot(
            id = "card_b",
            label = "Base",
            targetKey = "host:198.51.100.88",
            hostDisplay = "198.51.100.88:5201",
            resolvedIp = "198.51.100.88",
            sourceType = BaselineSourceType.MOBILE_NETBENCH,
            capturedAtEpochMillis = 1_700_000_000_000L,
            routing = BaselineRoutingMetrics(
                forwardRouteSignature = "CN2 GIA (198.51.100.88)",
                forwardBackboneTags = listOf(BackboneRouteTag.CN2_GIA),
                isDetourSuspected = false,
            ),
        )
        val after = before.copy(
            id = "card_a",
            capturedAtEpochMillis = 1_700_000_000_000L + 5L * 86_400_000L,
            routing = BaselineRoutingMetrics(
                forwardRouteSignature = "Cogent via 2001:db8:abcd:1234::99",
                forwardBackboneTags = listOf(BackboneRouteTag.COGENT),
                isDetourSuspected = true,
                detourReason = "经由 198.51.100.88 与 2001:db8:abcd:1234::99 绕美",
            ),
        )
        val diff = BaselineDiffEngine.compare(before, after)
        val masked = sanitizeBaselineComparisonCardDisplay(diff, maskTargetIp = true)

        assertFalse("host:port IPv4 must be masked", masked.safeHost.contains("198.51.100.88"))
        assertTrue(masked.safeHost.contains("198.51.100.*"))
        assertTrue(masked.highlightBullets.none { it.contains("198.51.100.88") || it.contains("2001:db8:abcd:1234::99") })
        assertTrue(
            masked.routingShifts.none {
                (it.beforeRoute?.contains("198.51.100.88") == true) ||
                    (it.afterRoute?.contains("2001:db8:abcd:1234::99") == true) ||
                    it.shiftSummary.contains("198.51.100.88")
            },
        )
    }

    @Test
    fun `compare_nanAndInfinityMetrics_sanitizedToUnchanged`() {
        val base = BenchmarkBaselineSnapshot(
            id = "nan_b",
            label = "Base",
            targetKey = "host:nan.example.com",
            hostDisplay = "nan.example.com",
            resolvedIp = "203.0.113.10",
            sourceType = BaselineSourceType.SSH_BENCHMARK,
            capturedAtEpochMillis = 1_700_000_000_000L,
            bandwidth = BaselineBandwidthMetrics(
                downloadMbps = Double.NaN,
                uploadMbps = 100.0,
            ),
            latency = BaselineLatencyMetrics(
                icmpAvgMs = 20.0,
                icmpLossPercent = Double.POSITIVE_INFINITY,
            ),
            routing = BaselineRoutingMetrics(
                reverseTerminalMs = Double.NaN,
                reversePeakHopLossPercent = Double.POSITIVE_INFINITY,
            ),
        )
        val after = base.copy(
            id = "nan_a",
            capturedAtEpochMillis = 1_700_000_060_000L,
            bandwidth = BaselineBandwidthMetrics(
                downloadMbps = 250.0,
                uploadMbps = Double.POSITIVE_INFINITY,
            ),
            latency = BaselineLatencyMetrics(
                icmpAvgMs = Double.NaN,
                icmpLossPercent = 0.0,
            ),
            routing = BaselineRoutingMetrics(
                reverseTerminalMs = 45.0,
                reversePeakHopLossPercent = 0.0,
            ),
        )

        val diff = BaselineDiffEngine.compare(base, after)
        assertTrue(diff.bandwidthDeltas.isEmpty())
        assertTrue(diff.latencyDeltas.isEmpty())
        diff.routingShifts.forEach { shift ->
            assertNull(shift.latencyDeltaMs)
            assertNull(shift.lossDeltaPercent)
            assertEquals(MetricTrend.UNCHANGED, shift.trend)
        }
    }

    @Test
    fun `isBackboneDowngraded_premiumToEmptyTags_returnsTrue`() {
        assertTrue(
            BaselineDiffEngine.isBackboneDowngraded(
                beforeTags = listOf(BackboneRouteTag.CN2_GIA),
                afterTags = emptyList(),
            ),
        )
        assertFalse(
            BaselineDiffEngine.isBackboneDowngraded(
                beforeTags = emptyList(),
                afterTags = listOf(BackboneRouteTag.CN2_GIA),
            ),
        )
    }
}
