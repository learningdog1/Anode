package com.nodeseek.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import com.nodeseek.app.ui.theme.NodePulseExtendedColors
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.pulsePressScale

/**
 * Semantic accent tone for Anode dialogs.
 */
enum class PulseDialogTone {
    PRIMARY,
    WARM,
    DESTRUCTIVE,
}

/**
 * Expressive Anode dialog container built on [BasicAlertDialog].
 *
 * Supports single-button notice/list dialogs (`dismissText = null`), two-button confirmation &
 * destructive flows, custom form/list bodies via [content], optional leading [icon] badge,
 * optional [titleTrailing] pill, and an optional tertiary [extraActionText] button.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PulseAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = null,
    tone: PulseDialogTone = PulseDialogTone.PRIMARY,
    accentColor: Color? = null,
    centerHeader: Boolean = false,
    titleTrailing: (@Composable RowScope.() -> Unit)? = null,
    confirmText: String = "确定",
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    confirmLoading: Boolean = false,
    useTonalConfirm: Boolean = false,
    dismissText: String? = "取消",
    onDismiss: () -> Unit = onDismissRequest,
    extraActionText: String? = null,
    onExtraAction: (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
    buttons: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val shape = PulseArchitecturalShapes.SheetContainer

    val resolvedAccent = accentColor ?: when (tone) {
        PulseDialogTone.PRIMARY -> MaterialTheme.colorScheme.primary
        PulseDialogTone.WARM -> ext.drumstickGoldEnd
        PulseDialogTone.DESTRUCTIVE -> MaterialTheme.colorScheme.error
    }

    val topWashBrush = Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to resolvedAccent.copy(alpha = if (isDark) 0.14f else 0.065f),
            0.38f to resolvedAccent.copy(alpha = if (isDark) 0.04f else 0.015f),
            1.0f to Color.Transparent,
        ),
    )
    val borderBrush = PulseBrushes.cardBorderBrush(
        accentColor = resolvedAccent,
        isDark = isDark,
    )

    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        properties = properties,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(min = 280.dp, max = 440.dp),
            shape = shape,
            color = ext.cardSurfaceElevated,
            tonalElevation = 0.dp,
            shadowElevation = if (isDark) PulseElevation.FloatingBar else 14.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(topWashBrush)
                    .border(width = 1.dp, brush = borderBrush, shape = shape)
                    .padding(horizontal = 22.dp, vertical = 22.dp),
            ) {
                if (centerHeader) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (icon != null) {
                            PulseDialogHeroIconBadge(
                                icon = icon,
                                tone = tone,
                                accentColor = accentColor,
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                            )
                            if (titleTrailing != null) {
                                titleTrailing()
                            }
                        }

                        if (!message.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (icon != null) {
                            PulseDialogCompactIconBadge(
                                icon = icon,
                                tone = tone,
                                accentColor = accentColor,
                            )
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (titleTrailing != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                content = titleTrailing,
                            )
                        }
                    }

                    if (!message.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (content != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        content = content,
                    )
                }

                val hasDefaultButtons = onConfirm != null || dismissText != null ||
                    (extraActionText != null && onExtraAction != null)
                if (buttons != null || hasDefaultButtons) {
                    Spacer(modifier = Modifier.height(20.dp))
                    if (buttons != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            content = buttons,
                        )
                    } else {
                        val confirmAction = onConfirm
                        val canShowSymmetric = centerHeader &&
                            dismissText != null &&
                            (extraActionText == null || onExtraAction == null)

                        if (canShowSymmetric && confirmAction != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                PulseDialogSecondaryButton(
                                    text = dismissText.orEmpty(),
                                    onClick = onDismiss,
                                    enabled = !confirmLoading,
                                    modifier = Modifier.weight(1f),
                                )
                                PulseDialogPrimaryButton(
                                    text = confirmText,
                                    onClick = confirmAction,
                                    tone = tone,
                                    enabled = confirmEnabled,
                                    loading = confirmLoading,
                                    useTonal = useTonalConfirm,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        } else if (extraActionText != null && onExtraAction != null) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(
                                    onClick = onExtraAction,
                                    enabled = !confirmLoading,
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        text = extraActionText,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (dismissText != null) {
                                    PulseDialogSecondaryButton(
                                        text = dismissText,
                                        onClick = onDismiss,
                                        enabled = !confirmLoading,
                                    )
                                }
                                if (onConfirm != null) {
                                    PulseDialogPrimaryButton(
                                        text = confirmText,
                                        onClick = onConfirm,
                                        tone = tone,
                                        enabled = confirmEnabled,
                                        loading = confirmLoading,
                                        useTonal = useTonalConfirm,
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            ) {
                                if (dismissText != null) {
                                    PulseDialogSecondaryButton(
                                        text = dismissText,
                                        onClick = onDismiss,
                                        enabled = !confirmLoading,
                                    )
                                }
                                if (onConfirm != null) {
                                    PulseDialogPrimaryButton(
                                        text = confirmText,
                                        onClick = onConfirm,
                                        tone = tone,
                                        enabled = confirmEnabled,
                                        loading = confirmLoading,
                                        useTonal = useTonalConfirm,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Convenience Anode confirmation / destructive alert dialog.
 */
@Composable
fun PulseConfirmDialog(
    onDismissRequest: () -> Unit,
    title: String,
    message: String,
    confirmText: String = "确认",
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String? = "取消",
    onDismiss: () -> Unit = onDismissRequest,
    icon: ImageVector? = null,
    tone: PulseDialogTone = PulseDialogTone.PRIMARY,
    accentColor: Color? = null,
    centerHeader: Boolean = (icon != null),
    confirmEnabled: Boolean = true,
    confirmLoading: Boolean = false,
    properties: DialogProperties = DialogProperties(),
    extraContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    PulseAlertDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        modifier = modifier,
        message = message,
        icon = icon,
        tone = tone,
        accentColor = accentColor,
        centerHeader = centerHeader,
        confirmText = confirmText,
        onConfirm = onConfirm,
        confirmEnabled = confirmEnabled,
        confirmLoading = confirmLoading,
        dismissText = dismissText,
        onDismiss = onDismiss,
        properties = properties,
        content = extraContent,
    )
}

/**
 * Capsule primary action button styled for Anode dialogs.
 */
@Composable
fun PulseDialogPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: PulseDialogTone = PulseDialogTone.PRIMARY,
    enabled: Boolean = true,
    loading: Boolean = false,
    useTonal: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val containerColor: Color
    val contentColor: Color

    if (useTonal) {
        when (tone) {
            PulseDialogTone.PRIMARY -> {
                containerColor = MaterialTheme.colorScheme.primaryContainer
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            }
            PulseDialogTone.WARM -> {
                containerColor = ext.drumstickContainer
                contentColor = ext.onDrumstickContainer
            }
            PulseDialogTone.DESTRUCTIVE -> {
                containerColor = MaterialTheme.colorScheme.errorContainer
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            }
        }
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled && !loading,
            shape = CircleShape,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            modifier = modifier.heightIn(min = 42.dp),
        ) {
            PulseDialogButtonContent(
                text = text,
                loading = loading,
                indicatorColor = contentColor,
                leadingIcon = leadingIcon,
            )
        }
    } else {
        when (tone) {
            PulseDialogTone.PRIMARY -> {
                containerColor = MaterialTheme.colorScheme.primary
                contentColor = MaterialTheme.colorScheme.onPrimary
            }
            PulseDialogTone.WARM -> {
                containerColor = MaterialTheme.colorScheme.tertiary
                contentColor = MaterialTheme.colorScheme.onTertiary
            }
            PulseDialogTone.DESTRUCTIVE -> {
                containerColor = MaterialTheme.colorScheme.error
                contentColor = MaterialTheme.colorScheme.onError
            }
        }
        Button(
            onClick = onClick,
            enabled = enabled && !loading,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            modifier = modifier.heightIn(min = 42.dp),
        ) {
            PulseDialogButtonContent(
                text = text,
                loading = loading,
                indicatorColor = contentColor,
                leadingIcon = leadingIcon,
            )
        }
    }
}

/**
 * Capsule secondary / dismiss action button styled for Anode dialogs.
 */
@Composable
fun PulseDialogSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark

    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(
                alpha = if (isDark) 0.78f else 0.88f,
            ),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = BorderStroke(
            width = 0.8.dp,
            color = ext.cardBorder.copy(alpha = if (isDark) 0.9f else 0.85f),
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        modifier = modifier.heightIn(min = 42.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PulseDialogButtonContent(
    text: String,
    loading: Boolean,
    indicatorColor: Color,
    leadingIcon: ImageVector? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                strokeWidth = 2.dp,
                color = indicatorColor,
            )
        } else if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PulseDialogHeroIconBadge(
    icon: ImageVector,
    tone: PulseDialogTone,
    accentColor: Color?,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val (haloColor, innerBrush, borderColor, iconTint) = resolveDialogBadgeColors(
        tone = tone,
        accentColor = accentColor,
        isDark = isDark,
        ext = ext,
    )

    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(haloColor),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(innerBrush)
                .border(width = 1.dp, color = borderColor, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun PulseDialogCompactIconBadge(
    icon: ImageVector,
    tone: PulseDialogTone,
    accentColor: Color?,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val (_, innerBrush, borderColor, iconTint) = resolveDialogBadgeColors(
        tone = tone,
        accentColor = accentColor,
        isDark = isDark,
        ext = ext,
    )

    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(innerBrush)
            .border(width = 0.9.dp, color = borderColor, shape = RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(20.dp),
        )
    }
}

private data class DialogBadgeColors(
    val haloColor: Color,
    val innerBrush: Brush,
    val borderColor: Color,
    val iconTint: Color,
)

@Composable
private fun resolveDialogBadgeColors(
    tone: PulseDialogTone,
    accentColor: Color?,
    isDark: Boolean,
    ext: NodePulseExtendedColors,
): DialogBadgeColors {
    val resolvedAccent = accentColor ?: when (tone) {
        PulseDialogTone.PRIMARY -> MaterialTheme.colorScheme.primary
        PulseDialogTone.WARM -> ext.drumstickGoldEnd
        PulseDialogTone.DESTRUCTIVE -> MaterialTheme.colorScheme.error
    }

    val containerStart: Color
    val containerEnd: Color
    val iconTint: Color
    when {
        accentColor != null -> {
            containerStart = accentColor.copy(alpha = if (isDark) 0.24f else 0.15f)
            containerEnd = accentColor.copy(alpha = if (isDark) 0.14f else 0.08f)
            iconTint = accentColor
        }
        tone == PulseDialogTone.WARM -> {
            containerStart = ext.drumstickContainer
            containerEnd = blendColor(ext.drumstickContainer, ext.drumstickGoldStart, 0.22f)
            iconTint = ext.onDrumstickContainer
        }
        tone == PulseDialogTone.DESTRUCTIVE -> {
            containerStart = MaterialTheme.colorScheme.errorContainer
            containerEnd = blendColor(
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.error,
                if (isDark) 0.16f else 0.08f,
            )
            iconTint = MaterialTheme.colorScheme.onErrorContainer
        }
        else -> {
            containerStart = MaterialTheme.colorScheme.primaryContainer
            containerEnd = blendColor(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.secondaryContainer,
                0.30f,
            )
            iconTint = MaterialTheme.colorScheme.onPrimaryContainer
        }
    }

    return DialogBadgeColors(
        haloColor = resolvedAccent.copy(alpha = if (isDark) 0.12f else 0.07f),
        innerBrush = Brush.linearGradient(colors = listOf(containerStart, containerEnd)),
        borderColor = resolvedAccent.copy(alpha = if (isDark) 0.42f else 0.26f),
        iconTint = iconTint,
    )
}

/**
 * Refined Anode popup dropdown menu with large rounded elevated sheet surface,
 * hairline border, soft ambient elevation, and clean offset spacing.
 */
@Composable
fun PulseDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 6.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    minWidth: Dp = 204.dp,
    maxWidth: Dp = 288.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val shape = RoundedCornerShape(20.dp)

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = minWidth, max = maxWidth),
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        shape = shape,
        containerColor = ext.cardSurfaceElevated,
        tonalElevation = 0.dp,
        shadowElevation = if (isDark) 8.dp else 12.dp,
        border = BorderStroke(
            width = 1.dp,
            color = if (isDark) ext.glassBorder else ext.cardBorder,
        ),
        content = content,
    )
}

/**
 * Styled menu row for [PulseDropdownMenu] with inset rounded highlight container,
 * optional soft-tinted leading icon badge, optional subtitle, active checkmark,
 * optional trailing status pill [badgeText], and destructive red styling.
 */
@Composable
fun PulseDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    selected: Boolean = false,
    badgeText: String? = null,
    isDestructive: Boolean = false,
    enabled: Boolean = true,
    accentColor: Color? = null,
    showCheckWhenSelected: Boolean = true,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val isDark = NodePulseThemeTokens.isDark
    val interactionSource = remember { MutableInteractionSource() }
    val itemShape = RoundedCornerShape(13.dp)

    val resolvedAccent = when {
        isDestructive -> MaterialTheme.colorScheme.error
        accentColor != null -> accentColor
        else -> MaterialTheme.colorScheme.primary
    }

    val rowBackground = when {
        selected -> resolvedAccent.copy(alpha = if (isDark) 0.16f else 0.095f)
        else -> Color.Transparent
    }
    val rowBorderColor = when {
        selected -> resolvedAccent.copy(alpha = if (isDark) 0.30f else 0.20f)
        else -> Color.Transparent
    }

    val iconBoxBackground = when {
        isDestructive -> MaterialTheme.colorScheme.errorContainer.copy(alpha = if (isDark) 0.48f else 0.78f)
        selected || accentColor != null -> resolvedAccent.copy(alpha = if (isDark) 0.22f else 0.13f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.85f else 0.88f)
    }
    val iconBoxBorder = when {
        isDestructive -> MaterialTheme.colorScheme.error.copy(alpha = if (isDark) 0.28f else 0.18f)
        selected || accentColor != null -> resolvedAccent.copy(alpha = if (isDark) 0.32f else 0.20f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.45f else 0.55f)
    }
    val iconTint = when {
        isDestructive -> MaterialTheme.colorScheme.error
        selected || accentColor != null -> resolvedAccent
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val titleColor = when {
        isDestructive -> MaterialTheme.colorScheme.error
        selected -> resolvedAccent
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.5.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .pulsePressScale(
                interactionSource = interactionSource,
                pressedScale = 0.98f,
            )
            .clip(itemShape)
            .background(rowBackground)
            .then(
                if (selected) {
                    Modifier.border(width = 0.8.dp, color = rowBorderColor, shape = itemShape)
                } else {
                    Modifier
                },
            )
            .semantics { this.selected = selected }
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .defaultMinSize(minHeight = 42.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(iconBoxBackground)
                    .border(
                        width = 0.75.dp,
                        color = iconBoxBorder,
                        shape = RoundedCornerShape(9.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(17.dp),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val hasCheck = trailingContent == null && selected && showCheckWhenSelected
        if (!badgeText.isNullOrBlank() || trailingContent != null || hasCheck) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!badgeText.isNullOrBlank()) {
                    val badgeContainer = when {
                        isDestructive -> MaterialTheme.colorScheme.errorContainer
                        selected -> resolvedAccent.copy(alpha = if (isDark) 0.24f else 0.14f)
                        accentColor != null -> accentColor.copy(alpha = if (isDark) 0.20f else 0.12f)
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    }
                    val badgeContentColor = when {
                        isDestructive -> MaterialTheme.colorScheme.onErrorContainer
                        selected || accentColor != null -> resolvedAccent
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(badgeContainer)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = badgeText,
                            style = NodePulseThemeTokens.extraTypography.microLabel,
                            color = badgeContentColor,
                            maxLines = 1,
                        )
                    }
                }

                if (trailingContent != null) {
                    trailingContent()
                } else if (hasCheck) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = resolvedAccent,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

/**
 * Compact section label for grouping items inside [PulseDropdownMenu].
 */
@Composable
fun PulseDropdownMenuSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.78f)),
        )
        Text(
            text = title,
            style = NodePulseThemeTokens.extraTypography.microLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Gradient-faded divider separating logical groups inside [PulseDropdownMenu].
 */
@Composable
fun PulseDropdownMenuDivider(
    modifier: Modifier = Modifier,
) {
    PulseGradientDivider(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
    )
}

/**
 * Drop-in replacement for Material [SnackbarHost] rendering [PulseSnackbarPill].
 */
@Composable
fun PulseSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    pillModifier: Modifier = Modifier.padding(bottom = 12.dp),
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
    ) { data ->
        PulseSnackbarPill(
            data = data,
            modifier = pillModifier,
        )
    }
}
