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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.domain.ssh.IpPrivacyMasker
import com.nodeseek.app.domain.ssh.SshBenchmarkPostFormatter
import com.nodeseek.app.domain.ssh.SshExecutionRecord
import com.nodeseek.app.domain.ssh.SshVaultRepository
import com.nodeseek.app.ui.components.AnsiAndCodeFormatter
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

    val selectedRuns = remember(exportableHistory, selectedIds) {
        val idSet = selectedIds.toSet()
        exportableHistory.filter { it.id in idSet }
    }
    val formattedPost = remember(selectedRuns, maskServerIp) {
        if (selectedRuns.isEmpty()) {
            null
        } else {
            SshBenchmarkPostFormatter.format(
                runs = selectedRuns,
                maskIp = maskServerIp,
            )
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
                        )
                        Text(
                            text = "单选生成 ansi 代码块 · 多选自动生成 :::: tabs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        Text("去测机", style = MaterialTheme.typography.labelMedium)
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
                        tint = if (maskServerIp) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
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
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(exportableHistory, key = { it.id }) { run ->
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = when {
                            formattedPost == null -> "请至少勾选 1 条记录"
                            formattedPost.usesTabsLayout -> "已选 ${formattedPost.runCount} 项 · 将生成 :::: tabs"
                            else -> "已选 1 项 · 将生成 ```ansi 代码块"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (formattedPost != null) {
                        PulseAccentPill(
                            text = if (formattedPost.usesTabsLayout) "多标签" else "ANSI",
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        enabled = formattedPost != null,
                        onClick = {
                            formattedPost?.let { copyTextToClipboard(context, it.markdownContent) }
                        },
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("复制")
                    }

                    if (onPublishToComposer != null) {
                        FilledTonalButton(
                            enabled = formattedPost != null,
                            onClick = {
                                formattedPost?.let { post ->
                                    onPublishToComposer(post.suggestedTitle, post.markdownContent)
                                    onDismiss()
                                }
                            },
                        ) {
                            Text("发测评")
                        }
                    }

                    Button(
                        enabled = formattedPost != null,
                        onClick = {
                            formattedPost?.let { post ->
                                onInsertMarkdown(post.markdownContent)
                                onDismiss()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("一键插入 Markdown")
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
            val previewText = remember(run.sanitizedAnsiOutput, maskServerIp) {
                val snippet = run.sanitizedAnsiOutput
                    .lineSequence()
                    .take(12)
                    .joinToString("\n")
                val masked = if (maskServerIp) {
                    IpPrivacyMasker.mask(snippet, knownServerHost = run.serverHost)
                } else {
                    snippet
                }
                AnsiAndCodeFormatter.parseAnsiTerminalText(masked, defaultForeground = ext.onCodeSurface)
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
