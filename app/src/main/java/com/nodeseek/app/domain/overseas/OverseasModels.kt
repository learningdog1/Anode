package com.nodeseek.app.domain.overseas

/**
 * Language presentation mode for overseas promotion detail content.
 */
enum class OverseasDisplayMode(
    val label: String,
    val shortLabel: String,
) {
    ORIGINAL("通知原文", "原"),
    CHINESE("中文译文", "译"),
    BILINGUAL("双语对照", "双语"),
}

/**
 * Translation backend configuration tier selected by the user for detail translation.
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
 * Provenance of an optional translation on [OverseasOfferDetail].
 */
enum class TranslationTier(val label: String) {
    NONE("原文"),
    CUSTOM_API("自定义 AI/DeepLX"),
    GOOGLE_GTX("在线机翻"),
    OFFLINE_GLOSSARY("离线术语"),
}

/**
 * Persisted user configuration for [com.nodeseek.app.data.overseas.OverseasTranslator].
 */
data class OverseasTranslationConfig(
    val engineKind: TranslationEngineKind = TranslationEngineKind.AUTO_GTX_WITH_GLOSSARY,
    val customEndpointUrl: String = "",
    val customApiKey: String = "",
    val customModel: String = DEFAULT_OPENAI_MODEL,
    val autoTranslateOnRefresh: Boolean = false,
    val displayMode: OverseasDisplayMode = OverseasDisplayMode.ORIGINAL,
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
 * Lightweight list item returned by `GET /api/v1/offers` for card feed display.
 * Cards intentionally render only [title] and [summary] without prices, specs, coupons, or tags.
 */
data class OverseasDealOffer(
    val id: String,
    val title: String,
    val originalTitle: String = "",
    val summary: String,
    val merchant: String = "",
    val sourceLink: String,
    val publishedAtEpochMillis: Long = 0L,
    val publishedAtIso: String = "",
)

data class OverseasDetailSection(
    val header: String,
    val items: List<String> = emptyList(),
)

data class OverseasLabeledLink(
    val text: String,
    val url: String,
)

data class OverseasMediaImage(
    val alt: String,
    val url: String,
)

/**
 * Optional structured fields accompanying `GET /api/v1/offers/{id}`.
 */
data class OverseasDetailStructured(
    val titleCn: String = "",
    val sections: List<OverseasDetailSection> = emptyList(),
    val orderLinks: List<OverseasLabeledLink> = emptyList(),
    val promoCode: String = "",
    val pros: List<String> = emptyList(),
    val cons: List<String> = emptyList(),
    val suitableFor: String = "",
    val aiComment: String = "",
    val viewCount: String = "",
    val commentCount: String = "",
    val aiModel: String = "",
)

/**
 * Full Telegram promotion notification detail returned by `GET /api/v1/offers/{id}`.
 */
data class OverseasOfferDetail(
    val id: String,
    val title: String,
    val originalTitle: String = "",
    val merchant: String = "",
    val author: String = "",
    val publishedAtEpochMillis: Long = 0L,
    val publishedAtIso: String = "",
    val sourceLink: String,
    val tgMarkdown: String,
    val tgHtml: String = "",
    val tgPlainText: String = "",
    val structured: OverseasDetailStructured? = null,
    val translatedMarkdown: String? = null,
    val translationTier: TranslationTier = TranslationTier.NONE,
) {
    fun resolveDisplayMarkdown(mode: OverseasDisplayMode): String {
        val translated = translatedMarkdown?.takeIf { it.isNotBlank() }
        return when (mode) {
            OverseasDisplayMode.ORIGINAL -> tgMarkdown
            OverseasDisplayMode.CHINESE -> translated ?: tgMarkdown
            OverseasDisplayMode.BILINGUAL -> {
                if (translated != null && !translated.equals(tgMarkdown, ignoreCase = true)) {
                    "$translated\n\n---\n\n### 原通知内容\n\n$tgMarkdown"
                } else {
                    tgMarkdown
                }
            }
        }
    }
}

/**
 * Original thread first-post (OP / 主楼) content returned by `GET /api/v1/offers/{id}/repost`
 * strictly excluding all comments, used for one-click forum reposting.
 */
data class OverseasRepostContent(
    val id: String,
    val title: String,
    val titleCn: String = "",
    val merchant: String = "",
    val author: String = "",
    val publishedAtEpochMillis: Long = 0L,
    val publishedAtIso: String = "",
    val sourceLink: String,
    val bodyMarkdown: String,
    val bodyText: String = "",
    val bodyHtml: String = "",
    val images: List<OverseasMediaImage> = emptyList(),
    val links: List<OverseasLabeledLink> = emptyList(),
    val suggestedCategorySlug: String = "info",
)

/**
 * Synchronization and pagination metadata for the overseas promotion monitor feed.
 */
data class OverseasSyncStatus(
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val lastSyncedEpochMillis: Long = 0L,
    val currentPage: Int = 1,
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
    val errorMessage: String? = null,
    val loadedFromCache: Boolean = false,
)

/**
 * Complete reactive repository snapshot consumed by UI.
 */
data class OverseasIntelSnapshot(
    val offers: List<OverseasDealOffer> = emptyList(),
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
