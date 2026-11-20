package com.nodeseek.app.ui.overseas

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material.icons.rounded.Translate
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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.data.overseas.OverseasIntelRepositoryImpl
import com.nodeseek.app.data.overseas.OverseasTranslator
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasDisplayMode
import com.nodeseek.app.domain.overseas.OverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasIntelSnapshot
import com.nodeseek.app.domain.overseas.OverseasOfferDetail
import com.nodeseek.app.domain.overseas.OverseasRepostContent
import com.nodeseek.app.domain.overseas.OverseasSyncStatus
import com.nodeseek.app.domain.overseas.OverseasTranslationConfig
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.theme.NodePulseTheme
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseThemeMode
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
    val isInspection = LocalInspectionMode.current
    val resolvedRepository = remember(repository, context) {
        repository ?: OverseasIntelRepositoryImpl(context = context)
    }
    val resolvedTranslator = remember(context, isInspection) {
        if (isInspection) null else OverseasTranslator(context = context)
    }
    val viewModel: OverseasIntelViewModel = viewModel(
        factory = OverseasIntelViewModel.factory(
            repository = resolvedRepository,
            translator = resolvedTranslator,
        ),
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

    val copyPromoCodeHandler: (String) -> Unit = remember(context, viewModel) {
        { code ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("promo_code", code))
            viewModel.notifyMessage("已复制优惠码 $code")
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
                totalCount = uiState.syncStatus.totalCount,
                displayMode = uiState.translationConfig.displayMode,
                isTranslatingList = uiState.isTranslatingList,
                isRefreshing = uiState.syncStatus.isRefreshing,
                onBack = onBack,
                onCycleDisplayMode = viewModel::cycleDisplayMode,
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
            onCopyPromoCode = copyPromoCodeHandler,
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
            onSave = { config ->
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
    val isInspection = LocalInspectionMode.current
    val resolvedRepository = remember(repository, context) {
        repository ?: OverseasIntelRepositoryImpl(context = context)
    }
    val resolvedTranslator = remember(context, isInspection) {
        if (isInspection) null else OverseasTranslator(context = context)
    }
    val viewModel: OverseasIntelViewModel = viewModel(
        key = "embedded_overseas_feed",
        factory = OverseasIntelViewModel.factory(
            repository = resolvedRepository,
            translator = resolvedTranslator,
        ),
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

    val copyPromoCodeHandler: (String) -> Unit = remember(context, viewModel) {
        { code ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("promo_code", code))
            viewModel.notifyMessage("已复制优惠码 $code")
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
        onCopyPromoCode = copyPromoCodeHandler,
        modifier = modifier,
        contentPadding = contentPadding,
    )
}

@Composable
private fun OverseasIntelTopBar(
    totalCount: Int,
    displayMode: OverseasDisplayMode,
    isTranslatingList: Boolean,
    isRefreshing: Boolean,
    onBack: () -> Unit,
    onCycleDisplayMode: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark

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
            val subtitleText = if (totalCount > 0) {
                "LowEndTalk 实时促销 · $totalCount 条"
            } else {
                "LowEndTalk 全量促销实时监控"
            }
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodySmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(
                    if (displayMode == OverseasDisplayMode.ORIGINAL) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.78f else 0.92f)
                    },
                )
                .border(0.8.dp, ext.cardBorder, CircleShape)
                .clickable(onClick = onCycleDisplayMode)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isTranslatingList) {
                CircularProgressIndicator(
                    modifier = Modifier.size(11.dp),
                    strokeWidth = 1.6.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Translate,
                    contentDescription = null,
                    tint = if (displayMode == OverseasDisplayMode.ORIGINAL) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                text = displayMode.shortLabel,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = if (displayMode == OverseasDisplayMode.ORIGINAL) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
            )
        }

        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = "列表翻译设置",
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

private fun buildShowcasePreviewRepository(
    displayMode: OverseasDisplayMode = OverseasDisplayMode.BILINGUAL,
): OverseasIntelRepository {
    val now = System.currentTimeMillis()
    val sampleOffers = listOf(
        OverseasDealOffer(
            id = "223566",
            title = "RackNerd 北美多机房 KVM 入门与常规套餐促销",
            originalTitle = "RackNerd - Los Angeles & San Jose KVM VPS - 2 vCPU 4GB RAM 45GB NVMe 4TB@1Gbps \$18.99/yr Recurring Promo Code: FLASH2026",
            summary = "RackNerd 推出洛杉矶、圣何塞及芝加哥多机房 KVM 虚拟服务器常规特惠方案，含续费同价与大流量配置，适合轻量建站与开发测试。",
            merchant = "RackNerd",
            sourceLink = "https://lowendtalk.com/discussion/223566/racknerd-special-offers",
            publishedAtEpochMillis = now - 25L * 60_000L,
            publishedAtIso = "2026-11-20T04:10:00+00:00",
        ),
        OverseasDealOffer(
            id = "223590",
            title = "[HostPapa] Black Friday Web Hosting – · cPanel | FREE Domain Y1 | CA/US/EU",
            originalTitle = "[HostPapa] Black Friday Web Hosting – \$1.25/mo | 25 GB NVMe | cPanel | FREE Domain Y1 | CA/US/EU |",
            summary = "来自 HostPapa 的 LowEndTalk 海外服务器与 VPS 促销情报，点击查看完整方案详情。",
            merchant = "BLACK FRIDAY",
            sourceLink = "https://lowendtalk.com/discussion/223590/hostpapa-bf",
            publishedAtEpochMillis = now - 70L * 60_000L,
            publishedAtIso = "2026-11-20T03:20:00+00:00",
        ),
        OverseasDealOffer(
            id = "223512",
            title = "CloudCone 东京与法兰克福 NVMe 云主机限时闪购",
            originalTitle = "CloudCone Flash Sale - Tokyo & Frankfurt 2C/4G 40GB NVMe Unmetered €12/year",
            summary = "开放日本东京与德国法兰克福节点 NVMe 固态云主机方案，支持按需弹性部署与快照备份。",
            merchant = "CloudCone",
            sourceLink = "https://lowendtalk.com/discussion/223512/cloudcone-nvme-deals",
            publishedAtEpochMillis = now - 3L * 3_600_000L,
            publishedAtIso = "2026-11-20T01:30:00+00:00",
        ),
    )

    return object : OverseasIntelRepository {
        private val _snapshot = MutableStateFlow(
            OverseasIntelSnapshot(
                offers = sampleOffers,
                translationConfig = OverseasTranslationConfig(displayMode = displayMode),
                syncStatus = OverseasSyncStatus(
                    lastSyncedEpochMillis = now - 120_000L,
                    currentPage = 1,
                    totalCount = 252,
                    hasMore = false,
                    loadedFromCache = true,
                ),
            ),
        )
        override val snapshot: StateFlow<OverseasIntelSnapshot> = _snapshot.asStateFlow()
        override suspend fun loadFromCache() = sampleOffers
        override suspend fun refresh(query: String) = Result.success(sampleOffers)
        override suspend fun loadNextPage(query: String) = Result.success(sampleOffers)
        override suspend fun getOfferDetail(offerId: String, forceRefresh: Boolean): Result<OverseasOfferDetail> =
            Result.failure(UnsupportedOperationException("preview"))
        override suspend fun translateOfferDetail(offerId: String, forceRefresh: Boolean): Result<OverseasOfferDetail> =
            Result.failure(UnsupportedOperationException("preview"))
        override suspend fun getRepostContent(offerId: String, forceRefresh: Boolean): Result<OverseasRepostContent> =
            Result.failure(UnsupportedOperationException("preview"))
        override suspend fun updateTranslationConfig(config: OverseasTranslationConfig) {
            _snapshot.update { it.copy(translationConfig = config) }
        }
        override suspend fun updateDisplayMode(mode: OverseasDisplayMode) {
            _snapshot.update { it.copy(translationConfig = it.translationConfig.copy(displayMode = mode)) }
        }
        override suspend fun testTranslationConfig(config: OverseasTranslationConfig) = Result.success("ok")
        override suspend fun clearTranslationCache() = Unit
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 840)
@Composable
private fun OverseasIntelScreenLightPreview() {
    NodePulseTheme(themeMode = PulseThemeMode.PAPER_LIGHT) {
        val repo = remember { buildShowcasePreviewRepository() }
        OverseasIntelScreen(
            onBack = {},
            repository = repo,
            onShareDealToForum = { _, _ -> },
        )
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 840)
@Composable
private fun OverseasIntelScreenDarkPreview() {
    NodePulseTheme(themeMode = PulseThemeMode.OBSIDIAN_DARK, purePitchAmoled = false) {
        val repo = remember { buildShowcasePreviewRepository() }
        OverseasIntelScreen(
            onBack = {},
            repository = repo,
            onShareDealToForum = { _, _ -> },
        )
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 840)
@Composable
private fun OverseasIntelScreenPitchAmoledPreview() {
    NodePulseTheme(themeMode = PulseThemeMode.OBSIDIAN_DARK, purePitchAmoled = true) {
        val repo = remember { buildShowcasePreviewRepository() }
        OverseasIntelScreen(
            onBack = {},
            repository = repo,
            onShareDealToForum = { _, _ -> },
        )
    }
}
