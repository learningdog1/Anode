package com.nodeseek.app.domain.digest

import com.nodeseek.app.data.local.CachedTopicDrumstickMeta
import com.nodeseek.app.domain.filter.ContentFilterConfig
import com.nodeseek.app.domain.filter.ContentFilterEngine
import com.nodeseek.app.domain.model.AppSettings
import com.nodeseek.app.domain.model.AwayDigestTriggerMode
import com.nodeseek.app.domain.model.TopicSummary
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

data class RankedAwayDigestResult(
    val allRankedUnread: List<AwayDigestItem>,
    val topItems: List<AwayDigestItem>,
    val skippedReadCount: Int,
) {
    val totalUnreadInWindow: Int
        get() = allRankedUnread.size
}

/**
 * Pure domain engine for While-You-Were-Away candidate windowing, read-history filtering,
 * deal/promo signal detection, and composite ranking by **回复数 + 鸡腿数 + 热度**.
 */
object AwayDigestEngine {

    private val BEIJING_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    private val SPACE_DATE_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val SPACE_DATE_MINUTE_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
    private val SLASH_DATE_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.US)
    private val SLASH_DATE_MINUTE_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.US)

    private const val CLOCK_SKEW_TOLERANCE_MILLIS = 10L * 60_000L
    private const val MINUTE_SKEW_TOLERANCE_MILLIS = 60_000L
    private const val MAX_ACTIVE_OLD_TOPIC_AGE_MILLIS = 36L * 3_600_000L

    /**
     * Evaluates whether returning to the app should create an [AwaySession] under [settings].
     */
    fun shouldTriggerSession(
        settings: AppSettings,
        awayDurationMillis: Long,
        wasAppClosedReopen: Boolean,
        minAwayDurationMillisOverride: Long? = null,
    ): Boolean {
        if (!settings.awayDigestEnabled) return false
        val safeDuration = awayDurationMillis.coerceAtLeast(0L)
        val thresholdMillis = minAwayDurationMillisOverride
            ?: (
                settings.awayDigestThresholdMinutes.coerceIn(
                    AppSettings.MIN_AWAY_DIGEST_THRESHOLD_MINUTES,
                    AppSettings.MAX_AWAY_DIGEST_THRESHOLD_MINUTES,
                ) * 60_000L
                )
        return when (settings.awayDigestTriggerMode) {
            AwayDigestTriggerMode.CUSTOM_DURATION -> safeDuration >= thresholdMillis
            AwayDigestTriggerMode.ON_APP_REOPEN -> wasAppClosedReopen
            AwayDigestTriggerMode.DURATION_OR_REOPEN -> wasAppClosedReopen || safeDuration >= thresholdMillis
        }
    }

    /**
     * Scales chronological page sweep budget for longer custom away windows (e.g. 12h–24h+),
     * while [shouldStopMultiPageScan] still stops immediately once page timestamps precede [awayStartMillis].
     */
    fun recommendedMaxPagesToScan(awayDurationMillis: Long): Int {
        val hours = awayDurationMillis.coerceAtLeast(0L) / 3_600_000.0
        return when {
            hours <= 1.0 -> 3
            hours <= 9.0 -> AwayDigestRepositoryImpl.DEFAULT_MAX_PAGES_TO_SCAN
            hours <= 16.0 -> 8
            else -> 10
        }
    }

    /**
     * Floors [awayStartMillis] to minute precision with a 60s clock-skew buffer so brand-new posts
     * during a short reopen window are never dropped when HTML/RSS timestamps omit seconds.
     */
    fun resolveEffectiveLowerBound(awayStartMillis: Long): Long {
        if (awayStartMillis <= 0L) return 0L
        val minuteFloor = (awayStartMillis / 60_000L) * 60_000L
        return (minuteFloor - MINUTE_SKEW_TOLERANCE_MILLIS).coerceAtLeast(0L)
    }

    private val FLASH_DEAL_KEYWORDS = listOf(
        "限时",
        "好价",
        "神价",
        "骨折",
        "闪购",
        "秒杀",
        "特价",
        "补货",
        "放货",
        "有货",
        "神车",
        "优惠码",
        "折扣",
        "白嫖",
        "免费领",
        "清仓",
        "亏本出",
        "秒出",
        "flash sale",
        "promo",
    )

    private val DEAL_BOARD_SLUGS = setOf(
        "info",
        "promotion",
        "trade",
        "carpool",
    )

    /**
     * Parses NodeSeek ISO-8601 (`2026-10-15T08:30:00.000Z`), offset ISO, RFC-1123 RSS `pubDate`,
     * or `yyyy-MM-dd HH:mm:ss` timestamps into epoch milliseconds.
     */
    fun parseTopicTimestampMillis(raw: String?): Long? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { return Instant.parse(trimmed).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
        runCatching {
            return ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        }
        runCatching {
            val clean = trimmed.substringBefore('.')
            return LocalDateTime.parse(clean, SPACE_DATE_TIME_FORMATTER)
                .atZone(BEIJING_ZONE)
                .toInstant()
                .toEpochMilli()
        }
        runCatching {
            return LocalDateTime.parse(trimmed, SPACE_DATE_MINUTE_FORMATTER)
                .atZone(BEIJING_ZONE)
                .toInstant()
                .toEpochMilli()
        }
        runCatching {
            val clean = trimmed.substringBefore('.')
            return LocalDateTime.parse(clean, SLASH_DATE_TIME_FORMATTER)
                .atZone(BEIJING_ZONE)
                .toInstant()
                .toEpochMilli()
        }
        runCatching {
            return LocalDateTime.parse(trimmed, SLASH_DATE_MINUTE_FORMATTER)
                .atZone(BEIJING_ZONE)
                .toInstant()
                .toEpochMilli()
        }
        runCatching {
            return LocalDateTime.parse(trimmed)
                .atZone(BEIJING_ZONE)
                .toInstant()
                .toEpochMilli()
        }
        return null
    }

    /**
     * Returns `true` when multi-page chronological scanning (`FeedSort.NEWEST_TOPIC`) can stop early
     * because every parseable topic timestamp on [pageTopics] has fallen older than [awayStartMillis].
     */
    fun shouldStopMultiPageScan(
        pageTopics: List<TopicSummary>,
        awayStartMillis: Long,
    ): Boolean {
        if (pageTopics.isEmpty()) return true
        val parsedTimestamps = pageTopics.mapNotNull { parseTopicTimestampMillis(it.lastActivityIso) }
        if (parsedTimestamps.isEmpty()) return false
        val lowerBound = resolveEffectiveLowerBound(awayStartMillis)
        return parsedTimestamps.all { it < lowerBound }
    }

    /**
     * Checks whether [topic] belongs to the away window `[awayStartMillis, reopenedAtMillis]`.
     * Prefers topics created within the window, while also allowing high-heat active discussions
     * from the recent 36h that surged during the away window.
     */
    fun isWithinAwayWindow(
        topic: TopicSummary,
        enrichedMeta: CachedTopicDrumstickMeta?,
        awayStartMillis: Long,
        reopenedAtMillis: Long,
    ): Boolean {
        val lowerBound = resolveEffectiveLowerBound(awayStartMillis)
        val upperBound = reopenedAtMillis + CLOCK_SKEW_TOLERANCE_MILLIS
        val activityMillis = parseTopicTimestampMillis(topic.lastActivityIso)
        val createdMillis = parseTopicTimestampMillis(enrichedMeta?.createdAtIso)

        if (createdMillis != null) {
            if (createdMillis in lowerBound..upperBound) {
                return true
            }
            if (createdMillis > upperBound) {
                return false
            }
            // Topic was created before user left: allow only if it had active replies during the away window
            // AND is a genuinely hot/high-drumstick discussion (not an ancient thread bumped once).
            val activeDuringAway = activityMillis != null && activityMillis in lowerBound..upperBound
            val effectiveDrumsticks = maxOf(topic.drumstickCount, enrichedMeta?.drumstickCount ?: 0)
            val effectiveReplies = maxOf(topic.replyCount, enrichedMeta?.replyCount ?: 0)
            val notAncient = createdMillis >= (awayStartMillis - MAX_ACTIVE_OLD_TOPIC_AGE_MILLIS)
            return activeDuringAway && notAncient && (effectiveReplies >= 25 || effectiveDrumsticks >= 4)
        }

        if (activityMillis != null) {
            return activityMillis in lowerBound..upperBound
        }

        // Fallback when timestamp is absent in synthetic/offline data
        return true
    }

    /**
     * Detects limited-time VPS deals, restock alerts, and promo offers (`限时好价 / 补货线报`).
     */
    fun isDealOrPromoTopic(
        topic: TopicSummary,
        drumstickCount: Int = topic.drumstickCount,
    ): Boolean {
        val lowerTitle = topic.title.lowercase(Locale.ROOT)
        val hasFlashDealKeyword = FLASH_DEAL_KEYWORDS.any { keyword ->
            lowerTitle.contains(keyword)
        }
        if (hasFlashDealKeyword) return true

        val slug = topic.categorySlug.trim().lowercase(Locale.ROOT)
        if (slug == "info" || slug == "promotion") {
            return drumstickCount >= 1 || topic.replyCount >= 5 || topic.viewCount >= 180
        }
        if (slug in DEAL_BOARD_SLUGS && drumstickCount >= 3) {
            return true
        }
        return false
    }

    /**
     * Computes composite worth-reading score combining:
     * 1. **鸡腿数 (`drumstickCount`)**: high-signal community endorsement & good-deal indicator
     * 2. **回复数 (`replyCount`)**: discussion depth
     * 3. **热度 (`hotness`)**: engagement velocity (replies & drumsticks per hour since posted),
     *    views, curated `/award` bonus, and limited-time deal/promo (`限时好价`) signal boost.
     */
    fun computeCompositeScore(
        topic: TopicSummary,
        drumstickCount: Int,
        replyCount: Int,
        viewCount: Int,
        isCuratedAward: Boolean,
        createdAtMillis: Long?,
        awayStartMillis: Long,
        reopenedAtMillis: Long,
    ): Double {
        val safeDrumsticks = drumstickCount.coerceAtLeast(0)
        val safeReplies = replyCount.coerceAtLeast(0)
        val safeViews = viewCount.coerceAtLeast(0)

        // 1. Drumsticks (鸡腿数): strongest quality / good-deal signal on NodeSeek
        val drumstickPoints = safeDrumsticks * 15.0 + ln(1.0 + safeDrumsticks) * 10.0 +
            if (isCuratedAward) 26.0 else 0.0

        // 2. Replies (回复数): discussion heat
        val replyPoints = safeReplies * 3.2 + ln(1.0 + safeReplies) * 8.0

        // 3. Views + Engagement Velocity (每小时热度增速)
        val viewPoints = ln(1.0 + safeViews) * 3.2 + (safeViews.coerceAtMost(25_000) / 200.0)

        val anchorTimestamp = createdAtMillis
            ?: parseTopicTimestampMillis(topic.lastActivityIso)
            ?: ((awayStartMillis + reopenedAtMillis) / 2L)
        val ageMillis = (reopenedAtMillis - anchorTimestamp).coerceIn(15L * 60_000L, 72L * 3_600_000L)
        val ageHours = ageMillis / 3_600_000.0
        val weightedEngagement = safeReplies * 1.6 + safeDrumsticks * 6.5 + (safeViews / 160.0)
        val velocityPoints = (weightedEngagement / (ageHours + 0.75)) * 4.5

        var total = drumstickPoints + replyPoints + viewPoints + velocityPoints

        // Boost limited-time deals & restock promos ("限时好价沉下去了")
        val isDeal = isDealOrPromoTopic(topic, safeDrumsticks)
        if (isDeal && (safeDrumsticks >= 1 || safeReplies >= 2 || safeViews >= 60 || FLASH_DEAL_KEYWORDS.any { topic.title.contains(it, ignoreCase = true) })) {
            total = total * 1.18 + 12.0
        }

        // Prefer fresh topics published during the away window over older pre-existing threads
        if (createdAtMillis != null && createdAtMillis < awayStartMillis) {
            total *= 0.78
        }

        return total
    }

    /**
     * Pre-sorts unread away-window candidates using feed-level signals so bounded detail enrichment
     * (`getTopicDetail`) prioritizes the most promising candidates first.
     */
    fun shortlistForDetailEnrichment(
        candidates: List<TopicSummary>,
        readTopicIds: Set<Long>,
        filterConfig: ContentFilterConfig,
        includeBlockedTopics: Boolean,
        enrichedMetaById: Map<Long, CachedTopicDrumstickMeta>,
        curatedAwardIds: Set<Long>,
        awayStartMillis: Long,
        reopenedAtMillis: Long,
        limit: Int,
    ): List<TopicSummary> {
        if (candidates.isEmpty() || limit <= 0) return emptyList()
        val contentFiltered = ContentFilterEngine.filterTopics(
            topics = candidates.filterNot { it.isPinned },
            config = filterConfig,
            includeBlockedTopics = includeBlockedTopics,
            activeBoardSlug = "",
        )
        return contentFiltered
            .asSequence()
            .filter { topic ->
                !topic.isRead &&
                    topic.id !in readTopicIds &&
                    isWithinAwayWindow(
                        topic = topic,
                        enrichedMeta = enrichedMetaById[topic.id],
                        awayStartMillis = awayStartMillis,
                        reopenedAtMillis = reopenedAtMillis,
                    )
            }
            .sortedByDescending { topic ->
                val meta = enrichedMetaById[topic.id]
                val drumsticks = maxOf(topic.drumstickCount, meta?.drumstickCount ?: 0)
                val replies = maxOf(topic.replyCount, meta?.replyCount ?: 0)
                val views = maxOf(topic.viewCount, meta?.viewCount ?: 0)
                computeCompositeScore(
                    topic = topic,
                    drumstickCount = drumsticks,
                    replyCount = replies,
                    viewCount = views,
                    isCuratedAward = topic.id in curatedAwardIds || meta?.isCuratedAward == true,
                    createdAtMillis = parseTopicTimestampMillis(meta?.createdAtIso),
                    awayStartMillis = awayStartMillis,
                    reopenedAtMillis = reopenedAtMillis,
                )
            }
            .take(limit)
            .toList()
    }

    /**
     * Filters away-window candidates (skipping pinned, content-filtered, and already-read topics),
     * ranks all unread candidates by composite score, and returns the Top [maxDigestItems] items
     * along with the full unread reserve pool and [RankedAwayDigestResult.skippedReadCount].
     */
    fun rankUnreadDigest(
        candidates: List<TopicSummary>,
        readTopicIds: Set<Long>,
        bookmarkedTopicIds: Set<Long>,
        filterConfig: ContentFilterConfig,
        includeBlockedTopics: Boolean,
        enrichedMetaById: Map<Long, CachedTopicDrumstickMeta>,
        curatedAwardIds: Set<Long>,
        awayStartMillis: Long,
        reopenedAtMillis: Long,
        maxDigestItems: Int = 10,
    ): RankedAwayDigestResult {
        if (candidates.isEmpty()) {
            return RankedAwayDigestResult(
                allRankedUnread = emptyList(),
                topItems = emptyList(),
                skippedReadCount = 0,
            )
        }

        val deduped = LinkedHashMap<Long, TopicSummary>(candidates.size)
        for (candidate in candidates) {
            if (candidate.id <= 0L || candidate.isPinned) continue
            val existing = deduped[candidate.id]
            if (existing == null) {
                deduped[candidate.id] = candidate
            } else {
                deduped[candidate.id] = existing.copy(
                    viewCount = maxOf(existing.viewCount, candidate.viewCount),
                    replyCount = maxOf(existing.replyCount, candidate.replyCount),
                    drumstickCount = maxOf(existing.drumstickCount, candidate.drumstickCount),
                    isRead = existing.isRead || candidate.isRead,
                    isBookmarked = existing.isBookmarked || candidate.isBookmarked,
                    categorySlug = existing.categorySlug.ifBlank { candidate.categorySlug },
                    categoryName = existing.categoryName.ifBlank { candidate.categoryName },
                    lastActivityIso = existing.lastActivityIso.ifBlank { candidate.lastActivityIso },
                )
            }
        }

        val visibleTopics = ContentFilterEngine.filterTopics(
            topics = deduped.values.toList(),
            config = filterConfig,
            includeBlockedTopics = includeBlockedTopics,
            activeBoardSlug = "",
        )

        var skippedRead = 0
        val unreadScored = ArrayList<AwayDigestItem>(visibleTopics.size)

        for (topic in visibleTopics) {
            val meta = enrichedMetaById[topic.id]
            if (!isWithinAwayWindow(topic, meta, awayStartMillis, reopenedAtMillis)) {
                continue
            }

            val alreadyRead = topic.isRead || topic.id in readTopicIds
            if (alreadyRead) {
                skippedRead += 1
                continue
            }

            val drumsticks = maxOf(topic.drumstickCount, meta?.drumstickCount ?: 0)
            val replies = maxOf(topic.replyCount, meta?.replyCount ?: 0)
            val views = maxOf(topic.viewCount, meta?.viewCount ?: 0)
            val isAward = topic.id in curatedAwardIds || meta?.isCuratedAward == true
            val createdMillis = parseTopicTimestampMillis(meta?.createdAtIso)

            val score = computeCompositeScore(
                topic = topic,
                drumstickCount = drumsticks,
                replyCount = replies,
                viewCount = views,
                isCuratedAward = isAward,
                createdAtMillis = createdMillis,
                awayStartMillis = awayStartMillis,
                reopenedAtMillis = reopenedAtMillis,
            )

            val enrichedTopic = topic.copy(
                viewCount = views,
                replyCount = replies,
                drumstickCount = drumsticks,
                isRead = false,
                isBookmarked = topic.isBookmarked || topic.id in bookmarkedTopicIds,
            )

            unreadScored.add(
                AwayDigestItem(
                    rank = 0,
                    topic = enrichedTopic,
                    drumstickCount = drumsticks,
                    replyCount = replies,
                    viewCount = views,
                    hotnessScore = score,
                    hotnessIndex = score.roundToInt().coerceAtLeast(1),
                    isDealOrPromo = isDealOrPromoTopic(enrichedTopic, drumsticks),
                    isCuratedAward = isAward,
                    publishedAtIso = meta?.createdAtIso?.takeIf { it.isNotBlank() }
                        ?: topic.lastActivityIso,
                ),
            )
        }

        val ranked = unreadScored
            .sortedWith(
                compareByDescending<AwayDigestItem> { it.hotnessScore }
                    .thenByDescending { it.drumstickCount }
                    .thenByDescending { it.replyCount }
                    .thenByDescending { it.viewCount }
                    .thenByDescending { it.topic.id },
            )
            .mapIndexed { index, item ->
                item.copy(rank = index + 1)
            }

        return RankedAwayDigestResult(
            allRankedUnread = ranked,
            topItems = ranked.take(maxDigestItems.coerceAtLeast(1)),
            skippedReadCount = skippedRead,
        )
    }
}
