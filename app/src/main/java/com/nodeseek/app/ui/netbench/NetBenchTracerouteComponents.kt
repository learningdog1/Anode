@file:OptIn(ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.netbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeseek.app.domain.netbench.BackboneRouteTag
import com.nodeseek.app.domain.netbench.ClientEgressSnapshot
import com.nodeseek.app.domain.netbench.HopLossClassification
import com.nodeseek.app.domain.netbench.IpClassificationEngine
import com.nodeseek.app.domain.netbench.NetBenchMarkdownFormatter
import com.nodeseek.app.domain.netbench.TracerouteDirection
import com.nodeseek.app.domain.netbench.TracerouteHop
import com.nodeseek.app.domain.netbench.TracerouteProgress
import com.nodeseek.app.domain.netbench.TracerouteSummary
import com.nodeseek.app.domain.ssh.SshServerProfile
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.components.PulseDialogTone
import com.nodeseek.app.ui.components.SortSegmentItem
import com.nodeseek.app.ui.components.SortSegmentedSwitcher
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseSectionHeader
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import java.util.Locale

@Composable
internal fun NetBenchTracerouteCard(
    targetInput: String,
    isRunningBenchmark: Boolean,
    forwardProgress: TracerouteProgress?,
    reverseSummary: TracerouteSummary?,
    isRunningReverseTrace: Boolean,
    reverseTraceError: String?,
    selectedReverseServer: SshServerProfile?,
    clientEgress: ClientEgressSnapshot?,
    vaultServers: List<SshServerProfile>,
    onRunForwardTraceOnly: () -> Unit,
    onTriggerReverseTrace: () -> Unit,
    onPickReverseServer: () -> Unit,
    enableForwardTrace: Boolean = true,
    enableReverseTrace: Boolean = true,
) {
    val forwardSummary = forwardProgress?.summary
    var selectedDirection by rememberSaveable {
        mutableStateOf(
            if (forwardSummary != null || enableForwardTrace) TracerouteDirection.FORWARD else TracerouteDirection.REVERSE,
        )
    }

    val effectiveDirection = when {
        forwardSummary != null && reverseSummary != null -> selectedDirection
        reverseSummary != null -> TracerouteDirection.REVERSE
        forwardSummary != null -> TracerouteDirection.FORWARD
        enableReverseTrace && !enableForwardTrace -> TracerouteDirection.REVERSE
        else -> TracerouteDirection.FORWARD
    }
    val activeSummary = if (effectiveDirection == TracerouteDirection.REVERSE) {
        reverseSummary
    } else {
        forwardSummary
    }

    val matchedHostServer = remember(targetInput, vaultServers) {
        val norm = normalizeHostForVaultMatch(targetInput)
        if (norm.isEmpty()) null else vaultServers.firstOrNull {
            normalizeHostForVaultMatch(it.resolvableNetBenchHost()) == norm
        }
    }
    val displayReverseTarget = selectedReverseServer ?: matchedHostServer

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PulseSectionHeader(
            title = "可视化路由追踪与骨干网识别",
            subtitle = "逐跳 MTR · CN2 GIA / 9929 / CMIN2 / 4837 / IIJ / SoftBank · 双向回程",
            icon = Icons.AutoMirrored.Rounded.AltRoute,
            trailing = {
                activeSummary?.timeWindowLabel?.takeIf { it.isNotBlank() }?.let { win ->
                    PulseAccentPill(
                        text = if (activeSummary.isPeakHourWindow) "晚高峰" else "非高峰",
                        containerColor = if (activeSummary.isPeakHourWindow) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        contentColor = if (activeSummary.isPeakHourWindow) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            },
        )

        // 1-tap Reverse Trace & Forward Trace Trigger Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PulseArchitecturalShapes.MediumBadge)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(
                    width = 0.8.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    shape = PulseArchitecturalShapes.MediumBadge,
                )
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (enableReverseTrace || reverseSummary != null || isRunningReverseTrace) {
                val directNet = clientEgress?.directNetwork
                val egressText = when {
                    directNet != null && !directNet.rawIp.isNullOrBlank() -> {
                        val masked = directNet.maskedIp ?: directNet.rawIp
                        "真实出口：${directNet.carrierDisplayName} · $masked"
                    }
                    clientEgress?.isVpnActive == true ->
                        "未探测到直连出口 IP（VPN 拦截时请关闭 VPN 或刷新出口）"
                    else -> "正在识别本机真实出口 IP…"
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Public,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = egressText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (enableForwardTrace || forwardSummary != null) {
                    OutlinedButton(
                        onClick = onRunForwardTraceOnly,
                        enabled = targetInput.isNotBlank() && !isRunningBenchmark,
                        modifier = if (!enableReverseTrace && reverseSummary == null) Modifier.fillMaxWidth() else Modifier,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.AltRoute, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("跑去程 MTR", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }

                if (enableReverseTrace || reverseSummary != null || isRunningReverseTrace) {
                    Button(
                        onClick = {
                            if (displayReverseTarget != null) {
                                onTriggerReverseTrace()
                            } else {
                                onPickReverseServer()
                            }
                        },
                        enabled = !isRunningReverseTrace,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        if (isRunningReverseTrace) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "正在经「${displayReverseTarget?.displayName ?: "VPS"}」追踪回程…",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (displayReverseTarget != null) {
                                    "一键反向回程 (${displayReverseTarget.displayName})"
                                } else {
                                    "一键 VPS 反向回程"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        if (!reverseTraceError.isNullOrBlank()) {
            NetBenchErrorCallout(message = reverseTraceError)
        }

        // Live progress bar during active forward traceroute
        if (forwardProgress != null && !forwardProgress.isFinished) {
            val frac = if (forwardProgress.totalRounds > 0) {
                ((forwardProgress.completedRounds.toFloat() * forwardProgress.maxHops + forwardProgress.currentHop) /
                    (forwardProgress.totalRounds * forwardProgress.maxHops.coerceAtLeast(1)).toFloat())
                    .coerceIn(0.05f, 0.99f)
            } else {
                0.2f
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "正在追踪 Hop ${forwardProgress.currentHop}/${forwardProgress.maxHops} · 第 ${forwardProgress.completedRounds.coerceAtLeast(1)}/${forwardProgress.totalRounds} 轮",
                    style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                    color = MaterialTheme.colorScheme.primary,
                )
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Forward / Reverse Switcher when both exist
        if (forwardSummary != null && reverseSummary != null) {
            SortSegmentedSwitcher(
                options = remember {
                    listOf(
                        SortSegmentItem(TracerouteDirection.FORWARD, "去程 (手机 → VPS)"),
                        SortSegmentItem(TracerouteDirection.REVERSE, "回程 (VPS → 手机)"),
                    )
                },
                selectedValue = effectiveDirection,
                onSelect = { selectedDirection = it },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (activeSummary != null) {
            // Backbone Tag Badges
            if (activeSummary.dominantBackboneTags.isNotEmpty() || activeSummary.routeSignature.isNotBlank()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    activeSummary.dominantBackboneTags.forEach { tag ->
                        BackboneTagBadge(tag = tag)
                    }
                    if (activeSummary.dominantBackboneTags.isEmpty() && activeSummary.routeSignature.isNotBlank()) {
                        PulseAccentPill(
                            text = activeSummary.routeSignature,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (activeSummary.sourceLabel.isNotBlank()) {
                        PulseAccentPill(
                            text = "${activeSummary.sourceLabel} · ${activeSummary.toolName}",
                            icon = Icons.Rounded.Terminal,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (!activeSummary.errorMessage.isNullOrBlank() && activeSummary.hops.isEmpty()) {
                NetBenchErrorCallout(message = activeSummary.errorMessage)
            } else {
                // Telemetry Summary Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TracerouteStatItem(
                        label = "总跳数",
                        value = "${activeSummary.totalHops} 跳",
                    )
                    TracerouteStatItem(
                        label = "终点均延",
                        value = activeSummary.terminalAvgMs?.let { NetBenchMarkdownFormatter.formatMs(it) } ?: "—",
                    )
                    TracerouteStatItem(
                        label = "峰值丢包",
                        value = String.format(Locale.US, "%.1f%%", activeSummary.peakHopLossPercent),
                    )
                    val lossBadgeText = when {
                        activeSummary.hasRealPacketLoss -> "真实丢包"
                        activeSummary.hasIntermediateRateLimitOnly -> "中间跳限速"
                        else -> "零丢包"
                    }
                    TracerouteStatItem(
                        label = "丢包判定",
                        value = lossBadgeText,
                    )
                }

                PulseGradientDivider()

                // Per-hop MTR Table
                if (activeSummary.hops.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TracerouteHopTableHeader()
                        activeSummary.hops.forEach { hop ->
                            TracerouteHopRow(hop = hop)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackboneTagBadge(tag: BackboneRouteTag) {
    val (bg, fg) = when {
        tag.isPremium -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        tag == BackboneRouteTag.IIJ || tag == BackboneRouteTag.SOFTBANK ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    PulseAccentPill(
        text = tag.displayLabel,
        containerColor = bg,
        contentColor = fg,
    )
}

@Composable
private fun TracerouteStatItem(
    label: String,
    value: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TracerouteHopTableHeader() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
        Text("节点 IP / 归属地", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(160.dp))
        Text("骨干 / ASN", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(105.dp))
        Text("丢包", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
        Text("均延 / 峰值", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(100.dp))
    }
}

@Composable
private fun TracerouteHopRow(hop: TracerouteHop) {
    val lossColor = when (hop.lossClassification) {
        HopLossClassification.REAL_PACKET_LOSS -> MaterialTheme.colorScheme.error
        HopLossClassification.INTERMEDIATE_RATE_LIMIT -> MaterialTheme.colorScheme.tertiary
        HopLossClassification.TIMEOUT_NO_REPLY -> MaterialTheme.colorScheme.onSurfaceVariant
        HopLossClassification.ZERO_LOSS -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (hop.isTargetReached) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.45f)
                },
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${hop.hopNumber}",
            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
            fontWeight = FontWeight.Bold,
            color = if (hop.isTargetReached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(24.dp),
        )
        Column(modifier = Modifier.width(160.dp)) {
            Text(
                text = hop.ip ?: "* (超时)",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = if (hop.ip == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val loc = hop.locationSummary
            val flag = if (!hop.countryCode.isNullOrBlank()) {
                "${IpClassificationEngine.countryFlagEmoji(hop.countryCode)} "
            } else {
                ""
            }
            val subText = listOfNotNull(
                loc?.let { "$flag$it" },
                hop.hostname?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (subText.isNotBlank()) {
                Text(
                    text = subText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(modifier = Modifier.width(105.dp)) {
            if (hop.backboneTags.isNotEmpty()) {
                Text(
                    text = hop.backboneTags.joinToString("/") { it.displayLabel },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (hop.backboneTags.any { it.isPremium }) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = hop.asn ?: if (hop.isPrivateOrCgnat) "内网/CGNAT" else "—",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            text = String.format(Locale.US, "%.0f%%", hop.lossPercent),
            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
            fontWeight = FontWeight.SemiBold,
            color = lossColor,
            modifier = Modifier.width(56.dp),
        )
        val rttLabel = if (hop.avgMs != null) {
            val maxPart = hop.maxMs?.let { "/${String.format(Locale.US, "%.1f", it)}" }.orEmpty()
            "${String.format(Locale.US, "%.1f", hop.avgMs)}$maxPart ms"
        } else {
            "—"
        }
        Text(
            text = rttLabel,
            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(100.dp),
        )
    }
}

@Composable
internal fun ReverseTraceHostKeyMismatchDialog(
    mismatch: PendingReverseHostKeyMismatch,
    onTrustAndContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    PulseAlertDialog(
        onDismissRequest = onDismiss,
        title = "反向回程 SSH 主机指纹变更",
        message = "经由「${mismatch.serverAlias}」(${mismatch.actualHostKey.host}:${mismatch.actualHostKey.port}) 发起回程路由追踪时，检测到服务器主机密钥与本地 TOFU 记录不一致。",
        icon = Icons.Rounded.WarningAmber,
        tone = PulseDialogTone.DESTRUCTIVE,
        confirmText = "信任新指纹并继续",
        onConfirm = onTrustAndContinue,
        dismissText = "取消",
        onDismiss = onDismiss,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PulseArchitecturalShapes.CodeBlock)
                .background(ext.codeSurface)
                .border(0.8.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f), PulseArchitecturalShapes.CodeBlock)
                .padding(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "已记录：${mismatch.expectedFingerprint}",
                    style = NodePulseThemeTokens.extraTypography.codeLabel.copy(fontSize = 11.sp),
                    color = ext.onCodeSurface,
                )
                Text(
                    text = "当前：${mismatch.actualHostKey.fingerprintSha256}",
                    style = NodePulseThemeTokens.extraTypography.codeLabel.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
