package com.nodeseek.app.domain.radar

import kotlinx.coroutines.flow.Flow

interface RadarRepository {
    val pollingSettingsFlow: Flow<RadarPollingSettings>
    val keywordRulesFlow: Flow<List<KeywordWatchRule>>
    val trackedTopicsFlow: Flow<List<TrackedTopic>>
    val alertRecordsFlow: Flow<List<LocalAlertRecord>>
    val unreadAlertCountFlow: Flow<Int>

    fun observeTrackedTopic(topicId: Long): Flow<TrackedTopic?>

    suspend fun getPollingSettings(): RadarPollingSettings
    suspend fun updatePollingSettings(transform: (RadarPollingSettings) -> RadarPollingSettings)

    suspend fun addKeywordRule(
        keyword: String,
        excludeWords: String = "",
        categorySlug: String = "",
        matchMode: KeywordMatchMode = KeywordMatchMode.CONTAINS,
    ): Result<KeywordWatchRule>

    suspend fun toggleKeywordRule(ruleId: Long, enabled: Boolean)
    suspend fun deleteKeywordRule(ruleId: Long)

    suspend fun trackTopic(
        topicId: Long,
        title: String,
        categorySlug: String = "",
        categoryName: String = "",
        authorName: String = "",
        currentMaxFloor: Int = -1,
        totalPages: Int = 1,
    ): TrackedTopic

    suspend fun untrackTopic(topicId: Long)

    suspend fun toggleTrackTopic(
        topicId: Long,
        title: String,
        categorySlug: String = "",
        categoryName: String = "",
        authorName: String = "",
        currentMaxFloor: Int = -1,
        totalPages: Int = 1,
    ): Boolean

    suspend fun onTopicFloorSeen(topicId: Long, maxSeenFloor: Int, totalPages: Int = 1)

    suspend fun recordAlertIfAbsent(record: LocalAlertRecord): Boolean
    suspend fun markAlertRead(alertId: Long, read: Boolean = true)
    suspend fun markAlertReadByDedupKey(dedupKey: String)
    suspend fun markAlertsReadForTopic(topicId: Long)
    suspend fun markAllAlertsRead()
    suspend fun deleteAlert(alertId: Long)
    suspend fun clearReadAlerts()
    suspend fun clearAllAlerts()

    // Bulk backup export & restore helpers
    suspend fun getKeywordRulesForBackup(): List<KeywordWatchRule> = emptyList()
    suspend fun getTrackedTopicsForBackup(): List<TrackedTopic> = emptyList()
    suspend fun restoreFromBackup(
        pollingSettings: RadarPollingSettings,
        keywordRules: List<KeywordWatchRule>,
        trackedTopics: List<TrackedTopic>,
        replaceExisting: Boolean,
    ): RadarBackupRestoreResult = RadarBackupRestoreResult()
}

data class RadarBackupRestoreResult(
    val keywordRulesImported: Int = 0,
    val trackedTopicsImported: Int = 0,
)

/**
 * Optional internal/engine sync capability implemented by [com.nodeseek.app.data.repository.RadarRepositoryImpl]
 * so the monitor engine can monotonically advance tracked topic progress and unread counts without
 * expanding the public [RadarRepository] contract surface.
 */
interface RadarMonitorSyncSupport {
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

    suspend fun markTopicCheckedWithoutNewReplies(
        topicId: Long,
        totalPages: Int? = null,
        checkedAtEpochMillis: Long,
    )
}
