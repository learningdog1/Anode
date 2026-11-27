package com.nodeseek.app.notifications

import com.nodeseek.app.domain.mail.OtpWatchState
import com.nodeseek.app.domain.mail.VerificationCodeExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpWatchNotificationAndReceiverTest {

    @Test
    fun `notification IDs stay strictly inside unused 90000-99999 range`() {
        assertEquals(90_100, OtpWatchNotificationHelper.ID_OTP_WATCH_ONGOING)
        assertEquals(90_101, OtpWatchNotificationHelper.ID_OTP_WATCH_TIMED_OUT)
        assertEquals(91_000, OtpWatchNotificationHelper.BASE_ID_OTP_FOUND)

        val sampleMailIds = listOf(0L, 1L, 42L, 999_999L, -123_456L, Long.MAX_VALUE, Long.MIN_VALUE)
        sampleMailIds.forEach { id ->
            val notifId = OtpWatchNotificationHelper.notificationIdForMail(id)
            assertTrue(
                "Notification ID $notifId for mailId=$id outside 91_000..98_999",
                notifId in 91_000..98_999,
            )
        }
    }

    @Test
    fun `formatting helpers produce crisp countdown, title, subtitle, and copy action label`() {
        assertEquals("02:41", OtpWatchNotificationHelper.formatRemainingCountdown(161))
        assertEquals("00:05", OtpWatchNotificationHelper.formatRemainingCountdown(5))
        assertEquals("00:00", OtpWatchNotificationHelper.formatRemainingCountdown(-3))

        assertEquals(
            "正在监听验证码 · 剩余 03:00",
            OtpWatchNotificationHelper.formatWatchingTitle(180),
        )
        assertEquals(
            "监控邮箱：otp@seek.li · 收到后将弹窗提醒",
            OtpWatchNotificationHelper.formatWatchingSubtitle("otp@seek.li"),
        )
        assertEquals(
            "收到验证码：839201",
            OtpWatchNotificationHelper.formatFoundTitle("839201"),
        )
        assertEquals(
            "一键复制 839201",
            OtpWatchNotificationHelper.formatCopyActionLabel("839201"),
        )

        val found = OtpWatchState.Found(
            code = "839201",
            mailId = 88L,
            subject = "Sign in to Cloudflare",
            fromDisplay = "Cloudflare",
            mailboxAddress = "cf@seek.li",
            foundAtMillis = 1000L,
        )
        assertEquals(
            "Cloudflare · Sign in to Cloudflare (cf@seek.li)",
            OtpWatchNotificationHelper.formatFoundSubtitle(found),
        )
    }

    @Test
    fun `VerificationCodeExtractor resolves server, segmented, Chinese, and English OTP formats`() {
        assertEquals(
            "482910",
            VerificationCodeExtractor.resolveCode(
                serverCode = null,
                subject = "Login verification",
                bodyText = "Your verification code is 482-910.",
            ),
        )
        assertEquals(
            "123456",
            VerificationCodeExtractor.resolveCode(
                serverCode = null,
                subject = "NodeSeek 安全验证",
                bodyText = "您的验证码：123456，5分钟内有效。",
            ),
        )
        assertEquals(
            "998877",
            VerificationCodeExtractor.resolveCode(
                serverCode = "998877",
                subject = "Code",
                bodyText = "Fallback 111222",
            ),
        )
    }

    @Test
    fun `notificationIdForMail avoids modulo 8000 periodic collisions and uses distinct mail tags`() {
        val id1 = OtpWatchNotificationHelper.notificationIdForMail(1L)
        val id8001 = OtpWatchNotificationHelper.notificationIdForMail(8_001L)
        val id16001 = OtpWatchNotificationHelper.notificationIdForMail(16_001L)
        assertTrue(id1 in 91_000..98_999)
        assertTrue(id8001 in 91_000..98_999)
        assertTrue(id16001 in 91_000..98_999)
        assertTrue(setOf(id1, id8001, id16001).size == 3)
        // Even-slot allocation prevents contentPendingIntent (notificationId + 1) collision
        assertTrue(id1 % 2 == 0)
        assertTrue(id8001 % 2 == 0)
        assertTrue(OtpWatchNotificationHelper.notificationTagForMail(1L) != OtpWatchNotificationHelper.notificationTagForMail(8_001L))
    }
}
