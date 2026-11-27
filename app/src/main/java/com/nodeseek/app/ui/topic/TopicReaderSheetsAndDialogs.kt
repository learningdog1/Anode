@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.topic

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.nodeseek.app.ui.composer.ImagePayloadReadResult
import com.nodeseek.app.ui.composer.readPickedImagePayloadBounded
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowUp
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nodeseek.app.di.LocalAppContainer
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.ui.components.MarkdownRenderer
import com.nodeseek.app.ui.components.NodeSeekDrumstickIcon
import com.nodeseek.app.ui.components.NodeSeekStickerAsyncImage
import com.nodeseek.app.ui.components.NodeSeekStickerCatalog
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.components.PulseConfirmDialog
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.PulseTextField
import com.nodeseek.app.ui.components.RankMicroBadge
import com.nodeseek.app.ui.components.SortSegmentItem
import com.nodeseek.app.ui.components.SortSegmentedSwitcher
import com.nodeseek.app.ui.netbench.NetBenchQuickBottomSheet
import com.nodeseek.app.ui.netbench.insertNetBenchMarkdownAtCursor
import com.nodeseek.app.ui.ssh.SshQuickInsertSheet
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.valuation.VpsValuationBottomSheet
import com.nodeseek.app.ui.valuation.insertMarkdownBlockAtCursor
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseStaggeredEntrance
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseHeroCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val BENCHMARK_TABS_SNIPPET = """
:::: tabs
::: tab-item 基础信息
在此输入硬件或测试摘要
:::
::: tab-item 网络回程
在此粘贴 MTR / 路由实测
:::
::::
"""

internal fun insertOrWrapMarkdown(
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
    val replacement = "$prefix$inner$suffix"
    val updated = text.replaceRange(start, end, replacement)
    val cursorStart = start + prefix.length
    val cursorEnd = cursorStart + inner.length
    return TextFieldValue(
        text = updated,
        selection = if (selected.isEmpty() && placeholder.isNotEmpty()) {
            TextRange(cursorStart, cursorEnd)
        } else {
            TextRange(start + replacement.length)
        },
    )
}

/**
 * Full Markdown Reply & Edit-Reply Modal Bottom Sheet with:
 * - Segmented `编辑 / 预览` switcher
 * - Replying-to quote context chip (`@user #floor`)
 * - Markdown formatting toolbar
 * - Inline `NodeSeekStickerCatalog` picker (`ac` / `yct` / `xhj` / `emoji`)
 * - Multi-format photo upload via `PickVisualMedia` / `OpenDocument` + `imageHostRepository`
 */
@Composable
internal fun ReplyComposerBottomSheet(
    state: ReplyComposerUiState,
    insertedUploadSnippets: Flow<String>,
    onDismiss: () -> Unit,
    onClearQuote: () -> Unit,
    onSelectTab: (ReplyEditorTab) -> Unit,
    onDraftChanged: (String) -> Unit,
    onToggleStickerPicker: () -> Unit,
    onPhotoBytesPicked: (ByteArray, String, String?) -> Unit,
    onSubmit: () -> Unit,
    onMentionClickInPreview: (String) -> Unit,
    onFloorClickInPreview: (Int) -> Unit,
    onUrlClickInPreview: ((String) -> Unit)? = null,
    onPhotoReadError: (String) -> Unit = {},
    onOpenFullNetBench: ((String) -> Unit)? = null,
    onOpenFullLiteSsh: (() -> Unit)? = null,
    onPublishSshToComposer: ((suggestedTitle: String, markdownContent: String) -> Unit)? = null,
    initialValuationMachineLabel: String = "",
) {
    val appContainer = LocalAppContainer.current
    val ext = NodePulseThemeTokens.extendedColors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val maxSheetHeight = (screenHeightDp * 0.88f).dp.coerceAtLeast(360.dp)
    val previewMaxHeight = (screenHeightDp * 0.46f).dp.coerceIn(220.dp, 460.dp)
    var showValuationSheet by remember { mutableStateOf(false) }
    var showNetBenchSheet by remember { mutableStateOf(false) }
    var showSshSheet by remember { mutableStateOf(false) }

    val latestOnDraftChanged by rememberUpdatedState(onDraftChanged)
    val latestOnPhotoBytesPicked by rememberUpdatedState(onPhotoBytesPicked)
    val latestOnPhotoReadError by rememberUpdatedState(onPhotoReadError)

    var textFieldValue by remember(state.composerRevision) {
        mutableStateOf(
            TextFieldValue(
                text = state.draftMarkdown,
                selection = TextRange(state.draftMarkdown.length),
            )
        )
    }
    val applyTransform: ((TextFieldValue) -> TextFieldValue) -> Unit = { transform ->
        val updated = transform(textFieldValue)
        textFieldValue = updated
        latestOnDraftChanged(updated.text)
    }

    LaunchedEffect(insertedUploadSnippets, state.composerRevision) {
        insertedUploadSnippets.collect { snippet ->
            val updated = insertOrWrapMarkdown(textFieldValue, prefix = snippet)
            textFieldValue = updated
            latestOnDraftChanged(updated.text)
        }
    }

    val handlePickedUri: (Uri?) -> Unit = { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val readResult = withContext(Dispatchers.IO) {
                    readPickedImagePayloadBounded(context = context, uri = uri)
                }
                when (readResult) {
                    is ImagePayloadReadResult.Success -> {
                        latestOnPhotoBytesPicked(readResult.bytes, readResult.mimeType, readResult.fileName)
                    }
                    is ImagePayloadReadResult.TooLarge -> {
                        latestOnPhotoReadError(readResult.message)
                    }
                    is ImagePayloadReadResult.ReadError -> {
                        latestOnPhotoReadError(readResult.message)
                    }
                }
            }
        }
    }

    val documentImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = handlePickedUri,
    )

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = handlePickedUri,
    )

    val sheetContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header Row: Leading Icon Badge + Title/Context + Segmented Switcher + Gradient CTA
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(PulseArchitecturalShapes.MediumBadge)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .border(
                            width = 0.9.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                            shape = PulseArchitecturalShapes.MediumBadge,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (state.isEditMode) Icons.Rounded.Edit else Icons.Rounded.ChatBubbleOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (state.editingComment != null) {
                            "编辑 #${state.editingComment.floorNumber} 楼回复"
                        } else {
                            "撰写回复"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "支持 GFM Markdown、代码高亮与贴纸",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SortSegmentedSwitcher(
                    options = remember {
                        ReplyEditorTab.entries.map { SortSegmentItem(it, it.label) }
                    },
                    selectedValue = state.activeTab,
                    onSelect = onSelectTab,
                    modifier = Modifier.width(120.dp),
                )

                val canSubmit = !state.isSubmitting && textFieldValue.text.isNotBlank()
                val submitBgModifier = if (canSubmit) {
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
                        .then(submitBgModifier)
                        .pulseBouncyClickable(
                            enabled = canSubmit,
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.Button,
                            onClick = onSubmit,
                        )
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (state.isSubmitting) {
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
                            text = if (state.isEditMode) "保存" else "发布",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = contentTint,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }

            // Quote Context Card
            state.quotedContext?.let { quote ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseCard(
                            shape = PulseArchitecturalShapes.MediumBadge,
                            containerColor = blendColor(
                                ext.cardSurface,
                                MaterialTheme.colorScheme.primaryContainer,
                                0.50f,
                            ),
                            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                            elevation = PulseElevation.Subtle,
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(16.dp)
                            .clip(CircleShape)
                            .background(PulseBrushes.brandGradient(horizontal = false)),
                    )
                    Icon(
                        imageVector = Icons.Rounded.FormatQuote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = "引用 @${quote.username} #${quote.floorNumber}",
                        style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f))
                            .pulseBouncyClickable(
                                pressedScale = NodePulseMotion.chipPressScale,
                                role = Role.Button,
                                onClickLabel = "移除引用",
                                onClick = onClearQuote,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "移除引用",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }

            // Editor vs Live Preview Body
            if (state.activeTab == ReplyEditorTab.WRITE) {
                PulseTextField(
                    value = textFieldValue,
                    onValueChange = { next ->
                        textFieldValue = next
                        onDraftChanged(next.text)
                    },
                    placeholder = "分享你的技术见解、测评补充或讨论观点…（自动保存本地草稿）",
                    textStyle = MaterialTheme.typography.bodyLarge,
                    singleLine = false,
                    minLines = 6,
                    maxLines = 12,
                    shape = PulseArchitecturalShapes.EditorialCard,
                    containerColor = ext.cardSurface,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Formatting & Media Toolbar
                MarkdownQuickToolbar(
                    isUploadingImage = state.isUploadingImage,
                    isStickerOpen = state.showStickerPicker,
                    onApplyTransform = applyTransform,
                    onToggleStickers = onToggleStickerPicker,
                    onPickPhoto = {
                        val launched = runCatching {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }.isSuccess
                        if (!launched) {
                            runCatching {
                                documentImageLauncher.launch(arrayOf("image/*"))
                            }.onFailure {
                                latestOnPhotoReadError("未找到可用的图片选择器，请检查系统相册或文件管理权限")
                            }
                        }
                    },
                    onPickFileImage = {
                        runCatching {
                            documentImageLauncher.launch(arrayOf("image/*"))
                        }.onFailure {
                            latestOnPhotoReadError("无法打开系统文件选择器，请重试")
                        }
                    },
                    onOpenValuationCalculator = { showValuationSheet = true },
                    onOpenNetBench = { showNetBenchSheet = true },
                    onOpenSshInsert = { showSshSheet = true },
                )

                // Inline NodeSeek Sticker Catalog Picker
                AnimatedVisibility(visible = state.showStickerPicker) {
                    InlineNodeSeekStickerPicker(
                        onSelectStickerCode = { stickerCode ->
                            val updated = insertOrWrapMarkdown(
                                value = textFieldValue,
                                prefix = " :$stickerCode: ",
                            )
                            textFieldValue = updated
                            latestOnDraftChanged(updated.text)
                        },
                    )
                }
            } else {
                val previewMarkdown = state.draftMarkdown.ifBlank { textFieldValue.text }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 180.dp, max = previewMaxHeight)
                        .pulseCard(
                            shape = PulseArchitecturalShapes.EditorialCard,
                            elevation = PulseElevation.Subtle,
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Visibility,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "Markdown 实时渲染预览",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.isUploadingImage) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.8.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "上传图片中…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    PulseGradientDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp),
                    ) {
                        if (previewMarkdown.isBlank()) {
                            Text(
                                text = "暂无预览内容，请切换回「编辑」输入正文。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            MarkdownRenderer(
                                markdown = previewMarkdown,
                                bodyStyle = MaterialTheme.typography.bodyMedium,
                                onMentionClick = onMentionClickInPreview,
                                onFloorClick = onFloorClickInPreview,
                                onUrlClick = onUrlClickInPreview,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }

    if (LocalInspectionMode.current) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                shape = PulseArchitecturalShapes.BottomSheetTop,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BottomSheetDefaults.DragHandle()
                    sheetContent()
                }
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            shape = PulseArchitecturalShapes.BottomSheetTop,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            sheetContent()
        }
    }

    if (showValuationSheet) {
        VpsValuationBottomSheet(
            primaryActionLabel = "插入回复内容",
            onInsertMarkdown = { markdown ->
                showValuationSheet = false
                applyTransform { insertMarkdownBlockAtCursor(it, markdown) }
            },
            onDismiss = { showValuationSheet = false },
            initialMachineLabel = initialValuationMachineLabel,
            marketReferenceRepository = appContainer.tradeMarketReferenceRepository,
        )
    }

    if (showNetBenchSheet) {
        NetBenchQuickBottomSheet(
            primaryActionLabel = "插入回复内容",
            onInsertMarkdown = { markdown ->
                showNetBenchSheet = false
                applyTransform { insertNetBenchMarkdownAtCursor(it, markdown) }
            },
            onDismiss = { showNetBenchSheet = false },
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
            onInsertMarkdown = { markdown ->
                showSshSheet = false
                applyTransform { insertMarkdownBlockAtCursor(it, markdown) }
            },
            onPublishToComposer = onPublishSshToComposer?.let { publishToComposer ->
                { suggestedTitle, markdown ->
                    showSshSheet = false
                    onDismiss()
                    publishToComposer(suggestedTitle, markdown)
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
}

@Composable
internal fun MarkdownQuickToolbar(
    isUploadingImage: Boolean,
    isStickerOpen: Boolean,
    onApplyTransform: ((TextFieldValue) -> TextFieldValue) -> Unit,
    onToggleStickers: () -> Unit,
    onPickPhoto: () -> Unit,
    modifier: Modifier = Modifier,
    onPickFileImage: (() -> Unit)? = null,
    onOpenValuationCalculator: () -> Unit = {},
    onOpenNetBench: () -> Unit = {},
    onOpenSshInsert: () -> Unit = {},
) {
    val ext = NodePulseThemeTokens.extendedColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.MediumBadge,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                borderColor = ext.cardBorder,
                elevation = PulseElevation.Subtle,
            )
            .animateContentSize()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToolbarIconAction(
            icon = Icons.Rounded.Calculate,
            label = "剩余价值",
            onClick = onOpenValuationCalculator,
        )
        ToolbarCompactChipAction(
            icon = Icons.Rounded.Speed,
            label = "测机",
            onClick = onOpenNetBench,
        )
        ToolbarCompactChipAction(
            icon = Icons.Rounded.Computer,
            label = "SSH 跑分",
            onClick = onOpenSshInsert,
        )
        ToolbarIconAction(
            icon = Icons.Rounded.EmojiEmotions,
            label = "贴纸",
            highlighted = isStickerOpen,
            onClick = onToggleStickers,
        )

        if (isUploadingImage) {
            Box(
                modifier = Modifier.size(38.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            ToolbarIconAction(
                icon = Icons.Rounded.AddPhotoAlternate,
                label = "相册选图",
                onClick = onPickPhoto,
            )
            if (onPickFileImage != null) {
                ToolbarIconAction(
                    icon = Icons.Rounded.PhotoLibrary,
                    label = "从文件选图（支持 WEBP/GIF/HEIC）",
                    onClick = onPickFileImage,
                )
            }
        }

        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .width(1.dp)
                .height(18.dp)
                .background(ext.cardBorder),
        )

        ToolbarIconAction(
            icon = Icons.Rounded.Title,
            label = "二级标题",
            onClick = {
                onApplyTransform { insertOrWrapMarkdown(it, prefix = "\n## ", placeholder = "小节标题") }
            },
        )
        ToolbarIconAction(
            icon = Icons.Rounded.FormatBold,
            label = "加粗",
            onClick = {
                onApplyTransform { insertOrWrapMarkdown(it, prefix = "**", suffix = "**", placeholder = "粗体文本") }
            },
        )
        ToolbarIconAction(
            icon = Icons.Rounded.FormatItalic,
            label = "斜体",
            onClick = {
                onApplyTransform { insertOrWrapMarkdown(it, prefix = "*", suffix = "*", placeholder = "斜体文本") }
            },
        )
        ToolbarIconAction(
            icon = Icons.Rounded.Code,
            label = "行内代码",
            onClick = {
                onApplyTransform { insertOrWrapMarkdown(it, prefix = "`", suffix = "`", placeholder = "code") }
            },
        )
        ToolbarIconAction(
            icon = Icons.Rounded.Terminal,
            label = "代码块",
            onClick = {
                onApplyTransform {
                    insertOrWrapMarkdown(
                        it,
                        prefix = "\n```bash\n",
                        suffix = "\n```\n",
                        placeholder = "# 粘贴命令或输出",
                    )
                }
            },
        )
        ToolbarIconAction(
            icon = Icons.Rounded.FormatQuote,
            label = "引用",
            onClick = {
                onApplyTransform { insertOrWrapMarkdown(it, prefix = "\n> ", placeholder = "引用内容") }
            },
        )
        ToolbarIconAction(
            icon = Icons.Rounded.Link,
            label = "链接",
            onClick = {
                onApplyTransform {
                    insertOrWrapMarkdown(it, prefix = "[", suffix = "](https://)", placeholder = "链接标题")
                }
            },
        )
        ToolbarIconAction(
            icon = Icons.Rounded.Tab,
            label = "多标签卡",
            onClick = {
                onApplyTransform { insertOrWrapMarkdown(it, prefix = BENCHMARK_TABS_SNIPPET) }
            },
        )
    }
}

@Composable
private fun ToolbarCompactChipAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
            .border(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                shape = shape,
            )
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
        )
    }
}

@Composable
private fun ToolbarIconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    val bg by animateColorAsState(
        targetValue = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "toolbarIconBg",
    )
    val tint by animateColorAsState(
        targetValue = if (highlighted) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "toolbarIconTint",
    )
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(shape)
            .background(bg)
            .then(
                if (highlighted) {
                    Modifier.border(
                        width = 0.8.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                        shape = shape,
                    )
                } else {
                    Modifier
                },
            )
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
internal fun InlineNodeSeekStickerPicker(
    onSelectStickerCode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val groups = NodeSeekStickerCatalog.groups
    var selectedGroupIndex by rememberSaveable { mutableIntStateOf(0) }
    val safeGroupIndex = selectedGroupIndex.coerceIn(0, groups.lastIndex)
    val activeGroupKey = groups[safeGroupIndex].first
    val stickers = remember(activeGroupKey) {
        NodeSeekStickerCatalog.stickersForGroup(activeGroupKey)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                elevation = PulseElevation.Card,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            groups.forEachIndexed { index, (_, title) ->
                val selected = safeGroupIndex == index
                val pillBg by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        ext.cardSurface
                    },
                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                    label = "inlineStickerTabBg",
                )
                val pillFg by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                    label = "inlineStickerTabFg",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(pillBg)
                        .border(
                            width = 0.9.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                            } else {
                                ext.cardBorder
                            },
                            shape = CircleShape,
                        )
                        .pulseBouncyClickable(
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.Tab,
                            onClick = { selectedGroupIndex = index },
                        )
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = pillFg,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        PulseGradientDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 50.dp),
            contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp),
        ) {
            items(stickers, key = { it.code }) { sticker ->
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(PulseArchitecturalShapes.MediumBadge)
                        .background(ext.cardSurface)
                        .border(
                            width = 0.8.dp,
                            color = ext.cardBorder.copy(alpha = 0.8f),
                            shape = PulseArchitecturalShapes.MediumBadge,
                        )
                        .pulseBouncyClickable(
                            pressedScale = NodePulseMotion.chipPressScale,
                            onClick = { onSelectStickerCode(sticker.code) },
                        )
                        .padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    NodeSeekStickerAsyncImage(
                        sticker = sticker,
                        preferStaticPreview = true,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
        }
    }
}

/**
 * Floor Jump & Multi-Page Navigation Modal Bottom Sheet.
 */
@Composable
internal fun FloorJumpBottomSheet(
    currentFloor: Int,
    estimatedTotalFloors: Int,
    currentPage: Int,
    totalPages: Int,
    opOnly: Boolean,
    reverseOrder: Boolean,
    onDismiss: () -> Unit,
    onJumpToFloor: (Int) -> Unit,
    onSelectPage: (Int) -> Unit,
    onToggleOpOnly: () -> Unit,
    onToggleReverseOrder: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxFloor = estimatedTotalFloors.coerceAtLeast(1)
    var sliderValue by remember(currentFloor, maxFloor) {
        mutableFloatStateOf(currentFloor.coerceIn(0, maxFloor).toFloat())
    }
    val previewFloor = sliderValue.roundToInt().coerceIn(0, maxFloor)
    val previewPage = if (previewFloor == 0) 1 else TopicDetailPage.pageForFloor(previewFloor)

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
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
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
                            text = "跳转楼层",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "当前第 $currentPage / $totalPages 页 · 共约 $estimatedTotalFloors 条回复",
                            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .then(
                            if (!NodePulseThemeTokens.isDark) {
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
                        .background(PulseBrushes.brandGradient())
                        .pulseBouncyClickable(
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.Button,
                            onClick = { onJumpToFloor(previewFloor) },
                        )
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (previewFloor == 0) "跳至主楼" else "跳至 #$previewFloor",
                        style = MaterialTheme.typography.labelLarge.withTabularNumbers(),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }

            // Live Slider Hero Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseHeroCard(
                        shape = PulseArchitecturalShapes.HeroBento,
                        elevation = PulseElevation.Card,
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (previewFloor == 0) "主楼 (OP)" else "#$previewFloor 楼",
                        style = NodePulseThemeTokens.extraTypography.metricValue,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    PulseAccentPill(
                        text = "对应第 $previewPage 页",
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                if (estimatedTotalFloors > 0) {
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        valueRange = 0f..maxFloor.toFloat(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Quick Action Pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FloorSheetQuickButton(
                    icon = Icons.Rounded.KeyboardDoubleArrowUp,
                    label = "直达主楼",
                    onClick = { onJumpToFloor(0) },
                    modifier = Modifier.weight(1f),
                )
                FloorSheetQuickButton(
                    icon = Icons.Rounded.KeyboardDoubleArrowDown,
                    label = "直达末楼",
                    onClick = {
                        if (estimatedTotalFloors > 0) {
                            onJumpToFloor(estimatedTotalFloors)
                        } else {
                            onSelectPage(totalPages)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FloorSheetToggleChip(
                    selected = opOnly,
                    icon = Icons.Rounded.Person,
                    label = "只看楼主",
                    onClick = onToggleOpOnly,
                    modifier = Modifier.weight(1f),
                )
                FloorSheetToggleChip(
                    selected = reverseOrder,
                    icon = Icons.Rounded.SwapVert,
                    label = if (reverseOrder) "当前倒序" else "倒序本页",
                    onClick = onToggleReverseOrder,
                    modifier = Modifier.weight(1f),
                )
            }

            // Page Grid (1..totalPages)
            if (totalPages > 1) {
                PulseGradientDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                Text(
                    text = "快速切换页码",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (p in 1..totalPages) {
                        val isActive = p == currentPage
                        val pageShape = PulseArchitecturalShapes.MediumBadge
                        val pageBgModifier = if (isActive) {
                            Modifier.background(PulseBrushes.brandGradient())
                        } else {
                            Modifier
                                .background(ext.cardSurface)
                                .border(
                                    width = 0.9.dp,
                                    color = ext.cardBorder,
                                    shape = pageShape,
                                )
                        }
                        Box(
                            modifier = Modifier
                                .clip(pageShape)
                                .then(pageBgModifier)
                                .pulseBouncyClickable(
                                    pressedScale = NodePulseMotion.chipPressScale,
                                    role = Role.Button,
                                    onClick = { onSelectPage(p) },
                                )
                                .padding(horizontal = 13.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "P$p",
                                style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun FloorSheetQuickButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    Row(
        modifier = modifier
            .pulseCard(
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                borderColor = ext.cardBorder,
                elevation = PulseElevation.Subtle,
            )
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun FloorSheetToggleChip(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val bg by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            ext.cardSurface
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "floorFilterBg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "floorFilterFg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        } else {
            ext.cardBorder
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "floorFilterBorder",
    )

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = CircleShape,
            )
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Switch,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = fg,
        )
    }
}

/**
 * Confirmation dialog before paid coin actions (`1 鸡腿` drumstick, `2 鸡腿` dislike, `5 鸡腿` OP pin).
 */
@Composable
internal fun PaidActionConfirmationDialog(
    action: PendingTopicPaidAction,
    viewerDrumsticks: Int?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDislike = action is PendingTopicPaidAction.DislikeReaction
    val floorNumber = when (action) {
        is PendingTopicPaidAction.DrumstickReaction -> action.floorNumber
        is PendingTopicPaidAction.DislikeReaction -> action.floorNumber
        is PendingTopicPaidAction.PinReply -> action.floorNumber
    }
    val floorLabel = if (floorNumber == 0) "主楼 (OP)" else "#$floorNumber 楼"

    val (icon, title, description) = when (action) {
        is PendingTopicPaidAction.DrumstickReaction -> Triple(
            NodeSeekDrumstickIcon,
            "确认投喂鸡腿？",
            "今日免费加鸡腿额度已用完。向 @${action.authorName} ($floorLabel) 投喂将消耗 ${action.coinCost} 个鸡腿，操作提交后不可撤销。",
        )
        is PendingTopicPaidAction.DislikeReaction -> Triple(
            Icons.Rounded.ThumbDown,
            "确认消耗鸡腿反对？",
            "对 @${action.authorName} ($floorLabel) 表示反对将消耗 ${action.coinCost} 个鸡腿，操作提交后不可撤销。",
        )
        is PendingTopicPaidAction.PinReply -> Triple(
            Icons.Rounded.PushPin,
            "确认置顶该回复？",
            "将 @${action.authorName} 的 $floorLabel 回复设为楼主置顶需消耗 ${action.coinCost} 个鸡腿（后续取消置顶免费）。",
        )
    }

    PulseConfirmDialog(
        onDismissRequest = onDismiss,
        title = title,
        message = description,
        confirmText = "确认消耗 ${action.coinCost} 鸡腿",
        onConfirm = onConfirm,
        dismissText = "取消",
        icon = icon,
        tone = if (isDislike) PulseDialogTone.DESTRUCTIVE else PulseDialogTone.WARM,
        centerHeader = true,
        extraContent = {
            if (viewerDrumsticks != null) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier
                            .clip(PulseArchitecturalShapes.SmallChip)
                            .background(ext.drumstickContainer.copy(alpha = 0.78f))
                            .border(
                                width = 0.8.dp,
                                color = ext.drumstickGoldEnd.copy(alpha = 0.36f),
                                shape = PulseArchitecturalShapes.SmallChip,
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = NodeSeekDrumstickIcon,
                            contentDescription = null,
                            tint = ext.onDrumstickContainer,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "当前账号余额：$viewerDrumsticks 鸡腿",
                            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                            color = ext.onDrumstickContainer,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        },
    )
}

/**
 * NodeImage API Key setup dialog triggered when uploading an image while `nodeImageApiKey` is blank.
 */
@Composable
internal fun NodeImageApiKeySetupDialog(
    onSaveAndContinue: (String) -> Unit,
    onDismiss: () -> Unit,
    initialApiKey: String = "",
    customMessage: String? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    var apiKey by rememberSaveable(initialApiKey) { mutableStateOf(initialApiKey) }

    PulseAlertDialog(
        onDismissRequest = onDismiss,
        title = "配置 NodeImage 图床密钥",
        message = customMessage
            ?: "图片上传使用官方 NodeImage 图床（api.nodeimage.com）。请粘贴您的 X-API-Key，保存后将自动继续上传所选图片。",
        icon = Icons.Rounded.Key,
        tone = PulseDialogTone.PRIMARY,
        centerHeader = true,
        confirmText = "保存并上传",
        onConfirm = { onSaveAndContinue(apiKey) },
        confirmEnabled = apiKey.trim().isNotEmpty(),
        dismissText = "暂不上传",
    ) {
        PulseTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            singleLine = true,
            label = "NodeImage X-API-Key",
            placeholder = "32–128 位十六进制密钥",
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Key,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            containerColor = ext.cardSurface,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Editorial Action Cards for `TopicAccessException` (`SignInRequired`, `CloudflareChallengeRequired`,
 * `LevelRequired`, `PrivateTopic`, and general offline/network retry).
 */
@Composable
internal fun TopicAccessRestrictionCard(
    error: TopicAccessUiError,
    topicId: Long,
    session: AccountSession?,
    onRequestWebViewAuth: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val (icon, title, body) = when (error) {
        TopicAccessUiError.SignInRequired -> Triple(
            Icons.AutoMirrored.Rounded.Login,
            "该主题需要登录访问",
            "此讨论帖（如内版或受限版块）仅向已登录的 NodeSeek 注册会员开放。完成官方网页登录后即可无缝阅读与互动。",
        )
        TopicAccessUiError.CloudflareChallengeRequired -> Triple(
            Icons.Rounded.Shield,
            "需要完成浏览器安全验证",
            "NodeSeek 当前触发了安全访问保护。请在应用内官方验证页手动完成验证，会话凭据将自动同步。",
        )
        is TopicAccessUiError.LevelRequired -> Triple(
            Icons.Rounded.WorkspacePremium,
            error.minimumLevel?.let { "需要达到 Lv.$it 阅读权限" } ?: "当前阅读等级不足",
            if (session != null) {
                "作者为本帖设置了阅读门槛。您当前为 Lv.${session.level}（${session.drumsticks} 鸡腿），积累更多鸡腿升级后即可解锁。"
            } else {
                "作者为本帖设置了阅读等级门槛。您当前处于未登录访客状态，请先登录已达标的 NodeSeek 账号。"
            },
        )
        TopicAccessUiError.PrivateTopic -> Triple(
            Icons.Rounded.Lock,
            "私有主题 (Lv.255)",
            "该主题已被作者设为私有归档状态，仅作者本人或特定管理员可见。",
        )
        is TopicAccessUiError.NetworkOrGeneric -> Triple(
            Icons.Rounded.WifiOff,
            "暂时无法加载主题内容",
            error.message,
        )
    }

    val accentTint: Color
    val iconContainer: Color
    val iconContent: Color
    when (error) {
        TopicAccessUiError.SignInRequired -> {
            accentTint = MaterialTheme.colorScheme.primary
            iconContainer = MaterialTheme.colorScheme.primaryContainer
            iconContent = MaterialTheme.colorScheme.onPrimaryContainer
        }
        TopicAccessUiError.CloudflareChallengeRequired -> {
            accentTint = MaterialTheme.colorScheme.tertiary
            iconContainer = MaterialTheme.colorScheme.tertiaryContainer
            iconContent = MaterialTheme.colorScheme.onTertiaryContainer
        }
        is TopicAccessUiError.LevelRequired -> {
            accentTint = ext.drumstickGoldEnd
            iconContainer = ext.drumstickContainer
            iconContent = ext.onDrumstickContainer
        }
        TopicAccessUiError.PrivateTopic -> {
            accentTint = MaterialTheme.colorScheme.secondary
            iconContainer = MaterialTheme.colorScheme.secondaryContainer
            iconContent = MaterialTheme.colorScheme.onSecondaryContainer
        }
        is TopicAccessUiError.NetworkOrGeneric -> {
            accentTint = MaterialTheme.colorScheme.error
            iconContainer = MaterialTheme.colorScheme.errorContainer
            iconContent = MaterialTheme.colorScheme.onErrorContainer
        }
    }

    PulseStaggeredEntrance(index = 0) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 28.dp)
                .pulseHeroCard(
                    accentColor = accentTint,
                    shape = PulseArchitecturalShapes.HeroBento,
                )
                .padding(horizontal = 24.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                accentTint.copy(alpha = 0.22f),
                                accentTint.copy(alpha = 0.04f),
                            ),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        color = accentTint.copy(alpha = 0.26f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(iconContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconContent,
                        modifier = Modifier.size(27.dp),
                    )
                }
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (error is TopicAccessUiError.LevelRequired && error.minimumLevel != null) {
                RankMicroBadge(level = error.minimumLevel.coerceIn(0, 6), showPersona = true)
            }

            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(4.dp))

            when (error) {
                TopicAccessUiError.SignInRequired -> {
                    Button(
                        onClick = { onRequestWebViewAuth("https://www.nodeseek.com/signIn.html") },
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Login,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("打开官方登录页", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = onRetry,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("我已登录，重新加载", maxLines = 1, softWrap = false)
                    }
                }

                TopicAccessUiError.CloudflareChallengeRequired -> {
                    Button(
                        onClick = { onRequestWebViewAuth("https://www.nodeseek.com/post-$topicId-1") },
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("前往完成安全验证", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = onRetry,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("已完成验证，刷新帖子", maxLines = 1, softWrap = false)
                    }
                }

                is TopicAccessUiError.LevelRequired -> {
                    if (session == null) {
                        Button(
                            onClick = { onRequestWebViewAuth("https://www.nodeseek.com/signIn.html") },
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("登录高阶账号查看", maxLines = 1, softWrap = false)
                        }
                    }
                    OutlinedButton(
                        onClick = onBack,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("返回上一页", maxLines = 1, softWrap = false)
                    }
                }

                TopicAccessUiError.PrivateTopic -> {
                    FilledTonalButton(
                        onClick = onBack,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("返回上一页", maxLines = 1, softWrap = false)
                    }
                }

                is TopicAccessUiError.NetworkOrGeneric -> {
                    Button(
                        onClick = onRetry,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("重新加载", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}
