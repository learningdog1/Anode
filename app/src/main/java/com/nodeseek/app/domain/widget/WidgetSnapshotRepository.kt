package com.nodeseek.app.domain.widget

import com.nodeseek.app.domain.mail.LatestSeekMailOtp

/**
 * Provides cold-start-safe aggregated snapshots for Jetpack Glance AppWidgets and App Shortcuts.
 */
interface WidgetSnapshotRepository {
    suspend fun loadServerStatusSnapshot(): ServerStatusWidgetSnapshot
    suspend fun loadDailyPulseSnapshot(): DailyPulseWidgetSnapshot
    suspend fun fetchOrCachedLatestOtp(timeoutMs: Long = DEFAULT_OTP_FETCH_TIMEOUT_MS): LatestSeekMailOtp?

    companion object {
        const val DEFAULT_OTP_FETCH_TIMEOUT_MS = 3_500L
    }
}

object NoOpWidgetSnapshotRepository : WidgetSnapshotRepository {
    override suspend fun loadServerStatusSnapshot(): ServerStatusWidgetSnapshot =
        ServerStatusWidgetSnapshot()

    override suspend fun loadDailyPulseSnapshot(): DailyPulseWidgetSnapshot =
        DailyPulseWidgetSnapshot()

    override suspend fun fetchOrCachedLatestOtp(timeoutMs: Long): LatestSeekMailOtp? = null
}
