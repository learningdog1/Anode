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
import com.nodeseek.app.domain.radar.NotificationTapTarget
import com.nodeseek.app.navigation.NodePulseRootShell
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {

    private val pendingDeepLinkUri = MutableStateFlow<Uri?>(null)
    private val pendingNotificationTap = Channel<NotificationTapTarget>(Channel.BUFFERED)

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
            NodePulseRootShell(
                container = appContainer,
                deepLinkUriFlow = pendingDeepLinkUri.asStateFlow(),
                onDeepLinkConsumed = {
                    pendingDeepLinkUri.value = null
                },
                notificationTapFlow = pendingNotificationTap.receiveAsFlow(),
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
        val target = PulseNotificationDispatcher.parseTapTarget(targetIntent) ?: return
        PulseNotificationDispatcher.clearTapTargetExtras(targetIntent)
        pendingNotificationTap.trySend(target)
    }
}
