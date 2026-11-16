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
val LocalNodePulseExtraTypography = staticCompositionLocalOf { buildNodePulseExtraTypography(1.0f) }
val LocalFontScale = staticCompositionLocalOf { 1.0f }
val LocalNodePulseDarkTheme = staticCompositionLocalOf { false }
val LocalNodePulseDynamicColor = staticCompositionLocalOf { false }

/**
 * Ergonomic accessor for extended NodePulse design tokens inside any `@Composable`.
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
    dynamicColor: Boolean = true,
    fontScale: Float = 1.0f,
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
        colorScheme.surfaceContainer,
        colorScheme.surfaceContainerHigh,
    ) {
        val raw = if (useDark) {
            if (purePitchAmoled) {
                DarkExtendedColors.copy(
                    cardSurface = ColorSchemePitchCard,
                    cardSurfaceElevated = Color(0xFF131722),
                    cardBorder = Color(0xFF232839),
                    cardHighlightRim = Color.White.copy(alpha = 0.08f),
                    glassSurface = Color(0xFF07090E).copy(alpha = 0.90f),
                    glassBorder = Color(0xFF262B3D),
                    shimmerBase = Color(0xFF11141E),
                    shimmerHighlight = Color(0xFF1E2436),
                )
            } else {
                DarkExtendedColors.copy(
                    cardSurface = colorScheme.surfaceContainer,
                    cardSurfaceElevated = colorScheme.surfaceContainerHigh,
                )
            }
        } else {
            LightExtendedColors
        }
        if (dynamicSupported) raw.harmonizeWith(colorScheme.primary, fraction = 0.12f) else raw
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

private val ColorSchemePitchCard = Color(0xFF0B0E14)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
