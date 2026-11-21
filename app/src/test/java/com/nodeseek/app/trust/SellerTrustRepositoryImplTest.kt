package com.nodeseek.app.trust

import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.data.trust.SellerTrustRepositoryImpl
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.ReactionResult
import com.nodeseek.app.domain.model.ReactionType
import com.nodeseek.app.domain.model.TopicAccessException
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.model.UserProfile
import com.nodeseek.app.domain.model.UserReplyExcerpt
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.repository.UserRepository
import com.nodeseek.app.domain.trust.ExposureCheckScope
import com.nodeseek.app.domain.trust.TrustFetchStatus
import com.nodeseek.app.domain.trust.TrustRiskEvaluator
import com.nodeseek.app.domain.trust.TrustRiskLevel
import com.nodeseek.app.domain.trust.TrustTag
import com.nodeseek.app.ui.trust.SellerTrustLoadStatus
import com.nodeseek.app.ui.trust.SellerTrustStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SellerTrustRepositoryImplTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val shanghaiZone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val baseClockMillis: Long = Instant.parse("2026-11-19T04:00:00Z").toEpochMilli()

    @Test
    fun inspectSeller_fetchesProfileTopicsAndExposeBoardWithZeroSearchCalls_andSharesBoardSnapshotAcrossSellers() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchCalls = AtomicInteger(0)
        val exposeFeedCalls = AtomicInteger(0)
        val userRepo = FakeUserRepository(
            onGetProfile = { uid ->
                val name = if (uid == 1001L) "CloudSeller" else "ShadyVendor"
                Result.success(sampleProfile(uid = uid, username = name, coins = 1750, joinedAtIso = "2024-07-10T08:00:00Z"))
            },
            onGetTopics = { uid, _ ->
                Result.success(
                    listOf(
                        sampleTopic(id = 11L, title = "[出] DMIT LAX Pro", categorySlug = "trade", categoryName = "交易", authorId = uid, authorName = "CloudSeller"),
                        sampleTopic(id = 12L, title = "日常水一贴", categorySlug = "daily", categoryName = "日常", authorId = uid, authorName = "CloudSeller"),
                        sampleTopic(id = 13L, title = "剩余价值出美西 VPS", categorySlug = "daily", categoryName = "日常", authorId = uid, authorName = "CloudSeller"),
                    ) to false,
                )
            },
        )
        val forumRepo = FakeForumRepository(
            onGetFeed = { slug, _, _ ->
                if (slug == "expose") {
                    exposeFeedCalls.incrementAndGet()
                    Result.success(
                        FeedPageResult(
                            topics = listOf(
                                sampleTopic(
                                    id = 888L,
                                    title = "曝光 ShadyVendor 收款不给机跑路",
                                    categorySlug = "expose",
                                    categoryName = "曝光",
                                    authorId = 9001L,
                                    authorName = "victim88",
                                ),
                            ),
                        ),
                    )
                } else {
                    Result.success(FeedPageResult(topics = emptyList()))
                }
            },
            onSearch = { _, _ ->
                searchCalls.incrementAndGet()
                Result.success(FeedPageResult(topics = emptyList()))
            },
        )

        val repo = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = File(tempFolder.root, "seller_trust_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = { baseClockMillis + testScheduler.currentTime },
            zoneId = shanghaiZone,
        )

        val report1 = repo.inspectSeller(uid = 1001L, usernameHint = "CloudSeller")
        assertTrue(
            "Expected global concurrency cap <= 2, actual=${repo.rateLimiter.peakConcurrencyForTest}",
            repo.rateLimiter.peakConcurrencyForTest <= 2,
        )
        assertEquals(0, searchCalls.get())
        assertEquals(1, exposeFeedCalls.get())
        assertEquals(1001L, report1.uid)
        assertEquals("CloudSeller", report1.username)
        assertEquals(4, report1.rankLevel)
        assertEquals(1750, report1.coins)
        assertEquals(2, report1.historicalTradeTopicCount)
        assertEquals(2, report1.recentTradeTopics.size)
        assertTrue(report1.searchSucceeded)
        assertEquals(ExposureCheckScope.BOARD_SNAPSHOT, report1.exposureCheckScope)
        assertTrue(report1.exposureSearchHits.isEmpty())
        assertEquals(TrustRiskLevel.NORMAL_CLEAR, report1.riskAssessment.level)
        assertFalse(report1.isFromCache)
        assertEquals(TrustFetchStatus.FRESH, report1.fetchStatus)

        // Second seller reuses shared `/categories/expose` snapshot -> still 1 board fetch, 0 `/search` calls, flags hit!
        val report2 = repo.inspectSeller(uid = 1002L, usernameHint = "ShadyVendor")
        assertEquals(0, searchCalls.get())
        assertEquals(1, exposeFeedCalls.get())
        assertEquals(1, report2.exposureSearchHits.size)
        assertEquals(TrustRiskLevel.HIGH_RISK_RED, report2.riskAssessment.level)
    }

    @Test
    fun searchSellerExposureDeep_isolatesSearchRateLimitFromProfileChecks_andEnforcesIntervalWithoutBlocking() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var manualOffsetMillis = 0L
        val clock = { baseClockMillis + testScheduler.currentTime + manualOffsetMillis }
        val searchCalls = AtomicInteger(0)
        var shouldFailSearch429 = true

        val userRepo = FakeUserRepository(
            onGetProfile = { uid ->
                Result.success(sampleProfile(uid = uid, username = "VeteranUser$uid", coins = 2600, joinedAtIso = "2023-05-01T00:00:00Z"))
            },
            onGetTopics = { uid, _ ->
                Result.success(
                    listOf(
                        sampleTopic(id = 21L, title = "[出] 搬瓦工", categorySlug = "trade", categoryName = "交易", authorId = uid, authorName = "VeteranUser$uid"),
                    ) to false,
                )
            },
        )
        val forumRepo = FakeForumRepository(
            onGetFeed = { _, _, _ -> Result.success(FeedPageResult(topics = emptyList())) },
            onSearch = { _, _ ->
                searchCalls.incrementAndGet()
                if (shouldFailSearch429) {
                    Result.failure(NodeSeekApiException(message = "Too Many Requests", statusCode = 429, retryAfterSeconds = 20L))
                } else {
                    Result.success(FeedPageResult(topics = emptyList()))
                }
            },
        )

        val repo = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = File(tempFolder.root, "isolated_search_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = clock,
            zoneId = shanghaiZone,
        )

        val baseReport = repo.inspectSeller(uid = 2002L, usernameHint = "VeteranUser2002")
        assertTrue(baseReport.profileSucceeded)
        assertTrue(baseReport.searchSucceeded)
        assertEquals(0, searchCalls.get())

        // Deep search hits 429 -> enters searchRateLimiter cooldown, returns notice without poisoning profile limiter!
        val limitedDeep = repo.searchSellerExposureDeep(uid = 2002L, usernameHint = "VeteranUser2002")
        assertEquals(1, searchCalls.get())
        assertTrue(limitedDeep.deepSearchCooldownUntilEpochMillis > clock())
        assertTrue(limitedDeep.deepSearchNoticeMessage.orEmpty().contains("限流"))
        assertEquals(0L, repo.rateLimiter.getActiveCooldownUntil("www.nodeseek.com", clock()))

        // Inspecting another seller while `/search` is cooling down still succeeds cleanly!
        val otherSeller = repo.inspectSeller(uid = 2003L, usernameHint = "VeteranUser2003")
        assertTrue(otherSeller.profileSucceeded)
        assertEquals(TrustRiskLevel.NORMAL_CLEAR, otherSeller.riskAssessment.level)

        // Advance past 20s+ jitter search cooldown and succeed on deep search
        manualOffsetMillis += 35_000L
        shouldFailSearch429 = false
        val okDeep = repo.searchSellerExposureDeep(uid = 2002L, usernameHint = "VeteranUser2002")
        assertEquals(2, searchCalls.get())
        assertTrue(okDeep.isDeepSearchVerified)
        assertEquals(0L, okDeep.deepSearchCooldownUntilEpochMillis)

        // Immediate repeat deep search 2s later is gated by 10s searchMinIntervalMillis WITHOUT sleeping or hitting /search
        manualOffsetMillis += 2_000L
        val gatedDeep = repo.searchSellerExposureDeep(uid = 2002L, usernameHint = "VeteranUser2002")
        assertEquals(2, searchCalls.get())
        assertTrue(gatedDeep.deepSearchCooldownUntilEpochMillis > clock())
        assertTrue(gatedDeep.deepSearchNoticeMessage.orEmpty().contains("限流"))
    }

    @Test
    fun searchSellerExposureDeep_mergesHitsWithBoardSnapshot_andPreservesThemOnProfileRefresh() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val userRepo = FakeUserRepository(
            onGetProfile = { uid ->
                Result.success(sampleProfile(uid = uid, username = "ShadyGuy", coins = 950, joinedAtIso = "2024-01-10T00:00:00Z"))
            },
            onGetTopics = { _, _ -> Result.success(emptyList<TopicSummary>() to false) },
        )
        val forumRepo = FakeForumRepository(
            onGetFeed = { _, _, _ ->
                Result.success(
                    FeedPageResult(
                        topics = listOf(
                            sampleTopic(
                                id = 908L,
                                title = "曝光 ShadyGuy 疑似跑路",
                                categorySlug = "expose",
                                categoryName = "曝光",
                                authorId = 887L,
                                authorName = "victim0",
                            ),
                        ),
                    ),
                )
            },
            onSearch = { _, _ ->
                Result.success(
                    FeedPageResult(
                        topics = listOf(
                            sampleTopic(
                                id = 909L,
                                title = "骗子 ShadyGuy 收钱拉黑跑路",
                                categorySlug = "daily",
                                categoryName = "日常",
                                authorId = 888L,
                                authorName = "victim1",
                            ),
                        ),
                    ),
                )
            },
        )

        val repo = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = File(tempFolder.root, "merge_deep_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = { baseClockMillis + testScheduler.currentTime },
            zoneId = shanghaiZone,
        )

        val initial = repo.inspectSeller(uid = 3003L, usernameHint = "ShadyGuy")
        assertEquals(ExposureCheckScope.BOARD_SNAPSHOT, initial.exposureCheckScope)
        assertEquals(1, initial.exposureSearchHits.size)
        assertEquals(TrustRiskLevel.HIGH_RISK_RED, initial.riskAssessment.level)

        val deep = repo.searchSellerExposureDeep(uid = 3003L, usernameHint = "ShadyGuy")
        assertEquals(ExposureCheckScope.DEEP_SEARCH, deep.exposureCheckScope)
        assertEquals(2, deep.exposureSearchHits.size)
        assertEquals(setOf(908L, 909L), deep.exposureSearchHits.map { it.topicId }.toSet())
        assertEquals(TrustRiskLevel.HIGH_RISK_RED, deep.riskAssessment.level)
    }

    @Test
    fun searchSellerExposureDeep_deduplicatesExposureHitsAndFiltersNoise() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchedArgs = mutableListOf<Pair<String, String>>()
        val userRepo = FakeUserRepository(
            onGetProfile = { uid ->
                Result.success(sampleProfile(uid = uid, username = "fox", coins = 420, joinedAtIso = "2025-01-01T00:00:00Z"))
            },
            onGetTopics = { _, _ -> Result.success(emptyList<TopicSummary>() to false) },
        )
        val sharedExposure = sampleTopic(
            id = 500L,
            title = "曝光 fox 骗子",
            categorySlug = "expose",
            categoryName = "曝光",
            authorId = 700L,
            authorName = "alice",
        )
        val noiseSubstring = sampleTopic(
            id = 501L,
            title = "曝光 foxmail 骗子",
            categorySlug = "expose",
            categoryName = "曝光",
            authorId = 701L,
            authorName = "bob",
        )
        val forumRepo = FakeForumRepository(
            onGetFeed = { _, _, _ -> Result.success(FeedPageResult(topics = listOf(sharedExposure, noiseSubstring))) },
            onSearch = { query, categorySlug ->
                searchedArgs += query to categorySlug
                Result.success(FeedPageResult(topics = listOf(sharedExposure, noiseSubstring)))
            },
        )

        val repo = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = File(tempFolder.root, "fox_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = { baseClockMillis + testScheduler.currentTime },
            zoneId = shanghaiZone,
        )

        val report = repo.searchSellerExposureDeep(uid = 4004L, usernameHint = "fox")
        assertEquals(1, searchedArgs.size)
        assertEquals("fox" to "expose", searchedArgs.single())
        assertEquals(1, report.exposureSearchHits.size)
        assertEquals(500L, report.exposureSearchHits.single().topicId)
        assertEquals(TrustRiskLevel.HIGH_RISK_RED, report.riskAssessment.level)
    }

    @Test
    fun inspectSeller_cachesPositiveResultFor18Hours_persistsToDisk_andThrottlesRapidForceRefresh() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var manualClockOffsetMillis = 0L
        val clock = { baseClockMillis + testScheduler.currentTime + manualClockOffsetMillis }
        val profileCalls = AtomicInteger(0)
        val notesFile = File(tempFolder.root, "seller_trust_notes.json")

        val userRepo = FakeUserRepository(
            onGetProfile = { uid ->
                profileCalls.incrementAndGet()
                Result.success(sampleProfile(uid = uid, username = "CachedSeller", coins = 1600, joinedAtIso = "2024-06-01T00:00:00Z"))
            },
            onGetTopics = { _, _ -> Result.success(emptyList<TopicSummary>() to false) },
        )
        val forumRepo = FakeForumRepository(
            onSearch = { _, _ -> Result.success(FeedPageResult(topics = emptyList())) },
        )

        val repo = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = notesFile,
            ioDispatcher = dispatcher,
            clockMillis = clock,
            zoneId = shanghaiZone,
        )

        val first = repo.inspectSeller(uid = 5005L, usernameHint = "CachedSeller")
        assertFalse(first.isFromCache)
        assertEquals(TrustFetchStatus.FRESH, first.fetchStatus)
        assertEquals(1, profileCalls.get())

        // Immediate forceRefresh 2s later -> throttled by manualRefreshMinIntervalMillis (15s)!
        manualClockOffsetMillis += 2_000L
        val throttledForce = repo.inspectSeller(uid = 5005L, usernameHint = "CachedSeller", forceRefresh = true)
        assertTrue(throttledForce.isFromCache)
        assertTrue(throttledForce.isRefreshThrottled)
        assertEquals(1, profileCalls.get())

        // 12 hours later -> still fresh within 18h TTL across a brand-new repository instance (disk cache)!
        manualClockOffsetMillis += 12L * 60L * 60L * 1000L
        val repoReloadedFromDisk = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = notesFile,
            ioDispatcher = dispatcher,
            clockMillis = clock,
            zoneId = shanghaiZone,
        )
        val fromDisk = repoReloadedFromDisk.inspectSeller(uid = 5005L, usernameHint = "CachedSeller")
        assertTrue(fromDisk.isFromCache)
        assertEquals(TrustFetchStatus.FROM_CACHE, fromDisk.fetchStatus)
        assertEquals(1, profileCalls.get())

        // forceRefresh past 15s throttle -> bypasses cache and refetches
        val forced = repoReloadedFromDisk.inspectSeller(uid = 5005L, usernameHint = "CachedSeller", forceRefresh = true)
        assertFalse(forced.isFromCache)
        assertEquals(2, profileCalls.get())

        // 19 hours later -> 18h TTL expired -> refetches automatically
        manualClockOffsetMillis += 19L * 60L * 60L * 1000L
        val expired = repoReloadedFromDisk.inspectSeller(uid = 5005L, usernameHint = "CachedSeller")
        assertFalse(expired.isFromCache)
        assertEquals(3, profileCalls.get())
    }

    @Test
    fun localSellerNotes_persistAcrossRepositoryInstances_andReEvaluateCachedRisk() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var manualOffsetMillis = 0L
        val clock = { baseClockMillis + testScheduler.currentTime + manualOffsetMillis }
        val notesFile = File(tempFolder.root, "seller_trust_notes.json")

        val userRepo = FakeUserRepository(
            onGetProfile = { uid ->
                Result.success(sampleProfile(uid = uid, username = "NoteTarget", coins = 1600, joinedAtIso = "2024-05-01T00:00:00Z"))
            },
            onGetTopics = { _, _ -> Result.success(emptyList<TopicSummary>() to false) },
        )
        val forumRepo = FakeForumRepository(
            onSearch = { _, _ -> Result.success(FeedPageResult(topics = emptyList())) },
        )

        val repo1 = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = notesFile,
            ioDispatcher = dispatcher,
            clockMillis = clock,
            zoneId = shanghaiZone,
        )

        val initialReport = repo1.inspectSeller(uid = 6006L, usernameHint = "NoteTarget")
        assertEquals(TrustRiskLevel.NORMAL_CLEAR, initialReport.riskAssessment.level)

        // Save blacklist note -> cached report immediately re-evaluates to HIGH_RISK_RED
        repo1.saveLocalNote(
            uid = 6006L,
            username = "NoteTarget",
            tag = TrustTag.RISK_BLACKLIST,
            noteText = "先款后失联，绝对避雷",
        )
        val cachedAfterBlacklist = repo1.inspectSeller(uid = 6006L, usernameHint = "NoteTarget")
        assertTrue(cachedAfterBlacklist.isFromCache)
        assertEquals(TrustRiskLevel.HIGH_RISK_RED, cachedAfterBlacklist.riskAssessment.level)
        assertEquals(TrustTag.RISK_BLACKLIST, cachedAfterBlacklist.localNote?.tag)
        assertEquals("先款后失联，绝对避雷", cachedAfterBlacklist.localNote?.noteText)

        // Save second seller note with later timestamp
        manualOffsetMillis += 5_000L
        repo1.saveLocalNote(
            uid = 6007L,
            username = "GoodGuy",
            tag = TrustTag.TRUSTED,
            noteText = "秒 Push 很爽快",
        )

        // Re-instantiate repository pointing to the exact same JSON file to prove persistence
        val repo2 = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = notesFile,
            ioDispatcher = dispatcher,
            clockMillis = clock,
            zoneId = shanghaiZone,
        )

        val observed = repo2.observeLocalNote(6006L).first()
        assertNotNull(observed)
        assertEquals(TrustTag.RISK_BLACKLIST, observed?.tag)
        assertEquals("先款后失联，绝对避雷", observed?.noteText)

        val allNotes = repo2.getAllNotes()
        assertEquals(2, allNotes.size)
        assertEquals(6007L, allNotes[0].uid) // newest first
        assertEquals(6006L, allNotes[1].uid)

        // Clearing note (NONE + blank text) removes it cleanly
        repo2.saveLocalNote(uid = 6006L, username = "NoteTarget", tag = TrustTag.NONE, noteText = "   ")
        assertNull(repo2.observeLocalNote(6006L).first())
        assertEquals(1, repo2.getAllNotes().size)
    }

    @Test
    fun inspectSeller_resolvesCanonicalUsernameUpfront_withZeroSearchCalls_andSingleQueryDeepSearch() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchedQueries = mutableListOf<Pair<String, String>>()

        val userRepo = FakeUserRepository(
            onGetProfile = { uid ->
                Result.success(sampleProfile(uid = uid, username = "RealSellerName", coins = 900, joinedAtIso = "2025-01-01T00:00:00Z"))
            },
            onGetTopics = { _, _ -> Result.success(emptyList<TopicSummary>() to false) },
        )
        val forumRepo = FakeForumRepository(
            onGetFeed = { _, _, _ -> Result.success(FeedPageResult(topics = emptyList())) },
            onSearch = { query, categorySlug ->
                searchedQueries += query to categorySlug
                Result.success(FeedPageResult(topics = emptyList()))
            },
        )

        val repo = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = File(tempFolder.root, "seller_trust_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = { baseClockMillis + testScheduler.currentTime },
            zoneId = shanghaiZone,
        )

        val report = repo.inspectSeller(uid = 7007L, usernameHint = "OutdatedHintName")
        assertEquals("RealSellerName", report.username)
        assertEquals(0, searchedQueries.size)

        // Explicit deep search issues exactly ONE canonical query across all boards (not 2 or 4!)
        val deep = repo.searchSellerExposureDeep(uid = 7007L, usernameHint = "OutdatedHintName")
        assertTrue(deep.isDeepSearchVerified)
        assertEquals(listOf("RealSellerName" to ""), searchedQueries)
    }

    @Test
    fun stateHolder_updatesTagDebouncedNote_andEnforcesManualRefreshThrottle() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val notesFile = File(tempFolder.root, "state_holder_notes.json")
        val profileCalls = AtomicInteger(0)

        val userRepo = FakeUserRepository(
            onGetProfile = { uid ->
                profileCalls.incrementAndGet()
                Result.success(sampleProfile(uid = uid, username = "LiveSeller", coins = 1600, joinedAtIso = "2024-03-01T00:00:00Z"))
            },
            onGetTopics = { _, _ -> Result.success(emptyList<TopicSummary>() to false) },
        )
        val forumRepo = FakeForumRepository(
            onSearch = { _, _ -> Result.success(FeedPageResult(topics = emptyList())) },
        )

        val clock = { baseClockMillis + testScheduler.currentTime }
        val repo = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = notesFile,
            ioDispatcher = dispatcher,
            clockMillis = clock,
            zoneId = shanghaiZone,
        )

        val holderScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val holder = SellerTrustStateHolder(
                repository = repo,
                scope = holderScope,
                uid = 8008L,
                usernameHint = "LiveSeller",
                clockMillis = clock,
            )
            holder.load()
            advanceUntilIdle()

            assertNotNull(holder.uiState.value.report)
            assertEquals(SellerTrustLoadStatus.FRESH, holder.uiState.value.loadStatus)
            assertEquals(TrustRiskLevel.NORMAL_CLEAR, holder.uiState.value.report?.riskAssessment?.level)
            assertEquals(1, profileCalls.get())

            // Rapid manual refresh tap 1s later is throttled and enters countdown
            advanceTimeBy(1_000L)
            holder.refresh()
            advanceTimeBy(100L)
            assertEquals(1, profileCalls.get())
            assertTrue(holder.uiState.value.isManualRefreshThrottled)
            assertTrue(holder.uiState.value.cooldownRemainingSeconds > 0)

            // Let countdown tick down to 0
            advanceTimeBy(16_000L)
            advanceUntilIdle()
            assertEquals(0, holder.uiState.value.cooldownRemainingSeconds)

            // 1-tap select RISK_BLACKLIST -> immediately flips risk to HIGH_RISK_RED
            holder.toggleTag(TrustTag.RISK_BLACKLIST)
            advanceUntilIdle()
            assertEquals(TrustTag.RISK_BLACKLIST, holder.uiState.value.draftTag)
            assertEquals(TrustRiskLevel.HIGH_RISK_RED, holder.uiState.value.report?.riskAssessment?.level)

            // 1-tap toggle same tag off -> reverts to NONE & NORMAL_CLEAR
            holder.toggleTag(TrustTag.RISK_BLACKLIST)
            advanceUntilIdle()
            assertEquals(TrustTag.NONE, holder.uiState.value.draftTag)
            assertEquals(TrustRiskLevel.NORMAL_CLEAR, holder.uiState.value.report?.riskAssessment?.level)
            assertNull(repo.observeLocalNote(8008L).first())

            // Debounced text note save
            holder.updateDraftNoteText("仅支持官方中介")
            advanceTimeBy(800L)
            advanceUntilIdle()
            assertEquals("仅支持官方中介", repo.observeLocalNote(8008L).first()?.noteText)
        } finally {
            holderScope.cancel()
        }
    }

    @Test
    fun inspectSeller_profileFailureStillRunsExposureSearchAndUsesHintFallback() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val userRepo = FakeUserRepository(
            onGetProfile = { Result.failure(RuntimeException("502 Bad Gateway")) },
            onGetTopics = { _, _ -> Result.failure(RuntimeException("502 Bad Gateway")) },
        )
        val forumRepo = FakeForumRepository(
            onGetFeed = { _, _, _ ->
                Result.success(
                    FeedPageResult(
                        topics = listOf(
                            sampleTopic(
                                id = 999L,
                                title = "曝光 GhostSeller 跑路",
                                categorySlug = "expose",
                                categoryName = "曝光",
                                authorId = 321L,
                                authorName = "buyerX",
                            ),
                        ),
                    ),
                )
            },
            onSearch = { _, _ -> Result.success(FeedPageResult(topics = emptyList())) },
        )

        val repo = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = File(tempFolder.root, "profile_fail_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = { baseClockMillis + testScheduler.currentTime },
            zoneId = shanghaiZone,
        )

        val report = repo.inspectSeller(uid = 9001L, usernameHint = "GhostSeller")
        assertFalse(report.profileSucceeded)
        assertFalse(report.topicsSucceeded)
        assertTrue(report.searchSucceeded)
        assertEquals("GhostSeller", report.username)
        assertEquals(1, report.exposureSearchHits.size)
        assertEquals(TrustRiskEvaluator.TERM_RUNAWAY, report.exposureSearchHits.single().matchedTerm)
        assertEquals(TrustRiskLevel.HIGH_RISK_RED, report.riskAssessment.level)
    }

    @Test
    fun inspectSeller_totalRemoteFailureUsesShortNegativeCache_andStillHonorsLocalBlacklist() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var manualOffsetMillis = 0L
        val clock = { baseClockMillis + testScheduler.currentTime + manualOffsetMillis }
        val profileCalls = AtomicInteger(0)
        val userRepo = FakeUserRepository(
            onGetProfile = {
                profileCalls.incrementAndGet()
                Result.failure(RuntimeException("Offline"))
            },
            onGetTopics = { _, _ -> Result.failure(RuntimeException("Offline")) },
        )
        val forumRepo = FakeForumRepository(
            onGetFeed = { _, _, _ -> Result.failure(RuntimeException("Offline")) },
            onSearch = { _, _ -> Result.failure(RuntimeException("Offline")) },
        )

        val repo = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = File(tempFolder.root, "offline_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = clock,
            zoneId = shanghaiZone,
        )

        repo.saveLocalNote(uid = 9002L, username = "OfflineBad", tag = TrustTag.RISK_BLACKLIST, noteText = "线下拉黑")

        val first = repo.inspectSeller(uid = 9002L, usernameHint = "OfflineBad")
        assertFalse(first.profileSucceeded)
        assertFalse(first.searchSucceeded)
        assertEquals(TrustFetchStatus.FAILED_RETRYABLE, first.fetchStatus)
        assertEquals(TrustRiskLevel.HIGH_RISK_RED, first.riskAssessment.level)
        assertEquals(1, profileCalls.get())

        // Within 3-minute negative TTL -> non-forced read hits short negative cache instead of hammering server
        manualOffsetMillis += 30_000L
        val negativeHit = repo.inspectSeller(uid = 9002L, usernameHint = "OfflineBad")
        assertTrue(negativeHit.isFromCache)
        assertTrue(negativeHit.isNegativeCache)
        assertEquals(TrustFetchStatus.FAILED_RETRYABLE, negativeHit.fetchStatus)
        assertEquals(TrustRiskLevel.HIGH_RISK_RED, negativeHit.riskAssessment.level)
        assertEquals(1, profileCalls.get())

        // Manual retry (forceRefresh = true) after failedRetryMinIntervalMillis (3s) bypasses negative cache
        val manualRetry = repo.inspectSeller(uid = 9002L, usernameHint = "OfflineBad", forceRefresh = true)
        assertFalse(manualRetry.isFromCache)
        assertEquals(2, profileCalls.get())

        // After 3-minute negative TTL expires -> automatic read retries again
        manualOffsetMillis += 4L * 60L * 1000L
        val afterNegTtl = repo.inspectSeller(uid = 9002L, usernameHint = "OfflineBad")
        assertFalse(afterNegTtl.isFromCache)
        assertEquals(3, profileCalls.get())
    }

    @Test
    fun inspectSeller_resolvesUidViaSearchUsersWhenUidIsZero() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val userRepo = FakeUserRepository(
            onGetProfile = { uid ->
                Result.success(sampleProfile(uid = uid, username = "MentionedSeller", coins = 2500, joinedAtIso = "2024-01-01T00:00:00Z"))
            },
            onGetTopics = { _, _ -> Result.success(emptyList<TopicSummary>() to false) },
            onSearchUsers = { keyword ->
                Result.success(listOf(FloorAuthor(uid = 7788L, username = keyword)))
            },
        )
        val forumRepo = FakeForumRepository(
            onSearch = { _, _ -> Result.success(FeedPageResult(topics = emptyList())) },
        )

        val repo = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = File(tempFolder.root, "uid_resolve_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = { baseClockMillis + testScheduler.currentTime },
            zoneId = shanghaiZone,
        )

        val report = repo.inspectSeller(uid = 0L, usernameHint = "@MentionedSeller")
        assertEquals(7788L, report.uid)
        assertEquals("MentionedSeller", report.username)
        assertEquals(5, report.rankLevel)
        assertEquals(TrustRiskLevel.NORMAL_CLEAR, report.riskAssessment.level)
    }

    private fun sampleProfile(
        uid: Long,
        username: String,
        coins: Int,
        joinedAtIso: String,
        topicCount: Int = 15,
    ): UserProfile = UserProfile(
        uid = uid,
        username = username,
        avatarUrl = "https://www.nodeseek.com/avatar/$uid.png",
        joinedAtIso = joinedAtIso,
        drumsticks = coins,
        stardust = 120,
        topicCount = topicCount,
        replyCount = 88,
    )

    private fun sampleTopic(
        id: Long,
        title: String,
        categorySlug: String,
        categoryName: String,
        authorId: Long?,
        authorName: String,
    ): TopicSummary = TopicSummary(
        id = id,
        title = title,
        categorySlug = categorySlug,
        categoryName = categoryName,
        authorId = authorId,
        authorName = authorName,
        lastActivityIso = "2026-11-18T12:00:00Z",
    )

    private class FakeUserRepository(
        private val onGetProfile: suspend (Long) -> Result<UserProfile>,
        private val onGetTopics: suspend (Long, Int) -> Result<Pair<List<TopicSummary>, Boolean>>,
        private val onSearchUsers: suspend (String) -> Result<List<FloorAuthor>> = { Result.success(emptyList()) },
    ) : UserRepository {
        override suspend fun getUserProfile(uid: Long, includeEditableSignature: Boolean): Result<UserProfile> =
            onGetProfile(uid)

        override suspend fun updateOwnProfile(bio: String, signature: String, readme: String): Result<Unit> =
            Result.success(Unit)

        override suspend fun getUserTopics(uid: Long, page: Int): Result<Pair<List<TopicSummary>, Boolean>> =
            onGetTopics(uid, page)

        override suspend fun getUserReplies(uid: Long, page: Int): Result<Pair<List<UserReplyExcerpt>, Boolean>> =
            Result.success(emptyList<UserReplyExcerpt>() to false)

        override suspend fun searchUsers(keyword: String): Result<List<FloorAuthor>> =
            onSearchUsers(keyword)

        override suspend fun setFollowed(targetUid: Long, follow: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun getConnections(following: Boolean): Result<List<FloorAuthor>> = Result.success(emptyList())
        override suspend fun getBlockedUsers(): Result<List<FloorAuthor>> = Result.success(emptyList())
        override suspend fun blockUserByName(username: String): Result<Unit> = Result.success(Unit)
        override suspend fun unblockUserById(targetUid: Long): Result<Unit> = Result.success(Unit)
    }

    private class FakeForumRepository(
        private val onGetFeed: suspend (String, FeedSort, Int) -> Result<FeedPageResult> = { _, _, _ ->
            Result.success(FeedPageResult(topics = emptyList()))
        },
        private val onSearch: suspend (String, String) -> Result<FeedPageResult> = { _, _ ->
            Result.success(FeedPageResult(topics = emptyList()))
        },
    ) : ForumRepository {
        override fun observeCategories(): Flow<List<Category>> = emptyFlow()
        override suspend fun refreshCategories(force: Boolean): Result<List<Category>> = Result.success(emptyList())
        override suspend fun getFeed(categorySlug: String, sort: FeedSort, page: Int, includeBlocked: Boolean, forceRefresh: Boolean): Result<FeedPageResult> =
            onGetFeed(categorySlug, sort, page)
        override suspend fun getCuratedFeed(page: Int, forceRefresh: Boolean): Result<FeedPageResult> =
            Result.success(FeedPageResult(topics = emptyList()))
        override suspend fun searchTopics(query: String, categorySlug: String, sort: FeedSort, page: Int): Result<FeedPageResult> =
            onSearch(query, categorySlug)
        override suspend fun getTopicDetail(topicId: Long, page: Int, includeBlocked: Boolean): Result<TopicDetailPage> =
            Result.failure(UnsupportedOperationException())
        override suspend fun createTopic(categorySlug: String, title: String, markdownContent: String, requiredViewLevel: Int): Result<Unit> =
            Result.success(Unit)
        override suspend fun editTopic(topicId: Long, title: String, markdownContent: String, requiredViewLevel: Int): Result<Unit> =
            Result.success(Unit)
        override suspend fun postReply(topicId: Long, markdownContent: String): Result<Unit> = Result.success(Unit)
        override suspend fun editReply(commentId: Long, markdownContent: String): Result<Unit> = Result.success(Unit)
        override suspend fun setReplyPinned(commentId: Long, pinned: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun getFreeDrumstickCost(): Result<Int> = Result.success(0)
        override suspend fun reactToComment(topicId: Long, commentId: Long, reaction: ReactionType): Result<ReactionResult> =
            Result.failure(UnsupportedOperationException())
    }
}
