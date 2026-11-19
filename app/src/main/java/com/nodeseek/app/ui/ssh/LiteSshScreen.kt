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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
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
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Preview
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import com.nodeseek.app.domain.ssh.SshRunStatus
import com.nodeseek.app.domain.ssh.SshScriptPreset
import com.nodeseek.app.domain.ssh.SshServerProfile
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
import kotlinx.coroutines.launch
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

    val exportPayload = uiState.exportPreviewPost

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
            if (exportPayload != null) {
                LiteSshExportDock(
                    formattedPost = exportPayload,
                    maskServerIp = uiState.maskServerIp,
                    onPreview = viewModel::openExportPreviewSheet,
                    onCopyMarkdown = { copyToClipboard(exportPayload.markdownContent, "已复制 Markdown 测评报告") },
                    onInsertToReply = onInsertToReply?.let { callback ->
                        { callback(exportPayload.markdownContent) }
                    },
                    onPublishToComposer = {
                        onPublishToComposer(exportPayload.suggestedTitle, exportPayload.markdownContent)
                    },
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
                bottom = if (exportPayload != null) 28.dp else 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. Encrypted SSH Server Vault
            item(key = "server_vault_section") {
                ServerVaultSection(
                    servers = uiState.servers,
                    selectedServerId = uiState.selectedServer?.id,
                    isRunning = uiState.isRunning,
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
                    customPresets = uiState.customPresets,
                    selectedPreset = uiState.selectedPreset,
                    enabledFlagIds = uiState.selectedPresetFlagIds,
                    resolvedCommand = uiState.resolvedSelectedCommand,
                    selectedServer = uiState.selectedServer,
                    isRunning = uiState.isRunning,
                    elapsedMillis = uiState.elapsedMillis,
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
                AnsiTerminalConsoleCard(
                    displayedOutput = uiState.displayedConsoleOutput,
                    connectionPhase = uiState.connectionPhase,
                    isRunning = uiState.isRunning,
                    activeTitle = uiState.viewingRun?.let { "${it.serverAlias} · ${it.presetName}" }
                        ?: uiState.activePresetName?.let { preset ->
                            val alias = uiState.activeServerAlias ?: uiState.selectedServer?.displayName
                            if (alias != null) "$alias · $preset" else preset
                        }
                        ?: "ANSI 终端控制台",
                    elapsedMillis = uiState.viewingRun?.durationMillis ?: uiState.elapsedMillis,
                    exitCode = uiState.viewingRun?.exitCode ?: uiState.exitCode,
                    errorMessage = if (uiState.viewingRun == null) uiState.errorMessage else uiState.viewingRun?.errorMessage,
                    maskServerIp = uiState.maskServerIp,
                    consoleFontScale = uiState.consoleFontScale,
                    autoScrollConsole = uiState.autoScrollConsole,
                    onToggleMaskIp = viewModel::toggleMaskServerIp,
                    onZoomOut = { viewModel.adjustConsoleFontScale(-0.10f) },
                    onZoomIn = { viewModel.adjustConsoleFontScale(0.10f) },
                    onResetZoom = viewModel::resetConsoleFontScale,
                    onToggleAutoScroll = viewModel::toggleAutoScrollConsole,
                    onCopyOutput = {
                        val raw = uiState.displayedConsoleOutput
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
    if (uiState.showServerEditor) {
        SshServerEditorDialog(
            existing = uiState.editingServer,
            onDismiss = viewModel::dismissServerEditor,
            onSave = viewModel::saveServer,
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

    if (uiState.showExportPreviewSheet && exportPayload != null) {
        SshExportPreviewBottomSheet(
            formattedPost = exportPayload,
            maskServerIp = uiState.maskServerIp,
            onToggleMaskIp = viewModel::toggleMaskServerIp,
            onCopyMarkdown = { md -> copyToClipboard(md, "已复制 Markdown 测评报告") },
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
    selectedServerId: String?,
    isRunning: Boolean,
    onSelectServer: (String) -> Unit,
    onAddServer: () -> Unit,
    onEditServer: (SshServerProfile) -> Unit,
    onDeleteServer: (SshServerProfile) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PulseSectionHeader(
            title = "主机保险库",
            subtitle = if (servers.isEmpty()) "零云端同步 · AES-256-GCM 硬件密钥保护" else "已保存 ${servers.size} 台 VPS",
            icon = Icons.Rounded.Dns,
            trailing = {
                TextButton(
                    onClick = onAddServer,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("添加主机")
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
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(servers, key = { it.id }) { server ->
                    val selected = server.id == selectedServerId
                    ServerVaultCard(
                        server = server,
                        selected = selected,
                        enabled = !isRunning,
                        onSelect = { onSelectServer(server.id) },
                        onEdit = { onEditServer(server) },
                        onDelete = { onDeleteServer(server) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerVaultCard(
    server: SshServerProfile,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else null

    Column(
        modifier = Modifier
            .widthIn(min = 216.dp, max = 256.dp)
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                borderColor = borderColor,
                borderWidth = if (selected) 1.2.dp else 0.8.dp,
            )
            .pulseBouncyClickable(enabled = enabled, role = Role.Button, onClick = onSelect)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
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
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                )
                Text(
                    text = server.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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

        Text(
            text = server.displayAddress,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
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
        }
    }
}

@Composable
private fun PresetCatalogSection(
    allPresets: List<SshScriptPreset>,
    customPresets: List<CustomSshPreset>,
    selectedPreset: SshScriptPreset,
    enabledFlagIds: Set<String>,
    resolvedCommand: String,
    selectedServer: SshServerProfile?,
    isRunning: Boolean,
    elapsedMillis: Long,
    onSelectPreset: (String) -> Unit,
    onToggleFlag: (String) -> Unit,
    onAddCustomPreset: () -> Unit,
    onEditCustomPreset: (CustomSshPreset) -> Unit,
    onDeleteCustomPreset: (CustomSshPreset) -> Unit,
    onRunPreset: () -> Unit,
    onStopExecution: () -> Unit,
) {
    var showCommandPreview by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PulseSectionHeader(
            title = "一键测评脚本",
            subtitle = "内置 5 套非交互 VPS 测机脚本，支持自定义扩展",
            icon = Icons.Rounded.Bolt,
            trailing = {
                TextButton(
                    onClick = onAddCustomPreset,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("自定义")
                }
            },
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(allPresets, key = { it.id }) { preset ->
                val isSelected = preset.id == selectedPreset.id
                FilterChip(
                    selected = isSelected,
                    enabled = !isRunning,
                    onClick = { onSelectPreset(preset.id) },
                    label = {
                        Text(
                            text = preset.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = if (preset.isCustom) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Code,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }

        // Selected Preset Card
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
                )
            }
            if (!isRunning) {
                TextButton(
                    onClick = onClearAll,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("清空", style = MaterialTheme.typography.labelMedium)
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
    displayedOutput: String,
    connectionPhase: SshConnectionPhase,
    isRunning: Boolean,
    activeTitle: String,
    elapsedMillis: Long,
    exitCode: Int?,
    errorMessage: String?,
    maskServerIp: Boolean,
    consoleFontScale: Float,
    autoScrollConsole: Boolean,
    onToggleMaskIp: (Boolean) -> Unit,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onResetZoom: () -> Unit,
    onToggleAutoScroll: (Boolean) -> Unit,
    onCopyOutput: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val codeTypography = NodePulseThemeTokens.extraTypography
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    // Pulsing green status LED while actively streaming
    val pulseTransition = rememberInfiniteTransition(label = "sshLedPulse")
    val runningLedAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sshLedAlpha",
    )

    LaunchedEffect(displayedOutput.length, autoScrollConsole) {
        if (autoScrollConsole && displayedOutput.isNotEmpty()) {
            verticalScroll.scrollTo(verticalScroll.maxValue)
        }
    }

    val styledOutput = remember(displayedOutput, ext.onCodeSurface) {
        if (displayedOutput.isEmpty()) {
            null
        } else if (AnsiAndCodeFormatter.containsAnsiEscapes(displayedOutput)) {
            AnsiAndCodeFormatter.parseAnsiTerminalText(
                rawText = displayedOutput,
                defaultForeground = ext.onCodeSurface,
            )
        } else {
            androidx.compose.ui.text.AnnotatedString(displayedOutput)
        }
    }

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
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .alpha(if (isRunning) runningLedAlpha else 1f)
                        .clip(CircleShape)
                        .background(statusColor),
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

            // Font zoom controls
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
                        .clickable(onClick = onZoomOut)
                        .padding(3.dp)
                        .size(14.dp),
                )
                Text(
                    text = "${(consoleFontScale * 100f).roundToInt()}%",
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
                        .clickable(onClick = onZoomIn)
                        .padding(3.dp)
                        .size(14.dp),
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
                    .clickable(enabled = displayedOutput.isNotBlank(), onClick = onCopyOutput)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    tint = if (displayedOutput.isNotBlank()) ext.onCodeSurface else ext.onCodeMuted.copy(alpha = 0.45f),
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "复制输出",
                    style = codeTypography.microLabel,
                    color = if (displayedOutput.isNotBlank()) ext.onCodeSurface else ext.onCodeMuted.copy(alpha = 0.45f),
                )
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

        // Terminal output viewport (horizontal + vertical scroll)
        val scaledFontSize = (11.5f * consoleFontScale).sp
        val scaledLineHeight = (16.5f * consoleFontScale).sp

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp, max = 420.dp)
                .verticalScroll(verticalScroll)
                .horizontalScroll(horizontalScroll)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            if (styledOutput != null) {
                SelectionContainer {
                    Text(
                        text = styledOutput,
                        style = codeTypography.codeBody.copy(
                            fontSize = scaledFontSize,
                            lineHeight = scaledLineHeight,
                        ),
                        softWrap = false,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (isRunning) {
                            "$ 正在建立 SSH 加密通道并分配 xterm-256color PTY..."
                        } else {
                            "$ 选择主机与预设脚本，点击「一键运行」即可实时查看高保真 ANSI 彩色输出"
                        },
                        style = codeTypography.codeBody.copy(fontSize = 12.sp, lineHeight = 18.sp),
                        color = ext.onCodeMuted,
                    )
                    Text(
                        text = "# 进度条 \\r 刷屏已自动折叠 · 公网 IP 默认自动打码 · 多脚本支持一键合并 :::: tabs",
                        style = codeTypography.codeLabel.copy(fontSize = 11.sp),
                        color = ext.onCodeMuted.copy(alpha = 0.72f),
                    )
                }
            }
        }
    }
}

@Composable
private fun LiteSshExportDock(
    formattedPost: SshFormattedPost,
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
                text = if (formattedPost.usesTabsLayout) {
                    "多标签测评已就绪（:::: tabs · ${formattedPost.runCount} 项）"
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onPreview,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Rounded.Preview, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("预览")
            }

            OutlinedButton(
                onClick = onCopyMarkdown,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("复制")
            }

            if (onInsertToReply != null) {
                FilledTonalButton(
                    onClick = onInsertToReply,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text("插入回复")
                }
            }

            Button(
                onClick = onPublishToComposer,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("一键发测评帖", maxLines = 1)
            }
        }
    }
}

internal fun copyTextToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Anode Lite SSH", text))
}

/**
 * Target NodeSeek board slug (`review` 测评版块) for 1-click benchmark review posts.
 */
const val LITE_SSH_REVIEW_CATEGORY_SLUG: String = SshBenchmarkPostFormatter.REVIEW_CATEGORY_SLUG
