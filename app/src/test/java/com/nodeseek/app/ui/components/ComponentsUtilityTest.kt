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
    fun `resolvePulseTopicCardMetrics scales fontScale and cardScale independently across 60 to 130 percent without clipping`() {
        val clampedLow = resolvePulseTopicCardMetrics(fontScale = 0.30f, cardScale = 0.30f)
        val at60 = resolvePulseTopicCardMetrics(fontScale = 0.60f, cardScale = 0.60f)
        assertEquals(at60, clampedLow)
        assertEquals(0.60f, at60.fontScale, 0.0001f)
        assertEquals(0.60f, at60.cardScale, 0.0001f)

        val clampedHigh = resolvePulseTopicCardMetrics(fontScale = 1.80f, cardScale = 1.80f)
        val at130 = resolvePulseTopicCardMetrics(fontScale = 1.30f, cardScale = 1.30f)
        assertEquals(at130, clampedHigh)
        assertEquals(1.30f, at130.fontScale, 0.0001f)
        assertEquals(1.30f, at130.cardScale, 0.0001f)

        // Default is 90% for both (matching fixed 90% baseline on non-homepage screens)
        val defaultMetrics = resolvePulseTopicCardMetrics()
        assertEquals(0.90f, defaultMetrics.fontScale, 0.0001f)
        assertEquals(0.90f, defaultMetrics.cardScale, 0.0001f)

        // 1. Axis independence: changing cardScale while fontScale=0.90f keeps ALL font sizes strictly identical
        val font90Card60 = resolvePulseTopicCardMetrics(fontScale = 0.90f, cardScale = 0.60f)
        val font90Card120 = resolvePulseTopicCardMetrics(fontScale = 0.90f, cardScale = 1.20f)
        assertEquals(font90Card60.titleFontSizeSp, font90Card120.titleFontSizeSp, 0.0001f)
        assertEquals(font90Card60.titleLineHeightSp, font90Card120.titleLineHeightSp, 0.0001f)
        assertEquals(font90Card60.authorFontSizeSp, font90Card120.authorFontSizeSp, 0.0001f)
        assertEquals(font90Card60.timeFontSizeSp, font90Card120.timeFontSizeSp, 0.0001f)
        assertEquals(font90Card60.footerMetricFontSizeSp, font90Card120.footerMetricFontSizeSp, 0.0001f)
        assertEquals(font90Card60.footerPillFontSizeSp, font90Card120.footerPillFontSizeSp, 0.0001f)
        assertTrue(font90Card60.innerVerticalPadding < font90Card120.innerVerticalPadding)
        assertTrue(font90Card60.outerVerticalPadding < font90Card120.outerVerticalPadding)
        assertTrue(font90Card60.headerToTitleSpacing < font90Card120.headerToTitleSpacing)
        assertTrue(font90Card60.estimatedHeightDp(2) < font90Card120.estimatedHeightDp(2))

        // 2. Axis independence: changing fontScale while cardScale=0.90f keeps card paddings, corner radius, and spacers identical
        val font60Card90 = resolvePulseTopicCardMetrics(fontScale = 0.60f, cardScale = 0.90f)
        val font120Card90 = resolvePulseTopicCardMetrics(fontScale = 1.20f, cardScale = 0.90f)
        assertEquals(font60Card90.outerVerticalPadding, font120Card90.outerVerticalPadding)
        assertEquals(font60Card90.innerVerticalPadding, font120Card90.innerVerticalPadding)
        assertEquals(font60Card90.innerHorizontalPadding, font120Card90.innerHorizontalPadding)
        assertEquals(font60Card90.cardCornerRadius, font120Card90.cardCornerRadius)
        assertEquals(font60Card90.headerToTitleSpacing, font120Card90.headerToTitleSpacing)
        assertEquals(font60Card90.titleToFooterSpacing, font120Card90.titleToFooterSpacing)
        assertTrue(font60Card90.titleFontSizeSp < font120Card90.titleFontSizeSp)

        // 3. Cross-scale safety & alignment across the full 2D grid (including 130% font + 60% compact card)
        val scaleStops = listOf(0.60f, 0.75f, 0.90f, 1.00f, 1.20f, 1.30f)
        for (f in scaleStops) {
            for (c in scaleStops) {
                val m = resolvePulseTopicCardMetrics(fontScale = f, cardScale = c)
                assertEquals(14f, m.outerHorizontalPadding.value, 0.001f)
                val titleRatio = m.titleLineHeightSp / m.titleFontSizeSp
                assertTrue("Title line-height ratio $titleRatio out of balanced range", titleRatio in 1.30f..1.45f)
                // Row 1 and footer pills never clip text even when fontScale >> cardScale
                assertTrue(m.authorRowMinHeight.value >= m.authorLineHeightSp + 1.0f)
                assertTrue(m.footerPillMinHeight.value >= m.footerPillLineHeightSp + 1.0f)
                // Bookmark touch box stays centered on Row 1 and symmetrical with avatar inset
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
        }

        // 4. Deep shrink beyond 85%: 60% compact mode achieves dramatic space savings (~67dp vs ~128dp at 100%)
        val at100 = resolvePulseTopicCardMetrics(fontScale = 1.00f, cardScale = 1.00f)
        assertTrue("60% 2-line card should be <= 70dp (was ${at60.estimatedHeightDp(2)})", at60.estimatedHeightDp(2) <= 70f)
        assertTrue("100% 2-line card should be >= 125dp (was ${at100.estimatedHeightDp(2)})", at100.estimatedHeightDp(2) >= 125f)
    }
}
