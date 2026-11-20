package com.nodeseek.app.ui.ssh

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.withTabularNumbers

/**
 * Global floating status capsule displayed across the app whenever a background SSH benchmark
 * session is actively running and the user has navigated away from `LiteSshScreen`.
 *
 * Tapping anywhere on the capsule or the trailing CTA returns directly to `LiteSshRoute`.
 */
@Composable
fun LiteSshRunningGlobalBar(
    uiState: LiteSshUiState,
    visible: Boolean,
    onReturnToLiteSsh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible && uiState.isRunning,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        val ext = NodePulseThemeTokens.extendedColors
        val isDark = NodePulseThemeTokens.isDark
        val pulse = rememberInfiniteTransition(label = "globalSshRunPulse")
        val dotAlpha by pulse.animateFloat(
            initialValue = 0.35f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 750, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "globalSshDotAlpha",
        )

        val serverAlias = uiState.activeServerAlias
            ?: uiState.selectedServer?.displayName
            ?: "VPS"
        val presetName = uiState.activePresetName ?: uiState.selectedPreset.name
        val progress = uiState.liveProgress
        val elapsedClock = formatElapsedClock(uiState.elapsedMillis)
        val subtitleText = if (progress.isSilentLong) {
            "${progress.silentRunningBannerText} · $elapsedClock"
        } else {
            "${progress.currentStageLabel} · 已运行 $elapsedClock"
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pulseCard(
                    shape = PulseArchitecturalShapes.HeroBento,
                    containerColor = ext.glassSurface.copy(alpha = if (isDark) 0.94f else 0.97f),
                    elevation = PulseElevation.FloatingBar,
                    accentColor = MaterialTheme.colorScheme.primary,
                )
                .clickable(role = Role.Button, onClick = onReturnToLiteSsh)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(PulseBrushes.brandSoftWash(0.18f))
                    .border(
                        width = 0.8.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                        shape = RoundedCornerShape(11.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .alpha(dotAlpha)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Text(
                        text = "后台测机中 · $serverAlias · $presetName",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall.withTabularNumbers(),
                    color = if (progress.isSilentLong) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            FilledTonalButton(
                onClick = onReturnToLiteSsh,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "返回测机界面",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
