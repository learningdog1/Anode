package com.nodeseek.app.domain.valuation

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure financial valuation engine for VPS remaining value & second-hand transfer math.
 *
 * Uses [BigDecimal] with [RoundingMode.HALF_UP] throughout so currency conversion,
 * linear period pro-rating, leap-year calendar cycles, and odd-cent fee splitting are exact.
 */
object VpsValuationEngine {

    private const val INTERMEDIATE_SCALE = 16
    private const val MAX_CYCLE_DAYS = 3660L
    private const val SECONDS_PER_DAY_LONG = 86_400L

    private val SECONDS_PER_DAY = BigDecimal(SECONDS_PER_DAY_LONG)
    private val HUNDRED = BigDecimal("100")
    private val TWO = BigDecimal("2")
    private val ZERO_2 = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
    private val ZERO_4 = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)

    private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    /**
     * Returns current Shanghai valuation timestamp normalized according to [preciseToMinute].
     */
    fun defaultValuationDateTime(
        preciseToMinute: Boolean = false,
        clock: Clock = Clock.system(VALUATION_ZONE_ID),
    ): LocalDateTime {
        val shanghaiClock = if (clock.zone == VALUATION_ZONE_ID) clock else clock.withZone(VALUATION_ZONE_ID)
        val now = LocalDateTime.now(shanghaiClock).withSecond(0).withNano(0)
        return if (preciseToMinute) now else now.toLocalDate().atStartOfDay()
    }

    /**
     * Returns today's [LocalDate] in `Asia/Shanghai`.
     */
    fun todayInShanghai(clock: Clock = Clock.system(VALUATION_ZONE_ID)): LocalDate {
        val shanghaiClock = if (clock.zone == VALUATION_ZONE_ID) clock else clock.withZone(VALUATION_ZONE_ID)
        return LocalDate.now(shanghaiClock)
    }

    /**
     * Validates [input] and computes remaining value + optional second-hand transfer breakdown.
     */
    fun calculate(input: VpsValuationInput): VpsValuationOutcome {
        val errors = mutableListOf<String>()

        if (input.renewalAmount <= BigDecimal.ZERO) {
            errors += "续费金额须大于 0"
        }

        val effectiveFxRate = if (input.currency == ValuationCurrency.CNY) {
            BigDecimal.ONE
        } else {
            if (input.fxRateToCny <= BigDecimal.ZERO) {
                errors += "汇率须大于 0"
            }
            input.fxRateToCny
        }

        val valuationDt = normalizeDateTime(input.valuationDateTime, input.preciseToMinute)
        val expiryDt = normalizeDateTime(input.expiryDateTime, input.preciseToMinute)

        var resolvedCycleStart: LocalDateTime = expiryDt
        var effectiveCycleDays: BigDecimal = BigDecimal.ONE
        var cycleDescription = ""

        when (input.cyclePreset) {
            BillingCyclePreset.MONTHLY,
            BillingCyclePreset.QUARTERLY,
            BillingCyclePreset.SEMI_ANNUAL,
            BillingCyclePreset.ANNUAL,
            BillingCyclePreset.BIENNIAL,
            BillingCyclePreset.TRIENNIAL,
            -> {
                val fixedDays = input.cyclePreset.fixedDays ?: 365
                resolvedCycleStart = expiryDt.minusDays(fixedDays.toLong())
                effectiveCycleDays = BigDecimal(fixedDays)
                cycleDescription = input.cyclePreset.label
            }

            BillingCyclePreset.CUSTOM -> {
                val customDays = input.customCycleDays
                if (customDays == null || customDays !in 1..MAX_CYCLE_DAYS.toInt()) {
                    errors += "自定义周期天数须在 1 ~ $MAX_CYCLE_DAYS 天之间"
                } else {
                    resolvedCycleStart = expiryDt.minusDays(customDays.toLong())
                    effectiveCycleDays = BigDecimal(customDays)
                    cycleDescription = "自定义 ${customDays}天"
                }
            }

            BillingCyclePreset.CALENDAR_NATURAL -> {
                val customPrev = input.previousRenewalDateTime?.let {
                    normalizeDateTime(it, input.preciseToMinute)
                }
                resolvedCycleStart = customPrev ?: expiryDt.minusMonths(input.naturalCycleSpan.months.toLong())
                val cycleSeconds = Duration.between(resolvedCycleStart, expiryDt).seconds
                if (cycleSeconds <= 0L) {
                    errors += "上一续费时间须早于到期时间"
                } else if (cycleSeconds > MAX_CYCLE_DAYS * SECONDS_PER_DAY_LONG) {
                    errors += "自然周期跨度不能超过 $MAX_CYCLE_DAYS 天"
                } else {
                    effectiveCycleDays = BigDecimal(cycleSeconds)
                        .divide(SECONDS_PER_DAY, INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
                    val formattedDays = formatDaysNumber(effectiveCycleDays)
                    val spanPrefix = if (customPrev != null) "自然周期" else input.naturalCycleSpan.label
                    cycleDescription = "$spanPrefix (${formattedDays}天)"
                }
            }
        }

        if (input.mode == ValuationMode.TRANSFER_DEAL) {
            if (input.askingPriceCny < BigDecimal.ZERO) {
                errors += "出让价格不能为负数"
            }
            if (input.pushFeeAmount < BigDecimal.ZERO) {
                errors += "Push / 过户费不能为负数"
            } else if (input.pushFeeAmount > BigDecimal.ZERO &&
                input.pushFeeCurrency != ValuationCurrency.CNY &&
                input.pushFeeToCnyRate <= BigDecimal.ZERO
            ) {
                errors += "Push 费汇率须大于 0"
            }
            if (input.middlemanEnabled) {
                when (input.middlemanFeeMode) {
                    MiddlemanFeeMode.PERCENT -> {
                        if (input.middlemanFeeValue < BigDecimal.ZERO || input.middlemanFeeValue > HUNDRED) {
                            errors += "中介费比例须在 0% ~ 100% 之间"
                        }
                    }
                    MiddlemanFeeMode.FIXED_CNY -> {
                        if (input.middlemanFeeValue < BigDecimal.ZERO) {
                            errors += "固定中介费不能为负数"
                        }
                    }
                }
            }
        }

        if (errors.isNotEmpty()) {
            return VpsValuationOutcome.Invalid(errors)
        }

        val rawRemainingSeconds = Duration.between(valuationDt, expiryDt).seconds
        val remainingSeconds = rawRemainingSeconds.coerceAtLeast(0L)
        val isExpired = remainingSeconds == 0L

        val remainingDays = BigDecimal(remainingSeconds)
            .divide(SECONDS_PER_DAY, INTERMEDIATE_SCALE, RoundingMode.HALF_UP)

        val ratioBd = if (effectiveCycleDays > BigDecimal.ZERO) {
            remainingDays.divide(effectiveCycleDays, 6, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }
        val remainingRatio = ratioBd.toFloat().coerceIn(0f, 1f)

        val renewalOriginal = input.renewalAmount.setScale(2, RoundingMode.HALF_UP).normalizeZero(2)
        val cycleRenewalCnyRaw = input.renewalAmount.multiply(effectiveFxRate)
        val cycleRenewalCny = cycleRenewalCnyRaw.setScale(2, RoundingMode.HALF_UP).normalizeZero(2)

        val dailyOfficialCny = cycleRenewalCnyRaw
            .divide(effectiveCycleDays, 2, RoundingMode.HALF_UP)
            .normalizeZero(2)
        val dailyOfficialHighPrecisionCny = cycleRenewalCnyRaw
            .divide(effectiveCycleDays, 4, RoundingMode.HALF_UP)
            .normalizeZero(4)

        val remainingValueOriginal = if (isExpired) {
            ZERO_2
        } else {
            input.renewalAmount
                .multiply(remainingDays)
                .divide(effectiveCycleDays, 2, RoundingMode.HALF_UP)
                .normalizeZero(2)
        }
        val remainingValueCny = if (isExpired) {
            ZERO_2
        } else {
            cycleRenewalCnyRaw
                .multiply(remainingDays)
                .divide(effectiveCycleDays, 2, RoundingMode.HALF_UP)
                .normalizeZero(2)
        }

        val transferBreakdown = if (input.mode == ValuationMode.TRANSFER_DEAL) {
            calculateTransferBreakdown(
                input = input,
                remainingValueCny = remainingValueCny,
                remainingDays = remainingDays,
            )
        } else {
            null
        }

        return VpsValuationOutcome.Success(
            VpsValuationResult(
                machineLabel = input.machineLabel.trim().take(120),
                mode = input.mode,
                currency = input.currency,
                renewalAmountOriginal = renewalOriginal,
                fxRateToCny = effectiveFxRate,
                cyclePreset = input.cyclePreset,
                cycleDescription = cycleDescription,
                effectiveCycleDays = effectiveCycleDays,
                resolvedCycleStartDateTime = resolvedCycleStart,
                valuationDateTime = valuationDt,
                expiryDateTime = expiryDt,
                preciseToMinute = input.preciseToMinute,
                isExpired = isExpired,
                remainingSeconds = remainingSeconds,
                remainingDays = remainingDays,
                remainingRatio = remainingRatio,
                cycleRenewalCny = cycleRenewalCny,
                dailyOfficialCostCny = dailyOfficialCny,
                dailyOfficialCostHighPrecisionCny = dailyOfficialHighPrecisionCny,
                remainingValueOriginal = remainingValueOriginal,
                remainingValueCny = remainingValueCny,
                transferBreakdown = transferBreakdown,
            )
        )
    }

    /**
     * Splits [totalCny] between buyer and seller according to [payer].
     *
     * When [FeePayer.SPLIT] divides an odd cent, buyer absorbs the extra `0.01 CNY` (`HALF_UP`)
     * and seller takes `total - buyer`, guaranteeing `buyer + seller == total`.
     */
    fun allocateFee(totalCny: BigDecimal, payer: FeePayer): Pair<BigDecimal, BigDecimal> {
        val roundedTotal = totalCny.setScale(2, RoundingMode.HALF_UP).normalizeZero(2)
        if (roundedTotal.compareTo(BigDecimal.ZERO) <= 0) {
            return ZERO_2 to ZERO_2
        }
        return when (payer) {
            FeePayer.BUYER -> roundedTotal to ZERO_2
            FeePayer.SELLER -> ZERO_2 to roundedTotal
            FeePayer.SPLIT -> {
                val buyerShare = roundedTotal.divide(TWO, 2, RoundingMode.HALF_UP).normalizeZero(2)
                val sellerShare = roundedTotal.subtract(buyerShare)
                    .setScale(2, RoundingMode.HALF_UP)
                    .normalizeZero(2)
                buyerShare to sellerShare
            }
        }
    }

    /**
     * Formats a valid [VpsValuationResult] into Markdown.
     */
    fun formatMarkdown(
        result: VpsValuationResult,
        style: ValuationMarkdownStyle = ValuationMarkdownStyle.SUMMARY_CARD,
        fxSourceNote: String = "参考汇率",
    ): String {
        val safeLabel = sanitizeInlineText(result.machineLabel)
        val safeFxNote = sanitizeInlineText(fxSourceNote).ifBlank { "参考汇率" }
        val valuationDateStr = formatDateTime(result.valuationDateTime, result.preciseToMinute)
        val expiryDateStr = formatDateTime(result.expiryDateTime, result.preciseToMinute)
        val daysRemainingStr = "${formatDaysNumber(result.remainingDays)} 天"
        val pctStr = formatPercent(BigDecimal((result.remainingRatio * 100.0).toString()))
        val dailyOfficialStr = formatDailyCny(
            result.dailyOfficialCostCny,
            result.dailyOfficialCostHighPrecisionCny,
        )
        val fxSummary = if (result.currency == ValuationCurrency.CNY) {
            "CNY 本币结算"
        } else {
            "1 ${result.currency.code} = ¥${formatFxRate(result.fxRateToCny)}（$safeFxNote）"
        }

        return when (style) {
            ValuationMarkdownStyle.SUMMARY_CARD -> buildSummaryCardMarkdown(
                result = result,
                safeLabel = safeLabel,
                valuationDateStr = valuationDateStr,
                expiryDateStr = expiryDateStr,
                daysRemainingStr = daysRemainingStr,
                pctStr = pctStr,
                dailyOfficialStr = dailyOfficialStr,
                fxSummary = fxSummary,
            )

            ValuationMarkdownStyle.THREE_COLUMN_TABLE -> buildThreeColumnTableMarkdown(
                result = result,
                safeLabel = safeLabel,
                valuationDateStr = valuationDateStr,
                expiryDateStr = expiryDateStr,
                daysRemainingStr = daysRemainingStr,
                pctStr = pctStr,
                dailyOfficialStr = dailyOfficialStr,
                fxSummary = fxSummary,
            )
        }
    }

    private fun buildSummaryCardMarkdown(
        result: VpsValuationResult,
        safeLabel: String,
        valuationDateStr: String,
        expiryDateStr: String,
        daysRemainingStr: String,
        pctStr: String,
        dailyOfficialStr: String,
        fxSummary: String,
    ): String = buildString {
        append("> **【VPS 估值折算】**")
        if (safeLabel.isNotEmpty()) {
            append(" ").append(safeLabel)
        }
        append("\n")
        append("> 基准时间：").append(valuationDateStr)
        append(" · 汇率口径：").append(fxSummary)
        append("\n\n")

        append("- **续费标准**：")
        if (result.currency == ValuationCurrency.CNY) {
            append("¥").append(result.cycleRenewalCny.toPlainString())
        } else {
            append(result.renewalAmountOriginal.toPlainString())
                .append(" ").append(result.currency.code)
                .append(" · 折合 ¥").append(result.cycleRenewalCny.toPlainString())
        }
        append(" / ").append(result.cycleDescription)
        append("（日均 ¥").append(dailyOfficialStr).append("）\n")

        append("- **剩余周期**：")
        append(valuationDateStr).append(" → ").append(expiryDateStr)
        if (result.isExpired) {
            append(" · **已到期**（余 0 天 / 0.0%）\n")
        } else {
            append(" · 余 **").append(daysRemainingStr).append("**（").append(pctStr).append("）\n")
        }

        append("- **净剩余价值**：**¥").append(result.remainingValueCny.toPlainString()).append("**")
        if (result.currency != ValuationCurrency.CNY) {
            append("（原币约 ")
                .append(result.remainingValueOriginal.toPlainString())
                .append(" ")
                .append(result.currency.code)
                .append("）")
        }

        result.transferBreakdown?.let { deal ->
            append("\n- **交易核算**：")
            append("出让 ¥").append(deal.askingPriceCny.toPlainString())
            val feeDetails = buildFeeDetailParts(deal)
            if (feeDetails.isNotEmpty()) {
                append("（含 ").append(feeDetails.joinToString("、")).append("）")
            }
            append(" · 买家实付 **¥").append(deal.buyerTotalCostCny.toPlainString()).append("**")
            append(" · 卖家净收 ¥").append(deal.sellerNetProceedsCny.toPlainString())
            append(" · **").append(deal.spreadStatus.label)
            if (deal.spreadStatus != DealSpreadStatus.PAR) {
                append(" ").append(formatSignedCny(deal.buyerPremiumCny))
                deal.buyerPremiumPercent?.let { pct ->
                    append(" (").append(formatSignedPercent(pct)).append(")")
                }
            }
            append("**")
            deal.buyerEffectiveDailyCostCny?.let { buyerDaily ->
                val dailyFmt = formatDailyCny(buyerDaily, deal.buyerEffectiveDailyCostHighPrecisionCny ?: ZERO_4)
                append(" · 持仓日均 ¥").append(dailyFmt)
            }
        }
    }.trimEnd()

    private fun buildThreeColumnTableMarkdown(
        result: VpsValuationResult,
        safeLabel: String,
        valuationDateStr: String,
        expiryDateStr: String,
        daysRemainingStr: String,
        pctStr: String,
        dailyOfficialStr: String,
        fxSummary: String,
    ): String = buildString {
        append("| 核算项 | 折算数值 | 口径说明 |\n")
        append("| :--- | ---: | :--- |\n")

        if (safeLabel.isNotEmpty()) {
            append("| 机型 / 套餐 | ")
                .append(escapeTableCell(safeLabel))
                .append(" | ")
                .append(result.mode.label)
                .append(" |\n")
        }

        val renewalNote = buildString {
            if (result.currency != ValuationCurrency.CNY) {
                append("原币 ").append(result.renewalAmountOriginal.toPlainString())
                    .append(" ").append(result.currency.code).append(" · ")
            }
            append(escapeTableCell(result.cycleDescription))
            append(" · 日均 ¥").append(dailyOfficialStr)
        }
        append("| 周期续费 | ¥")
            .append(result.cycleRenewalCny.toPlainString())
            .append(" | ")
            .append(renewalNote)
            .append(" |\n")

        val periodValue = if (result.isExpired) "已到期 (0 天)" else "$daysRemainingStr ($pctStr)"
        append("| 剩余周期 | ")
            .append(periodValue)
            .append(" | ")
            .append(valuationDateStr).append(" 至 ").append(expiryDateStr)
            .append(" |\n")

        val netValueNote = buildString {
            if (result.currency != ValuationCurrency.CNY) {
                append("原币约 ")
                    .append(result.remainingValueOriginal.toPlainString())
                    .append(" ")
                    .append(result.currency.code)
                    .append(" · ")
            }
            append("按周期线性折算")
        }
        append("| **净剩余价值** | **¥")
            .append(result.remainingValueCny.toPlainString())
            .append("** | ")
            .append(netValueNote)
            .append(" |\n")

        result.transferBreakdown?.let { deal ->
            append("| 出让挂牌价 | ¥")
                .append(deal.askingPriceCny.toPlainString())
                .append(" | 交易标价 |\n")

            if (deal.pushFeeTotalCny > BigDecimal.ZERO) {
                val pushNote = buildString {
                    if (deal.pushFeeCurrency != ValuationCurrency.CNY) {
                        append("原币 ").append(deal.pushFeeOriginal.toPlainString())
                            .append(" ").append(deal.pushFeeCurrency.code).append(" · ")
                    }
                    append(deal.pushFeePayer.label)
                    append("（买 ¥").append(deal.buyerPushFeeCny.toPlainString())
                    append(" / 卖 ¥").append(deal.sellerPushFeeCny.toPlainString()).append("）")
                }
                append("| Push / 过户费 | ¥")
                    .append(deal.pushFeeTotalCny.toPlainString())
                    .append(" | ")
                    .append(pushNote)
                    .append(" |\n")
            }

            if (deal.middlemanEnabled && deal.middlemanFeeTotalCny > BigDecimal.ZERO) {
                val midSpec = when (deal.middlemanFeeMode) {
                    MiddlemanFeeMode.PERCENT -> "${formatTrimmedDecimal(deal.middlemanFeeValue)}%"
                    MiddlemanFeeMode.FIXED_CNY -> "固定金额"
                }
                val midNote = "$midSpec · ${deal.middlemanFeePayer.label}" +
                    "（买 ¥${deal.buyerMiddlemanFeeCny.toPlainString()}" +
                    " / 卖 ¥${deal.sellerMiddlemanFeeCny.toPlainString()}）"
                append("| 中介手续费 | ¥")
                    .append(deal.middlemanFeeTotalCny.toPlainString())
                    .append(" | ")
                    .append(midNote)
                    .append(" |\n")
            }

            val buyerDailyNote = deal.buyerEffectiveDailyCostCny?.let { buyerDaily ->
                "持仓日均 ¥${formatDailyCny(buyerDaily, deal.buyerEffectiveDailyCostHighPrecisionCny ?: ZERO_4)}"
            } ?: "已到期，无持仓日均"
            append("| **买家实付成本** | **¥")
                .append(deal.buyerTotalCostCny.toPlainString())
                .append("** | ")
                .append(buyerDailyNote)
                .append(" |\n")

            append("| 卖家净到手 | ¥")
                .append(deal.sellerNetProceedsCny.toPlainString())
                .append(" | 扣除卖家承担费用后净收 |\n")

            val spreadValue = buildString {
                if (deal.spreadStatus == DealSpreadStatus.PAR) {
                    append("**¥0.00 (0.00%)**")
                } else {
                    append("**").append(formatSignedCny(deal.buyerPremiumCny))
                    deal.buyerPremiumPercent?.let { pct ->
                        append(" (").append(formatSignedPercent(pct)).append(")")
                    }
                    append("**")
                }
            }
            val spreadNote = when (deal.spreadStatus) {
                DealSpreadStatus.DISCOUNT -> "折价让利（低于净残值）"
                DealSpreadStatus.PAR -> "平出（与净残值持平）"
                DealSpreadStatus.PREMIUM -> "溢价（高于净残值）"
            }
            append("| **差价评估** | ")
                .append(spreadValue)
                .append(" | ")
                .append(spreadNote)
                .append(" |\n")
        }

        append("\n> 估值基准：").append(valuationDateStr)
        append(" · 汇率口径：").append(fxSummary)
        append(" · 负差价代表折价让利。")
    }.trimEnd()

    private fun calculateTransferBreakdown(
        input: VpsValuationInput,
        remainingValueCny: BigDecimal,
        remainingDays: BigDecimal,
    ): TransferDealBreakdown {
        val askingPriceCny = input.askingPriceCny.setScale(2, RoundingMode.HALF_UP).normalizeZero(2)
        val pushOrig = input.pushFeeAmount.setScale(2, RoundingMode.HALF_UP).normalizeZero(2)
        val pushEffectiveRate = if (input.pushFeeCurrency == ValuationCurrency.CNY) {
            BigDecimal.ONE
        } else {
            input.pushFeeToCnyRate
        }
        val pushTotalCny = input.pushFeeAmount
            .multiply(pushEffectiveRate)
            .setScale(2, RoundingMode.HALF_UP)
            .normalizeZero(2)
        val (buyerPush, sellerPush) = allocateFee(pushTotalCny, input.pushFeePayer)

        val middlemanTotalCny = if (!input.middlemanEnabled) {
            ZERO_2
        } else {
            when (input.middlemanFeeMode) {
                MiddlemanFeeMode.PERCENT -> askingPriceCny
                    .multiply(input.middlemanFeeValue)
                    .divide(HUNDRED, 2, RoundingMode.HALF_UP)
                    .normalizeZero(2)
                MiddlemanFeeMode.FIXED_CNY -> input.middlemanFeeValue
                    .setScale(2, RoundingMode.HALF_UP)
                    .normalizeZero(2)
            }
        }
        val (buyerMiddleman, sellerMiddleman) = allocateFee(middlemanTotalCny, input.middlemanFeePayer)

        val buyerTotalCostCny = askingPriceCny
            .add(buyerPush)
            .add(buyerMiddleman)
            .setScale(2, RoundingMode.HALF_UP)
            .normalizeZero(2)

        val sellerNetProceedsCny = askingPriceCny
            .subtract(sellerPush)
            .subtract(sellerMiddleman)
            .setScale(2, RoundingMode.HALF_UP)
            .normalizeZero(2)

        val buyerPremiumCny = buyerTotalCostCny
            .subtract(remainingValueCny)
            .setScale(2, RoundingMode.HALF_UP)
            .normalizeZero(2)

        val sellerPremiumCny = sellerNetProceedsCny
            .subtract(remainingValueCny)
            .setScale(2, RoundingMode.HALF_UP)
            .normalizeZero(2)

        val buyerPremiumPercent = if (remainingValueCny.compareTo(BigDecimal.ZERO) > 0) {
            buyerPremiumCny
                .multiply(HUNDRED)
                .divide(remainingValueCny, 2, RoundingMode.HALF_UP)
                .normalizeZero(2)
        } else {
            null
        }

        val spreadStatus = when {
            buyerPremiumCny.compareTo(BigDecimal.ZERO) < 0 -> DealSpreadStatus.DISCOUNT
            buyerPremiumCny.compareTo(BigDecimal.ZERO) > 0 -> DealSpreadStatus.PREMIUM
            else -> DealSpreadStatus.PAR
        }

        val buyerEffectiveDailyCostCny = if (remainingDays.compareTo(BigDecimal.ZERO) > 0) {
            buyerTotalCostCny.divide(remainingDays, 2, RoundingMode.HALF_UP).normalizeZero(2)
        } else {
            null
        }
        val buyerEffectiveDailyHighPrecision = if (remainingDays.compareTo(BigDecimal.ZERO) > 0) {
            buyerTotalCostCny.divide(remainingDays, 4, RoundingMode.HALF_UP).normalizeZero(4)
        } else {
            null
        }

        return TransferDealBreakdown(
            askingPriceCny = askingPriceCny,
            pushFeeOriginal = pushOrig,
            pushFeeCurrency = input.pushFeeCurrency,
            pushFeeTotalCny = pushTotalCny,
            pushFeePayer = input.pushFeePayer,
            buyerPushFeeCny = buyerPush,
            sellerPushFeeCny = sellerPush,
            middlemanEnabled = input.middlemanEnabled,
            middlemanFeeMode = input.middlemanFeeMode,
            middlemanFeeValue = input.middlemanFeeValue,
            middlemanFeeTotalCny = middlemanTotalCny,
            middlemanFeePayer = input.middlemanFeePayer,
            buyerMiddlemanFeeCny = buyerMiddleman,
            sellerMiddlemanFeeCny = sellerMiddleman,
            buyerTotalCostCny = buyerTotalCostCny,
            sellerNetProceedsCny = sellerNetProceedsCny,
            buyerPremiumCny = buyerPremiumCny,
            sellerPremiumCny = sellerPremiumCny,
            buyerPremiumPercent = buyerPremiumPercent,
            spreadStatus = spreadStatus,
            buyerEffectiveDailyCostCny = buyerEffectiveDailyCostCny,
            buyerEffectiveDailyCostHighPrecisionCny = buyerEffectiveDailyHighPrecision,
        )
    }

    private fun buildFeeDetailParts(deal: TransferDealBreakdown): List<String> = buildList {
        if (deal.pushFeeTotalCny > BigDecimal.ZERO) {
            add("Push ¥${deal.pushFeeTotalCny.toPlainString()}·${deal.pushFeePayer.label}")
        }
        if (deal.middlemanEnabled && deal.middlemanFeeTotalCny > BigDecimal.ZERO) {
            add("中介 ¥${deal.middlemanFeeTotalCny.toPlainString()}·${deal.middlemanFeePayer.label}")
        }
    }

    private fun normalizeDateTime(dt: LocalDateTime, preciseToMinute: Boolean): LocalDateTime {
        val truncated = dt.withSecond(0).withNano(0)
        return if (preciseToMinute) truncated else truncated.toLocalDate().atStartOfDay()
    }

    private fun BigDecimal.normalizeZero(scale: Int): BigDecimal =
        if (compareTo(BigDecimal.ZERO) == 0) {
            BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP)
        } else {
            this
        }

    fun formatDaysNumber(days: BigDecimal): String =
        days.setScale(2, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()

    fun formatDateTime(dt: LocalDateTime, preciseToMinute: Boolean): String =
        if (preciseToMinute) DATE_TIME_FORMATTER.format(dt) else DATE_FORMATTER.format(dt)

    fun formatSignedCny(amount: BigDecimal): String {
        val scaled = amount.setScale(2, RoundingMode.HALF_UP)
        return when {
            scaled.compareTo(BigDecimal.ZERO) > 0 -> "+¥${scaled.toPlainString()}"
            scaled.compareTo(BigDecimal.ZERO) < 0 -> "-¥${scaled.abs().toPlainString()}"
            else -> "¥0.00"
        }
    }

    fun formatSignedPercent(percent: BigDecimal): String {
        val scaled = percent.setScale(2, RoundingMode.HALF_UP)
        return when {
            scaled.compareTo(BigDecimal.ZERO) > 0 -> "+${scaled.toPlainString()}%"
            scaled.compareTo(BigDecimal.ZERO) < 0 -> "-${scaled.abs().toPlainString()}%"
            else -> "0.00%"
        }
    }

    fun formatFxRate(rate: BigDecimal): String {
        val scale = if (rate < BigDecimal("0.1")) 6 else 4
        return rate.setScale(scale, RoundingMode.HALF_UP).toPlainString()
    }

    private fun formatPercent(percent: BigDecimal): String =
        "${percent.setScale(1, RoundingMode.HALF_UP).toPlainString()}%"

    private fun formatDailyCny(scale2: BigDecimal, scale4: BigDecimal): String =
        if (scale2.compareTo(BigDecimal.ZERO) == 0 && scale4.compareTo(BigDecimal.ZERO) > 0) {
            scale4.stripTrailingZeros().toPlainString()
        } else {
            scale2.toPlainString()
        }

    private fun formatTrimmedDecimal(value: BigDecimal): String {
        val stripped = value.stripTrailingZeros()
        return if (stripped.scale() <= 0) stripped.setScale(0, RoundingMode.HALF_UP).toPlainString()
        else stripped.toPlainString()
    }

    private fun sanitizeInlineText(raw: String): String =
        raw.replace('\r', ' ').replace('\n', ' ').trim()

    private fun escapeTableCell(raw: String): String =
        sanitizeInlineText(raw).replace("|", "\\|")
}
