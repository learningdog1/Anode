package com.nodeseek.app.ui.topic

import com.nodeseek.app.data.repository.FakeSettingsRepository
import com.nodeseek.app.di.AppContainer
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.BookmarkedTopic
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.EditorDraft
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.FloorComment
import com.nodeseek.app.domain.model.ReactionResult
import com.nodeseek.app.domain.model.ReactionType
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.model.UserProfile
import com.nodeseek.app.domain.model.UserReplyExcerpt
import com.nodeseek.app.domain.repository.BookmarkAndHistoryRepository
import com.nodeseek.app.domain.repository.DraftRepository
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SignInStepResult
import com.nodeseek.app.domain.repository.UserRepository
import com.nodeseek.app.domain.trade.TradePrivacyOptions
import com.nodeseek.app.domain.valuation.MarketDataSource
import com.nodeseek.app.domain.valuation.MarketSearchStatus
import com.nodeseek.app.domain.valuation.TradeMarketPremiumAnalyzer
import com.nodeseek.app.domain.valuation.TradeCandidateTopic
import com.nodeseek.app.domain.valuation.TradeMarketReferenceRepository
import com.nodeseek.app.domain.valuation.TradeMarketReferenceState
import com.nodeseek.app.ui.valuation.extractMarketQueryWithContext
import com.nodeseek.app.ui.valuation.sanitizeMarketStatusMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class TradeAndCarpoolTopicDetailViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private data class RecordedTopicEdit(
        val topicId: Long,
        val title: String,
        val markdownContent: String,
        val requiredViewLevel: Int,
    )

    private fun buildContainer(
        forum: ForumRepository,
        session: SessionRepository,
        bookmarks: BookmarkAndHistoryRepository = FakeBookmarkRepo(),
        drafts: DraftRepository = InMemoryDraftRepo(),
        users: UserRepository = FakeUserRepo(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
    ): AppContainer = Proxy.newProxyInstance(
        AppContainer::class.java.classLoader,
        arrayOf(AppContainer::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getForumRepository" -> forum
            "getSessionRepository" -> session
            "getBookmarkAndHistoryRepository" -> bookmarks
            "getDraftRepository" -> drafts
            "getUserRepository" -> users
            "getSettingsRepository" -> settings
            "getRadarRepository" -> null
            else -> throw UnsupportedOperationException("Unexpected AppContainer call: ${method.name}")
        }
    } as AppContainer

    private fun makePage(
        topicId: Long = 500L,
        pageNum: Int = 1,
        totalPages: Int = 2,
        categorySlug: String = "trade",
        categoryName: String = "交易",
        title: String = "[出] DMIT LAX.Pro.Tiny 溢价 +80",
        opMarkdown: String = "测试IP: 203.0.113.42\n密码: pass1234\n邮箱: seller@example.com",
        requiredViewLevel: Int = 3,
        opUid: Long = 10L,
        includeOpening: Boolean = pageNum == 1,
    ): TopicDetailPage {
        val opAuthor = FloorAuthor(uid = opUid, username = "op_seller", isTopicAuthor = true)
        val opening = if (includeOpening) {
            FloorComment(
                commentId = 9000L,
                floorNumber = 0,
                author = opAuthor,
                createdAtIso = "2026-11-25T00:00:00Z",
                markdownBody = opMarkdown,
            )
        } else {
            null
        }
        val reply = FloorComment(
            commentId = 9100L + pageNum,
            floorNumber = (pageNum - 1) * 10 + 5,
            author = FloorAuthor(uid = 88L, username = "buyer_88"),
            createdAtIso = "2026-11-25T00:10:00Z",
            markdownBody = "Reply on page $pageNum",
        )
        return TopicDetailPage(
            topicId = topicId,
            title = title,
            categorySlug = categorySlug,
            categoryName = categoryName,
            author = opAuthor,
            currentPage = pageNum,
            totalPages = totalPages,
            requiredViewLevel = requiredViewLevel,
            openingFloor = opening,
            replies = listOf(reply),
        )
    }

    @Test
    fun `closeTradeTopic prepends sold prefix masks sensitive info and preserves requiredViewLevel`() = runTest {
        val session = FakeSessionRepo(AccountSession(uid = 10L, username = "op_seller"))
        var currentTitle = "[出] DMIT LAX.Pro.Tiny 溢价 +80"
        var currentBody = "测试IP: 203.0.113.42\n密码: pass1234\n邮箱: seller@example.com"
        val forum = FakeForumRepo(
            detailHandler = { _, p ->
                Result.success(
                    makePage(
                        pageNum = p,
                        title = currentTitle,
                        opMarkdown = currentBody,
                        requiredViewLevel = 4,
                    ),
                )
            },
            editHandler = { edit ->
                currentTitle = edit.title
                currentBody = edit.markdownContent
            },
        )
        val vm = TopicDetailViewModel(
            container = buildContainer(forum, session),
            topicId = 500L,
        )
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }
        vm.uiState.first { it.pageData != null && it.openingFloor != null }

        vm.closeTradeTopic(TradePrivacyOptions())

        assertEquals(1, forum.edits.size)
        val edit = forum.edits.single()
        assertEquals("[已出] [出] DMIT LAX.Pro.Tiny 溢价 +80", edit.title)
        assertEquals(4, edit.requiredViewLevel)
        assertTrue(edit.markdownContent.contains("203.0.*.*"))
        assertFalse(edit.markdownContent.contains("203.0.113.42"))
        assertFalse(edit.markdownContent.contains("pass1234"))
        assertTrue(edit.markdownContent.contains("s***@example.com"))

        assertTrue(vm.uiState.value.pageData!!.title.startsWith("[已出]"))
        assertFalse(vm.uiState.value.isMutatingTopic)
        collectJob.cancel()
    }

    @Test
    fun `closeTradeTopic and updateCarpoolStatus on page 2+ fetch Page 1 before editing and preserve rank`() = runTest {
        val session = FakeSessionRepo(AccountSession(uid = 10L, username = "op_seller"))
        var page1Requested = false
        var failPage1Initially = true
        var storedTitle = "Netflix 4K 合租 [3=1]"
        var storedBody = "**当前状态**：`3=1`（共 4 车位，已上车 3，空余 1）"

        val forum = FakeForumRepo(
            detailHandler = { _, p ->
                if (p == 1) {
                    page1Requested = true
                    if (failPage1Initially) {
                        Result.failure(IllegalStateException("initial prefetch skipped"))
                    } else {
                        Result.success(
                            makePage(
                                pageNum = 1,
                                totalPages = 2,
                                categorySlug = "carpool",
                                categoryName = "拼车",
                                title = storedTitle,
                                opMarkdown = storedBody,
                                requiredViewLevel = 5,
                            ),
                        )
                    }
                } else {
                    Result.success(
                        makePage(
                            pageNum = 2,
                            totalPages = 2,
                            categorySlug = "carpool",
                            categoryName = "拼车",
                            title = storedTitle,
                            opMarkdown = storedBody,
                            requiredViewLevel = 0, // page 2 might not carry authoritative rank until page 1!
                            includeOpening = false,
                        ),
                    )
                }
            },
            editHandler = { edit ->
                storedTitle = edit.title
                storedBody = edit.markdownContent
            },
        )

        val vm = TopicDetailViewModel(
            container = buildContainer(forum, session),
            topicId = 500L,
            initialFloor = 15, // starts on page 2!
        )
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }
        vm.uiState.first { it.pageData != null }

        // Page 1 initial prefetch failed -> openingFloor is still null on Page 2
        assertNull(vm.uiState.value.openingFloor)

        // Now allow Page 1 fetch inside ensurePageOneForOpEdit()
        failPage1Initially = false
        vm.updateCarpoolStatus(
            statusToken = "已满车",
            occupiedSeats = 4,
            totalSeats = 4,
            updateBody = true,
        )

        assertTrue("Expected Page 1 to be fetched before edit", page1Requested)
        assertEquals(1, forum.edits.size)
        val edit = forum.edits.single()
        assertEquals("Netflix 4K 合租 [已满车]", edit.title)
        assertTrue(edit.markdownContent.contains("`已满车`"))
        assertEquals(5, edit.requiredViewLevel)
        assertEquals("Netflix 4K 合租 [已满车]", vm.uiState.value.pageData?.title)
        assertNotNull(vm.uiState.value.openingFloor)
        assertTrue(vm.uiState.value.openingFloor!!.markdownBody.contains("`已满车`"))
        collectJob.cancel()
    }

    @Test
    fun `non-OP and unauthenticated users cannot mutate trade or carpool topics`() = runTest {
        val session = FakeSessionRepo(null)
        val forum = FakeForumRepo(
            detailHandler = { _, p -> Result.success(makePage(pageNum = p, opUid = 10L)) },
        )
        val vm = TopicDetailViewModel(
            container = buildContainer(forum, session),
            topicId = 500L,
        )
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }
        vm.uiState.first { it.pageData != null }

        // Signed-out caller
        vm.closeTradeTopic()
        assertEquals(0, forum.edits.size)

        // Different signed-in user (UID 99 != OP 10)
        session.sessionState.value = AccountSession(uid = 99L, username = "other_user")
        vm.closeTradeTopic()
        vm.updateCarpoolStatus("已满车")
        assertEquals(0, forum.edits.size)
        collectJob.cancel()
    }

    @Test
    fun `TopicDetailViewModel auto-analyzes local market reference for trade topics with zero network`() = runTest {
        val session = FakeSessionRepo(AccountSession(uid = 10L, username = "viewer"))
        var localCalls = 0
        var remoteCalls = 0
        val fakeMarketRepo = object : TradeMarketReferenceRepository {
            override suspend fun analyzeLocal(query: String, includeBuyRequests: Boolean): TradeMarketReferenceState {
                localCalls++
                val dist = TradeMarketPremiumAnalyzer.analyze(
                    query = query,
                    candidates = listOf(
                        TradeCandidateTopic(1L, "[出] DMIT LAX.Pro.Tiny 溢价 +60", MarketDataSource.FEED_CACHE),
                    ),
                )
                return TradeMarketReferenceState(query = query, distribution = dist)
            }

            override suspend fun searchRemoteAndMerge(query: String, includeBuyRequests: Boolean): TradeMarketReferenceState {
                remoteCalls++
                val dist = TradeMarketPremiumAnalyzer.analyze(
                    query = query,
                    candidates = listOf(
                        TradeCandidateTopic(1L, "[出] DMIT LAX.Pro.Tiny 溢价 +60", MarketDataSource.FEED_CACHE),
                        TradeCandidateTopic(2L, "[出] DMIT LAX.Pro.Tiny 溢价 +100", MarketDataSource.REMOTE_SEARCH),
                    ),
                )
                return TradeMarketReferenceState(
                    query = query,
                    distribution = dist,
                    searchStatus = MarketSearchStatus.SUCCESS,
                    hasRemoteResults = true,
                )
            }
        }

        val forum = FakeForumRepo(
            detailHandler = { _, p ->
                Result.success(
                    makePage(
                        pageNum = p,
                        categorySlug = "trade",
                        categoryName = "交易",
                        title = "[出] DMIT LAX.Pro.Tiny 1C1G 溢价+80",
                    ),
                )
            },
        )
        val vm = TopicDetailViewModel(
            container = buildContainer(forum, session),
            topicId = 500L,
            tradeMarketReferenceRepository = fakeMarketRepo,
        )
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }
        val loaded = vm.uiState.first { it.marketReference != null }

        assertEquals(1, localCalls)
        assertEquals(0, remoteCalls)
        assertEquals(BigDecimal("60.00"), loaded.marketReference?.distribution?.medianCny)

        // Explicit user-triggered remote search merges remote samples
        vm.searchTradeMarketReference()
        val afterSearch = vm.uiState.first { it.marketReference?.hasRemoteResults == true }
        assertEquals(1, remoteCalls)
        assertEquals(2, afterSearch.marketReference?.distribution?.sampleCount)
        assertEquals(BigDecimal("80.00"), afterSearch.marketReference?.distribution?.medianCny)
        collectJob.cancel()
    }

    @Test
    fun `TopicDetailViewModel skips market reference analysis on non-trade topics`() = runTest {
        val session = FakeSessionRepo(AccountSession(uid = 10L, username = "viewer"))
        var localCalls = 0
        val fakeMarketRepo = object : TradeMarketReferenceRepository {
            override suspend fun analyzeLocal(query: String, includeBuyRequests: Boolean): TradeMarketReferenceState {
                localCalls++
                return TradeMarketReferenceState(query = query)
            }

            override suspend fun searchRemoteAndMerge(query: String, includeBuyRequests: Boolean): TradeMarketReferenceState =
                TradeMarketReferenceState(query = query)
        }
        val forum = FakeForumRepo(
            detailHandler = { _, p ->
                Result.success(makePage(pageNum = p, categorySlug = "tech", categoryName = "技术", title = "DMIT Pro 测评"))
            },
        )
        val vm = TopicDetailViewModel(
            container = buildContainer(forum, session),
            topicId = 501L,
            tradeMarketReferenceRepository = fakeMarketRepo,
        )
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }
        vm.uiState.first { it.pageData != null }

        assertEquals(0, localCalls)
        assertNull(vm.uiState.value.marketReference)
        collectJob.cancel()
    }

    @Test
    fun `TopicDetailViewModel auto-triggers market reference when opened without trade board metadata`() = runTest {
        val session = FakeSessionRepo(AccountSession(uid = 10L, username = "viewer"))
        val recordedQueries = mutableListOf<String>()
        val fakeMarketRepo = object : TradeMarketReferenceRepository {
            override suspend fun analyzeLocal(query: String, includeBuyRequests: Boolean): TradeMarketReferenceState {
                recordedQueries += query
                val dist = TradeMarketPremiumAnalyzer.analyze(
                    query = query,
                    candidates = listOf(
                        TradeCandidateTopic(201L, "[出] 搬瓦工 MEGABOX-PRO 溢价 +120", MarketDataSource.FEED_CACHE),
                    ),
                )
                return TradeMarketReferenceState(query = query, distribution = dist)
            }

            override suspend fun searchRemoteAndMerge(query: String, includeBuyRequests: Boolean): TradeMarketReferenceState =
                TradeMarketReferenceState(query = query, searchStatus = MarketSearchStatus.SUCCESS, hasRemoteResults = true)
        }

        // Case 1: Blank categorySlug/categoryName (e.g., deep link / search / history)
        val blankCategoryForum = FakeForumRepo(
            detailHandler = { _, p ->
                Result.success(
                    makePage(
                        topicId = 502L,
                        pageNum = p,
                        categorySlug = "",
                        categoryName = "",
                        title = "[出] 搬瓦工 MEGABOX-PRO 剩余价值+100",
                    ),
                )
            },
        )
        val blankVm = TopicDetailViewModel(
            container = buildContainer(blankCategoryForum, session),
            topicId = 502L,
            tradeMarketReferenceRepository = fakeMarketRepo,
        )
        val blankJob = launch(testDispatcher) { blankVm.uiState.collect {} }
        val loadedBlank = blankVm.uiState.first { it.marketReference != null }
        assertTrue("Expected query extracted without trade board key", loadedBlank.marketReference!!.query.isNotBlank())
        assertEquals(BigDecimal("120.00"), loadedBlank.marketReference?.distribution?.medianCny)
        blankJob.cancel()

        // Case 2: "all" / "全部" feed context
        val allBoardForum = FakeForumRepo(
            detailHandler = { _, p ->
                Result.success(
                    makePage(
                        topicId = 503L,
                        pageNum = p,
                        categorySlug = "all",
                        categoryName = "全部",
                        title = "明盘出 搬瓦工 MEGABOX-PRO 溢价 +90",
                    ),
                )
            },
        )
        val allVm = TopicDetailViewModel(
            container = buildContainer(allBoardForum, session),
            topicId = 503L,
            tradeMarketReferenceRepository = fakeMarketRepo,
        )
        val allJob = launch(testDispatcher) { allVm.uiState.collect {} }
        val loadedAll = allVm.uiState.first { it.marketReference != null }
        assertEquals(BigDecimal("120.00"), loadedAll.marketReference?.distribution?.medianCny)
        assertEquals(2, recordedQueries.size)
        allJob.cancel()

        // Case 3: Exposure post without board metadata MUST still be skipped
        val exposeForum = FakeForumRepo(
            detailHandler = { _, p ->
                Result.success(
                    makePage(
                        topicId = 504L,
                        pageNum = p,
                        categorySlug = "",
                        categoryName = "",
                        title = "[曝光] 骗子出 搬瓦工 MEGABOX-PRO 剩余价值+100 跑路",
                    ),
                )
            },
        )
        val exposeVm = TopicDetailViewModel(
            container = buildContainer(exposeForum, session),
            topicId = 504L,
            tradeMarketReferenceRepository = fakeMarketRepo,
        )
        val exposeJob = launch(testDispatcher) { exposeVm.uiState.collect {} }
        exposeVm.uiState.first { it.pageData != null }
        assertNull(exposeVm.uiState.value.marketReference)
        assertEquals(2, recordedQueries.size)
        exposeJob.cancel()
    }

    @Test
    fun `TopicDetailViewModel auto-triggers remote search when local cache has zero peer samples and preserves result across refresh`() = runTest {
        val session = FakeSessionRepo(AccountSession(uid = 10L, username = "viewer"))
        var localCalls = 0
        var remoteCalls = 0
        val fakeMarketRepo = object : TradeMarketReferenceRepository {
            override suspend fun analyzeLocal(query: String, includeBuyRequests: Boolean): TradeMarketReferenceState {
                localCalls++
                // Only self-topic 510 in local cache (0 peer samples) -> must still trigger remote search!
                val dist = TradeMarketPremiumAnalyzer.analyze(
                    query = query,
                    candidates = listOf(
                        TradeCandidateTopic(510L, "[出] DMIT LAX.Pro.WEE 溢价 +80", MarketDataSource.READ_HISTORY),
                    ),
                )
                return TradeMarketReferenceState(query = query, distribution = dist)
            }

            override suspend fun searchRemoteAndMerge(query: String, includeBuyRequests: Boolean): TradeMarketReferenceState {
                remoteCalls++
                val dist = TradeMarketPremiumAnalyzer.analyze(
                    query = query,
                    candidates = listOf(
                        TradeCandidateTopic(510L, "[出] DMIT LAX.Pro.WEE 溢价 +80", MarketDataSource.READ_HISTORY),
                        TradeCandidateTopic(801L, "[出] DMIT LAX.Pro.WEE 溢价 +60", MarketDataSource.REMOTE_SEARCH),
                        TradeCandidateTopic(802L, "[出] DMIT LAX.Pro.WEE 溢价 +100", MarketDataSource.REMOTE_SEARCH),
                    ),
                )
                return TradeMarketReferenceState(
                    query = query,
                    distribution = dist,
                    searchStatus = MarketSearchStatus.SUCCESS,
                    hasRemoteResults = true,
                )
            }
        }

        val forum = FakeForumRepo(
            detailHandler = { _, p ->
                Result.success(
                    makePage(
                        topicId = 510L,
                        pageNum = p,
                        categorySlug = "trade",
                        categoryName = "交易",
                        title = "[出] DMIT LAX.Pro.WEE 溢价 +80",
                    ),
                )
            },
        )
        val vm = TopicDetailViewModel(
            container = buildContainer(forum, session),
            topicId = 510L,
            tradeMarketReferenceRepository = fakeMarketRepo,
        )
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }
        val loaded = vm.uiState.first { it.marketReference?.hasRemoteResults == true }

        assertEquals(1, localCalls)
        assertEquals(1, remoteCalls)
        assertEquals(3, loaded.marketReference?.distribution?.sampleCount)
        assertEquals(BigDecimal("80.00"), loaded.marketReference?.distribution?.medianCny)

        // Refreshing the current page must NOT clobber completed remote results or spam remote search
        vm.refreshCurrentPage()
        assertEquals(1, remoteCalls)
        assertTrue(vm.uiState.value.marketReference?.hasRemoteResults == true)
        assertEquals(3, vm.uiState.value.marketReference?.distribution?.sampleCount)
        collectJob.cancel()
    }

    @Test
    fun `TopicDetailViewModel extracts query from OP body when title is vague and Page 1 arrives after Page 2`() = runTest {
        val session = FakeSessionRepo(AccountSession(uid = 10L, username = "viewer"))
        val page1Gate = CompletableDeferred<Unit>()
        val analyzedQueries = mutableListOf<String>()

        val fakeMarketRepo = object : TradeMarketReferenceRepository {
            override suspend fun analyzeLocal(query: String, includeBuyRequests: Boolean): TradeMarketReferenceState {
                analyzedQueries += query
                val dist = TradeMarketPremiumAnalyzer.analyze(
                    query = query,
                    candidates = listOf(
                        TradeCandidateTopic(901L, "[出] DMIT LAX.Pro.WEE 溢价 +70", MarketDataSource.FEED_CACHE),
                    ),
                )
                return TradeMarketReferenceState(query = query, distribution = dist)
            }

            override suspend fun searchRemoteAndMerge(query: String, includeBuyRequests: Boolean): TradeMarketReferenceState =
                analyzeLocal(query, includeBuyRequests).copy(
                    searchStatus = MarketSearchStatus.SUCCESS,
                    hasRemoteResults = true,
                )
        }

        val forum = FakeForumRepo(
            detailHandler = { _, p ->
                if (p == 1) {
                    page1Gate.await()
                    Result.success(
                        makePage(
                            topicId = 520L,
                            pageNum = 1,
                            totalPages = 2,
                            categorySlug = "trade",
                            categoryName = "交易",
                            title = "[出] 传家宝 剩余价值+50",
                            opMarkdown = "| 套餐 / 机型 | DMIT LAX.Pro.WEE | 续费 \$36.9/yr |\n测试IP: 203.0.113.10",
                        ),
                    )
                } else {
                    Result.success(
                        makePage(
                            topicId = 520L,
                            pageNum = 2,
                            totalPages = 2,
                            categorySlug = "trade",
                            categoryName = "交易",
                            title = "[出] 传家宝 剩余价值+50",
                            includeOpening = false,
                        ),
                    )
                }
            },
        )

        val vm = TopicDetailViewModel(
            container = buildContainer(forum, session),
            topicId = 520L,
            initialFloor = 15, // starts on Page 2 without OP floor
            tradeMarketReferenceRepository = fakeMarketRepo,
        )
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }
        vm.uiState.first { it.pageData != null }

        // Even before Page 1 arrives, trade card state is present (never hidden/null)
        assertNotNull(vm.uiState.value.marketReference)

        // Release Page 1 prefetch with OP floor containing DMIT LAX.Pro.WEE
        page1Gate.complete(Unit)
        val afterOpBody = vm.uiState.first {
            val q = it.marketReference?.query.orEmpty()
            q.contains("DMIT", ignoreCase = true) && q.contains("WEE", ignoreCase = true)
        }
        assertEquals(BigDecimal("70.00"), afterOpBody.marketReference?.distribution?.medianCny)
        assertTrue(analyzedQueries.any { it.contains("DMIT", ignoreCase = true) })
        collectJob.cancel()
    }

    @Test
    fun `TopicDetailViewModel preserves user custom query across refresh and sanitizes raw 404 messages`() = runTest {
        val session = FakeSessionRepo(AccountSession(uid = 10L, username = "viewer"))
        val remoteQueries = mutableListOf<String>()
        val fakeMarketRepo = object : TradeMarketReferenceRepository {
            override suspend fun analyzeLocal(query: String, includeBuyRequests: Boolean): TradeMarketReferenceState {
                val dist = TradeMarketPremiumAnalyzer.analyze(
                    query = query,
                    candidates = listOf(
                        TradeCandidateTopic(1L, "[出] DMIT LAX.Pro.Tiny 溢价 +60", MarketDataSource.FEED_CACHE),
                    ),
                )
                return TradeMarketReferenceState(query = query, distribution = dist)
            }

            override suspend fun searchRemoteAndMerge(query: String, includeBuyRequests: Boolean): TradeMarketReferenceState {
                remoteQueries += query
                val dist = TradeMarketPremiumAnalyzer.analyze(
                    query = query,
                    candidates = listOf(
                        TradeCandidateTopic(2L, "[出] 搬瓦工 MEGABOX-PRO 溢价 +150", MarketDataSource.REMOTE_SEARCH),
                    ),
                )
                return TradeMarketReferenceState(
                    query = query,
                    distribution = dist,
                    searchStatus = MarketSearchStatus.SUCCESS,
                    hasRemoteResults = true,
                )
            }
        }

        val forum = FakeForumRepo(
            detailHandler = { _, p ->
                Result.success(
                    makePage(
                        topicId = 530L,
                        pageNum = p,
                        categorySlug = "trade",
                        categoryName = "交易",
                        title = "[出] DMIT LAX.Pro.Tiny 溢价 +80",
                    ),
                )
            },
        )
        val vm = TopicDetailViewModel(
            container = buildContainer(forum, session),
            topicId = 530L,
            tradeMarketReferenceRepository = fakeMarketRepo,
        )
        val collectJob = launch(testDispatcher) { vm.uiState.collect {} }
        vm.uiState.first { it.marketReference != null }

        // User edits keyword to a custom model and triggers remote search
        vm.searchTradeMarketReference("瓦工 MEGABOX")
        val afterCustom = vm.uiState.first { it.marketReference?.query == "瓦工 MEGABOX" && it.marketReference?.hasRemoteResults == true }
        assertEquals(BigDecimal("150.00"), afterCustom.marketReference?.distribution?.medianCny)

        // Pulling to refresh must keep user's custom query and results intact
        vm.refreshCurrentPage()
        assertEquals("瓦工 MEGABOX", vm.uiState.value.marketReference?.query)
        assertEquals(listOf("瓦工 MEGABOX"), remoteQueries)

        // Helper checks: body fallback extraction & raw HTTP 404 message sanitization
        val extractedFromBody = extractMarketQueryWithContext(
            title = "[出] 传家宝 剩余价值+50",
            openingMarkdown = "```bash\nyabs benchmark\n```\n机型：搬瓦工 MEGABOX-PRO DC6\nhttps://example.com",
        )
        assertTrue(
            "Expected BWG/MEGABOX extracted from body, got '$extractedFromBody'",
            extractedFromBody.contains("BWG", ignoreCase = true) || extractedFromBody.contains("MEGABOX", ignoreCase = true),
        )

        val sanitized404 = sanitizeMarketStatusMessage(
            TradeMarketReferenceState(
                query = "DMIT Pro",
                searchStatus = MarketSearchStatus.FAILED,
                statusMessage = "HTTP 404 Not Found: /search?q=DMIT",
            ),
        )
        assertNotNull(sanitized404)
        assertFalse("Raw HTTP 404 must not leak to UI", sanitized404!!.contains("404"))
        collectJob.cancel()
    }

    @Test
    fun `closeTradeTopic updateCarpoolStatus and appendLuckyLinkToTopicOp succeed without active uiState collector`() = runTest {
        val session = FakeSessionRepo(AccountSession(uid = 10L, username = "op_seller"))

        // 1. closeTradeTopic without uiState collector
        val tradeForum = FakeForumRepo(
            detailHandler = { _, p -> Result.success(makePage(pageNum = p, categorySlug = "trade", categoryName = "交易")) },
        )
        val tradeVm = TopicDetailViewModel(
            container = buildContainer(tradeForum, session),
            topicId = 601L,
        )
        tradeVm.closeTradeTopic()
        assertEquals(1, tradeForum.edits.size)
        assertTrue(tradeForum.edits.single().title.startsWith("[已出]"))

        // 2. updateCarpoolStatus without uiState collector
        val carpoolForum = FakeForumRepo(
            detailHandler = { _, p ->
                Result.success(
                    makePage(
                        pageNum = p,
                        categorySlug = "carpool",
                        categoryName = "拼车",
                        title = "Netflix 4K [3=1]",
                        opMarkdown = "**当前状态**：`3=1`",
                    ),
                )
            },
        )
        val carpoolVm = TopicDetailViewModel(
            container = buildContainer(carpoolForum, session),
            topicId = 602L,
        )
        carpoolVm.updateCarpoolStatus("已满车", occupiedSeats = 4, totalSeats = 4)
        assertEquals(1, carpoolForum.edits.size)
        assertEquals("Netflix 4K [已满车]", carpoolForum.edits.single().title)

        // 3. appendLuckyLinkToTopicOp without uiState collector
        val luckyForum = FakeForumRepo(
            detailHandler = { _, p ->
                Result.success(
                    makePage(
                        pageNum = p,
                        categorySlug = "daily",
                        categoryName = "日常",
                        title = "抽奖帖",
                        opMarkdown = "欢迎参与",
                    ),
                )
            },
        )
        val luckyVm = TopicDetailViewModel(
            container = buildContainer(luckyForum, session),
            topicId = 603L,
        )
        luckyVm.appendLuckyLinkToTopicOp("[🎲 参与官方抽奖](https://www.nodeseek.com/lucky?id=1)")
        assertEquals(1, luckyForum.edits.size)
        assertTrue(luckyForum.edits.single().markdownContent.contains("https://www.nodeseek.com/lucky?id=1"))
    }

    @Test
    fun `closeTradeTopic and updateCarpoolStatus reset isMutatingTopic on exception or cancellation`() = runTest {
        val stdDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(stdDispatcher)

        val session = FakeSessionRepo(AccountSession(uid = 10L, username = "op_seller"))

        // 1. Trade close throws RuntimeException on first call, then succeeds on retry
        var tradeShouldThrow = true
        val tradeForum = FakeForumRepo(
            detailHandler = { _, p -> Result.success(makePage(pageNum = p, categorySlug = "trade", categoryName = "交易")) },
            editHandler = {
                if (tradeShouldThrow) throw RuntimeException("simulated unexpected error")
            },
        )
        val tradeVm = TopicDetailViewModel(
            container = buildContainer(tradeForum, session),
            topicId = 701L,
        )
        val tradeCollect = launch(stdDispatcher) { tradeVm.uiState.collect {} }
        advanceUntilIdle()

        tradeVm.closeTradeTopic()
        advanceUntilIdle()
        assertFalse("isMutatingTopic must reset after exception", tradeVm.uiState.value.isMutatingTopic)

        tradeShouldThrow = false
        tradeVm.closeTradeTopic()
        advanceUntilIdle()
        assertEquals(2, tradeForum.edits.size)
        assertFalse(tradeVm.uiState.value.isMutatingTopic)
        tradeCollect.cancel()

        // 2. Carpool update throws CancellationException on first call, then succeeds on retry
        var carpoolShouldCancel = true
        val carpoolForum = FakeForumRepo(
            detailHandler = { _, p ->
                Result.success(
                    makePage(
                        pageNum = p,
                        categorySlug = "carpool",
                        categoryName = "拼车",
                        title = "Netflix 4K [3=1]",
                        opMarkdown = "**当前状态**：`3=1`",
                    ),
                )
            },
            editHandler = {
                if (carpoolShouldCancel) throw CancellationException("simulated cancellation")
            },
        )
        val carpoolVm = TopicDetailViewModel(
            container = buildContainer(carpoolForum, session),
            topicId = 702L,
        )
        val carpoolCollect = launch(stdDispatcher) { carpoolVm.uiState.collect {} }
        advanceUntilIdle()

        carpoolVm.updateCarpoolStatus("已满车")
        advanceUntilIdle()
        assertFalse("isMutatingTopic must reset after cancellation", carpoolVm.uiState.value.isMutatingTopic)

        carpoolShouldCancel = false
        carpoolVm.updateCarpoolStatus("已满车")
        advanceUntilIdle()
        assertEquals(2, carpoolForum.edits.size)
        assertFalse(carpoolVm.uiState.value.isMutatingTopic)
        carpoolCollect.cancel()
    }

    private class FakeForumRepo(
        private val detailHandler: suspend (Long, Int) -> Result<TopicDetailPage>,
        private val editHandler: (RecordedTopicEdit) -> Unit = {},
    ) : ForumRepository {
        val edits = mutableListOf<RecordedTopicEdit>()
        override fun observeCategories(): Flow<List<Category>> = flowOf(Category.DEFAULT_CATEGORIES)
        override suspend fun refreshCategories(force: Boolean): Result<List<Category>> =
            Result.success(Category.DEFAULT_CATEGORIES)

        override suspend fun getFeed(
            categorySlug: String,
            sort: FeedSort,
            page: Int,
            includeBlocked: Boolean,
            forceRefresh: Boolean,
        ): Result<FeedPageResult> = Result.success(FeedPageResult(topics = emptyList()))

        override suspend fun getCuratedFeed(page: Int, forceRefresh: Boolean): Result<FeedPageResult> =
            Result.success(FeedPageResult(topics = emptyList()))

        override suspend fun searchTopics(
            query: String,
            categorySlug: String,
            sort: FeedSort,
            page: Int,
        ): Result<FeedPageResult> = Result.success(FeedPageResult(topics = emptyList()))

        override suspend fun getTopicDetail(topicId: Long, page: Int, includeBlocked: Boolean): Result<TopicDetailPage> =
            detailHandler(topicId, page)

        override suspend fun createTopic(
            categorySlug: String,
            title: String,
            markdownContent: String,
            requiredViewLevel: Int,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun editTopic(
            topicId: Long,
            title: String,
            markdownContent: String,
            requiredViewLevel: Int,
        ): Result<Unit> {
            val rec = RecordedTopicEdit(topicId, title, markdownContent, requiredViewLevel)
            edits += rec
            editHandler(rec)
            return Result.success(Unit)
        }

        override suspend fun postReply(topicId: Long, markdownContent: String): Result<Unit> = Result.success(Unit)
        override suspend fun editReply(commentId: Long, markdownContent: String): Result<Unit> = Result.success(Unit)
        override suspend fun setReplyPinned(commentId: Long, pinned: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun getFreeDrumstickCost(): Result<Int> = Result.success(0)
        override suspend fun reactToComment(topicId: Long, commentId: Long, reaction: ReactionType): Result<ReactionResult> =
            Result.failure(UnsupportedOperationException())
    }

    private class FakeSessionRepo(initial: AccountSession?) : SessionRepository {
        override val sessionState = MutableStateFlow(initial)
        override val challengeRequired = MutableStateFlow(false)
        override val authVerifiedEpoch = MutableStateFlow(0L)
        override suspend fun refreshSession(): Result<AccountSession?> = Result.success(sessionState.value)
        override suspend fun signIn(username: String, password: String, turnstileToken: String): Result<SignInStepResult> =
            Result.success(SignInStepResult.Authenticated)

        override suspend fun verifyTwoFactor(otpCode: String, otpSession: String): Result<Unit> = Result.success(Unit)
        override suspend fun onWebViewAuthCompleted(): Result<AccountSession?> = Result.success(sessionState.value)
        override suspend fun onWebViewDismissed(): Result<AccountSession?> = Result.success(sessionState.value)
        override suspend fun signOut(): Result<Unit> = Result.success(Unit)
    }

    private class InMemoryDraftRepo : DraftRepository {
        private val map = linkedMapOf<Pair<Long, String>, EditorDraft>()
        override suspend fun getDraft(accountUid: Long, draftKey: String): EditorDraft? = map[accountUid to draftKey]
        override suspend fun saveDraft(draft: EditorDraft) {
            map[draft.accountUid to draft.draftKey] = draft
        }

        override suspend fun deleteDraft(accountUid: Long, draftKey: String) {
            map.remove(accountUid to draftKey)
        }

        override fun observeDrafts(accountUid: Long): Flow<List<EditorDraft>> = flowOf(emptyList())
        override fun observeDraft(accountUid: Long, draftKey: String): Flow<EditorDraft?> = flowOf(null)
    }

    private class FakeUserRepo : UserRepository {
        override suspend fun searchUsers(keyword: String): Result<List<FloorAuthor>> = Result.success(emptyList())
        override suspend fun getUserProfile(uid: Long, includeEditableSignature: Boolean): Result<UserProfile> =
            Result.failure(UnsupportedOperationException())

        override suspend fun updateOwnProfile(bio: String, signature: String, readme: String): Result<Unit> =
            Result.success(Unit)

        override suspend fun getUserTopics(uid: Long, page: Int): Result<Pair<List<TopicSummary>, Boolean>> =
            Result.success(emptyList<TopicSummary>() to false)

        override suspend fun getUserReplies(uid: Long, page: Int): Result<Pair<List<UserReplyExcerpt>, Boolean>> =
            Result.success(emptyList<UserReplyExcerpt>() to false)

        override suspend fun setFollowed(targetUid: Long, follow: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun getConnections(following: Boolean): Result<List<FloorAuthor>> = Result.success(emptyList())
        override suspend fun getBlockedUsers(): Result<List<FloorAuthor>> = Result.success(emptyList())
        override suspend fun blockUserByName(username: String): Result<Unit> = Result.success(Unit)
        override suspend fun unblockUserById(targetUid: Long): Result<Unit> = Result.success(Unit)
    }

    private class FakeBookmarkRepo : BookmarkAndHistoryRepository {
        override suspend fun setTopicBookmarked(topicId: Long, bookmarked: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun getServerBookmarks(page: Int): Result<Pair<List<TopicSummary>, Boolean>> =
            Result.success(emptyList<TopicSummary>() to false)

        override fun observeBookmarkedIds(): Flow<Set<Long>> = flowOf(emptySet())
        override fun observeReadHistory(): Flow<List<ReadHistoryEntry>> = flowOf(emptyList())
        override fun observeReadStateMap(topicIds: Collection<Long>): Flow<Map<Long, ReadHistoryEntry>> =
            flowOf(emptyMap())

        override suspend fun recordTopicVisit(entry: ReadHistoryEntry) = Unit
        override suspend fun clearReadHistory() = Unit
    }
}
