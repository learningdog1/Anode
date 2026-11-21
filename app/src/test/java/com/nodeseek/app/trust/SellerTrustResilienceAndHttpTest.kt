package com.nodeseek.app.trust

import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.trust.HttpSellerTrustRemoteDataSource
import com.nodeseek.app.data.trust.SellerTrustRateLimitedException
import com.nodeseek.app.data.trust.SellerTrustRateLimiter
import com.nodeseek.app.data.trust.SellerTrustRemoteDataSource
import com.nodeseek.app.data.trust.SellerTrustRepositoryImpl
import com.nodeseek.app.data.trust.SellerTrustTimeoutException
import com.nodeseek.app.data.trust.parseRetryAfterMillis
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.TopicAccessException
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.model.UserProfile
import com.nodeseek.app.domain.trust.TrustFetchStatus
import com.nodeseek.app.domain.trust.TrustRiskLevel
import com.nodeseek.app.ui.trust.formatTrustUpdatedAt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SellerTrustResilienceAndHttpTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val shanghaiZone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val baseEpochMillis: Long = Instant.parse("2026-11-20T04:00:00Z").toEpochMilli()

    @Test
    fun singleFlight_coalescesConcurrentCallsForSameSeller_andSurvivesSingleCallerCancellation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val profileCalls = AtomicInteger(0)
        val remoteGate = CompletableDeferred<Unit>()
        val startedSignal = CompletableDeferred<Unit>()

        val remote = FakeRemoteDataSource(
            onFetchProfile = { uid ->
                profileCalls.incrementAndGet()
                startedSignal.complete(Unit)
                remoteGate.await()
                sampleProfile(uid = uid, username = "CoalescedSeller", topicCount = 0)
            },
        )

        val clock = { baseEpochMillis + testScheduler.currentTime }
        val repo = SellerTrustRepositoryImpl(
            remoteDataSource = remote,
            notesFile = File(tempFolder.root, "coalesce_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = clock,
            rateLimiter = SellerTrustRateLimiter(
                minSiteIntervalMillis = 0L,
                clockMillis = clock,
                jitterFraction = { 0.0 },
            ),
            zoneId = shanghaiZone,
        )

        val cancelledCaller = async {
            repo.inspectSeller(uid = 101L, usernameHint = "CoalescedSeller")
        }
        val survivingCallers = (1..6).map {
            async {
                repo.inspectSeller(uid = 101L, usernameHint = "@CoalescedSeller")
            }
        }

        advanceUntilIdle()
        assertTrue(startedSignal.isCompleted)

        // Cancel the first caller while the shared remote call is still in-flight
        cancelledCaller.cancel()
        advanceUntilIdle()
        try {
            cancelledCaller.await()
            fail("Expected CancellationException for cancelled caller")
        } catch (_: CancellationException) {
            // Expected
        }

        // Release shared remote gate -> all 6 surviving callers receive the exact single result
        remoteGate.complete(Unit)
        val results = survivingCallers.awaitAll()
        assertEquals(1, profileCalls.get())
        assertEquals(6, results.size)
        results.forEach { report ->
            assertEquals(101L, report.uid)
            assertEquals("CoalescedSeller", report.username)
        }
    }

    @Test
    fun rateLimiter_enforcesGlobalConcurrencyCap_andPerSiteMinimumInterval() = runTest {
        val callTimestamps = Collections.synchronizedList(mutableListOf<Long>())
        val limiter = SellerTrustRateLimiter(
            maxGlobalConcurrency = 2,
            minSiteIntervalMillis = 400L,
            clockMillis = { baseEpochMillis + testScheduler.currentTime },
            jitterFraction = { 0.0 },
        )

        val jobs = (1..5).map { idx ->
            async {
                limiter.execute(host = "www.nodeseek.com") {
                    callTimestamps += testScheduler.currentTime
                    delay(900L) // Hold concurrency slot long enough to contend on maxGlobalConcurrency=2
                    idx
                }
            }
        }

        val values = jobs.awaitAll()
        assertEquals(listOf(1, 2, 3, 4, 5), values)
        assertTrue(
            "Expected peak concurrency <= 2, actual=${limiter.peakConcurrencyForTest}",
            limiter.peakConcurrencyForTest <= 2,
        )
        assertEquals(5, callTimestamps.size)
        val sorted = callTimestamps.sorted()
        for (i in 1 until sorted.size) {
            val delta = sorted[i] - sorted[i - 1]
            assertTrue(
                "Expected >= 400ms interval between requests to same host, got ${delta}ms ($sorted)",
                delta >= 400L,
            )
        }
    }

    @Test
    fun retryAfterParser_supportsDeltaSecondsAndRfc1123HttpDate() {
        assertEquals(45_000L, parseRetryAfterMillis("45", baseEpochMillis))
        assertEquals(1_000L, parseRetryAfterMillis("0", baseEpochMillis))
        assertEquals(
            90_000L,
            parseRetryAfterMillis("Fri, 20 Nov 2026 04:01:30 GMT", baseEpochMillis),
        )
        assertNull(parseRetryAfterMillis("   ", baseEpochMillis))
        assertNull(parseRetryAfterMillis("not-a-valid-header", baseEpochMillis))
    }

    @Test
    fun rateLimiter_handles429RetryAfter_escalatesExponentialBackoffWithJitter_andBlocksDuringCooldown() = runTest {
        var manualNow = baseEpochMillis
        val remoteHits = AtomicInteger(0)
        val limiter = SellerTrustRateLimiter(
            minSiteIntervalMillis = 0L,
            baseCooldownMillis = 15_000L,
            maxCooldownMillis = 300_000L,
            maxJitterRatio = 0.20,
            clockMillis = { manualNow },
            jitterFraction = { 0.5 }, // Deterministic +10% jitter (`0.20 * 0.5`)
        )

        // Strike 1 with Retry-After: 40s -> 40_000ms + 10% jitter = 44_000ms cooldown
        try {
            limiter.execute("www.nodeseek.com") {
                remoteHits.incrementAndGet()
                throw NodeSeekApiException("Too Many Requests", statusCode = 429, retryAfterSeconds = 40L)
            }
            fail("Expected SellerTrustRateLimitedException")
        } catch (rateLimited: SellerTrustRateLimitedException) {
            assertEquals(429, rateLimited.statusCode)
            assertEquals(baseEpochMillis + 44_000L, rateLimited.cooldownUntilEpochMillis)
        }

        // Calls during cooldown do NOT execute remote block at all
        manualNow += 20_000L
        try {
            limiter.execute("www.nodeseek.com") {
                remoteHits.incrementAndGet()
                "should_not_run"
            }
            fail("Expected active cooldown block")
        } catch (_: SellerTrustRateLimitedException) {
            assertEquals(1, remoteHits.get())
        }

        // Advance past 44s cooldown -> lightweight getInfo succeeds right after cooldown,
        // and /search hitting 429 again MUST still escalate to Strike 2 (30_000ms + 10% = 33_000ms)!
        manualNow = baseEpochMillis + 45_000L
        assertEquals("profile_ok", limiter.execute("www.nodeseek.com") { "profile_ok" })

        try {
            limiter.execute("www.nodeseek.com") {
                remoteHits.incrementAndGet()
                throw NodeSeekApiException("Too Many Requests", statusCode = 429)
            }
            fail("Expected second SellerTrustRateLimitedException")
        } catch (secondLimited: SellerTrustRateLimitedException) {
            assertEquals(2, remoteHits.get())
            assertEquals(manualNow + 33_000L, secondLimited.cooldownUntilEpochMillis)
        }

        // After a long healthy window (>= 120s past cooldown), strikes decay back to 0 -> next 429 starts at Strike 1 (15s + 10% = 16_500ms)
        manualNow += 33_000L + 130_000L
        assertEquals("healthy_ok", limiter.execute("www.nodeseek.com") { "healthy_ok" })
        try {
            limiter.execute("www.nodeseek.com") {
                throw NodeSeekApiException("Too Many Requests", statusCode = 429)
            }
            fail("Expected fresh Strike 1 cooldown")
        } catch (freshLimited: SellerTrustRateLimitedException) {
            assertEquals(manualNow + 16_500L, freshLimited.cooldownUntilEpochMillis)
        }
    }

    @Test
    fun rateLimiter_retriesTransient503AndTimeoutWithExponentialBackoff_thenEntersCooldownIfExhausted() = runTest {
        val attempts503 = AtomicInteger(0)
        val limiter = SellerTrustRateLimiter(
            minSiteIntervalMillis = 0L,
            maxTransientRetries = 2,
            retryBaseDelayMillis = 500L,
            overloadBaseCooldownMillis = 10_000L,
            maxJitterRatio = 0.20,
            clockMillis = { baseEpochMillis + testScheduler.currentTime },
            jitterFraction = { 0.5 }, // +10% jitter
        )

        // 1. Transient 503 fails twice, succeeds on 3rd attempt after 550ms + 1100ms exponential backoff
        val startVirtual = testScheduler.currentTime
        val value = limiter.execute("www.nodeseek.com") {
            val n = attempts503.incrementAndGet()
            if (n <= 2) {
                throw NodeSeekApiException("Service Unavailable", statusCode = 503)
            }
            "recovered"
        }
        val elapsedRecovery = testScheduler.currentTime - startVirtual
        assertEquals("recovered", value)
        assertEquals(3, attempts503.get())
        assertEquals(550L + 1_100L, elapsedRecovery)

        // 2. Persistent SocketTimeoutException exhausts 1 initial + 2 retries, then enters cooldown
        val timeoutAttempts = AtomicInteger(0)
        try {
            limiter.execute("www.nodeseek.com") {
                timeoutAttempts.incrementAndGet()
                throw SocketTimeoutException("Read timed out")
            }
            fail("Expected SellerTrustTimeoutException")
        } catch (timeoutErr: SellerTrustTimeoutException) {
            assertEquals(3, timeoutAttempts.get())
            assertTrue(timeoutErr.cooldownUntilEpochMillis > baseEpochMillis + testScheduler.currentTime)
        }
    }

    @Test
    fun repository_servesStaleCachedReportDuringRateLimitCooldown_insteadOfHammeringServer() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var manualOffsetMillis = 0L
        val clock = { baseEpochMillis + testScheduler.currentTime + manualOffsetMillis }
        var shouldRateLimit = false
        val profileHits = AtomicInteger(0)

        val remote = FakeRemoteDataSource(
            onFetchProfile = { uid ->
                profileHits.incrementAndGet()
                if (shouldRateLimit) {
                    throw NodeSeekApiException("429 Too Many Requests", statusCode = 429, retryAfterSeconds = 60L)
                }
                sampleProfile(uid = uid, username = "TrustedVendor", topicCount = 0)
            },
        )

        val repo = SellerTrustRepositoryImpl(
            remoteDataSource = remote,
            notesFile = File(tempFolder.root, "stale_fallback_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = clock,
            rateLimiter = SellerTrustRateLimiter(
                minSiteIntervalMillis = 0L,
                clockMillis = clock,
                jitterFraction = { 0.0 },
            ),
            zoneId = shanghaiZone,
        )

        // Populate positive cache at t=0
        val initial = repo.inspectSeller(uid = 301L, usernameHint = "TrustedVendor")
        assertEquals(TrustFetchStatus.FRESH, initial.fetchStatus)
        assertEquals(1, profileHits.get())

        // Advance 20 hours (past 18h normal TTL, still within 7-day stale retention) and enable 429
        manualOffsetMillis += 20L * 60L * 60L * 1000L
        shouldRateLimit = true

        val fallbackOn429 = repo.inspectSeller(uid = 301L, usernameHint = "TrustedVendor")
        assertEquals(2, profileHits.get()) // Attempted once, got 429 -> fell back to stale cache!
        assertTrue(fallbackOn429.isFromCache)
        assertEquals(TrustFetchStatus.STALE_CACHE_FALLBACK, fallbackOn429.fetchStatus)
        assertEquals(initial.fetchedAtEpochMillis, fallbackOn429.fetchedAtEpochMillis)
        assertTrue(fallbackOn429.cooldownUntilEpochMillis > clock())

        // Subsequent calls during 60s cooldown (even forceRefresh = true!) make ZERO network calls
        manualOffsetMillis += 10_000L
        val duringCooldown = repo.inspectSeller(uid = 301L, usernameHint = "TrustedVendor", forceRefresh = true)
        assertEquals(2, profileHits.get())
        assertTrue(duringCooldown.isFromCache)
        assertEquals(TrustFetchStatus.STALE_CACHE_FALLBACK, duringCooldown.fetchStatus)
    }

    @Test
    fun cacheStore_evictsLeastRecentlyUsedFromMemory_andRecoversFromDiskPersistence() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = { baseEpochMillis + testScheduler.currentTime }
        val profileCalls = AtomicInteger(0)

        val remote = FakeRemoteDataSource(
            onFetchProfile = { uid ->
                profileCalls.incrementAndGet()
                sampleProfile(uid = uid, username = "Seller$uid", topicCount = 0)
            },
        )

        // Each seller stores 2 keys (uid:N + name:sellerN), so maxMemoryCacheEntries = 4 holds 2 sellers in memory
        val lruRepo = SellerTrustRepositoryImpl(
            remoteDataSource = remote,
            notesFile = File(tempFolder.root, "lru3_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = clock,
            maxMemoryCacheEntries = 4,
            maxDiskCacheEntries = 20,
            rateLimiter = SellerTrustRateLimiter(minSiteIntervalMillis = 0L, clockMillis = clock),
            zoneId = shanghaiZone,
        )

        lruRepo.inspectSeller(uid = 1L, usernameHint = "Seller1")
        lruRepo.inspectSeller(uid = 2L, usernameHint = "Seller2")
        assertEquals(2, profileCalls.get())

        // Touch Seller1 so Seller1 becomes MRU and Seller2 becomes LRU
        val hit1 = lruRepo.inspectSeller(uid = 1L, usernameHint = "Seller1")
        assertTrue(hit1.isFromCache)

        // Insert Seller3 -> Seller2 is evicted from memory LRU, Seller1 stays in memory!
        lruRepo.inspectSeller(uid = 3L, usernameHint = "Seller3")
        assertEquals(3, profileCalls.get())
        assertTrue(lruRepo.cacheStore.isInMemoryLruForTest("uid:1"))
        assertFalse(lruRepo.cacheStore.isInMemoryLruForTest("uid:2"))
        assertTrue(lruRepo.cacheStore.isInMemoryLruForTest("uid:3"))

        // Reading Seller2 promotes it back from persistent disk cache with ZERO network calls!
        val promotedFromDisk = lruRepo.inspectSeller(uid = 2L, usernameHint = "Seller2")
        assertTrue(promotedFromDisk.isFromCache)
        assertEquals(3, profileCalls.get())
    }

    @Test
    fun partialSuccessCache_expiresEarlierThanFullPositiveCache() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var manualOffsetMillis = 0L
        val clock = { baseEpochMillis + testScheduler.currentTime + manualOffsetMillis }
        val profileCalls = AtomicInteger(0)

        val remote = FakeRemoteDataSource(
            onFetchProfile = { uid ->
                profileCalls.incrementAndGet()
                sampleProfile(uid = uid, username = "GuestCheckedSeller", topicCount = 0)
            },
            onFetchExposeBoard = {
                throw TopicAccessException.SignInRequired
            },
        )

        val repo = SellerTrustRepositoryImpl(
            remoteDataSource = remote,
            notesFile = File(tempFolder.root, "partial_ttl_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = clock,
            rateLimiter = SellerTrustRateLimiter(minSiteIntervalMillis = 0L, clockMillis = clock),
            zoneId = shanghaiZone,
        )

        val first = repo.inspectSeller(uid = 404L, usernameHint = "GuestCheckedSeller")
        assertTrue(first.profileSucceeded)
        assertFalse(first.searchSucceeded)
        assertEquals(1, profileCalls.get())

        // 5 minutes later -> still served from 15-minute partial cache
        manualOffsetMillis += 5L * 60L * 1000L
        val cachedPartial = repo.inspectSeller(uid = 404L, usernameHint = "GuestCheckedSeller")
        assertTrue(cachedPartial.isFromCache)
        assertEquals(1, profileCalls.get())

        // 16 minutes later -> 15-minute partial TTL expired -> refetches (unlike 18h positive TTL)
        manualOffsetMillis += 11L * 60L * 1000L
        val refetched = repo.inspectSeller(uid = 404L, usernameHint = "GuestCheckedSeller")
        assertFalse(refetched.isFromCache)
        assertEquals(2, profileCalls.get())
    }

    @Test
    fun quickBadgeCacheStream_triggersZeroNetworkCalls_andEnqueueInspectDeduplicatesBackgroundQueue() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = { baseEpochMillis + testScheduler.currentTime }
        val profileCalls = AtomicInteger(0)

        val remote = FakeRemoteDataSource(
            onFetchProfile = { uid ->
                profileCalls.incrementAndGet()
                sampleProfile(uid = uid, username = "BadgeUser$uid", topicCount = 0)
            },
        )

        val repo = SellerTrustRepositoryImpl(
            remoteDataSource = remote,
            notesFile = File(tempFolder.root, "badge_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = clock,
            rateLimiter = SellerTrustRateLimiter(minSiteIntervalMillis = 100L, clockMillis = clock),
            zoneId = shanghaiZone,
        )

        // Observing cached report for 20 uncached floor UIDs makes ZERO network calls!
        for (floorUid in 1L..20L) {
            assertNull(repo.observeCachedReport(floorUid).first())
        }
        assertEquals(0, profileCalls.get())

        // Enqueueing duplicate background inspections for the same UID deduplicates into 1 call
        repeat(5) {
            repo.enqueueInspect(uid = 88L, usernameHint = "BadgeUser88")
        }
        advanceUntilIdle()
        assertEquals(1, profileCalls.get())
        val cachedNow = repo.observeCachedReport(88L).first()
        assertNotNull(cachedNow)
        assertTrue(cachedNow!!.isFromCache)
        assertEquals(TrustRiskLevel.NORMAL_CLEAR, cachedNow.riskAssessment.level)
    }

    @Test
    fun httpSellerTrustRemoteDataSource_usesLightweightGetInfo_skipsZeroPostDiscussions_setsHeaders_andParsesRetryAfter() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val customUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8) Chrome/131.0.0.0 Mobile Safari/537.36"
            val uaProvider = UserAgentProvider(initialOverride = customUa)
            val dataSource = HttpSellerTrustRemoteDataSource(
                baseHttpClient = OkHttpClient(),
                userAgentProvider = uaProvider,
                baseOrigin = server.url("/"),
                clockMillis = { baseEpochMillis },
            )

            val configB64 = Base64.getEncoder().encodeToString(
                """{"allCategory":[{"key":"expose","cn_text":"曝光"}]}""".toByteArray(Charsets.UTF_8),
            )
            val emptySearchHtml =
                """<html><body><div id="nsk-body"></div><script id="temp-script" type="application/json">$configB64</script></body></html>"""

            // Enqueue:
            // 1) /api/account/getInfo/42 (nPost = 0 -> skips list-discussions!)
            // 2) single /categories/expose?sortBy=replyTime page (zero /search traffic by default!)
            // 3) /api/account/getInfo/43 (reuses shared expose board snapshot!)
            // 4) single /search?q=ZeroPostSeller... only when user explicitly invokes deep search!
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"success":true,"detail":{"member_id":42,"member_name":"ZeroPostSeller","created_at":"2024-01-01T00:00:00Z","coin":1600,"stardust":50,"nPost":0,"nComment":12}}""",
                    ),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(emptySearchHtml))
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"success":true,"detail":{"member_id":43,"member_name":"SecondSeller","created_at":"2024-02-01T00:00:00Z","coin":1700,"stardust":60,"nPost":0,"nComment":8}}""",
                    ),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(emptySearchHtml))

            val dispatcher = StandardTestDispatcher(testScheduler)
            val clock = { baseEpochMillis + testScheduler.currentTime }
            val repo = SellerTrustRepositoryImpl(
                remoteDataSource = dataSource,
                notesFile = File(tempFolder.root, "http_notes.json"),
                ioDispatcher = dispatcher,
                clockMillis = clock,
                rateLimiter = SellerTrustRateLimiter(
                    minSiteIntervalMillis = 0L,
                    clockMillis = clock,
                    jitterFraction = { 0.0 },
                ),
                searchMinIntervalMillis = 0L,
                zoneId = shanghaiZone,
            )

            val report = repo.inspectSeller(uid = 42L, usernameHint = "ZeroPostSeller")
            assertTrue(report.profileSucceeded)
            assertTrue(report.topicsSucceeded)
            assertTrue(report.searchSucceeded)
            assertFalse(report.isDeepSearchVerified)
            assertEquals(0, report.totalTopicsCount)
            assertEquals(0, report.historicalTradeTopicCount)

            // Only 2 HTTP requests total: NO /api/block-list/list, NO ?readme=1, NO list-discussions, NO /search!
            assertEquals(2, server.requestCount)
            val reqProfile = server.takeRequest()
            assertEquals("/api/account/getInfo/42", reqProfile.path)
            assertEquals(customUa, reqProfile.getHeader("User-Agent"))
            assertEquals("XMLHttpRequest", reqProfile.getHeader("X-Requested-With"))
            assertNotNull(reqProfile.getHeader("Accept-Language"))
            assertTrue(reqProfile.getHeader("Referer").orEmpty().contains("/space/42"))

            val reqExposeBoard = server.takeRequest()
            assertEquals("/categories/expose?sortBy=replyTime", reqExposeBoard.path)

            // Second seller reuses the shared /categories/expose snapshot -> only 1 getInfo call!
            val secondReport = repo.inspectSeller(uid = 43L, usernameHint = "SecondSeller")
            assertTrue(secondReport.profileSucceeded)
            assertTrue(secondReport.searchSucceeded)
            assertEquals(3, server.requestCount)
            val reqProfile2 = server.takeRequest()
            assertEquals("/api/account/getInfo/43", reqProfile2.path)

            // Explicit on-demand deep search issues exactly ONE /search query
            val deepReport = repo.searchSellerExposureDeep(uid = 42L, usernameHint = "ZeroPostSeller")
            assertTrue(deepReport.isDeepSearchVerified)
            assertEquals(4, server.requestCount)
            val reqDeepSearch = server.takeRequest()
            assertTrue(reqDeepSearch.path!!.startsWith("/search?q=ZeroPostSeller"))

            // Verify raw HTTP 429 with Retry-After header puts repository into cooldown
            server.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setHeader("Retry-After", "25")
                    .setBody("Too Many Requests"),
            )
            val rateLimitedReport = repo.inspectSeller(uid = 99L, usernameHint = "AnotherSeller")
            assertEquals(TrustFetchStatus.RATE_LIMITED, rateLimitedReport.fetchStatus)
            assertEquals(clock() + 25_000L, rateLimitedReport.cooldownUntilEpochMillis)
            assertEquals(5, server.requestCount) // Did NOT fire list-discussions or /categories/expose after 429!
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun formatTrustUpdatedAt_formatsRelativeAndWallClockTimestampsAccurately() {
        val now = Instant.parse("2026-11-20T04:30:30Z").toEpochMilli() // 12:30:30 Asia/Shanghai
        assertEquals("刚刚 (12:30)", formatTrustUpdatedAt(now - 15_000L, now, shanghaiZone))
        assertEquals("10 分钟前 (12:20)", formatTrustUpdatedAt(now - 10L * 60_000L, now, shanghaiZone))
        assertEquals("今日 09:30（3小时前）", formatTrustUpdatedAt(now - 3L * 3_600_000L, now, shanghaiZone))
        assertEquals("昨日 12:30", formatTrustUpdatedAt(now - 24L * 3_600_000L, now, shanghaiZone))
        assertEquals("11-15 12:30", formatTrustUpdatedAt(now - 5L * 24L * 3_600_000L, now, shanghaiZone))
    }

    private fun sampleProfile(
        uid: Long,
        username: String,
        topicCount: Int,
    ): UserProfile = UserProfile(
        uid = uid,
        username = username,
        avatarUrl = "https://www.nodeseek.com/avatar/$uid.png",
        joinedAtIso = "2024-03-01T00:00:00Z",
        drumsticks = 1600,
        stardust = 80,
        topicCount = topicCount,
        replyCount = 45,
    )

    private class FakeRemoteDataSource(
        override val siteHost: String = "www.nodeseek.com",
        private val onResolveUid: suspend (String) -> Long? = { null },
        private val onFetchProfile: suspend (Long) -> UserProfile,
        private val onFetchTopics: suspend (Long) -> List<TopicSummary> = { emptyList() },
        private val onFetchExposeBoard: suspend () -> FeedPageResult = { FeedPageResult(topics = emptyList()) },
        private val onSearch: suspend (String, String) -> FeedPageResult = { _, _ -> FeedPageResult(topics = emptyList()) },
    ) : SellerTrustRemoteDataSource {
        override suspend fun resolveUidByUsername(username: String): Long? = onResolveUid(username)
        override suspend fun fetchSellerProfile(uid: Long): UserProfile = onFetchProfile(uid)
        override suspend fun fetchSellerTopicsPage1(uid: Long): List<TopicSummary> = onFetchTopics(uid)
        override suspend fun fetchExposeBoardPage1(): FeedPageResult = onFetchExposeBoard()
        override suspend fun searchExposureTopicsPage1(
            query: String,
            categorySlug: String,
        ): FeedPageResult = onSearch(query, categorySlug)
    }
}
