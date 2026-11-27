package com.nodeseek.app.data.repository

import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.data.network.VoteDetailDto
import com.nodeseek.app.data.network.VoteOptionDto
import com.nodeseek.app.domain.model.PollDetail
import com.nodeseek.app.domain.model.PollOption
import com.nodeseek.app.domain.model.StardustTransferPreview
import com.nodeseek.app.domain.repository.PollAndStardustRepository

class PollAndStardustRepositoryImpl(
    private val apiClientProvider: () -> NodeSeekApiClient,
    private val sessionRepository: SessionRepositoryImpl,
) : PollAndStardustRepository {

    override suspend fun getVote(voteId: Long): Result<PollDetail> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            if (voteId <= 0L) {
                throw IllegalArgumentException("无效的投票 ID")
            }
            val resp = apiClientProvider().getVote(voteId)
            val dto = resp.vote
                ?: throw NodeSeekApiException(resp.message?.takeIf { it.isNotBlank() } ?: "未能读取投票详情")
            dto.toDomain()
        }

    override suspend fun createVote(
        title: String,
        multiple: Boolean,
        isPublic: Boolean,
        options: List<String>,
    ): Result<PollDetail> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val cleanTitle = title.trim()
            if (cleanTitle.isEmpty()) {
                throw IllegalArgumentException("请输入投票标题")
            }
            val cleanOptions = options.map { it.trim() }.filter { it.isNotEmpty() }
            if (cleanOptions.size < 2) {
                throw IllegalArgumentException("投票至少需要 2 个有效选项")
            }
            val resp = apiClientProvider().createVote(
                title = cleanTitle,
                multiple = multiple,
                isPublic = isPublic,
                options = cleanOptions,
            )
            val dto = resp.vote
                ?: throw NodeSeekApiException(resp.message?.takeIf { it.isNotBlank() } ?: "创建投票失败，服务端未返回投票 ID")
            dto.toDomain()
        }

    override suspend fun castVote(
        voteId: Long,
        voteItemIds: List<Long>,
    ): Result<PollDetail> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            if (voteId <= 0L) {
                throw IllegalArgumentException("无效的投票 ID")
            }
            val validIds = voteItemIds.filter { it > 0L }.distinct()
            if (validIds.isEmpty()) {
                throw IllegalArgumentException("请至少选择一个投票选项")
            }
            val client = apiClientProvider()
            client.castVote(validIds)
            val refreshed = client.getVote(voteId)
            val dto = refreshed.vote
                ?: throw NodeSeekApiException(
                    refreshed.message?.takeIf { it.isNotBlank() } ?: "投票成功，但刷新投票结果失败",
                )
            dto.toDomain()
        }

    override suspend fun lockVote(voteId: Long): Result<PollDetail> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            if (voteId <= 0L) {
                throw IllegalArgumentException("无效的投票 ID")
            }
            val client = apiClientProvider()
            client.lockVote(voteId)
            val refreshed = runSuspendCatching { client.getVote(voteId) }.getOrNull()?.vote
            refreshed?.toDomain() ?: PollDetail(
                id = voteId,
                title = "",
                creatorUid = 0L,
                isPublic = false,
                isLocked = true,
                isMultiple = false,
                options = emptyList(),
            )
        }

    override suspend fun prepareStardustTransfer(receiverId: Long): Result<StardustTransferPreview> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            if (receiverId <= 0L) {
                throw IllegalArgumentException("无效的接收者 UID")
            }
            val resp = apiClientProvider().prepareStardustTransfer(receiverId)
            val name = resp.receiverName.trim().ifBlank { "UID $receiverId" }
            StardustTransferPreview(
                receiverUid = receiverId,
                receiverName = name,
            )
        }

    override suspend fun sendStardust(
        memberId: Long,
        amount: Int,
        refId: Long,
    ): Result<Unit> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            if (memberId <= 0L) {
                throw IllegalArgumentException("无效的接收者 UID")
            }
            if (amount <= 0) {
                throw IllegalArgumentException("投喂星尘数量必须大于 0")
            }
            apiClientProvider().sendStardust(
                memberId = memberId,
                amount = amount,
                refId = refId.coerceAtLeast(0L),
            )
        }
}

private fun VoteDetailDto.toDomain(): PollDetail = PollDetail(
    id = id,
    title = title.trim(),
    creatorUid = uid,
    isPublic = isPublic,
    isLocked = locked,
    isMultiple = multiple,
    options = items.map { it.toDomain() },
)

private fun VoteOptionDto.toDomain(): PollOption = PollOption(
    voteItemId = voteItemId,
    text = text.trim(),
    count = count,
    voted = voted == true,
    voters = voters.orEmpty(),
)
