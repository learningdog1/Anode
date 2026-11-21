package com.nodeseek.app.domain.backup

import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.BookmarkedTopic
import com.nodeseek.app.domain.model.EditorDraft
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.ThemeMode
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.radar.KeywordWatchRule
import com.nodeseek.app.domain.radar.RadarPollingSettings
import com.nodeseek.app.domain.radar.TrackedTopic
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Versioned full-app backup payload serialized as a single JSON document.
 */
@Serializable
data class NodePulseBackupBundle(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val appVersion: String,
    val exportedAtEpochMillis: Long,
    val settings: AppSettingsBackupDto,
    val radar: RadarBackupDto,
    val blocklist: BlocklistBackupDto,
    val bookmarks: List<BookmarkedTopic>,
    val drafts: List<EditorDraft>,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

@Serializable
data class AppSettingsBackupDto(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val purePitchAmoled: Boolean = false,
    val useDynamicColor: Boolean = false,
    val fontScale: Float = AppSettings.DEFAULT_FONT_SCALE,
    val preferRandomCheckIn: Boolean = false,
    val pinnedCategorySlugs: Set<String> = AppSettings.DEFAULT_PINNED_CATEGORY_SLUGS,
    val includeBlockedTopics: Boolean = false,
    val preferRssFallback: Boolean = false,
    val nodeImageApiKey: String = "",
    val seekMailApiKey: String = "",
    val autoCheckUpdates: Boolean = true,
    val includePrereleaseUpdates: Boolean = false,
    val ignoredUpdateTag: String = "",
    val recentSearchQueries: List<String> = emptyList(),
)

@Serializable
data class RadarBackupDto(
    val pollingSettings: RadarPollingSettingsDto = RadarPollingSettingsDto(),
    val keywordRules: List<KeywordWatchRuleBackupDto> = emptyList(),
    val trackedTopics: List<TrackedTopicBackupDto> = emptyList(),
)

@Serializable
data class RadarPollingSettingsDto(
    val isEnabled: Boolean = true,
    val keywordAlertsEnabled: Boolean = true,
    val replyTrackingEnabled: Boolean = true,
    val inboxPushEnabled: Boolean = true,
    val foregroundIntervalMinutes: Int = 2,
    val backgroundIntervalMinutes: Int = RadarPollingSettings.MIN_BACKGROUND_INTERVAL_MINUTES,
    val wifiOnlyInBackground: Boolean = false,
    val requireBatteryNotLowInBackground: Boolean = true,
    val quietHoursEnabled: Boolean = false,
    val quietStartMinuteOfDay: Int = 23 * 60,
    val quietEndMinuteOfDay: Int = 7 * 60,
) {
    fun toDomain(): RadarPollingSettings = RadarPollingSettings(
        isEnabled = isEnabled,
        keywordAlertsEnabled = keywordAlertsEnabled,
        replyTrackingEnabled = replyTrackingEnabled,
        inboxPushEnabled = inboxPushEnabled,
        foregroundIntervalMinutes = foregroundIntervalMinutes.coerceIn(
            RadarPollingSettings.MIN_FOREGROUND_INTERVAL_MINUTES,
            RadarPollingSettings.MAX_FOREGROUND_INTERVAL_MINUTES,
        ),
        backgroundIntervalMinutes = backgroundIntervalMinutes.coerceIn(
            RadarPollingSettings.MIN_BACKGROUND_INTERVAL_MINUTES,
            RadarPollingSettings.MAX_BACKGROUND_INTERVAL_MINUTES,
        ),
        wifiOnlyInBackground = wifiOnlyInBackground,
        requireBatteryNotLowInBackground = requireBatteryNotLowInBackground,
        quietHoursEnabled = quietHoursEnabled,
        quietStartMinuteOfDay = quietStartMinuteOfDay.coerceIn(0, 1439),
        quietEndMinuteOfDay = quietEndMinuteOfDay.coerceIn(0, 1439),
    )
}

fun RadarPollingSettings.toBackupDto(): RadarPollingSettingsDto = RadarPollingSettingsDto(
    isEnabled = isEnabled,
    keywordAlertsEnabled = keywordAlertsEnabled,
    replyTrackingEnabled = replyTrackingEnabled,
    inboxPushEnabled = inboxPushEnabled,
    foregroundIntervalMinutes = foregroundIntervalMinutes,
    backgroundIntervalMinutes = backgroundIntervalMinutes,
    wifiOnlyInBackground = wifiOnlyInBackground,
    requireBatteryNotLowInBackground = requireBatteryNotLowInBackground,
    quietHoursEnabled = quietHoursEnabled,
    quietStartMinuteOfDay = quietStartMinuteOfDay,
    quietEndMinuteOfDay = quietEndMinuteOfDay,
)

@Serializable
data class KeywordWatchRuleBackupDto(
    val id: Long = 0L,
    val keyword: String,
    val excludeWords: String = "",
    val categorySlug: String = "",
    val matchMode: String = KeywordMatchMode.CONTAINS.name,
    val isEnabled: Boolean = true,
    val createdAtEpochMillis: Long = 0L,
) {
    fun resolvedMatchMode(): KeywordMatchMode =
        runCatching { KeywordMatchMode.valueOf(matchMode.trim().uppercase()) }
            .getOrDefault(KeywordMatchMode.CONTAINS)

    fun toDomain(): KeywordWatchRule = KeywordWatchRule(
        id = id,
        keyword = keyword,
        excludeWords = excludeWords,
        categorySlug = categorySlug,
        matchMode = resolvedMatchMode(),
        isEnabled = isEnabled,
        createdAtEpochMillis = createdAtEpochMillis,
    )
}

fun KeywordWatchRule.toBackupDto(): KeywordWatchRuleBackupDto = KeywordWatchRuleBackupDto(
    id = id,
    keyword = keyword,
    excludeWords = excludeWords,
    categorySlug = categorySlug,
    matchMode = matchMode.name,
    isEnabled = isEnabled,
    createdAtEpochMillis = createdAtEpochMillis,
)

@Serializable
data class TrackedTopicBackupDto(
    val topicId: Long,
    val title: String,
    val categorySlug: String = "",
    val categoryName: String = "",
    val authorName: String = "",
    val lastKnownFloor: Int = -1,
    val lastKnownTotalPages: Int = 1,
    val unreadNewReplies: Int = 0,
    val isActive: Boolean = true,
    val createdAtEpochMillis: Long = 0L,
    val lastCheckedEpochMillis: Long = 0L,
    val lastUpdatedEpochMillis: Long = 0L,
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

fun TrackedTopic.toBackupDto(): TrackedTopicBackupDto = TrackedTopicBackupDto(
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

@Serializable
data class BlocklistBackupDto(
    val blockedUsers: List<BlockedUserBackupDto> = emptyList(),
    val contentFilter: ContentFilterBackupDto = ContentFilterBackupDto(),
)

@Serializable
data class BlockedUserBackupDto(
    val uid: Long,
    val username: String,
    val avatarUrl: String = "",
) {
    fun toDomain(): FloorAuthor = FloorAuthor(
        uid = uid,
        username = username.trim(),
        avatarUrl = avatarUrl.trim(),
    )
}

fun FloorAuthor.toBlockedUserBackupDto(): BlockedUserBackupDto = BlockedUserBackupDto(
    uid = uid,
    username = username.trim(),
    avatarUrl = avatarUrl.trim(),
)

@Serializable
data class ContentFilterBackupDto(
    val isEnabled: Boolean = true,
    val mutedCategorySlugs: Set<String> = emptySet(),
    val rules: List<ContentFilterRuleBackupDto> = emptyList(),
)

@Serializable
data class ContentFilterRuleBackupDto(
    val id: String,
    val pattern: String,
    val matchMode: String = KeywordMatchMode.CONTAINS.name,
    val categorySlug: String = "",
    val isEnabled: Boolean = true,
    val note: String = "",
    val createdAtEpochMillis: Long = 0L,
)

/**
 * Bridge decoupling backup/restore from the content-filter module until branches are integrated.
 */
interface ContentFilterBackupBridge {
    suspend fun export(): ContentFilterBackupDto
    suspend fun restore(dto: ContentFilterBackupDto, merge: Boolean)
}

object NoOpContentFilterBackupBridge : ContentFilterBackupBridge {
    override suspend fun export(): ContentFilterBackupDto = ContentFilterBackupDto()
    override suspend fun restore(dto: ContentFilterBackupDto, merge: Boolean) = Unit
}

enum class RestoreStrategy {
    MERGE,
    OVERWRITE,
}

data class BackupPreviewSummary(
    val schemaVersion: Int,
    val appVersion: String,
    val exportedAtEpochMillis: Long,
    val radarKeywordRuleCount: Int,
    val radarTrackedTopicCount: Int,
    val blockedUserCount: Int,
    val contentFilterRuleCount: Int,
    val mutedCategoryCount: Int,
    val bookmarkCount: Int,
    val draftCount: Int,
    val recentSearchQueryCount: Int,
    val containsApiKeys: Boolean,
)

data class BackupRestoreSummary(
    val strategy: RestoreStrategy,
    val radarKeywordRulesImported: Int,
    val radarTrackedTopicsImported: Int,
    val blockedUsersImported: Int,
    val blockedUsersSyncedToServer: Int,
    val blockedUsersServerSyncSkippedOrFailed: Int,
    val contentFilterRulesImported: Int,
    val mutedCategoriesCount: Int,
    val bookmarksImported: Int,
    val draftsImported: Int,
    val recentSearchQueriesCount: Int,
)

data class BackupExportPayload(
    val bundle: NodePulseBackupBundle,
    val rawJson: String,
)

object BackupFileNaming {
    private val FILE_TIMESTAMP_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    fun createDefaultFileName(
        epochMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val stamp = FILE_TIMESTAMP_FORMATTER.format(
            Instant.ofEpochMilli(epochMillis).atZone(zoneId),
        )
        return "anode-backup-$stamp.json"
    }
}
