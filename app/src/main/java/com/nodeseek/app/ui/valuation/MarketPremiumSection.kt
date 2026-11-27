package com.nodeseek.app.ui.valuation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.valuation.DealSpreadStatus
import com.nodeseek.app.domain.valuation.MarketSearchStatus
import com.nodeseek.app.domain.valuation.TradeMarketPremiumAnalyzer
import com.nodeseek.app.domain.valuation.TradeMarketReferenceState
import com.nodeseek.app.domain.valuation.TradePremiumDistribution
import com.nodeseek.app.domain.valuation.TradePremiumObservation
import com.nodeseek.app.domain.valuation.VpsValuationEngine
import com.nodeseek.app.ui.components.PulseTextField
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.withTabularNumbers

private val QUICK_MARKET_MODELS = listOf(
    "DMIT Pro",
    "瓦工 MEGABOX",
    "RackNerd",
    "绿云 软银",
)

private val BODY_CODE_BLOCK_REGEX = Regex("""```[\s\S]*?```""")
private val BODY_INLINE_CODE_REGEX = Regex("""`([^`\n]+)`""")
private val BODY_MARKDOWN_IMAGE_REGEX = Regex("""!\[[^\]]*]\([^)]*\)""")
private val BODY_MARKDOWN_LINK_REGEX = Regex("""\[([^\]]+)]\([^)]*\)""")
private val BODY_HTML_TAG_REGEX = Regex("""<[^>]+>""")
private val BODY_URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
private val BODY_MD_DECOR_REGEX = Regex("""[*_~>#]+""")
private val BODY_SPEC_LABEL_LINE_REGEX = Regex(
    """^(?:[-*•\s|]*)(?:套餐\s*/?\s*机型|机型名称|机型|型号|机器型号|机器名称|机器|套餐名称|套餐|产品|vps|plan|model)\s*[:：|]\s*(.+)$""",
    RegexOption.IGNORE_CASE,
)
private val BODY_MULTI_SPACE_REGEX = Regex("""\s+""")
private val RAW_HTTP_404_REGEX = Regex("""(?i)(?:\bhttp\s*404\b|\b404\s*not\s*found\b|status\s*code\s*[:=]?\s*404|\b404\b)""")
private val RAW_TECH_ERROR_REGEX = Regex("""(?i)(?:<!doctype|<html|exception|java\.|kotlin\.|okhttp|retrofit|\bhttp\s*5\d{2}\b)""")

/**
 * Extracts a market-search query from [title], passing sanitized spec/model lines from
 * [openingMarkdown] as `fallbackText` into [TradeMarketPremiumAnalyzer.extractSuggestedQuery]
 * when the title alone is vague, weak, or empty.
 */
internal fun extractMarketQueryWithContext(
    title: String,
    openingMarkdown: String? = null,
): String {
    val cleanTitle = title.trim()
    val rawBody = openingMarkdown?.trim().orEmpty()
    val cleanedBody = if (rawBody.isNotEmpty()) cleanBodyForModelExtraction(rawBody) else ""
    val specCandidates = if (cleanedBody.isNotEmpty()) extractBodySpecLines(cleanedBody) else emptyList()
    val fallbackSnippet = buildBodyFallbackSnippet(cleanedBody, specCandidates)

    val primary = TradeMarketPremiumAnalyzer.extractSuggestedQuery(
        title = cleanTitle,
        fallbackText = fallbackSnippet.takeIf { it.isNotBlank() },
    )
    if (primary.isNotBlank()) {
        return primary
    }

    for (spec in specCandidates) {
        val fromSpec = TradeMarketPremiumAnalyzer.extractSuggestedQuery(spec)
        if (fromSpec.isNotBlank()) return fromSpec
    }
    return ""
}

private fun extractBodySpecLines(cleanedBody: String): List<String> = cleanedBody
    .lineSequence()
    .map { it.trim() }
    .mapNotNull { line ->
        val matchedValue = BODY_SPEC_LABEL_LINE_REGEX.find(line)?.groupValues?.getOrNull(1)?.trim()
            ?: return@mapNotNull null
        val firstCell = if (line.contains('|')) matchedValue.substringBefore('|').trim() else matchedValue
        firstCell.takeIf { it.isNotBlank() && !it.all { ch -> ch == '-' || ch == ':' || ch == '=' } }
    }
    .take(4)
    .toList()

private fun buildBodyFallbackSnippet(
    cleanedBody: String,
    specCandidates: List<String>,
): String {
    if (cleanedBody.isEmpty()) return ""
    val leadingLines = cleanedBody
        .lineSequence()
        .map { it.replace('|', ' ').replace(BODY_MULTI_SPACE_REGEX, " ").trim() }
        .filter { it.isNotBlank() && !it.all { ch -> ch == '-' || ch == '=' || ch == ':' } }
        .take(8)
        .joinToString(" ")
    return buildString {
        if (specCandidates.isNotEmpty()) {
            append(specCandidates.joinToString(" "))
            if (leadingLines.isNotBlank()) append(' ')
        }
        append(leadingLines)
    }.trim().take(400)
}

private fun cleanBodyForModelExtraction(markdown: String): String {
    return markdown
        .replace(BODY_CODE_BLOCK_REGEX, "\n")
        .replace(BODY_MARKDOWN_IMAGE_REGEX, " ")
        .replace(BODY_MARKDOWN_LINK_REGEX) { match -> match.groupValues[1] }
        .replace(BODY_INLINE_CODE_REGEX) { match -> match.groupValues[1] }
        .replace(BODY_URL_REGEX, " ")
        .replace(BODY_HTML_TAG_REGEX, " ")
        .replace(BODY_MD_DECOR_REGEX, "")
        .trim()
}

internal fun sanitizeMarketStatusMessage(state: TradeMarketReferenceState): String? {
    val raw = state.statusMessage?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val hasSamples = (state.distribution?.sampleCount ?: 0) > 0
    if (RAW_HTTP_404_REGEX.containsMatchIn(raw)) {
        return if (hasSamples) {
            "交易版搜索暂未返回更多结果，当前已保留本地参考样本"
        } else {
            "交易版搜索暂未找到匹配页面，可简化关键词（如仅保留商家 + 型号）后重试"
        }
    }
    if (RAW_TECH_ERROR_REGEX.containsMatchIn(raw)) {
        return if (hasSamples) {
            "联网补充暂时不可用，当前已保留本地参考样本"
        } else {
            "联网搜索暂时不可用，请稍后重试或简化关键词"
        }
    }
    return raw.take(120)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarketPremiumSection(
    activeQuery: String,
    referenceState: TradeMarketReferenceState,
    cooldownRemainingSeconds: Int,
    onQueryChange: (String) -> Unit,
    onSearchRemote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val isLoading = referenceState.searchStatus == MarketSearchStatus.LOADING
    val effectiveCooldown = maxOf(cooldownRemainingSeconds, referenceState.cooldownRemainingSeconds)
    val isInCooldown = effectiveCooldown > 0 &&
        (referenceState.searchStatus == MarketSearchStatus.COOLDOWN ||
            referenceState.searchStatus == MarketSearchStatus.RATE_LIMITED)
    val canSearchNow = activeQuery.isNotBlank() && !isLoading && !isInCooldown

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = ext.cardSurface,
                elevation = PulseElevation.None,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.QueryStats,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "同款市场溢价参考",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            PulseAccentPill(
                text = if (referenceState.hasRemoteResults) "本地+全站" else "本地缓存",
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        PulseTextField(
            value = activeQuery,
            onValueChange = onQueryChange,
            label = "参考关键词（商家 + 型号）",
            placeholder = "例如：DMIT Pro、瓦工 MEGABOX、RackNerd",
            singleLine = true,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    if (canSearchNow) onSearchRemote()
                },
            ),
            trailingIcon = if (activeQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "清空关键词",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QUICK_MARKET_MODELS.forEach { preset ->
                FilterChip(
                    selected = activeQuery.trim().equals(preset, ignoreCase = true),
                    onClick = { onQueryChange(preset) },
                    label = {
                        Text(
                            text = preset,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }

        MarketPremiumReferenceBody(
            state = referenceState,
            cooldownRemainingSeconds = cooldownRemainingSeconds,
            onSearchRemote = onSearchRemote,
            onOpenTopic = null,
            onOpenValuation = null,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarketPremiumReferenceCard(
    state: TradeMarketReferenceState,
    cooldownRemainingSeconds: Int,
    onSearchRemote: () -> Unit,
    onOpenTopic: ((Long, String) -> Unit)? = null,
    onOpenValuation: (() -> Unit)? = null,
    onSearchWithQuery: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val tradeSpec = NodePulseThemeTokens.categorySpec("trade")
    val supportsInlineEdit = onSearchWithQuery != null
    var isEditingQuery by rememberSaveable(supportsInlineEdit) { mutableStateOf(false) }
    var draftQuery by rememberSaveable { mutableStateOf(state.query) }

    LaunchedEffect(state.query) {
        if (!isEditingQuery) {
            draftQuery = state.query
        }
    }

    val isLoading = state.searchStatus == MarketSearchStatus.LOADING
    val effectiveCooldown = maxOf(cooldownRemainingSeconds, state.cooldownRemainingSeconds)
    val isInCooldown = effectiveCooldown > 0 &&
        (state.searchStatus == MarketSearchStatus.COOLDOWN ||
            state.searchStatus == MarketSearchStatus.RATE_LIMITED)
    val showEditor = supportsInlineEdit && (isEditingQuery || state.query.isBlank())

    val submitEditedQuery: (String) -> Unit = { rawQuery ->
        val clean = rawQuery.trim()
        if (clean.isNotEmpty() && !isLoading && !isInCooldown) {
            draftQuery = clean
            isEditingQuery = false
            onSearchWithQuery?.invoke(clean) ?: onSearchRemote()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                containerColor = ext.cardSurface,
                elevation = PulseElevation.Subtle,
                accentColor = tradeSpec.spineColor,
            )
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.QueryStats,
                contentDescription = null,
                tint = tradeSpec.spineColor,
                modifier = Modifier.size(17.dp),
            )
            Text(
                text = "同款市场溢价参考",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.query.isNotBlank() || supportsInlineEdit) {
                PulseAccentPill(
                    text = state.query.ifBlank { "手动输入关键词" },
                    icon = if (supportsInlineEdit) Icons.Rounded.Edit else null,
                    containerColor = tradeSpec.spineColor.copy(alpha = 0.14f),
                    contentColor = tradeSpec.spineColor,
                    modifier = if (supportsInlineEdit) {
                        Modifier.clickable {
                            isEditingQuery = !isEditingQuery
                            if (isEditingQuery) {
                                draftQuery = state.query
                            }
                        }
                    } else {
                        Modifier
                    },
                )
            }
        }

        AnimatedVisibility(visible = showEditor) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PulseTextField(
                    value = draftQuery,
                    onValueChange = { draftQuery = it.take(120) },
                    label = "搜贴关键词（商家 + 型号）",
                    placeholder = "例如：DMIT Pro、瓦工 MEGABOX、RackNerd",
                    singleLine = true,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { submitEditedQuery(draftQuery) },
                    ),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (draftQuery.isNotEmpty()) {
                                IconButton(onClick = { draftQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "清空关键词",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            IconButton(
                                onClick = { submitEditedQuery(draftQuery) },
                                enabled = draftQuery.isNotBlank() && !isLoading && !isInCooldown,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = "按关键词搜索",
                                    tint = tradeSpec.spineColor,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    QUICK_MARKET_MODELS.forEach { preset ->
                        FilterChip(
                            selected = draftQuery.trim().equals(preset, ignoreCase = true),
                            onClick = {
                                draftQuery = preset
                                if (!isLoading && !isInCooldown) {
                                    isEditingQuery = false
                                    onSearchWithQuery?.invoke(preset)
                                }
                            },
                            label = {
                                Text(
                                    text = preset,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        }

        MarketPremiumReferenceBody(
            state = state,
            cooldownRemainingSeconds = cooldownRemainingSeconds,
            onSearchRemote = {
                val edited = draftQuery.trim()
                if (supportsInlineEdit && showEditor && edited.isNotEmpty()) {
                    submitEditedQuery(edited)
                } else {
                    onSearchRemote()
                }
            },
            onOpenTopic = onOpenTopic,
            onOpenValuation = onOpenValuation,
            canSearchOverride = if (supportsInlineEdit && showEditor) draftQuery.isNotBlank() else null,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarketPremiumReferenceBody(
    state: TradeMarketReferenceState,
    cooldownRemainingSeconds: Int,
    onSearchRemote: () -> Unit,
    onOpenTopic: ((Long, String) -> Unit)?,
    onOpenValuation: (() -> Unit)?,
    canSearchOverride: Boolean? = null,
) {
    val dist = state.distribution
    val tradeSpec = NodePulseThemeTokens.categorySpec("trade")
    var expandedSamples by rememberSaveable(state.query) { mutableStateOf(false) }

    val isLoading = state.searchStatus == MarketSearchStatus.LOADING
    val effectiveCooldown = maxOf(cooldownRemainingSeconds, state.cooldownRemainingSeconds)
    val isInCooldown = effectiveCooldown > 0 &&
        (state.searchStatus == MarketSearchStatus.COOLDOWN || state.searchStatus == MarketSearchStatus.RATE_LIMITED)
    val hasSamples = dist != null && dist.sampleCount > 0

    if (state.query.isBlank() && canSearchOverride != true) {
        Text(
            text = "未能从标题自动识别机型，输入商家与型号关键词后即可检索近期溢价/折价参考。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (hasSamples) {
        DistributionSummaryBlock(dist = dist!!)
    } else if (isLoading) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = "正在检索「${state.query}」交易版同款溢价样本…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        val emptyHint = when {
            state.hasRemoteResults && dist != null && dist.matchedTopicCount > 0 ->
                "已检索本地及交易版，命中 ${dist.matchedTopicCount} 条同款帖，但标题均未明示溢价/折价数值，可调整关键词重试。"
            state.hasRemoteResults ->
                "交易版近期暂无「${state.query}」明示溢价样本，可简化关键词（如商家 + 核心型号）后重试。"
            dist != null && dist.matchedTopicCount > 0 ->
                "本地命中 ${dist.matchedTopicCount} 条同款帖，但标题均未明示溢价/折价数值，可点击下方按钮搜交易版补充。"
            else ->
                "本地缓存暂无「${state.query}」溢价样本，可点击下方按钮搜交易版或调整关键词。"
        }
        Text(
            text = emptyHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    sanitizeMarketStatusMessage(state)?.let { msg ->
        val isHardError = !hasSamples && (
            state.searchStatus == MarketSearchStatus.RATE_LIMITED ||
                state.searchStatus == MarketSearchStatus.FAILED ||
                state.searchStatus == MarketSearchStatus.CHALLENGE_REQUIRED
            )
        Text(
            text = msg,
            style = MaterialTheme.typography.labelSmall,
            color = if (isHardError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }

    val searchEnabled = (canSearchOverride ?: state.query.isNotBlank()) && !isLoading && !isInCooldown

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedButton(
            onClick = onSearchRemote,
            enabled = searchEnabled,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = tradeSpec.spineColor,
            ),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
            }
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = when {
                    isLoading -> "搜索中…"
                    isInCooldown -> "冷却 ${effectiveCooldown}s"
                    state.searchStatus == MarketSearchStatus.FAILED -> "重试搜索"
                    else -> "搜交易版"
                },
                style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (onOpenValuation != null) {
            OutlinedButton(
                onClick = onOpenValuation,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Calculate,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "估值核算",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (dist != null && dist.observations.isNotEmpty()) {
            TextButton(
                onClick = { expandedSamples = !expandedSamples },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (expandedSamples) "收起样本" else "查看样本 (${dist.observations.size})",
                    style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = if (expandedSamples) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }

    if (dist != null && dist.observations.isNotEmpty()) {
        AnimatedVisibility(visible = expandedSamples) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                dist.observations.take(6).forEach { obs ->
                    ObservationRow(
                        observation = obs,
                        onClick = onOpenTopic?.let { open ->
                            { open(obs.topicId, obs.title) }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DistributionSummaryBlock(
    dist: TradePremiumDistribution,
) {
    val p25Str = dist.p25Cny?.let(VpsValuationEngine::formatSignedCny) ?: "-"
    val medianStr = dist.medianCny?.let(VpsValuationEngine::formatSignedCny) ?: "-"
    val p75Str = dist.p75Cny?.let(VpsValuationEngine::formatSignedCny) ?: "-"
    val minStr = dist.minCny?.let(VpsValuationEngine::formatSignedCny) ?: "-"
    val maxStr = dist.maxCny?.let(VpsValuationEngine::formatSignedCny) ?: "-"

    val headlineRange = when {
        dist.sampleCount == 1 -> "单样本参考：$medianStr"
        dist.sampleCount == 2 -> "参考区间：$minStr ~ $maxStr"
        else -> "P25~P75：$p25Str ~ $p75Str"
    }
    val summaryDetail = when {
        dist.sampleCount == 1 ->
            "样本 1 条（本地 ${dist.localSampleCount} / 全站 ${dist.remoteSampleCount}）· 样本较少仅供参考"
        dist.sampleCount == 2 ->
            "样本 2 条（本地 ${dist.localSampleCount} / 全站 ${dist.remoteSampleCount}）"
        else ->
            "极值区间：$minStr ~ $maxStr · 样本 ${dist.sampleCount} 条（本地 ${dist.localSampleCount} / 全站 ${dist.remoteSampleCount}）"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = headlineRange,
                style = MaterialTheme.typography.bodyMedium.withTabularNumbers(),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "中位 $medianStr",
                style = MaterialTheme.typography.labelLarge.withTabularNumbers(),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Text(
            text = summaryDetail,
            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PulseAccentPill(
                text = "溢价 ${dist.premiumCount}",
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
            PulseAccentPill(
                text = "平出 ${dist.parCount}",
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            PulseAccentPill(
                text = "折价 ${dist.discountCount}",
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun ObservationRow(
    observation: TradePremiumObservation,
    onClick: (() -> Unit)?,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val badgeColors = when (observation.spreadStatus) {
        DealSpreadStatus.PREMIUM -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        DealSpreadStatus.PAR -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        DealSpreadStatus.DISCOUNT -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(0.7.dp, ext.cardBorder, RoundedCornerShape(9.dp))
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PulseAccentPill(
            text = VpsValuationEngine.formatSignedCny(observation.premiumDeltaCny),
            containerColor = badgeColors.first,
            contentColor = badgeColors.second,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = observation.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = buildString {
                    append(observation.source.label)
                    if (observation.isClosedSold) append(" · 已出")
                    if (observation.matchedExpression.isNotBlank()) {
                        append(" · ")
                        append(observation.matchedExpression)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
