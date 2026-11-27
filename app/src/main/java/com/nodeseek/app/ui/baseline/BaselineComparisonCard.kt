package com.nodeseek.app.ui.baseline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeseek.app.domain.baseline.BaselineComparisonFormatter
import com.nodeseek.app.domain.baseline.BaselineDiffReport
import com.nodeseek.app.domain.baseline.BaselineOverallVerdict
import com.nodeseek.app.domain.baseline.BenchmarkBaselineSnapshot
import com.nodeseek.app.domain.baseline.MetricTrend
import com.nodeseek.app.domain.baseline.RoutingShiftDiff
import com.nodeseek.app.domain.baseline.StreamingChangeType
import com.nodeseek.app.domain.valuation.VALUATION_ZONE_ID
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.pulseCard
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private val BASELINE_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

@Composable
fun BaselineActionRow(
    matchingBaselineCount: Int,
    totalBaselineCount: Int,
    onSaveAsBaseline: () -> Unit,
    onOpenCompare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = onSaveAsBaseline,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text("存为购入基线", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }

        OutlinedButton(
            onClick = onOpenCompare,
            enabled = totalBaselineCount > 0,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            val label = when {
                matchingBaselineCount > 0 -> "基线对比 ($matchingBaselineCount)"
                totalBaselineCount > 0 -> "基线对比 ($totalBaselineCount)"
                else -> "暂无基线"
            }
            Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal data class SanitizedBaselineComparisonCardDisplay(
    val safeHost: String,
    val highlightBullets: List<String>,
    val routingShifts: List<RoutingShiftDiff>,
)

internal fun sanitizeBaselineComparisonCardDisplay(
    diff: BaselineDiffReport,
    maskTargetIp: Boolean,
): SanitizedBaselineComparisonCardDisplay {
    val rawHost = diff.current.hostDisplay.ifBlank { diff.baseline.hostDisplay }
    val maskedHost = BaselineComparisonFormatter.maskDisplayHost(rawHost, maskTargetIp)
    if (!maskTargetIp) {
        return SanitizedBaselineComparisonCardDisplay(
            safeHost = maskedHost,
            highlightBullets = diff.highlightBullets,
            routingShifts = diff.routingShifts,
        )
    }
    val knownIps = listOfNotNull(
        diff.baseline.resolvedIp,
        diff.current.resolvedIp,
        diff.baseline.hostDisplay,
        diff.current.hostDisplay,
    )
    fun scrub(text: String): String = BaselineComparisonFormatter.scrubSensitiveIps(text, knownIps)
    return SanitizedBaselineComparisonCardDisplay(
        safeHost = scrub(maskedHost),
        highlightBullets = diff.highlightBullets.map(::scrub),
        routingShifts = diff.routingShifts.map { shift ->
            shift.copy(
                directionLabel = scrub(shift.directionLabel),
                beforeRoute = shift.beforeRoute?.let(::scrub),
                afterRoute = shift.afterRoute?.let(::scrub),
                shiftSummary = scrub(shift.shiftSummary),
            )
        },
    )
}

@Composable
fun BaselineComparisonCard(
    diff: BaselineDiffReport,
    maskTargetIp: Boolean,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val sanitizedDisplay = remember(diff, maskTargetIp) {
        sanitizeBaselineComparisonCardDisplay(diff, maskTargetIp)
    }
    val safeHost = sanitizedDisplay.safeHost
    val beforeDate = remember(diff.baseline.capturedAtEpochMillis) {
        BASELINE_DATE_FMT.format(
            Instant.ofEpochMilli(diff.baseline.capturedAtEpochMillis).atZone(VALUATION_ZONE_ID).toLocalDate(),
        )
    }
    val afterDate = remember(diff.current.capturedAtEpochMillis) {
        BASELINE_DATE_FMT.format(
            Instant.ofEpochMilli(diff.current.capturedAtEpochMillis).atZone(VALUATION_ZONE_ID).toLocalDate(),
        )
    }
    val verdictColor = when (diff.overallVerdict) {
        BaselineOverallVerdict.SIGNIFICANT_DEGRADATION -> MaterialTheme.colorScheme.error
        BaselineOverallVerdict.MINOR_REGRESSION -> ext.onDrumstickContainer
        BaselineOverallVerdict.IMPROVED,
        BaselineOverallVerdict.STABLE,
        -> MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                accentColor = verdictColor,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Before vs After · $safeHost",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$beforeDate → $afterDate · 间隔 ${diff.elapsedDays} 天",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = diff.overallVerdict.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = verdictColor,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(verdictColor.copy(alpha = 0.12f))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }

        if (sanitizedDisplay.highlightBullets.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                sanitizedDisplay.highlightBullets.forEach { bullet ->
                    Text(
                        text = "• $bullet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        // Throughput Deltas
        if (diff.bandwidthDeltas.isNotEmpty()) {
            SectionComparisonHeader("吞吐带宽")
            diff.bandwidthDeltas.forEach { bw ->
                val bText = bw.beforeMbps?.let { String.format(Locale.US, "%.1f", it) } ?: "-"
                val aText = bw.afterMbps?.let { String.format(Locale.US, "%.1f Mbps", it) } ?: "-"
                val dText = bw.deltaPercent?.let { String.format(Locale.US, "%+.0f%%", it) } ?: "-"
                ComparisonMetricLine(
                    label = bw.metricLabel,
                    beforeText = bText,
                    afterText = aText,
                    deltaText = dText,
                    trend = bw.trend,
                )
            }
        }

        // Latency & Loss Deltas
        if (diff.latencyDeltas.isNotEmpty()) {
            SectionComparisonHeader("延迟与丢包")
            diff.latencyDeltas.forEach { lat ->
                val bText = lat.beforeValue?.let { String.format(Locale.US, "%.1f", it) } ?: "-"
                val aText = lat.afterValue?.let { String.format(Locale.US, "%.1f %s", it, lat.unit) } ?: "-"
                val dText = lat.deltaValue?.let { String.format(Locale.US, "%+.1f %s", it, lat.unit) } ?: "-"
                ComparisonMetricLine(
                    label = lat.metricLabel,
                    beforeText = bText,
                    afterText = aText,
                    deltaText = dText,
                    trend = lat.trend,
                )
            }
        }

        // Routing Shifts
        if (sanitizedDisplay.routingShifts.isNotEmpty()) {
            SectionComparisonHeader("路由与骨干线路")
            sanitizedDisplay.routingShifts.forEach { shift ->
                ComparisonMetricLine(
                    label = shift.directionLabel,
                    beforeText = shift.beforeRoute ?: "-",
                    afterText = shift.afterRoute ?: "-",
                    deltaText = when {
                        shift.detourIntroduced -> "绕美"
                        shift.backboneDowngraded -> "降级"
                        shift.trend == MetricTrend.IMPROVED -> "提升"
                        else -> "变化"
                    },
                    trend = shift.trend,
                )
            }
        }

        // Streaming Unlocks
        if (diff.streamingDiffs.isNotEmpty()) {
            SectionComparisonHeader("流媒体与 AI 解锁")
            diff.streamingDiffs.forEach { unlock ->
                val trend = when (unlock.changeType) {
                    StreamingChangeType.LOST_UNLOCK,
                    StreamingChangeType.DOWNGRADED_TO_ORIGINALS,
                    -> MetricTrend.DEGRADED_SEVERE
                    StreamingChangeType.REGION_CHANGED -> MetricTrend.DEGRADED_WARN
                    StreamingChangeType.GAINED_UNLOCK -> MetricTrend.IMPROVED
                    StreamingChangeType.UNCHANGED -> MetricTrend.UNCHANGED
                }
                ComparisonMetricLine(
                    label = unlock.displayName,
                    beforeText = unlock.beforeStatus.label,
                    afterText = unlock.afterStatus.label,
                    deltaText = unlock.changeType.label,
                    trend = trend,
                )
            }
        }
    }
}

@Composable
private fun SectionComparisonHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ComparisonMetricLine(
    label: String,
    beforeText: String,
    afterText: String,
    deltaText: String,
    trend: MetricTrend,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val deltaColor = when (trend) {
        MetricTrend.DEGRADED_SEVERE -> MaterialTheme.colorScheme.error
        MetricTrend.DEGRADED_WARN -> ext.onDrumstickContainer
        MetricTrend.IMPROVED -> MaterialTheme.colorScheme.primary
        MetricTrend.UNCHANGED,
        MetricTrend.INFO,
        -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.32f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "$beforeText → $afterText",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.48f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = deltaText,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = deltaColor,
            modifier = Modifier.weight(0.20f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaselineComparisonBottomSheet(
    diff: BaselineDiffReport?,
    allBaselines: List<BenchmarkBaselineSnapshot>,
    matchingBaselineIds: Set<String>,
    selectedBaselineId: String?,
    maskTargetIp: Boolean,
    onSelectBaseline: (String) -> Unit,
    onDeleteBaseline: (String) -> Unit,
    onToggleMaskTargetIp: (Boolean) -> Unit,
    onCopyMarkdown: (String) -> Unit,
    onPublishMarkdown: ((String) -> Unit)? = null,
    onInsertToReply: ((String) -> Unit)? = null,
    onFeedbackMessage: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val isDark = NodePulseThemeTokens.isDark
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.90f).dp

    val orderedBaselines = remember(allBaselines, matchingBaselineIds) {
        val matching = allBaselines.filter { it.id in matchingBaselineIds }
        val others = allBaselines.filterNot { it.id in matchingBaselineIds }
        matching + others
    }
    val activeMarkdown = remember(diff, maskTargetIp) {
        diff?.let { BaselineComparisonFormatter.formatMarkdown(it, maskTargetIp = maskTargetIp) }.orEmpty()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Before vs After 基线对比",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Rounded.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text("隐藏 IP", style = MaterialTheme.typography.labelMedium)
                    Switch(
                        checked = maskTargetIp,
                        onCheckedChange = onToggleMaskTargetIp,
                    )
                }
            }

            if (orderedBaselines.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    orderedBaselines.forEach { baseline ->
                        val selected = baseline.id == selectedBaselineId
                        val isMatch = baseline.id in matchingBaselineIds
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectBaseline(baseline.id) },
                            label = {
                                Text(
                                    text = if (isMatch) "★ ${baseline.label}" else baseline.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingIcon = if (selected) {
                                {
                                    IconButton(
                                        onClick = { onDeleteBaseline(baseline.id) },
                                        modifier = Modifier.size(18.dp),
                                    ) {
                                        Icon(
                                            Icons.Rounded.DeleteOutline,
                                            contentDescription = "删除基线",
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (diff != null) {
                    BaselineComparisonCard(
                        diff = diff,
                        maskTargetIp = maskTargetIp,
                    )
                } else {
                    Text(
                        text = "请先保存至少一条购入基线，再选择基线进行对比。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (diff != null && activeMarkdown.isNotBlank()) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = { onCopyMarkdown(activeMarkdown) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("复制 MD", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = {
                            val ok = AndroidBaselineComparisonCardImageGenerator.shareCardPng(
                                context = context,
                                diff = diff,
                                maskTargetIp = maskTargetIp,
                                darkTheme = isDark,
                            )
                            onFeedbackMessage?.invoke(if (ok) "已调起分享对比卡片长图" else "导出对比图失败")
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("分享卡片图", maxLines = 1, softWrap = false)
                    }
                    if (onInsertToReply != null) {
                        FilledTonalButton(
                            onClick = {
                                onInsertToReply(activeMarkdown)
                                onDismiss()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text("插入回复", maxLines = 1, softWrap = false)
                        }
                    }
                    if (onPublishMarkdown != null) {
                        Button(
                            onClick = {
                                onPublishMarkdown(activeMarkdown)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("发对比帖", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SaveBaselineDialog(
    defaultLabel: String,
    onConfirm: (label: String, notes: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember(defaultLabel) { mutableStateOf(defaultLabel) }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存为购入基线") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "保存当前测速、延迟、路由及流媒体解锁快照，方便日后生成 Before vs After 对比卡。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("基线名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("备注（可选，例如：刚开机 / CN2 GIA）") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(label.trim().ifBlank { defaultLabel }, notes.trim())
                },
            ) {
                Text("保存基线")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
