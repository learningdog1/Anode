package com.nodeseek.app.data.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class SameOriginHttpResponse(
    val statusCode: Int,
    val headers: Headers = Headers.headersOf(),
    val contentType: String? = null,
    val body: String,
)

/**
 * First-party same-origin HTTP transport backed by Android's Chromium `WebView`.
 *
 * Why this exists:
 * 1. Cloudflare frequently binds `cf_clearance` (obtained by the user inside `WebViewSessionSheet`)
 *    to Chromium BoringSSL's TLS/HTTP2 fingerprint, rejecting Java/Conscrypt `OkHttpClient` replays.
 * 2. NodeSeek's official frontend wraps `window.fetch` on `www.nodeseek.com` to attach first-party
 *    request integrity context; raw HTTP clients without this context may receive
 *    `{"success": false, "message": "high risk action"}` on `/api/statistics/{upvote|like|dislike|collection}`.
 *
 * Strict Non-Bypass Guarantees:
 * - NEVER clicks, scripts, solves, waits out, or bypasses any CAPTCHA / Cloudflare Turnstile /
 *   anti-bot challenge.
 * - Read-only checks `window.__config__` / `#nsk-body`; if the origin is gated by a challenge,
 *   immediately aborts and throws [CloudflareChallengeException] so the user can verify manually
 *   in the visible `WebViewSessionSheet`.
 */
interface SameOriginTransport {
    fun canAttemptWithExistingClearance(): Boolean
    fun markVerifiedByUser()
    fun clearSessionContext() {}
    fun getUserAgent(): String? = null
    fun getCsrfToken(): String? = null
    suspend fun fetchHtml(url: String): SameOriginHttpResponse
    suspend fun executeApiRequest(
        method: String,
        url: String,
        refererUrl: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): SameOriginHttpResponse
}

class WebViewSameOriginActionRunner(
    context: Context,
    private val cookieBridge: WebViewCookieBridge,
    private val userAgentProvider: UserAgentProvider,
    private val csrfTokenStore: CsrfTokenStore,
) : SameOriginTransport {

    internal sealed interface PageLoadOutcome {
        data class Finished(val url: String?) : PageLoadOutcome
        data class NetworkError(val message: String) : PageLoadOutcome
    }

    private val appContext: Context = context.applicationContext ?: context
    private val mutex = Mutex()
    private val pendingFetchCallbacks = ConcurrentHashMap<String, CompletableDeferred<String>>()

    @Volatile
    private var verifiedInProcess: Boolean = false

    private var webView: WebView? = null
    private var activePageLoadDeferred: CompletableDeferred<PageLoadOutcome>? = null

    override fun canAttemptWithExistingClearance(): Boolean =
        verifiedInProcess || cookieBridge.hasSessionOrClearanceCookie()

    override fun markVerifiedByUser() {
        verifiedInProcess = true
    }

    override fun getUserAgent(): String? = userAgentProvider.getUserAgent(appContext)

    override fun getCsrfToken(): String? = csrfTokenStore.csrfToken

    override fun clearSessionContext() {
        verifiedInProcess = false
        csrfTokenStore.clear()
        val existingWebView = webView ?: return
        existingWebView.post {
            runCatching {
                existingWebView.evaluateJavascript(
                    "try { window.localStorage && window.localStorage.removeItem('security_token'); } catch (_) {}",
                    null,
                )
            }
        }
    }

    override suspend fun fetchHtml(url: String): SameOriginHttpResponse =
        executeInternal(
            method = "GET",
            url = url,
            refererUrl = "${NodeSeekUrlNormalizer.ORIGIN}/",
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Cache-Control" to "no-cache",
            ),
            body = null,
        )

    override suspend fun executeApiRequest(
        method: String,
        url: String,
        refererUrl: String,
        headers: Map<String, String>,
        body: String?,
    ): SameOriginHttpResponse =
        executeInternal(
            method = method,
            url = url,
            refererUrl = refererUrl,
            headers = headers,
            body = body,
        )

    private suspend fun executeInternal(
        method: String,
        url: String,
        refererUrl: String,
        headers: Map<String, String>,
        body: String?,
    ): SameOriginHttpResponse = mutex.withLock {
        withContext(Dispatchers.Main) {
            val wv = getOrCreateWebViewOnMain()
            val bootstrapUrl = normalizeSameOriginBootstrapUrl(refererUrl, fallbackUrl = url)
            ensureSameOriginReadyOnMain(wv, bootstrapUrl)

            val targetHttpUrl = url.toHttpUrlOrNull()
                ?: throw IOException("Invalid NodeSeek URL: $url")
            val pathWithQuery = buildString {
                append(targetHttpUrl.encodedPath)
                targetHttpUrl.encodedQuery?.takeIf { it.isNotEmpty() }?.let { q ->
                    append('?').append(q)
                }
            }
            val refererPath = bootstrapUrl.toHttpUrlOrNull()?.let { ref ->
                buildString {
                    append(ref.encodedPath.ifEmpty { "/" })
                    ref.encodedQuery?.takeIf { it.isNotEmpty() }?.let { q ->
                        append('?').append(q)
                    }
                }
            } ?: "/"

            val reqId = createRequestId()
            val deferred = CompletableDeferred<String>()
            pendingFetchCallbacks[reqId] = deferred

            val filteredHeadersJson = JSONObject().apply {
                for ((k, v) in headers) {
                    val lower = k.lowercase()
                    if (lower !in BROWSER_MANAGED_HEADER_NAMES) {
                        put(k, v)
                    }
                }
            }
            val configObj = JSONObject().apply {
                put("reqId", reqId)
                put("url", pathWithQuery)
                put("method", method.uppercase())
                put("refererPath", refererPath)
                put("headers", filteredHeadersJson)
                if (body != null) {
                    put("body", body)
                } else {
                    put("body", JSONObject.NULL)
                }
            }

            val script = buildSameOriginFetchScript(configObj.toString())
            val rawResultJson = try {
                wv.evaluateJavascript(script, null)
                withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    deferred.await()
                } ?: throw IOException("同源网页请求超时，请稍后重试。")
            } finally {
                pendingFetchCallbacks.remove(reqId)
            }

            cookieBridge.flush()
            parseBridgeResponse(rawResultJson, url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateWebViewOnMain(): WebView {
        webView?.let { return it }
        return WebView(appContext).apply {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                loadsImagesAutomatically = false
                blockNetworkImage = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                val configuredUa = userAgentProvider.getUserAgent(appContext)
                if (!userAgentProvider.isSystemWebViewDefault && configuredUa.isNotBlank()) {
                    userAgentString = configuredUa
                }
            }

            addJavascriptInterface(FetchJavascriptBridge(), JS_BRIDGE_NAME)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    // Never navigate away to non-HTTPS or non-NodeSeek hosts in background same-origin transport
                    return !isAllowedNavigationUrl(request?.url?.toString())
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        val desc = error?.description?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                            ?: "网络连接失败"
                        activePageLoadDeferred?.complete(PageLoadOutcome.NetworkError(desc))
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?,
                ) {
                    handler?.cancel()
                    activePageLoadDeferred?.complete(PageLoadOutcome.NetworkError("安全证书校验失败"))
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    cookieBridge.flush()
                    activePageLoadDeferred?.complete(PageLoadOutcome.Finished(url))
                }
            }
        }.also { created ->
            webView = created
        }
    }

    private suspend fun ensureSameOriginReadyOnMain(wv: WebView, bootstrapUrl: String) {
        val currentUrl = wv.url
        if (isAllowedNavigationUrl(currentUrl) &&
            currentUrl?.toHttpUrlOrNull()?.host.equals(NodeSeekUrlNormalizer.CANONICAL_HOST, ignoreCase = true)
        ) {
            if (probeOriginReadyOnMain(wv)) {
                return
            }
        }

        val loadDeferred = CompletableDeferred<PageLoadOutcome>()
        activePageLoadDeferred = loadDeferred
        val outcome = try {
            wv.loadUrl(bootstrapUrl)
            withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) {
                loadDeferred.await()
            }
        } catch (c: CancellationException) {
            wv.stopLoading()
            throw c
        } finally {
            if (activePageLoadDeferred === loadDeferred) {
                activePageLoadDeferred = null
            }
        }

        val ready = when (outcome) {
            null -> {
                wv.stopLoading()
                throw IOException("同源网页加载超时，请检查网络后重试。")
            }
            is PageLoadOutcome.NetworkError -> {
                wv.stopLoading()
                throw IOException(outcome.message)
            }
            is PageLoadOutcome.Finished -> probeOriginReadyOnMain(wv)
        }

        if (!ready) {
            wv.stopLoading()
            throw CloudflareChallengeException(url = bootstrapUrl)
        }
    }

    private suspend fun probeOriginReadyOnMain(wv: WebView): Boolean {
        val probeDeferred = CompletableDeferred<Boolean>()
        wv.evaluateJavascript(ORIGIN_READY_PROBE_JS) { rawLiteral ->
            val ready = rawLiteral?.trim() == "true"
            probeDeferred.complete(ready)
        }
        return try {
            withTimeoutOrNull(3_000L) { probeDeferred.await() } ?: false
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            false
        }
    }

    private fun parseBridgeResponse(rawJson: String, requestUrl: String): SameOriginHttpResponse =
        parseBridgeResponseForTest(
            rawJson = rawJson,
            requestUrl = requestUrl,
            csrfTokenStore = csrfTokenStore,
        )

    private fun normalizeSameOriginBootstrapUrl(refererUrl: String, fallbackUrl: String): String {
        val candidate = refererUrl.trim().ifEmpty { fallbackUrl }
        val parsed = candidate.toHttpUrlOrNull()
        return if (
            parsed != null &&
            NodeSeekUrlNormalizer.isNodeSeekHost(parsed.host) &&
            !parsed.encodedPath.startsWith("/api/") &&
            !parsed.encodedPath.contains("signIn", ignoreCase = true)
        ) {
            parsed.newBuilder()
                .scheme("https")
                .host(NodeSeekUrlNormalizer.CANONICAL_HOST)
                .build()
                .toString()
        } else {
            "${NodeSeekUrlNormalizer.ORIGIN}/"
        }
    }

    private inner class FetchJavascriptBridge {
        @JavascriptInterface
        fun onComplete(reqId: String, payloadJson: String) {
            pendingFetchCallbacks.remove(reqId)?.complete(payloadJson)
        }
    }

    companion object {
        private const val JS_BRIDGE_NAME = "__NodePulseFetchBridge"
        private const val PAGE_LOAD_TIMEOUT_MS = 12_000L
        private const val FETCH_TIMEOUT_MS = 15_000L

        internal fun isAllowedNavigationUrl(rawUrl: String?): Boolean =
            NodeSeekUrlNormalizer.isNodeSeekHttpsUrl(rawUrl)

        internal fun createRequestId(): String =
            "req_${UUID.randomUUID().toString().replace("-", "")}"

        internal fun parseBridgeResponseForTest(
            rawJson: String,
            requestUrl: String,
            csrfTokenStore: CsrfTokenStore = CsrfTokenStore(),
        ): SameOriginHttpResponse {
            val obj = runCatching { JSONObject(rawJson) }.getOrNull()
                ?: throw IOException("无法解析网页同源响应")
            if (!obj.optBoolean("ok", false)) {
                val errMsg = obj.optString("error", "同源请求失败")
                if (errMsg.contains("challenge", ignoreCase = true)) {
                    throw CloudflareChallengeException(url = requestUrl)
                }
                throw IOException(errMsg)
            }
            val status = obj.optInt("status", 0)
            val contentType = obj.optString("contentType", "").takeIf { it.isNotBlank() }
            val cfMitigated = obj.optString("cfMitigated", "").takeIf { it.isNotBlank() }
            val retryAfter = obj.optString("retryAfter", "").takeIf { it.isNotBlank() }
            val respCsrf = obj.optString("csrfToken", "").takeIf { it.isNotBlank() }
            val respSec = obj.optString("securityToken", "").takeIf { it.isNotBlank() }
            val body = obj.optString("body", "")

            if (!respSec.isNullOrBlank()) {
                csrfTokenStore.seedSecurityToken(respSec)
            }
            if (!respCsrf.isNullOrBlank()) {
                csrfTokenStore.seedCsrfToken(respCsrf)
            }

            val headersBuilder = Headers.Builder()
            if (contentType != null) headersBuilder.add("Content-Type", contentType)
            if (cfMitigated != null) headersBuilder.add("cf-mitigated", cfMitigated)
            if (retryAfter != null) headersBuilder.add("Retry-After", retryAfter)

            return SameOriginHttpResponse(
                statusCode = status,
                headers = headersBuilder.build(),
                contentType = contentType,
                body = body,
            )
        }

        private val BROWSER_MANAGED_HEADER_NAMES = setOf(
            "host",
            "origin",
            "referer",
            "user-agent",
            "cookie",
            "content-length",
            "accept-language",
            "sec-fetch-site",
            "sec-fetch-mode",
            "sec-fetch-dest",
            "sec-fetch-user",
            "upgrade-insecure-requests",
        )

        private const val ORIGIN_READY_PROBE_JS = """
            (function() {
              try {
                if (document.readyState === 'loading') return false;
                if (window._cf_chl_opt) return false;
                var title = String(document.title || '');
                if (title.indexOf('Just a moment') !== -1 || title.indexOf('请稍候') !== -1) return false;
                if (document.getElementById('challenge-running') || document.getElementById('cf-please-wait')) return false;
                return Boolean(window.__config__) ||
                  Boolean(document.querySelector('script#temp-script')) ||
                  Boolean(document.querySelector('#nsk-body, #nsk-body-left'));
              } catch (e) {
                return false;
              }
            })();
        """

        private fun buildSameOriginFetchScript(serializedConfig: String): String = """
            (function() {
              var cfg = $serializedConfig;
              var bridge = window.$JS_BRIDGE_NAME;
              function finish(payload) {
                try {
                  if (bridge && bridge.onComplete) {
                    bridge.onComplete(cfg.reqId, JSON.stringify(payload));
                  }
                } catch (_) {}
              }
              try {
                if (window._cf_chl_opt) {
                  finish({ ok: false, error: 'challenge_required' });
                  return;
                }
                if (cfg.refererPath) {
                  try { history.replaceState(null, '', cfg.refererPath); } catch (_) {}
                }
                var headersObj = cfg.headers || {};
                try {
                  var lsSec = window.localStorage ? window.localStorage.getItem('security_token') : null;
                  if (lsSec && !headersObj['x-security-token']) {
                    headersObj['x-security-token'] = lsSec;
                  }
                } catch (_) {}
                var init = {
                  method: cfg.method || 'GET',
                  credentials: 'same-origin',
                  cache: 'no-store',
                  headers: headersObj
                };
                if (cfg.refererPath) {
                  init.referrer = cfg.refererPath;
                }
                if (cfg.body !== null && cfg.body !== undefined && cfg.method !== 'GET' && cfg.method !== 'HEAD') {
                  init.body = cfg.body;
                }
                window.fetch(cfg.url, init).then(function(resp) {
                  return resp.text().then(function(text) {
                    var storedSec = null;
                    try {
                      storedSec = resp.headers.get('x-security-token') ||
                        (window.localStorage ? window.localStorage.getItem('security_token') : null);
                    } catch (_) {}
                    finish({
                      ok: true,
                      status: resp.status,
                      contentType: resp.headers.get('content-type') || '',
                      cfMitigated: resp.headers.get('cf-mitigated') || '',
                      retryAfter: resp.headers.get('retry-after') || '',
                      csrfToken: resp.headers.get('x-csrf-token') || '',
                      securityToken: storedSec || '',
                      body: text
                    });
                  });
                }).catch(function(err) {
                  finish({ ok: false, error: String(err && err.message ? err.message : err) });
                });
              } catch (outerErr) {
                finish({ ok: false, error: String(outerErr && outerErr.message ? outerErr.message : outerErr) });
              }
            })();
        """.trimIndent()
    }
}
