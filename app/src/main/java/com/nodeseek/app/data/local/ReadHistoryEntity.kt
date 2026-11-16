package com.nodeseek.app.data.local

import androidx.room.Entity
import androidx.room.Index
import com.nodeseek.app.domain.model.ReadHistoryEntry

@Entity(
    tableName = "read_history",
    primaryKeys = ["accountUid", "topicId"],
    indices = [Index(value = ["accountUid", "visitedAtEpochMillis"])],
)
data class ReadHistoryEntity(
    val accountUid: Long,
    val topicId: Long,
    val title: String,
    val categorySlug: String,
    val categoryName: String,
    val authorName: String,
    val authorAvatarUrl: String,
    val lastReadPage: Int,
    val lastReadFloor: Int,
    val visitedAtEpochMillis: Long,
)

fun ReadHistoryEntity.toDomain(): ReadHistoryEntry = ReadHistoryEntry(
    topicId = topicId,
    title = title,
    categoryName = categoryName,
    authorName = authorName,
    authorAvatarUrl = authorAvatarUrl,
    lastReadPage = lastReadPage,
    lastReadFloor = lastReadFloor,
    visitedAtEpochMillis = visitedAtEpochMillis,
    categorySlug = categorySlug,
    accountUid = accountUid,
)

fun ReadHistoryEntry.toEntity(accountUidOverride: Long = accountUid): ReadHistoryEntity =
    ReadHistoryEntity(
        accountUid = accountUidOverride,
        topicId = topicId,
        title = title,
        categorySlug = categorySlug,
        categoryName = categoryName,
        authorName = authorName,
        authorAvatarUrl = authorAvatarUrl,
        lastReadPage = lastReadPage,
        lastReadFloor = lastReadFloor,
        visitedAtEpochMillis = visitedAtEpochMillis,
    )
