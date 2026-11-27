package com.nodeseek.app.e2e.m1

import com.nodeseek.app.data.network.computeDynamicSign
import com.nodeseek.app.data.parser.NodeSeekHtmlParser
import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.domain.activity.DrandLuckyEngine
import com.nodeseek.app.domain.activity.LuckyViewerStatus
import com.nodeseek.app.domain.model.NsAppWidgetTarget
import com.nodeseek.app.domain.model.UpgradeEtaStatus
import com.nodeseek.app.domain.model.parseNsAppWidgetTarget
import com.nodeseek.app.e2e.E2eFixtures
import com.nodeseek.app.e2e.E2eMainDispatcherRule
import com.nodeseek.app.e2e.E2eTestHarness
import com.nodeseek.app.ui.components.MdBlock
import com.nodeseek.app.ui.components.parseNodeSeekMarkdownBlocks
import com.nodeseek.app.ui.hub.CreditStardustLedgerViewModel
import com.nodeseek.app.ui.hub.EconomyLedgerTab
import com.nodeseek.app.ui.topic.TopicDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class M1PairwiseE2eTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    @Test
    fun `E2E-T3-01 R1 and R2 embedded nsapp vote poll plus lucky draw inside same thread lifecycle`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(
                uid = 202L,
                username = "bob202",
                rank = 3,
                coin = 500,
                stardust = 30,
                sessionCookie = "ns_pairwise_sess",
            )

            val combinedConfigJson = """
                {
                  "user": {
                    "member_id": 202,
                    "member_name": "bob202",
                    "coin": 500,
                    "stardust": 30,
                    "rank": 3
                  },
                  "enableCustomedStyle": 1,
                  "allCategory": [
                    {"key": "daily", "cn_text": "日常", "customizable": 1, "showInIndex": 1, "adminOnly": 0}
                  ],
                  "postData": {
                    "postId": 456,
                    "postPage": 1,
                    "postPageCount": 1,
                    "title": "【投票+抽奖】选出下一期福利奖品",
                    "category": "daily",
                    "categoryWord": "日常",
                    "categoryLink": "/categories/daily",
                    "views": 880,
                    "collectionCount": 10,
                    "rank": 0,
                    "locked": 0,
                    "collected": 0,
                    "award": 1,
                    "op": {"uid": 101, "name": "alice101", "avatar": "/avatar/101.png"},
                    "comments": [
                      {
                        "commentId": 45600,
                        "floorIndex": 0,
                        "poster": {"uid": 101, "name": "alice101", "avatar": "/avatar/101.png", "isOp": 1, "roles": []},
                        "time": {"createdDate": "2026-11-24T12:00:00Z"},
                        "markdown": "[投哪个奖品](nsapp://vote?id=42)\n\n[点击查看开奖](https://www.nodeseek.com/lucky?post=456&time=1764000000000&count=2&start=1&duplicate=false&mode=view)",
                        "likeCount": 5,
                        "dislikeCount": 0,
                        "upvoteCount": 4,
                        "liked": 0,
                        "disliked": 0,
                        "upvoted": 0,
                        "pined": 0,
                        "hot": 0,
                        "blocked": 0
                      }
                    ]
                  }
                }
            """.trimIndent()

            val preVoteJson = """
                {
                  "success": true,
                  "vote": {
                    "id": 42,
                    "title": "投哪个奖品",
                    "uid": 101,
                    "isPublic": 1,
                    "locked": 0,
                    "multiple": 0,
                    "items": [
                      {"vote_item_id": 1001, "text": "邀请码", "count": 10, "voted": 0, "voters": []},
                      {"vote_item_id": 1002, "text": "星尘红包", "count": 4, "voted": 0, "voters": []}
                    ]
                  }
                }
            """.trimIndent()
            val postVoteJson = """
                {
                  "success": true,
                  "vote": {
                    "id": 42,
                    "title": "投哪个奖品",
                    "uid": 101,
                    "isPublic": 1,
                    "locked": 0,
                    "multiple": 0,
                    "items": [
                      {"vote_item_id": 1001, "text": "邀请码", "count": 11, "voted": 1, "voters": [202]},
                      {"vote_item_id": 1002, "text": "星尘红包", "count": 4, "voted": 0, "voters": []}
                    ]
                  }
                }
            """.trimIndent()

            harness.forumDispatcher.register("GET", "/post-456-1") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.wrapTempScriptHtml(combinedConfigJson))
            }
            harness.forumDispatcher.enqueueNext("GET", "/api/vote/info/42", MockResponse().setResponseCode(200).setBody(preVoteJson))
            harness.forumDispatcher.enqueueNext("GET", "/api/vote/info/42", MockResponse().setResponseCode(200).setBody(postVoteJson))
            harness.forumDispatcher.register("POST", "/api/vote/voteforitem") {
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }
            harness.forumDispatcher.register("GET", "/api/content/floor-data") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.loadText("drand/floor_data_456.json"))
            }
            harness.externalDispatcher.register(
                "GET",
                "/${DrandLuckyEngine.DRAND_CHAIN_HASH}/public/5618965",
            ) {
                MockResponse().setResponseCode(200).setBody(
                    E2eFixtures.loadText("drand/drand_round_4500000.json")
                        .replace("\"round\": 4500000", "\"round\": 5618965"),
                )
            }

            val container = harness.buildAppContainer()
            val vm = harness.manage(TopicDetailViewModel(container = container, topicId = 456L))
            val collectJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }

            val loadedState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.openingFloor != null } }
            }
            val openingMarkdown = loadedState.openingFloor!!.markdownBody
            val voteBlocks = parseNodeSeekMarkdownBlocks(openingMarkdown)
                .filterIsInstance<MdBlock.SpecialWidget>()
                .mapNotNull { parseNsAppWidgetTarget(it.url) as? NsAppWidgetTarget.Vote }
            assertEquals(1, voteBlocks.size)
            assertEquals(42L, voteBlocks.single().voteId)

            val luckySpec = DrandLuckyEngine.extractFirstLuckySpec(openingMarkdown, fallbackPostId = 456L)
            assertNotNull(luckySpec)
            assertEquals(5_618_965L, DrandLuckyEngine.computeDrandRound(luckySpec!!.drawTimeEpochMillis!!))

            vm.nsAppWidgetController.ensureVoteLoaded(42L)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.nsAppWidgetController.pollStateFor(42L).first { it.poll != null } }
            }
            vm.nsAppWidgetController.togglePollOption(42L, 1001L, isMultiple = false)
            vm.nsAppWidgetController.submitVote(42L)
            val votedPollState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.nsAppWidgetController.pollStateFor(42L).first {
                        !it.isSubmitting && it.poll?.hasCurrentUserVoted == true
                    }
                }
            }
            assertTrue(votedPollState.poll!!.options.first().voted)

            val votePostReq = harness.forumDispatcher.recordedRequests()
                .first { it.path == "/api/vote/voteforitem" }
            assertNotNull(votePostReq.getHeader("x-dynamic-sign"))

            val luckyOutcome = container.luckyDrawRepository.resolveLuckyDraw(
                spec = luckySpec,
                viewerUid = vm.uiState.value.session?.uid,
                nowEpochMillis = 1_764_000_010_000L,
            ).getOrThrow()
            assertEquals(LuckyViewerStatus.MY_WIN, luckyOutcome.viewerStatus)
            assertEquals(listOf(2, 5), luckyOutcome.winners.map { it.floorId })

            val floorReq = harness.forumDispatcher.recordedRequests()
                .first { it.path?.startsWith("/api/content/floor-data") == true }
            assertTrue(floorReq.getHeader("Cookie")?.contains("session=ns_pairwise_sess") == true)
            harness.assertNoCookiesLeakedToExternalServer()
            collectJob.cancel()
        }
    }

    @Test
    fun `E2E-T3-02 R1 and R3 stardust tip updates R3 stardust ledger balance and record`() = runTest {
        E2eTestHarness(
            initialEpochMillis = Instant.parse("2026-11-25T12:00:00Z").toEpochMilli(),
        ).use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", coin = 1650, stardust = 42)
            val tradeThread = NodeSeekHtmlParser.parseThreadPage(
                html = E2eFixtures.loadText("nodeseek/topic_trade_op_123.html"),
                pageUrl = "https://www.nodeseek.com/post-123-1",
            )
            // Verify thread fixture carries nsapp://stardust-receive widget, then target bob202 (uid=202) from alice101
            assertTrue(tradeThread.openingComment?.markdown?.contains("nsapp://stardust-receive") == true)
            val tipTarget = parseNsAppWidgetTarget(
                "nsapp://stardust-receive?uid=202&amount=10&ref=123&once=1&note=感谢分享",
            ) as NsAppWidgetTarget.StardustReceive

            val stardustComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/stardust_ledger_and_transfer.json"),
            ).jsonObject
            val updatedLedgerJson = """
                {
                  "success": true,
                  "records": [
                    {
                      "id": 982,
                      "diff": -10,
                      "result": 32,
                      "type": "transfer",
                      "created_at": "2026-11-25T12:00:00Z",
                      "peer_id": 202
                    },
                    {
                      "id": 981,
                      "diff": 10,
                      "result": 42,
                      "type": "transfer",
                      "created_at": "2026-11-24T01:00:00Z",
                      "peer_id": 202
                    }
                  ],
                  "exist_more": false,
                  "cursor": null
                }
            """.trimIndent()

            harness.forumDispatcher.register("POST", "/api/stardust/payment-prepare") {
                MockResponse().setResponseCode(200).setBody(stardustComposite.getValue("paymentPrepare").toString())
            }
            harness.forumDispatcher.register("POST", "/api/stardust/send") {
                MockResponse().setResponseCode(200).setBody(stardustComposite.getValue("send").toString())
            }
            harness.forumDispatcher.register("GET", "/api/stardust/list") {
                MockResponse().setResponseCode(200).setBody(updatedLedgerJson)
            }

            val preview = harness.pollAndStardustRepository.prepareStardustTransfer(tipTarget.receiverUid).getOrThrow()
            assertEquals("bob202", preview.receiverName)

            val sendResult = harness.pollAndStardustRepository.sendStardust(
                memberId = tipTarget.receiverUid,
                amount = tipTarget.suggestedAmount!!,
                refId = tipTarget.refId,
            )
            assertTrue(sendResult.isSuccess)

            val ledgerVm = harness.manage(
                CreditStardustLedgerViewModel(
                    repository = harness.communityToolsRepository,
                    initialTab = EconomyLedgerTab.STARDUST,
                    targetUid = 101L,
                    currentCoins = 1650,
                    currentStardust = 32,
                    canAccessDrumsticks = true,
                    clockMillis = harness.clock::nowMillis,
                ),
            )
            val ledgerState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    ledgerVm.uiState.first { !it.isLoadingStardust && it.stardustRecords.isNotEmpty() }
                }
            }
            val latest = ledgerState.stardustRecords.first()
            assertEquals(982L, latest.id)
            assertEquals(-10, latest.diff)
            assertEquals(32, latest.balanceAfter)
            assertEquals("转账", latest.typeLabel)
            assertEquals(202L, latest.peerUid)

            val stardustReqs = harness.forumDispatcher.recordedRequests()
                .filter { it.path?.startsWith("/api/stardust/") == true }
            assertEquals(3, stardustReqs.size)
            for (req in stardustReqs) {
                val expectedSign = computeDynamicSign(
                    method = req.method!!,
                    fullUrl = req.requestUrl.toString(),
                    userAgent = harness.testUserAgent,
                    rawBody = req.body.clone().readUtf8(),
                )
                assertEquals(expectedSign, req.getHeader("x-dynamic-sign"))
                assertTrue(req.getHeader("Cookie")?.contains("session=ns_e2e_sess") == true)
            }
        }
    }

    @Test
    fun `E2E-T3-03 R2 and R3 check-in level-up updates session level and resolves Drand lucky draw`() = runTest {
        E2eTestHarness(
            initialEpochMillis = Instant.parse("2026-11-25T12:00:00Z").toEpochMilli(),
        ).use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", coin = 396, stardust = 42)
            assertEquals(1, harness.sessionRepository.sessionState.value?.level)

            val boardComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/attendance_board_states.json"),
            ).jsonObject
            val postCheckInLedger = """
                {
                  "success": true,
                  "total": 4,
                  "data": [
                    [5, 401, "每日签到奖励", "2026-11-25 12:00:00"],
                    [8, 396, "每日签到奖励", "2026-11-24 08:00:00"],
                    [10, 388, "每日签到奖励", "2026-11-20 08:00:00"],
                    [7, 378, "每日签到奖励", "2026-11-15 08:00:00"]
                  ]
                }
            """.trimIndent()

            harness.forumDispatcher.register("POST", "/api/attendance") {
                MockResponse().setResponseCode(200)
                    .setBody("""{"success":true,"gain":5,"current":401,"message":"签到成功，获得 5 个鸡腿"}""")
            }
            harness.forumDispatcher.register("GET", "/api/attendance/board") {
                MockResponse().setResponseCode(200).setBody(boardComposite.getValue("fiveGainChecked").toString())
            }
            harness.forumDispatcher.register("GET", "/api/account/credit/page-1") {
                MockResponse().setResponseCode(200).setBody(postCheckInLedger)
            }
            harness.forumDispatcher.register("GET", "/api/content/floor-data") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.loadText("drand/floor_data_456.json"))
            }
            harness.externalDispatcher.register(
                "GET",
                "/${DrandLuckyEngine.DRAND_CHAIN_HASH}/public/5618965",
            ) {
                MockResponse().setResponseCode(200).setBody(
                    E2eFixtures.loadText("drand/drand_round_4500000.json")
                        .replace("\"round\": 4500000", "\"round\": 5618965"),
                )
            }

            val checkInStatus = harness.checkInRepository.checkIn(randomReward = false).getOrThrow()
            assertTrue(checkInStatus.hasCheckedInToday)
            assertEquals(5, checkInStatus.todayGain)
            assertEquals(401, harness.sessionRepository.sessionState.value?.drumsticks)
            assertEquals(2, harness.sessionRepository.sessionState.value?.level)

            val analyticsResult = harness.communityToolsRepository.getRecentCreditAnalytics(
                currentCoins = harness.sessionRepository.sessionState.value!!.drumsticks,
                nowEpochMillis = harness.clock.nowMillis(),
            ).getOrThrow()
            val eta = analyticsResult.analytics.upgradeEta
            assertEquals(2, eta.currentLevel)
            assertEquals(3, eta.nextLevel)
            assertEquals(900, eta.nextLevelThreshold)
            assertEquals(499, eta.coinsRemaining)
            assertEquals(UpgradeEtaStatus.ESTIMATED, eta.status)
            assertEquals(30.0 / 4.0, analyticsResult.analytics.checkInAverageCoins!!, 1e-6)

            val luckyThread = NodeSeekHtmlParser.parseThreadPage(
                html = E2eFixtures.loadText("nodeseek/topic_lucky_draw_456.html"),
                pageUrl = "https://www.nodeseek.com/post-456-1",
            )
            val luckySpec = DrandLuckyEngine.extractFirstLuckySpec(
                luckyThread.openingComment!!.markdown,
                fallbackPostId = 456L,
            )!!
            val outcome = harness.luckyDrawRepository.resolveLuckyDraw(
                spec = luckySpec,
                viewerUid = harness.sessionRepository.sessionState.value?.uid,
                nowEpochMillis = harness.clock.nowMillis(),
            ).getOrThrow()

            assertTrue(outcome.isDrawn)
            assertEquals(listOf(2, 5), outcome.winners.map { it.floorId })
            assertEquals(LuckyViewerStatus.PARTICIPATED_NOT_WON, outcome.viewerStatus)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }
}
