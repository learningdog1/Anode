package com.nodeseek.app.domain.valuation

interface TradeMarketReferenceRepository {
    suspend fun analyzeLocal(
        query: String,
        includeBuyRequests: Boolean = false,
        excludeTopicId: Long? = null,
    ): TradeMarketReferenceState

    suspend fun searchRemoteAndMerge(
        query: String,
        includeBuyRequests: Boolean = false,
        excludeTopicId: Long? = null,
    ): TradeMarketReferenceState

    /**
     * Records an already-fetched topic OP body markdown snippet in local memory so subsequent
     * market analyses for peer listings can parse body prices without extra network requests.
     */
    fun cacheTopicBody(topicId: Long, bodyMarkdown: String) {}
}

object NoOpTradeMarketReferenceRepository : TradeMarketReferenceRepository {
    override suspend fun analyzeLocal(
        query: String,
        includeBuyRequests: Boolean,
        excludeTopicId: Long?,
    ): TradeMarketReferenceState = TradeMarketReferenceState(query = query.trim())

    override suspend fun searchRemoteAndMerge(
        query: String,
        includeBuyRequests: Boolean,
        excludeTopicId: Long?,
    ): TradeMarketReferenceState = TradeMarketReferenceState(query = query.trim())
}
