package com.nodeseek.app.domain.valuation

import java.math.BigDecimal

enum class MarketDataSource(val label: String) {
    FEED_CACHE("版块缓存"),
    BOOKMARK("本地收藏"),
    READ_HISTORY("浏览历史"),
    REMOTE_SEARCH("全站搜索"),
}

enum class TradeListingIntent {
    SELL,
    BUY,
    UNKNOWN,
}

data class TradeCandidateTopic(
    val topicId: Long,
    val title: String,
    val source: MarketDataSource,
    val authorName: String = "",
    val activityHint: String = "",
)

data class ParsedTradeTitleSpread(
    val deltaCny: BigDecimal,
    val spreadStatus: DealSpreadStatus,
    val intent: TradeListingIntent,
    val isClosedSold: Boolean,
    val matchedExpression: String,
)

data class TradePremiumObservation(
    val topicId: Long,
    val title: String,
    val premiumDeltaCny: BigDecimal,
    val spreadStatus: DealSpreadStatus,
    val intent: TradeListingIntent,
    val isClosedSold: Boolean,
    val source: MarketDataSource,
    val matchedExpression: String,
    val authorName: String = "",
    val activityHint: String = "",
)

data class TradePremiumDistribution(
    val query: String,
    val normalizedTokens: List<String>,
    val sampleCount: Int,
    val matchedTopicCount: Int,
    val premiumCount: Int,
    val parCount: Int,
    val discountCount: Int,
    val minCny: BigDecimal?,
    val p25Cny: BigDecimal?,
    val medianCny: BigDecimal?,
    val p75Cny: BigDecimal?,
    val maxCny: BigDecimal?,
    val observations: List<TradePremiumObservation>,
    val localSampleCount: Int,
    val remoteSampleCount: Int,
)

enum class MarketSearchStatus {
    IDLE,
    LOADING,
    SUCCESS,
    COOLDOWN,
    RATE_LIMITED,
    CHALLENGE_REQUIRED,
    SIGN_IN_REQUIRED,
    FAILED,
}

data class TradeMarketReferenceState(
    val query: String = "",
    val distribution: TradePremiumDistribution? = null,
    val searchStatus: MarketSearchStatus = MarketSearchStatus.IDLE,
    val cooldownUntilEpochMillis: Long = 0L,
    val cooldownRemainingSeconds: Int = 0,
    val statusMessage: String? = null,
    val lastRemoteSearchedAtMillis: Long? = null,
    val hasRemoteResults: Boolean = false,
)
