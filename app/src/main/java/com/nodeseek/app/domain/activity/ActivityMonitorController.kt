package com.nodeseek.app.domain.activity

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * 社区「新活动通知」本地持久化配置。
 *
 * 开关仅在「活动」页面内展示，且默认严格为关闭（`notificationsEnabled = false`），
 * 仅在用户主动开启并授予系统通知权限后启动前后台低频巡检。
 */
@Immutable
@Serializable
data class ActivityMonitorSettings(
    val notificationsEnabled: Boolean = false,
    val foregroundIntervalMinutes: Int = DEFAULT_FOREGROUND_INTERVAL_MINUTES,
    val backgroundIntervalMinutes: Int = DEFAULT_BACKGROUND_INTERVAL_MINUTES,
    val wifiOnlyInBackground: Boolean = false,
    val requireBatteryNotLowInBackground: Boolean = false,
) {
    fun normalized(): ActivityMonitorSettings = copy(
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
        const val DEFAULT_FOREGROUND_INTERVAL_MINUTES = 3
        const val MIN_FOREGROUND_INTERVAL_MINUTES = 1
        const val MAX_FOREGROUND_INTERVAL_MINUTES = 30
        const val DEFAULT_BACKGROUND_INTERVAL_MINUTES = 15
        const val MIN_BACKGROUND_INTERVAL_MINUTES = 15
        const val MAX_BACKGROUND_INTERVAL_MINUTES = 180
    }
}

/**
 * 社区活动巡检运行状态。
 */
@Immutable
data class ActivityMonitorRuntimeStatus(
    val isRunningInForeground: Boolean = true,
    val isPollingNow: Boolean = false,
    val baselineInitialized: Boolean = false,
    val lastCheckEpochMillis: Long = 0L,
    val lastNotifiedEpochMillis: Long = 0L,
    val lastErrorSummary: String? = null,
)

/**
 * 单次社区活动巡检结果。
 */
sealed interface ActivityPollOutcome {
    data class Completed(
        val totalActivities: Int = 0,
        val newActivitiesNotified: Int = 0,
        val baselineInitializedNow: Boolean = false,
    ) : ActivityPollOutcome

    data class Retry(
        val reason: String,
    ) : ActivityPollOutcome

    data class Skipped(
        val reason: SkipReason,
    ) : ActivityPollOutcome {
        enum class SkipReason {
            DISABLED,
            BACKOFF_ACTIVE,
            UNMETERED_NETWORK_REQUIRED,
        }
    }
}

/**
 * 负责管理社区活动前后台巡检、首次开启历史基线静默建档（防刷屏）以及去重推送通知。
 */
interface ActivityMonitorController {
    val settingsFlow: StateFlow<ActivityMonitorSettings>
    val runtimeStatus: StateFlow<ActivityMonitorRuntimeStatus>

    fun start()
    fun stop()
    fun onForegroundChanged(inForeground: Boolean)
    fun triggerImmediateCheck()

    suspend fun setNotificationsEnabled(enabled: Boolean)
    suspend fun updateSettings(
        transform: (ActivityMonitorSettings) -> ActivityMonitorSettings,
    ): ActivityMonitorSettings

    suspend fun checkNow(manualTrigger: Boolean = false): ActivityPollOutcome

    suspend fun runBackgroundPollPass(): ActivityPollOutcome
}

object NoOpActivityMonitorController : ActivityMonitorController {
    override val settingsFlow: StateFlow<ActivityMonitorSettings> =
        MutableStateFlow(ActivityMonitorSettings()).asStateFlow()
    override val runtimeStatus: StateFlow<ActivityMonitorRuntimeStatus> =
        MutableStateFlow(ActivityMonitorRuntimeStatus()).asStateFlow()

    override fun start() = Unit
    override fun stop() = Unit
    override fun onForegroundChanged(inForeground: Boolean) = Unit
    override fun triggerImmediateCheck() = Unit
    override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit
    override suspend fun updateSettings(
        transform: (ActivityMonitorSettings) -> ActivityMonitorSettings,
    ): ActivityMonitorSettings = transform(settingsFlow.value).normalized()
    override suspend fun checkNow(manualTrigger: Boolean): ActivityPollOutcome =
        ActivityPollOutcome.Skipped(ActivityPollOutcome.Skipped.SkipReason.DISABLED)
    override suspend fun runBackgroundPollPass(): ActivityPollOutcome =
        ActivityPollOutcome.Skipped(ActivityPollOutcome.Skipped.SkipReason.DISABLED)
}
