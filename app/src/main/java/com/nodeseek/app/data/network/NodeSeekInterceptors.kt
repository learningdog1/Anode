package com.nodeseek.app.data.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Computes NodeSeek's `x-dynamic-sign` request header used on `/api/vote/` and `/api/stardust/` routes.
 *
 * Exact NodeSeek web client formula:
 * `SHA-1( [METHOD, fullUrl, navigator.userAgent, rawBody].join("\n\n") )` as 40-char lowercase hex.
 */
fun computeDynamicSign(
    method: String,
    fullUrl: String,
    userAgent: String,
    rawBody: String = "",
): String {
    val signingInput = listOf(
        method.uppercase(),
        fullUrl,
        userAgent,
        rawBody,
    ).joinToString("\n\n")
    val digest = MessageDigest.getInstance("SHA-1").digest(signingInput.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(digest.size * 2)
    for (byte in digest) {
        val v = byte.toInt() and 0xFF
        sb.append(HEX_DIGITS[v ushr 4])
        sb.append(HEX_DIGITS[v and 0x0F])
    }
    return sb.toString()
}

private val HEX_DIGITS = charArrayOf(
    '0', '1', '2', '3', '4', '5', '6', '7',
    '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
)

/**
 * Generates the 16-character lowercase random hex `csrf-token` expected by NodeSeek's Markdown editor
 * on `/api/content/{new-discussion|edit-discussion|new-comment|edit-comment}`.
 */
fun generateEditorCsrfToken(): String =
    UUID.randomUUID().toString().replace("-", "").lowercase().take(16)

/**
 * Canonical URL normalization, `+` -> `%2B` query escaping, and `/jump?to=` redirect unwrapping.
 */
object NodeSeekUrlNormalizer {
    const val ORIGIN = "https://www.nodeseek.com"
    const val CANONICAL_HOST = "www.nodeseek.com"
    const val BARE_HOST = "nodeseek.com"
    const val RSS_URL = "https://rss.nodeseek.com/"
    const val NODE_IMAGE_ORIGIN = "https://api.nodeimage.com"
    const val TURNSTILE_SITE_KEY = "0x4AAAAAAAaNy7leGjewpVyR"

    private val originHttpUrl: HttpUrl = ORIGIN.toHttpUrl()

    fun isNodeSeekHost(host: String): Boolean {
        val lower = host.lowercase()
        return lower == CANONICAL_HOST || lower == BARE_HOST || lower.endsWith(".$BARE_HOST")
    }

    /**
     * Strictly verifies that [rawUrl] is an `https://` URL whose host is `nodeseek.com` or
     * a subdomain `*.nodeseek.com` (rejecting lookalike suffix hosts such as `evilnodeseek.com`
     * and non-HTTPS schemes such as `http://`, `file://`, or `javascript:`).
     */
    fun isNodeSeekHttpsUrl(rawUrl: String?): Boolean {
        val trimmed = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val parsed = trimmed.toHttpUrlOrNull() ?: return false
        return parsed.scheme.equals("https", ignoreCase = true) && isNodeSeekHost(parsed.host)
    }

    /**
     * NodeSeek treats literal `+` in query strings as a space `' '`.
     * Replaces `+` with `%2B` in the encoded query component while keeping `%20` spaces intact.
     */
    fun encodePlusInQuery(url: HttpUrl): HttpUrl {
        val encodedQuery = url.encodedQuery ?: return url
        if (!encodedQuery.contains('+')) return url
        return url.newBuilder()
            .encodedQuery(encodedQuery.replace("+", "%2B"))
            .build()
    }

    /**
     * Builds a URL against [baseOrigin] (defaults to `https://www.nodeseek.com`), attaching query
     * parameters and safely escaping literal `+` as `%2B`.
     */
    fun buildUrl(
        path: String,
        queryParams: List<Pair<String, String?>> = emptyList(),
        baseOrigin: HttpUrl = originHttpUrl,
    ): HttpUrl {
        val cleanPath = path.trim().let { if (it.startsWith("/")) it else "/$it" }
        val resolved = baseOrigin.resolve(cleanPath)
            ?: throw IllegalArgumentException("Invalid NodeSeek path: $path")
        val builder = resolved.newBuilder()
        for ((key, value) in queryParams) {
            if (value != null) {
                builder.addQueryParameter(key, value)
            }
        }
        return encodePlusInQuery(builder.build())
    }

    /**
     * Resolves relative paths (`/avatar/101.png`, `/post-123-1`) and bare `http://nodeseek.com` URLs
     * to canonical `https://www.nodeseek.com/...` URLs with `+` escaped as `%2B` in query strings.
     */
    fun normalizeNodeSeekUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""
        val parsed = when {
            trimmed.startsWith("//") -> "https:$trimmed".toHttpUrlOrNull()
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed.toHttpUrlOrNull()
            else -> originHttpUrl.resolve(if (trimmed.startsWith("/")) trimmed else "/$trimmed")
        } ?: return trimmed

        val normalizedHostUrl = if (
            parsed.host.equals(BARE_HOST, ignoreCase = true) ||
            parsed.host.equals(CANONICAL_HOST, ignoreCase = true)
        ) {
            parsed.newBuilder()
                .scheme("https")
                .host(CANONICAL_HOST)
                .build()
        } else {
            parsed
        }
        return encodePlusInQuery(normalizedHostUrl).toString()
    }

    /**
     * Unwraps NodeSeek's internal external-link redirector `https://www.nodeseek.com/jump?to=<url>`.
     * Only unwraps `http` and `https` targets to prevent `javascript:` or custom-scheme injection.
     */
    fun unwrapJumpUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""
        val candidate = when {
            trimmed.startsWith("/jump") -> "$ORIGIN$trimmed".toHttpUrlOrNull()
            else -> trimmed.toHttpUrlOrNull()
        } ?: return trimmed

        val hostLower = candidate.host.lowercase()
        if ((hostLower == CANONICAL_HOST || hostLower == BARE_HOST) && candidate.encodedPath == "/jump") {
            val target = candidate.queryParameter("to")?.trim()
            val targetUrl = target?.toHttpUrlOrNull()
            if (targetUrl != null && (targetUrl.scheme == "https" || targetUrl.scheme == "http")) {
                return targetUrl.toString()
            }
        }
        return trimmed
    }
}

/**
 * Thread-safe holder for `x-csrf-token` and `x-security-token` response headers issued by NodeSeek
 * (used by `/api/invite/buyOne`).
 */
class CsrfTokenStore {
    @Volatile
    var csrfToken: String? = null
        private set

    @Volatile
    var securityToken: String? = null
        private set

    fun captureFromResponse(response: Response) {
        response.header("x-security-token")?.takeIf { it.isNotBlank() }?.let { token ->
            securityToken = token
        }
        response.header("x-csrf-token")?.takeIf { it.isNotBlank() }?.let { token ->
            if (SECURITY_TOKEN_PATTERN.matches(token)) {
                securityToken = token
                csrfToken = null
            } else {
                csrfToken = token
            }
        }
    }

    fun seedCsrfToken(token: String?) {
        if (token != null && SECURITY_TOKEN_PATTERN.matches(token)) {
            securityToken = token
            csrfToken = null
        } else {
            csrfToken = token?.takeIf { it.isNotBlank() }
        }
    }

    fun seedSecurityToken(token: String?) {
        securityToken = token?.takeIf { it.isNotBlank() }
    }

    fun clear() {
        csrfToken = null
        securityToken = null
    }

    companion object {
        private val SECURITY_TOKEN_PATTERN = Regex("""^\w+\.\w+$""")
    }
}

/**
 * OkHttp [Interceptor] enforcing NodeSeek URL canonicalization, `User-Agent` synchronization,
 * CSRF headers (`csrf-token`, `x-csrf-challenge`, `x-csrf-token`), and SHA-1 `x-dynamic-sign`.
 *
 * @param additionalTargetHost Optional host (e.g., `MockWebServer` localhost) treated identically to
 *   `www.nodeseek.com` during unit testing without rewriting its scheme/host.
 */
class NodeSeekRequestInterceptor(
    private val userAgentProvider: UserAgentProvider,
    private val csrfTokenStore: CsrfTokenStore = CsrfTokenStore(),
    private val additionalTargetHost: String? = null,
    private val csrfTokenGenerator: () -> String = ::generateEditorCsrfToken,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val originalUrl = original.url
        val isCanonicalNodeSeek =
            originalUrl.host.equals(NodeSeekUrlNormalizer.CANONICAL_HOST, ignoreCase = true) ||
                originalUrl.host.equals(NodeSeekUrlNormalizer.BARE_HOST, ignoreCase = true)
        val isTargetApiHost = isCanonicalNodeSeek ||
            NodeSeekUrlNormalizer.isNodeSeekHost(originalUrl.host) ||
            (additionalTargetHost != null && originalUrl.host.equals(additionalTargetHost, ignoreCase = true))

        if (!isTargetApiHost) {
            return chain.proceed(original)
        }

        val normalizedUrl = originalUrl.let { url ->
            val hostAdjusted = if (isCanonicalNodeSeek) {
                url.newBuilder()
                    .scheme("https")
                    .host(NodeSeekUrlNormalizer.CANONICAL_HOST)
                    .build()
            } else {
                url
            }
            NodeSeekUrlNormalizer.encodePlusInQuery(hostAdjusted)
        }

        val userAgent = original.header("User-Agent")?.takeIf { it.isNotBlank() }
            ?: userAgentProvider.getUserAgent()
        val path = normalizedUrl.encodedPath
        val isRssHost = originalUrl.host.equals("rss.nodeseek.com", ignoreCase = true)
        val isApiPath = path.startsWith("/api/")

        val builder = original.newBuilder()
            .url(normalizedUrl)
            .header("User-Agent", userAgent)

        if (original.header("Accept-Language") == null) {
            builder.header("Accept-Language", userAgentProvider.getAcceptLanguage())
        }

        if (!isRssHost) {
            if (original.header("Referer") == null) {
                builder.header("Referer", "${NodeSeekUrlNormalizer.ORIGIN}/")
            }

            if (isApiPath) {
                if (original.header("X-Requested-With") == null) {
                    builder.header("X-Requested-With", "XMLHttpRequest")
                }
                if (original.method != "GET" && original.method != "HEAD" && original.header("Origin") == null) {
                    builder.header("Origin", NodeSeekUrlNormalizer.ORIGIN)
                }
                if (original.header("Sec-Fetch-Site") == null) {
                    builder.header("Sec-Fetch-Site", "same-origin")
                }
                if (original.header("Sec-Fetch-Mode") == null) {
                    builder.header("Sec-Fetch-Mode", "cors")
                }
                if (original.header("Sec-Fetch-Dest") == null) {
                    builder.header("Sec-Fetch-Dest", "empty")
                }
                val secToken = csrfTokenStore.securityToken
                if (!secToken.isNullOrBlank() && original.header("x-security-token") == null) {
                    builder.header("x-security-token", secToken)
                }
            } else {
                if (original.header("Sec-Fetch-Site") == null) {
                    builder.header("Sec-Fetch-Site", "same-origin")
                }
                if (original.header("Sec-Fetch-Mode") == null) {
                    builder.header("Sec-Fetch-Mode", "navigate")
                }
                if (original.header("Sec-Fetch-Dest") == null) {
                    builder.header("Sec-Fetch-Dest", "document")
                }
                if (original.header("Sec-Fetch-User") == null) {
                    builder.header("Sec-Fetch-User", "?1")
                }
                if (original.header("Upgrade-Insecure-Requests") == null) {
                    builder.header("Upgrade-Insecure-Requests", "1")
                }
            }
        }

        if (path in EDITOR_CSRF_PATHS && original.header("csrf-token") == null) {
            builder.header("csrf-token", csrfTokenGenerator())
        }

        if (path in SIMPLE_CSRF_CHALLENGE_PATHS && original.header("x-csrf-challenge") == null) {
            builder.header("x-csrf-challenge", "simple-token")
        }

        if (path == "/api/invite/buyOne" && original.header("x-csrf-token") == null) {
            val token = csrfTokenStore.csrfToken
                ?: throw NodeSeekApiException("请先打开官网的邀请页面完成验证，再返回购买。")
            builder.header("x-csrf-token", token)
        }

        if (path.startsWith("/api/vote/") || path.startsWith("/api/stardust/")) {
            val rawBody = readRequestBodyUtf8(original)
            val sign = computeDynamicSign(
                method = original.method,
                fullUrl = normalizedUrl.toString(),
                userAgent = userAgent,
                rawBody = rawBody,
            )
            builder.header("x-dynamic-sign", sign)
        }

        return try {
            val response = chain.proceed(builder.build())
            if (path == "/api/account/signOut") {
                csrfTokenStore.clear()
            } else {
                csrfTokenStore.captureFromResponse(response)
            }
            response
        } catch (err: Throwable) {
            if (path == "/api/account/signOut") {
                csrfTokenStore.clear()
            }
            throw err
        }
    }

    private fun readRequestBodyUtf8(request: Request): String {
        val body = request.body ?: return ""
        val buffer = Buffer()
        body.writeTo(buffer)
        return buffer.readUtf8()
    }

    companion object {
        private val EDITOR_CSRF_PATHS = setOf(
            "/api/content/new-discussion",
            "/api/content/edit-discussion",
            "/api/content/new-comment",
            "/api/content/edit-comment",
        )

        private val SIMPLE_CSRF_CHALLENGE_PATHS = setOf(
            "/api/account/signOut",
            "/api/avatar/upload",
            "/api/invite/buyOne",
        )
    }
}
