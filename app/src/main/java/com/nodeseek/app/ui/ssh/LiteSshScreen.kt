@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.ssh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Preview
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.domain.ssh.CustomSshPreset
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshBenchmarkPostFormatter
import com.nodeseek.app.domain.ssh.SshExecutionRecord
import com.nodeseek.app.domain.ssh.SshFormattedPost
import com.nodeseek.app.domain.ssh.SshPresetCategory
import com.nodeseek.app.domain.ssh.SshPresetFilter
import com.nodeseek.app.domain.ssh.SshPresetFilterOption
import com.nodeseek.app.domain.ssh.SshPtySize
import com.nodeseek.app.domain.ssh.SshRunStatus
import com.nodeseek.app.domain.ssh.SshScriptPreset
import com.nodeseek.app.domain.ssh.SshServerGroupOption
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.domain.ssh.SshVaultCatalogFilter
import com.nodeseek.app.ui.components.AnsiAndCodeFormatter
import com.nodeseek.app.ui.components.PulseConfirmDialog
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.ObsidianErrorContainer
import com.nodeseek.app.ui.theme.ObsidianOnErrorContainer
import com.nodeseek.app.ui.theme.ObsidianOnPrimaryContainer
import com.nodeseek.app.ui.theme.ObsidianPrimary
import com.nodeseek.app.ui.theme.ObsidianPrimaryContainer
import com.nodeseek.app.ui.theme.ObsidianSecondary
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseSectionHeader
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Lite SSH (`轻量 SSH —— 一键跑测试脚本`) full-screen workspace.
 *
 * Features:
 * - Locally encrypted SSH Server Vault (Android Keystore AES-256-GCM + TOFU SHA-256 host key checks)
 * - Curated non-interactive VPS benchmark presets (Quick Check, YABS, NodeQuality, NextTrace,
 *   RegionRestrictionCheck) + user-saved custom scripts
 * - High-fidelity dark ANSI terminal viewer (`AnsiAndCodeFormatter`) with horizontal + vertical
 *   scroll, font zoom, live elapsed timer, immediate stop/disconnect, and default-ON IP privacy mask
 * - 1-Click export to NodeSeek `review` (测评) composer (single `ansi` fence or multi-run `:::: tabs`)
 */
@Composable
fun LiteSshScreen(
    onBack: () -> Unit,
    onPublishToComposer: (suggestedTitle: String, markdownContent: String) -> Unit,
    onInsertToReply: ((markdownContent: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    previewUiState: LiteSshUiState? = null,
    viewModel: LiteSshViewModel = viewModel(factory = LiteSshViewModel.factory(LocalContext.current)),
) {
    val observedUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = previewUiState ?: observedUiState
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDeleteServer by remember { mutableStateOf<SshServerProfile?>(null) }
    var pendingDeleteCustomPreset by remember { mutableStateOf<CustomSshPreset?>(null) }

    LaunchedEffect(uiState.statusMessage) {
        val msg = uiState.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeStatusMessage()
    }

    val copyToClipboard: (String, String) -> Unit = remember(context, scope, snackbarHostState) {
        { text, toastMsg ->
            copyTextToClipboard(context, text)
            scope.launch { snackbarHostState.showSnackbar(toastMsg) }
        }
    }

    val hasNonBlankLiveText = uiState.liveAnsiOutput.isNotEmpty()
    val exportMeta = remember(
        uiState.sessionRuns,
        uiState.selectedRunIdsForExport,
        uiState.viewingRunId,
        uiState.hasLiveOutputFlag,
        hasNonBlankLiveText,
        uiState.mergeLayout,
    ) {
        uiState.computeExportSummaryMeta()
    }
    val asyncExportActions = rememberAsyncSshExportActions(
        previewUiState = previewUiState,
        viewModel = viewModel,
        scope = scope,
        onCopyText = copyToClipboard,
        onInsertToReplyCallback = onInsertToReply,
        onPublishToComposerCallback = onPublishToComposer,
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { PulseSnackbarHost(hostState = snackbarHostState) },
        topBar = {
            LiteSshTopBar(
                recentRunCount = uiState.recentRuns.size,
                onBack = onBack,
                onOpenHistory = viewModel::openHistorySheet,
                onAddServer = viewModel::openAddServerDialog,
            )
        },
        bottomBar = {
            if (exportMeta != null) {
                LiteSshExportDock(
                    exportMeta = exportMeta,
                    maskServerIp = uiState.maskServerIp,
                    onPreview = viewModel::openExportPreviewSheet,
                    onCopyMarkdown = asyncExportActions.onCopyMarkdown,
                    onInsertToReply = asyncExportActions.onInsertToReply,
                    onPublishToComposer = asyncExportActions.onPublishToComposer,
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 14.dp,
                bottom = if (exportMeta != null) 28.dp else 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. Encrypted SSH Server Vault
            item(key = "server_vault_section") {
                ServerVaultSection(
                    servers = uiState.servers,
                    filteredServers = uiState.filteredServers,
                    inlineVisibleServers = uiState.inlineVisibleServers,
                    selectedServer = uiState.selectedServer,
                    searchQuery = uiState.serverSearchQuery,
                    groupOptions = uiState.serverGroupOptions,
                    selectedGroupKey = uiState.effectiveServerGroupKey,
                    isExpanded = uiState.isServerVaultExpanded,
                    isRunning = uiState.isRunning,
                    onSearchQueryChange = viewModel::updateServerSearchQuery,
                    onClearSearch = viewModel::clearServerSearchQuery,
                    onSelectGroup = viewModel::selectServerGroup,
                    onToggleExpanded = viewModel::toggleServerVaultExpanded,
                    onOpenQuickPicker = viewModel::openServerQuickPickerSheet,
                    onSelectServer = viewModel::selectServer,
                    onAddServer = viewModel::openAddServerDialog,
                    onEditServer = viewModel::openEditServerDialog,
                    onDeleteServer = { pendingDeleteServer = it },
                )
            }

            // 2. Curated & Custom Benchmark Presets
            item(key = "presets_section") {
                PresetCatalogSection(
                    allPresets = uiState.allPresets,
                    filteredPresets = uiState.filteredPresets,
                    inlineVisiblePresets = uiState.inlineVisiblePresets,
                    customPresets = uiState.customPresets,
                    selectedPreset = uiState.selectedPreset,
                    searchQuery = uiState.presetSearchQuery,
                    filterOptions = uiState.presetFilterOptions,
                    selectedFilter = uiState.selectedPresetFilter,
                    isExpanded = uiState.isPresetCatalogExpanded,
                    enabledFlagIds = uiState.selectedPresetFlagIds,
                    resolvedCommand = uiState.resolvedSelectedCommand,
                    selectedServer = uiState.selectedServer,
                    isRunning = uiState.isRunning,
                    elapsedMillis = uiState.elapsedMillis,
                    onSearchQueryChange = viewModel::updatePresetSearchQuery,
                    onClearSearch = viewModel::clearPresetSearchQuery,
                    onSelectFilter = viewModel::selectPresetFilter,
                    onToggleExpanded = viewModel::togglePresetCatalogExpanded,
                    onOpenQuickPicker = viewModel::openPresetQuickPickerSheet,
                    onSelectPreset = viewModel::selectPreset,
                    onToggleFlag = { flagId ->
                        viewModel.togglePresetFlag(uiState.selectedPreset.id, flagId)
                    },
                    onAddCustomPreset = viewModel::openAddCustomPresetDialog,
                    onEditCustomPreset = viewModel::openEditCustomPresetDialog,
                    onDeleteCustomPreset = { pendingDeleteCustomPreset = it },
                    onRunPreset = viewModel::runSelectedPreset,
                    onStopExecution = viewModel::cancelExecution,
                )
            }

            // 3. Session Multi-Run Collector (for 1-Click :::: tabs combining)
            if (uiState.sessionRuns.isNotEmpty()) {
                item(key = "session_runs_strip") {
                    SessionRunsCollectorStrip(
                        sessionRuns = uiState.sessionRuns,
                        selectedForExportIds = uiState.selectedRunIdsForExport,
                        viewingRunId = uiState.viewingRunId,
                        isRunning = uiState.isRunning,
                        onViewRun = viewModel::viewRun,
                        onToggleRunForExport = viewModel::toggleRunForExport,
                        onRemoveRun = viewModel::removeSessionRun,
                        onClearAll = viewModel::clearSessionRuns,
                    )
                }
            }

            // 4. High-Fidelity Dark ANSI Console
            item(key = "ansi_console") {
                val viewedRun = uiState.viewingRun
                val consoleHostForMask = viewedRun?.serverHost ?: uiState.activeServerHost ?: uiState.selectedServer?.host
                val hasCopyableOutput = viewedRun?.hasOutput == true || uiState.hasLiveOutput
                val livePtyResizeCallback: ((SshPtySize) -> Unit)? = remember(
                    previewUiState == null && viewedRun == null,
                    viewModel,
                ) {
                    if (previewUiState == null && viewedRun == null) {
                        viewModel::updateTerminalPtySize
                    } else {
                        null
                    }
                }
                AnsiTerminalConsoleCard(
                    consoleStreamHandle = if (previewUiState == null && viewedRun == null) {
                        viewModel.consoleStreamHandle
                    } else {
                        null
                    },
                    staticSourceKey = viewedRun?.id ?: if (previewUiState != null) "preview" else "completed_live",
                    staticAnsiOutput = viewedRun?.sanitizedAnsiOutput ?: uiState.liveAnsiOutput,
                    serverHostForMask = consoleHostForMask,
                    hasCopyableOutput = hasCopyableOutput,
                    connectionPhase = uiState.connectionPhase,
                    isRunning = uiState.isRunning,
                    activeTitle = viewedRun?.let { "${it.serverAlias} · ${it.presetName}" }
                        ?: uiState.activePresetName?.let { preset ->
                            val alias = uiState.activeServerAlias ?: uiState.selectedServer?.displayName
                            if (alias != null) "$alias · $preset" else preset
                        }
                        ?: "ANSI 终端控制台",
                    elapsedMillis = viewedRun?.durationMillis ?: uiState.elapsedMillis,
                    exitCode = viewedRun?.exitCode ?: uiState.exitCode,
                    errorMessage = if (viewedRun == null) uiState.errorMessage else viewedRun.errorMessage,
                    liveProgress = if (viewedRun == null) uiState.liveProgress else null,
                    lastOutputEpochMillis = if (viewedRun == null) uiState.lastOutputEpochMillis else null,
                    autoRespondPrompts = uiState.autoRespondPrompts,
                    autoRespondCount = if (viewedRun == null) uiState.autoRespondCount else 0,
                    maskServerIp = uiState.maskServerIp,
                    consoleFontScale = uiState.consoleFontScale,
                    terminalAutoFitEnabled = uiState.terminalAutoFitEnabled,
                    autoScrollConsole = uiState.autoScrollConsole,
                    onToggleMaskIp = viewModel::toggleMaskServerIp,
                    onToggleAutoRespondPrompts = viewModel::toggleAutoRespondPrompts,
                    onToggleAutoFit = viewModel::setTerminalAutoFitEnabled,
                    onZoomOut = { effectiveScale ->
                        viewModel.adjustConsoleFontScale(-0.10f, autoFitStartScale = effectiveScale)
                    },
                    onZoomIn = { effectiveScale ->
                        viewModel.adjustConsoleFontScale(0.10f, autoFitStartScale = effectiveScale)
                    },
                    onPinchZoom = { zoomFactor, effectiveScale ->
                        viewModel.pinchConsoleFontScale(zoomFactor, autoFitStartScale = effectiveScale)
                    },
                    onResetZoom = viewModel::resetConsoleFontScale,
                    onPtySizeChanged = livePtyResizeCallback,
                    onToggleAutoScroll = viewModel::toggleAutoScrollConsole,
                    onCopyOutput = {
                        val currentState = previewUiState ?: viewModel.uiState.value
                        val raw = currentState.displayedConsoleOutput
                        if (raw.isNotBlank()) {
                            val plain = AnsiAndCodeFormatter.stripAnsiEscapes(raw)
                            copyToClipboard(plain, "已复制终端纯文本输出")
                        }
                    },
                )
            }
        }
    }

    // Dialogs & Bottom Sheets
    if (uiState.showServerQuickPickerSheet) {
        SshServerQuickPickerSheet(
            servers = uiState.servers,
            filteredServers = uiState.filteredServers,
            selectedServerId = uiState.selectedServer?.id,
            searchQuery = uiState.serverSearchQuery,
            groupOptions = uiState.serverGroupOptions,
            selectedGroupKey = uiState.effectiveServerGroupKey,
            isRunning = uiState.isRunning,
            onSearchQueryChange = viewModel::updateServerSearchQuery,
            onClearSearch = viewModel::clearServerSearchQuery,
            onSelectGroup = viewModel::selectServerGroup,
            onSelectServer = viewModel::selectServer,
            onAddServer = viewModel::openAddServerDialog,
            onEditServer = viewModel::openEditServerDialog,
            onDismiss = viewModel::dismissServerQuickPickerSheet,
        )
    }

    if (uiState.showPresetQuickPickerSheet) {
        SshPresetQuickPickerSheet(
            allPresets = uiState.allPresets,
            filteredPresets = uiState.filteredPresets,
            customPresets = uiState.customPresets,
            selectedPresetId = uiState.selectedPreset.id,
            searchQuery = uiState.presetSearchQuery,
            filterOptions = uiState.presetFilterOptions,
            selectedFilter = uiState.selectedPresetFilter,
            isRunning = uiState.isRunning,
            onSearchQueryChange = viewModel::updatePresetSearchQuery,
            onClearSearch = viewModel::clearPresetSearchQuery,
            onSelectFilter = viewModel::selectPresetFilter,
            onSelectPreset = viewModel::selectPreset,
            onAddCustomPreset = viewModel::openAddCustomPresetDialog,
            onEditCustomPreset = viewModel::openEditCustomPresetDialog,
            onDismiss = viewModel::dismissPresetQuickPickerSheet,
        )
    }

    if (uiState.showServerEditor) {
        SshServerEditorDialog(
            existing = uiState.editingServer,
            onDismiss = viewModel::dismissServerEditor,
            onSave = viewModel::saveServer,
            servers = uiState.servers,
        )
    }

    if (uiState.showCustomPresetEditor) {
        CustomSshPresetEditorDialog(
            existing = uiState.editingCustomPreset,
            onDismiss = viewModel::dismissCustomPresetEditor,
            onSave = viewModel::saveCustomPreset,
        )
    }

    uiState.pendingHostKeyMismatch?.let { prompt ->
        HostKeyMismatchWarningDialog(
            prompt = prompt,
            onDismiss = viewModel::dismissHostKeyMismatch,
            onTrustAndContinue = viewModel::confirmTrustChangedHostKey,
        )
    }

    // Retained solely for cross-screen invocation from MobileNetBench (NodePulseNavHost.onOpenLiteSshIperf3Setup);
    // LiteSshScreen itself intentionally exposes no iperf3 trigger UI.
    if (uiState.showIperf3ServerDialog) {
        Iperf3ServerControlDialog(
            servers = uiState.servers,
            selectedServer = uiState.selectedServer,
            portText = uiState.iperf3ServerPortText,
            autoOpenFirewall = uiState.iperf3AutoOpenFirewall,
            isRunning = uiState.isIperf3ServiceBusy,
            onSelectServer = viewModel::selectServer,
            onAddServer = {
                viewModel.dismissIperf3ServerDialog()
                viewModel.openAddServerDialog()
            },
            onPortTextChange = viewModel::updateIperf3ServerPortText,
            onToggleAutoOpenFirewall = viewModel::toggleIperf3AutoOpenFirewall,
            onStartServer = { viewModel.startIperf3Server() },
            onStopServer = { viewModel.stopIperf3Server() },
            onCopyCommand = { cmd -> copyToClipboard(cmd, "已复制防火墙放行命令") },
            onDismiss = viewModel::dismissIperf3ServerDialog,
        )
    }

    uiState.pendingIperf3InstallPrompt?.let { prompt ->
        Iperf3InstallConfirmDialog(
            prompt = prompt,
            onConfirmInstallAndStart = viewModel::confirmInstallAndStartIperf3Server,
            onDismiss = viewModel::dismissIperf3InstallPrompt,
        )
    }

    if (uiState.showExportPreviewSheet) {
        SshResultPublishSheet(
            uiState = uiState,
            onToggleMaskIp = viewModel::toggleMaskServerIp,
            onSelectMergeLayout = viewModel::setMergeLayout,
            onToggleCandidateRun = viewModel::toggleCandidateRunForExport,
            onSelectCandidateRuns = viewModel::setSelectedRunsForExport,
            onCopyMarkdown = { md -> copyToClipboard(md, "已复制 Markdown 测评报告") },
            onCopyPlainText = { txt -> copyToClipboard(txt, "已复制 NodeQuality 官方纯文本报告") },
            onPublishToComposer = onPublishToComposer,
            onInsertToReply = onInsertToReply,
            onDismiss = viewModel::dismissExportPreviewSheet,
        )
    }

    if (uiState.showHistorySheet) {
        SshHistoryBottomSheet(
            recentRuns = uiState.recentRuns,
            onLoadToSession = viewModel::addHistoryRunToSession,
            onDeleteRecord = viewModel::deleteHistoryRun,
            onDismiss = viewModel::dismissHistorySheet,
            servers = uiState.servers,
            onLoadGroupToSession = viewModel::addHistoryServerGroupToSession,
        )
    }

    pendingDeleteServer?.let { server ->
        PulseConfirmDialog(
            onDismissRequest = { pendingDeleteServer = null },
            title = "删除主机配置？",
            message = "将从本地加密库移除「${server.displayName}」(${server.displayAddress}) 及其 TOFU 主机指纹记录，不会影响远程服务器。",
            icon = Icons.Rounded.DeleteOutline,
            tone = PulseDialogTone.DESTRUCTIVE,
            confirmText = "确认删除",
            onConfirm = {
                viewModel.deleteServer(server.id)
                pendingDeleteServer = null
            },
        )
    }

    pendingDeleteCustomPreset?.let { customPreset ->
        PulseConfirmDialog(
            onDismissRequest = { pendingDeleteCustomPreset = null },
            title = "删除自定义脚本？",
            message = "确定删除自定义预设「${customPreset.name}」吗？",
            icon = Icons.Rounded.DeleteOutline,
            tone = PulseDialogTone.DESTRUCTIVE,
            confirmText = "删除",
            onConfirm = {
                viewModel.deleteCustomPreset(customPreset.id)
                pendingDeleteCustomPreset = null
            },
        )
    }
}

@Composable
private fun LiteSshTopBar(
    recentRunCount: Int,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onAddServer: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseGlassSurface(showBottomBorder = true)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "轻量 SSH · 一键测机",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                PulseAccentPill(
                    text = "ANSI",
                    icon = Icons.Rounded.Terminal,
                )
            }
            Text(
                text = "Keystore 本地加密 · TOFU 指纹校验 · 一键发测评",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onOpenHistory) {
            Box(contentAlignment = Alignment.TopEnd) {
                Icon(
                    imageVector = Icons.Rounded.History,
                    contentDescription = "历史测试记录",
                )
                if (recentRunCount > 0) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        IconButton(onClick = onAddServer) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = "添加主机",
            )
        }
    }
}

@Composable
private fun ServerVaultSection(
    servers: List<SshServerProfile>,
    filteredServers: List<SshServerProfile>,
    inlineVisibleServers: List<SshServerProfile>,
    selectedServer: SshServerProfile?,
    searchQuery: String,
    groupOptions: List<SshServerGroupOption>,
    selectedGroupKey: String,
    isExpanded: Boolean,
    isRunning: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSelectGroup: (String) -> Unit,
    onToggleExpanded: () -> Unit,
    onOpenQuickPicker: () -> Unit,
    onSelectServer: (String) -> Unit,
    onAddServer: () -> Unit,
    onEditServer: (SshServerProfile) -> Unit,
    onDeleteServer: (SshServerProfile) -> Unit,
) {
    val serversById = remember(servers) { servers.associateBy { it.id } }
    val isFiltering = searchQuery.isNotBlank() || selectedGroupKey != SshServerGroupOption.KEY_ALL

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PulseSectionHeader(
            title = "主机保险库",
            subtitle = when {
                servers.isEmpty() -> "零云端同步 · AES-256-GCM 硬件密钥保护"
                isFiltering -> "命中 ${filteredServers.size} / ${servers.size} 台 VPS · 当前目标置顶"
                else -> "已保存 ${servers.size} 台 VPS · 当前目标置顶"
            },
            icon = Icons.Rounded.Dns,
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (servers.size > SshVaultCatalogFilter.DEFAULT_COLLAPSED_SERVER_COUNT) {
                        TextButton(
                            onClick = onOpenQuickPicker,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "快选 (${servers.size})",
                                style = MaterialTheme.typography.labelLarge.withTabularNumbers(),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                    TextButton(
                        onClick = onAddServer,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("添加主机", maxLines = 1, softWrap = false)
                    }
                }
            },
        )

        if (servers.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "连接你的第一台 VPS",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "一键运行 YABS / NodeQuality / NextTrace，自动清洗进度条与 IP 打码后发帖",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(
                    onClick = onAddServer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加 SSH 主机")
                }
            }
        } else {
            // Instant search box (alias / IP / domain / username / group tag + 1-tap clear)
            SshCompactSearchBar(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                onClear = onClearSearch,
                placeholder = "搜索主机别名 / IP / 用户名 / 分组标签…",
                matchCountSummary = if (isFiltering) "${filteredServers.size}/${servers.size}" else null,
            )

            // Group filter chips (All + each group with count) when groups exist
            if (groupOptions.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(groupOptions, key = { "vault_grp_${it.key}" }) { option ->
                        val active = option.key.equals(selectedGroupKey, ignoreCase = true)
                        FilterChip(
                            selected = active,
                            onClick = {
                                onSelectGroup(
                                    if (active && option.key != SshServerGroupOption.KEY_ALL) {
                                        SshServerGroupOption.KEY_ALL
                                    } else {
                                        option.key
                                    },
                                )
                            },
                            label = {
                                Text(
                                    text = "${option.label} (${option.count})",
                                    style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            }

            // Always surface the active target host even when hidden by an active search/group filter
            if (selectedServer != null && filteredServers.none { it.id == selectedServer.id }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f))
                        .border(
                            width = 0.9.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PulseAccentPill(text = "当前目标")
                        Text(
                            text = "${selectedServer.displayName} (${selectedServer.displayAddress})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = "重置筛选",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                onClearSearch()
                                onSelectGroup(SshServerGroupOption.KEY_ALL)
                            }
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }

            val hasMoreThanCollapsed = filteredServers.size > SshVaultCatalogFilter.DEFAULT_COLLAPSED_SERVER_COUNT
            val showScrollableExpanded = hasMoreThanCollapsed && (isExpanded || searchQuery.isNotBlank())

            if (filteredServers.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseCard(shape = RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "未找到匹配的 VPS 主机",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            onClearSearch()
                            onSelectGroup(SshServerGroupOption.KEY_ALL)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("清除筛选", style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else if (showScrollableExpanded) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 288.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(filteredServers, key = { it.id }) { server ->
                        val jumpHostName = server.jumpHostId?.let { serversById[it]?.displayName }
                        ServerVaultCompactRow(
                            server = server,
                            selected = server.id == selectedServer?.id,
                            enabled = !isRunning,
                            jumpHostName = jumpHostName,
                            onSelect = { onSelectServer(server.id) },
                            onEdit = { onEditServer(server) },
                            onDelete = { onDeleteServer(server) },
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    inlineVisibleServers.forEach { server ->
                        val jumpHostName = server.jumpHostId?.let { serversById[it]?.displayName }
                        ServerVaultCompactRow(
                            server = server,
                            selected = server.id == selectedServer?.id,
                            enabled = !isRunning,
                            jumpHostName = jumpHostName,
                            onSelect = { onSelectServer(server.id) },
                            onEdit = { onEditServer(server) },
                            onDelete = { onDeleteServer(server) },
                        )
                    }
                }
            }

            if (hasMoreThanCollapsed || servers.size > SshVaultCatalogFilter.DEFAULT_COLLAPSED_SERVER_COUNT) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (hasMoreThanCollapsed && searchQuery.isBlank()) {
                        TextButton(
                            onClick = onToggleExpanded,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (isExpanded) {
                                    "收起主机列表"
                                } else {
                                    "展开全部（共 ${filteredServers.size} 台）"
                                },
                                style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                            )
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    FilledTonalButton(
                        onClick = onOpenQuickPicker,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "快速检索抽屉 (${servers.size})",
                            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerVaultCompactRow(
    server: SshServerProfile,
    selected: Boolean,
    enabled: Boolean,
    jumpHostName: String? = null,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                borderColor = borderColor,
                borderWidth = if (selected) 1.3.dp else 0.8.dp,
            )
            .pulseBouncyClickable(enabled = enabled, role = Role.Button, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = server.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (selected) {
                    PulseAccentPill(text = "当前目标")
                }
                server.resolvedGroup?.let { groupLabel ->
                    PulseAccentPill(
                        text = groupLabel,
                        icon = Icons.Rounded.LocalOffer,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = server.displayAddress,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                PulseAccentPill(
                    text = if (server.authMethod == SshAuthMethod.PRIVATE_KEY) "私钥" else "密码",
                    icon = if (server.authMethod == SshAuthMethod.PRIVATE_KEY) Icons.Rounded.Key else Icons.Rounded.Password,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (server.hasTrustedHostKey) {
                    PulseAccentPill(
                        text = "TOFU 已校验",
                        icon = Icons.Rounded.VerifiedUser,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    PulseAccentPill(
                        text = "首次校验",
                        icon = Icons.Rounded.Lock,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (server.usesJumpHost) {
                    PulseAccentPill(
                        text = if (!jumpHostName.isNullOrBlank()) "跳板 · $jumpHostName" else "跳板",
                        icon = Icons.AutoMirrored.Rounded.AltRoute,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(
                onClick = onEdit,
                enabled = enabled,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = "编辑主机",
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onDelete,
                enabled = enabled,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "删除主机",
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PresetCatalogSection(
    allPresets: List<SshScriptPreset>,
    filteredPresets: List<SshScriptPreset>,
    inlineVisiblePresets: List<SshScriptPreset>,
    customPresets: List<CustomSshPreset>,
    selectedPreset: SshScriptPreset,
    searchQuery: String,
    filterOptions: List<SshPresetFilterOption>,
    selectedFilter: SshPresetFilter,
    isExpanded: Boolean,
    enabledFlagIds: Set<String>,
    resolvedCommand: String,
    selectedServer: SshServerProfile?,
    isRunning: Boolean,
    elapsedMillis: Long,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSelectFilter: (SshPresetFilter) -> Unit,
    onToggleExpanded: () -> Unit,
    onOpenQuickPicker: () -> Unit,
    onSelectPreset: (String) -> Unit,
    onToggleFlag: (String) -> Unit,
    onAddCustomPreset: () -> Unit,
    onEditCustomPreset: (CustomSshPreset) -> Unit,
    onDeleteCustomPreset: (CustomSshPreset) -> Unit,
    onRunPreset: () -> Unit,
    onStopExecution: () -> Unit,
) {
    var showCommandPreview by rememberSaveable { mutableStateOf(false) }
    val builtInCount = remember(allPresets) { allPresets.count { !it.isCustom } }
    val customCount = customPresets.size
    val isFiltering = searchQuery.isNotBlank() || selectedFilter != SshPresetFilter.ALL

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PulseSectionHeader(
            title = "一键测评脚本",
            subtitle = if (customCount > 0) {
                "内置 $builtInCount 套 + 自定义 $customCount 个 · 支持离开页面后台运行"
            } else {
                "内置 $builtInCount 套免交互 VPS 测机脚本 · 支持离开页面后台运行"
            },
            icon = Icons.Rounded.Bolt,
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    TextButton(
                        onClick = onOpenQuickPicker,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = "快选 (${allPresets.size})",
                            style = MaterialTheme.typography.labelLarge.withTabularNumbers(),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    TextButton(
                        onClick = onAddCustomPreset,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("自定义", maxLines = 1, softWrap = false)
                    }
                }
            },
        )

        // Instant search box (script name / description / command keyword)
        SshCompactSearchBar(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            onClear = onClearSearch,
            placeholder = "搜索脚本名称 / 功能描述 / 命令关键字…",
            matchCountSummary = if (isFiltering) "${filteredPresets.size}/${allPresets.size}" else null,
        )

        // Category / source filter chips (All / Custom / Benchmark / Network / Unlock / Diagnostic)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(filterOptions, key = { "preset_flt_${it.filter.name}" }) { option ->
                val active = option.filter == selectedFilter
                FilterChip(
                    selected = active,
                    onClick = {
                        onSelectFilter(
                            if (active && option.filter != SshPresetFilter.ALL) {
                                SshPresetFilter.ALL
                            } else {
                                option.filter
                            },
                        )
                    },
                    label = {
                        Text(
                            text = "${option.label} (${option.count})",
                            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                            maxLines = 1,
                        )
                    },
                )
            }
        }

        val hasMoreThanCollapsed = filteredPresets.size > SshVaultCatalogFilter.DEFAULT_COLLAPSED_PRESET_COUNT
        val showScrollableExpanded = hasMoreThanCollapsed && (isExpanded || searchQuery.isNotBlank())

        if (filteredPresets.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(shape = RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (selectedFilter == SshPresetFilter.CUSTOM && customPresets.isEmpty()) {
                        "暂未添加自定义测机脚本"
                    } else {
                        "未找到匹配的测评脚本"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (selectedFilter == SshPresetFilter.CUSTOM && customPresets.isEmpty()) {
                    TextButton(
                        onClick = onAddCustomPreset,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("新建自定义", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    TextButton(
                        onClick = {
                            onClearSearch()
                            onSelectFilter(SshPresetFilter.ALL)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("清空筛选", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        } else if (showScrollableExpanded) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 256.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(filteredPresets, key = { it.id }) { preset ->
                    PresetCompactSelectorRow(
                        preset = preset,
                        selected = preset.id == selectedPreset.id,
                        enabled = !isRunning,
                        onClick = { onSelectPreset(preset.id) },
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                inlineVisiblePresets.forEach { preset ->
                    PresetCompactSelectorRow(
                        preset = preset,
                        selected = preset.id == selectedPreset.id,
                        enabled = !isRunning,
                        onClick = { onSelectPreset(preset.id) },
                    )
                }
            }
        }

        if (hasMoreThanCollapsed || allPresets.size > SshVaultCatalogFilter.DEFAULT_COLLAPSED_PRESET_COUNT) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (hasMoreThanCollapsed && searchQuery.isBlank()) {
                    TextButton(
                        onClick = onToggleExpanded,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (isExpanded) {
                                "收起脚本列表"
                            } else {
                                "展开全部（共 ${filteredPresets.size} 个）"
                            },
                            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                        )
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }

                FilledTonalButton(
                    onClick = onOpenQuickPicker,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "快速选择面板 (${allPresets.size})",
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        // Selected Preset Execution Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseCard(shape = PulseArchitecturalShapes.EditorialCard)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = selectedPreset.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    PulseAccentPill(
                        text = selectedPreset.estimatedDurationLabel,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }

                if (selectedPreset.isCustom) {
                    val customObj = customPresets.firstOrNull { it.id == selectedPreset.id }
                    if (customObj != null) {
                        Row {
                            IconButton(
                                onClick = { onEditCustomPreset(customObj) },
                                enabled = !isRunning,
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(Icons.Rounded.Edit, contentDescription = "编辑脚本", modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = { onDeleteCustomPreset(customObj) },
                                enabled = !isRunning,
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除脚本", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            Text(
                text = selectedPreset.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Optional CLI flag toggles (e.g., YABS -5 / -f / -i / -g)
            if (selectedPreset.flags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    selectedPreset.flags.forEach { flag ->
                        val active = flag.id in enabledFlagIds
                        FilterChip(
                            selected = active,
                            enabled = !isRunning,
                            onClick = { onToggleFlag(flag.id) },
                            label = {
                                Text(
                                    text = flag.label,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                        )
                    }
                }
            }

            // Collapsible command preview
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showCommandPreview = !showCommandPreview }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "查看实际执行的非交互命令",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    imageVector = if (showCommandPreview) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }

            AnimatedVisibility(visible = showCommandPreview) {
                val ext = NodePulseThemeTokens.extendedColors
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(ext.codeSurface)
                        .border(0.8.dp, ext.codeBorder, RoundedCornerShape(10.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = resolvedCommand,
                        style = NodePulseThemeTokens.extraTypography.codeLabel.copy(fontSize = 11.5.sp),
                        color = ext.onCodeSurface,
                    )
                }
            }

            // Primary Run / Stop CTA
            if (isRunning) {
                Button(
                    onClick = onStopExecution,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError,
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "立即停止并断开 · ${formatElapsedClock(elapsedMillis)}",
                        style = MaterialTheme.typography.labelLarge.withTabularNumbers(),
                    )
                }
            } else {
                Button(
                    onClick = onRunPreset,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (selectedServer != null) {
                            "在「${selectedServer.displayName}」一键运行"
                        } else {
                            "添加主机并开始测机"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetCompactSelectorRow(
    preset: SshScriptPreset,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = shape,
                borderColor = borderColor,
                borderWidth = if (selected) 1.3.dp else 0.8.dp,
            )
            .pulseBouncyClickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when {
                    selected -> Icons.Rounded.Check
                    preset.isCustom -> Icons.Rounded.Code
                    else -> Icons.Rounded.Terminal
                },
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(13.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                PulseAccentPill(
                    text = if (preset.isCustom && preset.category == SshPresetCategory.CUSTOM) {
                        "自定义"
                    } else if (preset.isCustom) {
                        "自定义 · ${preset.category.label}"
                    } else {
                        preset.category.label
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = preset.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        PulseAccentPill(
            text = preset.estimatedDurationLabel,
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
        )
    }
}

@Composable
private fun SessionRunsCollectorStrip(
    sessionRuns: List<SshExecutionRecord>,
    selectedForExportIds: Set<String>,
    viewingRunId: String?,
    isRunning: Boolean,
    onViewRun: (String?) -> Unit,
    onToggleRunForExport: (String) -> Unit,
    onRemoveRun: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    val selectedCount = sessionRuns.count { it.id in selectedForExportIds && it.hasOutput }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tab,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = if (selectedCount >= 2) {
                        "本次已选 $selectedCount 项（将自动合并为 :::: tabs）"
                    } else {
                        "本次会话记录（勾选多项可合并多 Tab 测评）"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!isRunning) {
                TextButton(
                    onClick = onClearAll,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "清空",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessionRuns, key = { it.id }) { run ->
                val isChecked = run.id in selectedForExportIds
                val isViewing = run.id == viewingRunId
                val shape = RoundedCornerShape(12.dp)
                val borderColor = when {
                    isViewing -> MaterialTheme.colorScheme.primary
                    isChecked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                }

                Row(
                    modifier = Modifier
                        .clip(shape)
                        .background(
                            if (isViewing) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            } else {
                                NodePulseThemeTokens.extendedColors.cardSurface
                            },
                        )
                        .border(if (isViewing) 1.4.dp else 1.dp, borderColor, shape)
                        .clickable { onViewRun(run.id) }
                        .padding(start = 6.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(
                        onClick = { onToggleRunForExport(run.id) },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            imageVector = if (isChecked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                            contentDescription = "勾选导出",
                            tint = if (isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    Column {
                        Text(
                            text = run.presetName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${formatDurationShort(run.durationMillis)} · ${runStatusLabel(run.status, run.exitCode)}",
                            style = NodePulseThemeTokens.extraTypography.microLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { onRemoveRun(run.id) },
                        modifier = Modifier.size(22.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "移除此记录",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnsiTerminalConsoleCard(
    consoleStreamHandle: SshConsoleStreamHandle?,
    staticSourceKey: String,
    staticAnsiOutput: String,
    serverHostForMask: String?,
    hasCopyableOutput: Boolean,
    connectionPhase: SshConnectionPhase,
    isRunning: Boolean,
    activeTitle: String,
    elapsedMillis: Long,
    exitCode: Int?,
    errorMessage: String?,
    liveProgress: com.nodeseek.app.domain.ssh.SshLiveProgressStatus?,
    lastOutputEpochMillis: Long?,
    autoRespondPrompts: Boolean,
    autoRespondCount: Int,
    maskServerIp: Boolean,
    consoleFontScale: Float,
    terminalAutoFitEnabled: Boolean,
    autoScrollConsole: Boolean,
    onToggleMaskIp: (Boolean) -> Unit,
    onToggleAutoRespondPrompts: (Boolean) -> Unit,
    onToggleAutoFit: (Boolean) -> Unit,
    onZoomOut: (effectiveScale: Float) -> Unit,
    onZoomIn: (effectiveScale: Float) -> Unit,
    onPinchZoom: (zoomFactor: Float, effectiveScale: Float) -> Unit,
    onResetZoom: () -> Unit,
    onPtySizeChanged: ((SshPtySize) -> Unit)?,
    onToggleAutoScroll: (Boolean) -> Unit,
    onCopyOutput: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val codeTypography = NodePulseThemeTokens.extraTypography
    var layoutSolution by remember { mutableStateOf<TerminalLayoutSolution?>(null) }

    val statusColor = when (connectionPhase) {
        SshConnectionPhase.IDLE -> ext.onCodeMuted
        SshConnectionPhase.CONNECTING -> ObsidianSecondary
        SshConnectionPhase.RUNNING -> ObsidianPrimary
        SshConnectionPhase.COMPLETED -> if (exitCode == 0 || exitCode == null) ObsidianPrimary else ObsidianSecondary
        SshConnectionPhase.FAILED -> MaterialTheme.colorScheme.error
        SshConnectionPhase.CANCELLED -> ObsidianSecondary
    }

    val maskBg by animateColorAsState(
        targetValue = if (maskServerIp) ObsidianPrimaryContainer.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.06f),
        label = "maskBg",
    )
    val maskFg by animateColorAsState(
        targetValue = if (maskServerIp) ObsidianOnPrimaryContainer else ext.onCodeMuted,
        label = "maskFg",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PulseArchitecturalShapes.CodeBlock)
            .background(ext.codeSurface)
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), ext.codeBorder)),
                shape = PulseArchitecturalShapes.CodeBlock,
            ),
    ) {
        // Window chrome header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ext.codeHeaderSurface)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint = ext.onCodeMuted,
                    modifier = Modifier.size(15.dp),
                )
                ConsoleStatusLed(
                    isRunning = isRunning,
                    statusColor = statusColor,
                )
                Text(
                    text = activeTitle,
                    style = codeTypography.codeLabel,
                    color = ext.onCodeSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (elapsedMillis > 0L || isRunning) {
                    Text(
                        text = formatElapsedClock(elapsedMillis),
                        style = codeTypography.codeLabel.withTabularNumbers(),
                        color = statusColor,
                    )
                }
                val badgeText = when {
                    exitCode != null -> "EXIT $exitCode"
                    else -> connectionPhase.label
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(statusColor.copy(alpha = 0.16f))
                        .border(0.6.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = badgeText,
                        style = codeTypography.microLabel,
                        color = statusColor,
                    )
                }
            }
        }

        // Interactive console controls strip (IP privacy toggle, font zoom, auto-scroll, copy)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ext.codeHeaderSurface.copy(alpha = 0.72f))
                .border(0.5.dp, Color.White.copy(alpha = 0.06f))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // IP Privacy Mask Toggle (default ON)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(maskBg)
                    .border(0.7.dp, maskFg.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                    .clickable { onToggleMaskIp(!maskServerIp) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = maskFg,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = if (maskServerIp) "自动打码 IP: 开" else "自动打码 IP: 关",
                    style = codeTypography.microLabel,
                    color = maskFg,
                )
            }

            // Auto-respond interactive prompt fallback toggle (configurable)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (autoRespondPrompts) ObsidianPrimaryContainer.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.06f),
                    )
                    .clickable { onToggleAutoRespondPrompts(!autoRespondPrompts) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = if (autoRespondPrompts) ObsidianOnPrimaryContainer else ext.onCodeMuted,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = when {
                        autoRespondPrompts && autoRespondCount > 0 -> "自动应答: 开 ($autoRespondCount)"
                        autoRespondPrompts -> "自动应答: 开"
                        else -> "自动应答: 关"
                    },
                    style = codeTypography.microLabel,
                    color = if (autoRespondPrompts) ObsidianOnPrimaryContainer else ext.onCodeMuted,
                )
            }

            // Auto-fit / fixed-font toggle + zoom stepper + reset + PTY readout
            val displayScale = layoutSolution?.effectiveFontScale ?: consoleFontScale
            val isDefaultZoomState = terminalAutoFitEnabled &&
                kotlin.math.abs(consoleFontScale - LiteSshUiState.DEFAULT_CONSOLE_FONT_SCALE) <= 0.01f

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (terminalAutoFitEnabled) ObsidianPrimaryContainer.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.06f),
                    )
                    .border(
                        width = 0.7.dp,
                        color = if (terminalAutoFitEnabled) {
                            ObsidianOnPrimaryContainer.copy(alpha = 0.35f)
                        } else {
                            Color.White.copy(alpha = 0.10f)
                        },
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable { onToggleAutoFit(!terminalAutoFitEnabled) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.FitScreen,
                    contentDescription = null,
                    tint = if (terminalAutoFitEnabled) ObsidianOnPrimaryContainer else ext.onCodeMuted,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = if (terminalAutoFitEnabled) "自动适配" else "固定字号",
                    style = codeTypography.microLabel,
                    color = if (terminalAutoFitEnabled) ObsidianOnPrimaryContainer else ext.onCodeMuted,
                )
            }

            // Font zoom controls + reset
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(0.6.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ZoomOut,
                    contentDescription = "缩小字体",
                    tint = ext.onCodeSurface,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onZoomOut(displayScale) }
                        .padding(3.dp)
                        .size(14.dp),
                )
                Text(
                    text = "${(displayScale * 100f).roundToInt()}%",
                    style = codeTypography.microLabel.withTabularNumbers(),
                    color = ext.onCodeSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onResetZoom)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
                Icon(
                    imageVector = Icons.Rounded.ZoomIn,
                    contentDescription = "放大字体",
                    tint = ext.onCodeSurface,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onZoomIn(displayScale) }
                        .padding(3.dp)
                        .size(14.dp),
                )
                if (!isDefaultZoomState) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable(onClick = onResetZoom)
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RestartAlt,
                            contentDescription = "重置缩放",
                            tint = ext.onCodeSurface,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = "重置",
                            style = codeTypography.microLabel,
                            color = ext.onCodeSurface,
                        )
                    }
                }
            }

            layoutSolution?.let { solved ->
                val ptyText = if (solved.requiresHorizontalScroll) {
                    "${solved.ptySize.cols}×${solved.ptySize.rows} · 横滑"
                } else {
                    "${solved.ptySize.cols}×${solved.ptySize.rows}"
                }
                Text(
                    text = ptyText,
                    style = codeTypography.microLabel.withTabularNumbers(),
                    color = if (solved.hitMinReadableFloor && solved.requiresHorizontalScroll) {
                        ObsidianSecondary
                    } else {
                        ext.onCodeMuted
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }

            // Auto-scroll toggle
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (autoScrollConsole) ObsidianPrimaryContainer.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.06f),
                    )
                    .clickable { onToggleAutoScroll(!autoScrollConsole) }
                    .padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.VerticalAlignBottom,
                    contentDescription = null,
                    tint = if (autoScrollConsole) ObsidianOnPrimaryContainer else ext.onCodeMuted,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "跟随滚动",
                    style = codeTypography.microLabel,
                    color = if (autoScrollConsole) ObsidianOnPrimaryContainer else ext.onCodeMuted,
                )
            }

            // Copy plain output
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable(enabled = hasCopyableOutput, onClick = onCopyOutput)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    tint = if (hasCopyableOutput) ext.onCodeSurface else ext.onCodeMuted.copy(alpha = 0.45f),
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "复制输出",
                    style = codeTypography.microLabel,
                    color = if (hasCopyableOutput) ext.onCodeSurface else ext.onCodeMuted.copy(alpha = 0.45f),
                )
            }
        }

        // Live stage, last-output timestamp & long-silence reassurance banner while running
        if (isRunning && liveProgress != null) {
            val silenceSeconds = (liveProgress.silenceMillis / 1000L).coerceAtLeast(0L)
            val lastOutputText = remember(lastOutputEpochMillis, silenceSeconds) {
                when {
                    lastOutputEpochMillis == null -> "等待远程首包输出"
                    silenceSeconds < 3L -> "刚刚 (${formatClockTimeOfDay(lastOutputEpochMillis)})"
                    else -> "${silenceSeconds} 秒前 (${formatClockTimeOfDay(lastOutputEpochMillis)})"
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (liveProgress.isSilentLong) {
                            ObsidianSecondary.copy(alpha = 0.14f)
                        } else {
                            ext.codeHeaderSurface.copy(alpha = 0.50f)
                        },
                    )
                    .border(
                        width = 0.6.dp,
                        color = if (liveProgress.isSilentLong) {
                            ObsidianSecondary.copy(alpha = 0.40f)
                        } else {
                            Color.White.copy(alpha = 0.06f)
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.6.dp,
                            color = if (liveProgress.isSilentLong) ObsidianSecondary else ObsidianPrimary,
                        )
                        Text(
                            text = "当前阶段：${liveProgress.currentStageLabel}",
                            style = codeTypography.microLabel,
                            color = ext.onCodeSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = "已运行 ${formatElapsedClock(elapsedMillis)} · 最近输出 $lastOutputText",
                        style = codeTypography.microLabel.withTabularNumbers(),
                        color = ext.onCodeMuted,
                        maxLines = 1,
                    )
                }

                if (liveProgress.isSilentLong) {
                    Text(
                        text = liveProgress.silentRunningBannerText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ObsidianSecondary,
                    )
                    if (liveProgress.stageDetailHint.isNotBlank()) {
                        Text(
                            text = "${liveProgress.stageDetailHint}（已静默 ${silenceSeconds} 秒，离开页面亦可在后台继续运行）",
                            style = codeTypography.microLabel,
                            color = ext.onCodeMuted,
                        )
                    }
                }
            }
        }

        // Error banner if present
        if (!errorMessage.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ObsidianErrorContainer.copy(alpha = 0.65f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = ObsidianOnErrorContainer,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = ObsidianOnErrorContainer,
                )
            }
        }

        // Virtualized per-line terminal viewport (only collects stream when visible in LazyColumn)
        AnsiTerminalViewport(
            consoleStreamHandle = consoleStreamHandle,
            staticSourceKey = staticSourceKey,
            staticAnsiOutput = staticAnsiOutput,
            serverHostForMask = serverHostForMask,
            maskServerIp = maskServerIp,
            isRunning = isRunning,
            consoleFontScale = consoleFontScale,
            terminalAutoFitEnabled = terminalAutoFitEnabled,
            autoScrollConsole = autoScrollConsole,
            onPinchZoom = onPinchZoom,
            onPtySizeChanged = onPtySizeChanged,
            onLayoutSolved = { layoutSolution = it },
        )
    }
}

/**
 * Draw-phase-only pulsing LED indicator so 60–120fps alpha frames never recompose the console card,
 * and no choreographer animation runs while idle.
 */
@Composable
private fun ConsoleStatusLed(
    isRunning: Boolean,
    statusColor: Color,
) {
    if (isRunning) {
        val pulseTransition = rememberInfiniteTransition(label = "sshLedPulse")
        val runningLedAlphaState = pulseTransition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "sshLedAlpha",
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .graphicsLayer { alpha = runningLedAlphaState.value }
                .clip(CircleShape)
                .background(statusColor),
        )
    } else {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(statusColor),
        )
    }
}

private const val IMMEDIATE_PARSE_LINE_BUDGET = 120
private const val QUICK_PREVIEW_TAIL_LINES = 48
private const val PTY_RESIZE_DEBOUNCE_MS = 180L

@Composable
private fun AnsiTerminalViewport(
    consoleStreamHandle: SshConsoleStreamHandle?,
    staticSourceKey: String,
    staticAnsiOutput: String,
    serverHostForMask: String?,
    maskServerIp: Boolean,
    isRunning: Boolean,
    consoleFontScale: Float,
    terminalAutoFitEnabled: Boolean,
    autoScrollConsole: Boolean,
    onPinchZoom: (zoomFactor: Float, effectiveScale: Float) -> Unit,
    onPtySizeChanged: ((SshPtySize) -> Unit)?,
    onLayoutSolved: (TerminalLayoutSolution) -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val codeTypography = NodePulseThemeTokens.extraTypography
    val isInspection = LocalInspectionMode.current
    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val textMeasurer = rememberTextMeasurer()
    val consoleListState = rememberLazyListState()
    val horizontalScroll = rememberScrollState()
    val liveConsoleBuffer = remember { SshTerminalConsoleBuffer() }

    val (charWidthPxPerSp, lineHeightPxPerSp) = remember(
        textMeasurer,
        codeTypography.codeBody,
        density.density,
        density.fontScale,
    ) {
        measureTerminalFontUnitMetricsPx(
            textMeasurer = textMeasurer,
            baseStyle = codeTypography.codeBody,
            density = density,
            lineHeightRatio = TerminalFontSpec.DEFAULT_LINE_HEIGHT_RATIO,
        )
    }

    val minViewportHeight = remember(screenHeightDp) {
        if (screenHeightDp in 1..520) 180.dp else 240.dp
    }
    val maxViewportHeight = remember(screenHeightDp) {
        if (screenHeightDp in 1..520) {
            (screenHeightDp * 0.62f).dp.coerceIn(200.dp, 360.dp)
        } else {
            420.dp
        }
    }

    val streamState = consoleStreamHandle?.flow?.collectAsStateWithLifecycle()?.value
    val effectiveHost = if (streamState != null && !streamState.isEmpty) {
        streamState.serverHost ?: serverHostForMask
    } else {
        serverHostForMask
    }

    var asyncBackfillTag by remember { mutableStateOf("") }
    var asyncBackfillOutput by remember { mutableStateOf(TerminalRenderOutput()) }

    val renderOutput: TerminalRenderOutput = when {
        streamState != null && !streamState.isEmpty -> {
            val uncachedWork = liveConsoleBuffer.estimatedUncachedWork(
                runGeneration = streamState.runGeneration,
                snapshot = streamState.bufferSnapshot,
                maskIp = maskServerIp,
                knownServerHost = effectiveHost,
                defaultForeground = ext.onCodeSurface,
            )
            val targetTag = "live_${streamState.runGeneration}_${streamState.bufferSnapshot.version}_${maskServerIp}_$effectiveHost"
            val offloadToBackground = !isInspection && uncachedWork > IMMEDIATE_PARSE_LINE_BUDGET

            LaunchedEffect(offloadToBackground, targetTag, ext.onCodeSurface) {
                if (offloadToBackground) {
                    val full = withContext(Dispatchers.Default) {
                        liveConsoleBuffer.renderSnapshotWithMetrics(
                            runGeneration = streamState.runGeneration,
                            snapshot = streamState.bufferSnapshot,
                            maskIp = maskServerIp,
                            knownServerHost = effectiveHost,
                            defaultForeground = ext.onCodeSurface,
                        )
                    }
                    asyncBackfillOutput = full
                    asyncBackfillTag = targetTag
                }
            }

            if (!offloadToBackground) {
                remember(
                    streamState.runGeneration,
                    streamState.bufferSnapshot.version,
                    maskServerIp,
                    effectiveHost,
                    ext.onCodeSurface,
                ) {
                    liveConsoleBuffer.renderSnapshotWithMetrics(
                        runGeneration = streamState.runGeneration,
                        snapshot = streamState.bufferSnapshot,
                        maskIp = maskServerIp,
                        knownServerHost = effectiveHost,
                        defaultForeground = ext.onCodeSurface,
                    )
                }
            } else if (asyncBackfillTag == targetTag && asyncBackfillOutput.lines.isNotEmpty()) {
                asyncBackfillOutput
            } else {
                remember(
                    streamState.runGeneration,
                    streamState.bufferSnapshot.version,
                    maskServerIp,
                    effectiveHost,
                    ext.onCodeSurface,
                ) {
                    SshTerminalConsoleBuffer.renderQuickTailPreviewWithMetrics(
                        snapshot = streamState.bufferSnapshot,
                        maskIp = maskServerIp,
                        knownServerHost = effectiveHost,
                        defaultForeground = ext.onCodeSurface,
                        tailLines = QUICK_PREVIEW_TAIL_LINES,
                    )
                }
            }
        }

        staticAnsiOutput.isNotEmpty() -> {
            val isLargeStatic = !isInspection && staticAnsiOutput.length > 16_000
            val staticTag = "static_${staticSourceKey}_${staticAnsiOutput.length}_${staticAnsiOutput.hashCode()}_${maskServerIp}_$effectiveHost"

            LaunchedEffect(isLargeStatic, staticTag, ext.onCodeSurface) {
                if (isLargeStatic) {
                    val full = withContext(Dispatchers.Default) {
                        SshTerminalConsoleBuffer.renderStaticTranscriptWithMetrics(
                            sourceKey = staticSourceKey,
                            sanitizedAnsiText = staticAnsiOutput,
                            maskIp = maskServerIp,
                            knownServerHost = effectiveHost,
                            defaultForeground = ext.onCodeSurface,
                        )
                    }
                    asyncBackfillOutput = full
                    asyncBackfillTag = staticTag
                }
            }

            if (!isLargeStatic) {
                remember(staticSourceKey, staticAnsiOutput, maskServerIp, effectiveHost, ext.onCodeSurface) {
                    SshTerminalConsoleBuffer.renderStaticTranscriptWithMetrics(
                        sourceKey = staticSourceKey,
                        sanitizedAnsiText = staticAnsiOutput,
                        maskIp = maskServerIp,
                        knownServerHost = effectiveHost,
                        defaultForeground = ext.onCodeSurface,
                    )
                }
            } else if (asyncBackfillTag == staticTag && asyncBackfillOutput.lines.isNotEmpty()) {
                asyncBackfillOutput
            } else {
                remember(staticTag, ext.onCodeSurface) {
                    val tailChars = staticAnsiOutput.takeLast(6_000)
                    SshTerminalConsoleBuffer.renderStaticTranscriptWithMetrics(
                        sourceKey = "${staticSourceKey}_tail",
                        sanitizedAnsiText = tailChars,
                        maskIp = maskServerIp,
                        knownServerHost = effectiveHost,
                        defaultForeground = ext.onCodeSurface,
                        maxLines = QUICK_PREVIEW_TAIL_LINES,
                    )
                }
            }
        }

        else -> TerminalRenderOutput()
    }

    val renderedLines = renderOutput.lines
    val lastLineId = renderedLines.lastOrNull()?.id ?: -1L
    LaunchedEffect(renderedLines.size, lastLineId, autoScrollConsole) {
        if (autoScrollConsole && !consoleListState.isScrollInProgress && renderedLines.isNotEmpty()) {
            consoleListState.scrollToItem(renderedLines.lastIndex)
        }
    }

    val fitMode = if (terminalAutoFitEnabled) TerminalFitMode.AUTO_FIT else TerminalFitMode.FIXED

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val minRowWidth = maxWidth
        val horizontalPaddingPx = with(density) { 24.dp.toPx() }
        val verticalPaddingPx = with(density) { 24.dp.toPx() }
        val maxHeightPx = if (constraints.hasBoundedHeight) {
            minOf(constraints.maxHeight.toFloat(), with(density) { maxViewportHeight.toPx() })
        } else {
            with(density) { maxViewportHeight.toPx() }
        }
        val availableWidthPx = (constraints.maxWidth.toFloat() - horizontalPaddingPx).coerceAtLeast(1f)
        val availableHeightPx = (maxHeightPx - verticalPaddingPx).coerceAtLeast(1f)

        val layoutSolution = remember(
            availableWidthPx,
            availableHeightPx,
            charWidthPxPerSp,
            lineHeightPxPerSp,
            renderOutput.maxLineColumns,
            fitMode,
            consoleFontScale,
        ) {
            SshTerminalAutoFit.solveLayout(
                viewportWidthPx = availableWidthPx,
                viewportHeightPx = availableHeightPx,
                charWidthPxPerSp = charWidthPxPerSp,
                lineHeightPxPerSp = lineHeightPxPerSp,
                maxLineColumns = renderOutput.maxLineColumns,
                fitMode = fitMode,
                manualFontScale = consoleFontScale,
            )
        }

        LaunchedEffect(layoutSolution) {
            onLayoutSolved(layoutSolution)
        }

        LaunchedEffect(layoutSolution.ptySize, isRunning, onPtySizeChanged) {
            val callback = onPtySizeChanged ?: return@LaunchedEffect
            if (isRunning) {
                delay(PTY_RESIZE_DEBOUNCE_MS)
            }
            callback(layoutSolution.ptySize)
        }

        LaunchedEffect(layoutSolution.requiresHorizontalScroll) {
            if (!layoutSolution.requiresHorizontalScroll && horizontalScroll.value > 0) {
                horizontalScroll.scrollTo(0)
            }
        }

        val lineTextStyle = remember(
            codeTypography.codeBody,
            layoutSolution.effectiveFontSizeSp,
            layoutSolution.effectiveLineHeightSp,
        ) {
            codeTypography.codeBody.copy(
                fontSize = layoutSolution.effectiveFontSizeSp.sp,
                lineHeight = layoutSolution.effectiveLineHeightSp.sp,
            )
        }

        if (renderedLines.isNotEmpty()) {
            LazyColumn(
                state = consoleListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minViewportHeight, max = maxViewportHeight)
                    .terminalPinchToZoom { zoomFactor ->
                        onPinchZoom(zoomFactor, layoutSolution.effectiveFontScale)
                    }
                    .horizontalScroll(horizontalScroll),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(
                    items = renderedLines,
                    key = { it.id },
                    contentType = { "ansi_line" },
                ) { line ->
                    Text(
                        text = line.styledText,
                        style = lineTextStyle,
                        softWrap = false,
                        minLines = 1,
                        modifier = Modifier
                            .widthIn(min = minRowWidth)
                            .padding(horizontal = 12.dp),
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minViewportHeight)
                    .terminalPinchToZoom { zoomFactor ->
                        onPinchZoom(zoomFactor, layoutSolution.effectiveFontScale)
                    }
                    .padding(horizontal = 12.dp, vertical = 48.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (isRunning) {
                        "$ 正在建立 SSH 加密通道并分配 xterm-256color PTY (${layoutSolution.ptySize.cols}×${layoutSolution.ptySize.rows})..."
                    } else {
                        "$ 选择主机与预设脚本，点击「一键运行」即可实时查看高保真 ANSI 彩色输出"
                    },
                    style = codeTypography.codeBody.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = ext.onCodeMuted,
                )
                Text(
                    text = "# 宽表格自动缩放适配 · 双指捏合可手动缩放 · 进度条 \\r 自动折叠 · 公网 IP 默认打码",
                    style = codeTypography.codeLabel.copy(fontSize = 11.sp),
                    color = ext.onCodeMuted.copy(alpha = 0.72f),
                )
            }
        }
    }
}

@Composable
private fun LiteSshExportDock(
    exportMeta: SshExportSummaryMeta,
    maskServerIp: Boolean,
    onPreview: () -> Unit,
    onCopyMarkdown: () -> Unit,
    onInsertToReply: (() -> Unit)?,
    onPublishToComposer: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseGlassSurface(showTopBorder = true, showBottomBorder = false)
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (exportMeta.usesTabsLayout) {
                    "多标签测评已就绪（:::: tabs · ${exportMeta.runCount} 项）"
                } else {
                    "ANSI 测评代码块已就绪（```ansi）"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            PulseAccentPill(
                text = if (maskServerIp) "IP 已打码" else "原始 IP",
                icon = Icons.Rounded.Shield,
                containerColor = if (maskServerIp) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
                contentColor = if (maskServerIp) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = onPreview,
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
            ) {
                Icon(Icons.Rounded.Preview, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("预览", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }

            OutlinedButton(
                onClick = onCopyMarkdown,
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("复制", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }

            if (onInsertToReply != null) {
                FilledTonalButton(
                    onClick = onInsertToReply,
                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                ) {
                    Text("插入回复", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }

            Button(
                onClick = onPublishToComposer,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (onInsertToReply != null) "发测评" else "一键发测评帖",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun copyTextToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Anode Lite SSH", text))
}

private val CLOCK_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

private fun formatClockTimeOfDay(epochMillis: Long): String {
    if (epochMillis <= 0L) return "--:--:--"
    return runCatching {
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
            .format(CLOCK_TIME_FORMATTER)
    }.getOrDefault("--:--:--")
}

/**
 * Target NodeSeek board slug (`review` 测评版块) for 1-click benchmark review posts.
 */
const val LITE_SSH_REVIEW_CATEGORY_SLUG: String = SshBenchmarkPostFormatter.REVIEW_CATEGORY_SLUG
