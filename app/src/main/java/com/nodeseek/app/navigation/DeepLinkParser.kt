package com.nodeseek.app.navigation

import android.net.Uri
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Typed internal navigation target resolved from an incoming `nodeseek.com` / `www.nodeseek.com` URL.
 */
sealed interface NodePulseDeepLink {
    data class TopicDetail(
        val topicId: Long,
        val initialFloor: Int = 0,
    ) : NodePulseDeepLink

    data class CategoryBoard(
        val slug: String,
    ) : NodePulseDeepLink

    data class UserSpace(
        val uid: Long,
    ) : NodePulseDeepLink

    data object CuratedFeed : NodePulseDeepLink

    data object CheckInBoard : NodePulseDeepLink

    data object Inbox : NodePulseDeepLink
}

/**
 * Pure JVM + Android deep-link parser for official NodeSeek URLs:
 * - `https://www.nodeseek.com/post-{id}-{page}` (plus optional `#floor` anchor)
 * - `https://www.nodeseek.com/categories/{slug}`
 * - `https://www.nodeseek.com/space/{uid}`
 * - `https://www.nodeseek.com/jump?to=...` (unwrapped if pointing back to NodeSeek)
 * - `/award`, `/board`, `/notification`
 */
object DeepLinkParser {

    private val SUPPORTED_HOSTS = setOf("nodeseek.com", "www.nodeseek.com")
    private val POST_PATH_REGEX = Regex("""^/post-(\d+)-(\d+)(?:\.html)?/?$""", RegexOption.IGNORE_CASE)
    private val CATEGORY_PATH_REGEX = Regex("""^/categories/([a-zA-Z0-9_-]+)/?$""", RegexOption.IGNORE_CASE)
    private val SPACE_PATH_REGEX = Regex("""^/space/(\d+)(?:/.*)?$""", RegexOption.IGNORE_CASE)
    private val FLOOR_FRAGMENT_REGEX = Regex("""^(?:floor-|comment-)?(\d+)$""", RegexOption.IGNORE_CASE)

    fun parseUri(uri: Uri?): NodePulseDeepLink? =
        uri?.toString()?.let(::parseUrl)

    fun parseUrl(rawUrl: String?, maxRedirectDepth: Int = 2): NodePulseDeepLink? {
        if (rawUrl.isNullOrBlank() || maxRedirectDepth < 0) return null
        val trimmed = rawUrl.trim()
        val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return null

        val scheme = parsed.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null

        val host = parsed.host?.lowercase() ?: return null
        if (host !in SUPPORTED_HOSTS) return null

        val path = parsed.path?.ifBlank { "/" } ?: "/"

        // Unwrap /jump?to=<encoded> links when they point back to an internal NodeSeek page
        if (path.equals("/jump", ignoreCase = true)) {
            val target = extractQueryParam(parsed.rawQuery, "to")
            if (!target.isNullOrBlank()) {
                return parseUrl(target, maxRedirectDepth = maxRedirectDepth - 1)
            }
            return null
        }

        POST_PATH_REGEX.matchEntire(path)?.let { match ->
            val topicId = match.groupValues[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
            val page = match.groupValues[2].toIntOrNull()?.coerceAtLeast(1) ?: 1
            val fragmentFloor = parsed.rawFragment
                ?.trim()
                ?.let { frag -> FLOOR_FRAGMENT_REGEX.matchEntire(frag)?.groupValues?.get(1)?.toIntOrNull() }
                ?.takeIf { it >= 0 }

            val initialFloor = when {
                fragmentFloor != null -> fragmentFloor
                page > 1 -> (page - 1) * 10 + 1
                else -> 0
            }
            return NodePulseDeepLink.TopicDetail(
                topicId = topicId,
                initialFloor = initialFloor,
            )
        }

        CATEGORY_PATH_REGEX.matchEntire(path)?.let { match ->
            val rawSlug = match.groupValues[1].trim()
            val normalizedSlug = if (rawSlug.equals("all", ignoreCase = true)) "" else rawSlug
            return NodePulseDeepLink.CategoryBoard(slug = normalizedSlug)
        }

        SPACE_PATH_REGEX.matchEntire(path)?.let { match ->
            val uid = match.groupValues[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
            return NodePulseDeepLink.UserSpace(uid = uid)
        }

        val normalizedPath = if (path.length > 1) path.trimEnd('/') else path
        return when {
            normalizedPath.equals("/award", ignoreCase = true) -> NodePulseDeepLink.CuratedFeed
            normalizedPath.equals("/board", ignoreCase = true) -> NodePulseDeepLink.CheckInBoard
            normalizedPath.equals("/notification", ignoreCase = true) -> NodePulseDeepLink.Inbox
            else -> null
        }
    }

    private fun extractQueryParam(rawQuery: String?, key: String): String? {
        if (rawQuery.isNullOrBlank()) return null
        for (pair in rawQuery.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val k = pair.substring(0, idx)
            if (k.equals(key, ignoreCase = true)) {
                val encodedValue = pair.substring(idx + 1)
                return runCatching {
                    URLDecoder.decode(encodedValue, StandardCharsets.UTF_8.name())
                }.getOrDefault(encodedValue)
            }
        }
        return null
    }
}
