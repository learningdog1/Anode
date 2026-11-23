package com.nodeseek.app.domain.fleet

import com.nodeseek.app.data.valuation.FxRatesState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * Domain repository managing multi-panel VPS probes, server asset ledger, monitor settings,
 * and deduplicated background alert evaluation.
 */
interface FleetRepository {
    /**
     * Whether persisted state from disk has finished loading into the reactive flows.
     *
     * Disk-backed implementations start at `false` on cold start and flip to `true` only after
     * persisted records have been loaded and published, preventing background schedulers from
     * mistaking pre-load empty flows for "zero monitoring targets".
     */
    val isPersistedStateLoadedFlow: StateFlow<Boolean>
        get() = DEFAULT_PERSISTED_STATE_LOADED_FLOW

    val panelsFlow: StateFlow<List<ProbePanelConfig>>
    val snapshotsFlow: StateFlow<List<ProbeServerSnapshot>>
    val assetsFlow: StateFlow<List<VpsAssetRecord>>
    val cpuHistoryByProbeKeyFlow: StateFlow<Map<String, List<ProbeDailyCpuBucket>>>
        get() = DEFAULT_CPU_HISTORY_FLOW
    val monitorSettingsFlow: StateFlow<FleetMonitorSettings>
    val lastSyncEpochMillisFlow: StateFlow<Long>
    val isRefreshingFlow: StateFlow<Boolean>
    val panelErrorsFlow: StateFlow<Map<String, String>>

    suspend fun loadPersistedState()

    suspend fun upsertPanel(config: ProbePanelConfig): ProbePanelConfig
    suspend fun deletePanel(panelId: String)
    suspend fun testPanelConnection(config: ProbePanelConfig): ProbeConnectivityResult
    suspend fun refreshAllPanels(): FleetRefreshSummary
    suspend fun refreshAllPanels(silent: Boolean): FleetRefreshSummary = refreshAllPanels()
    suspend fun refreshPanel(panelId: String): Result<List<ProbeServerSnapshot>>

    suspend fun upsertAsset(asset: VpsAssetRecord): VpsAssetRecord
    suspend fun deleteAsset(assetId: String)
    suspend fun linkAssetToProbe(assetId: String, probeKey: String?)

    suspend fun updateMonitorSettings(settings: FleetMonitorSettings)

    /**
     * Evaluates probe offline/recovery transitions and renewal reminders against the latest
     * snapshots & assets, persisting updated deduplication markers.
     */
    suspend fun evaluateAlerts(
        today: LocalDate,
        nowEpochMillis: Long = System.currentTimeMillis(),
        fxRatesState: FxRatesState? = null,
        checkedPanels: Int? = null,
        failedPanels: Int = 0,
    ): FleetMonitorCheckReport

    companion object {
        private val DEFAULT_PERSISTED_STATE_LOADED_FLOW: StateFlow<Boolean> =
            MutableStateFlow(true).asStateFlow()
        private val DEFAULT_CPU_HISTORY_FLOW: StateFlow<Map<String, List<ProbeDailyCpuBucket>>> =
            MutableStateFlow(emptyMap<String, List<ProbeDailyCpuBucket>>()).asStateFlow()
    }
}
