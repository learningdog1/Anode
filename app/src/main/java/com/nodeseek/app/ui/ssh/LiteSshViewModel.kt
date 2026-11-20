package com.nodeseek.app.ui.ssh

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.data.ssh.JSchSshTransport
import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.domain.ssh.CustomSshPreset
import com.nodeseek.app.domain.ssh.HostKeyInfo
import com.nodeseek.app.domain.ssh.HostKeyVerificationResult
import com.nodeseek.app.domain.ssh.IpPrivacyMasker
import com.nodeseek.app.domain.ssh.LiteSshExecutionEvent
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.domain.ssh.SshBenchmarkPostFormatter
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshExecutionRecord
import com.nodeseek.app.domain.ssh.SshFormattedPost
import com.nodeseek.app.domain.ssh.SshHostKeyMismatchException
import com.nodeseek.app.domain.ssh.SshJumpHostConnectionException
import com.nodeseek.app.domain.ssh.SshJumpHostCycleException
import com.nodeseek.app.domain.ssh.SshJumpHostResolver
import com.nodeseek.app.domain.ssh.SshPresetCatalog
import com.nodeseek.app.domain.ssh.SshRunStatus
import com.nodeseek.app.domain.ssh.SshScriptPreset
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.domain.ssh.SshVaultRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
)

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
    val activeServerAlias: String? = null,
    val activeServerHost: String? = null,
    val activePresetName: String? = null,
    val activeCommand: String = "",
    val liveAnsiOutput: String = "",
    val elapsedMillis: Long = 0L,
    val exitCode: Int? = null,
    val errorMessage: String? = null,
    val sessionRuns: List<SshExecutionRecord> = emptyList(),
    val selectedRunIdsForExport: Set<String> = emptySet(),
    val viewingRunId: String? = null,
    val recentRuns: List<SshExecutionRecord> = emptyList(),
    val maskServerIp: Boolean = true,
    val consoleFontScale: Float = DEFAULT_CONSOLE_FONT_SCALE,
    val autoScrollConsole: Boolean = true,
    val pendingHostKeyMismatch: PendingHostKeyMismatchPrompt? = null,
    val showServerEditor: Boolean = false,
    val editingServer: SshServerProfile? = null,
    val showCustomPresetEditor: Boolean = false,
    val editingCustomPreset: CustomSshPreset? = null,
    val showExportPreviewSheet: Boolean = false,
    val showHistorySheet: Boolean = false,
    val statusMessage: String? = null,
) {
    val allPresets: List<SshScriptPreset>
        get() = builtInPresets + customPresets.map { it.toScriptPreset() }

    val selectedServer: SshServerProfile?
        get() = selectedServerId?.let { id -> servers.firstOrNull { it.id == id } }
            ?: servers.firstOrNull()

    val selectedPreset: SshScriptPreset
        get() = allPresets.firstOrNull { it.id == selectedPresetId }
            ?: builtInPresets.first()

    val selectedPresetFlagIds: Set<String>
        get() = enabledFlagIdsByPreset[selectedPreset.id] ?: selectedPreset.defaultEnabledFlagIds

    val resolvedSelectedCommand: String
        get() = selectedPreset.buildCommand(selectedPresetFlagIds)

    val viewingRun: SshExecutionRecord?
        get() = viewingRunId?.let { id ->
            sessionRuns.firstOrNull { it.id == id } ?: recentRuns.firstOrNull { it.id == id }
        }

    /**
     * Raw sanitized ANSI output currently displayed in the console (either live stream or a
     * selected completed session/history run).
     */
    val rawConsoleOutput: String
        get() = viewingRun?.sanitizedAnsiOutput ?: liveAnsiOutput

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
     * Completed runs currently included for 1-click NodeSeek export.
     */
    val exportableRuns: List<SshExecutionRecord>
        get() {
            val checked = sessionRuns.filter { it.id in selectedRunIdsForExport && it.hasOutput }
            if (checked.isNotEmpty()) return checked
            val viewed = viewingRun?.takeIf { it.hasOutput }
            if (viewed != null) return listOf(viewed)
            if (liveAnsiOutput.isNotBlank()) {
                return listOf(
                    SshExecutionRecord(
                        id = "live_snapshot",
                        serverId = selectedServer?.id.orEmpty(),
                        serverAlias = activeServerAlias ?: selectedServer?.displayName ?: "VPS",
                        serverHost = activeServerHost ?: selectedServer?.host.orEmpty(),
                        presetId = selectedPreset.id,
                        presetName = activePresetName ?: selectedPreset.name,
                        tabTitle = selectedPreset.tabTitle,
                        command = activeCommand.ifBlank { resolvedSelectedCommand },
                        sanitizedAnsiOutput = liveAnsiOutput,
                        exitCode = exitCode,
                        status = if (isRunning) SshRunStatus.RUNNING else SshRunStatus.SUCCEEDED,
                        startedAtEpochMillis = 0L,
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
                )
            }

    companion object {
        const val MIN_CONSOLE_FONT_SCALE: Float = 0.70f
        const val MAX_CONSOLE_FONT_SCALE: Float = 1.50f
        const val DEFAULT_CONSOLE_FONT_SCALE: Float = 1.00f
    }
}

/**
 * ViewModel powering `LiteSshScreen`: manages the encrypted server vault, preset selection & flags,
 * live streaming SSH execution via [LiteSshExecutor], TOFU host key mismatch prompts, and
 * 1-click NodeSeek Markdown / `:::: tabs` report export.
 */
class LiteSshViewModel(
    private val vaultRepository: SshVaultRepository,
    private val executor: LiteSshExecutor,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val enableElapsedTicker: Boolean = true,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LiteSshUiState())
    val uiState: StateFlow<LiteSshUiState> = _uiState.asStateFlow()

    private var executionJob: Job? = null
    private var elapsedTickerJob: Job? = null

    init {
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            vaultRepository.deleteCustomPreset(presetId)
            _uiState.update { it.copy(statusMessage = "已删除自定义脚本") }
        }
    }

    /**
     * Launches the currently selected preset against the currently selected server.
     */
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

    /**
     * Explicit user confirmation after reviewing a TOFU host key change warning.
     */
    fun confirmTrustChangedHostKey() {
        val prompt = _uiState.value.pendingHostKeyMismatch ?: return
        val server = _uiState.value.servers.firstOrNull { it.id == prompt.serverId }
        _uiState.update { it.copy(pendingHostKeyMismatch = null) }
        if (server == null) return

        viewModelScope.launch {
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

    /**
     * Immediately stops the active SSH script and disconnects the underlying session.
     */
    fun cancelExecution() {
        val activeJob = executionJob ?: return
        val state = _uiState.value
        executionJob = null
        elapsedTickerJob?.cancel()
        elapsedTickerJob = null
        activeJob.cancel()

        val partialOutput = state.liveAnsiOutput
        _uiState.update {
            it.copy(
                isRunning = false,
                connectionPhase = SshConnectionPhase.CANCELLED,
                statusMessage = "已断开 SSH 会话",
            )
        }

        if (partialOutput.isNotBlank()) {
            val server = state.selectedServer
            val cancelledRecord = SshExecutionRecord(
                id = idGenerator(),
                serverId = server?.id.orEmpty(),
                serverAlias = state.activeServerAlias ?: server?.displayName ?: "VPS",
                serverHost = state.activeServerHost ?: server?.host.orEmpty(),
                presetId = state.selectedPreset.id,
                presetName = state.activePresetName ?: state.selectedPreset.name,
                tabTitle = state.selectedPreset.tabTitle,
                command = state.activeCommand,
                sanitizedAnsiOutput = partialOutput,
                exitCode = null,
                status = SshRunStatus.CANCELLED,
                startedAtEpochMillis = (clock() - state.elapsedMillis).coerceAtLeast(0L),
                durationMillis = state.elapsedMillis,
            )
            appendCompletedSessionRun(cancelledRecord, autoSelect = false)
        }
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
        _uiState.update {
            it.copy(
                sessionRuns = emptyList(),
                selectedRunIdsForExport = emptySet(),
                viewingRunId = null,
                liveAnsiOutput = if (it.isRunning) it.liveAnsiOutput else "",
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

    fun deleteHistoryRun(runId: String) {
        viewModelScope.launch {
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
        _uiState.update {
            it.copy(
                isRunning = true,
                connectionPhase = SshConnectionPhase.CONNECTING,
                activeServerAlias = server.displayName,
                activeServerHost = server.host,
                activePresetName = preset.name,
                activeCommand = command,
                liveAnsiOutput = "",
                elapsedMillis = 0L,
                exitCode = null,
                errorMessage = null,
                viewingRunId = null,
                pendingHostKeyMismatch = null,
            )
        }

        if (enableElapsedTicker) {
            elapsedTickerJob = viewModelScope.launch {
                while (isActive) {
                    delay(ELAPSED_TICK_MS)
                    val elapsed = (clock() - startedAt).coerceAtLeast(0L)
                    _uiState.update { state ->
                        if (state.isRunning) state.copy(elapsedMillis = elapsed) else state
                    }
                }
            }
        }

        executionJob = viewModelScope.launch {
            try {
                val request: SshConnectionRequest = SshJumpHostResolver.buildConnectionRequest(
                    vaultRepository = vaultRepository,
                    server = server,
                    allowHostKeyMismatchOverride = allowHostKeyMismatchOverride,
                    mismatchOverrideServerId = mismatchOverrideServerId,
                )

                executor.runCommand(request = request, command = command).collect { event ->
                    when (event) {
                        LiteSshExecutionEvent.Connecting -> {
                            _uiState.update { it.copy(connectionPhase = SshConnectionPhase.CONNECTING) }
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
                            vaultRepository.markServerConnected(server.id, clock())
                            _uiState.update { it.copy(connectionPhase = SshConnectionPhase.RUNNING) }
                        }
                        is LiteSshExecutionEvent.OutputUpdated -> {
                            _uiState.update {
                                it.copy(
                                    connectionPhase = SshConnectionPhase.RUNNING,
                                    liveAnsiOutput = event.sanitizedAnsiOutput,
                                    elapsedMillis = event.elapsedMillis,
                                )
                            }
                        }
                        is LiteSshExecutionEvent.Completed -> {
                            val runStatus = if (event.exitCode == 0) {
                                SshRunStatus.SUCCEEDED
                            } else {
                                SshRunStatus.FAILED
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
                            )
                            _uiState.update {
                                it.copy(
                                    isRunning = false,
                                    connectionPhase = SshConnectionPhase.COMPLETED,
                                    liveAnsiOutput = event.sanitizedAnsiOutput,
                                    elapsedMillis = event.elapsedMillis,
                                    exitCode = event.exitCode,
                                    errorMessage = if (event.exitCode != 0) {
                                        "脚本退出码为 ${event.exitCode}"
                                    } else {
                                        null
                                    },
                                )
                            }
                            appendCompletedSessionRun(record, autoSelect = record.hasOutput)
                            if (record.hasOutput) {
                                vaultRepository.saveRunRecord(record)
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
            } catch (t: Throwable) {
                val friendlyError = formatFriendlySshError(t)
                val elapsed = (clock() - startedAt).coerceAtLeast(0L)
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        connectionPhase = SshConnectionPhase.FAILED,
                        elapsedMillis = elapsed,
                        errorMessage = friendlyError,
                        statusMessage = friendlyError,
                    )
                }
            } finally {
                elapsedTickerJob?.cancel()
                elapsedTickerJob = null
                executionJob = null
            }
        }
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

    override fun onCleared() {
        executionJob?.cancel()
        elapsedTickerJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val ELAPSED_TICK_MS: Long = 500L

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext ?: context
            val vault = SshServerVault.getDefault(appContext)
            return factory(
                vaultRepository = vault,
                executor = LiteSshExecutor(transport = JSchSshTransport(vaultRepository = vault)),
            )
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
