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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.pulseBouncyClickable

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SeekMailActionBottomSheet(
    mail: SeekMailSummary,
    isArchived: Boolean,
    localTags: List<SeekMailLocalTag>,
    onToggleArchive: () -> Unit,
    onManageTags: () -> Unit,
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
                text = "存档与删除仅保存在本机并按 API Key 隔离，操作后支持在底部提示栏撤销。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
