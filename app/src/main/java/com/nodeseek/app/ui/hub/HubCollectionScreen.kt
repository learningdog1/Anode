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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.ui.components.PulseConfirmDialog
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.PulseTopicCard
import com.nodeseek.app.ui.components.PulseTopicCardModel
import com.nodeseek.app.ui.components.formatCompactCount
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.withTabularNumbers
import java.time.Instant

/**
 * Dedicated screen for managing offline/server Bookmarks and floor-resuming Read History.
 */
@Composable
fun HubCollectionScreen(
    initialTab: Int = 0,
    onBack: () -> Unit,
    onOpenTopic: (topicId: Long, initialFloor: Int, titleHint: String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenAuthorProfile: ((uid: Long, usernameHint: String) -> Unit)? = null,
    onOpenCategory: ((slug: String) -> Unit)? = null,
    previewUiState: PulseHubUiState? = null,
    viewModel: PulseHubViewModel = viewModel(
        factory = PulseHubViewModel.factory(LocalAppContainer.current),
    ),
) {
    val observedUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = previewUiState ?: observedUiState
    val snackbarHostState = remember { SnackbarHostState() }
    val bookmarkedIds = remember(uiState.bookmarks) {
        uiState.bookmarks.map { it.topicId }.toSet()
    }
    var showClearHistoryConfirm by rememberSaveable { mutableStateOf(false) }
    var appliedInitialTab by rememberSaveable { mutableStateOf<Int?>(null) }

    LaunchedEffect(initialTab, viewModel) {
        if (appliedInitialTab != initialTab) {
            appliedInitialTab = initialTab
            viewModel.selectCollectionTab(
                if (initialTab == 1) HubCollectionTab.HISTORY else HubCollectionTab.BOOKMARKS,
            )
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = "收藏与足迹",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                        )
                        Text(
                            text = "${formatCompactCount(uiState.bookmarks.size)} 篇收藏 · ${formatCompactCount(uiState.readHistory.size)} 条浏览",
                            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
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
                    if (uiState.selectedTab == HubCollectionTab.HISTORY && uiState.readHistory.isNotEmpty()) {
                        IconButton(onClick = { showClearHistoryConfirm = true }) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = "清空记录",
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
                modifier = Modifier.pulseGlassSurface(
                    showTopBorder = false,
                    showBottomBorder = true,
                    tonalAccent = MaterialTheme.colorScheme.primary,
                ),
            )
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
            onRefresh = viewModel::refreshAll,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 12.dp,
                    bottom = 28.dp,
                ),
            ) {
                item(key = "collections_tabs") {
                    HubCollectionsTabHeader(
                        selectedTab = uiState.selectedTab,
                        bookmarkCount = uiState.bookmarks.size,
                        historyCount = uiState.readHistory.size,
                        onSelectTab = viewModel::selectCollectionTab,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                when (uiState.selectedTab) {
                    HubCollectionTab.BOOKMARKS -> {
                        if (uiState.bookmarks.isEmpty() && !uiState.isLoadingServerBookmarks) {
                            item(key = "bookmarks_empty") {
                                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                                    HubEmptyPlaceholderCard(
                                        icon = Icons.Rounded.Bookmark,
                                        title = "暂无收藏主题",
                                        subtitle = "浏览帖子时点按右上角书签即可加入收藏；本地将自动保存离线快照以便无网翻阅。",
                                    )
                                }
                            }
                        } else if (uiState.bookmarks.isEmpty() && uiState.isLoadingServerBookmarks) {
                            item(key = "bookmarks_loading") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        strokeWidth = 2.6.dp,
                                    )
                                }
                            }
                        } else {
                            items(
                                items = uiState.bookmarks,
                                key = { item -> "bm_${item.topicId}" },
                            ) { item ->
                                val summary = item.summary
                                val resolvedIso = remember(summary.lastActivityIso, item.savedAtEpochMillis) {
                                    summary.lastActivityIso.ifBlank {
                                        item.savedAtEpochMillis?.let { Instant.ofEpochMilli(it).toString() }.orEmpty()
                                    }
                                }
                                val cardModel = PulseTopicCardModel(
                                    id = summary.id,
                                    title = summary.title,
                                    categorySlug = summary.categorySlug,
                                    categoryName = summary.categoryName,
                                    authorId = summary.authorId,
                                    authorName = summary.authorName,
                                    authorAvatarUrl = summary.authorAvatarUrl,
                                    authorLevel = summary.authorLevel,
                                    viewCount = summary.viewCount,
                                    replyCount = summary.replyCount,
                                    lastActivityIso = resolvedIso,
                                    isPinned = summary.isPinned,
                                    requiredViewLevel = summary.requiredViewLevel,
                                    isRead = false,
                                    lastReadFloor = summary.lastReadFloor,
                                    isBookmarked = true,
                                    isHot = summary.isHot,
                                )
                                PulseTopicCard(
                                    topic = cardModel,
                                    showCategoryTag = true,
                                    onClick = {
                                        val floor = summary.lastReadFloor ?: 0
                                        onOpenTopic(item.topicId, floor, summary.title)
                                    },
                                    onBookmarkToggle = { viewModel.removeBookmark(item.topicId) },
                                    onAuthorClick = if (onOpenAuthorProfile != null) {
                                        { authorUid ->
                                            onOpenAuthorProfile(authorUid, summary.authorName)
                                        }
                                    } else {
                                        null
                                    },
                                    onCategoryClick = if (onOpenCategory != null && summary.categorySlug.isNotBlank()) {
                                        { slug -> onOpenCategory(slug) }
                                    } else {
                                        null
                                    },
                                )
                            }

                            if (uiState.hasMoreServerBookmarks || uiState.isLoadingServerBookmarks) {
                                item(key = "bookmarks_load_more") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
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
                                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                                    HubEmptyPlaceholderCard(
                                        icon = Icons.Rounded.History,
                                        title = "暂无浏览记录",
                                        subtitle = "您阅读过的主题与上次停留楼层会保存在设备本地，点击即可一键续读。",
                                    )
                                }
                            }
                        } else {
                            items(
                                items = uiState.readHistory,
                                key = { entry -> "hist_${entry.topicId}" },
                            ) { entry ->
                                val visitedIso = remember(entry.visitedAtEpochMillis) {
                                    Instant.ofEpochMilli(entry.visitedAtEpochMillis).toString()
                                }
                                val resumeLabel = if (entry.lastReadFloor > 0) {
                                    "续读 #${entry.lastReadFloor} 楼"
                                } else {
                                    "重新打开"
                                }
                                val cardModel = PulseTopicCardModel(
                                    id = entry.topicId,
                                    title = entry.title,
                                    categorySlug = entry.categorySlug,
                                    categoryName = entry.categoryName,
                                    authorName = entry.authorName,
                                    authorAvatarUrl = entry.authorAvatarUrl,
                                    lastActivityIso = visitedIso,
                                    isRead = false,
                                    lastReadFloor = entry.lastReadFloor.takeIf { it > 0 },
                                    isBookmarked = entry.topicId in bookmarkedIds,
                                )
                                PulseTopicCard(
                                    topic = cardModel,
                                    showCategoryTag = true,
                                    showEngagementMetrics = false,
                                    resumeBadgeTextOverride = resumeLabel,
                                    onClick = {
                                        onOpenTopic(
                                            entry.topicId,
                                            entry.lastReadFloor,
                                            entry.title,
                                        )
                                    },
                                    onBookmarkToggle = { viewModel.toggleHistoryBookmark(entry) },
                                    onAuthorClick = if (onOpenAuthorProfile != null && entry.authorName.isNotBlank()) {
                                        { _ -> onOpenAuthorProfile(0L, entry.authorName) }
                                    } else {
                                        null
                                    },
                                    onCategoryClick = if (onOpenCategory != null && entry.categorySlug.isNotBlank()) {
                                        { slug -> onOpenCategory(slug) }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
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
private fun HubCollectionsTabHeader(
    selectedTab: HubCollectionTab,
    bookmarkCount: Int,
    historyCount: Int,
    onSelectTab: (HubCollectionTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors

    Row(
        modifier = modifier
            .fillMaxWidth()
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
