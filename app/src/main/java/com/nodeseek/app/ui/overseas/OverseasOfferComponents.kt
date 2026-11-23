package com.nodeseek.app.ui.overseas

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.ui.components.PulseSearchField
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
 * Renders clean, tag-free [OverseasOfferCardUiModel] items with zero translation clutter.
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
    headerContent: (@Composable () -> Unit)? = null,
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
            if (headerContent != null) {
                item(key = "overseas_header_controls", contentType = "header_controls") {
                    headerContent()
                }
            }

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
    PulseSearchField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = "搜索商家、原帖标题或促销关键词…",
        modifier = Modifier.fillMaxWidth(),
    )
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
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onRetry)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * Clean, calm editorial Overseas VPS promotion card:
 * 1) Merchant monogram + merchant name + relative publish time
 * 2) Original clean headline title (no translation UI, no extracted price/spec/region tags)
 * 3) Clean summary (when informative)
 * 4) Action bar: Source link / Auto-translating One-click Repost / View Detail
 */
@Composable
fun OverseasOfferCard(
    cardModel: OverseasOfferCardUiModel,
    isReposting: Boolean,
    onOpenDetail: (OverseasDealOffer) -> Unit,
    onOpenSourceLink: (String) -> Unit,
    onRepostToForum: (OverseasDealOffer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val interactionSource = remember { MutableInteractionSource() }

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
        // 1. Merchant & Publish Time Meta Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                modifier = Modifier.weight(1f),
            )

            if (cardModel.relativeTimeText.isNotBlank()) {
                Text(
                    text = cardModel.relativeTimeText,
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        // 2. Clean Core Title
        Text(
            text = cardModel.headlineTitle,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )

        // 3. Clean Summary (omits boilerplate fallback)
        if (!cardModel.cleanSummary.isNullOrBlank()) {
            Text(
                text = cardModel.cleanSummary,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        PulseGradientDivider()

        // 4. Compact Action Footer
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
                label = if (isReposting) "正在翻译排版…" else "一键搬贴",
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
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PulseShimmerBox(modifier = Modifier.size(22.dp), shape = RoundedCornerShape(7.dp))
                PulseShimmerBox(modifier = Modifier.width(84.dp).height(14.dp))
            }
            PulseShimmerBox(modifier = Modifier.width(46.dp).height(12.dp))
        }
        PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.92f).height(20.dp))
        PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.84f).height(16.dp))
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
            Text(
                text = if (hasSearchQuery) "清空搜索" else "立即刷新情报",
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
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
