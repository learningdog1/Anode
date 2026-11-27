package com.nodeseek.app.ui.trade

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.fleet.FleetDeliveryMethod
import com.nodeseek.app.domain.trade.TradeDeliveryChecklistEngine
import com.nodeseek.app.domain.trade.TradePrivacyOptions
import com.nodeseek.app.domain.trade.TradePrivacySanitizer
import com.nodeseek.app.domain.trade.TradeTitleFormatter
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.withTabularNumbers

private enum class TradeClosureTab(val label: String) {
    CLOSE_AND_MASK("结帖与脱敏"),
    DELIVERY_CHECKLIST("交割清单助手"),
}

private const val CHECKLIST_PREFS_NAME = "anode_trade_checklist_prefs"

private fun loadCompletedStepIds(context: Context, topicId: Long): Set<String> {
    if (topicId <= 0L) return emptySet()
    val prefs = context.getSharedPreferences(CHECKLIST_PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getStringSet("topic_$topicId", emptySet())?.toSet().orEmpty()
}

private fun saveCompletedStepIds(context: Context, topicId: Long, ids: Set<String>) {
    if (topicId <= 0L) return
    val prefs = context.getSharedPreferences(CHECKLIST_PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putStringSet("topic_$topicId", ids).apply()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TradeClosureSheet(
    topicId: Long,
    topicTitle: String,
    openingMarkdown: String?,
    isMutating: Boolean,
    onConfirmClosure: (TradePrivacyOptions) -> Unit,
    onCopyFeedback: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val isInspection = LocalInspectionMode.current
    val ext = NodePulseThemeTokens.extendedColors
    val tradeSpec = NodePulseThemeTokens.categorySpec("trade")
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val alreadySold = remember(topicTitle) { TradeTitleFormatter.isMarkedSold(topicTitle) }
    val nextTitle = remember(topicTitle) { TradeTitleFormatter.applySoldPrefix(topicTitle) }

    var activeTabName by rememberSaveable { mutableStateOf(TradeClosureTab.CLOSE_AND_MASK.name) }
    val activeTab = remember(activeTabName) { TradeClosureTab.valueOf(activeTabName) }

    var maskIps by rememberSaveable { mutableStateOf(true) }
    var maskEmails by rememberSaveable { mutableStateOf(true) }
    var maskCredentials by rememberSaveable { mutableStateOf(true) }
    var maskOrders by rememberSaveable { mutableStateOf(true) }
    var appendNote by rememberSaveable { mutableStateOf(true) }
    var showDiffPreview by rememberSaveable { mutableStateOf(false) }

    val options = remember(maskIps, maskEmails, maskCredentials, maskOrders, appendNote) {
        TradePrivacyOptions(
            maskIpAddresses = maskIps,
            maskEmails = maskEmails,
            maskCredentials = maskCredentials,
            maskOrderAndContactIds = maskOrders,
            appendClosureNote = appendNote,
        )
    }

    val previewMarkdown = openingMarkdown.orEmpty()
    val sanitizePreview = remember(previewMarkdown, options) {
        TradePrivacySanitizer.sanitize(previewMarkdown, options)
    }

    val inferredMethod = remember(topicTitle, previewMarkdown) {
        TradeDeliveryChecklistEngine.inferDeliveryMethod(topicTitle, previewMarkdown)
    }
    var selectedMethodName by rememberSaveable(topicId) { mutableStateOf(inferredMethod.name) }
    val selectedMethod = remember(selectedMethodName) { FleetDeliveryMethod.valueOf(selectedMethodName) }

    var completedStepIds by remember(topicId) {
        mutableStateOf(
            if (isInspection) emptySet() else loadCompletedStepIds(context, topicId),
        )
    }
    val checklistSteps = remember(selectedMethod) {
        TradeDeliveryChecklistEngine.checklistFor(selectedMethod)
    }
    val completedCount = checklistSteps.count { it.id in completedStepIds }
    val progressFraction = if (checklistSteps.isEmpty()) 0f else completedCount.toFloat() / checklistSteps.size

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Sheet Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(tradeSpec.spineColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (alreadySold) Icons.Rounded.TaskAlt else Icons.Rounded.Sell,
                        contentDescription = null,
                        tint = tradeSpec.spineColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (alreadySold) "已出结帖与交割助手" else "一键标记 [已出] & 交割助手",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "自动保留主题权限等级 · 仅编辑主楼标题与脱敏正文",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PulseAccentPill(
                    text = if (alreadySold) "已标记已出" else "交易中",
                    containerColor = if (alreadySold) {
                        tradeSpec.spineColor.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    contentColor = if (alreadySold) {
                        tradeSpec.spineColor
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                )
            }

            // Tab Switcher
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TradeClosureTab.entries.forEach { tab ->
                    val selected = activeTab == tab
                    FilterChip(
                        selected = selected,
                        onClick = { activeTabName = tab.name },
                        leadingIcon = {
                            Icon(
                                imageVector = if (tab == TradeClosureTab.CLOSE_AND_MASK) {
                                    Icons.Rounded.PrivacyTip
                                } else {
                                    Icons.Rounded.Checklist
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        label = {
                            Text(
                                text = tab.label,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (activeTab == TradeClosureTab.CLOSE_AND_MASK) {
                // Title Preview Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseCard(
                            shape = PulseArchitecturalShapes.EditorialCard,
                            containerColor = ext.cardSurface,
                            elevation = PulseElevation.None,
                            accentColor = tradeSpec.spineColor,
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "结帖标题预览",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tradeSpec.spineColor,
                    )
                    Text(
                        text = nextTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (!alreadySold && topicTitle.isNotBlank()) {
                        Text(
                            text = "原标题：${topicTitle.trim()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Privacy Toggles Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseCard(
                            shape = PulseArchitecturalShapes.EditorialCard,
                            containerColor = ext.cardSurface,
                            elevation = PulseElevation.None,
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "敏感信息脱敏选项",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        val badgeText = if (openingMarkdown == null) {
                            "提交时拉取主楼"
                        } else if (sanitizePreview.redactionCount > 0) {
                            "检出 ${sanitizePreview.redactionCount} 处"
                        } else {
                            "未检出敏感项"
                        }
                        PulseAccentPill(
                            text = badgeText,
                            containerColor = if (sanitizePreview.redactionCount > 0) {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            contentColor = if (sanitizePreview.redactionCount > 0) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }

                    PrivacySwitchRow(
                        title = "隐藏公网 IPv4 / IPv6",
                        subtitle = "保留网段与测评格式，不破坏 Markdown 与 ANSI",
                        checked = maskIps,
                        onCheckedChange = { maskIps = it },
                    )
                    PrivacySwitchRow(
                        title = "隐藏邮箱地址",
                        subtitle = "脱敏个人邮箱，保留图床链接与 @提及",
                        checked = maskEmails,
                        onCheckedChange = { maskEmails = it },
                    )
                    PrivacySwitchRow(
                        title = "隐藏密码 / Token / UUID",
                        subtitle = "替换 Root 密码、密钥与 API Token 为 ******",
                        checked = maskCredentials,
                        onCheckedChange = { maskCredentials = it },
                    )
                    PrivacySwitchRow(
                        title = "隐藏订单号与联系方式",
                        subtitle = "遮罩订单号、Invoice、TG、QQ、微信 ID",
                        checked = maskOrders,
                        onCheckedChange = { maskOrders = it },
                    )
                    PrivacySwitchRow(
                        title = "文末追加已出脱敏说明",
                        subtitle = "幂等追加「已出结帖」引用提示",
                        checked = appendNote,
                        onCheckedChange = { appendNote = it },
                    )

                    if (openingMarkdown != null) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showDiffPreview = !showDiffPreview }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = if (showDiffPreview) "收起脱敏后正文预览" else "预览脱敏后正文",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Icon(
                                imageVector = if (showDiffPreview) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        AnimatedVisibility(visible = showDiffPreview) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(10.dp),
                            ) {
                                Text(
                                    text = sanitizePreview.sanitizedMarkdown.take(900).ifBlank { "(主楼正文为空)" },
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // Action Buttons
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onConfirmClosure(options) },
                        enabled = !isMutating,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tradeSpec.spineColor,
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isMutating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircleOutline,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = when {
                                isMutating -> "正在提交结帖…"
                                alreadySold -> "保存脱敏更新"
                                else -> "确认标记 [已出] 并提交"
                            },
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (!alreadySold && (maskIps || maskEmails || maskCredentials || maskOrders || appendNote)) {
                        OutlinedButton(
                            onClick = {
                                onConfirmClosure(
                                    TradePrivacyOptions(
                                        maskIpAddresses = false,
                                        maskEmails = false,
                                        maskCredentials = false,
                                        maskOrderAndContactIds = false,
                                        appendClosureNote = false,
                                    ),
                                )
                            },
                            enabled = !isMutating,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "仅追加 [已出] 前缀（不改正文）",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else {
                // Delivery Checklist Tab
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseCard(
                            shape = PulseArchitecturalShapes.EditorialCard,
                            containerColor = ext.cardSurface,
                            elevation = PulseElevation.None,
                            accentColor = tradeSpec.spineColor,
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "交割方式",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "已完成 $completedCount / ${checklistSteps.size}",
                            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                            fontWeight = FontWeight.SemiBold,
                            color = tradeSpec.spineColor,
                        )
                    }

                    LinearProgressIndicator(
                        progress = { progressFraction },
                        color = tradeSpec.spineColor,
                        trackColor = tradeSpec.spineColor.copy(alpha = 0.16f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FleetDeliveryMethod.entries.forEach { method ->
                            FilterChip(
                                selected = selectedMethod == method,
                                onClick = { selectedMethodName = method.name },
                                label = {
                                    Text(
                                        text = method.label,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }

                    Text(
                        text = selectedMethod.instructionText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                checklistSteps.forEach { step ->
                    val checked = step.id in completedStepIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(PulseArchitecturalShapes.EditorialCard)
                            .background(
                                if (checked) {
                                    tradeSpec.spineColor.copy(alpha = 0.08f)
                                } else {
                                    ext.cardSurface
                                },
                            )
                            .border(
                                width = 0.85.dp,
                                color = if (checked) {
                                    tradeSpec.spineColor.copy(alpha = 0.4f)
                                } else {
                                    ext.cardBorder
                                },
                                shape = PulseArchitecturalShapes.EditorialCard,
                            )
                            .clickable {
                                val updated = if (checked) {
                                    completedStepIds - step.id
                                } else {
                                    completedStepIds + step.id
                                }
                                completedStepIds = updated
                                if (!isInspection) {
                                    saveCompletedStepIds(context, topicId, updated)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = if (checked) {
                                Icons.Rounded.CheckBox
                            } else {
                                Icons.Rounded.CheckBoxOutlineBlank
                            },
                            contentDescription = null,
                            tint = if (checked) tradeSpec.spineColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(20.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = step.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (step.isCritical) {
                                    PulseAccentPill(
                                        text = "关键",
                                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                            Text(
                                text = step.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val msg = TradeDeliveryChecklistEngine.buildHandoverMessage(
                                method = selectedMethod,
                                topicTitle = topicTitle,
                            )
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Anode Handover", msg))
                            onCopyFeedback("已复制交割确认话术")
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "复制交割话术",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Button(
                        onClick = { onConfirmClosure(options) },
                        enabled = !isMutating,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tradeSpec.spineColor,
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = if (alreadySold) "更新脱敏" else "一键标记 [已出]",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PrivacySwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
