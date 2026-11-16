package com.nodeseek.app.data.repository

import com.nodeseek.app.data.local.KeywordWatchRuleEntity
import com.nodeseek.app.data.local.RadarDao
import com.nodeseek.app.data.local.RadarPreferencesStore
import com.nodeseek.app.data.local.TrackedTopicEntity
import com.nodeseek.app.data.local.toEntity
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.radar.KeywordWatchRule
import com.nodeseek.app.domain.radar.LocalAlertRecord
import com.nodeseek.app.domain.radar.RadarMonitorSyncSupport
import com.nodeseek.app.domain.radar.RadarPollingSettings
import com.nodeseek.app.domain.radar.RadarRepository
import com.nodeseek.app.domain.radar.TrackedTopic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.regex.PatternSyntaxException

class RadarRepositoryImpl(
    private val radarDao: RadarDao,
    private val preferencesStore: RadarPreferencesStore,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : RadarRepository, RadarMonitorSyncSupport {

    override val pollingSettingsFlow: Flow<RadarPollingSettings> =
        preferencesStore.pollingSettingsFlow

    override val keywordRulesFlow: Flow<List<KeywordWatchRule>> =
        radarDao.observeKeywordRules().map { list ->
            list.map { it.toDomain() }
        }

    override val trackedTopicsFlow: Flow<List<TrackedTopic>> =
        radarDao.observeTrackedTopics().map { list ->
            list.map { it.toDomain() }
        }

    override val alertRecordsFlow: Flow<List<LocalAlertRecord>> =
        radarDao.observeAlertRecords().map { list ->
            list.map { it.toDomain() }
        }

    override val unreadAlertCountFlow: Flow<Int> =
        radarDao.observeUnreadAlertCount()

    override fun observeTrackedTopic(topicId: Long): Flow<TrackedTopic?> =
        radarDao.observeTrackedTopic(topicId).map { it?.toDomain() }

    override suspend fun getPollingSettings(): RadarPollingSettings =
        preferencesStore.getPollingSettings()

    override suspend fun updatePollingSettings(
        transform: (RadarPollingSettings) -> RadarPollingSettings,
    ) {
        preferencesStore.updatePollingSettings(transform)
    }

    override suspend fun addKeywordRule(
        keyword: String,
        excludeWords: String,
        categorySlug: String,
        matchMode: KeywordMatchMode,
    ): Result<KeywordWatchRule> {
        val trimmedKeyword = keyword.trim()
        if (trimmedKeyword.isEmpty()) {
            return Result.failure(IllegalArgumentException("请输入监控关键词"))
        }
        val maxLen = if (matchMode == KeywordMatchMode.REGEX) MAX_REGEX_LENGTH else MAX_KEYWORD_LENGTH
        val normalizedKeyword = trimmedKeyword.take(maxLen)

        if (matchMode == KeywordMatchMode.REGEX) {
            try {
                Regex(normalizedKeyword, RegexOption.IGNORE_CASE)
            } catch (e: PatternSyntaxException) {
                return Result.failure(
                    IllegalArgumentException("正则表达式格式无效：${e.description ?: e.message.orEmpty()}"),
                )
            }
        }

        val normalizedExclude = normalizeExcludeWords(excludeWords)
        val normalizedSlug = categorySlug.trim().lowercase()

        val existing = radarDao.getKeywordRules()
        val duplicate = existing.any { row ->
            row.keyword.equals(normalizedKeyword, ignoreCase = true) &&
                row.excludeWords == normalizedExclude &&
                row.categorySlug == normalizedSlug &&
                row.matchMode == matchMode.name
        }
        if (duplicate) {
            return Result.failure(IllegalArgumentException("相同的关键词监控规则已存在"))
        }

        val now = clockMillis()
        val entity = KeywordWatchRuleEntity(
            keyword = normalizedKeyword,
            excludeWords = normalizedExclude,
            categorySlug = normalizedSlug,
            matchMode = matchMode.name,
            isEnabled = true,
            createdAtEpochMillis = now,
        )
        val generatedId = radarDao.insertKeywordRule(entity)
        return Result.success(entity.copy(id = generatedId).toDomain())
    }

    override suspend fun toggleKeywordRule(ruleId: Long, enabled: Boolean) {
        radarDao.setKeywordRuleEnabled(ruleId = ruleId, enabled = enabled)
    }

    override suspend fun deleteKeywordRule(ruleId: Long) {
        radarDao.deleteKeywordRule(ruleId)
    }

    override suspend fun trackTopic(
        topicId: Long,
        title: String,
        categorySlug: String,
        categoryName: String,
        authorName: String,
        currentMaxFloor: Int,
        totalPages: Int,
    ): TrackedTopic {
        val now = clockMillis()
        val existing = radarDao.getTrackedTopic(topicId)
        val safeTotalPages = maxOf(totalPages.coerceAtLeast(1), existing?.lastKnownTotalPages ?: 1)
        val resolvedFloor = when {
            currentMaxFloor >= 0 -> maxOf(currentMaxFloor, existing?.lastKnownFloor ?: -1)
            existing != null -> existing.lastKnownFloor
            else -> -1
        }
        val entity = TrackedTopicEntity(
            topicId = topicId,
            title = title.trim().ifBlank { existing?.title ?: "主题 #$topicId" },
            categorySlug = categorySlug.trim().ifBlank { existing?.categorySlug.orEmpty() },
            categoryName = categoryName.trim().ifBlank { existing?.categoryName.orEmpty() },
            authorName = authorName.trim().ifBlank { existing?.authorName.orEmpty() },
            lastKnownFloor = resolvedFloor,
            lastKnownTotalPages = safeTotalPages,
            unreadNewReplies = 0,
            isActive = true,
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
            lastCheckedEpochMillis = if (resolvedFloor >= 0) now else (existing?.lastCheckedEpochMillis ?: 0L),
            lastUpdatedEpochMillis = now,
        )
        radarDao.upsertTrackedTopic(entity)
        return entity.toDomain()
    }

    override suspend fun untrackTopic(topicId: Long) {
        radarDao.deleteTrackedTopic(topicId)
    }

    override suspend fun toggleTrackTopic(
        topicId: Long,
        title: String,
        categorySlug: String,
        categoryName: String,
        authorName: String,
        currentMaxFloor: Int,
        totalPages: Int,
    ): Boolean {
        val existing = radarDao.getTrackedTopic(topicId)
        return if (existing != null && existing.isActive) {
            radarDao.deleteTrackedTopic(topicId)
            false
        } else {
            trackTopic(
                topicId = topicId,
                title = title,
                categorySlug = categorySlug,
                categoryName = categoryName,
                authorName = authorName,
                currentMaxFloor = currentMaxFloor,
                totalPages = totalPages,
            )
            true
        }
    }

    override suspend fun onTopicFloorSeen(
        topicId: Long,
        maxSeenFloor: Int,
        totalPages: Int,
    ) {
        radarDao.markAlertsReadForTopic(topicId)
        radarDao.markTopicFloorSeen(
            topicId = topicId,
            maxSeenFloor = maxSeenFloor,
            totalPages = totalPages.coerceAtLeast(1),
            checkedAtEpochMillis = clockMillis(),
        )
    }

    override suspend fun recordAlertIfAbsent(record: LocalAlertRecord): Boolean {
        val insertedId = radarDao.insertAlertIfAbsent(record.toEntity())
        if (insertedId <= 0L) {
            return false
        }
        radarDao.trimAlertsToLimit(RadarDao.MAX_LOCAL_ALERT_HISTORY)
        return true
    }

    override suspend fun markAlertRead(alertId: Long, read: Boolean) {
        radarDao.setAlertRead(alertId = alertId, read = read)
    }

    override suspend fun markAlertReadByDedupKey(dedupKey: String) {
        if (dedupKey.isBlank()) return
        radarDao.markAlertReadByDedupKey(dedupKey)
    }

    override suspend fun markAlertsReadForTopic(topicId: Long) {
        radarDao.markAlertsReadForTopic(topicId)
    }

    override suspend fun markAllAlertsRead() {
        radarDao.markAllAlertsRead()
    }

    override suspend fun deleteAlert(alertId: Long) {
        radarDao.deleteAlert(alertId)
    }

    override suspend fun clearReadAlerts() {
        radarDao.clearReadAlerts()
    }

    override suspend fun clearAllAlerts() {
        radarDao.clearAllAlerts()
    }

    override suspend fun recordMonitorTopicProgress(
        topicId: Long,
        title: String?,
        categorySlug: String?,
        categoryName: String?,
        authorName: String?,
        newMaxFloor: Int,
        newTotalPages: Int,
        unreadDelta: Int,
        checkedAtEpochMillis: Long,
    ) {
        radarDao.recordMonitorTopicProgress(
            topicId = topicId,
            title = title?.trim(),
            categorySlug = categorySlug?.trim(),
            categoryName = categoryName?.trim(),
            authorName = authorName?.trim(),
            newMaxFloor = newMaxFloor,
            newTotalPages = newTotalPages.coerceAtLeast(1),
            unreadDelta = unreadDelta.coerceAtLeast(0),
            checkedAtEpochMillis = checkedAtEpochMillis,
        )
    }

    override suspend fun markTopicCheckedWithoutNewReplies(
        topicId: Long,
        totalPages: Int?,
        checkedAtEpochMillis: Long,
    ) {
        radarDao.markTopicCheckedWithoutNewReplies(
            topicId = topicId,
            totalPages = totalPages?.coerceAtLeast(1),
            checkedAtEpochMillis = checkedAtEpochMillis,
        )
    }

    companion object {
        private const val MAX_KEYWORD_LENGTH = 64
        private const val MAX_REGEX_LENGTH = 160
        private val EXCLUDE_SPLIT_REGEX = Regex("""[,，;；\n\r]+""")

        internal fun normalizeExcludeWords(raw: String): String {
            return raw.split(EXCLUDE_SPLIT_REGEX)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
                .joinToString(", ")
        }
    }
}
