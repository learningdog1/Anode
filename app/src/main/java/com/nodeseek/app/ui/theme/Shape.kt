package com.nodeseek.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val NodePulseShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

object PulseArchitecturalShapes {
    val Pill = CircleShape
    val SquircleFab = RoundedCornerShape(18.dp)
    val EditorialCard = RoundedCornerShape(16.dp)
    val HeroBento = RoundedCornerShape(22.dp)
    val CodeBlock = RoundedCornerShape(14.dp)
    val InlineImage = RoundedCornerShape(14.dp)
    val BottomSheetTop = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val SmallChip = RoundedCornerShape(8.dp)
    val MediumBadge = RoundedCornerShape(10.dp)
    val ElevatedTile = RoundedCornerShape(18.dp)
    val GlassBar = RoundedCornerShape(24.dp)
    val SheetContainer = RoundedCornerShape(28.dp)
}
