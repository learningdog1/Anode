package com.nodeseek.app.deals

import com.nodeseek.app.domain.deals.DealFilterEngine
import com.nodeseek.app.domain.deals.DealFilterState
import com.nodeseek.app.domain.deals.DealPriceCeiling
import com.nodeseek.app.domain.deals.DealRegionPreset
import com.nodeseek.app.domain.deals.DealRoutePreset
import com.nodeseek.app.domain.deals.DealSortOption
import com.nodeseek.app.domain.deals.DealSource
import com.nodeseek.app.domain.deals.DealSourceTab
import com.nodeseek.app.domain.deals.DealTags
import com.nodeseek.app.domain.deals.PromoDealCardModel
import com.nodeseek.app.domain.valuation.ValuationCurrency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class DealFilterEngineTest {

    private val nowMillis = 1_795_096_800_000L // 2026-11-19T22:00:00+08:00

    private val sampleDeals = listOf(
        PromoDealCardModel(
            id = "deal_hk_gia",
            source = DealSource.NODESEEK,
            topicId = 1001L,
            sourceUrl = "https://www.nodeseek.com/post-1001-1",
            orderUrl = "https://vendor1.example/cart.php?a=add&pid=1",
            merchantName = "DMIT",
            title = "DMIT 香港 CN2 GIA 黑五特惠 $18.88/年",
            rawPriceText = "$18.88/yr",
            priceAmount = BigDecimal("18.88"),
            currency = ValuationCurrency.USD,
            annualizedUsd = BigDecimal("18.88"),
            annualizedCny = BigDecimal("136.88"),
            billingCycleLabel = "年付",
            regionTags = listOf(DealTags.REGION_HK),
            routeTags = listOf(DealTags.ROUTE_CN2_GIA),
            promoCode = "HK-BF2026",
            expiresAtEpochMillis = nowMillis + 6L * 3_600_000L,
            publishedAtEpochMillis = nowMillis - 10_000L,
            replyCount = 85,
        ),
        PromoDealCardModel(
            id = "deal_jp_softbank",
            source = DealSource.OVERSEAS_LET,
            topicId = null,
            sourceUrl = "https://lowendtalk.com/discussion/999",
            orderUrl = "https://vendor2.example/order",
            merchantName = "GreenCloud 绿云",
            title = "GreenCloud Tokyo SoftBank $14.00/yr",
            rawPriceText = "$14.00/yr",
            priceAmount = BigDecimal("14.00"),
            currency = ValuationCurrency.USD,
            annualizedUsd = BigDecimal("14.00"),
            annualizedCny = BigDecimal("101.50"),
            billingCycleLabel = "年付",
            regionTags = listOf(DealTags.REGION_JP),
            routeTags = listOf(DealTags.ROUTE_SOFTBANK),
            promoCode = null,
            expiresAtEpochMillis = nowMillis + 48L * 3_600_000L,
            publishedAtEpochMillis = nowMillis - 5_000L,
            replyCount = 12,
        ),
        PromoDealCardModel(
            id = "deal_lax_9929_expensive",
            source = DealSource.NODESEEK,
            topicId = 1002L,
            sourceUrl = "https://www.nodeseek.com/post-1002-1",
            orderUrl = null,
            merchantName = "搬瓦工 (BWH)",
            title = "搬瓦工美西 9929 + CN2 GIA $49.00/年",
            rawPriceText = "$49.00/yr",
            priceAmount = BigDecimal("49.00"),
            currency = ValuationCurrency.USD,
            annualizedUsd = BigDecimal("49.00"),
            annualizedCny = BigDecimal("355.25"),
            billingCycleLabel = "年付",
            regionTags = listOf(DealTags.REGION_US_WEST),
            routeTags = listOf(DealTags.ROUTE_CN2_GIA, DealTags.ROUTE_9929),
            promoCode = "BWHCGLUKKB",
            expiresAtEpochMillis = null,
            publishedAtEpochMillis = nowMillis - 2_000L,
            replyCount = 210,
        ),
        PromoDealCardModel(
            id = "deal_eu_monthly_oos",
            source = DealSource.OVERSEAS_LEB,
            topicId = null,
            sourceUrl = "https://lowendbox.com/blog/bf-eu",
            orderUrl = "https://vendor3.example/cart",
            merchantName = "Netcup",
            title = "Netcup 德国月付特价 €2.50/mo",
            rawPriceText = "€2.50/mo",
            priceAmount = BigDecimal("2.50"),
            currency = ValuationCurrency.EUR,
            annualizedUsd = BigDecimal("32.48"),
            annualizedCny = BigDecimal("235.50"),
            billingCycleLabel = "月付",
            regionTags = listOf(DealTags.REGION_EU),
            routeTags = listOf(DealTags.ROUTE_BGP),
            promoCode = "NETCUP26",
            expiresAtEpochMillis = nowMillis - 3_600_000L, // already expired
            publishedAtEpochMillis = nowMillis - 30_000L,
            replyCount = 40,
            isOutOfStockHint = true,
        ),
    )

    @Test
    fun filterByPriceCeilingUnder20AndHkJpAndCn2Gia_matchesPrecisely() {
        val under20 = DealFilterEngine.apply(
            deals = sampleDeals,
            filter = DealFilterState(priceCeiling = DealPriceCeiling.UNDER_20),
            nowEpochMillis = nowMillis,
        )
        assertEquals(listOf("deal_jp_softbank", "deal_hk_gia"), under20.map { it.id })

        val hkJp = DealFilterEngine.apply(
            deals = sampleDeals,
            filter = DealFilterState(selectedRegions = setOf(DealRegionPreset.HK_JP)),
            nowEpochMillis = nowMillis,
        )
        assertEquals(listOf("deal_jp_softbank", "deal_hk_gia"), hkJp.map { it.id })

        val hkJpAndCn2Gia = DealFilterEngine.apply(
            deals = sampleDeals,
            filter = DealFilterState(
                priceCeiling = DealPriceCeiling.UNDER_20,
                selectedRegions = setOf(DealRegionPreset.HK_JP),
                selectedRoutes = setOf(DealRoutePreset.CN2_GIA),
            ),
            nowEpochMillis = nowMillis,
        )
        assertEquals(listOf("deal_hk_gia"), hkJpAndCn2Gia.map { it.id })
    }

    @Test
    fun filterBySourceTabPromoCodeOrderUrlAndKeyword_worksTogether() {
        val overseasOnly = DealFilterEngine.apply(
            deals = sampleDeals,
            filter = DealFilterState(sourceTab = DealSourceTab.OVERSEAS),
            nowEpochMillis = nowMillis,
        )
        assertEquals(setOf("deal_jp_softbank", "deal_eu_monthly_oos"), overseasOnly.map { it.id }.toSet())

        val couponAndOrder = DealFilterEngine.apply(
            deals = sampleDeals,
            filter = DealFilterState(
                requirePromoCode = true,
                requireOrderUrl = true,
                hideOutOfStock = true,
            ),
            nowEpochMillis = nowMillis,
        )
        assertEquals(listOf("deal_hk_gia"), couponAndOrder.map { it.id })

        val annualOnly = DealFilterEngine.apply(
            deals = sampleDeals,
            filter = DealFilterState(annualOnly = true),
            nowEpochMillis = nowMillis,
        )
        assertTrue(annualOnly.none { it.id == "deal_eu_monthly_oos" })

        val multiWordSearch = DealFilterEngine.apply(
            deals = sampleDeals,
            filter = DealFilterState(keyword = "搬瓦工 9929"),
            nowEpochMillis = nowMillis,
        )
        assertEquals(listOf("deal_lax_9929_expensive"), multiWordSearch.map { it.id })
    }

    @Test
    fun sortingModes_orderByLowestAnnualHottestAndExpiringSoon() {
        val lowestPrice = DealFilterEngine.apply(
            deals = sampleDeals,
            filter = DealFilterState(sortOption = DealSortOption.LOWEST_ANNUAL_PRICE),
            nowEpochMillis = nowMillis,
        )
        // In-stock ascending: $14.00 -> $18.88 -> $49.00, OOS ($32.48) placed after in-stock
        assertEquals(
            listOf("deal_jp_softbank", "deal_hk_gia", "deal_lax_9929_expensive", "deal_eu_monthly_oos"),
            lowestPrice.map { it.id },
        )

        val hottest = DealFilterEngine.apply(
            deals = sampleDeals,
            filter = DealFilterState(sortOption = DealSortOption.HOTTEST),
            nowEpochMillis = nowMillis,
        )
        assertEquals(
            listOf("deal_lax_9929_expensive", "deal_hk_gia", "deal_eu_monthly_oos", "deal_jp_softbank"),
            hottest.map { it.id },
        )

        val expiringSoon = DealFilterEngine.apply(
            deals = sampleDeals,
            filter = DealFilterState(sortOption = DealSortOption.EXPIRING_SOON),
            nowEpochMillis = nowMillis,
        )
        // Active soonest first (6h -> 48h), then no deadline, then already expired last
        assertEquals(
            listOf("deal_hk_gia", "deal_jp_softbank", "deal_lax_9929_expensive", "deal_eu_monthly_oos"),
            expiringSoon.map { it.id },
        )
    }

    @Test
    fun summarize_countsSourcesCouponsAndFindsLowestInStockAnnualDeal() {
        val summary = DealFilterEngine.summarize(sampleDeals)
        assertEquals(4, summary.totalCount)
        assertEquals(2, summary.nodeseekCount)
        assertEquals(2, summary.overseasCount)
        assertEquals(3, summary.couponDealCount)
        assertEquals("deal_jp_softbank", summary.lowestAnnualDeal?.id)
    }
}
