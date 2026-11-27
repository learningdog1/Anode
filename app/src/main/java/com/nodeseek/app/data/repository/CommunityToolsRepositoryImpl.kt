package com.nodeseek.app.data.repository

import com.nodeseek.app.data.network.CloudflareChallengeException
import com.nodeseek.app.data.network.CreditLedgerTupleDto
import com.nodeseek.app.data.network.InviteCodeRowDto
import com.nodeseek.app.data.network.ModerationRulingRowDto
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.data.network.StardustRecordDto
import com.nodeseek.app.domain.model.CreditAnalyticsLoadResult
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
import com.nodeseek.app.domain.model.StardustRecordType
import com.nodeseek.app.domain.repository.CommunityToolsRepository
import kotlinx.coroutines.CancellationException
import java.io.IOException

class CommunityToolsRepositoryImpl(
    private val apiClientProvider: () -> NodeSeekApiClient,
    private val sessionRepository: SessionRepositoryImpl,
) : CommunityToolsRepository {

    override suspend fun getCreditLedger(page: Int): Result<CreditLedgerPage> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val safePage = page.coerceIn(1, MAX_CREDIT_PAGES)
            val resp = apiClientProvider().getCreditLedger(page = safePage)
            if (resp.success == false) {
                throw NodeSeekApiException(resp.message?.takeIf { it.isNotBlank() } ?: "获取鸡腿账簿失败")
            }
            val entries = resp.data.map { it.toDomain() }
            val totalPages = if (resp.total > 0) {
                ((resp.total + CREDIT_PAGE_SIZE - 1) / CREDIT_PAGE_SIZE).coerceAtMost(MAX_CREDIT_PAGES)
            } else {
                0
            }
            val hasNext = if (totalPages > 0) {
                safePage < totalPages
            } else {
                entries.size >= CREDIT_PAGE_SIZE && safePage < MAX_CREDIT_PAGES
            }
            CreditLedgerPage(
                entries = entries,
                totalCount = resp.total.coerceAtLeast(entries.size),
                currentPage = safePage,
                hasNextPage = hasNext,
            )
        }

    override suspend fun getRecentCreditAnalytics(
        currentCoins: Int,
        windowDays: Int,
        maxPagesToFetch: Int,
        nowEpochMillis: Long,
    ): Result<CreditAnalyticsLoadResult> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val safeWindowDays = windowDays.coerceAtLeast(1)
            val safeMaxPages = maxPagesToFetch.coerceIn(1, MAX_CREDIT_PAGES)
            val cutoffMillis = nowEpochMillis - safeWindowDays.toLong() * 86_400_000L

            var firstPage: CreditLedgerPage? = null
            val accumulated = mutableListOf<CreditLedgerEntry>()
            var page = 1

            while (page <= safeMaxPages) {
                val resp = try {
                    apiClientProvider().getCreditLedger(page = page)
                } catch (c: CancellationException) {
                    throw c
                } catch (cf: CloudflareChallengeException) {
                    throw cf
                } catch (e: NodeSeekApiException) {
                    if (page > 1 && firstPage != null) {
                        break
                    }
                    throw e
                } catch (e: IOException) {
                    if (page > 1 && firstPage != null) {
                        break
                    }
                    throw e
                }
                if (resp.success == false) {
                    if (page == 1) {
                        throw NodeSeekApiException(resp.message?.takeIf { it.isNotBlank() } ?: "获取鸡腿账簿失败")
                    }
                    break
                }
                val entries = resp.data.map { it.toDomain() }
                val totalPages = if (resp.total > 0) {
                    ((resp.total + CREDIT_PAGE_SIZE - 1) / CREDIT_PAGE_SIZE).coerceAtMost(MAX_CREDIT_PAGES)
                } else {
                    0
                }
                val hasNext = if (totalPages > 0) {
                    page < totalPages
                } else {
                    entries.size >= CREDIT_PAGE_SIZE && page < MAX_CREDIT_PAGES
                }
                val pageResult = CreditLedgerPage(
                    entries = entries,
                    totalCount = resp.total.coerceAtLeast(entries.size),
                    currentPage = page,
                    hasNextPage = hasNext,
                )
                if (firstPage == null) {
                    firstPage = pageResult
                }
                accumulated.addAll(entries)

                val oldestTimestamp = entries.mapNotNull { it.createdAtEpochMillis }.minOrNull()
                if (!hasNext || entries.isEmpty() || (oldestTimestamp != null && oldestTimestamp < cutoffMillis)) {
                    break
                }
                page += 1
            }

            val resolvedFirstPage = firstPage ?: CreditLedgerPage(
                entries = emptyList(),
                totalCount = 0,
                currentPage = 1,
                hasNextPage = false,
            )
            val analytics = CreditLedgerAnalytics.analyze(
                entries = accumulated,
                currentCoins = currentCoins,
                nowEpochMillis = nowEpochMillis,
                windowDays = safeWindowDays,
            )
            CreditAnalyticsLoadResult(
                firstPage = resolvedFirstPage,
                fetchedEntries = accumulated,
                analytics = analytics,
            )
        }

    override suspend fun getStardustLedger(
        memberId: Long,
        count: Int,
        beforeId: Long?,
        type: String?,
    ): Result<StardustLedgerPage> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            if (memberId <= 0L) {
                throw IllegalArgumentException("无效的用户 UID")
            }
            val safeCount = count.coerceIn(1, 50)
            val resp = apiClientProvider().listStardust(
                memberId = memberId,
                count = safeCount,
                beforeId = beforeId,
                type = type?.trim()?.takeIf { it.isNotEmpty() },
            )
            if (resp.success == false) {
                throw NodeSeekApiException(resp.message?.takeIf { it.isNotBlank() } ?: "获取星尘流水失败")
            }
            val records = resp.records.map { it.toDomain() }
            val resolvedCursor = resp.cursor ?: records.lastOrNull()?.id
            StardustLedgerPage(
                records = records,
                hasMore = resp.existMore && resolvedCursor != null,
                nextCursor = if (resp.existMore) resolvedCursor else null,
            )
        }

    override suspend fun getInviteConfig(): Result<InviteConfig> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val resp = apiClientProvider().getInviteConfig()
            if (resp.success == false) {
                throw NodeSeekApiException(resp.message?.takeIf { it.isNotBlank() } ?: "获取邀请码购买配置失败")
            }
            val methods = resp.resolvedPayment
            InviteConfig(
                coinOption = methods?.coin?.let {
                    InvitePaymentOption(
                        method = InvitePaymentMethod.COIN,
                        enabled = it.enabled,
                        cost = it.cost.coerceAtLeast(0),
                    )
                },
                stardustOption = methods?.stardust?.let {
                    InvitePaymentOption(
                        method = InvitePaymentMethod.STARDUST,
                        enabled = it.enabled,
                        cost = it.cost.coerceAtLeast(0),
                    )
                },
            )
        }

    override suspend fun listInvites(page: Int): Result<InviteListPage> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val safePage = page.coerceAtLeast(1)
            val resp = apiClientProvider().listInvites(page = safePage)
            if (resp.success == false) {
                throw NodeSeekApiException(resp.message?.takeIf { it.isNotBlank() } ?: "获取邀请码列表失败")
            }
            val items = resp.data.map { it.toDomain() }
            val hasNext = if (resp.total > 0) {
                safePage * INVITE_PAGE_SIZE < resp.total
            } else {
                items.size >= INVITE_PAGE_SIZE
            }
            InviteListPage(
                items = items,
                totalCount = resp.total.coerceAtLeast(items.size),
                currentPage = safePage,
                hasNextPage = hasNext,
            )
        }

    override suspend fun buyInviteCode(payment: InvitePaymentMethod): Result<InvitePurchaseResult> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val resp = apiClientProvider().buyInviteCode(payment = payment.apiValue)
            if (resp.success == false) {
                throw NodeSeekApiException(resp.message?.takeIf { it.isNotBlank() } ?: "购买邀请码失败")
            }
            val code = resp.code?.trim().orEmpty()
            if (code.isEmpty() && resp.success != true) {
                throw NodeSeekApiException(resp.message?.takeIf { it.isNotBlank() } ?: "购买成功但未返回邀请码")
            }
            runSuspendCatching { sessionRepository.refreshSession() }
            InvitePurchaseResult(
                code = code,
                paymentMethod = payment,
                message = resp.message?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

    override suspend fun warmInviteCsrf(): Result<Unit> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            apiClientProvider().warmInviteCsrfPage()
        }

    override suspend fun listModerationRulings(
        page: Int,
        search: String?,
    ): Result<ModerationRulingPage> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val safePage = page.coerceIn(1, MAX_RULING_PAGES)
            val resp = apiClientProvider().listModerationRulings(
                page = safePage,
                search = search,
            )
            if (resp.success == false) {
                throw NodeSeekApiException(resp.message?.takeIf { it.isNotBlank() } ?: "获取小黑屋裁决列表失败")
            }
            val items = resp.data.map { it.toDomain() }
            val totalPages = if (resp.total > 0) {
                ((resp.total + RULING_PAGE_SIZE - 1) / RULING_PAGE_SIZE).coerceAtMost(MAX_RULING_PAGES)
            } else {
                0
            }
            val hasNext = if (totalPages > 0) {
                safePage < totalPages
            } else {
                items.size >= RULING_PAGE_SIZE && safePage < MAX_RULING_PAGES
            }
            ModerationRulingPage(
                items = items,
                totalCount = resp.total.coerceAtLeast(items.size),
                currentPage = safePage,
                hasNextPage = hasNext,
            )
        }

    override suspend fun getModerationRulingDetail(id: Long): Result<ModerationRuling> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            if (id <= 0L) {
                throw IllegalArgumentException("无效的裁决记录 ID")
            }
            val resp = apiClientProvider().getModerationRulingDetail(id = id)
            if (resp.success == false) {
                throw NodeSeekApiException(resp.message?.takeIf { it.isNotBlank() } ?: "获取裁决详情失败")
            }
            val dto = resp.resolvedRuling
                ?: throw NodeSeekApiException(resp.message?.takeIf { it.isNotBlank() } ?: "未找到裁决记录详情")
            dto.toDomain()
        }

    companion object {
        private const val CREDIT_PAGE_SIZE = 20
        private const val MAX_CREDIT_PAGES = 100
        private const val INVITE_PAGE_SIZE = 50
        private const val RULING_PAGE_SIZE = 20
        private const val MAX_RULING_PAGES = 100
    }
}

private fun CreditLedgerTupleDto.toDomain(): CreditLedgerEntry = CreditLedgerEntry(
    change = change,
    balance = balance,
    reason = reason.trim(),
    createdAtRaw = createdAt.trim(),
    createdAtEpochMillis = CreditLedgerAnalytics.parseLedgerTimestampMillis(createdAt),
    category = CreditLedgerAnalytics.classifyCreditReason(reason = reason, change = change),
)

private fun StardustRecordDto.toDomain(): StardustLedgerEntry {
    val resolvedType = StardustRecordType.fromApiType(type)
    return StardustLedgerEntry(
        id = id,
        diff = diff,
        balanceAfter = result,
        rawType = type.trim(),
        typeLabel = if (resolvedType != StardustRecordType.OTHER) {
            resolvedType.label
        } else {
            type.trim().ifBlank { resolvedType.label }
        },
        createdAtRaw = createdAt.trim(),
        createdAtEpochMillis = CreditLedgerAnalytics.parseLedgerTimestampMillis(createdAt),
        peerUid = peerId?.takeIf { it > 0L },
    )
}

private fun InviteCodeRowDto.toDomain(): InviteCodeItem = InviteCodeItem(
    code = code.trim(),
    applied = applied,
    createdAtRaw = createdAt.trim(),
    createdAtEpochMillis = CreditLedgerAnalytics.parseLedgerTimestampMillis(createdAt),
    appliedAtRaw = appliedAt?.trim()?.takeIf { it.isNotEmpty() },
    appliedBy = appliedBy?.trim()?.takeIf { it.isNotEmpty() },
)

private fun ModerationRulingRowDto.toDomain(): ModerationRuling = ModerationRuling(
    id = id,
    memberId = memberId?.takeIf { it > 0L },
    memberName = memberName.trim(),
    reason = reason.trim(),
    action = action.trim(),
    evidence = evidence?.trim()?.takeIf { it.isNotEmpty() },
    operatorName = operatorName?.trim()?.takeIf { it.isNotEmpty() },
    postId = postId?.takeIf { it > 0L },
    createdAtRaw = createdAt.trim(),
    createdAtEpochMillis = CreditLedgerAnalytics.parseLedgerTimestampMillis(createdAt),
)
