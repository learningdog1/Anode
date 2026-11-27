@file:OptIn(ExperimentalMaterial3Api::class)

package com.nodeseek.app.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.pulseCard
import kotlinx.coroutines.launch

@Composable
fun CategoryReorderSheet(
    initialCategories: List<Category>,
    isSyncing: Boolean,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<Category>) -> Unit,
    onReset: () -> Unit,
) {
    var draftCategories by remember { mutableStateOf(initialCategories) }
    val liveDraftHolder = remember {
        object {
            var list: List<Category> = initialCategories
        }
    }
    var hasEdited by remember { mutableStateOf(false) }
    var draggedSlug by remember { mutableStateOf<String?>(null) }
    var draggingOffsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(initialCategories) {
        if (!hasEdited && draggedSlug == null) {
            liveDraftHolder.list = initialCategories
            draftCategories = initialCategories
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        sheetGesturesEnabled = draggedSlug == null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "定制首页频道",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "拖拽右侧手柄调整顺序，开关控制首页显示",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭",
                    )
                }
            }

            if (isSyncing) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Pinned "全部" row outside LazyColumn so it cannot be dragged or hidden
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(shape = RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PushPin,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "全部",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "固定置顶 · 始终显示",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PulseAccentPill(
                    text = "已置顶",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 420.dp),
                contentPadding = PaddingValues(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(
                    items = draftCategories,
                    key = { _, item -> item.slug },
                ) { _, category ->
                    val isDragging = draggedSlug == category.slug
                    val categorySpec = NodePulseThemeTokens.categorySpec(category.slug)

                    val rowModifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragging) 1f else 0f)
                        .then(if (!isDragging) Modifier.animateItem() else Modifier)
                        .graphicsLayer {
                            translationY = if (isDragging) draggingOffsetY else 0f
                            scaleX = if (isDragging) 1.02f else 1f
                            scaleY = if (isDragging) 1.02f else 1f
                        }

                    Row(
                        modifier = rowModifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isDragging) {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            )
                            .border(
                                width = if (isDragging) 1.2.dp else 0.8.dp,
                                color = if (isDragging) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                },
                                shape = RoundedCornerShape(14.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(categorySpec.spineColor),
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = category.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = category.slug,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }

                        Switch(
                            checked = category.visibleOnHome,
                            enabled = category.isCustomizable && !isSaving,
                            onCheckedChange = { checked ->
                                hasEdited = true
                                val updated = liveDraftHolder.list.map { item ->
                                    if (item.slug == category.slug) {
                                        item.copy(visibleOnHome = checked)
                                    } else {
                                        item
                                    }
                                }
                                liveDraftHolder.list = updated
                                draftCategories = updated
                            },
                        )

                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .pointerInput(category.slug) {
                                    detectDragGestures(
                                        onDragStart = {
                                            draggedSlug = category.slug
                                            draggingOffsetY = 0f
                                            hasEdited = true
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            draggingOffsetY += dragAmount.y

                                            val currentList = liveDraftHolder.list
                                            val currentIndex = currentList.indexOfFirst { it.slug == category.slug }
                                            if (currentIndex < 0) return@detectDragGestures

                                            val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
                                            val currentInfo = visibleItems.firstOrNull { it.key == category.slug }
                                                ?: return@detectDragGestures
                                            // Wait for LazyColumn layout to reflect any previous intra-frame swap
                                            if (currentInfo.index != currentIndex) return@detectDragGestures

                                            val currentCenter = currentInfo.offset + draggingOffsetY + currentInfo.size / 2f

                                            val nextCategory = currentList.getOrNull(currentIndex + 1)
                                            val nextInfo = nextCategory?.let { next ->
                                                visibleItems.firstOrNull { it.key == next.slug }
                                            }
                                            val prevCategory = currentList.getOrNull(currentIndex - 1)
                                            val prevInfo = prevCategory?.let { prev ->
                                                visibleItems.firstOrNull { it.key == prev.slug }
                                            }

                                            if (nextInfo != null && currentCenter > nextInfo.offset + nextInfo.size / 2f) {
                                                val mutable = currentList.toMutableList()
                                                val moved = mutable.removeAt(currentIndex)
                                                mutable.add(currentIndex + 1, moved)
                                                liveDraftHolder.list = mutable
                                                draftCategories = mutable
                                                draggingOffsetY -= (nextInfo.offset - currentInfo.offset)
                                            } else if (prevInfo != null && currentCenter < prevInfo.offset + prevInfo.size / 2f) {
                                                val mutable = currentList.toMutableList()
                                                val moved = mutable.removeAt(currentIndex)
                                                mutable.add(currentIndex - 1, moved)
                                                liveDraftHolder.list = mutable
                                                draftCategories = mutable
                                                draggingOffsetY += (currentInfo.offset - prevInfo.offset)
                                            }

                                            val viewportStart = lazyListState.layoutInfo.viewportStartOffset
                                            val viewportEnd = lazyListState.layoutInfo.viewportEndOffset
                                            val itemTop = currentInfo.offset + draggingOffsetY
                                            val itemBottom = itemTop + currentInfo.size
                                            if (itemBottom > viewportEnd - 32f) {
                                                coroutineScope.launch { lazyListState.scrollBy(18f) }
                                            } else if (itemTop < viewportStart + 32f) {
                                                coroutineScope.launch { lazyListState.scrollBy(-18f) }
                                            }
                                        },
                                        onDragEnd = {
                                            draggedSlug = null
                                            draggingOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            draggedSlug = null
                                            draggingOffsetY = 0f
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DragHandle,
                                contentDescription = "拖拽调整 ${category.name} 顺序",
                                tint = if (isDragging) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        onReset()
                        onDismiss()
                    },
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("恢复默认")
                }
                Button(
                    onClick = {
                        onSave(draftCategories)
                        onDismiss()
                    },
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("保存")
                }
            }
        }
    }
}
