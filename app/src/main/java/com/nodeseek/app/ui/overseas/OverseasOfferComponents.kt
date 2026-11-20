package com.nodeseek.app.ui.overseas

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseShimmerBox
import com.nodeseek.app.ui.theme.PulseStaggeredEntrance
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard

/**
 * Overseas VPS Promotions feed composable backed by the LowEndTalk promotion monitor API.
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
                item(key = "overseas_sync_error_banner") {
                    OverseasErrorNoticeBanner(
                        message = uiState.syncStatus.errorMessage,
                        onRetry = onRefresh,
                    )
                }
            }

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
                            hasSearchQuery = uiState.searchQuery.isNotBlank(),
                            onClearSearch = { onSearchQueryChange("") },
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
                                isReposting = offer.id in uiState.repostingOfferIds,
                                onOpenDetail = { onOpenDetail(offer) },
                                onOpenSourceLink = { onOpenSourceLink(offer.sourceLink) },
                                onRepostToForum = { onRepostToForum(offer) },
                            )
                        }
                    }

                    if (uiState.searchQuery.isBlank() && uiState.syncStatus.hasMore) {
                        item(key = "overseas_load_more_footer") {
                            LaunchedEffect(uiState.filteredOffers.size, uiState.syncStatus.currentPage) {
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

            item(key = "overseas_bottom_spacer") {
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
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = PulseArchitecturalShapes.EditorialCard,
        placeholder = {
            Text(
                text = "搜索促销标题、简介或商家关键词…",
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
 * Promotion card preserving the Cool Mist & Slate Steel editorial card design while strictly
 * showing ONLY [OverseasDealOffer.title] and [OverseasDealOffer.summary] on the card body
 * (no annual prices, machine specs, coupon codes, or extra tags).
 *
 * Clicking anywhere on the card navigates to the detail screen (`查看详情`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverseasOfferCard(
    offer: OverseasDealOffer,
    isReposting: Boolean,
    onOpenDetail: () -> Unit,
    onOpenSourceLink: () -> Unit,
    onRepostToForum: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(shape = PulseArchitecturalShapes.EditorialCard)
            .clickable(onClick = onOpenDetail)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = offer.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (offer.summary.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.72f))
                    .padding(10.dp),
            ) {
                Text(
                    text = offer.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        PulseGradientDivider()

        // Action row: 查看详情 / 打开原帖 / 一键搬运发帖
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OfferActionChip(
                label = "查看详情",
                icon = Icons.AutoMirrored.Rounded.Article,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = onOpenDetail,
            )

            OfferActionChip(
                label = "打开原帖",
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                containerColor = ext.cardSurface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                borderColor = ext.cardBorder,
                onClick = onOpenSourceLink,
            )

            OfferActionChip(
                label = if (isReposting) "获取主楼…" else "一键搬运发帖",
                icon = Icons.AutoMirrored.Rounded.Send,
                isLoading = isReposting,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = onRepostToForum,
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
            .padding(horizontal = 11.dp, vertical = 7.dp),
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
                modifier = Modifier.size(14.dp),
            )
        }
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
        PulseShimmerBox(modifier = Modifier.fillMaxWidth().height(22.dp))
        PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.92f).height(52.dp), shape = RoundedCornerShape(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PulseShimmerBox(modifier = Modifier.width(84.dp).height(28.dp), shape = CircleShape)
            PulseShimmerBox(modifier = Modifier.width(84.dp).height(28.dp), shape = CircleShape)
            PulseShimmerBox(modifier = Modifier.width(104.dp).height(28.dp), shape = CircleShape)
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
            .pulseCard()
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
