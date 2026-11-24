package com.nodeseek.app.navigation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
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
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
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
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.nodeseek.app.data.ssh.JSchSshTransport
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.di.DefaultAppContainer
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.fleet.RestockAssetPrefill
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.NotificationKind
import com.nodeseek.app.domain.model.ThemeMode
import com.nodeseek.app.domain.radar.InAppMessageKind
import com.nodeseek.app.domain.radar.InboxTargetResolver
import com.nodeseek.app.domain.radar.NotificationTapTarget
import com.nodeseek.app.domain.radar.RadarPollingSettings
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.ui.adaptive.AdaptivePostureTransition
import com.nodeseek.app.ui.adaptive.DualPaneDestinationKind
import com.nodeseek.app.ui.adaptive.LocalPulseAdaptiveLayout
import com.nodeseek.app.ui.adaptive.PulseAdaptiveLayoutInfo
import com.nodeseek.app.ui.adaptive.PulseDualPaneScaffold
import com.nodeseek.app.ui.adaptive.PulseNavigationRail
import com.nodeseek.app.ui.adaptive.SelectedPaneTopic
import com.nodeseek.app.ui.adaptive.calculatePulseAdaptiveLayoutInfo
import com.nodeseek.app.ui.adaptive.isDualPaneListHost
import com.nodeseek.app.ui.adaptive.resolveAdaptivePostureTransition
import com.nodeseek.app.ui.boards.BoardsScreen
import com.nodeseek.app.ui.components.InAppMessagePopupOverlay
import com.nodeseek.app.ui.components.PulseBottomDockOverlay
import com.nodeseek.app.ui.components.PulseNavTab
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.WebViewSessionSheet
import com.nodeseek.app.ui.components.rememberInAppMessagePopupState
import com.nodeseek.app.ui.activity.ActivityScreen
import com.nodeseek.app.ui.activity.openActivityAppNotificationSettings
import com.nodeseek.app.ui.activity.rememberActivityNotificationBinding
import com.nodeseek.app.ui.composer.TopicComposerScreen
import com.nodeseek.app.ui.explore.ExploreScreen
import com.nodeseek.app.ui.fleet.MyServersScreen
import com.nodeseek.app.ui.fleet.MyServersTab
import com.nodeseek.app.ui.hub.CheckInBoardScreen
import com.nodeseek.app.ui.hub.DirectMessageScreen
import com.nodeseek.app.ui.hub.FeatureHubScreen
import com.nodeseek.app.ui.hub.HubCollectionScreen
import com.nodeseek.app.ui.hub.InboxScreen
import com.nodeseek.app.ui.hub.PulseHubScreen
import com.nodeseek.app.ui.hub.SettingsScreen
import com.nodeseek.app.ui.hub.UserProfileScreen
import com.nodeseek.app.ui.mail.SeekMailScreen
import com.nodeseek.app.ui.netbench.MobileNetBenchScreen
import com.nodeseek.app.ui.overseas.OverseasIntelDetailScreen
import com.nodeseek.app.ui.overseas.OverseasIntelScreen
import com.nodeseek.app.ui.overseas.openOverseasAppNotificationSettings
import com.nodeseek.app.ui.overseas.rememberOverseasPromoNotificationBinding
import com.nodeseek.app.ui.radar.PulseRadarScreen
import com.nodeseek.app.ui.restock.RestockMonitorScreen
import com.nodeseek.app.ui.ssh.LITE_SSH_REVIEW_CATEGORY_SLUG
import com.nodeseek.app.ui.ssh.LiteSshScreen
import com.nodeseek.app.ui.ssh.LiteSshViewModel
import com.nodeseek.app.ui.theme.NodePulseTheme
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseThemeMode
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.topic.TopicDetailScreen
import com.nodeseek.app.ui.trust.LocalSellerTrustRepository
import com.nodeseek.app.ui.update.AppUpdateDialog
import kotlinx.coroutines.Job
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
    windowSizeClass: WindowSizeClass? = null,
    deepLinkUriFlow: StateFlow<Uri?>? = null,
    onDeepLinkConsumed: () -> Unit = {},
    notificationTapFlow: Flow<NotificationTapTarget>? = null,
    shortcutDestinationFlow: Flow<AppDestination>? = null,
    onDarkThemeResolved: (Boolean) -> Unit = {},
) {
    CompositionLocalProvider(
        LocalAppContainer provides container,
        LocalSellerTrustRepository provides container.sellerTrustRepository,
    ) {
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

        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val adaptiveLayoutInfo = remember(windowSizeClass, maxWidth, maxHeight) {
                calculatePulseAdaptiveLayoutInfo(
                    windowWidthDp = maxWidth,
                    windowHeightDp = maxHeight,
                    explicitWindowSizeClass = windowSizeClass,
                )
            }
            CompositionLocalProvider(LocalPulseAdaptiveLayout provides adaptiveLayoutInfo) {
                NodePulseTheme(
                    themeMode = pulseThemeMode,
                    purePitchAmoled = settings.purePitchAmoled,
                    dynamicColor = settings.useDynamicColor,
                    fontScale = settings.fontScale,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        NodePulseNavHost(
                            container = container,
                            deepLinkUriFlow = deepLinkUriFlow,
                            onDeepLinkConsumed = onDeepLinkConsumed,
                            notificationTapFlow = notificationTapFlow,
                            shortcutDestinationFlow = shortcutDestinationFlow,
                        )
                    }
                }
            }
        }
    }
}

/**
 * End-to-end Compose Navigation host connecting the 3-tab Floating Pill Dock shell
 * (`ExploreScreen`, `FeatureHubScreen`, `PulseHubScreen`) with all child routes and a
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
    shortcutDestinationFlow: Flow<AppDestination>? = null,
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
    val radarPollingSettings by container.radarRepository.pollingSettingsFlow.collectAsStateWithLifecycle(
        initialValue = RadarPollingSettings(),
    )
    val sharedCheckInStatus by container.checkInRepository.checkInStatus.collectAsStateWithLifecycle()
    val updateState by container.appUpdateManager.state.collectAsStateWithLifecycle()

    // Top-level 3-tab shell state
    val adaptiveLayout = LocalPulseAdaptiveLayout.current
    var selectedTab by rememberSaveable { mutableStateOf(PulseNavTab.EXPLORE) }
    var fabExpanded by rememberSaveable { mutableStateOf(true) }
    var exploreScrollToTopTrigger by rememberSaveable { mutableIntStateOf(0) }
    var exploreRefreshOnScrollToTop by rememberSaveable { mutableStateOf(false) }
    var featureHubScrollToTopTrigger by rememberSaveable { mutableIntStateOf(0) }
    var pendingExploreCategorySlug by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExploreSort by rememberSaveable { mutableStateOf<FeedSort?>(null) }
    var lastSelectedCategorySlug by rememberSaveable { mutableStateOf("") }

    // Dual-pane master-detail & fold/unfold continuity state
    var selectedPaneTopic by rememberSaveable(stateSaver = SelectedPaneTopic.NullableSaver) {
        mutableStateOf(null)
    }
    var isDetailPaneMaximized by rememberSaveable { mutableStateOf(false) }
    var lastKnownDualPane by rememberSaveable { mutableStateOf(adaptiveLayout.isDualPane) }
    val latestIsDualPane by rememberUpdatedState(adaptiveLayout.isDualPane)
    val latestSelectedTab by rememberUpdatedState(selectedTab)

    // Centralized WebView session / challenge sheet state
    var activeWebViewRequest by remember { mutableStateOf<WebViewAuthRequest?>(null) }
    var pendingComposerSlugAfterAuth by rememberSaveable { mutableStateOf<String?>(null) }
    var challengeBannerDismissed by rememberSaveable { mutableStateOf(false) }
    var userResolveJob by remember { mutableStateOf<Job?>(null) }

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
    val isOnLiteSshRoute = remember(currentBackStackEntry) {
        currentBackStackEntry?.destination?.hasRoute<LiteSshRoute>() == true
    }
    val latestIsOnLiteSshRoute by rememberUpdatedState(isOnLiteSshRoute)

    LaunchedEffect(container) {
        container.sshSessionManager.attachFleetIntegration(
            fleetRepository = container.fleetRepository,
        )
        container.sshSessionManager.completionPopupEvents.collect { popup ->
            if (!latestIsOnLiteSshRoute) {
                inAppPopupState.enqueue(popup)
            }
        }
    }

    var hasPromptedNotificationThisSession by rememberSaveable { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        container.notificationDispatcher.markPostNotificationsRequestedOnce()
        if (!granted) {
            appScope.launch {
                val result = rootSnackbarHostState.showSnackbar(
                    message = "未开启系统通知权限，退至后台时手机无法收到回复与私信通知",
                    actionLabel = "去开启",
                )
                if (result == SnackbarResult.ActionPerformed) {
                    openOverseasAppNotificationSettings(context)
                }
            }
        }
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
            delay(600L)
            container.checkInRepository.getCheckInBoard(page = 1, forceRefresh = false)
        }
    }

    // Prompt Android 13+ POST_NOTIFICATIONS once WebView auth sheet is closed and Activity is resumed,
    // or surface a one-tap Settings action if system notifications were previously disabled.
    LaunchedEffect(
        session?.uid,
        activeWebViewRequest == null,
        radarPollingSettings.isEnabled,
        radarPollingSettings.inboxPushEnabled,
    ) {
        val currentUid = session?.uid ?: 0L
        if (currentUid <= 0L ||
            activeWebViewRequest != null ||
            hasPromptedNotificationThisSession ||
            !radarPollingSettings.isEnabled ||
            !radarPollingSettings.inboxPushEnabled
        ) {
            return@LaunchedEffect
        }
        delay(350L)
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return@LaunchedEffect
        }
        if (container.notificationDispatcher.hasPostNotificationsPermission()) {
            return@LaunchedEffect
        }

        hasPromptedNotificationThisSession = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val shouldShowRationale = context.findHostActivity()?.let { hostActivity ->
                ActivityCompat.shouldShowRequestPermissionRationale(
                    hostActivity,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            } == true
            if (!container.notificationDispatcher.hasRequestedPostNotificationsOnce() || shouldShowRationale) {
                runCatching {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                return@LaunchedEffect
            }
        }

        val result = rootSnackbarHostState.showSnackbar(
            message = "系统通知权限未开启，后台无法收到新回复与私信通知",
            actionLabel = "去开启",
        )
        if (result == SnackbarResult.ActionPerformed) {
            openOverseasAppNotificationSettings(context)
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

    // Foreground lightweight unread polling, piggyback wake, and in-app popup collection.
    // Scoped to RESUMED so pressing Home / locking screen immediately unsubscribes popups and
    // never consumes unread watermarks during onPause -> onStop background transitions.
    LaunchedEffect(lifecycleOwner, container, session?.uid) {
        val currentUid = session?.uid ?: 0L
        if (currentUid <= 0L) return@LaunchedEffect

        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
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

    // Reset detail maximize flag whenever switching away from Explore tab
    LaunchedEffect(selectedTab) {
        if (selectedTab != PulseNavTab.EXPLORE && isDetailPaneMaximized) {
            isDetailPaneMaximized = false
        }
    }

    // Seamless fold / unfold continuity between single-pane TopicDetailRoute and dual-pane right pane
    LaunchedEffect(adaptiveLayout.isDualPane) {
        val previousDualPane = lastKnownDualPane
        val currentDualPane = adaptiveLayout.isDualPane
        if (previousDualPane == currentDualPane) return@LaunchedEffect
        lastKnownDualPane = currentDualPane

        val currentEntry = navController.currentBackStackEntry
        val currentKind = resolveDestinationKind(
            entry = currentEntry,
            selectedTab = latestSelectedTab,
        )
        val previousKind = resolveDestinationKind(
            entry = navController.previousBackStackEntry,
            selectedTab = latestSelectedTab,
        )
        val currentTopicArgs = if (currentKind == DualPaneDestinationKind.TOPIC_DETAIL) {
            runCatching { currentEntry?.toRoute<TopicDetailRoute>() }.getOrNull()?.let { route ->
                SelectedPaneTopic(
                    topicId = route.topicId,
                    initialFloor = route.initialFloor,
                    titleHint = route.titleHint,
                )
            }
        } else {
            null
        }

        when (
            val transition = resolveAdaptivePostureTransition(
                wasDualPane = previousDualPane,
                isDualPane = currentDualPane,
                currentDestination = currentKind,
                previousDestination = previousKind,
                selectedPaneTopic = selectedPaneTopic,
                currentTopicDetailArgs = currentTopicArgs,
            )
        ) {
            AdaptivePostureTransition.None -> Unit
            AdaptivePostureTransition.ClearPaneState -> {
                selectedPaneTopic = null
                isDetailPaneMaximized = false
            }

            is AdaptivePostureTransition.PushTopicDetailAndClearPane -> {
                selectedPaneTopic = null
                isDetailPaneMaximized = false
                navController.navigate(
                    TopicDetailRoute(
                        topicId = transition.topic.topicId,
                        initialFloor = transition.topic.initialFloor,
                        titleHint = transition.topic.titleHint,
                    ),
                ) {
                    launchSingleTop = true
                }
            }

            is AdaptivePostureTransition.PopTopicDetailToDualPane -> {
                selectedPaneTopic = transition.topic
                isDetailPaneMaximized = false
                if (transition.switchToExploreTab) {
                    selectedTab = PulseNavTab.EXPLORE
                }
                navController.popBackStack()
            }
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
                val sanitizedFloor = initialFloor.coerceAtLeast(0)
                val currentKind = resolveDestinationKind(
                    entry = navController.currentBackStackEntry,
                    selectedTab = latestSelectedTab,
                )
                if (latestIsDualPane && currentKind.isDualPaneListHost()) {
                    selectedPaneTopic = SelectedPaneTopic(
                        topicId = topicId,
                        initialFloor = sanitizedFloor,
                        titleHint = titleHint,
                    )
                } else {
                    val topTopic = navController.currentBackStackEntry?.let { entry ->
                        if (entry.destination.hasRoute<TopicDetailRoute>()) {
                            runCatching { entry.toRoute<TopicDetailRoute>() }.getOrNull()
                        } else {
                            null
                        }
                    }
                    if (topTopic == null ||
                        topTopic.topicId != topicId ||
                        topTopic.initialFloor != sanitizedFloor
                    ) {
                        navController.navigate(
                            TopicDetailRoute(
                                topicId = topicId,
                                initialFloor = sanitizedFloor,
                                titleHint = titleHint,
                            ),
                        )
                    }
                }
            }
        }
    }

    val openTopicFromExternal: (topicId: Long, initialFloor: Int, titleHint: String) -> Unit = remember(navController) {
        { topicId, initialFloor, titleHint ->
            if (topicId > 0L) {
                val sanitizedFloor = initialFloor.coerceAtLeast(0)
                val currentKind = resolveDestinationKind(
                    entry = navController.currentBackStackEntry,
                    selectedTab = latestSelectedTab,
                )
                when {
                    latestIsDualPane && currentKind.isDualPaneListHost() -> {
                        isDetailPaneMaximized = false
                        selectedPaneTopic = SelectedPaneTopic(
                            topicId = topicId,
                            initialFloor = sanitizedFloor,
                            titleHint = titleHint,
                        )
                    }

                    latestIsDualPane &&
                        currentKind == DualPaneDestinationKind.EXPLORE_OTHER_TAB -> {
                        selectedTab = PulseNavTab.EXPLORE
                        isDetailPaneMaximized = false
                        selectedPaneTopic = SelectedPaneTopic(
                            topicId = topicId,
                            initialFloor = sanitizedFloor,
                            titleHint = titleHint,
                        )
                    }

                    else -> {
                        val topTopic = navController.currentBackStackEntry?.let { entry ->
                            if (entry.destination.hasRoute<TopicDetailRoute>()) {
                                runCatching { entry.toRoute<TopicDetailRoute>() }.getOrNull()
                            } else {
                                null
                            }
                        }
                        if (topTopic == null ||
                            topTopic.topicId != topicId ||
                            topTopic.initialFloor != sanitizedFloor
                        ) {
                            navController.navigate(
                                TopicDetailRoute(
                                    topicId = topicId,
                                    initialFloor = sanitizedFloor,
                                    titleHint = titleHint,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    val editTopic: (topicId: Long, categorySlug: String) -> Unit = remember(navController) {
        { topicId, categorySlug ->
            navController.navigate(
                ComposerRoute(
                    editTopicId = topicId,
                    initialCategorySlug = categorySlug.ifBlank { "daily" },
                ),
            )
        }
    }

    val requestTopicWebViewAuth: (url: String) -> Unit = remember(openWebViewAuth) {
        { url ->
            openWebViewAuth(
                url,
                url.contains("signIn", ignoreCase = true),
            )
        }
    }

    val closeDualPaneTopic: () -> Unit = remember {
        {
            selectedPaneTopic = null
            isDetailPaneMaximized = false
        }
    }

    val toggleDualPaneMaximize: () -> Unit = remember {
        {
            if (selectedPaneTopic != null) {
                isDetailPaneMaximized = !isDetailPaneMaximized
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

    val openDirectMessage: (peerUid: Long, peerName: String) -> Unit = remember(navController) {
        { peerUid, peerName ->
            isDetailPaneMaximized = false
            navController.navigate(
                DirectMessageRoute(
                    peerUid = peerUid,
                    peerName = peerName,
                ),
            ) {
                launchSingleTop = true
            }
        }
    }

    val openRestockMonitor: (initialUrl: String, initialTitle: String, initialTab: Int) -> Unit =
        remember(navController) {
            { initialUrl, initialTitle, initialTab ->
                isDetailPaneMaximized = false
                navController.navigate(
                    RestockMonitorRoute(
                        initialUrl = initialUrl,
                        initialTitle = initialTitle,
                        initialTab = initialTab.coerceIn(0, 1),
                    ),
                ) {
                    launchSingleTop = true
                }
            }
        }

    val openActivity: () -> Unit = remember(navController) {
        {
            isDetailPaneMaximized = false
            navController.navigate(ActivityRoute) {
                launchSingleTop = true
            }
        }
    }

    val openOverseasIntel: () -> Unit = remember(navController) {
        {
            isDetailPaneMaximized = false
            navController.navigate(OverseasIntelRoute) {
                launchSingleTop = true
            }
        }
    }

    val openMyServers: (initialTab: Int, importName: String, importPrice: String, importUrl: String) -> Unit =
        remember(navController) {
            { initialTab, importName, importPrice, importUrl ->
                isDetailPaneMaximized = false
                navController.navigate(
                    MyServersRoute(
                        initialTab = initialTab.coerceIn(0, 1),
                        importName = importName,
                        importPrice = importPrice,
                        importUrl = importUrl,
                    ),
                ) {
                    launchSingleTop = true
                }
            }
        }

    val openMobileNetBench: (initialTarget: String) -> Unit = remember(navController) {
        { initialTarget ->
            isDetailPaneMaximized = false
            navController.navigate(
                MobileNetBenchRoute(initialTarget = initialTarget),
            ) {
                launchSingleTop = true
            }
        }
    }

    val openLiteSsh: () -> Unit = remember(navController) {
        {
            isDetailPaneMaximized = false
            navController.navigate(LiteSshRoute) {
                launchSingleTop = true
            }
        }
    }

    val openComposerWithPrefill: (categorySlug: String, title: String, markdown: String) -> Unit =
        remember(navController) {
            { categorySlug, title, markdown ->
                isDetailPaneMaximized = false
                val prefillToken = if (title.isNotBlank() || markdown.isNotBlank()) {
                    ComposerPrefillStore.put(title = title, markdown = markdown)
                } else {
                    ""
                }
                navController.navigate(
                    ComposerRoute(
                        editTopicId = null,
                        initialCategorySlug = categorySlug.ifBlank { "daily" },
                        prefillId = prefillToken,
                    ),
                )
            }
        }

    LaunchedEffect(shortcutDestinationFlow, navController) {
        shortcutDestinationFlow?.collect { destination ->
            isDetailPaneMaximized = false
            if (destination is TopicDetailRoute) {
                openTopicFromExternal(destination.topicId, destination.initialFloor, destination.titleHint)
                return@collect
            }
            if (destination is OverseasIntelDetailRoute &&
                navController.currentBackStackEntry?.destination?.hasRoute<OverseasIntelRoute>() != true
            ) {
                navController.navigate(OverseasIntelRoute) {
                    launchSingleTop = true
                }
            }
            navController.navigate(destination) {
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
                    openTopicFromExternal(target.topicId, target.initialFloor, target.titleHint)
                }

                is NotificationTapTarget.OpenDirectMessage -> {
                    isDetailPaneMaximized = false
                    appScope.launch {
                        container.inboxRepository.markThreadRead(target.peerUid)
                    }
                    navController.navigate(
                        DirectMessageRoute(
                            peerUid = target.peerUid,
                            peerName = target.peerName,
                        )
                    ) {
                        launchSingleTop = true
                    }
                }

                NotificationTapTarget.OpenRadarAlerts -> {
                    isDetailPaneMaximized = false
                    openRadar(0)
                }

                NotificationTapTarget.OpenLiteSsh -> {
                    isDetailPaneMaximized = false
                    openLiteSsh()
                }
            }
        }
    }

    val publishSshToComposer: (suggestedTitle: String, markdownContent: String) -> Unit =
        remember(openComposerWithPrefill) {
            { suggestedTitle, markdownContent ->
                openComposerWithPrefill(
                    LITE_SSH_REVIEW_CATEGORY_SLUG,
                    suggestedTitle,
                    markdownContent,
                )
            }
        }

    val navigateToUser: (uid: Long, usernameHint: String) -> Unit = remember(navController, container) {
        { uid, usernameHint ->
            if (uid > 0L) {
                userResolveJob?.cancel()
                navController.navigate(
                    UserProfileRoute(
                        uid = uid,
                        usernameHint = usernameHint,
                    )
                )
            } else {
                val cleanName = usernameHint.removePrefix("@").trim()
                if (cleanName.isNotEmpty()) {
                    val originEntryId = navController.currentBackStackEntry?.id
                    userResolveJob?.cancel()
                    userResolveJob = appScope.launch {
                        val searchResult = container.userRepository.searchUsers(cleanName)
                        val matches = searchResult.getOrDefault(emptyList())
                        val resolved = matches.firstOrNull { it.username.equals(cleanName, ignoreCase = true) }
                        if (resolved != null && resolved.uid > 0L) {
                            if (originEntryId == null || navController.currentBackStackEntry?.id == originEntryId) {
                                navController.navigate(
                                    UserProfileRoute(
                                        uid = resolved.uid,
                                        usernameHint = resolved.username,
                                    )
                                )
                            }
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
            isDetailPaneMaximized = false
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
                openTopicFromExternal(parsed.topicId, parsed.initialFloor, "")
            }
            is NodePulseDeepLink.CategoryBoard -> {
                selectBoardAndOpenExplore(parsed.slug)
            }
            NodePulseDeepLink.CuratedFeed -> {
                pendingExploreSort = FeedSort.CURATED
                selectBoardAndOpenExplore("")
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
    val navBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val dockedBottomPadding = 92.dp + navBottomInset
    val exploreBottomPadding = if (adaptiveLayout.useNavigationRail) navBottomInset + 8.dp else dockedBottomPadding
    val featureHubBottomPadding = if (adaptiveLayout.useNavigationRail) 0.dp else dockedBottomPadding
    val pulseHubBottomPadding = if (adaptiveLayout.useNavigationRail) 0.dp else dockedBottomPadding
    val searchBottomPadding = 24.dp + navBottomInset

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
            // Root 3-tab container preserving scroll & ViewModel state across Explore / Feature Hub / Pulse Hub
            composable<ExploreRoute> { backStackEntry ->
                val refreshTrigger by backStackEntry.savedStateHandle
                    .getStateFlow(EDITED_TOPIC_REFRESH_KEY, 0L)
                    .collectAsStateWithLifecycle()

                // Placed before tab content so child BackHandlers (e.g. dual-pane detail) run first
                BackHandler(enabled = selectedTab != PulseNavTab.EXPLORE) {
                    selectedTab = PulseNavTab.EXPLORE
                    fabExpanded = true
                }

                val onTabSelected: (PulseNavTab) -> Unit = { targetTab ->
                    selectedTab = targetTab
                    if (targetTab == PulseNavTab.EXPLORE) {
                        fabExpanded = true
                    } else {
                        isDetailPaneMaximized = false
                    }
                }
                val onTabReselected: (PulseNavTab) -> Unit = { reselectedTab ->
                    when (reselectedTab) {
                        PulseNavTab.EXPLORE -> {
                            exploreRefreshOnScrollToTop = true
                            exploreScrollToTopTrigger += 1
                            fabExpanded = true
                        }
                        PulseNavTab.FEATURE_HUB -> {
                            featureHubScrollToTopTrigger += 1
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
                }
                val onComposeFabClick: () -> Unit = {
                    val defaultSlug = lastSelectedCategorySlug
                        .takeIf { it.isNotBlank() && it != "meaningless" && it != "sandbox" }
                        ?: "daily"
                    if (session != null) {
                        navController.navigate(
                            ComposerRoute(
                                editTopicId = null,
                                initialCategorySlug = defaultSlug,
                            ),
                        )
                    } else {
                        pendingComposerSlugAfterAuth = defaultSlug
                        appScope.launch {
                            rootSnackbarHostState.showSnackbar("请先登录 NodeSeek 账号后再发布新帖")
                        }
                        openWebViewAuth(SIGN_IN_URL, true)
                    }
                }
                val hubUnreadCount = (if (session != null) unreadCount else 0) + radarUnreadCount
                val hasPendingCheckIn = session != null &&
                    sharedCheckInStatus != null &&
                    !sharedCheckInStatus!!.hasCheckedInToday

                val movableTabContent = remember {
                    movableContentOf { render: @Composable () -> Unit ->
                        render()
                    }
                }
                val renderTabContent: @Composable () -> Unit = {
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
                                        AdaptiveMasterDetailContainer(
                                            adaptiveLayout = adaptiveLayout,
                                            selectedPaneTopic = selectedPaneTopic,
                                            isDetailPaneMaximized = isDetailPaneMaximized,
                                            allowBackToCloseDetail = true,
                                            refreshTrigger = refreshTrigger,
                                            onCloseDetailPane = closeDualPaneTopic,
                                            onToggleMaximizePane = toggleDualPaneMaximize,
                                            onNavigateToUser = navigateToUser,
                                            onNavigateToCategory = selectBoardAndOpenExplore,
                                            onOpenTopic = openTopic,
                                            onEditTopic = editTopic,
                                            onRequestWebViewAuth = requestTopicWebViewAuth,
                                            onStartDirectMessage = openDirectMessage,
                                            onOpenFullNetBench = openMobileNetBench,
                                            onOpenFullLiteSsh = openLiteSsh,
                                            onPublishSshToComposer = publishSshToComposer,
                                        ) {
                                            ExploreScreen(
                                                onOpenTopic = openTopic,
                                                onOpenSearch = {
                                                    navController.navigate(SearchRoute) {
                                                        launchSingleTop = true
                                                    }
                                                },
                                                onOpenBoardsTab = {
                                                    navController.navigate(BoardsRoute) {
                                                        launchSingleTop = true
                                                    }
                                                },
                                                onOpenAuthorProfile = navigateToUser,
                                                onRequestWebViewAuth = {
                                                    openWebViewAuth(SIGN_IN_URL, session == null)
                                                },
                                                externalCategorySlug = pendingExploreCategorySlug,
                                                onExternalCategoryConsumed = {
                                                    pendingExploreCategorySlug = null
                                                },
                                                externalSort = pendingExploreSort,
                                                onExternalSortConsumed = {
                                                    pendingExploreSort = null
                                                },
                                                onSelectedCategoryChanged = { slug ->
                                                    lastSelectedCategorySlug = slug
                                                },
                                                refreshTrigger = refreshTrigger,
                                                scrollToTopTrigger = exploreScrollToTopTrigger,
                                                onScrollToTopConsumed = {
                                                    exploreScrollToTopTrigger = 0
                                                    exploreRefreshOnScrollToTop = false
                                                },
                                                refreshOnScrollToTop = exploreRefreshOnScrollToTop,
                                                onScrollDirectionChanged = { isScrollingUp ->
                                                    fabExpanded = isScrollingUp
                                                },
                                                bottomContentPadding = exploreBottomPadding,
                                                selectedTopicId = if (adaptiveLayout.isDualPane) {
                                                    selectedPaneTopic?.topicId
                                                } else {
                                                    null
                                                },
                                            )
                                        }
                                    }

                                    PulseNavTab.FEATURE_HUB -> {
                                        FeatureHubScreen(
                                            onOpenSeekMail = { openSeekMail(0L, 0L) },
                                            onOpenActivity = openActivity,
                                            onOpenOverseasIntel = openOverseasIntel,
                                            onOpenRestockMonitor = { openRestockMonitor("", "", 0) },
                                            onOpenMyServers = { openMyServers(0, "", "", "") },
                                            onOpenNetBench = { openMobileNetBench("") },
                                            onOpenLiteSsh = openLiteSsh,
                                            contentBottomPadding = featureHubBottomPadding,
                                            scrollToTopTrigger = featureHubScrollToTopTrigger,
                                            onScrollToTopConsumed = { featureHubScrollToTopTrigger = 0 },
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
                                            onOpenBookmarks = {
                                                navController.navigate(HubCollectionRoute(initialTab = 0)) {
                                                    launchSingleTop = true
                                                }
                                            },
                                            onOpenReadHistory = {
                                                navController.navigate(HubCollectionRoute(initialTab = 1)) {
                                                    launchSingleTop = true
                                                }
                                            },
                                            onOpenSettings = {
                                                navController.navigate(SettingsRoute) {
                                                    launchSingleTop = true
                                                }
                                            },
                                            onRequestWebViewAuth = {
                                                openWebViewAuth(SIGN_IN_URL, true)
                                            },
                                            contentBottomPadding = pulseHubBottomPadding,
                                        )
                                    }
                                }
                            }
                        }
                    }

                if (adaptiveLayout.useNavigationRail) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        PulseNavigationRail(
                            selectedTab = selectedTab,
                            onSelectTab = onTabSelected,
                            onFabClick = onComposeFabClick,
                            showFab = selectedTab == PulseNavTab.EXPLORE,
                            unreadNotificationCount = hubUnreadCount,
                            hasPendingCheckIn = hasPendingCheckIn,
                            onReselectTab = onTabReselected,
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .consumeWindowInsets(
                                    WindowInsets.systemBars
                                        .union(WindowInsets.displayCutout)
                                        .only(WindowInsetsSides.Start),
                                ),
                        ) {
                            movableTabContent(renderTabContent)
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        movableTabContent(renderTabContent)

                        PulseBottomDockOverlay(
                            selectedTab = selectedTab,
                            onSelectTab = onTabSelected,
                            onReselectTab = onTabReselected,
                            onFabClick = onComposeFabClick,
                            showFab = selectedTab == PulseNavTab.EXPLORE,
                            fabExpanded = fabExpanded,
                            unreadNotificationCount = hubUnreadCount,
                            hasPendingCheckIn = hasPendingCheckIn,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }

            // Bento Board Directory & Pin Management
            composable<BoardsRoute> { backStackEntry ->
                val refreshTrigger by backStackEntry.savedStateHandle
                    .getStateFlow(EDITED_TOPIC_REFRESH_KEY, 0L)
                    .collectAsStateWithLifecycle()
                AdaptiveMasterDetailContainer(
                    adaptiveLayout = adaptiveLayout,
                    selectedPaneTopic = selectedPaneTopic,
                    isDetailPaneMaximized = isDetailPaneMaximized,
                    allowBackToCloseDetail = false,
                    refreshTrigger = refreshTrigger,
                    onCloseDetailPane = closeDualPaneTopic,
                    onToggleMaximizePane = toggleDualPaneMaximize,
                    onNavigateToUser = navigateToUser,
                    onNavigateToCategory = selectBoardAndOpenExplore,
                    onOpenTopic = openTopic,
                    onEditTopic = editTopic,
                    onRequestWebViewAuth = requestTopicWebViewAuth,
                    onStartDirectMessage = openDirectMessage,
                    onOpenFullNetBench = openMobileNetBench,
                    onOpenFullLiteSsh = openLiteSsh,
                    onPublishSshToComposer = publishSshToComposer,
                ) {
                    BoardsScreen(
                        onOpenTopic = openTopic,
                        autoFocusSearch = false,
                        standaloneSearchMode = false,
                        onBack = safePopBackStack,
                        onSelectBoard = selectBoardAndOpenExplore,
                        onOpenUserProfile = navigateToUser,
                        onRequestWebViewAuth = {
                            openWebViewAuth(SIGN_IN_URL, session == null)
                        },
                        bottomContentPadding = searchBottomPadding,
                        selectedTopicId = if (adaptiveLayout.isDualPane) {
                            selectedPaneTopic?.topicId
                        } else {
                            null
                        },
                    )
                }
            }

            composable<PulseHubRoute> {
                LaunchedEffect(Unit) {
                    selectedTab = PulseNavTab.PULSE_HUB
                    navController.popBackStack(ExploreRoute, inclusive = false)
                }
            }

            // Standalone Unified Search (posts, users & boards)
            composable<SearchRoute> { backStackEntry ->
                var shouldAutoFocusSearch by rememberSaveable { mutableStateOf(true) }
                val refreshTrigger by backStackEntry.savedStateHandle
                    .getStateFlow(EDITED_TOPIC_REFRESH_KEY, 0L)
                    .collectAsStateWithLifecycle()
                AdaptiveMasterDetailContainer(
                    adaptiveLayout = adaptiveLayout,
                    selectedPaneTopic = selectedPaneTopic,
                    isDetailPaneMaximized = isDetailPaneMaximized,
                    allowBackToCloseDetail = false,
                    refreshTrigger = refreshTrigger,
                    onCloseDetailPane = closeDualPaneTopic,
                    onToggleMaximizePane = toggleDualPaneMaximize,
                    onNavigateToUser = navigateToUser,
                    onNavigateToCategory = selectBoardAndOpenExplore,
                    onOpenTopic = openTopic,
                    onEditTopic = editTopic,
                    onRequestWebViewAuth = requestTopicWebViewAuth,
                    onStartDirectMessage = openDirectMessage,
                    onOpenFullNetBench = openMobileNetBench,
                    onOpenFullLiteSsh = openLiteSsh,
                    onPublishSshToComposer = publishSshToComposer,
                ) {
                    BoardsScreen(
                        onOpenTopic = openTopic,
                        autoFocusSearch = shouldAutoFocusSearch,
                        onAutoFocusConsumed = { shouldAutoFocusSearch = false },
                        standaloneSearchMode = true,
                        onBack = safePopBackStack,
                        onSelectBoard = selectBoardAndOpenExplore,
                        onOpenUserProfile = navigateToUser,
                        onRequestWebViewAuth = {
                            openWebViewAuth(SIGN_IN_URL, session == null)
                        },
                        bottomContentPadding = searchBottomPadding,
                        selectedTopicId = if (adaptiveLayout.isDualPane) {
                            selectedPaneTopic?.topicId
                        } else {
                            null
                        },
                    )
                }
            }

            // Bookmarks & Read History Collection
            composable<HubCollectionRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<HubCollectionRoute>()
                val refreshTrigger by backStackEntry.savedStateHandle
                    .getStateFlow(EDITED_TOPIC_REFRESH_KEY, 0L)
                    .collectAsStateWithLifecycle()
                AdaptiveMasterDetailContainer(
                    adaptiveLayout = adaptiveLayout,
                    selectedPaneTopic = selectedPaneTopic,
                    isDetailPaneMaximized = isDetailPaneMaximized,
                    allowBackToCloseDetail = false,
                    refreshTrigger = refreshTrigger,
                    onCloseDetailPane = closeDualPaneTopic,
                    onToggleMaximizePane = toggleDualPaneMaximize,
                    onNavigateToUser = navigateToUser,
                    onNavigateToCategory = selectBoardAndOpenExplore,
                    onOpenTopic = openTopic,
                    onEditTopic = editTopic,
                    onRequestWebViewAuth = requestTopicWebViewAuth,
                    onStartDirectMessage = openDirectMessage,
                    onOpenFullNetBench = openMobileNetBench,
                    onOpenFullLiteSsh = openLiteSsh,
                    onPublishSshToComposer = publishSshToComposer,
                ) {
                    HubCollectionScreen(
                        initialTab = route.initialTab,
                        onBack = safePopBackStack,
                        onOpenTopic = openTopic,
                        onOpenAuthorProfile = navigateToUser,
                        onOpenCategory = selectBoardAndOpenExplore,
                        selectedTopicId = if (adaptiveLayout.isDualPane) {
                            selectedPaneTopic?.topicId
                        } else {
                            null
                        },
                    )
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
                    onEditTopic = editTopic,
                    onRequestWebViewAuth = requestTopicWebViewAuth,
                    onStartDirectMessage = openDirectMessage,
                    onOpenFullNetBench = openMobileNetBench,
                    onOpenFullLiteSsh = openLiteSsh,
                    onPublishSshToComposer = publishSshToComposer,
                )
            }

            // Markdown Topic Composer (Create & Edit)
            composable<ComposerRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ComposerRoute>()
                val prefillPayload = remember(route.prefillId) {
                    ComposerPrefillStore.get(route.prefillId)
                }
                val resolvedTitle = prefillPayload?.title ?: route.initialTitle
                val resolvedMarkdown = prefillPayload?.markdown ?: route.initialMarkdown
                TopicComposerScreen(
                    editTopicId = route.editTopicId,
                    initialCategorySlug = route.initialCategorySlug,
                    initialTitle = resolvedTitle,
                    initialMarkdown = resolvedMarkdown,
                    prefillKey = route.prefillId,
                    onBack = {
                        if (route.prefillId.isNotBlank()) {
                            ComposerPrefillStore.remove(route.prefillId)
                        }
                        safePopBackStack()
                    },
                    onTopicPublished = { _ ->
                        if (route.prefillId.isNotBlank()) {
                            ComposerPrefillStore.remove(route.prefillId)
                        }
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set(EDITED_TOPIC_REFRESH_KEY, System.currentTimeMillis())
                        safePopBackStack()
                    },
                    onRequestWebViewAuth = { url ->
                        openWebViewAuth(
                            url,
                            url.contains("signIn", ignoreCase = true),
                        )
                    },
                    onOpenFullNetBench = openMobileNetBench,
                    onOpenFullLiteSsh = openLiteSsh,
                )
            }

            // Unified Inbox (@Me, Replies, System, Direct Message Threads)
            composable<InboxRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<InboxRoute>()
                val refreshTrigger by backStackEntry.savedStateHandle
                    .getStateFlow(EDITED_TOPIC_REFRESH_KEY, 0L)
                    .collectAsStateWithLifecycle()
                AdaptiveMasterDetailContainer(
                    adaptiveLayout = adaptiveLayout,
                    selectedPaneTopic = selectedPaneTopic,
                    isDetailPaneMaximized = isDetailPaneMaximized,
                    allowBackToCloseDetail = false,
                    refreshTrigger = refreshTrigger,
                    onCloseDetailPane = closeDualPaneTopic,
                    onToggleMaximizePane = toggleDualPaneMaximize,
                    onNavigateToUser = navigateToUser,
                    onNavigateToCategory = selectBoardAndOpenExplore,
                    onOpenTopic = openTopic,
                    onEditTopic = editTopic,
                    onRequestWebViewAuth = requestTopicWebViewAuth,
                    onStartDirectMessage = openDirectMessage,
                    onOpenFullNetBench = openMobileNetBench,
                    onOpenFullLiteSsh = openLiteSsh,
                    onPublishSshToComposer = publishSshToComposer,
                ) {
                    InboxScreen(
                        initialTab = route.initialTab,
                        onBack = safePopBackStack,
                        onOpenTopic = openTopic,
                        onOpenDirectMessage = openDirectMessage,
                        onOpenUserProfile = navigateToUser,
                        onOpenRadar = openRadar,
                        onOpenSeekMail = { openSeekMail(0L, 0L) },
                    )
                }
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
                    onOpenDirectMessage = openDirectMessage,
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
                )
            }

            // Pulse Radar: Keyword Alerts, Reply Tracking & Local Alert Records
            composable<PulseRadarRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<PulseRadarRoute>()
                val refreshTrigger by backStackEntry.savedStateHandle
                    .getStateFlow(EDITED_TOPIC_REFRESH_KEY, 0L)
                    .collectAsStateWithLifecycle()
                AdaptiveMasterDetailContainer(
                    adaptiveLayout = adaptiveLayout,
                    selectedPaneTopic = selectedPaneTopic,
                    isDetailPaneMaximized = isDetailPaneMaximized,
                    allowBackToCloseDetail = false,
                    refreshTrigger = refreshTrigger,
                    onCloseDetailPane = closeDualPaneTopic,
                    onToggleMaximizePane = toggleDualPaneMaximize,
                    onNavigateToUser = navigateToUser,
                    onNavigateToCategory = selectBoardAndOpenExplore,
                    onOpenTopic = openTopic,
                    onEditTopic = editTopic,
                    onRequestWebViewAuth = requestTopicWebViewAuth,
                    onStartDirectMessage = openDirectMessage,
                    onOpenFullNetBench = openMobileNetBench,
                    onOpenFullLiteSsh = openLiteSsh,
                    onPublishSshToComposer = publishSshToComposer,
                ) {
                    PulseRadarScreen(
                        initialTab = route.initialTab,
                        onBack = safePopBackStack,
                        onOpenTopic = openTopic,
                        container = container,
                    )
                }
            }

            // Seek Mail (seek.li OpenAPI Mailbox & OTP Client)
            composable<SeekMailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SeekMailRoute>()
                SeekMailScreen(
                    onBack = safePopBackStack,
                    initialMailboxId = route.initialMailboxId,
                    initialMailId = route.initialMailId,
                    container = container,
                )
            }

            // Feature 1: VPS Restock & Flash-Sale Monitor
            composable<RestockMonitorRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<RestockMonitorRoute>()
                RestockMonitorScreen(
                    repository = container.restockRepository,
                    monitorController = container.restockMonitorController,
                    notificationHelper = container.restockNotificationHelper,
                    flareSolverrClient = container.flareSolverrClient,
                    onBack = safePopBackStack,
                    initialTab = route.initialTab,
                    initialUrl = route.initialUrl,
                    initialTitle = route.initialTitle,
                    onOpenTopic = { targetTopicId ->
                        openTopic(targetTopicId, 0, "")
                    },
                    onImportToServerLedger = { item ->
                        openMyServers(
                            1,
                            item.title,
                            item.priceHint,
                            item.resolvedOrderUrl.ifBlank { item.rawUrl },
                        )
                    },
                )
            }

            // Feature 2: Community Activity Hub (ForumWatch integration + opt-in notifications)
            composable<ActivityRoute> {
                val activityNotificationBinding = rememberActivityNotificationBinding(
                    controller = container.activityMonitorController,
                    notificationDispatcher = container.notificationDispatcher,
                    onFeedbackMessage = { message, showOpenSettingsAction ->
                        appScope.launch {
                            val result = rootSnackbarHostState.showSnackbar(
                                message = message,
                                actionLabel = if (showOpenSettingsAction) "去设置" else null,
                            )
                            if (showOpenSettingsAction && result == SnackbarResult.ActionPerformed) {
                                openActivityAppNotificationSettings(context)
                            }
                        }
                    },
                )
                ActivityScreen(
                    repository = container.activityRepository,
                    onBack = safePopBackStack,
                    notificationsEnabled = activityNotificationBinding.isNotificationsEnabled,
                    onToggleNotifications = activityNotificationBinding.onToggleNotifications,
                    onOpenTopic = openTopic,
                    onOpenUser = navigateToUser,
                    onOpenCategory = selectBoardAndOpenExplore,
                )
            }

            // Feature 7: Overseas VPS Promotions (LowEndTalk Monitor API + Detail + Repost)
            composable<OverseasIntelRoute> {
                val promoNotificationBinding = rememberOverseasPromoNotificationBinding(
                    controller = container.overseasPromoMonitorController,
                    notificationDispatcher = container.notificationDispatcher,
                    onFeedbackMessage = { message, showOpenSettingsAction ->
                        appScope.launch {
                            val result = rootSnackbarHostState.showSnackbar(
                                message = message,
                                actionLabel = if (showOpenSettingsAction) "去设置" else null,
                            )
                            if (showOpenSettingsAction && result == SnackbarResult.ActionPerformed) {
                                openOverseasAppNotificationSettings(context)
                            }
                        }
                    },
                )
                OverseasIntelScreen(
                    onBack = safePopBackStack,
                    repository = container.overseasIntelRepository,
                    onOpenOfferDetail = { offer ->
                        navController.navigate(
                            OverseasIntelDetailRoute(
                                offerId = offer.id,
                                initialTitle = offer.title,
                                initialSourceUrl = offer.sourceLink,
                            ),
                        ) {
                            launchSingleTop = true
                        }
                    },
                    onShareDealToForum = { suggestedTitle, markdownBody ->
                        openComposerWithPrefill("info", suggestedTitle, markdownBody)
                    },
                    promoNotificationsEnabled = promoNotificationBinding.isNotificationsEnabled,
                    onTogglePromoNotifications = promoNotificationBinding.onToggleNotifications,
                )
            }

            composable<OverseasIntelDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<OverseasIntelDetailRoute>()
                OverseasIntelDetailScreen(
                    offerId = route.offerId,
                    initialTitle = route.initialTitle,
                    initialSourceUrl = route.initialSourceUrl,
                    onBack = safePopBackStack,
                    repository = container.overseasIntelRepository,
                    onShareDealToForum = { suggestedTitle, markdownBody ->
                        openComposerWithPrefill("info", suggestedTitle, markdownBody)
                    },
                )
            }

            // Feature 4: My Servers (Nezha/Komari Probe Monitor + Asset Ledger + One-Click Valuation Sale Post)
            composable<MyServersRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<MyServersRoute>()
                var pendingImportPrefill by remember(route.importName, route.importPrice, route.importUrl) {
                    mutableStateOf(
                        if (route.importName.isNotBlank() || route.importUrl.isNotBlank()) {
                            RestockAssetPrefill(
                                name = route.importName,
                                priceHint = route.importPrice,
                                purchaseUrl = route.importUrl,
                            )
                        } else {
                            null
                        },
                    )
                }
                MyServersScreen(
                    onBack = safePopBackStack,
                    onCreateSalePost = { suggestedTitle, markdownBody ->
                        openComposerWithPrefill("trade", suggestedTitle, markdownBody)
                    },
                    fleetRepository = container.fleetRepository,
                    exchangeRateRepository = container.exchangeRateRepository,
                    fleetWorkScheduler = container.fleetWorkScheduler,
                    initialTab = if (route.initialTab == 1 || route.highlightAssetId.isNotBlank()) {
                        MyServersTab.ASSETS
                    } else {
                        MyServersTab.PROBES
                    },
                    initialHighlightAssetId = route.highlightAssetId.takeIf { it.isNotBlank() },
                    pendingRestockPrefill = pendingImportPrefill,
                    onConsumeRestockPrefill = { pendingImportPrefill = null },
                )
            }

            // Feature 5: Mobile Network Benchmark (TCPing + Native iperf3 + IP Native/Broadcast Classifier)
            composable<MobileNetBenchRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<MobileNetBenchRoute>()
                MobileNetBenchScreen(
                    onBack = safePopBackStack,
                    initialTarget = route.initialTarget,
                    onInsertMarkdown = { markdown ->
                        openComposerWithPrefill("review", "", markdown)
                    },
                    onOpenLiteSshIperf3Setup = { targetHost, port ->
                        if (port > 0) {
                            container.sshSessionManager.openIperf3ServerDialog(
                                preferredHost = targetHost,
                                preferredPort = port,
                            )
                        }
                        navController.navigate(LiteSshRoute) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToVault = {
                        navController.navigate(LiteSshRoute) {
                            launchSingleTop = true
                        }
                    },
                    insertActionLabel = "一键发测评帖",
                )
            }

            // Feature 6: Lite SSH (Server Vault + Preset Benchmark Scripts + ANSI Viewer + 1-Click Review Post)
            composable<LiteSshRoute> {
                LaunchedEffect(container) {
                    container.sshSessionManager.attachFleetIntegration(
                        fleetRepository = container.fleetRepository,
                    )
                }
                val sshViewModel: LiteSshViewModel = viewModel(
                    factory = LiteSshViewModel.factory(
                        sessionManager = container.sshSessionManager,
                    ),
                )
                LiteSshScreen(
                    onBack = safePopBackStack,
                    onPublishToComposer = { suggestedTitle, markdownContent ->
                        openComposerWithPrefill(
                            LITE_SSH_REVIEW_CATEGORY_SLUG,
                            suggestedTitle,
                            markdownContent,
                        )
                    },
                    onOpenProbePanels = {
                        openMyServers(0, "", "", "")
                    },
                    viewModel = sshViewModel,
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
                        adaptiveLayout.isDualPane && selectedPaneTopic != null ->
                            "https://www.nodeseek.com/post-${selectedPaneTopic!!.topicId}-1"
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
                        openTopicFromExternal(target.topicId, target.initialFloor, target.titleHint)
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
                        ) {
                            launchSingleTop = true
                        }
                    }

                    NotificationTapTarget.OpenLiteSsh -> {
                        isDetailPaneMaximized = false
                        openLiteSsh()
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

        com.nodeseek.app.ui.ssh.LiteSshRunningGlobalBarHost(
            sessionManager = container.sshSessionManager,
            visible = !isOnLiteSshRoute && activeWebViewRequest == null,
            onReturnToLiteSsh = openLiteSsh,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(
                    start = 14.dp,
                    end = 14.dp,
                    bottom = if (adaptiveLayout.useNavigationRail) 16.dp else 88.dp,
                ),
        )

        PulseSnackbarHost(
            hostState = rootSnackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (adaptiveLayout.useNavigationRail) 16.dp else 94.dp),
            pillModifier = Modifier,
        )

        // Centralized first-party WebView authentication & challenge recovery sheet
        activeWebViewRequest?.let { request ->
            val userAgentProvider = (container as? DefaultAppContainer)?.userAgentProvider
            val synchronizedUserAgent = remember(userAgentProvider, context, request) {
                userAgentProvider?.refreshSystemUserAgentOnMain(context)
            }
            val useSystemDefaultUserAgent = remember(userAgentProvider, synchronizedUserAgent) {
                userAgentProvider?.isSystemWebViewDefault != false
            }
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

@Composable
private fun AdaptiveMasterDetailContainer(
    adaptiveLayout: PulseAdaptiveLayoutInfo,
    selectedPaneTopic: SelectedPaneTopic?,
    isDetailPaneMaximized: Boolean,
    allowBackToCloseDetail: Boolean,
    refreshTrigger: Long,
    onCloseDetailPane: () -> Unit,
    onToggleMaximizePane: () -> Unit,
    onNavigateToUser: (uid: Long, usernameHint: String) -> Unit,
    onNavigateToCategory: (slug: String) -> Unit,
    onOpenTopic: (topicId: Long, initialFloor: Int, titleHint: String) -> Unit,
    onEditTopic: (topicId: Long, categorySlug: String) -> Unit,
    onRequestWebViewAuth: (url: String) -> Unit,
    modifier: Modifier = Modifier,
    onStartDirectMessage: ((peerUid: Long, peerName: String) -> Unit)? = null,
    onOpenFullNetBench: ((initialTarget: String) -> Unit)? = null,
    onOpenFullLiteSsh: (() -> Unit)? = null,
    onPublishSshToComposer: ((suggestedTitle: String, markdownContent: String) -> Unit)? = null,
    listContent: @Composable () -> Unit,
) {
    val movableList = remember {
        movableContentOf { render: @Composable () -> Unit ->
            render()
        }
    }

    if (!adaptiveLayout.isDualPane) {
        Box(modifier = modifier.fillMaxSize()) {
            movableList(listContent)
        }
    } else {
        PulseDualPaneScaffold(
            adaptiveLayout = adaptiveLayout,
            hasDetailContent = selectedPaneTopic != null,
            isDetailMaximized = isDetailPaneMaximized && selectedPaneTopic != null,
            modifier = modifier,
            listPane = {
                movableList(listContent)
            },
            detailPane = {
                if (selectedPaneTopic != null) {
                    key(selectedPaneTopic.topicId) {
                        val paneStoreOwner = remember(selectedPaneTopic.topicId) {
                            object : ViewModelStoreOwner {
                                override val viewModelStore: ViewModelStore = ViewModelStore()
                            }
                        }
                        DisposableEffect(paneStoreOwner) {
                            onDispose {
                                paneStoreOwner.viewModelStore.clear()
                            }
                        }
                        CompositionLocalProvider(LocalViewModelStoreOwner provides paneStoreOwner) {
                            TopicDetailScreen(
                                topicId = selectedPaneTopic.topicId,
                                onBack = onCloseDetailPane,
                                initialFloor = selectedPaneTopic.initialFloor,
                                titleHint = selectedPaneTopic.titleHint,
                                refreshTrigger = refreshTrigger,
                                onNavigateToUser = onNavigateToUser,
                                onNavigateToCategory = onNavigateToCategory,
                                onOpenTopic = onOpenTopic,
                                onEditTopic = onEditTopic,
                                onRequestWebViewAuth = onRequestWebViewAuth,
                                onStartDirectMessage = onStartDirectMessage,
                                onOpenFullNetBench = onOpenFullNetBench,
                                onOpenFullLiteSsh = onOpenFullLiteSsh,
                                onPublishSshToComposer = onPublishSshToComposer,
                                isEmbeddedInDualPane = true,
                                isDetailPaneMaximized = isDetailPaneMaximized,
                                onCloseDetailPane = onCloseDetailPane,
                                onToggleMaximizePane = onToggleMaximizePane,
                            )
                        }
                    }
                }
            },
        )

        // Registered after PulseDualPaneScaffold so it takes LIFO priority over list BackHandlers
        BackHandler(
            enabled = selectedPaneTopic != null && (isDetailPaneMaximized || allowBackToCloseDetail),
        ) {
            if (isDetailPaneMaximized) {
                onToggleMaximizePane()
            } else {
                onCloseDetailPane()
            }
        }
    }
}

private fun resolveDestinationKind(
    entry: NavBackStackEntry?,
    selectedTab: PulseNavTab,
): DualPaneDestinationKind {
    val dest = entry?.destination ?: return DualPaneDestinationKind.OTHER
    return when {
        dest.hasRoute<ExploreRoute>() -> {
            if (selectedTab == PulseNavTab.EXPLORE) {
                DualPaneDestinationKind.EXPLORE_FEED
            } else {
                DualPaneDestinationKind.EXPLORE_OTHER_TAB
            }
        }
        dest.hasRoute<SearchRoute>() || dest.hasRoute<BoardsRoute>() ->
            DualPaneDestinationKind.BOARDS_OR_SEARCH
        dest.hasRoute<HubCollectionRoute>() ->
            DualPaneDestinationKind.HUB_COLLECTION
        dest.hasRoute<InboxRoute>() ->
            DualPaneDestinationKind.INBOX
        dest.hasRoute<PulseRadarRoute>() ->
            DualPaneDestinationKind.PULSE_RADAR
        dest.hasRoute<TopicDetailRoute>() ->
            DualPaneDestinationKind.TOPIC_DETAIL
        else -> DualPaneDestinationKind.OTHER
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
