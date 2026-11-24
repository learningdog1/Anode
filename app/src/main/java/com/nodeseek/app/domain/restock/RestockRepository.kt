package com.nodeseek.app.domain.restock

import com.nodeseek.app.domain.backup.RestockBackupDto
import kotlinx.coroutines.flow.StateFlow

/**
 * Persistent thread-safe repository for VPS restock watch items, restock wave event history,
 * and monitor polling preferences.
 */
interface RestockRepository {
    val watchItemsFlow: StateFlow<List<RestockWatchItem>>
    val eventHistoryFlow: StateFlow<List<RestockEventRecord>>
    val settingsFlow: StateFlow<RestockMonitorSettings>

    suspend fun getWatchItemsSnapshot(): List<RestockWatchItem>
    suspend fun getEventHistorySnapshot(): List<RestockEventRecord>
    suspend fun getSettingsSnapshot(): RestockMonitorSettings

    suspend fun addOrUpdateWatch(item: RestockWatchItem): RestockWatchItem
    suspend fun removeWatch(id: String)
    suspend fun setWatchEnabled(id: String, enabled: Boolean)
    suspend fun updateWatchPollInterval(id: String, intervalMinutes: Int)

    /**
     * Atomically applies a page check result to [watchId] and enforces restock wave deduplication:
     * returns a newly persisted [RestockEventRecord] if and only if the watch transitioned into
     * [RestockStockStatus.IN_STOCK] while armed (after having been [RestockStockStatus.OUT_OF_STOCK]),
     * then disarms the wave until [RestockStockStatus.OUT_OF_STOCK] is observed again.
     */
    suspend fun recordCheckResult(
        watchId: String,
        analysis: StockAnalysisResult,
    ): RestockEventRecord?

    suspend fun markEventRead(eventId: String)
    suspend fun markAllEventsRead()
    suspend fun deleteEvent(eventId: String)
    suspend fun clearEventHistory()

    suspend fun updateSettings(
        transform: (RestockMonitorSettings) -> RestockMonitorSettings,
    ): RestockMonitorSettings

    /**
     * Exports restock watch rules and monitor settings for encrypted full backup v2.
     */
    suspend fun exportForBackup(): RestockBackupDto = RestockBackupDto()

    /**
     * Restores restock watch rules and monitor settings from an encrypted full backup v2 payload.
     */
    suspend fun restoreFromBackup(
        backup: RestockBackupDto,
        replaceExisting: Boolean,
    ) = Unit
}
