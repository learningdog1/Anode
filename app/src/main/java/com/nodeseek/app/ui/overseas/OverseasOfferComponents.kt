package com.nodeseek.app.ui.overseas

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.overseas.ConvertedPriceTag
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasDisplayMode
import com.nodeseek.app.domain.overseas.OverseasForumPostFormatter
import com.nodeseek.app.domain.overseas.OverseasSource
import com.nodeseek.app.domain.overseas.OverseasSourceFilter
import com.nodeseek.app.domain.overseas.TranslationTier
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.ui.components.SortSegmentItem
import com.nodeseek.app.ui.components.SortSegmentedSwitcher
import com.nodeseek.app.ui.components.formatRelativeTimeChinese
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseShimmerBox
import com.nodeseek.app.ui.theme.PulseStaggeredEntrance
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.withTabularNumbers

/**
 * Reusable Overseas VPS Intelligence feed composable designed to work both standalone inside
 * [OverseasIntelScreen] and embedded inside `DealsHubScreen` (Feature 2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverseasOfferFeedContent(
    uiState: OverseasIntelUiState,
    onSelectFilter: (OverseasSourceFilter) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectDisplayMode: (OverseasDisplayMode) -> Unit,
    onRefresh: () -> Unit,
    onRefreshFxRates: () -> Unit,
    onOpenTranslatorSettings: () -> Unit,
    onTranslateOffer: (String) -> Unit,
    onCopyCoupon: (String) -> Unit,
    onOpenSourceLink: (String) -> Unit,
    onOpenOrderLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    onAddRestockWatch: ((orderUrl: String, title: String, priceLabel: String) -> Unit)? = null,
    onShareDealToForum: ((suggestedTitle: String, markdownBody: String) -> Unit)? = null,
    showHeroSummaryStrip: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
) {
    PullToRefreshBox(
        isRefreshing = uiState.syncStatus.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showHeroSummaryStrip) {
                item(key = "overseas_hero_fx_strip") {
                    OverseasHeroStatusStrip(
                        uiState = uiState,
                        onRefreshFxRates = onRefreshFxRates,
                        onSelectDisplayMode = onSelectDisplayMode,
                        onOpenTranslatorSettings = onOpenTranslatorSettings,
                    )
                }
            }

            // Source error notice (non-blocking — cached/healthy source offers remain visible)
            if (uiState.syncStatus.sourceErrors.isNotEmpty()) {
                item(key = "overseas_source_errors") {
                    OverseasPartialErrorBanner(
                        errors = uiState.syncStatus.sourceErrors,
                        onRetry = onRefresh,
                    )
                }
            }

            // Source filter chips: 全部 / LowEndTalk / LowEndBox / 黑五 Flash
            item(key = "overseas_filter_chips") {
                OverseasSourceFilterRow(
                    selected = uiState.selectedFilter,
                    counts = uiState.sourceCounts,
                    onSelect = onSelectFilter,
                )
            }

            // Compact keyword search bar
            item(key = "overseas_search_bar") {
                OverseasCompactSearchField(
                    query = uiState.searchQuery,
                    onQueryChange = onSearchQueryChange,
                )
            }

            when {
                uiState.syncStatus.isRefreshing && uiState.filteredOffers.isEmpty() -> {
                    items(count = 4, key = { idx -> "shimmer_$idx" }) {
                        OverseasOfferShimmerCard()
                    }
                }
                uiState.filteredOffers.isEmpty() -> {
                    item(key = "overseas_empty_state") {
                        OverseasEmptyStateCard(
                            hasSearchOrFilter = uiState.searchQuery.isNotBlank() ||
                                uiState.selectedFilter != OverseasSourceFilter.ALL,
                            onResetFilters = {
                                onSelectFilter(OverseasSourceFilter.ALL)
                                onSearchQueryChange("")
                            },
                            onRefresh = onRefresh,
                        )
                    }
                }
                else -> {
                    itemsIndexed(
                        items = uiState.filteredOffers,
                        key = { _, offer -> offer.id },
                    ) { index, offer ->
                        PulseStaggeredEntrance(index = index) {
                            OverseasOfferCard(
                                offer = offer,
                                displayMode = uiState.displayMode,
                                isTranslating = offer.id in uiState.translatingOfferIds,
                                onRetranslate = { onTranslateOffer(offer.id) },
                                onCopyCoupon = onCopyCoupon,
                                onOpenSourceLink = { onOpenSourceLink(offer.sourceLink) },
                                onOpenOrderLink = { onOpenOrderLink(offer.primaryOrderUrl) },
                                onAddRestockWatch = onAddRestockWatch?.let { callback ->
                                    {
                                        val title = offer.resolveHeadlineTitle(OverseasDisplayMode.CHINESE)
                                        val priceLabel = offer.primaryPrice?.badgeText.orEmpty()
                                        callback(offer.primaryOrderUrl, title, priceLabel)
                                    }
                                },
                                onShareDealToForum = onShareDealToForum?.let { callback ->
                                    {
                                        val post = OverseasForumPostFormatter.format(
                                            offer = offer,
                                            fxRatesState = uiState.fxRatesState,
                                        )
                                        callback(post.title, post.markdownBody)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            item(key = "overseas_bottom_spacer") {
                Spacer(modifier = Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun OverseasHeroStatusStrip(
    uiState: OverseasIntelUiState,
    onRefreshFxRates: () -> Unit,
    onSelectDisplayMode: (OverseasDisplayMode) -> Unit,
    onOpenTranslatorSettings: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val usdSourceLabel = uiState.fxRatesState.quoteFor(ValuationCurrency.USD).source.label

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Row 1: Real-time FX rate badge + manual FX sync button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(0.6.dp, ext.cardBorder, PulseArchitecturalShapes.SmallChip)
                    .clickable(onClick = onRefreshFxRates)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.CurrencyExchange,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "实时汇率 1 USD = ¥${uiState.usdRateFormatted} · 1 EUR = ¥${uiState.eurRateFormatted}",
                    style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            PulseAccentPill(
                text = if (uiState.fxRatesState.isRefreshing) "同步中…" else usdSourceLabel,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Row 2: Display mode quick toggle (译 / 双语 / 原文) + Translator settings trigger
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SortSegmentedSwitcher(
                options = remember {
                    OverseasDisplayMode.entries.map { mode ->
                        SortSegmentItem(value = mode, label = mode.shortLabel)
                    }
                },
                selectedValue = uiState.displayMode,
                onSelect = onSelectDisplayMode,
                modifier = Modifier.width(196.dp),
            )

            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(ext.cardSurface)
                    .border(0.8.dp, ext.cardBorder, CircleShape)
                    .clickable(onClick = onOpenTranslatorSettings)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (uiState.syncStatus.isTranslating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.8.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = if (uiState.syncStatus.isTranslating) "翻译中…" else "翻译设置",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun OverseasSourceFilterRow(
    selected: OverseasSourceFilter,
    counts: Map<OverseasSourceFilter, Int>,
    onSelect: (OverseasSourceFilter) -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(OverseasSourceFilter.entries) { filter ->
            val count = counts[filter] ?: 0
            val isSelected = selected == filter
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(filter) },
                label = {
                    Text(
                        text = if (count > 0) "${filter.label} ($count)" else filter.label,
                        style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = ext.cardBorder,
                    selectedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}

@Composable
private fun OverseasCompactSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = PulseArchitecturalShapes.EditorialCard,
        placeholder = {
            Text(
                text = "搜索商家、机房（东京 / 洛杉矶）、NVMe 或优惠码…",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Rounded.Clear,
                        contentDescription = "清空搜索",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        } else {
            null
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = ext.cardSurface,
            unfocusedContainerColor = ext.cardSurface,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = ext.cardBorder,
        ),
    )
}

@Composable
private fun OverseasPartialErrorBanner(
    errors: Map<OverseasSource, String>,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PulseArchitecturalShapes.SmallChip)
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = errors.values.joinToString("；"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "重试",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onRetry)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverseasOfferCard(
    offer: OverseasDealOffer,
    displayMode: OverseasDisplayMode,
    isTranslating: Boolean,
    onRetranslate: () -> Unit,
    onCopyCoupon: (String) -> Unit,
    onOpenSourceLink: () -> Unit,
    onOpenOrderLink: () -> Unit,
    modifier: Modifier = Modifier,
    onAddRestockWatch: (() -> Unit)? = null,
    onShareDealToForum: (() -> Unit)? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    var summaryExpanded by rememberSaveable(offer.id) { mutableStateOf(false) }

    val headlineTitle = offer.resolveHeadlineTitle(displayMode)
    val secondaryTitle = offer.resolveSecondaryOriginalTitle(displayMode)
    val primarySummary = offer.resolvePrimarySummary(displayMode)
    val secondarySummary = offer.resolveSecondaryOriginalSummary(displayMode)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(shape = PulseArchitecturalShapes.EditorialCard)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header meta row: Source pill + Flash/OOS badges + Provider + Relative time
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PulseAccentPill(
                text = offer.source.shortLabel,
                icon = Icons.Rounded.Language,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
            if (offer.isFlashOrBlackFriday) {
                PulseAccentPill(
                    text = "黑五/闪购",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (offer.isOutOfStockHint) {
                PulseAccentPill(
                    text = "疑似缺货",
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                text = offer.authorOrProvider,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (offer.publishedAtEpochMillis > 0L) {
                Text(
                    text = formatRelativeTimeChinese(offer.publishedAtEpochMillis.toString()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Title (+ Bilingual original English subtitle)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = headlineTitle,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!secondaryTitle.isNullOrBlank()) {
                Text(
                    text = secondaryTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Prominent Converted Price Badge ($XX/yr · ≈ ¥XXX 人民币) + extra tiers
        if (offer.priceMatches.isNotEmpty()) {
            OverseasPriceHighlightRow(priceMatches = offer.priceMatches)
        }

        // Spec & Datacenter Chips
        if (offer.specHighlights.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                offer.specHighlights.forEach { spec ->
                    Box(
                        modifier = Modifier
                            .clip(PulseArchitecturalShapes.SmallChip)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .border(0.6.dp, ext.cardBorder, PulseArchitecturalShapes.SmallChip)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = spec,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        // 1-Tap Copyable Coupon Chips
        if (offer.couponCodes.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                offer.couponCodes.forEach { code ->
                    Row(
                        modifier = Modifier
                            .clip(PulseArchitecturalShapes.SmallChip)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .border(
                                width = 0.8.dp,
                                color = ext.cardBorder,
                                shape = PulseArchitecturalShapes.SmallChip,
                            )
                            .pulseBouncyClickable { onCopyCoupon(code) }
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ConfirmationNumber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "优惠码 $code",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = "复制优惠码",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        }

        // Expandable summary + translation tier indicator & 1-tap retranslate
        if (primarySummary.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.72f))
                    .clickable { summaryExpanded = !summaryExpanded }
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = primarySummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (summaryExpanded) 18 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                AnimatedVisibility(visible = summaryExpanded && !secondarySummary.isNullOrBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PulseGradientDivider()
                        Text(
                            text = secondarySummary.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 12,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = if (summaryExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = if (summaryExpanded) "收起详情" else "展开详情",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (offer.translationTier != TranslationTier.NONE) {
                            Text(
                                text = "· ${offer.translationTier.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(enabled = !isTranslating, onClick = onRetranslate)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (isTranslating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.6.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                        Text(
                            text = if (isTranslating) "翻译中" else "重译",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        PulseGradientDivider()

        // Action row: 打开原帖 / 加入补货监控 / 一键搬运发帖 / 直达购买
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OfferActionChip(
                label = "打开原帖",
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                containerColor = ext.cardSurface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                borderColor = ext.cardBorder,
                onClick = onOpenSourceLink,
            )

            if (onAddRestockWatch != null) {
                OfferActionChip(
                    label = "加入补货监控",
                    icon = Icons.Rounded.NotificationsActive,
                    containerColor = ext.cardSurface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    borderColor = ext.cardBorder,
                    onClick = onAddRestockWatch,
                )
            }

            if (onShareDealToForum != null) {
                OfferActionChip(
                    label = "一键搬运发帖",
                    icon = Icons.AutoMirrored.Rounded.Send,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = onShareDealToForum,
                )
            }

            if (offer.orderLinks.isNotEmpty()) {
                OfferActionChip(
                    label = "直达购买",
                    icon = Icons.Rounded.ShoppingCart,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    onClick = onOpenOrderLink,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverseasPriceHighlightRow(priceMatches: List<ConvertedPriceTag>) {
    val ext = NodePulseThemeTokens.extendedColors
    val primary = priceMatches.first()
    val secondaryPrices = priceMatches.drop(1).take(2)

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Inline steel-blue price display + neutral CNY conversion
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = primary.rawText,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    .withTabularNumbers(),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "·",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${primary.formattedCny} 人民币",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    .withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Secondary tier price tags if multiple tiers appear in the offer
        secondaryPrices.forEach { tag ->
            Box(
                modifier = Modifier
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(0.6.dp, ext.cardBorder, PulseArchitecturalShapes.SmallChip)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            ) {
                Text(
                    text = tag.badgeText,
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OfferActionChip(
    label: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(containerColor)
            .then(
                if (borderColor != null) {
                    Modifier.border(0.8.dp, borderColor, CircleShape)
                } else {
                    Modifier
                },
            )
            .pulseBouncyClickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor,
        )
    }
}

@Composable
private fun OverseasOfferShimmerCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PulseShimmerBox(modifier = Modifier.width(56.dp).height(20.dp), shape = CircleShape)
            PulseShimmerBox(modifier = Modifier.width(86.dp).height(20.dp), shape = CircleShape)
        }
        PulseShimmerBox(modifier = Modifier.fillMaxWidth().height(22.dp))
        PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.75f).height(16.dp))
        PulseShimmerBox(modifier = Modifier.width(190.dp).height(30.dp), shape = PulseArchitecturalShapes.MediumBadge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PulseShimmerBox(modifier = Modifier.width(64.dp).height(22.dp))
            PulseShimmerBox(modifier = Modifier.width(72.dp).height(22.dp))
            PulseShimmerBox(modifier = Modifier.width(80.dp).height(22.dp))
        }
    }
}

@Composable
private fun OverseasEmptyStateCard(
    hasSearchOrFilter: Boolean,
    onResetFilters: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard()
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Translate,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp),
        )
        Text(
            text = if (hasSearchOrFilter) "当前筛选下暂无匹配的海外情报" else "暂未拉取到海外 VPS 情报",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = if (hasSearchOrFilter) {
                "尝试切换来源标签或清空搜索关键词"
            } else {
                "下拉刷新或点击下方按钮同步 LowEndTalk 与 LowEndBox 最新优惠"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(
            onClick = if (hasSearchOrFilter) onResetFilters else onRefresh,
            shape = CircleShape,
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (hasSearchOrFilter) "显示全部情报" else "立即刷新情报")
        }
    }
}

internal fun copyOverseasCouponToClipboard(context: Context, couponCode: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("VPS Coupon", couponCode))
    }
}

internal fun openOverseasExternalUrl(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
