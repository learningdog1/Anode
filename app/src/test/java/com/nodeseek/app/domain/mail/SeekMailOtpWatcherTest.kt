package com.nodeseek.app.domain.mail

import com.nodeseek.app.data.repository.FakeSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class SeekMailOtpWatcherTest {

    private class RecordingNotificationSink : OtpWatchNotificationSink {
        var ensuredCount = 0
        val watchingUpdates = mutableListOf<OtpWatchState.Watching>()
        val foundNotifications = mutableListOf<OtpWatchState.Found>()
        val timedOutNotifications = mutableListOf<OtpWatchState.TimedOut>()
        var cancelWatchingCount = 0

        override fun ensureChannel() {
            ensuredCount++
        }

        override fun postWatchingNotification(state: OtpWatchState.Watching) {
            watchingUpdates += state
        }

        override fun postOtpFoundNotification(found: OtpWatchState.Found) {
            foundNotifications += found
        }

        override fun postTimedOutNotification(timedOut: OtpWatchState.TimedOut) {
            timedOutNotifications += timedOut
        }

        override fun cancelWatchingNotification() {
            cancelWatchingCount++
        }
    }

    private class FakeWatchSeekMailRepository : SeekMailRepository {
        override val locallyReadMailIds: StateFlow<Set<Long>> = MutableStateFlow(emptySet<Long>()).asStateFlow()
        private val _latestRateLimit = MutableStateFlow<SeekMailRateLimit?>(null)
        override val latestRateLimit: StateFlow<SeekMailRateLimit?> = _latestRateLimit.asStateFlow()

        var mailsHandler: suspend (pollIndex: Int, forceRefresh: Boolean) -> Result<SeekMailPage> = { _, _ ->
            Result.success(SeekMailPage(emptyList(), 20, 0, 0, false))
        }
        var detailHandler: suspend (mailId: Long, forceRefresh: Boolean) -> Result<SeekMailDetail> = { id, _ ->
            Result.failure(SeekMailException(SeekMailErrorKind.NOT_FOUND, "missing $id"))
        }

        val getMailsCalls = mutableListOf<Boolean>()
        val getDetailCalls = mutableListOf<Pair<Long, Boolean>>()
        private var pollIdx = 0

        fun setRateLimit(rl: SeekMailRateLimit?) {
            _latestRateLimit.value = rl
        }

        override suspend fun verifyApiKey(candidateKey: String?): Result<SeekMailConnectionStatus> =
            Result.failure(UnsupportedOperationException())

        override suspend fun getMailboxes(forceRefresh: Boolean): Result<List<SeekMailbox>> =
            Result.success(emptyList())

        override suspend fun checkMailboxAvailable(address: String): Result<Boolean> =
            Result.success(true)

        override suspend fun createMailbox(address: String): Result<SeekMailbox> =
            Result.failure(UnsupportedOperationException())

        override suspend fun getMails(
            mailboxId: Long?,
            search: String?,
            tagId: Long?,
            limit: Int,
            offset: Int,
            forceRefresh: Boolean,
        ): Result<SeekMailPage> {
            getMailsCalls += forceRefresh
            return mailsHandler(pollIdx++, forceRefresh)
        }

        override suspend fun getUnreadMails(
            mailboxId: Long?,
            limit: Int,
            cursor: String?,
        ): Result<SeekMailUnreadPage> = Result.success(SeekMailUnreadPage(emptyList(), 50, false))

        override suspend fun getMailDetail(mailId: Long, forceRefresh: Boolean): Result<SeekMailDetail> {
            getDetailCalls += mailId to forceRefresh
            return detailHandler(mailId, forceRefresh)
        }

        override suspend fun getAttachments(mailId: Long): Result<List<SeekMailAttachment>> =
            Result.success(emptyList())

        override suspend fun downloadAttachmentBytes(mailId: Long, attachmentId: String): Result<ByteArray> =
            Result.failure(UnsupportedOperationException())

        override suspend fun getTags(forceRefresh: Boolean): Result<List<SeekMailTag>> =
            Result.success(emptyList())

        override suspend fun getContacts(limit: Int, forceRefresh: Boolean): Result<List<SeekMailContact>> =
            Result.success(emptyList())

        override suspend fun markMailReadLocally(mailId: Long) = Unit

        override suspend fun clearCache() = Unit
    }

    private fun summary(
        id: Long,
        subject: String = "Notice",
        snippet: String = "",
        verificationCode: String? = null,
        fromName: String = "GitHub",
        receivedAt: String = "2026-11-26T06:00:00Z",
    ) = SeekMailSummary(
        id = id,
        mailboxId = 10L,
        fromAddress = "noreply@github.com",
        fromName = fromName,
        toAddress = "otp@seek.li",
        subject = subject,
        snippet = snippet,
        verificationCode = verificationCode,
        receivedAt = receivedAt,
    )

    @Test
    fun `ignores baseline pre-existing OTP mail and extracts new OTP from detail fallback`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settings = FakeSettingsRepository().apply {
            setSeekMailApiKey("sk_live_test_key_123456")
        }
        val repo = FakeWatchSeekMailRepository()
        val sink = RecordingNotificationSink()

        val oldOtpMail = summary(id = 100L, subject = "Old OTP 111222", verificationCode = "111222")
        val nonOtpNewMail = summary(id = 101L, subject = "Welcome", snippet = "No digits here")
        val targetOtpMail = summary(id = 102L, subject = "Your login verification", snippet = "Click below")

        repo.mailsHandler = { idx, _ ->
            when (idx) {
                0 -> Result.success(SeekMailPage(listOf(oldOtpMail), 20, 0, 1, false))
                1 -> Result.success(SeekMailPage(listOf(nonOtpNewMail, oldOtpMail), 20, 0, 2, false))
                else -> Result.success(SeekMailPage(listOf(targetOtpMail, nonOtpNewMail, oldOtpMail), 20, 0, 3, false))
            }
        }
        repo.detailHandler = { mailId, _ ->
            when (mailId) {
                101L -> Result.success(
                    SeekMailDetail(
                        summary = nonOtpNewMail,
                        bodyText = "Welcome aboard! No code in this email.",
                    ),
                )
                102L -> Result.success(
                    SeekMailDetail(
                        summary = targetOtpMail,
                        bodyText = "您的验证码是 839-201，请在 5 分钟内输入。",
                    ),
                )
                else -> Result.failure(IllegalStateException("Unexpected detail fetch $mailId"))
            }
        }

        val watcher = DefaultSeekMailOtpWatcher(
            seekMailRepository = repo,
            settingsRepository = settings,
            notificationSink = sink,
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            clockMillis = { testScheduler.currentTime },
        )

        watcher.startWatch(mailboxId = 10L, mailboxAddress = "otp@seek.li")
        runCurrent()
        assertTrue(watcher.state.value is OtpWatchState.Watching)

        // Advance past first 4s poll -> inspects 101L (non-OTP) and adds to baselineIds
        advanceTimeBy(4_500L)
        runCurrent()
        assertTrue(watcher.state.value is OtpWatchState.Watching)
        assertEquals(listOf(101L to true), repo.getDetailCalls)

        // Advance past second 4s poll -> 101L is NOT re-fetched; 102L is fetched and resolves 839201
        advanceTimeBy(4_500L)
        runCurrent()
        val found = watcher.state.value as OtpWatchState.Found
        assertEquals("839201", found.code)
        assertEquals(102L, found.mailId)
        assertEquals("otp@seek.li", found.mailboxAddress)
        assertEquals(1, sink.foundNotifications.size)
        assertEquals("839201", sink.foundNotifications.single().code)
        assertTrue(sink.cancelWatchingCount >= 1)
        assertEquals(listOf(101L to true, 102L to true), repo.getDetailCalls)
        assertTrue(repo.getMailsCalls.all { it })

        // Advancing time further does not poll again
        val callsAfterFound = repo.getMailsCalls.size
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(callsAfterFound, repo.getMailsCalls.size)
    }

    @Test
    fun `respects rate limit backoff and transitions to TimedOut at 180s`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settings = FakeSettingsRepository().apply {
            setSeekMailApiKey("sk_live_test_key_123456")
        }
        val repo = FakeWatchSeekMailRepository()
        val sink = RecordingNotificationSink()

        // Advance virtual clock above 1_000_000s so resetEpochSeconds math is realistic
        advanceTimeBy(1_700_000_000_000L)
        val startSec = testScheduler.currentTime / 1000L

        repo.mailsHandler = { idx, _ ->
            when (idx) {
                0 -> Result.success(SeekMailPage(emptyList(), 20, 0, 0, false))
                1 -> {
                    repo.setRateLimit(SeekMailRateLimit(limit = 60, remaining = 1, resetEpochSeconds = startSec + 30L))
                    Result.failure(SeekMailException(SeekMailErrorKind.RATE_LIMITED, "429", httpStatus = 429))
                }
                else -> Result.success(SeekMailPage(emptyList(), 20, 0, 0, false))
            }
        }

        val watcher = DefaultSeekMailOtpWatcher(
            seekMailRepository = repo,
            settingsRepository = settings,
            notificationSink = sink,
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            clockMillis = { testScheduler.currentTime },
        )

        watcher.startWatch(mailboxId = 10L, mailboxAddress = "otp@seek.li", durationMillis = 180_000L)
        runCurrent()
        // First poll at 4s hits RATE_LIMITED with reset at +30s
        advanceTimeBy(4_500L)
        runCurrent()
        assertEquals(2, repo.getMailsCalls.size)

        // During next 10s (before reset +30s), watcher backs off and does NOT hammer API
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(2, repo.getMailsCalls.size)

        // Advance past end of 3-min window -> transitions to TimedOut
        advanceTimeBy(170_000L)
        runCurrent()
        assertTrue(watcher.state.value is OtpWatchState.TimedOut)
        assertEquals(1, sink.timedOutNotifications.size)
    }

    @Test
    fun `stopWatch cancels cleanly and missing key or unauthorized emits Error`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settings = FakeSettingsRepository()
        val repo = FakeWatchSeekMailRepository()
        val sink = RecordingNotificationSink()

        val watcher = DefaultSeekMailOtpWatcher(
            seekMailRepository = repo,
            settingsRepository = settings,
            notificationSink = sink,
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            clockMillis = { testScheduler.currentTime },
        )

        // Missing API key -> Error immediately
        watcher.startWatch(mailboxId = 1L)
        assertTrue(watcher.state.value is OtpWatchState.Error)

        // Valid API key -> start & stopWatch returns to Idle
        settings.setSeekMailApiKey("sk_live_test_key_123456")
        watcher.startWatch(mailboxId = 1L)
        runCurrent()
        assertTrue(watcher.state.value is OtpWatchState.Watching)

        watcher.stopWatch()
        assertEquals(OtpWatchState.Idle, watcher.state.value)
        val callsAtStop = repo.getMailsCalls.size
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(callsAtStop, repo.getMailsCalls.size)

        // 401 Unauthorized -> Error
        repo.mailsHandler = { _, _ ->
            Result.failure(SeekMailException(SeekMailErrorKind.UNAUTHORIZED, "Invalid key", httpStatus = 401))
        }
        watcher.startWatch(mailboxId = 1L)
        runCurrent()
        assertTrue(watcher.state.value is OtpWatchState.Error)
    }

    @Test
    fun `concurrent stopWatch and startWatch never clobber new watch state or cancel active notification`() {
        val settings = FakeSettingsRepository().apply {
            kotlinx.coroutines.runBlocking { setSeekMailApiKey("sk_live_test_key_123456") }
        }
        val repo = FakeWatchSeekMailRepository()
        val watchingNotificationActive = AtomicInteger(0)
        val slowCancelSink = object : OtpWatchNotificationSink {
            override fun ensureChannel() = Unit
            override fun postWatchingNotification(state: OtpWatchState.Watching) {
                watchingNotificationActive.set(1)
            }
            override fun postOtpFoundNotification(found: OtpWatchState.Found) = Unit
            override fun postTimedOutNotification(timedOut: OtpWatchState.TimedOut) = Unit
            override fun cancelWatchingNotification() {
                Thread.sleep(1L)
                watchingNotificationActive.set(0)
            }
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val watcher = DefaultSeekMailOtpWatcher(
                seekMailRepository = repo,
                settingsRepository = settings,
                notificationSink = slowCancelSink,
                scope = scope,
                ioDispatcher = Dispatchers.IO,
            )

            var clobberedCount = 0
            for (round in 0 until 100) {
                watcher.startWatch(mailboxId = 10L, durationMillis = 30_000L)
                val ready = CountDownLatch(2)
                val done = CountDownLatch(2)
                executor.submit {
                    ready.countDown()
                    ready.await(2, TimeUnit.SECONDS)
                    watcher.stopWatch()
                    done.countDown()
                }
                executor.submit {
                    ready.countDown()
                    ready.await(2, TimeUnit.SECONDS)
                    Thread.sleep(0, 200_000)
                    watcher.startWatch(mailboxId = 11L + round, durationMillis = 30_000L)
                    done.countDown()
                }
                assertTrue(done.await(5, TimeUnit.SECONDS))
                val immediateState = watcher.state.value
                Thread.sleep(15L)
                val settledState = watcher.state.value
                // If immediateState was clobbered to Idle while settledState became Watching, that's the race!
                if (immediateState is OtpWatchState.Idle && settledState is OtpWatchState.Watching) {
                    clobberedCount++
                }
                // If final state is Watching, its notification MUST still be active
                if (settledState is OtpWatchState.Watching && watchingNotificationActive.get() != 1) {
                    clobberedCount++
                }
                watcher.stopWatch()
            }
            assertEquals("Expected zero concurrent stopWatch/startWatch clobbers", 0, clobberedCount)
        } finally {
            executor.shutdownNow()
            scope.cancel()
        }
    }

    @Test
    fun `terminal Found TimedOut and Error states publish only after notificationSink completes under yielding sink`() = runBlocking {
        val settings = FakeSettingsRepository().apply {
            setSeekMailApiKey("sk_live_test_key_123456")
        }
        val cancelCompleted = AtomicInteger(0)
        val watchingActive = AtomicBoolean(false)
        val foundNotifications = CopyOnWriteArrayList<OtpWatchState.Found>()
        val timedOutNotifications = CopyOnWriteArrayList<OtpWatchState.TimedOut>()

        val yieldingSink = object : OtpWatchNotificationSink {
            override fun ensureChannel() = Unit

            override fun postWatchingNotification(state: OtpWatchState.Watching) {
                watchingActive.set(true)
            }

            override fun postOtpFoundNotification(found: OtpWatchState.Found) {
                Thread.sleep(2L)
                foundNotifications.add(found)
            }

            override fun postTimedOutNotification(timedOut: OtpWatchState.TimedOut) {
                Thread.sleep(2L)
                timedOutNotifications.add(timedOut)
            }

            override fun cancelWatchingNotification() {
                Thread.sleep(2L)
                watchingActive.set(false)
                cancelCompleted.incrementAndGet()
            }
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // 1. Found across 10 trials with yielding sink (pre-fix race failed 10/10)
            for (trial in 1..10) {
                val repo = FakeWatchSeekMailRepository()
                val oldMail = summary(id = 100L, subject = "Old", verificationCode = "111222")
                val newMail = summary(id = 200L + trial, subject = "Code", verificationCode = "991122")
                val pollCounter = AtomicInteger(0)
                repo.mailsHandler = { _, _ ->
                    val items = if (pollCounter.getAndIncrement() == 0) {
                        listOf(oldMail)
                    } else {
                        listOf(newMail, oldMail)
                    }
                    Result.success(SeekMailPage(items, 20, 0, items.size, false))
                }
                val watcher = DefaultSeekMailOtpWatcher(
                    seekMailRepository = repo,
                    settingsRepository = settings,
                    notificationSink = yieldingSink,
                    scope = scope,
                    ioDispatcher = Dispatchers.IO,
                    delayFn = { _ -> kotlinx.coroutines.yield() },
                )
                val cancelBefore = cancelCompleted.get()
                val foundBefore = foundNotifications.size
                watcher.startWatch(mailboxId = 6L, durationMillis = 30_000L)

                val found = withContext(Dispatchers.Default) {
                    withTimeout(5_000L) {
                        watcher.state.first { it is OtpWatchState.Found } as OtpWatchState.Found
                    }
                }
                assertEquals("991122", found.code)
                assertEquals(
                    "Trial $trial: postOtpFoundNotification must complete before Found is observed",
                    foundBefore + 1,
                    foundNotifications.size,
                )
                assertTrue(
                    "Trial $trial: cancelWatchingNotification must complete before Found is observed",
                    cancelCompleted.get() >= cancelBefore + 1,
                )
                assertFalse("Trial $trial: watching notification must be inactive", watchingActive.get())
            }

            // 2. TimedOut with yielding sink
            val timeoutRepo = FakeWatchSeekMailRepository()
            val virtualClock = AtomicLong(1_700_000_000_000L)
            val timeoutWatcher = DefaultSeekMailOtpWatcher(
                seekMailRepository = timeoutRepo,
                settingsRepository = settings,
                notificationSink = yieldingSink,
                scope = scope,
                ioDispatcher = Dispatchers.IO,
                clockMillis = { virtualClock.get() },
                delayFn = { ms ->
                    virtualClock.addAndGet(ms)
                    kotlinx.coroutines.yield()
                },
            )
            val cancelBeforeTimeout = cancelCompleted.get()
            val timedOutBefore = timedOutNotifications.size
            timeoutWatcher.startWatch(mailboxId = 7L, durationMillis = 5_000L)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    timeoutWatcher.state.first { it is OtpWatchState.TimedOut }
                }
            }
            assertEquals(
                "postTimedOutNotification must complete before TimedOut is observed",
                timedOutBefore + 1,
                timedOutNotifications.size,
            )
            assertTrue(cancelCompleted.get() >= cancelBeforeTimeout + 1)
            assertFalse(watchingActive.get())

            // 3. Fatal Error (401) with yielding sink
            val errorRepo = FakeWatchSeekMailRepository().apply {
                mailsHandler = { _, _ ->
                    Result.failure(SeekMailException(SeekMailErrorKind.UNAUTHORIZED, "Unauthorized", httpStatus = 401))
                }
            }
            val errorWatcher = DefaultSeekMailOtpWatcher(
                seekMailRepository = errorRepo,
                settingsRepository = settings,
                notificationSink = yieldingSink,
                scope = scope,
                ioDispatcher = Dispatchers.IO,
                delayFn = { _ -> kotlinx.coroutines.yield() },
            )
            val cancelBeforeError = cancelCompleted.get()
            errorWatcher.startWatch(mailboxId = 8L, durationMillis = 30_000L)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    errorWatcher.state.first { it is OtpWatchState.Error }
                }
            }
            assertTrue(
                "cancelWatchingNotification must complete before Error is observed",
                cancelCompleted.get() >= cancelBeforeError + 1,
            )
            assertFalse(watchingActive.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `immediate startWatch upon observing Found never receives stale postOtpFoundNotification after new postWatchingNotification`() = runBlocking {
        val settings = FakeSettingsRepository().apply {
            setSeekMailApiKey("sk_live_test_key_123456")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            for (trial in 1..10) {
                val events = CopyOnWriteArrayList<String>()
                val yieldingSink = object : OtpWatchNotificationSink {
                    override fun ensureChannel() = Unit

                    override fun postWatchingNotification(state: OtpWatchState.Watching) {
                        events.add("postWatching:mb=${state.mailboxId}")
                    }

                    override fun postOtpFoundNotification(found: OtpWatchState.Found) {
                        Thread.sleep(2L)
                        events.add("postFound:${found.code}")
                    }

                    override fun postTimedOutNotification(timedOut: OtpWatchState.TimedOut) {
                        events.add("postTimedOut:mb=${timedOut.mailboxId}")
                    }

                    override fun cancelWatchingNotification() {
                        Thread.sleep(2L)
                        events.add("cancelWatching")
                    }
                }

                val repo = FakeWatchSeekMailRepository()
                val oldMail = summary(id = 100L, subject = "Old", verificationCode = "111222")
                val newMail = summary(id = 300L + trial, subject = "Code", verificationCode = "991122")
                val gen1Polls = AtomicInteger(0)
                val gen2Active = AtomicBoolean(false)
                repo.mailsHandler = { _, _ ->
                    if (!gen2Active.get()) {
                        val items = if (gen1Polls.getAndIncrement() == 0) listOf(oldMail) else listOf(newMail, oldMail)
                        Result.success(SeekMailPage(items, 20, 0, items.size, false))
                    } else {
                        Result.success(SeekMailPage(listOf(newMail, oldMail), 20, 0, 2, false))
                    }
                }

                val watcher = DefaultSeekMailOtpWatcher(
                    seekMailRepository = repo,
                    settingsRepository = settings,
                    notificationSink = yieldingSink,
                    scope = scope,
                    ioDispatcher = Dispatchers.IO,
                    delayFn = { _ -> kotlinx.coroutines.yield() },
                )

                watcher.startWatch(mailboxId = 6L, durationMillis = 30_000L)
                withContext(Dispatchers.Default) {
                    withTimeout(5_000L) {
                        watcher.state.first { it is OtpWatchState.Found }
                    }
                }

                gen2Active.set(true)
                watcher.startWatch(mailboxId = 99L, durationMillis = 30_000L)
                Thread.sleep(10L)

                val postFoundIdx = events.indexOf("postFound:991122")
                val restartWatchingIdx = events.indexOf("postWatching:mb=99")
                assertTrue("Trial $trial: missing postFound:991122 in $events", postFoundIdx >= 0)
                assertTrue("Trial $trial: missing postWatching:mb=99 in $events", restartWatchingIdx >= 0)
                assertTrue(
                    "Trial $trial: stale postFound appeared after new postWatching in $events",
                    postFoundIdx < restartWatchingIdx,
                )
                assertTrue(
                    "Trial $trial: no postFound event allowed after restart postWatching in $events",
                    events.drop(restartWatchingIdx + 1).none { it.startsWith("postFound:") },
                )
                assertTrue(watcher.state.value is OtpWatchState.Watching)
                watcher.stopWatch()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `notificationSink exceptions do not leave watcher state or watchJob stuck`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settings = FakeSettingsRepository().apply {
            setSeekMailApiKey("sk_live_test_key_123456")
        }
        val throwingSink = object : OtpWatchNotificationSink {
            override fun ensureChannel() = Unit
            override fun postWatchingNotification(state: OtpWatchState.Watching) = Unit
            override fun postOtpFoundNotification(found: OtpWatchState.Found) {
                throw IllegalStateException("postFound failed")
            }
            override fun postTimedOutNotification(timedOut: OtpWatchState.TimedOut) {
                throw IllegalStateException("postTimedOut failed")
            }
            override fun cancelWatchingNotification() {
                throw IllegalStateException("cancelWatching failed")
            }
        }

        // 1. Found succeeds despite throwing sink
        val foundRepo = FakeWatchSeekMailRepository()
        val oldMail = summary(id = 10L, subject = "Old", verificationCode = "111222")
        val newMail = summary(id = 11L, subject = "New", verificationCode = "654321")
        foundRepo.mailsHandler = { idx, _ ->
            if (idx == 0) {
                Result.success(SeekMailPage(listOf(oldMail), 20, 0, 1, false))
            } else {
                Result.success(SeekMailPage(listOf(newMail, oldMail), 20, 0, 2, false))
            }
        }
        val foundWatcher = DefaultSeekMailOtpWatcher(
            seekMailRepository = foundRepo,
            settingsRepository = settings,
            notificationSink = throwingSink,
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            clockMillis = { testScheduler.currentTime },
        )
        foundWatcher.startWatch(mailboxId = 1L)
        runCurrent()
        advanceTimeBy(4_500L)
        runCurrent()
        val found = foundWatcher.state.value as OtpWatchState.Found
        assertEquals("654321", found.code)

        // 2. TimedOut succeeds despite throwing sink
        val timeoutRepo = FakeWatchSeekMailRepository()
        val timeoutWatcher = DefaultSeekMailOtpWatcher(
            seekMailRepository = timeoutRepo,
            settingsRepository = settings,
            notificationSink = throwingSink,
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            clockMillis = { testScheduler.currentTime },
        )
        timeoutWatcher.startWatch(mailboxId = 2L, durationMillis = 2_000L)
        runCurrent()
        advanceTimeBy(3_000L)
        runCurrent()
        assertTrue(timeoutWatcher.state.value is OtpWatchState.TimedOut)

        // 3. Fatal Error + stopWatch succeed despite throwing sink
        val errorRepo = FakeWatchSeekMailRepository().apply {
            mailsHandler = { _, _ ->
                Result.failure(SeekMailException(SeekMailErrorKind.UNAUTHORIZED, "401", httpStatus = 401))
            }
        }
        val errorWatcher = DefaultSeekMailOtpWatcher(
            seekMailRepository = errorRepo,
            settingsRepository = settings,
            notificationSink = throwingSink,
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            clockMillis = { testScheduler.currentTime },
        )
        errorWatcher.startWatch(mailboxId = 3L)
        runCurrent()
        assertTrue(errorWatcher.state.value is OtpWatchState.Error)

        errorWatcher.stopWatch()
        assertEquals(OtpWatchState.Idle, errorWatcher.state.value)
    }

    @Test
    fun `initial baseline failure recovers without swallowing newly arrived OTP mail during outage`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settings = FakeSettingsRepository().apply {
            setSeekMailApiKey("sk_live_test_key_123456")
        }
        val repo = FakeWatchSeekMailRepository()
        val sink = RecordingNotificationSink()

        val watchStartIso = "2026-11-26T10:00:00Z"
        val startMillis = java.time.Instant.parse(watchStartIso).toEpochMilli()
        val preWatchMail = summary(
            id = 50L,
            subject = "Old Pre-Watch OTP 111111",
            verificationCode = "111111",
            receivedAt = "2026-11-26T09:50:00Z",
        )
        val newArrivedOtpMail = summary(
            id = 51L,
            subject = "Your verification code",
            verificationCode = "778899",
            receivedAt = "2026-11-26T10:00:03Z",
        )

        var callCount = 0
        repo.mailsHandler = { _, _ ->
            callCount++
            if (callCount == 1) {
                // Initial baseline fails with 500 outage
                Result.failure(SeekMailException(SeekMailErrorKind.SERVER, "500", httpStatus = 500))
            } else {
                // Recovery poll returns both old mail and newly arrived OTP mail
                Result.success(SeekMailPage(listOf(newArrivedOtpMail, preWatchMail), 20, 0, 2, false))
            }
        }

        val watcher = DefaultSeekMailOtpWatcher(
            seekMailRepository = repo,
            settingsRepository = settings,
            notificationSink = sink,
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            clockMillis = { startMillis + testScheduler.currentTime },
        )

        watcher.startWatch(mailboxId = 10L, mailboxAddress = "otp@seek.li", durationMillis = 180_000L)
        runCurrent()
        assertTrue(watcher.state.value is OtpWatchState.Watching)

        advanceTimeBy(4_500L)
        runCurrent()

        val found = watcher.state.value as? OtpWatchState.Found
        assertNotNull("Expected OtpWatchState.Found on recovery poll, got ${watcher.state.value}", found)
        assertEquals("778899", found!!.code)
        assertEquals(51L, found.mailId)
    }
}
