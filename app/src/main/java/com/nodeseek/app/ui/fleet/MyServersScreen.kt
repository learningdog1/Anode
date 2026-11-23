@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.fleet

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.fleet.FleetWorkScheduler
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.domain.fleet.AssetExpiryUrgency
import com.nodeseek.app.domain.fleet.FleetPortfolioSummary
import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.fleet.FleetTrafficAndIdleAnalyzer
import com.nodeseek.app.domain.fleet.ProbePanelConfig
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.RestockAssetPrefill
import com.nodeseek.app.domain.fleet.ServerIdleAssessment
import com.nodeseek.app.domain.fleet.ServerTrafficForecast
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
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.pulseHeroCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

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
    previewProbeViewMode: ProbeViewMode? = null,
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val isInspectionMode = LocalInspectionMode.current
    var pendingDeleteAsset by remember { mutableStateOf<VpsAssetRecord?>(null) }
    var pendingRenewRequest by remember { mutableStateOf<Pair<VpsAssetRecord, UpcomingRenewalItem?>?>(null) }

    val hasEnabledPanels = remember(uiState.panels) { uiState.panels.any { it.enabled } }
    val existingAssetTags = remember(uiState.assets) { VpsAssetRecord.collectDistinctTags(uiState.assets) }

    LaunchedEffect(resolvedViewModel) {
        resolvedViewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Foreground-only 2-second live telemetry loop; cancels immediately on ON_PAUSE/leave.
    LaunchedEffect(lifecycleOwner, resolvedViewModel, previewUiState, isInspectionMode, hasEnabledPanels) {
        if (!isInspectionMode && previewUiState == null && hasEnabledPanels) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                resolvedViewModel.runForegroundProbeAutoRefresh()
            }
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
                showLiveBadge = hasEnabledPanels && uiState.selectedTab == MyServersTab.PROBES,
                animateLiveDot = !isInspectionMode && previewUiState == null,
                onBack = onBack,
                onOpenPanelManager = resolvedViewModel::openPanelManager,
                onRefresh = resolvedViewModel::refreshCurrentTab,
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
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FleetPortfolioHeroCard(
                    summary = uiState.portfolioSummary,
                    usdRateDisplay = uiState.fxRatesState?.rateFor(ValuationCurrency.USD)?.let {
                        "1 USD ≈ ¥${it.setScale(2, java.math.RoundingMode.HALF_UP)}"
                    },
                    backgroundMonitorEnabled = uiState.monitorSettings.backgroundMonitorEnabled,
                    onRefreshFx = resolvedViewModel::refreshFxRates,
                    onOpenMonitorSettings = resolvedViewModel::openMonitorSettings,
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
                        previewViewMode = previewProbeViewMode,
                        onRefresh = resolvedViewModel::refreshAllProbes,
                        onSelectFilter = resolvedViewModel::selectProbeFilter,
                        onSelectPanelFilter = resolvedViewModel::selectPanelFilter,
                        onOpenPanelManager = resolvedViewModel::openPanelManager,
                        onAddAssetFromProbe = resolvedViewModel::openAddAssetFromProbe,
                        onOpenIdleSalePost = resolvedViewModel::openIdleSalePost,
                        onOpenLinkedAsset = resolvedViewModel::openEditAssetSheet,
                    )
                }
                MyServersTab.ASSETS -> {
                    AssetsTabContent(
                        uiState = uiState,
                        onAddAsset = resolvedViewModel::openAddAssetSheet,
                        onEditAsset = resolvedViewModel::openEditAssetSheet,
                        onRequestRenewAsset = { asset, renewal -> pendingRenewRequest = asset to renewal },
                        onRequestDeleteAsset = { pendingDeleteAsset = it },
                        onBindProbe = resolvedViewModel::openProbeBinder,
                        onOpenSalePost = resolvedViewModel::openSalePostSheet,
                        onOpenIdleSalePost = resolvedViewModel::openIdleSalePost,
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
            saveError = uiState.panelSaveError,
            onClearSaveError = resolvedViewModel::clearPanelSaveError,
            initialSection = uiState.panelManagerInitialSection,
        )
    }

    uiState.assetEditorDraft?.let { draft ->
        AssetEditorBottomSheet(
            draft = draft,
            availableProbes = uiState.snapshots,
            onSave = resolvedViewModel::saveAsset,
            onDismiss = resolvedViewModel::closeAssetEditor,
            saveError = uiState.assetSaveError,
            onClearSaveError = resolvedViewModel::clearAssetSaveError,
            existingTags = existingAssetTags,
            initialTags = draft.tags,
            initialAutoRenew = draft.autoRenew,
        )
    }

    uiState.salePostAsset?.let { asset ->
        val linkedProbe = remember(asset.linkedProbeKey, uiState.snapshots) {
            asset.linkedProbeKey?.let { key -> uiState.snapshots.firstOrNull { it.probeKey == key } }
        }
        val idleAssessment = remember(asset.linkedProbeKey, uiState.idleAssessmentsByProbeKey) {
            asset.linkedProbeKey?.let { key -> uiState.idleAssessmentsByProbeKey[key] }
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
            idleAssessment = idleAssessment,
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

    pendingRenewRequest?.let { (asset, renewal) ->
        val nextIso = renewal?.nextExpiryAfterRenewalIso
        val cycles = renewal?.cyclesToAdvanceOnRenewal ?: 1
        val renewMessage = buildString {
            append("确认将「${asset.nickname}」标记为已续费？")
            if (nextIso != null) {
                if (cycles > 1) {
                    append("\n到期日将按${asset.cyclePreset.shortLabel}周期顺延 $cycles 期：${asset.expiryDateIso} → $nextIso。")
                } else {
                    append("\n到期日将按${asset.cyclePreset.shortLabel}周期顺延：${asset.expiryDateIso} → $nextIso。")
                }
            } else {
                append("\n到期日将按${asset.cyclePreset.shortLabel}周期自动顺延。")
            }
        }
        PulseConfirmDialog(
            onDismissRequest = { pendingRenewRequest = null },
            title = "确认已完成续费",
            message = renewMessage,
            confirmText = "确认已续费",
            tone = PulseDialogTone.PRIMARY,
            icon = Icons.Rounded.EventRepeat,
            onConfirm = {
                resolvedViewModel.markAssetRenewed(asset.id)
                pendingRenewRequest = null
            },
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
    showLiveBadge: Boolean,
    animateLiveDot: Boolean,
    onBack: () -> Unit,
    onOpenPanelManager: () -> Unit,
    onRefresh: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseGlassSurface(showBottomBorder = true)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "我的服务器",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showLiveBadge) {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f))
                            .border(
                                width = 0.75.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                shape = CircleShape,
                            )
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        LivePulseDot(
                            color = MaterialTheme.colorScheme.primary,
                            animate = animateLiveDot,
                        )
                        Text(
                            text = "2s",
                            style = NodePulseThemeTokens.extraTypography.microLabel,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            Text(
                text = "哪吒 / Komari 探针 · 资产账本 · 一键出机",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Probe panel & monitor manager quick entry
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .pulseBouncyClickable(
                    pressedScale = NodePulseMotion.chipPressScale,
                    role = Role.Button,
                    onClickLabel = "管理探针面板",
                    onClick = onOpenPanelManager,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = "管理探针面板",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }

        val refreshModifier = if (isRefreshing) {
            val spinTransition = rememberInfiniteTransition(label = "fleetRefreshSpin")
            val spinAngle = spinTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "fleetRefreshAngle",
            )
            Modifier.graphicsLayer { rotationZ = spinAngle.value }
        } else {
            Modifier
        }

        Box(
            modifier = Modifier
                .padding(end = 4.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.75f))
                .border(0.75.dp, ext.cardBorder, CircleShape)
                .pulseBouncyClickable(
                    enabled = !isRefreshing,
                    pressedScale = NodePulseMotion.chipPressScale,
                    role = Role.Button,
                    onClickLabel = if (selectedTab == MyServersTab.PROBES) "刷新探针" else "刷新汇率与状态",
                    onClick = onRefresh,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = if (selectedTab == MyServersTab.PROBES) "刷新探针" else "刷新汇率与状态",
                tint = if (isRefreshing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(19.dp)
                    .then(refreshModifier),
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
    onOpenMonitorSettings: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val hasExpiredOrCritical = remember(summary.expiredRenewalCount, summary.dueRenewals) {
        summary.expiredRenewalCount > 0 || summary.dueRenewals.any {
            it.urgency == AssetExpiryUrgency.EXPIRED || it.urgency == AssetExpiryUrgency.CRITICAL
        }
    }
    val onlineValueColor by animateColorAsState(
        targetValue = if (summary.offlineProbeCount > 0) {
            MaterialTheme.colorScheme.error
        } else if (summary.onlineProbeCount > 0) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "heroOnlineColor",
    )
    val renewalValueColor = when {
        hasExpiredOrCritical -> MaterialTheme.colorScheme.error
        summary.dueSoonRenewalCount > 0 || summary.dueRenewals.isNotEmpty() -> ext.onDrumstickContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val monthlyDisplay = remember(summary.totalMonthlyCostCny) {
        "¥${formatHeroCurrencyValue(summary.totalMonthlyCostCny)}"
    }
    val annualizedDisplay = remember(summary.totalAnnualizedCostCny) {
        "¥${formatHeroCurrencyValue(summary.totalAnnualizedCostCny)}"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseHeroCard(
                accentColor = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(20.dp),
                elevation = PulseElevation.Card,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // Compact 4-column telemetry KPI row with thin vertical dividers
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HeroKpiColumn(
                label = "探针在线",
                value = "${summary.onlineProbeCount}/${summary.totalProbeNodeCount}",
                valueColor = onlineValueColor,
                modifier = Modifier.weight(0.88f),
            )
            HeroVerticalDivider()
            HeroKpiColumn(
                label = "月均支出",
                value = monthlyDisplay,
                valueColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1.1f),
            )
            HeroVerticalDivider()
            HeroKpiColumn(
                label = "年化支出",
                value = annualizedDisplay,
                modifier = Modifier.weight(1.12f),
            )
            HeroVerticalDivider()
            HeroKpiColumn(
                label = "近期续费",
                value = "${summary.dueSoonRenewalCount} 台",
                valueColor = renewalValueColor,
                modifier = Modifier.weight(0.84f),
            )
        }

        // Ultra-compact status micro line: ledger count / FX refresh + patrol status dot
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(end = 8.dp)
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "刷新汇率",
                        onClick = onRefreshFx,
                    )
                    .padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.CurrencyExchange,
                    contentDescription = "刷新汇率",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    modifier = Modifier.size(11.dp),
                )
                Text(
                    text = "账本在册 ${summary.totalMachineCount} 台" +
                        (usdRateDisplay?.let { " · $it" }.orEmpty()),
                    style = NodePulseThemeTokens.extraTypography.microLabel.copy(
                        fontWeight = FontWeight.Normal,
                    ).withTabularNumbers(),
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "后台巡检设置",
                        onClick = onOpenMonitorSettings,
                    )
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
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
                    style = NodePulseThemeTokens.extraTypography.microLabel.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

private fun formatHeroCurrencyValue(amount: java.math.BigDecimal): String {
    val stripped = amount.stripTrailingZeros()
    return when {
        stripped.compareTo(java.math.BigDecimal.ZERO) == 0 -> "0"
        stripped.scale() <= 0 -> stripped.toPlainString()
        stripped.scale() == 1 -> stripped.toPlainString()
        else -> amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
    }
}

@Composable
private fun HeroVerticalDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(26.dp)
            .background(NodePulseThemeTokens.extendedColors.cardBorder),
    )
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
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.ExtraBold,
            ).withTabularNumbers(),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = NodePulseThemeTokens.extraTypography.microLabel.copy(
                fontWeight = FontWeight.Normal,
            ),
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
    onOpenIdleSalePost: (String) -> Unit,
    onOpenLinkedAsset: ((VpsAssetRecord) -> Unit)? = null,
    previewViewMode: ProbeViewMode? = null,
) {
    val panelsById = remember(uiState.panels) { uiState.panels.associateBy { it.id } }
    val panelScopedSnapshots = remember(uiState.snapshots, uiState.selectedPanelIdFilter) {
        val panelFilter = uiState.selectedPanelIdFilter
        if (panelFilter == null) {
            uiState.snapshots
        } else {
            uiState.snapshots.filter { it.panelId == panelFilter }
        }
    }
    val assetsByProbeKey = remember(uiState.assets) {
        uiState.assets.mapNotNull { asset ->
            asset.linkedProbeKey?.let { key -> key to asset }
        }.toMap()
    }
    val (viewMode, onSelectViewMode) = rememberPersistedProbeViewMode(overrideMode = previewViewMode)

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Single-row Filter Bar: All/Online/Offline + Panel Dropdown (with 管理面板) + Cards/List toggle
            item(key = "probe_filter_bar", contentType = "filter_bar") {
                ProbeFilterAndViewBar(
                    panelScopedSnapshots = panelScopedSnapshots,
                    allSnapshots = uiState.snapshots,
                    panels = uiState.panels,
                    selectedFilter = uiState.probeFilter,
                    selectedPanelIdFilter = uiState.selectedPanelIdFilter,
                    viewMode = viewMode,
                    onSelectFilter = onSelectFilter,
                    onSelectPanelFilter = onSelectPanelFilter,
                    onOpenPanelManager = onOpenPanelManager,
                    onSelectViewMode = onSelectViewMode,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }

            // Panel Error Banner
            if (uiState.panelErrors.isNotEmpty()) {
                item(key = "panel_errors", contentType = "error_banner") {
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
                item(key = "probes_empty_panels", contentType = "empty_state") {
                    FleetEmptyStateCard(
                        icon = Icons.Rounded.Dns,
                        title = "尚未接入探针面板",
                        description = "支持同时接入多个哪吒 v0、哪吒 v1 与 Komari 面板，实时查看 CPU、内存、硬盘、月流量与离线高优告警。",
                        actionLabel = "添加第一个探针面板",
                        onAction = onOpenPanelManager,
                    )
                }
            } else if (uiState.filteredSnapshots.isEmpty()) {
                item(key = "probes_empty_filtered", contentType = "empty_state") {
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
            } else if (viewMode == ProbeViewMode.DENSE_LIST) {
                item(key = "probe_dense_list", contentType = "probe_dense_list") {
                    ProbeDenseListCard(
                        snapshots = uiState.filteredSnapshots,
                        panelsById = panelsById,
                        assetsByProbeKey = assetsByProbeKey,
                        trafficForecastsByProbeKey = uiState.trafficForecastsByProbeKey,
                        idleAssessmentsByProbeKey = uiState.idleAssessmentsByProbeKey,
                        referenceNowEpochMillis = uiState.lastSyncEpochMillis,
                        onAddAssetFromProbe = onAddAssetFromProbe,
                        onOpenIdleSalePost = onOpenIdleSalePost,
                        onOpenLinkedAsset = onOpenLinkedAsset,
                    )
                }
            } else {
                items(
                    items = uiState.filteredSnapshots,
                    key = { snap -> snap.probeKey },
                    contentType = { "probe_card" },
                ) { snapshot ->
                    val panel = panelsById[snapshot.panelId]
                    val linkedAsset = assetsByProbeKey[snapshot.probeKey]
                    val trafficForecast = uiState.trafficForecastsByProbeKey[snapshot.probeKey]
                    val idleAssessment = uiState.idleAssessmentsByProbeKey[snapshot.probeKey]
                    ProbeServerCard(
                        snapshot = snapshot,
                        panel = panel,
                        linkedAsset = linkedAsset,
                        trafficForecast = trafficForecast,
                        idleAssessment = idleAssessment,
                        referenceNowEpochMillis = uiState.lastSyncEpochMillis,
                        onAddAssetFromProbe = onAddAssetFromProbe,
                        onOpenIdleSalePost = onOpenIdleSalePost,
                        onOpenLinkedAsset = onOpenLinkedAsset,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun FleetActionPill(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .border(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.34f else 0.20f),
                shape = CircleShape,
            )
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun ProbeServerCard(
    snapshot: ProbeServerSnapshot,
    panel: ProbePanelConfig?,
    linkedAsset: VpsAssetRecord?,
    trafficForecast: ServerTrafficForecast?,
    idleAssessment: ServerIdleAssessment?,
    referenceNowEpochMillis: Long,
    onAddAssetFromProbe: (ProbeServerSnapshot) -> Unit,
    onOpenIdleSalePost: (String) -> Unit,
    onOpenLinkedAsset: ((VpsAssetRecord) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    CompactProbeServerCard(
        snapshot = snapshot,
        panel = panel,
        linkedAsset = linkedAsset,
        trafficForecast = trafficForecast,
        idleAssessment = idleAssessment,
        referenceNowEpochMillis = referenceNowEpochMillis,
        onAddAssetFromProbe = onAddAssetFromProbe,
        onOpenIdleSalePost = onOpenIdleSalePost,
        onOpenLinkedAsset = onOpenLinkedAsset,
        modifier = modifier,
    )
}

@Composable
private fun SmoothMetricProgressBar(
    progress: Float,
    barColor: Color,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    height: Dp = 5.dp,
) {
    val animatedProgress = animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "fleetMetricProgress",
    )
    val animatedColor = animateColorAsState(
        targetValue = barColor,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "fleetMetricColor",
    )

    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                val radius = CornerRadius(size.height / 2f, size.height / 2f)
                drawRoundRect(
                    color = trackColor,
                    size = size,
                    cornerRadius = radius,
                )
                val fraction = animatedProgress.value.coerceIn(0f, 1f)
                if (fraction > 0.001f) {
                    val fillWidth = (size.width * fraction).coerceAtLeast(size.height)
                    drawRoundRect(
                        color = animatedColor.value,
                        size = Size(fillWidth.coerceAtMost(size.width), size.height),
                        cornerRadius = radius,
                    )
                }
            },
    )
}

@Composable
private fun LivePulseDot(
    color: Color,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!animate) {
        Box(
            modifier = modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "livePollPulse")
    val pulseAlpha = transition.animateFloat(
        initialValue = 0.40f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "livePollAlpha",
    )
    Spacer(
        modifier = modifier
            .size(6.dp)
            .drawBehind {
                drawCircle(color = color.copy(alpha = pulseAlpha.value))
            },
    )
}

// ── Tab 2: Assets Ledger Tab ──────────────────────────────────────────────────

@Composable
private fun AssetsTabContent(
    uiState: MyServersUiState,
    onAddAsset: () -> Unit,
    onEditAsset: (VpsAssetRecord) -> Unit,
    onRequestRenewAsset: (VpsAssetRecord, UpcomingRenewalItem?) -> Unit,
    onRequestDeleteAsset: (VpsAssetRecord) -> Unit,
    onBindProbe: (VpsAssetRecord) -> Unit,
    onOpenSalePost: (VpsAssetRecord) -> Unit,
    onOpenIdleSalePost: (String) -> Unit,
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
                .thenBy { it.nickname.lowercase() }
                .thenBy { it.id },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 84.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "assets_renewal_timeline", contentType = "assets_timeline") {
                AssetRenewalTimelineStrip(
                    totalAssetCount = uiState.assets.size,
                    upcomingRenewals = uiState.portfolioSummary.upcomingRenewals,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }

            if (sortedAssets.isEmpty()) {
                item(key = "assets_empty", contentType = "empty_state") {
                    FleetEmptyStateCard(
                        icon = Icons.Rounded.Storage,
                        title = "资产账本还是空的",
                        description = "记录每台 VPS 的配置、线路、续费币种与到期日，自动折算月均/年化人民币开销，并支持「不想续了 · 一键估值出机」。",
                        actionLabel = "录入第一台服务器",
                        onAction = onAddAsset,
                    )
                }
            } else {
                items(
                    items = sortedAssets,
                    key = { asset -> asset.id },
                    contentType = { "asset_card" },
                ) { asset ->
                    val renewal = renewalsByAssetId[asset.id]
                    val linkedProbe = asset.linkedProbeKey?.let { probesByKey[it] }
                    val trafficForecast = asset.linkedProbeKey?.let { uiState.trafficForecastsByProbeKey[it] }
                    val idleAssessment = asset.linkedProbeKey?.let { uiState.idleAssessmentsByProbeKey[it] }
                    VpsAssetCard(
                        asset = asset,
                        renewalItem = renewal,
                        linkedProbe = linkedProbe,
                        trafficForecast = trafficForecast,
                        idleAssessment = idleAssessment,
                        onEdit = { onEditAsset(asset) },
                        onMarkRenewed = { onRequestRenewAsset(asset, renewal) },
                        onDelete = { onRequestDeleteAsset(asset) },
                        onBindProbe = { onBindProbe(asset) },
                        onOpenSalePost = { onOpenSalePost(asset) },
                        onOpenUrl = onOpenUrl,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }

        AddAssetFloatingPill(
            onClick = onAddAsset,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 14.dp),
        )
    }
}

@Composable
private fun VpsAssetCard(
    asset: VpsAssetRecord,
    renewalItem: UpcomingRenewalItem?,
    linkedProbe: ProbeServerSnapshot?,
    trafficForecast: ServerTrafficForecast?,
    idleAssessment: ServerIdleAssessment?,
    onEdit: () -> Unit,
    onMarkRenewed: () -> Unit,
    onDelete: () -> Unit,
    onBindProbe: () -> Unit,
    onOpenSalePost: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AssetTicketCard(
        asset = asset,
        renewalItem = renewalItem,
        linkedProbe = linkedProbe,
        trafficForecast = trafficForecast,
        idleAssessment = idleAssessment,
        onEdit = onEdit,
        onMarkRenewed = onMarkRenewed,
        onDelete = onDelete,
        onBindProbe = onBindProbe,
        onOpenSalePost = onOpenSalePost,
        onOpenUrl = onOpenUrl,
        modifier = modifier,
    )
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

@Composable
private fun TrafficThresholdWarningBanner(
    forecasts: List<ServerTrafficForecast>,
    snapshotsByKey: Map<String, ProbeServerSnapshot>,
    assetsByProbeKey: Map<String, VpsAssetRecord>,
) {
    if (forecasts.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PulseArchitecturalShapes.EditorialCard)
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Rounded.NotificationsActive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "流量 80% 预警 (${forecasts.size} 台)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        forecasts.take(3).forEach { fc ->
            val name = assetsByProbeKey[fc.probeKey]?.nickname?.takeIf { it.isNotBlank() }
                ?: snapshotsByKey[fc.probeKey]?.name
                ?: fc.probeKey
            val pct = ((fc.usageRatio ?: 0.8) * 100.0).toInt()
            val forecastPart = fc.exhaustionForecastText?.let { " · $it" }.orEmpty()
            Text(
                text = "• $name：已用 ${pct}%$forecastPart（每月 ${fc.anchorResetDay} 号重置）",
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TrafficForecastCaptionRow(
    forecast: ServerTrafficForecast,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val resetLabel = "重置日：每月 ${forecast.anchorResetDay} 号"
    val (rightText, rightColor, rightBold) = when {
        forecast.isExhausted -> Triple("本周期流量已用完", MaterialTheme.colorScheme.error, true)
        !forecast.exhaustionForecastText.isNullOrBlank() -> Triple(
            forecast.exhaustionForecastText,
            if (forecast.isAtOrAboveAlertThreshold) MaterialTheme.colorScheme.error else ext.onDrumstickContainer,
            true,
        )
        forecast.isAtOrAboveAlertThreshold -> {
            val pct = ((forecast.usageRatio ?: 0.8) * 100.0).toInt()
            Triple("已达 80% 流量预警 (${pct}%)", ext.onDrumstickContainer, true)
        }
        else -> Triple(
            "下次重置 ${forecast.nextResetDate.monthValue}月${forecast.nextResetDate.dayOfMonth}日",
            MaterialTheme.colorScheme.onSurfaceVariant,
            false,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = resetLabel,
            style = NodePulseThemeTokens.extraTypography.microLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = rightText,
            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
            fontWeight = if (rightBold) FontWeight.SemiBold else FontWeight.Normal,
            color = rightColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(start = 8.dp),
        )
    }
}

@Composable
private fun IdleServerCalloutStrip(
    assessment: ServerIdleAssessment,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ext.drumstickContainer.copy(alpha = 0.72f))
            .border(0.75.dp, ext.onDrumstickContainer.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = assessment.recommendationText ?: "30天 CPU 与流量占用较低，建议出掉回血",
            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
            fontWeight = FontWeight.Medium,
            color = ext.onDrumstickContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        )
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .pulseBouncyClickable(
                    pressedScale = NodePulseMotion.chipPressScale,
                    role = Role.Button,
                    onClick = onAction,
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Rounded.LocalOffer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = actionLabel,
                style = NodePulseThemeTokens.extraTypography.microLabel,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun IdleFleetSummaryBanner(
    idleServers: List<ServerIdleAssessment>,
    onOpenIdleSalePost: (String) -> Unit,
) {
    if (idleServers.isEmpty()) return
    val ext = NodePulseThemeTokens.extendedColors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = ext.drumstickContainer.copy(alpha = 0.55f),
                borderColor = ext.onDrumstickContainer.copy(alpha = 0.28f),
                elevation = PulseElevation.Subtle,
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                PulseAccentPill(
                    text = "闲置识别",
                    containerColor = ext.drumstickContainer,
                    contentColor = ext.onDrumstickContainer,
                )
                Text(
                    text = "发现 ${idleServers.size} 台吃灰机器 · 建议出掉回血",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        idleServers.take(3).forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = item.serverName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.recommendationText ?: "30天 CPU 与流量占用较低，建议出掉",
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .pulseBouncyClickable(
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.Button,
                            onClick = { onOpenIdleSalePost(item.probeKey) },
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Rounded.LocalOffer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = if (item.linkedAsset != null) "生成出售帖" else "录入并出机",
                        style = NodePulseThemeTokens.extraTypography.microLabel,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

private fun formatBytesCompact(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    if (safe < 1024L) return "${safe}B"
    val kb = safe / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.0fK", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) {
        return if (mb >= 100.0) String.format(Locale.US, "%.0fM", mb)
        else String.format(Locale.US, "%.1fM", mb)
    }
    val gb = mb / 1024.0
    if (gb < 1024.0) {
        return if (gb >= 100.0) String.format(Locale.US, "%.0fG", gb)
        else String.format(Locale.US, "%.1fG", gb)
    }
    val tb = gb / 1024.0
    return String.format(Locale.US, "%.2fT", tb)
}
