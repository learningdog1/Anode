@file:OptIn(ExperimentalMaterial3Api::class)

package com.nodeseek.app.ui.fleet

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.data.fleet.DomainRdapClient
import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.fleet.FleetWorkScheduler
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.domain.fleet.FleetAssetType
import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.fleet.TlsCertificateInspector
import com.nodeseek.app.domain.fleet.UpcomingRenewalItem
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.ui.components.PulseConfirmDialog
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.pulseHeroCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Standalone "域名 / 证书资产管理" screen accessible directly from FeatureHub,
 * separate from "我的服务器" (Probe Monitor + VPS Asset Ledger).
 */
@Composable
fun DomainAssetsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    fleetRepository: FleetRepository? = null,
    exchangeRateRepository: ExchangeRateRepository? = null,
    fleetWorkScheduler: FleetWorkScheduler? = null,
    domainRdapClient: DomainRdapClient? = null,
    tlsCertificateInspector: TlsCertificateInspector? = null,
    initialHighlightAssetId: String? = null,
    previewUiState: DomainAssetsUiState? = null,
    viewModel: DomainAssetsViewModel? = null,
) {
    val context = LocalContext.current
    val resolvedViewModel: DomainAssetsViewModel = viewModel ?: run {
        val factory = remember(
            context,
            fleetRepository,
            exchangeRateRepository,
            fleetWorkScheduler,
            domainRdapClient,
            tlsCertificateInspector,
        ) {
            DomainAssetsViewModel.factory(
                fleetRepository = fleetRepository ?: FleetRepositoryImpl.forContext(context),
                exchangeRateRepository = exchangeRateRepository ?: ExchangeRateRepository.forContext(context),
                workScheduler = fleetWorkScheduler ?: FleetWorkScheduler(context),
                domainRdapClient = domainRdapClient,
                tlsCertificateInspector = tlsCertificateInspector,
            )
        }
        viewModel(factory = factory)
    }

    val observedUiState by resolvedViewModel.uiState.collectAsStateWithLifecycle()
    val uiState = previewUiState ?: observedUiState
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    val listState = rememberLazyListState()

    var pendingDeleteAsset by remember { mutableStateOf<VpsAssetRecord?>(null) }
    var pendingRenewRequest by remember { mutableStateOf<Pair<VpsAssetRecord, UpcomingRenewalItem?>?>(null) }
    var pendingHighlightAssetId by remember(initialHighlightAssetId) {
        mutableStateOf(initialHighlightAssetId?.trim()?.takeIf { it.isNotEmpty() })
    }

    val existingTags = remember(uiState.domainAssets) {
        VpsAssetRecord.collectDistinctTags(uiState.domainAssets)
    }
    val renewalsByAssetId = remember(uiState.upcomingRenewals) {
        uiState.upcomingRenewals.associateBy { it.asset.id }
    }
    val sortedDomains = remember(uiState.filteredDomains, renewalsByAssetId) {
        uiState.filteredDomains.sortedWith(
            compareBy<VpsAssetRecord> { renewalsByAssetId[it.id]?.daysRemaining ?: Long.MAX_VALUE }
                .thenBy { it.nickname.lowercase() }
                .thenBy { it.id },
        )
    }
    val dueSoonFilterCount = remember(uiState.domainAssets, renewalsByAssetId) {
        uiState.domainAssets.count { asset ->
            val r = renewalsByAssetId[asset.id]
            r != null && (r.isRenewalDue || r.daysRemaining <= 30L)
        }
    }

    LaunchedEffect(resolvedViewModel) {
        resolvedViewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(pendingHighlightAssetId, sortedDomains) {
        val targetId = pendingHighlightAssetId ?: return@LaunchedEffect
        val idx = sortedDomains.indexOfFirst { it.id == targetId }
        if (idx >= 0) {
            listState.animateScrollToItem(idx + 2)
            pendingHighlightAssetId = null
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { PulseSnackbarHost(hostState = snackbarHostState) },
        topBar = {
            DomainAssetsTopBar(
                isRefreshing = uiState.isRefreshingAll,
                onBack = onBack,
                onRefreshAll = resolvedViewModel::refreshAllDomainsAndTls,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                DomainPortfolioHeroCard(
                    domainCount = uiState.domainAssets.size,
                    monthlyCostCny = uiState.domainMonthlyCostCny,
                    annualizedCostCny = uiState.domainAnnualizedCostCny,
                    dueSoonCount = uiState.dueSoonCount,
                    expiredCount = uiState.expiredCount,
                    tlsAlertCount = uiState.tlsAlertCount,
                    tlsHealthyCount = uiState.tlsHealthyCount,
                    usdRateDisplay = uiState.fxRatesState?.rateFor(ValuationCurrency.USD)?.let {
                        "1 USD ≈ ¥${it.setScale(2, RoundingMode.HALF_UP)}"
                    },
                    onRefreshFx = resolvedViewModel::refreshFxRates,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                )

                PullToRefreshBox(
                    isRefreshing = uiState.isRefreshingAll,
                    onRefresh = resolvedViewModel::refreshAllDomainsAndTls,
                    modifier = Modifier.weight(1f),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item(key = "domain_filter_row", contentType = "filter_bar") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FilterChip(
                                    selected = uiState.filter == DomainAssetFilter.ALL,
                                    onClick = { resolvedViewModel.selectFilter(DomainAssetFilter.ALL) },
                                    label = { Text("全部 (${uiState.domainAssets.size})") },
                                )
                                FilterChip(
                                    selected = uiState.filter == DomainAssetFilter.DUE_SOON,
                                    onClick = { resolvedViewModel.selectFilter(DomainAssetFilter.DUE_SOON) },
                                    label = { Text("近期续费 ($dueSoonFilterCount)") },
                                )
                                FilterChip(
                                    selected = uiState.filter == DomainAssetFilter.TLS_ALERT,
                                    onClick = { resolvedViewModel.selectFilter(DomainAssetFilter.TLS_ALERT) },
                                    label = { Text("证书预警 (${uiState.tlsAlertCount})") },
                                )
                            }
                        }

                        item(key = "domain_renewal_timeline", contentType = "timeline") {
                            val visibleRenewals = remember(uiState.upcomingRenewals, sortedDomains) {
                                val visibleIds = sortedDomains.map { it.id }.toSet()
                                uiState.upcomingRenewals.filter { it.asset.id in visibleIds }
                            }
                            AssetRenewalTimelineStrip(
                                totalAssetCount = sortedDomains.size,
                                upcomingRenewals = visibleRenewals,
                                unitLabel = "个",
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }

                        if (sortedDomains.isEmpty()) {
                            item(key = "domains_empty", contentType = "empty_state") {
                                DomainEmptyStateCard(
                                    isFilteredEmpty = uiState.domainAssets.isNotEmpty(),
                                    onAction = {
                                        if (uiState.domainAssets.isNotEmpty()) {
                                            resolvedViewModel.selectFilter(DomainAssetFilter.ALL)
                                        } else {
                                            resolvedViewModel.openAddDomainSheet()
                                        }
                                    },
                                )
                            }
                        } else {
                            items(
                                items = sortedDomains,
                                key = { it.id },
                                contentType = { "domain_card" },
                            ) { asset ->
                                val renewal = renewalsByAssetId[asset.id]
                                AssetTicketCard(
                                    asset = asset,
                                    renewalItem = renewal,
                                    linkedProbe = null,
                                    trafficForecast = null,
                                    idleAssessment = null,
                                    onEdit = { resolvedViewModel.openEditDomainSheet(asset) },
                                    onMarkRenewed = { pendingRenewRequest = asset to renewal },
                                    onDelete = { pendingDeleteAsset = asset },
                                    onBindProbe = {},
                                    onOpenSalePost = {},
                                    onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
                                    onRefreshDomainAndTls = { resolvedViewModel.refreshDomainAndTlsForAsset(asset.id) },
                                    isRefreshingDomainAndTls = asset.id in uiState.refreshingDomainAssetIds,
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
            }

            AddDomainFloatingPill(
                onClick = resolvedViewModel::openAddDomainSheet,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 14.dp),
            )
        }
    }

    uiState.assetEditorDraft?.let { draft ->
        AssetEditorBottomSheet(
            draft = draft,
            availableProbes = emptyList(),
            onSave = resolvedViewModel::saveDomainAsset,
            onDismiss = resolvedViewModel::closeAssetEditor,
            saveError = uiState.assetSaveError,
            onClearSaveError = resolvedViewModel::clearAssetSaveError,
            existingTags = existingTags,
            initialTags = draft.tags,
            initialAutoRenew = draft.autoRenew,
            isLookingUpRdap = uiState.isLookingUpRdap,
            rdapLookupError = uiState.rdapLookupError,
            rdapSyncSummary = uiState.rdapSyncSummary,
            isCheckingTls = uiState.isCheckingTls,
            tlsCheckResultMessage = uiState.tlsCheckResultMessage,
            onLookupDomainRdap = resolvedViewModel::lookupDomainRdapForDraft,
            onCheckTlsCertificate = resolvedViewModel::checkTlsForDraft,
            lockedAssetType = FleetAssetType.DOMAIN_CERT,
        )
    }

    pendingRenewRequest?.let { (asset, renewal) ->
        val nextIso = renewal?.nextExpiryAfterRenewalIso
        val cycles = renewal?.cyclesToAdvanceOnRenewal ?: 1
        val renewMessage = buildString {
            append("确认将域名「${asset.nickname}」标记为已续费？")
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
            title = "确认域名已完成续费",
            message = renewMessage,
            confirmText = "确认已续费",
            tone = PulseDialogTone.PRIMARY,
            icon = Icons.Rounded.EventRepeat,
            onConfirm = {
                resolvedViewModel.markDomainRenewed(asset.id)
                pendingRenewRequest = null
            },
        )
    }

    pendingDeleteAsset?.let { asset ->
        PulseConfirmDialog(
            onDismissRequest = { pendingDeleteAsset = null },
            title = "删除域名资产",
            message = "确定要移除域名「${asset.nickname}」吗？该操作仅删除本地记录。",
            confirmText = "确认删除",
            tone = PulseDialogTone.DESTRUCTIVE,
            icon = Icons.Rounded.DeleteOutline,
            onConfirm = {
                resolvedViewModel.deleteDomainAsset(asset.id)
                pendingDeleteAsset = null
            },
        )
    }
}

@Composable
private fun DomainAssetsTopBar(
    isRefreshing: Boolean,
    onBack: () -> Unit,
    onRefreshAll: () -> Unit,
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
            Text(
                text = "域名 / 证书资产",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "RDAP 到期查询 · HTTPS TLS 证书监测",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val refreshModifier = if (isRefreshing) {
            val spinTransition = rememberInfiniteTransition(label = "domainRefreshSpin")
            val spinAngle = spinTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "domainRefreshAngle",
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
                    onClickLabel = "刷新全部 RDAP 与证书",
                    onClick = onRefreshAll,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = "刷新全部 RDAP 与证书",
                tint = if (isRefreshing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(19.dp)
                    .then(refreshModifier),
            )
        }
    }
}

@Composable
private fun DomainPortfolioHeroCard(
    domainCount: Int,
    monthlyCostCny: BigDecimal,
    annualizedCostCny: BigDecimal,
    dueSoonCount: Int,
    expiredCount: Int,
    tlsAlertCount: Int,
    tlsHealthyCount: Int,
    usdRateDisplay: String?,
    onRefreshFx: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val renewalColor = when {
        expiredCount > 0 -> MaterialTheme.colorScheme.error
        dueSoonCount > 0 -> ext.onDrumstickContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val tlsColor = when {
        tlsAlertCount > 0 -> MaterialTheme.colorScheme.error
        tlsHealthyCount > 0 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseHeroCard(
                accentColor = MaterialTheme.colorScheme.tertiary,
                shape = RoundedCornerShape(20.dp),
                elevation = PulseElevation.Card,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DomainHeroKpiColumn(
                label = "在册域名",
                value = "$domainCount 个",
                valueColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(0.86f),
            )
            DomainHeroVerticalDivider()
            DomainHeroKpiColumn(
                label = "月均开销",
                value = "¥${formatDomainHeroCurrency(monthlyCostCny)}",
                modifier = Modifier.weight(1.06f),
            )
            DomainHeroVerticalDivider()
            DomainHeroKpiColumn(
                label = "年化持仓",
                value = "¥${formatDomainHeroCurrency(annualizedCostCny)}",
                modifier = Modifier.weight(1.1f),
            )
            DomainHeroVerticalDivider()
            DomainHeroKpiColumn(
                label = if (tlsAlertCount > 0) "证书预警" else "近期续费",
                value = if (tlsAlertCount > 0) "$tlsAlertCount 项" else "$dueSoonCount 个",
                valueColor = if (tlsAlertCount > 0) tlsColor else renewalColor,
                modifier = Modifier.weight(0.88f),
            )
        }

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
                    text = "养米年化 ¥${formatDomainHeroCurrency(annualizedCostCny)}" +
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = null,
                    tint = if (tlsAlertCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(11.dp),
                )
                Text(
                    text = when {
                        tlsAlertCount > 0 -> "TLS 异常/临期 $tlsAlertCount"
                        tlsHealthyCount > 0 -> "TLS 正常 $tlsHealthyCount"
                        else -> "支持 RDAP + TLS"
                    },
                    style = NodePulseThemeTokens.extraTypography.microLabel.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    color = if (tlsAlertCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

private fun formatDomainHeroCurrency(amount: BigDecimal): String {
    val stripped = amount.stripTrailingZeros()
    return when {
        stripped.compareTo(BigDecimal.ZERO) == 0 -> "0"
        stripped.scale() <= 1 -> stripped.toPlainString()
        else -> amount.setScale(2, RoundingMode.HALF_UP).toPlainString()
    }
}

@Composable
private fun DomainHeroVerticalDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(26.dp)
            .background(NodePulseThemeTokens.extendedColors.cardBorder),
    )
}

@Composable
private fun DomainHeroKpiColumn(
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

@Composable
private fun AddDomainFloatingPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .shadow(
                elevation = if (isDark) 8.dp else 6.dp,
                shape = shape,
                clip = false,
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.36f else 0.18f),
                shape = shape,
            )
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Button,
                onClickLabel = "录入域名",
                onClick = onClick,
            )
            .height(46.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(19.dp),
        )
        Text(
            text = "录入域名",
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            ),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun DomainEmptyStateCard(
    isFilteredEmpty: Boolean,
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
                .background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            text = if (isFilteredEmpty) "当前筛选条件下无域名" else "暂未录入域名 / 证书",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = if (isFilteredEmpty) {
                "切换为「全部」即可查看所有已录入的域名与证书资产。"
            } else {
                "输入根域名即可通过 RDAP / WHOIS 自动查询注册商与到期日，并一键检测 HTTPS TLS 证书剩余有效期。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Button(onClick = onAction, shape = CircleShape) {
            Text(if (isFilteredEmpty) "查看全部域名" else "录入第一个域名")
        }
    }
}
