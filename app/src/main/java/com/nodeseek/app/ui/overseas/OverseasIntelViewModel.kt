package com.nodeseek.app.ui.overseas

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nodeseek.app.data.overseas.OverseasIntelRepositoryImpl
import com.nodeseek.app.data.overseas.OverseasTranslator
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasDisplayMode
import com.nodeseek.app.domain.overseas.OverseasForumPostFormatter
import com.nodeseek.app.domain.overseas.OverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasSyncStatus
import com.nodeseek.app.domain.overseas.OverseasTranslationConfig
import com.nodeseek.app.domain.overseas.SuggestedForumPost
import com.nodeseek.app.domain.overseas.TranslationEngineKind
import com.nodeseek.app.domain.overseas.VpsGlossaryTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OverseasIntelUiState(
    val allOffers: List<OverseasDealOffer> = emptyList(),
    val filteredOffers: List<OverseasDealOffer> = emptyList(),
    val offerCards: List<OverseasOfferCardUiModel> = emptyList(),
    val searchQuery: String = "",
    val translationConfig: OverseasTranslationConfig = OverseasTranslationConfig(),
    val syncStatus: OverseasSyncStatus = OverseasSyncStatus(),
    val repostingOfferIds: Set<String> = emptySet(),
    val isTranslatingList: Boolean = false,
    val isTestingTranslator: Boolean = false,
    val translatorTestFeedback: String? = null,
    val translatorTestSuccess: Boolean? = null,
)

private data class OverseasUiControls(
    val searchQuery: String = "",
    val repostingOfferIds: Set<String> = emptySet(),
    val translatedTitles: Map<String, String> = emptyMap(),
    val isTranslatingList: Boolean = false,
    val isTestingTranslator: Boolean = false,
    val translatorTestFeedback: String? = null,
    val translatorTestSuccess: Boolean? = null,
)

private data class CardParseCacheKey(
    val id: String,
    val title: String,
    val originalTitle: String,
    val summary: String,
    val merchant: String,
    val publishedAtEpochMillis: Long,
    val displayMode: OverseasDisplayMode,
    val translatedTitle: String?,
)

class OverseasIntelViewModel(
    private val repository: OverseasIntelRepository,
    autoLoadOnInit: Boolean = true,
    private val translator: OverseasTranslator? = null,
) : ViewModel() {

    private val controls = MutableStateFlow(OverseasUiControls())
    private var listTranslationJob: Job? = null
    private var lastEngineKind: TranslationEngineKind = repository.snapshot.value.translationConfig.engineKind
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
            translatedTitles = ctrl.translatedTitles,
        )
        val displayMode = snapshot.translationConfig.displayMode
        val cards = buildCardModels(
            offers = filtered,
            displayMode = displayMode,
            translatedTitles = ctrl.translatedTitles,
        )
        OverseasIntelUiState(
            allOffers = snapshot.offers,
            filteredOffers = filtered,
            offerCards = cards,
            searchQuery = ctrl.searchQuery,
            translationConfig = snapshot.translationConfig,
            syncStatus = snapshot.syncStatus,
            repostingOfferIds = ctrl.repostingOfferIds,
            isTranslatingList = ctrl.isTranslatingList,
            isTestingTranslator = ctrl.isTestingTranslator,
            translatorTestFeedback = ctrl.translatorTestFeedback,
            translatorTestSuccess = ctrl.translatorTestSuccess,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = buildInitialUiState(),
    )

    init {
        if (translator != null) {
            viewModelScope.launch {
                repository.snapshot
                    .distinctUntilChanged { old, new ->
                        old.offers == new.offers && old.translationConfig == new.translationConfig
                    }
                    .collect { snapshot ->
                        val config = snapshot.translationConfig
                        if (config.displayMode != OverseasDisplayMode.ORIGINAL || config.autoTranslateOnRefresh) {
                            scheduleListTitleTranslation(snapshot.offers, config)
                        }
                    }
            }
        }

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
        val cards = buildCardModels(
            offers = snap.offers,
            displayMode = snap.translationConfig.displayMode,
            translatedTitles = emptyMap(),
        )
        return OverseasIntelUiState(
            allOffers = snap.offers,
            filteredOffers = snap.offers,
            offerCards = cards,
            translationConfig = snap.translationConfig,
            syncStatus = snap.syncStatus,
        )
    }

    private fun buildCardModels(
        offers: List<OverseasDealOffer>,
        displayMode: OverseasDisplayMode,
        translatedTitles: Map<String, String>,
    ): List<OverseasOfferCardUiModel> {
        if (offers.isEmpty()) return emptyList()
        if (parsedCardCache.size > MAX_PARSE_CACHE_ENTRIES) {
            parsedCardCache.clear()
        }
        return offers.map { offer ->
            val translated = translatedTitles[offer.id]
            val cacheKey = CardParseCacheKey(
                id = offer.id,
                title = offer.title,
                originalTitle = offer.originalTitle,
                summary = offer.summary,
                merchant = offer.merchant,
                publishedAtEpochMillis = offer.publishedAtEpochMillis,
                displayMode = displayMode,
                translatedTitle = translated,
            )
            parsedCardCache.getOrPut(cacheKey) {
                OverseasOfferVisualParser.parseCardModel(
                    offer = offer,
                    displayMode = displayMode,
                    translatedTitle = translated,
                )
            }
        }
    }

    private fun scheduleListTitleTranslation(
        offers: List<OverseasDealOffer>,
        config: OverseasTranslationConfig,
    ) {
        val activeTranslator = translator ?: return
        val existingTranslated = controls.value.translatedTitles
        val pendingOffers = offers
            .asSequence()
            .filter { offer ->
                offer.title.isNotBlank() &&
                    !OverseasOfferVisualParser.containsCjk(offer.title) &&
                    offer.id !in existingTranslated
            }
            .take(MAX_ONLINE_LIST_TRANSLATIONS)
            .toList()

        if (pendingOffers.isEmpty()) return

        listTranslationJob?.cancel()
        listTranslationJob = viewModelScope.launch {
            controls.update { it.copy(isTranslatingList = true) }
            try {
                val batchBuffer = LinkedHashMap<String, String>()
                for ((index, offer) in pendingOffers.withIndex()) {
                    val result = runCatching {
                        activeTranslator.translateText(
                            text = offer.title,
                            config = config,
                            forceRefresh = false,
                            preserveMarkdown = false,
                        )
                    }.onFailure { if (it is CancellationException) throw it }.getOrNull()

                    val translated = result?.translatedText?.trim().orEmpty()
                    if (translated.isNotEmpty()) {
                        batchBuffer[offer.id] = translated
                    }
                    if (batchBuffer.size >= TRANSLATION_BATCH_EMIT_SIZE || index == pendingOffers.lastIndex) {
                        if (batchBuffer.isNotEmpty()) {
                            val snapshotBatch = batchBuffer.toMap()
                            batchBuffer.clear()
                            controls.update { ctrl ->
                                ctrl.copy(translatedTitles = ctrl.translatedTitles + snapshotBatch)
                            }
                        }
                    }
                }
            } finally {
                controls.update { it.copy(isTranslatingList = false) }
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

    fun prepareRepostToForum(offer: OverseasDealOffer) {
        if (offer.id in controls.value.repostingOfferIds) return
        viewModelScope.launch {
            controls.update { it.copy(repostingOfferIds = it.repostingOfferIds + offer.id) }
            try {
                val result = repository.getRepostContent(offerId = offer.id, forceRefresh = false)
                result.onSuccess { repostContent ->
                    val draft = OverseasForumPostFormatter.format(
                        repost = repostContent,
                        fallbackOffer = offer,
                    )
                    _repostDraftEvents.emit(draft)
                }.onFailure { err ->
                    emitSnackbar(err.message?.takeIf { it.isNotBlank() } ?: "获取主楼搬运内容失败，请稍后重试")
                }
            } finally {
                controls.update { it.copy(repostingOfferIds = it.repostingOfferIds - offer.id) }
            }
        }
    }

    fun setDisplayMode(mode: OverseasDisplayMode) {
        viewModelScope.launch {
            repository.updateDisplayMode(mode)
        }
    }

    fun cycleDisplayMode() {
        val current = uiState.value.translationConfig.displayMode
        val next = when (current) {
            OverseasDisplayMode.ORIGINAL -> OverseasDisplayMode.CHINESE
            OverseasDisplayMode.CHINESE -> OverseasDisplayMode.BILINGUAL
            OverseasDisplayMode.BILINGUAL -> OverseasDisplayMode.ORIGINAL
        }
        setDisplayMode(next)
    }

    fun saveTranslationConfig(config: OverseasTranslationConfig) {
        viewModelScope.launch {
            if (config.engineKind != lastEngineKind) {
                lastEngineKind = config.engineKind
                controls.update { it.copy(translatedTitles = emptyMap()) }
            }
            repository.updateTranslationConfig(config)
            emitSnackbar("列表翻译配置已保存（${config.engineKind.label}）")
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
            listTranslationJob?.cancel()
            repository.clearTranslationCache()
            translator?.clearCache()
            controls.update { it.copy(translatedTitles = emptyMap(), isTranslatingList = false) }
            emitSnackbar("已清空本地翻译缓存")
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
        private const val MAX_ONLINE_LIST_TRANSLATIONS = 30
        private const val TRANSLATION_BATCH_EMIT_SIZE = 6
        private const val MAX_PARSE_CACHE_ENTRIES = 600

        internal fun filterOffers(
            offers: List<OverseasDealOffer>,
            query: String,
            translatedTitles: Map<String, String> = emptyMap(),
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
                    val translated = translatedTitles[offer.id]
                        ?: if (!OverseasOfferVisualParser.containsCjk(offer.title)) {
                            VpsGlossaryTranslator.translateTitleOffline(offer.title)
                        } else {
                            null
                        }
                    if (!translated.isNullOrBlank()) {
                        append(' ').append(translated)
                    }
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
