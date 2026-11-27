package com.nodeseek.app.baseline

import com.nodeseek.app.domain.baseline.BaselineBandwidthMetrics
import com.nodeseek.app.domain.baseline.BaselineDiffEngine
import com.nodeseek.app.domain.baseline.BaselineLatencyMetrics
import com.nodeseek.app.domain.baseline.BaselineRoutingMetrics
import com.nodeseek.app.domain.baseline.BaselineSourceType
import com.nodeseek.app.domain.baseline.BenchmarkBaselineSnapshot
import com.nodeseek.app.domain.baseline.StreamingPlatformResult
import com.nodeseek.app.domain.baseline.StreamingUnlockStatus
import com.nodeseek.app.domain.netbench.BackboneRouteTag
import com.nodeseek.app.ui.baseline.AndroidBaselineComparisonCardImageGenerator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineComparisonCardImageRendererTest {

    @Test
    fun `extractRenderedCardTexts renders deltas, verdict, highlights and masks raw IPv4 and IPv6 when maskTargetIp is true`() {
        val before = BenchmarkBaselineSnapshot(
            id = "b_1",
            label = "购入基线",
            targetKey = "host:198.51.100.88",
            hostDisplay = "198.51.100.88",
            resolvedIp = "198.51.100.88",
            sourceType = BaselineSourceType.SSH_BENCHMARK,
            capturedAtEpochMillis = 1_700_000_000_000L,
            bandwidth = BaselineBandwidthMetrics(downloadMbps = 600.0, uploadMbps = 500.0),
            latency = BaselineLatencyMetrics(icmpAvgMs = 30.0, icmpLossPercent = 0.0),
            routing = BaselineRoutingMetrics(
                reverseRouteSignature = "CN2 GIA",
                reverseBackboneTags = listOf(BackboneRouteTag.CN2_GIA),
                isDetourSuspected = false,
            ),
            streamingUnlocks = listOf(
                StreamingPlatformResult("netflix", "Netflix", StreamingUnlockStatus.UNLOCKED, "HK"),
            ),
        )
        val after = before.copy(
            id = "b_2",
            label = "当前实测",
            capturedAtEpochMillis = 1_700_000_000_000L + 60L * 86_400_000L,
            bandwidth = BaselineBandwidthMetrics(downloadMbps = 200.0, uploadMbps = 180.0),
            latency = BaselineLatencyMetrics(icmpAvgMs = 160.0, icmpLossPercent = 8.0),
            routing = BaselineRoutingMetrics(
                reverseRouteSignature = "163 骨干 + Cogent",
                reverseBackboneTags = listOf(BackboneRouteTag.CT_163, BackboneRouteTag.COGENT),
                isDetourSuspected = true,
                detourReason = "回程绕美 (2001:db8:abcd:1234::99)",
            ),
            streamingUnlocks = listOf(
                StreamingPlatformResult("netflix", "Netflix", StreamingUnlockStatus.ORIGINALS_ONLY),
            ),
        )

        val diff = BaselineDiffEngine.compare(before, after)
        val model = AndroidBaselineComparisonCardImageGenerator.buildPresentation(diff, maskTargetIp = true)

        assertTrue(model.title.contains("198.51.100.*"))
        assertFalse(model.title.contains("198.51.100.88"))
        assertTrue(model.verdictBadge.contains("明显劣化"))

        for (dark in listOf(false, true)) {
            val texts = AndroidBaselineComparisonCardImageGenerator.extractRenderedCardTexts(
                diff = diff,
                maskTargetIp = true,
                darkTheme = dark,
            )
            val joined = texts.joinToString("\n")
            assertFalse("Raw IPv4 leaked (dark=$dark)", joined.contains("198.51.100.88"))
            assertFalse("Raw IPv6 leaked (dark=$dark)", joined.contains("2001:db8:abcd:1234::99"))
            assertTrue("Expected masked IP in card texts", joined.contains("198.51.100.*"))
            assertTrue("Expected bandwidth row", joined.contains("下行带宽"))
            assertTrue("Expected detour badge", joined.contains("绕美"))
            assertTrue("Expected streaming unlock row", joined.contains("Netflix"))
        }
    }
}
