package com.nodeseek.app.restock

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.nodeseek.app.data.restock.RestockCheckWorker
import com.nodeseek.app.data.restock.RestockMonitorControllerImpl
import com.nodeseek.app.data.restock.RestockMonitorHost
import com.nodeseek.app.data.restock.RestockNotificationHelper
import com.nodeseek.app.data.restock.RestockRepositoryImpl
import com.nodeseek.app.data.restock.RestockWorkManagerGateway
import com.nodeseek.app.data.restock.RestockWorkScheduler
import com.nodeseek.app.data.restock.StockPageAnalyzer
import com.nodeseek.app.domain.restock.MerchantPanelType
import com.nodeseek.app.domain.restock.RestockBackgroundPollOutcome
import com.nodeseek.app.domain.restock.RestockEventRecord
import com.nodeseek.app.domain.restock.RestockMonitorController
import com.nodeseek.app.domain.restock.RestockMonitorSettings
import com.nodeseek.app.domain.restock.RestockStockStatus
import com.nodeseek.app.domain.restock.RestockWatchItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class RestockMonitorControllerAndWorkerTest {

    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var repository: RestockRepositoryImpl
    private lateinit var analyzer: StockPageAnalyzer
    private lateinit var notificationSpy: RecordingRestockNotificationHelper

    private var fakeClockMillis: Long = 1_760_000_000_000L
    private var fakeUnmeteredNetwork: Boolean = true

    private class FakeAppContext(
        private val controllerProvider: (() -> RestockMonitorController)? = null,
    ) : ContextWrapper(null), RestockMonitorHost {
        override fun getApplicationContext(): Context = this
        override val restockMonitorController: RestockMonitorController
            get() = controllerProvider?.invoke() ?: error("No controller registered")
    }

    private class RecordingRestockNotificationHelper : RestockNotificationHelper(FakeAppContext()) {
        val postedEvents = mutableListOf<RestockEventRecord>()
        val summaryCounts = mutableListOf<Int>()

        override fun ensureChannel() = Unit
        override fun hasPostNotificationsPermission(): Boolean = true
        override fun postRestockUrgentNotification(event: RestockEventRecord) {
            postedEvents.add(event)
        }

        override fun postRestockSummaryNotification(events: List<RestockEventRecord>) {
            summaryCounts.add(events.size)
        }
    }

    private class RecordingWorkManagerGateway : RestockWorkManagerGateway {
        data class EnqueueCall(
            val uniqueWorkName: String,
            val policy: ExistingPeriodicWorkPolicy,
            val request: PeriodicWorkRequest,
        )

        val enqueueCalls = mutableListOf<EnqueueCall>()
        val cancelCalls = mutableListOf<String>()

        override fun enqueueUniquePeriodicWork(
            uniqueWorkName: String,
            existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
            request: PeriodicWorkRequest,
        ) {
            enqueueCalls.add(EnqueueCall(uniqueWorkName, existingPeriodicWorkPolicy, request))
        }

        override fun cancelUniqueWork(uniqueWorkName: String) {
            cancelCalls.add(uniqueWorkName)
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("restock_ctrl_test").toFile()
        repository = RestockRepositoryImpl(
            storeFile = File(tempDir, RestockRepositoryImpl.STORE_FILE_NAME),
            clockMillis = { fakeClockMillis },
        )
        analyzer = StockPageAnalyzer(
            httpClient = OkHttpClient.Builder().followRedirects(true).build(),
            clockMillis = { fakeClockMillis },
        )
        notificationSpy = RecordingRestockNotificationHelper()
        RestockCheckWorker.registerControllerProvider(null)
    }

    @After
    fun tearDown() {
        RestockCheckWorker.registerControllerProvider(null)
        server.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun `end-to-end MockWebServer check dispatches deduplicated urgent notifications across restock waves`() = runTest {
        var inStockWave = false

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (!inStockWave) {
                    MockResponse().setResponseCode(200).setBody(
                        """
                        <html><body>
                          <div id="order-standard_cart">
                            <div class="alert alert-danger">Out of Stock</div>
                            <a href="cart.php" class="btn">Continue Shopping</a>
                          </div>
                        </body></html>
                        """.trimIndent(),
                    )
                } else {
                    MockResponse().setResponseCode(200).setBody(
                        """
                        <html><body>
                          <div id="order-standard_cart">
                            <form id="frmConfigureProduct" action="cart.php?a=confproduct" method="post">
                              <div class="product-info"><p class="product-title">BF KVM Special</p></div>
                              <select name="billingcycle"><option selected>$10.98 USD Annually</option></select>
                              <button type="submit" id="btnCompleteProductConfig">Continue</button>
                            </form>
                          </div>
                        </body></html>
                        """.trimIndent(),
                    )
                }
            }
        }

        val productUrl = server.url("/cart.php?a=add&pid=801").toString()
        repository.addOrUpdateWatch(
            RestockWatchItem(
                id = "bf-1",
                title = "RackNerd BF",
                rawUrl = productUrl,
                resolvedOrderUrl = productUrl,
                panelType = MerchantPanelType.WHMCS,
            ),
        )

        val controller = RestockMonitorControllerImpl(
            repository = repository,
            analyzer = analyzer,
            notificationHelper = notificationSpy,
            scope = this,
            clockMillis = { fakeClockMillis },
            isUnmeteredNetwork = { fakeUnmeteredNetwork },
        )

        // Poll 1: Out of stock -> arms watch, 0 notifications
        controller.checkAllNow()
        assertEquals(RestockStockStatus.OUT_OF_STOCK, repository.getWatchItemsSnapshot().single().stockStatus)
        assertEquals(0, notificationSpy.postedEvents.size)

        // Poll 2: Restocked -> Wave #1 notification dispatched + price auto-extracted!
        inStockWave = true
        fakeClockMillis += 60_000L
        controller.checkAllNow()
        assertEquals(RestockStockStatus.IN_STOCK, repository.getWatchItemsSnapshot().single().stockStatus)
        assertEquals("$10.98/年", repository.getWatchItemsSnapshot().single().priceHint)
        assertEquals(1, notificationSpy.postedEvents.size)
        assertEquals(1, repository.getEventHistorySnapshot().size)

        // Poll 3: Still in stock -> deduped, no spam!
        fakeClockMillis += 60_000L
        controller.checkAllNow()
        assertEquals(1, notificationSpy.postedEvents.size)
        assertEquals(1, repository.getEventHistorySnapshot().size)

        // Poll 4: Sold out again -> re-arms!
        inStockWave = false
        fakeClockMillis += 60_000L
        controller.checkAllNow()
        assertEquals(RestockStockStatus.OUT_OF_STOCK, repository.getWatchItemsSnapshot().single().stockStatus)
        assertEquals(1, notificationSpy.postedEvents.size)

        // Poll 5: Restocks wave #2 -> 2nd urgent notification!
        inStockWave = true
        fakeClockMillis += 60_000L
        controller.checkAllNow()
        assertEquals(2, notificationSpy.postedEvents.size)
        assertEquals(2, repository.getEventHistorySnapshot().size)
    }

    @Test
    fun `RestockCheckWorker executes background poll pass and maps outcomes to WorkManager Result`() = runTest {
        val productUrl = server.url("/cart.php?a=add&pid=99").toString()
        repository.addOrUpdateWatch(
            RestockWatchItem(
                id = "bg-1",
                title = "Background Watch",
                rawUrl = productUrl,
                resolvedOrderUrl = productUrl,
                stockStatus = RestockStockStatus.OUT_OF_STOCK,
            ),
        )

        var responseCode = 200
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (responseCode == 503) {
                    MockResponse().setResponseCode(503).setBody("Unavailable")
                } else {
                    MockResponse().setResponseCode(200).setBody(
                        """
                        <html><body>
                          <div id="order-standard_cart">
                            <form id="frmConfigureProduct" action="cart.php?a=confproduct">
                              <select name="billingcycle"><option selected>$15.00 USD Annually</option></select>
                              <button type="submit" id="btnCompleteProductConfig">Continue</button>
                            </form>
                          </div>
                        </body></html>
                        """.trimIndent(),
                    )
                }
            }
        }

        val controller = RestockMonitorControllerImpl(
            repository = repository,
            analyzer = analyzer,
            notificationHelper = notificationSpy,
            scope = this,
            clockMillis = { fakeClockMillis },
            isUnmeteredNetwork = { fakeUnmeteredNetwork },
        )
        val context = FakeAppContext(controllerProvider = { controller })

        // 1. Transient HTTP 503 -> Result.retry()
        responseCode = 503
        val retryWorker = TestListenableWorkerBuilder<RestockCheckWorker>(context).build()
        assertEquals(ListenableWorker.Result.retry(), retryWorker.doWork())
        assertEquals(0, notificationSpy.postedEvents.size)

        // 2. Wi-Fi only on metered network -> Skipped -> Result.success()
        repository.updateSettings { it.copy(wifiOnlyInBackground = true) }
        fakeUnmeteredNetwork = false
        val skippedOutcome = controller.runBackgroundPollPass()
        assertEquals(
            RestockBackgroundPollOutcome.Skipped(
                RestockBackgroundPollOutcome.Skipped.SkipReason.UNMETERED_NETWORK_REQUIRED,
            ),
            skippedOutcome,
        )

        // 3. Back on Wi-Fi + HTTP 200 in-stock -> triggers urgent notification + Result.success()
        fakeUnmeteredNetwork = true
        responseCode = 200
        val okWorker = TestListenableWorkerBuilder<RestockCheckWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), okWorker.doWork())
        assertEquals(1, notificationSpy.postedEvents.size)

        // 4. Direct runner mapping verification
        val directRetryWorker = TestListenableWorkerBuilder<RestockCheckWorker>(FakeAppContext())
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = RestockCheckWorker(
                        appContext = appContext,
                        workerParams = workerParameters,
                        backgroundPollRunner = { RestockBackgroundPollOutcome.Retry("Timeout") },
                    )
                },
            )
            .build()
        assertEquals(ListenableWorker.Result.retry(), directRetryWorker.doWork())
    }

    @SuppressLint("RestrictedApi")
    @Test
    fun `RestockWorkScheduler enforces 15m minimum and cancels when no enabled watches remain`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val gateway = RecordingWorkManagerGateway()
        val scheduler = RestockWorkScheduler(gateway)

        val settingsFlow = MutableStateFlow(
            RestockMonitorSettings(
                isMonitorEnabled = true,
                backgroundIntervalMinutes = 15,
                wifiOnlyInBackground = false,
                requireBatteryNotLowInBackground = false,
            ),
        )
        val itemsFlow = MutableStateFlow(
            listOf(
                RestockWatchItem(
                    id = "w1",
                    title = "Test VPS",
                    rawUrl = "https://example.com/cart.php?a=add&pid=1",
                    resolvedOrderUrl = "https://example.com/cart.php?a=add&pid=1",
                    isEnabled = true,
                ),
            ),
        )

        scheduler.observe(
            scope = CoroutineScope(testDispatcher),
            settingsFlow = settingsFlow,
            watchItemsFlow = itemsFlow,
        )
        advanceUntilIdle()
        assertEquals(1, gateway.enqueueCalls.size)
        assertEquals(RestockWorkScheduler.UNIQUE_WORK_NAME, gateway.enqueueCalls.last().uniqueWorkName)
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, gateway.enqueueCalls.last().policy)
        assertEquals(
            PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS,
            gateway.enqueueCalls.last().request.workSpec.intervalDuration,
        )
        assertEquals(BackoffPolicy.EXPONENTIAL, gateway.enqueueCalls.last().request.workSpec.backoffPolicy)

        // Foreground-only setting edit should NOT churn WorkManager
        settingsFlow.value = settingsFlow.value.copy(defaultPollIntervalMinutes = 1)
        advanceUntilIdle()
        assertEquals(1, gateway.enqueueCalls.size)

        // Background constraint change triggers UPDATE
        settingsFlow.value = settingsFlow.value.copy(
            backgroundIntervalMinutes = 45,
            wifiOnlyInBackground = true,
            requireBatteryNotLowInBackground = true,
        )
        advanceUntilIdle()
        assertEquals(2, gateway.enqueueCalls.size)
        val updatedSpec = gateway.enqueueCalls.last().request.workSpec
        assertEquals(45L * 60_000L, updatedSpec.intervalDuration)
        assertEquals(NetworkType.UNMETERED, updatedSpec.constraints.requiredNetworkType)
        assertTrue(updatedSpec.constraints.requiresBatteryNotLow())

        // Disabling the only watch item cancels periodic work
        itemsFlow.value = itemsFlow.value.map { it.copy(isEnabled = false) }
        advanceUntilIdle()
        assertEquals(listOf(RestockWorkScheduler.UNIQUE_WORK_NAME), gateway.cancelCalls)

        // Verify coercion floor
        assertEquals(15L, RestockWorkScheduler.coerceIntervalMinutes(1))
        assertEquals(180L, RestockWorkScheduler.coerceIntervalMinutes(500))
        assertFalse(RestockWorkScheduler.shouldSchedule(settingsFlow.value.copy(isMonitorEnabled = false), true))

        scheduler.stopObserving()
    }

    @Test
    fun `foreground loop yields on background transition and probeUrl does not mutate repository`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                <html><body>
                  <div id="order-standard_cart">
                    <form id="frmConfigureProduct" action="cart.php?a=confproduct">
                      <div class="product-info"><p class="product-title">Probe Test VPS</p></div>
                      <select name="billingcycle"><option selected>$12.00 USD Annually</option></select>
                      <button type="submit" id="btnCompleteProductConfig">Continue</button>
                    </form>
                  </div>
                </body></html>
                """.trimIndent(),
            ),
        )

        val controllerScope = CoroutineScope(testDispatcher)
        val controller = RestockMonitorControllerImpl(
            repository = repository,
            analyzer = analyzer,
            notificationHelper = notificationSpy,
            scope = controllerScope,
            clockMillis = { fakeClockMillis },
            isUnmeteredNetwork = { true },
        )

        // Live probe must not persist anything
        val probed = controller.probeUrl(server.url("/cart.php?a=add&pid=55").toString())
        assertEquals(RestockStockStatus.IN_STOCK, probed.stockStatus)
        assertEquals("Probe Test VPS", probed.detectedTitle)
        assertEquals("$12.00/年", probed.detectedPriceHint)
        assertTrue(repository.getWatchItemsSnapshot().isEmpty())

        // Foreground lifecycle transitions (StandardTestDispatcher)
        controller.start()
        assertTrue(controller.isForegroundLoopActive())
        controller.onForegroundChanged(false)
        advanceUntilIdle()
        assertFalse(controller.isForegroundLoopActive())
        controller.onForegroundChanged(true)
        assertTrue(controller.isForegroundLoopActive())
        controller.stop()
        assertFalse(controller.isForegroundLoopActive())

        // Eager dispatcher (UnconfinedTestDispatcher) must never race on monitorJob assignment
        val eagerController = RestockMonitorControllerImpl(
            repository = repository,
            analyzer = analyzer,
            notificationHelper = notificationSpy,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            clockMillis = { fakeClockMillis },
            isUnmeteredNetwork = { true },
        )
        eagerController.start()
        assertTrue(eagerController.isForegroundLoopActive())
        eagerController.onForegroundChanged(false)
        assertFalse(eagerController.isForegroundLoopActive())
        eagerController.onForegroundChanged(true)
        assertTrue(eagerController.isForegroundLoopActive())
        eagerController.stop()
        assertFalse(eagerController.isForegroundLoopActive())
    }

    @Test
    fun `RestockNotificationHelper exposes expected channel constants and intent metadata`() {
        assertEquals("vps_restock_urgent", RestockNotificationHelper.CHANNEL_VPS_RESTOCK_URGENT)
        assertEquals("VPS 补货抢购提醒", RestockNotificationHelper.CHANNEL_NAME)
        assertEquals("com.nodeseek.app.group.VPS_RESTOCK", RestockNotificationHelper.GROUP_VPS_RESTOCK)
        assertEquals(3, RestockNotificationHelper.MAX_INDIVIDUAL_RESTOCK_NOTIFICATIONS)
        val id1 = RestockNotificationHelper.notificationIdForWatch("watch-a")
        val id2 = RestockNotificationHelper.notificationIdForWatch("watch-b")
        assertTrue(id1 in 60_000..64_500)
        assertTrue(id2 in 60_000..64_500)
    }

    @Test
    fun `RestockCheckWorker returns retry when cold-start container lateinit is uninitialized`() = runTest {
        val uninitializedContext = object : ContextWrapper(null), RestockMonitorHost {
            private lateinit var uninitController: RestockMonitorController
            override fun getApplicationContext(): Context = this
            override val restockMonitorController: RestockMonitorController
                get() = uninitController
        }
        val worker = TestListenableWorkerBuilder<RestockCheckWorker>(uninitializedContext).build()
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
    }

    @Test
    fun `batch restock caps individual urgent notifications at 3 and emits summary notification`() = runTest {
        val inStockHtml = """
            <html><body>
              <div id="order-standard_cart">
                <form id="frmConfigureProduct" action="cart.php?a=confproduct">
                  <div class="product-info"><p class="product-title">Burst VPS</p></div>
                  <select name="billingcycle"><option selected>$9.99 USD Annually</option></select>
                  <button type="submit" id="btnCompleteProductConfig">Continue</button>
                </form>
              </div>
            </body></html>
        """.trimIndent()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody(inStockHtml)
        }

        // Seed 5 watches that are armed (OUT_OF_STOCK) so all 5 restock simultaneously in one batch
        repeat(5) { idx ->
            val url = server.url("/cart.php?a=add&pid=${idx + 1}").toString()
            repository.addOrUpdateWatch(
                RestockWatchItem(
                    id = "burst-$idx",
                    title = "Burst VPS #$idx",
                    rawUrl = url,
                    resolvedOrderUrl = url,
                    pollIntervalMinutes = 1,
                    stockStatus = RestockStockStatus.OUT_OF_STOCK,
                    alertWaveArmed = true,
                ),
            )
        }

        val controller = RestockMonitorControllerImpl(
            repository = repository,
            analyzer = analyzer,
            notificationHelper = notificationSpy,
            scope = this,
            clockMillis = { fakeClockMillis },
            isUnmeteredNetwork = { true },
        )

        val outcome = controller.runBackgroundPollPass()
        assertTrue(outcome is RestockBackgroundPollOutcome.Completed)
        val completed = outcome as RestockBackgroundPollOutcome.Completed
        assertEquals(5, completed.restockedCount)
        assertEquals(RestockNotificationHelper.MAX_INDIVIDUAL_RESTOCK_NOTIFICATIONS, notificationSpy.postedEvents.size)
        assertEquals(listOf(5), notificationSpy.summaryCounts)
    }

    @Test
    fun `RestockWorkScheduler waits for async repository load before cancelling WorkManager`() = runTest {
        val storeFile = File(tempDir, "preseeded_restock_store.json")
        val seedRepo = RestockRepositoryImpl(storeFile = storeFile)
        seedRepo.addOrUpdateWatch(
            RestockWatchItem(
                id = "preseed-1",
                title = "Preseeded VPS",
                rawUrl = "https://example.com/cart.php?a=add&pid=999",
                resolvedOrderUrl = "https://example.com/cart.php?a=add&pid=999",
            ),
        )
        seedRepo.updateSettings { it.copy(isMonitorEnabled = true, backgroundIntervalMinutes = 15) }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val freshRepo = RestockRepositoryImpl(storeFile = storeFile)
        val gateway = RecordingWorkManagerGateway()
        val scheduler = RestockWorkScheduler(gateway)

        scheduler.observe(
            scope = CoroutineScope(testDispatcher),
            settingsFlow = freshRepo.settingsFlow,
            watchItemsFlow = freshRepo.watchItemsFlow,
        )
        // Ensure disk hydration has completed before draining the scheduler coroutine
        assertEquals(1, freshRepo.getWatchItemsSnapshot().size)
        advanceUntilIdle()

        // Must never have emitted a spurious cancelUniqueWork before disk load completed
        assertTrue(gateway.cancelCalls.isEmpty())
        assertEquals(1, gateway.enqueueCalls.size)
        scheduler.stopObserving()
    }
}
