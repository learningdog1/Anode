package com.nodeseek.app.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import androidx.glance.material3.ColorProviders
import com.nodeseek.app.ui.theme.DarkExtendedColors
import com.nodeseek.app.ui.theme.LightExtendedColors
import com.nodeseek.app.ui.theme.ObsidianDarkColorScheme
import com.nodeseek.app.ui.theme.ObsidianErrorContainer
import com.nodeseek.app.ui.theme.ObsidianOnErrorContainer
import com.nodeseek.app.ui.theme.ObsidianOnPrimaryContainer
import com.nodeseek.app.ui.theme.ObsidianOnSurfaceVariant
import com.nodeseek.app.ui.theme.ObsidianPrimary
import com.nodeseek.app.ui.theme.ObsidianPrimaryContainer
import com.nodeseek.app.ui.theme.ObsidianSurfaceContainer
import com.nodeseek.app.ui.theme.ObsidianSurfaceContainerHigh
import com.nodeseek.app.ui.theme.ObsidianSurfaceContainerHighest
import com.nodeseek.app.ui.theme.PaperErrorContainer
import com.nodeseek.app.ui.theme.PaperLightColorScheme
import com.nodeseek.app.ui.theme.PaperOnErrorContainer
import com.nodeseek.app.ui.theme.PaperOnPrimaryContainer
import com.nodeseek.app.ui.theme.PaperOnSurfaceVariant
import com.nodeseek.app.ui.theme.PaperPrimary
import com.nodeseek.app.ui.theme.PaperPrimaryContainer
import com.nodeseek.app.ui.theme.PaperSurfaceContainerHigh
import com.nodeseek.app.ui.theme.PaperSurfaceContainerLow
import com.nodeseek.app.ui.theme.PaperSurfaceContainerLowest

/**
 * Glance Material 3 theme mapping Cool Mist Light / Slate Steel Dark + Drumstick Gold tokens.
 */
object AnodeGlanceTheme {
    val colors = ColorProviders(
        light = PaperLightColorScheme,
        dark = ObsidianDarkColorScheme,
    )
}

object AnodeGlanceTokens {
    val cardSurface = ColorProvider(
        day = PaperSurfaceContainerLowest,
        night = ObsidianSurfaceContainer,
    )
    val pillSurface = ColorProvider(
        day = PaperSurfaceContainerLow,
        night = ObsidianSurfaceContainerHigh,
    )
    val trackBackground = ColorProvider(
        day = PaperSurfaceContainerHigh,
        night = ObsidianSurfaceContainerHighest,
    )
    val progressPrimary = ColorProvider(
        day = PaperPrimary,
        night = ObsidianPrimary,
    )
    val accentBg = ColorProvider(
        day = PaperPrimaryContainer,
        night = ObsidianPrimaryContainer,
    )
    val accentFg = ColorProvider(
        day = PaperOnPrimaryContainer,
        night = ObsidianOnPrimaryContainer,
    )
    val mutedText = ColorProvider(
        day = PaperOnSurfaceVariant,
        night = ObsidianOnSurfaceVariant,
    )
    val drumstickBg = ColorProvider(
        day = LightExtendedColors.drumstickContainer,
        night = DarkExtendedColors.drumstickContainer,
    )
    val drumstickFg = ColorProvider(
        day = LightExtendedColors.onDrumstickContainer,
        night = DarkExtendedColors.onDrumstickContainer,
    )
    val onlineBg = ColorProvider(
        day = Color(0xFFD9F5E5),
        night = Color(0xFF133A27),
    )
    val onlineFg = ColorProvider(
        day = Color(0xFF146C3E),
        night = Color(0xFF6EE7A8),
    )
    val warningBg = ColorProvider(
        day = Color(0xFFFFF1D6),
        night = Color(0xFF3A280B),
    )
    val warningFg = ColorProvider(
        day = Color(0xFF9A5200),
        night = Color(0xFFFBBF24),
    )
    val dangerBg = ColorProvider(
        day = PaperErrorContainer,
        night = ObsidianErrorContainer,
    )
    val dangerFg = ColorProvider(
        day = PaperOnErrorContainer,
        night = ObsidianOnErrorContainer,
    )
}
