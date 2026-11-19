package com.nodeseek.app.ui.trust

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodeseek.app.domain.trust.SellerTrustRepository
import com.nodeseek.app.domain.trust.TrustRiskLevel
import com.nodeseek.app.domain.trust.TrustTag
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.pulsePressScale

@Immutable
internal data class TrustVisualTone(
    val accent: Color,
    val container: Color,
    val containerEnd: Color,
    val content: Color,
    val border: Color,
    val icon: ImageVector,
) {
    val backgroundBrush: Brush
        get() = Brush.horizontalGradient(listOf(container, containerEnd))
}

internal object TrustPalette {

    @Composable
    fun forTag(tag: TrustTag): TrustVisualTone {
        val isDark = NodePulseThemeTokens.isDark
        val ext = NodePulseThemeTokens.extendedColors
        val primary = MaterialTheme.colorScheme.primary
        return when (tag) {
            TrustTag.TRUSTED -> TrustVisualTone(
                accent = primary,
                container = MaterialTheme.colorScheme.primaryContainer,
                containerEnd = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
                border = primary.copy(alpha = 0.30f),
                icon = Icons.Rounded.VerifiedUser,
            )

            TrustTag.NEUTRAL -> TrustVisualTone(
                accent = MaterialTheme.colorScheme.onSurfaceVariant,
                container = MaterialTheme.colorScheme.surfaceContainerLow,
                containerEnd = MaterialTheme.colorScheme.surfaceContainerLow,
                content = MaterialTheme.colorScheme.onSurface,
                border = ext.cardBorder,
                icon = Icons.Rounded.Visibility,
            )

            TrustTag.CAUTION -> {
                val cautionBg = ext.drumstickContainer.copy(alpha = if (isDark) 0.50f else 0.72f)
                TrustVisualTone(
                    accent = ext.onDrumstickContainer,
                    container = cautionBg,
                    containerEnd = cautionBg,
                    content = ext.onDrumstickContainer,
                    border = ext.onDrumstickContainer.copy(alpha = 0.28f),
                    icon = Icons.Rounded.GppMaybe,
                )
            }

            TrustTag.RISK_BLACKLIST -> {
                val errorBg = MaterialTheme.colorScheme.errorContainer.copy(
                    alpha = if (isDark) 0.42f else 0.55f,
                )
                TrustVisualTone(
                    accent = MaterialTheme.colorScheme.error,
                    container = errorBg,
                    containerEnd = errorBg,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                    border = MaterialTheme.colorScheme.error.copy(alpha = 0.38f),
                    icon = Icons.Rounded.GppBad,
                )
            }

            TrustTag.NONE -> TrustVisualTone(
                accent = MaterialTheme.colorScheme.onSurfaceVariant,
                container = MaterialTheme.colorScheme.surfaceContainerLow,
                containerEnd = MaterialTheme.colorScheme.surfaceContainerLow,
                content = MaterialTheme.colorScheme.onSurface,
                border = ext.cardBorder,
                icon = Icons.Rounded.Shield,
            )
        }
    }

    @Composable
    fun forRiskLevel(level: TrustRiskLevel): TrustVisualTone {
        val isDark = NodePulseThemeTokens.isDark
        val ext = NodePulseThemeTokens.extendedColors
        return when (level) {
            TrustRiskLevel.HIGH_RISK_RED -> {
                val errorBg = MaterialTheme.colorScheme.errorContainer.copy(
                    alpha = if (isDark) 0.42f else 0.55f,
                )
                TrustVisualTone(
                    accent = MaterialTheme.colorScheme.error,
                    container = errorBg,
                    containerEnd = errorBg,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                    border = MaterialTheme.colorScheme.error.copy(alpha = 0.38f),
                    icon = Icons.Rounded.GppBad,
                )
            }

            TrustRiskLevel.CAUTION_AMBER -> {
                val cautionBg = ext.drumstickContainer.copy(alpha = if (isDark) 0.50f else 0.72f)
                TrustVisualTone(
                    accent = ext.onDrumstickContainer,
                    container = cautionBg,
                    containerEnd = cautionBg,
                    content = ext.onDrumstickContainer,
                    border = ext.onDrumstickContainer.copy(alpha = 0.28f),
                    icon = Icons.Rounded.GppMaybe,
                )
            }

            TrustRiskLevel.NORMAL_CLEAR -> TrustVisualTone(
                accent = MaterialTheme.colorScheme.primary,
                container = MaterialTheme.colorScheme.primaryContainer,
                containerEnd = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
                border = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                icon = Icons.Rounded.VerifiedUser,
            )
        }
    }
}

/**
 * Compact inline badge (`查信誉` or live saved [TrustTag] pill such as `靠谱` / `观察` / `谨慎` / `避雷`)
 * designed for trade topic headers, direct message top bars, and user profiles.
 *
 * Automatically observes the local seller note for [uid] so updating a seller tag inside
 * [SellerTrustCardSheet] immediately refreshes the pill everywhere on screen.
 */
@Composable
fun SellerTrustQuickBadge(
    uid: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tagOverride: TrustTag? = null,
    compact: Boolean = false,
    repository: SellerTrustRepository? = null,
) {
    val isInspection = LocalInspectionMode.current
    val resolvedTag: TrustTag = if (tagOverride != null) {
        tagOverride
    } else if (!isInspection && uid > 0L) {
        val repo = rememberSellerTrustRepository(override = repository)
        val noteFlow = remember(repo, uid) { repo.observeLocalNote(uid) }
        val localNote by noteFlow.collectAsStateWithLifecycle(initialValue = null)
        localNote?.tag ?: TrustTag.NONE
    } else {
        TrustTag.NONE
    }

    val tone = TrustPalette.forTag(resolvedTag)
    val label = if (resolvedTag.isTagged) resolvedTag.shortLabel else "查信誉"
    val shape = if (compact) CircleShape else RoundedCornerShape(9.dp)
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .heightIn(min = if (compact) 24.dp else 28.dp)
            .pulsePressScale(
                interactionSource = interactionSource,
                pressedScale = NodePulseMotion.chipPressScale,
            )
            .clip(shape)
            .background(tone.backgroundBrush)
            .border(
                width = if (resolvedTag == TrustTag.RISK_BLACKLIST) 1.dp else 0.8.dp,
                color = tone.border,
                shape = shape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClickLabel = "查一查卖家信誉",
                onClick = onClick,
            )
            .padding(
                horizontal = if (compact) 8.dp else 9.5.dp,
                vertical = if (compact) 3.dp else 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 3.5.dp else 4.5.dp),
    ) {
        Icon(
            imageVector = tone.icon,
            contentDescription = null,
            tint = tone.accent,
            modifier = Modifier.size(if (compact) 12.5.dp else 14.dp),
        )
        Text(
            text = label,
            style = if (compact) {
                MaterialTheme.typography.labelSmall
            } else {
                MaterialTheme.typography.labelMedium
            },
            fontWeight = FontWeight.SemiBold,
            color = tone.content,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
