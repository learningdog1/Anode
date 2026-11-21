@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.restock

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodeseek.app.data.restock.MerchantLinkDetector
import com.nodeseek.app.data.restock.StockPageAnalyzer
import com.nodeseek.app.domain.restock.MerchantPanelType
import com.nodeseek.app.domain.restock.RestockMonitorController
import com.nodeseek.app.domain.restock.RestockRepository
import com.nodeseek.app.domain.restock.RestockStockStatus
import com.nodeseek.app.domain.restock.RestockWatchDraft
import com.nodeseek.app.domain.restock.RestockWatchItem
import com.nodeseek.app.domain.restock.StockAnalysisResult
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.pulseCard
import kotlinx.coroutines.launch
import java.net.URI
import java.util.UUID

internal val RESTOCK_POLL_INTERVAL_OPTIONS = listOf(1, 2, 5, 10, 15, 30, 60)

/**
 * Reusable Material 3 Expressive bottom sheet for adding or editing a VPS restock watch item.
 *
 * Designed so it can be invoked from [RestockMonitorScreen], on long-press of merchant product
 * links in `TopicDetailScreen`, and from Black Friday / Overseas deal cards.
 */
@Composable
fun AddRestockWatchBottomSheet(
    onDismiss: () -> Unit,
    onSave: (RestockWatchDraft) -> Unit,
    initialUrl: String = "",
    initialTitle: String = "",
    initialPriceHint: String = "",
    sourceTopicId: Long? = null,
    editingItem: RestockWatchItem? = null,
    defaultPollIntervalMinutes: Int = RestockWatchItem.DEFAULT_POLL_INTERVAL_MINUTES,
    onProbeUrl: (suspend (url: String, customInStockKeyword: String, customOutOfStockKeyword: String) -> StockAnalysisResult)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val fallbackAnalyzer = remember { StockPageAnalyzer() }
    val ext = NodePulseThemeTokens.extendedColors

    var urlText by rememberSaveable(editingItem?.id, initialUrl) {
        mutableStateOf(editingItem?.rawUrl ?: initialUrl)
    }
    var titleText by rememberSaveable(editingItem?.id, initialTitle) {
        mutableStateOf(editingItem?.title ?: initialTitle)
    }
    var priceHintText by rememberSaveable(editingItem?.id, initialPriceHint) {
        mutableStateOf(editingItem?.priceHint ?: initialPriceHint)
    }
    var selectedPanel by rememberSaveable(editingItem?.id) {
        mutableStateOf<MerchantPanelType?>(editingItem?.panelType)
    }
    var pollIntervalMinutes by rememberSaveable(editingItem?.id, defaultPollIntervalMinutes) {
        mutableIntStateOf(
            RestockWatchItem.clampPollInterval(
                editingItem?.pollIntervalMinutes ?: defaultPollIntervalMinutes,
            ),
        )
    }
    var customInStockKeyword by rememberSaveable(editingItem?.id) {
        mutableStateOf(editingItem?.customInStockKeyword.orEmpty())
    }
    var customOutOfStockKeyword by rememberSaveable(editingItem?.id) {
        mutableStateOf(editingItem?.customOutOfStockKeyword.orEmpty())
    }
    var showAdvancedKeywords by rememberSaveable(editingItem?.id) {
        mutableStateOf(
            editingItem != null && (
                editingItem.customInStockKeyword.isNotBlank() ||
                    editingItem.customOutOfStockKeyword.isNotBlank()
                ),
        )
    }
    var isProbing by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<StockAnalysisResult?>(null) }
    var urlError by remember { mutableStateOf<String?>(null) }

    val resolvedPreviewUrl = remember(urlText) {
        MerchantLinkDetector.resolveOrderUrl(urlText)
    }
    val wasUnwrapped = remember(urlText, resolvedPreviewUrl) {
        urlText.trim().isNotEmpty() &&
            resolvedPreviewUrl.isNotEmpty() &&
            !resolvedPreviewUrl.equals(urlText.trim(), ignoreCase = true)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = PulseArchitecturalShapes.BottomSheetTop,
        containerColor = ext.cardSurfaceElevated,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
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
                        imageVector = Icons.Rounded.Radar,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (editingItem != null) "编辑补货监控" else "添加补货监控",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "自动识别 WHMCS / SolusVM / HostBill / VirtFusion 与缺货状态",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭",
                    )
                }
            }

            if (sourceTopicId != null && sourceTopicId > 0L) {
                Row(
                    modifier = Modifier
                        .clip(PulseArchitecturalShapes.SmallChip)
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Forum,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "来源帖子 #$sourceTopicId",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            // Product URL input + 1-click Probe
            OutlinedTextField(
                value = urlText,
                onValueChange = {
                    urlText = it
                    urlError = null
                    probeResult = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("商品 / 下单页链接") },
                placeholder = { Text("https://my.merchant.com/cart.php?a=add&pid=123") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Link,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                isError = urlError != null,
                supportingText = {
                    when {
                        urlError != null -> Text(urlError!!)
                        wasUnwrapped -> Text(
                            text = "已自动解包直达链接：$resolvedPreviewUrl",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                singleLine = true,
                shape = PulseArchitecturalShapes.MediumBadge,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = {
                        val trimmed = urlText.trim()
                        if (trimmed.isEmpty()) {
                            urlError = "请先输入或粘贴商品链接"
                            return@FilledTonalButton
                        }
                        coroutineScope.launch {
                            isProbing = true
                            urlError = null
                            try {
                                val result = if (onProbeUrl != null) {
                                    onProbeUrl(trimmed, customInStockKeyword, customOutOfStockKeyword)
                                } else {
                                    fallbackAnalyzer.analyzeUrl(
                                        rawUrl = trimmed,
                                        customInStockKeyword = customInStockKeyword,
                                        customOutOfStockKeyword = customOutOfStockKeyword,
                                    )
                                }
                                probeResult = result
                                if (titleText.isBlank() && result.detectedTitle.isNotBlank()) {
                                    titleText = result.detectedTitle
                                }
                                if (priceHintText.isBlank() && result.detectedPriceHint.isNotBlank()) {
                                    priceHintText = result.detectedPriceHint
                                }
                                if (selectedPanel == null && result.panelType != MerchantPanelType.GENERIC) {
                                    selectedPanel = result.panelType
                                }
                            } finally {
                                isProbing = false
                            }
                        }
                    },
                    enabled = !isProbing,
                    shape = PulseArchitecturalShapes.MediumBadge,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    if (isProbing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "正在探测…",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Science,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "测试检测",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Text(
                    text = "一键探测面板类型、标题价格与当前库存",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }

            AnimatedVisibility(
                visible = probeResult != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                probeResult?.let { result ->
                    StockProbePreviewCard(result = result)
                }
            }

            // Title & Price inputs
            OutlinedTextField(
                value = titleText,
                onValueChange = { titleText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("商品名称（留空自动提取）") },
                placeholder = { Text("例如：RackNerd 2C2G 年付特惠款") },
                singleLine = true,
                shape = PulseArchitecturalShapes.MediumBadge,
            )

            OutlinedTextField(
                value = priceHintText,
                onValueChange = { priceHintText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("参考价格（可选）") },
                placeholder = { Text("例如：$10.98/年 或 ¥99/年") },
                singleLine = true,
                shape = PulseArchitecturalShapes.MediumBadge,
            )

            // Panel type selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "商城面板类型",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = selectedPanel == null,
                        onClick = { selectedPanel = null },
                        label = { Text("自动识别") },
                    )
                    MerchantPanelType.entries.forEach { panel ->
                        FilterChip(
                            selected = selectedPanel == panel,
                            onClick = { selectedPanel = panel },
                            label = { Text(panel.displayName) },
                        )
                    }
                }
            }

            // Poll interval chips
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "前台轮询间隔（每 $pollIntervalMinutes 分钟）",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RESTOCK_POLL_INTERVAL_OPTIONS.forEach { minutes ->
                        FilterChip(
                            selected = pollIntervalMinutes == minutes,
                            onClick = { pollIntervalMinutes = minutes },
                            label = { Text("${minutes}m") },
                        )
                    }
                }
            }

            PulseGradientDivider()

            // Collapsible custom keyword override section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PulseArchitecturalShapes.SmallChip)
                    .clickable { showAdvancedKeywords = !showAdvancedKeywords }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "自定义关键词覆盖（可选）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (showAdvancedKeywords) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = showAdvancedKeywords,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = customInStockKeyword,
                        onValueChange = { customInStockKeyword = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("自定义有货关键词（逗号分隔）") },
                        placeholder = { Text("例如：Order Now, 立即下单, 现货") },
                        singleLine = true,
                        shape = PulseArchitecturalShapes.MediumBadge,
                    )
                    OutlinedTextField(
                        value = customOutOfStockKeyword,
                        onValueChange = { customOutOfStockKeyword = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("自定义缺货关键词（逗号分隔）") },
                        placeholder = { Text("例如：Out of Stock, 已售罄, 补货中") },
                        singleLine = true,
                        shape = PulseArchitecturalShapes.MediumBadge,
                    )
                }
            }

            // Confirm button
            Button(
                onClick = {
                    val trimmed = urlText.trim()
                    val resolved = MerchantLinkDetector.resolveOrderUrl(trimmed)
                    if (!resolved.startsWith("http://", ignoreCase = true) &&
                        !resolved.startsWith("https://", ignoreCase = true)
                    ) {
                        urlError = "请输入有效的 http:// 或 https:// 商品链接"
                        return@Button
                    }
                    val finalPanel = selectedPanel
                        ?: probeResult?.panelType
                        ?: MerchantLinkDetector.inferPanelTypeFromUrl(resolved)
                    val finalTitle = titleText.trim()
                        .ifBlank { probeResult?.detectedTitle.orEmpty() }
                        .ifBlank {
                            runCatching { URI(resolved).host }.getOrNull()
                                ?.removePrefix("www.")
                                ?.takeIf { it.isNotBlank() }
                                ?.let { "$it VPS" }
                                ?: "VPS 商品监控"
                        }

                    onSave(
                        RestockWatchDraft(
                            rawUrl = trimmed,
                            title = finalTitle,
                            panelType = finalPanel,
                            priceHint = priceHintText.trim().ifBlank { probeResult?.detectedPriceHint.orEmpty() },
                            customInStockKeyword = customInStockKeyword.trim(),
                            customOutOfStockKeyword = customOutOfStockKeyword.trim(),
                            pollIntervalMinutes = pollIntervalMinutes,
                            isEnabled = editingItem?.isEnabled ?: true,
                            sourceTopicId = sourceTopicId ?: editingItem?.sourceTopicId,
                            initialStockStatus = probeResult?.stockStatus
                                ?: editingItem?.stockStatus
                                ?: RestockStockStatus.UNKNOWN,
                            initialEvidenceSummary = probeResult?.matchedSignal
                                ?: editingItem?.lastEvidenceSummary.orEmpty(),
                            editingId = editingItem?.id,
                        ),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = PulseArchitecturalShapes.MediumBadge,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (editingItem != null) "保存监控设置" else "开始蹲守补货",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Convenience wrapper for `@integrator` call sites (such as `TopicDetailScreen` long-press menus
 * and Black Friday / Overseas deal cards) that directly saves into [repository] and triggers an
 * initial check through [controller].
 */
@Composable
fun AddRestockWatchBottomSheet(
    repository: RestockRepository,
    controller: RestockMonitorController,
    onDismiss: () -> Unit,
    initialUrl: String = "",
    initialTitle: String = "",
    initialPriceHint: String = "",
    sourceTopicId: Long? = null,
    onWatchSaved: (RestockWatchItem) -> Unit = {},
) {
    val settings by repository.settingsFlow.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    AddRestockWatchBottomSheet(
        onDismiss = onDismiss,
        initialUrl = initialUrl,
        initialTitle = initialTitle,
        initialPriceHint = initialPriceHint,
        sourceTopicId = sourceTopicId,
        defaultPollIntervalMinutes = settings.defaultPollIntervalMinutes,
        onProbeUrl = { url, inKw, outKw ->
            controller.probeUrl(
                rawUrl = url,
                customInStockKeyword = inKw,
                customOutOfStockKeyword = outKw,
            )
        },
        onSave = { draft ->
            coroutineScope.launch {
                val now = System.currentTimeMillis()
                val resolved = MerchantLinkDetector.resolveOrderUrl(draft.rawUrl)
                val item = RestockWatchItem(
                    id = UUID.randomUUID().toString(),
                    title = draft.title,
                    rawUrl = draft.rawUrl,
                    resolvedOrderUrl = resolved,
                    panelType = draft.panelType ?: MerchantLinkDetector.inferPanelTypeFromUrl(resolved),
                    priceHint = draft.priceHint,
                    customInStockKeyword = draft.customInStockKeyword,
                    customOutOfStockKeyword = draft.customOutOfStockKeyword,
                    pollIntervalMinutes = RestockWatchItem.clampPollInterval(draft.pollIntervalMinutes),
                    isEnabled = true,
                    stockStatus = draft.initialStockStatus,
                    lastCheckedEpochMillis = if (draft.initialStockStatus != RestockStockStatus.UNKNOWN) now else 0L,
                    lastEvidenceSummary = draft.initialEvidenceSummary,
                    sourceTopicId = draft.sourceTopicId,
                    createdAtEpochMillis = now,
                    alertWaveArmed = draft.initialStockStatus == RestockStockStatus.OUT_OF_STOCK,
                )
                val saved = repository.addOrUpdateWatch(item)
                if (draft.initialStockStatus == RestockStockStatus.UNKNOWN) {
                    controller.triggerCheckSingle(saved.id)
                }
                onWatchSaved(saved)
                onDismiss()
            }
        },
    )
}

@Composable
internal fun StockProbePreviewCard(
    result: StockAnalysisResult,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .pulseCard(shape = PulseArchitecturalShapes.MediumBadge)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RestockStatusPill(status = result.stockStatus)
            PanelTypeBadge(panelType = result.panelType)
            if (result.detectedPriceHint.isNotBlank()) {
                PriceHintBadge(priceHint = result.detectedPriceHint)
            }
        }
        if (result.detectedTitle.isNotBlank()) {
            Text(
                text = result.detectedTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val detail = result.errorMessage ?: result.matchedSignal
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (result.stockStatus == RestockStockStatus.CHECK_ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

internal data class RestockStatusColors(
    val container: Color,
    val content: Color,
    val border: Color,
    val icon: ImageVector,
)

@Composable
internal fun restockStatusAccentColor(status: RestockStockStatus): Color = when (status) {
    RestockStockStatus.IN_STOCK -> MaterialTheme.colorScheme.primary
    RestockStockStatus.OUT_OF_STOCK -> MaterialTheme.colorScheme.onSurfaceVariant
    RestockStockStatus.CHECK_ERROR -> MaterialTheme.colorScheme.error
    RestockStockStatus.UNKNOWN -> MaterialTheme.colorScheme.outline
}

@Composable
internal fun rememberRestockStatusColors(status: RestockStockStatus): RestockStatusColors = when (status) {
    RestockStockStatus.IN_STOCK -> RestockStatusColors(
        container = MaterialTheme.colorScheme.primaryContainer,
        content = MaterialTheme.colorScheme.onPrimaryContainer,
        border = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
        icon = Icons.Rounded.CheckCircle,
    )
    RestockStockStatus.OUT_OF_STOCK -> RestockStatusColors(
        container = MaterialTheme.colorScheme.surfaceContainerHigh,
        content = MaterialTheme.colorScheme.onSurfaceVariant,
        border = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        icon = Icons.Rounded.HourglassBottom,
    )
    RestockStockStatus.CHECK_ERROR -> RestockStatusColors(
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
        border = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
        icon = Icons.Rounded.ErrorOutline,
    )
    RestockStockStatus.UNKNOWN -> RestockStatusColors(
        container = MaterialTheme.colorScheme.surfaceContainerHigh,
        content = MaterialTheme.colorScheme.onSurfaceVariant,
        border = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        icon = Icons.Rounded.Schedule,
    )
}

@Composable
fun RestockStatusPill(
    status: RestockStockStatus,
    modifier: Modifier = Modifier,
) {
    val colors = rememberRestockStatusColors(status)
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(colors.container)
            .border(width = 0.8.dp, color = colors.border, shape = CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = colors.icon,
            contentDescription = null,
            tint = colors.content,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = status.displayName,
            style = NodePulseThemeTokens.extraTypography.microLabel,
            color = colors.content,
            maxLines = 1,
        )
    }
}

@Composable
internal fun PanelTypeBadge(
    panelType: MerchantPanelType,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    Row(
        modifier = modifier
            .clip(PulseArchitecturalShapes.SmallChip)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                width = 0.75.dp,
                color = ext.cardBorder,
                shape = PulseArchitecturalShapes.SmallChip,
            )
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = panelType.displayName,
            style = NodePulseThemeTokens.extraTypography.microLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
internal fun PriceHintBadge(
    priceHint: String,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    Row(
        modifier = modifier
            .clip(PulseArchitecturalShapes.SmallChip)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                width = 0.75.dp,
                color = ext.cardBorder,
                shape = PulseArchitecturalShapes.SmallChip,
            )
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = priceHint,
            style = NodePulseThemeTokens.extraTypography.microLabel.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
