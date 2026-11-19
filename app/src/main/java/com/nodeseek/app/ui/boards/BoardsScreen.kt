package com.nodeseek.app.ui.boards

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.ui.components.CollapsiblePinnedTicker
import com.nodeseek.app.ui.components.CompactCountBadge
import com.nodeseek.app.ui.components.PinnedTickerTopic
import com.nodeseek.app.ui.components.PulseAvatar
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.PulseTopicCard
import com.nodeseek.app.ui.components.PulseTopicCardSkeleton
import com.nodeseek.app.ui.components.RankMicroBadge
import com.nodeseek.app.ui.components.SortSegmentItem
import com.nodeseek.app.ui.components.SortSegmentedSwitcher
import com.nodeseek.app.ui.components.WebViewSessionSheet
import com.nodeseek.app.ui.explore.toCardModel
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseSectionHeader
import com.nodeseek.app.ui.theme.PulseStaggeredEntrance
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.pulseHeroCard
import com.nodeseek.app.ui.theme.pulsePressScale
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val TECH_INTEL_SLUGS = setOf("tech", "dev", "info", "review", "inside", "sandbox")
private val TRADE_CARPOOL_SLUGS = setOf("trade", "carpool", "promotion")
private val TECH_HERO_PREFERENCES = setOf("tech", "review")
private val TRADE_HERO_PREFERENCES = setOf("trade")
private val COMMUNITY_HERO_PREFERENCES = setOf("daily")

private val SEARCH_SORT_SEGMENTS: List<SortSegmentItem<FeedSort>> = listOf(
    SortSegmentItem(FeedSort.LATEST_REPLY, "最新回复"),
    SortSegmentItem(FeedSort.NEWEST_TOPIC, "最新发布"),
)

private val BOARD_FEED_SORT_SEGMENTS: List<SortSegmentItem<FeedSort>> = listOf(
    SortSegmentItem(FeedSort.LATEST_REPLY, "最新回复"),
    SortSegmentItem(FeedSort.NEWEST_TOPIC, "最新发布"),
    SortSegmentItem(FeedSort.HOT, "热门精选"),
)

private enum class BoardsDisplayMode {
    DIRECTORY,
    SEARCH,
    INLINE_BOARD,
}

internal fun shouldRequestSearchAutoFocus(
    autoFocusSearch: Boolean,
    hasConsumedInitialAutoFocus: Boolean,
    submittedQuery: String,
    focusSearchTrigger: Int,
    lastHandledFocusTrigger: Int,
): Boolean {
    val initialAutoFocus = autoFocusSearch &&
        !hasConsumedInitialAutoFocus &&
        submittedQuery.isBlank()
    val explicitTrigger = focusSearchTrigger > 0 &&
        focusSearchTrigger != lastHandledFocusTrigger
    return initialAutoFocus || explicitTrigger
}

/**
 * NodePulse Boards & Unified Search Screen (`版块`).
 *
 * Dual-Mode Architecture:
 * 1. **Asymmetric Visual Bento Board Directory**:
 *    - 2-column `LazyVerticalGrid` with `GridItemSpan(2)` Hero Bento tiles (`tech`, `review`,
 *      `trade`, `daily`, `all`) and `GridItemSpan(1)` tiles, displaying rounded icons, Chinese &
 *      `/slug` badges, editorial descriptions, `5 🍗` badge on `inside`, and 1-tap Pin Star (`★`).
 *    - Tapping a board either invokes `onSelectBoard` (if caller wants to switch Explore tab) OR
 *      opens a full filtered inline board feed sub-view with BackHandler support.
 * 2. **Unified Search Experience**:
 *    - Recent search history chips + clear, `找帖子` / `找用户` scope chips, board & sort filters,
 *      and a prominent **1-Tap Google 站内搜索 (`site:www.nodeseek.com`)** fallback card.
 */
@Composable
fun BoardsScreen(
    onOpenTopic: (topicId: Long, initialFloor: Int, titleHint: String) -> Unit,
    modifier: Modifier = Modifier,
    initialCategorySlug: String = "",
    autoFocusSearch: Boolean = false,
    focusSearchTrigger: Int = 0,
    onAutoFocusConsumed: () -> Unit = {},
    onSelectBoard: ((String) -> Unit)? = null,
    onOpenUserProfile: (uid: Long, usernameHint: String) -> Unit = { _, _ -> },
    onRequestWebViewAuth: (() -> Unit)? = null,
    bottomContentPadding: Dp = 98.dp,
    standaloneSearchMode: Boolean = false,
    onBack: (() -> Unit)? = null,
    selectedTopicId: Long? = null,
    previewUiState: BoardsUiState? = null,
    viewModel: BoardsViewModel = viewModel(
        factory = BoardsViewModel.factory(
            container = LocalAppContainer.current,
            initialCategorySlug = initialCategorySlug,
            autoFocusSearch = autoFocusSearch,
        )
    ),
) {
    val observedUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = previewUiState ?: observedUiState
    val snackbarHostState = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    val searchListState = rememberLazyListState()
    var hasConsumedInitialAutoFocus by rememberSaveable { mutableStateOf(false) }
    var lastHandledFocusTrigger by rememberSaveable { mutableIntStateOf(0) }
    var showBuiltInWebViewSheet by rememberSaveable { mutableStateOf(false) }
    val dismissSearchKeyboard: () -> Unit = {
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    // Remember last inline slug so header transition doesn't flicker during exit animation.
    var lastInlineSlug by rememberSaveable { mutableStateOf(initialCategorySlug) }
    LaunchedEffect(uiState.activeInlineBoardSlug) {
        uiState.activeInlineBoardSlug?.let { active ->
            lastInlineSlug = active
        }
    }

    val triggerWebViewAuth: () -> Unit = remember(onRequestWebViewAuth) {
        {
            if (onRequestWebViewAuth != null) {
                onRequestWebViewAuth()
            } else {
                showBuiltInWebViewSheet = true
            }
        }
    }

    // Sync external initialCategorySlug when updated dynamically
    LaunchedEffect(initialCategorySlug) {
        if (initialCategorySlug.isNotBlank() && initialCategorySlug != uiState.activeInlineBoardSlug) {
            viewModel.openInlineBoard(initialCategorySlug)
        }
    }

    // Auto-focus search bar once on initial entry (never again when returning from a post)
    LaunchedEffect(autoFocusSearch, focusSearchTrigger) {
        if (
            shouldRequestSearchAutoFocus(
                autoFocusSearch = autoFocusSearch,
                hasConsumedInitialAutoFocus = hasConsumedInitialAutoFocus,
                submittedQuery = uiState.submittedQuery,
                focusSearchTrigger = focusSearchTrigger,
                lastHandledFocusTrigger = lastHandledFocusTrigger,
            )
        ) {
            val consumedInitial = autoFocusSearch && !hasConsumedInitialAutoFocus
            hasConsumedInitialAutoFocus = true
            if (focusSearchTrigger > 0) {
                lastHandledFocusTrigger = focusSearchTrigger
            }
            viewModel.setSearchModeActive(true)
            delay(80)
            runCatching { focusRequester.requestFocus() }
            keyboardController?.show()
            if (consumedInitial) {
                onAutoFocusConsumed()
            }
        }
    }

    LaunchedEffect(searchListState.isScrollInProgress) {
        if (searchListState.isScrollInProgress) {
            dismissSearchKeyboard()
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeSnackbar()
    }

    // Intercept system back when viewing an inline board feed, active search mode, or standalone search
    BackHandler(
        enabled = uiState.activeInlineBoardSlug != null || uiState.isSearchModeActive || standaloneSearchMode,
    ) {
        when {
            uiState.activeInlineBoardSlug != null -> viewModel.closeInlineBoard()
            standaloneSearchMode -> {
                dismissSearchKeyboard()
                onBack?.invoke()
            }
            uiState.isSearchModeActive -> {
                dismissSearchKeyboard()
                viewModel.setSearchModeActive(false)
            }
        }
    }

    val displayMode = when {
        uiState.activeInlineBoardSlug != null -> BoardsDisplayMode.INLINE_BOARD
        standaloneSearchMode || uiState.isSearchModeActive -> BoardsDisplayMode.SEARCH
        else -> BoardsDisplayMode.DIRECTORY
    }
    val visibleBoardCount = remember(uiState.categories) {
        uiState.categories.count { !it.isAdminOnly }
    }
    val pinnedBoardCount = remember(uiState.categories, uiState.pinnedCategorySlugs) {
        uiState.categories.count { !it.isAdminOnly && it.slug.isNotBlank() && it.slug in uiState.pinnedCategorySlugs }
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
            AnimatedContent(
                targetState = displayMode == BoardsDisplayMode.INLINE_BOARD,
                transitionSpec = {
                    (fadeIn(tween(200)) + expandVertically(tween(220))) togetherWith
                        (fadeOut(tween(140)) + shrinkVertically(tween(180)))
                },
                label = "boardsTopBarTransition",
            ) { isInlineBoard ->
                if (isInlineBoard) {
                    val activeSlug = uiState.activeInlineBoardSlug ?: lastInlineSlug
                    InlineBoardTopHeader(
                        slug = activeSlug,
                        categories = uiState.categories,
                        isPinned = activeSlug in uiState.pinnedCategorySlugs,
                        sort = uiState.inlineBoardSort,
                        onBack = viewModel::closeInlineBoard,
                        onTogglePin = {
                            viewModel.togglePinCategory(activeSlug)
                        },
                        onSelectSort = viewModel::selectInlineBoardSort,
                    )
                } else {
                    // Keep BoardsDirectorySearchHeader mounted across DIRECTORY <-> SEARCH
                    // so focusRequester stays attached without losing keyboard focus.
                    BoardsDirectorySearchHeader(
                        query = uiState.queryInput,
                        isSearchActive = standaloneSearchMode || uiState.isSearchModeActive,
                        pinnedCount = pinnedBoardCount,
                        totalBoardCount = visibleBoardCount,
                        focusRequester = focusRequester,
                        onQueryChange = viewModel::updateQueryInput,
                        onFocusSearch = { viewModel.setSearchModeActive(true) },
                        onSubmitSearch = {
                            dismissSearchKeyboard()
                            viewModel.submitSearch()
                            coroutineScope.launch {
                                searchListState.scrollToItem(0)
                            }
                        },
                        onCancelSearch = {
                            dismissSearchKeyboard()
                            if (standaloneSearchMode) {
                                onBack?.invoke()
                            } else {
                                viewModel.setSearchModeActive(false)
                            }
                        },
                        onClearInput = { viewModel.updateQueryInput("") },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AnimatedContent(
                targetState = displayMode,
                transitionSpec = {
                    (fadeIn(tween(220)) + scaleIn(initialScale = 0.985f, animationSpec = tween(220)) +
                        slideInVertically(animationSpec = tween(220)) { it / 32 }) togetherWith
                        fadeOut(tween(120))
                },
                label = "boardsBodyModeTransition",
            ) { mode ->
                when (mode) {
                    // 1. Filtered Inline Board Feed Sub-View
                    BoardsDisplayMode.INLINE_BOARD -> {
                        InlineBoardFeedContent(
                            uiState = uiState,
                            bottomContentPadding = bottomContentPadding,
                            selectedTopicId = selectedTopicId,
                            onOpenTopic = { topicId, initialFloor, titleHint ->
                                dismissSearchKeyboard()
                                onOpenTopic(topicId, initialFloor, titleHint)
                            },
                            onOpenUserProfile = { uid, usernameHint ->
                                dismissSearchKeyboard()
                                onOpenUserProfile(uid, usernameHint)
                            },
                            onBookmarkToggle = viewModel::toggleBookmark,
                            onRefresh = viewModel::refreshInlineBoard,
                            onLoadMore = viewModel::loadNextInlineBoardPage,
                            onVerifyWebView = triggerWebViewAuth,
                            onSwitchToFullWebFeed = viewModel::disablePreferRssAndRefreshInlineBoard,
                        )
                    }

                    // 2. Unified Search Mode (Active focus or submitted query)
                    BoardsDisplayMode.SEARCH -> {
                        UnifiedSearchContent(
                            uiState = uiState,
                            listState = searchListState,
                            bottomContentPadding = bottomContentPadding,
                            selectedTopicId = selectedTopicId,
                            onSelectRecentQuery = { q ->
                                dismissSearchKeyboard()
                                viewModel.submitSearch(q)
                                coroutineScope.launch {
                                    searchListState.scrollToItem(0)
                                }
                            },
                            onRemoveRecentQuery = viewModel::removeRecentQuery,
                            onClearRecentQueries = viewModel::clearRecentQueries,
                            onSelectScope = { scope ->
                                dismissSearchKeyboard()
                                viewModel.selectSearchScope(scope)
                            },
                            onSelectCategoryFilter = { slug ->
                                dismissSearchKeyboard()
                                viewModel.selectSearchCategoryFilter(slug)
                            },
                            onSelectSort = { sort ->
                                dismissSearchKeyboard()
                                viewModel.selectSearchSort(sort)
                            },
                            onSubmitCurrentQuery = {
                                dismissSearchKeyboard()
                                viewModel.submitSearch()
                            },
                            onOpenGoogleSearch = {
                                dismissSearchKeyboard()
                                runCatching { uriHandler.openUri(uiState.googleSiteSearchUrl) }
                            },
                            onJumpToBoard = { slug ->
                                dismissSearchKeyboard()
                                viewModel.setSearchModeActive(false)
                                if (onSelectBoard != null) {
                                    onSelectBoard(slug)
                                } else {
                                    viewModel.openInlineBoard(slug)
                                }
                            },
                            onOpenTopic = { topicId, initialFloor, titleHint ->
                                dismissSearchKeyboard()
                                onOpenTopic(topicId, initialFloor, titleHint)
                            },
                            onOpenUserProfile = { uid, usernameHint ->
                                dismissSearchKeyboard()
                                onOpenUserProfile(uid, usernameHint)
                            },
                            onBookmarkToggle = viewModel::toggleBookmark,
                            onLoadNextPage = viewModel::loadNextSearchPage,
                            onVerifyWebView = triggerWebViewAuth,
                        )
                    }

                    // 3. Asymmetric 2-Column Visual Bento Board Directory
                    BoardsDisplayMode.DIRECTORY -> {
                        BentoBoardDirectoryGrid(
                            categories = uiState.filteredBentoCategories,
                            pinnedSlugs = uiState.pinnedCategorySlugs,
                            bottomContentPadding = bottomContentPadding,
                            onBoardClick = { slug ->
                                if (onSelectBoard != null) {
                                    onSelectBoard(slug)
                                } else {
                                    viewModel.openInlineBoard(slug)
                                }
                            },
                            onTogglePin = viewModel::togglePinCategory,
                        )
                    }
                }
            }
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

// ── Top Headers ───────────────────────────────────────────────────────────────────────────

@Composable
private fun BoardsDirectorySearchHeader(
    query: String,
    isSearchActive: Boolean,
    pinnedCount: Int,
    totalBoardCount: Int,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onFocusSearch: () -> Unit,
    onSubmitSearch: () -> Unit,
    onCancelSearch: () -> Unit,
    onClearInput: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val extraType = NodePulseThemeTokens.extraTypography
    val isDark = NodePulseThemeTokens.isDark
    val keyboardController = LocalSoftwareKeyboardController.current
    var isFieldFocused by remember { mutableStateOf(false) }
    val searchHighlighted = isFieldFocused

    val searchBorderWidth by animateDpAsState(
        targetValue = if (searchHighlighted) 1.4.dp else 1.dp,
        animationSpec = NodePulseMotion.spatialDpSpring(),
        label = "searchBarBorderWidth",
    )
    val searchBorderBrush = if (searchHighlighted) {
        PulseBrushes.brandGradient()
    } else {
        SolidColor(ext.cardBorder)
    }
    val searchContainerColor by animateColorAsState(
        targetValue = if (searchHighlighted) ext.cardSurfaceElevated else ext.cardSurface,
        label = "searchBarContainer",
    )
    val searchIconTint by animateColorAsState(
        targetValue = if (searchHighlighted) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "searchIconTint",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseGlassSurface(
                showTopBorder = false,
                showBottomBorder = true,
                tonalAccent = if (isSearchActive) MaterialTheme.colorScheme.primary else null,
            )
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AnimatedVisibility(
            visible = !isSearchActive,
            enter = expandVertically(tween(220)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(34.dp)
                        .clip(CircleShape)
                        .background(PulseBrushes.brandGradient(horizontal = false)),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = "版块",
                        style = extraType.heroHeadline,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                    )
                    Text(
                        text = "点击星标可常驻首页快捷频道栏",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Compact live directory telemetry pill
                Row(
                    modifier = Modifier
                        .clip(PulseArchitecturalShapes.Pill)
                        .background(ext.cardSurface)
                        .border(width = 0.8.dp, color = ext.cardBorder, shape = PulseArchitecturalShapes.Pill)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Star,
                        contentDescription = null,
                        tint = ext.drumstickGoldStart,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = "$pinnedCount",
                        style = extraType.tabularBadge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Text(
                        text = "/ $totalBoardCount",
                        style = extraType.microLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnimatedVisibility(
                visible = isSearchActive,
                enter = expandHorizontally(tween(180)) + fadeIn(tween(160)),
                exit = shrinkHorizontally(tween(160)) + fadeOut(tween(120)),
            ) {
                IconButton(
                    onClick = onCancelSearch,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(ext.cardSurface)
                        .border(width = 0.8.dp, color = ext.cardBorder, shape = CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回版块目录",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .then(
                        if (!isDark) {
                            Modifier.shadow(
                                elevation = if (searchHighlighted) PulseElevation.CardElevated else PulseElevation.Subtle,
                                shape = PulseArchitecturalShapes.Pill,
                                clip = false,
                                ambientColor = ext.softShadowColor,
                                spotColor = ext.softShadowColor,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clip(PulseArchitecturalShapes.Pill)
                    .background(searchContainerColor)
                    .border(
                        width = searchBorderWidth,
                        brush = searchBorderBrush,
                        shape = PulseArchitecturalShapes.Pill,
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        runCatching { focusRequester.requestFocus() }
                        keyboardController?.show()
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = searchIconTint,
                    modifier = Modifier.size(19.dp),
                )

                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = "筛选版块，或输入关键词搜帖子 / 用户…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSubmitSearch() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { state ->
                                isFieldFocused = state.isFocused
                                if (state.isFocused && !isSearchActive) {
                                    onFocusSearch()
                                }
                            },
                    )
                }

                AnimatedVisibility(
                    visible = query.isNotEmpty(),
                    enter = scaleIn(NodePulseMotion.microBounceSpring()) + fadeIn(),
                    exit = fadeOut(tween(120)),
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(role = Role.Button, onClick = onClearInput),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "清空输入",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isSearchActive && query.isNotBlank(),
                enter = expandHorizontally(tween(180)) + fadeIn(tween(160)),
                exit = shrinkHorizontally(tween(160)) + fadeOut(tween(120)),
            ) {
                val submitInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .height(42.dp)
                        .pulsePressScale(
                            interactionSource = submitInteraction,
                            pressedScale = NodePulseMotion.chipPressScale,
                        )
                        .clip(CircleShape)
                        .background(PulseBrushes.brandGradient())
                        .clickable(
                            interactionSource = submitInteraction,
                            indication = LocalIndication.current,
                            role = Role.Button,
                            onClick = onSubmitSearch,
                        )
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "搜索",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineBoardTopHeader(
    slug: String,
    categories: List<Category>,
    isPinned: Boolean,
    sort: FeedSort,
    onBack: () -> Unit,
    onTogglePin: () -> Unit,
    onSelectSort: (FeedSort) -> Unit,
) {
    val spec = NodePulseThemeTokens.categorySpec(slug)
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val category = categories.firstOrNull { it.slug == slug }
    val title = category?.name ?: spec.defaultTitle
    val coinCost = maxOf(category?.topicCoinCost ?: 0, spec.postCoinCost)

    var pinBounce by remember { mutableStateOf(false) }
    LaunchedEffect(pinBounce) {
        if (pinBounce) {
            delay(180)
            pinBounce = false
        }
    }
    val starScale by animateFloatAsState(
        targetValue = if (pinBounce) 1.28f else 1f,
        animationSpec = NodePulseMotion.microBounceSpring(),
        label = "inlineHeaderStarScale",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseGlassSurface(
                showTopBorder = false,
                showBottomBorder = true,
                tonalAccent = spec.spineColor,
            )
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(ext.cardSurface.copy(alpha = 0.78f))
                    .border(width = 0.8.dp, color = ext.cardBorder, shape = CircleShape),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回版块目录",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(19.dp),
                )
            }

            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(spec.softTonalBrush)
                    .border(
                        width = 0.9.dp,
                        color = spec.spineColor.copy(alpha = if (isDark) 0.45f else 0.25f),
                        shape = RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = spec.icon,
                    contentDescription = null,
                    tint = spec.contentColor,
                    modifier = Modifier.size(20.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (slug.isNotBlank()) {
                        BoardSlugBadge(slug = slug, spec = spec)
                    }
                    if (coinCost > 0) {
                        BoardCoinCostPill(coins = coinCost)
                    }
                }
                Text(
                    text = category?.description?.ifBlank { spec.editorialDescription }
                        ?: spec.editorialDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (slug.isNotBlank()) {
                val starContainer by animateColorAsState(
                    targetValue = if (isPinned) ext.drumstickContainer else ext.cardSurface,
                    label = "inlinePinBg",
                )
                val starTint by animateColorAsState(
                    targetValue = if (isPinned) ext.drumstickGoldStart else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "inlinePinTint",
                )
                IconButton(
                    onClick = {
                        pinBounce = true
                        onTogglePin()
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(starContainer)
                        .border(
                            width = 0.8.dp,
                            color = if (isPinned) {
                                ext.drumstickGoldStart.copy(alpha = 0.45f)
                            } else {
                                ext.cardBorder
                            },
                            shape = CircleShape,
                        ),
                ) {
                    Icon(
                        imageVector = if (isPinned) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = if (isPinned) "取消常驻" else "常驻到首页",
                        tint = starTint,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer {
                                scaleX = starScale
                                scaleY = starScale
                            },
                    )
                }
            }
        }

        SortSegmentedSwitcher(
            options = BOARD_FEED_SORT_SEGMENTS,
            selectedValue = sort,
            onSelect = onSelectSort,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        )
    }
}

// ── Mode 1: Asymmetric Visual Bento Board Directory ──────────────────────────────────────

private enum class BentoDirectoryFilter(val label: String) {
    ALL("全部版块"),
    PINNED("常用版块"),
}

private data class BentoGridItem(
    val category: Category,
    val isHero: Boolean,
)

/**
 * Packs a board section into balanced 2-column Bento rows so `GridItemSpan(2)` Hero tiles
 * always start on column 0 and leave zero awkward half-row orphan gaps.
 */
private fun packBentoSection(
    boards: List<Category>,
    preferredHeroSlugs: Set<String> = emptySet(),
    forceFirstHeroIfOdd: Boolean = false,
): List<BentoGridItem> {
    if (boards.isEmpty()) return emptyList()
    if (boards.size == 1) {
        return listOf(BentoGridItem(boards.first(), isHero = true))
    }

    val wantHeroCount = when {
        boards.size >= 4 && boards.size % 2 == 0 -> 2
        boards.size % 2 == 1 -> 1
        else -> 0
    }

    val selectedHeroes = mutableListOf<Category>()
    for (board in boards) {
        if (selectedHeroes.size >= wantHeroCount) break
        if (board.slug in preferredHeroSlugs) {
            selectedHeroes.add(board)
        }
    }
    if (forceFirstHeroIfOdd && boards.size % 2 == 1 && selectedHeroes.isEmpty()) {
        selectedHeroes.add(boards.first())
    }

    while (selectedHeroes.isNotEmpty() && (boards.size - selectedHeroes.size) % 2 != 0) {
        selectedHeroes.removeAt(selectedHeroes.lastIndex)
    }

    val heroSlugs = selectedHeroes.map { it.slug }.toHashSet()
    val heroesQueue = boards.filter { it.slug in heroSlugs }
    val singlesQueue = boards.filterNot { it.slug in heroSlugs }

    val packed = ArrayList<BentoGridItem>(boards.size)
    var singleIndex = 0
    heroesQueue.forEachIndexed { heroIdx, hero ->
        packed.add(BentoGridItem(hero, isHero = true))
        if (heroIdx < heroesQueue.lastIndex && singleIndex + 1 < singlesQueue.size) {
            packed.add(BentoGridItem(singlesQueue[singleIndex], isHero = false))
            packed.add(BentoGridItem(singlesQueue[singleIndex + 1], isHero = false))
            singleIndex += 2
        }
    }
    while (singleIndex < singlesQueue.size) {
        packed.add(BentoGridItem(singlesQueue[singleIndex], isHero = false))
        singleIndex++
    }
    return packed
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BentoBoardDirectoryGrid(
    categories: List<Category>,
    pinnedSlugs: Set<String>,
    bottomContentPadding: Dp,
    onBoardClick: (String) -> Unit,
    onTogglePin: (String) -> Unit,
) {
    var activeFilter by rememberSaveable { mutableStateOf(BentoDirectoryFilter.ALL) }
    var initialEntranceCompleted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!initialEntranceCompleted) {
            delay(460L)
            initialEntranceCompleted = true
        }
    }

    val pinnedBoards = remember(categories, pinnedSlugs) {
        categories.filter { it.slug.isNotBlank() && it.slug in pinnedSlugs }
    }
    val packedPinnedOnly = remember(pinnedBoards) {
        packBentoSection(
            boards = pinnedBoards,
            preferredHeroSlugs = TECH_HERO_PREFERENCES + TRADE_HERO_PREFERENCES + COMMUNITY_HERO_PREFERENCES,
            forceFirstHeroIfOdd = true,
        )
    }
    val packedPinnedInline = remember(pinnedBoards) {
        packBentoSection(
            boards = pinnedBoards,
            preferredHeroSlugs = TECH_HERO_PREFERENCES + TRADE_HERO_PREFERENCES + COMMUNITY_HERO_PREFERENCES,
            forceFirstHeroIfOdd = true,
        )
    }
    val packedTechIntel = remember(categories) {
        packBentoSection(
            boards = categories.filter { it.slug in TECH_INTEL_SLUGS },
            preferredHeroSlugs = TECH_HERO_PREFERENCES,
        )
    }
    val packedTradeCarpool = remember(categories) {
        packBentoSection(
            boards = categories.filter { it.slug in TRADE_CARPOOL_SLUGS },
            preferredHeroSlugs = TRADE_HERO_PREFERENCES,
        )
    }
    val packedCommunity = remember(categories) {
        packBentoSection(
            boards = categories.filter {
                it.slug.isNotBlank() && it.slug !in TECH_INTEL_SLUGS && it.slug !in TRADE_CARPOOL_SLUGS
            },
            preferredHeroSlugs = COMMUNITY_HERO_PREFERENCES,
        )
    }
    val allAggregateBoard = remember(categories) {
        categories.firstOrNull { it.slug.isBlank() }
    }

    val ext = NodePulseThemeTokens.extendedColors

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 10.dp,
            bottom = bottomContentPadding + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "bento_quick_filters", span = { GridItemSpan(2) }) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BentoDirectoryFilter.entries.forEach { filter ->
                    val selected = activeFilter == filter
                    val count = if (filter == BentoDirectoryFilter.ALL) {
                        categories.size
                    } else {
                        pinnedBoards.size
                    }
                    val icon = if (filter == BentoDirectoryFilter.ALL) {
                        Icons.Rounded.GridView
                    } else {
                        Icons.Rounded.Star
                    }
                    BentoFilterPill(
                        label = "${filter.label} · $count",
                        selected = selected,
                        icon = icon,
                        iconTintOverride = if (filter == BentoDirectoryFilter.PINNED) {
                            ext.drumstickGoldStart
                        } else {
                            null
                        },
                        onClick = { activeFilter = filter },
                    )
                }
            }
        }

        if (activeFilter == BentoDirectoryFilter.PINNED) {
            if (packedPinnedOnly.isEmpty()) {
                item(key = "pinned_boards_empty", span = { GridItemSpan(2) }) {
                    ExpressiveStateCard(
                        icon = Icons.Rounded.StarBorder,
                        title = "暂无常用版块",
                        description = "点击任意版块卡片右上角的星标，即可将其固定到首页顶部快捷频道栏",
                        accentColor = ext.drumstickGoldStart,
                        modifier = Modifier.padding(top = 6.dp),
                        actions = {
                            Button(
                                onClick = { activeFilter = BentoDirectoryFilter.ALL },
                                shape = CircleShape,
                            ) {
                                Text(text = "浏览全部版块")
                            }
                        },
                    )
                }
            } else {
                item(key = "header_pinned_only", span = { GridItemSpan(2) }) {
                    DirectorySectionHeader(
                        title = "常用版块",
                        subtitle = "已固定至首页快捷频道栏",
                        icon = Icons.Rounded.Star,
                        accentColor = ext.drumstickGoldStart,
                        countLabel = "${packedPinnedOnly.size} 个",
                    )
                }
                itemsIndexed(
                    items = packedPinnedOnly,
                    key = { _, item -> "bento_pinned_only_${item.category.slug}" },
                    span = { _, item -> GridItemSpan(if (item.isHero) 2 else 1) },
                ) { idx, item ->
                    PulseStaggeredEntrance(
                        index = idx,
                        enabled = !initialEntranceCompleted && idx <= 6,
                        modifier = Modifier.animateItem(),
                    ) {
                        BentoBoardTile(
                            category = item.category,
                            isHero = item.isHero,
                            isPinned = true,
                            onClick = { onBoardClick(item.category.slug) },
                            onTogglePin = { onTogglePin(item.category.slug) },
                        )
                    }
                }
            }
        } else {
            if (packedPinnedInline.isNotEmpty()) {
                item(key = "header_pinned", span = { GridItemSpan(2) }) {
                    DirectorySectionHeader(
                        title = "常用版块",
                        subtitle = "点击星标管理首页快捷频道",
                        icon = Icons.Rounded.Star,
                        accentColor = ext.drumstickGoldStart,
                        countLabel = "${packedPinnedInline.size} 个",
                    )
                }
                itemsIndexed(
                    items = packedPinnedInline,
                    key = { _, item -> "bento_pinned_${item.category.slug}" },
                    span = { _, item -> GridItemSpan(if (item.isHero) 2 else 1) },
                ) { idx, item ->
                    PulseStaggeredEntrance(
                        index = idx,
                        enabled = !initialEntranceCompleted && idx <= 6,
                        modifier = Modifier.animateItem(),
                    ) {
                        BentoBoardTile(
                            category = item.category,
                            isHero = item.isHero,
                            isPinned = true,
                            onClick = { onBoardClick(item.category.slug) },
                            onTogglePin = { onTogglePin(item.category.slug) },
                        )
                    }
                }
            }

            if (packedTechIntel.isNotEmpty()) {
                item(key = "header_tech_intel", span = { GridItemSpan(2) }) {
                    DirectorySectionHeader(
                        title = "技术与情报",
                        subtitle = "极客运维 · 优惠线报 · 硬件测评",
                        icon = Icons.Rounded.Memory,
                        accentColor = MaterialTheme.colorScheme.primary,
                        countLabel = "${packedTechIntel.size} 个",
                    )
                }
                itemsIndexed(
                    items = packedTechIntel,
                    key = { _, item -> "bento_tech_${item.category.slug}" },
                    span = { _, item -> GridItemSpan(if (item.isHero) 2 else 1) },
                ) { idx, item ->
                    val staggerIndex = (packedPinnedInline.size + idx).coerceAtMost(6)
                    PulseStaggeredEntrance(
                        index = staggerIndex,
                        enabled = !initialEntranceCompleted && (packedPinnedInline.size + idx) <= 6,
                        modifier = Modifier.animateItem(),
                    ) {
                        BentoBoardTile(
                            category = item.category,
                            isHero = item.isHero,
                            isPinned = item.category.slug in pinnedSlugs,
                            onClick = { onBoardClick(item.category.slug) },
                            onTogglePin = { onTogglePin(item.category.slug) },
                        )
                    }
                }
            }

            if (packedTradeCarpool.isNotEmpty()) {
                item(key = "header_trade_carpool", span = { GridItemSpan(2) }) {
                    DirectorySectionHeader(
                        title = "交易与车队",
                        subtitle = "闲置流转 · 专线合租 · 商家推介",
                        icon = Icons.Rounded.SwapHoriz,
                        accentColor = MaterialTheme.colorScheme.secondary,
                        countLabel = "${packedTradeCarpool.size} 个",
                    )
                }
                itemsIndexed(
                    items = packedTradeCarpool,
                    key = { _, item -> "bento_trade_${item.category.slug}" },
                    span = { _, item -> GridItemSpan(if (item.isHero) 2 else 1) },
                ) { _, item ->
                    BentoBoardTile(
                        category = item.category,
                        isHero = item.isHero,
                        isPinned = item.category.slug in pinnedSlugs,
                        onClick = { onBoardClick(item.category.slug) },
                        onTogglePin = { onTogglePin(item.category.slug) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            if (packedCommunity.isNotEmpty() || allAggregateBoard != null) {
                item(key = "header_life_community", span = { GridItemSpan(2) }) {
                    val totalCommunity = packedCommunity.size + if (allAggregateBoard != null) 1 else 0
                    DirectorySectionHeader(
                        title = "日常与社区",
                        subtitle = "水贴闲聊 · 摄影曝光 · 全站汇总",
                        icon = Icons.Rounded.LocalCafe,
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        countLabel = "$totalCommunity 个",
                    )
                }
                itemsIndexed(
                    items = packedCommunity,
                    key = { _, item -> "bento_community_${item.category.slug}" },
                    span = { _, item -> GridItemSpan(if (item.isHero) 2 else 1) },
                ) { _, item ->
                    BentoBoardTile(
                        category = item.category,
                        isHero = item.isHero,
                        isPinned = item.category.slug in pinnedSlugs,
                        onClick = { onBoardClick(item.category.slug) },
                        onTogglePin = { onTogglePin(item.category.slug) },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (allAggregateBoard != null) {
                    item(
                        key = "bento_all_aggregate",
                        span = { GridItemSpan(2) },
                    ) {
                        BentoBoardTile(
                            category = allAggregateBoard,
                            isHero = true,
                            isPinned = false,
                            allowPin = false,
                            onClick = { onBoardClick("") },
                            onTogglePin = {},
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BentoFilterPill(
    label: String,
    selected: Boolean,
    icon: ImageVector,
    onClick: () -> Unit,
    iconTintOverride: Color? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val interactionSource = remember { MutableInteractionSource() }

    val bgColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            ext.cardSurface
        },
        label = "bentoFilterBg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "bentoFilterFg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.55f else 0.35f)
        } else {
            ext.cardBorder
        },
        label = "bentoFilterBorder",
    )

    Row(
        modifier = Modifier
            .pulsePressScale(interactionSource = interactionSource, pressedScale = NodePulseMotion.chipPressScale)
            .clip(CircleShape)
            .background(bgColor)
            .border(width = 1.dp, color = borderColor, shape = CircleShape)
            .semantics { this.selected = selected }
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 7.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTintOverride ?: contentColor,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun DirectorySectionHeader(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    countLabel: String,
) {
    PulseSectionHeader(
        title = title,
        subtitle = subtitle,
        icon = icon,
        accentColor = accentColor,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        trailing = {
            PulseAccentPill(
                text = countLabel,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun BentoBoardTile(
    category: Category,
    isHero: Boolean,
    isPinned: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier,
    allowPin: Boolean = category.slug.isNotBlank(),
) {
    val spec = NodePulseThemeTokens.categorySpec(category.slug)
    val isDark = NodePulseThemeTokens.isDark
    val haptics = LocalHapticFeedback.current
    val coinCost = maxOf(category.topicCoinCost, spec.postCoinCost)
    val shape = if (isHero) {
        PulseArchitecturalShapes.HeroBento
    } else {
        PulseArchitecturalShapes.ElevatedTile
    }

    var pinBounce by remember { mutableStateOf(false) }
    LaunchedEffect(pinBounce) {
        if (pinBounce) {
            delay(180)
            pinBounce = false
        }
    }
    val starScale by animateFloatAsState(
        targetValue = if (pinBounce) 1.3f else 1.0f,
        animationSpec = NodePulseMotion.microBounceSpring(),
        label = "bentoPinStarScale",
    )

    PulseInteractiveCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        isHero = isHero,
        accentColor = spec.spineColor,
        shape = shape,
        elevation = if (isHero) PulseElevation.Hero else PulseElevation.Card,
    ) {
        // Subtle editorial watermark on Hero Bento cards for depth and personality
        if (isHero) {
            Icon(
                imageVector = spec.icon,
                contentDescription = null,
                tint = spec.spineColor,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 12.dp, y = 14.dp)
                    .size(84.dp)
                    .graphicsLayer {
                        alpha = if (isDark) 0.09f else 0.06f
                        rotationZ = -14f
                    },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isHero) 16.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (isHero) 10.dp else 9.dp),
        ) {
            if (isHero) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(spec.softTonalBrush)
                            .border(
                                width = 0.9.dp,
                                color = spec.spineColor.copy(alpha = if (isDark) 0.48f else 0.28f),
                                shape = RoundedCornerShape(13.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = spec.icon,
                            contentDescription = null,
                            tint = spec.contentColor,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = category.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            BoardSlugBadge(
                                slug = category.slug.ifBlank { "all" },
                                spec = spec,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(32.dp)
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(spec.heroBrush),
                        )
                    }

                    if (coinCost > 0) {
                        BoardCoinCostPill(coins = coinCost)
                    }

                    if (allowPin) {
                        BentoPinStarButton(
                            isPinned = isPinned,
                            starScale = starScale,
                            onClick = {
                                pinBounce = true
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onTogglePin()
                            },
                        )
                    }
                }

                Text(
                    text = category.description.ifBlank { spec.editorialDescription },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(spec.containerColor.copy(alpha = if (isDark) 0.55f else 0.72f))
                            .border(
                                width = 0.75.dp,
                                color = spec.spineColor.copy(alpha = if (isDark) 0.36f else 0.22f),
                                shape = CircleShape,
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = if (category.slug.isBlank()) "全站聚合" else "进入版块",
                            style = NodePulseThemeTokens.extraTypography.microLabel,
                            color = spec.contentColor,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            tint = spec.contentColor,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(spec.softTonalBrush)
                            .border(
                                width = 0.85.dp,
                                color = spec.spineColor.copy(alpha = if (isDark) 0.42f else 0.24f),
                                shape = RoundedCornerShape(11.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = spec.icon,
                            contentDescription = null,
                            tint = spec.contentColor,
                            modifier = Modifier.size(19.dp),
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    if (coinCost > 0) {
                        BoardCoinCostPill(coins = coinCost)
                        Spacer(modifier = Modifier.width(5.dp))
                    }

                    if (allowPin) {
                        BentoPinStarButton(
                            isPinned = isPinned,
                            starScale = starScale,
                            onClick = {
                                pinBounce = true
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onTogglePin()
                            },
                        )
                    }
                }

                Text(
                    text = category.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = category.description.ifBlank { spec.editorialDescription },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BoardSlugBadge(
                        slug = category.slug.ifBlank { "all" },
                        spec = spec,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(spec.containerColor.copy(alpha = if (isDark) 0.50f else 0.70f))
                            .border(
                                width = 0.7.dp,
                                color = spec.spineColor.copy(alpha = if (isDark) 0.34f else 0.20f),
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            tint = spec.contentColor,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardSlugBadge(
    slug: String,
    spec: com.nodeseek.app.ui.theme.CategoryVisualSpec,
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(spec.containerColor.copy(alpha = if (isDark) 0.72f else 0.85f))
            .border(
                width = 0.7.dp,
                color = spec.spineColor.copy(alpha = if (isDark) 0.40f else 0.22f),
                shape = shape,
            )
            .padding(horizontal = 5.5.dp, vertical = 1.5.dp),
    ) {
        Text(
            text = "/$slug",
            style = NodePulseThemeTokens.extraTypography.microLabel.copy(
                fontFamily = FontFamily.Monospace,
            ),
            color = spec.contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BoardCoinCostPill(coins: Int) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(ext.drumstickContainer)
            .border(
                width = 0.75.dp,
                color = ext.drumstickGoldEnd.copy(alpha = if (isDark) 0.44f else 0.28f),
                shape = CircleShape,
            )
            .padding(horizontal = 6.5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Restaurant,
            contentDescription = null,
            tint = ext.onDrumstickContainer,
            modifier = Modifier.size(10.dp),
        )
        Text(
            text = "$coins",
            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
            color = ext.onDrumstickContainer,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun BentoPinStarButton(
    isPinned: Boolean,
    starScale: Float,
    onClick: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val starTint by animateColorAsState(
        targetValue = if (isPinned) {
            ext.drumstickGoldStart
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
        },
        label = "pinStarTint",
    )
    val starContainer by animateColorAsState(
        targetValue = if (isPinned) {
            ext.drumstickContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
        },
        label = "pinStarBg",
    )
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(starContainer)
            .border(
                width = if (isPinned) 0.8.dp else 0.dp,
                color = if (isPinned) ext.drumstickGoldStart.copy(alpha = 0.42f) else Color.Transparent,
                shape = CircleShape,
            ),
    ) {
        Icon(
            imageVector = if (isPinned) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            contentDescription = if (isPinned) "已常驻，点击移除" else "常驻到首页",
            tint = starTint,
            modifier = Modifier
                .size(17.dp)
                .graphicsLayer {
                    scaleX = starScale
                    scaleY = starScale
                },
        )
    }
}

// ── Mode 2: Unified Search Experience ─────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnifiedSearchContent(
    uiState: BoardsUiState,
    bottomContentPadding: Dp,
    selectedTopicId: Long?,
    onSelectRecentQuery: (String) -> Unit,
    onRemoveRecentQuery: (String) -> Unit,
    onClearRecentQueries: () -> Unit,
    onSelectScope: (SearchScope) -> Unit,
    onSelectCategoryFilter: (String) -> Unit,
    onSelectSort: (FeedSort) -> Unit,
    onSubmitCurrentQuery: () -> Unit,
    onOpenGoogleSearch: () -> Unit,
    onJumpToBoard: (String) -> Unit,
    onOpenTopic: (topicId: Long, initialFloor: Int, titleHint: String) -> Unit,
    onOpenUserProfile: (uid: Long, usernameHint: String) -> Unit,
    onBookmarkToggle: (TopicSummary) -> Unit,
    onLoadNextPage: () -> Unit,
    onVerifyWebView: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && last >= total - 4
        }
    }
    LaunchedEffect(shouldLoadMore, uiState.searchHasNextPage, uiState.isSearchingNextPage) {
        snapshotFlow { shouldLoadMore }
            .distinctUntilChanged()
            .collect { nearEnd ->
                if (nearEnd && uiState.searchHasNextPage && !uiState.isSearchingNextPage) {
                    onLoadNextPage()
                }
            }
    }

    val visibleCategories = remember(uiState.categories) {
        uiState.categories.filterNot { it.isAdminOnly }
    }
    val selectedCategoryName = remember(visibleCategories, uiState.searchCategoryFilterSlug) {
        visibleCategories.firstOrNull { it.slug == uiState.searchCategoryFilterSlug }?.name ?: "全部"
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = 8.dp,
            bottom = bottomContentPadding + 16.dp,
        ),
    ) {
        // Scope Filter (`找帖子` / `找用户`) + Sort Switcher + Semantic Category Filter Rail
        item(key = "search_scope_controls") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 5.dp)
                    .pulseCard(
                        shape = PulseArchitecturalShapes.ElevatedTile,
                        elevation = PulseElevation.Subtle,
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SearchScope.entries.forEach { scope ->
                        val selected = uiState.searchScope == scope
                        val scopeIcon = if (scope == SearchScope.TOPICS) {
                            Icons.AutoMirrored.Rounded.Article
                        } else {
                            Icons.Rounded.PersonSearch
                        }
                        BentoFilterPill(
                            label = scope.label,
                            selected = selected,
                            icon = scopeIcon,
                            onClick = { onSelectScope(scope) },
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    if (uiState.searchScope == SearchScope.TOPICS) {
                        PulseAccentPill(
                            text = selectedCategoryName,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (uiState.searchScope == SearchScope.TOPICS) {
                    SortSegmentedSwitcher(
                        options = SEARCH_SORT_SEGMENTS,
                        selectedValue = uiState.searchSort,
                        onSelect = onSelectSort,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = visibleCategories,
                            key = { "search_cat_${it.slug}" },
                        ) { cat ->
                            val selected = uiState.searchCategoryFilterSlug == cat.slug
                            val spec = NodePulseThemeTokens.categorySpec(cat.slug)
                            val interactionSource = remember { MutableInteractionSource() }
                            Row(
                                modifier = Modifier
                                    .pulsePressScale(
                                        interactionSource = interactionSource,
                                        pressedScale = NodePulseMotion.chipPressScale,
                                    )
                                    .clip(CircleShape)
                                    .then(
                                        if (selected) {
                                            Modifier.background(spec.softTonalBrush)
                                        } else {
                                            Modifier.background(
                                                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                                            )
                                        },
                                    )
                                    .border(
                                        width = if (selected) 1.dp else 0.75.dp,
                                        color = if (selected) {
                                            spec.spineColor.copy(alpha = if (isDark) 0.55f else 0.35f)
                                        } else {
                                            ext.cardBorder
                                        },
                                        shape = CircleShape,
                                    )
                                    .semantics { this.selected = selected }
                                    .clickable(
                                        interactionSource = interactionSource,
                                        indication = LocalIndication.current,
                                        role = Role.Tab,
                                        onClick = { onSelectCategoryFilter(cat.slug) },
                                    )
                                    .padding(horizontal = 11.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.5.dp),
                            ) {
                                if (selected) {
                                    Icon(
                                        imageVector = spec.icon,
                                        contentDescription = null,
                                        tint = spec.contentColor,
                                        modifier = Modifier.size(13.dp),
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(spec.spineColor),
                                    )
                                }
                                Text(
                                    text = cat.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) {
                                        spec.contentColor
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Instant matching Boards quick-jump row while typing
        if (uiState.queryInput.isNotBlank() && uiState.filteredBentoCategories.isNotEmpty()) {
            item(key = "matching_boards_quick_jump") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PulseSectionHeader(
                        title = "匹配版块直达",
                        icon = Icons.Rounded.Bolt,
                        accentColor = MaterialTheme.colorScheme.primary,
                        trailing = {
                            CompactCountBadge(
                                count = uiState.filteredBentoCategories.size,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(
                            items = uiState.filteredBentoCategories,
                            key = { "match_board_${it.slug}" },
                        ) { cat ->
                            val spec = NodePulseThemeTokens.categorySpec(cat.slug)
                            val interactionSource = remember { MutableInteractionSource() }
                            Row(
                                modifier = Modifier
                                    .pulsePressScale(
                                        interactionSource = interactionSource,
                                        pressedScale = NodePulseMotion.chipPressScale,
                                    )
                                    .clip(PulseArchitecturalShapes.MediumBadge)
                                    .background(spec.softTonalBrush)
                                    .border(
                                        width = 0.9.dp,
                                        color = spec.spineColor.copy(alpha = if (isDark) 0.45f else 0.28f),
                                        shape = PulseArchitecturalShapes.MediumBadge,
                                    )
                                    .clickable(
                                        interactionSource = interactionSource,
                                        indication = LocalIndication.current,
                                        role = Role.Button,
                                        onClick = { onJumpToBoard(cat.slug) },
                                    )
                                    .padding(horizontal = 11.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = spec.icon,
                                    contentDescription = null,
                                    tint = spec.contentColor,
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(
                                    text = "进入「${cat.name}」",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = spec.contentColor,
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                    contentDescription = null,
                                    tint = spec.contentColor,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Prominent 1-Tap Google Site Search (`site:www.nodeseek.com`) Card
        item(key = "google_site_search_fallback") {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                GoogleSiteSearchCard(
                    query = uiState.queryInput.ifBlank { uiState.submittedQuery },
                    onClick = onOpenGoogleSearch,
                )
            }
        }

        // Recent Search History Chips (when no submitted query yet)
        if (uiState.submittedQuery.isBlank() && uiState.recentSearchQueries.isNotEmpty()) {
            item(key = "recent_searches_section") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .pulseCard(
                            shape = PulseArchitecturalShapes.ElevatedTile,
                            elevation = PulseElevation.Subtle,
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PulseSectionHeader(
                        title = "最近搜索记录",
                        icon = Icons.Rounded.History,
                        accentColor = MaterialTheme.colorScheme.secondary,
                        trailing = {
                            val clearInteraction = remember { MutableInteractionSource() }
                            Row(
                                modifier = Modifier
                                    .pulsePressScale(
                                        interactionSource = clearInteraction,
                                        pressedScale = NodePulseMotion.chipPressScale,
                                    )
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable(
                                        interactionSource = clearInteraction,
                                        indication = LocalIndication.current,
                                        role = Role.Button,
                                        onClick = onClearRecentQueries,
                                    )
                                    .padding(horizontal = 9.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteSweep,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = "清空",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        uiState.recentSearchQueries.forEach { recent ->
                            val interactionSource = remember { MutableInteractionSource() }
                            Row(
                                modifier = Modifier
                                    .pulsePressScale(
                                        interactionSource = interactionSource,
                                        pressedScale = NodePulseMotion.chipPressScale,
                                    )
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .border(width = 0.75.dp, color = ext.cardBorder, shape = CircleShape)
                                    .clickable(
                                        interactionSource = interactionSource,
                                        indication = LocalIndication.current,
                                        role = Role.Button,
                                        onClick = { onSelectRecentQuery(recent) },
                                    )
                                    .padding(start = 11.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Text(
                                    text = recent,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "删除此条记录",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .clickable { onRemoveRecentQuery(recent) },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Results Section
        when {
            uiState.isSearching -> {
                items(count = 4, key = { "search_skeleton_$it" }) {
                    PulseTopicCardSkeleton()
                }
            }

            uiState.searchErrorMessage != null -> {
                item(key = "search_error_state") {
                    ExpressiveStateCard(
                        icon = if (uiState.isSearchChallengeRequired) {
                            Icons.Rounded.Shield
                        } else {
                            Icons.Rounded.CloudOff
                        },
                        title = if (uiState.isSearchChallengeRequired) {
                            "需要完成安全验证"
                        } else {
                            "站内搜索暂不可用"
                        },
                        description = uiState.searchErrorMessage,
                        accentColor = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        actions = {
                            if (uiState.isSearchChallengeRequired) {
                                Button(onClick = onVerifyWebView, shape = CircleShape) {
                                    Text(text = "完成网页验证 / 登录")
                                }
                            }
                            OutlinedButton(onClick = onSubmitCurrentQuery, shape = CircleShape) {
                                Text(text = "重试站内搜索")
                            }
                        },
                    )
                }
            }

            uiState.submittedQuery.isNotBlank() && uiState.searchScope == SearchScope.USERS -> {
                if (uiState.searchUsers.isEmpty()) {
                    item(key = "empty_users") {
                        ExpressiveStateCard(
                            icon = Icons.Rounded.PersonSearch,
                            title = "未找到匹配成员",
                            description = "没有找到名为「${uiState.submittedQuery}」的社区成员，可尝试核对用户名或更换关键词",
                            accentColor = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                } else {
                    item(key = "user_results_header") {
                        SearchResultsHeaderBar(
                            countText = "找到 ${uiState.searchUsers.size} 位社区成员",
                        )
                    }
                    items(
                        items = uiState.searchUsers,
                        key = { "user_${it.uid}" },
                    ) { member ->
                        MemberSearchResultCard(
                            member = member,
                            onClick = { onOpenUserProfile(member.uid, member.username) },
                        )
                    }
                }
            }

            uiState.submittedQuery.isNotBlank() && uiState.searchScope == SearchScope.TOPICS -> {
                if (uiState.searchTopics.isEmpty()) {
                    item(key = "empty_topics") {
                        ExpressiveStateCard(
                            icon = Icons.Rounded.SearchOff,
                            title = "暂无站内匹配帖子",
                            description = "未找到匹配「${uiState.submittedQuery}」的站内帖子，可使用上方 Google 站内搜索精准检索全站历史帖",
                            accentColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            actions = {
                                Button(onClick = onOpenGoogleSearch, shape = CircleShape) {
                                    Text(text = "使用 Google 站内搜索")
                                }
                            },
                        )
                    }
                } else {
                    item(key = "topic_results_header") {
                        val suffix = if (uiState.searchHasNextPage) "+" else ""
                        SearchResultsHeaderBar(
                            countText = "找到 ${uiState.searchTopics.size}$suffix 条相关主题",
                        )
                    }
                    items(
                        items = uiState.searchTopics,
                        key = { "search_topic_${it.id}" },
                    ) { topic ->
                        PulseTopicCard(
                            topic = topic.toCardModel(),
                            selectedTopicId = selectedTopicId,
                            onClick = {
                                onOpenTopic(topic.id, topic.lastReadFloor ?: 0, topic.title)
                            },
                            onBookmarkToggle = { onBookmarkToggle(topic) },
                            onAuthorClick = { uid -> onOpenUserProfile(uid, topic.authorName) },
                            onCategoryClick = onSelectCategoryFilter,
                        )
                    }

                    if (uiState.isSearchingNextPage) {
                        item(key = "search_more_spinner") {
                            LoadingMoreFooter(label = "正在加载更多结果…")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultsHeaderBar(countText: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        Text(
            text = countText,
            style = NodePulseThemeTokens.extraTypography.microLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        PulseGradientDivider(modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun GoogleSiteSearchCard(
    query: String,
    onClick: () -> Unit,
) {
    val isDark = NodePulseThemeTokens.isDark

    PulseInteractiveCard(
        onClick = onClick,
        isHero = true,
        accentColor = MaterialTheme.colorScheme.primary,
        shape = PulseArchitecturalShapes.ElevatedTile,
        elevation = PulseElevation.CardElevated,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PulseBrushes.brandGradient()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.TravelExplore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(21.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = if (query.isBlank()) {
                        "Google 站内搜索 NodeSeek 全站"
                    } else {
                        "使用 Google 站内搜索「$query」"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(
                                MaterialTheme.colorScheme.primaryContainer.copy(
                                    alpha = if (isDark) 0.72f else 0.9f,
                                ),
                            )
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = "site:nodeseek.com",
                            style = NodePulseThemeTokens.extraTypography.microLabel.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = "免登录免频控精准检索",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(
                            alpha = if (isDark) 0.65f else 0.85f,
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = "打开浏览器搜索",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun MemberSearchResultCard(
    member: FloorAuthor,
    onClick: () -> Unit,
) {
    val rankSpec = member.level?.let { NodePulseThemeTokens.rankSpec(it) }

    PulseInteractiveCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        isHero = false,
        accentColor = rankSpec?.glowColor,
        shape = PulseArchitecturalShapes.EditorialCard,
        elevation = PulseElevation.Card,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PulseAvatar(
                avatarUrl = member.avatarUrl.ifBlank { "/avatar/${member.uid}.png" },
                username = member.username,
                size = 42.dp,
                rankLevel = member.level,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = member.username,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (member.level != null) {
                        RankMicroBadge(level = member.level, showPersona = true)
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 6.dp, vertical = 1.5.dp),
                ) {
                    Text(
                        text = "UID ${member.uid}",
                        style = NodePulseThemeTokens.extraTypography.microLabel.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ── Mode 3: Filtered Inline Board Feed Sub-View ───────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InlineBoardFeedContent(
    uiState: BoardsUiState,
    bottomContentPadding: Dp,
    selectedTopicId: Long?,
    onOpenTopic: (topicId: Long, initialFloor: Int, titleHint: String) -> Unit,
    onOpenUserProfile: (uid: Long, usernameHint: String) -> Unit,
    onBookmarkToggle: (TopicSummary) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onVerifyWebView: () -> Unit,
    onSwitchToFullWebFeed: () -> Unit,
) {
    val listState = rememberLazyListState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && last >= total - 4
        }
    }
    val filteredPageAutoAdvance = uiState.inlineBoardTopics.isEmpty() &&
        uiState.inlineBoardHasNextPage &&
        !uiState.isInlineBoardLoading &&
        !uiState.isInlineBoardRefreshing &&
        !uiState.inlineBoardIsRssFallback &&
        uiState.inlineBoardError == null &&
        uiState.inlineBoardPage < 3

    LaunchedEffect(
        shouldLoadMore,
        filteredPageAutoAdvance,
        uiState.inlineBoardHasNextPage,
        uiState.isInlineBoardLoading,
        uiState.isInlineBoardRefreshing,
        uiState.isInlineBoardLoadingMore,
        uiState.inlineBoardIsRssFallback,
        uiState.inlineBoardError,
        uiState.inlineBoardPage,
        uiState.inlineBoardTopics.size,
    ) {
        if ((shouldLoadMore || filteredPageAutoAdvance) &&
            uiState.inlineBoardHasNextPage &&
            !uiState.isInlineBoardLoading &&
            !uiState.isInlineBoardRefreshing &&
            !uiState.isInlineBoardLoadingMore &&
            !uiState.inlineBoardIsRssFallback &&
            uiState.inlineBoardError == null
        ) {
            onLoadMore()
        }
    }

    PullToRefreshBox(
        isRefreshing = uiState.isInlineBoardRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 6.dp,
                bottom = bottomContentPadding + 16.dp,
            ),
        ) {
            if (uiState.inlineBoardPinnedTopics.isNotEmpty()) {
                item(key = "inline_board_pinned") {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        CollapsiblePinnedTicker(
                            pinnedTopics = uiState.inlineBoardPinnedTopics.map {
                                PinnedTickerTopic(
                                    id = it.id,
                                    title = it.title,
                                    categorySlug = it.categorySlug,
                                    categoryName = it.categoryName,
                                )
                            },
                            onTopicClick = { id ->
                                val t = uiState.inlineBoardPinnedTopics.firstOrNull { it.id == id }
                                onOpenTopic(id, t?.lastReadFloor ?: 0, t?.title.orEmpty())
                            },
                        )
                    }
                }
            }

            when {
                uiState.isInlineBoardLoading && uiState.inlineBoardTopics.isEmpty() -> {
                    items(count = 5, key = { "inline_skeleton_$it" }) {
                        PulseTopicCardSkeleton()
                    }
                }

                uiState.inlineBoardError != null && uiState.inlineBoardTopics.isEmpty() -> {
                    item(key = "inline_error") {
                        ExpressiveStateCard(
                            icon = if (uiState.inlineBoardChallengeRequired) {
                                Icons.Rounded.Shield
                            } else {
                                Icons.Rounded.CloudOff
                            },
                            title = if (uiState.inlineBoardChallengeRequired) {
                                "需要完成安全验证"
                            } else {
                                "暂时无法载入版块"
                            },
                            description = uiState.inlineBoardError,
                            accentColor = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            actions = {
                                if (uiState.inlineBoardChallengeRequired) {
                                    Button(onClick = onVerifyWebView, shape = CircleShape) {
                                        Text(text = "完成网页验证 / 登录")
                                    }
                                }
                                OutlinedButton(onClick = onRefresh, shape = CircleShape) {
                                    Text(text = "重试")
                                }
                            },
                        )
                    }
                }

                !uiState.isInlineBoardLoading &&
                    uiState.inlineBoardTopics.isEmpty() &&
                    uiState.inlineBoardPinnedTopics.isEmpty() &&
                    uiState.activeInlineBoardSlug != null -> {
                    when {
                        uiState.inlineBoardIsRssFallback -> {
                            item(key = "inline_rss_empty") {
                                InlineBoardRssFallbackCard(
                                    isEmptyBoard = true,
                                    isChallengeRequired = uiState.inlineBoardChallengeRequired,
                                    preferRssFallback = uiState.preferRssFallback,
                                    onVerifyWebView = onVerifyWebView,
                                    onSwitchToFullWebFeed = onSwitchToFullWebFeed,
                                    onRefresh = onRefresh,
                                )
                            }
                        }

                        uiState.inlineBoardHasNextPage &&
                            (uiState.isInlineBoardLoadingMore || filteredPageAutoAdvance) -> {
                            item(key = "inline_filtered_loading") {
                                LoadingMoreFooter(label = "正在加载更多匹配主题…")
                            }
                        }

                        else -> {
                            item(key = "inline_empty") {
                                ExpressiveStateCard(
                                    icon = Icons.Rounded.Forum,
                                    title = "该版块暂无主题",
                                    description = "当前排序下暂未获取到主题内容，点击刷新重新拉取最新动态",
                                    accentColor = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    actions = {
                                        OutlinedButton(onClick = onRefresh, shape = CircleShape) {
                                            Icon(
                                                imageVector = Icons.Rounded.Refresh,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(text = "刷新版块")
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                else -> {
                    items(
                        items = uiState.inlineBoardTopics,
                        key = { "inline_topic_${it.id}" },
                    ) { topic ->
                        PulseTopicCard(
                            topic = topic.toCardModel(),
                            showCategoryTag = false,
                            selectedTopicId = selectedTopicId,
                            onClick = {
                                onOpenTopic(topic.id, topic.lastReadFloor ?: 0, topic.title)
                            },
                            onBookmarkToggle = { onBookmarkToggle(topic) },
                            onAuthorClick = { uid -> onOpenUserProfile(uid, topic.authorName) },
                        )
                    }

                    when {
                        uiState.isInlineBoardLoadingMore -> {
                            item(key = "inline_more_spinner") {
                                LoadingMoreFooter(label = "正在加载更多主题…")
                            }
                        }

                        uiState.inlineBoardError != null -> {
                            item(key = "inline_more_error") {
                                ExpressiveStateCard(
                                    icon = if (uiState.inlineBoardChallengeRequired) {
                                        Icons.Rounded.Shield
                                    } else {
                                        Icons.Rounded.Refresh
                                    },
                                    title = "加载下一页未成功",
                                    description = uiState.inlineBoardError,
                                    accentColor = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    actions = {
                                        if (uiState.inlineBoardChallengeRequired) {
                                            Button(onClick = onVerifyWebView, shape = CircleShape) {
                                                Text(text = "完成网页验证")
                                            }
                                        }
                                        OutlinedButton(onClick = onLoadMore, shape = CircleShape) {
                                            Text(text = "点按重试")
                                        }
                                    },
                                )
                            }
                        }

                        uiState.inlineBoardIsRssFallback -> {
                            item(key = "inline_rss_footer") {
                                InlineBoardRssFallbackCard(
                                    isEmptyBoard = false,
                                    isChallengeRequired = uiState.inlineBoardChallengeRequired,
                                    preferRssFallback = uiState.preferRssFallback,
                                    onVerifyWebView = onVerifyWebView,
                                    onSwitchToFullWebFeed = onSwitchToFullWebFeed,
                                    onRefresh = onRefresh,
                                )
                            }
                        }

                        uiState.inlineBoardHasNextPage -> {
                            item(key = "inline_load_more") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                        .clip(CircleShape)
                                        .clickable(onClick = onLoadMore)
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

                        !uiState.isInlineBoardRefreshing -> {
                            item(key = "inline_end") {
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
    }
}

// ── Shared Local UI Primitives ────────────────────────────────────────────────────────────

@Composable
private fun PulseInteractiveCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isHero: Boolean = false,
    accentColor: Color? = null,
    shape: Shape = if (isHero) PulseArchitecturalShapes.HeroBento else PulseArchitecturalShapes.ElevatedTile,
    elevation: Dp = if (isHero) PulseElevation.Hero else PulseElevation.Card,
    role: Role? = Role.Button,
    pressedScale: Float = NodePulseMotion.cardPressScale,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val cardSurfaceMod = if (isHero) {
        Modifier.pulseHeroCard(
            accentColor = accentColor ?: MaterialTheme.colorScheme.primary,
            shape = shape,
            elevation = elevation,
        )
    } else {
        Modifier.pulseCard(
            shape = shape,
            elevation = elevation,
            accentColor = accentColor,
        )
    }

    Box(
        modifier = modifier
            .pulsePressScale(interactionSource = interactionSource, pressedScale = pressedScale)
            .then(cardSurfaceMod)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = role,
                onClick = onClick,
            ),
        content = content,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpressiveStateCard(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    actions: (@Composable () -> Unit)? = null,
) {
    val isDark = NodePulseThemeTokens.isDark

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.HeroBento,
                elevation = PulseElevation.Card,
                accentColor = accentColor,
            )
            .padding(horizontal = 22.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = if (isDark) 0.18f else 0.11f))
                .border(
                    width = 1.dp,
                    color = accentColor.copy(alpha = if (isDark) 0.38f else 0.22f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(25.dp),
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
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (actions != null) {
            FlowRow(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                actions()
            }
        }
    }
}

@Composable
private fun InlineBoardRssFallbackCard(
    isEmptyBoard: Boolean,
    isChallengeRequired: Boolean,
    preferRssFallback: Boolean,
    onVerifyWebView: () -> Unit,
    onSwitchToFullWebFeed: () -> Unit,
    onRefresh: () -> Unit,
) {
    ExpressiveStateCard(
        icon = if (isChallengeRequired) Icons.Rounded.Shield else Icons.Rounded.Bolt,
        title = if (isChallengeRequired) {
            "当前为 RSS 精简模式（需要网页验证）"
        } else {
            "当前处于 RSS 精简模式"
        },
        description = if (isEmptyBoard) {
            "RSS 仅包含全站最新约 20 条公开帖子，当前版块暂无匹配条目且不支持翻页。"
        } else {
            "RSS 仅包含全站最新约 20 条公开帖子（当前版块为本地筛选，且不支持下拉翻页）。"
        },
        accentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        actions = {
            if (preferRssFallback && !isChallengeRequired) {
                Button(onClick = onSwitchToFullWebFeed, shape = CircleShape) {
                    Text(text = "切换为完整网页流")
                }
                OutlinedButton(onClick = onVerifyWebView, shape = CircleShape) {
                    Text(text = "网页验证 / 登录")
                }
            } else {
                Button(onClick = onVerifyWebView, shape = CircleShape) {
                    Text(text = "完成网页验证，加载完整内容")
                }
                if (preferRssFallback) {
                    FilledTonalButton(onClick = onSwitchToFullWebFeed, shape = CircleShape) {
                        Text(text = "关闭 RSS 优先")
                    }
                } else {
                    OutlinedButton(onClick = onRefresh, shape = CircleShape) {
                        Text(text = "重试")
                    }
                }
            }
        },
    )
}

@Composable
private fun LoadingMoreFooter(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
