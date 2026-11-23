package com.nodeseek.app.data.trust

import com.nodeseek.app.domain.trust.OfficialTradeLookup
import com.nodeseek.app.domain.trust.OfficialTradeUserStats
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
import java.util.Locale

@Serializable
internal data class PersistedOfficialTradeCacheEntry(
    val key: String,
    val matches: List<OfficialTradeUserStats> = emptyList(),
    val isNotFound: Boolean = false,
    val cachedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val lastAccessedAtEpochMillis: Long = cachedAtEpochMillis,
)

@Serializable
private data class PersistedOfficialTradeCachePayload(
    val version: Int = OfficialTradeCacheStore.CURRENT_SCHEMA_VERSION,
    val entries: List<PersistedOfficialTradeCacheEntry> = emptyList(),
)

/**
 * Two-tier (in-memory LRU + atomic JSON disk persistence) cache for NodeSeek Official Trade
 * Platform user lookups:
 * - Positive (`Found`) entries: 6 hours TTL ([DEFAULT_POSITIVE_TTL_MILLIS]).
 * - Negative (`NotFound`) entries: 10 minutes TTL ([DEFAULT_NOT_FOUND_TTL_MILLIS]).
 */
internal class OfficialTradeCacheStore(
    private val cacheFile: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val maxMemoryEntries: Int = DEFAULT_MAX_MEMORY_ENTRIES,
    private val maxDiskEntries: Int = DEFAULT_MAX_DISK_ENTRIES,
    private val positiveTtlMillis: Long = DEFAULT_POSITIVE_TTL_MILLIS,
    private val notFoundTtlMillis: Long = DEFAULT_NOT_FOUND_TTL_MILLIS,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val mutex = Mutex()
    private var loadedFromDisk = false

    private val memoryLru = LinkedHashMap<String, PersistedOfficialTradeCacheEntry>(64, 0.75f, true)
    private val diskEntries = LinkedHashMap<String, PersistedOfficialTradeCacheEntry>(64, 0.75f, false)

    private val positiveSnapshotState =
        MutableStateFlow<Map<String, PersistedOfficialTradeCacheEntry>>(emptyMap())

    suspend fun getFresh(
        username: String,
        nowMillis: Long = clockMillis(),
    ): OfficialTradeLookup? {
        val key = normalizeUsernameKey(username)
        if (key.isEmpty()) return null
        return withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked(nowMillis)
                val entry = lookupAndPromoteLocked(key, nowMillis) ?: return@withLock null
                if (nowMillis < entry.expiresAtEpochMillis) {
                    if (entry.isNotFound) {
                        OfficialTradeLookup.NotFound
                    } else {
                        OfficialTradeLookup.Found(
                            matches = entry.matches,
                            fetchedAtMillis = entry.cachedAtEpochMillis,
                            fromCache = true,
                        )
                    }
                } else {
                    removeKeyLocked(key, nowMillis)
                    null
                }
            }
        }
    }

    suspend fun getFreshPositiveOnly(
        username: String,
        nowMillis: Long = clockMillis(),
    ): OfficialTradeLookup.Found? {
        return getFresh(username = username, nowMillis = nowMillis) as? OfficialTradeLookup.Found
    }

    fun observeFreshPositiveOnly(username: String): Flow<OfficialTradeLookup.Found?> = flow {
        val key = normalizeUsernameKey(username)
        if (key.isEmpty()) {
            emit(null)
            return@flow
        }
        ensureLoaded()
        emitAll(
            positiveSnapshotState
                .map { snapshot ->
                    val entry = snapshot[key] ?: return@map null
                    val now = clockMillis()
                    if (!entry.isNotFound && now < entry.expiresAtEpochMillis) {
                        OfficialTradeLookup.Found(
                            matches = entry.matches,
                            fetchedAtMillis = entry.cachedAtEpochMillis,
                            fromCache = true,
                        )
                    } else {
                        null
                    }
                }
                .distinctUntilChanged(),
        )
    }

    suspend fun putFound(
        username: String,
        matches: List<OfficialTradeUserStats>,
        nowMillis: Long = clockMillis(),
    ) {
        val key = normalizeUsernameKey(username)
        if (key.isEmpty() || positiveTtlMillis <= 0L) return
        putEntry(
            entry = PersistedOfficialTradeCacheEntry(
                key = key,
                matches = matches,
                isNotFound = false,
                cachedAtEpochMillis = nowMillis,
                expiresAtEpochMillis = nowMillis + positiveTtlMillis,
                lastAccessedAtEpochMillis = nowMillis,
            ),
            nowMillis = nowMillis,
        )
    }

    suspend fun putNotFound(
        username: String,
        nowMillis: Long = clockMillis(),
    ) {
        val key = normalizeUsernameKey(username)
        if (key.isEmpty() || notFoundTtlMillis <= 0L) return
        putEntry(
            entry = PersistedOfficialTradeCacheEntry(
                key = key,
                matches = emptyList(),
                isNotFound = true,
                cachedAtEpochMillis = nowMillis,
                expiresAtEpochMillis = nowMillis + notFoundTtlMillis,
                lastAccessedAtEpochMillis = nowMillis,
            ),
            nowMillis = nowMillis,
        )
    }

    suspend fun clear() {
        withContext(ioDispatcher) {
            mutex.withLock {
                loadedFromDisk = true
                memoryLru.clear()
                diskEntries.clear()
                positiveSnapshotState.value = emptyMap()
                runCatching {
                    val tmpFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
                    if (tmpFile.exists()) tmpFile.delete()
                    if (cacheFile.exists()) cacheFile.delete()
                }
            }
        }
    }

    private suspend fun putEntry(
        entry: PersistedOfficialTradeCacheEntry,
        nowMillis: Long,
    ) {
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked(nowMillis)
                memoryLru[entry.key] = entry
                diskEntries[entry.key] = entry
                evictMemoryIfNeededLocked(nowMillis)
                evictDiskIfNeededLocked(nowMillis)
                rebuildPositiveSnapshotLocked(nowMillis)
                writeToDiskLocked()
            }
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
    ): PersistedOfficialTradeCacheEntry? {
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

    private fun removeKeyLocked(
        key: String,
        nowMillis: Long,
    ) {
        val removedDisk = diskEntries.remove(key)
        memoryLru.remove(key)
        if (removedDisk != null) {
            rebuildPositiveSnapshotLocked(nowMillis)
            writeToDiskLocked()
        }
    }

    private fun evictMemoryIfNeededLocked(nowMillis: Long) {
        if (memoryLru.size <= maxMemoryEntries) return
        val expiredKeys = memoryLru.entries
            .filter { nowMillis >= it.value.expiresAtEpochMillis }
            .map { it.key }
        expiredKeys.forEach { memoryLru.remove(it) }

        while (memoryLru.size > maxMemoryEntries) {
            val lruKey = memoryLru.entries.firstOrNull()?.key ?: break
            memoryLru.remove(lruKey)
        }
    }

    private fun evictDiskIfNeededLocked(nowMillis: Long) {
        val expiredKeys = diskEntries.entries
            .filter { nowMillis >= it.value.expiresAtEpochMillis }
            .map { it.key }
        expiredKeys.forEach { key ->
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

    private fun rebuildPositiveSnapshotLocked(nowMillis: Long) {
        val next = LinkedHashMap<String, PersistedOfficialTradeCacheEntry>()
        for ((key, entry) in diskEntries) {
            if (!entry.isNotFound && nowMillis < entry.expiresAtEpochMillis) {
                next[key] = entry
            }
        }
        positiveSnapshotState.value = next.toMap()
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
            if (raw.isBlank()) null else json.decodeFromString<PersistedOfficialTradeCachePayload>(raw)
        }.getOrNull() ?: run {
            runCatching { cacheFile.delete() }
            return
        }

        if (payload.version < CURRENT_SCHEMA_VERSION) {
            runCatching { cacheFile.delete() }
            return
        }

        val validEntries = payload.entries
            .filter { entry -> entry.key.isNotBlank() && nowMillis < entry.expiresAtEpochMillis }
            .sortedBy { it.lastAccessedAtEpochMillis }

        for (entry in validEntries) {
            diskEntries[entry.key] = entry
            memoryLru[entry.key] = entry
        }
        evictMemoryIfNeededLocked(nowMillis)
        evictDiskIfNeededLocked(nowMillis)
        rebuildPositiveSnapshotLocked(nowMillis)
    }

    private fun writeToDiskLocked() {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val payload = PersistedOfficialTradeCachePayload(
                version = CURRENT_SCHEMA_VERSION,
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
        const val CACHE_FILE_NAME: String = "official_trade_cache_v1.json"
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val DEFAULT_MAX_MEMORY_ENTRIES: Int = 128
        const val DEFAULT_MAX_DISK_ENTRIES: Int = 256
        const val DEFAULT_POSITIVE_TTL_MILLIS: Long = 6L * 60L * 60L * 1_000L
        const val DEFAULT_NOT_FOUND_TTL_MILLIS: Long = 10L * 60L * 1_000L
        private const val MAX_CACHE_FILE_BYTES: Long = 1_024_000L

        internal fun normalizeCleanUsername(raw: String): String =
            raw.trim().removePrefix("@").trim()

        internal fun normalizeUsernameKey(raw: String): String =
            normalizeCleanUsername(raw).lowercase(Locale.ROOT)
    }
}
