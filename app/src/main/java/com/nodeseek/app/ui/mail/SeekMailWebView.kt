package com.nodeseek.app.ui.mail

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PurePitchBlack
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.util.Locale

private val ALLOWED_IMAGE_EXTENSIONS = setOf(
    "png",
    "jpg",
    "jpeg",
    "gif",
    "webp",
    "svg",
    "bmp",
    "ico",
    "avif",
)

private val ALLOWED_EXTERNAL_SCHEMES = setOf("http", "https", "mailto")

private data class SeekMailWebRenderSignature(
    val themedHtml: String,
    val allowRemoteImages: Boolean,
    val isDark: Boolean,
    val backgroundArgb: Int,
)

private class SeekMailWebBridgeState(
    @Volatile var allowRemoteImages: Boolean,
    @Volatile var onExternalUrlClick: (String) -> Unit,
    var lastSignature: SeekMailWebRenderSignature? = null,
)

/**
 * Isolated, privacy-first HTML email renderer Composable.
 *
 * Security & Session Isolation Guarantees:
 * - JavaScript, DOM storage, file/content access, and multi-window popups are strictly disabled.
 * - NEVER modifies global `CookieManager.getInstance().setAcceptCookie(...)` (which is process-wide
 *   and would break NodeSeek forum sessions).
 * - Blocks remote `http`/`https` requests inside `shouldInterceptRequest` unless
 *   [allowRemoteImages] is explicitly enabled AND the request targets an image resource.
 * - Routes `http://`, `https://`, and `mailto:` link taps to [onExternalUrlClick] and blocks all
 *   other URI schemes (`javascript:`, `file:`, `intent:`, `content:`, etc.).
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun SeekMailWebView(
    sanitizedBodyHtml: String,
    allowRemoteImages: Boolean,
    purePitchAmoled: Boolean,
    fontScale: Float,
    onExternalUrlClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark
    val colorScheme = MaterialTheme.colorScheme
    val backgroundColor = if (isDark && purePitchAmoled) PurePitchBlack else colorScheme.background
    val surfaceContainerColor = colorScheme.surfaceContainerLow
    val onSurfaceColor = colorScheme.onSurface
    val onSurfaceVariantColor = colorScheme.onSurfaceVariant
    val primaryColor = colorScheme.primary
    val outlineColor = colorScheme.outlineVariant

    if (LocalInspectionMode.current) {
        val previewLines = remember(sanitizedBodyHtml) {
            extractPreviewLinesFromHtml(sanitizedBodyHtml)
        }
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(backgroundColor)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            previewLines.forEach { block ->
                when (block.kind) {
                    PreviewHtmlBlockKind.HEADING -> Text(
                        text = block.text,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = (16f * fontScale).sp,
                        ),
                        color = onSurfaceColor,
                    )
                    PreviewHtmlBlockKind.CODE -> Text(
                        text = block.text,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = (15f * fontScale).sp,
                            letterSpacing = 2.sp,
                        ),
                        color = primaryColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(surfaceContainerColor)
                            .border(0.8.dp, outlineColor.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                    PreviewHtmlBlockKind.QUOTE -> Text(
                        text = block.text,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = (13.5f * fontScale).sp,
                            lineHeight = (20f * fontScale).sp,
                        ),
                        color = onSurfaceVariantColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(surfaceContainerColor.copy(alpha = 0.75f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    PreviewHtmlBlockKind.PARAGRAPH -> Text(
                        text = block.text,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = (14.5f * fontScale).sp,
                            lineHeight = (22f * fontScale).sp,
                        ),
                        color = onSurfaceColor,
                    )
                }
            }
        }
        return
    }

    val themedHtml = remember(
        sanitizedBodyHtml,
        isDark,
        purePitchAmoled,
        fontScale,
        backgroundColor,
        surfaceContainerColor,
        onSurfaceColor,
        onSurfaceVariantColor,
        primaryColor,
        outlineColor,
    ) {
        buildThemedEmailHtmlDocument(
            sanitizedFragmentHtml = sanitizedBodyHtml,
            isDark = isDark,
            purePitchAmoled = purePitchAmoled,
            fontScale = fontScale,
            backgroundHex = backgroundColor.toCssHex(),
            surfaceContainerHex = surfaceContainerColor.toCssHex(),
            onSurfaceHex = onSurfaceColor.toCssHex(),
            onSurfaceVariantHex = onSurfaceVariantColor.toCssHex(),
            primaryHex = primaryColor.toCssHex(),
            outlineHex = outlineColor.toCssHex(),
        )
    }

    val bridgeState = remember {
        SeekMailWebBridgeState(
            allowRemoteImages = allowRemoteImages,
            onExternalUrlClick = onExternalUrlClick,
        )
    }
    bridgeState.allowRemoteImages = allowRemoteImages
    bridgeState.onExternalUrlClick = onExternalUrlClick

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
            webViewRef = null
        }
    }

    val targetSignature = remember(themedHtml, allowRemoteImages, isDark, backgroundColor) {
        SeekMailWebRenderSignature(
            themedHtml = themedHtml,
            allowRemoteImages = allowRemoteImages,
            isDark = isDark,
            backgroundArgb = backgroundColor.toArgb(),
        )
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(backgroundColor.toArgb())
                isVerticalScrollBarEnabled = true
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_IF_CONTENT_SCROLLS

                // Only adjust third-party cookies for this isolated WebView instance; never call
                // CookieManager.getInstance().setAcceptCookie(false) which mutates process-wide state.
                runCatching {
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                }

                settings.apply {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    setSupportMultipleWindows(false)
                    javaScriptCanOpenWindowsAutomatically = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    loadsImagesAutomatically = allowRemoteImages
                    blockNetworkImage = !allowRemoteImages
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    textZoom = 100
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                }

                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, isDark)
                }

                // Keep vertical scrolling and pinch-zoom gestures responsive inside the WebView.
                setOnTouchListener { v, _ ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }

                webChromeClient = WebChromeClient()

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val uri = request?.url ?: return emptyBlockedWebResponse()
                        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
                        return when (scheme) {
                            "about", "data" -> null
                            "http", "https" -> {
                                if (bridgeState.allowRemoteImages && isAllowedRemoteImageRequest(request)) {
                                    null
                                } else {
                                    emptyBlockedWebResponse()
                                }
                            }
                            else -> emptyBlockedWebResponse()
                        }
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val uri = request?.url ?: return true
                        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
                        if (scheme in ALLOWED_EXTERNAL_SCHEMES) {
                            val targetUrl = uri.toString()
                            if (targetUrl.isNotBlank()) {
                                bridgeState.onExternalUrlClick(targetUrl)
                            }
                        }
                        return true
                    }
                }

                bridgeState.lastSignature = targetSignature
                webViewRef = this
                loadDataWithBaseURL("about:blank", themedHtml, "text/html", "utf-8", null)
            }
        },
        update = { webView ->
            bridgeState.allowRemoteImages = allowRemoteImages
            bridgeState.onExternalUrlClick = onExternalUrlClick

            if (bridgeState.lastSignature != targetSignature) {
                bridgeState.lastSignature = targetSignature
                webView.setBackgroundColor(targetSignature.backgroundArgb)
                webView.settings.apply {
                    loadsImagesAutomatically = allowRemoteImages
                    blockNetworkImage = !allowRemoteImages
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, isDark)
                }
                webView.loadDataWithBaseURL("about:blank", themedHtml, "text/html", "utf-8", null)
            }
        },
    )
}

internal fun buildThemedEmailHtmlDocument(
    sanitizedFragmentHtml: String,
    isDark: Boolean,
    purePitchAmoled: Boolean,
    fontScale: Float,
    backgroundHex: String,
    surfaceContainerHex: String,
    onSurfaceHex: String,
    onSurfaceVariantHex: String,
    primaryHex: String,
    outlineHex: String,
): String {
    val clampedScale = fontScale.coerceIn(0.85f, 1.35f)
    val baseFontPx = (15.5f * clampedScale)
    val resolvedBg = if (isDark && purePitchAmoled) "#000000" else backgroundHex
    val colorSchemeValue = if (isDark) "dark" else "light"
    val bodyContent = if (sanitizedFragmentHtml.isBlank()) {
        ""
    } else {
        normalizeEmailHtmlForMobileViewport(sanitizedFragmentHtml)
    }

    return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0" />
          <meta name="color-scheme" content="$colorSchemeValue" />
          <style>
            :root {
              color-scheme: $colorSchemeValue;
            }
            *, *::before, *::after {
              box-sizing: border-box;
            }
            html, body {
              margin: 0 !important;
              padding: 0 !important;
              width: 100% !important;
              max-width: 100% !important;
              min-width: 0 !important;
              overflow-x: hidden !important;
              background-color: $resolvedBg !important;
              color: $onSurfaceHex;
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Noto Sans SC", "PingFang SC", sans-serif;
              font-size: ${String.format(Locale.US, "%.1f", baseFontPx)}px;
              line-height: 1.65;
              word-wrap: break-word;
              overflow-wrap: anywhere;
              word-break: break-word;
              -webkit-text-size-adjust: 100%;
            }
            body {
              padding: 12px 14px 28px 14px !important;
              box-sizing: border-box !important;
            }
            .np-mail-root {
              width: 100% !important;
              max-width: 100% !important;
              min-width: 0 !important;
              overflow-x: hidden !important;
            }
            a {
              color: $primaryHex;
              text-decoration: underline;
              word-break: break-word;
              overflow-wrap: anywhere;
            }
            img, svg, video, picture, canvas {
              max-width: 100% !important;
              height: auto !important;
            }
            table {
              max-width: 100% !important;
              min-width: 0 !important;
              border-collapse: collapse;
            }
            th, td {
              border-color: $outlineHex;
              word-break: break-word;
              overflow-wrap: anywhere;
              white-space: normal;
            }
            pre {
              max-width: 100% !important;
              white-space: pre-wrap !important;
              word-break: break-word !important;
              overflow-wrap: anywhere !important;
              overflow-x: auto;
              padding: 10px 12px;
              border-radius: 10px;
              background-color: $surfaceContainerHex;
              border: 1px solid $outlineHex;
              font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
              font-size: 0.88em;
              line-height: 1.5;
            }
            code {
              font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
              font-size: 0.9em;
              padding: 1px 5px;
              border-radius: 5px;
              background-color: $surfaceContainerHex;
              word-break: break-word;
              overflow-wrap: anywhere;
            }
            pre code {
              background: transparent;
              padding: 0;
            }
            blockquote {
              margin: 10px 0;
              padding: 8px 12px;
              border-left: 3px solid $primaryHex;
              background-color: $surfaceContainerHex;
              color: $onSurfaceVariantHex;
              border-radius: 0 8px 8px 0;
            }
            hr {
              border: none;
              border-top: 1px solid $outlineHex;
              margin: 14px 0;
            }
            h1, h2, h3, h4 {
              line-height: 1.35;
              margin: 0.8em 0 0.4em;
            }
          </style>
        </head>
        <body>
          <div class="np-mail-root">$bodyContent</div>
          <style>
            html, body {
              width: 100% !important;
              max-width: 100% !important;
              min-width: 0 !important;
              overflow-x: hidden !important;
            }
            html body .np-mail-root,
            html body .np-mail-root * {
              box-sizing: border-box !important;
              max-width: 100% !important;
              min-width: 0 !important;
            }
            html body .np-mail-root img,
            html body .np-mail-root svg,
            html body .np-mail-root video,
            html body .np-mail-root picture,
            html body .np-mail-root canvas {
              max-width: 100% !important;
              height: auto !important;
            }
            html body .np-mail-root table {
              max-width: 100% !important;
              min-width: 0 !important;
            }
            html body .np-mail-root table.np-mail-wide-table {
              width: 100% !important;
              table-layout: fixed !important;
              font-size: 0.92em;
            }
            html body .np-mail-root table.np-mail-wide-table th,
            html body .np-mail-root table.np-mail-wide-table td {
              padding: 6px 5px !important;
            }
            html body .np-mail-root th,
            html body .np-mail-root td {
              word-break: break-word !important;
              overflow-wrap: anywhere !important;
              white-space: normal !important;
            }
            html body .np-mail-root pre {
              max-width: 100% !important;
              white-space: pre-wrap !important;
              word-break: break-word !important;
              overflow-wrap: anywhere !important;
            }
            html body .np-mail-root a {
              word-break: break-word !important;
              overflow-wrap: anywhere !important;
            }
          </style>
        </body>
        </html>
    """.trimIndent()
}

private val MEDIA_TAGS = setOf("img", "svg", "video", "picture", "canvas")
private val TABLE_CELL_TAGS = setOf("td", "th", "col", "colgroup")
private val CSS_LENGTH_REGEX = Regex(
    """^\s*([0-9]+(?:\.[0-9]+)?)\s*(px|pt|pc|in|cm|mm|em|rem|vw|%)?\s*$""",
    RegexOption.IGNORE_CASE,
)
private val EMBEDDED_MIN_WIDTH_REGEX = Regex(
    """min-width\s*:\s*[^;}]+""",
    RegexOption.IGNORE_CASE,
)
private val EMBEDDED_NOWRAP_REGEX = Regex(
    """white-space\s*:\s*nowrap(?:\s*!important)?""",
    RegexOption.IGNORE_CASE,
)
private val EMBEDDED_FIXED_WIDTH_REGEX = Regex(
    """(?<![a-z0-9-])width\s*:\s*([0-9]+(?:\.[0-9]+)?)\s*(px|pt|cm|mm|in)(?:\s*!important)?""",
    RegexOption.IGNORE_CASE,
)

/**
 * Normalizes desktop-fixed email layouts (e.g. 600px wrapper tables, fixed cell widths,
 * `min-width`, `nowrap`, huge desktop side gutters, and wide multi-column data tables)
 * purely in Kotlin/Jsoup with zero JavaScript so HTML mails fit mobile screens cleanly.
 */
internal fun normalizeEmailHtmlForMobileViewport(sanitizedFragmentHtml: String): String {
    if (sanitizedFragmentHtml.isBlank()) return ""
    val doc = Jsoup.parseBodyFragment(sanitizedFragmentHtml)
    doc.outputSettings().prettyPrint(false)
    val body = doc.body()

    for (element in body.allElements) {
        if (element === body) continue
        val tag = element.tagName().lowercase(Locale.ROOT)

        if (tag == "style") {
            normalizeEmbeddedStyleBlock(element)
            continue
        }

        if (element.hasAttr("nowrap")) {
            element.removeAttr("nowrap")
        }

        var removedOversizedMediaWidth = false
        if (element.hasAttr("width")) {
            val rawWidth = element.attr("width").trim()
            val parsed = parseCssLength(rawWidth)
            if (parsed != null) {
                when {
                    parsed.isPercent -> {
                        if (parsed.value > 100f) {
                            element.attr("width", "100%")
                        }
                    }
                    tag in MEDIA_TAGS -> {
                        if (parsed.approxPx > 260f) {
                            element.removeAttr("width")
                            element.removeAttr("height")
                            removedOversizedMediaWidth = true
                        }
                    }
                    tag == "table" -> {
                        if (parsed.approxPx > 260f) {
                            element.attr("width", "100%")
                        }
                    }
                    tag in TABLE_CELL_TAGS -> {
                        if (parsed.approxPx > 96f) {
                            element.removeAttr("width")
                        }
                    }
                    else -> {
                        if (parsed.approxPx > 260f) {
                            element.removeAttr("width")
                        }
                    }
                }
            }
        }

        if (element.hasAttr("style")) {
            val normalizedStyle = normalizeInlineStyleForMobile(
                rawStyle = element.attr("style"),
                tag = tag,
                removedOversizedMediaWidth = removedOversizedMediaWidth,
            )
            if (normalizedStyle.isBlank()) {
                element.removeAttr("style")
            } else {
                element.attr("style", normalizedStyle)
            }
        }
    }

    // Mark wide leaf data tables (4+ non-empty columns or dense multi-column grids) so CSS
    // switches them to `table-layout: fixed; width: 100%` without harming 1–3 column layout tables.
    for (table in body.select("table")) {
        if (isWideLeafDataTable(table)) {
            table.addClass("np-mail-wide-table")
        }
    }

    return body.html()
}

private fun normalizeEmbeddedStyleBlock(styleElement: Element) {
    val rawCss = styleElement.data()
    if (rawCss.isBlank()) return
    var normalized = EMBEDDED_MIN_WIDTH_REGEX.replace(rawCss, "min-width: 0")
    normalized = EMBEDDED_NOWRAP_REGEX.replace(normalized, "white-space: normal")
    normalized = EMBEDDED_FIXED_WIDTH_REGEX.replace(normalized) { match ->
        val amount = match.groupValues[1].toFloatOrNull() ?: return@replace match.value
        val unit = match.groupValues[2].lowercase(Locale.ROOT)
        val approxPx = toApproxPx(amount, unit)
        if (approxPx > 260f) {
            "max-width: 100%; width: auto"
        } else {
            match.value.replace(Regex("""\s*!important""", RegexOption.IGNORE_CASE), "")
        }
    }
    if (normalized != rawCss) {
        styleElement.childNodes().toList().forEach { it.remove() }
        styleElement.appendChild(DataNode(normalized))
    }
}

private fun normalizeInlineStyleForMobile(
    rawStyle: String,
    tag: String,
    removedOversizedMediaWidth: Boolean,
): String {
    val declarations = splitCssDeclarations(rawStyle)
    if (declarations.isEmpty()) return ""

    var dropMediaHeight = removedOversizedMediaWidth
    val parsedPairs = declarations.mapNotNull { decl ->
        val colonIdx = decl.indexOf(':')
        if (colonIdx <= 0) return@mapNotNull null
        val prop = decl.substring(0, colonIdx).trim().lowercase(Locale.ROOT)
        val value = decl.substring(colonIdx + 1).trim()
        if (prop.isEmpty() || value.isEmpty()) return@mapNotNull null
        prop to value
    }

    if (tag in MEDIA_TAGS && !dropMediaHeight) {
        for ((prop, value) in parsedPairs) {
            if (prop == "width") {
                val cleaned = stripImportant(value)
                val len = parseCssLength(cleaned)
                if (len != null && !len.isPercent && len.approxPx > 260f) {
                    dropMediaHeight = true
                    break
                }
            }
        }
    }

    val kept = ArrayList<String>(parsedPairs.size)
    for ((prop, rawVal) in parsedPairs) {
        val cleanVal = stripImportant(rawVal)
        when (prop) {
            "min-width" -> {
                // Drop desktop min-width locks; CSS enforces min-width: 0.
            }

            "white-space" -> {
                val lower = cleanVal.lowercase(Locale.ROOT)
                when {
                    lower.contains("nowrap") -> { /* drop nowrap */ }
                    lower == "pre" -> kept.add("white-space: pre-wrap")
                    else -> kept.add("$prop: $cleanVal")
                }
            }

            "word-break" -> {
                if (!cleanVal.equals("keep-all", ignoreCase = true)) {
                    kept.add("$prop: $cleanVal")
                }
            }

            "width" -> {
                val lower = cleanVal.lowercase(Locale.ROOT)
                if (lower == "max-content" || lower == "min-content") {
                    // Drop intrinsic width locks that overflow mobile screens.
                } else {
                    val len = parseCssLength(cleanVal)
                    when {
                        len == null -> kept.add("width: $cleanVal")
                        len.isPercent -> {
                            kept.add(if (len.value > 100f) "width: 100%" else "width: $cleanVal")
                        }
                        tag == "table" -> {
                            if (len.approxPx > 260f) {
                                kept.add("width: 100%")
                            } else {
                                kept.add("width: $cleanVal")
                            }
                        }
                        tag in TABLE_CELL_TAGS -> {
                            if (len.approxPx <= 96f) {
                                kept.add("width: $cleanVal")
                            }
                        }
                        tag in MEDIA_TAGS -> {
                            if (len.approxPx <= 260f) {
                                kept.add("width: $cleanVal")
                            }
                        }
                        else -> {
                            if (len.approxPx <= 260f) {
                                kept.add("width: $cleanVal")
                            }
                        }
                    }
                }
            }

            "max-width" -> {
                val len = parseCssLength(cleanVal)
                when {
                    len == null -> kept.add("max-width: $cleanVal")
                    len.isPercent && len.value > 100f -> kept.add("max-width: 100%")
                    !len.isPercent && len.approxPx > 320f -> kept.add("max-width: 100%")
                    else -> kept.add("max-width: $cleanVal")
                }
            }

            "height" -> {
                if (tag in MEDIA_TAGS) {
                    if (!dropMediaHeight) {
                        kept.add("height: $cleanVal")
                    }
                } else {
                    kept.add("$prop: $rawVal")
                }
            }

            "padding-left", "padding-right" -> {
                val len = parseCssLength(cleanVal)
                if (len != null && !len.isPercent && len.approxPx > 24f) {
                    kept.add("$prop: 16px")
                } else {
                    kept.add("$prop: $rawVal")
                }
            }

            "margin-left", "margin-right" -> {
                val len = parseCssLength(cleanVal)
                if (len != null && !len.isPercent && len.approxPx > 24f) {
                    kept.add("$prop: 0px")
                } else {
                    kept.add("$prop: $rawVal")
                }
            }

            "padding" -> {
                kept.add("padding: ${clampHorizontalBoxShorthand(cleanVal, maxPx = 24f, clampedValue = "16px")}")
            }

            "margin" -> {
                kept.add("margin: ${clampHorizontalBoxShorthand(cleanVal, maxPx = 24f, clampedValue = "0px")}")
            }

            else -> kept.add("$prop: $rawVal")
        }
    }
    return kept.joinToString("; ")
}

private fun clampHorizontalBoxShorthand(
    value: String,
    maxPx: Float,
    clampedValue: String,
): String {
    val parts = value.trim().split(Regex("""\s+"""))
    if (parts.size !in 1..4) return value
    fun clampIfOversized(token: String): String {
        val len = parseCssLength(token)
        return if (len != null && !len.isPercent && len.approxPx > maxPx) clampedValue else token
    }
    return when (parts.size) {
        1 -> {
            val clampedH = clampIfOversized(parts[0])
            if (clampedH == parts[0]) value else "${parts[0]} $clampedH"
        }
        2 -> "${parts[0]} ${clampIfOversized(parts[1])}"
        3 -> "${parts[0]} ${clampIfOversized(parts[1])} ${parts[2]}"
        4 -> "${parts[0]} ${clampIfOversized(parts[1])} ${parts[2]} ${clampIfOversized(parts[3])}"
        else -> value
    }
}

private fun isWideLeafDataTable(table: Element): Boolean {
    if (table.select("table").size > 1) return false
    val rows = table.select("tr")
    if (rows.size < 2) return false
    var maxNonEmptyCols = 0
    for (row in rows) {
        val cells = row.children().filter {
            val t = it.tagName().lowercase(Locale.ROOT)
            t == "td" || t == "th"
        }
        val nonEmptyCount = cells.count { cell ->
            cell.text().replace("\u00A0", "").isNotBlank() || cell.selectFirst("img, svg") != null
        }
        if (nonEmptyCount > maxNonEmptyCols) {
            maxNonEmptyCols = nonEmptyCount
        }
    }
    return maxNonEmptyCols >= 4
}

private fun splitCssDeclarations(style: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var inSingle = false
    var inDouble = false
    var parenDepth = 0
    for (ch in style) {
        when (ch) {
            '\'' -> if (!inDouble) inSingle = !inSingle
            '"' -> if (!inSingle) inDouble = !inDouble
            '(' -> if (!inSingle && !inDouble) parenDepth++
            ')' -> if (!inSingle && !inDouble && parenDepth > 0) parenDepth--
            ';' -> if (!inSingle && !inDouble && parenDepth == 0) {
                val item = current.toString().trim()
                if (item.isNotEmpty()) result.add(item)
                current.setLength(0)
                continue
            }
        }
        current.append(ch)
    }
    val trailing = current.toString().trim()
    if (trailing.isNotEmpty()) result.add(trailing)
    return result
}

private fun stripImportant(value: String): String =
    value.replace(Regex("""\s*!important\s*$""", RegexOption.IGNORE_CASE), "").trim()

private data class ParsedCssLength(
    val value: Float,
    val isPercent: Boolean,
    val approxPx: Float,
)

private fun parseCssLength(raw: String): ParsedCssLength? {
    val match = CSS_LENGTH_REGEX.matchEntire(raw) ?: return null
    val num = match.groupValues[1].toFloatOrNull() ?: return null
    val unit = match.groupValues[2].lowercase(Locale.ROOT)
    return if (unit == "%") {
        ParsedCssLength(value = num, isPercent = true, approxPx = 0f)
    } else {
        ParsedCssLength(value = num, isPercent = false, approxPx = toApproxPx(num, unit))
    }
}

private fun toApproxPx(value: Float, unit: String): Float = when (unit) {
    "", "px" -> value
    "pt" -> value * 1.333f
    "pc" -> value * 16f
    "em", "rem" -> value * 16f
    "in" -> value * 96f
    "cm" -> value * 37.8f
    "mm" -> value * 3.78f
    "vw" -> value * 4f
    else -> value
}

private fun isAllowedRemoteImageRequest(request: WebResourceRequest): Boolean {
    if (request.isForMainFrame) return false
    val method = request.method?.trim().orEmpty()
    if (method.isNotEmpty() && !method.equals("GET", ignoreCase = true)) {
        return false
    }

    val acceptHeader = request.requestHeaders
        ?.entries
        ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
        ?.value
        ?.lowercase(Locale.US)
        .orEmpty()

    if (
        acceptHeader.contains("text/css") ||
        acceptHeader.contains("text/html") ||
        acceptHeader.contains("javascript")
    ) {
        return false
    }
    if (acceptHeader.contains("image/")) {
        return true
    }

    val path = request.url?.encodedPath?.lowercase(Locale.US).orEmpty()
    val extension = path.substringAfterLast('/', "").substringAfterLast('.', "")
    return extension in ALLOWED_IMAGE_EXTENSIONS
}

private fun emptyBlockedWebResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

private fun Color.toCssHex(): String {
    val rgb = toArgb() and 0x00FFFFFF
    return String.format(Locale.US, "#%06X", rgb)
}

private enum class PreviewHtmlBlockKind {
    HEADING,
    CODE,
    QUOTE,
    PARAGRAPH,
}

private data class PreviewHtmlBlock(
    val kind: PreviewHtmlBlockKind,
    val text: String,
)

private fun extractPreviewLinesFromHtml(sanitizedHtml: String): List<PreviewHtmlBlock> {
    if (sanitizedHtml.isBlank()) return emptyList()
    val body = Jsoup.parseBodyFragment(sanitizedHtml).body()
    val blocks = mutableListOf<PreviewHtmlBlock>()
    for (el in body.children()) {
        val text = el.text().trim()
        if (text.isEmpty()) continue
        val kind = when (el.tagName().lowercase(Locale.US)) {
            "h1", "h2", "h3", "h4" -> PreviewHtmlBlockKind.HEADING
            "pre", "code" -> PreviewHtmlBlockKind.CODE
            "blockquote" -> PreviewHtmlBlockKind.QUOTE
            else -> PreviewHtmlBlockKind.PARAGRAPH
        }
        blocks += PreviewHtmlBlock(kind = kind, text = text)
    }
    if (blocks.isEmpty()) {
        val fallback = body.text().trim()
        if (fallback.isNotEmpty()) {
            blocks += PreviewHtmlBlock(PreviewHtmlBlockKind.PARAGRAPH, fallback)
        }
    }
    return blocks
}
