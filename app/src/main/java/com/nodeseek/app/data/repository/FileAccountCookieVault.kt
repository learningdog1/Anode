package com.nodeseek.app.data.repository

import android.content.Context
import com.nodeseek.app.data.local.SettingsSecretCipher
import com.nodeseek.app.data.local.SettingsSecretCipherFactory
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AccountSlotStatus
import com.nodeseek.app.domain.model.StoredAccountSlot
import com.nodeseek.app.domain.model.StoredCookieRecord
import com.nodeseek.app.domain.repository.AccountCookieVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

@Serializable
private data class AccountCookieVaultFileDto(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val slots: List<StoredAccountSlot> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * Local AES-256-GCM encrypted cookie vault persisted to `context.filesDir/account_cookie_vault_v1.json`.
 *
 * Deliberately excluded from [com.nodeseek.app.domain.backup.BackupManager] exports so live session
 * cookies never leave the device in portable backups.
 */
class FileAccountCookieVault(
    private val vaultFile: File,
    private val cipher: SettingsSecretCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
    deferInitialLoad: Boolean = false,
) : AccountCookieVault {

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Volatile
    private var isLoaded: Boolean = false

    private val _slotsFlow = MutableStateFlow<List<StoredAccountSlot>>(emptyList())
    override val slotsFlow: StateFlow<List<StoredAccountSlot>> = _slotsFlow.asStateFlow()

    init {
        if (!deferInitialLoad) {
            loadInitialSync()
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

    override suspend fun saveOrUpdateSlot(
        session: AccountSession,
        cookies: List<StoredCookieRecord>,
        status: AccountSlotStatus,
    ) {
        if (session.uid <= 0L || session.username.isBlank()) return
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                val now = clock()
                val cookiesJson = json.encodeToString(ListSerializer(StoredCookieRecord.serializer()), cookies)
                val encrypted = cipher.encrypt(cookiesJson)
                require(encrypted.isNotBlank() && cipher.isEncryptedEnvelope(encrypted)) {
                    "Failed to encrypt account cookies"
                }

                val existingList = _slotsFlow.value
                val existing = existingList.firstOrNull { it.session.uid == session.uid }
                val updatedSlot = StoredAccountSlot(
                    session = session,
                    encryptedCookies = encrypted,
                    status = status,
                    addedAtEpochMillis = existing?.addedAtEpochMillis ?: now,
                    lastUsedAtEpochMillis = now,
                )

                val nextList = buildList {
                    add(updatedSlot)
                    for (other in existingList) {
                        if (other.session.uid == session.uid) continue
                        val adjustedStatus = if (status == AccountSlotStatus.ACTIVE && other.status == AccountSlotStatus.ACTIVE) {
                            AccountSlotStatus.READY
                        } else {
                            other.status
                        }
                        add(other.copy(status = adjustedStatus))
                    }
                }.sortedByDescending { it.lastUsedAtEpochMillis }

                persistLocked(nextList)
            }
        }
    }

    override suspend fun updateSessionMetadata(
        session: AccountSession,
        status: AccountSlotStatus,
    ) {
        if (session.uid <= 0L || session.username.isBlank()) return
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                val existingList = _slotsFlow.value
                val existing = existingList.firstOrNull { it.session.uid == session.uid } ?: return@withLock
                val now = clock()
                val updatedSlot = existing.copy(
                    session = session,
                    status = status,
                    lastUsedAtEpochMillis = now,
                )
                val nextList = existingList.map { slot ->
                    when {
                        slot.session.uid == session.uid -> updatedSlot
                        status == AccountSlotStatus.ACTIVE && slot.status == AccountSlotStatus.ACTIVE ->
                            slot.copy(status = AccountSlotStatus.READY)
                        else -> slot
                    }
                }.sortedByDescending { it.lastUsedAtEpochMillis }
                persistLocked(nextList)
            }
        }
    }

    override suspend fun getSlot(uid: Long): StoredAccountSlot? {
        if (uid <= 0L) return null
        return withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                _slotsFlow.value.firstOrNull { it.session.uid == uid }
            }
        }
    }

    override suspend fun decryptCookies(uid: Long): List<StoredCookieRecord>? {
        if (uid <= 0L) return null
        return withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                val slot = _slotsFlow.value.firstOrNull { it.session.uid == uid } ?: return@withLock null
                val envelope = slot.encryptedCookies.trim()
                if (envelope.isEmpty() || !cipher.isEncryptedEnvelope(envelope)) {
                    return@withLock null
                }
                val decrypted = cipher.decrypt(envelope)
                if (decrypted.isBlank()) {
                    return@withLock null
                }
                runCatching {
                    json.decodeFromString(ListSerializer(StoredCookieRecord.serializer()), decrypted)
                }.getOrNull()
            }
        }
    }

    override suspend fun markStatus(uid: Long, status: AccountSlotStatus) {
        if (uid <= 0L) return
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                val existingList = _slotsFlow.value
                if (existingList.none { it.session.uid == uid }) return@withLock
                val nextList = existingList.map { slot ->
                    when {
                        slot.session.uid == uid -> slot.copy(status = status)
                        status == AccountSlotStatus.ACTIVE && slot.status == AccountSlotStatus.ACTIVE ->
                            slot.copy(status = AccountSlotStatus.READY)
                        else -> slot
                    }
                }
                persistLocked(nextList)
            }
        }
    }

    override suspend fun removeSlot(uid: Long) {
        if (uid <= 0L) return
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                val existingList = _slotsFlow.value
                val nextList = existingList.filterNot { it.session.uid == uid }
                if (nextList.size == existingList.size) return@withLock
                persistLocked(nextList)
            }
        }
    }

    override suspend fun clearAll() {
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                persistLocked(emptyList())
            }
        }
    }

    private fun loadInitialSync() {
        ensureLoadedLocked()
    }

    private fun ensureLoadedLocked() {
        if (isLoaded) return
        if (!vaultFile.exists()) {
            _slotsFlow.value = emptyList()
            isLoaded = true
            return
        }
        try {
            val raw = vaultFile.readText(Charsets.UTF_8).trim()
            if (raw.isEmpty()) {
                _slotsFlow.value = emptyList()
                isLoaded = true
                return
            }
            val parsed = json.decodeFromString(AccountCookieVaultFileDto.serializer(), raw)
            val validSlots = parsed.slots
                .filter { it.session.uid > 0L && it.session.username.isNotBlank() }
                .distinctBy { it.session.uid }
                .sortedByDescending { it.lastUsedAtEpochMillis }
            _slotsFlow.value = validSlots
            isLoaded = true
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            backupCorruptedFileLocked()
            _slotsFlow.value = emptyList()
            isLoaded = true
        }
    }

    private fun backupCorruptedFileLocked() {
        if (!vaultFile.exists()) return
        val parent = vaultFile.parentFile ?: return
        val corruptFile = File(parent, "${vaultFile.name}.corrupt.${clock()}")
        if (!vaultFile.renameTo(corruptFile)) {
            runCatching {
                vaultFile.copyTo(corruptFile, overwrite = true)
                vaultFile.delete()
            }
        }
    }

    private fun persistLocked(slots: List<StoredAccountSlot>) {
        val dto = AccountCookieVaultFileDto(
            schemaVersion = AccountCookieVaultFileDto.CURRENT_SCHEMA_VERSION,
            slots = slots,
        )
        val encodedBytes = json.encodeToString(AccountCookieVaultFileDto.serializer(), dto)
            .toByteArray(Charsets.UTF_8)
        vaultFile.parentFile?.mkdirs()
        val tmpFile = File(vaultFile.parentFile, "${vaultFile.name}.tmp")
        FileOutputStream(tmpFile).use { fos ->
            fos.write(encodedBytes)
            fos.fd.sync()
        }
        if (!tmpFile.renameTo(vaultFile)) {
            FileOutputStream(vaultFile).use { fos ->
                fos.write(encodedBytes)
                fos.fd.sync()
            }
            tmpFile.delete()
        }
        _slotsFlow.value = slots
        isLoaded = true
    }

    companion object {
        const val VAULT_FILE_NAME = "account_cookie_vault_v1.json"

        fun forContext(context: Context): FileAccountCookieVault {
            val appContext = context.applicationContext ?: context
            return FileAccountCookieVault(
                vaultFile = File(appContext.filesDir, VAULT_FILE_NAME),
                cipher = SettingsSecretCipherFactory.createDefault(appContext),
            )
        }
    }
}
