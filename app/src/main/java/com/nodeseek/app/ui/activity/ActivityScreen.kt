package com.nodeseek.app.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.HowToVote
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.data.activity.PreviewForumWatchActivityRepository
import com.nodeseek.app.domain.activity.ActivityCounts
import com.nodeseek.app.domain.activity.ActivityRepository
import com.nodeseek.app.domain.activity.ActivityTimeStatus
import com.nodeseek.app.domain.activity.ForumWatchActivityItem
import com.nodeseek.app.navigation.openNodeSeekOrExternalUrl
import com.nodeseek.app.ui.components.MarkdownRenderer
import com.nodeseek.app.ui.theme.NodePulseTheme
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseShimmerBox
import com.nodeseek.app.ui.theme.PulseStaggeredEntrance
import com.nodeseek.app.ui.theme.PulseThemeMode
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.pulseHeroCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 「活动」页面：接入 ForumWatch 社区活动与福利聚合 API，支持状态筛选、下拉刷新、
 * 原生帖子直跳、详情 Markdown 预览及默认关闭的可选「新活动通知」开关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    repository: ActivityRepository,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    notificationsEnabled: Boolean = false,
    onToggleNotifications: ((Boolean) -> Unit)? = null,
    onOpenTopic: ((topicId: Long, initialFloor: Int, titleHint: String) -> Unit)? = null,
    onOpenUser: ((uid: Long, usernameHint: String) -> Unit)? = null,
    onOpenCategory: ((slug: String) -> Unit)? = null,
) {
    val viewModel: ActivityViewModel = viewModel(
        key = "activity_vm_${System.identityHashCode(repository)}",
        factory = ActivityViewModel.factory(repository = repository),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    var localFallbackNotifEnabled by rememberSaveable(notificationsEnabled) {
        mutableStateOf(notificationsEnabled)
    }
    val effectiveNotifEnabled = if (onToggleNotifications != null) {
        notificationsEnabled
    } else {
        localFallbackNotifEnabled
    }
    val effectiveToggleNotif: (Boolean) -> Unit = remember(onToggleNotifications) {
        { target ->
            if (onToggleNotifications != null) {
                onToggleNotifications(target)
            } else {
                localFallbackNotifEnabled = target
            }
        }
    }

    val openAnyUrl: (String) -> Unit = remember(uriHandler, onOpenTopic, onOpenUser, onOpenCategory) {
        { rawUrl ->
            if (rawUrl.isNotBlank()) {
                openNodeSeekOrExternalUrl(
                    rawUrl = rawUrl,
                    uriHandler = uriHandler,
                    onOpenTopic = onOpenTopic,
                    onOpenUser = onOpenUser,
                    onOpenCategory = onOpenCategory,
                )
            }
        }
    }

    val handleOpenActivityPrimaryTarget: (ForumWatchActivityItem) -> Unit =
        remember(onOpenTopic, openAnyUrl, viewModel) {
            { item ->
                val nsTopicId = item.resolvedNodeSeekTopicId()
                when {
                    nsTopicId != null && onOpenTopic != null -> {
                        onOpenTopic(nsTopicId, 0, item.title)
                    }
                    item.sourceUrl.isNotBlank() -> {
                        openAnyUrl(item.sourceUrl)
                    }
                    else -> {
                        viewModel.openDetailSheet(item)
                    }
                }
            }
        }

    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ActivityTopBar(
                counts = uiState.counts,
                isRefreshing = uiState.isRefreshing,
                onBack = onBack,
                onRefresh = viewModel::refresh,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                ext.heroTintStart.copy(alpha = if (isDark) 0.22f else 0.14f),
                                ext.heroTintEnd.copy(alpha = if (isDark) 0.08f else 0.05f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )

            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = 28.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "activity_notification_switch") {
                        PulseStaggeredEntrance(index = 0) {
                            ActivityNotificationHeaderCard(
                                notificationsEnabled = effectiveNotifEnabled,
                                onToggleNotifications = effectiveToggleNotif,
                            )
                        }
                    }

                    item(key = "activity_filter_bar") {
                        ActivityStatusFilterRow(
                            selectedFilter = uiState.selectedFilter,
                            counts = uiState.counts,
                            onSelectFilter = viewModel::selectFilter,
                        )
                    }

                    if (!uiState.errorMessage.isNullOrBlank() && uiState.activities.isNotEmpty()) {
                        item(key = "activity_inline_error") {
                            ActivityInlineErrorBanner(
                                message = uiState.errorMessage.orEmpty(),
                                onRetry = viewModel::refresh,
                            )
                        }
                    }

                    when {
                        uiState.isInitialLoading -> {
                            items(3, key = { "activity_skeleton_$it" }) {
                                ActivityLoadingSkeletonCard()
                            }
                        }

                        uiState.filteredActivities.isEmpty() -> {
                            item(key = "activity_empty_or_error") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    ActivityEmptyOrErrorCard(
                                        selectedFilter = uiState.selectedFilter,
                                        totalCount = uiState.counts.all,
                                        errorMessage = uiState.errorMessage,
                                        isRefreshing = uiState.isRefreshing,
                                        onRefresh = viewModel::refresh,
                                        onResetFilter = { viewModel.selectFilter(ActivityStatusFilter.ALL) },
                                    )
                                }
                            }
                        }

                        else -> {
                            items(
                                items = uiState.filteredActivities,
                                key = { it.id },
                            ) { item ->
                                ActivityFeedCard(
                                    item = item,
                                    nowEpochMillis = uiState.nowEpochMillis,
                                    onOpenPrimaryTarget = { handleOpenActivityPrimaryTarget(item) },
                                    onOpenDetail = { viewModel.openDetailSheet(item) },
                                    onOpenLuckyUrl = item.luckyUrl?.takeIf { it.isNotBlank() }?.let { url ->
                                        { openAnyUrl(url) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    uiState.detailSheet?.let { sheetState ->
        ActivityDetailModalSheet(
            sheetState = sheetState,
            nowEpochMillis = uiState.nowEpochMillis,
            onDismiss = viewModel::dismissDetailSheet,
            onRetryLoad = viewModel::retryDetailSheet,
            onOpenPrimaryTarget = {
                val target = sheetState.detail?.item ?: sheetState.item
                viewModel.dismissDetailSheet()
                handleOpenActivityPrimaryTarget(target)
            },
            onOpenUrl = openAnyUrl,
        )
    }
}

@Composable
private fun ActivityTopBar(
    counts: ActivityCounts,
    isRefreshing: Boolean,
    onBack: (() -> Unit)?,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseGlassSurface(showBottomBorder = true)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (onBack == null) 8.dp else 0.dp),
        ) {
            Text(
                text = "活动",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (counts.all > 0) {
                    "共 ${counts.all} 场社区活动 · ${counts.ongoing} 场进行中"
                } else {
                    "社区抽奖、福利赠送与限时活动聚合"
                },
                style = MaterialTheme.typography.bodySmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(
            onClick = onRefresh,
            enabled = !isRefreshing,
        ) {
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "刷新活动列表",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ActivityStatusFilterRow(
    selectedFilter: ActivityStatusFilter,
    counts: ActivityCounts,
    onSelectFilter: (ActivityStatusFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActivityStatusFilter.entries.forEach { filter ->
            val count = when (filter) {
                ActivityStatusFilter.ALL -> counts.all
                ActivityStatusFilter.ONGOING -> counts.ongoing
                ActivityStatusFilter.UPCOMING -> counts.upcoming
                ActivityStatusFilter.ENDED -> counts.ended
            }
            val selected = selectedFilter == filter
            FilterChip(
                selected = selected,
                onClick = { onSelectFilter(filter) },
                shape = CircleShape,
                label = {
                    Text(
                        text = if (count > 0) "${filter.label} $count" else filter.label,
                        style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    )
                },
            )
        }
    }
}

@Composable
private fun ActivityInlineErrorBanner(
    message: String,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        FilledTonalButton(
            onClick = onRetry,
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                text = "重试",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ActivityLoadingSkeletonCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.ElevatedTile,
                elevation = PulseElevation.Card,
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PulseShimmerBox(modifier = Modifier.size(width = 64.dp, height = 22.dp), shape = CircleShape)
            PulseShimmerBox(modifier = Modifier.size(width = 56.dp, height = 22.dp), shape = CircleShape)
        }
        PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.88f).height(20.dp))
        PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.68f).height(16.dp))
        PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.45f).height(14.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityFeedCard(
    item: ForumWatchActivityItem,
    nowEpochMillis: Long,
    onOpenPrimaryTarget: () -> Unit,
    onOpenDetail: () -> Unit,
    onOpenLuckyUrl: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val liveStatus = item.resolveLiveStatus(nowEpochMillis)
    val isEnded = liveStatus == ActivityTimeStatus.ENDED
    val accentColor = when {
        item.pinned -> MaterialTheme.colorScheme.primary
        liveStatus == ActivityTimeStatus.ONGOING -> MaterialTheme.colorScheme.primary
        liveStatus == ActivityTimeStatus.UPCOMING -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.ElevatedTile,
                elevation = if (item.pinned) PulseElevation.CardElevated else PulseElevation.Card,
                accentColor = accentColor,
            )
            .pulseBouncyClickable(
                role = Role.Button,
                onClickLabel = "打开活动：${item.title}",
                onClick = onOpenPrimaryTarget,
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (item.pinned) {
                PulseAccentPill(
                    text = "置顶",
                    icon = Icons.Rounded.PushPin,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
            ActivityStatusBadge(status = liveStatus)
            PulseAccentPill(
                text = item.kind.displayLabel,
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.78f),
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (item.category.isNotBlank()) {
                PulseAccentPill(
                    text = item.category,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (isEnded) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        if (item.summary.isNotBlank()) {
            Text(
                text = item.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val hasStructuredMeta = !item.prize.isNullOrBlank() ||
            !item.participation.isNullOrBlank() ||
            item.drawFloor != null ||
            item.startsAtEpochMillis != null ||
            item.endsAtEpochMillis != null

        if (hasStructuredMeta) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                item.prize?.takeIf { it.isNotBlank() }?.let { prize ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CardGiftcard,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = "奖品：$prize",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                val participationLine = buildString {
                    item.participation?.takeIf { it.isNotBlank() }?.let { append(it) }
                    item.drawFloor?.takeIf { it > 0 }?.let { floor ->
                        if (isNotEmpty()) append(" · ")
                        append("开奖楼层 #$floor")
                    }
                }
                if (participationLine.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.HowToVote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = participationLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                formatActivityTimingLabel(item, nowEpochMillis)?.let { timeLabel ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Schedule,
                            contentDescription = null,
                            tint = if (liveStatus == ActivityTimeStatus.ONGOING) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = timeLabel,
                            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                            color = if (liveStatus == ActivityTimeStatus.ONGOING) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        PulseGradientDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val organizerLabel = buildString {
                if (item.organizer.isNotBlank()) {
                    append("@")
                    append(item.organizer)
                } else if (item.sourceId.isNotBlank()) {
                    append(item.sourceId)
                } else {
                    append("社区活动")
                }
            }
            Text(
                text = organizerLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onOpenLuckyUrl != null) {
                    OutlinedButton(
                        onClick = onOpenLuckyUrl,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "开奖页",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }

                FilledTonalButton(
                    onClick = onOpenDetail,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Description,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "详情",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityStatusBadge(
    status: ActivityTimeStatus,
) {
    val (bg, fg) = when (status) {
        ActivityTimeStatus.ONGOING ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ActivityTimeStatus.UPCOMING ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        ActivityTimeStatus.ENDED ->
            MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    PulseAccentPill(
        text = status.displayLabel,
        containerColor = bg,
        contentColor = fg,
    )
}

@Composable
private fun ActivityEmptyOrErrorCard(
    selectedFilter: ActivityStatusFilter,
    totalCount: Int,
    errorMessage: String?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onResetFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark
    val primary = MaterialTheme.colorScheme.primary
    val isFilteredEmpty = errorMessage == null && totalCount > 0 && selectedFilter != ActivityStatusFilter.ALL

    Column(
        modifier = modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .pulseHeroCard(
                accentColor = primary,
                shape = PulseArchitecturalShapes.HeroBento,
                elevation = PulseElevation.CardElevated,
            )
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(PulseBrushes.brandSoftWash(alpha = if (isDark) 0.24f else 0.14f))
                .border(
                    width = 1.dp,
                    color = primary.copy(alpha = if (isDark) 0.36f else 0.22f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (errorMessage != null) Icons.Rounded.WarningAmber else Icons.Rounded.Celebration,
                contentDescription = null,
                tint = if (errorMessage != null) MaterialTheme.colorScheme.error else primary,
                modifier = Modifier.size(28.dp),
            )
        }

        Text(
            text = when {
                errorMessage != null -> "活动列表加载失败"
                isFilteredEmpty -> "暂无「${selectedFilter.label}」状态的活动"
                else -> "当前暂无正在收录的社区活动"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Text(
            text = when {
                errorMessage != null -> errorMessage
                isFilteredEmpty -> "当前共有 $totalCount 场其他状态的社区活动，可切换回全部查看。"
                else -> "ForumWatch 正在持续巡检社区抽奖、福利赠送与节日互动帖，你也可以打开上方「新活动通知」第一时间接收提醒。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (isFilteredEmpty) {
            FilledTonalButton(
                onClick = onResetFilter,
                shape = CircleShape,
            ) {
                Text(
                    text = "查看全部活动",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else {
            FilledTonalButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                shape = CircleShape,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isRefreshing) "正在同步…" else "立即刷新",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ActivityDetailModalSheet(
    sheetState: ActivityDetailSheetState,
    nowEpochMillis: Long,
    onDismiss: () -> Unit,
    onRetryLoad: () -> Unit,
    onOpenPrimaryTarget: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val modalState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val item = sheetState.detail?.item ?: sheetState.item
    val liveStatus = item.resolveLiveStatus(nowEpochMillis)
    val markdownContent = sheetState.detail?.bodyMarkdown?.takeIf { it.isNotBlank() }
        ?: item.summary

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = modalState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ActivityStatusBadge(status = liveStatus)
                PulseAccentPill(
                    text = item.kind.displayLabel,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (item.organizer.isNotBlank()) {
                    PulseAccentPill(
                        text = "@${item.organizer}",
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            formatActivityTimingLabel(item, nowEpochMillis)?.let { timing ->
                Text(
                    text = timing,
                    style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            PulseGradientDivider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (sheetState.isLoading && sheetState.detail == null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = "正在加载完整活动正文…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (!sheetState.errorMessage.isNullOrBlank() && sheetState.detail == null) {
                    ActivityInlineErrorBanner(
                        message = sheetState.errorMessage,
                        onRetry = onRetryLoad,
                    )
                }

                if (markdownContent.isNotBlank()) {
                    MarkdownRenderer(
                        markdown = markdownContent,
                        bodyStyle = MaterialTheme.typography.bodyMedium,
                        onUrlClick = onOpenUrl,
                    )
                }

                val links = sheetState.detail?.externalLinks.orEmpty()
                if (links.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "相关链接",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            links.forEachIndexed { idx, link ->
                                OutlinedButton(
                                    onClick = { onOpenUrl(link.url) },
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = link.text.ifBlank { "活动链接 ${idx + 1}" },
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(13.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item.luckyUrl?.takeIf { it.isNotBlank() }?.let { luckyUrl ->
                    OutlinedButton(
                        onClick = { onOpenUrl(luckyUrl) },
                        modifier = Modifier.weight(1f),
                        shape = CircleShape,
                    ) {
                        Text(
                            text = "打开开奖页",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                if (item.resolvedNodeSeekTopicId() != null || item.sourceUrl.isNotBlank()) {
                    Button(
                        onClick = onOpenPrimaryTarget,
                        modifier = Modifier.weight(1f),
                        shape = CircleShape,
                    ) {
                        Text(
                            text = if (item.resolvedNodeSeekTopicId() != null) "前往原帖参与" else "打开活动来源",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

private val ACTIVITY_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm")

internal fun formatActivityTimingLabel(
    item: ForumWatchActivityItem,
    nowEpochMillis: Long,
): String? {
    val startsAt = item.startsAtEpochMillis?.takeIf { it > 0L }
    val endsAt = item.endsAtEpochMillis?.takeIf { it > 0L }
    val liveStatus = item.resolveLiveStatus(nowEpochMillis)

    return when (liveStatus) {
        ActivityTimeStatus.UPCOMING -> {
            if (startsAt != null) {
                val delta = (startsAt - nowEpochMillis).coerceAtLeast(0L)
                "距开始 ${formatRelativeDurationCompact(delta)}（${formatLocalMonthDayTime(startsAt)}）"
            } else {
                null
            }
        }

        ActivityTimeStatus.ONGOING -> {
            if (endsAt != null && endsAt > nowEpochMillis) {
                val delta = endsAt - nowEpochMillis
                "距截止 ${formatRelativeDurationCompact(delta)}（${formatLocalMonthDayTime(endsAt)}）"
            } else if (startsAt != null) {
                "开始于 ${formatLocalMonthDayTime(startsAt)}"
            } else {
                null
            }
        }

        ActivityTimeStatus.ENDED -> {
            if (endsAt != null && endsAt <= nowEpochMillis) {
                "已于 ${formatLocalMonthDayTime(endsAt)} 结束"
            } else {
                "活动已结束"
            }
        }
    }
}

private fun formatRelativeDurationCompact(durationMs: Long): String {
    val totalMinutes = (durationMs / 60_000L).coerceAtLeast(1L)
    val days = totalMinutes / (60L * 24L)
    val hours = (totalMinutes % (60L * 24L)) / 60L
    val minutes = totalMinutes % 60L
    return when {
        days > 0L -> "${days}天${hours}小时"
        hours > 0L -> "${hours}小时${minutes}分"
        else -> "${minutes}分钟"
    }
}

private fun formatLocalMonthDayTime(epochMillis: Long): String =
    runCatching {
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(ACTIVITY_TIME_FORMATTER)
    }.getOrDefault("")

@Preview(showBackground = true, widthDp = 412, heightDp = 840)
@Composable
private fun ActivityScreenLightPreview() {
    NodePulseTheme(themeMode = PulseThemeMode.PAPER_LIGHT) {
        val repo = remember { PreviewForumWatchActivityRepository() }
        ActivityScreen(repository = repo, onBack = {})
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 840)
@Composable
private fun ActivityScreenDarkPreview() {
    NodePulseTheme(themeMode = PulseThemeMode.OBSIDIAN_DARK) {
        val repo = remember { PreviewForumWatchActivityRepository() }
        ActivityScreen(repository = repo, onBack = {}, notificationsEnabled = true)
    }
}
