package com.nodeseek.app.data.ssh

import android.content.Context
import com.nodeseek.app.domain.backup.SshServerBackupDto
import com.nodeseek.app.domain.backup.SshVaultBackupDto
import com.nodeseek.app.domain.ssh.CustomSshPreset
import com.nodeseek.app.domain.ssh.DecryptedSshCredentials
import com.nodeseek.app.domain.ssh.HostKeyTrustPolicy
import com.nodeseek.app.domain.ssh.ProbeSyncMachine
import com.nodeseek.app.domain.ssh.SecretCipher
import com.nodeseek.app.domain.ssh.SshConnectionMode
import com.nodeseek.app.domain.ssh.SshExecutionRecord
import com.nodeseek.app.domain.ssh.SshJumpHostResolver
import com.nodeseek.app.domain.ssh.SshProbeSyncMerger
import com.nodeseek.app.domain.ssh.SshProbeSyncOutcome
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.domain.ssh.SshVaultCollapseState
import com.nodeseek.app.domain.ssh.SshVaultCorruptedException
import com.nodeseek.app.domain.ssh.SshVaultRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.SyncFailedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
private data class SshVaultFilePayload(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val servers: List<SshServerProfile> = emptyList(),
    val customPresets: List<CustomSshPreset> = emptyList(),
    val recentRuns: List<SshExecutionRecord> = emptyList(),
    val collapseState: SshVaultCollapseState = SshVaultCollapseState(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 2
    }
}

@Serializable
private data class SshRunsFilePayload(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val recentRuns: List<SshExecutionRecord> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/**
 * Local encrypted SSH Server Vault persisted to `context.filesDir/lite_ssh_vault.json`,
 * with large benchmark run outputs stored separately in `lite_ssh_vault_runs.json` to avoid
 * write amplification on frequent UI metadata updates.
 *
 * Encrypts all passwords, private keys, and optional key passphrases through [cipher] before
 * persisting, writes via fsynced temp files + atomic moves protected by a [Mutex], and refuses
 * to silently overwrite a corrupted vault file (backing it up to `.corrupt.<timestamp>` and
 * surfacing the error via [vaultLoadErrorFlow]).
 */
class SshServerVault(
    private val vaultFile: File,
    private val cipher: SecretCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    deferInitialLoad: Boolean = false,
) : SshVaultRepository {

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val runsFile: File = File(
        vaultFile.parentFile,
        "${vaultFile.nameWithoutExtension}_runs.json",
    )

    @Volatile
    private var isLoaded: Boolean = false

    @Volatile
    private var vaultCorrupted: Boolean = false

    @Volatile
    private var corruptBackupPath: String? = null

    private var hasSplitRunsFile: Boolean = false

    private val _serversFlow = MutableStateFlow<List<SshServerProfile>>(emptyList())
    override val serversFlow: StateFlow<List<SshServerProfile>> = _serversFlow.asStateFlow()

    private val _customPresetsFlow = MutableStateFlow<List<CustomSshPreset>>(emptyList())
    override val customPresetsFlow: StateFlow<List<CustomSshPreset>> = _customPresetsFlow.asStateFlow()

    private val _recentRunsFlow = MutableStateFlow<List<SshExecutionRecord>>(emptyList())
    override val recentRunsFlow: StateFlow<List<SshExecutionRecord>> = _recentRunsFlow.asStateFlow()

    private val _collapseStateFlow = MutableStateFlow(SshVaultCollapseState())
    override val collapseStateFlow: StateFlow<SshVaultCollapseState> = _collapseStateFlow.asStateFlow()

    private val _vaultLoadErrorFlow = MutableStateFlow<String?>(null)
    override val vaultLoadErrorFlow: StateFlow<String?> = _vaultLoadErrorFlow.asStateFlow()

    init {
        if (!deferInitialLoad) {
            loadInitialStateLocked()
        }
    }

    override suspend fun ensureLoaded() {
        if (isLoaded) return
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
            }
        }
    }

    override suspend fun retryLoadVault(): Boolean =
        withContext(ioDispatcher) {
            mutex.withLock {
                isLoaded = false
                loadInitialStateLocked()
                !vaultCorrupted
            }
        }

    override suspend fun resetCorruptedVault() {
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                if (vaultFile.isFile) {
                    ensureCorruptBackupLocked(vaultFile, isPrimaryVault = true)
                }
                vaultCorrupted = false
                _vaultLoadErrorFlow.value = null
                val emptyPayload = SshVaultFilePayload()
                atomicWriteText(
                    target = vaultFile,
                    content = json.encodeToString(SshVaultFilePayload.serializer(), emptyPayload),
                )
                _serversFlow.value = emptyList()
                _customPresetsFlow.value = emptyList()
                _collapseStateFlow.value = SshVaultCollapseState()
                isLoaded = true
            }
        }
    }

    override suspend fun saveServer(draft: SshServerDraft): SshServerProfile =
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                ensureVaultWritableLocked()

                val existingList = _serversFlow.value
                val existing = draft.id?.let { targetId ->
                    existingList.firstOrNull { it.id == targetId }
                }

                val resolvedProbePanelId = draft.probePanelId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: if (draft.preserveProbeLink) existing?.probePanelId else null
                val resolvedProbeServerId = draft.probeServerId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: if (draft.preserveProbeLink) existing?.probeServerId else null
                val isProbeLinked = resolvedProbePanelId != null && resolvedProbeServerId != null

                val cleanHost = draft.host.trim()
                val allowBlankHostForPanelTerminal =
                    isProbeLinked && draft.connectionMode == SshConnectionMode.PANEL_TERMINAL
                if (!allowBlankHostForPanelTerminal) {
                    require(cleanHost.isNotEmpty()) { "服务器地址不能为空" }
                }
                val safePort = draft.port.coerceIn(1, 65535)
                val cleanUser = draft.username.trim().ifBlank { SshServerProfile.DEFAULT_USERNAME }
                val cleanAlias = draft.alias.trim().ifBlank {
                    cleanHost.ifBlank { resolvedProbeServerId?.let { "节点 #$it" } ?: "未命名主机" }
                }

                val resolvedId = existing?.id ?: draft.id?.takeIf { it.isNotBlank() } ?: idGenerator()
                val cleanJumpHostId = SshJumpHostResolver.normalizeJumpHostId(draft.jumpHostId)
                SshJumpHostResolver.validateJumpHostSelection(
                    serverId = resolvedId,
                    jumpHostId = cleanJumpHostId,
                    servers = existingList,
                )

                val encryptedSecret = when {
                    draft.keepExistingSecret && existing != null && draft.secret.isEmpty() ->
                        existing.encryptedSecret
                    draft.secret.isEmpty() -> ""
                    else -> cipher.encrypt(draft.secret)
                }

                val encryptedPassphrase = when {
                    draft.keepExistingPassphrase && existing != null && draft.passphrase.isNullOrEmpty() ->
                        existing.encryptedPassphrase
                    draft.passphrase.isNullOrEmpty() -> null
                    else -> cipher.encrypt(draft.passphrase)
                }

                // If host or port changed on an existing entry, reset TOFU host key fingerprint so
                // the new endpoint is freshly verified on first connect.
                val endpointChanged = existing != null && (
                    !existing.host.equals(cleanHost, ignoreCase = true) ||
                        existing.port != safePort
                    )

                val resolvedGroupTag = when {
                    draft.groupEdited -> draft.groupTag.trim()
                    draft.groupTag.isNotBlank() -> draft.groupTag.trim()
                    else -> existing?.groupTag.orEmpty()
                }
                val resolvedConnectionMode = if (isProbeLinked) {
                    draft.connectionMode
                } else {
                    SshConnectionMode.DIRECT_SSH
                }

                val saved = SshServerProfile(
                    id = resolvedId,
                    alias = cleanAlias,
                    host = cleanHost,
                    port = safePort,
                    username = cleanUser,
                    authMethod = draft.authMethod,
                    encryptedSecret = encryptedSecret,
                    encryptedPassphrase = encryptedPassphrase,
                    knownHostKeyFingerprint = if (endpointChanged) null else existing?.knownHostKeyFingerprint,
                    knownHostKeyAlgorithm = if (endpointChanged) null else existing?.knownHostKeyAlgorithm,
                    lastConnectedEpochMillis = existing?.lastConnectedEpochMillis,
                    createdAtEpochMillis = existing?.createdAtEpochMillis ?: clock(),
                    jumpHostId = cleanJumpHostId,
                    groupTag = resolvedGroupTag,
                    probePanelId = resolvedProbePanelId,
                    probePanelName = draft.probePanelName?.trim()?.takeIf { it.isNotEmpty() }
                        ?: if (draft.preserveProbeLink) existing?.probePanelName else null,
                    probePanelType = draft.probePanelType?.trim()?.takeIf { it.isNotEmpty() }
                        ?: if (draft.preserveProbeLink) existing?.probePanelType else null,
                    probeServerId = resolvedProbeServerId,
                    probeRegion = draft.probeRegion.trim().ifBlank {
                        if (draft.preserveProbeLink) existing?.probeRegion.orEmpty() else ""
                    },
                    probeTags = draft.probeTags.takeIf { it.isNotEmpty() }
                        ?: if (draft.preserveProbeLink) existing?.probeTags.orEmpty() else emptyList(),
                    probeSyncedAtEpochMillis = draft.probeSyncedAtEpochMillis
                        ?: if (draft.preserveProbeLink) existing?.probeSyncedAtEpochMillis else null,
                    probeReportedHost = draft.probeReportedHost.trim().ifBlank {
                        if (draft.preserveProbeLink) existing?.probeReportedHost.orEmpty() else ""
                    },
                    probeReportedName = draft.probeReportedName.trim().ifBlank {
                        if (draft.preserveProbeLink) existing?.probeReportedName.orEmpty() else ""
                    },
                    connectionMode = resolvedConnectionMode,
                )

                val updated = if (existing != null) {
                    existingList.map { if (it.id == saved.id) saved else it }
                } else {
                    listOf(saved) + existingList
                }
                persistVaultStateLocked(
                    servers = updated,
                    customPresets = _customPresetsFlow.value,
                    collapseState = _collapseStateFlow.value,
                    throwOnCorrupt = true,
                )
                _serversFlow.value = updated
                saved
            }
        }

    override suspend fun deleteServer(serverId: String) {
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                ensureVaultWritableLocked()

                val remaining = _serversFlow.value.filterNot { it.id == serverId }
                if (remaining.size != _serversFlow.value.size) {
                    val cleaned = remaining.map { profile ->
                        if (profile.jumpHostId == serverId) {
                            profile.copy(jumpHostId = null)
                        } else {
                            profile
                        }
                    }
                    val prunedCollapse = _collapseStateFlow.value.pruneServerId(serverId)
                    persistVaultStateLocked(
                        servers = cleaned,
                        customPresets = _customPresetsFlow.value,
                        collapseState = prunedCollapse,
                        throwOnCorrupt = true,
                    )
                    _serversFlow.value = cleaned
                    _collapseStateFlow.value = prunedCollapse
                }
            }
        }
    }

    override suspend fun syncProbeMachines(machines: List<ProbeSyncMachine>): SshProbeSyncOutcome =
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                ensureVaultWritableLocked()

                val outcome = SshProbeSyncMerger.merge(
                    existingServers = _serversFlow.value,
                    incomingMachines = machines,
                    nowEpochMillis = clock(),
                    idGenerator = idGenerator,
                )
                if (outcome.mergedServers != _serversFlow.value) {
                    persistVaultStateLocked(
                        servers = outcome.mergedServers,
                        customPresets = _customPresetsFlow.value,
                        collapseState = _collapseStateFlow.value,
                        throwOnCorrupt = true,
                    )
                    _serversFlow.value = outcome.mergedServers
                }
                outcome
            }
        }

    override suspend fun updateServerConnectionMode(
        serverId: String,
        mode: SshConnectionMode,
    ) {
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                ensureVaultWritableLocked()

                var changed = false
                val updated = _serversFlow.value.map { profile ->
                    if (profile.id == serverId) {
                        val effectiveMode = if (profile.isProbeSynced) mode else SshConnectionMode.DIRECT_SSH
                        if (profile.connectionMode != effectiveMode) {
                            changed = true
                            profile.copy(connectionMode = effectiveMode)
                        } else {
                            profile
                        }
                    } else {
                        profile
                    }
                }
                if (changed) {
                    persistVaultStateLocked(
                        servers = updated,
                        customPresets = _customPresetsFlow.value,
                        collapseState = _collapseStateFlow.value,
                        throwOnCorrupt = true,
                    )
                    _serversFlow.value = updated
                }
            }
        }
    }

    override suspend fun updateCollapseState(
        transform: (SshVaultCollapseState) -> SshVaultCollapseState,
    ) {
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                val current = _collapseStateFlow.value
                val next = transform(current)
                if (next != current) {
                    if (vaultCorrupted) {
                        // Never overwrite a corrupted vault file from a UI collapse toggle.
                        return@withLock
                    }
                    persistVaultStateLocked(
                        servers = _serversFlow.value,
                        customPresets = _customPresetsFlow.value,
                        collapseState = next,
                        throwOnCorrupt = false,
                    )
                    _collapseStateFlow.value = next
                }
            }
        }
    }

    override suspend fun decryptCredentials(profile: SshServerProfile): DecryptedSshCredentials =
        withContext(ioDispatcher) {
            val secret = if (profile.encryptedSecret.isEmpty()) {
                ""
            } else {
                cipher.decrypt(profile.encryptedSecret)
            }
            val passphrase = profile.encryptedPassphrase
                ?.takeIf { it.isNotEmpty() }
                ?.let { cipher.decrypt(it) }
            DecryptedSshCredentials(
                secret = secret,
                passphrase = passphrase,
            )
        }

    override suspend fun updateHostKeyFingerprint(
        serverId: String,
        fingerprintSha256: String,
        algorithm: String?,
    ) {
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                if (vaultCorrupted) return@withLock

                val normalized = HostKeyTrustPolicy.normalizeFingerprint(fingerprintSha256)
                    ?: fingerprintSha256.trim()
                val updated = _serversFlow.value.map { profile ->
                    if (profile.id == serverId) {
                        profile.copy(
                            knownHostKeyFingerprint = normalized,
                            knownHostKeyAlgorithm = algorithm?.takeIf { it.isNotBlank() }
                                ?: profile.knownHostKeyAlgorithm,
                        )
                    } else {
                        profile
                    }
                }
                persistVaultStateLocked(
                    servers = updated,
                    customPresets = _customPresetsFlow.value,
                    collapseState = _collapseStateFlow.value,
                    throwOnCorrupt = false,
                )
                _serversFlow.value = updated
            }
        }
    }

    override suspend fun markServerConnected(
        serverId: String,
        timestampMillis: Long,
    ) {
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                if (vaultCorrupted) return@withLock

                val updated = _serversFlow.value.map { profile ->
                    if (profile.id == serverId) {
                        profile.copy(lastConnectedEpochMillis = timestampMillis)
                    } else {
                        profile
                    }
                }
                persistVaultStateLocked(
                    servers = updated,
                    customPresets = _customPresetsFlow.value,
                    collapseState = _collapseStateFlow.value,
                    throwOnCorrupt = false,
                )
                _serversFlow.value = updated
            }
        }
    }

    override suspend fun saveCustomPreset(preset: CustomSshPreset): CustomSshPreset =
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                ensureVaultWritableLocked()

                val cleanName = preset.name.trim()
                val cleanCommand = preset.command.trim()
                require(cleanName.isNotEmpty()) { "脚本名称不能为空" }
                require(cleanCommand.isNotEmpty()) { "脚本命令不能为空" }

                val existingList = _customPresetsFlow.value
                val existing = existingList.firstOrNull { it.id == preset.id }
                val saved = preset.copy(
                    id = existing?.id ?: preset.id.takeIf { it.isNotBlank() } ?: "custom_${idGenerator()}",
                    name = cleanName,
                    tabTitle = preset.tabTitle.trim().ifBlank { cleanName },
                    description = preset.description.trim(),
                    command = cleanCommand,
                    createdAtEpochMillis = existing?.createdAtEpochMillis
                        ?: preset.createdAtEpochMillis.takeIf { it > 0L }
                        ?: clock(),
                )
                val updated = if (existing != null) {
                    existingList.map { if (it.id == saved.id) saved else it }
                } else {
                    listOf(saved) + existingList
                }
                persistVaultStateLocked(
                    servers = _serversFlow.value,
                    customPresets = updated,
                    collapseState = _collapseStateFlow.value,
                    throwOnCorrupt = true,
                )
                _customPresetsFlow.value = updated
                saved
            }
        }

    override suspend fun deleteCustomPreset(presetId: String) {
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                ensureVaultWritableLocked()

                val updated = _customPresetsFlow.value.filterNot { it.id == presetId }
                if (updated.size != _customPresetsFlow.value.size) {
                    persistVaultStateLocked(
                        servers = _serversFlow.value,
                        customPresets = updated,
                        collapseState = _collapseStateFlow.value,
                        throwOnCorrupt = true,
                    )
                    _customPresetsFlow.value = updated
                }
            }
        }
    }

    override suspend fun saveRunRecord(record: SshExecutionRecord) {
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                val boundedOutput = if (record.sanitizedAnsiOutput.length > MAX_PERSISTED_OUTPUT_CHARS) {
                    record.sanitizedAnsiOutput.takeLast(MAX_PERSISTED_OUTPUT_CHARS)
                } else {
                    record.sanitizedAnsiOutput
                }
                val boundedRecord = record.copy(sanitizedAnsiOutput = boundedOutput)
                val updated = (listOf(boundedRecord) + _recentRunsFlow.value.filterNot { it.id == boundedRecord.id })
                    .take(MAX_RECENT_RUNS)
                persistRunsStateLocked(updated)
                _recentRunsFlow.value = updated
            }
        }
    }

    override suspend fun deleteRunRecord(runId: String) {
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                val updated = _recentRunsFlow.value.filterNot { it.id == runId }
                if (updated.size != _recentRunsFlow.value.size) {
                    persistRunsStateLocked(updated)
                    _recentRunsFlow.value = updated
                }
            }
        }
    }

    override suspend fun clearRunRecords() {
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                if (_recentRunsFlow.value.isNotEmpty()) {
                    persistRunsStateLocked(emptyList())
                    _recentRunsFlow.value = emptyList()
                }
            }
        }
    }

    override suspend fun exportForBackup(): SshVaultBackupDto = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLoadedLocked()
            ensureVaultWritableLocked()

            val exportedServers = _serversFlow.value.map { profile ->
                val plainSecret = if (profile.encryptedSecret.isEmpty()) {
                    ""
                } else {
                    cipher.decrypt(profile.encryptedSecret)
                }
                val plainPassphrase = profile.encryptedPassphrase
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { cipher.decrypt(it) }
                SshServerBackupDto(
                    id = profile.id,
                    alias = profile.alias,
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authMethod = profile.authMethod,
                    secret = plainSecret,
                    passphrase = plainPassphrase,
                    knownHostKeyFingerprint = profile.knownHostKeyFingerprint,
                    knownHostKeyAlgorithm = profile.knownHostKeyAlgorithm,
                    lastConnectedEpochMillis = profile.lastConnectedEpochMillis,
                    createdAtEpochMillis = profile.createdAtEpochMillis,
                    jumpHostId = profile.jumpHostId,
                    groupTag = profile.groupTag,
                    probePanelId = profile.probePanelId,
                    probePanelName = profile.probePanelName,
                    probePanelType = profile.probePanelType,
                    probeServerId = profile.probeServerId,
                    probeRegion = profile.probeRegion,
                    probeTags = profile.probeTags,
                    probeSyncedAtEpochMillis = profile.probeSyncedAtEpochMillis,
                    probeReportedHost = profile.probeReportedHost,
                    probeReportedName = profile.probeReportedName,
                    connectionMode = profile.connectionMode,
                )
            }

            SshVaultBackupDto(
                servers = exportedServers,
                customPresets = _customPresetsFlow.value,
                collapseState = _collapseStateFlow.value,
            )
        }
    }

    override suspend fun restoreFromBackup(
        backup: SshVaultBackupDto,
        replaceExisting: Boolean,
    ) {
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                ensureVaultWritableLocked()

                val existingServersById = _serversFlow.value.associateBy { it.id }
                val incomingProfiles = LinkedHashMap<String, SshServerProfile>()

                for (dto in backup.servers) {
                    val cleanId = dto.id.trim().ifBlank { idGenerator() }
                    val existing = existingServersById[cleanId]
                    val cleanHost = dto.host.trim()
                    val resolvedProbePanelId = dto.probePanelId?.trim()?.takeIf { it.isNotEmpty() }
                    val resolvedProbeServerId = dto.probeServerId?.trim()?.takeIf { it.isNotEmpty() }
                    val isProbeLinked = resolvedProbePanelId != null && resolvedProbeServerId != null
                    val resolvedMode = if (isProbeLinked) dto.connectionMode else SshConnectionMode.DIRECT_SSH
                    if (cleanHost.isEmpty() && !(isProbeLinked && resolvedMode == SshConnectionMode.PANEL_TERMINAL)) {
                        continue
                    }

                    val encryptedSecret = when {
                        dto.secret.isNotEmpty() -> cipher.encrypt(dto.secret)
                        !replaceExisting && existing != null -> existing.encryptedSecret
                        else -> ""
                    }
                    val encryptedPassphrase = when {
                        !dto.passphrase.isNullOrEmpty() -> cipher.encrypt(dto.passphrase)
                        !replaceExisting && existing != null -> existing.encryptedPassphrase
                        else -> null
                    }

                    incomingProfiles[cleanId] = SshServerProfile(
                        id = cleanId,
                        alias = dto.alias.trim().ifBlank {
                            cleanHost.ifBlank { resolvedProbeServerId?.let { "节点 #$it" } ?: "未命名主机" }
                        },
                        host = cleanHost,
                        port = dto.port.coerceIn(1, 65535),
                        username = dto.username.trim().ifBlank { SshServerProfile.DEFAULT_USERNAME },
                        authMethod = dto.authMethod,
                        encryptedSecret = encryptedSecret,
                        encryptedPassphrase = encryptedPassphrase,
                        knownHostKeyFingerprint = HostKeyTrustPolicy.normalizeFingerprint(dto.knownHostKeyFingerprint),
                        knownHostKeyAlgorithm = dto.knownHostKeyAlgorithm?.trim()?.takeIf { it.isNotEmpty() },
                        lastConnectedEpochMillis = dto.lastConnectedEpochMillis,
                        createdAtEpochMillis = dto.createdAtEpochMillis.takeIf { it > 0L }
                            ?: existing?.createdAtEpochMillis?.takeIf { it > 0L }
                            ?: clock(),
                        jumpHostId = SshJumpHostResolver.normalizeJumpHostId(dto.jumpHostId),
                        groupTag = dto.groupTag.trim(),
                        probePanelId = resolvedProbePanelId,
                        probePanelName = dto.probePanelName?.trim()?.takeIf { it.isNotEmpty() },
                        probePanelType = dto.probePanelType?.trim()?.takeIf { it.isNotEmpty() },
                        probeServerId = resolvedProbeServerId,
                        probeRegion = dto.probeRegion.trim(),
                        probeTags = dto.probeTags.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                        probeSyncedAtEpochMillis = dto.probeSyncedAtEpochMillis,
                        probeReportedHost = dto.probeReportedHost.trim(),
                        probeReportedName = dto.probeReportedName.trim(),
                        connectionMode = resolvedMode,
                    )
                }

                val mergedServerList = if (replaceExisting) {
                    incomingProfiles.values.toList()
                } else {
                    val combined = LinkedHashMap<String, SshServerProfile>()
                    _serversFlow.value.forEach { combined[it.id] = it }
                    incomingProfiles.forEach { (id, server) -> combined[id] = server }
                    combined.values.toList()
                }

                val validServerIds = mergedServerList.map { it.id }.toSet()
                val sanitizedServers = mergedServerList.map { profile ->
                    val jumpId = profile.jumpHostId
                    if (jumpId != null && (jumpId == profile.id || jumpId !in validServerIds)) {
                        profile.copy(jumpHostId = null)
                    } else {
                        profile
                    }
                }
                for (server in sanitizedServers) {
                    SshJumpHostResolver.validateJumpHostSelection(
                        serverId = server.id,
                        jumpHostId = server.jumpHostId,
                        servers = sanitizedServers,
                    )
                }

                val incomingPresets = LinkedHashMap<String, CustomSshPreset>()
                for (preset in backup.customPresets) {
                    val cleanName = preset.name.trim()
                    val cleanCommand = preset.command.trim()
                    if (cleanName.isEmpty() || cleanCommand.isEmpty()) continue
                    val cleanId = preset.id.trim().ifBlank { "custom_${idGenerator()}" }
                    incomingPresets[cleanId] = preset.copy(
                        id = cleanId,
                        name = cleanName,
                        tabTitle = preset.tabTitle.trim().ifBlank { cleanName },
                        description = preset.description.trim(),
                        command = cleanCommand,
                        createdAtEpochMillis = preset.createdAtEpochMillis.takeIf { it > 0L } ?: clock(),
                    )
                }

                val mergedPresets = if (replaceExisting) {
                    incomingPresets.values.toList()
                } else {
                    val combined = LinkedHashMap<String, CustomSshPreset>()
                    _customPresetsFlow.value.forEach { combined[it.id] = it }
                    incomingPresets.forEach { (id, preset) -> combined[id] = preset }
                    combined.values.toList()
                }

                val nextCollapse = if (replaceExisting) {
                    backup.collapseState.copy(
                        expandedServerIds = backup.collapseState.expandedServerIds.filter { it in validServerIds }.toSet(),
                    )
                } else {
                    val current = _collapseStateFlow.value
                    SshVaultCollapseState(
                        isVaultListCollapsed = current.isVaultListCollapsed,
                        collapsedGroupKeys = current.collapsedGroupKeys + backup.collapseState.collapsedGroupKeys,
                        expandedServerIds = (current.expandedServerIds + backup.collapseState.expandedServerIds)
                            .filter { it in validServerIds }
                            .toSet(),
                    )
                }

                persistVaultStateLocked(
                    servers = sanitizedServers,
                    customPresets = mergedPresets,
                    collapseState = nextCollapse,
                    throwOnCorrupt = true,
                )
                _serversFlow.value = sanitizedServers
                _customPresetsFlow.value = mergedPresets
                _collapseStateFlow.value = nextCollapse
            }
        }
    }

    private fun ensureLoadedLocked() {
        if (!isLoaded) {
            loadInitialStateLocked()
        }
    }

    private fun ensureVaultWritableLocked() {
        if (vaultCorrupted) {
            throw SshVaultCorruptedException(
                message = _vaultLoadErrorFlow.value
                    ?: "本地 SSH 保险库文件已损坏，已暂停写入以保护原始数据",
                backupPath = corruptBackupPath,
            )
        }
    }

    private fun loadInitialStateLocked() {
        val vaultPayload = readPrimaryVaultFileLocked()
        val runs = readRunsFileLocked(legacyFallbackRuns = vaultPayload?.recentRuns.orEmpty())

        if (vaultPayload != null) {
            _serversFlow.value = vaultPayload.servers
            _customPresetsFlow.value = vaultPayload.customPresets
            _collapseStateFlow.value = vaultPayload.collapseState
        } else {
            _serversFlow.value = emptyList()
            _customPresetsFlow.value = emptyList()
            _collapseStateFlow.value = SshVaultCollapseState()
        }
        _recentRunsFlow.value = runs
        isLoaded = true
    }

    /**
     * Reads [vaultFile]. Returns `null` when [vaultFile] exists, is non-blank, and fails to read
     * or parse — in which case [vaultCorrupted] is set to `true`, a `.corrupt.<timestamp>` backup
     * is created, and [_vaultLoadErrorFlow] is populated without modifying [vaultFile].
     */
    private fun readPrimaryVaultFileLocked(): SshVaultFilePayload? {
        if (!vaultFile.isFile) {
            vaultCorrupted = false
            corruptBackupPath = null
            _vaultLoadErrorFlow.value = null
            return SshVaultFilePayload()
        }
        return try {
            val raw = vaultFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) {
                vaultCorrupted = false
                corruptBackupPath = null
                _vaultLoadErrorFlow.value = null
                SshVaultFilePayload()
            } else {
                val decoded = json.decodeFromString<SshVaultFilePayload>(raw)
                vaultCorrupted = false
                corruptBackupPath = null
                _vaultLoadErrorFlow.value = null
                decoded.copy(
                    servers = decoded.servers.map { server ->
                        val normalizedJump = SshJumpHostResolver.normalizeJumpHostId(server.jumpHostId)
                        if (normalizedJump == server.jumpHostId) server else server.copy(jumpHostId = normalizedJump)
                    },
                )
            }
        } catch (_: Throwable) {
            val backupFile = ensureCorruptBackupLocked(vaultFile, isPrimaryVault = true)
            vaultCorrupted = true
            val backupHint = backupFile?.name?.let { "（已备份至 $it）" }.orEmpty()
            _vaultLoadErrorFlow.value =
                "检测到本地 SSH 保险库文件损坏$backupHint，已暂停写入以防止覆盖原数据；可尝试「重试读取」或「备份并重置」。"
            null
        }
    }

    private fun readRunsFileLocked(legacyFallbackRuns: List<SshExecutionRecord>): List<SshExecutionRecord> {
        if (!runsFile.isFile) {
            hasSplitRunsFile = false
            return legacyFallbackRuns
        }
        hasSplitRunsFile = true
        return try {
            val raw = runsFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) {
                emptyList()
            } else {
                json.decodeFromString<SshRunsFilePayload>(raw).recentRuns
            }
        } catch (_: Throwable) {
            ensureCorruptBackupLocked(runsFile, isPrimaryVault = false)
            emptyList()
        }
    }

    private fun ensureCorruptBackupLocked(
        sourceFile: File,
        isPrimaryVault: Boolean,
    ): File? {
        if (isPrimaryVault) {
            val existingPath = corruptBackupPath
            if (existingPath != null) {
                val existingBackup = File(existingPath)
                if (existingBackup.isFile) return existingBackup
            }
        }
        if (!sourceFile.isFile) return null
        return runCatching {
            val parent = sourceFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val ts = clock()
            var candidate = File(parent, "${sourceFile.name}.corrupt.$ts")
            var suffix = 1
            while (candidate.exists()) {
                candidate = File(parent, "${sourceFile.name}.corrupt.$ts.$suffix")
                suffix++
            }
            sourceFile.copyTo(candidate, overwrite = false)
            if (isPrimaryVault) {
                corruptBackupPath = candidate.absolutePath
            }
            candidate
        }.getOrNull()
    }

    private fun persistVaultStateLocked(
        servers: List<SshServerProfile>,
        customPresets: List<CustomSshPreset>,
        collapseState: SshVaultCollapseState,
        throwOnCorrupt: Boolean,
    ) {
        if (vaultCorrupted) {
            if (throwOnCorrupt) {
                ensureVaultWritableLocked()
            }
            return
        }
        // Migrate legacy inline recentRuns into the sibling runs file before stripping them from
        // the primary vault payload so no history records are ever dropped.
        val currentRuns = _recentRunsFlow.value
        if (!hasSplitRunsFile && currentRuns.isNotEmpty()) {
            persistRunsStateLocked(currentRuns)
        }
        val payload = SshVaultFilePayload(
            servers = servers,
            customPresets = customPresets,
            recentRuns = emptyList(),
            collapseState = collapseState,
        )
        val encoded = json.encodeToString(SshVaultFilePayload.serializer(), payload)
        atomicWriteText(target = vaultFile, content = encoded)
    }

    private fun persistRunsStateLocked(runs: List<SshExecutionRecord>) {
        val payload = SshRunsFilePayload(recentRuns = runs)
        val encoded = json.encodeToString(SshRunsFilePayload.serializer(), payload)
        atomicWriteText(target = runsFile, content = encoded)
        hasSplitRunsFile = true
    }

    private fun atomicWriteText(target: File, content: String) {
        val parent = target.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tempFile = File(parent, "${target.name}.tmp")
        val bytes = content.toByteArray(Charsets.UTF_8)
        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(bytes)
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (_: SyncFailedException) {
                    // Best-effort sync on filesystems where regular file fsync is unsupported.
                }
            }
            try {
                Files.move(
                    tempFile.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempFile.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (t: Throwable) {
            runCatching { tempFile.delete() }
            throw t
        }
    }

    companion object {
        const val VAULT_FILE_NAME: String = "lite_ssh_vault.json"
        const val MAX_RECENT_RUNS: Int = 20
        const val MAX_PERSISTED_OUTPUT_CHARS: Int = 180_000

        @Volatile
        private var sharedInstance: SshServerVault? = null

        private val backgroundLoadScope: CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Returns the process-wide [SshServerVault] backed by `context.filesDir/lite_ssh_vault.json`
         * and [AndroidKeystoreAesGcmCipher], ensuring `LiteSshScreen` and `SshQuickInsertSheet`
         * share live state automatically without blocking the main thread on cold startup.
         */
        fun getDefault(context: Context): SshServerVault {
            return sharedInstance ?: synchronized(this) {
                sharedInstance ?: SshServerVault(
                    vaultFile = File(context.applicationContext.filesDir, VAULT_FILE_NAME),
                    cipher = AndroidKeystoreAesGcmCipher(),
                    deferInitialLoad = true,
                ).also { vault ->
                    sharedInstance = vault
                    backgroundLoadScope.launch {
                        vault.ensureLoaded()
                    }
                }
            }
        }
    }
}
