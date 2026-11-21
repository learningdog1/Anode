package com.nodeseek.app.domain.trust

import kotlinx.serialization.Serializable

/**
 * Per-role completed transaction breakdown from the NodeSeek Official Trade Platform
 * (`agent.nodeseek.org` / `agent-api.nodeseek.org`).
 *
 * Known [role] values returned by `/open/v1/users/{id}/stats`:
 * - `"buyer"` (买家)
 * - `"seller"` (卖家)
 * - `"agent"` (中介)
 */
@Serializable
data class OfficialTradeRoleStat(
    val role: String,
    val trades: Int,
    val amount: String,
) {
    val roleDisplayLabel: String
        get() = when (role.trim().lowercase()) {
            ROLE_BUYER -> "买家"
            ROLE_SELLER -> "卖家"
            ROLE_AGENT -> "中介"
            else -> role
        }

    companion object {
        const val ROLE_BUYER: String = "buyer"
        const val ROLE_SELLER: String = "seller"
        const val ROLE_AGENT: String = "agent"
    }
}

/**
 * Aggregated completed transaction statistics for a single user account on the NodeSeek Official
 * Trade Platform (`GET /open/v1/users/{id}/stats`).
 */
@Serializable
data class OfficialTradeUserStats(
    val userId: Long,
    val username: String,
    val trades: Int,
    val amount: String,
    val lastCompletedAt: String?,
    val byRole: List<OfficialTradeRoleStat>,
    val userRole: String = "user",
    val avatarUrl: String? = null,
) {
    val isOfficialAgent: Boolean
        get() = userRole.equals(OfficialTradeRoleStat.ROLE_AGENT, ignoreCase = true)
}

/**
 * Result of looking up a username against the NodeSeek Official Trade Platform Open API.
 */
sealed interface OfficialTradeLookup {
    /** User has not configured an Official Trade Platform API Key in Settings. */
    data object NotConfigured : OfficialTradeLookup

    /** Username does not exist on the Official Trade Platform. */
    data object NotFound : OfficialTradeLookup

    /**
     * One or more platform accounts matched the queried username (up to 5 duplicate-name matches).
     */
    data class Found(
        val matches: List<OfficialTradeUserStats>,
        val fetchedAtMillis: Long,
        val fromCache: Boolean,
    ) : OfficialTradeLookup

    /** Client token-bucket limit or server HTTP 429 cooldown is active. */
    data class RateLimited(
        val retryAfterSeconds: Long?,
    ) : OfficialTradeLookup

    /** HTTP 401/403 — API Key is invalid, expired, or revoked. */
    data object Unauthorized : OfficialTradeLookup

    /** Network, timeout, server, or validation error. */
    data class Failed(
        val message: String,
    ) : OfficialTradeLookup
}
