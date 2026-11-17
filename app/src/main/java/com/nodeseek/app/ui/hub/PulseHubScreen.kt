@file:OptIn(ExperimentalMaterial3Api::class)

package com.nodeseek.app.ui.hub

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
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
import com.nodeseek.app.ui.components.CompactCountBadge
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.components.PulseAvatar
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.TurnstileVerificationWidget
import com.nodeseek.app.ui.components.WebViewSessionSheet
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseStaggeredEntrance
import com.nodeseek.app.ui.theme.RankPalette
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseHeroCard
import com.nodeseek.app.ui.theme.withTabularNumbers

/**
 * NodePulse "我的 · Pulse Hub" Dashboard Screen.
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
    onOpenBookmarks: () -> Unit = {},
    onOpenReadHistory: () -> Unit = {},
    onRequestWebViewAuth: (() -> Unit)? = null,
    contentBottomPadding: Dp = 104.dp,
    previewUiState: PulseHubUiState? = null,
    previewRadarUnreadCount: Int? = null,
    previewRadarRules: List<com.nodeseek.app.domain.radar.KeywordWatchRule>? = null,
    previewRadarTrackedTopics: List<com.nodeseek.app.domain.radar.TrackedTopic>? = null,
    previewHasAppUpdate: Boolean? = null,
    viewModel: PulseHubViewModel = viewModel(
        factory = PulseHubViewModel.factory(LocalAppContainer.current),
    ),
) {
    val appContainer = LocalAppContainer.current
    val observedUpdateState by appContainer.appUpdateManager.state.collectAsStateWithLifecycle()
    val hasAppUpdate = previewHasAppUpdate ?: observedUpdateState.hasUpdate
    val observedRadarUnreadCount by appContainer.radarRepository.unreadAlertCountFlow.collectAsStateWithLifecycle(
        initialValue = 0,
    )
    val radarUnreadCount = previewRadarUnreadCount ?: observedRadarUnreadCount
    val observedUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = previewUiState ?: observedUiState
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showInternalWebViewSheet by rememberSaveable { mutableStateOf(false) }

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
    val session = uiState.session
    val effectiveNotificationUnread = if (uiState.unreadNotificationCount > 0) {
        uiState.unreadNotificationCount
    } else {
        (uiState.unreadInboxCount - uiState.unreadDirectMessageCount).coerceAtLeast(0)
    }

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
                            hasAppUpdate = hasAppUpdate,
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(top = 10.dp, bottom = 2.dp),
                        )
                    }

                    // ── Profile Hero Card ──
                    item(key = "hub_hero_bento") {
                        PulseStaggeredEntrance(index = 0) {
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

                    // ── One-Line Check-In Card ──
                    item(key = "hub_checkin_card") {
                        PulseStaggeredEntrance(index = 1) {
                            CompactCheckInCard(
                                isSignedIn = session != null,
                                checkInStatus = uiState.checkInStatus,
                                isLoadingStatus = uiState.isLoadingCheckInStatus,
                                dailyQuota = uiState.dailyQuota,
                                preferRandom = uiState.settings.preferRandomCheckIn,
                                isCheckingIn = uiState.isCheckingIn,
                                onToggleRandomMode = viewModel::setPreferRandomCheckIn,
                                onCheckInClick = {
                                    if (session == null) {
                                        triggerWebViewLogin()
                                    } else {
                                        viewModel.performCheckIn()
                                    }
                                },
                                onOpenBoard = onOpenCheckInBoard,
                            )
                        }
                    }

                    // ── Group 1: 消息 ──
                    item(key = "hub_messages_group") {
                        PulseStaggeredEntrance(index = 2) {
                            HubGroupedSection(
                                title = "消息",
                                rows = listOf(
                                    HubMenuRowItem(
                                        label = "通知",
                                        badgeCount = effectiveNotificationUnread,
                                        onClick = {
                                            if (session != null) onOpenInbox(0) else triggerWebViewLogin()
                                        },
                                    ),
                                    HubMenuRowItem(
                                        label = "私信",
                                        badgeCount = uiState.unreadDirectMessageCount,
                                        onClick = {
                                            if (session != null) onOpenInbox(2) else triggerWebViewLogin()
                                        },
                                    ),
                                    HubMenuRowItem(
                                        label = "关键词提醒",
                                        badgeCount = radarUnreadCount,
                                        onClick = { onOpenRadar(0) },
                                    ),
                                ),
                            )
                        }
                    }

                    // ── Group 2: 内容 ──
                    item(key = "hub_content_group") {
                        PulseStaggeredEntrance(index = 3) {
                            HubGroupedSection(
                                title = "内容",
                                rows = listOf(
                                    HubMenuRowItem(
                                        label = "我的收藏",
                                        plainCount = uiState.bookmarks.size.takeIf { it > 0 }?.toString(),
                                        onClick = onOpenBookmarks,
                                    ),
                                    HubMenuRowItem(
                                        label = "浏览记录",
                                        onClick = onOpenReadHistory,
                                    ),
                                    HubMenuRowItem(
                                        label = "我的帖子",
                                        plainCount = (session?.topicCount ?: 0).takeIf { it > 0 }?.toString(),
                                        onClick = {
                                            if (session != null) {
                                                onOpenUserProfile(session.uid, session.username)
                                            } else {
                                                triggerWebViewLogin()
                                            }
                                        },
                                    ),
                                ),
                            )
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
}

@Composable
private fun PulseHubHeaderBar(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    hasAppUpdate: Boolean = false,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "我的",
            style = NodePulseThemeTokens.extraTypography.heroHeadline,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
        )

        Box(
            modifier = Modifier.size(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "打开设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(23.dp),
                )
            }

            if (hasAppUpdate) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 8.dp)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                        .border(
                            width = 1.5.dp,
                            color = MaterialTheme.colorScheme.background,
                            shape = CircleShape,
                        ),
                )
            }
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
                size = 52.dp,
                rankLevel = session.level,
                ringWidth = 2.2.dp,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = session.username,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val metaText = buildList {
                    add("Lv${session.level}")
                    add("UID ${session.uid}")
                    if (session.isTelegramBound) add("TG 已绑定")
                }.joinToString(" · ")
                Text(
                    text = metaText,
                    style = MaterialTheme.typography.bodySmall.withTabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = "查看个人主页",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(18.dp),
            )
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

        // ── 4-Column Centered Stats Row ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        ) {
            listOf(
                session.drumsticks.toString() to "鸡腿",
                session.stardust.toString() to "星尘",
                session.topicCount.toString() to "主题",
                session.replyCount.toString() to "评论",
            ).forEach { (value, label) ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium.withTabularNumbers(),
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactCheckInCard(
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
    val haptics = LocalHapticFeedback.current
    val hasCheckedIn = isSignedIn && checkInStatus?.hasCheckedInToday == true
    val isSyncingInitialStatus = isSignedIn && checkInStatus == null && isLoadingStatus && !isCheckingIn

    Row(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.ElevatedTile,
                elevation = PulseElevation.Card,
            )
            .then(
                if (isSignedIn) {
                    Modifier.pulseBouncyClickable(
                        role = Role.Button,
                        onClickLabel = "查看签到榜",
                        onClick = onOpenBoard,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (hasCheckedIn) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "今日已签到",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    val todayGain = checkInStatus?.todayGain
                    val todayOrder = checkInStatus?.todayOrder
                    val telemetrySummary = buildList {
                        todayGain?.let { add(if (it >= 0) "+$it 鸡腿" else "$it 鸡腿") }
                        todayOrder?.let { add("第 $it 名") }
                    }.joinToString(" · ")
                    if (telemetrySummary.isNotBlank()) {
                        Text(
                            text = telemetrySummary,
                            style = MaterialTheme.typography.bodySmall.withTabularNumbers(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isSignedIn) {
                        Text(
                            text = "今日未签到",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(0.7.dp, ext.cardBorder, CircleShape)
                                .pulseBouncyClickable(
                                    pressedScale = NodePulseMotion.chipPressScale,
                                    role = Role.Button,
                                    onClickLabel = "切换签到模式",
                                    onClick = { onToggleRandomMode(!preferRandom) },
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = if (preferRandom) "试试手气" else "固定 +5",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    } else {
                        Text(
                            text = "每日签到",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        Text(
                            text = "登录后领取鸡腿",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            val secondaryText = if (dailyQuota != null) {
                "发帖 ${dailyQuota.postBonusEarned}/${dailyQuota.maxPostBonus} · " +
                    "回帖 ${dailyQuota.replyBonusEarned}/${dailyQuota.maxReplyBonus} · " +
                    "加腿 ${dailyQuota.freeDrumsticksUsed}/${dailyQuota.maxFreeDrumsticks}"
            } else if (hasCheckedIn) {
                "共 ${checkInStatus?.totalCheckedInUsers ?: 0} 人已签"
            } else {
                "点击右侧领取今日鸡腿"
            }
            Text(
                text = secondaryText,
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (hasCheckedIn) {
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .clip(CircleShape)
                    .background(ext.cardSurfaceElevated)
                    .border(0.9.dp, ext.cardBorder, CircleShape)
                    .pulseBouncyClickable(
                        pressedScale = NodePulseMotion.chipPressScale,
                        role = Role.Button,
                        onClick = onOpenBoard,
                    )
                    .padding(horizontal = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "签到榜",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        } else {
            val ctaBrush = if (isSignedIn) {
                PulseBrushes.drumstickGoldGradient()
            } else {
                SolidColor(MaterialTheme.colorScheme.primary)
            }
            val ctaContentColor = if (isSignedIn) {
                Color(0xFF1F0E00)
            } else {
                MaterialTheme.colorScheme.onPrimary
            }
            val ctaLabel = when {
                !isSignedIn -> "登录签到"
                isCheckingIn -> "签到中"
                isSyncingInitialStatus -> "同步中"
                else -> "立即签到"
            }
            Row(
                modifier = Modifier
                    .height(32.dp)
                    .clip(CircleShape)
                    .background(ctaBrush)
                    .pulseBouncyClickable(
                        enabled = !isCheckingIn && !isSyncingInitialStatus,
                        pressedScale = NodePulseMotion.chipPressScale,
                        role = Role.Button,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCheckInClick()
                        },
                    )
                    .padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (isCheckingIn || isSyncingInitialStatus) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.8.dp,
                        color = ctaContentColor,
                    )
                }
                Text(
                    text = ctaLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = ctaContentColor,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

private data class HubMenuRowItem(
    val label: String,
    val badgeCount: Int = 0,
    val plainCount: String? = null,
    val onClick: () -> Unit,
)

@Composable
private fun HubGroupedSection(
    title: String,
    rows: List<HubMenuRowItem>,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseCard(
                    shape = PulseArchitecturalShapes.ElevatedTile,
                    elevation = PulseElevation.Card,
                ),
        ) {
            rows.forEachIndexed { index, row ->
                HubGroupedRow(item = row)
                if (index < rows.lastIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(0.6.dp)
                            .background(ext.cardBorder.copy(alpha = 0.65f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun HubGroupedRow(
    item: HubMenuRowItem,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClick = item.onClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (item.badgeCount > 0) {
            CompactCountBadge(
                count = item.badgeCount,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                minHeight = 20.dp,
            )
        } else if (!item.plainCount.isNullOrBlank()) {
            Text(
                text = item.plainCount,
                style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier.size(18.dp),
        )
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
        modifier = Modifier.padding(horizontal = 18.dp),
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
            usePlatformDefaultWidth = false,
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
                    text = "登录受 Cloudflare Turnstile 保护；若下方出现验证框请先完成确认。",
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
