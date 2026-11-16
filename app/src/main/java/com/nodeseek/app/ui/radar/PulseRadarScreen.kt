package com.nodeseek.app.ui.radar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.domain.radar.KeywordWatchRule
import com.nodeseek.app.domain.radar.LocalAlertKind
import com.nodeseek.app.domain.radar.LocalAlertRecord
import com.nodeseek.app.domain.radar.RadarPollingSettings
import com.nodeseek.app.domain.radar.RadarRuntimeStatus
import com.nodeseek.app.domain.radar.TrackedTopic
import com.nodeseek.app.ui.components.CompactCountBadge
import com.nodeseek.app.ui.components.PulseConfirmDialog
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.RelativeTimeText
import com.nodeseek.app.ui.components.formatBadgeCount
import com.nodeseek.app.ui.theme.CategoryPalette
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseStaggeredEntrance
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.pulseHeroCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulseRadarScreen(
    onBack: () -> Unit,
    onOpenTopic: (topicId: Long, initialFloor: Int, titleHint: String) -> Unit,
    modifier: Modifier = Modifier,
    initialTab: Int = 0,
    container: AppContainer = LocalAppContainer.current,
    previewUiState: PulseRadarUiState? = null,
) {
    val viewModel: PulseRadarViewModel = viewModel(
        factory = PulseRadarViewModel.factory(container),
    )
    val observedUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = previewUiState ?: observedUiState
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val pagerState = rememberPagerState(
        initialPage = initialTab.coerceIn(0, 2),
        pageCount = { 3 },
    )
    var showAddRuleSheet by rememberSaveable { mutableStateOf(false) }
    var confirmClearAllDialog by rememberSaveable { mutableStateOf(false) }
    var environmentExpanded by rememberSaveable {
        mutableStateOf(
            !uiState.environmentDiagnostics.hasNotificationPermission ||
                uiState.environmentDiagnostics.isSystemDoNotDisturbActive,
        )
    }

    LaunchedEffect(initialTab) {
        val target = initialTab.coerceIn(0, 2)
        if (pagerState.currentPage != target) {
            pagerState.animateScrollToPage(target)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshEnvironmentDiagnostics()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.refreshEnvironmentDiagnostics()
        if (!granted) {
            openAppNotificationSettings(context)
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("系统通知权限已授予")
            }
        }
    }

    val handleNotificationPermissionClick: () -> Unit = {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !uiState.environmentDiagnostics.hasNotificationPermission
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openAppNotificationSettings(context)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            PulseRadarTopBar(
                runtimeStatus = uiState.runtimeStatus,
                pollingSettings = uiState.pollingSettings,
                unreadCount = uiState.unreadAlertCount,
                onBack = onBack,
                onTriggerImmediateCheck = viewModel::triggerImmediateCheck,
                onOpenAddRule = {
                    viewModel.clearAddRuleError()
                    showAddRuleSheet = true
                },
            )
        },
        snackbarHost = {
            PulseSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
        ) {
            RadarEnvironmentBanner(
                pollingSettings = uiState.pollingSettings,
                runtimeStatus = uiState.runtimeStatus,
                diagnostics = uiState.environmentDiagnostics,
                expanded = environmentExpanded,
                onToggleExpanded = { environmentExpanded = !environmentExpanded },
                onRequestNotificationPermission = handleNotificationPermissionClick,
                onOpenBatteryOptimization = { openBatteryOptimizationSettings(context) },
                onOpenDoNotDisturb = { openDoNotDisturbSettings(context) },
                onTriggerImmediateCheck = viewModel::triggerImmediateCheck,
                onSetMasterEnabled = viewModel::setMasterEnabled,
                onSetKeywordAlertsEnabled = viewModel::setKeywordAlertsEnabled,
                onSetReplyTrackingEnabled = viewModel::setReplyTrackingEnabled,
                onSetForegroundInterval = viewModel::setForegroundIntervalMinutes,
                onSetBackgroundInterval = viewModel::setBackgroundIntervalMinutes,
                onSetWifiOnly = viewModel::setWifiOnlyInBackground,
                onSetQuietHours = viewModel::setQuietHoursEnabled,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            RadarSegmentedTabs(
                selectedTab = pagerState.currentPage,
                unreadAlertCount = uiState.unreadAlertCount,
                activeRulesCount = uiState.keywordRules.count { it.isEnabled },
                trackedCount = uiState.trackedTopics.size,
                onSelectTab = { tab ->
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(tab)
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                when (page) {
                    0 -> AlertsFeedTabPage(
                        uiState = uiState,
                        onSelectFilter = viewModel::selectFilter,
                        onOpenAlert = { record ->
                            viewModel.onOpenAlertRecord(record)
                            onOpenTopic(
                                record.topicId,
                                record.targetFloor.coerceAtLeast(0),
                                record.topicTitle,
                            )
                        },
                        onToggleRead = { record ->
                            viewModel.markAlertRead(record.id, !record.isRead)
                        },
                        onDeleteAlert = { alertId ->
                            viewModel.deleteAlert(alertId)
                        },
                        onMarkAllRead = viewModel::markAllAlertsRead,
                        onClearRead = viewModel::clearReadAlerts,
                        onRequestClearAll = { confirmClearAllDialog = true },
                        onSwitchToKeywords = {
                            coroutineScope.launch { pagerState.animateScrollToPage(1) }
                        },
                    )

                    1 -> KeywordRulesTabPage(
                        rules = uiState.keywordRules,
                        onAddClick = {
                            viewModel.clearAddRuleError()
                            showAddRuleSheet = true
                        },
                        onToggleRule = viewModel::toggleKeywordRule,
                        onDeleteRule = viewModel::deleteKeywordRule,
                    )

                    else -> TrackedTopicsTabPage(
                        trackedTopics = uiState.trackedTopics,
                        onOpenTrackedTopic = { topic ->
                            onOpenTopic(
                                topic.topicId,
                                topic.lastKnownFloor.coerceAtLeast(0),
                                topic.title,
                            )
                        },
                        onMarkTopicAlertsRead = viewModel::markTopicAlertsRead,
                        onUntrack = viewModel::untrackTopic,
                    )
                }
            }
        }
    }

    if (showAddRuleSheet) {
        AddKeywordRuleBottomSheet(
            errorMessage = uiState.addRuleError,
            isSubmitting = uiState.isSubmittingRule,
            onClearError = viewModel::clearAddRuleError,
            onDismiss = { showAddRuleSheet = false },
            onSubmit = { keyword, excludeWords, categorySlug, matchMode ->
                viewModel.submitKeywordRule(
                    keyword = keyword,
                    excludeWords = excludeWords,
                    categorySlug = categorySlug,
                    matchMode = matchMode,
                    onSuccess = { showAddRuleSheet = false },
                )
            },
        )
    }

    if (confirmClearAllDialog) {
        PulseConfirmDialog(
            onDismissRequest = { confirmClearAllDialog = false },
            icon = Icons.Rounded.DeleteSweep,
            tone = PulseDialogTone.DESTRUCTIVE,
            title = "清空全部本地提醒？",
            message = "这将清除本地保存的所有关键词命中与追踪回复历史记录，已设置的关键词规则与追更帖子列表不会被删除。",
            confirmText = "确认清空",
            dismissText = "取消",
            onConfirm = {
                confirmClearAllDialog = false
                viewModel.clearAllAlerts()
            },
        )
    }
}

@Composable
private fun PulseRadarTopBar(
    runtimeStatus: RadarRuntimeStatus,
    pollingSettings: RadarPollingSettings,
    unreadCount: Int,
    onBack: () -> Unit,
    onTriggerImmediateCheck: () -> Unit,
    onOpenAddRule: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radarSpin")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "radarRefreshRotation",
    )

    val statusText = when {
        runtimeStatus.isPollingNow -> "正在即时巡检社区更新…"
        !pollingSettings.isEnabled -> "定时监控已暂停"
        runtimeStatus.isRunningInForeground -> "前台守望 · 每 ${pollingSettings.foregroundIntervalMinutes} 分钟"
        else -> "后台守望 · 每 ${pollingSettings.backgroundIntervalMinutes} 分钟"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseGlassSurface(
                showTopBorder = false,
                showBottomBorder = true,
                tonalAccent = MaterialTheme.colorScheme.primary,
            )
            .statusBarsPadding()
            .padding(start = 6.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                modifier = Modifier.size(22.dp),
            )
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
                    text = "脉搏雷达",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (unreadCount > 0) {
                    PulseAccentPill(
                        text = "${formatBadgeCount(unreadCount)} 未读",
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (runtimeStatus.lastCheckEpochMillis > 0L) {
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    RelativeTimeText(
                        isoTimestamp = runtimeStatus.lastCheckEpochMillis.toString(),
                        prefix = "上次 ",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        RadarTopCircleButton(
            icon = if (runtimeStatus.isPollingNow) Icons.Rounded.Refresh else Icons.Rounded.Radar,
            contentDescription = "立即检查一次",
            onClick = onTriggerImmediateCheck,
            tint = MaterialTheme.colorScheme.primary,
            iconSize = 18.dp,
            iconModifier = if (runtimeStatus.isPollingNow) {
                Modifier.rotate(spinAngle)
            } else {
                Modifier
            },
        )

        RadarTopCircleButton(
            icon = Icons.Rounded.Add,
            contentDescription = "新增关键词规则",
            onClick = onOpenAddRule,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.26f),
            iconSize = 19.dp,
        )
    }
}

@Composable
private fun RadarTopCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(
        alpha = if (NodePulseThemeTokens.isDark) 0.78f else 0.90f,
    ),
    borderColor: Color = NodePulseThemeTokens.extendedColors.glassBorder,
    iconSize: Dp = 18.dp,
    iconModifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Button,
                onClickLabel = contentDescription,
                showRipple = false,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(containerColor)
                .border(
                    width = 0.8.dp,
                    color = borderColor,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier
                    .size(iconSize)
                    .then(iconModifier),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RadarEnvironmentBanner(
    pollingSettings: RadarPollingSettings,
    runtimeStatus: RadarRuntimeStatus,
    diagnostics: RadarEnvironmentDiagnostics,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenBatteryOptimization: () -> Unit,
    onOpenDoNotDisturb: () -> Unit,
    onTriggerImmediateCheck: () -> Unit,
    onSetMasterEnabled: (Boolean) -> Unit,
    onSetKeywordAlertsEnabled: (Boolean) -> Unit,
    onSetReplyTrackingEnabled: (Boolean) -> Unit,
    onSetForegroundInterval: (Int) -> Unit,
    onSetBackgroundInterval: (Int) -> Unit,
    onSetWifiOnly: (Boolean) -> Unit,
    onSetQuietHours: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val attentionNeeded = !diagnostics.hasNotificationPermission || diagnostics.isSystemDoNotDisturbActive
    val accentColor = when {
        !diagnostics.hasNotificationPermission -> MaterialTheme.colorScheme.error
        diagnostics.isSystemDoNotDisturbActive -> ext.drumstickGoldEnd
        !pollingSettings.isEnabled -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseHeroCard(accentColor = accentColor)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PulseArchitecturalShapes.SmallChip)
                .clickable(onClick = onToggleExpanded),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (attentionNeeded) Icons.Rounded.WarningAmber else Icons.Rounded.Radar,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "运行机制与系统环境",
                    style = NodePulseThemeTokens.extraTypography.sectionHeader,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "定时检查在客户端前台运行或退至手机后台期间工作，完全退出应用后自动停止；通知送达与后台轮询会受到手机「勿扰模式」与「电池优化/后台省电策略」影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EnvironmentStatusPill(
                icon = if (diagnostics.hasNotificationPermission) Icons.Rounded.CheckCircle else Icons.Rounded.NotificationsOff,
                label = if (diagnostics.hasNotificationPermission) "通知权限已就绪" else "点击授予通知权限",
                isHealthy = diagnostics.hasNotificationPermission,
                isWarning = !diagnostics.hasNotificationPermission,
                onClick = onRequestNotificationPermission,
            )

            EnvironmentStatusPill(
                icon = Icons.Rounded.BatteryChargingFull,
                label = if (diagnostics.isIgnoringBatteryOptimizations) "电池优化已放行" else "省电策略受限 · 去放行",
                isHealthy = diagnostics.isIgnoringBatteryOptimizations,
                isWarning = !diagnostics.isIgnoringBatteryOptimizations,
                onClick = onOpenBatteryOptimization,
            )

            EnvironmentStatusPill(
                icon = Icons.Rounded.DoNotDisturbOn,
                label = if (diagnostics.isSystemDoNotDisturbActive) "勿扰模式开启中 · 通知可能静音" else "系统勿扰未开启",
                isHealthy = !diagnostics.isSystemDoNotDisturbActive,
                isWarning = diagnostics.isSystemDoNotDisturbActive,
                onClick = onOpenDoNotDisturb,
            )

            EnvironmentStatusPill(
                icon = if (runtimeStatus.isPollingNow) Icons.Rounded.Refresh else Icons.Rounded.Bolt,
                label = if (runtimeStatus.isPollingNow) "巡检中…" else "立即检查一次",
                isHealthy = true,
                isWarning = false,
                usePrimaryTint = true,
                onClick = onTriggerImmediateCheck,
            )
        }

        if (!runtimeStatus.lastErrorSummary.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                shape = PulseArchitecturalShapes.SmallChip,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "最近巡检提示：${runtimeStatus.lastErrorSummary}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                PulseGradientDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "雷达监控总开关",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                    Switch(
                        checked = pollingSettings.isEnabled,
                        onCheckedChange = onSetMasterEnabled,
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = pollingSettings.keywordAlertsEnabled,
                        onClick = { onSetKeywordAlertsEnabled(!pollingSettings.keywordAlertsEnabled) },
                        label = { Text("关键词命中提醒") },
                        enabled = pollingSettings.isEnabled,
                    )
                    FilterChip(
                        selected = pollingSettings.replyTrackingEnabled,
                        onClick = { onSetReplyTrackingEnabled(!pollingSettings.replyTrackingEnabled) },
                        label = { Text("关注帖新回复") },
                        enabled = pollingSettings.isEnabled,
                    )
                    FilterChip(
                        selected = pollingSettings.wifiOnlyInBackground,
                        onClick = { onSetWifiOnly(!pollingSettings.wifiOnlyInBackground) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Wifi,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                        },
                        label = { Text("后台仅 Wi-Fi") },
                        enabled = pollingSettings.isEnabled,
                    )
                    FilterChip(
                        selected = pollingSettings.quietHoursEnabled,
                        onClick = { onSetQuietHours(!pollingSettings.quietHoursEnabled) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.NightsStay,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                        },
                        label = { Text("夜间免打扰 23:00-07:00") },
                        enabled = pollingSettings.isEnabled,
                    )
                }

                IntervalChipStrip(
                    title = "前台巡检间隔",
                    optionsMinutes = listOf(1, 2, 5, 10),
                    selectedMinutes = pollingSettings.foregroundIntervalMinutes,
                    enabled = pollingSettings.isEnabled,
                    onSelect = onSetForegroundInterval,
                )

                IntervalChipStrip(
                    title = "后台巡检间隔",
                    optionsMinutes = listOf(3, 5, 10, 15),
                    selectedMinutes = pollingSettings.backgroundIntervalMinutes,
                    enabled = pollingSettings.isEnabled,
                    onSelect = onSetBackgroundInterval,
                )
            }
        }
    }
}

@Composable
private fun EnvironmentStatusPill(
    icon: ImageVector,
    label: String,
    isHealthy: Boolean,
    isWarning: Boolean,
    usePrimaryTint: Boolean = false,
    onClick: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val bgColor = when {
        usePrimaryTint -> MaterialTheme.colorScheme.primaryContainer
        isWarning -> ext.heatContainer
        isHealthy -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fgColor = when {
        usePrimaryTint -> MaterialTheme.colorScheme.onPrimaryContainer
        isWarning -> ext.onHeatContainer
        isHealthy -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(bgColor)
            .border(
                width = 0.8.dp,
                color = fgColor.copy(alpha = 0.22f),
                shape = CircleShape,
            )
            .pulseBouncyClickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fgColor,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            style = NodePulseThemeTokens.extraTypography.microLabel,
            color = fgColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun IntervalChipStrip(
    title: String,
    optionsMinutes: List<Int>,
    selectedMinutes: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp),
        )
        optionsMinutes.forEach { minutes ->
            val selected = selectedMinutes == minutes
            FilterChip(
                selected = selected,
                onClick = { onSelect(minutes) },
                enabled = enabled,
                label = {
                    Text("${minutes}m")
                },
            )
        }
    }
}

@Composable
private fun RadarSegmentedTabs(
    selectedTab: Int,
    unreadAlertCount: Int,
    activeRulesCount: Int,
    trackedCount: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(
        Triple("提醒记录", unreadAlertCount, unreadAlertCount > 0),
        Triple("关键词关注", activeRulesCount, false),
        Triple("回复追踪", trackedCount, false),
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(PulseArchitecturalShapes.EditorialCard)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                width = 0.8.dp,
                color = NodePulseThemeTokens.extendedColors.cardBorder,
                shape = PulseArchitecturalShapes.EditorialCard,
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEachIndexed { index, (label, badgeCount, highlightBadge) ->
            val selected = selectedTab == index
            val bg by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    Color.Transparent
                },
                label = "tabBg",
            )
            val textColor by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                label = "tabFg",
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .clickable { onSelectTab(index) }
                    .padding(vertical = 9.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    ),
                    color = textColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badgeCount > 0) {
                    Spacer(modifier = Modifier.width(4.dp))
                    CompactCountBadge(
                        count = badgeCount,
                        containerColor = if (highlightBadge) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest.copy(
                                alpha = if (selected) 0.9f else 0.7f,
                            )
                        },
                        contentColor = if (highlightBadge) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertsFeedTabPage(
    uiState: PulseRadarUiState,
    onSelectFilter: (RadarAlertFilter) -> Unit,
    onOpenAlert: (LocalAlertRecord) -> Unit,
    onToggleRead: (LocalAlertRecord) -> Unit,
    onDeleteAlert: (Long) -> Unit,
    onMarkAllRead: () -> Unit,
    onClearRead: () -> Unit,
    onRequestClearAll: () -> Unit,
    onSwitchToKeywords: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(RadarAlertFilter.entries) { filter ->
                val count = when (filter) {
                    RadarAlertFilter.ALL -> uiState.alerts.size
                    RadarAlertFilter.UNREAD -> uiState.unreadAlertCount
                    RadarAlertFilter.KEYWORD_HIT -> uiState.keywordHitCount
                    RadarAlertFilter.TRACKED_REPLY -> uiState.trackedReplyCount
                }
                FilterChip(
                    selected = uiState.selectedFilter == filter,
                    onClick = { onSelectFilter(filter) },
                    label = {
                        Text(
                            text = if (count > 0) "${filter.label} · ${formatBadgeCount(count)}" else filter.label,
                            maxLines = 1,
                            softWrap = false,
                        )
                    },
                )
            }
        }

        if (uiState.alerts.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "本地离线保存 · 未登录也可查阅",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onMarkAllRead,
                    enabled = uiState.unreadAlertCount > 0,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DoneAll,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("全部已读", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(
                    onClick = onClearRead,
                    enabled = uiState.alerts.any { it.isRead },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("清理已读", style = MaterialTheme.typography.labelMedium)
                }
                IconButton(
                    onClick = onRequestClearAll,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ClearAll,
                        contentDescription = "清空全部",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        if (uiState.filteredAlerts.isEmpty()) {
            RadarEmptyStateCard(
                icon = Icons.Rounded.NotificationsNone,
                title = if (uiState.alerts.isEmpty()) "暂无本地提醒记录" else "当前筛选下暂无提醒",
                subtitle = if (uiState.alerts.isEmpty()) {
                    "新增关键词规则或在帖子右上角菜单开启「追踪新回复」后，命中记录会持久保留在这里，重启应用或退出登录后依然可查看。"
                } else {
                    "切换上方筛选标签以查看其他分类的命中记录。"
                },
                actionLabel = if (uiState.alerts.isEmpty()) "去设置关键词规则" else "查看全部记录",
                onAction = {
                    if (uiState.alerts.isEmpty()) onSwitchToKeywords() else onSelectFilter(RadarAlertFilter.ALL)
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(
                    items = uiState.filteredAlerts,
                    key = { _, record -> record.id },
                ) { index, record ->
                    PulseStaggeredEntrance(index = index.coerceAtMost(6)) {
                        AlertRecordCard(
                            record = record,
                            onClick = { onOpenAlert(record) },
                            onToggleRead = { onToggleRead(record) },
                            onDelete = { onDeleteAlert(record.id) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertRecordCard(
    record: LocalAlertRecord,
    onClick: () -> Unit,
    onToggleRead: () -> Unit,
    onDelete: () -> Unit,
) {
    val isUnread = !record.isRead
    val accentColor = when (record.kind) {
        LocalAlertKind.KEYWORD_HIT -> MaterialTheme.colorScheme.primary
        LocalAlertKind.TRACKED_REPLY -> MaterialTheme.colorScheme.tertiary
    }
    val kindLabel = when (record.kind) {
        LocalAlertKind.KEYWORD_HIT -> "关键词命中"
        LocalAlertKind.TRACKED_REPLY -> "追踪回复"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                accentColor = if (isUnread) accentColor else null,
            )
            .pulseBouncyClickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isUnread) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accentColor),
                )
            }
            PulseAccentPill(
                text = kindLabel,
                icon = if (record.kind == LocalAlertKind.KEYWORD_HIT) {
                    Icons.Rounded.Tag
                } else {
                    Icons.Rounded.Forum
                },
                containerColor = accentColor.copy(alpha = 0.14f),
                contentColor = accentColor,
            )
            if (record.matchedKeyword.isNotBlank()) {
                PulseAccentPill(
                    text = "#${record.matchedKeyword}",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (record.targetFloor > 0) {
                PulseAccentPill(
                    text = "#${record.targetFloor} 楼",
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            RelativeTimeText(
                isoTimestamp = record.createdAtEpochMillis.toString(),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Text(
            text = record.topicTitle.ifBlank { "主题 #${record.topicId}" },
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Medium,
            ),
            color = if (isUnread) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        if (record.actorName.isNotBlank() || record.summarySnippet.isNotBlank()) {
            val subtitle = buildString {
                if (record.actorName.isNotBlank()) append(record.actorName)
                if (record.actorName.isNotBlank() && record.summarySnippet.isNotBlank()) append("：")
                append(record.summarySnippet)
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (record.categoryName.isNotBlank()) {
                Text(
                    text = record.categoryName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = onToggleRead,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Icon(
                    imageVector = if (isUnread) Icons.Rounded.MarkEmailRead else Icons.Rounded.MarkEmailUnread,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isUnread) "标为已读" else "标为未读",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "删除记录",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun KeywordRulesTabPage(
    rules: List<KeywordWatchRule>,
    onAddClick: () -> Unit,
    onToggleRule: (ruleId: Long, enabled: Boolean) -> Unit,
    onDeleteRule: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "关注关键词 (${rules.size})",
                    style = NodePulseThemeTokens.extraTypography.sectionHeader,
                )
                Text(
                    text = "巡检最新主题标题与预览，命中即生成本地提醒与通知",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                onClick = onAddClick,
                shape = CircleShape,
                modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 36.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "新增规则",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        if (rules.isEmpty()) {
            RadarEmptyStateCard(
                icon = Icons.Rounded.Tag,
                title = "还没有配置关键词规则",
                subtitle = "支持不区分大小写的包含匹配与正则表达式，还可设置「排除词」及「限定版块」，帮你精准捕捉好价与关键情报。",
                actionLabel = "添加第一条关键词",
                actionIcon = Icons.Rounded.Add,
                onAction = onAddClick,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(
                    items = rules,
                    key = { _, rule -> rule.id },
                ) { index, rule ->
                    PulseStaggeredEntrance(index = index.coerceAtMost(6)) {
                        KeywordRuleCard(
                            rule = rule,
                            onToggle = { enabled -> onToggleRule(rule.id, enabled) },
                            onDelete = { onDeleteRule(rule.id) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordRuleCard(
    rule: KeywordWatchRule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val isDark = NodePulseThemeTokens.isDark
    val categorySpec = CategoryPalette.resolve(rule.categorySlug, isDark)
    val categoryLabel = if (rule.categorySlug.isBlank()) "全站版块" else categorySpec.defaultTitle
    val accent = if (rule.isEnabled) categorySpec.spineColor else null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(accentColor = accent)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = rule.keyword,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (rule.isEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PulseAccentPill(
                        text = if (rule.matchMode == KeywordMatchMode.REGEX) "正则匹配" else "包含匹配",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    PulseAccentPill(
                        text = categoryLabel,
                        containerColor = categorySpec.containerColor,
                        contentColor = categorySpec.contentColor,
                    )
                    if (rule.excludeWords.isNotBlank()) {
                        PulseAccentPill(
                            text = "排除: ${rule.excludeWords}",
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            Switch(
                checked = rule.isEnabled,
                onCheckedChange = onToggle,
            )

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "删除规则",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun TrackedTopicsTabPage(
    trackedTopics: List<TrackedTopic>,
    onOpenTrackedTopic: (TrackedTopic) -> Unit,
    onMarkTopicAlertsRead: (Long) -> Unit,
    onUntrack: (Long) -> Unit,
) {
    if (trackedTopics.isEmpty()) {
        RadarEmptyStateCard(
            icon = Icons.Rounded.NotificationsActive,
            title = "暂无追踪中的帖子",
            subtitle = "打开任意帖子详情页，在右上角「更多菜单」中开启「追踪新回复」，即可在此统一查看楼层进展与新回复提醒。",
            actionLabel = null,
            onAction = {},
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(
            items = trackedTopics,
            key = { _, topic -> topic.topicId },
        ) { index, topic ->
            PulseStaggeredEntrance(index = index.coerceAtMost(6)) {
                TrackedTopicCard(
                    topic = topic,
                    onClick = { onOpenTrackedTopic(topic) },
                    onMarkAlertsRead = { onMarkTopicAlertsRead(topic.topicId) },
                    onUntrack = { onUntrack(topic.topicId) },
                )
            }
        }
    }
}

@Composable
private fun TrackedTopicCard(
    topic: TrackedTopic,
    onClick: () -> Unit,
    onMarkAlertsRead: () -> Unit,
    onUntrack: () -> Unit,
) {
    val isDark = NodePulseThemeTokens.isDark
    val categorySpec = CategoryPalette.resolve(topic.categorySlug, isDark)
    val hasUnread = topic.unreadNewReplies > 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(accentColor = if (hasUnread) MaterialTheme.colorScheme.primary else categorySpec.spineColor)
            .pulseBouncyClickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (topic.categoryName.isNotBlank() || topic.categorySlug.isNotBlank()) {
                PulseAccentPill(
                    text = topic.categoryName.ifBlank { categorySpec.defaultTitle },
                    containerColor = categorySpec.containerColor,
                    contentColor = categorySpec.contentColor,
                )
            }
            if (hasUnread) {
                PulseAccentPill(
                    text = "+${formatBadgeCount(topic.unreadNewReplies)} 新回复",
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (topic.lastCheckedEpochMillis > 0L) {
                RelativeTimeText(
                    isoTimestamp = topic.lastCheckedEpochMillis.toString(),
                    prefix = "检查于 ",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Text(
            text = topic.title.ifBlank { "主题 #${topic.topicId}" },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val floorText = if (topic.lastKnownFloor >= 0) {
                "最新 #${topic.lastKnownFloor} 楼 · 共 ${topic.lastKnownTotalPages} 页"
            } else {
                "正在同步末页基线 · 共 ${topic.lastKnownTotalPages} 页"
            }
            Text(
                text = floorText,
                style = NodePulseThemeTokens.extraTypography.tabularBadge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (hasUnread) {
                TextButton(
                    onClick = onMarkAlertsRead,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("已读", style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButton(
                onClick = onUntrack,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.NotificationsOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "停止追踪",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = "打开帖子",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddKeywordRuleBottomSheet(
    errorMessage: String?,
    isSubmitting: Boolean,
    onClearError: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (keyword: String, excludeWords: String, categorySlug: String, matchMode: KeywordMatchMode) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = NodePulseThemeTokens.isDark
    val categorySpecs = remember(isDark) { CategoryPalette.allSpecs(isDark) }

    var keyword by rememberSaveable { mutableStateOf("") }
    var excludeWords by rememberSaveable { mutableStateOf("") }
    var selectedCategorySlug by rememberSaveable { mutableStateOf("") }
    var matchMode by rememberSaveable { mutableStateOf(KeywordMatchMode.CONTAINS) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "新增关键词提醒",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )

            OutlinedTextField(
                value = keyword,
                onValueChange = {
                    keyword = it
                    onClearError()
                },
                label = { Text("关键词 / 正则表达式") },
                placeholder = {
                    Text(
                        if (matchMode == KeywordMatchMode.REGEX) {
                            "例如：(?i)(claw|dmit).*年付"
                        } else {
                            "例如：CN2、补货、鸡腿、甲骨文"
                        },
                    )
                },
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "匹配模式",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = matchMode == KeywordMatchMode.CONTAINS,
                        onClick = {
                            matchMode = KeywordMatchMode.CONTAINS
                            onClearError()
                        },
                        label = { Text("包含关键词（忽略大小写）") },
                    )
                    FilterChip(
                        selected = matchMode == KeywordMatchMode.REGEX,
                        onClick = {
                            matchMode = KeywordMatchMode.REGEX
                            onClearError()
                        },
                        label = { Text("正则匹配") },
                    )
                }
            }

            OutlinedTextField(
                value = excludeWords,
                onValueChange = {
                    excludeWords = it
                    onClearError()
                },
                label = { Text("排除词（可选，英文/中文逗号分隔）") },
                placeholder = { Text("例如：求购,已出,回收") },
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "限定版块",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(categorySpecs, key = { it.slug }) { spec ->
                        FilterChip(
                            selected = selectedCategorySlug == spec.slug,
                            onClick = { selectedCategorySlug = spec.slug },
                            label = { Text(spec.defaultTitle) },
                        )
                    }
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDismiss,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        onSubmit(keyword, excludeWords, selectedCategorySlug, matchMode)
                    },
                    enabled = !isSubmitting,
                    shape = CircleShape,
                    modifier = Modifier.weight(2f),
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("保存并监控")
                }
            }
        }
    }
}

@Composable
private fun RadarEmptyStateCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String?,
    onAction: () -> Unit,
    actionIcon: ImageVector? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseCard()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!actionLabel.isNullOrBlank()) {
                FilledTonalButton(
                    onClick = onAction,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    if (actionIcon != null) {
                        Icon(
                            imageVector = actionIcon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = actionLabel,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

private fun openAppNotificationSettings(context: Context) {
    val specific = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val launched = runCatching { context.startActivity(specific) }.isSuccess
    if (!launched) {
        openAppDetailsSettings(context)
    }
}

private fun openBatteryOptimizationSettings(context: Context) {
    val ignoreSettings = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val launched = runCatching { context.startActivity(ignoreSettings) }.isSuccess
    if (!launched) {
        openAppDetailsSettings(context)
    }
}

private fun openDoNotDisturbSettings(context: Context) {
    val dndSettings = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val launched = runCatching { context.startActivity(dndSettings) }.isSuccess
    if (!launched) {
        val soundSettings = Intent(Settings.ACTION_SOUND_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(soundSettings) }
    }
}

private fun openAppDetailsSettings(context: Context) {
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(details) }
}
