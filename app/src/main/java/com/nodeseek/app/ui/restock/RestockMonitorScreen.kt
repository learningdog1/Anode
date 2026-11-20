@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.restock

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.ShoppingCartCheckout
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.data.restock.FlareSolverrClient
import com.nodeseek.app.data.restock.MerchantLinkDetector
import com.nodeseek.app.data.restock.RestockNotificationHelper
import com.nodeseek.app.domain.restock.RestockEventRecord
import com.nodeseek.app.domain.restock.RestockMonitorController
import com.nodeseek.app.domain.restock.RestockMonitorSettings
import com.nodeseek.app.domain.restock.RestockRepository
import com.nodeseek.app.domain.restock.RestockRuntimeStatus
import com.nodeseek.app.domain.restock.RestockStockStatus
import com.nodeseek.app.domain.restock.RestockWatchItem
import com.nodeseek.app.ui.components.CompactCountBadge
import com.nodeseek.app.ui.components.PulseConfirmDialog
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.SortSegmentItem
import com.nodeseek.app.ui.components.SortSegmentedSwitcher
import com.nodeseek.app.ui.components.formatRelativeTimeChinese
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseSectionHeader
import com.nodeseek.app.ui.theme.PulseStaggeredEntrance
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.launch
import java.net.URI

private val BACKGROUND_INTERVAL_OPTIONS = listOf(15, 30, 60, 120)
private val FLARE_SOLVERR_TIMEOUT_OPTIONS_MS = listOf(30_000, 60_000, 90_000)

/**
 * Full-featured VPS Restock & Flash-Sale Monitor screen with:
 * - Paste URL quick-add bar + 1-click "测试检测" preview,
 * - Live watch cards with stock pills (`有货可抢` / `缺货蹲守中` / `检测异常`),
 * - Manual "立即巡检全部" & per-item inspection,
 * - Foreground/background polling interval settings sheet,
 * - Restock alert history tab with unread badges,
 * - 1-click checkout redirect and optional [onImportToServerLedger] hook.
 */
@Composable
fun RestockMonitorScreen(
    repository: RestockRepository,
    monitorController: RestockMonitorController,
    notificationHelper: RestockNotificationHelper,
    flareSolverrClient: FlareSolverrClient,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: Int = 0,
    initialUrl: String = "",
    initialTitle: String = "",
    onOpenTopic: ((Long) -> Unit)? = null,
    onImportToServerLedger: ((RestockWatchItem) -> Unit)? = null,
    previewUiState: RestockMonitorUiState? = null,
) {
    val viewModel: RestockMonitorViewModel = viewModel(
        factory = RestockMonitorViewModel.factory(
            repository = repository,
            monitorController = monitorController,
            notificationHelper = notificationHelper,
            flareSolverrClient = flareSolverrClient,
        ),
    )
    val observedUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = previewUiState ?: observedUiState
    val isTestingFlareSolverr by viewModel.isTestingFlareSolverr.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val pagerState = rememberPagerState(
        initialPage = initialTab.coerceIn(0, 1),
        pageCount = { 2 },
    )

    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var consumedInitialPrefill by rememberSaveable(initialUrl, initialTitle) { mutableStateOf(false) }
    var pendingPrefillUrl by rememberSaveable { mutableStateOf("") }
    var pendingPrefillTitle by rememberSaveable { mutableStateOf("") }
    var editingWatchId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteWatchId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClearHistory by rememberSaveable { mutableStateOf(false) }
    var intervalPickerWatchId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(initialUrl, initialTitle) {
        if (initialUrl.isNotBlank() && !consumedInitialPrefill) {
            consumedInitialPrefill = true
            editingWatchId = null
            pendingPrefillUrl = initialUrl
            pendingPrefillTitle = initialTitle
            showAddSheet = true
        }
    }

    val editingItem = remember(editingWatchId, uiState.watchItems) {
        editingWatchId?.let { id -> uiState.watchItems.firstOrNull { it.id == id } }
    }
    val pendingDeleteItem = remember(pendingDeleteWatchId, uiState.watchItems) {
        pendingDeleteWatchId?.let { id -> uiState.watchItems.firstOrNull { it.id == id } }
    }
    val intervalPickerItem = remember(intervalPickerWatchId, uiState.watchItems) {
        intervalPickerWatchId?.let { id -> uiState.watchItems.firstOrNull { it.id == id } }
    }

    LaunchedEffect(initialTab) {
        val target = initialTab.coerceIn(0, 1)
        if (pagerState.currentPage != target) {
            pagerState.animateScrollToPage(target)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.snackbarMessages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshNotificationPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.refreshNotificationPermission()
        if (!granted) {
            openNotificationSettings(context)
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("补货高优先级通知权限已开启")
            }
        }
    }

    val openCheckoutUrl: (String) -> Unit = { url ->
        val resolved = MerchantLinkDetector.resolveOrderUrl(url)
        val launched = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(resolved)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.isSuccess
        if (!launched) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("未找到可打开该下单链接的浏览器")
            }
        }
    }

    val requestNotificationPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !uiState.hasNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings(context)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            RestockMonitorTopBar(
                runtimeStatus = uiState.runtimeStatus,
                settings = uiState.settings,
                onBack = onBack,
                onCheckAllNow = viewModel::checkAllNow,
                onOpenSettings = { showSettingsSheet = true },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingWatchId = null
                    pendingPrefillUrl = ""
                    pendingPrefillTitle = ""
                    showAddSheet = true
                },
                shape = PulseArchitecturalShapes.SquircleFab,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.navigationBarsPadding(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "添加监控",
                )
            }
        },
        snackbarHost = {
            PulseSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 2-Tab Segmented Switcher
            RestockTabSwitcher(
                selectedTab = pagerState.currentPage,
                watchCount = uiState.watchItems.size,
                historyCount = uiState.eventHistory.size,
                unreadHistoryCount = uiState.unreadEventCount,
                onSelectTab = { idx ->
                    coroutineScope.launch { pagerState.animateScrollToPage(idx) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> RestockWatchesTabContent(
                        uiState = uiState,
                        onQuickUrlChange = viewModel::onQuickUrlChange,
                        onPasteClipboard = {
                            val pasted = readClipboardText(context)
                            if (pasted.isNullOrBlank()) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("剪贴板暂无可用链接")
                                }
                            } else {
                                viewModel.onQuickUrlChange(pasted.trim())
                            }
                        },
                        onClearQuickUrl = viewModel::clearQuickUrl,
                        onProbeQuickUrl = viewModel::probeQuickUrl,
                        onQuickAdd = viewModel::quickAddCurrentUrl,
                        onOpenAdvancedAdd = {
                            editingWatchId = null
                            pendingPrefillUrl = uiState.quickUrlInput
                            pendingPrefillTitle = ""
                            showAddSheet = true
                        },
                        onOpenFreshAdd = {
                            editingWatchId = null
                            pendingPrefillUrl = ""
                            pendingPrefillTitle = ""
                            showAddSheet = true
                        },
                        onCheckAllNow = viewModel::checkAllNow,
                        onRequestNotificationPermission = requestNotificationPermission,
                        onToggleEnabled = viewModel::setWatchEnabled,
                        onCheckSingle = viewModel::checkSingleWatch,
                        onOpenOrderUrl = openCheckoutUrl,
                        onOpenIntervalPicker = { intervalPickerWatchId = it },
                        onEditWatch = { id ->
                            editingWatchId = id
                            pendingPrefillUrl = ""
                            pendingPrefillTitle = ""
                            showAddSheet = true
                        },
                        onDeleteWatch = { pendingDeleteWatchId = it },
                        onOpenTopic = onOpenTopic,
                        onImportToServerLedger = onImportToServerLedger,
                    )

                    else -> RestockHistoryTabContent(
                        events = uiState.eventHistory,
                        unreadCount = uiState.unreadEventCount,
                        onMarkAllRead = viewModel::markAllEventsRead,
                        onClearHistory = { confirmClearHistory = true },
                        onOpenOrderUrl = { event ->
                            viewModel.markEventRead(event.id)
                            openCheckoutUrl(event.resolvedOrderUrl)
                        },
                        onDeleteEvent = viewModel::deleteEvent,
                        onOpenTopic = onOpenTopic,
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        AddRestockWatchBottomSheet(
            onDismiss = {
                showAddSheet = false
                editingWatchId = null
                pendingPrefillUrl = ""
                pendingPrefillTitle = ""
            },
            onSave = { draft ->
                viewModel.saveWatchDraft(draft)
                showAddSheet = false
                editingWatchId = null
                pendingPrefillUrl = ""
                pendingPrefillTitle = ""
            },
            initialUrl = editingItem?.rawUrl ?: pendingPrefillUrl,
            initialTitle = editingItem?.title ?: pendingPrefillTitle,
            initialPriceHint = editingItem?.priceHint.orEmpty(),
            sourceTopicId = editingItem?.sourceTopicId,
            editingItem = editingItem,
            defaultPollIntervalMinutes = uiState.settings.defaultPollIntervalMinutes,
            onProbeUrl = { url, inKw, outKw ->
                viewModel.probeUrl(
                    rawUrl = url,
                    customInStockKeyword = inKw,
                    customOutOfStockKeyword = outKw,
                )
            },
        )
    }

    if (showSettingsSheet) {
        RestockMonitorSettingsBottomSheet(
            settings = uiState.settings,
            hasNotificationPermission = uiState.hasNotificationPermission,
            isTestingFlareSolverr = isTestingFlareSolverr,
            onDismiss = { showSettingsSheet = false },
            onToggleMaster = viewModel::setMonitorEnabled,
            onToggleUrgentNotifications = viewModel::setUrgentNotificationsEnabled,
            onSelectDefaultPollInterval = viewModel::setDefaultPollInterval,
            onSelectBackgroundInterval = viewModel::setBackgroundInterval,
            onToggleWifiOnly = viewModel::setWifiOnlyInBackground,
            onToggleRequireBatteryNotLow = viewModel::setRequireBatteryNotLow,
            onToggleFlareSolverr = viewModel::setFlareSolverrEnabled,
            onFlareSolverrBaseUrlChange = viewModel::setFlareSolverrBaseUrl,
            onSelectFlareSolverrTimeout = viewModel::setFlareSolverrMaxTimeout,
            onTestFlareSolverr = viewModel::testFlareSolverrConnection,
            onRequestNotificationPermission = requestNotificationPermission,
        )
    }

    if (intervalPickerItem != null) {
        WatchIntervalPickerDialog(
            item = intervalPickerItem,
            onDismiss = { intervalPickerWatchId = null },
            onSelectInterval = { minutes ->
                viewModel.updateWatchPollInterval(intervalPickerItem.id, minutes)
                intervalPickerWatchId = null
            },
        )
    }

    if (pendingDeleteItem != null) {
        PulseConfirmDialog(
            onDismissRequest = { pendingDeleteWatchId = null },
            title = "移除补货监控",
            message = "确定要停止蹲守「${pendingDeleteItem.title}」吗？历史补货记录会保留。",
            confirmText = "确认移除",
            tone = PulseDialogTone.DESTRUCTIVE,
            icon = Icons.Rounded.DeleteOutline,
            onConfirm = {
                viewModel.removeWatch(pendingDeleteItem.id)
                pendingDeleteWatchId = null
            },
        )
    }

    if (confirmClearHistory) {
        PulseConfirmDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = "清空补货记录",
            message = "确定要删除全部 ${uiState.eventHistory.size} 条历史补货通知记录吗？此操作无法撤销。",
            confirmText = "清空全部",
            tone = PulseDialogTone.DESTRUCTIVE,
            icon = Icons.Rounded.DeleteSweep,
            onConfirm = {
                viewModel.clearEventHistory()
                confirmClearHistory = false
            },
        )
    }
}

@Composable
private fun RestockMonitorTopBar(
    runtimeStatus: RestockRuntimeStatus,
    settings: RestockMonitorSettings,
    onBack: () -> Unit,
    onCheckAllNow: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "restockSpin")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "restockSpinAngle",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseGlassSurface(showBottomBorder = true)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "补货监控",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (settings.isMonitorEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        ),
                )
            }
            val subtitle = when {
                runtimeStatus.isCheckingNow -> "正在巡检商品库存状态…"
                !settings.isMonitorEnabled -> "监控总开关已暂停"
                runtimeStatus.lastCheckEpochMillis > 0L ->
                    "上次巡检：${formatRelativeTimeChinese(runtimeStatus.lastCheckEpochMillis.toString())}"
                else -> "前台快速蹲守 · 后台每 ${settings.backgroundIntervalMinutes} 分钟"
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onCheckAllNow,
            enabled = !runtimeStatus.isCheckingNow,
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = "立即巡检全部",
                modifier = if (runtimeStatus.isCheckingNow) {
                    Modifier.rotate(spinAngle)
                } else {
                    Modifier
                },
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = "监控间隔与推送设置",
            )
        }
    }
}

@Composable
private fun RestockTabSwitcher(
    selectedTab: Int,
    watchCount: Int,
    historyCount: Int,
    unreadHistoryCount: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val historyLabel = when {
        unreadHistoryCount > 0 -> "补货记录 · ${unreadHistoryCount} 未读"
        historyCount > 0 -> "补货记录 ($historyCount)"
        else -> "补货记录"
    }
    val options = remember(watchCount, historyLabel) {
        listOf(
            SortSegmentItem(0, "蹲守列表 ($watchCount)"),
            SortSegmentItem(1, historyLabel),
        )
    }
    SortSegmentedSwitcher(
        options = options,
        selectedValue = selectedTab,
        onSelect = onSelectTab,
        modifier = modifier,
    )
}

@Composable
private fun RestockWatchesTabContent(
    uiState: RestockMonitorUiState,
    onQuickUrlChange: (String) -> Unit,
    onPasteClipboard: () -> Unit,
    onClearQuickUrl: () -> Unit,
    onProbeQuickUrl: () -> Unit,
    onQuickAdd: () -> Unit,
    onOpenAdvancedAdd: () -> Unit,
    onOpenFreshAdd: () -> Unit,
    onCheckAllNow: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onCheckSingle: (String) -> Unit,
    onOpenOrderUrl: (String) -> Unit,
    onOpenIntervalPicker: (String) -> Unit,
    onEditWatch: (String) -> Unit,
    onDeleteWatch: (String) -> Unit,
    onOpenTopic: ((Long) -> Unit)?,
    onImportToServerLedger: ((RestockWatchItem) -> Unit)?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            bottom = 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Notification permission alert if disabled
        if (!uiState.hasNotificationPermission) {
            item(key = "notif_warning") {
                NotificationPermissionBanner(onGrantClick = onRequestNotificationPermission)
            }
        }

        // Hero telemetry overview + 1-click "立即巡检全部"
        item(key = "hero_summary") {
            RestockHeroSummaryCard(
                uiState = uiState,
                onCheckAllNow = onCheckAllNow,
            )
        }

        // Quick Paste URL bar with 1-click "测试检测" preview
        item(key = "quick_add_bar") {
            QuickAddUrlBarCard(
                urlInput = uiState.quickUrlInput,
                isProbing = uiState.isProbingQuickUrl,
                probeResult = uiState.quickProbeResult,
                onUrlChange = onQuickUrlChange,
                onPasteClipboard = onPasteClipboard,
                onClear = onClearQuickUrl,
                onProbe = onProbeQuickUrl,
                onQuickAdd = onQuickAdd,
                onOpenAdvanced = onOpenAdvancedAdd,
            )
        }

        item(key = "watch_list_header") {
            PulseSectionHeader(
                title = "监控任务",
                subtitle = if (uiState.watchItems.isEmpty()) {
                    "粘贴商家下单页链接即可开始蹲守补货"
                } else {
                    "共 ${uiState.watchItems.size} 项 · ${uiState.watchItems.count { it.isEnabled }} 项运行中"
                },
                icon = Icons.Rounded.Bolt,
                accentColor = MaterialTheme.colorScheme.primary,
            )
        }

        if (uiState.watchItems.isEmpty()) {
            item(key = "empty_watches") {
                EmptyWatchesPlaceholder(onAddClick = onOpenFreshAdd)
            }
        } else {
            itemsIndexed(
                items = uiState.watchItems,
                key = { _, item -> item.id },
            ) { index, item ->
                PulseStaggeredEntrance(index = index) {
                    RestockWatchCard(
                        item = item,
                        isChecking = item.id in uiState.runtimeStatus.checkingItemIds,
                        onToggleEnabled = { enabled -> onToggleEnabled(item.id, enabled) },
                        onCheckNow = { onCheckSingle(item.id) },
                        onOpenOrderUrl = { onOpenOrderUrl(item.resolvedOrderUrl) },
                        onOpenIntervalPicker = { onOpenIntervalPicker(item.id) },
                        onEdit = { onEditWatch(item.id) },
                        onDelete = { onDeleteWatch(item.id) },
                        onOpenTopic = item.sourceTopicId?.let { topicId ->
                            onOpenTopic?.let { handler -> { handler(topicId) } }
                        },
                        onImportToServerLedger = onImportToServerLedger?.let { handler ->
                            { handler(item) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationPermissionBanner(
    onGrantClick: () -> Unit,
) {
    val errorAccent = MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard()
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.NotificationsOff,
            contentDescription = null,
            tint = errorAccent,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "未开启系统通知权限",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "开启后才能在 VPS 补货瞬间弹出高优先级抢购提醒",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(
            onClick = onGrantClick,
            shape = PulseArchitecturalShapes.SmallChip,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text("去开启")
        }
    }
}

@Composable
private fun RestockHeroSummaryCard(
    uiState: RestockMonitorUiState,
    onCheckAllNow: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(shape = PulseArchitecturalShapes.HeroBento)
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (uiState.inStockCount > 0) {
                        "发现 ${uiState.inStockCount} 款有货可抢"
                    } else {
                        "全天候蹲守特惠机补货"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "自动识别 WHMCS / SolusVM / HostBill / VirtFusion，补货波次去重防刷屏",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(
                onClick = onCheckAllNow,
                enabled = !uiState.runtimeStatus.isCheckingNow && uiState.watchItems.any { it.isEnabled },
                shape = PulseArchitecturalShapes.MediumBadge,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (uiState.runtimeStatus.isCheckingNow) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("巡检中")
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("立即巡检")
                }
            }
        }

        PulseGradientDivider(color = ext.cardBorder.copy(alpha = 0.55f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RestockHeroStatColumn(
                label = "有货可抢",
                value = uiState.inStockCount.toString(),
                valueColor = if (uiState.inStockCount > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
            RestockHeroStatColumn(
                label = "缺货蹲守",
                value = uiState.outOfStockCount.toString(),
                modifier = Modifier.weight(1f),
            )
            RestockHeroStatColumn(
                label = "补货提醒",
                value = uiState.eventHistory.size.toString(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RestockHeroStatColumn(
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
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
            ).withTabularNumbers(),
            color = valueColor,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun QuickAddUrlBarCard(
    urlInput: String,
    isProbing: Boolean,
    probeResult: com.nodeseek.app.domain.restock.StockAnalysisResult?,
    onUrlChange: (String) -> Unit,
    onPasteClipboard: () -> Unit,
    onClear: () -> Unit,
    onProbe: () -> Unit,
    onQuickAdd: () -> Unit,
    onOpenAdvanced: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Link,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp),
            )
            Text(
                text = "快速粘贴商品链接",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onOpenAdvanced,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text("高级配置", style = MaterialTheme.typography.labelMedium)
            }
        }

        OutlinedTextField(
            value = urlInput,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = "粘贴 cart.php / 商家商品页 / NodeSeek 跳转链接",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            singleLine = true,
            shape = PulseArchitecturalShapes.MediumBadge,
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (urlInput.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "清空",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        IconButton(onClick = onPasteClipboard) {
                            Icon(
                                imageVector = Icons.Rounded.ContentPaste,
                                contentDescription = "粘贴剪贴板",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = onProbe,
                enabled = urlInput.isNotBlank() && !isProbing,
                shape = PulseArchitecturalShapes.MediumBadge,
                modifier = Modifier.weight(1f),
            ) {
                if (isProbing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("检测中…")
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Science,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("测试检测")
                }
            }

            Button(
                onClick = onQuickAdd,
                enabled = urlInput.isNotBlank() && !isProbing,
                shape = PulseArchitecturalShapes.MediumBadge,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text("一键蹲守")
            }
        }

        AnimatedVisibility(
            visible = probeResult != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            probeResult?.let { result ->
                StockProbePreviewCard(result = result)
            }
        }
    }
}

@Composable
private fun RestockWatchCard(
    item: RestockWatchItem,
    isChecking: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
    onOpenOrderUrl: () -> Unit,
    onOpenIntervalPicker: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenTopic: (() -> Unit)?,
    onImportToServerLedger: (() -> Unit)?,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val cardShape = PulseArchitecturalShapes.EditorialCard
    val cardBorderColor = when (item.stockStatus) {
        RestockStockStatus.IN_STOCK -> MaterialTheme.colorScheme.primary.copy(alpha = 0.48f)
        RestockStockStatus.CHECK_ERROR -> MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
        else -> ext.cardBorder
    }
    val cardBorderWidth = if (item.stockStatus == RestockStockStatus.IN_STOCK) 1.dp else 0.8.dp
    val hostText = remember(item.resolvedOrderUrl) {
        runCatching { URI(item.resolvedOrderUrl).host }.getOrNull()
            ?.removePrefix("www.")
            ?: item.resolvedOrderUrl
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(ext.cardSurface)
            .border(width = cardBorderWidth, color = cardBorderColor, shape = cardShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Top status badges & enable toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RestockStatusPill(status = item.stockStatus)
            PanelTypeBadge(panelType = item.panelType)
            if (item.priceHint.isNotBlank()) {
                PriceHintBadge(priceHint = item.priceHint)
            }
            Spacer(modifier = Modifier.weight(1f))
            Switch(
                checked = item.isEnabled,
                onCheckedChange = onToggleEnabled,
            )
        }

        // Title & URL caption
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$hostText · ${item.resolvedOrderUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = item.lastErrorMessage?.takeIf { item.stockStatus == RestockStockStatus.CHECK_ERROR }
                ?: item.lastEvidenceSummary
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.stockStatus == RestockStockStatus.CHECK_ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Metadata chips: poll interval, last checked, last restock, source topic
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClick = onOpenIntervalPicker)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "每 ${item.pollIntervalMinutes} 分钟",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (item.lastCheckedEpochMillis > 0L) {
                Text(
                    text = "检测于 ${formatRelativeTimeChinese(item.lastCheckedEpochMillis.toString())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }

            if (item.lastRestockedEpochMillis > 0L) {
                Text(
                    text = "上次补货 ${formatRelativeTimeChinese(item.lastRestockedEpochMillis.toString())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = restockStatusAccentColor(RestockStockStatus.IN_STOCK),
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }

            if (item.sourceTopicId != null && onOpenTopic != null) {
                Row(
                    modifier = Modifier
                        .clip(PulseArchitecturalShapes.SmallChip)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(
                            width = 0.8.dp,
                            color = ext.cardBorder,
                            shape = PulseArchitecturalShapes.SmallChip,
                        )
                        .clickable(onClick = onOpenTopic)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Forum,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = "原帖 #${item.sourceTopicId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        PulseGradientDivider()

        // Action row: 1-click checkout, single check, optional ledger import, edit, delete
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val isInStock = item.stockStatus == RestockStockStatus.IN_STOCK
            Button(
                onClick = onOpenOrderUrl,
                shape = PulseArchitecturalShapes.SmallChip,
                colors = if (isInStock) {
                    restockCheckoutButtonColors()
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = if (isInStock) {
                        Icons.Rounded.ShoppingCartCheckout
                    } else {
                        Icons.AutoMirrored.Rounded.OpenInNew
                    },
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = if (isInStock) "立即抢购" else "打开下单页",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            OutlinedButton(
                onClick = onCheckNow,
                enabled = !isChecking,
                shape = PulseArchitecturalShapes.SmallChip,
                border = BorderStroke(0.8.dp, ext.cardBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text("巡检", style = MaterialTheme.typography.labelMedium)
            }

            if (onImportToServerLedger != null) {
                IconButton(onClick = onImportToServerLedger) {
                    Icon(
                        imageVector = Icons.Rounded.AccountBalanceWallet,
                        contentDescription = "记入服务器账本",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = "编辑",
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyWatchesPlaceholder(
    onAddClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Radar,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text = "还没有正在蹲守的 VPS 链接",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "可在上方粘贴 WHMCS / SolusVM / HostBill 商品页，或在帖子内长按商品链接一键加入补货监控。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        FilledTonalButton(
            onClick = onAddClick,
            shape = PulseArchitecturalShapes.MediumBadge,
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("手动添加监控")
        }
    }
}

@Composable
private fun RestockHistoryTabContent(
    events: List<RestockEventRecord>,
    unreadCount: Int,
    onMarkAllRead: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenOrderUrl: (RestockEventRecord) -> Unit,
    onDeleteEvent: (String) -> Unit,
    onOpenTopic: ((Long) -> Unit)?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            bottom = 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (events.isNotEmpty()) {
            item(key = "history_actions") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (unreadCount > 0) "共 ${events.size} 条 · $unreadCount 条未读" else "共 ${events.size} 条补货记录",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (unreadCount > 0) {
                        TextButton(onClick = onMarkAllRead) {
                            Icon(
                                imageVector = Icons.Rounded.DoneAll,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("全部已读")
                        }
                    }
                    TextButton(onClick = onClearHistory) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteSweep,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("清空", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (events.isEmpty()) {
            item(key = "empty_history") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseCard()
                        .padding(horizontal = 24.dp, vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(36.dp),
                    )
                    Text(
                        text = "暂无补货放货记录",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "当蹲守商品从「缺货」变为「有货可抢」时，系统会立即发出高优先级抢购提醒并记录于此。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            itemsIndexed(
                items = events,
                key = { _, event -> event.id },
            ) { index, event ->
                PulseStaggeredEntrance(index = index) {
                    RestockEventHistoryCard(
                        event = event,
                        onOpenOrderUrl = { onOpenOrderUrl(event) },
                        onDelete = { onDeleteEvent(event.id) },
                        onOpenTopic = event.sourceTopicId?.let { topicId ->
                            onOpenTopic?.let { handler -> { handler(topicId) } }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RestockEventHistoryCard(
    event: RestockEventRecord,
    onOpenOrderUrl: () -> Unit,
    onDelete: () -> Unit,
    onOpenTopic: (() -> Unit)?,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val cardShape = PulseArchitecturalShapes.EditorialCard
    val borderColor = if (!event.isRead) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    } else {
        ext.cardBorder
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(ext.cardSurface)
            .border(width = if (!event.isRead) 1.dp else 0.8.dp, color = borderColor, shape = cardShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!event.isRead) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            RestockStatusPill(status = RestockStockStatus.IN_STOCK)
            PanelTypeBadge(panelType = event.panelType)
            if (event.priceHint.isNotBlank()) {
                PriceHintBadge(priceHint = event.priceHint)
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = formatRelativeTimeChinese(event.detectedAtEpochMillis.toString()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = event.watchTitle,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        if (event.evidenceSummary.isNotBlank()) {
            Text(
                text = "触发依据：${event.evidenceSummary}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onOpenOrderUrl,
                shape = PulseArchitecturalShapes.SmallChip,
                colors = restockCheckoutButtonColors(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ShoppingCartCheckout,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text("直达下单页", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }

            if (onOpenTopic != null) {
                OutlinedButton(
                    onClick = onOpenTopic,
                    shape = PulseArchitecturalShapes.SmallChip,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Forum,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("查看原帖", style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            IconButton(onClick = onDelete) {
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
private fun RestockMonitorSettingsBottomSheet(
    settings: RestockMonitorSettings,
    hasNotificationPermission: Boolean,
    isTestingFlareSolverr: Boolean,
    onDismiss: () -> Unit,
    onToggleMaster: (Boolean) -> Unit,
    onToggleUrgentNotifications: (Boolean) -> Unit,
    onSelectDefaultPollInterval: (Int) -> Unit,
    onSelectBackgroundInterval: (Int) -> Unit,
    onToggleWifiOnly: (Boolean) -> Unit,
    onToggleRequireBatteryNotLow: (Boolean) -> Unit,
    onToggleFlareSolverr: (Boolean) -> Unit,
    onFlareSolverrBaseUrlChange: (String) -> Unit,
    onSelectFlareSolverrTimeout: (Int) -> Unit,
    onTestFlareSolverr: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ext = NodePulseThemeTokens.extendedColors

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = ext.cardSurfaceElevated,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "补货监控与轮询设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            SettingSwitchRow(
                title = "启用自动补货监控",
                subtitle = "应用在前台时按单品间隔快速轮询，退到后台自动交由 WorkManager 巡检",
                checked = settings.isMonitorEnabled,
                onCheckedChange = onToggleMaster,
            )

            SettingSwitchRow(
                title = "高优先级补货抢购提醒",
                subtitle = "渠道「VPS 补货抢购提醒」(IMPORTANCE_HIGH)，点击通知直接打开结账页",
                checked = settings.urgentNotificationsEnabled,
                onCheckedChange = onToggleUrgentNotifications,
            )

            if (!hasNotificationPermission) {
                NotificationPermissionBanner(onGrantClick = onRequestNotificationPermission)
            }

            PulseGradientDivider()

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "新增监控默认前台轮询间隔（每 ${settings.defaultPollIntervalMinutes} 分钟）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RESTOCK_POLL_INTERVAL_OPTIONS.forEach { minutes ->
                        FilterChip(
                            selected = settings.defaultPollIntervalMinutes == minutes,
                            onClick = { onSelectDefaultPollInterval(minutes) },
                            label = { Text("${minutes}m") },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "后台 WorkManager 巡检周期（每 ${settings.backgroundIntervalMinutes} 分钟）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "受 Android 系统限制，后台周期任务最小间隔为 15 分钟",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    BACKGROUND_INTERVAL_OPTIONS.forEach { minutes ->
                        FilterChip(
                            selected = settings.backgroundIntervalMinutes == minutes,
                            onClick = { onSelectBackgroundInterval(minutes) },
                            label = { Text("${minutes}m") },
                        )
                    }
                }
            }

            PulseGradientDivider()

            SettingSwitchRow(
                title = "后台仅在 Wi-Fi 下巡检",
                subtitle = "开启后使用移动流量时不执行后台库存抓取",
                checked = settings.wifiOnlyInBackground,
                onCheckedChange = onToggleWifiOnly,
            )

            SettingSwitchRow(
                title = "低电量时暂停后台巡检",
                subtitle = "抢购高峰建议关闭，确保低电量也能收到补货提醒",
                checked = settings.requireBatteryNotLowInBackground,
                onCheckedChange = onToggleRequireBatteryNotLow,
            )

            PulseGradientDivider()

            SettingSwitchRow(
                title = "Cloudflare 盾自动突破（FlareSolverr）",
                subtitle = "商家页触发 CF 人机验证时改由自建 FlareSolverr 解盾后抓取，前台轮询与后台巡检均生效",
                checked = settings.flareSolverr.enabled,
                onCheckedChange = onToggleFlareSolverr,
            )

            if (settings.flareSolverr.enabled) {
                OutlinedTextField(
                    value = settings.flareSolverr.baseUrl,
                    onValueChange = onFlareSolverrBaseUrlChange,
                    singleLine = true,
                    label = { Text("FlareSolverr 服务地址") },
                    placeholder = { Text("http://192.168.1.10:8191") },
                    supportingText = {
                        Text("部署在 NAS / VPS 上的 FlareSolverr 实例，默认端口 8191")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "解盾超时预算（${settings.flareSolverr.maxTimeoutMillis / 1000} 秒）",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "挑战页较慢时调大；每次解盾都会在服务端启动真实浏览器页签",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FLARE_SOLVERR_TIMEOUT_OPTIONS_MS.forEach { millis ->
                            FilterChip(
                                selected = settings.flareSolverr.maxTimeoutMillis == millis,
                                onClick = { onSelectFlareSolverrTimeout(millis) },
                                label = { Text("${millis / 1000}s") },
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = onTestFlareSolverr,
                    enabled = !isTestingFlareSolverr,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isTestingFlareSolverr) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在连接…")
                    } else {
                        Text("测试连接")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun WatchIntervalPickerDialog(
    item: RestockWatchItem,
    onDismiss: () -> Unit,
    onSelectInterval: (Int) -> Unit,
) {
    PulseConfirmDialog(
        onDismissRequest = onDismiss,
        title = "调整巡检间隔",
        message = "为「${item.title}」选择前台自动检测周期：",
        confirmText = "关闭",
        dismissText = null,
        onConfirm = onDismiss,
        extraContent = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                RESTOCK_POLL_INTERVAL_OPTIONS.forEach { minutes ->
                    FilterChip(
                        selected = item.pollIntervalMinutes == minutes,
                        onClick = { onSelectInterval(minutes) },
                        label = { Text("每 ${minutes} 分钟") },
                    )
                }
            }
        },
    )
}

@Composable
private fun restockCheckoutButtonColors(): ButtonColors =
    ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    )

private fun readClipboardText(context: Context): String? {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount <= 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
