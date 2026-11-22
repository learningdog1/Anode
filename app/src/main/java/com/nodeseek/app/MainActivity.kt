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
import com.nodeseek.app.data.fleet.FleetAlertNotifier
import com.nodeseek.app.data.restock.RestockNotificationHelper
import com.nodeseek.app.domain.radar.NotificationTapTarget
import com.nodeseek.app.navigation.AppDestination
import com.nodeseek.app.navigation.MyServersRoute
import com.nodeseek.app.navigation.NodePulseRootShell
import com.nodeseek.app.navigation.RestockMonitorRoute
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {

    private val pendingDeepLinkUri = MutableStateFlow<Uri?>(null)
    private val pendingNotificationTap = Channel<NotificationTapTarget>(Channel.BUFFERED)
    private val pendingShortcutDestination = Channel<AppDestination>(Channel.BUFFERED)

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
                deepLinkUriFlow = pendingDeepLinkUri.asStateFlow(),
                onDeepLinkConsumed = {
                    pendingDeepLinkUri.value = null
                },
                notificationTapFlow = pendingNotificationTap.receiveAsFlow(),
                shortcutDestinationFlow = pendingShortcutDestination.receiveAsFlow(),
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
            targetIntent.action = null
            pendingShortcutDestination.trySend(RestockMonitorRoute(initialTab = 1))
            return
        }
        if (targetIntent.action == FleetAlertNotifier.ACTION_OPEN_FLEET) {
            val tab = targetIntent.getStringExtra(FleetAlertNotifier.EXTRA_FLEET_TAB)
            targetIntent.action = null
            val initialTab = if (tab == FleetAlertNotifier.EXTRA_TAB_ASSETS) 1 else 0
            pendingShortcutDestination.trySend(MyServersRoute(initialTab = initialTab))
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
