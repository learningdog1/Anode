package com.nodeseek.app.data.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Raised when NodeSeek or Cloudflare requires manual verification in an interactive first-party WebView.
 *
 * Never attempt to automate, script, or bypass this challenge; surface the interactive WebView sheet
 * so the user can complete verification normally.
 */
class CloudflareChallengeException(
    message: String = DEFAULT_MESSAGE,
    val statusCode: Int? = null,
    val url: String? = null,
) : IOException(message) {
    companion object {
        const val DEFAULT_MESSAGE = "官网需要完成验证，请打开官网后重试。"
    }
}

/**
 * Raised when an API call returns a structured logical error (`success: false`) or HTTP error.
 */
class NodeSeekApiException(
    message: String,
    val statusCode: Int? = null,
    val serverMessage: String? = null,
    val retryAfterSeconds: Long? = null,
) : IOException(message) {
    val isHighRiskAction: Boolean
        get() = serverMessage?.contains("high risk", ignoreCase = true) == true ||
            message?.contains("high risk", ignoreCase = true) == true

    val isOtpExpired: Boolean
        get() = serverMessage?.contains("OTP_EXPIRED_OR_NOT_EXIST", ignoreCase = true) == true ||
            message?.contains("OTP_EXPIRED_OR_NOT_EXIST", ignoreCase = true) == true
}

/**
 * Bridges `android.webkit.CookieManager.getInstance()` directly into OkHttp's [CookieJar],
 * with a thread-safe in-memory fallback when running inside pure JVM unit tests.
 */
class WebViewCookieBridge internal constructor(
    private val forceInMemory: Boolean,
    private val cookieManagerProvider: () -> CookieManager?,
) : CookieJar {

    constructor(forceInMemory: Boolean = false) : this(
        forceInMemory = forceInMemory,
        cookieManagerProvider = { CookieManager.getInstance() },
    )

    private val inMemoryCookies = CopyOnWriteArrayList<Cookie>()
    private val cachedSystemCookieManager = AtomicReference<CookieManager?>(null)

    /**
     * Resolves `CookieManager.getInstance()` lock-free so an IO thread triggering first-time
     * Chromium WebView provider initialization can never hold an app-level `by lazy` monitor
     * while the main thread calls [WebViewCookieBridge].
     */
    private fun resolveSystemCookieManager(): CookieManager? {
        if (forceInMemory) return null
        cachedSystemCookieManager.get()?.let { return it }
        val resolved = runCatching { cookieManagerProvider() }.getOrNull() ?: return null
        if (cachedSystemCookieManager.compareAndSet(null, resolved)) {
            runCatching { resolved.setAcceptCookie(true) }
            return resolved
        }
        return cachedSystemCookieManager.get() ?: resolved
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val replayable = cookies.filterNot { isUnreplayableCookie(it.name) }
        if (replayable.isEmpty()) return
        val manager = resolveSystemCookieManager()
        if (manager != null) {
            val urlString = url.toString()
            var wroteToSystem = false
            for (cookie in replayable) {
                runCatching {
                    manager.setCookie(urlString, cookie.toString())
                    wroteToSystem = true
                }
            }
            if (wroteToSystem) {
                flush()
                return
            }
        }
        saveInMemory(replayable)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val manager = resolveSystemCookieManager()
        if (manager != null) {
            val rawCookieHeader = runCatching { manager.getCookie(url.toString()) }.getOrNull()
            if (rawCookieHeader != null) {
                return parseCookieHeader(url, rawCookieHeader)
            }
            // Note: on unitTests.isReturnDefaultValues = true, manager.getCookie(...) returns null
            // even when nothing was stored in native WebKit; check in-memory fallback next.
        }
        return loadInMemory(url)
    }

    fun getRawCookieHeader(url: HttpUrl): String? {
        val cookies = loadForRequest(url)
        if (cookies.isEmpty()) return null
        return cookies.joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun hasClearanceCookie(url: HttpUrl = NodeSeekUrlNormalizer.ORIGIN.toHttpUrl()): Boolean =
        loadForRequest(url).any { it.name.equals("cf_clearance", ignoreCase = true) && it.value.isNotBlank() }

    fun hasSessionCookie(url: HttpUrl = NodeSeekUrlNormalizer.ORIGIN.toHttpUrl()): Boolean =
        loadForRequest(url).any { cookie ->
            cookie.value.isNotBlank() && (
                cookie.name.equals("session", ignoreCase = true) ||
                    cookie.name.equals("smac", ignoreCase = true) ||
                    cookie.name.equals("sid", ignoreCase = true)
                )
        }

    fun hasAnyAuthCookie(
        urls: List<HttpUrl> = listOf(
            NodeSeekUrlNormalizer.ORIGIN.toHttpUrl(),
            "https://${NodeSeekUrlNormalizer.BARE_HOST}/".toHttpUrl(),
        ),
    ): Boolean {
        for (target in urls) {
            for (cookie in loadForRequest(target)) {
                val lower = cookie.name.trim().lowercase()
                if (cookie.value.isBlank() ||
                    isUnreplayableCookie(lower) ||
                    isCloudflarePreservedCookie(lower) ||
                    lower == "colorscheme" ||
                    lower.startsWith("__cf") ||
                    lower.startsWith("_cf") ||
                    lower.startsWith("cf_")
                ) {
                    continue
                }
                return true
            }
        }
        return false
    }

    fun hasSessionOrClearanceCookie(url: HttpUrl = NodeSeekUrlNormalizer.ORIGIN.toHttpUrl()): Boolean =
        hasClearanceCookie(url) || hasSessionCookie(url)

    /**
     * Computes a stable fingerprint of meaningful auth & `cf_clearance` cookies across NodeSeek origins,
     * ignoring short-lived Cloudflare telemetry churn cookies (`__cf_bm`, `_cfuvid`, `cf_chl_*`) and `sortBy`.
     */
    fun cookieSnapshotHash(
        urls: List<HttpUrl> = listOf(
            NodeSeekUrlNormalizer.ORIGIN.toHttpUrl(),
            "https://${NodeSeekUrlNormalizer.BARE_HOST}/".toHttpUrl(),
        ),
    ): Int {
        val entries = LinkedHashMap<String, String>()
        for (target in urls) {
            for (cookie in loadForRequest(target)) {
                val lower = cookie.name.lowercase()
                if (isUnreplayableCookie(lower) ||
                    lower == "colorscheme" ||
                    (lower.startsWith("__cf") || lower.startsWith("_cf") || (lower.startsWith("cf_") && lower != "cf_clearance"))
                ) {
                    continue
                }
                entries[cookie.name] = cookie.value
            }
        }
        return entries.entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
            .hashCode()
    }

    /**
     * NodeSeek's `Set-Cookie: sortBy=postTime|replyTime` causes signed-out requests to `/`
     * and restricted `/post-*` pages to fail with nginx 503; strip and expire it while keeping
     * `?sortBy=` in explicit query strings.
     */
    fun dropUnreplayableCookies() {
        inMemoryCookies.removeAll { isUnreplayableCookie(it.name) }
        val manager = resolveSystemCookieManager() ?: return
        for (name in UNREPLAYABLE_COOKIE_NAMES) {
            runCatching {
                manager.setCookie(NodeSeekUrlNormalizer.ORIGIN, "$name=; Path=/; Max-Age=0")
                manager.setCookie(
                    NodeSeekUrlNormalizer.ORIGIN,
                    "$name=; Domain=.${NodeSeekUrlNormalizer.BARE_HOST}; Path=/; Max-Age=0",
                )
            }
        }
    }

    fun flush() {
        dropUnreplayableCookies()
        runCatching {
            resolveSystemCookieManager()?.flush()
        }
    }

    /**
     * Clears local NodeSeek authentication/session cookies across both `www.nodeseek.com` and
     * `.nodeseek.com` without hardcoding session cookie names. Preserves Cloudflare `cf_clearance`
     * unless [includeClearance] is `true` so guest browsing avoids an immediate challenge loop.
     */
    fun clearSessionCookies(includeClearance: Boolean = false) {
        inMemoryCookies.removeAll { cookie ->
            includeClearance || !isCloudflarePreservedCookie(cookie.name)
        }
        val manager = resolveSystemCookieManager() ?: return
        val targetUrls = listOf(
            NodeSeekUrlNormalizer.ORIGIN.toHttpUrl(),
            "https://${NodeSeekUrlNormalizer.BARE_HOST}/".toHttpUrl(),
        )
        val cookieNamesToClear = LinkedHashSet<String>()
        for (target in targetUrls) {
            for (cookie in loadForRequest(target)) {
                if (includeClearance || !isCloudflarePreservedCookie(cookie.name)) {
                    cookieNamesToClear.add(cookie.name)
                }
            }
        }
        for (name in cookieNamesToClear) {
            expireCookieAcrossNodeSeekHosts(manager, name)
        }
        flush()
    }

    fun clearAllCookies() {
        inMemoryCookies.clear()
        val manager = resolveSystemCookieManager() ?: return
        runCatching {
            manager.removeAllCookies(null)
            manager.flush()
        }
    }

    private fun expireCookieAcrossNodeSeekHosts(manager: CookieManager, name: String) {
        val bareOrigin = "https://${NodeSeekUrlNormalizer.BARE_HOST}"
        val expiredVariants = listOf(
            NodeSeekUrlNormalizer.ORIGIN to "$name=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT",
            NodeSeekUrlNormalizer.ORIGIN to "$name=; Domain=${NodeSeekUrlNormalizer.CANONICAL_HOST}; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT",
            NodeSeekUrlNormalizer.ORIGIN to "$name=; Domain=.${NodeSeekUrlNormalizer.BARE_HOST}; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT",
            bareOrigin to "$name=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT",
            bareOrigin to "$name=; Domain=.${NodeSeekUrlNormalizer.BARE_HOST}; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT",
        )
        for ((targetUrl, header) in expiredVariants) {
            runCatching {
                manager.setCookie(targetUrl, header)
            }
        }
    }

    private fun saveInMemory(newCookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (incoming in newCookies) {
            if (isUnreplayableCookie(incoming.name)) continue
            inMemoryCookies.removeAll { existing ->
                existing.name == incoming.name &&
                    existing.domain == incoming.domain &&
                    existing.path == incoming.path
            }
            if (incoming.expiresAt > now) {
                inMemoryCookies.add(incoming)
            }
        }
    }

    private fun loadInMemory(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        inMemoryCookies.removeAll { it.expiresAt <= now || isUnreplayableCookie(it.name) }
        return inMemoryCookies.filter { it.matches(url) }
    }

    companion object {
        private val UNREPLAYABLE_COOKIE_NAMES = setOf("sortBy")

        fun isUnreplayableCookie(name: String): Boolean =
            UNREPLAYABLE_COOKIE_NAMES.any { it.equals(name.trim(), ignoreCase = true) }

        fun isCloudflarePreservedCookie(name: String): Boolean =
            name.trim().equals("cf_clearance", ignoreCase = true)

        fun parseCookieHeader(url: HttpUrl, cookieHeader: String): List<Cookie> {
            if (cookieHeader.isBlank()) return emptyList()
            return cookieHeader
                .split(';')
                .mapNotNull { segment ->
                    val trimmed = segment.trim()
                    if (trimmed.isEmpty() || !trimmed.contains('=')) {
                        null
                    } else {
                        val parsed = Cookie.parse(url, trimmed) ?: run {
                            val eqIdx = trimmed.indexOf('=')
                            val name = trimmed.substring(0, eqIdx).trim()
                            val value = trimmed.substring(eqIdx + 1).trim()
                            if (name.isEmpty()) {
                                null
                            } else {
                                runCatching {
                                    Cookie.Builder()
                                        .name(name)
                                        .value(value)
                                        .domain(url.host)
                                        .path("/")
                                        .build()
                                }.getOrNull()
                            }
                        }
                        parsed?.takeUnless { isUnreplayableCookie(it.name) }
                    }
                }
        }
    }
}

/**
 * Pure detector for Cloudflare / website interactive verification gates.
 *
 * Strictly read-only detection: never solves or bypasses challenges.
 */
object CloudflareChallengeDetector {

    fun hasChallengeMitigationHeader(headers: Headers): Boolean =
        headers["cf-mitigated"]?.equals("challenge", ignoreCase = true) == true

    fun looksLikeChallengeHtml(rawBody: String): Boolean {
        if (rawBody.isBlank()) return false
        return rawBody.contains("cf-browser-verification", ignoreCase = true) ||
            rawBody.contains("challenges.cloudflare.com", ignoreCase = true) ||
            rawBody.contains("_cf_chl_opt", ignoreCase = true) ||
            rawBody.contains("Just a moment...", ignoreCase = true) ||
            rawBody.contains("请稍候", ignoreCase = false)
    }

    /**
     * Inspects an HTML response from NodeSeek and throws [CloudflareChallengeException] when
     * interactive verification is required.
     *
     * @param checkingPostAccess True for `/post-{id}-{page}` requests where `#nsk-body` may render
     *   a legitimate access-denied page (private / level required / sign-in required) without `temp-script`.
     * @param requireTempScript True when the target document is expected to carry `script#temp-script`.
     */
    fun inspectHtmlResponse(
        statusCode: Int,
        headers: Headers,
        htmlBody: String,
        url: String? = null,
        checkingPostAccess: Boolean = false,
        requireTempScript: Boolean = true,
    ) {
        if (hasChallengeMitigationHeader(headers)) {
            throw CloudflareChallengeException(statusCode = statusCode, url = url)
        }

        val doc by lazy { Jsoup.parse(htmlBody) }
        val isForumPage by lazy { doc.selectFirst("#nsk-body, #nsk-body-left") != null }

        if (statusCode == 403 && !isForumPage) {
            throw CloudflareChallengeException(statusCode = statusCode, url = url)
        }

        if (statusCode == 503 && !isForumPage && looksLikeChallengeHtml(htmlBody)) {
            throw CloudflareChallengeException(statusCode = statusCode, url = url)
        }

        if (requireTempScript && statusCode in 200..299) {
            val tempScript = doc.selectFirst("script#temp-script[type=application/json]")?.data()?.trim()
            if (tempScript.isNullOrEmpty()) {
                if (checkingPostAccess && isForumPage) {
                    // Legitimate forum error/access-restricted page; let NodeSeekHtmlParser inspect `#nsk-body-left`.
                    return
                }
                throw CloudflareChallengeException(statusCode = statusCode, url = url)
            }
        }
    }

    /**
     * Inspects a `/api/` JSON endpoint response and throws [CloudflareChallengeException] when
     * Cloudflare returns a challenge gate instead of API JSON.
     */
    fun inspectApiResponse(
        statusCode: Int,
        headers: Headers,
        rawBody: String,
        contentType: String? = headers["Content-Type"],
        url: String? = null,
    ) {
        if (hasChallengeMitigationHeader(headers)) {
            throw CloudflareChallengeException(statusCode = statusCode, url = url)
        }
        val trimmed = rawBody.trimStart()
        val looksLikeHtml = contentType?.contains("text/html", ignoreCase = true) == true ||
            trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true)

        if (statusCode == 403 && looksLikeHtml) {
            throw CloudflareChallengeException(statusCode = statusCode, url = url)
        }
        if ((statusCode == 403 || statusCode == 503) &&
            (trimmed.contains("cf-browser-verification", ignoreCase = true) ||
                trimmed.contains("challenges.cloudflare.com", ignoreCase = true) ||
                trimmed.contains("Just a moment...", ignoreCase = true))
        ) {
            throw CloudflareChallengeException(statusCode = statusCode, url = url)
        }
    }
}
