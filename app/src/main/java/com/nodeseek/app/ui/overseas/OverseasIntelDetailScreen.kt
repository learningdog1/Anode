package com.nodeseek.app.ui.overseas

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.data.overseas.OverseasIntelRepositoryImpl
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasDisplayMode
import com.nodeseek.app.domain.overseas.OverseasForumPostFormatter
import com.nodeseek.app.domain.overseas.OverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasOfferDetail
import com.nodeseek.app.domain.overseas.OverseasTranslationConfig
import com.nodeseek.app.domain.overseas.SuggestedForumPost
import com.nodeseek.app.domain.overseas.TranslationTier
import com.nodeseek.app.ui.components.MarkdownRenderer
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.formatRelativeTimeChinese
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseShimmerBox
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
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

data class OverseasOfferDetailUiState(
    val offerId: String = "",
    val detail: OverseasOfferDetail? = null,
    val isLoading: Boolean = true,
    val isTranslating: Boolean = false,
    val isReposting: Boolean = false,
    val errorMessage: String? = null,
    val displayMode: OverseasDisplayMode = OverseasDisplayMode.ORIGINAL,
    val translationConfig: OverseasTranslationConfig = OverseasTranslationConfig(),
    val isTestingTranslator: Boolean = false,
    val translatorTestFeedback: String? = null,
    val translatorTestSuccess: Boolean? = null,
)

private data class DetailLocalState(
    val detail: OverseasOfferDetail? = null,
    val isLoading: Boolean = true,
    val isTranslating: Boolean = false,
    val isReposting: Boolean = false,
    val errorMessage: String? = null,
    val isTestingTranslator: Boolean = false,
    val translatorTestFeedback: String? = null,
    val translatorTestSuccess: Boolean? = null,
)

class OverseasIntelDetailViewModel(
    private val offerId: String,
    private val repository: OverseasIntelRepository,
) : ViewModel() {

    private val localState = MutableStateFlow(DetailLocalState())

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    private val _repostDraftEvents = MutableSharedFlow<SuggestedForumPost>(extraBufferCapacity = 4)
    val repostDraftEvents: SharedFlow<SuggestedForumPost> = _repostDraftEvents.asSharedFlow()

    val uiState: StateFlow<OverseasOfferDetailUiState> = combine(
        repository.snapshot,
        localState,
    ) { snapshot, local ->
        OverseasOfferDetailUiState(
            offerId = offerId,
            detail = local.detail,
            isLoading = local.isLoading,
            isTranslating = local.isTranslating,
            isReposting = local.isReposting,
            errorMessage = local.errorMessage,
            displayMode = snapshot.translationConfig.displayMode,
            translationConfig = snapshot.translationConfig,
            isTestingTranslator = local.isTestingTranslator,
            translatorTestFeedback = local.translatorTestFeedback,
            translatorTestSuccess = local.translatorTestSuccess,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = OverseasOfferDetailUiState(offerId = offerId),
    )

    init {
        loadDetail(forceRefresh = false)
    }

    fun loadDetail(forceRefresh: Boolean) {
        viewModelScope.launch {
            localState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = repository.getOfferDetail(offerId = offerId, forceRefresh = forceRefresh)
            result.onSuccess { detail ->
                localState.update {
                    it.copy(
                        detail = detail,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
            }.onFailure { err ->
                localState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message?.takeIf { m -> m.isNotBlank() } ?: "加载促销详情失败，请检查网络连接",
                    )
                }
            }
        }
    }

    fun translateDetail(forceRefresh: Boolean = true) {
        if (localState.value.isTranslating) return
        viewModelScope.launch {
            localState.update { it.copy(isTranslating = true) }
            try {
                val result = repository.translateOfferDetail(offerId = offerId, forceRefresh = forceRefresh)
                result.onSuccess { updated ->
                    localState.update { it.copy(detail = updated) }
                    if (repository.snapshot.value.translationConfig.displayMode == OverseasDisplayMode.ORIGINAL) {
                        repository.updateDisplayMode(OverseasDisplayMode.CHINESE)
                    }
                    _snackbarMessages.tryEmit("详情翻译完成（${updated.translationTier.label}）")
                }.onFailure { err ->
                    _snackbarMessages.tryEmit(err.message?.takeIf { it.isNotBlank() } ?: "详情翻译失败")
                }
            } finally {
                localState.update { it.copy(isTranslating = false) }
            }
        }
    }

    fun cycleDisplayMode() {
        viewModelScope.launch {
            val current = uiState.value.displayMode
            val next = when (current) {
                OverseasDisplayMode.ORIGINAL -> OverseasDisplayMode.CHINESE
                OverseasDisplayMode.CHINESE -> OverseasDisplayMode.BILINGUAL
                OverseasDisplayMode.BILINGUAL -> OverseasDisplayMode.ORIGINAL
            }
            repository.updateDisplayMode(next)
            if (next != OverseasDisplayMode.ORIGINAL && localState.value.detail?.translatedMarkdown.isNullOrBlank()) {
                translateDetail(forceRefresh = false)
            }
        }
    }

    fun prepareRepost() {
        if (localState.value.isReposting) return
        viewModelScope.launch {
            localState.update { it.copy(isReposting = true) }
            try {
                val fallbackOffer = repository.snapshot.value.offers.firstOrNull { it.id == offerId }
                    ?: localState.value.detail?.let { d ->
                        OverseasDealOffer(
                            id = d.id,
                            title = d.title,
                            originalTitle = d.originalTitle,
                            summary = "",
                            merchant = d.merchant,
                            sourceLink = d.sourceLink,
                            publishedAtEpochMillis = d.publishedAtEpochMillis,
                            publishedAtIso = d.publishedAtIso,
                        )
                    }
                val result = repository.getRepostContent(offerId = offerId, forceRefresh = false)
                result.onSuccess { repostContent ->
                    val draft = OverseasForumPostFormatter.format(
                        repost = repostContent,
                        fallbackOffer = fallbackOffer,
                    )
                    _repostDraftEvents.emit(draft)
                }.onFailure { err ->
                    _snackbarMessages.tryEmit(err.message?.takeIf { it.isNotBlank() } ?: "获取主楼搬运内容失败，请稍后重试")
                }
            } finally {
                localState.update { it.copy(isReposting = false) }
            }
        }
    }

    fun saveTranslationConfig(
        config: OverseasTranslationConfig,
        retranslateAfterSave: Boolean,
    ) {
        viewModelScope.launch {
            repository.updateTranslationConfig(config)
            _snackbarMessages.tryEmit("翻译引擎配置已保存")
            if (retranslateAfterSave) {
                translateDetail(forceRefresh = true)
            }
        }
    }

    fun testTranslationConfig(config: OverseasTranslationConfig) {
        if (localState.value.isTestingTranslator) return
        viewModelScope.launch {
            localState.update {
                it.copy(
                    isTestingTranslator = true,
                    translatorTestFeedback = null,
                    translatorTestSuccess = null,
                )
            }
            val result = repository.testTranslationConfig(config)
            localState.update {
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
        localState.update {
            it.copy(
                translatorTestFeedback = null,
                translatorTestSuccess = null,
            )
        }
    }

    fun clearTranslationCache() {
        viewModelScope.launch {
            repository.clearTranslationCache()
            localState.update { state ->
                state.copy(
                    detail = state.detail?.copy(
                        translatedMarkdown = null,
                        translationTier = TranslationTier.NONE,
                    ),
                )
            }
            _snackbarMessages.tryEmit("已清空本地翻译缓存")
        }
    }

    companion object {
        fun factory(
            offerId: String,
            repository: OverseasIntelRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                OverseasIntelDetailViewModel(
                    offerId = offerId,
                    repository = repository,
                ) as T
        }
    }
}

/**
 * Detail screen presenting the full Telegram notification content (`GET /api/v1/offers/{id}`)
 * rendered cleanly with [MarkdownRenderer], optional detail translation, original post link,
 * and one-click main-post reposting (`GET /api/v1/offers/{id}/repost`).
 */
@Composable
fun OverseasIntelDetailScreen(
    offerId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialTitle: String = "",
    initialSourceUrl: String = "",
    repository: OverseasIntelRepository? = null,
    onOpenUrl: ((String) -> Unit)? = null,
    onShareDealToForum: ((suggestedTitle: String, markdownBody: String) -> Unit)? = null,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val resolvedRepository = remember(repository, context) {
        repository ?: OverseasIntelRepositoryImpl(context = context)
    }
    val viewModel: OverseasIntelDetailViewModel = viewModel(
        key = "overseas_detail_$offerId",
        factory = OverseasIntelDetailViewModel.factory(offerId, resolvedRepository),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showTranslatorSheet by rememberSaveable { mutableStateOf(false) }

    val openLinkHandler: (String) -> Unit = remember(onOpenUrl, context) {
        { url ->
            if (onOpenUrl != null) {
                onOpenUrl(url)
            } else {
                openOverseasExternalUrl(context, url)
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.snackbarMessages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(viewModel, onShareDealToForum) {
        viewModel.repostDraftEvents.collect { draft ->
            onShareDealToForum?.invoke(draft.title, draft.markdownBody)
        }
    }

    val effectiveSourceUrl = uiState.detail?.sourceLink?.takeIf { it.isNotBlank() } ?: initialSourceUrl

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { PulseSnackbarHost(hostState = snackbarHostState) },
        topBar = {
            OverseasDetailTopBar(
                subtitle = uiState.detail?.merchant?.takeIf { it.isNotBlank() } ?: "Telegram 促销通知详情",
                displayMode = uiState.displayMode,
                isRefreshing = uiState.isLoading,
                onBack = onBack,
                onCycleDisplayMode = viewModel::cycleDisplayMode,
                onOpenSettings = {
                    viewModel.clearTranslatorTestFeedback()
                    showTranslatorSheet = true
                },
                onRefresh = { viewModel.loadDetail(forceRefresh = true) },
            )
        },
        bottomBar = {
            OverseasDetailBottomBar(
                sourceUrl = effectiveSourceUrl,
                isReposting = uiState.isReposting,
                canRepost = onShareDealToForum != null,
                onOpenSource = { openLinkHandler(effectiveSourceUrl) },
                onRepost = viewModel::prepareRepost,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                uiState.isLoading && uiState.detail == null -> {
                    OverseasDetailShimmer(initialTitle = initialTitle)
                }
                uiState.detail == null -> {
                    OverseasDetailErrorCard(
                        message = uiState.errorMessage ?: "无法加载该条促销详情",
                        onRetry = { viewModel.loadDetail(forceRefresh = true) },
                    )
                }
                else -> {
                    val detail = uiState.detail!!
                    OverseasDetailHeaderCard(detail = detail)
                    OverseasDetailMarkdownCard(
                        detail = detail,
                        displayMode = uiState.displayMode,
                        isTranslating = uiState.isTranslating,
                        onTranslate = { viewModel.translateDetail(forceRefresh = true) },
                        onUrlClick = openLinkHandler,
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showTranslatorSheet) {
        OverseasTranslatorSettingsSheet(
            currentConfig = uiState.translationConfig,
            isTesting = uiState.isTestingTranslator,
            testFeedback = uiState.translatorTestFeedback,
            testSuccess = uiState.translatorTestSuccess,
            onDismiss = { showTranslatorSheet = false },
            onTestConnection = viewModel::testTranslationConfig,
            onClearCache = viewModel::clearTranslationCache,
            onSave = { config, retranslate ->
                viewModel.saveTranslationConfig(config, retranslateAfterSave = retranslate)
            },
        )
    }
}

@Composable
private fun OverseasDetailTopBar(
    subtitle: String,
    displayMode: OverseasDisplayMode,
    isRefreshing: Boolean,
    onBack: () -> Unit,
    onCycleDisplayMode: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseGlassSurface(showBottomBorder = true)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "促销详情",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(0.8.dp, ext.cardBorder, CircleShape)
                .clickable(onClick = onCycleDisplayMode)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = displayMode.shortLabel,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = "翻译设置",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        IconButton(onClick = onRefresh, enabled = !isRefreshing) {
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "刷新详情",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun OverseasDetailHeaderCard(detail: OverseasOfferDetail) {
    val providerText = detail.merchant.ifBlank { detail.author }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(shape = PulseArchitecturalShapes.EditorialCard)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = detail.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (detail.originalTitle.isNotBlank() && !detail.originalTitle.equals(detail.title, ignoreCase = true)) {
            Text(
                text = detail.originalTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (providerText.isNotBlank() || detail.publishedAtEpochMillis > 0L) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (providerText.isNotBlank()) {
                    PulseAccentPill(
                        text = providerText,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (detail.publishedAtEpochMillis > 0L) {
                    Text(
                        text = formatRelativeTimeChinese(detail.publishedAtEpochMillis.toString()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun OverseasDetailMarkdownCard(
    detail: OverseasOfferDetail,
    displayMode: OverseasDisplayMode,
    isTranslating: Boolean,
    onTranslate: () -> Unit,
    onUrlClick: (String) -> Unit,
) {
    val renderedMarkdown = remember(detail, displayMode) {
        detail.resolveDisplayMarkdown(displayMode)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(shape = PulseArchitecturalShapes.EditorialCard)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Telegram 通知正文",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
                if (detail.translationTier != TranslationTier.NONE && displayMode != OverseasDisplayMode.ORIGINAL) {
                    Text(
                        text = "· ${detail.translationTier.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(enabled = !isTranslating, onClick = onTranslate)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (isTranslating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.8.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = if (isTranslating) "翻译中…" else "翻译详情",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        PulseGradientDivider()

        MarkdownRenderer(
            markdown = renderedMarkdown,
            bodyStyle = MaterialTheme.typography.bodyMedium,
            enableTextSelection = true,
            onUrlClick = onUrlClick,
        )
    }
}

@Composable
private fun OverseasDetailBottomBar(
    sourceUrl: String,
    isReposting: Boolean,
    canRepost: Boolean,
    onOpenSource: () -> Unit,
    onRepost: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseGlassSurface(showTopBorder = true)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(
            onClick = onOpenSource,
            enabled = sourceUrl.isNotBlank(),
            modifier = Modifier.weight(1f),
            shape = CircleShape,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("打开原帖")
        }

        if (canRepost) {
            Button(
                onClick = onRepost,
                enabled = !isReposting,
                modifier = Modifier.weight(1.25f),
                shape = CircleShape,
            ) {
                if (isReposting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isReposting) "获取主楼中…" else "一键搬运发帖")
            }
        }
    }
}

@Composable
private fun OverseasDetailShimmer(initialTitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (initialTitle.isNotBlank()) {
            Text(
                text = initialTitle,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            PulseShimmerBox(modifier = Modifier.fillMaxWidth().height(24.dp))
        }
        PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.55f).height(16.dp))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PulseShimmerBox(modifier = Modifier.width(140.dp).height(18.dp))
        PulseShimmerBox(modifier = Modifier.fillMaxWidth().height(18.dp))
        PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.9f).height(18.dp))
        PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.82f).height(88.dp))
        PulseShimmerBox(modifier = Modifier.fillMaxWidth(0.75f).height(18.dp))
    }
}

@Composable
private fun OverseasDetailErrorCard(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(34.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FilledTonalButton(
            onClick = onRetry,
            shape = CircleShape,
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("重试加载")
        }
    }
}
