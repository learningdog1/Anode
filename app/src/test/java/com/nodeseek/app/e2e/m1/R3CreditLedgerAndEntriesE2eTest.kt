package com.nodeseek.app.e2e.m1

import com.nodeseek.app.data.network.computeDynamicSign
import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.CreditEntryCategory
import com.nodeseek.app.domain.model.CreditLedgerAnalytics
import com.nodeseek.app.domain.model.CreditLedgerEntry
import com.nodeseek.app.domain.model.InvitePaymentMethod
import com.nodeseek.app.domain.model.StardustRecordType
import com.nodeseek.app.domain.model.UpgradeEtaStatus
import com.nodeseek.app.e2e.E2eFixtures
import com.nodeseek.app.e2e.E2eMainDispatcherRule
import com.nodeseek.app.e2e.E2eTestHarness
import com.nodeseek.app.ui.hub.CreditStardustLedgerViewModel
import com.nodeseek.app.ui.hub.EconomyLedgerTab
import com.nodeseek.app.ui.hub.InviteAndRulingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
import java.time.Instant

class R3CreditLedgerAndEntriesE2eTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    private val fixtureClockEpochMillis: Long = Instant.parse("2026-11-25T12:00:00Z").toEpochMilli()

    @Test
    fun `E2E-T1-R3-1 fetch credit ledger and compute 30d check-in mean and leg-add expense ratio`() = runTest {
        E2eTestHarness(initialEpochMillis = fixtureClockEpochMillis).use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", coin = 1650, stardust = 42)
            val creditFixture = E2eFixtures.loadText("nodeseek/credit_ledger_30d.json")
            harness.forumDispatcher.enqueueNext(
                method = "GET",
                path = "/api/account/credit/page-1",
                response = MockResponse().setResponseCode(200).setBody(creditFixture),
            )
            // Second request with mixed non-gift expense (-5 "购买邀请码") to verify partial ratio
            val mixedExpenseLedger = """
                {
                  "success": true,
                  "total": 4,
                  "data": [
                    [5, 1650, "每日签到奖励", "2026-11-24 08:00:00"],
                    [-1, 1645, "给评论加鸡腿", "2026-11-23 22:10:00"],
                    [-2, 1646, "给主题加鸡腿", "2026-11-22 19:30:00"],
                    [-5, 1648, "购买邀请码", "2026-11-21 12:00:00"]
                  ]
                }
            """.trimIndent()
            harness.forumDispatcher.enqueueNext(
                method = "GET",
                path = "/api/account/credit/page-1",
                response = MockResponse().setResponseCode(200).setBody(mixedExpenseLedger),
            )

            val vm = harness.manage(
                CreditStardustLedgerViewModel(
                    repository = harness.communityToolsRepository,
                    initialTab = EconomyLedgerTab.DRUMSTICKS,
                    targetUid = 101L,
                    currentCoins = 1650,
                    currentStardust = 42,
                    canAccessDrumsticks = true,
                    clockMillis = harness.clock::nowMillis,
                ),
            )

            val loadedState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.isLoadingCredit && it.creditAnalytics != null }
                }
            }
            val analytics = loadedState.creditAnalytics!!
            assertEquals(10, loadedState.creditEntries.size)
            assertEquals(7, analytics.checkInCount)
            assertEquals(45, analytics.checkInTotalCoins)
            assertEquals(45.0 / 7.0, analytics.checkInAverageCoins!!, 1e-6)
            assertEquals(3, analytics.drumstickGiftExpenseCoins)
            assertEquals(3, analytics.totalExpenseCoins)
            assertEquals(1.0, analytics.drumstickExpenseRatio, 1e-6)

            val req1 = harness.forumDispatcher.recordedRequests()
                .first { it.path == "/api/account/credit/page-1" }
            assertTrue(req1.getHeader("Cookie")?.contains("session=ns_e2e_sess") == true)

            val mixedAnalytics = harness.communityToolsRepository.getRecentCreditAnalytics(
                currentCoins = 1650,
                nowEpochMillis = harness.clock.nowMillis(),
            ).getOrThrow().analytics
            assertEquals(3, mixedAnalytics.drumstickGiftExpenseCoins)
            assertEquals(8, mixedAnalytics.totalExpenseCoins)
            assertEquals(3.0 / 8.0, mixedAnalytics.drumstickExpenseRatio, 1e-6)
        }
    }

    @Test
    fun `E2E-T1-R3-2 compute Lv0 to Lv6 thresholds and upgrade ETA days`() = runTest {
        E2eTestHarness(initialEpochMillis = fixtureClockEpochMillis).use { harness ->
            assertEquals(0, AccountSession.calculateLevel(0))
            assertEquals(0, AccountSession.calculateLevel(99))
            assertEquals(1, AccountSession.calculateLevel(100))
            assertEquals(1, AccountSession.calculateLevel(399))
            assertEquals(2, AccountSession.calculateLevel(400))
            assertEquals(3, AccountSession.calculateLevel(900))
            assertEquals(4, AccountSession.calculateLevel(1600))
            assertEquals(5, AccountSession.calculateLevel(2500))
            assertEquals(5, AccountSession.calculateLevel(3599))
            assertEquals(6, AccountSession.calculateLevel(3600))

            harness.seedAuthenticatedSession(uid = 101L, coin = 1650)
            val net30Ledger = """
                {
                  "success": true,
                  "total": 2,
                  "data": [
                    [15, 1650, "每日签到奖励", "2026-11-24 08:00:00"],
                    [15, 1635, "每日签到奖励", "2026-11-10 08:00:00"]
                  ]
                }
            """.trimIndent()
            harness.forumDispatcher.register("GET", "/api/account/credit/page-1") {
                MockResponse().setResponseCode(200).setBody(net30Ledger)
            }

            val result = harness.communityToolsRepository.getRecentCreditAnalytics(
                currentCoins = 1650,
                windowDays = 30,
                nowEpochMillis = harness.clock.nowMillis(),
            ).getOrThrow()

            val eta = result.analytics.upgradeEta
            assertEquals(4, eta.currentLevel)
            assertEquals(5, eta.nextLevel)
            assertEquals(2500, eta.nextLevelThreshold)
            assertEquals(850, eta.coinsRemaining)
            assertEquals(1.0, eta.netCoinsPerDay30d, 1e-6)
            assertEquals(850, eta.estimatedDaysToNextLevel)
            assertEquals(UpgradeEtaStatus.ESTIMATED, eta.status)
            assertEquals(6, result.analytics.levelMilestones.size)
        }
    }

    @Test
    fun `E2E-T1-R3-3 paginate stardust ledger with before_id cursor, type filter, and x-dynamic-sign`() = runTest {
        E2eTestHarness(initialEpochMillis = fixtureClockEpochMillis).use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", coin = 1650, stardust = 42)
            val stardustComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/stardust_ledger_and_transfer.json"),
            ).jsonObject
            val page1Json = stardustComposite.getValue("list").toString()
            val page2Json = """
                {
                  "success": true,
                  "records": [
                    {
                      "id": 978,
                      "diff": -500,
                      "result": 0,
                      "type": "buyCode",
                      "created_at": "2026-11-15T10:00:00Z",
                      "peer_id": null
                    },
                    {
                      "id": 977,
                      "diff": 500,
                      "result": 500,
                      "type": "admin",
                      "created_at": "2026-11-10T10:00:00Z",
                      "peer_id": null
                    }
                  ],
                  "exist_more": false,
                  "cursor": null
                }
            """.trimIndent()
            val filteredBuyCodeJson = """
                {
                  "success": true,
                  "records": [
                    {
                      "id": 978,
                      "diff": -500,
                      "result": 0,
                      "type": "buyCode",
                      "created_at": "2026-11-15T10:00:00Z",
                      "peer_id": null
                    }
                  ],
                  "exist_more": false,
                  "cursor": null
                }
            """.trimIndent()

            harness.forumDispatcher.register("GET", "/api/stardust/list") { req ->
                val url = req.requestUrl
                val beforeId = url?.queryParameter("before_id")
                val type = url?.queryParameter("type")
                when {
                    type == "buyCode" -> MockResponse().setResponseCode(200).setBody(filteredBuyCodeJson)
                    beforeId == "979" -> MockResponse().setResponseCode(200).setBody(page2Json)
                    else -> MockResponse().setResponseCode(200).setBody(page1Json)
                }
            }

            val vm = harness.manage(
                CreditStardustLedgerViewModel(
                    repository = harness.communityToolsRepository,
                    initialTab = EconomyLedgerTab.STARDUST,
                    targetUid = 101L,
                    currentCoins = 1650,
                    currentStardust = 42,
                    canAccessDrumsticks = true,
                    clockMillis = harness.clock::nowMillis,
                ),
            )

            val page1State = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.isLoadingStardust && it.stardustRecords.size == 3 }
                }
            }
            assertEquals(listOf("转账", "点赞", "系统"), page1State.stardustRecords.map { it.typeLabel })
            assertEquals(979L, page1State.stardustNextCursor)
            assertTrue(page1State.stardustHasMore)

            vm.loadMoreStardust()
            val page2State = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.isLoadingMoreStardust && it.stardustRecords.size == 5 }
                }
            }
            assertEquals("购买邀请码", page2State.stardustRecords[3].typeLabel)
            assertEquals("管理", page2State.stardustRecords[4].typeLabel)
            assertFalse(page2State.stardustHasMore)

            vm.selectStardustTypeFilter(StardustRecordType.BUY_CODE.apiKey)
            val filteredState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        !it.isLoadingStardust &&
                            it.stardustTypeFilter == "buyCode" &&
                            it.stardustRecords.size == 1
                    }
                }
            }
            assertEquals(978L, filteredState.stardustRecords.single().id)

            val stardustRequests = harness.forumDispatcher.recordedRequests()
                .filter { it.path?.startsWith("/api/stardust/list") == true }
            assertEquals(3, stardustRequests.size)
            assertTrue(stardustRequests[1].requestUrl?.queryParameter("before_id") == "979")
            assertTrue(stardustRequests[2].requestUrl?.queryParameter("type") == "buyCode")
            for (req in stardustRequests) {
                val expectedSign = computeDynamicSign(
                    method = "GET",
                    fullUrl = req.requestUrl.toString(),
                    userAgent = harness.testUserAgent,
                    rawBody = "",
                )
                assertEquals(expectedSign, req.getHeader("x-dynamic-sign"))
            }
        }
    }

    @Test
    fun `E2E-T1-R3-4 fetch invite config and list then buyOne invite code with CSRF headers`() = runTest {
        E2eTestHarness(initialEpochMillis = fixtureClockEpochMillis).use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", coin = 1650, stardust = 42)
            val inviteRulingComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/invite_and_ruling.json"),
            ).jsonObject
            val homeHtml = E2eFixtures.loadText("nodeseek/home_logged_in_uid101.html")

            harness.forumDispatcher.register("GET", "/api/invite/config") {
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("x-csrf-token", "csrf_tok_123")
                    .setBody(inviteRulingComposite.getValue("inviteConfig").toString())
            }
            harness.forumDispatcher.register("GET", "/api/invite/list") {
                MockResponse().setResponseCode(200).setBody(inviteRulingComposite.getValue("inviteList").toString())
            }
            harness.forumDispatcher.register("POST", "/api/invite/buyOne") {
                MockResponse().setResponseCode(200).setBody(inviteRulingComposite.getValue("buyOneSuccess").toString())
            }
            harness.forumDispatcher.register("GET", "/") {
                MockResponse().setResponseCode(200).setBody(homeHtml)
            }

            val vm = harness.manage(
                InviteAndRulingsViewModel(
                    repository = harness.communityToolsRepository,
                    sessionRepository = harness.sessionRepository,
                    initialTab = 0,
                ),
            )

            val initialState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        !it.isLoadingInvites && it.inviteConfig != null && it.inviteItems.size == 2
                    }
                }
            }
            assertEquals(100, initialState.inviteConfig?.coinOption?.cost)
            assertEquals(500, initialState.inviteConfig?.stardustOption?.cost)
            assertEquals("NS-INV-2026-ABCD", initialState.inviteItems.first().code)

            vm.buyInviteCode(InvitePaymentMethod.COIN)
            val boughtState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.isBuyingInvite && it.lastPurchasedCode != null }
                }
            }
            assertEquals("NS-INV-2026-NEW1", boughtState.lastPurchasedCode)

            val buyReq = harness.forumDispatcher.recordedRequests().first { it.path == "/api/invite/buyOne" }
            assertEquals("simple-token", buyReq.getHeader("x-csrf-challenge"))
            assertEquals("csrf_tok_123", buyReq.getHeader("x-csrf-token"))
            val buyBody = TempScriptParser.json.parseToJsonElement(buyReq.body.readUtf8()).jsonObject
            assertEquals("coin", buyBody.getValue("payment").jsonPrimitive.content)
        }
    }

    @Test
    fun `E2E-T1-R3-5 search moderation rulings and inspect ruling detail via secondary entry`() = runTest {
        E2eTestHarness(initialEpochMillis = fixtureClockEpochMillis).use { harness ->
            val inviteRulingComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/invite_and_ruling.json"),
            ).jsonObject
            val rulingListJson = inviteRulingComposite.getValue("rulingList").toString()
            val rulingDetailJson = inviteRulingComposite.getValue("rulingDetail").toString()

            harness.forumDispatcher.register("GET", "/api/admin/ruling/page-1") {
                MockResponse().setResponseCode(200).setBody(rulingListJson)
            }
            harness.forumDispatcher.register("GET", "/api/admin/ruling/id-701") {
                MockResponse().setResponseCode(200).setBody(rulingDetailJson)
            }

            val vm = harness.manage(
                InviteAndRulingsViewModel(
                    repository = harness.communityToolsRepository,
                    sessionRepository = harness.sessionRepository,
                    initialTab = 1,
                ),
            )

            val listState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.isLoadingRulings && it.rulings.size == 2 }
                }
            }
            assertEquals("spammer99", listState.rulings.first().memberName)

            vm.updateRulingSearchQuery("spam")
            vm.submitRulingSearch()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.isLoadingRulings && it.activeRulingSearch == "spam" }
                }
            }
            val searchReq = harness.forumDispatcher.recordedRequests()
                .last { it.path?.startsWith("/api/admin/ruling/page-1") == true }
            assertEquals("spam", searchReq.requestUrl?.queryParameter("search"))

            vm.openRulingDetail(listState.rulings.first())
            val detailState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.isLoadingRulingDetail && it.selectedRulingDetail?.evidence != null }
                }
            }
            assertEquals("连续在 5 个版块重复发广告贴", detailState.selectedRulingDetail?.evidence)
        }
    }

    @Test
    fun `E2E-T2-R3-1 distinguish null record vs zero-gain check-in and include 0-coin check-in in 30d mean`() = runTest {
        E2eTestHarness(initialEpochMillis = fixtureClockEpochMillis).use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, coin = 1650)
            val boardComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/attendance_board_states.json"),
            ).jsonObject
            val creditFixture = E2eFixtures.loadText("nodeseek/credit_ledger_30d.json")

            harness.forumDispatcher.enqueueNext(
                method = "GET",
                path = "/api/attendance/board",
                response = MockResponse().setResponseCode(200).setBody(boardComposite.getValue("unchecked").toString()),
            )
            harness.forumDispatcher.enqueueNext(
                method = "GET",
                path = "/api/attendance/board",
                response = MockResponse().setResponseCode(200).setBody(boardComposite.getValue("zeroGainChecked").toString()),
            )
            harness.forumDispatcher.register("GET", "/api/account/credit/page-1") {
                MockResponse().setResponseCode(200).setBody(creditFixture)
            }

            val uncheckedStatus = harness.checkInRepository.getCheckInBoard(forceRefresh = true).getOrThrow()
            assertFalse(uncheckedStatus.hasCheckedInToday)
            assertNull(uncheckedStatus.todayGain)

            val zeroGainStatus = harness.checkInRepository.getCheckInBoard(forceRefresh = true).getOrThrow()
            assertTrue(zeroGainStatus.hasCheckedInToday)
            assertEquals(0, zeroGainStatus.todayGain)

            val analytics = harness.communityToolsRepository.getRecentCreditAnalytics(
                currentCoins = 1650,
                nowEpochMillis = harness.clock.nowMillis(),
            ).getOrThrow().analytics
            assertEquals(7, analytics.checkInCount)
            assertEquals(45, analytics.checkInTotalCoins)
            assertEquals(45.0 / 7.0, analytics.checkInAverageCoins!!, 1e-6)
        }
    }

    @Test
    fun `E2E-T2-R3-2 max level Lv6 returns MAX_LEVEL with null next threshold and no divide by zero`() = runTest {
        E2eTestHarness(initialEpochMillis = fixtureClockEpochMillis).use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, coin = 3600)
            harness.forumDispatcher.register("GET", "/api/account/credit/page-1") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.loadText("nodeseek/credit_ledger_30d.json"))
            }

            for (coins in listOf(3600, 4200)) {
                val analytics = harness.communityToolsRepository.getRecentCreditAnalytics(
                    currentCoins = coins,
                    nowEpochMillis = harness.clock.nowMillis(),
                ).getOrThrow().analytics
                assertEquals(6, analytics.upgradeEta.currentLevel)
                assertNull(analytics.upgradeEta.nextLevel)
                assertNull(analytics.upgradeEta.nextLevelThreshold)
                assertEquals(0, analytics.upgradeEta.coinsRemaining)
                assertNull(analytics.upgradeEta.estimatedDaysToNextLevel)
                assertEquals(UpgradeEtaStatus.MAX_LEVEL, analytics.upgradeEta.status)
            }
        }
    }

    @Test
    fun `E2E-T2-R3-3 negative net rate and empty ledger yield safe indeterminate ETA without NaN`() = runTest {
        E2eTestHarness(initialEpochMillis = fixtureClockEpochMillis).use { harness ->
            val declining = CreditLedgerAnalytics.analyze(
                entries = listOf(
                    CreditLedgerEntry(
                        change = -5,
                        balance = 500,
                        reason = "给主题加鸡腿",
                        createdAtRaw = "2026-11-24 10:00:00",
                        createdAtEpochMillis = CreditLedgerAnalytics.parseLedgerTimestampMillis("2026-11-24 10:00:00"),
                        category = CreditEntryCategory.DRUMSTICK_GIFT_EXPENSE,
                    ),
                ),
                currentCoins = 500,
                nowEpochMillis = harness.clock.nowMillis(),
            )
            assertEquals(UpgradeEtaStatus.STAGNANT_OR_DECLINING, declining.upgradeEta.status)
            assertNull(declining.upgradeEta.estimatedDaysToNextLevel)
            assertEquals(1.0, declining.drumstickExpenseRatio, 1e-6)

            val empty = CreditLedgerAnalytics.analyze(
                entries = emptyList(),
                currentCoins = 500,
                nowEpochMillis = harness.clock.nowMillis(),
            )
            assertEquals(UpgradeEtaStatus.INSUFFICIENT_DATA, empty.upgradeEta.status)
            assertNull(empty.upgradeEta.estimatedDaysToNextLevel)
            assertNull(empty.checkInAverageCoins)
            assertEquals(0.0, empty.drumstickExpenseRatio, 1e-6)
        }
    }

    @Test
    fun `E2E-T2-R3-4 buyOne without CSRF token fails locally then succeeds after warmInviteCsrf`() = runTest {
        E2eTestHarness(initialEpochMillis = fixtureClockEpochMillis).use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101", coin = 1650)
            val inviteRulingComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/invite_and_ruling.json"),
            ).jsonObject
            val homeHtml = E2eFixtures.loadText("nodeseek/home_logged_in_uid101.html")

            // Deliberately emit NO x-csrf-token header on /api/invite/config
            harness.forumDispatcher.register("GET", "/api/invite/config") {
                MockResponse().setResponseCode(200).setBody(inviteRulingComposite.getValue("inviteConfig").toString())
            }
            harness.forumDispatcher.register("GET", "/api/invite/list") {
                MockResponse().setResponseCode(200).setBody(inviteRulingComposite.getValue("inviteList").toString())
            }
            harness.forumDispatcher.register("GET", "/invite") {
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("x-csrf-token", "warmed_csrf_456")
                    .setBody("<html><body>Invite</body></html>")
            }
            harness.forumDispatcher.register("POST", "/api/invite/buyOne") {
                MockResponse().setResponseCode(200).setBody(inviteRulingComposite.getValue("buyOneSuccess").toString())
            }
            harness.forumDispatcher.register("GET", "/") {
                MockResponse().setResponseCode(200).setBody(homeHtml)
            }

            val vm = harness.manage(
                InviteAndRulingsViewModel(
                    repository = harness.communityToolsRepository,
                    sessionRepository = harness.sessionRepository,
                    initialTab = 0,
                ),
            )
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { !it.isLoadingInvites && it.inviteConfig != null } }
            }

            vm.buyInviteCode(InvitePaymentMethod.COIN)
            val verificationState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first { !it.isBuyingInvite && it.requiresInvitePageVerification }
                }
            }
            assertTrue(verificationState.requiresInvitePageVerification)
            assertEquals(0, harness.forumDispatcher.recordedRequests().count { it.path == "/api/invite/buyOne" })

            vm.refreshAfterInvitePageVerification()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        !it.requiresInvitePageVerification &&
                            harness.csrfTokenStore.csrfToken == "warmed_csrf_456"
                    }
                }
            }

            vm.buyInviteCode(InvitePaymentMethod.COIN)
            val purchasedState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { !it.isBuyingInvite && it.lastPurchasedCode != null } }
            }
            assertEquals("NS-INV-2026-NEW1", purchasedState.lastPurchasedCode)
            val buyReq = harness.forumDispatcher.recordedRequests().single { it.path == "/api/invite/buyOne" }
            assertEquals("warmed_csrf_456", buyReq.getHeader("x-csrf-token"))
        }
    }

    @Test
    fun `E2E-T2-R3-5 credit ledger caps at page 100, handles empty data, and gates other-user drumstick access`() = runTest {
        E2eTestHarness(initialEpochMillis = fixtureClockEpochMillis).use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, coin = 1650)
            val page100Rows = (1..20).joinToString(",") { idx ->
                """[1, ${1000 + idx}, "每日签到奖励", "2026-11-01 08:00:00"]"""
            }
            harness.forumDispatcher.register("GET", "/api/account/credit/page-100") {
                MockResponse().setResponseCode(200).setBody("""{"success":true,"total":5000,"data":[$page100Rows]}""")
            }
            harness.forumDispatcher.register("GET", "/api/account/credit/page-1") {
                MockResponse().setResponseCode(200).setBody("""{"success":true,"total":0,"data":[]}""")
            }
            val stardustComposite = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeseek/stardust_ledger_and_transfer.json"),
            ).jsonObject
            harness.forumDispatcher.register("GET", "/api/stardust/list") {
                MockResponse().setResponseCode(200).setBody(stardustComposite.getValue("list").toString())
            }

            val cappedPage = harness.communityToolsRepository.getCreditLedger(page = 150).getOrThrow()
            assertEquals(100, cappedPage.currentPage)
            assertFalse(cappedPage.hasNextPage)
            assertEquals(20, cappedPage.entries.size)

            val emptyPage = harness.communityToolsRepository.getCreditLedger(page = 1).getOrThrow()
            assertTrue(emptyPage.entries.isEmpty())
            assertFalse(emptyPage.hasNextPage)

            harness.forumDispatcher.clearRecordedRequests()

            val otherUserVm = harness.manage(
                CreditStardustLedgerViewModel(
                    repository = harness.communityToolsRepository,
                    initialTab = EconomyLedgerTab.DRUMSTICKS,
                    targetUid = 202L,
                    currentCoins = 500,
                    currentStardust = 20,
                    canAccessDrumsticks = false,
                    clockMillis = harness.clock::nowMillis,
                ),
            )
            val gatedState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    otherUserVm.uiState.first { !it.isLoadingStardust && it.stardustRecords.isNotEmpty() }
                }
            }
            assertEquals(EconomyLedgerTab.STARDUST, gatedState.selectedTab)
            otherUserVm.selectTab(EconomyLedgerTab.DRUMSTICKS)
            assertEquals(EconomyLedgerTab.STARDUST, otherUserVm.uiState.value.selectedTab)
            assertNotNull(gatedState.stardustRecords.firstOrNull())
            assertEquals(
                0,
                harness.forumDispatcher.recordedRequests().count { it.path?.startsWith("/api/account/credit/") == true },
            )
        }
    }
}
