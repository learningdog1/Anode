package com.nodeseek.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.sqrt

/**
 * NodeSeek Lv.0–Lv.6 metallic & gemstone rank system.
 * Official NodeSeek level formula: coins >= 100 * level^2 for level in 0..6.
 */
@Immutable
data class RankVisualSpec(
    val level: Int,
    val minCoins: Int,
    val nextLevelCoins: Int?,
    val personaTitle: String,
    val gradientColors: List<Color>,
    val containerColor: Color,
    val contentColor: Color,
    val glowColor: Color = gradientColors.firstOrNull() ?: contentColor,
) {
    val ringBrush: Brush
        get() = Brush.linearGradient(colors = gradientColors)

    val softContainerBrush: Brush
        get() = Brush.linearGradient(
            colors = listOf(
                containerColor,
                blendColor(containerColor, gradientColors.firstOrNull() ?: containerColor, 0.16f),
            )
        )

    fun harmonizeWith(primary: Color, fraction: Float = 0.10f): RankVisualSpec = copy(
        gradientColors = gradientColors.map { blendColor(it, primary, fraction) },
        containerColor = blendColor(containerColor, primary, fraction),
        glowColor = blendColor(glowColor, primary, fraction),
    )
}

@Immutable
data class RankProgressInfo(
    val level: Int,
    val currentCoins: Int,
    val currentFloorCoins: Int,
    val nextThresholdCoins: Int?,
    val progressFraction: Float,
    val coinsToNextLevel: Int,
    val personaTitle: String,
)

object RankPalette {
    /**
     * Computes NodeSeek display level (0..6) from total drumsticks (coins).
     */
    fun levelFromCoins(coins: Int): Int {
        val clamped = coins.coerceAtLeast(0)
        return sqrt(clamped / 100.0).toInt().coerceIn(0, 6)
    }

    /**
     * Computes full progress metrics for Pulse Hub rank progress bars.
     */
    fun progressFromCoins(coins: Int, isDark: Boolean = false): RankProgressInfo {
        val safeCoins = coins.coerceAtLeast(0)
        val level = levelFromCoins(safeCoins)
        val spec = forLevel(level, isDark = isDark)
        val floor = spec.minCoins
        val next = spec.nextLevelCoins
        val fraction = if (next == null || next <= floor) {
            1f
        } else {
            ((safeCoins - floor).toFloat() / (next - floor).toFloat()).coerceIn(0f, 1f)
        }
        val remaining = if (next == null) 0 else (next - safeCoins).coerceAtLeast(0)
        return RankProgressInfo(
            level = level,
            currentCoins = safeCoins,
            currentFloorCoins = floor,
            nextThresholdCoins = next,
            progressFraction = fraction,
            coinsToNextLevel = remaining,
            personaTitle = spec.personaTitle,
        )
    }

    fun forLevel(
        level: Int,
        isDark: Boolean,
        dynamicPrimaryToHarmonize: Color? = null,
    ): RankVisualSpec {
        val lv = level.coerceIn(0, 6)
        val base = when (lv) {
            0 -> RankVisualSpec(
                level = 0,
                minCoins = 0,
                nextLevelCoins = 100,
                personaTitle = "新脉",
                gradientColors = listOf(Color(0xFF94A3B8), Color(0xFF64748B)),
                containerColor = if (isDark) Color(0xFF1E222B) else Color(0xFFEEF1F5),
                contentColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF525B6B),
                glowColor = Color(0xFF64748B),
            )
            1 -> RankVisualSpec(
                level = 1,
                minCoins = 100,
                nextLevelCoins = 400,
                personaTitle = "赤铜",
                gradientColors = listOf(Color(0xFFE09558), Color(0xFFA85C2A)),
                containerColor = if (isDark) Color(0xFF2E1E16) else Color(0xFFF9EBE0),
                contentColor = if (isDark) Color(0xFFF3B382) else Color(0xFF844318),
                glowColor = Color(0xFFD98A4E),
            )
            2 -> RankVisualSpec(
                level = 2,
                minCoins = 400,
                nextLevelCoins = 900,
                personaTitle = "海蓝",
                gradientColors = listOf(Color(0xFF38BDF8), Color(0xFF0284C7)),
                containerColor = if (isDark) Color(0xFF142738) else Color(0xFFE1F0FA),
                contentColor = if (isDark) Color(0xFF7DD3FC) else Color(0xFF0B4F78),
                glowColor = Color(0xFF0EA5E9),
            )
            3 -> RankVisualSpec(
                level = 3,
                minCoins = 900,
                nextLevelCoins = 1600,
                personaTitle = "翡翠",
                gradientColors = listOf(Color(0xFF34D399), Color(0xFF0D9488)),
                containerColor = if (isDark) Color(0xFF102F26) else Color(0xFFDCF7EC),
                contentColor = if (isDark) Color(0xFF6EE7B7) else Color(0xFF065F46),
                glowColor = Color(0xFF10B981),
            )
            4 -> RankVisualSpec(
                level = 4,
                minCoins = 1600,
                nextLevelCoins = 2500,
                personaTitle = "靛曜",
                gradientColors = listOf(Color(0xFF60A5FA), Color(0xFF6366F1)),
                containerColor = if (isDark) Color(0xFF19234F) else Color(0xFFDFE7FF),
                contentColor = if (isDark) Color(0xFFA5B4FC) else Color(0xFF1E3A8A),
                glowColor = Color(0xFF6366F1),
            )
            5 -> RankVisualSpec(
                level = 5,
                minCoins = 2500,
                nextLevelCoins = 3600,
                personaTitle = "紫晶",
                gradientColors = listOf(Color(0xFFA855F7), Color(0xFFEC4899)),
                containerColor = if (isDark) Color(0xFF2D1942) else Color(0xFFF0E1FF),
                contentColor = if (isDark) Color(0xFFE9D5FF) else Color(0xFF5B21B6),
                glowColor = Color(0xFFC084FC),
            )
            else -> RankVisualSpec(
                level = 6,
                minCoins = 3600,
                nextLevelCoins = null,
                personaTitle = "耀金",
                gradientColors = listOf(Color(0xFFF59E0B), Color(0xFFEF4444), Color(0xFFFBBF24)),
                containerColor = if (isDark) Color(0xFF38260D) else Color(0xFFFFF0C7),
                contentColor = if (isDark) Color(0xFFFDE047) else Color(0xFF854D0E),
                glowColor = Color(0xFFF59E0B),
            )
        }
        return if (dynamicPrimaryToHarmonize != null) {
            base.harmonizeWith(dynamicPrimaryToHarmonize)
        } else {
            base
        }
    }
}
