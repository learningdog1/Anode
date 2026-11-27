package com.nodeseek.app.domain.repository

import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AccountSlotSummary
import com.nodeseek.app.domain.model.AccountSwitchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface MultiAccountManager {
    val accountsFlow: StateFlow<List<AccountSlotSummary>>
    val isSwitchingFlow: StateFlow<Boolean>
    val pendingAddAccountFlow: StateFlow<Boolean>

    suspend fun initializeAndSyncActiveAccount()

    suspend fun switchAccount(targetUid: Long): AccountSwitchResult

    suspend fun beginAddAccount(): Result<Unit>

    suspend fun cancelAddAccountIfUnauthenticated(): AccountSession?

    suspend fun removeAccount(uid: Long): Result<Unit>

    suspend fun onActiveAccountSignedOut(signedOutUid: Long?)
}

object NoOpMultiAccountManager : MultiAccountManager {
    private val emptyAccounts = MutableStateFlow<List<AccountSlotSummary>>(emptyList())
    private val falseFlow = MutableStateFlow(false)

    override val accountsFlow: StateFlow<List<AccountSlotSummary>> = emptyAccounts.asStateFlow()
    override val isSwitchingFlow: StateFlow<Boolean> = falseFlow.asStateFlow()
    override val pendingAddAccountFlow: StateFlow<Boolean> = falseFlow.asStateFlow()

    override suspend fun initializeAndSyncActiveAccount() = Unit

    override suspend fun switchAccount(targetUid: Long): AccountSwitchResult =
        AccountSwitchResult.Failure("当前环境不支持多账号切换")

    override suspend fun beginAddAccount(): Result<Unit> = Result.success(Unit)

    override suspend fun cancelAddAccountIfUnauthenticated(): AccountSession? = null

    override suspend fun removeAccount(uid: Long): Result<Unit> = Result.success(Unit)

    override suspend fun onActiveAccountSignedOut(signedOutUid: Long?) = Unit
}
