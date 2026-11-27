package com.nodeseek.app.domain.activity

import kotlinx.serialization.Serializable

@Serializable
data class LuckyDrawSpec(
    val postId: Long,
    val drawTimeEpochMillis: Long?,
    val winnerCount: Int = 1,
    val firstFloor: Int = 1,
    val removeDuplicates: Boolean = true,
    val rawUrl: String = "",
) {
    val hasValidDrawTime: Boolean
        get() = drawTimeEpochMillis != null &&
            drawTimeEpochMillis >= DrandLuckyEngine.DRAND_GENESIS_EPOCH_MILLIS
}

@Serializable
data class LuckyFloorEntry(
    val floorId: Int,
    val memberId: Long,
    val memberName: String,
)

@Serializable
data class DrandBeacon(
    val round: Long,
    val randomness: String,
    val signature: String = "",
    val previousSignature: String = "",
)

enum class LuckyViewerStatus {
    MY_WIN,
    PARTICIPATED_NOT_WON,
    PARTICIPATED_PENDING,
    INELIGIBLE_FLOOR,
    NOT_PARTICIPATED,
    NOT_LOGGED_IN,
}

data class LuckyDrawOutcome(
    val spec: LuckyDrawSpec,
    val drandRound: Long?,
    val beacon: DrandBeacon?,
    val floors: List<LuckyFloorEntry>,
    val eligibleFloorCount: Int,
    val winners: List<LuckyFloorEntry>,
    val viewerStatus: LuckyViewerStatus,
    val viewerFloors: List<LuckyFloorEntry>,
    val viewerWinningFloors: List<LuckyFloorEntry>,
    val isDrawn: Boolean,
    val beaconPendingMessage: String? = null,
)
