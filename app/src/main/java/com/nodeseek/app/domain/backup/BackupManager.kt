package com.nodeseek.app.domain.backup

import com.nodeseek.app.domain.model.FloorAuthor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Coordinates exporting and restoring full Anode local state as a versioned JSON bundle.
 */
interface BackupManager {
    val pendingRemoteBlocklistSyncState: StateFlow<PendingRemoteBlocklistSync?>
        get() = EMPTY_PENDING_REMOTE_SYNC_STATE

    val currentSyncTargetAccountFlow: Flow<BackupSyncTargetAccount?>
        get() = flowOf(null)

    suspend fun exportBackupJson(includeApiKeys: Boolean = false): Result<String>

    suspend fun exportBundle(includeApiKeys: Boolean = false): Result<NodePulseBackupBundle>

    suspend fun exportBackupPayload(includeApiKeys: Boolean = false): Result<BackupExportPayload>

    suspend fun inspectBackup(rawJson: String): Result<BackupPreviewSummary>

    suspend fun restoreBackup(
        rawJson: String,
        strategy: RestoreStrategy = RestoreStrategy.MERGE,
    ): Result<BackupRestoreSummary>

    fun dismissPendingRemoteBlocklistSync() = Unit

    suspend fun syncBlockedUsersToServer(
        expectedAccountUid: Long,
        expectedUsername: String? = null,
        users: List<FloorAuthor>? = null,
    ): Result<RemoteBlocklistSyncResult> = Result.failure(
        UnsupportedOperationException("Remote blocklist sync is not supported"),
    )

    suspend fun recoverPendingRestoreIfNeeded(): Result<Boolean> = Result.success(false)

    // ── Encrypted Full Backup v2 (passphrase-protected PBKDF2-HMAC-SHA256 + AES-256-GCM) ──

    fun isEncryptedBackupEnvelope(rawContent: String): Boolean = false

    suspend fun exportEncryptedFullBackup(
        passphrase: String,
    ): Result<EncryptedBackupExportPayload> = Result.failure(
        UnsupportedOperationException("Encrypted full backup v2 is not supported"),
    )

    suspend fun inspectEncryptedFullBackup(
        rawContent: String,
        passphrase: String,
    ): Result<EncryptedBackupPreviewSummary> = Result.failure(
        UnsupportedOperationException("Encrypted full backup v2 is not supported"),
    )

    suspend fun restoreEncryptedFullBackup(
        rawContent: String,
        passphrase: String,
        strategy: RestoreStrategy = RestoreStrategy.MERGE,
    ): Result<EncryptedBackupRestoreSummary> = Result.failure(
        UnsupportedOperationException("Encrypted full backup v2 is not supported"),
    )

    companion object {
        private val EMPTY_PENDING_REMOTE_SYNC_STATE: StateFlow<PendingRemoteBlocklistSync?> =
            MutableStateFlow(null)
    }
}
