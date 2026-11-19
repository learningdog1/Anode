package com.nodeseek.app.ui.overseas

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.data.overseas.OverseasIntelRepositoryImpl
import com.nodeseek.app.data.overseas.PreviewOverseasIntelRepository
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.data.valuation.FxRefreshOutcome
import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasDisplayMode
import com.nodeseek.app.domain.overseas.OverseasForumPostFormatter
import com.nodeseek.app.domain.overseas.OverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasSource
import com.nodeseek.app.domain.overseas.OverseasSourceFilter
import com.nodeseek.app.domain.overseas.OverseasSyncStatus
import com.nodeseek.app.domain.overseas.OverseasTranslationConfig
import com.nodeseek.app.domain.overseas.SuggestedForumPost
import com.nodeseek.app.domain.valuation.ValuationCurrency
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.RoundingMode

data class OverseasIntelUiState(
    val allOffers: List<OverseasDealOffer> = emptyList(),
    val filteredOffers: List<OverseasDealOffer> = emptyList(),
    val selectedFilter: OverseasSourceFilter = OverseasSourceFilter.ALL,
    val searchQuery: String = "",
    val displayMode: OverseasDisplayMode = OverseasDisplayMode.BILINGUAL,
    val fxRatesState: FxRatesState = PreviewOverseasIntelRepository.buildDefaultFxState(),
    val translationConfig: OverseasTranslationConfig = OverseasTranslationConfig(),
    val syncStatus: OverseasSyncStatus = OverseasSyncStatus(),
    val translatingOfferIds: Set<String> = emptySet(),
    val sourceCounts: Map<OverseasSourceFilter, Int> = emptyMap(),
    val isTestingTranslator: Boolean = false,
    val translatorTestFeedback: String? = null,
    val translatorTestSuccess: Boolean? = null,
) {
    val usdRateFormatted: String
        get() = fxRatesState.rateFor(ValuationCurrency.USD)
            .setScale(2, RoundingMode.HALF_UP)
            .toPlainString()

    val eurRateFormatted: String
        get() = fxRatesState.rateFor(ValuationCurrency.EUR)
            .setScale(2, RoundingMode.HALF_UP)
            .toPlainString()
}

private data class OverseasUiControls(
    val selectedFilter: OverseasSourceFilter = OverseasSourceFilter.ALL,
    val searchQuery: String = "",
    val translatingOfferIds: Set<String> = emptySet(),
    val isTestingTranslator: Boolean = false,
    val translatorTestFeedback: String? = null,
    val translatorTestSuccess: Boolean? = null,
)

class OverseasIntelViewModel(
    private val repository: OverseasIntelRepository,
    autoLoadOnInit: Boolean = true,
) : ViewModel() {

    private val controls = MutableStateFlow(OverseasUiControls())
    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    val uiState: StateFlow<OverseasIntelUiState> = combine(
        repository.snapshot,
        controls,
    ) { snapshot, ctrl ->
        val counts = mapOf(
            OverseasSourceFilter.ALL to snapshot.offers.size,
            OverseasSourceFilter.LET to snapshot.offers.count { it.source == OverseasSource.LET },
            OverseasSourceFilter.LEB to snapshot.offers.count { it.source == OverseasSource.LEB },
            OverseasSourceFilter.BLACK_FRIDAY_FLASH to snapshot.offers.count { it.isFlashOrBlackFriday },
        )
        val filtered = filterOffers(
            offers = snapshot.offers,
            filter = ctrl.selectedFilter,
            query = ctrl.searchQuery,
        )
        OverseasIntelUiState(
            allOffers = snapshot.offers,
            filteredOffers = filtered,
            selectedFilter = ctrl.selectedFilter,
            searchQuery = ctrl.searchQuery,
            displayMode = snapshot.translationConfig.displayMode,
            fxRatesState = snapshot.fxRatesState,
            translationConfig = snapshot.translationConfig,
            syncStatus = snapshot.syncStatus,
            translatingOfferIds = ctrl.translatingOfferIds,
            sourceCounts = counts,
            isTestingTranslator = ctrl.isTestingTranslator,
            translatorTestFeedback = ctrl.translatorTestFeedback,
            translatorTestSuccess = ctrl.translatorTestSuccess,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = OverseasIntelUiState(
            allOffers = repository.snapshot.value.offers,
            filteredOffers = repository.snapshot.value.offers,
            displayMode = repository.snapshot.value.translationConfig.displayMode,
            fxRatesState = repository.snapshot.value.fxRatesState,
            translationConfig = repository.snapshot.value.translationConfig,
            syncStatus = repository.snapshot.value.syncStatus,
        ),
    )

    init {
        if (autoLoadOnInit) {
            viewModelScope.launch {
                val cached = repository.loadFromCache()
                if (cached.isEmpty()) {
                    repository.refresh(forceTranslate = false)
                } else {
                    // Refresh silently in background if cache is older than 10 minutes
                    val lastSynced = repository.snapshot.value.syncStatus.lastSyncedEpochMillis
                    if (System.currentTimeMillis() - lastSynced > STALE_REFRESH_THRESHOLD_MS) {
                        repository.refresh(forceTranslate = false)
                    }
                }
            }
        }
    }

    fun selectFilter(filter: OverseasSourceFilter) {
        controls.update { it.copy(selectedFilter = filter) }
    }

    fun updateSearchQuery(query: String) {
        controls.update { it.copy(searchQuery = query) }
    }

    fun setDisplayMode(mode: OverseasDisplayMode) {
        viewModelScope.launch {
            repository.updateDisplayMode(mode)
        }
    }

    fun cycleDisplayMode() {
        val next = when (uiState.value.displayMode) {
            OverseasDisplayMode.CHINESE -> OverseasDisplayMode.BILINGUAL
            OverseasDisplayMode.BILINGUAL -> OverseasDisplayMode.ORIGINAL
            OverseasDisplayMode.ORIGINAL -> OverseasDisplayMode.CHINESE
        }
        setDisplayMode(next)
    }

    fun refresh(forceTranslate: Boolean = false) {
        viewModelScope.launch {
            val result = repository.refresh(forceTranslate = forceTranslate)
            result.onSuccess { offers ->
                val errors = repository.snapshot.value.syncStatus.sourceErrors
                if (errors.isEmpty()) {
                    emitSnackbar("已同步 ${offers.size} 条海外 VPS 情报")
                } else {
                    emitSnackbar("已同步 ${offers.size} 条情报（部分源异常，已自动兜底）")
                }
            }.onFailure { err ->
                emitSnackbar(err.message?.takeIf { it.isNotBlank() } ?: "拉取海外情报失败，请检查网络")
            }
        }
    }

    fun refreshExchangeRates() {
        viewModelScope.launch {
            when (val outcome = repository.refreshExchangeRates()) {
                is FxRefreshOutcome.Success -> {
                    val usd = outcome.state.rateFor(ValuationCurrency.USD)
                        .setScale(2, RoundingMode.HALF_UP)
                        .toPlainString()
                    emitSnackbar("实时汇率已更新：1 USD = ¥$usd")
                }
                is FxRefreshOutcome.Fallback -> {
                    emitSnackbar(outcome.reason)
                }
            }
        }
    }

    fun translateOffer(offerId: String, forceRefresh: Boolean = true) {
        if (offerId in controls.value.translatingOfferIds) return
        viewModelScope.launch {
            controls.update { it.copy(translatingOfferIds = it.translatingOfferIds + offerId) }
            try {
                val updated = repository.translateOffer(offerId = offerId, forceRefresh = forceRefresh)
                if (updated != null) {
                    emitSnackbar("翻译完成（${updated.translationTier.label}）")
                }
            } finally {
                controls.update { it.copy(translatingOfferIds = it.translatingOfferIds - offerId) }
            }
        }
    }

    fun translateAllLoaded(forceRefresh: Boolean = true) {
        viewModelScope.launch {
            repository.translateAllLoaded(forceRefresh = forceRefresh)
            emitSnackbar("已完成当前列表翻译")
        }
    }

    fun saveTranslationConfig(
        config: OverseasTranslationConfig,
        retranslateAfterSave: Boolean = false,
    ) {
        viewModelScope.launch {
            repository.updateTranslationConfig(config)
            emitSnackbar("翻译引擎配置已保存（${config.engineKind.label}）")
            if (retranslateAfterSave) {
                repository.translateAllLoaded(forceRefresh = true)
            }
        }
    }

    fun testTranslationConfig(config: OverseasTranslationConfig) {
        if (controls.value.isTestingTranslator) return
        viewModelScope.launch {
            controls.update {
                it.copy(
                    isTestingTranslator = true,
                    translatorTestFeedback = null,
                    translatorTestSuccess = null,
                )
            }
            val result = repository.testTranslationConfig(config)
            controls.update {
                it.copy(
                    isTestingTranslator = false,
                    translatorTestFeedback = result.fold(
                        onSuccess = { msg -> msg },
                        onFailure = { err -> err.message ?: "连接测试失败" },
                    ),
                    translatorTestSuccess = result.isSuccess,
                )
            }
        }
    }

    fun clearTranslatorTestFeedback() {
        controls.update {
            it.copy(
                translatorTestFeedback = null,
                translatorTestSuccess = null,
            )
        }
    }

    fun clearTranslationCache() {
        viewModelScope.launch {
            repository.clearTranslationCache()
            emitSnackbar("已清空本地翻译缓存")
        }
    }

    fun formatForForum(offer: OverseasDealOffer): SuggestedForumPost =
        OverseasForumPostFormatter.format(
            offer = offer,
            fxRatesState = uiState.value.fxRatesState,
        )

    fun notifyMessage(message: String) {
        emitSnackbar(message)
    }

    private fun emitSnackbar(message: String) {
        _snackbarMessages.tryEmit(message)
    }

    companion object {
        private const val STALE_REFRESH_THRESHOLD_MS = 10L * 60_000L

        internal fun filterOffers(
            offers: List<OverseasDealOffer>,
            filter: OverseasSourceFilter,
            query: String,
        ): List<OverseasDealOffer> {
            val bySource = when (filter) {
                OverseasSourceFilter.ALL -> offers
                OverseasSourceFilter.LET -> offers.filter { it.source == OverseasSource.LET }
                OverseasSourceFilter.LEB -> offers.filter { it.source == OverseasSource.LEB }
                OverseasSourceFilter.BLACK_FRIDAY_FLASH -> offers.filter { it.isFlashOrBlackFriday }
            }
            val trimmedQuery = query.trim()
            if (trimmedQuery.isEmpty()) return bySource

            val tokens = trimmedQuery.split(Regex("""\s+""")).filter { it.isNotBlank() }
            return bySource.filter { offer ->
                val haystack = buildString {
                    append(offer.originalTitle).append(' ')
                    append(offer.translatedTitle.orEmpty()).append(' ')
                    append(offer.originalSummary).append(' ')
                    append(offer.translatedSummary.orEmpty()).append(' ')
                    append(offer.authorOrProvider).append(' ')
                    append(offer.specHighlights.joinToString(" ")).append(' ')
                    append(offer.couponCodes.joinToString(" ")).append(' ')
                    append(offer.priceMatches.joinToString(" ") { "${it.rawText} ${it.formattedCny}" })
                }
                tokens.all { token -> haystack.contains(token, ignoreCase = true) }
            }
        }

        fun factory(
            repository: OverseasIntelRepository,
            autoLoadOnInit: Boolean = true,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                OverseasIntelViewModel(
                    repository = repository,
                    autoLoadOnInit = autoLoadOnInit,
                ) as T
        }

        fun factory(
            context: Context,
            exchangeRateRepository: ExchangeRateRepository = ExchangeRateRepository.forContext(context),
        ): ViewModelProvider.Factory = factory(
            repository = OverseasIntelRepositoryImpl(
                context = context,
                exchangeRateRepository = exchangeRateRepository,
            ),
        )
    }
}
