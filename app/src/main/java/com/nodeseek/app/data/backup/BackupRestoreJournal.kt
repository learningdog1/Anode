package com.nodeseek.app.data.backup

import androidx.room.withTransaction
import com.nodeseek.app.data.local.NodePulseDatabase
import com.nodeseek.app.data.local.SettingsSecretCipher
import com.nodeseek.app.domain.backup.NodePulseBackupBundle
import com.nodeseek.app.domain.backup.NodePulseFullBackupBundle
import com.nodeseek.app.domain.backup.RestoreStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Write-ahead rollback journal entry persisted before mutating any local repository during
 * [BackupManagerImpl.restoreBackup]. If a restore fails, is cancelled, or crashes mid-flight,
 * all local stores are rolled back to [preRestoreSnapshot] before the journal is cleared.
 */
@Serializable
data class BackupRestoreJournalEntry(
    val journalVersion: Int = CURRENT_JOURNAL_VERSION,
    val journalId: String,
    val createdAtEpochMillis: Long,
    val strategy: RestoreStrategy,
    val preRestoreSnapshot: NodePulseBackupBundle,
    val preRestoreFullSnapshot: NodePulseFullBackupBundle? = null,
) {
    companion object {
        const val CURRENT_JOURNAL_VERSION: Int = 1
        const val FULL_V2_JOURNAL_VERSION: Int = 2
    }
}

interface BackupRestoreJournalStore {
    suspend fun readPending(): BackupRestoreJournalEntry?
    suspend fun writePending(entry: BackupRestoreJournalEntry)
    suspend fun clear()
}

class InMemoryBackupRestoreJournalStore : BackupRestoreJournalStore {
    @Volatile
    private var pending: BackupRestoreJournalEntry? = null

    override suspend fun readPending(): BackupRestoreJournalEntry? = pending

    override suspend fun writePending(entry: BackupRestoreJournalEntry) {
        pending = entry
    }

    override suspend fun clear() {
        pending = null
    }
}

class FileBackupRestoreJournalStore(
    private val journalFile: File,
    private val journalCipher: SettingsSecretCipher? = null,
) : BackupRestoreJournalStore {

    private val tempFile: File
        get() = File(journalFile.parentFile ?: File("."), "${journalFile.name}.tmp")

    override suspend fun readPending(): BackupRestoreJournalEntry? = withContext(Dispatchers.IO) {
        runCatching { Files.deleteIfExists(tempFile.toPath()) }
        if (!journalFile.exists()) {
            return@withContext null
        }
        if (!journalFile.isFile) {
            throw IOException("恢复事务日志路径异常：${journalFile.absolutePath}")
        }
        val raw = journalFile.readText(Charsets.UTF_8).trim()
        if (raw.isEmpty()) {
            Files.deleteIfExists(journalFile.toPath())
            return@withContext null
        }
        val decodedJson = if (journalCipher != null && journalCipher.isEncryptedEnvelope(raw)) {
            journalCipher.decrypt(raw).trim()
        } else {
            raw
        }
        if (decodedJson.isEmpty()) {
            Files.deleteIfExists(journalFile.toPath())
            return@withContext null
        }
        try {
            BackupManagerImpl.backupJson.decodeFromString<BackupRestoreJournalEntry>(decodedJson)
        } catch (_: SerializationException) {
            // Quarantine/remove unreadable journal bytes that could not have come from a valid commit.
            Files.deleteIfExists(journalFile.toPath())
            null
        } catch (_: IllegalArgumentException) {
            Files.deleteIfExists(journalFile.toPath())
            null
        }
    }

    override suspend fun writePending(entry: BackupRestoreJournalEntry): Unit = withContext(Dispatchers.IO) {
        val parent = journalFile.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("无法创建恢复事务日志目录：${parent.absolutePath}")
        }
        val encodedJson = BackupManagerImpl.backupJson.encodeToString(entry)
        val persistedText = if (journalCipher != null) {
            journalCipher.encrypt(encodedJson)
        } else {
            encodedJson
        }
        val staging = tempFile
        try {
            FileOutputStream(staging).use { fos ->
                fos.write(persistedText.toByteArray(Charsets.UTF_8))
                fos.flush()
                runCatching { fos.fd.sync() }
            }
            try {
                Files.move(
                    staging.toPath(),
                    journalFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    staging.toPath(),
                    journalFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            Unit
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(staging.toPath()) }
            if (t is IOException) throw t
            throw IOException("写入恢复事务日志失败：${t.message.orEmpty()}", t)
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        runCatching { Files.deleteIfExists(tempFile.toPath()) }
        Files.deleteIfExists(journalFile.toPath())
        Unit
    }

    companion object {
        const val DEFAULT_JOURNAL_FILE_NAME = "backup_restore_journal_v1.json"
    }
}

/**
 * Executes Room database mutations inside a single atomic SQLite transaction where a
 * [NodePulseDatabase] is wired, or runs directly in lightweight unit tests using fake DAOs.
 */
interface LocalRestoreTransactionRunner {
    suspend fun <R> runInTransaction(block: suspend () -> R): R

    object Direct : LocalRestoreTransactionRunner {
        override suspend fun <R> runInTransaction(block: suspend () -> R): R = block()
    }
}

class RoomLocalRestoreTransactionRunner(
    private val database: NodePulseDatabase,
) : LocalRestoreTransactionRunner {
    override suspend fun <R> runInTransaction(block: suspend () -> R): R =
        database.withTransaction { block() }
}
