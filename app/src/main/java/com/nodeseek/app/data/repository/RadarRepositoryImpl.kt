package com.nodeseek.app.data.repository

import com.nodeseek.app.data.local.KeywordWatchRuleEntity
import com.nodeseek.app.data.local.RadarDao
import com.nodeseek.app.data.local.RadarPreferencesStore
import com.nodeseek.app.data.local.TrackedTopicEntity
import com.nodeseek.app.data.local.toEntity
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.radar.KeywordWatchRule
import com.nodeseek.app.domain.radar.LocalAlertRecord
import com.nodeseek.app.domain.radar.RadarBackupRestoreResult
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

    override suspend fun getKeywordRulesForBackup(): List<KeywordWatchRule> =
        radarDao.getKeywordRules().map { it.toDomain() }

    override suspend fun getTrackedTopicsForBackup(): List<TrackedTopic> =
        radarDao.getAllTrackedTopics().map { it.toDomain() }

    override suspend fun restoreFromBackup(
        pollingSettings: RadarPollingSettings,
        keywordRules: List<KeywordWatchRule>,
        trackedTopics: List<TrackedTopic>,
        replaceExisting: Boolean,
    ): RadarBackupRestoreResult {
        preferencesStore.updatePollingSettings { pollingSettings }

        val now = clockMillis()
        val dedupeKeys = if (replaceExisting) {
            LinkedHashSet()
        } else {
            radarDao.getKeywordRules()
                .map { existing ->
                    val domain = existing.toDomain()
                    ruleDedupeKey(domain.keyword, domain.matchMode, domain.categorySlug)
                }
                .toCollection(LinkedHashSet())
        }

        val ruleEntitiesToInsert = buildList {
            for (rule in keywordRules) {
                val entity = normalizeRuleForRestore(rule, fallbackNowMillis = now) ?: continue
                val key = ruleDedupeKey(entity.keyword, rule.matchMode, entity.categorySlug)
                if (dedupeKeys.add(key)) {
                    add(entity)
                }
            }
        }

        if (replaceExisting) {
            radarDao.replaceAllKeywordRules(ruleEntitiesToInsert)
        } else if (ruleEntitiesToInsert.isNotEmpty()) {
            radarDao.insertKeywordRules(ruleEntitiesToInsert)
        }

        val uniqueIncomingTopics = LinkedHashMap<Long, TrackedTopic>()
        for (topic in trackedTopics) {
            if (topic.topicId <= 0L) continue
            val existingIncoming = uniqueIncomingTopics[topic.topicId]
            if (existingIncoming == null || topic.lastUpdatedEpochMillis >= existingIncoming.lastUpdatedEpochMillis) {
                uniqueIncomingTopics[topic.topicId] = topic
            }
        }

        val topicEntities = if (replaceExisting) {
            uniqueIncomingTopics.values.map { topic -> normalizeTopicForRestore(topic, now) }
        } else {
            uniqueIncomingTopics.values.map { topic ->
                val normalized = normalizeTopicForRestore(topic, now)
                val existing = radarDao.getTrackedTopic(topic.topicId)
                if (existing == null) {
                    normalized
                } else {
                    existing.copy(
                        title = existing.title.ifBlank { normalized.title },
                        categorySlug = existing.categorySlug.ifBlank { normalized.categorySlug },
                        categoryName = existing.categoryName.ifBlank { normalized.categoryName },
                        authorName = existing.authorName.ifBlank { normalized.authorName },
                        lastKnownFloor = maxOf(existing.lastKnownFloor, normalized.lastKnownFloor),
                        lastKnownTotalPages = maxOf(existing.lastKnownTotalPages, normalized.lastKnownTotalPages),
                        unreadNewReplies = maxOf(existing.unreadNewReplies, normalized.unreadNewReplies),
                        isActive = existing.isActive || normalized.isActive,
                        createdAtEpochMillis = minPositive(existing.createdAtEpochMillis, normalized.createdAtEpochMillis),
                        lastCheckedEpochMillis = maxOf(existing.lastCheckedEpochMillis, normalized.lastCheckedEpochMillis),
                        lastUpdatedEpochMillis = maxOf(existing.lastUpdatedEpochMillis, normalized.lastUpdatedEpochMillis),
                    )
                }
            }
        }

        if (replaceExisting) {
            radarDao.replaceAllTrackedTopics(topicEntities)
        } else if (topicEntities.isNotEmpty()) {
            radarDao.upsertTrackedTopics(topicEntities)
        }

        return RadarBackupRestoreResult(
            keywordRulesImported = ruleEntitiesToInsert.size,
            trackedTopicsImported = topicEntities.size,
        )
    }

    private fun ruleDedupeKey(
        keyword: String,
        matchMode: KeywordMatchMode,
        categorySlug: String,
    ): Triple<String, KeywordMatchMode, String> =
        Triple(keyword.trim().lowercase(), matchMode, categorySlug.trim().lowercase())

    private fun normalizeRuleForRestore(
        rule: KeywordWatchRule,
        fallbackNowMillis: Long,
    ): KeywordWatchRuleEntity? {
        val trimmed = rule.keyword.trim()
        if (trimmed.isEmpty()) return null
        val maxLen = if (rule.matchMode == KeywordMatchMode.REGEX) MAX_REGEX_LENGTH else MAX_KEYWORD_LENGTH
        val normalizedKeyword = trimmed.take(maxLen)
        if (rule.matchMode == KeywordMatchMode.REGEX) {
            val validRegex = runCatching { Regex(normalizedKeyword, RegexOption.IGNORE_CASE) }.isSuccess
            if (!validRegex) return null
        }
        return KeywordWatchRuleEntity(
            id = 0L,
            keyword = normalizedKeyword,
            excludeWords = normalizeExcludeWords(rule.excludeWords),
            categorySlug = rule.categorySlug.trim().lowercase(),
            matchMode = rule.matchMode.name,
            isEnabled = rule.isEnabled,
            createdAtEpochMillis = if (rule.createdAtEpochMillis > 0L) rule.createdAtEpochMillis else fallbackNowMillis,
        )
    }

    private fun normalizeTopicForRestore(
        topic: TrackedTopic,
        fallbackNowMillis: Long,
    ): TrackedTopicEntity {
        val created = if (topic.createdAtEpochMillis > 0L) topic.createdAtEpochMillis else fallbackNowMillis
        val updated = if (topic.lastUpdatedEpochMillis > 0L) topic.lastUpdatedEpochMillis else created
        return TrackedTopicEntity(
            topicId = topic.topicId,
            title = topic.title.trim().ifBlank { "主题 #${topic.topicId}" },
            categorySlug = topic.categorySlug.trim(),
            categoryName = topic.categoryName.trim(),
            authorName = topic.authorName.trim(),
            lastKnownFloor = topic.lastKnownFloor.coerceAtLeast(-1),
            lastKnownTotalPages = topic.lastKnownTotalPages.coerceAtLeast(1),
            unreadNewReplies = topic.unreadNewReplies.coerceAtLeast(0),
            isActive = topic.isActive,
            createdAtEpochMillis = created,
            lastCheckedEpochMillis = topic.lastCheckedEpochMillis.coerceAtLeast(0L),
            lastUpdatedEpochMillis = updated,
        )
    }

    private fun minPositive(a: Long, b: Long): Long = when {
        a <= 0L -> b
        b <= 0L -> a
        else -> minOf(a, b)
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
