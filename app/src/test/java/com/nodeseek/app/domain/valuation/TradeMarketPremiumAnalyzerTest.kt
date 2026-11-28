package com.nodeseek.app.domain.valuation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class TradeMarketPremiumAnalyzerTest {

    @Test
    fun `extractSpread parses explicit premium discount par and closed sold titles accurately`() {
        val p1 = TradeMarketPremiumAnalyzer.extractSpread("[出] DMIT LAX.Pro.Wee 溢价 +80 包Push")!!
        assertEquals(BigDecimal("80.00"), p1.deltaCny)
        assertEquals(DealSpreadStatus.PREMIUM, p1.spreadStatus)
        assertEquals(TradeListingIntent.SELL, p1.intent)
        assertFalse(p1.isClosedSold)

        val p2 = TradeMarketPremiumAnalyzer.extractSpread("[出] 瓦工 MEGABOX Pro 剩余价值+120r")!!
        assertEquals(BigDecimal("120.00"), p2.deltaCny)
        assertEquals(DealSpreadStatus.PREMIUM, p2.spreadStatus)

        val p3 = TradeMarketPremiumAnalyzer.extractSpread("[出] BandwagonHOST DC6 折价 -35 出")!!
        assertEquals(BigDecimal("-35.00"), p3.deltaCny)
        assertEquals(DealSpreadStatus.DISCOUNT, p3.spreadStatus)

        val p4 = TradeMarketPremiumAnalyzer.extractSpread("[出] RackNerd 2C2G DC02 剩余价值-20出")!!
        assertEquals(BigDecimal("-20.00"), p4.deltaCny)
        assertEquals(DealSpreadStatus.DISCOUNT, p4.spreadStatus)

        val p5 = TradeMarketPremiumAnalyzer.extractSpread("[出] 绿云 软银 平出 原邮")!!
        assertEquals(BigDecimal("0.00"), p5.deltaCny)
        assertEquals(DealSpreadStatus.PAR, p5.spreadStatus)

        val p6 = TradeMarketPremiumAnalyzer.extractSpread("[出] Claw HK 原价出")!!
        assertEquals(BigDecimal("0.00"), p6.deltaCny)
        assertEquals(DealSpreadStatus.PAR, p6.spreadStatus)

        val p7 = TradeMarketPremiumAnalyzer.extractSpread("[已出] DMIT TYO.Pro.Tiny 溢价 50")!!
        assertEquals(BigDecimal("50.00"), p7.deltaCny)
        assertEquals(DealSpreadStatus.PREMIUM, p7.spreadStatus)
        assertTrue(p7.isClosedSold)

        val p8 = TradeMarketPremiumAnalyzer.extractSpread("[求购] DMIT Pro 溢价 60 收")!!
        assertEquals(BigDecimal("60.00"), p8.deltaCny)
        assertEquals(TradeListingIntent.BUY, p8.intent)
    }

    @Test
    fun `extractSpread filters out specs dates ASNs IPs percentages discounts and total asks`() {
        assertNull(
            TradeMarketPremiumAnalyzer.extractSpread(
                "DMIT Pro 1C1G 20G 100M 1Gbps 2026-08到期 CN2 GIA AS9929 1.2.3.4:8080 \$39.9/yr 溢价出 300r",
            ),
        )
        assertNull(TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 溢价 15%"))
        assertNull(TradeMarketPremiumAnalyzer.extractSpread("RackNerd 8折出"))
        assertNull(TradeMarketPremiumAnalyzer.extractSpread("2026-05-01出 DMIT Pro"))
        assertNull(TradeMarketPremiumAnalyzer.extractSpread("DC-02出 RackNerd 10刀年付"))
    }

    @Test
    fun `matchesQuery handles bidirectional merchant and model aliases with ASCII boundary safety`() {
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("出 大妈 LAX.Pro.Tiny 溢价+50", "DMIT Pro"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("DMIT EB Wee 溢价+50", "DMIT Pro"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("promo 活动", "DMIT Pro"))

        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("出 BandwagonHost MEGABOX Pro 剩余价值+100", "瓦工 MEGABOX"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("BWG MegaBox 溢价 80", "瓦工 MEGABOX"))

        // Short alias `rn` must NOT falsely match `return` or `berlin`
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("[出] RN 2C2G DC02 折价 -15", "RackNerd"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("berlin return 节点 溢价 20", "RackNerd"))
    }

    @Test
    fun `extractSuggestedQuery produces concise merchant and model query`() {
        val suggested = TradeMarketPremiumAnalyzer.extractSuggestedQuery(
            "[出] DMIT LAX.Pro.Tiny 1C1G 100M 2026到期 溢价+80",
        )
        assertTrue(suggested.startsWith("DMIT"))
        assertTrue(suggested.contains("pro", ignoreCase = true))
        assertTrue(suggested.contains("tiny", ignoreCase = true))
        assertFalse(suggested.contains("1c1g", ignoreCase = true))
        assertFalse(suggested.contains("2026"))
        assertFalse(suggested.contains("80"))
    }

    @Test
    fun `analyze computes deterministic quantiles deduplicates by topicId and filters buy requests`() {
        val candidates = listOf(
            TradeCandidateTopic(1L, "[出] DMIT Pro 溢价 +20", MarketDataSource.FEED_CACHE),
            TradeCandidateTopic(1L, "[出] DMIT Pro 溢价 +999", MarketDataSource.READ_HISTORY), // lower priority duplicate
            TradeCandidateTopic(2L, "[出] 大妈 LAX.Pro.Wee 溢价 +40", MarketDataSource.BOOKMARK),
            TradeCandidateTopic(3L, "[出] DMIT Pro.Tiny 剩余价值+60", MarketDataSource.REMOTE_SEARCH),
            TradeCandidateTopic(4L, "[出] DMIT Pro 平出", MarketDataSource.FEED_CACHE),
            TradeCandidateTopic(5L, "[求购] DMIT Pro 溢价 +200 收", MarketDataSource.FEED_CACHE), // BUY excluded by default
            TradeCandidateTopic(6L, "[出] DMIT Pro 1C1G 到期转让", MarketDataSource.FEED_CACHE), // matched but no numeric spread
        )

        val dist = TradeMarketPremiumAnalyzer.analyze(
            query = "DMIT Pro",
            candidates = candidates,
            includeBuyRequests = false,
        )

        assertEquals(4, dist.sampleCount)
        assertEquals(6, dist.matchedTopicCount)
        assertEquals(3, dist.premiumCount)
        assertEquals(1, dist.parCount)
        assertEquals(0, dist.discountCount)
        assertEquals(3, dist.localSampleCount)
        assertEquals(1, dist.remoteSampleCount)

        // Sorted: [0.00, 20.00, 40.00, 60.00]
        assertEquals(BigDecimal("0.00"), dist.minCny)
        assertEquals(BigDecimal("15.00"), dist.p25Cny)
        assertEquals(BigDecimal("30.00"), dist.medianCny)
        assertEquals(BigDecimal("45.00"), dist.p75Cny)
        assertEquals(BigDecimal("60.00"), dist.maxCny)

        // Including buy requests adds topic 5 (+200)
        val withBuy = TradeMarketPremiumAnalyzer.analyze(
            query = "DMIT Pro",
            candidates = candidates,
            includeBuyRequests = true,
        )
        assertEquals(5, withBuy.sampleCount)
        assertEquals(BigDecimal("40.00"), withBuy.medianCny)
        assertNotNull(withBuy.maxCny)
        assertEquals(BigDecimal("200.00"), withBuy.maxCny)
    }

    @Test
    fun `extractSpread preserves 1-2 digit decimal spreads while scrubbing dates and separator dashes`() {
        assertEquals(BigDecimal("45.50"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 剩余价值+45.5")?.deltaCny)
        assertEquals(BigDecimal("12.50"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 溢价+12.5")?.deltaCny)
        assertEquals(BigDecimal("9.90"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 溢价 9.9")?.deltaCny)
        assertEquals(BigDecimal("-19.90"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 折价-19.9")?.deltaCny)
        assertEquals(BigDecimal("15.50"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro +15.5出")?.deltaCny)
        assertEquals(BigDecimal("-8.80"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro -8.8出")?.deltaCny)
        assertEquals(BigDecimal("-12.50"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 剩余价值-12.5")?.deltaCny)

        // Dates with dot or slash remain scrubbed
        assertNull(TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 05.01到期"))
        assertEquals(BigDecimal("20.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 05.01到期 溢价+20")?.deltaCny)
        assertNull(TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 5/1出"))

        // Separator dashes (` - 200r出`, ` - 10出`) must NOT parse as negative discounts, while `-20出` does
        assertNull(TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 续费\$39.9/yr - 200r出"))
        assertNull(TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 套餐 - 10出"))
        val directDiscount = TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro -20出")
        assertNotNull(directDiscount)
        assertEquals(BigDecimal("-20.00"), directDiscount!!.deltaCny)
        assertEquals(DealSpreadStatus.DISCOUNT, directDiscount.spreadStatus)
    }

    @Test
    fun `extractSuggestedQuery preserves bracketed merchant tags and matchesQuery splits CJK ASCII boundaries`() {
        val suggested = TradeMarketPremiumAnalyzer.extractSuggestedQuery("[已出] [DMIT] Pro Wee +50出")
        assertTrue(suggested.contains("DMIT"))
        assertTrue(suggested.contains("pro", ignoreCase = true))
        assertTrue(suggested.contains("wee", ignoreCase = true))
        assertFalse(suggested.contains("50"))
        assertFalse(suggested.contains("已出"))

        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("BWG MEGABOX +100出", "瓦工MEGABOX"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("搬瓦工MEGABOX Pro 溢价+80", "BWG MEGABOX"))
    }

    @Test
    fun `extractSpread handles colon-separated spreads, 溢价出 50, and 剩余价值 colon without breaking total-ask filter`() {
        assertEquals(BigDecimal("50.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 溢价:50")?.deltaCny)
        assertEquals(BigDecimal("50.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 溢价：50")?.deltaCny)
        assertEquals(BigDecimal("-30.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 折价:30")?.deltaCny)
        assertEquals(BigDecimal("-30.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 折价：30")?.deltaCny)
        assertEquals(BigDecimal("50.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 溢价出 50")?.deltaCny)
        assertEquals(BigDecimal("100.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 剩余价值:100")?.deltaCny)
        // High total ask after 溢价出 (>100) still safely rejected
        assertNull(TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 1C1G 2026-01-01到期 1.2.3.4:8080 \$39.9/yr 溢价出 300r"))
    }

    @Test
    fun `extractSuggestedQuery accurately extracts real-world NodeSeek VPS models with search-safe punctuation`() {
        assertEquals(
            "DMIT LAX Pro WEE",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery("[出] DMIT LAX.Pro.WEE \$36.9/yr 剩余价值+80出"),
        )
        assertEquals(
            "DMIT LAX Pro WEE",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery("[出] DMIT PVM.LAX.Pro.WEE \$36.9/yr 2026-12-01到期 带原油"),
        )
        assertEquals(
            "BWG MEGABOX PRO",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery("【出】搬瓦工 BWG MEGABOX-PRO DC6 CN2 GIA-E"),
        )
        assertEquals(
            "BWG DC6 CN2 GIA",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery("【出】搬瓦工 DC6 CN2 GIA-E 传家宝 剩余价值+100"),
        )
        assertEquals(
            "CLAW HK",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery("明盘300出 Claw HK 4刀 1C2G 1T"),
        )
        assertEquals(
            "GREENCLOUD Tokyo IIJ 2222",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery("出绿云 JP Tokyo IIJ 2222 三年付"),
        )
        assertEquals(
            "RACKNERD 黑五 DC02",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 RN 2024黑五 2C2.5G DC02"),
        )
        assertEquals(
            "DMIT HKG EB CORONA",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery("DMIT HKG.EB.CORONA"),
        )
        assertEquals(
            "V.PS Tokyo Softbank Mini Pro",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery("V.PS Tokyo Softbank Mini Pro"),
        )
        assertEquals(
            "RFC JP T1 CO",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery("RFC JP T1 CO"),
        )
        assertEquals(
            "NETCUP RS1000 G9.5",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery("Netcup RS1000 G9.5"),
        )
        assertEquals(
            "BWG PLAN v2",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery("瓦工 THE PLAN v2"),
        )
        assertEquals(
            "YXVM HK Vol",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery("YxVM HK Vol"),
        )

        // Ensure extracted queries never contain search-hostile punctuation (/, [, ], $, +, ?, #, %)
        val samples = listOf(
            "[出] DMIT LAX.Pro.WEE \$36.9/yr 剩余价值+80出",
            "【出】搬瓦工 BWG MEGABOX-PRO DC6 CN2 GIA-E",
            "明盘300出 Claw HK 4刀 1C2G 1T流量 1Gbps 11.28到期 记者勿扰",
            "出 DMIT LAX/Pro/WEE 49.9刀 ¥200 传家宝 好鸡 秒出 自刀 可小刀 带原油 改邮 工单",
        )
        val hostileChars = setOf('/', '[', ']', '【', '】', '$', '+', '?', '#', '%', '&')
        samples.forEach { title ->
            val q = TradeMarketPremiumAnalyzer.extractSuggestedQuery(title)
            assertTrue("Expected non-blank query for '$title'", q.isNotBlank())
            assertTrue(
                "Query '$q' from '$title' must not contain hostile punctuation",
                q.none { it in hostileChars },
            )
        }
    }

    @Test
    fun `extractSuggestedQuery normalizes expanded vendor aliases and falls back to spec tier when no model exists`() {
        assertEquals("CLOUDCONE DC2", TradeMarketPremiumAnalyzer.extractSuggestedQuery("[出] CC 2C2G DC2 10.99刀年付 带原油"))
        assertEquals("SHARON HK Standard", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 Sharon HK Standard 剩余价值+40"))
        assertEquals("HALO HK BGP", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 Halo HK BGP 溢价50"))
        assertEquals("MISAKA HKG S2", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 御坂 HKG S2 原价出"))
        assertEquals("AKKO 圣何塞 CN2 GIA", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 AkkoCloud 圣何塞 CN2 GIA 折价30"))
        assertEquals("SALTYFISH SJC Pro", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 咸鱼云 美西 SJC Pro 溢价+30"))
        assertEquals("KIRINO 东京 软银", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 Kirino 东京 软银 剩余价值-20出"))
        assertEquals("ALICE HK Free", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 爱丽丝 HK Free 平出"))
        assertEquals("CERA 洛杉矶 CMIN2", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 Cera 洛杉矶 CMIN2 溢价+60"))
        assertEquals("OVH KS LE B", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 OVH KS-LE-B 杜甫 剩余价值+100"))
        assertEquals("HETZNER AX41 芬兰", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 HZ AX41 芬兰 剩余价值出"))
        assertEquals("SPARTAN 西雅图", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 斯巴达 西雅图 24刀 2C2G 溢价+50"))
        assertEquals("BUYVM 卢森堡", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 BuyVM 卢森堡 1C512M 3.5刀月付"))
        assertEquals("BUYVM 卢森堡", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 卢森堡 512M 平出"))
        assertEquals("VOLC 香港", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 火山云 香港 1C2G 溢价+20"))
        assertEquals("DEDIPATH LA", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 DediPath LA 2C2G 剩余价值出"))

        // Hardware specs stripped when model/region exists, but kept as sensible spec-tier fallback when none exists
        assertEquals("RACKNERD 2C2.5G", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 RN 2C2.5G 10.99刀年付 带原油"))
        assertEquals("CLAW 1C2G", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 Claw 4刀 1C2G 1T流量 原邮"))

        // Pure trade noise title without fallback returns empty string rather than garbage
        assertEquals("", TradeMarketPremiumAnalyzer.extractSuggestedQuery("[出] 出个传家宝好鸡 极品秒出 自刀可小刀 带原油改邮 记者勿扰 剩余价值+50"))

        // Short 2-char ASCII aliases must not trigger on TLD suffixes like .cc / .nc
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("分享网站 test.cc 教程", "CloudCone"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("分享网站 demo.nc 教程", "Netcup"))
    }

    @Test
    fun `extractSuggestedQuery uses fallbackText when title is vague or merchant-only without overriding strong titles`() {
        // Vague noise-only title falls back to OP body
        val fromBody = TradeMarketPremiumAnalyzer.extractSuggestedQuery(
            title = "[出] 出个传家宝好鸡 剩余价值+50",
            fallbackText = """
                ```bash
                # bench log ignored
                Geekbench 6 Score: 1234
                ```
                出吃灰机器，型号：DMIT LAX.Pro.WEE
                续费 ${'$'}36.9/yr，2026-12-01到期，带原油出。
            """.trimIndent(),
        )
        assertEquals("DMIT LAX Pro WEE", fromBody)

        // Merchant-only weak title enriched by labeled plan line in OP body
        val enrichedMerchant = TradeMarketPremiumAnalyzer.extractSuggestedQuery(
            title = "[出] 出个大妈传家宝 剩余价值+80",
            fallbackText = "套餐：LAX.Pro.WEE\n2026-12-01到期，原邮给。",
        )
        assertEquals("DMIT LAX Pro WEE", enrichedMerchant)

        // Strong title is NOT overridden by other vendors mentioned in body
        val keepStrongTitle = TradeMarketPremiumAnalyzer.extractSuggestedQuery(
            title = "[出] DMIT LAX.Pro.WEE 剩余价值+80出",
            fallbackText = "也可换 搬瓦工 MEGABOX-PRO 或 绿云 东京 软银",
        )
        assertEquals("DMIT LAX Pro WEE", keepStrongTitle)
    }

    @Test
    fun `matchesQuery strictly disambiguates tiers models generations and regions`() {
        // DMIT tiers and plans must not cross-pollute
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("[出] 大妈 LAX.Pro.WEE 剩余价值+60", "DMIT Pro WEE"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("[出] DMIT PVM.LAX.Pro.Wee 溢价+50", "DMIT LAX Pro WEE"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("[出] DMIT LAX.Pro.Malibu 溢价+120", "DMIT Pro WEE"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("[出] DMIT HKG.EB.CORONA 溢价+150", "DMIT Pro WEE"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("[出] DMIT LAX.EB.WEE 溢价+30", "DMIT Pro WEE"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("[出] DMIT LAX.Pro.Wee 溢价+60", "DMIT LAX Pro Tiny"))

        // BWG MEGABOX must match MEGABOX / MEGABOX-PRO and reject BIGGERBOX / MINIBOX / THE PLAN
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("[出] BWG MegaBox 折价 -25 出", "BWG MEGABOX PRO"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("[出] 搬瓦工 MEGABOX-PRO DC6 溢价+100", "BWG MEGABOX"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("[出] 搬瓦工 BIGGERBOX-PRO 溢价+100", "BWG MEGABOX"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("[出] 搬瓦工 BIGGERBOX-PRO 溢价+100", "BWG MEGABOX PRO"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("[出] 搬瓦工 MINIBOX 溢价+50", "BWG MEGABOX"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("[出] 搬瓦工 THE PLAN v1 溢价+100", "BWG PLAN v2"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("[出] 瓦工 THE PLAN v2 溢价+200", "BWG PLAN v2"))

        // Netcup generations (G9 vs G9.5 vs G11) and spaced RS 1000
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("[出] Netcup RS 1000 G9.5 平出", "NETCUP RS1000 G9.5"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("[出] Netcup RS1000 G9 平出", "NETCUP RS1000 G9.5"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("[出] Netcup RS1000 G9.5 平出", "NETCUP RS1000 G9"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("[出] Netcup RS1000 G11 平出", "NETCUP RS1000 G9.5"))

        // V.PS, GreenCloud, RackNerd, Claw, BuyVM, YxVM, RFC
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("[出] V.PS 东京 软银 Mini Pro +50出", "V.PS Tokyo Softbank Mini Pro"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("[出] V.PS 东京 软银 Mini +30出", "V.PS Tokyo Softbank Mini Pro"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("出绿云东京IIJ 2222 三年付 平出", "GREENCLOUD Tokyo IIJ 2222"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("出绿云东京软银2222 三年付 平出", "GREENCLOUD Tokyo IIJ 2222"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("GreenCloud Tokyo Softbank 2222 平出", "绿云软银"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("[出] RN 2024 BlackFriday DC-02 折价20", "RACKNERD 黑五 DC02"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("[出] RackNerd 黑五 DC2 折价15", "RACKNERD 黑五 DC02"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("[出] RN 黑五 DC03 折价15", "RACKNERD 黑五 DC02"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("[出] Claw SG 4刀 溢价+30", "CLAW HK"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("[出] 卢森堡 1C512M 平出", "BUYVM 卢森堡"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("[出] BuyVM LU Slice 平出", "BUYVM 卢森堡"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("[出] HostHatch 卢森堡 平出", "BUYVM 卢森堡"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("[出] BuyVM 拉斯维加斯 平出", "BUYVM 卢森堡"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("[出] YxVM HK Vol 溢价+40", "YXVM HK Vol"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("[出] YxVM HK Eco 溢价+40", "YXVM HK Vol"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("[出] RFC JP T1 CO 溢价+80", "RFC JP T1 CO"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("[出] RFC HK T1 CO 溢价+80", "RFC JP T1 CO"))
    }

    @Test
    fun `extractSpread handles extended Chinese spread phrasings while rejecting buyout prices and renewal fees`() {
        assertEquals(BigDecimal("50.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 剩余价值+50")?.deltaCny)
        assertEquals(BigDecimal("50.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 净残值+50")?.deltaCny)
        assertEquals(BigDecimal("50.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 残值+50")?.deltaCny)
        assertEquals(BigDecimal("50.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 溢价50")?.deltaCny)
        assertEquals(BigDecimal("50.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 溢价+50")?.deltaCny)
        assertEquals(BigDecimal("50.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro +50出")?.deltaCny)
        assertEquals(BigDecimal("-30.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 剩余价值-30")?.deltaCny)
        assertEquals(BigDecimal("-30.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 折价30")?.deltaCny)
        assertEquals(BigDecimal("-30.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro -30出")?.deltaCny)
        assertEquals(BigDecimal("-30.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 亏30出")?.deltaCny)
        assertEquals(BigDecimal("-50.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 血亏50出")?.deltaCny)
        assertEquals(BigDecimal("0.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 剩余价值出")?.deltaCny)
        assertEquals(BigDecimal("0.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 净残值出")?.deltaCny)
        assertEquals(BigDecimal("0.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 残值出")?.deltaCny)
        assertEquals(BigDecimal("0.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 平出")?.deltaCny)
        assertEquals(BigDecimal("0.00"), TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 原价出")?.deltaCny)

        // Renewal prices, specs, dates, and total buyout asks without spread markers MUST return null
        assertNull(TradeMarketPremiumAnalyzer.extractSpread("明盘300出 Claw HK 4刀 1C2G 1T"))
        assertNull(TradeMarketPremiumAnalyzer.extractSpread("250出 DMIT LAX.Pro.WEE"))
        assertNull(TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 250出"))
        assertNull(TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro \$39.9/yr 2026-12-01到期"))
        assertNull(TradeMarketPremiumAnalyzer.extractSpread("Claw HK 36.9刀年付"))
        assertNull(TradeMarketPremiumAnalyzer.extractSpread("出 RN 2024黑五 2C2.5G DC02"))
        assertNull(TradeMarketPremiumAnalyzer.extractSpread("DMIT Pro 亏本出 300r"))
    }

    @Test
    fun `analyze populates quantiles for 1 and 2 samples and excludes mismatched model tiers`() {
        // Single sample still populates min/p25/median/p75/max so UI triggers reference display
        val single = TradeMarketPremiumAnalyzer.analyze(
            query = "DMIT LAX Pro WEE",
            candidates = listOf(
                TradeCandidateTopic(1L, "[出] DMIT LAX.Pro.WEE 剩余价值+60出", MarketDataSource.FEED_CACHE),
                TradeCandidateTopic(2L, "[出] DMIT LAX.Pro.Malibu 溢价+200", MarketDataSource.FEED_CACHE),
                TradeCandidateTopic(3L, "[出] DMIT HKG.EB.CORONA 溢价+150", MarketDataSource.FEED_CACHE),
            ),
        )
        assertEquals(1, single.sampleCount)
        assertEquals(1, single.matchedTopicCount)
        assertEquals(BigDecimal("60.00"), single.minCny)
        assertEquals(BigDecimal("60.00"), single.p25Cny)
        assertEquals(BigDecimal("60.00"), single.medianCny)
        assertEquals(BigDecimal("60.00"), single.p75Cny)
        assertEquals(BigDecimal("60.00"), single.maxCny)

        // Two samples interpolate p25/median/p75 cleanly
        val pair = TradeMarketPremiumAnalyzer.analyze(
            query = "BWG MEGABOX PRO",
            candidates = listOf(
                TradeCandidateTopic(10L, "[出] 搬瓦工 MEGABOX-PRO 溢价+80", MarketDataSource.FEED_CACHE),
                TradeCandidateTopic(11L, "[出] BWG MegaBox 剩余价值+120出", MarketDataSource.REMOTE_SEARCH),
                TradeCandidateTopic(12L, "[出] 搬瓦工 BIGGERBOX-PRO 溢价+300", MarketDataSource.FEED_CACHE),
                TradeCandidateTopic(13L, "[出] 搬瓦工 MINIBOX 溢价+20", MarketDataSource.FEED_CACHE),
            ),
        )
        assertEquals(2, pair.sampleCount)
        assertEquals(2, pair.matchedTopicCount)
        assertEquals(BigDecimal("80.00"), pair.minCny)
        assertEquals(BigDecimal("90.00"), pair.p25Cny)
        assertEquals(BigDecimal("100.00"), pair.medianCny)
        assertEquals(BigDecimal("110.00"), pair.p75Cny)
        assertEquals(BigDecimal("120.00"), pair.maxCny)
    }

    @Test
    fun `buildSearchFallbackQueries produces progressively simpler punctuation-safe queries`() {
        val dmitFallbacks = TradeMarketPremiumAnalyzer.buildSearchFallbackQueries("DMIT LAX Pro WEE")
        assertTrue(dmitFallbacks.contains("DMIT Pro WEE"))
        assertTrue(dmitFallbacks.contains("DMIT"))
        assertFalse(dmitFallbacks.contains("DMIT LAX Pro WEE"))

        val bwgFallbacks = TradeMarketPremiumAnalyzer.buildSearchFallbackQueries("BWG MEGABOX PRO")
        assertTrue(bwgFallbacks.contains("BWG MEGABOX"))
        assertTrue(bwgFallbacks.contains("BWG"))

        assertTrue(TradeMarketPremiumAnalyzer.buildSearchFallbackQueries("RackNerd").isEmpty())
    }

    @Test
    fun `extractSuggestedQuery handles cloud vendors, Chinese slang, multi-word vendors, and noisy ASCII cleanly`() {
        // Cloud providers & Chinese slang
        assertEquals("ALIYUN 香港 轻量", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出阿里云香港轻量 200M 24元/月 150r"))
        assertEquals("ALIYUN 新加坡 轻量", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 阿里云 新加坡 轻量 200M 200r"))
        assertEquals("ALIYUN 香港 轻量", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 套路云 香港 轻量 200M 160r"))
        assertEquals("TENCENT 香港 轻量", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 腾讯云 香港 轻量 200M 180r"))
        assertEquals("TENCENT 香港 轻量", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 良心云 香港 轻量 200M 170r"))
        assertEquals("AWS Lightsail 新加坡", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 亚马逊 Lightsail 新加坡 5刀/月 35r"))
        assertEquals("AWS 8v", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 AWS 8v 账号 75r"))
        assertEquals("GCP", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 谷歌云 300刀 赠金号 120r"))
        assertEquals("AZURE", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 Azure 100刀 学生号 45r"))
        assertEquals("AZURE AZ100", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 微软云 AZ100 学生号 50元"))
        assertEquals("DIGITALOCEAN", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 DO 200刀 账号 35r"))
        assertEquals("SALTYFISH SJC Pro", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 咸鱼 SJC Pro"))
        assertEquals("SALTYFISH 圣何塞 pro", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出咸鱼圣何塞pro 溢价40"))
        assertEquals("CLAW SG", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 阿里爪 SG 4刀/月 55r"))
        assertEquals("GREENCLOUD 坡县 2525", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 绿云 坡县 2525 三年付 310r"))

        // Multi-word vendors & noisy ASCII tokens stripped via positive allow-list
        assertEquals("SPARTAN Seattle 4837", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 Spartan Host Seattle 4837 16刀/年 240r"))
        assertEquals("BWG DC6 CN2 GIA", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 Bandwagon Host DC6 CN2 GIA 49.9刀/年 340r"))
        assertEquals("SALTYFISH SJC Pro", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 SaltyFish Tech SJC Pro 溢价30"))
        assertEquals("SHARON HK Standard", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 Sharon Network HK Standard 380r"))
        assertEquals("CLAW HK", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 Claw Cloud HK 4刀/月 晚高峰不炸 解锁Disney TikTok 85r"))
        assertEquals("DMIT LAX Pro Wee", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 DMIT LAX Pro Wee 原生IP 解锁Netflix ChatGPT 300r"))
        assertEquals("NETCUP RS1000 G11", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 Netcup RS1000 G11 AMD EPYC DDR5 免税号 push 120r"))
        assertEquals("NETCUP RS2000 G11", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 Netcup RS 2000 G11 剩余价值+30"))
        assertEquals("OVH VPS 1", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 OVH VPS-1 0.97刀/月 30r"))
        assertEquals("DMIT LAX Pro Wee", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 DMIT LAX Pro Wee 走咸鱼 300r"))
        assertEquals("SPARTAN 西雅图", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 斯巴达 西雅图，可换 DMIT LAX Pro Wee"))
        // Common typos, romanizations, emojis, and expanded VPS vendors
        assertEquals("DMIT lax pro wee", TradeMarketPremiumAnalyzer.extractSuggestedQuery("🔥出 dimt lax.pro.wee 💰280r"))
        assertEquals("BWG MEGABOX PRO", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 banwagong megabox pro 360r"))
        assertEquals("ORACLE 首尔", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 OCI 首尔 升级号 380r"))
        assertEquals("SCALEWAY 星尘 法国", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 Scaleway 星尘 法国 30r"))
        assertEquals("AWS Lightsail 日本", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 Lightsail 日本 5刀/月 35r"))
        assertEquals("DMIT LAX Pro Wee", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 d家 LAX Pro Wee 280r"))
        assertEquals("GREENCLOUD 东京 软银 2222", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 绿帽云 东京 软银 2222 390r"))
        assertEquals("KUXUEYUN HK 9929", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 酷雪云 HK 9929 120r"))
        assertEquals("YECAOYUN 香港", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 野草云 香港 88r"))
        assertEquals(
            "DMIT LAX Pro WEE",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery(
                title = "出个大妈鸡",
                fallbackText = "DMIT LAX.Pro.WEE，续费 \$36.9/yr，也可换 搬瓦工 MEGABOX-PRO",
            ),
        )
    }

    @Test
    fun `extractSuggestedQuery and matchesQuery handle bounded fuzzy typos, G9_5 near spreads, title swap clauses, and bare hostnames`() {
        // 1. Bounded Damerau-Levenshtein <= 1 fuzzy vendor & SKU matching (pure alphabetic only)
        assertEquals("NETCUP rs1000 g11", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 netcp rs1000 g11 120r"))
        assertEquals("BWG MEGABOX", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 bwh megbox 350r"))
        assertEquals("BWG BIGGERBOX pro", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 瓦工 bigerbox pro 310r"))
        assertEquals("RACKNERD 黑五 dc02", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 racknred 2024黑五 dc02 50r"))
        assertEquals("HETZNER ax41 芬兰", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 heztner ax41 芬兰 350r"))
        assertEquals("DMIT lax pro Malibu", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 dmit lax pro malbu 500r"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("出 netcp rs1000 g11 120r", "NETCUP RS1000 G11"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("出 bwh megbox 350r", "BWG MEGABOX PRO"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("出 d家 LAX Pro Wee 280r", "DMIT LAX Pro WEE"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("出 Lightsail 日本 5刀/月", "AWS Lightsail JP"))
        // Fuzzy must NEVER match common English words (router != zouter, alive != alice) or cross numeric SKUs
        assertEquals("", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 闲置二手 router 200r"))
        assertEquals("", TradeMarketPremiumAnalyzer.extractSuggestedQuery("keep alive script test"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("出 Netcup RS2000 G11 180r", "NETCUP RS1000 G11"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("出 搬瓦工 DC9 CN2 GIA", "BWG DC6 CN2 GIA"))

        // 2. Decimal generation G9.5 must NOT be truncated when followed by spread phrases ending in 收/出
        assertEquals("NETCUP RS1000 G9.5", TradeMarketPremiumAnalyzer.extractSuggestedQuery("收 Netcup RS1000 G9.5 溢价80收"))
        assertEquals("NETCUP RS1000 G9.5", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 Netcup RS1000 G9.5 折价20出"))

        // 3. Swap & negation clauses inside titles stripped for BOTH extraction and matching
        assertEquals("DMIT LAX Pro Wee", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 DMIT LAX Pro Wee 290r（不换EB，谢绝大刀）"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("出 DMIT LAX Pro Wee 290r（不换EB，谢绝大刀）", "DMIT LAX Pro WEE"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("出 DMIT LAX Pro Wee 290r（不换EB，谢绝大刀）", "DMIT LAX EB WEE"))
        assertEquals("DMIT lax pro wee", TradeMarketPremiumAnalyzer.extractSuggestedQuery("300r出大妈lax pro wee，或者换瓦工megabox"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("300r出大妈lax pro wee，或者换瓦工megabox", "DMIT LAX Pro WEE"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("300r出大妈lax pro wee，或者换瓦工megabox", "BWG MEGABOX PRO"))
        assertEquals("DMIT LAX Pro Wee", TradeMarketPremiumAnalyzer.extractSuggestedQuery("吃灰不要了 出 DMIT LAX Pro Wee 280r"))

        // 4. Bare hostnames in review/tutorial posts must NOT trigger false merchant extraction or matching
        assertEquals("", TradeMarketPremiumAnalyzer.extractSuggestedQuery("分享一个 cloudcone.cc 测评脚本"))
        assertEquals("", TradeMarketPremiumAnalyzer.extractSuggestedQuery("分享 netcup.de 测评脚本"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("分享一个 cloudcone.cc 测评脚本", "CLOUDCONE"))
        // Real VPS trade mentioning vendor domain still extracts cleanly from the actual merchant token
        assertEquals("DMIT LAX Pro Wee", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 DMIT LAX Pro Wee 官网 dmit.io 300r"))
    }

    @Test
    fun `extractSuggestedQuery rejects non-VPS items, multi-vendor bundles, and English false positives`() {
        assertEquals("", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 4字母 .com 域名 明盘 1800r"))
        assertEquals("", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 Google Voice 老号 45r 带原邮"))
        assertEquals("", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 ChatGPT Plus 成品号 120r"))
        assertEquals("", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 Claude Pro 独享号 130元"))
        assertEquals("", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 闲置二手路由器 200r 包邮"))
        assertEquals("", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出了，封贴"))
        assertEquals("", TradeMarketPremiumAnalyzer.extractSuggestedQuery("分享网站 test.cc 教程"))
        assertEquals("", TradeMarketPremiumAnalyzer.extractSuggestedQuery("berlin return 节点 溢价 20"))
        assertEquals("", TradeMarketPremiumAnalyzer.extractSuggestedQuery("how to do this tutorial"))
        assertEquals("", TradeMarketPremiumAnalyzer.extractSuggestedQuery("打包出 DMIT LAX Pro Wee + 搬瓦工 MEGABOX-PRO 共 650r"))
        assertEquals("", TradeMarketPremiumAnalyzer.extractSuggestedQuery("合出 绿云 东京 2222 + 斯巴达 西雅图 16刀 打包 580r"))
        assertEquals(
            "",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery(
                title = "清库存 出三台小鸡 详情进来看",
                fallbackText = "1. Claw HK 4刀 70r\n2. Akile JP Lite 35r\n3. RN DC02 40r",
            ),
        )
        // Single-model multi-quantity bundle still extracts the shared model
        assertEquals(
            "RACKNERD 黑五 DC02",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery("出两台 RN 2024黑五 DC02 打包 95r"),
        )
    }

    @Test
    fun `extractSuggestedQuery enriches partial titles from markdown tables, labeled metadata, and strict superset bodies`() {
        // Markdown table in OP body
        assertEquals(
            "DMIT LAX Pro Malibu",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery(
                title = "出个大妈鸡 详情见表格",
                fallbackText = "| 套餐 / 机型 | DMIT LAX.Pro.Malibu | 续费 \$49.9/yr |\n| 到期日期 | 2027-05-01 | 原邮交付 |",
            ),
        )
        // Two-line merchant + plan labeled metadata
        assertEquals(
            "BWG MEGABOX PRO",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery(
                title = "出吃灰传家宝",
                fallbackText = "商家：BandwagonHost\n套餐：MEGABOX-PRO\n续费：\$45.68/年",
            ),
        )
        // Merchant-only title enriched by unlabeled leading body line
        assertEquals(
            "SPARTAN 西雅图 4837",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery(
                title = "[出] 斯巴达 9.9刀/年 120r",
                fallbackText = "西雅图 4837 1C512M，2026-12-20到期，带原油。",
            ),
        )
        // Partial title enriched when body restates a strict semantic superset
        assertEquals(
            "DMIT LAX Pro WEE",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery(
                title = "出大妈wee 290",
                fallbackText = "DMIT LAX.Pro.WEE，续费 36.9美元/年，2027-05-01到期。",
            ),
        )
        // Ancillary route mentions in body do NOT pollute complete/non-superset titles
        assertEquals(
            "SPARTAN 西雅图",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery(
                title = "出 斯巴达 西雅图 16刀/年 260r",
                fallbackText = "1C1G 4837，2027-01-25到期，带原邮出。",
            ),
        )
        assertEquals(
            "ZGO LAX Pro",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery(
                title = "出 ZGO LAX Pro",
                fallbackText = "9929+CMIN2，年付 \$29.9/yr，2027-03-10到期。",
            ),
        )
    }

    @Test
    fun `matchesQuery handles fullwidth lowercase, glued compounds, strict tier isolation, and bodySnippet safely`() {
        // Fullwidth lowercase & glued compounds
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("【出】ＤＭＩＴ　ＬＡＸ．Ｐｒｏ．ＷＥＥ　溢价＋６０", "DMIT LAX Pro WEE"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("出搬瓦工dc6cn2gia 46刀/年 320r", "BWG DC6 CN2 GIA"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("出绿云东京softbank2222 390r 不刀", "GREENCLOUD Tokyo Softbank 2222"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("出 绿云 坡县 2525 三年付 310r", "GREENCLOUD SG 2525"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("出大妈prowee 290r", "DMIT Pro WEE"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("出 Netcup RS1000G9.5 180r", "NETCUP RS1000 G9.5"))

        // Strict tier isolation: BWG DC6 != MEGABOX-PRO DC6, V.PS Mini != Mini Pro
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("【出】搬瓦工 BWG MEGABOX-PRO DC6 CN2 GIA-E", "BWG DC6"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("【出】搬瓦工 BWG MEGABOX-PRO DC6 CN2 GIA-E", "BWG DC6 CN2 GIA"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQuery("出 V.PS Tokyo Softbank Mini Pro 450r", "V.PS Tokyo Softbank Mini"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("出 V.PS Tokyo Softbank Mini 260r", "V.PS Tokyo Softbank Mini"))

        // bodySnippet matching enriches vague/partial titles without overriding conflicting title merchant/line
        assertTrue(
            TradeMarketPremiumAnalyzer.matchesQuery(
                title = "出大妈wee 290",
                query = "DMIT LAX Pro WEE",
                bodySnippet = "DMIT LAX.Pro.WEE，续费 36.9美元/年",
            ),
        )
        assertFalse(
            TradeMarketPremiumAnalyzer.matchesQuery(
                title = "出 瓦工 MEGABOX 380r",
                query = "DMIT LAX Pro WEE",
                bodySnippet = "也可换 DMIT LAX.Pro.WEE",
            ),
        )
        assertFalse(
            TradeMarketPremiumAnalyzer.matchesQuery(
                title = "出 DMIT LAX EB WEE 220r",
                query = "DMIT LAX Pro WEE",
                bodySnippet = "比 LAX.Pro.WEE 便宜，非 Pro 版本",
            ),
        )
        // Conflicting location / DC / route in title must NOT be overridden by bodySnippet
        assertFalse(
            TradeMarketPremiumAnalyzer.matchesQuery(
                title = "出 DMIT HKG Pro WEE 300",
                query = "DMIT LAX Pro WEE",
                bodySnippet = "刚买了美西机所以出这台香港的，原邮",
            ),
        )
        assertFalse(
            TradeMarketPremiumAnalyzer.matchesQuery(
                title = "出 Claw SG 4刀/月",
                query = "CLAW HK",
                bodySnippet = "已有香港鸡，出这台新加坡的",
            ),
        )
        assertFalse(
            TradeMarketPremiumAnalyzer.matchesQuery(
                title = "出 搬瓦工 DC9 CN2 GIA",
                query = "BWG DC6 CN2 GIA",
                bodySnippet = "换了DC6所以出这台DC9",
            ),
        )
        assertFalse(
            TradeMarketPremiumAnalyzer.matchesQuery(
                title = "出 绿云 大阪 2222",
                query = "GREENCLOUD Tokyo 2222",
                bodySnippet = "手头已有东京2222，出这台大阪的",
            ),
        )
        // Broad-region title (JP / US) remains compatible with specific-city body enrichment
        assertTrue(
            TradeMarketPremiumAnalyzer.matchesQuery(
                title = "出 绿云 日本 2222",
                query = "GREENCLOUD Tokyo 2222",
                bodySnippet = "东京 IIJ 2222 三年付",
            ),
        )
    }

    @Test
    fun `analyze wires TradeListingPriceParser for derived spreads, ask-price fallback, excludeTopicId, and unparsed tracking`() {
        val now = LocalDate.of(2026, 11, 28)
        val fx: (String) -> BigDecimal? = { code ->
            when (code.uppercase()) {
                "USD" -> BigDecimal("7.20")
                "CNY" -> BigDecimal.ONE
                else -> null
            }
        }
        val candidates = listOf(
            // Current topic itself — must be excluded via excludeTopicId
            TradeCandidateTopic(100L, "[出] DMIT LAX.Pro.WEE 溢价 +999", MarketDataSource.FEED_CACHE),
            // Explicit spread
            TradeCandidateTopic(101L, "[出] DMIT LAX.Pro.WEE 溢价 +80", MarketDataSource.FEED_CACHE),
            // Concise post with body renewal + expiry -> derived spread (ask 300 - residual)
            TradeCandidateTopic(
                topicId = 102L,
                title = "出 DMIT LAX Pro Wee 300",
                source = MarketDataSource.REMOTE_SEARCH,
                bodySnippet = "续费 \$36.9/年，2027-05-27到期，带原油出。",
            ),
            // Duplicate of 102 from higher-priority source without body -> must preserve bodySnippet from 102!
            TradeCandidateTopic(
                topicId = 102L,
                title = "出 DMIT LAX Pro Wee 300",
                source = MarketDataSource.FEED_CACHE,
                bodySnippet = null,
            ),
            // Ask-price-only concise post (no renewal/expiry -> ask fallback only)
            TradeCandidateTopic(103L, "出 DMIT LAX Pro Wee 明盘260r", MarketDataSource.REMOTE_SEARCH),
            // Matched but completely unpriced SELL post
            TradeCandidateTopic(104L, "出 DMIT LAX Pro Wee 带原油私聊", MarketDataSource.REMOTE_SEARCH),
            // Matched unpriced BUY post — must be excluded from matchedUnparsedTopics when includeBuyRequests=false
            TradeCandidateTopic(105L, "[收] DMIT LAX Pro Wee 带原油", MarketDataSource.REMOTE_SEARCH),
        )

        val dist = TradeMarketPremiumAnalyzer.analyze(
            query = "DMIT LAX Pro WEE",
            candidates = candidates,
            includeBuyRequests = false,
            fxToCny = fx,
            now = now,
            excludeTopicId = 100L,
        )

        assertEquals(5, dist.matchedTopicCount) // 101, 102, 103, 104, 105 (100 excluded!)
        assertEquals(2, dist.sampleCount) // 101 (explicit +80) + 102 (derived)
        assertEquals(1, dist.derivedSampleCount)
        assertEquals(2, dist.askSampleCount) // 102 (300) + 103 (260)
        assertEquals(BigDecimal("260.00"), dist.askMinCny)
        assertEquals(BigDecimal("280.00"), dist.askMedianCny)
        assertEquals(BigDecimal("300.00"), dist.askMaxCny)
        assertEquals(1, dist.askOnlyObservations.size)
        assertEquals(103L, dist.askOnlyObservations.single().topicId)
        assertEquals(3, dist.pricedTopicCount) // 101, 102, 103
        assertEquals(1, dist.matchedButUnparsedCount)
        assertEquals(listOf(104L), dist.matchedUnparsedTopics.map { it.topicId })

        val withBuy = TradeMarketPremiumAnalyzer.analyze(
            query = "DMIT LAX Pro WEE",
            candidates = candidates,
            includeBuyRequests = true,
            fxToCny = fx,
            now = now,
            excludeTopicId = 100L,
        )
        assertEquals(2, withBuy.matchedButUnparsedCount)
        assertEquals(listOf(105L, 104L), withBuy.matchedUnparsedTopics.map { it.topicId })

        val derivedObs = dist.observations.first { it.topicId == 102L }
        assertTrue(derivedObs.isDerived)
        assertEquals(BigDecimal("300.00"), derivedObs.askPriceCny)
        assertNotNull(derivedObs.residualCny)
        assertTrue(derivedObs.matchedExpression.startsWith("推算:"))
    }

    @Test
    fun `matchesQueryLoosely accepts ultra-short concise peer titles while rejecting conflicting tiers, locations, DCs, and generations`() {
        // Ultra-short DMIT peers (missing LAX / Pro) accepted for body-fetch candidate selection
        assertTrue(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出大妈wee", "DMIT LAX Pro WEE"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQueryLoosely("大妈 wee 300", "DMIT LAX Pro WEE"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出 DMIT Pro Wee 280r", "DMIT LAX Pro WEE"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQueryLoosely("大妈 Malibu 550r 带原邮", "DMIT LAX Pro Malibu"))

        // Conflicting DMIT line / tier / location MUST be rejected
        assertFalse(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出 DMIT LAX EB Wee 220r", "DMIT LAX Pro WEE"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出 DMIT HKG Pro Wee 300", "DMIT LAX Pro WEE"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出 DMIT LAX Pro Tiny 320r", "DMIT LAX Pro WEE"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出 DMIT LAX Pro Malibu 550r", "DMIT LAX Pro WEE"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出个大妈鸡 300r", "DMIT LAX Pro WEE"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出 瓦工 MEGABOX 380r", "DMIT LAX Pro WEE"))

        // GreenCloud / Netcup / BWG / Spartan / RackNerd / OVH / YxVM concise peers + conflict rejection
        assertTrue(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出个绿云 2222 带原油", "GREENCLOUD Tokyo Softbank 2222"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出绿云 日本 2222 390r", "GREENCLOUD Tokyo Softbank 2222"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出绿云 大阪 2222 330r", "GREENCLOUD Tokyo Softbank 2222"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出绿云 东京 IIJ 2222 360r", "GREENCLOUD Tokyo Softbank 2222"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出绿云 新加坡 2525 320r", "GREENCLOUD Tokyo Softbank 2222"))

        assertTrue(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出 Netcup RS1000 180r", "NETCUP RS1000 G9.5"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出 Netcup RS1000 G9 180r", "NETCUP RS1000 G9.5"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出 Netcup RS1000 G11 120r", "NETCUP RS1000 G9.5"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出 Netcup RS2000 G9.5 220r", "NETCUP RS1000 G9.5"))

        assertTrue(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出 搬瓦工 DC6 330r", "BWG DC6 CN2 GIA"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出 搬瓦工 DC9 CN2 GIA 380r", "BWG DC6 CN2 GIA"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出 搬瓦工 MEGABOX-PRO DC6 380r", "BWG DC6 CN2 GIA"))

        assertTrue(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出 斯巴达 西雅图 16刀/年 260r", "SPARTAN 西雅图 4837"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出 斯巴达 达拉斯 12刀/年 95r", "SPARTAN 西雅图 4837"))

        assertTrue(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出 OVH KS-LE-B 420r", "OVH KS LE B 法国"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出 OVH KS-LE-C 法国 420r", "OVH KS LE B 法国"))
        assertFalse(TradeMarketPremiumAnalyzer.matchesQueryLoosely("出 YxVM HK Eco 45r", "YXVM HK Vol"))
    }

    @Test
    fun `extractSuggestedQuery, matchesQuery, and extractSpread handle Traditional Chinese, broad-region body refinement, bullet labels, RFC JINX, and doubled-letter typos`() {
        // 1. Traditional -> Simplified Chinese folding (exposed publicly & used across extraction, matching, spread)
        assertEquals("大妈 洛杉矶 溢价+65 绿云 东京 软银", TradeMarketPremiumAnalyzer.foldTraditionalToSimplified("大媽 洛杉磯 溢價+65 綠雲 東京 軟銀"))
        assertEquals("DMIT 洛杉矶 Pro Wee", TradeMarketPremiumAnalyzer.extractSuggestedQuery("【出】大媽 洛杉磯 Pro Wee 溢價+65"))
        assertEquals("GREENCLOUD 东京 软银 2222", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 綠雲 東京 軟銀 2222 三年付 380元"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("【出】大媽 洛杉磯 Pro Wee 溢價+65", "DMIT LAX Pro WEE"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("出 綠雲 東京 軟銀 2222 三年付 380元", "GREENCLOUD Tokyo Softbank 2222"))
        assertEquals(BigDecimal("65.00"), TradeMarketPremiumAnalyzer.extractSpread("【出】大媽 洛杉磯 Pro Wee 溢價+65")?.deltaCny)

        // 2. Broad-region title refined by specific city/SKU in body
        assertEquals(
            "GREENCLOUD 东京 软银 2222",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery(
                title = "出个绿云日本",
                fallbackText = "东京 软银 2222 三年付 \$66/3年，2027-11-11到期，明盘 380r。",
            ),
        )
        assertEquals(
            "SALTYFISH SJC Pro",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery(
                title = "出个咸鱼美西",
                fallbackText = "SJC Pro 季付 \$19.9/季，2027-02-01到期，明盘 130r。",
            ),
        )

        // 3. Markdown bullet-list & numbered 2-line labeled metadata in body
        assertEquals(
            "DMIT LAX Pro WEE",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery(
                title = "出个闲置小鸡",
                fallbackText = "- 商家：DMIT\n- 套餐：LAX.Pro.WEE\n- 续费：\$36.9/年",
            ),
        )
        assertEquals(
            "BWG MEGABOX PRO",
            TradeMarketPremiumAnalyzer.extractSuggestedQuery(
                title = "出吃灰机",
                fallbackText = "1. 商家：BandwagonHost\n2. 套餐：MEGABOX-PRO",
            ),
        )

        // 4. RFC HK JINX must NOT collide as multi-vendor bundle; JINX is an RFC product line
        assertEquals("RFC HK JINX", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 RFC HK JINX 29.9刀/季 310r"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("出 RFC HK JINX 29.9刀/季 310r", "RFC HK JINX"))

        // 5. 5-char doubled-letter typo of 4-char vendor alias (e.g. dmitt -> dmit)
        assertEquals("DMIT lax pro wee", TradeMarketPremiumAnalyzer.extractSuggestedQuery("出 dmitt lax pro wee 280r"))
        assertTrue(TradeMarketPremiumAnalyzer.matchesQuery("出 dmitt lax pro wee 280r", "DMIT LAX Pro WEE"))
    }

    @Test
    fun `analyze computes bidirectional total ask and spread quantiles with SKU renewal consensus`() {
        val now = LocalDate.of(2026, 11, 28)
        // Use clean USD=7.30 so 36.50 USD/yr = 266.45 CNY/yr; 182.5d? Let's use 365 days -> 36.50 * 7.30 = 266.45,
        // or $50.00/yr at FX=7.30 = 365.00 CNY/yr = 1.00 CNY/day for exact mental math!
        val fx: (String) -> BigDecimal? = { code ->
            when (code.uppercase()) {
                "USD" -> BigDecimal("7.30")
                "CNY" -> BigDecimal.ONE
                else -> null
            }
        }
        // $50/yr @ 7.30 = 365.00 CNY/yr (1.00 CNY/day). Expiry 2027-06-16 = 200 days remaining -> residual = 200.00 CNY!
        val expiry200d = now.plusDays(200) // 2027-06-16
        val expiry180d = now.plusDays(180) // 2027-05-27

        // (a) Explicit spread + renewal/expiry -> derives askPriceCny = residual(200) + 80 = 280.00 (isAskDerived = true)
        // (b) Explicit total ask + renewal/expiry -> derives spread = 300 - residual(200) = +100.00 (isDerived = true)
        // (c) Concise peer omitting renewal (only expiry + total ask 240, 180d left) -> inherits consensus $50/yr ->
        //     residual = 180.00, derived spread = +60.00!
        // (d) Concise peer omitting renewal (only expiry + explicit spread 溢价+40, 180d left) -> inherits consensus $50/yr ->
        //     residual = 180.00, derived ask = 220.00 (isAskDerived = true)!
        val candidates = listOf(
            TradeCandidateTopic(
                topicId = 201L,
                title = "[出] DMIT LAX.Pro.WEE 溢价+80出",
                source = MarketDataSource.FEED_CACHE,
                bodySnippet = "续费 \$50/年，${expiry200d}到期，带原邮。",
            ),
            TradeCandidateTopic(
                topicId = 202L,
                title = "出 DMIT LAX Pro Wee 300出",
                source = MarketDataSource.REMOTE_SEARCH,
                bodySnippet = "年付 \$50/yr，${expiry200d}到期，原邮出。",
            ),
            TradeCandidateTopic(
                topicId = 203L,
                title = "出 DMIT LAX Pro Wee 明盘240r",
                source = MarketDataSource.REMOTE_SEARCH,
                bodySnippet = "${expiry180d}到期，带原邮。",
            ),
            TradeCandidateTopic(
                topicId = 204L,
                title = "出 DMIT LAX Pro Wee 溢价+40",
                source = MarketDataSource.REMOTE_SEARCH,
                bodySnippet = "${expiry180d}到期，原邮给。",
            ),
        )

        val dist = TradeMarketPremiumAnalyzer.analyze(
            query = "DMIT LAX Pro WEE",
            candidates = candidates,
            includeBuyRequests = false,
            fxToCny = fx,
            now = now,
        )

        assertEquals(4, dist.matchedTopicCount)
        assertEquals(4, dist.sampleCount)
        assertEquals(2, dist.derivedSampleCount) // 202 (+100) and 203 (+60)
        assertEquals(4, dist.askSampleCount) // 201 (280 derived), 202 (300), 203 (240), 204 (220 derived)
        assertEquals(2, dist.derivedAskSampleCount) // 201 and 204

        // Spreads sorted: [40, 60, 80, 100] -> median = 70.00, p25 = 55.00, p75 = 85.00
        assertEquals(BigDecimal("40.00"), dist.minCny)
        assertEquals(BigDecimal("55.00"), dist.p25Cny)
        assertEquals(BigDecimal("70.00"), dist.medianCny)
        assertEquals(BigDecimal("85.00"), dist.p75Cny)
        assertEquals(BigDecimal("100.00"), dist.maxCny)

        // Total asks sorted: [220, 240, 280, 300] -> median = 260.00, p25 = 235.00, p75 = 285.00
        assertEquals(BigDecimal("220.00"), dist.askMinCny)
        assertEquals(BigDecimal("235.00"), dist.askP25Cny)
        assertEquals(BigDecimal("260.00"), dist.askMedianCny)
        assertEquals(BigDecimal("285.00"), dist.askP75Cny)
        assertEquals(BigDecimal("300.00"), dist.askMaxCny)

        val obsById = dist.observations.associateBy { it.topicId }
        val o201 = obsById.getValue(201L)
        assertFalse(o201.isDerived)
        assertTrue(o201.isAskDerived)
        assertEquals(BigDecimal("200.00"), o201.residualCny)
        assertEquals(BigDecimal("280.00"), o201.askPriceCny)
        assertEquals(BigDecimal("80.00"), o201.premiumDeltaCny)

        val o202 = obsById.getValue(202L)
        assertTrue(o202.isDerived)
        assertFalse(o202.isAskDerived)
        assertEquals(BigDecimal("200.00"), o202.residualCny)
        assertEquals(BigDecimal("300.00"), o202.askPriceCny)
        assertEquals(BigDecimal("100.00"), o202.premiumDeltaCny)

        val o203 = obsById.getValue(203L)
        assertTrue(o203.isDerived)
        assertEquals(BigDecimal("180.00"), o203.residualCny)
        assertEquals(BigDecimal("60.00"), o203.premiumDeltaCny)

        val o204 = obsById.getValue(204L)
        assertFalse(o204.isDerived)
        assertTrue(o204.isAskDerived)
        assertEquals(BigDecimal("180.00"), o204.residualCny)
        assertEquals(BigDecimal("220.00"), o204.askPriceCny)

        val askById = dist.askObservations.associateBy { it.topicId }
        assertTrue(askById.getValue(201L).isAskDerived)
        assertFalse(askById.getValue(202L).isAskDerived)
        assertTrue(askById.getValue(201L).matchedExpression.contains("推算总价"))

        // Bare-merchant or conflicting-plan pool must NOT cross-pollinate SKU renewal consensus!
        val bareMerchantDist = TradeMarketPremiumAnalyzer.analyze(
            query = "DMIT",
            candidates = listOf(
                TradeCandidateTopic(301L, "出 DMIT LAX.Pro.WEE \$50/yr ${expiry200d}到期 280r", MarketDataSource.FEED_CACHE),
                TradeCandidateTopic(302L, "出 DMIT LAX.Pro.WEE \$50/yr ${expiry200d}到期 290r", MarketDataSource.FEED_CACHE),
                TradeCandidateTopic(303L, "出 DMIT TYO.Pro.Tiny ${expiry180d}到期 680r", MarketDataSource.FEED_CACHE),
            ),
            fxToCny = fx,
            now = now,
        )
        assertEquals(2, bareMerchantDist.sampleCount) // 303 must NOT inherit $50/yr on bare "DMIT" query!
        assertEquals(3, bareMerchantDist.askSampleCount)
        assertEquals(1, bareMerchantDist.askOnlyObservations.size)
        assertEquals(303L, bareMerchantDist.askOnlyObservations.single().topicId)

        // Conflicting renewals on a concrete query also refuse consensus (no cross-plan hallucination)
        val conflictingDist = TradeMarketPremiumAnalyzer.analyze(
            query = "SPARTAN 西雅图 4837",
            candidates = listOf(
                TradeCandidateTopic(401L, "出 斯巴达 西雅图 4837 \$16/yr ${expiry200d}到期 240r", MarketDataSource.FEED_CACHE),
                TradeCandidateTopic(402L, "出 斯巴达 西雅图 4837 \$24/yr ${expiry200d}到期 360r", MarketDataSource.FEED_CACHE),
                TradeCandidateTopic(403L, "出 斯巴达 西雅图 4837 ${expiry180d}到期 300r", MarketDataSource.FEED_CACHE),
            ),
            fxToCny = fx,
            now = now,
        )
        assertEquals(2, conflictingDist.sampleCount) // 403 must stay ask-only because $16 vs $24 disagree!
        assertEquals(1, conflictingDist.askOnlyObservations.size)
        assertEquals(403L, conflictingDist.askOnlyObservations.single().topicId)

        // Explicit caller referenceRenewal (from current topic) works when peers don't contradict it
        val withCallerRef = TradeMarketPremiumAnalyzer.analyze(
            query = "DMIT LAX Pro WEE",
            candidates = listOf(
                TradeCandidateTopic(501L, "出 DMIT LAX Pro Wee 明盘260r", MarketDataSource.REMOTE_SEARCH, bodySnippet = "${expiry200d}到期"),
            ),
            fxToCny = fx,
            now = now,
            referenceRenewal = ParsedRenewalInfo(
                amount = BigDecimal("50.00"),
                currency = ValuationCurrency.USD,
                cycle = BillingCyclePreset.ANNUAL,
                matchedExpression = "\$50/yr",
                cycleExplicit = true,
            ),
        )
        assertEquals(1, withCallerRef.sampleCount)
        assertEquals(BigDecimal("60.00"), withCallerRef.medianCny)
    }
}
