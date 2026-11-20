@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.netbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodeseek.app.data.netbench.AndroidNetBenchNetworkBridge
import com.nodeseek.app.data.netbench.NetBenchHistoryStore
import com.nodeseek.app.data.netbench.SystemDefaultNetworkBridge
import com.nodeseek.app.domain.netbench.Iperf3Config
import com.nodeseek.app.domain.netbench.Iperf3TestMode
import com.nodeseek.app.domain.netbench.NetBenchMarkdownStyle
import com.nodeseek.app.domain.netbench.TcpPortPreset
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseGradientDivider
import kotlinx.coroutines.delay
import java.io.File

/**
 * Utility helper to insert a generated NetBench Markdown report cleanly at the current cursor
 * position of a Compose [TextFieldValue] editor.
 */
fun insertNetBenchMarkdownAtCursor(
    value: TextFieldValue,
    markdownBlock: String,
): TextFieldValue {
    val cleanBlock = markdownBlock.trim()
    if (cleanBlock.isEmpty()) return value

    val text = value.text
    val start = value.selection.min.coerceIn(0, text.length)
    val end = value.selection.max.coerceIn(0, text.length)
    val before = text.substring(0, start)
    val after = text.substring(end)

    val leading = when {
        before.isEmpty() -> ""
        before.endsWith("\n\n") -> ""
        before.endsWith("\n") -> "\n"
        else -> "\n\n"
    }
    val trailing = when {
        after.isEmpty() -> ""
        after.startsWith("\n\n") -> ""
        after.startsWith("\n") -> "\n"
        else -> "\n\n"
    }

    val nextText = before + leading + cleanBlock + trailing + after
    val cursorAfterBlock = start + leading.length + cleanBlock.length
    return TextFieldValue(
        text = nextText,
        selection = TextRange(cursorAfterBlock),
    )
}

/**
 * Compact 1-tap Mobile Network Tester bottom sheet designed for direct embedding inside
 * `ReplyComposerBottomSheet` and `TopicComposerScreen`.
 */
@Composable
fun NetBenchQuickBottomSheet(
    onInsertMarkdown: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialTarget: String = "",
    primaryActionLabel: String = "插入测机报告",
    initialMarkdownStyle: NetBenchMarkdownStyle = NetBenchMarkdownStyle.REPLY_CARD,
    onCopiedFeedback: ((String) -> Unit)? = null,
    onOpenFullScreen: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val isInspection = LocalInspectionMode.current
    val sheetScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp

    val historyStore = remember(context, isInspection) {
        if (isInspection) {
            NetBenchHistoryStore(
                historyFile = File(System.getProperty("java.io.tmpdir", "/tmp"), "anode_preview_netbench_history.json"),
            )
        } else {
            NetBenchHistoryStore.forContext(context)
        }
    }

    val networkBridge = remember(context, isInspection) {
        if (isInspection) SystemDefaultNetworkBridge else AndroidNetBenchNetworkBridge(context)
    }

    val controller = remember(historyStore, networkBridge, sheetScope) {
        NetBenchSessionController(
            scope = sheetScope,
            historyStore = historyStore,
            networkBridge = networkBridge,
            initialTarget = initialTarget,
            initialMarkdownStyle = initialMarkdownStyle,
        )
    }

    DisposableEffect(controller) {
        onDispose {
            controller.stopBenchmark(silent = true)
        }
    }

    val uiState by controller.uiState.collectAsStateWithLifecycle()
    var inlineBanner by remember { mutableStateOf<String?>(null) }
    var directSshControlTrigger by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(controller) {
        controller.snackbarMessages.collect { msg ->
            inlineBanner = msg
        }
    }

    LaunchedEffect(inlineBanner) {
        if (inlineBanner != null) {
            delay(2_600L)
            inlineBanner = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            controller.stopBenchmark(silent = true)
            onDismiss()
        },
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = NodePulseThemeTokens.extendedColors.cardSurface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            // Sticky Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.NetworkCheck,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "快捷手机测机",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    val envSubtitle = when {
                        uiState.clientEgress?.isShowingProxyFallback == true ->
                            "检测到代理/VPN · 以下为代理出口 IP · 隐藏本机 IP"
                        uiState.clientEgress?.hasDualExitIps == true -> {
                            val direct = uiState.clientEgress?.directNetwork!!
                            val proxy = uiState.clientEgress?.proxyNetwork!!
                            "真实 ${direct.carrierDisplayName} · 代理 ${proxy.carrierDisplayName} · 隐藏本机 IP"
                        }
                        uiState.clientNetwork != null -> {
                            val net = uiState.clientNetwork!!
                            "${net.carrierDisplayName} · ${net.ipFamily.label} · 隐藏本机 IP"
                        }
                        else -> "Ping · TCPing · 原生 iperf3 · IP 原生/广播鉴定"
                    }
                    Text(
                        text = envSubtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (onOpenFullScreen != null) {
                    NetBenchSelectableChip(
                        label = "完整版",
                        selected = false,
                        icon = Icons.AutoMirrored.Rounded.OpenInNew,
                        onClick = {
                            controller.stopBenchmark(silent = true)
                            onOpenFullScreen(uiState.targetInput.trim())
                        },
                    )
                }

                IconButton(
                    onClick = {
                        controller.stopBenchmark(silent = true)
                        onDismiss()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            PulseGradientDivider(modifier = Modifier.padding(top = 6.dp))

            // Scrollable Compact Body
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AnimatedVisibility(visible = !inlineBanner.isNullOrBlank()) {
                    inlineBanner?.let { banner ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                                    shape = PulseArchitecturalShapes.SmallChip,
                                )
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                        ) {
                            Text(
                                text = banner,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }

                NetBenchClientEgressBanner(
                    egress = uiState.clientEgress,
                    fallbackNetwork = uiState.clientNetwork,
                    testViaProxy = uiState.testViaProxy,
                    isRefreshing = uiState.isRefreshingEgress,
                    onRefresh = controller::refreshClientEgress,
                    onEnableProxyTest = if (!uiState.isRunning) {
                        { controller.toggleTestViaProxy(true) }
                    } else {
                        null
                    },
                    compact = true,
                )

                // Target input
                OutlinedTextField(
                    value = uiState.targetInput,
                    onValueChange = controller::updateTargetInput,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isRunning,
                    singleLine = true,
                    label = { Text("目标 IP / IPv6 / 域名") },
                    placeholder = { Text("如 1.1.1.1 或 lg.example.com:443") },
                    trailingIcon = {
                        if (uiState.targetInput.isNotEmpty() && !uiState.isRunning) {
                            IconButton(onClick = { controller.updateTargetInput("") }) {
                                Icon(Icons.Rounded.Clear, contentDescription = "清空")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            keyboardController?.hide()
                            controller.startBenchmark()
                        },
                    ),
                    shape = PulseArchitecturalShapes.MediumBadge,
                )

                // Recent targets
                NetBenchRecentTargetsStrip(
                    entries = uiState.recentTargets,
                    onSelect = controller::applyHistoryEntry,
                    onRemove = controller::removeHistoryEntry,
                )

                // Quick Port Chips
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TcpPortPreset.entries.forEach { preset ->
                        NetBenchSelectableChip(
                            label = preset.port.toString(),
                            selected = uiState.effectiveTcpPort == preset.port,
                            enabled = !uiState.isRunning,
                            onClick = { controller.selectPresetPort(preset.port) },
                        )
                    }
                    // Probe count quick toggle (10 / 20)
                    NetBenchSelectableChip(
                        label = "${uiState.probeCount}次",
                        selected = true,
                        enabled = !uiState.isRunning,
                        onClick = {
                            val next = when (uiState.probeCount) {
                                10 -> 20
                                20 -> 6
                                else -> 10
                            }
                            controller.updateProbeCount(next)
                        },
                    )
                }

                // Quick Module Toggles Row
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NetBenchSelectableChip(
                        label = "Ping",
                        selected = uiState.enableIcmpPing,
                        icon = Icons.Rounded.GraphicEq,
                        enabled = !uiState.isRunning,
                        onClick = { controller.toggleIcmpPing(!uiState.enableIcmpPing) },
                    )
                    NetBenchSelectableChip(
                        label = "TCPing",
                        selected = uiState.enableTcpPing,
                        icon = Icons.Rounded.NetworkCheck,
                        enabled = !uiState.isRunning,
                        onClick = { controller.toggleTcpPing(!uiState.enableTcpPing) },
                    )
                    NetBenchSelectableChip(
                        label = "IP 归属与原生鉴定",
                        selected = uiState.enableIpCheck,
                        icon = Icons.Rounded.Public,
                        enabled = !uiState.isRunning,
                        onClick = { controller.toggleIpCheck(!uiState.enableIpCheck) },
                    )
                    NetBenchSelectableChip(
                        label = if (uiState.enableIperf3) {
                            "iperf3 ${uiState.iperfTestMode.label}"
                        } else {
                            "iperf3 测速"
                        },
                        selected = uiState.enableIperf3,
                        icon = Icons.Rounded.Speed,
                        enabled = !uiState.isRunning,
                        onClick = { controller.toggleIperf3(!uiState.enableIperf3) },
                    )
                    NetBenchSelectableChip(
                        label = "经由代理测试",
                        selected = uiState.testViaProxy,
                        icon = Icons.Rounded.VpnLock,
                        enabled = !uiState.isRunning,
                        onClick = { controller.toggleTestViaProxy(!uiState.testViaProxy) },
                    )
                }

                if (uiState.enableIcmpPing && uiState.clientEgress?.isVpnActive == true && !uiState.testViaProxy) {
                    NetBenchWarningCallout(
                        message = "提示：无法对 /system/bin/ping 子进程绑定底层网络，VPN 下 ICMP Ping 可能走默认路由，请结合 TCPing 参考。",
                    )
                }

                // Compact iperf3 direction & port bar when enabled
                AnimatedVisibility(visible = uiState.enableIperf3) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = uiState.iperfPortText,
                                onValueChange = controller::updateIperfPortText,
                                modifier = Modifier.width(96.dp),
                                enabled = !uiState.isRunning,
                                singleLine = true,
                                label = { Text("端口") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = PulseArchitecturalShapes.SmallChip,
                            )
                            Iperf3TestMode.entries.forEach { mode ->
                                NetBenchSelectableChip(
                                    label = when (mode) {
                                        Iperf3TestMode.DOWNLOAD -> "下载"
                                        Iperf3TestMode.UPLOAD -> "上传"
                                        Iperf3TestMode.BOTH -> "双向"
                                    },
                                    selected = uiState.iperfTestMode == mode,
                                    enabled = !uiState.isRunning,
                                    onClick = { controller.updateIperfTestMode(mode) },
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "并行流 -P",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Iperf3Config.PARALLEL_STREAM_PRESETS.forEach { streams ->
                                NetBenchSelectableChip(
                                    label = "${streams}流",
                                    selected = uiState.iperfParallelStreams == streams,
                                    enabled = !uiState.isRunning,
                                    onClick = { controller.updateIperfParallelStreams(streams) },
                                )
                            }
                        }

                        NetBenchSelectableChip(
                            label = "一键开启/停止 VPS 的 iperf3 服务端 (端口 ${uiState.effectiveIperfPort})",
                            selected = true,
                            icon = Icons.Rounded.Terminal,
                            enabled = !uiState.isRunning,
                            onClick = { directSshControlTrigger++ },
                        )
                    }
                }

                // Primary 1-tap Run / Stop CTA
                RunBenchmarkActionButton(
                    isRunning = uiState.isRunning,
                    canStart = uiState.canStart,
                    activePhase = uiState.activePhase,
                    onStart = {
                        keyboardController?.hide()
                        controller.startBenchmark()
                    },
                    onStop = { controller.stopBenchmark() },
                )

                if (!uiState.errorMessage.isNullOrBlank()) {
                    NetBenchErrorCallout(message = uiState.errorMessage!!)
                }

                uiState.resolvedTarget?.let { resolved ->
                    ResolvedTargetBanner(
                        resolved = resolved,
                        port = uiState.effectiveTcpPort,
                    )
                }

                uiState.icmpPingProgress?.let { pingProgress ->
                    NetBenchIcmpPingCard(
                        progress = pingProgress,
                        compact = true,
                    )
                }

                uiState.tcpPingProgress?.let { tcpProgress ->
                    NetBenchTcpPingCard(
                        progress = tcpProgress,
                        httpResult = uiState.httpLatencyResult,
                        compact = true,
                    )
                }

                if (uiState.iperfDownloadProgress != null || uiState.iperfUploadProgress != null) {
                    NetBenchIperf3Card(
                        downloadProgress = uiState.iperfDownloadProgress,
                        uploadProgress = uiState.iperfUploadProgress,
                        iperfPort = uiState.effectiveIperfPort,
                        compact = true,
                        onOpenIperf3ServerSetup = { directSshControlTrigger++ },
                    )
                }

                uiState.ipIntelligence?.let { intel ->
                    NetBenchIpIntelligenceCard(
                        report = intel,
                        clientNetwork = uiState.clientNetwork,
                        compact = true,
                        clientEgress = uiState.clientEgress,
                    )
                }

                if (uiState.generatedMarkdown.isNotBlank()) {
                    NetBenchMarkdownReportCard(
                        markdown = uiState.generatedMarkdown,
                        selectedStyle = uiState.markdownStyle,
                        maskTargetIp = uiState.maskTargetIpInReport,
                        includeClientNetwork = uiState.includeClientNetworkInReport,
                        onSelectStyle = controller::selectMarkdownStyle,
                        onToggleMaskTargetIp = controller::toggleMaskTargetIp,
                        onToggleIncludeClientNetwork = controller::toggleIncludeClientNetwork,
                        onCopyMarkdown = {
                            val ok = copyTextToClipboard(
                                context = context,
                                label = "NodeSeek 测机报告",
                                text = uiState.generatedMarkdown,
                            )
                            val feedback = if (ok) "已复制测机 Markdown" else "复制失败"
                            inlineBanner = feedback
                            onCopiedFeedback?.invoke(feedback)
                        },
                        onInsertMarkdown = { md ->
                            controller.stopBenchmark(silent = true)
                            onInsertMarkdown(md)
                            onDismiss()
                        },
                        insertActionLabel = primaryActionLabel,
                        compact = true,
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }

    NetBenchIperf3DialogsHost(
        showPrepPrompt = uiState.showIperf3ServerPrompt,
        openDirectSshControlTrigger = directSshControlTrigger,
        targetInput = uiState.targetInput,
        iperfPortText = uiState.iperfPortText,
        onPortTextChange = controller::updateIperfPortText,
        onDismissPrepPrompt = controller::dismissIperf3ServerPrompt,
        onDisableIperf3 = { controller.toggleIperf3(false) },
        onFeedbackMessage = { msg -> inlineBanner = msg },
    )
}
