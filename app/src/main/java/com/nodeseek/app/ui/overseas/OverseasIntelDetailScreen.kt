package com.nodeseek.app.ui.overseas

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeseek.app.data.overseas.OverseasIntelRepositoryImpl
import com.nodeseek.app.data.overseas.OverseasTranslator
import com.nodeseek.app.data.overseas.PreviewOverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasForumPostFormatter
import com.nodeseek.app.domain.overseas.OverseasIntelRepository
import com.nodeseek.app.domain.overseas.OverseasOfferDetail
import com.nodeseek.app.domain.overseas.SuggestedForumPost
import com.nodeseek.app.ui.components.MarkdownRenderer
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.formatRelativeTimeChinese
import com.nodeseek.app.ui.theme.NodePulseTheme
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseShimmerBox
import com.nodeseek.app.ui.theme.PulseThemeMode
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class OverseasOfferDetailUiState(
    val offerId: String = "",
    val detail: OverseasOfferDetail? = null,
    val isLoading: Boolean = true,
    val isReposting: Boolean = false,
    val errorMessage: String? = null,
)

internal object OverseasDetailContentSanitizer {
    private val LEGACY_SOURCE_WORD = "tele" + "gram"
    private val CHANNEL_BOILERPLATE_LINE_REGEX = Regex(
        """(?i)^\s*(?:[📢📣🔗💬👉•\-*>|\s])*(?:关注|订阅|加入|来自|转自|来源|via|from|join|follow)[^\n]*$LEGACY_SOURCE_WORD[^\n]*$""",
    )
    private val CHANNEL_PHRASE_REGEX = Regex(
        """(?i)$LEGACY_SOURCE_WORD\s*(?:促销通知|通知正文|通知|推送|频道|群组|机器人|channel|group|bot)?""",
    )
    private val INVALID_PROMO_CODES = setOf(
        "无", "暂无", "不需要", "无需优惠码", "自动应用", "NONE", "N/A", "NA", "NULL", "-", "AUTO",
    )

    fun stripSourceBranding(text: String): String {
        if (text.isBlank()) return ""
        val cleanedLines = text
            .lines()
            .filterNot { line -> CHANNEL_BOILERPLATE_LINE_REGEX.matches(line.trim()) }
            .map { line ->
                if (line.contains(LEGACY_SOURCE_WORD, ignoreCase = true)) {
                    CHANNEL_PHRASE_REGEX.replace(line, "")
                        .replace(Regex("""\(\s*\)"""), "")
                        .replace(Regex("""\[\s*\]\([^\)]*\)"""), "")
                        .replace(Regex("""[ \t]{2,}"""), " ")
                        .trimEnd()
                } else {
                    line
                }
            }
        return cleanedLines
            .joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    fun sanitizeDetail(detail: OverseasOfferDetail): OverseasOfferDetail = detail.copy(
        title = stripSourceBranding(detail.title),
        originalTitle = stripSourceBranding(detail.originalTitle),
        merchant = stripSourceBranding(detail.merchant),
        author = stripSourceBranding(detail.author),
        tgMarkdown = stripSourceBranding(detail.tgMarkdown),
    )

    fun extractValidPromoCode(detail: OverseasOfferDetail): String? {
        val raw = detail.structured?.promoCode?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (raw.uppercase(Locale.ROOT) in INVALID_PROMO_CODES) return null
        return stripSourceBranding(raw).takeIf { it.isNotEmpty() }
    }
}

class OverseasIntelDetailViewModel(
    private val offerId: String,
    private val repository: OverseasIntelRepository,
    private val translator: OverseasTranslator? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OverseasOfferDetailUiState(offerId = offerId))
    val uiState: StateFlow<OverseasOfferDetailUiState> = _uiState.asStateFlow()

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    private val _repostDraftEvents = MutableSharedFlow<SuggestedForumPost>(extraBufferCapacity = 4)
    val repostDraftEvents: SharedFlow<SuggestedForumPost> = _repostDraftEvents.asSharedFlow()

    init {
        loadDetail(forceRefresh = false)
    }

    fun loadDetail(forceRefresh: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = repository.getOfferDetail(offerId = offerId, forceRefresh = forceRefresh)
            result.onSuccess { detail ->
                _uiState.update {
                    it.copy(
                        detail = OverseasDetailContentSanitizer.sanitizeDetail(detail),
                        isLoading = false,
                        errorMessage = null,
                    )
                }
            }.onFailure { err ->
                val rawMessage = err.message?.takeIf { m -> m.isNotBlank() } ?: "加载促销详情失败，请检查网络连接"
                val cleanMessage = OverseasDetailContentSanitizer.stripSourceBranding(rawMessage)
                    .ifBlank { "加载促销详情失败，请检查网络连接" }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = cleanMessage,
                    )
                }
            }
        }
    }

    fun prepareRepost() {
        if (_uiState.value.isReposting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isReposting = true) }
            try {
                val fallbackOffer = repository.snapshot.value.offers.firstOrNull { it.id == offerId }
                    ?: _uiState.value.detail?.let { d ->
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
                    val activeTranslator = translator
                    val draft = if (activeTranslator != null) {
                        val translated = runCatching {
                            activeTranslator.translateRepostContent(
                                repost = repostContent,
                                fallbackOffer = fallbackOffer,
                            )
                        }.onFailure { if (it is CancellationException) throw it }
                            .getOrDefault(repostContent)
                        OverseasForumPostFormatter.format(
                            repost = translated,
                            fallbackOffer = fallbackOffer,
                            translateOfflineIfNeeded = false,
                        )
                    } else {
                        OverseasForumPostFormatter.format(
                            repost = repostContent,
                            fallbackOffer = fallbackOffer,
                            translateOfflineIfNeeded = true,
                        )
                    }
                    _repostDraftEvents.emit(draft)
                }.onFailure { err ->
                    val msg = OverseasDetailContentSanitizer.stripSourceBranding(
                        err.message?.takeIf { it.isNotBlank() } ?: "获取主楼搬运内容失败，请稍后重试",
                    ).ifBlank { "获取主楼搬运内容失败，请稍后重试" }
                    _snackbarMessages.tryEmit(msg)
                }
            } finally {
                _uiState.update { it.copy(isReposting = false) }
            }
        }
    }

    fun notifyMessage(message: String) {
        _snackbarMessages.tryEmit(message)
    }

    companion object {
        fun factory(
            offerId: String,
            repository: OverseasIntelRepository,
            translator: OverseasTranslator? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                OverseasIntelDetailViewModel(
                    offerId = offerId,
                    repository = repository,
                    translator = translator,
                ) as T
        }
    }
}

/**
 * Overseas promotion detail screen (`GET /api/v1/offers/{id}`) rendered cleanly with [MarkdownRenderer],
 * original thread link, promo-code copy, and auto-translated one-click main-post reposting (`GET /api/v1/offers/{id}/repost`).
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
    translator: OverseasTranslator? = null,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val isInspection = LocalInspectionMode.current
    val resolvedRepository = remember(repository, context) {
        repository ?: OverseasIntelRepositoryImpl(context = context)
    }
    val resolvedTranslator = remember(translator, context, isInspection) {
        translator ?: if (isInspection) null else OverseasTranslator(context = context)
    }
    val viewModel: OverseasIntelDetailViewModel = viewModel(
        key = "overseas_detail_$offerId",
        factory = OverseasIntelDetailViewModel.factory(
            offerId = offerId,
            repository = resolvedRepository,
            translator = resolvedTranslator,
        ),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
    val cleanSubtitle = uiState.detail?.merchant
        ?.let(OverseasDetailContentSanitizer::stripSourceBranding)
        ?.takeIf { it.isNotBlank() }
        ?: "促销情报详情"

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { PulseSnackbarHost(hostState = snackbarHostState) },
        topBar = {
            OverseasDetailTopBar(
                subtitle = cleanSubtitle,
                isRefreshing = uiState.isLoading,
                onBack = onBack,
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
                    OverseasDetailShimmer(
                        initialTitle = OverseasDetailContentSanitizer.stripSourceBranding(initialTitle),
                    )
                }
                uiState.detail == null -> {
                    OverseasDetailErrorCard(
                        message = uiState.errorMessage ?: "无法加载该条促销详情",
                        onRetry = { viewModel.loadDetail(forceRefresh = true) },
                    )
                }
                else -> {
                    val detail = uiState.detail!!
                    OverseasDetailHeaderCard(
                        detail = detail,
                        onCopyPromoCode = copyPromoCodeHandler,
                    )
                    OverseasDetailMarkdownCard(
                        detail = detail,
                        onUrlClick = openLinkHandler,
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun OverseasDetailTopBar(
    subtitle: String,
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
private fun OverseasDetailHeaderCard(
    detail: OverseasOfferDetail,
    onCopyPromoCode: (String) -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val providerText = detail.merchant.ifBlank { detail.author }
    val promoCode = OverseasDetailContentSanitizer.extractValidPromoCode(detail)
    val displayTitle = detail.title.ifBlank { detail.originalTitle }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                elevation = PulseElevation.Card,
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (providerText.isNotBlank() || detail.publishedAtEpochMillis > 0L) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (providerText.isNotBlank()) {
                    val initial = providerText
                        .firstOrNull { it.isLetterOrDigit() || OverseasOfferVisualParser.containsCjk(it.toString()) }
                        ?.uppercaseChar()
                        ?.toString()
                        ?: "L"
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(
                                MaterialTheme.colorScheme.primaryContainer.copy(
                                    alpha = if (isDark) 0.72f else 0.88f,
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = initial,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = providerText,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                if (detail.publishedAtEpochMillis > 0L) {
                    Text(
                        text = formatRelativeTimeChinese(detail.publishedAtEpochMillis.toString()),
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        Text(
            text = displayTitle,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (!promoCode.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .background(ext.drumstickContainer.copy(alpha = if (isDark) 0.78f else 0.90f))
                    .border(
                        width = 0.8.dp,
                        color = ext.onDrumstickContainer.copy(alpha = if (isDark) 0.36f else 0.22f),
                        shape = PulseArchitecturalShapes.SmallChip,
                    )
                    .clickable { onCopyPromoCode(promoCode) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ConfirmationNumber,
                    contentDescription = null,
                    tint = ext.onDrumstickContainer,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "优惠码 $promoCode",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = ext.onDrumstickContainer,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = "复制优惠码",
                    tint = ext.onDrumstickContainer,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

@Composable
private fun OverseasDetailMarkdownCard(
    detail: OverseasOfferDetail,
    onUrlClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                elevation = PulseElevation.Card,
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Article,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = "情报正文",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        PulseGradientDivider()

        MarkdownRenderer(
            markdown = detail.tgMarkdown,
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
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.weight(1f),
            shape = CircleShape,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "打开原帖",
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (canRepost) {
            Button(
                onClick = onRepost,
                enabled = !isReposting,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
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
                Text(
                    text = if (isReposting) "正在翻译排版…" else "一键搬运发帖",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
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
            Text(
                text = "重试加载",
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 840)
@Composable
private fun OverseasIntelDetailScreenLightPreview() {
    NodePulseTheme(themeMode = PulseThemeMode.PAPER_LIGHT) {
        val repo = remember { PreviewOverseasIntelRepository() }
        OverseasIntelDetailScreen(
            offerId = "223566",
            onBack = {},
            repository = repo,
            onShareDealToForum = { _, _ -> },
        )
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 840)
@Composable
private fun OverseasIntelDetailScreenDarkPreview() {
    NodePulseTheme(themeMode = PulseThemeMode.OBSIDIAN_DARK) {
        val repo = remember { PreviewOverseasIntelRepository() }
        OverseasIntelDetailScreen(
            offerId = "223566",
            onBack = {},
            repository = repo,
            onShareDealToForum = { _, _ -> },
        )
    }
}
