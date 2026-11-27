package com.nodeseek.app.domain.valuation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

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
}
