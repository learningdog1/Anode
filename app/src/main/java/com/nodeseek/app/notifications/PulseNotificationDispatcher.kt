package com.nodeseek.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nodeseek.app.MainActivity
import com.nodeseek.app.R
import com.nodeseek.app.domain.model.NotificationKind
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.radar.LocalAlertRecord
import com.nodeseek.app.domain.radar.NotificationTapTarget
import com.nodeseek.app.domain.ssh.SshExecutionRecord
import com.nodeseek.app.domain.ssh.SshRunStatus
import com.nodeseek.app.navigation.AppDestination
import com.nodeseek.app.navigation.OverseasIntelDetailRoute
import com.nodeseek.app.navigation.OverseasIntelRoute
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Manages NodePulse notification channels, Android 13+ permission & environment diagnostics
 * (Battery Optimization / System Do Not Disturb), notification emission, and Intent tap routing.
 */
open class PulseNotificationDispatcher(
    context: Context,
) {
    private val appContext: Context = context.applicationContext ?: context

    open fun ensureChannels() {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        val channels = listOf(
            NotificationChannel(
                CHANNEL_KEYWORD_ALERTS,
                "关键词雷达提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "当新发布主题命中本地关键词监控规则时通知"
            },
            NotificationChannel(
                CHANNEL_TRACKED_REPLIES,
                "关注帖新回复",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "当已开启追更的帖子收到新楼层回复时通知"
            },
            NotificationChannel(
                CHANNEL_FORUM_INTERACTIONS,
                "社区回复与提及",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "收到新的帖子回复或 @提及时通知"
            },
            NotificationChannel(
                CHANNEL_DIRECT_MESSAGES,
                "社区私信",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "收到新的社区私信时通知"
            },
            NotificationChannel(
                CHANNEL_SSH_BENCHMARK_ONGOING,
                "SSH 后台测机保活",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "SSH 一键测机在后台持续运行时的进度状态通知"
                setShowBadge(false)
            },
            NotificationChannel(
                CHANNEL_SSH_BENCHMARK_RESULT,
                "SSH 测机完成提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "当后台 SSH 测评脚本运行结束时通知"
            },
            NotificationChannel(
                CHANNEL_OVERSEAS_PROMOS,
                CHANNEL_OVERSEAS_PROMOS_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "当海外情报监测到新的海外 VPS 与服务器促销帖时主动推送通知"
                enableVibration(true)
                setShowBadge(true)
            },
        )
        manager.createNotificationChannels(channels)
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

    open fun hasRequestedPostNotificationsOnce(): Boolean {
        return runCatching {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_POST_NOTIF_REQUESTED_ONCE, false)
        }.getOrDefault(false)
    }

    open fun markPostNotificationsRequestedOnce() {
        runCatching {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_POST_NOTIF_REQUESTED_ONCE, true)
                .apply()
        }
    }

    open fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = appContext.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
    }

    open fun isSystemDoNotDisturbActive(): Boolean {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return false
        return when (manager.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            NotificationManager.INTERRUPTION_FILTER_NONE,
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
            -> true
            else -> false
        }
    }

    open fun postKeywordHitNotification(record: LocalAlertRecord) {
        if (!canPostNotification()) return
        ensureChannels()

        val target = NotificationTapTarget.OpenTopic(
            topicId = record.topicId,
            initialFloor = record.targetFloor,
            titleHint = record.topicTitle,
            alertDedupKey = record.dedupKey,
        )
        val notificationId = notificationIdForKey("kw:${record.topicId}", BASE_ID_KEYWORD)
        val contentTitle = buildString {
            append("关键词命中")
            if (record.matchedKeyword.isNotBlank()) {
                append(" · ")
                append(record.matchedKeyword)
            }
        }
        val bodyText = buildString {
            append(record.topicTitle)
            val metaParts = buildList {
                if (record.categoryName.isNotBlank()) add(record.categoryName)
                if (record.actorName.isNotBlank()) add(record.actorName)
            }
            if (metaParts.isNotEmpty()) {
                append("\n")
                append(metaParts.joinToString(" · "))
            }
            if (record.summarySnippet.isNotBlank()) {
                append("\n")
                append(record.summarySnippet)
            }
        }

        val notification = NotificationCompat.Builder(appContext, CHANNEL_KEYWORD_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(contentTitle)
            .setContentText(record.topicTitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(createTapPendingIntent(target, notificationId))
            .build()

        notifySafely(notificationId, notification)
    }

    open fun postTrackedReplyNotification(
        record: LocalAlertRecord,
        newReplyCount: Int = 1,
    ) {
        if (!canPostNotification()) return
        ensureChannels()

        val target = NotificationTapTarget.OpenTopic(
            topicId = record.topicId,
            initialFloor = record.targetFloor,
            titleHint = record.topicTitle,
            alertDedupKey = record.dedupKey,
        )
        val notificationId = notificationIdForKey("tracked:${record.topicId}", BASE_ID_TRACKED)
        val safeCount = newReplyCount.coerceAtLeast(1)
        val floorBadge = if (record.targetFloor > 0) " · #${record.targetFloor}" else ""
        val contentTitle = if (safeCount > 1) {
            "追更帖子有 $safeCount 条新回复$floorBadge"
        } else {
            "追更帖子有新回复$floorBadge"
        }
        val previewLine = buildString {
            if (record.actorName.isNotBlank()) {
                append(record.actorName)
                append("：")
            }
            append(record.summarySnippet.ifBlank { record.topicTitle })
        }
        val bigText = "${record.topicTitle}\n$previewLine"

        val notification = NotificationCompat.Builder(appContext, CHANNEL_TRACKED_REPLIES)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(contentTitle)
            .setContentText(previewLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(createTapPendingIntent(target, notificationId))
            .build()

        notifySafely(notificationId, notification)
    }

    open fun postForumInteractionNotification(
        itemId: Long,
        isMention: Boolean,
        senderName: String,
        topicId: Long,
        topicTitle: String,
        floorNumber: Int,
        excerpt: String,
    ) {
        if (!canPostNotification()) return
        ensureChannels()

        val target = NotificationTapTarget.OpenTopic(
            topicId = topicId,
            initialFloor = floorNumber,
            titleHint = topicTitle,
            alertDedupKey = null,
            notificationKind = if (isMention) NotificationKind.MENTIONS else NotificationKind.REPLIES,
            notificationId = itemId,
        )
        val key = if (topicId > 0L && floorNumber > 0) {
            "interaction:$topicId:$floorNumber"
        } else {
            "interaction:${if (isMention) "m" else "r"}:$itemId"
        }
        val notificationId = notificationIdForKey(key, BASE_ID_INTERACTION)
        val cleanSender = senderName.ifBlank { "社区用户" }
        val floorSuffix = if (floorNumber > 0) " (#$floorNumber)" else ""
        val contentTitle = if (isMention) {
            "$cleanSender 在帖子中 @了你$floorSuffix"
        } else {
            "$cleanSender 回复了你$floorSuffix"
        }
        val cleanExcerpt = excerpt.trim()
        val bigText = if (cleanExcerpt.isNotEmpty()) {
            "$topicTitle\n$cleanExcerpt"
        } else {
            topicTitle
        }

        val notification = NotificationCompat.Builder(appContext, CHANNEL_FORUM_INTERACTIONS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(contentTitle)
            .setContentText(topicTitle.ifBlank { cleanExcerpt })
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(createTapPendingIntent(target, notificationId))
            .build()

        notifySafely(notificationId, notification)
    }

    open fun postDirectMessageNotification(
        peerUid: Long,
        peerName: String,
        messageId: Long,
        previewText: String,
    ) {
        if (!canPostNotification()) return
        ensureChannels()

        val cleanPeerName = peerName.ifBlank { "用户 #$peerUid" }
        val target = NotificationTapTarget.OpenDirectMessage(
            peerUid = peerUid,
            peerName = cleanPeerName,
            peerAvatarUrl = if (peerUid > 0L) "https://www.nodeseek.com/avatar/$peerUid.png" else "",
        )
        val notificationId = notificationIdForKey("dm:$peerUid", BASE_ID_DM)
        val cleanPreview = previewText.trim().ifBlank { "发来一条新私信" }

        val notification = NotificationCompat.Builder(appContext, CHANNEL_DIRECT_MESSAGES)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("来自 $cleanPeerName 的私信")
            .setContentText(cleanPreview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(cleanPreview))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(createTapPendingIntent(target, notificationId))
            .build()

        notifySafely(notificationId, notification)
    }

    open fun buildSshBenchmarkOngoingNotification(
        serverAlias: String,
        presetName: String,
        statusLine: String,
        stopPendingIntent: PendingIntent? = null,
    ): android.app.Notification {
        ensureChannels()
        val tapIntent = createTapPendingIntent(
            target = NotificationTapTarget.OpenLiteSsh,
            requestCode = ID_SSH_ONGOING,
        )
        val cleanAlias = serverAlias.ifBlank { "VPS" }
        val cleanPreset = presetName.ifBlank { "测机脚本" }
        val builder = NotificationCompat.Builder(appContext, CHANNEL_SSH_BENCHMARK_ONGOING)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("正在测机 · $cleanAlias ($cleanPreset)")
            .setContentText(statusLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$statusLine\n点击返回轻量 SSH 测机控制台"))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .setContentIntent(tapIntent)
        if (stopPendingIntent != null) {
            builder.addAction(0, "停止测机", stopPendingIntent)
        }
        return builder.build()
    }

    open fun postSshBenchmarkCompletedNotification(
        record: SshExecutionRecord,
        errorDetail: String? = null,
    ) {
        if (!canPostNotification()) return
        ensureChannels()

        val notificationId = notificationIdForKey("ssh:${record.id}", BASE_ID_SSH_RESULT)
        val cleanAlias = record.serverAlias.ifBlank { record.serverHost.ifBlank { "VPS" } }
        val durationSec = (record.durationMillis / 1000L).coerceAtLeast(0L)
        val durationLabel = if (durationSec >= 60L) {
            "${durationSec / 60L}分${durationSec % 60L}秒"
        } else {
            "${durationSec}秒"
        }
        val title = when (record.status) {
            SshRunStatus.SUCCEEDED -> "测机完成 · $cleanAlias"
            SshRunStatus.FAILED -> "测机完成（异常退出）· $cleanAlias"
            SshRunStatus.CANCELLED -> "测机已停止 · $cleanAlias"
            SshRunStatus.RUNNING -> "测机进行中 · $cleanAlias"
        }
        val summary = when (record.status) {
            SshRunStatus.SUCCEEDED -> "「${record.presetName}」已完成（耗时 $durationLabel），点击查看报告"
            SshRunStatus.FAILED -> {
                val reason = errorDetail?.takeIf { it.isNotBlank() }
                    ?: record.errorMessage?.takeIf { it.isNotBlank() }
                    ?: record.exitCode?.let { "退出码 $it" }
                    ?: "执行失败"
                "「${record.presetName}」结束（$reason · 耗时 $durationLabel）"
            }
            SshRunStatus.CANCELLED -> "「${record.presetName}」已手动停止"
            SshRunStatus.RUNNING -> "「${record.presetName}」正在运行"
        }

        val notification = NotificationCompat.Builder(appContext, CHANNEL_SSH_BENCHMARK_RESULT)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$summary\n点击打开轻量 SSH 查看完整 ANSI 输出或一键发布测评帖"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(createTapPendingIntent(NotificationTapTarget.OpenLiteSsh, notificationId))
            .build()

        notifySafely(notificationId, notification)
    }

    open fun postOverseasPromoNotifications(offers: List<OverseasDealOffer>) {
        val validOffers = offers.filter { it.id.isNotBlank() }
        if (validOffers.isEmpty() || !canPostNotification()) return
        ensureChannels()

        val individualTargets = validOffers
            .take(MAX_INDIVIDUAL_OVERSEAS_NOTIFICATIONS)
            .asReversed()
        for (offer in individualTargets) {
            postOverseasPromoNotification(offer)
        }

        if (validOffers.size > MAX_INDIVIDUAL_OVERSEAS_NOTIFICATIONS) {
            postOverseasPromoSummaryNotification(validOffers)
        }
    }

    open fun postOverseasPromoNotification(offer: OverseasDealOffer) {
        val cleanId = offer.id.trim()
        if (cleanId.isEmpty() || !canPostNotification()) return
        ensureChannels()

        val notificationId = notificationIdForKey("overseas:$cleanId", BASE_ID_OVERSEAS_PROMO)
        val contentTitle = formatOverseasPromoNotificationTitle(offer)
        val cleanSummary = formatOverseasPromoNotificationSummary(offer)
        val cleanTitle = offer.title.trim().ifBlank { offer.originalTitle.trim() }
        val bigText = if (cleanTitle.isNotEmpty() && !cleanSummary.equals(cleanTitle, ignoreCase = true)) {
            "$cleanTitle\n$cleanSummary"
        } else {
            cleanSummary
        }

        val tapIntent = createOverseasPromoTapIntent(
            context = appContext,
            offerId = cleanId,
            title = cleanTitle,
            sourceUrl = offer.sourceLink.trim(),
        )
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_OVERSEAS_PROMOS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(contentTitle)
            .setContentText(cleanSummary)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(contentTitle)
                    .bigText(bigText),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setGroup(GROUP_OVERSEAS_PROMOS)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notifySafely(notificationId, notification)
    }

    protected open fun postOverseasPromoSummaryNotification(offers: List<OverseasDealOffer>) {
        if (offers.isEmpty() || !canPostNotification()) return
        val count = offers.size
        val title = "海外情报新增 $count 条促销贴"
        val previewLines = offers.take(5).map { item ->
            val merchantPrefix = item.merchant.trim().takeIf { it.isNotEmpty() }?.let { "[$it] " }.orEmpty()
            "$merchantPrefix${item.title.trim().ifBlank { item.originalTitle.trim() }}"
        }
        val summaryLine = previewLines.firstOrNull().orEmpty().ifBlank { "点击查看最新海外 VPS 促销情报" }
        val bigText = buildString {
            append(previewLines.joinToString("\n• ", prefix = "• "))
            if (count > previewLines.size) {
                append("\n…等共 $count 条最新促销情报")
            }
        }

        val tapIntent = createOverseasPromoTapIntent(context = appContext, offerId = null)
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            ID_OVERSEAS_PROMO_SUMMARY,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_OVERSEAS_PROMOS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(summaryLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setGroup(GROUP_OVERSEAS_PROMOS)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notifySafely(ID_OVERSEAS_PROMO_SUMMARY, notification)
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

    private fun createTapPendingIntent(
        target: NotificationTapTarget,
        requestCode: Int,
    ): PendingIntent {
        val intent = createTapIntent(appContext, target)
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_KEYWORD_ALERTS = "pulse_keyword_alerts"
        const val CHANNEL_TRACKED_REPLIES = "pulse_tracked_replies"
        const val CHANNEL_FORUM_INTERACTIONS = "pulse_forum_interactions"
        const val CHANNEL_DIRECT_MESSAGES = "pulse_direct_messages"
        const val CHANNEL_SSH_BENCHMARK_ONGOING = "pulse_ssh_benchmark_ongoing"
        const val CHANNEL_SSH_BENCHMARK_RESULT = "pulse_ssh_benchmark_result"
        const val CHANNEL_OVERSEAS_PROMOS = "pulse_overseas_promos"
        const val CHANNEL_OVERSEAS_PROMOS_NAME = "海外情报促销推送"

        private const val PREFS_NAME = "nodepulse_notification_prefs"
        private const val KEY_POST_NOTIF_REQUESTED_ONCE = "post_notifications_requested_once"

        const val ACTION_OPEN_OVERSEAS_PROMO = "com.nodeseek.app.action.OPEN_OVERSEAS_PROMO"
        private const val EXTRA_OVERSEAS_PAYLOAD = "com.nodeseek.app.extra.OVERSEAS_PROMO_PAYLOAD"
        private const val EXTRA_OVERSEAS_OFFER_ID = "com.nodeseek.app.extra.OVERSEAS_OFFER_ID"
        private const val EXTRA_OVERSEAS_TITLE = "com.nodeseek.app.extra.OVERSEAS_TITLE"
        private const val EXTRA_OVERSEAS_SOURCE_URL = "com.nodeseek.app.extra.OVERSEAS_SOURCE_URL"
        private const val GROUP_OVERSEAS_PROMOS = "com.nodeseek.app.group.OVERSEAS_PROMOS"
        const val MAX_INDIVIDUAL_OVERSEAS_NOTIFICATIONS = 3

        const val ACTION_OPEN_RADAR_TARGET = "com.nodeseek.app.action.OPEN_RADAR_TARGET"
        private const val EXTRA_TARGET_PAYLOAD = "com.nodeseek.app.extra.RADAR_TARGET_PAYLOAD"
        private const val EXTRA_TARGET_KIND = "com.nodeseek.app.extra.RADAR_TARGET_KIND"
        private const val EXTRA_TOPIC_ID = "com.nodeseek.app.extra.RADAR_TOPIC_ID"
        private const val EXTRA_INITIAL_FLOOR = "com.nodeseek.app.extra.RADAR_INITIAL_FLOOR"
        private const val EXTRA_TITLE_HINT = "com.nodeseek.app.extra.RADAR_TITLE_HINT"
        private const val EXTRA_ALERT_DEDUP_KEY = "com.nodeseek.app.extra.RADAR_ALERT_DEDUP_KEY"
        private const val EXTRA_NOTIFICATION_KIND = "com.nodeseek.app.extra.RADAR_NOTIFICATION_KIND"
        private const val EXTRA_NOTIFICATION_ID = "com.nodeseek.app.extra.RADAR_NOTIFICATION_ID"
        private const val EXTRA_PEER_UID = "com.nodeseek.app.extra.RADAR_PEER_UID"
        private const val EXTRA_PEER_NAME = "com.nodeseek.app.extra.RADAR_PEER_NAME"
        private const val EXTRA_PEER_AVATAR = "com.nodeseek.app.extra.RADAR_PEER_AVATAR"

        private const val KIND_TOPIC = "topic"
        private const val KIND_DM = "dm"
        private const val KIND_ALERTS = "radar_alerts"
        private const val KIND_LITE_SSH = "lite_ssh"

        private const val BASE_ID_KEYWORD = 10_000
        private const val BASE_ID_TRACKED = 20_000
        private const val BASE_ID_INTERACTION = 30_000
        private const val BASE_ID_DM = 40_000
        private const val BASE_ID_SSH_RESULT = 50_000
        const val ID_SSH_ONGOING = 59_100
        internal const val BASE_ID_OVERSEAS_PROMO = 70_000
        const val ID_OVERSEAS_PROMO_SUMMARY = 79_100

        internal fun notificationIdForKey(key: String, baseOffset: Int): Int {
            val masked = key.hashCode() and 0x00FF_FFFF
            return baseOffset + (masked % 9_000)
        }

        fun createTapIntent(context: Context, target: NotificationTapTarget): Intent {
            return Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_RADAR_TARGET
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_TARGET_PAYLOAD, encodeTapTarget(target))
                when (target) {
                    is NotificationTapTarget.OpenTopic -> {
                        putExtra(EXTRA_TARGET_KIND, KIND_TOPIC)
                        putExtra(EXTRA_TOPIC_ID, target.topicId)
                        putExtra(EXTRA_INITIAL_FLOOR, target.initialFloor)
                        putExtra(EXTRA_TITLE_HINT, target.titleHint)
                        if (target.alertDedupKey != null) {
                            putExtra(EXTRA_ALERT_DEDUP_KEY, target.alertDedupKey)
                        }
                        if (target.notificationKind != null) {
                            putExtra(EXTRA_NOTIFICATION_KIND, target.notificationKind.name)
                        }
                        if (target.notificationId > 0L) {
                            putExtra(EXTRA_NOTIFICATION_ID, target.notificationId)
                        }
                    }

                    is NotificationTapTarget.OpenDirectMessage -> {
                        putExtra(EXTRA_TARGET_KIND, KIND_DM)
                        putExtra(EXTRA_PEER_UID, target.peerUid)
                        putExtra(EXTRA_PEER_NAME, target.peerName)
                        if (target.peerAvatarUrl.isNotBlank()) {
                            putExtra(EXTRA_PEER_AVATAR, target.peerAvatarUrl)
                        }
                    }

                    NotificationTapTarget.OpenRadarAlerts -> {
                        putExtra(EXTRA_TARGET_KIND, KIND_ALERTS)
                    }

                    NotificationTapTarget.OpenLiteSsh -> {
                        putExtra(EXTRA_TARGET_KIND, KIND_LITE_SSH)
                    }
                }
            }
        }

        fun parseTapTarget(intent: Intent?): NotificationTapTarget? {
            if (intent == null) return null
            decodeTapTarget(intent.getStringExtra(EXTRA_TARGET_PAYLOAD))?.let { return it }

            return when (intent.getStringExtra(EXTRA_TARGET_KIND)) {
                KIND_TOPIC -> {
                    val topicId = intent.getLongExtra(EXTRA_TOPIC_ID, 0L)
                    if (topicId <= 0L) null else NotificationTapTarget.OpenTopic(
                        topicId = topicId,
                        initialFloor = intent.getIntExtra(EXTRA_INITIAL_FLOOR, 0).coerceAtLeast(0),
                        titleHint = intent.getStringExtra(EXTRA_TITLE_HINT).orEmpty(),
                        alertDedupKey = intent.getStringExtra(EXTRA_ALERT_DEDUP_KEY)?.takeIf { it.isNotBlank() },
                        notificationKind = intent.getStringExtra(EXTRA_NOTIFICATION_KIND)
                            ?.let { name -> runCatching { NotificationKind.valueOf(name) }.getOrNull() },
                        notificationId = intent.getLongExtra(EXTRA_NOTIFICATION_ID, 0L).coerceAtLeast(0L),
                    )
                }

                KIND_DM -> {
                    val peerUid = intent.getLongExtra(EXTRA_PEER_UID, 0L)
                    if (peerUid <= 0L) null else NotificationTapTarget.OpenDirectMessage(
                        peerUid = peerUid,
                        peerName = intent.getStringExtra(EXTRA_PEER_NAME).orEmpty(),
                        peerAvatarUrl = intent.getStringExtra(EXTRA_PEER_AVATAR).orEmpty(),
                    )
                }

                KIND_ALERTS -> NotificationTapTarget.OpenRadarAlerts
                KIND_LITE_SSH -> NotificationTapTarget.OpenLiteSsh
                else -> null
            }
        }

        fun clearTapTargetExtras(intent: Intent?) {
            if (intent == null) return
            if (intent.action == ACTION_OPEN_RADAR_TARGET) {
                intent.action = null
            }
            intent.removeExtra(EXTRA_TARGET_PAYLOAD)
            intent.removeExtra(EXTRA_TARGET_KIND)
            intent.removeExtra(EXTRA_TOPIC_ID)
            intent.removeExtra(EXTRA_INITIAL_FLOOR)
            intent.removeExtra(EXTRA_TITLE_HINT)
            intent.removeExtra(EXTRA_ALERT_DEDUP_KEY)
            intent.removeExtra(EXTRA_NOTIFICATION_KIND)
            intent.removeExtra(EXTRA_NOTIFICATION_ID)
            intent.removeExtra(EXTRA_PEER_UID)
            intent.removeExtra(EXTRA_PEER_NAME)
            intent.removeExtra(EXTRA_PEER_AVATAR)
        }

        internal fun encodeTapTarget(target: NotificationTapTarget): String {
            return when (target) {
                is NotificationTapTarget.OpenTopic -> {
                    val params = buildList {
                        add("topicId=${target.topicId}")
                        add("floor=${target.initialFloor.coerceAtLeast(0)}")
                        add("title=${urlEncode(target.titleHint)}")
                        target.alertDedupKey?.takeIf { it.isNotBlank() }?.let {
                            add("dedup=${urlEncode(it)}")
                        }
                        target.notificationKind?.let { kind ->
                            add("nKind=${urlEncode(kind.name)}")
                        }
                        if (target.notificationId > 0L) {
                            add("nId=${target.notificationId}")
                        }
                    }
                    "nodepulse://radar/topic?${params.joinToString("&")}"
                }

                is NotificationTapTarget.OpenDirectMessage -> {
                    val params = buildList {
                        add("peerUid=${target.peerUid}")
                        add("peerName=${urlEncode(target.peerName)}")
                        if (target.peerAvatarUrl.isNotBlank()) {
                            add("peerAvatar=${urlEncode(target.peerAvatarUrl)}")
                        }
                    }
                    "nodepulse://radar/dm?${params.joinToString("&")}"
                }

                NotificationTapTarget.OpenRadarAlerts -> "nodepulse://radar/alerts"
                NotificationTapTarget.OpenLiteSsh -> "nodepulse://radar/lite_ssh"
            }
        }

        internal fun decodeTapTarget(encoded: String?): NotificationTapTarget? {
            if (encoded.isNullOrBlank()) return null
            val trimmed = encoded.trim()
            if (!trimmed.startsWith("nodepulse://radar/")) return null

            val pathAndQuery = trimmed.removePrefix("nodepulse://radar/")
            val qIdx = pathAndQuery.indexOf('?')
            val path = if (qIdx >= 0) pathAndQuery.substring(0, qIdx) else pathAndQuery
            val query = if (qIdx >= 0) pathAndQuery.substring(qIdx + 1) else ""
            val params = parseQueryMap(query)

            return when (path) {
                "topic" -> {
                    val topicId = params["topicId"]?.toLongOrNull()?.takeIf { it > 0L } ?: return null
                    val floor = params["floor"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    val title = params["title"].orEmpty()
                    val dedup = params["dedup"]?.takeIf { it.isNotBlank() }
                    val nKind = params["nKind"]?.let { name ->
                        runCatching { NotificationKind.valueOf(name) }.getOrNull()
                    }
                    val nId = params["nId"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    NotificationTapTarget.OpenTopic(
                        topicId = topicId,
                        initialFloor = floor,
                        titleHint = title,
                        alertDedupKey = dedup,
                        notificationKind = nKind,
                        notificationId = nId,
                    )
                }

                "dm" -> {
                    val peerUid = params["peerUid"]?.toLongOrNull()?.takeIf { it > 0L } ?: return null
                    val peerName = params["peerName"].orEmpty()
                    val peerAvatar = params["peerAvatar"].orEmpty()
                    NotificationTapTarget.OpenDirectMessage(
                        peerUid = peerUid,
                        peerName = peerName,
                        peerAvatarUrl = peerAvatar,
                    )
                }

                "alerts" -> NotificationTapTarget.OpenRadarAlerts
                "lite_ssh" -> NotificationTapTarget.OpenLiteSsh
                else -> null
            }
        }

        private fun parseQueryMap(rawQuery: String): Map<String, String> {
            if (rawQuery.isBlank()) return emptyMap()
            val map = LinkedHashMap<String, String>()
            for (pair in rawQuery.split('&')) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val key = urlDecode(pair.substring(0, eq))
                val value = urlDecode(pair.substring(eq + 1))
                map[key] = value
            }
            return map
        }

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())

        private fun urlDecode(value: String): String =
            runCatching {
                URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            }.getOrDefault(value)

        private val TELEGRAM_WORD_REGEX = Regex("""\btelegram\b""", RegexOption.IGNORE_CASE)

        internal fun formatOverseasPromoNotificationTitle(offer: OverseasDealOffer): String {
            val cleanTitle = offer.title.trim()
                .ifBlank { offer.originalTitle.trim() }
                .ifBlank { "海外 VPS 新促销情报" }
            val cleanMerchant = offer.merchant.trim()
            return if (cleanMerchant.isNotEmpty() && !cleanTitle.contains(cleanMerchant, ignoreCase = true)) {
                "海外促销 · $cleanMerchant：$cleanTitle"
            } else {
                "海外促销 · $cleanTitle"
            }
        }

        internal fun formatOverseasPromoNotificationSummary(offer: OverseasDealOffer): String {
            val cleaned = offer.summary
                .replace(TELEGRAM_WORD_REGEX, "")
                .replace(Regex("""\s+"""), " ")
                .trim()
            return cleaned.ifBlank { "点击查看促销详情与活动链接" }
        }

        fun createOverseasPromoTapIntent(
            context: Context,
            offerId: String?,
            title: String = "",
            sourceUrl: String = "",
        ): Intent {
            val cleanId = offerId?.trim()?.takeIf { it.isNotEmpty() }
            return Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_OVERSEAS_PROMO
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(
                    EXTRA_OVERSEAS_PAYLOAD,
                    encodeOverseasPromoTarget(
                        offerId = cleanId,
                        title = title,
                        sourceUrl = sourceUrl,
                    ),
                )
                if (cleanId != null) {
                    putExtra(EXTRA_OVERSEAS_OFFER_ID, cleanId)
                    putExtra(EXTRA_OVERSEAS_TITLE, title)
                    putExtra(EXTRA_OVERSEAS_SOURCE_URL, sourceUrl)
                }
            }
        }

        fun parseOverseasPromoDestination(intent: Intent?): AppDestination? {
            if (intent == null) return null
            decodeOverseasPromoTarget(intent.getStringExtra(EXTRA_OVERSEAS_PAYLOAD))?.let { return it }
            if (intent.action != ACTION_OPEN_OVERSEAS_PROMO) return null

            val offerId = intent.getStringExtra(EXTRA_OVERSEAS_OFFER_ID)?.trim().orEmpty()
            return if (offerId.isNotEmpty()) {
                OverseasIntelDetailRoute(
                    offerId = offerId,
                    initialTitle = intent.getStringExtra(EXTRA_OVERSEAS_TITLE).orEmpty(),
                    initialSourceUrl = intent.getStringExtra(EXTRA_OVERSEAS_SOURCE_URL).orEmpty(),
                )
            } else {
                OverseasIntelRoute
            }
        }

        fun clearOverseasPromoExtras(intent: Intent?) {
            if (intent == null) return
            if (intent.action == ACTION_OPEN_OVERSEAS_PROMO) {
                intent.action = null
            }
            intent.removeExtra(EXTRA_OVERSEAS_PAYLOAD)
            intent.removeExtra(EXTRA_OVERSEAS_OFFER_ID)
            intent.removeExtra(EXTRA_OVERSEAS_TITLE)
            intent.removeExtra(EXTRA_OVERSEAS_SOURCE_URL)
        }

        internal fun encodeOverseasPromoTarget(
            offerId: String?,
            title: String = "",
            sourceUrl: String = "",
        ): String {
            val cleanId = offerId?.trim().orEmpty()
            if (cleanId.isEmpty()) {
                return "nodepulse://overseas/list"
            }
            val params = buildList {
                add("offerId=${urlEncode(cleanId)}")
                if (title.isNotBlank()) {
                    add("title=${urlEncode(title)}")
                }
                if (sourceUrl.isNotBlank()) {
                    add("sourceUrl=${urlEncode(sourceUrl)}")
                }
            }
            return "nodepulse://overseas/detail?${params.joinToString("&")}"
        }

        internal fun decodeOverseasPromoTarget(encoded: String?): AppDestination? {
            if (encoded.isNullOrBlank()) return null
            val trimmed = encoded.trim()
            if (!trimmed.startsWith("nodepulse://overseas/")) return null

            val pathAndQuery = trimmed.removePrefix("nodepulse://overseas/")
            val qIdx = pathAndQuery.indexOf('?')
            val path = if (qIdx >= 0) pathAndQuery.substring(0, qIdx) else pathAndQuery
            val query = if (qIdx >= 0) pathAndQuery.substring(qIdx + 1) else ""
            val params = parseQueryMap(query)

            return when (path) {
                "list" -> OverseasIntelRoute
                "detail" -> {
                    val offerId = params["offerId"]?.trim()?.takeIf { it.isNotEmpty() } ?: return OverseasIntelRoute
                    OverseasIntelDetailRoute(
                        offerId = offerId,
                        initialTitle = params["title"].orEmpty(),
                        initialSourceUrl = params["sourceUrl"].orEmpty(),
                    )
                }
                else -> null
            }
        }
    }
}
