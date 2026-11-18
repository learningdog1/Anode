package com.nodeseek.app.data.backup

import com.nodeseek.app.BuildConfig
import com.nodeseek.app.data.local.BlockedUsersCache
import com.nodeseek.app.domain.backup.BackupExportPayload
import com.nodeseek.app.domain.backup.BackupManager
import com.nodeseek.app.domain.backup.BackupPreviewSummary
import com.nodeseek.app.domain.backup.BackupRestoreSummary
import com.nodeseek.app.domain.backup.BlockedUserBackupDto
import com.nodeseek.app.domain.backup.BlocklistBackupDto
import com.nodeseek.app.domain.backup.ContentFilterBackupBridge
import com.nodeseek.app.domain.backup.NoOpContentFilterBackupBridge
import com.nodeseek.app.domain.backup.NodePulseBackupBundle
import com.nodeseek.app.domain.backup.RadarBackupDto
import com.nodeseek.app.domain.backup.RestoreStrategy
import com.nodeseek.app.domain.backup.toBackupDto
import com.nodeseek.app.domain.backup.toBlockedUserBackupDto
import com.nodeseek.app.domain.radar.RadarRepository
import com.nodeseek.app.domain.repository.BookmarkAndHistoryRepository
import com.nodeseek.app.domain.repository.DraftRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SettingsRepository
import com.nodeseek.app.domain.repository.UserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BackupManagerImpl(
    private val settingsRepository: SettingsRepository,
    private val radarRepository: RadarRepository,
    private val bookmarkAndHistoryRepository: BookmarkAndHistoryRepository,
    private val draftRepository: DraftRepository,
    private val userRepository: UserRepository,
    private val sessionRepository: SessionRepository,
    private val blockedUsersCache: BlockedUsersCache,
    private val contentFilterBackupBridge: ContentFilterBackupBridge = NoOpContentFilterBackupBridge,
    private val appVersionProvider: () -> String = { BuildConfig.VERSION_NAME },
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : BackupManager {

    override suspend fun exportBackupJson(includeApiKeys: Boolean): Result<String> =
        exportBackupPayload(includeApiKeys).map { it.rawJson }

    override suspend fun exportBackupPayload(includeApiKeys: Boolean): Result<BackupExportPayload> =
        exportBundle(includeApiKeys).mapCatching { bundle ->
            BackupExportPayload(
                bundle = bundle,
                rawJson = backupJson.encodeToString(bundle),
            )
        }

    override suspend fun exportBundle(includeApiKeys: Boolean): Result<NodePulseBackupBundle> {
        return try {
            val settingsDto = settingsRepository.exportSettingsForBackup(includeApiKeys = includeApiKeys)

            val pollingSettings = radarRepository.getPollingSettings().toBackupDto()
            val keywordRules = radarRepository.getKeywordRulesForBackup().map { it.toBackupDto() }
            val trackedTopics = radarRepository.getTrackedTopicsForBackup().map { it.toBackupDto() }

            val blockedUsers = resolveBlockedUsersForExport()
            val contentFilterDto = contentFilterBackupBridge.export()

            val bookmarks = bookmarkAndHistoryRepository.getAllBookmarksForBackup()
            val drafts = draftRepository.getAllDrafts()

            Result.success(
                NodePulseBackupBundle(
                    schemaVersion = NodePulseBackupBundle.CURRENT_SCHEMA_VERSION,
                    appVersion = appVersionProvider(),
                    exportedAtEpochMillis = clockMillis(),
                    settings = settingsDto,
                    radar = RadarBackupDto(
                        pollingSettings = pollingSettings,
                        keywordRules = keywordRules,
                        trackedTopics = trackedTopics,
                    ),
                    blocklist = BlocklistBackupDto(
                        blockedUsers = blockedUsers,
                        contentFilter = contentFilterDto,
                    ),
                    bookmarks = bookmarks,
                    drafts = drafts,
                ),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun inspectBackup(rawJson: String): Result<BackupPreviewSummary> {
        return parseAndValidateBundle(rawJson).map { bundle ->
            val validBlockedUsers = normalizeBlockedUsers(bundle.blocklist.blockedUsers)
            val validBookmarks = bundle.bookmarks
                .filter { it.topicId > 0L }
                .distinctBy { it.accountUid to it.topicId }
            val validDrafts = bundle.drafts
                .filter { it.draftKey.isNotBlank() && (it.title.isNotBlank() || it.markdownContent.isNotBlank()) }
                .distinctBy { it.accountUid to it.draftKey.trim() }

            BackupPreviewSummary(
                schemaVersion = bundle.schemaVersion,
                appVersion = bundle.appVersion,
                exportedAtEpochMillis = bundle.exportedAtEpochMillis,
                radarKeywordRuleCount = bundle.radar.keywordRules.count { it.keyword.isNotBlank() },
                radarTrackedTopicCount = bundle.radar.trackedTopics
                    .filter { it.topicId > 0L }
                    .distinctBy { it.topicId }
                    .size,
                blockedUserCount = validBlockedUsers.size,
                contentFilterRuleCount = bundle.blocklist.contentFilter.rules.count { it.pattern.isNotBlank() },
                mutedCategoryCount = bundle.blocklist.contentFilter.mutedCategorySlugs.count { it.isNotBlank() },
                bookmarkCount = validBookmarks.size,
                draftCount = validDrafts.size,
                recentSearchQueryCount = bundle.settings.recentSearchQueries
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinctBy { it.lowercase() }
                    .size,
                containsApiKeys = bundle.settings.nodeImageApiKey.isNotBlank() ||
                    bundle.settings.seekMailApiKey.isNotBlank(),
            )
        }
    }

    override suspend fun restoreBackup(
        rawJson: String,
        strategy: RestoreStrategy,
    ): Result<BackupRestoreSummary> {
        val bundle = parseAndValidateBundle(rawJson).getOrElse { error ->
            return Result.failure(error)
        }

        return try {
            val merge = (strategy == RestoreStrategy.MERGE)
            val replaceExisting = (strategy == RestoreStrategy.OVERWRITE)

            settingsRepository.restoreSettingsFromBackup(
                backup = bundle.settings,
                merge = merge,
            )

            val radarResult = radarRepository.restoreFromBackup(
                pollingSettings = bundle.radar.pollingSettings.toDomain(),
                keywordRules = bundle.radar.keywordRules.map { it.toDomain() },
                trackedTopics = bundle.radar.trackedTopics.map { it.toDomain() },
                replaceExisting = replaceExisting,
            )

            contentFilterBackupBridge.restore(
                dto = bundle.blocklist.contentFilter,
                merge = merge,
            )

            val importedBlockedUsers = normalizeBlockedUsers(bundle.blocklist.blockedUsers)
                .map { it.toDomain() }
            if (replaceExisting) {
                blockedUsersCache.replaceAll(importedBlockedUsers)
            } else {
                blockedUsersCache.mergeAll(importedBlockedUsers)
            }

            var syncedToServer = 0
            var serverSyncSkippedOrFailed = 0
            if (importedBlockedUsers.isNotEmpty()) {
                val isSignedIn = sessionRepository.sessionState.value != null
                if (isSignedIn) {
                    for (blockedUser in importedBlockedUsers) {
                        val synced = try {
                            userRepository.blockUserByName(blockedUser.username).isSuccess
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Throwable) {
                            false
                        }
                        if (synced) {
                            syncedToServer++
                        } else {
                            serverSyncSkippedOrFailed++
                        }
                    }
                } else {
                    serverSyncSkippedOrFailed = importedBlockedUsers.size
                }
            }

            bookmarkAndHistoryRepository.restoreBookmarks(
                items = bundle.bookmarks,
                replaceExisting = replaceExisting,
            )

            draftRepository.restoreDrafts(
                items = bundle.drafts,
                replaceExisting = replaceExisting,
            )

            val importedBookmarksCount = bundle.bookmarks
                .filter { it.topicId > 0L }
                .distinctBy { it.accountUid to it.topicId }
                .size
            val importedDraftsCount = bundle.drafts
                .filter { it.draftKey.isNotBlank() && (it.title.isNotBlank() || it.markdownContent.isNotBlank()) }
                .distinctBy { it.accountUid to it.draftKey.trim() }
                .size

            Result.success(
                BackupRestoreSummary(
                    strategy = strategy,
                    radarKeywordRulesImported = radarResult.keywordRulesImported,
                    radarTrackedTopicsImported = radarResult.trackedTopicsImported,
                    blockedUsersImported = importedBlockedUsers.size,
                    blockedUsersSyncedToServer = syncedToServer,
                    blockedUsersServerSyncSkippedOrFailed = serverSyncSkippedOrFailed,
                    contentFilterRulesImported = bundle.blocklist.contentFilter.rules.count { it.pattern.isNotBlank() },
                    mutedCategoriesCount = bundle.blocklist.contentFilter.mutedCategorySlugs.count { it.isNotBlank() },
                    bookmarksImported = importedBookmarksCount,
                    draftsImported = importedDraftsCount,
                    recentSearchQueriesCount = bundle.settings.recentSearchQueries
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinctBy { it.lowercase() }
                        .size,
                ),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private suspend fun resolveBlockedUsersForExport(): List<BlockedUserBackupDto> {
        val remoteResult = try {
            userRepository.getBlockedUsers()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }

        val resolved = remoteResult.getOrNull()
        val finalUsers = if (resolved != null) {
            blockedUsersCache.replaceAll(resolved)
            resolved
        } else {
            blockedUsersCache.getBlockedUsers()
        }
        return finalUsers.map { it.toBlockedUserBackupDto() }
    }

    private fun parseAndValidateBundle(rawJson: String): Result<NodePulseBackupBundle> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("备份文件内容为空"))
        }

        val bundle = try {
            backupJson.decodeFromString<NodePulseBackupBundle>(trimmed)
        } catch (e: SerializationException) {
            return Result.failure(
                IllegalArgumentException("备份文件格式无效或已损坏：${e.message ?: "无法解析 JSON"}", e),
            )
        } catch (e: IllegalArgumentException) {
            return Result.failure(
                IllegalArgumentException("备份文件格式无效或已损坏：${e.message ?: "无法解析 JSON"}", e),
            )
        }

        if (bundle.schemaVersion < 1 || bundle.schemaVersion > NodePulseBackupBundle.CURRENT_SCHEMA_VERSION) {
            return Result.failure(
                IllegalArgumentException(
                    "不支持的备份版本 v${bundle.schemaVersion}（当前仅支持 v1～v${NodePulseBackupBundle.CURRENT_SCHEMA_VERSION}）",
                ),
            )
        }

        if (bundle.exportedAtEpochMillis < 0L) {
            return Result.failure(IllegalArgumentException("备份文件包含无效的导出时间戳"))
        }

        return Result.success(bundle)
    }

    private fun normalizeBlockedUsers(raw: List<BlockedUserBackupDto>): List<BlockedUserBackupDto> {
        val deduped = LinkedHashMap<String, BlockedUserBackupDto>()
        for (item in raw) {
            val cleanName = item.username.trim()
            if (cleanName.isEmpty()) continue
            val key = cleanName.lowercase()
            val normalized = item.copy(
                uid = item.uid.coerceAtLeast(0L),
                username = cleanName,
                avatarUrl = item.avatarUrl.trim(),
            )
            val prev = deduped[key]
            if (prev == null) {
                deduped[key] = normalized
            } else {
                deduped[key] = normalized.copy(
                    uid = if (normalized.uid > 0L) normalized.uid else prev.uid,
                    avatarUrl = normalized.avatarUrl.ifBlank { prev.avatarUrl },
                )
            }
        }
        return deduped.values.toList()
    }

    companion object {
        val backupJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
