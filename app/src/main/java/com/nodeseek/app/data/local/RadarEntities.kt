package com.nodeseek.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.radar.KeywordWatchRule
import com.nodeseek.app.domain.radar.LocalAlertKind
import com.nodeseek.app.domain.radar.LocalAlertRecord
import com.nodeseek.app.domain.radar.TrackedTopic

@Entity(
    tableName = "radar_keyword_rules",
    indices = [
        Index(
            value = ["isEnabled", "createdAtEpochMillis"],
            name = "idx_radar_keyword_rules_enabled_created",
        ),
    ],
)
data class KeywordWatchRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val keyword: String,
    val excludeWords: String,
    val categorySlug: String,
    val matchMode: String,
    val isEnabled: Boolean,
    val createdAtEpochMillis: Long,
) {
    fun toDomain(): KeywordWatchRule {
        val parsedMode = runCatching { KeywordMatchMode.valueOf(matchMode) }
            .getOrDefault(KeywordMatchMode.CONTAINS)
        return KeywordWatchRule(
            id = id,
            keyword = keyword,
            excludeWords = excludeWords,
            categorySlug = categorySlug,
            matchMode = parsedMode,
            isEnabled = isEnabled,
            createdAtEpochMillis = createdAtEpochMillis,
        )
    }
}

fun KeywordWatchRule.toEntity(): KeywordWatchRuleEntity = KeywordWatchRuleEntity(
    id = id,
    keyword = keyword,
    excludeWords = excludeWords,
    categorySlug = categorySlug,
    matchMode = matchMode.name,
    isEnabled = isEnabled,
    createdAtEpochMillis = createdAtEpochMillis,
)

@Entity(
    tableName = "radar_tracked_topics",
    indices = [
        Index(
            value = ["isActive", "lastUpdatedEpochMillis"],
            name = "idx_radar_tracked_topics_active_updated",
        ),
    ],
)
data class TrackedTopicEntity(
    @PrimaryKey
    val topicId: Long,
    val title: String,
    val categorySlug: String,
    val categoryName: String,
    val authorName: String,
    val lastKnownFloor: Int,
    val lastKnownTotalPages: Int,
    val unreadNewReplies: Int,
    val isActive: Boolean,
    val createdAtEpochMillis: Long,
    val lastCheckedEpochMillis: Long,
    val lastUpdatedEpochMillis: Long,
) {
    fun toDomain(): TrackedTopic = TrackedTopic(
        topicId = topicId,
        title = title,
        categorySlug = categorySlug,
        categoryName = categoryName,
        authorName = authorName,
        lastKnownFloor = lastKnownFloor,
        lastKnownTotalPages = lastKnownTotalPages,
        unreadNewReplies = unreadNewReplies,
        isActive = isActive,
        createdAtEpochMillis = createdAtEpochMillis,
        lastCheckedEpochMillis = lastCheckedEpochMillis,
        lastUpdatedEpochMillis = lastUpdatedEpochMillis,
    )
}

fun TrackedTopic.toEntity(): TrackedTopicEntity = TrackedTopicEntity(
    topicId = topicId,
    title = title,
    categorySlug = categorySlug,
    categoryName = categoryName,
    authorName = authorName,
    lastKnownFloor = lastKnownFloor,
    lastKnownTotalPages = lastKnownTotalPages,
    unreadNewReplies = unreadNewReplies,
    isActive = isActive,
    createdAtEpochMillis = createdAtEpochMillis,
    lastCheckedEpochMillis = lastCheckedEpochMillis,
    lastUpdatedEpochMillis = lastUpdatedEpochMillis,
)

@Entity(
    tableName = "radar_local_alerts",
    indices = [
        Index(
            value = ["dedupKey"],
            name = "idx_radar_local_alerts_dedup",
            unique = true,
        ),
        Index(
            value = ["isRead", "createdAtEpochMillis"],
            name = "idx_radar_local_alerts_read_created",
        ),
        Index(
            value = ["topicId"],
            name = "idx_radar_local_alerts_topic",
        ),
    ],
)
data class LocalAlertEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val dedupKey: String,
    val kind: String,
    val topicId: Long,
    val topicTitle: String,
    val targetFloor: Int,
    val matchedKeyword: String,
    val actorName: String,
    val summarySnippet: String,
    val categoryName: String,
    val isRead: Boolean,
    val createdAtEpochMillis: Long,
) {
    fun toDomain(): LocalAlertRecord {
        val parsedKind = runCatching { LocalAlertKind.valueOf(kind) }
            .getOrDefault(LocalAlertKind.KEYWORD_HIT)
        return LocalAlertRecord(
            id = id,
            dedupKey = dedupKey,
            kind = parsedKind,
            topicId = topicId,
            topicTitle = topicTitle,
            targetFloor = targetFloor,
            matchedKeyword = matchedKeyword,
            actorName = actorName,
            summarySnippet = summarySnippet,
            categoryName = categoryName,
            isRead = isRead,
            createdAtEpochMillis = createdAtEpochMillis,
        )
    }
}

fun LocalAlertRecord.toEntity(): LocalAlertEntity = LocalAlertEntity(
    id = id,
    dedupKey = dedupKey,
    kind = kind.name,
    topicId = topicId,
    topicTitle = topicTitle,
    targetFloor = targetFloor,
    matchedKeyword = matchedKeyword,
    actorName = actorName,
    summarySnippet = summarySnippet,
    categoryName = categoryName,
    isRead = isRead,
    createdAtEpochMillis = createdAtEpochMillis,
)
