package com.nodeseek.app.ui.composer

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.image.BitmapMosaicProcessor
import com.nodeseek.app.domain.image.MosaicEdit
import com.nodeseek.app.domain.image.MosaicPoint
import com.nodeseek.app.domain.image.MosaicRect
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class MosaicToolMode(val label: String) {
    BRUSH("画笔涂鸦"),
    RECT("框选马赛克"),
}

private enum class MosaicBrushSizeOption(val label: String, val radiusNorm: Float) {
    SMALL("小", 0.025f),
    MEDIUM("中", 0.045f),
    LARGE("大", 0.075f),
}

private enum class MosaicBlockSizeOption(val label: String, val blockSizeNorm: Float) {
    FINE("细", 0.015f),
    MEDIUM("中", 0.024f),
    COARSE("粗", 0.040f),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePrivacyEditorSheet(
    sourceBytes: ByteArray,
    mimeType: String,
    fileName: String?,
    onConfirmUpload: (bytes: ByteArray, mimeType: String, fileName: String?) -> Unit,
    onSkipMosaicUpload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val ext = NodePulseThemeTokens.extendedColors
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.90f).dp.coerceAtLeast(420.dp)

    var toolMode by remember { mutableStateOf(MosaicToolMode.BRUSH) }
    var brushSize by remember { mutableStateOf(MosaicBrushSizeOption.MEDIUM) }
    var blockSize by remember { mutableStateOf(MosaicBlockSizeOption.MEDIUM) }
    val edits = remember(sourceBytes) { mutableStateListOf<MosaicEdit>() }

    var isDecoding by remember(sourceBytes) { mutableStateOf(true) }
    var isEncoding by remember { mutableStateOf(false) }
    var decodeFailed by remember(sourceBytes) { mutableStateOf(false) }

    var imageWidth by remember(sourceBytes) { mutableStateOf(0) }
    var imageHeight by remember(sourceBytes) { mutableStateOf(0) }
    var sourcePixels by remember(sourceBytes) { mutableStateOf<IntArray?>(null) }
    var displayBitmap by remember(sourceBytes) { mutableStateOf<Bitmap?>(null) }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var activeBrushPoints by remember { mutableStateOf<List<MosaicPoint>>(emptyList()) }
    var activeBoxStart by remember { mutableStateOf<MosaicPoint?>(null) }
    var activeBoxEnd by remember { mutableStateOf<MosaicPoint?>(null) }

    DisposableEffect(sourceBytes) {
        onDispose {
            displayBitmap?.let { bmp ->
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    LaunchedEffect(sourceBytes) {
        isDecoding = true
        decodeFailed = false
        val decodedResult = withContext(Dispatchers.Default) {
            val bmp = ImagePrivacyBitmapCodec.decodeEditableBitmap(sourceBytes)
            if (bmp == null || bmp.width <= 0 || bmp.height <= 0) {
                null
            } else {
                val w = bmp.width
                val h = bmp.height
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                Triple(w, h, px) to bmp
            }
        }
        if (decodedResult == null) {
            decodeFailed = true
            isDecoding = false
        } else {
            val (dimsAndPx, initialBmp) = decodedResult
            imageWidth = dimsAndPx.first
            imageHeight = dimsAndPx.second
            sourcePixels = dimsAndPx.third
            val previous = displayBitmap
            displayBitmap = initialBmp
            if (previous != null && previous !== initialBmp && !previous.isRecycled) {
                previous.recycle()
            }
            isDecoding = false
        }
    }

    val editsSnapshot = edits.toList()
    LaunchedEffect(editsSnapshot, sourcePixels, imageWidth, imageHeight) {
        val rawPx = sourcePixels ?: return@LaunchedEffect
        if (imageWidth <= 0 || imageHeight <= 0) return@LaunchedEffect
        val updatedBitmap = withContext(Dispatchers.Default) {
            val outPixels = BitmapMosaicProcessor.applyMosaicEdits(
                sourcePixels = rawPx,
                width = imageWidth,
                height = imageHeight,
                edits = editsSnapshot,
            )
            Bitmap.createBitmap(outPixels, imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
        }
        val prev = displayBitmap
        displayBitmap = updatedBitmap
        if (prev != null && prev !== updatedBitmap && !prev.isRecycled) {
            prev.recycle()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(PulseArchitecturalShapes.MediumBadge)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "上传前隐私打码",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "涂抹或框选敏感 IP、账号与订单信息（自动清除 EXIF）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    enabled = !isEncoding,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "取消",
                    )
                }
            }

            // Canvas Preview Area
            val imageBounds = remember(canvasSize, imageWidth, imageHeight) {
                computeFitBounds(
                    containerW = canvasSize.width.toFloat(),
                    containerH = canvasSize.height.toFloat(),
                    imageW = imageWidth.toFloat(),
                    imageH = imageHeight.toFloat(),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(min = 240.dp, max = 380.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .border(1.dp, ext.cardBorder, RoundedCornerShape(16.dp))
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(toolMode, brushSize, blockSize, imageBounds, isDecoding, isEncoding, decodeFailed) {
                        if (isDecoding || isEncoding || decodeFailed || imageBounds.width <= 1f || imageBounds.height <= 1f) {
                            return@pointerInput
                        }
                        detectDragGestures(
                            onDragStart = { offset ->
                                val norm = normalizePointerOffset(offset, imageBounds)
                                if (toolMode == MosaicToolMode.BRUSH) {
                                    activeBrushPoints = listOf(norm)
                                } else {
                                    activeBoxStart = norm
                                    activeBoxEnd = norm
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val norm = normalizePointerOffset(change.position, imageBounds)
                                if (toolMode == MosaicToolMode.BRUSH) {
                                    val last = activeBrushPoints.lastOrNull()
                                    if (last == null ||
                                        abs(last.xNorm - norm.xNorm) > 0.002f ||
                                        abs(last.yNorm - norm.yNorm) > 0.002f
                                    ) {
                                        activeBrushPoints = activeBrushPoints + norm
                                    }
                                } else {
                                    activeBoxEnd = norm
                                }
                            },
                            onDragEnd = {
                                if (toolMode == MosaicToolMode.BRUSH) {
                                    val pts = activeBrushPoints
                                    activeBrushPoints = emptyList()
                                    if (pts.isNotEmpty()) {
                                        edits += MosaicEdit.BrushStroke(
                                            points = pts,
                                            radiusNorm = brushSize.radiusNorm,
                                            blockSizeNorm = blockSize.blockSizeNorm,
                                        )
                                    }
                                } else {
                                    val start = activeBoxStart
                                    val end = activeBoxEnd
                                    activeBoxStart = null
                                    activeBoxEnd = null
                                    if (start != null && end != null &&
                                        abs(end.xNorm - start.xNorm) > 0.005f &&
                                        abs(end.yNorm - start.yNorm) > 0.005f
                                    ) {
                                        edits += MosaicEdit.BoxRegion(
                                            rect = MosaicRect(
                                                leftNorm = min(start.xNorm, end.xNorm),
                                                topNorm = min(start.yNorm, end.yNorm),
                                                rightNorm = max(start.xNorm, end.xNorm),
                                                bottomNorm = max(start.yNorm, end.yNorm),
                                            ),
                                            blockSizeNorm = blockSize.blockSizeNorm,
                                        )
                                    }
                                }
                            },
                            onDragCancel = {
                                activeBrushPoints = emptyList()
                                activeBoxStart = null
                                activeBoxEnd = null
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isDecoding -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(26.dp))
                            Text(
                                text = "正在解码预览图片…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    decodeFailed -> {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "当前图片格式无法在预览器中解码，可直接原图上传",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    displayBitmap != null -> {
                        val bmp = displayBitmap!!
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "隐私打码预览",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        val strokeAccent = MaterialTheme.colorScheme.primary
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            if (imageBounds.width > 1f && imageBounds.height > 1f) {
                                if (activeBrushPoints.isNotEmpty()) {
                                    val minBound = min(imageBounds.width, imageBounds.height)
                                    val strokeWidthPx = (brushSize.radiusNorm * minBound * 2f).coerceAtLeast(8f)
                                    if (activeBrushPoints.size == 1) {
                                        val pt = denormalizePoint(activeBrushPoints.first(), imageBounds)
                                        drawCircle(
                                            color = strokeAccent.copy(alpha = 0.48f),
                                            radius = strokeWidthPx / 2f,
                                            center = pt,
                                        )
                                    } else {
                                        val path = Path()
                                        activeBrushPoints.forEachIndexed { index, point ->
                                            val p = denormalizePoint(point, imageBounds)
                                            if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                                        }
                                        drawPath(
                                            path = path,
                                            color = strokeAccent.copy(alpha = 0.48f),
                                            style = Stroke(
                                                width = strokeWidthPx,
                                                cap = StrokeCap.Round,
                                                join = StrokeJoin.Round,
                                            ),
                                        )
                                    }
                                }
                                val start = activeBoxStart
                                val end = activeBoxEnd
                                if (start != null && end != null) {
                                    val p0 = denormalizePoint(start, imageBounds)
                                    val p1 = denormalizePoint(end, imageBounds)
                                    val left = min(p0.x, p1.x)
                                    val top = min(p0.y, p1.y)
                                    val w = abs(p1.x - p0.x)
                                    val h = abs(p1.y - p0.y)
                                    drawRect(
                                        color = strokeAccent.copy(alpha = 0.22f),
                                        topLeft = Offset(left, top),
                                        size = Size(w, h),
                                    )
                                    drawRect(
                                        color = strokeAccent,
                                        topLeft = Offset(left, top),
                                        size = Size(w, h),
                                        style = Stroke(width = 3f),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Tool mode + Undo / Reset row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = toolMode == MosaicToolMode.BRUSH,
                    onClick = { toolMode = MosaicToolMode.BRUSH },
                    label = { Text(MosaicToolMode.BRUSH.label) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Brush,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
                FilterChip(
                    selected = toolMode == MosaicToolMode.RECT,
                    onClick = { toolMode = MosaicToolMode.RECT },
                    label = { Text(MosaicToolMode.RECT.label) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.CropSquare,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(
                    onClick = {
                        if (edits.isNotEmpty()) {
                            edits.removeAt(edits.lastIndex)
                        }
                    },
                    enabled = edits.isNotEmpty() && !isEncoding,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Undo,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("撤销")
                }
                TextButton(
                    onClick = { edits.clear() },
                    enabled = edits.isNotEmpty() && !isEncoding,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("重置")
                }
            }

            // Brush size + Block granularity row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (toolMode == MosaicToolMode.BRUSH) {
                    Text(
                        text = "画笔",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MosaicBrushSizeOption.entries.forEach { opt ->
                        FilterChip(
                            selected = brushSize == opt,
                            onClick = { brushSize = opt },
                            label = { Text(opt.label) },
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = "颗粒",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MosaicBlockSizeOption.entries.forEach { opt ->
                    FilterChip(
                        selected = blockSize == opt,
                        onClick = { blockSize = opt },
                        label = { Text(opt.label) },
                    )
                }
            }

            // Bottom CTAs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onSkipMosaicUpload,
                    enabled = !isEncoding,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "跳过打码直接上传",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = {
                        val bmp = displayBitmap
                        if (bmp == null || decodeFailed) {
                            onSkipMosaicUpload()
                            return@Button
                        }
                        if (edits.isEmpty()) {
                            // Even without brush edits, re-encoding strips EXIF metadata cleanly if possible,
                            // or if encoding fails fallback to original.
                            isEncoding = true
                            coroutineScope.launch {
                                val prepared = withContext(Dispatchers.Default) {
                                    ImagePrivacyBitmapCodec.encodeEditedBitmap(
                                        bitmap = bmp,
                                        originalFileName = fileName,
                                    )
                                }
                                isEncoding = false
                                if (prepared != null) {
                                    onConfirmUpload(prepared.bytes, prepared.mimeType, prepared.fileName)
                                } else {
                                    onSkipMosaicUpload()
                                }
                            }
                        } else {
                            isEncoding = true
                            coroutineScope.launch {
                                val prepared = withContext(Dispatchers.Default) {
                                    ImagePrivacyBitmapCodec.encodeEditedBitmap(
                                        bitmap = bmp,
                                        originalFileName = fileName,
                                    )
                                }
                                isEncoding = false
                                if (prepared != null) {
                                    onConfirmUpload(prepared.bytes, prepared.mimeType, prepared.fileName)
                                } else {
                                    onSkipMosaicUpload()
                                }
                            }
                        }
                    },
                    enabled = !isDecoding && !isEncoding && !decodeFailed,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isEncoding) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("处理中…")
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "打码并上传",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private fun computeFitBounds(
    containerW: Float,
    containerH: Float,
    imageW: Float,
    imageH: Float,
): Rect {
    if (containerW <= 0f || containerH <= 0f || imageW <= 0f || imageH <= 0f) {
        return Rect.Zero
    }
    val scale = min(containerW / imageW, containerH / imageH)
    val drawnW = imageW * scale
    val drawnH = imageH * scale
    val left = (containerW - drawnW) / 2f
    val top = (containerH - drawnH) / 2f
    return Rect(left, top, left + drawnW, top + drawnH)
}

private fun normalizePointerOffset(offset: Offset, bounds: Rect): MosaicPoint {
    val x = ((offset.x - bounds.left) / bounds.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val y = ((offset.y - bounds.top) / bounds.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
    return MosaicPoint(xNorm = x, yNorm = y)
}

private fun denormalizePoint(point: MosaicPoint, bounds: Rect): Offset =
    Offset(
        x = bounds.left + point.xNorm * bounds.width,
        y = bounds.top + point.yNorm * bounds.height,
    )
