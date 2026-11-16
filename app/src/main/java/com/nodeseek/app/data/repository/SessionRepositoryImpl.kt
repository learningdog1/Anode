package com.nodeseek.app.data.repository

import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.data.network.WebViewCookieBridge
import com.nodeseek.app.data.parser.CategoryDto
import com.nodeseek.app.data.parser.ConfigPayloadDto
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.repository.SessionRepository
import com.nodeseek.app.domain.repository.SignInStepResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Concrete [SessionRepository] synchronizing authentication state from embedded HTML `__config__.user`,
 * native Turnstile/2FA sign-in, and interactive WebView cookie sessions.
 */
class SessionRepositoryImpl(
    private val apiClientProvider: () -> NodeSeekApiClient,
    private val cookieBridge: WebViewCookieBridge,
) : SessionRepository {

    private val _sessionState = MutableStateFlow<AccountSession?>(null)
    override val sessionState: StateFlow<AccountSession?> = _sessionState.asStateFlow()

    private val _challengeRequired = MutableStateFlow(false)
    override val challengeRequired: StateFlow<Boolean> = _challengeRequired.asStateFlow()

    private val _authVerifiedEpoch = MutableStateFlow(0L)
    override val authVerifiedEpoch: StateFlow<Long> = _authVerifiedEpoch.asStateFlow()

    var unreadCountListener: ((Int) -> Unit)? = null
    var embeddedCategoriesListener: ((List<CategoryDto>, Long) -> Unit)? = null
    var onVerifiedCallback: (() -> Unit)? = null

    fun onConfigPayloadObserved(config: ConfigPayloadDto) {
        _challengeRequired.value = false
        val session = config.user?.toDomainSession(config.enableCustomedStyle)
        _sessionState.value = session
        unreadCountListener?.invoke(session?.unreadNotificationCount ?: 0)
        val categories = config.allCategory
        if (!categories.isNullOrEmpty()) {
            embeddedCategoriesListener?.invoke(categories, session?.uid ?: 0L)
        }
    }

    fun reportChallengeRequired() {
        _challengeRequired.value = true
    }

    fun clearChallengeRequired() {
        _challengeRequired.value = false
    }

    fun updateCoinBalance(updatedCoins: Int) {
        _sessionState.update { current ->
            current?.copy(drumsticks = updatedCoins)
        }
    }

    override suspend fun refreshSession(): Result<AccountSession?> =
        runDomainCatching(onChallengeDetected = ::reportChallengeRequired) {
            val config = apiClientProvider().fetchSessionConfig()
            onConfigPayloadObserved(config)
            _sessionState.value
        }

    override suspend fun signIn(
        username: String,
        password: String,
        turnstileToken: String,
    ): Result<SignInStepResult> =
        runDomainCatching(onChallengeDetected = ::reportChallengeRequired) {
            val response = apiClientProvider().signIn(
                username = username.trim(),
                password = password,
                turnstileToken = turnstileToken.trim(),
            )
            val otpSession = response.resolvedOtpSession
            if (response.resolvedNeed2FA == true && !otpSession.isNullOrBlank()) {
                return@runDomainCatching SignInStepResult.TwoFactorRequired(otpSession)
            }
            if (!response.success) {
                throw NodeSeekApiException(response.message ?: "登录失败，请检查用户名、密码或人机验证。")
            }
            cookieBridge.flush()
            onVerifiedCallback?.invoke()
            _challengeRequired.value = false
            val config = apiClientProvider().fetchSessionConfig()
            onConfigPayloadObserved(config)
            if (config.user == null) {
                throw NodeSeekApiException("登录状态未能同步生效，请重试或改用 WebView 官方网页登录。")
            }
            SignInStepResult.Authenticated
        }

    override suspend fun verifyTwoFactor(
        otpCode: String,
        otpSession: String,
    ): Result<Unit> =
        runDomainCatching(onChallengeDetected = ::reportChallengeRequired) {
            val response = apiClientProvider().verifyTwoFactor(
                otpCode = otpCode.trim(),
                otpSession = otpSession,
            )
            if (!response.success) {
                if (response.isOtpExpired) {
                    throw NodeSeekApiException(
                        message = "两步验证会话已过期，请返回重新登录。",
                        serverMessage = response.message ?: "OTP_EXPIRED_OR_NOT_EXIST",
                    )
                }
                throw NodeSeekApiException(response.message ?: "双因素验证码错误，请重试。")
            }
            cookieBridge.flush()
            onVerifiedCallback?.invoke()
            _challengeRequired.value = false
            val config = apiClientProvider().fetchSessionConfig()
            onConfigPayloadObserved(config)
            if (config.user == null) {
                throw NodeSeekApiException("两步验证通过但登录状态未能同步生效，请重试或改用 WebView 官方网页登录。")
            }
        }

    override suspend fun onWebViewAuthCompleted(): Result<AccountSession?> {
        cookieBridge.flush()
        onVerifiedCallback?.invoke()
        _challengeRequired.value = false
        val refreshed = refreshSession()
        _authVerifiedEpoch.value = System.currentTimeMillis()
        return refreshed
    }

    override suspend fun onWebViewDismissed(): Result<AccountSession?> {
        val wasChallenged = _challengeRequired.value
        cookieBridge.flush()
        if (cookieBridge.hasSessionOrClearanceCookie()) {
            onVerifiedCallback?.invoke()
        }
        val refreshed = refreshSession()
        if (refreshed.isSuccess) {
            _challengeRequired.value = false
            if (wasChallenged || _sessionState.value != null) {
                _authVerifiedEpoch.value = System.currentTimeMillis()
            }
        }
        return refreshed
    }

    override suspend fun signOut(): Result<Unit> {
        val remoteSignOutResult = try {
            apiClientProvider().signOut()
            Result.success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Result.failure(t)
        }

        // Always clear local session cookies and state so HTTP 403 / Cloudflare challenge on
        // /api/account/signOut can never trap the user in a logged-in state on this device.
        cookieBridge.clearSessionCookies(includeClearance = false)
        apiClientProvider().clearTransportSessionContext()

        if (remoteSignOutResult.isSuccess) {
            val verifiedConfig = runCatching { apiClientProvider().fetchSessionConfig() }.getOrNull()
            if (verifiedConfig?.user != null) {
                cookieBridge.clearSessionCookies(includeClearance = true)
            }
        }

        clearLocalSessionState()
        return Result.success(Unit)
    }

    override suspend fun signOutLocalOnly(): Result<Unit> {
        cookieBridge.clearSessionCookies(includeClearance = false)
        apiClientProvider().clearTransportSessionContext()
        clearLocalSessionState()
        return Result.success(Unit)
    }

    private fun clearLocalSessionState() {
        _challengeRequired.value = false
        _sessionState.value = null
        unreadCountListener?.invoke(0)
    }
}
