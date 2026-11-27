package com.nodeseek.app.notifications

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.nodeseek.app.data.radar.AppContainerHost

/**
 * Handles high-priority notification actions for Seek Mail OTP watch:
 * - [OtpWatchNotificationHelper.ACTION_COPY_OTP]: copies the OTP to clipboard with
 *   [ClipDescription.EXTRA_IS_SENSITIVE] on API 33+, cancels the notification, and shows a Toast.
 * - [OtpWatchNotificationHelper.ACTION_STOP_OTP_WATCH]: stops the active OTP watcher and
 *   dismisses the ongoing watching notification.
 */
class CopyOtpBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            OtpWatchNotificationHelper.ACTION_COPY_OTP -> {
                val code = intent.getStringExtra(OtpWatchNotificationHelper.EXTRA_OTP_CODE)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return
                val notificationId = intent.getIntExtra(OtpWatchNotificationHelper.EXTRA_NOTIFICATION_ID, 0)
                val notificationTag = intent.getStringExtra(OtpWatchNotificationHelper.EXTRA_NOTIFICATION_TAG)
                    ?.takeIf { it.isNotBlank() }
                copyOtpToClipboard(context = context, code = code)
                if (notificationId > 0) {
                    runCatching {
                        val nm = NotificationManagerCompat.from(context)
                        if (notificationTag != null) {
                            nm.cancel(notificationTag, notificationId)
                        } else {
                            nm.cancel(notificationId)
                        }
                    }
                }
                runCatching {
                    Toast.makeText(context, "已复制验证码：$code", Toast.LENGTH_SHORT).show()
                }
            }

            OtpWatchNotificationHelper.ACTION_STOP_OTP_WATCH -> {
                val host = context.applicationContext as? AppContainerHost
                host?.container?.seekMailOtpWatcher?.stopWatch()
                runCatching {
                    NotificationManagerCompat.from(context)
                        .cancel(OtpWatchNotificationHelper.ID_OTP_WATCH_ONGOING)
                }
            }
        }
    }

    companion object {
        fun copyOtpToClipboard(context: Context, code: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return
            val clip = ClipData.newPlainText("Seek 邮箱验证码", code)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            clipboard.setPrimaryClip(clip)
        }
    }
}
