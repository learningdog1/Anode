package com.nodeseek.app.ui.radar

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.radar.KeywordWatchRule
import com.nodeseek.app.domain.radar.LocalAlertKind
import com.nodeseek.app.domain.radar.LocalAlertRecord
import com.nodeseek.app.domain.radar.RadarMonitorController
import com.nodeseek.app.domain.radar.RadarPollingSettings
import com.nodeseek.app.domain.radar.RadarRepository
import com.nodeseek.app.domain.radar.RadarRuntimeStatus
import com.nodeseek.app.domain.radar.TrackedTopic
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class RadarAlertFilter(val label: String) {
    ALL("全部"),
    UNREAD("未读"),
    KEYWORD_HIT("关键词命中"),
    TRACKED_REPLY("追踪回复"),
}

@Immutable
data class RadarEnvironmentDiagnostics(
    val hasNotificationPermission: Boolean = true,
    val isIgnoringBatteryOptimizations: Boolean = false,
    val isSystemDoNotDisturbActive: Boolean = false,
)

@Immutable
data class PulseRadarUiState(
    val selectedFilter: RadarAlertFilter = RadarAlertFilter.ALL,
    val alerts: List<LocalAlertRecord> = emptyList(),
    val filteredAlerts: List<LocalAlertRecord> = emptyList(),
    val unreadAlertCount: Int = 0,
    val keywordHitCount: Int = 0,
    val trackedReplyCount: Int = 0,
    val keywordRules: List<KeywordWatchRule> = emptyList(),
    val trackedTopics: List<TrackedTopic> = emptyList(),
    val pollingSettings: RadarPollingSettings = RadarPollingSettings(),
    val runtimeStatus: RadarRuntimeStatus = RadarRuntimeStatus(),
    val environmentDiagnostics: RadarEnvironmentDiagnostics = RadarEnvironmentDiagnostics(),
    val addRuleError: String? = null,
    val isSubmittingRule: Boolean = false,
)

class PulseRadarViewModel(
    private val radarRepository: RadarRepository,
    private val monitorController: RadarMonitorController,
    private val notificationDispatcher: PulseNotificationDispatcher,
) : ViewModel() {

    private val _selectedFilter = MutableStateFlow(RadarAlertFilter.ALL)
    private val _diagnostics = MutableStateFlow(readEnvironmentDiagnostics())
    private val _addRuleError = MutableStateFlow<String?>(null)
    private val _isSubmittingRule = MutableStateFlow(false)
    private val _snackbarChannel = Channel<String>(Channel.BUFFERED)

    val snackbarMessages: Flow<String> = _snackbarChannel.receiveAsFlow()

    private data class DraftState(
        val filter: RadarAlertFilter,
        val diagnostics: RadarEnvironmentDiagnostics,
        val addRuleError: String?,
        val isSubmittingRule: Boolean,
    )

    private val draftFlow = combine(
        _selectedFilter,
        _diagnostics,
        _addRuleError,
        _isSubmittingRule,
    ) { filter, diagnostics, error, submitting ->
        DraftState(
            filter = filter,
            diagnostics = diagnostics,
            addRuleError = error,
            isSubmittingRule = submitting,
        )
    }

    private data class RadarCollections(
        val alerts: List<LocalAlertRecord>,
        val unreadCount: Int,
        val rules: List<KeywordWatchRule>,
        val tracked: List<TrackedTopic>,
    )

    private val collectionsFlow = combine(
        radarRepository.alertRecordsFlow,
        radarRepository.unreadAlertCountFlow,
        radarRepository.keywordRulesFlow,
        radarRepository.trackedTopicsFlow,
    ) { alerts, unreadCount, rules, tracked ->
        RadarCollections(
            alerts = alerts,
            unreadCount = unreadCount,
            rules = rules,
            tracked = tracked,
        )
    }

    val uiState: StateFlow<PulseRadarUiState> = combine(
        collectionsFlow,
        radarRepository.pollingSettingsFlow,
        monitorController.runtimeStatus,
        draftFlow,
    ) { collections, settings, status, draft ->
        val filtered = when (draft.filter) {
            RadarAlertFilter.ALL -> collections.alerts
            RadarAlertFilter.UNREAD -> collections.alerts.filter { !it.isRead }
            RadarAlertFilter.KEYWORD_HIT -> collections.alerts.filter { it.kind == LocalAlertKind.KEYWORD_HIT }
            RadarAlertFilter.TRACKED_REPLY -> collections.alerts.filter { it.kind == LocalAlertKind.TRACKED_REPLY }
        }
        PulseRadarUiState(
            selectedFilter = draft.filter,
            alerts = collections.alerts,
            filteredAlerts = filtered,
            unreadAlertCount = collections.unreadCount,
            keywordHitCount = collections.alerts.count { it.kind == LocalAlertKind.KEYWORD_HIT },
            trackedReplyCount = collections.alerts.count { it.kind == LocalAlertKind.TRACKED_REPLY },
            keywordRules = collections.rules,
            trackedTopics = collections.tracked,
            pollingSettings = settings,
            runtimeStatus = status,
            environmentDiagnostics = draft.diagnostics,
            addRuleError = draft.addRuleError,
            isSubmittingRule = draft.isSubmittingRule,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = PulseRadarUiState(environmentDiagnostics = _diagnostics.value),
    )

    fun refreshEnvironmentDiagnostics() {
        _diagnostics.value = readEnvironmentDiagnostics()
    }

    fun selectFilter(filter: RadarAlertFilter) {
        _selectedFilter.value = filter
    }

    fun markAlertRead(alertId: Long, read: Boolean) {
        viewModelScope.launch {
            radarRepository.markAlertRead(alertId = alertId, read = read)
        }
    }

    fun onOpenAlertRecord(record: LocalAlertRecord) {
        if (record.isRead) return
        viewModelScope.launch {
            radarRepository.markAlertRead(alertId = record.id, read = true)
        }
    }

    fun markAllAlertsRead() {
        viewModelScope.launch {
            radarRepository.markAllAlertsRead()
            _snackbarChannel.trySend("已将所有提醒记录标为已读")
        }
    }

    fun deleteAlert(alertId: Long) {
        viewModelScope.launch {
            radarRepository.deleteAlert(alertId)
        }
    }

    fun clearReadAlerts() {
        viewModelScope.launch {
            radarRepository.clearReadAlerts()
            _snackbarChannel.trySend("已清理所有已读提醒记录")
        }
    }

    fun clearAllAlerts() {
        viewModelScope.launch {
            radarRepository.clearAllAlerts()
            _snackbarChannel.trySend("已清空本地提醒记录")
        }
    }

    fun clearAddRuleError() {
        _addRuleError.value = null
    }

    fun submitKeywordRule(
        keyword: String,
        excludeWords: String,
        categorySlug: String,
        matchMode: KeywordMatchMode,
        onSuccess: () -> Unit,
    ) {
        val trimmedKeyword = keyword.trim()
        if (trimmedKeyword.isEmpty()) {
            _addRuleError.value = "请输入关键词或匹配表达式"
            return
        }
        if (matchMode == KeywordMatchMode.REGEX) {
            val regexCheck = runCatching { Regex(trimmedKeyword) }
            if (regexCheck.isFailure) {
                val reason = regexCheck.exceptionOrNull()?.localizedMessage ?: "语法错误"
                _addRuleError.value = "正则表达式无效：$reason"
                return
            }
        }

        viewModelScope.launch {
            _isSubmittingRule.value = true
            _addRuleError.value = null
            val result = radarRepository.addKeywordRule(
                keyword = trimmedKeyword,
                excludeWords = excludeWords.trim(),
                categorySlug = categorySlug.trim().lowercase(),
                matchMode = matchMode,
            )
            _isSubmittingRule.value = false
            result.onSuccess {
                _snackbarChannel.trySend("已新增监控关键词「$trimmedKeyword」")
                onSuccess()
            }.onFailure { err ->
                _addRuleError.value = err.message?.takeIf { it.isNotBlank() } ?: "添加关键词失败，请检查输入"
            }
        }
    }

    fun toggleKeywordRule(ruleId: Long, enabled: Boolean) {
        viewModelScope.launch {
            radarRepository.toggleKeywordRule(ruleId = ruleId, enabled = enabled)
        }
    }

    fun deleteKeywordRule(ruleId: Long) {
        viewModelScope.launch {
            radarRepository.deleteKeywordRule(ruleId)
            _snackbarChannel.trySend("已移除该关键词规则")
        }
    }

    fun untrackTopic(topicId: Long) {
        viewModelScope.launch {
            radarRepository.untrackTopic(topicId)
            _snackbarChannel.trySend("已停止追踪该帖子的后续回复")
        }
    }

    fun markTopicAlertsRead(topicId: Long) {
        viewModelScope.launch {
            radarRepository.markAlertsReadForTopic(topicId)
        }
    }

    fun triggerImmediateCheck() {
        if (!uiState.value.pollingSettings.isEnabled) {
            _snackbarChannel.trySend("请先开启雷达监控总开关，再执行立即检查")
            return
        }
        monitorController.triggerImmediateCheck()
        _snackbarChannel.trySend("已发起一次即时雷达巡检")
    }

    fun setMasterEnabled(enabled: Boolean) {
        updatePollingSettings { it.copy(isEnabled = enabled) }
    }

    fun setKeywordAlertsEnabled(enabled: Boolean) {
        updatePollingSettings { it.copy(keywordAlertsEnabled = enabled) }
    }

    fun setReplyTrackingEnabled(enabled: Boolean) {
        updatePollingSettings { it.copy(replyTrackingEnabled = enabled) }
    }

    fun setInboxPushEnabled(enabled: Boolean) {
        updatePollingSettings { it.copy(inboxPushEnabled = enabled) }
    }

    fun setForegroundIntervalMinutes(minutes: Int) {
        updatePollingSettings {
            it.copy(
                foregroundIntervalMinutes = minutes.coerceIn(
                    RadarPollingSettings.MIN_FOREGROUND_INTERVAL_MINUTES,
                    RadarPollingSettings.MAX_FOREGROUND_INTERVAL_MINUTES,
                ),
            )
        }
    }

    fun setBackgroundIntervalMinutes(minutes: Int) {
        updatePollingSettings {
            it.copy(
                backgroundIntervalMinutes = minutes.coerceIn(
                    RadarPollingSettings.MIN_BACKGROUND_INTERVAL_MINUTES,
                    RadarPollingSettings.MAX_BACKGROUND_INTERVAL_MINUTES,
                ),
            )
        }
    }

    fun setWifiOnlyInBackground(wifiOnly: Boolean) {
        updatePollingSettings { it.copy(wifiOnlyInBackground = wifiOnly) }
    }

    fun setRequireBatteryNotLowInBackground(requireBatteryNotLow: Boolean) {
        updatePollingSettings { it.copy(requireBatteryNotLowInBackground = requireBatteryNotLow) }
    }

    fun setQuietHoursEnabled(enabled: Boolean) {
        updatePollingSettings { it.copy(quietHoursEnabled = enabled) }
    }

    private fun updatePollingSettings(transform: (RadarPollingSettings) -> RadarPollingSettings) {
        viewModelScope.launch {
            radarRepository.updatePollingSettings(transform)
        }
    }

    private fun readEnvironmentDiagnostics(): RadarEnvironmentDiagnostics {
        return RadarEnvironmentDiagnostics(
            hasNotificationPermission = notificationDispatcher.hasPostNotificationsPermission(),
            isIgnoringBatteryOptimizations = notificationDispatcher.isIgnoringBatteryOptimizations(),
            isSystemDoNotDisturbActive = notificationDispatcher.isSystemDoNotDisturbActive(),
        )
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PulseRadarViewModel(
                        radarRepository = container.radarRepository,
                        monitorController = container.radarMonitorController,
                        notificationDispatcher = container.notificationDispatcher,
                    ) as T
                }
            }
    }
}
