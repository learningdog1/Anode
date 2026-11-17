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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkRemove
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.Schedule
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.ui.components.CategoryMicroTag
import com.nodeseek.app.ui.components.PulseAvatar
import com.nodeseek.app.ui.components.PulseConfirmDialog
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.formatCompactCount
import com.nodeseek.app.ui.components.formatRelativeTimeChinese
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseStaggeredEntrance
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
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
    previewUiState: PulseHubUiState? = null,
    viewModel: PulseHubViewModel = viewModel(
        factory = PulseHubViewModel.factory(LocalAppContainer.current),
    ),
) {
    val observedUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = previewUiState ?: observedUiState
    val snackbarHostState = remember { SnackbarHostState() }
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
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "collections_tabs") {
                    HubCollectionsTabHeader(
                        selectedTab = uiState.selectedTab,
                        bookmarkCount = uiState.bookmarks.size,
                        historyCount = uiState.readHistory.size,
                        onSelectTab = viewModel::selectCollectionTab,
                    )
                }

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
