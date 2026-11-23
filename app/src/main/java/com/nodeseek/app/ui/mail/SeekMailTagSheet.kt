package com.nodeseek.app.ui.mail

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodeseek.app.domain.mail.SeekMailLocalTag
import com.nodeseek.app.domain.mail.SeekMailSummary
import com.nodeseek.app.ui.components.PulseTextField
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.blendColor
import com.nodeseek.app.ui.theme.pulseBouncyClickable

internal data class MailTagPreset(
    val name: String,
    val colorHex: String,
)

private val MAIL_TAG_COLOR_PALETTE: List<String> = listOf(
    "#2D637E", // Steel Teal (Anode signature)
    "#0F766E", // Deep Emerald
    "#2563EB", // Cobalt Blue
    "#7C3AED", // Violet
    "#D97706", // Amber Gold
    "#EA580C", // Warm Coral
    "#E11D48", // Crimson Rose
    "#64748B", // Slate Neutral
)

private val MAIL_TAG_QUICK_PRESETS: List<MailTagPreset> = listOf(
    MailTagPreset(name = "重要", colorHex = "#D97706"),
    MailTagPreset(name = "VPS", colorHex = "#2D637E"),
    MailTagPreset(name = "账单", colorHex = "#0F766E"),
    MailTagPreset(name = "验证码", colorHex = "#7C3AED"),
    MailTagPreset(name = "待办", colorHex = "#E11D48"),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SeekMailTagBottomSheet(
    mail: SeekMailSummary,
    allLocalTags: List<SeekMailLocalTag>,
    assignedTagIds: Set<String>,
    onToggleTag: (String) -> Unit,
    onCreateAndAttachTag: (name: String, colorHex: String) -> Unit,
    onClearMailTags: () -> Unit,
    onDeleteTagDefinition: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val isDark = NodePulseThemeTokens.isDark

    var newTagDraft by rememberSaveable(mail.id) { mutableStateOf("") }
    var selectedColorHex by rememberSaveable(mail.id) { mutableStateOf(MAIL_TAG_COLOR_PALETTE.first()) }
    var isManageMode by rememberSaveable(mail.id) { mutableStateOf(false) }

    val existingLowerNames = remember(allLocalTags) {
        allLocalTags.mapTo(HashSet()) { it.name.trim().lowercase() }
    }
    val remainingPresets = remember(existingLowerNames) {
        MAIL_TAG_QUICK_PRESETS.filterNot { it.name.lowercase() in existingLowerNames }
    }
    val selectedColor = remember(selectedColorHex) {
        parseMailHexColorOrNull(selectedColorHex)
    } ?: MaterialTheme.colorScheme.primary

    val submitNewTag: () -> Unit = {
        val clean = newTagDraft.trim().take(20)
        if (clean.isNotEmpty()) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onCreateAndAttachTag(clean, selectedColorHex)
            newTagDraft = ""
            keyboardController?.hide()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── Header ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(PulseArchitecturalShapes.MediumBadge)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Label,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = "邮件标签",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = mail.subject.ifBlank { mail.fromName.ifBlank { mail.fromAddress } },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (assignedTagIds.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onClearMailTags()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "移除全部",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }

            PulseGradientDivider()

            // ── Existing Tags (Toggle Assign / Remove or Delete Tag) ──
            if (allLocalTags.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (isManageMode) "点击 × 删除标签定义" else "已有标签（点击打标或移除）",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(role = Role.Button) { isManageMode = !isManageMode }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(
                                imageVector = if (isManageMode) Icons.Rounded.Check else Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                tint = if (isManageMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = if (isManageMode) "完成管理" else "管理",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isManageMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        allLocalTags.forEach { tag ->
                            val isAssigned = tag.id in assignedTagIds
                            SelectableLocalTagPill(
                                tag = tag,
                                selected = isAssigned,
                                manageMode = isManageMode,
                                onToggle = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onToggleTag(tag.id)
                                },
                                onDelete = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onDeleteTagDefinition(tag.id)
                                },
                            )
                        }
                    }
                }
            }

            // ── Quick Presets ──
            if (remainingPresets.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        text = "快捷预设",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        remainingPresets.forEach { preset ->
                            val presetColor = remember(preset.colorHex) {
                                parseMailHexColorOrNull(preset.colorHex)
                            } ?: MaterialTheme.colorScheme.primary
                            Row(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.72f else 0.90f))
                                    .border(
                                        width = 0.8.dp,
                                        color = NodePulseThemeTokens.extendedColors.cardBorder,
                                        shape = CircleShape,
                                    )
                                    .pulseBouncyClickable(
                                        pressedScale = NodePulseMotion.chipPressScale,
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onCreateAndAttachTag(preset.name, preset.colorHex)
                                        },
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    tint = presetColor,
                                    modifier = Modifier.size(13.dp),
                                )
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(presetColor),
                                )
                                Text(
                                    text = preset.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }

            // ── Create Custom Tag ──
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PulseTextField(
                    value = newTagDraft,
                    onValueChange = { if (it.length <= 20) newTagDraft = it },
                    label = "新建标签",
                    placeholder = "输入标签名称，例如：主力鸡 / 续费提醒",
                    singleLine = true,
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(selectedColor),
                        )
                    },
                    trailingIcon = {
                        val canAdd = newTagDraft.trim().isNotEmpty()
                        Row(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .clip(CircleShape)
                                .background(
                                    if (canAdd) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                )
                                .clickable(enabled = canAdd, role = Role.Button, onClick = submitNewTag)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                tint = if (canAdd) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "添加",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (canAdd) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitNewTag() }),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Color Swatch Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "颜色",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MAIL_TAG_COLOR_PALETTE.forEach { hex ->
                            val swatchColor = remember(hex) {
                                parseMailHexColorOrNull(hex)
                            } ?: MaterialTheme.colorScheme.primary
                            val isSelected = selectedColorHex.equals(hex, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(swatchColor)
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            swatchColor.copy(alpha = 0.35f)
                                        },
                                        shape = CircleShape,
                                    )
                                    .clickable(role = Role.RadioButton) {
                                        selectedColorHex = hex
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "已选颜色",
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Text(
                text = "标签保存在本地并按邮件 ID 持久化，可在收件箱顶部筛选栏按标签快速筛选。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = onDismiss,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "完成",
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun SelectableLocalTagPill(
    tag: SeekMailLocalTag,
    selected: Boolean,
    manageMode: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val isDark = NodePulseThemeTokens.isDark
    val accent = remember(tag.colorHex) { parseMailHexColorOrNull(tag.colorHex) }
        ?: MaterialTheme.colorScheme.primary
    val bgColor by animateColorAsState(
        targetValue = if (selected) {
            accent.copy(alpha = if (isDark) 0.28f else 0.16f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.72f else 0.88f)
        },
        label = "selectableTagBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            accent.copy(alpha = if (isDark) 0.72f else 0.55f)
        } else {
            NodePulseThemeTokens.extendedColors.cardBorder
        },
        label = "selectableTagBorder",
    )
    val labelColor = if (selected) {
        if (isDark) blendColor(accent, Color.White, 0.32f) else blendColor(accent, Color.Black, 0.28f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(bgColor)
            .border(
                width = if (selected) 1.1.dp else 0.8.dp,
                color = borderColor,
                shape = CircleShape,
            )
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                onClick = if (manageMode) onDelete else onToggle,
            )
            .padding(
                start = 10.dp,
                end = if (manageMode) 7.dp else 11.dp,
                top = 6.dp,
                bottom = 6.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (selected && !manageMode) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "已选中",
                tint = labelColor,
                modifier = Modifier.size(13.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
        }
        Text(
            text = tag.name,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            ),
            color = labelColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        if (manageMode) {
            Spacer(modifier = Modifier.width(1.dp))
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "删除标签 ${tag.name}",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
internal fun SeekMailCompactTagChip(
    name: String,
    colorHex: String,
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark
    val fontScale = NodePulseThemeTokens.fontScale
    val parsedColor = remember(colorHex) { parseMailHexColorOrNull(colorHex) }
        ?: MaterialTheme.colorScheme.secondary
    val bgColor = parsedColor.copy(alpha = if (isDark) 0.20f else 0.11f)
    val borderColor = parsedColor.copy(alpha = if (isDark) 0.44f else 0.25f)
    val textColor = if (isDark) {
        blendColor(parsedColor, Color.White, 0.30f)
    } else {
        blendColor(parsedColor, Color.Black, 0.28f)
    }
    val shape = RoundedCornerShape(6.dp)

    Row(
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .border(width = 0.7.dp, color = borderColor, shape = shape)
            .padding(horizontal = 5.5.dp, vertical = 1.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(parsedColor),
        )
        Text(
            text = name,
            style = NodePulseThemeTokens.extraTypography.microLabel.copy(
                fontSize = (10f * fontScale).sp,
                lineHeight = (13f * fontScale).sp,
            ),
            color = textColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun parseMailHexColorOrNull(raw: String): Color? {
    val hex = raw.trim().removePrefix("#")
    if (hex.isEmpty()) return null
    val normalized = when (hex.length) {
        3 -> buildString {
            append("FF")
            hex.forEach { c ->
                append(c)
                append(c)
            }
        }
        6 -> "FF$hex"
        8 -> hex
        else -> return null
    }
    val argb = normalized.toLongOrNull(16) ?: return null
    return Color(argb.toInt())
}
