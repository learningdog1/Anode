package com.nodeseek.app.e2e.m5

import android.content.ContextWrapper
import com.nodeseek.app.data.local.BookmarkEntity
import com.nodeseek.app.data.local.DraftEntity
import com.nodeseek.app.data.local.ReadHistoryEntity
import com.nodeseek.app.data.local.SoftwareSettingsSecretCipher
import com.nodeseek.app.data.network.CoilHttpClientFactory
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.data.parser.NodeSeekHtmlParser
import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.data.repository.FileAccountCookieVault
import com.nodeseek.app.data.repository.MultiAccountManagerImpl
import com.nodeseek.app.data.trust.SellerTrustRepositoryImpl
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AccountSlotStatus
import com.nodeseek.app.domain.model.AccountSwitchResult
import com.nodeseek.app.domain.model.CookieHostScope
import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.FloorComment
import com.nodeseek.app.domain.model.StoredCookieRecord
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.radar.RadarBackgroundPollOutcome
import com.nodeseek.app.domain.repository.SignInStepResult
import com.nodeseek.app.e2e.E2eFixtures
import com.nodeseek.app.e2e.E2eMainDispatcherRule
import com.nodeseek.app.e2e.E2eTestHarness
import com.nodeseek.app.e2e.fixtures.E2eBitmapAndTlsFixtures
import com.nodeseek.app.ui.share.LongImageThemeStyle
import com.nodeseek.app.ui.share.TopicLongImageAssetFetcher
import com.nodeseek.app.ui.share.TopicLongImageQrGenerator
import com.nodeseek.app.ui.share.TopicLongImageRenderer
import com.nodeseek.app.ui.share.TopicLongImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class R16LongImageAndMultiAccountE2eTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    private fun extractCookieValue(cookieHeader: String?, name: String): String? =
        cookieHeader.orEmpty()
            .split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')

    private fun buildHomeHtml(uid: Long?, username: String, coin: Int = 500, stardust: Int = 20): String {
        val userJson = if (uid != null) {
            """{"member_id":$uid,"member_name":"$username","coin":$coin,"stardust":$stardust,"rank":4,"unViewedCount":{"all":3}}"""
        } else {
            "null"
        }
        return E2eFixtures.wrapTempScriptHtml(
            configJson = """{"enableCustomedStyle":1,"user":$userJson,"allCategory":[{"key":"tech","cn_text":"技术"},{"key":"daily","cn_text":"日常"}]}""",
        )
    }

    @Test
    fun `E2E-T1-R16-1 TopicLongImageRenderer extractRenderedTexts QR matrix and asset URLs across all 3 themes`() {
        val parsed = NodeSeekHtmlParser.parseThreadPage(
            html = E2eFixtures.loadText("nodeseek/topic_long_image_701.html"),
            pageUrl = "https://www.nodeseek.com/post-701-1",
        )
        val opening = parsed.openingComment!!
        val highlight = parsed.replies.single()
        val openingFloor = FloorComment(
            commentId = opening.commentId,
            floorNumber = 0,
            author = FloorAuthor(
                uid = 101L,
                username = "alice101",
                avatarUrl = "https://www.nodeseek.com/avatar/101.png",
                isTopicAuthor = true,
                level = 4,
            ),
            createdAtIso = opening.time?.createdDate.orEmpty(),
            markdownBody = opening.markdown,
            signatureMarkdown = opening.signatureMarkdown,
        )
        val highlightFloor = FloorComment(
            commentId = highlight.commentId,
            floorNumber = highlight.floorIndex,
            author = FloorAuthor(
                uid = 202L,
                username = "bob202",
                avatarUrl = "https://www.nodeseek.com/avatar/202.png",
                isTopicAuthor = false,
                level = 3,
            ),
            createdAtIso = highlight.time?.createdDate.orEmpty(),
            markdownBody = highlight.markdown,
            isPinned = highlight.pined == true,
        )

        val qr = TopicLongImageQrGenerator.generateQrMatrix("https://www.nodeseek.com/post-701-1")
        assertEquals(qr.width, qr.height)
        assertTrue(qr.width >= 21)
        assertTrue(qr.darkModuleCount() > 50)

        for (style in LongImageThemeStyle.entries) {
            val request = TopicLongImageRequest(
                topicId = 701L,
                title = parsed.postData.title,
                categorySlug = "review",
                categoryName = "测评",
                targetFloor = openingFloor,
                highlightReply = highlightFloor,
                topicUrl = "https://www.nodeseek.com/post-701-1",
                viewCount = 2048,
                bookmarkCount = 64,
                themeStyle = style,
                includeSignature = true,
                includeQrCode = true,
            )

            val assetUrls = TopicLongImageAssetFetcher.collectRequiredAssetUrls(request)
            assertTrue(assetUrls.contains("https://www.nodeseek.com/static/image/sticker/emoji/00.png"))
            assertTrue(assetUrls.contains("https://www.nodeseek.com/static/image/sticker/ac/01.png"))
            assertTrue(assetUrls.contains("https://www.nodeseek.com/avatar/101.png"))
            assertTrue(assetUrls.contains("https://img.nodeimage.com/i/img_e2e_01.png"))

            val texts = TopicLongImageRenderer.extractRenderedTexts(request)
            val joined = texts.joinToString("\n")
            assertTrue(joined.contains("DMIT LAX.Pro 杂志长图深度测评"))
            assertTrue(joined.contains("alice101"))
            assertTrue(joined.contains("Lv.4"))
            assertTrue(joined.contains("CN2 GIA 延迟 138ms 0% Loss"))
            assertFalse(joined.contains("\u001B["))
            assertTrue(joined.contains("ANSI"))
            assertTrue(joined.contains("项目"))
            assertTrue(joined.contains("节点规格"))
            assertTrue(joined.contains(":ac01:"))
            assertTrue(joined.contains(":emoji00:"))
            assertTrue(joined.contains("签名 · SIGNATURE"))
            assertTrue(joined.contains("#12 置顶回复"))
            assertTrue(joined.contains("Anode · NodeSeek 长图分享"))
            assertTrue(joined.contains("https://www.nodeseek.com/post-701-1"))
        }
    }

    @Test
    fun `E2E-T1-R16-2 sub-page resolveOpeningFloorForShare and cookie-isolated TopicLongImageAssetFetcher prefetch`() = runBlocking {
        E2eTestHarness(useHttpsNodeSeekOrigin = true).use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", sessionCookie = "r16_p1_sess")
            val p1Html = E2eFixtures.loadText("nodeseek/topic_long_image_701.html")
            val p2Html = E2eFixtures.loadText("nodeseek/topic_long_image_701_page2.html")

            harness.forumDispatcher.register("GET", "/post-701-2") {
                MockResponse().setResponseCode(200).setBody(p2Html)
            }
            // Fail first background prefetch of page 1 so openingFloor starts null, then succeed on resolveOpeningFloorForShare()
            val page1Attempts = AtomicInteger(0)
            harness.forumDispatcher.register("GET", "/post-701-1") {
                if (page1Attempts.getAndIncrement() == 0) {
                    MockResponse().setResponseCode(500)
                } else {
                    MockResponse().setResponseCode(200).setBody(p1Html)
                }
            }

            val container = harness.buildAppContainer()
            val vm = harness.createTopicDetailViewModel(topicId = 701L, initialFloor = 12, container = container)
            val stateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        val page = it.pageData
                        page != null && page.currentPage == 2
                    }
                    while (page1Attempts.get() < 1 || harness.forumDispatcher.recordedRequests().none { it.path == "/post-701-1" }) {
                        delay(10L)
                    }
                }
            }

            assertSame(harness.coilOkHttpClient, vm.imageFetchClient)
            val resolvedOpening = vm.resolveOpeningFloorForShare()
            assertNotNull(resolvedOpening)
            assertEquals(0, resolvedOpening!!.floorNumber)
            assertTrue(resolvedOpening.markdownBody.contains("DMIT LAX Pro"))

            val extImgUrl = harness.externalServer.url("/assets/bench.png").toString()
            harness.externalDispatcher.register("GET", "/assets/bench.png") {
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
                    .setBody(okio.Buffer().write(E2eBitmapAndTlsFixtures.minimalPngBytes()))
            }

            val req = TopicLongImageRequest(
                topicId = 701L,
                title = "Subpage Share",
                categorySlug = "review",
                categoryName = "测评",
                targetFloor = resolvedOpening.copy(
                    author = resolvedOpening.author.copy(avatarUrl = ""),
                    markdownBody = "![bench]($extImgUrl)",
                ),
                topicUrl = "https://www.nodeseek.com/post-701-1",
            )
            TopicLongImageAssetFetcher.prefetch(
                context = ContextWrapper(null),
                request = req,
                coilOkHttpClient = vm.imageFetchClient,
            ).close()

            val extReq = harness.externalDispatcher.recordedRequests().single()
            assertEquals("/assets/bench.png", extReq.path)
            assertEquals("https://www.nodeseek.com/", extReq.getHeader("Referer"))
            assertFalse(extReq.getHeader("Accept").isNullOrBlank())
            harness.assertNoCookiesLeakedToExternalServer()
            stateJob.cancel()
        }
    }

    @Test
    fun `E2E-T1-R16-3 multi-account switch A to B preserves host scopes cf_clearance strips sortBy clears caches and never calls signOut`() = runBlocking {
        E2eTestHarness(useHttpsNodeSeekOrigin = true).use { harness ->
            val wwwUrl = "https://www.nodeseek.com:${harness.forumServer.port}/".toHttpUrl()
            val bareUrl = "https://nodeseek.com:${harness.forumServer.port}/".toHttpUrl()
            val future = System.currentTimeMillis() + 3_600_000L

            harness.cookieBridge.saveFromResponse(
                wwwUrl,
                listOf(
                    Cookie.Builder().name("session").value("sess_alice").domain("nodeseek.com").path("/").secure().expiresAt(future).build(),
                    Cookie.Builder().name("smac").value("smac_alice").hostOnlyDomain("www.nodeseek.com").path("/").secure().expiresAt(future).build(),
                    Cookie.Builder().name("cf_clearance").value("cf_keep_1").domain("nodeseek.com").path("/").secure().expiresAt(future).build(),
                    Cookie.Builder().name("sortBy").value("replyTime").domain("nodeseek.com").path("/").expiresAt(future).build(),
                    Cookie.Builder().name("__cf_bm").value("cf_bm_noise").domain("nodeseek.com").path("/").expiresAt(future).build(),
                    Cookie.Builder().name("colorscheme").value("dark").domain("nodeseek.com").path("/").expiresAt(future).build(),
                ),
            )
            harness.cookieBridge.saveFromResponse(
                bareUrl,
                listOf(
                    Cookie.Builder().name("bare_tok").value("bare_alice").hostOnlyDomain("nodeseek.com").path("/").secure().expiresAt(future).build(),
                ),
            )

            val recordedCookies = CopyOnWriteArrayList<String>()
            harness.forumDispatcher.register("GET", "/") { req ->
                val cookie = req.getHeader("Cookie").orEmpty()
                recordedCookies.add(cookie)
                when (extractCookieValue(cookie, "session")) {
                    "sess_alice" -> MockResponse().setResponseCode(200).setBody(buildHomeHtml(101L, "alice101", coin = 450, stardust = 10))
                    "sess_bob" -> MockResponse().setResponseCode(200).setBody(buildHomeHtml(202L, "bob202", coin = 920, stardust = 30))
                    else -> MockResponse().setResponseCode(200).setBody(buildHomeHtml(null, ""))
                }
            }

            harness.sessionRepository.refreshSession().getOrThrow()
            harness.multiAccountManager.initializeAndSyncActiveAccount()
            assertEquals(101L, harness.sessionRepository.sessionState.value?.uid)

            // Populate caches + CSRF tokens for Account A
            harness.csrfTokenStore.seedCsrfToken("alice_csrf")
            harness.csrfTokenStore.seedSecurityToken("alice_sec.tok")
            harness.forumDispatcher.register("GET", "/api/attendance/board") {
                MockResponse().setResponseCode(200).setBody(
                    """{"hasCheckedInToday":true,"order":1,"total":10,"record":{"memberId":101,"memberName":"alice101","gain":5,"createdAt":"2026-11-26T08:00:00Z"},"list":[]}""",
                )
            }
            harness.checkInRepository.getCheckInBoard(page = 1, forceRefresh = true)
            assertNotNull(harness.checkInRepository.checkInStatus.value)
            harness.bookmarkAndHistoryRepository.cacheTopicSummaries(
                listOf(TopicSummary(id = 555L, title = "Cached A", categorySlug = "tech", categoryName = "技术", authorName = "alice101")),
            )
            assertNotNull(harness.bookmarkAndHistoryRepository.getCachedTopicSummary(555L))

            // Save Account B slot in vault
            harness.accountCookieVault.saveOrUpdateSlot(
                session = AccountSession(uid = 202L, username = "bob202", drumsticks = 920, stardust = 30),
                cookies = listOf(StoredCookieRecord("session", "sess_bob", CookieHostScope.SHARED_DOT_DOMAIN)),
                status = AccountSlotStatus.READY,
            )

            val switchResult = harness.multiAccountManager.switchAccount(202L)
            assertTrue("Expected Switched, got $switchResult", switchResult is AccountSwitchResult.Switched)
            assertEquals(202L, harness.sessionRepository.sessionState.value?.uid)
            assertEquals("bob202", harness.sessionRepository.sessionState.value?.username)

            // Vault statuses and encrypted-at-rest verification
            assertEquals(AccountSlotStatus.READY, harness.accountCookieVault.getSlot(101L)?.status)
            assertEquals(AccountSlotStatus.ACTIVE, harness.accountCookieVault.getSlot(202L)?.status)
            val diskVault = harness.accountCookieVaultFile.readText(Charsets.UTF_8)
            assertTrue(diskVault.contains("v1:sw:"))
            assertFalse(diskVault.contains("sess_alice"))
            assertFalse(diskVault.contains("sess_bob"))
            assertFalse(diskVault.contains("smac_alice"))
            assertFalse(diskVault.contains("bare_alice"))

            // Caches & CSRF cleared; cf_clearance preserved; sortBy stripped; wire request used sess_bob; zero signOut
            assertNull(harness.csrfTokenStore.csrfToken)
            assertNull(harness.csrfTokenStore.securityToken)
            assertNull(harness.checkInRepository.checkInStatus.value)
            assertNull(harness.bookmarkAndHistoryRepository.getCachedTopicSummary(555L))
            assertTrue(harness.cookieBridge.hasClearanceCookie("https://www.nodeseek.com/".toHttpUrl()))
            assertTrue(harness.cookieBridge.loadForRequest("https://www.nodeseek.com/".toHttpUrl()).none { it.name == "sortBy" })
            assertTrue(recordedCookies.last().contains("session=sess_bob"))
            assertFalse(harness.forumDispatcher.recordedRequests().any { it.path?.contains("signOut") == true })
        }
    }

    @Test
    fun `E2E-T1-R16-4 beginAddAccount cancel rollback native sign-in addition and explicit sign-out remove only signed-out slot`() = runBlocking {
        E2eTestHarness(useHttpsNodeSeekOrigin = true).use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", sessionCookie = "sess_alice")
            harness.forumDispatcher.register("GET", "/") { req ->
                when (extractCookieValue(req.getHeader("Cookie"), "session")) {
                    "sess_alice" -> MockResponse().setResponseCode(200).setBody(buildHomeHtml(101L, "alice101"))
                    "sess_bob" -> MockResponse().setResponseCode(200).setBody(buildHomeHtml(202L, "bob202"))
                    else -> MockResponse().setResponseCode(200).setBody(buildHomeHtml(null, ""))
                }
            }
            harness.forumDispatcher.register("POST", "/api/account/signIn") {
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Set-Cookie", "session=sess_bob; Path=/; Secure")
                    .setBody("""{"success":true,"message":"ok"}""")
            }
            harness.forumDispatcher.register("POST", "/api/account/signOut") {
                MockResponse().setResponseCode(200).setBody("""{"success":true}""")
            }

            harness.sessionRepository.refreshSession().getOrThrow()
            harness.multiAccountManager.initializeAndSyncActiveAccount()

            // 1. beginAddAccount -> local guest without calling signOut -> cancel restores A
            assertTrue(harness.multiAccountManager.beginAddAccount().isSuccess)
            assertNull(harness.sessionRepository.sessionState.value)
            assertFalse(harness.cookieBridge.hasAnyAuthCookie())
            assertTrue(harness.multiAccountManager.pendingAddAccountFlow.value)
            assertFalse(harness.forumDispatcher.recordedRequests().any { it.path?.contains("signOut") == true })

            val restored = harness.multiAccountManager.cancelAddAccountIfUnauthenticated()
            assertEquals(101L, restored?.uid)
            assertEquals(101L, harness.sessionRepository.sessionState.value?.uid)
            assertFalse(harness.multiAccountManager.pendingAddAccountFlow.value)

            // 2. beginAddAccount -> signIn as B -> both A (READY) & B (ACTIVE) in vault
            assertTrue(harness.multiAccountManager.beginAddAccount().isSuccess)
            val signInRes = harness.sessionRepository.signIn("bob202", "pw123456", "turnstile_tok")
            assertEquals(SignInStepResult.Authenticated, signInRes.getOrThrow())
            assertEquals(202L, harness.sessionRepository.sessionState.value?.uid)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    harness.accountCookieVault.slotsFlow.first { slots ->
                        slots.any { it.session.uid == 202L && it.status == AccountSlotStatus.ACTIVE }
                    }
                    harness.multiAccountManager.pendingAddAccountFlow.first { !it }
                }
            }
            assertEquals(AccountSlotStatus.READY, harness.accountCookieVault.getSlot(101L)?.status)
            assertEquals(AccountSlotStatus.ACTIVE, harness.accountCookieVault.getSlot(202L)?.status)

            // 3. Explicit sign-out removes only B and keeps A in vault
            harness.sessionRepository.signOut().getOrThrow()
            harness.multiAccountManager.onActiveAccountSignedOut(202L)
            assertNull(harness.sessionRepository.sessionState.value)
            assertNull(harness.accountCookieVault.getSlot(202L))
            assertNotNull(harness.accountCookieVault.getSlot(101L))
        }
    }

    @Test
    fun `E2E-T1-R16-5 PulseHubViewModel switchAccount isolates Room Draft Bookmark ReadHistory and handles add-cancel and removeSavedAccount`() = runBlocking {
        E2eTestHarness(useHttpsNodeSeekOrigin = true).use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", sessionCookie = "sess_alice")
            harness.forumDispatcher.register("GET", "/") { req ->
                when (extractCookieValue(req.getHeader("Cookie"), "session")) {
                    "sess_alice" -> MockResponse().setResponseCode(200).setBody(buildHomeHtml(101L, "alice101"))
                    "sess_bob" -> MockResponse().setResponseCode(200).setBody(buildHomeHtml(202L, "bob202"))
                    else -> MockResponse().setResponseCode(200).setBody(buildHomeHtml(null, ""))
                }
            }
            harness.forumDispatcher.register("GET", "/api/attendance/board") {
                MockResponse().setResponseCode(200).setBody("""{"hasCheckedInToday":false,"list":[]}""")
            }
            harness.forumDispatcher.register("GET", "/api/attendance/daily") {
                MockResponse().setResponseCode(200).setBody("""{"success":true,"data":{"count":0}}""")
            }
            harness.forumDispatcher.register("GET", "/api/notification/unread-count") {
                MockResponse().setResponseCode(200).setBody("""{"success":true,"unreadCount":{"all":0}}""")
            }
            harness.forumDispatcher.register("GET", "/api/account/collection/list") {
                MockResponse().setResponseCode(200).setBody("""{"success":true,"list":[]}""")
            }

            harness.sessionRepository.refreshSession().getOrThrow()
            harness.multiAccountManager.initializeAndSyncActiveAccount()
            harness.accountCookieVault.saveOrUpdateSlot(
                session = AccountSession(uid = 202L, username = "bob202"),
                cookies = listOf(StoredCookieRecord("session", "sess_bob", CookieHostScope.SHARED_DOT_DOMAIN)),
                status = AccountSlotStatus.READY,
            )

            // Save uid=101 Room rows
            harness.draftDao.upsert(
                DraftEntity(
                    accountUid = 101L,
                    draftKey = "new_topic",
                    title = "Alice Draft",
                    markdownContent = "Secret A",
                    categorySlug = "tech",
                    updatedAtMillis = harness.clock.nowMillis(),
                ),
            )
            harness.bookmarkDao.upsert(
                BookmarkEntity(
                    accountUid = 101L,
                    topicId = 801L,
                    title = "Alice Bookmark",
                    categorySlug = "tech",
                    categoryName = "技术",
                    authorId = 101L,
                    authorName = "alice101",
                    authorAvatarUrl = "",
                    authorLevel = 4,
                    viewCount = 10,
                    replyCount = 1,
                    lastActivityIso = "2026-11-26T00:00:00Z",
                    requiredViewLevel = 0,
                    isLocked = false,
                    offlineDetailJson = null,
                    isSyncedWithServer = true,
                    savedAtEpochMillis = harness.clock.nowMillis(),
                ),
            )
            harness.readHistoryDao.upsert(
                ReadHistoryEntity(
                    accountUid = 101L,
                    topicId = 802L,
                    title = "Alice History",
                    categorySlug = "tech",
                    categoryName = "技术",
                    authorName = "alice101",
                    authorAvatarUrl = "",
                    lastReadPage = 1,
                    lastReadFloor = 3,
                    visitedAtEpochMillis = harness.clock.nowMillis(),
                ),
            )

            val hubVm = harness.createPulseHubViewModel()
            val events = CopyOnWriteArrayList<String>()
            val stateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { hubVm.uiState.collect {} }
            val eventJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { hubVm.events.collect { events.add(it) } }

            val initialState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { hubVm.uiState.first { it.accounts.size == 2 && it.session?.uid == 101L } }
            }
            assertTrue(initialState.accounts.first { it.uid == 101L }.isActive)
            assertEquals(AccountSlotStatus.READY, initialState.accounts.first { it.uid == 202L }.status)

            hubVm.switchAccount(202L)
            val switchedState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    hubVm.uiState.first { it.session?.uid == 202L && it.bookmarks.isEmpty() && it.readHistory.isEmpty() }
                }
            }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (events.none { it.contains("已切换至账号：bob202") }) delay(15L)
                }
            }
            assertTrue(events.any { it.contains("已切换至账号：bob202") })
            assertTrue(switchedState.bookmarks.isEmpty())
            assertTrue(switchedState.readHistory.isEmpty())
            assertNull(harness.draftDao.getDraft(202L, "new_topic"))

            // beginAddAccountWithNativeSignIn -> dismissNativeSignInDialog(cancelPendingAddAccount = true) restores B
            hubVm.beginAddAccountWithNativeSignIn()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { hubVm.uiState.first { it.nativeSignIn.isVisible && it.session == null } }
            }
            hubVm.dismissNativeSignInDialog(cancelPendingAddAccount = true)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { hubVm.uiState.first { !it.nativeSignIn.isVisible && it.session?.uid == 202L } }
            }

            // removeSavedAccount(101L) removes A cleanly
            hubVm.removeSavedAccount(101L)
            val afterRemove = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { hubVm.uiState.first { it.accounts.size == 1 } }
            }
            assertEquals(202L, afterRemove.accounts.single().uid)
            stateJob.cancel()
            eventJob.cancel()
        }
    }

    @Test
    fun `E2E-T2-R16-1 TopicLongImageRenderer handles pagination table header repeat code truncation QR omission and pure-JVM safe stubs`() {
        val rows = (1..250).map { i -> "| node-$i | ${100 + i}ms |" }
        val listItems = (1..300).map { i -> "- Bench item #$i" }
        val codeLines = (1..150).map { i -> "line_$i: ping ok" }
        val giantMarkdown = buildString {
            appendLine("| 节点 | 延迟 |")
            appendLine("| --- | --- |")
            rows.forEach { appendLine(it) }
            appendLine()
            listItems.forEach { appendLine(it) }
            appendLine()
            appendLine("```bash")
            codeLines.forEach { appendLine(it) }
            appendLine("```")
        }

        val floor = FloorComment(
            commentId = 9001L,
            floorNumber = 0,
            author = FloorAuthor(uid = 101L, username = "alice101", avatarUrl = "", isTopicAuthor = true, level = 4),
            createdAtIso = "2026-11-26T00:00:00Z",
            markdownBody = giantMarkdown,
        )

        val withQrReq = TopicLongImageRequest(
            topicId = 999L,
            title = "Giant Benchmark",
            categorySlug = "review",
            categoryName = "测评",
            targetFloor = floor,
            topicUrl = "https://www.nodeseek.com/post-999-1",
            includeQrCode = true,
        )
        val textsWithQr = TopicLongImageRenderer.extractRenderedTexts(withQrReq).joinToString("\n")
        assertTrue(textsWithQr.contains("第 2/"))
        assertTrue(textsWithQr.contains("... (共省略 30 行，完整内容请扫码查看原帖)"))
        assertTrue(textsWithQr.contains("扫码直达原帖"))
        // Repeated table header on subsequent slices
        val headerMatches = Regex("节点").findAll(textsWithQr).count()
        assertTrue("Expected repeated table header across slices, got $headerMatches", headerMatches >= 2)

        val noQrReq = withQrReq.copy(includeQrCode = false)
        val textsNoQr = TopicLongImageRenderer.extractRenderedTexts(noQrReq).joinToString("\n")
        assertFalse(textsNoQr.contains("扫码直达原帖"))

        // Blank QR throws IllegalArgumentException
        assertTrue(runCatching { TopicLongImageQrGenerator.generateQrMatrix("   ") }.exceptionOrNull() is IllegalArgumentException)

        // Pure-JVM render/save/share degrade cleanly without crashing
        val ctx = ContextWrapper(null)
        assertTrue(TopicLongImageRenderer.render(withQrReq).isEmpty())
        assertTrue(TopicLongImageRenderer.saveToGallery(ctx, emptyList()).isFailure)
        assertFalse(TopicLongImageRenderer.shareImages(ctx, emptyList(), "title"))
    }

    @Test
    fun `E2E-T2-R16-2 TopicLongImageAssetFetcher caps 24 URLs filters non-HTTP skips 404 or oversized and CoilHttpClientFactory normalizes ch SVG`() = runBlocking {
        E2eTestHarness(useHttpsNodeSeekOrigin = true).use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", sessionCookie = "coil_node_sess")

            val manyUrlsMd = buildString {
                for (i in 1..30) {
                    appendLine("![img$i](https://img.example.test/p$i.png)")
                }
                appendLine("[bad](javascript:alert(1))")
                appendLine("![file](file:///etc/passwd)")
            }
            val req = TopicLongImageRequest(
                topicId = 702L,
                title = "Many URLs",
                categorySlug = "tech",
                categoryName = "技术",
                targetFloor = FloorComment(
                    commentId = 1L,
                    floorNumber = 0,
                    author = FloorAuthor(uid = 101L, username = "alice101", avatarUrl = "", isTopicAuthor = true),
                    createdAtIso = "",
                    markdownBody = manyUrlsMd,
                ),
                topicUrl = "https://www.nodeseek.com/post-702-1",
            )
            val collected = TopicLongImageAssetFetcher.collectRequiredAssetUrls(req)
            assertEquals(24, collected.size)
            assertTrue(collected.all { it.startsWith("https://") })

            // Prefetch skips 404 and >8MB payloads cleanly
            val url404 = harness.externalServer.url("/missing.png").toString()
            val urlHuge = harness.externalServer.url("/huge.png").toString()
            val urlOk = harness.externalServer.url("/ok.png").toString()
            harness.externalDispatcher.register("GET", "/missing.png") { MockResponse().setResponseCode(404) }
            harness.externalDispatcher.register("GET", "/huge.png") {
                MockResponse().setResponseCode(200).setHeader("Content-Length", "9000000").setBody("x")
            }
            harness.externalDispatcher.register("GET", "/ok.png") {
                MockResponse().setResponseCode(200).setBody(okio.Buffer().write(E2eBitmapAndTlsFixtures.minimalPngBytes()))
            }

            val prefetchReq = req.copy(
                targetFloor = req.targetFloor.copy(
                    markdownBody = "![a]($url404)\n![b]($urlHuge)\n![c]($urlOk)",
                ),
            )
            TopicLongImageAssetFetcher.prefetch(ContextWrapper(null), prefetchReq, harness.coilOkHttpClient).close()

            // CoilHttpClientFactory attaches cookies on www.nodeseek.com, strips Cookie/Auth on externalServer, and normalizes ch SVG
            harness.forumDispatcher.register("GET", "/avatar/101.png") {
                MockResponse().setResponseCode(200).setBody(okio.Buffer().write(E2eBitmapAndTlsFixtures.minimalPngBytes()))
            }
            harness.coilOkHttpClient.newCall(
                Request.Builder().url("https://www.nodeseek.com:${harness.forumServer.port}/avatar/101.png").build(),
            ).execute().use { resp ->
                assertEquals(200, resp.code)
            }
            val forumAvatarReq = harness.forumDispatcher.recordedRequests().single { it.path == "/avatar/101.png" }
            assertTrue(forumAvatarReq.getHeader("Cookie")?.contains("session=coil_node_sess") == true)

            val chSvg = """<svg width="80ch" height="20ch"><text x="2ch" y="10ch" style="font-size:16px">MTR</text></svg>"""
            harness.externalDispatcher.register("GET", "/report.svg") {
                MockResponse().setResponseCode(200).setHeader("Content-Type", "image/svg+xml").setBody(chSvg)
            }
            val normalizedSvg = harness.coilOkHttpClient.newCall(
                Request.Builder()
                    .url(harness.externalServer.url("/report.svg"))
                    .header("Authorization", "Bearer leak_me")
                    .header("Cookie", "session=leak_me")
                    .build(),
            ).execute().use { resp ->
                assertEquals(200, resp.code)
                resp.body.string()
            }
            assertTrue(normalizedSvg.contains("""viewBox="0 0 """))
            assertFalse(normalizedSvg.contains("ch\""))
            val svgReq = harness.externalDispatcher.recordedRequests().single { it.path == "/report.svg" }
            assertNull(svgReq.getHeader("Authorization"))
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T2-R16-3 MultiAccountManagerImpl failure and rollback boundaries across expired CF 403 offline and HTTP 500`() = runBlocking {
        E2eTestHarness(useHttpsNodeSeekOrigin = true).use { harness ->
            val expiredSessions = HashSet<String>()
            val challengeSessions = HashSet<String>()
            val serverErrorSessions = HashSet<String>()

            harness.forumDispatcher.register("GET", "/") { req ->
                val tok = extractCookieValue(req.getHeader("Cookie"), "session")
                when {
                    tok != null && tok in serverErrorSessions -> MockResponse().setResponseCode(500).setBody("ERR")
                    tok != null && tok in challengeSessions -> MockResponse()
                        .setResponseCode(403)
                        .addHeader("cf-mitigated", "challenge")
                        .setBody("<html>Just a moment...</html>")
                    tok == "sess_alice" && tok !in expiredSessions -> MockResponse().setResponseCode(200).setBody(buildHomeHtml(101L, "alice101"))
                    tok == "sess_bob" && tok !in expiredSessions -> MockResponse().setResponseCode(200).setBody(buildHomeHtml(202L, "bob202"))
                    else -> MockResponse().setResponseCode(200).setBody(buildHomeHtml(null, ""))
                }
            }

            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", sessionCookie = "sess_alice")
            harness.sessionRepository.refreshSession().getOrThrow()
            harness.multiAccountManager.initializeAndSyncActiveAccount()

            suspend fun seedBob() {
                harness.accountCookieVault.saveOrUpdateSlot(
                    session = AccountSession(uid = 202L, username = "bob202"),
                    cookies = listOf(StoredCookieRecord("session", "sess_bob", CookieHostScope.SHARED_DOT_DOMAIN)),
                    status = AccountSlotStatus.READY,
                )
            }

            // (1) Target B expired -> TargetExpiredRolledBack, A restored & ACTIVE, B EXPIRED
            seedBob()
            expiredSessions.add("sess_bob")
            val r1 = harness.multiAccountManager.switchAccount(202L)
            assertTrue(r1 is AccountSwitchResult.TargetExpiredRolledBack)
            assertEquals(101L, (r1 as AccountSwitchResult.TargetExpiredRolledBack).restoredSession?.uid)
            assertEquals(AccountSlotStatus.ACTIVE, harness.accountCookieVault.getSlot(101L)?.status)
            assertEquals(AccountSlotStatus.EXPIRED, harness.accountCookieVault.getSlot(202L)?.status)
            expiredSessions.clear()

            // (2) Target B CF 403 -> ChallengeRequired(B), challengeRequired == true, B ACTIVE
            seedBob()
            challengeSessions.add("sess_bob")
            val r2 = harness.multiAccountManager.switchAccount(202L)
            assertTrue(r2 is AccountSwitchResult.ChallengeRequired)
            assertEquals(202L, harness.sessionRepository.sessionState.value?.uid)
            assertTrue(harness.sessionRepository.challengeRequired.value)
            assertEquals(AccountSlotStatus.ACTIVE, harness.accountCookieVault.getSlot(202L)?.status)
            challengeSessions.clear()
            harness.sessionRepository.clearChallengeRequired()

            // Switch back to A cleanly
            assertTrue(harness.multiAccountManager.switchAccount(101L) is AccountSwitchResult.Switched)

            // (3) HTTP 500 on B (after beginAddAccount()) -> Failure + rollback to A
            serverErrorSessions.add("sess_bob")
            assertTrue(harness.multiAccountManager.beginAddAccount().isSuccess)
            val r3 = harness.multiAccountManager.switchAccount(202L)
            assertTrue(r3 is AccountSwitchResult.Failure)
            assertEquals(101L, harness.sessionRepository.sessionState.value?.uid)
            assertEquals(AccountSlotStatus.ACTIVE, harness.accountCookieVault.getSlot(101L)?.status)
            assertFalse(harness.multiAccountManager.pendingAddAccountFlow.value)
            serverErrorSessions.clear()

            // (4) Target B expired when A has empty rollback cookies -> guest (restoredSession == null, both EXPIRED)
            seedBob()
            harness.cookieBridge.clearAllCookies()
            harness.accountCookieVault.saveOrUpdateSlot(
                session = harness.sessionRepository.sessionState.value!!,
                cookies = emptyList(),
                status = AccountSlotStatus.ACTIVE,
            )
            expiredSessions.add("sess_bob")
            val r4 = harness.multiAccountManager.switchAccount(202L)
            assertTrue(r4 is AccountSwitchResult.TargetExpiredRolledBack)
            assertNull((r4 as AccountSwitchResult.TargetExpiredRolledBack).restoredSession)
            assertNull(harness.sessionRepository.sessionState.value)
            assertEquals(AccountSlotStatus.EXPIRED, harness.accountCookieVault.getSlot(101L)?.status)
            assertEquals(AccountSlotStatus.EXPIRED, harness.accountCookieVault.getSlot(202L)?.status)
            expiredSessions.clear()

            // (5) forumServer.shutdown() -> OfflineUsingSnapshot(B)
            seedBob()
            harness.forumServer.shutdown()
            val r5 = harness.multiAccountManager.switchAccount(202L)
            assertTrue("Expected OfflineUsingSnapshot, got $r5", r5 is AccountSwitchResult.OfflineUsingSnapshot)
            assertEquals(202L, harness.sessionRepository.sessionState.value?.uid)
        }
    }

    @Test
    fun `E2E-T2-R16-4 vault tampering corruption backup exclusion and blank-username boundaries`() = runBlocking {
        E2eTestHarness(useHttpsNodeSeekOrigin = true).use { harness ->
            harness.forumDispatcher.register("GET", "/") { req ->
                val tok = extractCookieValue(req.getHeader("Cookie"), "session")
                if (tok == "sess_alice") MockResponse().setResponseCode(200).setBody(buildHomeHtml(101L, "alice101"))
                else MockResponse().setResponseCode(200).setBody(buildHomeHtml(null, ""))
            }
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", sessionCookie = "sess_alice")
            harness.sessionRepository.refreshSession().getOrThrow()
            harness.multiAccountManager.initializeAndSyncActiveAccount()

            // 1. Corrupt vault JSON -> creates .corrupt.<ts> backup and loads empty cleanly
            val corruptFile = File(harness.tempDir, "corrupt_vault.json")
            corruptFile.writeText("{not valid json", Charsets.UTF_8)
            val corruptVault = FileAccountCookieVault(corruptFile, harness.accountCookieVaultCipher, clock = harness.clock::nowMillis)
            corruptVault.ensureLoaded()
            assertTrue(corruptVault.slotsFlow.value.isEmpty())
            assertTrue(harness.tempDir.listFiles()?.any { it.name.startsWith("corrupt_vault.json.corrupt.") } == true)

            // 2. Blank username ignored by saveOrUpdateSlot
            corruptVault.saveOrUpdateSlot(
                session = AccountSession(uid = 303L, username = "   "),
                cookies = listOf(StoredCookieRecord("session", "sess_303", CookieHostScope.SHARED_DOT_DOMAIN)),
                status = AccountSlotStatus.READY,
            )
            assertNull(corruptVault.getSlot(303L))

            // 3. Wrong cipher key / tampered ciphertext -> decryptCookies returns null -> TargetExpiredRolledBack
            val bobSession = AccountSession(uid = 202L, username = "bob202")
            harness.accountCookieVault.saveOrUpdateSlot(
                session = bobSession,
                cookies = listOf(StoredCookieRecord("session", "sess_bob", CookieHostScope.SHARED_DOT_DOMAIN)),
                status = AccountSlotStatus.READY,
            )
            val wrongKeyVault = FileAccountCookieVault(
                vaultFile = harness.accountCookieVaultFile,
                cipher = SoftwareSettingsSecretCipher(SoftwareSettingsSecretCipher.randomKey()),
            )
            wrongKeyVault.ensureLoaded()
            assertNull(wrongKeyVault.decryptCookies(202L))

            val wrongManager = MultiAccountManagerImpl(
                vault = wrongKeyVault,
                cookieStore = harness.cookieBridge,
                sessionRepository = harness.sessionRepository,
                apiClientProvider = { harness.apiClient },
                checkInRepository = harness.checkInRepository,
                bookmarkAndHistoryRepository = harness.bookmarkAndHistoryRepository,
                forumRepository = harness.forumRepository,
                externalScope = harness.backgroundScope,
            )
            val res = wrongManager.switchAccount(202L)
            assertTrue(res is AccountSwitchResult.TargetExpiredRolledBack)
            assertEquals(101L, harness.sessionRepository.sessionState.value?.uid)

            // 4. BackupManagerImpl plaintext & encrypted backup v2 exclude account_cookie_vault_v1.json and live cookies
            val backupMgr = harness.createBackupManager()
            val plainBackup = backupMgr.exportBackupJson(includeApiKeys = true).getOrThrow()
            assertFalse(plainBackup.contains("sess_alice"))
            assertFalse(plainBackup.contains("sess_bob"))
            assertFalse(plainBackup.contains(FileAccountCookieVault.VAULT_FILE_NAME))

            val encBackup = backupMgr.exportEncryptedFullBackup("StrongPassphrase!123").getOrThrow()
            assertFalse(encBackup.rawEnvelopeJson.contains("sess_alice"))
            assertFalse(encBackup.bundle.toString().contains("sess_alice"))
        }
    }

    @Test
    fun `E2E-T2-R16-5 concurrency Mutex serialization and sessionGeneration guards across Feed SellerTrust and PulseRadarMonitor`() = runBlocking {
        E2eTestHarness(useHttpsNodeSeekOrigin = true).use { harness ->
            val feedEntered = CountDownLatch(1)
            val feedRelease = CountDownLatch(1)
            val gateFeed = AtomicBoolean(false)
            val searchEntered = CountDownLatch(1)
            val searchRelease = CountDownLatch(1)
            val gateSearch = AtomicBoolean(false)
            val bobInboxUnblocked = AtomicBoolean(false)

            harness.forumDispatcher.register("GET", "/") { req ->
                val tok = extractCookieValue(req.getHeader("Cookie"), "session")
                if (req.requestUrl?.queryParameter("sortBy") != null && gateFeed.get()) {
                    feedEntered.countDown()
                    feedRelease.await(3, TimeUnit.SECONDS)
                }
                when (tok) {
                    "sess_alice" -> MockResponse().setResponseCode(200).setBody(buildHomeHtml(101L, "alice101"))
                    "sess_bob" -> MockResponse().setResponseCode(200).setBody(buildHomeHtml(202L, "bob202"))
                    else -> MockResponse().setResponseCode(200).setBody(buildHomeHtml(null, ""))
                }
            }
            harness.forumDispatcher.register("GET", "/api/account/getInfo/999") {
                MockResponse().setResponseCode(200).setBody("""{"success":true,"detail":{"member_id":999,"member_name":"suspect999","nPost":0}}""")
            }
            harness.forumDispatcher.register("GET", "/categories/expose") {
                MockResponse().setResponseCode(200).setBody(buildHomeHtml(101L, "alice101"))
            }
            harness.forumDispatcher.register("GET", "/search") {
                if (gateSearch.get()) {
                    searchEntered.countDown()
                    searchRelease.await(3, TimeUnit.SECONDS)
                }
                MockResponse().setResponseCode(200).setBody(buildHomeHtml(101L, "alice101"))
            }
            harness.forumDispatcher.register("GET", "/api/notification/reply-to-me/list") { req ->
                val tok = extractCookieValue(req.getHeader("Cookie"), "session")
                if (tok == "sess_alice") {
                    MockResponse().setResponseCode(403).addHeader("cf-mitigated", "challenge").setBody("<html>Just a moment...</html>")
                } else {
                    bobInboxUnblocked.set(true)
                    MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[]}""")
                }
            }
            harness.forumDispatcher.register("GET", "/api/notification/at-me/list") {
                MockResponse().setResponseCode(200).setBody("""{"success":true,"data":[]}""")
            }
            harness.forumDispatcher.register("GET", "/api/notification/message/list") {
                MockResponse().setResponseCode(200).setBody("""{"success":true,"msgArray":[]}""")
            }

            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", sessionCookie = "sess_alice")
            harness.sessionRepository.refreshSession().getOrThrow()
            harness.multiAccountManager.initializeAndSyncActiveAccount()
            harness.accountCookieVault.saveOrUpdateSlot(
                session = AccountSession(uid = 202L, username = "bob202"),
                cookies = listOf(StoredCookieRecord("session", "sess_bob", CookieHostScope.SHARED_DOT_DOMAIN)),
                status = AccountSlotStatus.READY,
            )

            // 1. Slow HTML feed fetch from A (gen 0) released AFTER switch to B (gen 1) must NOT revert session
            try {
                gateFeed.set(true)
                val staleFeed = async(Dispatchers.IO) { harness.apiClient.fetchFeedPage() }
                assertTrue(feedEntered.await(3, TimeUnit.SECONDS))
                gateFeed.set(false)

                // 2. Slow SellerTrust /search from A (gen 0) released AFTER switch to B must NOT revert session
                val sellerTrustRepo = SellerTrustRepositoryImpl.forContext(
                    context = harness.testAppContext(),
                    userRepository = harness.userRepository,
                    forumRepository = harness.forumRepository,
                    baseHttpClient = harness.createPort443RoutingForumOkHttp(),
                    userAgentProvider = harness.userAgentProvider,
                    sameOriginTransport = harness.sameOriginTransport,
                    onConfigObserved = harness.sessionRepository::onConfigPayloadObserved,
                    sessionGenerationProvider = { harness.sessionRepository.currentSessionGeneration() },
                    onConfigObservedWithGeneration = { cfg, gen ->
                        harness.sessionRepository.onConfigPayloadObserved(cfg, expectedGeneration = gen)
                    },
                )
                gateSearch.set(true)
                val staleSearch = async(Dispatchers.IO) {
                    sellerTrustRepo.searchSellerExposureDeep(uid = 999L, usernameHint = "suspect999")
                }
                assertTrue(searchEntered.await(3, TimeUnit.SECONDS))
                gateSearch.set(false)

                // 3. PulseRadarMonitor without start(): Account A hits inbox CF 403 -> BACKOFF_ACTIVE on A
                harness.radarRepository.updatePollingSettings {
                    it.copy(isEnabled = true, keywordAlertsEnabled = false, replyTrackingEnabled = false, inboxPushEnabled = true)
                }
                val monitor = harness.createPulseRadarMonitor()
                assertTrue(monitor.runBackgroundPollPass() is RadarBackgroundPollOutcome.Completed)
                val backoffOutcome = monitor.runBackgroundPollPass()
                assertTrue("Expected BACKOFF_ACTIVE on A, got $backoffOutcome", backoffOutcome is RadarBackgroundPollOutcome.Skipped)

                // Switch to B while stale feed & stale search are still in-flight
                assertTrue(harness.multiAccountManager.switchAccount(202L) is AccountSwitchResult.Switched)
                assertEquals(202L, harness.sessionRepository.sessionState.value?.uid)

                feedRelease.countDown()
                searchRelease.countDown()
                staleFeed.await()
                staleSearch.await()

                assertEquals(202L, harness.sessionRepository.sessionState.value?.uid)
                assertEquals("bob202", harness.sessionRepository.sessionState.value?.username)

                // Monitor clears inboxCfBackoff on uid change even without start()
                val bobOutcome = monitor.runBackgroundPollPass()
                assertTrue("Expected Completed on B after backoff reset, got $bobOutcome", bobOutcome is RadarBackgroundPollOutcome.Completed)
                assertTrue(bobInboxUnblocked.get())

                // 4. Concurrent switchAccount calls serialize cleanly via Mutex
                val concurrentResults = withContext(Dispatchers.IO) {
                    listOf(
                        async { harness.multiAccountManager.switchAccount(101L) },
                        async { harness.multiAccountManager.switchAccount(202L) },
                    ).awaitAll()
                }
                assertTrue(concurrentResults.all { it is AccountSwitchResult.Switched })
                val finalUid = harness.sessionRepository.sessionState.value?.uid
                assertTrue(finalUid == 101L || finalUid == 202L)
            } finally {
                feedRelease.countDown()
                searchRelease.countDown()
            }
        }
    }
}
