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
import com.nodeseek.app.ui.theme.blendColor
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
        val primary = MaterialTheme.colorScheme.primary
        return when (tag) {
            TrustTag.TRUSTED -> if (isDark) {
                TrustVisualTone(
                    accent = Color(0xFF34D399),
                    container = Color(0xFF0E2A22),
                    containerEnd = Color(0xFF13362B),
                    content = Color(0xFF6EE7B7),
                    border = Color(0xFF34D399).copy(alpha = 0.42f),
                    icon = Icons.Rounded.VerifiedUser,
                )
            } else {
                TrustVisualTone(
                    accent = Color(0xFF059669),
                    container = Color(0xFFE6F9F1),
                    containerEnd = Color(0xFFD6F5E8),
                    content = Color(0xFF065F46),
                    border = Color(0xFF059669).copy(alpha = 0.28f),
                    icon = Icons.Rounded.VerifiedUser,
                )
            }

            TrustTag.NEUTRAL -> if (isDark) {
                TrustVisualTone(
                    accent = Color(0xFF60A5FA),
                    container = Color(0xFF13243B),
                    containerEnd = Color(0xFF192E4A),
                    content = Color(0xFF93C5FD),
                    border = Color(0xFF60A5FA).copy(alpha = 0.38f),
                    icon = Icons.Rounded.Visibility,
                )
            } else {
                TrustVisualTone(
                    accent = Color(0xFF2563EB),
                    container = Color(0xFFE8F1FE),
                    containerEnd = Color(0xFFDBEAFE),
                    content = Color(0xFF1E40AF),
                    border = Color(0xFF2563EB).copy(alpha = 0.24f),
                    icon = Icons.Rounded.Visibility,
                )
            }

            TrustTag.CAUTION -> if (isDark) {
                TrustVisualTone(
                    accent = Color(0xFFFBBF24),
                    container = Color(0xFF31240C),
                    containerEnd = Color(0xFF3D2D0F),
                    content = Color(0xFFFDE68A),
                    border = Color(0xFFF59E0B).copy(alpha = 0.48f),
                    icon = Icons.Rounded.GppMaybe,
                )
            } else {
                TrustVisualTone(
                    accent = Color(0xFFD97706),
                    container = Color(0xFFFFF5DC),
                    containerEnd = Color(0xFFFEF0C7),
                    content = Color(0xFF92400E),
                    border = Color(0xFFD97706).copy(alpha = 0.32f),
                    icon = Icons.Rounded.GppMaybe,
                )
            }

            TrustTag.RISK_BLACKLIST -> if (isDark) {
                TrustVisualTone(
                    accent = Color(0xFFF87171),
                    container = Color(0xFF3A1216),
                    containerEnd = Color(0xFF4C161B),
                    content = Color(0xFFFCA5A5),
                    border = Color(0xFFEF4444).copy(alpha = 0.56f),
                    icon = Icons.Rounded.GppBad,
                )
            } else {
                TrustVisualTone(
                    accent = Color(0xFFDC2626),
                    container = Color(0xFFFEE8E8),
                    containerEnd = Color(0xFFFED7D7),
                    content = Color(0xFF991B1B),
                    border = Color(0xFFDC2626).copy(alpha = 0.38f),
                    icon = Icons.Rounded.GppBad,
                )
            }

            TrustTag.NONE -> {
                val baseContainer = if (isDark) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.56f)
                } else {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                }
                TrustVisualTone(
                    accent = primary,
                    container = baseContainer,
                    containerEnd = blendColor(baseContainer, primary, if (isDark) 0.14f else 0.08f),
                    content = if (isDark) MaterialTheme.colorScheme.onPrimaryContainer else primary,
                    border = primary.copy(alpha = if (isDark) 0.38f else 0.24f),
                    icon = Icons.Rounded.Shield,
                )
            }
        }
    }

    @Composable
    fun forRiskLevel(level: TrustRiskLevel): TrustVisualTone {
        val isDark = NodePulseThemeTokens.isDark
        return when (level) {
            TrustRiskLevel.HIGH_RISK_RED -> if (isDark) {
                TrustVisualTone(
                    accent = Color(0xFFEF4444),
                    container = Color(0xFF3B1115),
                    containerEnd = Color(0xFF280D11),
                    content = Color(0xFFFECACA),
                    border = Color(0xFFEF4444).copy(alpha = 0.65f),
                    icon = Icons.Rounded.GppBad,
                )
            } else {
                TrustVisualTone(
                    accent = Color(0xFFDC2626),
                    container = Color(0xFFFEF2F2),
                    containerEnd = Color(0xFFFEE2E2),
                    content = Color(0xFF991B1B),
                    border = Color(0xFFDC2626).copy(alpha = 0.42f),
                    icon = Icons.Rounded.GppBad,
                )
            }

            TrustRiskLevel.CAUTION_AMBER -> if (isDark) {
                TrustVisualTone(
                    accent = Color(0xFFF59E0B),
                    container = Color(0xFF33240B),
                    containerEnd = Color(0xFF241A09),
                    content = Color(0xFFFDE68A),
                    border = Color(0xFFF59E0B).copy(alpha = 0.54f),
                    icon = Icons.Rounded.GppMaybe,
                )
            } else {
                TrustVisualTone(
                    accent = Color(0xFFD97706),
                    container = Color(0xFFFFFBEB),
                    containerEnd = Color(0xFFFEF3C7),
                    content = Color(0xFF92400E),
                    border = Color(0xFFD97706).copy(alpha = 0.36f),
                    icon = Icons.Rounded.GppMaybe,
                )
            }

            TrustRiskLevel.NORMAL_CLEAR -> if (isDark) {
                TrustVisualTone(
                    accent = Color(0xFF10B981),
                    container = Color(0xFF0E2720),
                    containerEnd = Color(0xFF122327),
                    content = Color(0xFFA7F3D0),
                    border = Color(0xFF10B981).copy(alpha = 0.36f),
                    icon = Icons.Rounded.VerifiedUser,
                )
            } else {
                TrustVisualTone(
                    accent = Color(0xFF059669),
                    container = Color(0xFFECFDF5),
                    containerEnd = Color(0xFFE6F4F1),
                    content = Color(0xFF065F46),
                    border = Color(0xFF059669).copy(alpha = 0.24f),
                    icon = Icons.Rounded.VerifiedUser,
                )
            }
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
