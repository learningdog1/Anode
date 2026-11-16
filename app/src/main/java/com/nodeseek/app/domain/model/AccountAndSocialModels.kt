package com.nodeseek.app.domain.model

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

@Serializable
data class AccountSession(
    val uid: Long,
    val username: String,
    val avatarUrl: String = "",
    val drumsticks: Int = 0,
    val stardust: Int = 0,
    val topicCount: Int = 0,
    val replyCount: Int = 0,
    val bookmarkCount: Int = 0,
    val permissionRank: Int = 0,
    val unreadNotificationCount: Int = 0,
    val canCustomizeCss: Boolean = false,
    val isTelegramBound: Boolean = false,
) {
    /** Official NodeSeek display level Lv0..Lv6: coins >= 100 * level^2 */
    val level: Int
        get() = calculateLevel(drumsticks)

    val currentLevelThreshold: Int
        get() = 100 * level * level

    val nextLevelThreshold: Int?
        get() = if (level >= 6) null else 100 * (level + 1) * (level + 1)

    val progressToNextLevel: Float
        get() {
            val next = nextLevelThreshold ?: return 1f
            val span = (next - currentLevelThreshold).coerceAtLeast(1)
            return ((drumsticks - currentLevelThreshold).toFloat() / span.toFloat()).coerceIn(0f, 1f)
        }

    companion object {
        fun calculateLevel(coins: Int): Int =
            sqrt((coins.coerceAtLeast(0) / 100.0)).toInt().coerceIn(0, 6)
    }
}

@Serializable
enum class ReactionType(val apiSegment: String, val defaultCoinCost: Int) {
    UPVOTE("upvote", 0),
    DRUMSTICK("like", 1),
    DISLIKE("dislike", 2),
}

@Serializable
data class ReactionResult(
    val updatedCount: Int,
    val remainingDrumsticks: Int? = null,
    val message: String? = null,
)

@Serializable
data class CheckInStatus(
    val hasCheckedInToday: Boolean,
    val todayGain: Int? = null,
    val todayOrder: Int? = null,
    val totalCheckedInUsers: Int = 0,
    val leaderboard: List<CheckInMember> = emptyList(),
    val hasNextPage: Boolean = false,
    val message: String? = null,
)

@Serializable
data class CheckInMember(
    val rank: Int,
    val uid: Long,
    val username: String,
    val gain: Int,
    val avatarUrl: String = "",
)

@Serializable
data class DailyQuotaProgress(
    val postBonusEarned: Int = 0,
    val maxPostBonus: Int = 0,
    val replyBonusEarned: Int = 0,
    val maxReplyBonus: Int = 0,
    val freeDrumsticksUsed: Int = 0,
    val maxFreeDrumsticks: Int = 0,
) {
    val nextDrumstickCost: Int
        get() = if (freeDrumsticksUsed < maxFreeDrumsticks) 0 else 1
}

@Serializable
enum class NotificationKind(val apiSegment: String, val markReadKey: String) {
    REPLIES("reply-to-me", "replys"),
    MENTIONS("at-me", "atMe"),
}

@Serializable
data class NotificationItem(
    val id: Long,
    val kind: NotificationKind,
    val topicId: Long,
    val topicTitle: String,
    val floorNumber: Int,
    val senderUid: Long,
    val senderName: String,
    val createdAtIso: String,
    val isRead: Boolean,
    val senderAvatarUrl: String = "",
    val excerpt: String? = null,
)

@Serializable
data class DirectMessageSummary(
    val peerUid: Long,
    val peerName: String,
    val peerAvatarUrl: String = "",
    val latestMessageId: Long? = null,
    val previewText: String,
    val isMarkdown: Boolean = false,
    val updatedAtIso: String,
    val isRead: Boolean = true,
    val isSystem: Boolean = false,
    val unreadCount: Int = 0,
)

@Serializable
data class DirectMessage(
    val id: Long,
    val senderUid: Long,
    val receiverUid: Long,
    val body: String,
    val isMarkdown: Boolean = false,
    val createdAtIso: String,
    val isRead: Boolean = true,
    val isSystem: Boolean = false,
    val noticeType: String = "",
)

@Serializable
data class UserProfile(
    val uid: Long,
    val username: String,
    val avatarUrl: String = "",
    val bio: String? = null,
    val readmeMarkdown: String? = null,
    val signatureMarkdown: String? = null,
    val joinedAtIso: String? = null,
    val drumsticks: Int? = null,
    val stardust: Int? = null,
    val topicCount: Int? = null,
    val replyCount: Int? = null,
    val followingCount: Int? = null,
    val followerCount: Int? = null,
    val isFollowedByViewer: Boolean? = null,
    val isBlockedByViewer: Boolean = false,
) {
    val level: Int
        get() = AccountSession.calculateLevel(drumsticks ?: 0)

    val nextLevelThreshold: Int?
        get() = if (level >= 6) null else 100 * (level + 1) * (level + 1)
}

@Serializable
data class UserReplyExcerpt(
    val topicId: Long,
    val topicTitle: String,
    val floorNumber: Int,
    val plainTextExcerpt: String,
    val createdAtIso: String = "",
)
