package com.nodeseek.app.deals

import com.nodeseek.app.data.valuation.CurrencyRateQuote
import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.domain.deals.DealBillingCycle
import com.nodeseek.app.domain.deals.DealPostExtractor
import com.nodeseek.app.domain.deals.DealTags
import com.nodeseek.app.domain.valuation.FxRateSource
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import com.nodeseek.app.domain.valuation.ValuationCurrency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.ZonedDateTime

class DealPostExtractorTest {

    private val fixedFxRates = FxRatesState(
        quotes = mapOf(
            ValuationCurrency.CNY to CurrencyRateQuote(ValuationCurrency.CNY, BigDecimal("1.0000"), FxRateSource.OFFLINE_DEFAULT),
            ValuationCurrency.USD to CurrencyRateQuote(ValuationCurrency.USD, BigDecimal("7.2500"), FxRateSource.ONLINE_FRESH),
            ValuationCurrency.EUR to CurrencyRateQuote(ValuationCurrency.EUR, BigDecimal("7.9750"), FxRateSource.ONLINE_FRESH), // 1.10 USD
            ValuationCurrency.GBP to CurrencyRateQuote(ValuationCurrency.GBP, BigDecimal("9.1500"), FxRateSource.ONLINE_FRESH),
            ValuationCurrency.HKD to CurrencyRateQuote(ValuationCurrency.HKD, BigDecimal("0.9300"), FxRateSource.ONLINE_FRESH),
            ValuationCurrency.JPY to CurrencyRateQuote(ValuationCurrency.JPY, BigDecimal("0.0480"), FxRateSource.ONLINE_FRESH),
        ),
    )

    private val referenceTimeMillis = ZonedDateTime.of(2026, 11, 19, 14, 0, 0, 0, VALUATION_ZONE_ID)
        .toInstant()
        .toEpochMilli()

    @Test
    fun extractChineseNodeSeekBlackFridayPost_parsesSpecsPriceRoutesCouponUrlAndDeadline() {
        val title = "【黑五特惠】DMIT 美西洛杉矶 LAX 1C1G 20G NVMe 1TB@1Gbps 三网回程 CN2 GIA 年付19.9刀"
        val body = """
            黑五闪购来了！续费同价，三网回程 CN2 GIA + 联通 AS9929 备线。
            循环优惠码：`DMIT-BF2026`
            活动截止至 2026-11-30 23:59
            直达购买：[立即抢购](https://www.nodeseek.com/jump?to=https%3A%2F%2Fwww.dmit.io%2Fcart.php%3Fa%3Dadd%26pid%3D188)
            测速图：![](https://nodeimage.com/i/test.png)
        """.trimIndent()

        val extracted = DealPostExtractor.extract(
            title = title,
            body = body,
            fxRates = fixedFxRates,
            publishedAtEpochMillis = referenceTimeMillis,
        )

        assertEquals("DMIT", extracted.merchantName)
        assertEquals(BigDecimal("19.9"), extracted.priceAmount)
        assertEquals(ValuationCurrency.USD, extracted.currency)
        assertEquals(DealBillingCycle.ANNUAL, extracted.billingCycle)
        assertEquals(BigDecimal("19.90"), extracted.annualizedUsd)
        // 19.9 * 7.25 = 144.275 -> 144.28
        assertEquals(BigDecimal("144.28"), extracted.annualizedCny)

        assertEquals(1, extracted.cpuCores)
        assertEquals("1G", extracted.ramText)
        assertEquals("20G NVMe", extracted.storageText)
        assertEquals("1T", extracted.trafficText)
        assertEquals("1Gbps", extracted.portSpeedText)

        assertTrue(DealTags.REGION_US_WEST in extracted.regionTags)
        assertTrue(DealTags.ROUTE_CN2_GIA in extracted.routeTags)
        assertTrue(DealTags.ROUTE_9929 in extracted.routeTags)

        assertEquals("DMIT-BF2026", extracted.promoCode)
        assertEquals("https://www.dmit.io/cart.php?a=add&pid=188", extracted.orderUrl)
        assertFalse(extracted.isOutOfStockHint)

        val expectedDeadline = ZonedDateTime.of(2026, 11, 30, 23, 59, 0, 0, VALUATION_ZONE_ID)
            .toInstant()
            .toEpochMilli()
        assertEquals(expectedDeadline, extracted.expiresAtEpochMillis)
    }

    @Test
    fun extractEnglishLetBlackFridayPost_parsesSpecsUsdPriceRegionsCouponAndEnglishDeadline() {
        val title = "[Black Friday] RackNerd 2 vCPU / 2GB RAM / 40GB NVMe / 3TB Bandwidth @ 1Gbps - Los Angeles & Tokyo - $10.99/yr"
        val body = """
            Special Black Friday Flash Sale!
            Locations: Los Angeles, San Jose, Tokyo (IIJ / SoftBank)
            Promo code: RN-BF2026
            Valid until Nov 29, 2026
            Order Now: https://my.racknerd.com/cart.php?a=add&pid=901
        """.trimIndent()

        val extracted = DealPostExtractor.extract(
            title = title,
            body = body,
            fxRates = fixedFxRates,
            publishedAtEpochMillis = referenceTimeMillis,
        )

        assertEquals("RackNerd", extracted.merchantName)
        assertEquals(BigDecimal("10.99"), extracted.priceAmount)
        assertEquals(ValuationCurrency.USD, extracted.currency)
        assertEquals(DealBillingCycle.ANNUAL, extracted.billingCycle)
        assertEquals(BigDecimal("10.99"), extracted.annualizedUsd)
        // 10.99 * 7.25 = 79.6775 -> 79.68
        assertEquals(BigDecimal("79.68"), extracted.annualizedCny)

        assertEquals(2, extracted.cpuCores)
        assertEquals("2G", extracted.ramText)
        assertEquals("40G NVMe", extracted.storageText)
        assertEquals("3T", extracted.trafficText)
        assertEquals("1Gbps", extracted.portSpeedText)

        assertTrue(DealTags.REGION_US_WEST in extracted.regionTags)
        assertTrue(DealTags.REGION_JP in extracted.regionTags)
        assertTrue(DealTags.ROUTE_IIJ in extracted.routeTags)
        assertTrue(DealTags.ROUTE_SOFTBANK in extracted.routeTags)
        assertEquals("RN-BF2026", extracted.promoCode)
        assertEquals("https://my.racknerd.com/cart.php?a=add&pid=901", extracted.orderUrl)

        val expectedDeadline = ZonedDateTime.of(2026, 11, 29, 23, 59, 59, 0, VALUATION_ZONE_ID)
            .toInstant()
            .toEpochMilli()
        assertEquals(expectedDeadline, extracted.expiresAtEpochMillis)
    }

    @Test
    fun extractMultiCurrencyAndCycles_annualizesEurCnyMonthlyAndTriennialAccurately() {
        // EUR annual: €12/yr -> 12 * 7.975 = 95.70 CNY, 95.70 / 7.25 = 13.20 USD
        val eurDeal = DealPostExtractor.extract(
            title = "Netcup 德国法兰克福 DE 4 vCPU 8GB RAM 128G SSD €12/yr",
            fxRates = fixedFxRates,
            publishedAtEpochMillis = referenceTimeMillis,
        )
        assertEquals(ValuationCurrency.EUR, eurDeal.currency)
        assertEquals(DealBillingCycle.ANNUAL, eurDeal.billingCycle)
        assertEquals(BigDecimal("95.70"), eurDeal.annualizedCny)
        assertEquals(BigDecimal("13.20"), eurDeal.annualizedUsd)
        assertTrue(DealTags.REGION_EU in eurDeal.regionTags)

        // CNY annual: ¥99/年 -> 99.00 CNY, 99 / 7.25 = 13.66 USD
        val cnyDeal = DealPostExtractor.extract(
            title = "阿里云香港轻量 2核2G 40G SSD 1T流量 ¥99/年 CMIN2 线路",
            fxRates = fixedFxRates,
            publishedAtEpochMillis = referenceTimeMillis,
        )
        assertEquals(ValuationCurrency.CNY, cnyDeal.currency)
        assertEquals(DealBillingCycle.ANNUAL, cnyDeal.billingCycle)
        assertEquals(BigDecimal("99.00"), cnyDeal.annualizedCny)
        assertEquals(BigDecimal("13.66"), cnyDeal.annualizedUsd)
        assertTrue(DealTags.REGION_HK in cnyDeal.regionTags)
        assertTrue(DealTags.ROUTE_CMIN2 in cnyDeal.routeTags)

        // Monthly USD: $2.50/mo -> $30.00/yr, 30 * 7.25 = 217.50 CNY
        val monthlyDeal = DealPostExtractor.extract(
            title = "ClawCloud 新加坡 SG 1C1G 20G NVMe 优化BGP $2.50/mo",
            fxRates = fixedFxRates,
            publishedAtEpochMillis = referenceTimeMillis,
        )
        assertEquals(ValuationCurrency.USD, monthlyDeal.currency)
        assertEquals(DealBillingCycle.MONTHLY, monthlyDeal.billingCycle)
        assertEquals(BigDecimal("30.00"), monthlyDeal.annualizedUsd)
        assertEquals(BigDecimal("217.50"), monthlyDeal.annualizedCny)
        assertTrue(DealTags.REGION_SG in monthlyDeal.regionTags)
        assertTrue(DealTags.ROUTE_BGP in monthlyDeal.routeTags)

        // Triennial USD: $29.97/3yr -> $9.99/yr
        val triennialDeal = DealPostExtractor.extract(
            title = "GreenCloud 绿云黑五 日本大阪 2C2G 三年付29.97刀",
            fxRates = fixedFxRates,
            publishedAtEpochMillis = referenceTimeMillis,
        )
        assertEquals(ValuationCurrency.USD, triennialDeal.currency)
        assertEquals(DealBillingCycle.TRIENNIAL, triennialDeal.billingCycle)
        assertEquals(BigDecimal("9.99"), triennialDeal.annualizedUsd)
        assertEquals(BigDecimal("72.43"), triennialDeal.annualizedCny)
        assertTrue(DealTags.REGION_JP in triennialDeal.regionTags)
    }

    @Test
    fun extractEdgeCases_avoidsFalsePositivesOnCn2GtStopWordsAndLowercaseEuWords() {
        val extracted = DealPostExtractor.extract(
            title = "Free debug test thread with CN2 GT and 1C 512M 1TB@1Gbps",
            body = "Promo code: none. Check out https://imgur.com/abc.png for screenshot. 闪购 24 小时",
            fxRates = fixedFxRates,
            publishedAtEpochMillis = referenceTimeMillis,
        )

        assertFalse("CN2 GT must not be tagged as CN2 GIA", DealTags.ROUTE_CN2_GIA in extracted.routeTags)
        assertFalse("Lowercase 'debug'/'free' must not trigger EU tag", DealTags.REGION_EU in extracted.regionTags)
        assertNull("Stop-word promo code 'none' must be rejected", extracted.promoCode)
        assertNull("Image URL must not be picked as merchant orderUrl", extracted.orderUrl)
        assertEquals(1, extracted.cpuCores)
        assertEquals("512M", extracted.ramText)
        assertNull("Traffic 1TB@1Gbps must not pollute storageText", extracted.storageText)
        assertEquals("1T", extracted.trafficText)
        assertEquals("1Gbps", extracted.portSpeedText)
        assertEquals(referenceTimeMillis + 24L * 3_600_000L, extracted.expiresAtEpochMillis)
    }

    @Test
    fun extractOutOfStockAndRestockHints_differentiatesCorrectly() {
        val soldOut = DealPostExtractor.extract(
            title = "[已售罄] 某商家黑五香港特价鸡 $9.99/yr",
            publishedAtEpochMillis = referenceTimeMillis,
        )
        assertTrue(soldOut.isOutOfStockHint)

        val restocked = DealPostExtractor.extract(
            title = "搬瓦工 MegaBox Pro 已补货！$49/yr CN2 GIA",
            body = "之前无货，现在补货啦抓紧上车！",
            publishedAtEpochMillis = referenceTimeMillis,
        )
        assertFalse(restocked.isOutOfStockHint)
        assertEquals("搬瓦工 (BWH)", restocked.merchantName)
        assertTrue(DealTags.REGION_US_WEST in restocked.regionTags)
        assertTrue(DealTags.ROUTE_CN2_GIA in restocked.routeTags)
        assertNotNull(restocked.annualizedUsd)
    }

    @Test
    fun extractCompactFourTupleSlangPriceShortChineseDeadlineAndJpyHkdGbp() {
        // Compact 4-tuple + Chinese slang price + short MM月DD日 deadline
        val compact = DealPostExtractor.extract(
            title = "AkkoCloud 德国美西 1C/1G/20G NVMe/1T 99r/年 截止 11月30日",
            body = "Code: `AKKO-BF26`",
            fxRates = fixedFxRates,
            publishedAtEpochMillis = referenceTimeMillis,
        )
        assertEquals("AkkoCloud", compact.merchantName)
        assertEquals(1, compact.cpuCores)
        assertEquals("1G", compact.ramText)
        assertEquals("20G NVMe", compact.storageText)
        assertEquals("1T", compact.trafficText)
        assertEquals(ValuationCurrency.CNY, compact.currency)
        assertEquals(BigDecimal("99.00"), compact.annualizedCny)
        assertEquals("AKKO-BF26", compact.promoCode)
        val expectedNov30 = ZonedDateTime.of(2026, 11, 30, 23, 59, 59, 0, VALUATION_ZONE_ID)
            .toInstant()
            .toEpochMilli()
        assertEquals(expectedNov30, compact.expiresAtEpochMillis)

        // HKD annual
        val hkd = DealPostExtractor.extract(
            title = "香港 HK$88/yr 1C1G",
            fxRates = fixedFxRates,
            publishedAtEpochMillis = referenceTimeMillis,
        )
        assertEquals(ValuationCurrency.HKD, hkd.currency)
        // 88 * 0.93 = 81.84 CNY; 81.84 / 7.25 = 11.29 USD
        assertEquals(BigDecimal("81.84"), hkd.annualizedCny)
        assertEquals(BigDecimal("11.29"), hkd.annualizedUsd)

        // JPY disambiguated from CNY when JPY/日元 appears
        val jpy = DealPostExtractor.extract(
            title = "日本东京 VPS ¥1500/年 (JPY)",
            fxRates = fixedFxRates,
            publishedAtEpochMillis = referenceTimeMillis,
        )
        assertEquals(ValuationCurrency.JPY, jpy.currency)
        // 1500 * 0.0480 = 72.00 CNY; 72 / 7.25 = 9.93 USD
        assertEquals(BigDecimal("72.00"), jpy.annualizedCny)
        assertEquals(BigDecimal("9.93"), jpy.annualizedUsd)
    }
}
