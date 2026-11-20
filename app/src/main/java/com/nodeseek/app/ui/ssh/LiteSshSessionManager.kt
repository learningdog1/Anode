package com.nodeseek.app.ui.ssh

import androidx.compose.runtime.Stable
import com.nodeseek.app.domain.radar.InAppMessageKind
import com.nodeseek.app.domain.radar.InAppMessagePopup
import com.nodeseek.app.domain.ssh.CustomSshPreset
import com.nodeseek.app.domain.ssh.HostKeyVerificationResult
import com.nodeseek.app.domain.ssh.LiteSshExecutionEvent
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.domain.ssh.SshAutoRespondConfig
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshExecutionRecord
import com.nodeseek.app.domain.ssh.SshFormattedPost
import com.nodeseek.app.domain.ssh.SshHostKeyMismatchException
import com.nodeseek.app.domain.ssh.SshLiveProgressStatus
import com.nodeseek.app.domain.ssh.SshPresetCatalog
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val _uiState = MutableStateFlow(LiteSshUiState())
    val uiState: StateFlow<LiteSshUiState> = _uiState.asStateFlow()

    private val _completionPopupEvents = MutableSharedFlow<InAppMessagePopup>(extraBufferCapacity = 4)
    val completionPopupEvents: SharedFlow<InAppMessagePopup> = _completionPopupEvents.asSharedFlow()

    private var executionJob: Job? = null
    private var elapsedTickerJob: Job? = null
    private var lastForegroundNotifyMillis: Long = 0L
    private var lastForegroundStatusLine: String = ""

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
        _uiState.update { state ->
            if (!state.isRunning) return@update state
            val elapsed = if (state.startedAtEpochMillis > 0L) {
                (nowMillis - state.startedAtEpochMillis).coerceAtLeast(0L)
            } else {
                state.elapsedMillis
            }
            val progress = SshStageDetector.detect(
                presetId = state.activePresetId ?: state.selectedPresetId,
                command = state.activeCommand,
                sanitizedOutput = state.liveAnsiOutput,
                isConnecting = state.connectionPhase == SshConnectionPhase.CONNECTING,
                startedAtEpochMillis = state.startedAtEpochMillis,
                lastOutputEpochMillis = state.lastOutputEpochMillis,
                nowEpochMillis = nowMillis,
                silenceThresholdMillis = silenceThresholdMillis,
            )
            state.copy(
                elapsedMillis = elapsed,
                liveProgress = progress,
            )
        }
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
    ) {
        executionJob?.cancel()
        elapsedTickerJob?.cancel()

        val startedAt = clock()
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

        syncForegroundNotificationIfNeeded(startedAt, force = true)

        if (enableElapsedTicker) {
            elapsedTickerJob = externalScope.launch {
                while (isActive) {
                    delay(ELAPSED_TICK_MS)
                    refreshLiveProgress(clock())
                }
            }
        }

        executionJob = externalScope.launch {
            var completedNormallyOrFailed = false
            try {
                val credentials = vaultRepository.decryptCredentials(server)
                val request = SshConnectionRequest(
                    host = server.host,
                    port = server.port,
                    username = server.username,
                    authMethod = server.authMethod,
                    secret = credentials.secret,
                    passphrase = credentials.passphrase,
                    expectedHostKeyFingerprint = server.knownHostKeyFingerprint,
                    allowHostKeyMismatchOverride = allowHostKeyMismatchOverride,
                )

                executor.runCommand(
                    request = request,
                    command = command,
                    autoRespondConfig = SshAutoRespondConfig(enabled = autoRespondEnabled),
                ).collect { event ->
                    when (event) {
                        LiteSshExecutionEvent.Connecting -> {
                            _uiState.update { it.copy(connectionPhase = SshConnectionPhase.CONNECTING) }
                        }
                        is LiteSshExecutionEvent.HostKeyChecked -> {
                            when (val verification = event.verification) {
                                is HostKeyVerificationResult.FirstUseTrust -> {
                                    vaultRepository.updateHostKeyFingerprint(
                                        serverId = server.id,
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
                                        serverId = server.id,
                                        fingerprintSha256 = verification.hostKey.fingerprintSha256,
                                        algorithm = verification.hostKey.algorithm,
                                    )
                                }
                                is HostKeyVerificationResult.Trusted -> Unit
                            }
                        }
                        LiteSshExecutionEvent.Connected -> {
                            val now = clock()
                            vaultRepository.markServerConnected(server.id, now)
                            _uiState.update { state ->
                                val progress = SshStageDetector.detect(
                                    presetId = preset.id,
                                    command = command,
                                    sanitizedOutput = state.liveAnsiOutput,
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
                            syncForegroundNotificationIfNeeded(now, force = true)
                        }
                        is LiteSshExecutionEvent.OutputUpdated -> {
                            val now = clock()
                            _uiState.update { state ->
                                val progress = SshStageDetector.detect(
                                    presetId = preset.id,
                                    command = command,
                                    sanitizedOutput = event.sanitizedAnsiOutput,
                                    isConnecting = false,
                                    startedAtEpochMillis = startedAt,
                                    lastOutputEpochMillis = now,
                                    nowEpochMillis = now,
                                    silenceThresholdMillis = silenceThresholdMillis,
                                )
                                state.copy(
                                    connectionPhase = SshConnectionPhase.RUNNING,
                                    liveAnsiOutput = event.sanitizedAnsiOutput,
                                    lastOutputEpochMillis = now,
                                    elapsedMillis = event.elapsedMillis,
                                    liveProgress = progress,
                                )
                            }
                            syncForegroundNotificationIfNeeded(now)
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
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        connectionPhase = SshConnectionPhase.FAILED,
                        errorMessage = "检测到主机密钥变更，已阻断连接",
                        pendingHostKeyMismatch = PendingHostKeyMismatchPrompt(
                            serverId = server.id,
                            serverAlias = server.displayName,
                            preset = preset,
                            command = command,
                            expectedFingerprint = mismatch.expectedFingerprint,
                            actualHostKey = mismatch.actualHostKey,
                        ),
                    )
                }
            } catch (t: Throwable) {
                val friendlyError = formatFriendlySshError(t)
                val elapsed = (clock() - startedAt).coerceAtLeast(0L)
                val partialOutput = _uiState.value.liveAnsiOutput
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        connectionPhase = SshConnectionPhase.FAILED,
                        elapsedMillis = elapsed,
                        errorMessage = friendlyError,
                        statusMessage = friendlyError,
                    )
                }
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
                foregroundServiceController.onSessionFinished()
            }
        }
    }

    private fun syncForegroundNotificationIfNeeded(nowMillis: Long, force: Boolean = false) {
        val state = _uiState.value
        if (!state.isRunning) return
        val progress = state.liveProgress
        val clockStr = formatElapsedClock(state.elapsedMillis)
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
