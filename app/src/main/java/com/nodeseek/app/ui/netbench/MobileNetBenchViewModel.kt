package com.nodeseek.app.ui.netbench

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.data.netbench.AndroidNetBenchNetworkBridge
import com.nodeseek.app.data.netbench.HttpLatencyRunner
import com.nodeseek.app.data.netbench.IcmpPingRunner
import com.nodeseek.app.data.netbench.IpIntelligenceClient
import com.nodeseek.app.data.netbench.Iperf3ClientRunner
import com.nodeseek.app.data.netbench.NetBenchHistoryStore
import com.nodeseek.app.data.netbench.NetBenchNetworkBridge
import com.nodeseek.app.data.netbench.NetBenchTargetResolver
import com.nodeseek.app.data.netbench.SystemDefaultNetworkBridge
import com.nodeseek.app.data.netbench.TcpPingRunner
import com.nodeseek.app.data.radar.AppContainerHost
import com.nodeseek.app.domain.netbench.ClientEgressSnapshot
import com.nodeseek.app.domain.netbench.ClientNetworkInfo
import com.nodeseek.app.domain.netbench.HttpLatencyResult
import com.nodeseek.app.domain.netbench.IcmpPingConfig
import com.nodeseek.app.domain.netbench.IcmpPingProgress
import com.nodeseek.app.domain.netbench.IpClassificationEngine
import com.nodeseek.app.domain.netbench.IpIntelligenceReport
import com.nodeseek.app.domain.netbench.Iperf3Config
import com.nodeseek.app.domain.netbench.Iperf3Direction
import com.nodeseek.app.domain.netbench.Iperf3Progress
import com.nodeseek.app.domain.netbench.Iperf3TestMode
import com.nodeseek.app.domain.netbench.NetBenchHistoryEntry
import com.nodeseek.app.domain.netbench.NetBenchMarkdownFormatter
import com.nodeseek.app.domain.netbench.NetBenchMarkdownOptions
import com.nodeseek.app.domain.netbench.NetBenchMarkdownStyle
import com.nodeseek.app.domain.netbench.NetBenchReport
import com.nodeseek.app.domain.netbench.NetBenchTargetParser
import com.nodeseek.app.domain.netbench.NetBenchUploadedImages
import com.nodeseek.app.domain.netbench.PreferredIpFamily
import com.nodeseek.app.domain.netbench.ResolvedTarget
import com.nodeseek.app.domain.netbench.TcpPingConfig
import com.nodeseek.app.domain.netbench.TcpPingProgress
import com.nodeseek.app.domain.repository.ImageHostRepository
import com.nodeseek.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Current execution phase of the Mobile Network Tester pipeline.
 */
enum class NetBenchPhase(val label: String) {
    IDLE("待命"),
    RESOLVING("正在解析目标地址…"),
    ICMP_PING("正在进行 ICMP Ping 延迟探测…"),
    TCPING("正在进行 TCPing 延迟探测…"),
    HTTP_PROBE("正在探测 HTTP 延迟…"),
    IP_INTELLIGENCE("正在查询 IP 归属与原生/广播属性…"),
    IPERF_DOWNLOAD("正在进行 iperf3 下载测速 (-R)…"),
    IPERF_UPLOAD("正在进行 iperf3 上传测速…"),
    COMPLETED("测机完成"),
    CANCELLED("已停止测机"),
    ERROR("测机出错"),
}

@Immutable
data class MobileNetBenchUiState(
    val targetInput: String = "",
    val tcpPortText: String = TcpPingConfig.DEFAULT_PORT.toString(),
    val probeCount: Int = TcpPingConfig.DEFAULT_PROBE_COUNT,
    val preferredFamily: PreferredIpFamily = PreferredIpFamily.AUTO,
    val enableIcmpPing: Boolean = true,
    val enableTcpPing: Boolean = true,
    val enableHttpProbe: Boolean = false,
    val enableIpCheck: Boolean = true,
    val enableIperf3: Boolean = false,
    val iperfPortText: String = Iperf3Config.DEFAULT_PORT.toString(),
    val iperfDurationSec: Int = Iperf3Config.DEFAULT_DURATION_SEC,
    val iperfParallelStreams: Int = Iperf3Config.DEFAULT_PARALLEL_STREAMS,
    val iperfTestMode: Iperf3TestMode = Iperf3TestMode.DOWNLOAD,
    val markdownStyle: NetBenchMarkdownStyle = NetBenchMarkdownStyle.FULL_TABLE,
    val maskTargetIpInReport: Boolean = true,
    val includeClientNetworkInReport: Boolean = true,
    val includeReportImages: Boolean = false,
    val isUploadingReportImages: Boolean = false,
    val reportImagesProgressText: String? = null,
    val reportImagesError: String? = null,
    val uploadedReportImages: NetBenchUploadedImages = NetBenchUploadedImages(),
    val plannedReportCardCount: Int = 0,
    val showNodeImageApiKeyDialog: Boolean = false,
    val renderDarkTheme: Boolean = false,
    val showIperf3ServerPrompt: Boolean = false,
    val isRunning: Boolean = false,
    val activePhase: NetBenchPhase = NetBenchPhase.IDLE,
    val resolvedTarget: ResolvedTarget? = null,
    val clientNetwork: ClientNetworkInfo? = null,
    val icmpPingProgress: IcmpPingProgress? = null,
    val tcpPingProgress: TcpPingProgress? = null,
    val httpLatencyResult: HttpLatencyResult? = null,
    val ipIntelligence: IpIntelligenceReport? = null,
    val iperfDownloadProgress: Iperf3Progress? = null,
    val iperfUploadProgress: Iperf3Progress? = null,
    val errorMessage: String? = null,
    val recentTargets: List<NetBenchHistoryEntry> = emptyList(),
    val generatedMarkdown: String = "",
    val clientEgress: ClientEgressSnapshot? = null,
    val isRefreshingEgress: Boolean = false,
    val testViaProxy: Boolean = false,
) {
    val effectiveTcpPort: Int
        get() = tcpPortText.trim().toIntOrNull()?.coerceIn(1, 65535) ?: TcpPingConfig.DEFAULT_PORT

    val effectiveIperfPort: Int
        get() = iperfPortText.trim().toIntOrNull()?.coerceIn(1, 65535) ?: Iperf3Config.DEFAULT_PORT

    val hasAnyResult: Boolean
        get() = icmpPingProgress != null ||
            tcpPingProgress != null ||
            httpLatencyResult != null ||
            ipIntelligence != null ||
            iperfDownloadProgress != null ||
            iperfUploadProgress != null

    val hasAnyModuleEnabled: Boolean
        get() = enableIcmpPing || enableTcpPing || enableHttpProbe || enableIpCheck || enableIperf3

    val canStart: Boolean
        get() = targetInput.isNotBlank() && hasAnyModuleEnabled && !isRunning
}

/**
 * Reusable coroutine controller backing both [MobileNetBenchViewModel] and [NetBenchQuickBottomSheet].
 */
class NetBenchSessionController(
    private val scope: CoroutineScope,
    private val historyStore: NetBenchHistoryStore,
    private val networkBridge: NetBenchNetworkBridge = SystemDefaultNetworkBridge,
    private val targetResolver: NetBenchTargetResolver = NetBenchTargetResolver(networkBridge = networkBridge),
    private val icmpPingRunner: IcmpPingRunner = IcmpPingRunner(resolver = targetResolver, networkBridge = networkBridge),
    private val tcpPingRunner: TcpPingRunner = TcpPingRunner(resolver = targetResolver, networkBridge = networkBridge),
    private val httpLatencyRunner: HttpLatencyRunner = HttpLatencyRunner(networkBridge = networkBridge),
    private val iperf3ClientRunner: Iperf3ClientRunner = Iperf3ClientRunner(resolver = targetResolver, networkBridge = networkBridge),
    private val ipIntelligenceClient: IpIntelligenceClient = IpIntelligenceClient(networkBridge = networkBridge),
    private val imageHostRepository: ImageHostRepository? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val cardImageGenerator: NetBenchCardImageGenerator = AndroidNetBenchCardImageGenerator,
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Default,
    initialTarget: String = "",
    initialMarkdownStyle: NetBenchMarkdownStyle = NetBenchMarkdownStyle.FULL_TABLE,
) {
    private val _uiState = MutableStateFlow(
        MobileNetBenchUiState(
            targetInput = initialTarget.trim(),
            markdownStyle = initialMarkdownStyle,
            maskTargetIpInReport = historyStore.preferences.value.maskTargetIp,
            includeClientNetworkInReport = historyStore.preferences.value.includeClientNetwork,
            includeReportImages = historyStore.preferences.value.includeReportImages,
            recentTargets = historyStore.entries.value,
        ),
    )
    val uiState: StateFlow<MobileNetBenchUiState> = _uiState.asStateFlow()

    private val _snackbarChannel = Channel<String>(Channel.BUFFERED)
    val snackbarMessages: Flow<String> = _snackbarChannel.receiveAsFlow()

    private var activeJob: Job? = null
    private var egressJob: Job? = null
    private var imageUploadJob: Job? = null
    private val uploadedUrlCache = ConcurrentHashMap<String, String>()
    private var cachedEgressSnapshot: ClientEgressSnapshot? = null
    private var lastAppliedInitialTarget: String = initialTarget.trim()

    init {
        scope.launch {
            historyStore.entries.collect { entries ->
                _uiState.update { it.copy(recentTargets = entries) }
            }
        }
        scope.launch {
            historyStore.preferences.collect { prefs ->
                _uiState.update { state ->
                    if (state.maskTargetIpInReport == prefs.maskTargetIp &&
                        state.includeClientNetworkInReport == prefs.includeClientNetwork &&
                        state.includeReportImages == prefs.includeReportImages
                    ) {
                        state
                    } else {
                        state.copy(
                            maskTargetIpInReport = prefs.maskTargetIp,
                            includeClientNetworkInReport = prefs.includeClientNetwork,
                            includeReportImages = prefs.includeReportImages,
                        ).withRegeneratedMarkdown()
                    }
                }
            }
        }
        // Pre-warm dual/direct local egress detection in background
        refreshClientEgress()
        // Automatically re-detect egress IP when Wi-Fi / Cellular / VPN topology changes
        scope.launch {
            networkBridge.observeNetworkChanges().collectLatest {
                delay(450L)
                val currentFp = networkBridge.inspectCurrentNetworks().plan.networkFingerprint
                if (cachedEgressSnapshot?.networkFingerprint != currentFp) {
                    refreshClientEgress()
                }
            }
        }
    }

    fun refreshClientEgress() {
        egressJob?.cancel()
        egressJob = scope.launch {
            _uiState.update { it.copy(isRefreshingEgress = true) }
            try {
                val snapshot = ipIntelligenceClient.detectClientEgress()
                cachedEgressSnapshot = snapshot
                _uiState.update { state ->
                    val effective = snapshot.activeNetworkForTest(state.testViaProxy)
                    state.copy(
                        clientEgress = snapshot,
                        clientNetwork = effective ?: state.clientNetwork,
                        isRefreshingEgress = false,
                    ).withRegeneratedMarkdown()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                _uiState.update { it.copy(isRefreshingEgress = false) }
            }
        }
    }

    fun toggleTestViaProxy(useProxy: Boolean) {
        imageUploadJob?.cancel()
        _uiState.update { state ->
            val updatedNetwork = state.clientEgress?.activeNetworkForTest(useProxy) ?: state.clientNetwork
            state.copy(
                testViaProxy = useProxy,
                clientNetwork = updatedNetwork,
                isUploadingReportImages = false,
                reportImagesProgressText = null,
            ).withRegeneratedMarkdown()
        }
        syncReportImagesIfNeeded(promptForMissingKey = false)
    }

    fun updateRenderDarkTheme(isDark: Boolean) {
        if (_uiState.value.renderDarkTheme == isDark) return
        imageUploadJob?.cancel()
        _uiState.update { state ->
            state.copy(
                renderDarkTheme = isDark,
                isUploadingReportImages = false,
                reportImagesProgressText = null,
            ).withRegeneratedMarkdown()
        }
        syncReportImagesIfNeeded(promptForMissingKey = false)
    }

    fun prefillTarget(rawTarget: String) {
        val cleaned = rawTarget.trim()
        if (cleaned.isEmpty() || cleaned == lastAppliedInitialTarget) return
        lastAppliedInitialTarget = cleaned
        val parsed = NetBenchTargetParser.parse(cleaned)
        _uiState.update { state ->
            state.copy(
                targetInput = cleaned,
                tcpPortText = parsed?.explicitPort?.toString() ?: state.tcpPortText,
                errorMessage = null,
            )
        }
    }

    fun updateTargetInput(value: String) {
        _uiState.update { it.copy(targetInput = value, errorMessage = null) }
    }

    fun updateTcpPortText(value: String) {
        val digits = value.filter { it.isDigit() }.take(5)
        _uiState.update { it.copy(tcpPortText = digits) }
    }

    fun selectPresetPort(port: Int) {
        _uiState.update { state ->
            val updatedIperfPort = if (port == Iperf3Config.DEFAULT_PORT) {
                port.toString()
            } else {
                state.iperfPortText
            }
            state.copy(
                tcpPortText = port.coerceIn(1, 65535).toString(),
                iperfPortText = updatedIperfPort,
            )
        }
    }

    fun updateProbeCount(count: Int) {
        _uiState.update {
            it.copy(
                probeCount = count.coerceIn(
                    TcpPingConfig.MIN_PROBE_COUNT,
                    TcpPingConfig.MAX_PROBE_COUNT,
                ),
            )
        }
    }

    fun updatePreferredFamily(family: PreferredIpFamily) {
        _uiState.update { it.copy(preferredFamily = family) }
    }

    fun toggleIcmpPing(enabled: Boolean) {
        _uiState.update { it.copy(enableIcmpPing = enabled) }
    }

    fun toggleTcpPing(enabled: Boolean) {
        _uiState.update { it.copy(enableTcpPing = enabled) }
    }

    fun toggleHttpProbe(enabled: Boolean) {
        _uiState.update { it.copy(enableHttpProbe = enabled) }
    }

    fun toggleIpCheck(enabled: Boolean) {
        _uiState.update { it.copy(enableIpCheck = enabled) }
    }

    fun toggleIperf3(enabled: Boolean) {
        _uiState.update { state ->
            val shouldPrompt = enabled && !state.enableIperf3
            state.copy(
                enableIperf3 = enabled,
                showIperf3ServerPrompt = when {
                    shouldPrompt -> true
                    !enabled -> false
                    else -> state.showIperf3ServerPrompt
                },
            )
        }
    }

    fun openIperf3ServerPrompt() {
        _uiState.update { it.copy(showIperf3ServerPrompt = true) }
    }

    fun dismissIperf3ServerPrompt() {
        _uiState.update { it.copy(showIperf3ServerPrompt = false) }
    }

    fun updateIperfPortText(value: String) {
        val digits = value.filter { it.isDigit() }.take(5)
        _uiState.update { it.copy(iperfPortText = digits) }
    }

    fun updateIperfDurationSec(seconds: Int) {
        _uiState.update { it.copy(iperfDurationSec = seconds.coerceIn(1, 30)) }
    }

    fun updateIperfParallelStreams(streams: Int) {
        _uiState.update {
            it.copy(
                iperfParallelStreams = streams.coerceIn(
                    Iperf3Config.MIN_PARALLEL_STREAMS,
                    Iperf3Config.MAX_PARALLEL_STREAMS,
                ),
            )
        }
    }

    fun updateIperfTestMode(mode: Iperf3TestMode) {
        _uiState.update { it.copy(iperfTestMode = mode) }
    }

    fun selectMarkdownStyle(style: NetBenchMarkdownStyle) {
        if (style != NetBenchMarkdownStyle.FULL_TABLE) {
            imageUploadJob?.cancel()
        }
        _uiState.update { state ->
            state.copy(
                markdownStyle = style,
                isUploadingReportImages = if (style == NetBenchMarkdownStyle.FULL_TABLE) state.isUploadingReportImages else false,
                reportImagesProgressText = if (style == NetBenchMarkdownStyle.FULL_TABLE) state.reportImagesProgressText else null,
                reportImagesError = if (style == NetBenchMarkdownStyle.FULL_TABLE) state.reportImagesError else null,
            ).withRegeneratedMarkdown()
        }
        scope.launch {
            historyStore.updatePreferences { it.copy(markdownStyle = style) }
        }
        if (style == NetBenchMarkdownStyle.FULL_TABLE) {
            syncReportImagesIfNeeded(promptForMissingKey = false)
        }
    }

    fun toggleMaskTargetIp(mask: Boolean) {
        imageUploadJob?.cancel()
        _uiState.update { state ->
            state.copy(
                maskTargetIpInReport = mask,
                isUploadingReportImages = false,
                reportImagesProgressText = null,
                reportImagesError = null,
            ).withRegeneratedMarkdown()
        }
        scope.launch {
            historyStore.updatePreferences { it.copy(maskTargetIp = mask) }
        }
        syncReportImagesIfNeeded(promptForMissingKey = false)
    }

    fun toggleIncludeClientNetwork(include: Boolean) {
        imageUploadJob?.cancel()
        _uiState.update { state ->
            state.copy(
                includeClientNetworkInReport = include,
                isUploadingReportImages = false,
                reportImagesProgressText = null,
                reportImagesError = null,
            ).withRegeneratedMarkdown()
        }
        scope.launch {
            historyStore.updatePreferences { it.copy(includeClientNetwork = include) }
        }
        syncReportImagesIfNeeded(promptForMissingKey = false)
    }

    fun toggleIncludeReportImages(include: Boolean) {
        if (!include) {
            imageUploadJob?.cancel()
            _uiState.update { state ->
                state.copy(
                    includeReportImages = false,
                    isUploadingReportImages = false,
                    reportImagesProgressText = null,
                    reportImagesError = null,
                ).withRegeneratedMarkdown()
            }
            scope.launch {
                historyStore.updatePreferences { it.copy(includeReportImages = false) }
            }
            return
        }

        val hasConfiguredKey = settingsRepository == null ||
            settingsRepository.settings.value.nodeImageApiKey.trim().isNotEmpty()
        if (!hasConfiguredKey) {
            _uiState.update {
                it.copy(
                    showNodeImageApiKeyDialog = true,
                    reportImagesError = null,
                )
            }
            return
        }

        _uiState.update { state ->
            state.copy(
                includeReportImages = true,
                markdownStyle = NetBenchMarkdownStyle.FULL_TABLE,
                reportImagesError = null,
            ).withRegeneratedMarkdown()
        }
        scope.launch {
            historyStore.updatePreferences {
                it.copy(
                    includeReportImages = true,
                    markdownStyle = NetBenchMarkdownStyle.FULL_TABLE,
                )
            }
        }
        syncReportImagesIfNeeded(promptForMissingKey = true)
    }

    fun openNodeImageApiKeyDialog() {
        _uiState.update { it.copy(showNodeImageApiKeyDialog = true) }
    }

    fun dismissNodeImageApiKeyDialog() {
        _uiState.update { it.copy(showNodeImageApiKeyDialog = false) }
    }

    fun saveNodeImageApiKeyAndUpload(rawKey: String) {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            _snackbarChannel.trySend("请输入有效的 NodeImage API Key")
            return
        }
        scope.launch {
            settingsRepository?.setNodeImageApiKey(trimmed)
            _uiState.update { state ->
                state.copy(
                    showNodeImageApiKeyDialog = false,
                    includeReportImages = true,
                    markdownStyle = NetBenchMarkdownStyle.FULL_TABLE,
                    reportImagesError = null,
                ).withRegeneratedMarkdown()
            }
            historyStore.updatePreferences {
                it.copy(
                    includeReportImages = true,
                    markdownStyle = NetBenchMarkdownStyle.FULL_TABLE,
                )
            }
            syncReportImagesIfNeeded(promptForMissingKey = false)
        }
    }

    fun retryUploadReportImages() {
        val hasConfiguredKey = settingsRepository == null ||
            settingsRepository.settings.value.nodeImageApiKey.trim().isNotEmpty()
        if (!hasConfiguredKey) {
            _uiState.update { it.copy(showNodeImageApiKeyDialog = true) }
            return
        }
        _uiState.update { it.copy(reportImagesError = null) }
        syncReportImagesIfNeeded(promptForMissingKey = true)
    }

    fun applyHistoryEntry(entry: NetBenchHistoryEntry) {
        _uiState.update { state ->
            state.copy(
                targetInput = entry.host,
                tcpPortText = entry.port.toString(),
                iperfPortText = entry.iperfPort.toString(),
                errorMessage = null,
            )
        }
    }

    fun removeHistoryEntry(entry: NetBenchHistoryEntry) {
        scope.launch {
            historyStore.remove(entry.host, entry.port)
        }
    }

    fun clearHistory() {
        scope.launch {
            historyStore.clear()
            _snackbarChannel.trySend("已清空历史测机记录")
        }
    }

    fun clearResults() {
        stopBenchmark(silent = true)
        imageUploadJob?.cancel()
        _uiState.update { state ->
            state.copy(
                activePhase = NetBenchPhase.IDLE,
                resolvedTarget = null,
                icmpPingProgress = null,
                tcpPingProgress = null,
                httpLatencyResult = null,
                ipIntelligence = null,
                iperfDownloadProgress = null,
                iperfUploadProgress = null,
                isUploadingReportImages = false,
                reportImagesProgressText = null,
                reportImagesError = null,
                uploadedReportImages = NetBenchUploadedImages(),
                plannedReportCardCount = 0,
                errorMessage = null,
                generatedMarkdown = "",
            )
        }
    }

    fun startBenchmark() {
        val snapshot = _uiState.value
        val rawInput = snapshot.targetInput.trim()
        val parsed = NetBenchTargetParser.parse(rawInput)
        if (parsed == null) {
            val msg = "请输入有效的 IPv4、IPv6 或域名（支持 host:port）"
            _uiState.update { it.copy(errorMessage = msg) }
            _snackbarChannel.trySend(msg)
            return
        }
        if (!snapshot.hasAnyModuleEnabled) {
            val msg = "请至少开启一项测机模块（Ping / TCPing / IP 鉴定 / iperf3）"
            _uiState.update { it.copy(errorMessage = msg) }
            _snackbarChannel.trySend(msg)
            return
        }

        activeJob?.cancel()
        imageUploadJob?.cancel()
        val effectiveTcpPort = parsed.effectivePort(snapshot.effectiveTcpPort)
        val effectiveIperfPort = snapshot.effectiveIperfPort

        _uiState.update { state ->
            state.copy(
                isRunning = true,
                activePhase = NetBenchPhase.RESOLVING,
                tcpPortText = effectiveTcpPort.toString(),
                resolvedTarget = null,
                icmpPingProgress = null,
                tcpPingProgress = null,
                httpLatencyResult = null,
                ipIntelligence = null,
                iperfDownloadProgress = null,
                iperfUploadProgress = null,
                isUploadingReportImages = false,
                reportImagesProgressText = null,
                reportImagesError = null,
                uploadedReportImages = NetBenchUploadedImages(),
                plannedReportCardCount = 0,
                errorMessage = null,
                generatedMarkdown = "",
            )
        }

        activeJob = scope.launch {
            try {
                // Re-detect client egress if uncached or network topology changed (e.g. Wi-Fi <-> Cellular / VPN)
                val currentFp = networkBridge.inspectCurrentNetworks().plan.networkFingerprint
                val clientEgressDeferred = if (cachedEgressSnapshot != null &&
                    cachedEgressSnapshot?.networkFingerprint == currentFp
                ) {
                    null
                } else {
                    async {
                        runCatching { ipIntelligenceClient.detectClientEgress() }.getOrNull()
                    }
                }

                // Step 0: Resolve target DNS via selected route (underlying physical network by default)
                val resolvedResult = targetResolver.resolve(
                    rawTarget = rawInput,
                    defaultPort = effectiveTcpPort,
                    preferredFamily = snapshot.preferredFamily,
                    useProxy = snapshot.testViaProxy,
                )
                val endpoint = resolvedResult.getOrElse { err ->
                    val errMsg = err.message ?: "无法解析目标主机"
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            activePhase = NetBenchPhase.ERROR,
                            errorMessage = errMsg,
                        )
                    }
                    _snackbarChannel.trySend(errMsg)
                    return@launch
                }

                _uiState.update { state ->
                    state.copy(resolvedTarget = endpoint.resolvedTarget)
                }

                // Step 1a: ICMP Ping (runs before iperf3 so link saturation cannot skew RTT)
                if (snapshot.enableIcmpPing) {
                    _uiState.update { it.copy(activePhase = NetBenchPhase.ICMP_PING) }
                    val icmpConfig = IcmpPingConfig(
                        target = endpoint.resolvedTarget.resolvedIp,
                        probeCount = snapshot.probeCount,
                        timeoutMs = IcmpPingConfig.DEFAULT_TIMEOUT_MS,
                        intervalMs = IcmpPingConfig.DEFAULT_INTERVAL_MS,
                        preferredFamily = snapshot.preferredFamily,
                        useProxy = snapshot.testViaProxy,
                    )
                    icmpPingRunner.run(icmpConfig).collect { progress ->
                        val corrected = progress.copy(
                            resolvedTarget = endpoint.resolvedTarget,
                            summary = progress.summary.copy(resolvedTarget = endpoint.resolvedTarget),
                        )
                        _uiState.update { state ->
                            state.copy(icmpPingProgress = corrected).withRegeneratedMarkdown()
                        }
                    }
                }

                // Step 1b: TCPing (runs before iperf3 so link saturation cannot skew RTT)
                if (snapshot.enableTcpPing) {
                    _uiState.update { it.copy(activePhase = NetBenchPhase.TCPING) }
                    val tcpConfig = TcpPingConfig(
                        target = endpoint.resolvedTarget.resolvedIp,
                        port = effectiveTcpPort,
                        probeCount = snapshot.probeCount,
                        timeoutMs = TcpPingConfig.DEFAULT_TIMEOUT_MS,
                        intervalMs = TcpPingConfig.DEFAULT_INTERVAL_MS,
                        preferredFamily = snapshot.preferredFamily,
                        useProxy = snapshot.testViaProxy,
                    )
                    tcpPingRunner.run(tcpConfig).collect { progress ->
                        val corrected = progress.copy(
                            resolvedTarget = endpoint.resolvedTarget,
                            summary = progress.summary.copy(resolvedTarget = endpoint.resolvedTarget),
                        )
                        _uiState.update { state ->
                            state.copy(tcpPingProgress = corrected).withRegeneratedMarkdown()
                        }
                    }
                }

                // Step 2: Optional HTTP/HTTPS Latency Probe
                if (snapshot.enableHttpProbe) {
                    _uiState.update { it.copy(activePhase = NetBenchPhase.HTTP_PROBE) }
                    val httpRes = httpLatencyRunner.probe(
                        rawTarget = rawInput,
                        port = effectiveTcpPort,
                        useProxy = snapshot.testViaProxy,
                    )
                    _uiState.update { state ->
                        state.copy(httpLatencyResult = httpRes).withRegeneratedMarkdown()
                    }
                }

                // Step 3: IP Intelligence & Native vs Broadcast Check
                if (snapshot.enableIpCheck) {
                    _uiState.update { it.copy(activePhase = NetBenchPhase.IP_INTELLIGENCE) }
                    val intel = ipIntelligenceClient.lookupTargetIp(endpoint.resolvedTarget.resolvedIp)
                    _uiState.update { state ->
                        state.copy(ipIntelligence = intel).withRegeneratedMarkdown()
                    }
                }

                // Collect local egress carrier info if ready
                clientEgressDeferred?.await()?.let { detected ->
                    cachedEgressSnapshot = detected
                    _uiState.update { state ->
                        val effective = detected.activeNetworkForTest(state.testViaProxy)
                        state.copy(
                            clientEgress = detected,
                            clientNetwork = effective ?: state.clientNetwork,
                        ).withRegeneratedMarkdown()
                    }
                }

                // Step 4: Native Pure-Kotlin iperf3 Speed Test
                if (snapshot.enableIperf3) {
                    val runDownload = snapshot.iperfTestMode == Iperf3TestMode.DOWNLOAD ||
                        snapshot.iperfTestMode == Iperf3TestMode.BOTH
                    val runUpload = snapshot.iperfTestMode == Iperf3TestMode.UPLOAD ||
                        snapshot.iperfTestMode == Iperf3TestMode.BOTH

                    val streams = snapshot.iperfParallelStreams.coerceIn(
                        Iperf3Config.MIN_PARALLEL_STREAMS,
                        Iperf3Config.MAX_PARALLEL_STREAMS,
                    )
                    if (runDownload) {
                        _uiState.update { it.copy(activePhase = NetBenchPhase.IPERF_DOWNLOAD) }
                        val dlConfig = Iperf3Config(
                            target = endpoint.resolvedTarget.resolvedIp,
                            port = effectiveIperfPort,
                            durationSec = snapshot.iperfDurationSec,
                            direction = Iperf3Direction.DOWNLOAD,
                            preferredFamily = snapshot.preferredFamily,
                            useProxy = snapshot.testViaProxy,
                            parallelStreams = streams,
                        )
                        iperf3ClientRunner.run(dlConfig, streams).collect { progress ->
                            val enriched = progress.copy(
                                parallelStreams = streams,
                                finalResult = progress.finalResult?.copy(
                                    resolvedTarget = endpoint.resolvedTarget,
                                    parallelStreams = streams,
                                ),
                            )
                            _uiState.update { state ->
                                state.copy(iperfDownloadProgress = enriched).withRegeneratedMarkdown()
                            }
                        }
                    }

                    if (runDownload && runUpload) {
                        // Give real iperf3 -s a short breather to release previous session lock
                        delay(400L)
                    }

                    if (runUpload) {
                        _uiState.update { it.copy(activePhase = NetBenchPhase.IPERF_UPLOAD) }
                        val ulConfig = Iperf3Config(
                            target = endpoint.resolvedTarget.resolvedIp,
                            port = effectiveIperfPort,
                            durationSec = snapshot.iperfDurationSec,
                            direction = Iperf3Direction.UPLOAD,
                            preferredFamily = snapshot.preferredFamily,
                            useProxy = snapshot.testViaProxy,
                            parallelStreams = streams,
                        )
                        iperf3ClientRunner.run(ulConfig, streams).collect { progress ->
                            val enriched = progress.copy(
                                parallelStreams = streams,
                                finalResult = progress.finalResult?.copy(
                                    resolvedTarget = endpoint.resolvedTarget,
                                    parallelStreams = streams,
                                ),
                            )
                            _uiState.update { state ->
                                state.copy(iperfUploadProgress = enriched).withRegeneratedMarkdown()
                            }
                        }
                    }
                }

                // Persist to recent history
                val finalState = _uiState.value
                val intel = finalState.ipIntelligence
                val regionSummary = intel?.takeIf { it.hasCoreInfo }?.let {
                    IpClassificationEngine.formatLocationSummary(
                        countryCode = it.countryCode,
                        countryName = it.countryName,
                        regionName = it.regionName,
                        cityName = it.cityName,
                    )
                }
                historyStore.record(
                    NetBenchHistoryEntry(
                        host = parsed.host,
                        port = effectiveTcpPort,
                        iperfPort = effectiveIperfPort,
                        resolvedIp = endpoint.resolvedTarget.resolvedIp,
                        ipFamily = endpoint.resolvedTarget.ipFamily,
                        lastAvgMs = finalState.tcpPingProgress?.summary?.avgMs,
                        lastPingAvgMs = finalState.icmpPingProgress?.summary?.avgMs,
                        lastPingLossPercent = finalState.icmpPingProgress?.summary?.lossPercent,
                        lastOriginType = intel?.originType,
                        lastRegionSummary = regionSummary,
                        testedAtEpochMillis = System.currentTimeMillis(),
                    ),
                )

                _uiState.update { state ->
                    state.copy(
                        isRunning = false,
                        activePhase = NetBenchPhase.COMPLETED,
                    ).withRegeneratedMarkdown()
                }
                syncReportImagesIfNeeded(promptForMissingKey = true)
            } catch (ce: CancellationException) {
                _uiState.update { state ->
                    state.copy(
                        isRunning = false,
                        activePhase = if (state.hasAnyResult) NetBenchPhase.CANCELLED else NetBenchPhase.IDLE,
                    ).withRegeneratedMarkdown()
                }
                throw ce
            } catch (t: Throwable) {
                val msg = t.message ?: "测机过程出现异常"
                _uiState.update { state ->
                    state.copy(
                        isRunning = false,
                        activePhase = NetBenchPhase.ERROR,
                        errorMessage = msg,
                    ).withRegeneratedMarkdown()
                }
                _snackbarChannel.trySend(msg)
            }
        }
    }

    fun stopBenchmark(silent: Boolean = false) {
        val wasRunning = activeJob?.isActive == true
        activeJob?.cancel()
        activeJob = null
        if (silent) {
            imageUploadJob?.cancel()
        }
        _uiState.update { state ->
            state.copy(
                isRunning = false,
                activePhase = if (state.hasAnyResult) NetBenchPhase.CANCELLED else NetBenchPhase.IDLE,
            ).withRegeneratedMarkdown()
        }
        if (wasRunning && !silent) {
            _snackbarChannel.trySend("已停止测机，已保留当前测得的数据")
            syncReportImagesIfNeeded(promptForMissingKey = false)
        }
    }

    private fun syncReportImagesIfNeeded(promptForMissingKey: Boolean) {
        val uploader = imageHostRepository ?: return
        val snapshot = _uiState.value
        if (snapshot.isRunning ||
            !snapshot.hasAnyResult ||
            !snapshot.includeReportImages ||
            snapshot.markdownStyle != NetBenchMarkdownStyle.FULL_TABLE
        ) {
            return
        }

        val report = snapshot.toNetBenchReport()
        val planned = cardImageGenerator.planCards(
            report = report,
            maskTargetIp = snapshot.maskTargetIpInReport,
            includeClientNetwork = snapshot.includeClientNetworkInReport,
            darkTheme = snapshot.renderDarkTheme,
        )
        if (planned.isEmpty()) {
            _uiState.update {
                it.copy(
                    isUploadingReportImages = false,
                    reportImagesProgressText = null,
                    reportImagesError = null,
                ).withRegeneratedMarkdown()
            }
            return
        }

        val missing = planned.filter { (_, cacheKey) ->
            uploadedUrlCache[cacheKey].isNullOrBlank()
        }
        if (missing.isEmpty()) {
            _uiState.update {
                it.copy(
                    isUploadingReportImages = false,
                    reportImagesProgressText = null,
                    reportImagesError = null,
                ).withRegeneratedMarkdown()
            }
            return
        }

        val hasConfiguredKey = settingsRepository == null ||
            settingsRepository.settings.value.nodeImageApiKey.trim().isNotEmpty()
        if (!hasConfiguredKey) {
            _uiState.update {
                it.copy(
                    isUploadingReportImages = false,
                    reportImagesProgressText = null,
                    showNodeImageApiKeyDialog = if (promptForMissingKey) true else it.showNodeImageApiKeyDialog,
                    reportImagesError = if (promptForMissingKey) {
                        null
                    } else {
                        "尚未配置 NodeImage 图床密钥，点击配置后自动上传配图"
                    },
                ).withRegeneratedMarkdown()
            }
            return
        }

        imageUploadJob?.cancel()
        val maskTargetIp = snapshot.maskTargetIpInReport
        val includeClientNet = snapshot.includeClientNetworkInReport
        val darkTheme = snapshot.renderDarkTheme

        imageUploadJob = scope.launch {
            _uiState.update {
                it.copy(
                    isUploadingReportImages = true,
                    reportImagesError = null,
                    reportImagesProgressText = "正在生成并上传测机卡片配图 (0/${planned.size})…",
                )
            }
            try {
                var uploadedSoFar = planned.size - missing.size
                var firstError: String? = null

                for ((kind, cacheKey) in missing) {
                    _uiState.update {
                        it.copy(
                            isUploadingReportImages = true,
                            reportImagesProgressText = "正在上传「${kind.altTitle}」(${uploadedSoFar + 1}/${planned.size})…",
                        )
                    }
                    val rendered = withContext(renderDispatcher) {
                        cardImageGenerator.renderCard(
                            kind = kind,
                            report = report,
                            maskTargetIp = maskTargetIp,
                            includeClientNetwork = includeClientNet,
                            darkTheme = darkTheme,
                        )
                    }
                    if (rendered == null || rendered.pngBytes.isEmpty()) {
                        if (firstError == null) {
                            firstError = "生成「${kind.altTitle}」卡片图片失败"
                        }
                        continue
                    }

                    val uploadResult = uploader.uploadImage(
                        bytes = rendered.pngBytes,
                        mimeType = "image/png",
                        fileName = rendered.fileName,
                    )
                    uploadResult.fold(
                        onSuccess = { url ->
                            uploadedUrlCache[cacheKey] = url
                            uploadedSoFar++
                            _uiState.update { current -> current.withRegeneratedMarkdown() }
                        },
                        onFailure = { err ->
                            if (firstError == null) {
                                firstError = err.message ?: "上传至 NodeImage 图床失败"
                            }
                        },
                    )
                }

                _uiState.update { current ->
                    current.copy(
                        isUploadingReportImages = false,
                        reportImagesProgressText = null,
                        reportImagesError = firstError,
                    ).withRegeneratedMarkdown()
                }
                if (firstError == null) {
                    _snackbarChannel.trySend("已上传 ${planned.size} 张测机配图至 NodeImage 图床")
                }
            } catch (ce: CancellationException) {
                _uiState.update { current ->
                    current.copy(
                        isUploadingReportImages = false,
                        reportImagesProgressText = null,
                    )
                }
                throw ce
            } catch (t: Throwable) {
                _uiState.update { current ->
                    current.copy(
                        isUploadingReportImages = false,
                        reportImagesProgressText = null,
                        reportImagesError = t.message ?: "生成或上传配图失败",
                    ).withRegeneratedMarkdown()
                }
            }
        }
    }

    private fun MobileNetBenchUiState.toNetBenchReport(): NetBenchReport {
        val effectiveClientNet = clientEgress?.activeNetworkForTest(testViaProxy) ?: clientNetwork
        return NetBenchReport(
            targetInput = targetInput,
            resolvedTarget = resolvedTarget,
            tcpPort = effectiveTcpPort,
            clientNetwork = effectiveClientNet,
            icmpPingSummary = icmpPingProgress?.summary,
            tcpPingSummary = tcpPingProgress?.summary,
            httpLatencyResult = httpLatencyResult,
            ipIntelligence = ipIntelligence,
            iperfDownloadResult = iperfDownloadProgress?.finalResult,
            iperfUploadResult = iperfUploadProgress?.finalResult,
            testedViaProxy = testViaProxy,
            proxyNetwork = clientEgress?.proxyNetwork,
        )
    }

    private fun MobileNetBenchUiState.withRegeneratedMarkdown(): MobileNetBenchUiState {
        if (!hasAnyResult) {
            return copy(
                generatedMarkdown = "",
                uploadedReportImages = NetBenchUploadedImages(),
                plannedReportCardCount = 0,
            )
        }
        val report = toNetBenchReport()
        val planned = cardImageGenerator.planCards(
            report = report,
            maskTargetIp = maskTargetIpInReport,
            includeClientNetwork = includeClientNetworkInReport,
            darkTheme = renderDarkTheme,
        )
        var resolvedImages = NetBenchUploadedImages()
        if (includeReportImages && markdownStyle == NetBenchMarkdownStyle.FULL_TABLE) {
            for ((kind, cacheKey) in planned) {
                val cachedUrl = uploadedUrlCache[cacheKey]
                if (!cachedUrl.isNullOrBlank()) {
                    resolvedImages = resolvedImages.withUrl(kind, cachedUrl)
                }
            }
        }
        val md = NetBenchMarkdownFormatter.format(
            report = report,
            options = NetBenchMarkdownOptions(
                style = markdownStyle,
                maskTargetIp = maskTargetIpInReport,
                includeClientNetwork = includeClientNetworkInReport,
                uploadedImages = resolvedImages,
            ),
        )
        return copy(
            generatedMarkdown = md,
            uploadedReportImages = resolvedImages,
            plannedReportCardCount = planned.size,
        )
    }
}

/**
 * ViewModel for [MobileNetBenchScreen].
 */
class MobileNetBenchViewModel(
    historyStore: NetBenchHistoryStore,
    networkBridge: NetBenchNetworkBridge = SystemDefaultNetworkBridge,
    targetResolver: NetBenchTargetResolver = NetBenchTargetResolver(networkBridge = networkBridge),
    icmpPingRunner: IcmpPingRunner = IcmpPingRunner(resolver = targetResolver, networkBridge = networkBridge),
    tcpPingRunner: TcpPingRunner = TcpPingRunner(resolver = targetResolver, networkBridge = networkBridge),
    httpLatencyRunner: HttpLatencyRunner = HttpLatencyRunner(networkBridge = networkBridge),
    iperf3ClientRunner: Iperf3ClientRunner = Iperf3ClientRunner(resolver = targetResolver, networkBridge = networkBridge),
    ipIntelligenceClient: IpIntelligenceClient = IpIntelligenceClient(networkBridge = networkBridge),
    imageHostRepository: ImageHostRepository? = null,
    settingsRepository: SettingsRepository? = null,
    cardImageGenerator: NetBenchCardImageGenerator = AndroidNetBenchCardImageGenerator,
    renderDispatcher: CoroutineDispatcher = Dispatchers.Default,
    initialTarget: String = "",
) : ViewModel() {

    private val controller = NetBenchSessionController(
        scope = viewModelScope,
        historyStore = historyStore,
        networkBridge = networkBridge,
        targetResolver = targetResolver,
        icmpPingRunner = icmpPingRunner,
        tcpPingRunner = tcpPingRunner,
        httpLatencyRunner = httpLatencyRunner,
        iperf3ClientRunner = iperf3ClientRunner,
        ipIntelligenceClient = ipIntelligenceClient,
        imageHostRepository = imageHostRepository,
        settingsRepository = settingsRepository,
        cardImageGenerator = cardImageGenerator,
        renderDispatcher = renderDispatcher,
        initialTarget = initialTarget,
    )

    val uiState: StateFlow<MobileNetBenchUiState> = controller.uiState
    val snackbarMessages: Flow<String> = controller.snackbarMessages

    fun prefillTarget(rawTarget: String) = controller.prefillTarget(rawTarget)
    fun updateTargetInput(value: String) = controller.updateTargetInput(value)
    fun updateTcpPortText(value: String) = controller.updateTcpPortText(value)
    fun selectPresetPort(port: Int) = controller.selectPresetPort(port)
    fun updateProbeCount(count: Int) = controller.updateProbeCount(count)
    fun updatePreferredFamily(family: PreferredIpFamily) = controller.updatePreferredFamily(family)
    fun toggleIcmpPing(enabled: Boolean) = controller.toggleIcmpPing(enabled)
    fun toggleTcpPing(enabled: Boolean) = controller.toggleTcpPing(enabled)
    fun toggleHttpProbe(enabled: Boolean) = controller.toggleHttpProbe(enabled)
    fun toggleIpCheck(enabled: Boolean) = controller.toggleIpCheck(enabled)
    fun toggleIperf3(enabled: Boolean) = controller.toggleIperf3(enabled)
    fun openIperf3ServerPrompt() = controller.openIperf3ServerPrompt()
    fun dismissIperf3ServerPrompt() = controller.dismissIperf3ServerPrompt()
    fun toggleTestViaProxy(useProxy: Boolean) = controller.toggleTestViaProxy(useProxy)
    fun updateRenderDarkTheme(isDark: Boolean) = controller.updateRenderDarkTheme(isDark)
    fun refreshClientEgress() = controller.refreshClientEgress()
    fun updateIperfPortText(value: String) = controller.updateIperfPortText(value)
    fun updateIperfDurationSec(seconds: Int) = controller.updateIperfDurationSec(seconds)
    fun updateIperfParallelStreams(streams: Int) = controller.updateIperfParallelStreams(streams)
    fun updateIperfTestMode(mode: Iperf3TestMode) = controller.updateIperfTestMode(mode)
    fun selectMarkdownStyle(style: NetBenchMarkdownStyle) = controller.selectMarkdownStyle(style)
    fun toggleMaskTargetIp(mask: Boolean) = controller.toggleMaskTargetIp(mask)
    fun toggleIncludeClientNetwork(include: Boolean) = controller.toggleIncludeClientNetwork(include)
    fun toggleIncludeReportImages(include: Boolean) = controller.toggleIncludeReportImages(include)
    fun openNodeImageApiKeyDialog() = controller.openNodeImageApiKeyDialog()
    fun dismissNodeImageApiKeyDialog() = controller.dismissNodeImageApiKeyDialog()
    fun saveNodeImageApiKeyAndUpload(rawKey: String) = controller.saveNodeImageApiKeyAndUpload(rawKey)
    fun retryUploadReportImages() = controller.retryUploadReportImages()
    fun applyHistoryEntry(entry: NetBenchHistoryEntry) = controller.applyHistoryEntry(entry)
    fun removeHistoryEntry(entry: NetBenchHistoryEntry) = controller.removeHistoryEntry(entry)
    fun clearHistory() = controller.clearHistory()
    fun clearResults() = controller.clearResults()
    fun startBenchmark() = controller.startBenchmark()
    fun stopBenchmark() = controller.stopBenchmark()

    override fun onCleared() {
        controller.stopBenchmark(silent = true)
        super.onCleared()
    }

    companion object {
        fun factory(
            context: Context,
            initialTarget: String = "",
            imageHostRepository: ImageHostRepository? = null,
            settingsRepository: SettingsRepository? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val appContainer = (context.applicationContext as? AppContainerHost)?.container
                val bridge = AndroidNetBenchNetworkBridge(context)
                return MobileNetBenchViewModel(
                    historyStore = NetBenchHistoryStore.forContext(context),
                    networkBridge = bridge,
                    imageHostRepository = imageHostRepository ?: appContainer?.imageHostRepository,
                    settingsRepository = settingsRepository ?: appContainer?.settingsRepository,
                    initialTarget = initialTarget,
                ) as T
            }
        }
    }
}
