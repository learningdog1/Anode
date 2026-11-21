package com.nodeseek.app.domain.trust

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Repository powering the pre-trade "查一查" Seller Reputation & Risk Check Card.
 */
interface SellerTrustRepository {
    suspend fun inspectSeller(
        uid: Long,
        usernameHint: String,
        forceRefresh: Boolean = false,
    ): SellerTrustReport

    /**
     * Runs an explicit, user-triggered single-query station `/search` deep scan for historical
     * exposure posts mentioning the seller, merging any hits into the cached [SellerTrustReport].
     */
    suspend fun searchSellerExposureDeep(
        uid: Long,
        usernameHint: String,
    ): SellerTrustReport = inspectSeller(uid = uid, usernameHint = usernameHint, forceRefresh = true)

    /**
     * Scans the next paced batch of older user discussion pages (`list-discussions`) to uncover
     * historical trade posts beyond the initial automatic scan window without triggering rate limits.
     */
    suspend fun scanMoreSellerTradeTopics(
        uid: Long,
        usernameHint: String = "",
    ): SellerTrustReport = inspectSeller(uid = uid, usernameHint = usernameHint, forceRefresh = false)

    /**
     * Observes cached positive/partial [SellerTrustReport] snapshots for [uid] from memory/disk
     * without ever triggering remote HTTP requests. Safe for inline list/floor badges.
     */
    fun observeCachedReport(uid: Long): Flow<SellerTrustReport?> = flowOf(null)

    /**
     * Observes cached positive/partial [SellerTrustReport] snapshots by [uid] or fallback [usernameHint]
     * without triggering remote HTTP requests.
     */
    fun observeCachedReport(
        uid: Long,
        usernameHint: String,
    ): Flow<SellerTrustReport?> = observeCachedReport(uid)

    /**
     * Reads a cached [SellerTrustReport] from memory/disk if available, never making network calls.
     */
    suspend fun getCachedReportOnly(
        uid: Long,
        usernameHint: String = "",
    ): SellerTrustReport? = null

    /**
     * Enqueues a low-priority, deduplicated background inspection that strictly obeys cache TTL,
     * site rate-limit cooldown, global concurrency cap, and per-site pacing.
     */
    fun enqueueInspect(
        uid: Long,
        usernameHint: String = "",
    ) = Unit

    fun observeLocalNote(uid: Long): Flow<LocalSellerNote?>

    suspend fun saveLocalNote(
        uid: Long,
        username: String,
        tag: TrustTag,
        noteText: String,
    )

    suspend fun getAllNotes(): List<LocalSellerNote>
}
