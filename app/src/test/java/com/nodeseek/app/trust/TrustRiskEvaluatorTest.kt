package com.nodeseek.app.trust

import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.trust.ExposureCandidate
import com.nodeseek.app.domain.trust.ExposureHitTopic
import com.nodeseek.app.domain.trust.LocalSellerNote
import com.nodeseek.app.domain.trust.TrustRiskEvaluator
import com.nodeseek.app.domain.trust.TrustRiskLevel
import com.nodeseek.app.domain.trust.TrustTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TrustRiskEvaluatorTest {

    private val shanghaiZone: ZoneId = ZoneId.of("Asia/Shanghai")
    // Fixed reference time: 2026-11-19T12:00:00+08:00
    private val fixedNowMillis: Long = Instant.parse("2026-11-19T04:00:00Z").toEpochMilli()

    // ── Registration Date Parsing & Duration Labels ──

    @Test
    fun parseRegistrationTenure_iso8601Utc_computesYearsAndMonths() {
        val tenure = TrustRiskEvaluator.parseRegistrationTenure(
            rawJoinedAt = "2024-07-10T08:15:30Z",
            nowEpochMillis = fixedNowMillis,
            zoneId = shanghaiZone,
        )

        assertEquals("2024-07-10", tenure.createdAtText)
        assertNotNull(tenure.registrationDays)
        assertTrue(tenure.registrationDays!! > 800)
        assertEquals("2年 4个月", tenure.durationLabel)
        assertFalse(tenure.isNewAccount)
    }

    @Test
    fun parseRegistrationTenure_isoOffsetDateTime_newAccountUnder30Days() {
        val tenure = TrustRiskEvaluator.parseRegistrationTenure(
            rawJoinedAt = "2026-11-01T18:30:00+08:00",
            nowEpochMillis = fixedNowMillis,
            zoneId = shanghaiZone,
        )

        assertEquals("2026-11-01", tenure.createdAtText)
        assertEquals(18, tenure.registrationDays)
        assertEquals("18天 · 新号", tenure.durationLabel)
        assertTrue(tenure.isNewAccount)
    }

    @Test
    fun parseRegistrationTenure_plainDateAndSpaceDateTimeAndSlashAndChineseDate() {
        val plain = TrustRiskEvaluator.parseRegistrationTenure(
            rawJoinedAt = "2026-11-19",
            nowEpochMillis = fixedNowMillis,
            zoneId = shanghaiZone,
        )
        assertEquals(0, plain.registrationDays)
        assertEquals("今日注册 · 新号", plain.durationLabel)

        val spaceDateTime = TrustRiskEvaluator.parseRegistrationTenure(
            rawJoinedAt = "2026-08-05 14:22:09",
            nowEpochMillis = fixedNowMillis,
            zoneId = shanghaiZone,
        )
        assertEquals("2026-08-05", spaceDateTime.createdAtText)
        assertEquals("3个月 14天", spaceDateTime.durationLabel)

        val slashDate = TrustRiskEvaluator.parseRegistrationTenure(
            rawJoinedAt = "2025/11/19",
            nowEpochMillis = fixedNowMillis,
            zoneId = shanghaiZone,
        )
        assertEquals("2025-11-19", slashDate.createdAtText)
        assertEquals("1年", slashDate.durationLabel)

        val cnDate = TrustRiskEvaluator.parseRegistrationTenure(
            rawJoinedAt = "加入于 2024年07月19日",
            nowEpochMillis = fixedNowMillis,
            zoneId = shanghaiZone,
        )
        assertEquals("2024-07-19", cnDate.createdAtText)
        assertEquals("2年 4个月", cnDate.durationLabel)
    }

    @Test
    fun parseRegistrationTenure_relativeFormatsAndEpochAndBlank() {
        val relDays = TrustRiskEvaluator.parseRegistrationTenure(
            rawJoinedAt = "18天前",
            nowEpochMillis = fixedNowMillis,
            zoneId = shanghaiZone,
        )
        assertEquals(18, relDays.registrationDays)
        assertEquals("18天 · 新号", relDays.durationLabel)

        val relWeeks = TrustRiskEvaluator.parseRegistrationTenure(
            rawJoinedAt = "2周前",
            nowEpochMillis = fixedNowMillis,
            zoneId = shanghaiZone,
        )
        assertEquals(14, relWeeks.registrationDays)
        assertEquals("14天 · 新号", relWeeks.durationLabel)

        val relYesterday = TrustRiskEvaluator.parseRegistrationTenure(
            rawJoinedAt = "昨天",
            nowEpochMillis = fixedNowMillis,
            zoneId = shanghaiZone,
        )
        assertEquals(1, relYesterday.registrationDays)
        assertEquals("1天 · 新号", relYesterday.durationLabel)

        val relYears = TrustRiskEvaluator.parseRegistrationTenure(
            rawJoinedAt = "2年前",
            nowEpochMillis = fixedNowMillis,
            zoneId = shanghaiZone,
        )
        assertEquals(730, relYears.registrationDays)
        assertEquals("2年", relYears.durationLabel)

        val relEn = TrustRiskEvaluator.parseRegistrationTenure(
            rawJoinedAt = "12 days ago",
            nowEpochMillis = fixedNowMillis,
            zoneId = shanghaiZone,
        )
        assertEquals(12, relEn.registrationDays)
        assertTrue(relEn.isNewAccount)

        val epochSeconds = Instant.parse("2025-05-19T04:00:00Z").epochSecond.toString()
        val parsedEpoch = TrustRiskEvaluator.parseRegistrationTenure(
            rawJoinedAt = epochSeconds,
            nowEpochMillis = fixedNowMillis,
            zoneId = shanghaiZone,
        )
        assertEquals("2025-05-19", parsedEpoch.createdAtText)
        assertEquals("1年 6个月", parsedEpoch.durationLabel)

        val unknown = TrustRiskEvaluator.parseRegistrationTenure(
            rawJoinedAt = "   ",
            nowEpochMillis = fixedNowMillis,
            zoneId = shanghaiZone,
        )
        assertNull(unknown.registrationDays)
        assertEquals("注册时间未知", unknown.durationLabel)
    }

    // ── Trade Topic Detection ──

    @Test
    fun isTradeTopic_recognizesTradeCategoryAndTitleKeywordsWhileExcludingDevPush() {
        assertTrue(TrustRiskEvaluator.isTradeTopic(topic(id = 1, title = "DMIT Pro", categorySlug = "trade", categoryName = "交易")))
        assertTrue(TrustRiskEvaluator.isTradeTopic(topic(id = 2, title = "[出] 搬瓦工 CN2 GIA", categorySlug = "daily", categoryName = "日常")))
        assertTrue(TrustRiskEvaluator.isTradeTopic(topic(id = 3, title = "【收】一台日本软银", categorySlug = "daily", categoryName = "日常")))
        assertTrue(TrustRiskEvaluator.isTradeTopic(topic(id = 4, title = "剩余价值出美西 VPS", categorySlug = "info", categoryName = "情报")))
        assertTrue(TrustRiskEvaluator.isTradeTopic(topic(id = 5, title = "瓦工带原邮可 Push", categorySlug = "daily", categoryName = "日常")))

        assertFalse(TrustRiskEvaluator.isTradeTopic(topic(id = 6, title = "分享一个 git push 自动部署脚本", categorySlug = "dev", categoryName = "Dev")))
        assertFalse(TrustRiskEvaluator.isTradeTopic(topic(id = 7, title = "今天天气不错水一贴", categorySlug = "daily", categoryName = "日常")))
    }

    // ── Exposure Hit Matching, Noise Filtering & Deduplication ──

    @Test
    fun filterExposureHits_matchesExactAsciiTokenAndRejectsPartialSubstringAndUnrelatedNoise() {
        val candidates = listOf(
            // Genuine hits for "bob"
            ExposureCandidate(
                topic = topic(id = 101, title = "曝光 @Bob 收款不给机直接拉黑", categorySlug = "expose", categoryName = "曝光", authorId = 900, authorName = "victimA"),
                queryTermHint = "曝光",
            ),
            ExposureCandidate(
                topic = topic(id = 102, title = "骗子bob跑路了大家注意避雷", categorySlug = "daily", categoryName = "日常", authorId = 901, authorName = "victimB"),
                queryTermHint = "骗子",
            ),
            // Duplicate of topic 101 coming from second search query with higher-severity term in snippet
            ExposureCandidate(
                topic = topic(id = 101, title = "曝光 @Bob 收款不给机直接拉黑", categorySlug = "expose", categoryName = "曝光", authorId = 900, authorName = "victimA"),
                snippet = "实锤骗子 bob 卷款跑路",
                queryTermHint = "骗子",
            ),
            // False positive: "bobby" contains "bob" as substring, NOT as token
            ExposureCandidate(
                topic = topic(id = 103, title = "曝光 bobby 骗子跑路", categorySlug = "expose", categoryName = "曝光", authorId = 902, authorName = "victimC"),
                queryTermHint = "曝光",
            ),
            // False positive: "super_bob" and "bob123"
            ExposureCandidate(
                topic = topic(id = 104, title = "挂人 super_bob 和 bob123 骗子", categorySlug = "expose", categoryName = "曝光", authorId = 903, authorName = "victimD"),
                queryTermHint = "骗子",
            ),
            // False positive: unrelated trade thread containing "bob" with no exposure/scam signal
            ExposureCandidate(
                topic = topic(id = 105, title = "[出] 找 bob 收的一台新加坡小鸡", categorySlug = "trade", categoryName = "交易", authorId = 904, authorName = "buyerE"),
                queryTermHint = "曝光",
            ),
            // False positive: authored by bob himself warning about another merchant
            ExposureCandidate(
                topic = topic(id = 106, title = "bob 实名曝光某垃圾商家跑路", categorySlug = "expose", categoryName = "曝光", authorId = 555, authorName = "bob"),
                queryTermHint = "曝光",
            ),
        )

        val hits = TrustRiskEvaluator.filterExposureHits(
            candidates = candidates,
            targetUid = 555L,
            targetUsername = "bob",
        )

        assertEquals(2, hits.size)
        assertEquals(setOf(101L, 102L), hits.map { it.topicId }.toSet())
        // Both deduplicated entries resolved to "骗子" (highest severity)
        assertEquals(TrustRiskEvaluator.TERM_SCAMMER, hits.first { it.topicId == 101L }.matchedTerm)
        assertEquals(TrustRiskEvaluator.TERM_SCAMMER, hits.first { it.topicId == 102L }.matchedTerm)
    }

    @Test
    fun filterExposureHits_supportsChineseUsernamesAndSnippetMatching() {
        val candidates = listOf(
            ExposureCandidate(
                topic = topic(id = 201, title = "挂人小鸡贩子收款删好友", categorySlug = "daily", categoryName = "日常", authorId = 800, authorName = "reporter1"),
            ),
            ExposureCandidate(
                topic = topic(id = 202, title = "曝光台：今天又遇到一个跑路的", categorySlug = "expose", categoryName = "曝光", authorId = 801, authorName = "reporter2"),
                snippet = "涉事卖家 ID 为 小鸡贩子，付完 USDT 就失联",
            ),
        )

        val hits = TrustRiskEvaluator.filterExposureHits(
            candidates = candidates,
            targetUid = 777L,
            targetUsername = "小鸡贩子",
        )

        assertEquals(2, hits.size)
        assertEquals(TrustRiskEvaluator.TERM_RUNAWAY, hits.first { it.topicId == 202L }.matchedTerm)
        assertEquals(TrustRiskEvaluator.TERM_CALLOUT, hits.first { it.topicId == 201L }.matchedTerm)
    }

    @Test
    fun filterExposureHits_countsExposeBoardPostEvenWithoutExplicitKeywordInTitle() {
        val candidates = listOf(
            ExposureCandidate(
                topic = topic(
                    id = 250L,
                    title = "关于 @alphaSeller 的交易经过说明",
                    categorySlug = "expose",
                    categoryName = "曝光",
                    authorId = 600L,
                    authorName = "buyer99",
                ),
            ),
        )
        val hits = TrustRiskEvaluator.filterExposureHits(
            candidates = candidates,
            targetUid = 500L,
            targetUsername = "alphaSeller",
        )
        assertEquals(1, hits.size)
        assertEquals(TrustRiskEvaluator.TERM_EXPOSE, hits.single().matchedTerm)
    }

    // ── Risk Evaluator Rules ──

    @Test
    fun evaluate_marksHighRiskRed_whenExposureHitsPresent_evenIfLocalNoteIsTrusted() {
        val hit = ExposureHitTopic(
            topicId = 301L,
            title = "曝光 sellerA 骗子",
            categoryName = "曝光",
            authorName = "victim",
            publishedAtText = "2026-11-18T10:00:00Z",
            matchedTerm = "骗子",
        )
        val assessment = TrustRiskEvaluator.evaluate(
            rankLevel = 5,
            registrationDays = 900,
            historicalTradeTopicCount = 12,
            exposureSearchHits = listOf(hit),
            searchSucceeded = true,
            localNote = LocalSellerNote(uid = 1L, username = "sellerA", tag = TrustTag.TRUSTED),
        )

        assertEquals(TrustRiskLevel.HIGH_RISK_RED, assessment.level)
        assertTrue(assessment.headline.contains("命中 1 条"))
        assertTrue(assessment.reasons.any { it.contains("1 条关联曝光帖") })
    }

    @Test
    fun evaluate_marksHighRiskRed_whenLocalNoteIsBlacklist() {
        val assessment = TrustRiskEvaluator.evaluate(
            rankLevel = 4,
            registrationDays = 500,
            historicalTradeTopicCount = 5,
            exposureSearchHits = emptyList(),
            searchSucceeded = true,
            localNote = LocalSellerNote(
                uid = 2L,
                username = "sellerB",
                tag = TrustTag.RISK_BLACKLIST,
                noteText = "私下交易爽约",
            ),
        )

        assertEquals(TrustRiskLevel.HIGH_RISK_RED, assessment.level)
        assertTrue(assessment.reasons.any { it.contains("高危避雷") && it.contains("私下交易爽约") })
    }

    @Test
    fun evaluate_marksCautionAmber_onEachCautionRuleIndependently() {
        // 1. Local CAUTION tag
        val cautionTag = TrustRiskEvaluator.evaluate(
            rankLevel = 4,
            registrationDays = 400,
            historicalTradeTopicCount = 3,
            exposureSearchHits = emptyList(),
            searchSucceeded = true,
            localNote = LocalSellerNote(uid = 3L, username = "sellerC", tag = TrustTag.CAUTION),
        )
        assertEquals(TrustRiskLevel.CAUTION_AMBER, cautionTag.level)

        // 2. Account registration < 30 days
        val newAccount = TrustRiskEvaluator.evaluate(
            rankLevel = 3,
            registrationDays = 29,
            historicalTradeTopicCount = 2,
            exposureSearchHits = emptyList(),
            searchSucceeded = true,
        )
        assertEquals(TrustRiskLevel.CAUTION_AMBER, newAccount.level)
        assertTrue(newAccount.reasons.any { it.contains("29 天") })

        // 3. rankLevel <= 1 with 0 prior trade history
        val lowRankNoTrade = TrustRiskEvaluator.evaluate(
            rankLevel = 1,
            registrationDays = 180,
            historicalTradeTopicCount = 0,
            exposureSearchHits = emptyList(),
            searchSucceeded = true,
        )
        assertEquals(TrustRiskLevel.CAUTION_AMBER, lowRankNoTrade.level)
        assertTrue(lowRankNoTrade.reasons.any { it.contains("Lv.1") })

        // 4. Search failed (incomplete exposure check)
        val searchFailed = TrustRiskEvaluator.evaluate(
            rankLevel = 5,
            registrationDays = 600,
            historicalTradeTopicCount = 10,
            exposureSearchHits = emptyList(),
            searchSucceeded = false,
        )
        assertEquals(TrustRiskLevel.CAUTION_AMBER, searchFailed.level)
        assertTrue(searchFailed.reasons.any { it.contains("曝光搜索未完整完成") })
    }

    @Test
    fun evaluate_marksNormalClear_whenNoRedOrAmberRulesFire() {
        // Boundary: 30 days old, Lv.1 WITH 1 trade topic, search succeeded, no hits
        val boundaryClear = TrustRiskEvaluator.evaluate(
            rankLevel = 1,
            registrationDays = 30,
            historicalTradeTopicCount = 1,
            exposureSearchHits = emptyList(),
            searchSucceeded = true,
        )
        assertEquals(TrustRiskLevel.NORMAL_CLEAR, boundaryClear.level)
        assertTrue(boundaryClear.safetyTip.contains("官方中介"))

        // Lv.2 with 0 trade topics, 90 days old, search succeeded
        val midRankClear = TrustRiskEvaluator.evaluate(
            rankLevel = 2,
            registrationDays = 90,
            historicalTradeTopicCount = 0,
            exposureSearchHits = emptyList(),
            searchSucceeded = true,
            localNote = LocalSellerNote(uid = 9L, username = "goodSeller", tag = TrustTag.TRUSTED),
            registrationDurationLabel = "3个月",
        )
        assertEquals(TrustRiskLevel.NORMAL_CLEAR, midRankClear.level)
        assertTrue(midRankClear.headline.contains("靠谱卖家"))
    }

    private fun topic(
        id: Long,
        title: String,
        categorySlug: String = "trade",
        categoryName: String = "交易",
        authorId: Long? = 100L,
        authorName: String = "someone",
        lastActivityIso: String = "2026-11-18T08:00:00Z",
    ): TopicSummary = TopicSummary(
        id = id,
        title = title,
        categorySlug = categorySlug,
        categoryName = categoryName,
        authorId = authorId,
        authorName = authorName,
        lastActivityIso = lastActivityIso,
    )
}
