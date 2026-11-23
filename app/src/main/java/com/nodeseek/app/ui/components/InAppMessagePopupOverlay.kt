package com.nodeseek.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeseek.app.domain.radar.InAppMessageKind
import com.nodeseek.app.domain.radar.InAppMessagePopup
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.areSystemAnimatorsEnabled
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val AUTO_DISMISS_DURATION_MS = 5_200L
private const val MAX_QUEUED_POPUPS = 10
private const val CLICK_EFFECT_NAVIGATION_DELAY_MS = 175L

@Stable
class InAppMessagePopupState {
    private val queue = mutableStateListOf<InAppMessagePopup>()

    val currentPopup: InAppMessagePopup?
        get() = queue.firstOrNull()

    val queuedCount: Int
        get() = (queue.size - 1).coerceAtLeast(0)

    fun enqueue(popup: InAppMessagePopup) {
        if (queue.any { it.dedupKey == popup.dedupKey }) return
        if (queue.size >= MAX_QUEUED_POPUPS) {
            if (queue.size > 1) {
                queue.removeAt(1)
            } else {
                queue.removeAt(0)
            }
        }
        queue.add(popup)
    }

    fun dismissCurrent() {
        if (queue.isNotEmpty()) {
            queue.removeAt(0)
        }
    }

    fun removeCurrentAndMatching(popup: InAppMessagePopup) {
        queue.removeAll { it.dedupKey == popup.dedupKey }
    }

    fun removeWhere(predicate: (InAppMessagePopup) -> Boolean) {
        queue.removeAll(predicate)
    }

    fun clear() {
        queue.clear()
    }
}

@Composable
fun rememberInAppMessagePopupState(): InAppMessagePopupState =
    remember { InAppMessagePopupState() }

private data class PopupSemanticStyle(
    val accentColor: Color,
    val secondaryAccentColor: Color,
    val badgeContainerColor: Color,
    val badgeContentColor: Color,
    val badgeBorderColor: Color,
    val badgeLabel: String,
    val icon: ImageVector,
    val actionLabel: String,
)

@Composable
private fun rememberPopupSemanticStyle(
    kind: InAppMessageKind,
    isDark: Boolean,
): PopupSemanticStyle {
    val colorScheme = MaterialTheme.colorScheme
    val ext = NodePulseThemeTokens.extendedColors
    val techSpec = NodePulseThemeTokens.categorySpec("tech")
    return remember(kind, isDark, colorScheme, ext, techSpec) {
        val borderAlpha = if (isDark) 0.42f else 0.26f
        when (kind) {
            InAppMessageKind.REPLY -> PopupSemanticStyle(
                accentColor = colorScheme.primary,
                secondaryAccentColor = blendColor(colorScheme.primary, colorScheme.secondary, 0.35f),
                badgeContainerColor = colorScheme.primaryContainer,
                badgeContentColor = colorScheme.onPrimaryContainer,
                badgeBorderColor = colorScheme.primary.copy(alpha = borderAlpha),
                badgeLabel = "回复了你",
                icon = Icons.Rounded.ChatBubbleOutline,
                actionLabel = "直达",
            )

            InAppMessageKind.MENTION -> PopupSemanticStyle(
                accentColor = colorScheme.secondary,
                secondaryAccentColor = blendColor(colorScheme.secondary, colorScheme.primary, 0.35f),
                badgeContainerColor = colorScheme.secondaryContainer,
                badgeContentColor = colorScheme.onSecondaryContainer,
                badgeBorderColor = colorScheme.secondary.copy(alpha = borderAlpha),
                badgeLabel = "@提到了你",
                icon = Icons.Rounded.AlternateEmail,
                actionLabel = "直达",
            )

            InAppMessageKind.DIRECT_MESSAGE -> PopupSemanticStyle(
                accentColor = if (isDark) ext.drumstickGoldEnd else ext.onDrumstickContainer,
                secondaryAccentColor = ext.drumstickGoldStart,
                badgeContainerColor = ext.drumstickContainer,
                badgeContentColor = ext.onDrumstickContainer,
                badgeBorderColor = ext.drumstickGoldEnd.copy(alpha = if (isDark) 0.44f else 0.30f),
                badgeLabel = "新私信",
                icon = Icons.Rounded.Mail,
                actionLabel = "去聊天",
            )

            InAppMessageKind.SSH_BENCHMARK_COMPLETED -> PopupSemanticStyle(
                accentColor = techSpec.spineColor,
                secondaryAccentColor = blendColor(techSpec.spineColor, colorScheme.secondary, 0.32f),
                badgeContainerColor = techSpec.containerColor,
                badgeContentColor = techSpec.contentColor,
                badgeBorderColor = techSpec.spineColor.copy(alpha = borderAlpha),
                badgeLabel = "测机完成",
                icon = Icons.Rounded.Terminal,
                actionLabel = "看报告",
            )
        }
    }
}

/**
 * Floating top in-app message toast overlay for newly arrived Replies, @Mentions, and Direct Messages.
 * Adapts to Paper Light, Obsidian Dark, and Pure Pitch AMOLED with spring entrance, border shimmer
 * sweep, top luminous countdown bar, swipe-up/horizontal dismiss, and bouncy halo tap feedback.
 */
@Composable
fun InAppMessagePopupOverlay(
    state: InAppMessagePopupState,
    onPopupClick: (InAppMessagePopup) -> Unit,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
) {
    val currentPopup = state.currentPopup
    val queuedCount = state.queuedCount

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedContent(
            targetState = currentPopup,
            contentKey = { it?.dedupKey ?: "__empty__" },
            transitionSpec = {
                val enter = slideInVertically(
                    animationSpec = spring(dampingRatio = 0.68f, stiffness = 420f),
                    initialOffsetY = { fullHeight -> -fullHeight - (fullHeight / 2).coerceAtLeast(36) },
                ) + scaleIn(
                    initialScale = 0.88f,
                    animationSpec = spring(dampingRatio = 0.62f, stiffness = 440f),
                ) + fadeIn(
                    animationSpec = tween(durationMillis = 210, easing = FastOutSlowInEasing),
                )

                val exit = slideOutVertically(
                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                    targetOffsetY = { fullHeight -> -fullHeight - 40 },
                ) + scaleOut(
                    targetScale = 0.93f,
                    animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
                ) + fadeOut(
                    animationSpec = tween(durationMillis = 170),
                )

                (enter togetherWith exit).using(SizeTransform(clip = false))
            },
            label = "inAppMessagePopupOverlayTransition",
        ) { popup ->
            if (popup != null) {
                InAppMessagePopupCard(
                    popup = popup,
                    queuedCount = queuedCount,
                    paused = paused,
                    onClick = { onPopupClick(popup) },
                    onDismiss = { state.removeCurrentAndMatching(popup) },
                )
            }
        }
    }
}

@Composable
private fun InAppMessagePopupCard(
    popup: InAppMessagePopup,
    queuedCount: Int,
    paused: Boolean,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val fontScale = NodePulseThemeTokens.fontScale
    val isPurePitch = isDark && MaterialTheme.colorScheme.background == Color.Black
    val style = rememberPopupSemanticStyle(kind = popup.kind, isDark = isDark)
    val haptics = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val latestOnClick by rememberUpdatedState(onClick)
    val latestOnDismiss by rememberUpdatedState(onDismiss)

    val cardShape = PulseArchitecturalShapes.HeroBento
    var cardWidthPx by remember(popup.dedupKey) { mutableFloatStateOf(0f) }
    var cardHeightPx by remember(popup.dedupKey) { mutableFloatStateOf(0f) }

    // Interaction & animation states
    var isInteracting by remember(popup.dedupKey) { mutableStateOf(false) }
    var isPressedVisual by remember(popup.dedupKey) { mutableStateOf(false) }
    var isDragging by remember(popup.dedupKey) { mutableStateOf(false) }
    var isActivating by remember(popup.dedupKey) { mutableStateOf(false) }
    var isDismissingOut by remember(popup.dedupKey) { mutableStateOf(false) }

    var dragX by remember(popup.dedupKey) { mutableFloatStateOf(0f) }
    var dragY by remember(popup.dedupKey) { mutableFloatStateOf(0f) }
    var lastTapOffset by remember(popup.dedupKey) { mutableStateOf(Offset.Unspecified) }

    val animatedDragXState = animateFloatAsState(
        targetValue = dragX,
        animationSpec = when {
            isDragging -> snap()
            isDismissingOut -> tween(durationMillis = 180, easing = FastOutSlowInEasing)
            else -> NodePulseMotion.spatialFloatSpring()
        },
        label = "popupDragX",
    )
    val animatedDragYState = animateFloatAsState(
        targetValue = dragY,
        animationSpec = when {
            isDragging -> snap()
            isDismissingOut -> tween(durationMillis = 175, easing = FastOutSlowInEasing)
            else -> NodePulseMotion.spatialFloatSpring()
        },
        label = "popupDragY",
    )

    val pressScaleState = animateFloatAsState(
        targetValue = if (isPressedVisual && !isActivating) 0.955f else 1f,
        animationSpec = NodePulseMotion.microBounceSpring(),
        label = "popupPressScale",
    )

    // Entrance shimmer border sweep & click halo animatables
    val sweepProgress = remember(popup.dedupKey) { Animatable(0f) }
    val clickBounceScale = remember(popup.dedupKey) { Animatable(1f) }
    val haloProgress = remember(popup.dedupKey) { Animatable(0f) }
    val activateLiftAlpha = remember(popup.dedupKey) { Animatable(1f) }

    LaunchedEffect(popup.dedupKey) {
        if (!areSystemAnimatorsEnabled()) {
            sweepProgress.snapTo(1f)
            return@LaunchedEffect
        }
        sweepProgress.snapTo(0f)
        sweepProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 920, easing = FastOutSlowInEasing),
        )
    }

    // Auto-dismiss countdown paused while user interacts or modal sheet is open
    val remainingMsState = remember(popup.dedupKey) { mutableLongStateOf(AUTO_DISMISS_DURATION_MS) }
    val isTimerPaused = paused || isInteracting || isPressedVisual || isDragging || isActivating || isDismissingOut
    LaunchedEffect(popup.dedupKey, isTimerPaused) {
        if (isTimerPaused || remainingMsState.longValue <= 0L) return@LaunchedEffect
        var lastFrameMs = withFrameMillis { it }
        while (isActive && remainingMsState.longValue > 0L) {
            val nowMs = withFrameMillis { it }
            val dt = (nowMs - lastFrameMs).coerceIn(0L, 100L)
            lastFrameMs = nowMs
            remainingMsState.longValue = (remainingMsState.longValue - dt).coerceAtLeast(0L)
        }
        if (remainingMsState.longValue <= 0L && !isActivating && !isDismissingOut) {
            latestOnDismiss()
        }
    }

    val triggerClickAndNavigate: () -> Unit = {
        if (!isActivating && !isDismissingOut) {
            isActivating = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            coroutineScope.launch {
                launch {
                    haloProgress.snapTo(0f)
                    haloProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                    )
                }
                launch {
                    clickBounceScale.animateTo(
                        targetValue = 0.945f,
                        animationSpec = tween(durationMillis = 60, easing = FastOutSlowInEasing),
                    )
                    clickBounceScale.animateTo(
                        targetValue = 1.032f,
                        animationSpec = spring(dampingRatio = 0.48f, stiffness = 620f),
                    )
                    clickBounceScale.animateTo(
                        targetValue = 0.985f,
                        animationSpec = tween(durationMillis = 60),
                    )
                }
                launch {
                    delay(120L)
                    activateLiftAlpha.animateTo(
                        targetValue = 0.85f,
                        animationSpec = tween(durationMillis = 70),
                    )
                }
                delay(CLICK_EFFECT_NAVIGATION_DELAY_MS)
                latestOnClick()
            }
        }
    }

    // Theme-specific layered surface & rim styling aligned with Cool Mist / Slate Steel / Pure Pitch
    val surfaceLow = MaterialTheme.colorScheme.surfaceContainerLow
    val surfaceLowest = MaterialTheme.colorScheme.surfaceContainerLowest
    val surfaceBrush = remember(isDark, isPurePitch, style.accentColor, ext.cardSurfaceElevated, surfaceLow, surfaceLowest) {
        when {
            isPurePitch -> Brush.verticalGradient(
                colors = listOf(
                    blendColor(ext.cardSurfaceElevated, style.accentColor, 0.14f).copy(alpha = 0.96f),
                    Color(0xFF0A0D10).copy(alpha = 0.97f),
                ),
            )
            isDark -> Brush.verticalGradient(
                colors = listOf(
                    blendColor(ext.cardSurfaceElevated, style.accentColor, 0.14f).copy(alpha = 0.96f),
                    surfaceLow.copy(alpha = 0.97f),
                ),
            )
            else -> Brush.verticalGradient(
                colors = listOf(
                    blendColor(ext.cardSurfaceElevated, style.accentColor, 0.05f).copy(alpha = 0.98f),
                    blendColor(surfaceLowest, surfaceLow, 0.35f).copy(alpha = 0.98f),
                ),
            )
        }
    }

    val baseBorderBrush = remember(isDark, isPurePitch, style.accentColor, ext.cardBorder) {
        val topRim = when {
            isPurePitch -> style.accentColor.copy(alpha = 0.50f)
            isDark -> blendColor(Color.White.copy(alpha = 0.18f), style.accentColor, 0.58f)
            else -> blendColor(Color.White, style.accentColor, 0.36f)
        }
        val bottomRim = when {
            isPurePitch -> Color(0xFF23293B)
            isDark -> ext.glassBorder
            else -> ext.cardBorder
        }
        Brush.verticalGradient(colors = listOf(topRim, bottomRim))
    }

    val shadowColor = remember(isDark, ext.softShadowColor, style.accentColor) {
        if (isDark) {
            style.accentColor.copy(alpha = 0.24f)
        } else {
            blendColor(ext.softShadowColor, style.accentColor, 0.24f)
        }
    }

    val timeDisplay = remember(popup.createdAtIso) {
        if (popup.createdAtIso.isBlank()) {
            "刚刚"
        } else {
            formatRelativeTimeChinese(popup.createdAtIso).ifBlank { "刚刚" }
        }
    }

    val subtitleSummary = remember(popup) {
        when (popup.kind) {
            InAppMessageKind.REPLY,
            InAppMessageKind.MENTION,
            -> popup.previewText.takeIf { it.isNotBlank() } ?: when {
                popup.targetFloor > 0 && popup.targetPage > 1 ->
                    "点击直达第 ${popup.targetPage} 页 #${popup.targetFloor} 楼"
                popup.targetFloor > 0 ->
                    "点击直达 #${popup.targetFloor} 楼回复位置"
                else -> "点击前往查看帖子详情"
            }

            InAppMessageKind.DIRECT_MESSAGE ->
                popup.previewText.takeIf { it.isNotBlank() } ?: "发来一条新私信，点击进入聊天"

            InAppMessageKind.SSH_BENCHMARK_COMPLETED ->
                popup.previewText.takeIf { it.isNotBlank() } ?: "测机完成，点击返回测机界面查看报告"
        }
    }

    val semanticDescription = remember(popup, style.badgeLabel, subtitleSummary) {
        "${popup.senderName} ${style.badgeLabel}：${popup.title}，$subtitleSummary"
    }

    Box(
        modifier = modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .onSizeChanged { size ->
                cardWidthPx = size.width.toFloat()
                cardHeightPx = size.height.toFloat()
            }
            .offset {
                IntOffset(
                    x = animatedDragXState.value.roundToInt(),
                    y = animatedDragYState.value.roundToInt(),
                )
            }
            .graphicsLayer {
                val curDragX = animatedDragXState.value
                val curDragY = animatedDragYState.value
                val hRatio = if (cardWidthPx > 0f) {
                    (abs(curDragX) / (cardWidthPx * 0.65f)).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val vRatio = if (curDragY < 0f && cardHeightPx > 0f) {
                    (abs(curDragY) / (cardHeightPx * 0.95f)).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val dragFadeAlpha = (1f - maxOf(hRatio, vRatio) * 0.72f).coerceIn(0.18f, 1f)
                val dragScale = if (curDragY < 0f) {
                    1f - (abs(curDragY) / 360f).coerceIn(0f, 0.06f)
                } else {
                    1f
                }
                val combinedScale = pressScaleState.value * clickBounceScale.value * dragScale
                scaleX = combinedScale
                scaleY = combinedScale
                alpha = dragFadeAlpha * activateLiftAlpha.value
                rotationZ = (curDragX / 48f).coerceIn(-4.5f, 4.5f)
            }
            .shadow(
                elevation = if (isDark) 12.dp else 11.dp,
                shape = cardShape,
                clip = false,
                ambientColor = shadowColor,
                spotColor = shadowColor,
            )
            .clip(cardShape)
            .background(surfaceBrush)
            .background(PulseBrushes.glassHighlightBrush(isDark))
            .border(width = 1.dp, brush = baseBorderBrush, shape = cardShape)
            .drawWithContent {
                drawContent()

                // 1. Entrance specular shimmer sweep across surface & border
                val sweep = sweepProgress.value
                if (sweep in 0.01f..0.99f) {
                    val bandSpan = size.width * 0.42f
                    val sweepX = -bandSpan + (size.width + bandSpan * 2f) * sweep
                    val shimmerBrush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            style.accentColor.copy(alpha = if (isDark) 0.16f else 0.12f),
                            Color.White.copy(alpha = if (isDark) 0.14f else 0.28f),
                            Color.Transparent,
                        ),
                        start = Offset(sweepX - bandSpan, 0f),
                        end = Offset(sweepX, size.height),
                    )
                    drawRect(brush = shimmerBrush)

                    val borderStrokePx = 1.5.dp.toPx()
                    val cornerPx = 22.dp.toPx()
                    val borderShimmerBrush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            style.accentColor.copy(alpha = 0.72f),
                            Color.White.copy(alpha = 0.90f),
                            style.secondaryAccentColor.copy(alpha = 0.65f),
                            Color.Transparent,
                        ),
                        start = Offset(sweepX - bandSpan, 0f),
                        end = Offset(sweepX, size.height),
                    )
                    drawRoundRect(
                        brush = borderShimmerBrush,
                        topLeft = Offset(borderStrokePx / 2f, borderStrokePx / 2f),
                        size = Size(size.width - borderStrokePx, size.height - borderStrokePx),
                        cornerRadius = CornerRadius(cornerPx, cornerPx),
                        style = Stroke(width = borderStrokePx),
                    )
                }

                // 2. Tap activation radial halo glow ripple + edge flash
                val halo = haloProgress.value
                if (halo > 0f && halo < 1f) {
                    val center = if (lastTapOffset != Offset.Unspecified) {
                        lastTapOffset
                    } else {
                        Offset(size.width / 2f, size.height / 2f)
                    }
                    val maxRadius = hypot(size.width, size.height) * 0.88f
                    val radius = (maxRadius * halo).coerceAtLeast(1f)
                    val fade = 1f - halo

                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                style.accentColor.copy(alpha = 0.28f * fade),
                                style.secondaryAccentColor.copy(alpha = 0.14f * fade),
                                Color.Transparent,
                            ),
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                        center = center,
                    )
                    drawCircle(
                        color = style.accentColor.copy(alpha = 0.62f * fade),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 2.dp.toPx() * (0.45f + 0.55f * fade)),
                    )
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = semanticDescription
                onClick(label = style.actionLabel) {
                    triggerClickAndNavigate()
                    true
                }
                dismiss(label = "关闭新消息提醒") {
                    latestOnDismiss()
                    true
                }
            }
            .pointerInput(popup.dedupKey) {
                val touchSlop = viewConfiguration.touchSlop
                val horizontalDismissPx = 74.dp.toPx()
                val verticalDismissPx = 40.dp.toPx()
                val flingVelocityPx = 620.dp.toPx()

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (isActivating || isDismissingOut) return@awaitEachGesture

                    isInteracting = true
                    isPressedVisual = true
                    lastTapOffset = down.position

                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)

                    var dragAxis = 0 // 0 = tap candidate, 1 = horizontal, 2 = vertical-up
                    var totalDx = 0f
                    var totalDy = 0f
                    var lastPos = down.position
                    var completedTap = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull()
                        if (change == null) break

                        if (!change.pressed) {
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            completedTap = dragAxis == 0 && !change.isConsumed
                            break
                        }

                        val delta = change.position - lastPos
                        lastPos = change.position
                        velocityTracker.addPosition(change.uptimeMillis, change.position)

                        if (dragAxis == 0) {
                            totalDx += delta.x
                            totalDy += delta.y
                            val absDx = abs(totalDx)
                            val absDy = abs(totalDy)
                            if (absDx > touchSlop && absDx > absDy * 1.12f) {
                                dragAxis = 1
                                isPressedVisual = false
                                isDragging = true
                                change.consume()
                                dragX = totalDx
                            } else if (totalDy < -touchSlop && absDy > absDx) {
                                dragAxis = 2
                                isPressedVisual = false
                                isDragging = true
                                change.consume()
                                dragY = totalDy
                            } else if (totalDy > touchSlop * 2.5f) {
                                isPressedVisual = false
                            }
                        } else {
                            change.consume()
                            if (dragAxis == 1) {
                                dragX += delta.x
                            } else if (dragAxis == 2) {
                                dragY = (dragY + delta.y).coerceAtMost(10.dp.toPx())
                            }
                        }
                    }

                    isPressedVisual = false
                    isDragging = false
                    isInteracting = false

                    if (dragAxis == 1) {
                        val vx = velocityTracker.calculateVelocity().x
                        val shouldDismiss = abs(dragX) >= horizontalDismissPx ||
                            (abs(vx) >= flingVelocityPx && sign(vx) == sign(dragX))
                        if (shouldDismiss) {
                            isDismissingOut = true
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val dir = if (abs(dragX) > 4f) sign(dragX) else sign(vx)
                            dragX = dir * (cardWidthPx.coerceAtLeast(600f) + 140f)
                            coroutineScope.launch {
                                delay(175L)
                                latestOnDismiss()
                            }
                        } else {
                            dragX = 0f
                        }
                    } else if (dragAxis == 2) {
                        val vy = velocityTracker.calculateVelocity().y
                        val shouldDismiss = dragY <= -verticalDismissPx || vy <= -flingVelocityPx
                        if (shouldDismiss) {
                            isDismissingOut = true
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            dragY = -(cardHeightPx.coerceAtLeast(200f) + 80f)
                            coroutineScope.launch {
                                delay(170L)
                                latestOnDismiss()
                            }
                        } else {
                            dragY = 0f
                        }
                    } else if (completedTap && totalDy <= touchSlop * 2.2f) {
                        triggerClickAndNavigate()
                    }
                }
            },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top luminous progress bar (draw-phase only, zero per-frame recomposition)
            TopLuminousProgressBar(
                progressProvider = {
                    (remainingMsState.longValue.toFloat() / AUTO_DISMISS_DURATION_MS.toFloat())
                        .coerceIn(0f, 1f)
                },
                accentColor = style.accentColor,
                secondaryAccentColor = style.secondaryAccentColor,
                isDark = isDark,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 13.dp, end = 12.dp, top = 11.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                // Sender avatar with corner semantic icon indicator
                Box(modifier = Modifier.size(44.dp)) {
                    PulseAvatar(
                        avatarUrl = popup.senderAvatarUrl,
                        username = popup.senderName,
                        size = 42.dp,
                        ringWidth = 1.6.dp,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(19.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(style.accentColor, style.secondaryAccentColor),
                                ),
                            )
                            .border(
                                width = 1.5.dp,
                                color = MaterialTheme.colorScheme.background,
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = style.icon,
                            contentDescription = null,
                            tint = if (isDark) Color(0xFF101417) else Color.White,
                            modifier = Modifier.size(10.dp),
                        )
                    }
                }

                // Sender header, semantic badge, floor/page tag, title & excerpt
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            text = popup.senderName,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(style.badgeContainerColor)
                                .border(
                                    width = 0.7.dp,
                                    color = style.badgeBorderColor,
                                    shape = RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 5.5.dp, vertical = 1.5.dp),
                        ) {
                            Text(
                                text = style.badgeLabel,
                                style = NodePulseThemeTokens.extraTypography.microLabel.copy(
                                    fontSize = (9.8f * fontScale).sp,
                                    lineHeight = (12f * fontScale).sp,
                                ),
                                color = style.badgeContentColor,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }

                        if (popup.kind != InAppMessageKind.DIRECT_MESSAGE && popup.targetFloor > 0) {
                            val floorBadgeText = if (popup.targetPage > 1) {
                                "P${popup.targetPage} · #${popup.targetFloor}"
                            } else {
                                "#${popup.targetFloor}"
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(
                                            alpha = if (isDark) 0.90f else 0.92f,
                                        ),
                                    )
                                    .border(
                                        width = 0.7.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                        shape = RoundedCornerShape(6.dp),
                                    )
                                    .padding(horizontal = 5.dp, vertical = 1.5.dp),
                            ) {
                                Text(
                                    text = floorBadgeText,
                                    style = NodePulseThemeTokens.extraTypography.tabularBadge.copy(
                                        fontSize = (9.8f * fontScale).sp,
                                        lineHeight = (12f * fontScale).sp,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }

                        Text(
                            text = timeDisplay,
                            style = MaterialTheme.typography.labelSmall.withTabularNumbers().copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = (10f * fontScale).sp,
                                lineHeight = (13f * fontScale).sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }

                    if (popup.kind != InAppMessageKind.DIRECT_MESSAGE && popup.title.isNotBlank()) {
                        Text(
                            text = popup.title,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Text(
                        text = subtitleSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (popup.kind == InAppMessageKind.DIRECT_MESSAGE) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = if (popup.kind == InAppMessageKind.DIRECT_MESSAGE) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Right column: optional queue counter + direct navigation capsule arrow
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (queuedCount > 0) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(style.accentColor.copy(alpha = if (isDark) 0.22f else 0.14f))
                                .border(
                                    width = 0.7.dp,
                                    color = style.accentColor.copy(alpha = 0.35f),
                                    shape = CircleShape,
                                )
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = "+$queuedCount",
                                style = NodePulseThemeTokens.extraTypography.tabularBadge.copy(
                                    fontSize = (9.5f * fontScale).sp,
                                    lineHeight = (12f * fontScale).sp,
                                ),
                                color = style.accentColor,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        style.accentColor.copy(alpha = if (isDark) 0.24f else 0.14f),
                                        style.secondaryAccentColor.copy(alpha = if (isDark) 0.18f else 0.10f),
                                    ),
                                ),
                            )
                            .border(
                                width = 0.8.dp,
                                color = style.accentColor.copy(alpha = if (isDark) 0.42f else 0.28f),
                                shape = CircleShape,
                            )
                            .padding(start = 8.dp, end = 4.dp, top = 4.5.dp, bottom = 4.5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            text = style.actionLabel,
                            style = NodePulseThemeTokens.extraTypography.microLabel.copy(
                                fontSize = (10.5f * fontScale).sp,
                                lineHeight = (13f * fontScale).sp,
                            ),
                            color = if (isDark) style.badgeContentColor else style.accentColor,
                            maxLines = 1,
                            softWrap = false,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = if (isDark) style.badgeContentColor else style.accentColor,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }

            // Subtle swipe-dismiss handle pill
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .height(2.5.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.outlineVariant.copy(
                                alpha = if (isDark) 0.45f else 0.62f,
                            ),
                        ),
                )
            }
        }
    }
}

@Composable
private fun TopLuminousProgressBar(
    progressProvider: () -> Float,
    accentColor: Color,
    secondaryAccentColor: Color,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(2.75.dp),
    ) {
        val fraction = progressProvider().coerceIn(0f, 1f)
        drawRect(
            color = accentColor.copy(alpha = if (isDark) 0.16f else 0.10f),
        )
        val activeWidth = size.width * fraction
        if (activeWidth > 1f) {
            val cy = size.height / 2f
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.85f),
                        secondaryAccentColor,
                        Color.White.copy(alpha = if (isDark) 0.88f else 0.95f),
                    ),
                    startX = 0f,
                    endX = activeWidth,
                ),
                start = Offset(0f, cy),
                end = Offset(activeWidth, cy),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
            // Tiny luminous tip highlight
            drawCircle(
                color = Color.White.copy(alpha = if (isDark) 0.82f else 0.90f),
                radius = size.height * 0.65f,
                center = Offset(activeWidth, cy),
            )
        }
    }
}
