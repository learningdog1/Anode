package com.nodeseek.app.data.local

import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.FeedSort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class CachedFeedSnapshot(
    val fetchedAtEpochMillis: Long,
    val result: FeedPageResult,
)

/**
 * Lightweight two-tier (memory + JSON file) Stale-While-Revalidate cache for page-1 forum feeds.
 *
 * Keyed by `(accountUid, categorySlug, sort)` so switching board columns or cold-starting the app
 * immediately renders the last seen feed before silently revalidating in the background.
 */
class FeedDiskCache(
    private val cacheDir: File,
    private val json: Json = TempScriptParser.json,
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxEntries: Int = 24,
) {
    private val memoryMap = ConcurrentHashMap<String, CachedFeedSnapshot>()
    private val diskMutex = Mutex()

    fun get(
        accountUid: Long,
        categorySlug: String,
        sort: FeedSort,
    ): CachedFeedSnapshot? {
        val key = buildCacheKey(accountUid, categorySlug, sort)
        memoryMap[key]?.let { return it }

        val file = File(cacheDir, "$key.json")
        if (!file.isFile) return null
        val length = file.length()
        if (length <= 0L || length > MAX_ENTRY_BYTES) return null

        val decoded = runCatching {
            val text = file.readText(Charsets.UTF_8)
            json.decodeFromString(CachedFeedSnapshot.serializer(), text)
        }.getOrNull() ?: run {
            runCatching { file.delete() }
            return null
        }

        memoryMap[key] = decoded
        return decoded
    }

    fun put(
        accountUid: Long,
        categorySlug: String,
        sort: FeedSort,
        snapshot: CachedFeedSnapshot,
    ) {
        val key = buildCacheKey(accountUid, categorySlug, sort)
        memoryMap[key] = snapshot
        trimMemoryIfNeeded()

        ioScope.launch {
            persistToDisk(key = key, snapshot = snapshot)
        }
    }

    /**
     * Synchronous write helper exposed for tests and deterministic shutdown checkpoints.
     */
    internal suspend fun persistNowForTest(
        accountUid: Long,
        categorySlug: String,
        sort: FeedSort,
        snapshot: CachedFeedSnapshot,
    ) {
        val key = buildCacheKey(accountUid, categorySlug, sort)
        memoryMap[key] = snapshot
        persistToDisk(key = key, snapshot = snapshot)
    }

    fun removeWhere(predicate: (CachedFeedSnapshot) -> Boolean) {
        val iterator = memoryMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (predicate(entry.value)) {
                iterator.remove()
            }
        }
        ioScope.launch {
            diskMutex.withLock {
                val files = cacheDir.listFiles { file ->
                    file.isFile && file.name.endsWith(".json")
                } ?: return@withLock
                for (file in files) {
                    val decoded = runCatching {
                        json.decodeFromString(CachedFeedSnapshot.serializer(), file.readText(Charsets.UTF_8))
                    }.getOrNull()
                    if (decoded == null || predicate(decoded)) {
                        runCatching { file.delete() }
                        memoryMap.remove(file.name.removeSuffix(".json"))
                    }
                }
            }
        }
    }

    fun clear() {
        memoryMap.clear()
        ioScope.launch {
            diskMutex.withLock {
                cacheDir.listFiles()?.forEach { file ->
                    runCatching { file.delete() }
                }
            }
        }
    }

    private suspend fun persistToDisk(key: String, snapshot: CachedFeedSnapshot) {
        diskMutex.withLock {
            runCatching {
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }
                val encoded = json.encodeToString(CachedFeedSnapshot.serializer(), snapshot)
                val targetFile = File(cacheDir, "$key.json")
                val tempFile = File(cacheDir, "$key.json.tmp")
                tempFile.writeText(encoded, Charsets.UTF_8)
                if (!tempFile.renameTo(targetFile)) {
                    targetFile.writeText(encoded, Charsets.UTF_8)
                    tempFile.delete()
                }
                pruneDiskFilesIfNeeded()
            }
        }
    }

    private fun pruneDiskFilesIfNeeded() {
        val files = cacheDir.listFiles { file ->
            file.isFile && file.name.endsWith(".json")
        } ?: return
        if (files.size <= maxEntries) return
        files.sortedBy { it.lastModified() }
            .take(files.size - maxEntries)
            .forEach { stale -> runCatching { stale.delete() } }
    }

    private fun trimMemoryIfNeeded() {
        if (memoryMap.size <= maxEntries * 2) return
        val oldestKeys = memoryMap.entries
            .sortedBy { it.value.fetchedAtEpochMillis }
            .take(memoryMap.size - maxEntries)
            .map { it.key }
        oldestKeys.forEach { memoryMap.remove(it) }
    }

    companion object {
        private const val MAX_ENTRY_BYTES = 512_000L

        internal fun buildCacheKey(
            accountUid: Long,
            categorySlug: String,
            sort: FeedSort,
        ): String {
            val rawSlug = categorySlug.trim().ifEmpty { "__all__" }
            val sanitizedSlug = rawSlug.replace(Regex("""[^a-zA-Z0-9_-]"""), "_")
            return "u${accountUid}_${sanitizedSlug}_${sort.name}"
        }
    }
}
