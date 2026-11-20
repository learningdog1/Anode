package com.nodeseek.app.ui.ssh

import androidx.compose.runtime.Stable
import com.nodeseek.app.domain.radar.InAppMessageKind
import com.nodeseek.app.domain.radar.InAppMessagePopup
import com.nodeseek.app.domain.ssh.CustomSshPreset
import com.nodeseek.app.domain.ssh.HostKeyVerificationResult
import com.nodeseek.app.domain.ssh.Iperf3RemoteServerCommands
import com.nodeseek.app.domain.ssh.LiteSshExecutionEvent
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.domain.ssh.SanitizedBufferSnapshot
import com.nodeseek.app.domain.ssh.SshAutoRespondConfig
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshExecutionRecord
import com.nodeseek.app.domain.ssh.SshFormattedPost
import com.nodeseek.app.domain.ssh.SshHostKeyMismatchException
import com.nodeseek.app.domain.ssh.SshJumpHostConnectionException
import com.nodeseek.app.domain.ssh.SshJumpHostCycleException
import com.nodeseek.app.domain.ssh.SshJumpHostResolver
import com.nodeseek.app.domain.ssh.SshLiveProgressStatus
import com.nodeseek.app.domain.ssh.SshPresetCatalog
import com.nodeseek.app.domain.ssh.SshPresetCategory
import com.nodeseek.app.domain.ssh.SshRunStatus
import com.nodeseek.app.domain.ssh.SshScriptPreset
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.domain.ssh.SshStageDetector
import com.nodeseek.app.domain.ssh.SshVaultRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
) {
    private val _consoleStreamState = MutableStateFlow(SshConsoleStreamState())
    val consoleStreamState: StateFlow<SshConsoleStreamState> = _consoleStreamState.asStateFlow()
    val consoleStreamHandle: SshConsoleStreamHandle = SshConsoleStreamHandle(consoleStreamState)

    private val _globalBarState = MutableStateFlow(LiteSshGlobalBarState())
    val globalBarState: StateFlow<LiteSshGlobalBarState> = _globalBarState.asStateFlow()

    private val liveBufferAccessor = LiveBufferAccessor { _consoleStreamState.value.bufferSnapshot }

    private val _uiState = MutableStateFlow(LiteSshUiState(liveBufferAccessor = liveBufferAccessor))
    val uiState: StateFlow<LiteSshUiState> = _uiState.asStateFlow()

    private val _completionPopupEvents = MutableSharedFlow<InAppMessagePopup>(extraBufferCapacity = 4)
    val completionPopupEvents: SharedFlow<InAppMessagePopup> = _completionPopupEvents.asSharedFlow()

    private var executionJob: Job? = null
    private var elapsedTickerJob: Job? = null
    private var lastForegroundNotifyMillis: Long = 0L
    private var lastForegroundStatusLine: String = ""
    private var runGenerationCounter: Long = 0L
    @Volatile
    private var latestOutputTimestampMillis: Long? = null

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
    }

    fun selectServer(serverId: String) {
        _uiState.update { it.copy(selectedServerId = serverId) }
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
                vaultRepository.saveServer(draft)
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
            vaultRepository.deleteServer(serverId)
            _uiState.update { it.copy(statusMessage = "已从本地保险库删除主机") }
        }
    }

    fun selectPreset(presetId: String) {
        _uiState.update { state ->
            state.copy(selectedPresetId = presetId)
        }
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
            vaultRepository.deleteCustomPreset(presetId)
            _uiState.update { it.copy(statusMessage = "已删除自定义脚本") }
        }
    }

    fun runSelectedPreset() {
        val state = _uiState.value
        val server = state.selectedServer
        if (server == null) {
            _uiState.update { it.copy(showServerEditor = true, statusMessage = "请先添加一台 VPS 主机") }
            return
        }
        val preset = state.selectedPreset
        val command = state.resolvedSelectedCommand
        executePresetOnServer(
            server = server,
            preset = preset,
            command = command,
            allowHostKeyMismatchOverride = false,
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
            executePresetOnServer(
                server = refreshed,
                preset = prompt.preset,
                command = prompt.command,
                allowHostKeyMismatchOverride = true,
                mismatchOverrideServerId = prompt.mismatchServerId,
            )
            attachIperf3PostRunWatcherIfNeeded(
                presetId = prompt.preset.id,
                server = refreshed,
                port = Iperf3RemoteServerCommands.validatePort(_uiState.value.iperf3ServerPortText)
                    ?: Iperf3RemoteServerCommands.DEFAULT_PORT,
                autoOpenFirewall = _uiState.value.iperf3AutoOpenFirewall,
            )
        }
    }

    fun dismissHostKeyMismatch() {
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
        elapsedTickerJob?.cancel()
        elapsedTickerJob = null
        activeJob.cancel()
        foregroundServiceController.onSessionFinished()

        val partialOutput = state.resolvedLiveAnsiOutput
        _uiState.update {
            it.copy(
                isRunning = false,
                connectionPhase = SshConnectionPhase.CANCELLED,
                liveAnsiOutput = partialOutput,
                hasLiveOutputFlag = partialOutput.isNotBlank(),
                statusMessage = "已断开 SSH 会话",
            )
        }
        syncGlobalBarState()

        if (partialOutput.isNotBlank()) {
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

    fun adjustConsoleFontScale(delta: Float) {
        _uiState.update { state ->
            val next = (state.consoleFontScale + delta).coerceIn(
                LiteSshUiState.MIN_CONSOLE_FONT_SCALE,
                LiteSshUiState.MAX_CONSOLE_FONT_SCALE,
            )
            state.copy(consoleFontScale = next)
        }
    }

    fun resetConsoleFontScale() {
        _uiState.update { it.copy(consoleFontScale = LiteSshUiState.DEFAULT_CONSOLE_FONT_SCALE) }
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

    fun openHistorySheet() {
        _uiState.update { it.copy(showHistorySheet = true) }
    }

    fun dismissHistorySheet() {
        _uiState.update { it.copy(showHistorySheet = false) }
    }

    fun buildExportPayload(): SshFormattedPost? = _uiState.value.exportPreviewPost

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
        executePresetOnServer(
            server = server,
            preset = preset,
            command = command,
            allowHostKeyMismatchOverride = false,
        )
        attachIperf3PostRunWatcherIfNeeded(
            presetId = preset.id,
            server = server,
            port = port,
            autoOpenFirewall = openFw,
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
        executePresetOnServer(
            server = server,
            preset = preset,
            command = command,
            allowHostKeyMismatchOverride = false,
        )
        attachIperf3PostRunWatcherIfNeeded(
            presetId = preset.id,
            server = server,
            port = prompt.port,
            autoOpenFirewall = prompt.autoOpenFirewall,
        )
    }

    fun dismissIperf3InstallPrompt() {
        _uiState.update { it.copy(pendingIperf3InstallPrompt = null) }
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
        val command = Iperf3RemoteServerCommands.buildStopScript(port = port)
        val preset = SshScriptPreset(
            id = Iperf3RemoteServerCommands.PRESET_ID_STOP,
            name = "停止 iperf3 服务端 ($port)",
            tabTitle = "停止 iperf3",
            category = SshPresetCategory.NETWORK,
            description = "停止 VPS 上运行中的 iperf3 服务端进程",
            estimatedDurationLabel = "~2 秒",
            commandTemplate = command,
        )
        _uiState.update {
            it.copy(
                iperf3ServerPortText = port.toString(),
                showIperf3ServerDialog = false,
            )
        }
        executePresetOnServer(
            server = server,
            preset = preset,
            command = command,
            allowHostKeyMismatchOverride = false,
        )
        attachIperf3PostRunWatcherIfNeeded(
            presetId = preset.id,
            server = server,
            port = port,
            autoOpenFirewall = state.iperf3AutoOpenFirewall,
        )
    }

    private fun attachIperf3PostRunWatcherIfNeeded(
        presetId: String,
        server: SshServerProfile,
        port: Int,
        autoOpenFirewall: Boolean,
    ) {
        val servicePresetIds = setOf(
            Iperf3RemoteServerCommands.PRESET_ID_START,
            Iperf3RemoteServerCommands.PRESET_ID_INSTALL_AND_START,
            Iperf3RemoteServerCommands.PRESET_ID_STOP,
        )
        if (presetId !in servicePresetIds) return
        val jobToWatch = executionJob ?: return

        externalScope.launch {
            jobToWatch.join()
            val finished = _uiState.value
            val latestServiceRun = finished.sessionRuns.lastOrNull { it.presetId == presetId }
            val serviceRunId = latestServiceRun?.id

            if (presetId == Iperf3RemoteServerCommands.PRESET_ID_START &&
                finished.exitCode == Iperf3RemoteServerCommands.EXIT_CODE_NOT_INSTALLED
            ) {
                val output = latestServiceRun?.sanitizedAnsiOutput ?: finished.rawConsoleOutput
                val parsed = Iperf3RemoteServerCommands.parseMissingProbeOutput(output)
                if (parsed != null) {
                    _uiState.update { s ->
                        s.copy(
                            sessionRuns = if (serviceRunId != null) {
                                s.sessionRuns.filterNot { it.id == serviceRunId }
                            } else {
                                s.sessionRuns
                            },
                            selectedRunIdsForExport = if (serviceRunId != null) {
                                s.selectedRunIdsForExport - serviceRunId
                            } else {
                                s.selectedRunIdsForExport
                            },
                            viewingRunId = if (s.viewingRunId == serviceRunId) {
                                s.sessionRuns.lastOrNull { it.id != serviceRunId }?.id
                            } else {
                                s.viewingRunId
                            },
                            errorMessage = null,
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
                    if (serviceRunId != null) {
                        vaultRepository.deleteRunRecord(serviceRunId)
                    }
                    return@launch
                }
            }

            // Uncheck service utility runs from review export by default so benchmark exports stay clean
            if (serviceRunId != null) {
                _uiState.update { s ->
                    val customStatus = when {
                        presetId == Iperf3RemoteServerCommands.PRESET_ID_START &&
                            finished.exitCode == Iperf3RemoteServerCommands.EXIT_CODE_PORT_CONFLICT ->
                            "端口 $port 已被其他进程占用，请更换端口后重试"
                        presetId in setOf(
                            Iperf3RemoteServerCommands.PRESET_ID_START,
                            Iperf3RemoteServerCommands.PRESET_ID_INSTALL_AND_START,
                        ) && finished.exitCode == 0 ->
                            "已在「${server.displayName}」开启 iperf3 服务端（端口 $port）"
                        presetId == Iperf3RemoteServerCommands.PRESET_ID_STOP && finished.exitCode == 0 ->
                            "已执行停止「${server.displayName}」的 iperf3 服务端（端口 $port）"
                        else -> s.statusMessage
                    }
                    s.copy(
                        selectedRunIdsForExport = s.selectedRunIdsForExport - serviceRunId,
                        statusMessage = customStatus,
                    )
                }
            }
        }
    }

    internal fun disposeOwnedScopeForTests() {
        executionJob?.cancel()
        elapsedTickerJob?.cancel()
        externalScope.cancel()
    }

    private fun executePresetOnServer(
        server: SshServerProfile,
        preset: SshScriptPreset,
        command: String,
        allowHostKeyMismatchOverride: Boolean,
        mismatchOverrideServerId: String? = null,
    ) {
        executionJob?.cancel()
        elapsedTickerJob?.cancel()

        val startedAt = clock()
        val currentRunGeneration = ++runGenerationCounter
        latestOutputTimestampMillis = null
        val autoRespondEnabled = _uiState.value.autoRespondPrompts
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
                val request: SshConnectionRequest = SshJumpHostResolver.buildConnectionRequest(
                    vaultRepository = vaultRepository,
                    server = server,
                    allowHostKeyMismatchOverride = allowHostKeyMismatchOverride,
                    mismatchOverrideServerId = mismatchOverrideServerId,
                )

                executor.runCommand(
                    request = request,
                    command = command,
                    autoRespondConfig = SshAutoRespondConfig(enabled = autoRespondEnabled),
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
                                    connectionPhase = SshConnectionPhase.COMPLETED,
                                    liveAnsiOutput = event.sanitizedAnsiOutput,
                                    hasLiveOutputFlag = event.sanitizedAnsiOutput.isNotBlank(),
                                    elapsedMillis = event.elapsedMillis,
                                    exitCode = event.exitCode,
                                    errorMessage = errMsg,
                                    statusMessage = if (event.exitCode == 0) {
                                        "测机完成：${server.displayName} · ${preset.name}"
                                    } else {
                                        "测机结束（退出码 ${event.exitCode}）：${server.displayName} · ${preset.name}"
                                    },
                                )
                            }
                            syncGlobalBarState()
                            appendCompletedSessionRun(record, autoSelect = record.hasOutput)
                            if (record.hasOutput) {
                                vaultRepository.saveRunRecord(record)
                            }
                            dispatchCompletionNotices(record = record, errorDetail = errMsg)
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
                        connectionPhase = SshConnectionPhase.FAILED,
                        liveAnsiOutput = partialOutput,
                        hasLiveOutputFlag = partialOutput.isNotBlank(),
                        elapsedMillis = elapsed,
                        errorMessage = friendlyError,
                        statusMessage = friendlyError,
                    )
                }
                syncGlobalBarState()
                if (!completedNormallyOrFailed) {
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
            raw.startsWith("跳板机连接失败") || raw.startsWith("目标经跳板连接失败") -> raw
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
        private const val ELAPSED_TICK_MS: Long = 500L
        private const val FOREGROUND_NOTIF_UPDATE_INTERVAL_MS: Long = 2_000L
    }
}
