package com.nodeseek.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ComponentsUtilityTest {

    @Test
    fun `normalizeNodeSeekAssetUrl upgrades case-insensitive HTTP and rejects unsafe schemes`() {
        assertEquals("https://img.example.com/a.png", normalizeNodeSeekAssetUrl("http://img.example.com/a.png"))
        assertEquals("https://img.example.com/a.png", normalizeNodeSeekAssetUrl("HTTP://img.example.com/a.png"))
        assertEquals("https://img.example.com/a.png", normalizeNodeSeekAssetUrl("HTTPS://img.example.com/a.png"))
        assertEquals("https://cdn.nodeseek.com/1.png", normalizeNodeSeekAssetUrl("//cdn.nodeseek.com/1.png"))
        assertEquals("https://www.nodeseek.com/avatar/101.png", normalizeNodeSeekAssetUrl("/avatar/101.png"))
        assertEquals("https://www.nodeseek.com/avatar/101.png", normalizeNodeSeekAssetUrl("avatar/101.png"))

        assertNull(normalizeNodeSeekAssetUrl(""))
        assertNull(normalizeNodeSeekAssetUrl("   "))
        assertNull(normalizeNodeSeekAssetUrl("file:///etc/passwd"))
        assertNull(normalizeNodeSeekAssetUrl("content://com.nodeseek.app/secret"))
        assertNull(normalizeNodeSeekAssetUrl("javascript:alert(1)"))
        assertNull(normalizeNodeSeekAssetUrl("data:image/png;base64,abc"))
    }

    @Test
    fun `WebViewSessionSheet URL guards reject suffix spoof HTTP and non-whitelisted hosts`() {
        assertTrue(isNodeSeekHttpsUrl("https://www.nodeseek.com/signIn.html"))
        assertTrue(isNodeSeekHttpsUrl("https://nodeseek.com/"))
        assertTrue(isNodeSeekHttpsUrl("https://sub.nodeseek.com/path"))

        assertFalse(isNodeSeekHttpsUrl("http://www.nodeseek.com/signIn.html"))
        assertFalse(isNodeSeekHttpsUrl("https://evilnodeseek.com/signIn.html"))
        assertFalse(isNodeSeekHttpsUrl("https://evil.com/nodeseek.com"))
        assertFalse(isNodeSeekHttpsUrl("file:///data/local/tmp/a.html"))
        assertFalse(isNodeSeekHttpsUrl("javascript:alert(1)"))
        assertFalse(isNodeSeekHttpsUrl(""))

        assertTrue(isTrustedSessionNavigationUrl("https://www.nodeseek.com/signIn.html"))
        assertTrue(isTrustedSessionNavigationUrl("https://challenges.cloudflare.com/turnstile/v0/api.js"))
        assertTrue(isTrustedSessionNavigationUrl("https://oauth.telegram.org/auth"))
        assertTrue(isTrustedSessionNavigationUrl("https://hcaptcha.com/challenge"))

        assertFalse(isTrustedSessionNavigationUrl("https://evil.example.com/phish"))
        assertFalse(isTrustedSessionNavigationUrl("http://challenges.cloudflare.com/"))
        assertFalse(isTrustedSessionNavigationUrl("file:///etc/hosts"))
        assertFalse(isTrustedSessionNavigationUrl("intent://evil/#Intent;end"))
    }

    @Test
    fun `formatCompactCount formatBadgeCount and formatRelativeTimeChinese format deterministically`() {
        assertEquals("0", formatCompactCount(-5))
        assertEquals("999", formatCompactCount(999))
        assertEquals("1.5k", formatCompactCount(1500))
        assertEquals("2.3w", formatCompactCount(23_400))

        assertEquals("0", formatBadgeCount(-1))
        assertEquals("99", formatBadgeCount(99))
        assertEquals("99+", formatBadgeCount(100))
        assertEquals("9+", formatBadgeCount(12, maxCount = 9))

        val now = 1_760_000_000_000L // 2025-10-09T08:53:20Z
        assertEquals("刚刚", formatRelativeTimeChinese("2025-10-09T08:53:00Z", nowEpochMillis = now))
        assertEquals("5 分钟前", formatRelativeTimeChinese("2025-10-09T08:48:00Z", nowEpochMillis = now))
        assertEquals("2 小时前", formatRelativeTimeChinese("2025-10-09T06:53:00Z", nowEpochMillis = now))
        assertEquals("昨天", formatRelativeTimeChinese("2025-10-08T08:00:00Z", nowEpochMillis = now))
        assertEquals("3 天前", formatRelativeTimeChinese("2025-10-06T08:00:00Z", nowEpochMillis = now))
        assertEquals("2024-01-15", formatRelativeTimeChinese("2024-01-15T08:00:00Z", nowEpochMillis = now))
    }

    @Test
    fun `NodeSeekStickerCatalog builds ASCII asset URLs even under Arabic locale`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar"))
            val ac = NodeSeekStickerCatalog.resolve("ac01")
            val yct = NodeSeekStickerCatalog.resolve("yct001")
            val emoji = NodeSeekStickerCatalog.resolve("emoji02")

            assertEquals("https://www.nodeseek.com/static/image/sticker/ac/01.png", ac?.imageUrl)
            assertEquals("https://www.nodeseek.com/static/image/sticker/yct/001.gif", yct?.imageUrl)
            assertEquals("https://www.nodeseek.com/static/image/sticker/emoji/02.webm", emoji?.imageUrl)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
