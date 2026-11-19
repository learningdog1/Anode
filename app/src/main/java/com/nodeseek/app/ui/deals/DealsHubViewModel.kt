package com.nodeseek.app.ui.deals

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.domain.deals.DealFilterEngine
import com.nodeseek.app.domain.deals.DealFilterState
import com.nodeseek.app.domain.deals.DealPriceCeiling
import com.nodeseek.app.domain.deals.DealRegionPreset
import com.nodeseek.app.domain.deals.DealRoutePreset
import com.nodeseek.app.domain.deals.DealSortOption
import com.nodeseek.app.domain.deals.DealSourceTab
import com.nodeseek.app.domain.deals.DealsHubSummaryStats
import com.nodeseek.app.domain.deals.DealsRepository
import com.nodeseek.app.domain.deals.PromoDealCardModel
import com.nodeseek.app.domain.valuation.ValuationCurrency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal

@Immutable
data class DealsHubUiState(
    val allDeals: List<PromoDealCardModel> = emptyList(),
    val filteredDeals: List<PromoDealCardModel> = emptyList(),
    val filterState: DealFilterState = DealFilterState(),
    val summaryStats: DealsHubSummaryStats = DealsHubSummaryStats(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val warningMessage: String? = null,
    val fxUsdToCnyRate: BigDecimal = ValuationCurrency.USD.defaultCnyRate,
    val fxProviderDate: String? = null,
    val lastUpdatedEpochMillis: Long = 0L,
    val isFromCache: Boolean = false,
)

/**
 * ViewModel powering `DealsHubScreen` with instant cached rendering, multi-criteria filtering,
 * sorting, and live FX-backed deal pricing.
 */
class DealsHubViewModel(
    private val dealsRepository: DealsRepository,
    initialTab: DealSourceTab = DealSourceTab.ALL,
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    private data class LoadOverlayState(
        val isInitialLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val errorMessage: String? = null,
    )

    private val _filterState = MutableStateFlow(DealFilterState(sourceTab = initialTab))
    private val _loadOverlay = MutableStateFlow(LoadOverlayState())

    val uiState: StateFlow<DealsHubUiState> = combine(
        dealsRepository.dealsState,
        _filterState,
        _loadOverlay,
    ) { snapshot, filter, overlay ->
        val now = nowMillisProvider()
        val summary = DealFilterEngine.summarize(snapshot.deals)
        val filtered = DealFilterEngine.apply(
            deals = snapshot.deals,
            filter = filter,
            nowEpochMillis = now,
        )
        DealsHubUiState(
            allDeals = snapshot.deals,
            filteredDeals = filtered,
            filterState = filter,
            summaryStats = summary,
            isInitialLoading = overlay.isInitialLoading && snapshot.deals.isEmpty(),
            isRefreshing = overlay.isRefreshing,
            errorMessage = overlay.errorMessage,
            warningMessage = snapshot.warningMessage,
            fxUsdToCnyRate = snapshot.fxUsdToCnyRate,
            fxProviderDate = snapshot.fxProviderDate,
            lastUpdatedEpochMillis = snapshot.updatedAtEpochMillis,
            isFromCache = snapshot.isFromCache,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = DealsHubUiState(
            filterState = DealFilterState(sourceTab = initialTab),
            isInitialLoading = true,
        ),
    )

    init {
        viewModelScope.launch {
            val cached = dealsRepository.loadCachedDeals()
            if (cached.deals.isNotEmpty()) {
                _loadOverlay.update { it.copy(isInitialLoading = false) }
            }
            refresh(force = false)
        }
    }

    fun refresh(force: Boolean = true) {
        viewModelScope.launch {
            _loadOverlay.update {
                it.copy(
                    isRefreshing = true,
                    errorMessage = null,
                )
            }
            val result = dealsRepository.refreshDeals(force = force)
            result.fold(
                onSuccess = {
                    _loadOverlay.value = LoadOverlayState(
                        isInitialLoading = false,
                        isRefreshing = false,
                        errorMessage = null,
                    )
                },
                onFailure = { err ->
                    _loadOverlay.value = LoadOverlayState(
                        isInitialLoading = false,
                        isRefreshing = false,
                        errorMessage = err.message?.takeIf { it.isNotBlank() } ?: "大促数据加载失败，请下拉重试",
                    )
                },
            )
        }
    }

    fun selectSourceTab(tab: DealSourceTab) {
        _filterState.update { it.copy(sourceTab = tab) }
    }

    fun selectPriceCeiling(ceiling: DealPriceCeiling) {
        _filterState.update { current ->
            val next = if (current.priceCeiling == ceiling) DealPriceCeiling.NONE else ceiling
            current.copy(priceCeiling = next)
        }
    }

    fun toggleAnnualOnly() {
        _filterState.update { it.copy(annualOnly = !it.annualOnly) }
    }

    fun toggleRegionPreset(preset: DealRegionPreset) {
        _filterState.update { current ->
            val updated = current.selectedRegions.toMutableSet()
            if (!updated.add(preset)) {
                updated.remove(preset)
            }
            current.copy(selectedRegions = updated)
        }
    }

    fun toggleRoutePreset(preset: DealRoutePreset) {
        _filterState.update { current ->
            val updated = current.selectedRoutes.toMutableSet()
            if (!updated.add(preset)) {
                updated.remove(preset)
            }
            current.copy(selectedRoutes = updated)
        }
    }

    fun toggleRequirePromoCode() {
        _filterState.update { it.copy(requirePromoCode = !it.requirePromoCode) }
    }

    fun toggleRequireOrderUrl() {
        _filterState.update { it.copy(requireOrderUrl = !it.requireOrderUrl) }
    }

    fun toggleHideOutOfStock() {
        _filterState.update { it.copy(hideOutOfStock = !it.hideOutOfStock) }
    }

    fun updateKeyword(keyword: String) {
        _filterState.update { it.copy(keyword = keyword) }
    }

    fun selectSortOption(sortOption: DealSortOption) {
        _filterState.update { it.copy(sortOption = sortOption) }
    }

    fun resetFilters() {
        _filterState.update { it.resetCriteriaKeepingTabAndSort() }
    }

    fun dismissError() {
        _loadOverlay.update { it.copy(errorMessage = null) }
    }

    companion object {
        fun tabForIndex(index: Int): DealSourceTab = when (index) {
            1 -> DealSourceTab.NODESEEK
            2 -> DealSourceTab.OVERSEAS
            else -> DealSourceTab.ALL
        }

        fun factory(
            dealsRepository: DealsRepository,
            initialTab: DealSourceTab = DealSourceTab.ALL,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(DealsHubViewModel::class.java)) {
                    "Unknown ViewModel class: ${modelClass.name}"
                }
                return DealsHubViewModel(
                    dealsRepository = dealsRepository,
                    initialTab = initialTab,
                ) as T
            }
        }

        fun factory(
            dealsRepository: DealsRepository,
            initialTabIndex: Int,
        ): ViewModelProvider.Factory = factory(
            dealsRepository = dealsRepository,
            initialTab = tabForIndex(initialTabIndex),
        )
    }
}
