package com.nodeseek.app.ui.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekMailWebViewHtmlTest {

    @Test
    fun `buildThemedEmailHtmlDocument injects mobile viewport, responsive CSS, and normalizes wide layouts`() {
        val wideDesktopMail = """
            <style>
              body { min-width: 640px !important; }
              .wrapper { width: 650px !important; white-space: nowrap !important; }
            </style>
            <table width="640" style="width: 640px !important; min-width: 640px !important; padding: 20px 60px;">
              <tr>
                <td width="320" nowrap style="width: 320px; min-width: 300px; white-space: nowrap;">
                  <img src="https://cdn.example/banner.png" width="600" height="240" style="width: 600px !important; height: 240px !important;" />
                  <img src="https://cdn.example/icon.png" width="16" height="16" />
                </td>
                <td width="320">
                  <div style="width: 580px; min-width: 500px; padding-left: 48px;">
                    <pre>very_long_unbroken_token_1234567890_abcdefghijklmnopqrstuvwxyz</pre>
                  </div>
                </td>
              </tr>
            </table>
        """.trimIndent()

        val doc = buildThemedEmailHtmlDocument(
            sanitizedFragmentHtml = wideDesktopMail,
            isDark = true,
            purePitchAmoled = false,
            fontScale = 1.0f,
            backgroundHex = "#101417",
            surfaceContainerHex = "#181C1F",
            onSurfaceHex = "#DFE3E7",
            onSurfaceVariantHex = "#C0C7CD",
            primaryHex = "#9ACAEC",
            outlineHex = "#40484D",
        )

        // Viewport meta & zero JavaScript
        assertTrue(doc.contains("width=device-width, initial-scale=1.0"))
        assertFalse(doc.lowercase().contains("<script"))

        // Wide table and fixed desktop dimensions normalized
        assertTrue(doc.contains("""width="100%""""))
        assertFalse(doc.contains("min-width: 640px"))
        assertFalse(doc.contains("min-width: 300px"))
        assertFalse(doc.contains("min-width: 500px"))
        assertFalse(doc.contains("width: 640px"))
        assertFalse(doc.contains("width: 650px"))
        assertFalse(doc.contains("width: 580px"))
        assertFalse(doc.contains("white-space: nowrap"))
        assertFalse(doc.contains(" nowrap"))

        // Banner image fixed dims removed while small 16x16 icon preserved
        assertFalse(doc.contains("""width="600""""))
        assertFalse(doc.contains("""height="240""""))
        assertTrue(doc.contains("""width="16""""))
        assertTrue(doc.contains("""height="16""""))

        // Excessive desktop horizontal padding clamped
        assertFalse(doc.contains("padding: 20px 60px"))
        assertTrue(doc.contains("padding: 20px 16px"))
        assertFalse(doc.contains("padding-left: 48px"))
        assertTrue(doc.contains("padding-left: 16px"))

        // Trailing responsive override block present after content root
        val rootCloseIdx = doc.indexOf("</div>")
        val trailingStyleIdx = doc.indexOf("html body .np-mail-root table.np-mail-wide-table")
        assertTrue(rootCloseIdx > 0 && trailingStyleIdx > rootCloseIdx)
    }

    @Test
    fun `normalizeEmailHtmlForMobileViewport tags 4+ column data tables but not spacer layout tables`() {
        val html = """
            <table id="spacer-layout" width="600">
              <tr>
                <td width="20">&nbsp;</td>
                <td width="560">Main content paragraph</td>
                <td width="20">&nbsp;</td>
              </tr>
              <tr>
                <td width="20">&nbsp;</td>
                <td width="560">Second line</td>
                <td width="20">&nbsp;</td>
              </tr>
            </table>
            <table id="invoice-grid" width="760">
              <tr><th>Item</th><th>Region</th><th>Cycle</th><th>Price</th></tr>
              <tr><td>VPS Pro</td><td>Tokyo</td><td>Yearly</td><td>$49.00</td></tr>
            </table>
        """.trimIndent()

        val normalized = normalizeEmailHtmlForMobileViewport(html)

        assertTrue(normalized.contains("""id="invoice-grid""""))
        assertTrue(normalized.contains("np-mail-wide-table"))
        val spacerTag = normalized.substringAfter("""id="spacer-layout"""").substringBefore(">")
        assertFalse(spacerTag.contains("np-mail-wide-table"))
    }

    @Test
    fun `wrapNormalizedEmailHtmlDocument applies light, dark, AMOLED tokens and font scaling`() {
        val lightDoc = wrapNormalizedEmailHtmlDocument(
            normalizedBodyHtml = "<p>Light Paper</p>",
            isDark = false,
            purePitchAmoled = false,
            fontScale = 1.0f,
            backgroundHex = "#FAF7F2",
            surfaceContainerHex = "#F2EDE3",
            onSurfaceHex = "#1A1B1E",
            onSurfaceVariantHex = "#555860",
            primaryHex = "#1E5EFF",
            outlineHex = "#DDD7CC",
        )
        assertTrue(lightDoc.contains("color-scheme: light"))
        assertTrue(lightDoc.contains("#FAF7F2"))
        assertTrue(lightDoc.contains("15.5px"))

        val amoledDoc = wrapNormalizedEmailHtmlDocument(
            normalizedBodyHtml = "<p>AMOLED Body</p>",
            isDark = true,
            purePitchAmoled = true,
            fontScale = 1.2f,
            backgroundHex = "#07080C",
            surfaceContainerHex = "#0B0E14",
            onSurfaceHex = "#E6E8EE",
            onSurfaceVariantHex = "#9EA5B0",
            primaryHex = "#5BB98C",
            outlineHex = "#242A36",
        )
        assertTrue(amoledDoc.contains("color-scheme: dark"))
        assertTrue(amoledDoc.contains("background-color: #000000"))
        assertTrue(amoledDoc.contains("18.6px"))
    }

    @Test
    fun `isAllowedRemoteImageRequestParts enforces subresource GET image-only policy`() {
        // Main frame navigation is always blocked
        assertFalse(
            isAllowedRemoteImageRequestParts(
                isForMainFrame = true,
                method = "GET",
                acceptHeader = "image/png",
                encodedPath = "/logo.png",
            ),
        )
        // Non-GET methods are blocked
        assertFalse(
            isAllowedRemoteImageRequestParts(
                isForMainFrame = false,
                method = "POST",
                acceptHeader = "image/png",
                encodedPath = "/logo.png",
            ),
        )
        // Stylesheet, HTML, and script requests are blocked even if path ends in .png
        assertFalse(
            isAllowedRemoteImageRequestParts(
                isForMainFrame = false,
                method = "GET",
                acceptHeader = "text/css,*/*;q=0.1",
                encodedPath = "/evil.png",
            ),
        )
        assertFalse(
            isAllowedRemoteImageRequestParts(
                isForMainFrame = false,
                method = "GET",
                acceptHeader = "text/html",
                encodedPath = "/page.png",
            ),
        )
        assertFalse(
            isAllowedRemoteImageRequestParts(
                isForMainFrame = false,
                method = "GET",
                acceptHeader = "application/javascript",
                encodedPath = "/track.png",
            ),
        )
        // Legitimate image Accept header or image file extension is allowed
        assertTrue(
            isAllowedRemoteImageRequestParts(
                isForMainFrame = false,
                method = "GET",
                acceptHeader = "image/avif,image/webp,image/png,*/*;q=0.8",
                encodedPath = "/cdn/avatar",
            ),
        )
        assertTrue(
            isAllowedRemoteImageRequestParts(
                isForMainFrame = false,
                method = "GET",
                acceptHeader = "*/*",
                encodedPath = "/assets/banner.webp",
            ),
        )
        // Arbitrary non-image path with generic Accept is blocked
        assertFalse(
            isAllowedRemoteImageRequestParts(
                isForMainFrame = false,
                method = "GET",
                acceptHeader = "*/*",
                encodedPath = "/api/beacon",
            ),
        )
    }
}
