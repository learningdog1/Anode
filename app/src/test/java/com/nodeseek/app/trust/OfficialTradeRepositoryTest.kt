package com.nodeseek.app.trust

import com.nodeseek.app.data.trust.OfficialTradeApiClient
import com.nodeseek.app.data.trust.OfficialTradeCacheStore
import com.nodeseek.app.data.trust.OfficialTradeKeyStore
import com.nodeseek.app.data.trust.OfficialTradeRateLimiter
import com.nodeseek.app.data.trust.OfficialTradeRepositoryImpl
import com.nodeseek.app.domain.ssh.SecretCipher
import com.nodeseek.app.domain.trust.OfficialTradeLookup
import com.nodeseek.app.domain.trust.OfficialTradeRoleStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class OfficialTradeRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val fakeClockMillis = AtomicLong(1_763_700_000_000L)

    private class FakeAesCipher : SecretCipher {
        override fun encrypt(plaintext: String): String {
            if (plaintext.isEmpty()) return ""
            val reversed = plaintext.reversed().toByteArray(StandardCharsets.UTF_8)
            return "test_enc:" + Base64.getEncoder().encodeToString(reversed)
        }

        override fun decrypt(ciphertext: String): String {
            if (ciphertext.isEmpty()) return ""
            require(ciphertext.startsWith("test_enc:")) { "Invalid ciphertext prefix" }
            val decoded = Base64.getDecoder().decode(ciphertext.removePrefix("test_enc:"))
            return String(decoded, StandardCharsets.UTF_8).reversed()
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun buildRepository(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        keyFile: File = File(tempFolder.root, "official_key.json"),
        cacheFile: File = File(tempFolder.root, "official_cache.json"),
        maxTokensPerMinute: Int = 50,
    ): OfficialTradeRepositoryImpl {
        val clock = { fakeClockMillis.get() }
        val apiClient = OfficialTradeApiClient(
            baseHttpClient = OkHttpClient(),
            baseUrl = server.url("/"),
            allowLoopbackHttpForTesting = true,
            clockMillis = clock,
        )
        val keyStore = OfficialTradeKeyStore(
            keyFile = keyFile,
            cipher = FakeAesCipher(),
            ioDispatcher = dispatcher,
            clockMillis = clock,
        )
        val cacheStore = OfficialTradeCacheStore(
            cacheFile = cacheFile,
            ioDispatcher = dispatcher,
            clockMillis = clock,
        )
        val rateLimiter = OfficialTradeRateLimiter(
            maxTokensPerMinute = maxTokensPerMinute,
            clockMillis = clock,
        )
        return OfficialTradeRepositoryImpl(
            apiClient = apiClient,
            keyStore = keyStore,
            cacheStore = cacheStore,
            rateLimiter = rateLimiter,
            ioDispatcher = dispatcher,
            clockMillis = clock,
        )
    }

    @Test
    fun unconfigured_returnsNotConfigured_andNeverHitsNetwork() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = buildRepository(dispatcher)

        assertFalse(repo.isConfigured.value)
        assertNull(repo.maskedApiKey())

        val result = repo.lookup("Alice")
        assertEquals(OfficialTradeLookup.NotConfigured, result)
        assertNull(repo.getCachedOnly("Alice"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun keyStore_trimsValidatesEncryptsMasksPersistsAndClears_withoutPlaintextOnDisk() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val keyFile = File(tempFolder.root, "persisted_key.json")
        val repo = buildRepository(dispatcher = dispatcher, keyFile = keyFile)

        try {
            repo.saveApiKey("sk_wrong_prefix_123456")
            fail("Expected IllegalArgumentException for non-ntk_ prefix")
        } catch (e: IllegalArgumentException) {
            assertFalse(e.message.orEmpty().contains("sk_wrong_prefix_123456"))
        }

        val fakeSecret = "ntk_fakekey0000111122223333abcd9876"
        repo.saveApiKey("  $fakeSecret \n ")
        assertTrue(repo.isConfigured.value)
        assertEquals("ntk_fake…9876", repo.maskedApiKey())

        // Verify on-disk payload never stores plaintext key
        val rawDiskText = keyFile.readText(Charsets.UTF_8)
        assertFalse("Plaintext API key must never appear on disk", rawDiskText.contains(fakeSecret))

        // Verify reload from disk on cold start
        val reloadedRepo = buildRepository(dispatcher = dispatcher, keyFile = keyFile)
        assertTrue(reloadedRepo.isConfigured.value)
        assertEquals("ntk_fake…9876", reloadedRepo.maskedApiKey())

        // Clear key
        reloadedRepo.clearApiKey()
        assertFalse(reloadedRepo.isConfigured.value)
        assertNull(reloadedRepo.maskedApiKey())
        assertFalse(keyFile.exists())
        assertEquals(OfficialTradeLookup.NotConfigured, reloadedRepo.lookup("Alice"))
    }

    @Test
    fun lookup_singleUser_parsesStatsRolesAndUrlEncodesSpecialUsernames() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = buildRepository(dispatcher)
        val fakeKey = "ntk_test_valid_key_1234567890abcd"
        repo.saveApiKey(fakeKey)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "users": [
                        {
                          "id": 9,
                          "username": "彭于晏 A+B&C",
                          "avatar_url": "https://www.nodeseek.com/avatar/2557.png",
                          "bio": "官方中介",
                          "role": "agent",
                          "created_at": "2026-11-13T05:20:10.616Z"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "user": {
                        "id": 9,
                        "username": "彭于晏 A+B&C",
                        "avatar_url": "https://www.nodeseek.com/avatar/2557.png",
                        "bio": "官方中介",
                        "role": "agent",
                        "created_at": "2026-11-13T05:20:10.616Z"
                      },
                      "stats": {
                        "trades": 11,
                        "amount": "5829.72",
                        "last_completed_at": "2026-11-21T16:13:57.761Z",
                        "by_role": [
                          {"role": "buyer", "trades": 2, "amount": "1000.00"},
                          {"role": "seller", "trades": 1, "amount": "688.00"},
                          {"role": "agent", "trades": 8, "amount": "4141.72"}
                        ]
                      }
                    }
                    """.trimIndent(),
                ),
        )

        val result = repo.lookup("  @彭于晏 A+B&C  ")
        assertTrue(result is OfficialTradeLookup.Found)
        val found = result as OfficialTradeLookup.Found
        assertFalse(found.fromCache)
        assertEquals(1, found.matches.size)

        val stats = found.matches.single()
        assertEquals(9L, stats.userId)
        assertEquals("彭于晏 A+B&C", stats.username)
        assertEquals(11, stats.trades)
        assertEquals("5829.72", stats.amount)
        assertEquals("2026-11-21T16:13:57.761Z", stats.lastCompletedAt)
        assertTrue(stats.isOfficialAgent)
        assertEquals(3, stats.byRole.size)
        assertEquals(OfficialTradeRoleStat("buyer", 2, "1000.00"), stats.byRole[0])
        assertEquals("买家", stats.byRole[0].roleDisplayLabel)
        assertEquals("卖家", stats.byRole[1].roleDisplayLabel)
        assertEquals("中介", stats.byRole[2].roleDisplayLabel)

        val searchReq = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("Bearer $fakeKey", searchReq.getHeader("Authorization"))
        assertEquals(
            "/open/v1/users?username=%E5%BD%AD%E4%BA%8E%E6%99%8F%20A%2BB%26C",
            searchReq.path,
        )
        val statsReq = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/open/v1/users/9/stats", statsReq.path)
    }

    @Test
    fun lookup_duplicateUsernames_capsStatsRequestsAtFirstFiveMatches() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = buildRepository(dispatcher)
        repo.saveApiKey("ntk_fake_dup_test_key_12345678")

        val usersJson = (1..7).joinToString(",") { id ->
            """{"id":$id,"username":"DupName","avatar_url":null,"bio":null,"role":"user","created_at":"2026-11-20T00:00:00Z"}"""
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"users":[$usersJson]}"""),
        )
        for (id in 1..5) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "user":{"id":$id,"username":"DupName","avatar_url":null,"bio":null,"role":"user"},
                          "stats":{"trades":$id,"amount":"${id}00.00","last_completed_at":null,"by_role":[{"role":"seller","trades":$id,"amount":"${id}00.00"}]}
                        }
                        """.trimIndent(),
                    ),
            )
        }

        val result = repo.lookup("DupName")
        assertTrue(result is OfficialTradeLookup.Found)
        val found = result as OfficialTradeLookup.Found
        assertEquals(5, found.matches.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), found.matches.map { it.userId })
        // 1 user list call + 5 stats calls = 6 total (IDs 6 & 7 never fetched)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun lookup_notFound_cachesNegativelyForTenMinutes_andExpiresCleanly() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = buildRepository(dispatcher)
        repo.saveApiKey("ntk_fake_notfound_key_12345678")

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"users":[]}"""))
        assertEquals(OfficialTradeLookup.NotFound, repo.lookup("GhostSeller"))
        assertEquals(1, server.requestCount)

        // Within 10-minute negative TTL -> served from cache without hitting server; getCachedOnly stays null
        fakeClockMillis.addAndGet(5L * 60L * 1_000L)
        assertEquals(OfficialTradeLookup.NotFound, repo.lookup("ghostseller"))
        assertNull(repo.getCachedOnly("GhostSeller"))
        assertEquals(1, server.requestCount)

        // Past 10 minutes -> negative cache expires and re-queries server
        fakeClockMillis.addAndGet(5L * 60L * 1_000L + 1L)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"users":[]}"""))
        assertEquals(OfficialTradeLookup.NotFound, repo.lookup("GhostSeller"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun cacheHit_servesMemoryAndDiskForSixHours_andGetCachedOnlyNeverHitsNetwork() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val keyFile = File(tempFolder.root, "cache_key.json")
        val cacheFile = File(tempFolder.root, "cache_entries.json")
        val repo = buildRepository(dispatcher = dispatcher, keyFile = keyFile, cacheFile = cacheFile)
        repo.saveApiKey("ntk_fake_cache_test_key_12345678")

        // Uncached getCachedOnly never hits network
        assertNull(repo.getCachedOnly("Alice"))
        assertEquals(0, server.requestCount)

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"users":[{"id":42,"username":"Alice","role":"user"}]}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"user":{"id":42,"username":"Alice","role":"user"},"stats":{"trades":3,"amount":"1688.00","last_completed_at":"2026-11-20T10:00:00Z","by_role":[{"role":"seller","trades":3,"amount":"1688.00"}]}}""",
            ),
        )

        val initial = repo.lookup("Alice") as OfficialTradeLookup.Found
        assertFalse(initial.fromCache)
        assertEquals(2, server.requestCount)

        // Memory cache hit at +3 hours
        fakeClockMillis.addAndGet(3L * 60L * 60L * 1_000L)
        val memHit = repo.lookup("@ALICE") as OfficialTradeLookup.Found
        assertTrue(memHit.fromCache)
        assertEquals(3, memHit.matches.single().trades)
        assertEquals(2, server.requestCount)

        // Cold restart disk cache hit at +5 hours (new repository instance)
        fakeClockMillis.addAndGet(2L * 60L * 60L * 1_000L)
        val restartedRepo = buildRepository(dispatcher = dispatcher, keyFile = keyFile, cacheFile = cacheFile)
        val badgeHit = restartedRepo.getCachedOnly("alice")
        assertNotNull(badgeHit)
        assertTrue(badgeHit!!.fromCache)
        assertEquals(3, badgeHit.matches.single().trades)
        assertEquals(3, restartedRepo.observeCachedOnly("Alice").first()!!.matches.single().trades)
        assertEquals(2, server.requestCount)

        // Expire past 6h total -> getCachedOnly returns null with zero network calls
        fakeClockMillis.addAndGet(1L * 60L * 60L * 1_000L + 1L)
        assertNull(restartedRepo.getCachedOnly("Alice"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun unauthorized_401AndVerifyApiKey_returnUnauthorizedWithoutCaching() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = buildRepository(dispatcher)
        repo.saveApiKey("ntk_fake_revoked_key_12345678")

        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":"API Key 无效或已吊销","code":"invalid_api_key"}"""),
        )
        assertEquals(OfficialTradeLookup.Unauthorized, repo.lookup("Alice"))

        // Verify API key testing endpoint: 401 -> Unauthorized, 200 -> Found
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":"API Key 无效或已吊销","code":"invalid_api_key"}"""),
        )
        assertEquals(OfficialTradeLookup.Unauthorized, repo.verifyApiKey("ntk_fake_candidate_bad_1234"))

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"users":[]}"""),
        )
        val verified = repo.verifyApiKey("ntk_fake_candidate_ok_1234")
        assertTrue(verified is OfficialTradeLookup.Found)
    }

    @Test
    fun rateLimit_429HonorsRetryAfterAndDefaultsTo60s_blockingRequestsDuringCooldown() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = buildRepository(dispatcher)
        repo.saveApiKey("ntk_fake_ratelimit_key_12345678")

        // 1) 429 with explicit Retry-After: 25
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "25")
                .setBody("""{"error":"Too Many Requests"}"""),
        )
        val first429 = repo.lookup("UserOne")
        assertEquals(OfficialTradeLookup.RateLimited(25L), first429)
        assertEquals(1, server.requestCount)

        // At +10s, cooldown still has 15s remaining -> returns RateLimited immediately with 0 HTTP calls
        fakeClockMillis.addAndGet(10_000L)
        val blockedDuringCooldown = repo.lookup("UserTwo", forceRefresh = true)
        assertEquals(OfficialTradeLookup.RateLimited(15L), blockedDuringCooldown)
        assertEquals(1, server.requestCount)

        // Advance past 25s -> cooldown cleared; now test 429 WITHOUT Retry-After (default 60s)
        fakeClockMillis.addAndGet(16_000L)
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error":"Rate limit exceeded"}"""),
        )
        val default60s = repo.lookup("UserTwo")
        assertEquals(OfficialTradeLookup.RateLimited(60L), default60s)
        assertEquals(2, server.requestCount)

        // At +59s -> still cooling down (1s left), 0 new requests
        fakeClockMillis.addAndGet(59_000L)
        assertEquals(OfficialTradeLookup.RateLimited(1L), repo.lookup("UserThree"))
        assertEquals(2, server.requestCount)

        // At +61s -> recovers and succeeds
        fakeClockMillis.addAndGet(2_000L)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"users":[]}"""))
        assertEquals(OfficialTradeLookup.NotFound, repo.lookup("UserThree"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun clientTokenBucket_capsOutboundRequestsAtConfiguredPerMinuteLimit_andSingleFlightCoalesces() = runTest {
        // Use Dispatchers.IO so all concurrent callers genuinely contend on the in-flight MockWebServer call
        val repo = buildRepository(
            dispatcher = kotlinx.coroutines.Dispatchers.IO,
            maxTokensPerMinute = 4,
        )
        repo.saveApiKey("ntk_fake_bucket_key_12345678")

        val searchCalls = AtomicInteger(0)
        val enteredStatsLatch = CountDownLatch(1)
        val releaseStatsLatch = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.equals("/open/v1/users?username=coalesced", ignoreCase = true) -> {
                        searchCalls.incrementAndGet()
                        MockResponse().setResponseCode(200)
                            .setBody("""{"users":[{"id":77,"username":"Coalesced","role":"user"}]}""")
                    }
                    path == "/open/v1/users/77/stats" -> {
                        enteredStatsLatch.countDown()
                        releaseStatsLatch.await(3, TimeUnit.SECONDS)
                        MockResponse().setResponseCode(200)
                            .setBody("""{"user":{"id":77,"username":"Coalesced","role":"user"},"stats":{"trades":5,"amount":"500.00","last_completed_at":null,"by_role":[]}}""")
                    }
                    else -> MockResponse().setResponseCode(200).setBody("""{"users":[]}""")
                }
            }
        }

        // Launch 5 concurrent lookups for the same username + cancel 1 while /stats is genuinely in-flight
        val cancelled = async(kotlinx.coroutines.Dispatchers.IO) { repo.lookup("Coalesced") }
        assertTrue(enteredStatsLatch.await(3, TimeUnit.SECONDS))
        val callers = (1..4).map {
            async(kotlinx.coroutines.Dispatchers.IO) { repo.lookup("@coalesced") }
        }
        // Give surviving callers a moment to attach to the active flight before cancelling the first waiter
        Thread.sleep(40L)
        cancelled.cancel()
        try {
            cancelled.await()
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // Expected
        }
        releaseStatsLatch.countDown()
        val coalescedResults = callers.awaitAll()
        assertEquals(1, searchCalls.get())
        coalescedResults.forEach { res ->
            assertTrue(res is OfficialTradeLookup.Found)
            assertEquals(5, (res as OfficialTradeLookup.Found).matches.single().trades)
        }
        assertEquals(2, server.requestCount) // Used 2 of 4 tokens

        // Consume remaining 2 tokens with NotFound queries
        assertEquals(OfficialTradeLookup.NotFound, repo.lookup("Miss1"))
        assertEquals(OfficialTradeLookup.NotFound, repo.lookup("Miss2"))
        assertEquals(4, server.requestCount)

        // 5th outbound token within same 60s window is blocked locally by token bucket!
        val bucketLimited = repo.lookup("Miss3")
        assertEquals(OfficialTradeLookup.RateLimited(60L), bucketLimited)
        assertEquals(4, server.requestCount)

        // Advance clock by 60s + 1ms -> tokens replenish
        fakeClockMillis.addAndGet(60_001L)
        assertEquals(OfficialTradeLookup.NotFound, repo.lookup("Miss3"))
        assertEquals(5, server.requestCount)
    }

    @Test
    fun httpsOnly_rejectsCleartextHttpOriginsInProductionMode() {
        try {
            OfficialTradeApiClient(
                baseUrl = "http://agent-api.nodeseek.org".toHttpUrl(),
                allowLoopbackHttpForTesting = false,
            )
            fail("Expected IllegalArgumentException for non-HTTPS baseUrl")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }
}
