package com.nodeseek.app.ui.composer

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nodeseek.app.domain.model.NodeImageItem
import com.nodeseek.app.domain.repository.ImageHostRepository
import com.nodeseek.app.ui.components.PulseConfirmDialog
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.pulseCard
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeImageGallerySheet(
    hasApiKey: Boolean,
    imageHostRepository: ImageHostRepository,
    onInsertMarkdown: ((String) -> Unit)? = null,
    onOpenApiKeyConfig: (() -> Unit)? = null,
    onFeedbackMessage: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp.coerceAtLeast(360.dp)

    var items by remember { mutableStateOf<List<NodeImageItem>>(emptyList()) }
    var currentPage by remember { mutableStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var totalCount by remember { mutableStateOf<Int?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var deletingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDeleteItem by remember { mutableStateOf<NodeImageItem?>(null) }

    fun loadPage(page: Int, append: Boolean) {
        if (!hasApiKey) return
        coroutineScope.launch {
            if (append) {
                isLoadingMore = true
            } else {
                isLoading = true
                errorMessage = null
            }
            val result = imageHostRepository.listImages(page = page, limit = 24)
            if (append) {
                isLoadingMore = false
            } else {
                isLoading = false
            }
            result.fold(
                onSuccess = { galleryPage ->
                    items = if (append) {
                        (items + galleryPage.items).distinctBy { it.imageId }
                    } else {
                        galleryPage.items
                    }
                    currentPage = galleryPage.page
                    hasMore = galleryPage.hasMore
                    totalCount = galleryPage.totalCount
                },
                onFailure = { err ->
                    val msg = err.message?.takeIf { it.isNotBlank() } ?: "加载 NodeImage 图床相册失败"
                    if (append) {
                        onFeedbackMessage(msg)
                    } else {
                        errorMessage = msg
                    }
                },
            )
        }
    }

    LaunchedEffect(hasApiKey) {
        if (hasApiKey) {
            loadPage(page = 1, append = false)
        } else {
            items = emptyList()
            errorMessage = null
        }
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
                        imageVector = Icons.Rounded.PhotoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "NodeImage 图床相册",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = when {
                        !hasApiKey -> "请先配置 X-API-Key 查看云端图片"
                        totalCount != null -> "共 ${totalCount} 张云端图片"
                        items.isNotEmpty() -> "已加载 ${items.size} 张云端图片"
                        else -> "浏览、复用或删除已上传至 NodeImage 的图片"
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (hasApiKey) {
                    IconButton(
                        onClick = { loadPage(page = 1, append = false) },
                        enabled = !isLoading && !isLoadingMore,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "刷新相册",
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭",
                    )
                }
            }

            when {
                !hasApiKey -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pulseCard(
                                shape = PulseArchitecturalShapes.EditorialCard,
                                elevation = PulseElevation.Subtle,
                            )
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp),
                        )
                        Text(
                            text = "尚未配置 NodeImage API Key",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "配置 api.nodeimage.com 的 X-API-Key 后，即可管理云端相册并快速插入帖子。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (onOpenApiKeyConfig != null) {
                            Button(
                                onClick = onOpenApiKeyConfig,
                                shape = CircleShape,
                            ) {
                                Text("配置 API Key")
                            }
                        }
                    }
                }

                isLoading && items.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = "正在加载云端相册…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                errorMessage != null && items.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pulseCard(
                                shape = PulseArchitecturalShapes.EditorialCard,
                                elevation = PulseElevation.Subtle,
                            )
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = errorMessage ?: "加载失败",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { loadPage(page = 1, append = false) },
                                shape = CircleShape,
                            ) {
                                Text("重试")
                            }
                            if (onOpenApiKeyConfig != null) {
                                TextButton(onClick = onOpenApiKeyConfig) {
                                    Text("检查 API Key")
                                }
                            }
                        }
                    }
                }

                items.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pulseCard(
                                shape = PulseArchitecturalShapes.EditorialCard,
                                elevation = PulseElevation.Subtle,
                            )
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PhotoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(30.dp),
                        )
                        Text(
                            text = "图床相册暂无图片",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "上传图片到 NodeImage 后会展示在这里。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 12.dp),
                    ) {
                        items(items, key = { it.imageId }) { item ->
                            val isDeleting = item.imageId in deletingIds
                            NodeImageGalleryCard(
                                item = item,
                                isDeleting = isDeleting,
                                canInsert = onInsertMarkdown != null,
                                onInsert = {
                                    val alt = item.fileName
                                        .replace("[", "")
                                        .replace("]", "")
                                        .ifBlank { "image" }
                                    onInsertMarkdown?.invoke("\n![$alt](${item.url})\n")
                                    onDismiss()
                                },
                                onCopyUrl = {
                                    copyGalleryUrlToClipboard(context, item.url)
                                    onFeedbackMessage("图片链接已复制")
                                },
                                onDeleteClick = { pendingDeleteItem = item },
                            )
                        }

                        if (hasMore) {
                            item(span = { GridItemSpan(2) }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    OutlinedButton(
                                        onClick = { loadPage(page = currentPage + 1, append = true) },
                                        enabled = !isLoadingMore,
                                        shape = CircleShape,
                                    ) {
                                        if (isLoadingMore) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 2.dp,
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("加载中…")
                                        } else {
                                            Text("加载下一页")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDeleteItem?.let { target ->
        PulseConfirmDialog(
            onDismissRequest = { pendingDeleteItem = null },
            title = "删除云端图片",
            message = "确定要从 NodeImage 图床永久删除「${target.fileName.ifBlank { target.imageId }}」吗？已发布的帖子外链可能会失效。",
            confirmText = "确认删除",
            tone = PulseDialogTone.DESTRUCTIVE,
            icon = Icons.Rounded.DeleteOutline,
            onConfirm = {
                pendingDeleteItem = null
                deletingIds = deletingIds + target.imageId
                coroutineScope.launch {
                    val result = imageHostRepository.deleteImage(target.imageId)
                    deletingIds = deletingIds - target.imageId
                    result.fold(
                        onSuccess = {
                            items = items.filterNot { it.imageId == target.imageId }
                            totalCount = totalCount?.let { (it - 1).coerceAtLeast(0) }
                            onFeedbackMessage("已从图床删除")
                        },
                        onFailure = { err ->
                            onFeedbackMessage(err.message ?: "删除图片失败")
                        },
                    )
                }
            },
        )
    }
}

@Composable
private fun NodeImageGalleryCard(
    item: NodeImageItem,
    isDeleting: Boolean,
    canInsert: Boolean,
    onInsert: () -> Unit,
    onCopyUrl: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val context = LocalContext.current
    val ext = NodePulseThemeTokens.extendedColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = RoundedCornerShape(14.dp),
                elevation = PulseElevation.Subtle,
            )
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !isDeleting) {
                if (canInsert) onInsert() else onCopyUrl()
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.25f)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.thumbnailUrl.ifBlank { item.url })
                    .crossfade(true)
                    .build(),
                contentDescription = item.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (isDeleting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.fileName.ifBlank { item.imageId },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                item.sizeBytes?.let(::formatGalleryBytes),
                item.createdAt.take(10).takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.dp, ext.cardBorder.copy(alpha = 0f)),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canInsert) {
                    TextButton(
                        onClick = onInsert,
                        enabled = !isDeleting,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AddPhotoAlternate,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("插入", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onCopyUrl,
                        enabled = !isDeleting,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = "复制链接",
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    IconButton(
                        onClick = onDeleteClick,
                        enabled = !isDeleting,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "删除图片",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun copyGalleryUrlToClipboard(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("NodeImage URL", url))
}

private fun formatGalleryBytes(bytes: Long): String {
    if (bytes < 1024L) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
    return String.format(Locale.US, "%.1f MB", kb / 1024.0)
}
