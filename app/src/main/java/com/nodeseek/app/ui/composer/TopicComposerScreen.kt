@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.composer

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.navigation.openNodeSeekOrExternalUrl
import com.nodeseek.app.ui.components.AccessLockChip
import com.nodeseek.app.ui.components.CategoryMicroTag
import com.nodeseek.app.ui.components.MarkdownRenderer
import com.nodeseek.app.ui.components.NodeSeekStickerCatalog
import com.nodeseek.app.ui.components.OpHighlightChip
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.components.PulseAvatar
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.PulseSnackbarHost
import com.nodeseek.app.ui.components.RankMicroBadge
import com.nodeseek.app.ui.components.SortSegmentItem
import com.nodeseek.app.ui.components.SortSegmentedSwitcher
import com.nodeseek.app.ui.components.WebViewSessionSheet
import com.nodeseek.app.ui.netbench.NetBenchQuickBottomSheet
import com.nodeseek.app.ui.netbench.insertNetBenchMarkdownAtCursor
import com.nodeseek.app.ui.ssh.SshQuickInsertSheet
import com.nodeseek.app.ui.valuation.VpsValuationBottomSheet
import com.nodeseek.app.ui.valuation.insertMarkdownBlockAtCursor
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseStaggeredEntrance
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.pulseHeroCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TABS_MARKDOWN_TEMPLATE = """
:::: tabs
::: tab-item 基础信息 / YABS
在此粘贴硬件配置或跑分结果
:::
::: tab-item 三网回程 / 网络实测
在此粘贴 MTR 路由或流媒体解锁结果
:::
::::
"""

/**
 * NodePulse Markdown Topic Composer Screen (`TopicComposerScreen`).
 *
 * Supports both:
 * 1. **New Topic** (`editTopicId == null`) — Board selector sheet (excluding `adminOnly` boards,
 *    with `5 鸡腿` fee notice on `inside`), View Permission selector (`Lv.0`..`Lv.6`, `Lv.255`),
 *    Markdown toolbar, `NodeSeekStickerCatalog` picker, `PickVisualMedia` NodeImage upload,
 *    live `编辑 / 预览` switcher, and `DraftRepository` auto-save.
 * 2. **Edit Topic** (`editTopicId != null`) — Pre-loads Floor 0 title/markdown/level, locks category
 *    per NodeSeek protocol rule, and confirms `10 鸡腿` fee before changing to `Lv.255` Private.
 */
@Composable
fun TopicComposerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    editTopicId: Long? = null,
    initialCategorySlug: String = "daily",
    initialTitle: String = "",
    initialMarkdown: String = "",
    prefillKey: String = "",
    onTopicPublished: (editedTopicId: Long?) -> Unit = { onBack() },
    onRequestWebViewAuth: ((url: String) -> Unit)? = null,
    onOpenFullNetBench: ((initialTarget: String) -> Unit)? = null,
    onOpenFullLiteSsh: (() -> Unit)? = null,
    previewUiState: TopicComposerUiState? = null,
    viewModel: TopicComposerViewModel = viewModel(
        key = "topic_composer_${editTopicId ?: "new"}_${initialCategorySlug}_${prefillKey.ifBlank { "${initialTitle.hashCode()}_${initialMarkdown.hashCode()}" }}",
        factory = TopicComposerViewModel.factory(
            container = LocalAppContainer.current,
            editTopicId = editTopicId,
            initialCategorySlug = initialCategorySlug,
            initialTitle = initialTitle,
            initialMarkdown = initialMarkdown,
        ),
    ),
) {
    val appContainer = LocalAppContainer.current
    val observedUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = previewUiState ?: observedUiState
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var fallbackWebViewUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var showValuationSheet by rememberSaveable { mutableStateOf(false) }
    var showNetBenchSheet by rememberSaveable { mutableStateOf(false) }
    var showSshSheet by rememberSaveable { mutableStateOf(false) }

    var bodyFieldValue by remember(uiState.editorRevision) {
        mutableStateOf(
            TextFieldValue(
                text = uiState.markdownContent,
                selection = TextRange(uiState.markdownContent.length),
            )
        )
    }

    val triggerWebViewAuth: (String) -> Unit = remember(onRequestWebViewAuth) {
        { url ->
            if (onRequestWebViewAuth != null) {
                onRequestWebViewAuth(url)
            } else {
                fallbackWebViewUrl = url
            }
        }
    }

    val applyEditorTransform: ((TextFieldValue) -> TextFieldValue) -> Unit = { transform ->
        val next = transform(bodyFieldValue)
        bodyFieldValue = next
        viewModel.onMarkdownChanged(next.text)
    }
    val currentApplyEditorTransform by rememberUpdatedState(applyEditorTransform)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TopicComposerUiEvent.ShowMessage -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is TopicComposerUiEvent.PublishedSuccess -> {
                    onTopicPublished(event.editedTopicId)
                }
                is TopicComposerUiEvent.RequestWebViewAuth -> {
                    triggerWebViewAuth(event.url)
                }
                is TopicComposerUiEvent.InsertUploadedMarkdown -> {
                    currentApplyEditorTransform { current ->
                        insertComposerMarkdown(current, prefix = event.snippet)
                    }
                }
            }
        }
    }

    val handlePickedImageUri: (Uri?) -> Unit = { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val payload = withContext(Dispatchers.IO) {
                    readPickedImagePayload(context = context, uri = uri)
                }
                if (payload != null) {
                    viewModel.onPhotoBytesSelected(
                        bytes = payload.bytes,
                        mimeType = payload.mimeType,
                        fileName = payload.fileName,
                    )
                } else {
                    viewModel.reportPhotoReadError("无法读取所选图片，请确认文件权限或更换格式")
                }
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = handlePickedImageUri,
    )
    val fallbackDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = handlePickedImageUri,
    )

    val showBottomBar = !uiState.isLoadingInitialTopic &&
        uiState.loadError == null &&
        uiState.activeTab == ComposerModeTab.WRITE

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ComposerTopBar(
                isEditMode = uiState.isEditMode,
                activeTab = uiState.activeTab,
                canSubmit = uiState.canSubmit,
                isSubmitting = uiState.isSubmitting,
                categorySlug = uiState.selectedCategorySlug,
                onBack = onBack,
                onSelectTab = viewModel::setActiveTab,
                onPublish = viewModel::onPublishClicked,
            )
        },
        bottomBar = {
            if (showBottomBar) {
                ComposerBottomFormattingBar(
                    isUploadingImage = uiState.isUploadingImage,
                    categorySlug = uiState.selectedCategorySlug,
                    onApplyTransform = applyEditorTransform,
                    onOpenValuationCalculator = { showValuationSheet = true },
                    onOpenNetBench = { showNetBenchSheet = true },
                    onOpenSshInsert = { showSshSheet = true },
                    onOpenStickers = { viewModel.setStickerSheetVisible(true) },
                    onPickPhoto = {
                        try {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        } catch (_: ActivityNotFoundException) {
                            fallbackDocumentLauncher.launch(arrayOf("image/*"))
                        }
                    },
                )
            }
        },
        snackbarHost = {
            PulseSnackbarHost(
                hostState = snackbarHostState,
                modifier = if (showBottomBar) Modifier else Modifier.navigationBarsPadding(),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                uiState.isLoadingInitialTopic -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "正在加载原帖 Markdown 内容…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                uiState.loadError != null -> {
                    ComposerLoadErrorCard(
                        message = uiState.loadError!!,
                        onRetry = { viewModel.loadTopicForEditing() },
                        onLogin = { triggerWebViewAuth("https://www.nodeseek.com/signIn.html") },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                else -> {
                    if (uiState.activeTab == ComposerModeTab.WRITE) {
                        ComposerWriteSurface(
                            uiState = uiState,
                            bodyFieldValue = bodyFieldValue,
                            onTitleChanged = viewModel::onTitleChanged,
                            onBodyValueChanged = { next ->
                                bodyFieldValue = next
                                viewModel.onMarkdownChanged(next.text)
                            },
                            onOpenBoardSheet = { viewModel.setBoardSheetVisible(true) },
                            onOpenPermissionSheet = { viewModel.setPermissionSheetVisible(true) },
                            onClearOrResetDraft = viewModel::clearDraftOrReset,
                            onRequestSignIn = {
                                triggerWebViewAuth("https://www.nodeseek.com/signIn.html")
                            },
                        )
                    } else {
                        ComposerLivePreviewSurface(
                            uiState = uiState,
                            markdownText = bodyFieldValue.text,
                        )
                    }
                }
            }
        }
    }

    // ── Bottom Sheets & Dialogs ───────────────────────────────────────────────

    if (uiState.showBoardSheet && !uiState.isEditMode) {
        BoardSelectorBottomSheet(
            boards = uiState.availableBoards,
            selectedSlug = uiState.selectedCategorySlug,
            viewerCoins = uiState.session?.drumsticks,
            onSelectBoard = viewModel::selectCategory,
            onDismiss = { viewModel.setBoardSheetVisible(false) },
        )
    }

    if (uiState.showPermissionSheet) {
        ViewPermissionBottomSheet(
            selectedLevel = uiState.requiredViewLevel,
            isEditMode = uiState.isEditMode,
            originalRequiredViewLevel = uiState.originalRequiredViewLevel,
            onSelectLevel = viewModel::selectRequiredViewLevel,
            onDismiss = { viewModel.setPermissionSheetVisible(false) },
        )
    }

    if (uiState.showStickerSheet) {
        StickerPickerModalSheet(
            onSelectStickerCode = { code ->
                viewModel.setStickerSheetVisible(false)
                applyEditorTransform { current ->
                    insertComposerMarkdown(current, prefix = " :$code: ")
                }
            },
            onDismiss = { viewModel.setStickerSheetVisible(false) },
        )
    }

    if (showValuationSheet) {
        VpsValuationBottomSheet(
            primaryActionLabel = "插入发帖正文",
            onInsertMarkdown = { generatedMarkdown ->
                showValuationSheet = false
                applyEditorTransform { current ->
                    insertMarkdownBlockAtCursor(current, generatedMarkdown)
                }
            },
            onDismiss = { showValuationSheet = false },
            onCopiedFeedback = { msg ->
                coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
            },
        )
    }

    if (showNetBenchSheet) {
        NetBenchQuickBottomSheet(
            primaryActionLabel = "插入发帖正文",
            onInsertMarkdown = { generatedMarkdown ->
                showNetBenchSheet = false
                applyEditorTransform { current ->
                    insertNetBenchMarkdownAtCursor(current, generatedMarkdown)
                }
            },
            onDismiss = { showNetBenchSheet = false },
            onCopiedFeedback = { msg ->
                coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
            },
            onOpenFullScreen = onOpenFullNetBench?.let { openFull ->
                { target ->
                    showNetBenchSheet = false
                    openFull(target)
                }
            },
        )
    }

    if (showSshSheet) {
        SshQuickInsertSheet(
            onDismiss = { showSshSheet = false },
            onInsertMarkdown = { generatedMarkdown ->
                showSshSheet = false
                applyEditorTransform { current ->
                    insertMarkdownBlockAtCursor(current, generatedMarkdown)
                }
            },
            onOpenFullLiteSsh = onOpenFullLiteSsh?.let { openFull ->
                {
                    showSshSheet = false
                    openFull()
                }
            },
            vaultRepository = appContainer.sshVaultRepository,
        )
    }

    if (uiState.showApiKeyDialog) {
        ComposerNodeImageKeyDialog(
            onSaveAndUpload = viewModel::saveApiKeyAndUpload,
            onDismiss = viewModel::dismissApiKeyDialog,
        )
    }

    uiState.pendingCoinConfirm?.let { confirmType ->
        ComposerCoinConfirmDialog(
            confirmType = confirmType,
            viewerCoins = uiState.session?.drumsticks,
            onConfirm = viewModel::confirmCoinSpendAndPublish,
            onDismiss = viewModel::dismissCoinConfirm,
        )
    }

    fallbackWebViewUrl?.let { authUrl ->
        WebViewSessionSheet(
            initialUrl = authUrl,
            requireSignedInUser = authUrl.contains("signIn", ignoreCase = true),
            autoDismissWhenVerified = true,
            onDismiss = { fallbackWebViewUrl = null },
            onSessionVerified = {
                fallbackWebViewUrl = null
                viewModel.onWebViewAuthCompleted()
            },
        )
    }
}

private fun insertComposerMarkdown(
    value: TextFieldValue,
    prefix: String,
    suffix: String = "",
    placeholder: String = "",
): TextFieldValue {
    val text = value.text
    val start = value.selection.min.coerceIn(0, text.length)
    val end = value.selection.max.coerceIn(0, text.length)
    val selected = text.substring(start, end)
    val inner = selected.ifEmpty { placeholder }
    val inserted = "$prefix$inner$suffix"
    val nextText = text.replaceRange(start, end, inserted)
    val selStart = start + prefix.length
    val selEnd = selStart + inner.length
    return TextFieldValue(
        text = nextText,
        selection = if (selected.isEmpty() && placeholder.isNotEmpty()) {
            TextRange(selStart, selEnd)
        } else {
            TextRange(start + inserted.length)
        },
    )
}

// ── Top Bar ───────────────────────────────────────────────────────────────────

@Composable
private fun ComposerTopBar(
    isEditMode: Boolean,
    activeTab: ComposerModeTab,
    canSubmit: Boolean,
    isSubmitting: Boolean,
    categorySlug: String,
    onBack: () -> Unit,
    onSelectTab: (ComposerModeTab) -> Unit,
    onPublish: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val categoryAccent = NodePulseThemeTokens.categorySpec(categorySlug).spineColor

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseGlassSurface(
                showTopBorder = false,
                showBottomBorder = true,
                tonalAccent = categoryAccent,
            )
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f))
                    .border(
                        width = 0.8.dp,
                        color = ext.cardBorder,
                        shape = CircleShape,
                    )
                    .pulseBouncyClickable(
                        pressedScale = NodePulseMotion.chipPressScale,
                        role = Role.Button,
                        onClickLabel = "返回",
                        onClick = onBack,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isEditMode) "编辑主题" else "发布新主题",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF22C55E)),
                    )
                    Text(
                        text = "草稿实时自动保存",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            SortSegmentedSwitcher(
                options = remember {
                    ComposerModeTab.entries.map { SortSegmentItem(it, it.label) }
                },
                selectedValue = activeTab,
                onSelect = onSelectTab,
                modifier = Modifier.width(116.dp),
            )

            val publishBg = if (canSubmit) {
                Modifier.background(PulseBrushes.brandGradient())
            } else {
                Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
            }
            Row(
                modifier = Modifier
                    .height(36.dp)
                    .then(
                        if (canSubmit && !NodePulseThemeTokens.isDark) {
                            Modifier.shadow(
                                elevation = PulseElevation.Card,
                                shape = CircleShape,
                                ambientColor = ext.softShadowColor,
                                spotColor = ext.softShadowColor,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clip(CircleShape)
                    .then(publishBg)
                    .pulseBouncyClickable(
                        enabled = canSubmit,
                        pressedScale = NodePulseMotion.chipPressScale,
                        role = Role.Button,
                        onClick = onPublish,
                    )
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    val contentTint = if (canSubmit) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = null,
                        tint = contentTint,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = if (isEditMode) "保存" else "发布",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = contentTint,
                    )
                }
            }
        }
    }
}

// ── Write Mode Surface ────────────────────────────────────────────────────────

@Composable
private fun ComposerWriteSurface(
    uiState: TopicComposerUiState,
    bodyFieldValue: TextFieldValue,
    onTitleChanged: (String) -> Unit,
    onBodyValueChanged: (TextFieldValue) -> Unit,
    onOpenBoardSheet: () -> Unit,
    onOpenPermissionSheet: () -> Unit,
    onClearOrResetDraft: () -> Unit,
    onRequestSignIn: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val boardSpec = NodePulseThemeTokens.categorySpec(uiState.selectedCategorySlug)
    val permissionOption = remember(uiState.requiredViewLevel) {
        ViewPermissionOption.forLevel(uiState.requiredViewLevel)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Guest Sign-In Reminder Banner
        if (uiState.session == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(
                        shape = PulseArchitecturalShapes.EditorialCard,
                        containerColor = blendColor(
                            ext.cardSurface,
                            MaterialTheme.colorScheme.primaryContainer,
                            0.52f,
                        ),
                        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                        elevation = PulseElevation.Subtle,
                    )
                    .pulseBouncyClickable(
                        pressedScale = NodePulseMotion.cardPressScale,
                        role = Role.Button,
                        onClick = onRequestSignIn,
                    )
                    .padding(horizontal = 13.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Login,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = "当前未登录账号 · 点击连接 NodeSeek 账号以发布帖子（您可先撰写草稿）",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Metadata Selector Strip (Board + View Permission + Draft Reset)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Board Selector Chip (Locked in Edit Mode per Protocol Rule #8)
            Row(
                modifier = Modifier
                    .height(35.dp)
                    .clip(PulseArchitecturalShapes.MediumBadge)
                    .background(boardSpec.softTonalBrush)
                    .border(
                        width = 1.dp,
                        color = boardSpec.spineColor.copy(alpha = 0.38f),
                        shape = PulseArchitecturalShapes.MediumBadge,
                    )
                    .pulseBouncyClickable(
                        enabled = !uiState.isEditMode,
                        pressedScale = NodePulseMotion.chipPressScale,
                        role = Role.Button,
                        onClick = onOpenBoardSheet,
                    )
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = if (uiState.isEditMode) Icons.Rounded.Lock else boardSpec.icon,
                    contentDescription = null,
                    tint = boardSpec.contentColor,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = uiState.selectedCategory?.name ?: boardSpec.defaultTitle,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = boardSpec.contentColor,
                )
                if (uiState.isEditMode) {
                    Text(
                        text = "(版块不可改)",
                        style = MaterialTheme.typography.labelSmall,
                        color = boardSpec.contentColor.copy(alpha = 0.8f),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = "选择版块",
                        tint = boardSpec.contentColor,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // View Permission Selector Chip
            val isRestrictedPerm = uiState.requiredViewLevel > 0
            val permBg by animateColorAsState(
                targetValue = if (isRestrictedPerm) ext.drumstickContainer else ext.cardSurface,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                label = "permBg",
            )
            val permFg by animateColorAsState(
                targetValue = if (isRestrictedPerm) ext.onDrumstickContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                label = "permFg",
            )
            val permBorder by animateColorAsState(
                targetValue = if (isRestrictedPerm) ext.drumstickGoldEnd.copy(alpha = 0.42f) else ext.cardBorder,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                label = "permBorder",
            )

            Row(
                modifier = Modifier
                    .height(35.dp)
                    .clip(PulseArchitecturalShapes.MediumBadge)
                    .background(permBg)
                    .border(
                        width = 1.dp,
                        color = permBorder,
                        shape = PulseArchitecturalShapes.MediumBadge,
                    )
                    .pulseBouncyClickable(
                        pressedScale = NodePulseMotion.chipPressScale,
                        role = Role.Button,
                        onClick = onOpenPermissionSheet,
                    )
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = if (uiState.requiredViewLevel == 0) {
                        Icons.Rounded.Public
                    } else {
                        Icons.Rounded.Lock
                    },
                    contentDescription = null,
                    tint = permFg,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = permissionOption.shortLabel,
                    style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    color = permFg,
                )
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = "设置阅读权限",
                    tint = permFg,
                    modifier = Modifier.size(16.dp),
                )
            }

            // Draft Reset / Clear Action
            if (uiState.hasRestoredDraft ||
                uiState.canResetToOriginalFloor ||
                (!uiState.isEditMode && (uiState.title.isNotBlank() || bodyFieldValue.text.isNotBlank()))
            ) {
                Row(
                    modifier = Modifier
                        .height(35.dp)
                        .clip(PulseArchitecturalShapes.MediumBadge)
                        .background(ext.cardSurface)
                        .border(
                            width = 1.dp,
                            color = ext.cardBorder,
                            shape = PulseArchitecturalShapes.MediumBadge,
                        )
                        .pulseBouncyClickable(
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.Button,
                            onClick = onClearOrResetDraft,
                        )
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (uiState.isEditMode) Icons.Rounded.Restore else Icons.Rounded.DeleteSweep,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = if (uiState.isEditMode) "还原原帖" else "清空草稿",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Inside Board 5-Coin Fee Notice
        if (!uiState.isEditMode && uiState.isInsideBoardSelected) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(
                        shape = PulseArchitecturalShapes.MediumBadge,
                        containerColor = ext.drumstickContainer,
                        borderColor = ext.drumstickGoldEnd.copy(alpha = 0.42f),
                        elevation = PulseElevation.Subtle,
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(ext.drumstickGoldEnd.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Restaurant,
                        contentDescription = null,
                        tint = ext.onDrumstickContainer,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Text(
                    text = "内版发帖提示：在「内版」创建新主题需消耗 5 鸡腿" +
                        (uiState.session?.let { "（当前余额 ${it.drumsticks} 鸡腿）" } ?: ""),
                    style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                    fontWeight = FontWeight.Medium,
                    color = ext.onDrumstickContainer,
                )
            }
        }

        // Edit-to-Private 10-Coin Fee Notice
        if (uiState.willIncurPrivateUpgradeFee) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(
                        shape = PulseArchitecturalShapes.MediumBadge,
                        containerColor = ext.drumstickContainer,
                        borderColor = ext.drumstickGoldEnd.copy(alpha = 0.42f),
                        elevation = PulseElevation.Subtle,
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(ext.drumstickGoldEnd.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = ext.onDrumstickContainer,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Text(
                    text = "私有化提示：将已有主题修改为「私有帖 (Lv.255)」将消耗 10 鸡腿",
                    style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                    fontWeight = FontWeight.Medium,
                    color = ext.onDrumstickContainer,
                )
            }
        }

        // Layered Editorial Writing Surface Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseCard(
                    shape = PulseArchitecturalShapes.HeroBento,
                    elevation = PulseElevation.Card,
                    accentColor = boardSpec.spineColor,
                ),
        ) {
            // Subtle category top accent ribbon
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                boardSpec.spineColor.copy(alpha = 0.85f),
                                boardSpec.gradientEndColor.copy(alpha = 0.65f),
                            ),
                        ),
                    ),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Editorial Title Input
                BasicTextField(
                    value = uiState.title,
                    onValueChange = onTitleChanged,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        ) {
                            if (uiState.title.isEmpty()) {
                                Text(
                                    text = "输入清晰的技术、测评或讨论标题…",
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f),
                                )
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                PulseGradientDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
                )

                // Editorial Markdown Body Input
                BasicTextField(
                    value = bodyFieldValue,
                    onValueChange = onBodyValueChanged,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onBackground,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 280.dp)
                                .padding(vertical = 2.dp),
                        ) {
                            if (bodyFieldValue.text.isEmpty()) {
                                Text(
                                    text = "使用 Markdown 撰写正文…\n\n• 支持代码块与 YABS 终端 ANSI 色彩\n• 支持 :::: tabs 多标签卡与表格\n• 支持 @用户名 提及、贴纸与 NodeImage 图床插图",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f),
                                )
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            PulseGradientDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.65f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = "Markdown · GFM 编辑器",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${bodyFieldValue.text.length} 字符",
                    style = NodePulseThemeTokens.extraTypography.tabularBadge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(36.dp))
    }
}

// ── Live Preview Surface ──────────────────────────────────────────────────────

@Composable
private fun ComposerLivePreviewSurface(
    uiState: TopicComposerUiState,
    markdownText: String,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val boardSpec = NodePulseThemeTokens.categorySpec(uiState.selectedCategorySlug)
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Preview Header Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseHeroCard(
                    accentColor = boardSpec.spineColor,
                    shape = PulseArchitecturalShapes.HeroBento,
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                boardSpec.spineColor.copy(alpha = if (NodePulseThemeTokens.isDark) 0.14f else 0.085f),
                                boardSpec.gradientEndColor.copy(alpha = if (NodePulseThemeTokens.isDark) 0.04f else 0.025f),
                                Color.Transparent,
                            ),
                        ),
                    )
                    .padding(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CategoryMicroTag(
                            slug = uiState.selectedCategorySlug,
                            labelOverride = uiState.selectedCategory?.name,
                            compact = false,
                        )
                        if (uiState.requiredViewLevel > 0) {
                            AccessLockChip(requiredLevel = uiState.requiredViewLevel)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        PulseAccentPill(
                            text = "实时排版预览",
                            icon = Icons.Rounded.Visibility,
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }

                    Text(
                        text = uiState.title.ifBlank { "（尚未填写标题）" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (uiState.title.isBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onBackground
                        },
                    )

                    uiState.session?.let { viewer ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(PulseArchitecturalShapes.EditorialCard)
                                .background(ext.cardSurface.copy(alpha = 0.72f))
                                .border(
                                    width = 0.8.dp,
                                    color = ext.cardBorder,
                                    shape = PulseArchitecturalShapes.EditorialCard,
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PulseAvatar(
                                avatarUrl = viewer.avatarUrl,
                                username = viewer.username,
                                size = 38.dp,
                                rankLevel = viewer.level,
                            )
                            Text(
                                text = viewer.username,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            OpHighlightChip(label = "楼主")
                            RankMicroBadge(level = viewer.level)
                        }
                    }
                }
            }
        }

        // Preview Content Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseCard(
                    shape = PulseArchitecturalShapes.EditorialCard,
                    elevation = PulseElevation.Card,
                )
                .padding(16.dp),
        ) {
            if (markdownText.isBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Visibility,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "正文为空，切换至「编辑」开始输入 Markdown 内容。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                MarkdownRenderer(
                    markdown = markdownText,
                    bodyStyle = MaterialTheme.typography.bodyLarge,
                    onUrlClick = { url -> openNodeSeekOrExternalUrl(rawUrl = url, uriHandler = uriHandler) },
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ── Sticky Formatting Toolbar ─────────────────────────────────────────────────

@Composable
private fun ComposerBottomFormattingBar(
    isUploadingImage: Boolean,
    categorySlug: String,
    onApplyTransform: ((TextFieldValue) -> TextFieldValue) -> Unit,
    onOpenValuationCalculator: () -> Unit,
    onOpenNetBench: () -> Unit,
    onOpenSshInsert: () -> Unit,
    onOpenStickers: () -> Unit,
    onPickPhoto: () -> Unit,
) {
    val categoryAccent = NodePulseThemeTokens.categorySpec(categorySlug).spineColor
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseGlassSurface(
                showTopBorder = true,
                showBottomBorder = false,
                tonalAccent = categoryAccent,
            )
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ComposerToolbarButton(
                icon = Icons.Rounded.Calculate,
                label = "剩余价值",
                onClick = onOpenValuationCalculator,
            )
            ComposerToolbarButton(
                icon = Icons.Rounded.Speed,
                label = "测机",
                onClick = onOpenNetBench,
            )
            ComposerToolbarButton(
                icon = Icons.Rounded.Computer,
                label = "SSH 跑分",
                onClick = onOpenSshInsert,
            )
            ComposerToolbarButton(
                icon = Icons.Rounded.EmojiEmotions,
                label = "贴纸",
                onClick = onOpenStickers,
            )

            if (isUploadingImage) {
                Row(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .border(
                            width = 0.8.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                            shape = RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = "上传中",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            } else {
                ComposerToolbarButton(
                    icon = Icons.Rounded.AddPhotoAlternate,
                    label = "图床上传",
                    onClick = onPickPhoto,
                )
            }

            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .width(1.dp)
                    .height(20.dp)
                    .background(NodePulseThemeTokens.extendedColors.cardBorder),
            )

            ComposerToolbarButton(
                icon = Icons.Rounded.Title,
                label = "H2",
                onClick = {
                    onApplyTransform {
                        insertComposerMarkdown(it, prefix = "\n## ", placeholder = "章节标题")
                    }
                },
            )
            ComposerToolbarButton(
                icon = Icons.Rounded.FormatBold,
                label = "加粗",
                onClick = {
                    onApplyTransform {
                        insertComposerMarkdown(it, prefix = "**", suffix = "**", placeholder = "粗体内容")
                    }
                },
            )
            ComposerToolbarButton(
                icon = Icons.Rounded.FormatItalic,
                label = "斜体",
                onClick = {
                    onApplyTransform {
                        insertComposerMarkdown(it, prefix = "*", suffix = "*", placeholder = "斜体内容")
                    }
                },
            )
            ComposerToolbarButton(
                icon = Icons.Rounded.Code,
                label = "代码",
                onClick = {
                    onApplyTransform {
                        insertComposerMarkdown(it, prefix = "`", suffix = "`", placeholder = "code")
                    }
                },
            )
            ComposerToolbarButton(
                icon = Icons.Rounded.Terminal,
                label = "代码块",
                onClick = {
                    onApplyTransform {
                        insertComposerMarkdown(
                            it,
                            prefix = "\n```bash\n",
                            suffix = "\n```\n",
                            placeholder = "curl -sL yabs.sh | bash",
                        )
                    }
                },
            )
            ComposerToolbarButton(
                icon = Icons.Rounded.FormatQuote,
                label = "引用",
                onClick = {
                    onApplyTransform {
                        insertComposerMarkdown(it, prefix = "\n> ", placeholder = "引用段落")
                    }
                },
            )
            ComposerToolbarButton(
                icon = Icons.Rounded.Link,
                label = "链接",
                onClick = {
                    onApplyTransform {
                        insertComposerMarkdown(it, prefix = "[", suffix = "](https://)", placeholder = "链接文字")
                    }
                },
            )
            ComposerToolbarButton(
                icon = Icons.Rounded.Tab,
                label = "Tabs",
                onClick = {
                    onApplyTransform {
                        insertComposerMarkdown(it, prefix = TABS_MARKDOWN_TEMPLATE)
                    }
                },
            )
        }
    }
}

@Composable
private fun ComposerToolbarButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val shape = PulseArchitecturalShapes.MediumBadge
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(shape)
            .background(ext.cardSurface)
            .border(
                width = 0.9.dp,
                color = ext.cardBorder,
                shape = shape,
            )
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Board Selector Sheet (Excludes `adminOnly`, highlights `inside` 5-coin fee) ──

@Composable
private fun BoardSelectorBottomSheet(
    boards: List<Category>,
    selectedSlug: String,
    viewerCoins: Int?,
    onSelectBoard: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ext = NodePulseThemeTokens.extendedColors

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(26.dp)
                        .clip(CircleShape)
                        .background(PulseBrushes.brandGradient(horizontal = false)),
                )
                Column {
                    Text(
                        text = "选择发布版块",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "请根据主题属性选择准确频道（管理员专属频道已自动过滤）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PulseGradientDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(boards, key = { it.slug }) { category ->
                    val spec = NodePulseThemeTokens.categorySpec(category.slug)
                    val isSelected = category.slug.equals(selectedSlug, ignoreCase = true)
                    val isInside = category.slug.equals("inside", ignoreCase = true) || category.topicCoinCost > 0

                    val rowContainer = if (isSelected) {
                        blendColor(ext.cardSurface, spec.containerColor, 0.42f)
                    } else {
                        ext.cardSurface
                    }
                    val rowBorder = if (isSelected) spec.spineColor else ext.cardBorder

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pulseCard(
                                shape = PulseArchitecturalShapes.EditorialCard,
                                containerColor = rowContainer,
                                borderColor = rowBorder,
                                borderWidth = if (isSelected) 1.4.dp else 1.dp,
                                elevation = if (isSelected) PulseElevation.Card else PulseElevation.Subtle,
                            )
                            .pulseBouncyClickable(
                                pressedScale = NodePulseMotion.cardPressScale,
                                role = Role.RadioButton,
                                onClick = { onSelectBoard(category.slug) },
                            )
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(PulseArchitecturalShapes.MediumBadge)
                                .background(spec.softTonalBrush)
                                .border(
                                    width = 0.8.dp,
                                    color = spec.spineColor.copy(alpha = 0.30f),
                                    shape = PulseArchitecturalShapes.MediumBadge,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = spec.icon,
                                contentDescription = null,
                                tint = spec.contentColor,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = category.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "/${category.slug}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (isInside) {
                                    Row(
                                        modifier = Modifier
                                            .clip(PulseArchitecturalShapes.SmallChip)
                                            .background(ext.drumstickContainer)
                                            .border(
                                                width = 0.8.dp,
                                                color = ext.drumstickGoldEnd.copy(alpha = 0.36f),
                                                shape = PulseArchitecturalShapes.SmallChip,
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Restaurant,
                                            contentDescription = null,
                                            tint = ext.onDrumstickContainer,
                                            modifier = Modifier.size(11.dp),
                                        )
                                        Text(
                                            text = "消耗 ${category.topicCoinCost.coerceAtLeast(5)} 鸡腿",
                                            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                                            fontWeight = FontWeight.SemiBold,
                                            color = ext.onDrumstickContainer,
                                        )
                                    }
                                }
                            }
                            Text(
                                text = category.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "已选中",
                                tint = spec.spineColor,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            if (viewerCoins != null) {
                Row(
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .clip(CircleShape)
                        .background(ext.drumstickContainer.copy(alpha = 0.72f))
                        .border(
                            width = 0.8.dp,
                            color = ext.drumstickGoldEnd.copy(alpha = 0.32f),
                            shape = CircleShape,
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Restaurant,
                        contentDescription = null,
                        tint = ext.onDrumstickContainer,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = "您当前持有 $viewerCoins 鸡腿",
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                        fontWeight = FontWeight.SemiBold,
                        color = ext.onDrumstickContainer,
                    )
                }
            }
        }
    }
}

// ── View Permission Selector Sheet (`Lv.0`, `Lv.1..Lv.6`, `Lv.255`) ───────────

@Composable
private fun ViewPermissionBottomSheet(
    selectedLevel: Int,
    isEditMode: Boolean,
    originalRequiredViewLevel: Int,
    onSelectLevel: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ext = NodePulseThemeTokens.extendedColors

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(26.dp)
                        .clip(CircleShape)
                        .background(PulseBrushes.brandGradient(horizontal = false)),
                )
                Column {
                    Text(
                        text = "设置阅读权限等级",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "可限制最低 NodeSeek 等级要求，或将帖子设为仅自己可见的私有帖",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PulseGradientDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ViewPermissionOption.ALL_OPTIONS, key = { it.requiredLevel }) { option ->
                    val isSelected = option.requiredLevel == selectedLevel
                    val incursTenCoinFee = isEditMode &&
                        originalRequiredViewLevel != 255 &&
                        option.requiredLevel == 255

                    val rowContainer = if (isSelected) {
                        blendColor(ext.cardSurface, MaterialTheme.colorScheme.primaryContainer, 0.44f)
                    } else {
                        ext.cardSurface
                    }
                    val rowBorder = if (isSelected) MaterialTheme.colorScheme.primary else ext.cardBorder

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pulseCard(
                                shape = PulseArchitecturalShapes.EditorialCard,
                                containerColor = rowContainer,
                                borderColor = rowBorder,
                                borderWidth = if (isSelected) 1.4.dp else 1.dp,
                                elevation = if (isSelected) PulseElevation.Card else PulseElevation.Subtle,
                            )
                            .pulseBouncyClickable(
                                pressedScale = NodePulseMotion.cardPressScale,
                                role = Role.RadioButton,
                                onClick = { onSelectLevel(option.requiredLevel) },
                            )
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (option.requiredLevel in 1..6) {
                            RankMicroBadge(level = option.requiredLevel)
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(PulseArchitecturalShapes.SmallChip)
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (option.requiredLevel == 0) {
                                        Icons.Rounded.Public
                                    } else {
                                        Icons.Rounded.Lock
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = option.fullTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (incursTenCoinFee) {
                                    Row(
                                        modifier = Modifier
                                            .clip(PulseArchitecturalShapes.SmallChip)
                                            .background(ext.drumstickContainer)
                                            .border(
                                                width = 0.8.dp,
                                                color = ext.drumstickGoldEnd.copy(alpha = 0.36f),
                                                shape = PulseArchitecturalShapes.SmallChip,
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "消耗 10 鸡腿",
                                            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                                            fontWeight = FontWeight.SemiBold,
                                            color = ext.onDrumstickContainer,
                                        )
                                    }
                                }
                            }
                            Text(
                                text = option.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "已选中",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

// ── Sticker Picker Modal Sheet ────────────────────────────────────────────────

@Composable
private fun StickerPickerModalSheet(
    onSelectStickerCode: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val groups = remember {
        listOf(
            "ac" to "AC娘",
            "yct" to "洋葱头",
            "xhj" to "小黄鸡",
        )
    }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val stickers = remember(selectedTab) {
        NodeSeekStickerCatalog.stickersForGroup(groups[selectedTab].first)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(PulseArchitecturalShapes.MediumBadge)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.EmojiEmotions,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = "插入社区表情贴纸",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PulseArchitecturalShapes.EditorialCard)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(0.8.dp, ext.cardBorder, PulseArchitecturalShapes.EditorialCard)
                    .padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                groups.forEachIndexed { idx, (_, name) ->
                    val selected = selectedTab == idx
                    val pillBg by animateColorAsState(
                        targetValue = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        },
                        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                        label = "modalStickerTabBg",
                    )
                    val pillFg by animateColorAsState(
                        targetValue = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                        label = "modalStickerTabFg",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(PulseArchitecturalShapes.MediumBadge)
                            .background(pillBg)
                            .pulseBouncyClickable(
                                pressedScale = NodePulseMotion.chipPressScale,
                                role = Role.Tab,
                                onClick = { selectedTab = idx },
                            )
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = pillFg,
                        )
                    }
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 52.dp),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
            ) {
                items(stickers, key = { it.code }) { sticker ->
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(PulseArchitecturalShapes.MediumBadge)
                            .background(ext.cardSurface)
                            .border(
                                width = 0.9.dp,
                                color = ext.cardBorder,
                                shape = PulseArchitecturalShapes.MediumBadge,
                            )
                            .pulseBouncyClickable(
                                pressedScale = NodePulseMotion.chipPressScale,
                                onClick = { onSelectStickerCode(sticker.code) },
                            )
                            .padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = sticker.imageUrl,
                            contentDescription = ":${sticker.code}:",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── Dialogs & Error Cards ─────────────────────────────────────────────────────

@Composable
private fun ComposerCoinConfirmDialog(
    confirmType: PendingComposerCoinConfirm,
    viewerCoins: Int?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val (title, message) = when (confirmType) {
        PendingComposerCoinConfirm.NewInsideBoardTopic -> {
            "确认在内版发布新主题？" to
                "在「内版 (inside)」创建新主题将消耗 5 个鸡腿。请确认版块与内容无误后继续发布。"
        }
        PendingComposerCoinConfirm.EditChangeToPrivate -> {
            "确认将主题设为私有 (Lv.255)？" to
                "根据 NodeSeek 社区规则，将已有非私有主题修改为「私有帖 (仅自己可见)」将消耗 10 个鸡腿。"
        }
    }

    PulseAlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        message = message,
        icon = Icons.Rounded.WorkspacePremium,
        tone = PulseDialogTone.WARM,
        accentColor = ext.drumstickGoldEnd,
        centerHeader = true,
        confirmText = "确认消耗 ${confirmType.coinCost} 鸡腿",
        onConfirm = onConfirm,
        dismissText = "返回修改",
        onDismiss = onDismiss,
        content = if (viewerCoins != null) {
            {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier
                            .clip(PulseArchitecturalShapes.SmallChip)
                            .background(ext.drumstickContainer.copy(alpha = 0.72f))
                            .border(
                                width = 0.8.dp,
                                color = ext.drumstickGoldEnd.copy(alpha = 0.32f),
                                shape = PulseArchitecturalShapes.SmallChip,
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Restaurant,
                            contentDescription = null,
                            tint = ext.onDrumstickContainer,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = "当前账号余额：$viewerCoins 鸡腿",
                            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                            color = ext.onDrumstickContainer,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun ComposerNodeImageKeyDialog(
    onSaveAndUpload: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    var inputKey by rememberSaveable { mutableStateOf("") }

    PulseAlertDialog(
        onDismissRequest = onDismiss,
        title = "设置 NodeImage 图床密钥",
        message = "检测到尚未配置 NodeImage API Key。请输入您在 nodeimage.com 获取的密钥，保存后将立即上传所选图片并插入 Markdown。",
        icon = Icons.Rounded.Key,
        tone = PulseDialogTone.PRIMARY,
        centerHeader = true,
        confirmText = "保存并上传",
        onConfirm = { onSaveAndUpload(inputKey) },
        confirmEnabled = inputKey.trim().isNotEmpty(),
        dismissText = "取消",
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = inputKey,
            onValueChange = { inputKey = it },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = ext.cardSurface,
                unfocusedContainerColor = ext.cardSurface,
                unfocusedBorderColor = ext.cardBorder,
            ),
            label = { Text("NodeImage X-API-Key") },
            placeholder = { Text("粘贴 32–128 位十六进制 API Key") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private data class PickedImagePayload(
    val bytes: ByteArray,
    val mimeType: String,
    val fileName: String?,
)

private fun readPickedImagePayload(
    context: Context,
    uri: Uri,
): PickedImagePayload? = runCatching {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri).orEmpty()
    val fileName = queryPickedImageDisplayName(resolver = resolver, uri = uri)
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
    if (bytes.isEmpty()) return@runCatching null
    PickedImagePayload(
        bytes = bytes,
        mimeType = mimeType,
        fileName = fileName,
    )
}.getOrNull()

private fun queryPickedImageDisplayName(
    resolver: ContentResolver,
    uri: Uri,
): String? = runCatching {
    resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (columnIndex >= 0) {
            cursor.getString(columnIndex)?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
    }
}.getOrNull() ?: uri.lastPathSegment
    ?.substringAfterLast('/')
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

@Composable
private fun ComposerLoadErrorCard(
    message: String,
    onRetry: () -> Unit,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorAccent = MaterialTheme.colorScheme.error
    PulseStaggeredEntrance(index = 0, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .pulseHeroCard(
                    accentColor = errorAccent,
                    shape = PulseArchitecturalShapes.HeroBento,
                )
                .padding(horizontal = 24.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                errorAccent.copy(alpha = 0.20f),
                                errorAccent.copy(alpha = 0.04f),
                            ),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        color = errorAccent.copy(alpha = 0.25f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WifiOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Text(
                text = "无法读取原帖内容",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onLogin, shape = CircleShape) {
                    Text("验证登录")
                }
                FilledTonalButton(onClick = onRetry, shape = CircleShape) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("重试")
                }
            }
        }
    }
}
