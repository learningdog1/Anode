package com.nodeseek.app.data.restock

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nodeseek.app.MainActivity
import com.nodeseek.app.R
import com.nodeseek.app.domain.restock.RestockEventRecord

/**
 * Manages the high-priority `vps_restock_urgent` notification channel ("VPS 补货抢购提醒")
 * and dispatches heads-up notifications whose primary content tap opens the unwrapped
 * merchant checkout URL directly via `Intent.ACTION_VIEW`, alongside an action button
 * to open the in-app restock monitor.
 */
open class RestockNotificationHelper(
    context: Context,
) {
    private val appContext: Context = context.applicationContext ?: context

    open fun ensureChannel() {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_VPS_RESTOCK_URGENT,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "当蹲守的 VPS 或抢购商品检测到补货可下单时立即发出高优先级弹窗提醒"
            enableVibration(true)
            enableLights(true)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    open fun hasPostNotificationsPermission(): Boolean {
        val runtimeGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return runtimeGranted && NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }

    open fun createDirectCheckoutIntent(resolvedOrderUrl: String): Intent {
        val targetUrl = MerchantLinkDetector.resolveOrderUrl(resolvedOrderUrl)
        return Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    open fun createOpenInAppIntent(
        watchId: String,
        eventId: String,
    ): Intent = Intent(appContext, MainActivity::class.java).apply {
        action = ACTION_OPEN_RESTOCK_MONITOR
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(EXTRA_RESTOCK_WATCH_ID, watchId)
        putExtra(EXTRA_RESTOCK_EVENT_ID, eventId)
    }

    open fun cancelNotificationForWatch(watchId: String) {
        if (watchId.isBlank()) return
        runCatching {
            NotificationManagerCompat.from(appContext).cancel(notificationIdForWatch(watchId))
        }
    }

    open fun postRestockUrgentNotifications(events: List<RestockEventRecord>) {
        if (events.isEmpty() || !hasPostNotificationsPermission()) return
        ensureChannel()

        val individualTargets = events.take(MAX_INDIVIDUAL_RESTOCK_NOTIFICATIONS)
        for (event in individualTargets) {
            postRestockUrgentNotification(event)
        }
        if (events.size > MAX_INDIVIDUAL_RESTOCK_NOTIFICATIONS) {
            postRestockSummaryNotification(events)
        }
    }

    open fun postRestockSummaryNotification(events: List<RestockEventRecord>) {
        if (events.isEmpty() || !hasPostNotificationsPermission()) return
        ensureChannel()

        val count = events.size
        val title = "发现 $count 款 VPS 补货可抢"
        val previewLines = events.take(5).map { item ->
            val priceSuffix = item.priceHint.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            "${item.watchTitle}$priceSuffix"
        }
        val summaryLine = previewLines.firstOrNull().orEmpty().ifBlank { "点击打开补货监控查看全部可抢商品" }
        val bigText = buildString {
            append(previewLines.joinToString("\n• ", prefix = "• "))
            if (count > previewLines.size) {
                append("\n…等共 $count 款已补货商品")
            }
        }
        val openMonitorIntent = createOpenInAppIntent(watchId = "", eventId = "")
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            ID_RESTOCK_SUMMARY,
            openMonitorIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_VPS_RESTOCK_URGENT)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(summaryLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(GROUP_VPS_RESTOCK)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notifySafely(ID_RESTOCK_SUMMARY, notification)
    }

    open fun postRestockUrgentNotification(event: RestockEventRecord) {
        if (!hasPostNotificationsPermission()) return
        ensureChannel()

        val notificationId = notificationIdForWatch(event.watchItemId)
        val checkoutUrl = MerchantLinkDetector.resolveOrderUrl(event.resolvedOrderUrl)
        val inAppIntent = createOpenInAppIntent(
            watchId = event.watchItemId,
            eventId = event.id,
        )
        val inAppPendingIntent = PendingIntent.getActivity(
            appContext,
            notificationId + ACTION_REQUEST_CODE_OFFSET,
            inAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val hasHttpCheckoutUrl = checkoutUrl.startsWith("http://", ignoreCase = true) ||
            checkoutUrl.startsWith("https://", ignoreCase = true)
        val primaryPendingIntent = if (hasHttpCheckoutUrl) {
            val directCheckoutIntent = createDirectCheckoutIntent(checkoutUrl)
            PendingIntent.getActivity(
                appContext,
                notificationId,
                directCheckoutIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            inAppPendingIntent
        }

        val title = "VPS 补货可抢 · ${event.watchTitle}"
        val summaryLine = buildString {
            if (event.priceHint.isNotBlank()) {
                append(event.priceHint)
                append(" · ")
            }
            append(event.panelType.displayName)
            append(" · 点击直达下单页")
        }
        val bigText = buildString {
            append(event.watchTitle)
            if (event.priceHint.isNotBlank()) {
                append("（")
                append(event.priceHint)
                append("）")
            }
            append("\n已从缺货恢复为有货可抢，点击通知直接打开结账页面。")
            if (event.evidenceSummary.isNotBlank()) {
                append("\n依据：")
                append(event.evidenceSummary)
            }
            append("\n链接：")
            append(checkoutUrl)
        }

        val notification = NotificationCompat.Builder(appContext, CHANNEL_VPS_RESTOCK_URGENT)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(summaryLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(GROUP_VPS_RESTOCK)
            .setAutoCancel(true)
            .setContentIntent(primaryPendingIntent)
            .addAction(0, "在应用内查看", inAppPendingIntent)
            .build()

        notifySafely(notificationId, notification)
    }

    private fun notifySafely(notificationId: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            NotificationManagerCompat.from(appContext).notify(notificationId, notification)
        }
    }

    companion object {
        const val CHANNEL_VPS_RESTOCK_URGENT = "vps_restock_urgent"
        const val CHANNEL_NAME = "VPS 补货抢购提醒"

        const val ACTION_OPEN_RESTOCK_MONITOR = "com.nodeseek.app.action.OPEN_RESTOCK_MONITOR"
        const val EXTRA_RESTOCK_WATCH_ID = "com.nodeseek.app.extra.RESTOCK_WATCH_ID"
        const val EXTRA_RESTOCK_EVENT_ID = "com.nodeseek.app.extra.RESTOCK_EVENT_ID"
        const val GROUP_VPS_RESTOCK = "com.nodeseek.app.group.VPS_RESTOCK"
        const val MAX_INDIVIDUAL_RESTOCK_NOTIFICATIONS = 3

        private const val BASE_ID_RESTOCK = 60_000
        private const val ACTION_REQUEST_CODE_OFFSET = 5_000
        const val ID_RESTOCK_SUMMARY = 69_100

        fun isRestockMonitorIntent(intent: Intent?): Boolean =
            intent?.action == ACTION_OPEN_RESTOCK_MONITOR

        internal fun notificationIdForWatch(watchId: String): Int {
            val masked = watchId.hashCode() and 0x00FF_FFFF
            return BASE_ID_RESTOCK + (masked % 4_500)
        }
    }
}
