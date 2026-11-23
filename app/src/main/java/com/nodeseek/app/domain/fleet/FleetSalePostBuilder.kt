package com.nodeseek.app.domain.fleet

import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.DealSpreadStatus
import com.nodeseek.app.domain.valuation.FeePayer
import com.nodeseek.app.domain.valuation.MiddlemanFeeMode
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.domain.valuation.ValuationMarkdownStyle
import com.nodeseek.app.domain.valuation.ValuationMode
import com.nodeseek.app.domain.valuation.VpsValuationEngine
import com.nodeseek.app.domain.valuation.VpsValuationInput
import com.nodeseek.app.domain.valuation.VpsValuationOutcome
import com.nodeseek.app.domain.valuation.VpsValuationResult
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.util.Locale

/**
 * Input configuration for generating a ready-to-publish NodeSeek `trade` board VPS sale post.
 */
data class FleetSalePostRequest(
    val asset: VpsAssetRecord,
    val probeSnapshot: ProbeServerSnapshot? = null,
    val fxRateToCny: BigDecimal = asset.currency.defaultCnyRate,
    val fxSourceNote: String = "参考汇率",
    val askingPriceCny: BigDecimal? = null,
    val pushFeeAmount: BigDecimal = BigDecimal.ZERO,
    val pushFeeCurrency: ValuationCurrency = ValuationCurrency.CNY,
    val pushFeeToCnyRate: BigDecimal = pushFeeCurrency.defaultCnyRate,
    val pushFeeBearer: PushFeeBearer = PushFeeBearer.BUYER,
    val middlemanEnabled: Boolean = false,
    val middlemanFeeMode: MiddlemanFeeMode = MiddlemanFeeMode.PERCENT,
    val middlemanFeeValue: BigDecimal = BigDecimal.ZERO,
    val middlemanFeePayer: FeePayer = FeePayer.BUYER,
    val deliveryMethod: FleetDeliveryMethod = FleetDeliveryMethod.PUSH,
    val includeProbeTelemetry: Boolean = true,
    val extraNote: String = "",
    val markdownStyle: ValuationMarkdownStyle = ValuationMarkdownStyle.THREE_COLUMN_TABLE,
    val valuationDateTime: LocalDateTime = VpsValuationEngine.defaultValuationDateTime(preciseToMinute = false),
)

/**
 * Generated sale post title, NodeSeek Markdown body, and underlying [VpsValuationResult].
 */
data class FleetSalePostResult(
    val title: String,
    val markdownBody: String,
    val valuationResult: VpsValuationResult,
    val effectiveAskingPriceCny: BigDecimal,
)

/**
 * Outcome wrapper for [FleetSalePostBuilder.build].
 */
sealed interface FleetSalePostOutcome {
    data class Success(val result: FleetSalePostResult) : FleetSalePostOutcome
    data class Invalid(val errors: List<String>) : FleetSalePostOutcome
}

/**
 * Builds complete NodeSeek `trade` sale posts (title + Markdown body) by pairing [VpsAssetRecord]
 * ledger data and optional live [ProbeServerSnapshot] telemetry with [VpsValuationEngine].
 */
object FleetSalePostBuilder {

    fun build(request: FleetSalePostRequest): FleetSalePostOutcome {
        val asset = request.asset
        val errors = mutableListOf<String>()

        val renewalAmount = asset.parsedRenewalAmount
        if (renewalAmount == null) {
            errors += "续费金额无效，请填写大于 0 的数字"
        }

        val expiryDate = FleetPortfolioCalculator.parseExpiryDate(asset.expiryDateIso)
        if (expiryDate == null) {
            errors += "到期日期格式需为 yyyy-MM-dd"
        }

        if (errors.isNotEmpty()) {
            return FleetSalePostOutcome.Invalid(errors)
        }

        val safeRenewal = renewalAmount!!
        val safeExpiryDateTime = expiryDate!!.atStartOfDay()
        val machineLabel = buildMachineHeadline(asset)

        // First calculate pure remaining value so we can default askingPriceCny to net remaining value when omitted.
        val netOnlyOutcome = VpsValuationEngine.calculate(
            VpsValuationInput(
                machineLabel = machineLabel,
                mode = ValuationMode.NET_VALUE_ONLY,
                renewalAmount = safeRenewal,
                currency = asset.currency,
                fxRateToCny = request.fxRateToCny,
                cyclePreset = asset.cyclePreset,
                customCycleDays = asset.customCycleDays,
                valuationDateTime = request.valuationDateTime,
                expiryDateTime = safeExpiryDateTime,
                preciseToMinute = false,
            ),
        )
        if (netOnlyOutcome is VpsValuationOutcome.Invalid) {
            return FleetSalePostOutcome.Invalid(netOnlyOutcome.errors)
        }
        val baseRemainingValueCny = (netOnlyOutcome as VpsValuationOutcome.Success).result.remainingValueCny

        val effectiveAskingPriceCny = (request.askingPriceCny ?: baseRemainingValueCny)
            .setScale(2, RoundingMode.HALF_UP)
        val effectivePushFee = if (request.pushFeeBearer == PushFeeBearer.FREE) {
            BigDecimal.ZERO
        } else {
            request.pushFeeAmount
        }

        val transferOutcome = VpsValuationEngine.calculate(
            VpsValuationInput(
                machineLabel = machineLabel,
                mode = ValuationMode.TRANSFER_DEAL,
                renewalAmount = safeRenewal,
                currency = asset.currency,
                fxRateToCny = request.fxRateToCny,
                cyclePreset = asset.cyclePreset,
                customCycleDays = asset.customCycleDays,
                valuationDateTime = request.valuationDateTime,
                expiryDateTime = safeExpiryDateTime,
                preciseToMinute = false,
                askingPriceCny = effectiveAskingPriceCny,
                pushFeeAmount = effectivePushFee,
                pushFeeCurrency = request.pushFeeCurrency,
                pushFeeToCnyRate = request.pushFeeToCnyRate,
                pushFeePayer = request.pushFeeBearer.feePayer,
                middlemanEnabled = request.middlemanEnabled,
                middlemanFeeMode = request.middlemanFeeMode,
                middlemanFeeValue = request.middlemanFeeValue,
                middlemanFeePayer = request.middlemanFeePayer,
            ),
        )
        if (transferOutcome is VpsValuationOutcome.Invalid) {
            return FleetSalePostOutcome.Invalid(transferOutcome.errors)
        }

        val valuationResult = (transferOutcome as VpsValuationOutcome.Success).result
        val title = buildSuggestedTitle(
            asset = asset,
            valuationResult = valuationResult,
            effectiveAskingPriceCny = effectiveAskingPriceCny,
        )
        val markdownBody = buildMarkdownBody(
            request = request,
            valuationResult = valuationResult,
        )

        return FleetSalePostOutcome.Success(
            FleetSalePostResult(
                title = title,
                markdownBody = markdownBody,
                valuationResult = valuationResult,
                effectiveAskingPriceCny = effectiveAskingPriceCny,
            ),
        )
    }

    internal fun buildMachineHeadline(asset: VpsAssetRecord): String {
        val parts = buildList {
            if (asset.providerName.isNotBlank()) add(asset.providerName.trim())
            if (asset.region.isNotBlank()) add(asset.region.trim())
            if (asset.specsSummary.isNotBlank()) add(asset.specsSummary.trim())
        }
        return if (parts.isNotEmpty()) {
            parts.joinToString(" ")
        } else {
            asset.nickname.trim().ifBlank { "VPS" }
        }
    }

    internal fun buildSuggestedTitle(
        asset: VpsAssetRecord,
        valuationResult: VpsValuationResult,
        effectiveAskingPriceCny: BigDecimal,
    ): String {
        val header = buildMachineHeadline(asset)
        val expiryStr = asset.expiryDateIso.trim()
        val deal = valuationResult.transferBreakdown
        val askingStr = effectiveAskingPriceCny.setScale(2, RoundingMode.HALF_UP).toPlainString()
        val remStr = valuationResult.remainingValueCny.setScale(2, RoundingMode.HALF_UP).toPlainString()

        return when {
            deal == null || deal.spreadStatus == DealSpreadStatus.PAR -> {
                "[出] $header 剩余价值 ¥$askingStr 出 (到期 $expiryStr)"
            }
            deal.spreadStatus == DealSpreadStatus.DISCOUNT -> {
                "[出] $header 剩余价值 ¥$remStr · 折价 ¥$askingStr 出 (到期 $expiryStr)"
            }
            else -> {
                "[出] $header 剩余价值 ¥$remStr · ¥$askingStr 出 (到期 $expiryStr)"
            }
        }
    }

    private fun buildMarkdownBody(
        request: FleetSalePostRequest,
        valuationResult: VpsValuationResult,
    ): String {
        val asset = request.asset
        val deal = valuationResult.transferBreakdown
        val valuationMarkdown = VpsValuationEngine.formatMarkdown(
            result = valuationResult,
            style = request.markdownStyle,
            fxSourceNote = request.fxSourceNote,
        )

        return buildString {
            append("### 机器配置与账单信息\n\n")
            if (asset.nickname.isNotBlank()) {
                append("- **机器名称**：").append(asset.nickname.trim()).append("\n")
            }
            if (asset.providerName.isNotBlank()) {
                append("- **商家 / 品牌**：").append(asset.providerName.trim()).append("\n")
            }
            val locationLine = buildList {
                if (asset.region.isNotBlank()) add(asset.region.trim())
                val routeSummary = asset.resolvedRouteTagSummary
                if (routeSummary.isNotBlank()) add("线路：$routeSummary")
            }.joinToString(" · ")
            if (locationLine.isNotEmpty()) {
                append("- **机房 / 线路**：").append(locationLine).append("\n")
            }
            if (asset.specsSummary.isNotBlank()) {
                append("- **硬件配置**：").append(asset.specsSummary.trim()).append("\n")
            }
            asset.monthlyTrafficQuotaGb?.takeIf { it > 0.0 }?.let { quotaGb ->
                val formattedQuota = if (quotaGb >= 1000.0 && quotaGb % 1000.0 == 0.0) {
                    "${(quotaGb / 1000.0).toInt()} TB / 月"
                } else if (quotaGb % 1.0 == 0.0) {
                    "${quotaGb.toInt()} GB / 月"
                } else {
                    String.format(Locale.US, "%.1f GB / 月", quotaGb)
                }
                append("- **月流量配额**：").append(formattedQuota).append("\n")
            }

            append("- **续费价格**：")
            if (valuationResult.currency == ValuationCurrency.CNY) {
                append("¥").append(valuationResult.cycleRenewalCny.toPlainString())
            } else {
                append(valuationResult.currency.symbol)
                    .append(valuationResult.renewalAmountOriginal.toPlainString())
                    .append(" ").append(valuationResult.currency.code)
                    .append("（约 ¥").append(valuationResult.cycleRenewalCny.toPlainString()).append("）")
            }
            append(" / ").append(valuationResult.cycleDescription).append("\n")

            append("- **到期时间**：").append(asset.expiryDateIso.trim())
            if (valuationResult.isExpired) {
                append("（已到期）\n")
            } else {
                append("（剩余 ")
                    .append(VpsValuationEngine.formatDaysNumber(valuationResult.remainingDays))
                    .append(" 天）\n")
            }

            if (asset.autoRenewNote.isNotBlank()) {
                append("- **续费备注**：").append(asset.autoRenewNote.trim()).append("\n")
            }

            // Optional live probe telemetry section (never leaks panel URL or token)
            val snapshot = request.probeSnapshot
            if (snapshot != null && request.includeProbeTelemetry) {
                append("\n### 探针实时运行参考\n\n")
                val statusLabel = if (snapshot.isOnline) "在线运行中" else "当前离线"
                append("- **当前状态**：").append(statusLabel)
                if (snapshot.uptimeSeconds > 0L) {
                    append(" · 持续在线 ").append(formatUptimeChinese(snapshot.uptimeSeconds))
                }
                append("\n")
                if (snapshot.os.isNotBlank()) {
                    append("- **系统环境**：").append(snapshot.os.trim()).append("\n")
                }
                val resourceParts = buildList {
                    val safeCpu = snapshot.cpuPercent.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 0.0
                    add(String.format(Locale.US, "CPU %.1f%%", safeCpu))
                    if (snapshot.memTotalBytes > 0L) {
                        add("内存 ${formatBytesBinary(snapshot.memUsedBytes)} / ${formatBytesBinary(snapshot.memTotalBytes)}")
                    }
                    if (snapshot.diskTotalBytes > 0L) {
                        add("硬盘 ${formatBytesBinary(snapshot.diskUsedBytes)} / ${formatBytesBinary(snapshot.diskTotalBytes)}")
                    }
                }
                if (resourceParts.isNotEmpty()) {
                    append("- **资源占用**：").append(resourceParts.joinToString(" · ")).append("\n")
                }
                if (snapshot.totalMonthlyTransferBytes > 0L) {
                    append("- **周期已用流量**：")
                        .append("↓ ").append(formatBytesBinary(snapshot.monthlyTransferInBytes))
                        .append(" / ↑ ").append(formatBytesBinary(snapshot.monthlyTransferOutBytes))
                        .append("（合计 ").append(formatBytesBinary(snapshot.totalMonthlyTransferBytes)).append("）\n")
                }
            }

            // VpsValuationEngine remaining-value & spread calculation
            append("\n### 剩余价值与溢折价核算\n\n")
            append(valuationMarkdown).append("\n")

            if (deal != null) {
                append("\n> **报价结论**：明盘 **¥")
                    .append(deal.askingPriceCny.toPlainString())
                    .append("** · 评估状态：**")
                    .append(deal.spreadStatus.label)
                if (deal.spreadStatus != DealSpreadStatus.PAR) {
                    append(" ").append(VpsValuationEngine.formatSignedCny(deal.buyerPremiumCny))
                    deal.buyerPremiumPercent?.let { pct ->
                        append(" (").append(VpsValuationEngine.formatSignedPercent(pct)).append(")")
                    }
                }
                append("** · 买家实付 **¥")
                    .append(deal.buyerTotalCostCny.toPlainString())
                    .append("**。\n")
            }

            // Delivery & Push instructions
            append("\n### 过户与交易说明\n\n")
            append("- **交付方式**：").append(request.deliveryMethod.label)
                .append(" — ").append(request.deliveryMethod.instructionText).append("\n")
            if (deal != null && deal.pushFeeTotalCny > BigDecimal.ZERO) {
                append("- **Push / 过户费**：¥").append(deal.pushFeeTotalCny.toPlainString())
                if (deal.pushFeeCurrency != ValuationCurrency.CNY) {
                    append("（原币 ")
                        .append(deal.pushFeeOriginal.toPlainString())
                        .append(" ")
                        .append(deal.pushFeeCurrency.code)
                        .append("）")
                }
                append("，由 **").append(request.pushFeeBearer.label).append("**")
                if (request.pushFeeBearer == PushFeeBearer.SPLIT) {
                    append("（买家 ¥").append(deal.buyerPushFeeCny.toPlainString())
                        .append(" / 卖家 ¥").append(deal.sellerPushFeeCny.toPlainString()).append("）")
                }
                append("\n")
            } else {
                append("- **Push / 过户费**：无额外 Push 费用\n")
            }
            if (deal != null && deal.middlemanEnabled && deal.middlemanFeeTotalCny > BigDecimal.ZERO) {
                append("- **中介担保**：支持论坛中介，手续费约 ¥")
                    .append(deal.middlemanFeeTotalCny.toPlainString())
                    .append("（").append(deal.middlemanFeePayer.label).append("）\n")
            } else {
                append("- **交易方式**：先款 / 论坛中介均可，欢迎站内私信联系。\n")
            }

            val noteText = request.extraNote.trim().ifBlank { asset.notes.trim() }
            if (noteText.isNotEmpty()) {
                append("- **补充说明**：").append(noteText).append("\n")
            }
        }.trimEnd()
    }
}
