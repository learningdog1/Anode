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
import com.nodeseek.app.data.restock.RestockNotificationHelper
import com.nodeseek.app.domain.radar.NotificationTapTarget
import com.nodeseek.app.navigation.AppDestination
import com.nodeseek.app.navigation.NodePulseRootShell
import com.nodeseek.app.navigation.PulseHubRoute
import com.nodeseek.app.navigation.RestockMonitorRoute
import com.nodeseek.app.navigation.SeekMailRoute
import com.nodeseek.app.navigation.ShortcutCommand
import com.nodeseek.app.navigation.ShortcutIntentParser
import com.nodeseek.app.notifications.CopyOtpBroadcastReceiver
import com.nodeseek.app.notifications.OtpWatchNotificationHelper
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import com.nodeseek.app.widget.GlanceWidgetUpdater
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
    private val pendingFeedbackMessage = Channel<String>(Channel.BUFFERED)
    private val externalFeedbackFlow = pendingFeedbackMessage.receiveAsFlow()

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
                externalFeedbackFlow = externalFeedbackFlow,
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

        ShortcutIntentParser.consumeCommand(targetIntent)?.let { command ->
            handleShortcutCommand(command)
            return
        }

        OtpWatchNotificationHelper.parseSeekMailTapDestination(targetIntent)?.let { destination ->
            OtpWatchNotificationHelper.clearSeekMailExtras(targetIntent)
            pendingShortcutDestination.trySend(destination)
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

    private fun handleShortcutCommand(command: ShortcutCommand) {
        when (command) {
            is ShortcutCommand.Navigate -> {
                pendingShortcutDestination.trySend(command.destination)
            }

            ShortcutCommand.PerformCheckIn -> {
                lifecycleScope.launch {
                    val container = (application as? NodePulseApp)?.container ?: return@launch
                    if (container.sessionRepository.sessionState.value == null) {
                        runCatching { container.sessionRepository.refreshSession() }
                    }
                    if (container.sessionRepository.sessionState.value == null) {
                        pendingFeedbackMessage.trySend("请先登录 NodeSeek 账号后再一键签到")
                        pendingShortcutDestination.trySend(PulseHubRoute)
                        return@launch
                    }
                    val preferRandom = container.settingsRepository.awaitLoadedSettings().preferRandomCheckIn
                    val result = container.checkInRepository.checkIn(preferRandom)
                    GlanceWidgetUpdater.updateAll(applicationContext)
                    val message = result.fold(
                        onSuccess = { status ->
                            val gain = status.todayGain
                            if (gain != null) {
                                "签到成功：+$gain 鸡腿"
                            } else {
                                status.message?.takeIf { it.isNotBlank() } ?: "今日已完成签到"
                            }
                        },
                        onFailure = { error ->
                            "签到失败：${error.message?.takeIf { it.isNotBlank() } ?: "请稍后重试"}"
                        },
                    )
                    pendingFeedbackMessage.trySend(message)
                }
            }

            ShortcutCommand.CopyLatestOtp -> {
                lifecycleScope.launch {
                    val container = (application as? NodePulseApp)?.container ?: return@launch
                    val hasSeekMailKey = container.settingsRepository.awaitLoadedSettings().seekMailApiKey.trim().isNotEmpty()
                    if (!hasSeekMailKey) {
                        pendingFeedbackMessage.trySend("请先配置 Seek 邮箱 API Key")
                        pendingShortcutDestination.trySend(SeekMailRoute(initialMailboxId = 0L, initialMailId = 0L))
                        return@launch
                    }
                    val latestOtp = container.widgetSnapshotRepository.fetchOrCachedLatestOtp()
                    if (latestOtp == null) {
                        pendingFeedbackMessage.trySend("暂未找到可复制的验证码邮件")
                        pendingShortcutDestination.trySend(SeekMailRoute(initialMailboxId = 0L, initialMailId = 0L))
                        return@launch
                    }
                    window.decorView.post {
                        CopyOtpBroadcastReceiver.copyOtpToClipboard(
                            context = this@MainActivity,
                            code = latestOtp.code,
                        )
                    }
                    val senderSuffix = latestOtp.sender.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
                    pendingFeedbackMessage.trySend("已复制最新验证码：${latestOtp.code}$senderSuffix")
                }
            }
        }
    }
}
