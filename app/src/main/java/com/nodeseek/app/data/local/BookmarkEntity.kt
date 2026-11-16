package com.nodeseek.app.data.local

import androidx.room.Entity
import androidx.room.Index
import com.nodeseek.app.domain.model.BookmarkedTopic
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val bookmarkSnapshotJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Entity(
    tableName = "bookmarks",
    primaryKeys = ["accountUid", "topicId"],
    indices = [Index(value = ["accountUid", "savedAtEpochMillis"])],
)
data class BookmarkEntity(
    val accountUid: Long,
    val topicId: Long,
    val title: String,
    val categorySlug: String,
    val categoryName: String,
    val authorId: Long?,
    val authorName: String,
    val authorAvatarUrl: String,
    val authorLevel: Int?,
    val viewCount: Int,
    val replyCount: Int,
    val lastActivityIso: String,
    val requiredViewLevel: Int?,
    val isLocked: Boolean,
    val offlineDetailJson: String?,
    val isSyncedWithServer: Boolean,
    val savedAtEpochMillis: Long,
) {
    fun decodeOfflineDetail(): TopicDetailPage? =
        offlineDetailJson?.let { raw ->
            runCatching {
                bookmarkSnapshotJson.decodeFromString<TopicDetailPage>(raw)
                    .copy(isOfflineSnapshot = true)
            }.getOrNull()
        }

    fun toSummary(): TopicSummary = TopicSummary(
        id = topicId,
        title = title,
        categorySlug = categorySlug,
        categoryName = categoryName,
        authorId = authorId,
        authorName = authorName,
        authorAvatarUrl = authorAvatarUrl,
        viewCount = viewCount,
        replyCount = replyCount,
        lastActivityIso = lastActivityIso,
        isPinned = false,
        requiredViewLevel = requiredViewLevel,
        isLocked = isLocked,
        isBookmarked = true,
        authorLevel = authorLevel,
    )

    fun toDomain(): BookmarkedTopic = BookmarkedTopic(
        topicId = topicId,
        accountUid = accountUid,
        summary = toSummary(),
        offlineDetailSnapshot = decodeOfflineDetail(),
        savedAtEpochMillis = savedAtEpochMillis,
        isSyncedWithServer = isSyncedWithServer,
    )

    companion object {
        fun encodeOfflineDetail(detail: TopicDetailPage?): String? =
            detail?.let { snapshot ->
                runCatching {
                    bookmarkSnapshotJson.encodeToString(snapshot.copy(isOfflineSnapshot = true))
                }.getOrNull()
            }

        fun fromSummary(
            accountUid: Long,
            summary: TopicSummary,
            detailSnapshot: TopicDetailPage? = null,
            isSyncedWithServer: Boolean = false,
            savedAtEpochMillis: Long = System.currentTimeMillis(),
        ): BookmarkEntity = BookmarkEntity(
            accountUid = accountUid,
            topicId = summary.id,
            title = summary.title,
            categorySlug = summary.categorySlug,
            categoryName = summary.categoryName,
            authorId = summary.authorId,
            authorName = summary.authorName,
            authorAvatarUrl = summary.authorAvatarUrl,
            authorLevel = summary.authorLevel,
            viewCount = summary.viewCount,
            replyCount = summary.replyCount,
            lastActivityIso = summary.lastActivityIso,
            requiredViewLevel = summary.requiredViewLevel,
            isLocked = summary.isLocked,
            offlineDetailJson = encodeOfflineDetail(detailSnapshot),
            isSyncedWithServer = isSyncedWithServer,
            savedAtEpochMillis = savedAtEpochMillis,
        )
    }
}

fun BookmarkedTopic.toEntity(): BookmarkEntity = BookmarkEntity.fromSummary(
    accountUid = accountUid,
    summary = summary,
    detailSnapshot = offlineDetailSnapshot,
    isSyncedWithServer = isSyncedWithServer,
    savedAtEpochMillis = savedAtEpochMillis,
)
