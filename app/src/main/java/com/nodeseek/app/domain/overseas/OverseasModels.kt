package com.nodeseek.app.domain.overseas

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
 * Full promotion detail returned by `GET /api/v1/offers/{id}`.
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
) {
    /**
     * True when the backend only returned a minimal placeholder notification because AI/OP
     * hydration is still pending asynchronously on the server; client cache should use a short
     * TTL so re-opening shortly afterward picks up the enriched result.
     */
    val isPendingAiHydration: Boolean
        get() {
            val hasRichStructured = structured != null && (
                structured.sections.isNotEmpty() ||
                    structured.orderLinks.isNotEmpty() ||
                    structured.aiComment.isNotBlank() ||
                    structured.pros.isNotEmpty() ||
                    structured.cons.isNotEmpty() ||
                    structured.suitableFor.isNotBlank()
                )
            if (hasRichStructured) return false
            val trimmedMd = tgMarkdown.trimStart()
            return trimmedMd.startsWith("📢 **新优惠信息**") ||
                trimmedMd.startsWith("📢 新优惠信息") ||
                trimmedMd.isEmpty()
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
 * Configurable domain-layer fallback filter for overseas VPS promotion offers.
 */
data class OverseasOfferFilterConfig(
    val enabled: Boolean = true,
    val excludeGenericMarketingBuzz: Boolean = true,
    val excludeNonServerServices: Boolean = true,
    val requireMinimumHostingSignal: Boolean = true,
    val blockedMerchants: Set<String> = emptySet(),
    val extraBlockedKeywords: Set<String> = emptySet(),
)

/**
 * Complete reactive repository snapshot consumed by UI.
 */
data class OverseasIntelSnapshot(
    val offers: List<OverseasDealOffer> = emptyList(),
    val syncStatus: OverseasSyncStatus = OverseasSyncStatus(),
    val filterConfig: OverseasOfferFilterConfig = OverseasOfferFilterConfig(),
    val filteredOutCount: Int = 0,
)

/**
 * Pre-formatted NodeSeek post draft produced by [OverseasForumPostFormatter].
 */
data class SuggestedForumPost(
    val title: String,
    val markdownBody: String,
    val suggestedCategorySlug: String = "info",
)
