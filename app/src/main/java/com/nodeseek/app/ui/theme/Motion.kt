package com.nodeseek.app.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset

/**
 * Material 3 Expressive spring physics & transition tokens for NodePulse.
 */
object NodePulseMotion {
    private const val SPATIAL_DAMPING = 0.72f
    private const val SPATIAL_STIFFNESS = 380f

    private const val MICRO_BOUNCE_DAMPING = 0.55f
    private const val MICRO_BOUNCE_STIFFNESS = 520f

    private const val QUICK_DAMPING = 0.78f
    private const val QUICK_STIFFNESS = 560f

    const val cardPressScale: Float = 0.975f
    const val chipPressScale: Float = 0.94f

    fun spatialDpSpring(): SpringSpec<Dp> = spring(
        dampingRatio = SPATIAL_DAMPING,
        stiffness = SPATIAL_STIFFNESS,
    )

    fun spatialFloatSpring(): SpringSpec<Float> = spring(
        dampingRatio = SPATIAL_DAMPING,
        stiffness = SPATIAL_STIFFNESS,
    )

    fun spatialOffsetSpring(): SpringSpec<IntOffset> = spring(
        dampingRatio = SPATIAL_DAMPING,
        stiffness = SPATIAL_STIFFNESS,
    )

    fun microBounceSpring(): SpringSpec<Float> = spring(
        dampingRatio = MICRO_BOUNCE_DAMPING,
        stiffness = MICRO_BOUNCE_STIFFNESS,
    )

    fun quickSpring(): SpringSpec<Float> = spring(
        dampingRatio = QUICK_DAMPING,
        stiffness = QUICK_STIFFNESS,
    )

    fun subtleTween(durationMillis: Int = 200): TweenSpec<Float> = tween(
        durationMillis = durationMillis,
        easing = FastOutSlowInEasing,
    )

    fun entranceFadeTween(
        durationMillis: Int = 240,
        delayMillis: Int = 0,
    ): TweenSpec<Float> = tween(
        durationMillis = durationMillis,
        delayMillis = delayMillis,
        easing = FastOutSlowInEasing,
    )
}
