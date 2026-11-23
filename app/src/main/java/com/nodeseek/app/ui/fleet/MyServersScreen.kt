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
                        onRefresh = resolvedViewModel::refreshAllProbes,
                        onSelectFilter = resolvedViewModel::selectProbeFilter,
                        onSelectPanelFilter = resolvedViewModel::selectPanelFilter,
                        onOpenPanelManager = resolvedViewModel::openPanelManager,
                        onAddAssetFromProbe = resolvedViewModel::openAddAssetFromProbe,
                        onOpenIdleSalePost = resolvedViewModel::openIdleSalePost,
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
    onRefresh: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseGlassSurface(showBottomBorder = true)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
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

        // Single dedicated Refresh action in top-right (no duplicate panel/add buttons).
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
                .size(38.dp)
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
                    .size(20.dp)
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseHeroCard(
                accentColor = MaterialTheme.colorScheme.primary,
                shape = PulseArchitecturalShapes.HeroBento,
                elevation = PulseElevation.Card,
            )
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Expressive 4-column telemetry KPI strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HeroKpiColumn(
                label = "探针在线",
                value = "${summary.onlineProbeCount}/${summary.totalProbeNodeCount} 台",
                valueColor = onlineValueColor,
                modifier = Modifier.weight(0.95f),
            )
            HeroVerticalDivider()
            HeroKpiColumn(
                label = "月均支出",
                value = "¥${summary.totalMonthlyCostCny.toPlainString()}",
                valueColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1.1f),
            )
            HeroVerticalDivider()
            HeroKpiColumn(
                label = "年化支出",
                value = "¥${summary.totalAnnualizedCostCny.toPlainString()}",
                modifier = Modifier.weight(1.15f),
            )
            HeroVerticalDivider()
            HeroKpiColumn(
                label = "近期续费",
                value = "${summary.dueSoonRenewalCount} 台",
                valueColor = renewalValueColor,
                modifier = Modifier.weight(0.82f),
            )
        }

        PulseGradientDivider(color = ext.cardBorder.copy(alpha = 0.55f))

        // Compact Footer Strip: FX tap-to-refresh + Background Patrol Settings entry
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
                    .clickable(onClick = onRefreshFx)
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.CurrencyExchange,
                    contentDescription = "刷新汇率",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "账本 ${summary.totalMachineCount} 台" +
                        (usdRateDisplay?.let { " · $it" }.orEmpty()),
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.90f))
                    .border(0.75.dp, ext.cardBorder, CircleShape)
                    .pulseBouncyClickable(
                        pressedScale = NodePulseMotion.chipPressScale,
                        role = Role.Button,
                        onClickLabel = "后台巡检设置",
                        onClick = onOpenMonitorSettings,
                    )
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
private fun HeroVerticalDivider() {
    Box(
        modifier = Modifier
            .width(0.75.dp)
            .height(26.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
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
    onOpenIdleSalePost: (String) -> Unit,
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
    val snapshotsByKey = remember(uiState.snapshots) {
        uiState.snapshots.associateBy { it.probeKey }
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Online / Offline & Panel Filter Bar
            item(key = "probe_filter_bar", contentType = "filter_bar") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ProbeOnlineFilter.entries.forEach { filter ->
                                val count = when (filter) {
                                    ProbeOnlineFilter.ALL -> panelScopedSnapshots.size
                                    ProbeOnlineFilter.ONLINE -> panelScopedSnapshots.count { it.isOnline }
                                    ProbeOnlineFilter.OFFLINE -> panelScopedSnapshots.count { !it.isOnline }
                                }
                                FleetChoiceChip(
                                    label = "${filter.label} $count",
                                    selected = uiState.probeFilter == filter,
                                    onClick = { onSelectFilter(filter) },
                                )
                            }
                        }
                        // Single clear entry point for managing probe panels.
                        FleetActionPill(
                            label = "管理面板",
                            icon = Icons.Rounded.Tune,
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

            if (uiState.highTrafficForecasts.isNotEmpty()) {
                item(key = "probes_traffic_warning_banner", contentType = "traffic_banner") {
                    TrafficThresholdWarningBanner(
                        forecasts = uiState.highTrafficForecasts,
                        snapshotsByKey = snapshotsByKey,
                        assetsByProbeKey = assetsByProbeKey,
                    )
                }
            }

            if (uiState.idleServers.isNotEmpty()) {
                item(key = "probes_idle_banner", contentType = "idle_banner") {
                    IdleFleetSummaryBanner(
                        idleServers = uiState.idleServers,
                        onOpenIdleSalePost = onOpenIdleSalePost,
                    )
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
                        onAddAssetFromProbe = onAddAssetFromProbe,
                        onOpenIdleSalePost = onOpenIdleSalePost,
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
    onAddAssetFromProbe: (ProbeServerSnapshot) -> Unit,
    onOpenIdleSalePost: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val statusDotColor by animateColorAsState(
        targetValue = if (snapshot.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "probeDotColor",
    )
    val statusBg by animateColorAsState(
        targetValue = if (snapshot.isOnline) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "probeStatusBg",
    )
    val statusFg by animateColorAsState(
        targetValue = if (snapshot.isOnline) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        },
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "probeStatusFg",
    )
    val cardBorderColor by animateColorAsState(
        targetValue = if (snapshot.isOnline) {
            ext.cardBorder
        } else {
            MaterialTheme.colorScheme.error.copy(alpha = if (isDark) 0.50f else 0.42f)
        },
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "probeCardBorder",
    )
    val cardAccentColor = if (snapshot.isOnline) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }

    val effectiveTrafficLimit = trafficForecast?.limitBytes
        ?: FleetTrafficAndIdleAnalyzer.resolveEffectiveTrafficLimitBytes(snapshot, linkedAsset)
    val trafficRatio = effectiveTrafficLimit?.takeIf { it > 0L }?.let { limit ->
        (snapshot.totalMonthlyTransferBytes.toDouble() / limit.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    val targetCpu = if (snapshot.isOnline) snapshot.cpuPercent.coerceIn(0.0, 100.0).toFloat() else 0f
    val targetMemRatio = if (snapshot.isOnline) snapshot.memUsageRatio.coerceIn(0f, 1f) else 0f
    val targetDiskRatio = if (snapshot.isOnline) snapshot.diskUsageRatio.coerceIn(0f, 1f) else 0f

    val animatedCpu by animateFloatAsState(
        targetValue = targetCpu,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "probeCpuNumber",
    )
    val animatedMemPct by animateFloatAsState(
        targetValue = targetMemRatio * 100f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "probeMemNumber",
    )
    val animatedDiskPct by animateFloatAsState(
        targetValue = targetDiskRatio * 100f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "probeDiskNumber",
    )

    val hasDiskTelemetry = snapshot.diskTotalBytes > 0L || snapshot.diskUsedBytes > 0L

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.ElevatedTile,
                containerColor = ext.cardSurface,
                borderColor = cardBorderColor,
                accentColor = cardAccentColor,
                elevation = PulseElevation.Card,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header: Expressive Region Badge + Server Identity + Status Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val codeBadge = snapshot.countryCode.trim().uppercase(Locale.US).ifBlank { "VPS" }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(statusBg.copy(alpha = if (isDark) 0.72f else 0.85f))
                    .border(
                        width = 0.8.dp,
                        color = statusDotColor.copy(alpha = if (isDark) 0.42f else 0.24f),
                        shape = RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = codeBadge,
                    style = NodePulseThemeTokens.extraTypography.codeLabel.copy(
                        fontWeight = FontWeight.ExtraBold,
                    ),
                    color = statusFg,
                    maxLines = 1,
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(statusDotColor),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = snapshot.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = remember(panel?.name, snapshot.os, snapshot.uptimeSeconds, snapshot.isOnline) {
                    buildList {
                        panel?.name?.takeIf { it.isNotBlank() }?.let { add(it) }
                        snapshot.os.takeIf { it.isNotBlank() }?.let { add(it) }
                        if (snapshot.isOnline && snapshot.uptimeSeconds > 0L) {
                            add("运行 ${formatUptimeChinese(snapshot.uptimeSeconds)}")
                        } else if (!snapshot.isOnline) {
                            add("节点未响应")
                        }
                    }.joinToString(" · ").ifBlank { "探针节点" }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (idleAssessment?.isIdle == true) {
                    PulseAccentPill(
                        text = "闲置",
                        containerColor = ext.drumstickContainer,
                        contentColor = ext.onDrumstickContainer,
                    )
                }
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(statusBg)
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusDotColor),
                    )
                    Text(
                        text = if (snapshot.isOnline) "在线" else "离线",
                        style = NodePulseThemeTokens.extraTypography.microLabel,
                        color = statusFg,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        // Fixed-geometry 2x2 Expressive Telemetry Bento (zero relayout on 2s updates)
        val cpuDetail = buildList {
            if (snapshot.cpuCores > 0) add("${snapshot.cpuCores}核")
            snapshot.load1?.let { add(String.format(Locale.US, "Load %.2f", it)) }
        }.joinToString(" · ").ifBlank { "实时负载" }

        val memDetail = if (snapshot.memTotalBytes > 0L) {
            "${formatBytesCompact(snapshot.memUsedBytes)} / ${formatBytesCompact(snapshot.memTotalBytes)}"
        } else {
            formatBytesCompact(snapshot.memUsedBytes)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProbeMetricBentoTile(
                label = "CPU",
                valueText = String.format(Locale.US, "%.1f%%", animatedCpu),
                caption = cpuDetail,
                progress = (targetCpu / 100f).coerceIn(0f, 1f),
                isDimmed = !snapshot.isOnline,
                modifier = Modifier.weight(1f),
            )
            ProbeMetricBentoTile(
                label = "内存",
                valueText = "${animatedMemPct.roundToInt()}%",
                caption = memDetail,
                progress = targetMemRatio,
                isDimmed = !snapshot.isOnline,
                modifier = Modifier.weight(1f),
            )
        }

        if (hasDiskTelemetry) {
            val diskDetail = if (snapshot.diskTotalBytes > 0L) {
                "${formatBytesCompact(snapshot.diskUsedBytes)} / ${formatBytesCompact(snapshot.diskTotalBytes)}"
            } else {
                formatBytesCompact(snapshot.diskUsedBytes)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProbeMetricBentoTile(
                    label = "硬盘",
                    valueText = "${animatedDiskPct.roundToInt()}%",
                    caption = diskDetail,
                    progress = targetDiskRatio,
                    isDimmed = !snapshot.isOnline,
                    modifier = Modifier.weight(1f),
                )
                ProbeLiveBandwidthTile(
                    netInSpeedBps = if (snapshot.isOnline) snapshot.netInSpeedBps else 0L,
                    netOutSpeedBps = if (snapshot.isOnline) snapshot.netOutSpeedBps else 0L,
                    isDimmed = !snapshot.isOnline,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            ProbeLiveBandwidthWideStrip(
                netInSpeedBps = if (snapshot.isOnline) snapshot.netInSpeedBps else 0L,
                netOutSpeedBps = if (snapshot.isOnline) snapshot.netOutSpeedBps else 0L,
                isDimmed = !snapshot.isOnline,
            )
        }

        // Monthly Traffic Quota Strip (when reported or configured via linked asset)
        if (snapshot.totalMonthlyTransferBytes > 0L || effectiveTrafficLimit != null) {
            ProbeMonthlyTrafficPanel(
                monthlyInBytes = snapshot.monthlyTransferInBytes,
                monthlyOutBytes = snapshot.monthlyTransferOutBytes,
                effectiveLimitBytes = effectiveTrafficLimit,
                trafficRatio = trafficRatio,
                trafficForecast = trafficForecast,
            )
        }

        if (idleAssessment?.isIdle == true) {
            IdleServerCalloutStrip(
                assessment = idleAssessment,
                actionLabel = if (linkedAsset != null) "出掉回血" else "录入并出机",
                onAction = { onOpenIdleSalePost(snapshot.probeKey) },
            )
        }

        PulseGradientDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f))

        // Clean Footer: Linked Asset Pill or Single "录入账本" CTA
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (linkedAsset != null) {
                PulseAccentPill(
                    text = "账本：${linkedAsset.nickname}",
                    icon = Icons.Rounded.Link,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
                if (linkedAsset.expiryDateIso.isNotBlank()) {
                    Text(
                        text = "到期 ${linkedAsset.expiryDateIso}",
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            } else {
                Text(
                    text = "未关联资产账本",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                )
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f))
                        .pulseBouncyClickable(
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.Button,
                            onClick = { onAddAssetFromProbe(snapshot) },
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = "录入账本",
                        style = NodePulseThemeTokens.extraTypography.microLabel,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProbeMetricBentoTile(
    label: String,
    valueText: String,
    caption: String,
    progress: Float,
    isDimmed: Boolean,
    modifier: Modifier = Modifier,
    warningThreshold: Float = 0.85f,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val barColor = when {
        isDimmed -> MaterialTheme.colorScheme.outlineVariant
        progress >= 0.92f -> MaterialTheme.colorScheme.error
        progress >= warningThreshold -> ext.onDrumstickContainer
        else -> MaterialTheme.colorScheme.primary
    }
    val valueColor = when {
        isDimmed -> MaterialTheme.colorScheme.onSurfaceVariant
        progress >= 0.92f -> MaterialTheme.colorScheme.error
        progress >= warningThreshold -> ext.onDrumstickContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = modifier
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(0.75.dp, ext.cardBorder.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                ).withTabularNumbers(),
                color = valueColor,
                maxLines = 1,
                softWrap = false,
            )
        }
        SmoothMetricProgressBar(
            progress = progress,
            barColor = barColor,
            height = 5.dp,
        )
        Text(
            text = caption,
            style = NodePulseThemeTokens.extraTypography.microLabel.withTabularNumbers(),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProbeLiveBandwidthTile(
    netInSpeedBps: Long,
    netOutSpeedBps: Long,
    isDimmed: Boolean,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val primaryTint = if (isDimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    val secondaryTint = if (isDimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary
    val textColor = if (isDimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface

    Column(
        modifier = modifier
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(0.75.dp, ext.cardBorder.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "实时带宽",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            Icon(
                imageVector = Icons.Rounded.SwapVert,
                contentDescription = null,
                tint = primaryTint.copy(alpha = 0.78f),
                modifier = Modifier.size(14.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Rounded.ArrowDownward,
                contentDescription = "下行",
                tint = primaryTint,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = formatSpeedBps(netInSpeedBps),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold).withTabularNumbers(),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Rounded.ArrowUpward,
                contentDescription = "上行",
                tint = secondaryTint,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = formatSpeedBps(netOutSpeedBps),
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProbeLiveBandwidthWideStrip(
    netInSpeedBps: Long,
    netOutSpeedBps: Long,
    isDimmed: Boolean,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val primaryTint = if (isDimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    val secondaryTint = if (isDimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary
    val textColor = if (isDimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(0.75.dp, ext.cardBorder.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                Icons.Rounded.ArrowDownward,
                contentDescription = "下行",
                tint = primaryTint,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "下行",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatSpeedBps(netInSpeedBps),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold).withTabularNumbers(),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .width(0.75.dp)
                .height(16.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        )
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                Icons.Rounded.ArrowUpward,
                contentDescription = "上行",
                tint = secondaryTint,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "上行",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatSpeedBps(netOutSpeedBps),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold).withTabularNumbers(),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProbeMonthlyTrafficPanel(
    monthlyInBytes: Long,
    monthlyOutBytes: Long,
    effectiveLimitBytes: Long?,
    trafficRatio: Float?,
    trafficForecast: ServerTrafficForecast?,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val barColor = when {
        trafficRatio == null -> MaterialTheme.colorScheme.primary
        trafficRatio >= 0.92f -> MaterialTheme.colorScheme.error
        trafficRatio >= 0.80f -> ext.onDrumstickContainer
        else -> MaterialTheme.colorScheme.primary
    }
    val pctValue = trafficForecast?.usageRatio?.times(100.0)
        ?: trafficRatio?.times(100f)?.toDouble()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(0.75.dp, ext.cardBorder.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "月流量",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            val detail = buildString {
                append("↓ ${formatBytesBinary(monthlyInBytes)}  ↑ ${formatBytesBinary(monthlyOutBytes)}")
                if (effectiveLimitBytes != null) {
                    append(" / ${formatBytesCompact(effectiveLimitBytes)}")
                }
                if (pctValue != null) {
                    append(" (${pctValue.roundToInt()}%)")
                }
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(start = 8.dp),
            )
        }
        if (trafficRatio != null) {
            SmoothMetricProgressBar(
                progress = trafficRatio,
                barColor = barColor,
                height = 5.dp,
            )
        }
        if (trafficForecast != null && trafficForecast.limitBytes != null) {
            TrafficForecastCaptionRow(forecast = trafficForecast)
        }
    }
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "assets_header_bar", contentType = "assets_header") {
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

        if (uiState.idleServers.isNotEmpty()) {
            item(key = "assets_idle_banner", contentType = "idle_banner") {
                IdleFleetSummaryBanner(
                    idleServers = uiState.idleServers,
                    onOpenIdleSalePost = onOpenIdleSalePost,
                )
            }
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
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val urgency = renewalItem?.urgency

    val isRenewalDue = renewalItem?.isRenewalDue == true

    val displayTags = remember(asset.tags, asset.routeTag) { asset.resolvedTags }

    val badgeText = when (urgency) {
        AssetExpiryUrgency.EXPIRED -> {
            val overdue = (-renewalItem.daysRemaining).coerceAtLeast(1L)
            "已过期 ${overdue}天"
        }
        AssetExpiryUrgency.CRITICAL -> {
            when {
                renewalItem.daysRemaining == 0L -> "今日到期"
                isRenewalDue -> "剩 ${renewalItem.daysRemaining} 天 · 待续费"
                else -> "剩 ${renewalItem.daysRemaining} 天"
            }
        }
        AssetExpiryUrgency.WARNING,
        AssetExpiryUrgency.NORMAL,
        -> if (isRenewalDue) {
            "剩 ${renewalItem.daysRemaining} 天 · 待续费"
        } else {
            "剩 ${renewalItem.daysRemaining} 天"
        }
        else -> "日期待补"
    }
    val badgeIcon: ImageVector? = when {
        urgency == AssetExpiryUrgency.EXPIRED -> Icons.Rounded.WarningAmber
        urgency == AssetExpiryUrgency.CRITICAL -> Icons.Rounded.Schedule
        isRenewalDue -> Icons.Rounded.NotificationsActive
        else -> null
    }
    val badgeFg = when {
        urgency == AssetExpiryUrgency.EXPIRED || urgency == AssetExpiryUrgency.CRITICAL ->
            MaterialTheme.colorScheme.onErrorContainer
        isRenewalDue -> ext.onDrumstickContainer
        urgency == AssetExpiryUrgency.NORMAL -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val badgeBg = when {
        urgency == AssetExpiryUrgency.EXPIRED || urgency == AssetExpiryUrgency.CRITICAL ->
            MaterialTheme.colorScheme.errorContainer
        isRenewalDue -> ext.drumstickContainer
        urgency == AssetExpiryUrgency.NORMAL -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val cardAccentColor: Color? = when {
        urgency == AssetExpiryUrgency.EXPIRED || urgency == AssetExpiryUrgency.CRITICAL ->
            MaterialTheme.colorScheme.error
        isRenewalDue -> ext.onDrumstickContainer
        else -> null
    }
    val cardBorderColor = when {
        urgency == AssetExpiryUrgency.EXPIRED ->
            MaterialTheme.colorScheme.error.copy(alpha = if (isDark) 0.55f else 0.45f)
        urgency == AssetExpiryUrgency.CRITICAL ->
            MaterialTheme.colorScheme.error.copy(alpha = if (isDark) 0.42f else 0.34f)
        isRenewalDue ->
            ext.onDrumstickContainer.copy(alpha = if (isDark) 0.42f else 0.32f)
        else -> ext.cardBorder
    }
    val expiryTextColor = when {
        urgency == AssetExpiryUrgency.EXPIRED || urgency == AssetExpiryUrgency.CRITICAL ->
            MaterialTheme.colorScheme.error
        isRenewalDue -> ext.onDrumstickContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.ElevatedTile,
                containerColor = ext.cardSurface,
                borderColor = cardBorderColor,
                accentColor = cardAccentColor,
                elevation = PulseElevation.Card,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header: Nickname + Provider/Region + Expressive Countdown Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = asset.nickname,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val metaText = remember(asset.providerName, asset.region) {
                    buildList {
                        if (asset.providerName.isNotBlank()) add(asset.providerName)
                        if (asset.region.isNotBlank()) add(asset.region)
                    }.joinToString(" · ")
                }
                if (metaText.isNotBlank()) {
                    Text(
                        text = metaText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            PulseAccentPill(
                text = badgeText,
                icon = badgeIcon,
                containerColor = badgeBg,
                contentColor = badgeFg,
            )
        }

        // Multi-Select Tags & Metadata Chips
        val hasMetadataChips = displayTags.isNotEmpty() ||
            asset.autoRenew ||
            asset.trafficResetDay != null ||
            asset.autoRenewNote.isNotBlank() ||
            idleAssessment?.isIdle == true
        if (hasMetadataChips) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                displayTags.forEach { tag ->
                    PulseAccentPill(
                        text = tag,
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.72f else 0.85f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                if (asset.autoRenew) {
                    PulseAccentPill(
                        text = "自动续费",
                        icon = Icons.Rounded.Autorenew,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = if (isDark) 0.78f else 0.90f),
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                if (asset.trafficResetDay != null) {
                    PulseAccentPill(
                        text = "每月 ${asset.trafficResetDay} 号重置",
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (asset.autoRenewNote.isNotBlank()) {
                    PulseAccentPill(
                        text = asset.autoRenewNote,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 180.dp),
                    )
                }
                if (idleAssessment?.isIdle == true) {
                    PulseAccentPill(
                        text = "闲置",
                        containerColor = ext.drumstickContainer,
                        contentColor = ext.onDrumstickContainer,
                    )
                }
            }
        }

        // Expressive Billing & Specs Bento Box
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(0.75.dp, ext.cardBorder.copy(alpha = 0.72f), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val origCost = if (asset.currency == ValuationCurrency.CNY) {
                        "¥${asset.renewalAmount}"
                    } else {
                        "${asset.currency.symbol}${asset.renewalAmount} ${asset.currency.code}"
                    }
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = origCost,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                            ).withTabularNumbers(),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "/ ${asset.cyclePreset.shortLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    if (renewalItem != null) {
                        Text(
                            text = "≈ ¥${renewalItem.renewalCny.toPlainString()} · 月均 ¥${renewalItem.monthlyCostCny.toPlainString()}",
                            style = NodePulseThemeTokens.extraTypography.microLabel.withTabularNumbers(),
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = if (isRenewalDue) "待续费到期日" else "到期日",
                        style = NodePulseThemeTokens.extraTypography.microLabel,
                        fontWeight = FontWeight.Medium,
                        color = if (isRenewalDue) expiryTextColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Text(
                        text = asset.expiryDateIso.ifBlank { "未设置" },
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ).withTabularNumbers(),
                        color = expiryTextColor,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            if (asset.specsSummary.isNotBlank()) {
                PulseGradientDivider(color = ext.cardBorder.copy(alpha = 0.55f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Rounded.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
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
        }

        // Renewal-Due Callout Strip with One-Tap "已续费" Action
        if (isRenewalDue) {
            renewalItem?.let { dueRenewal ->
                AssetRenewalDueStrip(
                    renewalItem = dueRenewal,
                    onMarkRenewed = onMarkRenewed,
                )
            }
        }

        // Linked Probe Telemetry Capsule (live-updated on 2s tick)
        if (linkedProbe != null) {
            val probeDot by animateColorAsState(
                targetValue = if (linkedProbe.isOnline) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                label = "assetProbeDot",
            )
            val animCpu by animateFloatAsState(
                targetValue = if (linkedProbe.isOnline) linkedProbe.cpuPercent.coerceIn(0.0, 100.0).toFloat() else 0f,
                animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                label = "assetProbeCpu",
            )
            val animRam by animateFloatAsState(
                targetValue = if (linkedProbe.isOnline) linkedProbe.memUsageRatio.coerceIn(0f, 1f) * 100f else 0f,
                animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                label = "assetProbeRam",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(0.75.dp, ext.cardBorder, RoundedCornerShape(12.dp))
                    .clickable(onClick = onBindProbe)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
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
                            .size(7.dp)
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
                    text = if (linkedProbe.isOnline) {
                        String.format(
                            Locale.US,
                            "CPU %.0f%% · RAM %d%%",
                            animCpu,
                            animRam.roundToInt(),
                        )
                    } else {
                        "探针离线"
                    },
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    color = if (linkedProbe.isOnline) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    maxLines = 1,
                    softWrap = false,
                )
            }
            if (trafficForecast != null && trafficForecast.limitBytes != null) {
                TrafficForecastCaptionRow(forecast = trafficForecast)
            }
        }

        if (idleAssessment?.isIdle == true) {
            IdleServerCalloutStrip(
                assessment = idleAssessment,
                actionLabel = "出掉回血",
                onAction = onOpenSalePost,
            )
        }

        PulseGradientDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f))

        // Action Bar: Edit / Bind / Renew (early) / Links / Delete + Prominent "不想续了 · 一键出机"
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
                if (!isRenewalDue) {
                    IconButton(onClick = onMarkRenewed, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Rounded.EventRepeat,
                            contentDescription = "标记已续费",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
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
                    text = if (idleAssessment?.isIdle == true) "出掉回血 · 生成出售帖" else "不想续了 · 一键出机",
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
private fun AssetRenewalDueStrip(
    renewalItem: UpcomingRenewalItem,
    onMarkRenewed: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isUrgentError = renewalItem.urgency == AssetExpiryUrgency.EXPIRED ||
        renewalItem.urgency == AssetExpiryUrgency.CRITICAL
    val containerColor = if (isUrgentError) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
    } else {
        ext.drumstickContainer.copy(alpha = 0.72f)
    }
    val borderColor = if (isUrgentError) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.28f)
    } else {
        ext.onDrumstickContainer.copy(alpha = 0.25f)
    }
    val contentColor = if (isUrgentError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        ext.onDrumstickContainer
    }
    val buttonBg = if (isUrgentError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val buttonFg = if (isUrgentError) {
        MaterialTheme.colorScheme.onError
    } else {
        MaterialTheme.colorScheme.onPrimary
    }

    val titleCopy = when (renewalItem.urgency) {
        AssetExpiryUrgency.EXPIRED -> {
            val overdue = (-renewalItem.daysRemaining).coerceAtLeast(1L)
            if (renewalItem.cyclesToAdvanceOnRenewal > 1) {
                "已逾期 ${overdue} 天（将顺延 ${renewalItem.cyclesToAdvanceOnRenewal} 期）"
            } else {
                "已逾期 ${overdue} 天，确认后顺延到期日"
            }
        }
        AssetExpiryUrgency.CRITICAL -> {
            if (renewalItem.daysRemaining == 0L) "今日到期，续费后自动顺延周期"
            else "剩余 ${renewalItem.daysRemaining} 天到期，建议及时续费"
        }
        else -> "进入续费提醒窗口（剩 ${renewalItem.daysRemaining} 天）"
    }
    val nextExpiryHint = renewalItem.nextExpiryAfterRenewalIso?.let { "确认后新到期日：$it" }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .border(0.75.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
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
                text = titleCopy,
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (nextExpiryHint != null) {
                Text(
                    text = nextExpiryHint,
                    style = NodePulseThemeTokens.extraTypography.microLabel.withTabularNumbers(),
                    fontWeight = FontWeight.Medium,
                    color = contentColor.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(buttonBg)
                .pulseBouncyClickable(
                    pressedScale = NodePulseMotion.chipPressScale,
                    role = Role.Button,
                    onClickLabel = "已续费",
                    onClick = onMarkRenewed,
                )
                .padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = buttonFg,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "已续费",
                style = NodePulseThemeTokens.extraTypography.microLabel,
                fontWeight = FontWeight.Bold,
                color = buttonFg,
                maxLines = 1,
                softWrap = false,
            )
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
