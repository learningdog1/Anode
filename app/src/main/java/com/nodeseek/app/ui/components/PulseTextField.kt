package com.nodeseek.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseElevation

/**
 * Shared design tokens for Anode text inputs and search fields.
 */
object PulseTextFieldDefaults {
    val Shape: Shape = RoundedCornerShape(14.dp)
    val SearchShape: Shape = CircleShape
    val MinHeight: Dp = 48.dp
    val SearchMinHeight: Dp = 44.dp
    internal val LabelStartPadding: Dp = 14.dp
    internal val LabelEndPadding: Dp = 14.dp
    internal val CutoutHorizontalPadding: Dp = 4.dp
    internal const val StateAnimMillis: Int = 150
}

/**
 * Compact, single-line-first outlined text field tailored to Anode's Cool Mist Light,
 * Slate Steel Dark, Pure Pitch AMOLED, and Material You Dynamic Color themes.
 *
 * Key visual guarantees:
 * - When [label] is provided, it **always floats on the top border** with a crisp border cutout
 *   and `maxLines = 1, overflow = TextOverflow.Ellipsis` — never wrapping onto 2 lines inside
 *   the box or pushing height up when leading/trailing actions are present.
 * - [placeholder] is immediately visible inside the box whenever [value] is empty, even before
 *   the field gains focus.
 * - Compact vertical height ([PulseTextFieldDefaults.MinHeight] = 48.dp) with balanced alignment
 *   for [leadingIcon], [prefix], [suffix], and [trailingIcon] slots.
 */
@Composable
fun PulseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    textStyle: TextStyle? = null,
    shape: Shape = PulseTextFieldDefaults.Shape,
    containerColor: Color = Color.Unspecified,
    minHeight: Dp = PulseTextFieldDefaults.MinHeight,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource? = null,
) {
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val isFocused by resolvedInteractionSource.collectIsFocusedAsState()
    val colorScheme = MaterialTheme.colorScheme
    val effectiveMinLines = if (singleLine) 1 else minLines.coerceAtLeast(1)
    val effectiveMaxLines = if (singleLine) 1 else maxLines.coerceAtLeast(effectiveMinLines)

    val resolvedTextStyle = resolvePulseInputTextStyle(
        overrideStyle = textStyle,
        enabled = enabled,
    )
    val accentColor = if (isError) colorScheme.error else colorScheme.primary
    val selectionColors = remember(accentColor) {
        TextSelectionColors(
            handleColor = accentColor,
            backgroundColor = accentColor.copy(alpha = 0.28f),
        )
    }
    val semanticsModifier = if (isError) {
        modifier.semantics { error(supportingText ?: "输入内容有误") }
    } else {
        modifier
    }

    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = semanticsModifier,
            enabled = enabled,
            readOnly = readOnly,
            textStyle = resolvedTextStyle,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            maxLines = effectiveMaxLines,
            minLines = effectiveMinLines,
            visualTransformation = visualTransformation,
            interactionSource = resolvedInteractionSource,
            cursorBrush = SolidColor(accentColor),
            decorationBox = { innerTextField ->
                PulseTextFieldDecorationBox(
                    isEmpty = value.isEmpty(),
                    isFocused = isFocused,
                    enabled = enabled,
                    isError = isError,
                    singleLine = singleLine,
                    maxLines = effectiveMaxLines,
                    label = label,
                    placeholder = placeholder,
                    supportingText = supportingText,
                    supportingContent = supportingContent,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon,
                    prefix = prefix,
                    suffix = suffix,
                    textStyle = resolvedTextStyle,
                    shape = shape,
                    containerColor = containerColor,
                    minHeight = minHeight,
                    innerTextField = innerTextField,
                )
            },
        )
    }
}

/**
 * [TextFieldValue] overload of [PulseTextField] for cursor- and selection-aware editors.
 */
@Composable
fun PulseTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    textStyle: TextStyle? = null,
    shape: Shape = PulseTextFieldDefaults.Shape,
    containerColor: Color = Color.Unspecified,
    minHeight: Dp = PulseTextFieldDefaults.MinHeight,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource? = null,
) {
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val isFocused by resolvedInteractionSource.collectIsFocusedAsState()
    val colorScheme = MaterialTheme.colorScheme
    val effectiveMinLines = if (singleLine) 1 else minLines.coerceAtLeast(1)
    val effectiveMaxLines = if (singleLine) 1 else maxLines.coerceAtLeast(effectiveMinLines)

    val resolvedTextStyle = resolvePulseInputTextStyle(
        overrideStyle = textStyle,
        enabled = enabled,
    )
    val accentColor = if (isError) colorScheme.error else colorScheme.primary
    val selectionColors = remember(accentColor) {
        TextSelectionColors(
            handleColor = accentColor,
            backgroundColor = accentColor.copy(alpha = 0.28f),
        )
    }
    val semanticsModifier = if (isError) {
        modifier.semantics { error(supportingText ?: "输入内容有误") }
    } else {
        modifier
    }

    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = semanticsModifier,
            enabled = enabled,
            readOnly = readOnly,
            textStyle = resolvedTextStyle,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            maxLines = effectiveMaxLines,
            minLines = effectiveMinLines,
            visualTransformation = visualTransformation,
            interactionSource = resolvedInteractionSource,
            cursorBrush = SolidColor(accentColor),
            decorationBox = { innerTextField ->
                PulseTextFieldDecorationBox(
                    isEmpty = value.text.isEmpty(),
                    isFocused = isFocused,
                    enabled = enabled,
                    isError = isError,
                    singleLine = singleLine,
                    maxLines = effectiveMaxLines,
                    label = label,
                    placeholder = placeholder,
                    supportingText = supportingText,
                    supportingContent = supportingContent,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon,
                    prefix = prefix,
                    suffix = suffix,
                    textStyle = resolvedTextStyle,
                    shape = shape,
                    containerColor = containerColor,
                    minHeight = minHeight,
                    innerTextField = innerTextField,
                )
            },
        )
    }
}

/**
 * Compact pill search field consistent with Anode's search bars across Boards, Deals, Mail,
 * Restock, Inbox, and picker bottom sheets.
 */
@Composable
fun PulseSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索…",
    enabled: Boolean = true,
    shape: Shape = PulseTextFieldDefaults.SearchShape,
    containerColor: Color = Color.Unspecified,
    elevated: Boolean = true,
    minHeight: Dp = PulseTextFieldDefaults.SearchMinHeight,
    leadingIcon: ImageVector? = Icons.Rounded.Search,
    showClearButton: Boolean = value.isNotEmpty(),
    onClear: () -> Unit = { onValueChange("") },
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    keyboardActions: KeyboardActions? = null,
    interactionSource: MutableInteractionSource? = null,
    focusRequester: FocusRequester? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark
    val colorScheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val isFocused by resolvedInteractionSource.collectIsFocusedAsState()

    val resolvedContainerColor = if (containerColor != Color.Unspecified) {
        containerColor
    } else if (isFocused) {
        ext.cardSurfaceElevated
    } else {
        ext.cardSurface
    }
    val animatedContainerColor by animateColorAsState(
        targetValue = resolvedContainerColor,
        animationSpec = tween(PulseTextFieldDefaults.StateAnimMillis),
        label = "pulseSearchContainer",
    )
    val animatedBorderColor by animateColorAsState(
        targetValue = when {
            !enabled -> colorScheme.outlineVariant.copy(alpha = 0.45f)
            isFocused -> colorScheme.primary
            else -> ext.cardBorder
        },
        animationSpec = tween(PulseTextFieldDefaults.StateAnimMillis),
        label = "pulseSearchBorderColor",
    )
    val animatedBorderWidth by animateDpAsState(
        targetValue = if (isFocused) 1.4.dp else 1.dp,
        animationSpec = tween(PulseTextFieldDefaults.StateAnimMillis),
        label = "pulseSearchBorderWidth",
    )
    val animatedIconTint by animateColorAsState(
        targetValue = when {
            !enabled -> colorScheme.onSurface.copy(alpha = 0.38f)
            isFocused -> colorScheme.primary
            else -> colorScheme.onSurfaceVariant
        },
        animationSpec = tween(PulseTextFieldDefaults.StateAnimMillis),
        label = "pulseSearchIconTint",
    )

    val textStyle = MaterialTheme.typography.bodyMedium.copy(
        color = if (enabled) colorScheme.onSurface else colorScheme.onSurface.copy(alpha = 0.38f),
    )
    val resolvedKeyboardActions = keyboardActions ?: KeyboardActions(
        onSearch = {
            if (onSearch != null) {
                onSearch()
            } else {
                focusManager.clearFocus()
            }
        },
    )

    var fieldModifier = modifier
    if (focusRequester != null) {
        fieldModifier = fieldModifier.focusRequester(focusRequester)
    }
    if (onFocusChanged != null) {
        fieldModifier = fieldModifier.onFocusChanged { onFocusChanged(it.isFocused) }
    }

    val selectionColors = remember(colorScheme.primary) {
        TextSelectionColors(
            handleColor = colorScheme.primary,
            backgroundColor = colorScheme.primary.copy(alpha = 0.28f),
        )
    }

    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier,
            enabled = enabled,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(colorScheme.primary),
            keyboardOptions = keyboardOptions,
            keyboardActions = resolvedKeyboardActions,
            interactionSource = resolvedInteractionSource,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = minHeight)
                        .then(
                            if (!isDark && elevated) {
                                Modifier.shadow(
                                    elevation = if (isFocused) PulseElevation.CardElevated else PulseElevation.Subtle,
                                    shape = shape,
                                    clip = false,
                                    ambientColor = ext.softShadowColor,
                                    spotColor = ext.softShadowColor,
                                )
                            } else {
                                Modifier
                            },
                        )
                        .clip(shape)
                        .background(animatedContainerColor)
                        .border(
                            width = animatedBorderWidth,
                            color = animatedBorderColor,
                            shape = shape,
                        )
                        .padding(start = 13.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (leadingIcon != null) {
                        Icon(
                            imageVector = leadingIcon,
                            contentDescription = null,
                            tint = animatedIconTint,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colorScheme.onSurfaceVariant.copy(alpha = 0.74f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }

                    AnimatedVisibility(
                        visible = showClearButton && enabled,
                        enter = scaleIn(NodePulseMotion.microBounceSpring()) + fadeIn(tween(120)),
                        exit = fadeOut(tween(110)),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable(role = Role.Button, onClick = onClear),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "清空搜索",
                                    tint = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }

                    if (trailingContent != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            content = trailingContent,
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun resolvePulseInputTextStyle(
    overrideStyle: TextStyle?,
    enabled: Boolean,
): TextStyle {
    val colorScheme = MaterialTheme.colorScheme
    val fallbackColor = if (enabled) {
        colorScheme.onSurface
    } else {
        colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val baseStyle = overrideStyle ?: MaterialTheme.typography.bodyLarge.let { bodyLarge ->
        bodyLarge.copy(
            fontSize = bodyLarge.fontSize * 0.95f,
            lineHeight = bodyLarge.fontSize * 1.36f,
        )
    }
    val resolvedColor = baseStyle.color.takeOrElse { fallbackColor }
    return baseStyle.copy(color = resolvedColor)
}

@Composable
private fun PulseTextFieldDecorationBox(
    isEmpty: Boolean,
    isFocused: Boolean,
    enabled: Boolean,
    isError: Boolean,
    singleLine: Boolean,
    maxLines: Int,
    label: String?,
    placeholder: String?,
    supportingText: String?,
    supportingContent: (@Composable () -> Unit)?,
    leadingIcon: (@Composable () -> Unit)?,
    trailingIcon: (@Composable () -> Unit)?,
    prefix: (@Composable () -> Unit)?,
    suffix: (@Composable () -> Unit)?,
    textStyle: TextStyle,
    shape: Shape,
    containerColor: Color,
    minHeight: Dp,
    innerTextField: @Composable () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = NodePulseThemeTokens.isDark
    val hasLabel = !label.isNullOrBlank()

    val targetContainerColor = if (containerColor != Color.Unspecified) {
        containerColor
    } else {
        Color.Transparent
    }
    val targetBorderColor = when {
        !enabled -> colorScheme.outlineVariant.copy(alpha = 0.45f)
        isError -> colorScheme.error
        isFocused -> colorScheme.primary
        isDark -> colorScheme.outline.copy(alpha = 0.54f)
        else -> colorScheme.outline.copy(alpha = 0.56f)
    }
    val targetBorderWidth = when {
        isFocused -> 1.6.dp
        isError -> 1.4.dp
        else -> 1.dp
    }
    val targetLabelColor = when {
        !enabled -> colorScheme.onSurface.copy(alpha = 0.38f)
        isError -> colorScheme.error
        isFocused -> colorScheme.primary
        else -> colorScheme.onSurfaceVariant
    }
    val targetLeadingIconColor = when {
        !enabled -> colorScheme.onSurface.copy(alpha = 0.38f)
        isError -> colorScheme.error
        isFocused -> colorScheme.primary
        else -> colorScheme.onSurfaceVariant
    }
    val targetTrailingIconColor = when {
        !enabled -> colorScheme.onSurface.copy(alpha = 0.38f)
        isError -> colorScheme.error
        else -> colorScheme.onSurfaceVariant
    }

    val animSpecColor = tween<Color>(PulseTextFieldDefaults.StateAnimMillis)
    val animSpecDp = tween<Dp>(PulseTextFieldDefaults.StateAnimMillis)

    val containerColorState = animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = animSpecColor,
        label = "pulseFieldContainer",
    )
    val borderColorState = animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = animSpecColor,
        label = "pulseFieldBorderColor",
    )
    val borderWidthState = animateDpAsState(
        targetValue = targetBorderWidth,
        animationSpec = animSpecDp,
        label = "pulseFieldBorderWidth",
    )
    val labelColor by animateColorAsState(
        targetValue = targetLabelColor,
        animationSpec = animSpecColor,
        label = "pulseFieldLabelColor",
    )
    val leadingIconColor by animateColorAsState(
        targetValue = targetLeadingIconColor,
        animationSpec = animSpecColor,
        label = "pulseFieldLeadingColor",
    )
    val trailingIconColor by animateColorAsState(
        targetValue = targetTrailingIconColor,
        animationSpec = animSpecColor,
        label = "pulseFieldTrailingColor",
    )

    val placeholderColor = if (enabled) {
        colorScheme.onSurfaceVariant.copy(alpha = 0.68f)
    } else {
        colorScheme.onSurface.copy(alpha = 0.32f)
    }
    val affixColor = if (enabled) {
        colorScheme.onSurfaceVariant
    } else {
        colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val labelTextStyle = MaterialTheme.typography.bodySmall.copy(
        fontWeight = FontWeight.Medium,
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        PulseNotchedOutlineLayout(
            hasLabel = hasLabel,
            shape = shape,
            containerColorState = containerColorState,
            borderColorState = borderColorState,
            borderWidthState = borderWidthState,
            label = {
                if (!label.isNullOrBlank()) {
                    Text(
                        text = label,
                        style = labelTextStyle,
                        color = labelColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = minHeight)
                    .padding(
                        start = if (leadingIcon != null) 12.dp else 14.dp,
                        end = if (trailingIcon != null) 6.dp else 14.dp,
                    ),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
            ) {
                if (leadingIcon != null) {
                    Box(
                        modifier = Modifier.padding(
                            top = if (singleLine) 0.dp else 13.dp,
                            end = 10.dp,
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        CompositionLocalProvider(LocalContentColor provides leadingIconColor) {
                            leadingIcon()
                        }
                    }
                }

                if (prefix != null) {
                    Box(
                        modifier = Modifier.padding(
                            top = if (singleLine) 0.dp else 12.dp,
                            end = 4.dp,
                        ),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        CompositionLocalProvider(
                            LocalContentColor provides affixColor,
                            LocalTextStyle provides textStyle.copy(color = affixColor),
                        ) {
                            prefix()
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = if (singleLine) 10.dp else 12.dp),
                    contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                ) {
                    if (isEmpty && !placeholder.isNullOrEmpty()) {
                        Text(
                            text = placeholder,
                            style = textStyle,
                            color = placeholderColor,
                            maxLines = if (singleLine) 1 else maxLines,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }

                if (suffix != null) {
                    Box(
                        modifier = Modifier.padding(
                            start = 6.dp,
                            top = if (singleLine) 0.dp else 12.dp,
                            end = if (trailingIcon != null) 2.dp else 0.dp,
                        ),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        CompositionLocalProvider(
                            LocalContentColor provides affixColor,
                            LocalTextStyle provides textStyle.copy(color = affixColor),
                        ) {
                            suffix()
                        }
                    }
                }

                if (trailingIcon != null) {
                    Box(
                        modifier = Modifier.padding(
                            start = 4.dp,
                            top = if (singleLine) 0.dp else 4.dp,
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        CompositionLocalProvider(LocalContentColor provides trailingIconColor) {
                            trailingIcon()
                        }
                    }
                }
            }
        }

        if (supportingContent != null || !supportingText.isNullOrBlank()) {
            val supportingColor = when {
                !enabled -> colorScheme.onSurface.copy(alpha = 0.38f)
                isError -> colorScheme.error
                else -> colorScheme.onSurfaceVariant
            }
            val supportingStyle = MaterialTheme.typography.bodySmall
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 4.dp),
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides supportingColor,
                    LocalTextStyle provides supportingStyle.copy(color = supportingColor),
                ) {
                    if (supportingContent != null) {
                        supportingContent()
                    } else if (!supportingText.isNullOrBlank()) {
                        Text(
                            text = supportingText,
                            style = supportingStyle,
                            color = supportingColor,
                        )
                    }
                }
            }
        }
    }
}

@Stable
private class NotchedCutoutState {
    var hasLabel by mutableStateOf(false)
    var topInsetPx by mutableFloatStateOf(0f)
    var labelWidthPx by mutableFloatStateOf(0f)
    var labelHeightPx by mutableFloatStateOf(0f)
    var labelStartPx by mutableFloatStateOf(0f)
    var cutoutHorizontalPaddingPx by mutableFloatStateOf(0f)
}

@Composable
private fun PulseNotchedOutlineLayout(
    hasLabel: Boolean,
    shape: Shape,
    containerColorState: State<Color>,
    borderColorState: State<Color>,
    borderWidthState: State<Dp>,
    label: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val labelStartPaddingPx = with(density) { PulseTextFieldDefaults.LabelStartPadding.roundToPx() }
    val labelEndPaddingPx = with(density) { PulseTextFieldDefaults.LabelEndPadding.roundToPx() }
    val cutoutHorizontalPaddingPx = with(density) { PulseTextFieldDefaults.CutoutHorizontalPadding.toPx() }
    val cutoutState = remember { NotchedCutoutState() }

    Layout(
        modifier = Modifier.drawBehind {
            val topInset = cutoutState.topInsetPx
            val boxHeight = (size.height - topInset).coerceAtLeast(0f)
            val strokeWidthPx = borderWidthState.value.toPx()
            val halfStroke = strokeWidthPx / 2f

            translate(top = topInset) {
                val bgColor = containerColorState.value
                if (bgColor.alpha > 0f && boxHeight > 0f) {
                    val fillOutline = shape.createOutline(
                        Size(size.width, boxHeight),
                        layoutDirection,
                        this,
                    )
                    drawOutline(outline = fillOutline, color = bgColor)
                }

                val strokeSize = Size(
                    width = (size.width - strokeWidthPx).coerceAtLeast(0f),
                    height = (boxHeight - strokeWidthPx).coerceAtLeast(0f),
                )
                if (strokeSize.width > 0f && strokeSize.height > 0f) {
                    val strokeOutline = shape.createOutline(strokeSize, layoutDirection, this)
                    val strokeStyle = Stroke(width = strokeWidthPx)
                    val strokeColor = borderColorState.value

                    val labelW = cutoutState.labelWidthPx
                    val labelH = cutoutState.labelHeightPx
                    if (cutoutState.hasLabel && labelW > 0f) {
                        val rawStart = (cutoutState.labelStartPx - cutoutState.cutoutHorizontalPaddingPx)
                            .coerceAtLeast(0f)
                        val rawEnd = (cutoutState.labelStartPx + labelW + cutoutState.cutoutHorizontalPaddingPx)
                            .coerceAtMost(size.width)
                        val cutoutLeft = if (layoutDirection == LayoutDirection.Rtl) {
                            size.width - rawEnd
                        } else {
                            rawStart
                        }
                        val cutoutRight = if (layoutDirection == LayoutDirection.Rtl) {
                            size.width - rawStart
                        } else {
                            rawEnd
                        }
                        val cutoutBottom = (labelH / 2f + strokeWidthPx).coerceAtLeast(strokeWidthPx * 2f)

                        clipRect(
                            left = cutoutLeft,
                            top = -strokeWidthPx,
                            right = cutoutRight,
                            bottom = cutoutBottom,
                            clipOp = ClipOp.Difference,
                        ) {
                            translate(left = halfStroke, top = halfStroke) {
                                drawOutline(
                                    outline = strokeOutline,
                                    color = strokeColor,
                                    style = strokeStyle,
                                )
                            }
                        }
                    } else {
                        translate(left = halfStroke, top = halfStroke) {
                            drawOutline(
                                outline = strokeOutline,
                                color = strokeColor,
                                style = strokeStyle,
                            )
                        }
                    }
                }
            }
        },
        content = {
            Box(propagateMinConstraints = true) {
                content()
            }

            if (hasLabel) {
                Box {
                    label()
                }
            }
        },
    ) { measurables, constraints ->
        val contentMeasurable = measurables[0]
        val labelMeasurable = measurables.getOrNull(1)

        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val labelMaxWidth = if (constraints.hasBoundedWidth) {
            (constraints.maxWidth - labelStartPaddingPx - labelEndPaddingPx).coerceAtLeast(0)
        } else {
            Constraints.Infinity
        }
        val labelPlaceable = labelMeasurable?.measure(
            looseConstraints.copy(maxWidth = labelMaxWidth),
        )
        val topInsetPx = if (labelPlaceable != null) labelPlaceable.height / 2 else 0

        val contentMaxHeight = if (constraints.hasBoundedHeight) {
            (constraints.maxHeight - topInsetPx).coerceAtLeast(0)
        } else {
            Constraints.Infinity
        }
        val contentPlaceable = contentMeasurable.measure(
            constraints.copy(
                minHeight = (constraints.minHeight - topInsetPx).coerceAtLeast(0),
                maxHeight = contentMaxHeight,
            ),
        )

        val nextHasLabel = labelPlaceable != null && labelPlaceable.width > 0
        val nextTopInset = topInsetPx.toFloat()
        val nextLabelW = labelPlaceable?.width?.toFloat() ?: 0f
        val nextLabelH = labelPlaceable?.height?.toFloat() ?: 0f
        val nextLabelStart = labelStartPaddingPx.toFloat()

        if (cutoutState.hasLabel != nextHasLabel) cutoutState.hasLabel = nextHasLabel
        if (cutoutState.topInsetPx != nextTopInset) cutoutState.topInsetPx = nextTopInset
        if (cutoutState.labelWidthPx != nextLabelW) cutoutState.labelWidthPx = nextLabelW
        if (cutoutState.labelHeightPx != nextLabelH) cutoutState.labelHeightPx = nextLabelH
        if (cutoutState.labelStartPx != nextLabelStart) cutoutState.labelStartPx = nextLabelStart
        if (cutoutState.cutoutHorizontalPaddingPx != cutoutHorizontalPaddingPx) {
            cutoutState.cutoutHorizontalPaddingPx = cutoutHorizontalPaddingPx
        }

        layout(contentPlaceable.width, topInsetPx + contentPlaceable.height) {
            contentPlaceable.placeRelative(0, topInsetPx)
            labelPlaceable?.placeRelative(labelStartPaddingPx, 0)
        }
    }
}
