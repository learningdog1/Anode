package com.nodeseek.app.domain.overseas

import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.domain.valuation.ValuationCurrency
import java.math.BigDecimal

/**
 * Supported upstream overseas VPS deal communities.
 */
enum class OverseasSource(
    val label: String,
    val shortLabel: String,
    val siteUrl: String,
) {
    LET(
        label = "LowEndTalk",
        shortLabel = "LET",
        siteUrl = "https://lowendtalk.com/categories/offers",
    ),
    LEB(
        label = "LowEndBox",
        shortLabel = "LEB",
        siteUrl = "https://lowendbox.com/",
    ),
}

/**
 * Feed filter chips for the Overseas Intelligence screen and Deals Hub bridge.
 */
enum class OverseasSourceFilter(val label: String) {
    ALL("全部"),
    LET("LowEndTalk"),
    LEB("LowEndBox"),
    BLACK_FRIDAY_FLASH("黑五 Flash"),
}

/**
 * Language presentation mode for overseas offers.
 */
enum class OverseasDisplayMode(
    val label: String,
    val shortLabel: String,
) {
    CHINESE("中文机翻", "译"),
    BILINGUAL("双语对照", "双语"),
    ORIGINAL("英文原文", "EN"),
}

/**
 * Billing cycle parsed from an overseas VPS price mention.
 */
enum class OverseasPriceCycle(
    val shortCode: String,
    val labelZh: String,
) {
    MONTHLY("mo", "月"),
    QUARTERLY("qtr", "季"),
    SEMI_ANNUAL("semi", "半年"),
    ANNUAL("yr", "年"),
    BIENNIAL("2yr", "两年"),
    TRIENNIAL("3yr", "三年"),
    ONE_TIME("once", "一次性"),
    UNKNOWN("", ""),
}

/**
 * A foreign-currency price extracted from an overseas offer together with its real-time CNY conversion.
 */
data class ConvertedPriceTag(
    val rawText: String,
    val originalAmount: BigDecimal,
    val currency: ValuationCurrency,
    val cycle: OverseasPriceCycle,
    val cnyAmount: BigDecimal,
    val formattedCny: String,
    val annualizedUsd: BigDecimal? = null,
    val annualizedCny: BigDecimal? = null,
) {
    /**
     * Compact highlight text suitable for cards and restock watch prefill (e.g. `$14.99/yr · ≈ ¥108.68/年`).
     */
    val badgeText: String
        get() = "$rawText · $formattedCny"

    /**
     * Expressive hero badge emphasizing CNY equivalent (e.g. `$14.99/yr · ≈ ¥108.68 人民币`).
     */
    val prominentBadgeText: String
        get() = "$rawText · ≈ ¥${cnyAmount.toPlainString()} 人民币"
}

/**
 * Translation backend configuration tier selected by the user.
 */
enum class TranslationEngineKind(
    val label: String,
    val description: String,
) {
    AUTO_GTX_WITH_GLOSSARY(
        label = "零配置默认 (GTX + 离线术语)",
        description = "优先使用免费 Google GTX 翻译并用 VPS 术语表润色，不通时自动降级到离线引擎",
    ),
    OPENAI_COMPATIBLE(
        label = "自定义 OpenAI 兼容 API",
        description = "支持 OpenAI / DeepSeek / SiliconFlow / 中转站 (/v1/chat/completions)",
    ),
    DEEPLX(
        label = "自定义 DeepLX API",
        description = "支持自建或第三方 DeepLX 翻译接口 (/translate)",
    ),
    OFFLINE_GLOSSARY_ONLY(
        label = "仅离线 VPS 术语引擎",
        description = "零网络请求，毫秒级本地转换常见 VPS 配置、机房、周期与促销术语",
    ),
}

/**
 * Provenance of the active translation on an [OverseasDealOffer].
 */
enum class TranslationTier(val label: String) {
    NONE("原文"),
    CUSTOM_API("自定义 AI/DeepLX"),
    GOOGLE_GTX("在线机翻"),
    OFFLINE_GLOSSARY("离线术语"),
}

/**
 * Persisted user configuration for [OverseasTranslator].
 */
data class OverseasTranslationConfig(
    val engineKind: TranslationEngineKind = TranslationEngineKind.AUTO_GTX_WITH_GLOSSARY,
    val customEndpointUrl: String = "",
    val customApiKey: String = "",
    val customModel: String = DEFAULT_OPENAI_MODEL,
    val autoTranslateOnRefresh: Boolean = true,
    val displayMode: OverseasDisplayMode = OverseasDisplayMode.BILINGUAL,
) {
    val hasValidCustomEndpoint: Boolean
        get() = customEndpointUrl.trim().startsWith("http://", ignoreCase = true) ||
            customEndpointUrl.trim().startsWith("https://", ignoreCase = true)

    companion object {
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
        const val DEFAULT_OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        const val DEFAULT_DEEPLX_ENDPOINT = "http://127.0.0.1:1188/translate"
    }
}

/**
 * Normalized overseas VPS offer aggregated from LowEndTalk or LowEndBox.
 */
data class OverseasDealOffer(
    val id: String,
    val source: OverseasSource,
    val originalTitle: String,
    val translatedTitle: String? = null,
    val originalSummary: String,
    val translatedSummary: String? = null,
    val authorOrProvider: String,
    val sourceLink: String,
    val orderLinks: List<String> = emptyList(),
    val couponCodes: List<String> = emptyList(),
    val priceMatches: List<ConvertedPriceTag> = emptyList(),
    val specHighlights: List<String> = emptyList(),
    val publishedAtEpochMillis: Long = 0L,
    val isFlashOrBlackFriday: Boolean = false,
    val isOutOfStockHint: Boolean = false,
    val translationTier: TranslationTier = TranslationTier.NONE,
) {
    val primaryPrice: ConvertedPriceTag?
        get() = priceMatches.firstOrNull()

    val primaryOrderUrl: String
        get() = orderLinks.firstOrNull()?.takeIf { it.isNotBlank() } ?: sourceLink

    fun resolveHeadlineTitle(mode: OverseasDisplayMode): String = when (mode) {
        OverseasDisplayMode.ORIGINAL -> originalTitle
        OverseasDisplayMode.CHINESE,
        OverseasDisplayMode.BILINGUAL,
        -> translatedTitle?.takeIf { it.isNotBlank() } ?: originalTitle
    }

    fun resolveSecondaryOriginalTitle(mode: OverseasDisplayMode): String? =
        if (
            mode == OverseasDisplayMode.BILINGUAL &&
            !translatedTitle.isNullOrBlank() &&
            !translatedTitle.equals(originalTitle, ignoreCase = true)
        ) {
            originalTitle
        } else {
            null
        }

    fun resolvePrimarySummary(mode: OverseasDisplayMode): String = when (mode) {
        OverseasDisplayMode.ORIGINAL -> originalSummary
        OverseasDisplayMode.CHINESE,
        OverseasDisplayMode.BILINGUAL,
        -> translatedSummary?.takeIf { it.isNotBlank() } ?: originalSummary
    }

    fun resolveSecondaryOriginalSummary(mode: OverseasDisplayMode): String? =
        if (
            mode == OverseasDisplayMode.BILINGUAL &&
            !translatedSummary.isNullOrBlank() &&
            !translatedSummary.equals(originalSummary, ignoreCase = true)
        ) {
            originalSummary
        } else {
            null
        }
}

/**
 * Synchronization and translation progress metadata for the overseas aggregator.
 */
data class OverseasSyncStatus(
    val isRefreshing: Boolean = false,
    val isTranslating: Boolean = false,
    val lastSyncedEpochMillis: Long = 0L,
    val sourceErrors: Map<OverseasSource, String> = emptyMap(),
    val loadedFromCache: Boolean = false,
)

/**
 * Complete reactive repository snapshot consumed by UI and cross-feature bridges.
 */
data class OverseasIntelSnapshot(
    val offers: List<OverseasDealOffer> = emptyList(),
    val fxRatesState: FxRatesState,
    val translationConfig: OverseasTranslationConfig = OverseasTranslationConfig(),
    val syncStatus: OverseasSyncStatus = OverseasSyncStatus(),
)

/**
 * Pre-formatted NodeSeek post draft produced by [OverseasForumPostFormatter].
 */
data class SuggestedForumPost(
    val title: String,
    val markdownBody: String,
    val suggestedCategorySlug: String = "info",
)
