package com.nodeseek.app.domain.mail

import com.nodeseek.app.data.mail.SeekMailHtmlSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekMailSanitizerAndOtpTest {

    @Test
    fun `resolveCode prioritizes non-blank serverCode from seek_li API over subject and body`() {
        val code = VerificationCodeExtractor.resolveCode(
            serverCode = "  908172  ",
            subject = "Your verification code is 111222",
            bodyText = "您的验证码为：333444，5分钟内有效",
        )
        assertEquals("908172", code)

        val fallback = VerificationCodeExtractor.resolveCode(
            serverCode = "   ",
            subject = "登录通知",
            bodyText = "您的验证码为：839201，5分钟内有效",
        )
        assertEquals("839201", fallback)
    }

    @Test
    fun `resolveCode extracts 4 to 8 digit and alphanumeric codes from Chinese and English messages`() {
        assertEquals(
            "839201",
            VerificationCodeExtractor.resolveCode(
                serverCode = null,
                subject = "账号安全提醒",
                bodyText = "您好，您的验证码为：839201，5分钟内有效，请勿转发。",
            ),
        )

        assertEquals(
            "4829",
            VerificationCodeExtractor.resolveCode(
                serverCode = null,
                subject = "【NodeSeek】安全码 4829",
                bodyText = "请使用以上安全码完成校验。",
            ),
        )

        assertEquals(
            "739104",
            VerificationCodeExtractor.resolveCode(
                serverCode = null,
                subject = "Sign-in request",
                bodyText = "Your verification code is 739104. It expires in 10 minutes.",
            ),
        )

        assertEquals(
            "G7K9P2",
            VerificationCodeExtractor.resolveCode(
                serverCode = null,
                subject = "Account security",
                bodyText = "One-time passcode: G7K9P2",
            ),
        )

        assertEquals(
            "AB12CD34",
            VerificationCodeExtractor.resolveCode(
                serverCode = null,
                subject = "Security check",
                bodyText = "Confirmation code: AB12CD34",
            ),
        )

        // Subject supplies label and body begins directly with the code.
        assertEquals(
            "519283",
            VerificationCodeExtractor.resolveCode(
                serverCode = null,
                subject = "【Seek】登录验证码",
                bodyText = ": 519283 请尽快输入。",
            ),
        )

        // Direct-labeled 4-digit year-like number should still extract when explicitly labeled.
        assertEquals(
            "2026",
            VerificationCodeExtractor.resolveCode(
                serverCode = null,
                subject = "",
                bodyText = "验证码：2026",
            ),
        )

        // Direct-labeled 6-digit code beats earlier unlabeled order/reference numbers.
        assertEquals(
            "445566",
            VerificationCodeExtractor.resolveCode(
                serverCode = null,
                subject = "",
                bodyText = "Order 99887 updated. Your verification code is 445566.",
            ),
        )

        // Direct-labeled segmented OTPs (hyphen, space, or single-letter prefix) are preserved & normalized.
        assertEquals(
            "482910",
            VerificationCodeExtractor.resolveCode(
                serverCode = null,
                subject = "GitHub Login",
                bodyText = "Your verification code: 482-910",
            ),
        )
        assertEquals(
            "482910",
            VerificationCodeExtractor.resolveCode(
                serverCode = null,
                subject = "Apple ID",
                bodyText = "您的验证码为：482 910，请勿泄露",
            ),
        )
        assertEquals(
            "48291038",
            VerificationCodeExtractor.resolveCode(
                serverCode = null,
                subject = "Security",
                bodyText = "One-time passcode: 4829-1038",
            ),
        )
        assertEquals(
            "123456",
            VerificationCodeExtractor.resolveCode(
                serverCode = null,
                subject = "Google",
                bodyText = "Verification code is G-123456",
            ),
        )
    }

    @Test
    fun `resolveCode rejects years, hex colors, URLs, ports, units, and plain words`() {
        assertNull(
            VerificationCodeExtractor.resolveCode(
                serverCode = null,
                subject = "Monthly newsletter",
                bodyText = "Copyright 2026 Seek Mail. All rights reserved.",
            ),
        )

        assertNull(
            VerificationCodeExtractor.resolveCode(
                serverCode = null,
                subject = "Verification code notice",
                bodyText = "Copyright 2026 Seek Mail. Please protect your verification code.",
            ),
        )

        assertNull(
            VerificationCodeExtractor.resolveCode(
                serverCode = null,
                subject = "Theme update",
                bodyText = "Your verification code badge color is #10b981 and #1A2B3C.",
            ),
        )

        assertNull(
            VerificationCodeExtractor.resolveCode(
                serverCode = null,
                subject = "Verify email",
                bodyText = "Verification code portal: https://seek.li:8443/path/123456",
            ),
        )

        assertNull(
            VerificationCodeExtractor.resolveCode(
                serverCode = null,
                subject = "Verification code format",
                bodyText = "Your verification code template uses CODE HTML HTTP SMTP MIME.",
            ),
        )

        assertNull(
            VerificationCodeExtractor.resolveCode(
                serverCode = null,
                subject = "Verification code specs",
                bodyText = "Verification code encoding: UTF8 BASE64 SHA256 HTML5 TLS13.",
            ),
        )

        assertNull(
            VerificationCodeExtractor.resolveCode(
                serverCode = null,
                subject = "Verification code timing",
                bodyText = "Verification code timeout 3600 seconds on 2026-11-16 at 12:34:56 uuid 550e8400-e29b-41d4-a716-446655440000 for user1234@seek.li.",
            ),
        )
    }

    @Test
    fun `sanitize strips dangerous tags, event handlers, and unsafe URI schemes while keeping safe formatting`() {
        val rawHtml = """
            <!DOCTYPE html>
            <html>
              <head>
                <meta http-equiv="refresh" content="0;url=https://evil.example/redirect">
                <base href="https://evil.example/">
                <link rel="stylesheet" href="https://evil.example/track.css">
                <script>alert('xss')</script>
              </head>
              <body>
                <p onclick="stealCookies()" onmouseover="evil()">Hello <strong>Seek</strong></p>
                <iframe src="https://evil.example/frame"></iframe>
                <object data="https://evil.example/obj.swf"></object>
                <embed src="https://evil.example/embed.swf">
                <form action="https://evil.example/phish">
                  <input type="text" name="otp">
                  <button type="submit">Submit</button>
                </form>
                <table><tr><td>Invoice #42</td><td>Paid</td></tr></table>
                <a href="javascript:alert(1)">Bad JS</a>
                <a href="file:///etc/passwd">Bad File</a>
                <a href="intent://scan/#Intent;end">Bad Intent</a>
                <a href="data:text/html,<script>alert(1)</script>">Bad Data</a>
                <a href="https://seek.li/verify?token=abc">Verify Account</a>
                <a href="mailto:support@seek.li">Support</a>
                <img src="cid:inline-logo@seek.li" onerror="alert(2)" alt="Logo">
                <div style="width: expression(alert(1))">Styled</div>
              </body>
            </html>
        """.trimIndent()

        val sanitized = SeekMailHtmlSanitizer.sanitize(rawHtml)
        val html = sanitized.bodyFragmentHtml
        val lower = html.lowercase()

        assertFalse(lower.contains("<script"))
        assertFalse(lower.contains("<iframe"))
        assertFalse(lower.contains("<object"))
        assertFalse(lower.contains("<embed"))
        assertFalse(lower.contains("<form"))
        assertFalse(lower.contains("<input"))
        assertFalse(lower.contains("<button"))
        assertFalse(lower.contains("<base"))
        assertFalse(lower.contains("<link"))
        assertFalse(lower.contains("http-equiv"))
        assertFalse(lower.contains("onclick"))
        assertFalse(lower.contains("onmouseover"))
        assertFalse(lower.contains("onerror"))
        assertFalse(lower.contains("javascript:"))
        assertFalse(lower.contains("file:"))
        assertFalse(lower.contains("intent:"))
        assertFalse(lower.contains("data:text/html"))
        assertFalse(lower.contains("expression("))

        assertTrue(lower.contains("<p"))
        assertTrue(html.contains("<strong>Seek</strong>"))
        assertTrue(lower.contains("<table>"))
        assertTrue(html.contains("Invoice #42"))
        assertTrue(html.contains("https://seek.li/verify?token=abc"))
        assertTrue(html.contains("mailto:support@seek.li"))
        assertTrue(html.contains("cid:inline-logo@seek.li"))
        assertFalse(sanitized.hasRemoteImages)
    }

    @Test
    fun `sanitize sets hasRemoteImages accurately for remote URLs vs inline text, cid, and data images`() {
        val textOnly = SeekMailHtmlSanitizer.sanitize("<p>Plain inline greeting</p>")
        assertFalse(textOnly.hasRemoteImages)

        val cidImage = SeekMailHtmlSanitizer.sanitize("""<p>Hi</p><img src="cid:inline-image-1">""")
        assertFalse(cidImage.hasRemoteImages)

        val dataImage = SeekMailHtmlSanitizer.sanitize(
            """<img src="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=">""",
        )
        assertFalse(dataImage.hasRemoteImages)

        val remoteHttps = SeekMailHtmlSanitizer.sanitize(
            """<p>Receipt</p><img src="https://tracker.example/pixel.png" width="1" height="1">""",
        )
        assertTrue(remoteHttps.hasRemoteImages)

        val remoteHttp = SeekMailHtmlSanitizer.sanitize(
            """<img src="http://cdn.example/logo.png">""",
        )
        assertTrue(remoteHttp.hasRemoteImages)

        val remoteCssBg = SeekMailHtmlSanitizer.sanitize(
            """<div style="background-image: url('https://tracker.example/bg.png')">Hello</div>""",
        )
        assertTrue(remoteCssBg.hasRemoteImages)
    }

    @Test
    fun `sanitize enforces Jsoup Safelist whitelist, blocks CSS escapes and fixed positioning, and extractPlainTextFallback strips markup`() {
        val trickyHtml = """
            <svg onload="alert(1)"><foreignObject><script>evil()</script></foreignObject></svg>
            <style>@import url('https://evil.example/leak.css'); body { display: none; }</style>
            <audio src="https://tracker.example/ping.mp3" autoplay></audio>
            <video poster="https://tracker.example/poster.png"></video>
            <dialog open>Phishing modal</dialog>
            <div style="position: fixed; top: 0; left: 0;">Overlay</div>
            <div style="background: u\72l('javascript:alert(1)')">Escaped JS CSS</div>
            <div style="background-image: content://com.android.contacts/data">Content URI</div>
            <p style="color: #222; content: '';">Safe paragraph</p>
        """.trimIndent()

        val sanitized = SeekMailHtmlSanitizer.sanitize(trickyHtml)
        val lower = sanitized.bodyFragmentHtml.lowercase()

        assertFalse(lower.contains("<svg"))
        assertFalse(lower.contains("<style"))
        assertFalse(lower.contains("<audio"))
        assertFalse(lower.contains("<video"))
        assertFalse(lower.contains("<dialog"))
        assertFalse(lower.contains("position: fixed"))
        assertFalse(lower.contains("javascript:"))
        assertFalse(lower.contains("content://"))
        assertTrue(lower.contains("safe paragraph"))
        assertTrue(lower.contains("color: #222"))

        // Escaped remote url() is decoded and flagged as hasRemoteImages = true
        val escapedRemote = SeekMailHtmlSanitizer.sanitize(
            """<div style="background: u\72l('https://tracker.example/escaped.png')">Track</div>""",
        )
        assertTrue(escapedRemote.hasRemoteImages)

        val plain = SeekMailHtmlSanitizer.extractPlainTextFallback(
            "<html><head><style>body{color:red}</style><script>evil()</script></head><body><p>Line 1</p><p>Line 2</p></body></html>",
        )
        assertTrue(plain.contains("Line 1"))
        assertTrue(plain.contains("Line 2"))
        assertFalse(plain.contains("evil()"))
        assertFalse(plain.contains("color:red"))
    }

    @Test
    fun `parseScoreDetails parses auth and final telemetry and returns null on blank or malformed JSON`() {
        val rawJson = """
            {
              "auth": {
                "spf": "pass",
                "dkim": "pass",
                "dmarc": "pass",
                "fromMismatch": false,
                "total": 40
              },
              "final": {
                "score": 12,
                "decision": "inbox",
                "aiUpgrade": false
              }
            }
        """.trimIndent()

        val telemetry = SeekMailHtmlSanitizer.parseScoreDetails(rawJson)
        assertNotNull(telemetry)
        assertEquals("pass", telemetry!!.spf)
        assertEquals("pass", telemetry.dkim)
        assertEquals("pass", telemetry.dmarc)
        assertFalse(telemetry.fromMismatch)
        assertEquals(40, telemetry.authTotalScore)
        assertEquals(12, telemetry.finalScore)
        assertEquals("inbox", telemetry.decision)
        assertFalse(telemetry.aiUpgrade)

        assertNull(SeekMailHtmlSanitizer.parseScoreDetails(""))
        assertNull(SeekMailHtmlSanitizer.parseScoreDetails("   "))
        assertNull(SeekMailHtmlSanitizer.parseScoreDetails("{not-valid-json"))
        assertNull(SeekMailHtmlSanitizer.parseScoreDetails("[]"))
    }
}
