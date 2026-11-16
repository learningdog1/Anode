package com.nodeseek.app.domain.valuation

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Default timezone used across NodeSeek VPS valuation calculations (`Asia/Shanghai`).
 */
val VALUATION_ZONE_ID: ZoneId = ZoneId.of("Asia/Shanghai")

/**
 * Supported currencies for VPS renewal and Push fee valuation.
 *
 * All exchange rates use the unified canonical unit: `1 unit of [code] = X CNY`.
 */
enum class ValuationCurrency(
    val code: String,
    val symbol: String,
    val displayNameZh: String,
    val defaultCnyRate: BigDecimal,
    val recommendedRateScale: Int = 4,
) {
    CNY("CNY", "¥", "人民币", BigDecimal("1.0000"), 4),
    USD("USD", "$", "美元", BigDecimal("7.2500"), 4),
    EUR("EUR", "€", "欧元", BigDecimal("7.8500"), 4),
    GBP("GBP", "£", "英镑", BigDecimal("9.1500"), 4),
    HKD("HKD", "HK$", "港元", BigDecimal("0.9300"), 4),
    JPY("JPY", "¥", "日元", BigDecimal("0.047500"), 6),
    SGD("SGD", "S$", "新加坡元", BigDecimal("5.4000"), 4),
    CAD("CAD", "C$", "加元", BigDecimal("5.1800"), 4),
    AUD("AUD", "A$", "澳元", BigDecimal("4.7200"), 4),
    TWD("TWD", "NT$", "新台币", BigDecimal("0.2230"), 4),
    KRW("KRW", "₩", "韩元", BigDecimal("0.005200"), 6),
    CHF("CHF", "CHF", "瑞士法郎", BigDecimal("8.2200"), 4),
    RUB("RUB", "₽", "卢布", BigDecimal("0.074000"), 6),
    ;

    companion object {
        fun fromCode(code: String?): ValuationCurrency? =
            entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) }
    }
}

/**
 * Valuation mode selector.
 */
enum class ValuationMode(val label: String) {
    NET_VALUE_ONLY("净残值折算"),
    TRANSFER_DEAL("交易溢价核算"),
}

/**
 * Billing cycle presets supporting both fixed-day conventions and calendar-accurate natural periods.
 */
enum class BillingCyclePreset(
    val label: String,
    val shortLabel: String,
    val fixedDays: Int?,
) {
    MONTHLY("月付 30天", "月付", 30),
    QUARTERLY("季付 90天", "季付", 90),
    SEMI_ANNUAL("半年 180天", "半年", 180),
    ANNUAL("年付 365天", "年付", 365),
    BIENNIAL("两年 730天", "两年", 730),
    TRIENNIAL("三年 1095天", "三年", 1095),
    CALENDAR_NATURAL("自然周期", "自然周期", null),
    CUSTOM("自定义天数", "自定义", null),
}

/**
 * Calendar span options when [BillingCyclePreset.CALENDAR_NATURAL] is selected.
 */
enum class NaturalCycleSpan(
    val label: String,
    val months: Int,
) {
    MONTH("自然月", 1),
    QUARTER("自然季", 3),
    HALF_YEAR("自然半年", 6),
    YEAR("自然年", 12),
    TWO_YEARS("自然两年", 24),
    THREE_YEARS("自然三年", 36),
}

/**
 * Cost-allocation rule for Push / transfer fees and middleman fees.
 */
enum class FeePayer(val label: String) {
    BUYER("买家承担"),
    SELLER("卖家承担"),
    SPLIT("双方平摊"),
}

/**
 * Middleman fee specification mode.
 */
enum class MiddlemanFeeMode(val label: String) {
    PERCENT("比例 %"),
    FIXED_CNY("固定 CNY"),
}

/**
 * Semantic deal comparison against theoretical net remaining value.
 */
enum class DealSpreadStatus(val label: String) {
    DISCOUNT("折价让利"),
    PAR("平出"),
    PREMIUM("溢价"),
}

/**
 * Markdown export templates for NodePulse VPS valuation.
 */
enum class ValuationMarkdownStyle(
    val label: String,
    val subtitle: String,
) {
    SUMMARY_CARD("精简摘要卡", "适合回帖与快捷报价"),
    THREE_COLUMN_TABLE("三栏核算表", "适合发帖正文展示"),
}

/**
 * Provenance of the active exchange rate for a currency.
 */
enum class FxRateSource(val label: String) {
    OFFLINE_DEFAULT("内置参考"),
    ONLINE_FRESH("在线同步"),
    ONLINE_CACHED("本地缓存"),
    MANUAL_OVERRIDE("手动校准"),
}

/**
 * Complete input specification for [VpsValuationEngine.calculate].
 *
 * All exchange rates follow `1 unit of foreign currency = X CNY`.
 */
data class VpsValuationInput(
    val machineLabel: String = "",
    val mode: ValuationMode = ValuationMode.NET_VALUE_ONLY,
    val renewalAmount: BigDecimal,
    val currency: ValuationCurrency = ValuationCurrency.USD,
    val fxRateToCny: BigDecimal = currency.defaultCnyRate,
    val cyclePreset: BillingCyclePreset = BillingCyclePreset.ANNUAL,
    val naturalCycleSpan: NaturalCycleSpan = NaturalCycleSpan.YEAR,
    val previousRenewalDateTime: LocalDateTime? = null,
    val customCycleDays: Int? = null,
    val valuationDateTime: LocalDateTime,
    val expiryDateTime: LocalDateTime,
    val preciseToMinute: Boolean = false,
    // Transfer Deal fields
    val askingPriceCny: BigDecimal = BigDecimal.ZERO,
    val pushFeeAmount: BigDecimal = BigDecimal.ZERO,
    val pushFeeCurrency: ValuationCurrency = ValuationCurrency.CNY,
    val pushFeeToCnyRate: BigDecimal = pushFeeCurrency.defaultCnyRate,
    val pushFeePayer: FeePayer = FeePayer.BUYER,
    val middlemanEnabled: Boolean = false,
    val middlemanFeeMode: MiddlemanFeeMode = MiddlemanFeeMode.PERCENT,
    val middlemanFeeValue: BigDecimal = BigDecimal.ZERO,
    val middlemanFeePayer: FeePayer = FeePayer.BUYER,
)

/**
 * Detailed buyer/seller breakdown when [ValuationMode.TRANSFER_DEAL] is active.
 */
data class TransferDealBreakdown(
    val askingPriceCny: BigDecimal,
    val pushFeeOriginal: BigDecimal,
    val pushFeeCurrency: ValuationCurrency,
    val pushFeeTotalCny: BigDecimal,
    val pushFeePayer: FeePayer,
    val buyerPushFeeCny: BigDecimal,
    val sellerPushFeeCny: BigDecimal,
    val middlemanEnabled: Boolean,
    val middlemanFeeMode: MiddlemanFeeMode,
    val middlemanFeeValue: BigDecimal,
    val middlemanFeeTotalCny: BigDecimal,
    val middlemanFeePayer: FeePayer,
    val buyerMiddlemanFeeCny: BigDecimal,
    val sellerMiddlemanFeeCny: BigDecimal,
    val buyerTotalCostCny: BigDecimal,
    val sellerNetProceedsCny: BigDecimal,
    val buyerPremiumCny: BigDecimal,
    val sellerPremiumCny: BigDecimal,
    val buyerPremiumPercent: BigDecimal?,
    val spreadStatus: DealSpreadStatus,
    val buyerEffectiveDailyCostCny: BigDecimal?,
    val buyerEffectiveDailyCostHighPrecisionCny: BigDecimal?,
)

/**
 * Complete financial valuation result calculated by [VpsValuationEngine].
 */
data class VpsValuationResult(
    val machineLabel: String,
    val mode: ValuationMode,
    val currency: ValuationCurrency,
    val renewalAmountOriginal: BigDecimal,
    val fxRateToCny: BigDecimal,
    val cyclePreset: BillingCyclePreset,
    val cycleDescription: String,
    val effectiveCycleDays: BigDecimal,
    val resolvedCycleStartDateTime: LocalDateTime,
    val valuationDateTime: LocalDateTime,
    val expiryDateTime: LocalDateTime,
    val preciseToMinute: Boolean,
    val isExpired: Boolean,
    val remainingSeconds: Long,
    val remainingDays: BigDecimal,
    val remainingRatio: Float,
    val cycleRenewalCny: BigDecimal,
    val dailyOfficialCostCny: BigDecimal,
    val dailyOfficialCostHighPrecisionCny: BigDecimal,
    val remainingValueOriginal: BigDecimal,
    val remainingValueCny: BigDecimal,
    val transferBreakdown: TransferDealBreakdown? = null,
)

/**
 * Result wrapper distinguishing valid financial calculations from user input validation issues.
 */
sealed interface VpsValuationOutcome {
    data class Success(val result: VpsValuationResult) : VpsValuationOutcome
    data class Invalid(val errors: List<String>) : VpsValuationOutcome
}
