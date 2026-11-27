package com.nodeseek.app.e2e.m5

import com.nodeseek.app.data.network.computeDynamicSign
import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.domain.activity.DrandLuckyEngine
import com.nodeseek.app.domain.activity.LuckyViewerStatus
import com.nodeseek.app.domain.image.BitmapMosaicProcessor
import com.nodeseek.app.domain.image.MosaicEdit
import com.nodeseek.app.domain.image.MosaicRect
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AccountSlotStatus
import com.nodeseek.app.domain.model.AccountSwitchResult
import com.nodeseek.app.domain.model.CookieHostScope
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.FloorComment
import com.nodeseek.app.domain.model.StoredCookieRecord
import com.nodeseek.app.e2e.E2eFixtures
import com.nodeseek.app.e2e.E2eMainDispatcherRule
import com.nodeseek.app.e2e.E2eTestHarness
import com.nodeseek.app.e2e.fixtures.E2eBitmapAndTlsFixtures
import com.nodeseek.app.ui.composer.ImagePrivacyBitmapCodec
import com.nodeseek.app.ui.composer.MosaicSupportStatus
import com.nodeseek.app.ui.composer.TopicComposerUiEvent
import com.nodeseek.app.ui.explore.ExploreViewModel
import com.nodeseek.app.ui.share.LongImageThemeStyle
import com.nodeseek.app.ui.share.TopicLongImageAssetFetcher
import com.nodeseek.app.ui.share.TopicLongImageQrGenerator
import com.nodeseek.app.ui.share.TopicLongImageRenderer
import com.nodeseek.app.ui.share.TopicLongImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class M5PairwiseE2eTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    private val validKey = "0123456789abcdef0123456789abcdef"

    private fun extractCookieValue(cookieHeader: String?, name: String): String? =
        cookieHeader.orEmpty()
            .split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')

    private fun buildThreadHtml(
        postId: Long,
        categorySlug: String,
        categoryWord: String,
        title: String,
        markdown: String,
        opUid: Long = 101L,
        opName: String = "alice101",
    ): String {
        val baseHtml = E2eFixtures.loadText("nodeseek/topic_trade_op_123.html")
        val encodedMatch = Regex("""<script id="temp-script"[^>]*>(.*?)</script>""").find(baseHtml)
            ?: error("Missing temp-script")
        val decodedJson = Base64.getDecoder().decode(encodedMatch.groupValues[1]).toString(Charsets.UTF_8)
        val catsJson = TempScriptParser.json.parseToJsonElement(decodedJson).jsonObject.getValue("allCategory").toString()
        val cfg = """{"user":{"member_id":$opUid,"member_name":${JsonPrimitive(opName)},"coin":1650,"stardust":42,"rank":4},"enableCustomedStyle":1,"allCategory":$catsJson,"postData":{"postId":$postId,"postPage":1,"postPageCount":1,"title":${JsonPrimitive(title)},"category":${JsonPrimitive(categorySlug)},"categoryWord":${JsonPrimitive(categoryWord)},"categoryLink":"/categories/$categorySlug","views":512,"collectionCount":12,"rank":0,"locked":0,"collected":0,"award":0,"op":{"uid":$opUid,"name":${JsonPrimitive(opName)},"avatar":"/avatar/$opUid.png"},"comments":[{"commentId":${postId * 100},"floorIndex":0,"poster":{"uid":$opUid,"name":${JsonPrimitive(opName)},"avatar":"/avatar/$opUid.png","isOp":1,"rank":4,"roles":[]},"time":{"createdDate":"2026-11-26T10:00:00Z","createdDateFormated":"2026-11-26 18:00:00"},"markdown":${JsonPrimitive(markdown)},"likeCount":5,"dislikeCount":0,"upvoteCount":4,"liked":0,"disliked":0,"upvoted":0,"pined":0,"hot":0,"blocked":0},{"commentId":${postId * 100 + 1},"floorIndex":1,"poster":{"uid":202,"name":"bob202","avatar":"/avatar/202.png","isOp":0,"rank":3,"roles":[]},"time":{"createdDate":"2026-11-26T10:05:00Z","createdDateFormated":"2026-11-26 18:05:00"},"markdown":"精彩测评支持！","likeCount":2,"dislikeCount":0,"upvoteCount":2,"liked":0,"disliked":0,"upvoted":0,"pined":1,"hot":0,"blocked":0}]}}"""
        return E2eFixtures.wrapTempScriptHtml(
            configJson = cfg,
            bodyHtml = """<div id="nsk-body"><div id="nsk-body-left"><div class="nsk-post"><h1>$title</h1><article class="post-content"></article></div></div></div>""",
            title = title,
        )
    }

    @Test
    fun `E2E-T3-16 R13 pre-upload mosaic redaction and NodeImage upload pair with R16 magazine long-image share and QR`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)

            val nodeImageJson = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeimage/nodeimage_responses.json"),
            ).jsonObject
            harness.externalDispatcher.register("POST", "/api/upload") {
                MockResponse().setResponseCode(200).setBody(nodeImageJson.getValue("uploadSuccess").toString())
            }
            val publishedContentRef = CopyOnWriteArrayList<String>()
            harness.forumDispatcher.register("POST", "/api/content/new-discussion") { req ->
                val body = req.body.readUtf8()
                val content = TempScriptParser.json.parseToJsonElement(body).jsonObject.getValue("content").jsonPrimitive.content
                publishedContentRef.add(content)
                MockResponse().setResponseCode(200).setBody("""{"success":true,"postId":901}""")
            }

            // 1. Pure-JVM mosaic redaction on pixel buffer + PNG inspection
            val pixels = IntArray(32 * 32) { idx ->
                val x = idx % 32
                val y = idx / 32
                (0xFF shl 24) or ((x * 7) shl 16) or ((y * 7) shl 8) or 0x42
            }
            val redactedPixels = BitmapMosaicProcessor.applyMosaicEdits(
                pixels,
                32,
                32,
                listOf(MosaicEdit.BoxRegion(MosaicRect(0.25f, 0.25f, 0.75f, 0.75f), blockSizeNorm = 0.25f)),
            )
            assertFalse(pixels.contentEquals(redactedPixels))

            val pngBytes = E2eBitmapAndTlsFixtures.solidRgbaPngBytes(32, 32, redactedPixels[8 * 32 + 8])
            assertTrue(ImagePrivacyBitmapCodec.inspectMosaicSupport(pngBytes, "image/png", "bench_redacted.png") is MosaicSupportStatus.SupportedStatic)

            // 2. Composer privacy gate -> NodeImage upload -> append ANSI + sticker -> publish post 901
            val composer = harness.createTopicComposerViewModel(initialCategorySlug = "review")
            val events = CopyOnWriteArrayList<TopicComposerUiEvent>()
            val cStateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { composer.uiState.collect {} }
            val cEventJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { composer.events.collect { events.add(it) } }

            composer.onPhotoBytesSelected(pngBytes, "image/png", "bench_redacted.png")
            withContext(Dispatchers.Default) { withTimeout(5_000L) { composer.uiState.first { it.showApiKeyDialog } } }
            composer.saveApiKeyAndUpload(validKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    composer.uiState.first {
                        !it.showApiKeyDialog &&
                            it.showPrivacyEditorSheet &&
                            it.settings.nodeImageApiKey == validKey
                    }
                }
            }
            composer.confirmPrivacyImageUpload(pngBytes, "image/png", "bench_redacted.png")

            val inserted = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (events.none { it is TopicComposerUiEvent.InsertUploadedMarkdown }) {
                        delay(15L)
                    }
                    events.filterIsInstance<TopicComposerUiEvent.InsertUploadedMarkdown>().single()
                }
            }
            val fullMarkdown = buildString {
                appendLine(inserted.snippet.trim())
                appendLine()
                appendLine("```ansi")
                appendLine("\u001B[32mCN2 GIA 9929 延迟 132ms\u001B[0m")
                appendLine("```")
                appendLine()
                appendLine("实测真香 :ac01:")
            }
            composer.onTitleChanged("DMIT Pro 隐私打码测评")
            composer.onMarkdownChanged(fullMarkdown)
            composer.onPublishClicked()

            val published = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (events.none { it is TopicComposerUiEvent.PublishedSuccess }) {
                        delay(15L)
                    }
                    events.filterIsInstance<TopicComposerUiEvent.PublishedSuccess>().single()
                }
            }
            assertEquals(901L, published.createdTopicId)

            // 3. Load TopicDetailViewModel(901L) -> resolveOpeningFloorForShare -> long-image assets/QR/text across themes
            val threadHtml = buildThreadHtml(
                postId = 901L,
                categorySlug = "review",
                categoryWord = "测评",
                title = "DMIT Pro 隐私打码测评",
                markdown = publishedContentRef.single(),
            )
            harness.forumDispatcher.register("GET", "/post-901-1") {
                MockResponse().setResponseCode(200).setBody(threadHtml)
            }
            val detailVm = harness.createTopicDetailViewModel(topicId = 901L)
            val dStateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { detailVm.uiState.collect {} }
            withContext(Dispatchers.Default) { withTimeout(5_000L) { detailVm.uiState.first { it.pageData != null } } }

            val openingFloor = detailVm.resolveOpeningFloorForShare()
            assertNotNull(openingFloor)
            val highlightReply = detailVm.uiState.value.displayedReplies.firstOrNull()
            assertNotNull(highlightReply)

            for (style in LongImageThemeStyle.entries) {
                val req = TopicLongImageRequest(
                    topicId = 901L,
                    title = "DMIT Pro 隐私打码测评",
                    categorySlug = "review",
                    categoryName = "测评",
                    targetFloor = openingFloor!!,
                    highlightReply = highlightReply,
                    topicUrl = "https://www.nodeseek.com/post-901-1",
                    themeStyle = style,
                    includeQrCode = true,
                )
                val urls = TopicLongImageAssetFetcher.collectRequiredAssetUrls(req)
                assertTrue(urls.contains("https://img.nodeimage.com/i/img_e2e_01.png"))
                assertTrue(urls.contains("https://www.nodeseek.com/static/image/sticker/ac/01.png"))

                val qr = TopicLongImageQrGenerator.generateQrMatrix(req.topicUrl)
                assertEquals(qr.width, qr.height)
                assertTrue(qr.width >= 21)
                assertTrue(qr.darkModuleCount() > 50)

                val renderedTexts = TopicLongImageRenderer.extractRenderedTexts(req).joinToString("\n")
                assertTrue(renderedTexts.contains("DMIT Pro 隐私打码测评"))
                assertTrue(renderedTexts.contains("alice101"))
                assertTrue(renderedTexts.contains("CN2 GIA 9929 延迟 132ms"))
                assertFalse(renderedTexts.contains("\u001B["))
                assertTrue(renderedTexts.contains(":ac01:"))
                assertTrue(renderedTexts.contains("精彩测评支持！"))
                assertTrue(renderedTexts.contains("https://www.nodeseek.com/post-901-1"))
            }

            harness.assertNoCookiesLeakedToExternalServer()
            cStateJob.cancel()
            cEventJob.cancel()
            dStateJob.cancel()
        }
    }

    @Test
    fun `E2E-T3-17 R1 poll insertion and R13 mosaic-redacted NodeImage compose publish and vote end-to-end`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", rank = 4)
            harness.settingsRepository.setNodeImageApiKey(validKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { harness.settingsRepository.settings.first { it.nodeImageApiKey == validKey } }
            }

            val nodeImageJson = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeimage/nodeimage_responses.json"),
            ).jsonObject
            harness.externalDispatcher.register("POST", "/api/upload") {
                MockResponse().setResponseCode(200).setBody(nodeImageJson.getValue("uploadSuccess").toString())
            }
            harness.forumDispatcher.register("POST", "/api/vote/info") {
                MockResponse().setResponseCode(200).setBody(
                    """{"success":true,"vote":{"id":99,"title":"选择测评机器","uid":101,"isPublic":1,"locked":0,"multiple":0,"items":[{"vote_item_id":501,"text":"DMIT Pro","count":0,"voted":0,"voters":[]},{"vote_item_id":502,"text":"Bandwagon","count":0,"voted":0,"voters":[]}]}}""",
                )
            }
            val publishedMdList = CopyOnWriteArrayList<String>()
            harness.forumDispatcher.register("POST", "/api/content/new-discussion") { req ->
                val content = TempScriptParser.json.parseToJsonElement(req.body.readUtf8())
                    .jsonObject.getValue("content").jsonPrimitive.content
                publishedMdList.add(content)
                MockResponse().setResponseCode(200).setBody("""{"success":true,"postId":902}""")
            }

            val composer = harness.createTopicComposerViewModel(initialCategorySlug = "tech")
            val events = CopyOnWriteArrayList<TopicComposerUiEvent>()
            val cStateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { composer.uiState.collect {} }
            val cEventJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { composer.events.collect { events.add(it) } }

            // 1. Insert nsapp://vote poll
            composer.createPollAndInsert(
                title = "选择测评机器",
                multiple = false,
                isPublic = true,
                options = listOf("DMIT Pro", "Bandwagon"),
            )
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (events.filterIsInstance<TopicComposerUiEvent.InsertUploadedMarkdown>().isEmpty()) {
                        delay(15L)
                    }
                }
            }

            // 2. Insert mosaic-redacted NodeImage
            val pngBytes = E2eBitmapAndTlsFixtures.solidRgbaPngBytes(16, 16)
            composer.onPhotoBytesSelected(pngBytes, "image/png", "redacted.png")
            withContext(Dispatchers.Default) { withTimeout(5_000L) { composer.uiState.first { it.showPrivacyEditorSheet } } }
            composer.confirmPrivacyImageUpload(pngBytes, "image/png", "redacted.png")
            val snippets = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (events.filterIsInstance<TopicComposerUiEvent.InsertUploadedMarkdown>().size < 2) {
                        delay(15L)
                    }
                    events.filterIsInstance<TopicComposerUiEvent.InsertUploadedMarkdown>().map { it.snippet }
                }
            }

            assertTrue(snippets[0].contains("nsapp://vote?id=99"))
            assertTrue(snippets[1].contains("https://img.nodeimage.com/i/img_e2e_01.png"))

            composer.onTitleChanged("投票与测速图组合帖")
            composer.onMarkdownChanged(snippets.joinToString("\n") { it.trim() })
            composer.onPublishClicked()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (events.none { it is TopicComposerUiEvent.PublishedSuccess }) {
                        delay(15L)
                    }
                }
            }

            // 3. Load TopicDetailViewModel(902L) -> verify openingFloor preserves poll & image markdown -> cast vote
            val threadHtml = buildThreadHtml(
                postId = 902L,
                categorySlug = "tech",
                categoryWord = "技术",
                title = "投票与测速图组合帖",
                markdown = publishedMdList.single(),
            )
            harness.forumDispatcher.register("GET", "/post-902-1") {
                MockResponse().setResponseCode(200).setBody(threadHtml)
            }
            val preVoteJson = """{"success":true,"vote":{"id":99,"title":"选择测评机器","uid":101,"isPublic":1,"locked":0,"multiple":0,"items":[{"vote_item_id":501,"text":"DMIT Pro","count":0,"voted":0,"voters":[]},{"vote_item_id":502,"text":"Bandwagon","count":0,"voted":0,"voters":[]}]}}"""
            val postVoteJson = """{"success":true,"vote":{"id":99,"title":"选择测评机器","uid":101,"isPublic":1,"locked":0,"multiple":0,"items":[{"vote_item_id":501,"text":"DMIT Pro","count":1,"voted":1,"voters":[101]},{"vote_item_id":502,"text":"Bandwagon","count":0,"voted":0,"voters":[]}]}}"""
            harness.forumDispatcher.enqueueNext("GET", "/api/vote/info/99", MockResponse().setResponseCode(200).setBody(preVoteJson))
            harness.forumDispatcher.enqueueNext("GET", "/api/vote/info/99", MockResponse().setResponseCode(200).setBody(postVoteJson))
            harness.forumDispatcher.register("POST", "/api/vote/voteforitem") {
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            val detailVm = harness.createTopicDetailViewModel(topicId = 902L)
            val dStateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { detailVm.uiState.collect {} }
            val loaded = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { detailVm.uiState.first { it.pageData != null } }
            }
            val openingMd = loaded.openingFloor?.markdownBody.orEmpty()
            assertTrue(openingMd.contains("[选择测评机器](nsapp://vote?id=99)"))
            assertTrue(openingMd.contains("![image](https://img.nodeimage.com/i/img_e2e_01.png)"))

            // Long-image asset fetcher also resolves uploaded image from published opening floor
            val longReq = TopicLongImageRequest(
                topicId = 902L,
                title = "投票与测速图组合帖",
                categorySlug = "tech",
                categoryName = "技术",
                targetFloor = loaded.openingFloor!!,
                topicUrl = "https://www.nodeseek.com/post-902-1",
            )
            assertTrue(TopicLongImageAssetFetcher.collectRequiredAssetUrls(longReq).contains("https://img.nodeimage.com/i/img_e2e_01.png"))
            assertTrue(TopicLongImageRenderer.extractRenderedTexts(longReq).joinToString("\n").contains("选择测评机器"))

            detailVm.nsAppWidgetController.ensureVoteLoaded(99L)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { detailVm.nsAppWidgetController.pollStateFor(99L).first { it.poll != null } }
            }
            detailVm.nsAppWidgetController.togglePollOption(99L, 501L, isMultiple = false)
            detailVm.nsAppWidgetController.submitVote(99L)
            val voted = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    detailVm.nsAppWidgetController.pollStateFor(99L).first { !it.isSubmitting && it.poll?.hasCurrentUserVoted == true }
                }
            }
            assertEquals(1, voted.poll!!.options[0].count)

            val voteReq = harness.forumDispatcher.recordedRequests().first { it.path == "/api/vote/voteforitem" }
            val voteBody = voteReq.body.readUtf8()
            val expectedSign = computeDynamicSign("POST", voteReq.requestUrl.toString(), harness.testUserAgent, voteBody)
            assertEquals(expectedSign, voteReq.getHeader("x-dynamic-sign"))
            val votePayload = TempScriptParser.json.parseToJsonElement(voteBody).jsonObject
            assertEquals(listOf("501"), votePayload.getValue("ids").jsonArray.map { it.jsonPrimitive.content })
            harness.assertNoCookiesLeakedToExternalServer()

            cStateJob.cancel()
            cEventJob.cancel()
            dStateJob.cancel()
        }
    }

    @Test
    fun `E2E-T3-18 R4 R16 and R2 R16 account switch switches homepage categories and lucky viewer win-check identity`() = runBlocking {
        E2eTestHarness(useHttpsNodeSeekOrigin = true).use { harness ->
            val home101 = E2eFixtures.loadText("nodeseek/home_logged_in_uid101.html")
            val home202 = E2eFixtures.loadText("nodeseek/home_logged_in_uid202.html")
            val homepageComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/homepage_categories.json"),
            ).jsonObject
            val hp101 = homepageComposite.getValue("uid101").toString()
            val hp202 = homepageComposite.getValue("uid202").toString()

            val luckyHtml = E2eFixtures.loadText("nodeseek/topic_lucky_draw_456.html")
            val floorDataJson = E2eFixtures.loadText("drand/floor_data_456.json")
            val drandJson = E2eFixtures.loadText("drand/drand_round_4500000.json")
                .replace("\"round\": 4500000", "\"round\": 5618965")
            val drandPath = "/${DrandLuckyEngine.DRAND_CHAIN_HASH}/public/5618965"

            harness.forumDispatcher.register("GET", "/") { req ->
                when (extractCookieValue(req.getHeader("Cookie"), "session")) {
                    "sess_bob" -> MockResponse().setResponseCode(200).setBody(home202)
                    else -> MockResponse().setResponseCode(200).setBody(home101)
                }
            }
            harness.forumDispatcher.register("GET", "/api/homepage") { req ->
                when (extractCookieValue(req.getHeader("Cookie"), "session")) {
                    "sess_bob" -> MockResponse().setResponseCode(200).setBody(hp202)
                    else -> MockResponse().setResponseCode(200).setBody(hp101)
                }
            }
            harness.forumDispatcher.register("GET", "/post-456-1") {
                MockResponse().setResponseCode(200).setBody(luckyHtml)
            }
            harness.forumDispatcher.register("GET", "/api/content/floor-data") {
                MockResponse().setResponseCode(200).setBody(floorDataJson)
            }
            harness.externalDispatcher.register("GET", drandPath) {
                MockResponse().setResponseCode(200).setBody(drandJson)
            }

            // Seed A (101L) active & B (202L) ready in vault
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", sessionCookie = "sess_alice")
            harness.sessionRepository.refreshSession().getOrThrow()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.categoryDao.observeCategories(101L).first { it.isNotEmpty() }
                }
            }
            harness.forumRepository.syncHomepageCategories().getOrThrow()
            harness.multiAccountManager.initializeAndSyncActiveAccount()
            harness.accountCookieVault.saveOrUpdateSlot(
                session = AccountSession(uid = 202L, username = "bob202"),
                cookies = listOf(StoredCookieRecord("session", "sess_bob", CookieHostScope.SHARED_DOT_DOMAIN)),
                status = AccountSlotStatus.READY,
            )

            val exploreVm = harness.manage(
                ExploreViewModel(
                    forumRepository = harness.forumRepository,
                    sessionRepository = harness.sessionRepository,
                    bookmarkAndHistoryRepository = harness.bookmarkAndHistoryRepository,
                    settingsRepository = harness.settingsRepository,
                    contentFilterRepository = harness.contentFilterRepository,
                ),
            )
            val exploreJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { exploreVm.uiState.collect {} }

            // 1. Account A (101L): homepage tabs show trade & hide daily; /lucky shows PARTICIPATED_NOT_WON
            val stateA = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    exploreVm.uiState.first { state ->
                        val slugs = state.orderedBoardTabs.map { it.slug }
                        !state.isSyncingCategories && "trade" in slugs && "daily" !in slugs
                    }
                }
            }
            assertEquals(listOf("", "tech", "trade", "review"), stateA.orderedBoardTabs.take(4).map { it.slug })
            assertTrue(stateA.orderedBoardTabs.any { it.slug == "tech" })
            assertFalse(stateA.orderedBoardTabs.any { it.slug == "daily" })
            val catsA = harness.categoryDao.getCategories(101L)
            assertEquals(true, catsA.first { it.slug == "trade" }.visibleOnHome)
            assertEquals(false, catsA.first { it.slug == "daily" }.visibleOnHome)

            val threadDetail = harness.forumRepository.getTopicDetail(456L).getOrThrow()
            val luckySpec = DrandLuckyEngine.extractFirstLuckySpec(threadDetail.openingFloor?.markdownBody.orEmpty(), fallbackPostId = 456L)!!
            val outcomeA = harness.luckyDrawRepository.resolveLuckyDraw(
                spec = luckySpec,
                viewerUid = harness.sessionRepository.sessionState.value?.uid,
                nowEpochMillis = 1_764_000_010_000L,
            ).getOrThrow()
            assertEquals(LuckyViewerStatus.PARTICIPATED_NOT_WON, outcomeA.viewerStatus)
            assertEquals(listOf(4), outcomeA.viewerFloors.map { it.floorId })
            assertTrue(outcomeA.viewerWinningFloors.isEmpty())

            // 2. Switch to Account B (202L): B's server order applies automatically via per-account isolation, verify tabs + /lucky MY_WIN
            val switchRes = harness.multiAccountManager.switchAccount(202L)
            assertTrue(switchRes is AccountSwitchResult.Switched)
            assertEquals(202L, harness.sessionRepository.sessionState.value?.uid)

            val stateB = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    exploreVm.uiState.first { state ->
                        val slugs = state.orderedBoardTabs.map { it.slug }
                        !state.isSyncingCategories &&
                            state.session?.uid == 202L &&
                            "daily" in slugs &&
                            "carpool" in slugs &&
                            "trade" !in slugs
                    }
                }
            }
            assertEquals(listOf("", "carpool", "info", "daily"), stateB.orderedBoardTabs.take(4).map { it.slug })
            assertTrue(stateB.orderedBoardTabs.any { it.slug == "daily" })
            assertFalse(stateB.orderedBoardTabs.any { it.slug == "trade" })
            val catsB = harness.categoryDao.getCategories(202L)
            assertEquals(false, catsB.first { it.slug == "trade" }.visibleOnHome)
            assertEquals(true, catsB.first { it.slug == "daily" }.visibleOnHome)
            // Account A's Room categories and persisted homepageCategoryOrder remain untouched
            assertEquals(true, harness.categoryDao.getCategories(101L).first { it.slug == "trade" }.visibleOnHome)
            assertEquals(
                listOf("tech", "trade", "review", "daily"),
                harness.settingsRepository.getHomepageCategoryOrder(101L).take(4),
            )

            val outcomeB = harness.luckyDrawRepository.resolveLuckyDraw(
                spec = luckySpec,
                viewerUid = harness.sessionRepository.sessionState.value?.uid,
                nowEpochMillis = 1_764_000_010_000L,
            ).getOrThrow()
            assertEquals(LuckyViewerStatus.MY_WIN, outcomeB.viewerStatus)
            assertEquals(listOf(2), outcomeB.viewerWinningFloors.map { it.floorId })
            harness.assertNoCookiesLeakedToExternalServer()
            exploreJob.cancel()
        }
    }
}
