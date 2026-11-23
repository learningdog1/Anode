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

    companion object {
        private val EMPTY_PENDING_REMOTE_SYNC_STATE: StateFlow<PendingRemoteBlocklistSync?> =
            MutableStateFlow(null)
    }
}
