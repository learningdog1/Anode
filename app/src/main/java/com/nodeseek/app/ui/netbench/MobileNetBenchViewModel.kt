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
import com.nodeseek.app.data.netbench.TracerouteRunner
import com.nodeseek.app.data.radar.AppContainerHost
import com.nodeseek.app.domain.baseline.BaselineDiffEngine
import com.nodeseek.app.domain.baseline.BaselineDiffReport
import com.nodeseek.app.domain.baseline.BaselineSnapshotExtractor
import com.nodeseek.app.domain.baseline.BenchmarkBaselineRepository
import com.nodeseek.app.domain.baseline.BenchmarkBaselineSnapshot
import com.nodeseek.app.domain.baseline.NoOpBenchmarkBaselineRepository
import com.nodeseek.app.domain.netbench.BackboneRouteTagger
import com.nodeseek.app.domain.netbench.ClientEgressSnapshot
import com.nodeseek.app.domain.netbench.ClientNetworkInfo
import com.nodeseek.app.domain.netbench.HopLossClassification
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
import com.nodeseek.app.domain.netbench.ReverseTracerouteCodec
import com.nodeseek.app.domain.netbench.TcpPingConfig
import com.nodeseek.app.domain.netbench.TcpPingProgress
import com.nodeseek.app.domain.netbench.TracerouteConfig
import com.nodeseek.app.domain.netbench.TracerouteDirection
import com.nodeseek.app.domain.netbench.TracerouteProgress
import com.nodeseek.app.domain.netbench.TracerouteSummary
import com.nodeseek.app.domain.repository.ImageHostRepository
import com.nodeseek.app.domain.repository.SettingsRepository
import com.nodeseek.app.domain.ssh.HostKeyInfo
import com.nodeseek.app.domain.ssh.SshHostKeyMismatchException
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.domain.ssh.SshVaultRepository
import com.nodeseek.app.ui.ssh.LiteSshSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Current execution phase of the Mobile Network Tester pipeline.
 */
enum class NetBenchPhase(val label: String) {
    IDLE("待命"),
    RESOLVING("正在解析目标地址…"),
    ICMP_PING("正在进行 ICMP Ping 延迟探测…"),
    TCPING("正在进行 TCPing 延迟探测…"),
    TRACEROUTE("正在执行去程路由追踪 (MTR)…"),
    REVERSE_TRACEROUTE("正在执行回程路由追踪 (VPS → 本机)…"),
    HTTP_PROBE("正在探测 HTTP 延迟…"),
    IP_INTELLIGENCE("正在查询 IP 归属与原生/广播属性…"),
    IPERF_DOWNLOAD("正在进行 iperf3 下载测速 (-R)…"),
    IPERF_UPLOAD("正在进行 iperf3 上传测速…"),
    COMPLETED("测机完成"),
    CANCELLED("已停止测机"),
    ERROR("测机出错"),
}

@Immutable
data class PendingReverseHostKeyMismatch(
    val serverId: String,
    val serverAlias: String,
    val expectedFingerprint: String,
    val actualHostKey: HostKeyInfo,
    val mismatchServerId: String = serverId,
)

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
    val iperfOmitSec: Int = Iperf3Config.DEFAULT_UI_OMIT_SEC,
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
    val vaultServers: List<SshServerProfile> = emptyList(),
    val generatedMarkdown: String = "",
    val clientEgress: ClientEgressSnapshot? = null,
    val isRefreshingEgress: Boolean = false,
    val testViaProxy: Boolean = false,
    val enableTraceroute: Boolean = false,
    val enableReverseTraceroute: Boolean = false,
    val tracerouteProbesPerHop: Int = TracerouteConfig.DEFAULT_PROBES_PER_HOP,
    val tracerouteProgress: TracerouteProgress? = null,
    val isRunningReverseTrace: Boolean = false,
    val reverseTracerouteResult: TracerouteSummary? = null,
    val reverseTraceError: String? = null,
    val selectedReverseServerId: String? = null,
    val pendingReverseTraceHostKeyMismatch: PendingReverseHostKeyMismatch? = null,
    val savedBaselines: List<BenchmarkBaselineSnapshot> = emptyList(),
    val matchingBaselines: List<BenchmarkBaselineSnapshot> = emptyList(),
    val selectedBaselineIdForCompare: String? = null,
    val activeBaselineDiff: BaselineDiffReport? = null,
    val showSaveBaselineDialog: Boolean = false,
    val showBaselineCompareSheet: Boolean = false,
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
            iperfUploadProgress != null ||
            (enableTraceroute && tracerouteProgress != null) ||
            (enableReverseTraceroute && reverseTracerouteResult != null)

    val hasAnyModuleEnabled: Boolean
        get() = enableIcmpPing ||
            enableTcpPing ||
            enableHttpProbe ||
            enableIpCheck ||
            enableIperf3 ||
            enableTraceroute ||
            enableReverseTraceroute

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
    private val httpLatencyRunner: HttpLatencyRunner = HttpLatencyRunner(
        networkBridge = networkBridge,
        targetResolver = targetResolver,
    ),
    private val iperf3ClientRunner: Iperf3ClientRunner = Iperf3ClientRunner(resolver = targetResolver, networkBridge = networkBridge),
    private val ipIntelligenceClient: IpIntelligenceClient = IpIntelligenceClient(networkBridge = networkBridge),
    private val imageHostRepository: ImageHostRepository? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val cardImageGenerator: NetBenchCardImageGenerator = AndroidNetBenchCardImageGenerator,
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Default,
    initialTarget: String = "",
    initialMarkdownStyle: NetBenchMarkdownStyle? = null,
    private val sshVaultRepository: SshVaultRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val tracerouteRunner: TracerouteRunner = TracerouteRunner(
        resolver = targetResolver,
        networkBridge = networkBridge,
        ipIntelligenceClient = ipIntelligenceClient,
    ),
    private val sshSessionManager: LiteSshSessionManager? = null,
    private val benchmarkBaselineRepository: BenchmarkBaselineRepository = NoOpBenchmarkBaselineRepository,
) {
    private val persistMarkdownStylePreference: Boolean = initialMarkdownStyle == null

    private val _uiState = MutableStateFlow(
        MobileNetBenchUiState(
            targetInput = initialTarget.trim(),
            markdownStyle = initialMarkdownStyle ?: historyStore.preferences.value.markdownStyle,
            maskTargetIpInReport = historyStore.preferences.value.maskTargetIp,
            includeClientNetworkInReport = historyStore.preferences.value.includeClientNetwork,
            includeReportImages = historyStore.preferences.value.includeReportImages,
            enableTraceroute = historyStore.preferences.value.enableForwardTraceroute,
            enableReverseTraceroute = historyStore.preferences.value.enableReverseTraceroute,
            recentTargets = historyStore.entries.value,
            vaultServers = sshVaultRepository?.serversFlow?.value.orEmpty(),
        ),
    )
    val uiState: StateFlow<MobileNetBenchUiState> = _uiState.asStateFlow()

    private val _snackbarChannel = Channel<String>(Channel.BUFFERED)
    val snackbarMessages: Flow<String> = _snackbarChannel.receiveAsFlow()

    private var activeJob: Job? = null
    private var egressJob: Job? = null
    private var imageUploadJob: Job? = null
    private var reverseTraceJob: Job? = null
    private val reverseTraceGeneration = AtomicLong(0L)
    private val benchmarkGeneration = AtomicLong(0L)
    private val imageUploadGeneration = AtomicLong(0L)
    private val uploadedUrlCache = ConcurrentHashMap<String, String>()
    private var cachedEgressSnapshot: ClientEgressSnapshot? = null
    private var lastAppliedInitialTarget: String = initialTarget.trim()
    @Volatile
    private var userToggledForwardTrace: Boolean = false
    @Volatile
    private var userToggledReverseTrace: Boolean = false

    init {
        scope.launch {
            historyStore.reload()
        }
        scope.launch {
            benchmarkBaselineRepository.ensureLoaded()
        }
        scope.launch {
            benchmarkBaselineRepository.baselinesFlow.collect { baselines ->
                _uiState.update { state ->
                    val matching = computeMatchingBaselines(state, baselines)
                    val updatedDiff = if (state.showBaselineCompareSheet) {
                        val selected = baselines.firstOrNull { it.id == state.selectedBaselineIdForCompare }
                            ?: matching.firstOrNull()
                            ?: baselines.firstOrNull()
                        selected?.let {
                            val currentSnap = BaselineSnapshotExtractor.fromNetBenchReport(
                                report = state.toNetBenchReport(),
                                id = "current_netbench",
                                label = "当前实测",
                            )
                            BaselineDiffEngine.compare(it, currentSnap)
                        }
                    } else {
                        state.activeBaselineDiff
                    }
                    state.copy(
                        savedBaselines = baselines,
                        matchingBaselines = matching,
                        activeBaselineDiff = updatedDiff,
                    )
                }
            }
        }
        scope.launch {
            historyStore.entries.collect { entries ->
                _uiState.update { it.copy(recentTargets = entries) }
            }
        }
        sshVaultRepository?.let { vault ->
            scope.launch {
                vault.serversFlow.collect { servers ->
                    _uiState.update { state ->
                        if (state.vaultServers == servers) state else state.copy(vaultServers = servers)
                    }
                }
            }
        }
        scope.launch {
            historyStore.preferences.collect { prefs ->
                _uiState.update { state ->
                    val targetStyle = if (persistMarkdownStylePreference) prefs.markdownStyle else state.markdownStyle
                    val targetForward = if (userToggledForwardTrace) state.enableTraceroute else prefs.enableForwardTraceroute
                    val targetReverse = if (userToggledReverseTrace) state.enableReverseTraceroute else prefs.enableReverseTraceroute
                    if (state.maskTargetIpInReport == prefs.maskTargetIp &&
                        state.includeClientNetworkInReport == prefs.includeClientNetwork &&
                        state.includeReportImages == prefs.includeReportImages &&
                        state.markdownStyle == targetStyle &&
                        state.enableTraceroute == targetForward &&
                        state.enableReverseTraceroute == targetReverse
                    ) {
                        state
                    } else {
                        state.copy(
                            markdownStyle = targetStyle,
                            maskTargetIpInReport = prefs.maskTargetIp,
                            includeClientNetworkInReport = prefs.includeClientNetwork,
                            includeReportImages = prefs.includeReportImages,
                            enableTraceroute = targetForward,
                            enableReverseTraceroute = targetReverse,
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
                val currentFp = withContext(ioDispatcher) {
                    networkBridge.inspectCurrentNetworks().plan.networkFingerprint
                }
                if (cachedEgressSnapshot?.networkFingerprint != currentFp) {
                    refreshClientEgress()
                }
            }
        }
    }

    private fun cancelActiveImageUpload() {
        imageUploadGeneration.incrementAndGet()
        imageUploadJob?.cancel()
        imageUploadJob = null
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
        cancelActiveImageUpload()
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
        cancelActiveImageUpload()
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

    fun toggleTraceroute(enabled: Boolean) {
        userToggledForwardTrace = true
        _uiState.update { state ->
            state.copy(
                enableTraceroute = enabled,
                tracerouteProgress = if (enabled) state.tracerouteProgress else null,
            ).withRegeneratedMarkdown()
        }
        scope.launch {
            historyStore.updatePreferences { it.copy(enableForwardTraceroute = enabled) }
        }
    }

    fun toggleReverseTraceroute(enabled: Boolean) {
        userToggledReverseTrace = true
        if (!enabled) {
            reverseTraceGeneration.incrementAndGet()
            reverseTraceJob?.cancel()
            reverseTraceJob = null
        }
        _uiState.update { state ->
            state.copy(
                enableReverseTraceroute = enabled,
                isRunningReverseTrace = if (enabled) state.isRunningReverseTrace else false,
                reverseTracerouteResult = if (enabled) state.reverseTracerouteResult else null,
                reverseTraceError = if (enabled) state.reverseTraceError else null,
                pendingReverseTraceHostKeyMismatch = if (enabled) state.pendingReverseTraceHostKeyMismatch else null,
            ).withRegeneratedMarkdown()
        }
        scope.launch {
            historyStore.updatePreferences { it.copy(enableReverseTraceroute = enabled) }
        }
    }

    fun selectReverseServer(serverId: String?) {
        _uiState.update {
            it.copy(
                selectedReverseServerId = serverId,
                reverseTraceError = null,
            )
        }
    }

    fun updateTracerouteProbesPerHop(count: Int) {
        _uiState.update {
            it.copy(
                tracerouteProbesPerHop = count.coerceIn(
                    TracerouteConfig.MIN_PROBES_PER_HOP,
                    TracerouteConfig.MAX_PROBES_PER_HOP,
                ),
            )
        }
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
        _uiState.update {
            it.copy(
                iperfDurationSec = seconds.coerceIn(
                    Iperf3Config.MIN_DURATION_SEC,
                    Iperf3Config.MAX_DURATION_SEC,
                ),
            )
        }
    }

    fun updateIperfOmitSec(seconds: Int) {
        _uiState.update { it.copy(iperfOmitSec = seconds.coerceIn(0, Iperf3Config.MAX_OMIT_SEC)) }
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
            cancelActiveImageUpload()
        }
        _uiState.update { state ->
            state.copy(
                markdownStyle = style,
                isUploadingReportImages = if (style == NetBenchMarkdownStyle.FULL_TABLE) state.isUploadingReportImages else false,
                reportImagesProgressText = if (style == NetBenchMarkdownStyle.FULL_TABLE) state.reportImagesProgressText else null,
                reportImagesError = if (style == NetBenchMarkdownStyle.FULL_TABLE) state.reportImagesError else null,
            ).withRegeneratedMarkdown()
        }
        if (persistMarkdownStylePreference) {
            scope.launch {
                historyStore.updatePreferences { it.copy(markdownStyle = style) }
            }
        }
        if (style == NetBenchMarkdownStyle.FULL_TABLE) {
            syncReportImagesIfNeeded(promptForMissingKey = false)
        }
    }

    fun toggleMaskTargetIp(mask: Boolean) {
        cancelActiveImageUpload()
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
        cancelActiveImageUpload()
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
            cancelActiveImageUpload()
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

    fun applyVaultServer(server: SshServerProfile): Boolean {
        val selection = server.toVaultHostSelection() ?: return false
        _uiState.update { state ->
            state.copy(
                targetInput = selection.host,
                tcpPortText = selection.port.toString(),
                selectedReverseServerId = server.id,
                errorMessage = null,
            )
        }
        val endpoint = formatVaultEndpointForFeedback(selection.host, selection.port)
        _snackbarChannel.trySend("已填入保险库主机「${selection.displayName}」($endpoint)")
        return true
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
        reverseTraceGeneration.incrementAndGet()
        reverseTraceJob?.cancel()
        reverseTraceJob = null
        cancelActiveImageUpload()
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
                tracerouteProgress = null,
                isRunningReverseTrace = false,
                reverseTracerouteResult = null,
                reverseTraceError = null,
                pendingReverseTraceHostKeyMismatch = null,
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

    fun runForwardTracerouteOnly() {
        val snapshot = _uiState.value
        if (snapshot.isRunning) return
        val rawInput = snapshot.targetInput.trim()
        val parsed = NetBenchTargetParser.parse(rawInput)
        if (parsed == null) {
            val msg = "请输入有效的 IPv4、IPv6 或域名（支持 host:port）"
            _uiState.update { it.copy(errorMessage = msg) }
            _snackbarChannel.trySend(msg)
            return
        }

        val runGen = benchmarkGeneration.incrementAndGet()
        activeJob?.cancel()
        cancelActiveImageUpload()
        val effectiveTcpPort = parsed.effectivePort(snapshot.effectiveTcpPort)
        userToggledForwardTrace = true

        _uiState.update {
            it.copy(
                enableTraceroute = true,
                isRunning = true,
                activePhase = NetBenchPhase.TRACEROUTE,
                tracerouteProgress = null,
                errorMessage = null,
            )
        }
        scope.launch {
            historyStore.updatePreferences { it.copy(enableForwardTraceroute = true) }
        }

        activeJob = scope.launch {
            try {
                val resolvedResult = targetResolver.resolve(
                    rawTarget = rawInput,
                    defaultPort = effectiveTcpPort,
                    preferredFamily = snapshot.preferredFamily,
                    useProxy = snapshot.testViaProxy,
                )
                val endpoint = resolvedResult.getOrElse { err ->
                    if (benchmarkGeneration.get() == runGen) {
                        val errMsg = err.message ?: "无法解析目标主机"
                        _uiState.update {
                            it.copy(
                                isRunning = false,
                                activePhase = NetBenchPhase.ERROR,
                                errorMessage = errMsg,
                            )
                        }
                        _snackbarChannel.trySend(errMsg)
                    }
                    return@launch
                }
                _uiState.update { it.copy(resolvedTarget = endpoint.resolvedTarget) }

                val config = TracerouteConfig(
                    target = endpoint.resolvedTarget.resolvedIp,
                    probesPerHop = snapshot.tracerouteProbesPerHop,
                    preferredFamily = snapshot.preferredFamily,
                    useProxy = snapshot.testViaProxy,
                    tcpProbePort = effectiveTcpPort,
                )
                tracerouteRunner.run(config, preResolvedEndpoint = endpoint).collect { progress ->
                    val corrected = progress.copy(resolvedTarget = endpoint.resolvedTarget)
                    _uiState.update { state ->
                        state.copy(tracerouteProgress = corrected).withRegeneratedMarkdown()
                    }
                }
                if (benchmarkGeneration.get() == runGen) {
                    _uiState.update { state ->
                        state.copy(
                            isRunning = false,
                            activePhase = NetBenchPhase.COMPLETED,
                        ).withRegeneratedMarkdown()
                    }
                }
            } catch (ce: CancellationException) {
                if (benchmarkGeneration.get() == runGen) {
                    _uiState.update { state ->
                        state.copy(
                            isRunning = false,
                            activePhase = if (state.hasAnyResult) NetBenchPhase.CANCELLED else NetBenchPhase.IDLE,
                        ).withRegeneratedMarkdown()
                    }
                }
                throw ce
            } catch (t: Throwable) {
                if (benchmarkGeneration.get() == runGen) {
                    val msg = t.message ?: "路由追踪失败"
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
    }

    fun runReverseTraceroute(
        serverProfile: SshServerProfile,
        allowHostKeyMismatchOverride: Boolean = false,
        mismatchOverrideServerId: String? = null,
    ) {
        val manager = sshSessionManager
        if (manager == null) {
            val msg = "SSH 会话管理器未就绪，请先前往「轻量 SSH」页面配置主机"
            _uiState.update { it.copy(reverseTraceError = msg) }
            _snackbarChannel.trySend(msg)
            return
        }

        val revGen = reverseTraceGeneration.incrementAndGet()
        reverseTraceJob?.cancel()
        userToggledReverseTrace = true
        _uiState.update {
            it.copy(
                enableReverseTraceroute = true,
                isRunningReverseTrace = true,
                selectedReverseServerId = serverProfile.id,
                reverseTraceError = null,
                pendingReverseTraceHostKeyMismatch = null,
            )
        }
        scope.launch {
            historyStore.updatePreferences { it.copy(enableReverseTraceroute = true) }
        }

        reverseTraceJob = scope.launch {
            executeReverseTraceroute(
                manager = manager,
                serverProfile = serverProfile,
                revGen = revGen,
                allowHostKeyMismatchOverride = allowHostKeyMismatchOverride,
                mismatchOverrideServerId = mismatchOverrideServerId,
                emitCompletionSnackbar = true,
            )
        }
    }

    private suspend fun executeReverseTraceroute(
        manager: LiteSshSessionManager,
        serverProfile: SshServerProfile,
        revGen: Long,
        allowHostKeyMismatchOverride: Boolean = false,
        mismatchOverrideServerId: String? = null,
        emitCompletionSnackbar: Boolean = false,
    ) {
        try {
            var egressSnapshot = _uiState.value.clientEgress
            if (egressSnapshot?.directNetwork?.rawIp.isNullOrBlank()) {
                val refreshed = runCatching { ipIntelligenceClient.detectClientEgress() }.getOrNull()
                if (refreshed != null) {
                    egressSnapshot = refreshed
                    cachedEgressSnapshot = refreshed
                    _uiState.update { state ->
                        val effective = refreshed.activeNetworkForTest(state.testViaProxy)
                        state.copy(
                            clientEgress = refreshed,
                            clientNetwork = effective ?: state.clientNetwork,
                        )
                    }
                }
            }

            val rawClientIp = egressSnapshot?.directNetwork?.rawIp
            val validatedIp = ReverseTracerouteCodec.validateClientPublicIp(rawClientIp).getOrElse { err ->
                if (reverseTraceGeneration.get() == revGen) {
                    val msg = err.message ?: "无法获取本机真实公网 IP"
                    _uiState.update {
                        it.copy(
                            isRunningReverseTrace = false,
                            reverseTraceError = msg,
                        )
                    }
                    _snackbarChannel.trySend(msg)
                }
                return
            }

            val command = ReverseTracerouteCodec.buildShellCommand(validatedIp)
            val cmdResult = manager.runOneShotCommand(
                profile = serverProfile,
                command = command,
                autoRespondPrompts = false,
                allowHostKeyMismatchOverride = allowHostKeyMismatchOverride,
                mismatchOverrideServerId = mismatchOverrideServerId,
                timeoutMillis = REVERSE_TRACE_SSH_TIMEOUT_MILLIS,
            )

            cmdResult.fold(
                onSuccess = { outcome ->
                    val parsedSummary = ReverseTracerouteCodec.parseOutput(
                        plainOutput = outcome.plainOutput,
                        targetIp = validatedIp,
                        sourceLabel = serverProfile.displayName,
                    )
                    val enrichedSummary = enrichReverseSummaryHops(parsedSummary)
                    if (reverseTraceGeneration.get() == revGen) {
                        _uiState.update { state ->
                            state.copy(
                                isRunningReverseTrace = false,
                                reverseTracerouteResult = enrichedSummary,
                                reverseTraceError = enrichedSummary.errorMessage,
                            ).withRegeneratedMarkdown()
                        }
                        if (emitCompletionSnackbar && enrichedSummary.errorMessage == null) {
                            _snackbarChannel.trySend("已完成「${serverProfile.displayName}」反向回程路由追踪")
                        }
                    }
                },
                onFailure = { err ->
                    if (reverseTraceGeneration.get() != revGen) return@fold
                    if (err is SshHostKeyMismatchException) {
                        val resolvedMismatchId = err.serverId ?: serverProfile.id
                        val resolvedAlias = err.serverAlias
                            ?: _uiState.value.vaultServers.firstOrNull { it.id == resolvedMismatchId }?.displayName
                            ?: serverProfile.displayName
                        _uiState.update {
                            it.copy(
                                isRunningReverseTrace = false,
                                reverseTraceError = "检测到主机密钥变更，已阻断连接",
                                pendingReverseTraceHostKeyMismatch = PendingReverseHostKeyMismatch(
                                    serverId = serverProfile.id,
                                    serverAlias = resolvedAlias,
                                    expectedFingerprint = err.expectedFingerprint,
                                    actualHostKey = err.actualHostKey,
                                    mismatchServerId = resolvedMismatchId,
                                ),
                            )
                        }
                    } else {
                        val msg = err.message?.takeIf { it.isNotBlank() } ?: "反向回程追踪失败"
                        _uiState.update {
                            it.copy(
                                isRunningReverseTrace = false,
                                reverseTraceError = msg,
                            )
                        }
                        if (emitCompletionSnackbar) {
                            _snackbarChannel.trySend(msg)
                        }
                    }
                },
            )
        } catch (ce: CancellationException) {
            if (reverseTraceGeneration.get() == revGen) {
                _uiState.update { it.copy(isRunningReverseTrace = false) }
            }
            throw ce
        } catch (t: Throwable) {
            if (reverseTraceGeneration.get() == revGen) {
                val msg = t.message?.takeIf { it.isNotBlank() } ?: "反向回程追踪异常"
                _uiState.update {
                    it.copy(
                        isRunningReverseTrace = false,
                        reverseTraceError = msg,
                    )
                }
            }
        }
    }

    private fun resolveReverseServerForBenchmark(
        state: MobileNetBenchUiState,
        rawInput: String,
        resolvedIp: String?,
    ): SshServerProfile? {
        val servers = state.vaultServers
        if (servers.isEmpty()) return null
        state.selectedReverseServerId?.let { id ->
            servers.firstOrNull { it.id == id }?.let { return it }
        }
        val normInput = normalizeHostForVaultMatch(rawInput)
        val normResolved = resolvedIp?.let(::normalizeHostForVaultMatch).orEmpty()
        return servers.firstOrNull { server ->
            val normServer = normalizeHostForVaultMatch(server.resolvableNetBenchHost())
            normServer.isNotEmpty() && (normServer == normInput || (normResolved.isNotEmpty() && normServer == normResolved))
        }
    }

    fun confirmTrustReverseTraceHostKey() {
        val mismatch = _uiState.value.pendingReverseTraceHostKeyMismatch ?: return
        val vault = sshVaultRepository ?: return
        val server = _uiState.value.vaultServers.firstOrNull { it.id == mismatch.serverId } ?: return
        _uiState.update { it.copy(pendingReverseTraceHostKeyMismatch = null) }
        scope.launch {
            vault.updateHostKeyFingerprint(
                serverId = mismatch.mismatchServerId,
                fingerprintSha256 = mismatch.actualHostKey.fingerprintSha256,
                algorithm = mismatch.actualHostKey.algorithm,
            )
            val refreshed = vault.serversFlow.value.firstOrNull { it.id == server.id }
                ?: server.copy(
                    knownHostKeyFingerprint = if (mismatch.mismatchServerId == server.id) {
                        mismatch.actualHostKey.fingerprintSha256
                    } else {
                        server.knownHostKeyFingerprint
                    },
                    knownHostKeyAlgorithm = if (mismatch.mismatchServerId == server.id) {
                        mismatch.actualHostKey.algorithm
                    } else {
                        server.knownHostKeyAlgorithm
                    },
                )
            runReverseTraceroute(
                serverProfile = refreshed,
                allowHostKeyMismatchOverride = false,
                mismatchOverrideServerId = null,
            )
        }
    }

    fun dismissReverseTraceHostKeyMismatch() {
        _uiState.update {
            it.copy(
                pendingReverseTraceHostKeyMismatch = null,
                reverseTraceError = "已因主机密钥变更取消反向回程追踪",
            )
        }
    }

    private suspend fun enrichReverseSummaryHops(summary: TracerouteSummary): TracerouteSummary {
        if (summary.hops.isEmpty()) return summary
        val candidateIps = summary.hops
            .asSequence()
            .mapNotNull { hop ->
                val ip = hop.ip ?: return@mapNotNull null
                if (hop.isPrivateOrCgnat || BackboneRouteTagger.isPrivateOrCgnat(ip)) return@mapNotNull null
                if (hop.asn != null && hop.countryCode != null) return@mapNotNull null
                ip
            }
            .distinct()
            .take(15)
            .toList()
        if (candidateIps.isEmpty()) return summary

        val sem = Semaphore(4)
        val intelMap = coroutineScope {
            candidateIps.map { ip ->
                async {
                    sem.withPermit {
                        val intel = runCatching {
                            withTimeoutOrNull(2_000L) { ipIntelligenceClient.lookupTargetIp(ip) }
                        }.getOrNull()
                        ip to intel
                    }
                }
            }.awaitAll().toMap()
        }

        val updatedHops = summary.hops.map { hop ->
            val intel = hop.ip?.let { intelMap[it] }
            if (intel == null) {
                hop
            } else {
                val mergedAsn = hop.asn ?: intel.asn
                val mergedOrg = hop.orgName ?: intel.orgName
                val mergedIsp = hop.ispName ?: intel.ispName
                val tags = BackboneRouteTagger.classifyHop(
                    ip = hop.ip,
                    asn = mergedAsn,
                    rdns = hop.hostname,
                    orgName = mergedOrg,
                    ispName = mergedIsp,
                )
                hop.copy(
                    asn = mergedAsn,
                    orgName = mergedOrg,
                    ispName = mergedIsp,
                    countryCode = hop.countryCode ?: intel.countryCode,
                    countryName = hop.countryName ?: intel.countryName,
                    regionName = hop.regionName ?: intel.regionName,
                    cityName = hop.cityName ?: intel.cityName,
                    backboneTags = if (tags.isNotEmpty()) tags else hop.backboneTags,
                )
            }
        }
        val lossClassified = BackboneRouteTagger.classifyHopLosses(updatedHops)
        val pathClass = BackboneRouteTagger.classifyPath(lossClassified, TracerouteDirection.REVERSE)
        val peakHop = lossClassified.filter { it.sent > 0 && it.received > 0 }.maxByOrNull { it.lossPercent }
        val hasRealLoss = lossClassified.any { it.lossClassification == HopLossClassification.REAL_PACKET_LOSS }
        val hasRateLimitOnly = !hasRealLoss &&
            lossClassified.any { it.lossClassification == HopLossClassification.INTERMEDIATE_RATE_LIMIT }
        return summary.copy(
            hops = lossClassified,
            peakHopLossPercent = peakHop?.lossPercent ?: 0.0,
            peakLossHopNumber = peakHop?.takeIf { it.lossPercent > 0.0 }?.hopNumber,
            hasRealPacketLoss = hasRealLoss,
            hasIntermediateRateLimitOnly = hasRateLimitOnly,
            dominantBackboneTags = pathClass.dominantTags,
            routeSignature = pathClass.routeSignature,
        )
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
            val msg = "请至少开启一项测机模块（Ping / TCPing / 去程 MTR / 回程 MTR / IP 鉴定 / iperf3）"
            _uiState.update { it.copy(errorMessage = msg) }
            _snackbarChannel.trySend(msg)
            return
        }

        val runGen = benchmarkGeneration.incrementAndGet()
        activeJob?.cancel()
        reverseTraceGeneration.incrementAndGet()
        reverseTraceJob?.cancel()
        reverseTraceJob = null
        cancelActiveImageUpload()
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
                tracerouteProgress = null,
                isRunningReverseTrace = false,
                reverseTracerouteResult = null,
                reverseTraceError = null,
                pendingReverseTraceHostKeyMismatch = null,
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
                val currentFp = withContext(ioDispatcher) {
                    networkBridge.inspectCurrentNetworks().plan.networkFingerprint
                }
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
                    if (benchmarkGeneration.get() == runGen) {
                        val errMsg = err.message ?: "无法解析目标主机"
                        _uiState.update {
                            it.copy(
                                isRunning = false,
                                activePhase = NetBenchPhase.ERROR,
                                errorMessage = errMsg,
                            )
                        }
                        _snackbarChannel.trySend(errMsg)
                    }
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

                // Step 1c: Optional Forward Visual Traceroute / MTR (before iperf3 saturation)
                if (snapshot.enableTraceroute) {
                    _uiState.update { it.copy(activePhase = NetBenchPhase.TRACEROUTE) }
                    val traceConfig = TracerouteConfig(
                        target = endpoint.resolvedTarget.resolvedIp,
                        probesPerHop = snapshot.tracerouteProbesPerHop,
                        preferredFamily = snapshot.preferredFamily,
                        useProxy = snapshot.testViaProxy,
                        tcpProbePort = effectiveTcpPort,
                    )
                    tracerouteRunner.run(traceConfig, preResolvedEndpoint = endpoint).collect { progress ->
                        val corrected = progress.copy(resolvedTarget = endpoint.resolvedTarget)
                        _uiState.update { state ->
                            state.copy(tracerouteProgress = corrected).withRegeneratedMarkdown()
                        }
                    }
                }

                // Collect local egress carrier info if ready (needed before Reverse MTR & Markdown)
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

                // Step 1d: Optional Reverse MTR via SSH VPS (before iperf3 link saturation)
                if (snapshot.enableReverseTraceroute) {
                    val manager = sshSessionManager
                    val targetServer = resolveReverseServerForBenchmark(
                        state = _uiState.value,
                        rawInput = rawInput,
                        resolvedIp = endpoint.resolvedTarget.resolvedIp,
                    )
                    when {
                        manager == null -> {
                            val msg = "SSH 会话管理器未就绪，请先前往「轻量 SSH」页面配置主机"
                            _uiState.update { it.copy(reverseTraceError = msg) }
                        }
                        targetServer == null -> {
                            val msg = "未匹配到对应的 SSH 服务器，请在「回程 MTR」开关下选择要执行回程追踪的 VPS"
                            _uiState.update { it.copy(reverseTraceError = msg) }
                        }
                        else -> {
                            val revGen = reverseTraceGeneration.incrementAndGet()
                            _uiState.update {
                                it.copy(
                                    activePhase = NetBenchPhase.REVERSE_TRACEROUTE,
                                    isRunningReverseTrace = true,
                                    selectedReverseServerId = targetServer.id,
                                    reverseTraceError = null,
                                    pendingReverseTraceHostKeyMismatch = null,
                                )
                            }
                            executeReverseTraceroute(
                                manager = manager,
                                serverProfile = targetServer,
                                revGen = revGen,
                                emitCompletionSnackbar = false,
                            )
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
                        preferredFamily = snapshot.preferredFamily,
                        preResolvedAddresses = listOf(endpoint.address),
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
                            omitSec = snapshot.iperfOmitSec,
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
                                val next = state.copy(iperfDownloadProgress = enriched)
                                if (enriched.finalResult != null) next.withRegeneratedMarkdown() else next
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
                            omitSec = snapshot.iperfOmitSec,
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
                                val next = state.copy(iperfUploadProgress = enriched)
                                if (enriched.finalResult != null) next.withRegeneratedMarkdown() else next
                            }
                        }
                    }
                }

                // Persist to recent history
                if (benchmarkGeneration.get() != runGen) return@launch
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

                if (benchmarkGeneration.get() == runGen) {
                    _uiState.update { state ->
                        state.copy(
                            isRunning = false,
                            activePhase = NetBenchPhase.COMPLETED,
                        ).withRegeneratedMarkdown()
                    }
                    syncReportImagesIfNeeded(promptForMissingKey = true)
                }
            } catch (ce: CancellationException) {
                if (benchmarkGeneration.get() == runGen) {
                    _uiState.update { state ->
                        state.copy(
                            isRunning = false,
                            activePhase = if (state.hasAnyResult) NetBenchPhase.CANCELLED else NetBenchPhase.IDLE,
                        ).withRegeneratedMarkdown()
                    }
                }
                throw ce
            } catch (t: Throwable) {
                if (benchmarkGeneration.get() == runGen) {
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
    }

    fun stopBenchmark(silent: Boolean = false) {
        val wasRunning = activeJob?.isActive == true
        val wasInReversePhase = _uiState.value.activePhase == NetBenchPhase.REVERSE_TRACEROUTE
        benchmarkGeneration.incrementAndGet()
        if (wasInReversePhase) {
            reverseTraceGeneration.incrementAndGet()
        }
        activeJob?.cancel()
        activeJob = null
        if (silent) {
            cancelActiveImageUpload()
        }
        _uiState.update { state ->
            state.copy(
                isRunning = false,
                isRunningReverseTrace = if (wasInReversePhase) false else state.isRunningReverseTrace,
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

        val uploadGen = imageUploadGeneration.incrementAndGet()
        imageUploadJob?.cancel()
        val maskTargetIp = snapshot.maskTargetIpInReport
        val includeClientNet = snapshot.includeClientNetworkInReport
        val darkTheme = snapshot.renderDarkTheme

        imageUploadJob = scope.launch {
            if (imageUploadGeneration.get() != uploadGen) return@launch
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
                    if (imageUploadGeneration.get() != uploadGen) return@launch
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
                            if (imageUploadGeneration.get() == uploadGen) {
                                _uiState.update { current -> current.withRegeneratedMarkdown() }
                            }
                        },
                        onFailure = { err ->
                            if (firstError == null) {
                                firstError = err.message ?: "上传至 NodeImage 图床失败"
                            }
                        },
                    )
                }

                if (imageUploadGeneration.get() == uploadGen) {
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
                }
            } catch (ce: CancellationException) {
                if (imageUploadGeneration.get() == uploadGen) {
                    _uiState.update { current ->
                        current.copy(
                            isUploadingReportImages = false,
                            reportImagesProgressText = null,
                        )
                    }
                }
                throw ce
            } catch (t: Throwable) {
                if (imageUploadGeneration.get() == uploadGen) {
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
    }

    // ── Purchase Baseline & Before-vs-After Comparison (R7) ───────────────────────────────

    fun openSaveBaselineDialog() {
        if (!_uiState.value.hasAnyResult) {
            _snackbarChannel.trySend("请先完成一次测机后再保存基线")
            return
        }
        _uiState.update { it.copy(showSaveBaselineDialog = true) }
    }

    fun dismissSaveBaselineDialog() {
        _uiState.update { it.copy(showSaveBaselineDialog = false) }
    }

    fun saveCurrentAsBaseline(
        label: String = "",
        notes: String = "",
    ) {
        val state = _uiState.value
        if (!state.hasAnyResult) return
        val matchedServerId = state.selectedReverseServerId ?: state.vaultServers.firstOrNull { server ->
            val normTarget = NetBenchTargetParser.parse(state.targetInput)?.host?.lowercase()
            normTarget != null && server.host.trim().equals(normTarget, ignoreCase = true)
        }?.id
        val snapshot = BaselineSnapshotExtractor.fromNetBenchReport(
            report = state.toNetBenchReport(),
            id = java.util.UUID.randomUUID().toString(),
            label = label.trim().ifBlank { null },
            serverId = matchedServerId,
        ).copy(notes = notes.trim())

        _uiState.update { it.copy(showSaveBaselineDialog = false) }
        scope.launch {
            val saved = benchmarkBaselineRepository.saveBaseline(snapshot)
            _snackbarChannel.trySend("已保存购入基线「${saved.label}」")
        }
    }

    fun openBaselineCompareSheet(baselineId: String? = null) {
        val state = _uiState.value
        if (!state.hasAnyResult) {
            _snackbarChannel.trySend("请先完成一次测机后再对比基线")
            return
        }
        val all = benchmarkBaselineRepository.baselinesFlow.value
        val matching = computeMatchingBaselines(state, all)
        val chosen = baselineId?.let { id -> all.firstOrNull { it.id == id } }
            ?: matching.firstOrNull()
            ?: all.firstOrNull()
        if (chosen == null) {
            _snackbarChannel.trySend("暂无已保存的购入基线")
            return
        }
        val currentSnap = BaselineSnapshotExtractor.fromNetBenchReport(
            report = state.toNetBenchReport(),
            id = "current_netbench",
            label = "当前实测",
            serverId = chosen.serverId,
        )
        val diff = BaselineDiffEngine.compare(chosen, currentSnap)
        _uiState.update {
            it.copy(
                savedBaselines = all,
                matchingBaselines = matching,
                selectedBaselineIdForCompare = chosen.id,
                activeBaselineDiff = diff,
                showBaselineCompareSheet = true,
            )
        }
    }

    fun dismissBaselineCompareSheet() {
        _uiState.update { it.copy(showBaselineCompareSheet = false) }
    }

    fun selectBaselineForComparison(baselineId: String) {
        val state = _uiState.value
        val all = state.savedBaselines
        val chosen = all.firstOrNull { it.id == baselineId } ?: return
        val currentSnap = BaselineSnapshotExtractor.fromNetBenchReport(
            report = state.toNetBenchReport(),
            id = "current_netbench",
            label = "当前实测",
            serverId = chosen.serverId,
        )
        val diff = BaselineDiffEngine.compare(chosen, currentSnap)
        _uiState.update {
            it.copy(
                selectedBaselineIdForCompare = chosen.id,
                activeBaselineDiff = diff,
            )
        }
    }

    fun deleteBaseline(baselineId: String) {
        scope.launch {
            benchmarkBaselineRepository.deleteBaseline(baselineId)
            val remaining = benchmarkBaselineRepository.baselinesFlow.value
            val state = _uiState.value
            val matching = computeMatchingBaselines(state, remaining)
            val nextSelected = matching.firstOrNull() ?: remaining.firstOrNull()
            val nextDiff = nextSelected?.let {
                val currentSnap = BaselineSnapshotExtractor.fromNetBenchReport(
                    report = state.toNetBenchReport(),
                    id = "current_netbench",
                    label = "当前实测",
                    serverId = it.serverId,
                )
                BaselineDiffEngine.compare(it, currentSnap)
            }
            _uiState.update {
                it.copy(
                    savedBaselines = remaining,
                    matchingBaselines = matching,
                    selectedBaselineIdForCompare = nextSelected?.id,
                    activeBaselineDiff = nextDiff,
                    showBaselineCompareSheet = if (remaining.isEmpty()) false else it.showBaselineCompareSheet,
                )
            }
            _snackbarChannel.trySend("已删除购入基线")
        }
    }

    private fun computeMatchingBaselines(
        state: MobileNetBenchUiState,
        allBaselines: List<BenchmarkBaselineSnapshot>,
    ): List<BenchmarkBaselineSnapshot> {
        val hostCandidate = state.resolvedTarget?.displayHost?.takeIf { it.isNotBlank() }
            ?: state.resolvedTarget?.resolvedIp?.takeIf { it.isNotBlank() }
            ?: state.targetInput.trim().takeIf { it.isNotEmpty() }
            ?: return emptyList()
        val key = BaselineSnapshotExtractor.normalizeTargetKey(hostOrIp = hostCandidate)
        val ipKey = state.resolvedTarget?.resolvedIp?.takeIf { it.isNotBlank() }?.let {
            BaselineSnapshotExtractor.normalizeTargetKey(hostOrIp = it)
        }
        return allBaselines.filter { b ->
            b.targetKey == key ||
                (ipKey != null && b.targetKey == ipKey) ||
                b.hostDisplay.equals(hostCandidate, ignoreCase = true) ||
                (state.resolvedTarget?.resolvedIp != null && b.resolvedIp == state.resolvedTarget.resolvedIp)
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
            forwardTraceroute = if (enableTraceroute) tracerouteProgress?.summary else null,
            reverseTraceroute = if (enableReverseTraceroute) reverseTracerouteResult else null,
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
        var resolvedImages = NetBenchUploadedImages()
        val plannedCount = if (includeReportImages && markdownStyle == NetBenchMarkdownStyle.FULL_TABLE) {
            val planned = cardImageGenerator.planCards(
                report = report,
                maskTargetIp = maskTargetIpInReport,
                includeClientNetwork = includeClientNetworkInReport,
                darkTheme = renderDarkTheme,
            )
            for ((kind, cacheKey) in planned) {
                val cachedUrl = uploadedUrlCache[cacheKey]
                if (!cachedUrl.isNullOrBlank()) {
                    resolvedImages = resolvedImages.withUrl(kind, cachedUrl)
                }
            }
            planned.size
        } else {
            estimatePlannedCardCount(report)
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
            plannedReportCardCount = plannedCount,
            matchingBaselines = computeMatchingBaselines(this, savedBaselines),
        )
    }

    private fun estimatePlannedCardCount(report: NetBenchReport): Int {
        var count = 0
        if (report.ipIntelligence?.let { it.hasCoreInfo || it.originType != com.nodeseek.app.domain.netbench.IpOriginType.UNKNOWN } == true) {
            count++
        }
        if (report.icmpPingSummary != null) count++
        if (report.tcpPingSummary != null) count++
        if (report.iperfDownloadResult?.success == true || report.iperfUploadResult?.success == true) {
            count++
        }
        return count
    }

    companion object {
        private const val REVERSE_TRACE_SSH_TIMEOUT_MILLIS = 60_000L
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
    httpLatencyRunner: HttpLatencyRunner = HttpLatencyRunner(
        networkBridge = networkBridge,
        targetResolver = targetResolver,
    ),
    iperf3ClientRunner: Iperf3ClientRunner = Iperf3ClientRunner(resolver = targetResolver, networkBridge = networkBridge),
    ipIntelligenceClient: IpIntelligenceClient = IpIntelligenceClient(networkBridge = networkBridge),
    imageHostRepository: ImageHostRepository? = null,
    settingsRepository: SettingsRepository? = null,
    cardImageGenerator: NetBenchCardImageGenerator = AndroidNetBenchCardImageGenerator,
    renderDispatcher: CoroutineDispatcher = Dispatchers.Default,
    initialTarget: String = "",
    sshVaultRepository: SshVaultRepository? = null,
    tracerouteRunner: TracerouteRunner = TracerouteRunner(
        resolver = targetResolver,
        networkBridge = networkBridge,
        ipIntelligenceClient = ipIntelligenceClient,
    ),
    sshSessionManager: LiteSshSessionManager? = null,
    benchmarkBaselineRepository: BenchmarkBaselineRepository = NoOpBenchmarkBaselineRepository,
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
        sshVaultRepository = sshVaultRepository,
        tracerouteRunner = tracerouteRunner,
        sshSessionManager = sshSessionManager,
        benchmarkBaselineRepository = benchmarkBaselineRepository,
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
    fun toggleTraceroute(enabled: Boolean) = controller.toggleTraceroute(enabled)
    fun toggleReverseTraceroute(enabled: Boolean) = controller.toggleReverseTraceroute(enabled)
    fun selectReverseServer(serverId: String?) = controller.selectReverseServer(serverId)
    fun updateTracerouteProbesPerHop(count: Int) = controller.updateTracerouteProbesPerHop(count)
    fun runForwardTracerouteOnly() = controller.runForwardTracerouteOnly()
    fun runReverseTraceroute(serverProfile: SshServerProfile) = controller.runReverseTraceroute(serverProfile)
    fun confirmTrustReverseTraceHostKey() = controller.confirmTrustReverseTraceHostKey()
    fun dismissReverseTraceHostKeyMismatch() = controller.dismissReverseTraceHostKeyMismatch()
    fun toggleIperf3(enabled: Boolean) = controller.toggleIperf3(enabled)
    fun openIperf3ServerPrompt() = controller.openIperf3ServerPrompt()
    fun dismissIperf3ServerPrompt() = controller.dismissIperf3ServerPrompt()
    fun toggleTestViaProxy(useProxy: Boolean) = controller.toggleTestViaProxy(useProxy)
    fun updateRenderDarkTheme(isDark: Boolean) = controller.updateRenderDarkTheme(isDark)
    fun refreshClientEgress() = controller.refreshClientEgress()
    fun updateIperfPortText(value: String) = controller.updateIperfPortText(value)
    fun updateIperfDurationSec(seconds: Int) = controller.updateIperfDurationSec(seconds)
    fun updateIperfOmitSec(seconds: Int) = controller.updateIperfOmitSec(seconds)
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
    fun applyVaultServer(server: SshServerProfile): Boolean = controller.applyVaultServer(server)
    fun removeHistoryEntry(entry: NetBenchHistoryEntry) = controller.removeHistoryEntry(entry)
    fun clearHistory() = controller.clearHistory()
    fun clearResults() = controller.clearResults()
    fun startBenchmark() = controller.startBenchmark()
    fun stopBenchmark() = controller.stopBenchmark()
    fun openSaveBaselineDialog() = controller.openSaveBaselineDialog()
    fun dismissSaveBaselineDialog() = controller.dismissSaveBaselineDialog()
    fun saveCurrentAsBaseline(label: String = "", notes: String = "") =
        controller.saveCurrentAsBaseline(label = label, notes = notes)
    fun openBaselineCompareSheet(baselineId: String? = null) = controller.openBaselineCompareSheet(baselineId)
    fun dismissBaselineCompareSheet() = controller.dismissBaselineCompareSheet()
    fun selectBaselineForComparison(baselineId: String) = controller.selectBaselineForComparison(baselineId)
    fun deleteBaseline(baselineId: String) = controller.deleteBaseline(baselineId)

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
            sshVaultRepository: SshVaultRepository? = null,
            sshSessionManager: LiteSshSessionManager? = null,
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
                    sshVaultRepository = sshVaultRepository ?: appContainer?.sshVaultRepository,
                    sshSessionManager = sshSessionManager ?: appContainer?.sshSessionManager,
                    benchmarkBaselineRepository = appContainer?.benchmarkBaselineRepository
                        ?: NoOpBenchmarkBaselineRepository,
                ) as T
            }
        }
    }
}
