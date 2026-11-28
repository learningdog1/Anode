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
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.DealSpreadStatus
import com.nodeseek.app.domain.valuation.FeePayer
import com.nodeseek.app.domain.valuation.MarketDataSource
import com.nodeseek.app.domain.valuation.MarketSearchStatus
import com.nodeseek.app.domain.valuation.MiddlemanFeeMode
import com.nodeseek.app.domain.valuation.NaturalCycleSpan
import com.nodeseek.app.domain.valuation.TradeAskPriceObservation
import com.nodeseek.app.domain.valuation.TradeCandidateTopic
import com.nodeseek.app.domain.valuation.TradeListingIntent
import com.nodeseek.app.domain.valuation.TradeListingPriceParser
import com.nodeseek.app.domain.valuation.TradeMarketPremiumAnalyzer
import com.nodeseek.app.domain.valuation.TradeMarketReferenceRepository
import com.nodeseek.app.domain.valuation.TradeMarketReferenceState
import com.nodeseek.app.domain.valuation.TradePremiumDistribution
import com.nodeseek.app.domain.valuation.TradePremiumObservation
import com.nodeseek.app.domain.valuation.TransferPricingBasis
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.domain.valuation.ValuationMode
import com.nodeseek.app.domain.valuation.VpsValuationOutcome
import com.nodeseek.app.ui.valuation.buildValuationSheetPrefill
import com.nodeseek.app.ui.valuation.evaluateValuationForm
import com.nodeseek.app.ui.valuation.extractMarketQueryWithContext
import com.nodeseek.app.ui.valuation.formatAskObservationDetail
import com.nodeseek.app.ui.valuation.formatAskPriceHeadline
import com.nodeseek.app.ui.valuation.formatCompactCny
import com.nodeseek.app.ui.valuation.formatCurrentTopicPricingSummary
import com.nodeseek.app.ui.valuation.formatDistributionSummaryDetail
import com.nodeseek.app.ui.valuation.formatMatchAndPricedSummary
import com.nodeseek.app.ui.valuation.formatMatchedOnlyHint
import com.nodeseek.app.ui.valuation.formatObservationDetail
import com.nodeseek.app.ui.valuation.formatTransferBasisReadout
import com.nodeseek.app.ui.valuation.parseSignedDecimalOrNull
import com.nodeseek.app.ui.valuation.sanitizeMarketStatusMessage
import com.nodeseek.app.ui.valuation.sanitizeSignedDecimalInput
import com.nodeseek.app.ui.valuation.seedTargetSpreadOnBasisSwitch
import com.nodeseek.app.ui.valuation.toggleSignedDecimalText
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
import java.time.LocalDate

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
            override suspend fun analyzeLocal(
                query: String,
                includeBuyRequests: Boolean,
                excludeTopicId: Long?,
            ): TradeMarketReferenceState {
                localCalls++
                val dist = TradeMarketPremiumAnalyzer.analyze(
                    query = query,
                    candidates = listOf(
                        TradeCandidateTopic(1L, "[出] DMIT LAX.Pro.Tiny 溢价 +60", MarketDataSource.FEED_CACHE),
                    ).filterNot { it.topicId == excludeTopicId },
                )
                return TradeMarketReferenceState(query = query, distribution = dist)
            }

            override suspend fun searchRemoteAndMerge(
                query: String,
                includeBuyRequests: Boolean,
                excludeTopicId: Long?,
            ): TradeMarketReferenceState {
                remoteCalls++
                val dist = TradeMarketPremiumAnalyzer.analyze(
                    query = query,
                    candidates = listOf(
                        TradeCandidateTopic(1L, "[出] DMIT LAX.Pro.Tiny 溢价 +60", MarketDataSource.FEED_CACHE),
                        TradeCandidateTopic(2L, "[出] DMIT LAX.Pro.Tiny 溢价 +100", MarketDataSource.REMOTE_SEARCH),
                    ).filterNot { it.topicId == excludeTopicId },
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
            override suspend fun analyzeLocal(
                query: String,
                includeBuyRequests: Boolean,
                excludeTopicId: Long?,
            ): TradeMarketReferenceState {
                localCalls++
                return TradeMarketReferenceState(query = query)
            }

            override suspend fun searchRemoteAndMerge(
                query: String,
                includeBuyRequests: Boolean,
                excludeTopicId: Long?,
            ): TradeMarketReferenceState =
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
            override suspend fun analyzeLocal(
                query: String,
                includeBuyRequests: Boolean,
                excludeTopicId: Long?,
            ): TradeMarketReferenceState {
                recordedQueries += query
                val dist = TradeMarketPremiumAnalyzer.analyze(
                    query = query,
                    candidates = listOf(
                        TradeCandidateTopic(201L, "[出] 搬瓦工 MEGABOX-PRO 溢价 +120", MarketDataSource.FEED_CACHE),
                    ).filterNot { it.topicId == excludeTopicId },
                )
                return TradeMarketReferenceState(query = query, distribution = dist)
            }

            override suspend fun searchRemoteAndMerge(
                query: String,
                includeBuyRequests: Boolean,
                excludeTopicId: Long?,
            ): TradeMarketReferenceState =
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
            override suspend fun analyzeLocal(
                query: String,
                includeBuyRequests: Boolean,
                excludeTopicId: Long?,
            ): TradeMarketReferenceState {
                localCalls++
                // Only self-topic 510 in local cache (excluded -> 0 peer samples) -> must still trigger remote search!
                val dist = TradeMarketPremiumAnalyzer.analyze(
                    query = query,
                    candidates = listOf(
                        TradeCandidateTopic(510L, "[出] DMIT LAX.Pro.WEE 溢价 +80", MarketDataSource.READ_HISTORY),
                    ).filterNot { it.topicId == excludeTopicId },
                )
                return TradeMarketReferenceState(query = query, distribution = dist)
            }

            override suspend fun searchRemoteAndMerge(
                query: String,
                includeBuyRequests: Boolean,
                excludeTopicId: Long?,
            ): TradeMarketReferenceState {
                remoteCalls++
                val dist = TradeMarketPremiumAnalyzer.analyze(
                    query = query,
                    candidates = listOf(
                        TradeCandidateTopic(510L, "[出] DMIT LAX.Pro.WEE 溢价 +80", MarketDataSource.READ_HISTORY),
                        TradeCandidateTopic(801L, "[出] DMIT LAX.Pro.WEE 溢价 +60", MarketDataSource.REMOTE_SEARCH),
                        TradeCandidateTopic(802L, "[出] DMIT LAX.Pro.WEE 溢价 +100", MarketDataSource.REMOTE_SEARCH),
                    ).filterNot { it.topicId == excludeTopicId },
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
        // Current topic 510L is excluded; only peers 801 (+60) and 802 (+100) remain (median +80.00)
        assertEquals(2, loaded.marketReference?.distribution?.sampleCount)
        assertTrue(loaded.marketReference?.distribution?.observations?.none { it.topicId == 510L } == true)
        assertEquals(BigDecimal("80.00"), loaded.marketReference?.distribution?.medianCny)

        // Refreshing the current page must NOT clobber completed remote results or spam remote search
        vm.refreshCurrentPage()
        assertEquals(1, remoteCalls)
        assertTrue(vm.uiState.value.marketReference?.hasRemoteResults == true)
        assertEquals(2, vm.uiState.value.marketReference?.distribution?.sampleCount)
        collectJob.cancel()
    }

    @Test
    fun `TopicDetailViewModel extracts query from OP body when title is vague and Page 1 arrives after Page 2`() = runTest {
        val session = FakeSessionRepo(AccountSession(uid = 10L, username = "viewer"))
        val page1Gate = CompletableDeferred<Unit>()
        val analyzedQueries = mutableListOf<String>()

        val fakeMarketRepo = object : TradeMarketReferenceRepository {
            override suspend fun analyzeLocal(
                query: String,
                includeBuyRequests: Boolean,
                excludeTopicId: Long?,
            ): TradeMarketReferenceState {
                analyzedQueries += query
                val dist = TradeMarketPremiumAnalyzer.analyze(
                    query = query,
                    candidates = listOf(
                        TradeCandidateTopic(901L, "[出] DMIT LAX.Pro.WEE 溢价 +70", MarketDataSource.FEED_CACHE),
                    ).filterNot { it.topicId == excludeTopicId },
                )
                return TradeMarketReferenceState(query = query, distribution = dist)
            }

            override suspend fun searchRemoteAndMerge(
                query: String,
                includeBuyRequests: Boolean,
                excludeTopicId: Long?,
            ): TradeMarketReferenceState =
                analyzeLocal(query, includeBuyRequests, excludeTopicId).copy(
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
            override suspend fun analyzeLocal(
                query: String,
                includeBuyRequests: Boolean,
                excludeTopicId: Long?,
            ): TradeMarketReferenceState {
                val dist = TradeMarketPremiumAnalyzer.analyze(
                    query = query,
                    candidates = listOf(
                        TradeCandidateTopic(1L, "[出] DMIT LAX.Pro.Tiny 溢价 +60", MarketDataSource.FEED_CACHE),
                    ).filterNot { it.topicId == excludeTopicId },
                )
                return TradeMarketReferenceState(query = query, distribution = dist)
            }

            override suspend fun searchRemoteAndMerge(
                query: String,
                includeBuyRequests: Boolean,
                excludeTopicId: Long?,
            ): TradeMarketReferenceState {
                remoteQueries += query
                val dist = TradeMarketPremiumAnalyzer.analyze(
                    query = query,
                    candidates = listOf(
                        TradeCandidateTopic(2L, "[出] 搬瓦工 MEGABOX-PRO 溢价 +150", MarketDataSource.REMOTE_SEARCH),
                    ).filterNot { it.topicId == excludeTopicId },
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

    @Test
    fun `TopicDetailViewModel triggers market reference on concise trade blank all and buy posts caches OP body and excludes self`() = runTest {
        val session = FakeSessionRepo(AccountSession(uid = 10L, username = "viewer"))
        val cachedBodies = mutableMapOf<Long, String>()
        val excludedIds = mutableListOf<Long?>()
        val recordedQueries = mutableListOf<String>()

        val fakeMarketRepo = object : TradeMarketReferenceRepository {
            override fun cacheTopicBody(topicId: Long, bodyMarkdown: String) {
                cachedBodies[topicId] = bodyMarkdown
            }

            override suspend fun analyzeLocal(
                query: String,
                includeBuyRequests: Boolean,
                excludeTopicId: Long?,
            ): TradeMarketReferenceState {
                recordedQueries += query
                excludedIds += excludeTopicId
                val dist = TradeMarketPremiumAnalyzer.analyze(
                    query = query,
                    candidates = listOf(
                        TradeCandidateTopic(9901L, "[出] DMIT LAX Pro Wee 溢价 +50", MarketDataSource.FEED_CACHE),
                    ),
                    excludeTopicId = excludeTopicId,
                )
                return TradeMarketReferenceState(query = query, distribution = dist)
            }

            override suspend fun searchRemoteAndMerge(
                query: String,
                includeBuyRequests: Boolean,
                excludeTopicId: Long?,
            ): TradeMarketReferenceState {
                excludedIds += excludeTopicId
                return TradeMarketReferenceState(
                    query = query,
                    searchStatus = MarketSearchStatus.SUCCESS,
                    hasRemoteResults = true,
                )
            }
        }

        data class ConciseCase(
            val topicId: Long,
            val slug: String,
            val name: String,
            val title: String,
            val body: String,
        )

        val cases = listOf(
            // Concise sell on trade board without "溢价"
            ConciseCase(751L, "trade", "交易", "出 DMIT LAX Pro Wee 300", "续费 \$36.9/年 2026-12-01到期"),
            // Concise buy (收) post with blank category
            ConciseCase(752L, "", "", "收咸鱼云 圣何塞 9929", "预算 200 带原油"),
            // Concise buy (收) post with "all" category
            ConciseCase(753L, "all", "全部", "[收] 搬瓦工 MEGABOX", "带原油收一台"),
            // Empty title-only extraction -> extracts from OP body
            ConciseCase(754L, "trade", "交易", "回血出个吃灰鸡", "机型：DMIT LAX Pro Wee\n明盘 280"),
        )

        for (tc in cases) {
            val forum = FakeForumRepo(
                detailHandler = { _, p ->
                    Result.success(
                        makePage(
                            topicId = tc.topicId,
                            pageNum = p,
                            categorySlug = tc.slug,
                            categoryName = tc.name,
                            title = tc.title,
                            opMarkdown = tc.body,
                        ),
                    )
                },
            )
            val vm = TopicDetailViewModel(
                container = buildContainer(forum, session),
                topicId = tc.topicId,
                tradeMarketReferenceRepository = fakeMarketRepo,
            )
            val job = launch(testDispatcher) { vm.uiState.collect {} }
            val loaded = vm.uiState.first { it.marketReference != null }
            assertTrue(
                "Expected non-blank extracted query for '${tc.title}', got '${loaded.marketReference?.query}'",
                loaded.marketReference!!.query.isNotBlank(),
            )
            assertEquals(tc.body, cachedBodies[tc.topicId])
            assertTrue("Expected excludeTopicId=${tc.topicId} passed to repo", tc.topicId in excludedIds)
            job.cancel()
        }
    }

    @Test
    fun `MarketPremiumSection formatters render derived spread formula ask price summary and match counts`() {
        assertEquals("300", formatCompactCny(BigDecimal("300.00")))
        assertEquals("36.9", formatCompactCny(BigDecimal("36.90")))
        assertEquals("12.35", formatCompactCny(BigDecimal("12.35")))

        val derivedObs = TradePremiumObservation(
            topicId = 10L,
            title = "出 DMIT LAX Pro Wee 300",
            premiumDeltaCny = BigDecimal("80.00"),
            spreadStatus = DealSpreadStatus.PREMIUM,
            intent = TradeListingIntent.SELL,
            isClosedSold = false,
            source = MarketDataSource.REMOTE_SEARCH,
            matchedExpression = "推算: 挂牌¥300 − 残值¥220",
            askPriceCny = BigDecimal("300.00"),
            residualCny = BigDecimal("220.00"),
            isDerived = true,
        )
        assertEquals(
            "全站搜索 · 推算溢价 +¥80.00（总价¥300 − 残值¥220）",
            formatObservationDetail(derivedObs),
        )

        val derivedTotalObs = TradePremiumObservation(
            topicId = 101L,
            title = "出 DMIT LAX Pro Wee 溢价 +80",
            premiumDeltaCny = BigDecimal("80.00"),
            spreadStatus = DealSpreadStatus.PREMIUM,
            intent = TradeListingIntent.SELL,
            isClosedSold = false,
            source = MarketDataSource.REMOTE_SEARCH,
            matchedExpression = "溢价 +80",
            askPriceCny = BigDecimal("300.00"),
            residualCny = BigDecimal("220.00"),
            isDerived = false,
            isAskDerived = true,
        )
        assertEquals(
            "全站搜索 · 推算总价 ¥300（残值¥220 + 溢价¥80）",
            formatObservationDetail(derivedTotalObs),
        )

        val derivedDiscountTotalObs = derivedTotalObs.copy(
            topicId = 102L,
            premiumDeltaCny = BigDecimal("-20.00"),
            spreadStatus = DealSpreadStatus.DISCOUNT,
            askPriceCny = BigDecimal("200.00"),
        )
        assertEquals(
            "全站搜索 · 推算总价 ¥200（残值¥220 − 折价¥20）",
            formatObservationDetail(derivedDiscountTotalObs),
        )

        val explicitObs = TradePremiumObservation(
            topicId = 11L,
            title = "[已出] DMIT LAX Pro Wee 溢价 +50",
            premiumDeltaCny = BigDecimal("50.00"),
            spreadStatus = DealSpreadStatus.PREMIUM,
            intent = TradeListingIntent.SELL,
            isClosedSold = true,
            source = MarketDataSource.FEED_CACHE,
            matchedExpression = "溢价 +50",
        )
        assertEquals(
            "版块缓存 · 已出 · 溢价 +50",
            formatObservationDetail(explicitObs),
        )

        val askObs = TradeAskPriceObservation(
            topicId = 12L,
            title = "出 DMIT LAX Pro Wee 280r",
            askPriceCny = BigDecimal("280.00"),
            intent = TradeListingIntent.SELL,
            isClosedSold = false,
            source = MarketDataSource.REMOTE_SEARCH,
            matchedExpression = "280r",
        )
        assertEquals(
            "全站搜索 · 280r",
            formatAskObservationDetail(askObs),
        )

        val askOnlyDist = TradePremiumDistribution(
            query = "DMIT LAX Pro Wee",
            normalizedTokens = listOf("dmit", "lax", "pro", "wee"),
            sampleCount = 0,
            matchedTopicCount = 5,
            premiumCount = 0,
            parCount = 0,
            discountCount = 0,
            minCny = null,
            p25Cny = null,
            medianCny = null,
            p75Cny = null,
            maxCny = null,
            observations = emptyList(),
            localSampleCount = 0,
            remoteSampleCount = 0,
            askSampleCount = 3,
            askMinCny = BigDecimal("260.00"),
            askP25Cny = BigDecimal("280.00"),
            askMedianCny = BigDecimal("300.00"),
            askP75Cny = BigDecimal("340.00"),
            askMaxCny = BigDecimal("360.00"),
            askObservations = listOf(
                askObs,
                askObs.copy(topicId = 13L, askPriceCny = BigDecimal("300.00")),
                askObs.copy(topicId = 14L, askPriceCny = BigDecimal("360.00")),
            ),
            matchedButUnparsedCount = 2,
        )
        assertEquals("匹配 5 帖 · 可计价 3 帖", formatMatchAndPricedSummary(askOnlyDist))
        assertEquals("同款挂牌价 中位 ¥300（¥280–¥340）", formatAskPriceHeadline(askOnlyDist))

        // Mixed 1 local spread + 2 remote ask-only -> priced local/remote counts must reconcile to (本地 1 / 全站 2)
        val mixedDist = TradePremiumDistribution(
            query = "DMIT LAX Pro Wee",
            normalizedTokens = listOf("dmit", "lax", "pro", "wee"),
            sampleCount = 1,
            matchedTopicCount = 3,
            premiumCount = 1,
            parCount = 0,
            discountCount = 0,
            minCny = BigDecimal("50.00"),
            p25Cny = BigDecimal("50.00"),
            medianCny = BigDecimal("50.00"),
            p75Cny = BigDecimal("50.00"),
            maxCny = BigDecimal("50.00"),
            observations = listOf(explicitObs),
            localSampleCount = 1,
            remoteSampleCount = 0,
            askSampleCount = 2,
            askMinCny = BigDecimal("280.00"),
            askP25Cny = BigDecimal("290.00"),
            askMedianCny = BigDecimal("300.00"),
            askP75Cny = BigDecimal("310.00"),
            askMaxCny = BigDecimal("320.00"),
            askObservations = listOf(
                askObs.copy(topicId = 21L, source = MarketDataSource.REMOTE_SEARCH),
                askObs.copy(topicId = 22L, source = MarketDataSource.REMOTE_SEARCH),
            ),
        )
        assertEquals(
            "匹配 3 帖 · 可计价 3 帖（本地 1 / 全站 2） · 样本较少仅供参考",
            formatDistributionSummaryDetail(mixedDist),
        )

        // When sampleCount >= 3, summary detail omits duplicate 极值 (already displayed in Tile 1)
        val threeSampleDist = mixedDist.copy(
            sampleCount = 3,
            matchedTopicCount = 5,
            observations = listOf(
                explicitObs,
                explicitObs.copy(topicId = 15L, premiumDeltaCny = BigDecimal("70.00")),
                explicitObs.copy(topicId = 16L, premiumDeltaCny = BigDecimal("90.00")),
            ),
            minCny = BigDecimal("50.00"),
            maxCny = BigDecimal("90.00"),
        )
        val threeSampleSummary = formatDistributionSummaryDetail(threeSampleDist)
        assertFalse("Summary detail must not duplicate 极值 already shown in metric tile", threeSampleSummary.contains("极值"))
        assertEquals("匹配 5 帖 · 可计价 5 帖（本地 3 / 全站 2）", threeSampleSummary)

        // Matched-only copy must not claim clickable rows below when sampleItems is empty
        val noRowsRemoteHint = formatMatchedOnlyHint(hasRemoteResults = true, hasClickableSamples = false)
        assertFalse("Hint must not reference rows below when list is empty", noRowsRemoteHint.contains("点开下方"))
        val withRowsRemoteHint = formatMatchedOnlyHint(hasRemoteResults = true, hasClickableSamples = true)
        assertTrue(withRowsRemoteHint.contains("点开下方"))

        // extractMarketQueryWithContext preserves newlines & markdown table pipes for analyzer fallback parsing
        assertEquals(
            "DMIT LAX Pro WEE",
            extractMarketQueryWithContext(
                title = "出个大妈传家宝",
                openingMarkdown = "LAX.Pro.WEE，续费 \$36.9/yr\n2027-03-01到期\n也可换 搬瓦工 MEGABOX-PRO",
            ),
        )
        assertEquals(
            "BWG MEGABOX PRO",
            extractMarketQueryWithContext(
                title = "出吃灰传家宝",
                openingMarkdown = "商家：BandwagonHost\n套餐：MEGABOX-PRO\n续费：\$45.68/年\n也可换 DMIT",
            ),
        )
        assertEquals(
            "DMIT LAX Pro WEE",
            extractMarketQueryWithContext(
                title = "出吃灰传家宝",
                openingMarkdown = "| 商家 | DMIT |\n| 套餐 | LAX.Pro.WEE |\n| 备注 | 也可换 搬瓦工 |",
            ),
        )

        // Current-topic pricing summary banner copy for total-ask, explicit-spread, and BUY posts
        val fixedDate = LocalDate.of(2026, 6, 1)
        val totalWithResidual = TradeListingPriceParser.inferSpread(
            title = "出 DMIT LAX Pro Wee 300",
            bodySnippet = "续费 \$36.5/年 2026-11-28到期",
            now = fixedDate,
        )!!
        val (totalHead, totalDetail) = formatCurrentTopicPricingSummary(totalWithResidual)!!
        assertEquals("本帖按总价出：总价 ¥300", totalHead)
        assertEquals("净残值 ¥130.5 · 推算溢价 +¥169.50", totalDetail)

        val totalOnly = TradeListingPriceParser.inferSpread(
            title = "出 DMIT LAX Pro Wee 300",
            bodySnippet = "带原油，私信",
            now = fixedDate,
        )!!
        assertEquals(
            "本帖按总价出：总价 ¥300" to "未标续费/到期，点「估值核算」算溢价",
            formatCurrentTopicPricingSummary(totalOnly),
        )

        val spreadWithResidual = TradeListingPriceParser.inferSpread(
            title = "出 DMIT LAX Pro Wee 溢价 +80",
            bodySnippet = "续费 \$36.5/年 2026-11-28到期",
            now = fixedDate,
        )!!
        val (spreadHead, spreadDetail) = formatCurrentTopicPricingSummary(spreadWithResidual)!!
        assertEquals("本帖按溢价出：溢价 +¥80.00", spreadHead)
        assertEquals("净残值 ¥130.5 · 推算总价 ¥210.5", spreadDetail)

        val spreadOnly = TradeListingPriceParser.inferSpread(
            title = "出 DMIT LAX Pro Wee 溢价 +80",
            bodySnippet = "带原油",
            now = fixedDate,
        )!!
        assertEquals(
            "本帖按溢价出：溢价 +¥80.00" to "未标续费/到期，点「估值核算」算总价",
            formatCurrentTopicPricingSummary(spreadOnly),
        )

        // Explicit par ("剩余价值出") and explicit discount ("折价30出") differentiate wording
        val explicitPar = TradeListingPriceParser.inferSpread(
            title = "出 DMIT LAX Pro Wee 剩余价值出",
            bodySnippet = "续费 \$36.5/年 2026-11-28到期",
            now = fixedDate,
        )!!
        assertEquals(
            "本帖按残值平出：平出 ¥0.00" to "净残值 ¥130.5 · 推算总价 ¥130.5",
            formatCurrentTopicPricingSummary(explicitPar),
        )

        val explicitDiscount = TradeListingPriceParser.inferSpread(
            title = "出 DMIT LAX Pro Wee 折价30出",
            bodySnippet = "续费 \$36.5/年 2026-11-28到期",
            now = fixedDate,
        )!!
        assertEquals(
            "本帖按折价出：折价 -¥30.00" to "净残值 ¥130.5 · 推算总价 ¥100.5",
            formatCurrentTopicPricingSummary(explicitDiscount),
        )

        // Derived discount and derived par differentiate wording instead of hardcoding "推算溢价"
        val totalDiscount = TradeListingPriceParser.inferSpread(
            title = "出 DMIT LAX Pro Wee 100r",
            bodySnippet = "续费 \$36.5/年 2026-11-28到期",
            now = fixedDate,
        )!!
        assertEquals(
            "本帖按总价出：总价 ¥100" to "净残值 ¥130.5 · 推算折价 -¥30.50",
            formatCurrentTopicPricingSummary(totalDiscount),
        )

        val totalPar = TradeListingPriceParser.inferSpread(
            title = "出 DMIT LAX Pro Wee 130.5r",
            bodySnippet = "续费 \$36.5/年 2026-11-28到期",
            now = fixedDate,
        )!!
        assertEquals(
            "本帖按总价出：总价 ¥130.5" to "净残值 ¥130.5 · 推算平出 ¥0.00",
            formatCurrentTopicPricingSummary(totalPar),
        )

        val buyPost = TradeListingPriceParser.inferSpread(
            title = "[收] DMIT LAX Pro Wee 溢价 +80",
            now = fixedDate,
        )!!
        assertNull(formatCurrentTopicPricingSummary(buyPost))
    }

    @Test
    fun `TopicDetailViewModel attaches currentTopicPricing for total-ask and explicit-spread posts across remote search`() = runTest {
        val session = FakeSessionRepo(AccountSession(uid = 10L, username = "viewer"))
        val fakeMarketRepo = object : TradeMarketReferenceRepository {
            override suspend fun analyzeLocal(
                query: String,
                includeBuyRequests: Boolean,
                excludeTopicId: Long?,
            ): TradeMarketReferenceState = TradeMarketReferenceState(query = query)

            override suspend fun searchRemoteAndMerge(
                query: String,
                includeBuyRequests: Boolean,
                excludeTopicId: Long?,
            ): TradeMarketReferenceState = TradeMarketReferenceState(
                query = query,
                searchStatus = MarketSearchStatus.SUCCESS,
                hasRemoteResults = true,
            )
        }

        // Case 1: Total-ask post with stated residual -> derives spread on currentTopicPricing
        val totalForum = FakeForumRepo(
            detailHandler = { _, p ->
                Result.success(
                    makePage(
                        topicId = 781L,
                        pageNum = p,
                        categorySlug = "trade",
                        categoryName = "交易",
                        title = "出 DMIT LAX Pro Wee 300",
                        opMarkdown = "剩余价值 220，明盘 300 带原油",
                    ),
                )
            },
        )
        val totalVm = TopicDetailViewModel(
            container = buildContainer(totalForum, session),
            topicId = 781L,
            tradeMarketReferenceRepository = fakeMarketRepo,
        )
        val totalJob = launch(testDispatcher) { totalVm.uiState.collect {} }
        val totalLoaded = totalVm.uiState.first { it.marketReference?.hasRemoteResults == true }
        val totalPricing = totalLoaded.marketReference?.currentTopicPricing
        assertNotNull(totalPricing)
        assertTrue(totalPricing!!.hasBothTotalAndSpread)
        assertTrue(totalPricing.isDerived)
        assertFalse(totalPricing.isAskDerived)
        assertEquals(BigDecimal("300.00"), totalPricing.askPriceCny)
        assertEquals(BigDecimal("80.00"), totalPricing.spreadDeltaCny)
        totalJob.cancel()

        // Case 2: Explicit-spread post with stated residual -> derives total ask on currentTopicPricing
        val spreadForum = FakeForumRepo(
            detailHandler = { _, p ->
                Result.success(
                    makePage(
                        topicId = 782L,
                        pageNum = p,
                        categorySlug = "trade",
                        categoryName = "交易",
                        title = "出 DMIT LAX Pro Wee 溢价 +80",
                        opMarkdown = "剩余价值 220，带原油",
                    ),
                )
            },
        )
        val spreadVm = TopicDetailViewModel(
            container = buildContainer(spreadForum, session),
            topicId = 782L,
            tradeMarketReferenceRepository = fakeMarketRepo,
        )
        val spreadJob = launch(testDispatcher) { spreadVm.uiState.collect {} }
        val spreadLoaded = spreadVm.uiState.first { it.marketReference?.hasRemoteResults == true }
        val spreadPricing = spreadLoaded.marketReference?.currentTopicPricing
        assertNotNull(spreadPricing)
        assertTrue(spreadPricing!!.hasBothTotalAndSpread)
        assertFalse(spreadPricing.isDerived)
        assertTrue(spreadPricing.isAskDerived)
        assertEquals(BigDecimal("80.00"), spreadPricing.spreadDeltaCny)
        assertEquals(BigDecimal("300.00"), spreadPricing.askPriceCny)
        spreadJob.cancel()
    }

    @Test
    fun `VpsValuationBottomSheet prefill and bidirectional spread form helpers compute total and spread accurately`() {
        val fixedDate = LocalDate.of(2026, 6, 1)

        // 1. Auto-prefill from total-ask listing
        val totalPrefill = buildValuationSheetPrefill(
            title = "[出] DMIT LAX Pro Wee 明盘 300",
            openingMarkdown = "续费 \$36.5/年，2026-11-28到期，带原油",
            now = fixedDate,
        )
        assertEquals(ValuationMode.TRANSFER_DEAL, totalPrefill.mode)
        assertEquals(TransferPricingBasis.BY_TOTAL_PRICE, totalPrefill.pricingBasis)
        assertEquals(ValuationCurrency.USD, totalPrefill.currency)
        assertEquals("36.5", totalPrefill.renewalAmountText)
        assertEquals(BillingCyclePreset.ANNUAL, totalPrefill.cyclePreset)
        assertEquals("2026-11-28", totalPrefill.expiryDateText)
        assertEquals("300", totalPrefill.askingPriceText)

        // 2. Auto-prefill from explicit-spread listing (positive and negative discount)
        val spreadPrefill = buildValuationSheetPrefill(
            title = "[出] DMIT LAX Pro Wee 溢价 +80",
            openingMarkdown = "续费 \$36.5/年，2026-11-28到期",
            now = fixedDate,
        )
        assertEquals(ValuationMode.TRANSFER_DEAL, spreadPrefill.mode)
        assertEquals(TransferPricingBasis.BY_SPREAD, spreadPrefill.pricingBasis)
        assertEquals("80", spreadPrefill.targetSpreadText)

        val discountPrefill = buildValuationSheetPrefill(
            title = "[出] DMIT LAX Pro Wee 折价 -30",
            openingMarkdown = "续费 \$36.5/年，2026-11-28到期",
            now = fixedDate,
        )
        assertEquals(TransferPricingBasis.BY_SPREAD, discountPrefill.pricingBasis)
        assertEquals("-30", discountPrefill.targetSpreadText)

        // 3. Signed decimal helpers (handles fullwidth minus, leading sign, toggle)
        assertEquals("-50.25", sanitizeSignedDecimalInput("－50.25"))
        assertEquals("-", sanitizeSignedDecimalInput("-"))
        assertNull(parseSignedDecimalOrNull("-"))
        assertEquals(BigDecimal("-50.25"), parseSignedDecimalOrNull("-50.25"))
        assertEquals("-80", toggleSignedDecimalText("80"))
        assertEquals("80", toggleSignedDecimalText("-80"))
        assertEquals("-", toggleSignedDecimalText("0"))

        // 4. evaluateValuationForm in BY_SPREAD mode: +80, 0, -30, and clamped -500
        fun evalBySpread(spreadText: String): VpsValuationOutcome = evaluateValuationForm(
            mode = ValuationMode.TRANSFER_DEAL,
            machineLabel = "DMIT LAX Pro Wee",
            currency = ValuationCurrency.USD,
            fxRateToCny = BigDecimal("7.25"),
            renewalAmountText = "36.5",
            cyclePreset = BillingCyclePreset.ANNUAL,
            naturalSpan = NaturalCycleSpan.YEAR,
            customDaysText = "365",
            useCustomPrevRenewal = false,
            prevRenewalDateText = "2025-11-28",
            preciseToMinute = false,
            valuationDateText = "2026-06-01",
            valuationTimeText = "00:00",
            expiryDateText = "2026-11-28",
            expiryTimeText = "00:00",
            askingPriceText = "0",
            pushFeeText = "0",
            pushCurrency = ValuationCurrency.CNY,
            pushFeeToCnyRate = BigDecimal.ONE,
            pushPayer = FeePayer.BUYER,
            middlemanEnabled = false,
            middlemanMode = MiddlemanFeeMode.PERCENT,
            middlemanValueText = "0",
            middlemanPayer = FeePayer.BUYER,
            pricingBasis = TransferPricingBasis.BY_SPREAD,
            targetSpreadText = spreadText,
        )

        val plus80 = (evalBySpread("80") as VpsValuationOutcome.Success).result
        assertEquals(BigDecimal("130.50"), plus80.remainingValueCny)
        val plus80Deal = plus80.transferBreakdown!!
        assertEquals(BigDecimal("210.50"), plus80Deal.askingPriceCny)
        assertEquals(BigDecimal("80.00"), plus80Deal.buyerPremiumCny)
        assertEquals(DealSpreadStatus.PREMIUM, plus80Deal.spreadStatus)

        val par0 = (evalBySpread("0") as VpsValuationOutcome.Success).result
        val par0Deal = par0.transferBreakdown!!
        assertEquals(BigDecimal("130.50"), par0Deal.askingPriceCny)
        assertEquals(DealSpreadStatus.PAR, par0Deal.spreadStatus)

        val minus30 = (evalBySpread("-30") as VpsValuationOutcome.Success).result
        val minus30Deal = minus30.transferBreakdown!!
        assertEquals(BigDecimal("100.50"), minus30Deal.askingPriceCny)
        assertEquals(BigDecimal("-30.00"), minus30Deal.buyerPremiumCny)
        assertEquals(DealSpreadStatus.DISCOUNT, minus30Deal.spreadStatus)

        val clamped = (evalBySpread("-500") as VpsValuationOutcome.Success).result
        val clampedDeal = clamped.transferBreakdown!!
        assertEquals(BigDecimal("0.00"), clampedDeal.askingPriceCny)
        assertEquals(DealSpreadStatus.DISCOUNT, clampedDeal.spreadStatus)

        assertTrue(evalBySpread("-") is VpsValuationOutcome.Invalid)

        // 5. Mode-switch round-trip and readout with buyer push fee (remainingValue=100, ask=150, buyerPush=20)
        fun evalWithFees(
            basis: TransferPricingBasis,
            askText: String = "150",
            spreadText: String = "0",
            pushFeeText: String = "20",
        ): VpsValuationOutcome = evaluateValuationForm(
            mode = ValuationMode.TRANSFER_DEAL,
            machineLabel = "DMIT Test",
            currency = ValuationCurrency.CNY,
            fxRateToCny = BigDecimal.ONE,
            renewalAmountText = "365",
            cyclePreset = BillingCyclePreset.ANNUAL,
            naturalSpan = NaturalCycleSpan.YEAR,
            customDaysText = "365",
            useCustomPrevRenewal = false,
            prevRenewalDateText = "2025-09-09",
            preciseToMinute = false,
            valuationDateText = "2026-06-01",
            valuationTimeText = "00:00",
            expiryDateText = "2026-09-09", // 100 days remaining -> net residual = 100.00 CNY
            expiryTimeText = "00:00",
            askingPriceText = askText,
            pushFeeText = pushFeeText,
            pushCurrency = ValuationCurrency.CNY,
            pushFeeToCnyRate = BigDecimal.ONE,
            pushPayer = FeePayer.BUYER,
            middlemanEnabled = false,
            middlemanMode = MiddlemanFeeMode.PERCENT,
            middlemanValueText = "0",
            middlemanPayer = FeePayer.BUYER,
            pricingBasis = basis,
            targetSpreadText = spreadText,
        )

        val totalWithFee = (evalWithFees(TransferPricingBasis.BY_TOTAL_PRICE, askText = "150", pushFeeText = "20") as VpsValuationOutcome.Success).result
        val totalWithFeeDeal = totalWithFee.transferBreakdown!!
        assertEquals(BigDecimal("100.00"), totalWithFee.remainingValueCny)
        assertEquals(BigDecimal("150.00"), totalWithFeeDeal.askingPriceCny)
        assertEquals(BigDecimal("70.00"), totalWithFeeDeal.buyerPremiumCny)
        // Switching BY_TOTAL_PRICE -> BY_SPREAD must seed pure ask spread "50" (NOT fee-inflated "70")
        val seededSpread = seedTargetSpreadOnBasisSwitch(totalWithFee)
        assertEquals("50", seededSpread)
        val roundTripped = (evalWithFees(TransferPricingBasis.BY_SPREAD, spreadText = seededSpread!!, pushFeeText = "20") as VpsValuationOutcome.Success).result
        val roundTrippedDeal = roundTripped.transferBreakdown!!
        assertEquals(BigDecimal("150.00"), roundTrippedDeal.askingPriceCny)
        assertEquals(BigDecimal("170.00"), roundTrippedDeal.buyerTotalCostCny)
        assertEquals(BigDecimal("70.00"), roundTrippedDeal.buyerPremiumCny)

        // Readout shows exact ask-residual math (+50) plus fee-inclusive premium (+70) when buyer fees > 0
        assertEquals(
            "对应溢价：+¥50.00（总价 ¥150.00 − 净残值 ¥100.00） · 含买家费用溢价 +¥70.00",
            formatTransferBasisReadout(totalWithFee),
        )
        assertEquals(
            "推算总价：¥150.00（净残值 ¥100.00 +¥50.00） · 含买家费用溢价 +¥70.00",
            formatTransferBasisReadout(roundTripped),
        )
        // Discount and par readouts with zero fees
        val discountNoFee = (evalWithFees(TransferPricingBasis.BY_TOTAL_PRICE, askText = "80", pushFeeText = "0") as VpsValuationOutcome.Success).result
        assertEquals(
            "对应折价：-¥20.00（总价 ¥80.00 − 净残值 ¥100.00）",
            formatTransferBasisReadout(discountNoFee),
        )
        val parNoFee = (evalWithFees(TransferPricingBasis.BY_TOTAL_PRICE, askText = "100", pushFeeText = "0") as VpsValuationOutcome.Success).result
        assertEquals(
            "对应平出：¥0.00（总价 ¥100.00 − 净残值 ¥100.00）",
            formatTransferBasisReadout(parNoFee),
        )
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
