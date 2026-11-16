@file:OptIn(ExperimentalMaterial3Api::class)

package com.nodeseek.app.ui.hub

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkRemove
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.CheckInStatus
import com.nodeseek.app.domain.model.DailyQuotaProgress
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.ui.components.CategoryMicroTag
import com.nodeseek.app.ui.components.CompactCountBadge
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.components.PulseAvatar
import com.nodeseek.app.ui.components.PulseConfirmDialog
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.RankMicroBadge
import com.nodeseek.app.ui.components.TurnstileVerificationWidget
import com.nodeseek.app.ui.components.WebViewSessionSheet
import com.nodeseek.app.ui.components.formatBadgeCount
import com.nodeseek.app.ui.components.formatCompactCount
import com.nodeseek.app.ui.components.formatRelativeTimeChinese
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGlassSurface
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseStaggeredEntrance
import com.nodeseek.app.ui.theme.RankPalette
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseHeroCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * NodePulse "枢机 · Pulse Hub" Bento Dashboard Screen.
 *
 * Material 3 Expressive Bento dashboard layout:
 * - 2x1 Hero Rank & Coin Progress Bento Card (or Guest Editorial Sign-In Hero Card)
 * - Interactive Inline 鸡腿 Check-In Bento Tile + Live Inbox Bento Tile
 * - Embedded Room Offline Bookmarks & Floor-Resuming Read History Tabs
 */
@Composable
fun PulseHubScreen(
    onOpenTopic: (topicId: Long, initialFloor: Int, titleHint: String) -> Unit,
    onOpenUserProfile: (uid: Long, usernameHint: String) -> Unit,
    onOpenCheckInBoard: () -> Unit,
    onOpenInbox: (initialTab: Int) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenRadar: (initialTab: Int) -> Unit = {},
    onOpenSeekMail: () -> Unit = {},
    onRequestWebViewAuth: (() -> Unit)? = null,
    contentBottomPadding: Dp = 104.dp,
    previewUiState: PulseHubUiState? = null,
    previewRadarUnreadCount: Int? = null,
    previewRadarRules: List<com.nodeseek.app.domain.radar.KeywordWatchRule>? = null,
    previewRadarTrackedTopics: List<com.nodeseek.app.domain.radar.TrackedTopic>? = null,
    viewModel: PulseHubViewModel = viewModel(
        factory = PulseHubViewModel.factory(LocalAppContainer.current),
    ),
) {
    val appContainer = LocalAppContainer.current
    val observedRadarUnreadCount by appContainer.radarRepository.unreadAlertCountFlow.collectAsStateWithLifecycle(
        initialValue = 0,
    )
    val radarUnreadCount = previewRadarUnreadCount ?: observedRadarUnreadCount
    val observedRadarRules by appContainer.radarRepository.keywordRulesFlow.collectAsStateWithLifecycle(
        initialValue = emptyList(),
    )
    val radarRules = previewRadarRules ?: observedRadarRules
    val observedRadarTrackedTopics by appContainer.radarRepository.trackedTopicsFlow.collectAsStateWithLifecycle(
        initialValue = emptyList(),
    )
    val radarTrackedTopics = previewRadarTrackedTopics ?: observedRadarTrackedTopics
    val observedUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = previewUiState ?: observedUiState
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showInternalWebViewSheet by rememberSaveable { mutableStateOf(false) }
    var showClearHistoryConfirm by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onHubVisible()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(uiState.session?.uid) {
        if (uiState.session != null) {
            viewModel.onHubVisible()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val triggerWebViewLogin: () -> Unit = {
        if (onRequestWebViewAuth != null) {
            onRequestWebViewAuth()
        } else {
            showInternalWebViewSheet = true
        }
    }

    val navBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            PulseSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = contentBottomPadding + navBottomInset),
                pillModifier = Modifier.padding(bottom = 8.dp),
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refreshAll,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Subtle atmospheric top gradient halo (AMOLED-safe, fades cleanly into background)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    ext.heroTintStart.copy(
                                        alpha = if (isDark) 0.22f else 0.14f,
                                    ),
                                    ext.heroTintEnd.copy(
                                        alpha = if (isDark) 0.08f else 0.05f,
                                    ),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = contentBottomPadding + navBottomInset + 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // ── Top Bar ──
                    item(key = "hub_header") {
                        PulseHubHeaderBar(
                            onOpenSettings = onOpenSettings,
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(top = 10.dp, bottom = 2.dp),
                        )
                    }

                    // ── 2x1 Hero Bento Card (Signed-In Rank & Progress vs Guest Sign-In) ──
                    item(key = "hub_hero_bento") {
                        PulseStaggeredEntrance(index = 0) {
                            val session = uiState.session
                            if (session != null) {
                                SignedInRankHeroBentoCard(
                                    session = session,
                                    onClick = { onOpenUserProfile(session.uid, session.username) },
                                )
                            } else {
                                SignedOutHeroBentoCard(
                                    onWebViewLogin = triggerWebViewLogin,
                                    onNativeCredentialLogin = viewModel::openNativeSignInDialog,
                                )
                            }
                        }
                    }

                    // ── Interactive 1x1 + 1x1 Bento Row (Check-In + Inbox) ──
                    item(key = "hub_bento_pair") {
                        PulseStaggeredEntrance(index = 1) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CheckInBentoTile(
                                    isSignedIn = uiState.session != null,
                                    checkInStatus = uiState.checkInStatus,
                                    isLoadingStatus = uiState.isLoadingCheckInStatus,
                                    dailyQuota = uiState.dailyQuota,
                                    preferRandom = uiState.settings.preferRandomCheckIn,
                                    isCheckingIn = uiState.isCheckingIn,
                                    onToggleRandomMode = viewModel::setPreferRandomCheckIn,
                                    onCheckInClick = {
                                        if (uiState.session == null) {
                                            triggerWebViewLogin()
                                        } else {
                                            viewModel.performCheckIn()
                                        }
                                    },
                                    onOpenBoard = onOpenCheckInBoard,
                                    modifier = Modifier
                                        .weight(1.15f)
                                        .fillMaxHeight(),
                                )

                                InboxBentoTile(
                                    isSignedIn = uiState.session != null,
                                    unreadCount = uiState.unreadInboxCount,
                                    onOpenInbox = onOpenInbox,
                                    onRequestLogin = triggerWebViewLogin,
                                    modifier = Modifier
                                        .weight(0.85f)
                                        .fillMaxHeight(),
                                )
                            }
                        }
                    }

                    // ── Pulse Radar & Reply Tracking Bento Card ──
                    item(key = "hub_radar_bento") {
                        PulseStaggeredEntrance(index = 2) {
                            PulseRadarHubBentoCard(
                                unreadCount = radarUnreadCount,
                                activeRuleCount = radarRules.count { it.isEnabled },
                                trackedTopicCount = radarTrackedTopics.size,
                                onOpenRadar = onOpenRadar,
                            )
                        }
                    }

                    // ── Seek Mail (seek.li) OpenAPI Bento Card ──
                    item(key = "hub_seek_mail_bento") {
                        PulseStaggeredEntrance(index = 3) {
                            SeekMailHubBentoCard(
                                isConfigured = uiState.seekMailConfigured,
                                mailboxCount = uiState.seekMailboxCount,
                                primaryAddress = uiState.seekMailPrimaryAddress,
                                unreadCount = uiState.seekMailUnreadCount,
                                hasMoreUnread = uiState.seekMailHasMoreUnread,
                                isLoading = uiState.seekMailLoading,
                                errorMessage = uiState.seekMailError,
                                onOpenSeekMail = onOpenSeekMail,
                            )
                        }
                    }

                    // ── Collections Switcher Header (收藏夹 | 阅读足迹) ──
                    item(key = "hub_collections_tabs") {
                        PulseStaggeredEntrance(index = 4) {
                            HubCollectionsTabHeader(
                                selectedTab = uiState.selectedTab,
                                bookmarkCount = uiState.bookmarks.size,
                                historyCount = uiState.readHistory.size,
                                onSelectTab = viewModel::selectCollectionTab,
                                onRequestClearHistory = {
                                    if (uiState.readHistory.isNotEmpty()) {
                                        showClearHistoryConfirm = true
                                    }
                                },
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }

                    // ── Tab Body ──
                    when (uiState.selectedTab) {
                        HubCollectionTab.BOOKMARKS -> {
                            if (uiState.bookmarks.isEmpty() && !uiState.isLoadingServerBookmarks) {
                                item(key = "bookmarks_empty") {
                                    HubEmptyPlaceholderCard(
                                        icon = Icons.Rounded.Bookmark,
                                        title = "暂无收藏主题",
                                        subtitle = "浏览帖子时点按右上角书签即可加入收藏；本地将自动保存离线快照以便无网翻阅。",
                                    )
                                }
                            } else {
                                itemsIndexed(
                                    items = uiState.bookmarks,
                                    key = { _, item -> "bm_${item.topicId}" },
                                ) { index, item ->
                                    PulseStaggeredEntrance(
                                        index = index,
                                        enabled = index < 8,
                                    ) {
                                        HubBookmarkCard(
                                            item = item,
                                            onClick = {
                                                val floor = item.summary.lastReadFloor ?: 0
                                                onOpenTopic(item.topicId, floor, item.summary.title)
                                            },
                                            onUnbookmark = { viewModel.removeBookmark(item.topicId) },
                                        )
                                    }
                                }

                                if (uiState.hasMoreServerBookmarks || uiState.isLoadingServerBookmarks) {
                                    item(key = "bookmarks_load_more") {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (uiState.isLoadingServerBookmarks) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(24.dp),
                                                    strokeWidth = 2.5.dp,
                                                )
                                            } else {
                                                OutlinedButton(
                                                    onClick = viewModel::loadMoreServerBookmarks,
                                                    shape = CircleShape,
                                                ) {
                                                    Text("加载更多云端收藏")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        HubCollectionTab.HISTORY -> {
                            if (uiState.readHistory.isEmpty()) {
                                item(key = "history_empty") {
                                    HubEmptyPlaceholderCard(
                                        icon = Icons.Rounded.History,
                                        title = "暂无浏览记录",
                                        subtitle = "您阅读过的主题与上次停留楼层会保存在设备本地，点击即可一键续读。",
                                    )
                                }
                            } else {
                                itemsIndexed(
                                    items = uiState.readHistory,
                                    key = { _, entry -> "hist_${entry.topicId}" },
                                ) { index, entry ->
                                    PulseStaggeredEntrance(
                                        index = index,
                                        enabled = index < 8,
                                    ) {
                                        HubReadHistoryCard(
                                            entry = entry,
                                            onClick = {
                                                onOpenTopic(
                                                    entry.topicId,
                                                    entry.lastReadFloor,
                                                    entry.title,
                                                )
                                            },
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

    if (showInternalWebViewSheet) {
        WebViewSessionSheet(
            onDismiss = { showInternalWebViewSheet = false },
            onSessionVerified = {
                showInternalWebViewSheet = false
                viewModel.onWebViewAuthCompleted()
            },
            autoDismissWhenVerified = true,
        )
    }

    if (uiState.nativeSignIn.isVisible) {
        NativeCredentialSignInDialog(
            state = uiState.nativeSignIn,
            onDismiss = viewModel::dismissNativeSignInDialog,
            onSubmitCredentials = viewModel::submitCredentials,
            onSubmitOtp = viewModel::submitTwoFactorCode,
            onReturnToCredentials = viewModel::returnToCredentialsStep,
            onSwitchToWebView = {
                viewModel.dismissNativeSignInDialog()
                triggerWebViewLogin()
            },
        )
    }

    if (showClearHistoryConfirm) {
        PulseConfirmDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = "清空浏览记录",
            message = "确定要清空保存在本机的全部浏览历史与楼层进度记录吗？此操作无法撤销。",
            confirmText = "确认清空",
            onConfirm = {
                showClearHistoryConfirm = false
                viewModel.clearReadHistory()
            },
            dismissText = "取消",
            icon = Icons.Rounded.DeleteSweep,
            tone = PulseDialogTone.DESTRUCTIVE,
        )
    }
}

@Composable
private fun PulseHubHeaderBar(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark
    val ext = NodePulseThemeTokens.extendedColors

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(PulseArchitecturalShapes.MediumBadge)
                    .background(PulseBrushes.brandSoftWash(alpha = if (isDark) 0.22f else 0.14f))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.34f else 0.20f),
                        shape = PulseArchitecturalShapes.MediumBadge,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Radar,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(21.dp),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = "个人中心",
                    style = NodePulseThemeTokens.extraTypography.heroHeadline,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
                Text(
                    text = "账号信息 · 每日签到 · 收藏与浏览记录",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        PulseGlassSurface(
            shape = CircleShape,
            containerColor = ext.cardSurfaceElevated.copy(alpha = if (isDark) 0.85f else 0.92f),
            tonalAccent = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(42.dp)
                .then(
                    if (!isDark) {
                        Modifier.shadow(
                            elevation = PulseElevation.Card,
                            shape = CircleShape,
                            clip = false,
                            ambientColor = ext.softShadowColor,
                            spotColor = ext.softShadowColor,
                        )
                    } else {
                        Modifier
                    },
                )
                .pulseBouncyClickable(
                    pressedScale = NodePulseMotion.chipPressScale,
                    role = Role.Button,
                    onClickLabel = "打开设置",
                    onClick = onOpenSettings,
                ),
        ) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = "打开设置",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(20.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignedOutHeroBentoCard(
    onWebViewLogin: () -> Unit,
    onNativeCredentialLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary

    val ctaGradient = Brush.horizontalGradient(
        colors = listOf(
            primaryColor,
            blendColor(primaryColor, secondaryColor, 0.32f),
        ),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseHeroCard(accentColor = primaryColor)
            .drawBehind {
                val center = Offset(size.width * 0.92f, size.height * 0.16f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = if (isDark) 0.16f else 0.10f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = 150.dp.toPx(),
                    ),
                    radius = 150.dp.toPx(),
                    center = center,
                )
                drawCircle(
                    color = primaryColor.copy(alpha = if (isDark) 0.14f else 0.09f),
                    radius = 58.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
                drawCircle(
                    color = secondaryColor.copy(alpha = if (isDark) 0.10f else 0.07f),
                    radius = 90.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .then(
                        if (!isDark) {
                            Modifier.shadow(
                                elevation = 4.dp,
                                shape = RoundedCornerShape(15.dp),
                                clip = false,
                                ambientColor = primaryColor.copy(alpha = 0.28f),
                                spotColor = primaryColor.copy(alpha = 0.28f),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clip(RoundedCornerShape(15.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF0C1224),
                                Color(0xFF15224A),
                                Color(0xFF1F3470),
                            ),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(15.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                AnodeBrandMark(modifier = Modifier.size(34.dp))
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Anode",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    PulseAccentPill(
                        text = "访客模式",
                        icon = Icons.Rounded.AutoAwesome,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Text(
                    text = "连接 NodeSeek 账户 · 解锁完整社区体验",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Text(
            text = "连接您的 NodeSeek 社区账号以解锁每日鸡腿签到、内版访问、云端收藏同步、站内私信与回帖互动。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HeroFeatureChip(icon = Icons.Rounded.Restaurant, label = "每日鸡腿")
            HeroFeatureChip(icon = Icons.Rounded.Bookmark, label = "云端同步")
            HeroFeatureChip(icon = Icons.Rounded.Mail, label = "站内私信")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1.25f)
                    .height(44.dp)
                    .then(
                        if (!isDark) {
                            Modifier.shadow(
                                elevation = 4.dp,
                                shape = CircleShape,
                                clip = false,
                                ambientColor = primaryColor.copy(alpha = 0.25f),
                                spotColor = primaryColor.copy(alpha = 0.25f),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clip(CircleShape)
                    .background(ctaGradient)
                    .pulseBouncyClickable(
                        pressedScale = NodePulseMotion.chipPressScale,
                        role = Role.Button,
                        onClick = onWebViewLogin,
                    )
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "WebView 安全登录",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier
                    .weight(0.95f)
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(ext.cardSurfaceElevated)
                    .border(1.dp, ext.cardBorder, CircleShape)
                    .pulseBouncyClickable(
                        pressedScale = NodePulseMotion.chipPressScale,
                        role = Role.Button,
                        onClick = onNativeCredentialLogin,
                    )
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "密码 / 2FA",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HeroFeatureChip(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark

    Row(
        modifier = modifier
            .clip(PulseArchitecturalShapes.SmallChip)
            .background(
                if (isDark) Color.White.copy(alpha = 0.05f) else ext.cardSurfaceElevated.copy(alpha = 0.85f),
            )
            .border(
                width = 0.8.dp,
                color = ext.cardBorder,
                shape = PulseArchitecturalShapes.SmallChip,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(12.dp),
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
private fun SignedInRankHeroBentoCard(
    session: AccountSession,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val rankSpec = NodePulseThemeTokens.rankSpec(session.level)
    val rankProgress = remember(session.drumsticks, isDark) {
        RankPalette.progressFromCoins(session.drumsticks, isDark = isDark)
    }
    val animatedProgress by animateFloatAsState(
        targetValue = rankProgress.progressFraction,
        animationSpec = NodePulseMotion.spatialFloatSpring(),
        label = "rankCoinProgress",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseHeroCard(accentColor = rankSpec.glowColor)
            .drawBehind {
                val glowCenter = Offset(size.width * 0.90f, size.height * 0.14f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            rankSpec.glowColor.copy(alpha = if (isDark) 0.18f else 0.11f),
                            Color.Transparent,
                        ),
                        center = glowCenter,
                        radius = 155.dp.toPx(),
                    ),
                    radius = 155.dp.toPx(),
                    center = glowCenter,
                )
                drawCircle(
                    color = rankSpec.glowColor.copy(alpha = if (isDark) 0.14f else 0.08f),
                    radius = 56.dp.toPx(),
                    center = glowCenter,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            .pulseBouncyClickable(
                role = Role.Button,
                onClickLabel = "查看个人主页",
                onClick = onClick,
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ── Identity Row ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PulseAvatar(
                avatarUrl = session.avatarUrl,
                username = session.username,
                size = 64.dp,
                rankLevel = session.level,
                ringWidth = 2.6.dp,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = session.username,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    RankMicroBadge(
                        level = session.level,
                        showPersona = true,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .clip(PulseArchitecturalShapes.SmallChip)
                            .background(
                                if (isDark) Color.White.copy(alpha = 0.06f)
                                else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.75f),
                            )
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "UID #${session.uid}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ).withTabularNumbers(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    if (session.isTelegramBound) {
                        PulseAccentPill(
                            text = "TG 已绑定",
                            icon = Icons.Rounded.Bolt,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(
                        if (isDark) Color.White.copy(alpha = 0.07f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = "查看个人主页",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // ── Animated Coin Progress Panel ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (isDark) Color.White.copy(alpha = 0.04f)
                    else Color.White.copy(alpha = 0.58f),
                )
                .border(
                    width = 0.8.dp,
                    color = rankSpec.glowColor.copy(alpha = if (isDark) 0.24f else 0.16f),
                    shape = RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val progressCaption = if (rankProgress.nextThresholdCoins != null) {
                    "距离 Lv.${session.level + 1} 还差 ${rankProgress.coinsToNextLevel} 鸡腿"
                } else {
                    "已达最高等级 Lv.6"
                }
                Text(
                    text = progressCaption,
                    style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(8.dp))
                val fractionLabel = if (rankProgress.nextThresholdCoins != null) {
                    "${session.drumsticks} / ${rankProgress.nextThresholdCoins}"
                } else {
                    "${session.drumsticks} 🍗"
                }
                Text(
                    text = fractionLabel,
                    style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                    fontWeight = FontWeight.Bold,
                    color = rankSpec.contentColor,
                    maxLines = 1,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress.coerceIn(0.04f, 1f))
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(rankSpec.ringBrush),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 2.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.82f)),
                    )
                }
            }
        }

        PulseGradientDivider(color = ext.cardBorder)

        // ── 3-Cell Telemetry Strip ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeroStatCell(
                iconTint = ext.onDrumstickContainer,
                containerColor = ext.drumstickContainer,
                accentColor = ext.drumstickGoldStart,
                icon = Icons.Rounded.Restaurant,
                value = session.drumsticks.toString(),
                label = "鸡腿",
                modifier = Modifier.weight(1f),
            )
            HeroStatCell(
                iconTint = ext.onStardustContainer,
                containerColor = ext.stardustContainer,
                accentColor = ext.stardustAccent,
                icon = Icons.Rounded.AutoAwesome,
                value = session.stardust.toString(),
                label = "星尘",
                modifier = Modifier.weight(1f),
            )
            HeroStatCell(
                iconTint = MaterialTheme.colorScheme.primary,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                accentColor = MaterialTheme.colorScheme.primary,
                icon = Icons.Rounded.Forum,
                value = "${session.topicCount} / ${session.replyCount}",
                label = "主题 / 评论",
                modifier = Modifier.weight(1.15f),
            )
        }
    }
}

@Composable
private fun HeroStatCell(
    iconTint: Color,
    containerColor: Color,
    accentColor: Color,
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val cellShape = RoundedCornerShape(14.dp)

    Column(
        modifier = modifier
            .clip(cellShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        containerColor.copy(alpha = if (isDark) 0.78f else 0.88f),
                        blendColor(containerColor, ext.cardSurface, 0.35f).copy(alpha = if (isDark) 0.58f else 0.72f),
                    ),
                ),
            )
            .border(
                width = 0.8.dp,
                color = accentColor.copy(alpha = if (isDark) 0.28f else 0.20f),
                shape = cellShape,
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(accentColor.copy(alpha = if (isDark) 0.22f else 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall.withTabularNumbers(),
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = label,
            style = NodePulseThemeTokens.extraTypography.microLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun CheckInBentoTile(
    isSignedIn: Boolean,
    checkInStatus: CheckInStatus?,
    isLoadingStatus: Boolean,
    dailyQuota: DailyQuotaProgress?,
    preferRandom: Boolean,
    isCheckingIn: Boolean,
    onToggleRandomMode: (Boolean) -> Unit,
    onCheckInClick: () -> Unit,
    onOpenBoard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val haptics = LocalHapticFeedback.current
    val hasCheckedIn = isSignedIn && checkInStatus?.hasCheckedInToday == true
    val isSyncingInitialStatus = isSignedIn && checkInStatus == null && isLoadingStatus && !isCheckingIn

    var popTrigger by remember { mutableStateOf(false) }
    LaunchedEffect(popTrigger) {
        if (popTrigger) {
            delay(200)
            popTrigger = false
        }
    }
    val ctaScale by animateFloatAsState(
        targetValue = if (popTrigger) 1.06f else 1f,
        animationSpec = NodePulseMotion.microBounceSpring(),
        label = "checkInButtonBounce",
    )

    val tileAccent = if (hasCheckedIn) ext.drumstickGoldStart else MaterialTheme.colorScheme.tertiary

    Column(
        modifier = modifier
            .pulseCard(
                shape = PulseArchitecturalShapes.ElevatedTile,
                elevation = PulseElevation.CardElevated,
                accentColor = tileAccent,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(PulseArchitecturalShapes.SmallChip)
                            .background(ext.drumstickContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Restaurant,
                            contentDescription = null,
                            tint = ext.drumstickGoldEnd,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Text(
                        text = "每日签到",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.45f else 0.55f))
                        .pulseBouncyClickable(
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.Button,
                            onClick = onOpenBoard,
                        )
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Leaderboard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = "签到榜",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }

            // Segmented 2-way Mode Switch (固定 +5 | 🎲 试试手气)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(0.8.dp, ext.cardBorder, CircleShape)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                CheckInModeSegment(
                    selected = !preferRandom,
                    icon = Icons.Rounded.Shield,
                    label = "固定 +5",
                    onClick = { onToggleRandomMode(false) },
                    modifier = Modifier.weight(1f),
                )
                CheckInModeSegment(
                    selected = preferRandom,
                    icon = Icons.Rounded.Casino,
                    label = "试试手气",
                    onClick = { onToggleRandomMode(true) },
                    modifier = Modifier.weight(1f),
                )
            }

            // Action / Checked-In Badge
            AnimatedContent(
                targetState = hasCheckedIn,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "checkInActionSwap",
            ) { checkedIn ->
                if (checkedIn) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(13.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        ext.drumstickContainer,
                                        blendColor(ext.drumstickContainer, ext.drumstickGoldStart, 0.18f),
                                    ),
                                ),
                            )
                            .border(
                                width = 1.dp,
                                color = ext.drumstickGoldStart.copy(alpha = 0.42f),
                                shape = RoundedCornerShape(13.dp),
                            )
                            .pulseBouncyClickable(
                                role = Role.Button,
                                onClick = onOpenBoard,
                            )
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = ext.drumstickGoldEnd,
                                modifier = Modifier.size(16.dp),
                            )
                            val gain = checkInStatus?.todayGain
                            val gainHeadline = when {
                                gain == null -> "今日已签到"
                                gain > 0 -> "今日已领 +$gain 鸡腿"
                                else -> "今日已签到 · 0 鸡腿"
                            }
                            Text(
                                text = gainHeadline,
                                style = MaterialTheme.typography.labelLarge.withTabularNumbers(),
                                fontWeight = FontWeight.Bold,
                                color = ext.onDrumstickContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val orderText = checkInStatus?.todayOrder?.let { "第 #$it 名签到" }
                            ?: "共 ${checkInStatus?.totalCheckedInUsers ?: 0} 人已签"
                        Text(
                            text = orderText,
                            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                            color = ext.onDrumstickContainer.copy(alpha = 0.85f),
                            maxLines = 1,
                        )
                    }
                } else {
                    val ctaBrush = if (isSignedIn) {
                        PulseBrushes.drumstickGoldGradient()
                    } else {
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                blendColor(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, 0.35f),
                            ),
                        )
                    }
                    val ctaContentColor = if (isSignedIn) {
                        Color(0xFF1F0E00)
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .graphicsLayer {
                                scaleX = ctaScale
                                scaleY = ctaScale
                            }
                            .clip(CircleShape)
                            .background(ctaBrush)
                            .pulseBouncyClickable(
                                enabled = !isCheckingIn && !isSyncingInitialStatus,
                                pressedScale = NodePulseMotion.chipPressScale,
                                role = Role.Button,
                                onClick = {
                                    popTrigger = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onCheckInClick()
                                },
                            )
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isCheckingIn || isSyncingInitialStatus) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp,
                                color = ctaContentColor,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isCheckingIn) "签到中…" else "同步状态…",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = ctaContentColor,
                                maxLines = 1,
                            )
                        } else {
                            Icon(
                                imageVector = if (isSignedIn) Icons.Rounded.Restaurant else Icons.Rounded.Language,
                                contentDescription = null,
                                tint = ctaContentColor,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (isSignedIn) "立即签到" else "登录签到",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = ctaContentColor,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        // Daily Bonus Quota Mini-Bars
        if (dailyQuota != null) {
            Column(
                modifier = Modifier.padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                PulseGradientDivider(color = ext.cardBorder)
                Spacer(modifier = Modifier.height(1.dp))
                QuotaMiniBar(
                    label = "发帖奖励",
                    current = dailyQuota.postBonusEarned,
                    max = dailyQuota.maxPostBonus,
                )
                QuotaMiniBar(
                    label = "回帖奖励",
                    current = dailyQuota.replyBonusEarned,
                    max = dailyQuota.maxReplyBonus,
                )
                QuotaMiniBar(
                    label = "免费加腿",
                    current = dailyQuota.freeDrumsticksUsed,
                    max = dailyQuota.maxFreeDrumsticks,
                )
            }
        }
    }
}

@Composable
private fun CheckInModeSegment(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        label = "modeSegBg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "modeSegFg",
    )

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(vertical = 5.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(12.dp),
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QuotaMiniBar(
    label: String,
    current: Int,
    max: Int,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val safeMax = max.coerceAtLeast(1)
    val progress = (current.toFloat() / safeMax.toFloat()).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = NodePulseMotion.spatialFloatSpring(),
        label = "quotaBarProgress",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(46.dp),
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            if (animatedProgress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress.coerceAtLeast(0.05f))
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(PulseBrushes.drumstickGoldGradient()),
                )
            }
        }
        Text(
            text = "$current/$max",
            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
            color = if (current >= max && max > 0) ext.drumstickGoldEnd else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun InboxBentoTile(
    isSignedIn: Boolean,
    unreadCount: Int,
    onOpenInbox: (Int) -> Unit,
    onRequestLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark
    val hasUnread = unreadCount > 0

    Column(
        modifier = modifier
            .pulseCard(
                shape = PulseArchitecturalShapes.ElevatedTile,
                elevation = PulseElevation.CardElevated,
                accentColor = if (hasUnread) MaterialTheme.colorScheme.primary else null,
            )
            .pulseBouncyClickable(
                role = Role.Button,
                onClickLabel = "查看通知",
                onClick = {
                    if (isSignedIn) onOpenInbox(0) else onRequestLogin()
                },
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(PulseArchitecturalShapes.SmallChip)
                            .background(
                                if (hasUnread) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Notifications,
                            contentDescription = null,
                            tint = if (hasUnread) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Text(
                        text = "消息通知",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (hasUnread) {
                    CompactCountBadge(
                        count = unreadCount,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        cutoutBorderColor = NodePulseThemeTokens.extendedColors.cardSurface,
                        cutoutBorderWidth = 1.dp,
                    )
                }
            }

            if (isSignedIn && hasUnread) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = formatCompactCount(unreadCount),
                        style = NodePulseThemeTokens.extraTypography.metricValue,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Text(
                        text = "条未读互动",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(bottom = 3.dp),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    text = if (!isSignedIn) {
                        "连接账号以接收楼层回复、@提及与站内私信提醒。"
                    } else {
                        "暂无未读动态，一切静好。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            modifier = Modifier.padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                InboxQuickChip(
                    label = "@我",
                    icon = Icons.Rounded.AlternateEmail,
                    onClick = { if (isSignedIn) onOpenInbox(1) else onRequestLogin() },
                    modifier = Modifier.weight(1f),
                )
                InboxQuickChip(
                    label = "私信",
                    icon = Icons.Rounded.Mail,
                    onClick = { if (isSignedIn) onOpenInbox(2) else onRequestLogin() },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.14f else 0.08f),
                    )
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "查看通知",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun InboxQuickChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors

    Row(
        modifier = modifier
            .clip(PulseArchitecturalShapes.SmallChip)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(0.8.dp, ext.cardBorder, PulseArchitecturalShapes.SmallChip)
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 6.dp, horizontal = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.width(3.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HubCollectionsTabHeader(
    selectedTab: HubCollectionTab,
    bookmarkCount: Int,
    historyCount: Int,
    onSelectTab: (HubCollectionTab) -> Unit,
    onRequestClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(0.8.dp, ext.cardBorder, CircleShape)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CollectionSegmentPill(
                selected = selectedTab == HubCollectionTab.BOOKMARKS,
                icon = Icons.Rounded.Bookmark,
                label = "我的收藏 (${formatCompactCount(bookmarkCount)})",
                onClick = { onSelectTab(HubCollectionTab.BOOKMARKS) },
                modifier = Modifier.weight(1f),
            )
            CollectionSegmentPill(
                selected = selectedTab == HubCollectionTab.HISTORY,
                icon = Icons.Rounded.History,
                label = "浏览记录 (${formatCompactCount(historyCount)})",
                onClick = { onSelectTab(HubCollectionTab.HISTORY) },
                modifier = Modifier.weight(1f),
            )
        }

        if (selectedTab == HubCollectionTab.HISTORY && historyCount > 0) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(ext.cardSurface)
                    .border(0.8.dp, ext.cardBorder, CircleShape)
                    .pulseBouncyClickable(
                        pressedScale = NodePulseMotion.chipPressScale,
                        role = Role.Button,
                        onClickLabel = "清空浏览记录",
                        onClick = onRequestClearHistory,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteSweep,
                    contentDescription = "清空浏览记录",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

@Composable
private fun CollectionSegmentPill(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        label = "collectionTabBg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "collectionTabFg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        } else {
            Color.Transparent
        },
        label = "collectionTabBorder",
    )

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .border(0.8.dp, borderColor, CircleShape)
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(vertical = 8.dp, horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(15.dp),
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.withTabularNumbers(),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HubBookmarkCard(
    item: HubBookmarkDisplayItem,
    onClick: () -> Unit,
    onUnbookmark: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val topic = item.summary
    val categorySpec = NodePulseThemeTokens.categorySpec(topic.categorySlug)
    val spineColor = categorySpec.spineColor
    val isDark = NodePulseThemeTokens.isDark

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                accentColor = spineColor,
            )
            .pulseBouncyClickable(
                role = Role.Button,
                onClick = onClick,
            )
            .drawWithContent {
                drawContent()
                val barW = 3.dp.toPx()
                val vPad = 14.dp.toPx()
                if (size.height > vPad * 2f) {
                    val r = 2.dp.toPx()
                    drawRoundRect(
                        color = spineColor,
                        topLeft = Offset(0f, vPad),
                        size = Size(barW, size.height - vPad * 2f),
                        cornerRadius = CornerRadius(r, r),
                    )
                }
            }
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (topic.categorySlug.isNotBlank() || topic.categoryName.isNotBlank()) {
                CategoryMicroTag(
                    slug = topic.categorySlug,
                    labelOverride = topic.categoryName,
                )
            }

            if (item.hasOfflineSnapshot) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .border(
                            width = 0.8.dp,
                            color = MaterialTheme.colorScheme.secondary.copy(alpha = if (isDark) 0.35f else 0.22f),
                            shape = CircleShape,
                        )
                        .padding(horizontal = 7.dp, vertical = 2.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.OfflinePin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = "已离线缓存",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            IconButton(
                onClick = onUnbookmark,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.BookmarkRemove,
                    contentDescription = "取消收藏",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Text(
            text = topic.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = topic.authorName.ifBlank { "NodeSeek 成员" },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (topic.replyCount > 0) {
                    Text(
                        text = "· ${formatCompactCount(topic.replyCount)} 回复",
                        style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            val savedIso = item.savedAtEpochMillis?.let { Instant.ofEpochMilli(it).toString() }
                ?: topic.lastActivityIso
            if (savedIso.isNotBlank()) {
                Text(
                    text = formatRelativeTimeChinese(savedIso),
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun HubReadHistoryCard(
    entry: ReadHistoryEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visitedIso = remember(entry.visitedAtEpochMillis) {
        Instant.ofEpochMilli(entry.visitedAtEpochMillis).toString()
    }
    val categorySpec = NodePulseThemeTokens.categorySpec(entry.categorySlug)
    val isDark = NodePulseThemeTokens.isDark

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                accentColor = if (entry.categorySlug.isNotBlank()) categorySpec.spineColor else null,
            )
            .pulseBouncyClickable(
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PulseAvatar(
                avatarUrl = entry.authorAvatarUrl,
                username = entry.authorName,
                size = 26.dp,
            )
            Text(
                text = entry.authorName.ifBlank { "匿名用户" },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (entry.categorySlug.isNotBlank() || entry.categoryName.isNotBlank()) {
                CategoryMicroTag(
                    slug = entry.categorySlug,
                    labelOverride = entry.categoryName,
                )
            }
        }

        Text(
            text = entry.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

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
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "浏览于 ${formatRelativeTimeChinese(visitedIso)}",
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val floorLabel = if (entry.lastReadFloor > 0) {
                "续读 #${entry.lastReadFloor} 楼"
            } else {
                "重新打开"
            }
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .border(
                        width = 0.8.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.35f else 0.20f),
                        shape = CircleShape,
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = floorLabel,
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
internal fun HubEmptyPlaceholderCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.HeroBento,
                accentColor = MaterialTheme.colorScheme.primary,
            )
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(PulseBrushes.brandSoftWash(alpha = if (isDark) 0.22f else 0.14f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.34f else 0.22f),
                    shape = CircleShape,
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
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NativeCredentialSignInDialog(
    state: NativeSignInDialogState,
    onDismiss: () -> Unit,
    onSubmitCredentials: (username: String, password: String, turnstileToken: String) -> Unit,
    onSubmitOtp: (otpCode: String) -> Unit,
    onReturnToCredentials: () -> Unit,
    onSwitchToWebView: () -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var turnstileToken by remember { mutableStateOf("") }
    var otpCode by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    val isOtpStep = state.otpSession != null

    LaunchedEffect(state.turnstileResetNonce) {
        if (state.turnstileResetNonce > 0) {
            turnstileToken = ""
        }
    }

    LaunchedEffect(isOtpStep) {
        if (isOtpStep) {
            otpCode = ""
        }
    }

    PulseAlertDialog(
        onDismissRequest = { if (!state.isSubmitting) onDismiss() },
        title = if (isOtpStep) "两步验证 (2FA)" else "账号密码登录",
        icon = if (isOtpStep) Icons.Rounded.Shield else Icons.Rounded.Key,
        tone = PulseDialogTone.PRIMARY,
        confirmText = if (isOtpStep) "验证并登录" else "登录",
        onConfirm = {
            if (isOtpStep) {
                onSubmitOtp(otpCode)
            } else {
                onSubmitCredentials(username, password, turnstileToken)
            }
        },
        confirmEnabled = !state.isSubmitting,
        confirmLoading = state.isSubmitting,
        dismissText = "取消",
        onDismiss = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = !state.isSubmitting,
            dismissOnClickOutside = !state.isSubmitting,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (isOtpStep) {
                Text(
                    text = "请输入验证器应用生成的 6 位动态验证码：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = otpCode,
                    onValueChange = { otpCode = it },
                    label = { Text("两步验证码") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = onReturnToCredentials,
                    enabled = !state.isSubmitting,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("返回修改账号密码")
                }
            } else {
                Text(
                    text = "输入账号密码后，请手动完成下方 Cloudflare 人机验证再点击登录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名 / 邮箱") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) {
                                    Icons.Rounded.VisibilityOff
                                } else {
                                    Icons.Rounded.Visibility
                                },
                                contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                TurnstileVerificationWidget(
                    resetNonce = state.turnstileResetNonce,
                    onTokenReceived = { token -> turnstileToken = token },
                    onTokenCleared = { turnstileToken = "" },
                )
            }

            if (!state.errorMessage.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = state.errorMessage,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            TextButton(
                onClick = onSwitchToWebView,
                enabled = !state.isSubmitting,
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Language,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("改用 WebView 官方网页安全登录")
            }
        }
    }
}

@Composable
private fun AnodeBrandMark(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val s = size.minDimension / 64f
        val stroke = 7.4f * s
        val apex = Offset(32f * s, 14f * s)
        val leftFoot = Offset(17f * s, 49f * s)
        val rightFoot = Offset(47f * s, 49f * s)

        // Right pillar (cobalt depth gradient)
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF9BB2FF), Color(0xFF5878F5)),
                start = apex,
                end = rightFoot,
            ),
            start = apex,
            end = rightFoot,
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )

        // Left pillar (crisp ice white)
        drawLine(
            color = Color.White,
            start = leftFoot,
            end = apex,
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )

        // Floating Anode core crossbar
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF3FE8DC), Color(0xFF6BA2FF)),
                start = Offset(27f * s, 39.5f * s),
                end = Offset(37f * s, 39.5f * s),
            ),
            start = Offset(29.6f * s, 39.5f * s),
            end = Offset(34.4f * s, 39.5f * s),
            strokeWidth = 5.2f * s,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun PulseRadarHubBentoCard(
    unreadCount: Int,
    activeRuleCount: Int,
    trackedTopicCount: Int,
    onOpenRadar: (initialTab: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (unreadCount > 0) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondary
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(accentColor = accent)
            .pulseBouncyClickable(onClick = { onOpenRadar(0) })
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(PulseArchitecturalShapes.MediumBadge)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Radar,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "关键词提醒与追更记录",
                    style = NodePulseThemeTokens.extraTypography.sectionHeader,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "正在监听 ${formatCompactCount(activeRuleCount)} 条关键词 · 已追更 ${formatCompactCount(trackedTopicCount)} 帖回复",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (unreadCount > 0) {
                PulseAccentPill(
                    text = "${formatBadgeCount(unreadCount)} 未读",
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }

            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = "进入脉搏雷达",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                Triple("提醒记录", 0, unreadCount),
                Triple("关键词关注", 1, activeRuleCount),
                Triple("回复追踪", 2, trackedTopicCount),
            ).forEach { (title, tab, chipCount) ->
                val highlightChipCount = tab == 0 && chipCount > 0
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f))
                        .border(
                            width = 0.7.dp,
                            color = NodePulseThemeTokens.extendedColors.cardBorder,
                            shape = CircleShape,
                        )
                        .pulseBouncyClickable(
                            pressedScale = NodePulseMotion.chipPressScale,
                            onClick = { onOpenRadar(tab) },
                        )
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = NodePulseThemeTokens.extraTypography.microLabel,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (chipCount > 0) {
                        CompactCountBadge(
                            count = chipCount,
                            containerColor = if (highlightChipCount) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            contentColor = if (highlightChipCount) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            minHeight = 16.dp,
                            textStyle = NodePulseThemeTokens.extraTypography.microLabel,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekMailHubBentoCard(
    isConfigured: Boolean,
    mailboxCount: Int,
    primaryAddress: String,
    unreadCount: Int,
    hasMoreUnread: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    onOpenSeekMail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasUnread = isConfigured && unreadCount > 0
    val hasError = isConfigured && !errorMessage.isNullOrBlank() && mailboxCount == 0
    val accent = when {
        hasUnread -> MaterialTheme.colorScheme.primary
        hasError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary
    }
    val subtitleText = when {
        !isConfigured -> "官方开发者 OpenAPI 收件箱 · 验证码提取 · 别名管理"
        isLoading && mailboxCount == 0 && primaryAddress.isBlank() -> "正在同步 seek.li 邮箱与未读邮件…"
        hasError -> errorMessage.orEmpty()
        primaryAddress.isNotBlank() -> "主邮箱：$primaryAddress"
        else -> "官方开发者 OpenAPI 收件箱 · 验证码提取 · 别名管理"
    }
    val statusPillText = when {
        !isConfigured -> "未配置 API Key"
        hasUnread -> "${formatBadgeCount(unreadCount)}${if (hasMoreUnread) "+" else ""} 未读"
        hasError -> "连接异常"
        else -> "已连接 · ${formatCompactCount(mailboxCount)} 邮箱"
    }
    val statusContainerColor = when {
        !isConfigured -> MaterialTheme.colorScheme.surfaceContainerHigh
        hasUnread -> MaterialTheme.colorScheme.primary
        hasError -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val statusContentColor = when {
        !isConfigured -> MaterialTheme.colorScheme.onSurfaceVariant
        hasUnread -> MaterialTheme.colorScheme.onPrimary
        hasError -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(accentColor = if (isConfigured) accent else null)
            .pulseBouncyClickable(
                role = Role.Button,
                onClickLabel = "打开 Seek 邮箱",
                onClick = onOpenSeekMail,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(PulseArchitecturalShapes.MediumBadge)
                    .background(accent.copy(alpha = if (isConfigured) 0.14f else 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (hasUnread) {
                        Icons.Rounded.MarkEmailUnread
                    } else {
                        Icons.Rounded.AlternateEmail
                    },
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Seek 邮箱 · seek.li",
                    style = NodePulseThemeTokens.extraTypography.sectionHeader,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            PulseAccentPill(
                text = statusPillText,
                containerColor = statusContainerColor,
                contentColor = statusContentColor,
            )

            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = "打开 Seek 邮箱",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val pills = if (isConfigured) {
                listOf(
                    "已连接 · ${formatCompactCount(mailboxCount)} 邮箱",
                    "验证码提取",
                    "别名管理",
                )
            } else {
                listOf(
                    "验证码提取",
                    "别名管理",
                    "立即配置",
                )
            }
            pills.forEachIndexed { index, label ->
                val isPrimaryChip = (isConfigured && index == 0) || (!isConfigured && index == 2)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(
                            if (isPrimaryChip && isConfigured) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f)
                            },
                        )
                        .border(
                            width = 0.7.dp,
                            color = if (isPrimaryChip && isConfigured) {
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.30f)
                            } else {
                                NodePulseThemeTokens.extendedColors.cardBorder
                            },
                            shape = CircleShape,
                        )
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = NodePulseThemeTokens.extraTypography.microLabel.withTabularNumbers(),
                        color = if (isPrimaryChip && isConfigured) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
