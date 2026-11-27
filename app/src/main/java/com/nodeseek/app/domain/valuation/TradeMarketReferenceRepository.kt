package com.nodeseek.app.domain.valuation

interface TradeMarketReferenceRepository {
    suspend fun analyzeLocal(
        query: String,
        includeBuyRequests: Boolean = false,
    ): TradeMarketReferenceState

    suspend fun searchRemoteAndMerge(
        query: String,
        includeBuyRequests: Boolean = false,
    ): TradeMarketReferenceState
}

object NoOpTradeMarketReferenceRepository : TradeMarketReferenceRepository {
    override suspend fun analyzeLocal(
        query: String,
        includeBuyRequests: Boolean,
    ): TradeMarketReferenceState = TradeMarketReferenceState(query = query.trim())

    override suspend fun searchRemoteAndMerge(
        query: String,
        includeBuyRequests: Boolean,
    ): TradeMarketReferenceState = TradeMarketReferenceState(query = query.trim())
}
