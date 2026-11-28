package com.nodeseek.app.e2e.m3

import com.nodeseek.app.data.parser.NodeSeekHtmlParser
import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.data.valuation.FxRefreshOutcome
import com.nodeseek.app.domain.baseline.BaselineBandwidthMetrics
import com.nodeseek.app.domain.baseline.BaselineComparisonFormatter
import com.nodeseek.app.domain.baseline.BaselineDiffEngine
import com.nodeseek.app.domain.baseline.BaselineOverallVerdict
import com.nodeseek.app.domain.baseline.BaselineRoutingMetrics
import com.nodeseek.app.domain.baseline.BaselineSourceType
import com.nodeseek.app.domain.baseline.BenchmarkBaselineSnapshot
import com.nodeseek.app.domain.baseline.StreamingPlatformResult
import com.nodeseek.app.domain.baseline.StreamingUnlockStatus
import com.nodeseek.app.domain.carpool.CarpoolBillingCycle
import com.nodeseek.app.domain.carpool.CarpoolCalculationInput
import com.nodeseek.app.domain.carpool.CarpoolCalculationOutcome
import com.nodeseek.app.domain.carpool.CarpoolCalculator
import com.nodeseek.app.domain.carpool.CarpoolMarkdownGenerator
import com.nodeseek.app.domain.carpool.CarpoolStatusUpdater
import com.nodeseek.app.domain.fleet.FleetDeliveryMethod
import com.nodeseek.app.domain.fleet.FleetSalePostBuilder
import com.nodeseek.app.domain.fleet.FleetSalePostOutcome
import com.nodeseek.app.domain.fleet.FleetSalePostRequest
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.netbench.BackboneRouteTag
import com.nodeseek.app.domain.trade.TradeDeliveryChecklistEngine
import com.nodeseek.app.domain.trade.TradePrivacyOptions
import com.nodeseek.app.domain.trade.TradePrivacySanitizer
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.MarketSearchStatus
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.domain.valuation.ValuationMarkdownStyle
import com.nodeseek.app.domain.valuation.ValuationMode
import com.nodeseek.app.domain.valuation.VpsValuationEngine
import com.nodeseek.app.domain.valuation.VpsValuationInput
import com.nodeseek.app.domain.valuation.VpsValuationOutcome
import com.nodeseek.app.e2e.E2eFixtures
import com.nodeseek.app.e2e.E2eMainDispatcherRule
import com.nodeseek.app.e2e.E2eTestHarness
import com.nodeseek.app.ui.composer.TopicComposerUiEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class M3PairwiseE2eTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    private fun buildThreadHtml(
        postId: Long,
        categorySlug: String,
        categoryWord: String,
        title: String,
        markdown: String,
        rank: Int = 0,
        opUid: Long = 101L,
        opName: String = "alice101",
    ): String {
        val baseHtml = E2eFixtures.loadText("nodeseek/topic_trade_op_123.html")
        val encodedMatch = Regex("""<script id="temp-script"[^>]*>(.*?)</script>""").find(baseHtml)
            ?: error("Missing temp-script in topic_trade_op_123.html")
        val decodedJson = Base64.getDecoder().decode(encodedMatch.groupValues[1]).toString(Charsets.UTF_8)
        val catsJson = TempScriptParser.json.parseToJsonElement(decodedJson).jsonObject.getValue("allCategory").toString()
        val cfg = """{"user":{"member_id":$opUid,"member_name":${JsonPrimitive(opName)},"coin":1650,"stardust":42,"rank":4},"enableCustomedStyle":1,"allCategory":$catsJson,"postData":{"postId":$postId,"postPage":1,"postPageCount":1,"title":${JsonPrimitive(title)},"category":${JsonPrimitive(categorySlug)},"categoryWord":${JsonPrimitive(categoryWord)},"categoryLink":"/categories/$categorySlug","views":320,"collectionCount":2,"rank":$rank,"locked":0,"collected":0,"award":0,"op":{"uid":$opUid,"name":${JsonPrimitive(opName)},"avatar":"/avatar/$opUid.png"},"comments":[{"commentId":${postId * 100},"floorIndex":0,"poster":{"uid":$opUid,"name":${JsonPrimitive(opName)},"avatar":"/avatar/$opUid.png","isOp":1,"roles":[]},"time":{"createdDate":"2026-11-24T15:00:00Z","createdDateFormated":"2026-11-24 23:00:00"},"markdown":${JsonPrimitive(markdown)},"likeCount":3,"dislikeCount":0,"upvoteCount":2,"liked":0,"disliked":0,"upvoted":0,"pined":0,"hot":0,"blocked":0}]}}"""
        return E2eFixtures.wrapTempScriptHtml(
            configJson = cfg,
            bodyHtml = """<div id="nsk-body"><div id="nsk-body-left"><div class="nsk-post"><h1>$title</h1><article class="post-content"></article></div></div></div>""",
            title = title,
        )
    }

    @Test
    fun `E2E-T3-07 R7 Before-vs-After diff embedded in trade post then closed via R9 with IP and credential masking`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)

            val beforeEpoch = harness.clock.nowMillis()
            val afterEpoch = beforeEpoch + 45L * 86_400_000L
            val beforeSnapshot = BenchmarkBaselineSnapshot(
                id = "base_t3_07",
                label = "购入首测",
                targetKey = "203.0.113.42",
                serverId = "srv_t3_07",
                hostDisplay = "203.0.113.42",
                resolvedIp = "203.0.113.42",
                sourceType = BaselineSourceType.SSH_BENCHMARK,
                capturedAtEpochMillis = beforeEpoch,
                bandwidth = BaselineBandwidthMetrics(downloadMbps = 800.0, uploadMbps = 500.0),
                routing = BaselineRoutingMetrics(
                    reverseRouteSignature = "CN2 GIA",
                    reverseBackboneTags = listOf(BackboneRouteTag.CN2_GIA),
                ),
                streamingUnlocks = listOf(
                    StreamingPlatformResult("netflix", "Netflix", StreamingUnlockStatus.UNLOCKED, "US"),
                ),
            )
            harness.benchmarkBaselineStore.saveBaseline(beforeSnapshot)

            val afterSnapshot = beforeSnapshot.copy(
                id = "cur_t3_07",
                label = "当前实测",
                capturedAtEpochMillis = afterEpoch,
                bandwidth = BaselineBandwidthMetrics(downloadMbps = 240.0, uploadMbps = 480.0),
                routing = BaselineRoutingMetrics(
                    reverseRouteSignature = "163",
                    reverseBackboneTags = listOf(BackboneRouteTag.CT_163),
                ),
                streamingUnlocks = listOf(
                    StreamingPlatformResult("netflix", "Netflix", StreamingUnlockStatus.LOCKED),
                ),
            )

            val diff = BaselineDiffEngine.compare(beforeSnapshot, afterSnapshot)
            assertEquals(BaselineOverallVerdict.SIGNIFICANT_DEGRADATION, diff.overallVerdict)
            val diffMarkdown = BaselineComparisonFormatter.formatMarkdown(diff, maskTargetIp = true)
            assertTrue(diffMarkdown.contains("明显劣化"))
            assertTrue(diffMarkdown.contains("-70.0%"))
            assertTrue(diffMarkdown.contains("203.0.113.*"))
            assertFalse(diffMarkdown.contains("203.0.113.42"))

            val fullTradeBody = buildString {
                appendLine(diffMarkdown)
                appendLine()
                appendLine("测试 IP: 198.51.100.77")
                appendLine("Root密码: pass_secret")
                appendLine("订单号: ORD-889900")
            }

            val servedTitle = AtomicReference("")
            val servedBody = AtomicReference("")

            harness.forumDispatcher.register("GET", "/api/content/list-categories") {
                MockResponse().setResponseCode(200).setBody(
                    """{"success":true,"data":[{"key":"trade","cn_text":"交易","customizable":1,"showInIndex":1}]}""",
                )
            }
            harness.forumDispatcher.register("POST", "/api/content/new-discussion") { req ->
                val obj = TempScriptParser.json.parseToJsonElement(req.body.clone().readUtf8()).jsonObject
                servedTitle.set(obj.getValue("title").jsonPrimitive.content)
                servedBody.set(obj.getValue("content").jsonPrimitive.content)
                MockResponse().setResponseCode(200).setBody("""{"success":true,"postId":707}""")
            }
            harness.forumDispatcher.register("GET", "/post-707-1") {
                MockResponse().setResponseCode(200).setBody(
                    buildThreadHtml(
                        postId = 707L,
                        categorySlug = "trade",
                        categoryWord = "交易",
                        title = servedTitle.get(),
                        markdown = servedBody.get(),
                        rank = 2,
                    ),
                )
            }
            harness.forumDispatcher.register("POST", "/api/content/edit-discussion") { req ->
                val obj = TempScriptParser.json.parseToJsonElement(req.body.clone().readUtf8()).jsonObject
                servedTitle.set(obj.getValue("title").jsonPrimitive.content)
                servedBody.set(obj.getValue("content").jsonPrimitive.content)
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            // 1. Publish trade post via TopicComposerViewModel
            val composer = harness.createTopicComposerViewModel(initialCategorySlug = "trade")
            val composerJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { composer.uiState.collect {} }
            val composerEvents = CopyOnWriteArrayList<TopicComposerUiEvent>()
            val composerEventJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                composer.events.collect { composerEvents.add(it) }
            }

            composer.onTitleChanged("[出] DMIT 实测降级出")
            composer.onMarkdownChanged(fullTradeBody)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { composer.uiState.first { it.canSubmit && it.selectedCategorySlug == "trade" } }
            }
            composer.onPublishClicked()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (composerEvents.none { it is TopicComposerUiEvent.PublishedSuccess && it.createdTopicId == 707L }) {
                        delay(20L)
                    }
                }
            }
            composerJob.cancel()
            composerEventJob.cancel()

            // 2. Open created post 707 in TopicDetailViewModel and closeTradeTopic()
            val detailVm = harness.createTopicDetailViewModel(topicId = 707L)
            val detailJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { detailVm.uiState.collect {} }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { detailVm.uiState.first { it.openingFloor != null } }
            }

            detailVm.closeTradeTopic(TradePrivacyOptions())
            val closed = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    detailVm.uiState.first {
                        !it.isMutatingTopic &&
                            it.pageData?.title == "[已出] [出] DMIT 实测降级出"
                    }
                }
            }

            val editReq = harness.forumDispatcher.recordedRequests()
                .single { it.path == "/api/content/edit-discussion" }
            val editJson = TempScriptParser.json.parseToJsonElement(editReq.body.readUtf8()).jsonObject
            assertFalse(editJson.containsKey("category"))
            assertEquals(2, editJson.getValue("rank").jsonPrimitive.int)
            val sanitizedContent = editJson.getValue("content").jsonPrimitive.content

            assertTrue(sanitizedContent.contains("198.51.*.*"))
            assertFalse(sanitizedContent.contains("198.51.100.77"))
            assertTrue(sanitizedContent.contains("Root密码: ******"))
            assertFalse(sanitizedContent.contains("pass_secret"))
            assertTrue(sanitizedContent.contains("OR****00"))
            assertFalse(sanitizedContent.contains("ORD-889900"))
            // Diff table percentages & backbone tags remain uncorrupted
            assertTrue(sanitizedContent.contains("-70.0%"))
            assertTrue(sanitizedContent.contains("CN2 GIA"))
            assertTrue(sanitizedContent.contains("对比维度"))
            assertTrue(sanitizedContent.contains("购入基线 (Before)"))
            assertTrue(sanitizedContent.contains("当前实测 (After)"))
            assertTrue(sanitizedContent.contains("增减 / 变化结论"))
            assertTrue(closed.openingFloor?.markdownBody?.contains(TradePrivacySanitizer.CLOSURE_NOTE_MARKER) == true)

            detailJob.cancel()
        }
    }

    @Test
    fun `E2E-T3-08 R9 closure invalidates FeedDiskCache and R10 refreshLocalMarketReference tracks closed sample without double prefix`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)

            val feed123Title = AtomicReference("DMIT LAX.Pro.WEE 溢价 +80 出")
            val post123Title = AtomicReference("DMIT LAX.Pro.WEE 溢价 +80 出")
            val post123Body = AtomicReference("测试 IP: 198.51.100.88")

            harness.forumDispatcher.register("GET", "/categories/trade") {
                val baseFeedHtml = E2eFixtures.loadText("nodeseek/feed_trade_page1.html")
                val injectedRow = """
                    <li class="post-list-item">
                      <div class="post-list-content">
                        <div class="post-title"><a href="/post-123-1">${feed123Title.get()}</a></div>
                        <div class="post-info">
                          <span class="info-author"><a href="/space/101">alice101</a></span>
                          <a class="post-category" href="/categories/trade">交易</a>
                          <span class="info-views"><span>640</span></span>
                          <span class="info-comments-count"><span>8</span></span>
                          <time datetime="2026-11-24T15:00:00Z"></time>
                        </div>
                      </div>
                    </li>
                """.trimIndent()
                val html = baseFeedHtml.replace("""<ul class="post-list">""", """<ul class="post-list">$injectedRow""")
                MockResponse().setResponseCode(200).setBody(html)
            }
            harness.forumDispatcher.register("GET", "/post-123-1") {
                MockResponse().setResponseCode(200).setBody(
                    buildThreadHtml(
                        postId = 123L,
                        categorySlug = "trade",
                        categoryWord = "交易",
                        title = post123Title.get(),
                        markdown = post123Body.get(),
                    ),
                )
            }
            harness.forumDispatcher.register("POST", "/api/content/edit-discussion") { req ->
                val obj = TempScriptParser.json.parseToJsonElement(req.body.clone().readUtf8()).jsonObject
                val newTitle = obj.getValue("title").jsonPrimitive.content
                post123Title.set(newTitle)
                post123Body.set(obj.getValue("content").jsonPrimitive.content)
                feed123Title.set(newTitle)
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            // 1. Seed FeedDiskCache via trade feed
            harness.forumRepository.getFeed("trade", FeedSort.LATEST_REPLY, 1).getOrThrow()

            // 2. Open topic 123 in TopicDetailViewModel; verify self-exclusion on topic 123's own card
            //    while peer-perspective local analysis sees 123 with isClosedSold == false.
            val vm = harness.createTopicDetailViewModel(topicId = 123L)
            val job = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }

            val initialState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.pageData != null && it.marketReference?.distribution != null }
                }
            }
            assertTrue(
                "Current topic 123L must be excluded from its own market reference",
                initialState.marketReference!!.distribution!!.observations.none { it.topicId == 123L },
            )
            val peerBeforeDist = harness.tradeMarketReferenceRepository.analyzeLocal("DMIT Pro").distribution!!
            val beforeObs123 = peerBeforeDist.observations.first { it.topicId == 123L }
            assertFalse(beforeObs123.isClosedSold)
            assertEquals(BigDecimal("80.00"), beforeObs123.premiumDeltaCny)

            // 3. Close trade topic via R9 (invalidates FeedDiskCache + updates server title)
            vm.closeTradeTopic(TradePrivacyOptions())
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        !it.isMutatingTopic &&
                            it.pageData?.title == "[已出] DMIT LAX.Pro.WEE 溢价 +80 出"
                    }
                }
            }
            // Wait for refreshCurrentPage()'s GET /post-123-1 + maybeAnalyzeLocalMarketReference to finish
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (harness.forumDispatcher.recordedRequests().count { it.path == "/post-123-1" } < 2) {
                        delay(20L)
                    }
                }
            }

            // 4. Reload trade feed (proves FeedDiskCache was invalidated) and verify both
            //    topic 123's own card (excluding self) and peer-perspective market reference (including closed 123).
            harness.forumRepository.getFeed("trade", FeedSort.LATEST_REPLY, 1).getOrThrow()
            vm.refreshLocalMarketReference("DMIT Pro")

            val refreshedState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { state ->
                        val ref = state.marketReference
                        ref?.query == "DMIT Pro" && ref.distribution != null
                    }
                }
            }
            val selfDist = refreshedState.marketReference!!.distribution!!
            assertTrue(
                "Current topic 123L must stay excluded from its own refreshed market reference",
                selfDist.observations.none { it.topicId == 123L },
            )
            // Peer topics only: 1001(+40), 1002(+60), 1003(0) -> median = 40.00
            assertEquals(BigDecimal("40.00"), selfDist.medianCny)

            val peerAfterDist = harness.tradeMarketReferenceRepository.analyzeLocal("DMIT Pro").distribution!!
            val afterObs123 = peerAfterDist.observations.first { it.topicId == 123L }
            assertTrue(afterObs123.isClosedSold)
            assertEquals("[已出] DMIT LAX.Pro.WEE 溢价 +80 出", afterObs123.title)
            assertEquals(BigDecimal("80.00"), afterObs123.premiumDeltaCny)
            assertFalse(afterObs123.title.contains("[已出] [已出]"))
            // Peer view includes closed 123: 1001(+40), 1002(+60), 1003(0), 123(+80) -> median of [0, 40, 60, 80] = 50.00
            assertEquals(BigDecimal("50.00"), peerAfterDist.medianCny)

            job.cancel()
        }
    }

    @Test
    fun `E2E-T3-09 R11 carpool 已满车 status update combined with R9 delivery checklist and privacy scrub via edit-discussion`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)

            val calc = (
                CarpoolCalculator.calculate(
                    CarpoolCalculationInput(
                        serviceName = "DMIT 拼车",
                        totalSeats = 4,
                        occupiedSeats = 3,
                        totalCycleCost = BigDecimal("240.00"),
                        billingCycle = CarpoolBillingCycle.QUARTERLY,
                    ),
                ) as CarpoolCalculationOutcome.Success
                ).result
            val initialBody = buildString {
                appendLine(CarpoolMarkdownGenerator.generateMarkdown(calc))
                appendLine()
                appendLine("支持 Push / 改邮交付")
                appendLine("测试IP: 198.51.100.42")
                appendLine("TG: @host_alice")
                appendLine("邮箱: owner@example.com")
            }

            val servedTitle = AtomicReference("[3=1] DMIT 拼车 Push/改邮交付")
            val servedBody = AtomicReference(initialBody)

            harness.forumDispatcher.register("GET", "/post-609-1") {
                MockResponse().setResponseCode(200).setBody(
                    buildThreadHtml(
                        postId = 609L,
                        categorySlug = "carpool",
                        categoryWord = "拼车",
                        title = servedTitle.get(),
                        markdown = servedBody.get(),
                        rank = 4,
                    ),
                )
            }
            harness.forumDispatcher.register("POST", "/api/content/edit-discussion") { req ->
                val obj = TempScriptParser.json.parseToJsonElement(req.body.clone().readUtf8()).jsonObject
                servedTitle.set(obj.getValue("title").jsonPrimitive.content)
                servedBody.set(obj.getValue("content").jsonPrimitive.content)
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            // 1. R11: Load post 609 and update status 3=1 -> 已满车 via TopicDetailViewModel
            val detailVm = harness.createTopicDetailViewModel(topicId = 609L)
            val detailJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { detailVm.uiState.collect {} }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { detailVm.uiState.first { it.openingFloor != null } }
            }

            detailVm.updateCarpoolStatus(
                statusToken = CarpoolStatusUpdater.FULL_CARPOOL_LABEL,
                occupiedSeats = 4,
                totalSeats = 4,
                updateBody = true,
            )
            val fullState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    detailVm.uiState.first {
                        !it.isMutatingTopic &&
                            it.pageData?.title == "[已满车] DMIT 拼车 Push/改邮交付" &&
                            it.openingFloor?.markdownBody?.contains("**当前状态**：`已满车`（共 4 车位，已上车 4，空余 0）") == true
                    }
                }
            }
            detailJob.cancel()

            val firstEditJson = TempScriptParser.json.parseToJsonElement(
                harness.forumDispatcher.recordedRequests().single { it.path == "/api/content/edit-discussion" }.body.readUtf8(),
            ).jsonObject
            assertEquals(4, firstEditJson.getValue("rank").jsonPrimitive.int)
            assertFalse(firstEditJson.containsKey("category"))

            // 2. R9: Infer delivery checklist & privacy scrub before committing final carpool handover state
            val updatedTitle = fullState.pageData!!.title
            val updatedBody = fullState.openingFloor!!.markdownBody
            val method = TradeDeliveryChecklistEngine.inferDeliveryMethod(updatedTitle, updatedBody)
            assertEquals(FleetDeliveryMethod.EITHER, method)
            val steps = TradeDeliveryChecklistEngine.checklistFor(method)
            assertTrue(steps.any { it.isCritical && it.id == "common_privacy_scrub" })
            val handoverMsg = TradeDeliveryChecklistEngine.buildHandoverMessage(method, updatedTitle)
            assertTrue(handoverMsg.contains("[已满车] DMIT 拼车 Push/改邮交付"))

            val sanitized = TradePrivacySanitizer.sanitize(
                markdown = updatedBody,
                options = TradePrivacyOptions(appendClosureNote = true),
            )
            assertEquals(1, sanitized.ipRedactions)
            assertEquals(1, sanitized.emailRedactions)
            assertEquals(1, sanitized.orderAndContactRedactions)
            assertTrue(sanitized.sanitizedMarkdown.contains("198.51.*.*"))
            assertTrue(sanitized.sanitizedMarkdown.contains("o***@example.com"))
            assertFalse(sanitized.sanitizedMarkdown.contains("198.51.100.42"))
            assertFalse(sanitized.sanitizedMarkdown.contains("owner@example.com"))
            assertFalse(sanitized.sanitizedMarkdown.contains("@host_alice"))
            assertTrue(sanitized.sanitizedMarkdown.contains(TradePrivacySanitizer.CLOSURE_NOTE_LINE))
            assertTrue(sanitized.sanitizedMarkdown.contains("**当前状态**：`已满车`（共 4 车位，已上车 4，空余 0）"))
            assertTrue(sanitized.sanitizedMarkdown.contains("| #1 |"))
            assertTrue(sanitized.sanitizedMarkdown.contains("| #4 |"))

            // 3. Persist scrubbed carpool body via TopicComposerViewModel(editTopicId = 609L)
            val composer = harness.createTopicComposerViewModel(editTopicId = 609L)
            val composerJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { composer.uiState.collect {} }
            val composerEvents = CopyOnWriteArrayList<TopicComposerUiEvent>()
            val composerEventJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                composer.events.collect { composerEvents.add(it) }
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { composer.uiState.first { !it.isLoadingInitialTopic && it.title.isNotEmpty() } }
            }
            composer.onMarkdownChanged(sanitized.sanitizedMarkdown)
            composer.onPublishClicked()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (composerEvents.none { it is TopicComposerUiEvent.PublishedSuccess && it.editedTopicId == 609L }) {
                        delay(20L)
                    }
                }
            }

            val editRequests = harness.forumDispatcher.recordedRequests()
                .filter { it.path == "/api/content/edit-discussion" }
            assertEquals(2, editRequests.size)
            val secondEditReq = editRequests[1]
            assertFalse(secondEditReq.getHeader("csrf-token").isNullOrBlank())
            val secondEditJson = TempScriptParser.json.parseToJsonElement(secondEditReq.body.readUtf8()).jsonObject
            assertEquals(4, secondEditJson.getValue("rank").jsonPrimitive.int)
            assertFalse(secondEditJson.containsKey("category"))
            val committedContent = secondEditJson.getValue("content").jsonPrimitive.content
            assertTrue(committedContent.contains("198.51.*.*"))
            assertFalse(committedContent.contains("198.51.100.42"))
            assertTrue(committedContent.contains("| #4 |"))

            harness.assertNoCookiesLeakedToExternalServer()
            composerJob.cancel()
            composerEventJob.cancel()
        }
    }

    @Test
    fun `E2E-T3-10 R10 valuation and market premium distribution combine with R7 baseline notes and masked target IP`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4, sessionCookie = "ns_t3_10_secret")

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

            // 1. R7: Save purchase baseline & compare with current snapshot
            val beforeEpoch = harness.clock.nowMillis()
            val afterEpoch = beforeEpoch + 30L * 86_400_000L
            val baseline = BenchmarkBaselineSnapshot(
                id = "base_t3_10",
                label = "DMIT Pro 购入基线",
                targetKey = "198.51.100.55",
                serverId = "srv_dmit",
                hostDisplay = "198.51.100.55",
                resolvedIp = "198.51.100.55",
                sourceType = BaselineSourceType.SSH_BENCHMARK,
                capturedAtEpochMillis = beforeEpoch,
                notes = "购入首测：三网 CN2 GIA，Netflix/ChatGPT 原生解锁",
                bandwidth = BaselineBandwidthMetrics(downloadMbps = 820.0, uploadMbps = 500.0),
                routing = BaselineRoutingMetrics(
                    reverseRouteSignature = "CN2 GIA",
                    reverseBackboneTags = listOf(BackboneRouteTag.CN2_GIA),
                ),
            )
            harness.benchmarkBaselineStore.saveBaseline(baseline)
            val matchedBaselines = harness.benchmarkBaselineStore.findMatchingBaselines(hostOrIp = "198.51.100.55")
            assertEquals(1, matchedBaselines.size)
            assertEquals("购入首测：三网 CN2 GIA，Netflix/ChatGPT 原生解锁", matchedBaselines.single().notes)

            val currentSnapshot = baseline.copy(
                id = "cur_t3_10",
                label = "出机前复测",
                capturedAtEpochMillis = afterEpoch,
                bandwidth = BaselineBandwidthMetrics(downloadMbps = 810.0, uploadMbps = 495.0),
            )
            val diff = BaselineDiffEngine.compare(baseline, currentSnapshot)
            val baselineCardMd = BaselineComparisonFormatter.formatMarkdown(diff, maskTargetIp = true)
            assertTrue(baselineCardMd.contains("198.51.100.*"))
            assertFalse(baselineCardMd.contains("198.51.100.55"))

            // 2. R10: Fetch Frankfurter FX (cookie-free) + VpsValuationEngine + MarketPremiumDistribution
            val fxOutcome = harness.exchangeRateRepository.ensureFreshRates()
            assertTrue(fxOutcome is FxRefreshOutcome.Success)
            val usdRate = (fxOutcome as FxRefreshOutcome.Success).state.rateFor(ValuationCurrency.USD)
            assertEquals(BigDecimal.ONE.divide(BigDecimal("0.137931"), 6, RoundingMode.HALF_UP), usdRate)
            harness.assertNoCookiesLeakedToExternalServer()

            harness.forumRepository.getFeed("trade", FeedSort.LATEST_REPLY, 1).getOrThrow()
            val marketState = harness.tradeMarketReferenceRepository.searchRemoteAndMerge("DMIT Pro")
            assertEquals(MarketSearchStatus.SUCCESS, marketState.searchStatus)
            val dist = marketState.distribution!!
            assertEquals(BigDecimal("0.00"), dist.p25Cny)
            assertEquals(BigDecimal("40.00"), dist.medianCny)
            assertEquals(BigDecimal("60.00"), dist.p75Cny)

            val valuationDt = LocalDateTime.of(2026, 11, 25, 0, 0)
            val expiryDt = LocalDateTime.of(2027, 5, 24, 0, 0)
            val valOutcome = VpsValuationEngine.calculate(
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
            assertTrue(valOutcome is VpsValuationOutcome.Success)
            val valResult = (valOutcome as VpsValuationOutcome.Success).result
            val valMd = VpsValuationEngine.formatMarkdown(valResult, ValuationMarkdownStyle.SUMMARY_CARD, "Frankfurter")
            assertTrue(valMd.contains("¥${valResult.remainingValueCny.toPlainString()}"))

            // 3. Compose trade sale post via FleetSalePostBuilder combining market ref + baseline notes + diff card
            val combinedNote = buildString {
                appendLine(matchedBaselines.single().notes)
                appendLine("同款近期市场溢价参考：P25 ¥${dist.p25Cny?.toPlainString()} / 中位 ¥${dist.medianCny?.toPlainString()} / P75 ¥${dist.p75Cny?.toPlainString()}")
                appendLine()
                appendLine(baselineCardMd)
            }
            val saleOutcome = FleetSalePostBuilder.build(
                FleetSalePostRequest(
                    asset = VpsAssetRecord(
                        id = "srv_dmit",
                        nickname = "DMIT Pro WEE",
                        providerName = "DMIT",
                        specsSummary = "1C / 1G",
                        region = "洛杉矶",
                        routeTag = "CN2 GIA",
                        renewalAmount = "36.90",
                        currency = ValuationCurrency.USD,
                        cyclePreset = BillingCyclePreset.ANNUAL,
                        expiryDateIso = "2027-05-24",
                    ),
                    fxRateToCny = usdRate,
                    fxSourceNote = "Frankfurter",
                    askingPriceCny = BigDecimal("180.00"),
                    extraNote = combinedNote,
                    valuationDateTime = valuationDt,
                ),
            )
            assertTrue(saleOutcome is FleetSalePostOutcome.Success)
            val salePost = (saleOutcome as FleetSalePostOutcome.Success).result
            assertTrue(salePost.markdownBody.contains("¥${valResult.remainingValueCny.toPlainString()}"))
            assertTrue(salePost.markdownBody.contains("中位 ¥40.00"))
            assertTrue(salePost.markdownBody.contains("购入首测：三网 CN2 GIA，Netflix/ChatGPT 原生解锁"))
            assertTrue(salePost.markdownBody.contains("198.51.100.*"))
            assertFalse(salePost.markdownBody.contains("198.51.100.55"))

            harness.assertNoCookiesLeakedToExternalServer()
        }
    }
}
