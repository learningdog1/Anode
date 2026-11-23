package com.nodeseek.app.data.network

import android.os.Build
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.TimeUnit
import kotlin.math.round

/**
 * Builds a dedicated [OkHttpClient] for Coil image requests.
 *
 * Security & CDN rules:
 * 1. Always attaches `Referer: https://www.nodeseek.com/` and browser image `Accept` header so
 *    forum-embedded image hosts (e.g., NodeImage / anti-hotlink CDNs) serve embedded images.
 * 2. Shares session cookies ONLY with `nodeseek.com` / `*.nodeseek.com` (for first-party avatars
 *    and stickers) and strips `Cookie` & `Authorization` headers on all external image hosts.
 * 3. Only advertises `image/avif` on Android 12+ (API 31+) where `ImageDecoder` can decode AVIF
 *    so CDNs using `Accept` content-negotiation serve WEBP/PNG/JPEG to older devices.
 * 4. Normalizes terminal-generated SVGs (such as Check.Place / IPQuality `ch` units without
 *    `viewBox`) so AndroidSVG/Coil can decode and scale them proportionally to screen width.
 */
object CoilHttpClientFactory {

    private const val MAX_SVG_NORMALIZE_BYTES = 2L * 1024L * 1024L
    private const val SVG_PEEK_BYTES = 4096L
    private val SVG_OPEN_TAG_REGEX = Regex("""<svg\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val SVG_VIEWBOX_ATTR_REGEX = Regex("""(?<![\w-])viewBox\s*=""", RegexOption.IGNORE_CASE)
    private val SVG_WIDTH_ATTR_REGEX = Regex("""(?<![\w-])width\s*=\s*(["'])([^"']+)\1""", RegexOption.IGNORE_CASE)
    private val SVG_HEIGHT_ATTR_REGEX = Regex("""(?<![\w-])height\s*=\s*(["'])([^"']+)\1""", RegexOption.IGNORE_CASE)
    private val SVG_ATTR_CH_REGEX = Regex(
        """((?<![\w-])(?:x|y|dx|dy|width|height)\s*=\s*["'])\s*(-?\d+(?:\.\d+)?)ch(\s*["'])""",
        RegexOption.IGNORE_CASE,
    )
    private val SVG_CSS_FONT_PX_REGEX = Regex("""font-size\s*:\s*(\d+(?:\.\d+)?)px\b""", RegexOption.IGNORE_CASE)
    private val SVG_LENGTH_REGEX = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*(px|em|ex|ch|pt)?\s*$""", RegexOption.IGNORE_CASE)

    val IMAGE_ACCEPT_HEADER: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
        } else {
            "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
        }

    fun create(
        cookieBridge: WebViewCookieBridge,
        userAgentProvider: UserAgentProvider,
        connectTimeoutSeconds: Long = 20L,
        readTimeoutSeconds: Long = 30L,
    ): OkHttpClient {
        val scopedCookieJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                if (NodeSeekUrlNormalizer.isNodeSeekHost(url.host)) {
                    cookieBridge.saveFromResponse(url, cookies)
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return if (NodeSeekUrlNormalizer.isNodeSeekHost(url.host)) {
                    cookieBridge.loadForRequest(url)
                } else {
                    emptyList()
                }
            }
        }

        val imageHeaderInterceptor = Interceptor { chain ->
            val original = chain.request()
            val isNodeSeek = NodeSeekUrlNormalizer.isNodeSeekHost(original.url.host)
            val builder = original.newBuilder()
                .header("User-Agent", userAgentProvider.getUserAgent())
                .header("Referer", "${NodeSeekUrlNormalizer.ORIGIN}/")
                .header("Accept", IMAGE_ACCEPT_HEADER)

            if (!isNodeSeek) {
                builder.removeHeader("Cookie")
                builder.removeHeader("Authorization")
            }

            val response = chain.proceed(builder.build())
            normalizeSvgResponseIfNeeded(response)
        }

        return OkHttpClient.Builder()
            .cookieJar(scopedCookieJar)
            .addInterceptor(imageHeaderInterceptor)
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun normalizeSvgResponseIfNeeded(response: Response): Response {
        if (!response.isSuccessful) return response
        val body = response.body ?: return response
        val mediaType = body.contentType()
        val isSvgContentType = mediaType?.subtype?.contains("svg", ignoreCase = true) == true
        val isSvgPath = response.request.url.encodedPath.endsWith(".svg", ignoreCase = true) &&
            (mediaType == null ||
                mediaType.type.equals("image", ignoreCase = true) ||
                (mediaType.type.equals("text", ignoreCase = true) && !mediaType.subtype.equals("html", ignoreCase = true)))
        if (!isSvgContentType && !isSvgPath) return response

        val contentLength = body.contentLength()
        if (contentLength > MAX_SVG_NORMALIZE_BYTES) return response

        val head = response.peekBody(SVG_PEEK_BYTES).string()
        val openMatch = SVG_OPEN_TAG_REGEX.find(head) ?: return response
        val openAttrs = openMatch.groupValues[1]
        val needsNormalization = !SVG_VIEWBOX_ATTR_REGEX.containsMatchIn(openAttrs) ||
            SVG_ATTR_CH_REGEX.containsMatchIn(head)
        if (!needsNormalization) return response

        // Bounded peek enforces MAX_SVG_NORMALIZE_BYTES even for chunked or transparently
        // gzip-decompressed responses where contentLength() == -1L, and keeps the original
        // ResponseBody unconsumed if the payload exceeds the limit.
        val peekedBytes = response.peekBody(MAX_SVG_NORMALIZE_BYTES + 1L).bytes()
        if (peekedBytes.size.toLong() > MAX_SVG_NORMALIZE_BYTES) return response

        val charset = mediaType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        val rawSvg = String(peekedBytes, charset)
        val normalizedSvg = normalizeSvgForAndroidSvg(rawSvg)

        // Original body is now being replaced with a freshly buffered copy; close the source stream.
        body.close()

        if (normalizedSvg == rawSvg) {
            return response.newBuilder()
                .body(peekedBytes.toResponseBody(mediaType))
                .build()
        }

        return response.newBuilder()
            .body(normalizedSvg.toResponseBody(mediaType))
            .removeHeader("Content-Length")
            .build()
    }

    internal fun normalizeSvgForAndroidSvg(svgText: String): String {
        val openMatch = SVG_OPEN_TAG_REGEX.find(svgText) ?: return svgText
        val openAttrs = openMatch.groupValues[1]
        val hasViewBox = SVG_VIEWBOX_ATTR_REGEX.containsMatchIn(openAttrs)
        val hasChUnits = SVG_ATTR_CH_REGEX.containsMatchIn(svgText)
        if (hasViewBox && !hasChUnits) return svgText

        val fontPx = SVG_CSS_FONT_PX_REGEX.find(svgText)
            ?.groupValues?.getOrNull(1)?.toFloatOrNull()
            ?.takeIf { it > 0f }
            ?: 16f

        val rawWidth = SVG_WIDTH_ATTR_REGEX.find(openAttrs)?.groupValues?.getOrNull(2)
        val rawHeight = SVG_HEIGHT_ATTR_REGEX.find(openAttrs)?.groupValues?.getOrNull(2)
        val parsedWidthPx = rawWidth?.let { parseSvgLengthPx(it, fontPx) }
        val parsedHeightPx = rawHeight?.let { parseSvgLengthPx(it, fontPx) }

        var rewrittenOpenTag = openMatch.value
        var modified = false

        // AndroidSVG 1.4 rejects `ch` length units (`SVGParseException`); convert to CSS px first.
        if (hasChUnits) {
            val chInPx = fontPx * 0.6f
            val updated = SVG_ATTR_CH_REGEX.replace(svgText) { m ->
                val num = m.groupValues[2].toFloatOrNull() ?: return@replace m.value
                "${m.groupValues[1]}${formatSvgNumber(num * chInPx)}px${m.groupValues[3]}"
            }
            if (updated != svgText) {
                val updatedOpenMatch = SVG_OPEN_TAG_REGEX.find(updated) ?: return updated
                rewrittenOpenTag = injectViewBoxIfMissing(
                    openTag = updatedOpenMatch.value,
                    hasViewBox = hasViewBox,
                    widthPx = parsedWidthPx,
                    heightPx = parsedHeightPx,
                )
                return updated.replaceRange(updatedOpenMatch.range, rewrittenOpenTag)
            }
        }

        if (!hasViewBox && parsedWidthPx != null && parsedHeightPx != null && parsedWidthPx > 0f && parsedHeightPx > 0f) {
            rewrittenOpenTag = injectViewBoxIfMissing(
                openTag = rewrittenOpenTag,
                hasViewBox = false,
                widthPx = parsedWidthPx,
                heightPx = parsedHeightPx,
            )
            if (rewrittenOpenTag != openMatch.value) {
                modified = true
            }
        }

        return if (modified) {
            svgText.replaceRange(openMatch.range, rewrittenOpenTag)
        } else {
            svgText
        }
    }

    private fun injectViewBoxIfMissing(
        openTag: String,
        hasViewBox: Boolean,
        widthPx: Float?,
        heightPx: Float?,
    ): String {
        if (hasViewBox || widthPx == null || heightPx == null || widthPx <= 0f || heightPx <= 0f) {
            return openTag
        }
        val viewBoxW = formatSvgNumber(widthPx)
        val viewBoxH = formatSvgNumber(heightPx)
        // Also normalize root width/height to px so CSS font-size em mismatch doesn't clip content.
        var tag = replaceFirstAttrValue(openTag, SVG_WIDTH_ATTR_REGEX, "width", "${viewBoxW}px")
        tag = replaceFirstAttrValue(tag, SVG_HEIGHT_ATTR_REGEX, "height", "${viewBoxH}px")
        val closeIdx = tag.lastIndexOf('>')
        if (closeIdx <= 0) return tag
        val insertIdx = if (tag[closeIdx - 1] == '/') closeIdx - 1 else closeIdx
        return buildString(tag.length + 32) {
            append(tag, 0, insertIdx)
            append(""" viewBox="0 0 """)
            append(viewBoxW)
            append(' ')
            append(viewBoxH)
            append('"')
            append(tag, insertIdx, tag.length)
        }
    }

    private fun replaceFirstAttrValue(
        tag: String,
        attrRegex: Regex,
        attrName: String,
        newValue: String,
    ): String {
        val match = attrRegex.find(tag) ?: return tag
        val q = match.groupValues[1]
        return tag.replaceRange(match.range, "$attrName=$q$newValue$q")
    }

    private fun parseSvgLengthPx(raw: String, fontPx: Float): Float? {
        val m = SVG_LENGTH_REGEX.matchEntire(raw) ?: return null
        val value = m.groupValues[1].toFloatOrNull() ?: return null
        return when (m.groupValues[2].lowercase()) {
            "", "px" -> value
            "ch" -> value * fontPx * 0.6f
            "em" -> value * fontPx
            "ex" -> value * fontPx * 0.5f
            "pt" -> value * (96f / 72f)
            else -> null
        }
    }

    private fun formatSvgNumber(value: Float): String {
        val rounded = round(value * 100f) / 100f
        return rounded.toString().removeSuffix(".0")
    }
}
