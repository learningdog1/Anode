package com.nodeseek.app.domain.trust

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Repository for querying completed transaction stats from the NodeSeek Official Trade Platform
 * (`https://agent-api.nodeseek.org`) and managing the device-local encrypted `ntk_` API Key.
 */
interface OfficialTradeRepository {
    val isConfigured: StateFlow<Boolean>

    suspend fun lookup(
        username: String,
        forceRefresh: Boolean = false,
    ): OfficialTradeLookup

    /**
     * Strictly reads a fresh positive result from memory/disk cache without ever issuing network
     * requests. Safe for inline topic/floor quick badges.
     */
    suspend fun getCachedOnly(username: String): OfficialTradeLookup.Found?

    /**
     * Reactive zero-network flow of fresh positive cached stats for [username]. Never triggers
     * network calls; updates automatically when [lookup] populates or refreshes the cache.
     */
    fun observeCachedOnly(username: String): Flow<OfficialTradeLookup.Found?> = flowOf(null)

    /**
     * Trims and validates the `ntk_` prefix before encrypting and persisting the API Key locally.
     *
     * @throws IllegalArgumentException if the key does not start with `ntk_` or contains whitespace/control chars.
     */
    suspend fun saveApiKey(key: String)

    suspend fun clearApiKey()

    /**
     * Verifies [key] against the Official Trade Platform using a single lightweight request
     * without mutating the saved key or polluting seller lookup caches.
     *
     * Returns [OfficialTradeLookup.Found] when the key is valid, [OfficialTradeLookup.Unauthorized]
     * on 401/403, [OfficialTradeLookup.RateLimited] on 429/cooldown, or [OfficialTradeLookup.Failed]
     * on format/network errors.
     */
    suspend fun verifyApiKey(key: String): OfficialTradeLookup

    /**
     * Returns a masked representation of the currently configured key (e.g. `ntk_abcd…1234`),
     * or `null` when unconfigured.
     */
    fun maskedApiKey(): String?
}

/**
 * Safe no-op [OfficialTradeRepository] used as default fallback in preview/test containers.
 */
object NoOpOfficialTradeRepository : OfficialTradeRepository {
    private val configuredState = MutableStateFlow(false)

    override val isConfigured: StateFlow<Boolean> = configuredState

    override suspend fun lookup(
        username: String,
        forceRefresh: Boolean,
    ): OfficialTradeLookup = OfficialTradeLookup.NotConfigured

    override suspend fun getCachedOnly(username: String): OfficialTradeLookup.Found? = null

    override suspend fun saveApiKey(key: String) = Unit

    override suspend fun clearApiKey() = Unit

    override suspend fun verifyApiKey(key: String): OfficialTradeLookup =
        OfficialTradeLookup.NotConfigured

    override fun maskedApiKey(): String? = null
}
