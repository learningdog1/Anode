package com.nodeseek.app.data.backup

import com.nodeseek.app.BuildConfig
import com.nodeseek.app.data.local.BlockedUsersCache
import com.nodeseek.app.data.local.SettingsRepositoryImpl
import com.nodeseek.app.data.local.normalizeBlockedUserList
import com.nodeseek.app.data.netbench.NetBenchReportPreferences
import com.nodeseek.app.data.trust.OfficialTradeKeyStore
import com.nodeseek.app.domain.backup.ActivityBackupDto
import com.nodeseek.app.domain.backup.AppSettingsBackupDto
import com.nodeseek.app.domain.backup.BackupExportPayload
import com.nodeseek.app.domain.backup.BackupManager
import com.nodeseek.app.domain.backup.BackupPreviewSummary
import com.nodeseek.app.domain.backup.BackupRestoreSummary
import com.nodeseek.app.domain.backup.BackupSyncTargetAccount
import com.nodeseek.app.domain.backup.BlockedUserBackupDto
import com.nodeseek.app.domain.backup.BlocklistBackupDto
import com.nodeseek.app.domain.backup.ContentFilterBackupBridge
import com.nodeseek.app.domain.backup.ContentFilterBackupDto
import com.nodeseek.app.domain.backup.ContentFilterRuleBackupDto
import com.nodeseek.app.domain.backup.EncryptedBackupEnvelope
import com.nodeseek.app.domain.backup.EncryptedBackupErrorKind
import com.nodeseek.app.domain.backup.EncryptedBackupException
import com.nodeseek.app.domain.backup.EncryptedBackupExportPayload
import com.nodeseek.app.domain.backup.EncryptedBackupPreviewSummary
import com.nodeseek.app.domain.backup.EncryptedBackupRestoreSummary
import com.nodeseek.app.domain.backup.FleetBackupDto
import com.nodeseek.app.domain.backup.NetBenchBackupDto
import com.nodeseek.app.domain.backup.NoOpContentFilterBackupBridge
import com.nodeseek.app.domain.backup.NodePulseBackupBundle
import com.nodeseek.app.domain.backup.NodePulseFullBackupBundle
import com.nodeseek.app.domain.backup.OverseasIntelBackupDto
import com.nodeseek.app.domain.backup.PendingRemoteBlocklistSync
import com.nodeseek.app.domain.backup.RadarBackupDto
import com.nodeseek.app.domain.backup.RemoteBlocklistSyncResult
import com.nodeseek.app.domain.backup.RestockBackupDto
import com.nodeseek.app.domain.backup.RestoreStrategy
import com.nodeseek.app.domain.backup.SeekMailBackupDto
import com.nodeseek.app.domain.backup.SshVaultBackupDto
import com.nodeseek.app.domain.backup.TrustBackupDto
import com.nodeseek.app.domain.backup.ValuationBackupDto
import com.nodeseek.app.domain.backup.toBackupDto
import com.nodeseek.app.domain.backup.toBlockedUserBackupDto
import com.nodeseek.app.domain.filter.ContentBlockRule
import com.nodeseek.app.domain.filter.ContentFilterConfig
import com.nodeseek.app.domain.filter.ContentFilterEngine
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.BookmarkedTopic
import com.nodeseek.app.domain.model.EditorDraft
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.radar.KeywordWatchRule
import com.nodeseek.app.domain.radar.RadarPollingSettings
import com.nodeseek.app.domain.radar.RadarRepository
import com.nodeseek.app.domain.radar.RadarRuleCanonicalizer
import com.nodeseek.app.domain.radar.TrackedTopic
import com.nodeseek.app.domain.repository.BookmarkAndHistoryRepository
import com.nodeseek.app.domain.repository.DraftRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SettingsRepository
import com.nodeseek.app.domain.repository.UserRepository
import com.nodeseek.app.domain.ssh.SshJumpHostResolver
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.domain.valuation.ValuationCurrency
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

class BackupManagerImpl(
    private val settingsRepository: SettingsRepository,
    private val radarRepository: RadarRepository,
    private val bookmarkAndHistoryRepository: BookmarkAndHistoryRepository,
    private val draftRepository: DraftRepository,
    private val userRepository: UserRepository,
    private val sessionRepository: SessionRepository,
    private val blockedUsersCache: BlockedUsersCache,
    private val contentFilterBackupBridge: ContentFilterBackupBridge = NoOpContentFilterBackupBridge,
    private val onRadarSettingsRestored: (suspend (RadarPollingSettings) -> Unit)? = null,
    private val transactionRunner: LocalRestoreTransactionRunner = LocalRestoreTransactionRunner.Direct,
    private val restoreJournalStore: BackupRestoreJournalStore = InMemoryBackupRestoreJournalStore(),
    private val appVersionProvider: () -> String = { BuildConfig.VERSION_NAME },
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val fullBackupStores: FullBackupV2Stores = FullBackupV2Stores(),
    private val backupCryptoV2: BackupCryptoV2 = BackupCryptoV2(),
) : BackupManager {

    private val restoreMutex = Mutex()

    private val _pendingRemoteBlocklistSyncState = MutableStateFlow<PendingRemoteBlocklistSync?>(null)
    override val pendingRemoteBlocklistSyncState: StateFlow<PendingRemoteBlocklistSync?> =
        _pendingRemoteBlocklistSyncState.asStateFlow()

    override val currentSyncTargetAccountFlow: Flow<BackupSyncTargetAccount?> =
        sessionRepository.sessionState
            .map { session -> session.toSyncTargetAccount() }
            .distinctUntilChanged()

    override suspend fun recoverPendingRestoreIfNeeded(): Result<Boolean> =
        restoreMutex.withLock {
            recoverPendingRestoreLocked(notifyRadarSchedulerOnRecovery = true)
        }

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
            restoreMutex.withLock {
                recoverPendingRestoreLocked(notifyRadarSchedulerOnRecovery = true).getOrThrow()

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
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun inspectBackup(rawJson: String): Result<BackupPreviewSummary> {
        return try {
            restoreMutex.withLock {
                recoverPendingRestoreLocked(notifyRadarSchedulerOnRecovery = true).getOrThrow()
            }
            val now = clockMillis()
            parseAndPrepareBundle(rawJson = rawJson, fallbackNowMillis = now).map { prepared ->
                BackupPreviewSummary(
                    schemaVersion = prepared.schemaVersion,
                    appVersion = prepared.appVersion,
                    exportedAtEpochMillis = prepared.exportedAtEpochMillis,
                    radarKeywordRuleCount = prepared.radarKeywordRules.size,
                    radarTrackedTopicCount = prepared.radarTrackedTopics.size,
                    blockedUserCount = prepared.blockedUsers.size,
                    contentFilterRuleCount = prepared.contentFilterDto.rules.size,
                    mutedCategoryCount = prepared.contentFilterDto.mutedCategorySlugs.size,
                    bookmarkCount = prepared.bookmarks.size,
                    draftCount = prepared.drafts.size,
                    recentSearchQueryCount = prepared.settings.recentSearchQueries.size,
                    containsApiKeys = prepared.settings.nodeImageApiKey.isNotBlank() ||
                        prepared.settings.seekMailApiKey.isNotBlank(),
                    targetSyncAccount = currentSignedInTargetAccount(),
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun restoreBackup(
        rawJson: String,
        strategy: RestoreStrategy,
    ): Result<BackupRestoreSummary> {
        // 1. Upfront full validation & normalization across all 6 backup sections BEFORE mutating anything.
        val now = clockMillis()
        val prepared = parseAndPrepareBundle(
            rawJson = rawJson,
            fallbackNowMillis = now,
        ).getOrElse { error ->
            return Result.failure(error)
        }

        return restoreMutex.withLock {
            // 2. Recover any uncommitted journal left over from an earlier interrupted/crashed restore.
            recoverPendingRestoreLocked(notifyRadarSchedulerOnRecovery = true).getOrElse { recoveryError ->
                return@withLock Result.failure(recoveryError)
            }

            // 3. Snapshot all current local stores and durably write rollback journal BEFORE mutating anything.
            val preRestoreSnapshot = try {
                captureLocalSnapshotLocked()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                return@withLock Result.failure(t)
            }

            val journalEntry = BackupRestoreJournalEntry(
                journalId = UUID.randomUUID().toString(),
                createdAtEpochMillis = now,
                strategy = strategy,
                preRestoreSnapshot = preRestoreSnapshot,
            )
            try {
                restoreJournalStore.writePending(journalEntry)
            } catch (ce: CancellationException) {
                throw ce
            } catch (ioError: Throwable) {
                return@withLock Result.failure(
                    IOException(
                        "无法写入恢复事务日志，已中止恢复以保护本地数据：${ioError.message.orEmpty()}",
                        ioError,
                    ),
                )
            }

            val merge = (strategy == RestoreStrategy.MERGE)
            val replaceExisting = (strategy == RestoreStrategy.OVERWRITE)

            var reachedSettings = false
            var reachedContentFilter = false
            var reachedBlockedUsers = false
            var reachedRadar = false
            var reachedBookmarks = false
            var reachedDrafts = false

            val radarResult = try {
                coroutineContext.ensureActive()

                reachedSettings = true
                settingsRepository.restoreSettingsFromBackup(
                    backup = prepared.settings,
                    merge = merge,
                )

                coroutineContext.ensureActive()
                reachedContentFilter = true
                contentFilterBackupBridge.restore(
                    dto = prepared.contentFilterDto,
                    merge = merge,
                )

                coroutineContext.ensureActive()
                reachedBlockedUsers = true
                if (replaceExisting) {
                    blockedUsersCache.replaceAll(prepared.blockedUsers)
                } else {
                    blockedUsersCache.mergeAll(prepared.blockedUsers)
                }

                coroutineContext.ensureActive()
                val dbResult = transactionRunner.runInTransaction {
                    coroutineContext.ensureActive()
                    reachedRadar = true
                    val r = radarRepository.restoreFromBackup(
                        pollingSettings = prepared.radarPollingSettings,
                        keywordRules = prepared.radarKeywordRules,
                        trackedTopics = prepared.radarTrackedTopics,
                        replaceExisting = replaceExisting,
                    )

                    coroutineContext.ensureActive()
                    reachedBookmarks = true
                    bookmarkAndHistoryRepository.restoreBookmarks(
                        items = prepared.bookmarks,
                        replaceExisting = replaceExisting,
                    )

                    coroutineContext.ensureActive()
                    reachedDrafts = true
                    draftRepository.restoreDrafts(
                        items = prepared.drafts,
                        replaceExisting = replaceExisting,
                    )
                    r
                }

                // Commit point: remove the rollback journal now that all local stores have succeeded.
                withContext(NonCancellable) {
                    restoreJournalStore.clear()
                }
                dbResult
            } catch (t: Throwable) {
                withContext(NonCancellable) {
                    rollbackLocalStateLocked(
                        snapshot = preRestoreSnapshot,
                        rollbackSettings = reachedSettings,
                        rollbackContentFilter = reachedContentFilter,
                        rollbackBlockedUsers = reachedBlockedUsers,
                        rollbackRadar = reachedRadar,
                        rollbackBookmarks = reachedBookmarks,
                        rollbackDrafts = reachedDrafts,
                        primaryError = t,
                    )
                }
                if (t is CancellationException) {
                    throw t
                }
                return@withLock Result.failure(t)
            }

            // 4. Only AFTER local restore is committed, sync Radar background scheduling and expose
            //    pending remote blocklist sync (decoupled from local import — zero automatic server writes!).
            try {
                onRadarSettingsRestored?.invoke(radarRepository.getPollingSettings())
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Local restore has already durably committed; scheduler observer will still pick up DataStore flow.
            }

            val targetAccount = currentSignedInTargetAccount()
            _pendingRemoteBlocklistSyncState.value = if (prepared.blockedUsers.isNotEmpty()) {
                PendingRemoteBlocklistSync(
                    users = prepared.blockedUsers,
                    targetAccountAtRestore = targetAccount,
                    strategy = strategy,
                    restoredAtEpochMillis = clockMillis(),
                )
            } else {
                null
            }

            Result.success(
                BackupRestoreSummary(
                    strategy = strategy,
                    radarKeywordRulesImported = radarResult.keywordRulesImported,
                    radarTrackedTopicsImported = radarResult.trackedTopicsImported,
                    blockedUsersImported = prepared.blockedUsers.size,
                    blockedUsersSyncedToServer = 0,
                    blockedUsersServerSyncSkippedOrFailed = 0,
                    pendingRemoteBlockUsers = prepared.blockedUsers,
                    targetSyncAccount = targetAccount,
                    contentFilterRulesImported = prepared.contentFilterDto.rules.size,
                    mutedCategoriesCount = prepared.contentFilterDto.mutedCategorySlugs.size,
                    bookmarksImported = prepared.bookmarks.size,
                    draftsImported = prepared.drafts.size,
                    recentSearchQueriesCount = prepared.settings.recentSearchQueries.size,
                ),
            )
        }
    }

    override fun dismissPendingRemoteBlocklistSync() {
        _pendingRemoteBlocklistSyncState.value = null
    }

    // ────────────────────────────────────────────────────────────────────────
    // Encrypted Full Backup v2 (PBKDF2-HMAC-SHA256 + AES-256-GCM)
    // ────────────────────────────────────────────────────────────────────────

    override fun isEncryptedBackupEnvelope(rawContent: String): Boolean =
        BackupCryptoV2.isEncryptedEnvelope(rawContent)

    override suspend fun exportEncryptedFullBackup(
        passphrase: String,
    ): Result<EncryptedBackupExportPayload> {
        return try {
            BackupCryptoV2.validatePassphraseForEncryption(passphrase)
            val bundle = restoreMutex.withLock {
                recoverPendingRestoreLocked(notifyRadarSchedulerOnRecovery = true).getOrThrow()
                captureFullLocalSnapshotLocked(resolveRemoteBlockedUsers = true)
            }
            val plaintextJson = backupJson.encodeToString(bundle)
            val envelope = backupCryptoV2.encrypt(
                plaintextUtf8 = plaintextJson,
                passphrase = passphrase,
            )
            val rawEnvelopeJson = backupCryptoV2.encodeEnvelope(envelope)
            Result.success(
                EncryptedBackupExportPayload(
                    bundle = bundle,
                    envelope = envelope,
                    rawEnvelopeJson = rawEnvelopeJson,
                ),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun inspectEncryptedFullBackup(
        rawContent: String,
        passphrase: String,
    ): Result<EncryptedBackupPreviewSummary> {
        return try {
            restoreMutex.withLock {
                recoverPendingRestoreLocked(notifyRadarSchedulerOnRecovery = true).getOrThrow()
            }
            val now = clockMillis()
            val prepared = decryptAndPrepareFullBundle(
                rawContent = rawContent,
                passphrase = passphrase,
                fallbackNowMillis = now,
            ).getOrElse { return Result.failure(it) }

            val core = prepared.core
            val env = prepared.envelope
            val bundle = prepared.fullBundle
            Result.success(
                EncryptedBackupPreviewSummary(
                    formatVersion = env.formatVersion,
                    schemaVersion = bundle.schemaVersion,
                    appVersion = core.appVersion,
                    exportedAtEpochMillis = core.exportedAtEpochMillis,
                    kdfAlgorithm = env.header.kdf,
                    kdfIterations = env.header.iterations,
                    cipherAlgorithm = env.header.cipher,
                    radarKeywordRuleCount = core.radarKeywordRules.size,
                    radarTrackedTopicCount = core.radarTrackedTopics.size,
                    blockedUserCount = core.blockedUsers.size,
                    contentFilterRuleCount = core.contentFilterDto.rules.size,
                    mutedCategoryCount = core.contentFilterDto.mutedCategorySlugs.size,
                    bookmarkCount = core.bookmarks.size,
                    draftCount = core.drafts.size,
                    recentSearchQueryCount = core.settings.recentSearchQueries.size,
                    containsApiKeys = core.settings.nodeImageApiKey.isNotBlank() ||
                        core.settings.seekMailApiKey.isNotBlank() ||
                        bundle.trust.officialTradeApiKey.isNotBlank(),
                    fleetPanelCount = bundle.fleet.panels.size,
                    fleetAssetCount = bundle.fleet.assets.size,
                    restockWatchCount = bundle.restock.watchItems.size,
                    sshServerCount = bundle.sshVault.servers.size,
                    sshCustomPresetCount = bundle.sshVault.customPresets.size,
                    seekMailTagCount = bundle.seekMail.localTagStore.tags.size,
                    seekMailArchivedCount = bundle.seekMail.localArchiveStore.archivedMails.size,
                    sellerNoteCount = bundle.trust.sellerNotes.size,
                    hasOfficialTradeApiKey = bundle.trust.officialTradeApiKey.isNotBlank(),
                    overseasBlockedMerchantCount = bundle.overseasIntel.filterConfig.blockedMerchants.size,
                    overseasBlockedKeywordCount = bundle.overseasIntel.filterConfig.extraBlockedKeywords.size,
                    overseasNotificationsEnabled = bundle.overseasIntel.promoMonitorSettings.notificationsEnabled,
                    activityNotificationsEnabled = bundle.activity.monitorSettings.notificationsEnabled,
                    manualFxOverrideCount = bundle.valuation.manualFxOverridesToCny.size,
                    targetSyncAccount = currentSignedInTargetAccount(),
                ),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun restoreEncryptedFullBackup(
        rawContent: String,
        passphrase: String,
        strategy: RestoreStrategy,
    ): Result<EncryptedBackupRestoreSummary> {
        // 1. Decrypt, authenticate GCM tag & AAD, and fully validate ALL v1 + v2 sections
        //    BEFORE mutating any local store or writing a journal entry.
        val now = clockMillis()
        val prepared = decryptAndPrepareFullBundle(
            rawContent = rawContent,
            passphrase = passphrase,
            fallbackNowMillis = now,
        ).getOrElse { error ->
            return Result.failure(error)
        }

        return restoreMutex.withLock {
            // 2. Recover any uncommitted journal left over from an earlier interrupted restore.
            recoverPendingRestoreLocked(notifyRadarSchedulerOnRecovery = true).getOrElse { recoveryError ->
                return@withLock Result.failure(recoveryError)
            }

            // 3. Snapshot all current local stores (v1 + v2) and persist WAL journal BEFORE mutating anything.
            val preRestoreFullSnapshot = try {
                captureFullLocalSnapshotLocked(resolveRemoteBlockedUsers = false)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                return@withLock Result.failure(t)
            }

            val journalEntry = BackupRestoreJournalEntry(
                journalVersion = BackupRestoreJournalEntry.FULL_V2_JOURNAL_VERSION,
                journalId = UUID.randomUUID().toString(),
                createdAtEpochMillis = now,
                strategy = strategy,
                preRestoreSnapshot = preRestoreFullSnapshot.toCoreV1Bundle(),
                preRestoreFullSnapshot = preRestoreFullSnapshot,
            )
            try {
                restoreJournalStore.writePending(journalEntry)
            } catch (ce: CancellationException) {
                throw ce
            } catch (ioError: Throwable) {
                return@withLock Result.failure(
                    IOException(
                        "无法写入恢复事务日志，已中止恢复以保护本地数据：${ioError.message.orEmpty()}",
                        ioError,
                    ),
                )
            }

            val merge = (strategy == RestoreStrategy.MERGE)
            val replaceExisting = (strategy == RestoreStrategy.OVERWRITE)
            val core = prepared.core
            val bundle = prepared.fullBundle

            var reachedSettings = false
            var reachedContentFilter = false
            var reachedBlockedUsers = false
            var reachedRadar = false
            var reachedBookmarks = false
            var reachedDrafts = false
            var reachedFleet = false
            var reachedRestock = false
            var reachedSshVault = false
            var reachedSeekMail = false
            var reachedOverseasIntel = false
            var reachedOverseasPromo = false
            var reachedActivity = false
            var reachedSellerTrust = false
            var reachedOfficialTrade = false
            var reachedNetBench = false
            var reachedValuation = false

            val radarResult = try {
                // ── Core v1 stores ──
                coroutineContext.ensureActive()
                reachedSettings = true
                settingsRepository.restoreSettingsFromBackup(
                    backup = core.settings,
                    merge = merge,
                )

                coroutineContext.ensureActive()
                reachedContentFilter = true
                contentFilterBackupBridge.restore(
                    dto = core.contentFilterDto,
                    merge = merge,
                )

                coroutineContext.ensureActive()
                reachedBlockedUsers = true
                if (replaceExisting) {
                    blockedUsersCache.replaceAll(core.blockedUsers)
                } else {
                    blockedUsersCache.mergeAll(core.blockedUsers)
                }

                coroutineContext.ensureActive()
                val dbResult = transactionRunner.runInTransaction {
                    coroutineContext.ensureActive()
                    reachedRadar = true
                    val r = radarRepository.restoreFromBackup(
                        pollingSettings = core.radarPollingSettings,
                        keywordRules = core.radarKeywordRules,
                        trackedTopics = core.radarTrackedTopics,
                        replaceExisting = replaceExisting,
                    )

                    coroutineContext.ensureActive()
                    reachedBookmarks = true
                    bookmarkAndHistoryRepository.restoreBookmarks(
                        items = core.bookmarks,
                        replaceExisting = replaceExisting,
                    )

                    coroutineContext.ensureActive()
                    reachedDrafts = true
                    draftRepository.restoreDrafts(
                        items = core.drafts,
                        replaceExisting = replaceExisting,
                    )
                    r
                }

                // ── Extended v2 stores ──
                fullBackupStores.fleetRepository?.let { repo ->
                    coroutineContext.ensureActive()
                    reachedFleet = true
                    repo.restoreFromBackup(
                        backup = bundle.fleet,
                        replaceExisting = replaceExisting,
                    )
                }

                fullBackupStores.restockRepository?.let { repo ->
                    coroutineContext.ensureActive()
                    reachedRestock = true
                    repo.restoreFromBackup(
                        backup = bundle.restock,
                        replaceExisting = replaceExisting,
                    )
                }

                fullBackupStores.sshVaultRepository?.let { repo ->
                    coroutineContext.ensureActive()
                    reachedSshVault = true
                    repo.restoreFromBackup(
                        backup = bundle.sshVault,
                        replaceExisting = replaceExisting,
                    )
                }

                fullBackupStores.seekMailRepository?.let { repo ->
                    coroutineContext.ensureActive()
                    reachedSeekMail = true
                    repo.restoreFromBackup(
                        backup = bundle.seekMail,
                        replaceExisting = replaceExisting,
                    )
                }

                fullBackupStores.overseasIntelRepository?.let { repo ->
                    coroutineContext.ensureActive()
                    reachedOverseasIntel = true
                    repo.restoreFilterConfigFromBackup(
                        config = bundle.overseasIntel.filterConfig.toDomain(),
                        merge = merge,
                    )
                }

                fullBackupStores.overseasPromoPreferencesStore?.let { store ->
                    coroutineContext.ensureActive()
                    reachedOverseasPromo = true
                    store.restoreSettingsFromBackup(bundle.overseasIntel.promoMonitorSettings)
                }

                fullBackupStores.activityPreferencesStore?.let { store ->
                    coroutineContext.ensureActive()
                    reachedActivity = true
                    store.restoreSettingsFromBackup(bundle.activity.monitorSettings)
                }

                fullBackupStores.sellerTrustRepository?.let { repo ->
                    coroutineContext.ensureActive()
                    reachedSellerTrust = true
                    repo.restoreLocalNotesFromBackup(
                        notes = bundle.trust.sellerNotes,
                        replaceExisting = replaceExisting,
                    )
                }

                fullBackupStores.officialTradeRepository?.let { repo ->
                    coroutineContext.ensureActive()
                    reachedOfficialTrade = true
                    repo.restoreApiKeyFromBackup(
                        apiKey = bundle.trust.officialTradeApiKey,
                        merge = merge,
                    )
                }

                fullBackupStores.netBenchHistoryStore?.let { store ->
                    coroutineContext.ensureActive()
                    reachedNetBench = true
                    store.restorePreferencesFromBackup(
                        NetBenchReportPreferences(
                            maskTargetIp = bundle.netBench.maskTargetIp,
                            includeClientNetwork = bundle.netBench.includeClientNetwork,
                            markdownStyle = bundle.netBench.markdownStyle,
                            includeReportImages = bundle.netBench.includeReportImages,
                        ),
                    )
                }

                fullBackupStores.exchangeRateRepository?.let { repo ->
                    coroutineContext.ensureActive()
                    reachedValuation = true
                    repo.restoreManualOverridesFromBackup(
                        overrides = bundle.valuation.manualFxOverridesToCny,
                        merge = merge,
                    )
                }

                // Commit point: remove the rollback journal now that ALL local stores have succeeded.
                withContext(NonCancellable) {
                    restoreJournalStore.clear()
                }
                dbResult
            } catch (t: Throwable) {
                withContext(NonCancellable) {
                    rollbackFullLocalStateLocked(
                        snapshot = preRestoreFullSnapshot,
                        rollbackCoreSettings = reachedSettings,
                        rollbackCoreContentFilter = reachedContentFilter,
                        rollbackCoreBlockedUsers = reachedBlockedUsers,
                        rollbackCoreRadar = reachedRadar,
                        rollbackCoreBookmarks = reachedBookmarks,
                        rollbackCoreDrafts = reachedDrafts,
                        rollbackFleet = reachedFleet,
                        rollbackRestock = reachedRestock,
                        rollbackSshVault = reachedSshVault,
                        rollbackSeekMail = reachedSeekMail,
                        rollbackOverseasIntel = reachedOverseasIntel,
                        rollbackOverseasPromo = reachedOverseasPromo,
                        rollbackActivity = reachedActivity,
                        rollbackSellerTrust = reachedSellerTrust,
                        rollbackOfficialTrade = reachedOfficialTrade,
                        rollbackNetBench = reachedNetBench,
                        rollbackValuation = reachedValuation,
                        primaryError = t,
                    )
                }
                if (t is CancellationException) {
                    throw t
                }
                return@withLock Result.failure(t)
            }

            // 4. Post-commit side effects (same safe non-fatal pattern as v1).
            try {
                onRadarSettingsRestored?.invoke(radarRepository.getPollingSettings())
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Local restore already durably committed.
            }

            val targetAccount = currentSignedInTargetAccount()
            _pendingRemoteBlocklistSyncState.value = if (core.blockedUsers.isNotEmpty()) {
                PendingRemoteBlocklistSync(
                    users = core.blockedUsers,
                    targetAccountAtRestore = targetAccount,
                    strategy = strategy,
                    restoredAtEpochMillis = clockMillis(),
                )
            } else {
                null
            }

            Result.success(
                EncryptedBackupRestoreSummary(
                    strategy = strategy,
                    radarKeywordRulesImported = radarResult.keywordRulesImported,
                    radarTrackedTopicsImported = radarResult.trackedTopicsImported,
                    blockedUsersImported = core.blockedUsers.size,
                    pendingRemoteBlockUsers = core.blockedUsers,
                    targetSyncAccount = targetAccount,
                    contentFilterRulesImported = core.contentFilterDto.rules.size,
                    mutedCategoriesCount = core.contentFilterDto.mutedCategorySlugs.size,
                    bookmarksImported = core.bookmarks.size,
                    draftsImported = core.drafts.size,
                    recentSearchQueriesCount = core.settings.recentSearchQueries.size,
                    fleetPanelsImported = bundle.fleet.panels.size,
                    fleetAssetsImported = bundle.fleet.assets.size,
                    restockWatchesImported = bundle.restock.watchItems.size,
                    sshServersImported = bundle.sshVault.servers.size,
                    sshCustomPresetsImported = bundle.sshVault.customPresets.size,
                    seekMailTagsImported = bundle.seekMail.localTagStore.tags.size,
                    seekMailArchivedImported = bundle.seekMail.localArchiveStore.archivedMails.size,
                    sellerNotesImported = bundle.trust.sellerNotes.size,
                    officialTradeApiKeyImported = bundle.trust.officialTradeApiKey.isNotBlank(),
                    manualFxOverridesImported = bundle.valuation.manualFxOverridesToCny.size,
                ),
            )
        }
    }

    private data class PreparedFullBackupPlan(
        val envelope: EncryptedBackupEnvelope,
        val fullBundle: NodePulseFullBackupBundle,
        val core: PreparedBackupPlan,
    )

    private fun decryptAndPrepareFullBundle(
        rawContent: String,
        passphrase: String,
        fallbackNowMillis: Long,
    ): Result<PreparedFullBackupPlan> {
        val decrypted = try {
            backupCryptoV2.decrypt(
                rawEnvelopeJson = rawContent,
                passphrase = passphrase,
            )
        } catch (e: EncryptedBackupException) {
            return Result.failure(e)
        } catch (t: Throwable) {
            return Result.failure(
                EncryptedBackupException(
                    kind = EncryptedBackupErrorKind.WRONG_PASSPHRASE_OR_TAMPERED,
                    message = "解密备份文件失败：${t.message.orEmpty()}",
                    cause = t,
                ),
            )
        }

        val bundle = try {
            backupJson.decodeFromString<NodePulseFullBackupBundle>(decrypted.plaintextUtf8)
        } catch (e: SerializationException) {
            return Result.failure(
                EncryptedBackupException(
                    kind = EncryptedBackupErrorKind.INVALID_FORMAT,
                    message = "解密后的备份内容格式无效或已损坏",
                    cause = e,
                ),
            )
        } catch (e: IllegalArgumentException) {
            return Result.failure(
                EncryptedBackupException(
                    kind = EncryptedBackupErrorKind.INVALID_FORMAT,
                    message = "解密后的备份内容格式无效或已损坏",
                    cause = e,
                ),
            )
        }

        if (bundle.schemaVersion < 1 || bundle.schemaVersion > NodePulseFullBackupBundle.CURRENT_SCHEMA_VERSION) {
            return Result.failure(
                EncryptedBackupException(
                    kind = EncryptedBackupErrorKind.UNSUPPORTED_VERSION,
                    message = "不支持的加密备份数据版本 v${bundle.schemaVersion}（当前支持 v1～v${NodePulseFullBackupBundle.CURRENT_SCHEMA_VERSION}）",
                ),
            )
        }

        // Reuse v1 strict validation for all 5 core sections.
        val corePlan = validateAndNormalizeCoreBundle(
            bundle = bundle.toCoreV1Bundle(),
            fallbackNowMillis = fallbackNowMillis,
        ).getOrElse { error ->
            return Result.failure(error)
        }

        // Upfront validation for v2 sections BEFORE mutating anything.
        val officialKey = bundle.trust.officialTradeApiKey.trim()
        if (officialKey.isNotEmpty() && !OfficialTradeKeyStore.isValidKeyFormat(officialKey)) {
            return Result.failure(
                IllegalArgumentException("备份文件包含格式无效的官方交易平台 API Key（必须以 ntk_ 开头）"),
            )
        }

        // Validate SSH jump-host graph has no cycles.
        val sshValidationProfiles = bundle.sshVault.servers.map { dto ->
            SshServerProfile(
                id = dto.id.trim(),
                alias = dto.alias,
                host = dto.host,
                port = dto.port.coerceIn(1, 65535),
                username = dto.username,
                jumpHostId = SshJumpHostResolver.normalizeJumpHostId(dto.jumpHostId),
            )
        }
        val sshIds = sshValidationProfiles.map { it.id }.filter { it.isNotEmpty() }.toSet()
        val sshSanitized = sshValidationProfiles.map { p ->
            if (p.jumpHostId != null && (p.jumpHostId == p.id || p.jumpHostId !in sshIds)) {
                p.copy(jumpHostId = null)
            } else {
                p
            }
        }
        for (profile in sshSanitized) {
            if (profile.id.isNotEmpty()) {
                try {
                    SshJumpHostResolver.validateJumpHostSelection(
                        serverId = profile.id,
                        jumpHostId = profile.jumpHostId,
                        servers = sshSanitized,
                    )
                } catch (e: IllegalArgumentException) {
                    return Result.failure(
                        IllegalArgumentException("备份文件包含循环 SSH 跳板机配置：${e.message.orEmpty()}", e),
                    )
                }
            }
        }

        // Validate manual FX override values are finite positive BigDecimals when present.
        val cleanedFxOverrides = LinkedHashMap<String, String>()
        for ((code, rawRate) in bundle.valuation.manualFxOverridesToCny) {
            val currency = ValuationCurrency.fromCode(code) ?: continue
            if (currency == ValuationCurrency.CNY) continue
            val parsed = rawRate.trim().toBigDecimalOrNull()
            if (parsed == null || parsed <= java.math.BigDecimal.ZERO) {
                return Result.failure(
                    IllegalArgumentException("备份文件包含无效的手动汇率配置：$code=$rawRate"),
                )
            }
            cleanedFxOverrides[currency.code] = rawRate.trim()
        }

        val normalizedBundle = bundle.copy(
            settings = corePlan.settings,
            trust = bundle.trust.copy(
                sellerNotes = bundle.trust.sellerNotes.filter { it.uid > 0L && it.isMeaningful },
                officialTradeApiKey = officialKey,
            ),
            valuation = ValuationBackupDto(manualFxOverridesToCny = cleanedFxOverrides),
        )

        return Result.success(
            PreparedFullBackupPlan(
                envelope = decrypted.envelope,
                fullBundle = normalizedBundle,
                core = corePlan,
            ),
        )
    }

    private suspend fun captureFullLocalSnapshotLocked(
        resolveRemoteBlockedUsers: Boolean,
    ): NodePulseFullBackupBundle {
        val settingsDto = settingsRepository.exportSettingsForBackup(includeApiKeys = true)
        val pollingSettings = radarRepository.getPollingSettings().toBackupDto()
        val keywordRules = radarRepository.getKeywordRulesForBackup().map { it.toBackupDto() }
        val trackedTopics = radarRepository.getTrackedTopicsForBackup().map { it.toBackupDto() }
        val blockedUsers = if (resolveRemoteBlockedUsers) {
            resolveBlockedUsersForExport()
        } else {
            blockedUsersCache.getBlockedUsers().map { it.toBlockedUserBackupDto() }
        }
        val contentFilterDto = contentFilterBackupBridge.export()
        val bookmarks = bookmarkAndHistoryRepository.getAllBookmarksForBackup()
        val drafts = draftRepository.getAllDrafts()

        val fleetDto = fullBackupStores.fleetRepository?.exportForBackup() ?: FleetBackupDto()
        val restockDto = fullBackupStores.restockRepository?.exportForBackup() ?: RestockBackupDto()
        val sshVaultDto = fullBackupStores.sshVaultRepository?.exportForBackup() ?: SshVaultBackupDto()
        val seekMailDto = fullBackupStores.seekMailRepository?.exportForBackup() ?: SeekMailBackupDto()
        val overseasFilterDto = fullBackupStores.overseasIntelRepository
            ?.exportFilterConfigForBackup()
            ?.toBackupDto()
            ?: OverseasIntelBackupDto().filterConfig
        val overseasPromoSettings = fullBackupStores.overseasPromoPreferencesStore
            ?.exportSettingsForBackup()
            ?: OverseasIntelBackupDto().promoMonitorSettings
        val activitySettings = fullBackupStores.activityPreferencesStore
            ?.exportSettingsForBackup()
            ?: ActivityBackupDto().monitorSettings
        val sellerNotes = fullBackupStores.sellerTrustRepository?.getAllNotes().orEmpty()
        val officialTradeKey = fullBackupStores.officialTradeRepository?.exportApiKeyForBackup().orEmpty()
        val netBenchPrefs = fullBackupStores.netBenchHistoryStore?.exportPreferencesForBackup()
            ?: NetBenchReportPreferences()
        val manualFxOverrides = fullBackupStores.exchangeRateRepository
            ?.exportManualOverridesForBackup()
            .orEmpty()

        return NodePulseFullBackupBundle(
            schemaVersion = NodePulseFullBackupBundle.CURRENT_SCHEMA_VERSION,
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
            fleet = fleetDto,
            restock = restockDto,
            sshVault = sshVaultDto,
            seekMail = seekMailDto,
            overseasIntel = OverseasIntelBackupDto(
                filterConfig = overseasFilterDto,
                promoMonitorSettings = overseasPromoSettings,
            ),
            activity = ActivityBackupDto(
                monitorSettings = activitySettings,
            ),
            trust = TrustBackupDto(
                sellerNotes = sellerNotes,
                officialTradeApiKey = officialTradeKey,
            ),
            netBench = NetBenchBackupDto(
                maskTargetIp = netBenchPrefs.maskTargetIp,
                includeClientNetwork = netBenchPrefs.includeClientNetwork,
                markdownStyle = netBenchPrefs.markdownStyle,
                includeReportImages = netBenchPrefs.includeReportImages,
            ),
            valuation = ValuationBackupDto(
                manualFxOverridesToCny = manualFxOverrides,
            ),
        )
    }

    /**
     * Rolls back every core v1 and extended v2 local store reached during an interrupted full restore
     * back to [snapshot]. Clears [restoreJournalStore] iff ALL stores roll back cleanly.
     *
     * Note: Core settings (including `seekMailApiKey`) are rolled back BEFORE `seekMailRepository`
     * so key-partitioned SeekMail local tags/archives are restored under the pre-restore API key.
     */
    private suspend fun rollbackFullLocalStateLocked(
        snapshot: NodePulseFullBackupBundle,
        rollbackCoreSettings: Boolean,
        rollbackCoreContentFilter: Boolean,
        rollbackCoreBlockedUsers: Boolean,
        rollbackCoreRadar: Boolean,
        rollbackCoreBookmarks: Boolean,
        rollbackCoreDrafts: Boolean,
        rollbackFleet: Boolean,
        rollbackRestock: Boolean,
        rollbackSshVault: Boolean,
        rollbackSeekMail: Boolean,
        rollbackOverseasIntel: Boolean,
        rollbackOverseasPromo: Boolean,
        rollbackActivity: Boolean,
        rollbackSellerTrust: Boolean,
        rollbackOfficialTrade: Boolean,
        rollbackNetBench: Boolean,
        rollbackValuation: Boolean,
        primaryError: Throwable?,
    ): Boolean {
        val coreClean = rollbackCoreStoresOnlyLocked(
            snapshot = snapshot.toCoreV1Bundle(),
            rollbackSettings = rollbackCoreSettings,
            rollbackContentFilter = rollbackCoreContentFilter,
            rollbackBlockedUsers = rollbackCoreBlockedUsers,
            rollbackRadar = rollbackCoreRadar,
            rollbackBookmarks = rollbackCoreBookmarks,
            rollbackDrafts = rollbackCoreDrafts,
            primaryError = primaryError,
        )

        val fleetRepo = fullBackupStores.fleetRepository
        val fleetClean = if (!rollbackFleet || fleetRepo == null) {
            true
        } else {
            runCatching {
                fleetRepo.restoreFromBackup(snapshot.fleet, replaceExisting = true)
            }.onFailure { primaryError?.addSuppressed(it) }.isSuccess
        }

        val restockRepo = fullBackupStores.restockRepository
        val restockClean = if (!rollbackRestock || restockRepo == null) {
            true
        } else {
            runCatching {
                restockRepo.restoreFromBackup(snapshot.restock, replaceExisting = true)
            }.onFailure { primaryError?.addSuppressed(it) }.isSuccess
        }

        val sshVaultRepo = fullBackupStores.sshVaultRepository
        val sshVaultClean = if (!rollbackSshVault || sshVaultRepo == null) {
            true
        } else {
            runCatching {
                sshVaultRepo.restoreFromBackup(snapshot.sshVault, replaceExisting = true)
            }.onFailure { primaryError?.addSuppressed(it) }.isSuccess
        }

        val seekMailRepo = fullBackupStores.seekMailRepository
        val seekMailClean = if (!rollbackSeekMail || seekMailRepo == null) {
            true
        } else {
            runCatching {
                seekMailRepo.restoreFromBackup(snapshot.seekMail, replaceExisting = true)
            }.onFailure { primaryError?.addSuppressed(it) }.isSuccess
        }

        val overseasIntelRepo = fullBackupStores.overseasIntelRepository
        val overseasIntelClean = if (!rollbackOverseasIntel || overseasIntelRepo == null) {
            true
        } else {
            runCatching {
                overseasIntelRepo.restoreFilterConfigFromBackup(
                    config = snapshot.overseasIntel.filterConfig.toDomain(),
                    merge = false,
                )
            }.onFailure { primaryError?.addSuppressed(it) }.isSuccess
        }

        val overseasPromoStore = fullBackupStores.overseasPromoPreferencesStore
        val overseasPromoClean = if (!rollbackOverseasPromo || overseasPromoStore == null) {
            true
        } else {
            runCatching {
                overseasPromoStore.restoreSettingsFromBackup(
                    snapshot.overseasIntel.promoMonitorSettings,
                )
            }.onFailure { primaryError?.addSuppressed(it) }.isSuccess
        }

        val activityStore = fullBackupStores.activityPreferencesStore
        val activityClean = if (!rollbackActivity || activityStore == null) {
            true
        } else {
            runCatching {
                activityStore.restoreSettingsFromBackup(
                    snapshot.activity.monitorSettings,
                )
            }.onFailure { primaryError?.addSuppressed(it) }.isSuccess
        }

        val sellerTrustRepo = fullBackupStores.sellerTrustRepository
        val sellerTrustClean = if (!rollbackSellerTrust || sellerTrustRepo == null) {
            true
        } else {
            runCatching {
                sellerTrustRepo.restoreLocalNotesFromBackup(
                    notes = snapshot.trust.sellerNotes,
                    replaceExisting = true,
                )
            }.onFailure { primaryError?.addSuppressed(it) }.isSuccess
        }

        val officialTradeRepo = fullBackupStores.officialTradeRepository
        val officialTradeClean = if (!rollbackOfficialTrade || officialTradeRepo == null) {
            true
        } else {
            runCatching {
                officialTradeRepo.restoreApiKeyFromBackup(
                    apiKey = snapshot.trust.officialTradeApiKey,
                    merge = false,
                )
            }.onFailure { primaryError?.addSuppressed(it) }.isSuccess
        }

        val netBenchStore = fullBackupStores.netBenchHistoryStore
        val netBenchClean = if (!rollbackNetBench || netBenchStore == null) {
            true
        } else {
            runCatching {
                netBenchStore.restorePreferencesFromBackup(
                    NetBenchReportPreferences(
                        maskTargetIp = snapshot.netBench.maskTargetIp,
                        includeClientNetwork = snapshot.netBench.includeClientNetwork,
                        markdownStyle = snapshot.netBench.markdownStyle,
                        includeReportImages = snapshot.netBench.includeReportImages,
                    ),
                )
            }.onFailure { primaryError?.addSuppressed(it) }.isSuccess
        }

        val exchangeRateRepo = fullBackupStores.exchangeRateRepository
        val valuationClean = if (!rollbackValuation || exchangeRateRepo == null) {
            true
        } else {
            runCatching {
                exchangeRateRepo.restoreManualOverridesFromBackup(
                    overrides = snapshot.valuation.manualFxOverridesToCny,
                    merge = false,
                )
            }.onFailure { primaryError?.addSuppressed(it) }.isSuccess
        }

        val allClean = coreClean &&
            fleetClean &&
            restockClean &&
            sshVaultClean &&
            seekMailClean &&
            overseasIntelClean &&
            overseasPromoClean &&
            activityClean &&
            sellerTrustClean &&
            officialTradeClean &&
            netBenchClean &&
            valuationClean

        if (allClean) {
            runCatching { restoreJournalStore.clear() }
                .onFailure { primaryError?.addSuppressed(it) }
        }
        return allClean
    }

    override suspend fun syncBlockedUsersToServer(
        expectedAccountUid: Long,
        expectedUsername: String?,
        users: List<FloorAuthor>?,
    ): Result<RemoteBlocklistSyncResult> {
        if (expectedAccountUid <= 0L) {
            return Result.failure(IllegalArgumentException("无效的目标账号 UID"))
        }

        restoreMutex.withLock {
            recoverPendingRestoreLocked(notifyRadarSchedulerOnRecovery = true).getOrElse { error ->
                return Result.failure(error)
            }
        }

        val verifiedTarget = verifyActiveTargetAccount(
            expectedAccountUid = expectedAccountUid,
            expectedUsername = expectedUsername,
        ).getOrElse { error ->
            return Result.failure(error)
        }

        val candidates = normalizeBlockedUserList(
            users ?: _pendingRemoteBlocklistSyncState.value?.users ?: blockedUsersCache.getBlockedUsers(),
        )
        if (candidates.isEmpty()) {
            _pendingRemoteBlocklistSyncState.value = null
            return Result.success(
                RemoteBlocklistSyncResult(
                    targetAccount = verifiedTarget,
                    requestedCount = 0,
                    syncedCount = 0,
                    failedCount = 0,
                ),
            )
        }

        var syncedCount = 0
        val failedUsers = ArrayList<FloorAuthor>()

        for (blockedUser in candidates) {
            coroutineContext.ensureActive()
            // Re-verify the active signed-in account before EVERY remote mutation so an account
            // switch or sign-out mid-sync can never mutate another account's server blocklist.
            verifyActiveTargetAccount(
                expectedAccountUid = expectedAccountUid,
                expectedUsername = expectedUsername ?: verifiedTarget.username,
            ).getOrElse { accountMismatch ->
                return Result.failure(accountMismatch)
            }

            val synced = try {
                userRepository.blockUserByName(blockedUser.username).isSuccess
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                false
            }
            if (synced) {
                syncedCount++
            } else {
                failedUsers.add(blockedUser)
            }
        }

        _pendingRemoteBlocklistSyncState.value = if (failedUsers.isEmpty()) {
            null
        } else {
            PendingRemoteBlocklistSync(
                users = failedUsers,
                targetAccountAtRestore = verifiedTarget,
                strategy = _pendingRemoteBlocklistSyncState.value?.strategy ?: RestoreStrategy.MERGE,
                restoredAtEpochMillis = clockMillis(),
            )
        }

        return Result.success(
            RemoteBlocklistSyncResult(
                targetAccount = verifiedTarget,
                requestedCount = candidates.size,
                syncedCount = syncedCount,
                failedCount = failedUsers.size,
                failedUsernames = failedUsers.map { it.username },
            ),
        )
    }

    private fun verifyActiveTargetAccount(
        expectedAccountUid: Long,
        expectedUsername: String?,
    ): Result<BackupSyncTargetAccount> {
        val current = currentSignedInTargetAccount()
            ?: return Result.failure(IllegalStateException("当前未登录在线账号，已取消远程屏蔽名单同步"))
        if (current.uid != expectedAccountUid) {
            return Result.failure(
                IllegalStateException(
                    "当前登录账号已变更（确认账号 UID=$expectedAccountUid，当前为 @${current.username} UID=${current.uid}），已中止远程屏蔽同步",
                ),
            )
        }
        val cleanExpectedName = expectedUsername?.trim().orEmpty()
        if (cleanExpectedName.isNotEmpty() && !current.username.equals(cleanExpectedName, ignoreCase = true)) {
            return Result.failure(
                IllegalStateException(
                    "当前登录账号与确认的目标账号不一致（期望 @$cleanExpectedName，当前为 @${current.username}），已中止远程屏蔽同步",
                ),
            )
        }
        return Result.success(current)
    }

    private fun currentSignedInTargetAccount(): BackupSyncTargetAccount? =
        sessionRepository.sessionState.value.toSyncTargetAccount()

    private fun AccountSession?.toSyncTargetAccount(): BackupSyncTargetAccount? {
        val session = this ?: return null
        val cleanName = session.username.trim()
        if (session.uid <= 0L || cleanName.isEmpty()) return null
        return BackupSyncTargetAccount(
            uid = session.uid,
            username = cleanName,
        )
    }

    private suspend fun captureLocalSnapshotLocked(): NodePulseBackupBundle {
        val settingsDto = settingsRepository.exportSettingsForBackup(includeApiKeys = true)
        val pollingSettings = radarRepository.getPollingSettings().toBackupDto()
        val keywordRules = radarRepository.getKeywordRulesForBackup().map { it.toBackupDto() }
        val trackedTopics = radarRepository.getTrackedTopicsForBackup().map { it.toBackupDto() }
        val blockedUsers = blockedUsersCache.getBlockedUsers().map { it.toBlockedUserBackupDto() }
        val contentFilterDto = contentFilterBackupBridge.export()
        val bookmarks = bookmarkAndHistoryRepository.getAllBookmarksForBackup()
        val drafts = draftRepository.getAllDrafts()

        return NodePulseBackupBundle(
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
        )
    }

    private suspend fun recoverPendingRestoreLocked(
        notifyRadarSchedulerOnRecovery: Boolean,
    ): Result<Boolean> {
        val pending = try {
            restoreJournalStore.readPending()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return Result.failure(t)
        } ?: return Result.success(false)

        val snapshot = pending.preRestoreSnapshot
        val fullSnapshot = pending.preRestoreFullSnapshot
        return try {
            withContext(NonCancellable) {
                val cleanlyRolledBack = if (fullSnapshot != null) {
                    rollbackFullLocalStateLocked(
                        snapshot = fullSnapshot,
                        rollbackCoreSettings = true,
                        rollbackCoreContentFilter = true,
                        rollbackCoreBlockedUsers = true,
                        rollbackCoreRadar = true,
                        rollbackCoreBookmarks = true,
                        rollbackCoreDrafts = true,
                        rollbackFleet = true,
                        rollbackRestock = true,
                        rollbackSshVault = true,
                        rollbackSeekMail = true,
                        rollbackOverseasIntel = true,
                        rollbackOverseasPromo = true,
                        rollbackActivity = true,
                        rollbackSellerTrust = true,
                        rollbackOfficialTrade = true,
                        rollbackNetBench = true,
                        rollbackValuation = true,
                        primaryError = null,
                    )
                } else {
                    rollbackLocalStateLocked(
                        snapshot = snapshot,
                        rollbackSettings = true,
                        rollbackContentFilter = true,
                        rollbackBlockedUsers = true,
                        rollbackRadar = true,
                        rollbackBookmarks = true,
                        rollbackDrafts = true,
                        primaryError = null,
                    )
                }
                if (!cleanlyRolledBack) {
                    throw IOException("恢复未提交的备份事务日志失败，将在下次操作时重试")
                }
            }
            if (notifyRadarSchedulerOnRecovery) {
                onRadarSettingsRestored?.invoke(radarRepository.getPollingSettings())
            }
            Result.success(true)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Rolls back every local store reached during an interrupted restore back to [snapshot].
     * Clears [restoreJournalStore] iff all local stores are verified back at [snapshot].
     */
    private suspend fun rollbackLocalStateLocked(
        snapshot: NodePulseBackupBundle,
        rollbackSettings: Boolean,
        rollbackContentFilter: Boolean,
        rollbackBlockedUsers: Boolean,
        rollbackRadar: Boolean,
        rollbackBookmarks: Boolean,
        rollbackDrafts: Boolean,
        primaryError: Throwable?,
    ): Boolean {
        val allClean = rollbackCoreStoresOnlyLocked(
            snapshot = snapshot,
            rollbackSettings = rollbackSettings,
            rollbackContentFilter = rollbackContentFilter,
            rollbackBlockedUsers = rollbackBlockedUsers,
            rollbackRadar = rollbackRadar,
            rollbackBookmarks = rollbackBookmarks,
            rollbackDrafts = rollbackDrafts,
            primaryError = primaryError,
        )
        if (allClean) {
            runCatching { restoreJournalStore.clear() }
                .onFailure { primaryError?.addSuppressed(it) }
        }
        return allClean
    }

    private suspend fun rollbackCoreStoresOnlyLocked(
        snapshot: NodePulseBackupBundle,
        rollbackSettings: Boolean,
        rollbackContentFilter: Boolean,
        rollbackBlockedUsers: Boolean,
        rollbackRadar: Boolean,
        rollbackBookmarks: Boolean,
        rollbackDrafts: Boolean,
        primaryError: Throwable?,
    ): Boolean {
        var draftsClean = !rollbackDrafts
        var bookmarksClean = !rollbackBookmarks
        var radarClean = !rollbackRadar

        if (rollbackDrafts || rollbackBookmarks || rollbackRadar) {
            val txRollback = runCatching {
                transactionRunner.runInTransaction {
                    if (rollbackDrafts) {
                        draftRepository.restoreDrafts(
                            items = snapshot.drafts,
                            replaceExisting = true,
                        )
                        draftsClean = true
                    }
                    if (rollbackBookmarks) {
                        bookmarkAndHistoryRepository.restoreBookmarks(
                            items = snapshot.bookmarks,
                            replaceExisting = true,
                        )
                        bookmarksClean = true
                    }
                    if (rollbackRadar) {
                        radarRepository.restoreExactSnapshot(
                            pollingSettings = snapshot.radar.pollingSettings.toDomain(),
                            keywordRules = snapshot.radar.keywordRules.map { it.toDomain() },
                            trackedTopics = snapshot.radar.trackedTopics.map { it.toDomain() },
                        )
                        radarClean = true
                    }
                }
            }
            if (txRollback.isFailure) {
                // Fall back to isolated per-store rollback so one throwing store cannot block others.
                if (rollbackDrafts && !draftsClean) {
                    draftsClean = runCatching {
                        draftRepository.restoreDrafts(snapshot.drafts, replaceExisting = true)
                    }.onFailure { primaryError?.addSuppressed(it) }.isSuccess ||
                        runCatching { draftRepository.getAllDrafts() == snapshot.drafts }.getOrDefault(false)
                }
                if (rollbackBookmarks && !bookmarksClean) {
                    bookmarksClean = runCatching {
                        bookmarkAndHistoryRepository.restoreBookmarks(snapshot.bookmarks, replaceExisting = true)
                    }.onFailure { primaryError?.addSuppressed(it) }.isSuccess ||
                        runCatching {
                            bookmarkAndHistoryRepository.getAllBookmarksForBackup() == snapshot.bookmarks
                        }.getOrDefault(false)
                }
                if (rollbackRadar && !radarClean) {
                    radarClean = runCatching {
                        radarRepository.restoreExactSnapshot(
                            pollingSettings = snapshot.radar.pollingSettings.toDomain(),
                            keywordRules = snapshot.radar.keywordRules.map { it.toDomain() },
                            trackedTopics = snapshot.radar.trackedTopics.map { it.toDomain() },
                        )
                    }.onFailure { primaryError?.addSuppressed(it) }.isSuccess
                }
            }
        }

        val expectedBlockedUsers = normalizeBlockedUserList(
            snapshot.blocklist.blockedUsers.map { it.toDomain() },
        )
        val blockedUsersClean = if (!rollbackBlockedUsers) {
            true
        } else {
            runCatching {
                blockedUsersCache.replaceAll(expectedBlockedUsers)
            }.onFailure { primaryError?.addSuppressed(it) }.isSuccess ||
                runCatching { blockedUsersCache.getBlockedUsers() == expectedBlockedUsers }.getOrDefault(false)
        }

        val contentFilterClean = if (!rollbackContentFilter) {
            true
        } else {
            runCatching {
                contentFilterBackupBridge.restore(
                    dto = snapshot.blocklist.contentFilter,
                    merge = false,
                )
            }.onFailure { primaryError?.addSuppressed(it) }.isSuccess
        }

        val settingsClean = if (!rollbackSettings) {
            true
        } else {
            runCatching {
                settingsRepository.restoreSettingsFromBackup(
                    backup = snapshot.settings,
                    merge = false,
                )
            }.onFailure { primaryError?.addSuppressed(it) }.isSuccess
        }

        return draftsClean &&
            bookmarksClean &&
            radarClean &&
            blockedUsersClean &&
            contentFilterClean &&
            settingsClean
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

    private data class PreparedBackupPlan(
        val schemaVersion: Int,
        val appVersion: String,
        val exportedAtEpochMillis: Long,
        val settings: AppSettingsBackupDto,
        val radarPollingSettings: RadarPollingSettings,
        val radarKeywordRules: List<KeywordWatchRule>,
        val radarTrackedTopics: List<TrackedTopic>,
        val blockedUsers: List<FloorAuthor>,
        val contentFilterDto: ContentFilterBackupDto,
        val bookmarks: List<BookmarkedTopic>,
        val drafts: List<EditorDraft>,
    )

    private fun parseAndPrepareBundle(
        rawJson: String,
        fallbackNowMillis: Long,
    ): Result<PreparedBackupPlan> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("备份文件内容为空"))
        }
        if (BackupCryptoV2.isEncryptedEnvelope(trimmed)) {
            return Result.failure(
                EncryptedBackupException(
                    kind = EncryptedBackupErrorKind.INVALID_FORMAT,
                    message = "检测到加密完整备份（v2），请使用「加密完整备份」入口并输入口令导入",
                ),
            )
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

        return validateAndNormalizeCoreBundle(
            bundle = bundle,
            fallbackNowMillis = fallbackNowMillis,
        )
    }

    private fun validateAndNormalizeCoreBundle(
        bundle: NodePulseBackupBundle,
        fallbackNowMillis: Long,
    ): Result<PreparedBackupPlan> {
        if (bundle.exportedAtEpochMillis < 0L) {
            return Result.failure(IllegalArgumentException("备份文件包含无效的导出时间戳"))
        }

        // 1. Validate & normalize Settings
        if (!bundle.settings.fontScale.isFinite()) {
            return Result.failure(IllegalArgumentException("备份文件包含无效的字体缩放比例"))
        }
        val normalizedSettings = bundle.settings.copy(
            fontScale = bundle.settings.fontScale.coerceIn(
                SettingsRepositoryImpl.MIN_FONT_SCALE,
                SettingsRepositoryImpl.MAX_FONT_SCALE,
            ),
            pinnedCategorySlugs = bundle.settings.pinnedCategorySlugs
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toCollection(LinkedHashSet()),
            nodeImageApiKey = bundle.settings.nodeImageApiKey.trim(),
            seekMailApiKey = bundle.settings.seekMailApiKey.trim(),
            ignoredUpdateTag = bundle.settings.ignoredUpdateTag.trim(),
            awayDigestThresholdMinutes = bundle.settings.awayDigestThresholdMinutes.coerceIn(
                AppSettings.MIN_AWAY_DIGEST_THRESHOLD_MINUTES,
                AppSettings.MAX_AWAY_DIGEST_THRESHOLD_MINUTES,
            ),
            recentSearchQueries = bundle.settings.recentSearchQueries
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
                .take(SettingsRepositoryImpl.MAX_RECENT_SEARCHES),
        )

        // 2. Validate & normalize Radar (polling settings, keyword rules with canonical key, tracked topics)
        val normalizedPolling = bundle.radar.pollingSettings.toDomain()
        val parsedRadarRules = ArrayList<KeywordWatchRule>(bundle.radar.keywordRules.size)
        for (ruleDto in bundle.radar.keywordRules) {
            val mode = RadarRuleCanonicalizer.parseMatchModeStrict(ruleDto.matchMode)
                .getOrElse { return Result.failure(it) }
            parsedRadarRules.add(
                KeywordWatchRule(
                    id = ruleDto.id,
                    keyword = ruleDto.keyword,
                    excludeWords = ruleDto.excludeWords,
                    categorySlug = ruleDto.categorySlug,
                    matchMode = mode,
                    isEnabled = ruleDto.isEnabled,
                    createdAtEpochMillis = ruleDto.createdAtEpochMillis,
                ),
            )
        }
        val normalizedRadarRules = RadarRuleCanonicalizer.deduplicateForRestore(
            rules = parsedRadarRules,
            fallbackNowMillis = fallbackNowMillis,
            existingRules = emptyList(),
            strictValidation = true,
        ).getOrElse { error ->
            return Result.failure(
                IllegalArgumentException("备份文件包含无效的雷达监控规则：${error.message.orEmpty()}", error),
            )
        }

        val uniqueTopics = LinkedHashMap<Long, TrackedTopic>()
        for (topicDto in bundle.radar.trackedTopics) {
            if (topicDto.topicId <= 0L) {
                return Result.failure(
                    IllegalArgumentException("备份文件包含无效的追踪主题 ID：${topicDto.topicId}"),
                )
            }
            val domain = topicDto.toDomain()
            val prev = uniqueTopics[domain.topicId]
            if (prev == null || domain.lastUpdatedEpochMillis >= prev.lastUpdatedEpochMillis) {
                uniqueTopics[domain.topicId] = domain
            }
        }

        // 3. Validate & normalize Blocklist (blocked users + content filter rules)
        for (blockedDto in bundle.blocklist.blockedUsers) {
            if (blockedDto.username.trim().isEmpty()) {
                return Result.failure(IllegalArgumentException("备份文件包含空的屏蔽用户名"))
            }
        }
        val normalizedBlockedUsers = normalizeBlockedUserList(
            normalizeBlockedUsers(bundle.blocklist.blockedUsers).map { it.toDomain() },
        )

        val validatedFilterRules = ArrayList<ContentBlockRule>(bundle.blocklist.contentFilter.rules.size)
        for (ruleDto in bundle.blocklist.contentFilter.rules) {
            val cleanPattern = ruleDto.pattern.trim()
            if (cleanPattern.isEmpty()) {
                return Result.failure(IllegalArgumentException("备份文件包含空的内容过滤规则"))
            }
            val resolvedMode = RadarRuleCanonicalizer.parseMatchModeStrict(ruleDto.matchMode)
                .getOrElse { cause ->
                    return Result.failure(
                        IllegalArgumentException("备份文件包含不支持的内容过滤匹配模式：${ruleDto.matchMode}", cause),
                    )
                }
            val validationError = ContentFilterEngine.validateRulePattern(cleanPattern, resolvedMode)
            if (validationError != null) {
                return Result.failure(
                    IllegalArgumentException("备份文件包含无效的内容过滤规则：$validationError"),
                )
            }
            validatedFilterRules.add(
                ContentBlockRule(
                    id = ruleDto.id,
                    pattern = cleanPattern,
                    matchMode = resolvedMode,
                    categorySlug = ruleDto.categorySlug,
                    isEnabled = ruleDto.isEnabled,
                    note = ruleDto.note,
                    createdAtEpochMillis = ruleDto.createdAtEpochMillis,
                ),
            )
        }
        val normalizedContentFilterConfig = ContentFilterEngine.normalizeConfig(
            ContentFilterConfig(
                isEnabled = bundle.blocklist.contentFilter.isEnabled,
                mutedCategorySlugs = bundle.blocklist.contentFilter.mutedCategorySlugs,
                rules = validatedFilterRules,
            ),
        )
        val normalizedContentFilterDto = ContentFilterBackupDto(
            isEnabled = normalizedContentFilterConfig.isEnabled,
            mutedCategorySlugs = normalizedContentFilterConfig.mutedCategorySlugs,
            rules = normalizedContentFilterConfig.rules.map { rule ->
                ContentFilterRuleBackupDto(
                    id = rule.id,
                    pattern = rule.pattern,
                    matchMode = rule.matchMode.name,
                    categorySlug = rule.categorySlug,
                    isEnabled = rule.isEnabled,
                    note = rule.note,
                    createdAtEpochMillis = rule.createdAtEpochMillis,
                )
            },
        )

        // 4. Validate & normalize Bookmarks
        val dedupedBookmarks = LinkedHashMap<Pair<Long, Long>, BookmarkedTopic>()
        for (raw in bundle.bookmarks) {
            if (raw.accountUid < 0L) {
                return Result.failure(
                    IllegalArgumentException("备份文件包含无效的收藏账号 UID：${raw.accountUid}"),
                )
            }
            if (raw.topicId <= 0L) {
                return Result.failure(
                    IllegalArgumentException("备份文件包含无效的收藏主题 ID：${raw.topicId}"),
                )
            }
            val normalized = raw.copy(
                summary = raw.summary.copy(id = raw.topicId, isBookmarked = true),
                savedAtEpochMillis = raw.savedAtEpochMillis.coerceAtLeast(0L),
            )
            val key = normalized.accountUid to normalized.topicId
            val prev = dedupedBookmarks[key]
            if (prev == null) {
                dedupedBookmarks[key] = normalized
            } else {
                val mergedSnapshot = when {
                    normalized.offlineDetailSnapshot != null && prev.offlineDetailSnapshot != null ->
                        if (normalized.savedAtEpochMillis >= prev.savedAtEpochMillis) {
                            normalized.offlineDetailSnapshot
                        } else {
                            prev.offlineDetailSnapshot
                        }
                    else -> normalized.offlineDetailSnapshot ?: prev.offlineDetailSnapshot
                }
                dedupedBookmarks[key] = normalized.copy(
                    offlineDetailSnapshot = mergedSnapshot,
                    savedAtEpochMillis = maxOf(prev.savedAtEpochMillis, normalized.savedAtEpochMillis),
                    isSyncedWithServer = prev.isSyncedWithServer || normalized.isSyncedWithServer,
                )
            }
        }

        // 5. Validate & normalize Drafts
        val dedupedDrafts = LinkedHashMap<Pair<Long, String>, EditorDraft>()
        for (raw in bundle.drafts) {
            if (raw.accountUid < 0L) {
                return Result.failure(
                    IllegalArgumentException("备份文件包含无效的草稿账号 UID：${raw.accountUid}"),
                )
            }
            val cleanKey = raw.draftKey.trim()
            if (cleanKey.isEmpty()) {
                return Result.failure(IllegalArgumentException("备份文件包含空的草稿标识"))
            }
            if (raw.title.isBlank() && raw.markdownContent.isBlank()) {
                continue
            }
            val normalized = raw.copy(
                draftKey = cleanKey,
                categorySlug = raw.categorySlug.trim().ifBlank { "daily" },
                updatedAtMillis = if (raw.updatedAtMillis > 0L) raw.updatedAtMillis else fallbackNowMillis,
            )
            val mapKey = normalized.accountUid to normalized.draftKey
            val prev = dedupedDrafts[mapKey]
            if (prev == null || normalized.updatedAtMillis >= prev.updatedAtMillis) {
                dedupedDrafts[mapKey] = normalized
            }
        }

        return Result.success(
            PreparedBackupPlan(
                schemaVersion = bundle.schemaVersion,
                appVersion = bundle.appVersion.trim().ifBlank { "unknown" },
                exportedAtEpochMillis = bundle.exportedAtEpochMillis,
                settings = normalizedSettings,
                radarPollingSettings = normalizedPolling,
                radarKeywordRules = normalizedRadarRules,
                radarTrackedTopics = uniqueTopics.values.toList(),
                blockedUsers = normalizedBlockedUsers,
                contentFilterDto = normalizedContentFilterDto,
                bookmarks = dedupedBookmarks.values.toList(),
                drafts = dedupedDrafts.values.toList(),
            ),
        )
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

        fun scheduleStartupRecoveryIfPresent(
            scope: CoroutineScope,
            journalFile: File,
            managerProvider: () -> BackupManager,
        ) {
            scope.launch {
                if (journalFile.isFile) {
                    managerProvider().recoverPendingRestoreIfNeeded()
                }
            }
        }
    }
}
