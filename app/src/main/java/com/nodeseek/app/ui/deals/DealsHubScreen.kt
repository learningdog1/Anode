package com.nodeseek.app.ui.deals

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FilterAltOff
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.domain.deals.BlackFridayCountdownCalculator
import com.nodeseek.app.domain.deals.BlackFridayCountdownSnapshot
import com.nodeseek.app.domain.deals.DealExpiryCountdown
import com.nodeseek.app.domain.deals.DealFilterState
import com.nodeseek.app.domain.deals.DealPriceCeiling
import com.nodeseek.app.domain.deals.DealRegionPreset
import com.nodeseek.app.domain.deals.DealRoutePreset
import com.nodeseek.app.domain.deals.DealSortOption
import com.nodeseek.app.domain.deals.DealSource
import com.nodeseek.app.domain.deals.DealSourceTab
import com.nodeseek.app.domain.deals.DealsHubSummaryStats
import com.nodeseek.app.domain.deals.DealsRepository
import com.nodeseek.app.domain.deals.PromoDealCardModel
import com.nodeseek.app.ui.components.CategoryMicroTag
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.SortSegmentItem
import com.nodeseek.app.ui.components.SortSegmentedSwitcher
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseShimmerBox
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Flagship Black Friday & Major Promo Hub screen (`黑五 / 大促专题页`).
 *
 * Aggregates structured NodeSeek & overseas (LET/LEB) VPS deals with a live `Asia/Shanghai`
 * Black Friday / Cyber Week countdown hero, multi-criteria filtering, 1-tap coupon copying,
 * direct checkout launch, and 1-tap handoff to Feature 1 (Restock Monitor).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DealsHubScreen(
    dealsRepository: DealsRepository,
    onOpenTopic: (topicId: Long, titleHint: String) -> Unit,
    onAddRestockWatch: (orderUrl: String, title: String, rawPriceText: String, topicId: Long?) -> Unit,
    modifier: Modifier = Modifier,
    initialTabIndex: Int = 0,
    onBack: (() -> Unit)? = null,
    onOpenExternalUrl: ((url: String) -> Unit)? = null,
    overseasTabContent: (@Composable () -> Unit)? = null,
    bottomContentPadding: Dp = 24.dp,
    nowMillisProvider: () -> Long = { System.currentTimeMillis() },
    previewUiState: DealsHubUiState? = null,
    viewModel: DealsHubViewModel = viewModel(
        key = "DealsHubViewModel_$initialTabIndex",
        factory = DealsHubViewModel.factory(
            dealsRepository = dealsRepository,
            initialTabIndex = initialTabIndex,
        ),
    ),
) {
    val observedUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = previewUiState ?: observedUiState
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var nowEpochMillis by remember { mutableLongStateOf(nowMillisProvider()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            nowEpochMillis = nowMillisProvider()
        }
    }

    val countdown = remember(nowEpochMillis) {
        BlackFridayCountdownCalculator.compute(nowEpochMillis)
    }

    val openUrl: (String) -> Unit = remember(context, onOpenExternalUrl) {
        { url ->
            if (onOpenExternalUrl != null) {
                onOpenExternalUrl(url)
            } else {
                openUrlInBrowser(context, url)
            }
        }
    }

    val copyPromoCode: (String) -> Unit = remember(context, haptics, coroutineScope) {
        { code ->
            copyDealTextToClipboard(context, "VPS 优惠码", code)
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            coroutineScope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar("已复制优惠码：$code")
            }
        }
    }

    val fxRateLabel = remember(uiState.fxUsdToCnyRate) {
        "1 USD ≈ ¥${uiState.fxUsdToCnyRate.setScale(2, RoundingMode.HALF_UP).toPlainString()}"
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            PulseSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
            )
        },
        topBar = {
            DealsHubTopBar(
                fxRateLabel = fxRateLabel,
                isRefreshing = uiState.isRefreshing,
                onBack = onBack,
                onRefresh = { viewModel.refresh(force = true) },
            )
        },
    ) { innerPadding ->
        val customOverseasSlot = if (uiState.filterState.sourceTab == DealSourceTab.OVERSEAS &&
            uiState.summaryStats.overseasCount == 0
        ) {
            overseasTabContent
        } else {
            null
        }

        if (customOverseasSlot != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                DealsSourceTabBar(
                    selectedTab = uiState.filterState.sourceTab,
                    stats = uiState.summaryStats,
                    onSelectTab = viewModel::selectSourceTab,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    customOverseasSlot()
                }
            }
        } else {
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { viewModel.refresh(force = true) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = 12.dp,
                        bottom = bottomContentPadding + 20.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 1. Hero Black Friday / Cyber Week Live Countdown Card
                    item(key = "bf_hero_countdown") {
                        BlackFridayHeroCountdownCard(
                            countdown = countdown,
                            stats = uiState.summaryStats,
                            fxUsdToCnyRate = uiState.fxUsdToCnyRate,
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                    }

                    // 2. Source Tabs (全部大促 / 站内优惠 / 海外情报 LET·LEB)
                    item(key = "source_tabs") {
                        DealsSourceTabBar(
                            selectedTab = uiState.filterState.sourceTab,
                            stats = uiState.summaryStats,
                            onSelectTab = viewModel::selectSourceTab,
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                    }

                    // 3. Inline Search + Sort Row
                    item(key = "search_sort_row") {
                        DealsSearchAndSortRow(
                            keyword = uiState.filterState.keyword,
                            sortOption = uiState.filterState.sortOption,
                            onKeywordChange = viewModel::updateKeyword,
                            onSelectSort = viewModel::selectSortOption,
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                    }

                    // 4. Horizontal Quick-Filter Chip Bars
                    item(key = "quick_filter_bars") {
                        DealsQuickFilterBars(
                            filterState = uiState.filterState,
                            onSelectPriceCeiling = viewModel::selectPriceCeiling,
                            onToggleAnnualOnly = viewModel::toggleAnnualOnly,
                            onToggleRequirePromoCode = viewModel::toggleRequirePromoCode,
                            onToggleRequireOrderUrl = viewModel::toggleRequireOrderUrl,
                            onToggleHideOutOfStock = viewModel::toggleHideOutOfStock,
                            onToggleRegionPreset = viewModel::toggleRegionPreset,
                            onToggleRoutePreset = viewModel::toggleRoutePreset,
                        )
                    }

                    // 5. Active Filter Result Strip / Warning Banner
                    if (uiState.filterState.hasActiveFilters) {
                        item(key = "active_filter_strip") {
                            ActiveFilterResultStrip(
                                matchedCount = uiState.filteredDeals.size,
                                totalCount = uiState.allDeals.size,
                                onResetFilters = viewModel::resetFilters,
                                modifier = Modifier.padding(horizontal = 14.dp),
                            )
                        }
                    }

                    val noticeMessage = uiState.errorMessage ?: uiState.warningMessage
                    if (!noticeMessage.isNullOrBlank()) {
                        item(key = "notice_banner") {
                            DealsNoticeBanner(
                                message = noticeMessage,
                                isError = uiState.errorMessage != null,
                                onRetry = { viewModel.refresh(force = true) },
                                modifier = Modifier.padding(horizontal = 14.dp),
                            )
                        }
                    }

                    // 6. Deal Cards / Skeletons / Empty State
                    when {
                        uiState.isInitialLoading -> {
                            items(count = 4, key = { idx -> "deal_skeleton_$idx" }) {
                                PromoDealCardSkeleton(
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                )
                            }
                        }

                        uiState.filteredDeals.isEmpty() -> {
                            item(key = "empty_deals_state") {
                                DealsEmptyStateCard(
                                    hasActiveFilters = uiState.filterState.hasActiveFilters,
                                    isOverseasTab = uiState.filterState.sourceTab == DealSourceTab.OVERSEAS,
                                    onResetFilters = viewModel::resetFilters,
                                    onSwitchToAllTab = { viewModel.selectSourceTab(DealSourceTab.ALL) },
                                    onRefresh = { viewModel.refresh(force = true) },
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                )
                            }
                        }

                        else -> {
                            items(
                                items = uiState.filteredDeals,
                                key = { deal -> deal.id },
                            ) { deal ->
                                PromoDealCard(
                                    deal = deal,
                                    nowEpochMillis = nowEpochMillis,
                                    onOpenSource = {
                                        val tid = deal.topicId
                                        if (deal.source == DealSource.NODESEEK && tid != null && tid > 0L) {
                                            onOpenTopic(tid, deal.title)
                                        } else {
                                            openUrl(deal.sourceUrl)
                                        }
                                    },
                                    onOpenOrderUrl = { url -> openUrl(url) },
                                    onCopyPromoCode = copyPromoCode,
                                    onAddRestockWatch = {
                                        val watchUrl = deal.orderUrl?.takeIf { it.isNotBlank() } ?: deal.sourceUrl
                                        onAddRestockWatch(
                                            watchUrl,
                                            deal.title,
                                            deal.restockPriceHint,
                                            deal.topicId,
                                        )
                                    },
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DealsHubTopBar(
    fxRateLabel: String,
    isRefreshing: Boolean,
    onBack: (() -> Unit)?,
    onRefresh: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val extraType = NodePulseThemeTokens.extraTypography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseGlassSurface(
                showBottomBorder = true,
                tonalAccent = MaterialTheme.colorScheme.primary,
            )
            .statusBarsPadding()
            .padding(start = 6.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "黑五大促专题",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 2.5.dp),
                ) {
                    Text(
                        text = "11.27",
                        style = extraType.microLabel.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        ).withTabularNumbers(),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Text(
                text = "VPS 价格解析 · 实时汇率 $fxRateLabel",
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(0.8.dp, ext.cardBorder, CircleShape)
                .clickable(enabled = !isRefreshing, role = Role.Button, onClick = onRefresh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = "刷新大促列表",
                tint = if (isRefreshing) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun BlackFridayHeroCountdownCard(
    countdown: BlackFridayCountdownSnapshot,
    stats: DealsHubSummaryStats,
    fxUsdToCnyRate: BigDecimal,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val extraType = NodePulseThemeTokens.extraTypography

    val lowestPriceText = remember(stats.lowestAnnualDeal) {
        val lowest = stats.lowestAnnualDeal?.annualizedUsd
        if (lowest != null) {
            "$${PromoDealCardModel.formatCompactDecimal(lowest)}/年"
        } else {
            "解析中"
        }
    }
    val lowestCnyHint = remember(stats.lowestAnnualDeal, fxUsdToCnyRate) {
        val lowestCny = stats.lowestAnnualDeal?.annualizedCny
        if (lowestCny != null) {
            "≈ ¥${PromoDealCardModel.formatWholeOrTwoDecimals(lowestCny)}"
        } else {
            "实时汇算"
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.HeroBento,
                elevation = PulseElevation.Card,
            )
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header row: Phase micro-pill + Schedule hint
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 3.5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = "黑五 · 11/27",
                    style = extraType.microLabel.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            Text(
                text = countdown.eventDateRangeLabel,
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        // Phase title + 4-segment neutral countdown tiles
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = countdown.phase.headlineLabel,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CountdownDigitTile(
                    value = countdown.days.toString().padStart(2, '0'),
                    unit = "天",
                    modifier = Modifier.weight(1f),
                )
                CountdownDigitTile(
                    value = countdown.hours.toString().padStart(2, '0'),
                    unit = "时",
                    modifier = Modifier.weight(1f),
                )
                CountdownDigitTile(
                    value = countdown.minutes.toString().padStart(2, '0'),
                    unit = "分",
                    modifier = Modifier.weight(1f),
                )
                CountdownDigitTile(
                    value = countdown.seconds.toString().padStart(2, '0'),
                    unit = "秒",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        PulseGradientDivider(color = ext.cardBorder.copy(alpha = 0.55f))

        // Borderless 3-column summary telemetry row matching StickyValuationTelemetryCard
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeroKpiColumn(
                label = "实时大促",
                value = "${stats.totalCount} 条",
                modifier = Modifier.weight(1f),
            )
            HeroKpiColumn(
                label = "年付最低 ($lowestCnyHint)",
                value = lowestPriceText,
                valueColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1.3f),
            )
            HeroKpiColumn(
                label = "含优惠码",
                value = "${stats.couponDealCount} 条",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CountdownDigitTile(
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
) {
    val tileShape = RoundedCornerShape(10.dp)

    Column(
        modifier = modifier
            .clip(tileShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                shape = tileShape,
            )
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = NodePulseThemeTokens.extraTypography.metricValue.copy(
                fontSize = 21.sp,
                fontWeight = FontWeight.ExtraBold,
            ).withTabularNumbers(),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            text = unit,
            style = NodePulseThemeTokens.extraTypography.microLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
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
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
            ).withTabularNumbers(),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DealsSourceTabBar(
    selectedTab: DealSourceTab,
    stats: DealsHubSummaryStats,
    onSelectTab: (DealSourceTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = remember(stats) {
        DealSourceTab.entries.map { tab ->
            val count = when (tab) {
                DealSourceTab.ALL -> stats.totalCount
                DealSourceTab.NODESEEK -> stats.nodeseekCount
                DealSourceTab.OVERSEAS -> stats.overseasCount
            }
            val label = if (count > 0) "${tab.label} ($count)" else tab.label
            SortSegmentItem(value = tab, label = label)
        }
    }
    SortSegmentedSwitcher(
        options = options,
        selectedValue = selectedTab,
        onSelect = onSelectTab,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun DealsSearchAndSortRow(
    keyword: String,
    sortOption: DealSortOption,
    onKeywordChange: (String) -> Unit,
    onSelectSort: (DealSortOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val fieldShape = RoundedCornerShape(12.dp)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Compact keyword search box
        Row(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clip(fieldShape)
                .background(ext.cardSurface)
                .border(width = 0.85.dp, color = ext.cardBorder, shape = fieldShape)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (keyword.isEmpty()) {
                    Text(
                        text = "搜商家、线路、机房或优惠码...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = keyword,
                    onValueChange = onKeywordChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (keyword.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "清空搜索",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .clickable { onKeywordChange("") },
                )
            }
        }

        // Sort dropdown selector
        Box {
            Row(
                modifier = Modifier
                    .height(40.dp)
                    .clip(fieldShape)
                    .background(ext.cardSurface)
                    .border(width = 0.85.dp, color = ext.cardBorder, shape = fieldShape)
                    .clickable(role = Role.Button) { sortMenuExpanded = true }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Sort,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = sortOption.label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }

            DropdownMenu(
                expanded = sortMenuExpanded,
                onDismissRequest = { sortMenuExpanded = false },
            ) {
                DealSortOption.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option.label,
                                fontWeight = if (option == sortOption) FontWeight.Bold else FontWeight.Normal,
                                color = if (option == sortOption) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        },
                        trailingIcon = if (option == sortOption) {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        } else {
                            null
                        },
                        onClick = {
                            sortMenuExpanded = false
                            onSelectSort(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DealsQuickFilterBars(
    filterState: DealFilterState,
    onSelectPriceCeiling: (DealPriceCeiling) -> Unit,
    onToggleAnnualOnly: () -> Unit,
    onToggleRequirePromoCode: () -> Unit,
    onToggleRequireOrderUrl: () -> Unit,
    onToggleHideOutOfStock: () -> Unit,
    onToggleRegionPreset: (DealRegionPreset) -> Unit,
    onToggleRoutePreset: (DealRoutePreset) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        // Row 1: Price ceilings & deal attribute filters
        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            item(key = "chip_under_20") {
                DealFilterToggleChip(
                    label = DealPriceCeiling.UNDER_20.label,
                    selected = filterState.priceCeiling == DealPriceCeiling.UNDER_20,
                    onClick = { onSelectPriceCeiling(DealPriceCeiling.UNDER_20) },
                )
            }
            item(key = "chip_under_15") {
                DealFilterToggleChip(
                    label = DealPriceCeiling.UNDER_15.label,
                    selected = filterState.priceCeiling == DealPriceCeiling.UNDER_15,
                    onClick = { onSelectPriceCeiling(DealPriceCeiling.UNDER_15) },
                )
            }
            item(key = "chip_under_35") {
                DealFilterToggleChip(
                    label = DealPriceCeiling.UNDER_35.label,
                    selected = filterState.priceCeiling == DealPriceCeiling.UNDER_35,
                    onClick = { onSelectPriceCeiling(DealPriceCeiling.UNDER_35) },
                )
            }
            item(key = "chip_annual_only") {
                DealFilterToggleChip(
                    label = "年付特惠",
                    selected = filterState.annualOnly,
                    onClick = onToggleAnnualOnly,
                )
            }
            item(key = "chip_promo_code") {
                DealFilterToggleChip(
                    label = "含优惠码",
                    selected = filterState.requirePromoCode,
                    onClick = onToggleRequirePromoCode,
                )
            }
            item(key = "chip_order_url") {
                DealFilterToggleChip(
                    label = "可直达抢购",
                    selected = filterState.requireOrderUrl,
                    onClick = onToggleRequireOrderUrl,
                )
            }
            item(key = "chip_in_stock") {
                DealFilterToggleChip(
                    label = "隐藏疑似售罄",
                    selected = filterState.hideOutOfStock,
                    onClick = onToggleHideOutOfStock,
                )
            }
        }

        // Row 2: Region & optimized route filters
        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            items(
                items = DealRegionPreset.entries,
                key = { "region_${it.name}" },
            ) { preset ->
                DealFilterToggleChip(
                    label = preset.label,
                    selected = preset in filterState.selectedRegions,
                    onClick = { onToggleRegionPreset(preset) },
                )
            }
            items(
                items = DealRoutePreset.entries,
                key = { "route_${it.name}" },
            ) { preset ->
                DealFilterToggleChip(
                    label = preset.label,
                    selected = preset in filterState.selectedRoutes,
                    onClick = { onToggleRoutePreset(preset) },
                )
            }
        }
    }
}

@Composable
private fun DealFilterToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val shape = CircleShape
    val bgColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)
    } else {
        ext.cardBorder
    }
    val textColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .clip(shape)
            .background(bgColor)
            .border(width = if (selected) 0.9.dp else 0.8.dp, color = borderColor, shape = shape)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 12.sp,
            ).withTabularNumbers(),
            color = textColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun ActiveFilterResultStrip(
    matchedCount: Int,
    totalCount: Int,
    onResetFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "筛选命中 $matchedCount / $totalCount 条大促",
            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onResetFilters,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.FilterAltOff,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "重置筛选",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun DealsNoticeBanner(
    message: String,
    isError: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(containerColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        if (isError) {
            TextButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) {
                Text(text = "重试", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * Expressive structured VPS Promo Deal Card with spec badges, region/route pills, highlighted
 * annualized USD & CNY pricing, 1-tap copyable coupon chip, per-deal expiry countdown, and
 * 3-action bar (`查看原帖` / `直达抢购` / `加入补货监控`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PromoDealCard(
    deal: PromoDealCardModel,
    nowEpochMillis: Long,
    onOpenSource: () -> Unit,
    onOpenOrderUrl: (String) -> Unit,
    onCopyPromoCode: (String) -> Unit,
    onAddRestockWatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val extraType = NodePulseThemeTokens.extraTypography

    val expiryCountdown: DealExpiryCountdown? = remember(deal.expiresAtEpochMillis, nowEpochMillis / 30_000L) {
        BlackFridayCountdownCalculator.formatDealExpiry(
            expiresAtEpochMillis = deal.expiresAtEpochMillis,
            nowEpochMillis = nowEpochMillis,
        )
    }
    val isUrgentExpiry = remember(deal.expiresAtEpochMillis, nowEpochMillis / 30_000L) {
        val exp = deal.expiresAtEpochMillis
        exp != null && exp > nowEpochMillis && (exp - nowEpochMillis) <= 24L * 3_600_000L
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(shape = PulseArchitecturalShapes.EditorialCard)
            .clickable(onClick = onOpenSource)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Row 1: Source badge + Merchant pill + Expiry / Stock status
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (deal.source == DealSource.NODESEEK) {
                CategoryMicroTag(
                    slug = deal.categorySlug.ifBlank { "info" },
                    labelOverride = deal.source.badgeLabel,
                    showIcon = false,
                    compact = true,
                )
            } else {
                DealMicroBadge(
                    text = deal.source.badgeLabel,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    icon = Icons.Rounded.Public,
                )
            }

            if (deal.merchantName.isNotBlank()) {
                DealMicroBadge(
                    text = deal.merchantName,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            if (deal.isOutOfStockHint) {
                DealMicroBadge(
                    text = "疑似售罄",
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (expiryCountdown != null) {
                val badgeBg = when {
                    expiryCountdown.isExpired -> MaterialTheme.colorScheme.surfaceContainerHigh
                    isUrgentExpiry || expiryCountdown.isUrgent -> ext.drumstickContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                }
                val badgeFg = when {
                    expiryCountdown.isExpired -> MaterialTheme.colorScheme.onSurfaceVariant
                    isUrgentExpiry || expiryCountdown.isUrgent -> ext.onDrumstickContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                DealMicroBadge(
                    text = expiryCountdown.label,
                    containerColor = badgeBg,
                    contentColor = badgeFg,
                    icon = Icons.Rounded.Schedule,
                )
            }
        }

        // Row 2: Title & optional summary
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = deal.title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    lineHeight = 20.5.sp,
                ),
                color = if (deal.isOutOfStockHint) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (deal.translatedSummary.isNotBlank()) {
                Text(
                    text = deal.translatedSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Row 3: Clean Inline Price & Cycle Strip (modeled after 09-vps-valuation.png)
        val priceHeadline = deal.primaryPriceLabel
        if (!priceHeadline.isNullOrBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val parts = priceHeadline.split("·", limit = 2)
                val primaryPrice = parts.first().trim()
                val cnyPart = parts.getOrNull(1)?.trim()

                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = primaryPrice,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                        ).withTabularNumbers(),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                    if (!cnyPart.isNullOrBlank()) {
                        Text(
                            text = cnyPart,
                            style = MaterialTheme.typography.bodyMedium.withTabularNumbers(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (deal.billingCycleLabel.isNotBlank()) {
                    DealMicroBadge(
                        text = deal.billingCycleLabel,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Row 4: Specs, Region & Route Badges FlowRow
        val hasBadges = deal.specBadges.isNotEmpty() || deal.regionTags.isNotEmpty() || deal.routeTags.isNotEmpty()
        if (hasBadges) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (deal.specsSummaryLine.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .clip(PulseArchitecturalShapes.SmallChip)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .border(
                                width = 0.7.dp,
                                color = ext.cardBorder,
                                shape = PulseArchitecturalShapes.SmallChip,
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = deal.specsSummaryLine,
                            style = extraType.codeLabel.copy(
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            ).withTabularNumbers(),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                deal.regionTags.forEach { region ->
                    DealMicroBadge(
                        text = region,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                deal.routeTags.forEach { route ->
                    DealMicroBadge(
                        text = route,
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        // Row 5: 1-Tap Copyable Promo Code Strip
        val coupon = deal.promoCode?.takeIf { it.isNotBlank() }
        if (coupon != null) {
            val couponShape = RoundedCornerShape(10.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(couponShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(
                        width = 0.8.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        shape = couponShape,
                    )
                    .pulseBouncyClickable(
                        role = Role.Button,
                        onClickLabel = "复制优惠码 $coupon",
                        onClick = { onCopyPromoCode(coupon) },
                    )
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ConfirmationNumber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "优惠码",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = coupon,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = "复制优惠码",
                        style = extraType.microLabel,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // Row 6: Community Telemetry + Uniform Action Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (deal.replyCount > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ChatBubbleOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = "${deal.replyCount}",
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (deal.publishedAtEpochMillis > 0L) {
                Text(
                    text = formatCompactRelativeAge(deal.publishedAtEpochMillis, nowEpochMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onOpenSource,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(0.8.dp, ext.cardBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 36.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "查看原帖",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }

            FilledTonalButton(
                onClick = onAddRestockWatch,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1.05f)
                    .defaultMinSize(minHeight = 36.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.NotificationsActive,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "加入监控",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val directOrderUrl = deal.orderUrl?.takeIf { it.isNotBlank() }
            if (directOrderUrl != null) {
                Button(
                    onClick = { onOpenOrderUrl(directOrderUrl) },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ShoppingCart,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "直达抢购",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun DealMicroBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .clip(PulseArchitecturalShapes.SmallChip)
            .background(containerColor)
            .padding(horizontal = 7.dp, vertical = 3.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            text = text,
            style = NodePulseThemeTokens.extraTypography.microLabel.copy(
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PromoDealCardSkeleton(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(shape = PulseArchitecturalShapes.EditorialCard)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PulseShimmerBox(modifier = Modifier.width(64.dp).height(20.dp), shape = RoundedCornerShape(6.dp))
            PulseShimmerBox(modifier = Modifier.width(76.dp).height(20.dp), shape = RoundedCornerShape(6.dp))
            Spacer(modifier = Modifier.weight(1f))
            PulseShimmerBox(modifier = Modifier.width(56.dp).height(20.dp), shape = RoundedCornerShape(6.dp))
        }
        PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.92f).height(16.dp))
        PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.65f).height(16.dp))
        PulseShimmerBox(modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PulseShimmerBox(modifier = Modifier.weight(1f).height(34.dp), shape = RoundedCornerShape(10.dp))
            PulseShimmerBox(modifier = Modifier.weight(1f).height(34.dp), shape = RoundedCornerShape(10.dp))
            PulseShimmerBox(modifier = Modifier.weight(1f).height(34.dp), shape = RoundedCornerShape(10.dp))
        }
    }
}

@Composable
private fun DealsEmptyStateCard(
    hasActiveFilters: Boolean,
    isOverseasTab: Boolean,
    onResetFilters: () -> Unit,
    onSwitchToAllTab: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(shape = PulseArchitecturalShapes.EditorialCard)
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (hasActiveFilters) Icons.Rounded.FilterAltOff else Icons.Rounded.LocalFireDepartment,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(34.dp),
        )
        Text(
            text = when {
                hasActiveFilters -> "当前筛选条件下暂无匹配大促"
                isOverseasTab -> "海外情报源暂未同步到最新黑五贴"
                else -> "暂未抓取到新的大促线报"
            },
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = when {
                hasActiveFilters -> "可以放宽价格上限、线路或机房筛选条件再看看。"
                isOverseasTab -> "可下拉刷新海外源，或切换「全部大促」查看站内最新黑五特惠。"
                else -> "下拉刷新重新拉取站内情报、促销与黑五搜索线报。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hasActiveFilters) {
                Button(onClick = onResetFilters) {
                    Text("清空筛选条件")
                }
            } else if (isOverseasTab) {
                OutlinedButton(onClick = onSwitchToAllTab) {
                    Text("查看全部大促")
                }
                Button(onClick = onRefresh) {
                    Text("立即刷新")
                }
            } else {
                Button(onClick = onRefresh) {
                    Text("重新加载")
                }
            }
        }
    }
}

private fun formatCompactRelativeAge(publishedAtMillis: Long, nowMillis: Long): String {
    val diffSec = abs(nowMillis - publishedAtMillis) / 1000L
    return when {
        diffSec < 60L -> "刚刚"
        diffSec < 3600L -> "${diffSec / 60L} 分钟前"
        diffSec < 86_400L -> "${diffSec / 3600L} 小时前"
        diffSec < 86_400L * 30L -> "${diffSec / 86_400L} 天前"
        else -> "较早发布"
    }
}

private fun copyDealTextToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun openUrlInBrowser(context: Context, rawUrl: String) {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(trimmed)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
