package com.nodeseek.app.ui.netbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.domain.netbench.Iperf3Config
import com.nodeseek.app.domain.netbench.NetBenchTargetParser
import com.nodeseek.app.domain.ssh.Iperf3RemoteServerCommands
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.ssh.HostKeyMismatchWarningDialog
import com.nodeseek.app.ui.ssh.Iperf3InstallConfirmDialog
import com.nodeseek.app.ui.ssh.Iperf3ServerAttachment
import com.nodeseek.app.ui.ssh.Iperf3ServerControlDialog
import com.nodeseek.app.ui.ssh.LiteSshViewModel
import com.nodeseek.app.ui.ssh.SshServerEditorDialog
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes

/**
 * Prompt dialog shown when the user enables `iperf3` throughput testing in Mobile Network Tester:
 * - Explains that `iperf3 -s` must be running on the target VPS first
 * - Displays and validates the target `iperf3` port (default `5201`, `1..65535`)
 * - Offers 1-tap jump/invocation of the SSH 1-click VPS `iperf3` server starter/installer
 */
@Composable
fun NetBenchIperf3ServerPromptDialog(
    targetInput: String,
    iperfPortText: String,
    onPortTextChange: (String) -> Unit,
    onInvokeOneClickSshStart: () -> Unit,
    onJumpToLiteSsh: (() -> Unit)? = null,
    onCopyManualCommand: ((String) -> Unit)? = null,
    onConfirmReady: () -> Unit,
    onDisableIperf3: () -> Unit,
    currentAttachment: Iperf3ServerAttachment? = null,
    onDisconnectExternalServer: (() -> Unit)? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val validPort = Iperf3RemoteServerCommands.validatePort(iperfPortText)
    val effectivePort = validPort ?: Iperf3Config.DEFAULT_PORT
    val parsedHost = remember(targetInput) {
        NetBenchTargetParser.parse(targetInput)?.host?.takeIf { it.isNotBlank() }
    }
    val manualCmd = "iperf3 -s -p $effectivePort -D"

    PulseAlertDialog(
        onDismissRequest = onConfirmReady,
        title = "需先在 VPS 开启 iperf3 服务端",
        message = buildString {
            append("开始吞吐测速前，请确保")
            if (parsedHost != null) append("目标 VPS ($parsedHost) ") else append("对应 VPS ")
            append("已启动 iperf3 服务端，且系统防火墙与云安全组已放行 TCP $effectivePort 端口。")
        },
        icon = Icons.Rounded.Speed,
        tone = PulseDialogTone.PRIMARY,
        confirmText = "已开启，继续",
        confirmEnabled = validPort != null,
        onConfirm = onConfirmReady,
        dismissText = "关闭测速开关",
        onDismiss = onDisableIperf3,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = iperfPortText,
                onValueChange = onPortTextChange,
                label = { Text("iperf3 服务端端口（1 ~ 65535）") },
                placeholder = { Text("默认 5201") },
                isError = validPort == null,
                supportingText = {
                    if (validPort == null) {
                        Text("请输入 1 ~ 65535 之间的有效端口")
                    } else {
                        Text("手机端将连接该端口执行纯 Kotlin TCP 协议测速")
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            // Copyable manual command card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PulseArchitecturalShapes.CodeBlock)
                    .background(ext.codeSurface)
                    .border(0.8.dp, ext.codeBorder, PulseArchitecturalShapes.CodeBlock)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "VPS 手动启动参考命令",
                        style = MaterialTheme.typography.labelSmall,
                        color = ext.onCodeMuted,
                    )
                    Text(
                        text = manualCmd,
                        style = NodePulseThemeTokens.extraTypography.codeLabel.copy(fontSize = 11.5.sp),
                        color = ext.onCodeSurface,
                    )
                }
                if (onCopyManualCommand != null) {
                    IconButton(
                        onClick = { onCopyManualCommand(manualCmd) },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = "复制命令",
                            tint = ext.onCodeSurface,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }

            // 1-Click SSH Start / Install button
            FilledTonalButton(
                onClick = onInvokeOneClickSshStart,
                enabled = validPort != null,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "一键开启 VPS 的 iperf3 服务端 (端口 $effectivePort)",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (currentAttachment?.isExternalReused == true && onDisconnectExternalServer != null) {
                OutlinedButton(
                    onClick = onDisconnectExternalServer,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "断开与外部 iperf3 服务的本地连接（不终止远程进程）",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (onJumpToLiteSsh != null) {
                OutlinedButton(
                    onClick = onJumpToLiteSsh,
                    enabled = validPort != null,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "前往「轻量 SSH」页面管理服务端",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Self-contained bridge hosting [NetBenchIperf3ServerPromptDialog] and the in-place SSH 1-click
 * `iperf3` server controller so users can start/install/stop `iperf3 -s` without losing their
 * Mobile Network Tester state.
 */
@Composable
internal fun NetBenchIperf3DialogsHost(
    showPrepPrompt: Boolean,
    openDirectSshControlTrigger: Int,
    targetInput: String,
    iperfPortText: String,
    onPortTextChange: (String) -> Unit,
    onDismissPrepPrompt: () -> Unit,
    onDisableIperf3: () -> Unit,
    onFeedbackMessage: (String) -> Unit,
    onNavigateToLiteSsh: ((targetHost: String, port: Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val isInspection = LocalInspectionMode.current
    val parsedHost = remember(targetInput) {
        NetBenchTargetParser.parse(targetInput)?.host.orEmpty()
    }
    val effectivePort = Iperf3RemoteServerCommands.validatePort(iperfPortText)
        ?: Iperf3Config.DEFAULT_PORT

    if (isInspection) {
        if (showPrepPrompt) {
            NetBenchIperf3ServerPromptDialog(
                targetInput = targetInput,
                iperfPortText = iperfPortText,
                onPortTextChange = onPortTextChange,
                onInvokeOneClickSshStart = onDismissPrepPrompt,
                onJumpToLiteSsh = onNavigateToLiteSsh?.let { nav -> { nav(parsedHost, effectivePort) } },
                onConfirmReady = onDismissPrepPrompt,
                onDisableIperf3 = onDisableIperf3,
            )
        }
        return
    }

    val sshViewModel: LiteSshViewModel = viewModel(
        key = "netbench_ssh_iperf3_bridge",
        factory = LiteSshViewModel.factory(context),
    )
    val sshState by sshViewModel.uiState.collectAsStateWithLifecycle()
    var showInlineSshDialog by rememberSaveable { mutableStateOf(false) }
    var lastHandledTrigger by rememberSaveable { mutableStateOf(0) }

    if (openDirectSshControlTrigger != lastHandledTrigger) {
        lastHandledTrigger = openDirectSshControlTrigger
        sshViewModel.openIperf3ServerDialog(preferredHost = parsedHost, preferredPort = effectivePort)
        sshViewModel.dismissIperf3ServerDialog()
        showInlineSshDialog = true
    }

    androidx.compose.runtime.LaunchedEffect(sshState.statusMessage) {
        val msg = sshState.statusMessage ?: return@LaunchedEffect
        onFeedbackMessage(msg)
        sshViewModel.consumeStatusMessage()
    }

    val matchedHostServer = remember(sshState.servers, parsedHost, sshState.selectedServer) {
        parsedHost.takeIf { it.isNotBlank() }?.let { host ->
            sshState.servers.firstOrNull { srv -> srv.host.trim().equals(host, ignoreCase = true) }
        } ?: sshState.selectedServer
    }
    val prepAttachment = sshState.iperf3AttachmentFor(
        serverId = matchedHostServer?.id,
        port = effectivePort,
    )

    if (showPrepPrompt) {
        NetBenchIperf3ServerPromptDialog(
            targetInput = targetInput,
            iperfPortText = iperfPortText,
            onPortTextChange = onPortTextChange,
            onInvokeOneClickSshStart = {
                onDismissPrepPrompt()
                sshViewModel.openIperf3ServerDialog(preferredHost = parsedHost, preferredPort = effectivePort)
                sshViewModel.dismissIperf3ServerDialog()
                showInlineSshDialog = true
            },
            onJumpToLiteSsh = onNavigateToLiteSsh?.let { nav ->
                {
                    onDismissPrepPrompt()
                    nav(parsedHost, effectivePort)
                }
            },
            onCopyManualCommand = { cmd ->
                copyTextToClipboard(context, "iperf3 启动命令", cmd)
                onFeedbackMessage("已复制：$cmd")
            },
            onConfirmReady = onDismissPrepPrompt,
            onDisableIperf3 = onDisableIperf3,
            currentAttachment = prepAttachment,
            onDisconnectExternalServer = {
                matchedHostServer?.let { srv -> sshViewModel.selectServer(srv.id) }
                sshViewModel.disconnectExternalIperf3Server(portOverride = effectivePort)
                onDisableIperf3()
                onDismissPrepPrompt()
            },
        )
    }

    if (showInlineSshDialog) {
        val inlinePortText = iperfPortText.ifBlank { sshState.iperf3ServerPortText }
        val inlinePort = Iperf3RemoteServerCommands.validatePort(inlinePortText)
        val inlineAttachment = sshState.iperf3AttachmentFor(
            serverId = sshState.selectedServer?.id,
            port = inlinePort,
        )
        Iperf3ServerControlDialog(
            servers = sshState.servers,
            selectedServer = sshState.selectedServer,
            portText = inlinePortText,
            autoOpenFirewall = sshState.iperf3AutoOpenFirewall,
            // iperf3 service commands run on an isolated SSH connection, so only gate on
            // an in-flight iperf3 utility command, never on a running benchmark script.
            isRunning = sshState.isIperf3ServiceBusy,
            onSelectServer = sshViewModel::selectServer,
            onAddServer = {
                showInlineSshDialog = false
                sshViewModel.openAddServerDialog()
            },
            onPortTextChange = { nextPort ->
                onPortTextChange(nextPort)
                sshViewModel.updateIperf3ServerPortText(nextPort)
            },
            onToggleAutoOpenFirewall = sshViewModel::toggleIperf3AutoOpenFirewall,
            onStartServer = {
                val port = Iperf3RemoteServerCommands.validatePort(inlinePortText) ?: return@Iperf3ServerControlDialog
                showInlineSshDialog = false
                sshViewModel.startIperf3Server(portOverride = port)
            },
            onStopServer = {
                val port = Iperf3RemoteServerCommands.validatePort(inlinePortText) ?: return@Iperf3ServerControlDialog
                showInlineSshDialog = false
                sshViewModel.stopIperf3Server(portOverride = port)
            },
            currentAttachment = inlineAttachment,
            onDisconnectExternalServer = {
                val port = Iperf3RemoteServerCommands.validatePort(inlinePortText) ?: return@Iperf3ServerControlDialog
                showInlineSshDialog = false
                sshViewModel.disconnectExternalIperf3Server(portOverride = port)
                onDisableIperf3()
            },
            onCopyCommand = { cmd ->
                copyTextToClipboard(context, "防火墙放行命令", cmd)
                onFeedbackMessage("已复制放行命令：$cmd")
            },
            onDismiss = { showInlineSshDialog = false },
        )
    }

    if (sshState.showServerEditor) {
        SshServerEditorDialog(
            existing = sshState.editingServer,
            onDismiss = sshViewModel::dismissServerEditor,
            onSave = { draft ->
                sshViewModel.saveServer(draft)
                showInlineSshDialog = true
            },
            servers = sshState.servers,
        )
    }

    sshState.pendingIperf3InstallPrompt?.let { prompt ->
        Iperf3InstallConfirmDialog(
            prompt = prompt,
            onConfirmInstallAndStart = sshViewModel::confirmInstallAndStartIperf3Server,
            onDismiss = sshViewModel::dismissIperf3InstallPrompt,
        )
    }

    sshState.pendingHostKeyMismatch?.let { mismatch ->
        HostKeyMismatchWarningDialog(
            prompt = mismatch,
            onDismiss = sshViewModel::dismissHostKeyMismatch,
            onTrustAndContinue = sshViewModel::confirmTrustChangedHostKey,
        )
    }
}
