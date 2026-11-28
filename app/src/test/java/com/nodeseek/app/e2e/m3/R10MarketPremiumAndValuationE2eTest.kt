package com.nodeseek.app.e2e.m3

import com.nodeseek.app.data.local.FeedDiskCache
import com.nodeseek.app.data.repository.ForumRepositoryImpl
import com.nodeseek.app.data.valuation.FxRefreshOutcome
import com.nodeseek.app.data.valuation.TradeMarketReferenceRepositoryImpl
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.DealSpreadStatus
import com.nodeseek.app.domain.valuation.MarketDataSource
import com.nodeseek.app.domain.valuation.MarketSearchStatus
import com.nodeseek.app.domain.valuation.TradeListingIntent
import com.nodeseek.app.domain.valuation.TradeMarketPremiumAnalyzer
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.domain.valuation.ValuationMarkdownStyle
import com.nodeseek.app.domain.valuation.ValuationMode
import com.nodeseek.app.domain.valuation.VpsValuationEngine
import com.nodeseek.app.domain.valuation.VpsValuationInput
import com.nodeseek.app.domain.valuation.VpsValuationOutcome
import com.nodeseek.app.e2e.E2eFixtures
import com.nodeseek.app.e2e.E2eMainDispatcherRule
import com.nodeseek.app.e2e.E2eTestHarness
import com.nodeseek.app.navigation.TopicDetailRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class R10MarketPremiumAndValuationE2eTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    @Test
    fun `E2E-T1-R10-1 TradeMarketPremiumAnalyzer extracts spread deltas statuses intents and closed markers accurately`() {
        data class SpreadCase(
            val title: String,
            val expectedDelta: BigDecimal,
            val expectedStatus: DealSpreadStatus,
            val expectedIntent: TradeListingIntent,
            val expectedClosed: Boolean,
        )

        val cases = listOf(
            SpreadCase("DMIT Pro 溢价 +50", BigDecimal("50.00"), DealSpreadStatus.PREMIUM, TradeListingIntent.UNKNOWN, false),
            SpreadCase("[出] 搬瓦工 MEGABOX 溢价+120", BigDecimal("120.00"), DealSpreadStatus.PREMIUM, TradeListingIntent.SELL, false),
            SpreadCase("[出] DMIT LAX.Pro 剩余价值+80r", BigDecimal("80.00"), DealSpreadStatus.PREMIUM, TradeListingIntent.SELL, false),
            SpreadCase("[出] BWG MegaBox 折价 -35 出", BigDecimal("-35.00"), DealSpreadStatus.DISCOUNT, TradeListingIntent.SELL, false),
            SpreadCase("[出] Claw HK 剩余价值-20出", BigDecimal("-20.00"), DealSpreadStatus.DISCOUNT, TradeListingIntent.SELL, false),
            SpreadCase("[出] DMIT Pro 平出", BigDecimal("0.00"), DealSpreadStatus.PAR, TradeListingIntent.SELL, false),
            SpreadCase("RackNerd 原价出", BigDecimal("0.00"), DealSpreadStatus.PAR, TradeListingIntent.SELL, false),
            SpreadCase("[已出] DMIT Pro 溢价 50", BigDecimal("50.00"), DealSpreadStatus.PREMIUM, TradeListingIntent.SELL, true),
            SpreadCase("DMIT Pro +15.5出", BigDecimal("15.50"), DealSpreadStatus.PREMIUM, TradeListingIntent.UNKNOWN, false),
            SpreadCase("DMIT Pro -8.8出", BigDecimal("-8.80"), DealSpreadStatus.DISCOUNT, TradeListingIntent.UNKNOWN, false),
            SpreadCase("[求购] DMIT Pro 溢价 60 收", BigDecimal("60.00"), DealSpreadStatus.PREMIUM, TradeListingIntent.BUY, false),
        )

        cases.forEach { tc ->
            val parsed = TradeMarketPremiumAnalyzer.extractSpread(tc.title)
            assertNotNull("Expected spread parsed for '${tc.title}'", parsed)
            parsed!!
            assertEquals("Delta mismatch for '${tc.title}'", tc.expectedDelta, parsed.deltaCny)
            assertEquals("Status mismatch for '${tc.title}'", tc.expectedStatus, parsed.spreadStatus)
            assertEquals("Intent mismatch for '${tc.title}'", tc.expectedIntent, parsed.intent)
            assertEquals("Closed mismatch for '${tc.title}'", tc.expectedClosed, parsed.isClosedSold)
        }
    }

    @Test
    fun `E2E-T1-R10-2 analyzeLocal aggregates disk cache bookmarks and read history offline with alias matching and priority dedup`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)

            harness.forumDispatcher.register("GET", "/categories/trade") {
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(E2eFixtures.loadText("nodeseek/feed_trade_page1.html"))
            }

            // Populate FeedDiskCache via public ForumRepository.getFeed
            val feedRes = harness.forumRepository.getFeed(
                categorySlug = "trade",
                sort = FeedSort.LATEST_REPLY,
                page = 1,
            )
            assertTrue(feedRes.isSuccess)

            // Wait until FeedDiskCache writes at least one non-empty .json snapshot file to disk
            val cacheDir = File(harness.tempDir, "feed_cache_v1")
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (true) {
                        val hasJson = cacheDir.listFiles { f ->
                            f.isFile && f.name.endsWith(".json") && f.length() > 0L
                        }?.isNotEmpty() == true
                        if (hasJson) break
                        delay(20L)
                    }
                }
            }

            // Seed Bookmark (overlapping 1001 to prove FEED_CACHE > BOOKMARK priority, plus unique 1010)
            harness.bookmarkAndHistoryRepository.saveOfflineBookmark(
                summary = TopicSummary(
                    id = 1001L,
                    title = "[出] DMIT LAX.Pro.Wee 溢价 +40",
                    categorySlug = "trade",
                    categoryName = "交易",
                    authorId = 101L,
                    authorName = "alice101",
                ),
            )
            harness.bookmarkAndHistoryRepository.saveOfflineBookmark(
                summary = TopicSummary(
                    id = 1010L,
                    title = "[出] 大妈 Pro.Malibu 剩余价值+100",
                    categorySlug = "trade",
                    categoryName = "交易",
                    authorId = 210L,
                    authorName = "zoe210",
                ),
            )

            // Seed ReadHistory (overlapping 1010 to prove BOOKMARK > READ_HISTORY, plus unique 1011)
            harness.bookmarkAndHistoryRepository.recordTopicVisit(
                ReadHistoryEntry(
                    topicId = 1010L,
                    title = "[出] 大妈 Pro.Malibu 剩余价值+100",
                    categoryName = "交易",
                    authorName = "zoe210",
                    categorySlug = "trade",
                    accountUid = 101L,
                ),
            )
            harness.bookmarkAndHistoryRepository.recordTopicVisit(
                ReadHistoryEntry(
                    topicId = 1011L,
                    title = "[出] DMIT Pro 折价 -20 出",
                    categoryName = "交易",
                    authorName = "kyle211",
                    categorySlug = "trade",
                    accountUid = 101L,
                ),
            )

            harness.forumDispatcher.clearRecordedRequests()

            // Zero-network local analysis for DMIT Pro
            val dmitLocal = harness.tradeMarketReferenceRepository.analyzeLocal("DMIT Pro")
            assertEquals(MarketSearchStatus.IDLE, dmitLocal.searchStatus)
            assertFalse(dmitLocal.hasRemoteResults)
            assertTrue("analyzeLocal MUST perform zero network calls", harness.forumDispatcher.recordedRequests().isEmpty())

            val dmitDist = dmitLocal.distribution!!
            // Values: [-20, 0, 40, 60, 100] -> min=-20, p25=0, median=40, p75=60, max=100
            assertEquals(5, dmitDist.sampleCount)
            assertEquals(5, dmitDist.localSampleCount)
            assertEquals(0, dmitDist.remoteSampleCount)
            assertEquals(BigDecimal("-20.00"), dmitDist.minCny)
            assertEquals(BigDecimal("0.00"), dmitDist.p25Cny)
            assertEquals(BigDecimal("40.00"), dmitDist.medianCny)
            assertEquals(BigDecimal("60.00"), dmitDist.p75Cny)
            assertEquals(BigDecimal("100.00"), dmitDist.maxCny)

            val obsById = dmitDist.observations.associateBy { it.topicId }
            assertEquals(MarketDataSource.FEED_CACHE, obsById.getValue(1001L).source)
            assertEquals(MarketDataSource.FEED_CACHE, obsById.getValue(1002L).source)
            assertEquals(MarketDataSource.BOOKMARK, obsById.getValue(1010L).source)
            assertEquals(MarketDataSource.READ_HISTORY, obsById.getValue(1011L).source)
            assertNull("BUY listing must be excluded by default", obsById[1004L])

            // Alias matching for 瓦工 MEGABOX (搬瓦工 + BWG MegaBox)
            val bwgLocal = harness.tradeMarketReferenceRepository.analyzeLocal("瓦工 MEGABOX")
            val bwgDist = bwgLocal.distribution!!
            assertEquals(2, bwgDist.sampleCount)
            assertEquals(BigDecimal("-25.00"), bwgDist.minCny)
            assertEquals(BigDecimal("37.50"), bwgDist.medianCny)
            assertEquals(BigDecimal("100.00"), bwgDist.maxCny)

            // Cold-start reload using a brand new FeedDiskCache + ForumRepositoryImpl + TradeMarketReferenceRepositoryImpl
            val coldDiskCache = FeedDiskCache(cacheDir = cacheDir, ioScope = harness.backgroundScope)
            val coldForumRepo = ForumRepositoryImpl(
                apiClientProvider = { harness.apiClient },
                sessionRepository = harness.sessionRepository,
                settingsRepository = harness.settingsRepository,
                categoryDao = harness.categoryDao,
                readHistoryDao = harness.readHistoryDao,
                bookmarkDao = harness.bookmarkDao,
                bookmarkAndHistoryRepository = harness.bookmarkAndHistoryRepository,
                backgroundScope = harness.backgroundScope,
                feedDiskCache = coldDiskCache,
                clockMillis = harness.clock::nowMillis,
            )
            val coldMarketRepo = TradeMarketReferenceRepositoryImpl(
                forumRepository = coldForumRepo,
                bookmarkAndHistoryRepository = harness.bookmarkAndHistoryRepository,
                clockMillis = harness.clock::nowMillis,
            )
            val coldDmit = coldMarketRepo.analyzeLocal("DMIT Pro")
            assertEquals(5, coldDmit.distribution?.sampleCount)
            assertEquals(BigDecimal("40.00"), coldDmit.distribution?.medianCny)
            assertTrue(harness.forumDispatcher.recordedRequests().isEmpty())
        }
    }

    @Test
    fun `E2E-T1-R10-3 TopicDetailViewModel auto-loads local market ref then merges remote search with dedup single-flight and 15m TTL`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)

            val searchHits = AtomicInteger(0)
            harness.forumDispatcher.register("GET", "/categories/trade") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.loadText("nodeseek/feed_trade_page1.html"))
            }
            harness.forumDispatcher.register("GET", "/post-123-1") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.loadText("nodeseek/topic_trade_op_123.html"))
            }
            harness.forumDispatcher.register("GET", "/search") {
                searchHits.incrementAndGet()
                MockResponse()
                    .setResponseCode(200)
                    .setHeadersDelay(80L, TimeUnit.MILLISECONDS)
                    .setBody(E2eFixtures.loadText("nodeseek/search_trade_dmit_pro.html"))
            }

            // Warm local trade feed cache (1001:+40, 1002:+60, 1003:0)
            harness.forumRepository.getFeed(categorySlug = "trade", sort = FeedSort.LATEST_REPLY, page = 1).getOrThrow()
            harness.forumDispatcher.clearRecordedRequests()

            val vm = harness.createTopicDetailViewModel(topicId = 123L)
            val stateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }

            val initialState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.pageData != null && it.marketReference != null }
                }
            }
            val initialRef = initialState.marketReference!!
            assertEquals(MarketSearchStatus.IDLE, initialRef.searchStatus)
            assertFalse(initialRef.hasRemoteResults)
            assertEquals(0, searchHits.get())

            // Trigger on-demand remote search for "DMIT Pro"
            vm.searchTradeMarketReference("DMIT Pro")
            val mergedState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        val ref = it.marketReference
                        ref?.searchStatus == MarketSearchStatus.SUCCESS && ref.hasRemoteResults
                    }
                }
            }
            assertEquals(1, searchHits.get())
            val mergedDist = mergedState.marketReference!!.distribution!!
            // Local: 1001(+40), 1002(+60), 1003(0); current topic 123 (+80) is excluded from its own samples!
            // Remote adds: 201(+120), 202(-10); 1001(+40) overlaps -> deduped to FEED_CACHE!
            // Values: [-10, 0, 40, 60, 120] -> sampleCount == 5 (3 local + 2 remote)
            assertEquals(5, mergedDist.sampleCount)
            assertEquals(3, mergedDist.localSampleCount)
            assertEquals(2, mergedDist.remoteSampleCount)
            val obs1001 = mergedDist.observations.first { it.topicId == 1001L }
            assertEquals(MarketDataSource.FEED_CACHE, obs1001.source)
            assertTrue(
                "Current topic 123L must be excluded from its own market reference samples",
                mergedDist.observations.none { it.topicId == 123L },
            )

            // Within 15 min TTL: calling searchRemoteAndMerge again uses cache with 0 network calls
            harness.clock.advanceByMillis(5 * 60_000L)
            val ttlHit = harness.tradeMarketReferenceRepository.searchRemoteAndMerge("DMIT Pro")
            assertEquals(MarketSearchStatus.SUCCESS, ttlHit.searchStatus)
            assertEquals(1, searchHits.get())

            // After 15 min TTL expires: concurrent calls coalesce via single-flight to exactly 1 extra network request
            harness.clock.advanceByMillis(11 * 60_000L)
            val concurrentResults = withContext(Dispatchers.Default) {
                (1..4).map {
                    async {
                        harness.tradeMarketReferenceRepository.searchRemoteAndMerge("DMIT Pro")
                    }
                }.awaitAll()
            }
            assertEquals(2, searchHits.get())
            assertTrue(concurrentResults.all { it.searchStatus == MarketSearchStatus.SUCCESS && it.distribution?.sampleCount == 6 })

            stateJob.cancel()
        }
    }

    @Test
    fun `E2E-T1-R10-4 VpsValuationEngine with Frankfurter FX and TradeMarketReferenceRepository produce consistent valuation and zero cookie leak`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4, sessionCookie = "ns_fx_secret")

            harness.externalDispatcher.register("GET", "/fx/latest") {
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(E2eFixtures.loadText("nodeseek/fx_latest_cny.json"))
            }
            harness.forumDispatcher.register("GET", "/categories/trade") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.loadText("nodeseek/feed_trade_page1.html"))
            }
            harness.forumDispatcher.register("GET", "/search") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.loadText("nodeseek/search_trade_dmit_pro.html"))
            }

            // 1. Fetch Frankfurter FX via cookie-free externalServer client
            val fxOutcome = harness.exchangeRateRepository.ensureFreshRates()
            assertTrue(fxOutcome is FxRefreshOutcome.Success)
            val fxSuccess = fxOutcome as FxRefreshOutcome.Success
            val usdRate = fxSuccess.state.rateFor(ValuationCurrency.USD)
            val expectedUsdRate = BigDecimal.ONE.divide(BigDecimal("0.137931"), 6, RoundingMode.HALF_UP)
            assertEquals(expectedUsdRate, usdRate)
            harness.assertNoCookiesLeakedToExternalServer()

            // 2. Compute remaining value + transfer deal breakdown
            val valuationDt = LocalDateTime.of(2026, 11, 25, 0, 0)
            val expiryDt = LocalDateTime.of(2027, 5, 24, 0, 0) // 180 days remaining out of 365
            val outcome = VpsValuationEngine.calculate(
                VpsValuationInput(
                    machineLabel = "DMIT Pro",
                    mode = ValuationMode.TRANSFER_DEAL,
                    renewalAmount = BigDecimal("36.90"),
                    currency = ValuationCurrency.USD,
                    fxRateToCny = usdRate,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    valuationDateTime = valuationDt,
                    expiryDateTime = expiryDt,
                    askingPriceCny = BigDecimal("180.00"),
                ),
            )
            assertTrue(outcome is VpsValuationOutcome.Success)
            val result = (outcome as VpsValuationOutcome.Success).result
            val expectedRemainingCny = BigDecimal("36.90")
                .multiply(usdRate)
                .multiply(BigDecimal("180"))
                .divide(BigDecimal("365"), 2, RoundingMode.HALF_UP)
            assertEquals(expectedRemainingCny, result.remainingValueCny)
            assertNotNull(result.transferBreakdown)
            assertEquals(
                BigDecimal("180.00").subtract(expectedRemainingCny),
                result.transferBreakdown!!.buyerPremiumCny,
            )

            val summaryMd = VpsValuationEngine.formatMarkdown(result, ValuationMarkdownStyle.SUMMARY_CARD, "Frankfurter 在线")
            val tableMd = VpsValuationEngine.formatMarkdown(result, ValuationMarkdownStyle.THREE_COLUMN_TABLE, "Frankfurter 在线")
            assertTrue(summaryMd.contains("DMIT Pro"))
            assertTrue(summaryMd.contains("¥${result.remainingValueCny.toPlainString()}"))
            assertTrue(tableMd.contains("| 核算项 | 折算数值 | 口径说明 |"))
            assertTrue(tableMd.contains("Frankfurter 在线"))

            // 3. Pair with same-model market reference distribution
            harness.forumRepository.getFeed("trade", FeedSort.LATEST_REPLY, 1).getOrThrow()
            val marketRef = harness.tradeMarketReferenceRepository.searchRemoteAndMerge("DMIT Pro")
            val dist = marketRef.distribution!!
            assertEquals(BigDecimal("-10.00"), dist.minCny)
            assertEquals(BigDecimal("0.00"), dist.p25Cny)
            assertEquals(BigDecimal("40.00"), dist.medianCny)
            assertEquals(BigDecimal("60.00"), dist.p75Cny)
            assertEquals(BigDecimal("120.00"), dist.maxCny)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T1-R10-5 clicking market observation opens target TopicDetailRoute in TopicDetailViewModel`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)

            harness.forumDispatcher.register("GET", "/search") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.loadText("nodeseek/search_trade_dmit_pro.html"))
            }
            val obsTitle = "[出] DMIT LAX.Pro.Malibu 溢价 +120"
            val targetHtml = E2eFixtures.wrapTempScriptHtml(
                configJson = """{"user":{"member_id":101,"member_name":"alice101","coin":1650,"stardust":42,"rank":4},"enableCustomedStyle":1,"allCategory":[{"key":"trade","cn_text":"交易","customizable":1,"showInIndex":1,"adminOnly":0}],"postData":{"postId":201,"postPage":1,"postPageCount":1,"title":"$obsTitle","category":"trade","categoryWord":"交易","categoryLink":"/categories/trade","views":710,"collectionCount":4,"rank":0,"locked":0,"collected":0,"award":0,"op":{"uid":301,"name":"mallory301","avatar":"/avatar/301.png"},"comments":[{"commentId":20100,"floorIndex":0,"poster":{"uid":301,"name":"mallory301","avatar":"/avatar/301.png","isOp":1,"roles":[]},"time":{"createdDate":"2026-11-24T14:00:00Z","createdDateFormated":"2026-11-24 22:00:00"},"markdown":"Malibu 溢价 +120 出","likeCount":2,"dislikeCount":0,"upvoteCount":1,"liked":0,"disliked":0,"upvoted":0,"pined":0,"hot":0,"blocked":0}]}}""",
                bodyHtml = """<div id="nsk-body"><div id="nsk-body-left"><div class="nsk-post"><h1>$obsTitle</h1></div></div></div>""",
                title = obsTitle,
            )
            harness.forumDispatcher.register("GET", "/post-201-1") {
                MockResponse().setResponseCode(200).setBody(targetHtml)
            }

            val marketRef = harness.tradeMarketReferenceRepository.searchRemoteAndMerge("DMIT Pro")
            val obs = marketRef.distribution!!.observations.first { it.topicId == 201L }
            val route = TopicDetailRoute(topicId = obs.topicId, titleHint = obs.title)

            val detailVm = harness.createTopicDetailViewModel(
                topicId = route.topicId,
                initialFloor = route.initialFloor,
                titleHint = route.titleHint,
            )
            val job = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { detailVm.uiState.collect {} }
            val loaded = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { detailVm.uiState.first { it.pageData != null } }
            }

            assertEquals(201L, loaded.pageData?.topicId)
            assertEquals(obsTitle, loaded.pageData?.title)
            assertTrue(harness.forumDispatcher.recordedRequests().any { it.path == "/post-201-1" })
            job.cancel()
        }
    }

    @Test
    fun `E2E-T2-R10-1 remote search query percent-encodes plus sign as pct2B and scopes category=trade with zero external cookie leak`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)
            harness.forumDispatcher.register("GET", "/search") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.loadText("nodeseek/search_trade_dmit_pro.html"))
            }

            harness.tradeMarketReferenceRepository.searchRemoteAndMerge("DMIT +50")

            val searchReq = harness.forumDispatcher.recordedRequests().single {
                it.requestUrl?.encodedPath == "/search"
            }
            val encodedQuery = searchReq.requestUrl?.encodedQuery.orEmpty()
            assertTrue("Expected %2B in encoded query, got: $encodedQuery", encodedQuery.contains("q=DMIT%20%2B50"))
            assertFalse("Unescaped '+' must not appear in q=", encodedQuery.contains("q=DMIT+50") || encodedQuery.contains("q=DMIT%20+50"))
            assertTrue(encodedQuery.contains("category=trade"))
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T2-R10-2 unmatched query returns empty distribution with null quantiles and zero exceptions`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)

            harness.forumDispatcher.register("GET", "/categories/trade") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.loadText("nodeseek/feed_trade_page1.html"))
            }
            val emptySearchHtml = E2eFixtures.wrapTempScriptHtml(
                configJson = """{"user":{"member_id":101,"member_name":"alice101","coin":1650,"stardust":42,"rank":4},"enableCustomedStyle":1,"allCategory":[{"key":"trade","cn_text":"交易","customizable":1,"showInIndex":1,"adminOnly":0}]}""",
                bodyHtml = """<div id="nsk-body"><div id="nsk-body-left"><ul class="post-list"></ul></div></div>""",
            )
            harness.forumDispatcher.register("GET", "/search") {
                MockResponse().setResponseCode(200).setBody(emptySearchHtml)
            }

            harness.forumRepository.getFeed("trade", FeedSort.LATEST_REPLY, 1).getOrThrow()

            val localEmpty = harness.tradeMarketReferenceRepository.analyzeLocal("UnknownVendor X99")
            val localDist = localEmpty.distribution!!
            assertEquals(0, localDist.sampleCount)
            assertEquals(0, localDist.matchedTopicCount)
            assertNull(localDist.minCny)
            assertNull(localDist.p25Cny)
            assertNull(localDist.medianCny)
            assertNull(localDist.p75Cny)
            assertNull(localDist.maxCny)
            assertTrue(localDist.observations.isEmpty())

            val remoteEmpty = harness.tradeMarketReferenceRepository.searchRemoteAndMerge("UnknownVendor X99")
            assertEquals(MarketSearchStatus.SUCCESS, remoteEmpty.searchStatus)
            val remoteDist = remoteEmpty.distribution!!
            assertEquals(0, remoteDist.sampleCount)
            assertEquals(0, remoteDist.matchedTopicCount)
            assertNull(remoteDist.minCny)
            assertNull(remoteDist.p25Cny)
            assertNull(remoteDist.medianCny)
            assertNull(remoteDist.p75Cny)
            assertNull(remoteDist.maxCny)
            assertTrue(remoteDist.observations.isEmpty())
        }
    }

    @Test
    fun `E2E-T2-R10-3 HTTP 429 rate-limit enforces Retry-After and exponential backoff cooldown while preserving local distribution`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)

            harness.forumDispatcher.register("GET", "/categories/trade") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.loadText("nodeseek/feed_trade_page1.html"))
            }
            val searchCount = AtomicInteger(0)
            harness.forumDispatcher.enqueueNext(
                method = "GET",
                path = "/search",
                response = MockResponse()
                    .setResponseCode(429)
                    .setHeader("Retry-After", "45")
                    .setBody("Too Many Requests"),
            )
            harness.forumDispatcher.register("GET", "/search") {
                searchCount.incrementAndGet()
                MockResponse().setResponseCode(429).setBody("Too Many Requests")
            }

            harness.forumRepository.getFeed("trade", FeedSort.LATEST_REPLY, 1).getOrThrow()
            harness.forumDispatcher.clearRecordedRequests()

            // First 429 with Retry-After: 45
            val first429 = harness.tradeMarketReferenceRepository.searchRemoteAndMerge("DMIT Pro")
            assertEquals(MarketSearchStatus.RATE_LIMITED, first429.searchStatus)
            assertEquals(45, first429.cooldownRemainingSeconds)
            assertEquals(3, first429.distribution?.sampleCount) // Local distribution preserved!

            // Advance 10s (still within 45s cooldown) -> COOLDOWN with ZERO extra network calls
            harness.clock.advanceByMillis(10_000L)
            val duringCooldown = harness.tradeMarketReferenceRepository.searchRemoteAndMerge("DMIT Pro")
            assertEquals(MarketSearchStatus.COOLDOWN, duringCooldown.searchStatus)
            assertTrue(duringCooldown.cooldownRemainingSeconds in 34..35)
            assertEquals(3, duringCooldown.distribution?.sampleCount)
            assertEquals(1, harness.forumDispatcher.recordedRequests().count { it.requestUrl?.encodedPath == "/search" })

            // Advance past 45s cooldown -> second 429 without Retry-After triggers exponential backoff (30s * 2^1 = 60s)
            harness.clock.advanceByMillis(36_000L)
            val second429 = harness.tradeMarketReferenceRepository.searchRemoteAndMerge("DMIT Pro")
            assertEquals(MarketSearchStatus.RATE_LIMITED, second429.searchStatus)
            assertEquals(60, second429.cooldownRemainingSeconds)
            assertEquals(2, harness.forumDispatcher.recordedRequests().count { it.requestUrl?.encodedPath == "/search" })
        }
    }

    @Test
    fun `E2E-T2-R10-4 adversarial noise titles yield null spread and short ASCII aliases respect word boundaries`() {
        val noiseTitles = listOf(
            "DMIT Pro 1C1G 20G 100M 1Gbps 2026-08到期 CN2 GIA AS9929 1.2.3.4:8080 $39.9/yr 溢价出 300r",
            "DMIT Pro 溢价 15%",
            "RackNerd 8折出",
            "DMIT Pro 05.01到期",
            "DMIT Pro 套餐 - 10出",
        )
        noiseTitles.forEach { title ->
            assertNull("Expected null spread for noise title '$title'", TradeMarketPremiumAnalyzer.extractSpread(title))
        }
        assertFalse(
            "Short alias 'rn' for RackNerd must not match substring in 'return'",
            TradeMarketPremiumAnalyzer.matchesQuery("berlin return 节点 溢价 20", "RackNerd"),
        )
        assertTrue(
            "Exact alias 'rn' should match standalone token",
            TradeMarketPremiumAnalyzer.matchesQuery("[出] RN 2C2G 平出", "RackNerd"),
        )
    }

    @Test
    fun `E2E-T2-R10-5 closed sold listings remain included in distribution while BUY requests and foreign prices are handled properly`() {
        val candidates = listOf(
            com.nodeseek.app.domain.valuation.TradeCandidateTopic(1L, "[已出] DMIT Pro 溢价 +50", MarketDataSource.FEED_CACHE),
            com.nodeseek.app.domain.valuation.TradeCandidateTopic(2L, "[出] DMIT Pro 溢价 +90", MarketDataSource.FEED_CACHE),
            com.nodeseek.app.domain.valuation.TradeCandidateTopic(3L, "【已出】DMIT Pro 平出", MarketDataSource.FEED_CACHE),
            com.nodeseek.app.domain.valuation.TradeCandidateTopic(4L, "[出] DMIT Pro 折价 -10", MarketDataSource.FEED_CACHE),
            com.nodeseek.app.domain.valuation.TradeCandidateTopic(5L, "[求购] DMIT Pro 溢价 +300 收", MarketDataSource.FEED_CACHE),
            com.nodeseek.app.domain.valuation.TradeCandidateTopic(6L, "DMIT Pro $39.9/yr 100刀年付 溢价 +60", MarketDataSource.FEED_CACHE),
        )

        val sellOnly = TradeMarketPremiumAnalyzer.analyze(
            query = "DMIT Pro",
            candidates = candidates,
            includeBuyRequests = false,
        )
        // Sorted values: [-10, 0, 50, 60, 90]
        assertEquals(5, sellOnly.sampleCount)
        assertEquals(BigDecimal("-10.00"), sellOnly.minCny)
        assertEquals(BigDecimal("0.00"), sellOnly.p25Cny)
        assertEquals(BigDecimal("50.00"), sellOnly.medianCny)
        assertEquals(BigDecimal("60.00"), sellOnly.p75Cny)
        assertEquals(BigDecimal("90.00"), sellOnly.maxCny)

        val byId = sellOnly.observations.associateBy { it.topicId }
        assertTrue(byId.getValue(1L).isClosedSold)
        assertTrue(byId.getValue(3L).isClosedSold)
        assertFalse(byId.getValue(2L).isClosedSold)
        assertEquals(BigDecimal("60.00"), byId.getValue(6L).premiumDeltaCny)
        assertNull(byId[5L])

        // When includeBuyRequests = true, topic 5 (+300) is included
        val withBuy = TradeMarketPremiumAnalyzer.analyze(
            query = "DMIT Pro",
            candidates = candidates,
            includeBuyRequests = true,
        )
        assertEquals(6, withBuy.sampleCount)
        assertEquals(BigDecimal("300.00"), withBuy.maxCny)
        assertEquals(TradeListingIntent.BUY, withBuy.observations.first { it.topicId == 5L }.intent)
    }

    @Test
    fun `E2E-T2-R10-6 searchTopics recovers from category-scoped 404 via minimal query with client-side trade filter and page-2 404 ends pagination`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)

            val unscopedMixedHtml = E2eFixtures.wrapTempScriptHtml(
                configJson = """{"user":{"member_id":101,"member_name":"alice101","coin":1650,"stardust":42,"rank":4},"enableCustomedStyle":1,"allCategory":[{"key":"trade","cn_text":"交易","customizable":1,"showInIndex":1,"adminOnly":0},{"key":"expose","cn_text":"曝光","customizable":0,"showInIndex":0,"adminOnly":0}]}""",
                bodyHtml = """
                    <div id="nsk-body">
                      <div id="nsk-body-left">
                        <ul class="post-list">
                          <li class="post-list-item">
                            <div class="post-list-content">
                              <div class="post-title"><a href="/post-801-1">[出] DMIT Pro 溢价 +75</a></div>
                              <div class="post-info">
                                <span class="info-author"><a href="/space/801">seller801</a></span>
                                <a class="post-category" href="/categories/trade">交易</a>
                              </div>
                            </div>
                          </li>
                          <li class="post-list-item">
                            <div class="post-list-content">
                              <div class="post-title"><a href="/post-802-1">[曝光] DMIT Pro 溢价 +999</a></div>
                              <div class="post-info">
                                <span class="info-author"><a href="/space/802">reporter802</a></span>
                                <a class="post-category" href="/categories/expose">曝光</a>
                              </div>
                            </div>
                          </li>
                        </ul>
                      </div>
                    </div>
                """.trimIndent(),
            )

            harness.forumDispatcher.register("GET", "/search") { req ->
                val url = req.requestUrl
                val page = url?.queryParameter("page")?.toIntOrNull() ?: 1
                val hasCategory = !url?.queryParameter("category").isNullOrBlank()
                when {
                    page > 1 -> MockResponse().setResponseCode(404).setBody("Not Found")
                    hasCategory -> MockResponse().setResponseCode(404).setBody("Not Found")
                    else -> MockResponse().setResponseCode(200).setBody(unscopedMixedHtml)
                }
            }

            // 1. Page 1 category=trade 404 falls back to minimal `/search?q=...` and filters out `expose` topic
            val state = harness.tradeMarketReferenceRepository.searchRemoteAndMerge("DMIT Pro")
            assertEquals(MarketSearchStatus.SUCCESS, state.searchStatus)
            assertTrue(state.hasRemoteResults)
            val dist = state.distribution!!
            assertEquals(1, dist.sampleCount)
            assertEquals(BigDecimal("75.00"), dist.medianCny)
            assertEquals(listOf(801L), dist.observations.map { it.topicId })

            // 2. Page > 1 HTTP 404 on `/search` is treated cleanly as end-of-pagination
            val page2 = harness.forumRepository.searchTopics(
                query = "DMIT Pro",
                categorySlug = "trade",
                sort = FeedSort.NEWEST_TOPIC,
                page = 2,
            )
            assertTrue(page2.isSuccess)
            val page2Result = page2.getOrThrow()
            assertTrue(page2Result.topics.isEmpty())
            assertFalse(page2Result.hasNextPage)
        }
    }

    @Test
    fun `E2E-T2-R10-7 full search HTTP 404 falls back to paginated categories-trade feed and survives page-2 404`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)

            val pagedTradeHtml = E2eFixtures.loadText("nodeseek/feed_trade_page1.html").replace(
                "</ul>",
                """</ul><div aria-label="pagination"><a class="pager-next" href="/categories/trade/page-2?sortBy=postTime">下一页</a></div>""",
            )
            harness.forumDispatcher.register("GET", "/search") {
                MockResponse().setResponseCode(404).setBody("Not Found")
            }
            harness.forumDispatcher.register("GET", "/categories/trade") {
                MockResponse().setResponseCode(200).setBody(pagedTradeHtml)
            }
            harness.forumDispatcher.register("GET", "/categories/trade/page-2") {
                MockResponse().setResponseCode(404).setBody("Not Found")
            }

            // Cold local cache + /search 404 -> scans /categories/trade page 1 and survives page 2 404
            val state = harness.tradeMarketReferenceRepository.searchRemoteAndMerge("DMIT Pro")
            assertEquals(MarketSearchStatus.SUCCESS, state.searchStatus)
            assertTrue(state.hasRemoteResults)
            val dist = state.distribution!!
            // feed_trade_page1.html DMIT Pro samples: 1001(+40), 1002(+60), 1003(0) -> median 40.00
            assertEquals(3, dist.sampleCount)
            assertEquals(BigDecimal("40.00"), dist.medianCny)
            assertFalse(state.statusMessage.orEmpty().contains("官网请求失败 (404)"))
            assertTrue(harness.forumDispatcher.recordedRequests().any { it.requestUrl?.encodedPath == "/categories/trade" })
            assertTrue(harness.forumDispatcher.recordedRequests().any { it.requestUrl?.encodedPath == "/categories/trade/page-2" })
        }
    }

    @Test
    fun `E2E-T2-R10-8 concise peer posts fetch OP bodies with Frankfurter FX produce derived spread and ask fallback while excluding self`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4, sessionCookie = "ns_concise_secret")

            harness.externalDispatcher.register("GET", "/fx/latest") {
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(E2eFixtures.loadText("nodeseek/fx_latest_cny.json"))
            }

            val searchHtml = E2eFixtures.wrapTempScriptHtml(
                configJson = """{"user":{"member_id":101,"member_name":"alice101","coin":1650,"stardust":42,"rank":4},"enableCustomedStyle":1,"allCategory":[{"key":"trade","cn_text":"交易","customizable":1,"showInIndex":1,"adminOnly":0}]}""",
                bodyHtml = """
                    <div id="nsk-body">
                      <div id="nsk-body-left">
                        <ul class="post-list">
                          <li class="post-list-item">
                            <div class="post-list-content">
                              <div class="post-title"><a href="/post-900-1">出 DMIT LAX Pro Wee 300</a></div>
                              <div class="post-info"><span class="info-author"><a href="/space/900">self900</a></span><a class="post-category" href="/categories/trade">交易</a></div>
                            </div>
                          </li>
                          <li class="post-list-item">
                            <div class="post-list-content">
                              <div class="post-title"><a href="/post-901-1">[出] DMIT LAX Pro Wee 带原油</a></div>
                              <div class="post-info"><span class="info-author"><a href="/space/901">seller901</a></span><a class="post-category" href="/categories/trade">交易</a></div>
                            </div>
                          </li>
                          <li class="post-list-item">
                            <div class="post-list-content">
                              <div class="post-title"><a href="/post-902-1">[出] DMIT LAX Pro Wee 280r</a></div>
                              <div class="post-info"><span class="info-author"><a href="/space/902">seller902</a></span><a class="post-category" href="/categories/trade">交易</a></div>
                            </div>
                          </li>
                          <li class="post-list-item">
                            <div class="post-list-content">
                              <div class="post-title"><a href="/post-903-1">[出] DMIT LAX Pro Wee 吃灰出</a></div>
                              <div class="post-info"><span class="info-author"><a href="/space/903">seller903</a></span><a class="post-category" href="/categories/trade">交易</a></div>
                            </div>
                          </li>
                          <li class="post-list-item">
                            <div class="post-list-content">
                              <div class="post-title"><a href="/post-904-1">大妈 wee 295</a></div>
                              <div class="post-info"><span class="info-author"><a href="/space/904">seller904</a></span><a class="post-category" href="/categories/trade">交易</a></div>
                            </div>
                          </li>
                          <li class="post-list-item">
                            <div class="post-list-content">
                              <div class="post-title"><a href="/post-905-1">[出] DMIT LAX Pro Wee 溢价 +50</a></div>
                              <div class="post-info"><span class="info-author"><a href="/space/905">seller905</a></span><a class="post-category" href="/categories/trade">交易</a></div>
                            </div>
                          </li>
                          <li class="post-list-item">
                            <div class="post-list-content">
                              <div class="post-title"><a href="/post-906-1">[出] DMIT LAX Pro Wee 310r $36.5/年 2027-05-24到期</a></div>
                              <div class="post-info"><span class="info-author"><a href="/space/906">seller906</a></span><a class="post-category" href="/categories/trade">交易</a></div>
                            </div>
                          </li>
                        </ul>
                      </div>
                    </div>
                """.trimIndent(),
            )
            harness.forumDispatcher.register("GET", "/search") {
                MockResponse().setResponseCode(200).setBody(searchHtml)
            }

            val post901Html = E2eFixtures.wrapTempScriptHtml(
                configJson = """{"user":{"member_id":101,"member_name":"alice101","coin":1650,"stardust":42,"rank":4},"enableCustomedStyle":1,"allCategory":[{"key":"trade","cn_text":"交易","customizable":1,"showInIndex":1,"adminOnly":0}],"postData":{"postId":901,"postPage":1,"postPageCount":1,"title":"[出] DMIT LAX Pro Wee 带原油","category":"trade","categoryWord":"交易","categoryLink":"/categories/trade","views":120,"collectionCount":1,"rank":0,"locked":0,"collected":0,"award":0,"op":{"uid":901,"name":"seller901","avatar":"/avatar/901.png"},"comments":[{"commentId":90100,"floorIndex":0,"poster":{"uid":901,"name":"seller901","avatar":"/avatar/901.png","isOp":1,"roles":[]},"time":{"createdDate":"2026-11-24T14:00:00Z","createdDateFormated":"2026-11-24 22:00:00"},"markdown":"明盘 300，续费 $36.5/年，2027-05-24到期","likeCount":0,"dislikeCount":0,"upvoteCount":0,"liked":0,"disliked":0,"upvoted":0,"pined":0,"hot":0,"blocked":0}]}}""",
                bodyHtml = """<div id="nsk-body"><div id="nsk-body-left"><div class="nsk-post"><h1>[出] DMIT LAX Pro Wee 带原油</h1></div></div></div>""",
                title = "[出] DMIT LAX Pro Wee 带原油",
            )
            val post902Html = E2eFixtures.wrapTempScriptHtml(
                configJson = """{"user":{"member_id":101,"member_name":"alice101","coin":1650,"stardust":42,"rank":4},"enableCustomedStyle":1,"allCategory":[{"key":"trade","cn_text":"交易","customizable":1,"showInIndex":1,"adminOnly":0}],"postData":{"postId":902,"postPage":1,"postPageCount":1,"title":"[出] DMIT LAX Pro Wee 280r","category":"trade","categoryWord":"交易","categoryLink":"/categories/trade","views":95,"collectionCount":0,"rank":0,"locked":0,"collected":0,"award":0,"op":{"uid":902,"name":"seller902","avatar":"/avatar/902.png"},"comments":[{"commentId":90200,"floorIndex":0,"poster":{"uid":902,"name":"seller902","avatar":"/avatar/902.png","isOp":1,"roles":[]},"time":{"createdDate":"2026-11-24T13:30:00Z","createdDateFormated":"2026-11-24 21:30:00"},"markdown":"带原油，不刀，私信联系","likeCount":0,"dislikeCount":0,"upvoteCount":0,"liked":0,"disliked":0,"upvoted":0,"pined":0,"hot":0,"blocked":0}]}}""",
                bodyHtml = """<div id="nsk-body"><div id="nsk-body-left"><div class="nsk-post"><h1>[出] DMIT LAX Pro Wee 280r</h1></div></div></div>""",
                title = "[出] DMIT LAX Pro Wee 280r",
            )
            val post903Html = E2eFixtures.wrapTempScriptHtml(
                configJson = """{"user":{"member_id":101,"member_name":"alice101","coin":1650,"stardust":42,"rank":4},"enableCustomedStyle":1,"allCategory":[{"key":"trade","cn_text":"交易","customizable":1,"showInIndex":1,"adminOnly":0}],"postData":{"postId":903,"postPage":1,"postPageCount":1,"title":"[出] DMIT LAX Pro Wee 吃灰出","category":"trade","categoryWord":"交易","categoryLink":"/categories/trade","views":80,"collectionCount":0,"rank":0,"locked":0,"collected":0,"award":0,"op":{"uid":903,"name":"seller903","avatar":"/avatar/903.png"},"comments":[{"commentId":90300,"floorIndex":0,"poster":{"uid":903,"name":"seller903","avatar":"/avatar/903.png","isOp":1,"roles":[]},"time":{"createdDate":"2026-11-24T13:00:00Z","createdDateFormated":"2026-11-24 21:00:00"},"markdown":"闲置吃灰，有兴趣私信聊","likeCount":0,"dislikeCount":0,"upvoteCount":0,"liked":0,"disliked":0,"upvoted":0,"pined":0,"hot":0,"blocked":0}]}}""",
                bodyHtml = """<div id="nsk-body"><div id="nsk-body-left"><div class="nsk-post"><h1>[出] DMIT LAX Pro Wee 吃灰出</h1></div></div></div>""",
                title = "[出] DMIT LAX Pro Wee 吃灰出",
            )
            val post904Html = E2eFixtures.wrapTempScriptHtml(
                configJson = """{"user":{"member_id":101,"member_name":"alice101","coin":1650,"stardust":42,"rank":4},"enableCustomedStyle":1,"allCategory":[{"key":"trade","cn_text":"交易","customizable":1,"showInIndex":1,"adminOnly":0}],"postData":{"postId":904,"postPage":1,"postPageCount":1,"title":"大妈 wee 295","category":"trade","categoryWord":"交易","categoryLink":"/categories/trade","views":110,"collectionCount":0,"rank":0,"locked":0,"collected":0,"award":0,"op":{"uid":904,"name":"seller904","avatar":"/avatar/904.png"},"comments":[{"commentId":90400,"floorIndex":0,"poster":{"uid":904,"name":"seller904","avatar":"/avatar/904.png","isOp":1,"roles":[]},"time":{"createdDate":"2026-11-24T12:30:00Z","createdDateFormated":"2026-11-24 20:30:00"},"markdown":"美西 LAX Pro Wee，续费 $36.5/年，2027-05-24到期","likeCount":0,"dislikeCount":0,"upvoteCount":0,"liked":0,"disliked":0,"upvoted":0,"pined":0,"hot":0,"blocked":0}]}}""",
                bodyHtml = """<div id="nsk-body"><div id="nsk-body-left"><div class="nsk-post"><h1>大妈 wee 295</h1></div></div></div>""",
                title = "大妈 wee 295",
            )
            val post905Html = E2eFixtures.wrapTempScriptHtml(
                configJson = """{"user":{"member_id":101,"member_name":"alice101","coin":1650,"stardust":42,"rank":4},"enableCustomedStyle":1,"allCategory":[{"key":"trade","cn_text":"交易","customizable":1,"showInIndex":1,"adminOnly":0}],"postData":{"postId":905,"postPage":1,"postPageCount":1,"title":"[出] DMIT LAX Pro Wee 溢价 +50","category":"trade","categoryWord":"交易","categoryLink":"/categories/trade","views":105,"collectionCount":0,"rank":0,"locked":0,"collected":0,"award":0,"op":{"uid":905,"name":"seller905","avatar":"/avatar/905.png"},"comments":[{"commentId":90500,"floorIndex":0,"poster":{"uid":905,"name":"seller905","avatar":"/avatar/905.png","isOp":1,"roles":[]},"time":{"createdDate":"2026-11-24T12:00:00Z","createdDateFormated":"2026-11-24 20:00:00"},"markdown":"续费 $36.5/年，2027-05-24到期，带原油","likeCount":0,"dislikeCount":0,"upvoteCount":0,"liked":0,"disliked":0,"upvoted":0,"pined":0,"hot":0,"blocked":0}]}}""",
                bodyHtml = """<div id="nsk-body"><div id="nsk-body-left"><div class="nsk-post"><h1>[出] DMIT LAX Pro Wee 溢价 +50</h1></div></div></div>""",
                title = "[出] DMIT LAX Pro Wee 溢价 +50",
            )
            harness.forumDispatcher.register("GET", "/post-901-1") {
                MockResponse().setResponseCode(200).setBody(post901Html)
            }
            harness.forumDispatcher.register("GET", "/post-902-1") {
                MockResponse().setResponseCode(200).setBody(post902Html)
            }
            harness.forumDispatcher.register("GET", "/post-903-1") {
                MockResponse().setResponseCode(200).setBody(post903Html)
            }
            harness.forumDispatcher.register("GET", "/post-904-1") {
                MockResponse().setResponseCode(200).setBody(post904Html)
            }
            harness.forumDispatcher.register("GET", "/post-905-1") {
                MockResponse().setResponseCode(200).setBody(post905Html)
            }

            val repo = TradeMarketReferenceRepositoryImpl(
                forumRepository = harness.forumRepository,
                bookmarkAndHistoryRepository = harness.bookmarkAndHistoryRepository,
                clockMillis = harness.clock::nowMillis,
                exchangeRateRepository = harness.exchangeRateRepository,
                nowDateProvider = { LocalDate.of(2026, 11, 25) },
            )

            val state = repo.searchRemoteAndMerge(
                query = "DMIT LAX Pro Wee",
                excludeTopicId = 900L,
            )
            assertEquals(MarketSearchStatus.SUCCESS, state.searchStatus)
            val dist = state.distribution!!
            assertEquals(6, dist.matchedTopicCount) // 901, 902, 903, 904 (loose->confirmed), 905 (explicit+derived ask), 906 (complete title); 900 excluded
            assertEquals(5, dist.pricedTopicCount)  // 901, 904, 906 (derived spread), 902 (ask-only), 905 (explicit spread + derived ask)
            assertEquals(4, dist.sampleCount)
            assertEquals(3, dist.derivedSampleCount)
            assertEquals(1, dist.derivedAskSampleCount)
            assertEquals(5, dist.askSampleCount)    // 901 (300), 902 (280), 904 (295), 905 (residual+50 derived), 906 (310)
            assertNotNull(dist.askMedianCny)
            assertEquals(1, dist.matchedButUnparsedCount)
            assertEquals(listOf(903L), dist.matchedUnparsedTopics.map { it.topicId })
            assertTrue(dist.observations.none { it.topicId == 900L })

            val derived901 = dist.observations.first { it.topicId == 901L }
            assertTrue(derived901.isDerived)
            assertFalse(derived901.isAskDerived)
            assertEquals(BigDecimal("300.00"), derived901.askPriceCny)
            assertTrue(derived901.matchedExpression.startsWith("推算: 挂牌¥300 − 残值¥"))

            val derived904 = dist.observations.first { it.topicId == 904L }
            assertTrue("Ultra-short loose peer 904 should confirm via body and produce derived spread", derived904.isDerived)
            assertEquals(BigDecimal("295.00"), derived904.askPriceCny)

            val explicit905 = dist.observations.first { it.topicId == 905L }
            assertFalse(explicit905.isDerived)
            assertTrue("Explicit-spread peer 905 should derive total ask from OP body", explicit905.isAskDerived)
            assertNotNull(explicit905.residualCny)
            assertEquals(explicit905.residualCny!!.add(BigDecimal("50.00")), explicit905.askPriceCny)

            val paths = harness.forumDispatcher.recordedRequests().mapNotNull { it.requestUrl?.encodedPath }.toSet()
            assertTrue("/post-901-1" in paths)
            assertTrue("/post-902-1" in paths)
            assertTrue("/post-903-1" in paths)
            assertTrue("/post-904-1" in paths)
            assertTrue("Explicit-spread topic 905 without total ask should fetch OP body", "/post-905-1" in paths)
            assertFalse("Complete title topic 906 with ask+renewal+expiry must skip body fetch", "/post-906-1" in paths)
            assertFalse("Excluded current topic 900 must not be fetched", "/post-900-1" in paths)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }
}
