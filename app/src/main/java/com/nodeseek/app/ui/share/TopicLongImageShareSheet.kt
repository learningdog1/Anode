package com.nodeseek.app.ui.share

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.model.FloorComment
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicLongImageShareSheet(
    topicId: Long,
    title: String,
    categorySlug: String,
    categoryName: String,
    targetFloor: FloorComment,
    highlightReply: FloorComment? = null,
    topicUrl: String,
    viewCount: Int = 0,
    bookmarkCount: Int = 0,
    coilOkHttpClient: OkHttpClient? = null,
    onFeedbackMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val ext = NodePulseThemeTokens.extendedColors
    val isAppDark = NodePulseThemeTokens.isDark
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.90f).dp.coerceAtLeast(420.dp)

    var selectedTheme by remember {
        mutableStateOf(
            if (isAppDark) LongImageThemeStyle.SLATE_STEEL_DARK else LongImageThemeStyle.COOL_MIST_LIGHT,
        )
    }
    val hasSignature = !targetFloor.signatureMarkdown.isNullOrBlank()
    var includeSignature by remember { mutableStateOf(hasSignature) }
    var includeQrCode by remember { mutableStateOf(true) }

    var isRendering by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var renderedPages by remember { mutableStateOf<List<RenderedTopicLongImage>>(emptyList()) }
    var previewBitmaps by remember { mutableStateOf<List<Bitmap>>(emptyList()) }

    DisposableEffect(Unit) {
        onDispose {
            previewBitmaps.forEach { bmp ->
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    val request = remember(
        topicId,
        title,
        categorySlug,
        categoryName,
        targetFloor,
        highlightReply,
        topicUrl,
        viewCount,
        bookmarkCount,
        selectedTheme,
        includeSignature,
        includeQrCode,
    ) {
        TopicLongImageRequest(
            topicId = topicId,
            title = title,
            categorySlug = categorySlug,
            categoryName = categoryName,
            targetFloor = targetFloor,
            highlightReply = highlightReply,
            topicUrl = topicUrl,
            viewCount = viewCount,
            bookmarkCount = bookmarkCount,
            themeStyle = selectedTheme,
            includeSignature = includeSignature,
            includeQrCode = includeQrCode,
        )
    }

    LaunchedEffect(request) {
        isRendering = true
        val output = withContext(Dispatchers.Default) {
            val assets = TopicLongImageAssetFetcher.prefetch(
                context = context,
                request = request,
                coilOkHttpClient = coilOkHttpClient,
            )
            try {
                val pages = TopicLongImageRenderer.render(request, assets)
                val decodedPreviews = pages.mapNotNull { page ->
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = if (page.heightPx > 4000) 2 else 1
                    }
                    BitmapFactory.decodeByteArray(page.pngBytes, 0, page.pngBytes.size, opts)
                }
                pages to decodedPreviews
            } finally {
                assets.close()
            }
        }
        val oldBitmaps = previewBitmaps
        renderedPages = output.first
        previewBitmaps = output.second
        oldBitmaps.forEach { bmp ->
            if (!bmp.isRecycled) bmp.recycle()
        }
        isRendering = false
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
                .heightIn(max = maxSheetHeight)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
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
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (targetFloor.floorNumber <= 0) "生成杂志长图" else "生成 #${targetFloor.floorNumber} 长图",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    val subtitle = if (renderedPages.size > 1) {
                        "内容较长，已智能分页为 ${renderedPages.size} 张高清长图"
                    } else {
                        "支持 ANSI 终端块、贴纸与原帖二维码导出"
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭",
                    )
                }
            }

            // Theme selector & options
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LongImageThemeStyle.entries.forEach { style ->
                    FilterChip(
                        selected = selectedTheme == style,
                        onClick = { selectedTheme = style },
                        label = { Text(style.label) },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasSignature) {
                    FilterChip(
                        selected = includeSignature,
                        onClick = { includeSignature = !includeSignature },
                        label = { Text("显示签名") },
                    )
                }
                FilterChip(
                    selected = includeQrCode,
                    onClick = { includeQrCode = !includeQrCode },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.QrCode2,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = { Text("附带原帖二维码") },
                )
            }

            // Live scrollable preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(min = 220.dp, max = 420.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .border(1.dp, ext.cardBorder, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isRendering -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = "正在加载图片资源并排版绘制…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    previewBitmaps.isEmpty() -> {
                        Text(
                            text = "无法生成长图预览，请稍后重试",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    else -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            previewBitmaps.forEachIndexed { idx, bmp ->
                                if (previewBitmaps.size > 1) {
                                    Text(
                                        text = "第 ${idx + 1} / ${previewBitmaps.size} 页",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 4.dp),
                                    )
                                }
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "长图预览第 ${idx + 1} 页",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp)),
                                )
                            }
                        }
                    }
                }
            }

            // Action CTAs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val pages = renderedPages
                        if (pages.isEmpty()) return@OutlinedButton
                        isSaving = true
                        coroutineScope.launch {
                            val res = withContext(Dispatchers.IO) {
                                TopicLongImageRenderer.saveToGallery(context, pages)
                            }
                            isSaving = false
                            res.fold(
                                onSuccess = { count ->
                                    onFeedbackMessage("已保存 $count 张长图到相册 Pictures/Anode")
                                },
                                onFailure = { err ->
                                    onFeedbackMessage(err.message ?: "保存长图失败")
                                },
                            )
                        }
                    },
                    enabled = !isRendering && !isSaving && renderedPages.isNotEmpty(),
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isSaving) "保存中…" else "保存到相册")
                }

                Button(
                    onClick = {
                        val pages = renderedPages
                        if (pages.isEmpty()) return@Button
                        val ok = TopicLongImageRenderer.shareImages(
                            context = context,
                            images = pages,
                            title = title,
                        )
                        if (!ok) {
                            onFeedbackMessage("无法拉起系统分享面板")
                        }
                    },
                    enabled = !isRendering && !isSaving && renderedPages.isNotEmpty(),
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("分享长图", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
