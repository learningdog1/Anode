package com.nodeseek.app.domain.trust

import kotlinx.coroutines.flow.Flow

/**
 * Repository powering the pre-trade "查一查" Seller Reputation & Risk Check Card.
 */
interface SellerTrustRepository {
    suspend fun inspectSeller(
        uid: Long,
        usernameHint: String,
        forceRefresh: Boolean = false,
    ): SellerTrustReport

    fun observeLocalNote(uid: Long): Flow<LocalSellerNote?>

    suspend fun saveLocalNote(
        uid: Long,
        username: String,
        tag: TrustTag,
        noteText: String,
    )

    suspend fun getAllNotes(): List<LocalSellerNote>
}
