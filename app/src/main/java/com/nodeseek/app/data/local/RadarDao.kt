package com.nodeseek.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RadarDao {

    // =========================================================================
    // Keyword Watch Rules
    // =========================================================================

    @Query(
        """
        SELECT * FROM radar_keyword_rules
        ORDER BY createdAtEpochMillis DESC, id DESC
        """
    )
    fun observeKeywordRules(): Flow<List<KeywordWatchRuleEntity>>

    @Query(
        """
        SELECT * FROM radar_keyword_rules
        ORDER BY createdAtEpochMillis DESC, id DESC
        """
    )
    suspend fun getKeywordRules(): List<KeywordWatchRuleEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertKeywordRule(entity: KeywordWatchRuleEntity): Long

    @Query("UPDATE radar_keyword_rules SET isEnabled = :enabled WHERE id = :ruleId")
    suspend fun setKeywordRuleEnabled(ruleId: Long, enabled: Boolean)

    @Query("DELETE FROM radar_keyword_rules WHERE id = :ruleId")
    suspend fun deleteKeywordRule(ruleId: Long)

    // =========================================================================
    // Tracked Topics
    // =========================================================================

    @Query(
        """
        SELECT * FROM radar_tracked_topics
        WHERE isActive = 1
        ORDER BY lastUpdatedEpochMillis DESC, createdAtEpochMillis DESC
        """
    )
    fun observeTrackedTopics(): Flow<List<TrackedTopicEntity>>

    @Query(
        """
        SELECT * FROM radar_tracked_topics
        WHERE topicId = :topicId AND isActive = 1
        LIMIT 1
        """
    )
    fun observeTrackedTopic(topicId: Long): Flow<TrackedTopicEntity?>

    @Query("SELECT * FROM radar_tracked_topics WHERE topicId = :topicId LIMIT 1")
    suspend fun getTrackedTopic(topicId: Long): TrackedTopicEntity?

    @Query(
        """
        SELECT * FROM radar_tracked_topics
        WHERE isActive = 1
        ORDER BY lastUpdatedEpochMillis DESC, createdAtEpochMillis DESC
        """
    )
    suspend fun getActiveTrackedTopics(): List<TrackedTopicEntity>

    @Upsert
    suspend fun upsertTrackedTopic(entity: TrackedTopicEntity)

    @Query("DELETE FROM radar_tracked_topics WHERE topicId = :topicId")
    suspend fun deleteTrackedTopic(topicId: Long)

    @Query(
        """
        UPDATE radar_tracked_topics
        SET lastKnownFloor = CASE WHEN lastKnownFloor < :maxSeenFloor THEN :maxSeenFloor ELSE lastKnownFloor END,
            lastKnownTotalPages = CASE WHEN lastKnownTotalPages < :totalPages THEN :totalPages ELSE lastKnownTotalPages END,
            unreadNewReplies = 0,
            lastCheckedEpochMillis = :checkedAtEpochMillis
        WHERE topicId = :topicId
        """
    )
    suspend fun markTopicFloorSeen(
        topicId: Long,
        maxSeenFloor: Int,
        totalPages: Int,
        checkedAtEpochMillis: Long,
    )

    @Query(
        """
        UPDATE radar_tracked_topics
        SET title = CASE WHEN :title IS NOT NULL AND :title != '' THEN :title ELSE title END,
            categorySlug = CASE WHEN :categorySlug IS NOT NULL AND :categorySlug != '' THEN :categorySlug ELSE categorySlug END,
            categoryName = CASE WHEN :categoryName IS NOT NULL AND :categoryName != '' THEN :categoryName ELSE categoryName END,
            authorName = CASE WHEN :authorName IS NOT NULL AND :authorName != '' THEN :authorName ELSE authorName END,
            lastKnownFloor = CASE WHEN lastKnownFloor < :newMaxFloor THEN :newMaxFloor ELSE lastKnownFloor END,
            lastKnownTotalPages = CASE WHEN lastKnownTotalPages < :newTotalPages THEN :newTotalPages ELSE lastKnownTotalPages END,
            unreadNewReplies = unreadNewReplies + :unreadDelta,
            lastCheckedEpochMillis = :checkedAtEpochMillis,
            lastUpdatedEpochMillis = CASE WHEN :unreadDelta > 0 THEN :checkedAtEpochMillis ELSE lastUpdatedEpochMillis END
        WHERE topicId = :topicId
        """
    )
    suspend fun recordMonitorTopicProgress(
        topicId: Long,
        title: String?,
        categorySlug: String?,
        categoryName: String?,
        authorName: String?,
        newMaxFloor: Int,
        newTotalPages: Int,
        unreadDelta: Int,
        checkedAtEpochMillis: Long,
    )

    @Query(
        """
        UPDATE radar_tracked_topics
        SET lastKnownTotalPages = CASE WHEN :totalPages IS NOT NULL AND lastKnownTotalPages < :totalPages THEN :totalPages ELSE lastKnownTotalPages END,
            lastCheckedEpochMillis = :checkedAtEpochMillis
        WHERE topicId = :topicId
        """
    )
    suspend fun markTopicCheckedWithoutNewReplies(
        topicId: Long,
        totalPages: Int?,
        checkedAtEpochMillis: Long,
    )

    // =========================================================================
    // Local Alert History
    // =========================================================================

    @Query(
        """
        SELECT * FROM radar_local_alerts
        ORDER BY createdAtEpochMillis DESC, id DESC
        LIMIT :limit
        """
    )
    fun observeAlertRecords(limit: Int = MAX_LOCAL_ALERT_HISTORY): Flow<List<LocalAlertEntity>>

    @Query("SELECT COUNT(*) FROM radar_local_alerts WHERE isRead = 0")
    fun observeUnreadAlertCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlertIfAbsent(entity: LocalAlertEntity): Long

    @Query(
        """
        DELETE FROM radar_local_alerts
        WHERE id NOT IN (
            SELECT id FROM radar_local_alerts
            ORDER BY createdAtEpochMillis DESC, id DESC
            LIMIT :maxRows
        )
        """
    )
    suspend fun trimAlertsToLimit(maxRows: Int = MAX_LOCAL_ALERT_HISTORY)

    @Query("UPDATE radar_local_alerts SET isRead = :read WHERE id = :alertId")
    suspend fun setAlertRead(alertId: Long, read: Boolean)

    @Query("UPDATE radar_local_alerts SET isRead = 1 WHERE dedupKey = :dedupKey")
    suspend fun markAlertReadByDedupKey(dedupKey: String)

    @Query("UPDATE radar_local_alerts SET isRead = 1 WHERE topicId = :topicId AND isRead = 0")
    suspend fun markAlertsReadForTopic(topicId: Long)

    @Query("UPDATE radar_local_alerts SET isRead = 1 WHERE isRead = 0")
    suspend fun markAllAlertsRead()

    @Query("DELETE FROM radar_local_alerts WHERE id = :alertId")
    suspend fun deleteAlert(alertId: Long)

    @Query("DELETE FROM radar_local_alerts WHERE isRead = 1")
    suspend fun clearReadAlerts()

    @Query("DELETE FROM radar_local_alerts")
    suspend fun clearAllAlerts()

    companion object {
        const val MAX_LOCAL_ALERT_HISTORY = 500
    }
}
