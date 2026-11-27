package com.nodeseek.app.ui.carpool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nodeseek.app.domain.carpool.CarpoolBillingCycle
import com.nodeseek.app.domain.carpool.CarpoolCalculationInput
import com.nodeseek.app.domain.carpool.CarpoolCalculationOutcome
import com.nodeseek.app.domain.carpool.CarpoolCalculator
import com.nodeseek.app.domain.carpool.CarpoolMarkdownGenerator
import com.nodeseek.app.domain.carpool.CarpoolSeatSplitMode
import com.nodeseek.app.domain.carpool.CarpoolStatusUpdater
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.ui.components.PulseTextField
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CarpoolCalculatorSheet(
    initialServiceName: String = "",
    onInsertCarpoolMarkdown: (markdown: String, suggestedStatusTag: String?) -> Unit,
    onCopiedFeedback: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val ext = NodePulseThemeTokens.extendedColors
    val carpoolSpec = NodePulseThemeTokens.categorySpec("carpool")
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var serviceName by rememberSaveable(initialServiceName) {
        mutableStateOf(initialServiceName.take(80).ifBlank { "Netflix 4K / 优化线路合租" })
    }
    var totalSeats by rememberSaveable { mutableIntStateOf(4) }
    var occupiedSeats by rememberSaveable { mutableIntStateOf(3) }
    var totalCostText by rememberSaveable { mutableStateOf("240") }
    var currencyName by rememberSaveable { mutableStateOf(ValuationCurrency.CNY.name) }
    val currency = remember(currencyName) { ValuationCurrency.valueOf(currencyName) }
    var fxRateText by rememberSaveable { mutableStateOf(currency.defaultCnyRate.toPlainString()) }
    var cycleName by rememberSaveable { mutableStateOf(CarpoolBillingCycle.QUARTERLY.name) }
    val billingCycle = remember(cycleName) { CarpoolBillingCycle.valueOf(cycleName) }
    var customDaysText by rememberSaveable { mutableStateOf("90") }
    var splitModeName by rememberSaveable { mutableStateOf(CarpoolSeatSplitMode.EQUAL_ALL_SEATS.name) }
    val splitMode = remember(splitModeName) { CarpoolSeatSplitMode.valueOf(splitModeName) }
    var jumpPenaltyText by rememberSaveable { mutableStateOf("10") }
    var simulatedUsedDaysText by rememberSaveable { mutableStateOf("30") }
    var updateTitleWithTag by rememberSaveable { mutableStateOf(true) }
    var showMarkdownPreview by rememberSaveable { mutableStateOf(false) }

    val parsedTotalCost = totalCostText.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val parsedFxRate = fxRateText.toBigDecimalOrNull() ?: currency.defaultCnyRate
    val parsedCustomDays = customDaysText.toIntOrNull()
    val parsedPenalty = jumpPenaltyText.toBigDecimalOrNull() ?: BigDecimal.ZERO

    val outcome = remember(
        serviceName,
        totalSeats,
        occupiedSeats,
        parsedTotalCost,
        currency,
        parsedFxRate,
        billingCycle,
        parsedCustomDays,
        splitMode,
        parsedPenalty,
    ) {
        CarpoolCalculator.calculate(
            CarpoolCalculationInput(
                serviceName = serviceName,
                totalSeats = totalSeats,
                occupiedSeats = occupiedSeats.coerceIn(1, totalSeats),
                totalCycleCost = parsedTotalCost,
                currency = currency,
                fxRateToCny = parsedFxRate,
                billingCycle = billingCycle,
                customCycleDays = parsedCustomDays,
                seatSplitMode = splitMode,
                jumpPenaltyPercent = parsedPenalty,
            ),
        )
    }

    val successResult = (outcome as? CarpoolCalculationOutcome.Success)?.result
    val refundPreview = remember(successResult, simulatedUsedDaysText) {
        if (successResult == null) {
            null
        } else {
            val used = simulatedUsedDaysText.toIntOrNull() ?: (successResult.effectiveCycleDays / 3)
            CarpoolCalculator.calculateProRataRefund(
                paidAmountCny = successResult.perPersonCycleCostCny,
                totalDays = successResult.effectiveCycleDays,
                usedDays = used,
                penaltyPercent = successResult.jumpPenaltyPercent,
            )
        }
    }

    val generatedMarkdown = remember(successResult) {
        successResult?.let { CarpoolMarkdownGenerator.generateMarkdown(it) }.orEmpty()
    }

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
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(carpoolSpec.spineColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Groups,
                        contentDescription = null,
                        tint = carpoolSpec.spineColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "拼车发车计算器 & 车位表",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "自动核算每人每季/月/年分摊与翻车/跳车按天退费",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val liveTag = CarpoolStatusUpdater.formatStatusTag(occupiedSeats, totalSeats)
                PulseAccentPill(
                    text = liveTag,
                    containerColor = carpoolSpec.spineColor.copy(alpha = 0.16f),
                    contentColor = carpoolSpec.spineColor,
                )
            }

            // Live Result Card
            if (successResult != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseCard(
                            shape = PulseArchitecturalShapes.EditorialCard,
                            containerColor = ext.cardSurface,
                            elevation = PulseElevation.Subtle,
                            accentColor = carpoolSpec.spineColor,
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "每人本周期 ¥${successResult.perPersonCycleCostCny.toPlainString()}",
                            style = MaterialTheme.typography.titleMedium.withTabularNumbers(),
                            fontWeight = FontWeight.ExtraBold,
                            color = carpoolSpec.spineColor,
                        )
                        Text(
                            text = "已上车 ${successResult.occupiedSeats} · 差 ${successResult.remainingSeats}（共 ${successResult.totalSeats} 车位）",
                            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PulseAccentPill(
                            text = "月均 ¥${successResult.perPersonMonthlyCny.toPlainString()}",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        PulseAccentPill(
                            text = "季均 ¥${successResult.perPersonQuarterlyCny.toPlainString()}",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        PulseAccentPill(
                            text = "年均 ¥${successResult.perPersonYearlyCny.toPlainString()}",
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        PulseAccentPill(
                            text = "日均 ¥${successResult.perPersonDailyCny.toPlainString()}",
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (outcome is CarpoolCalculationOutcome.Invalid) {
                Text(
                    text = outcome.errors.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Inputs Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pulseCard(
                        shape = PulseArchitecturalShapes.EditorialCard,
                        containerColor = ext.cardSurface,
                        elevation = PulseElevation.None,
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PulseTextField(
                    value = serviceName,
                    onValueChange = { if (it.length <= 80) serviceName = it },
                    label = "拼车项目名称",
                    placeholder = "例如：Netflix 4K 土区 / DMIT Pro 合租",
                    singleLine = true,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Seat Steppers
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SeatStepperBox(
                        label = "总车位",
                        value = totalSeats,
                        onDecrement = {
                            if (totalSeats > 2) {
                                totalSeats--
                                if (occupiedSeats > totalSeats) occupiedSeats = totalSeats
                            }
                        },
                        onIncrement = {
                            if (totalSeats < 32) totalSeats++
                        },
                        modifier = Modifier.weight(1f),
                    )
                    SeatStepperBox(
                        label = "已上车（含车主）",
                        value = occupiedSeats,
                        onDecrement = {
                            if (occupiedSeats > 1) occupiedSeats--
                        },
                        onIncrement = {
                            if (occupiedSeats < totalSeats) occupiedSeats++
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                // Cost & Currency
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PulseTextField(
                        value = totalCostText,
                        onValueChange = { totalCostText = sanitizeDecimal(it) },
                        label = "整车周期总价 (${currency.code})",
                        placeholder = "240",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.weight(1f),
                    )
                    if (currency != ValuationCurrency.CNY) {
                        PulseTextField(
                            value = fxRateText,
                            onValueChange = { fxRateText = sanitizeDecimal(it) },
                            label = "汇率 (1=${currency.code}->CNY)",
                            placeholder = currency.defaultCnyRate.toPlainString(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        ValuationCurrency.CNY,
                        ValuationCurrency.USD,
                        ValuationCurrency.EUR,
                        ValuationCurrency.HKD,
                        ValuationCurrency.TRY,
                    ).forEach { cur ->
                        FilterChip(
                            selected = currency == cur,
                            onClick = {
                                currencyName = cur.name
                                fxRateText = cur.defaultCnyRate.toPlainString()
                            },
                            label = {
                                Text(
                                    text = "${cur.code} ${cur.symbol}",
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }

                // Billing Cycle
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CarpoolBillingCycle.entries.forEach { cycle ->
                        FilterChip(
                            selected = billingCycle == cycle,
                            onClick = { cycleName = cycle.name },
                            label = {
                                Text(
                                    text = cycle.label,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }

                if (billingCycle == CarpoolBillingCycle.CUSTOM_DAYS) {
                    PulseTextField(
                        value = customDaysText,
                        onValueChange = { customDaysText = it.filter(Char::isDigit).take(4) },
                        label = "自定义周期天数",
                        placeholder = "90",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CarpoolSeatSplitMode.entries.forEach { mode ->
                        FilterChip(
                            selected = splitMode == mode,
                            onClick = { splitModeName = mode.name },
                            label = {
                                Text(
                                    text = mode.label,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }

            // Refund Simulator Card
            if (refundPreview != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulseCard(
                            shape = PulseArchitecturalShapes.EditorialCard,
                            containerColor = ext.cardSurface,
                            elevation = PulseElevation.None,
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "翻车 / 跳车按天退费公式",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PulseTextField(
                            value = simulatedUsedDaysText,
                            onValueChange = { simulatedUsedDaysText = it.filter(Char::isDigit).take(4) },
                            label = "模拟已用天数",
                            placeholder = "30",
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.weight(1f),
                        )
                        PulseTextField(
                            value = jumpPenaltyText,
                            onValueChange = { jumpPenaltyText = sanitizeDecimal(it) },
                            label = "跳车折损 %",
                            placeholder = "10",
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = "翻车退费（0%折损）：${refundPreview.crashFormulaText}",
                        style = MaterialTheme.typography.bodySmall.withTabularNumbers(),
                        color = carpoolSpec.spineColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "跳车退费（折损${refundPreview.penaltyRatePercent.stripTrailingZeros().toPlainString()}%）：${refundPreview.jumpFormulaText}",
                        style = MaterialTheme.typography.bodySmall.withTabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Markdown Preview Toggle
            if (generatedMarkdown.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showMarkdownPreview = !showMarkdownPreview }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (showMarkdownPreview) "收起生成的车位 Markdown" else "预览生成的车位 Markdown",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Icon(
                        imageVector = if (showMarkdownPreview) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                AnimatedVisibility(visible = showMarkdownPreview) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(10.dp),
                    ) {
                        Text(
                            text = generatedMarkdown,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { updateTitleWithTag = !updateTitleWithTag },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = updateTitleWithTag,
                    onCheckedChange = { updateTitleWithTag = it },
                )
                Text(
                    text = "同步在标题添加/更新 [${CarpoolStatusUpdater.formatStatusTag(occupiedSeats, totalSeats)}] 车位标记",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        if (generatedMarkdown.isNotEmpty()) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Anode Carpool Table", generatedMarkdown))
                            onCopiedFeedback?.invoke("已复制拼车车位表格")
                        }
                    },
                    enabled = generatedMarkdown.isNotEmpty(),
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
                        text = "仅复制表格",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Button(
                    onClick = {
                        val tag = if (updateTitleWithTag) {
                            CarpoolStatusUpdater.formatStatusTag(occupiedSeats, totalSeats)
                        } else {
                            null
                        }
                        onInsertCarpoolMarkdown(generatedMarkdown, tag)
                    },
                    enabled = generatedMarkdown.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = carpoolSpec.spineColor,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 11.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "插入发帖正文",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SeatStepperBox(
    label: String,
    value: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onDecrement, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Rounded.Remove, contentDescription = "减少", modifier = Modifier.size(16.dp))
            }
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleSmall.withTabularNumbers(),
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = onIncrement, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Rounded.Add, contentDescription = "增加", modifier = Modifier.size(16.dp))
            }
        }
    }
}

private fun sanitizeDecimal(input: String): String {
    var dotSeen = false
    return buildString {
        for (ch in input) {
            if (ch.isDigit()) {
                append(ch)
            } else if (ch == '.' && !dotSeen) {
                dotSeen = true
                append(ch)
            }
        }
    }.take(12)
}
