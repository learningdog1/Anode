package com.nodeseek.app.ui.activity

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.activity.ActivityCounts
import com.nodeseek.app.domain.activity.ActivityRepository
import com.nodeseek.app.domain.activity.ActivityTimeStatus
import com.nodeseek.app.domain.activity.ForumWatchActivityItem
import com.nodeseek.app.domain.activity.LuckyDrawSpec
import com.nodeseek.app.navigation.openNodeSeekOrExternalUrl
import com.nodeseek.app.ui.components.MarkdownRenderer
import com.nodeseek.app.ui.theme.NodePulseMotion
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
 * 「活动」页面：接入 ForumWatch 社区活动与福利聚合 API，支持两级分类/抽奖开奖状态筛选、下拉刷新、
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
    val context = LocalContext.current
    var activeLuckyDrawSpec by remember { mutableStateOf<LuckyDrawSpec?>(null) }

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
                    onOpenLuckyDraw = { deepLink ->
                        activeLuckyDrawSpec = deepLink.toSpec()
                    },
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
                        start = 12.dp,
                        end = 12.dp,
                        top = 8.dp,
                        bottom = 20.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
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
                        ActivityFilterBar(
                            selectedCategory = uiState.selectedCategory,
                            selectedLotteryStatus = uiState.selectedLotteryStatus,
                            showLotteryStatusFilters = uiState.showLotteryStatusFilters,
                            counts = uiState.counts,
                            onSelectCategory = viewModel::selectCategory,
                            onSelectLotteryStatus = viewModel::selectLotteryStatus,
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
                                        selectedCategory = uiState.selectedCategory,
                                        selectedLotteryStatus = uiState.selectedLotteryStatus,
                                        counts = uiState.counts,
                                        errorMessage = uiState.errorMessage,
                                        isRefreshing = uiState.isRefreshing,
                                        onRefresh = viewModel::refresh,
                                        onResetLotteryStatus = {
                                            viewModel.selectLotteryStatus(LotteryStatusFilter.ALL)
                                        },
                                        onResetAllFilters = viewModel::resetFilters,
                                    )
                                }
                            }
                        }

                        else -> {
                            items(
                                items = uiState.filteredActivities,
                                key = { it.id },
                            ) { item ->
                                val explicitLotteryUrl = item.resolvedLotteryUrl
                                ActivityFeedCard(
                                    item = item,
                                    nowEpochMillis = uiState.nowEpochMillis,
                                    onOpenPrimaryTarget = { handleOpenActivityPrimaryTarget(item) },
                                    onOpenDetail = { viewModel.openDetailSheet(item) },
                                    onOpenLuckyUrl = explicitLotteryUrl?.let { url ->
                                        { openAnyUrl(url) }
                                    },
                                )
                            }

                            if (uiState.hasMore) {
                                item(key = "activity_load_more_footer") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (uiState.isLoadingMore) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(22.dp),
                                                strokeWidth = 2.2.dp,
                                            )
                                        } else {
                                            FilledTonalButton(
                                                onClick = viewModel::loadNextPage,
                                                shape = CircleShape,
                                            ) {
                                                Text(
                                                    text = if (!uiState.errorMessage.isNullOrBlank()) {
                                                        "加载更多失败，点击重试"
                                                    } else {
                                                        "加载更多活动"
                                                    },
                                                    style = MaterialTheme.typography.labelLarge,
                                                )
                                            }
                                        }
                                    }
                                }
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

    activeLuckyDrawSpec?.let { luckySpec ->
        val appContainer = LocalAppContainer.current
        val viewerSession by appContainer.sessionRepository.sessionState.collectAsStateWithLifecycle()
        LuckyDrawSheet(
            spec = luckySpec,
            initialMode = LuckyDrawSheetMode.VERIFY,
            allowGeneratorMode = false,
            isViewerTopicAuthor = false,
            viewerUid = viewerSession?.uid,
            luckyDrawRepository = appContainer.luckyDrawRepository,
            forumRepository = appContainer.forumRepository,
            onDismiss = { activeLuckyDrawSpec = null },
            onFeedbackMessage = { msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            },
            onOpenTopicFloor = onOpenTopic?.let { open ->
                { topicId, floor ->
                    activeLuckyDrawSpec = null
                    open(topicId, floor, "")
                }
            },
            onOpenUser = onOpenUser?.let { open ->
                { uid, username ->
                    activeLuckyDrawSpec = null
                    open(uid, username)
                }
            },
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
                text = when {
                    counts.all > 0 && counts.lottery > 0 && counts.ongoing > 0 ->
                        "共 ${counts.all} 条活动 · ${counts.lottery} 场抽奖（${counts.ongoing} 进行中）"
                    counts.all > 0 && counts.lottery > 0 && counts.upcoming > 0 ->
                        "共 ${counts.all} 条活动 · ${counts.lottery} 场抽奖（${counts.upcoming} 即将开始）"
                    counts.all > 0 && counts.lottery > 0 ->
                        "共 ${counts.all} 条活动 · ${counts.lottery} 场抽奖"
                    counts.all > 0 ->
                        "共 ${counts.all} 条社区福利与活动"
                    else ->
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
private fun ActivityFilterBar(
    selectedCategory: ActivityCategoryFilter,
    selectedLotteryStatus: LotteryStatusFilter,
    showLotteryStatusFilters: Boolean,
    counts: ActivityCounts,
    onSelectCategory: (ActivityCategoryFilter) -> Unit,
    onSelectLotteryStatus: (LotteryStatusFilter) -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Tier 1: Category tabs (全部 / 抽奖 / 其他活动)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActivityCategoryFilter.entries.forEach { category ->
                val count = when (category) {
                    ActivityCategoryFilter.ALL -> counts.all
                    ActivityCategoryFilter.LOTTERY -> counts.lottery
                    ActivityCategoryFilter.OTHER -> counts.other
                }
                val selected = selectedCategory == category
                val bgColor = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                }
                val fgColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                val borderColor = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                } else {
                    ext.cardBorder
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(bgColor)
                        .border(0.75.dp, borderColor, CircleShape)
                        .pulseBouncyClickable(
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.Tab,
                            onClick = { onSelectCategory(category) },
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (count > 0) "${category.label} $count" else category.label,
                        style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = fgColor,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        // Tier 2: Draw-status chips — visible ONLY when 「抽奖」 category is selected
        AnimatedVisibility(
            visible = showLotteryStatusFilters,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            val subBarShape = RoundedCornerShape(12.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(subBarShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(0.75.dp, ext.cardBorder, subBarShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "开奖状态",
                    style = NodePulseThemeTokens.extraTypography.microLabel,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LotteryStatusFilter.entries.forEach { statusFilter ->
                        val count = when (statusFilter) {
                            LotteryStatusFilter.ALL -> counts.lottery
                            LotteryStatusFilter.ONGOING -> counts.ongoing
                            LotteryStatusFilter.UPCOMING -> counts.upcoming
                            LotteryStatusFilter.ENDED -> counts.ended
                        }
                        val selected = selectedLotteryStatus == statusFilter
                        val (bgColor, fgColor, borderColor) = if (selected) {
                            when (statusFilter) {
                                LotteryStatusFilter.UPCOMING -> Triple(
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                    MaterialTheme.colorScheme.onTertiaryContainer,
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f),
                                )
                                LotteryStatusFilter.ENDED -> Triple(
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                    MaterialTheme.colorScheme.onSurface,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                )
                                LotteryStatusFilter.ALL,
                                LotteryStatusFilter.ONGOING,
                                -> Triple(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.onPrimaryContainer,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                                )
                            }
                        } else {
                            Triple(
                                MaterialTheme.colorScheme.surface,
                                MaterialTheme.colorScheme.onSurfaceVariant,
                                ext.cardBorder,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(bgColor)
                                .border(0.75.dp, borderColor, CircleShape)
                                .pulseBouncyClickable(
                                    pressedScale = NodePulseMotion.chipPressScale,
                                    role = Role.Tab,
                                    onClick = { onSelectLotteryStatus(statusFilter) },
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (count > 0) "${statusFilter.label} $count" else statusFilter.label,
                                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = fgColor,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
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
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(16.dp),
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
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
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
                shape = PulseArchitecturalShapes.EditorialCard,
                elevation = PulseElevation.Card,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PulseShimmerBox(modifier = Modifier.size(width = 48.dp, height = 18.dp), shape = CircleShape)
            PulseShimmerBox(modifier = Modifier.size(width = 44.dp, height = 18.dp), shape = CircleShape)
        }
        PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.88f).height(16.dp))
        PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.55f).height(13.dp))
    }
}

@Composable
private fun ActivityFeedCard(
    item: ForumWatchActivityItem,
    nowEpochMillis: Long,
    onOpenPrimaryTarget: () -> Unit,
    onOpenDetail: () -> Unit,
    onOpenLuckyUrl: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val lotteryStatus = item.resolveLotteryStatusOrNull(nowEpochMillis)
    val isEnded = lotteryStatus == ActivityTimeStatus.ENDED
    val compactTimingLabel = remember(item, lotteryStatus, nowEpochMillis) {
        formatLotteryTimingLabel(
            item = item,
            lotteryStatus = lotteryStatus,
            nowEpochMillis = nowEpochMillis,
            compact = true,
        )
    }
    val accentColor = when {
        item.pinned -> MaterialTheme.colorScheme.primary
        lotteryStatus == ActivityTimeStatus.ONGOING -> MaterialTheme.colorScheme.primary
        lotteryStatus == ActivityTimeStatus.UPCOMING -> MaterialTheme.colorScheme.tertiary
        lotteryStatus == ActivityTimeStatus.ENDED -> MaterialTheme.colorScheme.outlineVariant
        else -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.65f)
    }

    val organizerLabel = when {
        item.organizer.isNotBlank() -> "@${item.organizer}"
        item.sourceId.isNotBlank() -> item.sourceId
        else -> ""
    }

    val hasPrizeMeta = !item.prize.isNullOrBlank()
    val inlineMetaLine = buildString {
        compactTimingLabel?.let { append(it) }
        item.prize?.takeIf { it.isNotBlank() }?.let {
            if (isNotEmpty()) append(" · ")
            append("奖品：$it")
        }
        item.drawFloor?.takeIf { it > 0 }?.let { floor ->
            if (isNotEmpty()) append(" · ")
            append("开奖 #$floor")
        }
        if (isEmpty()) {
            item.participation?.takeIf { it.isNotBlank() }?.let { append(it) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                elevation = if (item.pinned) PulseElevation.CardElevated else PulseElevation.Card,
                accentColor = accentColor,
            )
            .pulseBouncyClickable(
                role = Role.Button,
                onClickLabel = "打开活动：${item.title}",
                onClick = onOpenPrimaryTarget,
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // Row 1: Compact tags on left + organizer on right
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (item.pinned) {
                    ActivityCompactTag(
                        text = "置顶",
                        icon = Icons.Rounded.PushPin,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                if (lotteryStatus != null) {
                    ActivityStatusBadge(status = lotteryStatus, compact = true)
                } else {
                    ActivityCompactTag(
                        text = item.effectiveKind.displayLabel,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.78f),
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (item.category.isNotBlank()) {
                    ActivityCompactTag(
                        text = item.category,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (organizerLabel.isNotEmpty()) {
                Text(
                    text = organizerLabel,
                    style = NodePulseThemeTokens.extraTypography.microLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .widthIn(max = 120.dp),
                )
            }
        }

        // Row 2: Title
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (isEnded) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // Row 3: Compact 1-line summary
        if (item.summary.isNotBlank()) {
            Text(
                text = item.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Row 4: Inline draw-time / prize meta + compact action chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (inlineMetaLine.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (compactTimingLabel != null && !hasPrizeMeta) {
                            Icons.Rounded.Schedule
                        } else {
                            Icons.Rounded.CardGiftcard
                        },
                        contentDescription = null,
                        tint = if (isEnded) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = inlineMetaLine,
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (onOpenLuckyUrl != null) {
                    ActivityCompactActionChip(
                        text = "开奖页",
                        trailingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.primary,
                        outlined = true,
                        onClick = onOpenLuckyUrl,
                    )
                }
                ActivityCompactActionChip(
                    text = "详情",
                    leadingIcon = Icons.Rounded.Description,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    outlined = false,
                    onClick = onOpenDetail,
                )
            }
        }
    }
}

@Composable
private fun ActivityCompactTag(
    text: String,
    containerColor: Color,
    contentColor: Color,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(containerColor)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(11.dp),
            )
        }
        Text(
            text = text,
            style = NodePulseThemeTokens.extraTypography.microLabel,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun ActivityCompactActionChip(
    text: String,
    containerColor: Color,
    contentColor: Color,
    outlined: Boolean,
    onClick: () -> Unit,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(containerColor)
            .then(
                if (outlined) {
                    Modifier.border(0.75.dp, ext.cardBorder, CircleShape)
                } else {
                    Modifier
                },
            )
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            text = text,
            style = NodePulseThemeTokens.extraTypography.microLabel,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
        )
        if (trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

@Composable
private fun ActivityStatusBadge(
    status: ActivityTimeStatus,
    compact: Boolean = false,
) {
    val (bg, fg) = when (status) {
        ActivityTimeStatus.ONGOING ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ActivityTimeStatus.UPCOMING ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        ActivityTimeStatus.ENDED ->
            MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = "抽奖 · ${status.displayLabel}"
    if (compact) {
        ActivityCompactTag(
            text = label,
            containerColor = bg,
            contentColor = fg,
        )
    } else {
        PulseAccentPill(
            text = label,
            containerColor = bg,
            contentColor = fg,
        )
    }
}

@Composable
private fun ActivityEmptyOrErrorCard(
    selectedCategory: ActivityCategoryFilter,
    selectedLotteryStatus: LotteryStatusFilter,
    counts: ActivityCounts,
    errorMessage: String?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onResetLotteryStatus: () -> Unit,
    onResetAllFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark
    val primary = MaterialTheme.colorScheme.primary
    val isFilteredEmpty = errorMessage == null &&
        counts.all > 0 &&
        (selectedCategory != ActivityCategoryFilter.ALL || selectedLotteryStatus != LotteryStatusFilter.ALL)
    val canResetToAllLotteries = isFilteredEmpty &&
        selectedCategory == ActivityCategoryFilter.LOTTERY &&
        selectedLotteryStatus != LotteryStatusFilter.ALL &&
        counts.lottery > 0

    val headline = when {
        errorMessage != null -> "活动列表加载失败"
        isFilteredEmpty && selectedCategory == ActivityCategoryFilter.LOTTERY &&
            selectedLotteryStatus != LotteryStatusFilter.ALL ->
            "暂无「抽奖 · ${selectedLotteryStatus.label}」活动"
        isFilteredEmpty && selectedCategory == ActivityCategoryFilter.LOTTERY ->
            "当前暂无带明确抽奖链接的抽奖"
        isFilteredEmpty && selectedCategory == ActivityCategoryFilter.OTHER ->
            "暂无其他社区活动"
        else -> "当前暂无正在收录的社区活动"
    }

    val description = when {
        errorMessage != null -> errorMessage
        canResetToAllLotteries ->
            "当前共有 ${counts.lottery} 场其他开奖状态的抽奖活动，可切换查看全部抽奖。"
        isFilteredEmpty && selectedCategory == ActivityCategoryFilter.LOTTERY ->
            "仅正文或链接包含明确抽奖/开奖地址的帖子归入「抽奖」分类；当前共有 ${counts.all} 条社区活动可查看。"
        isFilteredEmpty ->
            "当前共有 ${counts.all} 条其他分类的社区活动，可切换回全部查看。"
        else ->
            "ForumWatch 正在持续巡检社区抽奖、福利赠送与节日互动帖，你也可以打开上方「新活动通知」第一时间接收提醒。"
    }

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
            text = headline,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (canResetToAllLotteries) {
            FilledTonalButton(
                onClick = onResetLotteryStatus,
                shape = CircleShape,
            ) {
                Text(
                    text = "查看全部抽奖",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else if (isFilteredEmpty) {
            FilledTonalButton(
                onClick = onResetAllFilters,
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
    val lotteryStatus = item.resolveLotteryStatusOrNull(nowEpochMillis)
    val explicitLotteryUrl = item.resolvedLotteryUrl
    val detailedTimingLabel = remember(item, lotteryStatus, nowEpochMillis) {
        formatLotteryTimingLabel(
            item = item,
            lotteryStatus = lotteryStatus,
            nowEpochMillis = nowEpochMillis,
            compact = false,
        )
    }
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
                if (lotteryStatus != null) {
                    ActivityStatusBadge(status = lotteryStatus)
                } else {
                    PulseAccentPill(
                        text = item.effectiveKind.displayLabel,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
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

            detailedTimingLabel?.let { timing ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Schedule,
                        contentDescription = null,
                        tint = if (lotteryStatus == ActivityTimeStatus.ENDED) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = timing,
                        style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                        fontWeight = FontWeight.SemiBold,
                        color = if (lotteryStatus == ActivityTimeStatus.ENDED) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
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
                explicitLotteryUrl?.let { luckyUrl ->
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

private val LOTTERY_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * Formats start/draw timing hints exclusively for explicit-link lotteries (`lotteryStatus != null`);
 * returns `null` for non-lottery activities.
 */
internal fun formatLotteryTimingLabel(
    item: ForumWatchActivityItem,
    lotteryStatus: ActivityTimeStatus?,
    nowEpochMillis: Long,
    compact: Boolean,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String? {
    if (lotteryStatus == null) return null
    val startsAt = item.startsAtEpochMillis?.takeIf { it > 0L }
    val drawAt = item.resolveDrawTimeEpochMillis()

    return when (lotteryStatus) {
        ActivityTimeStatus.UPCOMING -> {
            if (startsAt != null && startsAt > nowEpochMillis) {
                if (compact) {
                    "开始 ${formatLocalMonthDayTime(startsAt, zoneId)}"
                } else {
                    val delta = (startsAt - nowEpochMillis).coerceAtLeast(0L)
                    buildString {
                        append("距开始 ${formatRelativeDurationCompact(delta)}（${formatLocalMonthDayTime(startsAt, zoneId)}）")
                        if (drawAt != null) {
                            append(" · 开奖 ${formatLocalMonthDayTime(drawAt, zoneId)}")
                        }
                    }
                }
            } else if (drawAt != null) {
                "开奖 ${formatLocalMonthDayTime(drawAt, zoneId)}"
            } else {
                null
            }
        }

        ActivityTimeStatus.ONGOING -> {
            if (drawAt != null && drawAt > nowEpochMillis) {
                if (compact) {
                    "开奖 ${formatLocalMonthDayTime(drawAt, zoneId)}"
                } else {
                    val delta = drawAt - nowEpochMillis
                    "距开奖 ${formatRelativeDurationCompact(delta)}（${formatLocalMonthDayTime(drawAt, zoneId)}）"
                }
            } else {
                null
            }
        }

        ActivityTimeStatus.ENDED -> {
            if (drawAt != null && drawAt <= nowEpochMillis) {
                if (compact) {
                    "已开奖 ${formatLocalMonthDayTime(drawAt, zoneId)}"
                } else {
                    "已于 ${formatLocalMonthDayTime(drawAt, zoneId)} 开奖"
                }
            } else {
                if (compact) "已开奖" else "抽奖已结束"
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

private fun formatLocalMonthDayTime(
    epochMillis: Long,
    zoneId: ZoneId,
): String =
    runCatching {
        Instant.ofEpochMilli(epochMillis)
            .atZone(zoneId)
            .format(LOTTERY_TIME_FORMATTER)
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
