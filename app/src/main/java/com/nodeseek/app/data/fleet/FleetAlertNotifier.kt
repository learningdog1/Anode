package com.nodeseek.app.data.fleet

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nodeseek.app.MainActivity
import com.nodeseek.app.R
import com.nodeseek.app.domain.fleet.FleetAlertTransition
import com.nodeseek.app.domain.fleet.FleetMonitorCheckReport
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.fleet.formatBytesBinary
import java.math.BigDecimal
import java.util.Locale

/**
 * Abstraction for dispatching server offline, recovery, VPS renewal reminder, and >=80% traffic alerts.
 */
interface FleetAlertSink {
    fun ensureChannels()
    fun notifyServerOffline(panelName: String, server: ProbeServerSnapshot)
    fun notifyServerRecovered(panelName: String, server: ProbeServerSnapshot)
    fun notifyRenewalReminder(asset: VpsAssetRecord, daysRemaining: Long, renewalCny: BigDecimal)
    fun notifyTrafficWarning(alert: FleetAlertTransition.TrafficThresholdReached) = Unit

    fun dispatchReport(report: FleetMonitorCheckReport) {
        report.offlineAlerts.forEach { alert ->
            notifyServerOffline(alert.panelName, alert.server)
        }
        report.recoveryAlerts.forEach { alert ->
            notifyServerRecovered(alert.panelName, alert.server)
        }
        report.renewalReminders.forEach { reminder ->
            notifyRenewalReminder(reminder.asset, reminder.daysRemaining, reminder.renewalCny)
        }
        report.trafficAlerts.forEach { alert ->
            notifyTrafficWarning(alert)
        }
    }
}

/**
 * Android notification dispatcher for My Servers (`fleet_server_offline` at [NotificationManager.IMPORTANCE_HIGH]
 * and `fleet_renewal_reminder` at [NotificationManager.IMPORTANCE_DEFAULT]).
 */
open class FleetAlertNotifier(
    context: Context,
) : FleetAlertSink {

    private val appContext: Context = context.applicationContext ?: context

    override fun ensureChannels() {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        val channels = listOf(
            NotificationChannel(
                CHANNEL_SERVER_OFFLINE,
                "服务器离线告警",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "当监控中的探针节点由在线变为离线时发出高优先级告警"
                enableVibration(true)
            },
            NotificationChannel(
                CHANNEL_RENEWAL_REMINDER,
                "VPS 到期续费提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "当资产账本中的服务器临近到期日时发出续费提醒"
            },
            NotificationChannel(
                CHANNEL_TRAFFIC_WARNING,
                "服务器流量预警",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "当服务器周期流量使用达到 80% 时发出预警与耗尽预测"
            },
        )
        manager.createNotificationChannels(channels)
    }

    override fun notifyServerOffline(panelName: String, server: ProbeServerSnapshot) {
        if (!canPostNotification()) return
        ensureChannels()

        val flagPrefix = server.resolvedFlagEmoji.takeIf { it.isNotBlank() }?.let { "$it " }.orEmpty()
        val title = "服务器离线告警 · $flagPrefix${server.name}"
        val summary = "所属面板：$panelName · 节点已断开连接，请检查服务状态"
        val notificationId = notificationIdForKey("offline:${server.probeKey}", BASE_ID_OFFLINE)

        val notification = NotificationCompat.Builder(appContext, CHANNEL_SERVER_OFFLINE)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(createOpenFleetPendingIntent(notificationId, EXTRA_TAB_PROBES))
            .build()

        notifySafely(notificationId, notification)
    }

    override fun notifyServerRecovered(panelName: String, server: ProbeServerSnapshot) {
        if (!canPostNotification()) return
        ensureChannels()

        val flagPrefix = server.resolvedFlagEmoji.takeIf { it.isNotBlank() }?.let { "$it " }.orEmpty()
        val title = "服务器已恢复在线 · $flagPrefix${server.name}"
        val summary = "所属面板：$panelName · 节点心跳已恢复正常"
        val notificationId = notificationIdForKey("recovery:${server.probeKey}", BASE_ID_RECOVERY)

        val notification = NotificationCompat.Builder(appContext, CHANNEL_RENEWAL_REMINDER)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(createOpenFleetPendingIntent(notificationId, EXTRA_TAB_PROBES))
            .build()

        notifySafely(notificationId, notification)
    }

    override fun notifyRenewalReminder(
        asset: VpsAssetRecord,
        daysRemaining: Long,
        renewalCny: BigDecimal,
    ) {
        if (!canPostNotification()) return
        ensureChannels()

        val countdownLabel = when {
            daysRemaining <= 0L -> "今日到期"
            daysRemaining == 1L -> "明日到期"
            else -> "剩余 $daysRemaining 天到期"
        }
        val title = "续费提醒 · ${asset.nickname} ($countdownLabel)"
        val body = buildString {
            if (asset.providerName.isNotBlank()) {
                append(asset.providerName)
                if (asset.region.isNotBlank()) append(" · ").append(asset.region)
                append("\n")
            }
            append("到期日：").append(asset.expiryDateIso)
            append(" · 续费约 ¥").append(renewalCny.toPlainString())
            if (asset.specsSummary.isNotBlank()) {
                append("（").append(asset.specsSummary).append("）")
            }
        }
        val notificationId = notificationIdForKey(
            "renewal:${asset.id}:${asset.expiryDateIso}",
            BASE_ID_RENEWAL,
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_RENEWAL_REMINDER)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText("到期日 ${asset.expiryDateIso} · 预计续费 ¥${renewalCny.toPlainString()}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(createOpenFleetPendingIntent(notificationId, EXTRA_TAB_ASSETS))
            .build()

        notifySafely(notificationId, notification)
    }

    override fun notifyTrafficWarning(alert: FleetAlertTransition.TrafficThresholdReached) {
        if (!canPostNotification()) return
        ensureChannels()

        val (title, summary, bigBody) = formatTrafficWarningCopy(alert)
        val notificationId = notificationIdForKey(
            "traffic:${alert.server.probeKey}:${alert.cycleStartDateIso}",
            BASE_ID_TRAFFIC,
        )
        val targetTab = if (alert.linkedAsset != null) EXTRA_TAB_ASSETS else EXTRA_TAB_PROBES

        val notification = NotificationCompat.Builder(appContext, CHANNEL_TRAFFIC_WARNING)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigBody))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(createOpenFleetPendingIntent(notificationId, targetTab))
            .build()

        notifySafely(notificationId, notification)
    }

    private fun createOpenFleetPendingIntent(requestCode: Int, targetTab: String): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = ACTION_OPEN_FLEET
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_FLEET_TAB, targetTab)
        }
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPostNotification(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }

    private fun notifySafely(notificationId: Int, notification: android.app.Notification) {
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
        const val CHANNEL_SERVER_OFFLINE = "fleet_server_offline"
        const val CHANNEL_RENEWAL_REMINDER = "fleet_renewal_reminder"
        const val CHANNEL_TRAFFIC_WARNING = "fleet_traffic_warning"

        const val ACTION_OPEN_FLEET = "com.nodeseek.app.action.OPEN_FLEET"
        const val EXTRA_FLEET_TAB = "com.nodeseek.app.extra.FLEET_TAB"
        const val EXTRA_TAB_PROBES = "probes"
        const val EXTRA_TAB_ASSETS = "assets"

        private const val BASE_ID_OFFLINE = 60_000
        private const val BASE_ID_RECOVERY = 64_000
        private const val BASE_ID_RENEWAL = 68_000
        private const val BASE_ID_TRAFFIC = 72_000

        internal fun formatTrafficWarningCopy(
            alert: FleetAlertTransition.TrafficThresholdReached,
        ): Triple<String, String, String> {
            val flagPrefix = alert.server.resolvedFlagEmoji.takeIf { it.isNotBlank() }?.let { "$it " }.orEmpty()
            val displayName = alert.linkedAsset?.nickname?.takeIf { it.isNotBlank() } ?: alert.server.name
            val pctInt = (alert.usageRatio * 100.0).toInt().coerceAtLeast(80)
            val title = "流量预警 · $flagPrefix$displayName 已用 ${pctInt}%"

            val usedLabel = formatBytesBinary(alert.usedBytes)
            val limitLabel = formatBytesBinary(alert.limitBytes)
            val forecastSuffix = alert.exhaustionForecastText?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            val summary = "已用 $usedLabel / $limitLabel$forecastSuffix"

            val bigBody = buildString {
                append("周期流量已用 ")
                append(usedLabel).append(" / ").append(limitLabel)
                append(" (").append(String.format(Locale.US, "%.1f%%", alert.usageRatio * 100.0)).append(")")
                if (!alert.exhaustionForecastText.isNullOrBlank()) {
                    append("\n").append(alert.exhaustionForecastText)
                }
                append("\n下次重置日：").append(alert.nextResetDateIso)
                if (alert.panelName.isNotBlank()) {
                    append(" · 面板：").append(alert.panelName)
                }
            }
            return Triple(title, summary, bigBody)
        }

        internal fun notificationIdForKey(key: String, baseOffset: Int): Int {
            val masked = key.hashCode() and 0x00FF_FFFF
            return baseOffset + (masked % 3_500)
        }
    }
}
