package com.nodeseek.app.data.trust

import com.nodeseek.app.domain.trust.SellerTrustReport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
internal data class PersistedTrustCacheEntry(
    val key: String,
    val report: SellerTrustReport,
    val cachedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val lastAccessedAtEpochMillis: Long = cachedAtEpochMillis,
    val isNegative: Boolean = false,
)

@Serializable
private data class PersistedTrustCachePayload(
    val version: Int = 1,
    val entries: List<PersistedTrustCacheEntry> = emptyList(),
)

/**
 * Two-tier Seller Trust report cache:
 * 1. Bounded access-order in-memory LRU (`LinkedHashMap(..., accessOrder = true)`).
 * 2. Atomic JSON disk persistence (`filesDir/seller_trust_cache_v1.json`) surviving app restarts.
 *
 * Supports distinct TTLs for full positive results (12–24h, default 18h), partial results (15m),
 * and failed/empty negative results (short TTL, default 3m), plus stale-positive retention so
 * rate-limit cooldowns can serve the last known good snapshot instead of an empty screen.
 */
internal class SellerTrustCacheStore(
    private val cacheFile: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val maxMemoryEntries: Int = DEFAULT_MAX_MEMORY_ENTRIES,
    private val maxDiskEntries: Int = DEFAULT_MAX_DISK_ENTRIES,
    private val staleRetentionMillis: Long = DEFAULT_STALE_RETENTION_MILLIS,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val mutex = Mutex()
    private var loadedFromDisk = false

    // Access-ordered LinkedHashMap: get() promotes accessed key to MRU tail; first entry is LRU head.
    private val memoryLru = LinkedHashMap<String, PersistedTrustCacheEntry>(64, 0.75f, true)

    // Disk index holding entries loaded from or written to disk (allows disk hit even after memory LRU eviction).
    private val diskEntries = LinkedHashMap<String, PersistedTrustCacheEntry>(64, 0.75f, false)

    // Reactive positive/partial cache snapshot indexed by seller UID for zero-network QuickBadge observation.
    private val positiveByUidState = MutableStateFlow<Map<Long, PersistedTrustCacheEntry>>(emptyMap())

    suspend fun getFresh(
        key: String,
        nowMillis: Long = clockMillis(),
    ): PersistedTrustCacheEntry? {
        if (key.isBlank()) return null
        return withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked(nowMillis)
                val entry = lookupAndPromoteLocked(key, nowMillis) ?: return@withLock null
                if (nowMillis < entry.expiresAtEpochMillis) {
                    entry
                } else {
                    if (entry.isNegative || nowMillis - entry.cachedAtEpochMillis >= staleRetentionMillis) {
                        removeKeyLocked(key)
                    }
                    null
                }
            }
        }
    }

    /**
     * Returns a positive/partial cached report even if its normal TTL has expired (within [staleRetentionMillis]),
     * used as graceful fallback while the remote site is in a 429/503 rate-limit cooldown.
     */
    suspend fun getStalePositiveFallback(
        keys: List<String>,
        nowMillis: Long = clockMillis(),
    ): PersistedTrustCacheEntry? {
        val validKeys = keys.filter { it.isNotBlank() }.distinct()
        if (validKeys.isEmpty()) return null
        return withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked(nowMillis)
                for (key in validKeys) {
                    val entry = lookupAndPromoteLocked(key, nowMillis) ?: continue
                    val withinStaleWindow = nowMillis - entry.cachedAtEpochMillis < staleRetentionMillis
                    if (!entry.isNegative && entry.report.anyRemoteSucceeded && withinStaleWindow) {
                        return@withLock entry
                    }
                }
                null
            }
        }
    }

    suspend fun put(
        keys: Collection<String>,
        report: SellerTrustReport,
        ttlMillis: Long,
        isNegative: Boolean,
        nowMillis: Long = clockMillis(),
    ) {
        val cleanKeys = keys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleanKeys.isEmpty() || ttlMillis <= 0L) return
        val expiresAt = nowMillis + ttlMillis
        val stampedReport = report.copy(
            cachedAtEpochMillis = nowMillis,
            cacheExpiresAtEpochMillis = expiresAt,
            isNegativeCache = isNegative,
        )

        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked(nowMillis)
                for (key in cleanKeys) {
                    val entry = PersistedTrustCacheEntry(
                        key = key,
                        report = stampedReport,
                        cachedAtEpochMillis = nowMillis,
                        expiresAtEpochMillis = expiresAt,
                        lastAccessedAtEpochMillis = nowMillis,
                        isNegative = isNegative,
                    )
                    memoryLru[key] = entry
                    diskEntries[key] = entry
                }
                evictMemoryIfNeededLocked(nowMillis)
                evictDiskIfNeededLocked(nowMillis)
                rebuildUidSnapshotLocked(nowMillis)
                writeToDiskLocked()
            }
        }
    }

    suspend fun updateReportsForUid(
        uid: Long,
        transform: (SellerTrustReport) -> SellerTrustReport,
    ) {
        if (uid <= 0L) return
        val now = clockMillis()
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked(now)
                var changed = false
                for (key in memoryLru.keys.toList()) {
                    val existing = memoryLru[key] ?: continue
                    if (existing.report.uid == uid) {
                        memoryLru[key] = existing.copy(report = transform(existing.report))
                        changed = true
                    }
                }
                for (key in diskEntries.keys.toList()) {
                    val existing = diskEntries[key] ?: continue
                    if (existing.report.uid == uid) {
                        diskEntries[key] = existing.copy(report = transform(existing.report))
                        changed = true
                    }
                }
                if (changed) {
                    rebuildUidSnapshotLocked(now)
                    writeToDiskLocked()
                }
            }
        }
    }

    fun observePositiveReportByUid(uid: Long): Flow<SellerTrustReport?> = flow {
        if (uid <= 0L) {
            emit(null)
            return@flow
        }
        ensureLoaded()
        emitAll(
            positiveByUidState
                .map { byUid ->
                    val entry = byUid[uid] ?: return@map null
                    val now = clockMillis()
                    if (!entry.isNegative && entry.report.anyRemoteSucceeded && now < entry.expiresAtEpochMillis) {
                        entry.report.copy(isFromCache = true)
                    } else {
                        null
                    }
                }
                .distinctUntilChanged(),
        )
    }

    internal suspend fun isInMemoryLruForTest(key: String): Boolean =
        withContext(ioDispatcher) {
            mutex.withLock {
                memoryLru.containsKey(key)
            }
        }

    internal suspend fun memorySizeForTest(): Int =
        withContext(ioDispatcher) {
            mutex.withLock {
                memoryLru.size
            }
        }

    private suspend fun ensureLoaded() {
        if (loadedFromDisk) return
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked(clockMillis())
            }
        }
    }

    private fun lookupAndPromoteLocked(
        key: String,
        nowMillis: Long,
    ): PersistedTrustCacheEntry? {
        val memHit = memoryLru[key]
        if (memHit != null) {
            val touched = memHit.copy(lastAccessedAtEpochMillis = nowMillis)
            memoryLru[key] = touched
            diskEntries[key] = touched
            return touched
        }
        val diskHit = diskEntries[key] ?: return null
        val promoted = diskHit.copy(lastAccessedAtEpochMillis = nowMillis)
        diskEntries[key] = promoted
        memoryLru[key] = promoted
        evictMemoryIfNeededLocked(nowMillis)
        return promoted
    }

    private fun removeKeyLocked(key: String) {
        val removedDisk = diskEntries.remove(key)
        memoryLru.remove(key)
        if (removedDisk != null) {
            rebuildUidSnapshotLocked(clockMillis())
            writeToDiskLocked()
        }
    }

    private fun evictMemoryIfNeededLocked(nowMillis: Long) {
        if (memoryLru.size <= maxMemoryEntries) return
        // First drop expired negative entries from memory
        val expiredNegativeKeys = memoryLru.entries
            .filter { it.value.isNegative && nowMillis >= it.value.expiresAtEpochMillis }
            .map { it.key }
        expiredNegativeKeys.forEach { memoryLru.remove(it) }

        while (memoryLru.size > maxMemoryEntries) {
            val lruKey = memoryLru.entries.firstOrNull()?.key ?: break
            memoryLru.remove(lruKey)
        }
    }

    private fun evictDiskIfNeededLocked(nowMillis: Long) {
        // Prune expired negative entries and stale-retention-exceeded entries first
        val staleKeys = diskEntries.entries
            .filter { (_, entry) ->
                (entry.isNegative && nowMillis >= entry.expiresAtEpochMillis) ||
                    (nowMillis - entry.cachedAtEpochMillis >= staleRetentionMillis)
            }
            .map { it.key }
        staleKeys.forEach { key ->
            diskEntries.remove(key)
            memoryLru.remove(key)
        }

        if (diskEntries.size <= maxDiskEntries) return
        val overflow = diskEntries.size - maxDiskEntries
        diskEntries.entries
            .sortedBy { it.value.lastAccessedAtEpochMillis }
            .take(overflow)
            .map { it.key }
            .forEach { diskEntries.remove(it) }
    }

    private fun rebuildUidSnapshotLocked(nowMillis: Long) {
        val next = LinkedHashMap<Long, PersistedTrustCacheEntry>()
        for (entry in diskEntries.values) {
            val uid = entry.report.uid
            if (uid <= 0L || entry.isNegative || !entry.report.anyRemoteSucceeded) continue
            if (nowMillis >= entry.expiresAtEpochMillis) continue
            val existing = next[uid]
            if (existing == null || entry.cachedAtEpochMillis >= existing.cachedAtEpochMillis) {
                next[uid] = entry
            }
        }
        positiveByUidState.value = next.toMap()
    }

    private fun ensureLoadedLocked(nowMillis: Long) {
        if (loadedFromDisk) return
        loadedFromDisk = true
        if (!cacheFile.isFile) return
        val fileLen = cacheFile.length()
        if (fileLen <= 0L || fileLen > MAX_CACHE_FILE_BYTES) {
            if (fileLen > MAX_CACHE_FILE_BYTES) {
                runCatching { cacheFile.delete() }
            }
            return
        }

        val payload = runCatching {
            val raw = cacheFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) null else json.decodeFromString<PersistedTrustCachePayload>(raw)
        }.getOrNull() ?: run {
            runCatching { cacheFile.delete() }
            return
        }

        // Insert oldest-accessed first so MRU ordering in LinkedHashMap is preserved on startup.
        val validEntries = payload.entries
            .filter { entry ->
                entry.key.isNotBlank() &&
                    !(entry.isNegative && nowMillis >= entry.expiresAtEpochMillis) &&
                    (nowMillis - entry.cachedAtEpochMillis < staleRetentionMillis)
            }
            .sortedBy { it.lastAccessedAtEpochMillis }

        for (entry in validEntries) {
            diskEntries[entry.key] = entry
            memoryLru[entry.key] = entry
        }
        evictMemoryIfNeededLocked(nowMillis)
        evictDiskIfNeededLocked(nowMillis)
        rebuildUidSnapshotLocked(nowMillis)
    }

    private fun writeToDiskLocked() {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val payload = PersistedTrustCachePayload(
                version = 1,
                entries = diskEntries.values.sortedByDescending { it.lastAccessedAtEpochMillis },
            )
            val encoded = json.encodeToString(payload)
            val tmpFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            tmpFile.writeText(encoded, Charsets.UTF_8)
            if (!tmpFile.renameTo(cacheFile)) {
                cacheFile.writeText(encoded, Charsets.UTF_8)
                tmpFile.delete()
            }
        }
    }

    companion object {
        const val CACHE_FILE_NAME: String = "seller_trust_cache_v1.json"
        const val DEFAULT_MAX_MEMORY_ENTRIES: Int = 128
        const val DEFAULT_MAX_DISK_ENTRIES: Int = 256
        const val DEFAULT_STALE_RETENTION_MILLIS: Long = 7L * 24L * 60L * 60L * 1000L
        private const val MAX_CACHE_FILE_BYTES: Long = 2_048_000L
    }
}
