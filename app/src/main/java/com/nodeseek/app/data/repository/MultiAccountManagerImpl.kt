package com.nodeseek.app.data.repository

import com.nodeseek.app.data.network.CloudflareChallengeException
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.data.network.SessionCookieStore
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AccountSlotStatus
import com.nodeseek.app.domain.model.AccountSlotSummary
import com.nodeseek.app.domain.model.AccountSwitchResult
import com.nodeseek.app.domain.model.StoredAccountSlot
import com.nodeseek.app.domain.model.StoredCookieRecord
import com.nodeseek.app.domain.model.TopicAccessException
import com.nodeseek.app.domain.repository.AccountCookieVault
import com.nodeseek.app.domain.repository.BookmarkAndHistoryRepository
import com.nodeseek.app.domain.repository.CheckInRepository
import com.nodeseek.app.domain.repository.ForumRepository
import com.nodeseek.app.domain.repository.MultiAccountManager
import com.nodeseek.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Coordinates serialized multi-account switching, local encrypted cookie-slot snapshots,
 * cross-account cache resets, add-account rollback, and expired-slot recovery without
 * ever calling `/api/account/signOut` during switches.
 */
class MultiAccountManagerImpl(
    private val vault: AccountCookieVault,
    private val cookieStore: SessionCookieStore,
    private val sessionRepository: SessionRepositoryImpl,
    private val apiClientProvider: () -> NodeSeekApiClient,
    private val checkInRepository: CheckInRepository,
    private val bookmarkAndHistoryRepository: BookmarkAndHistoryRepository,
    private val forumRepository: ForumRepository,
    private val externalScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val settingsRepository: SettingsRepository? = null,
) : MultiAccountManager {

    private val mutex = Mutex()

    private val _accountsFlow = MutableStateFlow<List<AccountSlotSummary>>(emptyList())
    override val accountsFlow: StateFlow<List<AccountSlotSummary>> = _accountsFlow.asStateFlow()

    private val _isSwitchingFlow = MutableStateFlow(false)
    override val isSwitchingFlow: StateFlow<Boolean> = _isSwitchingFlow.asStateFlow()

    private val _pendingAddAccountFlow = MutableStateFlow(false)
    override val pendingAddAccountFlow: StateFlow<Boolean> = _pendingAddAccountFlow.asStateFlow()

    @Volatile
    private var isInternalTransition: Boolean = false

    private var pendingRollbackUid: Long? = null
    private var sessionObserverJob: Job? = null
    private var summaryObserverJob: Job? = null

    override suspend fun initializeAndSyncActiveAccount() {
        mutex.withLock {
            isInternalTransition = true
            try {
                vault.ensureLoaded()
                val activeUid = (sessionRepository.sessionState.value?.uid ?: 0L).coerceAtLeast(0L)
                settingsRepository?.setActiveHomepageCategoryAccountUid(activeUid)
                syncSessionToVaultLocked(sessionRepository.sessionState.value)
                startObserversLocked()
            } finally {
                isInternalTransition = false
                refreshAccountsSummaryLocked()
            }
        }
    }

    override suspend fun switchAccount(targetUid: Long): AccountSwitchResult {
        if (targetUid <= 0L) {
            return AccountSwitchResult.Failure("无效的账号编号")
        }
        return mutex.withLock {
            isInternalTransition = true
            try {
                vault.ensureLoaded()
                startObserversLocked()

                val currentSession = sessionRepository.sessionState.value
                if (currentSession != null && currentSession.uid == targetUid) {
                    settingsRepository?.setActiveHomepageCategoryAccountUid(targetUid)
                    val liveCookies = cookieStore.exportAccountCookies()
                    if (liveCookies.isNotEmpty()) {
                        vault.saveOrUpdateSlot(currentSession, liveCookies, AccountSlotStatus.ACTIVE)
                    } else {
                        vault.updateSessionMetadata(currentSession, AccountSlotStatus.ACTIVE)
                    }
                    _pendingAddAccountFlow.value = false
                    pendingRollbackUid = null
                    return@withLock AccountSwitchResult.Switched(currentSession)
                }

                val targetSlot = vault.getSlot(targetUid)
                    ?: return@withLock AccountSwitchResult.Failure("未找到目标账号槽位")

                val targetCookies = vault.decryptCookies(targetUid)
                if (targetCookies == null || targetCookies.isEmpty()) {
                    vault.markStatus(targetUid, AccountSlotStatus.EXPIRED)
                    val restoredSession = if (currentSession != null) {
                        currentSession
                    } else if (_pendingAddAccountFlow.value) {
                        val rollbackUid = pendingRollbackUid
                        val pendingRollbackSession = rollbackUid
                            ?.takeIf { it > 0L && it != targetUid }
                            ?.let { vault.getSlot(it)?.session }
                        val pendingRollbackCookies = rollbackUid
                            ?.takeIf { it > 0L && it != targetUid }
                            ?.let { vault.decryptCookies(it).orEmpty() }
                            .orEmpty()
                        val restored = rollbackAfterExpiredTargetLocked(
                            pendingRollbackSession,
                            pendingRollbackCookies,
                        )
                        _pendingAddAccountFlow.value = false
                        pendingRollbackUid = null
                        restored
                    } else {
                        null
                    }
                    return@withLock AccountSwitchResult.TargetExpiredRolledBack(
                        expiredUid = targetUid,
                        expiredUsername = targetSlot.session.username,
                        restoredSession = restoredSession,
                    )
                }

                var rollbackSession: AccountSession? = null
                var rollbackCookies: List<StoredCookieRecord> = emptyList()
                if (currentSession != null && currentSession.uid > 0L) {
                    val exportedCurrent = cookieStore.exportAccountCookies()
                    val storedCurrent = if (exportedCurrent.isNotEmpty()) {
                        vault.saveOrUpdateSlot(currentSession, exportedCurrent, AccountSlotStatus.READY)
                        exportedCurrent
                    } else {
                        vault.markStatus(currentSession.uid, AccountSlotStatus.READY)
                        vault.decryptCookies(currentSession.uid).orEmpty()
                    }
                    rollbackSession = currentSession
                    rollbackCookies = storedCurrent
                } else if (_pendingAddAccountFlow.value) {
                    val rollbackUid = pendingRollbackUid
                    if (rollbackUid != null && rollbackUid > 0L) {
                        rollbackSession = vault.getSlot(rollbackUid)?.session
                        rollbackCookies = vault.decryptCookies(rollbackUid).orEmpty()
                    }
                }

                _isSwitchingFlow.value = true
                cookieStore.clearSessionCookies(includeClearance = false)
                clearCrossAccountCaches()
                settingsRepository?.setActiveHomepageCategoryAccountUid(targetUid)

                cookieStore.restoreAccountCookies(targetCookies)
                sessionRepository.applyOptimisticSwitchedSession(targetSlot.session)
                vault.updateSessionMetadata(targetSlot.session, AccountSlotStatus.ACTIVE)
                refreshAccountsSummaryLocked()

                val refreshResult = sessionRepository.refreshSession()
                if (refreshResult.isSuccess) {
                    val verified = refreshResult.getOrNull()
                    if (verified != null && verified.uid > 0L) {
                        val liveCookies = cookieStore.exportAccountCookies().ifEmpty { targetCookies }
                        vault.saveOrUpdateSlot(verified, liveCookies, AccountSlotStatus.ACTIVE)
                        _pendingAddAccountFlow.value = false
                        pendingRollbackUid = null
                        sessionRepository.notifyAuthContextChanged()
                        AccountSwitchResult.Switched(verified)
                    } else {
                        vault.markStatus(targetUid, AccountSlotStatus.EXPIRED)
                        val expiredRollbackSession = rollbackSession?.takeIf { it.uid != targetUid }
                        val expiredRollbackCookies = if (expiredRollbackSession != null) {
                            rollbackCookies
                        } else {
                            emptyList()
                        }
                        val restoredSession = rollbackAfterExpiredTargetLocked(
                            expiredRollbackSession,
                            expiredRollbackCookies,
                        )
                        _pendingAddAccountFlow.value = false
                        pendingRollbackUid = null
                        AccountSwitchResult.TargetExpiredRolledBack(
                            expiredUid = targetUid,
                            expiredUsername = targetSlot.session.username,
                            restoredSession = restoredSession,
                        )
                    }
                } else {
                    val error = refreshResult.exceptionOrNull()
                        ?: IllegalStateException("切换账号失败")
                    if (error is CancellationException) {
                        throw error
                    }
                    when {
                        error is CloudflareChallengeException ||
                            error is TopicAccessException.CloudflareChallengeRequired -> {
                            sessionRepository.reportChallengeRequired()
                            _pendingAddAccountFlow.value = false
                            pendingRollbackUid = null
                            AccountSwitchResult.ChallengeRequired(targetSlot.session)
                        }
                        error is IOException && error !is NodeSeekApiException -> {
                            sessionRepository.clearChallengeRequired()
                            _pendingAddAccountFlow.value = false
                            pendingRollbackUid = null
                            AccountSwitchResult.OfflineUsingSnapshot(targetSlot.session, error)
                        }
                        else -> {
                            vault.markStatus(targetUid, AccountSlotStatus.READY)
                            rollbackAfterExpiredTargetLocked(rollbackSession, rollbackCookies)
                            _pendingAddAccountFlow.value = false
                            pendingRollbackUid = null
                            AccountSwitchResult.Failure(
                                message = error.message ?: "切换账号失败，请稍后重试",
                                cause = error,
                            )
                        }
                    }
                }
            } finally {
                isInternalTransition = false
                _isSwitchingFlow.value = false
                refreshAccountsSummaryLocked()
            }
        }
    }

    override suspend fun beginAddAccount(): Result<Unit> {
        return try {
            mutex.withLock {
                isInternalTransition = true
                try {
                    vault.ensureLoaded()
                    startObserversLocked()

                    val current = sessionRepository.sessionState.value
                    if (current != null && current.uid > 0L) {
                        val exported = cookieStore.exportAccountCookies()
                        if (exported.isNotEmpty()) {
                            vault.saveOrUpdateSlot(current, exported, AccountSlotStatus.READY)
                        } else {
                            vault.markStatus(current.uid, AccountSlotStatus.READY)
                        }
                        pendingRollbackUid = current.uid
                    } else {
                        pendingRollbackUid = null
                    }
                    _pendingAddAccountFlow.value = true

                    // Clear only local cookies & transport state; never revoke server session.
                    cookieStore.clearSessionCookies(includeClearance = false)
                    clearCrossAccountCaches()
                    settingsRepository?.setActiveHomepageCategoryAccountUid(0L)
                    sessionRepository.enterGuestForAccountAddition()
                } finally {
                    isInternalTransition = false
                    refreshAccountsSummaryLocked()
                }
            }
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun cancelAddAccountIfUnauthenticated(): AccountSession? {
        return mutex.withLock {
            isInternalTransition = true
            try {
                vault.ensureLoaded()
                if (!_pendingAddAccountFlow.value) {
                    return@withLock sessionRepository.sessionState.value
                }

                val current = sessionRepository.sessionState.value
                if (current != null && current.uid > 0L) {
                    settingsRepository?.setActiveHomepageCategoryAccountUid(current.uid)
                    syncSessionToVaultLocked(current)
                    _pendingAddAccountFlow.value = false
                    pendingRollbackUid = null
                    return@withLock current
                }

                val rollbackUid = pendingRollbackUid
                _pendingAddAccountFlow.value = false
                pendingRollbackUid = null

                if (rollbackUid == null) {
                    settingsRepository?.setActiveHomepageCategoryAccountUid(0L)
                    return@withLock null
                }

                val rollbackSlot = vault.getSlot(rollbackUid)
                    ?: run {
                        settingsRepository?.setActiveHomepageCategoryAccountUid(0L)
                        return@withLock null
                    }

                val rollbackCookies = vault.decryptCookies(rollbackUid)
                if (rollbackCookies == null || rollbackCookies.isEmpty()) {
                    vault.markStatus(rollbackUid, AccountSlotStatus.EXPIRED)
                    settingsRepository?.setActiveHomepageCategoryAccountUid(0L)
                    return@withLock null
                }

                cookieStore.clearSessionCookies(includeClearance = false)
                clearCrossAccountCaches()
                settingsRepository?.setActiveHomepageCategoryAccountUid(rollbackSlot.session.uid)
                cookieStore.restoreAccountCookies(rollbackCookies)
                sessionRepository.applyOptimisticSwitchedSession(rollbackSlot.session)
                vault.saveOrUpdateSlot(rollbackSlot.session, rollbackCookies, AccountSlotStatus.ACTIVE)
                rollbackSlot.session
            } finally {
                isInternalTransition = false
                refreshAccountsSummaryLocked()
            }
        }
    }

    override suspend fun removeAccount(uid: Long): Result<Unit> {
        if (uid <= 0L) return Result.success(Unit)
        return try {
            mutex.withLock {
                isInternalTransition = true
                try {
                    vault.ensureLoaded()
                    val activeUid = sessionRepository.sessionState.value?.uid
                    vault.removeSlot(uid)
                    settingsRepository?.setHomepageCategoryOrder(uid, emptyList())
                    if (pendingRollbackUid == uid) {
                        pendingRollbackUid = null
                        _pendingAddAccountFlow.value = false
                    }
                    if (activeUid == uid) {
                        clearCrossAccountCaches()
                        settingsRepository?.setActiveHomepageCategoryAccountUid(0L)
                        sessionRepository.signOutLocalOnly()
                    }
                } finally {
                    isInternalTransition = false
                    refreshAccountsSummaryLocked()
                }
            }
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun onActiveAccountSignedOut(signedOutUid: Long?) {
        mutex.withLock {
            isInternalTransition = true
            try {
                vault.ensureLoaded()
                if (signedOutUid != null && signedOutUid > 0L) {
                    vault.removeSlot(signedOutUid)
                }
                settingsRepository?.setActiveHomepageCategoryAccountUid(0L)
                _pendingAddAccountFlow.value = false
                pendingRollbackUid = null
            } finally {
                isInternalTransition = false
                refreshAccountsSummaryLocked()
            }
        }
    }

    private suspend fun rollbackAfterExpiredTargetLocked(
        rollbackSession: AccountSession?,
        rollbackCookies: List<StoredCookieRecord>,
    ): AccountSession? {
        cookieStore.clearSessionCookies(includeClearance = false)
        clearCrossAccountCaches()
        if (rollbackSession != null && rollbackSession.uid > 0L && rollbackCookies.isNotEmpty()) {
            settingsRepository?.setActiveHomepageCategoryAccountUid(rollbackSession.uid)
            cookieStore.restoreAccountCookies(rollbackCookies)
            vault.saveOrUpdateSlot(rollbackSession, rollbackCookies, AccountSlotStatus.ACTIVE)
            sessionRepository.applyOptimisticSwitchedSession(rollbackSession)
            return rollbackSession
        }
        if (rollbackSession != null && rollbackSession.uid > 0L) {
            vault.markStatus(rollbackSession.uid, AccountSlotStatus.EXPIRED)
        }
        settingsRepository?.setActiveHomepageCategoryAccountUid(0L)
        sessionRepository.enterGuestForAccountAddition()
        return null
    }

    private fun clearCrossAccountCaches() {
        apiClientProvider().clearTransportSessionContext()
        checkInRepository.clearCachedStatus()
        bookmarkAndHistoryRepository.clearMemoryCache()
        forumRepository.clearRssFallbackCache()
    }

    private suspend fun syncSessionToVaultLocked(session: AccountSession?) {
        if (session == null || session.uid <= 0L) return
        val exported = cookieStore.exportAccountCookies()
        if (exported.isNotEmpty()) {
            vault.saveOrUpdateSlot(session, exported, AccountSlotStatus.ACTIVE)
            if (_pendingAddAccountFlow.value) {
                _pendingAddAccountFlow.value = false
                pendingRollbackUid = null
            }
        } else if (vault.getSlot(session.uid) != null) {
            vault.updateSessionMetadata(session, AccountSlotStatus.ACTIVE)
            if (_pendingAddAccountFlow.value) {
                _pendingAddAccountFlow.value = false
                pendingRollbackUid = null
            }
        }
    }

    private fun startObserversLocked() {
        if (summaryObserverJob == null) {
            summaryObserverJob = externalScope.launch {
                combine(vault.slotsFlow, sessionRepository.sessionState) { slots, active ->
                    buildSummaries(slots, active)
                }.collect { summaries ->
                    _accountsFlow.value = summaries
                }
            }
        }
        if (sessionObserverJob == null) {
            sessionObserverJob = externalScope.launch {
                sessionRepository.sessionState.collect { session ->
                    if (isInternalTransition) return@collect
                    if (session == null || session.uid <= 0L) return@collect
                    mutex.withLock {
                        val latestSession = sessionRepository.sessionState.value
                        if (!isInternalTransition &&
                            latestSession != null &&
                            latestSession.uid == session.uid
                        ) {
                            syncSessionToVaultLocked(latestSession)
                            refreshAccountsSummaryLocked()
                        }
                    }
                }
            }
        }
    }

    private fun refreshAccountsSummaryLocked() {
        _accountsFlow.value = buildSummaries(
            slots = vault.slotsFlow.value,
            activeSession = sessionRepository.sessionState.value,
        )
    }

    private fun buildSummaries(
        slots: List<StoredAccountSlot>,
        activeSession: AccountSession?,
    ): List<AccountSlotSummary> {
        val mapped = slots.map { slot ->
            val isActive = activeSession != null && slot.session.uid == activeSession.uid
            val effectiveSession = if (isActive) activeSession else slot.session
            val effectiveStatus = when {
                slot.status == AccountSlotStatus.EXPIRED -> AccountSlotStatus.EXPIRED
                isActive -> AccountSlotStatus.ACTIVE
                else -> AccountSlotStatus.READY
            }
            AccountSlotSummary(
                uid = effectiveSession.uid,
                username = effectiveSession.username,
                avatarUrl = effectiveSession.avatarUrl,
                level = effectiveSession.level,
                drumsticks = effectiveSession.drumsticks,
                stardust = effectiveSession.stardust,
                isActive = isActive,
                status = effectiveStatus,
                lastUsedAtEpochMillis = if (isActive) {
                    maxOf(slot.lastUsedAtEpochMillis, clock())
                } else {
                    slot.lastUsedAtEpochMillis
                },
            )
        }.toMutableList()

        if (activeSession != null && activeSession.uid > 0L && mapped.none { it.uid == activeSession.uid }) {
            mapped.add(
                0,
                AccountSlotSummary(
                    uid = activeSession.uid,
                    username = activeSession.username,
                    avatarUrl = activeSession.avatarUrl,
                    level = activeSession.level,
                    drumsticks = activeSession.drumsticks,
                    stardust = activeSession.stardust,
                    isActive = true,
                    status = AccountSlotStatus.ACTIVE,
                    lastUsedAtEpochMillis = clock(),
                ),
            )
        }

        return mapped.sortedWith(
            compareByDescending<AccountSlotSummary> { it.isActive }
                .thenByDescending { it.lastUsedAtEpochMillis },
        )
    }
}
