package com.nodeseek.app.domain.backup

/**
 * Coordinates exporting and restoring full Anode local state as a versioned JSON bundle.
 */
interface BackupManager {
    suspend fun exportBackupJson(includeApiKeys: Boolean = true): Result<String>

    suspend fun exportBundle(includeApiKeys: Boolean = true): Result<NodePulseBackupBundle>

    suspend fun exportBackupPayload(includeApiKeys: Boolean = true): Result<BackupExportPayload>

    suspend fun inspectBackup(rawJson: String): Result<BackupPreviewSummary>

    suspend fun restoreBackup(
        rawJson: String,
        strategy: RestoreStrategy = RestoreStrategy.MERGE,
    ): Result<BackupRestoreSummary>
}
