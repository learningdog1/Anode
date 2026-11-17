@file:OptIn(ExperimentalMaterial3Api::class)

package com.nodeseek.app.ui.hub

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.data.parser.NodeSeekHtmlParser
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.DirectMessageSummary
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.NotificationItem
import com.nodeseek.app.ui.components.FloorNumberPill
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.components.PulseAvatar
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.RelativeTimeText
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseShimmerBox
import com.nodeseek.app.ui.theme.PulseStaggeredEntrance
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun InboxScreen(
    onBack: () -> Unit,
    onOpenTopic: (topicId: Long, initialFloor: Int, titleHint: String) -> Unit,
    onOpenDirectMessage: (peerUid: Long, peerName: String) -> Unit,
    onOpenUserProfile: (uid: Long, usernameHint: String) -> Unit,
    modifier: Modifier = Modifier,
    initialTab: Int = 0,
    onOpenRadar: (initialTab: Int) -> Unit = {},
    onOpenSeekMail: () -> Unit = {},
    previewUiState: InboxUiState? = null,
    previewHasSeekMailKey: Boolean? = null,
    previewRadarUnreadCount: Int? = null,
    viewModel: InboxViewModel = viewModel(
        factory = InboxViewModel.factory(LocalAppContainer.current, initialTab),
    ),
) {
    val appContainer = LocalAppContainer.current
    val settings by appContainer.settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = AppSettings(),
    )
    val hasSeekMailKey = previewHasSeekMailKey ?: settings.seekMailApiKey.isNotBlank()
    val observedRadarUnreadCount by appContainer.radarRepository.unreadAlertCountFlow.collectAsStateWithLifecycle(
        initialValue = 0,
    )
    val radarUnreadCount = previewRadarUnreadCount ?: observedRadarUnreadCount
    val radarRules by appContainer.radarRepository.keywordRulesFlow.collectAsStateWithLifecycle(
        initialValue = emptyList(),
    )
    val radarTrackedTopics by appContainer.radarRepository.trackedTopicsFlow.collectAsStateWithLifecycle(
        initialValue = emptyList(),
    )
    val observedUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = previewUiState ?: observedUiState
    val snackbarHostState = remember { SnackbarHostState() }
    val initialTabIndex = previewUiState?.selectedTab?.index
        ?: initialTab.coerceIn(0, InboxTab.entries.lastIndex)
    val pagerState = rememberPagerState(
        initialPage = initialTabIndex,
        pageCount = { InboxTab.entries.size },
    )
    val activeTab = previewUiState?.selectedTab ?: InboxTab.fromIndex(pagerState.targetPage)
    val coroutineScope = rememberCoroutineScope()
    val isDark = NodePulseThemeTokens.isDark
    val ext = NodePulseThemeTokens.extendedColors

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val tab = InboxTab.fromIndex(page)
                if (viewModel.uiState.value.selectedTab != tab) {
                    viewModel.selectTab(tab)
                }
            }
    }

    var lastHandledInitialTab by rememberSaveable { mutableIntStateOf(initialTab) }
    LaunchedEffect(initialTab) {
        if (initialTab != lastHandledInitialTab) {
            lastHandledInitialTab = initialTab
            val targetTab = InboxTab.fromIndex(initialTab)
            viewModel.selectTab(targetTab)
            pagerState.animateScrollToPage(targetTab.index)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var hasSeenInitialResume by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (hasSeenInitialResume) {
                    viewModel.refreshSilentlyOnResume()
                } else {
                    hasSeenInitialResume = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val totalUnread = uiState.totalUnreadCount

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseGlassSurface(
                        showTopBorder = false,
                        showBottomBorder = true,
                        tonalAccent = MaterialTheme.colorScheme.primary,
                    ),
            ) {
                TopAppBar(
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = "消息与私信",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (totalUnread > 0) {
                                    "共 $totalUnread 条未读消息"
                                } else {
                                    "全部消息已读"
                                },
                                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (totalUnread > 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    },
                    actions = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(end = 10.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(
                                        if (radarUnreadCount > 0) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                    )
                                    .border(
                                        width = 0.8.dp,
                                        color = if (radarUnreadCount > 0) {
                                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)
                                        } else {
                                            ext.cardBorder
                                        },
                                        shape = CircleShape,
                                    )
                                    .pulseBouncyClickable(
                                        pressedScale = NodePulseMotion.chipPressScale,
                                        role = Role.Button,
                                        onClick = { onOpenRadar(0) },
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Radar,
                                    contentDescription = "打开脉搏雷达",
                                    tint = if (radarUnreadCount > 0) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(
                                    text = if (radarUnreadCount > 0) {
                                        "雷达 ${formatInboxBadgeCount(radarUnreadCount)}"
                                    } else {
                                        "雷达"
                                    },
                                    style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false,
                                    color = if (radarUnreadCount > 0) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer.copy(
                                            alpha = if (isDark) 0.65f else 0.75f,
                                        ),
                                    )
                                    .border(
                                        width = 0.8.dp,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                        shape = CircleShape,
                                    )
                                    .pulseBouncyClickable(
                                        enabled = !uiState.isMarkingAllRead,
                                        pressedScale = NodePulseMotion.chipPressScale,
                                        role = Role.Button,
                                        onClick = viewModel::markCurrentTabAllRead,
                                    )
                                    .padding(horizontal = 11.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                if (uiState.isMarkingAllRead) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.DoneAll,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                                Text(
                                    text = "全部已读",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                )

                // Segmented Pill Tab Switcher synced with HorizontalPager
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f))
                        .border(0.8.dp, ext.cardBorder, CircleShape)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    InboxTab.entries.forEach { tab ->
                        val unreadBadge = when (tab) {
                            InboxTab.REPLIES -> uiState.unreadRepliesCount
                            InboxTab.MENTIONS -> uiState.unreadMentionsCount
                            InboxTab.DIRECT_MESSAGES -> uiState.unreadMessagesCount
                        }
                        val tabIcon = when (tab) {
                            InboxTab.REPLIES -> Icons.Rounded.ChatBubbleOutline
                            InboxTab.MENTIONS -> Icons.Rounded.AlternateEmail
                            InboxTab.DIRECT_MESSAGES -> Icons.Rounded.Mail
                        }
                        InboxSegmentedTabPill(
                            label = tab.label,
                            icon = tabIcon,
                            unreadCount = unreadBadge,
                            isSelected = activeTab == tab,
                            onClick = {
                                viewModel.selectTab(tab)
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(tab.index)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (activeTab == InboxTab.DIRECT_MESSAGES) {
                val primaryColor = MaterialTheme.colorScheme.primary
                val fabGradient = Brush.linearGradient(
                    colors = listOf(
                        primaryColor,
                        blendColor(primaryColor, MaterialTheme.colorScheme.secondary, 0.35f),
                    ),
                )
                Box(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .size(56.dp)
                        .then(
                            if (!isDark) {
                                Modifier.shadow(
                                    elevation = PulseElevation.FloatingBar,
                                    shape = PulseArchitecturalShapes.SquircleFab,
                                    clip = false,
                                    ambientColor = primaryColor.copy(alpha = 0.32f),
                                    spotColor = primaryColor.copy(alpha = 0.32f),
                                )
                            } else {
                                Modifier
                            },
                        )
                        .clip(PulseArchitecturalShapes.SquircleFab)
                        .background(fabGradient)
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = if (isDark) 0.20f else 0.25f),
                            shape = PulseArchitecturalShapes.SquircleFab,
                        )
                        .pulseBouncyClickable(
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.Button,
                            onClickLabel = "发起新私信",
                            onClick = viewModel::openNewMessageDialog,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "发起新私信",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(23.dp),
                    )
                }
            }
        },
        snackbarHost = {
            PulseSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refreshAllTabs,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                val enabledRuleCount = radarRules.count { it.isEnabled }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp)
                        .pulseCard(
                            accentColor = if (radarUnreadCount > 0) MaterialTheme.colorScheme.primary else null,
                        )
                        .pulseBouncyClickable(onClick = { onOpenRadar(0) })
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(PulseArchitecturalShapes.SmallChip)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Radar,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "关键词雷达与追更提醒",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (radarUnreadCount > 0) {
                                PulseAccentPill(
                                    text = "${formatInboxBadgeCount(radarUnreadCount)} 未读",
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                        Text(
                            text = "${enabledRuleCount} 条关键词监听 · ${radarTrackedTopics.size} 帖追更 · 离线/未登录均可查看",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                        .pulseCard(
                            accentColor = if (hasSeekMailKey) MaterialTheme.colorScheme.secondary else null,
                        )
                        .pulseBouncyClickable(
                            role = Role.Button,
                            onClickLabel = "打开 Seek 邮箱",
                            onClick = onOpenSeekMail,
                        )
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(PulseArchitecturalShapes.SmallChip)
                            .background(
                                if (hasSeekMailKey) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AlternateEmail,
                            contentDescription = null,
                            tint = if (hasSeekMailKey) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            text = "Seek 邮箱 · seek.li",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (hasSeekMailKey) {
                                "查收邮箱邮件、自动提取验证码与管理别名"
                            } else {
                                "尚未配置 API Key · 点击快速接入 seek.li 邮箱"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    PulseAccentPill(
                        text = if (hasSeekMailKey) "已配置" else "未配置",
                        containerColor = if (hasSeekMailKey) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        contentColor = if (hasSeekMailKey) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = "打开 Seek 邮箱",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) { page ->
                when (InboxTab.entries.getOrElse(page) { InboxTab.REPLIES }) {
                    InboxTab.REPLIES -> NotificationListContent(
                        items = uiState.replies,
                        isRefreshing = uiState.isRefreshing,
                        errorMessage = uiState.errorMessage,
                        emptyIcon = Icons.Rounded.ChatBubbleOutline,
                        emptyTitle = "暂无新的楼层回复",
                        emptySubtitle = "当其他社区成员在主题下回复您时，消息会实时汇总在这里。",
                        kindLabel = "回复",
                        onItemClick = { item ->
                            viewModel.markNotificationRead(item)
                            onOpenTopic(item.topicId, item.floorNumber, item.topicTitle)
                        },
                        onAuthorClick = onOpenUserProfile,
                    )

                    InboxTab.MENTIONS -> NotificationListContent(
                        items = uiState.mentions,
                        isRefreshing = uiState.isRefreshing,
                        errorMessage = uiState.errorMessage,
                        emptyIcon = Icons.Rounded.AlternateEmail,
                        emptyTitle = "暂无 @提及 消息",
                        emptySubtitle = "当有人在帖子或评论中 @ 您的用户名时，点击即可直达对应楼层。",
                        kindLabel = "提及",
                        onItemClick = { item ->
                            viewModel.markNotificationRead(item)
                            onOpenTopic(item.topicId, item.floorNumber, item.topicTitle)
                        },
                        onAuthorClick = onOpenUserProfile,
                    )

                    InboxTab.DIRECT_MESSAGES -> DirectMessageThreadListContent(
                        threads = uiState.messageThreads,
                        isRefreshing = uiState.isRefreshing,
                        errorMessage = uiState.errorMessage,
                        onThreadClick = { thread ->
                            val displayName = thread.peerName.ifBlank {
                                if (thread.isSystem || thread.peerUid <= 0L) "系统通知" else "用户 #${thread.peerUid}"
                            }
                            viewModel.markThreadRead(thread.peerUid)
                            onOpenDirectMessage(thread.peerUid, displayName)
                        },
                        onAvatarClick = onOpenUserProfile,
                    )
                }
            }
            }
        }
    }

    if (uiState.showNewMessageDialog) {
        NewDirectMessageDialog(
            results = uiState.userSearchResults,
            isSearching = uiState.isSearchingUsers,
            onSearchChange = viewModel::searchUsers,
            onSelectUser = { user ->
                viewModel.dismissNewMessageDialog()
                onOpenDirectMessage(user.uid, user.username)
            },
            onDismiss = viewModel::dismissNewMessageDialog,
        )
    }
}

@Composable
private fun InboxSegmentedTabPill(
    label: String,
    icon: ImageVector,
    unreadCount: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        label = "inboxTabBg",
    )
    val fg by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "inboxTabFg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        } else {
            Color.Transparent
        },
        label = "inboxTabBorder",
    )

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .border(0.8.dp, borderColor, CircleShape)
            .semantics { selected = isSelected }
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(vertical = 8.dp, horizontal = 5.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (unreadCount > 0) {
            Spacer(modifier = Modifier.width(4.dp))
            InboxUnreadCountBadge(count = unreadCount)
        }
    }
}

private fun formatInboxBadgeCount(count: Int): String =
    if (count > 99) "99+" else count.coerceAtLeast(0).toString()

@Composable
private fun InboxUnreadCountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    if (count <= 0) return
    val text = formatInboxBadgeCount(count)
    val horizontalPadding = if (count < 10) 0.dp else 5.5.dp
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .clip(CircleShape)
            .background(containerColor)
            .padding(horizontal = horizontalPadding, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = NodePulseThemeTokens.extraTypography.tabularBadge,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NotificationListContent(
    items: List<NotificationItem>,
    isRefreshing: Boolean,
    errorMessage: String?,
    emptyIcon: ImageVector,
    emptyTitle: String,
    emptySubtitle: String,
    kindLabel: String,
    onItemClick: (NotificationItem) -> Unit,
    onAuthorClick: (uid: Long, usernameHint: String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (isRefreshing && items.isEmpty()) {
            items(count = 4, key = { "notif_skel_$it" }) {
                InboxRowSkeletonCard()
            }
        } else if (items.isEmpty()) {
            item(key = "notif_empty") {
                HubEmptyPlaceholderCard(
                    icon = emptyIcon,
                    title = if (errorMessage != null) "加载失败" else emptyTitle,
                    subtitle = errorMessage ?: emptySubtitle,
                )
            }
        } else {
            itemsIndexed(
                items = items,
                key = { _, item -> "${item.kind.name}_${item.id}" },
            ) { index, item ->
                PulseStaggeredEntrance(
                    index = index,
                    enabled = index < 8,
                ) {
                    NotificationCard(
                        item = item,
                        kindLabel = kindLabel,
                        onClick = { onItemClick(item) },
                        onAuthorClick = { onAuthorClick(item.senderUid, item.senderName) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(
    item: NotificationItem,
    kindLabel: String,
    onClick: () -> Unit,
    onAuthorClick: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val primaryColor = MaterialTheme.colorScheme.primary
    val cleanExcerpt = remember(item.excerpt) {
        item.excerpt?.let(NodeSeekHtmlParser::stripMarkdownToPlainPreview).orEmpty()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = if (!item.isRead) ext.cardSurfaceElevated else ext.cardSurface,
                elevation = if (!item.isRead) PulseElevation.CardElevated else PulseElevation.Card,
                accentColor = if (!item.isRead) primaryColor else null,
            )
            .pulseBouncyClickable(
                role = Role.Button,
                onClick = onClick,
            )
            .drawWithContent {
                drawContent()
                if (!item.isRead) {
                    val barW = 3.dp.toPx()
                    val vPad = 14.dp.toPx()
                    if (size.height > vPad * 2f) {
                        val r = 2.dp.toPx()
                        drawRoundRect(
                            color = primaryColor,
                            topLeft = Offset(0f, vPad),
                            size = Size(barW, size.height - vPad * 2f),
                            cornerRadius = CornerRadius(r, r),
                        )
                    }
                }
            }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PulseAvatar(
            avatarUrl = item.senderAvatarUrl,
            username = item.senderName,
            size = 40.dp,
            onClick = onAuthorClick,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!item.isRead) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(primaryColor),
                        )
                    }
                    Text(
                        text = item.senderName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer.copy(
                                    alpha = if (isDark) 0.55f else 0.70f,
                                ),
                            )
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = kindLabel,
                            style = NodePulseThemeTokens.extraTypography.microLabel,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    RelativeTimeText(isoTimestamp = item.createdAtIso)
                }
                if (item.floorNumber > 0) {
                    FloorNumberPill(floorNumber = item.floorNumber)
                }
            }

            Text(
                text = item.topicTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (!item.isRead) FontWeight.SemiBold else FontWeight.Normal,
                color = if (!item.isRead) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (cleanExcerpt.isNotBlank()) {
                val quoteBarColor = if (!item.isRead) {
                    primaryColor.copy(alpha = 0.75f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isDark) Color.White.copy(alpha = 0.04f)
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                        )
                        .drawBehind {
                            drawRect(
                                color = quoteBarColor,
                                topLeft = Offset.Zero,
                                size = Size(2.5.dp.toPx(), size.height),
                            )
                        }
                        .padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                ) {
                    Text(
                        text = cleanExcerpt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectMessageThreadListContent(
    threads: List<DirectMessageSummary>,
    isRefreshing: Boolean,
    errorMessage: String?,
    onThreadClick: (DirectMessageSummary) -> Unit,
    onAvatarClick: (uid: Long, usernameHint: String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 92.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (isRefreshing && threads.isEmpty()) {
            items(count = 4, key = { "dm_skel_$it" }) {
                InboxRowSkeletonCard()
            }
        } else if (threads.isEmpty()) {
            item(key = "dm_empty") {
                HubEmptyPlaceholderCard(
                    icon = Icons.Rounded.Mail,
                    title = if (errorMessage != null) "加载失败" else "暂无站内私信会话",
                    subtitle = errorMessage ?: "点击右下角按钮搜索成员用户名即可发起一对一私信。",
                )
            }
        } else {
            itemsIndexed(
                items = threads,
                key = { _, thread -> "dm_peer_${thread.peerUid}" },
            ) { index, thread ->
                PulseStaggeredEntrance(
                    index = index,
                    enabled = index < 8,
                ) {
                    DirectMessageThreadCard(
                        thread = thread,
                        onClick = { onThreadClick(thread) },
                        onAvatarClick = {
                            if (thread.isSystem || thread.peerUid <= 0L) {
                                onThreadClick(thread)
                            } else {
                                onAvatarClick(thread.peerUid, thread.peerName)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectMessageThreadCard(
    thread: DirectMessageSummary,
    onClick: () -> Unit,
    onAvatarClick: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isSystemThread = thread.isSystem || thread.peerUid <= 0L
    val displayName = thread.peerName.ifBlank {
        if (isSystemThread) "系统通知" else "用户 #${thread.peerUid}"
    }
    val accentColor = if (isSystemThread) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondary
    }
    val preview = remember(thread.previewText) {
        NodeSeekHtmlParser.stripMarkdownToPlainPreview(thread.previewText)
            .ifBlank { "[无正文预览]" }
    }
    val threadUnreadCount = if (thread.isRead) 0 else thread.unreadCount.coerceAtLeast(1)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = if (!thread.isRead) ext.cardSurfaceElevated else ext.cardSurface,
                elevation = if (!thread.isRead) PulseElevation.CardElevated else PulseElevation.Card,
                accentColor = if (!thread.isRead) accentColor else null,
            )
            .pulseBouncyClickable(
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            if (isSystemThread) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                            shape = CircleShape,
                        )
                        .pulseBouncyClickable(
                            role = Role.Button,
                            onClick = onAvatarClick,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.NotificationsActive,
                        contentDescription = displayName,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            } else {
                PulseAvatar(
                    avatarUrl = thread.peerAvatarUrl,
                    username = displayName,
                    size = 46.dp,
                    onClick = onAvatarClick,
                )
            }
            if (!thread.isRead) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(2.dp, ext.cardSurfaceElevated, CircleShape),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isSystemThread) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = "系统",
                                style = NodePulseThemeTokens.extraTypography.microLabel,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                    if (thread.isMarkdown) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = "MD",
                                style = NodePulseThemeTokens.extraTypography.microLabel,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (threadUnreadCount > 0) {
                        InboxUnreadCountBadge(count = threadUnreadCount)
                    }
                    RelativeTimeText(isoTimestamp = thread.updatedAtIso)
                }
            }

            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (!thread.isRead) FontWeight.SemiBold else FontWeight.Normal,
                color = if (!thread.isRead) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun InboxRowSkeletonCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(shape = PulseArchitecturalShapes.EditorialCard)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulseShimmerBox(
            modifier = Modifier.size(42.dp),
            shape = CircleShape,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PulseShimmerBox(
                    modifier = Modifier
                        .width(96.dp)
                        .height(14.dp),
                    shape = RoundedCornerShape(5.dp),
                )
                PulseShimmerBox(
                    modifier = Modifier
                        .width(44.dp)
                        .height(12.dp),
                    shape = RoundedCornerShape(5.dp),
                )
            }
            PulseShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .height(15.dp),
                shape = RoundedCornerShape(5.dp),
            )
            PulseShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .height(12.dp),
                shape = RoundedCornerShape(5.dp),
            )
        }
    }
}

@Composable
private fun NewDirectMessageDialog(
    results: List<FloorAuthor>,
    isSearching: Boolean,
    onSearchChange: (String) -> Unit,
    onSelectUser: (FloorAuthor) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }

    PulseAlertDialog(
        onDismissRequest = onDismiss,
        title = "发起站内私信",
        icon = Icons.Rounded.Mail,
        tone = PulseDialogTone.PRIMARY,
        confirmText = "关闭",
        onConfirm = onDismiss,
        useTonalConfirm = true,
        dismissText = null,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    onSearchChange(it)
                },
                placeholder = { Text("输入成员用户名搜索…") },
                leadingIcon = {
                    Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
                },
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (isSearching) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            } else if (results.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(results, key = { it.uid }) { user ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pulseCard(
                                    shape = RoundedCornerShape(12.dp),
                                    elevation = PulseElevation.Subtle,
                                )
                                .pulseBouncyClickable(
                                    pressedScale = NodePulseMotion.cardPressScale,
                                    role = Role.Button,
                                    onClick = { onSelectUser(user) },
                                )
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PulseAvatar(
                                avatarUrl = user.avatarUrl,
                                username = user.username,
                                size = 32.dp,
                            )
                            Text(
                                text = user.username,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "UID #${user.uid}",
                                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else if (query.isNotBlank()) {
                Text(
                    text = "未找到匹配的社区成员",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}
