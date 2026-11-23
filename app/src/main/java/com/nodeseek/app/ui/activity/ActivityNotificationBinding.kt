package com.nodeseek.app.ui.activity

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodeseek.app.domain.activity.ActivityMonitorController
import com.nodeseek.app.domain.activity.ActivityMonitorRuntimeStatus
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.pulseCard
import kotlinx.coroutines.launch

internal sealed interface ActivityToggleDecision {
    data object DisableNotifications : ActivityToggleDecision
    data object EnableNotificationsImmediately : ActivityToggleDecision
    data object RequestRuntimePostNotificationsPermission : ActivityToggleDecision
    data object GuideToSystemNotificationSettings : ActivityToggleDecision
}

internal fun resolveEffectiveActivityNotificationsEnabled(
    preferenceEnabled: Boolean,
    hasPostNotificationsPermission: Boolean,
): Boolean = preferenceEnabled && hasPostNotificationsPermission

internal fun resolveActivityToggleDecision(
    targetEnabled: Boolean,
    sdkInt: Int,
    runtimePermissionGranted: Boolean,
    systemNotificationsEnabled: Boolean,
): ActivityToggleDecision {
    if (!targetEnabled) {
        return ActivityToggleDecision.DisableNotifications
    }
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU && !runtimePermissionGranted) {
        return ActivityToggleDecision.RequestRuntimePostNotificationsPermission
    }
    if (!systemNotificationsEnabled) {
        return ActivityToggleDecision.GuideToSystemNotificationSettings
    }
    return ActivityToggleDecision.EnableNotificationsImmediately
}

@Stable
class ActivityNotificationBindingState(
    val isNotificationsEnabled: Boolean,
    val isPreferenceEnabled: Boolean,
    val hasSystemNotificationPermission: Boolean,
    val runtimeStatus: ActivityMonitorRuntimeStatus,
    val onToggleNotifications: (Boolean) -> Unit,
    val onOpenSystemNotificationSettings: () -> Unit,
)

/**
 * Reusable Compose binding connecting [ActivityMonitorController] with Android 13+
 * `POST_NOTIFICATIONS` permission requests and system notification settings guidance.
 */
@Composable
fun rememberActivityNotificationBinding(
    controller: ActivityMonitorController,
    notificationDispatcher: PulseNotificationDispatcher,
    onFeedbackMessage: ((message: String, showOpenSettingsAction: Boolean) -> Unit)? = null,
): ActivityNotificationBindingState {
    val context = LocalContext.current
    val isInspection = LocalInspectionMode.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val settings by controller.settingsFlow.collectAsStateWithLifecycle()
    val runtimeStatus by controller.runtimeStatus.collectAsStateWithLifecycle()
    val latestFeedback by rememberUpdatedState(onFeedbackMessage)

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
            latestFeedback?.invoke("已开启新活动通知，发现社区新抽奖与福利时将自动提醒", false)
        } else {
            scope.launch {
                controller.setNotificationsEnabled(false)
            }
            val guidance = if (granted && !nowPermitted) {
                "系统应用通知总开关未开启，请前往系统设置开启通知"
            } else {
                "未授予通知权限，无法推送新活动提醒；可在系统设置中开启"
            }
            latestFeedback?.invoke(guidance, true)
        }
    }

    val openSettingsAction: () -> Unit = remember(context) {
        { openActivityAppNotificationSettings(context) }
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
                    resolveActivityToggleDecision(
                        targetEnabled = wantEnabled,
                        sdkInt = Build.VERSION.SDK_INT,
                        runtimePermissionGranted = runtimeGranted,
                        systemNotificationsEnabled = systemEnabled && fullPermitted,
                    )
                ) {
                    ActivityToggleDecision.DisableNotifications -> {
                        scope.launch {
                            controller.setNotificationsEnabled(false)
                        }
                        latestFeedback?.invoke("已关闭新活动通知", false)
                    }

                    ActivityToggleDecision.EnableNotificationsImmediately -> {
                        scope.launch {
                            controller.setNotificationsEnabled(true)
                        }
                        latestFeedback?.invoke("已开启新活动通知，发现社区新抽奖与福利时将自动提醒", false)
                    }

                    ActivityToggleDecision.RequestRuntimePostNotificationsPermission -> {
                        notificationDispatcher.markPostNotificationsRequestedOnce()
                        runCatching {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }.onFailure {
                            latestFeedback?.invoke("无法拉起系统权限弹窗，请在系统设置中开启通知权限", true)
                        }
                    }

                    ActivityToggleDecision.GuideToSystemNotificationSettings -> {
                        scope.launch {
                            controller.setNotificationsEnabled(false)
                        }
                        latestFeedback?.invoke("系统通知权限已关闭，请前往系统设置开启通知后再打开提醒", true)
                    }
                }
            }
        }
    }

    val effectiveEnabled = resolveEffectiveActivityNotificationsEnabled(
        preferenceEnabled = settings.notificationsEnabled,
        hasPostNotificationsPermission = hasSystemPermission,
    )

    return remember(
        effectiveEnabled,
        settings.notificationsEnabled,
        hasSystemPermission,
        runtimeStatus,
        toggleAction,
        openSettingsAction,
    ) {
        ActivityNotificationBindingState(
            isNotificationsEnabled = effectiveEnabled,
            isPreferenceEnabled = settings.notificationsEnabled,
            hasSystemNotificationPermission = hasSystemPermission,
            runtimeStatus = runtimeStatus,
            onToggleNotifications = toggleAction,
            onOpenSystemNotificationSettings = openSettingsAction,
        )
    }
}

/**
 * Prominent opt-in header card inside [ActivityScreen] controlling community activity notifications.
 */
@Composable
fun ActivityNotificationHeaderCard(
    notificationsEnabled: Boolean,
    onToggleNotifications: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val badgeShape = RoundedCornerShape(9.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                elevation = PulseElevation.Card,
                accentColor = if (notificationsEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(badgeShape)
                .background(PulseBrushes.brandSoftWash(if (notificationsEnabled) 0.18f else 0.08f))
                .border(
                    width = 0.8.dp,
                    color = MaterialTheme.colorScheme.primary.copy(
                        alpha = if (notificationsEnabled) 0.28f else 0.12f,
                    ),
                    shape = badgeShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (notificationsEnabled) {
                    Icons.Rounded.NotificationsActive
                } else {
                    Icons.Rounded.NotificationsOff
                },
                contentDescription = null,
                tint = if (notificationsEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(16.dp),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "新活动通知",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (notificationsEnabled) {
                    "已开启后台低频巡检，发现新抽奖与福利即时提醒"
                } else {
                    "默认关闭，开启后发现新社区活动自动推送通知"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Switch(
            checked = notificationsEnabled,
            onCheckedChange = onToggleNotifications,
        )
    }
}

fun openActivityAppNotificationSettings(context: Context) {
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
