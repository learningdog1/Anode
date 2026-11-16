package com.nodeseek.app.domain.repository

import com.nodeseek.app.domain.model.AccountSession
import kotlinx.coroutines.flow.StateFlow

interface SessionRepository {
    val sessionState: StateFlow<AccountSession?>
    val challengeRequired: StateFlow<Boolean>
    val authVerifiedEpoch: StateFlow<Long>

    suspend fun refreshSession(): Result<AccountSession?>
    suspend fun signIn(
        username: String,
        password: String,
        turnstileToken: String,
    ): Result<SignInStepResult>
    suspend fun verifyTwoFactor(otpCode: String, otpSession: String): Result<Unit>
    suspend fun onWebViewAuthCompleted(): Result<AccountSession?>
    suspend fun onWebViewDismissed(): Result<AccountSession?>
    suspend fun signOut(): Result<Unit>
    suspend fun signOutLocalOnly(): Result<Unit> = signOut()
}

sealed interface SignInStepResult {
    data object Authenticated : SignInStepResult
    data class TwoFactorRequired(val otpSession: String) : SignInStepResult
}
