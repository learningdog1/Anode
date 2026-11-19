package com.nodeseek.app.data.ssh

import android.content.Context
import com.nodeseek.app.domain.ssh.CustomSshPreset
import com.nodeseek.app.domain.ssh.DecryptedSshCredentials
import com.nodeseek.app.domain.ssh.HostKeyTrustPolicy
import com.nodeseek.app.domain.ssh.SecretCipher
import com.nodeseek.app.domain.ssh.SshExecutionRecord
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.domain.ssh.SshVaultRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
private data class SshVaultFilePayload(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val servers: List<SshServerProfile> = emptyList(),
    val customPresets: List<CustomSshPreset> = emptyList(),
    val recentRuns: List<SshExecutionRecord> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/**
 * Local encrypted SSH Server Vault persisted to `context.filesDir/lite_ssh_vault.json`.
 *
 * Encrypts all passwords, private keys, and optional key passphrases through [cipher] before
 * persisting, and performs atomic temp-file swaps protected by a [Mutex].
 */
class SshServerVault(
    private val vaultFile: File,
    private val cipher: SecretCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : SshVaultRepository {

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val initialPayload = readPayloadSync()

    private val _serversFlow = MutableStateFlow(initialPayload.servers)
    override val serversFlow: StateFlow<List<SshServerProfile>> = _serversFlow.asStateFlow()

    private val _customPresetsFlow = MutableStateFlow(initialPayload.customPresets)
    override val customPresetsFlow: StateFlow<List<CustomSshPreset>> = _customPresetsFlow.asStateFlow()

    private val _recentRunsFlow = MutableStateFlow(initialPayload.recentRuns)
    override val recentRunsFlow: StateFlow<List<SshExecutionRecord>> = _recentRunsFlow.asStateFlow()

    override suspend fun saveServer(draft: SshServerDraft): SshServerProfile =
        withContext(ioDispatcher) {
            mutex.withLock {
                val cleanHost = draft.host.trim()
                require(cleanHost.isNotEmpty()) { "服务器地址不能为空" }
                val safePort = draft.port.coerceIn(1, 65535)
                val cleanUser = draft.username.trim().ifBlank { SshServerProfile.DEFAULT_USERNAME }
                val cleanAlias = draft.alias.trim().ifBlank { cleanHost }

                val existingList = _serversFlow.value
                val existing = draft.id?.let { targetId ->
                    existingList.firstOrNull { it.id == targetId }
                }

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

                val saved = SshServerProfile(
                    id = existing?.id ?: draft.id?.takeIf { it.isNotBlank() } ?: idGenerator(),
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
                )

                val updated = if (existing != null) {
                    existingList.map { if (it.id == saved.id) saved else it }
                } else {
                    listOf(saved) + existingList
                }
                _serversFlow.value = updated
                persistCurrentStateLocked()
                saved
            }
        }

    override suspend fun deleteServer(serverId: String) {
        withContext(ioDispatcher) {
            mutex.withLock {
                val updated = _serversFlow.value.filterNot { it.id == serverId }
                if (updated.size != _serversFlow.value.size) {
                    _serversFlow.value = updated
                    persistCurrentStateLocked()
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
                _serversFlow.value = updated
                persistCurrentStateLocked()
            }
        }
    }

    override suspend fun markServerConnected(
        serverId: String,
        timestampMillis: Long,
    ) {
        withContext(ioDispatcher) {
            mutex.withLock {
                val updated = _serversFlow.value.map { profile ->
                    if (profile.id == serverId) {
                        profile.copy(lastConnectedEpochMillis = timestampMillis)
                    } else {
                        profile
                    }
                }
                _serversFlow.value = updated
                persistCurrentStateLocked()
            }
        }
    }

    override suspend fun saveCustomPreset(preset: CustomSshPreset): CustomSshPreset =
        withContext(ioDispatcher) {
            mutex.withLock {
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
                _customPresetsFlow.value = updated
                persistCurrentStateLocked()
                saved
            }
        }

    override suspend fun deleteCustomPreset(presetId: String) {
        withContext(ioDispatcher) {
            mutex.withLock {
                val updated = _customPresetsFlow.value.filterNot { it.id == presetId }
                if (updated.size != _customPresetsFlow.value.size) {
                    _customPresetsFlow.value = updated
                    persistCurrentStateLocked()
                }
            }
        }
    }

    override suspend fun saveRunRecord(record: SshExecutionRecord) {
        withContext(ioDispatcher) {
            mutex.withLock {
                val boundedOutput = if (record.sanitizedAnsiOutput.length > MAX_PERSISTED_OUTPUT_CHARS) {
                    record.sanitizedAnsiOutput.takeLast(MAX_PERSISTED_OUTPUT_CHARS)
                } else {
                    record.sanitizedAnsiOutput
                }
                val boundedRecord = record.copy(sanitizedAnsiOutput = boundedOutput)
                val updated = (listOf(boundedRecord) + _recentRunsFlow.value.filterNot { it.id == boundedRecord.id })
                    .take(MAX_RECENT_RUNS)
                _recentRunsFlow.value = updated
                persistCurrentStateLocked()
            }
        }
    }

    override suspend fun deleteRunRecord(runId: String) {
        withContext(ioDispatcher) {
            mutex.withLock {
                val updated = _recentRunsFlow.value.filterNot { it.id == runId }
                if (updated.size != _recentRunsFlow.value.size) {
                    _recentRunsFlow.value = updated
                    persistCurrentStateLocked()
                }
            }
        }
    }

    override suspend fun clearRunRecords() {
        withContext(ioDispatcher) {
            mutex.withLock {
                if (_recentRunsFlow.value.isNotEmpty()) {
                    _recentRunsFlow.value = emptyList()
                    persistCurrentStateLocked()
                }
            }
        }
    }

    private fun readPayloadSync(): SshVaultFilePayload {
        if (!vaultFile.isFile) return SshVaultFilePayload()
        return runCatching {
            val raw = vaultFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) SshVaultFilePayload()
            else json.decodeFromString<SshVaultFilePayload>(raw)
        }.getOrDefault(SshVaultFilePayload())
    }

    private fun persistCurrentStateLocked() {
        val payload = SshVaultFilePayload(
            servers = _serversFlow.value,
            customPresets = _customPresetsFlow.value,
            recentRuns = _recentRunsFlow.value,
        )
        val encoded = json.encodeToString(SshVaultFilePayload.serializer(), payload)
        vaultFile.parentFile?.mkdirs()
        val tempFile = File(vaultFile.parentFile, "${vaultFile.name}.tmp")
        tempFile.writeText(encoded, Charsets.UTF_8)
        if (!tempFile.renameTo(vaultFile)) {
            vaultFile.writeText(encoded, Charsets.UTF_8)
            tempFile.delete()
        }
    }

    companion object {
        const val VAULT_FILE_NAME: String = "lite_ssh_vault.json"
        const val MAX_RECENT_RUNS: Int = 20
        const val MAX_PERSISTED_OUTPUT_CHARS: Int = 180_000

        @Volatile
        private var sharedInstance: SshServerVault? = null

        /**
         * Returns the process-wide [SshServerVault] backed by `context.filesDir/lite_ssh_vault.json`
         * and [AndroidKeystoreAesGcmCipher], ensuring `LiteSshScreen` and `SshQuickInsertSheet`
         * share live state automatically.
         */
        fun getDefault(context: Context): SshServerVault {
            return sharedInstance ?: synchronized(this) {
                sharedInstance ?: SshServerVault(
                    vaultFile = File(context.applicationContext.filesDir, VAULT_FILE_NAME),
                    cipher = AndroidKeystoreAesGcmCipher(),
                ).also { sharedInstance = it }
            }
        }
    }
}
