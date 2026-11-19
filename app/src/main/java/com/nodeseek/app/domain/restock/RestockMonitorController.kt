package com.nodeseek.app.domain.restock

import kotlinx.coroutines.flow.StateFlow

/**
 * Coordinates foreground fast-polling, on-demand URL probing, single/batch manual inspection,
 * and WorkManager background poll passes for the VPS restock monitor.
 */
interface RestockMonitorController {
    val runtimeStatus: StateFlow<RestockRuntimeStatus>

    fun start()
    fun stop()
    fun onForegroundChanged(inForeground: Boolean)
    fun triggerImmediateCheckAll()
    fun triggerCheckSingle(watchId: String)

    /**
     * One-shot live probe of an arbitrary product/order URL for UI preview without persisting.
     */
    suspend fun probeUrl(
        rawUrl: String,
        customInStockKeyword: String = "",
        customOutOfStockKeyword: String = "",
    ): StockAnalysisResult

    /**
     * Immediately inspects a single watch item, updates repository state, and dispatches
     * a high-priority heads-up notification if a new restock wave occurred.
     */
    suspend fun checkWatchNow(watchId: String): StockAnalysisResult?

    /**
     * Immediately inspects all enabled (or all) watch items.
     */
    suspend fun checkAllNow(onlyEnabled: Boolean = true): List<StockAnalysisResult>

    /**
     * Executes a serialized background check pass intended for WorkManager execution.
     */
    suspend fun runBackgroundPollPass(): RestockBackgroundPollOutcome
}
