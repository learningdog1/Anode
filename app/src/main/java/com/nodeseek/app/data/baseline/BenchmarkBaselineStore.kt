package com.nodeseek.app.data.baseline

import android.content.Context
import com.nodeseek.app.domain.baseline.BaselineSnapshotExtractor
import com.nodeseek.app.domain.baseline.BenchmarkBaselineRepository
import com.nodeseek.app.domain.baseline.BenchmarkBaselineSnapshot
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
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
private data class BenchmarkBaselineStorePayload(
    val version: Int = 1,
    val baselines: List<BenchmarkBaselineSnapshot> = emptyList(),
)

/**
 * Atomic, Mutex-protected persistent store for purchase baselines (`filesDir/benchmark_baselines.json`).
 */
class BenchmarkBaselineStore(
    private val storeFile: File,
    private val maxEntries: Int = MAX_BASELINES,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    deferInitialLoad: Boolean = false,
) : BenchmarkBaselineRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val mutex = Mutex()
    private var diskLoaded: Boolean = !deferInitialLoad
    private val initialList: List<BenchmarkBaselineSnapshot> =
        if (deferInitialLoad) emptyList() else readPayloadFromDiskSafely().baselines

    private val _baselinesFlow = MutableStateFlow(initialList)
    override val baselinesFlow: StateFlow<List<BenchmarkBaselineSnapshot>> = _baselinesFlow.asStateFlow()

    override suspend fun ensureLoaded() {
        if (diskLoaded) return
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
            }
        }
    }

    override suspend fun saveBaseline(snapshot: BenchmarkBaselineSnapshot): BenchmarkBaselineSnapshot =
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                val cleanId = snapshot.id.trim().ifBlank { UUID.randomUUID().toString() }
                val cleanLabel = snapshot.label.trim().ifBlank {
                    snapshot.hostDisplay.trim().ifBlank { "购入基线" }
                }
                val cleanTargetKey = snapshot.targetKey.trim().ifBlank {
                    BaselineSnapshotExtractor.normalizeTargetKey(
                        serverId = snapshot.serverId,
                        hostOrIp = snapshot.hostDisplay.ifBlank { snapshot.resolvedIp },
                    )
                }
                val normalized = snapshot.copy(
                    id = cleanId,
                    label = cleanLabel,
                    targetKey = cleanTargetKey,
                    hostDisplay = snapshot.hostDisplay.trim().ifBlank { cleanTargetKey },
                    notes = snapshot.notes.trim(),
                )
                val updated = (_baselinesFlow.value.filterNot { it.id == cleanId } + normalized)
                    .sortedByDescending { it.capturedAtEpochMillis }
                    .take(maxEntries.coerceAtLeast(1))
                writeToDiskLocked(updated)
                _baselinesFlow.value = updated
                normalized
            }
        }

    override suspend fun deleteBaseline(id: String) {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) return
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                val updated = _baselinesFlow.value.filterNot { it.id == cleanId }
                writeToDiskLocked(updated)
                _baselinesFlow.value = updated
            }
        }
    }

    override fun findMatchingBaselines(
        serverId: String?,
        hostOrIp: String?,
    ): List<BenchmarkBaselineSnapshot> {
        val cleanServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanHost = hostOrIp?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedHostKey = cleanHost?.let {
            BaselineSnapshotExtractor.normalizeTargetKey(serverId = null, hostOrIp = it)
        }
        val normalizedHostLower = cleanHost?.lowercase(Locale.ROOT)

        return _baselinesFlow.value.filter { baseline ->
            val matchServer = cleanServerId != null && baseline.serverId == cleanServerId
            val matchTargetKey = normalizedHostKey != null && baseline.targetKey == normalizedHostKey
            val matchHost = normalizedHostLower != null && (
                baseline.hostDisplay.trim().equals(normalizedHostLower, ignoreCase = true) ||
                    baseline.resolvedIp?.trim()?.equals(normalizedHostLower, ignoreCase = true) == true
                )
            matchServer || matchTargetKey || matchHost
        }
    }

    override suspend fun exportForBackup(): List<BenchmarkBaselineSnapshot> =
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                _baselinesFlow.value
            }
        }

    override suspend fun restoreFromBackup(
        items: List<BenchmarkBaselineSnapshot>,
        replaceExisting: Boolean,
    ) {
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureLoadedLocked()
                val cleanIncoming = items.mapNotNull { item ->
                    val cleanId = item.id.trim().ifBlank { UUID.randomUUID().toString() }
                    val cleanHost = item.hostDisplay.trim()
                    if (cleanHost.isEmpty() && item.targetKey.isBlank()) return@mapNotNull null
                    item.copy(
                        id = cleanId,
                        label = item.label.trim().ifBlank { cleanHost.ifBlank { "购入基线" } },
                    )
                }
                val merged = if (replaceExisting) {
                    cleanIncoming
                } else {
                    val map = LinkedHashMap<String, BenchmarkBaselineSnapshot>()
                    _baselinesFlow.value.forEach { map[it.id] = it }
                    cleanIncoming.forEach { map[it.id] = it }
                    map.values.toList()
                }
                    .sortedByDescending { it.capturedAtEpochMillis }
                    .take(maxEntries.coerceAtLeast(1))

                writeToDiskLocked(merged)
                _baselinesFlow.value = merged
            }
        }
    }

    private fun ensureLoadedLocked() {
        if (!diskLoaded) {
            _baselinesFlow.value = readPayloadFromDiskSafely().baselines
            diskLoaded = true
        }
    }

    private fun readPayloadFromDiskSafely(): BenchmarkBaselineStorePayload {
        if (!storeFile.isFile) return BenchmarkBaselineStorePayload()
        return runCatching {
            val raw = storeFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) return BenchmarkBaselineStorePayload()
            val parsed = json.decodeFromString<BenchmarkBaselineStorePayload>(raw)
            parsed.copy(
                baselines = parsed.baselines
                    .sortedByDescending { it.capturedAtEpochMillis }
                    .take(maxEntries.coerceAtLeast(1)),
            )
        }.getOrElse {
            backupCorruptFileSafely()
            BenchmarkBaselineStorePayload()
        }
    }

    private fun backupCorruptFileSafely() {
        runCatching {
            if (storeFile.isFile) {
                val corruptBackup = File(
                    storeFile.parentFile,
                    "${storeFile.name}.corrupt.${System.currentTimeMillis()}",
                )
                storeFile.copyTo(corruptBackup, overwrite = true)
            }
        }
    }

    private fun writeToDiskLocked(baselines: List<BenchmarkBaselineSnapshot>) {
        storeFile.parentFile?.mkdirs()
        val serialized = json.encodeToString(
            BenchmarkBaselineStorePayload(baselines = baselines),
        )
        atomicWriteText(storeFile, serialized)
    }

    private fun atomicWriteText(target: File, content: String) {
        val parent = target.parentFile
        parent?.mkdirs()
        val tempFile = File(parent, "${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                runCatching { fos.fd.sync() }
            }
            try {
                Files.move(
                    tempFile.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Throwable) {
                Files.move(
                    tempFile.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (tempFile.exists()) {
                runCatching { tempFile.delete() }
            }
        }
    }

    companion object {
        const val BASELINE_FILE_NAME: String = "benchmark_baselines.json"
        const val MAX_BASELINES: Int = 64

        private val instances = ConcurrentHashMap<String, BenchmarkBaselineStore>()

        fun forContext(context: Context): BenchmarkBaselineStore {
            val appCtx = context.applicationContext ?: context
            val file = File(appCtx.filesDir, BASELINE_FILE_NAME)
            return instances.getOrPut(file.absolutePath) {
                BenchmarkBaselineStore(storeFile = file, deferInitialLoad = true)
            }
        }
    }
}
