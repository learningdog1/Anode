package com.nodeseek.app.e2e.m3

import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.domain.fleet.FleetDeliveryMethod
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.trade.TradeDeliveryChecklistEngine
import com.nodeseek.app.domain.trade.TradePrivacyOptions
import com.nodeseek.app.domain.trade.TradePrivacySanitizer
import com.nodeseek.app.domain.trade.TradeTitleFormatter
import com.nodeseek.app.e2e.E2eFixtures
import com.nodeseek.app.e2e.E2eMainDispatcherRule
import com.nodeseek.app.e2e.E2eTestHarness
import com.nodeseek.app.ui.composer.PendingComposerCoinConfirm
import com.nodeseek.app.ui.composer.TopicComposerUiEvent
import com.nodeseek.app.ui.topic.TopicDetailUiEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class R9TradeClosureAndChecklistE2eTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    private fun buildTradeThreadHtml(
        postId: Long = 123L,
        title: String = "DMIT LAX.Pro.WEE 溢价 +80 出",
        markdown: String = "出 DMIT LAX.Pro.WEE，IP: 198.51.100.88",
        categorySlug: String = "trade",
        categoryWord: String = "交易",
        rank: Int = 0,
        opUid: Long = 101L,
        opName: String = "alice101",
        viewerUid: Long = 101L,
        viewerName: String = "alice101",
        page: Int = 1,
        pageCount: Int = 1,
    ): String {
        val baseHtml = E2eFixtures.loadText("nodeseek/topic_trade_op_123.html")
        val encodedMatch = Regex("""<script id="temp-script"[^>]*>(.*?)</script>""").find(baseHtml)
            ?: error("Missing temp-script in topic_trade_op_123.html")
        val decodedJson = Base64.getDecoder().decode(encodedMatch.groupValues[1]).toString(Charsets.UTF_8)
        val rootObj = TempScriptParser.json.parseToJsonElement(decodedJson).jsonObject

        val userJson = """{"member_id":$viewerUid,"member_name":${jsonStr(viewerName)},"coin":1650,"stardust":42,"rank":4}"""
        val catsJson = rootObj.getValue("allCategory").toString()
        val commentsJson = if (page == 1) {
            """[{"commentId":12300,"floorIndex":0,"poster":{"uid":$opUid,"name":${jsonStr(opName)},"avatar":"/avatar/$opUid.png","isOp":1,"roles":[]},"time":{"createdDate":"2026-11-24T15:00:00Z","createdDateFormated":"2026-11-24 23:00:00"},"markdown":${jsonStr(markdown)},"likeCount":8,"dislikeCount":0,"upvoteCount":6,"liked":0,"disliked":0,"upvoted":0,"pined":0,"hot":0,"blocked":0}]"""
        } else {
            """[{"commentId":12311,"floorIndex":11,"poster":{"uid":202,"name":"bob202","avatar":"/avatar/202.png","isOp":0,"roles":[]},"time":{"createdDate":"2026-11-24T16:00:00Z","createdDateFormated":"2026-11-25 00:00:00"},"markdown":"Page $page reply","likeCount":0,"dislikeCount":0,"upvoteCount":0,"liked":0,"disliked":0,"upvoted":0,"pined":0,"hot":0,"blocked":0}]"""
        }
        val configJson = """{"user":$userJson,"enableCustomedStyle":1,"allCategory":$catsJson,"postData":{"postId":$postId,"postPage":$page,"postPageCount":$pageCount,"title":${jsonStr(title)},"category":${jsonStr(categorySlug)},"categoryWord":${jsonStr(categoryWord)},"categoryLink":"/categories/$categorySlug","views":640,"collectionCount":5,"rank":$rank,"locked":0,"collected":0,"award":0,"op":{"uid":$opUid,"name":${jsonStr(opName)},"avatar":"/avatar/$opUid.png"},"comments":$commentsJson}}"""
        return E2eFixtures.wrapTempScriptHtml(
            configJson = configJson,
            bodyHtml = """<div id="nsk-body"><div id="nsk-body-left"><div class="nsk-post"><h1>$title</h1><article class="post-content"></article></div></div></div>""",
            title = title,
        )
    }

    private fun jsonStr(raw: String): String =
        kotlinx.serialization.json.JsonPrimitive(raw).toString()

    @Test
    fun `E2E-T1-R9-1 Page 2+ closeTradeTopic fetches Page 1 authoritatively and preserves rank while omitting category`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4, coin = 1650)

            val servedTitle = AtomicReference("DMIT LAX.Pro.WEE 溢价 +80 出")
            val servedMarkdown = AtomicReference(
                "出 DMIT LAX.Pro.WEE，IP: 198.51.100.88\n联系邮箱: seller@example.com\n订单号: ORDER-20261125-99",
            )
            val hasEdited = AtomicBoolean(false)
            val page1Calls = AtomicInteger(0)
            val page1Hits = AtomicInteger(0)

            // First Page 1 call (init prefetch) fails once so we explicitly prove closeTradeTopic()'s
            // ensurePageOneForOpEdit() fetches GET /post-123-1 authoritatively before POST edit-discussion.
            harness.forumDispatcher.register("GET", "/post-123-1") {
                if (page1Calls.getAndIncrement() == 0) {
                    MockResponse().setResponseCode(500).setBody("transient prefetch error")
                } else {
                    page1Hits.incrementAndGet()
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/html; charset=utf-8")
                        .setBody(
                            buildTradeThreadHtml(
                                postId = 123L,
                                title = servedTitle.get(),
                                markdown = servedMarkdown.get(),
                                rank = 4,
                                page = 1,
                                pageCount = 2,
                            ),
                        )
                }
            }
            harness.forumDispatcher.register("GET", "/post-123-2") {
                val body = if (hasEdited.get()) {
                    buildTradeThreadHtml(
                        postId = 123L,
                        title = servedTitle.get(),
                        markdown = servedMarkdown.get(),
                        rank = 4,
                        page = 2,
                        pageCount = 2,
                    )
                } else {
                    E2eFixtures.loadText("nodeseek/topic_trade_op_123_page2.html")
                }
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(body)
            }
            harness.forumDispatcher.register("POST", "/api/content/edit-discussion") { req ->
                val bodyStr = req.body.clone().readUtf8()
                val obj = TempScriptParser.json.parseToJsonElement(bodyStr).jsonObject
                servedTitle.set(obj.getValue("title").jsonPrimitive.content)
                servedMarkdown.set(obj.getValue("content").jsonPrimitive.content)
                hasEdited.set(true)
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"success":true}""")
            }

            val vm = harness.createTopicDetailViewModel(topicId = 123L, initialFloor = 15)
            val stateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }

            val loaded = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.pageData != null && !it.isInitialLoading }
                }
            }
            assertEquals(2, loaded.currentPage)
            assertTrue(loaded.canEditTopic)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (page1Calls.get() < 1 || harness.forumDispatcher.recordedRequests().none { it.path == "/post-123-1" }) {
                        kotlinx.coroutines.delay(10L)
                    }
                }
            }

            vm.closeTradeTopic(TradePrivacyOptions())

            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        !it.isMutatingTopic &&
                            it.pageData?.title?.startsWith(TradeTitleFormatter.SOLD_PREFIX) == true
                    }
                    while (harness.forumDispatcher.recordedRequests().count { it.path == "/post-123-2" } < 2) {
                        kotlinx.coroutines.delay(20L)
                    }
                    vm.uiState.first {
                        !it.isRefreshing &&
                            !it.isMutatingTopic &&
                            it.pageData?.title == "[已出] DMIT LAX.Pro.WEE 溢价 +80 出"
                    }
                }
            }
            assertEquals("[已出] DMIT LAX.Pro.WEE 溢价 +80 出", vm.uiState.value.pageData?.title)

            val requests = harness.forumDispatcher.recordedRequests()
            val editReqIndex = requests.indexOfFirst { it.path == "/api/content/edit-discussion" }
            assertTrue("Expected POST /api/content/edit-discussion", editReqIndex >= 0)
            val page1ReqIndices = requests.mapIndexedNotNull { idx, r ->
                if (r.method == "GET" && r.path == "/post-123-1") idx else null
            }
            assertTrue("Expected authoritative Page 1 fetch before edit-discussion", page1ReqIndices.any { it < editReqIndex })
            assertTrue(page1Hits.get() >= 1)

            val editReq = requests[editReqIndex]
            val csrf = editReq.getHeader("csrf-token").orEmpty()
            assertEquals(16, csrf.length)
            assertTrue(csrf.all { it in '0'..'9' || it in 'a'..'f' })
            assertTrue(editReq.getHeader("Cookie")?.contains("session=ns_e2e_sess") == true)
            assertTrue(editReq.getHeader("Referer")?.endsWith("/post-123-1") == true)

            val editJson = TempScriptParser.json.parseToJsonElement(editReq.body.readUtf8()).jsonObject
            assertEquals("edit-discussion", editJson.getValue("mode").jsonPrimitive.content)
            assertEquals(123L, editJson.getValue("postId").jsonPrimitive.long)
            assertEquals("[已出] DMIT LAX.Pro.WEE 溢价 +80 出", editJson.getValue("title").jsonPrimitive.content)
            assertEquals(4, editJson.getValue("rank").jsonPrimitive.int)
            assertFalse("edit-discussion MUST omit category", editJson.containsKey("category"))

            stateJob.cancel()
        }
    }

    @Test
    fun `E2E-T1-R9-2 TradePrivacySanitizer masks IPv4 IPv6 email credentials and order contacts while preserving DNS RFC1918 nsapp and ANSI`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)

            val originalMarkdown = buildString {
                appendLine("出 DMIT LAX.Pro.WEE，测试机如下：")
                appendLine("公网 IPv4: 198.51.100.88")
                appendLine("公网 IPv6: 2a01:4f8:c012:1234::1")
                appendLine("内网管理: 192.168.1.10 · DNS: 1.1.1.1")
                appendLine("联系邮箱: seller@example.com")
                appendLine("Root密码: MyPass!123")
                appendLine("订单号: ORDER-20261125-99 · TG: @alice_trade")
                appendLine()
                appendLine("[投喂星尘](nsapp://stardust-receive?uid=101&amount=10)")
                appendLine()
                appendLine("```ansi")
                appendLine("\u001b[32mCN2 GIA 延迟 138ms\u001b[0m")
                appendLine("```")
            }

            val servedTitle = AtomicReference("DMIT LAX.Pro.WEE 溢价 +80 出")
            val servedMarkdown = AtomicReference(originalMarkdown)

            harness.forumDispatcher.register("GET", "/post-123-1") {
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        buildTradeThreadHtml(
                            title = servedTitle.get(),
                            markdown = servedMarkdown.get(),
                            rank = 2,
                        ),
                    )
            }
            harness.forumDispatcher.register("POST", "/api/content/edit-discussion") { req ->
                val obj = TempScriptParser.json.parseToJsonElement(req.body.clone().readUtf8()).jsonObject
                servedTitle.set(obj.getValue("title").jsonPrimitive.content)
                servedMarkdown.set(obj.getValue("content").jsonPrimitive.content)
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            val vm = harness.createTopicDetailViewModel(topicId = 123L)
            val stateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }

            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.openingFloor != null } }
            }

            vm.closeTradeTopic(TradePrivacyOptions())

            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        !it.isMutatingTopic &&
                            it.pageData?.title?.startsWith("[已出]") == true
                    }
                }
            }

            val editReq = harness.forumDispatcher.recordedRequests()
                .first { it.path == "/api/content/edit-discussion" }
            val sentContent = TempScriptParser.json.parseToJsonElement(editReq.body.readUtf8())
                .jsonObject.getValue("content").jsonPrimitive.content

            assertTrue(sentContent.contains("198.51.*.*"))
            assertFalse(sentContent.contains("198.51.100.88"))
            assertTrue(sentContent.contains("2a01:4f8:*:*"))
            assertFalse(sentContent.contains("2a01:4f8:c012:1234::1"))
            assertTrue(sentContent.contains("192.168.1.10"))
            assertTrue(sentContent.contains("1.1.1.1"))
            assertTrue(sentContent.contains("s***@example.com"))
            assertFalse(sentContent.contains("seller@example.com"))
            assertTrue(sentContent.contains("Root密码: ******"))
            assertFalse(sentContent.contains("MyPass!123"))
            assertTrue(sentContent.contains("OR****99"))
            assertFalse(sentContent.contains("ORDER-20261125-99"))
            assertTrue(sentContent.contains("@a****de"))
            assertFalse(sentContent.contains("@alice_trade"))
            assertTrue(sentContent.contains("nsapp://stardust-receive?uid=101&amount=10"))
            assertTrue(sentContent.contains("\u001b[32mCN2 GIA 延迟 138ms\u001b[0m"))
            assertTrue(sentContent.contains(TradePrivacySanitizer.CLOSURE_NOTE_MARKER))

            // Idempotency check on second pass
            val secondPass = TradePrivacySanitizer.sanitize(sentContent, TradePrivacyOptions())
            assertFalse(secondPass.changed)
            assertEquals(0, secondPass.redactionCount)
            assertFalse(secondPass.closureNoteAppended)
            assertEquals(sentContent, secondPass.sanitizedMarkdown)

            stateJob.cancel()
        }
    }

    @Test
    fun `E2E-T1-R9-3 TradeDeliveryChecklistEngine infers all 4 delivery methods generates handover template and pairs with closeTradeTopic`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)

            val cases = listOf(
                Triple("[出] DMIT Pro 支持站内 Push", "走商家后台 Push 过户", FleetDeliveryMethod.PUSH),
                Triple("[出] 瓦工 MEGABOX 改邮出", "协助改邮到买家邮箱", FleetDeliveryMethod.CHANGE_EMAIL),
                Triple("[出] RackNerd 带原油出", "附带原始邮箱完整交接", FleetDeliveryMethod.ORIGINAL_EMAIL),
                Triple("[出] Claw HK Push 或改邮均可", "支持 push 也可改邮交付", FleetDeliveryMethod.EITHER),
            )

            cases.forEach { (title, body, expectedMethod) ->
                val inferred = TradeDeliveryChecklistEngine.inferDeliveryMethod(title, body)
                assertEquals("Unexpected method for $title", expectedMethod, inferred)

                val steps = TradeDeliveryChecklistEngine.checklistFor(inferred)
                assertTrue(steps.size >= 5)
                assertEquals(steps.size, steps.map { it.id }.toSet().size)
                val criticalIds = steps.filter { it.isCritical }.map { it.id }.toSet()
                assertTrue("common_buyer_verify" in criticalIds)
                assertTrue("common_privacy_scrub" in criticalIds)
                assertEquals("common_mark_sold", steps.last().id)

                val soldTitle = TradeTitleFormatter.applySoldPrefix(title)
                val handoverMsg = TradeDeliveryChecklistEngine.buildHandoverMessage(inferred, soldTitle)
                val cleanTitle = TradeTitleFormatter.removeSoldPrefix(soldTitle)
                assertTrue(handoverMsg.contains(cleanTitle))
                assertFalse(handoverMsg.contains("《[已出]"))
            }

            // Pair checklist flow with live closeTradeTopic()
            val servedTitle = AtomicReference("[出] DMIT Pro 支持站内 Push")
            val servedBody = AtomicReference("走商家后台 Push 过户，测试 IP: 203.0.113.50")
            harness.forumDispatcher.register("GET", "/post-123-1") {
                MockResponse().setResponseCode(200).setBody(
                    buildTradeThreadHtml(title = servedTitle.get(), markdown = servedBody.get()),
                )
            }
            harness.forumDispatcher.register("POST", "/api/content/edit-discussion") { req ->
                val obj = TempScriptParser.json.parseToJsonElement(req.body.clone().readUtf8()).jsonObject
                servedTitle.set(obj.getValue("title").jsonPrimitive.content)
                servedBody.set(obj.getValue("content").jsonPrimitive.content)
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            val vm = harness.createTopicDetailViewModel(topicId = 123L)
            val stateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.openingFloor != null } }
            }

            vm.closeTradeTopic(TradePrivacyOptions())
            val finalState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.isMutatingTopic && it.pageData?.title?.startsWith("[已出]") == true }
                }
            }
            assertEquals("[已出] [出] DMIT Pro 支持站内 Push", finalState.pageData?.title)
            stateJob.cancel()
        }
    }

    @Test
    fun `E2E-T1-R9-4 closeTradeTopic updates local UI state immediately emits ShowMessage and invalidates ForumRepository feed cache`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)

            val feedTitle = AtomicReference("DMIT LAX.Pro.WEE 溢价 +80 出")
            val postTitle = AtomicReference("DMIT LAX.Pro.WEE 溢价 +80 出")
            val postMarkdown = AtomicReference("出 DMIT，测试 IP: 198.51.100.88")
            val feedFetchCount = AtomicInteger(0)
            val page1FetchCount = AtomicInteger(0)

            harness.forumDispatcher.register("GET", "/categories/trade") {
                val count = feedFetchCount.incrementAndGet()
                val html = E2eFixtures.loadText("nodeseek/feed_trade_page1.html")
                    .replace("DMIT LAX.Pro.Wee 溢价 +40", "${feedTitle.get()} #$count")
                MockResponse().setResponseCode(200).setBody(html)
            }
            harness.forumDispatcher.register("GET", "/post-123-1") {
                val n = page1FetchCount.incrementAndGet()
                // Simulate delayed refresh GET on the post-edit refresh call (n >= 2)
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        buildTradeThreadHtml(title = postTitle.get(), markdown = postMarkdown.get()),
                    )
                    .apply {
                        if (n >= 2) {
                            setHeadersDelay(150L, TimeUnit.MILLISECONDS)
                        }
                    }
            }
            harness.forumDispatcher.register("POST", "/api/content/edit-discussion") { req ->
                val obj = TempScriptParser.json.parseToJsonElement(req.body.clone().readUtf8()).jsonObject
                postTitle.set(obj.getValue("title").jsonPrimitive.content)
                postMarkdown.set(obj.getValue("content").jsonPrimitive.content)
                feedTitle.set(obj.getValue("title").jsonPrimitive.content)
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            // Warm feed cache before closure
            val firstFeed = harness.forumRepository.getFeed(categorySlug = "trade", sort = FeedSort.LATEST_REPLY, page = 1).getOrThrow()
            assertEquals(1, feedFetchCount.get())
            assertTrue(firstFeed.topics.any { it.title.contains("#1") })

            val vm = harness.createTopicDetailViewModel(topicId = 123L)
            val stateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
            val events = CopyOnWriteArrayList<TopicDetailUiEvent>()
            val eventJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.events.collect { events.add(it) } }

            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.openingFloor != null } }
            }

            vm.closeTradeTopic(TradePrivacyOptions())

            // Verify immediate local state update while refreshCurrentPage() may still be completing
            val closedState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        !it.isMutatingTopic &&
                            it.pageData?.title?.startsWith("[已出]") == true &&
                            it.openingFloor?.markdownBody?.contains(TradePrivacySanitizer.CLOSURE_NOTE_MARKER) == true
                    }
                }
            }
            assertTrue(closedState.pageData?.title?.startsWith("[已出]") == true)
            assertTrue(closedState.openingFloor?.markdownBody?.contains("198.51.*.*") == true)

            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (events.none { it is TopicDetailUiEvent.ShowMessage && it.message.contains("已标记") }) {
                        kotlinx.coroutines.delay(20L)
                    }
                }
            }

            // Verify feed cache was invalidated so next getFeed hits network rather than serving stale cache
            val refreshedFeed = harness.forumRepository.getFeed(categorySlug = "trade", sort = FeedSort.LATEST_REPLY, page = 1).getOrThrow()
            assertEquals(2, feedFetchCount.get())
            assertTrue(refreshedFeed.topics.any { it.title.contains("[已出]") && it.title.contains("#2") })

            stateJob.cancel()
            eventJob.cancel()
        }
    }

    @Test
    fun `E2E-T1-R9-5 closeTradeTopic preserves rank=255 and TopicComposerViewModel enforces 10-coin confirm when upgrading to rank=255`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4, coin = 1650)

            // Part A: closeTradeTopic on an already-private (rank=255) trade topic preserves rank=255
            val privateTitle = AtomicReference("DMIT Private 溢价 +80 出")
            val privateBody = AtomicReference("IP: 198.51.100.88")
            harness.forumDispatcher.register("GET", "/post-123-1") {
                MockResponse().setResponseCode(200).setBody(
                    buildTradeThreadHtml(
                        postId = 123L,
                        title = privateTitle.get(),
                        markdown = privateBody.get(),
                        rank = 255,
                    ),
                )
            }
            harness.forumDispatcher.register("POST", "/api/content/edit-discussion") { req ->
                val obj = TempScriptParser.json.parseToJsonElement(req.body.clone().readUtf8()).jsonObject
                privateTitle.set(obj.getValue("title").jsonPrimitive.content)
                privateBody.set(obj.getValue("content").jsonPrimitive.content)
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            val detailVm = harness.createTopicDetailViewModel(topicId = 123L)
            val detailJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { detailVm.uiState.collect {} }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { detailVm.uiState.first { it.openingFloor != null } }
            }
            detailVm.closeTradeTopic(TradePrivacyOptions())
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    detailVm.uiState.first { !it.isMutatingTopic && it.pageData?.title?.startsWith("[已出]") == true }
                }
            }
            val firstEditJson = TempScriptParser.json.parseToJsonElement(
                harness.forumDispatcher.recordedRequests().last { it.path == "/api/content/edit-discussion" }.body.readUtf8(),
            ).jsonObject
            assertEquals(255, firstEditJson.getValue("rank").jsonPrimitive.int)
            assertFalse(firstEditJson.containsKey("category"))
            detailJob.cancel()

            // Part B: Editing a public (rank=0) post to rank=255 in TopicComposerViewModel requires 10-coin confirmation
            harness.forumDispatcher.clearRecordedRequests()
            harness.forumDispatcher.register("GET", "/post-124-1") {
                MockResponse().setResponseCode(200).setBody(
                    buildTradeThreadHtml(
                        postId = 124L,
                        title = "DMIT Public 溢价 +50 出",
                        markdown = "Public body",
                        rank = 0,
                    ),
                )
            }
            val composer = harness.createTopicComposerViewModel(editTopicId = 124L)
            val composerJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { composer.uiState.collect {} }
            val composerEvents = CopyOnWriteArrayList<TopicComposerUiEvent>()
            val composerEventJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                composer.events.collect { composerEvents.add(it) }
            }

            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    composer.uiState.first { !it.isLoadingInitialTopic && it.title.isNotEmpty() }
                }
            }

            composer.selectRequiredViewLevel(255)
            composer.onTitleChanged("[已出] DMIT Public 溢价 +50 出")
            assertTrue(composer.uiState.value.willIncurPrivateUpgradeFee)

            composer.onPublishClicked()
            val confirmState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    composer.uiState.first { it.pendingCoinConfirm != null }
                }
            }
            assertEquals(PendingComposerCoinConfirm.EditChangeToPrivate, confirmState.pendingCoinConfirm)
            assertEquals(10, confirmState.pendingCoinConfirm?.coinCost)
            assertTrue(
                "Zero edit-discussion writes allowed before coin confirmation",
                harness.forumDispatcher.recordedRequests().none { it.path == "/api/content/edit-discussion" },
            )

            // Dismissing keeps zero network writes
            composer.dismissCoinConfirm()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { composer.uiState.first { it.pendingCoinConfirm == null } }
            }
            assertTrue(harness.forumDispatcher.recordedRequests().none { it.path == "/api/content/edit-discussion" })

            // Re-submit & confirm 10-coin spend
            composer.onPublishClicked()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { composer.uiState.first { it.pendingCoinConfirm == PendingComposerCoinConfirm.EditChangeToPrivate } }
            }
            composer.confirmCoinSpendAndPublish()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { composer.uiState.first { it.hasPublished && !it.isSubmitting } }
            }

            val secondEditReq = harness.forumDispatcher.recordedRequests()
                .single { it.path == "/api/content/edit-discussion" }
            val secondEditJson = TempScriptParser.json.parseToJsonElement(secondEditReq.body.readUtf8()).jsonObject
            assertEquals(124L, secondEditJson.getValue("postId").jsonPrimitive.long)
            assertEquals(255, secondEditJson.getValue("rank").jsonPrimitive.int)
            assertEquals("[已出] DMIT Public 溢价 +50 出", secondEditJson.getValue("title").jsonPrimitive.content)
            assertFalse(secondEditJson.containsKey("category"))

            composerJob.cancel()
            composerEventJob.cancel()
        }
    }

    @Test
    fun `E2E-T2-R9-1 non-OP on trade post and OP on non-trade post are blocked from closeTradeTopic with zero network writes`() = runBlocking {
        E2eTestHarness().use { harness ->
            // Case 1: Viewer uid=202 != OP uid=101 on trade post
            harness.seedAuthenticatedSession(uid = 202L, username = "bob202", rank = 3)
            harness.forumDispatcher.register("GET", "/post-123-1") {
                MockResponse().setResponseCode(200).setBody(
                    buildTradeThreadHtml(
                        postId = 123L,
                        categorySlug = "trade",
                        categoryWord = "交易",
                        opUid = 101L,
                        opName = "alice101",
                        viewerUid = 202L,
                        viewerName = "bob202",
                    ),
                )
            }
            harness.forumDispatcher.register("POST", "/api/content/edit-discussion") {
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            val nonOpVm = harness.createTopicDetailViewModel(topicId = 123L)
            val job1 = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { nonOpVm.uiState.collect {} }
            val state1 = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { nonOpVm.uiState.first { it.openingFloor != null } }
            }
            assertFalse(state1.canEditTopic)
            nonOpVm.closeTradeTopic(TradePrivacyOptions())
            kotlinx.coroutines.delay(150L)
            assertTrue(harness.forumDispatcher.recordedRequests().none { it.path == "/api/content/edit-discussion" })
            job1.cancel()

            // Case 2: Viewer uid=101 == OP uid=101 on tech post (non-trade)
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)
            harness.forumDispatcher.clearRecordedRequests()
            harness.forumDispatcher.register("GET", "/post-125-1") {
                MockResponse().setResponseCode(200).setBody(
                    buildTradeThreadHtml(
                        postId = 125L,
                        title = "Linux 网络调优记录",
                        categorySlug = "tech",
                        categoryWord = "技术",
                        opUid = 101L,
                        opName = "alice101",
                        viewerUid = 101L,
                        viewerName = "alice101",
                    ),
                )
            }
            val techVm = harness.createTopicDetailViewModel(topicId = 125L)
            val job2 = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { techVm.uiState.collect {} }
            val state2 = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { techVm.uiState.first { it.openingFloor != null } }
            }
            assertTrue(state2.canEditTopic)
            techVm.closeTradeTopic(TradePrivacyOptions())
            kotlinx.coroutines.delay(150L)
            assertTrue(harness.forumDispatcher.recordedRequests().none { it.path == "/api/content/edit-discussion" })
            job2.cancel()
        }
    }

    @Test
    fun `E2E-T2-R9-2 TradeTitleFormatter and closeTradeTopic avoid double sold prefix across all marker variants`() = runBlocking {
        val variants = listOf(
            "[已出] DMIT Pro",
            "【已出】DMIT Pro",
            "[SOLD] RackNerd",
            "DMIT Pro [已出]",
            "[已成交] 瓦工 MEGABOX",
        )
        variants.forEach { title ->
            assertTrue("Expected isMarkedSold=true for $title", TradeTitleFormatter.isMarkedSold(title))
            assertEquals("Expected no double prefix for $title", title, TradeTitleFormatter.applySoldPrefix(title))
        }
        assertEquals("[已出] DMIT Pro", TradeTitleFormatter.applySoldPrefix("DMIT Pro"))
        assertEquals("[已出]", TradeTitleFormatter.applySoldPrefix("   "))

        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)
            val alreadySoldTitle = "[已出] DMIT LAX.Pro.WEE 溢价 +80 出"
            val servedTitle = AtomicReference(alreadySoldTitle)
            val servedBody = AtomicReference("仅记录：无敏感信息")

            harness.forumDispatcher.register("GET", "/post-123-1") {
                MockResponse().setResponseCode(200).setBody(
                    buildTradeThreadHtml(title = servedTitle.get(), markdown = servedBody.get()),
                )
            }
            harness.forumDispatcher.register("POST", "/api/content/edit-discussion") { req ->
                val obj = TempScriptParser.json.parseToJsonElement(req.body.clone().readUtf8()).jsonObject
                servedTitle.set(obj.getValue("title").jsonPrimitive.content)
                servedBody.set(obj.getValue("content").jsonPrimitive.content)
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            val vm = harness.createTopicDetailViewModel(topicId = 123L)
            val job = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.openingFloor != null } }
            }

            vm.closeTradeTopic(TradePrivacyOptions())
            val finalState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        !it.isMutatingTopic &&
                            it.openingFloor?.markdownBody?.contains(TradePrivacySanitizer.CLOSURE_NOTE_MARKER) == true
                    }
                }
            }
            assertEquals(alreadySoldTitle, finalState.pageData?.title)
            assertFalse(finalState.pageData?.title?.contains("[已出] [已出]") == true)
            job.cancel()
        }
    }

    @Test
    fun `E2E-T2-R9-3 edit-discussion wire payload contains exact schema keys and strictly omits category`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)
            harness.forumDispatcher.register("GET", "/post-123-1") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.loadText("nodeseek/topic_trade_op_123.html"))
            }
            harness.forumDispatcher.register("POST", "/api/content/edit-discussion") {
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            val vm = harness.createTopicDetailViewModel(topicId = 123L)
            val job = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.openingFloor != null } }
            }

            vm.closeTradeTopic(TradePrivacyOptions())
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (harness.forumDispatcher.recordedRequests().none { it.path == "/api/content/edit-discussion" }) {
                        kotlinx.coroutines.delay(20L)
                    }
                }
            }

            val req = harness.forumDispatcher.recordedRequests().single { it.path == "/api/content/edit-discussion" }
            val rawBody = req.body.readUtf8()
            val jsonObj = TempScriptParser.json.parseToJsonElement(rawBody).jsonObject
            assertFalse(jsonObj.containsKey("category"))
            assertEquals(setOf("mode", "postId", "title", "content", "rank"), jsonObj.keys)
            assertEquals("edit-discussion", jsonObj.getValue("mode").jsonPrimitive.content)
            job.cancel()
        }
    }

    @Test
    fun `E2E-T2-R9-4 edit-discussion business failure and 403 Cloudflare challenge roll back cleanly without optimistic title corruption`() = runBlocking {
        // Case 1: HTTP 200 {"success":false,"message":"操作频繁"}
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)
            val originalTitle = "DMIT LAX.Pro.WEE 溢价 +80 出"
            val originalBody = "未结帖正文 198.51.100.88"
            harness.forumDispatcher.register("GET", "/post-123-1") {
                MockResponse().setResponseCode(200).setBody(
                    buildTradeThreadHtml(title = originalTitle, markdown = originalBody),
                )
            }
            harness.forumDispatcher.register("POST", "/api/content/edit-discussion") {
                MockResponse().setResponseCode(200).setBody("""{"success":false,"message":"操作频繁"}""")
            }

            val vm = harness.createTopicDetailViewModel(topicId = 123L)
            val stateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
            val events = CopyOnWriteArrayList<TopicDetailUiEvent>()
            val eventJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.events.collect { events.add(it) } }

            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.openingFloor != null } }
            }

            vm.closeTradeTopic(TradePrivacyOptions())
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (events.none { it is TopicDetailUiEvent.ShowMessage && it.message.contains("操作频繁") }) {
                        kotlinx.coroutines.delay(20L)
                    }
                }
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { !it.isMutatingTopic } }
            }
            val afterError = vm.uiState.value
            assertFalse(afterError.isMutatingTopic)
            assertEquals(originalTitle, afterError.pageData?.title)
            assertEquals(originalBody, afterError.openingFloor?.markdownBody)
            stateJob.cancel()
            eventJob.cancel()
        }

        // Case 2: HTTP 403 Cloudflare challenge
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)
            val originalTitle = "DMIT LAX.Pro.WEE 溢价 +80 出"
            val originalBody = "未结帖正文 198.51.100.88"
            val cfHtml = E2eFixtures.loadText("nodeseek/cf_challenge_403.html")
            harness.forumDispatcher.register("GET", "/post-123-1") {
                MockResponse().setResponseCode(200).setBody(
                    buildTradeThreadHtml(title = originalTitle, markdown = originalBody),
                )
            }
            harness.forumDispatcher.register("POST", "/api/content/edit-discussion") {
                MockResponse()
                    .setResponseCode(403)
                    .setHeader("cf-mitigated", "challenge")
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(cfHtml)
            }

            val vm = harness.createTopicDetailViewModel(topicId = 123L)
            val stateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
            val events = CopyOnWriteArrayList<TopicDetailUiEvent>()
            val eventJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.events.collect { events.add(it) } }

            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.openingFloor != null } }
            }

            vm.closeTradeTopic(TradePrivacyOptions())
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (events.none { it is TopicDetailUiEvent.RequestWebViewAuth }) {
                        kotlinx.coroutines.delay(20L)
                    }
                }
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { !it.isMutatingTopic } }
            }
            val afterCf = vm.uiState.value
            assertFalse(afterCf.isMutatingTopic)
            assertEquals(originalTitle, afterCf.pageData?.title)
            assertEquals(originalBody, afterCf.openingFloor?.markdownBody)
            stateJob.cancel()
            eventJob.cancel()
        }
    }

    @Test
    fun `E2E-T2-R9-5 TradePrivacySanitizer preserves URLs currency handles and quoted secrets safely`() = runBlocking {
        val input = buildString {
            appendLine("![bench](https://203.0.113.42/img/alice@2026.png)")
            appendLine("[续费](https://www.nodeseek.com/post-1-1)")
            appendLine("价格: ¥120 联系: @alice101 频道: https://t.me/public_channel")
            appendLine("[已出] 标题")
            appendLine("裸IP: 198.51.100.77 裸邮箱: test@example.org")
            appendLine("""密码: "hunter2"""")
        }

        val first = TradePrivacySanitizer.sanitize(input, TradePrivacyOptions())
        val out = first.sanitizedMarkdown

        assertTrue(out.contains("![bench](https://203.0.113.42/img/alice@2026.png)"))
        assertTrue(out.contains("[续费](https://www.nodeseek.com/post-1-1)"))
        assertTrue(out.contains("¥120"))
        assertTrue(out.contains("@alice101"))
        assertTrue(out.contains("https://t.me/public_channel"))
        assertTrue(out.contains("[已出] 标题"))

        assertTrue(out.contains("198.51.*.*"))
        assertFalse(out.contains("198.51.100.77"))
        assertTrue(out.contains("t***@example.org"))
        assertFalse(out.contains("test@example.org"))
        assertTrue(out.contains("""密码: "******""""))
        assertFalse(out.contains("hunter2"))
        assertTrue(first.closureNoteAppended)

        val second = TradePrivacySanitizer.sanitize(out, TradePrivacyOptions())
        assertEquals(0, second.redactionCount)
        assertFalse(second.changed)
        assertEquals(out, second.sanitizedMarkdown)

        // Also run through TopicDetailViewModel.closeTradeTopic() end-to-end
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)
            harness.forumDispatcher.register("GET", "/post-123-1") {
                MockResponse().setResponseCode(200).setBody(
                    buildTradeThreadHtml(title = "[已出] 标题", markdown = input),
                )
            }
            harness.forumDispatcher.register("POST", "/api/content/edit-discussion") {
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            val vm = harness.createTopicDetailViewModel(topicId = 123L)
            val job = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.openingFloor != null } }
            }
            vm.closeTradeTopic(TradePrivacyOptions())
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (harness.forumDispatcher.recordedRequests().none { it.path == "/api/content/edit-discussion" }) {
                        kotlinx.coroutines.delay(20L)
                    }
                }
            }
            val wireContent = TempScriptParser.json.parseToJsonElement(
                harness.forumDispatcher.recordedRequests().single { it.path == "/api/content/edit-discussion" }.body.readUtf8(),
            ).jsonObject.getValue("content").jsonPrimitive.content
            assertTrue(wireContent.contains("![bench](https://203.0.113.42/img/alice@2026.png)"))
            assertFalse(wireContent.contains("hunter2"))
            job.cancel()
        }
    }

    @Test
    fun `E2E-T2-R9-6 TradePrivacySanitizer masks compound keys emoji-prefixed credentials keycap boundaries in linear time via closeTradeTopic`() = runBlocking {
        val compoundInput = buildString {
            appendLine("测试密码: TestPass111")
            appendLine("临时密码: TempPass222")
            appendLine("VPS密码: VpsPass333")
            appendLine("管理密码: AdminPass444")
            appendLine("密码: 🔑EmojiStartSecret")
            appendLine("密码: Mid1🔑Mid2Secret")
            appendLine("密码: 👨‍👩‍👧‍👦FamilySecret")
            appendLine("密码: 1️⃣KeycapSecret")
            appendLine("1️⃣密码：hunter2")
            appendLine("🔑TG：my_handle")
            appendLine("密码：hunter21️⃣TG：my_handle")
            appendLine("密码：🔑TG：my_handle")
        }

        val sanitized = TradePrivacySanitizer.sanitize(compoundInput, TradePrivacyOptions())
        val out = sanitized.sanitizedMarkdown
        assertTrue(sanitized.changed)
        assertTrue(out.contains("测试密码: ******"))
        assertTrue(out.contains("临时密码: ******"))
        assertTrue(out.contains("VPS密码: ******"))
        assertTrue(out.contains("管理密码: ******"))
        assertTrue(out.contains("1️⃣密码：******"))
        assertTrue(out.contains("🔑TG：my****le"))
        assertTrue(out.contains("密码：******1️⃣TG：my****le"))
        assertTrue(out.contains("密码：******TG：my****le"))
        listOf(
            "TestPass111",
            "TempPass222",
            "VpsPass333",
            "AdminPass444",
            "EmojiStartSecret",
            "Mid1🔑Mid2Secret",
            "FamilySecret",
            "KeycapSecret",
            "hunter2",
            "my_handle",
        ).forEach { secret ->
            assertFalse("Secret leaked after sanitization: $secret", out.contains(secret))
        }
        assertTrue(out.contains(TradePrivacySanitizer.CLOSURE_NOTE_MARKER))

        // Idempotency check
        val secondPass = TradePrivacySanitizer.sanitize(out, TradePrivacyOptions())
        assertFalse(secondPass.changed)
        assertEquals(0, secondPass.redactionCount)
        assertEquals(out, secondPass.sanitizedMarkdown)

        // 20,000-emoji linear runtime check (< 250ms)
        val stressInput = "密码: " + "🔑".repeat(20_000)
        val startNs = System.nanoTime()
        val stressResult = TradePrivacySanitizer.sanitize(
            stressInput,
            TradePrivacyOptions(appendClosureNote = false),
        )
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertEquals("密码: ******", stressResult.sanitizedMarkdown)
        assertTrue("Expected linear runtime < 250ms, took ${elapsedMs}ms", elapsedMs < 250L)

        // End-to-end TopicDetailViewModel.closeTradeTopic()
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)
            val wireInput = buildString {
                appendLine("测试密码: TestPass111")
                appendLine("VPS密码: VpsPass333")
                appendLine("密码: 🔑EmojiStartSecret")
                appendLine("1️⃣密码：hunter2")
                appendLine("🔑TG：my_handle")
            }
            harness.forumDispatcher.register("GET", "/post-123-1") {
                MockResponse().setResponseCode(200).setBody(
                    buildTradeThreadHtml(
                        postId = 123L,
                        title = "DMIT Pro 出",
                        markdown = wireInput,
                        rank = 5,
                    ),
                )
            }
            harness.forumDispatcher.register("POST", "/api/content/edit-discussion") {
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            val vm = harness.createTopicDetailViewModel(topicId = 123L)
            val job = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.openingFloor != null } }
            }

            vm.closeTradeTopic(TradePrivacyOptions())
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (harness.forumDispatcher.recordedRequests().none { it.path == "/api/content/edit-discussion" }) {
                        kotlinx.coroutines.delay(20L)
                    }
                }
            }

            val editObj = TempScriptParser.json.parseToJsonElement(
                harness.forumDispatcher.recordedRequests()
                    .single { it.path == "/api/content/edit-discussion" }
                    .body.readUtf8(),
            ).jsonObject
            assertEquals("[已出] DMIT Pro 出", editObj.getValue("title").jsonPrimitive.content)
            assertEquals(5, editObj.getValue("rank").jsonPrimitive.int)
            val wireContent = editObj.getValue("content").jsonPrimitive.content
            assertTrue(wireContent.contains("测试密码: ******"))
            assertTrue(wireContent.contains("VPS密码: ******"))
            assertTrue(wireContent.contains("1️⃣密码：******"))
            assertTrue(wireContent.contains("🔑TG：my****le"))
            assertFalse(wireContent.contains("TestPass111"))
            assertFalse(wireContent.contains("VpsPass333"))
            assertFalse(wireContent.contains("EmojiStartSecret"))
            assertFalse(wireContent.contains("hunter2"))
            assertFalse(wireContent.contains("my_handle"))
            job.cancel()
        }
    }
}
