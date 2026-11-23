package com.nodeseek.app.domain.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thrown when the local SSH vault file failed to parse/load and write operations are blocked
 * to prevent overwriting potentially recoverable user credentials.
 */
class SshVaultCorruptedException(
    message: String,
    val backupPath: String? = null,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Persistence contract for encrypted SSH server profiles, user-defined custom scripts,
 * recent benchmark run outputs, and persisted vault collapse state.
 */
interface SshVaultRepository {
    val serversFlow: StateFlow<List<SshServerProfile>>
    val customPresetsFlow: StateFlow<List<CustomSshPreset>>
    val recentRunsFlow: StateFlow<List<SshExecutionRecord>>
    val collapseStateFlow: StateFlow<SshVaultCollapseState>
        get() = DEFAULT_COLLAPSE_STATE_FLOW
    val vaultLoadErrorFlow: StateFlow<String?>
        get() = DEFAULT_LOAD_ERROR_FLOW

    /** Ensures persisted vault state has been loaded off the main thread before reading/mutating. */
    suspend fun ensureLoaded() = Unit

    /** Re-attempts loading the vault file after a load error. Returns `true` if loaded cleanly. */
    suspend fun retryLoadVault(): Boolean = true

    /**
     * Backs up any unreadable vault file (if not already backed up) and initializes a clean empty
     * vault so the user can resume saving hosts explicitly.
     */
    suspend fun resetCorruptedVault() = Unit

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

    suspend fun syncProbeMachines(machines: List<ProbeSyncMachine>): SshProbeSyncOutcome =
        SshProbeSyncOutcome(mergedServers = serversFlow.value)

    suspend fun updateServerConnectionMode(
        serverId: String,
        mode: SshConnectionMode,
    ) = Unit

    suspend fun updateCollapseState(
        transform: (SshVaultCollapseState) -> SshVaultCollapseState,
    ) = Unit

    suspend fun saveCustomPreset(preset: CustomSshPreset): CustomSshPreset
    suspend fun deleteCustomPreset(presetId: String)

    suspend fun saveRunRecord(record: SshExecutionRecord)
    suspend fun deleteRunRecord(runId: String)
    suspend fun clearRunRecords()

    companion object {
        private val DEFAULT_COLLAPSE_STATE_FLOW: StateFlow<SshVaultCollapseState> =
            MutableStateFlow(SshVaultCollapseState()).asStateFlow()
        private val DEFAULT_LOAD_ERROR_FLOW: StateFlow<String?> =
            MutableStateFlow<String?>(null).asStateFlow()
    }
}
