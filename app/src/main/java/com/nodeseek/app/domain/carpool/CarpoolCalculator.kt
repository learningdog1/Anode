package com.nodeseek.app.domain.carpool

import com.nodeseek.app.domain.valuation.ValuationCurrency
import java.math.BigDecimal
import java.math.RoundingMode

enum class CarpoolBillingCycle(
    val label: String,
    val months: Int?,
    val defaultDays: Int,
) {
    MONTHLY("月付", 1, 30),
    QUARTERLY("季付", 3, 90),
    SEMI_ANNUAL("半年", 6, 180),
    ANNUAL("年付", 12, 365),
    CUSTOM_DAYS("自定义天数", null, 30),
}

enum class CarpoolSeatSplitMode(
    val label: String,
) {
    EQUAL_ALL_SEATS("全员均摊（含车主）"),
    DRIVER_EXCLUDED("车主免摊（仅乘客均摊）"),
}

enum class CarpoolSeatStatus(
    val label: String,
    val badge: String,
) {
    OCCUPIED("已上车", "🟢 已上车"),
    RESERVED("待确认", "🟡 待确认"),
    OPEN("空车位", "⚪ 空车位"),
}

data class CarpoolSeatSlot(
    val seatIndex: Int,
    val memberLabel: String,
    val status: CarpoolSeatStatus,
    val note: String = "",
)

data class CarpoolCalculationInput(
    val serviceName: String = "合租拼车",
    val totalSeats: Int = 4,
    val occupiedSeats: Int = 1,
    val totalCycleCost: BigDecimal,
    val currency: ValuationCurrency = ValuationCurrency.CNY,
    val fxRateToCny: BigDecimal = currency.defaultCnyRate,
    val billingCycle: CarpoolBillingCycle = CarpoolBillingCycle.QUARTERLY,
    val customCycleDays: Int? = null,
    val seatSplitMode: CarpoolSeatSplitMode = CarpoolSeatSplitMode.EQUAL_ALL_SEATS,
    val jumpPenaltyPercent: BigDecimal = BigDecimal.ZERO,
    val jumpExitPolicyNote: String = "跳车按剩余天数折算",
)

data class CarpoolCalculationResult(
    val serviceName: String,
    val totalSeats: Int,
    val occupiedSeats: Int,
    val remainingSeats: Int,
    val payingSeats: Int,
    val effectiveCycleDays: Int,
    val billingCycle: CarpoolBillingCycle,
    val currency: ValuationCurrency,
    val fxRateToCny: BigDecimal,
    val totalCycleCostOriginal: BigDecimal,
    val totalCycleCostCny: BigDecimal,
    val perPersonCycleCostCny: BigDecimal,
    val perPersonMonthlyCny: BigDecimal,
    val perPersonQuarterlyCny: BigDecimal,
    val perPersonYearlyCny: BigDecimal,
    val perPersonDailyCny: BigDecimal,
    val perPersonDailyHighPrecisionCny: BigDecimal,
    val statusTag: String,
    val jumpPenaltyPercent: BigDecimal,
    val jumpExitPolicyNote: String,
) {
    val isMicroShareRoundedToZero: Boolean
        get() = totalCycleCostCny > BigDecimal.ZERO &&
            perPersonCycleCostCny.compareTo(BigDecimal.ZERO) == 0
}

sealed interface CarpoolCalculationOutcome {
    data class Success(val result: CarpoolCalculationResult) : CarpoolCalculationOutcome
    data class Invalid(val errors: List<String>) : CarpoolCalculationOutcome
}

data class CarpoolRefundResult(
    val paidAmountCny: BigDecimal,
    val totalDays: Int,
    val usedDays: Int,
    val remainingDays: Int,
    val usedCostCny: BigDecimal,
    val crashRefundCny: BigDecimal,
    val jumpRefundCny: BigDecimal,
    val penaltyRatePercent: BigDecimal,
    val penaltyDeductedCny: BigDecimal,
    val crashFormulaText: String,
    val jumpFormulaText: String,
)

data class ParsedCarpoolStatus(
    val rawToken: String,
    val occupiedSeats: Int?,
    val remainingSeats: Int?,
    val isFull: Boolean,
)

object CarpoolCalculator {
    private const val INTERMEDIATE_SCALE = 16
    private val HUNDRED = BigDecimal("100")
    private val DAYS_30 = BigDecimal("30")
    private val DAYS_90 = BigDecimal("90")
    private val DAYS_365 = BigDecimal("365")

    fun calculate(input: CarpoolCalculationInput): CarpoolCalculationOutcome {
        val errors = mutableListOf<String>()
        if (input.totalSeats !in 2..32) {
            errors += "总车位数需在 2 ~ 32 之间"
        }
        if (input.occupiedSeats !in 1..input.totalSeats.coerceAtLeast(1)) {
            errors += "已上车人数需在 1 ~ 总车位数之间"
        }
        if (input.totalCycleCost <= BigDecimal.ZERO) {
            errors += "总周期费用必须大于 0"
        }
        if (input.fxRateToCny <= BigDecimal.ZERO) {
            errors += "汇率必须大于 0"
        }
        val effectiveDays = if (input.billingCycle == CarpoolBillingCycle.CUSTOM_DAYS) {
            input.customCycleDays ?: 0
        } else {
            input.billingCycle.defaultDays
        }
        if (effectiveDays !in 1..3660) {
            errors += "计费周期天数需在 1 ~ 3660 天之间"
        }
        if (input.jumpPenaltyPercent < BigDecimal.ZERO || input.jumpPenaltyPercent > HUNDRED) {
            errors += "跳车折损比例需在 0% ~ 100% 之间"
        }

        if (errors.isNotEmpty()) {
            return CarpoolCalculationOutcome.Invalid(errors)
        }

        val payingSeats = when (input.seatSplitMode) {
            CarpoolSeatSplitMode.EQUAL_ALL_SEATS -> input.totalSeats
            CarpoolSeatSplitMode.DRIVER_EXCLUDED -> (input.totalSeats - 1).coerceAtLeast(1)
        }
        val effectiveFxRate = if (input.currency == ValuationCurrency.CNY) {
            BigDecimal.ONE
        } else {
            input.fxRateToCny
        }

        val totalCostCnyExact = input.totalCycleCost.multiply(effectiveFxRate)
        val totalCycleCostCny = totalCostCnyExact.setScale(2, RoundingMode.HALF_UP)

        val payingSeatsBd = BigDecimal(payingSeats)
        val daysBd = BigDecimal(effectiveDays)

        val perPersonCycleExact = totalCostCnyExact.divide(payingSeatsBd, INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
        val perPersonDailyExact = perPersonCycleExact.divide(daysBd, INTERMEDIATE_SCALE, RoundingMode.HALF_UP)

        val perPersonCycleCostCny = perPersonCycleExact.setScale(2, RoundingMode.HALF_UP)
        val perPersonDailyCny = perPersonDailyExact.setScale(2, RoundingMode.HALF_UP)

        val cycleMonths = input.billingCycle.months
        val perPersonMonthlyCny = if (cycleMonths != null) {
            perPersonCycleExact.divide(BigDecimal(cycleMonths), 2, RoundingMode.HALF_UP)
        } else {
            perPersonDailyExact.multiply(DAYS_30).setScale(2, RoundingMode.HALF_UP)
        }

        val perPersonQuarterlyCny = when {
            input.billingCycle == CarpoolBillingCycle.QUARTERLY -> perPersonCycleCostCny
            cycleMonths != null -> perPersonCycleExact
                .multiply(BigDecimal(3))
                .divide(BigDecimal(cycleMonths), 2, RoundingMode.HALF_UP)
            else -> perPersonDailyExact.multiply(DAYS_90).setScale(2, RoundingMode.HALF_UP)
        }

        val perPersonYearlyCny = when {
            input.billingCycle == CarpoolBillingCycle.ANNUAL -> perPersonCycleCostCny
            cycleMonths != null -> perPersonCycleExact
                .multiply(BigDecimal(12))
                .divide(BigDecimal(cycleMonths), 2, RoundingMode.HALF_UP)
            else -> perPersonDailyExact.multiply(DAYS_365).setScale(2, RoundingMode.HALF_UP)
        }

        val remainingSeats = (input.totalSeats - input.occupiedSeats).coerceAtLeast(0)
        val statusTag = CarpoolStatusUpdater.formatStatusTag(
            occupiedSeats = input.occupiedSeats,
            totalSeats = input.totalSeats,
        )

        return CarpoolCalculationOutcome.Success(
            CarpoolCalculationResult(
                serviceName = input.serviceName.trim().ifEmpty { "合租拼车" },
                totalSeats = input.totalSeats,
                occupiedSeats = input.occupiedSeats,
                remainingSeats = remainingSeats,
                payingSeats = payingSeats,
                effectiveCycleDays = effectiveDays,
                billingCycle = input.billingCycle,
                currency = input.currency,
                fxRateToCny = effectiveFxRate,
                totalCycleCostOriginal = input.totalCycleCost.setScale(2, RoundingMode.HALF_UP),
                totalCycleCostCny = totalCycleCostCny,
                perPersonCycleCostCny = perPersonCycleCostCny,
                perPersonMonthlyCny = perPersonMonthlyCny,
                perPersonQuarterlyCny = perPersonQuarterlyCny,
                perPersonYearlyCny = perPersonYearlyCny,
                perPersonDailyCny = perPersonDailyCny,
                perPersonDailyHighPrecisionCny = perPersonDailyExact,
                statusTag = statusTag,
                jumpPenaltyPercent = input.jumpPenaltyPercent.setScale(1, RoundingMode.HALF_UP),
                jumpExitPolicyNote = input.jumpExitPolicyNote.trim().ifEmpty { "跳车按剩余天数折算" },
            ),
        )
    }

    /**
     * Pro-rata daily refund formula (`翻车/跳车按天退费公式`):
     * - `remainingDays = (totalDays - usedDays).coerceIn(0, totalDays)`
     * - `翻车退费 = paid * remainingDays / totalDays`
     * - `跳车退费 = paid * remainingDays / totalDays * (1 - penaltyRate)`
     */
    fun calculateProRataRefund(
        paidAmountCny: BigDecimal,
        totalDays: Int,
        usedDays: Int,
        penaltyPercent: BigDecimal = BigDecimal.ZERO,
    ): CarpoolRefundResult {
        val safeTotalDays = totalDays.coerceAtLeast(1)
        val clampedUsedDays = usedDays.coerceIn(0, safeTotalDays)
        val remainingDays = (safeTotalDays - clampedUsedDays).coerceIn(0, safeTotalDays)
        val safePaid = paidAmountCny.coerceAtLeast(BigDecimal.ZERO)
        val safePaidScaled = safePaid.setScale(2, RoundingMode.HALF_UP)

        val clampedPenaltyPercent = penaltyPercent.coerceIn(BigDecimal.ZERO, HUNDRED)
        val penaltyRate = clampedPenaltyPercent.divide(HUNDRED, INTERMEDIATE_SCALE, RoundingMode.HALF_UP)

        val rawRemaining = safePaid
            .multiply(BigDecimal(remainingDays))
            .divide(BigDecimal(safeTotalDays), INTERMEDIATE_SCALE, RoundingMode.HALF_UP)

        val crashRefundCny = rawRemaining.setScale(2, RoundingMode.HALF_UP)
        val jumpFactor = BigDecimal.ONE.subtract(penaltyRate).coerceAtLeast(BigDecimal.ZERO)
        val jumpRefundCny = rawRemaining.multiply(jumpFactor)
            .setScale(2, RoundingMode.HALF_UP)
            .coerceAtLeast(BigDecimal.ZERO.setScale(2))

        val penaltyDeductedCny = crashRefundCny.subtract(jumpRefundCny)
            .coerceAtLeast(BigDecimal.ZERO.setScale(2))
            .setScale(2, RoundingMode.HALF_UP)

        val usedCostCny = safePaidScaled.subtract(crashRefundCny)
            .coerceAtLeast(BigDecimal.ZERO.setScale(2))
            .setScale(2, RoundingMode.HALF_UP)

        val crashFormula = "¥${safePaidScaled.toPlainString()} × ($remainingDays / $safeTotalDays) = ¥${crashRefundCny.toPlainString()}"
        val jumpFormula = if (clampedPenaltyPercent.compareTo(BigDecimal.ZERO) == 0) {
            crashFormula
        } else {
            val remainPct = HUNDRED.subtract(clampedPenaltyPercent).stripTrailingZeros().toPlainString()
            "¥${safePaidScaled.toPlainString()} × ($remainingDays / $safeTotalDays) × $remainPct% = ¥${jumpRefundCny.toPlainString()}"
        }

        return CarpoolRefundResult(
            paidAmountCny = safePaidScaled,
            totalDays = safeTotalDays,
            usedDays = clampedUsedDays,
            remainingDays = remainingDays,
            usedCostCny = usedCostCny,
            crashRefundCny = crashRefundCny,
            jumpRefundCny = jumpRefundCny,
            penaltyRatePercent = clampedPenaltyPercent.setScale(1, RoundingMode.HALF_UP),
            penaltyDeductedCny = penaltyDeductedCny,
            crashFormulaText = crashFormula,
            jumpFormulaText = jumpFormula,
        )
    }

    fun defaultSeatSlots(
        totalSeats: Int,
        occupiedSeats: Int,
        driverLabel: String = "车主",
    ): List<CarpoolSeatSlot> {
        val safeTotal = totalSeats.coerceIn(2, 32)
        val safeOccupied = occupiedSeats.coerceIn(1, safeTotal)
        return (1..safeTotal).map { idx ->
            when {
                idx == 1 -> CarpoolSeatSlot(
                    seatIndex = 1,
                    memberLabel = driverLabel.ifBlank { "车主" },
                    status = CarpoolSeatStatus.OCCUPIED,
                    note = "主控/发起人",
                )
                idx <= safeOccupied -> CarpoolSeatSlot(
                    seatIndex = idx,
                    memberLabel = "车友 #$idx",
                    status = CarpoolSeatStatus.OCCUPIED,
                    note = "已确认上车",
                )
                else -> CarpoolSeatSlot(
                    seatIndex = idx,
                    memberLabel = "虚位以待",
                    status = CarpoolSeatStatus.OPEN,
                    note = "欢迎私信/回帖上车",
                )
            }
        }
    }
}

object CarpoolMarkdownGenerator {

    fun generateMarkdown(
        result: CarpoolCalculationResult,
        seats: List<CarpoolSeatSlot> = CarpoolCalculator.defaultSeatSlots(result.totalSeats, result.occupiedSeats),
    ): String {
        val safeTitle = escapeMarkdownText(result.serviceName)
        val cycleCostSummary = if (result.currency == ValuationCurrency.CNY) {
            "¥${result.totalCycleCostCny.toPlainString()}"
        } else {
            "${result.currency.symbol}${result.totalCycleCostOriginal.toPlainString()} ${result.currency.code}（约 ¥${result.totalCycleCostCny.toPlainString()}）"
        }

        val refundSample = CarpoolCalculator.calculateProRataRefund(
            paidAmountCny = result.perPersonCycleCostCny,
            totalDays = result.effectiveCycleDays,
            usedDays = result.effectiveCycleDays / 2,
            penaltyPercent = result.jumpPenaltyPercent,
        )

        return buildString {
            appendLine("### 🚗 拼车信息 · $safeTitle")
            appendLine()
            appendLine("**当前状态**：`${result.statusTag}`（共 ${result.totalSeats} 车位，已上车 ${result.occupiedSeats}，空余 ${result.remainingSeats}）")
            appendLine()
            appendLine("- **计费周期与总价**：${result.billingCycle.label}（${result.effectiveCycleDays} 天）· 总价 $cycleCostSummary · 均摊人数 ${result.payingSeats} 人")
            appendLine("- **每人分摊明细**：本周期 **¥${result.perPersonCycleCostCny.toPlainString()}** · 折合每月 **¥${result.perPersonMonthlyCny.toPlainString()}** · 每季 **¥${result.perPersonQuarterlyCny.toPlainString()}** · 每年 **¥${result.perPersonYearlyCny.toPlainString()}**（日均 ¥${result.perPersonDailyCny.toPlainString()}）")
            appendLine("- **翻车按天退费公式**：`退费 = 已付金额 × (剩余天数 / ${result.effectiveCycleDays})`（零违约扣减）")
            appendLine("- **跳车规则说明**：${escapeMarkdownText(result.jumpExitPolicyNote)}（折损 ${result.jumpPenaltyPercent.stripTrailingZeros().toPlainString()}%；例如用满 ${refundSample.usedDays} 天跳车可退 ¥${refundSample.jumpRefundCny.toPlainString()}）")
            appendLine()
            appendLine("| 车位 | 成员 / 状态 | 费用 / 备注 |")
            appendLine("| :--- | :--- | :--- |")
            val normalizedSeats = seats.ifEmpty {
                CarpoolCalculator.defaultSeatSlots(result.totalSeats, result.occupiedSeats)
            }
            normalizedSeats.forEach { slot ->
                val seatCol = "#${slot.seatIndex}"
                val memberCol = "${escapeTableCell(slot.memberLabel)} · ${slot.status.badge}"
                val feeNote = buildString {
                    append("¥${result.perPersonCycleCostCny.toPlainString()}/${result.billingCycle.label}")
                    val cleanNote = slot.note.trim()
                    if (cleanNote.isNotEmpty()) {
                        append(" · ")
                        append(escapeTableCell(cleanNote))
                    }
                }
                appendLine("| $seatCol | $memberCol | $feeNote |")
            }
        }.trimEnd()
    }

    private fun escapeTableCell(raw: String): String =
        raw.trim()
            .replace("\r\n", " ")
            .replace("\r", " ")
            .replace("\n", " ")
            .replace("|", "\\|")

    private fun escapeMarkdownText(raw: String): String =
        raw.trim()
            .replace("\r\n", " ")
            .replace("\r", " ")
            .replace("\n", " ")
}

object CarpoolStatusUpdater {
    const val FULL_CARPOOL_LABEL = "已满车"

    private val BRACKETED_TOKEN_REGEX = Regex(
        """[\[【]\s*(?:(\d{1,2})\s*=\s*(\d{1,2})|已满车|车已满|已发车|满车)\s*[\]】]""",
    )
    private val BARE_NM_TOKEN_REGEX = Regex(
        """(?<![A-Za-z0-9=])(\d{1,2})\s*=\s*(\d{1,2})(?![A-Za-z0-9=])""",
    )
    private val BARE_FULL_TOKEN_REGEX = Regex(
        """(?:已满车|车已满|满车)""",
    )
    private val MARKDOWN_STATUS_LINE_REGEX = Regex(
        """(?m)^(\*\*当前状态\*\*\s*[:：]\s*`)([^`]*)(`.*)$""",
    )
    private val MARKDOWN_STATUS_COUNTS_REGEX = Regex(
        """（共\s*(\d+)\s*车位，已上车\s*(\d+)，空余\s*(\d+)）""",
    )
    private val QUOTE_STATUS_LINE_REGEX = Regex(
        """(?m)^>\s*🚗\s*\*\*车位状态更新\*\*\s*[:：]\s*`[^`]*`.*$""",
    )

    fun formatStatusTag(occupiedSeats: Int, totalSeats: Int): String {
        val safeTotal = totalSeats.coerceAtLeast(1)
        val safeOccupied = occupiedSeats.coerceIn(0, safeTotal)
        val remaining = (safeTotal - safeOccupied).coerceAtLeast(0)
        return if (remaining == 0) FULL_CARPOOL_LABEL else "$safeOccupied=$remaining"
    }

    fun parseStatusToken(text: String): ParsedCarpoolStatus? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        BRACKETED_TOKEN_REGEX.find(trimmed)?.let { m ->
            val left = m.groupValues[1].toIntOrNull()
            val right = m.groupValues[2].toIntOrNull()
            return if (left != null && right != null) {
                ParsedCarpoolStatus(
                    rawToken = "$left=$right",
                    occupiedSeats = left,
                    remainingSeats = right,
                    isFull = right == 0,
                )
            } else {
                ParsedCarpoolStatus(
                    rawToken = FULL_CARPOOL_LABEL,
                    occupiedSeats = null,
                    remainingSeats = 0,
                    isFull = true,
                )
            }
        }
        BARE_NM_TOKEN_REGEX.find(trimmed)?.let { m ->
            val left = m.groupValues[1].toIntOrNull()
            val right = m.groupValues[2].toIntOrNull()
            if (left != null && right != null) {
                return ParsedCarpoolStatus(
                    rawToken = "$left=$right",
                    occupiedSeats = left,
                    remainingSeats = right,
                    isFull = right == 0,
                )
            }
        }
        BARE_FULL_TOKEN_REGEX.find(trimmed)?.let {
            return ParsedCarpoolStatus(
                rawToken = FULL_CARPOOL_LABEL,
                occupiedSeats = null,
                remainingSeats = 0,
                isFull = true,
            )
        }
        return null
    }

    fun updateTitle(title: String, newStatusToken: String): String {
        val cleanTag = newStatusToken.trim().removePrefix("[").removeSuffix("]").removePrefix("【").removeSuffix("】")
        if (cleanTag.isEmpty()) return title.trim()
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) return "[$cleanTag]"

        BRACKETED_TOKEN_REGEX.find(trimmedTitle)?.let { match ->
            val hadChineseBracket = match.value.startsWith("【")
            val replacement = if (hadChineseBracket) "【$cleanTag】" else "[$cleanTag]"
            val prefix = trimmedTitle.substring(0, match.range.first)
            val suffix = trimmedTitle.substring(match.range.last + 1)
                .replace(BRACKETED_TOKEN_REGEX, " ")
                .replace(BARE_NM_TOKEN_REGEX, " ")
                .replace(BARE_FULL_TOKEN_REGEX, " ")
            return "$prefix$replacement$suffix".replace(Regex("""\s{2,}"""), " ").trim()
        }

        BARE_NM_TOKEN_REGEX.find(trimmedTitle)?.let { match ->
            val prefix = trimmedTitle.substring(0, match.range.first)
            val suffix = trimmedTitle.substring(match.range.last + 1)
                .replace(BRACKETED_TOKEN_REGEX, " ")
                .replace(BARE_NM_TOKEN_REGEX, " ")
                .replace(BARE_FULL_TOKEN_REGEX, " ")
            return "$prefix$cleanTag$suffix".replace(Regex("""\s{2,}"""), " ").trim()
        }

        BARE_FULL_TOKEN_REGEX.find(trimmedTitle)?.let { match ->
            val prefix = trimmedTitle.substring(0, match.range.first)
            val suffix = trimmedTitle.substring(match.range.last + 1)
                .replace(BRACKETED_TOKEN_REGEX, " ")
                .replace(BARE_NM_TOKEN_REGEX, " ")
                .replace(BARE_FULL_TOKEN_REGEX, " ")
            return "$prefix$cleanTag$suffix".replace(Regex("""\s{2,}"""), " ").trim()
        }

        return "[$cleanTag] $trimmedTitle".trim()
    }

    fun updateMarkdownStatus(
        markdown: String,
        newStatusToken: String,
        occupiedSeats: Int? = null,
        totalSeats: Int? = null,
    ): String {
        val cleanTag = newStatusToken.trim().removePrefix("[").removeSuffix("]").removePrefix("【").removeSuffix("】")
        if (cleanTag.isEmpty()) return markdown

        var working = markdown
        val statusLineMatch = MARKDOWN_STATUS_LINE_REGEX.find(working)
        if (statusLineMatch != null) {
            val prefix = statusLineMatch.groupValues[1]
            var suffix = statusLineMatch.groupValues[3]
            val existingCountsMatch = MARKDOWN_STATUS_COUNTS_REGEX.find(suffix)
            val existingTotal = existingCountsMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            val parsedTag = parseStatusToken(cleanTag)

            val resolvedTotal: Int?
            val resolvedOccupied: Int?
            if (occupiedSeats != null && totalSeats != null && totalSeats >= 1) {
                resolvedTotal = totalSeats
                resolvedOccupied = if (parsedTag?.isFull == true) totalSeats else occupiedSeats.coerceIn(0, totalSeats)
            } else if (parsedTag?.isFull == true) {
                val inferredTotal = totalSeats?.takeIf { it >= 1 } ?: existingTotal
                resolvedTotal = inferredTotal
                resolvedOccupied = inferredTotal
            } else if (parsedTag?.occupiedSeats != null && parsedTag.remainingSeats != null) {
                val tagTotal = (parsedTag.occupiedSeats + parsedTag.remainingSeats).coerceAtLeast(1)
                resolvedTotal = totalSeats?.takeIf { it >= 1 } ?: tagTotal
                resolvedOccupied = parsedTag.occupiedSeats.coerceIn(0, resolvedTotal)
            } else {
                resolvedTotal = null
                resolvedOccupied = null
            }

            if (resolvedTotal != null && resolvedOccupied != null && resolvedTotal >= 1) {
                val safeOccupied = resolvedOccupied.coerceIn(0, resolvedTotal)
                val remaining = (resolvedTotal - safeOccupied).coerceAtLeast(0)
                val newCounts = "（共 $resolvedTotal 车位，已上车 $safeOccupied，空余 $remaining）"
                suffix = if (existingCountsMatch != null) {
                    suffix.replace(MARKDOWN_STATUS_COUNTS_REGEX, newCounts)
                } else {
                    "$suffix$newCounts"
                }
            }
            working = working.replaceRange(statusLineMatch.range, "$prefix$cleanTag$suffix")
            return working
        }

        val quoteLine = "> 🚗 **车位状态更新**：`$cleanTag`"
        if (QUOTE_STATUS_LINE_REGEX.containsMatchIn(working)) {
            return working.replace(QUOTE_STATUS_LINE_REGEX, quoteLine)
        }

        val trimmed = working.trimEnd()
        return if (trimmed.isEmpty()) {
            quoteLine
        } else {
            "$trimmed\n\n$quoteLine"
        }
    }
}
