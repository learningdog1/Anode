package com.nodeseek.app.domain.radar

import com.nodeseek.app.domain.model.NotificationItem
import com.nodeseek.app.domain.model.NotificationKind
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.navigation.DeepLinkParser
import com.nodeseek.app.navigation.NodePulseDeepLink
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.regex.PatternSyntaxException

enum class KeywordMatchMode {
    CONTAINS,
    REGEX,
}

data class KeywordWatchRule(
    val id: Long = 0L,
    val keyword: String,
    val excludeWords: String = "",
    val categorySlug: String = "",
    val matchMode: KeywordMatchMode = KeywordMatchMode.CONTAINS,
    val isEnabled: Boolean = true,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
)

data class RadarRuleDedupeKey(
    val normalizedKeywordLower: String,
    val matchMode: KeywordMatchMode,
    val normalizedCategorySlug: String,
    val canonicalExcludeTerms: List<String>,
)

/**
 * Single source of truth for normalizing, validating, and deduplicating [KeywordWatchRule] items
 * across rule creation, backup inspection/preview, and merge/overwrite backup restore.
 */
object RadarRuleCanonicalizer {
    const val MAX_KEYWORD_LENGTH = 64
    const val MAX_REGEX_LENGTH = 160
    private val EXCLUDE_SPLIT_REGEX = Regex("""[,，;；\n\r]+""")
    // Reject obvious catastrophic backtracking constructs such as (a+)+, (.*)*, (\w+){2,}
    private val NESTED_QUANTIFIER_REGEX = Regex("""\([^()]*[+*?\}][^()]*\)\s*(?:[+*]|\{\s*\d+\s*,)""")

    fun isPotentiallyUnsafeRegex(pattern: String): Boolean {
        if (pattern.length > MAX_REGEX_LENGTH) return true
        return NESTED_QUANTIFIER_REGEX.containsMatchIn(pattern)
    }

    fun normalizeCategorySlug(raw: String): String =
        raw.trim().lowercase()

    fun normalizeExcludeWords(raw: String): String =
        raw.split(EXCLUDE_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .joinToString(", ")

    fun canonicalExcludeTerms(raw: String): List<String> =
        raw.split(EXCLUDE_SPLIT_REGEX)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

    fun parseMatchModeStrict(raw: String): Result<KeywordMatchMode> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return Result.success(KeywordMatchMode.CONTAINS)
        }
        val resolved = KeywordMatchMode.entries.firstOrNull { mode ->
            mode.name.equals(trimmed, ignoreCase = true)
        } ?: return Result.failure(
            IllegalArgumentException("不支持的雷达关键词匹配模式：$trimmed"),
        )
        return Result.success(resolved)
    }

    fun validateAndNormalizeKeyword(
        rawKeyword: String,
        matchMode: KeywordMatchMode,
    ): Result<String> {
        val trimmed = rawKeyword.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("请输入监控关键词"))
        }
        val maxLen = if (matchMode == KeywordMatchMode.REGEX) MAX_REGEX_LENGTH else MAX_KEYWORD_LENGTH
        val normalizedKeyword = trimmed.take(maxLen)
        if (matchMode == KeywordMatchMode.REGEX) {
            if (isPotentiallyUnsafeRegex(normalizedKeyword)) {
                return Result.failure(
                    IllegalArgumentException("正则表达式包含嵌套量词，可能导致性能风险，请简化表达式"),
                )
            }
            try {
                Regex(normalizedKeyword, RegexOption.IGNORE_CASE)
            } catch (e: PatternSyntaxException) {
                return Result.failure(
                    IllegalArgumentException(
                        "正则表达式格式无效：${e.description ?: e.message.orEmpty()}",
                        e,
                    ),
                )
            }
        }
        return Result.success(normalizedKeyword)
    }

    fun dedupeKey(
        keyword: String,
        excludeWords: String,
        categorySlug: String,
        matchMode: KeywordMatchMode,
    ): RadarRuleDedupeKey {
        val maxLen = if (matchMode == KeywordMatchMode.REGEX) MAX_REGEX_LENGTH else MAX_KEYWORD_LENGTH
        return RadarRuleDedupeKey(
            normalizedKeywordLower = keyword.trim().take(maxLen).lowercase(),
            matchMode = matchMode,
            normalizedCategorySlug = normalizeCategorySlug(categorySlug),
            canonicalExcludeTerms = canonicalExcludeTerms(excludeWords),
        )
    }

    fun dedupeKey(rule: KeywordWatchRule): RadarRuleDedupeKey =
        dedupeKey(
            keyword = rule.keyword,
            excludeWords = rule.excludeWords,
            categorySlug = rule.categorySlug,
            matchMode = rule.matchMode,
        )

    fun normalizeRuleForRestore(
        rule: KeywordWatchRule,
        fallbackNowMillis: Long,
    ): Result<KeywordWatchRule> {
        val normalizedKeyword = validateAndNormalizeKeyword(rule.keyword, rule.matchMode)
            .getOrElse { return Result.failure(it) }
        return Result.success(
            KeywordWatchRule(
                id = 0L,
                keyword = normalizedKeyword,
                excludeWords = normalizeExcludeWords(rule.excludeWords),
                categorySlug = normalizeCategorySlug(rule.categorySlug),
                matchMode = rule.matchMode,
                isEnabled = rule.isEnabled,
                createdAtEpochMillis = if (rule.createdAtEpochMillis > 0L) {
                    rule.createdAtEpochMillis
                } else {
                    fallbackNowMillis
                },
            ),
        )
    }

    fun deduplicateForRestore(
        rules: List<KeywordWatchRule>,
        fallbackNowMillis: Long,
        existingRules: List<KeywordWatchRule> = emptyList(),
        strictValidation: Boolean = false,
    ): Result<List<KeywordWatchRule>> {
        val seenKeys = LinkedHashSet<RadarRuleDedupeKey>(existingRules.size + rules.size)
        for (existing in existingRules) {
            seenKeys.add(dedupeKey(existing))
        }
        val deduplicated = ArrayList<KeywordWatchRule>(rules.size)
        for (rule in rules) {
            val normalizedResult = normalizeRuleForRestore(rule, fallbackNowMillis)
            if (normalizedResult.isFailure) {
                if (strictValidation) {
                    return Result.failure(
                        normalizedResult.exceptionOrNull() ?: IllegalArgumentException("无效的雷达规则"),
                    )
                }
                continue
            }
            val normalized = normalizedResult.getOrThrow()
            if (seenKeys.add(dedupeKey(normalized))) {
                deduplicated.add(normalized)
            }
        }
        return Result.success(deduplicated)
    }
}

data class TrackedTopic(
    val topicId: Long,
    val title: String,
    val categorySlug: String = "",
    val categoryName: String = "",
    val authorName: String = "",
    val lastKnownFloor: Int = -1,
    val lastKnownTotalPages: Int = 1,
    val unreadNewReplies: Int = 0,
    val isActive: Boolean = true,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val lastCheckedEpochMillis: Long = 0L,
    val lastUpdatedEpochMillis: Long = System.currentTimeMillis(),
)

enum class LocalAlertKind {
    KEYWORD_HIT,
    TRACKED_REPLY,
}

data class LocalAlertRecord(
    val id: Long = 0L,
    val dedupKey: String,
    val kind: LocalAlertKind,
    val topicId: Long,
    val topicTitle: String,
    val targetFloor: Int = 0,
    val matchedKeyword: String = "",
    val actorName: String = "",
    val summarySnippet: String = "",
    val categoryName: String = "",
    val isRead: Boolean = false,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
)

data class RadarPollingSettings(
    val isEnabled: Boolean = true,
    val keywordAlertsEnabled: Boolean = true,
    val replyTrackingEnabled: Boolean = true,
    val inboxPushEnabled: Boolean = true,
    val foregroundIntervalMinutes: Int = 2,
    val backgroundIntervalMinutes: Int = MIN_BACKGROUND_INTERVAL_MINUTES,
    val wifiOnlyInBackground: Boolean = false,
    val requireBatteryNotLowInBackground: Boolean = false,
    val quietHoursEnabled: Boolean = false,
    val quietStartMinuteOfDay: Int = 23 * 60,
    val quietEndMinuteOfDay: Int = 7 * 60,
) {
    companion object {
        const val MIN_FOREGROUND_INTERVAL_MINUTES = 1
        const val MAX_FOREGROUND_INTERVAL_MINUTES = 60
        const val MIN_BACKGROUND_INTERVAL_MINUTES = 15
        const val MAX_BACKGROUND_INTERVAL_MINUTES = 180
    }
}

sealed interface RadarBackgroundPollOutcome {
    data class Completed(
        val keywordAlertsTriggered: Int = 0,
        val trackedReplyAlertsTriggered: Int = 0,
        val inboxNotificationsTriggered: Int = 0,
        val skippedInboxUnauthenticated: Boolean = false,
        val errorSummary: String? = null,
    ) : RadarBackgroundPollOutcome

    data class Retry(
        val reason: String,
    ) : RadarBackgroundPollOutcome

    data class Skipped(
        val reason: SkipReason,
    ) : RadarBackgroundPollOutcome {
        enum class SkipReason {
            DISABLED,
            BACKOFF_ACTIVE,
            UNMETERED_NETWORK_REQUIRED,
        }
    }
}

data class RadarRuntimeStatus(
    val isRunningInForeground: Boolean = true,
    val isPollingNow: Boolean = false,
    val lastCheckEpochMillis: Long = 0L,
    val lastErrorSummary: String? = null,
)

sealed interface NotificationTapTarget {
    data class OpenTopic(
        val topicId: Long,
        val initialFloor: Int = 0,
        val titleHint: String = "",
        val alertDedupKey: String? = null,
        val notificationKind: NotificationKind? = null,
        val notificationId: Long = 0L,
    ) : NotificationTapTarget

    data class OpenDirectMessage(
        val peerUid: Long,
        val peerName: String,
        val peerAvatarUrl: String = "",
    ) : NotificationTapTarget

    data object OpenRadarAlerts : NotificationTapTarget

    data object OpenLiteSsh : NotificationTapTarget
}

enum class InAppMessageKind {
    REPLY,
    MENTION,
    DIRECT_MESSAGE,
    SSH_BENCHMARK_COMPLETED,
}

data class InAppMessagePopup(
    val dedupKey: String,
    val kind: InAppMessageKind,
    val itemId: Long,
    val senderUid: Long,
    val senderName: String,
    val senderAvatarUrl: String = "",
    val title: String,
    val previewText: String,
    val createdAtIso: String = "",
    val topicId: Long = 0L,
    val targetFloor: Int = 0,
    val targetPage: Int = 1,
    val peerUid: Long = 0L,
    val peerName: String = "",
    val emittedAtEpochMillis: Long = System.currentTimeMillis(),
)

data class ResolvedTopicLocation(
    val topicId: Long,
    val page: Int,
    val floor: Int,
)

/**
 * Resolves exact topic page/floor locations and direct-message targets from notification rows,
 * embedded NodeSeek post links (`/post-{topicId}-{page}#floor`), and [InAppMessagePopup] payloads.
 */
object InboxTargetResolver {
    private val POST_WITH_PAGE_REGEX = Regex("""^/post-(\d+)-(\d+)(?:\.html)?/?$""", RegexOption.IGNORE_CASE)
    private val POST_NO_PAGE_REGEX = Regex("""^/post-(\d+)(?:\.html)?/?$""", RegexOption.IGNORE_CASE)
    private val FLOOR_FRAGMENT_REGEX = Regex("""^(?:floor-|comment-)?(\d+)$""", RegexOption.IGNORE_CASE)
    private val EMBEDDED_POST_URL_REGEX = Regex(
        """(?:https?://(?:www\.)?nodeseek\.com)?/post-\d+(?:-\d+)?(?:\.html)?(?:[?#][^\s)"'<>]*)?""",
        RegexOption.IGNORE_CASE,
    )

    fun parseTopicLocationFromUrl(
        rawUrl: String?,
        maxRedirectDepth: Int = 2,
    ): ResolvedTopicLocation? {
        if (rawUrl.isNullOrBlank() || maxRedirectDepth < 0) return null
        val normalized = normalizeCandidateUrl(rawUrl) ?: return null
        val parsed = runCatching { URI(normalized) }.getOrNull() ?: return null
        val host = parsed.host?.lowercase() ?: return null
        if (host != "nodeseek.com" && host != "www.nodeseek.com") return null
        val path = parsed.path?.ifBlank { "/" } ?: "/"

        if (path.equals("/jump", ignoreCase = true)) {
            val target = extractQueryParam(parsed.rawQuery, "to")
            if (!target.isNullOrBlank()) {
                return parseTopicLocationFromUrl(target, maxRedirectDepth = maxRedirectDepth - 1)
            }
            return null
        }

        val queryFloor = listOf("floor", "floor_id", "floorId")
            .firstNotNullOfOrNull { key ->
                extractQueryParam(parsed.rawQuery, key)?.toIntOrNull()?.takeIf { it >= 0 }
            }
        val fragmentFloor = parsed.rawFragment
            ?.trim()
            ?.let { frag -> FLOOR_FRAGMENT_REGEX.matchEntire(frag)?.groupValues?.get(1)?.toIntOrNull() }
            ?.takeIf { it >= 0 }

        val deepLink = DeepLinkParser.parseUrl(normalized, maxRedirectDepth = maxRedirectDepth)
        if (deepLink is NodePulseDeepLink.TopicDetail) {
            val explicitPage = POST_WITH_PAGE_REGEX.matchEntire(path)
                ?.groupValues?.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val resolvedFloor = when {
                fragmentFloor != null -> fragmentFloor
                queryFloor != null -> queryFloor
                deepLink.initialFloor > 0 -> deepLink.initialFloor
                else -> 0
            }
            val resolvedPage = if (resolvedFloor > 0) {
                TopicDetailPage.pageForFloor(resolvedFloor)
            } else {
                explicitPage
            }
            return ResolvedTopicLocation(
                topicId = deepLink.topicId,
                page = resolvedPage,
                floor = resolvedFloor,
            )
        }

        POST_NO_PAGE_REGEX.matchEntire(path)?.let { match ->
            val topicId = match.groupValues[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
            val resolvedFloor = fragmentFloor ?: queryFloor ?: 0
            val resolvedPage = if (resolvedFloor > 0) TopicDetailPage.pageForFloor(resolvedFloor) else 1
            return ResolvedTopicLocation(
                topicId = topicId,
                page = resolvedPage,
                floor = resolvedFloor,
            )
        }

        return null
    }

    fun extractTopicLocationFromText(text: String?): ResolvedTopicLocation? {
        if (text.isNullOrBlank()) return null
        val candidate = EMBEDDED_POST_URL_REGEX.find(text)?.value ?: return null
        return parseTopicLocationFromUrl(candidate)
    }

    fun resolveForNotificationItem(item: NotificationItem): ResolvedTopicLocation? {
        val fallbackLocation = extractTopicLocationFromText(item.excerpt)
            ?: extractTopicLocationFromText(item.topicTitle)

        if (item.topicId > 0L) {
            val resolvedFloor = when {
                item.floorNumber > 0 -> item.floorNumber
                fallbackLocation != null &&
                    fallbackLocation.topicId == item.topicId &&
                    fallbackLocation.floor > 0 -> fallbackLocation.floor
                else -> item.floorNumber.coerceAtLeast(0)
            }
            val resolvedPage = when {
                resolvedFloor > 0 -> TopicDetailPage.pageForFloor(resolvedFloor)
                fallbackLocation != null && fallbackLocation.topicId == item.topicId -> fallbackLocation.page
                else -> 1
            }
            return ResolvedTopicLocation(
                topicId = item.topicId,
                page = resolvedPage,
                floor = resolvedFloor,
            )
        }

        return fallbackLocation
    }

    fun toTapTarget(popup: InAppMessagePopup): NotificationTapTarget? = when (popup.kind) {
        InAppMessageKind.REPLY,
        InAppMessageKind.MENTION,
        -> {
            if (popup.topicId <= 0L) {
                null
            } else {
                val notificationKind = when (popup.kind) {
                    InAppMessageKind.REPLY -> NotificationKind.REPLIES
                    InAppMessageKind.MENTION -> NotificationKind.MENTIONS
                    InAppMessageKind.DIRECT_MESSAGE,
                    InAppMessageKind.SSH_BENCHMARK_COMPLETED,
                    -> null
                }
                NotificationTapTarget.OpenTopic(
                    topicId = popup.topicId,
                    initialFloor = popup.targetFloor.coerceAtLeast(0),
                    titleHint = popup.title,
                    notificationKind = notificationKind,
                    notificationId = popup.itemId,
                )
            }
        }

        InAppMessageKind.DIRECT_MESSAGE -> {
            if (popup.peerUid <= 0L) {
                null
            } else {
                NotificationTapTarget.OpenDirectMessage(
                    peerUid = popup.peerUid,
                    peerName = popup.peerName.ifBlank { popup.senderName },
                    peerAvatarUrl = popup.senderAvatarUrl,
                )
            }
        }

        InAppMessageKind.SSH_BENCHMARK_COMPLETED -> NotificationTapTarget.OpenLiteSsh
    }

    private fun normalizeCandidateUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "https://www.nodeseek.com$trimmed"
            trimmed.startsWith("post-", ignoreCase = true) ||
                trimmed.startsWith("jump?", ignoreCase = true) -> "https://www.nodeseek.com/$trimmed"
            else -> null
        }
    }

    private fun extractQueryParam(rawQuery: String?, key: String): String? {
        if (rawQuery.isNullOrBlank()) return null
        for (pair in rawQuery.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val k = pair.substring(0, idx)
            if (k.equals(key, ignoreCase = true)) {
                val encodedValue = pair.substring(idx + 1)
                return runCatching {
                    URLDecoder.decode(encodedValue, StandardCharsets.UTF_8.name())
                }.getOrDefault(encodedValue)
            }
        }
        return null
    }
}
