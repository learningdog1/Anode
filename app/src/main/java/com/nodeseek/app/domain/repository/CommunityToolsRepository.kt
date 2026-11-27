package com.nodeseek.app.domain.repository

import com.nodeseek.app.domain.model.CreditAnalyticsLoadResult
import com.nodeseek.app.domain.model.CreditLedgerAnalytics
import com.nodeseek.app.domain.model.CreditLedgerPage
import com.nodeseek.app.domain.model.InviteConfig
import com.nodeseek.app.domain.model.InviteListPage
import com.nodeseek.app.domain.model.InvitePaymentMethod
import com.nodeseek.app.domain.model.InvitePurchaseResult
import com.nodeseek.app.domain.model.ModerationRuling
import com.nodeseek.app.domain.model.ModerationRulingPage
import com.nodeseek.app.domain.model.StardustLedgerPage

interface CommunityToolsRepository {
    suspend fun getCreditLedger(page: Int = 1): Result<CreditLedgerPage>

    suspend fun getRecentCreditAnalytics(
        currentCoins: Int,
        windowDays: Int = CreditLedgerAnalytics.DEFAULT_WINDOW_DAYS,
        maxPagesToFetch: Int = 5,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Result<CreditAnalyticsLoadResult>

    suspend fun getStardustLedger(
        memberId: Long,
        count: Int = 10,
        beforeId: Long? = null,
        type: String? = null,
    ): Result<StardustLedgerPage>

    suspend fun getInviteConfig(): Result<InviteConfig>

    suspend fun listInvites(page: Int = 1): Result<InviteListPage>

    suspend fun buyInviteCode(payment: InvitePaymentMethod): Result<InvitePurchaseResult>

    suspend fun warmInviteCsrf(): Result<Unit> = Result.success(Unit)

    suspend fun listModerationRulings(
        page: Int = 1,
        search: String? = null,
    ): Result<ModerationRulingPage>

    suspend fun getModerationRulingDetail(id: Long): Result<ModerationRuling>
}

object NoOpCommunityToolsRepository : CommunityToolsRepository {
    private fun unsupported(): Result<Nothing> =
        Result.failure(UnsupportedOperationException("CommunityToolsRepository is not configured"))

    override suspend fun getCreditLedger(page: Int): Result<CreditLedgerPage> = unsupported()

    override suspend fun getRecentCreditAnalytics(
        currentCoins: Int,
        windowDays: Int,
        maxPagesToFetch: Int,
        nowEpochMillis: Long,
    ): Result<CreditAnalyticsLoadResult> = unsupported()

    override suspend fun getStardustLedger(
        memberId: Long,
        count: Int,
        beforeId: Long?,
        type: String?,
    ): Result<StardustLedgerPage> = unsupported()

    override suspend fun getInviteConfig(): Result<InviteConfig> = unsupported()

    override suspend fun listInvites(page: Int): Result<InviteListPage> = unsupported()

    override suspend fun buyInviteCode(payment: InvitePaymentMethod): Result<InvitePurchaseResult> = unsupported()

    override suspend fun warmInviteCsrf(): Result<Unit> = unsupported()

    override suspend fun listModerationRulings(page: Int, search: String?): Result<ModerationRulingPage> = unsupported()

    override suspend fun getModerationRulingDetail(id: Long): Result<ModerationRuling> = unsupported()
}
