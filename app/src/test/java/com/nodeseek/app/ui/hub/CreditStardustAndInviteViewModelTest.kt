package com.nodeseek.app.ui.hub

import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.CreditAnalytics30d
import com.nodeseek.app.domain.model.CreditAnalyticsLoadResult
import com.nodeseek.app.domain.model.CreditEntryCategory
import com.nodeseek.app.domain.model.CreditLedgerAnalytics
import com.nodeseek.app.domain.model.CreditLedgerEntry
import com.nodeseek.app.domain.model.CreditLedgerPage
import com.nodeseek.app.domain.model.InviteCodeItem
import com.nodeseek.app.domain.model.InviteConfig
import com.nodeseek.app.domain.model.InviteListPage
import com.nodeseek.app.domain.model.InvitePaymentMethod
import com.nodeseek.app.domain.model.InvitePaymentOption
import com.nodeseek.app.domain.model.InvitePurchaseResult
import com.nodeseek.app.domain.model.ModerationRuling
import com.nodeseek.app.domain.model.ModerationRulingPage
import com.nodeseek.app.domain.model.StardustLedgerEntry
import com.nodeseek.app.domain.model.StardustLedgerPage
import com.nodeseek.app.domain.repository.CommunityToolsRepository
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SignInStepResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CreditStardustAndInviteViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `CreditStardustLedgerViewModel loads 30d analytics and switches to cursor-paginated stardust`() = runTest {
        val repo = FakeCommunityToolsRepository()
        val now = Instant.parse("2026-11-25T00:00:00Z").toEpochMilli()
        val vm = CreditStardustLedgerViewModel(
            repository = repo,
            initialTab = EconomyLedgerTab.DRUMSTICKS,
            targetUid = 101L,
            currentCoins = 1650,
            currentStardust = 42,
            canAccessDrumsticks = true,
            clockMillis = { now },
        )

        val creditState = vm.uiState.value
        assertFalse(creditState.isLoadingCredit)
        assertEquals(2, creditState.creditEntries.size)
        assertNotNull(creditState.creditAnalytics)
        assertEquals(4, creditState.creditAnalytics!!.upgradeEta.currentLevel)

        vm.selectTab(EconomyLedgerTab.STARDUST)
        assertEquals(EconomyLedgerTab.STARDUST, vm.uiState.value.selectedTab)
        assertEquals(listOf(981L), vm.uiState.value.stardustRecords.map { it.id })
        assertTrue(vm.uiState.value.stardustHasMore)

        vm.loadMoreStardust()
        assertEquals(listOf(981L, 979L), vm.uiState.value.stardustRecords.map { it.id })
        assertFalse(vm.uiState.value.stardustHasMore)

        vm.selectStardustTypeFilter("transfer")
        assertEquals("transfer", repo.lastStardustType)
    }

    @Test
    fun `CreditStardustLedgerViewModel forces STARDUST tab when canAccessDrumsticks is false`() = runTest {
        val repo = FakeCommunityToolsRepository()
        val vm = CreditStardustLedgerViewModel(
            repository = repo,
            initialTab = EconomyLedgerTab.DRUMSTICKS,
            targetUid = 202L,
            currentCoins = 500,
            currentStardust = 12,
            canAccessDrumsticks = false,
        )

        assertEquals(EconomyLedgerTab.STARDUST, vm.uiState.value.selectedTab)
        assertEquals(0, repo.creditCalls)
        assertEquals(202L, repo.lastStardustMemberId)

        vm.selectTab(EconomyLedgerTab.DRUMSTICKS)
        assertEquals(EconomyLedgerTab.STARDUST, vm.uiState.value.selectedTab)
    }

    @Test
    fun `InviteAndRulingsViewModel handles purchase, CSRF recovery flow, and ruling search + detail`() = runTest {
        val repo = FakeCommunityToolsRepository()
        val sessionRepo = FakeToolsSessionRepository(
            AccountSession(uid = 101L, username = "Alice", drumsticks = 1650, stardust = 600),
        )
        val vm = InviteAndRulingsViewModel(
            repository = repo,
            sessionRepository = sessionRepo,
            initialTab = 0,
        )

        assertEquals(100, vm.uiState.value.inviteConfig?.coinOption?.cost)
        assertEquals(1, vm.uiState.value.inviteItems.size)

        // First purchase fails due to missing CSRF -> surfaces verification prompt
        repo.nextBuyResult = Result.failure(NodeSeekApiException("请先打开官网的邀请页面完成验证，再返回购买。"))
        vm.buyInviteCode(InvitePaymentMethod.COIN)
        assertTrue(vm.uiState.value.requiresInvitePageVerification)

        // Returning from WebView warms CSRF & clears banner
        vm.refreshAfterInvitePageVerification()
        assertTrue(repo.warmCsrfCalled)
        assertFalse(vm.uiState.value.requiresInvitePageVerification)

        // Second purchase succeeds
        repo.nextBuyResult = Result.success(
            InvitePurchaseResult(
                code = "NS-INV-NEW-888",
                paymentMethod = InvitePaymentMethod.STARDUST,
                message = "购买成功",
            ),
        )
        vm.buyInviteCode(InvitePaymentMethod.STARDUST)
        assertEquals("NS-INV-NEW-888", vm.uiState.value.lastPurchasedCode)

        // Switch to rulings tab, search, and open detail
        vm.selectTab(1)
        assertEquals(1, vm.uiState.value.rulings.size)
        vm.updateRulingSearchQuery("spam")
        vm.submitRulingSearch()
        assertEquals("spam", repo.lastRulingSearch)

        vm.openRulingDetail(vm.uiState.value.rulings.first())
        assertEquals("连续发广告", vm.uiState.value.selectedRulingDetail?.evidence)
    }

    @Test
    fun `inviteAndRulings_accountSwitchAToB_clearsOldCodesAndReloads`() = runTest {
        val sessionRepo = FakeToolsSessionRepository(
            AccountSession(uid = 101L, username = "Alice", drumsticks = 500, stardust = 1000),
        )
        val repo = object : CommunityToolsRepository by FakeCommunityToolsRepository() {
            override suspend fun listInvites(page: Int): Result<InviteListPage> {
                val uid = sessionRepo.sessionState.value?.uid ?: 0L
                val item = if (uid == 101L) {
                    InviteCodeItem("NS-INV-ALICE", false, "2026-11-01T00:00:00Z", null)
                } else {
                    InviteCodeItem("NS-INV-BOB", false, "2026-11-02T00:00:00Z", null)
                }
                return Result.success(
                    InviteListPage(
                        items = listOf(item),
                        totalCount = 1,
                        currentPage = 1,
                        hasNextPage = false,
                    ),
                )
            }
        }
        val vm = InviteAndRulingsViewModel(
            repository = repo,
            sessionRepository = sessionRepo,
            initialTab = 0,
        )
        assertEquals(listOf("NS-INV-ALICE"), vm.uiState.value.inviteItems.map { it.code })

        // Switching to uid=202 must clear uid=101 codes and reload uid=202 codes
        sessionRepo.sessionState.value = AccountSession(uid = 202L, username = "Bob", drumsticks = 300, stardust = 200)
        assertEquals(listOf("NS-INV-BOB"), vm.uiState.value.inviteItems.map { it.code })
    }

    @Test
    fun `creditLedger_syncHostSnapshotUidChange_clearsPriorEntries`() = runTest {
        val repo = FakeCommunityToolsRepository()
        val vm = CreditStardustLedgerViewModel(
            repository = repo,
            initialTab = EconomyLedgerTab.DRUMSTICKS,
            targetUid = 101L,
            currentCoins = 500,
            currentStardust = 100,
            canAccessDrumsticks = true,
            clockMillis = { Instant.parse("2026-11-26T10:00:00Z").toEpochMilli() },
        )
        assertTrue(vm.uiState.value.creditEntries.isNotEmpty())
        assertNotNull(vm.uiState.value.creditAnalytics)

        // Switching host targetUid=202 onto STARDUST tab must clear prior account's credit ledger & analytics
        vm.syncHostSnapshot(
            targetUid = 202L,
            currentCoins = 10,
            currentStardust = 50,
            canAccessDrumsticks = true,
            requestedTab = EconomyLedgerTab.STARDUST,
        )
        assertTrue(vm.uiState.value.creditEntries.isEmpty())
        assertNull(vm.uiState.value.creditAnalytics)
        assertEquals(202L, repo.lastStardustMemberId)
    }

    private class FakeToolsSessionRepository(initial: AccountSession?) : SessionRepository {
        override val sessionState = MutableStateFlow(initial)
        override val challengeRequired = MutableStateFlow(false)
        override val authVerifiedEpoch = MutableStateFlow(0L)
        override suspend fun refreshSession(): Result<AccountSession?> = Result.success(sessionState.value)
        override suspend fun signIn(username: String, password: String, turnstileToken: String) =
            Result.success(SignInStepResult.Authenticated)
        override suspend fun verifyTwoFactor(otpCode: String, otpSession: String) = Result.success(Unit)
        override suspend fun onWebViewAuthCompleted(): Result<AccountSession?> = Result.success(sessionState.value)
        override suspend fun onWebViewDismissed(): Result<AccountSession?> = Result.success(sessionState.value)
        override suspend fun signOut(): Result<Unit> = Result.success(Unit)
    }

    private class FakeCommunityToolsRepository : CommunityToolsRepository {
        var creditCalls = 0
        var lastStardustMemberId: Long? = null
        var lastStardustType: String? = null
        var lastRulingSearch: String? = null
        var warmCsrfCalled = false
        var nextBuyResult: Result<InvitePurchaseResult> = Result.success(
            InvitePurchaseResult("NS-INV-DEFAULT", InvitePaymentMethod.COIN, "购买成功"),
        )

        override suspend fun getCreditLedger(page: Int): Result<CreditLedgerPage> =
            Result.success(CreditLedgerPage(emptyList(), 0, page, false))

        override suspend fun getRecentCreditAnalytics(
            currentCoins: Int,
            windowDays: Int,
            maxPagesToFetch: Int,
            nowEpochMillis: Long,
        ): Result<CreditAnalyticsLoadResult> {
            creditCalls++
            val entries = listOf(
                CreditLedgerEntry(5, 1650, "每日签到奖励", "2026-11-24 08:00:00", Instant.parse("2026-11-24T00:00:00Z").toEpochMilli(), CreditEntryCategory.CHECK_IN),
                CreditLedgerEntry(-1, 1645, "给评论加鸡腿", "2026-11-23 08:00:00", Instant.parse("2026-11-23T00:00:00Z").toEpochMilli(), CreditEntryCategory.DRUMSTICK_GIFT_EXPENSE),
            )
            val page = CreditLedgerPage(entries, 2, 1, false)
            val analytics: CreditAnalytics30d = CreditLedgerAnalytics.analyze(entries, currentCoins, nowEpochMillis, windowDays)
            return Result.success(CreditAnalyticsLoadResult(page, entries, analytics))
        }

        override suspend fun getStardustLedger(
            memberId: Long,
            count: Int,
            beforeId: Long?,
            type: String?,
        ): Result<StardustLedgerPage> {
            lastStardustMemberId = memberId
            lastStardustType = type
            return if (beforeId == null) {
                Result.success(
                    StardustLedgerPage(
                        records = listOf(
                            StardustLedgerEntry(981L, 10, 42, "transfer", "转账", "2026-11-24T01:00:00Z", null, 202L),
                        ),
                        hasMore = true,
                        nextCursor = 981L,
                    ),
                )
            } else {
                Result.success(
                    StardustLedgerPage(
                        records = listOf(
                            StardustLedgerEntry(979L, 32, 32, "system", "系统", "2026-11-20T00:00:00Z", null, null),
                        ),
                        hasMore = false,
                        nextCursor = null,
                    ),
                )
            }
        }

        override suspend fun getInviteConfig(): Result<InviteConfig> = Result.success(
            InviteConfig(
                coinOption = InvitePaymentOption(InvitePaymentMethod.COIN, true, 100),
                stardustOption = InvitePaymentOption(InvitePaymentMethod.STARDUST, true, 500),
            ),
        )

        override suspend fun listInvites(page: Int): Result<InviteListPage> = Result.success(
            InviteListPage(
                items = listOf(InviteCodeItem("NS-INV-OLD", false, "2026-11-01T00:00:00Z", null)),
                totalCount = 1,
                currentPage = 1,
                hasNextPage = false,
            ),
        )

        override suspend fun buyInviteCode(payment: InvitePaymentMethod): Result<InvitePurchaseResult> = nextBuyResult

        override suspend fun warmInviteCsrf(): Result<Unit> {
            warmCsrfCalled = true
            return Result.success(Unit)
        }

        override suspend fun listModerationRulings(page: Int, search: String?): Result<ModerationRulingPage> {
            lastRulingSearch = search
            return Result.success(
                ModerationRulingPage(
                    items = listOf(
                        ModerationRuling(701L, 9901L, "spammer99", "恶意刷屏", "封禁 30 天", createdAtRaw = "2026-11-20T10:00:00Z"),
                    ),
                    totalCount = 1,
                    currentPage = 1,
                    hasNextPage = false,
                ),
            )
        }

        override suspend fun getModerationRulingDetail(id: Long): Result<ModerationRuling> = Result.success(
            ModerationRuling(
                id = id,
                memberId = 9901L,
                memberName = "spammer99",
                reason = "恶意刷屏",
                action = "封禁 30 天",
                evidence = "连续发广告",
                createdAtRaw = "2026-11-20T10:00:00Z",
            ),
        )
    }
}
