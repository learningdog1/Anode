package com.nodeseek.app.domain.deals

import androidx.compose.runtime.Immutable
import java.math.BigDecimal
import java.util.Locale

/**
 * Annualized USD ceiling quick-filter options.
 */
enum class DealPriceCeiling(
    val label: String,
    val maxAnnualUsd: BigDecimal?,
) {
    NONE("不限价格", null),
    UNDER_15("≤ $15/年", BigDecimal("15.00")),
    UNDER_20("≤ $20/年", BigDecimal("20.00")),
    UNDER_35("≤ $35/年", BigDecimal("35.00")),
}

/**
 * Preset datacenter/region filter chips.
 */
enum class DealRegionPreset(
    val label: String,
    val matchingTags: Set<String>,
) {
    HK_JP("香港 / 日本", setOf(DealTags.REGION_HK, DealTags.REGION_JP)),
    US_WEST("美西", setOf(DealTags.REGION_US_WEST)),
    SINGAPORE("新加坡", setOf(DealTags.REGION_SG)),
    EUROPE("欧洲", setOf(DealTags.REGION_EU)),
    US_EAST("美东", setOf(DealTags.REGION_US_EAST)),
}

/**
 * Preset optimized network route filter chips.
 */
enum class DealRoutePreset(
    val label: String,
    val matchingTags: Set<String>,
) {
    CN2_GIA("CN2 GIA", setOf(DealTags.ROUTE_CN2_GIA)),
    CU_CMIN2("9929 / CMIN2", setOf(DealTags.ROUTE_9929, DealTags.ROUTE_CMIN2)),
    IIJ_SOFTBANK("IIJ / 软银", setOf(DealTags.ROUTE_IIJ, DealTags.ROUTE_SOFTBANK)),
    BGP("BGP", setOf(DealTags.ROUTE_BGP)),
}

/**
 * Sort options for the Black Friday / Major Promo Hub.
 */
enum class DealSortOption(val label: String) {
    LATEST("最新上线"),
    LOWEST_ANNUAL_PRICE("年付最低价"),
    HOTTEST("社区热度"),
    EXPIRING_SOON("即将截止"),
}

/**
 * Complete multi-criteria filter & sort state for `DealsHubScreen`.
 */
@Immutable
data class DealFilterState(
    val sourceTab: DealSourceTab = DealSourceTab.ALL,
    val priceCeiling: DealPriceCeiling = DealPriceCeiling.NONE,
    val annualOnly: Boolean = false,
    val selectedRegions: Set<DealRegionPreset> = emptySet(),
    val selectedRoutes: Set<DealRoutePreset> = emptySet(),
    val requirePromoCode: Boolean = false,
    val requireOrderUrl: Boolean = false,
    val hideOutOfStock: Boolean = false,
    val keyword: String = "",
    val sortOption: DealSortOption = DealSortOption.LATEST,
) {
    val activeCriteriaCount: Int
        get() {
            var count = 0
            if (priceCeiling != DealPriceCeiling.NONE) count++
            if (annualOnly) count++
            count += selectedRegions.size
            count += selectedRoutes.size
            if (requirePromoCode) count++
            if (requireOrderUrl) count++
            if (hideOutOfStock) count++
            if (keyword.isNotBlank()) count++
            return count
        }

    val hasActiveFilters: Boolean
        get() = activeCriteriaCount > 0

    fun resetCriteriaKeepingTabAndSort(): DealFilterState = DealFilterState(
        sourceTab = sourceTab,
        sortOption = sortOption,
    )
}

/**
 * Aggregated header stats across the unfiltered deals pool.
 */
@Immutable
data class DealsHubSummaryStats(
    val totalCount: Int = 0,
    val nodeseekCount: Int = 0,
    val overseasCount: Int = 0,
    val couponDealCount: Int = 0,
    val lowestAnnualDeal: PromoDealCardModel? = null,
)

/**
 * Pure filtering, sorting, and statistics engine for [PromoDealCardModel] lists.
 */
object DealFilterEngine {

    fun summarize(deals: List<PromoDealCardModel>): DealsHubSummaryStats {
        var nodeseekCount = 0
        var overseasCount = 0
        var couponCount = 0
        var lowestDeal: PromoDealCardModel? = null

        for (deal in deals) {
            if (deal.source == DealSource.NODESEEK) {
                nodeseekCount++
            } else {
                overseasCount++
            }
            if (!deal.promoCode.isNullOrBlank()) {
                couponCount++
            }
            val annual = deal.annualizedUsd
            if (!deal.isOutOfStockHint && annual != null && annual > BigDecimal.ZERO) {
                val currentLowest = lowestDeal?.annualizedUsd
                if (currentLowest == null || annual < currentLowest) {
                    lowestDeal = deal
                }
            }
        }

        // Fall back to any deal with annualizedUsd if all priced deals had OOS hints
        if (lowestDeal == null) {
            lowestDeal = deals
                .filter { it.annualizedUsd != null && it.annualizedUsd > BigDecimal.ZERO }
                .minByOrNull { it.annualizedUsd!! }
        }

        return DealsHubSummaryStats(
            totalCount = deals.size,
            nodeseekCount = nodeseekCount,
            overseasCount = overseasCount,
            couponDealCount = couponCount,
            lowestAnnualDeal = lowestDeal,
        )
    }

    fun apply(
        deals: List<PromoDealCardModel>,
        filter: DealFilterState,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): List<PromoDealCardModel> {
        val requiredRegionTags: Set<String> = filter.selectedRegions
            .flatMap { it.matchingTags }
            .toSet()
        val requiredRouteTags: Set<String> = filter.selectedRoutes
            .flatMap { it.matchingTags }
            .toSet()
        val keywordTokens: List<String> = filter.keyword
            .trim()
            .lowercase(Locale.ROOT)
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }

        val filtered = deals.filter { deal ->
            matchesSourceTab(deal, filter.sourceTab) &&
                matchesPriceCeiling(deal, filter.priceCeiling) &&
                (!filter.annualOnly || deal.isAnnualPromo) &&
                (requiredRegionTags.isEmpty() || deal.regionTags.any { it in requiredRegionTags }) &&
                (requiredRouteTags.isEmpty() || deal.routeTags.any { it in requiredRouteTags }) &&
                (!filter.requirePromoCode || !deal.promoCode.isNullOrBlank()) &&
                (!filter.requireOrderUrl || !deal.orderUrl.isNullOrBlank()) &&
                (!filter.hideOutOfStock || !deal.isOutOfStockHint) &&
                matchesKeywords(deal, keywordTokens)
        }

        return sortDeals(filtered, filter.sortOption, nowEpochMillis)
    }

    private fun matchesSourceTab(deal: PromoDealCardModel, tab: DealSourceTab): Boolean = when (tab) {
        DealSourceTab.ALL -> true
        DealSourceTab.NODESEEK -> deal.source == DealSource.NODESEEK
        DealSourceTab.OVERSEAS -> deal.source.isOverseas
    }

    private fun matchesPriceCeiling(deal: PromoDealCardModel, ceiling: DealPriceCeiling): Boolean {
        val maxUsd = ceiling.maxAnnualUsd ?: return true
        val annualUsd = deal.annualizedUsd ?: return false
        return annualUsd > BigDecimal.ZERO && annualUsd <= maxUsd
    }

    private fun matchesKeywords(deal: PromoDealCardModel, tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return true
        val haystack = buildString {
            append(deal.title).append(' ')
            append(deal.translatedSummary).append(' ')
            append(deal.merchantName).append(' ')
            append(deal.promoCode.orEmpty()).append(' ')
            append(deal.rawPriceText.orEmpty()).append(' ')
            append(deal.billingCycleLabel).append(' ')
            append(deal.specsSummaryLine).append(' ')
            append(deal.regionTags.joinToString(" ")).append(' ')
            append(deal.routeTags.joinToString(" ")).append(' ')
            append(deal.authorName)
        }.lowercase(Locale.ROOT)

        return tokens.all { token -> haystack.contains(token) }
    }

    private fun sortDeals(
        deals: List<PromoDealCardModel>,
        sortOption: DealSortOption,
        nowEpochMillis: Long,
    ): List<PromoDealCardModel> = when (sortOption) {
        DealSortOption.LATEST -> deals.sortedWith(
            compareByDescending<PromoDealCardModel> { it.publishedAtEpochMillis }
                .thenByDescending { it.replyCount },
        )

        DealSortOption.LOWEST_ANNUAL_PRICE -> deals.sortedWith(
            compareBy<PromoDealCardModel> { it.annualizedUsd == null || it.annualizedUsd <= BigDecimal.ZERO }
                .thenBy { it.isOutOfStockHint }
                .thenBy { it.annualizedUsd ?: BigDecimal("9999999") }
                .thenByDescending { it.publishedAtEpochMillis },
        )

        DealSortOption.HOTTEST -> deals.sortedWith(
            compareByDescending<PromoDealCardModel> { it.replyCount }
                .thenByDescending { it.viewCount }
                .thenByDescending { it.publishedAtEpochMillis },
        )

        DealSortOption.EXPIRING_SOON -> deals.sortedWith(
            compareBy<PromoDealCardModel> { deal ->
                val exp = deal.expiresAtEpochMillis
                when {
                    exp != null && exp > nowEpochMillis -> 0
                    exp == null -> 1
                    else -> 2
                }
            }
                .thenBy { deal ->
                    val exp = deal.expiresAtEpochMillis
                    if (exp != null && exp > nowEpochMillis) exp else Long.MAX_VALUE
                }
                .thenByDescending { it.publishedAtEpochMillis },
        )
    }
}
