package com.nodeseek.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * NodePulse ("Cool Mist Light & Slate Steel Dark") Color Tokens.
 *
 * Core Material 3 color palette:
 * - Cool Mist Ice-Blue Light (#F7FAFF canvas, #FFFFFF crisp sheet, #2D637E Steel Teal-Blue)
 * - Slate Steel Dark (#101417 / #000000 Pitch, #1C2024 slate container, #9ACAEC Sky Blue)
 * - Harmonized accents, semantic heat & Drumstick Gold tokens across Light, Dark & Pure Pitch modes.
 */

// ── Cool Mist Ice-Blue (Light) ────────────────────────────────────────────────
val PaperPrimary = Color(0xFF2D637E)
val PaperOnPrimary = Color(0xFFFFFFFF)
val PaperPrimaryContainer = Color(0xFFCDE7FB)
val PaperOnPrimaryContainer = Color(0xFF001E2E)
val PaperInversePrimary = Color(0xFF9ACAEC)

val PaperSecondary = Color(0xFF4E616D)
val PaperOnSecondary = Color(0xFFFFFFFF)
val PaperSecondaryContainer = Color(0xFFD1E5F3)
val PaperOnSecondaryContainer = Color(0xFF0A1E28)

val PaperTertiary = Color(0xFF605A7D)
val PaperOnTertiary = Color(0xFFFFFFFF)
val PaperTertiaryContainer = Color(0xFFE6DEFF)
val PaperOnTertiaryContainer = Color(0xFF1C1736)

val PaperBackground = Color(0xFFF7FAFF)
val PaperOnBackground = Color(0xFF171C1F)
val PaperSurface = Color(0xFFF7FAFF)
val PaperOnSurface = Color(0xFF171C1F)
val PaperSurfaceVariant = Color(0xFFDCE3EA)
val PaperOnSurfaceVariant = Color(0xFF40484D)

val PaperSurfaceContainerLowest = Color(0xFFFFFFFF)
val PaperSurfaceContainerLow = Color(0xFFF1F4F9)
val PaperSurfaceContainer = Color(0xFFEBEEF3)
val PaperSurfaceContainerHigh = Color(0xFFE5E8EE)
val PaperSurfaceContainerHighest = Color(0xFFDFE3E8)

val PaperOutline = Color(0xFF70787E)
val PaperOutlineVariant = Color(0xFFC0C7CD)
val PaperInverseSurface = Color(0xFF2C3135)
val PaperInverseOnSurface = Color(0xFFEEF1F6)

val PaperError = Color(0xFFBA1A1A)
val PaperOnError = Color(0xFFFFFFFF)
val PaperErrorContainer = Color(0xFFFFDAD6)
val PaperOnErrorContainer = Color(0xFF410002)

// ── Slate Steel Telemetry (Dark / AMOLED) ─────────────────────────────────────
val ObsidianPrimary = Color(0xFF9ACAEC)
val ObsidianOnPrimary = Color(0xFF00344B)
val ObsidianPrimaryContainer = Color(0xFF114B66)
val ObsidianOnPrimaryContainer = Color(0xFFCDE7FB)
val ObsidianInversePrimary = Color(0xFF2D637E)

val ObsidianSecondary = Color(0xFFB5C9D7)
val ObsidianOnSecondary = Color(0xFF20333E)
val ObsidianSecondaryContainer = Color(0xFF364955)
val ObsidianOnSecondaryContainer = Color(0xFFD1E5F3)

val ObsidianTertiary = Color(0xFFCAC1E9)
val ObsidianOnTertiary = Color(0xFF322C4C)
val ObsidianTertiaryContainer = Color(0xFF494264)
val ObsidianOnTertiaryContainer = Color(0xFFE6DEFF)

val ObsidianBackground = Color(0xFF101417)
val PurePitchBlack = Color(0xFF000000)
val ObsidianOnBackground = Color(0xFFDFE3E7)
val ObsidianSurface = Color(0xFF101417)
val ObsidianOnSurface = Color(0xFFDFE3E7)
val ObsidianSurfaceVariant = Color(0xFF40484D)
val ObsidianOnSurfaceVariant = Color(0xFFC0C7CD)

val ObsidianSurfaceContainerLowest = Color(0xFF0B0F12)
val ObsidianSurfaceContainerLow = Color(0xFF181C1F)
val ObsidianSurfaceContainer = Color(0xFF1C2024)
val ObsidianSurfaceContainerHigh = Color(0xFF262A2E)
val ObsidianSurfaceContainerHighest = Color(0xFF313539)

val ObsidianOutline = Color(0xFF8A9297)
val ObsidianOutlineVariant = Color(0xFF40484D)
val ObsidianInverseSurface = Color(0xFFDFE3E7)
val ObsidianInverseOnSurface = Color(0xFF2C3135)

val ObsidianError = Color(0xFFFFB4AB)
val ObsidianOnError = Color(0xFF690005)
val ObsidianErrorContainer = Color(0xFF93000A)
val ObsidianOnErrorContainer = Color(0xFFFFDAD6)

val PaperLightColorScheme: ColorScheme = lightColorScheme(
    primary = PaperPrimary,
    onPrimary = PaperOnPrimary,
    primaryContainer = PaperPrimaryContainer,
    onPrimaryContainer = PaperOnPrimaryContainer,
    inversePrimary = PaperInversePrimary,
    secondary = PaperSecondary,
    onSecondary = PaperOnSecondary,
    secondaryContainer = PaperSecondaryContainer,
    onSecondaryContainer = PaperOnSecondaryContainer,
    tertiary = PaperTertiary,
    onTertiary = PaperOnTertiary,
    tertiaryContainer = PaperTertiaryContainer,
    onTertiaryContainer = PaperOnTertiaryContainer,
    background = PaperBackground,
    onBackground = PaperOnBackground,
    surface = PaperSurface,
    onSurface = PaperOnSurface,
    surfaceVariant = PaperSurfaceVariant,
    onSurfaceVariant = PaperOnSurfaceVariant,
    surfaceTint = PaperPrimary,
    inverseSurface = PaperInverseSurface,
    inverseOnSurface = PaperInverseOnSurface,
    error = PaperError,
    onError = PaperOnError,
    errorContainer = PaperErrorContainer,
    onErrorContainer = PaperOnErrorContainer,
    outline = PaperOutline,
    outlineVariant = PaperOutlineVariant,
    scrim = Color(0xFF000000),
    surfaceBright = PaperSurface,
    surfaceDim = Color(0xFFD7DADF),
    surfaceContainerLowest = PaperSurfaceContainerLowest,
    surfaceContainerLow = PaperSurfaceContainerLow,
    surfaceContainer = PaperSurfaceContainer,
    surfaceContainerHigh = PaperSurfaceContainerHigh,
    surfaceContainerHighest = PaperSurfaceContainerHighest,
)

val ObsidianDarkColorScheme: ColorScheme = darkColorScheme(
    primary = ObsidianPrimary,
    onPrimary = ObsidianOnPrimary,
    primaryContainer = ObsidianPrimaryContainer,
    onPrimaryContainer = ObsidianOnPrimaryContainer,
    inversePrimary = ObsidianInversePrimary,
    secondary = ObsidianSecondary,
    onSecondary = ObsidianOnSecondary,
    secondaryContainer = ObsidianSecondaryContainer,
    onSecondaryContainer = ObsidianOnSecondaryContainer,
    tertiary = ObsidianTertiary,
    onTertiary = ObsidianOnTertiary,
    tertiaryContainer = ObsidianTertiaryContainer,
    onTertiaryContainer = ObsidianOnTertiaryContainer,
    background = ObsidianBackground,
    onBackground = ObsidianOnBackground,
    surface = ObsidianSurface,
    onSurface = ObsidianOnSurface,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = ObsidianOnSurfaceVariant,
    surfaceTint = ObsidianPrimary,
    inverseSurface = ObsidianInverseSurface,
    inverseOnSurface = ObsidianInverseOnSurface,
    error = ObsidianError,
    onError = ObsidianOnError,
    errorContainer = ObsidianErrorContainer,
    onErrorContainer = ObsidianOnErrorContainer,
    outline = ObsidianOutline,
    outlineVariant = ObsidianOutlineVariant,
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF363A3E),
    surfaceDim = ObsidianBackground,
    surfaceContainerLowest = ObsidianSurfaceContainerLowest,
    surfaceContainerLow = ObsidianSurfaceContainerLow,
    surfaceContainer = ObsidianSurfaceContainer,
    surfaceContainerHigh = ObsidianSurfaceContainerHigh,
    surfaceContainerHighest = ObsidianSurfaceContainerHighest,
)

fun ColorScheme.withPurePitchAmoled(): ColorScheme = copy(
    background = PurePitchBlack,
    surface = PurePitchBlack,
    surfaceDim = PurePitchBlack,
    surfaceContainerLowest = PurePitchBlack,
    surfaceContainerLow = Color(0xFF0A0D10),
    surfaceContainer = Color(0xFF111518),
    surfaceContainerHigh = Color(0xFF191D21),
    surfaceContainerHighest = Color(0xFF23272B),
)

/**
 * Extended semantic colors for rewards (鸡腿 / 星尘), thread heat, editorial cards,
 * and terminal/code blocks.
 */
@Immutable
data class NodePulseExtendedColors(
    val cardSurface: Color,
    val cardBorder: Color,
    val drumstickGoldStart: Color,
    val drumstickGoldEnd: Color,
    val drumstickContainer: Color,
    val onDrumstickContainer: Color,
    val stardustAccent: Color,
    val stardustContainer: Color,
    val onStardustContainer: Color,
    val heatContainer: Color,
    val onHeatContainer: Color,
    val codeSurface: Color,
    val codeHeaderSurface: Color,
    val codeBorder: Color,
    val onCodeSurface: Color,
    val onCodeMuted: Color,
    val readTitleAlpha: Float,
    val cardSurfaceElevated: Color = cardSurface,
    val cardHighlightRim: Color = Color.Transparent,
    val glassSurface: Color = cardSurface,
    val glassBorder: Color = cardBorder,
    val brandGradientStart: Color = PaperPrimary,
    val brandGradientEnd: Color = PaperSecondary,
    val heroTintStart: Color = Color.Transparent,
    val heroTintEnd: Color = Color.Transparent,
    val shimmerBase: Color = cardSurface,
    val shimmerHighlight: Color = cardSurface,
    val softShadowColor: Color = Color(0x14000000),
) {
    fun harmonizeWith(primary: Color, fraction: Float = 0.12f): NodePulseExtendedColors = copy(
        drumstickGoldStart = blendColor(drumstickGoldStart, primary, fraction),
        drumstickGoldEnd = blendColor(drumstickGoldEnd, primary, fraction),
        drumstickContainer = blendColor(drumstickContainer, primary, fraction),
        stardustAccent = blendColor(stardustAccent, primary, fraction),
        stardustContainer = blendColor(stardustContainer, primary, fraction),
        heatContainer = blendColor(heatContainer, primary, fraction),
        brandGradientStart = blendColor(brandGradientStart, primary, fraction * 2f),
        heroTintStart = blendColor(heroTintStart, primary.copy(alpha = heroTintStart.alpha), fraction * 2f),
    )
}

val LightExtendedColors = NodePulseExtendedColors(
    cardSurface = PaperSurfaceContainerLowest,
    cardBorder = PaperOutlineVariant.copy(alpha = 0.68f),
    drumstickGoldStart = Color(0xFFF59E0B),
    drumstickGoldEnd = Color(0xFFD97706),
    drumstickContainer = Color(0xFFFFF4DC),
    onDrumstickContainer = Color(0xFF9A5200),
    stardustAccent = Color(0xFF6D28D9),
    stardustContainer = Color(0xFFEDE7FF),
    onStardustContainer = Color(0xFF5B21B6),
    heatContainer = Color(0xFFFFE8DF),
    onHeatContainer = Color(0xFFC2410C),
    codeSurface = Color(0xFF12151E),
    codeHeaderSurface = Color(0xFF1A1E2B),
    codeBorder = Color(0xFF2A3042),
    onCodeSurface = Color(0xFFE6EDF3),
    onCodeMuted = Color(0xFF8B949E),
    readTitleAlpha = 0.66f,
    cardSurfaceElevated = Color(0xFFFFFFFF),
    cardHighlightRim = Color.White.copy(alpha = 0.92f),
    glassSurface = PaperBackground.copy(alpha = 0.88f),
    glassBorder = PaperOutlineVariant.copy(alpha = 0.72f),
    brandGradientStart = PaperPrimary,
    brandGradientEnd = blendColor(PaperPrimary, PaperSecondary, 0.45f),
    heroTintStart = PaperPrimary.copy(alpha = 0.09f),
    heroTintEnd = PaperSecondary.copy(alpha = 0.055f),
    shimmerBase = PaperSurfaceContainerHigh,
    shimmerHighlight = PaperSurfaceContainerLowest,
    softShadowColor = PaperPrimary.copy(alpha = 0.08f),
)

val DarkExtendedColors = NodePulseExtendedColors(
    cardSurface = ObsidianSurfaceContainer,
    cardBorder = ObsidianOutlineVariant.copy(alpha = 0.78f),
    drumstickGoldStart = Color(0xFFFCD34D),
    drumstickGoldEnd = Color(0xFFF59E0B),
    drumstickContainer = Color(0xFF2B200E),
    onDrumstickContainer = Color(0xFFFBBF24),
    stardustAccent = Color(0xFFC4B5FD),
    stardustContainer = Color(0xFF241B42),
    onStardustContainer = Color(0xFFDDD6FE),
    heatContainer = Color(0xFF361B15),
    onHeatContainer = Color(0xFFFB923C),
    codeSurface = Color(0xFF0E1118),
    codeHeaderSurface = Color(0xFF161B26),
    codeBorder = Color(0xFF262D40),
    onCodeSurface = Color(0xFFE6EDF3),
    onCodeMuted = Color(0xFF8B949E),
    readTitleAlpha = 0.66f,
    cardSurfaceElevated = ObsidianSurfaceContainerHigh,
    cardHighlightRim = Color.White.copy(alpha = 0.09f),
    glassSurface = ObsidianBackground.copy(alpha = 0.86f),
    glassBorder = ObsidianOutlineVariant.copy(alpha = 0.62f),
    brandGradientStart = ObsidianPrimary,
    brandGradientEnd = blendColor(ObsidianPrimary, ObsidianSecondary, 0.45f),
    heroTintStart = ObsidianPrimary.copy(alpha = 0.16f),
    heroTintEnd = ObsidianSecondary.copy(alpha = 0.08f),
    shimmerBase = ObsidianSurfaceContainer,
    shimmerHighlight = ObsidianSurfaceContainerHighest,
    softShadowColor = Color(0x66000000),
)

/**
 * Pure Kotlin sRGB linear interpolation (works identically on Android & JVM unit tests).
 */
fun blendColor(source: Color, target: Color, fraction: Float): Color {
    val t = fraction.coerceIn(0f, 1f)
    val inv = 1f - t
    val r = ((source.red * inv + target.red * t) * 255f).roundToInt().coerceIn(0, 255)
    val g = ((source.green * inv + target.green * t) * 255f).roundToInt().coerceIn(0, 255)
    val b = ((source.blue * inv + target.blue * t) * 255f).roundToInt().coerceIn(0, 255)
    val a = ((source.alpha * inv + target.alpha * t) * 255f).roundToInt().coerceIn(0, 255)
    return Color(red = r, green = g, blue = b, alpha = a)
}
