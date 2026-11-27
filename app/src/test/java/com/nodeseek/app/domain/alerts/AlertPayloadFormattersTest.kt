package com.nodeseek.app.domain.alerts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPayloadFormattersTest {

    private val offlineEvent = ForwardableAlertEvent.ProbeOffline(
        panelName = "主控探针",
        serverName = "DMIT-LAX",
        probeKey = "p1:10",
        flagEmoji = "🇺🇸",
        occurredAtEpochMillis = 1_760_000_000_000L,
    )
    private val recoveredEvent = ForwardableAlertEvent.ProbeRecovered(
        panelName = "主控探针",
        serverName = "DMIT-LAX",
        probeKey = "p1:10",
        flagEmoji = "🇺🇸",
        occurredAtEpochMillis = 1_760_000_060_000L,
    )
    private val trafficEvent = ForwardableAlertEvent.TrafficThreshold(
        panelName = "主控探针",
        serverName = "JP-Edge",
        probeKey = "p1:11",
        flagEmoji = "🇯🇵",
        usedBytes = 850L * 1024L * 1024L * 1024L,
        limitBytes = 1000L * 1024L * 1024L * 1024L,
        usageRatio = 0.85,
        nextResetDateIso = "2026-12-01",
        exhaustionForecastText = "约 3 天后耗尽",
        occurredAtEpochMillis = 1_760_000_120_000L,
    )
    private val restockEvent = ForwardableAlertEvent.RestockAvailable(
        watchId = "w1",
        watchTitle = "RackNerd 2C2G",
        resolvedOrderUrl = "https://my.racknerd.com/cart.php?a=add&pid=99",
        priceHint = "$10.99/yr",
        panelTypeLabel = "WHMCS",
        evidenceSummary = "Add to Cart visible",
        occurredAtEpochMillis = 1_760_000_180_000L,
    )
    private val radarKeywordEvent = ForwardableAlertEvent.RadarKeywordHit(
        topicId = 99881L,
        topicTitle = "[出] DMIT LAX.Pro.Tiny",
        matchedKeyword = "DMIT",
        authorName = "seller01",
        categoryName = "交易",
        summarySnippet = "带原油出",
        occurredAtEpochMillis = 1_760_000_240_000L,
    )
    private val radarReplyEvent = ForwardableAlertEvent.RadarTrackedReply(
        topicId = 99881L,
        topicTitle = "[出] DMIT LAX.Pro.Tiny",
        targetFloor = 12,
        newReplyCount = 2,
        actorName = "buyer02",
        categoryName = "交易",
        summarySnippet = "PM 了",
        occurredAtEpochMillis = 1_760_000_300_000L,
    )

    @Test
    fun `URL and Telegram token validation rejects non-http schemes, whitespace, and path traversal`() {
        assertTrue(AlertUrlValidator.isValidHttpUrl("https://api.day.app"))
        assertTrue(AlertUrlValidator.isValidHttpUrl("http://192.168.1.5:8080/hook"))
        assertFalse(AlertUrlValidator.isValidHttpUrl(""))
        assertFalse(AlertUrlValidator.isValidHttpUrl("ftp://example.com/file"))
        assertFalse(AlertUrlValidator.isValidHttpUrl("javascript:alert(1)"))
        assertFalse(AlertUrlValidator.isValidHttpUrl("https://example.com/bad url"))
        assertFalse(AlertUrlValidator.isValidHttpUrl("https://"))

        assertTrue(AlertUrlValidator.isValidTelegramBotToken("123456:ABC-DEF_xyz"))
        assertFalse(AlertUrlValidator.isValidTelegramBotToken("123456:bad/path"))
        assertFalse(AlertUrlValidator.isValidTelegramBotToken("123456:bad?q=1"))
        assertFalse(AlertUrlValidator.isValidTelegramBotToken("123456:bad\\path"))
        assertFalse(AlertUrlValidator.isValidTelegramBotToken("123456:bad%2fpath"))
        assertFalse(AlertUrlValidator.isValidTelegramBotToken(":missing_id"))
        assertFalse(AlertUrlValidator.isValidTelegramBotToken("123456:"))
        assertFalse(AlertUrlValidator.isValidTelegramBotToken("123456:extra:colon"))
        assertFalse(AlertUrlValidator.isValidTelegramBotToken("notoken_no_colon"))
    }

    @Test
    fun `TelegramBotFormatter builds valid Bot API URL, clamps long text, and encodes JSON cleanly`() {
        val cfg = TelegramForwardConfig(
            enabled = true,
            botToken = "123456:ABC_DEF",
            chatId = "-100998877",
            disableNotification = true,
        )
        val req = TelegramBotFormatter.buildRequest(cfg, trafficEvent)
        assertNotNull(req)
        assertEquals("https://api.telegram.org/bot123456:ABC_DEF/sendMessage", req!!.url)
        val obj = Json.parseToJsonElement(req.jsonBody).jsonObject
        assertEquals("-100998877", obj["chat_id"]?.jsonPrimitive?.content)
        assertEquals(true, obj["disable_web_page_preview"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, obj["disable_notification"]?.jsonPrimitive?.booleanOrNull)
        val text = obj["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("85%"))
        assertTrue(text.contains("JP-Edge"))

        val hugeEvent = radarKeywordEvent.copy(summarySnippet = "X".repeat(6_000))
        val clampedReq = TelegramBotFormatter.buildRequest(cfg, hugeEvent)!!
        val clampedText = Json.parseToJsonElement(clampedReq.jsonBody).jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(clampedText.length <= 4096)

        assertNull(TelegramBotFormatter.buildRequest(cfg.copy(enabled = false), offlineEvent))
        assertNull(TelegramBotFormatter.buildRequest(cfg.copy(botToken = "bad/token"), offlineEvent))
    }

    @Test
    fun `BarkFormatter builds POST push JSON with optional target URL and archive flag`() {
        val cfg = BarkForwardConfig(
            enabled = true,
            serverUrl = "https://bark.example.com/",
            deviceKey = "my_device_key",
            sound = "bell",
            group = "NodeSeek",
            isArchive = true,
        )
        val req = BarkFormatter.buildRequest(cfg, restockEvent)
        assertNotNull(req)
        assertEquals("https://bark.example.com/push", req!!.url)
        val obj = Json.parseToJsonElement(req.jsonBody).jsonObject
        assertEquals("my_device_key", obj["device_key"]?.jsonPrimitive?.content)
        assertEquals("NodeSeek", obj["group"]?.jsonPrimitive?.content)
        assertEquals(1, obj["isArchive"]?.jsonPrimitive?.intOrNull)
        assertEquals("bell", obj["sound"]?.jsonPrimitive?.content)
        assertEquals("https://my.racknerd.com/cart.php?a=add&pid=99", obj["url"]?.jsonPrimitive?.content)
        assertTrue(obj["title"]!!.jsonPrimitive.content.contains("RackNerd 2C2G"))
    }

    @Test
    fun `WebhookFormatter builds structured JSON, sanitizes headers, and computes HMAC-SHA256 signature`() {
        val cfg = WebhookForwardConfig(
            enabled = true,
            url = "https://hooks.example.com/alerts",
            secretHeaderName = "X-Custom-Token",
            secretValue = "s3cr3t\r\nInjected",
            customHeaders = mapOf("X-Env" to "prod"),
        )
        val allEvents = listOf(
            offlineEvent,
            recoveredEvent,
            trafficEvent,
            restockEvent,
            radarKeywordEvent,
            radarReplyEvent,
        )
        allEvents.forEach { event ->
            val req = WebhookFormatter.buildRequest(cfg, event)
            assertNotNull("Expected request for ${event.category}", req)
            assertEquals("https://hooks.example.com/alerts", req!!.url)
            assertEquals("prod", req.headers["X-Env"])
            assertEquals("s3cr3tInjected", req.headers["X-Custom-Token"])
            val expectedSig = "sha256=" + WebhookFormatter.hmacSha256Hex("s3cr3tInjected", req.jsonBody)
            assertEquals(expectedSig, req.headers[WebhookFormatter.HEADER_SIGNATURE])

            val obj = Json.parseToJsonElement(req.jsonBody).jsonObject
            assertEquals(event.category.name, obj["category"]?.jsonPrimitive?.content)
            assertEquals(event.occurredAtEpochMillis, obj["timestamp"]?.jsonPrimitive?.longOrNull)
            assertTrue(obj["details"]?.jsonObject?.isNotEmpty() == true)
        }

        assertNull(WebhookFormatter.buildRequest(cfg.copy(url = "file:///etc/passwd"), offlineEvent))
    }

    @Test
    fun `AlertUrlValidator and WebhookFormatter enforce RFC 7230 header names against CRLF colon and space injection`() {
        assertTrue(AlertUrlValidator.isValidHeaderName("X-Custom-Token_1"))
        assertFalse(AlertUrlValidator.isValidHeaderName(""))
        assertFalse(AlertUrlValidator.isValidHeaderName("X-Bad: header"))
        assertFalse(AlertUrlValidator.isValidHeaderName("X-Space Name"))
        assertFalse(AlertUrlValidator.isValidHeaderName("X-Line\nBreak"))

        assertEquals("X-SigEvil", AlertUrlValidator.sanitizeHeaderName("X-Sig\r\nEvil"))
        assertEquals("X-AB", AlertUrlValidator.sanitizeHeaderName("X-A\nB"))
        assertNull(AlertUrlValidator.sanitizeHeaderName("X-A\r\nEvilKey: foo"))
        assertNull(AlertUrlValidator.sanitizeHeaderName("X-Sig\r\nEvil: 1"))

        val cfg = WebhookForwardConfig(
            enabled = true,
            url = "https://hooks.example.com/alerts",
            secretHeaderName = "X-Sig\r\nEvil: 1",
            secretValue = "sec\r\nInjected: 2",
            customHeaders = mapOf(
                "X-A\r\nEvilKey: foo" to "bar",
                "X-A\nB" to "val\r\nX: y",
            ),
        )
        val req = WebhookFormatter.buildRequest(cfg, offlineEvent)!!
        assertFalse("Injected colon/space custom header must be rejected", req.headers.keys.any { it.contains("EvilKey") || it.contains(":") || it.contains(" ") })
        assertEquals("valX: y", req.headers["X-AB"])
        assertEquals("secInjected: 2", req.headers[WebhookForwardConfig.DEFAULT_SECRET_HEADER])
        assertNull(req.headers["X-SigEvil: 1"])
    }

    @Test
    fun `AlertUrlValidator blocks cloud metadata always and blocks loopback private hosts when SSRF strict`() {
        // Cloud metadata & unspecified ALWAYS blocked (even with defaults)
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://169.254.169.254/latest/meta-data/"))
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://metadata.google.internal/computeMetadata/v1/"))
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://100.100.100.200/latest/meta-data/"))
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://0.0.0.0:8080/hook"))
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://[::ffff:169.254.169.254]/latest"))

        // Production mode (allowLoopback = false) blocks localhost / 127.0.0.1 / [::1] / obfuscated loopback
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://127.0.0.1:8080/hook", allowLoopback = false))
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://localhost:8080/hook", allowLoopback = false))
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://[::1]:8080/hook", allowLoopback = false))
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://0x7f000001/hook", allowLoopback = false))
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://2130706433/hook", allowLoopback = false))

        // Private network guard blocks RFC1918 & IPv6 ULA when allowPrivateNetwork = false
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://192.168.1.5:8080/hook", allowLoopback = false, allowPrivateNetwork = false))
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://10.0.0.1/hook", allowLoopback = false, allowPrivateNetwork = false))
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://[fc00::1]/hook", allowLoopback = false, allowPrivateNetwork = false))

        // Public HTTPS remains valid under strictest settings, and Telegram path-traversal token rejected
        assertTrue(AlertUrlValidator.isValidHttpUrl("https://hooks.example.com/alerts", allowLoopback = false, allowPrivateNetwork = false))
        assertFalse(AlertUrlValidator.isValidTelegramBotToken("12345:abc/../../sendMessage"))
    }

    @Test
    fun `AlertUrlValidator blocks IPv4-translated NAT64 6to4 and Teredo embedded loopback metadata and private targets without breaking public IPv6`() {
        // IPv4-translated (::ffff:0:0/96)
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://[::ffff:0:127.0.0.1]/hook", allowLoopback = false, allowPrivateNetwork = false))
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://[::ffff:0:127.0.0.1]/hook", allowLoopback = false, allowPrivateNetwork = true))
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://[::ffff:0:169.254.169.254]/latest"))

        // NAT64 Well-Known Prefix (64:ff9b::/96)
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://[64:ff9b::127.0.0.1]/hook", allowLoopback = false, allowPrivateNetwork = false))
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://[64:ff9b::127.0.0.1]/hook", allowLoopback = false, allowPrivateNetwork = true))
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://[64:ff9b::169.254.169.254]/latest"))
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://[64:ff9b::a9fe:a9fe]/latest"))

        // NAT64 Local-Use Prefix (64:ff9b:1::/48)
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://[64:ff9b:1::127.0.0.1]/hook", allowLoopback = false, allowPrivateNetwork = true))
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://[64:ff9b:1::169.254.169.254]/latest"))
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://[64:ff9b:1::1]/hook", allowLoopback = false, allowPrivateNetwork = false))

        // 6to4 (2002::/16)
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://[2002:7f00:1::]/hook", allowLoopback = false, allowPrivateNetwork = false))
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://[2002:7f00:1::]/hook", allowLoopback = false, allowPrivateNetwork = true))
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://[2002:a9fe:a9fe::]/latest"))
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://[2002:0a00:0001::]/hook", allowLoopback = false, allowPrivateNetwork = false))

        // Teredo (2001:0000::/32): server=127.0.0.1
        assertFalse(AlertUrlValidator.isValidHttpUrl("http://[2001:0:7f00:1::]/hook", allowLoopback = false, allowPrivateNetwork = false))

        // Valid public IPv6 & public transition addresses MUST remain allowed under strictest settings
        assertTrue(AlertUrlValidator.isValidHttpUrl("http://[2001:4860:4860::8888]/hook", allowLoopback = false, allowPrivateNetwork = false))
        assertTrue(AlertUrlValidator.isValidHttpUrl("http://[2606:4700:4700::1111]/hook", allowLoopback = false, allowPrivateNetwork = false))
        assertTrue(AlertUrlValidator.isValidHttpUrl("http://[2002:0101:0101::]/hook", allowLoopback = false, allowPrivateNetwork = false))
        assertTrue(AlertUrlValidator.isValidHttpUrl("http://[64:ff9b::8.8.8.8]/hook", allowLoopback = false, allowPrivateNetwork = false))
    }

    @Test
    fun `AlertUrlValidator blocks NAT64 64_ff9b_1 slash-56 and slash-64 loopback and IMDS under production flags while allowing public IPv4`() {
        // Production DefaultAppContainer flags: allowLoopback = false, allowPrivateNetwork = true
        // /64 -> 169.254.169.254 (Cloud IMDS)
        assertFalse(
            AlertUrlValidator.isValidHttpUrl(
                "http://[64:ff9b:1:aaaa:00a9:fea9:fe00::]/latest",
                allowLoopback = false,
                allowPrivateNetwork = true,
            ),
        )
        // /56 -> 127.0.0.1 (Loopback)
        assertFalse(
            AlertUrlValidator.isValidHttpUrl(
                "http://[64:ff9b:1:aa7f:0000:0100::]/hook",
                allowLoopback = false,
                allowPrivateNetwork = true,
            ),
        )
        // /64 -> 127.0.0.1 (Loopback)
        assertFalse(
            AlertUrlValidator.isValidHttpUrl(
                "http://[64:ff9b:1:aaaa:007f:0000:0100::]/hook",
                allowLoopback = false,
                allowPrivateNetwork = true,
            ),
        )
        // /48 -> 127.0.0.1 (Loopback)
        assertFalse(
            AlertUrlValidator.isValidHttpUrl(
                "http://[64:ff9b:1:7f00:0000:0001::]/hook",
                allowLoopback = false,
                allowPrivateNetwork = true,
            ),
        )
        // Public IPv4 inside 64:ff9b:1::/48 remains allowed when allowPrivateNetwork = true
        assertTrue(
            AlertUrlValidator.isValidHttpUrl(
                "http://[64:ff9b:1::8.8.8.8]/hook",
                allowLoopback = false,
                allowPrivateNetwork = true,
            ),
        )
        assertTrue(
            AlertUrlValidator.isValidHttpUrl(
                "http://[64:ff9b:1:aaaa:0008:0808:0800::]/hook",
                allowLoopback = false,
                allowPrivateNetwork = true,
            ),
        )
    }

    @Test
    fun `AlertUrlValidator blocks non-zero-prefix slash-96 NAT64 under 64_ff9b_1 while allowing public slash-96 and zero-padded slash-64`() {
        // Production DefaultAppContainer flags: allowLoopback = false, allowPrivateNetwork = true
        // Non-zero byte[8] (/96 subnet with dead in hextet 4): 64:ff9b:1:0:dead::169.254.169.254
        assertFalse(
            AlertUrlValidator.isValidHttpUrl(
                "http://[64:ff9b:1:0:dead::169.254.169.254]/latest",
                allowLoopback = false,
                allowPrivateNetwork = true,
            ),
        )
        // Zero byte[8] with non-zero bytes in 6..11 (/96 subnet): 64:ff9b:1:101:1::127.0.0.1
        assertFalse(
            AlertUrlValidator.isValidHttpUrl(
                "http://[64:ff9b:1:101:1::127.0.0.1]/hook",
                allowLoopback = false,
                allowPrivateNetwork = true,
            ),
        )
        // Public IPv4 in non-zero-prefix /96 remains allowed
        assertTrue(
            AlertUrlValidator.isValidHttpUrl(
                "http://[64:ff9b:1:0:dead::8.8.8.8]/hook",
                allowLoopback = false,
                allowPrivateNetwork = true,
            ),
        )
        // Zero-padded /64 public IPv4 ending in .0 or .127 must NOT trigger 0.0.0.0/8 or 127.0.0.0/8 false positives
        assertTrue(
            AlertUrlValidator.isValidHttpUrl(
                "http://[64:ff9b:1:aaaa:0001:0203:0000::]/hook",
                allowLoopback = false,
                allowPrivateNetwork = true,
            ),
        )
        assertTrue(
            AlertUrlValidator.isValidHttpUrl(
                "http://[64:ff9b:1:aaaa:0001:0203:7f00::]/hook",
                allowLoopback = false,
                allowPrivateNetwork = true,
            ),
        )
    }
}
