@file:OptIn(ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.netbench

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.netbench.Iperf3Config
import com.nodeseek.app.domain.netbench.Iperf3TestMode
import com.nodeseek.app.domain.netbench.PreferredIpFamily
import com.nodeseek.app.domain.netbench.TcpPingConfig
import com.nodeseek.app.domain.netbench.TcpPortPreset
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.components.PulseTextField
import com.nodeseek.app.ui.components.SortSegmentItem
import com.nodeseek.app.ui.components.SortSegmentedSwitcher
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseSectionHeader
import com.nodeseek.app.ui.theme.PulseStaggeredEntrance
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.withTabularNumbers
import com.nodeseek.app.ui.topic.NodeImageApiKeySetupDialog
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Full-screen Mobile Network Tester (`手机端测机`):
 * - Real-time TCPing latency + jitter + packet loss sparkline/bars
 * - Native pure-Kotlin iperf3 v3.x TCP speed test (Download `-R` / Upload / Both)
 * - Multi-source IP Geo/ASN + RIR WHOIS Native-vs-Broadcast classifier
 * - Privacy-safe 1-click NodeSeek Markdown report (`回帖精简卡` & `完整测机表格`)
 */
@Composable
fun MobileNetBenchScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialTarget: String = "",
    onInsertMarkdown: ((String) -> Unit)? = null,
    onOpenLiteSshIperf3Setup: ((targetHost: String, port: Int) -> Unit)? = null,
    onNavigateToVault: (() -> Unit)? = null,
    insertActionLabel: String = "插入回帖/发帖",
    initialListItemIndex: Int = 0,
    previewUiState: MobileNetBenchUiState? = null,
    viewModel: MobileNetBenchViewModel = viewModel(
        factory = MobileNetBenchViewModel.factory(
            context = LocalContext.current,
            initialTarget = initialTarget,
            imageHostRepository = LocalAppContainer.current.imageHostRepository,
            settingsRepository = LocalAppContainer.current.settingsRepository,
            sshVaultRepository = LocalAppContainer.current.sshVaultRepository,
        ),
    ),
) {
    val context = LocalContext.current
    val appContainer = LocalAppContainer.current
    val appSettings by appContainer.settingsRepository.settings.collectAsStateWithLifecycle()
    val isDark = NodePulseThemeTokens.isDark
    val observedUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = previewUiState ?: observedUiState
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    var directSshControlTrigger by rememberSaveable { mutableIntStateOf(0) }
    var showVaultHostPicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(isDark) {
        viewModel.updateRenderDarkTheme(isDark)
    }

    LaunchedEffect(initialTarget) {
        if (initialTarget.isNotBlank()) {
            viewModel.prefillTarget(initialTarget)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding(),
            )
        },
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseGlassSurface(showBottomBorder = true, tonalAccent = MaterialTheme.colorScheme.primary)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "手机端测机",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        text = "Ping · TCPing · 原生 iperf3 · IP 原生/广播鉴定",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (uiState.hasAnyResult && !uiState.isRunning) {
                    IconButton(onClick = viewModel::clearResults) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteSweep,
                            contentDescription = "清空本次结果",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialListItemIndex)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. Target & Port Configuration Card
            item(key = "target_config") {
                PulseStaggeredEntrance(index = 0) {
                    TargetAndPortConfigCard(
                        uiState = uiState,
                        onTargetChange = viewModel::updateTargetInput,
                        onTcpPortChange = viewModel::updateTcpPortText,
                        onSelectPresetPort = viewModel::selectPresetPort,
                        onProbeCountChange = viewModel::updateProbeCount,
                        onPreferredFamilyChange = viewModel::updatePreferredFamily,
                        onApplyHistory = viewModel::applyHistoryEntry,
                        onRemoveHistory = viewModel::removeHistoryEntry,
                        onClearHistory = viewModel::clearHistory,
                        onRefreshEgress = viewModel::refreshClientEgress,
                        onEnableProxyTest = { viewModel.toggleTestViaProxy(true) },
                        onOpenVaultPicker = {
                            keyboardController?.hide()
                            showVaultHostPicker = true
                        },
                        onSubmit = {
                            keyboardController?.hide()
                            viewModel.startBenchmark()
                        },
                    )
                }
            }

            // 2. Modules & iperf3 Configuration Card
            item(key = "modules_config") {
                PulseStaggeredEntrance(index = 1) {
                    TestModulesConfigCard(
                        uiState = uiState,
                        onToggleIcmpPing = viewModel::toggleIcmpPing,
                        onToggleTcpPing = viewModel::toggleTcpPing,
                        onToggleIpCheck = viewModel::toggleIpCheck,
                        onToggleIperf3 = viewModel::toggleIperf3,
                        onToggleHttpProbe = viewModel::toggleHttpProbe,
                        onToggleTestViaProxy = viewModel::toggleTestViaProxy,
                        onIperfPortChange = viewModel::updateIperfPortText,
                        onIperfDurationChange = viewModel::updateIperfDurationSec,
                        onIperfOmitChange = viewModel::updateIperfOmitSec,
                        onIperfParallelStreamsChange = viewModel::updateIperfParallelStreams,
                        onIperfModeChange = viewModel::updateIperfTestMode,
                        onOpenIperf3SshManager = { directSshControlTrigger++ },
                    )
                }
            }

            // 3. Primary Run / Stop CTA Button
            item(key = "run_action") {
                RunBenchmarkActionButton(
                    isRunning = uiState.isRunning,
                    canStart = uiState.canStart,
                    activePhase = uiState.activePhase,
                    onStart = {
                        keyboardController?.hide()
                        viewModel.startBenchmark()
                    },
                    onStop = { viewModel.stopBenchmark() },
                )
            }

            // 4. Error Banner (if top-level DNS/input error)
            if (!uiState.errorMessage.isNullOrBlank()) {
                item(key = "top_error") {
                    NetBenchErrorCallout(message = uiState.errorMessage!!)
                }
            }

            // 5. Resolved Target Banner
            uiState.resolvedTarget?.let { resolved ->
                item(key = "resolved_target") {
                    ResolvedTargetBanner(
                        resolved = resolved,
                        port = uiState.effectiveTcpPort,
                    )
                }
            }

            // 6. Live ICMP Ping Telemetry Card
            uiState.icmpPingProgress?.let { pingProgress ->
                item(key = "icmp_ping_card") {
                    NetBenchIcmpPingCard(progress = pingProgress)
                }
            }

            // 7. Live TCPing Telemetry Card
            uiState.tcpPingProgress?.let { tcpProgress ->
                item(key = "tcping_card") {
                    NetBenchTcpPingCard(
                        progress = tcpProgress,
                        httpResult = uiState.httpLatencyResult,
                    )
                }
            }

            // Standalone HTTP latency card if TCPing was toggled off
            if (uiState.tcpPingProgress == null && uiState.httpLatencyResult != null) {
                item(key = "http_only_card") {
                    StandaloneHttpLatencyCard(result = uiState.httpLatencyResult!!)
                }
            }

            // 7. Live Native iperf3 Card
            if (uiState.iperfDownloadProgress != null || uiState.iperfUploadProgress != null) {
                item(key = "iperf3_card") {
                    NetBenchIperf3Card(
                        downloadProgress = uiState.iperfDownloadProgress,
                        uploadProgress = uiState.iperfUploadProgress,
                        iperfPort = uiState.effectiveIperfPort,
                        onOpenIperf3ServerSetup = { directSshControlTrigger++ },
                    )
                }
            }

            // 8. IP Intelligence & Native vs Broadcast Card
            uiState.ipIntelligence?.let { intel ->
                item(key = "ip_intel_card") {
                    NetBenchIpIntelligenceCard(
                        report = intel,
                        clientNetwork = uiState.clientNetwork,
                        clientEgress = uiState.clientEgress,
                    )
                }
            }

            // 9. Unified NodeSeek Markdown Report Card
            if (uiState.generatedMarkdown.isNotBlank()) {
                item(key = "markdown_report_card") {
                    NetBenchMarkdownReportCard(
                        markdown = uiState.generatedMarkdown,
                        selectedStyle = uiState.markdownStyle,
                        maskTargetIp = uiState.maskTargetIpInReport,
                        includeClientNetwork = uiState.includeClientNetworkInReport,
                        includeReportImages = uiState.includeReportImages,
                        isUploadingReportImages = uiState.isUploadingReportImages,
                        reportImagesProgressText = uiState.reportImagesProgressText,
                        reportImagesError = uiState.reportImagesError,
                        uploadedImageCount = uiState.uploadedReportImages.uploadedCount,
                        plannedImageCount = uiState.plannedReportCardCount,
                        onSelectStyle = viewModel::selectMarkdownStyle,
                        onToggleMaskTargetIp = viewModel::toggleMaskTargetIp,
                        onToggleIncludeClientNetwork = viewModel::toggleIncludeClientNetwork,
                        onToggleIncludeReportImages = viewModel::toggleIncludeReportImages,
                        onRetryUploadReportImages = viewModel::retryUploadReportImages,
                        onConfigureNodeImageApiKey = viewModel::openNodeImageApiKeyDialog,
                        onCopyMarkdown = {
                            val ok = copyTextToClipboard(
                                context = context,
                                label = "NodeSeek 测机报告",
                                text = uiState.generatedMarkdown,
                            )
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    if (ok) "已复制测机 Markdown 到剪贴板" else "复制失败，请长按源码复制",
                                )
                            }
                        },
                        onInsertMarkdown = onInsertMarkdown,
                        insertActionLabel = insertActionLabel,
                    )
                }
            }
        }
    }

    if (uiState.showNodeImageApiKeyDialog) {
        NodeImageApiKeySetupDialog(
            initialApiKey = appSettings.nodeImageApiKey,
            customMessage = "测机卡片配图使用官方 NodeImage 图床（api.nodeimage.com）生成直链并嵌入完整报告。请粘贴您的 X-API-Key，保存后将自动渲染并上传本次测机卡片。",
            onSaveAndContinue = viewModel::saveNodeImageApiKeyAndUpload,
            onDismiss = viewModel::dismissNodeImageApiKeyDialog,
        )
    }

    if (showVaultHostPicker && !uiState.isRunning) {
        VaultHostPickerSheet(
            servers = uiState.vaultServers,
            currentTargetInput = uiState.targetInput,
            onSelectServer = { server ->
                keyboardController?.hide()
                viewModel.applyVaultServer(server)
            },
            onDismiss = { showVaultHostPicker = false },
            onNavigateToVault = onNavigateToVault
                ?: onOpenLiteSshIperf3Setup?.let { openSsh -> { openSsh("", 0) } },
        )
    }

    NetBenchIperf3DialogsHost(
        showPrepPrompt = uiState.showIperf3ServerPrompt,
        openDirectSshControlTrigger = directSshControlTrigger,
        targetInput = uiState.targetInput,
        iperfPortText = uiState.iperfPortText,
        onPortTextChange = viewModel::updateIperfPortText,
        onDismissPrepPrompt = viewModel::dismissIperf3ServerPrompt,
        onDisableIperf3 = { viewModel.toggleIperf3(false) },
        onFeedbackMessage = { msg ->
            coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
        },
        onNavigateToLiteSsh = onOpenLiteSshIperf3Setup,
    )
}

@Composable
private fun TargetAndPortConfigCard(
    uiState: MobileNetBenchUiState,
    onTargetChange: (String) -> Unit,
    onTcpPortChange: (String) -> Unit,
    onSelectPresetPort: (Int) -> Unit,
    onProbeCountChange: (Int) -> Unit,
    onPreferredFamilyChange: (PreferredIpFamily) -> Unit,
    onApplyHistory: (com.nodeseek.app.domain.netbench.NetBenchHistoryEntry) -> Unit,
    onRemoveHistory: (com.nodeseek.app.domain.netbench.NetBenchHistoryEntry) -> Unit,
    onClearHistory: () -> Unit,
    onRefreshEgress: () -> Unit,
    onEnableProxyTest: () -> Unit,
    onOpenVaultPicker: () -> Unit,
    onSubmit: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PulseSectionHeader(
            title = "测试目标与端口",
            subtitle = "支持 IPv4、IPv6 ([::1])、域名或保险库主机",
            icon = Icons.Rounded.NetworkCheck,
            trailing = {
                uiState.clientNetwork?.let { net ->
                    val isFallback = uiState.clientEgress?.isShowingProxyFallback == true
                    val pillText = when {
                        uiState.testViaProxy -> "经由代理 · ${net.ipFamily.label}"
                        isFallback -> "代理出口 · ${net.ipFamily.label}"
                        else -> "${net.carrier.shortLabel} · ${net.ipFamily.label}"
                    }
                    PulseAccentPill(
                        text = pillText,
                        containerColor = if (isFallback) {
                            ext.drumstickContainer.copy(alpha = 0.72f)
                        } else {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                        },
                        contentColor = if (isFallback) {
                            ext.onDrumstickContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                    )
                }
            },
        )

        NetBenchClientEgressBanner(
            egress = uiState.clientEgress,
            fallbackNetwork = uiState.clientNetwork,
            testViaProxy = uiState.testViaProxy,
            isRefreshing = uiState.isRefreshingEgress,
            onRefresh = onRefreshEgress,
            onEnableProxyTest = if (!uiState.isRunning) onEnableProxyTest else null,
        )

        PulseTextField(
            value = uiState.targetInput,
            onValueChange = onTargetChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isRunning,
            singleLine = true,
            label = "目标 IP / IPv6 / 域名",
            placeholder = "例如 1.1.1.1 或 lg.example.com",
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Router,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            trailingIcon = {
                NetBenchTargetTrailingActions(
                    hasText = uiState.targetInput.isNotEmpty(),
                    enabled = !uiState.isRunning,
                    onClear = { onTargetChange("") },
                    onOpenVaultPicker = onOpenVaultPicker,
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
        )

        // Recent targets strip
        NetBenchRecentTargetsStrip(
            entries = uiState.recentTargets,
            onSelect = onApplyHistory,
            onRemove = onRemoveHistory,
            onClearAll = onClearHistory,
        )

        PulseGradientDivider()

        // Port Presets + Custom Port Input
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "TCPing 端口预设",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            PulseTextField(
                value = uiState.tcpPortText,
                onValueChange = onTcpPortChange,
                modifier = Modifier.width(112.dp),
                enabled = !uiState.isRunning,
                singleLine = true,
                label = "自定义端口",
                placeholder = TcpPingConfig.DEFAULT_PORT.toString(),
                minHeight = 42.dp,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TcpPortPreset.entries.forEach { preset ->
                NetBenchSelectableChip(
                    label = preset.label,
                    selected = uiState.effectiveTcpPort == preset.port,
                    enabled = !uiState.isRunning,
                    onClick = { onSelectPresetPort(preset.port) },
                )
            }
        }

        // Probe count slider (4..50)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Ping / TCPing 探测次数",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${uiState.probeCount} 次",
                    style = MaterialTheme.typography.labelLarge.withTabularNumbers(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = uiState.probeCount.toFloat(),
                onValueChange = { onProbeCountChange(it.roundToInt()) },
                valueRange = TcpPingConfig.MIN_PROBE_COUNT.toFloat()..TcpPingConfig.MAX_PROBE_COUNT.toFloat(),
                enabled = !uiState.isRunning,
            )
        }

        // Preferred IP Family selector
        SortSegmentedSwitcher(
            options = remember {
                PreferredIpFamily.entries.map { SortSegmentItem(it, it.label) }
            },
            selectedValue = uiState.preferredFamily,
            onSelect = { if (!uiState.isRunning) onPreferredFamilyChange(it) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TestModulesConfigCard(
    uiState: MobileNetBenchUiState,
    onToggleIcmpPing: (Boolean) -> Unit,
    onToggleTcpPing: (Boolean) -> Unit,
    onToggleIpCheck: (Boolean) -> Unit,
    onToggleIperf3: (Boolean) -> Unit,
    onToggleHttpProbe: (Boolean) -> Unit,
    onToggleTestViaProxy: (Boolean) -> Unit,
    onIperfPortChange: (String) -> Unit,
    onIperfDurationChange: (Int) -> Unit,
    onIperfOmitChange: (Int) -> Unit,
    onIperfParallelStreamsChange: (Int) -> Unit,
    onIperfModeChange: (Iperf3TestMode) -> Unit,
    onOpenIperf3SshManager: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PulseSectionHeader(
            title = "测机项目开关",
            subtitle = "按需组合延迟、IP 鉴定与带宽测速",
            icon = Icons.Rounded.Tune,
        )

        ModuleToggleRow(
            title = "Ping 延迟与抖动",
            subtitle = "ICMP Ping 实时统计平均/最小/最大延迟、mdev 抖动与丢包",
            icon = Icons.Rounded.GraphicEq,
            checked = uiState.enableIcmpPing,
            enabled = !uiState.isRunning,
            onCheckedChange = onToggleIcmpPing,
        )
        ModuleToggleRow(
            title = "TCPing 延迟与抖动",
            subtitle = "实时统计平均/最小/最大延迟、mdev 抖动与丢包",
            icon = Icons.Rounded.NetworkCheck,
            checked = uiState.enableTcpPing,
            enabled = !uiState.isRunning,
            onCheckedChange = onToggleTcpPing,
        )
        ModuleToggleRow(
            title = "IP 归属与原生/广播鉴定",
            subtitle = "多源 GeoIP + RIR WHOIS/RDAP 比对原生与广播 IP",
            icon = Icons.Rounded.Public,
            checked = uiState.enableIpCheck,
            enabled = !uiState.isRunning,
            onCheckedChange = onToggleIpCheck,
        )
        ModuleToggleRow(
            title = "原生 iperf3 吞吐测速",
            subtitle = "纯 Kotlin TCP 协议直连服务端，无需 Root/二进制",
            icon = Icons.Rounded.Speed,
            checked = uiState.enableIperf3,
            enabled = !uiState.isRunning,
            onCheckedChange = onToggleIperf3,
        )

        AnimatedVisibility(
            visible = uiState.enableIperf3,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PulseArchitecturalShapes.MediumBadge)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(
                            alpha = if (NodePulseThemeTokens.isDark) 0.55f else 0.45f,
                        ),
                    )
                    .border(
                        width = 0.8.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        shape = PulseArchitecturalShapes.MediumBadge,
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SortSegmentedSwitcher(
                    options = remember {
                        Iperf3TestMode.entries.map { SortSegmentItem(it, it.label) }
                    },
                    selectedValue = uiState.iperfTestMode,
                    onSelect = { if (!uiState.isRunning) onIperfModeChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PulseTextField(
                        value = uiState.iperfPortText,
                        onValueChange = onIperfPortChange,
                        modifier = Modifier.width(120.dp),
                        enabled = !uiState.isRunning,
                        singleLine = true,
                        label = "iperf3 端口",
                        placeholder = Iperf3Config.DEFAULT_PORT.toString(),
                        minHeight = 44.dp,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "预热剔除 (-O)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Iperf3Config.OMIT_PRESETS.forEach { sec ->
                                NetBenchSelectableChip(
                                    label = "${sec}s",
                                    selected = uiState.iperfOmitSec == sec,
                                    enabled = !uiState.isRunning,
                                    onClick = { onIperfOmitChange(sec) },
                                )
                            }
                        }
                    }
                }

                var showCustomDurationDialog by rememberSaveable { mutableStateOf(false) }
                val isCustomDuration = uiState.iperfDurationSec !in Iperf3Config.DURATION_PRESETS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "单向时长 (-t)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Iperf3Config.DURATION_PRESETS.forEach { sec ->
                            NetBenchSelectableChip(
                                label = "${sec}s",
                                selected = uiState.iperfDurationSec == sec,
                                enabled = !uiState.isRunning,
                                onClick = { onIperfDurationChange(sec) },
                            )
                        }
                        NetBenchSelectableChip(
                            label = if (isCustomDuration) "${uiState.iperfDurationSec}s" else "自定义",
                            selected = isCustomDuration,
                            icon = Icons.Rounded.Edit,
                            enabled = !uiState.isRunning,
                            onClick = { showCustomDurationDialog = true },
                        )
                    }
                }
                Text(
                    text = "单向实际耗时约 ${uiState.iperfOmitSec + uiState.iperfDurationSec}s：" +
                        if (uiState.iperfOmitSec > 0) {
                            "前 ${uiState.iperfOmitSec}s 为 TCP 慢启动预热，不计入均速（同 iperf3 -O）"
                        } else {
                            "未剔除慢启动，均速会包含起速阶段"
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
                if (showCustomDurationDialog) {
                    IperfCustomDurationDialog(
                        initialSec = uiState.iperfDurationSec,
                        onConfirm = { sec ->
                            onIperfDurationChange(sec)
                            showCustomDurationDialog = false
                        },
                        onDismiss = { showCustomDurationDialog = false },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "并行流 (-P)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Iperf3Config.PARALLEL_STREAM_PRESETS.forEach { streams ->
                            NetBenchSelectableChip(
                                label = "${streams}流",
                                selected = uiState.iperfParallelStreams == streams,
                                enabled = !uiState.isRunning,
                                onClick = { onIperfParallelStreamsChange(streams) },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "测速前需在目标 VPS 开启 iperf3 服务端",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    NetBenchSelectableChip(
                        label = "一键开启/停止",
                        selected = true,
                        icon = Icons.Rounded.Terminal,
                        enabled = !uiState.isRunning,
                        onClick = onOpenIperf3SshManager,
                    )
                }
            }
        }

        ModuleToggleRow(
            title = "HTTP/HTTPS 响应探测",
            subtitle = "额外测量 TLS 握手与首字节 TTFB 耗时",
            icon = Icons.Rounded.Language,
            checked = uiState.enableHttpProbe,
            enabled = !uiState.isRunning,
            onCheckedChange = onToggleHttpProbe,
        )

        PulseGradientDivider()

        ModuleToggleRow(
            title = "经由代理测试",
            subtitle = if (uiState.testViaProxy) {
                "Ping / TCPing / HTTP / iperf3 将通过当前代理/VPN 链路测试"
            } else {
                "Ping / TCPing / HTTP / iperf3 均优先绑定非 VPN 底层真实物理网络（仅降级子进程受限时回退默认路由）"
            },
            icon = Icons.Rounded.VpnLock,
            checked = uiState.testViaProxy,
            enabled = !uiState.isRunning,
            onCheckedChange = onToggleTestViaProxy,
        )
    }
}

@Composable
private fun ModuleToggleRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
internal fun RunBenchmarkActionButton(
    isRunning: Boolean,
    canStart: Boolean,
    activePhase: NetBenchPhase,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stopColor = MaterialTheme.colorScheme.error
    val containerModifier = if (isRunning) {
        Modifier
            .fillMaxWidth()
            .clip(PulseArchitecturalShapes.ElevatedTile)
            .background(stopColor)
            .pulseBouncyClickable(onClick = onStop)
            .padding(vertical = 14.dp, horizontal = 16.dp)
    } else if (canStart) {
        Modifier
            .fillMaxWidth()
            .clip(PulseArchitecturalShapes.ElevatedTile)
            .background(MaterialTheme.colorScheme.primary)
            .pulseBouncyClickable(onClick = onStart)
            .padding(vertical = 14.dp, horizontal = 16.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .clip(PulseArchitecturalShapes.ElevatedTile)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(vertical = 14.dp, horizontal = 16.dp)
    }

    Row(
        modifier = modifier.then(containerModifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (isRunning) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.2.dp,
                color = MaterialTheme.colorScheme.onError,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "${activePhase.label}  (点击停止)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onError,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Rounded.Stop,
                contentDescription = "停止测机",
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.size(18.dp),
            )
        } else {
            val fg = if (canStart) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "开始测机",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = fg,
            )
        }
    }
}

@Composable
private fun StandaloneHttpLatencyCard(
    result: com.nodeseek.app.domain.netbench.HttpLatencyResult,
) {
    val accent = if (result.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PulseSectionHeader(
            title = "HTTP/HTTPS 响应探测",
            subtitle = result.url,
            icon = Icons.Rounded.Language,
            accentColor = accent,
        )
        if (result.success && result.totalMs != null) {
            val detail = buildList {
                result.statusCode?.let { add("HTTP $it") }
                add("总耗时 ${com.nodeseek.app.domain.netbench.NetBenchMarkdownFormatter.formatMs(result.totalMs)}")
                result.ttfbMs?.let { add("TTFB ${com.nodeseek.app.domain.netbench.NetBenchMarkdownFormatter.formatMs(it)}") }
                result.tlsHandshakeMs?.let { add("TLS ${com.nodeseek.app.domain.netbench.NetBenchMarkdownFormatter.formatMs(it)}") }
                result.protocol?.let { add(it) }
            }.joinToString(" · ")
            Text(
                text = detail,
                style = NodePulseThemeTokens.extraTypography.codeLabel,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            NetBenchErrorCallout(message = result.localDiagnosticMessage ?: "HTTP 探测失败")
        }
    }
}

@Composable
internal fun IperfCustomDurationDialog(
    initialSec: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf(initialSec.toString()) }
    val parsed = input.toIntOrNull()
    val valid = parsed != null && parsed in Iperf3Config.MIN_DURATION_SEC..Iperf3Config.MAX_DURATION_SEC
    PulseAlertDialog(
        onDismissRequest = onDismiss,
        title = "自定义单向测速时长",
        message = "输入 ${Iperf3Config.MIN_DURATION_SEC}–${Iperf3Config.MAX_DURATION_SEC} 秒（官方 iperf3 默认 10 秒）。" +
            "时长越长，越能平滑链路波动，但会消耗更多流量。",
        icon = Icons.Rounded.Timer,
        confirmText = "确定",
        onConfirm = { parsed?.let(onConfirm) },
        confirmEnabled = valid,
        dismissText = "取消",
        onDismiss = onDismiss,
    ) {
        PulseTextField(
            value = input,
            onValueChange = { raw -> input = raw.filter { it.isDigit() }.take(2) },
            singleLine = true,
            isError = input.isNotEmpty() && !valid,
            label = "时长（秒）",
            placeholder = Iperf3Config.DEFAULT_DURATION_SEC.toString(),
            suffix = { Text("s") },
            supportingText = if (input.isNotEmpty() && !valid) {
                "请输入 ${Iperf3Config.MIN_DURATION_SEC}–${Iperf3Config.MAX_DURATION_SEC} 之间的整数"
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
