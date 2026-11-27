package com.nodeseek.app.domain.repository

import com.nodeseek.app.domain.model.PollDetail
import com.nodeseek.app.domain.model.StardustTransferPreview

interface PollAndStardustRepository {
    suspend fun getVote(voteId: Long): Result<PollDetail>

    suspend fun createVote(
        title: String,
        multiple: Boolean,
        isPublic: Boolean,
        options: List<String>,
    ): Result<PollDetail>

    suspend fun castVote(
        voteId: Long,
        voteItemIds: List<Long>,
    ): Result<PollDetail>

    suspend fun lockVote(voteId: Long): Result<PollDetail>

    suspend fun prepareStardustTransfer(receiverId: Long): Result<StardustTransferPreview>

    suspend fun sendStardust(
        memberId: Long,
        amount: Int,
        refId: Long = 0L,
    ): Result<Unit>
}

object NoOpPollAndStardustRepository : PollAndStardustRepository {
    private fun unsupported(): Result<Nothing> =
        Result.failure(UnsupportedOperationException("PollAndStardustRepository is not configured"))

    override suspend fun getVote(voteId: Long): Result<PollDetail> = unsupported()

    override suspend fun createVote(
        title: String,
        multiple: Boolean,
        isPublic: Boolean,
        options: List<String>,
    ): Result<PollDetail> = unsupported()

    override suspend fun castVote(voteId: Long, voteItemIds: List<Long>): Result<PollDetail> = unsupported()

    override suspend fun lockVote(voteId: Long): Result<PollDetail> = unsupported()

    override suspend fun prepareStardustTransfer(receiverId: Long): Result<StardustTransferPreview> = unsupported()

    override suspend fun sendStardust(memberId: Long, amount: Int, refId: Long): Result<Unit> = unsupported()
}
