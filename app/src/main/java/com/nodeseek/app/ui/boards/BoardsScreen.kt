package com.nodeseek.app.ui.boards

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.ui.components.CompactCountBadge
import com.nodeseek.app.ui.components.PulseAvatar
import com.nodeseek.app.ui.components.PulseSearchField
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
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.pulseHeroCard
import com.nodeseek.app.ui.theme.pulsePressScale
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val SEARCH_SORT_SEGMENTS: List<SortSegmentItem<FeedSort>> = listOf(
    SortSegmentItem(FeedSort.LATEST_REPLY, "最新回复"),
    SortSegmentItem(FeedSort.NEWEST_TOPIC, "最新发布"),
)

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
 * Anode Unified Search Screen (`全站统一搜索`):
 * - Recent search history chips + clear, `找帖子` / `找用户` scope chips, board & sort filters,
 *   matching board quick-jump back to Explore, and a prominent **1-Tap Google 站内搜索
 *   (`site:www.nodeseek.com`)** fallback card.
 */
@Composable
fun BoardsScreen(
    onOpenTopic: (topicId: Long, initialFloor: Int, titleHint: String) -> Unit,
    modifier: Modifier = Modifier,
    autoFocusSearch: Boolean = false,
    focusSearchTrigger: Int = 0,
    onAutoFocusConsumed: () -> Unit = {},
    onSelectBoard: ((String) -> Unit)? = null,
    onOpenUserProfile: (uid: Long, usernameHint: String) -> Unit = { _, _ -> },
    onRequestWebViewAuth: (() -> Unit)? = null,
    bottomContentPadding: Dp = 98.dp,
    onBack: (() -> Unit)? = null,
    selectedTopicId: Long? = null,
    previewUiState: BoardsUiState? = null,
    viewModel: BoardsViewModel = viewModel(
        factory = BoardsViewModel.factory(
            container = LocalAppContainer.current,
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

    val triggerWebViewAuth: () -> Unit = remember(onRequestWebViewAuth) {
        {
            if (onRequestWebViewAuth != null) {
                onRequestWebViewAuth()
            } else {
                showBuiltInWebViewSheet = true
            }
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

    BackHandler(enabled = onBack != null) {
        dismissSearchKeyboard()
        onBack?.invoke()
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
            UnifiedSearchHeader(
                query = uiState.queryInput,
                focusRequester = focusRequester,
                onBack = if (onBack != null) {
                    {
                        dismissSearchKeyboard()
                        onBack()
                    }
                } else {
                    null
                },
                onQueryChange = viewModel::updateQueryInput,
                onSubmitSearch = {
                    dismissSearchKeyboard()
                    viewModel.submitSearch()
                    coroutineScope.launch {
                        searchListState.scrollToItem(0)
                    }
                },
                onClearInput = { viewModel.updateQueryInput("") },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
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
                    viewModel.submitSearch(
                        uiState.queryInput.trim().ifEmpty { uiState.submittedQuery },
                    )
                },
                onOpenGoogleSearch = {
                    dismissSearchKeyboard()
                    runCatching { uriHandler.openUri(uiState.googleSiteSearchUrl) }
                },
                onJumpToBoard = if (onSelectBoard != null) {
                    { slug ->
                        dismissSearchKeyboard()
                        onSelectBoard(slug)
                    }
                } else {
                    null
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

// ── Top Header ────────────────────────────────────────────────────────────────────────────

@Composable
private fun UnifiedSearchHeader(
    query: String,
    focusRequester: FocusRequester,
    onBack: (() -> Unit)?,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onClearInput: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseGlassSurface(
                showTopBorder = false,
                showBottomBorder = true,
                tonalAccent = MaterialTheme.colorScheme.primary,
            )
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(ext.cardSurface)
                    .border(width = 0.8.dp, color = ext.cardBorder, shape = CircleShape),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(19.dp),
                )
            }
        }

        PulseSearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "输入关键词搜帖子 / 用户…",
            minHeight = 46.dp,
            onClear = onClearInput,
            onSearch = onSubmitSearch,
            focusRequester = focusRequester,
            modifier = Modifier.weight(1f),
        )

        AnimatedVisibility(
            visible = query.isNotBlank(),
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

@Composable
private fun SearchScopeFilterPill(
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
        label = "searchScopeFilterBg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "searchScopeFilterFg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.55f else 0.35f)
        } else {
            ext.cardBorder
        },
        label = "searchScopeFilterBorder",
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

// ── Unified Search Experience ─────────────────────────────────────────────────────────────

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
    onJumpToBoard: ((String) -> Unit)?,
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
    LaunchedEffect(
        shouldLoadMore,
        uiState.searchHasNextPage,
        uiState.isSearchingNextPage,
        uiState.searchNextPageError,
    ) {
        snapshotFlow { shouldLoadMore }
            .distinctUntilChanged()
            .collect { nearEnd ->
                if (nearEnd &&
                    uiState.searchHasNextPage &&
                    !uiState.isSearchingNextPage &&
                    uiState.searchNextPageError == null
                ) {
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
                        SearchScopeFilterPill(
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

        // Instant matching Boards quick-jump row while typing (switches Explore horizontal strip)
        if (onJumpToBoard != null && uiState.queryInput.isNotBlank() && uiState.matchingCategories.isNotEmpty()) {
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
                                count = uiState.matchingCategories.size,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(
                            items = uiState.matchingCategories,
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
                                    Text(
                                        text = "完成网页验证 / 登录",
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            OutlinedButton(onClick = onSubmitCurrentQuery, shape = CircleShape) {
                                Text(
                                    text = "重试站内搜索",
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
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
                                    Text(
                                        text = "使用 Google 站内搜索",
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
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
                    } else if (uiState.searchNextPageError != null) {
                        item(key = "search_next_page_error") {
                            ExpressiveStateCard(
                                icon = if (uiState.isSearchChallengeRequired) {
                                    Icons.Rounded.Shield
                                } else {
                                    Icons.Rounded.CloudOff
                                },
                                title = "加载下一页失败",
                                description = uiState.searchNextPageError,
                                accentColor = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                actions = {
                                    if (uiState.isSearchChallengeRequired) {
                                        Button(onClick = onVerifyWebView, shape = CircleShape) {
                                            Text(
                                                text = "完成网页验证",
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    OutlinedButton(onClick = onLoadNextPage, shape = CircleShape) {
                                        Text(
                                            text = "重试加载更多",
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                            )
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
