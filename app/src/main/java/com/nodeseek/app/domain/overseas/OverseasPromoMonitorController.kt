package com.nodeseek.app.domain.overseas

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * Persisted configuration for proactive overseas promotion monitoring & push notifications.
 *
 * Per product design, the notification toggle lives exclusively inside the Overseas Intel feature
 * (never in global Settings) and defaults to `true` so new promotions are proactively pushed
 * whenever system notification permission is available.
 */
@Immutable
@Serializable
data class OverseasPromoMonitorSettings(
    val notificationsEnabled: Boolean = true,
    val foregroundIntervalMinutes: Int = DEFAULT_FOREGROUND_INTERVAL_MINUTES,
    val backgroundIntervalMinutes: Int = DEFAULT_BACKGROUND_INTERVAL_MINUTES,
    val wifiOnlyInBackground: Boolean = false,
    val requireBatteryNotLowInBackground: Boolean = false,
) {
    fun normalized(): OverseasPromoMonitorSettings = copy(
        foregroundIntervalMinutes = foregroundIntervalMinutes.coerceIn(
            MIN_FOREGROUND_INTERVAL_MINUTES,
            MAX_FOREGROUND_INTERVAL_MINUTES,
        ),
        backgroundIntervalMinutes = backgroundIntervalMinutes.coerceIn(
            MIN_BACKGROUND_INTERVAL_MINUTES,
            MAX_BACKGROUND_INTERVAL_MINUTES,
        ),
    )

    companion object {
        const val DEFAULT_FOREGROUND_INTERVAL_MINUTES = 2
        const val MIN_FOREGROUND_INTERVAL_MINUTES = 1
        const val MAX_FOREGROUND_INTERVAL_MINUTES = 30
        const val DEFAULT_BACKGROUND_INTERVAL_MINUTES = 15
        const val MIN_BACKGROUND_INTERVAL_MINUTES = 15
        const val MAX_BACKGROUND_INTERVAL_MINUTES = 180

        const val TELEGRAM_MONITOR_CHANNEL_URL = "https://t.me/colmidad_lowendtalk_offer"
    }
}

/**
 * Live runtime status of the foreground/background overseas promotion monitor.
 */
@Immutable
data class OverseasPromoRuntimeStatus(
    val isRunningInForeground: Boolean = true,
    val isPollingNow: Boolean = false,
    val baselineInitialized: Boolean = false,
    val lastCheckEpochMillis: Long = 0L,
    val lastNotifiedEpochMillis: Long = 0L,
    val lastErrorSummary: String? = null,
)

/**
 * Outcome of a single overseas promotion check pass (foreground or WorkManager background).
 */
sealed interface OverseasPromoPollOutcome {
    data class Completed(
        val totalOffers: Int = 0,
        val newOffersNotified: Int = 0,
        val baselineInitializedNow: Boolean = false,
    ) : OverseasPromoPollOutcome

    data class Retry(
        val reason: String,
    ) : OverseasPromoPollOutcome

    data class Skipped(
        val reason: SkipReason,
    ) : OverseasPromoPollOutcome {
        enum class SkipReason {
            DISABLED,
            BACKOFF_ACTIVE,
            UNMETERED_NETWORK_REQUIRED,
        }
    }
}

/**
 * Lifecycle-aware controller managing proactive overseas promotion polling (foreground coroutine
 * loop + background WorkManager passes), baseline initialization without historical spam, and
 * deduplicated system notification dispatch.
 */
interface OverseasPromoMonitorController {
    val settingsFlow: StateFlow<OverseasPromoMonitorSettings>
    val runtimeStatus: StateFlow<OverseasPromoRuntimeStatus>

    fun start()
    fun stop()
    fun onForegroundChanged(inForeground: Boolean)
    fun triggerImmediateCheck()

    suspend fun setNotificationsEnabled(enabled: Boolean)
    suspend fun updateSettings(
        transform: (OverseasPromoMonitorSettings) -> OverseasPromoMonitorSettings,
    ): OverseasPromoMonitorSettings

    /**
     * Executes an immediate check pass (foreground or manual trigger).
     */
    suspend fun checkNow(manualTrigger: Boolean = false): OverseasPromoPollOutcome

    /**
     * Executes a serialized background check pass intended for WorkManager execution.
     */
    suspend fun runBackgroundPollPass(): OverseasPromoPollOutcome
}

object NoOpOverseasPromoMonitorController : OverseasPromoMonitorController {
    override val settingsFlow: StateFlow<OverseasPromoMonitorSettings> =
        MutableStateFlow(OverseasPromoMonitorSettings()).asStateFlow()
    override val runtimeStatus: StateFlow<OverseasPromoRuntimeStatus> =
        MutableStateFlow(OverseasPromoRuntimeStatus()).asStateFlow()

    override fun start() = Unit
    override fun stop() = Unit
    override fun onForegroundChanged(inForeground: Boolean) = Unit
    override fun triggerImmediateCheck() = Unit
    override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit
    override suspend fun updateSettings(
        transform: (OverseasPromoMonitorSettings) -> OverseasPromoMonitorSettings,
    ): OverseasPromoMonitorSettings = transform(settingsFlow.value).normalized()
    override suspend fun checkNow(manualTrigger: Boolean): OverseasPromoPollOutcome =
        OverseasPromoPollOutcome.Completed()
    override suspend fun runBackgroundPollPass(): OverseasPromoPollOutcome =
        OverseasPromoPollOutcome.Completed()
}
