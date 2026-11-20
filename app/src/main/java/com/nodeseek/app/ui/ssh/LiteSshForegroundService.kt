package com.nodeseek.app.ui.ssh

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nodeseek.app.NodePulseApp
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Abstraction for starting, updating, and stopping the foreground keep-alive service while an
 * SSH benchmark session runs in the background.
 */
interface SshForegroundServiceController {
    fun onSessionRunning(
        serverAlias: String,
        presetName: String,
        statusLine: String,
    )

    fun onSessionFinished()
}

object NoOpSshForegroundServiceController : SshForegroundServiceController {
    override fun onSessionRunning(
        serverAlias: String,
        presetName: String,
        statusLine: String,
    ) = Unit

    override fun onSessionFinished() = Unit
}

/**
 * Production [SshForegroundServiceController] backed by [LiteSshForegroundService] (`dataSync`
 * foreground service type + ongoing progress notification with 1-tap return and stop actions).
 */
class AndroidSshForegroundServiceController(
    context: Context,
    private val notificationDispatcher: PulseNotificationDispatcher,
) : SshForegroundServiceController {

    private val appContext: Context = context.applicationContext ?: context
    private val serviceStarted = AtomicBoolean(false)

    override fun onSessionRunning(
        serverAlias: String,
        presetName: String,
        statusLine: String,
    ) {
        if (serviceStarted.compareAndSet(false, true)) {
            val startIntent = LiteSshForegroundService.createStartIntent(
                context = appContext,
                serverAlias = serverAlias,
                presetName = presetName,
                statusLine = statusLine,
            )
            val started = runCatching {
                ContextCompat.startForegroundService(appContext, startIntent)
                true
            }.getOrDefault(false)
            if (!started) {
                serviceStarted.set(false)
                postOngoingNotificationDirectly(serverAlias, presetName, statusLine)
            }
        } else {
            postOngoingNotificationDirectly(serverAlias, presetName, statusLine)
        }
    }

    override fun onSessionFinished() {
        val wasStarted = serviceStarted.getAndSet(false)
        if (wasStarted) {
            runCatching {
                appContext.stopService(Intent(appContext, LiteSshForegroundService::class.java))
            }
        }
        runCatching {
            NotificationManagerCompat.from(appContext)
                .cancel(PulseNotificationDispatcher.ID_SSH_ONGOING)
        }
    }

    private fun postOngoingNotificationDirectly(
        serverAlias: String,
        presetName: String,
        statusLine: String,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            val stopIntent = LiteSshForegroundService.createStopPendingIntent(appContext)
            val notification = notificationDispatcher.buildSshBenchmarkOngoingNotification(
                serverAlias = serverAlias,
                presetName = presetName,
                statusLine = statusLine,
                stopPendingIntent = stopIntent,
            )
            NotificationManagerCompat.from(appContext)
                .notify(PulseNotificationDispatcher.ID_SSH_ONGOING, notification)
        }
    }
}

/**
 * Foreground keep-alive service (`foregroundServiceType="dataSync"`) ensuring long-running SSH
 * benchmarks (YABS Geekbench, NodeQuality, GoECS, Bench.sh) continue streaming uninterrupted when
 * the user leaves `LiteSshScreen` or switches apps.
 */
class LiteSshForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SSH_BENCHMARK) {
            (application as? NodePulseApp)?.container?.sshSessionManager?.cancelExecution()
            stopForegroundSafely()
            stopSelf()
            return START_NOT_STICKY
        }

        val serverAlias = intent?.getStringExtra(EXTRA_SERVER_ALIAS).orEmpty().ifBlank { "VPS" }
        val presetName = intent?.getStringExtra(EXTRA_PRESET_NAME).orEmpty().ifBlank { "一键测机" }
        val statusLine = intent?.getStringExtra(EXTRA_STATUS_LINE).orEmpty().ifBlank { "后台持续运行中..." }

        val dispatcher = (application as? NodePulseApp)?.container?.notificationDispatcher
            ?: PulseNotificationDispatcher(this)
        val notification = dispatcher.buildSshBenchmarkOngoingNotification(
            serverAlias = serverAlias,
            presetName = presetName,
            statusLine = statusLine,
            stopPendingIntent = createStopPendingIntent(this),
        )

        val fgsStarted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    PulseNotificationDispatcher.ID_SSH_ONGOING,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(PulseNotificationDispatcher.ID_SSH_ONGOING, notification)
            }
            true
        }.getOrDefault(false)

        if (!fgsStarted) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        acquireBoundedWakeLock()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForegroundSafely()
        stopSelf(startId)
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun stopForegroundSafely() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    private fun acquireBoundedWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "NodePulse:LiteSshBenchmarkWakeLock",
            ).apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_LOCK_DURATION_MS)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            val lock = wakeLock
            wakeLock = null
            if (lock?.isHeld == true) {
                lock.release()
            }
        }
    }

    companion object {
        const val ACTION_START_SSH_BENCHMARK = "com.nodeseek.app.action.START_SSH_BENCHMARK"
        const val ACTION_STOP_SSH_BENCHMARK = "com.nodeseek.app.action.STOP_SSH_BENCHMARK"

        private const val EXTRA_SERVER_ALIAS = "com.nodeseek.app.extra.SSH_SERVER_ALIAS"
        private const val EXTRA_PRESET_NAME = "com.nodeseek.app.extra.SSH_PRESET_NAME"
        private const val EXTRA_STATUS_LINE = "com.nodeseek.app.extra.SSH_STATUS_LINE"

        private const val REQUEST_CODE_STOP = 59_101
        private const val MAX_WAKE_LOCK_DURATION_MS = 30L * 60_000L

        fun createStartIntent(
            context: Context,
            serverAlias: String,
            presetName: String,
            statusLine: String,
        ): Intent = Intent(context, LiteSshForegroundService::class.java).apply {
            action = ACTION_START_SSH_BENCHMARK
            putExtra(EXTRA_SERVER_ALIAS, serverAlias)
            putExtra(EXTRA_PRESET_NAME, presetName)
            putExtra(EXTRA_STATUS_LINE, statusLine)
        }

        fun createStopPendingIntent(context: Context): PendingIntent {
            val stopIntent = Intent(context, LiteSshForegroundService::class.java).apply {
                action = ACTION_STOP_SSH_BENCHMARK
            }
            return PendingIntent.getService(
                context,
                REQUEST_CODE_STOP,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
