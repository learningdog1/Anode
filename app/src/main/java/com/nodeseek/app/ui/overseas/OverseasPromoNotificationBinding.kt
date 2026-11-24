package com.nodeseek.app.ui.overseas

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodeseek.app.domain.overseas.OverseasPromoMonitorController
import com.nodeseek.app.domain.overseas.OverseasPromoMonitorSettings
import com.nodeseek.app.domain.overseas.OverseasPromoRuntimeStatus
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import kotlinx.coroutines.launch

/**
 * Pure decision model for toggling overseas promotion notifications across Android API levels.
 */
internal sealed interface OverseasPromoToggleDecision {
    data object DisableNotifications : OverseasPromoToggleDecision
    data object EnableNotificationsImmediately : OverseasPromoToggleDecision
    data object RequestRuntimePostNotificationsPermission : OverseasPromoToggleDecision
    data object GuideToSystemNotificationSettings : OverseasPromoToggleDecision
}

/**
 * Computes effective switch state shown in the Overseas Intel UI: enabled only when both the
 * user's overseas notification preference is ON and system notification permission is available.
 */
internal fun resolveEffectivePromoNotificationsEnabled(
    preferenceEnabled: Boolean,
    hasPostNotificationsPermission: Boolean,
): Boolean = preferenceEnabled && hasPostNotificationsPermission

/**
 * Resolves what action should occur when the user toggles the overseas promotion notification switch.
 */
internal fun resolveOverseasPromoToggleDecision(
    targetEnabled: Boolean,
    sdkInt: Int,
    runtimePermissionGranted: Boolean,
    systemNotificationsEnabled: Boolean,
): OverseasPromoToggleDecision {
    if (!targetEnabled) {
        return OverseasPromoToggleDecision.DisableNotifications
    }
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU && !runtimePermissionGranted) {
        return OverseasPromoToggleDecision.RequestRuntimePostNotificationsPermission
    }
    if (!systemNotificationsEnabled) {
        return OverseasPromoToggleDecision.GuideToSystemNotificationSettings
    }
    return OverseasPromoToggleDecision.EnableNotificationsImmediately
}

@Stable
class OverseasPromoNotificationBindingState(
    val isNotificationsEnabled: Boolean,
    val isPreferenceEnabled: Boolean,
    val hasSystemNotificationPermission: Boolean,
    val runtimeStatus: OverseasPromoRuntimeStatus,
    val onToggleNotifications: (Boolean) -> Unit,
    val onOpenMonitorTelegramChannel: () -> Unit,
    val onOpenSystemNotificationSettings: () -> Unit,
)

/**
 * Reusable Compose binding connecting [OverseasPromoMonitorController] with Android 13+
 * `POST_NOTIFICATIONS` permission requests and system notification settings guidance.
 */
@Composable
fun rememberOverseasPromoNotificationBinding(
    controller: OverseasPromoMonitorController,
    notificationDispatcher: PulseNotificationDispatcher,
    onFeedbackMessage: ((message: String, showOpenSettingsAction: Boolean) -> Unit)? = null,
    onOpenUrl: ((String) -> Unit)? = null,
): OverseasPromoNotificationBindingState {
    val context = LocalContext.current
    val isInspection = LocalInspectionMode.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val settings by controller.settingsFlow.collectAsStateWithLifecycle()
    val runtimeStatus by controller.runtimeStatus.collectAsStateWithLifecycle()

    val latestFeedback by rememberUpdatedState(onFeedbackMessage)
    val latestOpenUrl by rememberUpdatedState(onOpenUrl)

    var hasSystemPermission by remember(notificationDispatcher, isInspection) {
        mutableStateOf(
            if (isInspection) true else notificationDispatcher.hasPostNotificationsPermission(),
        )
    }

    DisposableEffect(lifecycleOwner, notificationDispatcher, isInspection) {
        if (isInspection) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    hasSystemPermission = notificationDispatcher.hasPostNotificationsPermission()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val nowPermitted = notificationDispatcher.hasPostNotificationsPermission()
        hasSystemPermission = nowPermitted
        if (granted && nowPermitted) {
            scope.launch {
                controller.setNotificationsEnabled(true)
            }
            latestFeedback?.invoke("已开启海外情报促销主动推送通知", false)
        } else {
            // Do not force the switch ON when permission is denied; guide user clearly.
            scope.launch {
                controller.setNotificationsEnabled(false)
            }
            val guidance = if (granted && !nowPermitted) {
                "系统应用通知总开关未开启，请前往系统设置开启通知"
            } else {
                "未授予通知权限，无法主动推送新促销贴；可在系统设置中开启通知权限"
            }
            latestFeedback?.invoke(guidance, true)
        }
    }

    val openSettingsAction: () -> Unit = remember(context) {
        { openOverseasAppNotificationSettings(context) }
    }

    val openTgChannelAction: () -> Unit = remember(context) {
        {
            val handler = latestOpenUrl
            if (handler != null) {
                handler(OverseasPromoMonitorSettings.TELEGRAM_MONITOR_CHANNEL_URL)
            } else {
                launchExternalPromoUrl(context, OverseasPromoMonitorSettings.TELEGRAM_MONITOR_CHANNEL_URL)
            }
        }
    }

    val toggleAction: (Boolean) -> Unit = remember(context, controller, notificationDispatcher, isInspection) {
        { wantEnabled ->
            if (isInspection) {
                scope.launch { controller.setNotificationsEnabled(wantEnabled) }
            } else {
                val runtimeGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }
                val systemEnabled = runCatching {
                    NotificationManagerCompat.from(context).areNotificationsEnabled()
                }.getOrDefault(true)
                val fullPermitted = notificationDispatcher.hasPostNotificationsPermission()
                hasSystemPermission = fullPermitted

                when (
                    resolveOverseasPromoToggleDecision(
                        targetEnabled = wantEnabled,
                        sdkInt = Build.VERSION.SDK_INT,
                        runtimePermissionGranted = runtimeGranted,
                        systemNotificationsEnabled = systemEnabled && fullPermitted,
                    )
                ) {
                    OverseasPromoToggleDecision.DisableNotifications -> {
                        scope.launch {
                            controller.setNotificationsEnabled(false)
                        }
                        latestFeedback?.invoke("已关闭海外情报促销推送通知", false)
                    }

                    OverseasPromoToggleDecision.EnableNotificationsImmediately -> {
                        scope.launch {
                            controller.setNotificationsEnabled(true)
                        }
                        latestFeedback?.invoke("已开启海外情报促销主动推送通知", false)
                    }

                    OverseasPromoToggleDecision.RequestRuntimePostNotificationsPermission -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationDispatcher.markPostNotificationsRequestedOnce()
                            runCatching {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }.onFailure {
                                latestFeedback?.invoke("无法拉起系统权限弹窗，请在系统设置中开启通知权限", true)
                            }
                        } else {
                            scope.launch {
                                controller.setNotificationsEnabled(true)
                            }
                            latestFeedback?.invoke("已开启海外情报促销主动推送通知", false)
                        }
                    }

                    OverseasPromoToggleDecision.GuideToSystemNotificationSettings -> {
                        scope.launch {
                            controller.setNotificationsEnabled(false)
                        }
                        latestFeedback?.invoke("系统通知权限已关闭，请前往系统设置开启通知后再打开推送", true)
                    }
                }
            }
        }
    }

    val effectiveEnabled = resolveEffectivePromoNotificationsEnabled(
        preferenceEnabled = settings.notificationsEnabled,
        hasPostNotificationsPermission = hasSystemPermission,
    )

    return remember(
        effectiveEnabled,
        settings.notificationsEnabled,
        hasSystemPermission,
        runtimeStatus,
        toggleAction,
        openTgChannelAction,
        openSettingsAction,
    ) {
        OverseasPromoNotificationBindingState(
            isNotificationsEnabled = effectiveEnabled,
            isPreferenceEnabled = settings.notificationsEnabled,
            hasSystemNotificationPermission = hasSystemPermission,
            runtimeStatus = runtimeStatus,
            onToggleNotifications = toggleAction,
            onOpenMonitorTelegramChannel = openTgChannelAction,
            onOpenSystemNotificationSettings = openSettingsAction,
        )
    }
}

fun openOverseasAppNotificationSettings(context: Context) {
    val started = runCatching {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    if (!started) {
        runCatching {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }
}

private fun launchExternalPromoUrl(context: Context, url: String) {
    if (url.isBlank()) return
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
