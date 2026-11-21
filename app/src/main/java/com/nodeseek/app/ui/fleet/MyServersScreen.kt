@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.fleet

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.fleet.FleetWorkScheduler
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.domain.fleet.AssetExpiryUrgency
import com.nodeseek.app.domain.fleet.FleetPortfolioSummary
import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.RestockAssetPrefill
import com.nodeseek.app.domain.fleet.UpcomingRenewalItem
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.fleet.formatBytesBinary
import com.nodeseek.app.domain.fleet.formatSpeedBps
import com.nodeseek.app.domain.fleet.formatUptimeChinese
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.ui.components.PulseConfirmDialog
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.SortSegmentItem
import com.nodeseek.app.ui.components.SortSegmentedSwitcher
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseStaggeredEntrance
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.withTabularNumbers
import java.util.Locale

/**
 * Feature 4: "我的服务器" (My Servers — Multi-Panel Probe Monitor + VPS Asset Ledger + One-Click Sale Post).
 */
@Composable
fun MyServersScreen(
    onBack: () -> Unit,
    onCreateSalePost: (title: String, markdownBody: String) -> Unit,
    modifier: Modifier = Modifier,
    fleetRepository: FleetRepository? = null,
    exchangeRateRepository: ExchangeRateRepository? = null,
    fleetWorkScheduler: FleetWorkScheduler? = null,
    initialTab: MyServersTab = MyServersTab.PROBES,
    pendingRestockPrefill: RestockAssetPrefill? = null,
    onConsumeRestockPrefill: (() -> Unit)? = null,
    previewUiState: MyServersUiState? = null,
    viewModel: MyServersViewModel? = null,
) {
    val context = LocalContext.current
    val resolvedViewModel: MyServersViewModel = viewModel ?: run {
        val factory = remember(context, fleetRepository, exchangeRateRepository, fleetWorkScheduler, initialTab) {
            MyServersViewModel.factory(
                fleetRepository = fleetRepository ?: FleetRepositoryImpl.forContext(context),
                exchangeRateRepository = exchangeRateRepository ?: ExchangeRateRepository.forContext(context),
                workScheduler = fleetWorkScheduler ?: FleetWorkScheduler(context),
                initialTab = initialTab,
            )
        }
        viewModel(factory = factory)
    }

    val observedUiState by resolvedViewModel.uiState.collectAsStateWithLifecycle()
    val uiState = previewUiState ?: observedUiState
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var pendingDeleteAsset by remember { mutableStateOf<VpsAssetRecord?>(null) }

    LaunchedEffect(resolvedViewModel) {
        resolvedViewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(pendingRestockPrefill) {
        if (pendingRestockPrefill != null) {
            resolvedViewModel.prefillAssetFromRestock(
                name = pendingRestockPrefill.name,
                priceHint = pendingRestockPrefill.priceHint,
                purchaseUrl = pendingRestockPrefill.purchaseUrl,
            )
            onConsumeRestockPrefill?.invoke()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { PulseSnackbarHost(hostState = snackbarHostState) },
        topBar = {
            MyServersTopBar(
                isRefreshing = uiState.isRefreshing,
                selectedTab = uiState.selectedTab,
                onBack = onBack,
                onOpenPanelManager = resolvedViewModel::openPanelManager,
                onRefresh = resolvedViewModel::refreshAllProbes,
                onPrimaryAdd = {
                    if (uiState.selectedTab == MyServersTab.PROBES) {
                        resolvedViewModel.openPanelManager()
                    } else {
                        resolvedViewModel.openAddAssetSheet()
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Sticky Portfolio Hero + Segmented Tabs
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FleetPortfolioHeroCard(
                    summary = uiState.portfolioSummary,
                    usdRateDisplay = uiState.fxRatesState?.rateFor(ValuationCurrency.USD)?.let {
                        "1 USD ≈ ¥${it.setScale(2, java.math.RoundingMode.HALF_UP)}"
                    },
                    backgroundMonitorEnabled = uiState.monitorSettings.backgroundMonitorEnabled,
                    onRefreshFx = resolvedViewModel::refreshFxRates,
                    onOpenSettings = resolvedViewModel::openPanelManager,
                )

                val tabOptions = remember(uiState.snapshots.size, uiState.assets.size) {
                    listOf(
                        SortSegmentItem(
                            MyServersTab.PROBES,
                            "探针状态 (${uiState.snapshots.size})",
                        ),
                        SortSegmentItem(
                            MyServersTab.ASSETS,
                            "资产账本 (${uiState.assets.size})",
                        ),
                    )
                }
                SortSegmentedSwitcher(
                    options = tabOptions,
                    selectedValue = uiState.selectedTab,
                    onSelect = resolvedViewModel::selectTab,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            when (uiState.selectedTab) {
                MyServersTab.PROBES -> {
                    ProbesTabContent(
                        uiState = uiState,
                        onRefresh = resolvedViewModel::refreshAllProbes,
                        onSelectFilter = resolvedViewModel::selectProbeFilter,
                        onSelectPanelFilter = resolvedViewModel::selectPanelFilter,
                        onOpenPanelManager = resolvedViewModel::openPanelManager,
                        onAddAssetFromProbe = resolvedViewModel::openAddAssetFromProbe,
                    )
                }
                MyServersTab.ASSETS -> {
                    AssetsTabContent(
                        uiState = uiState,
                        onAddAsset = resolvedViewModel::openAddAssetSheet,
                        onEditAsset = resolvedViewModel::openEditAssetSheet,
                        onRequestDeleteAsset = { pendingDeleteAsset = it },
                        onBindProbe = resolvedViewModel::openProbeBinder,
                        onOpenSalePost = resolvedViewModel::openSalePostSheet,
                        onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
                    )
                }
            }
        }
    }

    // ── Modal Sheets & Dialogs ────────────────────────────────────────────────

    if (uiState.showPanelManagerSheet) {
        ProbePanelManagerSheet(
            panels = uiState.panels,
            monitorSettings = uiState.monitorSettings,
            testState = uiState.panelTestState,
            onTestConnection = resolvedViewModel::testPanelConnection,
            onClearTestState = resolvedViewModel::clearPanelTestState,
            onSavePanel = resolvedViewModel::savePanel,
            onDeletePanel = resolvedViewModel::deletePanel,
            onToggleOfflineAlert = resolvedViewModel::togglePanelOfflineAlert,
            onUpdateMonitorSettings = resolvedViewModel::updateMonitorSettings,
            onDismiss = resolvedViewModel::closePanelManager,
        )
    }

    uiState.assetEditorDraft?.let { draft ->
        AssetEditorBottomSheet(
            draft = draft,
            availableProbes = uiState.snapshots,
            onSave = resolvedViewModel::saveAsset,
            onDismiss = resolvedViewModel::closeAssetEditor,
        )
    }

    uiState.salePostAsset?.let { asset ->
        val linkedProbe = remember(asset.linkedProbeKey, uiState.snapshots) {
            asset.linkedProbeKey?.let { key -> uiState.snapshots.firstOrNull { it.probeKey == key } }
        }
        FleetSalePostSheet(
            asset = asset,
            linkedProbe = linkedProbe,
            fxRatesState = uiState.fxRatesState,
            onCreateSalePost = onCreateSalePost,
            onCopiedFeedback = { msg ->
                coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
            },
            onDismiss = resolvedViewModel::closeSalePostSheet,
        )
    }

    uiState.bindingProbeAsset?.let { asset ->
        ProbeBindBottomSheet(
            asset = asset,
            probes = uiState.snapshots,
            onSelectProbeKey = { key -> resolvedViewModel.bindAssetToProbe(asset.id, key) },
            onDismiss = resolvedViewModel::closeProbeBinder,
        )
    }

    pendingDeleteAsset?.let { asset ->
        PulseConfirmDialog(
            onDismissRequest = { pendingDeleteAsset = null },
            title = "删除资产记录",
            message = "确定要从资产账本中移除「${asset.nickname}」吗？该操作不会影响远端探针面板。",
            confirmText = "确认删除",
            tone = PulseDialogTone.DESTRUCTIVE,
            icon = Icons.Rounded.DeleteOutline,
            onConfirm = {
                resolvedViewModel.deleteAsset(asset.id)
                pendingDeleteAsset = null
            },
        )
    }
}

@Composable
private fun MyServersTopBar(
    isRefreshing: Boolean,
    selectedTab: MyServersTab,
    onBack: () -> Unit,
    onOpenPanelManager: () -> Unit,
    onRefresh: () -> Unit,
    onPrimaryAdd: () -> Unit,
) {
    val spinTransition = rememberInfiniteTransition(label = "fleetRefreshSpin")
    val spinAngle by spinTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "fleetRefreshAngle",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseGlassSurface(showBottomBorder = true)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "我的服务器",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "哪吒 / Komari 探针 · 资产账本 · 一键出机",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onOpenPanelManager) {
            Icon(Icons.Rounded.Tune, contentDescription = "管理探针面板")
        }
        IconButton(onClick = onRefresh, enabled = !isRefreshing) {
            Icon(
                Icons.Rounded.Refresh,
                contentDescription = "刷新探针",
                modifier = Modifier.rotate(if (isRefreshing) spinAngle else 0f),
            )
        }
        IconButton(onClick = onPrimaryAdd) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = if (selectedTab == MyServersTab.PROBES) "添加探针面板" else "添加服务器资产",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun FleetPortfolioHeroCard(
    summary: FleetPortfolioSummary,
    usdRateDisplay: String?,
    backgroundMonitorEnabled: Boolean,
    onRefreshFx: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val onlineValueColor = if (summary.offlineProbeCount > 0) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val renewalValueColor = if (summary.dueSoonRenewalCount > 0) {
        ext.onDrumstickContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.HeroBento,
                containerColor = ext.cardSurface,
                elevation = PulseElevation.Card,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Clean 4-column borderless KPI telemetry strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HeroKpiColumn(
                label = "探针在线",
                value = "${summary.onlineProbeCount}/${summary.totalProbeNodeCount} 台",
                valueColor = onlineValueColor,
                modifier = Modifier.weight(0.95f),
            )
            HeroKpiColumn(
                label = "月均支出",
                value = "¥${summary.totalMonthlyCostCny.toPlainString()}",
                valueColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1.1f),
            )
            HeroKpiColumn(
                label = "年化支出",
                value = "¥${summary.totalAnnualizedCostCny.toPlainString()}",
                modifier = Modifier.weight(1.15f),
            )
            HeroKpiColumn(
                label = "近期续费",
                value = "${summary.dueSoonRenewalCount} 台",
                valueColor = renewalValueColor,
                modifier = Modifier.weight(0.8f),
            )
        }

        PulseGradientDivider(color = ext.cardBorder.copy(alpha = 0.55f))

        // Compact Footer Strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "账本在册 ${summary.totalMachineCount} 台" +
                    (usdRateDisplay?.let { " · $it" }.orEmpty()),
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(end = 8.dp)
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .clickable(onClick = onRefreshFx)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(0.75.dp, ext.cardBorder, CircleShape)
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (backgroundMonitorEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        ),
                )
                Text(
                    text = if (backgroundMonitorEnabled) "后台巡检中" else "巡检已停",
                    style = NodePulseThemeTokens.extraTypography.microLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun HeroKpiColumn(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
            ).withTabularNumbers(),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = NodePulseThemeTokens.extraTypography.microLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Tab 1: Probes Tab ─────────────────────────────────────────────────────────

@Composable
private fun ProbesTabContent(
    uiState: MyServersUiState,
    onRefresh: () -> Unit,
    onSelectFilter: (ProbeOnlineFilter) -> Unit,
    onSelectPanelFilter: (String?) -> Unit,
    onOpenPanelManager: () -> Unit,
    onAddAssetFromProbe: (ProbeServerSnapshot) -> Unit,
) {
    val panelsById = remember(uiState.panels) { uiState.panels.associateBy { it.id } }
    val assetsByProbeKey = remember(uiState.assets) {
        uiState.assets.mapNotNull { asset ->
            asset.linkedProbeKey?.let { key -> key to asset }
        }.toMap()
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Online / Offline & Panel Filter Bar
            item(key = "probe_filter_bar") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ProbeOnlineFilter.entries.forEach { filter ->
                                val count = when (filter) {
                                    ProbeOnlineFilter.ALL -> uiState.snapshots.size
                                    ProbeOnlineFilter.ONLINE -> uiState.snapshots.count { it.isOnline }
                                    ProbeOnlineFilter.OFFLINE -> uiState.snapshots.count { !it.isOnline }
                                }
                                FleetChoiceChip(
                                    label = "${filter.label} $count",
                                    selected = uiState.probeFilter == filter,
                                    onClick = { onSelectFilter(filter) },
                                )
                            }
                        }
                        FleetChoiceChip(
                            label = "管理面板",
                            selected = false,
                            onClick = onOpenPanelManager,
                        )
                    }

                    if (uiState.panels.size > 1) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FleetChoiceChip(
                                label = "全部面板",
                                selected = uiState.selectedPanelIdFilter == null,
                                onClick = { onSelectPanelFilter(null) },
                            )
                            uiState.panels.forEach { panel ->
                                FleetChoiceChip(
                                    label = panel.name,
                                    selected = uiState.selectedPanelIdFilter == panel.id,
                                    onClick = { onSelectPanelFilter(panel.id) },
                                )
                            }
                        }
                    }
                }
            }

            // Panel Error Banner
            if (uiState.panelErrors.isNotEmpty()) {
                item(key = "panel_errors") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(PulseArchitecturalShapes.EditorialCard)
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "部分探针面板同步异常",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        uiState.panelErrors.forEach { (panelId, err) ->
                            val panelName = panelsById[panelId]?.name ?: panelId
                            Text(
                                text = "• $panelName：$err",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            // Empty States
            if (uiState.panels.isEmpty()) {
                item(key = "probes_empty_panels") {
                    FleetEmptyStateCard(
                        icon = Icons.Rounded.Dns,
                        title = "尚未接入探针面板",
                        description = "支持同时接入多个哪吒 v0、哪吒 v1 与 Komari 面板，实时查看 CPU、内存、硬盘、月流量与离线高优告警。",
                        actionLabel = "添加第一个探针面板",
                        onAction = onOpenPanelManager,
                    )
                }
            } else if (uiState.filteredSnapshots.isEmpty()) {
                item(key = "probes_empty_filtered") {
                    FleetEmptyStateCard(
                        icon = Icons.Rounded.CloudDone,
                        title = if (uiState.snapshots.isEmpty()) "暂无节点数据" else "当前筛选条件下无节点",
                        description = if (uiState.snapshots.isEmpty()) {
                            "下拉刷新以从已配置的探针面板拉取服务器状态。"
                        } else {
                            "可切换「全部 / 在线 / 离线」筛选条件查看更多节点。"
                        },
                        actionLabel = if (uiState.snapshots.isEmpty()) "立即同步" else "查看全部节点",
                        onAction = {
                            if (uiState.snapshots.isEmpty()) onRefresh() else onSelectFilter(ProbeOnlineFilter.ALL)
                        },
                    )
                }
            } else {
                itemsIndexed(
                    items = uiState.filteredSnapshots,
                    key = { _, snap -> snap.probeKey },
                ) { index, snapshot ->
                    val panel = panelsById[snapshot.panelId]
                    val linkedAsset = assetsByProbeKey[snapshot.probeKey]
                    PulseStaggeredEntrance(index = index) {
                        ProbeServerCard(
                            snapshot = snapshot,
                            panel = panel,
                            linkedAsset = linkedAsset,
                            onAddAssetFromProbe = { onAddAssetFromProbe(snapshot) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProbeServerCard(
    snapshot: ProbeServerSnapshot,
    panel: ProbePanelConfig?,
    linkedAsset: VpsAssetRecord?,
    onAddAssetFromProbe: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val dotColor = if (snapshot.isOnline) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    val statusBg = if (snapshot.isOnline) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val statusFg = if (snapshot.isOnline) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    val cardBorderColor = if (snapshot.isOnline) {
        ext.cardBorder
    } else {
        MaterialTheme.colorScheme.error.copy(alpha = 0.42f)
    }

    val effectiveTrafficLimit = snapshot.trafficLimitBytes
        ?: linkedAsset?.monthlyTrafficQuotaGb?.takeIf { it > 0.0 }?.let { gb ->
            (gb * 1024.0 * 1024.0 * 1024.0).toLong()
        }
    val trafficRatio = effectiveTrafficLimit?.takeIf { it > 0L }?.let { limit ->
        (snapshot.totalMonthlyTransferBytes.toDouble() / limit.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = ext.cardSurface,
                borderColor = cardBorderColor,
                elevation = PulseElevation.Card,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header Row: Region Code Badge + Name + Online Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val codeBadge = snapshot.countryCode.trim().uppercase(Locale.US).ifBlank { "VPS" }
            Box(
                modifier = Modifier
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(0.75.dp, ext.cardBorder, PulseArchitecturalShapes.SmallChip)
                    .padding(horizontal = 7.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = codeBadge,
                    style = NodePulseThemeTokens.extraTypography.codeLabel.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = snapshot.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitleParts = buildList {
                    panel?.name?.takeIf { it.isNotBlank() }?.let { add(it) }
                    snapshot.os.takeIf { it.isNotBlank() }?.let { add(it) }
                    if (snapshot.uptimeSeconds > 0L) add("运行 ${formatUptimeChinese(snapshot.uptimeSeconds)}")
                }
                if (subtitleParts.isNotEmpty()) {
                    Text(
                        text = subtitleParts.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(statusBg)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Text(
                    text = if (snapshot.isOnline) "在线" else "离线",
                    style = NodePulseThemeTokens.extraTypography.microLabel,
                    color = statusFg,
                )
            }
        }

        // Resource Progress Bars (CPU, RAM, Disk, Monthly Traffic)
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ResourceMetricBar(
                label = "CPU",
                detail = String.format(Locale.US, "%.1f%%", snapshot.cpuPercent.coerceIn(0.0, 100.0)) +
                    (snapshot.load1?.let { String.format(Locale.US, " · Load %.2f", it) }.orEmpty()),
                progress = (snapshot.cpuPercent / 100.0).toFloat().coerceIn(0f, 1f),
            )
            ResourceMetricBar(
                label = "内存",
                detail = if (snapshot.memTotalBytes > 0L) {
                    "${formatBytesBinary(snapshot.memUsedBytes)} / ${formatBytesBinary(snapshot.memTotalBytes)} (${(snapshot.memUsageRatio * 100).toInt()}%)"
                } else {
                    formatBytesBinary(snapshot.memUsedBytes)
                },
                progress = snapshot.memUsageRatio,
            )
            if (snapshot.diskTotalBytes > 0L || snapshot.diskUsedBytes > 0L) {
                ResourceMetricBar(
                    label = "硬盘",
                    detail = if (snapshot.diskTotalBytes > 0L) {
                        "${formatBytesBinary(snapshot.diskUsedBytes)} / ${formatBytesBinary(snapshot.diskTotalBytes)} (${(snapshot.diskUsageRatio * 100).toInt()}%)"
                    } else {
                        formatBytesBinary(snapshot.diskUsedBytes)
                    },
                    progress = snapshot.diskUsageRatio,
                )
            }
            if (snapshot.totalMonthlyTransferBytes > 0L || effectiveTrafficLimit != null) {
                val trafficDetail = buildString {
                    append("↓ ${formatBytesBinary(snapshot.monthlyTransferInBytes)}  ↑ ${formatBytesBinary(snapshot.monthlyTransferOutBytes)}")
                    if (effectiveTrafficLimit != null) {
                        append(" / ${formatBytesBinary(effectiveTrafficLimit)}")
                    }
                }
                ResourceMetricBar(
                    label = "流量",
                    detail = trafficDetail,
                    progress = trafficRatio ?: 0f,
                    showTrack = trafficRatio != null,
                )
            }
        }

        PulseGradientDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

        // Footer: Live Network Speed & Asset Link Status
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
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(
                        Icons.Rounded.ArrowDownward,
                        contentDescription = "下行",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = formatSpeedBps(snapshot.netInSpeedBps),
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(
                        Icons.Rounded.ArrowUpward,
                        contentDescription = "上行",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = formatSpeedBps(snapshot.netOutSpeedBps),
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            if (linkedAsset != null) {
                PulseAccentPill(
                    text = "账本：${linkedAsset.nickname}",
                    icon = Icons.Rounded.Link,
                    modifier = Modifier.widthIn(max = 148.dp),
                )
            } else {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .pulseBouncyClickable(
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.Button,
                            onClick = onAddAssetFromProbe,
                        )
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = "录入账本",
                        style = NodePulseThemeTokens.extraTypography.microLabel,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResourceMetricBar(
    label: String,
    detail: String,
    progress: Float,
    showTrack: Boolean = true,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
        label = "fleetMetricProgress",
    )
    val barColor = if (progress >= 0.90f) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showTrack) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress.coerceAtLeast(0.01f))
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(barColor),
                )
            }
        }
    }
}

// ── Tab 2: Assets Ledger Tab ──────────────────────────────────────────────────

@Composable
private fun AssetsTabContent(
    uiState: MyServersUiState,
    onAddAsset: () -> Unit,
    onEditAsset: (VpsAssetRecord) -> Unit,
    onRequestDeleteAsset: (VpsAssetRecord) -> Unit,
    onBindProbe: (VpsAssetRecord) -> Unit,
    onOpenSalePost: (VpsAssetRecord) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val renewalsByAssetId = remember(uiState.portfolioSummary.upcomingRenewals) {
        uiState.portfolioSummary.upcomingRenewals.associateBy { it.asset.id }
    }
    val probesByKey = remember(uiState.snapshots) {
        uiState.snapshots.associateBy { it.probeKey }
    }
    val sortedAssets = remember(uiState.assets, renewalsByAssetId) {
        uiState.assets.sortedWith(
            compareBy<VpsAssetRecord> { renewalsByAssetId[it.id]?.daysRemaining ?: Long.MAX_VALUE }
                .thenBy { it.nickname.lowercase() },
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "assets_header_bar") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "按到期倒计时排序 · 共 ${uiState.assets.size} 台",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                )
                FilledTonalButton(
                    onClick = onAddAsset,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "录入小鸡",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        if (sortedAssets.isEmpty()) {
            item(key = "assets_empty") {
                FleetEmptyStateCard(
                    icon = Icons.Rounded.Storage,
                    title = "资产账本还是空的",
                    description = "记录每台 VPS 的配置、线路、续费币种与到期日，自动折算月均/年化人民币开销，并支持「不想续了 · 一键估值出机」。",
                    actionLabel = "录入第一台服务器",
                    onAction = onAddAsset,
                )
            }
        } else {
            itemsIndexed(
                items = sortedAssets,
                key = { _, asset -> asset.id },
            ) { index, asset ->
                val renewal = renewalsByAssetId[asset.id]
                val linkedProbe = asset.linkedProbeKey?.let { probesByKey[it] }
                PulseStaggeredEntrance(index = index) {
                    VpsAssetCard(
                        asset = asset,
                        renewalItem = renewal,
                        linkedProbe = linkedProbe,
                        onEdit = { onEditAsset(asset) },
                        onDelete = { onRequestDeleteAsset(asset) },
                        onBindProbe = { onBindProbe(asset) },
                        onOpenSalePost = { onOpenSalePost(asset) },
                        onOpenUrl = onOpenUrl,
                    )
                }
            }
        }
    }
}

@Composable
private fun VpsAssetCard(
    asset: VpsAssetRecord,
    renewalItem: UpcomingRenewalItem?,
    linkedProbe: ProbeServerSnapshot?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onBindProbe: () -> Unit,
    onOpenSalePost: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors

    val badgeText = when (renewalItem?.urgency) {
        AssetExpiryUrgency.EXPIRED -> {
            val overdue = (-renewalItem.daysRemaining).coerceAtLeast(1L)
            "已过期 ${overdue}天"
        }
        AssetExpiryUrgency.CRITICAL -> {
            if (renewalItem.daysRemaining == 0L) "今日到期" else "剩 ${renewalItem.daysRemaining} 天"
        }
        AssetExpiryUrgency.WARNING,
        AssetExpiryUrgency.NORMAL,
        -> "剩 ${renewalItem.daysRemaining} 天"
        else -> "日期待补"
    }
    val badgeFg = when (renewalItem?.urgency) {
        AssetExpiryUrgency.EXPIRED,
        AssetExpiryUrgency.CRITICAL,
        -> MaterialTheme.colorScheme.onErrorContainer
        AssetExpiryUrgency.WARNING -> ext.onDrumstickContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val badgeBg = when (renewalItem?.urgency) {
        AssetExpiryUrgency.EXPIRED,
        AssetExpiryUrgency.CRITICAL,
        -> MaterialTheme.colorScheme.errorContainer
        AssetExpiryUrgency.WARNING -> ext.drumstickContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = ext.cardSurface,
                borderColor = ext.cardBorder,
                elevation = PulseElevation.Card,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // Header: Nickname + Countdown Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = asset.nickname,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val metaParts = buildList {
                    if (asset.providerName.isNotBlank()) add(asset.providerName)
                    if (asset.region.isNotBlank()) add(asset.region)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (metaParts.isNotEmpty()) {
                        Text(
                            text = metaParts.joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (asset.routeTag.isNotBlank()) {
                        PulseAccentPill(text = asset.routeTag)
                    }
                }
            }

            PulseAccentPill(
                text = badgeText,
                containerColor = badgeBg,
                contentColor = badgeFg,
            )
        }

        // Specs & Billing Details
        if (asset.specsSummary.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Rounded.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = asset.specsSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PulseArchitecturalShapes.SmallChip)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val origCost = if (asset.currency == ValuationCurrency.CNY) {
                "¥${asset.renewalAmount}"
            } else {
                "${asset.currency.symbol}${asset.renewalAmount} ${asset.currency.code}"
            }
            val cnySuffix = renewalItem?.let { " ≈ ¥${it.renewalCny.toPlainString()}" }.orEmpty()
            Text(
                text = "$origCost / ${asset.cyclePreset.shortLabel}$cnySuffix",
                style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            )
            Text(
                text = "到期 ${asset.expiryDateIso}",
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }

        // Linked Probe Telemetry Strip (if bound)
        if (linkedProbe != null) {
            val probeDot = if (linkedProbe.isOnline) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(0.75.dp, ext.cardBorder, PulseArchitecturalShapes.SmallChip)
                    .clickable(onClick = onBindProbe)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
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
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(probeDot),
                    )
                    Text(
                        text = linkedProbe.name,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = String.format(
                        Locale.US,
                        "CPU %.0f%% · RAM %d%%",
                        linkedProbe.cpuPercent,
                        (linkedProbe.memUsageRatio * 100).toInt(),
                    ),
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        PulseGradientDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

        // Action Bar: Edit / Bind / Links + Prominent "不想续了 · 一键出机"
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onBindProbe, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Rounded.Link,
                        contentDescription = "绑定探针",
                        tint = if (linkedProbe != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (asset.panelManageUrl.isNotBlank() || asset.purchaseUrl.isNotBlank()) {
                    IconButton(
                        onClick = { onOpenUrl(asset.panelManageUrl.ifBlank { asset.purchaseUrl }) },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = "打开面板/购买链接",
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // Signature CTA: "不想续了 · 一键出机"
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .pulseBouncyClickable(
                        pressedScale = NodePulseMotion.chipPressScale,
                        role = Role.Button,
                        onClick = onOpenSalePost,
                    )
                    .padding(horizontal = 13.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    Icons.Rounded.LocalOffer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "不想续了 · 一键出机",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ProbeBindBottomSheet(
    asset: VpsAssetRecord,
    probes: List<ProbeServerSnapshot>,
    onSelectProbeKey: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "为「${asset.nickname}」绑定探针节点",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (probes.isEmpty()) {
                Text(
                    text = "当前暂无已同步的探针节点，请先在「探针状态」页添加并同步面板。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(PulseArchitecturalShapes.MediumBadge)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { onSelectProbeKey(null) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("不绑定探针", style = MaterialTheme.typography.bodyMedium)
                    if (asset.linkedProbeKey == null) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                probes.forEach { snap ->
                    val selected = asset.linkedProbeKey == snap.probeKey
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(PulseArchitecturalShapes.MediumBadge)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                            )
                            .clickable { onSelectProbeKey(snap.probeKey) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val codePrefix = snap.countryCode.trim().uppercase(Locale.US)
                                .takeIf { it.isNotBlank() }
                                ?.let { "[$it] " }
                                .orEmpty()
                            Text(
                                text = "$codePrefix${snap.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            )
                            Text(
                                text = "${if (snap.isOnline) "在线" else "离线"} · ${snap.os.ifBlank { snap.remoteServerId }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selected) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun FleetEmptyStateCard(
    icon: ImageVector,
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.HeroBento,
                containerColor = ext.cardSurface,
                elevation = PulseElevation.Subtle,
            )
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Button(onClick = onAction, shape = CircleShape) {
            Text(actionLabel)
        }
    }
}
