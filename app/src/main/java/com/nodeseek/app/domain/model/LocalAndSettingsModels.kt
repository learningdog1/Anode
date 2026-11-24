package com.nodeseek.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode {
    SYSTEM,
    PAPER_LIGHT,
    OBSIDIAN_DARK,
}

@Serializable
enum class AwayDigestTriggerMode {
    CUSTOM_DURATION,
    ON_APP_REOPEN,
    DURATION_OR_REOPEN,
    ;

    val usesDurationThreshold: Boolean
        get() = this == CUSTOM_DURATION || this == DURATION_OR_REOPEN
}

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val purePitchAmoled: Boolean = false,
    val useDynamicColor: Boolean = false,
    val fontScale: Float = DEFAULT_FONT_SCALE,
    val preferRandomCheckIn: Boolean = false,
    val pinnedCategorySlugs: Set<String> = DEFAULT_PINNED_CATEGORY_SLUGS,
    val includeBlockedTopics: Boolean = false,
    val preferRssFallback: Boolean = false,
    val nodeImageApiKey: String = "",
    val seekMailApiKey: String = "",
    val autoCheckUpdates: Boolean = true,
    val includePrereleaseUpdates: Boolean = false,
    val ignoredUpdateTag: String = "",
    val awayDigestEnabled: Boolean = true,
    val awayDigestTriggerMode: AwayDigestTriggerMode = AwayDigestTriggerMode.CUSTOM_DURATION,
    val awayDigestThresholdMinutes: Int = DEFAULT_AWAY_DIGEST_THRESHOLD_MINUTES,
) {
    companion object {
        /** Default scale for homepage topic cards (font size & card compaction) and global UI baseline. */
        const val DEFAULT_FONT_SCALE: Float = 0.90f
        const val MIN_FONT_SCALE: Float = 0.75f
        const val MAX_FONT_SCALE: Float = 1.30f
        const val DEFAULT_AWAY_DIGEST_THRESHOLD_MINUTES: Int = 60
        const val MIN_AWAY_DIGEST_THRESHOLD_MINUTES: Int = 5
        const val MAX_AWAY_DIGEST_THRESHOLD_MINUTES: Int = 72 * 60

        val PRESET_AWAY_DIGEST_MINUTES: List<Int> = listOf(
            30,
            60,
            120,
            240,
            360,
            540,
            720,
            1440,
        )

        val DEFAULT_PINNED_CATEGORY_SLUGS: Set<String> = linkedSetOf(
            "daily",
            "tech",
            "info",
            "review",
            "trade",
        )
    }
}

@Serializable
data class EditorDraft(
    val draftKey: String,
    val accountUid: Long,
    val title: String = "",
    val markdownContent: String,
    val categorySlug: String = "daily",
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    companion object {
        const val NEW_TOPIC_KEY = "new_topic"
        fun replyKey(topicId: Long): String = "reply_$topicId"
        fun editTopicKey(topicId: Long): String = "edit_topic_$topicId"
        fun editReplyKey(commentId: Long): String = "edit_reply_$commentId"
    }
}

@Serializable
data class ReadHistoryEntry(
    val topicId: Long,
    val title: String,
    val categoryName: String,
    val authorName: String,
    val authorAvatarUrl: String = "",
    val lastReadPage: Int = 1,
    val lastReadFloor: Int = 0,
    val visitedAtEpochMillis: Long = System.currentTimeMillis(),
    val categorySlug: String = "",
    val accountUid: Long = 0L,
)

@Serializable
data class BookmarkedTopic(
    val topicId: Long,
    val accountUid: Long = 0L,
    val summary: TopicSummary,
    val offlineDetailSnapshot: TopicDetailPage? = null,
    val savedAtEpochMillis: Long = System.currentTimeMillis(),
    val isSyncedWithServer: Boolean = false,
) {
    val hasOfflineSnapshot: Boolean
        get() = offlineDetailSnapshot != null
}
