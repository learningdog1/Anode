package com.nodeseek.app.ui.deals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.LocalOffer
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeseek.app.domain.deals.BlackFridayCountdownCalculator
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.delay

/**
 * Compact, live-ticking Black Friday & Major Promo entry banner designed for `ExploreScreen`.
 *
 * Styled to match Anode's clean wireframe `CollapsiblePinnedTicker`: crisp `cardSurface` sheet,
 * hairline `cardBorder`, `primaryContainer` tag pill, and muted tabular countdown/count chip.
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

    val cardShape = RoundedCornerShape(14.dp)
    val tagShape = RoundedCornerShape(7.dp)

    val trailingHint = remember(dealCountHint, lowestPriceHint) {
        when {
            !lowestPriceHint.isNullOrBlank() -> "低至 $lowestPriceHint"
            dealCountHint != null && dealCountHint > 0 -> "$dealCountHint 条"
            else -> "11/27"
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(ext.cardSurface)
            .border(width = 0.8.dp, color = ext.cardBorder, shape = cardShape)
            .pulseBouncyClickable(
                role = Role.Button,
                onClickLabel = "进入黑五大促专题",
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(tagShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.LocalOffer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = "黑五专题",
                style = extraType.microLabel.copy(
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                softWrap = false,
            )
        }

        Text(
            text = countdown.bannerHeadline,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.SemiBold,
            ).withTabularNumbers(),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Row(
            modifier = Modifier
                .clip(tagShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(
                    width = 0.6.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    shape = tagShape,
                )
                .padding(start = 7.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = trailingHint,
                style = extraType.microLabel.copy(
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                ).withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
