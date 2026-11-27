package com.nodeseek.app.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TreeMap
import kotlin.math.ceil

enum class CreditEntryCategory(val label: String) {
    CHECK_IN("签到"),
    DRUMSTICK_GIFT_EXPENSE("加腿消耗"),
    DRUMSTICK_GIFT_INCOME("收到加腿"),
    POST_OR_REPLY_BONUS("发帖/回帖"),
    OTHER_INCOME("其他收入"),
    OTHER_EXPENSE("其他支出"),
}

data class CreditLedgerEntry(
    val change: Int,
    val balance: Int,
    val reason: String,
    val createdAtRaw: String,
    val createdAtEpochMillis: Long?,
    val category: CreditEntryCategory,
)

data class CreditLedgerPage(
    val entries: List<CreditLedgerEntry>,
    val totalCount: Int,
    val currentPage: Int,
    val hasNextPage: Boolean,
)

enum class StardustRecordType(val apiKey: String, val label: String) {
    SYSTEM("system", "系统"),
    ADMIN("admin", "管理"),
    TRANSFER("transfer", "转账"),
    UPVOTE("upvote", "点赞"),
    BUY_CODE("buyCode", "购买邀请码"),
    OTHER("", "其他"),
    ;

    companion object {
        fun fromApiType(raw: String): StardustRecordType {
            val normalized = raw.trim()
            return entries.firstOrNull {
                it.apiKey.isNotEmpty() && it.apiKey.equals(normalized, ignoreCase = true)
            } ?: OTHER
        }
    }
}

data class StardustLedgerEntry(
    val id: Long,
    val diff: Int,
    val balanceAfter: Int,
    val rawType: String,
    val typeLabel: String,
    val createdAtRaw: String,
    val createdAtEpochMillis: Long?,
    val peerUid: Long?,
)

data class StardustLedgerPage(
    val records: List<StardustLedgerEntry>,
    val hasMore: Boolean,
    val nextCursor: Long?,
)

data class DailyCoinBucket(
    val localDate: String,
    val incomeCoins: Int,
    val expenseCoins: Int,
    val netCoins: Int,
)

enum class UpgradeEtaStatus {
    MAX_LEVEL,
    ESTIMATED,
    STAGNANT_OR_DECLINING,
    INSUFFICIENT_DATA,
}

data class LevelUpgradeEta(
    val currentLevel: Int,
    val currentCoins: Int,
    val nextLevel: Int?,
    val nextLevelThreshold: Int?,
    val coinsRemaining: Int,
    val netCoinsPerDay30d: Double,
    val estimatedDaysToNextLevel: Int?,
    val status: UpgradeEtaStatus,
)

data class LevelMilestoneEstimate(
    val level: Int,
    val requiredCoins: Int,
    val unlocked: Boolean,
    val coinsNeeded: Int,
    val estimatedDays: Int?,
)

data class CreditAnalytics30d(
    val windowDays: Int = 30,
    val windowEntriesCount: Int,
    val checkInCount: Int,
    val checkInTotalCoins: Int,
    val checkInAverageCoins: Double?,
    val totalIncomeCoins: Int,
    val totalExpenseCoins: Int,
    val drumstickGiftExpenseCoins: Int,
    val drumstickExpenseRatio: Double,
    val netCoinChange30d: Int,
    val netDailyRate: Double,
    val upgradeEta: LevelUpgradeEta,
    val levelMilestones: List<LevelMilestoneEstimate>,
    val dailyTrendBuckets: List<DailyCoinBucket>,
)

data class CreditAnalyticsLoadResult(
    val firstPage: CreditLedgerPage,
    val fetchedEntries: List<CreditLedgerEntry>,
    val analytics: CreditAnalytics30d,
)

enum class InvitePaymentMethod(val apiValue: String, val label: String) {
    COIN("coin", "鸡腿"),
    STARDUST("stardust", "星尘"),
}

data class InvitePaymentOption(
    val method: InvitePaymentMethod,
    val enabled: Boolean,
    val cost: Int,
)

data class InviteConfig(
    val coinOption: InvitePaymentOption?,
    val stardustOption: InvitePaymentOption?,
)

data class InviteCodeItem(
    val code: String,
    val applied: Boolean,
    val createdAtRaw: String,
    val createdAtEpochMillis: Long?,
    val appliedAtRaw: String? = null,
    val appliedBy: String? = null,
)

data class InviteListPage(
    val items: List<InviteCodeItem>,
    val totalCount: Int,
    val currentPage: Int,
    val hasNextPage: Boolean,
)

data class InvitePurchaseResult(
    val code: String,
    val paymentMethod: InvitePaymentMethod,
    val message: String? = null,
)

data class ModerationRuling(
    val id: Long,
    val memberId: Long?,
    val memberName: String,
    val reason: String,
    val action: String,
    val evidence: String? = null,
    val operatorName: String? = null,
    val postId: Long? = null,
    val createdAtRaw: String = "",
    val createdAtEpochMillis: Long? = null,
)

data class ModerationRulingPage(
    val items: List<ModerationRuling>,
    val totalCount: Int,
    val currentPage: Int,
    val hasNextPage: Boolean,
)

/**
 * Pure-Kotlin 30-day drumstick ledger analytics and Lv.1–Lv.6 upgrade calculator.
 * Uses `java.time` exclusively so all logic executes deterministically in JVM unit tests.
 */
object CreditLedgerAnalytics {

    const val DEFAULT_WINDOW_DAYS: Int = 30
    private const val MILLIS_PER_DAY: Long = 86_400_000L
    private const val MAX_LEVEL: Int = 6
    private val BEIJING_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    private val SQL_DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun parseLedgerTimestampMillis(raw: String?): Long? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        // 1. ISO-8601 instant with Z
        runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrNull()?.let { return it }

        // 2. ISO-8601 with explicit offset
        runCatching { OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }.getOrNull()?.let { return it }

        // 3. SQL Beijing-time string "yyyy-MM-dd HH:mm:ss[.SSS]"
        val normalizedSql = trimmed.substringBefore('.')
        runCatching {
            LocalDateTime.parse(normalizedSql, SQL_DATE_TIME_FORMATTER)
                .atZone(BEIJING_ZONE)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()?.let { return it }

        // 4. LocalDateTime ISO ("yyyy-MM-ddTHH:mm:ss")
        runCatching {
            LocalDateTime.parse(normalizedSql, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(BEIJING_ZONE)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()?.let { return it }

        // 5. Date-only string ("yyyy-MM-dd")
        if (trimmed.length == 10) {
            runCatching {
                LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE)
                    .atStartOfDay(BEIJING_ZONE)
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()?.let { return it }
        }

        // 6. Numeric epoch string (seconds or milliseconds)
        if (trimmed.all { it.isDigit() }) {
            val numeric = trimmed.toLongOrNull() ?: return null
            return when {
                trimmed.length >= 13 -> numeric
                trimmed.length == 10 -> numeric * 1000L
                else -> null
            }
        }

        // Fallback date-only prefix if string had trailing tokens
        if (trimmed.length > 10 && trimmed[4] == '-' && trimmed[7] == '-') {
            runCatching {
                LocalDate.parse(trimmed.take(10), DateTimeFormatter.ISO_LOCAL_DATE)
                    .atStartOfDay(BEIJING_ZONE)
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()?.let { return it }
        }

        return null
    }

    fun classifyCreditReason(reason: String, change: Int): CreditEntryCategory {
        val r = reason.trim().lowercase()

        // Check-in entries can be 0-coin rolls, so include change >= 0.
        if (change >= 0 && (
                r.contains("签到") ||
                    r.contains("attendance") ||
                    r.contains("checkin") ||
                    r.contains("check-in")
                )
        ) {
            return CreditEntryCategory.CHECK_IN
        }

        // 加腿 expenses must strictly have change < 0 so incoming gifts never count as expenses.
        if (change < 0 && (
                r.contains("加鸡腿") ||
                    r.contains("加腿") ||
                    r.contains("送鸡腿") ||
                    r.contains("投喂鸡腿") ||
                    r == "like" ||
                    r.contains("give like") ||
                    r.contains("freelike")
                )
        ) {
            return CreditEntryCategory.DRUMSTICK_GIFT_EXPENSE
        }

        if (change > 0 && (
                r.contains("加鸡腿") ||
                    r.contains("加腿") ||
                    r.contains("收到鸡腿")
                )
        ) {
            return CreditEntryCategory.DRUMSTICK_GIFT_INCOME
        }

        if (change > 0 && (
                r.contains("发帖") ||
                    r.contains("回帖") ||
                    r.contains("回复") ||
                    r.contains("主题") ||
                    r.contains("评论")
                )
        ) {
            return CreditEntryCategory.POST_OR_REPLY_BONUS
        }

        return if (change >= 0) {
            CreditEntryCategory.OTHER_INCOME
        } else {
            CreditEntryCategory.OTHER_EXPENSE
        }
    }

    fun analyze(
        entries: List<CreditLedgerEntry>,
        currentCoins: Int,
        nowEpochMillis: Long = System.currentTimeMillis(),
        windowDays: Int = DEFAULT_WINDOW_DAYS,
    ): CreditAnalytics30d {
        val safeWindowDays = windowDays.coerceAtLeast(1)
        val cutoffMillis = nowEpochMillis - safeWindowDays.toLong() * MILLIS_PER_DAY
        val safeCoins = currentCoins.coerceAtLeast(0)

        val windowEntries = entries.mapNotNull { entry ->
            val ts = entry.createdAtEpochMillis ?: parseLedgerTimestampMillis(entry.createdAtRaw)
            if (ts != null && ts >= cutoffMillis && ts <= nowEpochMillis + MILLIS_PER_DAY) {
                if (entry.createdAtEpochMillis == ts) entry else entry.copy(createdAtEpochMillis = ts)
            } else {
                null
            }
        }

        val checkInEntries = windowEntries.filter { it.category == CreditEntryCategory.CHECK_IN }
        val checkInCount = checkInEntries.size
        val checkInTotalCoins = checkInEntries.sumOf { it.change }
        val checkInAverageCoins: Double? = if (checkInCount > 0) {
            checkInTotalCoins.toDouble() / checkInCount.toDouble()
        } else {
            null
        }

        val totalIncomeCoins = windowEntries.filter { it.change > 0 }.sumOf { it.change }
        val totalExpenseCoins = windowEntries.filter { it.change < 0 }.sumOf { -it.change }
        val drumstickGiftExpenseCoins = windowEntries
            .filter { it.category == CreditEntryCategory.DRUMSTICK_GIFT_EXPENSE && it.change < 0 }
            .sumOf { -it.change }

        val drumstickExpenseRatio = if (totalExpenseCoins > 0) {
            (drumstickGiftExpenseCoins.toDouble() / totalExpenseCoins.toDouble()).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        val netCoinChange30d = totalIncomeCoins - totalExpenseCoins
        val netDailyRate = if (windowEntries.isNotEmpty()) {
            netCoinChange30d.toDouble() / safeWindowDays.toDouble()
        } else {
            0.0
        }

        val currentLevel = AccountSession.calculateLevel(safeCoins)
        val nextLevel = if (currentLevel >= MAX_LEVEL) null else currentLevel + 1
        val nextLevelThreshold = nextLevel?.let { 100 * it * it }
        val coinsRemaining = nextLevelThreshold?.let { (it - safeCoins).coerceAtLeast(0) } ?: 0

        val etaStatus: UpgradeEtaStatus
        val estimatedDaysToNextLevel: Int?
        when {
            currentLevel >= MAX_LEVEL -> {
                etaStatus = UpgradeEtaStatus.MAX_LEVEL
                estimatedDaysToNextLevel = null
            }
            windowEntries.isEmpty() -> {
                etaStatus = UpgradeEtaStatus.INSUFFICIENT_DATA
                estimatedDaysToNextLevel = null
            }
            netDailyRate <= 0.0 -> {
                etaStatus = UpgradeEtaStatus.STAGNANT_OR_DECLINING
                estimatedDaysToNextLevel = null
            }
            else -> {
                etaStatus = UpgradeEtaStatus.ESTIMATED
                estimatedDaysToNextLevel = if (coinsRemaining == 0) {
                    0
                } else {
                    ceil(coinsRemaining / netDailyRate).toInt().coerceAtLeast(1)
                }
            }
        }

        val upgradeEta = LevelUpgradeEta(
            currentLevel = currentLevel,
            currentCoins = safeCoins,
            nextLevel = nextLevel,
            nextLevelThreshold = nextLevelThreshold,
            coinsRemaining = coinsRemaining,
            netCoinsPerDay30d = netDailyRate,
            estimatedDaysToNextLevel = estimatedDaysToNextLevel,
            status = etaStatus,
        )

        val levelMilestones = (1..MAX_LEVEL).map { lvl ->
            val required = 100 * lvl * lvl
            val unlocked = safeCoins >= required
            val needed = (required - safeCoins).coerceAtLeast(0)
            val estDays = when {
                unlocked -> 0
                windowEntries.isNotEmpty() && netDailyRate > 0.0 ->
                    ceil(needed / netDailyRate).toInt().coerceAtLeast(1)
                else -> null
            }
            LevelMilestoneEstimate(
                level = lvl,
                requiredCoins = required,
                unlocked = unlocked,
                coinsNeeded = needed,
                estimatedDays = estDays,
            )
        }

        val dailyMap = TreeMap<String, Pair<Int, Int>>()
        for (entry in windowEntries) {
            val ts = entry.createdAtEpochMillis ?: continue
            val localDate = Instant.ofEpochMilli(ts).atZone(BEIJING_ZONE).toLocalDate().toString()
            val prev = dailyMap[localDate] ?: (0 to 0)
            val nextPair = if (entry.change >= 0) {
                (prev.first + entry.change) to prev.second
            } else {
                prev.first to (prev.second + (-entry.change))
            }
            dailyMap[localDate] = nextPair
        }
        val dailyTrendBuckets = dailyMap.entries.map { (date, pair) ->
            DailyCoinBucket(
                localDate = date,
                incomeCoins = pair.first,
                expenseCoins = pair.second,
                netCoins = pair.first - pair.second,
            )
        }

        return CreditAnalytics30d(
            windowDays = safeWindowDays,
            windowEntriesCount = windowEntries.size,
            checkInCount = checkInCount,
            checkInTotalCoins = checkInTotalCoins,
            checkInAverageCoins = checkInAverageCoins,
            totalIncomeCoins = totalIncomeCoins,
            totalExpenseCoins = totalExpenseCoins,
            drumstickGiftExpenseCoins = drumstickGiftExpenseCoins,
            drumstickExpenseRatio = drumstickExpenseRatio,
            netCoinChange30d = netCoinChange30d,
            netDailyRate = netDailyRate,
            upgradeEta = upgradeEta,
            levelMilestones = levelMilestones,
            dailyTrendBuckets = dailyTrendBuckets,
        )
    }
}
