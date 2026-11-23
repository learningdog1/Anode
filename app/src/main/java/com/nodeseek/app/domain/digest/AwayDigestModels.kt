package com.nodeseek.app.domain.digest

import androidx.compose.runtime.Immutable
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.TopicSummary
import kotlinx.serialization.Serializable

/**
 * Captures a user away window (`[awayStartMillis, reopenedAtMillis]`) detected when reopening
 * the app after being inactive for at least [AwayDurationFormatter.DEFAULT_MIN_AWAY_DURATION_MILLIS].
 */
@Immutable
@Serializable
data class AwaySession(
    val sessionId: String,
    val awayStartMillis: Long,
    val reopenedAtMillis: Long,
    val awayDurationMillis: Long = (reopenedAtMillis - awayStartMillis).coerceAtLeast(0L),
    val isDismissed: Boolean = false,
) {
    val headlineText: String
        get() = AwayDurationFormatter.formatHeadline(awayDurationMillis)

    val compactDurationText: String
        get() = AwayDurationFormatter.formatDurationOnly(awayDurationMillis)
}

/**
 * Formats away durations into natural Chinese headlines (e.g. `"你离开了 9 小时"`, `"你离开了不到 1 分钟"`).
 */
object AwayDurationFormatter {
    /** Default threshold (1 hour) before triggering the While-You-Were-Away digest. */
    const val DEFAULT_MIN_AWAY_DURATION_MILLIS: Long = 60L * 60_000L

    fun formatHeadline(awayDurationMillis: Long): String {
        val safeMillis = awayDurationMillis.coerceAtLeast(0L)
        return if (safeMillis < 60_000L) {
            "你离开了不到 1 分钟"
        } else {
            "你离开了 ${formatDurationOnly(safeMillis)}"
        }
    }

    fun formatDurationOnly(awayDurationMillis: Long): String {
        val safeMillis = awayDurationMillis.coerceAtLeast(0L)
        if (safeMillis < 60_000L) {
            return "不到 1 分钟"
        }
        val totalMinutes = safeMillis / 60_000L
        if (totalMinutes < 60L) {
            return "$totalMinutes 分钟"
        }

        val totalHours = totalMinutes / 60L
        val remMinutes = totalMinutes % 60L
        if (totalHours < 24L) {
            return if (remMinutes == 0L) {
                "$totalHours 小时"
            } else {
                "$totalHours 小时 $remMinutes 分钟"
            }
        }

        val days = totalHours / 24L
        val remHours = totalHours % 24L
        return if (remHours == 0L) {
            "$days 天"
        } else {
            "$days 天 $remHours 小时"
        }
    }

    /**
     * Formats a Settings threshold in minutes into concise Chinese (e.g. `30 分钟`, `1 小时 30 分钟`, `9 小时`, `24 小时`).
     */
    fun formatThresholdMinutes(minutes: Int): String {
        val clamped = minutes.coerceAtLeast(1)
        if (clamped < 60) {
            return "$clamped 分钟"
        }
        val hours = clamped / 60
        val remMinutes = clamped % 60
        return if (remMinutes == 0) {
            "$hours 小时"
        } else {
            "$hours 小时 $remMinutes 分钟"
        }
    }

    /**
     * Parses custom hours/minutes inputs into validated total minutes within
     * `[AppSettings.MIN_AWAY_DIGEST_THRESHOLD_MINUTES, AppSettings.MAX_AWAY_DIGEST_THRESHOLD_MINUTES]`.
     */
    fun parseCustomThresholdMinutes(
        hoursInput: String,
        minutesInput: String,
    ): Int? {
        val cleanHours = hoursInput.trim()
        val cleanMinutes = minutesInput.trim()
        if (cleanHours.isEmpty() && cleanMinutes.isEmpty()) return null

        val hours = if (cleanHours.isEmpty()) 0L else cleanHours.toLongOrNull() ?: return null
        val minutes = if (cleanMinutes.isEmpty()) 0L else cleanMinutes.toLongOrNull() ?: return null
        if (hours < 0L || minutes < 0L || hours > 72L || minutes > 72L * 60L) return null

        val total = hours * 60L + minutes
        if (total !in AppSettings.MIN_AWAY_DIGEST_THRESHOLD_MINUTES.toLong()..AppSettings.MAX_AWAY_DIGEST_THRESHOLD_MINUTES.toLong()) {
            return null
        }
        return total.toInt()
    }
}

/**
 * A single unread highlight ranked for the While-You-Were-Away Top 10 digest.
 */
@Immutable
data class AwayDigestItem(
    val rank: Int,
    val topic: TopicSummary,
    val drumstickCount: Int,
    val replyCount: Int,
    val viewCount: Int,
    val hotnessScore: Double,
    val hotnessIndex: Int,
    val isDealOrPromo: Boolean = false,
    val isCuratedAward: Boolean = false,
    val publishedAtIso: String = topic.lastActivityIso,
)

/**
 * Reactive UI state for the While-You-Were-Away Top 10 Unread Digest (`离开期间速览`).
 */
@Immutable
data class AwayDigestState(
    val activeSession: AwaySession? = null,
    val isLoading: Boolean = false,
    val items: List<AwayDigestItem> = emptyList(),
    val totalUnreadInWindow: Int = 0,
    val skippedReadCount: Int = 0,
    val pagesScanned: Int = 0,
    val errorMessage: String? = null,
) {
    val headlineText: String
        get() = activeSession?.headlineText.orEmpty()

    val subtitleText: String
        get() = when {
            items.isNotEmpty() -> buildString {
                append("为你精选这段时间最值得看的 ")
                append(items.size)
                append(" 个未读好帖")
                if (skippedReadCount > 0) {
                    append(" · 已自动跳过 ")
                    append(skippedReadCount)
                    append(" 篇已读")
                }
            }
            isLoading -> "正在按回复数、鸡腿数与热度盘点未读好帖..."
            else -> ""
        }

    val isVisible: Boolean
        get() = activeSession != null &&
            !activeSession.isDismissed &&
            (isLoading || items.isNotEmpty())
}
