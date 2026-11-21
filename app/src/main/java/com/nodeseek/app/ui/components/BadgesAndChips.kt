package com.nodeseek.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.pulsePressScale
import com.nodeseek.app.ui.theme.withTabularNumbers
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Semantic board/category micro-tag using the 15-board NodePulse palette with soft gradient
 * fill and crisp hairline accent border.
 */
@Composable
fun CategoryMicroTag(
    slug: String,
    modifier: Modifier = Modifier,
    labelOverride: String? = null,
    showIcon: Boolean = true,
    compact: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val isDark = NodePulseThemeTokens.isDark
    val spec = NodePulseThemeTokens.categorySpec(slug)
    val label = labelOverride?.takeIf { it.isNotBlank() } ?: spec.defaultTitle
    val shape = RoundedCornerShape(if (compact) 8.dp else 10.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val clickModifier = if (onClick != null) {
        Modifier
            .pulsePressScale(interactionSource = interactionSource, pressedScale = NodePulseMotion.chipPressScale)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .clip(shape)
            .background(spec.softTonalBrush)
            .border(
                width = 0.75.dp,
                color = spec.spineColor.copy(alpha = if (isDark) 0.34f else 0.20f),
                shape = shape,
            )
            .then(clickModifier)
            .padding(
                horizontal = if (compact) 7.5.dp else 10.5.dp,
                vertical = if (compact) 3.dp else 5.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showIcon) {
            Icon(
                imageVector = spec.icon,
                contentDescription = null,
                tint = spec.contentColor,
                modifier = Modifier.size(if (compact) 12.dp else 14.dp),
            )
        }
        Text(
            text = label,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = spec.contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Lv.0–Lv.6 metallic/gemstone micro-badge with gradient container tint and gradient hairline border.
 */
@Composable
fun RankMicroBadge(
    level: Int,
    modifier: Modifier = Modifier,
    showPersona: Boolean = false,
    compact: Boolean = false,
) {
    val spec = NodePulseThemeTokens.rankSpec(level)
    val shape = RoundedCornerShape(if (compact) 5.5.dp else 7.dp)
    val fontScale = NodePulseThemeTokens.fontScale
    val text = if (showPersona) {
        "Lv.${spec.level} · ${spec.personaTitle}"
    } else {
        "Lv.${spec.level}"
    }

    Row(
        modifier = modifier
            .clip(shape)
            .background(spec.softContainerBrush)
            .border(
                width = if (compact) 0.8.dp else 0.9.dp,
                brush = spec.ringBrush,
                shape = shape,
            )
            .padding(
                horizontal = if (compact) 5.dp else 6.dp,
                vertical = if (compact) 1.dp else 2.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp),
    ) {
        if (spec.level >= 5) {
            Box(
                modifier = Modifier
                    .size(if (compact) 4.dp else 5.dp)
                    .clip(CircleShape)
                    .background(spec.ringBrush),
            )
        }
        Text(
            text = text,
            style = if (compact) {
                NodePulseThemeTokens.extraTypography.tabularBadge.copy(
                    fontSize = (10f * fontScale).sp,
                    lineHeight = (13f * fontScale).sp,
                )
            } else {
                NodePulseThemeTokens.extraTypography.tabularBadge
            },
            color = spec.contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One-tap Daily Check-in (鸡腿) status chip for the Explore Top Bar.
 * Morphs from glowing warm gold CTA (`签到领腿`) to soft verified pill (`今日 +5`).
 */
@Composable
fun DrumstickCheckInChip(
    checkedIn: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    todayGain: Int? = null,
    isLoading: Boolean = false,
    isSignedIn: Boolean = true,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val interactionSource = remember { MutableInteractionSource() }

    val containerColor by animateColorAsState(
        targetValue = when {
            !isSignedIn -> MaterialTheme.colorScheme.surfaceContainerHigh
            checkedIn -> ext.drumstickContainer
            else -> MaterialTheme.colorScheme.tertiaryContainer
        },
        label = "checkInContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !isSignedIn -> MaterialTheme.colorScheme.onSurfaceVariant
            checkedIn -> ext.onDrumstickContainer
            else -> MaterialTheme.colorScheme.onTertiaryContainer
        },
        label = "checkInContent",
    )
    val scale by animateFloatAsState(
        targetValue = if (checkedIn || !isSignedIn) 1.0f else 1.02f,
        animationSpec = NodePulseMotion.microBounceSpring(),
        label = "checkInScale",
    )

    val backgroundBrush = when {
        !isSignedIn -> SolidColor(containerColor)
        checkedIn -> Brush.horizontalGradient(
            listOf(
                containerColor,
                blendColor(containerColor, ext.drumstickGoldStart, if (isDark) 0.14f else 0.18f),
            ),
        )
        else -> Brush.horizontalGradient(
            listOf(
                blendColor(containerColor, ext.drumstickGoldStart, if (isDark) 0.22f else 0.18f),
                containerColor,
            ),
        )
    }

    val borderBrush = when {
        !checkedIn && isSignedIn -> PulseBrushes.drumstickGoldGradient()
        checkedIn && isSignedIn -> SolidColor(ext.drumstickGoldEnd.copy(alpha = if (isDark) 0.44f else 0.36f))
        else -> SolidColor(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
    }

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .pulsePressScale(interactionSource = interactionSource, pressedScale = NodePulseMotion.chipPressScale)
            .then(
                if (!isDark && !checkedIn && isSignedIn) {
                    Modifier.shadow(
                        elevation = 3.dp,
                        shape = CircleShape,
                        clip = false,
                        ambientColor = ext.drumstickGoldEnd.copy(alpha = 0.32f),
                        spotColor = ext.drumstickGoldEnd.copy(alpha = 0.32f),
                    )
                } else {
                    Modifier
                },
            )
            .height(34.dp)
            .clip(CircleShape)
            .background(backgroundBrush)
            .border(width = if (!checkedIn && isSignedIn) 1.15.dp else 0.9.dp, brush = borderBrush, shape = CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = !isLoading,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 11.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = contentColor,
            )
            Text(
                text = "签到中",
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                maxLines = 1,
                softWrap = false,
            )
        } else {
            Icon(
                imageVector = if (checkedIn) Icons.Rounded.CheckCircle else Icons.Rounded.Restaurant,
                contentDescription = null,
                tint = if (isSignedIn) ext.drumstickGoldEnd else contentColor,
                modifier = Modifier.size(15.dp),
            )
            val label = when {
                !isSignedIn -> "登录领腿"
                checkedIn && todayGain != null -> "今日 +$todayGain"
                checkedIn -> "已签到"
                else -> "签到领腿"
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Heat / Comment count telemetry pill. Switches to warm coral-amber gradient when thread is hot.
 */
@Composable
fun HeatCommentPill(
    replyCount: Int,
    modifier: Modifier = Modifier,
    viewCount: Int = 0,
    isHotOverride: Boolean = false,
    compact: Boolean = false,
) {
    val isHot = isHotOverride || replyCount >= 30 || viewCount >= 1500
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val fontScale = NodePulseThemeTokens.fontScale

    val bgBrush = if (isHot) {
        Brush.horizontalGradient(
            listOf(
                ext.heatContainer,
                blendColor(ext.heatContainer, ext.onHeatContainer, if (isDark) 0.18f else 0.12f),
            ),
        )
    } else {
        SolidColor(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.85f else 0.72f))
    }
    val borderColor = if (isHot) {
        ext.onHeatContainer.copy(alpha = if (isDark) 0.36f else 0.22f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)
    }
    val fgColor = if (isHot) ext.onHeatContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier
            .heightIn(min = if (compact) 20.dp else 22.dp)
            .clip(CircleShape)
            .background(bgBrush)
            .border(width = 0.75.dp, color = borderColor, shape = CircleShape)
            .padding(
                horizontal = if (compact) 7.dp else 8.dp,
                vertical = if (compact) 2.dp else 3.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 3.5.dp),
    ) {
        Icon(
            imageVector = if (isHot) Icons.Rounded.LocalFireDepartment else Icons.Rounded.ChatBubbleOutline,
            contentDescription = null,
            tint = fgColor,
            modifier = Modifier.size(if (compact) 11.dp else 12.dp),
        )
        Text(
            text = formatCompactCount(replyCount),
            style = if (compact) {
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = (11f * fontScale).sp,
                    lineHeight = (13.5f * fontScale).sp,
                ).withTabularNumbers()
            } else {
                MaterialTheme.typography.labelMedium.withTabularNumbers()
            },
            fontWeight = if (isHot) FontWeight.Bold else FontWeight.Medium,
            color = fgColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Gradient brand highlight chip identifying the Topic Author ("楼主").
 */
@Composable
fun OpHighlightChip(
    modifier: Modifier = Modifier,
    label: String = "楼主",
) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(PulseBrushes.brandGradient())
            .border(
                width = 0.7.dp,
                color = Color.White.copy(alpha = 0.24f),
                shape = shape,
            )
            .padding(horizontal = 6.5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Read-permission level gate chip (`🔒 Lv.2+` or `🔒 仅作者`).
 */
@Composable
fun AccessLockChip(
    requiredLevel: Int,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (requiredLevel <= 0) return
    val isDark = NodePulseThemeTokens.isDark
    val insideSpec = NodePulseThemeTokens.categorySpec("inside")
    val label = if (requiredLevel >= 255) "仅作者可见" else "Lv.$requiredLevel+"
    val shape = RoundedCornerShape(if (compact) 6.dp else 7.dp)

    Row(
        modifier = modifier
            .then(if (compact) Modifier.heightIn(min = 20.dp) else Modifier)
            .clip(shape)
            .background(insideSpec.softTonalBrush)
            .border(
                width = 0.75.dp,
                color = insideSpec.spineColor.copy(alpha = if (isDark) 0.40f else 0.24f),
                shape = shape,
            )
            .padding(horizontal = if (compact) 6.dp else 6.5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Lock,
            contentDescription = null,
            tint = insideSpec.contentColor,
            modifier = Modifier.size(if (compact) 10.5.dp else 11.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
            color = insideSpec.contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Compact floor index pill (`#12` / `OP`) used inside Topic Reader comment headers.
 */
@Composable
fun FloorNumberPill(
    floorNumber: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val isDark = NodePulseThemeTokens.isDark
    val isOp = floorNumber == 0
    val shape = RoundedCornerShape(7.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val clickMod = if (onClick != null) {
        Modifier
            .pulsePressScale(interactionSource = interactionSource, pressedScale = NodePulseMotion.chipPressScale)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
    } else {
        Modifier
    }

    val bgColor = if (isOp) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.72f else 0.85f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f)
    }
    val borderColor = if (isOp) {
        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.42f else 0.25f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    }
    val fgColor = if (isOp) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val text = if (isOp) "OP" else "#$floorNumber"
    Row(
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .border(width = 0.75.dp, color = borderColor, shape = shape)
            .then(clickMod)
            .padding(horizontal = 6.5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = NodePulseThemeTokens.extraTypography.tabularBadge,
            color = fgColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Tabular Chinese relative time text composable (`刚刚`, `3 分钟前`, `2 小时前`, `昨天`, `10-15`).
 */
@Composable
fun RelativeTimeText(
    isoTimestamp: String,
    modifier: Modifier = Modifier,
    prefix: String = "",
    style: TextStyle = MaterialTheme.typography.labelMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val formatted = remember(isoTimestamp) { formatRelativeTimeChinese(isoTimestamp) }
    val display = if (prefix.isNotEmpty() && formatted.isNotEmpty()) "$prefix$formatted" else formatted
    Text(
        text = display,
        style = style.withTabularNumbers(),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

fun formatCompactCount(count: Int): String {
    val safe = count.coerceAtLeast(0)
    return when {
        safe >= 10_000 -> String.format(Locale.US, "%.1fw", safe / 10_000.0)
        safe >= 1_000 -> String.format(Locale.US, "%.1fk", safe / 1_000.0)
        else -> safe.toString()
    }
}

/**
 * Formats compact corner/notification badge counts, keeping `0..maxCount` exact and capping
 * larger counts at `"$maxCount+"` (default `"99+"`) so pill geometry stays predictable.
 */
fun formatBadgeCount(
    count: Int,
    maxCount: Int = 99,
): String {
    val safeMax = maxCount.coerceAtLeast(1)
    val safeCount = count.coerceAtLeast(0)
    return if (safeCount > safeMax) "$safeMax+" else safeCount.toString()
}

/**
 * Compact numeric pill badge engineered for tight corner anchors and header telemetry:
 * - Single-digit (`0..9`): crisp circle (`minWidth = minHeight`).
 * - Multi-digit (`10..99`, `99+`): compact capsule (`CircleShape`) with `4.5.dp` horizontal
 *   padding, `maxLines = 1`, `softWrap = false`, `tabularBadge` (`tnum`) numerals, and an
 *   optional cutout border ring so it separates cleanly over icons and tinted containers.
 */
@Composable
fun CompactCountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    maxCount: Int = 99,
    showZero: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    containerBrush: Brush? = null,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    cutoutBorderColor: Color = Color.Transparent,
    cutoutBorderWidth: Dp = 1.dp,
    minHeight: Dp = 18.dp,
    textStyle: TextStyle = NodePulseThemeTokens.extraTypography.tabularBadge,
) {
    if (count <= 0 && !showZero) return
    val formatted = formatBadgeCount(count = count, maxCount = maxCount)
    val isSingleDigit = formatted.length <= 1
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = minHeight, minHeight = minHeight)
            .clip(CircleShape)
            .then(
                if (containerBrush != null) {
                    Modifier.background(containerBrush, CircleShape)
                } else {
                    Modifier.background(containerColor, CircleShape)
                },
            )
            .then(
                if (cutoutBorderWidth > 0.dp && cutoutBorderColor.alpha > 0f) {
                    Modifier.border(
                        width = cutoutBorderWidth,
                        color = cutoutBorderColor,
                        shape = CircleShape,
                    )
                } else {
                    Modifier
                },
            )
            .padding(
                horizontal = if (isSingleDigit) 2.5.dp else 4.5.dp,
                vertical = 1.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = formatted,
            style = textStyle.withTabularNumbers(),
            color = contentColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Fixed-size icon/button anchor that overlays a top-end [CompactCountBadge] (or optional
 * notification dot when [count] <= 0 and [showIndicatorDotWhenZero] is true) with a `0x0` layout
 * footprint so `1` -> `88` -> `99+` transitions never widen the anchor, shift siblings, or wrap.
 */
@Composable
fun CornerCountBadgeBox(
    count: Int,
    modifier: Modifier = Modifier,
    containerSize: Dp = 24.dp,
    maxCount: Int = 99,
    badgeOffset: DpOffset = DpOffset(x = 5.dp, y = (-6).dp),
    containerColor: Color = MaterialTheme.colorScheme.error,
    containerBrush: Brush? = null,
    contentColor: Color = MaterialTheme.colorScheme.onError,
    cutoutBorderColor: Color = MaterialTheme.colorScheme.surface,
    cutoutBorderWidth: Dp = 1.25.dp,
    badgeMinHeight: Dp = 18.dp,
    badgeTextStyle: TextStyle = NodePulseThemeTokens.extraTypography.tabularBadge,
    showIndicatorDotWhenZero: Boolean = false,
    indicatorDotColor: Color = MaterialTheme.colorScheme.tertiary,
    indicatorDotBrush: Brush? = null,
    indicatorDotSize: Dp = 8.dp,
    indicatorDotOffset: DpOffset = DpOffset(x = 3.dp, y = (-2).dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.size(containerSize),
        contentAlignment = Alignment.Center,
    ) {
        content()
        if (count > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .topEndCornerOverlay(badgeOffset),
            ) {
                CompactCountBadge(
                    count = count,
                    maxCount = maxCount,
                    containerColor = containerColor,
                    containerBrush = containerBrush,
                    contentColor = contentColor,
                    cutoutBorderColor = cutoutBorderColor,
                    cutoutBorderWidth = cutoutBorderWidth,
                    minHeight = badgeMinHeight,
                    textStyle = badgeTextStyle,
                )
            }
        } else if (showIndicatorDotWhenZero) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .topEndCornerOverlay(indicatorDotOffset),
            ) {
                Box(
                    modifier = Modifier
                        .size(indicatorDotSize)
                        .clip(CircleShape)
                        .then(
                            if (indicatorDotBrush != null) {
                                Modifier.background(indicatorDotBrush, CircleShape)
                            } else {
                                Modifier.background(indicatorDotColor, CircleShape)
                            },
                        )
                        .then(
                            if (cutoutBorderWidth > 0.dp && cutoutBorderColor.alpha > 0f) {
                                Modifier.border(
                                    width = cutoutBorderWidth,
                                    color = cutoutBorderColor,
                                    shape = CircleShape,
                                )
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }
    }
}

/**
 * Measures a corner overlay unbounded (so narrow icon containers never force badge wrapping)
 * while occupying a `0x0` layout footprint anchored by its top-end corner.
 */
private fun Modifier.topEndCornerOverlay(offset: DpOffset): Modifier =
    this
        .zIndex(1f)
        .layout { measurable, _ ->
            val placeable = measurable.measure(Constraints())
            layout(0, 0) {
                val xOffsetPx = offset.x.roundToPx()
                val yOffsetPx = offset.y.roundToPx()
                placeable.placeRelative(
                    x = -placeable.width + xOffsetPx,
                    y = yOffsetPx,
                )
            }
        }

private val SPACE_DATE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

private val SHORT_MONTH_DAY_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd", Locale.US)

private val FULL_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

/**
 * Parses ISO-8601, `yyyy-MM-dd HH:mm:ss`, or RFC-1123 (RSS `pubDate`) strings into a concise
 * Chinese relative timestamp string. Gracefully returns the input if already human-formatted.
 */
fun formatRelativeTimeChinese(
    rawTimestamp: String,
    nowEpochMillis: Long = System.currentTimeMillis(),
): String {
    val trimmed = rawTimestamp.trim()
    if (trimmed.isEmpty()) return ""

    val parsedEpochMillis = parseTimestampEpochMillis(trimmed) ?: return trimmed
    val diffSeconds = ((nowEpochMillis - parsedEpochMillis) / 1000L)

    if (diffSeconds < 45L) return "刚刚"
    if (diffSeconds < 3600L) return "${(diffSeconds / 60L).coerceAtLeast(1L)} 分钟前"
    if (diffSeconds < 86_400L) return "${diffSeconds / 3600L} 小时前"
    if (diffSeconds < 172_800L) return "昨天"
    if (diffSeconds < 604_800L) return "${diffSeconds / 86_400L} 天前"

    val zone = ZoneId.systemDefault()
    val targetDate = Instant.ofEpochMilli(parsedEpochMillis).atZone(zone)
    val nowDate = Instant.ofEpochMilli(nowEpochMillis).atZone(zone)
    return if (targetDate.year == nowDate.year) {
        SHORT_MONTH_DAY_FORMATTER.format(targetDate)
    } else {
        FULL_DATE_FORMATTER.format(targetDate)
    }
}

internal fun parseTimestampEpochMillis(value: String): Long? {
    runCatching { return Instant.parse(value).toEpochMilli() }
    runCatching { return OffsetDateTime.parse(value).toInstant().toEpochMilli() }
    runCatching {
        return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant()
            .toEpochMilli()
    }
    runCatching {
        return LocalDateTime.parse(value, SPACE_DATE_TIME_FORMATTER)
            .atZone(ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli()
    }
    value.toLongOrNull()?.let { numeric ->
        return if (numeric < 10_000_000_000L) numeric * 1000L else numeric
    }
    return null
}
