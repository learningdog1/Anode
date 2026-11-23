package com.nodeseek.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.nodeseek.app.data.fleet.FleetAlertNotifier
import com.nodeseek.app.data.restock.RestockNotificationHelper
import com.nodeseek.app.domain.radar.NotificationTapTarget
import com.nodeseek.app.navigation.AppDestination
import com.nodeseek.app.navigation.MyServersRoute
import com.nodeseek.app.navigation.NodePulseRootShell
import com.nodeseek.app.navigation.RestockMonitorRoute
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val pendingDeepLinkUri = MutableStateFlow<Uri?>(null)
    private val deepLinkUriFlow = pendingDeepLinkUri.asStateFlow()
    private val pendingNotificationTap = Channel<NotificationTapTarget>(Channel.BUFFERED)
    private val notificationTapFlow = pendingNotificationTap.receiveAsFlow()
    private val pendingShortcutDestination = Channel<AppDestination>(Channel.BUFFERED)
    private val shortcutDestinationFlow = pendingShortcutDestination.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        if (savedInstanceState == null) {
            if (intent?.action == Intent.ACTION_VIEW) {
                pendingDeepLinkUri.value = intent?.data
            }
            dispatchNotificationTapIfPresent(intent)
        }

        val appContainer = (application as NodePulseApp).container

        setContent {
            @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
            val windowSizeClass = calculateWindowSizeClass(this)
            NodePulseRootShell(
                container = appContainer,
                windowSizeClass = windowSizeClass,
                deepLinkUriFlow = deepLinkUriFlow,
                onDeepLinkConsumed = {
                    pendingDeepLinkUri.value = null
                },
                notificationTapFlow = notificationTapFlow,
                shortcutDestinationFlow = shortcutDestinationFlow,
                onDarkThemeResolved = { isDark ->
                    val systemBarStyle = if (isDark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            scrim = Color.TRANSPARENT,
                            darkScrim = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                Color.TRANSPARENT
                            } else {
                                Color.argb(0x1F, 0x00, 0x00, 0x00)
                            },
                        )
                    }
                    enableEdgeToEdge(
                        statusBarStyle = systemBarStyle,
                        navigationBarStyle = systemBarStyle,
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        window.isNavigationBarContrastEnforced = false
                    }
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            pendingDeepLinkUri.value = intent.data
        }
        dispatchNotificationTapIfPresent(intent)
    }

    private fun dispatchNotificationTapIfPresent(targetIntent: Intent?) {
        if (targetIntent == null) return
        if (RestockNotificationHelper.isRestockMonitorIntent(targetIntent)) {
            val watchId = targetIntent.getStringExtra(RestockNotificationHelper.EXTRA_RESTOCK_WATCH_ID)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val eventId = targetIntent.getStringExtra(RestockNotificationHelper.EXTRA_RESTOCK_EVENT_ID)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            targetIntent.action = null
            targetIntent.removeExtra(RestockNotificationHelper.EXTRA_RESTOCK_WATCH_ID)
            targetIntent.removeExtra(RestockNotificationHelper.EXTRA_RESTOCK_EVENT_ID)
            if (watchId != null) {
                runCatching {
                    NotificationManagerCompat.from(this)
                        .cancel(RestockNotificationHelper.notificationIdForWatch(watchId))
                }
            }
            if (eventId != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        (application as? NodePulseApp)?.container?.restockRepository?.markEventRead(eventId)
                    }
                }
            }
            pendingShortcutDestination.trySend(RestockMonitorRoute(initialTab = 1))
            return
        }
        if (targetIntent.action == FleetAlertNotifier.ACTION_OPEN_FLEET) {
            val tab = targetIntent.getStringExtra(FleetAlertNotifier.EXTRA_FLEET_TAB)
            val assetId = targetIntent.getStringExtra(FleetAlertNotifier.EXTRA_ASSET_ID).orEmpty().trim()
            targetIntent.action = null
            targetIntent.removeExtra(FleetAlertNotifier.EXTRA_FLEET_TAB)
            targetIntent.removeExtra(FleetAlertNotifier.EXTRA_ASSET_ID)
            val initialTab = if (tab == FleetAlertNotifier.EXTRA_TAB_ASSETS || assetId.isNotEmpty()) 1 else 0
            pendingShortcutDestination.trySend(
                MyServersRoute(initialTab = initialTab, highlightAssetId = assetId),
            )
            return
        }
        PulseNotificationDispatcher.parseOverseasPromoDestination(targetIntent)?.let { destination ->
            PulseNotificationDispatcher.clearOverseasPromoExtras(targetIntent)
            pendingShortcutDestination.trySend(destination)
            return
        }
        PulseNotificationDispatcher.parseCommunityActivityDestination(targetIntent)?.let { destination ->
            PulseNotificationDispatcher.clearCommunityActivityExtras(targetIntent)
            pendingShortcutDestination.trySend(destination)
            return
        }
        val target = PulseNotificationDispatcher.parseTapTarget(targetIntent) ?: return
        PulseNotificationDispatcher.clearTapTargetExtras(targetIntent)
        pendingNotificationTap.trySend(target)
    }
}
