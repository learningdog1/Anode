package com.nodeseek.app.domain.model

import com.nodeseek.app.data.network.CreditLedgerResponseDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class EconomyModelsAndAnalyticsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun makeEntry(
        change: Int,
        balance: Int,
        reason: String,
        createdAtRaw: String,
    ): CreditLedgerEntry = CreditLedgerEntry(
        change = change,
        balance = balance,
        reason = reason,
        createdAtRaw = createdAtRaw,
        createdAtEpochMillis = CreditLedgerAnalytics.parseLedgerTimestampMillis(createdAtRaw),
        category = CreditLedgerAnalytics.classifyCreditReason(reason = reason, change = change),
    )

    @Test
    fun `analyzes credit_ledger_30d fixture including 0-coin check-ins and leg-add ratio`() {
        val fixtureCandidates = listOf(
            File("app/src/test/resources/e2e/nodeseek/credit_ledger_30d.json"),
            File("src/test/resources/e2e/nodeseek/credit_ledger_30d.json"),
        )
        val fixtureFile = fixtureCandidates.firstOrNull { it.isFile }
            ?: error("credit_ledger_30d.json fixture not found from ${File(".").absolutePath}")

        val dto = json.decodeFromString(
            CreditLedgerResponseDto.serializer(),
            fixtureFile.readText(Charsets.UTF_8),
        )
        val entries = dto.data.map {
            makeEntry(
                change = it.change,
                balance = it.balance,
                reason = it.reason,
                createdAtRaw = it.createdAt,
            )
        }
        val nowMillis = Instant.parse("2026-11-25T00:00:00Z").toEpochMilli()

        val analytics = CreditLedgerAnalytics.analyze(
            entries = entries,
            currentCoins = 1650,
            nowEpochMillis = nowMillis,
            windowDays = 30,
        )

        // 7 check-ins (5 + 12 + 0 + 5 + 8 + 5 + 10 = 45), 3 leg-add expenses (-1 -1 -1 = 3)
        assertEquals(10, analytics.windowEntriesCount)
        assertEquals(7, analytics.checkInCount)
        assertEquals(45, analytics.checkInTotalCoins)
        assertEquals(45.0 / 7.0, analytics.checkInAverageCoins!!, 1e-6)
        assertEquals(45, analytics.totalIncomeCoins)
        assertEquals(3, analytics.totalExpenseCoins)
        assertEquals(3, analytics.drumstickGiftExpenseCoins)
        assertEquals(1.0, analytics.drumstickExpenseRatio, 1e-6)
        assertEquals(42, analytics.netCoinChange30d)
        assertEquals(42.0 / 30.0, analytics.netDailyRate, 1e-6)

        // 1650 coins => Lv.4 (1600), next Lv.5 (2500), remaining = 850, ceil(850 / (42/30)) = 608
        assertEquals(4, analytics.upgradeEta.currentLevel)
        assertEquals(5, analytics.upgradeEta.nextLevel)
        assertEquals(2500, analytics.upgradeEta.nextLevelThreshold)
        assertEquals(850, analytics.upgradeEta.coinsRemaining)
        assertEquals(UpgradeEtaStatus.ESTIMATED, analytics.upgradeEta.status)
        assertEquals(608, analytics.upgradeEta.estimatedDaysToNextLevel)

        // Check Lv.1..Lv.6 milestone ladder (100 * level^2)
        assertEquals(listOf(100, 400, 900, 1600, 2500, 3600), analytics.levelMilestones.map { it.requiredCoins })
        assertTrue(analytics.levelMilestones.take(4).all { it.unlocked && it.estimatedDays == 0 })
        assertFalse(analytics.levelMilestones[4].unlocked)
        assertEquals(850, analytics.levelMilestones[4].coinsNeeded)
        assertEquals(608, analytics.levelMilestones[4].estimatedDays)
        assertEquals(1950, analytics.levelMilestones[5].coinsNeeded)
        assertEquals(1393, analytics.levelMilestones[5].estimatedDays)
    }

    @Test
    fun `parses SQL Beijing time, ISO-8601, OffsetDateTime, date-only, and numeric epochs and enforces 30d cutoff`() {
        val sqlBeijing = CreditLedgerAnalytics.parseLedgerTimestampMillis("2026-11-24 08:00:00")
        val isoUtc = CreditLedgerAnalytics.parseLedgerTimestampMillis("2026-11-24T00:00:00Z")
        assertNotNull(sqlBeijing)
        assertEquals(isoUtc, sqlBeijing)

        val offsetIso = CreditLedgerAnalytics.parseLedgerTimestampMillis("2026-11-24T08:00:00+08:00")
        assertEquals(isoUtc, offsetIso)

        val dateOnly = CreditLedgerAnalytics.parseLedgerTimestampMillis("2026-11-24")
        assertEquals(Instant.parse("2026-11-23T16:00:00Z").toEpochMilli(), dateOnly)

        val epochSeconds = CreditLedgerAnalytics.parseLedgerTimestampMillis("1764000000")
        val epochMillis = CreditLedgerAnalytics.parseLedgerTimestampMillis("1764000000000")
        assertEquals(1764000000_000L, epochSeconds)
        assertEquals(1764000000_000L, epochMillis)

        // Entry 31 days old must be excluded from 30d window while entry 30 days old (exact boundary) is kept
        val now = Instant.parse("2026-11-25T00:00:00Z").toEpochMilli()
        val exact30d = Instant.ofEpochMilli(now - 30L * 86_400_000L).toString()
        val expired31d = Instant.ofEpochMilli(now - 31L * 86_400_000L).toString()

        val analytics = CreditLedgerAnalytics.analyze(
            entries = listOf(
                makeEntry(10, 110, "每日签到奖励", exact30d),
                makeEntry(99, 100, "每日签到奖励", expired31d),
            ),
            currentCoins = 110,
            nowEpochMillis = now,
            windowDays = 30,
        )
        assertEquals(1, analytics.windowEntriesCount)
        assertEquals(1, analytics.checkInCount)
        assertEquals(10, analytics.checkInTotalCoins)
    }

    @Test
    fun `classifies leg-add expenses strictly when negative and computes mixed expense ratio`() {
        val now = Instant.parse("2026-11-25T00:00:00Z").toEpochMilli()
        val entries = listOf(
            makeEntry(-1, 500, "给评论加鸡腿", "2026-11-24 10:00:00"),
            makeEntry(-1, 501, "投喂鸡腿", "2026-11-23 10:00:00"),
            makeEntry(1, 502, "收到主题加鸡腿", "2026-11-22 10:00:00"),
            makeEntry(-100, 402, "购买邀请码", "2026-11-21 10:00:00"),
        )
        assertEquals(CreditEntryCategory.DRUMSTICK_GIFT_EXPENSE, entries[0].category)
        assertEquals(CreditEntryCategory.DRUMSTICK_GIFT_EXPENSE, entries[1].category)
        assertEquals(CreditEntryCategory.DRUMSTICK_GIFT_INCOME, entries[2].category)
        assertEquals(CreditEntryCategory.OTHER_EXPENSE, entries[3].category)

        val analytics = CreditLedgerAnalytics.analyze(
            entries = entries,
            currentCoins = 402,
            nowEpochMillis = now,
        )
        assertEquals(0, analytics.checkInCount)
        assertNull(analytics.checkInAverageCoins)
        assertEquals(2, analytics.drumstickGiftExpenseCoins)
        assertEquals(102, analytics.totalExpenseCoins)
        assertEquals(2.0 / 102.0, analytics.drumstickExpenseRatio, 1e-6)
    }

    @Test
    fun `handles edge cases MAX_LEVEL, INSUFFICIENT_DATA, STAGNANT_OR_DECLINING, and zero expenses`() {
        val now = Instant.parse("2026-11-25T00:00:00Z").toEpochMilli()

        // 1. Empty window -> INSUFFICIENT_DATA, zero ratio, null average
        val emptyAnalytics = CreditLedgerAnalytics.analyze(
            entries = emptyList(),
            currentCoins = 250,
            nowEpochMillis = now,
        )
        assertNull(emptyAnalytics.checkInAverageCoins)
        assertEquals(0.0, emptyAnalytics.drumstickExpenseRatio, 1e-6)
        assertEquals(UpgradeEtaStatus.INSUFFICIENT_DATA, emptyAnalytics.upgradeEta.status)
        assertNull(emptyAnalytics.upgradeEta.estimatedDaysToNextLevel)
        assertEquals(1, emptyAnalytics.upgradeEta.currentLevel)
        assertEquals(400, emptyAnalytics.upgradeEta.nextLevelThreshold)
        assertEquals(150, emptyAnalytics.upgradeEta.coinsRemaining)

        // 2. Negative net rate -> STAGNANT_OR_DECLINING
        val declining = CreditLedgerAnalytics.analyze(
            entries = listOf(makeEntry(-50, 200, "给主题加鸡腿", "2026-11-24 10:00:00")),
            currentCoins = 200,
            nowEpochMillis = now,
        )
        assertEquals(UpgradeEtaStatus.STAGNANT_OR_DECLINING, declining.upgradeEta.status)
        assertNull(declining.upgradeEta.estimatedDaysToNextLevel)

        // 3. Lv.6 (>= 3600) -> MAX_LEVEL
        val maxLevel = CreditLedgerAnalytics.analyze(
            entries = listOf(makeEntry(10, 4000, "每日签到奖励", "2026-11-24 10:00:00")),
            currentCoins = 4000,
            nowEpochMillis = now,
        )
        assertEquals(6, maxLevel.upgradeEta.currentLevel)
        assertNull(maxLevel.upgradeEta.nextLevel)
        assertNull(maxLevel.upgradeEta.nextLevelThreshold)
        assertEquals(UpgradeEtaStatus.MAX_LEVEL, maxLevel.upgradeEta.status)
        assertNull(maxLevel.upgradeEta.estimatedDaysToNextLevel)
        assertEquals(0.0, maxLevel.drumstickExpenseRatio, 1e-6)
    }

    @Test
    fun `CreditLedgerTupleSerializer decodes JSON nulls as empty strings and strips commas from numeric strings`() {
        val raw = """
            {
              "success": true,
              "total": 3,
              "data": [
                [5, 100, null, null],
                ["1,000", "2,000", "转账", "2026-11-24 08:00:00"],
                ["-1,250", "750", "消费", "2026-11-24 09:00:00"]
              ]
            }
        """.trimIndent()
        val dto = json.decodeFromString(CreditLedgerResponseDto.serializer(), raw)
        assertEquals(3, dto.data.size)

        val nullEntry = dto.data[0]
        assertEquals(5, nullEntry.change)
        assertEquals(100, nullEntry.balance)
        assertEquals("", nullEntry.reason)
        assertEquals("", nullEntry.createdAt)

        val commaIncome = dto.data[1]
        assertEquals(1000, commaIncome.change)
        assertEquals(2000, commaIncome.balance)
        assertEquals("转账", commaIncome.reason)
        assertEquals("2026-11-24 08:00:00", commaIncome.createdAt)

        val commaExpense = dto.data[2]
        assertEquals(-1250, commaExpense.change)
        assertEquals(750, commaExpense.balance)
    }
}
