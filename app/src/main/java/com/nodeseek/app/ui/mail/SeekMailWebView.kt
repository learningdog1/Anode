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
                isHorizontalScrollBarEnabled = true
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
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                }

                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, isDark)
                }

                // Prevent parent sheets/pagers from intercepting horizontal table drags or vertical scrolls.
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
        sanitizedFragmentHtml
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
            html, body {
              margin: 0;
              padding: 0;
              background-color: $resolvedBg !important;
              color: $onSurfaceHex;
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Noto Sans SC", "PingFang SC", sans-serif;
              font-size: ${String.format(Locale.US, "%.1f", baseFontPx)}px;
              line-height: 1.65;
              word-wrap: break-word;
              overflow-wrap: anywhere;
              -webkit-text-size-adjust: 100%;
            }
            body {
              padding: 14px 16px 28px 16px;
              box-sizing: border-box;
            }
            .np-mail-root {
              max-width: 100%;
              overflow-x: auto;
            }
            a {
              color: $primaryHex;
              text-decoration: underline;
              word-break: break-word;
            }
            img, svg, video, picture {
              max-width: 100% !important;
              height: auto !important;
            }
            table {
              max-width: 100% !important;
              border-collapse: collapse;
            }
            th, td {
              border-color: $outlineHex;
            }
            pre {
              max-width: 100%;
              overflow-x: auto;
              padding: 12px;
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
        </body>
        </html>
    """.trimIndent()
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
