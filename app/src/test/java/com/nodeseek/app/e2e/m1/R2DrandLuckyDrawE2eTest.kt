package com.nodeseek.app.e2e.m1

import androidx.compose.ui.platform.UriHandler
import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.domain.activity.ActivityCounts
import com.nodeseek.app.domain.activity.ActivityFeedSnapshot
import com.nodeseek.app.domain.activity.ActivityLotteryClassifier
import com.nodeseek.app.domain.activity.ActivityRepository
import com.nodeseek.app.domain.activity.DrandLuckyEngine
import com.nodeseek.app.domain.activity.ForumWatchActivityDetail
import com.nodeseek.app.domain.activity.ForumWatchActivityItem
import com.nodeseek.app.domain.activity.LuckyDrawSpec
import com.nodeseek.app.domain.activity.LuckyViewerStatus
import com.nodeseek.app.e2e.E2eFixtures
import com.nodeseek.app.e2e.E2eMainDispatcherRule
import com.nodeseek.app.e2e.E2eTestHarness
import com.nodeseek.app.navigation.NodePulseDeepLink
import com.nodeseek.app.navigation.openNodeSeekOrExternalUrl
import com.nodeseek.app.ui.activity.ActivityViewModel
import com.nodeseek.app.ui.composer.TopicComposerUiEvent
import com.nodeseek.app.ui.composer.TopicComposerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

class R2DrandLuckyDrawE2eTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    private val drandPathPrefix =
        "/${DrandLuckyEngine.DRAND_CHAIN_HASH}/public"

    @Test
    fun `E2E-T1-R2-1 extract lucky spec from topic 456 and resolve deterministic Drand winners`() = runTest {
        E2eTestHarness().use { harness ->
            val topicHtml = E2eFixtures.loadText("nodeseek/topic_lucky_draw_456.html")
            val floorDataJson = E2eFixtures.loadText("drand/floor_data_456.json")
            val drandRound5618965Json = E2eFixtures.loadText("drand/drand_round_4500000.json")
                .replace("\"round\": 4500000", "\"round\": 5618965")

            harness.forumDispatcher.register("GET", "/post-456-1") {
                MockResponse().setResponseCode(200).setBody(topicHtml)
            }
            harness.forumDispatcher.register("GET", "/api/content/floor-data") {
                MockResponse().setResponseCode(200).setBody(floorDataJson)
            }
            harness.externalDispatcher.register("GET", "$drandPathPrefix/5618965") {
                MockResponse().setResponseCode(200).setBody(drandRound5618965Json)
            }

            val detail = harness.forumRepository.getTopicDetail(456L).getOrThrow()
            val openingMd = detail.openingFloor?.markdownBody.orEmpty()
            val spec = DrandLuckyEngine.extractFirstLuckySpec(openingMd, fallbackPostId = 456L)
            assertNotNull(spec)
            spec!!
            assertEquals(456L, spec.postId)
            assertEquals(1_764_000_000_000L, spec.drawTimeEpochMillis)
            assertEquals(2, spec.winnerCount)
            assertEquals(1, spec.firstFloor)
            assertTrue(spec.removeDuplicates)

            val outcome = harness.luckyDrawRepository.resolveLuckyDraw(
                spec = spec,
                viewerUid = 202L,
                nowEpochMillis = 1_764_000_010_000L,
            ).getOrThrow()

            assertTrue(outcome.isDrawn)
            assertEquals(5_618_965L, outcome.drandRound)
            assertEquals(listOf(2, 5), outcome.winners.map { it.floorId })
            assertEquals(listOf("bob202", "dave205"), outcome.winners.map { it.memberName })
            assertEquals(LuckyViewerStatus.MY_WIN, outcome.viewerStatus)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T1-R2-2 computeDrandRound maps 30s period boundaries to exact Drand beacon path`() = runTest {
        E2eTestHarness().use { harness ->
            val floorDataJson = E2eFixtures.loadText("drand/floor_data_456.json")
            val drandRound4500000Json = E2eFixtures.loadText("drand/drand_round_4500000.json")

            harness.forumDispatcher.register("GET", "/api/content/floor-data") {
                MockResponse().setResponseCode(200).setBody(floorDataJson)
            }
            harness.externalDispatcher.register("GET", "$drandPathPrefix/4500000") {
                MockResponse().setResponseCode(200).setBody(drandRound4500000Json)
            }

            val exactBoundaryMs = 1_730_431_050_000L
            val endOfPeriodMs = 1_730_431_079_999L
            assertEquals(4_500_000L, DrandLuckyEngine.computeDrandRound(exactBoundaryMs))
            assertEquals(4_500_000L, DrandLuckyEngine.computeDrandRound(endOfPeriodMs))

            val outcomeExact = harness.luckyDrawRepository.resolveLuckyDraw(
                spec = LuckyDrawSpec(postId = 456L, drawTimeEpochMillis = exactBoundaryMs, winnerCount = 2),
                viewerUid = 101L,
                nowEpochMillis = exactBoundaryMs + 1_000L,
            ).getOrThrow()
            val outcomeEnd = harness.luckyDrawRepository.resolveLuckyDraw(
                spec = LuckyDrawSpec(postId = 456L, drawTimeEpochMillis = endOfPeriodMs, winnerCount = 2),
                viewerUid = 101L,
                nowEpochMillis = endOfPeriodMs + 1_000L,
            ).getOrThrow()

            assertEquals(4_500_000L, outcomeExact.drandRound)
            assertEquals(4_500_000L, outcomeEnd.drandRound)
            assertEquals(listOf(2, 5), outcomeExact.winners.map { it.floorId })
            assertEquals(listOf(2, 5), outcomeEnd.winners.map { it.floorId })

            val externalPaths = harness.externalDispatcher.recordedRequests().mapNotNull { it.path }
            assertEquals(listOf("$drandPathPrefix/4500000", "$drandPathPrefix/4500000"), externalPaths)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T1-R2-3 evaluateViewerStatus distinguishes MY_WIN, PARTICIPATED_NOT_WON, and NOT_LOGGED_IN`() = runTest {
        E2eTestHarness().use { harness ->
            val floorDataJson = E2eFixtures.loadText("drand/floor_data_456.json")
            val drandJson = E2eFixtures.loadText("drand/drand_round_4500000.json")
                .replace("\"round\": 4500000", "\"round\": 5618965")
            harness.forumDispatcher.register("GET", "/api/content/floor-data") {
                MockResponse().setResponseCode(200).setBody(floorDataJson)
            }
            harness.externalDispatcher.register("GET", "$drandPathPrefix/5618965") {
                MockResponse().setResponseCode(200).setBody(drandJson)
            }

            val spec = LuckyDrawSpec(
                postId = 456L,
                drawTimeEpochMillis = 1_764_000_000_000L,
                winnerCount = 2,
                firstFloor = 1,
                removeDuplicates = true,
            )

            val winOutcome = harness.luckyDrawRepository.resolveLuckyDraw(spec, viewerUid = 202L, nowEpochMillis = 1_764_000_010_000L).getOrThrow()
            assertEquals(LuckyViewerStatus.MY_WIN, winOutcome.viewerStatus)
            assertEquals(listOf(2), winOutcome.viewerWinningFloors.map { it.floorId })

            val lostOutcome = harness.luckyDrawRepository.resolveLuckyDraw(spec, viewerUid = 101L, nowEpochMillis = 1_764_000_010_000L).getOrThrow()
            assertEquals(LuckyViewerStatus.PARTICIPATED_NOT_WON, lostOutcome.viewerStatus)
            assertTrue(lostOutcome.viewerWinningFloors.isEmpty())
            assertEquals(listOf(4), lostOutcome.viewerFloors.map { it.floorId })

            val guestOutcome = harness.luckyDrawRepository.resolveLuckyDraw(spec, viewerUid = null, nowEpochMillis = 1_764_000_010_000L).getOrThrow()
            assertEquals(LuckyViewerStatus.NOT_LOGGED_IN, guestOutcome.viewerStatus)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T1-R2-4 OP composer appends lucky URL on publish and posts winner reply with mentions`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4, coin = 1650)

            harness.forumDispatcher.register("GET", "/api/content/list-categories") {
                MockResponse().setResponseCode(200).setBody(
                    """{"success":true,"data":[{"key":"daily","cn_text":"日常","customizable":1,"showInIndex":1}]}""",
                )
            }
            harness.forumDispatcher.register("POST", "/api/content/new-discussion") {
                MockResponse().setResponseCode(200).setBody("""{"success":true,"postId":456}""")
            }
            harness.forumDispatcher.register("POST", "/api/content/edit-discussion") {
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }
            harness.forumDispatcher.register("POST", "/api/content/new-comment") {
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }
            harness.forumDispatcher.register("GET", "/api/content/floor-data") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.loadText("drand/floor_data_456.json"))
            }
            harness.externalDispatcher.register("GET", "$drandPathPrefix/5618965") {
                MockResponse().setResponseCode(200).setBody(
                    E2eFixtures.loadText("drand/drand_round_4500000.json")
                        .replace("\"round\": 4500000", "\"round\": 5618965"),
                )
            }

            // Part A: Composer publishes topic and auto-appends /lucky link via edit-discussion (without category key)
            val composer = harness.manage(TopicComposerViewModel(container = harness.buildAppContainer()))
            val stateJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) { composer.uiState.collect {} }
            val events = CopyOnWriteArrayList<TopicComposerUiEvent>()
            val eventJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) { composer.events.collect { events.add(it) } }

            composer.onTitleChanged("【抽奖】送 2 个邀请码")
            composer.onMarkdownChanged("回复参与抽奖，开奖后自动公布结果。")
            composer.setPendingLuckyDrawSpec(
                LuckyDrawSpec(
                    postId = 0L,
                    drawTimeEpochMillis = 1_764_000_000_000L,
                    winnerCount = 2,
                    firstFloor = 1,
                    removeDuplicates = true,
                ),
            )
            composer.onPublishClicked()

            val published = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (true) {
                        val hit = events.filterIsInstance<TopicComposerUiEvent.PublishedSuccess>().firstOrNull()
                        if (hit != null) return@withTimeout hit
                        kotlinx.coroutines.delay(15L)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    error("Unreachable")
                }
            }
            assertEquals(456L, published.createdTopicId)

            val newDiscReq = harness.forumDispatcher.recordedRequests().first { it.path == "/api/content/new-discussion" }
            assertFalse(newDiscReq.getHeader("csrf-token").isNullOrBlank())

            val editDiscReq = harness.forumDispatcher.recordedRequests().first { it.path == "/api/content/edit-discussion" }
            assertFalse(editDiscReq.getHeader("csrf-token").isNullOrBlank())
            val editJson = TempScriptParser.json.parseToJsonElement(editDiscReq.body.readUtf8()).jsonObject
            assertFalse(editJson.containsKey("category"))
            val expectedLuckyUrl =
                "https://www.nodeseek.com/lucky?post=456&time=1764000000000&count=2&start=1&duplicate=false&mode=view"
            assertTrue(editJson.getValue("content").jsonPrimitive.content.contains(expectedLuckyUrl))

            // Part B: Build winner reply markdown & post via ForumRepository.postReply
            val parsedSpec = DrandLuckyEngine.extractFirstLuckySpec(
                editJson.getValue("content").jsonPrimitive.content,
                fallbackPostId = 456L,
            )!!
            val outcome = harness.luckyDrawRepository.resolveLuckyDraw(
                spec = parsedSpec,
                viewerUid = 101L,
                nowEpochMillis = 1_764_000_010_000L,
            ).getOrThrow()
            val replyMarkdown = DrandLuckyEngine.buildWinnerReplyMarkdown(outcome)
            assertTrue(replyMarkdown.contains("@bob202"))
            assertTrue(replyMarkdown.contains("@dave205"))

            val replyResult = harness.forumRepository.postReply(456L, replyMarkdown)
            assertTrue(replyResult.isSuccess)
            val commentReq = harness.forumDispatcher.recordedRequests().first { it.path == "/api/content/new-comment" }
            assertFalse(commentReq.getHeader("csrf-token").isNullOrBlank())
            val commentJson = TempScriptParser.json.parseToJsonElement(commentReq.body.readUtf8()).jsonObject
            assertEquals("456", commentJson.getValue("postId").jsonPrimitive.content)
            assertTrue(commentJson.getValue("content").jsonPrimitive.content.contains("@bob202 @dave205"))

            harness.assertNoCookiesLeakedToExternalServer()
            stateJob.cancel()
            eventJob.cancel()
        }
    }

    @Test
    fun `E2E-T1-R2-5 activity hub item routes through deep-link router to lucky draw resolution without external browser`() = runTest {
        E2eTestHarness().use { harness ->
            val luckyUrl =
                "https://www.nodeseek.com/lucky?post=456&time=1764000000000&count=2&start=1&duplicate=false&mode=view"
            val rawItem = ForumWatchActivityItem(
                id = "ns-456",
                title = "送 2 个邀请码",
                sourceUrl = "https://www.nodeseek.com/post-456-1",
                luckyUrl = luckyUrl,
            )
            val normalizedItem = ActivityLotteryClassifier.normalizeItem(rawItem)
            val fakeRepo = object : ActivityRepository {
                private val _snapshot = MutableStateFlow(
                    ActivityFeedSnapshot(
                        activities = listOf(normalizedItem),
                        counts = ActivityCounts(all = 1, lottery = 1),
                    ),
                )
                override val snapshot: StateFlow<ActivityFeedSnapshot> = _snapshot.asStateFlow()
                override suspend fun loadCachedActivities(): ActivityFeedSnapshot = _snapshot.value
                override suspend fun refreshActivities(force: Boolean): Result<ActivityFeedSnapshot> =
                    Result.success(_snapshot.value)
                override suspend fun getActivityDetail(id: String, forceRefresh: Boolean): Result<ForumWatchActivityDetail> =
                    Result.failure(NoSuchElementException(id))
            }

            val activityVm = harness.manage(
                ActivityViewModel(
                    repository = fakeRepo,
                    clockMillis = { 1_764_000_010_000L },
                    enableClockTicker = false,
                ),
            )
            val collectJob = backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                activityVm.uiState.collect {}
            }
            val listedItem = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    activityVm.uiState.first { it.filteredActivities.isNotEmpty() }.filteredActivities.single()
                }
            }
            assertTrue(listedItem.isLottery)
            assertEquals(luckyUrl, listedItem.resolvedLotteryUrl)

            var uriHandlerCalled = false
            val recordingUriHandler = object : UriHandler {
                override fun openUri(uri: String) {
                    uriHandlerCalled = true
                }
            }
            var capturedDeepLink: NodePulseDeepLink.LuckyDraw? = null
            openNodeSeekOrExternalUrl(
                rawUrl = listedItem.resolvedLotteryUrl!!,
                uriHandler = recordingUriHandler,
                onOpenLuckyDraw = { capturedDeepLink = it },
            )
            assertFalse(uriHandlerCalled)
            assertNotNull(capturedDeepLink)

            harness.forumDispatcher.register("GET", "/api/content/floor-data") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.loadText("drand/floor_data_456.json"))
            }
            harness.externalDispatcher.register("GET", "$drandPathPrefix/5618965") {
                MockResponse().setResponseCode(200).setBody(
                    E2eFixtures.loadText("drand/drand_round_4500000.json")
                        .replace("\"round\": 4500000", "\"round\": 5618965"),
                )
            }

            val outcome = harness.luckyDrawRepository.resolveLuckyDraw(
                spec = capturedDeepLink!!.toSpec(),
                viewerUid = 202L,
                nowEpochMillis = 1_764_000_010_000L,
            ).getOrThrow()
            assertEquals(LuckyViewerStatus.MY_WIN, outcome.viewerStatus)
            harness.assertNoCookiesLeakedToExternalServer()
            collectJob.cancel()
        }
    }

    @Test
    fun `E2E-T2-R2-1 pre-draw skips Drand network call and HTTP 404 returns pending state gracefully`() = runTest {
        // Subcase A: before draw time -> externalServer NOT called
        E2eTestHarness().use { harness ->
            harness.forumDispatcher.register("GET", "/api/content/floor-data") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.loadText("drand/floor_data_456.json"))
            }
            val spec = LuckyDrawSpec(
                postId = 456L,
                drawTimeEpochMillis = 1_764_000_000_000L,
                winnerCount = 2,
                firstFloor = 1,
                removeDuplicates = true,
            )

            val preDraw = harness.luckyDrawRepository.resolveLuckyDraw(
                spec = spec,
                viewerUid = 101L,
                nowEpochMillis = 1_763_999_940_000L,
            ).getOrThrow()
            assertFalse(preDraw.isDrawn)
            assertNull(preDraw.beacon)
            assertTrue(preDraw.winners.isEmpty())
            assertEquals(LuckyViewerStatus.PARTICIPATED_PENDING, preDraw.viewerStatus)
            assertTrue(harness.externalDispatcher.recordedRequests().isEmpty())
        }

        // Subcase B: draw time reached, Drand returns 404 -> Result.success with isDrawn=false & beaconPendingMessage
        E2eTestHarness().use { harness ->
            harness.forumDispatcher.register("GET", "/api/content/floor-data") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.loadText("drand/floor_data_456.json"))
            }
            harness.externalDispatcher.register("GET", "$drandPathPrefix/5618965") {
                MockResponse().setResponseCode(404).setBody("""{"error":"not published yet"}""")
            }
            val spec = LuckyDrawSpec(
                postId = 456L,
                drawTimeEpochMillis = 1_764_000_000_000L,
                winnerCount = 2,
                firstFloor = 1,
                removeDuplicates = true,
            )

            val pendingResult = harness.luckyDrawRepository.resolveLuckyDraw(
                spec = spec,
                viewerUid = 101L,
                nowEpochMillis = 1_764_000_005_000L,
            )
            assertTrue(pendingResult.isSuccess)
            val pending = pendingResult.getOrThrow()
            assertFalse(pending.isDrawn)
            assertNull(pending.beacon)
            assertNotNull(pending.beaconPendingMessage)
            assertEquals(LuckyViewerStatus.PARTICIPATED_PENDING, pending.viewerStatus)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T2-R2-2 duplicate false deduplicates repeat user floors while duplicate true allows multiple wins`() = runTest {
        E2eTestHarness().use { harness ->
            harness.forumDispatcher.register("GET", "/api/content/floor-data") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.loadText("drand/floor_data_456.json"))
            }
            harness.externalDispatcher.register("GET", "$drandPathPrefix/5618965") {
                MockResponse().setResponseCode(200).setBody(
                    E2eFixtures.loadText("drand/drand_round_4500000.json")
                        .replace("\"round\": 4500000", "\"round\": 5618965"),
                )
            }

            val dedupOutcome = harness.luckyDrawRepository.resolveLuckyDraw(
                spec = LuckyDrawSpec(
                    postId = 456L,
                    drawTimeEpochMillis = 1_764_000_000_000L,
                    winnerCount = 6,
                    firstFloor = 1,
                    removeDuplicates = true,
                ),
                viewerUid = 201L,
                nowEpochMillis = 1_764_000_010_000L,
            ).getOrThrow()
            assertEquals(listOf(1, 2, 4, 5, 6), dedupOutcome.winners.map { it.floorId })
            assertEquals(1, dedupOutcome.winners.count { it.memberId == 201L })

            val allowDupOutcome = harness.luckyDrawRepository.resolveLuckyDraw(
                spec = LuckyDrawSpec(
                    postId = 456L,
                    drawTimeEpochMillis = 1_764_000_000_000L,
                    winnerCount = 6,
                    firstFloor = 1,
                    removeDuplicates = false,
                ),
                viewerUid = 201L,
                nowEpochMillis = 1_764_000_010_000L,
            ).getOrThrow()
            assertEquals(listOf(1, 2, 3, 4, 5, 6), allowDupOutcome.winners.map { it.floorId })
            assertEquals(2, allowDupOutcome.winners.count { it.memberId == 201L })
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T2-R2-3 start floor boundary excludes earlier floors and marks excluded participant INELIGIBLE_FLOOR`() = runTest {
        E2eTestHarness().use { harness ->
            harness.forumDispatcher.register("GET", "/api/content/floor-data") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.loadText("drand/floor_data_456.json"))
            }
            harness.externalDispatcher.register("GET", "$drandPathPrefix/5618965") {
                MockResponse().setResponseCode(200).setBody(
                    E2eFixtures.loadText("drand/drand_round_4500000.json")
                        .replace("\"round\": 4500000", "\"round\": 5618965"),
                )
            }

            val outcome = harness.luckyDrawRepository.resolveLuckyDraw(
                spec = LuckyDrawSpec(
                    postId = 456L,
                    drawTimeEpochMillis = 1_764_000_000_000L,
                    winnerCount = 2,
                    firstFloor = 5,
                    removeDuplicates = true,
                ),
                viewerUid = 202L, // bob202 posted on floor 2 < start=5
                nowEpochMillis = 1_764_000_010_000L,
            ).getOrThrow()

            assertEquals(2, outcome.eligibleFloorCount)
            assertEquals(listOf(5, 6), outcome.winners.map { it.floorId })
            assertEquals(LuckyViewerStatus.INELIGIBLE_FLOOR, outcome.viewerStatus)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T2-R2-4 winnerCount exceeding eligible members caps cleanly at all eligible unique winners`() = runTest {
        E2eTestHarness().use { harness ->
            harness.forumDispatcher.register("GET", "/api/content/floor-data") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.loadText("drand/floor_data_456.json"))
            }
            harness.externalDispatcher.register("GET", "$drandPathPrefix/5618965") {
                MockResponse().setResponseCode(200).setBody(
                    E2eFixtures.loadText("drand/drand_round_4500000.json")
                        .replace("\"round\": 4500000", "\"round\": 5618965"),
                )
            }

            val outcome = harness.luckyDrawRepository.resolveLuckyDraw(
                spec = LuckyDrawSpec(
                    postId = 456L,
                    drawTimeEpochMillis = 1_764_000_000_000L,
                    winnerCount = 20,
                    firstFloor = 1,
                    removeDuplicates = true,
                ),
                viewerUid = 205L,
                nowEpochMillis = 1_764_000_010_000L,
            ).getOrThrow()

            assertEquals(5, outcome.eligibleFloorCount)
            assertEquals(listOf(1, 2, 4, 5, 6), outcome.winners.map { it.floorId })
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T2-R2-5 Drand requests never leak NodeSeek session cookies or signing headers`() = runTest {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(
                uid = 101L,
                username = "alice101",
                sessionCookie = "secret_ns_cookie",
                cfClearance = "cf_secret",
            )
            harness.forumDispatcher.register("GET", "/api/content/floor-data") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.loadText("drand/floor_data_456.json"))
            }
            harness.externalDispatcher.register("GET", "$drandPathPrefix/5618965") {
                MockResponse().setResponseCode(200).setBody(
                    E2eFixtures.loadText("drand/drand_round_4500000.json")
                        .replace("\"round\": 4500000", "\"round\": 5618965"),
                )
            }

            harness.luckyDrawRepository.resolveLuckyDraw(
                spec = LuckyDrawSpec(
                    postId = 456L,
                    drawTimeEpochMillis = 1_764_000_000_000L,
                    winnerCount = 2,
                ),
                viewerUid = 101L,
                nowEpochMillis = 1_764_000_010_000L,
            ).getOrThrow()

            val floorReq = harness.forumDispatcher.recordedRequests()
                .first { it.path?.startsWith("/api/content/floor-data") == true }
            val floorCookie = floorReq.getHeader("Cookie").orEmpty()
            assertTrue(floorCookie.contains("session=secret_ns_cookie"))
            assertTrue(floorCookie.contains("cf_clearance=cf_secret"))

            val drandReq = harness.externalDispatcher.recordedRequests().single()
            assertNull(drandReq.getHeader("Cookie"))
            assertNull(drandReq.getHeader("x-dynamic-sign"))
            assertNull(drandReq.getHeader("x-csrf-token"))
            assertNull(drandReq.getHeader("x-security-token"))
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }
}
