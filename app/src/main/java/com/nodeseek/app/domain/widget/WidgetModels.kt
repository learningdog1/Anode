package com.nodeseek.app.domain.widget

import com.nodeseek.app.domain.fleet.AssetExpiryUrgency

/**
 * Snapshot state rendered by `ServerStatusGlanceWidget`:
 * probe availability count, deterministic primary machine traffic bar, and nearest VPS renewal.
 */
data class ServerStatusWidgetSnapshot(
    val totalProbeCount: Int = 0,
    val onlineProbeCount: Int = 0,
    val offlineProbeCount: Int = 0,
    val primaryServerName: String? = null,
    val primaryServerFlag: String = "",
    val primaryTrafficUsedBytes: Long = 0L,
    val primaryTrafficLimitBytes: Long? = null,
    val primaryTrafficRatio: Float? = null,
    val nearestVpsName: String? = null,
    val nearestVpsDaysRemaining: Long? = null,
    val nearestVpsUrgency: AssetExpiryUrgency? = null,
    val nearestVpsExpiryIso: String? = null,
    val lastSyncEpochMillis: Long = 0L,
) {
    val hasProbes: Boolean
        get() = totalProbeCount > 0

    val hasNearestVps: Boolean
        get() = !nearestVpsName.isNullOrBlank() && nearestVpsDaysRemaining != null
}

/**
 * Snapshot state rendered by `DailyPulseGlanceWidget`:
 * daily check-in status/rewards, Radar unread pulse & latest hit, and cached Seek Mail OTP.
 */
data class DailyPulseWidgetSnapshot(
    val isSignedIn: Boolean = false,
    val username: String = "",
    val drumsticks: Int = 0,
    val hasCheckedInToday: Boolean = false,
    val todayGain: Int? = null,
    val todayOrder: Int? = null,
    val preferRandomCheckIn: Boolean = false,
    val radarEnabled: Boolean = true,
    val unreadAlertCount: Int = 0,
    val latestAlertTitle: String? = null,
    val latestAlertKeyword: String? = null,
    val latestOtpCode: String? = null,
    val latestOtpSender: String? = null,
    val hasSeekMailKey: Boolean = false,
)
