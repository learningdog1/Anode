package com.nodeseek.app.domain.filter

import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.radar.KeywordMatchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentFilterEngineTest {

    private fun sampleTopic(
        id: Long = 1L,
        title: String = "普通技术讨论贴",
        categorySlug: String = "tech",
        categoryName: String = "技术",
    ) = TopicSummary(
        id = id,
        title = title,
        categorySlug = categorySlug,
        categoryName = categoryName,
        authorName = "tester",
    )

    @Test
    fun `disabled master switch or includeBlockedTopics never hides any topic`() {
        val config = ContentFilterConfig(
            isEnabled = true,
            mutedCategorySlugs = setOf("promotion"),
            rules = listOf(
                ContentBlockRule(
                    id = "r1",
                    pattern = "AFF",
                    matchMode = KeywordMatchMode.CONTAINS,
                ),
            ),
        )
        val matchingTopic = sampleTopic(
            title = "限时优惠 AFF 返利",
            categorySlug = "promotion",
            categoryName = "推广",
        )

        assertTrue(
            ContentFilterEngine.shouldHideTopic(
                topic = matchingTopic,
                config = config,
                includeBlockedTopics = false,
                activeBoardSlug = "",
            ),
        )
        assertFalse(
            ContentFilterEngine.shouldHideTopic(
                topic = matchingTopic,
                config = config.copy(isEnabled = false),
                includeBlockedTopics = false,
                activeBoardSlug = "",
            ),
        )
        assertFalse(
            ContentFilterEngine.shouldHideTopic(
                topic = matchingTopic,
                config = config,
                includeBlockedTopics = true,
                activeBoardSlug = "",
            ),
        )
    }

    @Test
    fun `keyword rules support case-insensitive matching, enable toggle, and category scoping`() {
        val globalRule = ContentBlockRule(
            id = "r_global",
            pattern = "求购",
            matchMode = KeywordMatchMode.CONTAINS,
            categorySlug = "",
            isEnabled = true,
        )
        val tradeOnlyRule = ContentBlockRule(
            id = "r_trade",
            pattern = "dmit",
            matchMode = KeywordMatchMode.CONTAINS,
            categorySlug = "TRADE",
            isEnabled = true,
        )
        val nameScopedRule = ContentBlockRule(
            id = "r_name",
            pattern = "水贴",
            matchMode = KeywordMatchMode.CONTAINS,
            categorySlug = "日常",
            isEnabled = true,
        )
        val disabledRule = ContentBlockRule(
            id = "r_off",
            pattern = "linux",
            matchMode = KeywordMatchMode.CONTAINS,
            isEnabled = false,
        )

        val config = ContentFilterConfig(
            isEnabled = true,
            rules = listOf(globalRule, tradeOnlyRule, nameScopedRule, disabledRule),
        )

        // Matches global keyword rule regardless of board
        assertTrue(
            ContentFilterEngine.shouldHideTopic(
                sampleTopic(title = "[求购] 甲骨文账号", categorySlug = "trade", categoryName = "交易"),
                config,
                includeBlockedTopics = false,
            ),
        )
        // Matches trade-only rule case-insensitively on categorySlug & pattern
        assertTrue(
            ContentFilterEngine.shouldHideTopic(
                sampleTopic(title = "出 DMIT Pro", categorySlug = "trade", categoryName = "交易"),
                config,
                includeBlockedTopics = false,
            ),
        )
        // Does NOT hide DMIT inside tech board because rule is scoped to trade
        assertFalse(
            ContentFilterEngine.shouldHideTopic(
                sampleTopic(title = "DMIT 三网回程测评", categorySlug = "tech", categoryName = "技术"),
                config,
                includeBlockedTopics = false,
            ),
        )
        // Matches categoryName-scoped rule
        assertTrue(
            ContentFilterEngine.shouldHideTopic(
                sampleTopic(title = "今日水贴打卡", categorySlug = "daily", categoryName = "日常"),
                config,
                includeBlockedTopics = false,
            ),
        )
        // Disabled rule does not hide
        assertFalse(
            ContentFilterEngine.shouldHideTopic(
                sampleTopic(title = "Linux 内核调优", categorySlug = "tech", categoryName = "技术"),
                config,
                includeBlockedTopics = false,
            ),
        )
    }

    @Test
    fun `regex rules match safely and validateRegex catches malformed patterns without throwing`() {
        assertNull(ContentFilterEngine.validateRegex("""(?i)(\baff\b|返利)"""))
        assertNotNull(ContentFilterEngine.validateRegex(""))
        assertNotNull(ContentFilterEngine.validateRegex("[unclosed("))
        assertNotNull(ContentFilterEngine.validateRulePattern("", KeywordMatchMode.CONTAINS))
        assertNull(ContentFilterEngine.validateRulePattern("求购", KeywordMatchMode.CONTAINS))

        val config = ContentFilterConfig(
            isEnabled = true,
            rules = listOf(
                ContentBlockRule(
                    id = "invalid",
                    pattern = "[unclosed(",
                    matchMode = KeywordMatchMode.REGEX,
                    isEnabled = true,
                ),
                ContentBlockRule(
                    id = "valid",
                    pattern = """\b(?:claw|dmit)\s*(?:hk|jp)\b""",
                    matchMode = KeywordMatchMode.REGEX,
                    isEnabled = true,
                ),
            ),
        )

        assertTrue(
            ContentFilterEngine.shouldHideTopic(
                sampleTopic(title = "出 Claw HK 年付"),
                config,
                includeBlockedTopics = false,
            ),
        )
        assertFalse(
            ContentFilterEngine.shouldHideTopic(
                sampleTopic(title = "BandwagonHost LAX"),
                config,
                includeBlockedTopics = false,
            ),
        )
        assertFalse(
            ContentFilterEngine.testRuleMatch(
                sampleTitle = "任意标题",
                pattern = "[unclosed(",
                matchMode = KeywordMatchMode.REGEX,
            ),
        )
    }

    @Test
    fun `muted categories hide in aggregated stream and global search but remain viewable when explicitly opened`() {
        val config = ContentFilterConfig(
            isEnabled = true,
            mutedCategorySlugs = setOf("Promotion", "meaningless"),
            rules = listOf(
                ContentBlockRule(
                    id = "r1",
                    pattern = "AFF",
                    matchMode = KeywordMatchMode.CONTAINS,
                ),
            ),
        )

        val normalPromo = sampleTopic(
            id = 1L,
            title = "新商家五折促销",
            categorySlug = "promotion",
            categoryName = "推广",
        )
        val keywordBlockedPromo = sampleTopic(
            id = 2L,
            title = "新商家五折促销带 AFF",
            categorySlug = "promotion",
            categoryName = "推广",
        )
        val techPost = sampleTopic(
            id = 3L,
            title = "Docker Compose 部署指南",
            categorySlug = "tech",
            categoryName = "技术",
        )

        // All-feed / global search (activeBoardSlug = "") hides both promotion posts
        val allFeedVisible = ContentFilterEngine.filterTopics(
            topics = listOf(normalPromo, keywordBlockedPromo, techPost),
            config = config,
            includeBlockedTopics = false,
            activeBoardSlug = "",
        )
        assertEquals(listOf(3L), allFeedVisible.map { it.id })

        // Explicitly entering "promotion" board shows non-keyword-blocked promo, still hides AFF post
        val promotionBoardVisible = ContentFilterEngine.filterTopics(
            topics = listOf(normalPromo, keywordBlockedPromo),
            config = config,
            includeBlockedTopics = false,
            activeBoardSlug = "promotion",
        )
        assertEquals(listOf(1L), promotionBoardVisible.map { it.id })
    }

    @Test
    fun `curated presets accurately match noisy buy sell aff carpool lottery posts without false positives`() {
        val presetById = ContentFilterEngine.CURATED_PRESETS.associateBy { it.id }

        val buyPreset = presetById.getValue("preset_buy_request")
        assertTrue(ContentFilterEngine.testRuleMatch("[求购] RackNerd 圣何塞闪购款", buyPreset.pattern, buyPreset.matchMode))
        assertTrue(ContentFilterEngine.testRuleMatch("收一台 RackNerd 年付小鸡", buyPreset.pattern, buyPreset.matchMode))
        assertTrue(ContentFilterEngine.testRuleMatch("【收】DMIT HK Pro", buyPreset.pattern, buyPreset.matchMode))
        assertTrue(ContentFilterEngine.testRuleMatch("高价收瓦工传家宝", buyPreset.pattern, buyPreset.matchMode))
        assertFalse(ContentFilterEngine.testRuleMatch("开源收集脚本合集", buyPreset.pattern, buyPreset.matchMode))
        assertFalse(ContentFilterEngine.testRuleMatch("自建图床收纳工具推荐", buyPreset.pattern, buyPreset.matchMode))

        val affPreset = presetById.getValue("preset_aff_promo")
        assertTrue(ContentFilterEngine.testRuleMatch("[推广] 促销附带 AFF", affPreset.pattern, affPreset.matchMode))
        assertTrue(ContentFilterEngine.testRuleMatch("CloudCone $10 年付 (aff)", affPreset.pattern, affPreset.matchMode))
        assertTrue(ContentFilterEngine.testRuleMatch("走返利链接直降 5 刀", affPreset.pattern, affPreset.matchMode))
        assertFalse(ContentFilterEngine.testRuleMatch("Traffic 流量监控面板", affPreset.pattern, affPreset.matchMode))

        val sellPreset = presetById.getValue("preset_sell_premium")
        assertTrue(ContentFilterEngine.testRuleMatch("[出] HKBN 家宽大带宽 NAT", sellPreset.pattern, sellPreset.matchMode))
        assertTrue(ContentFilterEngine.testRuleMatch("出 DMIT HK Pro 季付", sellPreset.pattern, sellPreset.matchMode))
        assertTrue(ContentFilterEngine.testRuleMatch("溢价50出瓦工", sellPreset.pattern, sellPreset.matchMode))
        assertFalse(ContentFilterEngine.testRuleMatch("服务器突然出现丢包", sellPreset.pattern, sellPreset.matchMode))

        val carpoolPreset = presetById.getValue("preset_carpool")
        assertTrue(ContentFilterEngine.testRuleMatch("Netflix 土区拼车缺 2 人", carpoolPreset.pattern, carpoolPreset.matchMode))
        assertFalse(ContentFilterEngine.testRuleMatch("自建 Emby 媒体库分享", carpoolPreset.pattern, carpoolPreset.matchMode))

        val lotteryPreset = presetById.getValue("preset_lottery")
        assertTrue(ContentFilterEngine.testRuleMatch("[抽奖] 抽一台小鸡庆祝周末", lotteryPreset.pattern, lotteryPreset.matchMode))
        assertFalse(ContentFilterEngine.testRuleMatch("本周运维复盘", lotteryPreset.pattern, lotteryPreset.matchMode))

        val configWithBuy = ContentFilterConfig(rules = listOf(buyPreset.toRule()))
        assertTrue(ContentFilterEngine.isPresetActive(buyPreset, configWithBuy))
        assertFalse(ContentFilterEngine.isPresetActive(affPreset, configWithBuy))
    }

    @Test
    fun `validateRegex rejects ReDoS nested quantifiers and overly long patterns while allowing all curated presets`() {
        // All curated presets remain valid under ReDoS guards
        for (preset in ContentFilterEngine.CURATED_PRESETS) {
            assertNull("Preset ${preset.id} must pass validateRegex", ContentFilterEngine.validateRegex(preset.pattern))
        }

        // Classic ReDoS patterns are rejected statically
        assertNotNull(ContentFilterEngine.validateRegex("(a+)+$"))
        assertNotNull(ContentFilterEngine.validateRegex("([a-zA-Z]+)*b"))
        assertNotNull(ContentFilterEngine.validateRegex("(.*a){20}"))
        assertNotNull(ContentFilterEngine.validateRegex("(a+){2,10}"))
        assertNotNull(ContentFilterEngine.validateRegex("a".repeat(257)))

        // Unsafe regex stored in config is safely skipped without backtracking hang
        val unsafeConfig = ContentFilterConfig(
            isEnabled = true,
            rules = listOf(
                ContentBlockRule(
                    id = "redos",
                    pattern = "(a+)+$",
                    matchMode = KeywordMatchMode.REGEX,
                    isEnabled = true,
                ),
            ),
        )
        assertFalse(
            ContentFilterEngine.shouldHideTopic(
                sampleTopic(title = "a".repeat(40) + "!"),
                unsafeConfig,
                includeBlockedTopics = false,
            ),
        )
    }
}
