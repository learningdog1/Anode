package com.nodeseek.app.domain.activity

interface LuckyDrawRepository {
    suspend fun fetchFloorData(
        postId: Long,
        drawTimeEpochMillis: Long,
    ): Result<List<LuckyFloorEntry>>

    suspend fun fetchDrandBeacon(round: Long): Result<DrandBeacon>

    suspend fun resolveLuckyDraw(
        spec: LuckyDrawSpec,
        viewerUid: Long?,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Result<LuckyDrawOutcome>
}

object NoOpLuckyDrawRepository : LuckyDrawRepository {
    private fun unsupported(): Result<Nothing> =
        Result.failure(UnsupportedOperationException("LuckyDrawRepository is not configured"))

    override suspend fun fetchFloorData(
        postId: Long,
        drawTimeEpochMillis: Long,
    ): Result<List<LuckyFloorEntry>> = unsupported()

    override suspend fun fetchDrandBeacon(round: Long): Result<DrandBeacon> = unsupported()

    override suspend fun resolveLuckyDraw(
        spec: LuckyDrawSpec,
        viewerUid: Long?,
        nowEpochMillis: Long,
    ): Result<LuckyDrawOutcome> = unsupported()
}
