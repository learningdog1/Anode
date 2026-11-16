package com.nodeseek.app.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.nodeseek.app.data.network.NodeSeekUrlNormalizer
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URI

private const val DEFAULT_SIGN_IN_URL = "https://www.nodeseek.com/signIn.html"

@Immutable
data class WebViewSessionStatus(
    val configReady: Boolean = false,
    val signedInUid: Long? = null,
    val signedInUsername: String? = null,
    val currentUrl: String = DEFAULT_SIGN_IN_URL,
) {
    val isAuthenticated: Boolean
        get() = signedInUid != null && signedInUid > 0L
}

/**
 * Privacy-transparent Material 3 `ModalBottomSheet` hosting a first-party `WebView`
 * (`https://www.nodeseek.com/signIn.html` or challenge verification page).
 *
 * Security & Compliance Guarantees:
 * - Shares `CookieManager.getInstance()` directly with OkHttp session transport and flushes cookies.
 * - Strictly read-only inspection of `window.__config__` / `window.__config__.user` to detect
 *   when the human user completes login, 2FA, or page verification.
 * - NEVER injects scripts to click, solve, or bypass any anti-bot/Turnstile challenge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun WebViewSessionSheet(
    onDismiss: () -> Unit,
    onSessionVerified: (WebViewSessionStatus) -> Unit,
    modifier: Modifier = Modifier,
    initialUrl: String = DEFAULT_SIGN_IN_URL,
    requireSignedInUser: Boolean = true,
    autoDismissWhenVerified: Boolean = false,
    userAgentOverride: String? = null,
    useSystemDefaultUserAgent: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uriHandler = LocalUriHandler.current
    val haptics = LocalHapticFeedback.current
    val isDark = NodePulseThemeTokens.isDark

    // Avoid sending an already-signed-in user to /signIn.html when only Cloudflare verification is needed,
    // which would otherwise encourage a redundant WebView login and risk evicting desktop sessions.
    val effectiveInitialUrl = remember(initialUrl, requireSignedInUser) {
        if (!requireSignedInUser && initialUrl.contains("/signIn", ignoreCase = true)) {
            "${NodeSeekUrlNormalizer.ORIGIN}/"
        } else {
            initialUrl
        }
    }

    var currentUrl by remember(effectiveInitialUrl) { mutableStateOf(effectiveInitialUrl) }
    var progressPercent by remember { mutableIntStateOf(0) }
    var isPageLoading by remember(effectiveInitialUrl) { mutableStateOf(true) }
    var sessionStatus by remember { mutableStateOf(WebViewSessionStatus(currentUrl = effectiveInitialUrl)) }
    var hasFinishedPageOnNodeSeek by remember(effectiveInitialUrl) { mutableStateOf(false) }
    var autoCompletedOnce by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var popupWebViewRef by remember { mutableStateOf<WebView?>(null) }

    val displayHost = remember(currentUrl) {
        runCatching { URI(currentUrl).host ?: "www.nodeseek.com" }.getOrDefault("www.nodeseek.com")
    }

    // Gentle read-only polling while the sheet is open so SPA/AJAX login transitions on
    // signIn.html or challenge completions are picked up even when no full-page navigation fires.
    LaunchedEffect(webViewRef, hasFinishedPageOnNodeSeek) {
        while (webViewRef != null) {
            delay(1200)
            if (hasFinishedPageOnNodeSeek) {
                webViewRef?.let { wv ->
                    probeWindowConfigReadOnly(wv) { probed ->
                        sessionStatus = probed
                    }
                }
            }
        }
    }

    val meetsCompletionCriteria = if (requireSignedInUser) {
        sessionStatus.isAuthenticated && hasFinishedPageOnNodeSeek && !isPageLoading
    } else {
        hasFinishedPageOnNodeSeek && sessionStatus.configReady && !isPageLoading
    }

    LaunchedEffect(meetsCompletionCriteria, autoDismissWhenVerified) {
        if (meetsCompletionCriteria && autoDismissWhenVerified && !autoCompletedOnce) {
            autoCompletedOnce = true
            // Allow final redirect Set-Cookie writes (e.g. cf_clearance / session) to commit before closing
            delay(650L)
            CookieManager.getInstance().flush()
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onSessionVerified(sessionStatus)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush()
            popupWebViewRef?.apply {
                stopLoading()
                destroy()
            }
            popupWebViewRef = null
            webViewRef?.apply {
                stopLoading()
                destroy()
            }
            webViewRef = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            CookieManager.getInstance().flush()
            onDismiss()
        },
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = null,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding(),
        ) {
            // ── 1. SSL Verified Top Bar ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = {
                        CookieManager.getInstance().flush()
                        onDismiss()
                    },
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭网页会话",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape,
                        )
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = "HTTPS 加密连接",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = displayHost,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(
                    onClick = { webViewRef?.reload() },
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "刷新网页",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(
                    onClick = { runCatching { uriHandler.openUri(currentUrl) } },
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.OpenInBrowser,
                        contentDescription = "在系统浏览器打开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (progressPercent in 1..99) {
                LinearProgressIndicator(
                    progress = { progressPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainer,
                )
            }

            // ── 2. Privacy Transparency & Live User Status Banner ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "安全声明：请在下方官方页面直接完成登录、2FA 或验证。客户端不读取密码，仅同步本地会话 Cookie。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }

                AnimatedVisibility(visible = sessionStatus.isAuthenticated || (!requireSignedInUser && sessionStatus.configReady)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            val statusLabel = if (sessionStatus.isAuthenticated) {
                                "已连接账号：@${sessionStatus.signedInUsername.orEmpty()} (UID ${sessionStatus.signedInUid})"
                            } else {
                                "站点会话已就绪，可返回应用继续浏览"
                            }
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                CookieManager.getInstance().flush()
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSessionVerified(sessionStatus)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            shape = CircleShape,
                        ) {
                            Text(
                                text = "完成并返回",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            // ── 3. First-Party WebView Viewport ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(16.dp),
                    ),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                loadsImagesAutomatically = true
                                javaScriptCanOpenWindowsAutomatically = true
                                setSupportMultipleWindows(true)
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                // Leave native WebView UA untouched when using system default so Chromium
                                // sends genuine Sec-CH-UA client hints during Cloudflare verification.
                                if (!useSystemDefaultUserAgent && !userAgentOverride.isNullOrBlank()) {
                                    userAgentString = userAgentOverride
                                }
                            }

                            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, isDark)
                            }

                            // Prevent BottomSheet vertical drag from hijacking WebView scrolling
                            setOnTouchListener { v, _ ->
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                                false
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progressPercent = newProgress
                                }

                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: Message?,
                                ): Boolean {
                                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                    val parentCtx = view?.context ?: return false
                                    val childWebView = WebView(parentCtx).apply {
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                        )
                                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                        settings.apply {
                                            javaScriptEnabled = true
                                            domStorageEnabled = true
                                            loadsImagesAutomatically = true
                                            javaScriptCanOpenWindowsAutomatically = true
                                            setSupportMultipleWindows(true)
                                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                            if (!useSystemDefaultUserAgent && !userAgentOverride.isNullOrBlank()) {
                                                userAgentString = userAgentOverride
                                            }
                                        }
                                        setOnTouchListener { v, _ ->
                                            v.parent?.requestDisallowInterceptTouchEvent(true)
                                            false
                                        }
                                        webChromeClient = object : WebChromeClient() {
                                            override fun onCloseWindow(window: WebView?) {
                                                CookieManager.getInstance().flush()
                                                popupWebViewRef = null
                                                window?.destroy()
                                            }
                                        }
                                        webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(
                                                v: WebView?,
                                                request: WebResourceRequest?,
                                            ): Boolean {
                                                val target = request?.url?.toString().orEmpty()
                                                if (!isTrustedVerificationPopupUrl(target)) {
                                                    runCatching { uriHandler.openUri(target) }
                                                    popupWebViewRef = null
                                                    v?.destroy()
                                                    return true
                                                }
                                                return false
                                            }

                                            override fun onPageFinished(v: WebView?, url: String?) {
                                                CookieManager.getInstance().flush()
                                            }
                                        }
                                    }
                                    popupWebViewRef?.destroy()
                                    popupWebViewRef = childWebView
                                    transport.webView = childWebView
                                    resultMsg.sendToTarget()
                                    return true
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean = false

                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: Bitmap?,
                                ) {
                                    isPageLoading = true
                                    if (!url.isNullOrBlank()) {
                                        currentUrl = url
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isPageLoading = false
                                    if (!url.isNullOrBlank()) {
                                        currentUrl = url
                                        val finishedHost = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
                                        if (finishedHost.endsWith("nodeseek.com", ignoreCase = true)) {
                                            hasFinishedPageOnNodeSeek = true
                                        }
                                    }
                                    CookieManager.getInstance().flush()
                                    if (view != null) {
                                        probeWindowConfigReadOnly(view) { probed ->
                                            sessionStatus = probed
                                        }
                                    }
                                }
                            }

                            webViewRef = this
                            loadUrl(effectiveInitialUrl)
                        }
                    },
                )

                popupWebViewRef?.let { childPopup ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { childPopup },
                        )
                        IconButton(
                            onClick = {
                                CookieManager.getInstance().flush()
                                popupWebViewRef = null
                                childPopup.stopLoading()
                                childPopup.destroy()
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f)),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "关闭验证弹窗",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun isTrustedVerificationPopupUrl(rawUrl: String): Boolean {
    if (rawUrl.isBlank() || rawUrl.startsWith("about:", ignoreCase = true)) return true
    val host = runCatching { URI(rawUrl).host?.lowercase().orEmpty() }.getOrDefault("")
    if (host.isEmpty()) return false
    return host == "nodeseek.com" ||
        host.endsWith(".nodeseek.com") ||
        host == "cloudflare.com" ||
        host.endsWith(".cloudflare.com") ||
        host == "oauth.telegram.org" ||
        host.endsWith(".telegram.org")
}

private const val READ_ONLY_CONFIG_PROBE_JS = """
(function() {
  try {
    if (document.readyState === 'loading') return JSON.stringify({ ready: false });
    if (window._cf_chl_opt) return JSON.stringify({ ready: false });
    var title = String(document.title || '');
    if (title.indexOf('Just a moment') !== -1 || title.indexOf('请稍候') !== -1) {
      return JSON.stringify({ ready: false });
    }
    if (document.getElementById('challenge-running') || document.getElementById('cf-please-wait')) {
      return JSON.stringify({ ready: false });
    }
    var cfg = window.__config__;
    if (!cfg) {
      var tempEl = document.querySelector('script#temp-script');
      if (tempEl && tempEl.textContent) {
        try {
          var b64 = tempEl.textContent.trim();
          var binStr = atob(b64);
          var bytes = new Uint8Array(binStr.length);
          for (var i = 0; i < binStr.length; i++) bytes[i] = binStr.charCodeAt(i);
          cfg = JSON.parse(new TextDecoder('utf-8').decode(bytes));
        } catch (_) {}
      }
    }
    var hasForumBody = Boolean(document.querySelector('#nsk-body, #nsk-body-left'));
    if (!cfg && !hasForumBody) return JSON.stringify({ ready: false });
    var u = cfg ? cfg.user : null;
    return JSON.stringify({
      ready: true,
      uid: u && u.member_id ? Number(u.member_id) : null,
      name: u && u.member_name ? String(u.member_name) : null
    });
  } catch (e) {
    return JSON.stringify({ ready: false });
  }
})();
"""

private enum class TurnstileStatus {
    LOADING,
    READY,
    VERIFIED,
    EXPIRED,
    ERROR,
}

/**
 * Visible, user-operated Cloudflare Turnstile verification widget hosted inside a first-party
 * `https://www.nodeseek.com/` WebView context for native password sign-in (`POST /api/account/signIn`).
 *
 * Strictly non-bypass: never automates, clicks, or scripts verification; the user interacts
 * directly with the official Cloudflare Turnstile UI.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun TurnstileVerificationWidget(
    resetNonce: Int,
    onTokenReceived: (String) -> Unit,
    onTokenCleared: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark
    val uriHandler = LocalUriHandler.current
    val latestOnTokenReceived by rememberUpdatedState(onTokenReceived)
    val latestOnTokenCleared by rememberUpdatedState(onTokenCleared)

    var status by remember { mutableStateOf(TurnstileStatus.LOADING) }
    var manualReloadCount by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(resetNonce) {
        if (resetNonce > 0) {
            status = TurnstileStatus.LOADING
            latestOnTokenCleared()
            val wv = webViewRef
            if (wv != null) {
                wv.evaluateJavascript("if (window.__resetTurnstile) { window.__resetTurnstile(); }") { result ->
                    if (result == null || result == "null") {
                        manualReloadCount += 1
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush()
            webViewRef?.apply {
                stopLoading()
                removeJavascriptInterface(TURNSTILE_JS_BRIDGE)
                destroy()
            }
            webViewRef = null
        }
    }

    val containerColor = when (status) {
        TurnstileStatus.VERIFIED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        TurnstileStatus.ERROR, TurnstileStatus.EXPIRED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val borderColor = when (status) {
        TurnstileStatus.VERIFIED -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        TurnstileStatus.ERROR, TurnstileStatus.EXPIRED -> MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor)
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = when (status) {
                        TurnstileStatus.VERIFIED -> Icons.Rounded.CheckCircle
                        TurnstileStatus.ERROR, TurnstileStatus.EXPIRED -> Icons.Rounded.ErrorOutline
                        else -> Icons.Rounded.Shield
                    },
                    contentDescription = null,
                    tint = when (status) {
                        TurnstileStatus.VERIFIED -> MaterialTheme.colorScheme.primary
                        TurnstileStatus.ERROR, TurnstileStatus.EXPIRED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(15.dp),
                )
                val statusLabel = when (status) {
                    TurnstileStatus.LOADING -> "正在加载 Cloudflare 人机验证…"
                    TurnstileStatus.READY -> "请手动完成下方人机验证"
                    TurnstileStatus.VERIFIED -> "人机验证已通过"
                    TurnstileStatus.EXPIRED -> "验证已过期，请重新完成"
                    TurnstileStatus.ERROR -> "验证加载异常，请刷新重试"
                }
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = when (status) {
                        TurnstileStatus.VERIFIED -> MaterialTheme.colorScheme.primary
                        TurnstileStatus.ERROR, TurnstileStatus.EXPIRED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(
                onClick = {
                    status = TurnstileStatus.LOADING
                    latestOnTokenCleared()
                    manualReloadCount += 1
                },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "刷新人机验证",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val useCompact = maxWidth < 300.dp
            val widgetSize = if (useCompact) "compact" else "flexible"
            val widgetHeight = if (useCompact) 144.dp else 72.dp
            val htmlPayload = remember(isDark, widgetSize) {
                buildTurnstileWidgetHtml(
                    siteKey = NodeSeekUrlNormalizer.TURNSTILE_SITE_KEY,
                    isDark = isDark,
                    widgetSize = widgetSize,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(widgetHeight)
                    .clip(RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                loadsImagesAutomatically = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            }

                            setOnTouchListener { v, _ ->
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                                false
                            }

                            addJavascriptInterface(
                                object {
                                    @JavascriptInterface
                                    fun onReady() {
                                        post {
                                            if (status == TurnstileStatus.LOADING) {
                                                status = TurnstileStatus.READY
                                            }
                                        }
                                    }

                                    @JavascriptInterface
                                    fun onToken(token: String) {
                                        post {
                                            val clean = token.trim()
                                            if (clean.isNotEmpty()) {
                                                CookieManager.getInstance().flush()
                                                status = TurnstileStatus.VERIFIED
                                                latestOnTokenReceived(clean)
                                            }
                                        }
                                    }

                                    @JavascriptInterface
                                    fun onExpired() {
                                        post {
                                            status = TurnstileStatus.EXPIRED
                                            latestOnTokenCleared()
                                        }
                                    }

                                    @JavascriptInterface
                                    fun onError() {
                                        post {
                                            status = TurnstileStatus.ERROR
                                            latestOnTokenCleared()
                                        }
                                    }
                                },
                                TURNSTILE_JS_BRIDGE,
                            )

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val target = request?.url?.toString().orEmpty()
                                    if (target.startsWith("http://", ignoreCase = true) ||
                                        target.startsWith("https://", ignoreCase = true)
                                    ) {
                                        runCatching { uriHandler.openUri(target) }
                                        return true
                                    }
                                    return false
                                }
                            }

                            webViewRef = this
                            tag = Triple(isDark, widgetSize, manualReloadCount)
                            loadDataWithBaseURL(
                                "${NodeSeekUrlNormalizer.ORIGIN}/",
                                htmlPayload,
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        }
                    },
                    update = { view ->
                        val desiredTag = Triple(isDark, widgetSize, manualReloadCount)
                        if (view.tag != desiredTag) {
                            view.tag = desiredTag
                            view.loadDataWithBaseURL(
                                "${NodeSeekUrlNormalizer.ORIGIN}/",
                                htmlPayload,
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        }
                    },
                )

                if (status == TurnstileStatus.LOADING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}

private const val TURNSTILE_JS_BRIDGE = "__NodePulseTurnstileBridge"

private fun buildTurnstileWidgetHtml(
    siteKey: String,
    isDark: Boolean,
    widgetSize: String,
): String {
    val theme = if (isDark) "dark" else "light"
    return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
          <style>
            html, body {
              margin: 0;
              padding: 0;
              width: 100%;
              height: 100%;
              background: transparent;
              display: flex;
              align-items: center;
              justify-content: center;
              overflow: hidden;
            }
            #cf-turnstile {
              width: 100%;
              display: flex;
              align-items: center;
              justify-content: center;
            }
          </style>
        </head>
        <body>
          <div id="cf-turnstile"></div>
          <script>
            var __widgetId = null;
            function __renderTurnstile() {
              if (!window.turnstile) return;
              var bridge = window.$TURNSTILE_JS_BRIDGE;
              try {
                __widgetId = window.turnstile.render('#cf-turnstile', {
                  sitekey: '$siteKey',
                  theme: '$theme',
                  size: '$widgetSize',
                  callback: function(token) {
                    if (bridge && bridge.onToken) bridge.onToken(String(token || ''));
                  },
                  'expired-callback': function() {
                    if (bridge && bridge.onExpired) bridge.onExpired();
                  },
                  'error-callback': function() {
                    if (bridge && bridge.onError) bridge.onError();
                  }
                });
                if (bridge && bridge.onReady) bridge.onReady();
              } catch (e) {
                if (bridge && bridge.onError) bridge.onError();
              }
            }
            window.__resetTurnstile = function() {
              try {
                if (window.turnstile && __widgetId !== null) {
                  window.turnstile.reset(__widgetId);
                  var bridge = window.$TURNSTILE_JS_BRIDGE;
                  if (bridge && bridge.onReady) bridge.onReady();
                  return 'ok';
                }
              } catch (_) {}
              return null;
            };
            window.onTurnstileApiLoad = __renderTurnstile;
          </script>
          <script src="https://challenges.cloudflare.com/turnstile/v0/api.js?onload=onTurnstileApiLoad&render=explicit" async defer onerror="window.$TURNSTILE_JS_BRIDGE && window.$TURNSTILE_JS_BRIDGE.onError && window.$TURNSTILE_JS_BRIDGE.onError()"></script>
        </body>
        </html>
    """.trimIndent()
}

private fun probeWindowConfigReadOnly(
    webView: WebView,
    onResult: (WebViewSessionStatus) -> Unit,
) {
    val pageUrl = webView.url.orEmpty()
    val host = runCatching { URI(pageUrl).host.orEmpty() }.getOrDefault("")
    if (!host.endsWith("nodeseek.com", ignoreCase = true)) return

    webView.evaluateJavascript(READ_ONLY_CONFIG_PROBE_JS) { rawJsonLiteral ->
        val decoded = decodeEvaluateJavascriptString(rawJsonLiteral) ?: return@evaluateJavascript
        runCatching {
            val obj = JSONObject(decoded)
            val ready = obj.optBoolean("ready", false)
            val uid = if (obj.has("uid") && !obj.isNull("uid")) obj.optLong("uid", 0L) else 0L
            val name = if (obj.has("name") && !obj.isNull("name")) obj.optString("name", "") else ""
            if (ready || uid > 0L) {
                CookieManager.getInstance().flush()
            }
            onResult(
                WebViewSessionStatus(
                    configReady = ready,
                    signedInUid = uid.takeIf { it > 0L },
                    signedInUsername = name.takeIf { it.isNotBlank() },
                    currentUrl = pageUrl,
                )
            )
        }
    }
}

private fun decodeEvaluateJavascriptString(raw: String?): String? {
    if (raw.isNullOrBlank() || raw == "null") return null
    return if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2) {
        runCatching { JSONObject("{\"v\":$raw}").getString("v") }.getOrNull()
    } else {
        raw
    }
}
