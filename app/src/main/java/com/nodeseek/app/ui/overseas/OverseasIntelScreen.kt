package com.nodeseek.app.ui.overseas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.data.overseas.OverseasIntelRepositoryImpl
import com.nodeseek.app.data.overseas.PreviewOverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasIntelRepository
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.theme.NodePulseTheme
import com.nodeseek.app.ui.theme.PulseThemeMode
import com.nodeseek.app.ui.theme.pulseGlassSurface

/**
 * Standalone Overseas VPS Promotions Screen (`海外情报`) powered by the LowEndTalk Promotion Monitor API.
 */
@Composable
fun OverseasIntelScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    repository: OverseasIntelRepository? = null,
    onOpenOfferDetail: ((OverseasDealOffer) -> Unit)? = null,
    onOpenUrl: ((String) -> Unit)? = null,
    onShareDealToForum: ((suggestedTitle: String, markdownBody: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val resolvedRepository = remember(repository, context) {
        repository ?: OverseasIntelRepositoryImpl(context = context)
    }
    val viewModel: OverseasIntelViewModel = viewModel(
        factory = OverseasIntelViewModel.factory(resolvedRepository),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showTranslatorSheet by rememberSaveable { mutableStateOf(false) }
    var fallbackSelectedOfferId by rememberSaveable { mutableStateOf<String?>(null) }

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
        viewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(viewModel, onShareDealToForum) {
        viewModel.repostDraftEvents.collect { draft ->
            onShareDealToForum?.invoke(draft.title, draft.markdownBody)
        }
    }

    val activeFallbackId = fallbackSelectedOfferId
    if (onOpenOfferDetail == null && activeFallbackId != null) {
        val selectedOffer = uiState.allOffers.firstOrNull { it.id == activeFallbackId }
        OverseasIntelDetailScreen(
            offerId = activeFallbackId,
            initialTitle = selectedOffer?.title.orEmpty(),
            initialSourceUrl = selectedOffer?.sourceLink.orEmpty(),
            onBack = { fallbackSelectedOfferId = null },
            modifier = modifier,
            repository = resolvedRepository,
            onOpenUrl = openLinkHandler,
            onShareDealToForum = onShareDealToForum,
        )
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { PulseSnackbarHost(hostState = snackbarHostState) },
        topBar = {
            OverseasIntelTopBar(
                isRefreshing = uiState.syncStatus.isRefreshing,
                onBack = onBack,
                onOpenSettings = {
                    viewModel.clearTranslatorTestFeedback()
                    showTranslatorSheet = true
                },
                onRefresh = viewModel::refresh,
            )
        },
    ) { innerPadding ->
        OverseasOfferFeedContent(
            uiState = uiState,
            onSearchQueryChange = viewModel::updateSearchQuery,
            onRefresh = viewModel::refresh,
            onLoadNextPage = viewModel::loadNextPage,
            onOpenDetail = { offer ->
                if (onOpenOfferDetail != null) {
                    onOpenOfferDetail(offer)
                } else {
                    fallbackSelectedOfferId = offer.id
                }
            },
            onOpenSourceLink = openLinkHandler,
            onRepostToForum = viewModel::prepareRepostToForum,
            modifier = Modifier.padding(innerPadding),
        )
    }

    if (showTranslatorSheet) {
        OverseasTranslatorSettingsSheet(
            currentConfig = uiState.translationConfig,
            isTesting = uiState.isTestingTranslator,
            testFeedback = uiState.translatorTestFeedback,
            testSuccess = uiState.translatorTestSuccess,
            showTranslateDetailButton = false,
            onDismiss = { showTranslatorSheet = false },
            onTestConnection = viewModel::testTranslationConfig,
            onClearCache = viewModel::clearTranslationCache,
            onSave = { config, _ ->
                viewModel.saveTranslationConfig(config)
            },
        )
    }
}

/**
 * Stateful embed wrapper preserved for cross-feature compatibility.
 */
@Composable
fun EmbeddedOverseasOfferFeed(
    modifier: Modifier = Modifier,
    repository: OverseasIntelRepository? = null,
    onOpenOfferDetail: ((OverseasDealOffer) -> Unit)? = null,
    onOpenUrl: ((String) -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER")
    onAddRestockWatch: ((orderUrl: String, title: String, priceLabel: String) -> Unit)? = null,
    onShareDealToForum: ((suggestedTitle: String, markdownBody: String) -> Unit)? = null,
    onFeedbackMessage: ((String) -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
) {
    val context = LocalContext.current
    val resolvedRepository = remember(repository, context) {
        repository ?: OverseasIntelRepositoryImpl(context = context)
    }
    val viewModel: OverseasIntelViewModel = viewModel(
        key = "embedded_overseas_feed",
        factory = OverseasIntelViewModel.factory(resolvedRepository),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var fallbackSelectedOfferId by rememberSaveable { mutableStateOf<String?>(null) }

    val openLinkHandler: (String) -> Unit = remember(onOpenUrl, context) {
        { url ->
            if (onOpenUrl != null) {
                onOpenUrl(url)
            } else {
                openOverseasExternalUrl(context, url)
            }
        }
    }

    LaunchedEffect(viewModel, onFeedbackMessage) {
        if (onFeedbackMessage != null) {
            viewModel.snackbarMessages.collect { msg -> onFeedbackMessage(msg) }
        }
    }

    LaunchedEffect(viewModel, onShareDealToForum) {
        viewModel.repostDraftEvents.collect { draft ->
            onShareDealToForum?.invoke(draft.title, draft.markdownBody)
        }
    }

    val activeFallbackId = fallbackSelectedOfferId
    if (onOpenOfferDetail == null && activeFallbackId != null) {
        val selectedOffer = uiState.allOffers.firstOrNull { it.id == activeFallbackId }
        OverseasIntelDetailScreen(
            offerId = activeFallbackId,
            initialTitle = selectedOffer?.title.orEmpty(),
            initialSourceUrl = selectedOffer?.sourceLink.orEmpty(),
            onBack = { fallbackSelectedOfferId = null },
            modifier = modifier,
            repository = resolvedRepository,
            onOpenUrl = openLinkHandler,
            onShareDealToForum = onShareDealToForum,
        )
        return
    }

    OverseasOfferFeedContent(
        uiState = uiState,
        onSearchQueryChange = viewModel::updateSearchQuery,
        onRefresh = viewModel::refresh,
        onLoadNextPage = viewModel::loadNextPage,
        onOpenDetail = { offer ->
            if (onOpenOfferDetail != null) {
                onOpenOfferDetail(offer)
            } else {
                fallbackSelectedOfferId = offer.id
            }
        },
        onOpenSourceLink = openLinkHandler,
        onRepostToForum = viewModel::prepareRepostToForum,
        modifier = modifier,
        contentPadding = contentPadding,
    )
}

@Composable
private fun OverseasIntelTopBar(
    isRefreshing: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
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
                text = "海外情报",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "LowEndTalk 全量促销实时监控",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = "详情翻译设置",
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
                    contentDescription = "刷新海外情报",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OverseasIntelScreenLightPreview() {
    NodePulseTheme(themeMode = PulseThemeMode.PAPER_LIGHT) {
        val repo = remember { PreviewOverseasIntelRepository() }
        OverseasIntelScreen(
            onBack = {},
            repository = repo,
            onShareDealToForum = { _, _ -> },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OverseasIntelScreenDarkPreview() {
    NodePulseTheme(themeMode = PulseThemeMode.OBSIDIAN_DARK, purePitchAmoled = true) {
        val repo = remember { PreviewOverseasIntelRepository() }
        OverseasIntelScreen(
            onBack = {},
            repository = repo,
            onShareDealToForum = { _, _ -> },
        )
    }
}
