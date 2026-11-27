package com.nodeseek.app.data.activity

import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.data.repository.SessionRepositoryImpl
import com.nodeseek.app.data.repository.runDomainCatching
import com.nodeseek.app.domain.activity.DrandBeacon
import com.nodeseek.app.domain.activity.DrandLuckyEngine
import com.nodeseek.app.domain.activity.LuckyDrawOutcome
import com.nodeseek.app.domain.activity.LuckyDrawRepository
import com.nodeseek.app.domain.activity.LuckyDrawSpec
import com.nodeseek.app.domain.activity.LuckyFloorEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class LuckyDrawRepositoryImpl(
    private val apiClientProvider: () -> NodeSeekApiClient,
    private val drandApiClient: DrandApiClient,
    private val sessionRepository: SessionRepositoryImpl? = null,
) : LuckyDrawRepository {

    override suspend fun fetchFloorData(
        postId: Long,
        drawTimeEpochMillis: Long,
    ): Result<List<LuckyFloorEntry>> =
        runDomainCatching(
            onChallengeDetected = { sessionRepository?.reportChallengeRequired() },
        ) {
            if (postId <= 0L) {
                throw IllegalArgumentException("无效的抽奖帖子 ID")
            }
            if (drawTimeEpochMillis <= 0L) {
                throw IllegalArgumentException("无效的开奖时间戳")
            }
            val resp = apiClientProvider().getLuckyFloorData(
                postId = postId,
                timeEpochMillis = drawTimeEpochMillis,
            )
            if (resp.success == false) {
                throw NodeSeekApiException(
                    resp.message?.takeIf { it.isNotBlank() } ?: "获取抽奖楼层数据失败",
                )
            }
            resp.data.map { dto ->
                LuckyFloorEntry(
                    floorId = dto.floorId,
                    memberId = dto.memberId,
                    memberName = dto.memberName.trim().ifBlank { "UID ${dto.memberId}" },
                )
            }
        }

    override suspend fun fetchDrandBeacon(round: Long): Result<DrandBeacon> =
        runDomainCatching(onChallengeDetected = {}) {
            drandApiClient.getBeacon(round)
        }

    override suspend fun resolveLuckyDraw(
        spec: LuckyDrawSpec,
        viewerUid: Long?,
        nowEpochMillis: Long,
    ): Result<LuckyDrawOutcome> =
        runDomainCatching(
            onChallengeDetected = { sessionRepository?.reportChallengeRequired() },
        ) {
            val drawTimeMs = spec.drawTimeEpochMillis
                ?: throw IllegalArgumentException("抽奖链接缺少开奖时间参数 (time)")
            if (drawTimeMs < DrandLuckyEngine.DRAND_GENESIS_EPOCH_MILLIS) {
                throw IllegalArgumentException("开奖时间早于 Drand 主网创世时间")
            }

            val round = DrandLuckyEngine.computeDrandRound(drawTimeMs)
            val isDrawTimeReached = nowEpochMillis >= drawTimeMs

            if (!isDrawTimeReached) {
                val floors = fetchFloorData(spec.postId, drawTimeMs).getOrThrow()
                val eligibleCount = countEligibleFloors(floors, spec)
                val (viewerStatus, viewerFloors, viewerWins) = DrandLuckyEngine.evaluateViewerStatus(
                    spec = spec,
                    floors = floors,
                    winners = emptyList(),
                    viewerUid = viewerUid,
                    isDrawn = false,
                )
                return@runDomainCatching LuckyDrawOutcome(
                    spec = spec,
                    drandRound = round,
                    beacon = null,
                    floors = floors,
                    eligibleFloorCount = eligibleCount,
                    winners = emptyList(),
                    viewerStatus = viewerStatus,
                    viewerFloors = viewerFloors,
                    viewerWinningFloors = viewerWins,
                    isDrawn = false,
                    beaconPendingMessage = null,
                )
            }

            val (floors, beaconResult) = coroutineScope {
                val floorsDeferred = async { fetchFloorData(spec.postId, drawTimeMs).getOrThrow() }
                val beaconDeferred = async { fetchDrandBeacon(round) }
                floorsDeferred.await() to beaconDeferred.await()
            }

            val beaconError = beaconResult.exceptionOrNull()
            if (beaconError != null) {
                if (beaconError is DrandApiException && beaconError.isRoundNotYetPublished) {
                    val eligibleCount = countEligibleFloors(floors, spec)
                    val (viewerStatus, viewerFloors, viewerWins) = DrandLuckyEngine.evaluateViewerStatus(
                        spec = spec,
                        floors = floors,
                        winners = emptyList(),
                        viewerUid = viewerUid,
                        isDrawn = false,
                    )
                    return@runDomainCatching LuckyDrawOutcome(
                        spec = spec,
                        drandRound = round,
                        beacon = null,
                        floors = floors,
                        eligibleFloorCount = eligibleCount,
                        winners = emptyList(),
                        viewerStatus = viewerStatus,
                        viewerFloors = viewerFloors,
                        viewerWinningFloors = viewerWins,
                        isDrawn = false,
                        beaconPendingMessage = beaconError.message,
                    )
                }
                throw beaconError
            }

            val beacon = beaconResult.getOrThrow()
            val winners = DrandLuckyEngine.drawWinners(
                floors = floors,
                spec = spec,
                randomnessHex = beacon.randomness,
            )
            val eligibleCount = countEligibleFloors(floors, spec)
            val (viewerStatus, viewerFloors, viewerWins) = DrandLuckyEngine.evaluateViewerStatus(
                spec = spec,
                floors = floors,
                winners = winners,
                viewerUid = viewerUid,
                isDrawn = true,
            )

            LuckyDrawOutcome(
                spec = spec,
                drandRound = round,
                beacon = beacon,
                floors = floors,
                eligibleFloorCount = eligibleCount,
                winners = winners,
                viewerStatus = viewerStatus,
                viewerFloors = viewerFloors,
                viewerWinningFloors = viewerWins,
                isDrawn = true,
                beaconPendingMessage = null,
            )
        }

    private fun countEligibleFloors(
        floors: List<LuckyFloorEntry>,
        spec: LuckyDrawSpec,
    ): Int {
        val eligible = floors.filter { it.floorId >= spec.firstFloor }
        return if (spec.removeDuplicates) {
            eligible.distinctBy { it.memberId }.size
        } else {
            eligible.size
        }
    }
}
