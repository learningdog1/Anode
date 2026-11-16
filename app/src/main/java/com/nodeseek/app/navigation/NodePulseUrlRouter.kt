package com.nodeseek.app.navigation

import androidx.compose.ui.platform.UriHandler
import com.nodeseek.app.ui.components.unwrapNodeSeekJumpUrl

/**
 * Resolves a clicked URL inside Markdown content, routing first-party `nodeseek.com` links
 * (`post-*`, `/space/{uid}`, `/categories/{slug}`, `/jump?to=...`) natively inside NodePulse
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
    onOpenCheckIn: (() -> Unit)? = null,
    onOpenInbox: (() -> Unit)? = null,
) {
    val unwrapped = unwrapNodeSeekJumpUrl(rawUrl)
    val normalizedUrl = if (unwrapped.startsWith("/")) {
        "https://www.nodeseek.com$unwrapped"
    } else {
        unwrapped
    }
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
                runCatching { uriHandler.openUri(normalizedUrl) }
            }
        }
        is NodePulseDeepLink.UserSpace -> {
            if (onOpenUser != null) {
                onOpenUser(parsed.uid, "")
            } else {
                runCatching { uriHandler.openUri(normalizedUrl) }
            }
        }
        is NodePulseDeepLink.CategoryBoard -> {
            if (onOpenCategory != null) {
                onOpenCategory(parsed.slug)
            } else {
                runCatching { uriHandler.openUri(normalizedUrl) }
            }
        }
        NodePulseDeepLink.CheckInBoard -> {
            if (onOpenCheckIn != null) {
                onOpenCheckIn()
            } else {
                runCatching { uriHandler.openUri(normalizedUrl) }
            }
        }
        NodePulseDeepLink.Inbox -> {
            if (onOpenInbox != null) {
                onOpenInbox()
            } else {
                runCatching { uriHandler.openUri(normalizedUrl) }
            }
        }
        null -> {
            runCatching { uriHandler.openUri(normalizedUrl) }
        }
    }
}
