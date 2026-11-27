package com.nodeseek.app.e2e.m1

import com.nodeseek.app.data.network.computeDynamicSign
import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.domain.model.NsAppWidgetTarget
import com.nodeseek.app.domain.model.StardustTipTarget
import com.nodeseek.app.domain.model.parseNsAppWidgetTarget
import com.nodeseek.app.e2e.E2eFixtures
import com.nodeseek.app.e2e.E2eMainDispatcherRule
import com.nodeseek.app.e2e.E2eTestHarness
import com.nodeseek.app.ui.components.MdBlock
import com.nodeseek.app.ui.components.dispatchMarkdownUrlClick
import com.nodeseek.app.ui.components.parseNodeSeekMarkdownBlocks
import com.nodeseek.app.ui.composer.TopicComposerUiEvent
import com.nodeseek.app.ui.composer.TopicComposerViewModel
import com.nodeseek.app.ui.topic.TopicDetailUiEvent
import com.nodeseek.app.ui.topic.TopicDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class R1PollAndStardustE2eTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    @Test
    fun `E2E-T1-R1-1 parse nsapp vote widget from thread markdown and render PollDetail counts and share percentages`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(
                uid = 101L,
                username = "alice101",
                rank = 4,
                coin = 1650,
                stardust = 42,
            )
            val topicHtml = E2eFixtures.loadText("nodeseek/topic_trade_op_123.html")
            val voteJson = E2eFixtures.loadText("nodeseek/vote_info_42.json")

            harness.forumDispatcher.register("GET", "/post-123-1") {
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(topicHtml)
            }
            harness.forumDispatcher.register("GET", "/api/vote/info/42") {
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(voteJson)
            }

            val detail = harness.forumRepository.getTopicDetail(123L).getOrThrow()
            val openingMarkdown = detail.openingFloor?.markdownBody.orEmpty()
            val blocks = parseNodeSeekMarkdownBlocks(openingMarkdown)
            val voteWidgets = blocks.filterIsInstance<MdBlock.SpecialWidget>()
                .mapNotNull { widget ->
                    (parseNsAppWidgetTarget(widget.url) as? NsAppWidgetTarget.Vote)?.let { widget to it }
                }
            assertEquals(1, voteWidgets.size)
            assertEquals("选出你最常用的 VPS 系统", voteWidgets.single().first.title)
            assertEquals(42L, voteWidgets.single().second.voteId)

            val container = harness.buildAppContainer()
            val vm = harness.manage(TopicDetailViewModel(container = container, topicId = 123L))
            val collectJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                vm.uiState.collect {}
            }

            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.pageData != null }
                }
            }

            vm.nsAppWidgetController.ensureVoteLoaded(42L)
            val pollState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.nsAppWidgetController.pollStateFor(42L).first { it.poll != null && !it.isLoading }
                }
            }

            val poll = pollState.poll!!
            assertEquals(42L, poll.id)
            assertEquals("你最常用的 VPS 系统？", poll.title)
            assertEquals(101L, poll.creatorUid)
            assertTrue(poll.isPublic)
            assertFalse(poll.isLocked)
            assertFalse(poll.isMultiple)
            assertEquals(3, poll.options.size)
            assertEquals(30L, poll.totalVotes)
            assertTrue(poll.hasCurrentUserVoted)
            assertTrue(poll.hasVisibleCounts)
            assertEquals(60, poll.sharePercent(poll.options[0]))
            assertEquals(23, poll.sharePercent(poll.options[1]))
            assertEquals(17, poll.sharePercent(poll.options[2]))

            val voteReq = harness.forumDispatcher.recordedRequests()
                .first { it.path == "/api/vote/info/42" }
            val expectedSign = computeDynamicSign(
                method = "GET",
                fullUrl = voteReq.requestUrl.toString(),
                userAgent = harness.testUserAgent,
                rawBody = "",
            )
            assertEquals(40, voteReq.getHeader("x-dynamic-sign")?.length)
            assertEquals(expectedSign, voteReq.getHeader("x-dynamic-sign"))
            collectJob.cancel()
        }
    }

    @Test
    fun `E2E-T1-R1-2 single-choice poll mutual exclusion and castVote submission refreshes counts`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(
                uid = 202L,
                username = "bob202",
                rank = 3,
                coin = 500,
                stardust = 30,
            )
            val topicHtml = E2eFixtures.loadText("nodeseek/topic_trade_op_123.html")
            val preVoteJson = """
                {
                  "success": true,
                  "vote": {
                    "id": 42,
                    "title": "你最常用的 VPS 系统？",
                    "uid": 101,
                    "isPublic": 1,
                    "locked": 0,
                    "multiple": 0,
                    "items": [
                      {"vote_item_id": 1001, "text": "Debian 12", "count": 18, "voted": 0, "voters": [203]},
                      {"vote_item_id": 1002, "text": "Alpine Linux", "count": 7, "voted": 0, "voters": [101]},
                      {"vote_item_id": 1003, "text": "Ubuntu 24.04", "count": 5, "voted": 0, "voters": [204]}
                    ]
                  }
                }
            """.trimIndent()
            val postVoteJson = """
                {
                  "success": true,
                  "vote": {
                    "id": 42,
                    "title": "你最常用的 VPS 系统？",
                    "uid": 101,
                    "isPublic": 1,
                    "locked": 0,
                    "multiple": 0,
                    "items": [
                      {"vote_item_id": 1001, "text": "Debian 12", "count": 19, "voted": 1, "voters": [203, 202]},
                      {"vote_item_id": 1002, "text": "Alpine Linux", "count": 7, "voted": 0, "voters": [101]},
                      {"vote_item_id": 1003, "text": "Ubuntu 24.04", "count": 5, "voted": 0, "voters": [204]}
                    ]
                  }
                }
            """.trimIndent()

            harness.forumDispatcher.register("GET", "/post-123-1") {
                MockResponse().setResponseCode(200).setBody(topicHtml)
            }
            harness.forumDispatcher.enqueueNext("GET", "/api/vote/info/42", MockResponse().setResponseCode(200).setBody(preVoteJson))
            harness.forumDispatcher.enqueueNext("GET", "/api/vote/info/42", MockResponse().setResponseCode(200).setBody(postVoteJson))
            harness.forumDispatcher.register("POST", "/api/vote/voteforitem") {
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            val vm = harness.manage(TopicDetailViewModel(container = harness.buildAppContainer(), topicId = 123L))
            val collectJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                vm.uiState.collect {}
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.pageData != null } }
            }

            vm.nsAppWidgetController.ensureVoteLoaded(42L)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.nsAppWidgetController.pollStateFor(42L).first { it.poll != null }
                }
            }

            vm.nsAppWidgetController.togglePollOption(42L, 1001L, isMultiple = false)
            assertEquals(setOf(1001L), vm.nsAppWidgetController.pollStateFor(42L).value.selectedOptionIds)

            vm.nsAppWidgetController.togglePollOption(42L, 1002L, isMultiple = false)
            assertEquals(setOf(1002L), vm.nsAppWidgetController.pollStateFor(42L).value.selectedOptionIds)

            vm.nsAppWidgetController.togglePollOption(42L, 1001L, isMultiple = false)
            assertEquals(setOf(1001L), vm.nsAppWidgetController.pollStateFor(42L).value.selectedOptionIds)

            vm.nsAppWidgetController.submitVote(42L)
            val updatedState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.nsAppWidgetController.pollStateFor(42L).first {
                        !it.isSubmitting && it.poll?.hasCurrentUserVoted == true
                    }
                }
            }

            val updatedPoll = updatedState.poll!!
            assertTrue(updatedPoll.hasCurrentUserVoted)
            assertEquals(19, updatedPoll.options[0].count)
            assertEquals(31L, updatedPoll.totalVotes)
            assertEquals(61, updatedPoll.sharePercent(updatedPoll.options[0]))
            assertTrue(updatedState.selectedOptionIds.isEmpty())

            val castReq = harness.forumDispatcher.recordedRequests()
                .first { it.path == "/api/vote/voteforitem" }
            val castBody = castReq.body.readUtf8()
            val castJson = TempScriptParser.json.parseToJsonElement(castBody).jsonObject
            assertEquals(listOf("1001"), castJson.getValue("ids").jsonArray.map { it.jsonPrimitive.content })
            val expectedSign = computeDynamicSign(
                method = "POST",
                fullUrl = castReq.requestUrl.toString(),
                userAgent = harness.testUserAgent,
                rawBody = castBody,
            )
            assertEquals(expectedSign, castReq.getHeader("x-dynamic-sign"))
            assertTrue(castReq.getHeader("Cookie")?.contains("session=ns_e2e_sess") == true)
            collectJob.cancel()
        }
    }

    @Test
    fun `E2E-T1-R1-3 multi-choice poll submits multiple selected option IDs and refreshes voted flags`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 202L, username = "bob202")
            val topicHtml = E2eFixtures.loadText("nodeseek/topic_trade_op_123.html")
            val preMultiJson = """
                {
                  "success": true,
                  "vote": {
                    "id": 42,
                    "title": "支持多选的系统",
                    "uid": 101,
                    "isPublic": 1,
                    "locked": 0,
                    "multiple": 1,
                    "items": [
                      {"vote_item_id": 1001, "text": "Debian 12", "count": 10, "voted": 0, "voters": []},
                      {"vote_item_id": 1002, "text": "Alpine Linux", "count": 5, "voted": 0, "voters": []},
                      {"vote_item_id": 1003, "text": "Ubuntu 24.04", "count": 2, "voted": 0, "voters": []}
                    ]
                  }
                }
            """.trimIndent()
            val postMultiJson = """
                {
                  "success": true,
                  "vote": {
                    "id": 42,
                    "title": "支持多选的系统",
                    "uid": 101,
                    "isPublic": 1,
                    "locked": 0,
                    "multiple": 1,
                    "items": [
                      {"vote_item_id": 1001, "text": "Debian 12", "count": 11, "voted": 1, "voters": [202]},
                      {"vote_item_id": 1002, "text": "Alpine Linux", "count": 6, "voted": 1, "voters": [202]},
                      {"vote_item_id": 1003, "text": "Ubuntu 24.04", "count": 2, "voted": 0, "voters": []}
                    ]
                  }
                }
            """.trimIndent()

            harness.forumDispatcher.register("GET", "/post-123-1") {
                MockResponse().setResponseCode(200).setBody(topicHtml)
            }
            harness.forumDispatcher.enqueueNext("GET", "/api/vote/info/42", MockResponse().setResponseCode(200).setBody(preMultiJson))
            harness.forumDispatcher.enqueueNext("GET", "/api/vote/info/42", MockResponse().setResponseCode(200).setBody(postMultiJson))
            harness.forumDispatcher.register("POST", "/api/vote/voteforitem") {
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            val vm = harness.manage(TopicDetailViewModel(container = harness.buildAppContainer(), topicId = 123L))
            val collectJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                vm.uiState.collect {}
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.pageData != null } }
            }

            vm.nsAppWidgetController.ensureVoteLoaded(42L)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.nsAppWidgetController.pollStateFor(42L).first { it.poll != null } }
            }

            vm.nsAppWidgetController.togglePollOption(42L, 1001L, isMultiple = true)
            vm.nsAppWidgetController.togglePollOption(42L, 1002L, isMultiple = true)
            assertEquals(setOf(1001L, 1002L), vm.nsAppWidgetController.pollStateFor(42L).value.selectedOptionIds)

            vm.nsAppWidgetController.submitVote(42L)
            val updatedState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.nsAppWidgetController.pollStateFor(42L).first {
                        !it.isSubmitting && it.poll?.hasCurrentUserVoted == true
                    }
                }
            }

            val refreshedPoll = updatedState.poll!!
            assertTrue(refreshedPoll.options[0].voted)
            assertTrue(refreshedPoll.options[1].voted)
            assertFalse(refreshedPoll.options[2].voted)

            val castReq = harness.forumDispatcher.recordedRequests()
                .first { it.path == "/api/vote/voteforitem" }
            val castJson = TempScriptParser.json.parseToJsonElement(castReq.body.readUtf8()).jsonObject
            assertEquals(setOf("1001", "1002"), castJson.getValue("ids").jsonArray.map { it.jsonPrimitive.content }.toSet())
            collectJob.cancel()
        }
    }

    @Test
    fun `E2E-T1-R1-4 composer creates poll via signed POST api vote info and embeds nsapp vote link in new discussion`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(
                uid = 101L,
                username = "alice101",
                rank = 4,
                coin = 1650,
                stardust = 42,
            )

            harness.forumDispatcher.register("GET", "/api/content/list-categories") {
                MockResponse().setResponseCode(200).setBody(
                    """{"success":true,"data":[{"key":"daily","cn_text":"日常","customizable":1,"showInIndex":1}]}""",
                )
            }
            harness.forumDispatcher.register("POST", "/api/vote/info") {
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "success": true,
                      "vote": {
                        "id": 99,
                        "title": "最佳线路?",
                        "uid": 101,
                        "isPublic": 1,
                        "locked": 0,
                        "multiple": 0,
                        "items": [
                          {"vote_item_id": 3001, "text": "CN2 GIA", "count": 0, "voted": 0, "voters": []},
                          {"vote_item_id": 3002, "text": "9929", "count": 0, "voted": 0, "voters": []}
                        ]
                      }
                    }
                    """.trimIndent(),
                )
            }
            harness.forumDispatcher.register("POST", "/api/content/new-discussion") {
                MockResponse().setResponseCode(200).setBody("""{"success":true,"postId":789}""")
            }

            val composer = harness.manage(TopicComposerViewModel(container = harness.buildAppContainer()))
            val stateCollectJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                composer.uiState.collect {}
            }
            val emittedEvents = CopyOnWriteArrayList<TopicComposerUiEvent>()
            val eventCollectJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                composer.events.collect { emittedEvents.add(it) }
            }

            composer.createPollAndInsert(
                title = "最佳线路?",
                multiple = false,
                isPublic = true,
                options = listOf("CN2 GIA", "9929"),
            )

            val insertedEvent = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (true) {
                        val match = emittedEvents.filterIsInstance<TopicComposerUiEvent.InsertUploadedMarkdown>()
                            .firstOrNull()
                        if (match != null) return@withTimeout match
                        kotlinx.coroutines.delay(15L)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    error("Unreachable")
                }
            }
            assertEquals("\n[最佳线路?](nsapp://vote?id=99)\n", insertedEvent.snippet)

            composer.onTitleChanged("线路调研")
            composer.onMarkdownChanged("请大家投票：\n${insertedEvent.snippet.trim()}")
            composer.onPublishClicked()

            val publishedEvent = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (true) {
                        val match = emittedEvents.filterIsInstance<TopicComposerUiEvent.PublishedSuccess>()
                            .firstOrNull()
                        if (match != null) return@withTimeout match
                        kotlinx.coroutines.delay(15L)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    error("Unreachable")
                }
            }
            assertEquals(789L, publishedEvent.createdTopicId)

            val createVoteReq = harness.forumDispatcher.recordedRequests()
                .first { it.path == "/api/vote/info" }
            val createVoteBody = createVoteReq.body.readUtf8()
            val expectedSign = computeDynamicSign(
                method = "POST",
                fullUrl = createVoteReq.requestUrl.toString(),
                userAgent = harness.testUserAgent,
                rawBody = createVoteBody,
            )
            assertEquals(expectedSign, createVoteReq.getHeader("x-dynamic-sign"))

            val publishReq = harness.forumDispatcher.recordedRequests()
                .first { it.path == "/api/content/new-discussion" }
            assertFalse(publishReq.getHeader("csrf-token").isNullOrBlank())
            val publishJson = TempScriptParser.json.parseToJsonElement(publishReq.body.readUtf8()).jsonObject
            assertTrue(publishJson.getValue("content").jsonPrimitive.content.contains("[最佳线路?](nsapp://vote?id=99)"))

            stateCollectJob.cancel()
            eventCollectJob.cancel()
        }
    }

    @Test
    fun `E2E-T1-R1-5 parse stardust-receive widget, prepare transfer, and submit signed stardust tip`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(
                uid = 101L,
                username = "alice101",
                rank = 4,
                coin = 1650,
                stardust = 42,
            )
            val stardustUrl = "nsapp://stardust-receive?uid=202&amount=10&ref=123&once=1&note=感谢分享"
            val blocks = parseNodeSeekMarkdownBlocks("[投喂星尘]($stardustUrl)")
            val widget = blocks.filterIsInstance<MdBlock.SpecialWidget>().single()
            val parsedTarget = parseNsAppWidgetTarget(widget.url) as NsAppWidgetTarget.StardustReceive
            assertEquals(202L, parsedTarget.receiverUid)
            assertEquals(10, parsedTarget.suggestedAmount)
            assertEquals(123L, parsedTarget.refId)
            assertTrue(parsedTarget.once)
            assertEquals("感谢分享", parsedTarget.note)

            val stardustComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/stardust_ledger_and_transfer.json"),
            ).jsonObject
            val topicHtml = E2eFixtures.loadText("nodeseek/topic_trade_op_123.html")
            val homeHtml = E2eFixtures.loadText("nodeseek/home_logged_in_uid101.html")

            harness.forumDispatcher.register("GET", "/post-123-1") {
                MockResponse().setResponseCode(200).setBody(topicHtml)
            }
            harness.forumDispatcher.register("GET", "/") {
                MockResponse().setResponseCode(200).setBody(homeHtml)
            }
            harness.forumDispatcher.register("POST", "/api/stardust/payment-prepare") {
                MockResponse().setResponseCode(200).setBody(stardustComposite.getValue("paymentPrepare").toString())
            }
            harness.forumDispatcher.register("POST", "/api/stardust/send") {
                MockResponse().setResponseCode(200).setBody(stardustComposite.getValue("send").toString())
            }

            val vm = harness.manage(TopicDetailViewModel(container = harness.buildAppContainer(), topicId = 123L))
            val collectJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                vm.uiState.collect {}
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.pageData != null } }
            }

            var externalUriCalled = false
            dispatchMarkdownUrlClick(
                rawUrl = stardustUrl,
                nsAppWidgetController = vm.nsAppWidgetController,
                onOpenInlineVote = null,
                onUrlClick = null,
                onOpenExternalUri = { externalUriCalled = true },
            )
            assertFalse(externalUriCalled)

            val preparedState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        val dlg = it.stardustTipDialog
                        dlg != null && !dlg.isPreparing
                    }
                }
            }
            assertEquals("bob202", preparedState.stardustTipDialog?.resolvedReceiverName)
            assertEquals("10", preparedState.stardustTipDialog?.amountInput)

            vm.submitStardustTip()

            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { it.stardustTipDialog == null }
                }
            }
            val cardState = vm.nsAppWidgetController.stardustStateFor(stardustUrl).value
            assertTrue(cardState.isTipped)
            assertEquals(10, cardState.lastTippedAmount)

            val prepareReq = harness.forumDispatcher.recordedRequests()
                .first { it.path == "/api/stardust/payment-prepare" }
            val prepareBody = prepareReq.body.readUtf8()
            assertEquals(
                computeDynamicSign("POST", prepareReq.requestUrl.toString(), harness.testUserAgent, prepareBody),
                prepareReq.getHeader("x-dynamic-sign"),
            )

            val sendReq = harness.forumDispatcher.recordedRequests()
                .first { it.path == "/api/stardust/send" }
            val sendBody = sendReq.body.readUtf8()
            val sendJson = TempScriptParser.json.parseToJsonElement(sendBody).jsonObject
            assertEquals("202", sendJson.getValue("member_id").jsonPrimitive.content)
            assertEquals("10", sendJson.getValue("diff").jsonPrimitive.content)
            assertEquals("123", sendJson.getValue("ref_id").jsonPrimitive.content)
            assertEquals(
                computeDynamicSign("POST", sendReq.requestUrl.toString(), harness.testUserAgent, sendBody),
                sendReq.getHeader("x-dynamic-sign"),
            )
            collectJob.cancel()
        }
    }

    @Test
    fun `E2E-T2-R1-1 null vote counts and null voters decode cleanly with zero totalVotes`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L)
            val nullCountsJson = """
                {
                  "success": true,
                  "vote": {
                    "id": 77,
                    "title": "隐藏实时票数的多选投票",
                    "uid": 101,
                    "isPublic": 0,
                    "locked": 0,
                    "multiple": 1,
                    "items": [
                      {"vote_item_id": 1, "text": "选项 A", "count": null, "voted": 0, "voters": null},
                      {"vote_item_id": 2, "text": "选项 B", "count": null, "voted": 0, "voters": null}
                    ]
                  }
                }
            """.trimIndent()
            harness.forumDispatcher.register("GET", "/api/vote/info/77") {
                MockResponse().setResponseCode(200).setBody(nullCountsJson)
            }

            val result = harness.pollAndStardustRepository.getVote(77L)
            assertTrue(result.isSuccess)
            val poll = result.getOrThrow()
            assertFalse(poll.isPublic)
            assertFalse(poll.isLocked)
            assertTrue(poll.isMultiple)
            assertFalse(poll.hasVisibleCounts)
            assertFalse(poll.hasCurrentUserVoted)
            assertEquals(0L, poll.totalVotes)
            assertEquals(0, poll.sharePercent(poll.options[0]))
            assertEquals(0f, poll.shareFraction(poll.options[0]), 0.0001f)
        }
    }

    @Test
    fun `E2E-T2-R1-2 locked poll blocks selection locally and server vote rejection retains uncorrupted poll`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 202L)
            val topicHtml = E2eFixtures.loadText("nodeseek/topic_trade_op_123.html")
            val lockedJson = """
                {
                  "success": true,
                  "vote": {
                    "id": 42,
                    "title": "已锁定投票",
                    "uid": 101,
                    "isPublic": 1,
                    "locked": 1,
                    "multiple": 0,
                    "items": [
                      {"vote_item_id": 1001, "text": "A", "count": 5, "voted": 0, "voters": []},
                      {"vote_item_id": 1002, "text": "B", "count": 3, "voted": 0, "voters": []}
                    ]
                  }
                }
            """.trimIndent()
            val unlockedJson = """
                {
                  "success": true,
                  "vote": {
                    "id": 43,
                    "title": "服务端刚刚锁定",
                    "uid": 101,
                    "isPublic": 1,
                    "locked": 0,
                    "multiple": 0,
                    "items": [
                      {"vote_item_id": 2001, "text": "X", "count": 4, "voted": 0, "voters": []},
                      {"vote_item_id": 2002, "text": "Y", "count": 6, "voted": 0, "voters": []}
                    ]
                  }
                }
            """.trimIndent()

            harness.forumDispatcher.register("GET", "/post-123-1") {
                MockResponse().setResponseCode(200).setBody(topicHtml)
            }
            harness.forumDispatcher.register("GET", "/api/vote/info/42") {
                MockResponse().setResponseCode(200).setBody(lockedJson)
            }
            harness.forumDispatcher.register("GET", "/api/vote/info/43") {
                MockResponse().setResponseCode(200).setBody(unlockedJson)
            }
            harness.forumDispatcher.register("POST", "/api/vote/voteforitem") {
                MockResponse().setResponseCode(200).setBody("""{"success":false,"message":"投票已锁定或已投过票"}""")
            }

            val vm = harness.manage(TopicDetailViewModel(container = harness.buildAppContainer(), topicId = 123L))
            val collectJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                vm.uiState.collect {}
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.pageData != null } }
            }

            // Locked poll blocks togglePollOption locally
            vm.nsAppWidgetController.ensureVoteLoaded(42L)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.nsAppWidgetController.pollStateFor(42L).first { it.poll != null } }
            }
            vm.nsAppWidgetController.togglePollOption(42L, 1001L, isMultiple = false)
            assertTrue(vm.nsAppWidgetController.pollStateFor(42L).value.selectedOptionIds.isEmpty())

            // Unlocked locally but server rejects castVote
            vm.nsAppWidgetController.ensureVoteLoaded(43L)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.nsAppWidgetController.pollStateFor(43L).first { it.poll != null } }
            }
            vm.nsAppWidgetController.togglePollOption(43L, 2001L, isMultiple = false)
            vm.nsAppWidgetController.submitVote(43L)
            val errorState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.nsAppWidgetController.pollStateFor(43L).first { !it.isSubmitting && it.errorMessage != null }
                }
            }
            assertEquals("投票已锁定或已投过票", errorState.errorMessage)
            assertFalse(errorState.isSubmitting)
            assertEquals(43L, errorState.poll?.id)
            assertEquals(10L, errorState.poll?.totalVotes)
            assertFalse(errorState.poll?.hasCurrentUserVoted == true)
            collectJob.cancel()
        }
    }

    @Test
    fun `E2E-T2-R1-3 createVote validates non-blank title and at least two options before any network call`() = runTest {
        E2eTestHarness().use { harness ->
            val emptyTitleResult = harness.pollAndStardustRepository.createVote(
                title = "   ",
                multiple = false,
                isPublic = true,
                options = listOf("A", "B"),
            )
            assertTrue(emptyTitleResult.isFailure)
            assertTrue(emptyTitleResult.exceptionOrNull() is IllegalArgumentException)

            val insufficientOptionsResult = harness.pollAndStardustRepository.createVote(
                title = "Valid Title",
                multiple = false,
                isPublic = true,
                options = listOf("OnlyOne", "   "),
            )
            assertTrue(insufficientOptionsResult.isFailure)
            assertTrue(insufficientOptionsResult.exceptionOrNull() is IllegalArgumentException)

            assertTrue(harness.forumDispatcher.recordedRequests().isEmpty())
        }
    }

    @Test
    fun `E2E-T2-R1-4 payment-prepare failure surfaces prepareError and blocks send endpoint`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, stardust = 42)
            val topicHtml = E2eFixtures.loadText("nodeseek/topic_trade_op_123.html")
            harness.forumDispatcher.register("GET", "/post-123-1") {
                MockResponse().setResponseCode(200).setBody(topicHtml)
            }
            harness.forumDispatcher.register("POST", "/api/stardust/payment-prepare") {
                MockResponse().setResponseCode(200).setBody("""{"success":false,"message":"USER NOT FOUND"}""")
            }

            val vm = harness.manage(TopicDetailViewModel(container = harness.buildAppContainer(), topicId = 123L))
            val collectJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                vm.uiState.collect {}
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.pageData != null } }
            }

            vm.openStardustTip(
                StardustTipTarget(
                    receiverUid = 999L,
                    suggestedAmount = 5,
                    refId = 123L,
                ),
            )

            val dialogState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        val dlg = it.stardustTipDialog
                        dlg != null && !dlg.isPreparing
                    }
                }
            }
            assertEquals("没有找到这位用户，请检查用户编号。", dialogState.stardustTipDialog?.prepareError)
            assertFalse(dialogState.stardustTipDialog?.isPreparing == true)
            assertTrue(
                harness.forumDispatcher.recordedRequests().none { it.path == "/api/stardust/send" },
            )
            collectJob.cancel()
        }
    }

    @Test
    fun `E2E-T2-R1-5 sendStardust handles insufficient balance and 403 Cloudflare challenge without double-send retry`() = runTest {
        // Subcase A: server returns insufficient balance JSON error
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, stardust = 50)
            val topicHtml = E2eFixtures.loadText("nodeseek/topic_trade_op_123.html")
            val stardustComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/stardust_ledger_and_transfer.json"),
            ).jsonObject

            harness.forumDispatcher.register("GET", "/post-123-1") {
                MockResponse().setResponseCode(200).setBody(topicHtml)
            }
            harness.forumDispatcher.register("POST", "/api/stardust/payment-prepare") {
                MockResponse().setResponseCode(200).setBody(stardustComposite.getValue("paymentPrepare").toString())
            }
            harness.forumDispatcher.register("POST", "/api/stardust/send") {
                MockResponse().setResponseCode(200).setBody("""{"success":false,"message":"星尘余额不足"}""")
            }

            val widgetKey = "nsapp://stardust-receive?uid=202&amount=10&ref=123"
            val vm = harness.manage(TopicDetailViewModel(container = harness.buildAppContainer(), topicId = 123L))
            val collectJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                vm.uiState.collect {}
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.pageData != null } }
            }

            vm.openStardustTip(
                StardustTipTarget(receiverUid = 202L, suggestedAmount = 10, refId = 123L, widgetKey = widgetKey),
            )
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.stardustTipDialog?.isPreparing == false } }
            }
            vm.submitStardustTip()

            val failedDialogState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        val dlg = it.stardustTipDialog
                        dlg != null && !dlg.isSending && dlg.sendError != null
                    }
                }
            }
            assertEquals("星尘余额不足", failedDialogState.stardustTipDialog?.sendError)
            assertFalse(vm.nsAppWidgetController.stardustStateFor(widgetKey).value.isTipped)
            collectJob.cancel()
        }

        // Subcase B: HTTP 403 Cloudflare challenge emits RequestWebViewAuth and sends exactly 1 /api/stardust/send request
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, stardust = 50)
            val topicHtml = E2eFixtures.loadText("nodeseek/topic_trade_op_123.html")
            val cfHtml = E2eFixtures.loadText("nodeseek/cf_challenge_403.html")
            val stardustComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/stardust_ledger_and_transfer.json"),
            ).jsonObject

            harness.forumDispatcher.register("GET", "/post-123-1") {
                MockResponse().setResponseCode(200).setBody(topicHtml)
            }
            harness.forumDispatcher.register("POST", "/api/stardust/payment-prepare") {
                MockResponse().setResponseCode(200).setBody(stardustComposite.getValue("paymentPrepare").toString())
            }
            harness.forumDispatcher.register("POST", "/api/stardust/send") {
                MockResponse()
                    .setResponseCode(403)
                    .setHeader("cf-mitigated", "challenge")
                    .setHeader("Content-Type", "text/html")
                    .setBody(cfHtml)
            }

            val vm = harness.manage(TopicDetailViewModel(container = harness.buildAppContainer(), topicId = 123L))
            val stateJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
            val events = CopyOnWriteArrayList<TopicDetailUiEvent>()
            val eventJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.events.collect { events.add(it) } }

            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.pageData != null } }
            }
            vm.openStardustTip(StardustTipTarget(receiverUid = 202L, suggestedAmount = 10, refId = 123L))
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.stardustTipDialog?.isPreparing == false } }
            }
            vm.submitStardustTip()

            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (!harness.sessionRepository.challengeRequired.value ||
                        events.none { it is TopicDetailUiEvent.RequestWebViewAuth }
                    ) {
                        kotlinx.coroutines.delay(15L)
                    }
                }
            }
            assertTrue(harness.sessionRepository.challengeRequired.value)
            assertTrue(events.any { it is TopicDetailUiEvent.RequestWebViewAuth })
            assertEquals(
                1,
                harness.forumDispatcher.recordedRequests().count { it.path == "/api/stardust/send" },
            )
            stateJob.cancel()
            eventJob.cancel()
        }
    }
}
