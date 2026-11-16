package com.nodeseek.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * NodePulse ("Editorial Paper & Obsidian Telemetry") Color Tokens.
 *
 * Core Material 3 Expressive color palette:
 * - Warm Editorial Paper Light (#FAF7F2 parchment, #FFFFFF crisp sheet, #3655D6 Electric Cobalt)
 * - Deep Obsidian AMOLED Dark (#07080C / #000000 Pitch, #151821 slate container, #8DA2FF Periwinkle)
 * - Electric Cobalt, Cyan Pulse & Drumstick Gold brand accents across Light, Dark & Pure Pitch modes.
 */

// ── Warm Editorial Paper (Light) ──────────────────────────────────────────────
val PaperPrimary = Color(0xFF3655D6)
val PaperOnPrimary = Color(0xFFFFFFFF)
val PaperPrimaryContainer = Color(0xFFDDE3FF)
val PaperOnPrimaryContainer = Color(0xFF0B1D6E)
val PaperInversePrimary = Color(0xFF8DA2FF)

val PaperSecondary = Color(0xFF0B7A75)
val PaperOnSecondary = Color(0xFFFFFFFF)
val PaperSecondaryContainer = Color(0xFFCCF3EE)
val PaperOnSecondaryContainer = Color(0xFF00201E)

val PaperTertiary = Color(0xFFC86D00)
val PaperOnTertiary = Color(0xFFFFFFFF)
val PaperTertiaryContainer = Color(0xFFFFDFB8)
val PaperOnTertiaryContainer = Color(0xFF3E1E00)

val PaperBackground = Color(0xFFFAF7F2)
val PaperOnBackground = Color(0xFF1B1B1F)
val PaperSurface = Color(0xFFFAF7F2)
val PaperOnSurface = Color(0xFF1B1B1F)
val PaperSurfaceVariant = Color(0xFFEBE5DA)
val PaperOnSurfaceVariant = Color(0xFF5A5863)

val PaperSurfaceContainerLowest = Color(0xFFFFFFFF)
val PaperSurfaceContainerLow = Color(0xFFF5F1EA)
val PaperSurfaceContainer = Color(0xFFEFEAE1)
val PaperSurfaceContainerHigh = Color(0xFFE8E2D7)
val PaperSurfaceContainerHighest = Color(0xFFDFD8CB)

val PaperOutline = Color(0xFF84818C)
val PaperOutlineVariant = Color(0xFFDBD5C9)
val PaperInverseSurface = Color(0xFF2F3036)
val PaperInverseOnSurface = Color(0xFFF2EFF7)

val PaperError = Color(0xFFBA1A1A)
val PaperOnError = Color(0xFFFFFFFF)
val PaperErrorContainer = Color(0xFFFFDAD6)
val PaperOnErrorContainer = Color(0xFF410002)

// ── Deep Obsidian Telemetry (Dark / AMOLED) ───────────────────────────────────
val ObsidianPrimary = Color(0xFF8DA2FF)
val ObsidianOnPrimary = Color(0xFF071E72)
val ObsidianPrimaryContainer = Color(0xFF213C9E)
val ObsidianOnPrimaryContainer = Color(0xFFDDE3FF)
val ObsidianInversePrimary = Color(0xFF3655D6)

val ObsidianSecondary = Color(0xFF4FD8CE)
val ObsidianOnSecondary = Color(0xFF003734)
val ObsidianSecondaryContainer = Color(0xFF00504C)
val ObsidianOnSecondaryContainer = Color(0xFF92F4EC)

val ObsidianTertiary = Color(0xFFFFB951)
val ObsidianOnTertiary = Color(0xFF452B00)
val ObsidianTertiaryContainer = Color(0xFF633F00)
val ObsidianOnTertiaryContainer = Color(0xFFFFDDB3)

val ObsidianBackground = Color(0xFF07080C)
val PurePitchBlack = Color(0xFF000000)
val ObsidianOnBackground = Color(0xFFEAE8EF)
val ObsidianSurface = Color(0xFF07080C)
val ObsidianOnSurface = Color(0xFFEAE8EF)
val ObsidianSurfaceVariant = Color(0xFF222634)
val ObsidianOnSurfaceVariant = Color(0xFF9C9AA6)

val ObsidianSurfaceContainerLowest = Color(0xFF040507)
val ObsidianSurfaceContainerLow = Color(0xFF0F1117)
val ObsidianSurfaceContainer = Color(0xFF151821)
val ObsidianSurfaceContainerHigh = Color(0xFF1D212D)
val ObsidianSurfaceContainerHighest = Color(0xFF262B3A)

val ObsidianOutline = Color(0xFF656470)
val ObsidianOutlineVariant = Color(0xFF262A38)
val ObsidianInverseSurface = Color(0xFFEAE8EF)
val ObsidianInverseOnSurface = Color(0xFF1B1B1F)

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
    surfaceBright = PaperSurfaceContainerLowest,
    surfaceDim = Color(0xFFE5DFD3),
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
    surfaceBright = Color(0xFF2B3040),
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
    surfaceContainerLow = Color(0xFF0A0C10),
    surfaceContainer = Color(0xFF10131A),
    surfaceContainerHigh = Color(0xFF181C26),
    surfaceContainerHighest = Color(0xFF212634),
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
    cardBorder = PaperOutlineVariant.copy(alpha = 0.62f),
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
    glassBorder = PaperOutlineVariant.copy(alpha = 0.70f),
    brandGradientStart = PaperPrimary,
    brandGradientEnd = Color(0xFF0E8F89),
    heroTintStart = PaperPrimary.copy(alpha = 0.085f),
    heroTintEnd = PaperSecondary.copy(alpha = 0.055f),
    shimmerBase = Color(0xFFEAE3D5),
    shimmerHighlight = Color(0xFFFCF9F3),
    softShadowColor = Color(0x181B2559),
)

val DarkExtendedColors = NodePulseExtendedColors(
    cardSurface = ObsidianSurfaceContainer,
    cardBorder = Color(0xFF2A3044),
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
    glassSurface = Color(0xFF0D1017).copy(alpha = 0.86f),
    glassBorder = Color.White.copy(alpha = 0.11f),
    brandGradientStart = ObsidianPrimary,
    brandGradientEnd = ObsidianSecondary,
    heroTintStart = ObsidianPrimary.copy(alpha = 0.15f),
    heroTintEnd = ObsidianSecondary.copy(alpha = 0.08f),
    shimmerBase = Color(0xFF1A1E2B),
    shimmerHighlight = Color(0xFF2B3246),
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
