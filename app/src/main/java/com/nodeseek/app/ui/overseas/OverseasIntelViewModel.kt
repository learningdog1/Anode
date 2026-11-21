package com.nodeseek.app.ui.overseas

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.data.overseas.OverseasIntelRepositoryImpl
import com.nodeseek.app.data.overseas.OverseasTranslator
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasForumPostFormatter
import com.nodeseek.app.domain.overseas.OverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasSyncStatus
import com.nodeseek.app.domain.overseas.SuggestedForumPost
import kotlinx.coroutines.CancellationException
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

data class OverseasIntelUiState(
    val allOffers: List<OverseasDealOffer> = emptyList(),
    val filteredOffers: List<OverseasDealOffer> = emptyList(),
    val offerCards: List<OverseasOfferCardUiModel> = emptyList(),
    val searchQuery: String = "",
    val syncStatus: OverseasSyncStatus = OverseasSyncStatus(),
    val repostingOfferIds: Set<String> = emptySet(),
)

private data class OverseasUiControls(
    val searchQuery: String = "",
    val repostingOfferIds: Set<String> = emptySet(),
)

private data class CardParseCacheKey(
    val id: String,
    val title: String,
    val originalTitle: String,
    val summary: String,
    val merchant: String,
    val publishedAtEpochMillis: Long,
)

class OverseasIntelViewModel(
    private val repository: OverseasIntelRepository,
    autoLoadOnInit: Boolean = true,
    private val translator: OverseasTranslator? = null,
) : ViewModel() {

    private val controls = MutableStateFlow(OverseasUiControls())
    private val parsedCardCache = HashMap<CardParseCacheKey, OverseasOfferCardUiModel>()

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    private val _repostDraftEvents = MutableSharedFlow<SuggestedForumPost>(extraBufferCapacity = 4)
    val repostDraftEvents: SharedFlow<SuggestedForumPost> = _repostDraftEvents.asSharedFlow()

    val uiState: StateFlow<OverseasIntelUiState> = combine(
        repository.snapshot,
        controls,
    ) { snapshot, ctrl ->
        val filtered = filterOffers(
            offers = snapshot.offers,
            query = ctrl.searchQuery,
        )
        val cards = buildCardModels(filtered)
        OverseasIntelUiState(
            allOffers = snapshot.offers,
            filteredOffers = filtered,
            offerCards = cards,
            searchQuery = ctrl.searchQuery,
            syncStatus = snapshot.syncStatus,
            repostingOfferIds = ctrl.repostingOfferIds,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = buildInitialUiState(),
    )

    init {
        if (autoLoadOnInit) {
            viewModelScope.launch {
                val cached = repository.loadFromCache()
                if (cached.isEmpty()) {
                    repository.refresh()
                } else {
                    val lastSynced = repository.snapshot.value.syncStatus.lastSyncedEpochMillis
                    if (System.currentTimeMillis() - lastSynced > STALE_REFRESH_THRESHOLD_MS) {
                        repository.refresh()
                    }
                }
            }
        }
    }

    private fun buildInitialUiState(): OverseasIntelUiState {
        val snap = repository.snapshot.value
        val cards = buildCardModels(snap.offers)
        return OverseasIntelUiState(
            allOffers = snap.offers,
            filteredOffers = snap.offers,
            offerCards = cards,
            syncStatus = snap.syncStatus,
        )
    }

    private fun buildCardModels(
        offers: List<OverseasDealOffer>,
    ): List<OverseasOfferCardUiModel> {
        if (offers.isEmpty()) return emptyList()
        if (parsedCardCache.size > MAX_PARSE_CACHE_ENTRIES) {
            parsedCardCache.clear()
        }
        return offers.map { offer ->
            val cacheKey = CardParseCacheKey(
                id = offer.id,
                title = offer.title,
                originalTitle = offer.originalTitle,
                summary = offer.summary,
                merchant = offer.merchant,
                publishedAtEpochMillis = offer.publishedAtEpochMillis,
            )
            parsedCardCache.getOrPut(cacheKey) {
                OverseasOfferVisualParser.parseCardModel(offer = offer)
            }
        }
    }

    fun updateSearchQuery(query: String) {
        controls.update { it.copy(searchQuery = query) }
    }

    fun refresh() {
        viewModelScope.launch {
            val result = repository.refresh()
            result.onSuccess { offers ->
                emitSnackbar("已同步 ${offers.size} 条海外促销情报")
            }.onFailure { err ->
                val hasCache = repository.snapshot.value.offers.isNotEmpty()
                val reason = err.message?.takeIf { it.isNotBlank() } ?: "拉取海外情报失败，请检查网络"
                emitSnackbar(if (hasCache) "$reason，已展示缓存内容" else reason)
            }
        }
    }

    fun loadNextPage() {
        val state = uiState.value
        if (state.searchQuery.isNotBlank()) return
        if (state.syncStatus.isRefreshing || state.syncStatus.isLoadingMore || !state.syncStatus.hasMore) return
        viewModelScope.launch {
            repository.loadNextPage()
                .onFailure { err ->
                    emitSnackbar(err.message?.takeIf { it.isNotBlank() } ?: "加载下一页失败")
                }
        }
    }

    /**
     * Fetches the OP main-post content (`GET /api/v1/offers/{id}/repost`), automatically translates
     * English titles and main-post Markdown into idiomatic Chinese, and formats a clean NodeSeek draft.
     */
    fun prepareRepostToForum(offer: OverseasDealOffer) {
        if (offer.id in controls.value.repostingOfferIds) return
        viewModelScope.launch {
            controls.update { it.copy(repostingOfferIds = it.repostingOfferIds + offer.id) }
            try {
                val result = repository.getRepostContent(offerId = offer.id, forceRefresh = false)
                result.onSuccess { repostContent ->
                    val activeTranslator = translator
                    val draft = if (activeTranslator != null) {
                        val translated = runCatching {
                            activeTranslator.translateRepostContent(
                                repost = repostContent,
                                fallbackOffer = offer,
                                config = repository.snapshot.value.translationConfig,
                            )
                        }.onFailure { if (it is CancellationException) throw it }
                            .getOrDefault(repostContent)
                        OverseasForumPostFormatter.format(
                            repost = translated,
                            fallbackOffer = offer,
                            translateOfflineIfNeeded = false,
                        )
                    } else {
                        OverseasForumPostFormatter.format(
                            repost = repostContent,
                            fallbackOffer = offer,
                            translateOfflineIfNeeded = true,
                        )
                    }
                    _repostDraftEvents.emit(draft)
                }.onFailure { err ->
                    emitSnackbar(err.message?.takeIf { it.isNotBlank() } ?: "获取主楼搬运内容失败，请稍后重试")
                }
            } finally {
                controls.update { it.copy(repostingOfferIds = it.repostingOfferIds - offer.id) }
            }
        }
    }

    fun notifyMessage(message: String) {
        emitSnackbar(message)
    }

    private fun emitSnackbar(message: String) {
        _snackbarMessages.tryEmit(message)
    }

    companion object {
        private const val STALE_REFRESH_THRESHOLD_MS = 10L * 60_000L
        private const val MAX_PARSE_CACHE_ENTRIES = 600

        internal fun filterOffers(
            offers: List<OverseasDealOffer>,
            query: String,
        ): List<OverseasDealOffer> {
            val trimmedQuery = query.trim()
            if (trimmedQuery.isEmpty()) return offers

            val tokens = trimmedQuery.split(Regex("""\s+""")).filter { it.isNotBlank() }
            return offers.filter { offer ->
                val haystack = buildString {
                    append(offer.title).append(' ')
                    append(offer.originalTitle).append(' ')
                    append(offer.summary).append(' ')
                    append(offer.merchant)
                }
                tokens.all { token -> haystack.contains(token, ignoreCase = true) }
            }
        }

        fun factory(
            repository: OverseasIntelRepository,
            autoLoadOnInit: Boolean = true,
            translator: OverseasTranslator? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                OverseasIntelViewModel(
                    repository = repository,
                    autoLoadOnInit = autoLoadOnInit,
                    translator = translator,
                ) as T
        }

        fun factory(context: Context): ViewModelProvider.Factory {
            val appCtx = context.applicationContext ?: context
            return factory(
                repository = OverseasIntelRepositoryImpl(context = appCtx),
                translator = OverseasTranslator(appCtx),
            )
        }
    }
}
