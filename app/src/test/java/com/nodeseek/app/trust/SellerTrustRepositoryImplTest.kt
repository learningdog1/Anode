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
import com.nodeseek.app.domain.trust.TrustRiskEvaluator
import com.nodeseek.app.domain.trust.TrustRiskLevel
import com.nodeseek.app.domain.trust.TrustTag
import com.nodeseek.app.ui.trust.SellerTrustStateHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
    fun inspectSeller_runsProfileTopicsAndBothSearchesConcurrently() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val startedCount = AtomicInteger(0)
        val allFourStarted = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()

        suspend fun awaitBarrier() {
            if (startedCount.incrementAndGet() >= 4) {
                allFourStarted.complete(Unit)
            }
            releaseGate.await()
        }

        val userRepo = FakeUserRepository(
            onGetProfile = { uid ->
                awaitBarrier()
                Result.success(sampleProfile(uid = uid, username = "CloudSeller", coins = 1750, joinedAtIso = "2024-07-10T08:00:00Z"))
            },
            onGetTopics = { uid, _ ->
                awaitBarrier()
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
            onSearch = { _ ->
                awaitBarrier()
                Result.success(FeedPageResult(topics = emptyList()))
            },
        )

        val repo = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = File(tempFolder.root, "seller_trust_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = { baseClockMillis },
            zoneId = shanghaiZone,
        )

        val reportDeferred = async {
            repo.inspectSeller(uid = 1001L, usernameHint = "CloudSeller")
        }

        // Advance so all 4 concurrent coroutines hit the barrier; sequential execution would deadlock here
        advanceUntilIdle()
        assertTrue("Expected all 4 remote calls to start concurrently", allFourStarted.isCompleted)
        releaseGate.complete(Unit)

        val report = reportDeferred.await()
        assertEquals(1001L, report.uid)
        assertEquals("CloudSeller", report.username)
        assertEquals(4, report.rankLevel)
        assertEquals(1750, report.coins)
        assertEquals(2, report.historicalTradeTopicCount)
        assertEquals(2, report.recentTradeTopics.size)
        assertTrue(report.searchSucceeded)
        assertTrue(report.exposureSearchHits.isEmpty())
        assertEquals(TrustRiskLevel.NORMAL_CLEAR, report.riskAssessment.level)
        assertFalse(report.isFromCache)
    }

    @Test
    fun inspectSeller_handlesSearchRateLimitGracefullyWhileProfileSucceeds() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val userRepo = FakeUserRepository(
            onGetProfile = { uid ->
                Result.success(sampleProfile(uid = uid, username = "VeteranUser", coins = 2600, joinedAtIso = "2023-05-01T00:00:00Z"))
            },
            onGetTopics = { uid, _ ->
                Result.success(
                    listOf(
                        sampleTopic(id = 21L, title = "[出] 搬瓦工", categorySlug = "trade", categoryName = "交易", authorId = uid, authorName = "VeteranUser"),
                    ) to false,
                )
            },
        )
        val forumRepo = FakeForumRepository(
            onSearch = {
                Result.failure(NodeSeekApiException(message = "Too Many Requests", statusCode = 429))
            },
        )

        val repo = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = File(tempFolder.root, "seller_trust_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = { baseClockMillis },
            zoneId = shanghaiZone,
        )

        val report = repo.inspectSeller(uid = 2002L, usernameHint = "VeteranUser")
        assertTrue(report.profileSucceeded)
        assertTrue(report.topicsSucceeded)
        assertFalse(report.searchSucceeded)
        assertNotNull(report.searchFailureReason)
        assertTrue(report.searchFailureReason!!.contains("限流"))
        assertEquals(5, report.rankLevel)
        assertEquals(1, report.historicalTradeTopicCount)
        assertEquals(TrustRiskLevel.CAUTION_AMBER, report.riskAssessment.level)
    }

    @Test
    fun inspectSeller_partialSearchFailureStillSurfacesExposureHitFromSucceededQuery() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val userRepo = FakeUserRepository(
            onGetProfile = { uid ->
                Result.success(sampleProfile(uid = uid, username = "ShadyGuy", coins = 950, joinedAtIso = "2024-01-10T00:00:00Z"))
            },
            onGetTopics = { _, _ -> Result.success(emptyList<TopicSummary>() to false) },
        )
        val forumRepo = FakeForumRepository(
            onSearch = { query ->
                if (query.startsWith("曝光")) {
                    Result.failure(TopicAccessException.SignInRequired)
                } else {
                    Result.success(
                        FeedPageResult(
                            topics = listOf(
                                sampleTopic(
                                    id = 909L,
                                    title = "骗子 ShadyGuy 收钱拉黑跑路",
                                    categorySlug = "expose",
                                    categoryName = "曝光",
                                    authorId = 888L,
                                    authorName = "victim1",
                                ),
                            ),
                        ),
                    )
                }
            },
        )

        val repo = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = File(tempFolder.root, "seller_trust_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = { baseClockMillis },
            zoneId = shanghaiZone,
        )

        val report = repo.inspectSeller(uid = 3003L, usernameHint = "ShadyGuy")
        assertFalse(report.searchSucceeded)
        assertEquals(1, report.exposureSearchHits.size)
        assertEquals(909L, report.exposureSearchHits.single().topicId)
        assertEquals(TrustRiskEvaluator.TERM_SCAMMER, report.exposureSearchHits.single().matchedTerm)
        assertEquals(TrustRiskLevel.HIGH_RISK_RED, report.riskAssessment.level)
    }

    @Test
    fun inspectSeller_deduplicatesExposureHitsAcrossQueriesAndFiltersNoise() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
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
            onSearch = {
                Result.success(FeedPageResult(topics = listOf(sharedExposure, noiseSubstring)))
            },
        )

        val repo = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = File(tempFolder.root, "seller_trust_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = { baseClockMillis },
            zoneId = shanghaiZone,
        )

        val report = repo.inspectSeller(uid = 4004L, usernameHint = "fox")
        assertEquals(1, report.exposureSearchHits.size)
        assertEquals(500L, report.exposureSearchHits.single().topicId)
        assertEquals(TrustRiskLevel.HIGH_RISK_RED, report.riskAssessment.level)
    }

    @Test
    fun inspectSeller_cachesFor5Minutes_andForceRefreshBypassesCache() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var nowMillis = baseClockMillis
        val profileCalls = AtomicInteger(0)

        val userRepo = FakeUserRepository(
            onGetProfile = { uid ->
                profileCalls.incrementAndGet()
                Result.success(sampleProfile(uid = uid, username = "CachedSeller", coins = 1600, joinedAtIso = "2024-06-01T00:00:00Z"))
            },
            onGetTopics = { _, _ -> Result.success(emptyList<TopicSummary>() to false) },
        )
        val forumRepo = FakeForumRepository(
            onSearch = { Result.success(FeedPageResult(topics = emptyList())) },
        )

        val repo = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = File(tempFolder.root, "seller_trust_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = { nowMillis },
            zoneId = shanghaiZone,
        )

        val first = repo.inspectSeller(uid = 5005L, usernameHint = "CachedSeller")
        assertFalse(first.isFromCache)
        assertEquals(1, profileCalls.get())

        // 2 minutes later -> served from cache
        nowMillis += 2L * 60L * 1000L
        val second = repo.inspectSeller(uid = 5005L, usernameHint = "CachedSeller")
        assertTrue(second.isFromCache)
        assertEquals(1, profileCalls.get())

        // forceRefresh -> bypasses cache
        val forced = repo.inspectSeller(uid = 5005L, usernameHint = "CachedSeller", forceRefresh = true)
        assertFalse(forced.isFromCache)
        assertEquals(2, profileCalls.get())

        // 6 minutes later -> TTL expired -> refetches
        nowMillis += 6L * 60L * 1000L
        val expired = repo.inspectSeller(uid = 5005L, usernameHint = "CachedSeller")
        assertFalse(expired.isFromCache)
        assertEquals(3, profileCalls.get())
    }

    @Test
    fun localSellerNotes_persistAcrossRepositoryInstances_andReEvaluateCachedRisk() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var nowMillis = baseClockMillis
        val notesFile = File(tempFolder.root, "seller_trust_notes.json")

        val userRepo = FakeUserRepository(
            onGetProfile = { uid ->
                Result.success(sampleProfile(uid = uid, username = "NoteTarget", coins = 1600, joinedAtIso = "2024-05-01T00:00:00Z"))
            },
            onGetTopics = { _, _ -> Result.success(emptyList<TopicSummary>() to false) },
        )
        val forumRepo = FakeForumRepository(
            onSearch = { Result.success(FeedPageResult(topics = emptyList())) },
        )

        val repo1 = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = notesFile,
            ioDispatcher = dispatcher,
            clockMillis = { nowMillis },
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
        nowMillis += 5_000L
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
            clockMillis = { nowMillis },
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
    fun inspectSeller_resolvesCanonicalUsernameWhenHintIsPlaceholder() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val searchedQueries = mutableListOf<String>()

        val userRepo = FakeUserRepository(
            onGetProfile = { uid ->
                Result.success(sampleProfile(uid = uid, username = "RealSellerName", coins = 900, joinedAtIso = "2025-01-01T00:00:00Z"))
            },
            onGetTopics = { _, _ -> Result.success(emptyList<TopicSummary>() to false) },
        )
        val forumRepo = FakeForumRepository(
            onSearch = { query ->
                searchedQueries += query
                Result.success(FeedPageResult(topics = emptyList()))
            },
        )

        val repo = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = File(tempFolder.root, "seller_trust_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = { baseClockMillis },
            zoneId = shanghaiZone,
        )

        val report = repo.inspectSeller(uid = 7007L, usernameHint = "用户 #7007")
        assertEquals("RealSellerName", report.username)
        assertEquals(setOf("曝光 RealSellerName", "骗子 RealSellerName"), searchedQueries.toSet())
    }

    @Test
    fun stateHolder_updatesTagAndDebouncedNoteAndReflectsLiveRiskReEvaluation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val notesFile = File(tempFolder.root, "state_holder_notes.json")

        val userRepo = FakeUserRepository(
            onGetProfile = { uid ->
                Result.success(sampleProfile(uid = uid, username = "LiveSeller", coins = 1600, joinedAtIso = "2024-03-01T00:00:00Z"))
            },
            onGetTopics = { _, _ -> Result.success(emptyList<TopicSummary>() to false) },
        )
        val forumRepo = FakeForumRepository(
            onSearch = { Result.success(FeedPageResult(topics = emptyList())) },
        )

        val repo = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = notesFile,
            ioDispatcher = dispatcher,
            clockMillis = { baseClockMillis },
            zoneId = shanghaiZone,
        )

        val holderScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val holder = SellerTrustStateHolder(
                repository = repo,
                scope = holderScope,
                uid = 8008L,
                usernameHint = "LiveSeller",
            )
            holder.load()
            advanceUntilIdle()

            assertNotNull(holder.uiState.value.report)
            assertEquals(TrustRiskLevel.NORMAL_CLEAR, holder.uiState.value.report?.riskAssessment?.level)

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
            onSearch = { query ->
                if (query.startsWith("曝光")) {
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
                } else {
                    Result.success(FeedPageResult(topics = emptyList()))
                }
            },
        )

        val repo = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = File(tempFolder.root, "profile_fail_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = { baseClockMillis },
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
    fun inspectSeller_totalRemoteFailureIsNotCached_andStillHonorsLocalBlacklist() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val profileCalls = AtomicInteger(0)
        val userRepo = FakeUserRepository(
            onGetProfile = {
                profileCalls.incrementAndGet()
                Result.failure(RuntimeException("Offline"))
            },
            onGetTopics = { _, _ -> Result.failure(RuntimeException("Offline")) },
        )
        val forumRepo = FakeForumRepository(
            onSearch = { Result.failure(RuntimeException("Offline")) },
        )

        val repo = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = File(tempFolder.root, "offline_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = { baseClockMillis },
            zoneId = shanghaiZone,
        )

        repo.saveLocalNote(uid = 9002L, username = "OfflineBad", tag = TrustTag.RISK_BLACKLIST, noteText = "线下拉黑")

        val first = repo.inspectSeller(uid = 9002L, usernameHint = "OfflineBad")
        assertFalse(first.profileSucceeded)
        assertFalse(first.searchSucceeded)
        assertEquals(TrustRiskLevel.HIGH_RISK_RED, first.riskAssessment.level)
        assertEquals(1, profileCalls.get())

        // Second call must retry because total failure is not cached
        val second = repo.inspectSeller(uid = 9002L, usernameHint = "OfflineBad")
        assertFalse(second.isFromCache)
        assertEquals(2, profileCalls.get())
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
            onSearch = { Result.success(FeedPageResult(topics = emptyList())) },
        )

        val repo = SellerTrustRepositoryImpl(
            userRepository = userRepo,
            forumRepository = forumRepo,
            notesFile = File(tempFolder.root, "uid_resolve_notes.json"),
            ioDispatcher = dispatcher,
            clockMillis = { baseClockMillis },
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
    ): UserProfile = UserProfile(
        uid = uid,
        username = username,
        avatarUrl = "https://www.nodeseek.com/avatar/$uid.png",
        joinedAtIso = joinedAtIso,
        drumsticks = coins,
        stardust = 120,
        topicCount = 15,
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
        private val onSearch: suspend (String) -> Result<FeedPageResult>,
    ) : ForumRepository {
        override fun observeCategories(): Flow<List<Category>> = emptyFlow()
        override suspend fun refreshCategories(force: Boolean): Result<List<Category>> = Result.success(emptyList())
        override suspend fun getFeed(categorySlug: String, sort: FeedSort, page: Int, includeBlocked: Boolean, forceRefresh: Boolean): Result<FeedPageResult> =
            Result.success(FeedPageResult(topics = emptyList()))
        override suspend fun getCuratedFeed(page: Int, forceRefresh: Boolean): Result<FeedPageResult> =
            Result.success(FeedPageResult(topics = emptyList()))
        override suspend fun searchTopics(query: String, categorySlug: String, sort: FeedSort, page: Int): Result<FeedPageResult> =
            onSearch(query)
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
