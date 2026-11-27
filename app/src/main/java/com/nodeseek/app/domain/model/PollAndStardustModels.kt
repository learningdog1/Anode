package com.nodeseek.app.domain.model

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

/**
 * Typed AST target for embedded `nsapp://` interactive cards in Markdown posts.
 */
sealed interface NsAppWidgetTarget {
    val rawUrl: String

    data class Vote(
        val voteId: Long,
        override val rawUrl: String,
    ) : NsAppWidgetTarget

    data class StardustReceive(
        val receiverUid: Long,
        val suggestedAmount: Int? = null,
        val refId: Long = 0L,
        val once: Boolean = false,
        val note: String = "",
        override val rawUrl: String,
    ) : NsAppWidgetTarget
}

/**
 * Pure-Kotlin parser (safe on JVM without Android `Uri`) for NodeSeek `nsapp://` widget URLs:
 * - `nsapp://vote?id=42` (or `voteId=42`)
 * - `nsapp://stardust-receive?uid=101&amount=10&ref=123&once=1&note=...`
 */
fun parseNsAppWidgetTarget(rawUrl: String): NsAppWidgetTarget? {
    val trimmed = rawUrl.trim()
    if (!trimmed.startsWith("nsapp://", ignoreCase = true)) return null

    val afterScheme = trimmed.substring("nsapp://".length)
    val qIdx = afterScheme.indexOf('?')
    val hIdx = afterScheme.indexOf('#')
    val pathEnd = listOf(qIdx, hIdx).filter { it >= 0 }.minOrNull() ?: afterScheme.length
    val hostAndPath = afterScheme.substring(0, pathEnd).trim().trim('/').lowercase()

    val rawQuery = if (qIdx >= 0) {
        val queryEnd = if (hIdx > qIdx) hIdx else afterScheme.length
        afterScheme.substring(qIdx + 1, queryEnd)
    } else {
        ""
    }
    val params = parsePureQueryParams(rawQuery)

    return when (hostAndPath) {
        "vote" -> {
            val voteId = (params["id"] ?: params["voteid"])
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: return null
            NsAppWidgetTarget.Vote(
                voteId = voteId,
                rawUrl = trimmed,
            )
        }
        "stardust-receive" -> {
            val receiverUid = (params["uid"] ?: params["receiver_id"] ?: params["member_id"])
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: return null
            val suggestedAmount = params["amount"]
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
            val refId = params["ref"]
                ?.toLongOrNull()
                ?.takeIf { it >= 0L }
                ?: 0L
            val onceRaw = params["once"]?.trim()?.lowercase()
            val once = onceRaw == "1" || onceRaw == "true"
            val note = params["note"].orEmpty().trim()
            NsAppWidgetTarget.StardustReceive(
                receiverUid = receiverUid,
                suggestedAmount = suggestedAmount,
                refId = refId,
                once = once,
                note = note,
                rawUrl = trimmed,
            )
        }
        else -> null
    }
}

private fun parsePureQueryParams(rawQuery: String): Map<String, String> {
    if (rawQuery.isBlank()) return emptyMap()
    val result = LinkedHashMap<String, String>()
    for (pair in rawQuery.split('&')) {
        if (pair.isBlank()) continue
        val eqIdx = pair.indexOf('=')
        val rawKey = if (eqIdx >= 0) pair.substring(0, eqIdx) else pair
        val rawVal = if (eqIdx >= 0) pair.substring(eqIdx + 1) else ""
        val key = decodeQueryComponent(rawKey).trim().lowercase()
        if (key.isNotEmpty() && !result.containsKey(key)) {
            result[key] = decodeQueryComponent(rawVal)
        }
    }
    return result
}

private fun decodeQueryComponent(raw: String): String {
    if (raw.isEmpty()) return ""
    return runCatching {
        URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
    }.getOrDefault(raw)
}

data class PollOption(
    val voteItemId: Long,
    val text: String,
    val count: Int? = null,
    val voted: Boolean = false,
    val voters: List<Long> = emptyList(),
)

data class PollDetail(
    val id: Long,
    val title: String,
    val creatorUid: Long,
    val isPublic: Boolean,
    val isLocked: Boolean,
    val isMultiple: Boolean,
    val options: List<PollOption>,
) {
    val hasCurrentUserVoted: Boolean
        get() = options.any { it.voted }

    val hasVisibleCounts: Boolean
        get() = options.any { it.count != null }

    val totalVotes: Long
        get() = options.sumOf { (it.count ?: 0).coerceAtLeast(0).toLong() }

    fun shareFraction(option: PollOption): Float {
        val total = totalVotes
        val count = option.count ?: return 0f
        if (total <= 0L || count <= 0) return 0f
        return (count.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    fun sharePercent(option: PollOption): Int =
        (shareFraction(option) * 100f).roundToInt().coerceIn(0, 100)
}

data class StardustTransferPreview(
    val receiverUid: Long,
    val receiverName: String,
)

data class StardustTipTarget(
    val receiverUid: Long,
    val receiverNameHint: String = "",
    val suggestedAmount: Int? = null,
    val refId: Long = 0L,
    val once: Boolean = false,
    val note: String = "",
    val widgetKey: String? = null,
)

/**
 * Formats `[title](nsapp://vote?id=...)`, sanitizing `[` / `]` / newlines so `SPECIAL_WIDGET_REGEX`
 * always matches cleanly in `MarkdownRenderer`.
 */
fun formatVoteWidgetMarkdown(voteId: Long, title: String): String {
    val safeTitle = title
        .replace("\r", " ")
        .replace("\n", " ")
        .replace("[", "【")
        .replace("]", "】")
        .trim()
        .ifBlank { "互动投票" }
    return "[$safeTitle](nsapp://vote?id=$voteId)"
}

/**
 * Formats `[投喂星尘](nsapp://stardust-receive?uid=...&amount=...&ref=...&once=...&note=...)`.
 */
fun formatStardustWidgetMarkdown(
    uid: Long,
    amount: Int? = null,
    note: String? = null,
    once: Boolean = false,
    refId: Long = 0L,
): String {
    val queryParts = buildList {
        add("uid=$uid")
        if (amount != null && amount > 0) {
            add("amount=$amount")
        }
        if (refId > 0L) {
            add("ref=$refId")
        }
        if (once) {
            add("once=1")
        }
        val cleanNote = note
            ?.replace("\r", " ")
            ?.replace("\n", " ")
            ?.trim()
            .orEmpty()
        if (cleanNote.isNotEmpty()) {
            val encodedNote = URLEncoder.encode(cleanNote, StandardCharsets.UTF_8.name())
                .replace("+", "%20")
            add("note=$encodedNote")
        }
    }
    return "[投喂星尘](nsapp://stardust-receive?${queryParts.joinToString("&")})"
}
