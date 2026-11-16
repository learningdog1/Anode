@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nodeseek.app.ui.valuation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.DealSpreadStatus
import com.nodeseek.app.domain.valuation.FeePayer
import com.nodeseek.app.domain.valuation.FxRateSource
import com.nodeseek.app.domain.valuation.MiddlemanFeeMode
import com.nodeseek.app.domain.valuation.NaturalCycleSpan
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.domain.valuation.ValuationMarkdownStyle
import com.nodeseek.app.domain.valuation.ValuationMode
import com.nodeseek.app.domain.valuation.VpsValuationEngine
import com.nodeseek.app.domain.valuation.VpsValuationInput
import com.nodeseek.app.domain.valuation.VpsValuationOutcome
import com.nodeseek.app.ui.components.MarkdownRenderer
import com.nodeseek.app.ui.components.PulseAlertDialog
import com.nodeseek.app.ui.components.PulseDialogPrimaryButton
import com.nodeseek.app.ui.components.PulseDialogSecondaryButton
import com.nodeseek.app.ui.components.SortSegmentItem
import com.nodeseek.app.ui.components.SortSegmentedSwitcher
import com.nodeseek.app.ui.theme.NodePulseMotion
import com.nodeseek.app.ui.theme.NodePulseThemeTokens
import com.nodeseek.app.ui.theme.PulseAccentPill
import com.nodeseek.app.ui.theme.PulseArchitecturalShapes
import com.nodeseek.app.ui.theme.PulseBrushes
import com.nodeseek.app.ui.theme.PulseElevation
import com.nodeseek.app.ui.theme.PulseGradientDivider
import com.nodeseek.app.ui.theme.PulseSectionHeader
import com.nodeseek.app.ui.theme.pulseBouncyClickable
import com.nodeseek.app.ui.theme.pulseCard
import com.nodeseek.app.ui.theme.pulseHeroCard
import com.nodeseek.app.ui.theme.withTabularNumbers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ISO_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private val HH_MM_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

private enum class DatePickerTarget(val title: String) {
    VALUATION("选择估值日期"),
    EXPIRY("选择到期日期"),
    PREV_RENEWAL("选择上期续费日期"),
}

private enum class TimePickerTarget(val title: String) {
    VALUATION("选择估值时刻"),
    EXPIRY("选择到期时刻"),
}

private enum class CurrencyPickerTarget(val title: String) {
    RENEWAL("选择续费币种"),
    PUSH("选择 Push 费币种"),
}

/**
 * Inserts [markdownBlock] cleanly at the current cursor/selection of [value], adding blank-line
 * separation when adjacent text is non-empty, and places the cursor immediately after the block.
 */
internal fun insertMarkdownBlockAtCursor(
    value: TextFieldValue,
    markdownBlock: String,
): TextFieldValue {
    val cleanBlock = markdownBlock.trim()
    if (cleanBlock.isEmpty()) return value

    val text = value.text
    val start = value.selection.min.coerceIn(0, text.length)
    val end = value.selection.max.coerceIn(0, text.length)
    val before = text.substring(0, start)
    val after = text.substring(end)

    val leading = when {
        before.isEmpty() -> ""
        before.endsWith("\n\n") -> ""
        before.endsWith("\n") -> "\n"
        else -> "\n\n"
    }
    val trailing = when {
        after.isEmpty() -> ""
        after.startsWith("\n\n") -> ""
        after.startsWith("\n") -> "\n"
        else -> "\n\n"
    }

    val nextText = before + leading + cleanBlock + trailing + after
    val cursorAfterBlock = start + leading.length + cleanBlock.length
    return TextFieldValue(
        text = nextText,
        selection = TextRange(cursorAfterBlock),
    )
}

/**
 * NodePulse VPS Remaining Value & Second-Hand Transfer Valuation Bottom Sheet.
 *
 * Features:
 * - Sticky live telemetry hero card with animated remaining progress bar & semantic spread badge
 * - All 13 currencies backed by [ExchangeRateRepository] (24h Frankfurter sync + offline defaults + manual override)
 * - Fixed-day & leap-year-aware natural calendar cycle presets, plus `Asia/Shanghai` date/minute precision
 * - Transfer deal breakdown with Push/middleman fee splits (`买家/卖家/平摊`)
 * - Dual Markdown templates with live [MarkdownRenderer] preview & direct editor insertion
 */
@Composable
fun VpsValuationBottomSheet(
    primaryActionLabel: String,
    onInsertMarkdown: (String) -> Unit,
    onDismiss: () -> Unit,
    initialMachineLabel: String = "",
    initialMarkdownStyle: ValuationMarkdownStyle = ValuationMarkdownStyle.SUMMARY_CARD,
    onCopiedFeedback: ((String) -> Unit)? = null,
    initialMode: ValuationMode = ValuationMode.NET_VALUE_ONLY,
    initialCurrency: ValuationCurrency = ValuationCurrency.USD,
    initialRenewalAmountText: String = "10.00",
    initialAskingPriceText: String = "50.00",
    initialPushFeeText: String = "0",
    initialMiddlemanEnabled: Boolean = false,
) {
    val context = LocalContext.current
    val isInspection = LocalInspectionMode.current
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val rateRepo = remember(context, isInspection) {
        if (isInspection) {
            ExchangeRateRepository(
                cacheFile = File(System.getProperty("java.io.tmpdir", "/tmp"), "anode_preview_fx.json"),
            )
        } else {
            ExchangeRateRepository.forContext(context)
        }
    }
    val fxState by rateRepo.state.collectAsStateWithLifecycle()

    if (!isInspection) {
        LaunchedEffect(rateRepo) {
            rateRepo.ensureFreshRates(force = false)
        }
    }

    val initialNow = remember { VpsValuationEngine.defaultValuationDateTime(preciseToMinute = true) }
    val initialTodayStr = remember(initialNow) { ISO_DATE_FORMATTER.format(initialNow.toLocalDate()) }
    val initialNowTimeStr = remember(initialNow) { HH_MM_FORMATTER.format(initialNow.toLocalTime()) }
    val initialExpiryStr = remember(initialNow) {
        ISO_DATE_FORMATTER.format(initialNow.toLocalDate().plusDays(180))
    }

    var modeName by rememberSaveable(initialMode) { mutableStateOf(initialMode.name) }
    val mode = remember(modeName) { ValuationMode.valueOf(modeName) }

    var machineLabel by rememberSaveable(initialMachineLabel) {
        mutableStateOf(initialMachineLabel.take(120))
    }
    var currencyName by rememberSaveable(initialCurrency) { mutableStateOf(initialCurrency.name) }
    val currency = remember(currencyName) { ValuationCurrency.valueOf(currencyName) }

    var renewalAmountText by rememberSaveable(initialRenewalAmountText) { mutableStateOf(initialRenewalAmountText) }

    var cyclePresetName by rememberSaveable { mutableStateOf(BillingCyclePreset.ANNUAL.name) }
    val cyclePreset = remember(cyclePresetName) { BillingCyclePreset.valueOf(cyclePresetName) }

    var naturalSpanName by rememberSaveable { mutableStateOf(NaturalCycleSpan.YEAR.name) }
    val naturalSpan = remember(naturalSpanName) { NaturalCycleSpan.valueOf(naturalSpanName) }

    var customDaysText by rememberSaveable { mutableStateOf("365") }
    var useCustomPrevRenewal by rememberSaveable { mutableStateOf(false) }
    var prevRenewalDateText by rememberSaveable {
        mutableStateOf(ISO_DATE_FORMATTER.format(initialNow.toLocalDate().plusDays(180).minusYears(1)))
    }

    var preciseToMinute by rememberSaveable { mutableStateOf(false) }
    var valuationDateText by rememberSaveable { mutableStateOf(initialTodayStr) }
    var valuationTimeText by rememberSaveable { mutableStateOf(initialNowTimeStr) }
    var expiryDateText by rememberSaveable { mutableStateOf(initialExpiryStr) }
    var expiryTimeText by rememberSaveable { mutableStateOf("00:00") }

    // Transfer Deal state
    var askingPriceText by rememberSaveable(initialAskingPriceText) { mutableStateOf(initialAskingPriceText) }
    var pushFeeText by rememberSaveable(initialPushFeeText) { mutableStateOf(initialPushFeeText) }
    var pushCurrencyName by rememberSaveable { mutableStateOf(ValuationCurrency.CNY.name) }
    val pushCurrency = remember(pushCurrencyName) { ValuationCurrency.valueOf(pushCurrencyName) }
    var pushPayerName by rememberSaveable { mutableStateOf(FeePayer.BUYER.name) }
    val pushPayer = remember(pushPayerName) { FeePayer.valueOf(pushPayerName) }

    var middlemanEnabled by rememberSaveable(initialMiddlemanEnabled) { mutableStateOf(initialMiddlemanEnabled) }
    var middlemanModeName by rememberSaveable { mutableStateOf(MiddlemanFeeMode.PERCENT.name) }
    val middlemanMode = remember(middlemanModeName) { MiddlemanFeeMode.valueOf(middlemanModeName) }
    var middlemanValueText by rememberSaveable { mutableStateOf("5") }
    var middlemanPayerName by rememberSaveable { mutableStateOf(FeePayer.BUYER.name) }
    val middlemanPayer = remember(middlemanPayerName) { FeePayer.valueOf(middlemanPayerName) }

    // Output style & preview state
    var markdownStyleName by rememberSaveable { mutableStateOf(initialMarkdownStyle.name) }
    val markdownStyle = remember(markdownStyleName) { ValuationMarkdownStyle.valueOf(markdownStyleName) }
    var showRawMarkdown by rememberSaveable { mutableStateOf(false) }
    var copiedBadgeVisible by remember { mutableStateOf(false) }

    // Picker & dialog states
    var datePickerTarget by remember { mutableStateOf<DatePickerTarget?>(null) }
    var timePickerTarget by remember { mutableStateOf<TimePickerTarget?>(null) }
    var currencyPickerTarget by remember { mutableStateOf<CurrencyPickerTarget?>(null) }
    var fxEditCurrency by remember { mutableStateOf<ValuationCurrency?>(null) }

    LaunchedEffect(copiedBadgeVisible) {
        if (copiedBadgeVisible) {
            delay(1800)
            copiedBadgeVisible = false
        }
    }

    val activeQuote = fxState.quoteFor(currency)
    val pushQuote = fxState.quoteFor(pushCurrency)

    val outcome = remember(
        mode,
        machineLabel,
        currency,
        activeQuote.rateToCny,
        renewalAmountText,
        cyclePreset,
        naturalSpan,
        customDaysText,
        useCustomPrevRenewal,
        prevRenewalDateText,
        preciseToMinute,
        valuationDateText,
        valuationTimeText,
        expiryDateText,
        expiryTimeText,
        askingPriceText,
        pushFeeText,
        pushCurrency,
        pushQuote.rateToCny,
        pushPayer,
        middlemanEnabled,
        middlemanMode,
        middlemanValueText,
        middlemanPayer,
    ) {
        evaluateValuationForm(
            mode = mode,
            machineLabel = machineLabel,
            currency = currency,
            fxRateToCny = activeQuote.rateToCny,
            renewalAmountText = renewalAmountText,
            cyclePreset = cyclePreset,
            naturalSpan = naturalSpan,
            customDaysText = customDaysText,
            useCustomPrevRenewal = useCustomPrevRenewal,
            prevRenewalDateText = prevRenewalDateText,
            preciseToMinute = preciseToMinute,
            valuationDateText = valuationDateText,
            valuationTimeText = valuationTimeText,
            expiryDateText = expiryDateText,
            expiryTimeText = expiryTimeText,
            askingPriceText = askingPriceText,
            pushFeeText = pushFeeText,
            pushCurrency = pushCurrency,
            pushFeeToCnyRate = pushQuote.rateToCny,
            pushPayer = pushPayer,
            middlemanEnabled = middlemanEnabled,
            middlemanMode = middlemanMode,
            middlemanValueText = middlemanValueText,
            middlemanPayer = middlemanPayer,
        )
    }

    val fxSourceNote = remember(activeQuote, fxState.providerDate) {
        buildFxSourceFootnote(activeQuote.source, fxState.providerDate)
    }

    val generatedMarkdown = remember(outcome, markdownStyle, fxSourceNote) {
        when (outcome) {
            is VpsValuationOutcome.Success -> VpsValuationEngine.formatMarkdown(
                result = outcome.result,
                style = markdownStyle,
                fxSourceNote = fxSourceNote,
            )
            is VpsValuationOutcome.Invalid -> ""
        }
    }

    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.90f).dp
    val ext = NodePulseThemeTokens.extendedColors

    val sheetContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── 1. Sheet Header ───────────────────────────────────────────────
            ValuationSheetHeader(
                fxState = fxState,
                onRefreshRates = {
                    coroutineScope.launch { rateRepo.refreshOnline() }
                },
                onClose = onDismiss,
            )

            // ── 2. Sticky Live Telemetry Hero Card ────────────────────────────
            StickyValuationTelemetryCard(
                outcome = outcome,
                mode = mode,
            )

            // ── 3. Scrollable Configuration Form & Preview ────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Mode Segmented Switcher
                SortSegmentedSwitcher(
                    options = remember {
                        ValuationMode.entries.map { SortSegmentItem(it, it.label) }
                    },
                    selectedValue = mode,
                    onSelect = { modeName = it.name },
                    modifier = Modifier.fillMaxWidth(),
                )

                // Plan & Renewal + FX Card
                ValuationFormSectionCard(
                    title = "套餐与续费参数",
                    icon = Icons.Rounded.CurrencyExchange,
                ) {
                    OutlinedTextField(
                        value = machineLabel,
                        onValueChange = { if (it.length <= 120) machineLabel = it },
                        label = { Text("套餐 / 机型名称（选填）") },
                        placeholder = { Text("例如：RackNerd 2C2G DC02") },
                        singleLine = true,
                        shape = PulseArchitecturalShapes.MediumBadge,
                        colors = valuationTextFieldColors(ext.cardSurface, ext.cardBorder),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        CurrencyDropdownChip(
                            currency = currency,
                            onClick = { currencyPickerTarget = CurrencyPickerTarget.RENEWAL },
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        OutlinedTextField(
                            value = renewalAmountText,
                            onValueChange = { renewalAmountText = sanitizeDecimalInput(it) },
                            label = { Text("续费金额 (${currency.code})") },
                            placeholder = { Text("10.00") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            shape = PulseArchitecturalShapes.MediumBadge,
                            colors = valuationTextFieldColors(ext.cardSurface, ext.cardBorder),
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // FX Rate Provenance & Quick Override Strip
                    if (currency != ValuationCurrency.CNY) {
                        FxRateStrip(
                            currency = currency,
                            rateToCny = activeQuote.rateToCny,
                            source = activeQuote.source,
                            hasManualOverride = activeQuote.hasManualOverride,
                            providerDate = fxState.providerDate,
                            lastSyncError = fxState.lastSyncError,
                            onEditRate = { fxEditCurrency = currency },
                            onResetOverride = {
                                coroutineScope.launch { rateRepo.clearManualOverride(currency) }
                            },
                        )
                    }
                }

                // Billing Cycle Card
                ValuationFormSectionCard(
                    title = "计费周期与日历折算",
                    icon = Icons.Rounded.CalendarMonth,
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        BillingCyclePreset.entries.forEach { preset ->
                            ValuationChoiceChip(
                                selected = cyclePreset == preset,
                                label = preset.label,
                                onClick = { cyclePresetName = preset.name },
                            )
                        }
                    }

                    AnimatedVisibility(visible = cyclePreset == BillingCyclePreset.CUSTOM) {
                        OutlinedTextField(
                            value = customDaysText,
                            onValueChange = { customDaysText = it.filter(Char::isDigit).take(4) },
                            label = { Text("自定义周期天数 (1 ~ 3660 天)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = PulseArchitecturalShapes.MediumBadge,
                            colors = valuationTextFieldColors(ext.cardSurface, ext.cardBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                        )
                    }

                    AnimatedVisibility(visible = cyclePreset == BillingCyclePreset.CALENDAR_NATURAL) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                NaturalCycleSpan.entries.forEach { span ->
                                    ValuationChoiceChip(
                                        selected = !useCustomPrevRenewal && naturalSpan == span,
                                        label = span.label,
                                        onClick = {
                                            useCustomPrevRenewal = false
                                            naturalSpanName = span.name
                                        },
                                    )
                                }
                                ValuationChoiceChip(
                                    selected = useCustomPrevRenewal,
                                    label = "指定上期续费日",
                                    onClick = {
                                        if (!useCustomPrevRenewal) {
                                            val exp = parseIsoDate(expiryDateText)
                                                ?: VpsValuationEngine.todayInShanghai().plusDays(180)
                                            prevRenewalDateText = ISO_DATE_FORMATTER.format(
                                                exp.minusMonths(naturalSpan.months.toLong())
                                            )
                                        }
                                        useCustomPrevRenewal = !useCustomPrevRenewal
                                    },
                                )
                            }

                            if (useCustomPrevRenewal) {
                                DateInputRow(
                                    label = "上期续费日期 (YYYY-MM-DD)",
                                    dateText = prevRenewalDateText,
                                    onDateTextChange = { prevRenewalDateText = it },
                                    showTimeField = false,
                                    timeText = "",
                                    onTimeTextChange = {},
                                    onOpenDatePicker = { datePickerTarget = DatePickerTarget.PREV_RENEWAL },
                                    onOpenTimePicker = {},
                                )
                            }

                            if (outcome is VpsValuationOutcome.Success) {
                                val res = outcome.result
                                val startStr = VpsValuationEngine.formatDateTime(
                                    res.resolvedCycleStartDateTime,
                                    res.preciseToMinute,
                                )
                                val endStr = VpsValuationEngine.formatDateTime(
                                    res.expiryDateTime,
                                    res.preciseToMinute,
                                )
                                val daysStr = VpsValuationEngine.formatDaysNumber(res.effectiveCycleDays)
                                val leapHint = if (res.effectiveCycleDays.toInt() == 366) " · 含闰年 366 天" else ""
                                Text(
                                    text = "日历区间：$startStr → $endStr（共 $daysStr 天$leapHint）",
                                    style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                // Dates & Precision Card
                ValuationFormSectionCard(
                    title = "估值基准与到期时间",
                    icon = Icons.Rounded.Schedule,
                    trailing = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "精确到时分",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Switch(
                                checked = preciseToMinute,
                                onCheckedChange = { enabled ->
                                    preciseToMinute = enabled
                                    if (enabled && valuationTimeText.isBlank()) {
                                        valuationTimeText = HH_MM_FORMATTER.format(
                                            VpsValuationEngine.defaultValuationDateTime(preciseToMinute = true)
                                                .toLocalTime()
                                        )
                                    }
                                },
                            )
                        }
                    },
                ) {
                    DateInputRow(
                        label = "估值基准日期",
                        dateText = valuationDateText,
                        onDateTextChange = { valuationDateText = it },
                        showTimeField = preciseToMinute,
                        timeText = valuationTimeText,
                        onTimeTextChange = { valuationTimeText = it },
                        onOpenDatePicker = { datePickerTarget = DatePickerTarget.VALUATION },
                        onOpenTimePicker = { timePickerTarget = TimePickerTarget.VALUATION },
                    )

                    DateInputRow(
                        label = "服务到期日期",
                        dateText = expiryDateText,
                        onDateTextChange = { expiryDateText = it },
                        showTimeField = preciseToMinute,
                        timeText = expiryTimeText,
                        onTimeTextChange = { expiryTimeText = it },
                        onOpenDatePicker = { datePickerTarget = DatePickerTarget.EXPIRY },
                        onOpenTimePicker = { timePickerTarget = TimePickerTarget.EXPIRY },
                    )

                    // Quick Date Jump Pills
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ValuationQuickFillChip(
                            label = "今天基准",
                            onClick = {
                                val nowSh = VpsValuationEngine.defaultValuationDateTime(preciseToMinute = true)
                                valuationDateText = ISO_DATE_FORMATTER.format(nowSh.toLocalDate())
                                valuationTimeText = HH_MM_FORMATTER.format(nowSh.toLocalTime())
                            },
                        )
                        val baseDate = parseIsoDate(valuationDateText) ?: VpsValuationEngine.todayInShanghai()
                        listOf(
                            "+30天" to baseDate.plusDays(30),
                            "+90天" to baseDate.plusDays(90),
                            "+180天" to baseDate.plusDays(180),
                            "+1年" to baseDate.plusYears(1),
                        ).forEach { (chipLabel, targetDate) ->
                            ValuationQuickFillChip(
                                label = "到期 $chipLabel",
                                onClick = { expiryDateText = ISO_DATE_FORMATTER.format(targetDate) },
                            )
                        }
                    }
                }

                // Transfer Deal Section (Visible when mode == TRANSFER_DEAL)
                AnimatedVisibility(visible = mode == ValuationMode.TRANSFER_DEAL) {
                    ValuationFormSectionCard(
                        title = "过户定价与交易成本",
                        icon = Icons.Rounded.Edit,
                        accentColor = MaterialTheme.colorScheme.tertiary,
                    ) {
                        OutlinedTextField(
                            value = askingPriceText,
                            onValueChange = { askingPriceText = sanitizeDecimalInput(it) },
                            label = { Text("出让挂牌价 (CNY ¥)") },
                            placeholder = { Text("50.00") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            shape = PulseArchitecturalShapes.MediumBadge,
                            colors = valuationTextFieldColors(ext.cardSurface, ext.cardBorder),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        PulseGradientDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                        // Push / Transfer Fee
                        Text(
                            text = "Push / 过户费（选填）",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            CurrencyDropdownChip(
                                currency = pushCurrency,
                                onClick = { currencyPickerTarget = CurrencyPickerTarget.PUSH },
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            OutlinedTextField(
                                value = pushFeeText,
                                onValueChange = { pushFeeText = sanitizeDecimalInput(it) },
                                label = { Text("Push 费用 (${pushCurrency.code})") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                shape = PulseArchitecturalShapes.MediumBadge,
                                colors = valuationTextFieldColors(ext.cardSurface, ext.cardBorder),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pushCurrency != ValuationCurrency.CNY) {
                            Text(
                                text = "折算汇率：1 ${pushCurrency.code} = ¥${VpsValuationEngine.formatFxRate(pushQuote.rateToCny)}（点击可改）",
                                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(PulseArchitecturalShapes.SmallChip)
                                    .clickable { fxEditCurrency = pushCurrency }
                                    .padding(vertical = 2.dp, horizontal = 4.dp),
                            )
                        }
                        PayerSelectorRow(
                            selectedPayer = pushPayer,
                            onSelectPayer = { pushPayerName = it.name },
                        )

                        PulseGradientDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                        // Middleman Fee
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(
                                    text = "中介手续费",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "支持按出让价百分比或固定人民币分摊",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = middlemanEnabled,
                                onCheckedChange = { middlemanEnabled = it },
                            )
                        }

                        AnimatedVisibility(visible = middlemanEnabled) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    MiddlemanFeeMode.entries.forEach { feeMode ->
                                        ValuationChoiceChip(
                                            selected = middlemanMode == feeMode,
                                            label = feeMode.label,
                                            onClick = { middlemanModeName = feeMode.name },
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = middlemanValueText,
                                    onValueChange = { middlemanValueText = sanitizeDecimalInput(it) },
                                    label = {
                                        Text(
                                            if (middlemanMode == MiddlemanFeeMode.PERCENT) {
                                                "中介费率 (%)"
                                            } else {
                                                "固定中介费 (CNY ¥)"
                                            }
                                        )
                                    },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    shape = PulseArchitecturalShapes.MediumBadge,
                                    colors = valuationTextFieldColors(ext.cardSurface, ext.cardBorder),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                PayerSelectorRow(
                                    selectedPayer = middlemanPayer,
                                    onSelectPayer = { middlemanPayerName = it.name },
                                )
                            }
                        }
                    }
                }

                // Output Style Selector & Live Markdown Preview Card
                ValuationFormSectionCard(
                    title = "输出排版模板与预览",
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
                                text = if (showRawMarkdown) "渲染预览" else "查看源码",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                ) {
                    SortSegmentedSwitcher(
                        options = remember {
                            ValuationMarkdownStyle.entries.map { SortSegmentItem(it, it.label) }
                        },
                        selectedValue = markdownStyle,
                        onSelect = { markdownStyleName = it.name },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = markdownStyle.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        when {
                            outcome is VpsValuationOutcome.Invalid -> {
                                Text(
                                    text = outcome.errors.joinToString("；"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            showRawMarkdown -> {
                                SelectionContainer {
                                    Text(
                                        text = generatedMarkdown,
                                        style = NodePulseThemeTokens.extraTypography.codeBody,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            else -> {
                                MarkdownRenderer(
                                    markdown = generatedMarkdown,
                                    bodyStyle = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
            }

            // ── 4. Pinned Bottom Action Bar ───────────────────────────────────
            PulseGradientDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val isValid = outcome is VpsValuationOutcome.Success && generatedMarkdown.isNotEmpty()
                Row(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(
                            width = 0.9.dp,
                            color = ext.cardBorder,
                            shape = CircleShape,
                        )
                        .pulseBouncyClickable(
                            enabled = isValid,
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.Button,
                            onClick = {
                                if (isValid) {
                                    copyToClipboard(context, generatedMarkdown)
                                    copiedBadgeVisible = true
                                    onCopiedFeedback?.invoke("已复制估值结果到剪贴板")
                                }
                            },
                        )
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = if (copiedBadgeVisible) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        tint = if (isValid) {
                            if (copiedBadgeVisible) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        },
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (copiedBadgeVisible) "已复制" else "复制",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isValid) {
                            if (copiedBadgeVisible) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        },
                    )
                }

                val ctaBg = if (isValid) {
                    Modifier.background(PulseBrushes.brandGradient())
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .then(
                            if (isValid && !NodePulseThemeTokens.isDark) {
                                Modifier.shadow(
                                    elevation = PulseElevation.Card,
                                    shape = CircleShape,
                                    ambientColor = ext.softShadowColor,
                                    spotColor = ext.softShadowColor,
                                )
                            } else {
                                Modifier
                            },
                        )
                        .clip(CircleShape)
                        .then(ctaBg)
                        .pulseBouncyClickable(
                            enabled = isValid,
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.Button,
                            onClick = {
                                if (isValid) {
                                    onInsertMarkdown(generatedMarkdown)
                                }
                            },
                        )
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    val ctaTint = if (isValid) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    }
                    Icon(
                        imageVector = Icons.Rounded.Calculate,
                        contentDescription = null,
                        tint = ctaTint,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = primaryActionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = ctaTint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    if (LocalInspectionMode.current) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                shape = PulseArchitecturalShapes.BottomSheetTop,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BottomSheetDefaults.DragHandle()
                    sheetContent()
                }
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            shape = PulseArchitecturalShapes.BottomSheetTop,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            sheetContent()
        }
    }

    // ── Material 3 DatePickerDialog ───────────────────────────────────────────
    datePickerTarget?.let { target ->
        key(target) {
            val currentText = when (target) {
                DatePickerTarget.VALUATION -> valuationDateText
                DatePickerTarget.EXPIRY -> expiryDateText
                DatePickerTarget.PREV_RENEWAL -> prevRenewalDateText
            }
            val initialDate = parseIsoDate(currentText) ?: VpsValuationEngine.todayInShanghai()
            val initialMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)

            DatePickerDialog(
                onDismissRequest = { datePickerTarget = null },
                shape = PulseArchitecturalShapes.SheetContainer,
                tonalElevation = 0.dp,
                colors = DatePickerDefaults.colors(
                    containerColor = ext.cardSurfaceElevated,
                ),
                confirmButton = {
                    PulseDialogPrimaryButton(
                        text = "确定",
                        onClick = {
                            pickerState.selectedDateMillis?.let { millis ->
                                val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                                val formatted = ISO_DATE_FORMATTER.format(picked)
                                when (target) {
                                    DatePickerTarget.VALUATION -> valuationDateText = formatted
                                    DatePickerTarget.EXPIRY -> expiryDateText = formatted
                                    DatePickerTarget.PREV_RENEWAL -> prevRenewalDateText = formatted
                                }
                            }
                            datePickerTarget = null
                        },
                    )
                },
                dismissButton = {
                    PulseDialogSecondaryButton(
                        text = "取消",
                        onClick = { datePickerTarget = null },
                    )
                },
            ) {
                DatePicker(
                    state = pickerState,
                    colors = DatePickerDefaults.colors(
                        containerColor = ext.cardSurfaceElevated,
                    ),
                    title = {
                        Text(
                            text = target.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                        )
                    },
                )
            }
        }
    }

    // ── Material 3 TimePicker Dialog ──────────────────────────────────────────
    timePickerTarget?.let { target ->
        key(target) {
            val initialTime = parseTimeHm(
                when (target) {
                    TimePickerTarget.VALUATION -> valuationTimeText
                    TimePickerTarget.EXPIRY -> expiryTimeText
                }
            ) ?: LocalTime.MIDNIGHT
            val timePickerState = rememberTimePickerState(
                initialHour = initialTime.hour,
                initialMinute = initialTime.minute,
                is24Hour = true,
            )

            PulseAlertDialog(
                onDismissRequest = { timePickerTarget = null },
                icon = Icons.Rounded.Schedule,
                title = target.title,
                confirmText = "确定",
                onConfirm = {
                    val formatted = HH_MM_FORMATTER.format(
                        LocalTime.of(timePickerState.hour, timePickerState.minute)
                    )
                    when (target) {
                        TimePickerTarget.VALUATION -> valuationTimeText = formatted
                        TimePickerTarget.EXPIRY -> expiryTimeText = formatted
                    }
                    timePickerTarget = null
                },
                dismissText = "取消",
                onDismiss = { timePickerTarget = null },
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    TimePicker(state = timePickerState)
                }
            }
        }
    }

    // ── Currency Picker Dialog ────────────────────────────────────────────────
    currencyPickerTarget?.let { target ->
        val selected = when (target) {
            CurrencyPickerTarget.RENEWAL -> currency
            CurrencyPickerTarget.PUSH -> pushCurrency
        }
        CurrencyPickerDialog(
            title = target.title,
            selectedCurrency = selected,
            fxState = fxState,
            onSelect = { chosen ->
                when (target) {
                    CurrencyPickerTarget.RENEWAL -> currencyName = chosen.name
                    CurrencyPickerTarget.PUSH -> pushCurrencyName = chosen.name
                }
                currencyPickerTarget = null
            },
            onDismiss = { currencyPickerTarget = null },
        )
    }

    // ── FX Rate Manual Override Dialog ────────────────────────────────────────
    fxEditCurrency?.let { editCurr ->
        val quote = fxState.quoteFor(editCurr)
        FxOverrideDialog(
            currency = editCurr,
            currentRate = quote.rateToCny,
            onlineRate = quote.onlineRateToCny,
            hasManualOverride = quote.hasManualOverride,
            onSave = { newRate ->
                fxEditCurrency = null
                coroutineScope.launch {
                    rateRepo.setManualOverride(editCurr, newRate)
                }
            },
            onReset = {
                fxEditCurrency = null
                coroutineScope.launch {
                    rateRepo.clearManualOverride(editCurr)
                }
            },
            onDismiss = { fxEditCurrency = null },
        )
    }
}

// ── Header & Sticky Telemetry Card ────────────────────────────────────────────

@Composable
private fun ValuationSheetHeader(
    fxState: FxRatesState,
    onRefreshRates: () -> Unit,
    onClose: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(PulseArchitecturalShapes.MediumBadge)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(
                    width = 0.9.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                    shape = PulseArchitecturalShapes.MediumBadge,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Calculate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "VPS 估值折算",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val fxStatusLabel = when {
                fxState.isRefreshing -> "正在同步 Frankfurter 实时汇率…"
                !fxState.lastSyncError.isNullOrBlank() -> fxState.lastSyncError!!
                fxState.providerDate != null -> "汇率基准 ${fxState.providerDate} · 24h 本地缓存"
                else -> "当前使用内置离线参考汇率"
            }
            Text(
                text = fxStatusLabel,
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(0.8.dp, ext.cardBorder, CircleShape)
                .pulseBouncyClickable(
                    enabled = !fxState.isRefreshing,
                    pressedScale = NodePulseMotion.chipPressScale,
                    role = Role.Button,
                    onClickLabel = "同步最新汇率",
                    onClick = onRefreshRates,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (fxState.isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Sync,
                    contentDescription = "刷新汇率",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(0.8.dp, ext.cardBorder, CircleShape)
                .pulseBouncyClickable(
                    pressedScale = NodePulseMotion.chipPressScale,
                    role = Role.Button,
                    onClickLabel = "关闭估值计算器",
                    onClick = onClose,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "关闭",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
private fun StickyValuationTelemetryCard(
    outcome: VpsValuationOutcome,
    mode: ValuationMode,
) {
    val result = (outcome as? VpsValuationOutcome.Success)?.result
    val spreadStatus = result?.transferBreakdown?.spreadStatus
    val telemetryAccent by animateColorAsState(
        targetValue = when {
            outcome is VpsValuationOutcome.Invalid -> MaterialTheme.colorScheme.error
            result?.isExpired == true -> MaterialTheme.colorScheme.error
            mode == ValuationMode.TRANSFER_DEAL && spreadStatus == DealSpreadStatus.DISCOUNT ->
                MaterialTheme.colorScheme.secondary
            mode == ValuationMode.TRANSFER_DEAL && spreadStatus == DealSpreadStatus.PREMIUM ->
                MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "telemetryAccent",
    )

    val animatedProgress by animateFloatAsState(
        targetValue = result?.remainingRatio ?: 0f,
        animationSpec = NodePulseMotion.spatialFloatSpring(),
        label = "remainingProgress",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseHeroCard(
                accentColor = telemetryAccent,
                shape = PulseArchitecturalShapes.HeroBento,
                elevation = PulseElevation.Card,
            )
            .animateContentSize()
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (outcome is VpsValuationOutcome.Invalid) {
            Text(
                text = "待完善输入项",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = outcome.errors.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (result != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "净剩余价值 (CNY)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "¥${result.remainingValueCny.toPlainString()}",
                        style = NodePulseThemeTokens.extraTypography.metricValue.copy(
                            fontSize = 25.sp,
                            lineHeight = 30.sp,
                            fontWeight = FontWeight.ExtraBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    val origPart = if (result.currency != ValuationCurrency.CNY) {
                        "原币 ${result.remainingValueOriginal.toPlainString()} ${result.currency.code} · "
                    } else {
                        ""
                    }
                    val dailyPart = if (result.dailyOfficialCostCny > BigDecimal.ZERO) {
                        "官方日均 ¥${result.dailyOfficialCostCny.toPlainString()}"
                    } else {
                        "官方日均 ¥${result.dailyOfficialCostHighPrecisionCny.stripTrailingZeros().toPlainString()}"
                    }
                    Text(
                        text = "$origPart$dailyPart",
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Semantic Spread / Status Pill
                val deal = result.transferBreakdown
                if (deal != null) {
                    val (pillBg, pillFg) = when (deal.spreadStatus) {
                        DealSpreadStatus.DISCOUNT ->
                            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                        DealSpreadStatus.PAR ->
                            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                        DealSpreadStatus.PREMIUM ->
                            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                    }
                    val badgeText = when (deal.spreadStatus) {
                        DealSpreadStatus.PAR -> "平出 0.00%"
                        else -> {
                            val amt = VpsValuationEngine.formatSignedCny(deal.buyerPremiumCny)
                            val pct = deal.buyerPremiumPercent?.let {
                                " (${VpsValuationEngine.formatSignedPercent(it)})"
                            }.orEmpty()
                            "${deal.spreadStatus.label} $amt$pct"
                        }
                    }
                    PulseAccentPill(
                        text = badgeText,
                        containerColor = pillBg,
                        contentColor = pillFg,
                    )
                } else {
                    val daysPillText = if (result.isExpired) {
                        "已到期"
                    } else {
                        "余 ${VpsValuationEngine.formatDaysNumber(result.remainingDays)} 天"
                    }
                    PulseAccentPill(
                        text = daysPillText,
                        containerColor = if (result.isExpired) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        contentColor = if (result.isExpired) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                    )
                }
            }

            // Remaining Cycle Progress Track
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedProgress.coerceIn(0f, 1f))
                            .height(7.dp)
                            .clip(CircleShape)
                            .background(telemetryAccent),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val remStr = VpsValuationEngine.formatDaysNumber(result.remainingDays)
                    val cycStr = VpsValuationEngine.formatDaysNumber(result.effectiveCycleDays)
                    val overCycleHint = if (result.remainingDays > result.effectiveCycleDays) " (含提前续费)" else ""
                    Text(
                        text = if (result.isExpired) "当前周期已结束" else "剩余 $remStr / $cycStr 天$overCycleHint",
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val pctText = String.format(Locale.US, "%.1f%%", result.remainingRatio * 100f)
                    Text(
                        text = pctText,
                        style = NodePulseThemeTokens.extraTypography.tabularBadge,
                        color = telemetryAccent,
                    )
                }
            }

            // Transfer Summary Strip
            result.transferBreakdown?.let { deal ->
                PulseGradientDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TelemetryMiniKpi(
                        label = "买家实付",
                        value = "¥${deal.buyerTotalCostCny.toPlainString()}",
                        modifier = Modifier.weight(1f),
                    )
                    TelemetryMiniKpi(
                        label = "卖家净收",
                        value = "¥${deal.sellerNetProceedsCny.toPlainString()}",
                        modifier = Modifier.weight(1f),
                    )
                    TelemetryMiniKpi(
                        label = "持仓日均",
                        value = deal.buyerEffectiveDailyCostCny?.let { "¥${it.toPlainString()}/天" } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryMiniKpi(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = label,
            style = NodePulseThemeTokens.extraTypography.microLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge.withTabularNumbers(),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Reusable Form Cards & Controls ────────────────────────────────────────────

@Composable
private fun ValuationFormSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pulseCard(
                shape = PulseArchitecturalShapes.EditorialCard,
                elevation = PulseElevation.Subtle,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PulseSectionHeader(
            title = title,
            icon = icon,
            accentColor = accentColor,
            trailing = if (trailing != null) {
                { trailing() }
            } else {
                null
            },
        )
        content()
    }
}

@Composable
private fun CurrencyDropdownChip(
    currency: ValuationCurrency,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    Row(
        modifier = modifier
            .height(50.dp)
            .clip(PulseArchitecturalShapes.MediumBadge)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f))
            .border(
                width = 0.9.dp,
                color = ext.cardBorder,
                shape = PulseArchitecturalShapes.MediumBadge,
            )
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Button,
                onClickLabel = "切换币种",
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "${currency.code} ${currency.symbol}",
            style = MaterialTheme.typography.labelLarge.withTabularNumbers(),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = "选择币种",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun FxRateStrip(
    currency: ValuationCurrency,
    rateToCny: BigDecimal,
    source: FxRateSource,
    hasManualOverride: Boolean,
    providerDate: String?,
    lastSyncError: String?,
    onEditRate: () -> Unit,
    onResetOverride: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val rateFormatted = VpsValuationEngine.formatFxRate(rateToCny)
    val hundredHint = if (currency == ValuationCurrency.JPY ||
        currency == ValuationCurrency.KRW ||
        currency == ValuationCurrency.RUB
    ) {
        val per100 = rateToCny.multiply(BigDecimal("100")).setScale(2, java.math.RoundingMode.HALF_UP)
        " · 100 ${currency.code} ≈ ¥${per100.toPlainString()}"
    } else {
        ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PulseArchitecturalShapes.MediumBadge)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f))
            .border(0.8.dp, ext.cardBorder, PulseArchitecturalShapes.MediumBadge)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "1 ${currency.code} = ¥$rateFormatted CNY$hundredHint",
                    style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val dateNote = if (source == FxRateSource.ONLINE_FRESH || source == FxRateSource.ONLINE_CACHED) {
                    providerDate?.let { " ($it)" }.orEmpty()
                } else {
                    ""
                }
                Text(
                    text = "来源：${source.label}$dateNote",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hasManualOverride) {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .border(0.8.dp, ext.cardBorder, CircleShape)
                            .pulseBouncyClickable(
                                pressedScale = NodePulseMotion.chipPressScale,
                                role = Role.Button,
                                onClick = onResetOverride,
                            )
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RestartAlt,
                            contentDescription = "恢复自动汇率",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = "还原",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f))
                        .pulseBouncyClickable(
                            pressedScale = NodePulseMotion.chipPressScale,
                            role = Role.Button,
                            onClick = onEditRate,
                        )
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "调整汇率",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = "校准",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        if (!lastSyncError.isNullOrBlank()) {
            Text(
                text = lastSyncError,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun DateInputRow(
    label: String,
    dateText: String,
    onDateTextChange: (String) -> Unit,
    showTimeField: Boolean,
    timeText: String,
    onTimeTextChange: (String) -> Unit,
    onOpenDatePicker: () -> Unit,
    onOpenTimePicker: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val dateValid = remember(dateText) { parseIsoDate(dateText) != null }
    val timeValid = remember(timeText, showTimeField) { !showTimeField || parseTimeHm(timeText) != null }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = dateText,
                onValueChange = { raw ->
                    val filtered = raw.filter { it.isDigit() || it == '-' }.take(10)
                    onDateTextChange(filtered)
                },
                label = { Text(label) },
                placeholder = { Text("YYYY-MM-DD") },
                isError = !dateValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                trailingIcon = {
                    IconButton(
                        onClick = onOpenDatePicker,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CalendarMonth,
                            contentDescription = "打开日历选择器",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                },
                shape = PulseArchitecturalShapes.MediumBadge,
                colors = valuationTextFieldColors(ext.cardSurface, ext.cardBorder),
                modifier = Modifier.weight(1.45f),
            )

            if (showTimeField) {
                OutlinedTextField(
                    value = timeText,
                    onValueChange = { raw ->
                        val filtered = raw.filter { it.isDigit() || it == ':' }.take(5)
                        onTimeTextChange(filtered)
                    },
                    label = { Text("时刻") },
                    placeholder = { Text("HH:mm") },
                    isError = !timeValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    trailingIcon = {
                        IconButton(
                            onClick = onOpenTimePicker,
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Schedule,
                                contentDescription = "选择时刻",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    shape = PulseArchitecturalShapes.MediumBadge,
                    colors = valuationTextFieldColors(ext.cardSurface, ext.cardBorder),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (!dateValid || !timeValid) {
            Text(
                text = buildString {
                    if (!dateValid) append("日期格式应为 YYYY-MM-DD")
                    if (!dateValid && !timeValid) append("；")
                    if (!timeValid) append("时刻格式应为 HH:mm")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun PayerSelectorRow(
    selectedPayer: FeePayer,
    onSelectPayer: (FeePayer) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        FeePayer.entries.forEach { payer ->
            ValuationChoiceChip(
                selected = selectedPayer == payer,
                label = payer.label,
                onClick = { onSelectPayer(payer) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ValuationChoiceChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ext = NodePulseThemeTokens.extendedColors
    val bg by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f)
        },
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "choiceChipBg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "choiceChipFg",
    )
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
    } else {
        ext.cardBorder
    }

    Box(
        modifier = modifier
            .clip(PulseArchitecturalShapes.SmallChip)
            .background(bg)
            .border(0.9.dp, borderColor, PulseArchitecturalShapes.SmallChip)
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.withTabularNumbers(),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ValuationQuickFillChip(
    label: String,
    onClick: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f))
            .border(0.8.dp, ext.cardBorder, CircleShape)
            .pulseBouncyClickable(
                pressedScale = NodePulseMotion.chipPressScale,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun CurrencyPickerDialog(
    title: String,
    selectedCurrency: ValuationCurrency,
    fxState: FxRatesState,
    onSelect: (ValuationCurrency) -> Unit,
    onDismiss: () -> Unit,
) {
    val ext = NodePulseThemeTokens.extendedColors
    PulseAlertDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Rounded.CurrencyExchange,
        title = title,
        buttons = {
            PulseDialogPrimaryButton(
                text = "关闭",
                onClick = onDismiss,
                useTonal = true,
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(ValuationCurrency.entries, key = { it.code }) { item ->
                val isSelected = item == selectedCurrency
                val quote = fxState.quoteFor(item)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(PulseArchitecturalShapes.MediumBadge)
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            }
                        )
                        .border(
                            width = 0.8.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            } else {
                                ext.cardBorder
                            },
                            shape = PulseArchitecturalShapes.MediumBadge,
                        )
                        .clickable { onSelect(item) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${item.code} (${item.symbol}) · ${item.displayNameZh}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (item != ValuationCurrency.CNY) {
                            Text(
                                text = "1 ${item.code} = ¥${VpsValuationEngine.formatFxRate(quote.rateToCny)} (${quote.source.label})",
                                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "已选中",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FxOverrideDialog(
    currency: ValuationCurrency,
    currentRate: BigDecimal,
    onlineRate: BigDecimal?,
    hasManualOverride: Boolean,
    onSave: (BigDecimal) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var rateText by remember(currency, currentRate) {
        mutableStateOf(VpsValuationEngine.formatFxRate(currentRate))
    }
    val parsedRate = remember(rateText) { parseDecimalOrNull(rateText) }
    val isValid = parsedRate != null && parsedRate > BigDecimal.ZERO

    PulseAlertDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Rounded.Edit,
        title = "手动校准 ${currency.code} 汇率",
        message = "统一口径：1 ${currency.code} = X CNY。此校准值会持久保存在本地，直至手动还原。",
        confirmText = "保存校准",
        confirmEnabled = isValid,
        onConfirm = {
            if (parsedRate != null && parsedRate > BigDecimal.ZERO) {
                onSave(parsedRate)
            }
        },
        dismissText = "取消",
        onDismiss = onDismiss,
        extraActionText = if (hasManualOverride) "恢复自动" else null,
        onExtraAction = if (hasManualOverride) onReset else null,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = rateText,
                onValueChange = { rateText = sanitizeDecimalInput(it, maxDecimals = 6) },
                label = { Text("1 ${currency.code} 折合人民币 (CNY)") },
                isError = !isValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = PulseArchitecturalShapes.MediumBadge,
                modifier = Modifier.fillMaxWidth(),
            )
            val autoRef = onlineRate ?: currency.defaultCnyRate
            Text(
                text = "自动参考值：¥${VpsValuationEngine.formatFxRate(autoRef)}",
                style = MaterialTheme.typography.labelSmall.withTabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun valuationTextFieldColors(
    containerColor: Color,
    borderColor: Color,
) = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = containerColor,
    unfocusedContainerColor = containerColor,
    unfocusedBorderColor = borderColor,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
)

// ── Parsing & Formatting Helpers ──────────────────────────────────────────────

private fun evaluateValuationForm(
    mode: ValuationMode,
    machineLabel: String,
    currency: ValuationCurrency,
    fxRateToCny: BigDecimal,
    renewalAmountText: String,
    cyclePreset: BillingCyclePreset,
    naturalSpan: NaturalCycleSpan,
    customDaysText: String,
    useCustomPrevRenewal: Boolean,
    prevRenewalDateText: String,
    preciseToMinute: Boolean,
    valuationDateText: String,
    valuationTimeText: String,
    expiryDateText: String,
    expiryTimeText: String,
    askingPriceText: String,
    pushFeeText: String,
    pushCurrency: ValuationCurrency,
    pushFeeToCnyRate: BigDecimal,
    pushPayer: FeePayer,
    middlemanEnabled: Boolean,
    middlemanMode: MiddlemanFeeMode,
    middlemanValueText: String,
    middlemanPayer: FeePayer,
): VpsValuationOutcome {
    val parseErrors = mutableListOf<String>()

    val renewalAmount = parseDecimalOrNull(renewalAmountText)
    if (renewalAmount == null || renewalAmount <= BigDecimal.ZERO) {
        parseErrors += "请输入大于 0 的续费金额"
    }

    val valuationDate = parseIsoDate(valuationDateText)
    if (valuationDate == null) {
        parseErrors += "估值基准日期格式应为 YYYY-MM-DD"
    }
    val expiryDate = parseIsoDate(expiryDateText)
    if (expiryDate == null) {
        parseErrors += "到期日期格式应为 YYYY-MM-DD"
    }

    val valuationTime = if (preciseToMinute) {
        parseTimeHm(valuationTimeText).also {
            if (it == null) parseErrors += "估值时刻格式应为 HH:mm"
        } ?: LocalTime.MIDNIGHT
    } else {
        LocalTime.MIDNIGHT
    }

    val expiryTime = if (preciseToMinute) {
        parseTimeHm(expiryTimeText).also {
            if (it == null) parseErrors += "到期时刻格式应为 HH:mm"
        } ?: LocalTime.MIDNIGHT
    } else {
        LocalTime.MIDNIGHT
    }

    val customCycleDays = if (cyclePreset == BillingCyclePreset.CUSTOM) {
        customDaysText.trim().toIntOrNull().also {
            if (it == null || it !in 1..3660) {
                parseErrors += "自定义周期天数须为 1 ~ 3660 的整数"
            }
        }
    } else {
        null
    }

    val prevRenewalDt = if (cyclePreset == BillingCyclePreset.CALENDAR_NATURAL && useCustomPrevRenewal) {
        val parsedPrev = parseIsoDate(prevRenewalDateText)
        if (parsedPrev == null) {
            parseErrors += "上期续费日期格式应为 YYYY-MM-DD"
            null
        } else {
            LocalDateTime.of(parsedPrev, expiryTime)
        }
    } else {
        null
    }

    var askingPrice = BigDecimal.ZERO
    var pushFee = BigDecimal.ZERO
    var middlemanVal = BigDecimal.ZERO
    if (mode == ValuationMode.TRANSFER_DEAL) {
        val parsedAsking = parseDecimalOrNull(askingPriceText)
        if (parsedAsking == null || parsedAsking < BigDecimal.ZERO) {
            parseErrors += "请输入有效的出让价格"
        } else {
            askingPrice = parsedAsking
        }

        val parsedPush = if (pushFeeText.isBlank()) BigDecimal.ZERO else parseDecimalOrNull(pushFeeText)
        if (parsedPush == null || parsedPush < BigDecimal.ZERO) {
            parseErrors += "Push 费格式有误"
        } else {
            pushFee = parsedPush
        }

        if (middlemanEnabled) {
            val parsedMid = if (middlemanValueText.isBlank()) BigDecimal.ZERO else parseDecimalOrNull(middlemanValueText)
            if (parsedMid == null || parsedMid < BigDecimal.ZERO) {
                parseErrors += "中介费格式有误"
            } else {
                middlemanVal = parsedMid
            }
        }
    }

    if (parseErrors.isNotEmpty()) {
        return VpsValuationOutcome.Invalid(parseErrors)
    }

    return VpsValuationEngine.calculate(
        VpsValuationInput(
            machineLabel = machineLabel,
            mode = mode,
            renewalAmount = renewalAmount!!,
            currency = currency,
            fxRateToCny = fxRateToCny,
            cyclePreset = cyclePreset,
            naturalCycleSpan = naturalSpan,
            previousRenewalDateTime = prevRenewalDt,
            customCycleDays = customCycleDays,
            valuationDateTime = LocalDateTime.of(valuationDate!!, valuationTime),
            expiryDateTime = LocalDateTime.of(expiryDate!!, expiryTime),
            preciseToMinute = preciseToMinute,
            askingPriceCny = askingPrice,
            pushFeeAmount = pushFee,
            pushFeeCurrency = pushCurrency,
            pushFeeToCnyRate = pushFeeToCnyRate,
            pushFeePayer = pushPayer,
            middlemanEnabled = middlemanEnabled,
            middlemanFeeMode = middlemanMode,
            middlemanFeeValue = middlemanVal,
            middlemanFeePayer = middlemanPayer,
        )
    )
}

private fun buildFxSourceFootnote(
    source: FxRateSource,
    providerDate: String?,
): String = when (source) {
    FxRateSource.MANUAL_OVERRIDE -> "手动校准"
    FxRateSource.ONLINE_FRESH,
    FxRateSource.ONLINE_CACHED,
    -> if (!providerDate.isNullOrBlank()) "Frankfurter $providerDate" else "在线汇率"
    FxRateSource.OFFLINE_DEFAULT -> "内置参考汇率"
}

private fun sanitizeDecimalInput(
    raw: String,
    maxDecimals: Int = 4,
): String {
    val sb = StringBuilder()
    var hasDot = false
    var decimals = 0
    for (ch in raw) {
        when {
            ch.isDigit() -> {
                if (hasDot) {
                    if (decimals < maxDecimals) {
                        sb.append(ch)
                        decimals++
                    }
                } else if (sb.length < 10) {
                    sb.append(ch)
                }
            }
            ch == '.' && !hasDot -> {
                if (sb.isEmpty()) sb.append('0')
                sb.append('.')
                hasDot = true
            }
        }
    }
    return sb.toString()
}

private fun parseDecimalOrNull(raw: String): BigDecimal? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed == ".") return null
    return trimmed.toBigDecimalOrNull()
}

private fun parseIsoDate(raw: String): LocalDate? {
    val trimmed = raw.trim()
    if (trimmed.length != 10) return null
    return runCatching { LocalDate.parse(trimmed, ISO_DATE_FORMATTER) }.getOrNull()
}

private fun parseTimeHm(raw: String): LocalTime? {
    val trimmed = raw.trim()
    if (trimmed.length != 5 || trimmed[2] != ':') return null
    return runCatching { LocalTime.parse(trimmed, HH_MM_FORMATTER) }.getOrNull()
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Anode VPS Valuation", text))
}
