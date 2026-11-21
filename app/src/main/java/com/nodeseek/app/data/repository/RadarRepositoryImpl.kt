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
import com.nodeseek.app.domain.radar.RadarRuleCanonicalizer
import com.nodeseek.app.domain.radar.RadarRuleDedupeKey
import com.nodeseek.app.domain.radar.TrackedTopic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
        val normalizedKeyword = RadarRuleCanonicalizer
            .validateAndNormalizeKeyword(keyword, matchMode)
            .getOrElse { return Result.failure(it) }

        val normalizedExclude = normalizeExcludeWords(excludeWords)
        val normalizedSlug = RadarRuleCanonicalizer.normalizeCategorySlug(categorySlug)
        val candidateKey = canonicalRuleKey(
            keyword = normalizedKeyword,
            excludeWords = normalizedExclude,
            categorySlug = normalizedSlug,
            matchMode = matchMode,
        )

        val existing = radarDao.getKeywordRules()
        val duplicate = existing.any { row ->
            canonicalRuleKey(row.toDomain()) == candidateKey
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
        val existingRules = if (replaceExisting) {
            emptyList()
        } else {
            radarDao.getKeywordRules().map { it.toDomain() }
        }
        val deduplicatedRules = RadarRuleCanonicalizer.deduplicateForRestore(
            rules = keywordRules,
            fallbackNowMillis = now,
            existingRules = existingRules,
            strictValidation = false,
        ).getOrThrow()
        val ruleEntitiesToInsert = deduplicatedRules.map { it.toEntity().copy(id = 0L) }

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

    override suspend fun restoreExactSnapshot(
        pollingSettings: RadarPollingSettings,
        keywordRules: List<KeywordWatchRule>,
        trackedTopics: List<TrackedTopic>,
    ) {
        preferencesStore.updatePollingSettings { pollingSettings }
        radarDao.replaceAllKeywordRules(keywordRules.map { it.toEntity() })
        radarDao.replaceAllTrackedTopics(trackedTopics.map { it.toEntity() })
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
        internal fun normalizeExcludeWords(raw: String): String =
            RadarRuleCanonicalizer.normalizeExcludeWords(raw)

        internal fun canonicalRuleKey(
            keyword: String,
            excludeWords: String,
            categorySlug: String,
            matchMode: KeywordMatchMode,
        ): RadarRuleDedupeKey = RadarRuleCanonicalizer.dedupeKey(
            keyword = keyword,
            excludeWords = excludeWords,
            categorySlug = categorySlug,
            matchMode = matchMode,
        )

        internal fun canonicalRuleKey(rule: KeywordWatchRule): RadarRuleDedupeKey =
            RadarRuleCanonicalizer.dedupeKey(rule)
    }
}
