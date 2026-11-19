package com.nodeseek.app.domain.ssh

import kotlinx.coroutines.flow.StateFlow

/**
 * Persistence contract for encrypted SSH server profiles, user-defined custom scripts,
 * and recent benchmark run outputs.
 */
interface SshVaultRepository {
    val serversFlow: StateFlow<List<SshServerProfile>>
    val customPresetsFlow: StateFlow<List<CustomSshPreset>>
    val recentRunsFlow: StateFlow<List<SshExecutionRecord>>

    suspend fun saveServer(draft: SshServerDraft): SshServerProfile
    suspend fun deleteServer(serverId: String)
    suspend fun decryptCredentials(profile: SshServerProfile): DecryptedSshCredentials
    suspend fun updateHostKeyFingerprint(
        serverId: String,
        fingerprintSha256: String,
        algorithm: String? = null,
    )
    suspend fun markServerConnected(
        serverId: String,
        timestampMillis: Long = System.currentTimeMillis(),
    )

    suspend fun saveCustomPreset(preset: CustomSshPreset): CustomSshPreset
    suspend fun deleteCustomPreset(presetId: String)

    suspend fun saveRunRecord(record: SshExecutionRecord)
    suspend fun deleteRunRecord(runId: String)
    suspend fun clearRunRecords()
}
