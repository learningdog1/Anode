package com.nodeseek.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

const val TABULAR_NUMS_FEATURE = "tnum"

fun TextStyle.withTabularNumbers(): TextStyle =
    copy(fontFeatureSettings = TABULAR_NUMS_FEATURE)

/**
 * Additional editorial & telemetry text styles beyond standard Material 3 Typography slots.
 */
@Immutable
data class NodePulseExtraTypography(
    val titleMediumUnread: TextStyle,
    val titleMediumRead: TextStyle,
    val codeBody: TextStyle,
    val codeLabel: TextStyle,
    val tabularBadge: TextStyle,
    val sectionHeader: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.1).sp,
    ),
    val heroHeadline: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
        lineHeight = 31.sp,
        letterSpacing = (-0.4).sp,
    ),
    val metricValue: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
        fontFeatureSettings = TABULAR_NUMS_FEATURE,
    ),
    val microLabel: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.25.sp,
        fontFeatureSettings = TABULAR_NUMS_FEATURE,
    ),
)

fun buildNodePulseTypography(fontScale: Float = 0.90f): Typography {
    val s = fontScale.coerceIn(0.85f, 1.30f)
    return Typography(
        displaySmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = (32f * s).sp,
            lineHeight = (40f * s).sp,
            letterSpacing = (-0.5f).sp,
            fontFeatureSettings = TABULAR_NUMS_FEATURE,
        ),
        headlineMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = (25f * s).sp,
            lineHeight = (33f * s).sp,
            letterSpacing = (-0.3f).sp,
        ),
        headlineSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = (22f * s).sp,
            lineHeight = (30f * s).sp,
            letterSpacing = (-0.2f).sp,
        ),
        titleLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = (19f * s).sp,
            lineHeight = (26f * s).sp,
            letterSpacing = 0.sp,
        ),
        titleMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = (16.5f * s).sp,
            lineHeight = (23.5f * s).sp,
            letterSpacing = 0.sp,
        ),
        titleSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = (14.5f * s).sp,
            lineHeight = (20f * s).sp,
            letterSpacing = 0.1.sp,
        ),
        bodyLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = (16f * s).sp,
            lineHeight = (26.5f * s).sp,
            letterSpacing = 0.2.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = (14f * s).sp,
            lineHeight = (21f * s).sp,
            letterSpacing = 0.15.sp,
        ),
        bodySmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = (12.5f * s).sp,
            lineHeight = (18f * s).sp,
            letterSpacing = 0.2.sp,
        ),
        labelLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = (13.5f * s).sp,
            lineHeight = (18f * s).sp,
            letterSpacing = 0.1.sp,
            fontFeatureSettings = TABULAR_NUMS_FEATURE,
        ),
        labelMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = (12f * s).sp,
            lineHeight = (16f * s).sp,
            letterSpacing = 0.2.sp,
            fontFeatureSettings = TABULAR_NUMS_FEATURE,
        ),
        labelSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = (10.5f * s).sp,
            lineHeight = (14f * s).sp,
            letterSpacing = 0.3.sp,
            fontFeatureSettings = TABULAR_NUMS_FEATURE,
        ),
    )
}

fun buildNodePulseExtraTypography(fontScale: Float = 0.90f): NodePulseExtraTypography {
    val s = fontScale.coerceIn(0.85f, 1.30f)
    return NodePulseExtraTypography(
        titleMediumUnread = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = (16.5f * s).sp,
            lineHeight = (23.5f * s).sp,
            letterSpacing = 0.sp,
        ),
        titleMediumRead = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = (16.5f * s).sp,
            lineHeight = (23.5f * s).sp,
            letterSpacing = 0.sp,
        ),
        codeBody = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Normal,
            fontSize = (13f * s).sp,
            lineHeight = (19.5f * s).sp,
            letterSpacing = 0.sp,
            fontFeatureSettings = TABULAR_NUMS_FEATURE,
        ),
        codeLabel = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = (11.5f * s).sp,
            lineHeight = (15f * s).sp,
            letterSpacing = 0.2.sp,
        ),
        tabularBadge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = (11f * s).sp,
            lineHeight = (14f * s).sp,
            letterSpacing = 0.25.sp,
            fontFeatureSettings = TABULAR_NUMS_FEATURE,
        ),
        sectionHeader = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = (15f * s).sp,
            lineHeight = (20f * s).sp,
            letterSpacing = (-0.1f).sp,
        ),
        heroHeadline = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.ExtraBold,
            fontSize = (24f * s).sp,
            lineHeight = (31f * s).sp,
            letterSpacing = (-0.4f).sp,
        ),
        metricValue = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = (20f * s).sp,
            lineHeight = (26f * s).sp,
            letterSpacing = (-0.2f).sp,
            fontFeatureSettings = TABULAR_NUMS_FEATURE,
        ),
        microLabel = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = (10.5f * s).sp,
            lineHeight = (14f * s).sp,
            letterSpacing = 0.25.sp,
            fontFeatureSettings = TABULAR_NUMS_FEATURE,
        ),
    )
}
