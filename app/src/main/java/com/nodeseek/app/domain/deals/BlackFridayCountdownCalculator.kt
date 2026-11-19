package com.nodeseek.app.domain.deals

import androidx.compose.runtime.Immutable
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * Active seasonal phase of the Black Friday / Cyber Week countdown.
 */
enum class BlackFridayEventPhase(
    val badgeTitle: String,
    val headlineLabel: String,
) {
    PRE_BLACK_FRIDAY("BLACK FRIDAY", "距离黑五正日还有"),
    BLACK_FRIDAY_LIVE("黑五正日狂欢", "黑五正日进行中 · 距本波结束"),
    CYBER_WEEKEND("黑五返场 · 迎网一", "距离网络星期一开启"),
    CYBER_MONDAY_LIVE("CYBER MONDAY", "网络星期一收官 · 距大促结束"),
}

/**
 * Immutable countdown snapshot computed in `Asia/Shanghai` for Black Friday & Cyber Monday.
 */
@Immutable
data class BlackFridayCountdownSnapshot(
    val eventYear: Int,
    val phase: BlackFridayEventPhase,
    val blackFridayDate: LocalDate,
    val cyberMondayDate: LocalDate,
    val targetEpochMillis: Long,
    val remainingMillis: Long,
    val days: Long,
    val hours: Long,
    val minutes: Long,
    val seconds: Long,
    val cyberMondayRemainingMillis: Long,
) {
    val formattedHms: String
        get() = "%02d:%02d:%02d".format(hours, minutes, seconds)

    val formattedDayHms: String
        get() = if (days > 0L) {
            "${days}天 $formattedHms"
        } else {
            formattedHms
        }

    val bannerHeadline: String
        get() = when (phase) {
            BlackFridayEventPhase.PRE_BLACK_FRIDAY -> "距离黑五还有 $formattedDayHms"
            BlackFridayEventPhase.BLACK_FRIDAY_LIVE -> "黑五正日狂欢中 · 剩 $formattedDayHms"
            BlackFridayEventPhase.CYBER_WEEKEND -> "黑五返场 · 距网一 $formattedDayHms"
            BlackFridayEventPhase.CYBER_MONDAY_LIVE -> "网络星期一收官 · 剩 $formattedDayHms"
        }

    val eventDateRangeLabel: String
        get() {
            val fmt = DateTimeFormatter.ofPattern("MM/dd")
            return "黑五 ${blackFridayDate.format(fmt)} · 网一 ${cyberMondayDate.format(fmt)}"
        }
}

/**
 * Per-deal expiry countdown badge state.
 */
@Immutable
data class DealExpiryCountdown(
    val label: String,
    val isUrgent: Boolean,
    val isExpired: Boolean,
)

/**
 * Deterministic Black Friday & Cyber Monday countdown engine pinned to `Asia/Shanghai`.
 *
 * Rules:
 * - Black Friday is always the day after the 4th Thursday of November (e.g. `2026-11-27`).
 * - Cyber Monday is Black Friday + 3 days (e.g. `2026-11-30`), ending at `Dec 01 00:00:00` CST.
 * - After Cyber Monday ends, the countdown rolls cleanly to next year's Black Friday.
 */
object BlackFridayCountdownCalculator {

    val ZONE_ID: ZoneId = VALUATION_ZONE_ID

    fun blackFridayDate(year: Int): LocalDate {
        val fourthThursday = LocalDate.of(year, Month.NOVEMBER, 1)
            .with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY))
        return fourthThursday.plusDays(1)
    }

    fun cyberMondayDate(year: Int): LocalDate = blackFridayDate(year).plusDays(3)

    fun compute(
        nowEpochMillis: Long,
        zoneId: ZoneId = ZONE_ID,
    ): BlackFridayCountdownSnapshot {
        val nowZdt = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId)
        val currentYear = nowZdt.year
        val currentCyberMondayEnd = cyberMondayDate(currentYear)
            .plusDays(1)
            .atStartOfDay(zoneId)

        val eventYear = if (!nowZdt.isBefore(currentCyberMondayEnd)) {
            currentYear + 1
        } else {
            currentYear
        }

        val bfDate = blackFridayDate(eventYear)
        val cmDate = cyberMondayDate(eventYear)

        val bfStart = bfDate.atStartOfDay(zoneId)
        val bfEnd = bfDate.plusDays(1).atStartOfDay(zoneId)
        val cmStart = cmDate.atStartOfDay(zoneId)
        val cmEnd = cmDate.plusDays(1).atStartOfDay(zoneId)

        val (phase, targetZdt) = when {
            nowZdt.isBefore(bfStart) -> BlackFridayEventPhase.PRE_BLACK_FRIDAY to bfStart
            nowZdt.isBefore(bfEnd) -> BlackFridayEventPhase.BLACK_FRIDAY_LIVE to bfEnd
            nowZdt.isBefore(cmStart) -> BlackFridayEventPhase.CYBER_WEEKEND to cmStart
            else -> BlackFridayEventPhase.CYBER_MONDAY_LIVE to cmEnd
        }

        val remainingMillis = (targetZdt.toInstant().toEpochMilli() - nowEpochMillis).coerceAtLeast(0L)
        val cmRemainingMillis = (cmEnd.toInstant().toEpochMilli() - nowEpochMillis).coerceAtLeast(0L)

        val totalSeconds = remainingMillis / 1000L
        val days = totalSeconds / 86_400L
        val hours = (totalSeconds % 86_400L) / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L

        return BlackFridayCountdownSnapshot(
            eventYear = eventYear,
            phase = phase,
            blackFridayDate = bfDate,
            cyberMondayDate = cmDate,
            targetEpochMillis = targetZdt.toInstant().toEpochMilli(),
            remainingMillis = remainingMillis,
            days = days,
            hours = hours,
            minutes = minutes,
            seconds = seconds,
            cyberMondayRemainingMillis = cmRemainingMillis,
        )
    }

    /**
     * Convenience overload for computing countdown from an explicit local date/time in `Asia/Shanghai`.
     */
    fun compute(nowZdt: ZonedDateTime): BlackFridayCountdownSnapshot =
        compute(nowEpochMillis = nowZdt.toInstant().toEpochMilli(), zoneId = nowZdt.zone)

    /**
     * Formats an individual deal's expiry countdown badge.
     */
    fun formatDealExpiry(
        expiresAtEpochMillis: Long?,
        nowEpochMillis: Long,
    ): DealExpiryCountdown? {
        if (expiresAtEpochMillis == null || expiresAtEpochMillis <= 0L) return null
        val diffMillis = expiresAtEpochMillis - nowEpochMillis
        if (diffMillis <= 0L) {
            return DealExpiryCountdown(
                label = "已截止",
                isUrgent = false,
                isExpired = true,
            )
        }
        val totalMinutes = diffMillis / 60_000L
        val days = totalMinutes / (24L * 60L)
        val hours = (totalMinutes % (24L * 60L)) / 60L
        val minutes = totalMinutes % 60L

        val label = when {
            days >= 1L -> "剩 ${days}天 %02d时".format(hours)
            hours >= 1L -> "仅剩 ${hours}时%02d分".format(minutes)
            minutes >= 1L -> "仅剩 ${minutes}分钟"
            else -> "即将截止"
        }
        return DealExpiryCountdown(
            label = label,
            isUrgent = diffMillis <= 24L * 3_600_000L,
            isExpired = false,
        )
    }
}
