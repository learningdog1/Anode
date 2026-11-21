package com.nodeseek.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Decoupled UI theme mode enum so `ui/theme` compiles cleanly without `domain`.
 */
enum class PulseThemeMode {
    SYSTEM,
    PAPER_LIGHT,
    OBSIDIAN_DARK,
}

val LocalNodePulseExtendedColors = staticCompositionLocalOf { LightExtendedColors }
val LocalNodePulseExtraTypography = staticCompositionLocalOf { buildNodePulseExtraTypography(0.90f) }
val LocalFontScale = staticCompositionLocalOf { 0.90f }
val LocalNodePulseDarkTheme = staticCompositionLocalOf { false }
val LocalNodePulseDynamicColor = staticCompositionLocalOf { false }

/**
 * Ergonomic accessor for extended Anode design tokens inside any `@Composable`.
 */
object NodePulseThemeTokens {
    val extendedColors: NodePulseExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalNodePulseExtendedColors.current

    val extraTypography: NodePulseExtraTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalNodePulseExtraTypography.current

    val fontScale: Float
        @Composable
        @ReadOnlyComposable
        get() = LocalFontScale.current

    val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalNodePulseDarkTheme.current

    val isDynamicColorActive: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalNodePulseDynamicColor.current

    @Composable
    @ReadOnlyComposable
    fun categorySpec(slug: String): CategoryVisualSpec {
        val harmonizeTarget = if (isDynamicColorActive) MaterialTheme.colorScheme.primary else null
        return CategoryPalette.resolve(
            slug = slug,
            isDark = isDark,
            dynamicPrimaryToHarmonize = harmonizeTarget,
        )
    }

    @Composable
    @ReadOnlyComposable
    fun rankSpec(level: Int): RankVisualSpec {
        val harmonizeTarget = if (isDynamicColorActive) MaterialTheme.colorScheme.primary else null
        return RankPalette.forLevel(
            level = level,
            isDark = isDark,
            dynamicPrimaryToHarmonize = harmonizeTarget,
        )
    }
}

@Composable
fun NodePulseTheme(
    themeMode: PulseThemeMode = PulseThemeMode.SYSTEM,
    purePitchAmoled: Boolean = false,
    dynamicColor: Boolean = false,
    fontScale: Float = 0.90f,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        PulseThemeMode.SYSTEM -> systemDark
        PulseThemeMode.PAPER_LIGHT -> false
        PulseThemeMode.OBSIDIAN_DARK -> true
    }

    val context = LocalContext.current
    val dynamicSupported = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val baseScheme: ColorScheme = when {
        dynamicSupported && useDark -> dynamicDarkColorScheme(context)
        dynamicSupported && !useDark -> dynamicLightColorScheme(context)
        useDark -> ObsidianDarkColorScheme
        else -> PaperLightColorScheme
    }

    val colorScheme = if (useDark && purePitchAmoled) {
        baseScheme.withPurePitchAmoled()
    } else {
        baseScheme
    }

    val extendedColors = remember(
        useDark,
        purePitchAmoled,
        dynamicSupported,
        colorScheme.primary,
        colorScheme.secondary,
        colorScheme.background,
        colorScheme.outlineVariant,
        colorScheme.surfaceContainerLowest,
        colorScheme.surfaceContainer,
        colorScheme.surfaceContainerHigh,
        colorScheme.surfaceContainerHighest,
    ) {
        val raw = if (useDark) {
            if (purePitchAmoled) {
                DarkExtendedColors.copy(
                    cardSurface = ColorSchemePitchCard,
                    cardSurfaceElevated = Color(0xFF14181C),
                    cardBorder = if (dynamicSupported) colorScheme.outlineVariant.copy(alpha = 0.72f) else Color(0xFF272D32),
                    cardHighlightRim = Color.White.copy(alpha = 0.08f),
                    glassSurface = Color(0xFF070A0D).copy(alpha = 0.90f),
                    glassBorder = if (dynamicSupported) colorScheme.outlineVariant.copy(alpha = 0.68f) else Color(0xFF272D32),
                    shimmerBase = Color(0xFF111518),
                    shimmerHighlight = Color(0xFF1F2429),
                )
            } else if (dynamicSupported) {
                DarkExtendedColors.copy(
                    cardSurface = colorScheme.surfaceContainer,
                    cardSurfaceElevated = colorScheme.surfaceContainerHigh,
                    cardBorder = colorScheme.outlineVariant.copy(alpha = 0.78f),
                    glassSurface = colorScheme.background.copy(alpha = 0.86f),
                    glassBorder = colorScheme.outlineVariant.copy(alpha = 0.62f),
                    shimmerBase = colorScheme.surfaceContainer,
                    shimmerHighlight = colorScheme.surfaceContainerHighest,
                )
            } else {
                DarkExtendedColors.copy(
                    cardSurface = colorScheme.surfaceContainer,
                    cardSurfaceElevated = colorScheme.surfaceContainerHigh,
                )
            }
        } else if (dynamicSupported) {
            LightExtendedColors.copy(
                cardSurface = colorScheme.surfaceContainerLowest,
                cardSurfaceElevated = colorScheme.surfaceContainerLowest,
                cardBorder = colorScheme.outlineVariant.copy(alpha = 0.68f),
                glassSurface = colorScheme.background.copy(alpha = 0.88f),
                glassBorder = colorScheme.outlineVariant.copy(alpha = 0.72f),
                shimmerBase = colorScheme.surfaceContainerHigh,
                shimmerHighlight = colorScheme.surfaceContainerLowest,
                softShadowColor = colorScheme.primary.copy(alpha = 0.08f),
            )
        } else {
            LightExtendedColors
        }
        if (dynamicSupported) {
            raw.harmonizeWith(colorScheme.primary, fraction = 0.14f).copy(
                brandGradientStart = colorScheme.primary,
                brandGradientEnd = blendColor(colorScheme.primary, colorScheme.secondary, 0.45f),
                heroTintStart = colorScheme.primary.copy(alpha = if (useDark) 0.16f else 0.09f),
                heroTintEnd = colorScheme.secondary.copy(alpha = if (useDark) 0.08f else 0.055f),
            )
        } else {
            raw
        }
    }

    val clampedScale = fontScale.coerceIn(0.85f, 1.35f)
    val typography = remember(clampedScale) { buildNodePulseTypography(clampedScale) }
    val extraTypography = remember(clampedScale) { buildNodePulseExtraTypography(clampedScale) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !useDark
                controller.isAppearanceLightNavigationBars = !useDark
            }
        }
    }

    CompositionLocalProvider(
        LocalNodePulseExtendedColors provides extendedColors,
        LocalNodePulseExtraTypography provides extraTypography,
        LocalFontScale provides clampedScale,
        LocalNodePulseDarkTheme provides useDark,
        LocalNodePulseDynamicColor provides dynamicSupported,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = NodePulseShapes,
            content = content,
        )
    }
}

private val ColorSchemePitchCard = Color(0xFF0C1013)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
