package com.nodeseek.app.ui.overseas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseThemeMode
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

const val OVERSEAS_MONITOR_TG_CHANNEL_URL = "https://t.me/colmidad_lowendtalk_offer"

/**
 * Standalone Overseas VPS Promotions Screen (`海外情报`) powered by the LowEndTalk Promotion Monitor API.
 *
 * Browsing is intentionally clean and free of any translation controls or extracted spec/price tags,
 * while one-click reposting (`一键搬贴`) automatically translates and formats OP posts for NodeSeek.
 */
@Composable
fun OverseasIntelScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    repository: OverseasIntelRepository? = null,
    onOpenOfferDetail: ((OverseasDealOffer) -> Unit)? = null,
    onOpenUrl: ((String) -> Unit)? = null,
    onShareDealToForum: ((suggestedTitle: String, markdownBody: String) -> Unit)? = null,
    promoNotificationsEnabled: Boolean = false,
    onTogglePromoNotifications: ((Boolean) -> Unit)? = null,
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
    var fallbackSelectedOfferId by rememberSaveable { mutableStateOf<String?>(null) }
    var localFallbackPromoEnabled by rememberSaveable(promoNotificationsEnabled) {
        mutableStateOf(promoNotificationsEnabled)
    }

    val effectivePromoEnabled = if (onTogglePromoNotifications != null) {
        promoNotificationsEnabled
    } else {
        localFallbackPromoEnabled
    }

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
            translator = resolvedTranslator,
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
                isRefreshing = uiState.syncStatus.isRefreshing,
                onBack = onBack,
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
            headerContent = {
                OverseasPromoControlCard(
                    promoNotificationsEnabled = effectivePromoEnabled,
                    onTogglePromoNotifications = { enabled ->
                        if (onTogglePromoNotifications != null) {
                            onTogglePromoNotifications(enabled)
                        } else {
                            localFallbackPromoEnabled = enabled
                        }
                    },
                    onFollowMonitorChannel = {
                        openLinkHandler(OVERSEAS_MONITOR_TG_CHANNEL_URL)
                    },
                )
            },
            modifier = Modifier.padding(innerPadding),
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
            translator = resolvedTranslator,
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
private fun OverseasPromoControlCard(
    promoNotificationsEnabled: Boolean,
    onTogglePromoNotifications: (Boolean) -> Unit,
    onFollowMonitorChannel: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                elevation = PulseElevation.Card,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (promoNotificationsEnabled) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (promoNotificationsEnabled) {
                        Icons.Rounded.NotificationsActive
                    } else {
                        Icons.Rounded.NotificationsNone
                    },
                    contentDescription = null,
                    tint = if (promoNotificationsEnabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "促销推送通知",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (promoNotificationsEnabled) {
                        "已开启 · 发现新促销贴将第一时间主动通知"
                    } else {
                        "开启后无需打开页面，新促销贴出现即主动通知"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Switch(
                checked = promoNotificationsEnabled,
                onCheckedChange = onTogglePromoNotifications,
            )
        }

        PulseGradientDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(
                    MaterialTheme.colorScheme.primaryContainer.copy(
                        alpha = if (isDark) 0.68f else 0.86f,
                    ),
                )
                .border(
                    width = 0.8.dp,
                    color = MaterialTheme.colorScheme.primary.copy(
                        alpha = if (isDark) 0.34f else 0.22f,
                    ),
                    shape = CircleShape,
                )
                .pulseBouncyClickable(onClick = onFollowMonitorChannel)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Send,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "关注监控 TG 频道",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun OverseasIntelTopBar(
    totalCount: Int,
    isRefreshing: Boolean,
    onBack: () -> Unit,
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

private fun buildShowcasePreviewRepository(): OverseasIntelRepository {
    val now = System.currentTimeMillis()
    val sampleOffers = listOf(
        OverseasDealOffer(
            id = "223566",
            title = "RackNerd - Los Angeles & San Jose KVM VPS - 2 vCPU 4GB RAM 45GB NVMe $18.99/yr",
            originalTitle = "RackNerd - Los Angeles & San Jose KVM VPS - 2 vCPU 4GB RAM 45GB NVMe $18.99/yr",
            summary = "RackNerd 推出洛杉矶、圣何塞及芝加哥多机房 KVM 虚拟服务器常规特惠方案，适合轻量建站与开发测试。",
            merchant = "RackNerd",
            sourceLink = "https://lowendtalk.com/discussion/223566/racknerd-special-offers",
            publishedAtEpochMillis = now - 25L * 60_000L,
            publishedAtIso = "2026-11-20T04:10:00+00:00",
        ),
        OverseasDealOffer(
            id = "223590",
            title = "[HostPapa] Black Friday Web Hosting – $1.25/mo | 25 GB NVMe | cPanel | FREE Domain Y1",
            originalTitle = "[HostPapa] Black Friday Web Hosting – $1.25/mo | 25 GB NVMe | cPanel | FREE Domain Y1",
            summary = "来自 HostPapa 的 LowEndTalk 海外服务器与 VPS 促销情报，点击查看完整方案详情。",
            merchant = "BLACK FRIDAY",
            sourceLink = "https://lowendtalk.com/discussion/223590/hostpapa-bf",
            publishedAtEpochMillis = now - 70L * 60_000L,
            publishedAtIso = "2026-11-20T03:20:00+00:00",
        ),
        OverseasDealOffer(
            id = "223512",
            title = "CloudCone Flash Sale - Tokyo & Frankfurt 2C/4G 40GB NVMe Unmetered €12/year",
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
                translationConfig = OverseasTranslationConfig(),
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
            promoNotificationsEnabled = true,
            onTogglePromoNotifications = {},
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
            promoNotificationsEnabled = false,
            onTogglePromoNotifications = {},
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
            promoNotificationsEnabled = true,
            onTogglePromoNotifications = {},
            onShareDealToForum = { _, _ -> },
        )
    }
}
