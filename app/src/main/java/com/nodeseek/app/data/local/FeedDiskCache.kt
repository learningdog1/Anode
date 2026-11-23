package com.nodeseek.app.data.local

import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.FeedSort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class CachedFeedSnapshot(
    val fetchedAtEpochMillis: Long,
    val result: FeedPageResult,
)

/**
 * Lightweight two-tier (memory + JSON file) Stale-While-Revalidate cache for page-1 forum feeds.
 *
 * Keyed by `(accountUid, categorySlug, sort, includeBlocked)` so switching board columns,
 * toggling blocked-post visibility, or cold-starting the app never returns stale mismatched state.
 *
 * Guarantees:
 * 1. Synchronous [get] NEVER performs disk I/O (safe on Main thread); disk snapshots are warmed
 *    asynchronously on [ioScope] and also lazily loaded via suspend [getOrLoadFromDisk].
 * 2. All disk mutations (`put`, `removeWhere`, `clear`, `warmUp`) are strictly FIFO-ordered via
 *    a single sequential worker channel + [diskMutex], preventing a delayed `clear()` from
 *    deleting a newer `put()` and eliminating torn reads.
 */
class FeedDiskCache(
    private val cacheDir: File,
    private val json: Json = TempScriptParser.json,
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxEntries: Int = 24,
) {
    private sealed interface DiskOp {
        data object WarmUp : DiskOp
        data class Put(val key: String, val snapshot: CachedFeedSnapshot) : DiskOp
        data class RemoveWhere(val predicate: (CachedFeedSnapshot) -> Boolean) : DiskOp
        data object Clear : DiskOp
        data class Barrier(val done: CompletableDeferred<Unit>) : DiskOp
    }

    private val memoryMap = ConcurrentHashMap<String, CachedFeedSnapshot>()
    private val diskMutex = Mutex()
    private val diskOps = Channel<DiskOp>(Channel.UNLIMITED)
    private val warmUpRequested = AtomicBoolean(false)

    init {
        ioScope.launch(Dispatchers.IO) {
            for (op in diskOps) {
                try {
                    when (op) {
                        DiskOp.WarmUp -> warmUpFromDiskLocked()
                        is DiskOp.Put -> persistToDisk(op.key, op.snapshot)
                        is DiskOp.RemoveWhere -> removeWhereOnDisk(op.predicate)
                        DiskOp.Clear -> clearOnDisk()
                        is DiskOp.Barrier -> op.done.complete(Unit)
                    }
                } catch (c: CancellationException) {
                    if (op is DiskOp.Barrier) op.done.cancel(c)
                    throw c
                } catch (_: Throwable) {
                    if (op is DiskOp.Barrier) op.done.complete(Unit)
                    // Best-effort cache persistence; never crash background worker.
                }
            }
        }
        warmUpAsync()
    }

    fun warmUpAsync() {
        if (warmUpRequested.compareAndSet(false, true)) {
            diskOps.trySend(DiskOp.WarmUp)
        }
    }

    /**
     * Non-blocking memory-only lookup safe to invoke from the Main thread.
     * Triggers background disk warm-up if not already scheduled.
     */
    fun get(
        accountUid: Long,
        categorySlug: String,
        sort: FeedSort,
        includeBlocked: Boolean = false,
    ): CachedFeedSnapshot? {
        warmUpAsync()
        val key = buildCacheKey(accountUid, categorySlug, sort, includeBlocked)
        return memoryMap[key]
    }

    /**
     * Suspend lookup that checks in-memory cache first and falls back to [diskMutex]-protected
     * disk I/O on [Dispatchers.IO].
     */
    suspend fun getOrLoadFromDisk(
        accountUid: Long,
        categorySlug: String,
        sort: FeedSort,
        includeBlocked: Boolean = false,
    ): CachedFeedSnapshot? {
        val key = buildCacheKey(accountUid, categorySlug, sort, includeBlocked)
        memoryMap[key]?.let { return it }

        return withContext(Dispatchers.IO) {
            diskMutex.withLock {
                memoryMap[key]?.let { return@withLock it }
                val file = File(cacheDir, "$key.json")
                val decoded = decodeFileOrDelete(file) ?: return@withLock null
                memoryMap[key] = decoded
                decoded
            }
        }
    }

    fun put(
        accountUid: Long,
        categorySlug: String,
        sort: FeedSort,
        snapshot: CachedFeedSnapshot,
        includeBlocked: Boolean = false,
    ) {
        val key = buildCacheKey(accountUid, categorySlug, sort, includeBlocked)
        memoryMap[key] = snapshot
        trimMemoryIfNeeded()
        diskOps.trySend(DiskOp.Put(key = key, snapshot = snapshot))
    }

    /**
     * Synchronous write helper exposed for tests and deterministic shutdown checkpoints.
     */
    internal suspend fun persistNowForTest(
        accountUid: Long,
        categorySlug: String,
        sort: FeedSort,
        snapshot: CachedFeedSnapshot,
        includeBlocked: Boolean = false,
    ) {
        val key = buildCacheKey(accountUid, categorySlug, sort, includeBlocked)
        memoryMap[key] = snapshot
        persistToDisk(key = key, snapshot = snapshot)
    }

    /**
     * Synchronous warm-up helper for deterministic cold-start tests.
     */
    internal suspend fun warmUpNowForTest() {
        warmUpFromDiskLocked()
    }

    /**
     * Waits until all previously enqueued disk operations have completed in FIFO order.
     */
    internal suspend fun flushForTest() {
        val done = CompletableDeferred<Unit>()
        diskOps.trySend(DiskOp.Barrier(done))
        done.await()
    }

    fun removeWhere(predicate: (CachedFeedSnapshot) -> Boolean) {
        val iterator = memoryMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (predicate(entry.value)) {
                iterator.remove()
            }
        }
        diskOps.trySend(DiskOp.RemoveWhere(predicate))
    }

    fun clear() {
        memoryMap.clear()
        diskOps.trySend(DiskOp.Clear)
    }

    private suspend fun warmUpFromDiskLocked() {
        diskMutex.withLock {
            val files = cacheDir.listFiles { file ->
                file.isFile && file.name.endsWith(".json")
            } ?: return@withLock
            files.sortedByDescending { it.lastModified() }
                .take(maxEntries)
                .forEach { file ->
                    val key = file.name.removeSuffix(".json")
                    if (!memoryMap.containsKey(key)) {
                        decodeFileOrDelete(file)?.let { decoded ->
                            memoryMap.putIfAbsent(key, decoded)
                        }
                    }
                }
        }
    }

    private suspend fun removeWhereOnDisk(predicate: (CachedFeedSnapshot) -> Boolean) {
        diskMutex.withLock {
            val files = cacheDir.listFiles { file ->
                file.isFile && file.name.endsWith(".json")
            } ?: return@withLock
            for (file in files) {
                val decoded = decodeFileOrDelete(file)
                if (decoded == null || predicate(decoded)) {
                    runCatching { file.delete() }
                    val key = file.name.removeSuffix(".json")
                    memoryMap.computeIfPresent(key) { _, current ->
                        if (predicate(current)) null else current
                    }
                }
            }
        }
    }

    private suspend fun clearOnDisk() {
        diskMutex.withLock {
            cacheDir.listFiles()?.forEach { file ->
                runCatching { file.delete() }
            }
        }
    }

    private suspend fun persistToDisk(key: String, snapshot: CachedFeedSnapshot) {
        diskMutex.withLock {
            runCatching {
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }
                val encodedBytes = json.encodeToString(CachedFeedSnapshot.serializer(), snapshot)
                    .toByteArray(Charsets.UTF_8)
                val targetFile = File(cacheDir, "$key.json")
                val tempFile = File(cacheDir, "$key.json.tmp")
                FileOutputStream(tempFile).use { fos ->
                    fos.write(encodedBytes)
                    fos.fd.sync()
                }
                if (!tempFile.renameTo(targetFile)) {
                    FileOutputStream(targetFile).use { fos ->
                        fos.write(encodedBytes)
                        fos.fd.sync()
                    }
                    tempFile.delete()
                }
                pruneDiskFilesIfNeeded()
            }
        }
    }

    private fun decodeFileOrDelete(file: File): CachedFeedSnapshot? {
        if (!file.isFile) return null
        val length = file.length()
        if (length <= 0L || length > MAX_ENTRY_BYTES) {
            runCatching { file.delete() }
            return null
        }
        return runCatching {
            val text = file.readText(Charsets.UTF_8)
            json.decodeFromString(CachedFeedSnapshot.serializer(), text)
        }.getOrNull() ?: run {
            runCatching { file.delete() }
            null
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
            includeBlocked: Boolean = false,
        ): String {
            val rawSlug = categorySlug.trim().ifEmpty { "__all__" }
            val sanitizedSlug = rawSlug.replace(Regex("""[^a-zA-Z0-9_-]"""), "_")
            val blockedSuffix = if (includeBlocked) "_blk" else ""
            return "u${accountUid}_${sanitizedSlug}_${sort.name}$blockedSuffix"
        }
    }
}
