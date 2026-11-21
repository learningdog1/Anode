package com.nodeseek.app.ui.trust

import com.nodeseek.app.domain.trust.LocalSellerNote
import com.nodeseek.app.domain.trust.OfficialTradeLookup
import com.nodeseek.app.domain.trust.OfficialTradeRepository
import com.nodeseek.app.domain.trust.OfficialTradeRoleStat
import com.nodeseek.app.domain.trust.OfficialTradeUserStats
import com.nodeseek.app.domain.trust.SellerTrustReport
import com.nodeseek.app.domain.trust.SellerTrustRepository
import com.nodeseek.app.domain.trust.TrustRiskAssessment
import com.nodeseek.app.domain.trust.TrustRiskLevel
import com.nodeseek.app.domain.trust.TrustTag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SellerTrustOfficialTradeStateTest {

    private val baseNowMillis: Long = Instant.parse("2026-11-21T12:00:00Z").toEpochMilli()

    @Test
    fun `stateHolder loads official stats automatically and transitions across all lookup states`() = runTest {
        val officialRepo = FakeOfficialTradeRepository(configured = false)
        val sellerRepo = FakeSellerTrustRepository()
        val holderScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

        try {
            val holder = SellerTrustStateHolder(
                repository = sellerRepo,
                scope = holderScope,
                uid = 88L,
                usernameHint = "@Alice",
                clockMillis = { baseNowMillis + testScheduler.currentTime },
                officialTradeRepository = officialRepo,
            )
            runCurrent()

            // 1. NotConfigured initially
            holder.load(forceRefresh = false)
            advanceUntilIdle()
            assertEquals(OfficialTradeLoadStatus.NOT_CONFIGURED, holder.uiState.value.officialTradeStatus)
            assertEquals(listOf("Alice" to false), officialRepo.lookupCalls)

            // 2. Configuring key triggers automatic lookup -> Loading -> Found
            val gate = CompletableDeferred<OfficialTradeLookup>()
            officialRepo.nextLookupHandler = { _, _ -> gate.await() }
            officialRepo.saveApiKey("ntk_fake_unit_test_key_0000111122223333")
            runCurrent()
            assertEquals(OfficialTradeLoadStatus.LOADING, holder.uiState.value.officialTradeStatus)

            val sampleMatch = OfficialTradeUserStats(
                userId = 12L,
                username = "Alice",
                trades = 3,
                amount = "1688.00",
                lastCompletedAt = "2026-11-21T10:00:00Z",
                byRole = listOf(
                    OfficialTradeRoleStat(role = "buyer", trades = 2, amount = "1000.00"),
                    OfficialTradeRoleStat(role = "seller", trades = 1, amount = "688.00"),
                ),
            )
            gate.complete(
                OfficialTradeLookup.Found(
                    matches = listOf(sampleMatch),
                    fetchedAtMillis = baseNowMillis - 300_000L,
                    fromCache = true,
                ),
            )
            advanceUntilIdle()
            assertEquals(OfficialTradeLoadStatus.FOUND, holder.uiState.value.officialTradeStatus)
            assertEquals(1, holder.uiState.value.officialTradeFound?.matches?.size)

            // 3. Manual refresh -> RateLimited countdown ticks down to 0, then unlocks retry
            officialRepo.nextLookupHandler = { _, _ ->
                OfficialTradeLookup.RateLimited(retryAfterSeconds = 4L)
            }
            holder.refreshOfficialTrade()
            runCurrent()
            assertEquals(OfficialTradeLoadStatus.RATE_LIMITED, holder.uiState.value.officialTradeStatus)
            assertEquals(4, holder.uiState.value.officialTradeCooldownSeconds)
            assertEquals("Alice" to true, officialRepo.lookupCalls.last())

            val callsDuringCooldown = officialRepo.lookupCalls.size
            holder.refreshOfficialTrade()
            runCurrent()
            assertEquals(callsDuringCooldown, officialRepo.lookupCalls.size)

            advanceTimeBy(4_100L)
            runCurrent()
            assertEquals(0, holder.uiState.value.officialTradeCooldownSeconds)

            // 4. Retry -> Unauthorized, NotFound, and Failed states
            officialRepo.nextLookupHandler = { _, _ -> OfficialTradeLookup.Unauthorized }
            holder.refreshOfficialTrade()
            advanceUntilIdle()
            assertEquals(OfficialTradeLoadStatus.UNAUTHORIZED, holder.uiState.value.officialTradeStatus)

            officialRepo.nextLookupHandler = { _, _ -> OfficialTradeLookup.NotFound }
            holder.refreshOfficialTrade()
            advanceUntilIdle()
            assertEquals(OfficialTradeLoadStatus.NOT_FOUND, holder.uiState.value.officialTradeStatus)

            officialRepo.nextLookupHandler = { _, _ -> OfficialTradeLookup.Failed("连接超时") }
            holder.refreshOfficialTrade()
            advanceUntilIdle()
            assertEquals(OfficialTradeLoadStatus.FAILED, holder.uiState.value.officialTradeStatus)
        } finally {
            holderScope.cancel()
        }
    }

    @Test
    fun `stateHolder resolves username from SellerTrustReport when initial usernameHint is blank`() = runTest {
        val officialRepo = FakeOfficialTradeRepository(configured = true)
        officialRepo.nextLookupHandler = { username, _ ->
            OfficialTradeLookup.Found(
                matches = listOf(
                    OfficialTradeUserStats(
                        userId = 77L,
                        username = username,
                        trades = 5,
                        amount = "2500.00",
                        lastCompletedAt = null,
                        byRole = emptyList(),
                    ),
                ),
                fetchedAtMillis = baseNowMillis,
                fromCache = false,
            )
        }
        val sellerRepo = FakeSellerTrustRepository(resolvedUsername = "ResolvedTrader")
        val holderScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

        try {
            val holder = SellerTrustStateHolder(
                repository = sellerRepo,
                scope = holderScope,
                uid = 505L,
                usernameHint = "",
                clockMillis = { baseNowMillis },
                officialTradeRepository = officialRepo,
            )

            holder.load(forceRefresh = false)
            advanceUntilIdle()

            assertEquals(listOf("ResolvedTrader" to false), officialRepo.lookupCalls)
            assertEquals(OfficialTradeLoadStatus.FOUND, holder.uiState.value.officialTradeStatus)
            assertEquals(5, holder.uiState.value.officialTradeFound?.matches?.single()?.trades)
        } finally {
            holderScope.cancel()
        }
    }

    @Test
    fun `formatting helpers handle amounts, source cache age, role breakdown, and cached quick badge markers`() {
        assertEquals("¥1,688.00", formatOfficialTradeAmount("1688.00"))
        assertEquals("¥4,141.72", formatOfficialTradeAmount("4141.72"))
        assertEquals("¥0.00", formatOfficialTradeAmount("0"))

        assertEquals("暂无成交", formatOfficialTradeCompletedRelative(null, baseNowMillis))
        assertEquals(
            "2 小时前",
            formatOfficialTradeCompletedRelative("2026-11-21T10:00:00Z", baseNowMillis),
        )

        val cachedAttribution = formatOfficialTradeSourceAttribution(
            fetchedAtMillis = baseNowMillis - 15 * 60_000L,
            fromCache = true,
            nowEpochMillis = baseNowMillis,
        )
        assertEquals("数据来自 NodeSeek 官方交易平台 · 缓存于 15 分钟前", cachedAttribution)

        val breakdown = buildNormalizedRoleBreakdown(
            listOf(
                OfficialTradeRoleStat(role = "agent", trades = 4, amount = "3200.50"),
                OfficialTradeRoleStat(role = "buyer", trades = 1, amount = "100.00"),
            ),
        )
        assertEquals(listOf("买家", "卖家", "中介"), breakdown.map { it.roleDisplayLabel })
        assertEquals(listOf(1, 0, 4), breakdown.map { it.trades })

        // Quick badge marker: null when uncached, compact count for single match, homonym tag for duplicates
        assertNull(formatOfficialQuickBadgeMarker(null, compact = true))
        val singleFound = OfficialTradeLookup.Found(
            matches = listOf(
                OfficialTradeUserStats(
                    userId = 10L,
                    username = "Bob",
                    trades = 12,
                    amount = "800.00",
                    lastCompletedAt = null,
                    byRole = emptyList(),
                ),
            ),
            fetchedAtMillis = baseNowMillis,
            fromCache = true,
        )
        assertEquals("官·12", formatOfficialQuickBadgeMarker(singleFound, compact = true))
        assertEquals("官 12场", formatOfficialQuickBadgeMarker(singleFound, compact = false))

        val homonymFound = singleFound.copy(
            matches = listOf(
                singleFound.matches.first(),
                singleFound.matches.first().copy(userId = 99L, trades = 2),
            ),
        )
        assertEquals("官·重名", formatOfficialQuickBadgeMarker(homonymFound, compact = true))
        assertTrue(formatOfficialQuickBadgeMarker(homonymFound, compact = false)!!.contains("同名2"))
    }

    private class FakeSellerTrustRepository(
        private val resolvedUsername: String = "Alice",
    ) : SellerTrustRepository {
        override suspend fun inspectSeller(
            uid: Long,
            usernameHint: String,
            forceRefresh: Boolean,
        ): SellerTrustReport = SellerTrustReport(
            uid = uid,
            username = usernameHint.trim().removePrefix("@").ifEmpty { resolvedUsername },
            riskAssessment = TrustRiskAssessment(
                level = TrustRiskLevel.NORMAL_CLEAR,
                headline = "核验正常",
            ),
        )

        override fun observeLocalNote(uid: Long): Flow<LocalSellerNote?> = flowOf(null)
        override suspend fun saveLocalNote(uid: Long, username: String, tag: TrustTag, noteText: String) = Unit
        override suspend fun getAllNotes(): List<LocalSellerNote> = emptyList()
    }

    private class FakeOfficialTradeRepository(
        configured: Boolean,
    ) : OfficialTradeRepository {
        private val configuredFlow = MutableStateFlow(configured)
        override val isConfigured: StateFlow<Boolean> = configuredFlow.asStateFlow()

        val lookupCalls = mutableListOf<Pair<String, Boolean>>()
        var nextLookupHandler: suspend (String, Boolean) -> OfficialTradeLookup = { _, _ ->
            OfficialTradeLookup.NotFound
        }

        override suspend fun lookup(username: String, forceRefresh: Boolean): OfficialTradeLookup {
            lookupCalls += username to forceRefresh
            if (!configuredFlow.value) return OfficialTradeLookup.NotConfigured
            return nextLookupHandler(username, forceRefresh)
        }

        override suspend fun getCachedOnly(username: String): OfficialTradeLookup.Found? = null

        override suspend fun saveApiKey(key: String) {
            configuredFlow.value = key.trim().isNotEmpty()
        }

        override suspend fun clearApiKey() {
            configuredFlow.value = false
        }

        override suspend fun verifyApiKey(key: String): OfficialTradeLookup =
            OfficialTradeLookup.Found(matches = emptyList(), fetchedAtMillis = 1L, fromCache = false)

        override fun maskedApiKey(): String? = if (configuredFlow.value) "ntk_fake…3333" else null
    }
}
