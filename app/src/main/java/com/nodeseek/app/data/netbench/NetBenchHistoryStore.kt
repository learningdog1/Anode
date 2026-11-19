package com.nodeseek.app.data.netbench

import android.content.Context
import com.nodeseek.app.domain.netbench.NetBenchHistoryEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
private data class NetBenchHistoryPayload(
    val version: Int = 1,
    val entries: List<NetBenchHistoryEntry> = emptyList(),
)

/**
 * Self-contained recent target history store backed by `filesDir/netbench_history.json`.
 */
class NetBenchHistoryStore(
    private val historyFile: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<NetBenchHistoryEntry>>(readFromDiskSafely())
    val entries: StateFlow<List<NetBenchHistoryEntry>> = _entries.asStateFlow()

    suspend fun reload(): List<NetBenchHistoryEntry> = withContext(ioDispatcher) {
        mutex.withLock {
            val loaded = readFromDiskSafely()
            _entries.value = loaded
            loaded
        }
    }

    suspend fun record(entry: NetBenchHistoryEntry): List<NetBenchHistoryEntry> = withContext(ioDispatcher) {
        val cleanHost = entry.host.trim()
        if (cleanHost.isEmpty()) return@withContext _entries.value

        mutex.withLock {
            val normalized = entry.copy(
                host = cleanHost,
                port = entry.port.coerceIn(1, 65535),
                iperfPort = entry.iperfPort.coerceIn(1, 65535),
            )
            val current = _entries.value.filterNot { existing ->
                existing.host.equals(normalized.host, ignoreCase = true) && existing.port == normalized.port
            }
            val updated = (listOf(normalized) + current)
                .sortedByDescending { it.testedAtEpochMillis }
                .take(maxEntries.coerceAtLeast(1))
            _entries.value = updated
            writeToDiskSafely(updated)
            updated
        }
    }

    suspend fun remove(host: String, port: Int): List<NetBenchHistoryEntry> = withContext(ioDispatcher) {
        mutex.withLock {
            val updated = _entries.value.filterNot {
                it.host.equals(host.trim(), ignoreCase = true) && it.port == port
            }
            _entries.value = updated
            writeToDiskSafely(updated)
            updated
        }
    }

    suspend fun clear(): Unit = withContext(ioDispatcher) {
        mutex.withLock {
            _entries.value = emptyList()
            runCatching {
                if (historyFile.exists()) {
                    historyFile.delete()
                }
            }
        }
    }

    private fun readFromDiskSafely(): List<NetBenchHistoryEntry> {
        if (!historyFile.isFile) return emptyList()
        return runCatching {
            val raw = historyFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) return emptyList()
            val payload = json.decodeFromString<NetBenchHistoryPayload>(raw)
            payload.entries
                .filter { it.host.isNotBlank() }
                .sortedByDescending { it.testedAtEpochMillis }
                .take(maxEntries.coerceAtLeast(1))
        }.getOrDefault(emptyList())
    }

    private fun writeToDiskSafely(list: List<NetBenchHistoryEntry>) {
        runCatching {
            historyFile.parentFile?.mkdirs()
            val tempFile = File(historyFile.parentFile, "${historyFile.name}.tmp")
            val serialized = json.encodeToString(NetBenchHistoryPayload(entries = list))
            tempFile.writeText(serialized, Charsets.UTF_8)
            if (!tempFile.renameTo(historyFile)) {
                historyFile.writeText(serialized, Charsets.UTF_8)
                tempFile.delete()
            }
        }
    }

    companion object {
        const val HISTORY_FILE_NAME = "netbench_history.json"
        const val DEFAULT_MAX_ENTRIES = 16

        private val instances = ConcurrentHashMap<String, NetBenchHistoryStore>()

        fun forContext(context: Context): NetBenchHistoryStore {
            val appCtx = context.applicationContext ?: context
            val file = File(appCtx.filesDir, HISTORY_FILE_NAME)
            return instances.getOrPut(file.absolutePath) {
                NetBenchHistoryStore(historyFile = file)
            }
        }
    }
}
