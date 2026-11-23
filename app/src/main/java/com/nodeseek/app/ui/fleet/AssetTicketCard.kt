package com.nodeseek.app.ui.fleet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeseek.app.domain.fleet.AssetExpiryUrgency
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.ServerIdleAssessment
import com.nodeseek.app.domain.fleet.ServerTrafficForecast
import com.nodeseek.app.domain.fleet.UpcomingRenewalItem
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ── Ticket Visual State & Pure Formatting Helpers ─────────────────────────────

internal enum class AssetTicketVisualTone {
    CRITICAL_OR_EXPIRED,
    RENEWAL_DUE,
    IDLE,
    NORMAL,
    UNKNOWN,
}

internal enum class TimelineMarkerTone {
    ERROR,
    GOLD,
    PRIMARY,
}

internal data class RenewalTimelineMarker(
    val assetId: String,
    val daysRemaining: Long,
    val fraction: Float,
    val tone: TimelineMarkerTone,
)

internal data class RenewalTimelineSummary(
    val totalAssetCount: Int,
    val due30dTotalCny: BigDecimal,
    val due30dCount: Int,
    val markers: List<RenewalTimelineMarker>,
)

private val SECONDARY_TAG_KEYWORDS = setOf(
    "kvm", "ovz", "openvz", "lxc", "nat", "ipv6", "v6", "大盘鸡", "存储",
)

internal fun resolveAssetTicketTone(
    renewalItem: UpcomingRenewalItem?,
    isIdle: Boolean,
): AssetTicketVisualTone {
    val urgency = renewalItem?.urgency
    return when {
        urgency == AssetExpiryUrgency.EXPIRED || urgency == AssetExpiryUrgency.CRITICAL ->
            AssetTicketVisualTone.CRITICAL_OR_EXPIRED
        renewalItem?.isRenewalDue == true ->
            AssetTicketVisualTone.RENEWAL_DUE
        isIdle ->
            AssetTicketVisualTone.IDLE
        renewalItem == null || urgency == AssetExpiryUrgency.UNKNOWN ->
            AssetTicketVisualTone.UNKNOWN
        else ->
            AssetTicketVisualTone.NORMAL
    }
}

internal fun resolveCycleTotalDays(
    cyclePreset: BillingCyclePreset,
    customCycleDays: Int?,
): Float = when (cyclePreset) {
    BillingCyclePreset.MONTHLY -> 30f
    BillingCyclePreset.QUARTERLY -> 90f
    BillingCyclePreset.SEMI_ANNUAL -> 180f
    BillingCyclePreset.ANNUAL,
    BillingCyclePreset.CALENDAR_NATURAL,
    -> 365f
    BillingCyclePreset.BIENNIAL -> 730f
    BillingCyclePreset.TRIENNIAL -> 1095f
    BillingCyclePreset.CUSTOM -> (customCycleDays ?: 365).coerceAtLeast(1).toFloat()
}

internal fun computeCycleRemainingFraction(
    asset: VpsAssetRecord,
    renewalItem: UpcomingRenewalItem?,
): Float {
    if (renewalItem == null || renewalItem.urgency == AssetExpiryUrgency.UNKNOWN) return 0f
    if (renewalItem.daysRemaining <= 0L) return 0f
    val totalDays = resolveCycleTotalDays(asset.cyclePreset, asset.customCycleDays)
    return (renewalItem.daysRemaining.toFloat() / totalDays).coerceIn(0.015f, 1f)
}

internal fun formatExpiryMonthDay(expiryDateIso: String): String {
    val trimmed = expiryDateIso.trim()
    if (trimmed.isEmpty()) return "未设置"
    val parts = trimmed.split('-')
    if (parts.size == 3 && parts[1].length == 2 && parts[2].length == 2) {
        return "${parts[1]}-${parts[2]}"
    }
    return trimmed
}

internal fun formatCycleSuffix(
    cyclePreset: BillingCyclePreset,
    customCycleDays: Int?,
): String = when (cyclePreset) {
    BillingCyclePreset.MONTHLY -> "/月"
    BillingCyclePreset.QUARTERLY -> "/季"
    BillingCyclePreset.SEMI_ANNUAL -> "/半年"
    BillingCyclePreset.ANNUAL,
    BillingCyclePreset.CALENDAR_NATURAL,
    -> "/年"
    BillingCyclePreset.BIENNIAL -> "/2年"
    BillingCyclePreset.TRIENNIAL -> "/3年"
    BillingCyclePreset.CUSTOM -> "/${(customCycleDays ?: 365).coerceAtLeast(1)}天"
}

internal fun formatAssetPriceText(asset: VpsAssetRecord): String {
    val amount = asset.renewalAmount.trim().ifEmpty { "0" }
    return when (asset.currency) {
        ValuationCurrency.CNY -> "¥$amount"
        ValuationCurrency.JPY -> "¥$amount JPY"
        ValuationCurrency.CHF -> "CHF $amount"
        else -> "${asset.currency.symbol}$amount"
    }
}

internal fun formatCompactCny(amount: BigDecimal): String {
    val safe = if (amount < BigDecimal.ZERO) BigDecimal.ZERO else amount
    return if (safe >= BigDecimal("1000")) {
        safe.setScale(0, RoundingMode.HALF_UP).toPlainString()
    } else {
        val scaled = safe.setScale(1, RoundingMode.HALF_UP)
        val plain = scaled.toPlainString()
        if (plain.endsWith(".0")) plain.dropLast(2) else plain
    }
}

internal fun formatGroupedCny(amount: BigDecimal): String {
    val safe = if (amount < BigDecimal.ZERO) BigDecimal.ZERO else amount
    return if (safe >= BigDecimal("100")) {
        String.format(Locale.US, "%,.0f", safe.setScale(0, RoundingMode.HALF_UP).toDouble())
    } else {
        formatCompactCny(safe)
    }
}

internal fun resolveTrafficWarningLine(forecast: ServerTrafficForecast?): String? {
    if (forecast == null || forecast.limitBytes == null) return null
    val pct = ((forecast.usageRatio ?: 0.0) * 100.0).roundToInt()
    return when {
        forecast.isExhausted -> "本周期流量已用完（每月 ${forecast.anchorResetDay} 号重置）"
        forecast.willExhaustBeforeReset || forecast.isAtOrAboveAlertThreshold -> {
            val detail = forecast.exhaustionForecastText?.takeIf { it.isNotBlank() }
            if (detail != null) {
                "流量 ${pct}% · $detail"
            } else {
                "流量已达 ${pct}% 预警（每月 ${forecast.anchorResetDay} 号重置）"
            }
        }
        else -> null
    }
}

internal fun buildRenewalTimelineSummary(
    totalAssetCount: Int,
    upcomingRenewals: List<UpcomingRenewalItem>,
): RenewalTimelineSummary {
    var due30dTotal = BigDecimal.ZERO
    var due30dCount = 0
    val markers = mutableListOf<RenewalTimelineMarker>()

    for (item in upcomingRenewals) {
        if (item.urgency == AssetExpiryUrgency.UNKNOWN) continue
        if (item.daysRemaining <= 30L) {
            due30dTotal = due30dTotal.add(item.renewalCny)
            due30dCount++
        }
        if (item.daysRemaining <= 90L) {
            val fraction = (item.daysRemaining.coerceAtLeast(0L).toFloat() / 90f)
                .coerceIn(0.025f, 0.975f)
            val tone = when {
                item.daysRemaining <= 7L ||
                    item.urgency == AssetExpiryUrgency.EXPIRED ||
                    item.urgency == AssetExpiryUrgency.CRITICAL -> TimelineMarkerTone.ERROR
                item.daysRemaining <= 30L || item.isRenewalDue -> TimelineMarkerTone.GOLD
                else -> TimelineMarkerTone.PRIMARY
            }
            markers += RenewalTimelineMarker(
                assetId = item.asset.id,
                daysRemaining = item.daysRemaining,
                fraction = fraction,
                tone = tone,
            )
        }
    }

    // Draw less urgent markers first so error/gold markers remain clearly visible on top.
    val orderedMarkers = markers.sortedWith(
        compareByDescending<RenewalTimelineMarker> {
            when (it.tone) {
                TimelineMarkerTone.ERROR -> 0
                TimelineMarkerTone.GOLD -> 1
                TimelineMarkerTone.PRIMARY -> 2
            }
        }.thenByDescending { it.daysRemaining },
    )

    return RenewalTimelineSummary(
        totalAssetCount = totalAssetCount,
        due30dTotalCny = due30dTotal.setScale(2, RoundingMode.HALF_UP),
        due30dCount = due30dCount,
        markers = orderedMarkers,
    )
}

// ── Ticket-Stub Path & Custom Shape ───────────────────────────────────────────

private val TICKET_CORNER_RADIUS: Dp = 16.dp
private val TICKET_STUB_WIDTH: Dp = 78.dp
private val TICKET_NOTCH_RADIUS: Dp = 7.dp

internal fun buildTicketStubPath(
    size: Size,
    cornerRadiusPx: Float,
    stubWidthPx: Float,
    notchRadiusPx: Float,
): Path {
    val w = size.width
    val h = size.height
    val path = Path()
    if (w <= 0f || h <= 0f) return path

    val cr = cornerRadiusPx.coerceIn(0f, minOf(w, h) / 2f)
    val maxNotchForHeight = (h / 4f).coerceAtLeast(0f)
    val rawSeamX = (w - stubWidthPx).coerceIn(cr, (w - cr).coerceAtLeast(cr))
    val maxNotchLeft = (rawSeamX - cr).coerceAtLeast(0f)
    val maxNotchRight = (w - cr - rawSeamX).coerceAtLeast(0f)
    val notchR = notchRadiusPx.coerceIn(0f, minOf(maxNotchForHeight, maxNotchLeft, maxNotchRight))
    val seamX = rawSeamX

    path.moveTo(cr, 0f)

    // Top edge up to top notch
    if (notchR > 0.5f) {
        path.lineTo(seamX - notchR, 0f)
        // Top semicircular cutout curving downward into the ticket
        path.arcTo(
            rect = Rect(
                left = seamX - notchR,
                top = -notchR,
                right = seamX + notchR,
                bottom = notchR,
            ),
            startAngleDegrees = 180f,
            sweepAngleDegrees = -180f,
            forceMoveTo = false,
        )
    }

    // Top-right corner
    path.lineTo(w - cr, 0f)
    if (cr > 0f) {
        path.arcTo(
            rect = Rect(left = w - 2f * cr, top = 0f, right = w, bottom = 2f * cr),
            startAngleDegrees = 270f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false,
        )
    }

    // Right edge & bottom-right corner
    path.lineTo(w, h - cr)
    if (cr > 0f) {
        path.arcTo(
            rect = Rect(left = w - 2f * cr, top = h - 2f * cr, right = w, bottom = h),
            startAngleDegrees = 0f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false,
        )
    }

    // Bottom edge up to bottom notch
    if (notchR > 0.5f) {
        path.lineTo(seamX + notchR, h)
        // Bottom semicircular cutout curving upward into the ticket
        path.arcTo(
            rect = Rect(
                left = seamX - notchR,
                top = h - notchR,
                right = seamX + notchR,
                bottom = h + notchR,
            ),
            startAngleDegrees = 0f,
            sweepAngleDegrees = -180f,
            forceMoveTo = false,
        )
    }

    // Bottom-left corner
    path.lineTo(cr, h)
    if (cr > 0f) {
        path.arcTo(
            rect = Rect(left = 0f, top = h - 2f * cr, right = 2f * cr, bottom = h),
            startAngleDegrees = 90f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false,
        )
    }

    // Left edge & top-left corner
    path.lineTo(0f, cr)
    if (cr > 0f) {
        path.arcTo(
            rect = Rect(left = 0f, top = 0f, right = 2f * cr, bottom = 2f * cr),
            startAngleDegrees = 180f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false,
        )
    }

    path.close()
    return path
}

internal class TicketStubShape(
    private val cornerRadius: Dp = TICKET_CORNER_RADIUS,
    private val stubWidth: Dp = TICKET_STUB_WIDTH,
    private val notchRadius: Dp = TICKET_NOTCH_RADIUS,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = with(density) {
        Outline.Generic(
            buildTicketStubPath(
                size = size,
                cornerRadiusPx = cornerRadius.toPx(),
                stubWidthPx = stubWidth.toPx(),
                notchRadiusPx = notchRadius.toPx(),
            ),
        )
    }
}

// ── 90-Day Renewal Timeline Strip ─────────────────────────────────────────────

@Composable
internal fun AssetRenewalTimelineStrip(
    totalAssetCount: Int,
    upcomingRenewals: List<UpcomingRenewalItem>,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val summary = remember(totalAssetCount, upcomingRenewals) {
        buildRenewalTimelineSummary(totalAssetCount, upcomingRenewals)
    }

    val trackColor = if (isDark) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val errorColor = MaterialTheme.colorScheme.error
    val goldColor = if (isDark) {
        ext.onDrumstickContainer
    } else {
        blendColor(ext.onDrumstickContainer, ext.drumstickGoldEnd, 0.50f)
    }
    val primaryColor = MaterialTheme.colorScheme.primary
    val todayMarkerColor = MaterialTheme.colorScheme.onSurface
    val cardSurface = ext.cardSurface
    val mutedColor = MaterialTheme.colorScheme.outline

    val due30dAmountText = remember(summary.due30dTotalCny) {
        "¥${formatGroupedCny(summary.due30dTotalCny)}"
    }
    val hasDue30d = summary.due30dTotalCny > BigDecimal.ZERO

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = ext.cardSurface,
                borderColor = ext.cardBorder,
                elevation = PulseElevation.Subtle,
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        ),
                    ) {
                        append("未来 90 天续费")
                    }
                    withStyle(
                        SpanStyle(
                            color = mutedColor,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                        ),
                    ) {
                        append(" · 共 ${summary.totalAssetCount} 台")
                    }
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(end = 8.dp),
            )
            Text(
                text = buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            color = mutedColor,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                        ),
                    ) {
                        append("30 天内需付 ")
                    }
                    withStyle(
                        SpanStyle(
                            color = if (hasDue30d) errorColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp,
                        ),
                    ) {
                        append(due30dAmountText)
                    }
                },
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                maxLines = 1,
                softWrap = false,
            )
        }

        // 3dp axis with today tick + urgency-colored renewal markers
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp),
        ) {
            val barHeight = 3.dp.toPx()
            val barY = (size.height - barHeight) / 2f
            val corner = CornerRadius(barHeight / 2f, barHeight / 2f)
            val zone1End = size.width / 3f
            val zone2End = size.width * 2f / 3f

            // Base 60-90d full rounded track
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, barY),
                size = Size(size.width, barHeight),
                cornerRadius = corner,
            )
            // 30-60d subtle gold segment
            drawRect(
                color = blendColor(trackColor, goldColor, if (isDark) 0.35f else 0.32f),
                topLeft = Offset(zone1End, barY),
                size = Size(zone2End - zone1End, barHeight),
            )
            // 0-30d subtle error segment (left rounded)
            drawRoundRect(
                color = blendColor(trackColor, errorColor, if (isDark) 0.48f else 0.45f),
                topLeft = Offset(0f, barY),
                size = Size(zone1End, barHeight),
                cornerRadius = corner,
            )
            // Square off right side of zone 1 seamlessly
            if (zone1End > barHeight) {
                drawRect(
                    color = blendColor(trackColor, errorColor, if (isDark) 0.48f else 0.45f),
                    topLeft = Offset(zone1End - barHeight, barY),
                    size = Size(barHeight, barHeight),
                )
            }

            // Today vertical tick at x = 0
            val nowWidth = 2.dp.toPx()
            val nowHeight = 16.dp.toPx().coerceAtMost(size.height)
            drawRoundRect(
                color = todayMarkerColor,
                topLeft = Offset(0f, (size.height - nowHeight) / 2f),
                size = Size(nowWidth, nowHeight),
                cornerRadius = CornerRadius(nowWidth / 2f, nowWidth / 2f),
            )

            // Renewal dots with card-surface halo ring
            val outerRadius = 6.5.dp.toPx()
            val innerRadius = 4.dp.toPx()
            val centerY = size.height / 2f
            val minX = nowWidth + outerRadius * 0.7f
            val maxX = (size.width - outerRadius).coerceAtLeast(minX)

            summary.markers.forEach { marker ->
                val cx = (marker.fraction * size.width).coerceIn(minX, maxX)
                val dotColor = when (marker.tone) {
                    TimelineMarkerTone.ERROR -> errorColor
                    TimelineMarkerTone.GOLD -> goldColor
                    TimelineMarkerTone.PRIMARY -> primaryColor
                }
                drawCircle(
                    color = cardSurface,
                    radius = outerRadius,
                    center = Offset(cx, centerY),
                )
                drawCircle(
                    color = dotColor,
                    radius = innerRadius,
                    center = Offset(cx, centerY),
                )
            }
        }

        // Exact fractional tick labels: 今天 (0%), 30天 (33.3%), 60天 (66.7%), 90天 (100%)
        TimelineTickLabelsRow(
            color = mutedColor,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TimelineTickLabelsRow(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val labelStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 9.sp,
        lineHeight = 11.sp,
        color = color,
    ).withTabularNumbers()

    Layout(
        modifier = modifier,
        content = {
            Text(text = "今天", style = labelStyle, maxLines = 1, softWrap = false)
            Text(text = "30天", style = labelStyle, maxLines = 1, softWrap = false)
            Text(text = "60天", style = labelStyle, maxLines = 1, softWrap = false)
            Text(text = "90天", style = labelStyle, maxLines = 1, softWrap = false)
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val totalWidth = constraints.maxWidth
        val totalHeight = placeables.maxOfOrNull { it.height } ?: 0

        layout(totalWidth, totalHeight) {
            if (placeables.size == 4) {
                placeables[0].placeRelative(x = 0, y = 0)
                val x30 = ((totalWidth / 3f) - placeables[1].width / 2f)
                    .roundToInt()
                    .coerceIn(0, (totalWidth - placeables[1].width).coerceAtLeast(0))
                val x60 = ((totalWidth * 2f / 3f) - placeables[2].width / 2f)
                    .roundToInt()
                    .coerceIn(0, (totalWidth - placeables[2].width).coerceAtLeast(0))
                val x90 = (totalWidth - placeables[3].width).coerceAtLeast(0)
                placeables[1].placeRelative(x = x30, y = 0)
                placeables[2].placeRelative(x = x60, y = 0)
                placeables[3].placeRelative(x = x90, y = 0)
            }
        }
    }
}

// ── Add Asset Floating Action Pill ────────────────────────────────────────────

@Composable
internal fun AddAssetFloatingPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .shadow(
                elevation = if (isDark) 8.dp else 6.dp,
                shape = shape,
                clip = false,
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.36f else 0.18f),
                shape = shape,
            )
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Button,
                onClickLabel = "录入小鸡",
                onClick = onClick,
            )
            .height(46.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(19.dp),
        )
        Text(
            text = "录入小鸡",
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            ),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            softWrap = false,
        )
    }
}

// ── Ticket-Stub VPS Asset Card ────────────────────────────────────────────────

@Composable
internal fun AssetTicketCard(
    asset: VpsAssetRecord,
    renewalItem: UpcomingRenewalItem?,
    linkedProbe: ProbeServerSnapshot?,
    trafficForecast: ServerTrafficForecast?,
    idleAssessment: ServerIdleAssessment?,
    onEdit: () -> Unit,
    onMarkRenewed: () -> Unit,
    onDelete: () -> Unit,
    onBindProbe: () -> Unit,
    onOpenSalePost: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val isInspection = LocalInspectionMode.current

    val isIdle = idleAssessment?.isIdle == true
    val tone = remember(renewalItem?.urgency, renewalItem?.isRenewalDue, isIdle) {
        resolveAssetTicketTone(renewalItem = renewalItem, isIdle = isIdle)
    }

    val errorColor = MaterialTheme.colorScheme.error
    val goldColor = if (isDark) {
        ext.onDrumstickContainer
    } else {
        blendColor(ext.onDrumstickContainer, ext.drumstickGoldEnd, 0.50f)
    }
    val primaryColor = MaterialTheme.colorScheme.primary
    val mutedColor = MaterialTheme.colorScheme.outline
    val cardSurface = ext.cardSurface

    val accentColor = when (tone) {
        AssetTicketVisualTone.CRITICAL_OR_EXPIRED -> errorColor
        AssetTicketVisualTone.RENEWAL_DUE,
        AssetTicketVisualTone.IDLE,
        -> goldColor
        AssetTicketVisualTone.NORMAL -> primaryColor
        AssetTicketVisualTone.UNKNOWN -> mutedColor
    }

    val borderColor = when (tone) {
        AssetTicketVisualTone.CRITICAL_OR_EXPIRED ->
            blendColor(ext.cardBorder, errorColor, if (isDark) 0.44f else 0.40f)
        AssetTicketVisualTone.RENEWAL_DUE ->
            blendColor(ext.cardBorder, goldColor, if (isDark) 0.42f else 0.38f)
        AssetTicketVisualTone.IDLE ->
            blendColor(ext.cardBorder, goldColor, if (isDark) 0.32f else 0.28f)
        AssetTicketVisualTone.NORMAL,
        AssetTicketVisualTone.UNKNOWN,
        -> ext.cardBorder
    }

    val perforationColor = when (tone) {
        AssetTicketVisualTone.CRITICAL_OR_EXPIRED -> errorColor.copy(alpha = 0.55f)
        AssetTicketVisualTone.RENEWAL_DUE -> goldColor.copy(alpha = 0.55f)
        AssetTicketVisualTone.IDLE -> goldColor.copy(alpha = 0.45f)
        AssetTicketVisualTone.NORMAL,
        AssetTicketVisualTone.UNKNOWN,
        -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    }

    val mainTopColor = blendColor(
        source = cardSurface,
        target = accentColor,
        fraction = if (isDark) 0.08f else 0.055f,
    )
    val stubSurfaceColor = blendColor(
        source = cardSurface,
        target = accentColor,
        fraction = if (isDark) 0.14f else 0.11f,
    )
    val trackColor = if (isDark) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val ringTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.10f else 0.09f)
    val lightShadowColor = ext.softShadowColor.copy(alpha = 0.06f)

    val ticketShape = remember { TicketStubShape() }
    var menuExpanded by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(false) }

    val displayTags = remember(asset.tags, asset.routeTag) { asset.resolvedTags }
    val trafficWarningLine = remember(trafficForecast) { resolveTrafficWarningLine(trafficForecast) }
    val hasExpandableDetails = linkedProbe != null ||
        asset.trafficResetDay != null ||
        asset.autoRenewNote.isNotBlank() ||
        asset.notes.isNotBlank()

    // Live animated probe metrics when bound
    val probeDotColor by animateColorAsState(
        targetValue = when {
            linkedProbe == null -> mutedColor
            linkedProbe.isOnline -> primaryColor
            else -> errorColor
        },
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "ticketProbeDotColor",
    )
    val animatedCpu by animateFloatAsState(
        targetValue = if (linkedProbe?.isOnline == true) {
            linkedProbe.cpuPercent.coerceIn(0.0, 100.0).toFloat()
        } else {
            0f
        },
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "ticketProbeCpu",
    )
    val animatedRam by animateFloatAsState(
        targetValue = if (linkedProbe?.isOnline == true) {
            linkedProbe.memUsageRatio.coerceIn(0f, 1f) * 100f
        } else {
            0f
        },
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "ticketProbeRam",
    )

    val targetRingFraction = remember(asset.cyclePreset, asset.customCycleDays, renewalItem) {
        computeCycleRemainingFraction(asset, renewalItem)
    }
    val animatedRingFraction by animateFloatAsState(
        targetValue = targetRingFraction,
        animationSpec = tween(durationMillis = if (isInspection) 0 else 420, easing = FastOutSlowInEasing),
        label = "ticketRingFraction",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 96.dp)
            .height(IntrinsicSize.Min)
            .animateContentSize(animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing))
            .drawBehind {
                val cornerPx = TICKET_CORNER_RADIUS.toPx()
                val stubPx = TICKET_STUB_WIDTH.toPx()
                val notchPx = TICKET_NOTCH_RADIUS.toPx()
                val path = buildTicketStubPath(
                    size = size,
                    cornerRadiusPx = cornerPx,
                    stubWidthPx = stubPx,
                    notchRadiusPx = notchPx,
                )

                if (!isDark) {
                    translate(top = 1.5.dp.toPx()) {
                        drawPath(path = path, color = lightShadowColor)
                    }
                }

                clipPath(path) {
                    val seamX = (size.width - stubPx).coerceIn(
                        cornerPx + notchPx,
                        (size.width - cornerPx - notchPx).coerceAtLeast(cornerPx + notchPx),
                    )
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to mainTopColor,
                            0.60f to cardSurface,
                            1f to cardSurface,
                        ),
                        topLeft = Offset.Zero,
                        size = Size(seamX, size.height),
                    )
                    drawRect(
                        color = stubSurfaceColor,
                        topLeft = Offset(seamX, 0f),
                        size = Size((size.width - seamX).coerceAtLeast(0f), size.height),
                    )
                    val perfTop = notchPx + 2.dp.toPx()
                    val perfBottom = size.height - notchPx - 2.dp.toPx()
                    if (perfBottom > perfTop) {
                        drawLine(
                            color = perforationColor,
                            start = Offset(seamX, perfTop),
                            end = Offset(seamX, perfBottom),
                            strokeWidth = 1.5.dp.toPx(),
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(3.5.dp.toPx(), 3.dp.toPx()),
                                0f,
                            ),
                        )
                    }
                }

                drawPath(
                    path = path,
                    color = borderColor,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            .clip(ticketShape)
            .then(
                if (hasExpandableDetails) {
                    Modifier.clickable(
                        onClickLabel = if (detailsExpanded) "收起详情" else "展开探针与备注详情",
                        onClick = { detailsExpanded = !detailsExpanded },
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        // ── Left Main Body ────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 13.dp, top = 10.dp, end = 10.dp, bottom = 9.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Row 1: Probe status dot + Nickname + Tags (max 2 + "+N") + AutoRenew icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ProbeBindingDot(
                        isBound = linkedProbe != null,
                        isOnline = linkedProbe?.isOnline == true,
                        dotColor = probeDotColor,
                        mutedColor = mutedColor,
                    )

                    Text(
                        text = asset.nickname,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    // Tag pills: optional gold "闲置" badge + up to 2 tags (+N overflow)
                    val maxRegularTags = if (isIdle) 1 else 2
                    val visibleTags = displayTags.take(maxRegularTags)
                    val overflowCount = (displayTags.size - visibleTags.size).coerceAtLeast(0)

                    if (isIdle) {
                        TicketTagPill(
                            text = "闲置",
                            containerColor = ext.drumstickContainer,
                            contentColor = ext.onDrumstickContainer,
                        )
                    }
                    visibleTags.forEachIndexed { index, tag ->
                        val useTertiary = index == 1 ||
                            tag.lowercase(Locale.ROOT) in SECONDARY_TAG_KEYWORDS
                        TicketTagPill(
                            text = tag,
                            containerColor = if (useTertiary) {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                            },
                            contentColor = if (useTertiary) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                        )
                    }
                    if (overflowCount > 0) {
                        TicketTagPill(
                            text = "+$overflowCount",
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (asset.autoRenew) {
                        Icon(
                            imageVector = Icons.Rounded.Autorenew,
                            contentDescription = "自动续费",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }

                // Row 2: provider · region · specs (specs monospace)
                val providerRegionPrefix = remember(asset.providerName, asset.region) {
                    buildList {
                        if (asset.providerName.isNotBlank()) add(asset.providerName.trim())
                        if (asset.region.isNotBlank()) add(asset.region.trim())
                    }.joinToString(" · ")
                }
                val compactSpecs = remember(asset.specsSummary) {
                    asset.specsSummary.trim().replace(" / ", "/")
                }
                if (providerRegionPrefix.isNotBlank() || compactSpecs.isNotBlank()) {
                    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
                    Text(
                        text = buildAnnotatedString {
                            if (providerRegionPrefix.isNotBlank()) {
                                withStyle(
                                    SpanStyle(
                                        color = mutedColor,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Normal,
                                    ),
                                ) {
                                    append(providerRegionPrefix)
                                }
                            }
                            if (providerRegionPrefix.isNotBlank() && compactSpecs.isNotBlank()) {
                                withStyle(
                                    SpanStyle(
                                        color = mutedColor,
                                        fontSize = 10.5.sp,
                                    ),
                                ) {
                                    append(" · ")
                                }
                            }
                            if (compactSpecs.isNotBlank()) {
                                withStyle(
                                    SpanStyle(
                                        color = onSurfaceVariant,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace,
                                        letterSpacing = (-0.2).sp,
                                    ),
                                ) {
                                    append(compactSpecs)
                                }
                            }
                        },
                        style = MaterialTheme.typography.labelSmall.copy(lineHeight = 14.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(7.dp))

            // Row 3: Price + CNY subtext on left, ⋮ menu + 1 contextual action pill on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Price block
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    val priceText = remember(asset.currency, asset.renewalAmount) {
                        formatAssetPriceText(asset)
                    }
                    val cycleSuffix = remember(asset.cyclePreset, asset.customCycleDays) {
                        formatCycleSuffix(asset.cyclePreset, asset.customCycleDays)
                    }
                    Row(
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            text = priceText,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                lineHeight = 16.sp,
                                letterSpacing = (-0.3).sp,
                            ).withTabularNumbers(),
                            color = primaryColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = cycleSuffix,
                            style = TextStyle(
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Medium,
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                            ),
                            color = mutedColor,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(start = 1.dp, bottom = 0.5.dp),
                        )
                    }

                    val cnySubtext = remember(
                        renewalItem?.renewalCny,
                        renewalItem?.monthlyCostCny,
                        asset.autoRenew,
                        asset.autoRenewNote,
                    ) {
                        if (renewalItem != null) {
                            val base = "≈¥${formatCompactCny(renewalItem.renewalCny)}"
                            val tail = when {
                                asset.autoRenew && asset.autoRenewNote.isNotBlank() -> asset.autoRenewNote.trim()
                                asset.autoRenew -> "自动扣款"
                                else -> "月均¥${formatCompactCny(renewalItem.monthlyCostCny)}"
                            }
                            "$base · $tail"
                        } else if (asset.autoRenew) {
                            asset.autoRenewNote.trim().ifBlank { "自动扣款" }
                        } else {
                            ""
                        }
                    }
                    if (cnySubtext.isNotEmpty()) {
                        Text(
                            text = cnySubtext,
                            style = TextStyle(
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Medium,
                                fontSize = 9.5.sp,
                                lineHeight = 12.sp,
                            ).withTabularNumbers(),
                            color = mutedColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Right actions: 28dp "⋮" menu button + ONE contextual primary pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(trackColor)
                                .pulseBouncyClickable(
                                    pressedScale = NodePulseMotion.chipPressScale,
                                    role = Role.Button,
                                    onClickLabel = "更多操作",
                                    onClick = { menuExpanded = true },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "更多操作",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }

                        AssetTicketOverflowMenu(
                            expanded = menuExpanded,
                            onDismiss = { menuExpanded = false },
                            asset = asset,
                            renewalItem = renewalItem,
                            linkedProbe = linkedProbe,
                            animatedCpu = animatedCpu,
                            animatedRam = animatedRam,
                            tone = tone,
                            onEdit = onEdit,
                            onBindProbe = onBindProbe,
                            onMarkRenewed = onMarkRenewed,
                            onOpenSalePost = onOpenSalePost,
                            onOpenUrl = onOpenUrl,
                            onDelete = onDelete,
                        )
                    }

                    TicketPrimaryActionPill(
                        tone = tone,
                        onMarkRenewed = onMarkRenewed,
                        onOpenSalePost = onOpenSalePost,
                    )
                }
            }

            // Conditional traffic overrun / 80% warning line (shown ONLY when it's a warning)
            if (trafficWarningLine != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = errorColor,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = trafficWarningLine,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        ).withTabularNumbers(),
                        color = errorColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Tap-to-expand compact detail strip (live probe CPU/RAM, reset day, notes)
            AnimatedVisibility(visible = detailsExpanded && hasExpandableDetails) {
                AssetTicketExpandedDetails(
                    asset = asset,
                    linkedProbe = linkedProbe,
                    animatedCpu = animatedCpu,
                    animatedRam = animatedRam,
                    onBindProbe = onBindProbe,
                )
            }
        }

        // ── Right Perforated Stub (78dp) ──────────────────────────────────────
        AssetTicketStub(
            asset = asset,
            renewalItem = renewalItem,
            tone = tone,
            ringFraction = animatedRingFraction,
            accentColor = accentColor,
            trackColor = ringTrackColor,
            mutedColor = mutedColor,
            modifier = Modifier
                .width(TICKET_STUB_WIDTH)
                .fillMaxHeight()
                .padding(horizontal = 6.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ProbeBindingDot(
    isBound: Boolean,
    isOnline: Boolean,
    dotColor: Color,
    mutedColor: Color,
) {
    val desc = when {
        !isBound -> "未绑定探针"
        isOnline -> "探针在线"
        else -> "探针离线"
    }
    Canvas(
        modifier = Modifier
            .size(11.dp)
            .semantics { contentDescription = desc },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        if (isBound) {
            // Soft outer glow halo + crisp 7dp core dot
            drawCircle(
                color = dotColor.copy(alpha = 0.22f),
                radius = size.minDimension / 2f,
                center = center,
            )
            drawCircle(
                color = dotColor,
                radius = 3.5.dp.toPx(),
                center = center,
            )
        } else {
            // Dashed outline circle when unbound
            drawCircle(
                color = mutedColor,
                radius = 3.25.dp.toPx(),
                center = center,
                style = Stroke(
                    width = 1.4.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(2.2.dp.toPx(), 1.8.dp.toPx()),
                        0f,
                    ),
                ),
            )
        }
    }
}

@Composable
private fun TicketTagPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Box(
        modifier = Modifier
            .widthIn(max = 84.dp)
            .clip(CircleShape)
            .background(containerColor)
            .padding(horizontal = 6.dp, vertical = 1.5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 9.5.sp,
                lineHeight = 12.sp,
            ),
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TicketPrimaryActionPill(
    tone: AssetTicketVisualTone,
    onMarkRenewed: () -> Unit,
    onOpenSalePost: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark

    val goldBg = if (isDark) {
        ext.onDrumstickContainer
    } else {
        blendColor(ext.onDrumstickContainer, ext.drumstickGoldEnd, 0.50f)
    }
    val goldFg = if (isDark) ext.drumstickContainer else ext.cardSurface

    val (label, icon, bg, fg, action) = when (tone) {
        AssetTicketVisualTone.RENEWAL_DUE -> PillActionSpec(
            label = "已续费",
            icon = Icons.Rounded.Check,
            containerColor = goldBg,
            contentColor = goldFg,
            onClick = onMarkRenewed,
        )
        AssetTicketVisualTone.IDLE -> PillActionSpec(
            label = "出掉回血",
            icon = Icons.Rounded.LocalOffer,
            containerColor = goldBg,
            contentColor = goldFg,
            onClick = onOpenSalePost,
        )
        AssetTicketVisualTone.CRITICAL_OR_EXPIRED -> PillActionSpec(
            label = "已续费",
            icon = Icons.Rounded.Check,
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
            onClick = onMarkRenewed,
        )
        AssetTicketVisualTone.NORMAL,
        AssetTicketVisualTone.UNKNOWN,
        -> PillActionSpec(
            label = "出机",
            icon = Icons.Rounded.LocalOffer,
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.16f else 0.13f),
            contentColor = MaterialTheme.colorScheme.primary,
            onClick = onOpenSalePost,
        )
    }

    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(CircleShape)
            .background(bg)
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Button,
                onClickLabel = label,
                onClick = action,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = label,
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            ),
            color = fg,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private data class PillActionSpec(
    val label: String,
    val icon: ImageVector,
    val containerColor: Color,
    val contentColor: Color,
    val onClick: () -> Unit,
)

@Composable
private fun AssetTicketStub(
    asset: VpsAssetRecord,
    renewalItem: UpcomingRenewalItem?,
    tone: AssetTicketVisualTone,
    ringFraction: Float,
    accentColor: Color,
    trackColor: Color,
    mutedColor: Color,
    modifier: Modifier = Modifier,
) {
    val urgency = renewalItem?.urgency

    val (bigNumberText, subLabelText) = remember(
        renewalItem?.daysRemaining,
        urgency,
        tone,
    ) {
        when {
            renewalItem == null || urgency == AssetExpiryUrgency.UNKNOWN -> "—" to "待补"
            urgency == AssetExpiryUrgency.EXPIRED -> {
                val overdue = abs(renewalItem.daysRemaining).coerceAtLeast(1L)
                "-$overdue" to "已过期"
            }
            renewalItem.daysRemaining == 0L -> "0" to "今日到期"
            tone == AssetTicketVisualTone.RENEWAL_DUE -> "${renewalItem.daysRemaining}" to "待续费"
            else -> "${renewalItem.daysRemaining}" to "天"
        }
    }

    val numberColor = when (tone) {
        AssetTicketVisualTone.NORMAL -> MaterialTheme.colorScheme.onSurface
        AssetTicketVisualTone.UNKNOWN -> mutedColor
        else -> accentColor
    }
    val subLabelColor = when {
        urgency == AssetExpiryUrgency.EXPIRED -> MaterialTheme.colorScheme.error
        tone == AssetTicketVisualTone.RENEWAL_DUE -> accentColor.copy(alpha = 0.90f)
        else -> mutedColor
    }
    val dateColor = when (tone) {
        AssetTicketVisualTone.CRITICAL_OR_EXPIRED -> MaterialTheme.colorScheme.error
        AssetTicketVisualTone.RENEWAL_DUE -> accentColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val numberFontSize = when {
        bigNumberText.length >= 4 -> 12.5.sp
        bigNumberText.length == 3 -> 14.5.sp
        else -> 16.5.sp
    }

    val expiryMmDd = remember(asset.expiryDateIso) { formatExpiryMonthDay(asset.expiryDateIso) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        // 50dp Countdown Ring
        Box(
            modifier = Modifier.size(50.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val strokePx = 4.dp.toPx()
                val inset = strokePx / 2f
                val arcSize = Size(size.width - strokePx, size.height - strokePx)
                val topLeft = Offset(inset, inset)

                // Background track ring
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx),
                )

                // Foreground remaining-cycle arc
                val sweep = (ringFraction.coerceIn(0f, 1f)) * 360f
                if (sweep > 0.5f) {
                    drawArc(
                        color = accentColor,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = bigNumberText,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = numberFontSize,
                        lineHeight = numberFontSize * 1.05f,
                        letterSpacing = (-0.5).sp,
                    ).withTabularNumbers(),
                    color = numberColor,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = subLabelText,
                    style = TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 8.5.sp,
                        lineHeight = 10.sp,
                    ),
                    color = subLabelColor,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        // Expiry date MM-dd
        Text(
            text = expiryMmDd,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.5.sp,
                lineHeight = 12.sp,
            ).withTabularNumbers(),
            color = dateColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun AssetTicketExpandedDetails(
    asset: VpsAssetRecord,
    linkedProbe: ProbeServerSnapshot?,
    animatedCpu: Float,
    animatedRam: Float,
    onBindProbe: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val infoParts = buildList {
        if (asset.trafficResetDay != null) add("每月 ${asset.trafficResetDay} 号重置流量")
        if (asset.autoRenewNote.isNotBlank()) add(asset.autoRenewNote.trim())
        if (asset.notes.isNotBlank()) add(asset.notes.trim())
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(0.75.dp, ext.cardBorder.copy(alpha = 0.72f), RoundedCornerShape(9.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (linkedProbe != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onBindProbe),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = linkedProbe.name,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 6.dp),
                )
                Text(
                    text = if (linkedProbe.isOnline) {
                        String.format(Locale.US, "CPU %.0f%% · RAM %d%%", animatedCpu, animatedRam.roundToInt())
                    } else {
                        "探针离线"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    ).withTabularNumbers(),
                    color = if (linkedProbe.isOnline) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        if (infoParts.isNotEmpty()) {
            Text(
                text = infoParts.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AssetTicketOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    asset: VpsAssetRecord,
    renewalItem: UpcomingRenewalItem?,
    linkedProbe: ProbeServerSnapshot?,
    animatedCpu: Float,
    animatedRam: Float,
    tone: AssetTicketVisualTone,
    onEdit: () -> Unit,
    onBindProbe: () -> Unit,
    onMarkRenewed: () -> Unit,
    onOpenSalePost: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val hasPanelUrl = asset.panelManageUrl.isNotBlank()
    val hasPurchaseUrl = asset.purchaseUrl.isNotBlank()
    val hasAnyUrl = hasPanelUrl || hasPurchaseUrl

    val headerLines = remember(
        linkedProbe?.name,
        linkedProbe?.isOnline,
        animatedCpu.roundToInt(),
        animatedRam.roundToInt(),
        asset.trafficResetDay,
        asset.autoRenewNote,
        renewalItem?.nextExpiryAfterRenewalIso,
        renewalItem?.isRenewalDue,
    ) {
        buildList {
            if (linkedProbe != null) {
                val telemetry = if (linkedProbe.isOnline) {
                    String.format(Locale.US, "CPU %.0f%% · RAM %d%%", animatedCpu, animatedRam.roundToInt())
                } else {
                    "离线"
                }
                add("探针：${linkedProbe.name} ($telemetry)")
            }
            if (asset.trafficResetDay != null) {
                add("流量重置：每月 ${asset.trafficResetDay} 号")
            }
            if (asset.autoRenewNote.isNotBlank()) {
                add("续费备注：${asset.autoRenewNote.trim()}")
            }
            if (renewalItem?.isRenewalDue == true && !renewalItem.nextExpiryAfterRenewalIso.isNullOrBlank()) {
                add("续费后顺延至：${renewalItem.nextExpiryAfterRenewalIso}")
            }
        }
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        if (headerLines.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .widthIn(min = 180.dp, max = 250.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                headerLines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
        }

        DropdownMenuItem(
            text = { Text("编辑") },
            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = {
                onDismiss()
                onEdit()
            },
        )
        DropdownMenuItem(
            text = { Text(if (linkedProbe != null) "更换探针" else "绑定探针") },
            leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = {
                onDismiss()
                onBindProbe()
            },
        )
        DropdownMenuItem(
            text = { Text("标记已续费") },
            leadingIcon = { Icon(Icons.Rounded.EventRepeat, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = {
                onDismiss()
                onMarkRenewed()
            },
        )
        if (tone == AssetTicketVisualTone.RENEWAL_DUE || tone == AssetTicketVisualTone.CRITICAL_OR_EXPIRED) {
            DropdownMenuItem(
                text = { Text("生成出售帖（出机）") },
                leadingIcon = { Icon(Icons.Rounded.LocalOffer, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = {
                    onDismiss()
                    onOpenSalePost()
                },
            )
        }
        if (hasAnyUrl) {
            if (hasPanelUrl && hasPurchaseUrl) {
                DropdownMenuItem(
                    text = { Text("打开管理面板") },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = {
                        onDismiss()
                        onOpenUrl(asset.panelManageUrl)
                    },
                )
                DropdownMenuItem(
                    text = { Text("打开购买链接") },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = {
                        onDismiss()
                        onOpenUrl(asset.purchaseUrl)
                    },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("打开面板·购买链接") },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = {
                        onDismiss()
                        onOpenUrl(asset.panelManageUrl.ifBlank { asset.purchaseUrl })
                    },
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
        DropdownMenuItem(
            text = {
                Text(
                    text = "删除",
                    color = MaterialTheme.colorScheme.error,
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            },
            onClick = {
                onDismiss()
                onDelete()
            },
        )
    }
}
