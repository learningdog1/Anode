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
 * Synchronized lifecycle coordinator preventing Android 8–15 `ForegroundServiceDidNotStartInTimeException`
 * when an SSH benchmark finishes, fails, or is cancelled milliseconds after `startForegroundService`
 * (before the main looper has delivered `LiteSshForegroundService.onStartCommand`).
 *
 * Contract:
 * - Once [requestStartOrUpdate] transitions from idle to `pendingStartContract = true`, the caller
 *   dispatches `ContextCompat.startForegroundService(...)` exactly once.
 * - If [requestStop] is called BEFORE [onServicePromotedToForeground] runs on the main thread,
 *   we do **NOT** call `Context.stopService(...)` prematurely; instead `shouldRemainForeground` is
 *   flipped to `false` so `onStartCommand` first fulfills `ServiceCompat.startForeground(...)` and
 *   immediately stops itself cleanly!
 */
internal class ForegroundServiceContractCoordinator {
    data class SessionNotificationPayload(
        val serverAlias: String,
        val presetName: String,
        val statusLine: String,
    )

    private val lock = Any()
    private var shouldRemainForeground: Boolean = false
    private var pendingStartContract: Boolean = false
    private var activeService: LiteSshForegroundService? = null
    private var latestPayload: SessionNotificationPayload? = null

    enum class StartDirective {
        START_FOREGROUND_SERVICE,
        UPDATE_NOTIFICATION_ONLY,
    }

    fun requestStartOrUpdate(payload: SessionNotificationPayload? = null): StartDirective = synchronized(lock) {
        if (payload != null) {
            latestPayload = payload
        }
        shouldRemainForeground = true
        if (activeService == null && !pendingStartContract) {
            pendingStartContract = true
            StartDirective.START_FOREGROUND_SERVICE
        } else {
            StartDirective.UPDATE_NOTIFICATION_ONLY
        }
    }

    fun currentPayload(): SessionNotificationPayload? = synchronized(lock) { latestPayload }

    fun onStartForegroundServiceCallFailed() = synchronized(lock) {
        pendingStartContract = false
    }

    /**
     * Invoked inside `LiteSshForegroundService.onStartCommand` **immediately after**
     * `ServiceCompat.startForeground(...)` has fulfilled Android's 5-second FGS contract.
     *
     * @return true if the session is still active and the service should remain in foreground;
     *   false if the session already ended (or a stop action arrived) and the service must
     *   immediately call `stopForeground` + `stopSelf`.
     */
    fun onServicePromotedToForeground(
        service: LiteSshForegroundService,
        forceStop: Boolean = false,
    ): Boolean = synchronized(lock) {
        pendingStartContract = false
        if (forceStop || !shouldRemainForeground) {
            shouldRemainForeground = false
            activeService = null
            false
        } else {
            activeService = service
            true
        }
    }

    /**
     * Marks the session as finished and returns the already-promoted [LiteSshForegroundService]
     * instance to stop immediately, or `null` if `startForegroundService` is still in flight
     * (in which case `onStartCommand` will fulfill `startForeground` and self-stop upon arrival).
     */
    fun requestStop(): LiteSshForegroundService? = synchronized(lock) {
        shouldRemainForeground = false
        val running = activeService
        activeService = null
        running
    }

    fun onServiceDestroyed(service: LiteSshForegroundService) = synchronized(lock) {
        if (activeService === service) {
            activeService = null
        }
    }

    internal val isContractPendingForTest: Boolean
        get() = synchronized(lock) { pendingStartContract }

    internal val shouldRemainForegroundForTest: Boolean
        get() = synchronized(lock) { shouldRemainForeground }
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

    override fun onSessionRunning(
        serverAlias: String,
        presetName: String,
        statusLine: String,
    ) {
        val payload = ForegroundServiceContractCoordinator.SessionNotificationPayload(
            serverAlias = serverAlias,
            presetName = presetName,
            statusLine = statusLine,
        )
        when (LiteSshForegroundService.contractCoordinator.requestStartOrUpdate(payload)) {
            ForegroundServiceContractCoordinator.StartDirective.START_FOREGROUND_SERVICE -> {
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
                    LiteSshForegroundService.contractCoordinator.onStartForegroundServiceCallFailed()
                    postOngoingNotificationDirectly(serverAlias, presetName, statusLine)
                }
            }

            ForegroundServiceContractCoordinator.StartDirective.UPDATE_NOTIFICATION_ONLY -> {
                postOngoingNotificationDirectly(serverAlias, presetName, statusLine)
            }
        }
    }

    override fun onSessionFinished() {
        val promotedService = LiteSshForegroundService.contractCoordinator.requestStop()
        if (promotedService != null) {
            promotedService.stopForegroundAndSelfFromController()
        } else if (!LiteSshForegroundService.contractCoordinator.isContractPendingForTest) {
            // Fallback notification cleanup when FGS was never started (e.g. background restriction)
            cancelOngoingNotification(appContext)
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

    companion object {
        internal fun cancelOngoingNotification(context: Context) {
            runCatching {
                NotificationManagerCompat.from(context)
                    .cancel(PulseNotificationDispatcher.ID_SSH_ONGOING)
            }
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
        val isStopAction = intent?.action == ACTION_STOP_SSH_BENCHMARK
        val latest = contractCoordinator.currentPayload()
        val serverAlias = (latest?.serverAlias ?: intent?.getStringExtra(EXTRA_SERVER_ALIAS).orEmpty()).ifBlank { "VPS" }
        val presetName = (latest?.presetName ?: intent?.getStringExtra(EXTRA_PRESET_NAME).orEmpty()).ifBlank { "一键测机" }
        val statusLine = (latest?.statusLine ?: intent?.getStringExtra(EXTRA_STATUS_LINE).orEmpty()).ifBlank { "后台持续运行中..." }

        val dispatcher = (application as? NodePulseApp)?.container?.notificationDispatcher
            ?: PulseNotificationDispatcher(this)
        val notification = dispatcher.buildSshBenchmarkOngoingNotification(
            serverAlias = serverAlias,
            presetName = presetName,
            statusLine = statusLine,
            stopPendingIntent = createStopPendingIntent(this),
        )

        // Always fulfill Android's 5-second startForegroundService contract FIRST before any early exit!
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

        if (isStopAction) {
            (application as? NodePulseApp)?.container?.sshSessionManager?.cancelExecution()
        }

        val keepRunning = contractCoordinator.onServicePromotedToForeground(
            service = this,
            forceStop = isStopAction || !fgsStarted,
        )

        if (!keepRunning) {
            stopForegroundSafely()
            stopSelf()
            AndroidSshForegroundServiceController.cancelOngoingNotification(this)
            return START_NOT_STICKY
        }

        acquireBoundedWakeLock()
        return START_NOT_STICKY
    }

    internal fun stopForegroundAndSelfFromController() {
        releaseWakeLock()
        stopForegroundSafely()
        runCatching { stopSelf() }
        AndroidSshForegroundServiceController.cancelOngoingNotification(this)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        contractCoordinator.requestStop()
        releaseWakeLock()
        stopForegroundSafely()
        stopSelf(startId)
    }

    override fun onDestroy() {
        contractCoordinator.onServiceDestroyed(this)
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
        internal val contractCoordinator = ForegroundServiceContractCoordinator()

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
