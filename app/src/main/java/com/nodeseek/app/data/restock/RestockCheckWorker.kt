package com.nodeseek.app.data.restock

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nodeseek.app.domain.restock.RestockBackgroundPollOutcome
import com.nodeseek.app.domain.restock.RestockMonitorController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.IOException

/**
 * Optional host interface implemented by [android.app.Application] (or test Contexts)
 * so [RestockCheckWorker] can delegate directly to the app's singleton [RestockMonitorController].
 */
interface RestockMonitorHost {
    val restockMonitorController: RestockMonitorController
}

/**
 * WorkManager periodic worker executing a single serialized VPS restock check pass
 * and dispatching high-priority heads-up notifications on `OUT_OF_STOCK -> IN_STOCK` waves.
 */
class RestockCheckWorker internal constructor(
    appContext: Context,
    workerParams: WorkerParameters,
    private val backgroundPollRunner: (suspend () -> RestockBackgroundPollOutcome)?,
) : CoroutineWorker(appContext, workerParams) {

    constructor(
        appContext: Context,
        workerParams: WorkerParameters,
    ) : this(
        appContext = appContext,
        workerParams = workerParams,
        backgroundPollRunner = null,
    )

    override suspend fun doWork(): Result {
        val outcome = try {
            val runner = backgroundPollRunner ?: resolveDefaultRunner()
            runner()
        } catch (c: CancellationException) {
            throw c
        } catch (e: IOException) {
            return Result.retry()
        } catch (_: Throwable) {
            return Result.failure()
        }

        return when (outcome) {
            is RestockBackgroundPollOutcome.Retry -> Result.retry()
            is RestockBackgroundPollOutcome.Completed,
            is RestockBackgroundPollOutcome.Skipped,
            -> Result.success()
        }
    }

    private fun resolveDefaultRunner(): suspend () -> RestockBackgroundPollOutcome {
        val app = applicationContext
        if (app is RestockMonitorHost) {
            return app.restockMonitorController::runBackgroundPollPass
        }
        registeredControllerProvider?.invoke()?.let { registered ->
            return registered::runBackgroundPollPass
        }

        // Cold-start resilient standalone fallback using the same JSON store file.
        val standaloneRepository = RestockRepositoryImpl(app)
        val standaloneController = RestockMonitorControllerImpl(
            appContext = app,
            repository = standaloneRepository,
            analyzer = StockPageAnalyzer(
                flareSolverrClient = FlareSolverrClient(),
                settingsProvider = { standaloneRepository.getSettingsSnapshot() },
            ),
            notificationHelper = RestockNotificationHelper(app),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        return standaloneController::runBackgroundPollPass
    }

    companion object {
        @Volatile
        private var registeredControllerProvider: (() -> RestockMonitorController)? = null

        /**
         * Registers the app-wide [RestockMonitorController] provider so WorkManager background
         * passes share the same in-process mutex and live [StateFlow] without requiring
         * [android.app.Application] to implement [RestockMonitorHost].
         */
        fun registerControllerProvider(provider: (() -> RestockMonitorController)?) {
            registeredControllerProvider = provider
        }
    }
}
