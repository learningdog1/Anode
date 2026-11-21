@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.hub

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.filter.ContentBlockRule
import com.nodeseek.app.domain.filter.ContentFilterConfig
import com.nodeseek.app.domain.filter.ContentFilterEngine
import com.nodeseek.app.domain.filter.ContentFilterPreset
import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.radar.KeywordMatchMode
import com.nodeseek.app.ui.theme.CategoryPalette
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulsePressScale
import com.nodeseek.app.ui.theme.withTabularNumbers
import java.util.UUID

private val SAMPLE_TEST_TITLES: List<Pair<String, String>> = listOf(
    "求购贴" to "[求购] RackNerd 圣何塞闪购款 10刀",
    "AFF 推广" to "[推广] 某主机商限时特惠附带 AFF 返利",
    "溢价出机" to "[出] DMIT HK Pro 季付溢价 50 包 Push",
    "技术测评" to "【深度测评】东京 Tier-1 BGP 实测与 YABS 报告",
)

@Composable
fun ContentFilterSettingsSection(
    config: ContentFilterConfig,
    includeBlockedTopics: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onTogglePreset: (ContentFilterPreset) -> Unit,
    onUpsertRule: (ContentBlockRule) -> Unit,
    onToggleRuleEnabled: (ruleId: String, enabled: Boolean) -> Unit,
    onRemoveRule: (ruleId: String) -> Unit,
    onToggleMutedCategory: (categorySlug: String) -> Unit,
    onDisableIncludeBlockedTopics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRuleEditor by rememberSaveable { mutableStateOf(false) }
    var editingRuleId by rememberSaveable { mutableStateOf<String?>(null) }

    val activeRulesCount = remember(config.rules) { config.rules.count { it.isEnabled } }
    val normalizedMutedSlugs = remember(config.mutedCategorySlugs) {
        config.mutedCategorySlugs.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }
    val totalActiveCount = activeRulesCount + normalizedMutedSlugs.size
    val editingRule = remember(editingRuleId, config.rules) {
        editingRuleId?.let { targetId -> config.rules.firstOrNull { it.id == targetId } }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "内容屏蔽",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pulseCard(
                    shape = PulseArchitecturalShapes.ElevatedTile,
                    elevation = PulseElevation.Card,
                ),
        ) {
            ContentFilterMasterHeader(
                config = config,
                activeRulesCount = activeRulesCount,
                mutedBoardsCount = normalizedMutedSlugs.size,
                totalActiveCount = totalActiveCount,
                onSetEnabled = onSetEnabled,
            )

            AnimatedVisibility(
                visible = includeBlockedTopics,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                ContentFilterBypassNotice(
                    onDisableIncludeBlockedTopics = onDisableIncludeBlockedTopics,
                )
            }

            ContentFilterSectionDivider()

            PresetChipsSubsection(
                config = config,
                onTogglePreset = onTogglePreset,
            )

            ContentFilterSectionDivider()

            RulesListSubsection(
                rules = config.rules,
                isMasterEnabled = config.isEnabled,
                onAddRule = {
                    editingRuleId = null
                    showRuleEditor = true
                },
                onEditRule = { rule ->
                    editingRuleId = rule.id
                    showRuleEditor = true
                },
                onToggleRuleEnabled = onToggleRuleEnabled,
                onRemoveRule = onRemoveRule,
            )

            ContentFilterSectionDivider()

            MutedBoardsSubsection(
                normalizedMutedSlugs = normalizedMutedSlugs,
                onToggleMutedCategory = onToggleMutedCategory,
            )
        }
    }

    if (showRuleEditor) {
        ContentBlockRuleEditorSheet(
            initialRule = editingRule,
            onDismiss = {
                showRuleEditor = false
                editingRuleId = null
            },
            onSave = { savedRule ->
                onUpsertRule(savedRule)
                showRuleEditor = false
                editingRuleId = null
            },
        )
    }
}

@Composable
private fun ContentFilterMasterHeader(
    config: ContentFilterConfig,
    activeRulesCount: Int,
    mutedBoardsCount: Int,
    totalActiveCount: Int,
    onSetEnabled: (Boolean) -> Unit,
) {
    val summarySubtitle = when {
        !config.isEnabled -> "已暂停 · 保留 ${config.rules.size} 条规则、$mutedBoardsCount 个屏蔽版块"
        totalActiveCount == 0 -> "在信息流与搜索结果中自动隐藏匹配的帖子"
        else -> "已启用 $activeRulesCount 条规则 · 屏蔽 $mutedBoardsCount 个版块"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onSetEnabled(!config.isEnabled) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(
                    if (config.isEnabled) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.FilterAlt,
                contentDescription = null,
                tint = if (config.isEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(16.dp),
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
                Text(
                    text = "按内容与版块屏蔽",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (config.isEnabled && totalActiveCount > 0) {
                    PulseAccentPill(
                        text = "$totalActiveCount 生效",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else if (!config.isEnabled) {
                    PulseAccentPill(
                        text = "已暂停",
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = summarySubtitle,
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Switch(
            checked = config.isEnabled,
            onCheckedChange = onSetEnabled,
            thumbContent = if (config.isEnabled) {
                {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                    )
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun ContentFilterBypassNotice(
    onDisableIncludeBlockedTopics: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ext.heatContainer.copy(alpha = 0.72f))
            .border(
                width = 0.8.dp,
                color = ext.onHeatContainer.copy(alpha = 0.28f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = ext.onHeatContainer,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "已开启「显示已屏蔽成员内容」，屏蔽规则暂不生效",
            style = MaterialTheme.typography.labelSmall,
            color = ext.onHeatContainer,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(ext.onHeatContainer.copy(alpha = 0.14f))
                .pulseBouncyClickable(
                    pressedScale = NodePulseMotion.chipPressScale,
                    role = Role.Button,
                    onClick = onDisableIncludeBlockedTopics,
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                text = "恢复屏蔽",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = ext.onHeatContainer,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PresetChipsSubsection(
    config: ContentFilterConfig,
    onTogglePreset: (ContentFilterPreset) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "一键屏蔽常见水贴",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "点按启用或移除",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ContentFilterEngine.CURATED_PRESETS.forEach { preset ->
                val isActive = ContentFilterEngine.isPresetActive(preset, config)
                FilterChip(
                    selected = isActive,
                    onClick = { onTogglePreset(preset) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (isActive) Icons.Rounded.Check else Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                    },
                    label = {
                        Text(
                            text = preset.label,
                            maxLines = 1,
                            softWrap = false,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun RulesListSubsection(
    rules: List<ContentBlockRule>,
    isMasterEnabled: Boolean,
    onAddRule: () -> Unit,
    onEditRule: (ContentBlockRule) -> Unit,
    onToggleRuleEnabled: (ruleId: String, enabled: Boolean) -> Unit,
    onRemoveRule: (ruleId: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "自定义屏蔽规则 (${rules.size})",
                    style = MaterialTheme.typography.titleSmall.withTabularNumbers(),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "支持关键词包含与正则表达式，点击规则可编辑与试测",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FilledTonalButton(
                onClick = onAddRule,
                shape = CircleShape,
                modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 34.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "添加规则",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        if (rules.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(
                        width = 0.75.dp,
                        color = NodePulseThemeTokens.extendedColors.cardBorder,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "暂未添加屏蔽规则。可点击上方预设芯片一键屏蔽「收/求购」「AFF/推广」，或点击「添加规则」自定义关键词/正则。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rules.forEach { rule ->
                    ContentBlockRuleRow(
                        rule = rule,
                        isMasterEnabled = isMasterEnabled,
                        onClick = { onEditRule(rule) },
                        onToggleEnabled = { enabled -> onToggleRuleEnabled(rule.id, enabled) },
                        onDelete = { onRemoveRule(rule.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContentBlockRuleRow(
    rule: ContentBlockRule,
    isMasterEnabled: Boolean,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val isDark = NodePulseThemeTokens.isDark
    val ext = NodePulseThemeTokens.extendedColors
    val categorySpec = CategoryPalette.resolve(rule.categorySlug, isDark)
    val scopeLabel = if (rule.categorySlug.isBlank()) "全站版块" else categorySpec.defaultTitle
    val regexError = remember(rule.pattern, rule.matchMode) {
        if (rule.matchMode == KeywordMatchMode.REGEX) {
            ContentFilterEngine.validateRegex(rule.pattern)
        } else {
            null
        }
    }
    val hasNote = rule.note.isNotBlank()
    val effectiveActive = isMasterEnabled && rule.isEnabled

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PulseArchitecturalShapes.MediumBadge)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                width = 0.8.dp,
                color = if (regexError != null) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
                } else {
                    ext.cardBorder
                },
                shape = PulseArchitecturalShapes.MediumBadge,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = if (hasNote) rule.note else rule.pattern,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (effectiveActive) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (hasNote) {
                Text(
                    text = rule.pattern,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PulseAccentPill(
                    text = if (rule.matchMode == KeywordMatchMode.REGEX) "正则" else "关键词",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                PulseAccentPill(
                    text = scopeLabel,
                    containerColor = if (rule.categorySlug.isBlank()) {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    } else {
                        categorySpec.containerColor
                    },
                    contentColor = if (rule.categorySlug.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        categorySpec.contentColor
                    },
                )
                if (regexError != null) {
                    PulseAccentPill(
                        text = "正则无效",
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        Switch(
            checked = rule.isEnabled,
            onCheckedChange = onToggleEnabled,
        )

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = "删除规则",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun MutedBoardsSubsection(
    normalizedMutedSlugs: Set<String>,
    onToggleMutedCategory: (String) -> Unit,
) {
    val isDark = NodePulseThemeTokens.isDark
    val ext = NodePulseThemeTokens.extendedColors
    val mutableCategories = remember {
        Category.DEFAULT_CATEGORIES.filter { it.slug.isNotBlank() && !it.isAdminOnly }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "屏蔽版块 (${normalizedMutedSlugs.size})",
                style = MaterialTheme.typography.titleSmall.withTabularNumbers(),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "已选版块将在「全部」信息流与全站搜索中隐藏，主动进入对应版块时仍可正常查看",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            mutableCategories.forEach { category ->
                val isMuted = category.slug.lowercase() in normalizedMutedSlugs
                val categorySpec = CategoryPalette.resolve(category.slug, isDark)
                val chipBg by animateColorAsState(
                    targetValue = if (isMuted) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    label = "mutedBoardChipBg",
                )
                val chipBorder by animateColorAsState(
                    targetValue = if (isMuted) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
                    } else {
                        ext.cardBorder
                    },
                    label = "mutedBoardChipBorder",
                )
                val contentColor = if (isMuted) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                val interaction = remember { MutableInteractionSource() }

                Row(
                    modifier = Modifier
                        .pulsePressScale(interaction, pressedScale = NodePulseMotion.chipPressScale)
                        .clip(CircleShape)
                        .background(chipBg)
                        .border(width = 0.8.dp, color = chipBorder, shape = CircleShape)
                        .clickable(
                            interactionSource = interaction,
                            indication = LocalIndication.current,
                            role = Role.Checkbox,
                            onClick = { onToggleMutedCategory(category.slug) },
                        )
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Rounded.VisibilityOff else categorySpec.icon,
                        contentDescription = null,
                        tint = if (isMuted) contentColor else categorySpec.spineColor,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = category.name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isMuted) FontWeight.SemiBold else FontWeight.Medium,
                        color = contentColor,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContentBlockRuleEditorSheet(
    initialRule: ContentBlockRule?,
    onDismiss: () -> Unit,
    onSave: (ContentBlockRule) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = NodePulseThemeTokens.isDark
    val ext = NodePulseThemeTokens.extendedColors
    val categorySpecs = remember(isDark) { CategoryPalette.allSpecs(isDark) }

    var pattern by rememberSaveable(initialRule?.id) {
        mutableStateOf(initialRule?.pattern.orEmpty())
    }
    var matchMode by rememberSaveable(initialRule?.id) {
        mutableStateOf(initialRule?.matchMode ?: KeywordMatchMode.CONTAINS)
    }
    var selectedCategorySlug by rememberSaveable(initialRule?.id) {
        mutableStateOf(initialRule?.categorySlug.orEmpty())
    }
    var note by rememberSaveable(initialRule?.id) {
        mutableStateOf(initialRule?.note.orEmpty())
    }
    var sampleTitle by rememberSaveable {
        mutableStateOf(SAMPLE_TEST_TITLES.first().second)
    }

    val validationError = remember(pattern, matchMode) {
        if (pattern.isEmpty()) null else ContentFilterEngine.validateRulePattern(pattern, matchMode)
    }
    val regexSyntaxError = remember(pattern, matchMode) {
        if (matchMode == KeywordMatchMode.REGEX && pattern.isNotBlank()) {
            ContentFilterEngine.validateRegex(pattern)
        } else {
            null
        }
    }
    val canSave = pattern.isNotBlank() && regexSyntaxError == null
    val sampleMatches = remember(sampleTitle, pattern, matchMode, regexSyntaxError) {
        if (pattern.isBlank() || regexSyntaxError != null) {
            false
        } else {
            ContentFilterEngine.testRuleMatch(sampleTitle, pattern, matchMode)
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = if (initialRule == null) "添加内容屏蔽规则" else "编辑屏蔽规则",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            OutlinedTextField(
                value = pattern,
                onValueChange = { pattern = it },
                label = {
                    Text(if (matchMode == KeywordMatchMode.REGEX) "正则表达式" else "屏蔽关键词")
                },
                placeholder = {
                    Text(
                        if (matchMode == KeywordMatchMode.REGEX) {
                            """例如：(^|[\s【\[])(收|求购)"""
                        } else {
                            "例如：求购、AFF、代购"
                        },
                    )
                },
                isError = regexSyntaxError != null,
                supportingText = when {
                    regexSyntaxError != null -> {
                        {
                            Text(
                                text = regexSyntaxError,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    matchMode == KeywordMatchMode.REGEX && pattern.isNotBlank() -> {
                        {
                            Text(
                                text = "正则表达式语法有效（自动忽略大小写）",
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    validationError != null -> {
                        {
                            Text(
                                text = validationError,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    else -> null
                },
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "匹配模式",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = matchMode == KeywordMatchMode.CONTAINS,
                        onClick = { matchMode = KeywordMatchMode.CONTAINS },
                        label = { Text("包含关键词（忽略大小写）") },
                    )
                    FilterChip(
                        selected = matchMode == KeywordMatchMode.REGEX,
                        onClick = { matchMode = KeywordMatchMode.REGEX },
                        label = { Text("正则匹配") },
                    )
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("规则备注（可选）") },
                placeholder = { Text("例如：屏蔽求购帖") },
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "生效版块范围",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "__all__") {
                        FilterChip(
                            selected = selectedCategorySlug.isBlank(),
                            onClick = { selectedCategorySlug = "" },
                            label = { Text("全站版块") },
                        )
                    }
                    items(
                        items = categorySpecs.filter { it.slug.isNotBlank() },
                        key = { it.slug },
                    ) { spec ->
                        FilterChip(
                            selected = selectedCategorySlug.equals(spec.slug, ignoreCase = true),
                            onClick = { selectedCategorySlug = spec.slug },
                            label = { Text(spec.defaultTitle) },
                        )
                    }
                }
            }

            // Live Sample Title Test Preview
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(0.8.dp, ext.cardBorder, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "实时匹配预览",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    when {
                        regexSyntaxError != null -> PulseAccentPill(
                            text = "正则无效",
                            icon = Icons.Rounded.WarningAmber,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        pattern.isBlank() -> PulseAccentPill(
                            text = "等待输入",
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        sampleMatches -> PulseAccentPill(
                            text = "命中屏蔽 · 将隐藏",
                            icon = Icons.Rounded.Block,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        else -> PulseAccentPill(
                            text = "未命中 · 正常显示",
                            icon = Icons.Rounded.CheckCircleOutline,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }

                OutlinedTextField(
                    value = sampleTitle,
                    onValueChange = { sampleTitle = it },
                    label = { Text("测试帖子标题") },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(SAMPLE_TEST_TITLES, key = { it.first }) { (chipLabel, titleSample) ->
                        FilterChip(
                            selected = sampleTitle == titleSample,
                            onClick = { sampleTitle = titleSample },
                            colors = FilterChipDefaults.filterChipColors(),
                            label = {
                                Text(
                                    text = chipLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDismiss,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消", maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = {
                        val ruleToSave = ContentBlockRule(
                            id = initialRule?.id ?: UUID.randomUUID().toString(),
                            pattern = pattern.trim(),
                            matchMode = matchMode,
                            categorySlug = selectedCategorySlug.trim().lowercase(),
                            isEnabled = initialRule?.isEnabled ?: true,
                            note = note.trim(),
                            createdAtEpochMillis = initialRule?.createdAtEpochMillis ?: System.currentTimeMillis(),
                        )
                        onSave(ruleToSave)
                    },
                    enabled = canSave,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.weight(2f),
                ) {
                    Text(
                        text = if (initialRule == null) "保存并启用" else "保存修改",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContentFilterSectionDivider() {
    val ext = NodePulseThemeTokens.extendedColors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(0.6.dp)
            .background(ext.cardBorder.copy(alpha = 0.65f)),
    )
}
