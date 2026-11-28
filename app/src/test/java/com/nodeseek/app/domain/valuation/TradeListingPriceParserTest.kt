package com.nodeseek.app.domain.valuation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

class TradeListingPriceParserTest {

    private val fixedNow: LocalDate = LocalDate.of(2026, 6, 1)

    private val fixedFx: (String) -> BigDecimal? = { code ->
        when (code.uppercase()) {
            "CNY" -> BigDecimal.ONE
            "USD" -> BigDecimal("7.20")
            "EUR" -> BigDecimal("7.80")
            "GBP" -> BigDecimal("9.10")
            "HKD" -> BigDecimal("0.92")
            else -> null
        }
    }

    @Test
    fun `parseAskPrice extracts explicit keyword CNY suffix verb and concise bare trailing prices`() {
        assertEquals(
            BigDecimal("300.00"),
            TradeListingPriceParser.parseAskPrice("出 DMIT LAX Pro Wee 300")?.amountCny,
        )
        assertEquals(
            BigDecimal("120.00"),
            TradeListingPriceParser.parseAskPrice("[出] 斯巴达 9.9刀/年 120r 12月到期")?.amountCny,
        )
        assertEquals(
            BigDecimal("88.00"),
            TradeListingPriceParser.parseAskPrice("绿云2222 带原油出 明盘88")?.amountCny,
        )
        assertEquals(
            BigDecimal("488.00"),
            TradeListingPriceParser.parseAskPrice("出 搬瓦工 MEGABOX Pro 一口价 488")?.amountCny,
        )
        assertEquals(
            BigDecimal("280.00"),
            TradeListingPriceParser.parseAskPrice("出 DMIT LAX.Pro.WEE 280出")?.amountCny,
        )
        assertEquals(
            BigDecimal("88.00"),
            TradeListingPriceParser.parseAskPrice("出 Claw HK 4刀/月 ¥88 带原油")?.amountCny,
        )
        assertEquals(
            BigDecimal("350.00"),
            TradeListingPriceParser.parseAskPrice("[出] 瓦工 DC6 价格: 350元")?.amountCny,
        )
        assertEquals(
            BigDecimal("260.00"),
            TradeListingPriceParser.parseAskPrice("[出] DMIT LAX Pro Wee 自刀至 260")?.amountCny,
        )
        assertEquals(
            BigDecimal("280.00"),
            TradeListingPriceParser.parseAskPrice("[出] DMIT LAX Pro Wee 自刀20 现价280")?.amountCny,
        )
        assertEquals(
            BigDecimal("300.00"),
            TradeListingPriceParser.parseAskPrice("DMIT Pro 1C1G 2026-01-01到期 \$39.9/yr 溢价出 300r")?.amountCny,
        )
        assertEquals(
            BigDecimal("300.00"),
            TradeListingPriceParser.parseAskPrice("DMIT Pro 亏本出 300r")?.amountCny,
        )
    }

    @Test
    fun `parseAskPrice converts explicit foreign-currency asks using injected FX`() {
        val usdtAsk = TradeListingPriceParser.parseAskPrice("出 DMIT LAX Pro Wee 明盘 30u", fxToCny = fixedFx)
        assertNotNull(usdtAsk)
        assertEquals(BigDecimal("216.00"), usdtAsk!!.amountCny)

        val verbUsdt = TradeListingPriceParser.parseAskPrice("出 瓦工 MEGABOX 50usdt出", fxToCny = fixedFx)
        assertNotNull(verbUsdt)
        assertEquals(BigDecimal("360.00"), verbUsdt!!.amountCny)

        val usdLabeled = TradeListingPriceParser.parseAskPrice("出 Netcup RS 1000 明盘 $40", fxToCny = fixedFx)
        assertNotNull(usdLabeled)
        assertEquals(BigDecimal("288.00"), usdLabeled!!.amountCny)
    }

    @Test
    fun `parseAskPrice rejects model codes specs renewal fees balances push fees dates and discounts`() {
        val noAskTitles = listOf(
            "出 绿云 2222 东京 软银 三年付 25刀",
            "出 Netcup RS 1000 G9.5 带原油",
            "出 Netcup VPS 2000 G11 带原油",
            "出 RN 2024黑五 2C2.5G 10.99刀年付 DC02",
            "出 DMIT LAX.Pro.WEE 1C1G 20G 1000G 1Gbps 2026-12-01到期 \$36.9/yr",
            "出 瓦工 46刀 9929 4837 CN2 GIA 8折",
            "出 DMIT Pro 带余额 50元 push费 30元",
            "出 2台 绿云 2525",
            "分享教程 1.2.3.4:8080 AS9929",
            "[出] DMIT LAX.Pro.Wee 溢价 +80 包Push",
            "[出] 瓦工 MEGABOX Pro 剩余价值+120r",
            "[出] BandwagonHOST DC6 折价 -35 出",
            "出 斯巴达 西雅图 24刀 2C2G 带原油",
            "出 OVH KS-LE-B 杜甫 2026-10-01到期",
        )
        for (title in noAskTitles) {
            assertNull("Expected no ask price for '$title'", TradeListingPriceParser.parseAskPrice(title))
        }
    }

    @Test
    fun `parseRenewal extracts currency amount and billing cycle across formats while ignoring push and balance`() {
        val r1 = TradeListingPriceParser.parseRenewal("[出] DMIT LAX.Pro.WEE \$36.9/yr 300出")!!
        assertEquals(BigDecimal("36.90"), r1.amount)
        assertEquals(ValuationCurrency.USD, r1.currency)
        assertEquals(BillingCyclePreset.ANNUAL, r1.cycle)
        assertTrue(r1.cycleExplicit)

        val r2 = TradeListingPriceParser.parseRenewal("[出] 斯巴达 9.9刀/年 120r 12月到期")!!
        assertEquals(BigDecimal("9.90"), r2.amount)
        assertEquals(ValuationCurrency.USD, r2.currency)
        assertEquals(BillingCyclePreset.ANNUAL, r2.cycle)

        val r3 = TradeListingPriceParser.parseRenewal("出 Netcup RS1000 €5/月 明盘80")!!
        assertEquals(BigDecimal("5.00"), r3.amount)
        assertEquals(ValuationCurrency.EUR, r3.currency)
        assertEquals(BillingCyclePreset.MONTHLY, r3.cycle)

        val r4 = TradeListingPriceParser.parseRenewal("出 瓦工 49.9美元/年 380出")!!
        assertEquals(BigDecimal("49.90"), r4.amount)
        assertEquals(ValuationCurrency.USD, r4.currency)
        assertEquals(BillingCyclePreset.ANNUAL, r4.cycle)

        val r5 = TradeListingPriceParser.parseRenewal("出 绿云 2222 25刀 三年付 明盘200")!!
        assertEquals(BigDecimal("25.00"), r5.amount)
        assertEquals(ValuationCurrency.USD, r5.currency)
        assertEquals(BillingCyclePreset.TRIENNIAL, r5.cycle)

        val r6 = TradeListingPriceParser.parseRenewal("出 阿里云 轻量 99元/年 80出")!!
        assertEquals(BigDecimal("99.00"), r6.amount)
        assertEquals(ValuationCurrency.CNY, r6.currency)
        assertEquals(BillingCyclePreset.ANNUAL, r6.cycle)

        val r7 = TradeListingPriceParser.parseRenewal("出 季付 12刀 小鸡 明盘100")!!
        assertEquals(BigDecimal("12.00"), r7.amount)
        assertEquals(ValuationCurrency.USD, r7.currency)
        assertEquals(BillingCyclePreset.QUARTERLY, r7.cycle)

        // Title has renewal without cycle, body supplies cycle
        val r8 = TradeListingPriceParser.parseRenewal(
            title = "出 斯巴达 24刀 西雅图 280出",
            bodySnippet = "续费周期：年付\n到期时间：2026-12-01",
        )!!
        assertEquals(BigDecimal("24.00"), r8.amount)
        assertEquals(ValuationCurrency.USD, r8.currency)
        assertEquals(BillingCyclePreset.ANNUAL, r8.cycle)

        // Push fee and account balance must NOT be mistaken for renewal
        assertNull(TradeListingPriceParser.parseRenewal("出 DMIT Pro Push费 \$8 带余额 \$10 明盘200"))
    }

    @Test
    fun `parseExpiry parses full dates short dates month-only remaining days and ignores purchase dates`() {
        val e1 = TradeListingPriceParser.parseExpiry("DMIT Wee 2026-12-01到期", now = fixedNow)!!
        assertEquals(LocalDate.of(2026, 12, 1), e1.expiryDate)
        assertEquals(183, e1.remainingDays)
        assertFalse(e1.isApproximate)

        val e2 = TradeListingPriceParser.parseExpiry("斯巴达 12.1到期", now = fixedNow)!!
        assertEquals(LocalDate.of(2026, 12, 1), e2.expiryDate)
        assertFalse(e2.isApproximate)

        // Past month/day relative to June 1 rolls forward to next year
        val e3 = TradeListingPriceParser.parseExpiry("瓦工 3.15到期", now = fixedNow)!!
        assertEquals(LocalDate.of(2027, 3, 15), e3.expiryDate)
        assertFalse(e3.isApproximate)

        val e4 = TradeListingPriceParser.parseExpiry("[出] 斯巴达 9.9刀/年 120r 12月到期", now = fixedNow)!!
        assertEquals(LocalDate.of(2026, 12, 15), e4.expiryDate)
        assertTrue(e4.isApproximate)

        val e5 = TradeListingPriceParser.parseExpiry("出 绿云 剩余200天 明盘150", now = fixedNow)!!
        assertEquals(fixedNow.plusDays(200), e5.expiryDate)
        assertEquals(200, e5.remainingDays)
        assertFalse(e5.isApproximate)

        val e6 = TradeListingPriceParser.parseExpiry(
            title = "[出] 斯巴达 12月到期",
            bodySnippet = "到期时间：2026-12-05\n带原邮",
            now = fixedNow,
        )!!
        // Exact date in body overrides approximate month in title
        assertEquals(LocalDate.of(2026, 12, 5), e6.expiryDate)
        assertFalse(e6.isApproximate)

        // Purchase dates must be ignored
        assertNull(TradeListingPriceParser.parseExpiry("出 绿云 2024-11-28购买 2025-01-01开通", now = fixedNow))
        assertNull(TradeListingPriceParser.parseExpiry("出 DMIT 2026-05-01刚续费", now = fixedNow))
    }

    @Test
    fun `inferSpread derives exact spread matching VpsValuationEngine from title and body`() {
        // $36.9/yr * 7.20 = 265.68 CNY/yr; 2026-06-01 -> 2026-12-01 = 183 days
        // residual = 265.68 * 183 / 365 = 133.20; ask = 300.00 -> spread = +166.80
        val inferred = TradeListingPriceParser.inferSpread(
            title = "出大妈wee 带原邮",
            bodySnippet = """
                ```bash
                Geekbench 6 Score: 1450
                ```
                机器型号：DMIT LAX.Pro.WEE
                续费：${'$'}36.9/yr
                到期时间：2026-12-01
                明盘 300 不刀
            """.trimIndent(),
            fxToCny = fixedFx,
            now = fixedNow,
        )

        assertNotNull(inferred)
        assertTrue(inferred!!.hasSpread)
        assertTrue(inferred.isDerived)
        assertEquals(BigDecimal("300.00"), inferred.askPriceCny)
        assertEquals(BigDecimal("133.20"), inferred.residualCny)
        assertEquals(BigDecimal("166.80"), inferred.spreadDeltaCny)
        assertEquals(DealSpreadStatus.PREMIUM, inferred.spreadStatus)
        assertEquals(PricingConfidence.MEDIUM, inferred.confidence)
        assertEquals("推算: 挂牌¥300 − 残值¥133.2", inferred.matchedExpression)

        // Cross-check directly against VpsValuationEngine.calculate
        val engineOutcome = VpsValuationEngine.calculate(
            VpsValuationInput(
                mode = ValuationMode.TRANSFER_DEAL,
                renewalAmount = BigDecimal("36.90"),
                currency = ValuationCurrency.USD,
                fxRateToCny = BigDecimal("7.20"),
                cyclePreset = BillingCyclePreset.ANNUAL,
                valuationDateTime = fixedNow.atStartOfDay(),
                expiryDateTime = LocalDate.of(2026, 12, 1).atStartOfDay(),
                askingPriceCny = BigDecimal("300.00"),
            ),
        ) as VpsValuationOutcome.Success
        assertEquals(engineOutcome.result.remainingValueCny, inferred.residualCny)
        assertEquals(engineOutcome.result.transferBreakdown!!.buyerPremiumCny, inferred.spreadDeltaCny)
    }

    @Test
    fun `inferSpread handles concise title with renewal expiry and ask price`() {
        // 9.9 * 7.20 = 71.28; 2026-06-01 -> 2026-12-15 = 197 days; residual = 71.28 * 197 / 365 = 38.47
        val inferred = TradeListingPriceParser.inferSpread(
            title = "[出] 斯巴达 9.9刀/年 120r 12月到期",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertTrue(inferred.hasSpread)
        assertTrue(inferred.isDerived)
        assertEquals(BigDecimal("120.00"), inferred.askPriceCny)
        assertEquals(BigDecimal("38.47"), inferred.residualCny)
        assertEquals(BigDecimal("81.53"), inferred.spreadDeltaCny)
        assertEquals(DealSpreadStatus.PREMIUM, inferred.spreadStatus)
        assertEquals(PricingConfidence.LOW, inferred.confidence) // approximate month-only expiry
    }

    @Test
    fun `inferSpread handles discount derived spread stated residual and ask-price-only fallback`() {
        // Discount derived spread: 99 CNY/yr, 300 days remaining -> residual = 81.37, ask = 60 -> -21.37
        val discount = TradeListingPriceParser.inferSpread(
            title = "[出] 阿里云 轻量 99元/年 剩余300天 60出",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertTrue(discount.isDerived)
        assertEquals(BigDecimal("60.00"), discount.askPriceCny)
        assertEquals(BigDecimal("81.37"), discount.residualCny)
        assertEquals(BigDecimal("-21.37"), discount.spreadDeltaCny)
        assertEquals(DealSpreadStatus.DISCOUNT, discount.spreadStatus)

        // Stated residual in body + ask price (must NOT misread 剩余价值：220 as +220 spread!)
        val statedResidual = TradeListingPriceParser.inferSpread(
            title = "[出] DMIT LAX.Pro.WEE",
            bodySnippet = "剩余价值：220元\n明盘：300元 带原油出",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertTrue(statedResidual.isDerived)
        assertEquals(BigDecimal("300.00"), statedResidual.askPriceCny)
        assertEquals(BigDecimal("220.00"), statedResidual.residualCny)
        assertEquals(BigDecimal("80.00"), statedResidual.spreadDeltaCny)
        assertEquals("推算: 挂牌¥300 − 残值¥220", statedResidual.matchedExpression)

        // Ask-price-only fallback when renewal/expiry are absent
        val askOnly1 = TradeListingPriceParser.inferSpread("出 DMIT LAX Pro Wee 300", fxToCny = fixedFx, now = fixedNow)!!
        assertFalse(askOnly1.hasSpread)
        assertTrue(askOnly1.isAskPriceOnly)
        assertNull(askOnly1.spreadDeltaCny)
        assertEquals(BigDecimal("300.00"), askOnly1.askPriceCny)

        val askOnly2 = TradeListingPriceParser.inferSpread("绿云2222 带原油出 明盘88", fxToCny = fixedFx, now = fixedNow)!!
        assertFalse(askOnly2.hasSpread)
        assertTrue(askOnly2.isAskPriceOnly)
        assertEquals(BigDecimal("88.00"), askOnly2.askPriceCny)
    }

    @Test
    fun `inferSpread prefers explicit spread in title or body and avoids deriving on expired or missing FX`() {
        val explicitTitle = TradeListingPriceParser.inferSpread(
            title = "[出] DMIT LAX.Pro.WEE \$36.9/yr 2026-12-01到期 剩余价值+80出",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertTrue(explicitTitle.hasSpread)
        assertFalse(explicitTitle.isDerived)
        assertEquals(BigDecimal("80.00"), explicitTitle.spreadDeltaCny)
        assertEquals(PricingConfidence.HIGH, explicitTitle.confidence)

        val explicitBody = TradeListingPriceParser.inferSpread(
            title = "出大妈wee",
            bodySnippet = "带原油，溢价 65 包push",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertTrue(explicitBody.hasSpread)
        assertFalse(explicitBody.isDerived)
        assertEquals(BigDecimal("65.00"), explicitBody.spreadDeltaCny)

        // Expired listing must NOT fabricate residual=0 spread; falls back to ask-price-only
        val expired = TradeListingPriceParser.inferSpread(
            title = "[出] DMIT LAX.Pro.WEE \$36.9/yr 2026-05-01到期 300出",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertFalse(expired.hasSpread)
        assertTrue(expired.isAskPriceOnly)
        assertEquals(BigDecimal("300.00"), expired.askPriceCny)

        // Missing FX rate falls back to ask-price-only
        val noFx = TradeListingPriceParser.inferSpread(
            title = "[出] DMIT LAX.Pro.WEE \$36.9/yr 2026-12-01到期 300出",
            fxToCny = { null },
            now = fixedNow,
        )!!
        assertFalse(noFx.hasSpread)
        assertTrue(noFx.isAskPriceOnly)
        assertEquals(BigDecimal("300.00"), noFx.askPriceCny)
    }

    @Test
    fun `hasTitlePricingSignal and intent detection work for concise buy sell and unpriced titles`() {
        assertTrue(TradeListingPriceParser.hasTitlePricingSignal("出 DMIT LAX Pro Wee 300"))
        assertTrue(TradeListingPriceParser.hasTitlePricingSignal("绿云2222 带原油出 明盘88"))
        assertTrue(TradeListingPriceParser.hasTitlePricingSignal("[出] DMIT Pro 平出"))
        assertFalse(TradeListingPriceParser.hasTitlePricingSignal("出大妈wee 带原邮"))
        assertFalse(TradeListingPriceParser.hasTitlePricingSignal("出 绿云 2222 东京 软银 三年付 25刀"))
        assertFalse(TradeListingPriceParser.hasTitlePricingSignal("出 Netcup RS 1000 G9.5 带原油"))

        val buyPost = TradeListingPriceParser.inferSpread("[收] DMIT LAX Pro Wee 明盘300", fxToCny = fixedFx, now = fixedNow)!!
        assertEquals(TradeListingIntent.BUY, buyPost.intent)
        assertEquals(BigDecimal("300.00"), buyPost.askPriceCny)

        val soldPost = TradeListingPriceParser.inferSpread("[已出] 斯巴达 9.9刀/年 120r", fxToCny = fixedFx, now = fixedNow)!!
        assertEquals(TradeListingIntent.SELL, soldPost.intent)
        assertTrue(soldPost.isClosedSold)

        // Leading 【收】 must win over inline 剩余价值+60
        val buySpread = TradeListingPriceParser.parse("【收】V.PS 东京 软银 Mini Pro 剩余价值+60", now = fixedNow)
        assertEquals(TradeListingIntent.BUY, buySpread.intent)
        assertEquals(BigDecimal("60.00"), buySpread.explicitSpread?.deltaCny)

        // Decimal renewals in 24..29 range must NOT be scrubbed as short dates (27.08, 29.9, 29.95)
        val r299 = TradeListingPriceParser.parseRenewal("出 DMIT PVM.LAX.EB.WEE \$29.9/yr 230r")!!
        assertEquals(BigDecimal("29.90"), r299.amount)
        assertEquals(ValuationCurrency.USD, r299.currency)
        assertEquals(BillingCyclePreset.ANNUAL, r299.cycle)

        val r2708 = TradeListingPriceParser.parseRenewal("出 瓦工 MINIBOX 240", "年付 \$27.08/yr，2026-12-25到期")!!
        assertEquals(BigDecimal("27.08"), r2708.amount)
        assertEquals(BillingCyclePreset.ANNUAL, r2708.cycle)

        // Cycle prefix + CNY ask ("三年付 330r") must NOT swallow 330r as CNY renewal
        val bundleCycleAsk = TradeListingPriceParser.parse(
            title = "出绿云大阪2222 三年付 330r",
            bodySnippet = "Osaka 2222，三年付 \$66/3yr，2027-09-15到期。",
            now = fixedNow,
        )
        assertEquals(BigDecimal("330.00"), bundleCycleAsk.askPrice?.amountCny)
        assertEquals(BigDecimal("66.00"), bundleCycleAsk.renewal?.amount)
        assertEquals(ValuationCurrency.USD, bundleCycleAsk.renewal?.currency)
        assertEquals(BillingCyclePreset.TRIENNIAL, bundleCycleAsk.renewal?.cycle)

        // Cloud credit accounts must NOT parse credit as renewal; multi-machine bundles must NOT parse bundle total as single-machine ask
        val gcpAccount = TradeListingPriceParser.parse("出 GCP 300刀 账号 明盘110", now = fixedNow)
        assertNull(gcpAccount.renewal)
        assertEquals(BigDecimal("110.00"), gcpAccount.askPrice?.amountCny)

        val multiBundle = TradeListingPriceParser.parse("打包出 DMIT LAX Pro Wee + 搬瓦工 MEGABOX-PRO 共 650r", now = fixedNow)
        assertNull(multiBundle.askPrice)

        // Bare decimal tier shorthand ("36.9 已出") must NOT parse as ask price ¥36.9
        assertNull(TradeListingPriceParser.parseAskPrice("DMIT HKG Pro 36.9 已出"))

        // Par sale stating exact residual ask ("剩余价值 ¥36.40 出") yields both 0 spread and ¥36.40 ask
        val parResidual = TradeListingPriceParser.parse(
            "[出] RackNerd 洛杉矶 DC02 剩余价值 ¥36.40 出 (到期 2027-05-20)",
            now = fixedNow,
        )
        assertEquals(BigDecimal("0.00"), parResidual.explicitSpread?.deltaCny)
        assertEquals(BigDecimal("36.40"), parResidual.askPrice?.amountCny)
        assertEquals(LocalDate.of(2027, 5, 20), parResidual.expiry?.expiryDate)

        // "push 120r" (transfer method + CNY ask) must NOT be swallowed as a push fee
        val pushTransferAsk = TradeListingPriceParser.parse(
            title = "出 Netcup RS1000 G11 AMD EPYC DDR5 免税号 push 120r",
            bodySnippet = "月付 €8.75/月，2026-12-26到期，支持push过户。",
            now = fixedNow,
        )
        assertEquals(BigDecimal("120.00"), pushTransferAsk.askPrice?.amountCny)
        assertEquals(BigDecimal("8.75"), pushTransferAsk.renewal?.amount)
        assertEquals(ValuationCurrency.EUR, pushTransferAsk.renewal?.currency)

        // CJK-adjacent balance ("账号带10欧余额") must NOT be misparsed as renewal
        val balanceTrap = TradeListingPriceParser.parse(
            title = "出 Netcup RS1000 G9.5 账号带10欧余额 明盘220r",
            bodySnippet = "月付 €5.75/月，2026-12-19到期，带原邮。",
            now = fixedNow,
        )
        assertEquals(BigDecimal("220.00"), balanceTrap.askPrice?.amountCny)
        assertEquals(BigDecimal("5.75"), balanceTrap.renewal?.amount)
        assertEquals(ValuationCurrency.EUR, balanceTrap.renewal?.currency)

        // Par sale via "剩余价值:X 明盘X" must derive PAR (0.00) rather than +X PREMIUM
        val equalParColon1 = TradeListingPriceParser.inferSpread(
            title = "出 DMIT LAX Pro Wee 剩余价值:220 明盘220",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertTrue(equalParColon1.isDerived)
        assertEquals(BigDecimal("0.00"), equalParColon1.spreadDeltaCny)
        assertEquals(DealSpreadStatus.PAR, equalParColon1.spreadStatus)
        assertEquals(BigDecimal("220.00"), equalParColon1.askPriceCny)
        assertEquals(BigDecimal("220.00"), equalParColon1.residualCny)
        assertEquals("推算: 挂牌¥220 − 残值¥220", equalParColon1.matchedExpression)

        val equalParColon2 = TradeListingPriceParser.inferSpread(
            title = "出 DMIT LAX Pro Wee 剩余价值：220 明盘 220r",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertTrue(equalParColon2.isDerived)
        assertEquals(BigDecimal("0.00"), equalParColon2.spreadDeltaCny)
        assertEquals(DealSpreadStatus.PAR, equalParColon2.spreadStatus)
        assertEquals(BigDecimal("220.00"), equalParColon2.askPriceCny)
        assertEquals(BigDecimal("220.00"), equalParColon2.residualCny)

        // Legacy "剩余价值:100" with NO ask price remains unchanged
        val legacyColonOnly = TradeListingPriceParser.inferSpread(
            title = "DMIT Pro 剩余价值:100",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertFalse(legacyColonOnly.isDerived)
        assertEquals(BigDecimal("100.00"), legacyColonOnly.spreadDeltaCny)
        assertEquals(DealSpreadStatus.PREMIUM, legacyColonOnly.spreadStatus)
        assertNull(legacyColonOnly.askPriceCny)
    }

    @Test
    fun `detects unbracketed buy intent multi-machine bundles without dabao and glued cycle plus ask`() {
        // Category 6: unbracketed BUY phrasings must never leak as SELL
        val buyTitles = listOf(
            "wtb dmit lax pro wee 280r",
            "🚀 WTB BWG MEGABOX 380r",
            "诚心蹲一台 瓦工 MEGABOX 预算360",
            "求大佬割爱 DMIT LAX Pro Wee 明盘400",
            "🙏 求老哥割爱 斯巴达 西雅图 剩余价值+50",
            "在线蹲个 Claw HK 4刀/月 80r",
            "DMIT LAX Pro Wee 预算 300 带原邮",
        )
        for (title in buyTitles) {
            val parsed = TradeListingPriceParser.parse(title, now = fixedNow)
            assertEquals("Expected BUY intent for '$title'", TradeListingIntent.BUY, parsed.intent)
        }

        // Unbracketed / colloquial SELL phrasings
        val sellTitles = listOf(
            "DMIT LAX Pro Wee 折价50甩卖",
            "瓦工 DC6 亏本30转",
            "wts dmit lax pro wee 280r",
            "🔥出 DMIT LAX Pro Wee 290r",
        )
        for (title in sellTitles) {
            val parsed = TradeListingPriceParser.parse(title, now = fixedNow)
            assertEquals("Expected SELL intent for '$title'", TradeListingIntent.SELL, parsed.intent)
        }

        // Category 7: multi-machine bundles without "打包" must NOT produce single-machine ask price
        val bundleTitles = listOf(
            "出 DMIT LAX Pro Wee 和 Claw HK 两台一起350",
            "两台 Netcup VPS 1000 G11 一起160r",
            "出 DMIT LAX Pro Wee 和 Claw HK 一起350",
            "3台 RN DC02 打包 120r",
            "两只 绿云 2222 合计 680元",
        )
        for (title in bundleTitles) {
            assertNull("Expected null askPrice for bundle '$title'", TradeListingPriceParser.parseAskPrice(title))
        }
        // Single machine with "原邮一起出" or "含push费一起" is NOT a multi-machine bundle
        assertEquals(
            BigDecimal("300.00"),
            TradeListingPriceParser.parseAskPrice("带原邮一起出 DMIT LAX Pro Wee 300r")?.amountCny,
        )
        assertEquals(
            BigDecimal("295.00"),
            TradeListingPriceParser.parseAskPrice("出 DMIT LAX Pro Wee 含push费一起295r")?.amountCny,
        )

        // Category 8: glued cycle + ask price (no space between cycle and ask)
        val glued1 = TradeListingPriceParser.parse("带原油出个爪云香港4刀/月75r", now = fixedNow)
        assertEquals(BigDecimal("4.00"), glued1.renewal?.amount)
        assertEquals(ValuationCurrency.USD, glued1.renewal?.currency)
        assertEquals(BillingCyclePreset.MONTHLY, glued1.renewal?.cycle)
        assertEquals(BigDecimal("75.00"), glued1.askPrice?.amountCny)

        val glued2 = TradeListingPriceParser.parse("出 DMIT LAX Pro Wee \$36.9/yr300r 2026-12-01到期", now = fixedNow)
        assertEquals(BigDecimal("36.90"), glued2.renewal?.amount)
        assertEquals(ValuationCurrency.USD, glued2.renewal?.currency)
        assertEquals(BillingCyclePreset.ANNUAL, glued2.renewal?.cycle)
        assertEquals(BigDecimal("300.00"), glued2.askPrice?.amountCny)

        val glued3 = TradeListingPriceParser.parse("出 Netcup RS1000 €5.75/mo120r", now = fixedNow)
        assertEquals(BigDecimal("5.75"), glued3.renewal?.amount)
        assertEquals(ValuationCurrency.EUR, glued3.renewal?.currency)
        assertEquals(BillingCyclePreset.MONTHLY, glued3.renewal?.cycle)
        assertEquals(BigDecimal("120.00"), glued3.askPrice?.amountCny)
    }

    @Test
    fun `handles colloquial spreads Traditional Chinese phrasing and expanded buy intent without ask false positives`() {
        // Colloquial explicit spreads must parse as spread (not tiny ask price)
        val plus50 = TradeListingPriceParser.inferSpread("出 搬瓦工 BIGGERBOX-PRO 加50出", fxToCny = fixedFx, now = fixedNow)!!
        assertTrue(plus50.hasSpread)
        assertFalse(plus50.isDerived)
        assertEquals(BigDecimal("50.00"), plus50.spreadDeltaCny)
        assertEquals(DealSpreadStatus.PREMIUM, plus50.spreadStatus)
        assertNull(plus50.askPriceCny)
        assertTrue(TradeListingPriceParser.hasTitlePricingSignal("出 搬瓦工 BIGGERBOX-PRO 加50出"))

        val micro30 = TradeListingPriceParser.inferSpread("出 Netcup RS1000 G9.5 微溢30", fxToCny = fixedFx, now = fixedNow)!!
        assertTrue(micro30.hasSpread)
        assertFalse(micro30.isDerived)
        assertEquals(BigDecimal("30.00"), micro30.spreadDeltaCny)
        assertNull(micro30.askPriceCny)
        assertTrue(TradeListingPriceParser.hasTitlePricingSignal("出 Netcup RS1000 G9.5 微溢30"))

        val small20 = TradeListingPriceParser.inferSpread("出 DMIT LAX Pro Wee 小溢20", fxToCny = fixedFx, now = fixedNow)!!
        assertEquals(BigDecimal("20.00"), small20.spreadDeltaCny)
        assertNull(small20.askPriceCny)

        val addPrice50 = TradeListingPriceParser.inferSpread("出 DMIT LAX Pro Wee 加价50", fxToCny = fixedFx, now = fixedNow)!!
        assertEquals(BigDecimal("50.00"), addPrice50.spreadDeltaCny)
        assertNull(addPrice50.askPriceCny)

        val minus20 = TradeListingPriceParser.inferSpread("出 RN DC02 减20出", fxToCny = fixedFx, now = fixedNow)!!
        assertEquals(BigDecimal("-20.00"), minus20.spreadDeltaCny)
        assertEquals(DealSpreadStatus.DISCOUNT, minus20.spreadStatus)
        assertNull(minus20.askPriceCny)

        val microDisc = TradeListingPriceParser.inferSpread("出 Claw HK 微折15", fxToCny = fixedFx, now = fixedNow)!!
        assertEquals(BigDecimal("-15.00"), microDisc.spreadDeltaCny)
        assertNull(microDisc.askPriceCny)

        // Traditional Chinese spreads, asks, renewals, and intents via shared folding
        val tradSpread = TradeListingPriceParser.inferSpread("【出】大媽 洛杉磯 Pro Wee 溢價+65", fxToCny = fixedFx, now = fixedNow)!!
        assertTrue(tradSpread.hasSpread)
        assertFalse(tradSpread.isDerived)
        assertEquals(BigDecimal("65.00"), tradSpread.spreadDeltaCny)
        assertNull(tradSpread.askPriceCny)

        val tradSpreadNoSign = TradeListingPriceParser.inferSpread("出 瓦工 MEGABOX 溢價35", fxToCny = fixedFx, now = fixedNow)!!
        assertEquals(BigDecimal("35.00"), tradSpreadNoSign.spreadDeltaCny)
        assertNull(tradSpreadNoSign.askPriceCny)

        val tradAsk = TradeListingPriceParser.parse("出 綠雲 東京 軟銀 2222 三年付 380元", now = fixedNow)
        assertEquals(TradeListingIntent.SELL, tradAsk.intent)
        assertEquals(BigDecimal("380.00"), tradAsk.askPrice?.amountCny)

        val tradSold = TradeListingPriceParser.parse("『已出』大媽 Pro Wee 290r", now = fixedNow)
        assertEquals(TradeListingIntent.SELL, tradSold.intent)
        assertTrue(tradSold.isClosedSold)

        // Expanded BUY phrasing ("想买一台", "求一台", "想收", "350以内", Traditional "想買一台")
        val expandedBuys = listOf(
            "想买一台 DMIT LAX Pro Wee 350以内",
            "想買一台 瓦工 MEGABOX 380以內",
            "求一台 斯巴达 西雅图 16刀/年 260r",
            "求个 Claw HK 4刀/月 80r",
            "想收 搬瓦工 DC6 CN2 GIA 300r以内",
            "打算买一台 Netcup RS1000 G9.5 明盘180",
        )
        for (title in expandedBuys) {
            val parsed = TradeListingPriceParser.parse(title, now = fixedNow)
            assertEquals("Expected BUY intent for '$title'", TradeListingIntent.BUY, parsed.intent)
        }

        // Guard: "续费打折 200r" must still parse 200r ask (bare "折" must NOT scrub ask)
        assertEquals(
            BigDecimal("200.00"),
            TradeListingPriceParser.parseAskPrice("出 DMIT Pro 续费打折 200r")?.amountCny,
        )
    }

    @Test
    fun `inferSpread derives total ask from explicit spread plus residual and supports referenceRenewal`() {
        // 1. Explicit spread + own renewal + expiry => derives total ask (residual + spread)
        // $36.9/yr * 7.20 = 265.68 CNY/yr; 2026-06-01 -> 2026-12-01 = 183 days => residual = 133.20
        val explicitWithResidual = TradeListingPriceParser.inferSpread(
            title = "[出] DMIT LAX.Pro.WEE 溢价+80出",
            bodySnippet = "续费 \$36.9/yr，2026-12-01到期，带原邮。",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertTrue(explicitWithResidual.hasSpread)
        assertTrue(explicitWithResidual.hasAskPrice)
        assertTrue(explicitWithResidual.hasBothTotalAndSpread)
        assertFalse(explicitWithResidual.isDerived)
        assertTrue(explicitWithResidual.isAskDerived)
        assertEquals(BigDecimal("80.00"), explicitWithResidual.spreadDeltaCny)
        assertEquals(BigDecimal("133.20"), explicitWithResidual.residualCny)
        assertEquals(BigDecimal("213.20"), explicitWithResidual.askPriceCny)
        assertEquals(DealSpreadStatus.PREMIUM, explicitWithResidual.spreadStatus)

        // 2. Explicit par ("平出") + renewal + expiry => derived ask equals residual
        val parWithResidual = TradeListingPriceParser.inferSpread(
            title = "[出] DMIT LAX.Pro.WEE \$36.9/yr 2026-12-01到期 平出",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertFalse(parWithResidual.isDerived)
        assertTrue(parWithResidual.isAskDerived)
        assertEquals(BigDecimal("0.00"), parWithResidual.spreadDeltaCny)
        assertEquals(BigDecimal("133.20"), parWithResidual.residualCny)
        assertEquals(BigDecimal("133.20"), parWithResidual.askPriceCny)

        // 3. Explicit discount exceeding residual clamps derived total ask to 0.00
        // 99 CNY/yr, 30 days left => residual = 8.14; discount -20 => ask clamped to 0.00
        val clampedDiscount = TradeListingPriceParser.inferSpread(
            title = "[出] 阿里云 轻量 99元/年 剩余30天 折价-20出",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertFalse(clampedDiscount.isDerived)
        assertTrue(clampedDiscount.isAskDerived)
        assertEquals(BigDecimal("-20.00"), clampedDiscount.spreadDeltaCny)
        assertEquals(BigDecimal("8.14"), clampedDiscount.residualCny)
        assertEquals(BigDecimal("0.00"), clampedDiscount.askPriceCny)

        // 4. Explicit spread + explicit ask keeps explicit ask (isAskDerived = false)
        val bothExplicit = TradeListingPriceParser.inferSpread(
            title = "出 DMIT LAX Pro Wee \$36.9/yr 2026-12-01到期 剩余价值+50 总价300r",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertFalse(bothExplicit.isDerived)
        assertFalse(bothExplicit.isAskDerived)
        assertEquals(BigDecimal("50.00"), bothExplicit.spreadDeltaCny)
        assertEquals(BigDecimal("300.00"), bothExplicit.askPriceCny)
        assertEquals(BigDecimal("133.20"), bothExplicit.residualCny)

        // 5. Concise peer omitting renewal uses referenceRenewal in BOTH directions
        val dmitRefRenewal = ParsedRenewalInfo(
            amount = BigDecimal("36.90"),
            currency = ValuationCurrency.USD,
            cycle = BillingCyclePreset.ANNUAL,
            matchedExpression = "\$36.9/yr",
            cycleExplicit = true,
        )
        // Total-ask peer + expiry + referenceRenewal => derived spread
        val askWithRef = TradeListingPriceParser.inferSpread(
            title = "出 DMIT LAX Pro Wee 2026-12-01到期 220r",
            fxToCny = fixedFx,
            now = fixedNow,
            referenceRenewal = dmitRefRenewal,
        )!!
        assertTrue(askWithRef.isDerived)
        assertFalse(askWithRef.isAskDerived)
        assertEquals(BigDecimal("133.20"), askWithRef.residualCny)
        assertEquals(BigDecimal("86.80"), askWithRef.spreadDeltaCny)
        assertEquals(BigDecimal("220.00"), askWithRef.askPriceCny)

        // Explicit-spread peer + expiry + referenceRenewal => derived total ask
        val spreadWithRef = TradeListingPriceParser.inferSpread(
            title = "出 DMIT LAX Pro Wee 2026-12-01到期 溢价+60出",
            fxToCny = fixedFx,
            now = fixedNow,
            referenceRenewal = dmitRefRenewal,
        )!!
        assertFalse(spreadWithRef.isDerived)
        assertTrue(spreadWithRef.isAskDerived)
        assertEquals(BigDecimal("60.00"), spreadWithRef.spreadDeltaCny)
        assertEquals(BigDecimal("133.20"), spreadWithRef.residualCny)
        assertEquals(BigDecimal("193.20"), spreadWithRef.askPriceCny)

        // 6. Inline stated residual + spread ("剩余价值180+50出", "残值180-20出", "剩余价值:180+50")
        val inlinePlus = TradeListingPriceParser.inferSpread(
            title = "出 DMIT LAX Pro Wee 剩余价值180+50出",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertFalse(inlinePlus.isDerived)
        assertTrue(inlinePlus.isAskDerived)
        assertEquals(BigDecimal("180.00"), inlinePlus.parsedDetails.statedResidualCny)
        assertEquals(BigDecimal("180.00"), inlinePlus.residualCny)
        assertEquals(BigDecimal("50.00"), inlinePlus.spreadDeltaCny)
        assertEquals(DealSpreadStatus.PREMIUM, inlinePlus.spreadStatus)
        assertEquals(BigDecimal("230.00"), inlinePlus.askPriceCny)

        val inlineMinus = TradeListingPriceParser.inferSpread(
            title = "出 DMIT LAX Pro Wee 残值180-20出",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertFalse(inlineMinus.isDerived)
        assertTrue(inlineMinus.isAskDerived)
        assertEquals(BigDecimal("180.00"), inlineMinus.residualCny)
        assertEquals(BigDecimal("-20.00"), inlineMinus.spreadDeltaCny)
        assertEquals(DealSpreadStatus.DISCOUNT, inlineMinus.spreadStatus)
        assertEquals(BigDecimal("160.00"), inlineMinus.askPriceCny)

        val inlineColonPlus = TradeListingPriceParser.inferSpread(
            title = "出 DMIT LAX Pro Wee 剩余价值:180+50r",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertFalse(inlineColonPlus.isDerived)
        assertTrue(inlineColonPlus.isAskDerived)
        assertEquals(BigDecimal("180.00"), inlineColonPlus.residualCny)
        assertEquals(BigDecimal("50.00"), inlineColonPlus.spreadDeltaCny)
        assertEquals(BigDecimal("230.00"), inlineColonPlus.askPriceCny)
    }

    @Test
    fun `inferSpread handles sub-95d and 375d uncycled annuals plus fresh-cycle expiry expressions`() {
        // 1. Uncycled $36.9 with 45 days remaining (<95d) infers ANNUAL and derives spread
        // 36.9 * 7.20 * 45 / 365 = 32.76; ask = 120 -> spread = +87.24
        val shortAnnual = TradeListingPriceParser.inferSpread(
            title = "出 DMIT LAX Pro Wee \$36.9 剩余45天 120r",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertTrue(shortAnnual.isDerived)
        assertEquals(BigDecimal("32.76"), shortAnnual.residualCny)
        assertEquals(BigDecimal("87.24"), shortAnnual.spreadDeltaCny)

        // 2. Uncycled $36.9 with 375 days remaining (newly renewed 371..380d) infers ANNUAL
        val overYearAnnual = TradeListingPriceParser.inferSpread(
            title = "出 DMIT LAX Pro Wee \$36.9 剩余375天 350r",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertTrue(overYearAnnual.isDerived)
        assertEquals(BigDecimal("272.96"), overYearAnnual.residualCny)
        assertEquals(BigDecimal("77.04"), overYearAnnual.spreadDeltaCny)

        // 3. Classic promo decimal 9.9刀 with 50 days left (>35d) infers ANNUAL
        val promoAnnual = TradeListingPriceParser.inferSpread(
            title = "出 斯巴达 9.9刀 剩余50天 90r",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertTrue(promoAnnual.isDerived)
        assertEquals(BigDecimal("9.76"), promoAnnual.residualCny)
        assertEquals(BigDecimal("80.24"), promoAnnual.spreadDeltaCny)

        // 4. Uncycled 4刀 with 18 days left (monthly Claw) must NOT guess ANNUAL
        val smallMonthlyAmbiguous = TradeListingPriceParser.inferSpread(
            title = "出 Claw HK 4刀 剩余18天 55r",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertFalse(smallMonthlyAmbiguous.hasSpread)
        assertTrue(smallMonthlyAmbiguous.isAskPriceOnly)
        assertEquals(BigDecimal("55.00"), smallMonthlyAmbiguous.askPriceCny)

        // 5. Fresh-cycle expiry expressions: 满年 / 刚续费 / 新开 (annual & monthly)
        val fullYear = TradeListingPriceParser.inferSpread(
            title = "出 DMIT LAX Pro Wee \$36.9/yr 满年 320r",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertTrue(fullYear.isDerived)
        assertEquals(BigDecimal("265.68"), fullYear.residualCny)
        assertEquals(BigDecimal("54.32"), fullYear.spreadDeltaCny)

        val justRenewed = TradeListingPriceParser.inferSpread(
            title = "出 搬瓦工 MEGABOX 45.68刀/年 刚续费 400r",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertTrue(justRenewed.isDerived)
        assertEquals(BigDecimal("328.90"), justRenewed.residualCny)
        assertEquals(BigDecimal("71.10"), justRenewed.spreadDeltaCny)

        val freshMonthly = TradeListingPriceParser.inferSpread(
            title = "出 Claw HK 4刀/月 新开 55r",
            fxToCny = fixedFx,
            now = fixedNow,
        )!!
        assertTrue(freshMonthly.isDerived)
        // 4 * 7.20 * 30 / 30 = 28.80 residual => spread = 55 - 28.80 = 26.20
        assertEquals(BigDecimal("28.80"), freshMonthly.residualCny)
        assertEquals(BigDecimal("26.20"), freshMonthly.spreadDeltaCny)

        // Fresh "新开" with injected monthly referenceRenewal adapts to 30d
        val monthlyRef = ParsedRenewalInfo(
            amount = BigDecimal("4.00"),
            currency = ValuationCurrency.USD,
            cycle = BillingCyclePreset.MONTHLY,
            matchedExpression = "4刀/月",
            cycleExplicit = true,
        )
        val freshWithMonthlyRef = TradeListingPriceParser.inferSpread(
            title = "出 Claw HK 新开 55r",
            fxToCny = fixedFx,
            now = fixedNow,
            referenceRenewal = monthlyRef,
        )!!
        assertTrue(freshWithMonthlyRef.isDerived)
        assertEquals(BigDecimal("28.80"), freshWithMonthlyRef.residualCny)
        assertEquals(BigDecimal("26.20"), freshWithMonthlyRef.spreadDeltaCny)
    }

    @Test
    fun `golden corpus validation achieves at least 90 percent accuracy across all pricing labels`() {
        val tsvText = javaClass.getResourceAsStream("/market/trade_titles_golden.tsv")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: File("src/test/resources/market/trade_titles_golden.tsv").readText(Charsets.UTF_8)

        val lines = tsvText.lineSequence().filter { it.isNotBlank() }.toList()
        assertTrue("Expected golden corpus rows", lines.size > 200)

        val goldenNow = LocalDate.of(2026, 11, 28)

        var spreadTotal = 0
        var spreadCorrect = 0
        var spreadFpCount = 0
        var spreadNegTotal = 0
        val spreadErrors = mutableListOf<String>()

        var askTotal = 0
        var askCorrect = 0
        var askFpCount = 0
        var askNegTotal = 0
        val askErrors = mutableListOf<String>()

        var renewalAmtTotal = 0
        var renewalAmtCorrect = 0
        var renewalCurTotal = 0
        var renewalCurCorrect = 0
        var renewalCycleTotal = 0
        var renewalCycleCorrect = 0
        var renewalFpCount = 0
        var renewalNegTotal = 0
        val renewalErrors = mutableListOf<String>()

        var expiryTotal = 0
        var expiryCorrect = 0
        val expiryErrors = mutableListOf<String>()

        var derivableTotal = 0
        var derivableSuccess = 0
        val derivedErrors = mutableListOf<String>()

        var intentTotal = 0
        var intentCorrect = 0
        val intentErrors = mutableListOf<String>()

        for (line in lines.drop(1)) {
            val cols = line.split('\t')
            if (cols.size < 15) continue
            val id = cols[0].trim()
            val title = cols[2]
            val body = cols[3].replace("\\n", "\n").ifBlank { null }
            val expectedIntent = TradeListingIntent.valueOf(cols[7].trim())
            val expectedSpread = cols[8].trim().takeIf { it.isNotEmpty() }?.let(::BigDecimal)
            val expectedAsk = cols[9].trim().takeIf { it.isNotEmpty() }?.let(::BigDecimal)
            val expectedRenewalAmt = cols[10].trim().takeIf { it.isNotEmpty() }?.let(::BigDecimal)
            val expectedRenewalCur = cols[11].trim().takeIf { it.isNotEmpty() }
            val expectedRenewalCycle = cols[12].trim().takeIf { it.isNotEmpty() }
            val expectedExpiry = cols[13].trim().takeIf { it.isNotEmpty() }

            val details = TradeListingPriceParser.parse(
                title = title,
                bodySnippet = body,
                now = goldenNow,
            )
            val inferred = TradeListingPriceParser.inferSpread(
                title = title,
                bodySnippet = body,
                now = goldenNow,
            )

            // Intent
            intentTotal++
            if (details.intent == expectedIntent) {
                intentCorrect++
            } else if (intentErrors.size < 25) {
                intentErrors += "  [#$id] intent exp=$expectedIntent act=${details.intent} title='$title'"
            }

            // Explicit spread
            if (expectedSpread != null) {
                spreadTotal++
                val actualSpread = details.explicitSpread?.deltaCny
                if (actualSpread != null && actualSpread.compareTo(expectedSpread) == 0) {
                    spreadCorrect++
                } else {
                    spreadErrors += "  [#$id] spread MISS exp=$expectedSpread act=$actualSpread title='$title' body='${body.orEmpty().take(60)}'"
                }
            } else {
                spreadNegTotal++
                if (details.explicitSpread != null) {
                    spreadFpCount++
                    spreadErrors += "  [#$id] spread FP act=${details.explicitSpread.deltaCny} (${details.explicitSpread.matchedExpression}) title='$title'"
                }
            }

            // Ask price CNY
            if (expectedAsk != null) {
                askTotal++
                val actualAsk = details.askPrice?.amountCny
                if (actualAsk != null && actualAsk.compareTo(expectedAsk) == 0) {
                    askCorrect++
                } else {
                    askErrors += "  [#$id] ask MISS exp=$expectedAsk act=$actualAsk title='$title' body='${body.orEmpty().take(60)}'"
                }
            } else {
                askNegTotal++
                if (details.askPrice != null) {
                    askFpCount++
                    askErrors += "  [#$id] ask FP act=${details.askPrice.amountCny} (${details.askPrice.matchedExpression}) title='$title' body='${body.orEmpty().take(60)}'"
                }
            }

            // Renewal amount / currency / cycle
            if (expectedRenewalAmt != null) {
                renewalAmtTotal++
                val actualRenewal = details.renewal
                if (actualRenewal != null && actualRenewal.amount.compareTo(expectedRenewalAmt) == 0) {
                    renewalAmtCorrect++
                } else {
                    renewalErrors += "  [#$id] renewal_amt MISS exp=$expectedRenewalAmt act=${actualRenewal?.amount} title='$title' body='${body.orEmpty().take(60)}'"
                }
                if (expectedRenewalCur != null) {
                    renewalCurTotal++
                    if (actualRenewal?.currency?.code.equals(expectedRenewalCur, ignoreCase = true)) {
                        renewalCurCorrect++
                    } else {
                        renewalErrors += "  [#$id] renewal_cur MISS exp=$expectedRenewalCur act=${actualRenewal?.currency?.code} title='$title'"
                    }
                }
                if (expectedRenewalCycle != null) {
                    renewalCycleTotal++
                    val actualCycleStr = actualRenewal?.cycle?.toGoldenCycleString()
                    if (actualCycleStr == expectedRenewalCycle) {
                        renewalCycleCorrect++
                    } else {
                        renewalErrors += "  [#$id] renewal_cycle MISS exp=$expectedRenewalCycle act=$actualCycleStr title='$title' body='${body.orEmpty().take(60)}'"
                    }
                }
            } else {
                renewalNegTotal++
                if (details.renewal != null) {
                    renewalFpCount++
                    renewalErrors += "  [#$id] renewal FP act=${details.renewal} title='$title' body='${body.orEmpty().take(60)}'"
                }
            }

            // Expiry
            if (expectedExpiry != null) {
                expiryTotal++
                val actualExpiry = details.expiry
                if (actualExpiry != null && matchesGoldenExpiry(actualExpiry, expectedExpiry)) {
                    expiryCorrect++
                } else {
                    expiryErrors += "  [#$id] expiry MISS exp=$expectedExpiry act=$actualExpiry title='$title' body='${body.orEmpty().take(60)}'"
                }
            }

            // Derived spread end-to-end check on derivable rows (ask + renewal + cycle + expiry, no explicit spread)
            if (expectedAsk != null &&
                expectedSpread == null &&
                expectedRenewalAmt != null &&
                expectedRenewalCur != null &&
                expectedRenewalCycle != null &&
                expectedExpiry != null
            ) {
                derivableTotal++
                if (inferred != null &&
                    inferred.isDerived &&
                    inferred.hasSpread &&
                    inferred.askPriceCny != null &&
                    inferred.residualCny != null &&
                    inferred.askPriceCny.subtract(inferred.residualCny).compareTo(inferred.spreadDeltaCny) == 0
                ) {
                    derivableSuccess++
                } else {
                    derivedErrors += "  [#$id] derived MISS inferred=$inferred title='$title'"
                }
            }
        }

        val spreadAcc = spreadCorrect.toDouble() / spreadTotal
        val askAcc = askCorrect.toDouble() / askTotal
        val renewalAmtAcc = renewalAmtCorrect.toDouble() / renewalAmtTotal
        val renewalCurAcc = renewalCurCorrect.toDouble() / renewalCurTotal
        val renewalCycleAcc = renewalCycleCorrect.toDouble() / renewalCycleTotal
        val expiryAcc = expiryCorrect.toDouble() / expiryTotal
        val derivedAcc = derivableSuccess.toDouble() / derivableTotal
        val intentAcc = intentCorrect.toDouble() / intentTotal

        println(
            buildString {
                appendLine("=== TradeListingPriceParser Golden TSV Report ===")
                appendLine(String.format(Locale.US, "  Explicit spread:  %d/%d (%.1f%%) | FP=%d/%d", spreadCorrect, spreadTotal, spreadAcc * 100, spreadFpCount, spreadNegTotal))
                appendLine(String.format(Locale.US, "  Ask price CNY:    %d/%d (%.1f%%) | FP=%d/%d", askCorrect, askTotal, askAcc * 100, askFpCount, askNegTotal))
                appendLine(String.format(Locale.US, "  Renewal amount:   %d/%d (%.1f%%) | FP=%d/%d", renewalAmtCorrect, renewalAmtTotal, renewalAmtAcc * 100, renewalFpCount, renewalNegTotal))
                appendLine(String.format(Locale.US, "  Renewal currency: %d/%d (%.1f%%)", renewalCurCorrect, renewalCurTotal, renewalCurAcc * 100))
                appendLine(String.format(Locale.US, "  Renewal cycle:    %d/%d (%.1f%%)", renewalCycleCorrect, renewalCycleTotal, renewalCycleAcc * 100))
                appendLine(String.format(Locale.US, "  Expiry:           %d/%d (%.1f%%)", expiryCorrect, expiryTotal, expiryAcc * 100))
                appendLine(String.format(Locale.US, "  Derived spread:   %d/%d (%.1f%%)", derivableSuccess, derivableTotal, derivedAcc * 100))
                appendLine(String.format(Locale.US, "  Intent:           %d/%d (%.1f%%)", intentCorrect, intentTotal, intentAcc * 100))
                if (spreadErrors.isNotEmpty()) {
                    appendLine("  Spread errors:")
                    spreadErrors.take(20).forEach { appendLine(it) }
                }
                if (askErrors.isNotEmpty()) {
                    appendLine("  Ask errors:")
                    askErrors.take(20).forEach { appendLine(it) }
                }
                if (renewalErrors.isNotEmpty()) {
                    appendLine("  Renewal errors:")
                    renewalErrors.take(20).forEach { appendLine(it) }
                }
                if (expiryErrors.isNotEmpty()) {
                    appendLine("  Expiry errors:")
                    expiryErrors.take(20).forEach { appendLine(it) }
                }
                if (derivedErrors.isNotEmpty()) {
                    appendLine("  Derived errors:")
                    derivedErrors.take(20).forEach { appendLine(it) }
                }
                if (intentErrors.isNotEmpty()) {
                    appendLine("  Intent errors:")
                    intentErrors.take(20).forEach { appendLine(it) }
                }
            },
        )

        assertTrue("Explicit spread accuracy $spreadAcc < 0.90", spreadAcc >= 0.90)
        assertTrue("Ask price accuracy $askAcc < 0.90", askAcc >= 0.90)
        assertTrue("Renewal amount accuracy $renewalAmtAcc < 0.90", renewalAmtAcc >= 0.90)
        assertTrue("Renewal currency accuracy $renewalCurAcc < 0.90", renewalCurAcc >= 0.90)
        assertTrue("Renewal cycle accuracy $renewalCycleAcc < 0.90", renewalCycleAcc >= 0.90)
        assertTrue("Expiry accuracy $expiryAcc < 0.90", expiryAcc >= 0.90)
        assertTrue("Derived spread yield $derivedAcc < 0.90", derivedAcc >= 0.90)
        assertTrue("Intent accuracy $intentAcc < 0.90", intentAcc >= 0.90)
    }

    private fun BillingCyclePreset.toGoldenCycleString(): String? = when (this) {
        BillingCyclePreset.MONTHLY -> "month"
        BillingCyclePreset.QUARTERLY -> "quarter"
        BillingCyclePreset.SEMI_ANNUAL -> "semi_annual"
        BillingCyclePreset.ANNUAL -> "year"
        BillingCyclePreset.BIENNIAL -> "2year"
        BillingCyclePreset.TRIENNIAL -> "3year"
        else -> null
    }

    private fun matchesGoldenExpiry(actual: ParsedExpiryInfo, expected: String): Boolean {
        return when {
            expected.endsWith("d") -> {
                val days = expected.removeSuffix("d").toIntOrNull()
                days != null && actual.remainingDays == days
            }
            expected.startsWith("--") && expected.length == 7 -> {
                // --MM-DD
                val parts = expected.removePrefix("--").split('-')
                parts.size == 2 &&
                    actual.expiryDate.monthValue == parts[0].toInt() &&
                    actual.expiryDate.dayOfMonth == parts[1].toInt()
            }
            expected.startsWith("--") && expected.length == 4 -> {
                // --MM
                val month = expected.removePrefix("--").toInt()
                actual.expiryDate.monthValue == month
            }
            expected.length == 7 -> {
                // YYYY-MM
                val parts = expected.split('-')
                parts.size == 2 &&
                    actual.expiryDate.year == parts[0].toInt() &&
                    actual.expiryDate.monthValue == parts[1].toInt()
            }
            else -> {
                // YYYY-MM-DD
                actual.expiryDate == LocalDate.parse(expected)
            }
        }
    }
}
