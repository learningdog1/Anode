package com.nodeseek.app.deals

import com.nodeseek.app.domain.deals.BlackFridayCountdownCalculator
import com.nodeseek.app.domain.deals.BlackFridayEventPhase
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

class BlackFridayCountdownCalculatorTest {

    @Test
    fun blackFridayAndCyberMondayDates_for2026And2027AreExact() {
        assertEquals(LocalDate.of(2026, 11, 27), BlackFridayCountdownCalculator.blackFridayDate(2026))
        assertEquals(LocalDate.of(2026, 11, 30), BlackFridayCountdownCalculator.cyberMondayDate(2026))

        assertEquals(LocalDate.of(2027, 11, 26), BlackFridayCountdownCalculator.blackFridayDate(2027))
        assertEquals(LocalDate.of(2027, 11, 29), BlackFridayCountdownCalculator.cyberMondayDate(2027))
    }

    @Test
    fun countdownFromNov19_2026ToNov27_2026_computesExactDaysHoursMinutesSeconds() {
        // 2026-11-19 00:00:00 Asia/Shanghai -> exactly 8 days to 2026-11-27 00:00:00
        val nov19Midnight = ZonedDateTime.of(2026, 11, 19, 0, 0, 0, 0, VALUATION_ZONE_ID)
        val snapMidnight = BlackFridayCountdownCalculator.compute(nov19Midnight)
        assertEquals(2026, snapMidnight.eventYear)
        assertEquals(BlackFridayEventPhase.PRE_BLACK_FRIDAY, snapMidnight.phase)
        assertEquals(8L, snapMidnight.days)
        assertEquals(0L, snapMidnight.hours)
        assertEquals(0L, snapMidnight.minutes)
        assertEquals(0L, snapMidnight.seconds)
        assertEquals("距离黑五还有 8天 00:00:00", snapMidnight.bannerHeadline)

        // 2026-11-19 22:00:39 Asia/Shanghai -> 7 days, 1 hour, 59 minutes, 21 seconds
        val nov19Evening = ZonedDateTime.of(2026, 11, 19, 22, 0, 39, 0, VALUATION_ZONE_ID)
        val snapEvening = BlackFridayCountdownCalculator.compute(nov19Evening)
        assertEquals(BlackFridayEventPhase.PRE_BLACK_FRIDAY, snapEvening.phase)
        assertEquals(7L, snapEvening.days)
        assertEquals(1L, snapEvening.hours)
        assertEquals(59L, snapEvening.minutes)
        assertEquals(21L, snapEvening.seconds)
        assertEquals("7天 01:59:21", snapEvening.formattedDayHms)
    }

    @Test
    fun countdownTransitionsAcrossBlackFridayCyberWeekendCyberMondayAndRollover() {
        // Black Friday day itself (2026-11-27 12:30:00 CST) -> 11h 30m until Nov 28 00:00
        val bfLive = BlackFridayCountdownCalculator.compute(
            ZonedDateTime.of(2026, 11, 27, 12, 30, 0, 0, VALUATION_ZONE_ID),
        )
        assertEquals(BlackFridayEventPhase.BLACK_FRIDAY_LIVE, bfLive.phase)
        assertEquals(0L, bfLive.days)
        assertEquals(11L, bfLive.hours)
        assertEquals(30L, bfLive.minutes)

        // Cyber Weekend (2026-11-28 00:00:00 CST) -> 2 days until Cyber Monday Nov 30 00:00
        val cyberWeekend = BlackFridayCountdownCalculator.compute(
            ZonedDateTime.of(2026, 11, 28, 0, 0, 0, 0, VALUATION_ZONE_ID),
        )
        assertEquals(BlackFridayEventPhase.CYBER_WEEKEND, cyberWeekend.phase)
        assertEquals(2L, cyberWeekend.days)

        // Cyber Monday live (2026-11-30 20:00:00 CST) -> 4h until Dec 1 00:00
        val cyberMonday = BlackFridayCountdownCalculator.compute(
            ZonedDateTime.of(2026, 11, 30, 20, 0, 0, 0, VALUATION_ZONE_ID),
        )
        assertEquals(BlackFridayEventPhase.CYBER_MONDAY_LIVE, cyberMonday.phase)
        assertEquals(4L, cyberMonday.hours)

        // Post Cyber Monday (2026-12-01 00:00:00 CST) -> rolls over to 2027-11-26
        val rollover = BlackFridayCountdownCalculator.compute(
            ZonedDateTime.of(2026, 12, 1, 0, 0, 0, 0, VALUATION_ZONE_ID),
        )
        assertEquals(2027, rollover.eventYear)
        assertEquals(LocalDate.of(2027, 11, 26), rollover.blackFridayDate)
        assertEquals(BlackFridayEventPhase.PRE_BLACK_FRIDAY, rollover.phase)
        assertEquals(360L, rollover.days)
    }

    @Test
    fun formatDealExpiry_handlesUrgentMultiDayExpiredAndNull() {
        val now = 1_795_096_800_000L

        assertNull(BlackFridayCountdownCalculator.formatDealExpiry(null, now))

        val urgent = BlackFridayCountdownCalculator.formatDealExpiry(now + 2L * 3_600_000L + 15L * 60_000L, now)
        assertNotNull(urgent)
        assertEquals("仅剩 2时15分", urgent!!.label)
        assertTrue(urgent.isUrgent)
        assertFalse(urgent.isExpired)

        val multiDay = BlackFridayCountdownCalculator.formatDealExpiry(now + 3L * 86_400_000L + 4L * 3_600_000L, now)
        assertEquals("剩 3天 04时", multiDay!!.label)
        assertFalse(multiDay.isUrgent)
        assertFalse(multiDay.isExpired)

        val expired = BlackFridayCountdownCalculator.formatDealExpiry(now - 1_000L, now)
        assertEquals("已截止", expired!!.label)
        assertTrue(expired.isExpired)
    }
}
