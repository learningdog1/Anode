package com.nodeseek.app.data.mail

import com.nodeseek.app.domain.mail.SeekMailContact
import com.nodeseek.app.domain.mail.SeekMailDetail
import com.nodeseek.app.domain.mail.SeekMailPage
import com.nodeseek.app.domain.mail.SeekMailTag
import com.nodeseek.app.domain.mail.SeekMailbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

@Serializable
data class CachedMailboxesEntry(
    val fetchedAtEpochMillis: Long,
    val mailboxes: List<SeekMailbox>,
)

@Serializable
data class CachedTagsEntry(
    val fetchedAtEpochMillis: Long,
    val tags: List<SeekMailTag>,
)

@Serializable
data class CachedContactsEntry(
    val fetchedAtEpochMillis: Long,
    val limit: Int,
    val contacts: List<SeekMailContact>,
)

@Serializable
data class CachedMailPageEntry(
    val fetchedAtEpochMillis: Long,
    val page: SeekMailPage,
)

@Serializable
data class CachedMailDetailEntry(
    val fetchedAtEpochMillis: Long,
    val detail: SeekMailDetail,
)

@Serializable
private data class CachedLocallyReadIdsEntry(
    val updatedAtEpochMillis: Long,
    val mailIds: Set<Long>,
)

/**
 * Two-tier (in-memory + atomic JSON disk) cache for Seek Mail (`seek.li`) resources.
 *
 * All entries are strictly partitioned by the first 16 hex characters of the SHA-256 hash of
 * the trimmed API key so switching or clearing keys never leaks cached messages or local read state
 * across accounts, and raw API keys are never written to cache filenames or JSON payloads.
 */
class SeekMailDiskCache(
    private val cacheDir: File,
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val json: Json = SeekMailJson
    private val diskMutex = Mutex()
    private val ioDispatcher: CoroutineContext =
        ioScope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.IO

    private val mailboxesMemory = ConcurrentHashMap<String, CachedMailboxesEntry>()
    private val tagsMemory = ConcurrentHashMap<String, CachedTagsEntry>()
    private val contactsMemory = ConcurrentHashMap<String, CachedContactsEntry>()
    private val mailPageMemory = ConcurrentHashMap<String, CachedMailPageEntry>()
    private val mailDetailMemory = ConcurrentHashMap<String, CachedMailDetailEntry>()
    private val locallyReadMemory = ConcurrentHashMap<String, Set<Long>>()

    suspend fun getMailboxes(apiKey: String): CachedMailboxesEntry? {
        val ns = keyNamespace(apiKey) ?: return null
        val key = "${ns}_mailboxes"
        mailboxesMemory[key]?.let { return it }
        return readFromDisk(key, CachedMailboxesEntry.serializer())?.also {
            mailboxesMemory[key] = it
        }
    }

    suspend fun saveMailboxes(
        apiKey: String,
        mailboxes: List<SeekMailbox>,
        fetchedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val ns = keyNamespace(apiKey) ?: return
        val key = "${ns}_mailboxes"
        val entry = CachedMailboxesEntry(
            fetchedAtEpochMillis = fetchedAtEpochMillis,
            mailboxes = mailboxes,
        )
        mailboxesMemory[key] = entry
        writeToDisk(key, CachedMailboxesEntry.serializer(), entry)
    }

    suspend fun invalidateMailboxes(apiKey: String) {
        val ns = keyNamespace(apiKey) ?: return
        val key = "${ns}_mailboxes"
        mailboxesMemory.remove(key)
        deleteDiskKey(key)
    }

    suspend fun getTags(apiKey: String): CachedTagsEntry? {
        val ns = keyNamespace(apiKey) ?: return null
        val key = "${ns}_tags"
        tagsMemory[key]?.let { return it }
        return readFromDisk(key, CachedTagsEntry.serializer())?.also {
            tagsMemory[key] = it
        }
    }

    suspend fun saveTags(
        apiKey: String,
        tags: List<SeekMailTag>,
        fetchedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val ns = keyNamespace(apiKey) ?: return
        val key = "${ns}_tags"
        val entry = CachedTagsEntry(
            fetchedAtEpochMillis = fetchedAtEpochMillis,
            tags = tags,
        )
        tagsMemory[key] = entry
        writeToDisk(key, CachedTagsEntry.serializer(), entry)
    }

    suspend fun getContacts(apiKey: String): CachedContactsEntry? {
        val ns = keyNamespace(apiKey) ?: return null
        val key = "${ns}_contacts"
        contactsMemory[key]?.let { return it }
        return readFromDisk(key, CachedContactsEntry.serializer())?.also {
            contactsMemory[key] = it
        }
    }

    suspend fun saveContacts(
        apiKey: String,
        limit: Int,
        contacts: List<SeekMailContact>,
        fetchedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val ns = keyNamespace(apiKey) ?: return
        val key = "${ns}_contacts"
        val entry = CachedContactsEntry(
            fetchedAtEpochMillis = fetchedAtEpochMillis,
            limit = limit,
            contacts = contacts,
        )
        contactsMemory[key] = entry
        writeToDisk(key, CachedContactsEntry.serializer(), entry)
    }

    suspend fun getDefaultMailsPage(
        apiKey: String,
        mailboxId: Long? = null,
    ): CachedMailPageEntry? {
        val ns = keyNamespace(apiKey) ?: return null
        val key = defaultMailPageKey(ns, mailboxId)
        mailPageMemory[key]?.let { return it }
        return readFromDisk(key, CachedMailPageEntry.serializer())?.also {
            mailPageMemory[key] = it
        }
    }

    suspend fun saveDefaultMailsPage(
        apiKey: String,
        mailboxId: Long? = null,
        page: SeekMailPage,
        fetchedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val ns = keyNamespace(apiKey) ?: return
        val key = defaultMailPageKey(ns, mailboxId)
        val entry = CachedMailPageEntry(
            fetchedAtEpochMillis = fetchedAtEpochMillis,
            page = page,
        )
        mailPageMemory[key] = entry
        trimMemoryMapIfNeeded(mailPageMemory, MAX_PAGE_ENTRIES) { it.fetchedAtEpochMillis }
        writeToDisk(key, CachedMailPageEntry.serializer(), entry)
    }

    suspend fun getMailDetail(
        apiKey: String,
        mailId: Long,
    ): CachedMailDetailEntry? {
        val ns = keyNamespace(apiKey) ?: return null
        val key = detailKey(ns, mailId)
        mailDetailMemory[key]?.let { return it }
        return readFromDisk(key, CachedMailDetailEntry.serializer())?.also {
            mailDetailMemory[key] = it
        }
    }

    suspend fun saveMailDetail(
        apiKey: String,
        mailId: Long,
        detail: SeekMailDetail,
        fetchedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val ns = keyNamespace(apiKey) ?: return
        val key = detailKey(ns, mailId)
        val entry = CachedMailDetailEntry(
            fetchedAtEpochMillis = fetchedAtEpochMillis,
            detail = detail,
        )
        mailDetailMemory[key] = entry
        trimMemoryMapIfNeeded(mailDetailMemory, MAX_DETAIL_ENTRIES) { it.fetchedAtEpochMillis }
        writeToDisk(
            key = key,
            serializer = CachedMailDetailEntry.serializer(),
            value = entry,
            prunePrefix = "${ns}_detail_",
            maxForPrefix = MAX_DETAIL_ENTRIES,
        )
    }

    suspend fun getLocallyReadIds(apiKey: String): Set<Long> {
        val ns = keyNamespace(apiKey) ?: return emptySet()
        val key = "${ns}_read_ids"
        locallyReadMemory[key]?.let { return it }
        val loaded = readFromDisk(key, CachedLocallyReadIdsEntry.serializer())?.mailIds ?: emptySet()
        locallyReadMemory[key] = loaded
        return loaded
    }

    suspend fun saveLocallyReadIds(
        apiKey: String,
        mailIds: Set<Long>,
        updatedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val ns = keyNamespace(apiKey) ?: return
        val key = "${ns}_read_ids"
        val snapshot = mailIds.toSet()
        locallyReadMemory[key] = snapshot
        writeToDisk(
            key = key,
            serializer = CachedLocallyReadIdsEntry.serializer(),
            value = CachedLocallyReadIdsEntry(
                updatedAtEpochMillis = updatedAtEpochMillis,
                mailIds = snapshot,
            ),
        )
    }

    suspend fun clearForKey(apiKey: String) {
        val ns = keyNamespace(apiKey) ?: return
        val prefix = "${ns}_"
        removeKeysWithPrefix(mailboxesMemory, prefix)
        removeKeysWithPrefix(tagsMemory, prefix)
        removeKeysWithPrefix(contactsMemory, prefix)
        removeKeysWithPrefix(mailPageMemory, prefix)
        removeKeysWithPrefix(mailDetailMemory, prefix)
        removeKeysWithPrefix(locallyReadMemory, prefix)

        withContext(ioDispatcher) {
            diskMutex.withLock {
                cacheDir.listFiles { file ->
                    file.isFile && file.name.startsWith(prefix)
                }?.forEach { stale ->
                    runCatching { stale.delete() }
                }
            }
        }
    }

    suspend fun clearAll() {
        mailboxesMemory.clear()
        tagsMemory.clear()
        contactsMemory.clear()
        mailPageMemory.clear()
        mailDetailMemory.clear()
        locallyReadMemory.clear()

        withContext(ioDispatcher) {
            diskMutex.withLock {
                cacheDir.listFiles()?.forEach { file ->
                    runCatching { file.deleteRecursively() }
                }
            }
        }
    }

    private suspend fun <T> readFromDisk(
        key: String,
        serializer: KSerializer<T>,
    ): T? = withContext(ioDispatcher) {
        diskMutex.withLock {
            val file = File(cacheDir, "$key.json")
            if (!file.isFile) return@withLock null
            val length = file.length()
            if (length <= 0L || length > MAX_ENTRY_BYTES) return@withLock null
            runCatching {
                val text = file.readText(Charsets.UTF_8)
                json.decodeFromString(serializer, text)
            }.getOrNull() ?: run {
                runCatching { file.delete() }
                null
            }
        }
    }

    private suspend fun <T> writeToDisk(
        key: String,
        serializer: KSerializer<T>,
        value: T,
        prunePrefix: String? = null,
        maxForPrefix: Int = 0,
    ) {
        withContext(ioDispatcher) {
            diskMutex.withLock {
                runCatching {
                    if (!cacheDir.exists()) {
                        cacheDir.mkdirs()
                    }
                    val encoded = json.encodeToString(serializer, value)
                    val targetFile = File(cacheDir, "$key.json")
                    val tempFile = File(cacheDir, "$key.json.tmp")
                    tempFile.writeText(encoded, Charsets.UTF_8)
                    if (!tempFile.renameTo(targetFile)) {
                        targetFile.writeText(encoded, Charsets.UTF_8)
                        tempFile.delete()
                    }
                    if (prunePrefix != null && maxForPrefix > 0) {
                        pruneDiskPrefixIfNeeded(prunePrefix, maxForPrefix)
                    }
                }
            }
        }
    }

    private suspend fun deleteDiskKey(key: String) {
        withContext(ioDispatcher) {
            diskMutex.withLock {
                runCatching { File(cacheDir, "$key.json").delete() }
                runCatching { File(cacheDir, "$key.json.tmp").delete() }
            }
        }
    }

    private fun pruneDiskPrefixIfNeeded(prefix: String, maxEntries: Int) {
        val files = cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith(prefix) && file.name.endsWith(".json")
        } ?: return
        if (files.size <= maxEntries) return
        files.sortedBy { it.lastModified() }
            .take(files.size - maxEntries)
            .forEach { stale -> runCatching { stale.delete() } }
    }

    private fun <V> trimMemoryMapIfNeeded(
        map: ConcurrentHashMap<String, V>,
        maxEntries: Int,
        timestampSelector: (V) -> Long,
    ) {
        if (map.size <= maxEntries * 2) return
        val oldestKeys = map.entries
            .sortedBy { timestampSelector(it.value) }
            .take(map.size - maxEntries)
            .map { it.key }
        oldestKeys.forEach { map.remove(it) }
    }

    private fun <V> removeKeysWithPrefix(map: ConcurrentHashMap<String, V>, prefix: String) {
        val iterator = map.keys.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().startsWith(prefix)) {
                iterator.remove()
            }
        }
    }

    private fun defaultMailPageKey(namespace: String, mailboxId: Long?): String {
        val safeMailbox = mailboxId?.takeIf { it > 0L } ?: 0L
        return "${namespace}_mails_mb_${safeMailbox}"
    }

    private fun detailKey(namespace: String, mailId: Long): String =
        "${namespace}_detail_${mailId}"

    companion object {
        private const val MAX_ENTRY_BYTES = 2_097_152L // 2 MB
        private const val MAX_DETAIL_ENTRIES = 40
        private const val MAX_PAGE_ENTRIES = 20

        internal fun keyNamespace(apiKey: String): String? {
            val trimmed = apiKey.trim()
            if (trimmed.isEmpty()) return null
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(trimmed.toByteArray(Charsets.UTF_8))
            return buildString(16) {
                for (i in 0 until 8) {
                    val b = digest[i].toInt() and 0xFF
                    append(HEX_CHARS[b ushr 4])
                    append(HEX_CHARS[b and 0x0F])
                }
            }
        }

        private const val HEX_CHARS = "0123456789abcdef"
    }
}
