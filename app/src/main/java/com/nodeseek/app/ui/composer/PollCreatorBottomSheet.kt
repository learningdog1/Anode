@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HowToVote
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.model.formatStardustWidgetMarkdown
import com.nodeseek.app.ui.components.PulseTextField
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseGradientDivider

private const val MAX_POLL_OPTIONS = 12
private const val MIN_POLL_OPTIONS = 2

private enum class ComposerWidgetTab(val label: String) {
    POLL("创建投票"),
    STARDUST("星尘收款卡"),
}

@Composable
fun PollCreatorBottomSheet(
    isCreatingPoll: Boolean,
    errorMessage: String?,
    viewerUid: Long?,
    onCreatePoll: (title: String, multiple: Boolean, isPublic: Boolean, options: List<String>) -> Unit,
    onInsertStardustMarkdown: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.86f).dp.coerceAtLeast(320.dp)
    val ext = NodePulseThemeTokens.extendedColors

    var activeTab by remember { mutableStateOf(ComposerWidgetTab.POLL) }
    var title by remember { mutableStateOf("") }
    var isMultiple by remember { mutableStateOf(false) }
    var isPublic by remember { mutableStateOf(true) }
    val options = remember { mutableStateListOf("", "") }

    var stardustAmount by remember { mutableStateOf("10") }
    var stardustNote by remember { mutableStateOf("") }
    var stardustOnce by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = { if (!isCreatingPoll) onDismiss() },
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (activeTab == ComposerWidgetTab.POLL) {
                        Icons.Rounded.HowToVote
                    } else {
                        Icons.Rounded.AutoAwesome
                    },
                    contentDescription = null,
                    tint = if (activeTab == ComposerWidgetTab.POLL) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        ext.stardustAccent
                    },
                )
                Text(
                    text = "插入帖内互动组件",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { if (!isCreatingPoll) onDismiss() },
                    enabled = !isCreatingPoll,
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ComposerWidgetTab.entries.forEach { tab ->
                    val selected = activeTab == tab
                    FilterChip(
                        selected = selected,
                        onClick = { if (!isCreatingPoll) activeTab = tab },
                        label = { Text(tab.label) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (tab == ComposerWidgetTab.POLL) {
                                    Icons.Rounded.HowToVote
                                } else {
                                    Icons.Rounded.AutoAwesome
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }

            PulseGradientDivider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (activeTab == ComposerWidgetTab.POLL) {
                    PulseTextField(
                        value = title,
                        onValueChange = { title = it.take(100) },
                        label = "投票标题",
                        placeholder = "例如：这台 HK 优化鸡续费还是出掉？",
                        singleLine = true,
                        enabled = !isCreatingPoll,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = !isMultiple,
                            onClick = { if (!isCreatingPoll) isMultiple = false },
                            label = { Text("单选") },
                        )
                        FilterChip(
                            selected = isMultiple,
                            onClick = { if (!isCreatingPoll) isMultiple = true },
                            label = { Text("多选") },
                        )
                        FilterChip(
                            selected = isPublic,
                            onClick = { if (!isCreatingPoll) isPublic = true },
                            label = { Text("公开投票人 UID") },
                        )
                        FilterChip(
                            selected = !isPublic,
                            onClick = { if (!isCreatingPoll) isPublic = false },
                            label = { Text("匿名投票") },
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "投票选项 (${options.size}/$MAX_POLL_OPTIONS)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        options.forEachIndexed { index, optionText ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                PulseTextField(
                                    value = optionText,
                                    onValueChange = { next ->
                                        options[index] = next.take(80)
                                    },
                                    label = "选项 ${index + 1}",
                                    placeholder = "输入选项内容",
                                    singleLine = true,
                                    enabled = !isCreatingPoll,
                                    modifier = Modifier.weight(1f),
                                )
                                if (options.size > MIN_POLL_OPTIONS) {
                                    IconButton(
                                        onClick = { options.removeAt(index) },
                                        enabled = !isCreatingPoll,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "删除选项",
                                        )
                                    }
                                }
                            }
                        }

                        if (options.size < MAX_POLL_OPTIONS) {
                            TextButton(
                                onClick = { options.add("") },
                                enabled = !isCreatingPoll,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("添加选项")
                            }
                        }
                    }

                    if (!errorMessage.isNullOrBlank()) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    val cleanOptions = options.map { it.trim() }.filter { it.isNotEmpty() }
                    val canCreate = title.trim().isNotEmpty() && cleanOptions.size >= MIN_POLL_OPTIONS && !isCreatingPoll

                    Button(
                        onClick = {
                            onCreatePoll(title.trim(), isMultiple, isPublic, cleanOptions)
                        },
                        enabled = canCreate,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 12.dp),
                    ) {
                        if (isCreatingPoll) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = if (isCreatingPoll) "正在创建投票…" else "创建并插入投票",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    if (viewerUid == null || viewerUid <= 0L) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(14.dp),
                        ) {
                            Text(
                                text = "请先登录 NodeSeek 账号后再生成个人星尘收款卡。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            text = "将为当前账号 (UID $viewerUid) 生成可直接投喂的星尘收款卡片。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PulseTextField(
                            value = stardustAmount,
                            onValueChange = { raw ->
                                stardustAmount = raw.filter { it.isDigit() }.take(5)
                            },
                            label = "建议金额（可选）",
                            placeholder = "例如：10",
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PulseTextField(
                            value = stardustNote,
                            onValueChange = { stardustNote = it.take(80) },
                            label = "附言说明（可选）",
                            placeholder = "例如：觉得测评有帮助欢迎投喂星尘 ✨",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    width = 0.8.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .clickable { stardustOnce = !stardustOnce }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "标记为一次性打赏",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "在卡片上显示「一次性打赏」提示徽标",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = stardustOnce,
                                onCheckedChange = { stardustOnce = it },
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                val snippet = formatStardustWidgetMarkdown(
                                    uid = viewerUid,
                                    amount = stardustAmount.toIntOrNull()?.takeIf { it > 0 },
                                    note = stardustNote,
                                    once = stardustOnce,
                                )
                                onInsertStardustMarkdown(snippet)
                            },
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = ext.stardustAccent,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "插入星尘收款卡",
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}
