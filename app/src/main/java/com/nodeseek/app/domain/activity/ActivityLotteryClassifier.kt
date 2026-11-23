package com.nodeseek.app.domain.activity

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

/**
 * Classifies community activity items into explicit-link lotteries (`抽奖`) vs. other activities,
 * extracts draw timestamps (`开奖时间`) from lottery links when missing, and computes lottery-only
 * lifecycle status (`进行中` / `即将开始` / `已结束`).
 *
 * Classification rules:
 * 1. An item counts as [ActivityKind.LOTTERY] **only** if an explicit lottery/draw URL is found
 *    in its `luckyUrl`, `externalLinks`, `bodyMarkdown`, `summary`, or `sourceUrl`.
 *    - Bare tool homepages (e.g. `https://www.nodeseek.com/lucky` with no draw parameters or subpath),
 *      ordinary forum post links (`nodeseek.com/post-123-1`), Telegram links, vendor URLs, and code
 *      repositories (e.g. GitHub scripts mentioning "lottery") are **not** explicit lottery links.
 * 2. Items that lack an explicit lottery link are never classified as [ActivityKind.LOTTERY]; if the
 *    upstream feed labeled them `lottery` via title keywords, they are downgraded to a non-lottery
 *    [ActivityKind] (`FLOOR`, `GIVEAWAY`, `PROMO`, or `EVENT`) and receive `null` lottery status.
 * 3. Lottery time status (`ONGOING` / `UPCOMING` / `ENDED`) applies **only** to explicit-link
 *    lotteries and is evaluated from `startsAtEpochMillis` and the resolved draw time (`endsAtEpochMillis`,
 *    falling back to the `time` query parameter in the lottery URL):
 *    - If server explicitly closed the lottery (`status == ENDED`) or `now >= drawAt` → [ActivityTimeStatus.ENDED].
 *    - Else if `startsAt != null && now < startsAt` → [ActivityTimeStatus.UPCOMING].
 *    - Else if `drawAt != null && now < drawAt` → [ActivityTimeStatus.ONGOING].
 *    - Fallback when `drawAt == null` (e.g. floor-based draw or link without `time` parameter):
 *      if `startsAt == null && status == UPCOMING` → [ActivityTimeStatus.UPCOMING];
 *      otherwise → [ActivityTimeStatus.ONGOING] so open explicit-link lotteries remain joinable.
 */
object ActivityLotteryClassifier {

    private val URL_EXTRACT_REGEX = Regex(
        pattern = """https?://[^\s<>"'\]\[()`*~\u3000-\u303F\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF\uFF00-\uFFEF]+""",
        option = RegexOption.IGNORE_CASE,
    )

    private val TRAILING_URL_PUNCTUATION = charArrayOf(
        '.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '"', '\'', '*', '~',
        '。', '，', '；', '：', '！', '？', '、', '）', '】', '》',
    )

    private val FORUM_PRIMARY_HOSTS = setOf(
        "nodeseek.com",
        "www.nodeseek.com",
        "m.nodeseek.com",
        "linux.do",
        "www.linux.do",
    )

    private val FORUM_LOTTERY_PATH_PREFIXES = setOf(
        "lucky",
        "lottery",
        "raffle",
    )

    private val DEDICATED_LOTTERY_SUBDOMAIN_LABELS = setOf(
        "lucky",
        "lottery",
        "raffle",
        "choujiang",
    )

    private val DEDICATED_LOTTERY_PLATFORM_HOSTS = setOf(
        "gleam.io",
        "www.gleam.io",
        "choujiang.org",
        "www.choujiang.org",
        "m.choujiang.org",
        "sweepwidget.com",
        "www.sweepwidget.com",
        "kingsumo.com",
        "www.kingsumo.com",
        "rafflecopter.com",
        "www.rafflecopter.com",
        "raffall.com",
        "www.raffall.com",
        "randompicker.com",
        "www.randompicker.com",
    )

    private val DRAW_TIME_QUERY_KEYS = listOf(
        "time",
        "draw_time",
        "drawtime",
        "draw_at",
        "drawat",
        "end_time",
        "endtime",
        "end",
        "deadline",
    )

    private val TRACKING_QUERY_KEYS = setOf(
        "ref",
        "from",
        "spm",
        "fbclid",
        "gclid",
        "share",
        "source",
    )

    private const val MIN_VALID_EPOCH_SECONDS = 1_000_000_000L // 2001-09-09
    private const val MAX_VALID_EPOCH_SECONDS = 9_999_999_999L
    private const val MIN_VALID_EPOCH_MILLIS = 1_000_000_000_000L // 2001-09-09

    /**
     * Returns `true` only if [rawUrl] is an explicit, concrete lottery/draw URL
     * (not a bare tool homepage or ordinary forum/vendor link).
     */
    fun isExplicitLotteryUrl(rawUrl: String?): Boolean {
        val cleaned = sanitizeUrlCandidate(rawUrl) ?: return false
        val uri = parseHttpUriOrNull(cleaned) ?: return false
        val host = uri.host?.trim()?.trimEnd('.')?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
            ?: return false
        val pathSegments = (uri.rawPath ?: "")
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val queryParams = parseQueryParams(uri.rawQuery)
        val hasConcreteQuery = queryParams.any { (k, v) ->
            k.isNotBlank() &&
                v.isNotBlank() &&
                !k.startsWith("utm_") &&
                k !in TRACKING_QUERY_KEYS
        }

        // 1. Official forum lottery endpoints, e.g. https://www.nodeseek.com/lucky?post=1073388&time=...
        if (host in FORUM_PRIMARY_HOSTS) {
            val firstSegment = pathSegments.firstOrNull()?.lowercase(Locale.ROOT) ?: return false
            if (firstSegment !in FORUM_LOTTERY_PATH_PREFIXES) return false
            val hasSubPath = pathSegments.size > 1
            return hasConcreteQuery || hasSubPath
        }

        // 2. Dedicated lottery subdomains, e.g. https://lucky.nodeseek.com/?post=618001 or lottery.linux.do/draw/42
        val hostLabels = host.split('.').filter { it.isNotEmpty() }
        if (hostLabels.size >= 3 && hostLabels.first() in DEDICATED_LOTTERY_SUBDOMAIN_LABELS) {
            val hasPath = pathSegments.isNotEmpty()
            return hasConcreteQuery || hasPath
        }

        // 3. Dedicated third-party lottery/raffle platforms (must point to a campaign/draw, not homepage)
        if (host in DEDICATED_LOTTERY_PLATFORM_HOSTS) {
            val hasPath = pathSegments.isNotEmpty()
            return hasPath || hasConcreteQuery
        }

        return false
    }

    /**
     * Extracts candidate HTTP(S) URLs from plain text or Markdown content.
     */
    fun extractUrlsFromText(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return URL_EXTRACT_REGEX.findAll(text)
            .mapNotNull { sanitizeUrlCandidate(it.value) }
            .distinct()
            .toList()
    }

    /**
     * Searches available activity fields in priority order and returns the first explicit lottery URL,
     * or `null` when no explicit lottery link exists.
     */
    fun findExplicitLotteryUrl(
        luckyUrl: String? = null,
        externalLinks: List<ActivityLabeledLink> = emptyList(),
        bodyMarkdown: String = "",
        summary: String = "",
        sourceUrl: String = "",
    ): String? {
        sanitizeUrlCandidate(luckyUrl)?.takeIf(::isExplicitLotteryUrl)?.let { return it }

        for (link in externalLinks) {
            sanitizeUrlCandidate(link.url)?.takeIf(::isExplicitLotteryUrl)?.let { return it }
        }

        if (bodyMarkdown.isNotBlank()) {
            extractUrlsFromText(bodyMarkdown).firstOrNull(::isExplicitLotteryUrl)?.let { return it }
        }

        if (summary.isNotBlank()) {
            extractUrlsFromText(summary).firstOrNull(::isExplicitLotteryUrl)?.let { return it }
        }

        sanitizeUrlCandidate(sourceUrl)?.takeIf(::isExplicitLotteryUrl)?.let { return it }

        return null
    }

    /**
     * Extracts the draw timestamp (`开奖时间`, in epoch milliseconds) from an explicit lottery URL's
     * query parameters (e.g. `time=1795438800000` on `nodeseek.com/lucky`), supporting 13-digit epoch
     * millis, 10-digit epoch seconds, and ISO-8601 timestamps.
     */
    fun extractDrawTimeEpochMillis(lotteryUrl: String?): Long? {
        val cleaned = sanitizeUrlCandidate(lotteryUrl) ?: return null
        if (!isExplicitLotteryUrl(cleaned)) return null
        val uri = parseHttpUriOrNull(cleaned) ?: return null
        val params = parseQueryParams(uri.rawQuery)
        if (params.isEmpty()) return null

        for (key in DRAW_TIME_QUERY_KEYS) {
            val rawValue = params[key]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            parseTimestampEpochMillis(rawValue)?.let { return it }
        }
        return null
    }

    /**
     * Returns the non-lottery fallback [ActivityKind] when an upstream record was tagged `LOTTERY`
     * without an explicit lottery link.
     */
    fun resolveNonLotteryFallbackKind(item: ForumWatchActivityItem): ActivityKind {
        if (item.kind != ActivityKind.LOTTERY) return item.kind
        if (item.drawFloor != null && item.drawFloor > 0) return ActivityKind.FLOOR
        val lowerTags = item.tags.map { it.trim().lowercase(Locale.ROOT) }
        if (lowerTags.any {
                it.contains("福利") || it.contains("赠送") || it.contains("免费") || it == "giveaway"
            }
        ) {
            return ActivityKind.GIVEAWAY
        }
        if (lowerTags.any {
                it.contains("特惠") || it.contains("闪购") || it.contains("促销") || it == "promo"
            }
        ) {
            return ActivityKind.PROMO
        }
        return ActivityKind.EVENT
    }

    /**
     * Normalizes a [ForumWatchActivityItem] (optionally enriched by [bodyMarkdown] / [externalLinks])
     * so `luckyUrl`, `kind`, and draw time (`endsAtEpochMillis`) strictly reflect explicit lottery links.
     */
    fun normalizeItem(
        item: ForumWatchActivityItem,
        bodyMarkdown: String = "",
        externalLinks: List<ActivityLabeledLink> = emptyList(),
    ): ForumWatchActivityItem {
        val explicitUrl = findExplicitLotteryUrl(
            luckyUrl = item.luckyUrl,
            externalLinks = externalLinks,
            bodyMarkdown = bodyMarkdown,
            summary = item.summary,
            sourceUrl = item.sourceUrl,
        )
        return if (explicitUrl != null) {
            val resolvedDrawTime = item.endsAtEpochMillis?.takeIf { it > 0L }
                ?: extractDrawTimeEpochMillis(explicitUrl)
            item.copy(
                kind = ActivityKind.LOTTERY,
                luckyUrl = explicitUrl,
                endsAtEpochMillis = resolvedDrawTime,
            )
        } else {
            val normalizedKind = resolveNonLotteryFallbackKind(item)
            if (normalizedKind == item.kind && item.luckyUrl == null) {
                item
            } else {
                item.copy(
                    kind = normalizedKind,
                    luckyUrl = null,
                )
            }
        }
    }

    /**
     * Normalizes a [ForumWatchActivityDetail] by scanning its `bodyMarkdown` and `externalLinks`
     * in addition to the item's list fields.
     */
    fun normalizeDetail(detail: ForumWatchActivityDetail): ForumWatchActivityDetail {
        val normalizedItem = normalizeItem(
            item = detail.item,
            bodyMarkdown = detail.bodyMarkdown,
            externalLinks = detail.externalLinks,
        )
        return if (normalizedItem == detail.item) detail else detail.copy(item = normalizedItem)
    }

    /**
     * Computes lottery lifecycle status (`ONGOING` / `UPCOMING` / `ENDED`) exclusively for items
     * containing an explicit lottery link; returns `null` for all non-lottery activities.
     */
    fun resolveLotteryStatusOrNull(
        item: ForumWatchActivityItem,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): ActivityTimeStatus? {
        val explicitUrl = item.resolvedLotteryUrl ?: return null
        val drawAt = item.endsAtEpochMillis?.takeIf { it > 0L }
            ?: extractDrawTimeEpochMillis(explicitUrl)
        return resolveTimeStatusFromBounds(
            serverStatus = item.status,
            startsAtEpochMillis = item.startsAtEpochMillis,
            drawAtEpochMillis = drawAt,
            nowEpochMillis = nowEpochMillis,
        )
    }

    /**
     * Shared status bucketing from start/draw timestamps and server terminal flag:
     * 1. Server terminal `ENDED` → `ENDED` (never revives closed/locked lotteries).
     * 2. `drawAt != null && now >= drawAt` → `ENDED`.
     * 3. `startsAt != null && now < startsAt` → `UPCOMING`.
     * 4. `drawAt != null && now < drawAt` → `ONGOING`.
     * 5. Fallback when `drawAt == null`:
     *    - `startsAt == null && serverStatus == UPCOMING` → `UPCOMING`.
     *    - Otherwise → `ONGOING`.
     */
    internal fun resolveTimeStatusFromBounds(
        serverStatus: ActivityTimeStatus,
        startsAtEpochMillis: Long?,
        drawAtEpochMillis: Long?,
        nowEpochMillis: Long,
    ): ActivityTimeStatus {
        if (serverStatus == ActivityTimeStatus.ENDED) {
            return ActivityTimeStatus.ENDED
        }
        val drawAt = drawAtEpochMillis?.takeIf { it > 0L }
        val startsAt = startsAtEpochMillis?.takeIf { it > 0L }
        if (drawAt != null && nowEpochMillis >= drawAt) {
            return ActivityTimeStatus.ENDED
        }
        if (startsAt != null && nowEpochMillis < startsAt) {
            return ActivityTimeStatus.UPCOMING
        }
        if (drawAt != null) {
            return ActivityTimeStatus.ONGOING
        }
        // Fallback when no draw time is available: respect explicit UPCOMING if no start time was set,
        // otherwise default to ONGOING so active explicit-link lotteries remain joinable.
        return if (startsAt == null && serverStatus == ActivityTimeStatus.UPCOMING) {
            ActivityTimeStatus.UPCOMING
        } else {
            ActivityTimeStatus.ONGOING
        }
    }

    internal fun sanitizeUrlCandidate(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var trimmed = raw.trim()
            .replace("&amp;", "&", ignoreCase = true)
            .trimStart('<', '(', '[', '"', '\'', '*', '~')
        while (trimmed.isNotEmpty() && trimmed.last() in TRAILING_URL_PUNCTUATION) {
            trimmed = trimmed.dropLast(1)
        }
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return trimmed
    }

    private fun parseHttpUriOrNull(url: String): URI? =
        runCatching {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()) uri else null
        }.getOrNull()

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isBlank()) continue
            val eqIdx = pair.indexOf('=')
            val rawKey = if (eqIdx >= 0) pair.substring(0, eqIdx) else pair
            val rawVal = if (eqIdx >= 0) pair.substring(eqIdx + 1) else ""
            val key = decodeUrlComponent(rawKey).trim().lowercase(Locale.ROOT)
            val value = decodeUrlComponent(rawVal).trim()
            if (key.isNotEmpty() && key !in result) {
                result[key] = value
            }
        }
        return result
    }

    private fun decodeUrlComponent(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

    private fun parseTimestampEpochMillis(raw: String): Long? {
        val numeric = raw.toLongOrNull()
        if (numeric != null) {
            return when {
                numeric >= MIN_VALID_EPOCH_MILLIS -> numeric
                numeric in MIN_VALID_EPOCH_SECONDS..MAX_VALID_EPOCH_SECONDS -> numeric * 1_000L
                else -> null
            }
        }
        runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
            ?.takeIf { it >= MIN_VALID_EPOCH_MILLIS }
            ?.let { return it }
        runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
            ?.takeIf { it >= MIN_VALID_EPOCH_MILLIS }
            ?.let { return it }
        return null
    }
}
