package com.nodeseek.app.ui.deals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeseek.app.domain.deals.BlackFridayCountdownCalculator
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.delay

/**
 * Compact, live-ticking Black Friday & Major Promo entry banner designed for `ExploreScreen`.
 *
 * Displays `距离黑五还有 X天 XX:XX:XX` (live `Asia/Shanghai` countdown) alongside a vivid
 * `大促专题` CTA pill, harmonized across Cool Mist Light, Slate Steel Dark, and Pure Pitch AMOLED.
 */
@Composable
fun BlackFridayPromoBanner(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dealCountHint: Int? = null,
    lowestPriceHint: String? = null,
    nowMillisProvider: () -> Long = { System.currentTimeMillis() },
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val extraType = NodePulseThemeTokens.extraTypography

    var nowEpochMillis by remember { mutableLongStateOf(nowMillisProvider()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            nowEpochMillis = nowMillisProvider()
        }
    }

    val countdown = remember(nowEpochMillis) {
        BlackFridayCountdownCalculator.compute(nowEpochMillis)
    }

    val emberAccent = if (isDark) Color(0xFFFB923C) else Color(0xFFEA580C)
    val roseAccent = if (isDark) Color(0xFFF43F5E) else Color(0xFFDC2626)
    val cardShape = RoundedCornerShape(16.dp)

    val containerBrush = Brush.linearGradient(
        colors = listOf(
            blendColor(ext.cardSurfaceElevated, emberAccent, if (isDark) 0.22f else 0.11f),
            blendColor(ext.cardSurface, ext.drumstickGoldStart, if (isDark) 0.14f else 0.08f),
            blendColor(ext.cardSurface, roseAccent, if (isDark) 0.10f else 0.05f),
        ),
    )
    val borderBrush = Brush.horizontalGradient(
        colors = listOf(
            emberAccent.copy(alpha = if (isDark) 0.56f else 0.38f),
            ext.drumstickGoldStart.copy(alpha = if (isDark) 0.42f else 0.30f),
            roseAccent.copy(alpha = if (isDark) 0.45f else 0.28f),
        ),
    )

    val subtitleHint = remember(dealCountHint, lowestPriceHint, countdown.eventDateRangeLabel) {
        buildList {
            if (dealCountHint != null && dealCountHint > 0) add("实时聚合 ${dealCountHint} 条大促")
            if (!lowestPriceHint.isNullOrBlank()) add("低至 $lowestPriceHint")
            if (isEmpty()) add("${countdown.eventDateRangeLabel} · 站内+LET 线报速览")
        }.joinToString(" · ")
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(containerBrush)
            .border(width = 1.dp, brush = borderBrush, shape = cardShape)
            .pulseBouncyClickable(
                role = Role.Button,
                onClickLabel = "进入黑五大促专题",
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(PulseArchitecturalShapes.MediumBadge)
                .background(PulseBrushes.heatFlameGradient()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.LocalFireDepartment,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(19.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = countdown.bannerHeadline,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp,
                ).withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitleHint,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(PulseBrushes.heatFlameGradient())
                .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "大促专题",
                style = extraType.microLabel.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = Color.White,
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
