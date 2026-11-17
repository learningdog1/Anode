package com.nodeseek.app.navigation

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.di.DefaultAppContainer
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.NotificationKind
import com.nodeseek.app.domain.model.ThemeMode
import com.nodeseek.app.domain.radar.InAppMessageKind
import com.nodeseek.app.domain.radar.InboxTargetResolver
import com.nodeseek.app.domain.radar.NotificationTapTarget
import com.nodeseek.app.ui.boards.BoardsScreen
import com.nodeseek.app.ui.components.InAppMessagePopupOverlay
import com.nodeseek.app.ui.components.PulseBottomDockOverlay
import com.nodeseek.app.ui.components.PulseNavTab
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.WebViewSessionSheet
import com.nodeseek.app.ui.components.rememberInAppMessagePopupState
import com.nodeseek.app.ui.composer.TopicComposerScreen
import com.nodeseek.app.ui.explore.ExploreScreen
import com.nodeseek.app.ui.hub.CheckInBoardScreen
import com.nodeseek.app.ui.hub.DirectMessageScreen
import com.nodeseek.app.ui.hub.InboxScreen
import com.nodeseek.app.ui.hub.PulseHubScreen
import com.nodeseek.app.ui.hub.SettingsScreen
import com.nodeseek.app.ui.hub.UserProfileScreen
import com.nodeseek.app.ui.mail.SeekMailScreen
import com.nodeseek.app.ui.radar.PulseRadarScreen
import com.nodeseek.app.ui.theme.NodePulseTheme
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseThemeMode
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.topic.TopicDetailScreen
import com.nodeseek.app.ui.update.AppUpdateDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val SIGN_IN_URL = "https://www.nodeseek.com/signIn.html"
private const val CHALLENGE_VERIFY_URL = "https://www.nodeseek.com/"
private const val EDITED_TOPIC_REFRESH_KEY = "edited_topic_refresh"
private const val FOREGROUND_UNREAD_POLL_INTERVAL_MS = 20_000L

private data class WebViewAuthRequest(
    val url: String,
    val requireSignedInUser: Boolean,
    val autoDismissWhenVerified: Boolean = true,
)

/**
 * Root application shell providing [LocalAppContainer], reactive [NodePulseTheme] settings
 * (`ThemeMode`, Pure Pitch AMOLED, Material You Dynamic Color, font scale), and the end-to-end
 * [NodePulseNavHost] with centralized WebView authentication & deep-link routing.
 */
@Composable
fun NodePulseRootShell(
    container: AppContainer,
    modifier: Modifier = Modifier,
    deepLinkUriFlow: StateFlow<Uri?>? = null,
    onDeepLinkConsumed: () -> Unit = {},
    notificationTapFlow: Flow<NotificationTapTarget>? = null,
    onDarkThemeResolved: (Boolean) -> Unit = {},
) {
    CompositionLocalProvider(LocalAppContainer provides container) {
        val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(
            initialValue = AppSettings(),
        )

        val pulseThemeMode = when (settings.themeMode) {
            ThemeMode.SYSTEM -> PulseThemeMode.SYSTEM
            ThemeMode.PAPER_LIGHT -> PulseThemeMode.PAPER_LIGHT
            ThemeMode.OBSIDIAN_DARK -> PulseThemeMode.OBSIDIAN_DARK
        }

        val systemDark = isSystemInDarkTheme()
        val resolvedDark = when (pulseThemeMode) {
            PulseThemeMode.SYSTEM -> systemDark
            PulseThemeMode.PAPER_LIGHT -> false
            PulseThemeMode.OBSIDIAN_DARK -> true
        }

        LaunchedEffect(resolvedDark) {
            onDarkThemeResolved(resolvedDark)
        }

        NodePulseTheme(
            themeMode = pulseThemeMode,
            purePitchAmoled = settings.purePitchAmoled,
            dynamicColor = settings.useDynamicColor,
            fontScale = settings.fontScale,
        ) {
            Surface(
                modifier = modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                NodePulseNavHost(
                    container = container,
                    deepLinkUriFlow = deepLinkUriFlow,
                    onDeepLinkConsumed = onDeepLinkConsumed,
                    notificationTapFlow = notificationTapFlow,
                )
            }
        }
    }
}

/**
 * End-to-end Compose Navigation host connecting the 3-tab Floating Pill Dock shell
 * (`ExploreScreen`, `BoardsScreen`, `PulseHubScreen`) with all child routes and a
 * centralized first-party `WebViewSessionSheet`.
 */
@Composable
fun NodePulseNavHost(
    modifier: Modifier = Modifier,
    container: AppContainer = LocalAppContainer.current,
    navController: NavHostController = rememberNavController(),
    deepLinkUriFlow: StateFlow<Uri?>? = null,
    onDeepLinkConsumed: () -> Unit = {},
    notificationTapFlow: Flow<NotificationTapTarget>? = null,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val appScope = rememberCoroutineScope()
    val rootSnackbarHostState = remember { SnackbarHostState() }
    val inAppPopupState = rememberInAppMessagePopupState()

    val session by container.sessionRepository.sessionState.collectAsStateWithLifecycle()
    val challengeRequired by container.sessionRepository.challengeRequired.collectAsStateWithLifecycle()
    val unreadCount by container.inboxRepository.unreadTotalCount.collectAsStateWithLifecycle()
    val radarUnreadCount by container.radarRepository.unreadAlertCountFlow.collectAsStateWithLifecycle(initialValue = 0)
    val sharedCheckInStatus by container.checkInRepository.checkInStatus.collectAsStateWithLifecycle()
    val updateState by container.appUpdateManager.state.collectAsStateWithLifecycle()

    // Top-level 3-tab shell state
    var selectedTab by rememberSaveable { mutableStateOf(PulseNavTab.EXPLORE) }
    var fabExpanded by rememberSaveable { mutableStateOf(true) }
    var exploreScrollToTopTrigger by rememberSaveable { mutableIntStateOf(0) }
    var boardsFocusSearchTrigger by rememberSaveable { mutableIntStateOf(0) }
    var pendingExploreCategorySlug by rememberSaveable { mutableStateOf<String?>(null) }
    var lastSelectedCategorySlug by rememberSaveable { mutableStateOf("") }

    // Centralized WebView session / challenge sheet state
    var activeWebViewRequest by remember { mutableStateOf<WebViewAuthRequest?>(null) }
    var pendingComposerSlugAfterAuth by rememberSaveable { mutableStateOf<String?>(null) }
    var challengeBannerDismissed by rememberSaveable { mutableStateOf(false) }

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val activeDmPeerUid = remember(currentBackStackEntry) {
        val entry = currentBackStackEntry ?: return@remember null
        if (entry.destination.hasRoute<DirectMessageRoute>()) {
            runCatching { entry.toRoute<DirectMessageRoute>().peerUid }.getOrNull()
        } else {
            null
        }
    }
    val latestActiveDmPeerUid by rememberUpdatedState(activeDmPeerUid)
    val latestChallengeRequired by rememberUpdatedState(challengeRequired)
    val latestWebViewActive by rememberUpdatedState(activeWebViewRequest != null)

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { }

    val synchronizedUserAgent = remember(container, context) {
        (container as? DefaultAppContainer)?.userAgentProvider?.getUserAgent(context)
    }
    val useSystemDefaultUserAgent = remember(container, synchronizedUserAgent) {
        (container as? DefaultAppContainer)?.userAgentProvider?.isSystemWebViewDefault != false
    }

    // Cold-start background session fallback only when not immediately served by Explore's "/" fetch
    LaunchedEffect(container) {
        delay(1_500L)
        if (container.sessionRepository.sessionState.value == null &&
            !container.sessionRepository.challengeRequired.value &&
            selectedTab != PulseNavTab.EXPLORE
        ) {
            container.sessionRepository.refreshSession()
        }
    }

    // Cold-start GitHub Release update check (respects autoCheckUpdates & cooldown)
    LaunchedEffect(container) {
        delay(2_000L)
        container.appUpdateManager.checkForUpdatesOnLaunch()
    }

    // Resume APK installation automatically once the user grants "Install unknown apps"
    DisposableEffect(lifecycleOwner, container) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                container.appUpdateManager.onAppResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Keep pending check-in dot & popup queue synchronized with active session
    LaunchedEffect(session?.uid) {
        inAppPopupState.clear()
        val currentSession = session
        if (currentSession == null) {
            container.checkInRepository.clearCachedStatus()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                currentSession.uid > 0L &&
                !container.notificationDispatcher.hasPostNotificationsPermission() &&
                !container.notificationDispatcher.hasRequestedPostNotificationsOnce()
            ) {
                container.notificationDispatcher.markPostNotificationsRequestedOnce()
                runCatching {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            delay(600L)
            container.checkInRepository.getCheckInBoard(page = 1, forceRefresh = false)
        }
    }

    // Clear queued DM popups for a peer as soon as the user opens that peer's 1v1 chat screen
    LaunchedEffect(activeDmPeerUid) {
        val peer = activeDmPeerUid
        if (peer != null && peer > 0L) {
            inAppPopupState.removeWhere {
                it.kind == InAppMessageKind.DIRECT_MESSAGE && it.peerUid == peer
            }
        }
    }

    // Foreground lightweight unread polling, piggyback wake, and in-app popup collection
    LaunchedEffect(lifecycleOwner, container, session?.uid) {
        val currentUid = session?.uid ?: 0L
        if (currentUid <= 0L) return@LaunchedEffect

        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                container.inboxRepository.inAppPopupEvents.collect { popup ->
                    val currentActiveDmPeer = latestActiveDmPeerUid
                    val suppressedByActiveChat = popup.kind == InAppMessageKind.DIRECT_MESSAGE &&
                        currentActiveDmPeer != null &&
                        popup.peerUid == currentActiveDmPeer
                    if (!suppressedByActiveChat) {
                        inAppPopupState.enqueue(popup)
                    }
                }
            }
            launch {
                container.inboxRepository.unreadIncreaseEvents.collect {
                    if (!latestChallengeRequired && !latestWebViewActive) {
                        container.inboxRepository.syncForegroundInbox(forceListSync = true)
                    }
                }
            }
            delay(500L)
            while (isActive) {
                if (!latestChallengeRequired && !latestWebViewActive) {
                    container.inboxRepository.syncForegroundInbox(forceListSync = false)
                }
                delay(FOREGROUND_UNREAD_POLL_INTERVAL_MS)
            }
        }
    }

    // Re-arm challenge banner if challenge state clears and later triggers again
    LaunchedEffect(challengeRequired) {
        if (!challengeRequired) {
            challengeBannerDismissed = false
        }
    }

    // Clear external category trigger after ExploreScreen observes it so re-selecting the same board works
    LaunchedEffect(selectedTab, pendingExploreCategorySlug) {
        if (selectedTab == PulseNavTab.EXPLORE && pendingExploreCategorySlug != null) {
            delay(150)
            pendingExploreCategorySlug = null
        }
    }

    // Clear one-shot scroll-to-top trigger after ExploreScreen consumes it so tab switches preserve scroll
    LaunchedEffect(selectedTab, exploreScrollToTopTrigger) {
        if (selectedTab == PulseNavTab.EXPLORE && exploreScrollToTopTrigger > 0) {
            delay(150)
            exploreScrollToTopTrigger = 0
        }
    }

    // Clear one-shot focus-search trigger after BoardsScreen consumes it so visiting Boards later shows Bento grid
    LaunchedEffect(selectedTab, boardsFocusSearchTrigger) {
        if (selectedTab == PulseNavTab.BOARDS && boardsFocusSearchTrigger > 0) {
            delay(250)
            boardsFocusSearchTrigger = 0
        }
    }

    val openWebViewAuth: (url: String, requireSignedIn: Boolean) -> Unit = remember {
        { rawUrl, requireSignedIn ->
            val trimmed = rawUrl.trim()
            val manualBrowse = trimmed.endsWith("#manual")
            val targetUrl = trimmed.removeSuffix("#manual").ifBlank { SIGN_IN_URL }
            activeWebViewRequest = WebViewAuthRequest(
                url = targetUrl,
                requireSignedInUser = requireSignedIn,
                autoDismissWhenVerified = !manualBrowse,
            )
        }
    }

    val safePopBackStack: () -> Unit = remember(navController) {
        {
            if (!navController.popBackStack()) {
                selectedTab = PulseNavTab.EXPLORE
            }
        }
    }

    val openTopic: (topicId: Long, initialFloor: Int, titleHint: String) -> Unit = remember(navController) {
        { topicId, initialFloor, titleHint ->
            if (topicId > 0L) {
                navController.navigate(
                    TopicDetailRoute(
                        topicId = topicId,
                        initialFloor = initialFloor.coerceAtLeast(0),
                        titleHint = titleHint,
                    )
                )
            }
        }
    }

    val openRadar: (initialTab: Int) -> Unit = remember(navController) {
        { initialTab ->
            navController.navigate(
                PulseRadarRoute(initialTab = initialTab.coerceIn(0, 2))
            ) {
                launchSingleTop = true
            }
        }
    }

    val openSeekMail: (initialMailboxId: Long, initialMailId: Long) -> Unit = remember(navController) {
        { initialMailboxId, initialMailId ->
            navController.navigate(
                SeekMailRoute(
                    initialMailboxId = initialMailboxId.coerceAtLeast(0L),
                    initialMailId = initialMailId.coerceAtLeast(0L),
                )
            ) {
                launchSingleTop = true
            }
        }
    }

    // Native notification tap deep-link handling
    LaunchedEffect(notificationTapFlow, navController) {
        notificationTapFlow?.collect { target ->
            when (target) {
                is NotificationTapTarget.OpenTopic -> {
                    val dedupKey = target.alertDedupKey
                    if (!dedupKey.isNullOrBlank()) {
                        appScope.launch {
                            container.radarRepository.markAlertReadByDedupKey(dedupKey)
                        }
                    }
                    val nKind = target.notificationKind
                    val nId = target.notificationId
                    if (nKind != null && nId > 0L) {
                        appScope.launch {
                            container.inboxRepository.markNotificationsRead(nKind, ids = listOf(nId))
                        }
                    }
                    openTopic(target.topicId, target.initialFloor, target.titleHint)
                }

                is NotificationTapTarget.OpenDirectMessage -> {
                    appScope.launch {
                        container.inboxRepository.markThreadRead(target.peerUid)
                    }
                    navController.navigate(
                        DirectMessageRoute(
                            peerUid = target.peerUid,
                            peerName = target.peerName,
                        )
                    )
                }

                NotificationTapTarget.OpenRadarAlerts -> {
                    openRadar(0)
                }
            }
        }
    }

    val navigateToUser: (uid: Long, usernameHint: String) -> Unit = remember(navController, container) {
        { uid, usernameHint ->
            if (uid > 0L) {
                navController.navigate(
                    UserProfileRoute(
                        uid = uid,
                        usernameHint = usernameHint,
                    )
                )
            } else {
                val cleanName = usernameHint.removePrefix("@").trim()
                if (cleanName.isNotEmpty()) {
                    appScope.launch {
                        val searchResult = container.userRepository.searchUsers(cleanName)
                        val matches = searchResult.getOrDefault(emptyList())
                        val resolved = matches.firstOrNull { it.username.equals(cleanName, ignoreCase = true) }
                            ?: matches.firstOrNull()
                        if (resolved != null && resolved.uid > 0L) {
                            navController.navigate(
                                UserProfileRoute(
                                    uid = resolved.uid,
                                    usernameHint = resolved.username,
                                )
                            )
                        } else if (container.sessionRepository.sessionState.value == null && searchResult.isFailure) {
                            rootSnackbarHostState.showSnackbar("通过 @$cleanName 查找用户需要先登录账号")
                        } else {
                            rootSnackbarHostState.showSnackbar("未找到用户 @$cleanName")
                        }
                    }
                }
            }
        }
    }

    val selectBoardAndOpenExplore: (slug: String) -> Unit = remember(navController) {
        { rawSlug ->
            val normalized = if (rawSlug.equals("all", ignoreCase = true)) "" else rawSlug.trim()
            lastSelectedCategorySlug = normalized
            pendingExploreCategorySlug = normalized
            selectedTab = PulseNavTab.EXPLORE
            fabExpanded = true
            exploreScrollToTopTrigger += 1
            navController.popBackStack(ExploreRoute, inclusive = false)
        }
    }

    // Incoming OS / App-Link deep-link handling
    val incomingDeepLinkUri = deepLinkUriFlow?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(incomingDeepLinkUri) {
        val uri = incomingDeepLinkUri ?: return@LaunchedEffect
        when (val parsed = DeepLinkParser.parseUri(uri)) {
            is NodePulseDeepLink.TopicDetail -> {
                openTopic(parsed.topicId, parsed.initialFloor, "")
            }
            is NodePulseDeepLink.CategoryBoard -> {
                selectBoardAndOpenExplore(parsed.slug)
            }
            is NodePulseDeepLink.UserSpace -> {
                navigateToUser(parsed.uid, "")
            }
            NodePulseDeepLink.CheckInBoard -> {
                navController.navigate(CheckInBoardRoute) {
                    launchSingleTop = true
                }
            }
            NodePulseDeepLink.Inbox -> {
                navController.navigate(InboxRoute(initialTab = 0)) {
                    launchSingleTop = true
                }
            }
            null -> Unit
        }
        onDeepLinkConsumed()
    }

    val tabStateHolder = rememberSaveableStateHolder()
    val dockedBottomPadding = 92.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = ExploreRoute,
            modifier = Modifier.fillMaxSize(),
            enterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                ) + fadeIn(tween(durationMillis = 220)) + scaleIn(
                    initialScale = 0.96f,
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                    targetOffset = { fullWidth -> (fullWidth * 0.22f).toInt() },
                ) + fadeOut(tween(durationMillis = 180)) + scaleOut(
                    targetScale = 0.97f,
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                )
            },
            popEnterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                    initialOffset = { fullWidth -> (fullWidth * 0.22f).toInt() },
                ) + fadeIn(tween(durationMillis = 220)) + scaleIn(
                    initialScale = 0.97f,
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                ) + fadeOut(tween(durationMillis = 180)) + scaleOut(
                    targetScale = 0.96f,
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                )
            },
        ) {
            // Root 3-tab container preserving scroll & ViewModel state across Explore / Boards / Pulse Hub
            composable<ExploreRoute> {
                // Placed before tab content so child BackHandlers (e.g. Boards inline search) run first
                BackHandler(enabled = selectedTab != PulseNavTab.EXPLORE) {
                    selectedTab = PulseNavTab.EXPLORE
                    fabExpanded = true
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = {
                            val forward = targetState.ordinal >= initialState.ordinal
                            (
                                slideInHorizontally(
                                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                                    initialOffsetX = { fullWidth ->
                                        if (forward) (fullWidth * 0.05f).toInt() else -(fullWidth * 0.05f).toInt()
                                    },
                                ) + fadeIn(tween(durationMillis = 180))
                            ) togetherWith (
                                slideOutHorizontally(
                                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                                    targetOffsetX = { fullWidth ->
                                        if (forward) -(fullWidth * 0.04f).toInt() else (fullWidth * 0.04f).toInt()
                                    },
                                ) + fadeOut(tween(durationMillis = 140))
                            )
                        },
                        label = "rootTabTransition",
                    ) { tab ->
                        tabStateHolder.SaveableStateProvider(tab.name) {
                            when (tab) {
                                PulseNavTab.EXPLORE -> {
                                    ExploreScreen(
                                        onOpenTopic = openTopic,
                                        onOpenSearch = {
                                            boardsFocusSearchTrigger += 1
                                            selectedTab = PulseNavTab.BOARDS
                                        },
                                        onOpenBoardsTab = {
                                            selectedTab = PulseNavTab.BOARDS
                                        },
                                        onOpenAuthorProfile = navigateToUser,
                                        onRequestWebViewAuth = {
                                            openWebViewAuth(SIGN_IN_URL, session == null)
                                        },
                                        externalCategorySlug = pendingExploreCategorySlug,
                                        scrollToTopTrigger = exploreScrollToTopTrigger,
                                        onScrollDirectionChanged = { isScrollingUp ->
                                            fabExpanded = isScrollingUp
                                        },
                                        bottomContentPadding = dockedBottomPadding,
                                    )
                                }

                                PulseNavTab.BOARDS -> {
                                    BoardsScreen(
                                        onOpenTopic = openTopic,
                                        autoFocusSearch = false,
                                        focusSearchTrigger = boardsFocusSearchTrigger,
                                        onSelectBoard = { slug ->
                                            selectBoardAndOpenExplore(slug)
                                        },
                                        onOpenUserProfile = navigateToUser,
                                        onRequestWebViewAuth = {
                                            openWebViewAuth(SIGN_IN_URL, session == null)
                                        },
                                        bottomContentPadding = dockedBottomPadding,
                                    )
                                }

                                PulseNavTab.PULSE_HUB -> {
                                    PulseHubScreen(
                                        onOpenTopic = openTopic,
                                        onOpenUserProfile = navigateToUser,
                                        onOpenCheckInBoard = {
                                            navController.navigate(CheckInBoardRoute) {
                                                launchSingleTop = true
                                            }
                                        },
                                        onOpenInbox = { initialTab ->
                                            navController.navigate(InboxRoute(initialTab = initialTab)) {
                                                launchSingleTop = true
                                            }
                                        },
                                        onOpenRadar = openRadar,
                                        onOpenSeekMail = { openSeekMail(0L, 0L) },
                                        onOpenSettings = {
                                            navController.navigate(SettingsRoute) {
                                                launchSingleTop = true
                                            }
                                        },
                                        onRequestWebViewAuth = {
                                            openWebViewAuth(SIGN_IN_URL, true)
                                        },
                                        contentBottomPadding = dockedBottomPadding,
                                    )
                                }
                            }
                        }
                    }

                    PulseBottomDockOverlay(
                        selectedTab = selectedTab,
                        onSelectTab = { targetTab ->
                            selectedTab = targetTab
                            if (targetTab == PulseNavTab.EXPLORE) {
                                fabExpanded = true
                            }
                        },
                        onReselectTab = { reselectedTab ->
                            when (reselectedTab) {
                                PulseNavTab.EXPLORE -> {
                                    exploreScrollToTopTrigger += 1
                                    fabExpanded = true
                                }
                                PulseNavTab.BOARDS -> {
                                    boardsFocusSearchTrigger += 1
                                }
                                PulseNavTab.PULSE_HUB -> {
                                    appScope.launch {
                                        container.sessionRepository.refreshSession()
                                        if (container.sessionRepository.sessionState.value != null) {
                                            container.checkInRepository.getCheckInBoard(
                                                page = 1,
                                                forceRefresh = true,
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        onFabClick = {
                            val defaultSlug = lastSelectedCategorySlug
                                .takeIf { it.isNotBlank() && it != "meaningless" && it != "sandbox" }
                                ?: "daily"
                            if (session != null) {
                                navController.navigate(
                                    ComposerRoute(
                                        editTopicId = null,
                                        initialCategorySlug = defaultSlug,
                                    )
                                )
                            } else {
                                pendingComposerSlugAfterAuth = defaultSlug
                                appScope.launch {
                                    rootSnackbarHostState.showSnackbar("请先登录 NodeSeek 账号后再发布新帖")
                                }
                                openWebViewAuth(SIGN_IN_URL, true)
                            }
                        },
                        showFab = selectedTab == PulseNavTab.EXPLORE || selectedTab == PulseNavTab.BOARDS,
                        fabExpanded = fabExpanded,
                        unreadNotificationCount = (if (session != null) unreadCount else 0) + radarUnreadCount,
                        hasPendingCheckIn = session != null &&
                            sharedCheckInStatus != null &&
                            !sharedCheckInStatus!!.hasCheckedInToday,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            // Defensive aliases in case any caller navigates to BoardsRoute / PulseHubRoute directly
            composable<BoardsRoute> {
                LaunchedEffect(Unit) {
                    selectedTab = PulseNavTab.BOARDS
                    navController.popBackStack(ExploreRoute, inclusive = false)
                }
            }

            composable<PulseHubRoute> {
                LaunchedEffect(Unit) {
                    selectedTab = PulseNavTab.PULSE_HUB
                    navController.popBackStack(ExploreRoute, inclusive = false)
                }
            }

            // Topic Detail Reader
            composable<TopicDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<TopicDetailRoute>()
                val refreshTrigger by backStackEntry.savedStateHandle
                    .getStateFlow(EDITED_TOPIC_REFRESH_KEY, 0L)
                    .collectAsStateWithLifecycle()
                TopicDetailScreen(
                    topicId = route.topicId,
                    onBack = safePopBackStack,
                    initialFloor = route.initialFloor,
                    titleHint = route.titleHint,
                    refreshTrigger = refreshTrigger,
                    onNavigateToUser = navigateToUser,
                    onNavigateToCategory = selectBoardAndOpenExplore,
                    onOpenTopic = openTopic,
                    onEditTopic = { topicId, categorySlug ->
                        navController.navigate(
                            ComposerRoute(
                                editTopicId = topicId,
                                initialCategorySlug = categorySlug.ifBlank { "daily" },
                            )
                        )
                    },
                    onRequestWebViewAuth = { url ->
                        openWebViewAuth(
                            url,
                            url.contains("signIn", ignoreCase = true),
                        )
                    },
                )
            }

            // Markdown Topic Composer (Create & Edit)
            composable<ComposerRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ComposerRoute>()
                TopicComposerScreen(
                    editTopicId = route.editTopicId,
                    initialCategorySlug = route.initialCategorySlug,
                    onBack = safePopBackStack,
                    onTopicPublished = { editedTopicId ->
                        if (editedTopicId != null) {
                            navController.previousBackStackEntry
                                ?.savedStateHandle
                                ?.set(EDITED_TOPIC_REFRESH_KEY, System.currentTimeMillis())
                        }
                        safePopBackStack()
                    },
                    onRequestWebViewAuth = { url ->
                        openWebViewAuth(
                            url,
                            url.contains("signIn", ignoreCase = true),
                        )
                    },
                )
            }

            // Unified Inbox (@Me, Replies, System, Direct Message Threads)
            composable<InboxRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<InboxRoute>()
                InboxScreen(
                    initialTab = route.initialTab,
                    onBack = safePopBackStack,
                    onOpenTopic = openTopic,
                    onOpenDirectMessage = { peerUid, peerName ->
                        navController.navigate(
                            DirectMessageRoute(
                                peerUid = peerUid,
                                peerName = peerName,
                            )
                        )
                    },
                    onOpenUserProfile = navigateToUser,
                    onOpenRadar = openRadar,
                    onOpenSeekMail = { openSeekMail(0L, 0L) },
                )
            }

            // 1-on-1 Direct Message Chat
            composable<DirectMessageRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<DirectMessageRoute>()
                DirectMessageScreen(
                    peerUid = route.peerUid,
                    peerName = route.peerName,
                    onBack = safePopBackStack,
                    onOpenUserProfile = navigateToUser,
                    onOpenTopic = openTopic,
                    onOpenCategory = selectBoardAndOpenExplore,
                )
            }

            // Member Space / User Profile
            composable<UserProfileRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<UserProfileRoute>()
                UserProfileScreen(
                    uid = route.uid,
                    usernameHint = route.usernameHint,
                    onBack = safePopBackStack,
                    onOpenTopic = openTopic,
                    onOpenDirectMessage = { peerUid, peerName ->
                        navController.navigate(
                            DirectMessageRoute(
                                peerUid = peerUid,
                                peerName = peerName,
                            )
                        )
                    },
                    onOpenUserProfile = navigateToUser,
                    onOpenCategory = selectBoardAndOpenExplore,
                    onRequestWebViewAuth = openWebViewAuth,
                )
            }

            // Daily Drumstick Check-In Leaderboard
            composable<CheckInBoardRoute> {
                CheckInBoardScreen(
                    onBack = safePopBackStack,
                    onOpenUserProfile = navigateToUser,
                )
            }

            // Preferences, Appearance, Blocklist, NodeImage & Cache Settings
            composable<SettingsRoute> {
                SettingsScreen(
                    onBack = safePopBackStack,
                    onOpenUserProfile = navigateToUser,
                    onOpenRadar = openRadar,
                    onOpenSeekMail = { openSeekMail(0L, 0L) },
                )
            }

            // Pulse Radar: Keyword Alerts, Reply Tracking & Local Alert Records
            composable<PulseRadarRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<PulseRadarRoute>()
                PulseRadarScreen(
                    initialTab = route.initialTab,
                    onBack = safePopBackStack,
                    onOpenTopic = openTopic,
                    container = container,
                )
            }

            // Seek Mail (seek.li OpenAPI Mailbox & OTP Client)
            composable<SeekMailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SeekMailRoute>()
                SeekMailScreen(
                    onBack = safePopBackStack,
                    onOpenSettings = {
                        navController.navigate(SettingsRoute) {
                            launchSingleTop = true
                        }
                    },
                    initialMailboxId = route.initialMailboxId,
                    initialMailId = route.initialMailId,
                    container = container,
                )
            }
        }

        // Non-intrusive global Cloudflare / security verification banner
        AnimatedVisibility(
            visible = challengeRequired && !challengeBannerDismissed && activeWebViewRequest == null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            ChallengeVerificationBanner(
                onVerifyClick = {
                    val activeTopicRoute = runCatching {
                        navController.currentBackStackEntry?.toRoute<TopicDetailRoute>()
                    }.getOrNull()
                    val activeUserRoute = runCatching {
                        navController.currentBackStackEntry?.toRoute<UserProfileRoute>()
                    }.getOrNull()
                    val targetUrl = when {
                        activeTopicRoute != null && activeTopicRoute.topicId > 0L ->
                            "https://www.nodeseek.com/post-${activeTopicRoute.topicId}-1"
                        activeUserRoute != null && activeUserRoute.uid > 0L ->
                            "https://www.nodeseek.com/space/${activeUserRoute.uid}"
                        else -> CHALLENGE_VERIFY_URL
                    }
                    openWebViewAuth(targetUrl, false)
                },
                onDismissClick = {
                    challengeBannerDismissed = true
                },
            )
        }

        // Floating top in-app message popup overlay for newly arrived replies, @mentions, and DMs
        InAppMessagePopupOverlay(
            state = inAppPopupState,
            paused = activeWebViewRequest != null,
            onPopupClick = { popup ->
                inAppPopupState.removeCurrentAndMatching(popup)
                when (val target = InboxTargetResolver.toTapTarget(popup)) {
                    is NotificationTapTarget.OpenTopic -> {
                        val nKind = target.notificationKind
                        val nId = target.notificationId
                        if (nKind != null && nId > 0L) {
                            appScope.launch {
                                container.inboxRepository.markNotificationsRead(
                                    kind = nKind,
                                    ids = listOf(nId),
                                )
                            }
                        }
                        openTopic(target.topicId, target.initialFloor, target.titleHint)
                    }

                    is NotificationTapTarget.OpenDirectMessage -> {
                        inAppPopupState.removeWhere {
                            it.kind == InAppMessageKind.DIRECT_MESSAGE && it.peerUid == target.peerUid
                        }
                        appScope.launch {
                            container.inboxRepository.markThreadRead(target.peerUid)
                        }
                        navController.navigate(
                            DirectMessageRoute(
                                peerUid = target.peerUid,
                                peerName = target.peerName,
                            )
                        )
                    }

                    NotificationTapTarget.OpenRadarAlerts, null -> Unit
                }
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(
                    start = 14.dp,
                    end = 14.dp,
                    top = if (challengeRequired && !challengeBannerDismissed && activeWebViewRequest == null) 84.dp else 10.dp,
                ),
        )

        PulseSnackbarHost(
            hostState = rootSnackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 94.dp),
            pillModifier = Modifier,
        )

        // Centralized first-party WebView authentication & challenge recovery sheet
        activeWebViewRequest?.let { request ->
            WebViewSessionSheet(
                initialUrl = request.url,
                requireSignedInUser = request.requireSignedInUser,
                autoDismissWhenVerified = request.autoDismissWhenVerified,
                userAgentOverride = synchronizedUserAgent,
                useSystemDefaultUserAgent = useSystemDefaultUserAgent,
                onDismiss = {
                    val hadPendingComposer = pendingComposerSlugAfterAuth != null
                    pendingComposerSlugAfterAuth = null
                    activeWebViewRequest = null
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.set(EDITED_TOPIC_REFRESH_KEY, System.currentTimeMillis())
                    appScope.launch {
                        val updatedSession = container.sessionRepository.onWebViewDismissed().getOrNull()
                        if (updatedSession != null) {
                            container.inboxRepository.refreshUnreadCount()
                        } else if (hadPendingComposer) {
                            // Keep clean state if user cancelled login
                        }
                    }
                },
                onSessionVerified = { status ->
                    val composerSlug = pendingComposerSlugAfterAuth
                    pendingComposerSlugAfterAuth = null
                    activeWebViewRequest = null
                    challengeBannerDismissed = false
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.set(EDITED_TOPIC_REFRESH_KEY, System.currentTimeMillis())
                    appScope.launch {
                        val refreshedSession = container.sessionRepository.onWebViewAuthCompleted().getOrNull()
                        container.forumRepository.refreshCategories(force = false)
                        if (refreshedSession != null || status.isAuthenticated) {
                            container.inboxRepository.refreshUnreadCount()
                            if (composerSlug != null) {
                                navController.navigate(
                                    ComposerRoute(
                                        editTopicId = null,
                                        initialCategorySlug = composerSlug,
                                    )
                                )
                            }
                        }
                    }
                },
            )
        }

        // In-app GitHub Release update prompt dialog
        if (activeWebViewRequest == null &&
            updateState.showUpdatePrompt &&
            updateState.latestRelease != null
        ) {
            AppUpdateDialog(
                state = updateState,
                onDismiss = container.appUpdateManager::dismissUpdatePrompt,
                onIgnoreVersion = container.appUpdateManager::ignoreRelease,
                onStartDownload = container.appUpdateManager::startDownloadAndInstall,
                onCancelDownload = container.appUpdateManager::cancelDownload,
                onInstallReadyApk = {
                    val launched = container.appUpdateManager.installReadyApk()
                    if (!launched) {
                        appScope.launch {
                            rootSnackbarHostState.showSnackbar("请先确认「安装未知应用」权限后重试")
                        }
                    }
                },
                onOpenUnknownSourcesSettings = {
                    val opened = container.appUpdateManager.openUnknownSourcesSettings()
                    if (!opened) {
                        appScope.launch {
                            rootSnackbarHostState.showSnackbar("无法打开系统安装权限设置，请在系统设置中手动授权")
                        }
                    }
                },
                onOpenBrowserUrl = { url ->
                    runCatching {
                        uriHandler.openUri(url)
                    }.onFailure {
                        appScope.launch {
                            rootSnackbarHostState.showSnackbar("无法打开浏览器，请访问 $url")
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ChallengeVerificationBanner(
    onVerifyClick: () -> Unit,
    onDismissClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val badgeShape = RoundedCornerShape(12.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.HeroBento,
                containerColor = ext.glassSurface.copy(alpha = if (isDark) 0.94f else 0.96f),
                elevation = PulseElevation.FloatingBar,
                accentColor = MaterialTheme.colorScheme.primary,
            )
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(badgeShape)
                .background(PulseBrushes.brandSoftWash(0.18f))
                .border(
                    width = 0.8.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                    shape = badgeShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "需要完成站点安全验证",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "在内置官方网页完成验证后即可自动恢复完整连接",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        FilledTonalButton(
            onClick = onVerifyClick,
            shape = CircleShape,
        ) {
            Text(
                text = "前往验证",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        IconButton(onClick = onDismissClick) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "关闭提示",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
