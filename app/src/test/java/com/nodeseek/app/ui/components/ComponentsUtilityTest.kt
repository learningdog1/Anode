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

    @Test
    fun `resolvePulseTopicCardMetrics clamps scale and compacts card height harmoniously without breaking alignment`() {
        val clampedLow = resolvePulseTopicCardMetrics(0.40f)
        val at75 = resolvePulseTopicCardMetrics(0.75f)
        assertEquals(at75, clampedLow)
        assertEquals(0.75f, at75.scale, 0.0001f)

        val clampedHigh = resolvePulseTopicCardMetrics(1.80f)
        val at130 = resolvePulseTopicCardMetrics(1.30f)
        assertEquals(at130, clampedHigh)
        assertEquals(1.30f, at130.scale, 0.0001f)

        val stops = listOf(0.75f, 0.85f, 0.90f, 1.00f, 1.15f, 1.30f).map(::resolvePulseTopicCardMetrics)
        for (i in 0 until stops.lastIndex) {
            val smaller = stops[i]
            val larger = stops[i + 1]
            assertTrue("titleFontSize should grow monotonically", smaller.titleFontSizeSp < larger.titleFontSizeSp)
            assertTrue("titleLineHeight should grow monotonically", smaller.titleLineHeightSp < larger.titleLineHeightSp)
            assertTrue("authorFontSize should grow monotonically", smaller.authorFontSizeSp < larger.authorFontSizeSp)
            assertTrue("footerMetricFontSize should grow monotonically", smaller.footerMetricFontSizeSp < larger.footerMetricFontSizeSp)
            assertTrue("innerVerticalPadding should grow monotonically", smaller.innerVerticalPadding < larger.innerVerticalPadding)
            assertTrue("outerVerticalPadding should grow monotonically", smaller.outerVerticalPadding < larger.outerVerticalPadding)
            assertTrue("avatarSize should grow monotonically", smaller.avatarSize < larger.avatarSize)
            assertTrue("footerPillMinHeight should grow monotonically", smaller.footerPillMinHeight < larger.footerPillMinHeight)
            assertTrue("estimated 2-line height should grow monotonically", smaller.estimatedHeightDp(2) < larger.estimatedHeightDp(2))
        }

        stops.forEach { m ->
            // Horizontal feed rail alignment stays consistent with top header & pinned ticker
            assertEquals(14f, m.outerHorizontalPadding.value, 0.001f)
            // Readable typography floors & balanced line-height ratios at every stop
            assertTrue("Title must stay legible (>=12sp)", m.titleFontSizeSp >= 12f)
            assertTrue("Author must stay legible (>=10.5sp)", m.authorFontSizeSp >= 10.5f)
            assertTrue("Footer pills must stay legible (>=9sp)", m.footerPillFontSizeSp >= 9f)
            val titleRatio = m.titleLineHeightSp / m.titleFontSizeSp
            assertTrue("Title line-height ratio $titleRatio out of balanced range", titleRatio in 1.30f..1.45f)
            // Bookmark touch box stays mathematically centered on Row 1 and symmetrical with avatar inset
            assertEquals(
                m.innerVerticalPadding.value * 2f + m.authorRowMinHeight.value,
                m.bookmarkTouchHeight.value,
                0.01f,
            )
            assertEquals(
                m.innerHorizontalPadding.value * 2f + m.bookmarkIconSize.value,
                m.bookmarkTouchWidth.value,
                0.01f,
            )
        }

        // Compaction effectiveness: 75% mode reduces 2-line card footprint by >25% vs 100%
        val at100 = resolvePulseTopicCardMetrics(1.00f)
        assertTrue("75% 2-line card should be <= 95dp (was ${at75.estimatedHeightDp(2)})", at75.estimatedHeightDp(2) <= 95f)
        assertTrue("100% 2-line card should be >= 125dp (was ${at100.estimatedHeightDp(2)})", at100.estimatedHeightDp(2) >= 125f)
        assertTrue(
            "75% compaction should save at least 25% vertical height vs 100%",
            at75.estimatedHeightDp(2) <= at100.estimatedHeightDp(2) * 0.75f,
        )
    }
}
