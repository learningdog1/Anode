@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.ssh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PostAdd
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.ssh.SshBenchmarkPostFormatter
import com.nodeseek.app.domain.ssh.SshExecutionRecord
import com.nodeseek.app.domain.ssh.SshFormattedPost
import com.nodeseek.app.domain.ssh.SshMergeLayout
import com.nodeseek.app.ui.components.MarkdownRenderer
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.pulseCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Off-main-thread export action runner for `LiteSshScreen`'s bottom dock so clicking
 * Copy / Insert-to-Reply / 1-Click Publish never blocks the UI thread on multi-MB outputs.
 */
@Immutable
internal data class AsyncSshExportActions(
    val isExporting: Boolean,
    val onCopyMarkdown: () -> Unit,
    val onInsertToReply: (() -> Unit)?,
    val onPublishToComposer: () -> Unit,
)

@Composable
internal fun rememberAsyncSshExportActions(
    previewUiState: LiteSshUiState?,
    viewModel: LiteSshViewModel,
    scope: CoroutineScope = rememberCoroutineScope(),
    onCopyText: (text: String, toast: String) -> Unit,
    onInsertToReplyCallback: ((String) -> Unit)?,
    onPublishToComposerCallback: (suggestedTitle: String, markdownContent: String) -> Unit,
): AsyncSshExportActions {
    var isExporting by remember { mutableStateOf(false) }

    fun runOffMainThread(consume: (SshFormattedPost) -> Unit) {
        if (isExporting) return
        isExporting = true
        scope.launch {
            try {
                val post = if (previewUiState != null) {
                    withContext(Dispatchers.Default) { previewUiState.exportPreviewPost }
                } else {
                    viewModel.buildExportPayloadAsync()
                }
                if (post != null && post.markdownContent.isNotBlank()) {
                    consume(post)
                }
            } finally {
                isExporting = false
            }
        }
    }

    return remember(
        isExporting,
        previewUiState,
        viewModel,
        onCopyText,
        onInsertToReplyCallback,
        onPublishToComposerCallback,
    ) {
        AsyncSshExportActions(
            isExporting = isExporting,
            onCopyMarkdown = {
                runOffMainThread { post ->
                    onCopyText(post.markdownContent, "已复制 Markdown 测评报告")
                }
            },
            onInsertToReply = onInsertToReplyCallback?.let { replyCb ->
                {
                    runOffMainThread { post ->
                        replyCb(post.markdownContent)
                    }
                }
            },
            onPublishToComposer = {
                runOffMainThread { post ->
                    onPublishToComposerCallback(post.suggestedTitle, post.markdownContent)
                }
            },
        )
    }
}

/**
 * Non-blocking SSH benchmark result preview & multi-run merge publish sheet.
 *
 * Capabilities:
 * - Formats single or multi-run outputs asynchronously on `Dispatchers.Default` so multi-megabyte
 *   `NodeQuality` / `YABS` / `融合怪` transcripts never freeze the UI thread.
 * - Allows checking/unchecking multiple runs from the active session and recent vault history
 *   directly inside the sheet to combine into a single review post.
 * - Supports switching multi-run merge style (`:::: tabs` vs `## 分段标题`), toggling IP privacy
 *   masking (`IpPrivacyMasker`), editing the suggested post title, copying Markdown or official
 *   NodeQuality plain text (`复制文本`), and 1-click publishing to `TopicComposerScreen`.
 */
@Composable
internal fun SshResultPublishSheet(
    uiState: LiteSshUiState,
    onToggleMaskIp: (Boolean) -> Unit,
    onSelectMergeLayout: (SshMergeLayout) -> Unit,
    onToggleCandidateRun: (SshExecutionRecord) -> Unit,
    onSelectCandidateRuns: (List<SshExecutionRecord>) -> Unit,
    onCopyMarkdown: (String) -> Unit,
    onCopyPlainText: ((String) -> Unit)? = null,
    onPublishToComposer: (suggestedTitle: String, markdownContent: String) -> Unit,
    onInsertToReply: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ext = NodePulseThemeTokens.extendedColors
    val isInspection = LocalInspectionMode.current

    val candidateRuns = remember(
        uiState.sessionRuns,
        uiState.recentRuns,
        uiState.viewingRunId,
        uiState.selectedServerId,
        uiState.activeServerHost,
    ) {
        uiState.mergeCandidateRuns
    }

    val activeServerKey = uiState.viewingRun?.serverId?.takeIf { it.isNotBlank() }
        ?: uiState.selectedServer?.id
    val sameServerCandidates = remember(candidateRuns, activeServerKey) {
        if (activeServerKey.isNullOrBlank()) {
            candidateRuns
        } else {
            candidateRuns.filter { it.serverId == activeServerKey }.ifEmpty { candidateRuns }
        }
    }

    val runsToFormat = remember(
        uiState.sessionRuns,
        uiState.selectedRunIdsForExport,
        uiState.viewingRunId,
        uiState.hasLiveOutputFlag,
        uiState.elapsedMillis / 2_000L,
    ) {
        uiState.exportableRuns
    }

    var formattedPost by remember {
        mutableStateOf<SshFormattedPost?>(
            if (isInspection) {
                SshBenchmarkPostFormatter.format(
                    runs = runsToFormat,
                    maskIp = uiState.maskServerIp,
                    mergeLayout = uiState.mergeLayout,
                )
            } else {
                null
            },
        )
    }
    var isFormatting by remember { mutableStateOf(!isInspection && runsToFormat.isNotEmpty()) }

    val formatRequestKey = remember(runsToFormat, uiState.maskServerIp, uiState.mergeLayout) {
        val idsPart = runsToFormat.joinToString(",") { "${it.id}:${it.sanitizedAnsiOutput.length}" }
        "${idsPart}_${uiState.maskServerIp}_${uiState.mergeLayout}"
    }

    LaunchedEffect(formatRequestKey, isInspection) {
        if (runsToFormat.isEmpty()) {
            formattedPost = null
            isFormatting = false
            return@LaunchedEffect
        }
        if (isInspection) {
            formattedPost = SshBenchmarkPostFormatter.format(
                runs = runsToFormat,
                maskIp = uiState.maskServerIp,
                mergeLayout = uiState.mergeLayout,
            )
            isFormatting = false
            return@LaunchedEffect
        }
        isFormatting = true
        val built = withContext(Dispatchers.Default) {
            SshBenchmarkPostFormatter.format(
                runs = runsToFormat,
                maskIp = uiState.maskServerIp,
                mergeLayout = uiState.mergeLayout,
            )
        }
        formattedPost = built
        isFormatting = false
    }

    val suggestedTitle = formattedPost?.suggestedTitle.orEmpty()
    var customTitle by rememberSaveable(suggestedTitle) { mutableStateOf(suggestedTitle) }
    val effectiveTitle = customTitle.trim().ifBlank { suggestedTitle }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = ext.cardSurfaceElevated,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "测评排版实时预览与合并发布",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = when {
                            formattedPost == null && isFormatting -> "正在后台整理测评报告排版…"
                            (formattedPost?.runCount ?: 0) >= 2 && uiState.mergeLayout == SshMergeLayout.SECTIONS ->
                                "已合并 ${formattedPost?.runCount} 项测试 · 各段独立标题排版"
                            formattedPost?.usesTabsLayout == true && (formattedPost?.runCount ?: 0) >= 2 ->
                                "已合并 ${formattedPost?.runCount} 项测试 · NodeSeek 多标签页（:::: tabs）"
                            formattedPost?.nodeQualityShareUrl != null || formattedPost?.plainTextCopy != null ->
                                "NodeQuality 官方多模块排版"
                            else ->
                                "单脚本 ANSI 高保真代码块（```ansi）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PulseAccentPill(
                    text = "review 测评",
                    icon = Icons.Rounded.PostAdd,
                )
            }

            // Multi-run selector strip (session runs + recent runs)
            if (candidateRuns.size >= 2 || (candidateRuns.size == 1 && uiState.selectedRunIdsForExport.isEmpty())) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Tab,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = "勾选要合并发布的测试结果（已选 ${runsToFormat.size} 项）",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (sameServerCandidates.size >= 2) {
                            TextButton(
                                onClick = { onSelectCandidateRuns(sameServerCandidates) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("全选本机 (${sameServerCandidates.size})", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val effectiveSelectedIds = remember(uiState.selectedRunIdsForExport, runsToFormat) {
                            if (uiState.selectedRunIdsForExport.isNotEmpty()) {
                                uiState.selectedRunIdsForExport
                            } else {
                                runsToFormat.map { it.id }.toSet()
                            }
                        }
                        candidateRuns.forEach { candidate ->
                            val checked = candidate.id in effectiveSelectedIds
                            val chipShape = RoundedCornerShape(10.dp)
                            Row(
                                modifier = Modifier
                                    .clip(chipShape)
                                    .background(
                                        if (checked) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                        } else {
                                            ext.cardSurface
                                        },
                                    )
                                    .border(
                                        width = if (checked) 1.2.dp else 0.8.dp,
                                        color = if (checked) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                        },
                                        shape = chipShape,
                                    )
                                    .clickable { onToggleCandidateRun(candidate) }
                                    .padding(horizontal = 9.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    imageVector = if (checked) {
                                        Icons.Rounded.CheckBox
                                    } else {
                                        Icons.Rounded.CheckBoxOutlineBlank
                                    },
                                    contentDescription = null,
                                    tint = if (checked) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(16.dp),
                                )
                                Column {
                                    Text(
                                        text = candidate.tabTitle.ifBlank { candidate.presetName },
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = "${candidate.serverAlias.ifBlank { "VPS" }} · ${formatDurationShort(candidate.durationMillis)}",
                                        style = NodePulseThemeTokens.extraTypography.microLabel,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Merge layout switcher when 2+ runs are selected
            if (runsToFormat.size >= 2) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SshMergeLayout.entries.forEach { option ->
                        FilterChip(
                            selected = uiState.mergeLayout == option,
                            onClick = { onSelectMergeLayout(option) },
                            label = {
                                Text(option.label, style = MaterialTheme.typography.labelSmall)
                            },
                        )
                    }
                }
            }

            // IP privacy toggle card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(shape = RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = if (uiState.maskServerIp) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(18.dp),
                    )
                    Column {
                        Text(
                            text = "自动打码服务器 IP",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = "对全部合并脚本统一隐藏公网 IPv4 / IPv6 后段",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = uiState.maskServerIp,
                    onCheckedChange = onToggleMaskIp,
                )
            }

            // Editable post title
            if (suggestedTitle.isNotEmpty() || customTitle.isNotEmpty()) {
                OutlinedTextField(
                    value = customTitle,
                    onValueChange = { customTitle = it },
                    label = { Text("测评帖标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            PulseGradientDivider()

            // Live rendered Markdown preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(min = 160.dp, max = 330.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val post = formattedPost
                when {
                    isFormatting && post == null -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "正在后台生成测评预览…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    post == null || post.markdownContent.isBlank() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "请至少勾选 1 项非空测试结果进行预览或发布",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    else -> {
                        MarkdownRenderer(
                            markdown = post.markdownContent,
                            enableTextSelection = true,
                        )
                    }
                }
            }

            val currentPost = formattedPost
            val actionsEnabled = currentPost != null && currentPost.markdownContent.isNotBlank() && !isFormatting

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    enabled = actionsEnabled,
                    onClick = {
                        currentPost?.let { onCopyMarkdown(it.markdownContent) }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("复制 MD", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                if (currentPost?.plainTextCopy != null && onCopyPlainText != null) {
                    OutlinedButton(
                        enabled = actionsEnabled,
                        onClick = { onCopyPlainText(currentPost.plainTextCopy) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text("复制纯文本", maxLines = 1)
                    }
                }

                if (onInsertToReply != null) {
                    FilledTonalButton(
                        enabled = actionsEnabled,
                        onClick = {
                            currentPost?.let {
                                onInsertToReply(it.markdownContent)
                                onDismiss()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text("插入回复", maxLines = 1)
                    }
                }

                Button(
                    enabled = actionsEnabled,
                    onClick = {
                        currentPost?.let {
                            onPublishToComposer(
                                effectiveTitle.ifBlank { it.suggestedTitle },
                                it.markdownContent,
                            )
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("一键发测评", maxLines = 1)
                }
            }
        }
    }
}
