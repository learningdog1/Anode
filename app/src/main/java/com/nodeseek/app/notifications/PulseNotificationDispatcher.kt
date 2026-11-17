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
import com.nodeseek.app.domain.radar.LocalAlertRecord
import com.nodeseek.app.domain.radar.NotificationTapTarget
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

        private const val PREFS_NAME = "nodepulse_notification_prefs"
        private const val KEY_POST_NOTIF_REQUESTED_ONCE = "post_notifications_requested_once"

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

        private const val BASE_ID_KEYWORD = 10_000
        private const val BASE_ID_TRACKED = 20_000
        private const val BASE_ID_INTERACTION = 30_000
        private const val BASE_ID_DM = 40_000

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
    }
}
