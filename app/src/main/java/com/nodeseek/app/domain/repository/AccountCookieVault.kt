package com.nodeseek.app.domain.repository

import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AccountSlotStatus
import com.nodeseek.app.domain.model.StoredAccountSlot
import com.nodeseek.app.domain.model.StoredCookieRecord
import kotlinx.coroutines.flow.StateFlow

interface AccountCookieVault {
    val slotsFlow: StateFlow<List<StoredAccountSlot>>

    suspend fun ensureLoaded()

    suspend fun saveOrUpdateSlot(
        session: AccountSession,
        cookies: List<StoredCookieRecord>,
        status: AccountSlotStatus = AccountSlotStatus.ACTIVE,
    )

    suspend fun updateSessionMetadata(
        session: AccountSession,
        status: AccountSlotStatus = AccountSlotStatus.ACTIVE,
    )

    suspend fun getSlot(uid: Long): StoredAccountSlot?

    suspend fun decryptCookies(uid: Long): List<StoredCookieRecord>?

    suspend fun markStatus(uid: Long, status: AccountSlotStatus)

    suspend fun removeSlot(uid: Long)

    suspend fun clearAll()
}
