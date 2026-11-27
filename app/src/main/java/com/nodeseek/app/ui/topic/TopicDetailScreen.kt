@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    ExperimentalFoundationApi::class,
)

package com.nodeseek.app.ui.topic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.ThumbUpOffAlt
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.data.restock.MerchantLinkDetector
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.activity.DrandLuckyEngine
import com.nodeseek.app.domain.activity.LuckyDrawSpec
import com.nodeseek.app.domain.carpool.CarpoolStatusUpdater
import com.nodeseek.app.domain.trade.TradeTitleFormatter
import com.nodeseek.app.domain.valuation.TradeMarketReferenceState
import com.nodeseek.app.domain.valuation.ValuationMode
import com.nodeseek.app.ui.activity.LuckyDrawInlineBannerCard
import com.nodeseek.app.ui.activity.LuckyDrawSheet
import com.nodeseek.app.ui.activity.LuckyDrawSheetMode
import com.nodeseek.app.ui.carpool.CarpoolQuickStatusBar
import com.nodeseek.app.ui.carpool.CarpoolStatusSheet
import com.nodeseek.app.ui.composer.ImagePrivacyEditorSheet
import com.nodeseek.app.ui.share.TopicLongImageShareSheet
import com.nodeseek.app.ui.trade.TradeClosureSheet
import com.nodeseek.app.ui.valuation.MarketPremiumReferenceCard
import com.nodeseek.app.ui.valuation.VpsValuationBottomSheet
import com.nodeseek.app.ui.valuation.extractMarketQueryWithContext
import com.nodeseek.app.ui.components.NoOpNsAppWidgetController
import com.nodeseek.app.ui.components.NsAppWidgetController
import com.nodeseek.app.domain.model.AuthorBadge
import com.nodeseek.app.domain.model.FloorComment
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.navigation.isSafeExternalHttpUrl
import com.nodeseek.app.navigation.normalizeNodeSeekUrl
import com.nodeseek.app.navigation.openNodeSeekOrExternalUrl
import com.nodeseek.app.ui.restock.AddRestockWatchBottomSheet
import com.nodeseek.app.ui.trust.SellerTrustCardSheet
import com.nodeseek.app.ui.trust.SellerTrustQuickBadge
import com.nodeseek.app.ui.components.AccessLockChip
import com.nodeseek.app.ui.components.CategoryMicroTag
import com.nodeseek.app.ui.components.FloorNumberPill
import com.nodeseek.app.ui.components.MarkdownRenderer
import com.nodeseek.app.ui.components.NodeSeekDrumstickFilledIcon
import com.nodeseek.app.ui.components.NodeSeekDrumstickIcon
import com.nodeseek.app.ui.components.OpHighlightChip
import com.nodeseek.app.ui.components.PulseAvatar
import com.nodeseek.app.ui.components.PulseDropdownMenu
import com.nodeseek.app.ui.components.PulseDropdownMenuDivider
import com.nodeseek.app.ui.components.PulseDropdownMenuItem
import com.nodeseek.app.ui.components.PulseDropdownMenuSectionHeader
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.RankMicroBadge
import com.nodeseek.app.ui.components.RelativeTimeText
import com.nodeseek.app.ui.components.SortSegmentItem
import com.nodeseek.app.ui.components.SortSegmentedSwitcher
import com.nodeseek.app.ui.components.WebViewSessionSheet
import com.nodeseek.app.ui.components.formatCompactCount
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseShimmerBox
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.pulseHeroCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

private data class TopicTrustInspectTarget(
    val uid: Long,
    val username: String,
    val avatarUrl: String,
)

/**
 * Anode Magazine-Grade Topic Reader Screen (`TopicDetailScreen`).
 *
 * Visual & interaction architecture:
 * - Magazine Article Surface + Selectable `headlineSmall` title + Author Telemetry Strip
 * - Clean architectural hairline threaded floor stream with inline OP & floor badges
 * - Single Anchored Tonal Quick-Reply Bar with bookmark & floor-jump actions
 * - Right-Edge Draggable Floor Scrubber + Floor Jump Modal Sheet
 * - Full Markdown Reply & Edit Bottom Sheet with live preview, stickers, photo upload & drafts
 */
@Composable
fun TopicDetailScreen(
    topicId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialFloor: Int = 0,
    titleHint: String = "",
    refreshTrigger: Long = 0L,
    onNavigateToUser: (uid: Long, name: String) -> Unit = { _, _ -> },
    onNavigateToCategory: (slug: String) -> Unit = {},
    onOpenTopic: (topicId: Long, initialFloor: Int, titleHint: String) -> Unit = { _, _, _ -> },
    onEditTopic: (topicId: Long, categorySlug: String) -> Unit = { _, _ -> },
    onRequestWebViewAuth: ((url: String) -> Unit)? = null,
    onStartDirectMessage: ((peerUid: Long, peerName: String) -> Unit)? = null,
    onOpenFullNetBench: ((initialTarget: String) -> Unit)? = null,
    onOpenFullLiteSsh: (() -> Unit)? = null,
    onPublishSshToComposer: ((suggestedTitle: String, markdownContent: String) -> Unit)? = null,
    isEmbeddedInDualPane: Boolean = false,
    isDetailPaneMaximized: Boolean = false,
    onCloseDetailPane: (() -> Unit)? = null,
    onToggleMaximizePane: (() -> Unit)? = null,
    previewUiState: TopicDetailUiState? = null,
    viewModel: TopicDetailViewModel = viewModel(
        key = "topic_detail_$topicId",
        factory = TopicDetailViewModel.factory(
            container = LocalAppContainer.current,
            topicId = topicId,
            initialFloor = initialFloor,
            titleHint = titleHint,
        ),
    ),
) {
    val appContainer = LocalAppContainer.current
    val observedUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = previewUiState ?: observedUiState
    val snackbarHostState = remember { SnackbarHostState() }
    val lazyListState = rememberLazyListState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val haptics = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    var fallbackWebViewUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var actionSheetComment by remember { mutableStateOf<FloorComment?>(null) }
    var selectableSheetComment by remember { mutableStateOf<FloorComment?>(null) }
    var linkActionUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var restockWatchPrefillUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var lastLinkLongPressMs by remember { mutableLongStateOf(0L) }
    var trustSheetTarget by remember { mutableStateOf<TopicTrustInspectTarget?>(null) }
    var activeLuckyDrawSpec by remember { mutableStateOf<LuckyDrawSpec?>(null) }
    var activeLuckyDrawMode by remember { mutableStateOf<LuckyDrawSheetMode?>(null) }
    var showTradeClosureSheet by rememberSaveable { mutableStateOf(false) }
    var showCarpoolStatusSheet by rememberSaveable { mutableStateOf(false) }
    var showMarketValuationSheet by rememberSaveable { mutableStateOf(false) }
    var longImageShareTarget by remember { mutableStateOf<FloorComment?>(null) }
    var marketRefCooldownSec by remember { mutableIntStateOf(0) }
    val uploadedMarkdownChannel = remember { Channel<String>(Channel.BUFFERED) }

    LaunchedEffect(uiState.marketReference?.cooldownUntilEpochMillis) {
        val until = uiState.marketReference?.cooldownUntilEpochMillis ?: 0L
        if (until <= 0L) {
            marketRefCooldownSec = 0
            return@LaunchedEffect
        }
        while (true) {
            val remMs = until - System.currentTimeMillis()
            if (remMs <= 0L) {
                marketRefCooldownSec = 0
                break
            }
            marketRefCooldownSec = ((remMs + 999L) / 1000L).toInt()
            delay(1_000L)
        }
    }

    val openFloorMoreActions: (FloorComment) -> Unit = { comment ->
        if (System.currentTimeMillis() - lastLinkLongPressMs > 350L && linkActionUrl == null) {
            actionSheetComment = comment
        }
    }

    val openSellerTrust: (Long, String, String) -> Unit = { uid, username, avatarUrl ->
        if (uid > 0L || username.isNotBlank()) {
            trustSheetTarget = TopicTrustInspectTarget(
                uid = uid,
                username = username,
                avatarUrl = avatarUrl,
            )
        } else {
            onNavigateToUser(uid, username)
        }
    }

    val copyMarkdownSource: (FloorComment) -> Unit = { comment ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Anode Markdown", comment.markdownBody))
        coroutineScope.launch { snackbarHostState.showSnackbar("已复制 Markdown 源码") }
    }

    val triggerWebViewAuth: (String) -> Unit = remember(onRequestWebViewAuth) {
        { url ->
            if (onRequestWebViewAuth != null) {
                onRequestWebViewAuth(url)
            } else {
                fallbackWebViewUrl = url
            }
        }
    }

    var lastAppliedInitialFloor by remember(topicId) { mutableIntStateOf(initialFloor) }
    LaunchedEffect(topicId, initialFloor) {
        if (initialFloor > 0 && initialFloor != lastAppliedInitialFloor) {
            lastAppliedInitialFloor = initialFloor
            viewModel.jumpToFloor(initialFloor)
        }
    }

    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0L) {
            viewModel.onExternalRefreshTriggered(refreshTrigger)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TopicDetailUiEvent.ShowMessage -> {
                    launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        val result = snackbarHostState.showSnackbar(
                            message = event.message,
                            actionLabel = event.actionLabel,
                            duration = if (event.actionLabel != null) {
                                SnackbarDuration.Long
                            } else {
                                SnackbarDuration.Short
                            },
                        )
                        if (result == SnackbarResult.ActionPerformed && !event.webViewUrl.isNullOrBlank()) {
                            triggerWebViewAuth(event.webViewUrl)
                        }
                    }
                }
                is TopicDetailUiEvent.NavigateToUser -> {
                    onNavigateToUser(event.uid, event.username)
                }
                is TopicDetailUiEvent.RequestWebViewAuth -> {
                    triggerWebViewAuth(event.url)
                }
                is TopicDetailUiEvent.InsertUploadedMarkdown -> {
                    uploadedMarkdownChannel.trySend(event.snippet)
                }
            }
        }
    }

    // Track the floor the user is actually reading (live counter pill, scrubber & ReadHistoryEntry).
    // Uses the reply's own floor number from the item key and the item geometry relative to a
    // reading line near the top of the viewport (see computeReadingFloor), instead of the
    // bottom-most visible item. While a programmatic jump/resume scroll is pending the ViewModel
    // already holds the target floor, so screen reports are suspended until the scroll lands.
    // Combining with visibleFloor re-syncs the indicator whenever the ViewModel overwrites it
    // without moving the list (e.g. refresh, page append), which a plain distinct flow would miss.
    val pendingScrollFloorState = rememberUpdatedState(uiState.pendingScrollFloor)
    LaunchedEffect(lazyListState, viewModel) {
        val readingFloorFlow = snapshotFlow {
            if (pendingScrollFloorState.value != null) {
                null
            } else {
                val info = lazyListState.layoutInfo
                computeReadingFloor(
                    visibleItems = info.visibleItemsInfo.map { item ->
                        ReadingListItem(key = item.key, offset = item.offset, size = item.size)
                    },
                    viewportStart = info.viewportStartOffset,
                    viewportEnd = info.viewportEndOffset,
                    canScrollForward = lazyListState.canScrollForward,
                )
            }
        }
        combine(readingFloorFlow, viewModel.visibleFloor) { readingFloor, _ -> readingFloor }
            .collect { floor ->
                if (floor != null) {
                    viewModel.onVisibleFloorChanged(floor)
                }
            }
    }

    // Continuous multi-page append when approaching bottom in chronological mode
    val shouldAppendNextPage by remember {
        derivedStateOf {
            val info = lazyListState.layoutInfo
            val total = info.totalItemsCount
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && last >= total - 4
        }
    }
    LaunchedEffect(
        shouldAppendNextPage,
        uiState.reverseOrder,
        uiState.hasMoreNextPages,
        uiState.isAppendingNextPage,
        uiState.isPageSwitching,
        uiState.isInitialLoading,
    ) {
        snapshotFlow { shouldAppendNextPage }
            .distinctUntilChanged()
            .collect { nearEnd ->
                if (nearEnd &&
                    !uiState.reverseOrder &&
                    uiState.hasMoreNextPages &&
                    !uiState.isAppendingNextPage &&
                    !uiState.isPageSwitching &&
                    !uiState.isInitialLoading
                ) {
                    viewModel.appendNextPage()
                }
            }
    }

    // Execute programmatic floor jump / resume scroll once page items are laid out
    LaunchedEffect(
        uiState.pendingScrollFloor,
        uiState.loadedStartPage,
        uiState.loadedEndPage,
        uiState.displayedReplies.size,
    ) {
        val targetFloor = uiState.pendingScrollFloor ?: return@LaunchedEffect
        val pageData = uiState.pageData ?: return@LaunchedEffect

        if (targetFloor <= 0) {
            lazyListState.animateScrollToItem(0)
            viewModel.consumePendingScrollFloor()
        } else {
            val replyIndex = uiState.displayedReplies.indexOfFirst { it.floorNumber == targetFloor }
            if (replyIndex >= 0) {
                var prefixCount = 1 // article_header
                if (pageData.isOfflineSnapshot) prefixCount += 1
                if (uiState.openingFloor != null || uiState.loadedStartPage > 1) prefixCount += 1
                prefixCount += 1 // replies_section_bar
                if (uiState.hasPreviousUnloadedPages) prefixCount += 1 // earlier_pages_bar
                val targetListIndex = prefixCount + replyIndex
                lazyListState.scrollToItem(targetListIndex)
                viewModel.consumePendingScrollFloor()
            } else if (!uiState.isPageSwitching && !uiState.isInitialLoading) {
                // Floor not present (e.g. deleted floor on target page): scroll to start of replies
                lazyListState.scrollToItem(0)
                viewModel.consumePendingScrollFloor()
            }
        }
    }

    val hasScrolledPastHeader by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 140
        }
    }

    val handleMarkdownUrlClick: (String) -> Unit = remember(
        topicId,
        onNavigateToUser,
        onNavigateToCategory,
        onOpenTopic,
        uriHandler,
    ) {
        { url ->
            openNodeSeekOrExternalUrl(
                rawUrl = url,
                uriHandler = uriHandler,
                currentTopicId = topicId,
                onJumpFloorInCurrentTopic = viewModel::jumpToFloor,
                onOpenTopic = onOpenTopic,
                onOpenUser = onNavigateToUser,
                onOpenCategory = onNavigateToCategory,
                onOpenLuckyDraw = { deepLink ->
                    activeLuckyDrawSpec = deepLink.toSpec()
                    activeLuckyDrawMode = LuckyDrawSheetMode.VERIFY
                },
            )
        }
    }

    val handleMarkdownUrlLongClick: (String) -> Unit = remember {
        { rawUrl ->
            val unwrapped = MerchantLinkDetector.resolveOrderUrl(rawUrl).ifBlank { rawUrl.trim() }
            if (unwrapped.startsWith("http://", ignoreCase = true) ||
                unwrapped.startsWith("https://", ignoreCase = true)
            ) {
                lastLinkLongPressMs = System.currentTimeMillis()
                linkActionUrl = unwrapped
            }
        }
    }

    val shareTopic: () -> Unit = {
        val shareTitle = uiState.pageData?.title?.ifBlank { titleHint } ?: titleHint
        val shareUrl = "https://www.nodeseek.com/post-$topicId-${uiState.currentPage}"
        val shareText = if (shareTitle.isNotBlank()) "$shareTitle\n$shareUrl" else shareUrl
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, shareTitle)
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        context.startActivity(Intent.createChooser(sendIntent, "分享帖子"))
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopicReaderTopBar(
                title = uiState.pageData?.title?.ifBlank { uiState.titleHint } ?: uiState.titleHint,
                author = uiState.pageData?.author,
                categorySlug = uiState.pageData?.categorySlug,
                categoryName = uiState.pageData?.categoryName,
                showCompactIdentity = hasScrolledPastHeader || uiState.pageData == null,
                opOnly = uiState.opOnly,
                reverseOrder = uiState.reverseOrder,
                isBookmarked = uiState.isBookmarked,
                canEditTopic = uiState.canEditTopic,
                isPageSwitching = uiState.isPageSwitching,
                isTrackingReplies = uiState.isTrackingReplies,
                currentPage = uiState.currentPage,
                totalPages = uiState.totalPages,
                isEmbeddedInDualPane = isEmbeddedInDualPane,
                isDetailPaneMaximized = isDetailPaneMaximized,
                onBack = onBack,
                onCloseDetailPane = onCloseDetailPane,
                onToggleMaximizePane = onToggleMaximizePane,
                onAuthorClick = {
                    uiState.pageData?.author?.let { onNavigateToUser(it.uid, it.username) }
                },
                onToggleOpOnly = viewModel::toggleOpOnly,
                onToggleReverseOrder = viewModel::toggleReverseOrder,
                onToggleReplyTracking = viewModel::toggleReplyTracking,
                onToggleBookmark = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.toggleBookmark()
                },
                onOpenFloorJumpSheet = { viewModel.setFloorJumpSheetVisible(true) },
                onRefresh = viewModel::refreshCurrentPage,
                onEditTopic = {
                    uiState.pageData?.let { onEditTopic(topicId, it.categorySlug) }
                },
                onOpenTradeClosure = { showTradeClosureSheet = true },
                onOpenCarpoolStatus = { showCarpoolStatusSheet = true },
                onOpenLuckyGenerator = {
                    val existingSpec = uiState.openingFloor?.markdownBody?.let { body ->
                        DrandLuckyEngine.extractFirstLuckySpec(body, fallbackPostId = topicId)
                    }
                    activeLuckyDrawSpec = existingSpec ?: LuckyDrawSpec(
                        postId = topicId,
                        drawTimeEpochMillis = System.currentTimeMillis() + 3_600_000L,
                    )
                    activeLuckyDrawMode = LuckyDrawSheetMode.GENERATOR
                },
                onCopyLink = {
                    val link = "https://www.nodeseek.com/post-$topicId-${uiState.currentPage}"
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Anode Topic Link", link))
                    coroutineScope.launch { snackbarHostState.showSnackbar("帖子链接已复制") }
                },
                onOpenInWebView = {
                    triggerWebViewAuth("https://www.nodeseek.com/post-$topicId-${uiState.currentPage}#manual")
                },
                onOpenBrowser = {
                    runCatching {
                        uriHandler.openUri("https://www.nodeseek.com/post-$topicId-${uiState.currentPage}")
                    }
                },
                onShare = shareTopic,
                onShareLongImage = {
                    coroutineScope.launch {
                        val op = viewModel.resolveOpeningFloorForShare()
                        if (op != null) {
                            longImageShareTarget = op
                        } else {
                            snackbarHostState.showSnackbar("主楼内容仍在加载，请稍后重试")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (uiState.pageData != null) {
                AnchoredQuickReplyBar(
                    isLocked = uiState.pageData!!.isLocked,
                    hasUnsentDraft = uiState.replyComposer.hasUnsentDraft,
                    draftPreview = uiState.replyComposer.draftMarkdown,
                    isBookmarked = uiState.isBookmarked,
                    visibleFloorFlow = viewModel.visibleFloor,
                    estimatedTotalFloors = uiState.estimatedTotalFloors,
                    categorySlug = uiState.pageData?.categorySlug,
                    onOpenReplyComposer = { viewModel.openReplySheet(quoteComment = null) },
                    onToggleBookmark = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.toggleBookmark()
                    },
                    onShare = shareTopic,
                    onOpenFloorJumpSheet = { viewModel.setFloorJumpSheetVisible(true) },
                    isEmbeddedInDualPane = isEmbeddedInDualPane,
                )
            }
        },
        snackbarHost = {
            PulseSnackbarHost(
                hostState = snackbarHostState,
                modifier = if (uiState.pageData == null) {
                    if (isEmbeddedInDualPane) {
                        Modifier.windowInsetsPadding(
                            WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.End),
                        )
                    } else {
                        Modifier.navigationBarsPadding()
                    }
                } else {
                    Modifier
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                uiState.isInitialLoading && uiState.pageData == null -> {
                    ReaderLoadingSkeleton(titleHint = uiState.titleHint)
                }

                uiState.accessError != null && uiState.pageData == null -> {
                    TopicAccessRestrictionCard(
                        error = uiState.accessError!!,
                        topicId = topicId,
                        session = uiState.session,
                        onRequestWebViewAuth = triggerWebViewAuth,
                        onRetry = viewModel::retryLoad,
                        onBack = if (isEmbeddedInDualPane) (onCloseDetailPane ?: onBack) else onBack,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }

                uiState.pageData != null -> {
                    val pageData = uiState.pageData!!
                    PullToRefreshBox(
                        isRefreshing = uiState.isRefreshing,
                        onRefresh = viewModel::refreshCurrentPage,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 24.dp),
                        ) {
                            // 0. Offline Bookmark Snapshot Banner (when offline)
                            if (pageData.isOfflineSnapshot) {
                                item(key = "offline_banner") {
                                    OfflineSnapshotBanner(onRefresh = viewModel::refreshCurrentPage)
                                }
                            }

                            // 1. Magazine Article Header
                            item(key = "article_header") {
                                val headerAuthor = uiState.openingFloor?.author ?: pageData.author
                                MagazineArticleHeader(
                                    pageData = pageData,
                                    openingFloor = uiState.openingFloor,
                                    effectiveBookmarkCount = uiState.effectiveBookmarkCount,
                                    canEditTopic = uiState.canEditTopic,
                                    isTrackingReplies = uiState.isTrackingReplies,
                                    trackedBaselineFloor = uiState.trackedBaselineFloor,
                                    totalPages = uiState.totalPages,
                                    showTrustQuickBadge = true,
                                    onCategoryClick = { onNavigateToCategory(pageData.categorySlug) },
                                    onAuthorClick = {
                                        onNavigateToUser(headerAuthor.uid, headerAuthor.username)
                                    },
                                    onAvatarClick = {
                                        openSellerTrust(
                                            headerAuthor.uid,
                                            headerAuthor.username,
                                            headerAuthor.avatarUrl,
                                        )
                                    },
                                    onInspectTrust = {
                                        openSellerTrust(
                                            headerAuthor.uid,
                                            headerAuthor.username,
                                            headerAuthor.avatarUrl,
                                        )
                                    },
                                    onEditTopic = { onEditTopic(topicId, pageData.categorySlug) },
                                    onJumpToFloor = viewModel::jumpToFloor,
                                    onJumpToLastPage = { viewModel.selectPage(uiState.totalPages) },
                                    onToggleReplyTracking = viewModel::toggleReplyTracking,
                                    marketReference = uiState.marketReference,
                                    marketCooldownRemainingSeconds = marketRefCooldownSec,
                                    isMutatingTopic = uiState.isMutatingTopic,
                                    onSearchMarketRemote = { customQuery ->
                                        viewModel.searchTradeMarketReference(customQuery)
                                    },
                                    onOpenMarketValuation = { showMarketValuationSheet = true },
                                    onOpenMarketTopic = { targetId, targetTitle ->
                                        onOpenTopic(targetId, 0, targetTitle)
                                    },
                                    onQuickCarpoolStatus = { tag ->
                                        val parsed = CarpoolStatusUpdater.parseStatusToken(tag)
                                        viewModel.updateCarpoolStatus(
                                            statusToken = tag,
                                            occupiedSeats = parsed?.occupiedSeats,
                                            totalSeats = if (parsed?.occupiedSeats != null && parsed.remainingSeats != null) {
                                                parsed.occupiedSeats + parsed.remainingSeats
                                            } else {
                                                null
                                            },
                                        )
                                    },
                                    onOpenCarpoolStatusSheet = { showCarpoolStatusSheet = true },
                                )
                            }

                            // 2. Floor 0 Body (Expanded when Page 1 loaded; Collapsible Strip when started on Page > 1)
                            if (uiState.openingFloor != null || uiState.loadedStartPage > 1) {
                                item(key = "floor_0") {
                                    if (uiState.loadedStartPage == 1 || uiState.isOpExpandedOnSubPage) {
                                        val opening = uiState.openingFloor
                                        if (opening != null) {
                                            val detectedLuckySpec = remember(opening.markdownBody, topicId) {
                                                DrandLuckyEngine.extractFirstLuckySpec(
                                                    markdown = opening.markdownBody,
                                                    fallbackPostId = topicId,
                                                )
                                            }
                                            OpeningFloorArticleBody(
                                                openingFloor = opening,
                                                isSubPageExpanded = uiState.loadedStartPage > 1,
                                                isBusy = uiState.busyCommentIds.contains(opening.commentId),
                                                canEditTopic = uiState.canEditTopic,
                                                nsAppWidgetController = viewModel.nsAppWidgetController,
                                                detectedLuckySpec = detectedLuckySpec,
                                                onOpenLuckySheet = { spec ->
                                                    activeLuckyDrawSpec = spec
                                                    activeLuckyDrawMode = LuckyDrawSheetMode.VERIFY
                                                },
                                                onCollapseSubPageOp = viewModel::toggleOpExpandedOnSubPage,
                                                onMentionClick = viewModel::resolveAndNavigateToUser,
                                                onFloorClick = viewModel::jumpToFloor,
                                                onUrlClick = handleMarkdownUrlClick,
                                                onUrlLongClick = handleMarkdownUrlLongClick,
                                                onUpvote = { viewModel.onUpvoteClicked(opening) },
                                                onDrumstick = { viewModel.onDrumstickClicked(opening) },
                                                onDislike = { viewModel.onDislikeClicked(opening) },
                                                onReplyToOp = { viewModel.openReplySheet(quoteComment = null) },
                                                onEditTopic = { onEditTopic(topicId, pageData.categorySlug) },
                                                onOpenMoreActions = { openFloorMoreActions(opening) },
                                            )
                                        }
                                    } else {
                                        CollapsedSubPageOpBar(
                                            currentPage = uiState.loadedStartPage,
                                            hasCachedOp = uiState.openingFloor != null,
                                            onExpandOp = viewModel::toggleOpExpandedOnSubPage,
                                            onGoFirstPage = { viewModel.selectPage(1) },
                                        )
                                    }
                                }
                            }

                            // 3. Threaded Replies Section Header
                            item(key = "replies_section_bar") {
                                RepliesStreamHeaderBar(
                                    estimatedTotalFloors = uiState.estimatedTotalFloors,
                                    currentPage = uiState.currentPage,
                                    loadedStartPage = uiState.loadedStartPage,
                                    loadedEndPage = uiState.loadedEndPage,
                                    totalPages = uiState.totalPages,
                                    opOnly = uiState.opOnly,
                                    reverseOrder = uiState.reverseOrder,
                                    onToggleOpOnly = viewModel::toggleOpOnly,
                                    onToggleReverseOrder = viewModel::toggleReverseOrder,
                                    onOpenPageSheet = { viewModel.setFloorJumpSheetVisible(true) },
                                )
                            }

                            if (uiState.hasPreviousUnloadedPages) {
                                item(key = "earlier_pages_bar") {
                                    EarlierPagesLoadBar(
                                        targetPage = uiState.loadedStartPage - 1,
                                        isLoading = uiState.isPrependingPreviousPage,
                                        onLoadEarlier = viewModel::prependPreviousPage,
                                    )
                                }
                            }

                            // 4. Threaded Floor Reply Stream
                            if (uiState.displayedReplies.isEmpty()) {
                                item(key = "empty_replies") {
                                    EmptyRepliesNotice(
                                        opOnly = uiState.opOnly,
                                        hasMorePages = uiState.totalPages > 1,
                                        onDisableOpOnly = viewModel::toggleOpOnly,
                                        onNextPage = viewModel::appendNextPage,
                                        hasNextPage = uiState.hasMoreNextPages,
                                    )
                                }
                            } else {
                                items(
                                    items = uiState.displayedReplies,
                                    key = { reply -> "floor_${reply.floorNumber}_${reply.commentId}" },
                                    contentType = { "reply_floor" },
                                ) { reply ->
                                    val isOpAuthor = reply.author.isTopicAuthor ||
                                        reply.author.uid == pageData.author.uid
                                    val isOwnReply = uiState.session?.uid == reply.author.uid
                                    ThreadedFloorReplyRow(
                                        reply = reply,
                                        isOpAuthor = isOpAuthor,
                                        isOwnReply = isOwnReply,
                                        isViewerTopicAuthor = uiState.isViewerTopicAuthor,
                                        isHighlighted = uiState.highlightedFloor == reply.floorNumber,
                                        isBusy = uiState.busyCommentIds.contains(reply.commentId),
                                        showTrustQuickBadge = true,
                                        nsAppWidgetController = viewModel.nsAppWidgetController,
                                        onAuthorClick = {
                                            onNavigateToUser(reply.author.uid, reply.author.username)
                                        },
                                        onAvatarClick = {
                                            openSellerTrust(
                                                reply.author.uid,
                                                reply.author.username,
                                                reply.author.avatarUrl,
                                            )
                                        },
                                        onMentionClick = viewModel::resolveAndNavigateToUser,
                                        onFloorClick = viewModel::jumpToFloor,
                                        onUrlClick = handleMarkdownUrlClick,
                                        onUrlLongClick = handleMarkdownUrlLongClick,
                                        onUpvote = { viewModel.onUpvoteClicked(reply) },
                                        onDrumstick = { viewModel.onDrumstickClicked(reply) },
                                        onDislike = { viewModel.onDislikeClicked(reply) },
                                        onQuoteReply = { viewModel.openReplySheet(quoteComment = reply) },
                                        onEditReply = { viewModel.openEditReplySheet(reply) },
                                        onTogglePin = { viewModel.onTogglePinReplyClicked(reply) },
                                        onOpenMoreActions = { openFloorMoreActions(reply) },
                                    )
                                }
                            }

                            // 5. Continuous Stream Footer
                            if (uiState.displayedReplies.isNotEmpty() || uiState.isAppendingNextPage || uiState.hasMoreNextPages) {
                                item(key = "stream_footer") {
                                    RepliesStreamFooter(
                                        isLoading = uiState.isAppendingNextPage,
                                        hasMorePages = uiState.hasMoreNextPages,
                                        nextPage = uiState.loadedEndPage + 1,
                                        loadedCount = uiState.displayedReplies.size,
                                        onLoadNextPage = viewModel::appendNextPage,
                                    )
                                }
                            }
                        }

                        // Right-Edge Draggable Floor Scrubber
                        if (uiState.estimatedTotalFloors > 0) {
                            RightEdgeFloorScrubber(
                                lazyListState = lazyListState,
                                visibleFloorFlow = viewModel.visibleFloor,
                                totalFloors = uiState.estimatedTotalFloors,
                                onOpenFloorJumpSheet = { viewModel.setFloorJumpSheetVisible(true) },
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Sheets & Confirmation Dialogs ─────────────────────────────────────────

    actionSheetComment?.let { targetComment ->
        val isTargetOp = targetComment.floorNumber <= 0
        val isOwnComment = uiState.session?.uid == targetComment.author.uid
        val canEditTarget = if (isTargetOp) uiState.canEditTopic else isOwnComment
        val detectedMerchantLinks = remember(targetComment.markdownBody) {
            MerchantLinkDetector.extractMerchantLinks(targetComment.markdownBody)
        }
        FloorActionBottomSheet(
            comment = targetComment,
            isViewerTopicAuthor = uiState.isViewerTopicAuthor,
            canEdit = canEditTarget,
            canTipStardust = targetComment.author.uid > 0L && targetComment.author.uid != uiState.session?.uid,
            firstMerchantLinkUrl = detectedMerchantLinks.firstOrNull()?.resolvedUrl,
            merchantLinkCount = detectedMerchantLinks.size,
            onDismiss = { actionSheetComment = null },
            onTipStardust = {
                actionSheetComment = null
                viewModel.openStardustTipForComment(targetComment)
            },
            onInspectSellerTrust = {
                actionSheetComment = null
                openSellerTrust(
                    targetComment.author.uid,
                    targetComment.author.username,
                    targetComment.author.avatarUrl,
                )
            },
            onAddMerchantLinkToRestock = { merchantUrl ->
                actionSheetComment = null
                restockWatchPrefillUrl = merchantUrl
            },
            onReplyOrQuote = {
                actionSheetComment = null
                viewModel.openReplySheet(quoteComment = if (isTargetOp) null else targetComment)
            },
            onDrumstick = {
                actionSheetComment = null
                viewModel.onDrumstickClicked(targetComment)
            },
            onFreeTextSelect = {
                actionSheetComment = null
                selectableSheetComment = targetComment
            },
            onCopyMarkdown = {
                actionSheetComment = null
                copyMarkdownSource(targetComment)
            },
            onShareLongImage = {
                actionSheetComment = null
                longImageShareTarget = targetComment
            },
            onEdit = {
                actionSheetComment = null
                val slug = uiState.pageData?.categorySlug
                if (isTargetOp && slug != null) {
                    onEditTopic(topicId, slug)
                } else if (!isTargetOp) {
                    viewModel.openEditReplySheet(targetComment)
                }
            },
            onTogglePin = {
                actionSheetComment = null
                viewModel.onTogglePinReplyClicked(targetComment)
            },
        )
    }

    selectableSheetComment?.let { comment ->
        SelectableMarkdownBottomSheet(
            comment = comment,
            nsAppWidgetController = viewModel.nsAppWidgetController,
            onDismiss = { selectableSheetComment = null },
            onCopyAllMarkdown = {
                copyMarkdownSource(comment)
            },
            onMentionClick = { username ->
                selectableSheetComment = null
                viewModel.resolveAndNavigateToUser(username)
            },
            onFloorClick = { floor ->
                selectableSheetComment = null
                viewModel.jumpToFloor(floor)
            },
            onUrlClick = handleMarkdownUrlClick,
        )
    }

    if (uiState.showFloorJumpSheet) {
        val visibleFloor by viewModel.visibleFloor.collectAsStateWithLifecycle()
        FloorJumpBottomSheet(
            currentFloor = visibleFloor,
            estimatedTotalFloors = uiState.estimatedTotalFloors,
            currentPage = uiState.currentPage,
            totalPages = uiState.totalPages,
            opOnly = uiState.opOnly,
            reverseOrder = uiState.reverseOrder,
            onDismiss = { viewModel.setFloorJumpSheetVisible(false) },
            onJumpToFloor = viewModel::jumpToFloor,
            onSelectPage = viewModel::selectPage,
            onToggleOpOnly = viewModel::toggleOpOnly,
            onToggleReverseOrder = viewModel::toggleReverseOrder,
        )
    }

    if (uiState.replyComposer.isSheetOpen) {
        val replyValuationLabel = remember(
            uiState.marketReference?.query,
            uiState.pageData?.title,
            uiState.titleHint,
            uiState.openingFloor?.markdownBody,
        ) {
            uiState.marketReference?.query?.takeIf { it.isNotBlank() }
                ?: extractMarketQueryWithContext(
                    title = uiState.pageData?.title?.ifBlank { uiState.titleHint } ?: uiState.titleHint,
                    openingMarkdown = uiState.openingFloor?.markdownBody,
                )
        }
        ReplyComposerBottomSheet(
            state = uiState.replyComposer,
            insertedUploadSnippets = remember(uploadedMarkdownChannel) {
                uploadedMarkdownChannel.receiveAsFlow()
            },
            onDismiss = viewModel::closeReplySheet,
            onClearQuote = viewModel::clearQuotedContext,
            onSelectTab = viewModel::setReplyEditorTab,
            onDraftChanged = viewModel::onReplyDraftChanged,
            onToggleStickerPicker = viewModel::toggleReplyStickerPicker,
            onPhotoBytesPicked = { bytes, mimeType, fileName ->
                viewModel.onPhotoSelectedForReply(bytes, mimeType, fileName)
            },
            onPhotoReadError = viewModel::onReplyPhotoReadError,
            onSubmit = viewModel::submitReply,
            onMentionClickInPreview = viewModel::resolveAndNavigateToUser,
            onFloorClickInPreview = viewModel::jumpToFloor,
            onUrlClickInPreview = handleMarkdownUrlClick,
            onOpenFullNetBench = onOpenFullNetBench,
            onOpenFullLiteSsh = onOpenFullLiteSsh,
            onPublishSshToComposer = onPublishSshToComposer,
            initialValuationMachineLabel = replyValuationLabel,
        )
    }

    linkActionUrl?.let { targetUrl ->
        ExternalLinkActionBottomSheet(
            url = targetUrl,
            onDismiss = { linkActionUrl = null },
            onAddToRestockMonitor = {
                linkActionUrl = null
                restockWatchPrefillUrl = targetUrl
            },
            onCopyLink = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("Anode Link", targetUrl))
                linkActionUrl = null
                coroutineScope.launch { snackbarHostState.showSnackbar("链接已复制") }
            },
            onOpenInBrowser = {
                linkActionUrl = null
                val normalized = normalizeNodeSeekUrl(targetUrl)
                if (isSafeExternalHttpUrl(normalized)) {
                    runCatching { uriHandler.openUri(normalized) }.onFailure {
                        handleMarkdownUrlClick(targetUrl)
                    }
                }
            },
        )
    }

    restockWatchPrefillUrl?.let { prefillUrl ->
        val topicTitle = uiState.pageData?.title?.ifBlank { titleHint } ?: titleHint
        AddRestockWatchBottomSheet(
            repository = appContainer.restockRepository,
            controller = appContainer.restockMonitorController,
            onDismiss = { restockWatchPrefillUrl = null },
            initialUrl = prefillUrl,
            initialTitle = topicTitle,
            sourceTopicId = topicId,
            onWatchSaved = { saved ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("已加入补货监控：${saved.title}")
                }
            },
        )
    }

    trustSheetTarget?.let { target ->
        SellerTrustCardSheet(
            uid = target.uid,
            usernameHint = target.username,
            avatarUrlHint = target.avatarUrl,
            onOpenTopic = { targetTopicId ->
                trustSheetTarget = null
                onOpenTopic(targetTopicId, 0, "")
            },
            onOpenUserProfile = { targetUid, targetUsername ->
                trustSheetTarget = null
                onNavigateToUser(targetUid, targetUsername)
            },
            onStartDm = onStartDirectMessage?.let { startDm ->
                { targetUid, targetUsername ->
                    trustSheetTarget = null
                    startDm(targetUid, targetUsername)
                }
            },
            onDismiss = { trustSheetTarget = null },
            repository = appContainer.sellerTrustRepository,
        )
    }

    if (uiState.replyComposer.showApiKeyDialog) {
        NodeImageApiKeySetupDialog(
            onSaveAndContinue = viewModel::saveNodeImageApiKeyAndContinue,
            onDismiss = viewModel::dismissApiKeyDialog,
        )
    }

    uiState.replyComposer.pendingPrivacyImage?.let { pending ->
        ImagePrivacyEditorSheet(
            sourceBytes = pending.bytes,
            mimeType = pending.mimeType,
            fileName = pending.fileName,
            onConfirmUpload = viewModel::confirmReplyPrivacyImageUpload,
            onSkipMosaicUpload = viewModel::skipReplyPrivacyEditAndUpload,
            onDismiss = viewModel::dismissReplyPrivacyEditor,
        )
    }

    longImageShareTarget?.let { targetFloor ->
        val pageData = uiState.pageData
        val shareTitle = pageData?.title?.ifBlank { uiState.titleHint } ?: uiState.titleHint
        val shareUrl = "https://www.nodeseek.com/post-$topicId-1"
        TopicLongImageShareSheet(
            topicId = topicId,
            title = shareTitle,
            categorySlug = pageData?.categorySlug.orEmpty(),
            categoryName = pageData?.categoryName.orEmpty(),
            targetFloor = targetFloor,
            topicUrl = shareUrl,
            viewCount = pageData?.viewCount ?: 0,
            bookmarkCount = uiState.effectiveBookmarkCount,
            coilOkHttpClient = viewModel.imageFetchClient,
            onFeedbackMessage = { msg ->
                coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
            },
            onDismiss = { longImageShareTarget = null },
        )
    }

    uiState.pendingPaidAction?.let { pendingAction ->
        PaidActionConfirmationDialog(
            action = pendingAction,
            viewerDrumsticks = uiState.session?.drumsticks,
            onConfirm = viewModel::confirmPendingPaidAction,
            onDismiss = viewModel::dismissPendingPaidAction,
        )
    }

    uiState.stardustTipDialog?.let { tipDialogState ->
        StardustTipDialog(
            state = tipDialogState,
            viewerUid = uiState.session?.uid,
            viewerBalance = uiState.session?.stardust,
            onAmountChanged = viewModel::updateStardustTipAmount,
            onRetryPrepare = viewModel::retryStardustPrepare,
            onConfirmSend = viewModel::submitStardustTip,
            onDismiss = viewModel::dismissStardustTipDialog,
        )
    }

    if (activeLuckyDrawMode != null && activeLuckyDrawSpec != null) {
        LuckyDrawSheet(
            spec = activeLuckyDrawSpec,
            initialMode = activeLuckyDrawMode ?: LuckyDrawSheetMode.VERIFY,
            allowGeneratorMode = uiState.canEditTopic,
            isViewerTopicAuthor = uiState.isViewerTopicAuthor,
            viewerUid = uiState.session?.uid,
            luckyDrawRepository = appContainer.luckyDrawRepository,
            forumRepository = appContainer.forumRepository,
            onDismiss = {
                activeLuckyDrawMode = null
                activeLuckyDrawSpec = null
            },
            onFeedbackMessage = { msg ->
                coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
            },
            onOpenTopicFloor = { targetPostId, targetFloor ->
                activeLuckyDrawMode = null
                activeLuckyDrawSpec = null
                if (targetPostId == topicId) {
                    viewModel.jumpToFloor(targetFloor)
                } else {
                    onOpenTopic(targetPostId, targetFloor, "")
                }
            },
            onOpenUser = { uid, username ->
                activeLuckyDrawMode = null
                activeLuckyDrawSpec = null
                onNavigateToUser(uid, username)
            },
            onWinnerReplyPosted = {
                viewModel.refreshCurrentPage()
            },
            onAppendLuckyToTopicOp = if (uiState.canEditTopic) {
                { luckyMarkdown, _ ->
                    activeLuckyDrawMode = null
                    activeLuckyDrawSpec = null
                    viewModel.appendLuckyLinkToTopicOp(luckyMarkdown)
                }
            } else {
                null
            },
        )
    }

    fallbackWebViewUrl?.let { authUrl ->
        val manualBrowse = authUrl.endsWith("#manual")
        val cleanUrl = authUrl.removeSuffix("#manual")
        WebViewSessionSheet(
            initialUrl = cleanUrl,
            requireSignedInUser = cleanUrl.contains("signIn", ignoreCase = true),
            autoDismissWhenVerified = !manualBrowse,
            onDismiss = {
                fallbackWebViewUrl = null
                viewModel.onWebViewDismissed()
            },
            onSessionVerified = {
                fallbackWebViewUrl = null
                viewModel.onWebViewAuthCompleted()
            },
        )
    }

    if (showTradeClosureSheet) {
        val currentTitle = uiState.pageData?.title?.ifBlank { uiState.titleHint } ?: uiState.titleHint
        TradeClosureSheet(
            topicId = topicId,
            topicTitle = currentTitle,
            openingMarkdown = uiState.openingFloor?.markdownBody,
            isMutating = uiState.isMutatingTopic,
            onConfirmClosure = { options ->
                showTradeClosureSheet = false
                viewModel.closeTradeTopic(options)
            },
            onCopyFeedback = { msg ->
                coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
            },
            onDismiss = { showTradeClosureSheet = false },
        )
    }

    if (showCarpoolStatusSheet) {
        val currentTitle = uiState.pageData?.title?.ifBlank { uiState.titleHint } ?: uiState.titleHint
        CarpoolStatusSheet(
            topicTitle = currentTitle,
            isMutating = uiState.isMutatingTopic,
            onUpdateStatus = { token, occupied, total, updateBody ->
                showCarpoolStatusSheet = false
                viewModel.updateCarpoolStatus(
                    statusToken = token,
                    occupiedSeats = occupied,
                    totalSeats = total,
                    updateBody = updateBody,
                )
            },
            onDismiss = { showCarpoolStatusSheet = false },
        )
    }

    if (showMarketValuationSheet) {
        val valuationMachineLabel = remember(
            uiState.marketReference?.query,
            uiState.pageData?.title,
            uiState.titleHint,
            uiState.openingFloor?.markdownBody,
        ) {
            uiState.marketReference?.query?.takeIf { it.isNotBlank() }
                ?: extractMarketQueryWithContext(
                    title = uiState.pageData?.title?.ifBlank { uiState.titleHint } ?: uiState.titleHint,
                    openingMarkdown = uiState.openingFloor?.markdownBody,
                )
        }
        VpsValuationBottomSheet(
            primaryActionLabel = "复制估值报价",
            onInsertMarkdown = { generatedMarkdown ->
                showMarketValuationSheet = false
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("Anode Valuation", generatedMarkdown))
                coroutineScope.launch { snackbarHostState.showSnackbar("已复制估值报价") }
            },
            onDismiss = { showMarketValuationSheet = false },
            initialMachineLabel = valuationMachineLabel,
            initialMode = ValuationMode.TRANSFER_DEAL,
            onCopiedFeedback = { msg ->
                coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
            },
            marketReferenceRepository = appContainer.tradeMarketReferenceRepository,
        )
    }
}

// ── Top Bar ───────────────────────────────────────────────────────────────────

@Composable
private fun TopicReaderTopBar(
    title: String,
    author: com.nodeseek.app.domain.model.FloorAuthor?,
    categorySlug: String?,
    categoryName: String? = null,
    showCompactIdentity: Boolean,
    opOnly: Boolean,
    reverseOrder: Boolean,
    isBookmarked: Boolean,
    canEditTopic: Boolean,
    isPageSwitching: Boolean,
    isTrackingReplies: Boolean,
    currentPage: Int,
    totalPages: Int,
    isEmbeddedInDualPane: Boolean,
    isDetailPaneMaximized: Boolean,
    onBack: () -> Unit,
    onCloseDetailPane: (() -> Unit)?,
    onToggleMaximizePane: (() -> Unit)?,
    onAuthorClick: () -> Unit,
    onToggleOpOnly: () -> Unit,
    onToggleReverseOrder: () -> Unit,
    onToggleReplyTracking: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenFloorJumpSheet: () -> Unit,
    onRefresh: () -> Unit,
    onEditTopic: () -> Unit,
    onOpenTradeClosure: () -> Unit = {},
    onOpenCarpoolStatus: () -> Unit = {},
    onOpenLuckyGenerator: () -> Unit,
    onCopyLink: () -> Unit,
    onOpenInWebView: () -> Unit,
    onOpenBrowser: () -> Unit,
    onShare: () -> Unit,
    onShareLongImage: () -> Unit = {},
) {
    val ext = NodePulseThemeTokens.extendedColors
    val categoryAccent = categorySlug?.takeIf { it.isNotBlank() }?.let {
        NodePulseThemeTokens.categorySpec(it).spineColor
    }
    val isTradeTopic = categorySlug.equals("trade", ignoreCase = true) || categoryName?.trim() == "交易"
    val isCarpoolTopic = categorySlug.equals("carpool", ignoreCase = true) || categoryName?.trim() == "拼车"
    val isAlreadySold = remember(title) { TradeTitleFormatter.isMarkedSold(title) }
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseGlassSurface(
                showTopBorder = false,
                showBottomBorder = !isPageSwitching,
                tonalAccent = categoryAccent,
            )
            .then(
                if (isEmbeddedInDualPane) {
                    Modifier.windowInsetsPadding(
                        WindowInsets.statusBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Top + WindowInsetsSides.End),
                    )
                } else {
                    Modifier.statusBarsPadding()
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isEmbeddedInDualPane) {
                IconButton(
                    onClick = onCloseDetailPane ?: onBack,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭帖子详情",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showCompactIdentity && title.isNotBlank(),
                    enter = fadeIn() + slideInVertically { it / 3 },
                    exit = fadeOut() + slideOutVertically { it / 3 },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (author != null) {
                            PulseAvatar(
                                avatarUrl = author.avatarUrl,
                                username = author.username,
                                size = 28.dp,
                                rankLevel = author.level,
                                ringWidth = 1.6.dp,
                                onClick = onAuthorClick,
                            )
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Prominent OP-Only (`只看楼主`) Toggle Chip in Top Bar
            val activeBrush = Brush.horizontalGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.primary,
                    blendColor(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, 0.42f),
                ),
            )
            val opChipBgColor by animateColorAsState(
                targetValue = if (opOnly) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)
                },
                label = "opOnlyChipBg",
            )
            val opChipFg by animateColorAsState(
                targetValue = if (opOnly) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                label = "opOnlyChipFg",
            )
            val opChipBorder by animateColorAsState(
                targetValue = if (opOnly) {
                    MaterialTheme.colorScheme.primary
                } else {
                    ext.cardBorder.copy(alpha = 0.75f)
                },
                label = "opOnlyChipBorder",
            )

            Row(
                modifier = Modifier
                    .height(32.dp)
                    .clip(CircleShape)
                    .then(
                        if (opOnly) {
                            Modifier.background(activeBrush)
                        } else {
                            Modifier.background(opChipBgColor)
                        }
                    )
                    .border(0.8.dp, opChipBorder, CircleShape)
                    .pulseBouncyClickable(
                        pressedScale = NodePulseMotion.chipPressScale,
                        role = Role.Switch,
                        onClick = onToggleOpOnly,
                    )
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    tint = opChipFg,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "只看楼主",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (opOnly) FontWeight.Bold else FontWeight.Medium,
                    color = opChipFg,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (canEditTopic && isTradeTopic) {
                val tradeAccent = NodePulseThemeTokens.categorySpec("trade").spineColor
                Row(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (isAlreadySold) tradeAccent else tradeAccent.copy(alpha = 0.14f),
                        )
                        .border(0.8.dp, tradeAccent.copy(alpha = 0.55f), CircleShape)
                        .pulseBouncyClickable(
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.Button,
                            onClick = onOpenTradeClosure,
                        )
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (isAlreadySold) Icons.Rounded.TaskAlt else Icons.Rounded.CheckCircleOutline,
                        contentDescription = null,
                        tint = if (isAlreadySold) Color.White else tradeAccent,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = if (isAlreadySold) "已出" else "标已出",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isAlreadySold) Color.White else tradeAccent,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else if (canEditTopic && isCarpoolTopic) {
                val carpoolAccent = NodePulseThemeTokens.categorySpec("carpool").spineColor
                Row(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(CircleShape)
                        .background(carpoolAccent.copy(alpha = 0.14f))
                        .border(0.8.dp, carpoolAccent.copy(alpha = 0.55f), CircleShape)
                        .pulseBouncyClickable(
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.Button,
                            onClick = onOpenCarpoolStatus,
                        )
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Groups,
                        contentDescription = null,
                        tint = carpoolAccent,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "车位",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = carpoolAccent,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (isEmbeddedInDualPane && onToggleMaximizePane != null) {
                IconButton(
                    onClick = onToggleMaximizePane,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (isDetailPaneMaximized) {
                            Icons.Rounded.CloseFullscreen
                        } else {
                            Icons.Rounded.OpenInFull
                        },
                        contentDescription = if (isDetailPaneMaximized) "退出全屏展开" else "全屏展开阅读",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = if (isEmbeddedInDualPane) Modifier.size(40.dp) else Modifier,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "更多选项",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                PulseDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    minWidth = 238.dp,
                    maxWidth = 296.dp,
                ) {
                    PulseDropdownMenuSectionHeader(
                        title = "阅读与导航",
                        accentColor = MaterialTheme.colorScheme.primary,
                    )
                    PulseDropdownMenuItem(
                        text = "只看楼主",
                        subtitle = if (opOnly) "仅显示主题作者楼层，点击恢复" else "隐藏其他成员回复",
                        icon = Icons.Rounded.Person,
                        selected = opOnly,
                        badgeText = if (opOnly) "已开启" else null,
                        onClick = {
                            menuExpanded = false
                            onToggleOpOnly()
                        },
                    )
                    PulseDropdownMenuItem(
                        text = "跳楼与页码导航",
                        subtitle = "快速跳转楼层或切换分页面",
                        icon = Icons.Rounded.UnfoldMore,
                        badgeText = "P$currentPage/$totalPages",
                        onClick = {
                            menuExpanded = false
                            onOpenFloorJumpSheet()
                        },
                    )
                    PulseDropdownMenuItem(
                        text = if (reverseOrder) "切换为正序阅读" else "切换为倒序本页",
                        subtitle = if (reverseOrder) "当前本页楼层倒序排列" else "从本页最末回复向上浏览",
                        icon = Icons.Rounded.SwapVert,
                        selected = reverseOrder,
                        badgeText = if (reverseOrder) "倒序中" else null,
                        showCheckWhenSelected = false,
                        onClick = {
                            menuExpanded = false
                            onToggleReverseOrder()
                        },
                    )
                    PulseDropdownMenuItem(
                        text = "刷新当前页",
                        subtitle = "重新拉取最新楼层与互动状态",
                        icon = Icons.Rounded.Refresh,
                        onClick = {
                            menuExpanded = false
                            onRefresh()
                        },
                    )

                    PulseDropdownMenuDivider()
                    PulseDropdownMenuSectionHeader(
                        title = "互动与管理",
                        accentColor = ext.drumstickGoldEnd,
                    )
                    PulseDropdownMenuItem(
                        text = if (isTrackingReplies) "追踪新回复" else "开启追更提醒",
                        subtitle = if (isTrackingReplies) "正在监测新回复，点击停止追踪" else "有新回复时及时追踪提醒",
                        icon = if (isTrackingReplies) {
                            Icons.Rounded.NotificationsActive
                        } else {
                            Icons.Rounded.NotificationsNone
                        },
                        selected = isTrackingReplies,
                        badgeText = if (isTrackingReplies) "追踪中" else null,
                        onClick = {
                            menuExpanded = false
                            onToggleReplyTracking()
                        },
                    )
                    PulseDropdownMenuItem(
                        text = if (isBookmarked) "已收藏主题" else "收藏主题",
                        subtitle = if (isBookmarked) "已缓存首屏离线快照 · 点击取消" else "加入收藏并同步离线快照",
                        icon = if (isBookmarked) {
                            Icons.Rounded.Bookmark
                        } else {
                            Icons.Rounded.BookmarkBorder
                        },
                        selected = isBookmarked,
                        badgeText = if (isBookmarked) "已收藏" else null,
                        accentColor = if (isBookmarked) ext.drumstickGoldEnd else null,
                        onClick = {
                            menuExpanded = false
                            onToggleBookmark()
                        },
                    )
                    if (canEditTopic) {
                        if (isTradeTopic) {
                            PulseDropdownMenuItem(
                                text = "一键标记已出 & 交割助手",
                                subtitle = "标题追加 [已出]、脱敏隐私并核对交割清单",
                                icon = Icons.Rounded.Sell,
                                accentColor = NodePulseThemeTokens.categorySpec("trade").spineColor,
                                onClick = {
                                    menuExpanded = false
                                    onOpenTradeClosure()
                                },
                            )
                        }
                        if (isCarpoolTopic) {
                            PulseDropdownMenuItem(
                                text = "更新拼车状态",
                                subtitle = "一键切换 3=1 / 已满车 等车位状态",
                                icon = Icons.Rounded.DirectionsCar,
                                accentColor = NodePulseThemeTokens.categorySpec("carpool").spineColor,
                                onClick = {
                                    menuExpanded = false
                                    onOpenCarpoolStatus()
                                },
                            )
                        }
                        PulseDropdownMenuItem(
                            text = "编辑主题",
                            subtitle = "修改主楼标题、正文或阅读等级",
                            icon = Icons.Rounded.Edit,
                            accentColor = MaterialTheme.colorScheme.secondary,
                            onClick = {
                                menuExpanded = false
                                onEditTopic()
                            },
                        )
                        PulseDropdownMenuItem(
                            text = "生成 / 管理抽奖",
                            subtitle = "配置 Drand 可验证 `/lucky` 抽奖链接",
                            icon = Icons.Rounded.Casino,
                            accentColor = MaterialTheme.colorScheme.primary,
                            onClick = {
                                menuExpanded = false
                                onOpenLuckyGenerator()
                            },
                        )
                    }

                    PulseDropdownMenuDivider()
                    PulseDropdownMenuSectionHeader(
                        title = "分享与链接",
                        accentColor = MaterialTheme.colorScheme.tertiary,
                    )
                    PulseDropdownMenuItem(
                        text = "复制帖子链接",
                        icon = Icons.Rounded.ContentCopy,
                        onClick = {
                            menuExpanded = false
                            onCopyLink()
                        },
                    )
                    PulseDropdownMenuItem(
                        text = "分享帖子",
                        icon = Icons.Rounded.Share,
                        onClick = {
                            menuExpanded = false
                            onShare()
                        },
                    )
                    PulseDropdownMenuItem(
                        text = "生成杂志长图",
                        icon = Icons.Rounded.Image,
                        accentColor = MaterialTheme.colorScheme.primary,
                        onClick = {
                            menuExpanded = false
                            onShareLongImage()
                        },
                    )
                    PulseDropdownMenuItem(
                        text = "内置 WebView 浏览",
                        icon = Icons.Rounded.Language,
                        onClick = {
                            menuExpanded = false
                            onOpenInWebView()
                        },
                    )
                    PulseDropdownMenuItem(
                        text = "在系统浏览器中打开",
                        icon = Icons.AutoMirrored.Rounded.OpenInNew,
                        onClick = {
                            menuExpanded = false
                            onOpenBrowser()
                        },
                    )
                }
            }
        }

        if (isPageSwitching) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
        }
    }
}

// ── Magazine Article Header & Floor 0 ─────────────────────────────────────────

@Composable
private fun MagazineArticleHeader(
    pageData: TopicDetailPage,
    openingFloor: FloorComment?,
    effectiveBookmarkCount: Int,
    canEditTopic: Boolean,
    isTrackingReplies: Boolean,
    trackedBaselineFloor: Int,
    totalPages: Int,
    showTrustQuickBadge: Boolean,
    onCategoryClick: () -> Unit,
    onAuthorClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onInspectTrust: () -> Unit,
    onEditTopic: () -> Unit,
    onJumpToFloor: (Int) -> Unit,
    onJumpToLastPage: () -> Unit,
    onToggleReplyTracking: () -> Unit,
    marketReference: TradeMarketReferenceState? = null,
    marketCooldownRemainingSeconds: Int = 0,
    isMutatingTopic: Boolean = false,
    onSearchMarketRemote: ((String?) -> Unit)? = null,
    onOpenMarketValuation: (() -> Unit)? = null,
    onOpenMarketTopic: ((Long, String) -> Unit)? = null,
    onQuickCarpoolStatus: ((String) -> Unit)? = null,
    onOpenCarpoolStatusSheet: (() -> Unit)? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val categorySpec = NodePulseThemeTokens.categorySpec(pageData.categorySlug)
    val author = openingFloor?.author ?: pageData.author
    val publishIso = openingFloor?.createdAtIso?.takeIf { it.isNotBlank() }
        ?: pageData.createdAtIso.orEmpty()

    val headerWashBrush = Brush.verticalGradient(
        colors = listOf(
            categorySpec.spineColor.copy(alpha = if (isDark) 0.14f else 0.085f),
            categorySpec.gradientEndColor.copy(alpha = if (isDark) 0.04f else 0.025f),
            Color.Transparent,
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(headerWashBrush)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Editorial Eyebrow Row
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CategoryMicroTag(
                slug = pageData.categorySlug,
                labelOverride = pageData.categoryName,
                compact = false,
                onClick = onCategoryClick,
            )

            if (pageData.requiredViewLevel > 0) {
                AccessLockChip(
                    requiredLevel = pageData.requiredViewLevel,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }

            if (pageData.isCuratedAward) {
                EditorialStatusPill(
                    icon = Icons.Rounded.EmojiEvents,
                    label = "推荐阅读",
                    containerColor = ext.drumstickContainer,
                    contentColor = ext.onDrumstickContainer,
                    borderColor = ext.drumstickGoldEnd.copy(alpha = 0.4f),
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }

            if (pageData.isLocked) {
                EditorialStatusPill(
                    icon = Icons.Rounded.Lock,
                    label = "已锁定",
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    borderColor = ext.cardBorder,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .clip(CircleShape)
                    .background(ext.cardSurface.copy(alpha = if (isDark) 0.72f else 0.85f))
                    .border(0.8.dp, ext.cardBorder.copy(alpha = 0.7f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                TelemetryMetricItem(
                    icon = Icons.Rounded.Visibility,
                    text = formatCompactCount(pageData.viewCount),
                )
                TelemetryMetricItem(
                    icon = Icons.Rounded.BookmarkBorder,
                    text = formatCompactCount(effectiveBookmarkCount),
                )
            }
        }

        // Selectable Magazine Headline
        SelectionContainer {
            Text(
                text = pageData.title,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.35).sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        // Author Telemetry Hero Card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pulseHeroCard(
                    accentColor = categorySpec.spineColor,
                    shape = PulseArchitecturalShapes.ElevatedTile,
                    elevation = PulseElevation.Card,
                )
                .padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            PulseAvatar(
                avatarUrl = author.avatarUrl,
                username = author.username,
                size = 46.dp,
                rankLevel = author.level,
                ringWidth = 2.2.dp,
                onClick = onAvatarClick,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = author.username,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .clickable(onClick = onAuthorClick),
                    )
                    OpHighlightChip(
                        label = "楼主",
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    author.level?.let {
                        RankMicroBadge(
                            level = it,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                    author.roleBadges.forEach { badge ->
                        AuthorRoleMicroPill(
                            badge = badge,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (publishIso.isNotBlank()) {
                        RelativeTimeText(
                            isoTimestamp = publishIso,
                            prefix = "发布于 ",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                    if (!openingFloor?.editedAtIso.isNullOrBlank()) {
                        Text(
                            text = "· 已编辑",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = "UID ${author.uid}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (showTrustQuickBadge && (author.uid > 0L || author.username.isNotBlank())) {
                SellerTrustQuickBadge(
                    uid = author.uid,
                    usernameHint = author.username,
                    onClick = onInspectTrust,
                )
            }

            if (canEditTopic) {
                FilledTonalButton(
                    onClick = onEditTopic,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("编辑", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                }
            }
        }

        if (isTrackingReplies) {
            var trackingMenuExpanded by remember { mutableStateOf(false) }
            Box {
                val pillLabel = if (trackedBaselineFloor >= 0) {
                    "追踪新回复中 · 已跟进至 #$trackedBaselineFloor 楼"
                } else {
                    "追踪新回复中 · 同步最新楼层"
                }
                PulseAccentPill(
                    text = pillLabel,
                    icon = Icons.Rounded.NotificationsActive,
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .pulseBouncyClickable { trackingMenuExpanded = true },
                )

                PulseDropdownMenu(
                    expanded = trackingMenuExpanded,
                    onDismissRequest = { trackingMenuExpanded = false },
                    minWidth = 228.dp,
                    maxWidth = 290.dp,
                ) {
                    PulseDropdownMenuSectionHeader(
                        title = "追更雷达控制",
                        accentColor = MaterialTheme.colorScheme.primary,
                    )
                    if (trackedBaselineFloor > 0) {
                        PulseDropdownMenuItem(
                            text = "跳至已跟进楼层 #$trackedBaselineFloor",
                            subtitle = "定位上次已读基准楼层",
                            icon = Icons.Rounded.NotificationsActive,
                            selected = true,
                            badgeText = "#$trackedBaselineFloor",
                            showCheckWhenSelected = false,
                            onClick = {
                                trackingMenuExpanded = false
                                onJumpToFloor(trackedBaselineFloor)
                            },
                        )
                    }
                    PulseDropdownMenuItem(
                        text = "跳至最新末页",
                        subtitle = "查看当前最新第 $totalPages 页回复",
                        icon = Icons.Rounded.KeyboardDoubleArrowDown,
                        badgeText = "第 $totalPages 页",
                        onClick = {
                            trackingMenuExpanded = false
                            onJumpToLastPage()
                        },
                    )
                    PulseDropdownMenuDivider()
                    PulseDropdownMenuItem(
                        text = "停止追踪新回复",
                        subtitle = "移除本帖后续新回复通知",
                        icon = Icons.Rounded.NotificationsOff,
                        isDestructive = true,
                        onClick = {
                            trackingMenuExpanded = false
                            onToggleReplyTracking()
                        },
                    )
                }
            }
        }

        val isCarpoolTopic = pageData.categorySlug.equals("carpool", ignoreCase = true) ||
            pageData.categoryName.trim() == "拼车"

        if (marketReference != null) {
            MarketPremiumReferenceCard(
                state = marketReference,
                cooldownRemainingSeconds = marketCooldownRemainingSeconds,
                onSearchRemote = { onSearchMarketRemote?.invoke(null) },
                onSearchWithQuery = { editedQuery -> onSearchMarketRemote?.invoke(editedQuery) },
                onOpenTopic = onOpenMarketTopic,
                onOpenValuation = onOpenMarketValuation,
            )
        }

        if (canEditTopic && isCarpoolTopic) {
            CarpoolQuickStatusBar(
                topicTitle = pageData.title,
                isMutating = isMutatingTopic,
                onQuickSelectTag = { tag -> onQuickCarpoolStatus?.invoke(tag) },
                onOpenStatusSheet = { onOpenCarpoolStatusSheet?.invoke() },
            )
        }

        PulseGradientDivider(
            color = categorySpec.spineColor.copy(alpha = 0.42f),
            thickness = 1.dp,
        )
    }
}

@Composable
private fun OpeningFloorArticleBody(
    openingFloor: FloorComment,
    isSubPageExpanded: Boolean,
    isBusy: Boolean,
    canEditTopic: Boolean,
    nsAppWidgetController: NsAppWidgetController = NoOpNsAppWidgetController,
    detectedLuckySpec: LuckyDrawSpec? = null,
    onOpenLuckySheet: (LuckyDrawSpec) -> Unit = {},
    onCollapseSubPageOp: () -> Unit,
    onMentionClick: (String) -> Unit,
    onFloorClick: (Int) -> Unit,
    onUrlClick: (String) -> Unit,
    onUrlLongClick: (String) -> Unit,
    onUpvote: () -> Unit,
    onDrumstick: () -> Unit,
    onDislike: () -> Unit,
    onReplyToOp: () -> Unit,
    onEditTopic: () -> Unit,
    onOpenMoreActions: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onOpenMoreActions,
            )
            .padding(horizontal = 18.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (isSubPageExpanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(
                        shape = PulseArchitecturalShapes.MediumBadge,
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        elevation = PulseElevation.None,
                        accentColor = MaterialTheme.colorScheme.primary,
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "主楼正文预览",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = onCollapseSubPageOp) {
                    Text("收起主楼", maxLines = 1, softWrap = false)
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MarkdownRenderer(
                markdown = openingFloor.markdownBody,
                bodyStyle = MaterialTheme.typography.bodyLarge,
                onMentionClick = onMentionClick,
                onFloorClick = onFloorClick,
                onUrlClick = onUrlClick,
                onUrlLongClick = onUrlLongClick,
                nsAppWidgetController = nsAppWidgetController,
            )

            if (detectedLuckySpec != null) {
                LuckyDrawInlineBannerCard(
                    spec = detectedLuckySpec,
                    onOpenLuckySheet = { onOpenLuckySheet(detectedLuckySpec) },
                )
            }

            // Optional Author Signature Footnote
            val sig = openingFloor.signatureMarkdown?.trim().orEmpty()
            if (sig.isNotEmpty()) {
                AuthorSignatureBlock(
                    signatureMarkdown = sig,
                    fadeSurfaceColor = MaterialTheme.colorScheme.background,
                    onMentionClick = onMentionClick,
                    onFloorClick = onFloorClick,
                    onUrlClick = onUrlClick,
                    onUrlLongClick = onUrlLongClick,
                )
            }
        }

        // OP Reaction & Action Rail
        CommentActionIconRow(
            comment = openingFloor,
            isOwnReply = false,
            canPinReply = false,
            canEditTopicOnOp = canEditTopic,
            isBusy = isBusy,
            quoteActionLabel = "回复主楼",
            onUpvote = onUpvote,
            onDrumstick = onDrumstick,
            onDislike = onDislike,
            onQuoteReply = onReplyToOp,
            onEdit = onEditTopic,
            onTogglePin = {},
            onMoreActions = onOpenMoreActions,
        )

        Spacer(modifier = Modifier.height(2.dp))
    }
}

@Composable
private fun CollapsedSubPageOpBar(
    currentPage: Int,
    hasCachedOp: Boolean,
    onExpandOp: () -> Unit,
    onGoFirstPage: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                elevation = PulseElevation.Subtle,
                accentColor = MaterialTheme.colorScheme.primary,
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "正在阅读第 $currentPage 页 · 主楼已折叠",
            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (hasCachedOp) {
            TextButton(
                onClick = onExpandOp,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text("展开主楼", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
            }
        }
        TextButton(
            onClick = onGoFirstPage,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text("回第 1 页", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
        }
    }
}

// ── Threaded Floor Replies Stream ─────────────────────────────────────────────

@Composable
private fun RepliesStreamHeaderBar(
    estimatedTotalFloors: Int,
    currentPage: Int,
    loadedStartPage: Int,
    loadedEndPage: Int,
    totalPages: Int,
    opOnly: Boolean,
    reverseOrder: Boolean,
    onToggleOpOnly: () -> Unit,
    onToggleReverseOrder: () -> Unit,
    onOpenPageSheet: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val badgeShape = PulseArchitecturalShapes.SmallChip

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
    ) {
        PulseGradientDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.78f))
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(3.5.dp)
                        .height(16.dp)
                        .clip(CircleShape)
                        .background(PulseBrushes.brandGradient(horizontal = false)),
                )

                Text(
                    text = "全部回复",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f))
                        .border(
                            width = 0.8.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                            shape = CircleShape,
                        )
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = formatCompactCount(estimatedTotalFloors),
                        style = NodePulseThemeTokens.extraTypography.tabularBadge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                if (totalPages > 1) {
                    val pageBadgeText = if (loadedStartPage < loadedEndPage) {
                        "第 $loadedStartPage-$loadedEndPage/$totalPages 页"
                    } else {
                        "第 $currentPage/$totalPages 页"
                    }
                    Box(
                        modifier = Modifier
                            .clip(badgeShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(
                                width = 0.8.dp,
                                color = ext.cardBorder,
                                shape = badgeShape,
                            )
                            .pulseBouncyClickable(
                                pressedScale = NodePulseMotion.chipPressScale,
                                role = Role.Button,
                                onClick = onOpenPageSheet,
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = pageBadgeText,
                            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }

            CompactTogglePill(
                label = if (reverseOrder) "倒序 ⇅" else "正序 ⇅",
                active = reverseOrder,
                onClick = onToggleReverseOrder,
            )

            CompactTogglePill(
                label = "只看楼主",
                active = opOnly,
                onClick = onToggleOpOnly,
            )
        }
        PulseGradientDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
        )
    }
}

/**
 * Layered editorial floor reply card with soft elevation, hairline border,
 * and subtle tonal highlights for pinned, OP, or jump-targeted floors.
 */
@Composable
private fun ThreadedFloorReplyRow(
    reply: FloorComment,
    isOpAuthor: Boolean,
    isOwnReply: Boolean,
    isViewerTopicAuthor: Boolean,
    isHighlighted: Boolean,
    isBusy: Boolean,
    showTrustQuickBadge: Boolean,
    nsAppWidgetController: NsAppWidgetController = NoOpNsAppWidgetController,
    onAuthorClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onMentionClick: (String) -> Unit,
    onFloorClick: (Int) -> Unit,
    onUrlClick: (String) -> Unit,
    onUrlLongClick: (String) -> Unit,
    onUpvote: () -> Unit,
    onDrumstick: () -> Unit,
    onDislike: () -> Unit,
    onQuoteReply: () -> Unit,
    onEditReply: () -> Unit,
    onTogglePin: () -> Unit,
    onOpenMoreActions: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val cardContainer by animateColorAsState(
        targetValue = when {
            isHighlighted -> blendColor(ext.cardSurface, MaterialTheme.colorScheme.primaryContainer, 0.36f)
            reply.isPinned -> blendColor(ext.cardSurface, MaterialTheme.colorScheme.secondaryContainer, 0.24f)
            else -> ext.cardSurface
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "replyCardBg",
    )
    val cardBorder by animateColorAsState(
        targetValue = when {
            isHighlighted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.58f)
            reply.isPinned -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.42f)
            isOpAuthor -> MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
            else -> ext.cardBorder
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "replyCardBorder",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = cardContainer,
                borderColor = cardBorder,
                elevation = if (isHighlighted || reply.isPinned) {
                    PulseElevation.CardElevated
                } else {
                    PulseElevation.Card
                },
            )
            .combinedClickable(
                onClick = {},
                onLongClick = onOpenMoreActions,
            )
            .padding(start = 15.dp, end = 15.dp, top = 14.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // Header Row: Avatar + Identity Chips + Timestamp + Compact Tabular `#floor` Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PulseAvatar(
                avatarUrl = reply.author.avatarUrl,
                username = reply.author.username,
                size = 36.dp,
                rankLevel = reply.author.level,
                ringWidth = 1.6.dp,
                onClick = onAvatarClick,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = reply.author.username,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .clickable(onClick = onAuthorClick),
                    )

                    reply.author.level?.let { lv ->
                        RankMicroBadge(
                            level = lv,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }

                    if (isOpAuthor) {
                        OpHighlightChip(
                            label = "楼主",
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }

                    reply.author.roleBadges.forEach { badge ->
                        AuthorRoleMicroPill(
                            badge = badge,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }

                    if (reply.isPinned) {
                        EditorialStatusPill(
                            icon = Icons.Rounded.PushPin,
                            label = "置顶",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            borderColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.28f),
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }

                    if (reply.isHot) {
                        EditorialStatusPill(
                            icon = Icons.Rounded.LocalFireDepartment,
                            label = "热评",
                            containerColor = ext.heatContainer,
                            contentColor = ext.onHeatContainer,
                            borderColor = ext.onHeatContainer.copy(alpha = 0.28f),
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }

                    if (showTrustQuickBadge && (reply.author.uid > 0L || reply.author.username.isNotBlank())) {
                        SellerTrustQuickBadge(
                            uid = reply.author.uid,
                            usernameHint = reply.author.username,
                            compact = true,
                            onClick = onAvatarClick,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RelativeTimeText(
                        isoTimestamp = reply.createdAtIso,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (!reply.editedAtIso.isNullOrBlank()) {
                        Text(
                            text = "· 已编辑",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FloorNumberPill(
                    floorNumber = reply.floorNumber,
                    onClick = onQuoteReply,
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f))
                        .border(
                            width = 0.8.dp,
                            color = ext.cardBorder.copy(alpha = 0.7f),
                            shape = CircleShape,
                        )
                        .pulseBouncyClickable(
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.Button,
                            onClickLabel = "更多楼层操作",
                            onClick = onOpenMoreActions,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreHoriz,
                        contentDescription = "更多楼层操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // Markdown Body + Optional Author Signature
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MarkdownRenderer(
                markdown = reply.markdownBody,
                bodyStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.94f,
                    lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 0.92f,
                ),
                onMentionClick = onMentionClick,
                onFloorClick = onFloorClick,
                onUrlClick = onUrlClick,
                onUrlLongClick = onUrlLongClick,
                nsAppWidgetController = nsAppWidgetController,
            )

            // Author Signature (if non-blank)
            val sig = reply.signatureMarkdown?.trim().orEmpty()
            if (sig.isNotEmpty()) {
                AuthorSignatureBlock(
                    signatureMarkdown = sig,
                    fadeSurfaceColor = cardContainer,
                    onMentionClick = onMentionClick,
                    onFloorClick = onFloorClick,
                    onUrlClick = onUrlClick,
                    onUrlLongClick = onUrlLongClick,
                )
            }
        }

        // Compact Icon-Only Comment Actions
        CommentActionIconRow(
            comment = reply,
            isOwnReply = isOwnReply,
            canPinReply = isViewerTopicAuthor,
            canEditTopicOnOp = false,
            isBusy = isBusy,
            quoteActionLabel = "引用回复",
            onUpvote = onUpvote,
            onDrumstick = onDrumstick,
            onDislike = onDislike,
            onQuoteReply = onQuoteReply,
            onEdit = onEditReply,
            onTogglePin = onTogglePin,
        )
    }
}

@Composable
private fun AuthorSignatureBlock(
    signatureMarkdown: String,
    fadeSurfaceColor: Color,
    onMentionClick: (String) -> Unit,
    onFloorClick: (Int) -> Unit,
    onUrlClick: (String) -> Unit,
    onUrlLongClick: ((String) -> Unit)? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val density = LocalDensity.current
    val collapseHeightPx = with(density) { 52.dp.roundToPx() }
    val thresholdHeightPx = with(density) { 68.dp.roundToPx() }

    val baseSmall = MaterialTheme.typography.bodySmall
    val sigStyle = baseSmall.copy(
        fontSize = baseSmall.fontSize * 0.90f,
        lineHeight = baseSmall.lineHeight * 0.88f,
    )
    val sigTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f)
    val sigLinkColor = blendColor(
        source = MaterialTheme.colorScheme.onSurfaceVariant,
        target = MaterialTheme.colorScheme.primary,
        fraction = 0.48f,
    ).copy(alpha = 0.86f)
    val dashColor = ext.cardBorder.copy(alpha = 0.65f)

    var expanded by rememberSaveable(signatureMarkdown) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val strokePx = 0.8.dp.toPx()
                val dashPx = 4.dp.toPx()
                val gapPx = 3.dp.toPx()
                drawLine(
                    color = dashColor,
                    start = Offset(0f, strokePx / 2f),
                    end = Offset(size.width, strokePx / 2f),
                    strokeWidth = strokePx,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashPx, gapPx), 0f),
                )
            }
            .padding(top = 6.dp),
    ) {
        SubcomposeLayout(
            modifier = Modifier.fillMaxWidth(),
        ) { constraints ->
            val contentMeasurables = subcompose("signature_content") {
                MarkdownRenderer(
                    markdown = signatureMarkdown,
                    bodyStyle = sigStyle,
                    textColor = sigTextColor,
                    linkColor = sigLinkColor,
                    compactSignatureMode = true,
                    inlineSignatureFooter = true,
                    onMentionClick = onMentionClick,
                    onFloorClick = onFloorClick,
                    onUrlClick = onUrlClick,
                    onUrlLongClick = onUrlLongClick,
                )
            }
            val contentPlaceable = contentMeasurables.first().measure(constraints)
            val overflows = contentPlaceable.height > thresholdHeightPx
            val collapsed = overflows && !expanded
            val displayHeight = if (collapsed) {
                collapseHeightPx.coerceAtMost(contentPlaceable.height)
            } else {
                contentPlaceable.height
            }

            val fadePlaceable = if (collapsed && displayHeight > 0) {
                val fadeHeightPx = with(density) { 18.dp.roundToPx() }
                    .coerceAtMost((displayHeight * 0.45f).toInt())
                if (fadeHeightPx > 0) {
                    subcompose("signature_fade") {
                        Box(
                            modifier = Modifier.background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, fadeSurfaceColor),
                                ),
                            ),
                        )
                    }.first().measure(Constraints.fixed(contentPlaceable.width, fadeHeightPx))
                } else {
                    null
                }
            } else {
                null
            }

            val togglePlaceable = if (overflows) {
                subcompose("signature_toggle") {
                    Text(
                        text = if (expanded) "收起 ▴" else "展开签名 ▾",
                        style = NodePulseThemeTokens.extraTypography.microLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .padding(top = 3.dp)
                            .clickable(role = Role.Button) { expanded = !expanded },
                    )
                }.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
            } else {
                null
            }

            val totalHeight = displayHeight + (togglePlaceable?.height ?: 0)
            layout(contentPlaceable.width, totalHeight) {
                if (collapsed) {
                    val clipBottom = displayHeight.toFloat()
                    contentPlaceable.placeRelativeWithLayer(0, 0) {
                        shape = GenericShape { size, _ ->
                            addRect(Rect(0f, 0f, size.width, clipBottom))
                        }
                        clip = true
                    }
                } else {
                    contentPlaceable.placeRelative(0, 0)
                }
                fadePlaceable?.placeRelative(0, displayHeight - fadePlaceable.height)
                togglePlaceable?.placeRelative(0, displayHeight)
            }
        }
    }
}

// ── Lightweight Icon-Only Comment Actions ─────────────────────────────────────

@Composable
private fun CommentActionIconRow(
    comment: FloorComment,
    isOwnReply: Boolean,
    canPinReply: Boolean,
    canEditTopicOnOp: Boolean,
    isBusy: Boolean,
    quoteActionLabel: String,
    onUpvote: () -> Unit,
    onDrumstick: () -> Unit,
    onDislike: () -> Unit,
    onQuoteReply: () -> Unit,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onMoreActions: (() -> Unit)? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val mutedTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 👍 点赞 (`UPVOTE`)
        CompactReactionAction(
            commentId = comment.commentId,
            icon = if (comment.hasUpvoted) Icons.Rounded.ThumbUp else Icons.Rounded.ThumbUpOffAlt,
            contentDescription = if (comment.upvoteCount > 0) "点赞 ${comment.upvoteCount}" else "点赞",
            onClickLabel = "点赞",
            count = comment.upvoteCount,
            active = comment.hasUpvoted,
            activeStateLabel = "已点赞",
            activeTint = MaterialTheme.colorScheme.primary,
            inactiveTint = mutedTint,
            enabled = !isBusy,
            onClick = onUpvote,
        )

        // 🍗 加鸡腿 (`DRUMSTICK`)
        CompactReactionAction(
            commentId = comment.commentId,
            icon = if (comment.hasGivenDrumstick) NodeSeekDrumstickFilledIcon else NodeSeekDrumstickIcon,
            contentDescription = if (comment.drumstickCount > 0) "加鸡腿 ${comment.drumstickCount}" else "加鸡腿",
            onClickLabel = "加鸡腿",
            count = comment.drumstickCount,
            active = comment.hasGivenDrumstick,
            activeStateLabel = "已投喂鸡腿",
            activeTint = ext.drumstickGoldEnd,
            inactiveTint = mutedTint,
            enabled = !isBusy,
            onClick = onDrumstick,
        )

        // 👎 反对 (`DISLIKE`)
        CompactReactionAction(
            commentId = comment.commentId,
            icon = if (comment.hasDisliked) Icons.Rounded.ThumbDown else Icons.Rounded.ThumbDownOffAlt,
            contentDescription = if (comment.dislikeCount > 0) "反对 ${comment.dislikeCount}" else "反对",
            onClickLabel = "反对",
            count = comment.dislikeCount,
            active = comment.hasDisliked,
            activeStateLabel = "已反对",
            activeTint = MaterialTheme.colorScheme.error,
            inactiveTint = mutedTint,
            enabled = !isBusy,
            onClick = onDislike,
        )

        // 💬 引用回复
        CompactReactionAction(
            commentId = comment.commentId,
            icon = Icons.Rounded.FormatQuote,
            contentDescription = quoteActionLabel,
            onClickLabel = quoteActionLabel,
            count = 0,
            active = false,
            activeStateLabel = null,
            activeTint = mutedTint,
            inactiveTint = mutedTint,
            enabled = true,
            onClick = onQuoteReply,
        )

        // Edit own reply (or OP edit topic)
        if (isOwnReply || canEditTopicOnOp) {
            CompactReactionAction(
                commentId = comment.commentId,
                icon = Icons.Rounded.Edit,
                contentDescription = "编辑",
                onClickLabel = "编辑",
                count = 0,
                active = false,
                activeStateLabel = null,
                activeTint = mutedTint,
                inactiveTint = mutedTint,
                enabled = !isBusy,
                onClick = onEdit,
            )
        }

        // OP Pin / Unpin reply
        if (canPinReply) {
            CompactReactionAction(
                commentId = comment.commentId,
                icon = Icons.Rounded.PushPin,
                contentDescription = if (comment.isPinned) "取消置顶" else "置顶",
                onClickLabel = if (comment.isPinned) "取消置顶" else "置顶",
                count = 0,
                active = comment.isPinned,
                activeStateLabel = "已置顶",
                activeTint = MaterialTheme.colorScheme.secondary,
                inactiveTint = mutedTint,
                enabled = !isBusy,
                onClick = onTogglePin,
            )
        }

        if (onMoreActions != null) {
            CompactReactionAction(
                commentId = comment.commentId,
                icon = Icons.Rounded.MoreHoriz,
                contentDescription = "更多操作",
                onClickLabel = "更多操作",
                count = 0,
                active = false,
                activeStateLabel = null,
                activeTint = mutedTint,
                inactiveTint = mutedTint,
                enabled = true,
                onClick = onMoreActions,
            )
        }

        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(14.dp)
                    .align(Alignment.CenterVertically),
                strokeWidth = 1.8.dp,
            )
        }
    }
}

@Composable
private fun CompactReactionAction(
    commentId: Long,
    icon: ImageVector,
    contentDescription: String,
    onClickLabel: String,
    count: Int,
    active: Boolean,
    activeStateLabel: String?,
    activeTint: Color,
    inactiveTint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(
        targetValue = if (active) activeTint else inactiveTint,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "reactionTint",
    )

    var wasActive by remember(commentId) { mutableStateOf(active) }
    val popScale = remember(commentId) { Animatable(1f) }
    LaunchedEffect(commentId, active) {
        if (active && !wasActive) {
            popScale.snapTo(0.84f)
            popScale.animateTo(
                targetValue = 1.22f,
                animationSpec = NodePulseMotion.microBounceSpring(),
            )
            popScale.animateTo(
                targetValue = 1f,
                animationSpec = NodePulseMotion.quickSpring(),
            )
        } else if (!active) {
            popScale.snapTo(1f)
        }
        wasActive = active
    }

    Row(
        modifier = Modifier
            .heightIn(min = 26.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .clip(CircleShape)
            .pulseBouncyClickable(
                enabled = enabled,
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Button,
                onClickLabel = onClickLabel,
                onClick = onClick,
            )
            .then(
                if (active && activeStateLabel != null) {
                    Modifier.semantics { stateDescription = activeStateLabel }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier
                .size(16.5.dp)
                .graphicsLayer {
                    val scale = popScale.value
                    scaleX = scale
                    scaleY = scale
                },
        )
        if (count > 0) {
            Text(
                text = formatCompactCount(count),
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = tint,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

// ── Single Anchored Tonal Quick-Reply Bar ─────────────────────────────────────

@Composable
private fun AnchoredQuickReplyBar(
    isLocked: Boolean,
    hasUnsentDraft: Boolean,
    draftPreview: String,
    isBookmarked: Boolean,
    visibleFloorFlow: StateFlow<Int>,
    estimatedTotalFloors: Int,
    onOpenReplyComposer: () -> Unit,
    onToggleBookmark: () -> Unit,
    onShare: () -> Unit,
    onOpenFloorJumpSheet: () -> Unit,
    categorySlug: String? = null,
    isEmbeddedInDualPane: Boolean = false,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val categoryAccent = categorySlug?.takeIf { it.isNotBlank() }?.let {
        NodePulseThemeTokens.categorySpec(it).spineColor
    }
    val bookmarkScale by animateFloatAsState(
        targetValue = if (isBookmarked) 1.14f else 1.0f,
        animationSpec = NodePulseMotion.microBounceSpring(),
        label = "readerBookmarkScale",
    )
    val bookmarkBg by animateColorAsState(
        targetValue = if (isBookmarked) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "readerBookmarkBg",
    )
    val bookmarkBorder by animateColorAsState(
        targetValue = if (isBookmarked) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
        } else {
            ext.cardBorder
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "readerBookmarkBorder",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseGlassSurface(
                showTopBorder = true,
                showBottomBorder = false,
                tonalAccent = categoryAccent,
            )
            .then(
                if (isEmbeddedInDualPane) {
                    Modifier.windowInsetsPadding(
                        WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.End),
                    )
                } else {
                    Modifier.navigationBarsPadding()
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            // 1. Inline Quick-Reply Prompt Pill
            val promptBorderModifier = if (hasUnsentDraft) {
                Modifier.border(
                    width = 1.25.dp,
                    brush = PulseBrushes.brandGradient(),
                    shape = CircleShape,
                )
            } else {
                Modifier.border(
                    width = 1.dp,
                    color = ext.cardBorder,
                    shape = CircleShape,
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .graphicsLayer { alpha = if (isLocked) 0.65f else 1f }
                    .clip(CircleShape)
                    .background(ext.cardSurface)
                    .then(promptBorderModifier)
                    .pulseBouncyClickable(
                        enabled = !isLocked,
                        pressedScale = NodePulseMotion.cardPressScale,
                        role = Role.Button,
                        onClick = onOpenReplyComposer,
                    )
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    imageVector = if (isLocked) Icons.Rounded.Lock else Icons.Rounded.ChatBubbleOutline,
                    contentDescription = null,
                    tint = if (hasUnsentDraft) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(16.dp),
                )
                val promptText = when {
                    isLocked -> "主题已锁定"
                    hasUnsentDraft -> "草稿: ${draftPreview.replace('\n', ' ').trim()}"
                    else -> "说说你的看法，支持 Markdown…"
                }
                Text(
                    text = promptText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (hasUnsentDraft) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (hasUnsentDraft) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 2. 1-Tap Bookmark Toggle
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .graphicsLayer {
                        scaleX = bookmarkScale
                        scaleY = bookmarkScale
                    }
                    .clip(CircleShape)
                    .background(bookmarkBg)
                    .border(
                        width = 0.9.dp,
                        color = bookmarkBorder,
                        shape = CircleShape,
                    )
                    .pulseBouncyClickable(
                        pressedScale = NodePulseMotion.chipPressScale,
                        role = Role.Button,
                        onClickLabel = if (isBookmarked) "取消收藏" else "收藏并离线缓存",
                        onClick = onToggleBookmark,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    contentDescription = if (isBookmarked) "取消收藏" else "收藏并离线缓存",
                    tint = if (isBookmarked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(19.dp),
                )
            }

            // 3. Share Action
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(
                        width = 0.9.dp,
                        color = ext.cardBorder,
                        shape = CircleShape,
                    )
                    .pulseBouncyClickable(
                        pressedScale = NodePulseMotion.chipPressScale,
                        role = Role.Button,
                        onClickLabel = "分享帖子",
                        onClick = onShare,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = "分享帖子",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            // 4. Interactive `currentFloor / totalFloors` Counter Pill -> Floor Jump Sheet
            LiveFloorJumpPill(
                visibleFloorFlow = visibleFloorFlow,
                estimatedTotalFloors = estimatedTotalFloors,
                onClick = onOpenFloorJumpSheet,
            )
        }
    }
}

@Composable
private fun LiveFloorJumpPill(
    visibleFloorFlow: StateFlow<Int>,
    estimatedTotalFloors: Int,
    onClick: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val currentFloor by visibleFloorFlow.collectAsStateWithLifecycle()
    val floorText = if (currentFloor <= 0) {
        "OP/$estimatedTotalFloors"
    } else {
        "$currentFloor/$estimatedTotalFloors"
    }
    Row(
        modifier = Modifier
            .height(38.dp)
            .then(
                if (!NodePulseThemeTokens.isDark) {
                    Modifier.shadow(
                        elevation = PulseElevation.Card,
                        shape = CircleShape,
                        ambientColor = ext.softShadowColor,
                        spotColor = ext.softShadowColor,
                    )
                } else {
                    Modifier
                },
            )
            .clip(CircleShape)
            .background(PulseBrushes.brandGradient())
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = floorText,
            style = NodePulseThemeTokens.extraTypography.tabularBadge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
            softWrap = false,
        )
        Icon(
            imageVector = Icons.Rounded.UnfoldMore,
            contentDescription = "跳转楼层",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(14.dp),
        )
    }
}

// ── Right-Edge Draggable Floor Scrubber ───────────────────────────────────────

@Composable
private fun RightEdgeFloorScrubber(
    lazyListState: LazyListState,
    visibleFloorFlow: StateFlow<Int>,
    totalFloors: Int,
    onOpenFloorJumpSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val density = LocalDensity.current
    val trackPx = remember(density) { with(density) { 240.dp.toPx() }.coerceAtLeast(1f) }
    val haptics = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    var isDragging by remember { mutableStateOf(false) }
    var visibleRecently by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var lastHapticIndex by remember { mutableIntStateOf(-1) }
    var scrubberScrollJob by remember { mutableStateOf<Job?>(null) }

    val isScrolling = lazyListState.isScrollInProgress

    LaunchedEffect(isScrolling, isDragging) {
        if (isScrolling || isDragging) {
            visibleRecently = true
        } else {
            delay(1_800)
            visibleRecently = false
        }
    }

    val scrubberBorder by animateColorAsState(
        targetValue = if (isDragging) MaterialTheme.colorScheme.primary else ext.glassBorder,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "scrubberBorder",
    )

    AnimatedVisibility(
        visible = visibleRecently || isDragging,
        enter = fadeIn() + slideInHorizontally(initialOffsetX = { it / 2 }),
        exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it / 2 }),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .then(
                    if (!NodePulseThemeTokens.isDark) {
                        Modifier.shadow(
                            elevation = PulseElevation.FloatingBar,
                            shape = RoundedCornerShape(18.dp),
                            ambientColor = ext.softShadowColor,
                            spotColor = ext.softShadowColor,
                        )
                    } else {
                        Modifier
                    },
                )
                .clip(RoundedCornerShape(18.dp))
                .background(ext.glassSurface)
                .border(
                    width = if (isDragging) 1.2.dp else 1.dp,
                    color = scrubberBorder,
                    shape = RoundedCornerShape(18.dp),
                )
                .clickable(onClick = onOpenFloorJumpSheet)
                .pointerInput(trackPx) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            isDragging = true
                            val maxIndex = (lazyListState.layoutInfo.totalItemsCount - 1).coerceAtLeast(1)
                            dragFraction = (lazyListState.firstVisibleItemIndex.toFloat() / maxIndex.toFloat())
                                .coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            isDragging = false
                            lastHapticIndex = -1
                        },
                        onDragCancel = {
                            isDragging = false
                            lastHapticIndex = -1
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            dragFraction = (dragFraction + dragAmount / trackPx).coerceIn(0f, 1f)
                            val totalItems = lazyListState.layoutInfo.totalItemsCount
                            if (totalItems > 1) {
                                val targetIndex = (dragFraction * (totalItems - 1)).toInt().coerceIn(0, totalItems - 1)
                                if (targetIndex != lastHapticIndex) {
                                    lastHapticIndex = targetIndex
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    scrubberScrollJob?.cancel()
                                    scrubberScrollJob = coroutineScope.launch {
                                        lazyListState.scrollToItem(targetIndex)
                                    }
                                }
                            }
                        },
                    )
                }
                .padding(horizontal = 9.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val currentFloor by visibleFloorFlow.collectAsStateWithLifecycle()
            Text(
                text = if (currentFloor <= 0) "OP" else "#$currentFloor",
                style = NodePulseThemeTokens.extraTypography.tabularBadge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
            )
            Icon(
                imageVector = Icons.Rounded.UnfoldMore,
                contentDescription = "拖动快速浏览楼层",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = "$totalFloors",
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

// ── Continuous Multi-Page Stream Controls & Action Sheets ─────────────────────

@Composable
private fun EarlierPagesLoadBar(
    targetPage: Int,
    isLoading: Boolean,
    onLoadEarlier: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(ext.cardSurface)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                    shape = CircleShape,
                )
                .pulseBouncyClickable(
                    enabled = !isLoading,
                    pressedScale = NodePulseMotion.chipPressScale,
                    role = Role.Button,
                    onClick = onLoadEarlier,
                )
                .padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "正在加载第 $targetPage 页…",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "加载第 $targetPage 页（更早楼层）",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun RepliesStreamFooter(
    isLoading: Boolean,
    hasMorePages: Boolean,
    nextPage: Int,
    loadedCount: Int,
    onLoadNextPage: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isLoading -> {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(ext.cardSurface)
                        .border(1.dp, ext.cardBorder, CircleShape)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "正在加载第 $nextPage 页…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            hasMorePages -> {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f))
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                            shape = CircleShape,
                        )
                        .pulseBouncyClickable(
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.Button,
                            onClick = onLoadNextPage,
                        )
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "继续加载第 $nextPage 页",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PulseGradientDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "已加载全部 $loadedCount 条回复",
                        style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    )
                    PulseGradientDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun FloorActionBottomSheet(
    comment: FloorComment,
    isViewerTopicAuthor: Boolean,
    canEdit: Boolean,
    canTipStardust: Boolean = false,
    firstMerchantLinkUrl: String? = null,
    merchantLinkCount: Int = 0,
    onDismiss: () -> Unit,
    onTipStardust: () -> Unit = {},
    onInspectSellerTrust: () -> Unit,
    onAddMerchantLinkToRestock: (String) -> Unit,
    onReplyOrQuote: () -> Unit,
    onDrumstick: () -> Unit,
    onFreeTextSelect: () -> Unit,
    onCopyMarkdown: () -> Unit,
    onShareLongImage: () -> Unit = {},
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.84f).dp.coerceAtLeast(300.dp)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val floorTitle = if (comment.floorNumber <= 0) {
                "主楼 · ${comment.author.username}"
            } else {
                "#${comment.floorNumber} · ${comment.author.username}"
            }
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(3.5.dp)
                        .height(16.dp)
                        .clip(CircleShape)
                        .background(PulseBrushes.brandGradient(horizontal = false)),
                )
                Text(
                    text = floorTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (comment.isPinned) {
                    PulseAccentPill(
                        text = "已置顶",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .pulseCard(
                        shape = PulseArchitecturalShapes.EditorialCard,
                        elevation = PulseElevation.Subtle,
                    )
                    .padding(vertical = 4.dp),
            ) {
                FloorSheetActionRow(
                    icon = Icons.Rounded.FormatQuote,
                    label = if (comment.floorNumber <= 0) "回复主楼" else "引用回复",
                    subtitle = if (comment.floorNumber <= 0) "撰写新回复参与讨论" else "附带 @${comment.author.username} 引用块",
                    badgeContainer = MaterialTheme.colorScheme.primaryContainer,
                    iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = onReplyOrQuote,
                )
                PulseGradientDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                FloorSheetActionRow(
                    icon = if (comment.hasGivenDrumstick) NodeSeekDrumstickFilledIcon else NodeSeekDrumstickIcon,
                    label = if (comment.hasGivenDrumstick) "已投喂鸡腿" else "加鸡腿",
                    badgeText = if (comment.hasGivenDrumstick) "已投喂" else null,
                    badgeContainer = ext.drumstickContainer,
                    iconTint = ext.onDrumstickContainer,
                    onClick = onDrumstick,
                )
                if (canTipStardust) {
                    PulseGradientDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                    FloorSheetActionRow(
                        icon = Icons.Rounded.AutoAwesome,
                        label = "投喂星尘",
                        subtitle = "向 @${comment.author.username} 转账打赏星尘",
                        badgeContainer = ext.stardustContainer,
                        iconTint = ext.onStardustContainer,
                        onClick = onTipStardust,
                    )
                }
                PulseGradientDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                FloorSheetActionRow(
                    icon = Icons.Rounded.TextFields,
                    label = "自由选择 / 复制文本",
                    subtitle = "长按拖动选区 · 支持排版与全文模式",
                    onClick = onFreeTextSelect,
                )
                PulseGradientDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                FloorSheetActionRow(
                    icon = Icons.Rounded.ContentCopy,
                    label = "复制 Markdown 源码",
                    onClick = onCopyMarkdown,
                )
                PulseGradientDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                FloorSheetActionRow(
                    icon = Icons.Rounded.Image,
                    label = "生成长图分享",
                    subtitle = "导出当前楼层为杂志级长图 + 原帖二维码",
                    badgeContainer = MaterialTheme.colorScheme.primaryContainer,
                    iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = onShareLongImage,
                )
                if (comment.author.uid > 0L) {
                    PulseGradientDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                    FloorSheetActionRow(
                        icon = Icons.Rounded.Shield,
                        label = "查卖家信誉",
                        subtitle = "检索注册时长、历史交易帖与曝光/骗子关联",
                        badgeContainer = MaterialTheme.colorScheme.secondaryContainer,
                        iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                        onClick = onInspectSellerTrust,
                    )
                }
                if (!firstMerchantLinkUrl.isNullOrBlank()) {
                    PulseGradientDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                    FloorSheetActionRow(
                        icon = Icons.Rounded.Bolt,
                        label = "加入补货监控",
                        subtitle = if (merchantLinkCount > 1) {
                            "检测到 $merchantLinkCount 个商家链接 · 优先监控主下单页"
                        } else {
                            firstMerchantLinkUrl
                        },
                        badgeText = "盯盘",
                        badgeContainer = MaterialTheme.colorScheme.primaryContainer,
                        iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                        onClick = { onAddMerchantLinkToRestock(firstMerchantLinkUrl) },
                    )
                }
                if (canEdit) {
                    PulseGradientDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                    FloorSheetActionRow(
                        icon = Icons.Rounded.Edit,
                        label = "编辑内容",
                        onClick = onEdit,
                    )
                }
                if (isViewerTopicAuthor && comment.floorNumber > 0) {
                    PulseGradientDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                    FloorSheetActionRow(
                        icon = Icons.Rounded.PushPin,
                        label = if (comment.isPinned) "取消置顶" else "置顶该回复",
                        subtitle = if (comment.isPinned) "取消后不消耗鸡腿" else "楼主特权 · 首次置顶消耗 5 鸡腿",
                        badgeText = if (comment.isPinned) "置顶中" else null,
                        badgeContainer = MaterialTheme.colorScheme.secondaryContainer,
                        iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                        onClick = onTogglePin,
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ExternalLinkActionBottomSheet(
    url: String,
    onDismiss: () -> Unit,
    onAddToRestockMonitor: () -> Unit,
    onCopyLink: () -> Unit,
    onOpenInBrowser: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isMerchantLink = remember(url) { MerchantLinkDetector.isLikelyMerchantLink(url) }
    val detectedPanel = remember(url) { MerchantLinkDetector.inferPanelTypeFromUrl(url) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(3.5.dp)
                        .height(16.dp)
                        .clip(CircleShape)
                        .background(PulseBrushes.brandGradient(horizontal = false)),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isMerchantLink) "商家购买链接操作" else "外部链接操作",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isMerchantLink) {
                    PulseAccentPill(
                        text = detectedPanel.displayName,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(
                        shape = PulseArchitecturalShapes.EditorialCard,
                        elevation = PulseElevation.Subtle,
                    )
                    .padding(vertical = 4.dp),
            ) {
                FloorSheetActionRow(
                    icon = Icons.Rounded.Bolt,
                    label = "加入补货监控",
                    subtitle = "自动解包跳转链接并周期检测库存，补货即发高优弹窗直达下单",
                    badgeText = if (isMerchantLink) "推荐" else null,
                    badgeContainer = MaterialTheme.colorScheme.primaryContainer,
                    iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = onAddToRestockMonitor,
                )
                PulseGradientDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                FloorSheetActionRow(
                    icon = Icons.Rounded.ContentCopy,
                    label = "复制链接",
                    subtitle = "复制解包后的直达地址",
                    onClick = onCopyLink,
                )
                PulseGradientDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                FloorSheetActionRow(
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    label = "浏览器打开",
                    onClick = onOpenInBrowser,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun FloorSheetActionRow(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    badgeText: String? = null,
    badgeContainer: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.cardPressScale,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(PulseArchitecturalShapes.MediumBadge)
                .background(badgeContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!badgeText.isNullOrBlank()) {
            PulseAccentPill(
                text = badgeText,
                containerColor = badgeContainer.copy(alpha = 0.85f),
                contentColor = iconTint,
            )
        }
    }
}

private enum class SelectableSheetMode(val label: String) {
    RENDERED("排版选区"),
    FULL_TEXT("全文纯文本"),
}

private fun splitTextForSelectionChunks(rawText: String): List<String> {
    if (rawText.isEmpty()) return emptyList()
    val maxChunkLength = 1_200
    val chunks = ArrayList<String>()
    val current = StringBuilder()

    fun flushCurrent() {
        if (current.isNotEmpty()) {
            chunks.add(current.toString())
            current.clear()
        }
    }

    val lines = rawText.split('\n')
    for (index in lines.indices) {
        val line = if (index < lines.lastIndex) "${lines[index]}\n" else lines[index]
        if (line.length > maxChunkLength) {
            flushCurrent()
            var offset = 0
            while (offset < line.length) {
                val end = minOf(offset + maxChunkLength, line.length)
                chunks.add(line.substring(offset, end))
                offset = end
            }
        } else {
            if (current.length + line.length > maxChunkLength) {
                flushCurrent()
            }
            current.append(line)
        }
    }
    flushCurrent()
    return chunks
}

@Composable
private fun SelectableMarkdownBottomSheet(
    comment: FloorComment,
    nsAppWidgetController: NsAppWidgetController = NoOpNsAppWidgetController,
    onDismiss: () -> Unit,
    onCopyAllMarkdown: () -> Unit,
    onMentionClick: (String) -> Unit,
    onFloorClick: (Int) -> Unit,
    onUrlClick: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp.coerceAtLeast(360.dp)
    var mode by rememberSaveable(comment.commentId, comment.floorNumber) {
        mutableStateOf(SelectableSheetMode.RENDERED)
    }
    val modeOptions = remember {
        SelectableSheetMode.entries.map { SortSegmentItem(it, it.label) }
    }
    val bodyLength = comment.markdownBody.length

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(PulseArchitecturalShapes.MediumBadge)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.TextFields,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column {
                        Text(
                            text = if (comment.floorNumber <= 0) {
                                "自由选择 · 主楼正文"
                            } else {
                                "自由选择 · #${comment.floorNumber} ${comment.author.username}"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "长按文字可自由拖动选区复制 · 共 $bodyLength 字",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f))
                        .border(
                            width = 0.9.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                            shape = CircleShape,
                        )
                        .pulseBouncyClickable(
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.Button,
                            onClick = onCopyAllMarkdown,
                        )
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "复制源码",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SortSegmentedSwitcher(
                    options = modeOptions,
                    selectedValue = mode,
                    onSelect = { mode = it },
                    modifier = Modifier.width(172.dp),
                )
                Text(
                    text = if (mode == SelectableSheetMode.RENDERED) {
                        "内容较长可切换「全文纯文本」防折叠遗漏"
                    } else {
                        "已展开全部源码与折叠区块，自动换行可完整选取"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            PulseGradientDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(bottom = 16.dp),
            ) {
                val contentScrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp)
                        .pulseCard(
                            shape = PulseArchitecturalShapes.EditorialCard,
                            elevation = PulseElevation.Subtle,
                        )
                        .verticalScroll(contentScrollState)
                        .padding(horizontal = 15.dp, vertical = 16.dp),
                ) {
                    if (comment.markdownBody.isBlank()) {
                        Text(
                            text = "当前楼层暂无可选文字内容。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (mode == SelectableSheetMode.RENDERED) {
                        MarkdownRenderer(
                            markdown = comment.markdownBody,
                            bodyStyle = MaterialTheme.typography.bodyLarge,
                            enableTextSelection = true,
                            onMentionClick = onMentionClick,
                            onFloorClick = onFloorClick,
                            onUrlClick = onUrlClick,
                            nsAppWidgetController = nsAppWidgetController,
                        )
                    } else {
                        val textChunks = remember(comment.markdownBody) {
                            splitTextForSelectionChunks(comment.markdownBody)
                        }
                        SelectionContainer(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                textChunks.forEach { chunk ->
                                    Text(
                                        text = chunk,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            lineHeight = 26.sp,
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        softWrap = true,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }
        }
    }
}

@Composable
private fun OfflineSnapshotBanner(onRefresh: () -> Unit) {
    val ext = NodePulseThemeTokens.extendedColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = blendColor(
                    ext.cardSurface,
                    MaterialTheme.colorScheme.secondaryContainer,
                    0.42f,
                ),
                borderColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.30f),
                elevation = PulseElevation.Subtle,
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.OfflinePin,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = "当前正在浏览本地离线收藏快照",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .border(
                    width = 0.8.dp,
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.30f),
                    shape = CircleShape,
                )
                .pulseBouncyClickable(
                    pressedScale = NodePulseMotion.chipPressScale,
                    role = Role.Button,
                    onClick = onRefresh,
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                text = "在线刷新",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun EmptyRepliesNotice(
    opOnly: Boolean,
    hasMorePages: Boolean,
    hasNextPage: Boolean,
    onDisableOpOnly: () -> Unit,
    onNextPage: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp)
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                elevation = PulseElevation.Subtle,
            )
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Forum,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = if (opOnly) "当前页暂无楼主的回复" else "暂无回复，来抢沙发吧",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (opOnly) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDisableOpOnly, shape = CircleShape) {
                    Text("显示全部楼层", maxLines = 1, softWrap = false)
                }
                if (hasMorePages && hasNextPage) {
                    FilledTonalButton(onClick = onNextPage, shape = CircleShape) {
                        Text("查看下一页", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderLoadingSkeleton(titleHint: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Opening article skeleton
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseHeroCard(shape = PulseArchitecturalShapes.HeroBento)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PulseShimmerBox(modifier = Modifier.width(64.dp).height(22.dp), shape = CircleShape)
                PulseShimmerBox(modifier = Modifier.width(48.dp).height(22.dp), shape = CircleShape)
            }
            if (titleHint.isNotBlank()) {
                Text(
                    text = titleHint,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            } else {
                PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.92f).height(24.dp))
                PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.68f).height(24.dp))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PulseShimmerBox(modifier = Modifier.size(38.dp), shape = CircleShape)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    PulseShimmerBox(modifier = Modifier.width(104.dp).height(14.dp))
                    PulseShimmerBox(modifier = Modifier.width(142.dp).height(11.dp))
                }
            }
            PulseGradientDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            PulseShimmerBox(modifier = Modifier.fillMaxWidth().height(15.dp))
            PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.94f).height(15.dp))
            PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.86f).height(15.dp))
            PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.58f).height(15.dp))
        }

        // Reply card skeletons
        repeat(2) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(shape = PulseArchitecturalShapes.EditorialCard)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PulseShimmerBox(modifier = Modifier.size(34.dp), shape = CircleShape)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PulseShimmerBox(modifier = Modifier.width(96.dp).height(13.dp))
                        PulseShimmerBox(modifier = Modifier.width(64.dp).height(10.dp))
                    }
                    PulseShimmerBox(modifier = Modifier.width(34.dp).height(20.dp), shape = CircleShape)
                }
                PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.9f).height(14.dp))
                PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.62f).height(14.dp))
            }
        }
    }
}

@Composable
private fun TelemetryMetricItem(
    icon: ImageVector,
    text: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EditorialStatusPill(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    val shape = PulseArchitecturalShapes.SmallChip
    Row(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .then(
                if (borderColor != null) {
                    Modifier.border(width = 0.8.dp, color = borderColor, shape = shape)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 6.5.dp, vertical = 2.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
        )
    }
}

@Composable
private fun AuthorRoleMicroPill(
    badge: AuthorBadge,
    modifier: Modifier = Modifier,
) {
    val shape = PulseArchitecturalShapes.SmallChip
    Row(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .border(
                width = 0.7.dp,
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f),
                shape = shape,
            )
            .padding(horizontal = 5.5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = badge.label.ifBlank { badge.code },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun CompactTogglePill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val bg by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "compactToggleBg",
    )
    val fg by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "compactToggleFg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)
        } else {
            ext.cardBorder
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "compactToggleBorder",
    )

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .border(
                width = 0.9.dp,
                color = borderColor,
                shape = CircleShape,
            )
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Switch,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 4.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
            softWrap = false,
        )
    }
}
