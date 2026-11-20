package com.nodeseek.app.ui.overseas

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseShimmerBox
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.withTabularNumbers

/**
 * Overseas VPS Promotions feed composable backed by the LowEndTalk promotion monitor API.
 * Uses precomputed [OverseasOfferCardUiModel] items with stable keys and zero scroll-time animation overhead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverseasOfferFeedContent(
    uiState: OverseasIntelUiState,
    onSearchQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadNextPage: () -> Unit,
    onOpenDetail: (OverseasDealOffer) -> Unit,
    onOpenSourceLink: (String) -> Unit,
    onRepostToForum: (OverseasDealOffer) -> Unit,
    modifier: Modifier = Modifier,
    onCopyPromoCode: ((String) -> Unit)? = null,
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
            if (!uiState.syncStatus.errorMessage.isNullOrBlank()) {
                item(key = "overseas_sync_error_banner", contentType = "error_banner") {
                    OverseasErrorNoticeBanner(
                        message = uiState.syncStatus.errorMessage,
                        onRetry = onRefresh,
                    )
                }
            }

            item(key = "overseas_search_bar", contentType = "search_bar") {
                OverseasCompactSearchField(
                    query = uiState.searchQuery,
                    onQueryChange = onSearchQueryChange,
                )
            }

            when {
                uiState.syncStatus.isRefreshing && uiState.offerCards.isEmpty() -> {
                    items(
                        count = 4,
                        key = { idx -> "shimmer_$idx" },
                        contentType = { "shimmer_card" },
                    ) {
                        OverseasOfferShimmerCard()
                    }
                }
                uiState.offerCards.isEmpty() -> {
                    item(key = "overseas_empty_state", contentType = "empty_state") {
                        OverseasEmptyStateCard(
                            hasSearchQuery = uiState.searchQuery.isNotBlank(),
                            onClearSearch = { onSearchQueryChange("") },
                            onRefresh = onRefresh,
                        )
                    }
                }
                else -> {
                    items(
                        items = uiState.offerCards,
                        key = { it.id },
                        contentType = { "offer_card" },
                    ) { cardModel ->
                        OverseasOfferCard(
                            cardModel = cardModel,
                            isReposting = cardModel.id in uiState.repostingOfferIds,
                            onOpenDetail = onOpenDetail,
                            onOpenSourceLink = onOpenSourceLink,
                            onRepostToForum = onRepostToForum,
                            onCopyPromoCode = onCopyPromoCode,
                        )
                    }

                    if (uiState.searchQuery.isBlank() && uiState.syncStatus.hasMore) {
                        item(key = "overseas_load_more_footer", contentType = "load_more") {
                            LaunchedEffect(uiState.offerCards.size, uiState.syncStatus.currentPage) {
                                onLoadNextPage()
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.2.dp,
                                )
                            }
                        }
                    }
                }
            }

            item(key = "overseas_bottom_spacer", contentType = "spacer") {
                Spacer(modifier = Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun OverseasCompactSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val focusManager = LocalFocusManager.current
    var isFocused by remember { mutableStateOf(false) }

    val borderColor = if (isFocused) {
        MaterialTheme.colorScheme.primary
    } else {
        ext.cardBorder
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .then(
                if (!isDark) {
                    Modifier.shadow(
                        elevation = if (isFocused) PulseElevation.CardElevated else PulseElevation.Subtle,
                        shape = PulseArchitecturalShapes.Pill,
                        clip = false,
                        ambientColor = ext.softShadowColor,
                        spotColor = ext.softShadowColor,
                    )
                } else {
                    Modifier
                },
            )
            .clip(PulseArchitecturalShapes.Pill)
            .background(ext.cardSurface)
            .border(
                width = if (isFocused) 1.25.dp else 0.9.dp,
                color = borderColor,
                shape = PulseArchitecturalShapes.Pill,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = "搜索商家、机房地区、配置或促销关键词…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isFocused = it.isFocused },
            )
        }

        AnimatedVisibility(
            visible = query.isNotEmpty(),
            enter = scaleIn(NodePulseMotion.microBounceSpring()) + fadeIn(),
            exit = fadeOut(tween(120)),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(role = Role.Button, onClick = { onQueryChange("") }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "清空搜索",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun OverseasErrorNoticeBanner(
    message: String,
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
            text = message,
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

/**
 * NodePulse editorial Overseas VPS promotion card with clear 4-tier visual hierarchy:
 * 1) Header meta group: Merchant monogram + name + source micro-badge + primary status tag + tabular time
 * 2) Title group: Headline (+ optional bilingual English subtitle)
 * 3) Highlight group: Prominent tabular price pill + location/region group + promo code chip
 * 4) Specs & perks group: Unified rounded chips for CPU/RAM/Storage/Traffic/Port & perks + optional AI summary
 * 5) Compact action footer: Source link / One-click OP repost / View detail CTA
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverseasOfferCard(
    cardModel: OverseasOfferCardUiModel,
    isReposting: Boolean,
    onOpenDetail: (OverseasDealOffer) -> Unit,
    onOpenSourceLink: (String) -> Unit,
    onRepostToForum: (OverseasDealOffer) -> Unit,
    modifier: Modifier = Modifier,
    onCopyPromoCode: ((String) -> Unit)? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val interactionSource = remember { MutableInteractionSource() }

    val headerTag = remember(cardModel.promoTags) {
        cardModel.promoTags.firstOrNull { it.tone == OfferTagTone.WARNING }
            ?: cardModel.promoTags.firstOrNull { it.tone == OfferTagTone.PROMO }
    }
    val recurringTag = remember(cardModel.promoTags) {
        cardModel.promoTags.firstOrNull { it.label == "续费同价" }
    }
    val secondaryChipsTags = remember(cardModel.promoTags, headerTag, recurringTag) {
        cardModel.promoTags.filter { it !== headerTag && it !== recurringTag }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                elevation = PulseElevation.Card,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClickLabel = "查看促销详情",
                onClick = { onOpenDetail(cardModel.offer) },
            )
            .padding(horizontal = 15.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── Tier 1: Merchant + Source + Event/Status Tag + Relative Time ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(
                                alpha = if (isDark) 0.72f else 0.88f,
                            ),
                        )
                        .border(
                            width = 0.75.dp,
                            color = MaterialTheme.colorScheme.primary.copy(
                                alpha = if (isDark) 0.36f else 0.22f,
                            ),
                            shape = RoundedCornerShape(7.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = cardModel.merchantInitial,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                    )
                }

                Text(
                    text = cardModel.merchantLabel,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 5.5.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = cardModel.sourceBadge,
                        style = NodePulseThemeTokens.extraTypography.microLabel.copy(
                            fontSize = 9.5.sp,
                            lineHeight = 12.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }

                if (headerTag != null) {
                    OfferUnifiedTagChip(tag = headerTag)
                }
            }

            if (cardModel.relativeTimeText.isNotBlank()) {
                Text(
                    text = cardModel.relativeTimeText,
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        // ── Tier 2: Headline Title (+ Bilingual Original Subtitle) ──
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = cardModel.headlineTitle,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!cardModel.secondaryTitle.isNullOrBlank()) {
                Text(
                    text = cardModel.secondaryTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // ── Tier 3 & 4: Prominent Price Callout + Region Group + Specs & Perks ──
        val hasPriceOrCodeBand = cardModel.priceBadge != null ||
            recurringTag != null ||
            cardModel.promoCode != null

        if (hasPriceOrCodeBand) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                cardModel.priceBadge?.let { price ->
                    OfferPriceHighlightPill(
                        priceBadge = price,
                        isRecurring = recurringTag != null,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }

                if (cardModel.priceBadge == null && recurringTag != null) {
                    OfferUnifiedTagChip(
                        tag = recurringTag,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }

                if (cardModel.regionTags.isNotEmpty()) {
                    OfferRegionChip(
                        regionTags = cardModel.regionTags,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }

                cardModel.promoCode?.let { code ->
                    OfferPromoCodeChip(
                        code = code,
                        onCopyPromoCode = onCopyPromoCode,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }

        val inlineRegionInSpecs = !hasPriceOrCodeBand && cardModel.regionTags.isNotEmpty()
        if (inlineRegionInSpecs || cardModel.specChips.isNotEmpty() || secondaryChipsTags.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (inlineRegionInSpecs) {
                    OfferRegionChip(
                        regionTags = cardModel.regionTags,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }

                cardModel.specChips.forEach { spec ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .clip(PulseArchitecturalShapes.SmallChip)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .border(
                                width = 0.75.dp,
                                color = ext.cardBorder,
                                shape = PulseArchitecturalShapes.SmallChip,
                            )
                            .padding(horizontal = 8.dp, vertical = 4.5.dp),
                    ) {
                        Text(
                            text = spec,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Medium,
                            ).withTabularNumbers(),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                }

                secondaryChipsTags.forEach { tag ->
                    OfferUnifiedTagChip(
                        tag = tag,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }

        // ── Tier 5: Clean Editorial Summary (omits generic server fallback boilerplate) ──
        if (!cardModel.cleanSummary.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerLow.copy(
                            alpha = if (isDark) 0.64f else 0.74f,
                        ),
                    )
                    .border(
                        width = 0.6.dp,
                        color = ext.cardBorder.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 11.dp, vertical = 9.dp),
            ) {
                Text(
                    text = cardModel.cleanSummary,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        PulseGradientDivider()

        // ── Tier 6: Compact Action Footer ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OfferActionChip(
                label = "原帖",
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                containerColor = ext.cardSurface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                borderColor = ext.cardBorder,
                onClick = { onOpenSourceLink(cardModel.offer.sourceLink) },
            )

            OfferActionChip(
                label = if (isReposting) "搬运中…" else "一键搬运",
                icon = Icons.AutoMirrored.Rounded.Send,
                isLoading = isReposting,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = { onRepostToForum(cardModel.offer) },
            )

            Spacer(modifier = Modifier.weight(1f))

            OfferActionChip(
                label = "查看详情",
                icon = Icons.AutoMirrored.Rounded.Article,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = { onOpenDetail(cardModel.offer) },
            )
        }
    }
}

@Composable
private fun OfferRegionChip(
    regionTags: List<String>,
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark
    Row(
        modifier = modifier
            .heightIn(min = 26.dp)
            .clip(PulseArchitecturalShapes.SmallChip)
            .background(
                MaterialTheme.colorScheme.secondaryContainer.copy(
                    alpha = if (isDark) 0.56f else 0.72f,
                ),
            )
            .border(
                width = 0.75.dp,
                color = MaterialTheme.colorScheme.secondary.copy(
                    alpha = if (isDark) 0.30f else 0.18f,
                ),
                shape = PulseArchitecturalShapes.SmallChip,
            )
            .padding(horizontal = 8.dp, vertical = 4.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = regionTags.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OfferPromoCodeChip(
    code: String,
    onCopyPromoCode: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    Row(
        modifier = modifier
            .heightIn(min = 26.dp)
            .clip(PulseArchitecturalShapes.SmallChip)
            .background(
                ext.drumstickContainer.copy(
                    alpha = if (isDark) 0.76f else 0.90f,
                ),
            )
            .border(
                width = 0.75.dp,
                color = ext.onDrumstickContainer.copy(
                    alpha = if (isDark) 0.36f else 0.22f,
                ),
                shape = PulseArchitecturalShapes.SmallChip,
            )
            .then(
                if (onCopyPromoCode != null) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = "复制优惠码",
                        onClick = { onCopyPromoCode(code) },
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 8.dp, vertical = 4.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.ConfirmationNumber,
            contentDescription = null,
            tint = ext.onDrumstickContainer,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = "优惠码 $code",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
            ),
            color = ext.onDrumstickContainer,
            maxLines = 1,
        )
        if (onCopyPromoCode != null) {
            Icon(
                imageVector = Icons.Rounded.ContentCopy,
                contentDescription = "复制",
                tint = ext.onDrumstickContainer,
                modifier = Modifier.size(11.5.dp),
            )
        }
    }
}

@Composable
private fun OfferPriceHighlightPill(
    priceBadge: OverseasPriceBadge,
    isRecurring: Boolean,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val containerBrush = Brush.horizontalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.78f else 0.92f),
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.44f else 0.56f),
        ),
    )

    Row(
        modifier = modifier
            .heightIn(min = 28.dp)
            .clip(PulseArchitecturalShapes.MediumBadge)
            .background(containerBrush)
            .border(
                width = 0.85.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.42f else 0.28f),
                shape = PulseArchitecturalShapes.MediumBadge,
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = priceBadge.amountText,
                style = NodePulseThemeTokens.extraTypography.metricValue.copy(
                    fontSize = 17.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                ),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            if (priceBadge.cycleLabel.isNotBlank()) {
                Text(
                    text = priceBadge.cycleLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    ).withTabularNumbers(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 1.dp),
                )
            }
        }
        if (isRecurring) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(ext.drumstickContainer.copy(alpha = if (isDark) 0.82f else 0.95f))
                    .padding(horizontal = 5.dp, vertical = 1.5.dp),
            ) {
                Text(
                    text = "续费同价",
                    style = NodePulseThemeTokens.extraTypography.microLabel.copy(
                        fontSize = 9.5.sp,
                        lineHeight = 12.sp,
                    ),
                    color = ext.onDrumstickContainer,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun OfferUnifiedTagChip(
    tag: OverseasOfferTag,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark

    val (bgColor, borderColor, fgColor) = when (tag.tone) {
        OfferTagTone.PROMO -> Triple(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.65f else 0.80f),
            MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.34f else 0.22f),
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        OfferTagTone.PERK -> Triple(
            ext.drumstickContainer.copy(alpha = if (isDark) 0.70f else 0.88f),
            ext.onDrumstickContainer.copy(alpha = if (isDark) 0.34f else 0.20f),
            ext.onDrumstickContainer,
        )
        OfferTagTone.WARNING -> Triple(
            MaterialTheme.colorScheme.errorContainer.copy(alpha = if (isDark) 0.72f else 0.86f),
            MaterialTheme.colorScheme.error.copy(alpha = if (isDark) 0.36f else 0.22f),
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }

    Box(
        modifier = modifier
            .clip(PulseArchitecturalShapes.SmallChip)
            .background(bgColor)
            .border(0.75.dp, borderColor, PulseArchitecturalShapes.SmallChip)
            .padding(horizontal = 7.dp, vertical = 3.5.dp),
    ) {
        Text(
            text = tag.label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = fgColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun OfferActionChip(
    label: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color? = null,
    isLoading: Boolean = false,
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
            .pulseBouncyClickable(enabled = !isLoading, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                strokeWidth = 1.8.dp,
                color = contentColor,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(13.5.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun OverseasOfferShimmerCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(shape = PulseArchitecturalShapes.EditorialCard)
            .padding(horizontal = 15.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PulseShimmerBox(modifier = Modifier.size(22.dp), shape = RoundedCornerShape(7.dp))
                PulseShimmerBox(modifier = Modifier.width(84.dp).height(14.dp))
            }
            PulseShimmerBox(modifier = Modifier.width(46.dp).height(12.dp))
        }
        PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.92f).height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PulseShimmerBox(modifier = Modifier.width(82.dp).height(28.dp), shape = PulseArchitecturalShapes.MediumBadge)
            PulseShimmerBox(modifier = Modifier.width(96.dp).height(28.dp), shape = PulseArchitecturalShapes.SmallChip)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PulseShimmerBox(modifier = Modifier.width(56.dp).height(22.dp), shape = PulseArchitecturalShapes.SmallChip)
            PulseShimmerBox(modifier = Modifier.width(64.dp).height(22.dp), shape = PulseArchitecturalShapes.SmallChip)
            PulseShimmerBox(modifier = Modifier.width(74.dp).height(22.dp), shape = PulseArchitecturalShapes.SmallChip)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PulseShimmerBox(modifier = Modifier.width(66.dp).height(28.dp), shape = CircleShape)
            PulseShimmerBox(modifier = Modifier.width(88.dp).height(28.dp), shape = CircleShape)
            Spacer(modifier = Modifier.weight(1f))
            PulseShimmerBox(modifier = Modifier.width(88.dp).height(28.dp), shape = CircleShape)
        }
    }
}

@Composable
private fun OverseasEmptyStateCard(
    hasSearchQuery: Boolean,
    onClearSearch: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(shape = PulseArchitecturalShapes.EditorialCard)
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Public,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp),
        )
        Text(
            text = if (hasSearchQuery) "当前搜索下暂无匹配的促销情报" else "暂未拉取到海外 VPS 促销情报",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = if (hasSearchQuery) {
                "尝试更换或清空搜索关键词"
            } else {
                "下拉刷新或点击下方按钮同步 LowEndTalk 最新促销"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(
            onClick = if (hasSearchQuery) onClearSearch else onRefresh,
            shape = CircleShape,
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (hasSearchQuery) "清空搜索" else "立即刷新情报")
        }
    }
}

internal fun openOverseasExternalUrl(context: Context, url: String) {
    if (url.isBlank()) return
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
