package com.nodeseek.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class AccountSlotStatus {
    ACTIVE,
    READY,
    EXPIRED,
}

@Serializable
enum class CookieHostScope {
    SHARED_DOT_DOMAIN,
    WWW_ONLY,
    BARE_ONLY,
}

@Serializable
data class StoredCookieRecord(
    val name: String,
    val value: String,
    val scope: CookieHostScope,
)

@Serializable
data class StoredAccountSlot(
    val session: AccountSession,
    val encryptedCookies: String,
    val status: AccountSlotStatus = AccountSlotStatus.READY,
    val addedAtEpochMillis: Long,
    val lastUsedAtEpochMillis: Long,
)

data class AccountSlotSummary(
    val uid: Long,
    val username: String,
    val avatarUrl: String,
    val level: Int,
    val drumsticks: Int,
    val stardust: Int,
    val isActive: Boolean,
    val status: AccountSlotStatus,
    val lastUsedAtEpochMillis: Long,
)

sealed interface AccountSwitchResult {
    data class Switched(val session: AccountSession) : AccountSwitchResult

    data class ChallengeRequired(val session: AccountSession) : AccountSwitchResult

    data class OfflineUsingSnapshot(
        val session: AccountSession,
        val cause: Throwable,
    ) : AccountSwitchResult

    data class TargetExpiredRolledBack(
        val expiredUid: Long,
        val expiredUsername: String,
        val restoredSession: AccountSession?,
    ) : AccountSwitchResult

    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : AccountSwitchResult
}
