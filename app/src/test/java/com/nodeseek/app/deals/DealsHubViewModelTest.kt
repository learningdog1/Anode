package com.nodeseek.app.deals

import com.nodeseek.app.domain.deals.DealPriceCeiling
import com.nodeseek.app.domain.deals.DealRegionPreset
import com.nodeseek.app.domain.deals.DealRoutePreset
import com.nodeseek.app.domain.deals.DealSortOption
import com.nodeseek.app.domain.deals.DealSource
import com.nodeseek.app.domain.deals.DealSourceTab
import com.nodeseek.app.domain.deals.DealTags
import com.nodeseek.app.domain.deals.DealsFeedSnapshot
import com.nodeseek.app.domain.deals.DealsRepository
import com.nodeseek.app.domain.deals.PromoDealCardModel
import com.nodeseek.app.ui.deals.DealsHubViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class DealsHubViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun viewModel_filtersSortsSwitchesTabsAndResetsCriteria() = runTest {
        val now = 1_795_096_800_000L
        val deals = listOf(
            PromoDealCardModel(
                id = "ns_1",
                source = DealSource.NODESEEK,
                topicId = 101L,
                sourceUrl = "https://www.nodeseek.com/post-101-1",
                orderUrl = "https://dmit.example/order",
                merchantName = "DMIT",
                title = "DMIT 香港 CN2 GIA $18/yr",
                annualizedUsd = BigDecimal("18.00"),
                annualizedCny = BigDecimal("130.50"),
                billingCycleLabel = "年付",
                regionTags = listOf(DealTags.REGION_HK),
                routeTags = listOf(DealTags.ROUTE_CN2_GIA),
                promoCode = "DMITBF",
                publishedAtEpochMillis = now - 1_000L,
                replyCount = 50,
            ),
            PromoDealCardModel(
                id = "let_2",
                source = DealSource.OVERSEAS_LET,
                topicId = null,
                sourceUrl = "https://lowendtalk.com/discussion/202",
                orderUrl = "https://rn.example/order",
                merchantName = "RackNerd",
                title = "RackNerd LA $10.99/yr",
                annualizedUsd = BigDecimal("10.99"),
                annualizedCny = BigDecimal("79.68"),
                billingCycleLabel = "年付",
                regionTags = listOf(DealTags.REGION_US_WEST),
                routeTags = listOf(DealTags.ROUTE_BGP),
                publishedAtEpochMillis = now - 5_000L,
                replyCount = 10,
            ),
        )

        val fakeRepo = FakeDealsRepository(
            DealsFeedSnapshot(
                deals = deals,
                updatedAtEpochMillis = now,
                fxUsdToCnyRate = BigDecimal("7.2500"),
            ),
        )

        val vm = DealsHubViewModel(
            dealsRepository = fakeRepo,
            nowMillisProvider = { now },
        )
        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isInitialLoading)
        assertEquals(2, vm.uiState.value.filteredDeals.size)
        assertEquals("let_2", vm.uiState.value.summaryStats.lowestAnnualDeal?.id)

        // Toggle ≤ $15/年 -> only RackNerd ($10.99)
        vm.selectPriceCeiling(DealPriceCeiling.UNDER_15)
        assertEquals(listOf("let_2"), vm.uiState.value.filteredDeals.map { it.id })

        // Tapping same ceiling toggles it back off
        vm.selectPriceCeiling(DealPriceCeiling.UNDER_15)
        assertEquals(2, vm.uiState.value.filteredDeals.size)

        // Toggle HK/JP + CN2 GIA -> only DMIT
        vm.toggleRegionPreset(DealRegionPreset.HK_JP)
        vm.toggleRoutePreset(DealRoutePreset.CN2_GIA)
        assertEquals(listOf("ns_1"), vm.uiState.value.filteredDeals.map { it.id })
        assertTrue(vm.uiState.value.filterState.hasActiveFilters)

        // Reset keeps tab & sort while clearing criteria
        vm.selectSortOption(DealSortOption.LOWEST_ANNUAL_PRICE)
        vm.resetFilters()
        assertFalse(vm.uiState.value.filterState.hasActiveFilters)
        assertEquals(DealSortOption.LOWEST_ANNUAL_PRICE, vm.uiState.value.filterState.sortOption)
        assertEquals(listOf("let_2", "ns_1"), vm.uiState.value.filteredDeals.map { it.id })

        // Switch tab to OVERSEAS
        vm.selectSourceTab(DealSourceTab.OVERSEAS)
        assertEquals(listOf("let_2"), vm.uiState.value.filteredDeals.map { it.id })

        collectJob.cancel()
    }

    private class FakeDealsRepository(
        private val snapshot: DealsFeedSnapshot,
    ) : DealsRepository {
        private val _state = MutableStateFlow(DealsFeedSnapshot())
        override val dealsState: StateFlow<DealsFeedSnapshot> = _state.asStateFlow()

        override suspend fun loadCachedDeals(): DealsFeedSnapshot {
            _state.value = snapshot
            return snapshot
        }

        override suspend fun refreshDeals(force: Boolean): Result<DealsFeedSnapshot> {
            _state.value = snapshot
            return Result.success(snapshot)
        }
    }
}
