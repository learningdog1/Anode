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

enum class PricingConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

data class TradeCandidateTopic(
    val topicId: Long,
    val title: String,
    val source: MarketDataSource,
    val authorName: String = "",
    val activityHint: String = "",
    val bodySnippet: String? = null,
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
    val askPriceCny: BigDecimal? = null,
    val residualCny: BigDecimal? = null,
    val isDerived: Boolean = false,
    val confidence: PricingConfidence = PricingConfidence.HIGH,
    val isAskDerived: Boolean = false,
)

data class TradeAskPriceObservation(
    val topicId: Long,
    val title: String,
    val askPriceCny: BigDecimal,
    val intent: TradeListingIntent,
    val isClosedSold: Boolean,
    val source: MarketDataSource,
    val matchedExpression: String,
    val authorName: String = "",
    val activityHint: String = "",
    val residualCny: BigDecimal? = null,
    val inferredSpreadCny: BigDecimal? = null,
    val isAskDerived: Boolean = false,
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
    val derivedSampleCount: Int = 0,
    val askSampleCount: Int = 0,
    val askMinCny: BigDecimal? = null,
    val askP25Cny: BigDecimal? = null,
    val askMedianCny: BigDecimal? = null,
    val askP75Cny: BigDecimal? = null,
    val askMaxCny: BigDecimal? = null,
    val askObservations: List<TradeAskPriceObservation> = emptyList(),
    val matchedButUnparsedCount: Int = 0,
    val matchedUnparsedTopics: List<TradeCandidateTopic> = emptyList(),
    val derivedAskSampleCount: Int = 0,
) {
    /**
     * Number of distinct matched listings that produced either a spread or an ask-price sample.
     */
    val pricedTopicCount: Int
        get() = buildSet {
            observations.forEach { add(it.topicId) }
            askObservations.forEach { add(it.topicId) }
        }.size

    /**
     * Ask-price observations that did NOT also produce a spread observation.
     */
    val askOnlyObservations: List<TradeAskPriceObservation>
        get() {
            if (observations.isEmpty()) return askObservations
            val spreadIds = observations.mapTo(HashSet(observations.size)) { it.topicId }
            return askObservations.filter { it.topicId !in spreadIds }
        }
}

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
    val currentTopicPricing: InferredListingPricing? = null,
)
