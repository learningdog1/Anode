package com.nodeseek.app.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Checks Android's system animator scale (`ValueAnimator.areAnimatorsEnabled()` on API 26+) so
 * infinite shimmers and custom frame loops degrade gracefully under Reduced Motion.
 */
internal fun areSystemAnimatorsEnabled(): Boolean =
    runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)

/**
 * Optional shared shimmer progress (`0f..1f`) provided by skeleton containers so all child
 * [PulseShimmerBox] elements animate in phase using a single `InfiniteTransition` and zero
 * composition-phase recompositions.
 */
val LocalPulseShimmerProgress = compositionLocalOf<State<Float>?> { null }

@Composable
fun ProvideSharedPulseShimmer(
    durationMillis: Int = 1250,
    content: @Composable () -> Unit,
) {
    val isInspection = LocalInspectionMode.current
    val animatorsEnabled = remember { areSystemAnimatorsEnabled() }
    if (isInspection || !animatorsEnabled || LocalPulseShimmerProgress.current != null) {
        content()
        return
    }
    val transition = rememberInfiniteTransition(label = "sharedPulseShimmerTransition")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sharedPulseShimmerProgress",
    )
    CompositionLocalProvider(LocalPulseShimmerProgress provides progress) {
        content()
    }
}

/**
 * Standardized elevation scale for Anode cards, hero tiles, and floating glass bars.
 */
object PulseElevation {
    val None: Dp = 0.dp
    val Subtle: Dp = 1.dp
    val Card: Dp = 2.dp
    val CardElevated: Dp = 4.dp
    val Hero: Dp = 6.dp
    val FloatingBar: Dp = 8.dp
}

/**
 * Theme-aware gradient brush factories for Warm Editorial Paper Light,
 * Deep Obsidian Dark, and Pure Pitch AMOLED.
 */
object PulseBrushes {

    @Composable
    fun brandGradient(horizontal: Boolean = true): Brush {
        val ext = NodePulseThemeTokens.extendedColors
        return remember(ext.brandGradientStart, ext.brandGradientEnd, horizontal) {
            val colors = listOf(ext.brandGradientStart, ext.brandGradientEnd)
            if (horizontal) {
                Brush.horizontalGradient(colors)
            } else {
                Brush.linearGradient(colors)
            }
        }
    }

    @Composable
    fun brandSoftWash(alpha: Float = 0.14f): Brush {
        val ext = NodePulseThemeTokens.extendedColors
        return remember(ext.brandGradientStart, ext.brandGradientEnd, alpha) {
            Brush.linearGradient(
                colors = listOf(
                    ext.brandGradientStart.copy(alpha = alpha),
                    ext.brandGradientEnd.copy(alpha = alpha * 0.65f),
                ),
            )
        }
    }

    @Composable
    fun drumstickGoldGradient(): Brush {
        val ext = NodePulseThemeTokens.extendedColors
        return remember(ext.drumstickGoldStart, ext.drumstickGoldEnd) {
            Brush.horizontalGradient(
                colors = listOf(ext.drumstickGoldStart, ext.drumstickGoldEnd),
            )
        }
    }

    @Composable
    fun heatFlameGradient(): Brush {
        val isDark = NodePulseThemeTokens.isDark
        return remember(isDark) {
            val colors = if (isDark) {
                listOf(Color(0xFFFB923C), Color(0xFFF43F5E))
            } else {
                listOf(Color(0xFFF97316), Color(0xFFEA580C))
            }
            Brush.horizontalGradient(colors)
        }
    }

    @Composable
    fun heroBannerGradient(
        accentColor: Color = MaterialTheme.colorScheme.primary,
    ): Brush {
        val ext = NodePulseThemeTokens.extendedColors
        val isDark = NodePulseThemeTokens.isDark
        return remember(ext.cardSurfaceElevated, ext.cardSurface, ext.brandGradientEnd, accentColor, isDark) {
            val startWash = blendColor(
                source = ext.cardSurfaceElevated,
                target = accentColor,
                fraction = if (isDark) 0.22f else 0.11f,
            )
            val midWash = blendColor(
                source = ext.cardSurface,
                target = ext.brandGradientEnd,
                fraction = if (isDark) 0.08f else 0.04f,
            )
            Brush.linearGradient(
                colors = listOf(startWash, midWash, ext.cardSurface),
            )
        }
    }

    @Composable
    fun glassHighlightBrush(
        isDark: Boolean = NodePulseThemeTokens.isDark,
    ): Brush = remember(isDark) {
        val topHighlight = if (isDark) {
            Color.White.copy(alpha = 0.07f)
        } else {
            Color.White.copy(alpha = 0.45f)
        }
        Brush.verticalGradient(
            colors = listOf(topHighlight, Color.Transparent),
        )
    }

    @Composable
    fun cardBorderBrush(
        accentColor: Color? = null,
        isDark: Boolean = NodePulseThemeTokens.isDark,
    ): Brush {
        val ext = NodePulseThemeTokens.extendedColors
        return remember(ext.cardBorder, accentColor, isDark) {
            val topRim = if (accentColor != null) {
                blendColor(
                    source = ext.cardBorder,
                    target = accentColor,
                    fraction = if (isDark) 0.48f else 0.32f,
                )
            } else if (isDark) {
                blendColor(ext.cardBorder, Color.White, 0.12f)
            } else {
                blendColor(ext.cardBorder, Color.White, 0.35f)
            }
            Brush.verticalGradient(
                colors = listOf(topRim, ext.cardBorder),
            )
        }
    }

    @Composable
    fun rememberShimmerBrush(
        targetOffset: Float = 1100f,
        durationMillis: Int = 1250,
    ): Brush {
        val ext = NodePulseThemeTokens.extendedColors
        val isInspection = LocalInspectionMode.current
        val animatorsEnabled = remember { areSystemAnimatorsEnabled() }
        if (isInspection || !animatorsEnabled) {
            return remember(ext.shimmerBase) { SolidColor(ext.shimmerBase) }
        }
        val transition = rememberInfiniteTransition(label = "pulseShimmerTransition")
        val translate by transition.animateFloat(
            initialValue = 0f,
            targetValue = targetOffset,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = durationMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "pulseShimmerOffset",
        )
        val bandWidth = targetOffset * 0.32f
        return Brush.linearGradient(
            colors = listOf(
                ext.shimmerBase,
                ext.shimmerHighlight,
                ext.shimmerBase,
            ),
            start = Offset(translate - bandWidth, 0f),
            end = Offset(translate, bandWidth * 0.7f),
        )
    }
}

/**
 * Elevated editorial card modifier with layered surface tint, subtle light-mode ambient shadow,
 * and crisp top-lit hairline border that adapts cleanly across Paper Light, Obsidian Dark & AMOLED.
 */
@Composable
fun Modifier.pulseCard(
    shape: Shape = PulseArchitecturalShapes.EditorialCard,
    containerColor: Color = NodePulseThemeTokens.extendedColors.cardSurface,
    borderColor: Color? = null,
    borderBrush: Brush? = null,
    borderWidth: Dp = 1.dp,
    elevation: Dp = PulseElevation.Card,
    accentColor: Color? = null,
): Modifier {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val effectiveElevation = if (isDark) PulseElevation.None else elevation
    val surfaceBrush = if (accentColor != null) {
        val tintedTop = blendColor(
            source = containerColor,
            target = accentColor,
            fraction = if (isDark) 0.11f else 0.055f,
        )
        Brush.verticalGradient(listOf(tintedTop, containerColor))
    } else {
        SolidColor(containerColor)
    }
    val resolvedBorderBrush = when {
        borderBrush != null -> borderBrush
        borderColor != null -> SolidColor(borderColor)
        else -> PulseBrushes.cardBorderBrush(accentColor = accentColor, isDark = isDark)
    }

    return this
        .then(
            if (effectiveElevation > 0.dp) {
                Modifier.shadow(
                    elevation = effectiveElevation,
                    shape = shape,
                    clip = false,
                    ambientColor = ext.softShadowColor,
                    spotColor = ext.softShadowColor,
                )
            } else {
                Modifier
            },
        )
        .clip(shape)
        .background(brush = surfaceBrush)
        .then(
            if (borderWidth > 0.dp) {
                Modifier.border(width = borderWidth, brush = resolvedBorderBrush, shape = shape)
            } else {
                Modifier
            },
        )
}

/**
 * Rich hero bento card modifier with atmospheric diagonal accent gradient, specular top rim,
 * and subtle luminous border.
 */
@Composable
fun Modifier.pulseHeroCard(
    accentColor: Color = MaterialTheme.colorScheme.primary,
    shape: Shape = PulseArchitecturalShapes.HeroBento,
    elevation: Dp = PulseElevation.Hero,
    borderWidth: Dp = 1.dp,
): Modifier {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val effectiveElevation = if (isDark) PulseElevation.None else elevation
    val heroBorder = Brush.linearGradient(
        colors = listOf(
            accentColor.copy(alpha = if (isDark) 0.42f else 0.28f),
            ext.cardBorder,
        ),
    )

    return this
        .then(
            if (effectiveElevation > 0.dp) {
                Modifier.shadow(
                    elevation = effectiveElevation,
                    shape = shape,
                    clip = false,
                    ambientColor = blendColor(ext.softShadowColor, accentColor, 0.25f),
                    spotColor = blendColor(ext.softShadowColor, accentColor, 0.25f),
                )
            } else {
                Modifier
            },
        )
        .clip(shape)
        .background(PulseBrushes.heroBannerGradient(accentColor))
        .background(PulseBrushes.glassHighlightBrush(isDark))
        .then(
            if (borderWidth > 0.dp) {
                Modifier.border(width = borderWidth, brush = heroBorder, shape = shape)
            } else {
                Modifier
            },
        )
}

/**
 * Translucent glass-like bar surface with top highlight sheen and crisp hairline edge dividers.
 * Ideal for TopAppBars, sticky filter headers, reply composer bars, and bottom docks.
 */
@Composable
fun Modifier.pulseGlassSurface(
    shape: Shape = RectangleShape,
    containerColor: Color = NodePulseThemeTokens.extendedColors.glassSurface,
    borderColor: Color = NodePulseThemeTokens.extendedColors.glassBorder,
    showTopBorder: Boolean = false,
    showBottomBorder: Boolean = true,
    tonalAccent: Color? = null,
): Modifier {
    val isDark = NodePulseThemeTokens.isDark
    return this
        .clip(shape)
        .background(containerColor)
        .then(
            if (tonalAccent != null) {
                Modifier.background(
                    Brush.verticalGradient(
                        colors = listOf(
                            tonalAccent.copy(alpha = if (isDark) 0.11f else 0.06f),
                            Color.Transparent,
                        ),
                    ),
                )
            } else {
                Modifier
            },
        )
        .background(PulseBrushes.glassHighlightBrush(isDark))
        .drawWithContent {
            drawContent()
            val strokePx = 0.8.dp.toPx()
            if (showTopBorder) {
                drawLine(
                    color = borderColor,
                    start = Offset(0f, strokePx / 2f),
                    end = Offset(size.width, strokePx / 2f),
                    strokeWidth = strokePx,
                )
            }
            if (showBottomBorder) {
                drawLine(
                    color = borderColor,
                    start = Offset(0f, size.height - strokePx / 2f),
                    end = Offset(size.width, size.height - strokePx / 2f),
                    strokeWidth = strokePx,
                )
            }
        }
}

/**
 * Micro-bounce press scale modifier bound to an existing [InteractionSource].
 */
@Composable
fun Modifier.pulsePressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = NodePulseMotion.cardPressScale,
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = NodePulseMotion.microBounceSpring(),
        label = "pulsePressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Interactive press-scale + ripple clickable modifier with Anode spring physics.
 */
@Composable
fun Modifier.pulseBouncyClickable(
    enabled: Boolean = true,
    pressedScale: Float = NodePulseMotion.cardPressScale,
    role: Role? = null,
    onClickLabel: String? = null,
    showRipple: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val indication = if (showRipple) LocalIndication.current else null
    return this
        .pulsePressScale(
            interactionSource = interactionSource,
            pressedScale = pressedScale,
        )
        .clickable(
            interactionSource = interactionSource,
            indication = indication,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
}

/**
 * Diagonal animated shimmer background modifier for loading skeleton blocks.
 * Reads animation progress strictly during the draw phase (`drawBehind`) and reuses any
 * ancestor [LocalPulseShimmerProgress] to eliminate composition-phase recomposition storms.
 */
@Composable
fun Modifier.pulseShimmer(
    shape: Shape = RoundedCornerShape(8.dp),
): Modifier {
    val ext = NodePulseThemeTokens.extendedColors
    val isInspection = LocalInspectionMode.current
    val animatorsEnabled = remember { areSystemAnimatorsEnabled() }
    if (isInspection || !animatorsEnabled) {
        return this
            .clip(shape)
            .background(ext.shimmerBase)
    }

    val sharedProgress = LocalPulseShimmerProgress.current
    val progressState = sharedProgress ?: run {
        val transition = rememberInfiniteTransition(label = "pulseShimmerFallbackTransition")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1250, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "pulseShimmerFallbackProgress",
        )
    }
    val shimmerColors = remember(ext.shimmerBase, ext.shimmerHighlight) {
        listOf(ext.shimmerBase, ext.shimmerHighlight, ext.shimmerBase)
    }

    return this
        .clip(shape)
        .drawBehind {
            val span = (size.width.coerceAtLeast(320f)) * 1.6f
            val bandWidth = span * 0.32f
            val translate = -bandWidth + (span + bandWidth * 2f) * progressState.value
            drawRect(
                brush = Brush.linearGradient(
                    colors = shimmerColors,
                    start = Offset(translate - bandWidth, 0f),
                    end = Offset(translate, bandWidth * 0.7f),
                ),
            )
        }
}

/**
 * Standalone glass container composable with specular top sheen and hairline border.
 */
@Composable
fun PulseGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = PulseArchitecturalShapes.EditorialCard,
    containerColor: Color = NodePulseThemeTokens.extendedColors.glassSurface,
    borderColor: Color = NodePulseThemeTokens.extendedColors.glassBorder,
    tonalAccent: Color? = null,
    borderWidth: Dp = 1.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val isDark = NodePulseThemeTokens.isDark
    Box(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .then(
                if (tonalAccent != null) {
                    Modifier.background(
                        Brush.verticalGradient(
                            colors = listOf(
                                tonalAccent.copy(alpha = if (isDark) 0.12f else 0.06f),
                                Color.Transparent,
                            ),
                        ),
                    )
                } else {
                    Modifier
                },
            )
            .background(PulseBrushes.glassHighlightBrush(isDark))
            .then(
                if (borderWidth > 0.dp) {
                    Modifier.border(width = borderWidth, color = borderColor, shape = shape)
                } else {
                    Modifier
                },
            ),
        content = content,
    )
}

/**
 * Expressive editorial section header with gradient accent bar or tinted icon badge,
 * optional subtitle, and trailing slot.
 */
@Composable
fun PulseSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val extraType = NodePulseThemeTokens.extraTypography

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .background(accentColor.copy(alpha = if (isDark) 0.18f else 0.12f))
                    .border(
                        width = 0.8.dp,
                        color = accentColor.copy(alpha = if (isDark) 0.34f else 0.22f),
                        shape = PulseArchitecturalShapes.SmallChip,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(if (subtitle != null) 28.dp else 17.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(accentColor, ext.brandGradientEnd),
                        ),
                    ),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = title,
                style = extraType.sectionHeader,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (trailing != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                content = trailing,
            )
        }
    }
}

/**
 * Gradient-faded horizontal divider that avoids harsh hard-edged lines.
 */
@Composable
fun PulseGradientDivider(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outlineVariant,
    thickness: Dp = 0.8.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        color.copy(alpha = 0.65f),
                        Color.Transparent,
                    ),
                ),
            ),
    )
}

/**
 * Shimmering skeleton box block for loading placeholders.
 */
@Composable
fun PulseShimmerBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
) {
    Box(modifier = modifier.pulseShimmer(shape = shape))
}

/**
 * Subtle staggered fade + slide-up entrance wrapper for cards, grid tiles, and section blocks.
 * Items beyond [maxStaggerSteps] or under Reduced Motion appear immediately at full opacity so
 * scrolling long `LazyColumn` lists never flashes invisible rows.
 */
@Composable
fun PulseStaggeredEntrance(
    modifier: Modifier = Modifier,
    index: Int = 0,
    enabled: Boolean = true,
    maxStaggerSteps: Int = 6,
    content: @Composable () -> Unit,
) {
    val isInspection = LocalInspectionMode.current
    val animatorsEnabled = remember { areSystemAnimatorsEnabled() }
    val withinInitialStagger = index in 0..maxStaggerSteps
    val effectiveEnabled = enabled && !isInspection && animatorsEnabled && withinInitialStagger
    val progress = remember { Animatable(if (effectiveEnabled) 0f else 1f) }
    LaunchedEffect(effectiveEnabled) {
        if (!effectiveEnabled) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        if (index > 0) {
            delay(index * 26L)
        }
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        )
    }

    Box(
        modifier = modifier.graphicsLayer {
            val p = progress.value
            alpha = p
            translationY = (1f - p) * 12.dp.toPx()
        },
    ) {
        content()
    }
}

/**
 * Compact polished accent pill with optional icon and optional gradient fill.
 */
@Composable
fun PulseAccentPill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    containerBrush: Brush? = null,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    val bgBrush = containerBrush ?: SolidColor(containerColor)
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(bgBrush)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            text = text,
            style = NodePulseThemeTokens.extraTypography.microLabel,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
