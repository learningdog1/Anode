package com.nodeseek.app.ui.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.mail.SeekMailLocalTag
import com.nodeseek.app.domain.mail.SeekMailSummary
import com.nodeseek.app.ui.components.RelativeTimeText
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseGlassSurface
import com.nodeseek.app.ui.theme.withTabularNumbers

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SeekMailActionBottomSheet(
    mail: SeekMailSummary,
    isArchived: Boolean,
    localTags: List<SeekMailLocalTag>,
    onToggleArchive: () -> Unit,
    onManageTags: () -> Unit,
    onStartMultiSelect: () -> Unit,
    onRequestDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = LocalHapticFeedback.current
    val senderLabel = mail.fromName.ifBlank { mail.fromAddress }.ifBlank { "未知发件人" }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Compact Mail Context Header ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                MailSenderAvatar(
                    senderName = senderLabel,
                    senderAddress = mail.fromAddress,
                    isUnread = !mail.isRead,
                    size = 36.dp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = senderLabel,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isArchived) {
                            PulseAccentPill(
                                text = "已存档",
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        RelativeTimeText(
                            isoTimestamp = mail.receivedAt.ifBlank { mail.createdAt },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text(
                        text = mail.subject.ifBlank { "(无主题)" },
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (localTags.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            localTags.take(4).forEach { tag ->
                                SeekMailCompactTagChip(
                                    name = tag.name,
                                    colorHex = tag.colorHex,
                                )
                            }
                        }
                    }
                }
            }

            PulseGradientDivider()

            // ── Primary Mail Actions ──
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MailActionSheetRow(
                    icon = if (isArchived) Icons.Rounded.Unarchive else Icons.Rounded.Archive,
                    title = if (isArchived) "取消存档" else "存档邮件",
                    subtitle = if (isArchived) {
                        "将此邮件移回收件箱列表"
                    } else {
                        "移出收件箱并收纳至「已存档」，随时可查看或恢复"
                    },
                    accentColor = MaterialTheme.colorScheme.secondary,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleArchive()
                    },
                )

                MailActionSheetRow(
                    icon = Icons.AutoMirrored.Rounded.Label,
                    title = "管理标签",
                    subtitle = if (localTags.isEmpty()) {
                        "为邮件添加或自定义本地分类标签"
                    } else {
                        "已添加 ${localTags.size} 个本地标签，点击编辑"
                    },
                    accentColor = MaterialTheme.colorScheme.primary,
                    badgeText = localTags.size.takeIf { it > 0 }?.toString(),
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onManageTags()
                    },
                )

                MailActionSheetRow(
                    icon = Icons.Rounded.Checklist,
                    title = "批量多选",
                    subtitle = "选中此邮件并进入多选模式，批量存档、打标或删除",
                    accentColor = MaterialTheme.colorScheme.tertiary,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onStartMultiSelect()
                    },
                )

                MailActionSheetRow(
                    icon = Icons.Rounded.DeleteOutline,
                    title = "删除邮件",
                    subtitle = "从本地列表移除，不影响 seek.li 服务器原件",
                    accentColor = MaterialTheme.colorScheme.error,
                    isDestructive = true,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onRequestDelete()
                    },
                )
            }

            Text(
                text = "提示：直接点击邮件左侧头像或筛选栏「多选」也可快速进入批量选择。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SeekMailSelectionTopBar(
    selectedCount: Int,
    visibleCount: Int,
    isAllSelected: Boolean,
    onExitSelection: () -> Unit,
    onToggleSelectAll: () -> Unit,
) {
    val isDark = NodePulseThemeTokens.isDark
    val haptics = LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pulseGlassSurface(
                showTopBorder = false,
                showBottomBorder = true,
                tonalAccent = MaterialTheme.colorScheme.primary,
            )
            .statusBarsPadding()
            .padding(start = 6.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onExitSelection()
            },
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "退出多选",
                modifier = Modifier.size(22.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = if (selectedCount > 0) "已选 $selectedCount 封" else "批量选择邮件",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold)
                    .withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (visibleCount > 0) {
                    "点击卡片切换选中状态 · 当前列表共 $visibleCount 封"
                } else {
                    "当前列表暂无可选邮件"
                },
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (visibleCount > 0) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (isAllSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDark) 0.85f else 0.92f)
                        },
                    )
                    .border(
                        width = 0.8.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = if (isAllSelected) 0.8f else 0.3f),
                        shape = CircleShape,
                    )
                    .pulseBouncyClickable(
                        pressedScale = NodePulseMotion.chipPressScale,
                        role = Role.Button,
                        onClickLabel = if (isAllSelected) "取消全选" else "全选当前列表",
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleSelectAll()
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = if (isAllSelected) Icons.Rounded.RemoveDone else Icons.Rounded.DoneAll,
                    contentDescription = null,
                    tint = if (isAllSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = if (isAllSelected) "取消全选" else "全选",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (isAllSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
internal fun SeekMailBatchActionBar(
    selectedCount: Int,
    allSelectedArchived: Boolean,
    hasUnreadSelected: Boolean,
    onArchiveOrUnarchive: () -> Unit,
    onBatchTag: () -> Unit,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = NodePulseThemeTokens.isDark
    val ext = NodePulseThemeTokens.extendedColors
    val haptics = LocalHapticFeedback.current
    val hasSelection = selectedCount > 0

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .border(
                width = 1.dp,
                color = if (isDark) ext.glassBorder else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                shape = PulseArchitecturalShapes.GlassBar,
            ),
        shape = PulseArchitecturalShapes.GlassBar,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.96f else 0.98f),
        shadowElevation = PulseElevation.FloatingBar,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BatchBarActionButton(
                icon = if (allSelectedArchived) Icons.Rounded.Unarchive else Icons.Rounded.Archive,
                label = if (allSelectedArchived) "取消存档" else "存档",
                tint = MaterialTheme.colorScheme.secondary,
                enabled = hasSelection,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onArchiveOrUnarchive()
                },
                modifier = Modifier.weight(1f),
            )

            BatchBarActionButton(
                icon = Icons.AutoMirrored.Rounded.Label,
                label = "标签",
                tint = MaterialTheme.colorScheme.primary,
                enabled = hasSelection,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onBatchTag()
                },
                modifier = Modifier.weight(1f),
            )

            BatchBarActionButton(
                icon = Icons.Rounded.MarkEmailRead,
                label = "标为已读",
                tint = MaterialTheme.colorScheme.onSurface,
                enabled = hasSelection && hasUnreadSelected,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onMarkRead()
                },
                modifier = Modifier.weight(1f),
            )

            BatchBarActionButton(
                icon = Icons.Rounded.DeleteOutline,
                label = "删除",
                tint = MaterialTheme.colorScheme.error,
                enabled = hasSelection,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDelete()
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BatchBarActionButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.36f }
            .clip(RoundedCornerShape(14.dp))
            .pulseBouncyClickable(
                enabled = enabled,
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = NodePulseThemeTokens.extraTypography.microLabel.copy(fontWeight = FontWeight.SemiBold),
            color = tint,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MailActionSheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit,
    badgeText: String? = null,
    isDestructive: Boolean = false,
) {
    val isDark = NodePulseThemeTokens.isDark
    val ext = NodePulseThemeTokens.extendedColors
    val rowShape = RoundedCornerShape(14.dp)
    val rowBg = if (isDestructive) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = if (isDark) 0.24f else 0.42f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.72f else 0.88f)
    }
    val rowBorder = if (isDestructive) {
        MaterialTheme.colorScheme.error.copy(alpha = if (isDark) 0.36f else 0.22f)
    } else {
        ext.cardBorder
    }
    val iconContainerBg = if (isDestructive) {
        MaterialTheme.colorScheme.error.copy(alpha = if (isDark) 0.22f else 0.12f)
    } else {
        accentColor.copy(alpha = if (isDark) 0.22f else 0.12f)
    }
    val titleColor = if (isDestructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(rowBg)
            .border(width = 0.8.dp, color = rowBorder, shape = rowShape)
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.cardPressScale,
                role = Role.Button,
                onClickLabel = title,
                onClick = onClick,
            )
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(iconContainerBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(19.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (!badgeText.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = if (isDark) 0.24f else 0.14f))
                    .padding(horizontal = 8.dp, vertical = 2.5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = badgeText,
                    style = NodePulseThemeTokens.extraTypography.microLabel,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                )
            }
        }

        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = if (isDestructive) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            },
            modifier = Modifier.size(18.dp),
        )
    }
}
