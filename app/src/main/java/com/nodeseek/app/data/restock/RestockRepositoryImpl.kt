package com.nodeseek.app.data.restock

import android.content.Context
import com.nodeseek.app.domain.backup.RestockBackupDto
import com.nodeseek.app.domain.restock.MerchantPanelType
import com.nodeseek.app.domain.restock.RestockEventRecord
import com.nodeseek.app.domain.restock.RestockMonitorSettings
import com.nodeseek.app.domain.restock.RestockRepository
import com.nodeseek.app.domain.restock.RestockStockStatus
import com.nodeseek.app.domain.restock.RestockWatchItem
import com.nodeseek.app.domain.restock.StockAnalysisResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap

internal object RestockLoadStateRegistry {
    private val loadedFlows = Collections.synchronizedMap(WeakHashMap<StateFlow<*>, StateFlow<Boolean>>())

    fun register(watchItemsFlow: StateFlow<*>, loadedFlow: StateFlow<Boolean>) {
        loadedFlows[watchItemsFlow] = loadedFlow
    }

    fun loadedFlowFor(watchItemsFlow: Any): StateFlow<Boolean>? =
        loadedFlows[watchItemsFlow]
}

@Serializable
internal data class RestockStoreSnapshot(
    val version: Int = 1,
    val watchItems: List<RestockWatchItem> = emptyList(),
    val events: List<RestockEventRecord> = emptyList(),
    val settings: RestockMonitorSettings = RestockMonitorSettings(),
)

/**
 * Thread-safe persistent JSON store (`context.filesDir/restock_monitor_store.json`)
 * for VPS restock watch items, restock transition history, and monitor settings.
 *
 * Uses atomic temp-file writes guarded by a [Mutex] so foreground UI edits, fast polling,
 * and WorkManager background checks never corrupt storage or require Room migrations.
 * Disk hydration runs asynchronously on [Dispatchers.IO] (and is awaited before any suspend
 * read/write) so cold-start construction never blocks the main thread.
 */
class RestockRepositoryImpl(
    private val storeFile: File,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : RestockRepository {

    constructor(
        context: Context,
        clockMillis: () -> Long = System::currentTimeMillis,
    ) : this(
        storeFile = File((context.applicationContext ?: context).filesDir, STORE_FILE_NAME),
        clockMillis = clockMillis,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val mutex = Mutex()
    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var diskLoaded = false
    private val _isLoadedFlow = MutableStateFlow(false)

    private val _watchItemsFlow = MutableStateFlow<List<RestockWatchItem>>(emptyList())
    override val watchItemsFlow: StateFlow<List<RestockWatchItem>> = _watchItemsFlow.asStateFlow().also {
        RestockLoadStateRegistry.register(it, _isLoadedFlow.asStateFlow())
    }

    private val _eventHistoryFlow = MutableStateFlow<List<RestockEventRecord>>(emptyList())
    override val eventHistoryFlow: StateFlow<List<RestockEventRecord>> = _eventHistoryFlow.asStateFlow()

    private val _settingsFlow = MutableStateFlow(RestockMonitorSettings().normalized())
    override val settingsFlow: StateFlow<RestockMonitorSettings> = _settingsFlow.asStateFlow()

    init {
        preloadScope.launch {
            mutex.withLock {
                ensureDiskLoadedLocked()
            }
        }
    }

    private suspend fun ensureDiskLoadedLocked() {
        if (diskLoaded) return
        val snapshot = withContext(Dispatchers.IO) {
            readSnapshotFromDiskSync()
        }
        _watchItemsFlow.value = snapshot.watchItems
        _eventHistoryFlow.value = snapshot.events
        _settingsFlow.value = snapshot.settings.normalized()
        diskLoaded = true
        _isLoadedFlow.value = true
    }

    override suspend fun getWatchItemsSnapshot(): List<RestockWatchItem> =
        mutex.withLock {
            ensureDiskLoadedLocked()
            _watchItemsFlow.value
        }

    override suspend fun getEventHistorySnapshot(): List<RestockEventRecord> =
        mutex.withLock {
            ensureDiskLoadedLocked()
            _eventHistoryFlow.value
        }

    override suspend fun getSettingsSnapshot(): RestockMonitorSettings =
        mutex.withLock {
            ensureDiskLoadedLocked()
            _settingsFlow.value
        }

    override suspend fun addOrUpdateWatch(item: RestockWatchItem): RestockWatchItem =
        mutex.withLock {
            ensureDiskLoadedLocked()
            val normalizedUrl = MerchantLinkDetector.resolveOrderUrl(item.rawUrl)
                .ifBlank { item.resolvedOrderUrl.trim() }
            val resolvedPanel = if (item.panelType == MerchantPanelType.GENERIC) {
                MerchantLinkDetector.inferPanelTypeFromUrl(normalizedUrl)
            } else {
                item.panelType
            }
            val currentList = _watchItemsFlow.value
            val existing = currentList.firstOrNull { it.id == item.id }
            val urlChanged = existing != null &&
                !normalizedUrl.equals(existing.resolvedOrderUrl, ignoreCase = true)
            val needsTargetReset = urlChanged &&
                item.lastCheckedEpochMillis == existing?.lastCheckedEpochMillis
            val effectiveStatus = if (needsTargetReset) RestockStockStatus.UNKNOWN else item.stockStatus

            val normalizedItem = item.copy(
                id = item.id.ifBlank { UUID.randomUUID().toString() },
                title = item.title.trim().ifBlank { existing?.title ?: "VPS 商品监控" },
                rawUrl = item.rawUrl.trim(),
                resolvedOrderUrl = normalizedUrl,
                panelType = resolvedPanel,
                priceHint = item.priceHint.trim(),
                customInStockKeyword = item.customInStockKeyword.trim(),
                customOutOfStockKeyword = item.customOutOfStockKeyword.trim(),
                pollIntervalMinutes = RestockWatchItem.clampPollInterval(item.pollIntervalMinutes),
                stockStatus = effectiveStatus,
                lastCheckedEpochMillis = if (needsTargetReset) 0L else item.lastCheckedEpochMillis,
                consecutiveErrors = if (needsTargetReset) 0 else item.consecutiveErrors,
                lastErrorMessage = if (needsTargetReset) null else item.lastErrorMessage,
                alertWaveArmed = when {
                    needsTargetReset -> false
                    effectiveStatus == RestockStockStatus.OUT_OF_STOCK -> true
                    effectiveStatus == RestockStockStatus.IN_STOCK -> false
                    urlChanged -> item.alertWaveArmed
                    else -> existing?.alertWaveArmed ?: item.alertWaveArmed
                },
            )

            val updatedList = if (existing != null) {
                currentList.map { if (it.id == normalizedItem.id) normalizedItem else it }
            } else {
                listOf(normalizedItem) + currentList
            }

            persistLocked(
                RestockStoreSnapshot(
                    watchItems = updatedList,
                    events = _eventHistoryFlow.value,
                    settings = _settingsFlow.value,
                ),
            )
            normalizedItem
        }

    override suspend fun removeWatch(id: String) {
        mutex.withLock {
            ensureDiskLoadedLocked()
            val updated = _watchItemsFlow.value.filterNot { it.id == id }
            if (updated.size == _watchItemsFlow.value.size) return
            persistLocked(
                RestockStoreSnapshot(
                    watchItems = updated,
                    events = _eventHistoryFlow.value,
                    settings = _settingsFlow.value,
                ),
            )
        }
    }

    override suspend fun setWatchEnabled(id: String, enabled: Boolean) {
        mutex.withLock {
            ensureDiskLoadedLocked()
            val current = _watchItemsFlow.value
            val target = current.firstOrNull { it.id == id } ?: return
            if (target.isEnabled == enabled) return
            val updated = current.map { item ->
                if (item.id == id) item.copy(isEnabled = enabled) else item
            }
            persistLocked(
                RestockStoreSnapshot(
                    watchItems = updated,
                    events = _eventHistoryFlow.value,
                    settings = _settingsFlow.value,
                ),
            )
        }
    }

    override suspend fun updateWatchPollInterval(id: String, intervalMinutes: Int) {
        mutex.withLock {
            ensureDiskLoadedLocked()
            val clamped = RestockWatchItem.clampPollInterval(intervalMinutes)
            val current = _watchItemsFlow.value
            val target = current.firstOrNull { it.id == id } ?: return
            if (target.pollIntervalMinutes == clamped) return
            val updated = current.map { item ->
                if (item.id == id) item.copy(pollIntervalMinutes = clamped) else item
            }
            persistLocked(
                RestockStoreSnapshot(
                    watchItems = updated,
                    events = _eventHistoryFlow.value,
                    settings = _settingsFlow.value,
                ),
            )
        }
    }

    override suspend fun recordCheckResult(
        watchId: String,
        analysis: StockAnalysisResult,
    ): RestockEventRecord? = mutex.withLock {
        ensureDiskLoadedLocked()
        val currentItems = _watchItemsFlow.value
        val existing = currentItems.firstOrNull { it.id == watchId } ?: return@withLock null

        val checkedAt = if (analysis.checkedAtEpochMillis > 0L) {
            analysis.checkedAtEpochMillis
        } else {
            clockMillis()
        }

        val previousStatus = existing.stockStatus
        val newStatus = analysis.stockStatus
        val wasArmed = existing.alertWaveArmed || previousStatus == RestockStockStatus.OUT_OF_STOCK

        // Only trigger a restock wave when transitioning into IN_STOCK while armed (having been OUT_OF_STOCK).
        // Repeated IN_STOCK polls or IN_STOCK -> CHECK_ERROR -> IN_STOCK remain disarmed and deduped.
        val isNewRestockWave = (newStatus == RestockStockStatus.IN_STOCK) && wasArmed

        val nextArmed = when (newStatus) {
            RestockStockStatus.OUT_OF_STOCK -> true
            RestockStockStatus.IN_STOCK -> false
            RestockStockStatus.CHECK_ERROR,
            RestockStockStatus.UNKNOWN,
            -> wasArmed
        }

        val nextPanel = if (analysis.panelType != MerchantPanelType.GENERIC) {
            analysis.panelType
        } else {
            existing.panelType
        }

        val nextTitle = existing.title.ifBlank { analysis.detectedTitle.ifBlank { "VPS 商品监控" } }
        val nextPriceHint = existing.priceHint.ifBlank { analysis.detectedPriceHint }
        val nextErrors = if (newStatus == RestockStockStatus.CHECK_ERROR) {
            existing.consecutiveErrors + 1
        } else {
            0
        }

        val updatedItem = existing.copy(
            title = nextTitle,
            panelType = nextPanel,
            priceHint = nextPriceHint,
            stockStatus = newStatus,
            lastCheckedEpochMillis = checkedAt,
            lastRestockedEpochMillis = if (isNewRestockWave) checkedAt else existing.lastRestockedEpochMillis,
            consecutiveErrors = nextErrors,
            lastErrorMessage = if (newStatus == RestockStockStatus.CHECK_ERROR) {
                analysis.errorMessage ?: analysis.matchedSignal
            } else {
                null
            },
            lastEvidenceSummary = analysis.matchedSignal,
            alertWaveArmed = nextArmed,
        )

        val updatedItems = currentItems.map { if (it.id == watchId) updatedItem else it }

        val createdEvent = if (isNewRestockWave) {
            RestockEventRecord(
                id = UUID.randomUUID().toString(),
                watchItemId = updatedItem.id,
                watchTitle = updatedItem.title,
                resolvedOrderUrl = updatedItem.resolvedOrderUrl,
                panelType = updatedItem.panelType,
                priceHint = updatedItem.priceHint,
                previousStatus = RestockStockStatus.OUT_OF_STOCK,
                detectedStatus = RestockStockStatus.IN_STOCK,
                evidenceSummary = analysis.matchedSignal,
                detectedAtEpochMillis = checkedAt,
                isRead = false,
                sourceTopicId = updatedItem.sourceTopicId,
            )
        } else {
            null
        }

        val updatedEvents = if (createdEvent != null) {
            (listOf(createdEvent) + _eventHistoryFlow.value).take(MAX_EVENT_HISTORY)
        } else {
            _eventHistoryFlow.value
        }

        persistLocked(
            RestockStoreSnapshot(
                watchItems = updatedItems,
                events = updatedEvents,
                settings = _settingsFlow.value,
            ),
        )
        createdEvent
    }

    override suspend fun markEventRead(eventId: String) {
        mutex.withLock {
            ensureDiskLoadedLocked()
            val current = _eventHistoryFlow.value
            if (current.none { it.id == eventId && !it.isRead }) return
            val updated = current.map { event ->
                if (event.id == eventId) event.copy(isRead = true) else event
            }
            persistLocked(
                RestockStoreSnapshot(
                    watchItems = _watchItemsFlow.value,
                    events = updated,
                    settings = _settingsFlow.value,
                ),
            )
        }
    }

    override suspend fun markAllEventsRead() {
        mutex.withLock {
            ensureDiskLoadedLocked()
            val current = _eventHistoryFlow.value
            if (current.none { !it.isRead }) return
            val updated = current.map { it.copy(isRead = true) }
            persistLocked(
                RestockStoreSnapshot(
                    watchItems = _watchItemsFlow.value,
                    events = updated,
                    settings = _settingsFlow.value,
                ),
            )
        }
    }

    override suspend fun deleteEvent(eventId: String) {
        mutex.withLock {
            ensureDiskLoadedLocked()
            val updated = _eventHistoryFlow.value.filterNot { it.id == eventId }
            if (updated.size == _eventHistoryFlow.value.size) return
            persistLocked(
                RestockStoreSnapshot(
                    watchItems = _watchItemsFlow.value,
                    events = updated,
                    settings = _settingsFlow.value,
                ),
            )
        }
    }

    override suspend fun clearEventHistory() {
        mutex.withLock {
            ensureDiskLoadedLocked()
            if (_eventHistoryFlow.value.isEmpty()) return
            persistLocked(
                RestockStoreSnapshot(
                    watchItems = _watchItemsFlow.value,
                    events = emptyList(),
                    settings = _settingsFlow.value,
                ),
            )
        }
    }

    override suspend fun updateSettings(
        transform: (RestockMonitorSettings) -> RestockMonitorSettings,
    ): RestockMonitorSettings = mutex.withLock {
        ensureDiskLoadedLocked()
        val updated = transform(_settingsFlow.value).normalized()
        if (updated == _settingsFlow.value) return@withLock updated
        persistLocked(
            RestockStoreSnapshot(
                watchItems = _watchItemsFlow.value,
                events = _eventHistoryFlow.value,
                settings = updated,
            ),
        )
        updated
    }

    override suspend fun exportForBackup(): RestockBackupDto = mutex.withLock {
        ensureDiskLoadedLocked()
        RestockBackupDto(
            watchItems = _watchItemsFlow.value,
            settings = _settingsFlow.value,
        )
    }

    override suspend fun restoreFromBackup(
        backup: RestockBackupDto,
        replaceExisting: Boolean,
    ) {
        mutex.withLock {
            ensureDiskLoadedLocked()
            val normalizedIncoming = LinkedHashMap<String, RestockWatchItem>()
            for (raw in backup.watchItems) {
                val resolvedUrl = MerchantLinkDetector.resolveOrderUrl(raw.rawUrl)
                    .ifBlank { raw.resolvedOrderUrl.trim() }
                if (resolvedUrl.isBlank()) continue
                val cleanId = raw.id.trim().ifBlank { UUID.randomUUID().toString() }
                val resolvedPanel = if (raw.panelType == MerchantPanelType.GENERIC) {
                    MerchantLinkDetector.inferPanelTypeFromUrl(resolvedUrl)
                } else {
                    raw.panelType
                }
                normalizedIncoming[cleanId] = raw.copy(
                    id = cleanId,
                    title = raw.title.trim().ifBlank { "VPS 商品监控" },
                    rawUrl = raw.rawUrl.trim().ifBlank { resolvedUrl },
                    resolvedOrderUrl = resolvedUrl,
                    panelType = resolvedPanel,
                    priceHint = raw.priceHint.trim(),
                    customInStockKeyword = raw.customInStockKeyword.trim(),
                    customOutOfStockKeyword = raw.customOutOfStockKeyword.trim(),
                    pollIntervalMinutes = RestockWatchItem.clampPollInterval(raw.pollIntervalMinutes),
                )
            }

            val mergedWatches = if (replaceExisting) {
                normalizedIncoming.values.toList()
            } else {
                val combined = LinkedHashMap<String, RestockWatchItem>()
                _watchItemsFlow.value.forEach { combined[it.id] = it }
                normalizedIncoming.forEach { (id, item) -> combined[id] = item }
                combined.values.toList()
            }

            val remainingIds = mergedWatches.map { it.id }.toSet()
            val nextEvents = if (replaceExisting) {
                _eventHistoryFlow.value.filter { it.watchItemId in remainingIds }
            } else {
                _eventHistoryFlow.value
            }

            persistLocked(
                RestockStoreSnapshot(
                    watchItems = mergedWatches,
                    events = nextEvents,
                    settings = backup.settings.normalized(),
                ),
            )
        }
    }

    private suspend fun persistLocked(snapshot: RestockStoreSnapshot) {
        val normalizedSnapshot = snapshot.copy(settings = snapshot.settings.normalized())
        withContext(Dispatchers.IO) {
            writeSnapshotAtomic(normalizedSnapshot)
        }
        _watchItemsFlow.value = normalizedSnapshot.watchItems
        _eventHistoryFlow.value = normalizedSnapshot.events
        _settingsFlow.value = normalizedSnapshot.settings
    }

    private fun readSnapshotFromDiskSync(): RestockStoreSnapshot {
        if (!storeFile.isFile) return RestockStoreSnapshot()
        return runCatching {
            val raw = storeFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) {
                RestockStoreSnapshot()
            } else {
                val decoded = json.decodeFromString<RestockStoreSnapshot>(raw)
                decoded.copy(settings = decoded.settings.normalized())
            }
        }.getOrElse {
            RestockStoreSnapshot()
        }
    }

    private fun writeSnapshotAtomic(snapshot: RestockStoreSnapshot) {
        val parent = storeFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val payload = json.encodeToString(snapshot)
        val tempFile = File(parent ?: File("."), "${storeFile.name}.tmp")
        tempFile.writeText(payload, Charsets.UTF_8)
        if (!tempFile.renameTo(storeFile)) {
            // Fallback for filesystems where renameTo cannot replace an existing target
            storeFile.writeText(payload, Charsets.UTF_8)
            tempFile.delete()
        }
    }

    companion object {
        const val STORE_FILE_NAME = "restock_monitor_store.json"
        const val MAX_EVENT_HISTORY = 200
    }
}
