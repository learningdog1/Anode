package com.nodeseek.app.trust

import com.nodeseek.app.data.trust.OfficialTradeApiClient
import com.nodeseek.app.data.trust.OfficialTradeCacheStore
import com.nodeseek.app.data.trust.OfficialTradeKeyStore
import com.nodeseek.app.data.trust.OfficialTradeRateLimiter
import com.nodeseek.app.data.trust.OfficialTradeRepositoryImpl
import com.nodeseek.app.data.trust.SellerTopicsPage
import com.nodeseek.app.data.trust.SellerTrustRateLimiter
import com.nodeseek.app.data.trust.SellerTrustRemoteDataSource
import com.nodeseek.app.data.trust.SellerTrustRepositoryImpl
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.model.UserProfile
import com.nodeseek.app.domain.ssh.SecretCipher
import com.nodeseek.app.domain.trust.ExposureCandidate
import com.nodeseek.app.domain.trust.ExposureCheckScope
import com.nodeseek.app.domain.trust.LocalSellerNote
import com.nodeseek.app.domain.trust.OfficialTradeLookup
import com.nodeseek.app.domain.trust.OfficialTradeRepository
import com.nodeseek.app.domain.trust.SellerTrustReport
import com.nodeseek.app.domain.trust.SellerTrustRepository
import com.nodeseek.app.domain.trust.TrustRiskAssessment
import com.nodeseek.app.domain.trust.TrustRiskEvaluator
import com.nodeseek.app.domain.trust.TrustRiskLevel
import com.nodeseek.app.domain.trust.TrustTag
import com.nodeseek.app.ui.trust.SellerTrustStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.AEADBadTagException

@OptIn(ExperimentalCoroutinesApi::class)
class SellerTrustAuditFixRegressionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val baseClockMillis = Instant.parse("2026-11-20T04:00:00Z").toEpochMilli()

    @Test
    fun rssFallbackExposePageIsNotCached30mAndMarksUnverifiedAmberInsteadOfNormalClear() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val exposeCalls = AtomicInteger(0)
        var returnRealBoard = false

        val remote = object : SellerTrustRemoteDataSource {
            override val siteHost: String = "www.nodeseek.com"
            override suspend fun resolveUidByUsername(username: String): Long? = 501L
            override suspend fun fetchSellerProfile(uid: Long): UserProfile = UserProfile(
                uid = uid,
                username = "VeteranSeller",
                avatarUrl = "https://www.nodeseek.com/avatar/$uid.png",
                joinedAtIso = "2024-01-01T00:00:00Z",
                drumsticks = 1600,
                stardust = 80,
                topicCount = 1,
                replyCount = 10,
            )
            override suspend fun fetchSellerTopicsPage1(uid: Long): List<TopicSummary> = emptyList()
            override suspend fun fetchSellerTopicsPage(uid: Long, page: Int): SellerTopicsPage =
                SellerTopicsPage(
                    topics = listOf(
                        TopicSummary(
                            id = 1001L,
                            title = "[出] 搬瓦工 CN2 GIA",
                            categorySlug = "trade",
                            categoryName = "交易",
                            authorId = uid,
                            authorName = "VeteranSeller",
                        ),
                    ),
                    hasMore = false,
                )
            override suspend fun fetchExposeBoardPage1(): FeedPageResult {
                exposeCalls.incrementAndGet()
                return if (!returnRealBoard) {
                    FeedPageResult(topics = emptyList(), isFromRssFallback = true)
                } else {
                    FeedPageResult(topics = emptyList(), isFromRssFallback = false)
                }
            }
            override suspend fun searchExposureTopicsPage1(query: String, categorySlug: String) =
                FeedPageResult(topics = emptyList())
        }

        val clock = { baseClockMillis + testScheduler.currentTime }
        val repo = SellerTrustRepositoryImpl(
            remoteDataSource = remote,
            notesFile = File(tempFolder.root, "rss_fallback_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = clock,
            rateLimiter = SellerTrustRateLimiter(minSiteIntervalMillis = 0L, clockMillis = clock),
            zoneId = ZoneId.of("Asia/Shanghai"),
        )

        val first = repo.inspectSeller(uid = 501L, usernameHint = "VeteranSeller")
        assertFalse("RSS fallback must not mark searchSucceeded = true", first.searchSucceeded)
        assertEquals(ExposureCheckScope.UNVERIFIED, first.exposureCheckScope)
        assertEquals(TrustRiskLevel.CAUTION_AMBER, first.riskAssessment.level)
        assertTrue(first.searchFailureReason.orEmpty().contains("RSS"))

        // Second seller must hit fetchExposeBoardPage1 again because RSS fallback was NOT cached for 30m!
        returnRealBoard = true
        val second = repo.inspectSeller(uid = 502L, usernameHint = "OtherSeller")
        assertEquals(2, exposeCalls.get())
        assertTrue(second.searchSucceeded)
        assertEquals(ExposureCheckScope.BOARD_SNAPSHOT, second.exposureCheckScope)
        assertEquals(TrustRiskLevel.NORMAL_CLEAR, second.riskAssessment.level)
    }

    @Test
    fun exposeBoardSnapshotStoreServesStaleFallbackWhenRemoteFailsAfterFreshTtl() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var manualOffsetMs = 0L
        val clock = { baseClockMillis + testScheduler.currentTime + manualOffsetMs }
        var failExposeBoard = false

        val remote = object : SellerTrustRemoteDataSource {
            override val siteHost: String = "www.nodeseek.com"
            override suspend fun resolveUidByUsername(username: String) = 601L
            override suspend fun fetchSellerProfile(uid: Long) = UserProfile(
                uid = uid,
                username = "Seller$uid",
                avatarUrl = "https://www.nodeseek.com/avatar/$uid.png",
                joinedAtIso = "2024-01-01T00:00:00Z",
                drumsticks = 1600,
                stardust = 80,
                topicCount = 0,
                replyCount = 10,
            )
            override suspend fun fetchSellerTopicsPage1(uid: Long) = emptyList<TopicSummary>()
            override suspend fun fetchExposeBoardPage1(): FeedPageResult {
                if (failExposeBoard) throw IOException("503 upstream down")
                return FeedPageResult(
                    topics = listOf(
                        TopicSummary(
                            id = 999L,
                            title = "曝光 Seller602 收款跑路",
                            categorySlug = "expose",
                            categoryName = "曝光",
                            authorId = 900L,
                            authorName = "victim",
                        ),
                    ),
                )
            }
            override suspend fun searchExposureTopicsPage1(query: String, categorySlug: String) =
                FeedPageResult(topics = emptyList())
        }

        val repo = SellerTrustRepositoryImpl(
            remoteDataSource = remote,
            notesFile = File(tempFolder.root, "stale_expose_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = clock,
            rateLimiter = SellerTrustRateLimiter(minSiteIntervalMillis = 0L, maxTransientRetries = 0, clockMillis = clock),
            zoneId = ZoneId.of("Asia/Shanghai"),
        )

        val first = repo.inspectSeller(uid = 601L, usernameHint = "Seller601")
        assertTrue(first.searchSucceeded)

        // Advance past 30m fresh TTL (still within 6h stale retention) and fail remote expose fetch
        manualOffsetMs += 45L * 60L * 1000L
        failExposeBoard = true
        val second = repo.inspectSeller(uid = 602L, usernameHint = "Seller602")
        assertTrue("Stale expose snapshot should still flag known scammer", second.searchSucceeded)
        assertEquals(1, second.exposureSearchHits.size)
        assertEquals(TrustRiskLevel.HIGH_RISK_RED, second.riskAssessment.level)
    }

    @Test
    fun riskEvaluatorHandlesProfileOrTopicsFailureAndRejectsAmbiguousGenericUsernameNoise() {
        // 1. profileSucceeded = false must NOT falsely blame Lv.0 low rank
        val profileFailed = TrustRiskEvaluator.evaluate(
            rankLevel = 0,
            registrationDays = null,
            historicalTradeTopicCount = 0,
            exposureSearchHits = emptyList(),
            searchSucceeded = true,
            profileSucceeded = false,
            topicsSucceeded = true,
        )
        assertEquals(TrustRiskLevel.CAUTION_AMBER, profileFailed.level)
        assertFalse(profileFailed.reasons.any { it.contains("会员等级较低") })
        assertTrue(profileFailed.reasons.any { it.contains("卖家档案接口加载失败") })

        // 2. topicsSucceeded = false triggers CAUTION_AMBER and does NOT falsely claim zero trade topics
        val topicsFailed = TrustRiskEvaluator.evaluate(
            rankLevel = 4,
            registrationDays = 500,
            historicalTradeTopicCount = 0,
            exposureSearchHits = emptyList(),
            searchSucceeded = true,
            profileSucceeded = true,
            topicsSucceeded = false,
        )
        assertEquals(TrustRiskLevel.CAUTION_AMBER, topicsFailed.level)
        assertTrue(topicsFailed.reasons.any { it.contains("历史主题列表加载失败") })

        // 3. Broad/ambiguous username like "vps" / "dmit" must NOT trigger HIGH_RISK_RED on generic expose title
        val unrelatedExpose = ExposureCandidate(
            topic = TopicSummary(
                id = 888L,
                title = "曝光骗子 @badguy 出 DMIT HK VPS 收款跑路",
                categorySlug = "expose",
                categoryName = "曝光",
                authorId = 900L,
                authorName = "victim",
            ),
        )
        assertTrue(
            TrustRiskEvaluator.filterExposureHits(listOf(unrelatedExpose), targetUid = 10L, targetUsername = "vps").isEmpty(),
        )
        assertTrue(
            TrustRiskEvaluator.filterExposureHits(listOf(unrelatedExpose), targetUid = 11L, targetUsername = "dmit").isEmpty(),
        )
        assertTrue(
            TrustRiskEvaluator.filterExposureHits(listOf(unrelatedExpose), targetUid = 12L, targetUsername = "hk").isEmpty(),
        )

        // Explicit @vps mention STILL matches cleanly
        val directMention = ExposureCandidate(
            topic = TopicSummary(
                id = 889L,
                title = "曝光 @vps 收款不给机跑路",
                categorySlug = "expose",
                categoryName = "曝光",
                authorId = 901L,
                authorName = "victim2",
            ),
        )
        assertEquals(
            1,
            TrustRiskEvaluator.filterExposureHits(listOf(directMention), targetUid = 10L, targetUsername = "vps").size,
        )
    }

    @Test
    fun scanMoreTopicsDeduplicatesShiftedPagesBeyondRecentTradeCap() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // Page 1 has 15 trade topics (IDs 1..15); recentTradeTopics only stores top 10 (1..10).
        // Page 2 shifts and repeats IDs 11..15 + adds 1 truly new trade topic (ID 16).
        val page1Topics = (1L..15L).map { id ->
            TopicSummary(id = id, title = "[出] 机器 #$id", categorySlug = "trade", categoryName = "交易", authorId = 77L, authorName = "Trader77")
        }
        val page2Topics = (11L..16L).map { id ->
            TopicSummary(id = id, title = "[出] 机器 #$id", categorySlug = "trade", categoryName = "交易", authorId = 77L, authorName = "Trader77")
        }

        val remote = object : SellerTrustRemoteDataSource {
            override val siteHost: String = "www.nodeseek.com"
            override suspend fun resolveUidByUsername(username: String) = 77L
            override suspend fun fetchSellerProfile(uid: Long) = UserProfile(
                uid = uid,
                username = "Trader77",
                avatarUrl = "https://www.nodeseek.com/avatar/$uid.png",
                joinedAtIso = "2024-01-01T00:00:00Z",
                drumsticks = 1600,
                stardust = 80,
                topicCount = 16,
                replyCount = 20,
            )
            override suspend fun fetchSellerTopicsPage1(uid: Long) = page1Topics
            override suspend fun fetchSellerTopicsPage(uid: Long, page: Int): SellerTopicsPage =
                when (page) {
                    1 -> SellerTopicsPage(topics = page1Topics, hasMore = true)
                    2 -> SellerTopicsPage(topics = page2Topics, hasMore = false)
                    else -> SellerTopicsPage(topics = emptyList(), hasMore = false)
                }
            override suspend fun fetchExposeBoardPage1() = FeedPageResult(topics = emptyList())
            override suspend fun searchExposureTopicsPage1(query: String, categorySlug: String) =
                FeedPageResult(topics = emptyList())
        }

        val clock = { baseClockMillis + testScheduler.currentTime }
        val repo = SellerTrustRepositoryImpl(
            remoteDataSource = remote,
            notesFile = File(tempFolder.root, "scan_dedup_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = clock,
            maxAutoTopicPages = 1,
            scanMoreTopicPages = 2,
            rateLimiter = SellerTrustRateLimiter(minSiteIntervalMillis = 0L, clockMillis = clock),
            zoneId = ZoneId.of("Asia/Shanghai"),
        )

        val initial = repo.inspectSeller(uid = 77L, usernameHint = "Trader77")
        assertEquals(15, initial.historicalTradeTopicCount)
        assertEquals(15, initial.scannedTopicsCount)

        val expanded = repo.scanMoreSellerTradeTopics(uid = 77L, usernameHint = "Trader77")
        // Only ID 16 is new -> total MUST be 16, NOT 21!
        assertEquals(16, expanded.historicalTradeTopicCount)
        assertEquals(16, expanded.scannedTopicsCount)
    }

    @Test
    fun officialTradeClearApiKeyWipesCacheAndKeyStorePreservesFileOnTransientError() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"users":[{"id":1,"username":"Alice","role":"user"}]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"user":{"id":1,"username":"Alice","role":"user"},"stats":{"trades":4,"amount":"500.00","by_role":[]}}"""))
        server.start()

        val keyFile = File(tempFolder.root, "ot_key_clear.json")
        val cacheFile = File(tempFolder.root, "ot_cache_clear.json")
        val clock = AtomicLong(baseClockMillis)

        var failCipherTransiently = false
        var failCipherAead = false
        val controllableCipher = object : SecretCipher {
            override fun encrypt(plaintext: String): String =
                "enc:" + Base64.getEncoder().encodeToString(plaintext.toByteArray(StandardCharsets.UTF_8))

            override fun decrypt(ciphertext: String): String {
                if (failCipherTransiently) throw IOException("Temporary keystore binder error")
                if (failCipherAead) throw AEADBadTagException("Tag mismatch")
                return String(Base64.getDecoder().decode(ciphertext.removePrefix("enc:")), StandardCharsets.UTF_8)
            }
        }

        try {
            val keyStore = OfficialTradeKeyStore(keyFile = keyFile, cipher = controllableCipher, ioDispatcher = dispatcher, clockMillis = { clock.get() })
            val cacheStore = OfficialTradeCacheStore(cacheFile = cacheFile, ioDispatcher = dispatcher, clockMillis = { clock.get() })
            val repo = OfficialTradeRepositoryImpl(
                apiClient = OfficialTradeApiClient(baseHttpClient = OkHttpClient(), baseUrl = server.url("/"), allowLoopbackHttpForTesting = true, clockMillis = { clock.get() }),
                keyStore = keyStore,
                cacheStore = cacheStore,
                rateLimiter = OfficialTradeRateLimiter(clockMillis = { clock.get() }),
                ioDispatcher = dispatcher,
                clockMillis = { clock.get() },
            )

            repo.saveApiKey("ntk_valid_test_key_1234567890")
            assertTrue(repo.lookup("Alice") is OfficialTradeLookup.Found)
            assertNotNull(repo.getCachedOnly("Alice"))
            assertNotNull(repo.observeCachedOnly("Alice").first())
            assertTrue(cacheFile.exists())

            // Clearing API key wipes cache file + memory and gates getCachedOnly/observeCachedOnly to null
            repo.clearApiKey()
            assertNull(repo.getCachedOnly("Alice"))
            assertNull(repo.observeCachedOnly("Alice").first())
            assertFalse(cacheFile.exists())

            // Transient error during cold load MUST preserve keyFile and recover on ensureLoaded()
            repo.saveApiKey("ntk_valid_test_key_1234567890")
            assertTrue(keyFile.exists())
            failCipherTransiently = true
            val transientKeyStore = OfficialTradeKeyStore(keyFile = keyFile, cipher = controllableCipher, ioDispatcher = dispatcher, clockMillis = { clock.get() })
            assertFalse(transientKeyStore.isConfigured.value)
            assertTrue("keyFile must survive transient error", keyFile.exists())

            failCipherTransiently = false
            transientKeyStore.ensureLoaded()
            assertTrue("keyStore should recover once transient error clears", transientKeyStore.isConfigured.value)

            // Permanent AEAD corruption deletes keyFile cleanly
            failCipherAead = true
            val corruptedKeyStore = OfficialTradeKeyStore(keyFile = keyFile, cipher = controllableCipher, ioDispatcher = dispatcher, clockMillis = { clock.get() })
            assertFalse(corruptedKeyStore.isConfigured.value)
            assertFalse("Permanently corrupted keyFile should be deleted", keyFile.exists())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun stateHolderSkipsPlaceholderUsernameForOfficialTradeAndFlushesNoteOnDismiss() = runTest {
        val savedNotes = mutableListOf<Triple<Long, TrustTag, String>>()
        val sellerRepo = object : SellerTrustRepository {
            override suspend fun inspectSeller(uid: Long, usernameHint: String, forceRefresh: Boolean) =
                SellerTrustReport(
                    uid = uid,
                    username = "用户 #$uid",
                    riskAssessment = TrustRiskAssessment(level = TrustRiskLevel.CAUTION_AMBER, headline = "档案失败"),
                    profileSucceeded = false,
                )
            override fun observeLocalNote(uid: Long): Flow<LocalSellerNote?> = flowOf(null)
            override suspend fun saveLocalNote(uid: Long, username: String, tag: TrustTag, noteText: String) {
                savedNotes += Triple(uid, tag, noteText)
            }
            override suspend fun getAllNotes(): List<LocalSellerNote> = emptyList()
        }

        val officialLookups = mutableListOf<String>()
        val officialRepo = object : OfficialTradeRepository {
            override val isConfigured: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
            override suspend fun lookup(username: String, forceRefresh: Boolean): OfficialTradeLookup {
                officialLookups += username
                return OfficialTradeLookup.NotFound
            }
            override suspend fun getCachedOnly(username: String): OfficialTradeLookup.Found? = null
            override suspend fun saveApiKey(key: String) = Unit
            override suspend fun clearApiKey() = Unit
            override suspend fun verifyApiKey(key: String) = OfficialTradeLookup.NotFound
            override fun maskedApiKey(): String = "ntk_fake…1234"
        }

        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val holder = SellerTrustStateHolder(
                repository = sellerRepo,
                scope = scope,
                uid = 12345L,
                usernameHint = "用户 #12345",
                clockMillis = { baseClockMillis },
                officialTradeRepository = officialRepo,
            )
            holder.load(forceRefresh = false)
            advanceUntilIdle()
            assertTrue("Placeholder username must never hit official trade API: $officialLookups", officialLookups.isEmpty())

            // Typing a note and immediately dismissing before 700ms debounce still persists!
            holder.updateDraftNoteText("先走中介再交易")
            holder.flushPendingNoteOnDismiss()
            advanceUntilIdle()
            assertTrue(savedNotes.any { it.first == 12345L && it.third == "先走中介再交易" })
        } finally {
            scope.cancel()
        }
    }
}
