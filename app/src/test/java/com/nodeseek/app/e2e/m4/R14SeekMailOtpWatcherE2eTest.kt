package com.nodeseek.app.e2e.m4

import com.nodeseek.app.data.mail.SeekMailApiClient
import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.domain.mail.OtpWatchNotificationSink
import com.nodeseek.app.domain.mail.OtpWatchState
import com.nodeseek.app.domain.mail.SeekMailErrorKind
import com.nodeseek.app.domain.mail.SeekMailException
import com.nodeseek.app.domain.mail.VerificationCodeExtractor
import com.nodeseek.app.e2e.E2eFixtures
import com.nodeseek.app.e2e.E2eMainDispatcherRule
import com.nodeseek.app.e2e.E2eOtpWatchNotificationSink
import com.nodeseek.app.e2e.E2eTestHarness
import com.nodeseek.app.notifications.CopyOtpBroadcastReceiver
import com.nodeseek.app.notifications.OtpWatchNotificationHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.DocumentBuilderFactory

class R14SeekMailOtpWatcherE2eTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    private val appModuleDir: File by lazy {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        when {
            File(cwd, "src/main/AndroidManifest.xml").isFile -> cwd
            File(cwd, "app/src/main/AndroidManifest.xml").isFile -> File(cwd, "app")
            else -> error("Unable to locate app module directory from ${cwd.absolutePath}")
        }
    }

    private fun parseManifest(): Document {
        val file = File(appModuleDir, "src/main/AndroidManifest.xml")
        assertTrue("Missing AndroidManifest.xml: ${file.absolutePath}", file.isFile)
        return DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
    }

    private fun Element.androidAttr(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)
            .ifEmpty { getAttribute("android:$name") }

    @Test
    fun `E2E-T1-R14-1 end-to-end SeekMailViewModel 3-min OTP watch ignores baseline skips non-OTP resolves detail fallback and leaks zero cookies`() = runBlocking {
        E2eTestHarness().use { harness ->
            val apiKey = "sk_live_" + "1".repeat(32)
            harness.seedAuthenticatedSession(uid = 101L, sessionCookie = "r14_forum_sess", cfClearance = "r14_cf")
            harness.settingsRepository.setSeekMailApiKey(apiKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first { it.seekMailApiKey == apiKey }
                }
            }

            val fixture = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("mail/seekmail_otp_watch_pages.json"),
            ).jsonObject
            val mailboxesJson = fixture.getValue("mailboxes").toString()
            val baselineJson = fixture.getValue("baselineMailsPage").toString()
            val nonOtpJson = fixture.getValue("nonOtpMailsPage").toString()
            val newOtpPageJson = fixture.getValue("newOtpSummaryOnlyPage").toString()
            val nonOtpDetailJson = fixture.getValue("nonOtpDetail301").toString()
            val otpDetailJson = fixture.getValue("otpDetailNew").toString()

            val watchActive = AtomicBoolean(false)
            val watchPollIndex = AtomicInteger(0)

            harness.externalDispatcher.register("GET", "/openapi/v1/mailboxes") {
                MockResponse().setResponseCode(200).setBody(mailboxesJson)
            }
            harness.externalDispatcher.register("GET", "/openapi/v1/tags") {
                MockResponse().setResponseCode(200).setBody("""{"data":[]}""")
            }
            harness.externalDispatcher.register("GET", "/openapi/v1/mails") {
                val body = if (!watchActive.get()) {
                    baselineJson
                } else {
                    when (watchPollIndex.getAndIncrement()) {
                        0 -> baselineJson
                        1 -> nonOtpJson
                        else -> newOtpPageJson
                    }
                }
                MockResponse().setResponseCode(200).setBody(body)
            }
            harness.externalDispatcher.register("GET", "/openapi/v1/mails/301") {
                MockResponse().setResponseCode(200).setBody(nonOtpDetailJson)
            }
            harness.externalDispatcher.register("GET", "/openapi/v1/mails/302") {
                MockResponse().setResponseCode(200).setBody(otpDetailJson)
            }

            val container = harness.buildAppContainer()
            val vm = harness.createSeekMailViewModel(initialMailboxId = 5L, container = container)
            val stateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }

            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        it.hasConfiguredKey &&
                            it.mailboxes.isNotEmpty() &&
                            !it.isLoadingMails &&
                            !it.isLoadingMailboxes
                    }
                }
            }

            assertEquals(5L, vm.uiState.value.selectedMailboxId)
            assertEquals("otp@seek.li", vm.uiState.value.selectedMailbox?.address)

            harness.externalDispatcher.clearRecordedRequests()
            watchActive.set(true)
            vm.startOtpWatch()

            val foundState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.otpWatchState is OtpWatchState.Found }
                }
            }
            val found = foundState.otpWatchState as OtpWatchState.Found
            assertEquals("839201", found.code)
            assertEquals(302L, found.mailId)
            assertEquals("otp@seek.li", found.mailboxAddress)
            assertEquals("GitHub", found.fromDisplay)

            assertEquals(1, harness.otpWatchNotificationSink.foundNotifications.size)
            assertEquals("839201", harness.otpWatchNotificationSink.foundNotifications.single().code)
            assertTrue(harness.otpWatchNotificationSink.cancelWatchingCount.get() >= 1)

            val recorded = harness.externalDispatcher.recordedRequests()
            assertTrue(recorded.isNotEmpty())
            assertTrue(recorded.all { it.getHeader("Authorization") == "Bearer $apiKey" })
            harness.assertNoCookiesLeakedToExternalServer()

            stateJob.cancel()
        }
    }

    @Test
    fun `E2E-T1-R14-2 VerificationCodeExtractor handles server code Chinese English segmented and bridged OTPs while rejecting false positives`() {
        // Server code takes priority
        assertEquals(
            "998877",
            VerificationCodeExtractor.resolveCode(
                serverCode = " 998877 ",
                subject = "验证码 111222",
                bodyText = "Your code is 333444",
            ),
        )

        // Chinese & English keywords
        assertEquals(
            "739104",
            VerificationCodeExtractor.resolveCode(null, "安全通知", "您的验证码：739104，10分钟内有效"),
        )
        assertEquals(
            "482910",
            VerificationCodeExtractor.resolveCode(null, "Security", "Your verification code is 482910"),
        )

        // Segmented codes
        assertEquals(
            "482910",
            VerificationCodeExtractor.resolveCode(null, "Cloudflare", "您的验证码为 482-910，请勿泄露"),
        )
        assertEquals(
            "482910",
            VerificationCodeExtractor.resolveCode(null, "Google", "Your verification code is G-482910."),
        )
        assertEquals(
            "48291038",
            VerificationCodeExtractor.resolveCode(null, "Auth", "Confirmation code: 4829-1038"),
        )

        // Bridged subject-to-body
        assertEquals(
            "556677",
            VerificationCodeExtractor.resolveCode(null, "登录验证码", ": 556677"),
        )

        // False positives MUST return null
        val falsePositives = listOf(
            "Visit https://example.com/123456 for details",
            "Contact user1234@example.com for support",
            "Brand accent color #123456 and #AABBCC",
            "Request UUID 550e8400-e29b-41d4-a716-446655440000",
            "Timestamp 2026-11-26 12:34:56 UTC",
            "Encoding UTF8 and checksum SHA256 transport",
            "Copyright © 2026 NodeSeek",
            "Valid for 60 秒 / 5 minutes / 100 ms",
        )
        falsePositives.forEach { body ->
            assertNull(
                "Expected null OTP for false positive: $body",
                VerificationCodeExtractor.resolveCode(null, "General Notice", body),
            )
        }
    }

    @Test
    fun `E2E-T1-R14-3 OtpWatchNotificationHelper CopyOtpBroadcastReceiver manifest and SeekMailUiState MM SS countdown contracts`() = runBlocking {
        // Constants & ID range
        assertEquals("seek_mail_otp_watch", OtpWatchNotificationHelper.CHANNEL_OTP_WATCH)
        assertEquals(90_100, OtpWatchNotificationHelper.ID_OTP_WATCH_ONGOING)
        assertEquals(90_101, OtpWatchNotificationHelper.ID_OTP_WATCH_TIMED_OUT)
        listOf(0L, 1L, -1L, 302L, Long.MAX_VALUE, Long.MIN_VALUE).forEach { id ->
            val notifId = OtpWatchNotificationHelper.notificationIdForMail(id)
            assertTrue(
                "Expected notificationId in 91_000..98_999 for $id, got $notifId",
                notifId in 91_000..98_999,
            )
        }

        // Countdown & labels
        assertEquals("03:00", OtpWatchNotificationHelper.formatRemainingCountdown(180))
        assertEquals("02:41", OtpWatchNotificationHelper.formatRemainingCountdown(161))
        assertEquals("00:00", OtpWatchNotificationHelper.formatRemainingCountdown(0))
        assertEquals("00:00", OtpWatchNotificationHelper.formatRemainingCountdown(-5))
        assertEquals("正在监听验证码 · 剩余 02:41", OtpWatchNotificationHelper.formatWatchingTitle(161))
        assertEquals("监控邮箱：otp@seek.li · 收到后将弹窗提醒", OtpWatchNotificationHelper.formatWatchingSubtitle("otp@seek.li"))
        assertEquals("正在实时轮询 Seek 邮箱新验证码", OtpWatchNotificationHelper.formatWatchingSubtitle("  "))
        assertEquals("收到验证码：839201", OtpWatchNotificationHelper.formatFoundTitle(" 839201 "))
        assertEquals("一键复制 839201", OtpWatchNotificationHelper.formatCopyActionLabel(" 839201 "))

        val found = OtpWatchState.Found(
            code = "839201",
            mailId = 302L,
            subject = "Login Code",
            fromDisplay = "GitHub",
            mailboxAddress = "otp@seek.li",
            foundAtMillis = 1_764_000_000_000L,
        )
        assertEquals("GitHub · Login Code (otp@seek.li)", OtpWatchNotificationHelper.formatFoundSubtitle(found))

        // Manifest declaration & zero-arg receiver instantiation
        val manifest = parseManifest()
        val receivers = manifest.getElementsByTagName("receiver")
        val copyReceiver = (0 until receivers.length)
            .mapNotNull { receivers.item(it) as? Element }
            .firstOrNull { it.androidAttr("name") == ".notifications.CopyOtpBroadcastReceiver" }
        assertNotNull("CopyOtpBroadcastReceiver missing from AndroidManifest.xml", copyReceiver)
        assertEquals("false", copyReceiver!!.androidAttr("exported"))
        assertNotNull(CopyOtpBroadcastReceiver())

        // Live countdown MM:SS transitions through SeekMailViewModel
        E2eTestHarness().use { harness ->
            val apiKey = "sk_live_" + "2".repeat(32)
            harness.settingsRepository.setSeekMailApiKey(apiKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first { it.seekMailApiKey == apiKey }
                }
            }
            val fixture = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("mail/seekmail_otp_watch_pages.json"),
            ).jsonObject
            harness.externalDispatcher.register("GET", "/openapi/v1/mailboxes") {
                MockResponse().setResponseCode(200).setBody(fixture.getValue("mailboxes").toString())
            }
            harness.externalDispatcher.register("GET", "/openapi/v1/tags") {
                MockResponse().setResponseCode(200).setBody("""{"data":[]}""")
            }
            harness.externalDispatcher.register("GET", "/openapi/v1/mails") {
                MockResponse().setResponseCode(200).setBody(fixture.getValue("baselineMailsPage").toString())
            }

            val observedCountdowns = CopyOnWriteArrayList<String>()
            val gate = CompletableDeferred<Unit>()
            val watcher = harness.createSeekMailOtpWatcher(
                delayFn = { ms ->
                    harness.clock.advanceByMillis(ms)
                    kotlinx.coroutines.yield()
                    if (!gate.isCompleted) {
                        gate.await()
                    }
                },
            )
            val container = harness.buildAppContainer(overrides = mapOf("getSeekMailOtpWatcher" to watcher))
            val vm = harness.createSeekMailViewModel(initialMailboxId = 5L, container = container)
            val stateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                vm.uiState.collect { ui ->
                    val watch = ui.otpWatchState
                    if (watch is OtpWatchState.Watching) {
                        observedCountdowns.add(
                            OtpWatchNotificationHelper.formatRemainingCountdown(watch.remainingSeconds),
                        )
                    }
                }
            }

            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.hasConfiguredKey && !it.isLoadingMails && !it.isLoadingMailboxes }
                }
            }
            vm.startOtpWatch()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.otpWatchState is OtpWatchState.Watching }
                }
            }
            gate.complete(Unit)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.otpWatchState is OtpWatchState.TimedOut }
                }
            }
            assertTrue("03:00" in observedCountdowns)
            assertTrue(observedCountdowns.any { it != "03:00" && Regex("""0[0-2]:\d{2}""").matches(it) })
            stateJob.cancel()
        }
    }

    @Test
    fun `E2E-T1-R14-4 rate-limit headers and HTTP 429 trigger 10s and 15s-plus backoff before finding OTP`() = runBlocking {
        E2eTestHarness().use { harness ->
            val apiKey = "sk_live_" + "3".repeat(32)
            harness.settingsRepository.setSeekMailApiKey(apiKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first { it.seekMailApiKey == apiKey }
                }
            }

            val fixture = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("mail/seekmail_otp_watch_pages.json"),
            ).jsonObject
            val baselineJson = fixture.getValue("baselineMailsPage").toString()
            val newOtpPageJson = fixture.getValue("newOtpSummaryOnlyPage").toString()
            val otpDetailJson = fixture.getValue("otpDetailNew").toString()
            val rateLimitedJson = fixture.getValue("rateLimitError429").toString()

            val pollClocks = CopyOnWriteArrayList<Long>()
            val pollIdx = AtomicInteger(0)

            harness.externalDispatcher.register("GET", "/openapi/v1/mails") {
                val idx = pollIdx.getAndIncrement()
                val nowMs = harness.clock.nowMillis()
                pollClocks.add(nowMs)
                val nowSec = nowMs / 1000L
                when (idx) {
                    0 -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("X-Ratelimit-Limit", "60")
                        .setHeader("X-Ratelimit-Remaining", "50")
                        .setHeader("X-Ratelimit-Reset", (nowSec + 60L).toString())
                        .setBody(baselineJson)
                    1 -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("X-Ratelimit-Limit", "60")
                        .setHeader("X-Ratelimit-Remaining", "8")
                        .setHeader("X-Ratelimit-Reset", (nowSec + 60L).toString())
                        .setBody(baselineJson)
                    2 -> MockResponse()
                        .setResponseCode(429)
                        .setHeader("X-Ratelimit-Limit", "60")
                        .setHeader("X-Ratelimit-Remaining", "1")
                        .setHeader("X-Ratelimit-Reset", (nowSec + 25L).toString())
                        .setBody(rateLimitedJson)
                    else -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("X-Ratelimit-Limit", "60")
                        .setHeader("X-Ratelimit-Remaining", "59")
                        .setHeader("X-Ratelimit-Reset", (nowSec + 60L).toString())
                        .setBody(newOtpPageJson)
                }
            }
            harness.externalDispatcher.register("GET", "/openapi/v1/mails/302") {
                MockResponse().setResponseCode(200).setBody(otpDetailJson)
            }

            val watcher = harness.createSeekMailOtpWatcher()
            watcher.startWatch(mailboxId = 5L, mailboxAddress = "otp@seek.li")

            val found = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    watcher.state.first { it is OtpWatchState.Found } as OtpWatchState.Found
                }
            }
            assertEquals("839201", found.code)
            assertEquals(4, pollClocks.size)
            // Poll 0 -> Poll 1: normal 4s interval
            assertEquals(4_000L, pollClocks[1] - pollClocks[0])
            // Poll 1 -> Poll 2: low-quota (remaining=8 <= 10) 10s interval
            assertEquals(10_000L, pollClocks[2] - pollClocks[1])
            // Poll 2 (429, reset=+25s) -> Poll 3: >= 15s backoff and respects resetEpochSeconds (+25.5s -> 26s ticks)
            val backoffDelta = pollClocks[3] - pollClocks[2]
            assertTrue("Expected backoff >= 25_000ms, got $backoffDelta", backoffDelta >= 25_000L)
            assertTrue("Expected Found before 180s expiry", (pollClocks[3] - pollClocks[0]) < 180_000L)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T1-R14-5 full 180s expiry emits TimedOut dismissOtpWatchResult returns Idle and restart works cleanly`() = runBlocking {
        E2eTestHarness().use { harness ->
            val apiKey = "sk_live_" + "4".repeat(32)
            harness.settingsRepository.setSeekMailApiKey(apiKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first { it.seekMailApiKey == apiKey }
                }
            }

            val fixture = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("mail/seekmail_otp_watch_pages.json"),
            ).jsonObject
            val baselineJson = fixture.getValue("baselineMailsPage").toString()
            val newOtpPageJson = fixture.getValue("newOtpSummaryOnlyPage").toString()
            val otpDetailJson = fixture.getValue("otpDetailNew").toString()

            val serveNewOtp = AtomicBoolean(false)
            harness.externalDispatcher.register("GET", "/openapi/v1/mailboxes") {
                MockResponse().setResponseCode(200).setBody(fixture.getValue("mailboxes").toString())
            }
            harness.externalDispatcher.register("GET", "/openapi/v1/tags") {
                MockResponse().setResponseCode(200).setBody("""{"data":[]}""")
            }
            harness.externalDispatcher.register("GET", "/openapi/v1/mails") {
                val body = if (serveNewOtp.get()) newOtpPageJson else baselineJson
                MockResponse().setResponseCode(200).setBody(body)
            }
            harness.externalDispatcher.register("GET", "/openapi/v1/mails/302") {
                MockResponse().setResponseCode(200).setBody(otpDetailJson)
            }

            val vm = harness.createSeekMailViewModel(initialMailboxId = 5L)
            val stateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.hasConfiguredKey && !it.isLoadingMails && !it.isLoadingMailboxes }
                }
            }

            val startMs = harness.clock.nowMillis()
            vm.startOtpWatch()

            val timedOutState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.otpWatchState is OtpWatchState.TimedOut }
                }
            }
            val timedOut = timedOutState.otpWatchState as OtpWatchState.TimedOut
            assertEquals(5L, timedOut.mailboxId)
            assertEquals(startMs + 180_000L, timedOut.expiredAtMillis)
            assertEquals(1, harness.otpWatchNotificationSink.timedOutNotifications.size)

            vm.dismissOtpWatchResult()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.otpWatchState == OtpWatchState.Idle }
                }
            }

            // Restart watcher cleanly and capture new OTP
            val restartPhase = AtomicInteger(0)
            harness.externalDispatcher.register("GET", "/openapi/v1/mails") {
                val body = if (restartPhase.getAndIncrement() == 0) baselineJson else newOtpPageJson
                MockResponse().setResponseCode(200).setBody(body)
            }
            vm.startOtpWatch()
            val found = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.otpWatchState is OtpWatchState.Found }.otpWatchState as OtpWatchState.Found
                }
            }
            assertEquals("839201", found.code)
            stateJob.cancel()
        }
    }

    @Test
    fun `E2E-T2-R14-1 missing key invalid key format HTTP 401 and HTTP 403 emit Error immediately without polling loop`() = runBlocking {
        E2eTestHarness().use { harness ->
            val watcher = harness.createSeekMailOtpWatcher()

            // 1. Missing API key
            watcher.startWatch(mailboxId = 5L)
            assertTrue(watcher.state.value is OtpWatchState.Error)
            assertTrue(harness.otpWatchNotificationSink.cancelWatchingCount.get() >= 1)
            assertTrue(harness.externalDispatcher.recordedRequests().isEmpty())

            // 2. Invalid key format (newline/control chars)
            val badFormatKey = "sk_live_invalid\nheader"
            harness.settingsRepository.setSeekMailApiKey(badFormatKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first { it.seekMailApiKey == badFormatKey }
                }
            }
            watcher.startWatch(mailboxId = 5L)
            val badFormatState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    watcher.state.first { it is OtpWatchState.Error }
                }
            }
            assertTrue(badFormatState is OtpWatchState.Error)
            assertTrue(harness.externalDispatcher.recordedRequests().isEmpty())

            // 3. HTTP 401 Unauthorized -> immediate Error, single request
            val validKey = "sk_live_" + "5".repeat(32)
            harness.settingsRepository.setSeekMailApiKey(validKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first { it.seekMailApiKey == validKey }
                }
            }
            val fixture = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("mail/seekmail_otp_watch_pages.json"),
            ).jsonObject
            harness.externalDispatcher.register("GET", "/openapi/v1/mails") {
                MockResponse().setResponseCode(401).setBody(fixture.getValue("unauthorizedError401").toString())
            }
            watcher.startWatch(mailboxId = 5L)
            val err401 = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { watcher.state.first { it is OtpWatchState.Error } }
            }
            assertTrue(err401 is OtpWatchState.Error)
            assertEquals(1, harness.externalDispatcher.recordedRequests().size)

            // 4. HTTP 403 Forbidden -> immediate Error, single request
            harness.externalDispatcher.clearRecordedRequests()
            harness.externalDispatcher.register("GET", "/openapi/v1/mails") {
                MockResponse().setResponseCode(403).setBody("""{"error":{"code":"FORBIDDEN","message":"Forbidden"}}""")
            }
            watcher.startWatch(mailboxId = 5L)
            val err403 = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { watcher.state.first { it is OtpWatchState.Error } }
            }
            assertTrue(err403 is OtpWatchState.Error)
            assertEquals(1, harness.externalDispatcher.recordedRequests().size)
        }
    }

    @Test
    fun `E2E-T2-R14-2 stopWatch and rapid restart generation guard suppress stale detail callbacks`() = runBlocking {
        E2eTestHarness().use { harness ->
            val apiKey = "sk_live_" + "6".repeat(32)
            harness.settingsRepository.setSeekMailApiKey(apiKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first { it.seekMailApiKey == apiKey }
                }
            }
            val fixture = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("mail/seekmail_otp_watch_pages.json"),
            ).jsonObject
            val baselineJson = fixture.getValue("baselineMailsPage").toString()
            val newOtpPageJson = fixture.getValue("newOtpSummaryOnlyPage").toString()
            val staleDetailJson = fixture.getValue("otpDetailNew").toString()

            // Case A: stopWatch() mid-flight cancels polling and keeps Idle
            val gateFirstTick = CompletableDeferred<Unit>()
            val afterFirstTick = CompletableDeferred<Unit>()
            harness.externalDispatcher.register("GET", "/openapi/v1/mails") {
                MockResponse().setResponseCode(200).setBody(baselineJson)
            }
            val watcherA = harness.createSeekMailOtpWatcher(
                delayFn = { ms ->
                    afterFirstTick.complete(Unit)
                    gateFirstTick.await()
                    harness.clock.advanceByMillis(ms)
                    kotlinx.coroutines.yield()
                },
            )
            watcherA.startWatch(mailboxId = 5L)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { afterFirstTick.await() }
            }
            val callsAtStop = harness.externalDispatcher.recordedRequests().size
            watcherA.stopWatch()
            gateFirstTick.complete(Unit)
            assertEquals(OtpWatchState.Idle, watcherA.state.value)
            delay(50L)
            assertEquals(callsAtStop, harness.externalDispatcher.recordedRequests().size)

            // Case B: Rapid restart generation guard while generation 1's getMailDetail(302) is in-flight
            val gen1DetailEntered = CountDownLatch(1)
            val releaseGen1Detail = CountDownLatch(1)
            val gen2PollIdx = AtomicInteger(0)
            val gen1Active = AtomicBoolean(true)

            harness.externalDispatcher.clearRecordedRequests()
            harness.externalDispatcher.register("GET", "/openapi/v1/mails") {
                val body = if (gen1Active.get()) {
                    if (gen2PollIdx.getAndIncrement() == 0) baselineJson else newOtpPageJson
                } else {
                    // Generation 2 sees 302 as baseline, and later sees 303 with code 991122
                    if (gen2PollIdx.getAndIncrement() == 0) {
                        newOtpPageJson
                    } else {
                        """
                        {
                          "data": [
                            {
                              "id": 303,
                              "mailboxId": 6,
                              "fromAddress": "auth@newgen.test",
                              "fromName": "Gen2Auth",
                              "toAddress": "backup@seek.li",
                              "subject": "验证码：991122",
                              "bodyText": "您的验证码：991122",
                              "verificationCode": "991122",
                              "receivedAt": "2026-11-26T06:05:00Z"
                            }
                          ],
                          "pagination": {"limit": 20, "offset": 0, "total": 1}
                        }
                        """.trimIndent()
                    }
                }
                MockResponse().setResponseCode(200).setBody(body)
            }
            harness.externalDispatcher.register("GET", "/openapi/v1/mails/302") {
                gen1DetailEntered.countDown()
                releaseGen1Detail.await(3, TimeUnit.SECONDS)
                MockResponse().setResponseCode(200).setBody(staleDetailJson)
            }

            val sinkB = E2eOtpWatchNotificationSink()
            val watcherB = harness.createSeekMailOtpWatcher(notificationSink = sinkB)
            watcherB.startWatch(mailboxId = 5L, mailboxAddress = "otp@seek.li")

            assertTrue("Expected generation 1 to hit /mails/302", gen1DetailEntered.await(3, TimeUnit.SECONDS))

            // Restart generation 2 while generation 1's /mails/302 is still blocked
            gen1Active.set(false)
            gen2PollIdx.set(0)
            watcherB.startWatch(mailboxId = 6L, mailboxAddress = "backup@seek.li")
            releaseGen1Detail.countDown()

            val gen2Found = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    watcherB.state.first { it is OtpWatchState.Found } as OtpWatchState.Found
                }
            }
            assertEquals("991122", gen2Found.code)
            assertEquals(303L, gen2Found.mailId)
            assertEquals(1, sinkB.foundNotifications.size)
            assertEquals("991122", sinkB.foundNotifications.single().code)
        }
    }

    @Test
    fun `E2E-T2-R14-3 transient 500 on baseline poll recovers on next 200 without falsely treating baseline mail as new OTP`() = runBlocking {
        E2eTestHarness().use { harness ->
            val apiKey = "sk_live_" + "7".repeat(32)
            harness.settingsRepository.setSeekMailApiKey(apiKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first { it.seekMailApiKey == apiKey }
                }
            }
            val fixture = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("mail/seekmail_otp_watch_pages.json"),
            ).jsonObject
            val baselineJson = fixture.getValue("baselineMailsPage").toString()
            val newOtpPageJson = fixture.getValue("newOtpSummaryOnlyPage").toString()
            val otpDetailJson = fixture.getValue("otpDetailNew").toString()

            val pollIdx = AtomicInteger(0)
            harness.externalDispatcher.register("GET", "/openapi/v1/mails") {
                when (pollIdx.getAndIncrement()) {
                    0 -> MockResponse().setResponseCode(500).setBody("""{"error":"transient"}""")
                    1 -> MockResponse().setResponseCode(200).setBody(baselineJson)
                    else -> MockResponse().setResponseCode(200).setBody(newOtpPageJson)
                }
            }
            harness.externalDispatcher.register("GET", "/openapi/v1/mails/302") {
                MockResponse().setResponseCode(200).setBody(otpDetailJson)
            }

            val watcher = harness.createSeekMailOtpWatcher()
            watcher.startWatch(mailboxId = 5L, mailboxAddress = "otp@seek.li")

            val found = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    watcher.state.first { it is OtpWatchState.Found } as OtpWatchState.Found
                }
            }
            // Baseline 111222 on poll 1 was NOT treated as new; 839201 from poll 2 was captured!
            assertEquals("839201", found.code)
            assertEquals(302L, found.mailId)
            assertEquals(3, pollIdx.get())
        }
    }

    @Test
    fun `E2E-T2-R14-4 openMail rapid concurrency does not regress to stale detail and disconnectApiKey clears cache and state`() = runBlocking {
        E2eTestHarness().use { harness ->
            val apiKey = "sk_live_" + "8".repeat(32)
            harness.settingsRepository.setSeekMailApiKey(apiKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first { it.seekMailApiKey == apiKey }
                }
            }
            val fixture = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("mail/seekmail_otp_watch_pages.json"),
            ).jsonObject
            val mailboxesJson = fixture.getValue("mailboxes").toString()
            val nonOtpPageJson = fixture.getValue("nonOtpMailsPage").toString()
            val detail301Json = fixture.getValue("nonOtpDetail301").toString()
            val detail300Json = """
                {
                  "data": {
                    "mail": {
                      "id": 300,
                      "mailboxId": 5,
                      "fromAddress": "security@older.example",
                      "fromName": "OlderAuth",
                      "toAddress": "otp@seek.li",
                      "subject": "Older OTP 111222",
                      "bodyText": "Your older verification code is 111222.",
                      "verificationCode": "111222"
                    },
                    "tags": []
                  }
                }
            """.trimIndent()

            val detail301Gate = CountDownLatch(1)
            val detail301Entered = CountDownLatch(1)

            harness.externalDispatcher.register("GET", "/openapi/v1/mailboxes") {
                MockResponse().setResponseCode(200).setBody(mailboxesJson)
            }
            harness.externalDispatcher.register("GET", "/openapi/v1/tags") {
                MockResponse().setResponseCode(200).setBody("""{"data":[]}""")
            }
            harness.externalDispatcher.register("GET", "/openapi/v1/mails") {
                MockResponse().setResponseCode(200).setBody(nonOtpPageJson)
            }
            harness.externalDispatcher.register("GET", "/openapi/v1/mails/301") {
                detail301Entered.countDown()
                detail301Gate.await(3, TimeUnit.SECONDS)
                MockResponse().setResponseCode(200).setBody(detail301Json)
            }
            harness.externalDispatcher.register("GET", "/openapi/v1/mails/300") {
                MockResponse().setResponseCode(200).setBody(detail300Json)
            }

            val vm = harness.createSeekMailViewModel(initialMailboxId = 5L)
            val stateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.mails.size >= 2 && !it.isLoadingMails }
                }
            }

            val mail301 = vm.uiState.value.mails.first { it.id == 301L }
            val mail300 = vm.uiState.value.mails.first { it.id == 300L }

            // Open 301 (slow), then immediately open 300 (fast)
            vm.openMail(mail301)
            assertTrue(detail301Entered.await(3, TimeUnit.SECONDS))
            vm.openMail(mail300)

            val loaded300 = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.openedMailId == 300L && it.openedDetail?.summary?.id == 300L && !it.isLoadingDetail }
                }
            }
            // Now release slow 301 response -> must NOT overwrite 300
            detail301Gate.countDown()
            delay(60L)
            assertEquals(300L, vm.uiState.value.openedMailId)
            assertEquals(300L, loaded300.openedDetail?.summary?.id)
            assertNotNull(harness.seekMailRepository.getCachedLatestOtp())

            // Start watcher, then stop & disconnect API key -> clears cache & resets state
            vm.startOtpWatch()
            vm.stopOtpWatch()
            vm.disconnectApiKey()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.hasConfiguredKey }
                }
            }
            assertNull(harness.seekMailRepository.getCachedLatestOtp())
            assertEquals(OtpWatchState.Idle, vm.uiState.value.otpWatchState)
            stateJob.cancel()
        }
    }

    @Test
    fun `E2E-T2-R14-5 SeekMailApiClient rejects 302 redirects and never leaks Bearer token to redirect target`() = runBlocking {
        E2eTestHarness().use { harness ->
            val apiKey = "sk_live_" + "9".repeat(32)
            harness.externalDispatcher.register("GET", "/openapi/v1/mails") {
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", harness.externalServer.url("/evil-steal-bearer").toString())
            }
            harness.externalDispatcher.register("GET", "/evil-steal-bearer") {
                MockResponse().setResponseCode(200).setBody("""{"data":[]}""")
            }

            val thrown = try {
                harness.seekMailApiClient.getMails(apiKey = apiKey)
                null
            } catch (ex: SeekMailException) {
                ex
            }
            assertNotNull("Expected SeekMailException on 302 redirect", thrown)
            assertEquals(SeekMailErrorKind.NETWORK, thrown!!.kind)

            val requests = harness.externalDispatcher.recordedRequests()
            assertEquals(1, requests.size)
            assertTrue(requests.none { it.path?.contains("evil-steal-bearer") == true })
            harness.assertNoCookiesLeakedToExternalServer()

            // Also verify non-loopback plain HTTP origin is rejected at construction
            val rejected = runCatching {
                SeekMailApiClient(baseUrl = "http://seek.li/openapi/v1".toHttpUrl())
            }.exceptionOrNull()
            assertTrue(rejected is IllegalArgumentException)
        }
    }

    @Test
    fun `E2E-T2-R14-6 DefaultSeekMailOtpWatcher serializes stopWatch dismissResult missing-key Error and concurrent startStop notification order`() = runBlocking {
        E2eTestHarness().use { harness ->
            val fixture = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("mail/seekmail_otp_watch_pages.json"),
            ).jsonObject
            val baselineJson = fixture.getValue("baselineMailsPage").toString()
            harness.externalDispatcher.register("GET", "/openapi/v1/mails") {
                MockResponse().setResponseCode(200).setBody(baselineJson)
            }

            val watchingActive = AtomicBoolean(false)
            val cancelCalls = AtomicInteger(0)
            val trackingSink = object : OtpWatchNotificationSink {
                override fun ensureChannel() = Unit
                override fun postWatchingNotification(state: OtpWatchState.Watching) {
                    watchingActive.set(true)
                }
                override fun postOtpFoundNotification(found: OtpWatchState.Found) {
                    watchingActive.set(false)
                }
                override fun postTimedOutNotification(timedOut: OtpWatchState.TimedOut) {
                    watchingActive.set(false)
                }
                override fun cancelWatchingNotification() {
                    cancelCalls.incrementAndGet()
                    watchingActive.set(false)
                }
            }

            val gate = CompletableDeferred<Unit>()
            val watcher = harness.createSeekMailOtpWatcher(
                notificationSink = trackingSink,
                delayFn = { ms ->
                    harness.clock.advanceByMillis(ms)
                    kotlinx.coroutines.yield()
                    if (!gate.isCompleted) {
                        gate.await()
                    }
                },
            )

            // 1. Missing API key emits Error and cancels watching notification under lock
            watchingActive.set(true)
            watcher.startWatch(mailboxId = 5L)
            assertTrue(watcher.state.value is OtpWatchState.Error)
            assertFalse(watchingActive.get())
            assertTrue(cancelCalls.get() >= 1)

            // dismissResult() transitions Error -> Idle
            watcher.dismissResult()
            assertEquals(OtpWatchState.Idle, watcher.state.value)

            // 2. Configure API key and verify dismissResult() while Watching stays Watching
            val apiKey = "sk_live_" + "a".repeat(32)
            harness.settingsRepository.setSeekMailApiKey(apiKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.settingsRepository.settings.first { it.seekMailApiKey == apiKey }
                }
            }
            watcher.startWatch(mailboxId = 5L, mailboxAddress = "otp@seek.li")
            assertTrue(watcher.state.value is OtpWatchState.Watching)
            assertTrue(watchingActive.get())
            watcher.dismissResult()
            assertTrue("dismissResult() must not clobber active Watching", watcher.state.value is OtpWatchState.Watching)
            watcher.stopWatch()
            assertEquals(OtpWatchState.Idle, watcher.state.value)
            assertFalse(watchingActive.get())

            // 3. 60 concurrent barrier rounds racing stopWatch() vs startWatch() on Dispatchers.IO
            for (round in 0 until 60) {
                watcher.startWatch(mailboxId = 5L, mailboxAddress = "otp@seek.li")
                assertTrue(watchingActive.get())
                val barrier = CyclicBarrier(2)
                withContext(Dispatchers.IO) {
                    listOf(
                        async {
                            barrier.await(3, TimeUnit.SECONDS)
                            watcher.stopWatch()
                        },
                        async {
                            barrier.await(3, TimeUnit.SECONDS)
                            watcher.startWatch(mailboxId = 5L, mailboxAddress = "otp@seek.li")
                        },
                    ).awaitAll()
                }
                when (val settled = watcher.state.value) {
                    is OtpWatchState.Watching -> assertTrue(
                        "Round $round settled Watching but watching notification was cancelled out of order",
                        watchingActive.get(),
                    )
                    OtpWatchState.Idle -> assertFalse(
                        "Round $round settled Idle but watching notification remained active",
                        watchingActive.get(),
                    )
                    else -> error("Unexpected settled state at round $round: $settled")
                }
                watcher.stopWatch()
                assertEquals(OtpWatchState.Idle, watcher.state.value)
                assertFalse(watchingActive.get())
            }

            // 4. Clean TimedOut -> dismissResult() -> Idle transition
            gate.complete(Unit)
            watcher.startWatch(mailboxId = 5L, mailboxAddress = "otp@seek.li")
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    watcher.state.first { it is OtpWatchState.TimedOut }
                }
            }
            watcher.dismissResult()
            assertEquals(OtpWatchState.Idle, watcher.state.value)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }
}
