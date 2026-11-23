package com.nodeseek.app.ui.ssh

import androidx.compose.runtime.Stable
import com.nodeseek.app.data.fleet.ProbePanelTerminalTransport
import com.nodeseek.app.data.fleet.ProbeVaultSyncClient
import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.radar.InAppMessageKind
import com.nodeseek.app.domain.radar.InAppMessagePopup
import com.nodeseek.app.domain.ssh.CustomSshPreset
import com.nodeseek.app.domain.ssh.HostKeyVerificationResult
import com.nodeseek.app.domain.ssh.Iperf3RemoteServerCommands
import com.nodeseek.app.domain.ssh.Iperf3ServiceOwnership
import com.nodeseek.app.domain.ssh.Iperf3StartProbeResult
import com.nodeseek.app.domain.ssh.Iperf3StopOutcome
import com.nodeseek.app.domain.ssh.LiteSshExecutionEvent
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.domain.ssh.SanitizedBufferSnapshot
import com.nodeseek.app.domain.ssh.SshAutoRespondConfig
import com.nodeseek.app.domain.ssh.SshConnectionMode
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshExecutionRecord
import com.nodeseek.app.domain.ssh.SshFormattedPost
import com.nodeseek.app.domain.ssh.SshMergeLayout
import com.nodeseek.app.domain.ssh.SshHostKeyMismatchException
import com.nodeseek.app.domain.ssh.SshJumpHostConnectionException
import com.nodeseek.app.domain.ssh.SshJumpHostCycleException
import com.nodeseek.app.domain.ssh.SshJumpHostResolver
import com.nodeseek.app.domain.ssh.SshLiveProgressStatus
import com.nodeseek.app.domain.ssh.SshPresetCatalog
import com.nodeseek.app.domain.ssh.SshPresetCategory
import com.nodeseek.app.domain.ssh.SshPresetFilter
import com.nodeseek.app.domain.ssh.SshPtyResizeCallbackContext
import com.nodeseek.app.domain.ssh.SshPtyResizer
import com.nodeseek.app.domain.ssh.SshPtySize
import com.nodeseek.app.domain.ssh.SshRunStatus
import com.nodeseek.app.domain.ssh.SshScriptPreset
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshServerGroupOption
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.domain.ssh.SshStageDetector
import com.nodeseek.app.domain.ssh.SshStdinCallbackContext
import com.nodeseek.app.domain.ssh.SshStdinWriter
import com.nodeseek.app.domain.ssh.SshVaultCatalogFilter
import com.nodeseek.app.domain.ssh.SshVaultRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Application-scoped SSH benchmark session manager decoupled from screen and ViewModel lifecycles.
 *
 * Guarantees:
 * - Navigating away from `LiteSshScreen` never cancels or interrupts an active benchmark run.
 * - Coordinates [SshForegroundServiceController] (`dataSync` foreground service + ongoing progress
 *   notification) while a benchmark is running.
 * - Tracks live elapsed time, last output timestamp, inferred script stage, and long-silence
 *   reassurance hints via [SshStageDetector].
 * - Emits completion notifications to both [PulseNotificationDispatcher] (via [onBenchmarkCompleted])
 *   and the in-app popup overlay ([completionPopupEvents]) when a benchmark finishes.
 */
@Stable
class LiteSshSessionManager(
    private val vaultRepository: SshVaultRepository,
    private val executor: LiteSshExecutor,
    private val externalScope: CoroutineScope,
    private val foregroundServiceController: SshForegroundServiceController = NoOpSshForegroundServiceController,
    private val onBenchmarkCompleted: ((record: SshExecutionRecord, errorDetail: String?) -> Unit)? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val enableElapsedTicker: Boolean = true,
    private val silenceThresholdMillis: Long = SshStageDetector.DEFAULT_SILENCE_THRESHOLD_MILLIS,
    fleetRepository: FleetRepository? = null,
    private val probeSyncClientOverride: ProbeVaultSyncClient? = null,
    private val panelTerminalExecutorOverride: LiteSshExecutor? = null,
) {
    private val _consoleStreamState = MutableStateFlow(SshConsoleStreamState())
    val consoleStreamState: StateFlow<SshConsoleStreamState> = _consoleStreamState.asStateFlow()
    val consoleStreamHandle: SshConsoleStreamHandle = SshConsoleStreamHandle(consoleStreamState)

    private val _globalBarState = MutableStateFlow(LiteSshGlobalBarState())
    val globalBarState: StateFlow<LiteSshGlobalBarState> = _globalBarState.asStateFlow()

    private val liveBufferAccessor = LiveBufferAccessor { _consoleStreamState.value.bufferSnapshot }

    private val _uiState = MutableStateFlow(
        LiteSshUiState(
            liveBufferAccessor = liveBufferAccessor,
            collapseState = vaultRepository.collapseStateFlow.value,
            vaultLoadError = vaultRepository.vaultLoadErrorFlow.value,
        ),
    )
    val uiState: StateFlow<LiteSshUiState> = _uiState.asStateFlow()

    private val _completionPopupEvents = MutableSharedFlow<InAppMessagePopup>(extraBufferCapacity = 4)
    val completionPopupEvents: SharedFlow<InAppMessagePopup> = _completionPopupEvents.asSharedFlow()

    private val fleetAttachLock = Any()
    @Volatile
    private var attachedFleetRepository: FleetRepository? = null
    private var fleetPanelsObserveJob: Job? = null
    private var probeSyncJob: Job? = null
    @Volatile
    private var defaultProbeSyncClient: ProbeVaultSyncClient? = null
    @Volatile
    private var defaultPanelTerminalExecutor: LiteSshExecutor? = null

    private var executionJob: Job? = null
    private var iperf3ServiceJob: Job? = null
    private var iperf3ServiceGeneration: Long = 0L
    private var elapsedTickerJob: Job? = null
    private var lastForegroundNotifyMillis: Long = 0L
    private var lastForegroundStatusLine: String = ""
    private var runGenerationCounter: Long = 0L
    @Volatile
    private var latestOutputTimestampMillis: Long? = null
    @Volatile
    private var desiredPtySize: SshPtySize? = null
    @Volatile
    private var activePtyResizer: SshPtyResizer? = null
    @Volatile
    private var activePtyResizerGeneration: Long = 0L
    @Volatile
    private var activeStdinWriter: SshStdinWriter? = null
    @Volatile
    private var activeStdinWriterGeneration: Long = 0L

    init {
        externalScope.launch {
            vaultRepository.serversFlow.collect { servers ->
                _uiState.update { state ->
                    val resolvedSelection = state.selectedServerId
                        ?.takeIf { id -> servers.any { it.id == id } }
                        ?: servers.firstOrNull()?.id
                    state.copy(
                        servers = servers,
                        selectedServerId = resolvedSelection,
                    )
                }
            }
        }
        externalScope.launch {
            vaultRepository.customPresetsFlow.collect { presets ->
                _uiState.update { state ->
                    val allIds = state.builtInPresets.map { it.id }.toSet() + presets.map { it.id }
                    val validSelectedPreset = if (state.selectedPresetId in allIds) {
                        state.selectedPresetId
                    } else {
                        SshPresetCatalog.PRESET_QUICK_CHECK
                    }
                    state.copy(
                        customPresets = presets,
                        selectedPresetId = validSelectedPreset,
                    )
                }
            }
        }
        externalScope.launch {
            vaultRepository.recentRunsFlow.collect { runs ->
                _uiState.update { it.copy(recentRuns = runs) }
            }
        }
        externalScope.launch {
            vaultRepository.collapseStateFlow.collect { collapse ->
                _uiState.update { it.copy(collapseState = collapse) }
            }
        }
        externalScope.launch {
            vaultRepository.vaultLoadErrorFlow.collect { err ->
                _uiState.update { it.copy(vaultLoadError = err) }
            }
        }
        externalScope.launch {
            runCatching { vaultRepository.ensureLoaded() }
        }
        if (fleetRepository != null) {
            attachFleetIntegration(fleetRepository)
        }
    }

    /**
     * Binds [fleetRepository] to this session manager (idempotently) to enable probe panel sync
     * into the SSH Host Vault and panel WebSocket terminal execution (Nezha v1 / Komari).
     */
    fun attachFleetIntegration(fleetRepository: FleetRepository) {
        synchronized(fleetAttachLock) {
            if (attachedFleetRepository === fleetRepository) return
            attachedFleetRepository = fleetRepository
            defaultProbeSyncClient = probeSyncClientOverride ?: ProbeVaultSyncClient()
            defaultPanelTerminalExecutor = panelTerminalExecutorOverride ?: LiteSshExecutor(
                transport = ProbePanelTerminalTransport(fleetRepository = fleetRepository),
                processingDispatcher = Dispatchers.Default,
            )
            fleetPanelsObserveJob?.cancel()
            fleetPanelsObserveJob = externalScope.launch {
                loadFleetPersistedStateBestEffort(fleetRepository)
                fleetRepository.panelsFlow.collect { panels ->
                    _uiState.update { it.copy(probePanels = panels) }
                }
            }
        }
    }

    private suspend fun loadFleetPersistedStateBestEffort(fleetRepository: FleetRepository) {
        if (fleetRepository.isPersistedStateLoadedFlow.value) return
        try {
            fleetRepository.loadPersistedState()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Best-effort cold load; proceed with currently loaded fleet panels.
        }
    }

    fun selectServer(serverId: String) {
        _uiState.update { it.copy(selectedServerId = serverId) }
    }

    fun updateServerSearchQuery(query: String) {
        _uiState.update { it.copy(serverSearchQuery = query) }
    }

    fun clearServerSearchQuery() {
        _uiState.update { it.copy(serverSearchQuery = "") }
    }

    fun selectServerGroup(groupKey: String) {
        _uiState.update { it.copy(selectedServerGroupKey = groupKey) }
    }

    fun setServerVaultExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isServerVaultExpanded = expanded) }
    }

    fun toggleServerVaultExpanded() {
        _uiState.update { it.copy(isServerVaultExpanded = !it.isServerVaultExpanded) }
    }

    fun toggleVaultListCollapsed() {
        _uiState.update { it.copy(collapseState = it.collapseState.toggleVaultListCollapsed()) }
        externalScope.launch {
            vaultRepository.updateCollapseState { it.toggleVaultListCollapsed() }
        }
    }

    fun toggleVaultGroupCollapsed(groupKey: String) {
        _uiState.update { it.copy(collapseState = it.collapseState.toggleGroupCollapsed(groupKey)) }
        externalScope.launch {
            vaultRepository.updateCollapseState { it.toggleGroupCollapsed(groupKey) }
        }
    }

    fun setAllVaultGroupsCollapsed(collapsed: Boolean) {
        val keys = _uiState.value.vaultServerGroups.map { it.key }
        if (keys.isEmpty()) return
        _uiState.update {
            it.copy(collapseState = it.collapseState.setAllGroupsCollapsed(keys, collapsed))
        }
        externalScope.launch {
            vaultRepository.updateCollapseState { it.setAllGroupsCollapsed(keys, collapsed) }
        }
    }

    fun toggleVaultServerExpanded(serverId: String) {
        _uiState.update { it.copy(collapseState = it.collapseState.toggleServerExpanded(serverId)) }
        externalScope.launch {
            vaultRepository.updateCollapseState { it.toggleServerExpanded(serverId) }
        }
    }

    fun setServerConnectionMode(serverId: String, mode: SshConnectionMode) {
        externalScope.launch {
            runCatching {
                vaultRepository.updateServerConnectionMode(serverId, mode)
            }.onSuccess {
                _uiState.update { state ->
                    val server = state.servers.firstOrNull { it.id == serverId }
                    val label = server?.displayName ?: "主机"
                    state.copy(statusMessage = "已将「$label」切换为${mode.label}模式")
                }
            }.onFailure { err ->
                _uiState.update { it.copy(statusMessage = err.message ?: "切换连接模式失败") }
            }
        }
    }

    /**
     * Pulls machines from all enabled Nezha / Komari probe panels in [FleetRepository] and merges
     * them into the SSH Host Vault with deduplication and strict credential preservation.
     */
    fun syncFromProbePanels() {
        if (probeSyncJob?.isActive == true) return
        val fleetRepo = attachedFleetRepository
        if (fleetRepo == null) {
            _uiState.update { it.copy(statusMessage = "探针模块未就绪，请先在「我的服务器」配置探针面板") }
            return
        }
        probeSyncJob = externalScope.launch {
            _uiState.update { it.copy(isSyncingProbes = true) }
            try {
                loadFleetPersistedStateBestEffort(fleetRepo)
                val panels = fleetRepo.panelsFlow.value.filter { it.enabled }
                if (panels.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isSyncingProbes = false,
                            statusMessage = "尚未配置探针面板，请先前往「我的服务器」添加哪吒或 Komari 面板",
                        )
                    }
                    return@launch
                }
                val syncClient = probeSyncClientOverride ?: defaultProbeSyncClient ?: ProbeVaultSyncClient()
                val fetched = syncClient.fetchAllPanels(
                    panels = panels,
                    fallbackSnapshots = fleetRepo.snapshotsFlow.value,
                )
                if (fetched.machines.isEmpty()) {
                    val firstErr = fetched.failedPanelErrors.values.firstOrNull()
                    val msg = if (firstErr != null) {
                        "探针同步失败：$firstErr"
                    } else {
                        "已连接 ${fetched.succeededPanelCount} 个探针面板，未发现可同步的服务器节点"
                    }
                    _uiState.update {
                        it.copy(
                            isSyncingProbes = false,
                            lastProbeSyncSummary = msg,
                            statusMessage = msg,
                        )
                    }
                    return@launch
                }

                val outcome = vaultRepository.syncProbeMachines(fetched.machines)
                val summary = buildString {
                    append("探针同步完成（共 ${outcome.totalSyncedMachines} 台）：")
                    val parts = buildList {
                        if (outcome.addedCount > 0) add("新增 ${outcome.addedCount} 台")
                        if (outcome.linkedManualCount > 0) add("关联已有 ${outcome.linkedManualCount} 台")
                        if (outcome.updatedCount > 0) add("更新 ${outcome.updatedCount} 台")
                        if (outcome.unchangedCount > 0 && outcome.addedCount == 0 && outcome.updatedCount == 0 && outcome.linkedManualCount == 0) {
                            add("全部已是最新")
                        }
                    }
                    append(parts.joinToString("，"))
                    if (fetched.failedPanelErrors.isNotEmpty()) {
                        append("（${fetched.failedPanelErrors.size} 个面板拉取异常）")
                    }
                }
                _uiState.update {
                    it.copy(
                        isSyncingProbes = false,
                        lastProbeSyncSummary = summary,
                        statusMessage = summary,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val msg = "探针同步失败：${t.message?.takeIf { it.isNotBlank() } ?: "网络异常"}"
                _uiState.update {
                    it.copy(
                        isSyncingProbes = false,
                        statusMessage = msg,
                    )
                }
            }
        }
    }

    /**
     * Opens an interactive PTY terminal session on [serverId] (or the currently selected server)
     * using either Panel Terminal (Nezha v1 / Komari WebSocket) or Direct SSH (`ChannelShell`).
     */
    fun openInteractiveTerminal(
        serverId: String? = null,
        modeOverride: SshConnectionMode? = null,
    ) {
        val state = _uiState.value
        val server = serverId?.let { id -> state.servers.firstOrNull { it.id == id } }
            ?: state.selectedServer
        if (server == null) {
            _uiState.update { it.copy(showServerEditor = true, statusMessage = "请先添加或同步一台 VPS 主机") }
            return
        }
        if (state.selectedServerId != server.id) {
            _uiState.update { it.copy(selectedServerId = server.id) }
        }
        val effectiveMode = modeOverride ?: server.effectiveConnectionMode
        if (effectiveMode == SshConnectionMode.DIRECT_SSH && !server.canDirectSsh) {
            _uiState.update {
                it.copy(
                    showServerEditor = true,
                    editingServer = server,
                    statusMessage = "请先为「${server.displayName}」补充 IP 地址和 SSH 密码/私钥，或切换为「探针终端」",
                )
            }
            return
        }
        if (effectiveMode == SshConnectionMode.PANEL_TERMINAL && !server.isProbeSynced) {
            _uiState.update {
                it.copy(statusMessage = "「${server.displayName}」未关联探针面板，请使用「直连 SSH」模式")
            }
            return
        }
        val interactivePreset = SshScriptPreset(
            id = PRESET_ID_INTERACTIVE_TERMINAL,
            name = if (effectiveMode == SshConnectionMode.PANEL_TERMINAL) "探针交互终端" else "SSH 交互终端",
            tabTitle = "交互终端",
            category = SshPresetCategory.DIAGNOSTIC,
            description = "实时交互式 PTY 终端会话",
            estimatedDurationLabel = "实时",
            commandTemplate = "",
        )
        executePresetOnServer(
            server = server,
            preset = interactivePreset,
            command = "",
            allowHostKeyMismatchOverride = false,
            connectionModeOverride = effectiveMode,
            interactiveShell = true,
        )
    }

    /**
     * Sends raw text or control sequences (`Ctrl+C`, `Tab`, `Esc`, arrows) to the active PTY stdin.
     */
    fun sendTerminalInput(text: String, appendNewline: Boolean = false) {
        val writer = activeStdinWriter ?: return
        val payload = if (appendNewline) "$text\r" else text
        if (payload.isEmpty()) return
        externalScope.launch(Dispatchers.IO) {
            runCatching { writer.write(payload) }
        }
    }

    fun openServerQuickPickerSheet() {
        _uiState.update { it.copy(showServerQuickPickerSheet = true) }
    }

    fun dismissServerQuickPickerSheet() {
        _uiState.update { it.copy(showServerQuickPickerSheet = false) }
    }

    fun openAddServerDialog() {
        _uiState.update { it.copy(showServerEditor = true, editingServer = null) }
    }

    fun openEditServerDialog(profile: SshServerProfile) {
        _uiState.update { it.copy(showServerEditor = true, editingServer = profile) }
    }

    fun dismissServerEditor() {
        _uiState.update { it.copy(showServerEditor = false, editingServer = null) }
    }

    fun saveServer(draft: SshServerDraft) {
        externalScope.launch {
            runCatching {
                val normalizedDraft = SshVaultCatalogFilter.normalizeDraftForPersistence(draft)
                vaultRepository.saveServer(normalizedDraft)
            }.onSuccess { saved ->
                _uiState.update {
                    it.copy(
                        selectedServerId = saved.id,
                        showServerEditor = false,
                        editingServer = null,
                        statusMessage = "已加密保存主机「${saved.displayName}」",
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(statusMessage = err.message ?: "保存主机失败")
                }
            }
        }
    }

    fun deleteServer(serverId: String) {
        externalScope.launch {
            runCatching {
                vaultRepository.deleteServer(serverId)
            }.onSuccess {
                _uiState.update { state ->
                    state.copy(
                        iperf3Attachments = state.iperf3Attachments.filterKeys { it.serverId != serverId },
                        statusMessage = "已从本地保险库删除主机",
                    )
                }
            }.onFailure { err ->
                _uiState.update { it.copy(statusMessage = err.message ?: "删除主机失败") }
            }
        }
    }

    fun retryLoadVault() {
        externalScope.launch {
            runCatching {
                vaultRepository.retryLoadVault()
            }.onSuccess { loaded ->
                _uiState.update {
                    it.copy(
                        statusMessage = if (loaded) {
                            "已成功重新加载 SSH 保险库"
                        } else {
                            "SSH 保险库文件仍无法解析，原文件已保留备份"
                        },
                    )
                }
            }.onFailure { err ->
                _uiState.update { it.copy(statusMessage = err.message ?: "重新加载保险库失败") }
            }
        }
    }

    fun resetCorruptedVault() {
        externalScope.launch {
            runCatching {
                vaultRepository.resetCorruptedVault()
            }.onSuccess {
                _uiState.update {
                    it.copy(statusMessage = "已备份损坏文件并重置 SSH 保险库，现在可以重新添加主机")
                }
            }.onFailure { err ->
                _uiState.update { it.copy(statusMessage = err.message ?: "重置保险库失败") }
            }
        }
    }

    fun selectPreset(presetId: String) {
        _uiState.update { state ->
            state.copy(selectedPresetId = presetId)
        }
    }

    fun updatePresetSearchQuery(query: String) {
        _uiState.update { it.copy(presetSearchQuery = query) }
    }

    fun clearPresetSearchQuery() {
        _uiState.update { it.copy(presetSearchQuery = "") }
    }

    fun selectPresetFilter(filter: SshPresetFilter) {
        _uiState.update { it.copy(selectedPresetFilter = filter) }
    }

    fun setPresetCatalogExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isPresetCatalogExpanded = expanded) }
    }

    fun togglePresetCatalogExpanded() {
        _uiState.update { it.copy(isPresetCatalogExpanded = !it.isPresetCatalogExpanded) }
    }

    fun openPresetQuickPickerSheet() {
        _uiState.update { it.copy(showPresetQuickPickerSheet = true) }
    }

    fun dismissPresetQuickPickerSheet() {
        _uiState.update { it.copy(showPresetQuickPickerSheet = false) }
    }

    fun togglePresetFlag(presetId: String, flagId: String) {
        _uiState.update { state ->
            val preset = state.allPresets.firstOrNull { it.id == presetId } ?: return@update state
            val targetFlag = preset.flags.firstOrNull { it.id == flagId } ?: return@update state
            val currentFlags = state.enabledFlagIdsByPreset[presetId] ?: preset.defaultEnabledFlagIds
            val updatedFlags = currentFlags.toMutableSet()

            if (flagId in updatedFlags) {
                updatedFlags.remove(flagId)
            } else {
                val exclusiveGroup = targetFlag.exclusiveGroup
                if (exclusiveGroup != null) {
                    preset.flags
                        .filter { it.exclusiveGroup == exclusiveGroup }
                        .forEach { updatedFlags.remove(it.id) }
                }
                updatedFlags.add(flagId)
            }

            state.copy(
                enabledFlagIdsByPreset = state.enabledFlagIdsByPreset + (presetId to updatedFlags),
            )
        }
    }

    fun toggleAutoRespondPrompts(enabled: Boolean) {
        _uiState.update { it.copy(autoRespondPrompts = enabled) }
    }

    fun openAddCustomPresetDialog() {
        _uiState.update { it.copy(showCustomPresetEditor = true, editingCustomPreset = null) }
    }

    fun openEditCustomPresetDialog(preset: CustomSshPreset) {
        _uiState.update { it.copy(showCustomPresetEditor = true, editingCustomPreset = preset) }
    }

    fun dismissCustomPresetEditor() {
        _uiState.update { it.copy(showCustomPresetEditor = false, editingCustomPreset = null) }
    }

    fun saveCustomPreset(
        id: String?,
        name: String,
        tabTitle: String,
        description: String,
        command: String,
        category: SshPresetCategory = SshPresetCategory.CUSTOM,
    ) {
        externalScope.launch {
            runCatching {
                vaultRepository.saveCustomPreset(
                    CustomSshPreset(
                        id = id.orEmpty(),
                        name = name,
                        tabTitle = tabTitle,
                        description = description,
                        command = command,
                        category = category,
                    ),
                )
            }.onSuccess { saved ->
                _uiState.update {
                    it.copy(
                        selectedPresetId = saved.id,
                        showCustomPresetEditor = false,
                        editingCustomPreset = null,
                        statusMessage = "已保存自定义脚本「${saved.name}」",
                    )
                }
            }.onFailure { err ->
                _uiState.update { it.copy(statusMessage = err.message ?: "保存自定义脚本失败") }
            }
        }
    }

    fun deleteCustomPreset(presetId: String) {
        externalScope.launch {
            runCatching {
                vaultRepository.deleteCustomPreset(presetId)
            }.onSuccess {
                _uiState.update { it.copy(statusMessage = "已删除自定义脚本") }
            }.onFailure { err ->
                _uiState.update { it.copy(statusMessage = err.message ?: "删除自定义脚本失败") }
            }
        }
    }

    fun runSelectedPreset() {
        val state = _uiState.value
        val server = state.selectedServer
        if (server == null) {
            _uiState.update { it.copy(showServerEditor = true, statusMessage = "请先添加一台 VPS 主机") }
            return
        }
        val effectiveMode = server.effectiveConnectionMode
        if (effectiveMode == SshConnectionMode.DIRECT_SSH && server.isProbeSynced && !server.canDirectSsh) {
            _uiState.update {
                it.copy(
                    showServerEditor = true,
                    editingServer = server,
                    statusMessage = "「${server.displayName}」尚未填写直连 SSH 凭据，请补充密码/私钥或切换为「探针终端」模式",
                )
            }
            return
        }
        val preset = state.selectedPreset
        val command = state.resolvedSelectedCommand
        executePresetOnServer(
            server = server,
            preset = preset,
            command = command,
            allowHostKeyMismatchOverride = false,
            connectionModeOverride = effectiveMode,
            interactiveShell = false,
        )
    }

    fun confirmTrustChangedHostKey() {
        val prompt = _uiState.value.pendingHostKeyMismatch ?: return
        val server = _uiState.value.servers.firstOrNull { it.id == prompt.serverId }
        _uiState.update { it.copy(pendingHostKeyMismatch = null) }
        if (server == null) return

        externalScope.launch {
            vaultRepository.updateHostKeyFingerprint(
                serverId = prompt.mismatchServerId,
                fingerprintSha256 = prompt.actualHostKey.fingerprintSha256,
                algorithm = prompt.actualHostKey.algorithm,
            )
            val refreshed = _uiState.value.servers.firstOrNull { it.id == server.id }
                ?: if (prompt.mismatchServerId == server.id) {
                    server.copy(
                        knownHostKeyFingerprint = prompt.actualHostKey.fingerprintSha256,
                        knownHostKeyAlgorithm = prompt.actualHostKey.algorithm,
                    )
                } else {
                    server
                }
            if (prompt.preset.id in IPERF3_SERVICE_PRESET_IDS) {
                executeIperf3ServiceCommand(
                    server = refreshed,
                    preset = prompt.preset,
                    command = prompt.command,
                    port = prompt.iperf3Port
                        ?: Iperf3RemoteServerCommands.validatePort(_uiState.value.iperf3ServerPortText)
                        ?: Iperf3RemoteServerCommands.DEFAULT_PORT,
                    autoOpenFirewall = prompt.iperf3AutoOpenFirewall,
                    allowHostKeyMismatchOverride = true,
                    mismatchOverrideServerId = prompt.mismatchServerId,
                )
            } else {
                executePresetOnServer(
                    server = refreshed,
                    preset = prompt.preset,
                    command = prompt.command,
                    allowHostKeyMismatchOverride = true,
                    mismatchOverrideServerId = prompt.mismatchServerId,
                    connectionModeOverride = SshConnectionMode.DIRECT_SSH,
                    interactiveShell = prompt.preset.id == PRESET_ID_INTERACTIVE_TERMINAL,
                )
            }
        }
    }

    fun dismissHostKeyMismatch() {
        val prompt = _uiState.value.pendingHostKeyMismatch
        if (prompt != null && prompt.preset.id in IPERF3_SERVICE_PRESET_IDS) {
            // iperf3 service utility runs are isolated from the benchmark console state.
            _uiState.update {
                it.copy(
                    pendingHostKeyMismatch = null,
                    statusMessage = "已因主机密钥变更取消 iperf3 服务端操作",
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                pendingHostKeyMismatch = null,
                connectionPhase = SshConnectionPhase.CANCELLED,
                errorMessage = "已因主机密钥变更取消连接",
            )
        }
    }

    fun cancelExecution() {
        val activeJob = executionJob ?: return
        val state = _uiState.value
        executionJob = null
        activePtyResizer = null
        activeStdinWriter = null
        elapsedTickerJob?.cancel()
        elapsedTickerJob = null
        activeJob.cancel()
        foregroundServiceController.onSessionFinished()

        val partialOutput = state.resolvedLiveAnsiOutput
        _uiState.update {
            it.copy(
                isRunning = false,
                isInteractiveSession = false,
                connectionPhase = SshConnectionPhase.CANCELLED,
                liveAnsiOutput = partialOutput,
                hasLiveOutputFlag = partialOutput.isNotBlank(),
                statusMessage = "已断开终端会话",
            )
        }
        syncGlobalBarState()

        if (partialOutput.isNotBlank() && !state.isInteractiveSession) {
            val server = state.selectedServer
            val cancelledRecord = SshExecutionRecord(
                id = idGenerator(),
                serverId = server?.id.orEmpty(),
                serverAlias = state.activeServerAlias ?: server?.displayName ?: "VPS",
                serverHost = state.activeServerHost ?: server?.host.orEmpty(),
                presetId = state.activePresetId ?: state.selectedPreset.id,
                presetName = state.activePresetName ?: state.selectedPreset.name,
                tabTitle = state.selectedPreset.tabTitle,
                command = state.activeCommand,
                sanitizedAnsiOutput = partialOutput,
                exitCode = null,
                status = SshRunStatus.CANCELLED,
                startedAtEpochMillis = state.startedAtEpochMillis.takeIf { it > 0L }
                    ?: (clock() - state.elapsedMillis).coerceAtLeast(0L),
                durationMillis = state.elapsedMillis,
            )
            appendCompletedSessionRun(cancelledRecord, autoSelect = false)
        }
    }

    /**
     * Recomputes elapsed duration, output silence duration, and stage hints against [nowMillis].
     */
    fun refreshLiveProgress(nowMillis: Long = clock()) {
        val currentSnapshot = _consoleStreamState.value.bufferSnapshot
        _uiState.update { state ->
            if (!state.isRunning) return@update state
            val elapsed = if (state.startedAtEpochMillis > 0L) {
                (nowMillis - state.startedAtEpochMillis).coerceAtLeast(0L)
            } else {
                state.elapsedMillis
            }
            val effectiveLastOutput = latestOutputTimestampMillis ?: state.lastOutputEpochMillis
            val progress = SshStageDetector.detectFromBuffer(
                presetId = state.activePresetId ?: state.selectedPresetId,
                command = state.activeCommand,
                bufferSnapshot = currentSnapshot,
                fallbackOutput = state.liveAnsiOutput,
                isConnecting = state.connectionPhase == SshConnectionPhase.CONNECTING,
                startedAtEpochMillis = state.startedAtEpochMillis,
                lastOutputEpochMillis = effectiveLastOutput,
                nowEpochMillis = nowMillis,
                silenceThresholdMillis = silenceThresholdMillis,
            )
            state.copy(
                elapsedMillis = elapsed,
                lastOutputEpochMillis = effectiveLastOutput,
                liveProgress = progress,
            )
        }
        syncGlobalBarState()
        syncForegroundNotificationIfNeeded(nowMillis)
    }

    fun toggleMaskServerIp(enabled: Boolean) {
        _uiState.update { it.copy(maskServerIp = enabled) }
    }

    fun adjustConsoleFontScale(
        delta: Float,
        autoFitStartScale: Float? = null,
    ) {
        _uiState.update { state ->
            val baseScale = if (state.terminalAutoFitEnabled && autoFitStartScale != null) {
                autoFitStartScale.coerceIn(
                    LiteSshUiState.MIN_CONSOLE_FONT_SCALE,
                    LiteSshUiState.MAX_CONSOLE_FONT_SCALE,
                )
            } else {
                state.consoleFontScale
            }
            val next = (baseScale + delta).coerceIn(
                LiteSshUiState.MIN_CONSOLE_FONT_SCALE,
                LiteSshUiState.MAX_CONSOLE_FONT_SCALE,
            )
            state.copy(
                terminalAutoFitEnabled = false,
                consoleFontScale = next,
            )
        }
    }

    fun pinchConsoleFontScale(
        zoomFactor: Float,
        autoFitStartScale: Float? = null,
    ) {
        if (!zoomFactor.isFinite() || zoomFactor <= 0f) return
        _uiState.update { state ->
            val baseScale = if (state.terminalAutoFitEnabled && autoFitStartScale != null) {
                autoFitStartScale.coerceIn(
                    LiteSshUiState.MIN_CONSOLE_FONT_SCALE,
                    LiteSshUiState.MAX_CONSOLE_FONT_SCALE,
                )
            } else {
                state.consoleFontScale
            }
            val next = (baseScale * zoomFactor).coerceIn(
                LiteSshUiState.MIN_CONSOLE_FONT_SCALE,
                LiteSshUiState.MAX_CONSOLE_FONT_SCALE,
            )
            state.copy(
                terminalAutoFitEnabled = false,
                consoleFontScale = next,
            )
        }
    }

    fun setTerminalAutoFitEnabled(enabled: Boolean) {
        _uiState.update { it.copy(terminalAutoFitEnabled = enabled) }
    }

    fun resetConsoleFontScale() {
        _uiState.update {
            it.copy(
                terminalAutoFitEnabled = true,
                consoleFontScale = LiteSshUiState.DEFAULT_CONSOLE_FONT_SCALE,
            )
        }
    }

    fun updateTerminalPtySize(ptySize: SshPtySize) {
        val normalized = ptySize.normalized()
        if (desiredPtySize == normalized) return
        desiredPtySize = normalized
        activePtyResizer?.resize(normalized)
    }

    fun toggleAutoScrollConsole(enabled: Boolean) {
        _uiState.update { it.copy(autoScrollConsole = enabled) }
    }

    fun viewRun(runId: String?) {
        _uiState.update { it.copy(viewingRunId = runId) }
    }

    fun toggleRunForExport(runId: String) {
        _uiState.update { state ->
            val next = if (runId in state.selectedRunIdsForExport) {
                state.selectedRunIdsForExport - runId
            } else {
                state.selectedRunIdsForExport + runId
            }
            state.copy(selectedRunIdsForExport = next)
        }
    }

    fun toggleCandidateRunForExport(record: SshExecutionRecord) {
        _uiState.update { state ->
            val inSession = state.sessionRuns.any { it.id == record.id }
            val nextSession = if (inSession) state.sessionRuns else state.sessionRuns + record
            val nextSelected = if (record.id in state.selectedRunIdsForExport) {
                state.selectedRunIdsForExport - record.id
            } else {
                state.selectedRunIdsForExport + record.id
            }
            state.copy(
                sessionRuns = nextSession,
                selectedRunIdsForExport = nextSelected,
            )
        }
    }

    fun setSelectedRunsForExport(records: List<SshExecutionRecord>) {
        _uiState.update { state ->
            val existingIds = state.sessionRuns.map { it.id }.toSet()
            val chronologicalNew = records
                .filter { it.hasOutput && it.id !in existingIds }
                .sortedBy { it.startedAtEpochMillis }
            val nextSession = state.sessionRuns + chronologicalNew
            val nextSelected = records.filter { it.hasOutput }.map { it.id }.toSet()
            state.copy(
                sessionRuns = nextSession,
                selectedRunIdsForExport = nextSelected,
            )
        }
    }

    fun removeSessionRun(runId: String) {
        _uiState.update { state ->
            val nextRuns = state.sessionRuns.filterNot { it.id == runId }
            val nextViewing = if (state.viewingRunId == runId) nextRuns.lastOrNull()?.id else state.viewingRunId
            state.copy(
                sessionRuns = nextRuns,
                selectedRunIdsForExport = state.selectedRunIdsForExport - runId,
                viewingRunId = nextViewing,
            )
        }
    }

    fun clearSessionRuns() {
        val stillRunning = _uiState.value.isRunning
        if (!stillRunning) {
            _consoleStreamState.value = SshConsoleStreamState()
        }
        _uiState.update {
            it.copy(
                sessionRuns = emptyList(),
                selectedRunIdsForExport = emptySet(),
                viewingRunId = null,
                liveAnsiOutput = if (it.isRunning) it.liveAnsiOutput else "",
                hasLiveOutputFlag = if (it.isRunning) it.hasLiveOutputFlag else false,
                connectionPhase = if (it.isRunning) it.connectionPhase else SshConnectionPhase.IDLE,
                exitCode = if (it.isRunning) it.exitCode else null,
                errorMessage = null,
            )
        }
    }

    fun addHistoryRunToSession(record: SshExecutionRecord) {
        _uiState.update { state ->
            val alreadyInSession = state.sessionRuns.any { it.id == record.id }
            val nextSession = if (alreadyInSession) state.sessionRuns else state.sessionRuns + record
            state.copy(
                sessionRuns = nextSession,
                selectedRunIdsForExport = state.selectedRunIdsForExport + record.id,
                viewingRunId = record.id,
                showHistorySheet = false,
                statusMessage = "已载入历史记录「${record.presetName}」",
            )
        }
    }

    fun addHistoryServerGroupToSession(records: List<SshExecutionRecord>) {
        if (records.isEmpty()) return
        _uiState.update { state ->
            val existingIds = state.sessionRuns.map { it.id }.toSet()
            val chronological = records.sortedBy { it.startedAtEpochMillis }
            val newEntries = chronological.filterNot { it.id in existingIds }
            val nextSession = state.sessionRuns + newEntries
            val addedIds = chronological.map { it.id }.toSet()
            val serverLabel = records.first().serverAlias.ifBlank { "主机" }
            state.copy(
                sessionRuns = nextSession,
                selectedRunIdsForExport = state.selectedRunIdsForExport + addedIds,
                viewingRunId = chronological.last().id,
                showHistorySheet = false,
                statusMessage = "已载入「$serverLabel」的 ${records.size} 条测试记录",
            )
        }
    }

    fun deleteHistoryRun(runId: String) {
        externalScope.launch {
            vaultRepository.deleteRunRecord(runId)
        }
    }

    fun openExportPreviewSheet() {
        _uiState.update { it.copy(showExportPreviewSheet = true) }
    }

    fun dismissExportPreviewSheet() {
        _uiState.update { it.copy(showExportPreviewSheet = false) }
    }

    fun setMergeLayout(layout: SshMergeLayout) {
        _uiState.update { it.copy(mergeLayout = layout) }
    }

    fun openHistorySheet() {
        _uiState.update { it.copy(showHistorySheet = true) }
    }

    fun dismissHistorySheet() {
        _uiState.update { it.copy(showHistorySheet = false) }
    }

    fun buildExportPayload(): SshFormattedPost? = _uiState.value.exportPreviewPost

    suspend fun buildExportPayloadAsync(): SshFormattedPost? {
        val stateSnapshot = _uiState.value
        return withContext(Dispatchers.Default) {
            val runs = stateSnapshot.exportableRuns
            if (runs.isEmpty()) {
                null
            } else {
                com.nodeseek.app.domain.ssh.SshBenchmarkPostFormatter.format(
                    runs = runs,
                    maskIp = stateSnapshot.maskServerIp,
                    mergeLayout = stateSnapshot.mergeLayout,
                )
            }
        }
    }

    fun consumeStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun openIperf3ServerDialog(
        preferredHost: String? = null,
        preferredPort: Int? = null,
    ) {
        _uiState.update { state ->
            val cleanHost = preferredHost?.trim()?.takeIf { it.isNotEmpty() }
            val matchedServerId = cleanHost?.let { targetHost ->
                state.servers.firstOrNull { srv ->
                    srv.host.trim().equals(targetHost, ignoreCase = true)
                }?.id
            }
            val nextPortText = preferredPort
                ?.takeIf(Iperf3RemoteServerCommands::isValidPort)
                ?.toString()
                ?: state.iperf3ServerPortText
            state.copy(
                selectedServerId = matchedServerId ?: state.selectedServerId,
                iperf3ServerPortText = nextPortText,
                showIperf3ServerDialog = true,
            )
        }
    }

    fun dismissIperf3ServerDialog() {
        _uiState.update { it.copy(showIperf3ServerDialog = false) }
    }

    fun updateIperf3ServerPortText(portText: String) {
        val digits = portText.filter { it.isDigit() }.take(5)
        _uiState.update { it.copy(iperf3ServerPortText = digits) }
    }

    fun toggleIperf3AutoOpenFirewall(enabled: Boolean) {
        _uiState.update { it.copy(iperf3AutoOpenFirewall = enabled) }
    }

    fun startIperf3Server(
        portOverride: Int? = null,
        autoOpenFirewallOverride: Boolean? = null,
    ) {
        val state = _uiState.value
        val server = state.selectedServer
        if (server == null) {
            _uiState.update {
                it.copy(
                    showIperf3ServerDialog = false,
                    showServerEditor = true,
                    statusMessage = "请先添加一台 VPS 主机",
                )
            }
            return
        }
        val port = portOverride ?: Iperf3RemoteServerCommands.validatePort(state.iperf3ServerPortText)
        if (port == null || !Iperf3RemoteServerCommands.isValidPort(port)) {
            _uiState.update {
                it.copy(statusMessage = "iperf3 端口必须在 1 ~ 65535 之间")
            }
            return
        }
        val openFw = autoOpenFirewallOverride ?: state.iperf3AutoOpenFirewall
        val command = Iperf3RemoteServerCommands.buildCheckAndStartScript(
            port = port,
            autoOpenFirewall = openFw,
        )
        val preset = SshScriptPreset(
            id = Iperf3RemoteServerCommands.PRESET_ID_START,
            name = "开启 iperf3 服务端 ($port)",
            tabTitle = "iperf3 服务端",
            category = SshPresetCategory.NETWORK,
            description = "检测安装状态并在后台守护启动 iperf3 -s -p $port",
            estimatedDurationLabel = "~3 秒",
            commandTemplate = command,
        )
        _uiState.update {
            it.copy(
                iperf3ServerPortText = port.toString(),
                iperf3AutoOpenFirewall = openFw,
                showIperf3ServerDialog = false,
                pendingIperf3InstallPrompt = null,
            )
        }
        executeIperf3ServiceCommand(
            server = server,
            preset = preset,
            command = command,
            port = port,
            autoOpenFirewall = openFw,
            allowHostKeyMismatchOverride = false,
        )
    }

    fun confirmInstallAndStartIperf3Server() {
        val state = _uiState.value
        val prompt = state.pendingIperf3InstallPrompt ?: return
        val server = state.servers.firstOrNull { it.id == prompt.serverId } ?: state.selectedServer
        _uiState.update { it.copy(pendingIperf3InstallPrompt = null) }
        if (server == null) return

        val command = Iperf3RemoteServerCommands.buildInstallAndStartScript(
            distro = prompt.probe.distroFamily,
            isRoot = prompt.probe.isRoot,
            hasDnf = prompt.probe.hasDnf,
            port = prompt.port,
            autoOpenFirewall = prompt.autoOpenFirewall,
        )
        val preset = SshScriptPreset(
            id = Iperf3RemoteServerCommands.PRESET_ID_INSTALL_AND_START,
            name = "安装并开启 iperf3 (${prompt.port})",
            tabTitle = "iperf3 安装与启动",
            category = SshPresetCategory.NETWORK,
            description = "自动安装 iperf3 (${prompt.probe.distroFamily.displayLabel}) 并启动后台服务端",
            estimatedDurationLabel = "~15-45 秒",
            commandTemplate = command,
        )
        executeIperf3ServiceCommand(
            server = server,
            preset = preset,
            command = command,
            port = prompt.port,
            autoOpenFirewall = prompt.autoOpenFirewall,
            allowHostKeyMismatchOverride = false,
        )
    }

    fun dismissIperf3InstallPrompt() {
        _uiState.update { it.copy(pendingIperf3InstallPrompt = null) }
    }

    fun disconnectExternalIperf3Server(portOverride: Int? = null) {
        val state = _uiState.value
        val server = state.selectedServer
        if (server == null) {
            _uiState.update {
                it.copy(
                    showIperf3ServerDialog = false,
                    showServerEditor = true,
                    statusMessage = "请先添加一台 VPS 主机",
                )
            }
            return
        }
        val port = portOverride ?: Iperf3RemoteServerCommands.validatePort(state.iperf3ServerPortText)
        if (port == null || !Iperf3RemoteServerCommands.isValidPort(port)) {
            _uiState.update {
                it.copy(statusMessage = "iperf3 端口必须在 1 ~ 65535 之间")
            }
            return
        }
        val endpointKey = Iperf3EndpointKey(serverId = server.id, port = port)
        _uiState.update { cur ->
            cur.copy(
                iperf3ServerPortText = port.toString(),
                showIperf3ServerDialog = false,
                iperf3Attachments = cur.iperf3Attachments - endpointKey,
                statusMessage = "已断开与「${server.displayName}」端口 $port 外部 iperf3 服务的本地连接（未终止远程进程）",
            )
        }
    }

    fun stopIperf3Server(portOverride: Int? = null) {
        val state = _uiState.value
        val server = state.selectedServer
        if (server == null) {
            _uiState.update {
                it.copy(
                    showIperf3ServerDialog = false,
                    showServerEditor = true,
                    statusMessage = "请先添加一台 VPS 主机",
                )
            }
            return
        }
        val port = portOverride ?: Iperf3RemoteServerCommands.validatePort(state.iperf3ServerPortText)
        if (port == null || !Iperf3RemoteServerCommands.isValidPort(port)) {
            _uiState.update {
                it.copy(statusMessage = "iperf3 端口必须在 1 ~ 65535 之间")
            }
            return
        }
        val endpointKey = Iperf3EndpointKey(serverId = server.id, port = port)
        val attachment = state.iperf3Attachments[endpointKey]
        if (attachment?.isExternalReused == true) {
            disconnectExternalIperf3Server(portOverride = port)
            return
        }
        val ownedInstance = attachment?.ownedInstance
        val command = Iperf3RemoteServerCommands.buildStopScript(
            port = port,
            expectedPid = ownedInstance?.pid,
            expectedStartTime = ownedInstance?.startTime,
        )
        val preset = SshScriptPreset(
            id = Iperf3RemoteServerCommands.PRESET_ID_STOP,
            name = "停止 iperf3 服务端 ($port)",
            tabTitle = "停止 iperf3",
            category = SshPresetCategory.NETWORK,
            description = "仅停止本应用在端口 $port 启动的 iperf3 服务端进程",
            estimatedDurationLabel = "~2 秒",
            commandTemplate = command,
        )
        _uiState.update {
            it.copy(
                iperf3ServerPortText = port.toString(),
                showIperf3ServerDialog = false,
            )
        }
        executeIperf3ServiceCommand(
            server = server,
            preset = preset,
            command = command,
            port = port,
            autoOpenFirewall = state.iperf3AutoOpenFirewall,
            allowHostKeyMismatchOverride = false,
        )
    }

    /**
     * Runs an `iperf3` server utility command (start / install+start / stop) on its own SSH
     * connection, fully isolated from the benchmark pipeline in [executePresetOnServer]:
     * - never cancels or disturbs an in-progress benchmark script (e.g. NodeQuality / YABS);
     * - never touches `isRunning`, the live console stream, or the global running bar;
     * - never appends to `sessionRuns` nor persists a run record into the local history;
     * - never posts foreground-service / completion notifications or in-app completion popups.
     * Only a short [LiteSshUiState.statusMessage] summarises the result.
     */
    private fun executeIperf3ServiceCommand(
        server: SshServerProfile,
        preset: SshScriptPreset,
        command: String,
        port: Int,
        autoOpenFirewall: Boolean,
        allowHostKeyMismatchOverride: Boolean,
        mismatchOverrideServerId: String? = null,
    ) {
        iperf3ServiceJob?.cancel()
        val presetId = preset.id
        val generation = ++iperf3ServiceGeneration
        _uiState.update { it.copy(isIperf3ServiceBusy = true) }

        val job = externalScope.launch {
            try {
                val effectiveMode = server.effectiveConnectionMode
                val selectedExecutor = if (effectiveMode == SshConnectionMode.PANEL_TERMINAL) {
                    resolvePanelTerminalExecutor()
                        ?: throw IllegalStateException("探针终端执行器未就绪，请先在「我的服务器」配置探针面板或切换为「直连 SSH」")
                } else {
                    executor
                }
                val request: SshConnectionRequest = SshJumpHostResolver.buildConnectionRequest(
                    vaultRepository = vaultRepository,
                    server = server,
                    allowHostKeyMismatchOverride = allowHostKeyMismatchOverride,
                    mismatchOverrideServerId = mismatchOverrideServerId,
                    connectionModeOverride = effectiveMode,
                )
                var exitCode: Int? = null
                var output = ""
                selectedExecutor.runCommand(
                    request = request,
                    command = command,
                    autoRespondConfig = SshAutoRespondConfig(enabled = _uiState.value.autoRespondPrompts),
                ).collect { event ->
                    when (event) {
                        is LiteSshExecutionEvent.HostKeyChecked -> {
                            val checkedServerId = event.serverId ?: server.id
                            when (val verification = event.verification) {
                                is HostKeyVerificationResult.FirstUseTrust ->
                                    vaultRepository.updateHostKeyFingerprint(
                                        serverId = checkedServerId,
                                        fingerprintSha256 = verification.hostKey.fingerprintSha256,
                                        algorithm = verification.hostKey.algorithm,
                                    )
                                is HostKeyVerificationResult.KeyChangedWarning ->
                                    vaultRepository.updateHostKeyFingerprint(
                                        serverId = checkedServerId,
                                        fingerprintSha256 = verification.hostKey.fingerprintSha256,
                                        algorithm = verification.hostKey.algorithm,
                                    )
                                is HostKeyVerificationResult.Trusted -> Unit
                            }
                        }
                        LiteSshExecutionEvent.Connected ->
                            vaultRepository.markServerConnected(server.id, clock())
                        is LiteSshExecutionEvent.Completed -> {
                            exitCode = event.exitCode
                            output = event.sanitizedAnsiOutput
                        }
                        else -> Unit
                    }
                }
                handleIperf3ServiceResult(
                    presetId = presetId,
                    server = server,
                    port = port,
                    autoOpenFirewall = autoOpenFirewall,
                    exitCode = exitCode ?: 0,
                    output = output,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (mismatch: SshHostKeyMismatchException) {
                val resolvedMismatchServerId = mismatch.serverId ?: server.id
                val resolvedMismatchAlias = mismatch.serverAlias
                    ?: _uiState.value.servers.firstOrNull { it.id == resolvedMismatchServerId }?.displayName
                    ?: server.displayName
                _uiState.update {
                    it.copy(
                        statusMessage = "检测到主机密钥变更，已阻断 iperf3 服务端操作",
                        pendingHostKeyMismatch = PendingHostKeyMismatchPrompt(
                            serverId = server.id,
                            serverAlias = resolvedMismatchAlias,
                            preset = preset,
                            command = command,
                            expectedFingerprint = mismatch.expectedFingerprint,
                            actualHostKey = mismatch.actualHostKey,
                            mismatchServerId = resolvedMismatchServerId,
                            iperf3Port = port,
                            iperf3AutoOpenFirewall = autoOpenFirewall,
                        ),
                    )
                }
            } catch (t: Throwable) {
                _uiState.update { it.copy(statusMessage = "iperf3 服务端操作失败：${formatFriendlySshError(t)}") }
            } finally {
                if (iperf3ServiceGeneration == generation) {
                    iperf3ServiceJob = null
                    _uiState.update { it.copy(isIperf3ServiceBusy = false) }
                }
            }
        }
        if (iperf3ServiceGeneration == generation && job.isActive) {
            iperf3ServiceJob = job
        }
    }

    private fun handleIperf3ServiceResult(
        presetId: String,
        server: SshServerProfile,
        port: Int,
        autoOpenFirewall: Boolean,
        exitCode: Int,
        output: String,
    ) {
        if (presetId == Iperf3RemoteServerCommands.PRESET_ID_START &&
            exitCode == Iperf3RemoteServerCommands.EXIT_CODE_NOT_INSTALLED
        ) {
            val parsed = Iperf3RemoteServerCommands.parseMissingProbeOutput(output)
            if (parsed != null) {
                _uiState.update { s ->
                    s.copy(
                        statusMessage = "未检测到 iperf3（${parsed.osRelease.displayTitle}），可一键自动安装",
                        pendingIperf3InstallPrompt = PendingIperf3InstallPrompt(
                            serverId = server.id,
                            serverAlias = server.displayName,
                            port = port,
                            autoOpenFirewall = autoOpenFirewall,
                            probe = parsed,
                        ),
                    )
                }
                return
            }
        }
        val endpointKey = Iperf3EndpointKey(serverId = server.id, port = port)
        val isStart = presetId == Iperf3RemoteServerCommands.PRESET_ID_START ||
            presetId == Iperf3RemoteServerCommands.PRESET_ID_INSTALL_AND_START
        if (isStart && exitCode == 0) {
            val startProbe = Iperf3RemoteServerCommands.parseStartResultOutput(output, fallbackPort = port)
            _uiState.update { cur ->
                when (startProbe) {
                    is Iperf3StartProbeResult.StartedByApp -> cur.copy(
                        iperf3Attachments = cur.iperf3Attachments + (
                            endpointKey to Iperf3ServerAttachment(
                                serverId = server.id,
                                serverAlias = server.displayName,
                                port = port,
                                ownership = Iperf3ServiceOwnership.APP_STARTED,
                                ownedInstance = startProbe.instance,
                            )
                        ),
                        statusMessage = "已在「${server.displayName}」开启 iperf3 服务端（端口 $port，PID ${startProbe.instance.pid}）",
                    )
                    is Iperf3StartProbeResult.AlreadyOwnedByApp -> cur.copy(
                        iperf3Attachments = cur.iperf3Attachments + (
                            endpointKey to Iperf3ServerAttachment(
                                serverId = server.id,
                                serverAlias = server.displayName,
                                port = port,
                                ownership = Iperf3ServiceOwnership.APP_STARTED,
                                ownedInstance = startProbe.instance,
                            )
                        ),
                        statusMessage = "「${server.displayName}」端口 $port 已有本应用启动的 iperf3 服务端运行中（PID ${startProbe.instance.pid}）",
                    )
                    is Iperf3StartProbeResult.ReusedExternalService -> cur.copy(
                        iperf3Attachments = cur.iperf3Attachments + (
                            endpointKey to Iperf3ServerAttachment(
                                serverId = server.id,
                                serverAlias = server.displayName,
                                port = port,
                                ownership = Iperf3ServiceOwnership.EXTERNAL_REUSED,
                                ownedInstance = null,
                            )
                        ),
                        statusMessage = "已连接复用「${server.displayName}」端口 $port 已有的外部 iperf3 服务（非本应用启动，仅支持断开本地连接）",
                    )
                    null -> cur.copy(
                        statusMessage = "已在「${server.displayName}」开启 iperf3 服务端（端口 $port）",
                    )
                }
            }
            return
        }
        if (presetId == Iperf3RemoteServerCommands.PRESET_ID_STOP && exitCode == 0) {
            val stopProbe = Iperf3RemoteServerCommands.parseStopResultOutput(output, fallbackPort = port)
            val stopMessage = when (stopProbe?.outcome) {
                Iperf3StopOutcome.STOPPED ->
                    "已停止「${server.displayName}」由本应用启动的 iperf3 服务端（端口 $port${stopProbe.pid?.let { "，PID $it" }.orEmpty()}）"
                Iperf3StopOutcome.ALREADY_STOPPED ->
                    "「${server.displayName}」端口 $port 的本应用 iperf3 服务端已退出，已清理状态记录"
                Iperf3StopOutcome.EXTERNAL_UNTOUCHED ->
                    "「${server.displayName}」端口 $port 运行的是外部 iperf3 服务（非本应用启动），未执行远程终止"
                Iperf3StopOutcome.STALE_PID_MISMATCH ->
                    "检测到「${server.displayName}」端口 $port 记录的 PID 已复用（启动时间不匹配），已拒绝终止并清理旧记录"
                Iperf3StopOutcome.TARGET_MISMATCH,
                Iperf3StopOutcome.METADATA_MISMATCH,
                ->
                    "检测到「${server.displayName}」端口 $port 进程参数或状态元数据不匹配，已拒绝终止以保护其他任务"
                Iperf3StopOutcome.NOT_FOUND ->
                    "「${server.displayName}」端口 $port 未发现由本应用启动的 iperf3 服务端"
                Iperf3StopOutcome.KILL_FAILED,
                null,
                ->
                    "已执行停止「${server.displayName}」的 iperf3 服务端（端口 $port）"
            }
            _uiState.update { cur ->
                cur.copy(
                    iperf3Attachments = cur.iperf3Attachments - endpointKey,
                    statusMessage = stopMessage,
                )
            }
            return
        }
        val message = when {
            isStart && exitCode == Iperf3RemoteServerCommands.EXIT_CODE_PORT_CONFLICT ->
                "端口 $port 已被其他进程占用，请更换端口后重试"
            else -> {
                val action = if (isStart) "开启" else "停止"
                val lastLine = lastMeaningfulOutputLine(output)
                buildString {
                    append("「${server.displayName}」$action iperf3 服务端失败（退出码 $exitCode）")
                    if (lastLine != null) append("：").append(lastLine)
                }
            }
        }
        _uiState.update { it.copy(statusMessage = message) }
    }

    private fun lastMeaningfulOutputLine(output: String): String? =
        output.replace(ANSI_ESCAPE_REGEX, "")
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() && !Iperf3RemoteServerCommands.isInternalMarkerLine(it) }
            ?.take(120)

    internal fun disposeOwnedScopeForTests() {
        executionJob?.cancel()
        elapsedTickerJob?.cancel()
        iperf3ServiceJob?.cancel()
        fleetPanelsObserveJob?.cancel()
        probeSyncJob?.cancel()
        externalScope.cancel()
    }

    private fun resolvePanelTerminalExecutor(): LiteSshExecutor? =
        panelTerminalExecutorOverride ?: defaultPanelTerminalExecutor

    private fun executePresetOnServer(
        server: SshServerProfile,
        preset: SshScriptPreset,
        command: String,
        allowHostKeyMismatchOverride: Boolean,
        mismatchOverrideServerId: String? = null,
        connectionModeOverride: SshConnectionMode? = null,
        interactiveShell: Boolean = false,
    ) {
        executionJob?.cancel()
        elapsedTickerJob?.cancel()

        val effectiveMode = connectionModeOverride ?: server.effectiveConnectionMode
        val startedAt = clock()
        val currentRunGeneration = ++runGenerationCounter
        latestOutputTimestampMillis = null
        val autoRespondEnabled = !interactiveShell && _uiState.value.autoRespondPrompts
        val initialProgress = SshStageDetector.detect(
            presetId = preset.id,
            command = command,
            sanitizedOutput = "",
            isConnecting = true,
            startedAtEpochMillis = startedAt,
            lastOutputEpochMillis = null,
            nowEpochMillis = startedAt,
            silenceThresholdMillis = silenceThresholdMillis,
        )

        lastForegroundNotifyMillis = 0L
        lastForegroundStatusLine = ""

        _consoleStreamState.value = SshConsoleStreamState(
            runGeneration = currentRunGeneration,
            bufferSnapshot = SanitizedBufferSnapshot.EMPTY,
            serverHost = server.host,
        )

        _uiState.update {
            it.copy(
                isRunning = true,
                isInteractiveSession = interactiveShell,
                activeConnectionMode = effectiveMode,
                connectionPhase = SshConnectionPhase.CONNECTING,
                activeServerAlias = server.displayName,
                activeServerHost = server.host,
                activePresetId = preset.id,
                activePresetName = preset.name,
                activeCommand = command,
                liveAnsiOutput = "",
                hasLiveOutputFlag = false,
                liveBufferAccessor = liveBufferAccessor,
                startedAtEpochMillis = startedAt,
                lastOutputEpochMillis = null,
                elapsedMillis = 0L,
                liveProgress = initialProgress,
                autoRespondCount = 0,
                lastAutoRespondedPrompt = null,
                exitCode = null,
                errorMessage = null,
                viewingRunId = null,
                pendingHostKeyMismatch = null,
            )
        }
        syncGlobalBarState()

        syncForegroundNotificationIfNeeded(startedAt, force = true)

        if (enableElapsedTicker) {
            elapsedTickerJob = externalScope.launch {
                while (isActive) {
                    if (hasActiveUiObservers()) {
                        delay(ELAPSED_TICK_MS)
                        val now = clock()
                        if (hasActiveUiObservers()) {
                            refreshLiveProgress(now)
                        } else {
                            syncBackgroundForegroundNotificationOnly(now)
                        }
                    } else {
                        // App UI is stopped / backgrounded: pause 500ms UI state churn and only
                        // wake immediately if a UI collector re-attaches or every 2s for FGS notification.
                        val observerReattached = withTimeoutOrNull(FOREGROUND_NOTIF_UPDATE_INTERVAL_MS) {
                            merge(
                                _uiState.subscriptionCount,
                                _globalBarState.subscriptionCount,
                                _consoleStreamState.subscriptionCount,
                            ).first { it > 0 }
                            true
                        } ?: false
                        val now = clock()
                        if (observerReattached) {
                            refreshLiveProgress(now)
                        } else {
                            syncBackgroundForegroundNotificationOnly(now)
                        }
                    }
                }
            }
        }

        executionJob = externalScope.launch {
            var completedNormallyOrFailed = false
            try {
                val selectedExecutor = if (effectiveMode == SshConnectionMode.PANEL_TERMINAL) {
                    resolvePanelTerminalExecutor()
                        ?: throw IllegalStateException("探针终端执行器未就绪，请先在「我的服务器」配置探针面板或切换为「直连 SSH」")
                } else {
                    executor
                }

                val baseRequest: SshConnectionRequest = SshJumpHostResolver.buildConnectionRequest(
                    vaultRepository = vaultRepository,
                    server = server,
                    allowHostKeyMismatchOverride = allowHostKeyMismatchOverride,
                    mismatchOverrideServerId = mismatchOverrideServerId,
                    connectionModeOverride = effectiveMode,
                    interactiveShell = interactiveShell,
                )
                val initialPty = desiredPtySize
                val request = if (initialPty != null) {
                    baseRequest.copy(
                        ptyCols = initialPty.cols,
                        ptyRows = initialPty.rows,
                        ptyWidthPx = initialPty.widthPx,
                        ptyHeightPx = initialPty.heightPx,
                    )
                } else {
                    baseRequest
                }

                selectedExecutor.runCommand(
                    request = request,
                    command = command,
                    autoRespondConfig = SshAutoRespondConfig(enabled = autoRespondEnabled),
                ).flowOn(
                    SshPtyResizeCallbackContext { resizer ->
                        if (activePtyResizerGeneration <= currentRunGeneration) {
                            activePtyResizerGeneration = currentRunGeneration
                            activePtyResizer = resizer
                            desiredPtySize?.let(resizer::resize)
                        }
                    } + SshStdinCallbackContext { writer ->
                        if (activeStdinWriterGeneration <= currentRunGeneration) {
                            activeStdinWriterGeneration = currentRunGeneration
                            activeStdinWriter = writer
                        }
                    },
                ).collect { event ->
                    when (event) {
                        LiteSshExecutionEvent.Connecting -> {
                            _uiState.update { it.copy(connectionPhase = SshConnectionPhase.CONNECTING) }
                            syncGlobalBarState()
                        }
                        is LiteSshExecutionEvent.HostKeyChecked -> {
                            val checkedServerId = event.serverId ?: server.id
                            when (val verification = event.verification) {
                                is HostKeyVerificationResult.FirstUseTrust -> {
                                    vaultRepository.updateHostKeyFingerprint(
                                        serverId = checkedServerId,
                                        fingerprintSha256 = verification.hostKey.fingerprintSha256,
                                        algorithm = verification.hostKey.algorithm,
                                    )
                                    _uiState.update {
                                        it.copy(
                                            statusMessage = "首次连接已信任并记录主机指纹 (${verification.hostKey.algorithm})",
                                        )
                                    }
                                }
                                is HostKeyVerificationResult.KeyChangedWarning -> {
                                    vaultRepository.updateHostKeyFingerprint(
                                        serverId = checkedServerId,
                                        fingerprintSha256 = verification.hostKey.fingerprintSha256,
                                        algorithm = verification.hostKey.algorithm,
                                    )
                                }
                                is HostKeyVerificationResult.Trusted -> Unit
                            }
                        }
                        LiteSshExecutionEvent.Connected -> {
                            val now = clock()
                            latestOutputTimestampMillis = now
                            vaultRepository.markServerConnected(server.id, now)
                            val snap = _consoleStreamState.value.bufferSnapshot
                            _uiState.update { state ->
                                val progress = SshStageDetector.detectFromBuffer(
                                    presetId = preset.id,
                                    command = command,
                                    bufferSnapshot = snap,
                                    fallbackOutput = state.liveAnsiOutput,
                                    isConnecting = false,
                                    startedAtEpochMillis = startedAt,
                                    lastOutputEpochMillis = now,
                                    nowEpochMillis = now,
                                    silenceThresholdMillis = silenceThresholdMillis,
                                )
                                state.copy(
                                    connectionPhase = SshConnectionPhase.RUNNING,
                                    lastOutputEpochMillis = now,
                                    liveProgress = progress,
                                )
                            }
                            syncGlobalBarState()
                            syncForegroundNotificationIfNeeded(now, force = true)
                        }
                        is LiteSshExecutionEvent.OutputUpdated -> {
                            val now = clock()
                            latestOutputTimestampMillis = now
                            _consoleStreamState.value = SshConsoleStreamState(
                                runGeneration = currentRunGeneration,
                                bufferSnapshot = event.bufferSnapshot,
                                serverHost = server.host,
                            )

                            val progress = SshStageDetector.detectFromBuffer(
                                presetId = preset.id,
                                command = command,
                                bufferSnapshot = event.bufferSnapshot,
                                isConnecting = false,
                                startedAtEpochMillis = startedAt,
                                lastOutputEpochMillis = now,
                                nowEpochMillis = now,
                                silenceThresholdMillis = silenceThresholdMillis,
                            )

                            val cur = _uiState.value
                            val nonEmpty = !event.bufferSnapshot.isEmpty
                            val elapsedSecondChanged = (event.elapsedMillis / 1_000L) != (cur.elapsedMillis / 1_000L)
                            val progressChanged =
                                progress.currentStageLabel != cur.liveProgress.currentStageLabel ||
                                    progress.stageDetailHint != cur.liveProgress.stageDetailHint ||
                                    progress.isSilentLong != cur.liveProgress.isSilentLong
                            val shouldPublishChromeUpdate =
                                (nonEmpty && !cur.hasLiveOutputFlag) ||
                                    cur.connectionPhase != SshConnectionPhase.RUNNING ||
                                    elapsedSecondChanged ||
                                    progressChanged

                            if (shouldPublishChromeUpdate && (!enableElapsedTicker || hasActiveUiObservers() || !cur.hasLiveOutputFlag)) {
                                _uiState.update { state ->
                                    state.copy(
                                        connectionPhase = SshConnectionPhase.RUNNING,
                                        hasLiveOutputFlag = state.hasLiveOutputFlag || nonEmpty,
                                        lastOutputEpochMillis = now,
                                        elapsedMillis = event.elapsedMillis,
                                        liveProgress = progress,
                                    )
                                }
                                syncGlobalBarState()
                            }
                            syncForegroundNotificationIfNeeded(
                                nowMillis = now,
                                progressOverride = progress,
                                elapsedMillisOverride = event.elapsedMillis,
                            )
                        }
                        is LiteSshExecutionEvent.AutoResponded -> {
                            _uiState.update {
                                it.copy(
                                    autoRespondCount = event.decision.responseNumber,
                                    lastAutoRespondedPrompt = event.decision.matchedPromptLine,
                                )
                            }
                        }
                        is LiteSshExecutionEvent.Completed -> {
                            completedNormallyOrFailed = true
                            _consoleStreamState.value = SshConsoleStreamState(
                                runGeneration = currentRunGeneration,
                                bufferSnapshot = event.bufferSnapshot,
                                serverHost = server.host,
                            )
                            val runStatus = if (event.exitCode == 0) {
                                SshRunStatus.SUCCEEDED
                            } else {
                                SshRunStatus.FAILED
                            }
                            val errMsg = if (event.exitCode != 0) {
                                "脚本退出码为 ${event.exitCode}"
                            } else {
                                null
                            }
                            val record = SshExecutionRecord(
                                id = idGenerator(),
                                serverId = server.id,
                                serverAlias = server.displayName,
                                serverHost = server.host,
                                presetId = preset.id,
                                presetName = preset.name,
                                tabTitle = preset.tabTitle,
                                command = command,
                                sanitizedAnsiOutput = event.sanitizedAnsiOutput,
                                exitCode = event.exitCode,
                                status = runStatus,
                                startedAtEpochMillis = startedAt,
                                durationMillis = event.elapsedMillis,
                                errorMessage = errMsg,
                            )
                            _uiState.update {
                                it.copy(
                                    isRunning = false,
                                    isInteractiveSession = false,
                                    connectionPhase = SshConnectionPhase.COMPLETED,
                                    liveAnsiOutput = event.sanitizedAnsiOutput,
                                    hasLiveOutputFlag = event.sanitizedAnsiOutput.isNotBlank(),
                                    elapsedMillis = event.elapsedMillis,
                                    exitCode = event.exitCode,
                                    errorMessage = errMsg,
                                    statusMessage = when {
                                        interactiveShell -> "终端会话已结束：${server.displayName}"
                                        event.exitCode == 0 -> "测机完成：${server.displayName} · ${preset.name}"
                                        else -> "测机结束（退出码 ${event.exitCode}）：${server.displayName} · ${preset.name}"
                                    },
                                )
                            }
                            syncGlobalBarState()
                            if (!interactiveShell) {
                                appendCompletedSessionRun(record, autoSelect = record.hasOutput)
                                if (record.hasOutput) {
                                    vaultRepository.saveRunRecord(record)
                                }
                                dispatchCompletionNotices(record = record, errorDetail = errMsg)
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (mismatch: SshHostKeyMismatchException) {
                val resolvedMismatchServerId = mismatch.serverId ?: server.id
                val resolvedMismatchAlias = mismatch.serverAlias
                    ?: _uiState.value.servers.firstOrNull { it.id == resolvedMismatchServerId }?.displayName
                    ?: server.displayName
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        isInteractiveSession = false,
                        connectionPhase = SshConnectionPhase.FAILED,
                        errorMessage = "检测到主机密钥变更，已阻断连接",
                        pendingHostKeyMismatch = PendingHostKeyMismatchPrompt(
                            serverId = server.id,
                            serverAlias = resolvedMismatchAlias,
                            preset = preset,
                            command = command,
                            expectedFingerprint = mismatch.expectedFingerprint,
                            actualHostKey = mismatch.actualHostKey,
                            mismatchServerId = resolvedMismatchServerId,
                        ),
                    )
                }
                syncGlobalBarState()
            } catch (t: Throwable) {
                val friendlyError = formatFriendlySshError(t)
                val elapsed = (clock() - startedAt).coerceAtLeast(0L)
                val partialOutput = _uiState.value.resolvedLiveAnsiOutput
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        isInteractiveSession = false,
                        connectionPhase = SshConnectionPhase.FAILED,
                        liveAnsiOutput = partialOutput,
                        hasLiveOutputFlag = partialOutput.isNotBlank(),
                        elapsedMillis = elapsed,
                        errorMessage = friendlyError,
                        statusMessage = friendlyError,
                    )
                }
                syncGlobalBarState()
                if (!completedNormallyOrFailed && !interactiveShell) {
                    val failedRecord = SshExecutionRecord(
                        id = idGenerator(),
                        serverId = server.id,
                        serverAlias = server.displayName,
                        serverHost = server.host,
                        presetId = preset.id,
                        presetName = preset.name,
                        tabTitle = preset.tabTitle,
                        command = command,
                        sanitizedAnsiOutput = partialOutput,
                        exitCode = null,
                        status = SshRunStatus.FAILED,
                        startedAtEpochMillis = startedAt,
                        durationMillis = elapsed,
                        errorMessage = friendlyError,
                    )
                    dispatchCompletionNotices(record = failedRecord, errorDetail = friendlyError)
                }
            } finally {
                if (activePtyResizerGeneration == currentRunGeneration) {
                    activePtyResizer = null
                }
                if (activeStdinWriterGeneration == currentRunGeneration) {
                    activeStdinWriter = null
                }
                elapsedTickerJob?.cancel()
                elapsedTickerJob = null
                executionJob = null
                syncGlobalBarState()
                foregroundServiceController.onSessionFinished()
            }
        }
    }

    private fun hasActiveUiObservers(): Boolean =
        _uiState.subscriptionCount.value > 0 ||
            _globalBarState.subscriptionCount.value > 0 ||
            _consoleStreamState.subscriptionCount.value > 0

    private fun syncGlobalBarState(state: LiteSshUiState = _uiState.value) {
        _globalBarState.value = state.toGlobalBarState()
    }

    private fun syncBackgroundForegroundNotificationOnly(nowMillis: Long) {
        val state = _uiState.value
        if (!state.isRunning) return
        val elapsed = if (state.startedAtEpochMillis > 0L) {
            (nowMillis - state.startedAtEpochMillis).coerceAtLeast(0L)
        } else {
            state.elapsedMillis
        }
        val effectiveLastOutput = latestOutputTimestampMillis ?: state.lastOutputEpochMillis
        val progress = SshStageDetector.detectFromBuffer(
            presetId = state.activePresetId ?: state.selectedPresetId,
            command = state.activeCommand,
            bufferSnapshot = _consoleStreamState.value.bufferSnapshot,
            fallbackOutput = state.liveAnsiOutput,
            isConnecting = state.connectionPhase == SshConnectionPhase.CONNECTING,
            startedAtEpochMillis = state.startedAtEpochMillis,
            lastOutputEpochMillis = effectiveLastOutput,
            nowEpochMillis = nowMillis,
            silenceThresholdMillis = silenceThresholdMillis,
        )
        syncForegroundNotificationIfNeeded(
            nowMillis = nowMillis,
            progressOverride = progress,
            elapsedMillisOverride = elapsed,
        )
    }

    private fun syncForegroundNotificationIfNeeded(
        nowMillis: Long,
        force: Boolean = false,
        progressOverride: SshLiveProgressStatus? = null,
        elapsedMillisOverride: Long? = null,
    ) {
        val state = _uiState.value
        if (!state.isRunning) return
        val progress = progressOverride ?: state.liveProgress
        val elapsed = elapsedMillisOverride ?: state.elapsedMillis
        val clockStr = formatElapsedClock(elapsed)
        val statusLine = if (progress.isSilentLong) {
            "${progress.silentRunningBannerText} · $clockStr"
        } else {
            "${progress.currentStageLabel} · 已运行 $clockStr"
        }
        val stageChanged = statusLine != lastForegroundStatusLine &&
            (force || (nowMillis - lastForegroundNotifyMillis) >= FOREGROUND_NOTIF_UPDATE_INTERVAL_MS)
        if (!force && !stageChanged) return

        lastForegroundNotifyMillis = nowMillis
        lastForegroundStatusLine = statusLine
        foregroundServiceController.onSessionRunning(
            serverAlias = state.activeServerAlias ?: state.selectedServer?.displayName ?: "VPS",
            presetName = state.activePresetName ?: state.selectedPreset.name,
            statusLine = statusLine,
        )
    }

    private fun dispatchCompletionNotices(
        record: SshExecutionRecord,
        errorDetail: String?,
    ) {
        onBenchmarkCompleted?.invoke(record, errorDetail)

        val durationLabel = formatDurationShort(record.durationMillis)
        val preview = when (record.status) {
            SshRunStatus.SUCCEEDED -> "测机完成 · 耗时 $durationLabel，点击查看 ANSI 报告或一键发测评"
            SshRunStatus.FAILED -> "测机结束（${errorDetail ?: "异常退出"}）· 耗时 $durationLabel"
            else -> "测机已结束"
        }
        _completionPopupEvents.tryEmit(
            InAppMessagePopup(
                dedupKey = "ssh_done_${record.id}",
                kind = InAppMessageKind.SSH_BENCHMARK_COMPLETED,
                itemId = record.startedAtEpochMillis,
                senderUid = 0L,
                senderName = record.serverAlias.ifBlank { "轻量 SSH" },
                title = record.presetName,
                previewText = preview,
                emittedAtEpochMillis = clock(),
            ),
        )
    }

    private fun appendCompletedSessionRun(
        record: SshExecutionRecord,
        autoSelect: Boolean,
    ) {
        _uiState.update { state ->
            val updatedRuns = state.sessionRuns + record
            val updatedSelection = if (autoSelect) {
                state.selectedRunIdsForExport + record.id
            } else {
                state.selectedRunIdsForExport
            }
            state.copy(
                sessionRuns = updatedRuns,
                selectedRunIdsForExport = updatedSelection,
                viewingRunId = record.id,
            )
        }
    }

    private fun formatFriendlySshError(t: Throwable): String {
        if (t is SshJumpHostConnectionException || t is SshJumpHostCycleException) {
            return t.message?.trim()?.takeIf { it.isNotEmpty() } ?: "跳板机连接失败"
        }
        val raw = t.message?.trim().orEmpty()
        return when {
            raw.startsWith("跳板机连接失败") ||
                raw.startsWith("目标经跳板连接失败") ||
                raw.startsWith("哪吒") ||
                raw.startsWith("Komari") ||
                raw.startsWith("探针") ||
                raw.startsWith("当前主机") ||
                raw.startsWith("安全校验失败") ||
                raw.startsWith("未找到") ||
                raw.startsWith("等待 Komari") ||
                raw.startsWith("连接哪吒") ||
                raw.startsWith("连接 Komari") ||
                raw.startsWith("创建哪吒") -> raw
            raw.contains("Auth fail", ignoreCase = true) ||
                raw.contains("Auth cancel", ignoreCase = true) ->
                "SSH 认证失败：请检查用户名、密码或私钥（及私钥口令）"
            raw.contains("timeout", ignoreCase = true) ||
                raw.contains("timed out", ignoreCase = true) ->
                "连接超时：请检查服务器地址、端口及防火墙设置"
            raw.contains("Connection refused", ignoreCase = true) ->
                "连接被拒绝：请确认目标主机的 SSH 服务端口已开放"
            raw.contains("UnknownHostException", ignoreCase = true) ||
                raw.contains("Unable to resolve host", ignoreCase = true) ->
                "无法解析主机域名，请检查服务器地址或网络连接"
            raw.contains("invalid privatekey", ignoreCase = true) ->
                "私钥格式无效或私钥密码错误，请确认粘贴完整的 PEM / OpenSSH 私钥"
            raw.isNotEmpty() -> "SSH 执行异常：$raw"
            else -> "SSH 连接失败，请稍后重试"
        }
    }

    companion object {
        const val PRESET_ID_INTERACTIVE_TERMINAL: String = "__interactive_terminal__"
        private const val ELAPSED_TICK_MS: Long = 500L
        private const val FOREGROUND_NOTIF_UPDATE_INTERVAL_MS: Long = 2_000L
        private val ANSI_ESCAPE_REGEX = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
        private val IPERF3_SERVICE_PRESET_IDS: Set<String> = setOf(
            Iperf3RemoteServerCommands.PRESET_ID_START,
            Iperf3RemoteServerCommands.PRESET_ID_INSTALL_AND_START,
            Iperf3RemoteServerCommands.PRESET_ID_STOP,
        )
    }
}
