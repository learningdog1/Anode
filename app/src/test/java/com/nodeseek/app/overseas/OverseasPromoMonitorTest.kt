package com.nodeseek.app.overseas

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.nodeseek.app.data.overseas.OverseasPromoMonitorControllerImpl
import com.nodeseek.app.data.overseas.OverseasPromoMonitorHost
import com.nodeseek.app.data.overseas.OverseasPromoPreferencesStore
import com.nodeseek.app.data.overseas.OverseasPromoWorkManagerGateway
import com.nodeseek.app.data.overseas.OverseasPromoWorkScheduler
import com.nodeseek.app.data.overseas.OverseasPromoWorker
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasIntelSnapshot
import com.nodeseek.app.domain.overseas.OverseasOfferDetail
import com.nodeseek.app.domain.overseas.OverseasOfferFilterConfig
import com.nodeseek.app.domain.overseas.OverseasPromoMonitorController
import com.nodeseek.app.domain.overseas.OverseasPromoMonitorSettings
import com.nodeseek.app.domain.overseas.OverseasPromoPollOutcome
import com.nodeseek.app.domain.overseas.OverseasRepostContent
import com.nodeseek.app.domain.overseas.OverseasSyncStatus
import com.nodeseek.app.navigation.OverseasIntelDetailRoute
import com.nodeseek.app.navigation.OverseasIntelRoute
import com.nodeseek.app.notifications.PulseNotificationDispatcher
import com.nodeseek.app.ui.overseas.OVERSEAS_MONITOR_TG_CHANNEL_URL
import com.nodeseek.app.ui.overseas.OverseasPromoToggleDecision
import com.nodeseek.app.ui.overseas.resolveEffectivePromoNotificationsEnabled
import com.nodeseek.app.ui.overseas.resolveOverseasPromoToggleDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class OverseasPromoMonitorTest {

    private lateinit var tempDir: File
    private lateinit var storeFile: File
    private lateinit var fakeRepository: FakeOverseasIntelRepository
    private lateinit var preferencesStore: OverseasPromoPreferencesStore
    private lateinit var notificationSpy: RecordingPromoNotificationDispatcher

    private var fakeClockMillis: Long = 1_763_700_000_000L
    private var fakeUnmeteredNetwork: Boolean = true

    private class FakeMonitorAppContext(
        private val controllerProvider: (() -> OverseasPromoMonitorController)? = null,
    ) : ContextWrapper(null), OverseasPromoMonitorHost {
        override fun getApplicationContext(): Context = this
        override val overseasPromoMonitorController: OverseasPromoMonitorController
            get() = controllerProvider?.invoke() ?: error("No overseasPromoMonitorController registered")
    }

    private class RecordingPromoNotificationDispatcher(
        var permissionGranted: Boolean = true,
    ) : PulseNotificationDispatcher(FakeMonitorAppContext()) {
        val dispatchedBatches = mutableListOf<List<OverseasDealOffer>>()
        val dispatchedOffers = mutableListOf<OverseasDealOffer>()

        override fun ensureChannels() = Unit
        override fun hasPostNotificationsPermission(): Boolean = permissionGranted

        override fun postOverseasPromoNotifications(offers: List<OverseasDealOffer>) {
            if (!permissionGranted) return
            val valid = offers.filter { it.id.isNotBlank() }
            if (valid.isEmpty()) return
            dispatchedBatches.add(valid)
            dispatchedOffers.addAll(valid)
        }

        override fun postOverseasPromoNotification(offer: OverseasDealOffer) {
            if (!permissionGranted || offer.id.isBlank()) return
            dispatchedOffers.add(offer)
        }
    }

    private class RecordingPromoWorkManagerGateway : OverseasPromoWorkManagerGateway {
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

    private class FakeOverseasIntelRepository(
        initialOffers: List<OverseasDealOffer> = emptyList(),
    ) : OverseasIntelRepository {
        var serverOffers: List<OverseasDealOffer> = initialOffers
        var nextError: Throwable? = null
        var refreshCallCount: Int = 0

        private val _snapshot = MutableStateFlow(OverseasIntelSnapshot(offers = initialOffers))
        override val snapshot: StateFlow<OverseasIntelSnapshot> = _snapshot.asStateFlow()

        fun preloadSnapshotOffers(offers: List<OverseasDealOffer>) {
            _snapshot.update {
                it.copy(
                    offers = offers,
                    syncStatus = OverseasSyncStatus(totalCount = offers.size),
                )
            }
        }

        override suspend fun loadFromCache(): List<OverseasDealOffer> = _snapshot.value.offers

        override suspend fun refresh(query: String): Result<List<OverseasDealOffer>> {
            refreshCallCount += 1
            nextError?.let { err ->
                return Result.failure(err)
            }
            val list = serverOffers
            _snapshot.update {
                it.copy(
                    offers = list,
                    syncStatus = OverseasSyncStatus(
                        lastSyncedEpochMillis = System.currentTimeMillis(),
                        totalCount = list.size,
                    ),
                )
            }
            return Result.success(list)
        }

        override suspend fun loadNextPage(query: String): Result<List<OverseasDealOffer>> =
            Result.success(_snapshot.value.offers)

        override suspend fun getOfferDetail(offerId: String, forceRefresh: Boolean): Result<OverseasOfferDetail> =
            Result.failure(UnsupportedOperationException("unused in monitor test"))

        override suspend fun getRepostContent(offerId: String, forceRefresh: Boolean): Result<OverseasRepostContent> =
            Result.failure(UnsupportedOperationException("unused in monitor test"))

        override suspend fun updateFilterConfig(config: OverseasOfferFilterConfig) {
            _snapshot.update { it.copy(filterConfig = config) }
        }
    }

    private fun sampleOffer(
        id: String,
        title: String = "RackNerd Black Friday 2GB KVM VPS $10.98/yr",
        merchant: String = "RackNerd",
        summary: String = "2 vCPU / 2GB RAM / 30GB NVMe / 3TB Traffic @ Los Angeles",
    ) = OverseasDealOffer(
        id = id,
        title = title,
        originalTitle = title,
        summary = summary,
        merchant = merchant,
        sourceLink = "https://lowendtalk.com/discussion/$id/promo",
        publishedAtEpochMillis = fakeClockMillis,
    )

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("overseas_promo_monitor_test").toFile()
        storeFile = File(tempDir, OverseasPromoPreferencesStore.STORE_FILE_NAME)
        fakeRepository = FakeOverseasIntelRepository()
        preferencesStore = OverseasPromoPreferencesStore(storeFile = storeFile)
        notificationSpy = RecordingPromoNotificationDispatcher(permissionGranted = true)
        OverseasPromoWorker.registerControllerProvider(null)
    }

    @After
    fun tearDown() {
        OverseasPromoWorker.registerControllerProvider(null)
        tempDir.deleteRecursively()
    }

    @Test
    fun `first baseline initialization records historical offers without spamming notifications even after transient offline start`() = runTest {
        val historicalOffers = (1..6).map { idx ->
            sampleOffer(id = "hist-$idx", title = "Historical VPS Offer #$idx - $12/yr")
        }
        fakeRepository.serverOffers = historicalOffers

        val controller = OverseasPromoMonitorControllerImpl(
            repository = fakeRepository,
            preferencesStore = preferencesStore,
            notificationDispatcher = notificationSpy,
            scope = this,
            clockMillis = { fakeClockMillis },
            isUnmeteredNetwork = { fakeUnmeteredNetwork },
            startupDelayMillis = 0L,
        )

        // 1. Offline failure on very first check MUST NOT mark empty baseline initialized!
        fakeRepository.nextError = IOException("Network unreachable")
        val failedFirstOutcome = controller.checkNow(manualTrigger = true)
        assertTrue(failedFirstOutcome is OverseasPromoPollOutcome.Retry)
        assertFalse(preferencesStore.isBaselineInitialized())
        assertTrue(notificationSpy.dispatchedOffers.isEmpty())

        // 2. Network recovers -> first successful sync establishes baseline with ZERO notifications
        fakeRepository.nextError = null
        fakeClockMillis += 35_000L
        val baselineOutcome = controller.checkNow(manualTrigger = true)
        assertEquals(
            OverseasPromoPollOutcome.Completed(
                totalOffers = 6,
                newOffersNotified = 0,
                baselineInitializedNow = true,
            ),
            baselineOutcome,
        )
        assertTrue(preferencesStore.isBaselineInitialized())
        assertEquals(historicalOffers.map { it.id }.toSet(), preferencesStore.getSeenOfferIds())
        assertTrue(notificationSpy.dispatchedOffers.isEmpty())
    }

    @Test
    fun `new offers after baseline trigger immediate notifications, filter spam, and deduplicate across cold store restarts`() = runTest {
        val hist1 = sampleOffer(id = "101", title = "HostHatch NVMe Storage VPS $15/yr", merchant = "HostHatch")
        val hist2 = sampleOffer(id = "102", title = "GreenCloud Tokyo EPYC VPS $25/yr", merchant = "GreenCloud")
        fakeRepository.serverOffers = listOf(hist2, hist1)

        val controller = OverseasPromoMonitorControllerImpl(
            repository = fakeRepository,
            preferencesStore = preferencesStore,
            notificationDispatcher = notificationSpy,
            scope = this,
            clockMillis = { fakeClockMillis },
            startupDelayMillis = 0L,
        )

        // Poll 1: Establish baseline (0 notifications)
        controller.checkNow(manualTrigger = true)
        assertEquals(0, notificationSpy.dispatchedOffers.size)

        // Poll 2: Two valid new promos + one generic marketing buzz spam arrive
        val validNew1 = sampleOffer(id = "103", title = "DMIT LAX Pro Flash Sale $39.90/yr", merchant = "DMIT")
        val validNew2 = sampleOffer(id = "104", title = "ClawCloud HK 1Gbps KVM VPS $14/yr", merchant = "ClawCloud")
        val spamBuzz = OverseasDealOffer(
            id = "999",
            title = "99.9% Uptime High powered cloud servers 50+ locations world wide",
            originalTitle = "99.9% Uptime High powered cloud servers 50+ locations world wide",
            summary = "",
            merchant = "SpamHost",
            sourceLink = "https://lowendtalk.com/discussion/999/spam",
        )
        fakeRepository.serverOffers = listOf(validNew2, spamBuzz, validNew1, hist2, hist1)
        fakeClockMillis += 120_000L

        val secondOutcome = controller.checkNow(manualTrigger = false)
        assertEquals(
            OverseasPromoPollOutcome.Completed(
                totalOffers = 4, // spamBuzz filtered out by OverseasOfferQualityFilter
                newOffersNotified = 2,
                baselineInitializedNow = false,
            ),
            secondOutcome,
        )
        assertEquals(listOf("104", "103"), notificationSpy.dispatchedOffers.map { it.id })

        // Poll 3: Same feed -> 0 duplicate notifications
        fakeClockMillis += 120_000L
        val thirdOutcome = controller.checkNow(manualTrigger = false)
        assertEquals(
            OverseasPromoPollOutcome.Completed(
                totalOffers = 4,
                newOffersNotified = 0,
                baselineInitializedNow = false,
            ),
            thirdOutcome,
        )
        assertEquals(2, notificationSpy.dispatchedOffers.size)

        // Simulate full app process kill & cold restart reading persisted storeFile from disk
        val reloadedStore = OverseasPromoPreferencesStore(storeFile = storeFile)
        assertTrue(reloadedStore.isBaselineInitialized())
        assertTrue(reloadedStore.getSeenOfferIds().containsAll(listOf("101", "102", "103", "104")))

        val validNew3 = sampleOffer(id = "105", title = "Netcup RS 1000 G11 Promo €6.50/mo", merchant = "Netcup")
        fakeRepository.serverOffers = listOf(validNew3, validNew2, validNew1, hist2, hist1)
        fakeClockMillis += 120_000L

        val coldRestartController = OverseasPromoMonitorControllerImpl(
            repository = fakeRepository,
            preferencesStore = reloadedStore,
            notificationDispatcher = notificationSpy,
            scope = this,
            clockMillis = { fakeClockMillis },
            startupDelayMillis = 0L,
        )
        val fourthOutcome = coldRestartController.runBackgroundPollPass()
        assertEquals(
            OverseasPromoPollOutcome.Completed(
                totalOffers = 5,
                newOffersNotified = 1,
                baselineInitializedNow = false,
            ),
            fourthOutcome,
        )
        assertEquals(listOf("104", "103", "105"), notificationSpy.dispatchedOffers.map { it.id })
    }

    @Test
    fun `preferencesStore bounds seenOfferIds to maxSeenOfferIds while keeping active page-1 offers warm`() = runTest {
        // Initialize with pinned page-1 offer + 399 older offers = 400 total
        val initialIds = listOf("pinned-page-1") + (1..399).map { "old-$it" }
        preferencesStore.initializeBaseline(initialIds, fakeClockMillis)
        assertEquals(400, preferencesStore.getSeenOfferIds().size)

        // Rotate 60 brand-new offers while "pinned-page-1" stays visible on page 1
        val newBatch = (400..459).map { "fresh-$it" }
        preferencesStore.recordPollOutcome(
            currentFeedIds = listOf("pinned-page-1") + newBatch.takeLast(19),
            newlyNotifiedIds = newBatch,
            checkedAtEpochMillis = fakeClockMillis + 60_000L,
        )

        val retained = preferencesStore.getSeenOfferIds()
        assertEquals(OverseasPromoPreferencesStore.DEFAULT_MAX_SEEN_OFFER_IDS, retained.size)
        assertTrue("Active page-1 offer must be retained via MRU promotion", "pinned-page-1" in retained)
        assertTrue("Newest offers must be retained", "fresh-459" in retained)
        assertFalse("Oldest inactive offers must age out cleanly", "old-1" in retained)
    }

    @SuppressLint("RestrictedApi")
    @Test
    fun `disabling switch cancels WorkManager schedule and suppresses all polling and notifications`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val gateway = RecordingPromoWorkManagerGateway()
        val scheduler = OverseasPromoWorkScheduler(gateway)

        val controllerScope = CoroutineScope(testDispatcher)
        fakeRepository.serverOffers = listOf(sampleOffer(id = "base-1"))
        val controller = OverseasPromoMonitorControllerImpl(
            repository = fakeRepository,
            preferencesStore = preferencesStore,
            notificationDispatcher = notificationSpy,
            scope = controllerScope,
            clockMillis = { fakeClockMillis },
            startupDelayMillis = 0L,
        )

        scheduler.observe(
            scope = controllerScope,
            settingsFlow = controller.settingsFlow,
        )
        advanceUntilIdle()

        // Default notificationsEnabled = true -> scheduled immediately with 15m minimum & CONNECTED network
        assertTrue(controller.settingsFlow.value.notificationsEnabled)
        assertEquals(1, gateway.enqueueCalls.size)
        assertEquals(OverseasPromoWorkScheduler.UNIQUE_WORK_NAME, gateway.enqueueCalls.last().uniqueWorkName)
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, gateway.enqueueCalls.last().policy)
        assertEquals(
            PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS,
            gateway.enqueueCalls.last().request.workSpec.intervalDuration,
        )
        assertEquals(
            NetworkType.CONNECTED,
            gateway.enqueueCalls.last().request.workSpec.constraints.requiredNetworkType,
        )
        assertEquals(BackoffPolicy.EXPONENTIAL, gateway.enqueueCalls.last().request.workSpec.backoffPolicy)

        // Establish baseline first
        controller.checkNow(manualTrigger = true)
        val callsBeforeDisable = fakeRepository.refreshCallCount

        // User turns OFF notifications inside Overseas Intel screen
        controller.setNotificationsEnabled(false)
        advanceUntilIdle()
        assertFalse(controller.settingsFlow.value.notificationsEnabled)
        assertEquals(listOf(OverseasPromoWorkScheduler.UNIQUE_WORK_NAME), gateway.cancelCalls)

        // New offer appears while notifications are OFF -> skipped, zero network calls, zero notifications
        val whileOffOffer = sampleOffer(id = "while-off-2", title = "Silent Offer $9/yr")
        fakeRepository.serverOffers = listOf(whileOffOffer, sampleOffer(id = "base-1"))
        fakeClockMillis += 180_000L

        assertEquals(
            OverseasPromoPollOutcome.Skipped(OverseasPromoPollOutcome.Skipped.SkipReason.DISABLED),
            controller.checkNow(manualTrigger = true),
        )
        assertEquals(
            OverseasPromoPollOutcome.Skipped(OverseasPromoPollOutcome.Skipped.SkipReason.DISABLED),
            controller.runBackgroundPollPass(),
        )
        assertEquals(callsBeforeDisable, fakeRepository.refreshCallCount)
        assertTrue(notificationSpy.dispatchedOffers.isEmpty())

        // User views Overseas Intel screen (while-off-2 loaded on screen) and turns switch back ON
        fakeRepository.preloadSnapshotOffers(fakeRepository.serverOffers)
        controller.setNotificationsEnabled(true)
        advanceUntilIdle()
        assertEquals(2, gateway.enqueueCalls.size)
        // Offers already on screen when toggling ON are seeded as seen -> 0 historical spam
        assertTrue(notificationSpy.dispatchedOffers.isEmpty())

        // Subsequent brand-new offer immediately notifies
        val afterEnableOffer = sampleOffer(id = "after-on-3", title = "Fresh Flash Deal $7.99/yr")
        fakeRepository.serverOffers = listOf(afterEnableOffer, whileOffOffer, sampleOffer(id = "base-1"))
        fakeClockMillis += 120_000L
        controller.checkNow(manualTrigger = false)
        assertEquals(listOf("after-on-3"), notificationSpy.dispatchedOffers.map { it.id })

        scheduler.stopObserving()
        controller.stop()
    }

    @Test
    fun `foreground polling loop proactively detects new offers without opening screen and yields on background transition`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val controllerScope = CoroutineScope(testDispatcher)
        val deterministicStore = OverseasPromoPreferencesStore(
            storeFile = File(tempDir, "fg_loop_prefs.json"),
            ioDispatcher = testDispatcher,
        )
        fakeRepository.serverOffers = listOf(sampleOffer(id = "init-1"))

        val controller = OverseasPromoMonitorControllerImpl(
            repository = fakeRepository,
            preferencesStore = deterministicStore,
            notificationDispatcher = notificationSpy,
            scope = controllerScope,
            clockMillis = { testScheduler.currentTime + 1_700_000_000_000L },
            startupDelayMillis = 1_000L,
        )

        // App starts in foreground -> runs initial baseline poll after startupDelayMillis
        controller.start()
        assertTrue(controller.isForegroundLoopActive())
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, fakeRepository.refreshCallCount)
        assertTrue(deterministicStore.isBaselineInitialized())
        assertTrue(notificationSpy.dispatchedOffers.isEmpty())

        // User stays on another screen for 2 minutes; new promo appears -> detected automatically!
        fakeRepository.serverOffers = listOf(
            sampleOffer(id = "auto-fg-2", title = "SpartanHost Seattle DDoS KVM $24/yr", merchant = "SpartanHost"),
            sampleOffer(id = "init-1"),
        )
        advanceTimeBy(2L * 60_000L)
        runCurrent()
        assertEquals(2, fakeRepository.refreshCallCount)
        assertEquals(listOf("auto-fg-2"), notificationSpy.dispatchedOffers.map { it.id })

        // App moves to background -> foreground coroutine exits cleanly
        controller.onForegroundChanged(false)
        runCurrent()
        assertFalse(controller.isForegroundLoopActive())

        // App returns to foreground -> foreground coroutine resumes
        controller.onForegroundChanged(true)
        assertTrue(controller.isForegroundLoopActive())
        controller.stop()
        assertFalse(controller.isForegroundLoopActive())

        // Also verify eager UnconfinedTestDispatcher safety (no monitorJob assignment race)
        val unconfinedDispatcher = UnconfinedTestDispatcher(testScheduler)
        val eagerStore = OverseasPromoPreferencesStore(
            storeFile = File(tempDir, "eager_loop_prefs.json"),
            ioDispatcher = unconfinedDispatcher,
        )
        val eagerController = OverseasPromoMonitorControllerImpl(
            repository = fakeRepository,
            preferencesStore = eagerStore,
            notificationDispatcher = notificationSpy,
            scope = CoroutineScope(unconfinedDispatcher),
            clockMillis = { fakeClockMillis },
            startupDelayMillis = 0L,
        )
        eagerController.start()
        assertTrue(eagerController.isForegroundLoopActive())
        eagerController.onForegroundChanged(false)
        assertFalse(eagerController.isForegroundLoopActive())
        eagerController.stop()
    }

    @Test
    fun `OverseasPromoWorker executes background poll pass and maps outcomes to WorkManager Result`() = runTest {
        fakeRepository.serverOffers = listOf(sampleOffer(id = "bg-base-1"))

        val controller = OverseasPromoMonitorControllerImpl(
            repository = fakeRepository,
            preferencesStore = preferencesStore,
            notificationDispatcher = notificationSpy,
            scope = this,
            clockMillis = { fakeClockMillis },
            isUnmeteredNetwork = { fakeUnmeteredNetwork },
            startupDelayMillis = 0L,
        )
        // Seed baseline
        controller.runBackgroundPollPass()
        assertEquals(0, notificationSpy.dispatchedOffers.size)

        val hostContext = FakeMonitorAppContext(controllerProvider = { controller })

        // 1. Transient network failure -> Result.retry()
        fakeRepository.nextError = IOException("Socket timeout")
        fakeClockMillis += 60_000L
        val retryWorker = TestListenableWorkerBuilder<OverseasPromoWorker>(hostContext).build()
        assertEquals(ListenableWorker.Result.retry(), retryWorker.doWork())
        assertEquals(0, notificationSpy.dispatchedOffers.size)

        // 2. Wi-Fi only in background on metered cellular network -> Skipped -> Result.success()
        fakeRepository.nextError = null
        controller.updateSettings { it.copy(wifiOnlyInBackground = true) }
        fakeUnmeteredNetwork = false
        fakeClockMillis += 60_000L // past 30s initial error backoff
        val meteredOutcome = controller.runBackgroundPollPass()
        assertEquals(
            OverseasPromoPollOutcome.Skipped(
                OverseasPromoPollOutcome.Skipped.SkipReason.UNMETERED_NETWORK_REQUIRED,
            ),
            meteredOutcome,
        )

        // 3. Unmetered network restored + new offer published -> Worker dispatches notification & Result.success()
        fakeUnmeteredNetwork = true
        fakeRepository.serverOffers = listOf(
            sampleOffer(id = "bg-new-2", title = "BandwagonHost Megabox Pro $49/yr", merchant = "BandwagonHost"),
            sampleOffer(id = "bg-base-1"),
        )
        fakeClockMillis += 60_000L
        val okWorker = TestListenableWorkerBuilder<OverseasPromoWorker>(hostContext).build()
        assertEquals(ListenableWorker.Result.success(), okWorker.doWork())
        assertEquals(listOf("bg-new-2"), notificationSpy.dispatchedOffers.map { it.id })

        // 4. Direct WorkerFactory runner verification
        val directRetryWorker = TestListenableWorkerBuilder<OverseasPromoWorker>(FakeMonitorAppContext())
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = OverseasPromoWorker(
                        appContext = appContext,
                        workerParams = workerParameters,
                        backgroundPollRunner = { OverseasPromoPollOutcome.Retry("503 Service Unavailable") },
                    )
                },
            )
            .build()
        assertEquals(ListenableWorker.Result.retry(), directRetryWorker.doWork())
    }

    @Test
    fun `notification tap routing, channel constants, TG channel URL, and Android 13 permission matrix work as expected`() {
        // Channel & TG monitor channel URL constants
        assertEquals("pulse_overseas_promos", PulseNotificationDispatcher.CHANNEL_OVERSEAS_PROMOS)
        assertEquals("海外情报促销推送", PulseNotificationDispatcher.CHANNEL_OVERSEAS_PROMOS_NAME)
        assertEquals("https://t.me/colmidad_lowendtalk_offer", OVERSEAS_MONITOR_TG_CHANNEL_URL)
        assertEquals(
            "https://t.me/colmidad_lowendtalk_offer",
            OverseasPromoMonitorSettings.TELEGRAM_MONITOR_CHANNEL_URL,
        )

        // Single offer tap target -> OverseasIntelDetailRoute
        val encodedDetail = PulseNotificationDispatcher.encodeOverseasPromoTarget(
            offerId = "offer-8821",
            title = "RackNerd $10.98/yr KVM",
            sourceUrl = "https://lowendtalk.com/discussion/8821/racknerd",
        )
        assertEquals(
            OverseasIntelDetailRoute(
                offerId = "offer-8821",
                initialTitle = "RackNerd $10.98/yr KVM",
                initialSourceUrl = "https://lowendtalk.com/discussion/8821/racknerd",
            ),
            PulseNotificationDispatcher.decodeOverseasPromoTarget(encodedDetail),
        )

        // Summary / list tap target -> OverseasIntelRoute
        val encodedList = PulseNotificationDispatcher.encodeOverseasPromoTarget(offerId = null)
        assertEquals(OverseasIntelRoute, PulseNotificationDispatcher.decodeOverseasPromoTarget(encodedList))

        // Notification title & summary formatting (including stripping any stray "Telegram" word)
        val offer = OverseasDealOffer(
            id = "42",
            title = "Spring NVMe Special $12/yr",
            summary = "Telegram 促销通知：2C4G / 60G NVMe",
            merchant = "CloudCone",
            sourceLink = "https://lowendtalk.com/discussion/42/cloudcone",
        )
        assertEquals(
            "海外促销 · CloudCone：Spring NVMe Special $12/yr",
            PulseNotificationDispatcher.formatOverseasPromoNotificationTitle(offer),
        )
        val formattedSummary = PulseNotificationDispatcher.formatOverseasPromoNotificationSummary(offer)
        assertFalse(formattedSummary.contains("telegram", ignoreCase = true))
        assertTrue(formattedSummary.contains("2C4G / 60G NVMe"))

        // Android 13+ permission & switch decision matrix
        assertFalse(resolveEffectivePromoNotificationsEnabled(preferenceEnabled = true, hasPostNotificationsPermission = false))
        assertTrue(resolveEffectivePromoNotificationsEnabled(preferenceEnabled = true, hasPostNotificationsPermission = true))
        assertFalse(resolveEffectivePromoNotificationsEnabled(preferenceEnabled = false, hasPostNotificationsPermission = true))

        assertEquals(
            OverseasPromoToggleDecision.RequestRuntimePostNotificationsPermission,
            resolveOverseasPromoToggleDecision(
                targetEnabled = true,
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                runtimePermissionGranted = false,
                systemNotificationsEnabled = false,
            ),
        )
        assertEquals(
            OverseasPromoToggleDecision.EnableNotificationsImmediately,
            resolveOverseasPromoToggleDecision(
                targetEnabled = true,
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                runtimePermissionGranted = true,
                systemNotificationsEnabled = true,
            ),
        )
        assertEquals(
            OverseasPromoToggleDecision.GuideToSystemNotificationSettings,
            resolveOverseasPromoToggleDecision(
                targetEnabled = true,
                sdkInt = Build.VERSION_CODES.S_V2,
                runtimePermissionGranted = true,
                systemNotificationsEnabled = false,
            ),
        )
        assertEquals(
            OverseasPromoToggleDecision.DisableNotifications,
            resolveOverseasPromoToggleDecision(
                targetEnabled = false,
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                runtimePermissionGranted = false,
                systemNotificationsEnabled = false,
            ),
        )
    }
}
