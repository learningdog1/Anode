@file:OptIn(ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.explore

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.digest.AwayDigestState
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.ui.components.CollapsiblePinnedTicker
import com.nodeseek.app.ui.components.PinnedTickerTopic
import com.nodeseek.app.ui.components.PulseDropdownMenu
import com.nodeseek.app.ui.components.PulseDropdownMenuItem
import com.nodeseek.app.ui.components.PulseDropdownMenuSectionHeader
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.PulseTopicCard
import com.nodeseek.app.ui.components.PulseTopicCardModel
import com.nodeseek.app.ui.components.PulseTopicCardSkeleton
import com.nodeseek.app.ui.components.WebViewSessionSheet
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGlassSurface
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.pulseHeroCard
import com.nodeseek.app.ui.theme.pulsePressScale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val DEEP_SCROLL_SNAP_THRESHOLD = 15

private val EXPLORE_SORT_OPTIONS: List<Pair<FeedSort, String>> = listOf(
    FeedSort.LATEST_REPLY to "新评论",
    FeedSort.NEWEST_TOPIC to "新帖子",
    FeedSort.HOT to "热门",
    FeedSort.CURATED to "推荐阅读",
)

/**
 * Native Android Feed Screen (`首页`):
 * - Atmospheric glass header (`Anode` brand lockup + compact search icon + minimal sort selector)
 * - Clean minimalist category text strip synchronized 1:1 with `HorizontalPager` across all boards
 * - Per-board cached slices in ViewModel + compact outlined feed cards
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    onOpenTopic: (topicId: Long, initialFloor: Int, titleHint: String) -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenAuthorProfile: (uid: Long, usernameHint: String) -> Unit = { _, _ -> },
    onRequestWebViewAuth: (() -> Unit)? = null,
    externalCategorySlug: String? = null,
    onExternalCategoryConsumed: () -> Unit = {},
    externalSort: FeedSort? = null,
    onExternalSortConsumed: () -> Unit = {},
    onSelectedCategoryChanged: (String) -> Unit = {},
    refreshTrigger: Long = 0L,
    scrollToTopTrigger: Int = 0,
    onScrollToTopConsumed: () -> Unit = {},
    refreshOnScrollToTop: Boolean = false,
    onScrollDirectionChanged: ((isScrollingUp: Boolean) -> Unit)? = null,
    bottomContentPadding: Dp = 88.dp,
    selectedTopicId: Long? = null,
    listState: LazyListState = rememberLazyListState(),
    previewUiState: ExploreUiState? = null,
    viewModel: ExploreViewModel = viewModel(
        factory = ExploreViewModel.factory(
            container = LocalAppContainer.current,
            initialCategorySlug = externalCategorySlug.orEmpty(),
        )
    ),
) {
    val observedUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = previewUiState ?: observedUiState
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showBuiltInWebViewSheet by rememberSaveable { mutableStateOf(false) }
    var showAwayDigestSheet by rememberSaveable { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    val boardTabs = uiState.orderedBoardTabs
    val selectedTabIndex = remember(boardTabs, uiState.selectedCategorySlug) {
        boardTabs.indexOfFirst { it.slug == uiState.selectedCategorySlug }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(
        initialPage = selectedTabIndex,
        pageCount = { boardTabs.size.coerceAtLeast(1) },
    )
    val visualTabIndex = pagerState.targetPage.coerceIn(0, (boardTabs.size - 1).coerceAtLeast(0))
    val categoryRowState = rememberLazyListState()

    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val activeBoardSlug = boardTabs.getOrNull(visualTabIndex)?.slug.orEmpty()
    val activeCategorySpec = NodePulseThemeTokens.categorySpec(activeBoardSlug)
    val headerNeutralBase = MaterialTheme.colorScheme.background
    val animatedHeaderAccent by animateColorAsState(
        targetValue = if (activeBoardSlug.isBlank()) {
            headerNeutralBase
        } else {
            blendColor(
                source = headerNeutralBase,
                target = activeCategorySpec.spineColor,
                fraction = 0.72f,
            )
        },
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "exploreHeaderAccent",
    )

    val triggerWebViewAuth: () -> Unit = remember(onRequestWebViewAuth) {
        {
            if (onRequestWebViewAuth != null) {
                onRequestWebViewAuth()
            } else {
                showBuiltInWebViewSheet = true
            }
        }
    }

    // Report active category back to host (e.g. so FAB composer defaults to the current board)
    LaunchedEffect(uiState.selectedCategorySlug) {
        onSelectedCategoryChanged(uiState.selectedCategorySlug)
    }

    // Apply external sort selection (e.g. /award curated deep link)
    LaunchedEffect(externalSort) {
        val targetSort = externalSort ?: return@LaunchedEffect
        viewModel.selectSort(targetSort)
        onExternalSortConsumed()
    }

    // Refresh feed when returning from publishing a new topic
    var lastHandledRefreshTrigger by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0L && refreshTrigger != lastHandledRefreshTrigger) {
            lastHandledRefreshTrigger = refreshTrigger
            viewModel.refresh()
        }
    }

    // Synchronize external board selection when navigated from search or deep link
    LaunchedEffect(externalCategorySlug, boardTabs) {
        val targetSlug = externalCategorySlug ?: return@LaunchedEffect
        if (targetSlug != uiState.selectedCategorySlug) {
            viewModel.selectCategory(targetSlug)
        }
        val targetIndex = boardTabs.indexOfFirst { it.slug == targetSlug }
        if (targetIndex >= 0) {
            if (pagerState.currentPage != targetIndex) {
                if (abs(targetIndex - pagerState.currentPage) <= 1) {
                    pagerState.animateScrollToPage(targetIndex)
                } else {
                    pagerState.scrollToPage(targetIndex)
                }
            }
            onExternalCategoryConsumed()
        }
    }

    // Keep active category pill visible inside the horizontal strip when swiping pages
    LaunchedEffect(visualTabIndex, boardTabs.size) {
        if (visualTabIndex in boardTabs.indices) {
            val visibleItems = categoryRowState.layoutInfo.visibleItemsInfo
            val currentItem = visibleItems.firstOrNull { it.index == visualTabIndex }
            val viewportEnd = categoryRowState.layoutInfo.viewportEndOffset
            val needsScroll = currentItem == null ||
                currentItem.offset < 16 ||
                (currentItem.offset + currentItem.size) > (viewportEnd - 16)
            if (needsScroll) {
                categoryRowState.animateScrollToItem((visualTabIndex - 1).coerceAtLeast(0))
            }
        }
    }

    // Notify ViewModel immediately when horizontal swipe commits to a target board tab,
    // re-aligning pager index first if `boardTabs` reordered so tab updates never drift selection.
    val currentSelectedSlug by rememberUpdatedState(uiState.selectedCategorySlug)
    LaunchedEffect(pagerState, boardTabs) {
        if (externalCategorySlug == null) {
            val expectedIndex = boardTabs.indexOfFirst { it.slug == currentSelectedSlug }
            if (expectedIndex >= 0 &&
                pagerState.currentPage != expectedIndex &&
                !pagerState.isScrollInProgress
            ) {
                pagerState.scrollToPage(expectedIndex)
            }
        }
        snapshotFlow { pagerState.targetPage }
            .distinctUntilChanged()
            .collect { targetPage ->
                val targetTab = boardTabs.getOrNull(targetPage) ?: return@collect
                if (targetTab.slug != currentSelectedSlug) {
                    viewModel.selectCategory(targetTab.slug)
                }
            }
    }

    val selectAndScrollToCategory: (String) -> Unit = { slug ->
        val normalized = slug.trim()
        viewModel.selectCategory(normalized)
        val targetIndex = boardTabs.indexOfFirst { it.slug == normalized }
        if (targetIndex >= 0 && targetIndex != pagerState.currentPage) {
            coroutineScope.launch {
                if (abs(targetIndex - pagerState.currentPage) <= 1) {
                    pagerState.animateScrollToPage(targetIndex)
                } else {
                    pagerState.scrollToPage(targetIndex)
                }
            }
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeSnackbar()
    }

    val currentSortLabel = remember(uiState.selectedSort) {
        EXPLORE_SORT_OPTIONS.firstOrNull { it.first == uiState.selectedSort }?.second ?: "新评论"
    }
    val availableSortOptions = remember(uiState.selectedCategorySlug) {
        if (uiState.selectedCategorySlug.isBlank()) {
            EXPLORE_SORT_OPTIONS
        } else {
            EXPLORE_SORT_OPTIONS.filter { it.first != FeedSort.CURATED }
        }
    }

    val currentSortAccent = when (uiState.selectedSort) {
        FeedSort.HOT -> ext.onHeatContainer
        FeedSort.CURATED -> ext.stardustAccent
        else -> MaterialTheme.colorScheme.primary
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            PulseSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = bottomContentPadding),
            )
        },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseGlassSurface(
                        showTopBorder = false,
                        showBottomBorder = true,
                        tonalAccent = animatedHeaderAccent,
                    )
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExploreBrandTitle()

                    Spacer(modifier = Modifier.weight(1f))

                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .pulseBouncyClickable(
                                pressedScale = NodePulseMotion.chipPressScale,
                                role = Role.Button,
                                onClickLabel = "搜索主题与成员",
                                showRipple = false,
                                onClick = onOpenSearch,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "搜索主题",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Box {
                        ExploreSortTrigger(
                            label = currentSortLabel,
                            textColor = if (sortMenuExpanded || uiState.selectedSort != FeedSort.LATEST_REPLY) {
                                currentSortAccent
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            expanded = sortMenuExpanded,
                            onClick = { sortMenuExpanded = true },
                        )

                        PulseDropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false },
                        ) {
                            PulseDropdownMenuSectionHeader(title = "信息流排序")
                            availableSortOptions.forEach { (sortValue, sortLabel) ->
                                val selected = sortValue == uiState.selectedSort
                                val (itemIcon, itemSubtitle) = when (sortValue) {
                                    FeedSort.LATEST_REPLY -> Icons.Rounded.Forum to "最新回复互动优先"
                                    FeedSort.NEWEST_TOPIC -> Icons.Rounded.Bolt to "按发布时间顺序"
                                    FeedSort.HOT -> Icons.Rounded.LocalFireDepartment to "高热讨论排行"
                                    FeedSort.CURATED -> Icons.Rounded.AutoAwesome to "社区精选加分佳作"
                                }
                                val itemAccent: Color? = when (sortValue) {
                                    FeedSort.HOT -> ext.onHeatContainer
                                    FeedSort.CURATED -> ext.stardustAccent
                                    else -> null
                                }
                                PulseDropdownMenuItem(
                                    text = sortLabel,
                                    subtitle = itemSubtitle,
                                    icon = itemIcon,
                                    selected = selected,
                                    accentColor = itemAccent,
                                    onClick = {
                                        sortMenuExpanded = false
                                        viewModel.selectSort(sortValue)
                                    },
                                )
                            }
                        }
                    }
                }

                LazyRow(
                    state = categoryRowState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 1.dp, bottom = 7.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    itemsIndexed(
                        items = boardTabs,
                        key = { _, board -> board.slug.ifBlank { "__all__" } },
                    ) { index, board ->
                        val isSelected = index == visualTabIndex
                        ExploreCategoryTab(
                            board = board,
                            selected = isSelected,
                            onClick = {
                                viewModel.selectCategory(board.slug)
                                coroutineScope.launch {
                                    if (abs(index - pagerState.currentPage) <= 1) {
                                        pagerState.animateScrollToPage(index)
                                    } else {
                                        pagerState.scrollToPage(index)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            key = { page -> boardTabs.getOrNull(page)?.slug ?: "tab_$page" },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) { page ->
            val board = boardTabs.getOrNull(page) ?: Category(slug = "", name = "全部")
            val pageListState = if (page == 0) listState else rememberLazyListState()
            val slice = uiState.sliceFor(board.slug)
            val isActivePage = pagerState.currentPage == page
            val canConsumeScrollToTop = isActivePage &&
                (externalCategorySlug == null || board.slug == externalCategorySlug)

            ExploreBoardFeedPage(
                board = board,
                slice = slice,
                awayDigest = if (board.slug.isBlank()) uiState.awayDigest else AwayDigestState(),
                isAwayDigestExpanded = uiState.isAwayDigestExpanded,
                preferRssFallback = uiState.preferRssFallback,
                isActivePage = isActivePage,
                canConsumeScrollToTop = canConsumeScrollToTop,
                listState = pageListState,
                scrollToTopTrigger = scrollToTopTrigger,
                onScrollToTopConsumed = onScrollToTopConsumed,
                refreshOnScrollToTop = refreshOnScrollToTop,
                bottomContentPadding = bottomContentPadding,
                selectedTopicId = selectedTopicId,
                onScrollDirectionChanged = onScrollDirectionChanged,
                onRefresh = { viewModel.refresh(board.slug) },
                onLoadNextPage = { viewModel.loadNextPage(board.slug) },
                onSelectCategory = selectAndScrollToCategory,
                onBookmarkToggle = viewModel::toggleBookmark,
                onOpenTopic = onOpenTopic,
                onOpenAwayDigestTopic = { digestTopic ->
                    viewModel.onAwayDigestTopicClicked(digestTopic)
                    onOpenTopic(
                        digestTopic.id,
                        digestTopic.lastReadFloor ?: 0,
                        digestTopic.title,
                    )
                },
                onToggleAwayDigestExpanded = viewModel::toggleAwayDigestExpanded,
                onOpenAwayDigestSheet = { showAwayDigestSheet = true },
                onDismissAwayDigest = {
                    showAwayDigestSheet = false
                    viewModel.dismissAwayDigest()
                },
                onOpenAuthorProfile = onOpenAuthorProfile,
                onTriggerWebViewAuth = triggerWebViewAuth,
                onSwitchToFullWebFeed = viewModel::disablePreferRssAndRefresh,
            )
        }
    }

    if (showAwayDigestSheet && uiState.awayDigest.items.isNotEmpty()) {
        AwayDigestBottomSheet(
            digestState = uiState.awayDigest,
            onDismissSheet = { showAwayDigestSheet = false },
            onDismissDigestSession = viewModel::dismissAwayDigest,
            onTopicClick = { digestTopic ->
                viewModel.onAwayDigestTopicClicked(digestTopic)
                onOpenTopic(
                    digestTopic.id,
                    digestTopic.lastReadFloor ?: 0,
                    digestTopic.title,
                )
            },
            onSelectCategory = selectAndScrollToCategory,
        )
    }

    if (showBuiltInWebViewSheet) {
        WebViewSessionSheet(
            onDismiss = { showBuiltInWebViewSheet = false },
            requireSignedInUser = false,
            onSessionVerified = {
                showBuiltInWebViewSheet = false
                viewModel.onWebViewAuthCompleted()
            },
        )
    }
}

@Composable
private fun ExploreBrandTitle(modifier: Modifier = Modifier) {
    val isDark = NodePulseThemeTokens.isDark
    val emblemShape = RoundedCornerShape(8.5.dp)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(emblemShape)
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
                    width = 0.8.dp,
                    color = Color.White.copy(alpha = if (isDark) 0.18f else 0.24f),
                    shape = emblemShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            ExploreAnodeBrandMark(modifier = Modifier.size(20.dp))
        }

        Text(
            text = "Anode",
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.4).sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun ExploreAnodeBrandMark(modifier: Modifier = Modifier) {
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
private fun ExploreSortTrigger(
    label: String,
    textColor: Color,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = NodePulseMotion.spatialFloatSpring(),
        label = "sortMenuChevronRotation",
    )

    Row(
        modifier = modifier
            .height(32.dp)
            .pulsePressScale(
                interactionSource = interactionSource,
                pressedScale = NodePulseMotion.chipPressScale,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = "切换信息流排序",
                onClick = onClick,
            )
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = textColor,
            maxLines = 1,
            softWrap = false,
        )
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = "切换排序",
            tint = textColor,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { rotationZ = chevronRotation },
        )
    }
}

@Composable
private fun ExploreCategoryTab(
    board: Category,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .semantics { this.selected = selected }
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Tab,
                showRipple = false,
                onClick = onClick,
            )
            .padding(vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = board.name,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = if (selected) 14.5.sp else 14.sp,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
            ),
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            softWrap = false,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExploreBoardFeedPage(
    board: Category,
    slice: ExploreBoardSliceUiState,
    awayDigest: AwayDigestState,
    isAwayDigestExpanded: Boolean,
    preferRssFallback: Boolean,
    isActivePage: Boolean,
    canConsumeScrollToTop: Boolean,
    listState: LazyListState,
    scrollToTopTrigger: Int,
    onScrollToTopConsumed: () -> Unit,
    refreshOnScrollToTop: Boolean,
    bottomContentPadding: Dp,
    selectedTopicId: Long?,
    onScrollDirectionChanged: ((isScrollingUp: Boolean) -> Unit)?,
    onRefresh: () -> Unit,
    onLoadNextPage: () -> Unit,
    onSelectCategory: (String) -> Unit,
    onBookmarkToggle: (TopicSummary) -> Unit,
    onOpenTopic: (topicId: Long, initialFloor: Int, titleHint: String) -> Unit,
    onOpenAwayDigestTopic: (TopicSummary) -> Unit,
    onToggleAwayDigestExpanded: () -> Unit,
    onOpenAwayDigestSheet: () -> Unit,
    onDismissAwayDigest: () -> Unit,
    onOpenAuthorProfile: (uid: Long, usernameHint: String) -> Unit,
    onTriggerWebViewAuth: () -> Unit,
    onSwitchToFullWebFeed: () -> Unit,
) {
    val pageScope = rememberCoroutineScope()
    val homeCardScale = NodePulseThemeTokens.homeCardScale
    var scrollToTopJob by remember { mutableStateOf<Job?>(null) }
    val latestOnScrollToTopConsumed by rememberUpdatedState(onScrollToTopConsumed)
    val latestRefreshOnScrollToTop by rememberUpdatedState(refreshOnScrollToTop)
    val latestOnRefresh by rememberUpdatedState(onRefresh)
    val latestIsRefreshing by rememberUpdatedState(slice.isRefreshing || slice.isInitialLoading)

    LaunchedEffect(isActivePage) {
        if (!isActivePage) {
            scrollToTopJob?.cancel()
            scrollToTopJob = null
        }
    }

    LaunchedEffect(scrollToTopTrigger, canConsumeScrollToTop) {
        if (scrollToTopTrigger > 0 && canConsumeScrollToTop) {
            // Capture before consuming: the consumer resets the refresh flag together with the trigger.
            val shouldRefresh = latestRefreshOnScrollToTop
            latestOnScrollToTopConsumed()
            scrollToTopJob?.cancel()
            scrollToTopJob = pageScope.launch {
                if (listState.firstVisibleItemIndex > DEEP_SCROLL_SNAP_THRESHOLD) {
                    listState.scrollToItem(DEEP_SCROLL_SNAP_THRESHOLD)
                }
                listState.animateScrollToItem(0)
                if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                    listState.scrollToItem(0)
                }
                // Re-tapping the home tab: back to top, then refresh (GitHub issue #1).
                if (shouldRefresh && !latestIsRefreshing) {
                    latestOnRefresh()
                }
            }
        }
    }

    if (isActivePage && onScrollDirectionChanged != null) {
        LaunchedEffect(listState, onScrollDirectionChanged) {
            var prevIndex = listState.firstVisibleItemIndex
            var prevOffset = listState.firstVisibleItemScrollOffset
            var lastReportedUp = true
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { (index, offset) ->
                    val nextUp = when {
                        index == 0 && offset < 24 -> true
                        index < prevIndex -> true
                        index > prevIndex -> false
                        offset - prevOffset > 18 -> false
                        prevOffset - offset > 18 -> true
                        else -> lastReportedUp
                    }
                    prevIndex = index
                    prevOffset = offset
                    if (nextUp != lastReportedUp) {
                        lastReportedUp = nextUp
                        onScrollDirectionChanged(nextUp)
                    }
                }
        }
    }

    val shouldLoadMore by remember(listState) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisible >= totalItems - 4
        }
    }
    val filteredPageAutoAdvance = slice.topics.isEmpty() &&
        slice.hasNextPage &&
        !slice.isInitialLoading &&
        !slice.isRefreshing &&
        !slice.isFromRssFallback &&
        !slice.isPreviewFromAllFeed &&
        slice.errorMessage == null &&
        slice.nextPageError == null &&
        slice.currentPage < 3

    LaunchedEffect(
        isActivePage,
        shouldLoadMore,
        filteredPageAutoAdvance,
        slice.hasNextPage,
        slice.isInitialLoading,
        slice.isRefreshing,
        slice.isLoadingNextPage,
        slice.isFromRssFallback,
        slice.isPreviewFromAllFeed,
        slice.nextPageError,
        slice.currentPage,
        slice.topics.size,
    ) {
        if (isActivePage &&
            (shouldLoadMore || filteredPageAutoAdvance) &&
            slice.hasNextPage &&
            !slice.isInitialLoading &&
            !slice.isRefreshing &&
            !slice.isLoadingNextPage &&
            !slice.isFromRssFallback &&
            !slice.isPreviewFromAllFeed &&
            slice.nextPageError == null
        ) {
            onLoadNextPage()
        }
    }

    PullToRefreshBox(
        isRefreshing = slice.isManualRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 4.dp,
                bottom = bottomContentPadding + 12.dp,
            ),
        ) {
            // While-You-Were-Away Top 10 Unread Digest ("你离开了 X 小时")
            if (board.slug.isBlank() && awayDigest.isVisible) {
                item(key = "away_digest_card") {
                    Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                        AwayDigestHeroCard(
                            digestState = awayDigest,
                            isExpanded = isAwayDigestExpanded,
                            onToggleExpanded = onToggleAwayDigestExpanded,
                            onOpenFullSheet = onOpenAwayDigestSheet,
                            onDismiss = onDismissAwayDigest,
                            onTopicClick = onOpenAwayDigestTopic,
                            onSelectCategory = onSelectCategory,
                        )
                    }
                }
            }

            // Compact Collapsible Pinned Ticker (Page 1)
            if (slice.pinnedTopics.isNotEmpty() && slice.sort != FeedSort.CURATED) {
                item(key = "pinned_ticker_${board.slug}") {
                    val tickerItems = remember(slice.pinnedTopics) {
                        slice.pinnedTopics.map {
                            PinnedTickerTopic(
                                id = it.id,
                                title = it.title,
                                categorySlug = it.categorySlug,
                                categoryName = it.categoryName,
                            )
                        }
                    }
                    Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp)) {
                        CollapsiblePinnedTicker(
                            pinnedTopics = tickerItems,
                            onTopicClick = { pinnedId ->
                                val target = slice.pinnedTopics.firstOrNull { it.id == pinnedId }
                                onOpenTopic(
                                    pinnedId,
                                    target?.lastReadFloor ?: 0,
                                    target?.title.orEmpty(),
                                )
                            },
                        )
                    }
                }
            }

            when {
                // 1. Shimmer Skeletons during cold load
                slice.isInitialLoading && slice.topics.isEmpty() -> {
                    items(count = 6, key = { "skeleton_${board.slug}_$it" }) {
                        PulseTopicCardSkeleton(cardScale = homeCardScale)
                    }
                }

                // 2. Error / Cloudflare Challenge Recovery State
                slice.errorMessage != null && slice.topics.isEmpty() -> {
                    item(key = "feed_error_card_${board.slug}") {
                        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            ExploreErrorRecoveryCard(
                                message = slice.errorMessage,
                                isChallengeRequired = slice.isChallengeOrAuthRequired,
                                onRetry = onRefresh,
                                onVerifyWebView = onTriggerWebViewAuth,
                            )
                        }
                    }
                }

                // 3. Empty Feed / RSS Fallback Empty State / Filtered Auto-Advance Loading
                slice.topics.isEmpty() -> {
                    when {
                        slice.isFromRssFallback -> {
                            item(key = "feed_rss_empty_card_${board.slug}") {
                                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    ExploreRssFallbackCard(
                                        boardName = board.name,
                                        isEmptyBoard = true,
                                        isChallengeRequired = slice.isChallengeOrAuthRequired,
                                        preferRssFallback = preferRssFallback,
                                        onVerifyWebView = onTriggerWebViewAuth,
                                        onSwitchToFullWebFeed = onSwitchToFullWebFeed,
                                        onRefresh = onRefresh,
                                    )
                                }
                            }
                        }

                        slice.hasNextPage && (slice.isLoadingNextPage || filteredPageAutoAdvance) -> {
                            item(key = "feed_filtered_loading_${board.slug}") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Text(
                                            text = "正在加载更多匹配主题...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        else -> {
                            item(key = "feed_empty_card_${board.slug}") {
                                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    ExploreEmptyCard(
                                        selectedCategorySlug = board.slug,
                                        onResetCategory = { onSelectCategory("") },
                                        onRefresh = onRefresh,
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. Active Layered Editorial Topic Cards
                else -> {
                    val showCategoryOnRow = board.slug.isBlank()
                    items(
                        items = slice.topics,
                        key = { it.id },
                    ) { topic ->
                        PulseTopicCard(
                            topic = topic.toCardModel(),
                            showCategoryTag = showCategoryOnRow,
                            cardScale = homeCardScale,
                            selectedTopicId = selectedTopicId,
                            onClick = {
                                onOpenTopic(
                                    topic.id,
                                    topic.lastReadFloor ?: 0,
                                    topic.title,
                                )
                            },
                            onBookmarkToggle = { onBookmarkToggle(topic) },
                            onAuthorClick = { authorUid ->
                                onOpenAuthorProfile(authorUid, topic.authorName)
                            },
                            onCategoryClick = { slug ->
                                onSelectCategory(slug)
                            },
                        )
                    }

                    // Next-page loading, retry, RSS fallback recovery, or end-of-feed footer
                    when {
                        slice.isLoadingNextPage -> {
                            item(key = "pagination_loading_${board.slug}") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 14.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    PulseGlassSurface(
                                        shape = CircleShape,
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            Text(
                                                text = "正在加载更多...",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        slice.nextPageError != null -> {
                            item(key = "pagination_error_${board.slug}") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                        .pulseCard(
                                            shape = RoundedCornerShape(14.dp),
                                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.58f),
                                            borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                                            elevation = PulseElevation.Subtle,
                                        )
                                        .clickable { onLoadNextPage() }
                                        .padding(horizontal = 14.dp, vertical = 11.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Refresh,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = slice.nextPageError,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        if (slice.isChallengeOrAuthRequired) {
                                            Button(onClick = onTriggerWebViewAuth, shape = CircleShape) {
                                                Text(text = "完成网页验证", maxLines = 1, softWrap = false)
                                            }
                                        }
                                        OutlinedButton(onClick = onLoadNextPage, shape = CircleShape) {
                                            Text(text = "点按重试", maxLines = 1, softWrap = false)
                                        }
                                    }
                                }
                            }
                        }

                        slice.isFromRssFallback -> {
                            item(key = "pagination_rss_fallback_${board.slug}") {
                                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    ExploreRssFallbackCard(
                                        boardName = board.name,
                                        isEmptyBoard = false,
                                        isChallengeRequired = slice.isChallengeOrAuthRequired,
                                        preferRssFallback = preferRssFallback,
                                        onVerifyWebView = onTriggerWebViewAuth,
                                        onSwitchToFullWebFeed = onSwitchToFullWebFeed,
                                        onRefresh = onRefresh,
                                    )
                                }
                            }
                        }

                        slice.hasNextPage && !slice.isPreviewFromAllFeed -> {
                            item(key = "pagination_load_more_${board.slug}") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                        .clip(CircleShape)
                                        .clickable(onClick = onLoadNextPage)
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "点按加载更多",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }

                        !slice.hasNextPage && !slice.isRefreshing && !slice.isPreviewFromAllFeed -> {
                            item(key = "pagination_end_${board.slug}") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 14.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "已加载全部内容",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (slice.isRefreshing && !slice.isManualRefreshing && slice.topics.isNotEmpty()) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .align(Alignment.TopCenter),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun ExploreErrorRecoveryCard(
    message: String,
    isChallengeRequired: Boolean,
    onRetry: () -> Unit,
    onVerifyWebView: () -> Unit,
) {
    val accent = if (isChallengeRequired) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseHeroCard(
                accentColor = accent,
                shape = PulseArchitecturalShapes.HeroBento,
                elevation = PulseElevation.CardElevated,
            )
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(
                        if (isChallengeRequired) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                    )
                    .border(
                        width = 1.dp,
                        color = accent.copy(alpha = 0.30f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isChallengeRequired) Icons.Rounded.Shield else Icons.Rounded.CloudOff,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Text(
            text = if (isChallengeRequired) "需要完成站点会话验证" else "连接暂时中断",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isChallengeRequired) {
                Button(onClick = onVerifyWebView, shape = CircleShape) {
                    Text(text = "完成网页验证 / 登录", maxLines = 1, softWrap = false)
                }
                OutlinedButton(onClick = onRetry, shape = CircleShape) {
                    Text(text = "重试", maxLines = 1, softWrap = false)
                }
            } else {
                Button(onClick = onRetry, shape = CircleShape) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "重新加载", maxLines = 1, softWrap = false)
                }
                FilledTonalButton(onClick = onVerifyWebView, shape = CircleShape) {
                    Text(text = "网页会话", maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExploreRssFallbackCard(
    boardName: String,
    isEmptyBoard: Boolean,
    isChallengeRequired: Boolean,
    preferRssFallback: Boolean,
    onVerifyWebView: () -> Unit,
    onSwitchToFullWebFeed: () -> Unit,
    onRefresh: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseHeroCard(
                accentColor = accent,
                shape = PulseArchitecturalShapes.HeroBento,
                elevation = PulseElevation.Card,
            )
            .padding(horizontal = 18.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.12f))
                .border(
                    width = 1.dp,
                    color = accent.copy(alpha = 0.25f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isChallengeRequired) Icons.Rounded.Shield else Icons.Rounded.Bolt,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }

        Text(
            text = if (isChallengeRequired) {
                "当前为 RSS 精简模式（需要网页验证）"
            } else {
                "当前处于 RSS 精简模式"
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Text(
            text = if (isEmptyBoard) {
                "RSS 仅包含全站最新约 20 条公开帖子，当前「$boardName」暂无匹配条目且不支持翻页。"
            } else {
                "RSS 仅包含全站最新约 20 条公开帖子（各分区为本地筛选，且不支持下拉翻页加载更多）。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (preferRssFallback && !isChallengeRequired) {
                Button(onClick = onSwitchToFullWebFeed, shape = CircleShape) {
                    Text(text = "切换为完整网页流", maxLines = 1, softWrap = false)
                }
                OutlinedButton(onClick = onVerifyWebView, shape = CircleShape) {
                    Text(text = "网页验证 / 登录", maxLines = 1, softWrap = false)
                }
            } else {
                Button(onClick = onVerifyWebView, shape = CircleShape) {
                    Text(text = "完成网页验证，加载完整内容", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
                if (preferRssFallback) {
                    FilledTonalButton(onClick = onSwitchToFullWebFeed, shape = CircleShape) {
                        Text(text = "关闭 RSS 优先", maxLines = 1, softWrap = false)
                    }
                } else {
                    OutlinedButton(onClick = onRefresh, shape = CircleShape) {
                        Text(text = "重试", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExploreEmptyCard(
    selectedCategorySlug: String,
    onResetCategory: () -> Unit,
    onRefresh: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseHeroCard(
                accentColor = accent,
                shape = PulseArchitecturalShapes.HeroBento,
                elevation = PulseElevation.Card,
            )
            .padding(26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(PulseBrushes.brandSoftWash(0.14f))
                .border(
                    width = 1.dp,
                    color = accent.copy(alpha = 0.24f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Forum,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            text = "当前版块暂无内容",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "尝试左右滑动切换版块，或下拉刷新获取最新讨论。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (selectedCategorySlug.isNotBlank()) {
                FilledTonalButton(onClick = onResetCategory, shape = CircleShape) {
                    Text(text = "返回全部", maxLines = 1, softWrap = false)
                }
            }
            OutlinedButton(onClick = onRefresh, shape = CircleShape) {
                Text(text = "刷新", maxLines = 1, softWrap = false)
            }
        }
    }
}

internal fun TopicSummary.toCardModel(): PulseTopicCardModel = PulseTopicCardModel(
    id = id,
    title = title,
    categorySlug = categorySlug,
    categoryName = categoryName,
    authorId = authorId,
    authorName = authorName,
    authorAvatarUrl = authorAvatarUrl,
    authorLevel = authorLevel,
    viewCount = viewCount,
    replyCount = replyCount,
    lastActivityIso = lastActivityIso,
    isPinned = isPinned,
    requiredViewLevel = requiredViewLevel,
    isRead = isRead,
    lastReadFloor = lastReadFloor,
    isBookmarked = isBookmarked,
    isHot = isHot,
)
