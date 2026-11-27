package com.nodeseek.app.notifications

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
import com.nodeseek.app.domain.mail.OtpWatchNotificationSink
import com.nodeseek.app.domain.mail.OtpWatchState
import com.nodeseek.app.navigation.SeekMailRoute

/**
 * Manages the Seek Mail 3-minute OTP watcher notification channel (`seek_mail_otp_watch`),
 * ongoing silent countdown notification (`90_100`), and high-priority OTP captured
 * heads-up notification (`91_000+`) with a 1-tap `"一键复制 <验证码>"` action.
 */
open class OtpWatchNotificationHelper(
    context: Context,
) : OtpWatchNotificationSink {

    private val appContext: Context = context.applicationContext ?: context

    @Volatile
    private var channelEnsured: Boolean = false

    override fun ensureChannel() {
        if (channelEnsured) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        synchronized(this) {
            if (channelEnsured) return
            val channel = NotificationChannel(
                CHANNEL_OTP_WATCH,
                CHANNEL_OTP_WATCH_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Seek 邮箱 3 分钟验证码监听与一键复制高优通知"
                enableVibration(true)
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
            channelEnsured = true
        }
    }

    override fun postWatchingNotification(state: OtpWatchState.Watching) {
        if (!canPostNotifications()) return
        ensureChannel()

        val stopIntent = buildStopWatchIntent(appContext)
        val stopPendingIntent = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE_STOP_WATCH,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = buildOpenSeekMailIntent(
            context = appContext,
            mailboxId = state.mailboxId ?: 0L,
            mailId = 0L,
        )
        val contentPendingIntent = PendingIntent.getActivity(
            appContext,
            REQUEST_CODE_OPEN_WATCHING,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_OTP_WATCH)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(formatWatchingTitle(state.remainingSeconds))
            .setContentText(formatWatchingSubtitle(state.mailboxAddress))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(false)
            .setContentIntent(contentPendingIntent)
            .addAction(0, ACTION_LABEL_STOP_WATCH, stopPendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(appContext).notify(ID_OTP_WATCH_ONGOING, notification)
        }
    }

    override fun postOtpFoundNotification(found: OtpWatchState.Found) {
        if (!canPostNotifications()) return
        ensureChannel()

        val notificationId = notificationIdForMail(found.mailId)
        val notificationTag = notificationTagForMail(found.mailId)
        val copyIntent = buildCopyOtpIntent(
            context = appContext,
            code = found.code,
            notificationId = notificationId,
            notificationTag = notificationTag,
        )
        val copyPendingIntent = PendingIntent.getBroadcast(
            appContext,
            notificationId,
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = buildOpenSeekMailIntent(
            context = appContext,
            mailboxId = 0L,
            mailId = found.mailId,
        )
        val contentPendingIntent = PendingIntent.getActivity(
            appContext,
            notificationId + 1,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val bodyText = formatFoundSubtitle(found)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_OTP_WATCH)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(formatFoundTitle(found.code))
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .addAction(0, formatCopyActionLabel(found.code), copyPendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(appContext).notify(notificationTag, notificationId, notification)
        }
    }

    override fun postTimedOutNotification(timedOut: OtpWatchState.TimedOut) {
        cancelWatchingNotification()
        if (!canPostNotifications()) return
        ensureChannel()

        val contentIntent = buildOpenSeekMailIntent(
            context = appContext,
            mailboxId = timedOut.mailboxId ?: 0L,
            mailId = 0L,
        )
        val contentPendingIntent = PendingIntent.getActivity(
            appContext,
            REQUEST_CODE_OPEN_TIMEOUT,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_OTP_WATCH)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("验证码监听已结束")
            .setContentText("3 分钟内未收到新验证码邮件，点击可重新开启监听")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(appContext).notify(ID_OTP_WATCH_TIMED_OUT, notification)
        }
    }

    override fun cancelWatchingNotification() {
        runCatching {
            NotificationManagerCompat.from(appContext).cancel(ID_OTP_WATCH_ONGOING)
        }
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return runCatching {
            NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        }.getOrDefault(false)
    }

    companion object {
        const val CHANNEL_OTP_WATCH = "seek_mail_otp_watch"
        const val CHANNEL_OTP_WATCH_NAME = "Seek 邮箱验证码监听"

        const val ID_OTP_WATCH_ONGOING = 90_100
        const val ID_OTP_WATCH_TIMED_OUT = 90_101
        const val BASE_ID_OTP_FOUND = 91_000

        const val ACTION_COPY_OTP = "com.nodeseek.app.ACTION_COPY_OTP"
        const val ACTION_STOP_OTP_WATCH = "com.nodeseek.app.ACTION_STOP_OTP_WATCH"
        const val ACTION_OPEN_SEEK_MAIL = "com.nodeseek.app.ACTION_OPEN_SEEK_MAIL"

        const val EXTRA_OTP_CODE = "extra_otp_code"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_NOTIFICATION_TAG = "extra_notification_tag"
        const val EXTRA_MAILBOX_ID = "extra_mailbox_id"
        const val EXTRA_MAIL_ID = "extra_mail_id"

        const val ACTION_LABEL_STOP_WATCH = "停止监听"

        private const val REQUEST_CODE_STOP_WATCH = 90_201
        private const val REQUEST_CODE_OPEN_WATCHING = 90_202
        private const val REQUEST_CODE_OPEN_TIMEOUT = 90_203
        private const val OTP_FOUND_SLOT_COUNT = 4_000

        fun notificationTagForMail(mailId: Long): String = "seek_mail_otp_$mailId"

        fun notificationIdForMail(mailId: Long): Int {
            // SplitMix64 mixing eliminates periodic modulo collisions (e.g. 1L vs 8001L),
            // and step-by-2 slots prevent adjacent requestCode collisions with (notificationId + 1).
            var z = mailId + -7046029254386353131L // 0x9e3779b97f4a7c15UL as signed Long
            z = (z xor (z ushr 30)) * -4658895280553007687L
            z = (z xor (z ushr 27)) * -7723592293110705685L
            z = z xor (z ushr 31)
            val slot = ((z and Long.MAX_VALUE) % OTP_FOUND_SLOT_COUNT).toInt()
            return BASE_ID_OTP_FOUND + slot * 2
        }

        fun formatRemainingCountdown(remainingSeconds: Int): String {
            val safe = remainingSeconds.coerceAtLeast(0)
            val mins = safe / 60
            val secs = safe % 60
            return "%02d:%02d".format(mins, secs)
        }

        fun formatWatchingTitle(remainingSeconds: Int): String =
            "正在监听验证码 · 剩余 ${formatRemainingCountdown(remainingSeconds)}"

        fun formatWatchingSubtitle(mailboxAddress: String?): String =
            mailboxAddress?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { "监控邮箱：$it · 收到后将弹窗提醒" }
                ?: "正在实时轮询 Seek 邮箱新验证码"

        fun formatFoundTitle(code: String): String =
            "收到验证码：${code.trim()}"

        fun formatCopyActionLabel(code: String): String =
            "一键复制 ${code.trim()}"

        fun formatFoundSubtitle(found: OtpWatchState.Found): String = buildString {
            val sender = found.fromDisplay.trim().ifBlank { "未知发件人" }
            append(sender)
            if (found.subject.isNotBlank()) {
                append(" · ")
                append(found.subject.trim())
            }
            found.mailboxAddress?.trim()?.takeIf { it.isNotEmpty() }?.let { addr ->
                append(" (")
                append(addr)
                append(")")
            }
        }

        fun buildCopyOtpIntent(
            context: Context,
            code: String,
            notificationId: Int,
            notificationTag: String? = null,
        ): Intent = Intent(context, CopyOtpBroadcastReceiver::class.java).apply {
            action = ACTION_COPY_OTP
            putExtra(EXTRA_OTP_CODE, code)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            if (!notificationTag.isNullOrBlank()) {
                putExtra(EXTRA_NOTIFICATION_TAG, notificationTag)
            }
        }

        fun buildStopWatchIntent(context: Context): Intent =
            Intent(context, CopyOtpBroadcastReceiver::class.java).apply {
                action = ACTION_STOP_OTP_WATCH
            }

        fun buildOpenSeekMailIntent(
            context: Context,
            mailboxId: Long = 0L,
            mailId: Long = 0L,
        ): Intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_SEEK_MAIL
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_MAILBOX_ID, mailboxId)
            putExtra(EXTRA_MAIL_ID, mailId)
        }

        fun parseSeekMailTapDestination(intent: Intent?): SeekMailRoute? {
            if (intent?.action != ACTION_OPEN_SEEK_MAIL) return null
            val mailboxId = intent.getLongExtra(EXTRA_MAILBOX_ID, 0L).coerceAtLeast(0L)
            val mailId = intent.getLongExtra(EXTRA_MAIL_ID, 0L).coerceAtLeast(0L)
            return SeekMailRoute(
                initialMailboxId = mailboxId,
                initialMailId = mailId,
            )
        }

        fun clearSeekMailExtras(intent: Intent?) {
            if (intent == null) return
            if (intent.action == ACTION_OPEN_SEEK_MAIL) {
                intent.action = null
            }
            intent.removeExtra(EXTRA_MAILBOX_ID)
            intent.removeExtra(EXTRA_MAIL_ID)
        }
    }
}
