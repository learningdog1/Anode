@file:OptIn(ExperimentalMaterial3Api::class)

package com.nodeseek.app.ui.ssh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.domain.ssh.IpPrivacyMasker
import com.nodeseek.app.domain.ssh.SanitizedBufferSnapshot
import com.nodeseek.app.domain.ssh.SshBenchmarkPostFormatter
import com.nodeseek.app.domain.ssh.SshExecutionRecord
import com.nodeseek.app.domain.ssh.SshFormattedPost
import com.nodeseek.app.domain.ssh.SshVaultRepository
import com.nodeseek.app.ui.components.AnsiAndCodeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.withTabularNumbers

/**
 * Reusable quick-insert modal bottom sheet for embedding saved/recent SSH benchmark outputs
 * directly into NodeSeek Topic Replies or Topic Composer drafts.
 *
 * - Selecting 1 run inserts a fenced ```` ```ansi ```` Markdown block.
 * - Selecting 2+ runs automatically composes a NodeSeek interactive `:::: tabs` / `::: tab-item`
 *   benchmark block.
 * - IP privacy masking (`自动打码服务器 IP`) is enabled by default.
 */
@Composable
fun SshQuickInsertSheet(
    onDismiss: () -> Unit,
    onInsertMarkdown: (markdownContent: String) -> Unit,
    onOpenFullLiteSsh: (() -> Unit)? = null,
    onPublishToComposer: ((suggestedTitle: String, markdownContent: String) -> Unit)? = null,
    vaultRepository: SshVaultRepository? = null,
) {
    val context = LocalContext.current
    val resolvedVault = remember(vaultRepository, context) {
        vaultRepository ?: SshServerVault.getDefault(context)
    }
    val recentRuns by resolvedVault.recentRunsFlow.collectAsStateWithLifecycle()
    val exportableHistory = remember(recentRuns) { recentRuns.filter { it.hasOutput } }

    var selectedIds by rememberSaveable(exportableHistory.firstOrNull()?.id) {
        mutableStateOf(
            exportableHistory.firstOrNull()?.let { listOf(it.id) } ?: emptyList(),
        )
    }
    var maskServerIp by rememberSaveable { mutableStateOf(true) }
    var expandedPreviewRunId by rememberSaveable { mutableStateOf<String?>(null) }
    var historySearchQuery by rememberSaveable { mutableStateOf("") }

    val filteredHistory = remember(exportableHistory, historySearchQuery) {
        val q = historySearchQuery.trim().lowercase()
        if (q.isEmpty()) {
            exportableHistory
        } else {
            val tokens = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
            exportableHistory.filter { run ->
                tokens.all { token ->
                    run.serverAlias.lowercase().contains(token) ||
                        run.serverHost.lowercase().contains(token) ||
                        run.presetName.lowercase().contains(token) ||
                        run.tabTitle.lowercase().contains(token) ||
                        run.command.lowercase().contains(token)
                }
            }
        }
    }

    val selectedRuns = remember(exportableHistory, selectedIds) {
        val idSet = selectedIds.toSet()
        exportableHistory.filter { it.id in idSet }
    }
    val isInspection = LocalInspectionMode.current
    var formattedPost by remember {
        mutableStateOf<SshFormattedPost?>(
            if (isInspection && selectedRuns.isNotEmpty()) {
                SshBenchmarkPostFormatter.format(runs = selectedRuns, maskIp = maskServerIp)
            } else {
                null
            },
        )
    }
    LaunchedEffect(selectedRuns, maskServerIp, isInspection) {
        formattedPost = if (selectedRuns.isEmpty()) {
            null
        } else if (isInspection) {
            SshBenchmarkPostFormatter.format(runs = selectedRuns, maskIp = maskServerIp)
        } else {
            withContext(Dispatchers.Default) {
                SshBenchmarkPostFormatter.format(
                    runs = selectedRuns,
                    maskIp = maskServerIp,
                )
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ext = NodePulseThemeTokens.extendedColors

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
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text(
                            text = "插入 SSH 测机报告",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "单选生成 ansi 代码块 · 多选自动生成 :::: tabs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (onOpenFullLiteSsh != null) {
                    FilledTonalButton(
                        onClick = {
                            onDismiss()
                            onOpenFullLiteSsh()
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "去测机",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }

            // IP privacy toggle bar
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
                        tint = if (maskServerIp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Column {
                        Text(
                            text = "自动打码服务器 IP",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = "隐藏公网 IPv4 / IPv6 后段，保留线路段特征",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = maskServerIp,
                    onCheckedChange = { maskServerIp = it },
                )
            }

            if (exportableHistory.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseCard(shape = RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        text = "暂无已保存的测机记录",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "先打开「轻量 SSH」一键跑完 YABS / NodeQuality / NextTrace，即可直接在此插入 Markdown",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (onOpenFullLiteSsh != null) {
                        Button(
                            onClick = {
                                onDismiss()
                                onOpenFullLiteSsh()
                            },
                        ) {
                            Text("打开轻量 SSH")
                        }
                    }
                }
            } else {
                if (exportableHistory.size > 3 || historySearchQuery.isNotEmpty()) {
                    SshCompactSearchBar(
                        value = historySearchQuery,
                        onValueChange = { historySearchQuery = it },
                        onClear = { historySearchQuery = "" },
                        placeholder = "搜索主机备注 / IP / 脚本名称…",
                        matchCountSummary = if (historySearchQuery.isNotBlank()) {
                            "${filteredHistory.size}/${exportableHistory.size}"
                        } else {
                            null
                        },
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredHistory, key = { it.id }) { run ->
                        val isSelected = run.id in selectedIds
                        val isExpanded = expandedPreviewRunId == run.id
                        QuickInsertRunRow(
                            run = run,
                            selected = isSelected,
                            expanded = isExpanded,
                            maskServerIp = maskServerIp,
                            onToggleSelected = {
                                selectedIds = if (isSelected) {
                                    selectedIds - run.id
                                } else {
                                    selectedIds + run.id
                                }
                            },
                            onTogglePreview = {
                                expandedPreviewRunId = if (isExpanded) null else run.id
                            },
                        )
                    }
                }

                PulseGradientDivider()

                val currentFormattedPost = formattedPost
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = when {
                            currentFormattedPost == null -> "请至少勾选 1 条记录"
                            currentFormattedPost.usesTabsLayout -> "已选 ${currentFormattedPost.runCount} 项 · 将生成 :::: tabs"
                            else -> "已选 ${currentFormattedPost.runCount} 项 · 将生成 ```ansi 代码块"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                    )
                    if (currentFormattedPost != null) {
                        PulseAccentPill(
                            text = if (currentFormattedPost.usesTabsLayout) "多标签" else "ANSI",
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        enabled = currentFormattedPost != null,
                        onClick = {
                            currentFormattedPost?.let { copyTextToClipboard(context, it.markdownContent) }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "复制",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (onPublishToComposer != null) {
                        FilledTonalButton(
                            enabled = currentFormattedPost != null,
                            onClick = {
                                currentFormattedPost?.let { post ->
                                    onPublishToComposer(post.suggestedTitle, post.markdownContent)
                                    onDismiss()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = "发测评",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Button(
                        enabled = currentFormattedPost != null,
                        onClick = {
                            currentFormattedPost?.let { post ->
                                onInsertMarkdown(post.markdownContent)
                                onDismiss()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = "一键插入 Markdown",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickInsertRunRow(
    run: SshExecutionRecord,
    selected: Boolean,
    expanded: Boolean,
    maskServerIp: Boolean,
    onToggleSelected: () -> Unit,
    onTogglePreview: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = shape,
                borderColor = if (selected) MaterialTheme.colorScheme.primary else null,
                borderWidth = if (selected) 1.4.dp else 1.dp,
            )
            .clickable(onClick = onToggleSelected)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (selected) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "${run.serverAlias} · ${run.presetName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${formatRunTimestamp(run.startedAtEpochMillis)} · ${formatDurationShort(run.durationMillis)} · ${runStatusLabel(run.status, run.exitCode)}",
                    style = MaterialTheme.typography.bodySmall.withTabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(
                onClick = onTogglePreview,
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = "展开 ANSI 预览",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (expanded) {
            var previewText by remember(run.id, maskServerIp) {
                mutableStateOf(AnnotatedString("正在加载预览…"))
            }
            LaunchedEffect(run.id, run.sanitizedAnsiOutput, maskServerIp, ext.onCodeSurface) {
                previewText = withContext(Dispatchers.Default) {
                    val tailSlice = run.sanitizedAnsiOutput.take(8_192)
                    val snippet = SanitizedBufferSnapshot.fromSanitizedText(
                        sanitizedText = tailSlice,
                        maxLines = 12,
                        maxLineChars = 240,
                    ).fullAnsiText.trimEnd('\n', '\r')
                    val masked = if (maskServerIp) {
                        IpPrivacyMasker.mask(snippet, knownServerHost = run.serverHost)
                    } else {
                        snippet
                    }
                    AnsiAndCodeFormatter.parseAnsiTerminalText(masked, defaultForeground = ext.onCodeSurface)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ext.codeSurface)
                    .border(0.8.dp, ext.codeBorder, RoundedCornerShape(10.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                Text(
                    text = previewText,
                    style = NodePulseThemeTokens.extraTypography.codeBody.copy(
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                    ),
                    softWrap = false,
                )
            }
        }
    }
}
