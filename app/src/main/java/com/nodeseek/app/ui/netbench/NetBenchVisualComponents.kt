package com.nodeseek.app.ui.netbench

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeseek.app.domain.netbench.ClientEgressSnapshot
import com.nodeseek.app.domain.netbench.ClientNetworkInfo
import com.nodeseek.app.domain.netbench.EgressRouteKind
import com.nodeseek.app.domain.netbench.HttpLatencyResult
import com.nodeseek.app.domain.netbench.IcmpPingProgress
import com.nodeseek.app.domain.netbench.IpClassificationEngine
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.IpIntelligenceReport
import com.nodeseek.app.domain.netbench.IpOriginType
import com.nodeseek.app.domain.netbench.IpUsageType
import com.nodeseek.app.domain.netbench.Iperf3Direction
import com.nodeseek.app.domain.netbench.Iperf3Progress
import com.nodeseek.app.domain.netbench.Iperf3Stage
import com.nodeseek.app.domain.netbench.NetBenchHistoryEntry
import com.nodeseek.app.domain.netbench.NetBenchMarkdownFormatter
import com.nodeseek.app.domain.netbench.NetBenchMarkdownStyle
import com.nodeseek.app.domain.netbench.ResolvedTarget
import com.nodeseek.app.domain.netbench.TcpPingProgress
import com.nodeseek.app.domain.netbench.TcpPingSample
import com.nodeseek.app.ui.components.MarkdownRenderer
import com.nodeseek.app.ui.components.SortSegmentItem
import com.nodeseek.app.ui.components.SortSegmentedSwitcher
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseSectionHeader
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import java.util.Locale

internal object NetBenchTelemetryColors {
    @Composable
    fun excellent(): Color = MaterialTheme.colorScheme.primary

    @Composable
    fun good(): Color = MaterialTheme.colorScheme.secondary

    @Composable
    fun moderate(): Color = NodePulseThemeTokens.extendedColors.onDrumstickContainer

    @Composable
    fun poor(): Color = MaterialTheme.colorScheme.error

    @Composable
    fun violet(): Color = MaterialTheme.colorScheme.tertiary

    @Composable
    fun forLatencyMs(ms: Double?): Color = when {
        ms == null -> poor()
        ms < 60.0 -> excellent()
        ms < 150.0 -> good()
        ms < 250.0 -> moderate()
        else -> poor()
    }

    @Composable
    fun forOrigin(origin: IpOriginType): Color = when (origin) {
        IpOriginType.NATIVE -> excellent()
        IpOriginType.BROADCAST -> moderate()
        IpOriginType.ANYCAST -> violet()
        IpOriginType.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

internal fun copyTextToClipboard(context: Context, label: String, text: String): Boolean =
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        true
    }.getOrDefault(false)

/**
 * Compact selectable chip styled consistently with NodePulse cards.
 */
@Composable
internal fun NetBenchSelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val isDark = NodePulseThemeTokens.isDark
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.22f else 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.65f else 0.55f)
        },
        label = "netbenchChipBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.60f else 0.45f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        },
        label = "netbenchChipBorder",
    )
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .clip(PulseArchitecturalShapes.SmallChip)
            .background(containerColor)
            .border(width = 1.dp, color = borderColor, shape = PulseArchitecturalShapes.SmallChip)
            .pulseBouncyClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/**
 * Resolved target pill displaying resolved IP and IPv4/IPv6 badge.
 */
@Composable
internal fun ResolvedTargetBanner(
    resolved: ResolvedTarget,
    port: Int,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(PulseArchitecturalShapes.MediumBadge)
            .background(ext.cardSurface)
            .border(
                width = 0.8.dp,
                color = ext.cardBorder,
                shape = PulseArchitecturalShapes.MediumBadge,
            )
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Router,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = resolved.formatAddressWithPort(port),
                style = NodePulseThemeTokens.extraTypography.codeLabel,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (resolved.isDomain) {
                val dnsText = if (resolved.dnsDurationMs > 0.0) {
                    "域名 ${resolved.displayHost} · DNS ${NetBenchMarkdownFormatter.formatMs(resolved.dnsDurationMs)}"
                } else {
                    "域名 ${resolved.displayHost}"
                }
                Text(
                    text = dnsText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        PulseAccentPill(
            text = resolved.ipFamily.label,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Displays the device's real underlying egress IP vs proxy/VPN egress IP simultaneously,
 * plus an explicit fallback banner when VPN prevents binding to the underlying physical network.
 */
@Composable
internal fun NetBenchClientEgressBanner(
    egress: ClientEgressSnapshot?,
    fallbackNetwork: ClientNetworkInfo?,
    testViaProxy: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onEnableProxyTest: (() -> Unit)? = null,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val direct = egress?.directNetwork
    val proxy = egress?.proxyNetwork
    val fallbackSingle = fallbackNetwork?.takeIf { egress == null }

    if (egress == null && fallbackSingle == null && !isRefreshing) return

    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val isFallbackWarning = egress?.isShowingProxyFallback == true

    val bgColor = when {
        isFallbackWarning -> ext.drumstickContainer.copy(alpha = if (isDark) 0.28f else 0.52f)
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val borderColor = when {
        isFallbackWarning -> ext.onDrumstickContainer.copy(alpha = if (isDark) 0.42f else 0.30f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(PulseArchitecturalShapes.MediumBadge)
            .background(bgColor)
            .border(width = 0.8.dp, color = borderColor, shape = PulseArchitecturalShapes.MediumBadge)
            .padding(horizontal = 10.dp, vertical = if (compact) 7.dp else 9.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp),
    ) {
        if (egress == null && fallbackSingle == null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.8.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "正在检测本机真实出口 IP 与代理状态…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        if (isFallbackWarning) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = ext.onDrumstickContainer,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "检测到代理/VPN，以下为代理出口 IP",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = ext.onDrumstickContainer,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                )
                EgressRefreshPill(isRefreshing = isRefreshing, onRefresh = onRefresh)
            }
            egress.directBindFailureReason?.let { reason ->
                Text(
                    text = reason.userHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            proxy?.let { proxyInfo ->
                EgressIpSummaryRow(
                    badgeText = "代理出口 IP",
                    info = proxyInfo,
                    transportFallback = egress.activeTransportLabel,
                    badgeBg = MaterialTheme.colorScheme.surfaceContainerHigh,
                    badgeFg = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (!testViaProxy && onEnableProxyTest != null && !compact) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    NetBenchSelectableChip(
                        label = "切换为「经由代理测试」",
                        selected = false,
                        icon = Icons.Rounded.VpnLock,
                        onClick = onEnableProxyTest,
                    )
                }
            }
        } else if (egress?.proxyDetected == true) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = if (egress.isVpnActive) "检测到代理/VPN" else "检测到系统代理",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                PulseAccentPill(
                    text = if (testViaProxy) "经由代理测试" else "直连底层网络",
                    containerColor = if (testViaProxy) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    contentColor = if (testViaProxy) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                )
                Spacer(modifier = Modifier.weight(1f))
                EgressRefreshPill(isRefreshing = isRefreshing, onRefresh = onRefresh)
            }
            direct?.let { directInfo ->
                EgressIpSummaryRow(
                    badgeText = "真实出口 IP",
                    info = directInfo,
                    transportFallback = egress.directTransportLabel,
                    badgeBg = MaterialTheme.colorScheme.primaryContainer,
                    badgeFg = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            proxy?.let { proxyInfo ->
                EgressIpSummaryRow(
                    badgeText = "代理出口 IP",
                    info = proxyInfo,
                    transportFallback = egress.activeTransportLabel,
                    badgeBg = MaterialTheme.colorScheme.surfaceContainerHigh,
                    badgeFg = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val net = direct ?: fallbackSingle
            if (net != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        EgressIpSummaryRow(
                            badgeText = if (net.routeKind == EgressRouteKind.PROXY_FALLBACK) "代理出口 IP" else "真实出口 IP",
                            info = net,
                            transportFallback = egress?.directTransportLabel,
                            badgeBg = MaterialTheme.colorScheme.primaryContainer,
                            badgeFg = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    EgressRefreshPill(isRefreshing = isRefreshing, onRefresh = onRefresh)
                }
            }
        }
    }
}

@Composable
private fun EgressRefreshPill(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(enabled = !isRefreshing, onClick = onRefresh)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(11.dp),
                strokeWidth = 1.6.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = "刷新出口 IP",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            text = if (isRefreshing) "检测中" else "刷新",
            style = NodePulseThemeTokens.extraTypography.microLabel,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun EgressIpSummaryRow(
    badgeText: String,
    info: ClientNetworkInfo,
    transportFallback: String?,
    badgeBg: Color,
    badgeFg: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PulseAccentPill(
            text = badgeText,
            containerColor = badgeBg,
            contentColor = badgeFg,
        )
        val details = buildList {
            add("${info.carrierDisplayName} · ${info.ipFamily.label}")
            info.maskedIp?.let { add(it) }
            info.asn?.let { add(it) }
            val transport = info.transportLabel ?: transportFallback
            transport?.let { add(it) }
        }.joinToString(" · ")
        Text(
            text = details,
            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Horizontal strip of recent benchmark targets persisted in `filesDir/netbench_history.json`.
 */
@Composable
internal fun NetBenchRecentTargetsStrip(
    entries: List<NetBenchHistoryEntry>,
    onSelect: (NetBenchHistoryEntry) -> Unit,
    onRemove: (NetBenchHistoryEntry) -> Unit,
    onClearAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    val isDark = NodePulseThemeTokens.isDark
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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
                    imageVector = Icons.Rounded.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "最近测机目标",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onClearAll != null) {
                Text(
                    text = "清空",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onClearAll)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            entries.forEach { entry ->
                val effectiveLatencyMs = entry.lastPingAvgMs ?: entry.lastAvgMs
                val latencyColor = NetBenchTelemetryColors.forLatencyMs(effectiveLatencyMs)
                Row(
                    modifier = Modifier
                        .clip(PulseArchitecturalShapes.MediumBadge)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh.copy(
                                alpha = if (isDark) 0.72f else 0.62f,
                            ),
                        )
                        .border(
                            width = 0.8.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                            shape = PulseArchitecturalShapes.MediumBadge,
                        )
                        .clickable { onSelect(entry) }
                        .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = "${entry.host}:${entry.port}",
                            style = NodePulseThemeTokens.extraTypography.codeLabel,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        val meta = buildList {
                            when {
                                entry.lastPingAvgMs != null && entry.lastAvgMs != null ->
                                    add("Ping ${NetBenchMarkdownFormatter.formatMs(entry.lastPingAvgMs)} · TCP ${NetBenchMarkdownFormatter.formatMs(entry.lastAvgMs)}")
                                entry.lastPingAvgMs != null ->
                                    add("Ping ${NetBenchMarkdownFormatter.formatMs(entry.lastPingAvgMs)}")
                                entry.lastAvgMs != null ->
                                    add(NetBenchMarkdownFormatter.formatMs(entry.lastAvgMs))
                            }
                            entry.lastOriginType?.takeIf { it != IpOriginType.UNKNOWN }?.let { add(it.badgeLabel) }
                            entry.lastRegionSummary?.let { add(it) }
                        }.joinToString(" · ")
                        if (meta.isNotEmpty()) {
                            Text(
                                text = meta,
                                style = NodePulseThemeTokens.extraTypography.microLabel,
                                color = if (effectiveLatencyMs != null) latencyColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "删除历史记录",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .clickable { onRemove(entry) }
                            .padding(2.dp),
                    )
                }
            }
        }
    }
}

/**
 * Normalized sample point for shared RTT bar + trend sparkline visualization.
 */
internal data class LatencySamplePoint(
    val seq: Int,
    val success: Boolean,
    val rttMs: Double?,
)

/**
 * Custom Canvas live RTT latency bar chart + smooth trend sparkline + average reference line.
 */
@Composable
internal fun LatencyWaveformChart(
    samples: List<LatencySamplePoint>,
    plannedCount: Int,
    avgMs: Double?,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 112.dp,
) {
    val poorColor = NetBenchTelemetryColors.poor()
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val lineColor = MaterialTheme.colorScheme.primary
    val avgLineColor = MaterialTheme.colorScheme.outlineVariant

    val sparkPath = remember { Path() }
    val avgDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f) }
    val barTopColor = remember(lineColor) { lineColor.copy(alpha = 0.78f) }
    val barBottomColor = remember(lineColor) { lineColor.copy(alpha = 0.22f) }

    val maxRtt = remember(samples) {
        var rawMax = 0.0
        var found = false
        for (i in samples.indices) {
            val s = samples[i]
            val rtt = s.rttMs
            if (s.success && rtt != null && rtt > rawMax) {
                rawMax = rtt
                found = true
            }
        }
        val base = if (found) rawMax else 100.0
        (base * 1.22).coerceAtLeast(20.0)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "实时 RTT 波形 (${samples.size}/$plannedCount)",
                style = NodePulseThemeTokens.extraTypography.microLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "峰值刻度 ${NetBenchMarkdownFormatter.formatMs(maxRtt)}",
                style = NodePulseThemeTokens.extraTypography.microLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .clip(PulseArchitecturalShapes.SmallChip)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 8.dp, vertical = 10.dp),
        ) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            // Horizontal reference grid lines (25%, 50%, 75%)
            for (step in 1..3) {
                val y = h * (step / 4f)
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            // Average dashed line
            if (avgMs != null && avgMs > 0.0) {
                val avgNorm = (avgMs / maxRtt).toFloat().coerceIn(0f, 1f)
                val avgY = h * (1f - avgNorm)
                drawLine(
                    color = avgLineColor.copy(alpha = 0.85f),
                    start = Offset(0f, avgY),
                    end = Offset(w, avgY),
                    strokeWidth = 1.2.dp.toPx(),
                    pathEffect = avgDashEffect,
                )
            }

            val slots = maxOf(plannedCount, samples.size, 4)
            val slotWidth = w / slots.toFloat()
            val barWidth = (slotWidth * 0.58f).coerceIn(3.dp.toPx(), 18.dp.toPx())
            val barCorner = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            val failCorner = CornerRadius(3.dp.toPx(), 3.dp.toPx())
            val barGradient = Brush.verticalGradient(
                colors = listOf(barTopColor, barBottomColor),
                startY = 0f,
                endY = h,
            )

            sparkPath.reset()
            var hasSparkStart = false
            var successDotCount = 0

            for (index in samples.indices) {
                val sample = samples[index]
                val centerX = slotWidth * index + slotWidth / 2f
                val leftX = centerX - barWidth / 2f

                if (sample.success && sample.rttMs != null) {
                    val norm = (sample.rttMs / maxRtt).toFloat().coerceIn(0.04f, 1f)
                    val barHeight = h * norm
                    val topY = h - barHeight

                    drawRoundRect(
                        brush = barGradient,
                        topLeft = Offset(leftX, topY),
                        size = Size(barWidth, barHeight),
                        cornerRadius = barCorner,
                    )

                    successDotCount++
                    if (!hasSparkStart) {
                        sparkPath.moveTo(centerX, topY)
                        hasSparkStart = true
                    } else {
                        sparkPath.lineTo(centerX, topY)
                    }
                } else {
                    // Timeout / dropped packet indicator bar
                    val failHeight = h * 0.22f
                    drawRoundRect(
                        color = poorColor.copy(alpha = 0.72f),
                        topLeft = Offset(leftX, h - failHeight),
                        size = Size(barWidth, failHeight),
                        cornerRadius = failCorner,
                    )
                    hasSparkStart = false
                }
            }

            if (successDotCount >= 2) {
                drawPath(
                    path = sparkPath,
                    color = lineColor.copy(alpha = 0.82f),
                    style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            val dotRadius = 2.6.dp.toPx()
            for (index in samples.indices) {
                val sample = samples[index]
                if (sample.success && sample.rttMs != null) {
                    val norm = (sample.rttMs / maxRtt).toFloat().coerceIn(0.04f, 1f)
                    val topY = h * (1f - norm)
                    val centerX = slotWidth * index + slotWidth / 2f
                    drawCircle(
                        color = lineColor,
                        radius = dotRadius,
                        center = Offset(centerX, topY),
                    )
                }
            }
        }
    }
}

/**
 * Custom Canvas live TCPing latency bar chart + smooth trend sparkline + average reference line.
 */
@Composable
internal fun TcpPingLatencyChart(
    samples: List<TcpPingSample>,
    plannedCount: Int,
    avgMs: Double?,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 112.dp,
) {
    val points = remember(samples) {
        samples.map { LatencySamplePoint(seq = it.seq, success = it.success, rttMs = it.rttMs) }
    }
    LatencyWaveformChart(
        samples = points,
        plannedCount = plannedCount,
        avgMs = avgMs,
        modifier = modifier,
        chartHeight = chartHeight,
    )
}

/**
 * Full ICMP Ping result card with KPI metric pills, live RTT chart, and binding diagnostics.
 */
@Composable
internal fun NetBenchIcmpPingCard(
    progress: IcmpPingProgress,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val summary = progress.summary
    val points = remember(progress.samples) {
        progress.samples.map { LatencySamplePoint(seq = it.seq, success = it.success, rttMs = it.rttMs) }
    }
    val subtitle = buildList {
        add("ICMP")
        summary.boundInterfaceName?.let { add("网卡 $it") }
        add("${summary.received}/${summary.sent} 成功")
    }.joinToString(" · ")

    NetBenchLatencyResultCard(
        title = "Ping 延迟探测",
        subtitle = subtitle,
        icon = Icons.Rounded.GraphicEq,
        isFinished = progress.isFinished,
        completedFraction = progress.completedFraction,
        plannedCount = progress.totalPlanned,
        sent = summary.sent,
        avgMs = summary.avgMs,
        minMs = summary.minMs,
        maxMs = summary.maxMs,
        jitterMdevMs = summary.jitterMdevMs,
        lossPercent = summary.lossPercent,
        samples = points,
        warningNote = summary.networkBindingNote,
        errorMessage = progress.errorMessage,
        compact = compact,
        modifier = modifier,
    )
}

/**
 * Full TCPing result card with KPI metric pills and live RTT chart.
 */
@Composable
internal fun NetBenchTcpPingCard(
    progress: TcpPingProgress,
    httpResult: HttpLatencyResult? = null,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val summary = progress.summary
    val points = remember(progress.samples) {
        progress.samples.map { LatencySamplePoint(seq = it.seq, success = it.success, rttMs = it.rttMs) }
    }

    NetBenchLatencyResultCard(
        title = "TCPing 延迟探测",
        subtitle = "端口 ${progress.port} · ${summary.received}/${summary.sent} 成功",
        icon = Icons.Rounded.NetworkCheck,
        isFinished = progress.isFinished,
        completedFraction = progress.completedFraction,
        plannedCount = progress.totalPlanned,
        sent = summary.sent,
        avgMs = summary.avgMs,
        minMs = summary.minMs,
        maxMs = summary.maxMs,
        jitterMdevMs = summary.jitterMdevMs,
        lossPercent = summary.lossPercent,
        samples = points,
        warningNote = null,
        errorMessage = progress.errorMessage,
        compact = compact,
        modifier = modifier,
    ) {
        // Optional HTTP latency strip
        if (httpResult != null) {
            PulseGradientDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = "HTTP 探测",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (httpResult.success && httpResult.totalMs != null) {
                    val info = buildList {
                        httpResult.statusCode?.let { add("HTTP $it") }
                        add(NetBenchMarkdownFormatter.formatMs(httpResult.totalMs))
                        httpResult.tlsHandshakeMs?.let { add("TLS ${NetBenchMarkdownFormatter.formatMs(it)}") }
                    }.joinToString(" · ")
                    Text(
                        text = info,
                        style = NodePulseThemeTokens.extraTypography.codeLabel,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        text = httpResult.errorMessage ?: "探测失败",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun NetBenchLatencyResultCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isFinished: Boolean,
    completedFraction: Float,
    plannedCount: Int,
    sent: Int,
    avgMs: Double?,
    minMs: Double?,
    maxMs: Double?,
    jitterMdevMs: Double?,
    lossPercent: Double,
    samples: List<LatencySamplePoint>,
    warningNote: String?,
    errorMessage: String?,
    compact: Boolean,
    modifier: Modifier = Modifier,
    extraFooterContent: @Composable (() -> Unit)? = null,
) {
    val hasLoss = lossPercent > 0.0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard()
            .padding(if (compact) 12.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PulseSectionHeader(
            title = title,
            subtitle = subtitle,
            icon = icon,
            trailing = {
                val statusText = when {
                    !isFinished -> "${(completedFraction * 100).toInt()}%"
                    lossPercent <= 0.0 -> "0% 丢包"
                    else -> "丢包 ${NetBenchMarkdownFormatter.formatPercent(lossPercent)}"
                }
                PulseAccentPill(
                    text = statusText,
                    containerColor = if (hasLoss) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    contentColor = if (hasLoss) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                )
            },
        )

        if (!isFinished) {
            LinearProgressIndicator(
                progress = { completedFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }

        // Single 4-column neutral telemetry strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(vertical = 10.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val avgDisplay = when {
                avgMs != null -> NetBenchMarkdownFormatter.formatMs(avgMs)
                !isFinished -> "探测中"
                sent == 0 -> "不可用"
                else -> "超时"
            }
            NetBenchTelemetryStatColumn(
                label = "平均延迟",
                value = avgDisplay,
                valueColor = if (avgMs == null && isFinished) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            NetBenchTelemetryStatColumn(
                label = "最小/最大",
                value = if (minMs != null && maxMs != null) {
                    "${String.format(Locale.US, "%.0f", minMs)}/${String.format(Locale.US, "%.0f", maxMs)} ms"
                } else {
                    "—"
                },
                valueColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            NetBenchTelemetryStatColumn(
                label = "抖动",
                value = jitterMdevMs?.let { NetBenchMarkdownFormatter.formatMs(it) } ?: "—",
                valueColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            NetBenchTelemetryStatColumn(
                label = "丢包率",
                value = NetBenchMarkdownFormatter.formatPercent(lossPercent),
                valueColor = if (hasLoss) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }

        if (samples.isNotEmpty()) {
            LatencyWaveformChart(
                samples = samples,
                plannedCount = plannedCount,
                avgMs = avgMs,
                chartHeight = if (compact) 86.dp else 114.dp,
            )
        }

        if (!warningNote.isNullOrBlank()) {
            NetBenchWarningCallout(message = warningNote)
        }

        if (!errorMessage.isNullOrBlank()) {
            NetBenchErrorCallout(message = errorMessage)
        }

        extraFooterContent?.invoke()
    }
}

@Composable
private fun NetBenchTelemetryStatColumn(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
            ).withTabularNumbers(),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * Custom Canvas semi-circular Mbps throughput gauge with smooth sweep animation.
 */
@Composable
internal fun MbpsArcGauge(
    mbps: Double,
    label: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 136.dp,
) {
    val scaleMax = remember(mbps) {
        when {
            mbps <= 100.0 -> 100.0
            mbps <= 300.0 -> 300.0
            mbps <= 500.0 -> 500.0
            mbps <= 1000.0 -> 1000.0
            mbps <= 2500.0 -> 2500.0
            else -> 10_000.0
        }
    }
    val targetFraction = (mbps / scaleMax).toFloat().coerceIn(0f, 1f)
    val animatedFractionState = animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "mbpsGaugeFraction",
    )
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val secondaryAccent = MaterialTheme.colorScheme.secondary
    val sweepBrush = remember(accentColor, secondaryAccent) {
        Brush.sweepGradient(
            colors = listOf(accentColor, secondaryAccent, accentColor),
        )
    }

    Box(
        modifier = modifier.size(width = sizeDp, height = sizeDp * 0.78f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val strokePx = 10.dp.toPx()
            val arcDiameter = minOf(size.width, size.height * 1.25f) - strokePx
            val topLeft = Offset(
                x = (size.width - arcDiameter) / 2f,
                y = strokePx / 2f,
            )
            val arcSize = Size(arcDiameter, arcDiameter)

            // Background track (220 deg arc)
            drawArc(
                color = trackColor,
                startAngle = 160f,
                sweepAngle = 220f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )

            // Active sweep gradient arc (reads State inside DrawScope only -> 0 recompositions)
            val fraction = animatedFractionState.value
            if (fraction > 0.005f) {
                drawArc(
                    brush = sweepBrush,
                    startAngle = 160f,
                    sweepAngle = 220f * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 14.dp),
        ) {
            val (valueStr, unitStr) = if (mbps >= 1000.0) {
                String.format(Locale.US, "%.2f", mbps / 1000.0) to "Gbps"
            } else {
                String.format(Locale.US, "%.1f", mbps) to "Mbps"
            }
            Text(
                text = valueStr,
                style = NodePulseThemeTokens.extraTypography.metricValue.copy(fontSize = 21.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = "$unitStr · $label",
                style = NodePulseThemeTokens.extraTypography.microLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * Live native pure-Kotlin iperf3 throughput card showing Download &/or Upload gauges.
 */
@Composable
internal fun NetBenchIperf3Card(
    downloadProgress: Iperf3Progress?,
    uploadProgress: Iperf3Progress?,
    iperfPort: Int,
    compact: Boolean = false,
    onOpenIperf3ServerSetup: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (downloadProgress == null && uploadProgress == null) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard()
            .padding(if (compact) 12.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PulseSectionHeader(
            title = "原生 iperf3 吞吐测速",
            subtitle = "纯 Kotlin TCP 协议 · 端口 $iperfPort",
            icon = Icons.Rounded.Speed,
            accentColor = MaterialTheme.colorScheme.primary,
        )

        val items = listOfNotNull(downloadProgress, uploadProgress)
        if (items.size == 2 && !compact) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Iperf3SingleDirectionBlock(
                    progress = items[0],
                    modifier = Modifier.weight(1f),
                    gaugeSize = 122.dp,
                )
                Iperf3SingleDirectionBlock(
                    progress = items[1],
                    modifier = Modifier.weight(1f),
                    gaugeSize = 122.dp,
                )
            }
        } else {
            items.forEachIndexed { idx, progress ->
                if (idx > 0) PulseGradientDivider()
                Iperf3SingleDirectionBlock(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth(),
                    gaugeSize = if (compact) 114.dp else 136.dp,
                )
            }
        }

        if (onOpenIperf3ServerSetup != null && items.any { it.stage == Iperf3Stage.FAILED }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f))
                    .pulseBouncyClickable(onClick = onOpenIperf3ServerSetup)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "一键开启 VPS 的 iperf3 服务端 (端口 $iperfPort)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun Iperf3SingleDirectionBlock(
    progress: Iperf3Progress,
    gaugeSize: Dp,
    modifier: Modifier = Modifier,
) {
    val isDownload = progress.direction == Iperf3Direction.DOWNLOAD
    val dirColor = MaterialTheme.colorScheme.primary
    val displayMbps = when (progress.stage) {
        Iperf3Stage.COMPLETED -> progress.finalResult?.effectiveMbps ?: progress.averageMbps
        Iperf3Stage.TEST_RUNNING -> if (progress.instantMbps > 0.0) progress.instantMbps else progress.averageMbps
        else -> progress.averageMbps
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = if (isDownload) Icons.Rounded.Download else Icons.Rounded.Upload,
                    contentDescription = null,
                    tint = dirColor,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = progress.direction.flagLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            PulseAccentPill(
                text = progress.stage.label,
                containerColor = if (progress.stage == Iperf3Stage.FAILED) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                contentColor = if (progress.stage == Iperf3Stage.FAILED) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
            )
        }

        if (progress.stage == Iperf3Stage.FAILED) {
            NetBenchErrorCallout(
                message = progress.errorMessage ?: "iperf3 测速失败",
            )
        } else {
            MbpsArcGauge(
                mbps = displayMbps,
                label = if (progress.stage == Iperf3Stage.COMPLETED) "平均" else "实时",
                accentColor = dirColor,
                sizeDp = gaugeSize,
            )

            if (progress.stage != Iperf3Stage.COMPLETED) {
                LinearProgressIndicator(
                    progress = { progress.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape),
                    color = dirColor,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "已传输 ${NetBenchMarkdownFormatter.formatBytes(progress.bytesTransferred)}",
                    style = NodePulseThemeTokens.extraTypography.microLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "峰值 ${NetBenchMarkdownFormatter.formatMbps(progress.peakMbps)}",
                    style = NodePulseThemeTokens.extraTypography.microLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * IP Intelligence & Native-vs-Broadcast classifier card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NetBenchIpIntelligenceCard(
    report: IpIntelligenceReport,
    clientNetwork: ClientNetworkInfo?,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    clientEgress: ClientEgressSnapshot? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val locationText = IpClassificationEngine.formatLocationSummary(
        countryCode = report.countryCode,
        countryName = report.countryName,
        regionName = report.regionName,
        cityName = report.cityName,
    )
    val (originBg, originFg) = when (report.originType) {
        IpOriginType.NATIVE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        IpOriginType.BROADCAST -> ext.drumstickContainer to ext.onDrumstickContainer
        IpOriginType.ANYCAST -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        IpOriginType.UNKNOWN -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard()
            .padding(if (compact) 12.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PulseSectionHeader(
            title = "IP 归属与原生/广播鉴定",
            subtitle = "${report.ip} · ${report.ipFamily.label}",
            icon = Icons.Rounded.Public,
            accentColor = MaterialTheme.colorScheme.primary,
            trailing = {
                PulseAccentPill(
                    text = report.originType.label,
                    icon = when (report.originType) {
                        IpOriginType.NATIVE -> Icons.Rounded.VerifiedUser
                        IpOriginType.BROADCAST -> Icons.Rounded.WarningAmber
                        IpOriginType.ANYCAST -> Icons.Rounded.Bolt
                        IpOriginType.UNKNOWN -> Icons.Rounded.Public
                    },
                    containerColor = originBg,
                    contentColor = originFg,
                )
            },
        )

        // Transparent evidence box
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PulseArchitecturalShapes.MediumBadge)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(
                    width = 0.8.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    shape = PulseArchitecturalShapes.MediumBadge,
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = report.originEvidence,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (report.errorMessage != null) {
            NetBenchErrorCallout(message = report.errorMessage)
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PulseAccentPill(
                    text = locationText,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )
                report.asn?.let { asn ->
                    PulseAccentPill(
                        text = asn,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (report.usageType != IpUsageType.UNKNOWN) {
                    PulseAccentPill(
                        text = report.usageType.label,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val orgLabel = report.orgName?.takeIf { it.isNotBlank() } ?: report.ispName
            if (!orgLabel.isNullOrBlank()) {
                Text(
                    text = "运营商 / 机构：$orgLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val sourceSummary = listOfNotNull(
                report.geoSource?.let { "Geo: $it" },
                report.whoisSource?.let { "WHOIS: $it" },
                report.rirAuthority?.let { "RIR: $it" },
            ).joinToString(" · ")
            if (sourceSummary.isNotEmpty()) {
                Text(
                    text = sourceSummary,
                    style = NodePulseThemeTokens.extraTypography.microLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                )
            }
        }

        // Local client egress privacy strip
        if (clientNetwork != null || clientEgress != null) {
            PulseGradientDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = null,
                    tint = if (clientEgress?.isShowingProxyFallback == true) {
                        ext.onDrumstickContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(14.dp),
                )
                val clientText = buildString {
                    when {
                        clientEgress?.isShowingProxyFallback == true -> {
                            val proxy = clientEgress.proxyNetwork
                            append("检测到代理/VPN，以下为代理出口 IP")
                            if (proxy != null) {
                                append("：${proxy.carrierDisplayName} · ${proxy.ipFamily.label}")
                                proxy.maskedIp?.let { append(" ($it)") }
                            }
                        }
                        clientEgress?.hasDualExitIps == true -> {
                            val direct = clientEgress.directNetwork!!
                            val proxy = clientEgress.proxyNetwork!!
                            append("真实出口：${direct.carrierDisplayName} · ${direct.ipFamily.label}")
                            direct.maskedIp?.let { append(" ($it)") }
                            append("\n代理出口：${proxy.carrierDisplayName} · ${proxy.ipFamily.label}")
                            proxy.maskedIp?.let { append(" ($it)") }
                        }
                        else -> {
                            val net = clientEgress?.directNetwork ?: clientNetwork
                            if (net != null) {
                                append("本机出口：${net.carrierDisplayName} · ${net.ipFamily.label}")
                                net.maskedIp?.let { append(" ($it)") }
                            }
                        }
                    }
                    append(" · 生成报告已自动隐藏本机 IP")
                }
                Text(
                    text = clientText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Markdown preview & 1-click Copy / Insert card with style switcher (`回帖精简卡` / `完整测机表格`).
 */
@Composable
internal fun NetBenchMarkdownReportCard(
    markdown: String,
    selectedStyle: NetBenchMarkdownStyle,
    maskTargetIp: Boolean,
    includeClientNetwork: Boolean = true,
    onSelectStyle: (NetBenchMarkdownStyle) -> Unit,
    onToggleMaskTargetIp: (Boolean) -> Unit,
    onToggleIncludeClientNetwork: (Boolean) -> Unit = {},
    onCopyMarkdown: () -> Unit,
    onInsertMarkdown: ((String) -> Unit)? = null,
    insertActionLabel: String = "插入回帖/发帖",
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (markdown.isBlank()) return
    var showRawSource by rememberSaveable { mutableStateOf(false) }
    val ext = NodePulseThemeTokens.extendedColors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard()
            .padding(if (compact) 12.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PulseSectionHeader(
            title = "NodeSeek 测机 Markdown 报告",
            subtitle = "已自动隐藏本机 IP，可直接发帖/回帖",
            icon = Icons.Rounded.Code,
            trailing = {
                NetBenchSelectableChip(
                    label = if (showRawSource) "渲染" else "源码",
                    selected = showRawSource,
                    icon = if (showRawSource) Icons.Rounded.Visibility else Icons.Rounded.Code,
                    onClick = { showRawSource = !showRawSource },
                )
            },
        )

        SortSegmentedSwitcher(
            options = remember {
                listOf(
                    SortSegmentItem(NetBenchMarkdownStyle.REPLY_CARD, NetBenchMarkdownStyle.REPLY_CARD.label),
                    SortSegmentItem(NetBenchMarkdownStyle.FULL_TABLE, NetBenchMarkdownStyle.FULL_TABLE.label),
                )
            },
            selectedValue = selectedStyle,
            onSelect = onSelectStyle,
            modifier = Modifier.fillMaxWidth(),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PulseArchitecturalShapes.MediumBadge)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "隐藏目标 IP/端口",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "自动打码目标小鸡 IP 末段并隐藏测速端口",
                        style = NodePulseThemeTokens.extraTypography.microLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = maskTargetIp,
                    onCheckedChange = onToggleMaskTargetIp,
                )
            }

            PulseGradientDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "包含本机网络环境",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "显示本机运营商与协议栈（始终隐藏本机 IP）",
                        style = NodePulseThemeTokens.extraTypography.microLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = includeClientNetwork,
                    onCheckedChange = onToggleIncludeClientNetwork,
                )
            }
        }

        if (showRawSource) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PulseArchitecturalShapes.CodeBlock)
                    .background(ext.codeSurface)
                    .border(1.dp, ext.codeBorder, PulseArchitecturalShapes.CodeBlock)
                    .padding(12.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = markdown,
                        style = NodePulseThemeTokens.extraTypography.codeBody,
                        color = ext.onCodeSurface,
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PulseArchitecturalShapes.MediumBadge)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerLowest.copy(
                            alpha = if (NodePulseThemeTokens.isDark) 0.55f else 0.90f,
                        ),
                    )
                    .border(
                        width = 0.8.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                        shape = PulseArchitecturalShapes.MediumBadge,
                    )
                    .padding(12.dp),
            ) {
                MarkdownRenderer(
                    markdown = markdown,
                    bodyStyle = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Action Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(PulseArchitecturalShapes.MediumBadge)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .pulseBouncyClickable(onClick = onCopyMarkdown)
                    .padding(vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "复制 Markdown",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            if (onInsertMarkdown != null) {
                Row(
                    modifier = Modifier
                        .weight(1.15f)
                        .clip(PulseArchitecturalShapes.MediumBadge)
                        .background(MaterialTheme.colorScheme.primary)
                        .pulseBouncyClickable(onClick = { onInsertMarkdown(markdown) })
                        .padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = insertActionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

/**
 * Informational/warning callout box (e.g., ping subprocess network binding fallback notice).
 */
@Composable
internal fun NetBenchWarningCallout(
    message: String,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(PulseArchitecturalShapes.SmallChip)
            .background(ext.drumstickContainer.copy(alpha = if (isDark) 0.28f else 0.55f))
            .border(
                width = 0.8.dp,
                color = ext.onDrumstickContainer.copy(alpha = if (isDark) 0.42f else 0.30f),
                shape = PulseArchitecturalShapes.SmallChip,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = ext.onDrumstickContainer,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Friendly error diagnostic callout box.
 */
@Composable
internal fun NetBenchErrorCallout(
    message: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(PulseArchitecturalShapes.SmallChip)
            .background(MaterialTheme.colorScheme.errorContainer)
            .border(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                shape = PulseArchitecturalShapes.SmallChip,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
