package com.nodeseek.app.domain.deals

import androidx.compose.runtime.Immutable
import com.nodeseek.app.domain.valuation.ValuationCurrency
import kotlinx.coroutines.flow.StateFlow
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Origin channel of a Black Friday / promo VPS deal item.
 */
enum class DealSource(
    val displayLabel: String,
    val badgeLabel: String,
) {
    NODESEEK("站内优惠", "NodeSeek"),
    OVERSEAS_LET("LowEndTalk", "LET"),
    OVERSEAS_LEB("LowEndBox", "LEB"),
    ;

    val isOverseas: Boolean
        get() = this == OVERSEAS_LET || this == OVERSEAS_LEB
}

/**
 * Primary source tabs shown in `DealsHubScreen`.
 */
enum class DealSourceTab(val label: String) {
    ALL("全部大促"),
    NODESEEK("站内优惠"),
    OVERSEAS("海外情报 LET·LEB"),
}

/**
 * Canonical Chinese region and network route tags used across extraction and filtering.
 */
object DealTags {
    const val REGION_HK = "香港"
    const val REGION_JP = "日本"
    const val REGION_SG = "新加坡"
    const val REGION_US_WEST = "美西"
    const val REGION_US_EAST = "美东"
    const val REGION_EU = "欧洲"
    const val REGION_OTHER = "其他"

    val ALL_REGIONS: List<String> = listOf(
        REGION_HK,
        REGION_JP,
        REGION_SG,
        REGION_US_WEST,
        REGION_US_EAST,
        REGION_EU,
        REGION_OTHER,
    )

    const val ROUTE_CN2_GIA = "CN2 GIA"
    const val ROUTE_9929 = "9929"
    const val ROUTE_CMIN2 = "CMIN2"
    const val ROUTE_IIJ = "IIJ"
    const val ROUTE_SOFTBANK = "软银"
    const val ROUTE_BGP = "BGP"

    val ALL_ROUTES: List<String> = listOf(
        ROUTE_CN2_GIA,
        ROUTE_9929,
        ROUTE_CMIN2,
        ROUTE_IIJ,
        ROUTE_SOFTBANK,
        ROUTE_BGP,
    )
}

/**
 * Normalized billing cycle used for annualizing recurring VPS promo prices.
 */
enum class DealBillingCycle(
    val label: String,
    val shortUnit: String,
    private val annualNumerator: BigDecimal?,
    private val annualDenominator: BigDecimal?,
) {
    MONTHLY("月付", "月", BigDecimal("12"), BigDecimal.ONE),
    QUARTERLY("季付", "季", BigDecimal("4"), BigDecimal.ONE),
    SEMI_ANNUAL("半年付", "半年", BigDecimal("2"), BigDecimal.ONE),
    ANNUAL("年付", "年", BigDecimal.ONE, BigDecimal.ONE),
    BIENNIAL("两年付", "2年", BigDecimal.ONE, BigDecimal("2")),
    TRIENNIAL("三年付", "3年", BigDecimal.ONE, BigDecimal("3")),
    ONE_TIME("一次性", "次", null, null),
    UNKNOWN("", "", null, null),
    ;

    fun annualize(amount: BigDecimal): BigDecimal? {
        val num = annualNumerator ?: return null
        val den = annualDenominator ?: return null
        if (amount <= BigDecimal.ZERO) return null
        return amount.multiply(num).divide(den, 6, RoundingMode.HALF_UP)
    }

    val isAnnualOrLonger: Boolean
        get() = this == ANNUAL || this == BIENNIAL || this == TRIENNIAL

    companion object {
        fun fromLabel(label: String?): DealBillingCycle {
            val normalized = label?.trim().orEmpty()
            return entries.firstOrNull { it.label == normalized } ?: when {
                normalized.contains("三年") || normalized.contains("3年") -> TRIENNIAL
                normalized.contains("两年") || normalized.contains("2年") -> BIENNIAL
                normalized.contains("半年") -> SEMI_ANNUAL
                normalized.contains("年") -> ANNUAL
                normalized.contains("季") -> QUARTERLY
                normalized.contains("月") -> MONTHLY
                normalized.contains("一次") -> ONE_TIME
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Structured Black Friday / Promo VPS Deal card model shared by NodeSeek & Overseas Intel feeds.
 */
@Immutable
data class PromoDealCardModel(
    val id: String,
    val source: DealSource,
    val topicId: Long? = null,
    val sourceUrl: String,
    val orderUrl: String? = null,
    val merchantName: String = "",
    val title: String,
    val translatedSummary: String = "",
    val rawPriceText: String? = null,
    val priceAmount: BigDecimal? = null,
    val currency: ValuationCurrency? = null,
    val annualizedUsd: BigDecimal? = null,
    val annualizedCny: BigDecimal? = null,
    val billingCycleLabel: String = "",
    val cpuCores: Int? = null,
    val ramText: String? = null,
    val storageText: String? = null,
    val trafficText: String? = null,
    val portSpeedText: String? = null,
    val regionTags: List<String> = emptyList(),
    val routeTags: List<String> = emptyList(),
    val promoCode: String? = null,
    val expiresAtEpochMillis: Long? = null,
    val publishedAtEpochMillis: Long = 0L,
    val replyCount: Int = 0,
    val isOutOfStockHint: Boolean = false,
    val authorName: String = "",
    val categorySlug: String = "",
    val viewCount: Int = 0,
    val isDetailEnriched: Boolean = false,
) {
    val specBadges: List<String>
        get() = buildList {
            cpuCores?.takeIf { it > 0 }?.let { add("${it}C") }
            ramText?.takeIf { it.isNotBlank() }?.let(::add)
            storageText?.takeIf { it.isNotBlank() }?.let(::add)
            trafficText?.takeIf { it.isNotBlank() }?.let(::add)
            portSpeedText?.takeIf { it.isNotBlank() }?.let(::add)
        }

    val specsSummaryLine: String
        get() = specBadges.joinToString(" · ")

    val isAnnualPromo: Boolean
        get() = DealBillingCycle.fromLabel(billingCycleLabel).isAnnualOrLonger

    val isUltraValueDeal: Boolean
        get() = annualizedUsd != null && annualizedUsd > BigDecimal.ZERO && annualizedUsd <= ULTRA_VALUE_USD_THRESHOLD

    val primaryPriceLabel: String?
        get() {
            if (annualizedUsd != null) {
                val usdStr = "$${formatCompactDecimal(annualizedUsd)}/年"
                val cnyStr = annualizedCny?.let { "≈ ¥${formatWholeOrTwoDecimals(it)}" }
                return if (cnyStr != null) "$usdStr · $cnyStr" else usdStr
            }
            if (priceAmount != null && currency != null) {
                val base = "${currency.symbol}${formatCompactDecimal(priceAmount)}"
                val cycle = billingCycleLabel.takeIf { it.isNotBlank() }?.let { "/$it" }.orEmpty()
                return "$base$cycle"
            }
            return rawPriceText?.takeIf { it.isNotBlank() }
        }

    val restockPriceHint: String
        get() = rawPriceText?.takeIf { it.isNotBlank() }
            ?: primaryPriceLabel
            ?: ""

    companion object {
        val ULTRA_VALUE_USD_THRESHOLD: BigDecimal = BigDecimal("15.00")

        fun formatCompactDecimal(value: BigDecimal): String {
            val scaled = value.setScale(2, RoundingMode.HALF_UP)
            val stripped = scaled.stripTrailingZeros()
            return if (stripped.scale() <= 0) {
                stripped.setScale(0, RoundingMode.HALF_UP).toPlainString()
            } else {
                scaled.toPlainString()
            }
        }

        fun formatWholeOrTwoDecimals(value: BigDecimal): String {
            val scaled = value.setScale(2, RoundingMode.HALF_UP)
            return if (scaled >= BigDecimal("100")) {
                scaled.setScale(0, RoundingMode.HALF_UP).toPlainString()
            } else {
                formatCompactDecimal(scaled)
            }
        }
    }
}

/**
 * Snapshot emitted by [DealsRepository] holding aggregated deals and FX telemetry.
 */
@Immutable
data class DealsFeedSnapshot(
    val deals: List<PromoDealCardModel> = emptyList(),
    val updatedAtEpochMillis: Long = 0L,
    val fxUsdToCnyRate: BigDecimal = ValuationCurrency.USD.defaultCnyRate,
    val fxProviderDate: String? = null,
    val isFromCache: Boolean = false,
    val warningMessage: String? = null,
)

/**
 * Plug-in bridge allowing Feature 7 (`overseas`) or any external promo source to inject
 * structured [PromoDealCardModel] items directly into the Black Friday Deals Hub.
 */
fun interface ExternalDealsBridge {
    suspend fun fetchExternalDeals(): List<PromoDealCardModel>

    companion object {
        val EMPTY: ExternalDealsBridge = ExternalDealsBridge { emptyList() }
    }
}

/**
 * Repository contract for aggregating NodeSeek & external Black Friday / Promo deals.
 */
interface DealsRepository {
    val dealsState: StateFlow<DealsFeedSnapshot>

    suspend fun loadCachedDeals(): DealsFeedSnapshot

    suspend fun refreshDeals(force: Boolean = false): Result<DealsFeedSnapshot>
}
