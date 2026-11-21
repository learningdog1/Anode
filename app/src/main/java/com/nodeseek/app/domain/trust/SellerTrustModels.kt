package com.nodeseek.app.domain.trust

import com.nodeseek.app.domain.model.TopicSummary
import kotlinx.serialization.Serializable

/**
 * Local buyer-defined reputation tag for a NodeSeek seller.
 */
@Serializable
enum class TrustTag(
    val displayLabel: String,
    val shortLabel: String,
) {
    NONE(displayLabel = "未标记", shortLabel = "未标记"),
    TRUSTED(displayLabel = "靠谱卖家", shortLabel = "靠谱"),
    NEUTRAL(displayLabel = "已交易/观察", shortLabel = "观察"),
    CAUTION(displayLabel = "需谨慎/走中介", shortLabel = "谨慎"),
    RISK_BLACKLIST(displayLabel = "高危避雷", shortLabel = "避雷");

    val isTagged: Boolean
        get() = this != NONE
}

/**
 * Device-local private note & tag attached to a NodeSeek seller UID.
 */
@Serializable
data class LocalSellerNote(
    val uid: Long,
    val username: String,
    val tag: TrustTag = TrustTag.NONE,
    val noteText: String = "",
    val updatedAtEpochMillis: Long = 0L,
) {
    val isMeaningful: Boolean
        get() = tag != TrustTag.NONE || noteText.isNotBlank()
}

/**
 * Exposure / scam thread matched against a target seller username via station search.
 */
@Serializable
data class ExposureHitTopic(
    val topicId: Long,
    val title: String,
    val categoryName: String,
    val authorName: String,
    val publishedAtText: String,
    val matchedTerm: String,
    val categorySlug: String = "",
)

/**
 * Overall risk verdict rendered in the prominent Seller Trust Card banner.
 */
@Serializable
enum class TrustRiskLevel {
    HIGH_RISK_RED,
    CAUTION_AMBER,
    NORMAL_CLEAR,
}

/**
 * Structured risk verdict with human-readable reasons and transaction safety advice.
 */
@Serializable
data class TrustRiskAssessment(
    val level: TrustRiskLevel,
    val headline: String,
    val reasons: List<String> = emptyList(),
    val safetyTip: String = DEFAULT_SAFETY_TIP,
) {
    companion object {
        const val DEFAULT_SAFETY_TIP: String =
            "大额交易请走官方中介，核验原邮与 Push 流程，切勿轻信私下先款。"
    }
}

/**
 * Parsed registration date and human-readable tenure duration for a seller account.
 */
@Serializable
data class RegistrationTenure(
    val createdAtText: String,
    val registrationDays: Int?,
    val durationLabel: String,
) {
    val isNewAccount: Boolean
        get() = registrationDays != null && registrationDays < TrustRiskEvaluator.NEW_ACCOUNT_THRESHOLD_DAYS
}

/**
 * Input candidate passed to [TrustRiskEvaluator.filterExposureHits], allowing optional search snippets
 * alongside the structured [TopicSummary].
 */
data class ExposureCandidate(
    val topic: TopicSummary,
    val snippet: String? = null,
    val queryTermHint: String? = null,
)

/**
 * Scope of exposure verification reflected by a [SellerTrustReport].
 */
@Serializable
enum class ExposureCheckScope {
    /** Matched locally against the shared page-1 `/categories/expose` board snapshot (zero `/search` calls). */
    BOARD_SNAPSHOT,

    /** Verified via on-demand single-query station `/search` deep scan in addition to the board snapshot. */
    DEEP_SEARCH,

    /** Neither board snapshot nor deep search succeeded cleanly. */
    UNVERIFIED,
}

/**
 * Data source & resilience status of a [SellerTrustReport].
 */
@Serializable
enum class TrustFetchStatus {
    /** Fresh remote inspection completed (all or partial signals succeeded). */
    FRESH,

    /** Served from memory LRU or persistent disk cache within valid TTL. */
    FROM_CACHE,

    /** Served from cached snapshot because remote is in rate-limit cooldown, throttled, or degraded. */
    STALE_CACHE_FALLBACK,

    /** Remote site is rate-limited (HTTP 429/503 / cooldown) and no usable positive cache exists. */
    RATE_LIMITED,

    /** Remote inspection failed or returned empty/unresolvable seller (or hit short negative cache); manually retryable. */
    FAILED_RETRYABLE,
}

/**
 * Complete pre-trade seller trust inspection report aggregated from profile, user topics,
 * station exposure searches, and local private notes.
 */
@Serializable
data class SellerTrustReport(
    val uid: Long,
    val username: String,
    val avatarUrl: String = "",
    val rankLevel: Int = 0,
    val coins: Int? = null,
    val stardust: Int? = null,
    val createdAtText: String = "",
    val registrationDays: Int? = null,
    val registrationDurationLabel: String = "注册时间未知",
    val totalTopicsCount: Int? = null,
    val totalCommentsCount: Int? = null,
    val historicalTradeTopicCount: Int = 0,
    val recentTradeTopics: List<TopicSummary> = emptyList(),
    val exposureSearchHits: List<ExposureHitTopic> = emptyList(),
    val searchSucceeded: Boolean = true,
    val localNote: LocalSellerNote? = null,
    val riskAssessment: TrustRiskAssessment,
    val profileSucceeded: Boolean = true,
    val topicsSucceeded: Boolean = true,
    val searchFailureReason: String? = null,
    val fetchedAtEpochMillis: Long = 0L,
    val isFromCache: Boolean = false,
    val fetchStatus: TrustFetchStatus = if (isFromCache) TrustFetchStatus.FROM_CACHE else TrustFetchStatus.FRESH,
    val cachedAtEpochMillis: Long = 0L,
    val cacheExpiresAtEpochMillis: Long = 0L,
    val cooldownUntilEpochMillis: Long = 0L,
    val failureMessage: String? = null,
    val isNegativeCache: Boolean = false,
    val isRefreshThrottled: Boolean = false,
    val exposureCheckScope: ExposureCheckScope = ExposureCheckScope.BOARD_SNAPSHOT,
    val deepSearchCooldownUntilEpochMillis: Long = 0L,
    val deepSearchNoticeMessage: String? = null,
) {
    val rankLabel: String
        get() = "Lv.${rankLevel.coerceIn(0, 6)}"

    val isNewAccount: Boolean
        get() = registrationDays != null && registrationDays < TrustRiskEvaluator.NEW_ACCOUNT_THRESHOLD_DAYS

    val anyRemoteSucceeded: Boolean
        get() = profileSucceeded || topicsSucceeded || searchSucceeded

    val isDeepSearchVerified: Boolean
        get() = exposureCheckScope == ExposureCheckScope.DEEP_SEARCH

    val isRateLimited: Boolean
        get() = fetchStatus == TrustFetchStatus.RATE_LIMITED ||
            (fetchStatus == TrustFetchStatus.STALE_CACHE_FALLBACK && !isRefreshThrottled && cooldownUntilEpochMillis > 0L)
}
