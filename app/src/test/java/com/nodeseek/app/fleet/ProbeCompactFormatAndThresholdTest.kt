package com.nodeseek.app.fleet

import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbePanelType
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.ServerTrafficForecast
import com.nodeseek.app.domain.fleet.TrafficResetDaySource
import com.nodeseek.app.ui.fleet.ProbeMetricHeat
import com.nodeseek.app.ui.fleet.ProbeViewMode
import com.nodeseek.app.ui.fleet.ProbeViewModeCodec
import com.nodeseek.app.ui.fleet.buildDenseOfflineSpanLabel
import com.nodeseek.app.ui.fleet.buildProbeCardMetaLine
import com.nodeseek.app.ui.fleet.classifyProbeMetricHeat
import com.nodeseek.app.ui.fleet.formatOfflineDurationCompact
import com.nodeseek.app.ui.fleet.formatProbeBytesTight
import com.nodeseek.app.ui.fleet.formatSpeedDense
import com.nodeseek.app.ui.fleet.formatSpeedParts
import com.nodeseek.app.ui.fleet.formatUptimeCompact
import com.nodeseek.app.ui.fleet.resolveTrafficDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ProbeCompactFormatAndThresholdTest {

    @Test
    fun `classifyProbeMetricHeat follows under-60 normal, 60-to-85 warning, over-85 high`() {
        assertEquals(ProbeMetricHeat.NORMAL, classifyProbeMetricHeat(0f))
        assertEquals(ProbeMetricHeat.NORMAL, classifyProbeMetricHeat(59.9f))
        assertEquals(ProbeMetricHeat.WARNING, classifyProbeMetricHeat(60f))
        assertEquals(ProbeMetricHeat.WARNING, classifyProbeMetricHeat(78f))
        assertEquals(ProbeMetricHeat.WARNING, classifyProbeMetricHeat(85f))
        assertEquals(ProbeMetricHeat.HIGH, classifyProbeMetricHeat(85.1f))
        assertEquals(ProbeMetricHeat.HIGH, classifyProbeMetricHeat(91f))
    }

    @Test
    fun `formatProbeBytesTight produces concise readable labels`() {
        val mib = 1024L * 1024L
        val gib = 1024L * mib
        assertEquals("940M", formatProbeBytesTight(940L * mib))
        assertEquals("2G", formatProbeBytesTight(2L * gib))
        assertEquals("14G", formatProbeBytesTight(14L * gib))
        assertEquals("463G", formatProbeBytesTight(463L * gib))
        assertEquals("1T", formatProbeBytesTight(1024L * gib))
        assertEquals("1.62T", formatProbeBytesTight(1659L * gib))
        assertEquals("20T", formatProbeBytesTight(20L * 1024L * gib))
    }

    @Test
    fun `formatSpeedParts and formatSpeedDense format throughput concisely`() {
        assertEquals("17.5" to "MB/s", formatSpeedParts(18_350_080L))
        assertEquals("17.5M", formatSpeedDense(18_350_080L))
        assertEquals("20.0" to "KB/s", formatSpeedParts(20_480L))
        assertEquals("20K", formatSpeedDense(20_480L))
        assertEquals("0" to "B/s", formatSpeedParts(0L))
        assertEquals("0B", formatSpeedDense(0L))
    }

    @Test
    fun `resolveTrafficDisplayState surfaces forecast tick and overrun warning`() {
        val gib = 1024L * 1024L * 1024L
        val normalSnap = ProbeServerSnapshot(
            panelId = "p1",
            remoteServerId = "1",
            name = "Normal",
            monthlyTransferInBytes = 215L * gib,
            monthlyTransferOutBytes = 248L * gib,
            trafficLimitBytes = 1024L * gib,
            isOnline = true,
        )
        val normalForecast = ServerTrafficForecast(
            probeKey = normalSnap.probeKey,
            usedBytes = 463L * gib,
            limitBytes = 1024L * gib,
            usageRatio = 463.0 / 1024.0,
            anchorResetDay = 1,
            resetDaySource = TrafficResetDaySource.FIRST_OF_MONTH_DEFAULT,
            cycleStartDate = LocalDate.of(2026, 11, 1),
            nextResetDate = LocalDate.of(2026, 12, 1),
            elapsedMillis = 15L * 86_400_000L,
            dailyBurnBytes = 30L * gib,
            projectedCycleUsageRatio = 0.78,
            isAtOrAboveAlertThreshold = false,
            isExhausted = false,
            willExhaustBeforeReset = false,
            predictedExhaustionDate = null,
            exhaustionForecastText = null,
        )
        val normalState = resolveTrafficDisplayState(normalSnap, null, normalForecast)
        assertTrue(normalState.hasQuota)
        assertFalse(normalState.isForecastOver)
        assertEquals(0.78f, normalState.forecastTickFraction!!, 0.001f)
        assertEquals("463G/1T", normalState.cardText)

        val overrunSnap = normalSnap.copy(
            monthlyTransferInBytes = 800L * gib,
            monthlyTransferOutBytes = 859L * gib,
            trafficLimitBytes = 2048L * gib,
        )
        val overrunForecast = normalForecast.copy(
            usedBytes = 1659L * gib,
            limitBytes = 2048L * gib,
            usageRatio = 1659.0 / 2048.0,
            projectedCycleUsageRatio = 1.32,
            isAtOrAboveAlertThreshold = true,
            willExhaustBeforeReset = true,
            exhaustionForecastText = "预计本月 24 号流量会用完",
        )
        val overrunState = resolveTrafficDisplayState(overrunSnap, null, overrunForecast)
        assertTrue(overrunState.isForecastOver)
        assertEquals(1f, overrunState.forecastTickFraction!!, 0.001f)
        assertEquals("1.62T/2T 预计超", overrunState.cardText)

        val unlimitedSnap = normalSnap.copy(
            monthlyTransferInBytes = 0L,
            monthlyTransferOutBytes = 0L,
            trafficLimitBytes = null,
        )
        val unlimitedState = resolveTrafficDisplayState(unlimitedSnap, null, null)
        assertFalse(unlimitedState.hasQuota)
        assertNull(unlimitedState.forecastTickFraction)
    }

    @Test
    fun `offline metadata and view mode codec behave deterministically`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val panel = ProbePanelConfig(
            id = "p-nezha",
            name = "Nezha v1",
            baseUrl = "https://status.example.com",
            panelType = ProbePanelType.NEZHA_V1,
        )
        val nowMs = 1_795_060_800_000L // deterministic timestamp
        val threeHoursAgo = nowMs - 3L * 3_600_000L
        val offlineSnap = ProbeServerSnapshot(
            panelId = "p-nezha",
            remoteServerId = "105",
            name = "RackNerd-LAX-DC02",
            isOnline = false,
            lastActiveEpochMillis = threeHoursAgo,
        )

        val meta = buildProbeCardMetaLine(offlineSnap, panel, nowMs, zone)
        assertTrue("Expected offline duration and last active time, got: $meta", meta.contains("Nezha v1") && meta.contains("失联 3 小时") && meta.contains("最后 "))
        assertEquals("离线 3 小时", buildDenseOfflineSpanLabel(threeHoursAgo, nowMs))
        assertEquals("2 天", formatOfflineDurationCompact(48L * 3_600_000L))
        assertEquals("42天", formatUptimeCompact(42L * 86_400L + 3600L))

        assertEquals(ProbeViewMode.CARDS, ProbeViewModeCodec.decode(null))
        assertEquals(ProbeViewMode.CARDS, ProbeViewModeCodec.decode("unknown"))
        assertEquals(ProbeViewMode.DENSE_LIST, ProbeViewModeCodec.decode(ProbeViewModeCodec.encode(ProbeViewMode.DENSE_LIST)))
    }
}
