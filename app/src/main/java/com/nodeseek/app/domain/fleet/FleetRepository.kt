package com.nodeseek.app.domain.fleet

import com.nodeseek.app.data.valuation.FxRatesState
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/**
 * Domain repository managing multi-panel VPS probes, server asset ledger, monitor settings,
 * and deduplicated background alert evaluation.
 */
interface FleetRepository {
    val panelsFlow: StateFlow<List<ProbePanelConfig>>
    val snapshotsFlow: StateFlow<List<ProbeServerSnapshot>>
    val assetsFlow: StateFlow<List<VpsAssetRecord>>
    val monitorSettingsFlow: StateFlow<FleetMonitorSettings>
    val lastSyncEpochMillisFlow: StateFlow<Long>
    val isRefreshingFlow: StateFlow<Boolean>
    val panelErrorsFlow: StateFlow<Map<String, String>>

    suspend fun loadPersistedState()

    suspend fun upsertPanel(config: ProbePanelConfig): ProbePanelConfig
    suspend fun deletePanel(panelId: String)
    suspend fun testPanelConnection(config: ProbePanelConfig): ProbeConnectivityResult
    suspend fun refreshAllPanels(): FleetRefreshSummary
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
}
