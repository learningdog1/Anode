package com.nodeseek.app.ui.ssh

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nodeseek.app.NodePulseApp
import com.nodeseek.app.data.ssh.JSchSshTransport
import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.ssh.CustomSshPreset
import com.nodeseek.app.domain.ssh.HostKeyInfo
import com.nodeseek.app.domain.ssh.IpPrivacyMasker
import com.nodeseek.app.domain.ssh.Iperf3MissingProbeResult
import com.nodeseek.app.domain.ssh.Iperf3OwnedInstance
import com.nodeseek.app.domain.ssh.Iperf3RemoteServerCommands
import com.nodeseek.app.domain.ssh.Iperf3ServiceOwnership
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.domain.ssh.NodeQualityReportFormatter
import com.nodeseek.app.domain.ssh.SshConnectionMode
import com.nodeseek.app.domain.ssh.SshMergeLayout
import com.nodeseek.app.domain.ssh.SanitizedBufferSnapshot
import com.nodeseek.app.domain.ssh.SshBenchmarkPostFormatter
import com.nodeseek.app.domain.ssh.SshExecutionRecord
import com.nodeseek.app.domain.ssh.SshFormattedPost
import com.nodeseek.app.domain.ssh.SshLiveProgressStatus
import com.nodeseek.app.domain.ssh.SshPresetCatalog
import com.nodeseek.app.domain.ssh.SshPresetCategory
import com.nodeseek.app.domain.ssh.SshPresetFilter
import com.nodeseek.app.domain.ssh.SshPresetFilterOption
import com.nodeseek.app.domain.ssh.SshPtySize
import com.nodeseek.app.domain.ssh.SshRunStatus
import com.nodeseek.app.domain.ssh.SshScriptPreset
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshServerGroupOption
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.domain.ssh.SshVaultCatalogFilter
import com.nodeseek.app.domain.ssh.SshVaultCollapseState
import com.nodeseek.app.domain.ssh.SshVaultRepository
import com.nodeseek.app.domain.ssh.SshVaultServerGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Current execution lifecycle phase surfaced in the ANSI console header.
 */
enum class SshConnectionPhase(val label: String) {
    IDLE("待命"),
    CONNECTING("连接中"),
    RUNNING("运行中"),
    COMPLETED("已完成"),
    FAILED("执行失败"),
    CANCELLED("已停止"),
}

/**
 * Security warning state presented when a known server's SHA-256 host key changes.
 */
@Immutable
data class PendingHostKeyMismatchPrompt(
    val serverId: String,
    val serverAlias: String,
    val preset: SshScriptPreset,
    val command: String,
    val expectedFingerprint: String,
    val actualHostKey: HostKeyInfo,
    val mismatchServerId: String = serverId,
    /** Port of the isolated iperf3 service utility run that hit the mismatch (null for benchmark runs). */
    val iperf3Port: Int? = null,
    val iperf3AutoOpenFirewall: Boolean = false,
)

/**
 * Confirmation prompt presented when `iperf3` is not yet installed on the target VPS.
 */
@Immutable
data class PendingIperf3InstallPrompt(
    val serverId: String,
    val serverAlias: String,
    val port: Int,
    val autoOpenFirewall: Boolean,
    val probe: Iperf3MissingProbeResult,
)

/**
 * Unique `(serverId, port)` key identifying an attached `iperf3` service endpoint.
 */
@Immutable
data class Iperf3EndpointKey(
    val serverId: String,
    val port: Int,
)

/**
 * Tracks whether a `(serverId, port)` `iperf3` endpoint was started by this app (with verified
 * [ownedInstance] `pid` + `startTime` + `port`) or is an existing external service reused locally.
 */
@Immutable
data class Iperf3ServerAttachment(
    val serverId: String,
    val serverAlias: String,
    val port: Int,
    val ownership: Iperf3ServiceOwnership,
    val ownedInstance: Iperf3OwnedInstance? = null,
) {
    val isAppStarted: Boolean
        get() = ownership == Iperf3ServiceOwnership.APP_STARTED && ownedInstance != null

    val isExternalReused: Boolean
        get() = ownership == Iperf3ServiceOwnership.EXTERNAL_REUSED
}

@Immutable
fun interface LiveBufferAccessor {
    fun currentSnapshot(): SanitizedBufferSnapshot
}

@Immutable
data class LiteSshUiState(
    val servers: List<SshServerProfile> = emptyList(),
    val selectedServerId: String? = null,
    val builtInPresets: List<SshScriptPreset> = SshPresetCatalog.builtInPresets,
    val customPresets: List<CustomSshPreset> = emptyList(),
    val selectedPresetId: String = SshPresetCatalog.PRESET_QUICK_CHECK,
    val enabledFlagIdsByPreset: Map<String, Set<String>> = SshPresetCatalog.builtInPresets
        .associate { it.id to it.defaultEnabledFlagIds },
    val isRunning: Boolean = false,
    val connectionPhase: SshConnectionPhase = SshConnectionPhase.IDLE,
    val activeServerId: String? = null,
    val activeServerAlias: String? = null,
    val activeServerHost: String? = null,
    val activePresetId: String? = null,
    val activePresetName: String? = null,
    val activeTabTitle: String? = null,
    val activeCommand: String = "",
    val liveAnsiOutput: String = "",
    val hasLiveOutputFlag: Boolean = false,
    internal val liveBufferAccessor: LiveBufferAccessor? = null,
    val startedAtEpochMillis: Long = 0L,
    val lastOutputEpochMillis: Long? = null,
    val elapsedMillis: Long = 0L,
    val liveProgress: SshLiveProgressStatus = SshLiveProgressStatus(),
    val autoRespondPrompts: Boolean = true,
    val autoRespondCount: Int = 0,
    val lastAutoRespondedPrompt: String? = null,
    val exitCode: Int? = null,
    val errorMessage: String? = null,
    val sessionRuns: List<SshExecutionRecord> = emptyList(),
    val selectedRunIdsForExport: Set<String> = emptySet(),
    val viewingRunId: String? = null,
    val recentRuns: List<SshExecutionRecord> = emptyList(),
    val maskServerIp: Boolean = true,
    val consoleFontScale: Float = DEFAULT_CONSOLE_FONT_SCALE,
    val terminalAutoFitEnabled: Boolean = true,
    val autoScrollConsole: Boolean = true,
    val pendingHostKeyMismatch: PendingHostKeyMismatchPrompt? = null,
    val showServerEditor: Boolean = false,
    val editingServer: SshServerProfile? = null,
    val showCustomPresetEditor: Boolean = false,
    val editingCustomPreset: CustomSshPreset? = null,
    val showExportPreviewSheet: Boolean = false,
    val mergeLayout: SshMergeLayout = SshMergeLayout.TABS,
    val showHistorySheet: Boolean = false,
    val statusMessage: String? = null,
    val showIperf3ServerDialog: Boolean = false,
    val iperf3ServerPortText: String = Iperf3RemoteServerCommands.DEFAULT_PORT.toString(),
    val iperf3AutoOpenFirewall: Boolean = false,
    val pendingIperf3InstallPrompt: PendingIperf3InstallPrompt? = null,
    val iperf3Attachments: Map<Iperf3EndpointKey, Iperf3ServerAttachment> = emptyMap(),
    /**
     * True while an isolated iperf3 server start/install/stop utility command is in flight. This is
     * independent from [isRunning] so iperf3 can be toggled while a benchmark script runs.
     */
    val isIperf3ServiceBusy: Boolean = false,
    val serverSearchQuery: String = "",
    val selectedServerGroupKey: String = SshServerGroupOption.KEY_ALL,
    val isServerVaultExpanded: Boolean = false,
    val showServerQuickPickerSheet: Boolean = false,
    val presetSearchQuery: String = "",
    val selectedPresetFilter: SshPresetFilter = SshPresetFilter.ALL,
    val isPresetCatalogExpanded: Boolean = false,
    val showPresetQuickPickerSheet: Boolean = false,
    val collapseState: SshVaultCollapseState = SshVaultCollapseState(),
    val isInteractiveSession: Boolean = false,
    val activeConnectionMode: SshConnectionMode? = null,
    val isSyncingProbes: Boolean = false,
    val probePanels: List<ProbePanelConfig> = emptyList(),
    val lastProbeSyncSummary: String? = null,
    val vaultLoadError: String? = null,
) {
    val configuredProbePanelCount: Int
        get() = probePanels.count { it.enabled }

    val vaultServerGroups: List<SshVaultServerGroup>
        get() = SshVaultCatalogFilter.groupServersForCollapsibleVault(
            filteredServers = filteredServers,
            selectedServerId = selectedServer?.id,
        )

    val allPresets: List<SshScriptPreset>
        get() = builtInPresets + customPresets.map { it.toScriptPreset() }

    val selectedServer: SshServerProfile?
        get() = selectedServerId?.let { id -> servers.firstOrNull { it.id == id } }
            ?: servers.firstOrNull()

    fun iperf3AttachmentFor(
        serverId: String? = selectedServer?.id,
        port: Int? = Iperf3RemoteServerCommands.validatePort(iperf3ServerPortText),
    ): Iperf3ServerAttachment? {
        if (serverId.isNullOrBlank() || port == null) return null
        return iperf3Attachments[Iperf3EndpointKey(serverId = serverId, port = port)]
    }

    val serverGroupOptions: List<SshServerGroupOption>
        get() = SshVaultCatalogFilter.buildServerGroupOptions(servers)

    val effectiveServerGroupKey: String
        get() = SshVaultCatalogFilter.resolveEffectiveServerGroupKey(
            selectedGroupKey = selectedServerGroupKey,
            options = serverGroupOptions,
        )

    val filteredServers: List<SshServerProfile>
        get() = SshVaultCatalogFilter.filterAndSortServers(
            servers = servers,
            selectedServerId = selectedServer?.id,
            searchQuery = serverSearchQuery,
            selectedGroupKey = effectiveServerGroupKey,
            pinSelectedFirst = true,
        )

    val inlineVisibleServers: List<SshServerProfile>
        get() = SshVaultCatalogFilter.visibleServersForInlineSection(
            filteredServers = filteredServers,
            expanded = isServerVaultExpanded,
            searchQuery = serverSearchQuery,
        )

    val selectedPreset: SshScriptPreset
        get() = allPresets.firstOrNull { it.id == selectedPresetId }
            ?: builtInPresets.first()

    val presetFilterOptions: List<SshPresetFilterOption>
        get() = SshVaultCatalogFilter.buildPresetFilterOptions(allPresets)

    val filteredPresets: List<SshScriptPreset>
        get() = SshVaultCatalogFilter.filterAndSortPresets(
            presets = allPresets,
            selectedPresetId = selectedPreset.id,
            searchQuery = presetSearchQuery,
            filter = selectedPresetFilter,
            pinSelectedFirst = true,
        )

    val inlineVisiblePresets: List<SshScriptPreset>
        get() = SshVaultCatalogFilter.visiblePresetsForInlineSection(
            filteredPresets = filteredPresets,
            expanded = isPresetCatalogExpanded,
            searchQuery = presetSearchQuery,
        )

    val selectedPresetFlagIds: Set<String>
        get() = enabledFlagIdsByPreset[selectedPreset.id] ?: selectedPreset.defaultEnabledFlagIds

    val resolvedSelectedCommand: String
        get() = selectedPreset.buildCommand(selectedPresetFlagIds)

    val viewingRun: SshExecutionRecord?
        get() = viewingRunId?.let { id ->
            sessionRuns.firstOrNull { it.id == id } ?: recentRuns.firstOrNull { it.id == id }
        }

    val hasLiveOutput: Boolean
        get() = hasLiveOutputFlag ||
            liveAnsiOutput.isNotBlank() ||
            (liveBufferAccessor?.currentSnapshot()?.isEmpty == false)

    val resolvedLiveAnsiOutput: String
        get() = if (liveAnsiOutput.isNotEmpty()) {
            liveAnsiOutput
        } else {
            liveBufferAccessor?.currentSnapshot()?.takeIf { !it.isEmpty }?.fullAnsiText.orEmpty()
        }

    /**
     * Raw sanitized ANSI output currently displayed in the console (either live stream or a
     * selected completed session/history run).
     */
    val rawConsoleOutput: String
        get() = viewingRun?.sanitizedAnsiOutput ?: resolvedLiveAnsiOutput

    /**
     * Display-ready ANSI output respecting the [maskServerIp] privacy toggle.
     */
    val displayedConsoleOutput: String
        get() {
            val raw = rawConsoleOutput
            if (raw.isEmpty()) return ""
            val hostToMask = viewingRun?.serverHost ?: activeServerHost ?: selectedServer?.host
            return if (maskServerIp) {
                IpPrivacyMasker.mask(raw, knownServerHost = hostToMask)
            } else {
                raw
            }
        }

    /**
     * O(1) summary metadata for `LiteSshExportDock` avoiding per-frame regex masking & post formatting.
     */
    fun computeExportSummaryMeta(): SshExportSummaryMeta? {
        var checkedCount = 0
        var singleCheckedIsNq = false
        for (run in sessionRuns) {
            if (run.id in selectedRunIdsForExport && run.hasOutput) {
                checkedCount++
                if (checkedCount == 1) {
                    singleCheckedIsNq = run.presetId == SshPresetCatalog.PRESET_NODE_QUALITY ||
                        run.command.contains("nodequality", ignoreCase = true)
                }
            }
        }
        if (checkedCount > 0) {
            val usesTabs = if (checkedCount >= 2) {
                mergeLayout == SshMergeLayout.TABS
            } else {
                singleCheckedIsNq
            }
            return SshExportSummaryMeta(runCount = checkedCount, usesTabsLayout = usesTabs)
        }
        val viewed = viewingRun
        if (viewed?.hasOutput == true) {
            val isNq = viewed.presetId == SshPresetCatalog.PRESET_NODE_QUALITY ||
                viewed.command.contains("nodequality", ignoreCase = true)
            return SshExportSummaryMeta(runCount = 1, usesTabsLayout = isNq)
        }
        if (hasLiveOutput) {
            val isNq = (activePresetId ?: selectedPreset.id) == SshPresetCatalog.PRESET_NODE_QUALITY ||
                activeCommand.contains("nodequality", ignoreCase = true)
            return SshExportSummaryMeta(runCount = 1, usesTabsLayout = isNq)
        }
        return null
    }

    /**
     * All candidate runs available for multi-run merging in the Preview & Publish sheet
     * (`sessionRuns` first, followed by same-server and recent persisted runs with output).
     */
    val mergeCandidateRuns: List<SshExecutionRecord>
        get() {
            val seenIds = HashSet<String>(sessionRuns.size + recentRuns.size)
            val result = ArrayList<SshExecutionRecord>(sessionRuns.size + recentRuns.size)
            for (run in sessionRuns) {
                if (run.hasOutput && seenIds.add(run.id)) {
                    result.add(run)
                }
            }
            val preferredServerId = viewingRun?.serverId ?: selectedServer?.id
            val preferredHost = viewingRun?.serverHost ?: activeServerHost ?: selectedServer?.host
            for (run in recentRuns) {
                if (run.hasOutput &&
                    ((!preferredServerId.isNullOrBlank() && run.serverId == preferredServerId) ||
                        (!preferredHost.isNullOrBlank() && run.serverHost.equals(preferredHost, ignoreCase = true))) &&
                    seenIds.add(run.id)
                ) {
                    result.add(run)
                }
            }
            for (run in recentRuns) {
                if (run.hasOutput && seenIds.add(run.id)) {
                    result.add(run)
                }
            }
            return result
        }

    fun toGlobalBarState(): LiteSshGlobalBarState = if (!isRunning) {
        LiteSshGlobalBarState(isRunning = false)
    } else {
        LiteSshGlobalBarState(
            isRunning = true,
            serverAlias = activeServerAlias ?: selectedServer?.displayName ?: "VPS",
            presetName = activePresetName ?: selectedPreset.name,
            currentStageLabel = liveProgress.currentStageLabel,
            silentRunningBannerText = liveProgress.silentRunningBannerText,
            isSilentLong = liveProgress.isSilentLong,
            elapsedSeconds = (elapsedMillis / 1_000L).coerceAtLeast(0L),
        )
    }

    /**
     * Completed runs currently included for 1-click NodeSeek export.
     */
    val exportableRuns: List<SshExecutionRecord>
        get() {
            val checked = sessionRuns.filter { it.id in selectedRunIdsForExport && it.hasOutput }
            if (checked.isNotEmpty()) return checked
            val viewed = viewingRun?.takeIf { it.hasOutput }
            if (viewed != null) return listOf(viewed)
            val liveText = resolvedLiveAnsiOutput
            if (liveText.isNotBlank()) {
                return listOf(
                    SshExecutionRecord(
                        id = "live_snapshot",
                        serverId = activeServerId ?: selectedServer?.id.orEmpty(),
                        serverAlias = activeServerAlias ?: selectedServer?.displayName ?: "VPS",
                        serverHost = activeServerHost ?: selectedServer?.host.orEmpty(),
                        presetId = activePresetId ?: selectedPreset.id,
                        presetName = activePresetName ?: selectedPreset.name,
                        tabTitle = activeTabTitle ?: selectedPreset.tabTitle,
                        command = activeCommand.ifBlank { resolvedSelectedCommand },
                        sanitizedAnsiOutput = liveText,
                        exitCode = exitCode,
                        status = if (isRunning) SshRunStatus.RUNNING else SshRunStatus.SUCCEEDED,
                        startedAtEpochMillis = startedAtEpochMillis,
                        durationMillis = elapsedMillis,
                    ),
                )
            }
            return emptyList()
        }

    val exportPreviewPost: SshFormattedPost?
        get() = exportableRuns
            .takeIf { it.isNotEmpty() }
            ?.let { runs ->
                SshBenchmarkPostFormatter.format(
                    runs = runs,
                    maskIp = maskServerIp,
                    mergeLayout = mergeLayout,
                )
            }

    companion object {
        const val MIN_CONSOLE_FONT_SCALE: Float = 0.50f
        const val MAX_CONSOLE_FONT_SCALE: Float = 1.80f
        const val DEFAULT_CONSOLE_FONT_SCALE: Float = 1.00f
    }
}

/**
 * ViewModel powering `LiteSshScreen`.
 *
 * Delegates session state and SSH execution to the application-scoped [LiteSshSessionManager] so
 * leaving `LiteSshScreen` (and clearing this ViewModel) never terminates an in-progress benchmark.
 */
class LiteSshViewModel(
    val sessionManager: LiteSshSessionManager,
    private val ownsSessionManagerScope: Boolean = false,
) : ViewModel() {

    constructor(
        vaultRepository: SshVaultRepository,
        executor: LiteSshExecutor,
        clock: () -> Long = { System.currentTimeMillis() },
        idGenerator: () -> String = { UUID.randomUUID().toString() },
        enableElapsedTicker: Boolean = true,
    ) : this(
        sessionManager = LiteSshSessionManager(
            vaultRepository = vaultRepository,
            executor = executor,
            externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            foregroundServiceController = NoOpSshForegroundServiceController,
            clock = clock,
            idGenerator = idGenerator,
            enableElapsedTicker = enableElapsedTicker,
        ),
        ownsSessionManagerScope = true,
    )

    val uiState: StateFlow<LiteSshUiState>
        get() = sessionManager.uiState

    val consoleStreamState: StateFlow<SshConsoleStreamState>
        get() = sessionManager.consoleStreamState

    val consoleStreamHandle: SshConsoleStreamHandle
        get() = sessionManager.consoleStreamHandle

    fun selectServer(serverId: String) = sessionManager.selectServer(serverId)
    fun updateServerSearchQuery(query: String) = sessionManager.updateServerSearchQuery(query)
    fun clearServerSearchQuery() = sessionManager.clearServerSearchQuery()
    fun selectServerGroup(groupKey: String) = sessionManager.selectServerGroup(groupKey)
    fun setServerVaultExpanded(expanded: Boolean) = sessionManager.setServerVaultExpanded(expanded)
    fun toggleServerVaultExpanded() = sessionManager.toggleServerVaultExpanded()
    fun toggleVaultListCollapsed() = sessionManager.toggleVaultListCollapsed()
    fun toggleVaultGroupCollapsed(groupKey: String) = sessionManager.toggleVaultGroupCollapsed(groupKey)
    fun setAllVaultGroupsCollapsed(collapsed: Boolean) = sessionManager.setAllVaultGroupsCollapsed(collapsed)
    fun toggleVaultServerExpanded(serverId: String) = sessionManager.toggleVaultServerExpanded(serverId)
    fun syncFromProbePanels() = sessionManager.syncFromProbePanels()
    fun setServerConnectionMode(serverId: String, mode: SshConnectionMode) =
        sessionManager.setServerConnectionMode(serverId, mode)
    fun openInteractiveTerminal(serverId: String? = null, modeOverride: SshConnectionMode? = null) =
        sessionManager.openInteractiveTerminal(serverId = serverId, modeOverride = modeOverride)
    fun sendTerminalInput(text: String, appendNewline: Boolean = false) =
        sessionManager.sendTerminalInput(text = text, appendNewline = appendNewline)
    fun openServerQuickPickerSheet() = sessionManager.openServerQuickPickerSheet()
    fun dismissServerQuickPickerSheet() = sessionManager.dismissServerQuickPickerSheet()

    fun openAddServerDialog() = sessionManager.openAddServerDialog()
    fun openEditServerDialog(profile: SshServerProfile) = sessionManager.openEditServerDialog(profile)
    fun dismissServerEditor() = sessionManager.dismissServerEditor()
    fun saveServer(draft: SshServerDraft) = sessionManager.saveServer(draft)
    fun deleteServer(serverId: String) = sessionManager.deleteServer(serverId)
    fun retryLoadVault() = sessionManager.retryLoadVault()
    fun resetCorruptedVault() = sessionManager.resetCorruptedVault()

    fun selectPreset(presetId: String) = sessionManager.selectPreset(presetId)
    fun updatePresetSearchQuery(query: String) = sessionManager.updatePresetSearchQuery(query)
    fun clearPresetSearchQuery() = sessionManager.clearPresetSearchQuery()
    fun selectPresetFilter(filter: SshPresetFilter) = sessionManager.selectPresetFilter(filter)
    fun setPresetCatalogExpanded(expanded: Boolean) = sessionManager.setPresetCatalogExpanded(expanded)
    fun togglePresetCatalogExpanded() = sessionManager.togglePresetCatalogExpanded()
    fun openPresetQuickPickerSheet() = sessionManager.openPresetQuickPickerSheet()
    fun dismissPresetQuickPickerSheet() = sessionManager.dismissPresetQuickPickerSheet()

    fun togglePresetFlag(presetId: String, flagId: String) = sessionManager.togglePresetFlag(presetId, flagId)
    fun toggleAutoRespondPrompts(enabled: Boolean) = sessionManager.toggleAutoRespondPrompts(enabled)

    fun openAddCustomPresetDialog() = sessionManager.openAddCustomPresetDialog()
    fun openEditCustomPresetDialog(preset: CustomSshPreset) = sessionManager.openEditCustomPresetDialog(preset)
    fun dismissCustomPresetEditor() = sessionManager.dismissCustomPresetEditor()
    fun saveCustomPreset(
        id: String?,
        name: String,
        tabTitle: String,
        description: String,
        command: String,
        category: SshPresetCategory = SshPresetCategory.CUSTOM,
    ) = sessionManager.saveCustomPreset(id, name, tabTitle, description, command, category)
    fun deleteCustomPreset(presetId: String) = sessionManager.deleteCustomPreset(presetId)

    fun runSelectedPreset() = sessionManager.runSelectedPreset()
    fun confirmTrustChangedHostKey() = sessionManager.confirmTrustChangedHostKey()
    fun dismissHostKeyMismatch() = sessionManager.dismissHostKeyMismatch()
    fun cancelExecution() = sessionManager.cancelExecution()

    fun toggleMaskServerIp(enabled: Boolean) = sessionManager.toggleMaskServerIp(enabled)
    fun adjustConsoleFontScale(delta: Float, autoFitStartScale: Float? = null) =
        sessionManager.adjustConsoleFontScale(delta, autoFitStartScale)
    fun pinchConsoleFontScale(zoomFactor: Float, autoFitStartScale: Float? = null) =
        sessionManager.pinchConsoleFontScale(zoomFactor, autoFitStartScale)
    fun setTerminalAutoFitEnabled(enabled: Boolean) = sessionManager.setTerminalAutoFitEnabled(enabled)
    fun resetConsoleFontScale() = sessionManager.resetConsoleFontScale()
    fun updateTerminalPtySize(ptySize: SshPtySize) = sessionManager.updateTerminalPtySize(ptySize)
    fun toggleAutoScrollConsole(enabled: Boolean) = sessionManager.toggleAutoScrollConsole(enabled)

    fun viewRun(runId: String?) = sessionManager.viewRun(runId)
    fun toggleRunForExport(runId: String) = sessionManager.toggleRunForExport(runId)
    fun toggleCandidateRunForExport(record: SshExecutionRecord) = sessionManager.toggleCandidateRunForExport(record)
    fun setSelectedRunsForExport(records: List<SshExecutionRecord>) = sessionManager.setSelectedRunsForExport(records)
    fun removeSessionRun(runId: String) = sessionManager.removeSessionRun(runId)
    fun clearSessionRuns() = sessionManager.clearSessionRuns()

    fun addHistoryRunToSession(record: SshExecutionRecord) = sessionManager.addHistoryRunToSession(record)
    fun addHistoryServerGroupToSession(records: List<SshExecutionRecord>) =
        sessionManager.addHistoryServerGroupToSession(records)
    fun deleteHistoryRun(runId: String) = sessionManager.deleteHistoryRun(runId)

    fun openExportPreviewSheet() = sessionManager.openExportPreviewSheet()
    fun dismissExportPreviewSheet() = sessionManager.dismissExportPreviewSheet()
    fun setMergeLayout(layout: SshMergeLayout) = sessionManager.setMergeLayout(layout)
    fun openHistorySheet() = sessionManager.openHistorySheet()
    fun dismissHistorySheet() = sessionManager.dismissHistorySheet()
    fun buildExportPayload(): SshFormattedPost? = sessionManager.buildExportPayload()
    suspend fun buildExportPayloadAsync(): SshFormattedPost? = sessionManager.buildExportPayloadAsync()
    fun consumeStatusMessage() = sessionManager.consumeStatusMessage()

    fun openIperf3ServerDialog(preferredHost: String? = null, preferredPort: Int? = null) =
        sessionManager.openIperf3ServerDialog(preferredHost = preferredHost, preferredPort = preferredPort)
    fun dismissIperf3ServerDialog() = sessionManager.dismissIperf3ServerDialog()
    fun updateIperf3ServerPortText(portText: String) = sessionManager.updateIperf3ServerPortText(portText)
    fun toggleIperf3AutoOpenFirewall(enabled: Boolean) = sessionManager.toggleIperf3AutoOpenFirewall(enabled)
    fun startIperf3Server(portOverride: Int? = null, autoOpenFirewallOverride: Boolean? = null) =
        sessionManager.startIperf3Server(portOverride = portOverride, autoOpenFirewallOverride = autoOpenFirewallOverride)
    fun stopIperf3Server(portOverride: Int? = null) = sessionManager.stopIperf3Server(portOverride = portOverride)
    fun disconnectExternalIperf3Server(portOverride: Int? = null) =
        sessionManager.disconnectExternalIperf3Server(portOverride = portOverride)
    fun confirmInstallAndStartIperf3Server() = sessionManager.confirmInstallAndStartIperf3Server()
    fun dismissIperf3InstallPrompt() = sessionManager.dismissIperf3InstallPrompt()

    override fun onCleared() {
        // Only cancel scope when constructed via standalone test convenience constructor;
        // production AppContainer sessionManager continues running in background!
        if (ownsSessionManagerScope) {
            sessionManager.disposeOwnedScopeForTests()
        }
        super.onCleared()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext ?: context
            val container = (appContext as? NodePulseApp)?.container
            val sharedManager = container?.sshSessionManager
            if (sharedManager != null) {
                sharedManager.attachFleetIntegration(fleetRepository = container.fleetRepository)
                return factory(sessionManager = sharedManager)
            }
            val vault = SshServerVault.getDefault(appContext)
            return factory(
                vaultRepository = vault,
                executor = LiteSshExecutor(
                    transport = JSchSshTransport(vaultRepository = vault),
                    processingDispatcher = Dispatchers.Default,
                ),
            )
        }

        fun factory(
            sessionManager: LiteSshSessionManager,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return LiteSshViewModel(
                    sessionManager = sessionManager,
                    ownsSessionManagerScope = false,
                ) as T
            }
        }

        fun factory(
            vaultRepository: SshVaultRepository,
            executor: LiteSshExecutor,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return LiteSshViewModel(
                    vaultRepository = vaultRepository,
                    executor = executor,
                ) as T
            }
        }
    }
}
