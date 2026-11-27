package com.nodeseek.app.navigation

import androidx.compose.ui.platform.UriHandler
import com.nodeseek.app.ui.components.unwrapNodeSeekJumpUrl

/**
 * Resolves a clicked URL inside Markdown content, routing first-party `nodeseek.com` links
 * (`post-*`, `/space/{uid}`, `/categories/{slug}`, `/jump?to=...`) natively inside Anode
 * before falling back to the system browser [UriHandler].
 */
fun openNodeSeekOrExternalUrl(
    rawUrl: String,
    uriHandler: UriHandler,
    currentTopicId: Long? = null,
    onJumpFloorInCurrentTopic: ((Int) -> Unit)? = null,
    onOpenTopic: ((topicId: Long, initialFloor: Int, titleHint: String) -> Unit)? = null,
    onOpenUser: ((uid: Long, usernameHint: String) -> Unit)? = null,
    onOpenCategory: ((slug: String) -> Unit)? = null,
    onOpenCurated: (() -> Unit)? = null,
    onOpenCheckIn: (() -> Unit)? = null,
    onOpenInbox: (() -> Unit)? = null,
    onOpenLuckyDraw: ((NodePulseDeepLink.LuckyDraw) -> Unit)? = null,
) {
    val normalizedUrl = normalizeNodeSeekUrl(rawUrl)
    when (val parsed = DeepLinkParser.parseUrl(normalizedUrl)) {
        is NodePulseDeepLink.TopicDetail -> {
            if (currentTopicId != null &&
                parsed.topicId == currentTopicId &&
                onJumpFloorInCurrentTopic != null
            ) {
                onJumpFloorInCurrentTopic(parsed.initialFloor)
            } else if (onOpenTopic != null) {
                onOpenTopic(parsed.topicId, parsed.initialFloor, "")
            } else {
                openSafeExternalUri(uriHandler, normalizedUrl)
            }
        }
        is NodePulseDeepLink.UserSpace -> {
            if (onOpenUser != null) {
                onOpenUser(parsed.uid, "")
            } else {
                openSafeExternalUri(uriHandler, normalizedUrl)
            }
        }
        is NodePulseDeepLink.CategoryBoard -> {
            if (onOpenCategory != null) {
                onOpenCategory(parsed.slug)
            } else {
                openSafeExternalUri(uriHandler, normalizedUrl)
            }
        }
        NodePulseDeepLink.CuratedFeed -> {
            if (onOpenCurated != null) {
                onOpenCurated()
            } else if (onOpenCategory != null) {
                onOpenCategory("")
            } else {
                openSafeExternalUri(uriHandler, normalizedUrl)
            }
        }
        NodePulseDeepLink.CheckInBoard -> {
            if (onOpenCheckIn != null) {
                onOpenCheckIn()
            } else {
                openSafeExternalUri(uriHandler, normalizedUrl)
            }
        }
        NodePulseDeepLink.Inbox -> {
            if (onOpenInbox != null) {
                onOpenInbox()
            } else {
                openSafeExternalUri(uriHandler, normalizedUrl)
            }
        }
        is NodePulseDeepLink.LuckyDraw -> {
            if (onOpenLuckyDraw != null) {
                onOpenLuckyDraw(parsed)
            } else {
                openSafeExternalUri(uriHandler, normalizedUrl)
            }
        }
        null -> {
            openSafeExternalUri(uriHandler, normalizedUrl)
        }
    }
}

internal fun normalizeNodeSeekUrl(rawUrl: String): String {
    val unwrapped = unwrapNodeSeekJumpUrl(rawUrl).trim()
    return when {
        unwrapped.startsWith("//") -> "https:$unwrapped"
        unwrapped.startsWith("/") -> "https://www.nodeseek.com$unwrapped"
        else -> unwrapped
    }
}

internal fun isSafeExternalHttpUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isISOControl() }) return false
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return false
    val scheme = trimmed.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return false
    val remainder = trimmed.substring(schemeEnd + 3)
    val authorityEnd = remainder.indexOfFirst { it == '/' || it == '?' || it == '#' }
        .let { if (it < 0) remainder.length else it }
    val authority = remainder.substring(0, authorityEnd)
    return authority.isNotBlank() && !authority.any { it.isWhitespace() || it.isISOControl() }
}

private fun openSafeExternalUri(
    uriHandler: UriHandler,
    url: String,
) {
    if (isSafeExternalHttpUrl(url)) {
        runCatching { uriHandler.openUri(url) }
    }
}
