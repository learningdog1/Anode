package com.nodeseek.app.ui.restock

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.data.restock.FlareSolverrClient
import com.nodeseek.app.data.restock.MerchantLinkDetector
import com.nodeseek.app.data.restock.RestockNotificationHelper
import com.nodeseek.app.domain.restock.MerchantPanelType
import com.nodeseek.app.domain.restock.RestockEventRecord
import com.nodeseek.app.domain.restock.RestockMonitorController
import com.nodeseek.app.domain.restock.RestockMonitorSettings
import com.nodeseek.app.domain.restock.RestockRepository
import com.nodeseek.app.domain.restock.RestockRuntimeStatus
import com.nodeseek.app.domain.restock.RestockStockStatus
import com.nodeseek.app.domain.restock.RestockWatchDraft
import com.nodeseek.app.domain.restock.RestockWatchItem
import com.nodeseek.app.domain.restock.StockAnalysisResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URI
import java.util.UUID

@Immutable
data class RestockMonitorUiState(
    val watchItems: List<RestockWatchItem> = emptyList(),
    val eventHistory: List<RestockEventRecord> = emptyList(),
    val settings: RestockMonitorSettings = RestockMonitorSettings(),
    val runtimeStatus: RestockRuntimeStatus = RestockRuntimeStatus(),
    val hasNotificationPermission: Boolean = true,
    val inStockCount: Int = 0,
    val outOfStockCount: Int = 0,
    val errorCount: Int = 0,
    val unreadEventCount: Int = 0,
    val quickUrlInput: String = "",
    val isProbingQuickUrl: Boolean = false,
    val quickProbeResult: StockAnalysisResult? = null,
)

class RestockMonitorViewModel(
    private val repository: RestockRepository,
    private val monitorController: RestockMonitorController,
    private val notificationHelper: RestockNotificationHelper,
    private val flareSolverrClient: FlareSolverrClient,
) : ViewModel() {

    private val _quickUrlInput = MutableStateFlow("")
    private val _isProbingQuickUrl = MutableStateFlow(false)
    private val _quickProbeResult = MutableStateFlow<StockAnalysisResult?>(null)
    private val _hasNotificationPermission = MutableStateFlow(
        runCatching { notificationHelper.hasPostNotificationsPermission() }.getOrDefault(true),
    )
    private val _isTestingFlareSolverr = MutableStateFlow(false)
    private val _snackbarChannel = Channel<String>(Channel.BUFFERED)

    /** Kept outside [uiState] (its `combine` is already at the 5-flow limit). */
    val isTestingFlareSolverr: StateFlow<Boolean> = _isTestingFlareSolverr.asStateFlow()

    val snackbarMessages: Flow<String> = _snackbarChannel.receiveAsFlow()

    private data class QuickBarState(
        val urlInput: String,
        val isProbing: Boolean,
        val probeResult: StockAnalysisResult?,
        val hasNotificationPermission: Boolean,
    )

    private val quickBarFlow = combine(
        _quickUrlInput,
        _isProbingQuickUrl,
        _quickProbeResult,
        _hasNotificationPermission,
    ) { url, probing, probeResult, hasPermission ->
        QuickBarState(
            urlInput = url,
            isProbing = probing,
            probeResult = probeResult,
            hasNotificationPermission = hasPermission,
        )
    }

    val uiState: StateFlow<RestockMonitorUiState> = combine(
        repository.watchItemsFlow,
        repository.eventHistoryFlow,
        repository.settingsFlow,
        monitorController.runtimeStatus,
        quickBarFlow,
    ) { items, events, settings, runtime, quick ->
        RestockMonitorUiState(
            watchItems = items,
            eventHistory = events,
            settings = settings,
            runtimeStatus = runtime,
            hasNotificationPermission = quick.hasNotificationPermission,
            inStockCount = items.count { it.stockStatus == RestockStockStatus.IN_STOCK },
            outOfStockCount = items.count { it.stockStatus == RestockStockStatus.OUT_OF_STOCK },
            errorCount = items.count { it.stockStatus == RestockStockStatus.CHECK_ERROR },
            unreadEventCount = events.count { !it.isRead },
            quickUrlInput = quick.urlInput,
            isProbingQuickUrl = quick.isProbing,
            quickProbeResult = quick.probeResult,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = RestockMonitorUiState(
            hasNotificationPermission = _hasNotificationPermission.value,
        ),
    )

    fun refreshNotificationPermission() {
        _hasNotificationPermission.value = runCatching {
            notificationHelper.hasPostNotificationsPermission()
        }.getOrDefault(true)
    }

    fun onQuickUrlChange(value: String) {
        _quickUrlInput.value = value
        val currentProbe = _quickProbeResult.value
        if (currentProbe != null && currentProbe.rawUrl.trim() != value.trim()) {
            _quickProbeResult.value = null
        }
    }

    fun clearQuickUrl() {
        _quickUrlInput.value = ""
        _quickProbeResult.value = null
    }

    fun probeQuickUrl() {
        val raw = _quickUrlInput.value.trim()
        if (raw.isEmpty()) {
            _snackbarChannel.trySend("请先粘贴商品或抢购链接")
            return
        }
        viewModelScope.launch {
            _isProbingQuickUrl.value = true
            try {
                val result = monitorController.probeUrl(rawUrl = raw)
                _quickProbeResult.value = result
            } finally {
                _isProbingQuickUrl.value = false
            }
        }
    }

    fun quickAddCurrentUrl() {
        val raw = _quickUrlInput.value.trim()
        if (raw.isEmpty()) {
            _snackbarChannel.trySend("请先粘贴商品链接")
            return
        }
        val resolved = MerchantLinkDetector.resolveOrderUrl(raw)
        if (!resolved.startsWith("http://", ignoreCase = true) &&
            !resolved.startsWith("https://", ignoreCase = true)
        ) {
            _snackbarChannel.trySend("请输入有效的 http:// 或 https:// 链接")
            return
        }

        val probe = _quickProbeResult.value?.takeIf { it.rawUrl.trim() == raw }
        val defaultInterval = repository.settingsFlow.value.defaultPollIntervalMinutes
        val inferredPanel = probe?.panelType ?: MerchantLinkDetector.inferPanelTypeFromUrl(resolved)
        val derivedTitle = probe?.detectedTitle?.takeIf { it.isNotBlank() }
            ?: deriveFallbackTitle(resolved)

        val draft = RestockWatchDraft(
            rawUrl = raw,
            title = derivedTitle,
            panelType = inferredPanel,
            priceHint = probe?.detectedPriceHint.orEmpty(),
            pollIntervalMinutes = defaultInterval,
            initialStockStatus = probe?.stockStatus ?: RestockStockStatus.UNKNOWN,
            initialEvidenceSummary = probe?.matchedSignal.orEmpty(),
        )

        viewModelScope.launch {
            val saved = saveWatchDraftInternal(draft)
            _quickUrlInput.value = ""
            _quickProbeResult.value = null
            _snackbarChannel.trySend("已加入补货蹲守：${saved.title}")
            if (probe == null) {
                monitorController.triggerCheckSingle(saved.id)
            }
        }
    }

    suspend fun probeUrl(
        rawUrl: String,
        customInStockKeyword: String = "",
        customOutOfStockKeyword: String = "",
    ): StockAnalysisResult = monitorController.probeUrl(
        rawUrl = rawUrl,
        customInStockKeyword = customInStockKeyword,
        customOutOfStockKeyword = customOutOfStockKeyword,
    )

    fun saveWatchDraft(draft: RestockWatchDraft) {
        viewModelScope.launch {
            val isEdit = !draft.editingId.isNullOrBlank()
            val saved = saveWatchDraftInternal(draft)
            _snackbarChannel.trySend(
                if (isEdit) "已更新监控：${saved.title}" else "已加入补货蹲守：${saved.title}",
            )
            if (saved.stockStatus == RestockStockStatus.UNKNOWN) {
                monitorController.triggerCheckSingle(saved.id)
            }
        }
    }

    private suspend fun saveWatchDraftInternal(draft: RestockWatchDraft): RestockWatchItem {
        val existing = draft.editingId?.let { id ->
            repository.getWatchItemsSnapshot().firstOrNull { it.id == id }
        }
        val resolvedUrl = MerchantLinkDetector.resolveOrderUrl(draft.rawUrl)
        val panelType = draft.panelType ?: MerchantLinkDetector.inferPanelTypeFromUrl(resolvedUrl)
        val title = draft.title.trim().ifBlank { deriveFallbackTitle(resolvedUrl) }

        val item = if (existing != null) {
            val now = System.currentTimeMillis()
            val urlChanged = !resolvedUrl.equals(existing.resolvedOrderUrl, ignoreCase = true)
            val keywordsChanged = draft.customInStockKeyword.trim() != existing.customInStockKeyword ||
                draft.customOutOfStockKeyword.trim() != existing.customOutOfStockKeyword
            val targetChanged = urlChanged || keywordsChanged
            val hasFreshProbe = draft.initialStockStatus != RestockStockStatus.UNKNOWN &&
                (draft.initialStockStatus != existing.stockStatus ||
                    draft.initialEvidenceSummary != existing.lastEvidenceSummary)
            val shouldResetState = targetChanged && !hasFreshProbe
            val nextStatus = when {
                shouldResetState -> RestockStockStatus.UNKNOWN
                draft.initialStockStatus != RestockStockStatus.UNKNOWN -> draft.initialStockStatus
                else -> existing.stockStatus
            }
            val nextArmed = when {
                shouldResetState -> false
                nextStatus == RestockStockStatus.OUT_OF_STOCK -> true
                nextStatus == RestockStockStatus.IN_STOCK -> false
                else -> existing.alertWaveArmed
            }
            existing.copy(
                title = title,
                rawUrl = draft.rawUrl.trim(),
                resolvedOrderUrl = resolvedUrl,
                panelType = panelType,
                priceHint = draft.priceHint.trim(),
                customInStockKeyword = draft.customInStockKeyword.trim(),
                customOutOfStockKeyword = draft.customOutOfStockKeyword.trim(),
                pollIntervalMinutes = RestockWatchItem.clampPollInterval(draft.pollIntervalMinutes),
                isEnabled = draft.isEnabled,
                stockStatus = nextStatus,
                lastCheckedEpochMillis = when {
                    shouldResetState -> 0L
                    hasFreshProbe -> now
                    else -> existing.lastCheckedEpochMillis
                },
                consecutiveErrors = if (shouldResetState || hasFreshProbe) 0 else existing.consecutiveErrors,
                lastErrorMessage = if (shouldResetState || hasFreshProbe) null else existing.lastErrorMessage,
                lastEvidenceSummary = when {
                    shouldResetState -> ""
                    draft.initialEvidenceSummary.isNotBlank() -> draft.initialEvidenceSummary
                    else -> existing.lastEvidenceSummary
                },
                sourceTopicId = draft.sourceTopicId ?: existing.sourceTopicId,
                alertWaveArmed = nextArmed,
            )
        } else {
            val now = System.currentTimeMillis()
            RestockWatchItem(
                id = UUID.randomUUID().toString(),
                title = title,
                rawUrl = draft.rawUrl.trim(),
                resolvedOrderUrl = resolvedUrl,
                panelType = panelType,
                priceHint = draft.priceHint.trim(),
                customInStockKeyword = draft.customInStockKeyword.trim(),
                customOutOfStockKeyword = draft.customOutOfStockKeyword.trim(),
                pollIntervalMinutes = RestockWatchItem.clampPollInterval(draft.pollIntervalMinutes),
                isEnabled = draft.isEnabled,
                stockStatus = draft.initialStockStatus,
                lastCheckedEpochMillis = if (draft.initialStockStatus != RestockStockStatus.UNKNOWN) now else 0L,
                lastEvidenceSummary = draft.initialEvidenceSummary,
                sourceTopicId = draft.sourceTopicId,
                createdAtEpochMillis = now,
                alertWaveArmed = draft.initialStockStatus == RestockStockStatus.OUT_OF_STOCK,
            )
        }
        return repository.addOrUpdateWatch(item)
    }

    fun removeWatch(id: String) {
        viewModelScope.launch {
            repository.removeWatch(id)
            _snackbarChannel.trySend("已移除该监控项")
        }
    }

    fun setWatchEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.setWatchEnabled(id, enabled)
        }
    }

    fun updateWatchPollInterval(id: String, intervalMinutes: Int) {
        viewModelScope.launch {
            repository.updateWatchPollInterval(id, intervalMinutes)
            _snackbarChannel.trySend("已调整巡检频率为每 ${RestockWatchItem.clampPollInterval(intervalMinutes)} 分钟")
        }
    }

    fun checkSingleWatch(id: String) {
        viewModelScope.launch {
            val result = monitorController.checkWatchNow(id)
            if (result != null) {
                val summary = when (result.stockStatus) {
                    RestockStockStatus.IN_STOCK -> "检测完成：有货可抢！"
                    RestockStockStatus.OUT_OF_STOCK -> "检测完成：当前仍缺货"
                    RestockStockStatus.CHECK_ERROR -> "检测异常：${result.errorMessage ?: result.matchedSignal}"
                    RestockStockStatus.UNKNOWN -> "检测完成：状态未明，建议配置关键词"
                }
                _snackbarChannel.trySend(summary)
            }
        }
    }

    fun checkAllNow() {
        viewModelScope.launch {
            val items = repository.getWatchItemsSnapshot().filter { it.isEnabled }
            if (items.isEmpty()) {
                _snackbarChannel.trySend("暂无已启用的监控项")
                return@launch
            }
            val results = monitorController.checkAllNow(onlyEnabled = true)
            val inStock = results.count { it.stockStatus == RestockStockStatus.IN_STOCK }
            val errors = results.count { it.stockStatus == RestockStockStatus.CHECK_ERROR }
            val msg = buildString {
                append("已完成 ${results.size} 项巡检")
                if (inStock > 0) append(" · $inStock 项有货可抢")
                if (errors > 0) append(" · $errors 项异常")
            }
            _snackbarChannel.trySend(msg)
        }
    }

    fun markEventRead(eventId: String) {
        viewModelScope.launch {
            repository.markEventRead(eventId)
        }
    }

    fun markAllEventsRead() {
        viewModelScope.launch {
            repository.markAllEventsRead()
            _snackbarChannel.trySend("已将所有补货记录标为已读")
        }
    }

    fun deleteEvent(eventId: String) {
        viewModelScope.launch {
            repository.deleteEvent(eventId)
        }
    }

    fun clearEventHistory() {
        viewModelScope.launch {
            repository.clearEventHistory()
            _snackbarChannel.trySend("已清空补货提醒历史")
        }
    }

    fun setMonitorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(isMonitorEnabled = enabled) }
        }
    }

    fun setDefaultPollInterval(minutes: Int) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(defaultPollIntervalMinutes = RestockWatchItem.clampPollInterval(minutes))
            }
        }
    }

    fun setBackgroundInterval(minutes: Int) {
        viewModelScope.launch {
            repository.updateSettings {
                it.copy(
                    backgroundIntervalMinutes = minutes.coerceIn(
                        RestockMonitorSettings.MIN_BACKGROUND_INTERVAL_MINUTES,
                        RestockMonitorSettings.MAX_BACKGROUND_INTERVAL_MINUTES,
                    ),
                )
            }
        }
    }

    fun setWifiOnlyInBackground(wifiOnly: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(wifiOnlyInBackground = wifiOnly) }
        }
    }

    fun setRequireBatteryNotLow(require: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(requireBatteryNotLowInBackground = require) }
        }
    }

    fun setUrgentNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(urgentNotificationsEnabled = enabled) }
        }
    }

    fun setFlareSolverrEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(flareSolverr = it.flareSolverr.copy(enabled = enabled)) }
        }
    }

    fun setFlareSolverrBaseUrl(baseUrl: String) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(flareSolverr = it.flareSolverr.copy(baseUrl = baseUrl.trim())) }
        }
    }

    fun setFlareSolverrMaxTimeout(timeoutMillis: Int) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(flareSolverr = it.flareSolverr.copy(maxTimeoutMillis = timeoutMillis)) }
        }
    }

    fun testFlareSolverrConnection(draftBaseUrl: String? = null) {
        if (_isTestingFlareSolverr.value) return
        viewModelScope.launch {
            val config = if (draftBaseUrl != null) {
                repository.updateSettings {
                    it.copy(flareSolverr = it.flareSolverr.copy(baseUrl = draftBaseUrl.trim()))
                }.flareSolverr
            } else {
                repository.getSettingsSnapshot().flareSolverr
            }
            if (config.baseUrl.isBlank()) {
                _snackbarChannel.trySend("请先填写 FlareSolverr 服务地址")
                return@launch
            }
            _isTestingFlareSolverr.value = true
            try {
                val reachable = flareSolverrClient.ping(config)
                _snackbarChannel.trySend(
                    if (reachable) {
                        "FlareSolverr 连接成功，CF 盾自动突破已就绪"
                    } else {
                        "FlareSolverr 连接失败，请检查服务地址与运行状态"
                    },
                )
            } finally {
                _isTestingFlareSolverr.value = false
            }
        }
    }

    private fun deriveFallbackTitle(url: String): String {
        val host = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.").orEmpty()
        return if (host.isNotBlank()) "$host VPS" else "VPS 商品监控"
    }

    companion object {
        fun factory(
            repository: RestockRepository,
            monitorController: RestockMonitorController,
            notificationHelper: RestockNotificationHelper,
            flareSolverrClient: FlareSolverrClient,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return RestockMonitorViewModel(
                    repository = repository,
                    monitorController = monitorController,
                    notificationHelper = notificationHelper,
                    flareSolverrClient = flareSolverrClient,
                ) as T
            }
        }
    }
}
