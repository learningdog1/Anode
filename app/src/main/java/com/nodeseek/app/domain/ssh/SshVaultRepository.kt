package com.nodeseek.app.domain.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    }
}
