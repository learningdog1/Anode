@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.fleet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.domain.fleet.FleetDeliveryMethod
import com.nodeseek.app.domain.fleet.FleetSalePostBuilder
import com.nodeseek.app.domain.fleet.FleetSalePostOutcome
import com.nodeseek.app.domain.fleet.FleetSalePostRequest
import com.nodeseek.app.domain.fleet.ProbeServerSnapshot
import com.nodeseek.app.domain.fleet.PushFeeBearer
import com.nodeseek.app.domain.fleet.ServerIdleAssessment
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.valuation.DealSpreadStatus
import com.nodeseek.app.domain.valuation.FxRateSource
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.domain.valuation.ValuationMarkdownStyle
import com.nodeseek.app.domain.valuation.VpsValuationEngine
import com.nodeseek.app.ui.components.MarkdownRenderer
import com.nodeseek.app.ui.components.SortSegmentItem
import com.nodeseek.app.ui.components.SortSegmentedSwitcher
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseSectionHeader
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * "不想续了 · 一键出机" bottom sheet:
 * Calculates remaining value via [VpsValuationEngine] + [FleetSalePostBuilder], lets the user
 * tune asking price, push fee bearer, delivery method, and live probe telemetry inclusion,
 * previews the complete NodeSeek `trade` post, and jumps straight to TopicComposer via
 * [onCreateSalePost].
 */
@Composable
internal fun FleetSalePostSheet(
    asset: VpsAssetRecord,
    linkedProbe: ProbeServerSnapshot?,
    fxRatesState: FxRatesState?,
    onCreateSalePost: (title: String, markdownBody: String) -> Unit,
    onCopiedFeedback: (String) -> Unit,
    onDismiss: () -> Unit,
    idleAssessment: ServerIdleAssessment? = null,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp
    val ext = NodePulseThemeTokens.extendedColors
    val isDark = NodePulseThemeTokens.isDark

    val fxQuote = fxRatesState?.quoteFor(asset.currency)
    val fxRate = fxQuote?.rateToCny ?: asset.currency.defaultCnyRate
    val fxSourceNote = remember(fxQuote, fxRatesState?.providerDate) {
        when (fxQuote?.source) {
            FxRateSource.ONLINE_FRESH -> fxRatesState?.providerDate?.let { "在线汇率 $it" } ?: "在线实时汇率"
            FxRateSource.ONLINE_CACHED -> "本地缓存汇率"
            FxRateSource.MANUAL_OVERRIDE -> "手动校准汇率"
            else -> "参考汇率"
        }
    }

    // Initial baseline valuation to seed default asking price = remainingValueCny
    val baselineOutcome = remember(asset, fxRate) {
        FleetSalePostBuilder.build(
            FleetSalePostRequest(
                asset = asset,
                probeSnapshot = linkedProbe,
                fxRateToCny = fxRate,
                fxSourceNote = fxSourceNote,
            ),
        )
    }
    val baselineRemainingValue = remember(baselineOutcome) {
        (baselineOutcome as? FleetSalePostOutcome.Success)?.result?.valuationResult?.remainingValueCny
            ?: BigDecimal.ZERO
    }

    var askingPriceText by remember(asset.id, baselineRemainingValue) {
        mutableStateOf(baselineRemainingValue.setScale(2, RoundingMode.HALF_UP).toPlainString())
    }
    var pushFeeText by remember(asset.id) { mutableStateOf("0") }
    var pushFeeBearer by remember(asset.id) { mutableStateOf(PushFeeBearer.BUYER) }
    var deliveryMethod by remember(asset.id) { mutableStateOf(FleetDeliveryMethod.PUSH) }
    var includeProbeTelemetry by remember(asset.id) { mutableStateOf(linkedProbe != null) }
    var extraNote by remember(asset.id) { mutableStateOf(asset.notes) }
    var markdownStyle by remember { mutableStateOf(ValuationMarkdownStyle.THREE_COLUMN_TABLE) }
    var showRawMarkdown by remember { mutableStateOf(false) }
    var customTitleOverride by remember(asset.id) { mutableStateOf<String?>(null) }
    var copiedRecently by remember { mutableStateOf(false) }

    LaunchedEffect(copiedRecently) {
        if (copiedRecently) {
            delay(1800)
            copiedRecently = false
        }
    }

    val parsedAsking = askingPriceText.trim().toBigDecimalOrNull()
    val parsedPushFee = pushFeeText.trim().toBigDecimalOrNull() ?: BigDecimal.ZERO

    val saleOutcome = remember(
        asset,
        linkedProbe,
        fxRate,
        fxSourceNote,
        parsedAsking,
        parsedPushFee,
        pushFeeBearer,
        deliveryMethod,
        includeProbeTelemetry,
        extraNote,
        markdownStyle,
    ) {
        FleetSalePostBuilder.build(
            FleetSalePostRequest(
                asset = asset,
                probeSnapshot = linkedProbe,
                fxRateToCny = fxRate,
                fxSourceNote = fxSourceNote,
                askingPriceCny = parsedAsking,
                pushFeeAmount = parsedPushFee,
                pushFeeCurrency = ValuationCurrency.CNY,
                pushFeeBearer = pushFeeBearer,
                deliveryMethod = deliveryMethod,
                includeProbeTelemetry = includeProbeTelemetry,
                extraNote = extraNote,
                markdownStyle = markdownStyle,
            ),
        )
    }

    val successResult = (saleOutcome as? FleetSalePostOutcome.Success)?.result
    val effectiveTitle = customTitleOverride ?: successResult?.title.orEmpty()

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
                .heightIn(max = maxHeight)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Sheet Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (idleAssessment?.isIdle == true) "闲置回血 · 一键估值出机" else "不想续了 · 一键估值出机",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        if (idleAssessment?.isIdle == true) {
                            PulseAccentPill(
                                text = "闲置",
                                containerColor = ext.drumstickContainer,
                                contentColor = ext.onDrumstickContainer,
                            )
                        }
                    }
                    Text(
                        text = "${asset.nickname} · 到期 ${asset.expiryDateIso}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    idleAssessment?.takeIf { it.isIdle }?.recommendationText?.let { reason ->
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = ext.onDrumstickContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭")
                }
            }

            // Sticky Valuation Telemetry Card
            if (successResult != null) {
                val valuation = successResult.valuationResult
                val deal = valuation.transferBreakdown
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseCard(shape = PulseArchitecturalShapes.HeroBento)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                text = "净剩余价值",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "¥${valuation.remainingValueCny.toPlainString()}",
                                style = NodePulseThemeTokens.extraTypography.metricValue,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (deal != null) {
                            val badgeText = when (deal.spreadStatus) {
                                DealSpreadStatus.PAR -> "平出 ¥0.00"
                                else -> "${deal.spreadStatus.label} ${VpsValuationEngine.formatSignedCny(deal.buyerPremiumCny)}"
                            }
                            val ext = NodePulseThemeTokens.extendedColors
                            val (pillBg, pillFg) = when (deal.spreadStatus) {
                                DealSpreadStatus.DISCOUNT -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                                DealSpreadStatus.PAR -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
                                DealSpreadStatus.PREMIUM -> ext.drumstickContainer to ext.onDrumstickContainer
                            }
                            PulseAccentPill(
                                text = badgeText,
                                containerColor = pillBg,
                                contentColor = pillFg,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "剩余 ${VpsValuationEngine.formatDaysNumber(valuation.remainingDays)} 天",
                            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                        if (deal != null) {
                            Text(
                                text = "买家实付 ¥${deal.buyerTotalCostCny.toPlainString()} · 卖家净收 ¥${deal.sellerNetProceedsCny.toPlainString()}",
                                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .padding(start = 8.dp),
                            )
                        }
                    }
                }
            }

            // Scrollable configuration & preview
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Asking Price + Quick Presets
                PulseSectionHeader(title = "出让定价与过户选项", icon = Icons.Rounded.LocalOffer)
                OutlinedTextField(
                    value = askingPriceText,
                    onValueChange = {
                        askingPriceText = sanitizeAssetDecimal(it)
                        customTitleOverride = null
                    },
                    label = { Text("出让明盘价 (CNY ¥)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = PulseArchitecturalShapes.MediumBadge,
                    colors = fleetTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        "平出 (残值)" to baselineRemainingValue,
                        "-5 元折价" to baselineRemainingValue.subtract(BigDecimal("5")).coerceAtLeast(BigDecimal.ZERO),
                        "-10 元折价" to baselineRemainingValue.subtract(BigDecimal("10")).coerceAtLeast(BigDecimal.ZERO),
                        "+10 元" to baselineRemainingValue.add(BigDecimal("10")),
                        "+20 元" to baselineRemainingValue.add(BigDecimal("20")),
                    ).forEach { (label, target) ->
                        FleetChoiceChip(
                            label = label,
                            selected = parsedAsking?.compareTo(target.setScale(2, RoundingMode.HALF_UP)) == 0,
                            onClick = {
                                askingPriceText = target.setScale(2, RoundingMode.HALF_UP).toPlainString()
                                customTitleOverride = null
                            },
                        )
                    }
                }

                // Delivery Method
                Text(
                    text = "交付方式",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FleetDeliveryMethod.entries.forEach { method ->
                        FleetChoiceChip(
                            label = method.label,
                            selected = deliveryMethod == method,
                            onClick = { deliveryMethod = method },
                        )
                    }
                }

                // Push Fee & Bearer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = pushFeeText,
                        onValueChange = { pushFeeText = sanitizeAssetDecimal(it) },
                        label = { Text("Push / 过户费 (CNY ¥)") },
                        singleLine = true,
                        enabled = pushFeeBearer != PushFeeBearer.FREE,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = PulseArchitecturalShapes.MediumBadge,
                        colors = fleetTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PushFeeBearer.entries.forEach { bearer ->
                        FleetChoiceChip(
                            label = bearer.label,
                            selected = pushFeeBearer == bearer,
                            onClick = {
                                pushFeeBearer = bearer
                                if (bearer == PushFeeBearer.FREE) pushFeeText = "0"
                            },
                        )
                    }
                }

                if (linkedProbe != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "附带探针运行参考数据",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "仅展示在线时长、系统与负载摘要，绝不泄露面板地址或 Token",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = includeProbeTelemetry,
                            onCheckedChange = { includeProbeTelemetry = it },
                        )
                    }
                }

                OutlinedTextField(
                    value = extraNote,
                    onValueChange = { extraNote = it },
                    label = { Text("交易补充说明（选填）") },
                    placeholder = { Text("例如：IP 干净解锁流媒体，站内私信秒回") },
                    singleLine = true,
                    shape = PulseArchitecturalShapes.MediumBadge,
                    colors = fleetTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )

                PulseGradientDivider()

                // Title & Markdown Preview
                PulseSectionHeader(
                    title = "交易帖预览 (trade)",
                    icon = Icons.Rounded.Visibility,
                    trailing = {
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(0.8.dp, ext.cardBorder, CircleShape)
                                .pulseBouncyClickable(
                                    pressedScale = NodePulseMotion.chipPressScale,
                                    role = Role.Button,
                                    onClick = { showRawMarkdown = !showRawMarkdown },
                                )
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = if (showRawMarkdown) Icons.Rounded.Visibility else Icons.Rounded.Code,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = if (showRawMarkdown) "渲染预览" else "Markdown",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )

                OutlinedTextField(
                    value = effectiveTitle,
                    onValueChange = { customTitleOverride = it },
                    label = { Text("发帖标题（可微调）") },
                    singleLine = true,
                    shape = PulseArchitecturalShapes.MediumBadge,
                    colors = fleetTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )

                SortSegmentedSwitcher(
                    options = remember {
                        ValuationMarkdownStyle.entries.map { SortSegmentItem(it, it.label) }
                    },
                    selectedValue = markdownStyle,
                    onSelect = { markdownStyle = it },
                    modifier = Modifier.fillMaxWidth(),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseCard(
                            shape = PulseArchitecturalShapes.MediumBadge,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            elevation = PulseElevation.None,
                        )
                        .padding(12.dp),
                ) {
                    when (saleOutcome) {
                        is FleetSalePostOutcome.Invalid -> {
                            Text(
                                text = saleOutcome.errors.joinToString("；"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        is FleetSalePostOutcome.Success -> {
                            if (showRawMarkdown) {
                                SelectionContainer {
                                    Text(
                                        text = saleOutcome.result.markdownBody,
                                        style = NodePulseThemeTokens.extraTypography.codeBody,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            } else {
                                MarkdownRenderer(
                                    markdown = saleOutcome.result.markdownBody,
                                    bodyStyle = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
            }

            // Bottom Action Bar
            PulseGradientDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val isValid = successResult != null && effectiveTitle.isNotBlank()
                Row(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(0.9.dp, ext.cardBorder, CircleShape)
                        .pulseBouncyClickable(
                            enabled = isValid,
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.Button,
                            onClick = {
                                if (successResult != null) {
                                    copySalePostToClipboard(
                                        context,
                                        "$effectiveTitle\n\n${successResult.markdownBody}",
                                    )
                                    copiedRecently = true
                                    onCopiedFeedback("已复制出机标题与正文")
                                }
                            },
                        )
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = if (copiedRecently) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (copiedRecently) "已复制" else "复制",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(CircleShape)
                        .then(
                            if (isValid) Modifier.background(MaterialTheme.colorScheme.primary)
                            else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        )
                        .pulseBouncyClickable(
                            enabled = isValid,
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.Button,
                            onClick = {
                                if (successResult != null) {
                                    onCreateSalePost(effectiveTitle, successResult.markdownBody)
                                    onDismiss()
                                }
                            },
                        )
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    val tint = if (isValid) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = "一键跳转发帖",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = tint,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun copySalePostToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("NodeSeek VPS Sale Post", text))
}
