package com.nodeseek.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ThemeAndPaletteTest {

    @Test
    fun `RankPalette levelFromCoins follows official 100 times level squared formula`() {
        assertEquals(0, RankPalette.levelFromCoins(-50))
        assertEquals(0, RankPalette.levelFromCoins(0))
        assertEquals(0, RankPalette.levelFromCoins(99))
        assertEquals(1, RankPalette.levelFromCoins(100))
        assertEquals(1, RankPalette.levelFromCoins(399))
        assertEquals(2, RankPalette.levelFromCoins(400))
        assertEquals(2, RankPalette.levelFromCoins(899))
        assertEquals(3, RankPalette.levelFromCoins(900))
        assertEquals(4, RankPalette.levelFromCoins(1600))
        assertEquals(5, RankPalette.levelFromCoins(2500))
        assertEquals(6, RankPalette.levelFromCoins(3600))
        assertEquals(6, RankPalette.levelFromCoins(10_000))

        val midProgress = RankPalette.progressFromCoins(250)
        assertEquals(1, midProgress.level)
        assertEquals(100, midProgress.currentFloorCoins)
        assertEquals(400, midProgress.nextThresholdCoins)
        assertEquals(150, midProgress.coinsToNextLevel)
        assertEquals(0.5f, midProgress.progressFraction, 0.001f)

        val maxProgress = RankPalette.progressFromCoins(9999)
        assertEquals(6, maxProgress.level)
        assertEquals(null, maxProgress.nextThresholdCoins)
        assertEquals(0, maxProgress.coinsToNextLevel)
        assertEquals(1f, maxProgress.progressFraction, 0.001f)
    }

    @Test
    fun `RankPalette caches static instances when unharmonized`() {
        assertSame(RankPalette.forLevel(3, isDark = false), RankPalette.forLevel(3, isDark = false))
        assertSame(RankPalette.forLevel(5, isDark = true), RankPalette.forLevel(5, isDark = true))
    }

    @Test
    fun `CategoryPalette covers all 15 boards aligns All with Cool Mist and preserves unknown slugs`() {
        val allSpecs = CategoryPalette.allSpecs(isDark = false)
        assertEquals(15, allSpecs.size)

        // "全部" slug uses Cool Mist primary tokens rather than stale Electric Cobalt
        val allSpecLight = CategoryPalette.resolve("", isDark = false)
        assertEquals(PaperPrimary, allSpecLight.spineColor)
        assertEquals(PaperPrimaryContainer, allSpecLight.containerColor)
        assertSame(allSpecLight, CategoryPalette.resolve("", isDark = false))

        // Unknown slug preserves slug & title instead of silently relabeling as "沙盒"
        val unknownSpec = CategoryPalette.resolve("future-board", isDark = false)
        assertEquals("future-board", unknownSpec.slug)
        assertEquals("future-board", unknownSpec.defaultTitle)
        assertNotEquals("sandbox", unknownSpec.slug)
        assertNotEquals("沙盒", unknownSpec.defaultTitle)
    }

    @Test
    fun `Typography clamps fontScale strictly to 0_85f through 1_30f`() {
        val clampedHigh = buildNodePulseTypography(1.35f)
        val exactMax = buildNodePulseTypography(1.30f)
        assertEquals(exactMax.bodyMedium.fontSize, clampedHigh.bodyMedium.fontSize)

        val clampedLow = buildNodePulseTypography(0.50f)
        val exactMin = buildNodePulseTypography(0.85f)
        assertEquals(exactMin.bodyMedium.fontSize, clampedLow.bodyMedium.fontSize)

        val extraHigh = buildNodePulseExtraTypography(1.50f)
        val extraMax = buildNodePulseExtraTypography(1.30f)
        assertEquals(extraMax.titleMediumUnread.fontSize, extraHigh.titleMediumUnread.fontSize)
    }

    @Test
    fun `Light Dark and AMOLED themes meet WCAG AA 4_5 contrast for key text and semantic chips`() {
        // Light mode
        assertContrastAtLeast("Light body on bg", PaperOnSurface, PaperBackground, 4.5)
        assertContrastAtLeast("Light secondary on card", PaperOnSurfaceVariant, PaperSurfaceContainerLowest, 4.5)
        val lightReadTitle = compositeOver(
            PaperOnSurfaceVariant.copy(alpha = LightExtendedColors.readTitleAlpha),
            PaperSurfaceContainerLowest,
        )
        assertContrastAtLeast("Light read title on card", lightReadTitle, PaperSurfaceContainerLowest, 4.5)
        assertContrastAtLeast(
            "Light heat pill",
            LightExtendedColors.onHeatContainer,
            LightExtendedColors.heatContainer,
            4.5,
        )
        assertContrastAtLeast(
            "Light drumstick pill",
            LightExtendedColors.onDrumstickContainer,
            LightExtendedColors.drumstickContainer,
            4.5,
        )

        // Dark & AMOLED mode
        assertContrastAtLeast("Dark body on bg", ObsidianOnSurface, ObsidianBackground, 4.5)
        assertContrastAtLeast("Dark secondary on card", ObsidianOnSurfaceVariant, ObsidianSurfaceContainer, 4.5)
        val darkReadTitle = compositeOver(
            ObsidianOnSurfaceVariant.copy(alpha = DarkExtendedColors.readTitleAlpha),
            ObsidianSurfaceContainer,
        )
        assertContrastAtLeast("Dark read title on card", darkReadTitle, ObsidianSurfaceContainer, 4.5)
        assertContrastAtLeast(
            "Dark heat pill",
            DarkExtendedColors.onHeatContainer,
            DarkExtendedColors.heatContainer,
            4.5,
        )
        assertContrastAtLeast(
            "Dark drumstick pill",
            DarkExtendedColors.onDrumstickContainer,
            DarkExtendedColors.drumstickContainer,
            4.5,
        )
        val amoledScheme = ObsidianDarkColorScheme.withPurePitchAmoled()
        assertEquals(PurePitchBlack, amoledScheme.background)
        assertContrastAtLeast("AMOLED body on black", amoledScheme.onSurface, amoledScheme.background, 4.5)

        // All 15 boards & 7 rank badges meet AA contrast in both Light and Dark
        listOf(false, true).forEach { isDark ->
            CategoryPalette.allSpecs(isDark = isDark).forEach { board ->
                assertContrastAtLeast(
                    "Category ${board.slug} (dark=$isDark)",
                    board.contentColor,
                    board.containerColor,
                    4.5,
                )
            }
            for (level in 0..6) {
                val rank = RankPalette.forLevel(level, isDark = isDark)
                assertContrastAtLeast(
                    "Rank Lv.$level (dark=$isDark)",
                    rank.contentColor,
                    rank.containerColor,
                    4.5,
                )
            }
        }
    }

    private fun assertContrastAtLeast(label: String, fg: Color, bg: Color, minRatio: Double) {
        val ratio = wcagContrastRatio(fg, bg)
        assertTrue("$label contrast $ratio < $minRatio", ratio >= minRatio)
    }

    private fun compositeOver(fg: Color, bg: Color): Color {
        val a = fg.alpha
        return Color(
            red = fg.red * a + bg.red * (1f - a),
            green = fg.green * a + bg.green * (1f - a),
            blue = fg.blue * a + bg.blue * (1f - a),
            alpha = 1f,
        )
    }

    private fun relativeLuminance(color: Color): Double {
        fun linearize(channel: Float): Double {
            val c = channel.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linearize(color.red) +
            0.7152 * linearize(color.green) +
            0.0722 * linearize(color.blue)
    }

    private fun wcagContrastRatio(fg: Color, bg: Color): Double {
        val l1 = relativeLuminance(fg)
        val l2 = relativeLuminance(bg)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
