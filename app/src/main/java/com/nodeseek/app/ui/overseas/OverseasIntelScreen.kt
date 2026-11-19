package com.nodeseek.app.ui.overseas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.data.overseas.OverseasIntelRepositoryImpl
import com.nodeseek.app.data.overseas.PreviewOverseasIntelRepository
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.domain.overseas.OverseasDisplayMode
import com.nodeseek.app.domain.overseas.OverseasIntelRepository
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.theme.NodePulseTheme
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseThemeMode
import com.nodeseek.app.ui.theme.pulseGlassSurface

/**
 * Standalone Overseas VPS Intelligence Aggregator Screen (`海外情报聚合`).
 *
 * Cross-feature callbacks:
 * - [onAddRestockWatch]: plugs straight into Feature 1 (`AddRestockWatchBottomSheet` / `RestockMonitor`)
 * - [onShareDealToForum]: prefills `TopicComposerScreen` with formatted Chinese + source-credited Markdown
 * - [onOpenUrl]: opens external original post or direct merchant checkout URL (defaults to browser Intent)
 */
@Composable
fun OverseasIntelScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    repository: OverseasIntelRepository? = null,
    onOpenUrl: ((String) -> Unit)? = null,
    onAddRestockWatch: ((orderUrl: String, title: String, priceLabel: String) -> Unit)? = null,
    onShareDealToForum: ((suggestedTitle: String, markdownBody: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val resolvedRepository = remember(repository, context) {
        repository ?: OverseasIntelRepositoryImpl(
            context = context,
            exchangeRateRepository = ExchangeRateRepository.forContext(context),
        )
    }
    val viewModel: OverseasIntelViewModel = viewModel(
        factory = OverseasIntelViewModel.factory(resolvedRepository),
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
        viewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { PulseSnackbarHost(hostState = snackbarHostState) },
        topBar = {
            OverseasIntelTopBar(
                displayMode = uiState.displayMode,
                isRefreshing = uiState.syncStatus.isRefreshing,
                onBack = onBack,
                onCycleDisplayMode = viewModel::cycleDisplayMode,
                onOpenSettings = {
                    viewModel.clearTranslatorTestFeedback()
                    showTranslatorSheet = true
                },
                onRefresh = { viewModel.refresh(forceTranslate = false) },
            )
        },
    ) { innerPadding ->
        OverseasOfferFeedContent(
            uiState = uiState,
            onSelectFilter = viewModel::selectFilter,
            onSearchQueryChange = viewModel::updateSearchQuery,
            onSelectDisplayMode = viewModel::setDisplayMode,
            onRefresh = { viewModel.refresh(forceTranslate = false) },
            onRefreshFxRates = viewModel::refreshExchangeRates,
            onOpenTranslatorSettings = {
                viewModel.clearTranslatorTestFeedback()
                showTranslatorSheet = true
            },
            onTranslateOffer = { offerId -> viewModel.translateOffer(offerId, forceRefresh = true) },
            onCopyCoupon = { code ->
                copyOverseasCouponToClipboard(context, code)
                viewModel.notifyMessage("已复制优惠码：$code")
            },
            onOpenSourceLink = openLinkHandler,
            onOpenOrderLink = openLinkHandler,
            onAddRestockWatch = onAddRestockWatch,
            onShareDealToForum = onShareDealToForum,
            modifier = Modifier.padding(innerPadding),
        )
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

/**
 * Drop-in stateful embed wrapper so `DealsHubScreen` (Feature 2) can render the complete
 * LET/LEB translated overseas deals tab with minimal boilerplate.
 */
@Composable
fun EmbeddedOverseasOfferFeed(
    modifier: Modifier = Modifier,
    repository: OverseasIntelRepository? = null,
    onOpenUrl: ((String) -> Unit)? = null,
    onAddRestockWatch: ((orderUrl: String, title: String, priceLabel: String) -> Unit)? = null,
    onShareDealToForum: ((suggestedTitle: String, markdownBody: String) -> Unit)? = null,
    onFeedbackMessage: ((String) -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
) {
    val context = LocalContext.current
    val resolvedRepository = remember(repository, context) {
        repository ?: OverseasIntelRepositoryImpl(
            context = context,
            exchangeRateRepository = ExchangeRateRepository.forContext(context),
        )
    }
    val viewModel: OverseasIntelViewModel = viewModel(
        key = "embedded_overseas_feed",
        factory = OverseasIntelViewModel.factory(resolvedRepository),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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

    LaunchedEffect(viewModel, onFeedbackMessage) {
        if (onFeedbackMessage != null) {
            viewModel.snackbarMessages.collect { msg -> onFeedbackMessage(msg) }
        }
    }

    OverseasOfferFeedContent(
        uiState = uiState,
        onSelectFilter = viewModel::selectFilter,
        onSearchQueryChange = viewModel::updateSearchQuery,
        onSelectDisplayMode = viewModel::setDisplayMode,
        onRefresh = { viewModel.refresh(forceTranslate = false) },
        onRefreshFxRates = viewModel::refreshExchangeRates,
        onOpenTranslatorSettings = {
            viewModel.clearTranslatorTestFeedback()
            showTranslatorSheet = true
        },
        onTranslateOffer = { offerId -> viewModel.translateOffer(offerId, forceRefresh = true) },
        onCopyCoupon = { code ->
            copyOverseasCouponToClipboard(context, code)
            viewModel.notifyMessage("已复制优惠码：$code")
        },
        onOpenSourceLink = openLinkHandler,
        onOpenOrderLink = openLinkHandler,
        onAddRestockWatch = onAddRestockWatch,
        onShareDealToForum = onShareDealToForum,
        modifier = modifier,
        contentPadding = contentPadding,
    )

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
private fun OverseasIntelTopBar(
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
                text = "海外情报聚合",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "LET · LEB 实时搬运与人民币折算",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Language cycle pill (译 / 双语 / EN)
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
            onAddRestockWatch = { _, _, _ -> },
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
            onAddRestockWatch = { _, _, _ -> },
            onShareDealToForum = { _, _ -> },
        )
    }
}
