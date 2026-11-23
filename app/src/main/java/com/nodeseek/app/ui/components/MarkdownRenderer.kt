@file:OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
)

package com.nodeseek.app.ui.components

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.HowToVote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.asPainter
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Scale as CoilScale
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseHeroCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Native Jetpack Compose Markdown + NodeSeek Forum Extension Renderer (`MarkdownRenderer`).
 *
 * Capabilities:
 * - Headings (`#`..`######` & Setext `===`/`---`), paragraphs, horizontal rules
 * - Optional free text selection, clickable `@username`, `#floor`, reference links, and `/jump?to=` unwrapping
 * - Linked badge rows (`[![alt](img)](url)` / `<a href><img /></a>`) & inline HTML (`<br>`, `<b>`, `<kbd>`, `<details>`)
 * - Electric Cobalt-spine blockquotes (`>`) without double-pass IntrinsicSize overhead
 * - Bulleted, numbered, and task lists (`- [x]` / `- [ ]`)
 * - Syntax-tinted code blocks (` ``` ` / `~~~`) with 1-tap Copy button + ANSI SGR escape rendering
 * - `:::: tabs` / `::: tab-item Title` interactive benchmark report tabs
 * - GFM pipe tables (with or without outer pipes) with column alignment & horizontal scroll
 * - Inline NodeSeek `:sticker:` rendering (`ac` / `yct` / `xhj` / `emoji`)
 * - Embedded `nsapp://vote` & `nsapp://stardust-receive` widget cards
 * - Zoomable Coil image cards with full-screen pinch-to-zoom Lightbox dialog
 */
private data class ImageActionTarget(
    val imageUrl: String,
    val altText: String,
    val linkUrl: String? = null,
)

@Immutable
private data class CachedMarkdownImageMeta(
    val aspectRatio: Float? = null,
    val isTinyRaster: Boolean = false,
    val loadedSuccess: Boolean = false,
)

private val LocalInsideTabsContainer = compositionLocalOf { false }
private val LocalMarkdownSelectionEnabled = compositionLocalOf { false }
private val LocalMarkdownUrlLongClick = compositionLocalOf<((String) -> Unit)?> { null }
private val LocalMarkdownImageTargetWidthPx = compositionLocalOf<Int?> { null }
private val LocalMarkdownImageMetaCache = compositionLocalOf<MutableMap<String, CachedMarkdownImageMeta>?> { null }
private val LocalMarkdownPrefetchJobs = compositionLocalOf<MutableMap<String, Deferred<ImageResult>>?> { null }
private val LocalMarkdownActiveCardUrls = compositionLocalOf<MutableSet<String>?> { null }

@Composable
private fun rememberMarkdownImageSaveAction(): (String, String) -> Unit {
    val context = LocalContext.current
    var pendingSave by remember { mutableStateOf<Pair<String, String>?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val queued = pendingSave
        pendingSave = null
        if (granted && queued != null) {
            MarkdownImageSaver.saveImageToGallery(
                context = context,
                rawImageUrl = queued.first,
                altText = queued.second,
            )
        } else if (!granted) {
            Toast.makeText(context, "需要存储权限才能保存图片到相册", Toast.LENGTH_SHORT).show()
        }
    }

    return remember(context, permissionLauncher) {
        { imageUrl, altText ->
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingSave = imageUrl to altText
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                MarkdownImageSaver.saveImageToGallery(
                    context = context,
                    rawImageUrl = imageUrl,
                    altText = altText,
                )
            }
        }
    }
}

private const val ASYNC_MARKDOWN_PARSE_THRESHOLD_CHARS = 32_000
private const val QUICK_MARKDOWN_PREVIEW_CHARS = 12_000

@Composable
fun MarkdownRenderer(
    markdown: String,
    modifier: Modifier = Modifier,
    bodyStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    linkColor: Color = MaterialTheme.colorScheme.primary,
    compactSignatureMode: Boolean = false,
    inlineSignatureFooter: Boolean = false,
    enableTextSelection: Boolean = false,
    onMentionClick: ((String) -> Unit)? = null,
    onFloorClick: ((Int) -> Unit)? = null,
    onUrlClick: ((String) -> Unit)? = null,
    onUrlLongClick: ((String) -> Unit)? = null,
) {
    val isInspection = LocalInspectionMode.current
    val offloadParse = !isInspection && markdown.length > ASYNC_MARKDOWN_PARSE_THRESHOLD_CHARS
    var asyncParsedKey by remember { mutableStateOf("") }
    var asyncParsedDoc by remember { mutableStateOf<ParsedMarkdownDocument?>(null) }

    val targetParseKey = if (offloadParse) "${markdown.length}_${markdown.hashCode()}" else ""
    LaunchedEffect(offloadParse, targetParseKey, markdown) {
        if (offloadParse) {
            val doc = withContext(Dispatchers.Default) {
                parseNodeSeekMarkdownDocument(markdown)
            }
            asyncParsedDoc = doc
            asyncParsedKey = targetParseKey
        }
    }

    val parsedDoc = when {
        !offloadParse -> remember(markdown) { parseNodeSeekMarkdownDocument(markdown) }
        asyncParsedKey == targetParseKey && asyncParsedDoc != null -> asyncParsedDoc!!
        else -> remember(targetParseKey) {
            parseNodeSeekMarkdownDocument(markdown.take(QUICK_MARKDOWN_PREVIEW_CHARS))
        }
    }
    var lightboxImageState by remember { mutableStateOf<Pair<String, String>?>(null) }
    var actionSheetImage by remember { mutableStateOf<ImageActionTarget?>(null) }
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val saveImage = rememberMarkdownImageSaveAction()

    val effectiveUrlClick: (String) -> Unit = remember(onUrlClick, uriHandler) {
        { rawUrl ->
            val unwrapped = unwrapNodeSeekJumpUrl(rawUrl)
            if (onUrlClick != null) {
                onUrlClick(unwrapped)
            } else {
                runCatching { uriHandler.openUri(unwrapped) }
            }
        }
    }

    val effectiveUrlLongClick: ((String) -> Unit)? = remember(onUrlLongClick, haptics) {
        onUrlLongClick?.let { handler ->
            { rawUrl ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                handler(unwrapNodeSeekJumpUrl(rawUrl))
            }
        }
    }

    val triggerImageLongPress: (String, String, String?) -> Unit = remember(haptics) {
        { url, alt, linkUrl ->
            if (normalizeMarkdownImageUrl(url).isNotBlank()) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                actionSheetImage = ImageActionTarget(
                    imageUrl = url,
                    altText = alt,
                    linkUrl = linkUrl,
                )
            }
        }
    }

    val contentColumn: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(
                when {
                    inlineSignatureFooter -> 3.dp
                    compactSignatureMode -> 6.dp
                    else -> 11.dp
                },
            ),
        ) {
            MarkdownBlockStream(
                blocks = parsedDoc.blocks,
                referenceLinks = parsedDoc.referenceLinks,
                bodyStyle = bodyStyle,
                textColor = textColor,
                linkColor = linkColor,
                compactSignatureMode = compactSignatureMode,
                inlineSignatureFooter = inlineSignatureFooter,
                onMentionClick = onMentionClick,
                onFloorClick = onFloorClick,
                onUrlClick = effectiveUrlClick,
                onImageClick = { url, alt -> lightboxImageState = url to alt },
                onImageLongClick = triggerImageLongPress,
            )
        }
    }

    CompositionLocalProvider(
        LocalMarkdownSelectionEnabled provides enableTextSelection,
        LocalMarkdownUrlLongClick provides effectiveUrlLongClick,
    ) {
        if (enableTextSelection) {
            SelectionContainer(modifier = modifier.fillMaxWidth()) {
                contentColumn()
            }
        } else {
            Box(modifier = modifier.fillMaxWidth()) {
                contentColumn()
            }
        }
    }

    lightboxImageState?.let { (imageUrl, altText) ->
        PulseImageLightboxDialog(
            imageUrl = imageUrl,
            altText = altText,
            onDismiss = { lightboxImageState = null },
            onOpenExternal = { effectiveUrlClick(imageUrl) },
        )
    }

    actionSheetImage?.let { target ->
        val linkedTargetUrl = target.linkUrl?.takeIf { it.isNotBlank() }
        MarkdownImageActionBottomSheet(
            target = target,
            onDismiss = { actionSheetImage = null },
            onDownload = {
                actionSheetImage = null
                saveImage(target.imageUrl, target.altText)
            },
            onViewFullscreen = {
                actionSheetImage = null
                lightboxImageState = target.imageUrl to target.altText
            },
            onCopyUrl = {
                val urlToCopy = normalizeMarkdownImageUrl(target.imageUrl).ifBlank { target.imageUrl }
                copyPlainTextToClipboard(context, urlToCopy)
                Toast.makeText(context, "已复制图片链接", Toast.LENGTH_SHORT).show()
                actionSheetImage = null
            },
            onOpenExternal = {
                val destination = linkedTargetUrl
                    ?: normalizeMarkdownImageUrl(target.imageUrl).ifBlank { target.imageUrl }
                actionSheetImage = null
                effectiveUrlClick(destination)
            },
            onInspectLinkUrl = if (linkedTargetUrl != null && effectiveUrlLongClick != null) {
                {
                    actionSheetImage = null
                    effectiveUrlLongClick(linkedTargetUrl)
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun MarkdownBlockStream(
    blocks: List<MdBlock>,
    referenceLinks: Map<String, Pair<String, String?>>,
    bodyStyle: TextStyle,
    textColor: Color,
    linkColor: Color,
    compactSignatureMode: Boolean,
    inlineSignatureFooter: Boolean,
    onMentionClick: ((String) -> Unit)?,
    onFloorClick: ((Int) -> Unit)?,
    onUrlClick: (String) -> Unit,
    onImageClick: (String, String) -> Unit,
    onImageLongClick: (String, String, String?) -> Unit,
) {
    blocks.forEach { block ->
        when (block) {
            is MdBlock.Heading -> HeadingBlockView(
                block = block,
                referenceLinks = referenceLinks,
                bodyStyle = bodyStyle,
                textColor = textColor,
                linkColor = linkColor,
                compactSignatureMode = compactSignatureMode,
                inlineSignatureFooter = inlineSignatureFooter,
                onMentionClick = onMentionClick,
                onFloorClick = onFloorClick,
                onUrlClick = onUrlClick,
            )

            is MdBlock.Paragraph -> ParagraphBlockView(
                rawText = block.text,
                referenceLinks = referenceLinks,
                style = bodyStyle,
                color = textColor,
                linkColor = linkColor,
                compactSignatureMode = compactSignatureMode,
                inlineSignatureFooter = inlineSignatureFooter,
                onMentionClick = onMentionClick,
                onFloorClick = onFloorClick,
                onUrlClick = onUrlClick,
                onImageClick = onImageClick,
                onImageLongClick = onImageLongClick,
            )

            is MdBlock.Blockquote -> CobaltBlockquoteView(
                nestedBlocks = block.children,
                referenceLinks = referenceLinks,
                bodyStyle = bodyStyle,
                textColor = textColor,
                linkColor = linkColor,
                compactSignatureMode = compactSignatureMode,
                inlineSignatureFooter = inlineSignatureFooter,
                onMentionClick = onMentionClick,
                onFloorClick = onFloorClick,
                onUrlClick = onUrlClick,
                onImageClick = onImageClick,
                onImageLongClick = onImageLongClick,
            )

            is MdBlock.CodeFence -> PulseCodeBlockCard(
                language = block.language,
                code = block.code,
            )

            is MdBlock.ImageCard -> PulseZoomableImageCard(
                imageUrl = block.url,
                altText = block.alt,
                compactMode = compactSignatureMode,
                signatureFooterMode = inlineSignatureFooter,
                onClick = {
                    if (!block.linkUrl.isNullOrBlank()) {
                        onUrlClick(unwrapNodeSeekJumpUrl(block.linkUrl))
                    } else {
                        onImageClick(block.url, block.alt)
                    }
                },
                onLongClick = { onImageLongClick(block.url, block.alt, block.linkUrl) },
            )

            is MdBlock.ImageRow -> MarkdownImageRowView(
                items = block.items,
                compactSignatureMode = compactSignatureMode,
                inlineSignatureFooter = inlineSignatureFooter,
                linkColor = linkColor,
                onUrlClick = onUrlClick,
                onImageClick = onImageClick,
                onImageLongClick = onImageLongClick,
            )

            is MdBlock.ListContainer -> MarkdownListBlockView(
                items = block.items,
                referenceLinks = referenceLinks,
                bodyStyle = bodyStyle,
                textColor = textColor,
                linkColor = linkColor,
                compactSignatureMode = compactSignatureMode,
                inlineSignatureFooter = inlineSignatureFooter,
                onMentionClick = onMentionClick,
                onFloorClick = onFloorClick,
                onUrlClick = onUrlClick,
                onImageClick = onImageClick,
                onImageLongClick = onImageLongClick,
            )

            is MdBlock.Table -> GfmTableCard(
                table = block,
                referenceLinks = referenceLinks,
                bodyStyle = bodyStyle,
                textColor = textColor,
                linkColor = linkColor,
                inlineSignatureFooter = inlineSignatureFooter,
                onMentionClick = onMentionClick,
                onFloorClick = onFloorClick,
                onUrlClick = onUrlClick,
                onImageClick = onImageClick,
                onImageLongClick = onImageLongClick,
            )

            is MdBlock.TabsContainer -> NodeSeekTabsCard(
                tabs = block.tabs,
                referenceLinks = referenceLinks,
                bodyStyle = bodyStyle,
                textColor = textColor,
                linkColor = linkColor,
                compactSignatureMode = compactSignatureMode,
                inlineSignatureFooter = inlineSignatureFooter,
                onMentionClick = onMentionClick,
                onFloorClick = onFloorClick,
                onUrlClick = onUrlClick,
                onImageClick = onImageClick,
                onImageLongClick = onImageLongClick,
            )

            is MdBlock.DetailsSpoiler -> DetailsSpoilerView(
                summary = block.summary,
                children = block.children,
                referenceLinks = referenceLinks,
                bodyStyle = bodyStyle,
                textColor = textColor,
                linkColor = linkColor,
                compactSignatureMode = compactSignatureMode,
                inlineSignatureFooter = inlineSignatureFooter,
                onMentionClick = onMentionClick,
                onFloorClick = onFloorClick,
                onUrlClick = onUrlClick,
                onImageClick = onImageClick,
                onImageLongClick = onImageLongClick,
            )

            is MdBlock.SpecialWidget -> EmbeddedNsAppWidgetCard(
                widget = block,
                onUrlClick = onUrlClick,
            )

            MdBlock.HorizontalRule -> PulseGradientDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = if (inlineSignatureFooter) 0.5.dp else 1.dp,
                modifier = Modifier.padding(
                    vertical = when {
                        inlineSignatureFooter -> 2.dp
                        compactSignatureMode -> 4.dp
                        else -> 6.dp
                    },
                ),
            )
        }
    }
}

@Composable
private fun HeadingBlockView(
    block: MdBlock.Heading,
    referenceLinks: Map<String, Pair<String, String?>>,
    bodyStyle: TextStyle,
    textColor: Color,
    linkColor: Color,
    compactSignatureMode: Boolean,
    inlineSignatureFooter: Boolean,
    onMentionClick: ((String) -> Unit)?,
    onFloorClick: ((Int) -> Unit)?,
    onUrlClick: (String) -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    if (inlineSignatureFooter) {
        RichInlineText(
            rawText = block.text,
            referenceLinks = referenceLinks,
            style = bodyStyle.copy(fontWeight = FontWeight.SemiBold),
            color = textColor,
            linkColor = linkColor,
            underlineLinks = false,
            compactInlineSticker = true,
            onMentionClick = onMentionClick,
            onFloorClick = onFloorClick,
            onUrlClick = onUrlClick,
            onInlineImageClick = null,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    if (compactSignatureMode) {
        val compactStyle = when (block.level) {
            1 -> MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
            2 -> MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
            else -> MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (block.level <= 2) {
                Box(
                    modifier = Modifier
                        .width(2.5.dp)
                        .height(12.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(linkColor, ext.brandGradientEnd),
                            ),
                        ),
                )
            }
            RichInlineText(
                rawText = block.text,
                referenceLinks = referenceLinks,
                style = compactStyle,
                color = textColor,
                linkColor = linkColor,
                onMentionClick = onMentionClick,
                onFloorClick = onFloorClick,
                onUrlClick = onUrlClick,
                onInlineImageClick = null,
                modifier = Modifier.weight(1f),
            )
        }
        return
    }

    val style = when (block.level) {
        1 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold)
        2 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        3 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        else -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (block.level <= 2) 8.dp else 3.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (block.level in 2..3) {
                Box(
                    modifier = Modifier
                        .padding(top = if (block.level == 2) 5.dp else 4.dp)
                        .width(if (block.level == 2) 3.5.dp else 3.dp)
                        .height(if (block.level == 2) 17.dp else 14.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(linkColor, ext.brandGradientEnd),
                            ),
                        ),
                )
            }
            RichInlineText(
                rawText = block.text,
                referenceLinks = referenceLinks,
                style = style,
                color = textColor,
                linkColor = linkColor,
                onMentionClick = onMentionClick,
                onFloorClick = onFloorClick,
                onUrlClick = onUrlClick,
                onInlineImageClick = null,
                modifier = Modifier.weight(1f),
            )
        }
        if (block.level == 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(linkColor, ext.brandGradientEnd),
                            ),
                        ),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(0.8.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    linkColor.copy(alpha = 0.28f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
            }
        } else if (block.level == 2) {
            PulseGradientDivider(
                color = linkColor.copy(alpha = 0.45f),
                thickness = 0.8.dp,
            )
        }
    }
}

/**
 * Editorial Cobalt-Spine Blockquote (`4.dp` gradient left spine + soft tonal wash drawn via `drawBehind` to avoid IntrinsicSize double-measure).
 */
@Composable
private fun CobaltBlockquoteView(
    nestedBlocks: List<MdBlock>,
    referenceLinks: Map<String, Pair<String, String?>>,
    bodyStyle: TextStyle,
    textColor: Color,
    linkColor: Color,
    compactSignatureMode: Boolean,
    inlineSignatureFooter: Boolean,
    onMentionClick: ((String) -> Unit)?,
    onFloorClick: ((Int) -> Unit)?,
    onUrlClick: (String) -> Unit,
    onImageClick: (String, String) -> Unit,
    onImageLongClick: (String, String, String?) -> Unit,
) {
    if (inlineSignatureFooter) {
        val barColor = linkColor.copy(alpha = 0.55f)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(
                        color = barColor,
                        size = Size(width = 2.dp.toPx(), height = size.height),
                    )
                }
                .padding(start = 8.dp, top = 1.dp, bottom = 1.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MarkdownBlockStream(
                blocks = nestedBlocks,
                referenceLinks = referenceLinks,
                bodyStyle = bodyStyle,
                textColor = textColor,
                linkColor = linkColor,
                compactSignatureMode = compactSignatureMode,
                inlineSignatureFooter = true,
                onMentionClick = onMentionClick,
                onFloorClick = onFloorClick,
                onUrlClick = onUrlClick,
                onImageClick = onImageClick,
                onImageLongClick = onImageLongClick,
            )
        }
        return
    }

    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 16.dp, bottomEnd = 16.dp)
    val baseSurface = MaterialTheme.colorScheme.surfaceContainerLow
    val tintedStart = blendColor(
        source = baseSurface,
        target = linkColor,
        fraction = if (isDark) 0.14f else 0.065f,
    )
    val bgBrush = Brush.horizontalGradient(colors = listOf(tintedStart, baseSurface))
    val spineBrush = Brush.verticalGradient(colors = listOf(linkColor, ext.brandGradientEnd))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(
                width = 0.8.dp,
                color = ext.cardBorder.copy(alpha = 0.65f),
                shape = shape,
            )
            .drawBehind {
                drawRect(brush = bgBrush)
                drawRect(
                    brush = spineBrush,
                    size = Size(width = 4.dp.toPx(), height = size.height),
                )
            }
            .padding(start = 16.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MarkdownBlockStream(
            blocks = nestedBlocks,
            referenceLinks = referenceLinks,
            bodyStyle = bodyStyle.copy(
                fontSize = (bodyStyle.fontSize.value * 0.94f).sp,
                lineHeight = (bodyStyle.lineHeight.value * 0.94f).sp,
            ),
            textColor = textColor,
            linkColor = linkColor,
            compactSignatureMode = compactSignatureMode,
            inlineSignatureFooter = false,
            onMentionClick = onMentionClick,
            onFloorClick = onFloorClick,
            onUrlClick = onUrlClick,
            onImageClick = onImageClick,
            onImageLongClick = onImageLongClick,
        )
    }
}

/**
 * Syntax-tinted & ANSI-aware Code Block Card with terminal header chrome and 1-tap Copy button.
 */
private val TERMINAL_CODE_LANGUAGES = setOf(
    "ansi",
    "text",
    "txt",
    "console",
    "terminal",
    "log",
)

private const val MAX_MEASURED_CODE_CHARS = 16_000
private const val MIN_TERMINAL_FIT_SCALE = 0.28f

internal fun isTerminalReportCodeBlock(
    language: String,
    code: String,
    hasAnsiEscapes: Boolean,
): Boolean {
    if (hasAnsiEscapes || language.equals("ansi", ignoreCase = true)) return true
    val normalizedLang = language.trim().lowercase()
    if (normalizedLang.isNotEmpty() && normalizedLang !in TERMINAL_CODE_LANGUAGES) return false
    return containsTerminalBoxReportArt(code)
}

internal fun isTerminalReportDisplayMode(
    language: String,
    code: String,
    hasAnsiEscapes: Boolean,
    insideTabsContainer: Boolean = false,
): Boolean {
    if (isTerminalReportCodeBlock(language = language, code = code, hasAnsiEscapes = hasAnsiEscapes)) {
        return true
    }
    val normalizedLang = language.trim().lowercase()
    return insideTabsContainer && (normalizedLang.isEmpty() || normalizedLang in TERMINAL_CODE_LANGUAGES)
}

internal fun containsTerminalBoxReportArt(code: String): Boolean {
    var boxCharCount = 0
    for (ch in code) {
        if (ch in "─│┌┐└┘╭╮╰╯├┤┬┴┼═║") {
            boxCharCount++
            if (boxCharCount >= 4) return true
        }
    }
    return false
}

private fun maxMeasuredLineWidthPx(layoutResult: TextLayoutResult): Int {
    var maxWidth = layoutResult.size.width
    for (line in 0 until layoutResult.lineCount) {
        val lineWidth = ceil((layoutResult.getLineRight(line) - layoutResult.getLineLeft(line)).toDouble()).toInt()
        if (lineWidth > maxWidth) maxWidth = lineWidth
    }
    return maxWidth
}

private fun buildSampleForWidthMeasure(styledText: AnnotatedString): AnnotatedString {
    if (styledText.length <= MAX_MEASURED_CODE_CHARS) return styledText
    val topLongest = styledText.text
        .lineSequence()
        .sortedByDescending { it.length }
        .take(60)
        .joinToString("\n")
    return AnnotatedString(topLongest)
}

private fun computeFittedMonospaceScale(
    textMeasurer: TextMeasurer,
    styledText: AnnotatedString,
    baseStyle: TextStyle,
    availableWidthPx: Int,
): Float {
    if (availableWidthPx <= 0 || styledText.isEmpty()) return 1f
    val measureText = buildSampleForWidthMeasure(styledText)
    val baseLayout = textMeasurer.measure(
        text = measureText,
        style = baseStyle,
        softWrap = false,
        maxLines = Int.MAX_VALUE,
    )
    val baseMaxWidthPx = maxMeasuredLineWidthPx(baseLayout)
    if (baseMaxWidthPx <= availableWidthPx || baseMaxWidthPx <= 0) return 1f

    var scale = (availableWidthPx.toFloat() / baseMaxWidthPx.toFloat()).coerceIn(MIN_TERMINAL_FIT_SCALE, 1f)
    val baseFontSize = if (baseStyle.fontSize != TextUnit.Unspecified) baseStyle.fontSize else 13.sp
    val baseLineHeight = if (baseStyle.lineHeight != TextUnit.Unspecified) baseStyle.lineHeight else 19.5.sp
    val checkLayout = textMeasurer.measure(
        text = measureText,
        style = baseStyle.copy(
            fontSize = baseFontSize * scale,
            lineHeight = baseLineHeight * scale,
        ),
        softWrap = false,
        maxLines = Int.MAX_VALUE,
    )
    val checkMaxWidthPx = maxMeasuredLineWidthPx(checkLayout)
    if (checkMaxWidthPx > availableWidthPx && checkMaxWidthPx > 0) {
        scale = (scale * (availableWidthPx.toFloat() / checkMaxWidthPx.toFloat()) * 0.985f)
            .coerceIn(MIN_TERMINAL_FIT_SCALE, 1f)
    }
    return scale
}

private const val ASYNC_CODE_PARSE_THRESHOLD_CHARS = 12_000
private const val QUICK_CODE_PREVIEW_CHARS = 6_000

@Composable
fun PulseCodeBlockCard(
    language: String,
    code: String,
    modifier: Modifier = Modifier,
    insideTabsContainer: Boolean = LocalInsideTabsContainer.current,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val codeTypography = NodePulseThemeTokens.extraTypography
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val isInspection = LocalInspectionMode.current
    val textMeasurer = rememberTextMeasurer()
    val selectionEnabled = LocalMarkdownSelectionEnabled.current
    var copied by remember { mutableStateOf(false) }
    var maxRenderedLines by remember(code) {
        mutableIntStateOf(AnsiAndCodeFormatter.DEFAULT_INITIAL_MAX_CODE_LINES)
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }

    val hasAnsi = remember(code) { AnsiAndCodeFormatter.containsAnsiEscapes(code) }
    val isTerminalReport = remember(language, code, hasAnsi, insideTabsContainer) {
        isTerminalReportDisplayMode(
            language = language,
            code = code,
            hasAnsiEscapes = hasAnsi,
            insideTabsContainer = insideTabsContainer,
        )
    }
    val showHeaderChrome = !isTerminalReport
    var fitToWidth by remember(code, isTerminalReport) { mutableStateOf(isTerminalReport) }

    val offloadCodeParse = !isInspection && code.length > ASYNC_CODE_PARSE_THRESHOLD_CHARS
    val codeCacheTag = if (offloadCodeParse) {
        "${code.length}_${code.hashCode()}_${hasAnsi}_${isTerminalReport}_$maxRenderedLines"
    } else {
        ""
    }
    var asyncChunkTag by remember { mutableStateOf("") }
    var asyncChunked by remember { mutableStateOf<AnsiAndCodeFormatter.ChunkedStyledCode?>(null) }

    LaunchedEffect(offloadCodeParse, codeCacheTag, ext.onCodeSurface) {
        if (offloadCodeParse) {
            val built = withContext(Dispatchers.Default) {
                AnsiAndCodeFormatter.buildStyledCodeChunks(
                    code = code,
                    hasAnsi = hasAnsi,
                    isTerminalReport = isTerminalReport,
                    defaultForeground = ext.onCodeSurface,
                    linesPerChunk = AnsiAndCodeFormatter.DEFAULT_CODE_CHUNK_LINES,
                    maxLines = maxRenderedLines,
                )
            }
            asyncChunked = built
            asyncChunkTag = codeCacheTag
        }
    }

    val chunkedCode = when {
        !offloadCodeParse -> remember(code, hasAnsi, isTerminalReport, ext.onCodeSurface, maxRenderedLines) {
            AnsiAndCodeFormatter.buildStyledCodeChunks(
                code = code,
                hasAnsi = hasAnsi,
                isTerminalReport = isTerminalReport,
                defaultForeground = ext.onCodeSurface,
                linesPerChunk = AnsiAndCodeFormatter.DEFAULT_CODE_CHUNK_LINES,
                maxLines = maxRenderedLines,
            )
        }
        asyncChunkTag == codeCacheTag && asyncChunked != null -> asyncChunked!!
        else -> remember(codeCacheTag, ext.onCodeSurface) {
            AnsiAndCodeFormatter.buildStyledCodeChunks(
                code = code.take(QUICK_CODE_PREVIEW_CHARS),
                hasAnsi = hasAnsi,
                isTerminalReport = isTerminalReport,
                defaultForeground = ext.onCodeSurface,
                linesPerChunk = AnsiAndCodeFormatter.DEFAULT_CODE_CHUNK_LINES,
                maxLines = AnsiAndCodeFormatter.DEFAULT_CODE_CHUNK_LINES,
            )
        }
    }
    val measureAnnotatedSample = remember(chunkedCode.plainSampleForMeasure) {
        AnnotatedString(chunkedCode.plainSampleForMeasure)
    }
    val headerLabel = remember(language, hasAnsi) {
        when {
            language.isNotBlank() -> language.lowercase()
            hasAnsi -> "terminal · ansi"
            else -> "code"
        }
    }
    val copyBg by animateColorAsState(
        targetValue = if (copied) Color(0xFF134E3A) else Color.White.copy(alpha = 0.08f),
        label = "codeCopyBg",
    )
    val copyFg by animateColorAsState(
        targetValue = if (copied) Color(0xFF6EE7B7) else ext.onCodeSurface,
        label = "codeCopyFg",
    )
    val copyBorderColor by animateColorAsState(
        targetValue = if (copied) Color(0xFF34D399).copy(alpha = 0.45f) else Color.White.copy(alpha = 0.12f),
        label = "codeCopyBorder",
    )
    val cardBorderBrush = Brush.verticalGradient(
        colors = listOf(Color.White.copy(alpha = 0.15f), ext.codeBorder),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (!isDark) {
                    Modifier.shadow(
                        elevation = PulseElevation.Card,
                        shape = PulseArchitecturalShapes.CodeBlock,
                        clip = false,
                        ambientColor = ext.softShadowColor,
                        spotColor = ext.softShadowColor,
                    )
                } else {
                    Modifier
                }
            )
            .clip(PulseArchitecturalShapes.CodeBlock)
            .background(ext.codeSurface)
            .border(1.dp, cardBorderBrush, PulseArchitecturalShapes.CodeBlock)
            .then(
                if (!showHeaderChrome && !selectionEnabled) {
                    Modifier.pointerInput(code, hasAnsi) {
                        detectTapGestures(
                            onLongPress = {
                                val plainToCopy = if (hasAnsi) {
                                    AnsiAndCodeFormatter.stripAnsiEscapes(code)
                                } else {
                                    code
                                }
                                copyPlainTextToClipboard(context, plainToCopy)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                Toast.makeText(context, "已复制报告内容", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val baseStyle = codeTypography.codeBody
            val horizontalPad = if (isTerminalReport && fitToWidth) 8.dp else 13.dp
            val verticalPad = if (showHeaderChrome) 12.dp else 10.dp
            val availableWidthPx = if (constraints.hasBoundedWidth && constraints.maxWidth > 0) {
                (constraints.maxWidth - with(density) { (horizontalPad * 2).roundToPx() }).coerceAtLeast(1)
            } else {
                0
            }
            val fitScale = remember(measureAnnotatedSample, baseStyle, availableWidthPx, isTerminalReport) {
                if (!isTerminalReport || availableWidthPx <= 0 || measureAnnotatedSample.isEmpty()) {
                    1f
                } else {
                    computeFittedMonospaceScale(
                        textMeasurer = textMeasurer,
                        styledText = measureAnnotatedSample,
                        baseStyle = baseStyle,
                        availableWidthPx = availableWidthPx,
                    )
                }
            }
            val canToggleFit = isTerminalReport && fitScale < 0.99f
            val appliedScale = if (fitToWidth && canToggleFit) fitScale else 1f
            val effectiveCodeStyle = remember(baseStyle, appliedScale) {
                if (appliedScale >= 0.999f) {
                    baseStyle
                } else {
                    val baseFontSize = if (baseStyle.fontSize != TextUnit.Unspecified) baseStyle.fontSize else 13.sp
                    val baseLineHeight = if (baseStyle.lineHeight != TextUnit.Unspecified) baseStyle.lineHeight else 19.5.sp
                    baseStyle.copy(
                        fontSize = baseFontSize * appliedScale,
                        lineHeight = baseLineHeight * appliedScale,
                    )
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                val copyCodeAction: () -> Unit = {
                    val plainToCopy = if (hasAnsi) {
                        AnsiAndCodeFormatter.stripAnsiEscapes(code)
                    } else {
                        code
                    }
                    copyPlainTextToClipboard(context, plainToCopy)
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    copied = true
                }

                if (showHeaderChrome) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ext.codeHeaderSurface)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.White.copy(alpha = 0.05f), Color.Transparent),
                                ),
                            )
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(6.5.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFF5F56).copy(alpha = 0.85f)),
                                )
                                Box(
                                    modifier = Modifier
                                        .size(6.5.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFFBD2E).copy(alpha = 0.85f)),
                                )
                                Box(
                                    modifier = Modifier
                                        .size(6.5.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF27C93F).copy(alpha = 0.85f)),
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.07f))
                                    .border(0.6.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = headerLabel,
                                    style = codeTypography.codeLabel,
                                    color = ext.onCodeMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(copyBg)
                                    .border(0.8.dp, copyBorderColor, RoundedCornerShape(7.dp))
                                    .pulseBouncyClickable(
                                        pressedScale = NodePulseMotion.chipPressScale,
                                        role = Role.Button,
                                        onClick = copyCodeAction,
                                    )
                                    .padding(horizontal = 9.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                    contentDescription = null,
                                    tint = copyFg,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = if (copied) "已复制" else "复制代码",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = copyFg,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        thickness = 0.8.dp,
                        color = ext.codeBorder.copy(alpha = 0.8f),
                    )
                } else {
                    // Compact utility bar for ANSI / terminal benchmark reports: exposes fit-to-width
                    // toggle (`已适配` / `1:1原宽`) and 1-tap plain-text copy even in text-selection mode.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ext.codeHeaderSurface.copy(alpha = 0.65f))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = headerLabel,
                            style = codeTypography.codeLabel,
                            color = ext.onCodeMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (canToggleFit) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(
                                            if (fitToWidth) {
                                                Color(0xFF1D4ED8).copy(alpha = 0.28f)
                                            } else {
                                                Color.White.copy(alpha = 0.08f)
                                            },
                                        )
                                        .border(
                                            0.8.dp,
                                            if (fitToWidth) {
                                                Color(0xFF60A5FA).copy(alpha = 0.45f)
                                            } else {
                                                Color.White.copy(alpha = 0.12f)
                                            },
                                            RoundedCornerShape(7.dp),
                                        )
                                        .pulseBouncyClickable(
                                            pressedScale = NodePulseMotion.chipPressScale,
                                            role = Role.Button,
                                            onClick = { fitToWidth = !fitToWidth },
                                        )
                                        .padding(horizontal = 8.dp, vertical = 3.5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = if (fitToWidth) "已适配" else "1:1原宽",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (fitToWidth) Color(0xFF93C5FD) else ext.onCodeSurface,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(copyBg)
                                    .border(0.8.dp, copyBorderColor, RoundedCornerShape(7.dp))
                                    .pulseBouncyClickable(
                                        pressedScale = NodePulseMotion.chipPressScale,
                                        role = Role.Button,
                                        onClick = copyCodeAction,
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                    contentDescription = null,
                                    tint = copyFg,
                                    modifier = Modifier.size(12.5.dp),
                                )
                                Text(
                                    text = if (copied) "已复制" else "复制",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = copyFg,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        thickness = 0.6.dp,
                        color = ext.codeBorder.copy(alpha = 0.6f),
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = horizontalPad, vertical = verticalPad),
                ) {
                    chunkedCode.chunks.forEach { styledChunk ->
                        Text(
                            text = styledChunk,
                            style = effectiveCodeStyle,
                            softWrap = false,
                        )
                    }
                    if (chunkedCode.hiddenLineCount > 0) {
                        val canExpandMore = maxRenderedLines < AnsiAndCodeFormatter.HARD_MAX_RENDERED_CODE_LINES
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (canExpandMore) {
                                "… 已折叠其余 ${chunkedCode.hiddenLineCount} 行，点击继续展开"
                            } else {
                                "… 已达预览上限（其余 ${chunkedCode.hiddenLineCount} 行可通过复制获取完整文本）"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF93C5FD),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .then(
                                    if (canExpandMore) {
                                        Modifier.clickable {
                                            maxRenderedLines = (maxRenderedLines + 400)
                                                .coerceAtMost(AnsiAndCodeFormatter.HARD_MAX_RENDERED_CODE_LINES)
                                        }
                                    } else {
                                        Modifier
                                    },
                                )
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownListBlockView(
    items: List<MdListItem>,
    referenceLinks: Map<String, Pair<String, String?>>,
    bodyStyle: TextStyle,
    textColor: Color,
    linkColor: Color,
    compactSignatureMode: Boolean,
    inlineSignatureFooter: Boolean,
    onMentionClick: ((String) -> Unit)?,
    onFloorClick: ((Int) -> Unit)?,
    onUrlClick: (String) -> Unit,
    onImageClick: (String, String) -> Unit,
    onImageLongClick: (String, String, String?) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (inlineSignatureFooter) 3.dp else 6.dp),
    ) {
        items.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (item.indentLevel * 14).dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    item.checked != null -> {
                        Icon(
                            imageVector = if (item.checked) {
                                Icons.Rounded.CheckBox
                            } else {
                                Icons.Rounded.CheckBoxOutlineBlank
                            },
                            contentDescription = null,
                            tint = if (item.checked) {
                                linkColor
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .size(17.dp),
                        )
                    }
                    item.orderNumber != null -> {
                        Text(
                            text = "${item.orderNumber}.",
                            style = bodyStyle.withTabularNumbers(),
                            fontWeight = FontWeight.SemiBold,
                            color = linkColor,
                            modifier = Modifier.widthIn(min = 20.dp),
                        )
                    }
                    else -> {
                        Text(
                            text = "•",
                            style = bodyStyle,
                            fontWeight = FontWeight.Bold,
                            color = linkColor,
                            modifier = Modifier.padding(horizontal = 3.dp),
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    if (item.text.contains("![") || item.text.contains("<img", ignoreCase = true)) {
                        ParagraphBlockView(
                            rawText = item.text,
                            referenceLinks = referenceLinks,
                            style = bodyStyle,
                            color = textColor,
                            linkColor = linkColor,
                            compactSignatureMode = compactSignatureMode,
                            inlineSignatureFooter = inlineSignatureFooter,
                            onMentionClick = onMentionClick,
                            onFloorClick = onFloorClick,
                            onUrlClick = onUrlClick,
                            onImageClick = onImageClick,
                            onImageLongClick = onImageLongClick,
                        )
                    } else {
                        RichInlineText(
                            rawText = item.text,
                            referenceLinks = referenceLinks,
                            style = bodyStyle,
                            color = textColor,
                            linkColor = linkColor,
                            underlineLinks = !inlineSignatureFooter,
                            compactInlineSticker = inlineSignatureFooter,
                            onMentionClick = onMentionClick,
                            onFloorClick = onFloorClick,
                            onUrlClick = onUrlClick,
                            onInlineImageClick = onImageClick,
                            onInlineImageLongClick = onImageLongClick,
                        )
                    }
                }
            }
        }
    }
}

internal data class MarkdownImagePrefetchTarget(
    val resolvedUrl: String,
    val useFullCardWidth: Boolean,
)

internal fun extractMarkdownImagePrefetchTargets(
    tabs: List<MdTabItem>,
    referenceLinks: Map<String, Pair<String, String?>> = emptyMap(),
    compactSignatureMode: Boolean = false,
): List<MarkdownImagePrefetchTarget> {
    val ordered = LinkedHashMap<String, Boolean>()
    fun record(rawUrl: String, useFullCardWidth: Boolean) {
        val normalized = normalizeMarkdownImageUrl(rawUrl)
        if (normalized.isBlank()) return
        val prev = ordered[normalized]
        ordered[normalized] = (prev == true) || useFullCardWidth
    }

    fun collectInlineTokens(
        tokens: List<MdInlineToken>,
        allowPromoteTopLevel: Boolean,
    ) {
        for (token in tokens) {
            when (token) {
                is MdInlineToken.Image -> {
                    val isPromoted = allowPromoteTopLevel && !isBadgeOrSmallGraphicUrl(token.url)
                    record(token.url, useFullCardWidth = isPromoted && !compactSignatureMode)
                }
                is MdInlineToken.Sticker -> {
                    record(token.sticker.imageUrl, useFullCardWidth = false)
                }
                is MdInlineToken.Link -> {
                    collectInlineTokens(token.labelTokens, allowPromoteTopLevel = false)
                }
                is MdInlineToken.Styled -> {
                    collectInlineTokens(token.children, allowPromoteTopLevel = false)
                }
                else -> Unit
            }
        }
    }

    fun collectBlocks(blocks: List<MdBlock>) {
        for (block in blocks) {
            when (block) {
                is MdBlock.ImageCard -> {
                    record(block.url, useFullCardWidth = !compactSignatureMode)
                }
                is MdBlock.ImageRow -> {
                    block.items.forEach { item ->
                        val isCard = !compactSignatureMode && !isBadgeOrSmallGraphicUrl(item.imageUrl)
                        record(item.imageUrl, useFullCardWidth = isCard)
                    }
                }
                is MdBlock.Paragraph -> {
                    val tokens = tokenizeMarkdownInline(block.text, referenceLinks)
                    collectInlineTokens(tokens, allowPromoteTopLevel = true)
                }
                is MdBlock.Table -> {
                    val unwrapped = extractImageTableCardsOrNull(block, referenceLinks)
                    if (unwrapped != null) {
                        unwrapped.forEach { cardItem ->
                            cardItem.label?.takeIf { it.isNotBlank() }?.let { label ->
                                collectInlineTokens(
                                    tokenizeMarkdownInline(label, referenceLinks),
                                    allowPromoteTopLevel = false,
                                )
                            }
                            record(cardItem.image.imageUrl, useFullCardWidth = true)
                        }
                    } else {
                        block.headers.forEach { header ->
                            collectInlineTokens(
                                tokenizeMarkdownInline(header, referenceLinks),
                                allowPromoteTopLevel = false,
                            )
                        }
                        block.rows.forEach { row ->
                            row.forEach { cell ->
                                val cellImages = parseStandaloneImageRowLine(cell.trim(), referenceLinks)
                                    ?.takeIf { imgs -> imgs.all { !isBadgeOrSmallGraphicUrl(it.imageUrl) } }
                                if (cellImages != null) {
                                    cellImages.forEach { img ->
                                        record(img.imageUrl, useFullCardWidth = true)
                                    }
                                } else {
                                    collectInlineTokens(
                                        tokenizeMarkdownInline(cell, referenceLinks),
                                        allowPromoteTopLevel = false,
                                    )
                                }
                            }
                        }
                    }
                }
                is MdBlock.ListContainer -> {
                    block.items.forEach { item ->
                        val tokens = tokenizeMarkdownInline(item.text, referenceLinks)
                        val canPromote = item.text.contains("![") || item.text.contains("<img", ignoreCase = true)
                        collectInlineTokens(tokens, allowPromoteTopLevel = canPromote)
                    }
                }
                is MdBlock.Blockquote -> collectBlocks(block.children)
                is MdBlock.DetailsSpoiler -> {
                    collectInlineTokens(
                        tokenizeMarkdownInline(block.summary, referenceLinks),
                        allowPromoteTopLevel = false,
                    )
                    collectBlocks(block.children)
                }
                is MdBlock.TabsContainer -> {
                    block.tabs.forEach { collectBlocks(it.children) }
                }
                is MdBlock.Heading -> {
                    collectInlineTokens(
                        tokenizeMarkdownInline(block.text, referenceLinks),
                        allowPromoteTopLevel = false,
                    )
                }
                is MdBlock.CodeFence,
                is MdBlock.SpecialWidget,
                MdBlock.HorizontalRule -> Unit
            }
        }
    }

    tabs.forEach { tab -> collectBlocks(tab.children) }
    return ordered.map { (url, fullWidth) ->
        MarkdownImagePrefetchTarget(resolvedUrl = url, useFullCardWidth = fullWidth)
    }
}

internal fun extractMarkdownImageUrls(
    tabs: List<MdTabItem>,
    referenceLinks: Map<String, Pair<String, String?>> = emptyMap(),
): List<String> = extractMarkdownImagePrefetchTargets(
    tabs = tabs,
    referenceLinks = referenceLinks,
).map { it.resolvedUrl }

/**
 * Interactive `:::: tabs` / `::: tab-item Title` container used in NodeSeek VPS benchmark posts.
 */
@Composable
private fun NodeSeekTabsCard(
    tabs: List<MdTabItem>,
    referenceLinks: Map<String, Pair<String, String?>>,
    bodyStyle: TextStyle,
    textColor: Color,
    linkColor: Color,
    compactSignatureMode: Boolean,
    inlineSignatureFooter: Boolean,
    onMentionClick: ((String) -> Unit)?,
    onFloorClick: ((Int) -> Unit)?,
    onUrlClick: (String) -> Unit,
    onImageClick: (String, String) -> Unit,
    onImageLongClick: (String, String, String?) -> Unit,
) {
    if (tabs.isEmpty()) return
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val context = LocalContext.current
    val density = LocalDensity.current
    val imageLoader = remember(context) { SingletonImageLoader.get(context) }
    val saveableStateHolder = rememberSaveableStateHolder()
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val safeIndex = selectedTabIndex.coerceIn(0, tabs.lastIndex)
    val tabImageMetaCache = remember(tabs) { ConcurrentHashMap<String, CachedMarkdownImageMeta>() }
    val prefetchJobs = remember(tabs) { ConcurrentHashMap<String, Deferred<ImageResult>>() }
    val activeCardUrls = remember(tabs) { Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>()) }
    val prefetchTargets = remember(tabs, referenceLinks, compactSignatureMode) {
        extractMarkdownImagePrefetchTargets(
            tabs = tabs,
            referenceLinks = referenceLinks,
            compactSignatureMode = compactSignatureMode,
        )
    }
    val tabHeaderBg = blendColor(
        source = MaterialTheme.colorScheme.surfaceContainer,
        target = MaterialTheme.colorScheme.primary,
        fraction = if (isDark) 0.08f else 0.04f,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = ext.cardSurface,
                elevation = PulseElevation.Card,
                accentColor = MaterialTheme.colorScheme.primary,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(tabHeaderBg)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == safeIndex
                val pillBg by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f)
                    },
                    label = "mdTabBg_$index",
                )
                val pillFg by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    label = "mdTabFg_$index",
                )
                val pillBorder by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        ext.cardBorder.copy(alpha = 0.65f)
                    },
                    label = "mdTabBorder_$index",
                )

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(pillBg)
                        .border(0.8.dp, pillBorder, CircleShape)
                        .pulseBouncyClickable(
                            pressedScale = NodePulseMotion.chipPressScale,
                            onClick = { selectedTabIndex = index },
                        )
                        .padding(horizontal = 13.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tab.title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = pillFg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        PulseGradientDivider(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            thickness = 0.8.dp,
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val tabBodyHorizontalPaddingPx = with(density) { 14.dp.roundToPx() * 2 }
            val tabTargetWidthPx = if (constraints.hasBoundedWidth && constraints.maxWidth > 0) {
                (constraints.maxWidth - tabBodyHorizontalPaddingPx).coerceAtLeast(1)
            } else {
                (context.resources.displayMetrics.widthPixels - with(density) { 64.dp.roundToPx() }).coerceAtLeast(1)
            }

            LaunchedEffect(imageLoader, prefetchTargets, tabTargetWidthPx) {
                if (prefetchTargets.isEmpty()) return@LaunchedEffect
                val disposables = mutableListOf<Disposable>()
                try {
                    prefetchTargets.forEach { target ->
                        val targetWidth = if (target.useFullCardWidth) tabTargetWidthPx else null
                        val warm = findWarmMemoryCachedImage(
                            imageLoader = imageLoader,
                            resolvedUrl = target.resolvedUrl,
                            targetWidthPx = targetWidth,
                        )
                        if (warm != null) {
                            val (ratio, tiny) = computeImageLayoutMeta(
                                width = warm.image.width.toFloat(),
                                height = warm.image.height.toFloat(),
                                isSvgUrl = isSvgImageUrl(target.resolvedUrl),
                            )
                            tabImageMetaCache[target.resolvedUrl] = CachedMarkdownImageMeta(
                                aspectRatio = ratio,
                                isTinyRaster = tiny,
                                loadedSuccess = true,
                            )
                        } else if (!activeCardUrls.contains(target.resolvedUrl)) {
                            val request = buildMarkdownCoilRequest(
                                context = context,
                                resolvedUrl = target.resolvedUrl,
                                targetWidthPx = targetWidth,
                                enableCrossfade = false,
                                onSuccess = { result ->
                                    val img = result.image
                                    val (ratio, tiny) = computeImageLayoutMeta(
                                        width = img.width.toFloat(),
                                        height = img.height.toFloat(),
                                        isSvgUrl = isSvgImageUrl(target.resolvedUrl),
                                    )
                                    tabImageMetaCache[target.resolvedUrl] = CachedMarkdownImageMeta(
                                        aspectRatio = ratio,
                                        isTinyRaster = tiny,
                                        loadedSuccess = true,
                                    )
                                    prefetchJobs.remove(target.resolvedUrl)
                                },
                                onError = {
                                    prefetchJobs.remove(target.resolvedUrl)
                                },
                                onCancel = {
                                    prefetchJobs.remove(target.resolvedUrl)
                                },
                            )
                            val disposable = imageLoader.enqueue(request)
                            if (disposable.job.isActive) {
                                prefetchJobs[target.resolvedUrl] = disposable.job
                            }
                            disposables += disposable
                        }
                    }
                    awaitCancellation()
                } finally {
                    disposables.forEach { it.dispose() }
                    prefetchJobs.clear()
                }
            }

            CompositionLocalProvider(
                LocalInsideTabsContainer provides true,
                LocalMarkdownImageTargetWidthPx provides tabTargetWidthPx,
                LocalMarkdownImageMetaCache provides tabImageMetaCache,
                LocalMarkdownPrefetchJobs provides prefetchJobs,
                LocalMarkdownActiveCardUrls provides activeCardUrls,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(
                            animationSpec = spring(
                                dampingRatio = 0.72f,
                                stiffness = 380f,
                            ),
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    saveableStateHolder.SaveableStateProvider(key = safeIndex) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            MarkdownBlockStream(
                                blocks = tabs[safeIndex].children,
                                referenceLinks = referenceLinks,
                                bodyStyle = bodyStyle,
                                textColor = textColor,
                                linkColor = linkColor,
                                compactSignatureMode = compactSignatureMode,
                                inlineSignatureFooter = inlineSignatureFooter,
                                onMentionClick = onMentionClick,
                                onFloorClick = onFloorClick,
                                onUrlClick = onUrlClick,
                                onImageClick = onImageClick,
                                onImageLongClick = onImageLongClick,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal data class TableImageCardItem(
    val label: String?,
    val image: MdImageItem,
)

/**
 * Unwraps markdown tables used purely as image/screenshot layout grids (such as side-by-side
 * NodeQuality benchmark tables) so report images scale to screen width without horizontal scrolling.
 */
internal fun extractImageTableCardsOrNull(
    table: MdBlock.Table,
    referenceLinks: Map<String, Pair<String, String?>>,
): List<TableImageCardItem>? {
    val colCount = maxOf(
        table.headers.size,
        table.rows.maxOfOrNull { it.size } ?: 0,
    )
    if (colCount == 0) return null

    fun parseNonBadgeCellImages(cell: String): List<MdImageItem>? {
        val trimmed = cell.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parsed = parseStandaloneImageRowLine(trimmed, referenceLinks) ?: return null
        return if (parsed.all { !isBadgeOrSmallGraphicUrl(it.imageUrl) }) parsed else null
    }

    val headerImages = table.headers.map { parseNonBadgeCellImages(it) }
    val headersAreImagesOnly = headerImages.isNotEmpty() &&
        headerImages.all { it != null } &&
        headerImages.any { !it.isNullOrEmpty() }

    val allRows = if (headersAreImagesOnly) {
        listOf(table.headers) + table.rows
    } else {
        table.rows
    }
    if (allRows.isEmpty()) return null

    val pendingColLabels = if (headersAreImagesOnly) {
        MutableList(colCount) { "" }
    } else {
        MutableList(colCount) { col -> table.headers.getOrElse(col) { "" }.trim() }
    }

    val extracted = mutableListOf<TableImageCardItem>()
    for ((rowIdx, row) in allRows.withIndex()) {
        val parsedCells = (0 until colCount).map { col ->
            parseNonBadgeCellImages(row.getOrElse(col) { "" })
        }
        val isImageRow = parsedCells.all { it != null } && parsedCells.any { !it.isNullOrEmpty() }
        if (isImageRow) {
            for (col in 0 until colCount) {
                val cellImages = parsedCells[col].orEmpty()
                if (cellImages.isEmpty()) continue
                val colLabel = pendingColLabels.getOrElse(col) { "" }.takeIf { it.isNotBlank() }
                for (img in cellImages) {
                    extracted += TableImageCardItem(label = colLabel, image = img)
                }
            }
            pendingColLabels.fill("")
        } else {
            // Allow label rows only when immediately followed by an image row (e.g. 2x2 screenshot grids).
            val hasNextImageRow = rowIdx + 1 < allRows.size && run {
                val nextCells = (0 until colCount).map { col ->
                    parseNonBadgeCellImages(allRows[rowIdx + 1].getOrElse(col) { "" })
                }
                nextCells.all { it != null } && nextCells.any { !it.isNullOrEmpty() }
            }
            if (!hasNextImageRow) return null
            for (col in 0 until colCount) {
                pendingColLabels[col] = row.getOrElse(col) { "" }.trim()
            }
        }
    }

    return extracted.takeIf { it.isNotEmpty() }
}

private class TableGridLayoutMetrics(
    var colWidths: IntArray = IntArray(0),
    var rowHeights: IntArray = IntArray(0),
)

/**
 * Computes shared column pixel widths across all rows of a GFM pipe table:
 * - Short label/metric columns stay single-line at their natural intrinsic width.
 * - Tables narrower than [viewportWidthPx] expand proportionally to fill the card cleanly.
 * - Tables slightly wider than [viewportWidthPx] wrap wide note/description columns down to
 *   [minWrappableColPx] before falling back to smooth horizontal scrolling for multi-column grids.
 */
internal fun computeGfmTableColumnWidths(
    intrinsicColMaxWidths: IntArray,
    viewportWidthPx: Int,
    minColPx: Int,
    maxColPx: Int,
    minWrappableColPx: Int,
): IntArray {
    val colCount = intrinsicColMaxWidths.size
    if (colCount == 0) return IntArray(0)

    val safeMin = minColPx.coerceAtLeast(1)
    val safeMax = maxColPx.coerceAtLeast(safeMin)
    val safeWrapFloor = minWrappableColPx.coerceIn(safeMin, safeMax)

    val desired = IntArray(colCount) { c ->
        intrinsicColMaxWidths[c].coerceIn(safeMin, safeMax)
    }
    if (viewportWidthPx <= 0) return desired

    val totalDesired = desired.sum()
    if (totalDesired == viewportWidthPx) return desired

    if (totalDesired < viewportWidthPx) {
        val extra = viewportWidthPx - totalDesired
        val stretched = IntArray(colCount)
        var allocated = 0
        for (c in 0 until colCount) {
            val add = if (c == colCount - 1) {
                extra - allocated
            } else {
                ((extra.toLong() * desired[c]) / totalDesired.coerceAtLeast(1)).toInt()
            }
            stretched[c] = desired[c] + add
            allocated += add
        }
        return stretched
    }

    // For 2-column and 3-column tables on mobile viewports, fit 100% inside viewportWidthPx
    // so the rightmost column wraps cleanly instead of clipping at the card edge.
    if (colCount <= 3 && viewportWidthPx >= colCount * safeMin) {
        val fairShare = viewportWidthPx / colCount
        val compactCols = (0 until colCount).filter { desired[it] <= fairShare }
        val flexCols = (0 until colCount).filter { desired[it] > fairShare }
        if (flexCols.isNotEmpty()) {
            val result = desired.copyOf()
            val fixedSum = compactCols.sumOf { desired[it] }
            val remaining = (viewportWidthPx - fixedSum).coerceAtLeast(flexCols.size * safeMin)
            val totalDemand = flexCols.sumOf { desired[it].coerceAtLeast(1) }
            var allocated = 0
            flexCols.forEachIndexed { idx, col ->
                val width = if (idx == flexCols.lastIndex) {
                    (remaining - allocated).coerceAtLeast(safeMin)
                } else {
                    ((remaining.toLong() * desired[col]) / totalDemand.coerceAtLeast(1))
                        .toInt()
                        .coerceAtLeast(safeMin)
                }
                result[col] = width
                allocated += width
            }
            // Trim last flex column if safeMin clamping slightly exceeded remaining
            val diff = result.sum() - viewportWidthPx
            if (diff > 0) {
                val widestCol = flexCols.maxBy { result[it] }
                result[widestCol] = (result[widestCol] - diff).coerceAtLeast(safeMin)
            }
            return result
        }
    }

    // totalDesired > viewportWidthPx (4+ columns): check if wrapping wide columns above floor fits
    val wrappableCols = (0 until colCount).filter { desired[it] > safeWrapFloor }
    if (wrappableCols.isEmpty()) return desired

    val fixedSum = (0 until colCount).filter { desired[it] <= safeWrapFloor }.sumOf { desired[it] }
    val minFitTotal = fixedSum + wrappableCols.size * safeWrapFloor
    if (minFitTotal <= viewportWidthPx) {
        val result = desired.copyOf()
        val extraAboveFloor = viewportWidthPx - minFitTotal
        val totalExcessDemand = wrappableCols.sumOf { (desired[it] - safeWrapFloor).coerceAtLeast(1) }
        var allocated = 0
        wrappableCols.forEachIndexed { idx, col ->
            val share = if (idx == wrappableCols.lastIndex) {
                extraAboveFloor - allocated
            } else {
                val demand = (desired[col] - safeWrapFloor).coerceAtLeast(1)
                ((extraAboveFloor.toLong() * demand) / totalExcessDemand.coerceAtLeast(1)).toInt()
            }
            result[col] = safeWrapFloor + share
            allocated += share
        }
        return result
    }

    return desired
}

/**
 * Horizontally scrollable GFM Pipe Table with unified multi-row column widths, aligned cells,
 * vertical & horizontal architectural hairlines, and alternating zebra rows.
 */
@Composable
private fun GfmTableCard(
    table: MdBlock.Table,
    referenceLinks: Map<String, Pair<String, String?>>,
    bodyStyle: TextStyle,
    textColor: Color,
    linkColor: Color,
    inlineSignatureFooter: Boolean,
    onMentionClick: ((String) -> Unit)?,
    onFloorClick: ((Int) -> Unit)?,
    onUrlClick: (String) -> Unit,
    onImageClick: (String, String) -> Unit,
    onImageLongClick: (String, String, String?) -> Unit,
) {
    val extractedImageCards = remember(table, referenceLinks) {
        extractImageTableCardsOrNull(table, referenceLinks)
    }
    if (extractedImageCards != null) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            extractedImageCards.forEach { cardItem ->
                val img = cardItem.image
                val labelText = cardItem.label
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (!labelText.isNullOrBlank()) {
                        RichInlineText(
                            rawText = labelText,
                            referenceLinks = referenceLinks,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            linkColor = linkColor,
                            underlineLinks = !inlineSignatureFooter,
                            compactInlineSticker = inlineSignatureFooter,
                            onMentionClick = onMentionClick,
                            onFloorClick = onFloorClick,
                            onUrlClick = onUrlClick,
                            onInlineImageClick = onImageClick,
                            onInlineImageLongClick = onImageLongClick,
                        )
                    }
                    PulseZoomableImageCard(
                        imageUrl = img.imageUrl,
                        altText = img.alt.ifBlank { labelText.orEmpty() },
                        compactMode = false,
                        signatureFooterMode = inlineSignatureFooter,
                        onClick = {
                            if (!img.linkUrl.isNullOrBlank()) {
                                onUrlClick(unwrapNodeSeekJumpUrl(img.linkUrl))
                            } else {
                                onImageClick(img.imageUrl, img.alt.ifBlank { labelText.orEmpty() })
                            }
                        },
                        onLongClick = {
                            onImageLongClick(
                                img.imageUrl,
                                img.alt.ifBlank { labelText.orEmpty() },
                                img.linkUrl,
                            )
                        },
                    )
                }
            }
        }
        return
    }

    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val density = LocalDensity.current
    val colCount = maxOf(
        table.headers.size,
        table.rows.maxOfOrNull { it.size } ?: 0,
    )
    if (colCount == 0) return

    val isCompactBody = bodyStyle.fontSize != TextUnit.Unspecified && bodyStyle.fontSize.value < 13.5f
    val headerBaseStyle = if (isCompactBody) {
        MaterialTheme.typography.labelMedium
    } else {
        MaterialTheme.typography.labelLarge
    }
    val cellBaseStyle = (if (isCompactBody) {
        MaterialTheme.typography.bodySmall
    } else {
        MaterialTheme.typography.bodyMedium
    }).withTabularNumbers()

    val cellHorizPad = if (isCompactBody) 8.5.dp else 11.dp
    val headerVertPad = if (isCompactBody) 8.dp else 9.5.dp
    val dataVertPad = if (isCompactBody) 7.5.dp else 8.5.dp

    val headerBg = blendColor(
        source = MaterialTheme.colorScheme.surfaceContainerHigh,
        target = linkColor,
        fraction = if (isDark) 0.14f else 0.07f,
    )
    val zebraRowBg = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.72f)
    val headerBorderColor = ext.cardBorder.copy(alpha = 0.88f)
    val rowBorderColor = ext.cardBorder.copy(alpha = 0.52f)
    val colBorderColor = ext.cardBorder.copy(alpha = 0.40f)

    val parsedDataCellImages: List<List<List<MdImageItem>?>> = remember(table.rows, colCount, referenceLinks) {
        table.rows.map { row ->
            List(colCount) { col ->
                val rawCell = row.getOrElse(col) { "" }.trim()
                parseStandaloneImageRowLine(rawCell, referenceLinks)
                    ?.takeIf { imgs -> imgs.all { !isBadgeOrSmallGraphicUrl(it.imageUrl) } }
            }
        }
    }

    val gridMetrics = remember { TableGridLayoutMetrics() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = ext.cardSurface,
                elevation = PulseElevation.Subtle,
            ),
    ) {
        val viewportWidthPx = constraints.maxWidth.takeIf { it != Constraints.Infinity } ?: 0
        val minColPx = with(density) { (if (isCompactBody) 62.dp else 72.dp).roundToPx() }
        val maxColPx = with(density) { (if (isCompactBody) 244.dp else 268.dp).roundToPx() }
        val minWrappableColPx = with(density) { (if (isCompactBody) 124.dp else 140.dp).roundToPx() }
        val imageCellMinPx = with(density) { 164.dp.roundToPx() }
        val minRowHeightPx = with(density) { (if (isCompactBody) 32.dp else 36.dp).roundToPx() }
        val totalRows = 1 + table.rows.size

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            Layout(
                modifier = Modifier.drawBehind {
                    val colW = gridMetrics.colWidths
                    val rowH = gridMetrics.rowHeights
                    if (colW.isEmpty() || rowH.isEmpty()) return@drawBehind

                    // 1. Header background
                    val headerH = rowH[0].toFloat()
                    drawRect(
                        color = headerBg,
                        topLeft = Offset.Zero,
                        size = Size(width = size.width, height = headerH),
                    )

                    // 2. Alternating zebra row backgrounds + horizontal dividers
                    var yCursor = headerH
                    for (r in 1 until rowH.size) {
                        val h = rowH[r].toFloat()
                        if ((r - 1) % 2 == 1) {
                            drawRect(
                                color = zebraRowBg,
                                topLeft = Offset(x = 0f, y = yCursor),
                                size = Size(width = size.width, height = h),
                            )
                        }
                        val strokePx = (if (r == 1) 0.9.dp else 0.7.dp).toPx()
                        drawLine(
                            color = if (r == 1) headerBorderColor else rowBorderColor,
                            start = Offset(0f, yCursor),
                            end = Offset(size.width, yCursor),
                            strokeWidth = strokePx,
                        )
                        yCursor += h
                    }

                    // 3. Vertical column hairlines
                    val vStrokePx = 0.65.dp.toPx()
                    var xCursor = 0f
                    for (c in 0 until colW.size - 1) {
                        xCursor += colW[c].toFloat()
                        drawLine(
                            color = colBorderColor,
                            start = Offset(xCursor, 0f),
                            end = Offset(xCursor, size.height),
                            strokeWidth = vStrokePx,
                        )
                    }
                },
                content = {
                    // Row 0: Header cells
                    for (col in 0 until colCount) {
                        val align = table.alignments.getOrElse(col) { TextAlign.Start }
                        Box(
                            modifier = Modifier.padding(horizontal = cellHorizPad, vertical = headerVertPad),
                        ) {
                            RichInlineText(
                                rawText = table.headers.getOrElse(col) { "" },
                                referenceLinks = referenceLinks,
                                style = headerBaseStyle.copy(
                                    fontWeight = FontWeight.Bold,
                                    textAlign = align,
                                ),
                                color = textColor,
                                linkColor = linkColor,
                                underlineLinks = !inlineSignatureFooter,
                                compactInlineSticker = inlineSignatureFooter,
                                onMentionClick = onMentionClick,
                                onFloorClick = onFloorClick,
                                onUrlClick = onUrlClick,
                                onInlineImageClick = onImageClick,
                                onInlineImageLongClick = onImageLongClick,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    // Rows 1..N: Data cells
                    table.rows.forEachIndexed { rowIdx, row ->
                        for (col in 0 until colCount) {
                            val align = table.alignments.getOrElse(col) { TextAlign.Start }
                            val rawCell = row.getOrElse(col) { "" }
                            val standaloneCellImages = parsedDataCellImages.getOrNull(rowIdx)?.getOrNull(col)
                            Box(
                                modifier = Modifier.padding(horizontal = cellHorizPad, vertical = dataVertPad),
                            ) {
                                if (standaloneCellImages != null) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        standaloneCellImages.forEach { img ->
                                            PulseZoomableImageCard(
                                                imageUrl = img.imageUrl,
                                                altText = img.alt,
                                                compactMode = false,
                                                signatureFooterMode = inlineSignatureFooter,
                                                onClick = {
                                                    if (!img.linkUrl.isNullOrBlank()) {
                                                        onUrlClick(unwrapNodeSeekJumpUrl(img.linkUrl))
                                                    } else {
                                                        onImageClick(img.imageUrl, img.alt)
                                                    }
                                                },
                                                onLongClick = {
                                                    onImageLongClick(img.imageUrl, img.alt, img.linkUrl)
                                                },
                                            )
                                        }
                                    }
                                } else {
                                    RichInlineText(
                                        rawText = rawCell,
                                        referenceLinks = referenceLinks,
                                        style = cellBaseStyle.copy(textAlign = align),
                                        color = textColor,
                                        linkColor = linkColor,
                                        underlineLinks = !inlineSignatureFooter,
                                        compactInlineSticker = inlineSignatureFooter,
                                        onMentionClick = onMentionClick,
                                        onFloorClick = onFloorClick,
                                        onUrlClick = onUrlClick,
                                        onInlineImageClick = onImageClick,
                                        onInlineImageLongClick = onImageLongClick,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                },
            ) { measurables, _ ->
                if (measurables.isEmpty()) {
                    gridMetrics.colWidths = IntArray(0)
                    gridMetrics.rowHeights = IntArray(0)
                    return@Layout layout(0, 0) {}
                }

                val intrinsicMaxByCol = IntArray(colCount)
                for (r in 0 until totalRows) {
                    for (c in 0 until colCount) {
                        val idx = r * colCount + c
                        if (idx >= measurables.size) break
                        val hasImg = r > 0 && parsedDataCellImages.getOrNull(r - 1)?.getOrNull(c) != null
                        val naturalW = if (hasImg) {
                            maxOf(imageCellMinPx, measurables[idx].maxIntrinsicWidth(Constraints.Infinity))
                        } else {
                            measurables[idx].maxIntrinsicWidth(Constraints.Infinity)
                        }
                        if (naturalW > intrinsicMaxByCol[c]) {
                            intrinsicMaxByCol[c] = naturalW
                        }
                    }
                }

                val colWidths = computeGfmTableColumnWidths(
                    intrinsicColMaxWidths = intrinsicMaxByCol,
                    viewportWidthPx = viewportWidthPx,
                    minColPx = minColPx,
                    maxColPx = maxColPx,
                    minWrappableColPx = minWrappableColPx,
                )

                val placeables = Array(measurables.size) { idx ->
                    val col = idx % colCount
                    measurables[idx].measure(Constraints.fixedWidth(colWidths[col]))
                }

                val rowHeights = IntArray(totalRows) { r ->
                    var maxH = minRowHeightPx
                    for (c in 0 until colCount) {
                        val idx = r * colCount + c
                        if (idx < placeables.size && placeables[idx].height > maxH) {
                            maxH = placeables[idx].height
                        }
                    }
                    maxH
                }

                gridMetrics.colWidths = colWidths
                gridMetrics.rowHeights = rowHeights

                val tableWidth = colWidths.sum()
                val tableHeight = rowHeights.sum()

                layout(tableWidth, tableHeight) {
                    var y = 0
                    for (r in 0 until totalRows) {
                        val rowH = rowHeights[r]
                        var x = 0
                        for (c in 0 until colCount) {
                            val idx = r * colCount + c
                            if (idx < placeables.size) {
                                val p = placeables[idx]
                                val yOffset = y + ((rowH - p.height) / 2).coerceAtLeast(0)
                                p.placeRelative(x = x, y = yOffset)
                            }
                            x += colWidths[c]
                        }
                        y += rowH
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsSpoilerView(
    summary: String,
    children: List<MdBlock>,
    referenceLinks: Map<String, Pair<String, String?>>,
    bodyStyle: TextStyle,
    textColor: Color,
    linkColor: Color,
    compactSignatureMode: Boolean,
    inlineSignatureFooter: Boolean,
    onMentionClick: ((String) -> Unit)?,
    onFloorClick: ((Int) -> Unit)?,
    onUrlClick: (String) -> Unit,
    onImageClick: (String, String) -> Unit,
    onImageLongClick: (String, String, String?) -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    var expanded by rememberSaveable(summary) { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = NodePulseMotion.spatialFloatSpring(),
        label = "spoilerChevronRotation",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                elevation = PulseElevation.Subtle,
                accentColor = if (expanded) linkColor else null,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .background(linkColor.copy(alpha = if (isDark) 0.18f else 0.11f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = linkColor,
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = arrowRotation },
                )
            }
            RichInlineText(
                rawText = summary.ifBlank { "展开详情" },
                referenceLinks = referenceLinks,
                style = MaterialTheme.typography.labelLarge,
                color = textColor,
                linkColor = linkColor,
                underlineLinks = !inlineSignatureFooter,
                compactInlineSticker = inlineSignatureFooter,
                onMentionClick = onMentionClick,
                onFloorClick = onFloorClick,
                onUrlClick = onUrlClick,
                onInlineImageClick = null,
                modifier = Modifier.weight(1f),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PulseGradientDivider(
                    color = ext.cardBorder,
                    thickness = 0.8.dp,
                )
                MarkdownBlockStream(
                    blocks = children,
                    referenceLinks = referenceLinks,
                    bodyStyle = bodyStyle,
                    textColor = textColor,
                    linkColor = linkColor,
                    compactSignatureMode = compactSignatureMode,
                    inlineSignatureFooter = inlineSignatureFooter,
                    onMentionClick = onMentionClick,
                    onFloorClick = onFloorClick,
                    onUrlClick = onUrlClick,
                    onImageClick = onImageClick,
                    onImageLongClick = onImageLongClick,
                )
            }
        }
    }
}

private enum class ImageLoadStatus {
    LOADING,
    SUCCESS,
    ERROR,
}

internal fun normalizeMarkdownImageUrl(rawUrl: String?): String {
    val trimmed = rawUrl
        ?.trim()
        ?.removePrefix("<")
        ?.removeSuffix(">")
        ?.trim()
        .orEmpty()
    if (trimmed.isEmpty()) return ""
    val lower = trimmed.lowercase(java.util.Locale.US)
    return when {
        lower.startsWith("file:") ||
            lower.startsWith("content:") ||
            lower.startsWith("android.resource:") ||
            lower.startsWith("javascript:") ||
            lower.startsWith("vbscript:") -> ""
        lower.startsWith("data:") -> {
            if (lower.startsWith("data:image/")) trimmed else ""
        }
        lower.startsWith("https://") -> "https://" + trimmed.substring(8)
        lower.startsWith("http://") -> {
            val rest = trimmed.substring(7)
            val host = rest.substringBefore('/').substringBefore(':').lowercase(java.util.Locale.US)
            if (host == "localhost" || host == "127.0.0.1") {
                "http://$rest"
            } else {
                "https://$rest"
            }
        }
        trimmed.startsWith("//") -> "https:$trimmed"
        else -> {
            // Reject arbitrary unknown URI schemes (e.g. `intent:`, `ftp:`) while keeping relative paths.
            val schemeColon = trimmed.indexOf(':')
            val firstSlash = trimmed.indexOf('/')
            if (schemeColon > 0 && (firstSlash < 0 || schemeColon < firstSlash)) {
                ""
            } else {
                normalizeNodeSeekAssetUrl(trimmed) ?: trimmed
            }
        }
    }
}

private fun buildMarkdownCoilRequest(
    context: Context,
    resolvedUrl: String,
    retryNonce: Int = 0,
    targetWidthPx: Int? = null,
    enableCrossfade: Boolean = true,
    onSuccess: ((SuccessResult) -> Unit)? = null,
    onError: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
): ImageRequest {
    return ImageRequest.Builder(context)
        .data(resolvedUrl)
        .crossfade(enableCrossfade)
        .apply {
            if (targetWidthPx != null && targetWidthPx > 0) {
                size(Dimension(targetWidthPx), Dimension.Undefined)
                scale(CoilScale.FIT)
                precision(Precision.INEXACT)
            }
            if (retryNonce > 0) {
                memoryCachePolicy(CachePolicy.WRITE_ONLY)
                diskCachePolicy(CachePolicy.WRITE_ONLY)
            }
            if (onSuccess != null || onError != null || onCancel != null) {
                listener(
                    object : ImageRequest.Listener {
                        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                            onSuccess?.invoke(result)
                        }

                        override fun onError(request: ImageRequest, result: ErrorResult) {
                            onError?.invoke()
                        }

                        override fun onCancel(request: ImageRequest) {
                            onCancel?.invoke()
                        }
                    },
                )
            }
        }
        .build()
}

internal fun computeImageLayoutMeta(
    width: Float,
    height: Float,
    isSvgUrl: Boolean,
): Pair<Float?, Boolean> {
    if (width <= 0f || height <= 0f) return null to false
    val tinyRaster = !isSvgUrl && width < 120f && height < 80f
    val ratio = if (!tinyRaster) {
        (width / height).coerceIn(0.12f, 6.0f)
    } else {
        null
    }
    return ratio to tinyRaster
}

private fun findWarmMemoryCachedImage(
    imageLoader: ImageLoader,
    resolvedUrl: String,
    targetWidthPx: Int?,
): MemoryCache.Value? {
    if (resolvedUrl.isBlank()) return null
    val memoryCache = imageLoader.memoryCache ?: return null
    val entry = memoryCache[MemoryCache.Key(resolvedUrl)] ?: return null
    val image = entry.image
    if (image.width <= 0 || image.height <= 0) return null
    val isSampled = (entry.extras["coil#is_sampled"] as? Boolean) == true
    val hitMaxBitmapLimit = image.width >= 4090 || image.height >= 4090
    if (isSampled &&
        !hitMaxBitmapLimit &&
        targetWidthPx != null &&
        targetWidthPx > 0 &&
        image.width < (targetWidthPx * 0.9f).toInt()
    ) {
        return null
    }
    return entry
}

private sealed interface ParagraphRenderSegment {
    data class InlineRun(val tokens: List<MdInlineToken>) : ParagraphRenderSegment
    data class PromotedImage(val token: MdInlineToken.Image) : ParagraphRenderSegment
}

private fun splitParagraphSegments(tokens: List<MdInlineToken>): List<ParagraphRenderSegment> {
    val segments = mutableListOf<ParagraphRenderSegment>()
    val pendingInline = mutableListOf<MdInlineToken>()

    fun flushInline() {
        if (pendingInline.isEmpty()) return
        val hasMeaningful = pendingInline.any { token ->
            when (token) {
                is MdInlineToken.PlainText -> token.text.isNotBlank()
                MdInlineToken.LineBreak -> false
                else -> true
            }
        }
        if (hasMeaningful) {
            segments += ParagraphRenderSegment.InlineRun(pendingInline.toList())
        }
        pendingInline.clear()
    }

    for (token in tokens) {
        if (token is MdInlineToken.Image && !isBadgeOrSmallGraphicUrl(token.url)) {
            flushInline()
            segments += ParagraphRenderSegment.PromotedImage(token)
        } else {
            pendingInline += token
        }
    }
    flushInline()
    return segments
}

@Composable
private fun ParagraphBlockView(
    rawText: String,
    referenceLinks: Map<String, Pair<String, String?>>,
    style: TextStyle,
    color: Color,
    linkColor: Color,
    compactSignatureMode: Boolean,
    inlineSignatureFooter: Boolean,
    onMentionClick: ((String) -> Unit)?,
    onFloorClick: ((Int) -> Unit)?,
    onUrlClick: (String) -> Unit,
    onImageClick: (String, String) -> Unit,
    onImageLongClick: (String, String, String?) -> Unit,
) {
    val tokens = remember(rawText, referenceLinks) { tokenizeMarkdownInline(rawText, referenceLinks) }
    val segments = remember(tokens) { splitParagraphSegments(tokens) }
    if (segments.size == 1 && segments[0] is ParagraphRenderSegment.InlineRun) {
        RichInlineTextTokens(
            tokens = (segments[0] as ParagraphRenderSegment.InlineRun).tokens,
            style = style,
            color = color,
            linkColor = linkColor,
            underlineLinks = !inlineSignatureFooter,
            compactInlineSticker = inlineSignatureFooter,
            onMentionClick = onMentionClick,
            onFloorClick = onFloorClick,
            onUrlClick = onUrlClick,
            onInlineImageClick = onImageClick,
            onInlineImageLongClick = onImageLongClick,
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(
            when {
                inlineSignatureFooter -> 3.dp
                compactSignatureMode -> 6.dp
                else -> 10.dp
            },
        ),
    ) {
        segments.forEach { segment ->
            when (segment) {
                is ParagraphRenderSegment.InlineRun -> {
                    RichInlineTextTokens(
                        tokens = segment.tokens,
                        style = style,
                        color = color,
                        linkColor = linkColor,
                        underlineLinks = !inlineSignatureFooter,
                        compactInlineSticker = inlineSignatureFooter,
                        onMentionClick = onMentionClick,
                        onFloorClick = onFloorClick,
                        onUrlClick = onUrlClick,
                        onInlineImageClick = onImageClick,
                        onInlineImageLongClick = onImageLongClick,
                    )
                }
                is ParagraphRenderSegment.PromotedImage -> {
                    val img = segment.token
                    PulseZoomableImageCard(
                        imageUrl = img.url,
                        altText = img.alt,
                        compactMode = compactSignatureMode,
                        signatureFooterMode = inlineSignatureFooter,
                        onClick = {
                            if (!img.linkUrl.isNullOrBlank()) {
                                onUrlClick(unwrapNodeSeekJumpUrl(img.linkUrl))
                            } else {
                                onImageClick(img.url, img.alt)
                            }
                        },
                        onLongClick = { onImageLongClick(img.url, img.alt, img.linkUrl) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarkdownImageRowView(
    items: List<MdImageItem>,
    compactSignatureMode: Boolean,
    inlineSignatureFooter: Boolean,
    linkColor: Color,
    onUrlClick: (String) -> Unit,
    onImageClick: (String, String) -> Unit,
    onImageLongClick: (String, String, String?) -> Unit,
) {
    val nonBadgeItems = remember(items, compactSignatureMode) {
        if (compactSignatureMode) emptyList() else items.filter { !isBadgeOrSmallGraphicUrl(it.imageUrl) }
    }
    val badgeItems = remember(items, compactSignatureMode) {
        if (compactSignatureMode) items else items.filter { isBadgeOrSmallGraphicUrl(it.imageUrl) }
    }
    if (!compactSignatureMode && nonBadgeItems.isNotEmpty() && badgeItems.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            nonBadgeItems.forEach { item ->
                PulseZoomableImageCard(
                    imageUrl = item.imageUrl,
                    altText = item.alt,
                    compactMode = false,
                    signatureFooterMode = inlineSignatureFooter,
                    onClick = {
                        if (!item.linkUrl.isNullOrBlank()) {
                            onUrlClick(unwrapNodeSeekJumpUrl(item.linkUrl))
                        } else {
                            onImageClick(item.imageUrl, item.alt)
                        }
                    },
                    onLongClick = { onImageLongClick(item.imageUrl, item.alt, item.linkUrl) },
                )
            }
            MarkdownImageRowView(
                items = badgeItems,
                compactSignatureMode = compactSignatureMode,
                inlineSignatureFooter = inlineSignatureFooter,
                linkColor = linkColor,
                onUrlClick = onUrlClick,
                onImageClick = onImageClick,
                onImageLongClick = onImageLongClick,
            )
        }
        return
    }

    val allStandalonePhotos = remember(items, compactSignatureMode) {
        !compactSignatureMode && items.all { item -> !isBadgeOrSmallGraphicUrl(item.imageUrl) }
    }
    if (allStandalonePhotos) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items.forEach { item ->
                PulseZoomableImageCard(
                    imageUrl = item.imageUrl,
                    altText = item.alt,
                    compactMode = false,
                    signatureFooterMode = inlineSignatureFooter,
                    onClick = {
                        if (!item.linkUrl.isNullOrBlank()) {
                            onUrlClick(unwrapNodeSeekJumpUrl(item.linkUrl))
                        } else {
                            onImageClick(item.imageUrl, item.alt)
                        }
                    },
                    onLongClick = { onImageLongClick(item.imageUrl, item.alt, item.linkUrl) },
                )
            }
        }
        return
    }

    val context = LocalContext.current
    val imageLoader = remember(context) { SingletonImageLoader.get(context) }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (inlineSignatureFooter) 6.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (inlineSignatureFooter) 4.dp else 6.dp),
    ) {
        items.forEach { item ->
            val resolvedUrl = remember(item.imageUrl) { normalizeMarkdownImageUrl(item.imageUrl) }
            val isBadge = remember(resolvedUrl) { isBadgeOrSmallGraphicUrl(resolvedUrl) }
            var retryNonce by remember(resolvedUrl) { mutableIntStateOf(0) }
            val warmEntry = remember(imageLoader, resolvedUrl, retryNonce) {
                if (retryNonce == 0) {
                    findWarmMemoryCachedImage(
                        imageLoader = imageLoader,
                        resolvedUrl = resolvedUrl,
                        targetWidthPx = null,
                    )
                } else {
                    null
                }
            }
            val warmImage = warmEntry?.image
            val warmPainter = remember(warmImage, context) { warmImage?.asPainter(context) }
            var loadStatus by remember(resolvedUrl, retryNonce) {
                mutableStateOf(
                    when {
                        resolvedUrl.isBlank() -> ImageLoadStatus.ERROR
                        warmImage != null -> ImageLoadStatus.SUCCESS
                        else -> ImageLoadStatus.LOADING
                    },
                )
            }
            var asyncSucceeded by remember(resolvedUrl, retryNonce) { mutableStateOf(false) }
            val enableCrossfade = remember(resolvedUrl, retryNonce) { warmImage == null }
            val request = remember(context, resolvedUrl, retryNonce, enableCrossfade) {
                buildMarkdownCoilRequest(
                    context = context,
                    resolvedUrl = resolvedUrl,
                    retryNonce = retryNonce,
                    enableCrossfade = enableCrossfade,
                )
            }

            val handleClick: () -> Unit = {
                if (!item.linkUrl.isNullOrBlank()) {
                    onUrlClick(unwrapNodeSeekJumpUrl(item.linkUrl))
                } else {
                    onImageClick(resolvedUrl.ifBlank { item.imageUrl }, item.alt)
                }
            }
            val handleLongClick: () -> Unit = {
                onImageLongClick(resolvedUrl.ifBlank { item.imageUrl }, item.alt, item.linkUrl)
            }

            if (loadStatus == ImageLoadStatus.ERROR) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            if (!item.linkUrl.isNullOrBlank()) {
                                handleClick()
                            } else {
                                loadStatus = ImageLoadStatus.LOADING
                                retryNonce++
                            }
                        },
                        onLongClick = handleLongClick,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (item.linkUrl.isNullOrBlank()) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "重试加载图片",
                                tint = linkColor,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                        Text(
                            text = item.alt.ifBlank {
                                if (!item.linkUrl.isNullOrBlank()) "查看链接" else "点击重试"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = linkColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                val maxHeight = when {
                    inlineSignatureFooter -> if (isBadge) 22.dp else 36.dp
                    isBadge -> 32.dp
                    compactSignatureMode -> 108.dp
                    else -> 220.dp
                }
                val minWidth = when {
                    inlineSignatureFooter -> 20.dp
                    isBadge -> 24.dp
                    else -> 96.dp
                }
                val maxWidth = if (inlineSignatureFooter) 200.dp else 300.dp
                val minHeight = when {
                    inlineSignatureFooter -> 16.dp
                    isBadge -> 20.dp
                    else -> 72.dp
                }
                Box(
                    modifier = Modifier
                        .widthIn(min = minWidth, max = maxWidth)
                        .heightIn(min = minHeight, max = maxHeight)
                        .clip(RoundedCornerShape(if (isBadge || inlineSignatureFooter) 6.dp else 10.dp))
                        .background(
                            if (isBadge || inlineSignatureFooter) {
                                Color.Transparent
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        )
                        .combinedClickable(
                            onClick = handleClick,
                            onLongClick = handleLongClick,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (warmPainter != null && !asyncSucceeded) {
                        Image(
                            painter = warmPainter,
                            contentDescription = item.alt.ifBlank { "徽章或插图" },
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .heightIn(min = minHeight, max = maxHeight)
                                .widthIn(min = minWidth, max = maxWidth),
                        )
                    }
                    key(resolvedUrl, retryNonce) {
                        AsyncImage(
                            model = request,
                            contentDescription = item.alt.ifBlank { "徽章或插图" },
                            contentScale = ContentScale.Fit,
                            onLoading = {
                                if (warmPainter == null) {
                                    loadStatus = ImageLoadStatus.LOADING
                                }
                            },
                            onSuccess = {
                                asyncSucceeded = true
                                loadStatus = ImageLoadStatus.SUCCESS
                            },
                            onError = {
                                if (warmPainter == null) {
                                    loadStatus = ImageLoadStatus.ERROR
                                }
                            },
                            modifier = Modifier
                                .heightIn(min = minHeight, max = maxHeight)
                                .widthIn(min = minWidth, max = maxWidth),
                        )
                    }
                    if (loadStatus == ImageLoadStatus.LOADING && !isBadge && !inlineSignatureFooter) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = linkColor.copy(alpha = 0.72f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmbeddedNsAppWidgetCard(
    widget: MdBlock.SpecialWidget,
    onUrlClick: (String) -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val isVote = widget.url.startsWith("nsapp://vote", ignoreCase = true)
    val accent = if (isVote) MaterialTheme.colorScheme.primary else ext.stardustAccent
    val fgColor = if (isVote) MaterialTheme.colorScheme.onPrimaryContainer else ext.onStardustContainer

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseHeroCard(
                accentColor = accent,
                shape = PulseArchitecturalShapes.EditorialCard,
                elevation = PulseElevation.Card,
            )
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.cardPressScale,
                onClick = { onUrlClick(widget.url) },
            )
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(PulseArchitecturalShapes.SmallChip)
                .background(accent.copy(alpha = if (isDark) 0.22f else 0.13f))
                .border(
                    width = 0.8.dp,
                    color = accent.copy(alpha = if (isDark) 0.4f else 0.25f),
                    shape = PulseArchitecturalShapes.SmallChip,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isVote) Icons.Rounded.HowToVote else Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = widget.title.ifBlank { if (isVote) "互动投票组件" else "星尘打赏收款" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (isVote) "点击查看投票详情或参与投票" else "点击打开星尘转账互动面板",
                style = MaterialTheme.typography.labelMedium,
                color = fgColor.copy(alpha = 0.88f),
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(16.dp),
        )
    }
}

private fun isSvgImageUrl(rawUrl: String): Boolean {
    return rawUrl.substringBefore('?').substringBefore('#').lowercase().endsWith(".svg")
}

/**
 * Zoomable Coil Image Card inside Markdown stream (width-fitted for portraits & reports)
 * with loading indicator, error retry banner, and Lightbox tap trigger.
 */
@Composable
fun PulseZoomableImageCard(
    imageUrl: String,
    altText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compactMode: Boolean = false,
    signatureFooterMode: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val context = LocalContext.current
    val imageLoader = remember(context) { SingletonImageLoader.get(context) }
    val localTargetWidthPx = LocalMarkdownImageTargetWidthPx.current
    val metaCache = LocalMarkdownImageMetaCache.current
    val prefetchJobs = LocalMarkdownPrefetchJobs.current
    val activeCardUrls = LocalMarkdownActiveCardUrls.current
    val resolvedUrl = remember(imageUrl) { normalizeMarkdownImageUrl(imageUrl) }
    val isSvgUrl = remember(resolvedUrl) { isSvgImageUrl(resolvedUrl) }
    val isCompact = compactMode || signatureFooterMode
    var retryNonce by remember(resolvedUrl) { mutableIntStateOf(0) }

    val outerModifier = modifier.then(
        when {
            signatureFooterMode -> Modifier.widthIn(max = 220.dp)
            compactMode -> Modifier.widthIn(max = 320.dp)
            else -> Modifier.fillMaxWidth()
        },
    )

    BoxWithConstraints(modifier = outerModifier) {
        val effectiveTargetWidthPx = if (!isCompact) {
            if (constraints.hasBoundedWidth && constraints.maxWidth > 0) {
                constraints.maxWidth
            } else {
                localTargetWidthPx
            }
        } else {
            null
        }

        val activePrefetchJob = remember(prefetchJobs, resolvedUrl, retryNonce) {
            if (retryNonce == 0) prefetchJobs?.get(resolvedUrl) else null
        }
        var awaitingPrefetch by remember(resolvedUrl, retryNonce, activePrefetchJob) {
            val warmQuick = if (retryNonce == 0) {
                findWarmMemoryCachedImage(
                    imageLoader = imageLoader,
                    resolvedUrl = resolvedUrl,
                    targetWidthPx = effectiveTargetWidthPx,
                )
            } else {
                null
            }
            mutableStateOf(warmQuick == null && activePrefetchJob?.isActive == true)
        }
        val warmEntry = remember(imageLoader, resolvedUrl, retryNonce, effectiveTargetWidthPx, awaitingPrefetch) {
            if (retryNonce == 0) {
                findWarmMemoryCachedImage(
                    imageLoader = imageLoader,
                    resolvedUrl = resolvedUrl,
                    targetWidthPx = effectiveTargetWidthPx,
                )
            } else {
                null
            }
        }
        val warmImage = warmEntry?.image
        val warmPainter = remember(warmImage, context) { warmImage?.asPainter(context) }
        val recordedMeta = remember(metaCache, resolvedUrl, retryNonce, awaitingPrefetch) {
            if (retryNonce == 0) metaCache?.get(resolvedUrl) else null
        }
        val initialLayoutMeta = remember(warmImage, recordedMeta, isSvgUrl) {
            if (warmImage != null && warmImage.width > 0 && warmImage.height > 0) {
                computeImageLayoutMeta(
                    width = warmImage.width.toFloat(),
                    height = warmImage.height.toFloat(),
                    isSvgUrl = isSvgUrl,
                )
            } else {
                recordedMeta?.aspectRatio to (recordedMeta?.isTinyRaster ?: false)
            }
        }
        val hasWarmOrRecordedSuccess = warmImage != null || recordedMeta?.loadedSuccess == true

        var loadStatus by remember(resolvedUrl, retryNonce) {
            mutableStateOf(
                when {
                    resolvedUrl.isBlank() -> ImageLoadStatus.ERROR
                    hasWarmOrRecordedSuccess -> ImageLoadStatus.SUCCESS
                    else -> ImageLoadStatus.LOADING
                },
            )
        }
        var loadedAspectRatio by remember(resolvedUrl, retryNonce) {
            mutableStateOf(initialLayoutMeta.first)
        }
        var isTinyRasterGraphic by remember(resolvedUrl, retryNonce) {
            mutableStateOf(initialLayoutMeta.second)
        }
        var asyncSucceeded by remember(resolvedUrl, retryNonce) {
            mutableStateOf(false)
        }

        LaunchedEffect(activePrefetchJob) {
            if (activePrefetchJob != null && awaitingPrefetch) {
                try {
                    val result = activePrefetchJob.await()
                    if (result is SuccessResult) {
                        val img = result.image
                        val (ratio, tiny) = computeImageLayoutMeta(
                            width = img.width.toFloat(),
                            height = img.height.toFloat(),
                            isSvgUrl = isSvgUrl,
                        )
                        isTinyRasterGraphic = tiny
                        if (ratio != null) {
                            loadedAspectRatio = ratio
                        }
                        metaCache?.put(
                            resolvedUrl,
                            CachedMarkdownImageMeta(
                                aspectRatio = ratio,
                                isTinyRaster = tiny,
                                loadedSuccess = true,
                            ),
                        )
                        loadStatus = ImageLoadStatus.SUCCESS
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Fall back to regular AsyncImage request if prefetch was cancelled or failed.
                } finally {
                    awaitingPrefetch = false
                }
            }
        }

        SideEffect {
            if (activeCardUrls != null &&
                resolvedUrl.isNotBlank() &&
                loadStatus == ImageLoadStatus.LOADING &&
                !awaitingPrefetch
            ) {
                activeCardUrls.add(resolvedUrl)
            }
        }
        DisposableEffect(activeCardUrls, resolvedUrl) {
            onDispose {
                activeCardUrls?.remove(resolvedUrl)
            }
        }

        val enableCrossfade = remember(resolvedUrl, retryNonce, awaitingPrefetch) {
            !hasWarmOrRecordedSuccess
        }
        val imageRequest = remember(context, resolvedUrl, retryNonce, effectiveTargetWidthPx, enableCrossfade) {
            buildMarkdownCoilRequest(
                context = context,
                resolvedUrl = resolvedUrl,
                retryNonce = retryNonce,
                targetWidthPx = effectiveTargetWidthPx,
                enableCrossfade = enableCrossfade,
            )
        }

        Box(
            modifier = Modifier
                .then(
                    when {
                        isCompact -> Modifier
                        else -> {
                            Modifier
                                .fillMaxWidth()
                                .then(
                                    if (loadStatus == ImageLoadStatus.LOADING) {
                                        Modifier.heightIn(min = 148.dp)
                                    } else {
                                        Modifier
                                    },
                                )
                                .then(
                                    if (!isDark) {
                                        Modifier.shadow(
                                            elevation = PulseElevation.Subtle,
                                            shape = PulseArchitecturalShapes.InlineImage,
                                            clip = false,
                                            ambientColor = ext.softShadowColor,
                                            spotColor = ext.softShadowColor,
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                        }
                    },
                )
                .clip(PulseArchitecturalShapes.InlineImage)
                .background(
                    if (isCompact) {
                        if (loadStatus == ImageLoadStatus.SUCCESS) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                )
                .then(
                    if (!isCompact) {
                        Modifier.border(
                            width = 0.8.dp,
                            color = ext.cardBorder.copy(alpha = 0.7f),
                            shape = PulseArchitecturalShapes.InlineImage,
                        )
                    } else {
                        Modifier
                    },
                )
                .combinedClickable(
                    onClick = {
                        if (loadStatus == ImageLoadStatus.ERROR) {
                            if (resolvedUrl.isNotBlank()) {
                                asyncSucceeded = false
                                loadStatus = ImageLoadStatus.LOADING
                                retryNonce++
                            }
                        } else if (resolvedUrl.isNotBlank()) {
                            onClick()
                        }
                    },
                    onLongClick = if (resolvedUrl.isNotBlank()) onLongClick else null,
                ),
            contentAlignment = if (isCompact) Alignment.CenterStart else Alignment.Center,
        ) {
            if (loadStatus == ImageLoadStatus.ERROR) {
                Row(
                    modifier = Modifier
                        .then(if (isCompact) Modifier else Modifier.fillMaxWidth())
                        .padding(if (isCompact) 8.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "重试加载",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = altText.ifBlank {
                            if (resolvedUrl.isBlank()) "图片地址无效或不受支持" else "图片加载失败，点按重试"
                        },
                        style = if (isCompact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (resolvedUrl.isNotBlank()) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                            modifier = Modifier.clickable(onClick = onClick),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = "打开原图",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = "原图",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }
            } else {
                val ratio = loadedAspectRatio
                val imageModifier = when {
                    signatureFooterMode -> {
                        if (ratio != null) {
                            Modifier
                                .widthIn(min = 24.dp, max = 200.dp)
                                .heightIn(min = 16.dp, max = 36.dp)
                                .aspectRatio(ratio, matchHeightConstraintsFirst = true)
                        } else {
                            Modifier
                                .width(88.dp)
                                .height(26.dp)
                        }
                    }
                    compactMode -> {
                        if (ratio != null) {
                            Modifier
                                .widthIn(min = 48.dp, max = 300.dp)
                                .heightIn(min = 28.dp, max = 128.dp)
                                .aspectRatio(ratio, matchHeightConstraintsFirst = true)
                        } else {
                            Modifier
                                .width(128.dp)
                                .height(48.dp)
                        }
                    }
                    isTinyRasterGraphic -> {
                        Modifier
                            .widthIn(min = 32.dp, max = 140.dp)
                            .heightIn(min = 24.dp, max = 96.dp)
                    }
                    ratio != null -> {
                        // Remove the narrow 460dp height cap so tall portrait report images (e.g. Hardware /
                        // IP quality tests) fill 100% screen width cleanly without vertical clipping or side gaps.
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp, max = 3000.dp)
                            .aspectRatio(ratio, matchHeightConstraintsFirst = false)
                    }
                    loadStatus == ImageLoadStatus.SUCCESS -> {
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 3000.dp)
                    }
                    else -> {
                        // Keep height unconstrained on AsyncImage while loading so Coil's SizeResolver
                        // decodes against full card width rather than downsampling tall reports into 148dp.
                        Modifier.fillMaxWidth()
                    }
                }

                if (warmPainter != null && !asyncSucceeded) {
                    Image(
                        painter = warmPainter,
                        contentDescription = altText.ifBlank { "帖子插图" },
                        contentScale = if (isCompact || isTinyRasterGraphic) ContentScale.Fit else ContentScale.FillWidth,
                        modifier = imageModifier,
                    )
                }

                if (!awaitingPrefetch) {
                    key(resolvedUrl, retryNonce) {
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = altText.ifBlank { "帖子插图" },
                            contentScale = if (isCompact || isTinyRasterGraphic) ContentScale.Fit else ContentScale.FillWidth,
                            onLoading = {
                                if (warmPainter == null && recordedMeta?.loadedSuccess != true) {
                                    loadStatus = ImageLoadStatus.LOADING
                                }
                            },
                            onSuccess = { success ->
                                asyncSucceeded = true
                                loadStatus = ImageLoadStatus.SUCCESS
                                activeCardUrls?.remove(resolvedUrl)
                                val intrinsic = success.painter.intrinsicSize
                                if (intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f) {
                                    val (computedRatio, tinyRaster) = computeImageLayoutMeta(
                                        width = intrinsic.width,
                                        height = intrinsic.height,
                                        isSvgUrl = isSvgUrl,
                                    )
                                    isTinyRasterGraphic = tinyRaster
                                    if (computedRatio != null) {
                                        loadedAspectRatio = computedRatio
                                    }
                                    metaCache?.put(
                                        resolvedUrl,
                                        CachedMarkdownImageMeta(
                                            aspectRatio = computedRatio ?: loadedAspectRatio,
                                            isTinyRaster = tinyRaster,
                                            loadedSuccess = true,
                                        ),
                                    )
                                } else {
                                    metaCache?.put(
                                        resolvedUrl,
                                        CachedMarkdownImageMeta(
                                            aspectRatio = loadedAspectRatio,
                                            isTinyRaster = isTinyRasterGraphic,
                                            loadedSuccess = true,
                                        ),
                                    )
                                }
                            },
                            onError = {
                                activeCardUrls?.remove(resolvedUrl)
                                if (warmPainter == null) {
                                    loadStatus = ImageLoadStatus.ERROR
                                }
                            },
                            modifier = imageModifier,
                        )
                    }
                }

                if (loadStatus == ImageLoadStatus.LOADING) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(if (isCompact) 16.dp else 22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        )
                        if (!isCompact) {
                            Text(
                                text = "正在加载图片…",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (!isCompact && loadStatus == ImageLoadStatus.SUCCESS) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.62f))
                            .border(0.8.dp, Color.White.copy(alpha = 0.24f), CircleShape)
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ZoomIn,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = "放大",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Full-screen Pinch-to-Zoom & Double-Tap Zoom Lightbox Dialog.
 */
@Composable
fun PulseImageLightboxDialog(
    imageUrl: String,
    altText: String = "",
    onDismiss: () -> Unit,
    onOpenExternal: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val saveImage = rememberMarkdownImageSaveAction()
    val resolvedUrl = remember(imageUrl) { normalizeMarkdownImageUrl(imageUrl) }
    var retryNonce by remember(resolvedUrl) { mutableIntStateOf(0) }
    var loadStatus by remember(resolvedUrl, retryNonce) {
        mutableStateOf(if (resolvedUrl.isBlank()) ImageLoadStatus.ERROR else ImageLoadStatus.LOADING)
    }
    val imageRequest = remember(context, resolvedUrl, retryNonce) {
        buildMarkdownCoilRequest(
            context = context,
            resolvedUrl = resolvedUrl,
            retryNonce = retryNonce,
        )
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var showActionCard by remember { mutableStateOf(false) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 8f)
        scale = newScale
        offset = if (newScale <= 1.01f) {
            Offset.Zero
        } else {
            offset + panChange
        }
    }

    Dialog(
        onDismissRequest = {
            if (showActionCard) {
                showActionCard = false
            } else {
                onDismiss()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black.copy(alpha = 0.96f),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (loadStatus != ImageLoadStatus.ERROR) {
                    key(resolvedUrl, retryNonce) {
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = altText.ifBlank { "放大图片" },
                            contentScale = ContentScale.Fit,
                            onLoading = { loadStatus = ImageLoadStatus.LOADING },
                            onSuccess = { loadStatus = ImageLoadStatus.SUCCESS },
                            onError = { loadStatus = ImageLoadStatus.ERROR },
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onDoubleTap = {
                                            if (scale > 1.2f) {
                                                scale = 1f
                                                offset = Offset.Zero
                                            } else {
                                                scale = 2.5f
                                            }
                                        },
                                        onTap = {
                                            if (showActionCard) {
                                                showActionCard = false
                                            } else if (scale <= 1.05f) {
                                                onDismiss()
                                            }
                                        },
                                        onLongPress = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            showActionCard = true
                                        },
                                    )
                                }
                                .transformable(state = transformState)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                },
                        )
                    }
                }

                if (loadStatus == ImageLoadStatus.LOADING) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(34.dp),
                    )
                }

                if (loadStatus == ImageLoadStatus.ERROR) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "原图加载失败，请重试或在浏览器打开",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.88f),
                            textAlign = TextAlign.Center,
                        )
                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.16f),
                            modifier = Modifier
                                .border(0.8.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                                .clickable {
                                    if (resolvedUrl.isNotBlank()) {
                                        loadStatus = ImageLoadStatus.LOADING
                                        retryNonce++
                                    }
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = "重新加载",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }

                // Top controls bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.14f))
                            .border(0.8.dp, Color.White.copy(alpha = 0.24f), CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "关闭预览",
                            tint = Color.White,
                        )
                    }

                    if (altText.isNotBlank()) {
                        Text(
                            text = altText,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.88f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                            textAlign = TextAlign.Center,
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(
                            onClick = {
                                saveImage(resolvedUrl.ifBlank { imageUrl }, altText)
                            },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.14f))
                                .border(0.8.dp, Color.White.copy(alpha = 0.24f), CircleShape),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = "下载图片",
                                tint = Color.White,
                            )
                        }

                        if (onOpenExternal != null) {
                            IconButton(
                                onClick = onOpenExternal,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.14f))
                                    .border(0.8.dp, Color.White.copy(alpha = 0.24f), CircleShape),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = "在浏览器打开原图",
                                    tint = Color.White,
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = scale > 1.05f && !showActionCard,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 36.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f))
                            .border(0.8.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = "${(scale * 100).toInt()}% · 双击还原",
                            style = NodePulseThemeTokens.extraTypography.tabularBadge,
                            color = Color.White.copy(alpha = 0.92f),
                        )
                    }
                }

                if (showActionCard) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.42f))
                            .clickable { showActionCard = false },
                    )
                }

                AnimatedVisibility(
                    visible = showActionCard,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    val targetUrl = resolvedUrl.ifBlank { imageUrl }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .clip(PulseArchitecturalShapes.SheetContainer)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .border(
                                width = 0.8.dp,
                                color = NodePulseThemeTokens.extendedColors.cardBorder,
                                shape = PulseArchitecturalShapes.SheetContainer,
                            )
                            .padding(vertical = 8.dp),
                    ) {
                        MarkdownImageActionSheetRow(
                            icon = Icons.Rounded.Download,
                            title = "下载保存到相册",
                            subtitle = "保存原图至系统相册 Pictures/Anode",
                            highlightPrimary = true,
                            onClick = {
                                showActionCard = false
                                saveImage(targetUrl, altText)
                            },
                        )
                        PulseGradientDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                        MarkdownImageActionSheetRow(
                            icon = Icons.Rounded.ContentCopy,
                            title = "复制图片链接",
                            onClick = {
                                copyPlainTextToClipboard(context, targetUrl)
                                Toast.makeText(context, "已复制图片链接", Toast.LENGTH_SHORT).show()
                                showActionCard = false
                            },
                        )
                        if (onOpenExternal != null) {
                            PulseGradientDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                modifier = Modifier.padding(horizontal = 14.dp),
                            )
                            MarkdownImageActionSheetRow(
                                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                                title = "在浏览器打开",
                                onClick = {
                                    showActionCard = false
                                    onOpenExternal()
                                },
                            )
                        }
                        PulseGradientDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                        MarkdownImageActionSheetRow(
                            icon = Icons.Rounded.Close,
                            title = "取消",
                            onClick = { showActionCard = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownImageActionBottomSheet(
    target: ImageActionTarget,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onViewFullscreen: () -> Unit,
    onCopyUrl: () -> Unit,
    onOpenExternal: () -> Unit,
    onInspectLinkUrl: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(3.5.dp)
                        .height(16.dp)
                        .clip(CircleShape)
                        .background(PulseBrushes.brandGradient(horizontal = false)),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "图片操作",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val subtitleText = target.altText.trim().ifBlank {
                        normalizeMarkdownImageUrl(target.imageUrl)
                    }
                    if (subtitleText.isNotBlank()) {
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(
                        shape = PulseArchitecturalShapes.EditorialCard,
                        elevation = PulseElevation.Subtle,
                    )
                    .padding(vertical = 4.dp),
            ) {
                MarkdownImageActionSheetRow(
                    icon = Icons.Rounded.Download,
                    title = "下载保存到相册",
                    subtitle = "保存原图至系统相册 Pictures/Anode",
                    highlightPrimary = true,
                    onClick = onDownload,
                )
                PulseGradientDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                MarkdownImageActionSheetRow(
                    icon = Icons.Rounded.ZoomIn,
                    title = "全屏查看图片",
                    subtitle = "双指缩放查看高清细节",
                    onClick = onViewFullscreen,
                )
                PulseGradientDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                MarkdownImageActionSheetRow(
                    icon = Icons.Rounded.ContentCopy,
                    title = "复制图片链接",
                    onClick = onCopyUrl,
                )
                PulseGradientDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                MarkdownImageActionSheetRow(
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    title = "在浏览器打开",
                    onClick = onOpenExternal,
                )
                if (onInspectLinkUrl != null) {
                    PulseGradientDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                    MarkdownImageActionSheetRow(
                        icon = Icons.Rounded.Bolt,
                        title = "链接操作 / 加入补货监控",
                        subtitle = target.linkUrl?.let { unwrapNodeSeekJumpUrl(it) },
                        highlightPrimary = true,
                        onClick = onInspectLinkUrl,
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun MarkdownImageActionSheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    highlightPrimary: Boolean = false,
    onClick: () -> Unit,
) {
    val badgeBg = if (highlightPrimary) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val iconColor = if (highlightPrimary) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(PulseArchitecturalShapes.SmallChip)
                .background(badgeBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (highlightPrimary) FontWeight.SemiBold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── Rich Inline Parser & Composable (`@username`, `#floor`, `:sticker:`, links, code) ─────

/**
 * Renders a NodeSeek official sticker (`ac` / `yct` / `xhj` / Fluent `emoji`).
 *
 * For Fluent video stickers (`isVideoEmoji == true`):
 * - Inline rendering (`preferStaticPreview = false`) loads the animated `.webm` first (decoded
 *   with alpha transparency via [WebmStickerDecoder]) and seamlessly falls back to the static
 *   transparent `.png` if WebM decoding fails on a device.
 * - Picker grids (`preferStaticPreview = true`) load the lightweight transparent `.png` preview
 *   first and fall back to `.webm` if `.png` is unavailable.
 */
@Composable
internal fun NodeSeekStickerAsyncImage(
    sticker: NodeSeekSticker,
    modifier: Modifier = Modifier,
    preferStaticPreview: Boolean = false,
    enableCrossfade: Boolean = true,
) {
    val context = LocalContext.current
    var useFallbackUrl by remember(sticker.code, preferStaticPreview) { mutableStateOf(false) }

    val primaryUrl = if (preferStaticPreview) sticker.staticPreviewUrl else sticker.imageUrl
    val fallbackUrl = if (preferStaticPreview) sticker.imageUrl else sticker.staticPreviewUrl
    val activeUrl = if (useFallbackUrl && fallbackUrl != primaryUrl) fallbackUrl else primaryUrl

    val request = remember(context, activeUrl, enableCrossfade, useFallbackUrl, sticker.code) {
        buildMarkdownCoilRequest(
            context = context,
            resolvedUrl = normalizeMarkdownImageUrl(activeUrl),
            enableCrossfade = enableCrossfade,
            onError = if (!useFallbackUrl && fallbackUrl != primaryUrl) {
                { useFallbackUrl = true }
            } else {
                null
            },
        )
    }

    AsyncImage(
        model = request,
        contentDescription = ":${sticker.code}:",
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

@Composable
private fun RichInlineText(
    rawText: String,
    referenceLinks: Map<String, Pair<String, String?>>,
    style: TextStyle,
    color: Color,
    linkColor: Color,
    onMentionClick: ((String) -> Unit)?,
    onFloorClick: ((Int) -> Unit)?,
    onUrlClick: (String) -> Unit,
    onInlineImageClick: ((String, String) -> Unit)?,
    modifier: Modifier = Modifier,
    underlineLinks: Boolean = true,
    compactInlineSticker: Boolean = false,
    onInlineImageLongClick: ((String, String, String?) -> Unit)? = null,
) {
    val tokens = remember(rawText, referenceLinks) { tokenizeMarkdownInline(rawText, referenceLinks) }
    RichInlineTextTokens(
        tokens = tokens,
        style = style,
        color = color,
        linkColor = linkColor,
        underlineLinks = underlineLinks,
        compactInlineSticker = compactInlineSticker,
        onMentionClick = onMentionClick,
        onFloorClick = onFloorClick,
        onUrlClick = onUrlClick,
        onInlineImageClick = onInlineImageClick,
        onInlineImageLongClick = onInlineImageLongClick,
        modifier = modifier,
    )
}

@Composable
private fun RichInlineTextTokens(
    tokens: List<MdInlineToken>,
    style: TextStyle,
    color: Color,
    linkColor: Color,
    onMentionClick: ((String) -> Unit)?,
    onFloorClick: ((Int) -> Unit)?,
    onUrlClick: (String) -> Unit,
    onInlineImageClick: ((String, String) -> Unit)?,
    modifier: Modifier = Modifier,
    underlineLinks: Boolean = true,
    compactInlineSticker: Boolean = false,
    onInlineImageLongClick: ((String, String, String?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val insideTabs = LocalInsideTabsContainer.current
    val isDark = NodePulseThemeTokens.isDark
    val codeBg = blendColor(
        source = MaterialTheme.colorScheme.surfaceContainerHigh,
        target = linkColor,
        fraction = if (isDark) 0.16f else 0.07f,
    )
    val codeFg = blendColor(
        source = MaterialTheme.colorScheme.onSurface,
        target = linkColor,
        fraction = 0.22f,
    )
    val currentOnMention by rememberUpdatedState(onMentionClick)
    val currentOnFloor by rememberUpdatedState(onFloorClick)
    val currentOnUrl by rememberUpdatedState(onUrlClick)
    val currentOnInlineImage by rememberUpdatedState(onInlineImageClick)
    val currentOnInlineImageLongClick by rememberUpdatedState(onInlineImageLongClick)

    val parsed = remember(tokens, linkColor, codeBg, codeFg, underlineLinks) {
        buildInlineAnnotatedResultFromTokens(
            tokens = tokens,
            linkColor = linkColor,
            codeBg = codeBg,
            codeFg = codeFg,
            underlineLinks = underlineLinks,
            onMentionClick = { username -> currentOnMention?.invoke(username) },
            hasMentionHandler = onMentionClick != null,
            onFloorClick = { floor -> currentOnFloor?.invoke(floor) },
            hasFloorHandler = onFloorClick != null,
            onUrlClick = { url -> currentOnUrl(url) },
        )
    }

    val inlineContentMap = remember(context, parsed.stickers, parsed.inlineImages, compactInlineSticker, insideTabs) {
        val stickerSize = if (compactInlineSticker) 15.sp else 24.sp
        buildMap {
            parsed.stickers.forEach { (id, sticker) ->
                put(
                    id,
                    InlineTextContent(
                        Placeholder(
                            width = stickerSize,
                            height = stickerSize,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                        )
                    ) {
                        NodeSeekStickerAsyncImage(
                            sticker = sticker,
                            preferStaticPreview = false,
                            enableCrossfade = !insideTabs,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                )
            }
            parsed.inlineImages.forEach { (id, img) ->
                val isBadge = isBadgeOrSmallGraphicUrl(img.imageUrl)
                val normalized = normalizeMarkdownImageUrl(img.imageUrl)
                val inlineWidth = when {
                    isBadge -> if (compactInlineSticker) 68.sp else 88.sp
                    compactInlineSticker -> 15.sp
                    else -> 24.sp
                }
                val inlineHeight = when {
                    isBadge -> if (compactInlineSticker) 16.sp else 20.sp
                    compactInlineSticker -> 15.sp
                    else -> 24.sp
                }
                put(
                    id,
                    InlineTextContent(
                        Placeholder(
                            width = inlineWidth,
                            height = inlineHeight,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                        )
                    ) {
                        AsyncImage(
                            model = buildMarkdownCoilRequest(
                                context = context,
                                resolvedUrl = normalized,
                                enableCrossfade = !insideTabs,
                            ),
                            contentDescription = img.alt,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .combinedClickable(
                                    onClick = {
                                        if (!img.linkUrl.isNullOrBlank()) {
                                            currentOnUrl(unwrapNodeSeekJumpUrl(img.linkUrl))
                                        } else {
                                            currentOnInlineImage?.invoke(normalized, img.alt)
                                        }
                                    },
                                    onLongClick = {
                                        currentOnInlineImageLongClick?.invoke(normalized, img.alt, img.linkUrl)
                                    },
                                ),
                        )
                    }
                )
            }
        }
    }

    val urlLongClick = LocalMarkdownUrlLongClick.current
    val selectionEnabled = LocalMarkdownSelectionEnabled.current
    val currentOnUrlLongClick by rememberUpdatedState(urlLongClick)
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val hasUrlLinks = remember(parsed.annotatedString) {
        parsed.annotatedString.getLinkAnnotations(0, parsed.annotatedString.length)
            .any { (it.item as? LinkAnnotation.Clickable)?.tag?.startsWith("url:") == true }
    }

    val linkLongPressModifier = if (urlLongClick != null && !selectionEnabled && hasUrlLinks) {
        Modifier.pointerInput(parsed.annotatedString) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val layout = textLayoutResult ?: return@awaitEachGesture
                val charOffset = layout.getOffsetForPosition(down.position)
                val matchedUrl = parsed.annotatedString
                    .getLinkAnnotations(charOffset, charOffset)
                    .mapNotNull { (it.item as? LinkAnnotation.Clickable)?.tag }
                    .firstOrNull { it.startsWith("url:") }
                    ?.removePrefix("url:")
                    ?: return@awaitEachGesture

                val triggerTimeout = (viewConfiguration.longPressTimeoutMillis - 50L).coerceAtLeast(300L)
                val touchSlop = viewConfiguration.touchSlop
                val cancelledOrTapped = withTimeoutOrNull(triggerTimeout) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: return@withTimeoutOrNull true
                        if (!change.pressed) return@withTimeoutOrNull true
                        if ((change.position - down.position).getDistance() > touchSlop) {
                            return@withTimeoutOrNull true
                        }
                    }
                }
                if (cancelledOrTapped == null) {
                    currentOnUrlLongClick?.invoke(matchedUrl)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { if (it.id == down.id) it.consume() }
                    } while (event.changes.any { it.id == down.id && it.pressed })
                }
            }
        }
    } else {
        Modifier
    }

    Text(
        text = parsed.annotatedString,
        style = style.copy(color = color),
        inlineContent = inlineContentMap,
        onTextLayout = { textLayoutResult = it },
        modifier = modifier.then(linkLongPressModifier),
    )
}

private data class InlineParseResult(
    val annotatedString: AnnotatedString,
    val stickers: Map<String, NodeSeekSticker>,
    val inlineImages: Map<String, MdImageItem>,
)

@Immutable
internal sealed interface MdInlineToken {
    data class PlainText(val text: String) : MdInlineToken
    data class CodeSpan(val code: String) : MdInlineToken
    data class Image(val alt: String, val url: String, val linkUrl: String? = null) : MdInlineToken
    data class Link(val labelTokens: List<MdInlineToken>, val url: String) : MdInlineToken
    data class Sticker(val sticker: NodeSeekSticker) : MdInlineToken
    data class Mention(val username: String) : MdInlineToken
    data class FloorRef(val floor: Int) : MdInlineToken
    data class Styled(
        val children: List<MdInlineToken>,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strikethrough: Boolean = false,
        val underline: Boolean = false,
        val isKbd: Boolean = false,
    ) : MdInlineToken
    data object LineBreak : MdInlineToken
}

private fun buildInlineAnnotatedResultFromTokens(
    tokens: List<MdInlineToken>,
    linkColor: Color,
    codeBg: Color,
    codeFg: Color,
    onMentionClick: (String) -> Unit,
    hasMentionHandler: Boolean,
    onFloorClick: (Int) -> Unit,
    hasFloorHandler: Boolean,
    onUrlClick: (String) -> Unit,
    underlineLinks: Boolean = true,
): InlineParseResult {
    val stickers = mutableMapOf<String, NodeSeekSticker>()
    val inlineImages = mutableMapOf<String, MdImageItem>()
    var inlineSeq = 0

    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = linkColor,
            fontWeight = FontWeight.Medium,
            textDecoration = if (underlineLinks) TextDecoration.Underline else TextDecoration.None,
        )
    )
    val chipLinkStyles = TextLinkStyles(
        style = SpanStyle(
            color = linkColor,
            fontWeight = FontWeight.SemiBold,
        )
    )

    fun AnnotatedString.Builder.appendTokens(list: List<MdInlineToken>) {
        for (token in list) {
            when (token) {
                is MdInlineToken.PlainText -> append(token.text)

                MdInlineToken.LineBreak -> append("\n")

                is MdInlineToken.CodeSpan -> {
                    val s = length
                    append(" ${token.code} ")
                    addStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 0.90.emOrUnspecified(),
                            background = codeBg,
                            color = codeFg,
                        ),
                        s,
                        length,
                    )
                }

                is MdInlineToken.Image -> {
                    val id = "inline_img_${inlineSeq++}"
                    inlineImages[id] = MdImageItem(
                        imageUrl = token.url,
                        alt = token.alt,
                        linkUrl = token.linkUrl,
                    )
                    appendInlineContent(id, token.alt.ifBlank { "[图片]" })
                }

                is MdInlineToken.Link -> {
                    val targetUrl = unwrapNodeSeekJumpUrl(token.url)
                    val linkIndex = pushLink(
                        LinkAnnotation.Clickable(
                            tag = "url:$targetUrl",
                            styles = linkStyles,
                            linkInteractionListener = { onUrlClick(targetUrl) },
                        )
                    )
                    appendTokens(token.labelTokens)
                    pop(linkIndex)
                }

                is MdInlineToken.Sticker -> {
                    val id = "sticker_${inlineSeq++}"
                    stickers[id] = token.sticker
                    appendInlineContent(id, ":${token.sticker.code}:")
                }

                is MdInlineToken.Mention -> {
                    val s = length
                    append("@${token.username}")
                    if (hasMentionHandler) {
                        addLink(
                            clickable = LinkAnnotation.Clickable(
                                tag = "mention:${token.username}",
                                styles = chipLinkStyles,
                                linkInteractionListener = { onMentionClick(token.username) },
                            ),
                            start = s,
                            end = length,
                        )
                    } else {
                        addStyle(
                            SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold),
                            s,
                            length,
                        )
                    }
                }

                is MdInlineToken.FloorRef -> {
                    val s = length
                    append("#${token.floor}")
                    if (hasFloorHandler) {
                        addLink(
                            clickable = LinkAnnotation.Clickable(
                                tag = "floor:${token.floor}",
                                styles = chipLinkStyles,
                                linkInteractionListener = { onFloorClick(token.floor) },
                            ),
                            start = s,
                            end = length,
                        )
                    } else {
                        addStyle(
                            SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold),
                            s,
                            length,
                        )
                    }
                }

                is MdInlineToken.Styled -> {
                    val textDeco = when {
                        token.strikethrough && token.underline ->
                            TextDecoration.combine(listOf(TextDecoration.LineThrough, TextDecoration.Underline))
                        token.strikethrough -> TextDecoration.LineThrough
                        token.underline -> TextDecoration.Underline
                        else -> null
                    }
                    val styleIndex = pushStyle(
                        SpanStyle(
                            fontWeight = if (token.bold || token.isKbd) FontWeight.Bold else null,
                            fontStyle = if (token.italic) FontStyle.Italic else null,
                            textDecoration = textDeco,
                            fontFamily = if (token.isKbd) FontFamily.Monospace else null,
                            fontSize = if (token.isKbd) 0.88.emOrUnspecified() else androidx.compose.ui.unit.TextUnit.Unspecified,
                            background = if (token.isKbd) codeBg else Color.Unspecified,
                            color = if (token.isKbd) codeFg else Color.Unspecified,
                        )
                    )
                    if (token.isKbd) append(" ")
                    appendTokens(token.children)
                    if (token.isKbd) append(" ")
                    pop(styleIndex)
                }
            }
        }
    }

    val annotated = buildAnnotatedString {
        appendTokens(tokens)
    }

    return InlineParseResult(
        annotatedString = annotated,
        stickers = stickers,
        inlineImages = inlineImages,
    )
}

private const val MAX_INLINE_DEPTH = 12
private const val ESCAPABLE_PUNCTUATION = """!"#$%&'()*+,-./:;<=>?@[\]^_`{|}~"""
private val INLINE_BR_REGEX = Regex("""^<br\s*/?>""", RegexOption.IGNORE_CASE)
private val INLINE_AUTOLINK_REGEX = Regex("""^<(https?://[^>\s]+)>""", RegexOption.IGNORE_CASE)
private val INLINE_HTML_IMG_REGEX = Regex("""^<img\b([^>]*?)/?>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val INLINE_HTML_VIDEO_REGEX = Regex(
    """^<video\b([^>]*)>(.*?)</video>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val INLINE_HTML_SOURCE_SRC_REGEX = Regex(
    """<source\b[^>]*\bsrc\s*=\s*(?:"([^"]+)"|'([^']+)'|([^\s"'=<>`]+))""",
    RegexOption.IGNORE_CASE,
)
private val INLINE_HTML_ANCHOR_OPEN_REGEX = Regex("""^<a\b([^>]*?)>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val INLINE_HTML_PAIRED_TAG_REGEX = Regex(
    """^<(strong|b|em|i|del|s|strike|u|ins|kbd|code|h[1-6]|span|font|small|mark|sub|sup)\b[^>]*>(.*?)</\1>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val INLINE_STRAY_HTML_TAG_REGEX = Regex(
    """^</?(?:h[1-6]|p|div|center|span|font|small|section|article)\b[^>]*>""",
    RegexOption.IGNORE_CASE,
)
private val HTML_ATTR_REGEX = Regex("""([a-zA-Z_:][\w:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""")
private val STICKER_CODE_REGEX = Regex("""^[a-zA-Z]{2,5}\d{2,4}$""")
private val FLOOR_DIGITS_REGEX = Regex("""^[0-9]{1,5}""")

internal fun tokenizeMarkdownInline(
    text: String,
    referenceLinks: Map<String, Pair<String, String?>> = emptyMap(),
    depth: Int = 0,
): List<MdInlineToken> {
    if (text.isEmpty()) return emptyList()
    if (depth >= MAX_INLINE_DEPTH) return listOf(MdInlineToken.PlainText(decodeHtmlEntitiesMinimal(text)))

    val tokens = mutableListOf<MdInlineToken>()
    val plainBuffer = StringBuilder()

    fun flushPlain() {
        if (plainBuffer.isNotEmpty()) {
            val decoded = decodeHtmlEntitiesMinimal(plainBuffer.toString())
            if (decoded.isNotEmpty()) {
                val last = tokens.lastOrNull()
                if (last is MdInlineToken.PlainText) {
                    tokens[tokens.lastIndex] = MdInlineToken.PlainText(last.text + decoded)
                } else {
                    tokens += MdInlineToken.PlainText(decoded)
                }
            }
            plainBuffer.clear()
        }
    }

    val n = text.length
    var i = 0
    // Negative-lookup watermarks ensure O(N) total scan even on adversarial inputs with thousands
    // of unmatched brackets, backticks, or emphasis delimiters.
    var noMatchingBracketFrom = Int.MAX_VALUE
    var noSingleTickFrom = Int.MAX_VALUE
    var noTildeStrikeFrom = Int.MAX_VALUE
    var noTripleStarFrom = Int.MAX_VALUE
    var noTripleUnderscoreFrom = Int.MAX_VALUE
    var noDoubleStarFrom = Int.MAX_VALUE
    var noDoubleUnderscoreFrom = Int.MAX_VALUE
    var noSingleStarFrom = Int.MAX_VALUE
    var noSingleUnderscoreFrom = Int.MAX_VALUE

    while (i < n) {
        val c = text[i]

        // 1. Backslash escape
        if (c == '\\' && i + 1 < n && text[i + 1] in ESCAPABLE_PUNCTUATION) {
            plainBuffer.append(text[i + 1])
            i += 2
            continue
        }

        // 2. Backtick code span (supports multi-backtick fences like ``a `b` c``)
        if (c == '`') {
            var tickLen = 1
            while (i + tickLen < n && text[i + tickLen] == '`') tickLen++
            val closeStart = if (tickLen == 1 && i >= noSingleTickFrom) {
                -1
            } else {
                findClosingBacktickRun(text, i + tickLen, tickLen).also { found ->
                    if (found == -1 && tickLen == 1) noSingleTickFrom = i
                }
            }
            if (closeStart != -1) {
                flushPlain()
                var code = text.substring(i + tickLen, closeStart).replace('\n', ' ')
                if (code.length >= 2 && code.startsWith(' ') && code.endsWith(' ') && code.any { it != ' ' }) {
                    code = code.substring(1, code.length - 1)
                }
                tokens += MdInlineToken.CodeSpan(code)
                i = closeStart + tickLen
                continue
            } else {
                repeat(tickLen) { plainBuffer.append('`') }
                i += tickLen
                continue
            }
        }

        // 3. Markdown Image ![alt](url) or Linked/Rich Markdown Link [![alt](img)](link) / [label](url) / [label][ref]
        if ((c == '!' && i + 1 < n && text[i + 1] == '[') || c == '[') {
            val isImage = c == '!'
            val openBracketIdx = if (isImage) i + 1 else i
            val closeBracketIdx = if (openBracketIdx >= noMatchingBracketFrom) {
                -1
            } else {
                findMatchingBracket(text, openBracketIdx).also { found ->
                    if (found == -1 && text.indexOf(']', startIndex = openBracketIdx + 1) == -1) {
                        noMatchingBracketFrom = openBracketIdx
                    }
                }
            }
            if (closeBracketIdx != -1) {
                val rawLabel = text.substring(openBracketIdx + 1, closeBracketIdx)
                val afterBracket = closeBracketIdx + 1

                // 3a. Inline destination `(... "title")`
                if (afterBracket < n && text[afterBracket] == '(') {
                    val parsedDest = parseInlineLinkDestination(text, afterBracket)
                    if (parsedDest != null) {
                        val (rawUrl, closeParenIdx) = parsedDest
                        val targetUrl = unwrapNodeSeekJumpUrl(decodeHtmlEntitiesMinimal(rawUrl))
                        flushPlain()
                        if (isImage) {
                            tokens += MdInlineToken.Image(alt = decodeHtmlEntitiesMinimal(rawLabel), url = targetUrl)
                        } else {
                            val nestedImg = extractSingleImageTokenOrNull(rawLabel, referenceLinks, depth + 1)
                            if (nestedImg != null) {
                                tokens += nestedImg.copy(linkUrl = targetUrl)
                            } else {
                                tokens += MdInlineToken.Link(
                                    labelTokens = tokenizeMarkdownInline(rawLabel, referenceLinks, depth + 1),
                                    url = targetUrl,
                                )
                            }
                        }
                        i = closeParenIdx + 1
                        continue
                    }
                }

                // 3b. Reference link `[label][ref]` or collapsed `[label][]`
                if (afterBracket < n && text[afterBracket] == '[') {
                    val refCloseIdx = findMatchingBracket(text, afterBracket)
                    if (refCloseIdx != -1) {
                        val rawRefId = text.substring(afterBracket + 1, refCloseIdx)
                        val refKey = rawRefId.ifBlank { rawLabel }.trim().lowercase()
                        val refTarget = referenceLinks[refKey]?.first
                        if (refTarget != null) {
                            val targetUrl = unwrapNodeSeekJumpUrl(refTarget)
                            flushPlain()
                            if (isImage) {
                                tokens += MdInlineToken.Image(alt = decodeHtmlEntitiesMinimal(rawLabel), url = targetUrl)
                            } else {
                                val nestedImg = extractSingleImageTokenOrNull(rawLabel, referenceLinks, depth + 1)
                                if (nestedImg != null) {
                                    tokens += nestedImg.copy(linkUrl = targetUrl)
                                } else {
                                    tokens += MdInlineToken.Link(
                                        labelTokens = tokenizeMarkdownInline(rawLabel, referenceLinks, depth + 1),
                                        url = targetUrl,
                                    )
                                }
                            }
                            i = refCloseIdx + 1
                            continue
                        }
                    }
                }

                // 3c. Shortcut reference link `[label]`
                val shortcutKey = rawLabel.trim().lowercase()
                val shortcutTarget = referenceLinks[shortcutKey]?.first
                if (shortcutTarget != null) {
                    val targetUrl = unwrapNodeSeekJumpUrl(shortcutTarget)
                    flushPlain()
                    if (isImage) {
                        tokens += MdInlineToken.Image(alt = decodeHtmlEntitiesMinimal(rawLabel), url = targetUrl)
                    } else {
                        val nestedImg = extractSingleImageTokenOrNull(rawLabel, referenceLinks, depth + 1)
                        if (nestedImg != null) {
                            tokens += nestedImg.copy(linkUrl = targetUrl)
                        } else {
                            tokens += MdInlineToken.Link(
                                labelTokens = tokenizeMarkdownInline(rawLabel, referenceLinks, depth + 1),
                                url = targetUrl,
                            )
                        }
                    }
                    i = closeBracketIdx + 1
                    continue
                }
            }
        }

        // 4. Inline HTML tags & <https://...> autolinks (bounded window avoids O(N²) substring copies)
        if (c == '<' && i + 1 < n && (text[i + 1].isLetter() || text[i + 1] == '/')) {
            val htmlWindowEnd = (i + 4_096).coerceAtMost(n)
            val remaining = text.substring(i, htmlWindowEnd)

            val brMatch = INLINE_BR_REGEX.find(remaining)
            if (brMatch != null) {
                flushPlain()
                tokens += MdInlineToken.LineBreak
                i += brMatch.value.length
                continue
            }

            val autoMatch = INLINE_AUTOLINK_REGEX.find(remaining)
            if (autoMatch != null) {
                val url = unwrapNodeSeekJumpUrl(decodeHtmlEntitiesMinimal(autoMatch.groupValues[1]))
                flushPlain()
                tokens += MdInlineToken.Link(
                    labelTokens = listOf(MdInlineToken.PlainText(url)),
                    url = url,
                )
                i += autoMatch.value.length
                continue
            }

            val imgMatch = INLINE_HTML_IMG_REGEX.find(remaining)
            if (imgMatch != null) {
                val attrs = parseHtmlAttributes(imgMatch.groupValues[1])
                val src = resolveHtmlImageSrc(attrs)
                if (!src.isNullOrBlank()) {
                    val alt = attrs["alt"].orEmpty().let(::decodeHtmlEntitiesMinimal)
                    flushPlain()
                    tokens += MdInlineToken.Image(alt = alt, url = src)
                    i += imgMatch.value.length
                    continue
                }
            }

            val videoMatch = INLINE_HTML_VIDEO_REGEX.find(remaining)
            if (videoMatch != null) {
                val videoAttrs = parseHtmlAttributes(videoMatch.groupValues[1])
                val videoInner = videoMatch.groupValues[2]
                val candidateSources = buildList {
                    videoAttrs["src"]?.let(::decodeHtmlEntitiesMinimal)?.let(::add)
                    INLINE_HTML_SOURCE_SRC_REGEX.findAll(videoInner).forEach { m ->
                        val rawSrc = m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }
                        if (rawSrc.isNotBlank()) add(decodeHtmlEntitiesMinimal(rawSrc))
                    }
                }
                val resolvedSticker = candidateSources.firstNotNullOfOrNull(NodeSeekStickerCatalog::resolveFromAssetUrl)
                if (resolvedSticker != null) {
                    flushPlain()
                    tokens += MdInlineToken.Sticker(resolvedSticker)
                    i += videoMatch.value.length
                    continue
                }
            }

            val anchorOpen = INLINE_HTML_ANCHOR_OPEN_REGEX.find(remaining)
            if (anchorOpen != null) {
                val closeTagIdx = remaining.indexOf("</a>", startIndex = anchorOpen.value.length, ignoreCase = true)
                if (closeTagIdx != -1) {
                    val attrs = parseHtmlAttributes(anchorOpen.groupValues[1])
                    val href = attrs["href"]?.let(::decodeHtmlEntitiesMinimal)?.let(::unwrapNodeSeekJumpUrl)
                    val inner = remaining.substring(anchorOpen.value.length, closeTagIdx).trim()
                    if (!href.isNullOrBlank()) {
                        flushPlain()
                        val nestedImg = extractSingleImageTokenOrNull(inner, referenceLinks, depth + 1)
                        if (nestedImg != null) {
                            tokens += nestedImg.copy(linkUrl = href)
                        } else {
                            val labelTokens = if (inner.isNotEmpty()) {
                                tokenizeMarkdownInline(inner, referenceLinks, depth + 1)
                            } else {
                                listOf(MdInlineToken.PlainText(href))
                            }
                            tokens += MdInlineToken.Link(labelTokens = labelTokens, url = href)
                        }
                        i += closeTagIdx + 4
                        continue
                    }
                }
            }

            val pairedHtml = INLINE_HTML_PAIRED_TAG_REGEX.find(remaining)
            if (pairedHtml != null) {
                val tag = pairedHtml.groupValues[1].lowercase()
                val inner = pairedHtml.groupValues[2]
                flushPlain()
                when (tag) {
                    "code" -> tokens += MdInlineToken.CodeSpan(inner)
                    "kbd" -> tokens += MdInlineToken.Styled(
                        children = tokenizeMarkdownInline(inner, referenceLinks, depth + 1),
                        isKbd = true,
                    )
                    "strong", "b" -> tokens += MdInlineToken.Styled(
                        children = tokenizeMarkdownInline(inner, referenceLinks, depth + 1),
                        bold = true,
                    )
                    "em", "i" -> tokens += MdInlineToken.Styled(
                        children = tokenizeMarkdownInline(inner, referenceLinks, depth + 1),
                        italic = true,
                    )
                    "del", "s", "strike" -> tokens += MdInlineToken.Styled(
                        children = tokenizeMarkdownInline(inner, referenceLinks, depth + 1),
                        strikethrough = true,
                    )
                    "u", "ins" -> tokens += MdInlineToken.Styled(
                        children = tokenizeMarkdownInline(inner, referenceLinks, depth + 1),
                        underline = true,
                    )
                    "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        val trimmedHeading = inner.trim()
                        if (trimmedHeading.isNotEmpty()) {
                            tokens += MdInlineToken.Styled(
                                children = tokenizeMarkdownInline(trimmedHeading, referenceLinks, depth + 1),
                                bold = true,
                            )
                        }
                    }
                    "span", "font", "small", "mark", "sub", "sup" -> {
                        if (inner.isNotEmpty()) {
                            tokens += tokenizeMarkdownInline(inner, referenceLinks, depth + 1)
                        }
                    }
                }
                i += pairedHtml.value.length
                continue
            }

            val strayHtmlTag = INLINE_STRAY_HTML_TAG_REGEX.find(remaining)
            if (strayHtmlTag != null) {
                flushPlain()
                i += strayHtmlTag.value.length
                continue
            }
        }

        // 5. Bare URL https://... or http://...
        if ((c == 'h' || c == 'H') &&
            (text.startsWith("https://", i, ignoreCase = true) || text.startsWith("http://", i, ignoreCase = true)) &&
            (i == 0 || !text[i - 1].isLetterOrDigit())
        ) {
            val end = findBareUrlEnd(text, i)
            if (end > i + 7) {
                val targetUrl = unwrapNodeSeekJumpUrl(text.substring(i, end))
                flushPlain()
                tokens += MdInlineToken.Link(
                    labelTokens = listOf(MdInlineToken.PlainText(targetUrl)),
                    url = targetUrl,
                )
                i = end
                continue
            }
        }

        // 6. NodeSeek Sticker :ac01: / :yct001: / :xhj001: / :emoji00:
        if (c == ':') {
            val closeColon = text.indexOf(':', startIndex = i + 1)
            if (closeColon in (i + 3)..(i + 12)) {
                val candidate = text.substring(i + 1, closeColon)
                if (STICKER_CODE_REGEX.matches(candidate)) {
                    val sticker = NodeSeekStickerCatalog.resolve(candidate)
                    if (sticker != null) {
                        flushPlain()
                        tokens += MdInlineToken.Sticker(sticker)
                        i = closeColon + 1
                        continue
                    }
                }
            }
        }

        // 7. Strikethrough ~~...~~
        if (c == '~' && i + 1 < n && text[i + 1] == '~') {
            val closeIdx = if (i >= noTildeStrikeFrom) {
                -1
            } else {
                findClosingDelimiter(text, i + 2, "~~", allowNewline = true).also { found ->
                    if (found == -1) noTildeStrikeFrom = i
                }
            }
            if (closeIdx != -1) {
                flushPlain()
                tokens += MdInlineToken.Styled(
                    children = tokenizeMarkdownInline(text.substring(i + 2, closeIdx), referenceLinks, depth + 1),
                    strikethrough = true,
                )
                i = closeIdx + 2
                continue
            }
        }

        // 8. Bold+Italic (*** / ___), Bold (** / __), Italic (* / _)
        if (c == '*' || c == '_') {
            val prevIsWord = i > 0 && isWordChar(text[i - 1])
            val delimiterChar = c
            var runLen = 1
            while (i + runLen < n && text[i + runLen] == delimiterChar) runLen++

            if (runLen >= 3 && (delimiterChar == '*' || !prevIsWord)) {
                val noTripleFrom = if (delimiterChar == '*') noTripleStarFrom else noTripleUnderscoreFrom
                val delim = if (delimiterChar == '*') "***" else "___"
                val closeIdx = if (i >= noTripleFrom) {
                    -1
                } else {
                    findClosingDelimiter(
                        text = text,
                        startIndex = i + 3,
                        delimiter = delim,
                        allowNewline = true,
                        requireWordBoundaryEnd = delimiterChar == '_',
                    ).also { found ->
                        if (found == -1) {
                            if (delimiterChar == '*') noTripleStarFrom = i else noTripleUnderscoreFrom = i
                        }
                    }
                }
                if (closeIdx != -1) {
                    flushPlain()
                    tokens += MdInlineToken.Styled(
                        children = tokenizeMarkdownInline(text.substring(i + 3, closeIdx), referenceLinks, depth + 1),
                        bold = true,
                        italic = true,
                    )
                    i = closeIdx + 3
                    continue
                }
            }

            if (runLen >= 2 && (delimiterChar == '*' || !prevIsWord)) {
                val noDoubleFrom = if (delimiterChar == '*') noDoubleStarFrom else noDoubleUnderscoreFrom
                val delim = if (delimiterChar == '*') "**" else "__"
                val closeIdx = if (i >= noDoubleFrom) {
                    -1
                } else {
                    findClosingDelimiter(
                        text = text,
                        startIndex = i + 2,
                        delimiter = delim,
                        allowNewline = true,
                        requireWordBoundaryEnd = delimiterChar == '_',
                    ).also { found ->
                        if (found == -1) {
                            if (delimiterChar == '*') noDoubleStarFrom = i else noDoubleUnderscoreFrom = i
                        }
                    }
                }
                if (closeIdx != -1) {
                    flushPlain()
                    tokens += MdInlineToken.Styled(
                        children = tokenizeMarkdownInline(text.substring(i + 2, closeIdx), referenceLinks, depth + 1),
                        bold = true,
                    )
                    i = closeIdx + 2
                    continue
                }
            }

            if (runLen == 1 && (delimiterChar == '*' || !prevIsWord)) {
                val nextOk = i + 1 < n && !text[i + 1].isWhitespace() && text[i + 1] != delimiterChar
                if (nextOk) {
                    val noSingleFrom = if (delimiterChar == '*') noSingleStarFrom else noSingleUnderscoreFrom
                    val closeIdx = if (i >= noSingleFrom) {
                        -1
                    } else {
                        findClosingSingleEmphasis(text, i + 1, delimiterChar).also { found ->
                            if (found == -1 && text.indexOf('\n', startIndex = i + 1) == -1) {
                                if (delimiterChar == '*') noSingleStarFrom = i else noSingleUnderscoreFrom = i
                            }
                        }
                    }
                    if (closeIdx != -1) {
                        flushPlain()
                        tokens += MdInlineToken.Styled(
                            children = tokenizeMarkdownInline(text.substring(i + 1, closeIdx), referenceLinks, depth + 1),
                            italic = true,
                        )
                        i = closeIdx + 1
                        continue
                    }
                }
            }
        }

        // 9. @username mention
        if (c == '@' && (i == 0 || isMentionBoundaryBefore(text[i - 1]))) {
            val end = scanUsernameEnd(text, i + 1)
            if (end > i + 1) {
                val username = text.substring(i + 1, end)
                flushPlain()
                tokens += MdInlineToken.Mention(username)
                i = end
                continue
            }
        }

        // 10. #floor reference (zero-allocation digit scan instead of substring + regex per '#')
        if (c == '#' && i + 1 < n && text[i + 1] in '0'..'9' && (i == 0 || isFloorBoundaryBefore(text[i - 1]))) {
            var afterIdx = i + 1
            val maxDigitsEnd = (i + 6).coerceAtMost(n)
            var floor = 0
            while (afterIdx < maxDigitsEnd && text[afterIdx] in '0'..'9') {
                floor = floor * 10 + (text[afterIdx] - '0')
                afterIdx++
            }
            val validAfter = afterIdx >= n || (
                !text[afterIdx].isLetterOrDigit() &&
                    text[afterIdx] != '_' &&
                    !(text[afterIdx] == '.' && afterIdx + 1 < n && text[afterIdx + 1].isDigit())
                )
            if (validAfter && floor > 0) {
                flushPlain()
                tokens += MdInlineToken.FloorRef(floor)
                i = afterIdx
                continue
            }
        }

        plainBuffer.append(c)
        i++
    }

    flushPlain()
    return tokens
}

private fun extractSingleImageTokenOrNull(
    raw: String,
    referenceLinks: Map<String, Pair<String, String?>>,
    depth: Int,
): MdInlineToken.Image? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val parsed = tokenizeMarkdownInline(trimmed, referenceLinks, depth)
    val nonBlank = parsed.filterNot { it is MdInlineToken.PlainText && it.text.isBlank() }
    return nonBlank.singleOrNull() as? MdInlineToken.Image
}

private fun findClosingBacktickRun(text: String, startIndex: Int, runLen: Int): Int {
    var i = startIndex
    val n = text.length
    while (i < n) {
        if (text[i] == '`') {
            var count = 1
            while (i + count < n && text[i + count] == '`') count++
            if (count == runLen) return i
            i += count
        } else {
            i++
        }
    }
    return -1
}

private fun findMatchingBracket(text: String, openBracketIdx: Int): Int {
    var depth = 0
    var i = openBracketIdx
    val n = text.length
    while (i < n) {
        val ch = text[i]
        if (ch == '\\' && i + 1 < n) {
            i += 2
            continue
        }
        if (ch == '`') {
            var ticks = 1
            while (i + ticks < n && text[i + ticks] == '`') ticks++
            val closeTicks = findClosingBacktickRun(text, i + ticks, ticks)
            if (closeTicks != -1) {
                i = closeTicks + ticks
                continue
            }
        }
        if (ch == '[') {
            depth++
        } else if (ch == ']') {
            depth--
            if (depth == 0) return i
        }
        i++
    }
    return -1
}

private fun parseInlineLinkDestination(text: String, openParenIdx: Int): Pair<String, Int>? {
    val n = text.length
    var i = openParenIdx + 1
    while (i < n && text[i].isWhitespace()) i++
    if (i >= n) return null

    // Angle-bracketed URL `<https://...>`
    if (text[i] == '<') {
        val closeAngle = text.indexOf('>', startIndex = i + 1)
        if (closeAngle == -1) return null
        val url = text.substring(i + 1, closeAngle).trim().replace(" ", "%20")
        var j = closeAngle + 1
        while (j < n && text[j] != ')') {
            if (text[j] == '\\' && j + 1 < n) {
                j += 2
            } else {
                j++
            }
        }
        return if (j < n && text[j] == ')' && url.isNotEmpty()) url to j else null
    }

    // Standard `(url "optional title")` with balanced parentheses inside URL
    val urlBuilder = StringBuilder()
    var parenDepth = 1
    var inTitle = false
    var titleQuote = '\u0000'
    while (i < n) {
        val ch = text[i]
        if (ch == '\\' && i + 1 < n) {
            if (!inTitle) urlBuilder.append(text[i + 1])
            i += 2
            continue
        }
        if (inTitle) {
            if (ch == titleQuote) {
                inTitle = false
            }
            i++
            continue
        }
        if (ch == ')' && parenDepth == 1) {
            val url = urlBuilder.toString().trim()
            return if (url.isNotEmpty()) url to i else null
        }
        if (ch.isWhitespace()) {
            // Optional title after URL
            var j = i
            while (j < n && text[j].isWhitespace()) j++
            if (j < n && (text[j] == '"' || text[j] == '\'')) {
                inTitle = true
                titleQuote = text[j]
                i = j + 1
                continue
            }
            if (j < n && text[j] == ')') {
                val url = urlBuilder.toString().trim()
                return if (url.isNotEmpty()) url to j else null
            }
            return null
        }
        if (ch == '(') parenDepth++
        if (ch == ')') parenDepth--
        urlBuilder.append(ch)
        i++
    }
    return null
}

private fun isDisallowedHtmlImageScheme(candidate: String): Boolean {
    val lower = candidate.trim().lowercase(java.util.Locale.US)
    return lower.startsWith("javascript:") ||
        lower.startsWith("vbscript:") ||
        lower.startsWith("file:") ||
        lower.startsWith("content:") ||
        lower.startsWith("android.resource:")
}

private fun resolveHtmlImageSrc(attrs: Map<String, String>): String? {
    val lazyKeys = listOf("data-src", "data-original", "data-lazy-src", "data-url", "data-actualsrc")
    for (key in lazyKeys) {
        val candidate = attrs[key]?.let(::decodeHtmlEntitiesMinimal)?.trim()
        if (!candidate.isNullOrBlank() &&
            !candidate.startsWith("data:image/", ignoreCase = true) &&
            !isDisallowedHtmlImageScheme(candidate)
        ) {
            return normalizeMarkdownImageUrl(candidate).takeIf { it.isNotBlank() }
        }
    }
    val directSrc = attrs["src"]?.let(::decodeHtmlEntitiesMinimal)?.trim()
    if (!directSrc.isNullOrBlank() &&
        !directSrc.startsWith("data:image/", ignoreCase = true) &&
        !isDisallowedHtmlImageScheme(directSrc)
    ) {
        return normalizeMarkdownImageUrl(directSrc).takeIf { it.isNotBlank() }
    }
    val srcset = (attrs["data-srcset"] ?: attrs["srcset"])?.let(::decodeHtmlEntitiesMinimal)?.trim()
    if (!srcset.isNullOrBlank()) {
        val first = srcset.substringBefore(',').trim().substringBefore(' ').trim()
        if (first.isNotEmpty() && !isDisallowedHtmlImageScheme(first)) {
            return normalizeMarkdownImageUrl(first).takeIf { it.isNotBlank() }
        }
    }
    return directSrc
        ?.takeIf { it.isNotBlank() && !isDisallowedHtmlImageScheme(it) }
        ?.let(::normalizeMarkdownImageUrl)
        ?.takeIf { it.isNotBlank() }
}

private fun parseHtmlAttributes(attrText: String): Map<String, String> {
    if (attrText.isBlank()) return emptyMap()
    val result = mutableMapOf<String, String>()
    for (match in HTML_ATTR_REGEX.findAll(attrText)) {
        val key = match.groupValues[1].lowercase()
        val value = match.groups[2]?.value
            ?: match.groups[3]?.value
            ?: match.groups[4]?.value
            ?: ""
        result[key] = value
    }
    return result
}

private fun findBareUrlEnd(text: String, startIndex: Int): Int {
    var i = startIndex
    val n = text.length
    var parenBalance = 0
    while (i < n) {
        val ch = text[i]
        if (ch.isWhitespace() || ch in "<>\"'`") break
        if (ch == '(') parenBalance++
        if (ch == ')') {
            if (parenBalance == 0) break
            parenBalance--
        }
        i++
    }
    while (i > startIndex && text[i - 1] in ".,;:!?)") {
        if (text[i - 1] == ')') {
            val slice = text.substring(startIndex, i)
            val opens = slice.count { it == '(' }
            val closes = slice.count { it == ')' }
            if (opens >= closes) break
        }
        i--
    }
    return i
}

private fun findClosingDelimiter(
    text: String,
    startIndex: Int,
    delimiter: String,
    allowNewline: Boolean,
    requireWordBoundaryEnd: Boolean = false,
): Int {
    val n = text.length
    var i = startIndex
    while (i + delimiter.length <= n) {
        val ch = text[i]
        if (!allowNewline && ch == '\n') return -1
        if (ch == '\\' && i + 1 < n) {
            i += 2
            continue
        }
        if (text.startsWith(delimiter, i)) {
            if (i > startIndex && !text[i - 1].isWhitespace()) {
                val endPos = i + delimiter.length
                val boundaryOk = !requireWordBoundaryEnd || endPos >= n || !isWordChar(text[endPos])
                if (boundaryOk) return i
            }
        }
        i++
    }
    return -1
}

private fun findClosingSingleEmphasis(text: String, startIndex: Int, marker: Char): Int {
    val n = text.length
    var i = startIndex
    while (i < n) {
        val ch = text[i]
        if (ch == '\n') return -1
        if (ch == '\\' && i + 1 < n) {
            i += 2
            continue
        }
        if (ch == '`') {
            var ticks = 1
            while (i + ticks < n && text[i + ticks] == '`') ticks++
            val closeTicks = findClosingBacktickRun(text, i + ticks, ticks)
            if (closeTicks != -1) {
                i = closeTicks + ticks
                continue
            }
        }
        if (ch == marker) {
            val isDouble = (i + 1 < n && text[i + 1] == marker) || (i > 0 && text[i - 1] == marker && i - 1 >= startIndex)
            if (!isDouble && i > startIndex && !text[i - 1].isWhitespace()) {
                val boundaryOk = marker == '*' || i + 1 >= n || !isWordChar(text[i + 1])
                if (boundaryOk) return i
            }
        }
        i++
    }
    return -1
}

private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

private fun isMentionBoundaryBefore(c: Char): Boolean =
    !c.isLetterOrDigit() && c != '_' && c != '@' && c != '#' && c != '.'

private fun isFloorBoundaryBefore(c: Char): Boolean =
    !c.isLetterOrDigit() && c != '_' && c != '@' && c != '#' && c != '&'

private fun scanUsernameEnd(text: String, start: Int): Int {
    var i = start
    while (i < text.length) {
        val ch = text[i]
        if (ch.isLetterOrDigit() || ch == '_' || ch == '-') {
            i++
        } else {
            break
        }
    }
    return i
}

private fun decodeHtmlEntitiesMinimal(input: String): String {
    if (!input.contains('&')) return input
    return input
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
}

internal fun isBadgeOrSmallGraphicUrl(rawUrl: String): Boolean {
    val lower = rawUrl.lowercase()
    val pathOnly = lower.substringBefore('?').substringBefore('#')
    // Treat known badge/counter/emoji endpoints as compact inline graphics, but NOT arbitrary .svg files
    // (benchmark tools such as Report.Check.Place emit full-width SVG reports).
    return lower.contains("img.shields.io") ||
        lower.contains("shields.io/badge") ||
        lower.contains("komarev.com") ||
        lower.contains("badgen.net") ||
        lower.contains("badge.fury.io") ||
        lower.contains("visitor-badge") ||
        lower.contains("hits.sh") ||
        lower.contains("hits.seeyoufarm.com") ||
        lower.contains("hits.xykt.de") ||
        lower.contains("star.xykt.de") ||
        lower.contains("forthebadge.com") ||
        lower.contains("github-readme-stats") ||
        lower.contains("img.nodeseek.com/emoji") ||
        pathOnly.contains("/badge/") ||
        pathOnly.contains("/badges/") ||
        pathOnly.endsWith("/badge.svg")
}

private fun Double.emOrUnspecified() = androidx.compose.ui.unit.TextUnit(
    value = this.toFloat(),
    type = androidx.compose.ui.unit.TextUnitType.Em,
)

// ── Block-Level AST, Cache & Parser ──────────────────────────────────────────────────────

internal data class ParsedMarkdownDocument(
    val blocks: List<MdBlock>,
    val referenceLinks: Map<String, Pair<String, String?>>,
)

@Immutable
internal data class MdImageItem(
    val imageUrl: String,
    val alt: String,
    val linkUrl: String? = null,
)

@Immutable
internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Blockquote(val children: List<MdBlock>) : MdBlock
    data class CodeFence(val language: String, val code: String) : MdBlock
    data class ImageCard(val alt: String, val url: String, val linkUrl: String? = null) : MdBlock
    data class ImageRow(val items: List<MdImageItem>) : MdBlock
    data class ListContainer(val items: List<MdListItem>) : MdBlock
    data class Table(
        val headers: List<String>,
        val alignments: List<TextAlign>,
        val rows: List<List<String>>,
    ) : MdBlock
    data class TabsContainer(val tabs: List<MdTabItem>) : MdBlock
    data class DetailsSpoiler(val summary: String, val children: List<MdBlock>) : MdBlock
    data class SpecialWidget(val title: String, val url: String) : MdBlock
    data object HorizontalRule : MdBlock
}

@Immutable
internal data class MdListItem(
    val text: String,
    val indentLevel: Int = 0,
    val orderNumber: Int? = null,
    val checked: Boolean? = null,
)

@Immutable
internal data class MdTabItem(
    val title: String,
    val children: List<MdBlock>,
)

private object MarkdownDocumentCache {
    private const val MAX_ENTRIES = 256
    private const val MAX_CACHEABLE_CHARS = 64_000
    private val lock = Any()
    private val map = object : LinkedHashMap<String, ParsedMarkdownDocument>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParsedMarkdownDocument>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    fun getOrPut(markdown: String, compute: () -> ParsedMarkdownDocument): ParsedMarkdownDocument {
        if (markdown.length > MAX_CACHEABLE_CHARS) {
            return compute()
        }
        synchronized(lock) {
            map[markdown]?.let { return it }
        }
        val parsed = compute()
        synchronized(lock) {
            map.putIfAbsent(markdown, parsed)?.let { return it }
        }
        return parsed
    }
}

private fun parseAtxHeading(trimmed: String): MdBlock.Heading? {
    if (trimmed.isEmpty() || trimmed[0] != '#') return null
    var level = 0
    while (level < trimmed.length && level < 6 && trimmed[level] == '#') level++
    if (level !in 1..6 || level >= trimmed.length || !trimmed[level].isWhitespace()) return null
    var start = level
    while (start < trimmed.length && trimmed[start].isWhitespace()) start++
    var end = trimmed.length
    while (end > start && trimmed[end - 1] == '#') end--
    while (end > start && trimmed[end - 1].isWhitespace()) end--
    if (end <= start) return null
    return MdBlock.Heading(level = level, text = trimmed.substring(start, end))
}
private val HTML_HEADING_SINGLE_LINE_REGEX = Regex("""^<h([1-6])\b[^>]*>(.*?)</h\1>\s*$""", RegexOption.IGNORE_CASE)
private val HTML_HEADING_OPEN_REGEX = Regex("""^<h([1-6])\b[^>]*>(.*)$""", RegexOption.IGNORE_CASE)
private val SETEXT_H1_REGEX = Regex("""^={2,}\s*$""")
private val SETEXT_H2_REGEX = Regex("""^-{3,}\s*$""")
private val HR_REGEX = Regex("""^(?:-{3,}|\*{3,}|_{3,}|<hr\s*/?>)\s*$""", RegexOption.IGNORE_CASE)
private val WRAPPER_TAG_LINE_REGEX = Regex("""^</?(?:p|div|center|span|font|small|section|article|h[1-6])\b[^>]*>\s*$""", RegexOption.IGNORE_CASE)
private val WRAPPED_SINGLE_LINE_HTML_REGEX = Regex("""^<(?:p|div|center)\b[^>]*>(.*)</(?:p|div|center)>\s*$""", RegexOption.IGNORE_CASE)
private val SPECIAL_WIDGET_REGEX =
    Regex("""^\[([^\]]*)]\((nsapp://(?:vote|stardust-receive)[^)]*)\)\s*$""", RegexOption.IGNORE_CASE)
private val LIST_ITEM_REGEX =
    Regex("""^(\s*)(?:([-*+])|(\d+)\.)\s+(?:\[([ xX])]\s+)?(.+)$""")
private val TABLE_DIVIDER_CELL_REGEX = Regex("""^:?-{1,}:?$""")
private val REF_LINK_DEF_REGEX = Regex("""^\s*\[([^\]]+)]\s*:\s*(?:<([^>\s]+)>|(\S+))(?:\s+(?:"([^"]*)"|'([^']*)'|\(([^)]*)\)))?\s*$""")
private const val MAX_BLOCK_DEPTH = 8
private val DETAILS_OPEN_REGEX = Regex("""^\s*<details\b[^>]*>""", RegexOption.IGNORE_CASE)
private val DETAILS_CLOSE_REGEX = Regex("""</details>\s*$""", RegexOption.IGNORE_CASE)
private val SUMMARY_INLINE_REGEX = Regex("""<summary\b[^>]*>(.*?)</summary>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

internal fun parseNodeSeekMarkdownDocument(markdown: String): ParsedMarkdownDocument {
    if (markdown.isBlank()) {
        return ParsedMarkdownDocument(blocks = emptyList(), referenceLinks = emptyMap())
    }
    return MarkdownDocumentCache.getOrPut(markdown) {
        val normalized = markdown.replace("\r\n", "\n")
        val rawLines = normalized.lines()
        val referenceLinks = mutableMapOf<String, Pair<String, String?>>()
        val filteredLines = extractReferenceDefinitionsOutsideFences(rawLines, referenceLinks)
        val blocks = parseBlocksInternal(filteredLines, referenceLinks)
        ParsedMarkdownDocument(blocks = blocks, referenceLinks = referenceLinks)
    }
}

internal fun parseNodeSeekMarkdownBlocks(markdown: String): List<MdBlock> =
    parseNodeSeekMarkdownDocument(markdown).blocks

private fun extractReferenceDefinitionsOutsideFences(
    rawLines: List<String>,
    referenceLinks: MutableMap<String, Pair<String, String?>>,
): List<String> {
    val kept = ArrayList<String>(rawLines.size)
    var activeFenceChar: Char? = null
    var activeFenceLen = 0

    for (line in rawLines) {
        val trimmed = line.trim()
        if (activeFenceChar != null) {
            kept += line
            if (isClosingFence(trimmed, activeFenceChar, activeFenceLen)) {
                activeFenceChar = null
                activeFenceLen = 0
            }
            continue
        }

        val fenceOpen = parseOpeningFence(trimmed)
        if (fenceOpen != null) {
            activeFenceChar = fenceOpen.first
            activeFenceLen = fenceOpen.second
            kept += line
            continue
        }

        val refMatch = REF_LINK_DEF_REGEX.matchEntire(line)
        if (refMatch != null) {
            val id = refMatch.groupValues[1].trim().lowercase()
            val url = (refMatch.groups[2]?.value ?: refMatch.groupValues[3]).trim()
            val title = refMatch.groups[4]?.value
                ?: refMatch.groups[5]?.value
                ?: refMatch.groups[6]?.value
            if (id.isNotEmpty() && url.isNotEmpty()) {
                referenceLinks.putIfAbsent(id, url to title)
                continue
            }
        }

        kept += line
    }
    return kept
}

private fun parseBlocksInternal(
    lines: List<String>,
    referenceLinks: Map<String, Pair<String, String?>>,
    depth: Int = 0,
): List<MdBlock> {
    if (lines.isEmpty()) return emptyList()
    if (depth >= MAX_BLOCK_DEPTH) {
        val fallbackText = lines.joinToString("\n").trim()
        return if (fallbackText.isNotEmpty()) listOf(MdBlock.Paragraph(fallbackText)) else emptyList()
    }
    val result = mutableListOf<MdBlock>()
    var i = 0

    while (i < lines.size) {
        val line = normalizeWrapperLine(lines[i])
        val trimmed = line.trim()

        if (trimmed.isEmpty() || WRAPPER_TAG_LINE_REGEX.matches(trimmed)) {
            i++
            continue
        }

        // 1. Custom `:::: tabs` block
        if (trimmed.startsWith(":::: tabs") || trimmed.startsWith("::::tabs")) {
            i++
            val tabItems = mutableListOf<MdTabItem>()
            var currentTitle: String? = null
            val currentLines = mutableListOf<String>()

            fun flushTab() {
                val title = currentTitle
                if (title != null) {
                    tabItems += MdTabItem(
                        title = title,
                        children = parseBlocksInternal(currentLines, referenceLinks, depth + 1),
                    )
                }
                currentTitle = null
                currentLines.clear()
            }

            while (i < lines.size) {
                val inner = lines[i].trim()
                if (inner == "::::") {
                    i++
                    break
                }
                if (inner.startsWith("::: tab-item")) {
                    flushTab()
                    currentTitle = inner.removePrefix("::: tab-item").trim().ifBlank { "选项 ${tabItems.size + 1}" }
                } else if (inner == ":::") {
                    flushTab()
                } else if (currentTitle != null) {
                    currentLines += lines[i]
                }
                i++
            }
            flushTab()
            if (tabItems.isNotEmpty()) {
                result += MdBlock.TabsContainer(tabItems)
            }
            continue
        }

        // 2. HTML <details><summary>...</summary>...</details> spoiler block
        if (DETAILS_OPEN_REGEX.containsMatchIn(trimmed)) {
            val detailLines = mutableListOf<String>()
            var foundClose = false
            while (i < lines.size) {
                detailLines += lines[i]
                if (DETAILS_CLOSE_REGEX.containsMatchIn(lines[i].trim())) {
                    foundClose = true
                    i++
                    break
                }
                i++
            }
            if (detailLines.isNotEmpty()) {
                detailLines[0] = detailLines[0].trimStart()
            }
            val joined = detailLines.joinToString("\n")
                .replaceFirst(DETAILS_OPEN_REGEX, "")
                .let { if (foundClose) it.replaceFirst(DETAILS_CLOSE_REGEX, "") else it }
            val summaryMatch = SUMMARY_INLINE_REGEX.find(joined)
            val summary = summaryMatch?.groupValues?.get(1)?.trim().orEmpty().ifBlank { "展开详情" }
            val bodyText = if (summaryMatch != null) {
                joined.removeRange(summaryMatch.range).trim()
            } else {
                joined.trim()
            }
            result += MdBlock.DetailsSpoiler(
                summary = summary,
                children = parseBlocksInternal(bodyText.lines(), referenceLinks, depth + 1),
            )
            continue
        }

        // 3. Fenced code block ``` or ~~~
        val fenceOpen = parseOpeningFence(trimmed)
        if (fenceOpen != null) {
            val (fenceChar, fenceLen) = fenceOpen
            val lang = trimmed.drop(fenceLen).trim()
            i++
            val codeLines = mutableListOf<String>()
            while (i < lines.size && !isClosingFence(lines[i].trim(), fenceChar, fenceLen)) {
                codeLines += lines[i]
                i++
            }
            if (i < lines.size) i++
            result += MdBlock.CodeFence(language = lang, code = codeLines.joinToString("\n"))
            continue
        }

        // 4. Horizontal rule (including <hr> / <hr/>)
        if (HR_REGEX.matches(trimmed)) {
            result += MdBlock.HorizontalRule
            i++
            continue
        }

        // 5. ATX Heading `# ...` or HTML `<h1..h6>...</h1..h6>`
        val atxHeading = parseAtxHeading(trimmed)
        if (atxHeading != null) {
            result += atxHeading
            i++
            continue
        }

        val htmlHeadingSingle = HTML_HEADING_SINGLE_LINE_REGEX.matchEntire(trimmed)
        if (htmlHeadingSingle != null) {
            val level = htmlHeadingSingle.groupValues[1].toIntOrNull()?.coerceIn(1, 6) ?: 2
            val headingText = htmlHeadingSingle.groupValues[2].trim()
            if (headingText.isNotEmpty()) {
                result += MdBlock.Heading(level = level, text = headingText)
            }
            i++
            continue
        }

        val htmlHeadingOpen = HTML_HEADING_OPEN_REGEX.matchEntire(trimmed)
        if (htmlHeadingOpen != null) {
            val level = htmlHeadingOpen.groupValues[1].toIntOrNull()?.coerceIn(1, 6) ?: 2
            val closeRegex = Regex("""</h$level\s*>""", RegexOption.IGNORE_CASE)
            val collected = mutableListOf<String>()
            val firstRest = htmlHeadingOpen.groupValues[2]
            if (firstRest.isNotBlank()) collected += firstRest
            var scanIdx = i + 1
            var closedAt = -1
            while (scanIdx < lines.size && scanIdx <= i + 6) {
                val candidate = lines[scanIdx]
                if (closeRegex.containsMatchIn(candidate)) {
                    val beforeClose = candidate.replaceFirst(closeRegex, "").trim()
                    if (beforeClose.isNotEmpty()) collected += beforeClose
                    closedAt = scanIdx
                    break
                }
                collected += candidate.trim()
                scanIdx++
            }
            if (closedAt != -1) {
                val joinedHeading = collected.joinToString(" ").trim()
                if (joinedHeading.isNotEmpty()) {
                    result += MdBlock.Heading(level = level, text = joinedHeading)
                }
                i = closedAt + 1
                continue
            } else if (firstRest.isNotBlank()) {
                result += MdBlock.Heading(level = level, text = firstRest.trim())
                i++
                continue
            }
        }

        // 6. Setext Heading (`Title\n===` or `Title\n---`)
        if (i + 1 < lines.size && isEligibleSetextTitleLine(trimmed)) {
            val nextTrimmed = lines[i + 1].trim()
            when {
                SETEXT_H1_REGEX.matches(nextTrimmed) -> {
                    result += MdBlock.Heading(level = 1, text = trimmed)
                    i += 2
                    continue
                }
                SETEXT_H2_REGEX.matches(nextTrimmed) -> {
                    result += MdBlock.Heading(level = 2, text = trimmed)
                    i += 2
                    continue
                }
            }
        }

        // 7. Standalone embedded nsapp:// widget
        val widgetMatch = SPECIAL_WIDGET_REGEX.matchEntire(trimmed)
        if (widgetMatch != null) {
            result += MdBlock.SpecialWidget(
                title = widgetMatch.groupValues[1],
                url = widgetMatch.groupValues[2],
            )
            i++
            continue
        }

        // 8. Standalone image / badge / linked-badge line(s)
        val firstRowItems = parseStandaloneImageRowLine(trimmed, referenceLinks)
        if (firstRowItems != null) {
            if (firstRowItems.size == 1 &&
                !isBadgeOrSmallGraphicUrl(firstRowItems[0].imageUrl)
            ) {
                result += MdBlock.ImageCard(
                    alt = firstRowItems[0].alt,
                    url = firstRowItems[0].imageUrl,
                    linkUrl = firstRowItems[0].linkUrl,
                )
                i++
                continue
            } else {
                val merged = firstRowItems.toMutableList()
                i++
                while (i < lines.size) {
                    val nextNormalized = normalizeWrapperLine(lines[i]).trim()
                    if (nextNormalized.isEmpty() || WRAPPER_TAG_LINE_REGEX.matches(nextNormalized)) {
                        if (WRAPPER_TAG_LINE_REGEX.matches(nextNormalized)) {
                            i++
                            continue
                        }
                        break
                    }
                    val nextItems = parseStandaloneImageRowLine(nextNormalized, referenceLinks)
                    if (nextItems != null && (nextItems.size > 1 || isBadgeOrSmallGraphicUrl(nextItems[0].imageUrl))) {
                        merged += nextItems
                        i++
                    } else {
                        break
                    }
                }
                result += MdBlock.ImageRow(merged)
                continue
            }
        }

        // 9. Blockquote `>`
        if (trimmed.startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                quoteLines += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                i++
            }
            val nested = parseBlocksInternal(quoteLines, referenceLinks, depth + 1)
            if (nested.isNotEmpty()) {
                result += MdBlock.Blockquote(nested)
            }
            continue
        }

        // 10. GFM Pipe Table (supports with or without outer `|`)
        if (trimmed.contains("|") && i + 1 < lines.size && isTableDividerRow(lines[i + 1])) {
            val headers = splitPipeRow(lines[i])
            val alignments = splitPipeRow(lines[i + 1]).map { cell ->
                val c = cell.trim()
                when {
                    c.startsWith(":") && c.endsWith(":") -> TextAlign.Center
                    c.endsWith(":") -> TextAlign.End
                    else -> TextAlign.Start
                }
            }
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].contains("|") && lines[i].trim().isNotEmpty()) {
                rows += splitPipeRow(lines[i])
                i++
            }
            result += MdBlock.Table(headers = headers, alignments = alignments, rows = rows)
            continue
        }

        // 11. Bulleted / Ordered / Task List
        if (LIST_ITEM_REGEX.matches(line)) {
            val items = mutableListOf<MdListItem>()
            while (i < lines.size) {
                val match = LIST_ITEM_REGEX.matchEntire(lines[i]) ?: break
                val indent = match.groupValues[1].length / 2
                val orderNum = match.groups[3]?.value?.toIntOrNull()
                val checkFlag = match.groups[4]?.value?.let { !it.equals(" ", ignoreCase = true) }
                val itemText = match.groupValues[5]
                items += MdListItem(
                    text = itemText,
                    indentLevel = indent,
                    orderNumber = orderNum,
                    checked = checkFlag,
                )
                i++
            }
            result += MdBlock.ListContainer(items)
            continue
        }

        // 12. Standard Paragraph (merge consecutive plain lines, yielding before block starters & Setext underlines)
        val paraLines = mutableListOf<String>()
        while (i < lines.size) {
            val cur = normalizeWrapperLine(lines[i])
            val curTrimmed = cur.trim()
            if (curTrimmed.isEmpty() ||
                WRAPPER_TAG_LINE_REGEX.matches(curTrimmed) ||
                parseOpeningFence(curTrimmed) != null ||
                curTrimmed.startsWith(":::: tabs") ||
                curTrimmed.startsWith("::::tabs") ||
                DETAILS_OPEN_REGEX.containsMatchIn(curTrimmed) ||
                curTrimmed.startsWith(">") ||
                parseAtxHeading(curTrimmed) != null ||
                HTML_HEADING_SINGLE_LINE_REGEX.matches(curTrimmed) ||
                HTML_HEADING_OPEN_REGEX.matches(curTrimmed) ||
                HR_REGEX.matches(curTrimmed) ||
                SPECIAL_WIDGET_REGEX.matches(curTrimmed) ||
                parseStandaloneImageRowLine(curTrimmed, referenceLinks) != null ||
                LIST_ITEM_REGEX.matches(cur) ||
                (curTrimmed.contains("|") && i + 1 < lines.size && isTableDividerRow(lines[i + 1]))
            ) {
                break
            }
            if (paraLines.isNotEmpty() && i + 1 < lines.size && isEligibleSetextTitleLine(curTrimmed)) {
                val nextT = lines[i + 1].trim()
                if (SETEXT_H1_REGEX.matches(nextT) || SETEXT_H2_REGEX.matches(nextT)) {
                    break
                }
            }
            paraLines += cur
            i++
        }
        if (paraLines.isNotEmpty()) {
            result += MdBlock.Paragraph(paraLines.joinToString("\n"))
        } else {
            i++
        }
    }

    return result
}

private fun normalizeWrapperLine(line: String): String {
    val trimmed = line.trim()
    val wrapped = WRAPPED_SINGLE_LINE_HTML_REGEX.matchEntire(trimmed)
    return if (wrapped != null) wrapped.groupValues[1].trim() else line
}

private fun parseOpeningFence(trimmed: String): Pair<Char, Int>? {
    if (trimmed.isEmpty()) return null
    val first = trimmed[0]
    if (first != '`' && first != '~') return null
    var count = 1
    while (count < trimmed.length && trimmed[count] == first) count++
    if (count < 3) return null
    val info = trimmed.substring(count)
    if (first == '`' && info.contains('`')) return null
    return first to count
}

private fun isClosingFence(trimmed: String, fenceChar: Char, openLen: Int): Boolean {
    if (trimmed.length < openLen) return false
    var count = 0
    while (count < trimmed.length && trimmed[count] == fenceChar) count++
    return count >= openLen && count == trimmed.length
}

private fun isEligibleSetextTitleLine(trimmed: String): Boolean {
    if (trimmed.isEmpty()) return false
    return !trimmed.startsWith(">") &&
        !trimmed.startsWith("#") &&
        !trimmed.startsWith("-") &&
        !trimmed.startsWith("*") &&
        !trimmed.startsWith("+") &&
        !trimmed.startsWith("`") &&
        !trimmed.startsWith("~") &&
        !HR_REGEX.matches(trimmed) &&
        !LIST_ITEM_REGEX.matches(trimmed)
}

private fun parseStandaloneImageRowLine(
    trimmed: String,
    referenceLinks: Map<String, Pair<String, String?>>,
): List<MdImageItem>? {
    if (trimmed.isEmpty()) return null
    if (!trimmed.contains("![") && !trimmed.contains("<img", ignoreCase = true)) return null
    val tokens = tokenizeMarkdownInline(trimmed, referenceLinks)
    val images = mutableListOf<MdImageItem>()
    for (token in tokens) {
        when (token) {
            is MdInlineToken.Image -> images += MdImageItem(
                imageUrl = token.url,
                alt = token.alt,
                linkUrl = token.linkUrl,
            )
            MdInlineToken.LineBreak -> Unit
            is MdInlineToken.PlainText -> {
                if (token.text.isNotBlank()) return null
            }
            else -> return null
        }
    }
    return images.takeIf { it.isNotEmpty() }
}

private fun isTableDividerRow(line: String): Boolean {
    if (!line.contains("|")) return false
    val cells = splitPipeRow(line)
    return cells.isNotEmpty() && cells.all { TABLE_DIVIDER_CELL_REGEX.matches(it.trim()) }
}

private fun splitPipeRow(line: String): List<String> {
    var trimmed = line.trim()
    if (trimmed.startsWith("|")) trimmed = trimmed.drop(1)
    if (trimmed.endsWith("|") && !trimmed.endsWith("\\|")) trimmed = trimmed.dropLast(1)

    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    var inBackticks = false
    while (i < trimmed.length) {
        val ch = trimmed[i]
        if (ch == '\\' && i + 1 < trimmed.length && trimmed[i + 1] == '|') {
            current.append('|')
            i += 2
            continue
        }
        if (ch == '`') {
            inBackticks = !inBackticks
            current.append(ch)
            i++
            continue
        }
        if (ch == '|' && !inBackticks) {
            cells += current.toString().trim()
            current.clear()
            i++
            continue
        }
        current.append(ch)
        i++
    }
    cells += current.toString().trim()
    return cells
}

private fun copyPlainTextToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Anode Code", text))
}
