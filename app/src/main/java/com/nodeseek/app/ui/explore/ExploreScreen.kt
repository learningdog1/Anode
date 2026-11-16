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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SwapVert
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

private val EXPLORE_SORT_OPTIONS: List<Pair<FeedSort, String>> = listOf(
    FeedSort.LATEST_REPLY to "新评论",
    FeedSort.NEWEST_TOPIC to "新帖子",
    FeedSort.HOT to "热门",
    FeedSort.CURATED to "推荐阅读",
)

/**
 * Native Android Feed Screen (`首页`):
 * - Atmospheric glass header (`Anode` architectural brand lockup + search trigger pill + sort selector)
 * - Tactile semantic category pill strip + aligned boards shortcut synchronized 1:1 with `HorizontalPager`
 * - Per-board cached slices in ViewModel + layered editorial feed cards
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    onOpenTopic: (topicId: Long, initialFloor: Int, titleHint: String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenBoardsTab: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenAuthorProfile: (uid: Long, usernameHint: String) -> Unit = { _, _ -> },
    onRequestWebViewAuth: (() -> Unit)? = null,
    externalCategorySlug: String? = null,
    scrollToTopTrigger: Int = 0,
    onScrollDirectionChanged: ((isScrollingUp: Boolean) -> Unit)? = null,
    bottomContentPadding: Dp = 88.dp,
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

    // Synchronize external board selection when navigated from Boards tab or deep link
    LaunchedEffect(externalCategorySlug, boardTabs.size) {
        val targetSlug = externalCategorySlug ?: return@LaunchedEffect
        if (targetSlug != uiState.selectedCategorySlug) {
            viewModel.selectCategory(targetSlug)
        }
        val targetIndex = boardTabs.indexOfFirst { it.slug == targetSlug }.coerceAtLeast(0)
        if (pagerState.currentPage != targetIndex) {
            if (abs(targetIndex - pagerState.currentPage) <= 1) {
                pagerState.animateScrollToPage(targetIndex)
            } else {
                pagerState.scrollToPage(targetIndex)
            }
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

    // Notify ViewModel immediately when horizontal swipe commits to a target board tab
    LaunchedEffect(pagerState, boardTabs) {
        snapshotFlow { pagerState.targetPage }
            .distinctUntilChanged()
            .collect { targetPage ->
                val targetTab = boardTabs.getOrNull(targetPage) ?: return@collect
                if (targetTab.slug != uiState.selectedCategorySlug) {
                    viewModel.selectCategory(targetTab.slug)
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
                        .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ExploreBrandTitle()

                    ExploreSearchTriggerPill(
                        onClick = onOpenSearch,
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(min = 64.dp),
                    )

                    Box {
                        ExploreSortPill(
                            label = currentSortLabel,
                            accentColor = currentSortAccent,
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 1.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LazyRow(
                        state = categoryRowState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(start = 14.dp, end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        itemsIndexed(
                            items = boardTabs,
                            key = { _, board -> board.slug.ifBlank { "__all__" } },
                        ) { index, board ->
                            val isSelected = index == visualTabIndex
                            ExploreCategoryPill(
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

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(16.dp)
                            .background(ext.cardBorder.copy(alpha = 0.85f)),
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    ExploreBoardsButton(
                        onClick = onOpenBoardsTab,
                    )

                    Spacer(modifier = Modifier.width(14.dp))
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

            ExploreBoardFeedPage(
                board = board,
                slice = slice,
                isActivePage = isActivePage,
                listState = pageListState,
                scrollToTopTrigger = scrollToTopTrigger,
                bottomContentPadding = bottomContentPadding,
                onScrollDirectionChanged = onScrollDirectionChanged,
                onRefresh = { viewModel.refresh(board.slug) },
                onLoadNextPage = { viewModel.loadNextPage(board.slug) },
                onSelectCategory = viewModel::selectCategory,
                onBookmarkToggle = viewModel::toggleBookmark,
                onOpenTopic = onOpenTopic,
                onOpenAuthorProfile = onOpenAuthorProfile,
                onTriggerWebViewAuth = triggerWebViewAuth,
            )
        }
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
private fun ExploreSearchTriggerPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val containerColor = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.048f)
    }

    Row(
        modifier = modifier
            .height(36.dp)
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Button,
                onClickLabel = "搜索主题与成员",
                showRipple = false,
                onClick = onClick,
            )
            .clip(CircleShape)
            .background(containerColor)
            .border(
                width = 0.8.dp,
                color = ext.cardBorder.copy(alpha = if (isDark) 0.78f else 0.65f),
                shape = CircleShape,
            )
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.84f),
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = "搜索主题",
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ExploreSortPill(
    label: String,
    accentColor: Color,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val interactionSource = remember { MutableInteractionSource() }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = NodePulseMotion.spatialFloatSpring(),
        label = "sortMenuChevronRotation",
    )
    val containerColor = when {
        expanded -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.55f else 0.72f)
        isDark -> ext.cardSurfaceElevated.copy(alpha = 0.90f)
        else -> ext.cardSurface.copy(alpha = 0.94f)
    }

    Row(
        modifier = modifier
            .height(36.dp)
            .pulsePressScale(
                interactionSource = interactionSource,
                pressedScale = NodePulseMotion.chipPressScale,
            )
            .clip(CircleShape)
            .background(containerColor)
            .border(
                width = 0.8.dp,
                color = if (expanded) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                } else {
                    ext.cardBorder.copy(alpha = if (isDark) 0.90f else 0.95f)
                },
                shape = CircleShape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = "切换信息流排序",
                onClick = onClick,
            )
            .padding(start = 10.dp, end = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.SwapVert,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
        )
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = "切换排序",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(15.dp)
                .graphicsLayer { rotationZ = chevronRotation },
        )
    }
}

@Composable
private fun ExploreCategoryPill(
    board: Category,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark
    val spec = NodePulseThemeTokens.categorySpec(board.slug)
    val isAllTab = board.slug.isBlank()

    val selectedFillBrush: Brush = if (isAllTab) {
        Brush.horizontalGradient(
            colors = listOf(
                MaterialTheme.colorScheme.primaryContainer,
                blendColor(
                    source = MaterialTheme.colorScheme.primaryContainer,
                    target = MaterialTheme.colorScheme.secondaryContainer,
                    fraction = 0.35f,
                ),
            ),
        )
    } else {
        spec.softTonalBrush
    }
    val unselectedFillBrush: Brush = SolidColor(
        MaterialTheme.colorScheme.onSurface.copy(
            alpha = if (isDark) 0.06f else 0.045f,
        ),
    )
    val selectedBorderColor = if (isAllTab) {
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.45f else 0.28f)
    } else {
        spec.spineColor.copy(alpha = if (isDark) 0.50f else 0.34f)
    }
    val selectedContentColor = if (isAllTab) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        spec.contentColor
    }
    val dotColor = if (isAllTab) {
        MaterialTheme.colorScheme.primary
    } else {
        spec.spineColor
    }

    Row(
        modifier = modifier
            .height(32.dp)
            .semantics { this.selected = selected }
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Tab,
                showRipple = false,
                onClick = onClick,
            )
            .clip(CircleShape)
            .background(if (selected) selectedFillBrush else unselectedFillBrush)
            .then(
                if (selected) {
                    Modifier.border(
                        width = 0.8.dp,
                        color = selectedBorderColor,
                        shape = CircleShape,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (selected) {
            Icon(
                imageVector = spec.icon,
                contentDescription = null,
                tint = selectedContentColor,
                modifier = Modifier.size(13.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(5.5.dp)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = 0.80f)),
            )
        }
        Text(
            text = board.name,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) selectedContentColor else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun ExploreBoardsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark

    Box(
        modifier = modifier
            .size(32.dp)
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Button,
                onClickLabel = "全部版块",
                showRipple = false,
                onClick = onClick,
            )
            .clip(CircleShape)
            .background(
                if (isDark) {
                    ext.cardSurfaceElevated.copy(alpha = 0.85f)
                } else {
                    ext.cardSurface.copy(alpha = 0.90f)
                },
            )
            .border(
                width = 0.8.dp,
                color = ext.cardBorder.copy(alpha = if (isDark) 0.85f else 0.90f),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.GridView,
            contentDescription = "全部版块",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExploreBoardFeedPage(
    board: Category,
    slice: ExploreBoardSliceUiState,
    isActivePage: Boolean,
    listState: LazyListState,
    scrollToTopTrigger: Int,
    bottomContentPadding: Dp,
    onScrollDirectionChanged: ((isScrollingUp: Boolean) -> Unit)?,
    onRefresh: () -> Unit,
    onLoadNextPage: () -> Unit,
    onSelectCategory: (String) -> Unit,
    onBookmarkToggle: (TopicSummary) -> Unit,
    onOpenTopic: (topicId: Long, initialFloor: Int, titleHint: String) -> Unit,
    onOpenAuthorProfile: (uid: Long, usernameHint: String) -> Unit,
    onTriggerWebViewAuth: () -> Unit,
) {
    LaunchedEffect(scrollToTopTrigger, isActivePage) {
        if (scrollToTopTrigger > 0 && isActivePage) {
            listState.animateScrollToItem(0)
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
    LaunchedEffect(isActivePage, shouldLoadMore, slice.hasNextPage, slice.isLoadingNextPage) {
        if (isActivePage && shouldLoadMore && slice.hasNextPage && !slice.isLoadingNextPage) {
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
                top = 6.dp,
                bottom = bottomContentPadding + 14.dp,
            ),
        ) {
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
                    Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)) {
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
                        PulseTopicCardSkeleton()
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

                // 3. Empty Feed State
                slice.topics.isEmpty() -> {
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

                    // Next-page loading or pagination retry footer
                    if (slice.isLoadingNextPage) {
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
                    } else if (slice.nextPageError != null) {
                        item(key = "pagination_error_${board.slug}") {
                            Row(
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
                                    text = "加载下一页未成功，点按重试",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
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

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isChallengeRequired) {
                Button(onClick = onVerifyWebView, shape = CircleShape) {
                    Text(text = "完成网页验证 / 登录")
                }
                OutlinedButton(onClick = onRetry, shape = CircleShape) {
                    Text(text = "重试")
                }
            } else {
                Button(onClick = onRetry, shape = CircleShape) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "重新加载")
                }
                FilledTonalButton(onClick = onVerifyWebView, shape = CircleShape) {
                    Text(text = "网页会话")
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
                    Text(text = "返回全部")
                }
            }
            OutlinedButton(onClick = onRefresh, shape = CircleShape) {
                Text(text = "刷新")
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
