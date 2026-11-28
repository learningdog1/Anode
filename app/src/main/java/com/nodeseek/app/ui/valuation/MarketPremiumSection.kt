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
import androidx.compose.foundation.layout.widthIn
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
import com.nodeseek.app.domain.valuation.InferredListingPricing
import com.nodeseek.app.domain.valuation.MarketDataSource
import com.nodeseek.app.domain.valuation.MarketSearchStatus
import com.nodeseek.app.domain.valuation.TradeAskPriceObservation
import com.nodeseek.app.domain.valuation.TradeCandidateTopic
import com.nodeseek.app.domain.valuation.TradeListingIntent
import com.nodeseek.app.domain.valuation.TradeMarketPremiumAnalyzer
import com.nodeseek.app.domain.valuation.TradeMarketReferenceState
import com.nodeseek.app.domain.valuation.TradePremiumDistribution
import com.nodeseek.app.domain.valuation.TradePremiumObservation
import com.nodeseek.app.domain.valuation.VpsValuationEngine
import java.math.BigDecimal
import java.math.RoundingMode
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
private val BODY_HORIZONTAL_SPACE_REGEX = Regex("""[ \t]+""")
private val RAW_HTTP_404_REGEX = Regex("""(?i)(?:\bhttp\s*404\b|\b404\s*not\s*found\b|status\s*code\s*[:=]?\s*404|\b404\b)""")
private val RAW_TECH_ERROR_REGEX = Regex("""(?i)(?:<!doctype|<html|exception|java\.|kotlin\.|okhttp|retrofit|\bhttp\s*5\d{2}\b)""")

/**
 * Extracts a market-search query from [title], passing sanitized line-structured OP body text from
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
    val fallbackSnippet = buildBodyFallbackSnippet(cleanedBody)

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
): String {
    if (cleanedBody.isEmpty()) return ""
    return cleanedBody
        .lineSequence()
        .map { it.replace(BODY_HORIZONTAL_SPACE_REGEX, " ").trim() }
        .filter { line ->
            line.isNotBlank() &&
                !line.all { ch -> ch == '-' || ch == '=' || ch == ':' || ch == '|' || ch == ' ' }
        }
        .take(18)
        .joinToString("\n")
        .trim()
        .take(1_200)
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
    val hasReference = (state.distribution?.pricedTopicCount ?: 0) > 0 ||
        (state.distribution?.matchedTopicCount ?: 0) > 0
    if (RAW_HTTP_404_REGEX.containsMatchIn(raw)) {
        return if (hasReference) {
            "交易版搜索暂未返回更多结果，当前已保留本地参考样本"
        } else {
            "交易版搜索暂未找到匹配页面，可简化关键词（如仅保留商家 + 型号）后重试"
        }
    }
    if (RAW_TECH_ERROR_REGEX.containsMatchIn(raw)) {
        return if (hasReference) {
            "联网补充暂时不可用，当前已保留本地参考样本"
        } else {
            "联网搜索暂时不可用，请稍后重试或简化关键词"
        }
    }
    return raw.take(120)
}

internal fun formatCompactCny(amount: BigDecimal): String {
    val scaled = amount.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
    return if (scaled.scale() <= 0) {
        scaled.setScale(0, RoundingMode.HALF_UP).toPlainString()
    } else {
        scaled.toPlainString()
    }
}

internal fun formatMatchAndPricedSummary(dist: TradePremiumDistribution): String {
    val matched = maxOf(dist.matchedTopicCount, dist.pricedTopicCount)
    return "匹配 $matched 帖 · 可计价 ${dist.pricedTopicCount} 帖"
}

internal fun computePricedSourceCounts(dist: TradePremiumDistribution): Pair<Int, Int> {
    val sourcesByTopic = LinkedHashMap<Long, MarketDataSource>()
    dist.observations.forEach { sourcesByTopic[it.topicId] = it.source }
    dist.askOnlyObservations.forEach { sourcesByTopic.putIfAbsent(it.topicId, it.source) }
    if (sourcesByTopic.isEmpty()) {
        return dist.localSampleCount to dist.remoteSampleCount
    }
    val localCount = sourcesByTopic.values.count { it != MarketDataSource.REMOTE_SEARCH }
    val remoteCount = sourcesByTopic.values.count { it == MarketDataSource.REMOTE_SEARCH }
    return localCount to remoteCount
}

internal fun formatDistributionSummaryDetail(dist: TradePremiumDistribution): String {
    val matchPricedText = formatMatchAndPricedSummary(dist)
    val (localPricedCount, remotePricedCount) = computePricedSourceCounts(dist)
    return buildString {
        append(matchPricedText)
        append("（本地 $localPricedCount / 全站 $remotePricedCount）")
        if (dist.sampleCount == 1) {
            append(" · 样本较少仅供参考")
        }
    }
}

internal fun formatMatchedOnlyHint(
    hasRemoteResults: Boolean,
    hasClickableSamples: Boolean,
): String = when {
    hasClickableSamples && hasRemoteResults ->
        "已检索本地与交易版并解析主楼正文，同款帖均未标明价格，可点开下方帖子查看或调整关键词。"
    hasClickableSamples ->
        "本地匹配到同款帖但标题未标明价格，点击「搜交易版」将自动拉取主楼正文补充计价。"
    hasRemoteResults ->
        "已匹配到同款帖，但暂无可直接计价的出售样本（可能为求购帖或未标价），可调整关键词重试。"
    else ->
        "本地匹配到同款帖但暂无可计价出售样本，点击「搜交易版」检索更多同款出售记录。"
}

internal fun formatAskPriceHeadline(dist: TradePremiumDistribution): String? {
    val median = dist.askMedianCny ?: return null
    if (dist.askSampleCount <= 0) return null
    val p25 = dist.askP25Cny ?: dist.askMinCny ?: median
    val p75 = dist.askP75Cny ?: dist.askMaxCny ?: median
    return "同款挂牌价 中位 ¥${formatCompactCny(median)}（¥${formatCompactCny(p25)}–¥${formatCompactCny(p75)}）"
}

internal fun formatCurrentTopicPricingSummary(
    pricing: InferredListingPricing,
): Pair<String, String>? {
    if (pricing.intent == TradeListingIntent.BUY) return null
    return when {
        !pricing.isAskDerived &&
            pricing.askPriceCny != null &&
            (pricing.isDerived || pricing.spreadDeltaCny == null) -> {
            val askStr = "¥${formatCompactCny(pricing.askPriceCny)}"
            val headline = "本帖按总价出：总价 $askStr"
            val detail = if (pricing.residualCny != null && pricing.spreadDeltaCny != null) {
                val derivedSpreadText = when {
                    pricing.spreadDeltaCny > BigDecimal.ZERO ->
                        "推算溢价 ${VpsValuationEngine.formatSignedCny(pricing.spreadDeltaCny)}"
                    pricing.spreadDeltaCny < BigDecimal.ZERO ->
                        "推算折价 ${VpsValuationEngine.formatSignedCny(pricing.spreadDeltaCny)}"
                    else ->
                        "推算平出 ¥0.00"
                }
                "净残值 ¥${formatCompactCny(pricing.residualCny)} · $derivedSpreadText"
            } else {
                "未标续费/到期，点「估值核算」算溢价"
            }
            headline to detail
        }
        !pricing.isDerived && pricing.spreadDeltaCny != null -> {
            val spread = pricing.spreadDeltaCny
            val headline = when {
                spread > BigDecimal.ZERO ->
                    "本帖按溢价出：溢价 ${VpsValuationEngine.formatSignedCny(spread)}"
                spread < BigDecimal.ZERO ->
                    "本帖按折价出：折价 ${VpsValuationEngine.formatSignedCny(spread)}"
                else ->
                    "本帖按残值平出：平出 ¥0.00"
            }
            val detail = when {
                pricing.askPriceCny != null && pricing.residualCny != null -> {
                    val totalLabel = if (pricing.isAskDerived) "推算总价" else "总价"
                    "净残值 ¥${formatCompactCny(pricing.residualCny)} · $totalLabel ¥${formatCompactCny(pricing.askPriceCny)}"
                }
                pricing.askPriceCny != null ->
                    "明盘总价 ¥${formatCompactCny(pricing.askPriceCny)}"
                else ->
                    "未标续费/到期，点「估值核算」算总价"
            }
            headline to detail
        }
        pricing.askPriceCny != null -> {
            "本帖挂牌总价 ¥${formatCompactCny(pricing.askPriceCny)}" to "点「估值核算」补充续费与到期日即可算溢价"
        }
        else -> null
    }
}

internal fun formatObservationDetail(observation: TradePremiumObservation): String = buildString {
    append(observation.source.label)
    if (observation.isClosedSold) append(" · 已出")
    val expr = when {
        observation.isDerived && observation.askPriceCny != null && observation.residualCny != null ->
            "推算溢价 ${VpsValuationEngine.formatSignedCny(observation.premiumDeltaCny)}（总价¥${formatCompactCny(observation.askPriceCny)} − 残值¥${formatCompactCny(observation.residualCny)}）"
        !observation.isDerived && observation.isAskDerived && observation.askPriceCny != null && observation.residualCny != null -> {
            val spreadTerm = when {
                observation.premiumDeltaCny > BigDecimal.ZERO ->
                    "+ 溢价¥${formatCompactCny(observation.premiumDeltaCny)}"
                observation.premiumDeltaCny < BigDecimal.ZERO ->
                    "− 折价¥${formatCompactCny(observation.premiumDeltaCny.abs())}"
                else -> "+ 平出¥0"
            }
            "推算总价 ¥${formatCompactCny(observation.askPriceCny)}（残值¥${formatCompactCny(observation.residualCny)} $spreadTerm）"
        }
        !observation.isDerived && observation.askPriceCny != null -> {
            val base = observation.matchedExpression.takeIf { it.isNotBlank() }
            if (base != null) {
                "总价¥${formatCompactCny(observation.askPriceCny)} · $base"
            } else {
                "总价¥${formatCompactCny(observation.askPriceCny)}"
            }
        }
        observation.matchedExpression.isNotBlank() ->
            observation.matchedExpression
        observation.isDerived ->
            "推算溢价"
        else -> ""
    }
    if (expr.isNotBlank()) {
        append(" · ")
        append(expr)
    }
}

internal fun formatAskObservationDetail(observation: TradeAskPriceObservation): String = buildString {
    append(observation.source.label)
    if (observation.isClosedSold) append(" · 已出")
    if (observation.matchedExpression.isNotBlank()) {
        append(" · ")
        append(observation.matchedExpression)
    } else {
        append(" · 挂牌 ¥${formatCompactCny(observation.askPriceCny)}")
    }
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PulseAccentPill(
                text = if (referenceState.hasRemoteResults) "本地+全站" else "本地缓存",
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.widthIn(max = 110.dp),
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
                    modifier = Modifier
                        .widthIn(max = 148.dp)
                        .then(
                            if (supportsInlineEdit) {
                                Modifier.clickable {
                                    isEditingQuery = !isEditingQuery
                                    if (isEditingQuery) {
                                        draftQuery = state.query
                                    }
                                }
                            } else {
                                Modifier
                            },
                        ),
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

private sealed interface MarketSampleRowItem {
    val topicId: Long

    data class Spread(val observation: TradePremiumObservation) : MarketSampleRowItem {
        override val topicId: Long get() = observation.topicId
    }

    data class AskOnly(val observation: TradeAskPriceObservation) : MarketSampleRowItem {
        override val topicId: Long get() = observation.topicId
    }

    data class UnparsedMatched(val topic: TradeCandidateTopic) : MarketSampleRowItem {
        override val topicId: Long get() = topic.topicId
    }
}

private const val DEFAULT_VISIBLE_SAMPLE_ROWS = 3
private const val MAX_EXPANDED_SAMPLE_ROWS = 8

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
    var samplesExpanded by rememberSaveable(state.query) { mutableStateOf(false) }
    var samplesCollapsed by rememberSaveable(state.query) { mutableStateOf(false) }

    val isLoading = state.searchStatus == MarketSearchStatus.LOADING
    val effectiveCooldown = maxOf(cooldownRemainingSeconds, state.cooldownRemainingSeconds)
    val isInCooldown = effectiveCooldown > 0 &&
        (state.searchStatus == MarketSearchStatus.COOLDOWN || state.searchStatus == MarketSearchStatus.RATE_LIMITED)
    val hasSpreadSamples = dist != null && dist.sampleCount > 0
    val hasAskSamples = dist != null && dist.askSampleCount > 0 && dist.askMedianCny != null
    val hasMatchedTopics = dist != null && (dist.matchedTopicCount > 0 || dist.pricedTopicCount > 0)
    val hasAnyPriced = hasSpreadSamples || hasAskSamples

    val sampleItems = remember(dist) {
        if (dist == null) {
            emptyList()
        } else {
            buildList {
                dist.observations.forEach { add(MarketSampleRowItem.Spread(it)) }
                dist.askOnlyObservations.forEach { add(MarketSampleRowItem.AskOnly(it)) }
                if (isEmpty() || dist.pricedTopicCount < dist.matchedTopicCount) {
                    val listedIds = mapTo(HashSet()) { it.topicId }
                    dist.matchedUnparsedTopics.forEach { topic ->
                        if (topic.topicId !in listedIds) {
                            add(MarketSampleRowItem.UnparsedMatched(topic))
                        }
                    }
                }
            }
        }
    }

    state.currentTopicPricing?.let(::formatCurrentTopicPricingSummary)?.let { (headline, detail) ->
        CurrentTopicPricingBanner(
            headline = headline,
            detail = detail,
        )
    }

    if (state.query.isBlank() && canSearchOverride != true) {
        Text(
            text = "未能从标题自动识别机型，输入商家与型号关键词后即可检索近期溢价/挂牌价参考。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (hasSpreadSamples) {
        DistributionSummaryBlock(dist = dist)
    } else if (hasAskSamples) {
        AskPriceSummaryBlock(
            dist = dist,
            hasRemoteResults = state.hasRemoteResults,
        )
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
                text = "正在检索「${state.query}」交易版同款价格与主楼信息…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else if (hasMatchedTopics) {
        MatchedOnlySummaryBlock(
            dist = dist,
            hasRemoteResults = state.hasRemoteResults,
            hasClickableSamples = sampleItems.isNotEmpty(),
        )
    } else {
        val emptyHint = if (state.hasRemoteResults) {
            "交易版近期暂无「${state.query}」同款记录，可简化关键词（如商家 + 核心型号）后重试。"
        } else {
            "本地缓存暂无「${state.query}」同款记录，可点击下方按钮搜交易版或调整关键词。"
        }
        Text(
            text = emptyHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (isLoading && (hasSpreadSamples || hasAskSamples)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.8.dp,
            )
            Text(
                text = "正在联网补充更多同款帖与主楼报价…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    sanitizeMarketStatusMessage(state)?.let { msg ->
        val isHardError = !hasAnyPriced && !hasMatchedTopics && (
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

        if (sampleItems.isNotEmpty()) {
            val hasMoreThanPreview = sampleItems.size > DEFAULT_VISIBLE_SAMPLE_ROWS
            val toggleLabel = when {
                samplesCollapsed -> "查看样本 (${sampleItems.size})"
                hasMoreThanPreview && !samplesExpanded -> "展开全部 (${sampleItems.size})"
                else -> "收起样本"
            }
            val isToggleExpanded = !samplesCollapsed && (!hasMoreThanPreview || samplesExpanded)
            TextButton(
                onClick = {
                    when {
                        samplesCollapsed -> {
                            samplesCollapsed = false
                            samplesExpanded = hasMoreThanPreview
                        }
                        hasMoreThanPreview && !samplesExpanded -> {
                            samplesExpanded = true
                        }
                        else -> {
                            samplesExpanded = false
                            samplesCollapsed = true
                        }
                    }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = toggleLabel,
                    style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = if (isToggleExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }

    if (sampleItems.isNotEmpty()) {
        val visibleLimit = if (samplesExpanded) MAX_EXPANDED_SAMPLE_ROWS else DEFAULT_VISIBLE_SAMPLE_ROWS
        AnimatedVisibility(visible = !samplesCollapsed) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                sampleItems.take(visibleLimit).forEach { item ->
                    when (item) {
                        is MarketSampleRowItem.Spread -> ObservationRow(
                            observation = item.observation,
                            onClick = onOpenTopic?.let { open ->
                                { open(item.observation.topicId, item.observation.title) }
                            },
                        )
                        is MarketSampleRowItem.AskOnly -> AskObservationRow(
                            observation = item.observation,
                            onClick = onOpenTopic?.let { open ->
                                { open(item.observation.topicId, item.observation.title) }
                            },
                        )
                        is MarketSampleRowItem.UnparsedMatched -> MatchedTopicRow(
                            topic = item.topic,
                            onClick = onOpenTopic?.let { open ->
                                { open(item.topic.topicId, item.topic.title) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentTopicPricingBanner(
    headline: String,
    detail: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PulseAccentPill(
                text = "本帖估算",
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = headline,
                style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SummaryMetricTile(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.72f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall.withTabularNumbers(),
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1,
                softWrap = false,
            )
        }
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    val derivedSpreadCount = maxOf(dist.derivedSampleCount, dist.observations.count { it.isDerived })
    val derivedAskCount = maxOf(dist.derivedAskSampleCount, dist.askObservations.count { it.isAskDerived })

    val spreadDetail = buildString {
        when {
            dist.sampleCount == 1 -> append("单样本参考 · 共 1 条")
            dist.sampleCount == 2 -> append("参考区间：$minStr ~ $maxStr")
            else -> append("P25~P75：$p25Str ~ $p75Str · 极值 $minStr ~ $maxStr")
        }
        if (derivedSpreadCount > 0) {
            append(" · 含推算 $derivedSpreadCount 条")
        }
    }

    val askMedian = dist.askMedianCny?.takeIf { dist.askSampleCount > 0 }
    val askDetail = if (askMedian != null) {
        val askP25 = dist.askP25Cny ?: dist.askMinCny ?: askMedian
        val askP75 = dist.askP75Cny ?: dist.askMaxCny ?: askMedian
        val askMin = dist.askMinCny ?: askP25
        val askMax = dist.askMaxCny ?: askP75
        buildString {
            when {
                dist.askSampleCount == 1 -> append("单样本参考 · 共 1 条")
                dist.askSampleCount == 2 -> append("参考区间：¥${formatCompactCny(askMin)} ~ ¥${formatCompactCny(askMax)}")
                else -> append("P25~P75：¥${formatCompactCny(askP25)} ~ ¥${formatCompactCny(askP75)} · 区间 ¥${formatCompactCny(askMin)} ~ ¥${formatCompactCny(askMax)}")
            }
            if (derivedAskCount > 0) {
                append(" · 含推算 $derivedAskCount 条")
            }
        }
    } else {
        null
    }

    val summaryDetail = formatDistributionSummaryDetail(dist)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "以溢价差额为跨到期日标准，同步折算总价",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SummaryMetricTile(
            label = "溢价中位",
            value = medianStr,
            valueColor = MaterialTheme.colorScheme.primary,
            detail = spreadDetail,
        )

        if (askMedian != null && askDetail != null) {
            SummaryMetricTile(
                label = "总价中位",
                value = "¥${formatCompactCny(askMedian)}",
                valueColor = MaterialTheme.colorScheme.onSurface,
                detail = askDetail,
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
            if (derivedSpreadCount > 0) {
                PulseAccentPill(
                    text = "含推算溢价 $derivedSpreadCount 条",
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            if (derivedAskCount > 0) {
                PulseAccentPill(
                    text = "含推算总价 $derivedAskCount 条",
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.78f),
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskPriceSummaryBlock(
    dist: TradePremiumDistribution,
    hasRemoteResults: Boolean,
) {
    val median = dist.askMedianCny?.takeIf { dist.askSampleCount > 0 } ?: return
    val askP25 = dist.askP25Cny ?: dist.askMinCny ?: median
    val askP75 = dist.askP75Cny ?: dist.askMaxCny ?: median
    val askMin = dist.askMinCny ?: askP25
    val askMax = dist.askMaxCny ?: askP75
    val derivedAskCount = maxOf(dist.derivedAskSampleCount, dist.askObservations.count { it.isAskDerived })
    val matchPricedText = formatMatchAndPricedSummary(dist)

    val metricDetail = buildString {
        when {
            dist.askSampleCount == 1 -> append("单样本参考 · 共 1 条")
            dist.askSampleCount == 2 -> append("参考区间：¥${formatCompactCny(askMin)} ~ ¥${formatCompactCny(askMax)}")
            else -> append("P25~P75：¥${formatCompactCny(askP25)} ~ ¥${formatCompactCny(askP75)} · 区间 ¥${formatCompactCny(askMin)} ~ ¥${formatCompactCny(askMax)}")
        }
        if (derivedAskCount > 0) {
            append(" · 含推算 $derivedAskCount 条")
        }
    }
    val subHint = if (hasRemoteResults) {
        "未获取到续费/到期信息以推算溢价，当前按同款挂牌总价汇总参考。"
    } else {
        "当前按挂牌总价汇总参考，点击「搜交易版」可拉取主楼正文补充推算溢价。"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SummaryMetricTile(
            label = "同款挂牌价 中位",
            value = "¥${formatCompactCny(median)}",
            valueColor = MaterialTheme.colorScheme.primary,
            detail = metricDetail,
        )
        Text(
            text = matchPricedText,
            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = subHint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PulseAccentPill(
                text = "挂牌价 ${dist.askSampleCount} 条",
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (derivedAskCount > 0) {
                PulseAccentPill(
                    text = "含推算总价 $derivedAskCount 条",
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun MatchedOnlySummaryBlock(
    dist: TradePremiumDistribution,
    hasRemoteResults: Boolean,
    hasClickableSamples: Boolean,
) {
    val summaryLine = formatMatchAndPricedSummary(dist)
    val hint = formatMatchedOnlyHint(
        hasRemoteResults = hasRemoteResults,
        hasClickableSamples = hasClickableSamples,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = summaryLine,
            style = MaterialTheme.typography.bodyMedium.withTabularNumbers(),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        Column(
            modifier = Modifier.widthIn(max = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            PulseAccentPill(
                text = VpsValuationEngine.formatSignedCny(observation.premiumDeltaCny),
                containerColor = badgeColors.first,
                contentColor = badgeColors.second,
                modifier = Modifier.widthIn(max = 96.dp),
            )
            if (observation.askPriceCny != null) {
                val askTag = if (observation.isAskDerived) {
                    "约¥${formatCompactCny(observation.askPriceCny)}"
                } else {
                    "总¥${formatCompactCny(observation.askPriceCny)}"
                }
                val isEstimated = observation.isDerived || observation.isAskDerived
                PulseAccentPill(
                    text = askTag,
                    containerColor = if (isEstimated) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    contentColor = if (isEstimated) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier.widthIn(max = 96.dp),
                )
            } else if (observation.isDerived) {
                PulseAccentPill(
                    text = "推算",
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.widthIn(max = 96.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = observation.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = formatObservationDetail(observation),
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AskObservationRow(
    observation: TradeAskPriceObservation,
    onClick: (() -> Unit)?,
) {
    val ext = NodePulseThemeTokens.extendedColors
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
            text = if (observation.isAskDerived) {
                "约¥${formatCompactCny(observation.askPriceCny)}"
            } else {
                "总价 ¥${formatCompactCny(observation.askPriceCny)}"
            },
            containerColor = if (observation.isAskDerived) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            contentColor = if (observation.isAskDerived) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            modifier = Modifier.widthIn(max = 96.dp),
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
                text = formatAskObservationDetail(observation),
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MatchedTopicRow(
    topic: TradeCandidateTopic,
    onClick: (() -> Unit)?,
) {
    val ext = NodePulseThemeTokens.extendedColors
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
            text = "同款",
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = topic.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${topic.source.label} · 未标明价格",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
