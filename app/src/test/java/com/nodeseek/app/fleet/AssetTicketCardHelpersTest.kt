package com.nodeseek.app.fleet

import com.nodeseek.app.domain.fleet.AssetExpiryUrgency
import com.nodeseek.app.domain.fleet.ServerTrafficForecast
import com.nodeseek.app.domain.fleet.TrafficResetDaySource
import com.nodeseek.app.domain.fleet.UpcomingRenewalItem
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.ui.fleet.AssetTicketVisualTone
import com.nodeseek.app.ui.fleet.TimelineMarkerTone
import com.nodeseek.app.ui.fleet.buildRenewalTimelineSummary
import com.nodeseek.app.ui.fleet.computeCycleRemainingFraction
import com.nodeseek.app.ui.fleet.formatAssetPriceText
import com.nodeseek.app.ui.fleet.formatCompactCny
import com.nodeseek.app.ui.fleet.formatCycleSuffix
import com.nodeseek.app.ui.fleet.formatExpiryMonthDay
import com.nodeseek.app.ui.fleet.formatGroupedCny
import com.nodeseek.app.ui.fleet.resolveAssetTicketTone
import com.nodeseek.app.ui.fleet.resolveTrafficWarningLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class AssetTicketCardHelpersTest {

    private fun sampleAsset(
        id: String = "a1",
        currency: ValuationCurrency = ValuationCurrency.USD,
        amount: String = "149.90",
        cycle: BillingCyclePreset = BillingCyclePreset.ANNUAL,
        customDays: Int? = null,
        expiryIso: String = "2026-11-28",
    ) = VpsAssetRecord(
        id = id,
        nickname = "Sample-$id",
        providerName = "DMIT",
        specsSummary = "2C/2G/40G",
        region = "东京",
        renewalAmount = amount,
        currency = currency,
        cyclePreset = cycle,
        customCycleDays = customDays,
        expiryDateIso = expiryIso,
    )

    private fun sampleRenewal(
        asset: VpsAssetRecord,
        daysRemaining: Long,
        urgency: AssetExpiryUrgency,
        renewalCny: String = "100.00",
        withinWindow: Boolean = false,
    ) = UpcomingRenewalItem(
        asset = asset,
        daysRemaining = daysRemaining,
        urgency = urgency,
        renewalCny = BigDecimal(renewalCny),
        monthlyCostCny = BigDecimal("10.00"),
        annualizedCostCny = BigDecimal("120.00"),
        isWithinReminderWindow = withinWindow,
    )

    @Test
    fun `resolveAssetTicketTone prioritizes critical and expired over renewal due and idle`() {
        val a = sampleAsset()
        assertEquals(
            AssetTicketVisualTone.CRITICAL_OR_EXPIRED,
            resolveAssetTicketTone(sampleRenewal(a, 2L, AssetExpiryUrgency.CRITICAL, withinWindow = true), isIdle = true),
        )
        assertEquals(
            AssetTicketVisualTone.CRITICAL_OR_EXPIRED,
            resolveAssetTicketTone(sampleRenewal(a, -1L, AssetExpiryUrgency.EXPIRED, withinWindow = true), isIdle = false),
        )
        assertEquals(
            AssetTicketVisualTone.RENEWAL_DUE,
            resolveAssetTicketTone(sampleRenewal(a, 13L, AssetExpiryUrgency.WARNING, withinWindow = true), isIdle = true),
        )
        assertEquals(
            AssetTicketVisualTone.IDLE,
            resolveAssetTicketTone(sampleRenewal(a, 40L, AssetExpiryUrgency.NORMAL, withinWindow = false), isIdle = true),
        )
        assertEquals(
            AssetTicketVisualTone.NORMAL,
            resolveAssetTicketTone(sampleRenewal(a, 200L, AssetExpiryUrgency.NORMAL, withinWindow = false), isIdle = false),
        )
        assertEquals(
            AssetTicketVisualTone.UNKNOWN,
            resolveAssetTicketTone(null, isIdle = false),
        )
    }

    @Test
    fun `computeCycleRemainingFraction computes bounded cycle ratio`() {
        val annual = sampleAsset(cycle = BillingCyclePreset.ANNUAL)
        assertEquals(0f, computeCycleRemainingFraction(annual, null), 0.0001f)
        assertEquals(
            0f,
            computeCycleRemainingFraction(annual, sampleRenewal(annual, -2L, AssetExpiryUrgency.EXPIRED)),
            0.0001f,
        )
        val fiveDays = computeCycleRemainingFraction(annual, sampleRenewal(annual, 5L, AssetExpiryUrgency.CRITICAL))
        assertTrue(fiveDays in 0.014f..0.02f)

        val monthly = sampleAsset(cycle = BillingCyclePreset.MONTHLY)
        assertEquals(
            0.9f,
            computeCycleRemainingFraction(monthly, sampleRenewal(monthly, 27L, AssetExpiryUrgency.NORMAL)),
            0.001f,
        )
        assertEquals(
            1.0f,
            computeCycleRemainingFraction(monthly, sampleRenewal(monthly, 40L, AssetExpiryUrgency.NORMAL)),
            0.001f,
        )
    }

    @Test
    fun `formatting helpers match compact ledger styles`() {
        assertEquals("11-28", formatExpiryMonthDay("2026-11-28"))
        assertEquals("未设置", formatExpiryMonthDay("   "))
        assertEquals("/年", formatCycleSuffix(BillingCyclePreset.ANNUAL, null))
        assertEquals("/月", formatCycleSuffix(BillingCyclePreset.MONTHLY, null))
        assertEquals("/45天", formatCycleSuffix(BillingCyclePreset.CUSTOM, 45))

        assertEquals("$149.90", formatAssetPriceText(sampleAsset(currency = ValuationCurrency.USD, amount = "149.90")))
        assertEquals("€4.59", formatAssetPriceText(sampleAsset(currency = ValuationCurrency.EUR, amount = "4.59")))
        assertEquals("¥60.00", formatAssetPriceText(sampleAsset(currency = ValuationCurrency.CNY, amount = "60.00")))

        assertEquals("1085", formatCompactCny(BigDecimal("1085.28")))
        assertEquals("90.4", formatCompactCny(BigDecimal("90.44")))
        assertEquals("79.5", formatCompactCny(BigDecimal("79.50")))
        assertEquals("12", formatCompactCny(BigDecimal("12.00")))
        assertEquals("1,339", formatGroupedCny(BigDecimal("1338.58")))
    }

    @Test
    fun `buildRenewalTimelineSummary sums 30d payable and classifies 90d markers`() {
        val a1 = sampleAsset(id = "crit")
        val a2 = sampleAsset(id = "due")
        val a3 = sampleAsset(id = "soon")
        val a4 = sampleAsset(id = "later")
        val a5 = sampleAsset(id = "far")

        val summary = buildRenewalTimelineSummary(
            totalAssetCount = 5,
            upcomingRenewals = listOf(
                sampleRenewal(a1, 5L, AssetExpiryUrgency.CRITICAL, "1085.28", withinWindow = true),
                sampleRenewal(a2, 13L, AssetExpiryUrgency.WARNING, "79.50", withinWindow = true),
                sampleRenewal(a3, 27L, AssetExpiryUrgency.NORMAL, "173.80", withinWindow = false),
                sampleRenewal(a4, 40L, AssetExpiryUrgency.NORMAL, "35.80", withinWindow = false),
                sampleRenewal(a5, 265L, AssetExpiryUrgency.NORMAL, "130.32", withinWindow = false),
            ),
        )

        assertEquals(5, summary.totalAssetCount)
        assertEquals(3, summary.due30dCount)
        assertEquals(BigDecimal("1338.58"), summary.due30dTotalCny)
        // Only <= 90d appear as markers (4 markers; a5 at 265d excluded)
        assertEquals(4, summary.markers.size)

        val byId = summary.markers.associateBy { it.assetId }
        assertEquals(TimelineMarkerTone.ERROR, byId["crit"]?.tone)
        assertEquals(TimelineMarkerTone.GOLD, byId["due"]?.tone)
        assertEquals(TimelineMarkerTone.GOLD, byId["soon"]?.tone)
        assertEquals(TimelineMarkerTone.PRIMARY, byId["later"]?.tone)
        // Most urgent marker is placed last so Canvas draws it on top
        assertEquals("crit", summary.markers.last().assetId)
    }

    @Test
    fun `resolveTrafficWarningLine only surfaces warning or exhaustion states`() {
        val normal = ServerTrafficForecast(
            probeKey = "p:1",
            usedBytes = 300L,
            limitBytes = 1000L,
            usageRatio = 0.30,
            anchorResetDay = 1,
            resetDaySource = TrafficResetDaySource.EXPLICIT_ASSET_CONFIG,
            cycleStartDate = LocalDate.of(2026, 11, 1),
            nextResetDate = LocalDate.of(2026, 12, 1),
            elapsedMillis = 1000L,
            dailyBurnBytes = 20L,
            projectedCycleUsageRatio = 0.60,
            isAtOrAboveAlertThreshold = false,
            isExhausted = false,
            willExhaustBeforeReset = false,
            predictedExhaustionDate = null,
            exhaustionForecastText = null,
        )
        assertNull(resolveTrafficWarningLine(normal))

        val warning = normal.copy(
            usageRatio = 0.85,
            isAtOrAboveAlertThreshold = true,
            willExhaustBeforeReset = true,
            exhaustionForecastText = "预计 11月26日耗尽",
        )
        assertEquals("流量 85% · 预计 11月26日耗尽", resolveTrafficWarningLine(warning))

        val exhausted = normal.copy(usageRatio = 1.0, isExhausted = true)
        assertEquals("本周期流量已用完（每月 1 号重置）", resolveTrafficWarningLine(exhausted))
    }
}
