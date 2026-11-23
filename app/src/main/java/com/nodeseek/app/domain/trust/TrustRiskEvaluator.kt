package com.nodeseek.app.domain.trust

import com.nodeseek.app.domain.model.TopicSummary
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Pure domain evaluator for Seller Reputation & Risk Check ("交易前查一查"):
 * - Parses diverse NodeSeek registration timestamp formats into tenure days & labels
 * - Identifies trade-related topics from category slug/name and common NodeSeek trade markers
 * - Filters & deduplicates station search exposure hits strictly referencing the target username
 * - Computes deterministic RED / AMBER / CLEAR risk assessments
 */
object TrustRiskEvaluator {

    const val NEW_ACCOUNT_THRESHOLD_DAYS: Int = 30
    const val LOW_RANK_THRESHOLD: Int = 1

    const val TERM_SCAMMER: String = "骗子"
    const val TERM_RUNAWAY: String = "跑路"
    const val TERM_CALLOUT: String = "挂人"
    const val TERM_EXPOSE: String = "曝光"

    private val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    private val DISPLAY_DATE_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    private val DATE_TIME_PATTERNS: List<DateTimeFormatter> = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-M-d H:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss", Locale.US),
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
    )

    private val DATE_ONLY_PATTERNS: List<DateTimeFormatter> = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-M-d", Locale.US),
        DateTimeFormatter.ofPattern("yyyy/M/d", Locale.US),
        DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.US),
        DateTimeFormatter.ofPattern("yyyy年MM月dd日", Locale.CHINA),
        DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA),
    )

    private val DATE_PREFIX_STRIP_REGEX = Regex("^(?:注册于|加入于|创建于|joined\\s*on|joined)\\s*[:：]?\\s*", RegexOption.IGNORE_CASE)
    private val RELATIVE_CN_REGEX = Regex("^(\\d+)\\s*(秒|分钟|分|小时|时|天|日|周|星期|个月|月|年)前$")
    private val RELATIVE_EN_REGEX = Regex("^(\\d+)\\s*(second|sec|minute|min|hour|hr|day|week|month|year)s?\\s+ago$", RegexOption.IGNORE_CASE)

    private val EXPOSURE_TITLE_PREFIX_REGEX = Regex(
        """^(?:[\[\【(（「『]\s*)?(?:曝光|挂人|避雷|维权|仲裁|被骗|骗子)""",
        RegexOption.IGNORE_CASE,
    )

    private val CLOSED_TRADE_ONLY_REGEX = Regex(
        """^(?:[\[\【(（「『]?\s*(?:已出|出了|已售|已秒|秒出|已收|收到了|已收到|已交易|交易完成)\s*[\]\】)）」』]?)(?:[\s,，.。!！~～/|\-—:：]*(?:封贴|封帖|结贴|结帖|勿念|勿扰|勿pm|谢谢|感谢|老哥|老板|mjj|bd|绑定)*)*$""",
        RegexOption.IGNORE_CASE,
    )

    private val BRACKETED_TRADE_TAG_REGEX = Regex(
        """[\[\【(（「『]\s*(?:出|收|卖|买|交易|转|转让|已出|已收|已售|已秒|求购|诚收|急出|急收|慢收|好价出|低价出|换|互换|换鸡)\s*[\]\】)）」』]""",
    )

    private val TRADE_TITLE_PHRASES: List<String> = listOf(
        "剩余价值", "明盘", "暗盘", "原邮", "原油", "带邮", "改邮",
        "包push", "不包push", "走中介", "中介费", "先款", "先货", "口令红包",
        "出鸡", "收鸡", "出小鸡", "收小鸡", "出母鸡", "换鸡", "出独服", "收独服",
        "出瓦工", "收瓦工", "出搬瓦工", "收搬瓦工", "出大妈", "收大妈", "出绿云", "收绿云",
        "出斯巴达", "收斯巴达", "出爪云", "收爪云", "出甲骨文", "收甲骨文", "出龟壳", "收龟壳",
        "出域名", "收域名", "出米", "收米", "出邀请码", "收邀请码", "出闲置", "收闲置", "出传家宝", "收传家宝",
        "出一台", "收一台", "出两台", "收两台", "出几台", "收几台",
        "折价出", "折价收", "溢价出", "溢价收", "平价出", "平价收",
        "骨折出", "亏本出", "回血出", "清仓出", "打包出", "原价出",
        "好价出", "好价收", "低价出", "高价收", "急出", "急收", "慢收",
        "佛系出", "佛系收", "随缘出", "随缘收", "诚收", "诚心收", "诚心出",
        "求购", "出售", "秒出", "交易完成", "已交易",
    )

    private val QUANTIFIER_TRADE_REGEX = Regex(
        """(?:出|收)一?(?:个|台|只|枚|套)(?!\s*(?:主意|难题|问题|教程|测评|评测|脚本|方案|视频|文章|办法|招|坑|故障|bug|error))""",
        RegexOption.IGNORE_CASE,
    )

    private val LEADING_TRADE_VERB_REGEX = Regex(
        """^(?:继续|再|长期|大量|无限)?[出收](?:[\s:：/\-]+|[A-Za-z0-9])""",
    )

    private val LEADING_NON_TRADE_VERB_REGEX = Regex(
        """^(?:出\s*(?:bug|error|问题|故障|事|错|状况)|收\s*(?:email|mail|sms|验证码|邮件|信件))""",
        RegexOption.IGNORE_CASE,
    )

    private val LEADING_SOLD_STATUS_REGEX = Regex(
        """^(?:已出|已收|已售|已秒|出了|收到了)(?:[\s,，.。!！/|\-—:：]|封贴|封帖)""",
    )

    private val TRAILING_SOLD_STATUS_REGEX = Regex(
        """(?:[\s,，.。!！/|\-—:：~～A-Za-z0-9刀元块uUrR])(?:已出|已收|已售|已秒)(?:[\s,，.。!！~～/|\-—:：]*(?:封贴|封帖|结贴|结帖|勿念|勿扰|勿pm|谢谢|感谢)*)?$""",
        RegexOption.IGNORE_CASE,
    )

    private val NON_TRADE_YICHU_REGEX = Regex(
        """(?:结果|公告|版本|更新|补丁|名单|账单|报告|教程|评测|测评|消息|通知|政策|规定|细则|脚本|方案)\s*已出""",
    )

    private val PRICE_VERB_TRADE_REGEX = Regex(
        """(?:¥|￥|\$)?\d+(?:\.\d+)?\s*(?:元|块|刀|u|U|r|R|rmb|RMB|cny|CNY|usd|USD|hkd|HKD|欧|镑|折)?\s*(?:包邮|包push|不包push|改邮|带邮|原邮)?\s*[出收](?![现海口发来错品席身生版差站墙狱到藏益费录入拾缩敛件信割尾官])""",
    )

    private val RMB_SLANG_PRICE_REGEX = Regex(
        """(?<![A-Za-z0-9_-])\d{2,5}(?:\.\d{1,2})?\s*[rR](?![A-Za-z0-9_])""",
    )

    private val VPS_BILLING_CONTEXT_REGEX = Regex(
        """(?:刀|年付|月付|季付|三年付|到期|剩余|瓦工|搬瓦工|大妈|绿云|斯巴达|爪云|dmit|bwg|racknerd|\brn\b|claw|netcup|ovh|vir|cc|vps)""",
        RegexOption.IGNORE_CASE,
    )

    private val PUSH_WORD_REGEX = Regex("(?<![A-Za-z0-9_])push(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
    private val NON_TRADE_PUSH_REGEX = Regex("\\b(?:git|docker|branch|github|gitlab|code|commit|force|web|apns|fcm)\\s+push\\b", RegexOption.IGNORE_CASE)

    private val SCAMMER_SYNONYMS: List<String> = listOf("骗子", "诈骗", "被骗", "行骗", "骗钱", "骗鸡", "骗走", "老赖", "骗术")
    private val RUNAWAY_SYNONYMS: List<String> = listOf("跑路", "卷款", "失联", "收款拉黑", "删号跑")
    private val CALLOUT_SYNONYMS: List<String> = listOf("挂人", "避雷", "避坑", "黑名单", "拉黑")
    private val EXPOSE_SYNONYMS: List<String> = listOf("曝光", "实锤", "维权", "交易纠纷", "仲裁")
    private val AMBIGUOUS_SHORT_ASCII_REGEX = Regex("^[A-Za-z0-9_]{1,3}$")
    private val AMBIGUOUS_GENERIC_USERNAMES: Set<String> = setOf(
        "vps", "cloud", "hk", "us", "jp", "sg", "uk", "de", "kr", "tw",
        "nat", "cn2", "bgp", "ip", "ssh", "dns", "cdn", "ssl", "api", "ai",
        "dmit", "bwg", "rn", "cc", "claw", "push",
    )

    /**
     * Builds the single-request `(query, categorySlug)` pair for on-demand historical exposure deep search.
     *
     * Consolidates legacy multi-query searches (`"曝光 <user>"` + `"骗子 <user>"`) into one username
     * query, scoping broad/ambiguous short tokens to the `expose` board to avoid irrelevant noise.
     */
    fun buildDeepSearchQuery(canonicalUsername: String): Pair<String, String> {
        val clean = canonicalUsername.trim().removePrefix("@")
        val categorySlug = if (isBroadAmbiguousUsername(clean)) "expose" else ""
        return clean to categorySlug
    }

    /**
     * Returns true when [username] is short ASCII (`<= 3` chars) or matches a common VPS/forum term
     * where an unscoped site search would return unrelated trade/discussion noise.
     */
    fun isBroadAmbiguousUsername(username: String): Boolean {
        val clean = username.trim().removePrefix("@")
        if (clean.isEmpty()) return false
        return AMBIGUOUS_SHORT_ASCII_REGEX.matches(clean) ||
            clean.lowercase(Locale.ROOT) in AMBIGUOUS_GENERIC_USERNAMES
    }

    /**
     * Builds an external Google site-search URL for checking NodeSeek exposure posts without hitting
     * NodeSeek's `/search` rate limits.
     */
    fun buildExternalExposureSearchUrl(username: String): String {
        val clean = username.trim().removePrefix("@")
        val rawQuery = if (clean.isEmpty()) {
            "site:www.nodeseek.com 曝光"
        } else {
            "site:www.nodeseek.com \"$clean\" (曝光 OR 骗子 OR 跑路 OR 挂人 OR 避雷)"
        }
        val encoded = runCatching { URLEncoder.encode(rawQuery, "UTF-8") }.getOrDefault(rawQuery)
        return "https://www.google.com/search?q=$encoded"
    }

    /**
     * Parses a raw NodeSeek registration timestamp (`ISO-8601`, `yyyy-MM-dd`, `yyyy-MM-dd HH:mm:ss`,
     * `yyyy/MM/dd`, Chinese date, RFC-1123, epoch sec/ms, or relative time strings like `18天前`/`2年前`)
     * into a structured [RegistrationTenure].
     */
    fun parseRegistrationTenure(
        rawJoinedAt: String?,
        nowEpochMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = DEFAULT_ZONE,
    ): RegistrationTenure {
        val trimmed = rawJoinedAt?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return RegistrationTenure(
                createdAtText = "",
                registrationDays = null,
                durationLabel = "注册时间未知",
            )
        }

        val cleaned = DATE_PREFIX_STRIP_REGEX.replaceFirst(trimmed, "").trim()
        val nowInstant = Instant.ofEpochMilli(nowEpochMillis)
        val nowDate = nowInstant.atZone(zoneId).toLocalDate()

        // 1. Try exact instant / date-time / date parsing
        parseExactRegistrationDate(cleaned, zoneId)?.let { createdDate ->
            val days = ChronoUnit.DAYS.between(createdDate, nowDate).toInt().coerceAtLeast(0)
            val durationLabel = formatExactDurationLabel(
                createdDate = createdDate,
                nowDate = nowDate,
                days = days,
            )
            return RegistrationTenure(
                createdAtText = DISPLAY_DATE_FORMATTER.format(createdDate),
                registrationDays = days,
                durationLabel = durationLabel,
            )
        }

        // 2. Try relative strings (`刚刚`, `昨天`, `18天前`, `2年前`, `15 days ago`, etc.)
        parseRelativeRegistrationDays(cleaned)?.let { relDays ->
            val safeDays = relDays.coerceAtLeast(0)
            val approxDate = nowDate.minusDays(safeDays.toLong())
            val durationLabel = formatApproximateDurationLabel(days = safeDays)
            return RegistrationTenure(
                createdAtText = DISPLAY_DATE_FORMATTER.format(approxDate),
                registrationDays = safeDays,
                durationLabel = durationLabel,
            )
        }

        return RegistrationTenure(
            createdAtText = cleaned,
            registrationDays = null,
            durationLabel = "注册时间未知",
        )
    }

    private fun parseExactRegistrationDate(value: String, zoneId: ZoneId): LocalDate? {
        if (value.isBlank()) return null

        // Numeric epoch (seconds or milliseconds)
        value.toLongOrNull()?.let { numeric ->
            if (numeric > 0L) {
                val epochMillis = if (numeric < 10_000_000_000L) numeric * 1000L else numeric
                return Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate()
            }
        }

        // ISO-8601 Instant (`2024-03-01T12:00:00Z`)
        runCatching {
            return Instant.parse(value).atZone(zoneId).toLocalDate()
        }

        // ISO-8601 OffsetDateTime (`2024-03-01T20:00:00+08:00`)
        runCatching {
            return OffsetDateTime.parse(value).atZoneSameInstant(zoneId).toLocalDate()
        }

        // Zoned / RFC-1123 (`Fri, 01 Mar 2024 12:00:00 GMT`)
        runCatching {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                .withZoneSameInstant(zoneId)
                .toLocalDate()
        }

        for (formatter in DATE_TIME_PATTERNS) {
            runCatching {
                return LocalDateTime.parse(value, formatter).toLocalDate()
            }
        }

        for (formatter in DATE_ONLY_PATTERNS) {
            runCatching {
                return LocalDate.parse(value, formatter)
            }
        }

        return null
    }

    private fun parseRelativeRegistrationDays(value: String): Int? {
        val lower = value.lowercase(Locale.ROOT)
        when (lower) {
            "刚刚", "刚才", "今天", "今日", "just now", "today" -> return 0
            "昨天", "昨日", "yesterday" -> return 1
            "前天" -> return 2
        }

        RELATIVE_CN_REGEX.matchEntire(value)?.let { match ->
            val amount = match.groupValues[1].toIntOrNull() ?: return null
            return when (match.groupValues[2]) {
                "秒", "分钟", "分", "小时", "时" -> 0
                "天", "日" -> amount
                "周", "星期" -> amount * 7
                "个月", "月" -> amount * 30
                "年" -> amount * 365
                else -> null
            }
        }

        RELATIVE_EN_REGEX.matchEntire(lower)?.let { match ->
            val amount = match.groupValues[1].toIntOrNull() ?: return null
            return when (match.groupValues[2]) {
                "second", "sec", "minute", "min", "hour", "hr" -> 0
                "day" -> amount
                "week" -> amount * 7
                "month" -> amount * 30
                "year" -> amount * 365
                else -> null
            }
        }

        return null
    }

    private fun formatExactDurationLabel(
        createdDate: LocalDate,
        nowDate: LocalDate,
        days: Int,
    ): String {
        if (days <= 0) return "今日注册 · 新号"
        if (days < NEW_ACCOUNT_THRESHOLD_DAYS) return "${days}天 · 新号"

        val period = Period.between(createdDate, nowDate)
        val years = period.years.coerceAtLeast(0)
        val months = period.months.coerceAtLeast(0)
        val remDays = period.days.coerceAtLeast(0)

        return when {
            years >= 1 && months > 0 -> "${years}年 ${months}个月"
            years >= 1 -> "${years}年"
            months >= 1 && remDays > 0 -> "${months}个月 ${remDays}天"
            months >= 1 -> "${months}个月"
            else -> "${days}天"
        }
    }

    private fun formatApproximateDurationLabel(days: Int): String {
        if (days <= 0) return "今日注册 · 新号"
        if (days < NEW_ACCOUNT_THRESHOLD_DAYS) return "${days}天 · 新号"

        val years = days / 365
        val remAfterYears = days % 365
        val months = remAfterYears / 30
        val remDays = remAfterYears % 30

        return when {
            years >= 1 && months > 0 -> "${years}年 ${months}个月"
            years >= 1 -> "${years}年"
            months >= 1 && remDays > 0 -> "${months}个月 ${remDays}天"
            months >= 1 -> "${months}个月"
            else -> "${days}天"
        }
    }

    /**
     * Determines whether a user's posted [TopicSummary] belongs to trading activity.
     *
     * Note: NodeSeek's `/api/content/list-discussions` returns `{ post_id, title, rank }` without
     * `category`, so when `categorySlug` is unknown this classifier also matches real-world NodeSeek
     * unbracketed trade verbs (`出 DMIT...`, `收个...`), price-verb patterns (`280R出`, `60元出`),
     * and post-sale edited titles (`已出`, `出了 封贴`, `[已出] ...`) while guarding against
     * exposure reports and technical/daily false positives.
     */
    fun isTradeTopic(topic: TopicSummary): Boolean {
        if (topic.categorySlug.equals("trade", ignoreCase = true)) return true
        if (topic.categoryName.trim() == "交易") return true
        if (topic.categorySlug.equals("expose", ignoreCase = true) || topic.categoryName.trim() == "曝光") {
            return false
        }

        val title = topic.title.trim()
        if (title.isEmpty()) return false
        if (EXPOSURE_TITLE_PREFIX_REGEX.containsMatchIn(title)) return false

        if (CLOSED_TRADE_ONLY_REGEX.matches(title)) return true
        if (BRACKETED_TRADE_TAG_REGEX.containsMatchIn(title)) return true

        if (TRADE_TITLE_PHRASES.any { phrase -> title.contains(phrase, ignoreCase = true) }) {
            return true
        }

        if (QUANTIFIER_TRADE_REGEX.containsMatchIn(title)) return true

        if (LEADING_TRADE_VERB_REGEX.containsMatchIn(title) && !LEADING_NON_TRADE_VERB_REGEX.containsMatchIn(title)) {
            return true
        }

        if (!NON_TRADE_YICHU_REGEX.containsMatchIn(title) &&
            (LEADING_SOLD_STATUS_REGEX.containsMatchIn(title) || TRAILING_SOLD_STATUS_REGEX.containsMatchIn(title))
        ) {
            return true
        }

        if (PRICE_VERB_TRADE_REGEX.containsMatchIn(title)) return true

        if (RMB_SLANG_PRICE_REGEX.containsMatchIn(title) && VPS_BILLING_CONTEXT_REGEX.containsMatchIn(title)) {
            return true
        }

        if (PUSH_WORD_REGEX.containsMatchIn(title) && !NON_TRADE_PUSH_REGEX.containsMatchIn(title)) {
            return true
        }

        return false
    }

    /**
     * Checks whether [text] genuinely references [targetUsername], using ASCII identifier-boundary
     * guards for alphanumeric edges so short usernames like `bob` match `曝光bob跑路` / `曝光 @bob 骗子`
     * without falsely matching `bobby`, `my_bob`, or `bob123`.
     */
    fun referencesTargetUsername(text: String, targetUsername: String): Boolean {
        val cleanTarget = targetUsername.trim()
        if (cleanTarget.isEmpty() || text.isBlank()) return false

        val firstAscii = isAsciiIdentifierChar(cleanTarget.first())
        val lastAscii = isAsciiIdentifierChar(cleanTarget.last())

        val prefix = if (firstAscii) "(?<![A-Za-z0-9_])" else ""
        val suffix = if (lastAscii) "(?![A-Za-z0-9_])" else ""
        val pattern = Regex(prefix + Regex.escape(cleanTarget) + suffix, RegexOption.IGNORE_CASE)
        return pattern.containsMatchIn(text)
    }

    /**
     * Stricter username reference check for exposure matching:
     * - For distinct usernames, uses standard boundary matching ([referencesTargetUsername]).
     * - For broad/ambiguous short ASCII or generic VPS terms (e.g., `vps`, `dmit`, `hk`, `push`),
     *   requires explicit `@mention` or immediate accusation/user-label context so unrelated expose
     *   titles like `"曝光骗子 @badguy 出 DMIT HK VPS 跑路"` never falsely flag a seller named `vps`.
     */
    internal fun referencesTargetUsernameForExposure(text: String, targetUsername: String): Boolean {
        val cleanTarget = targetUsername.trim().removePrefix("@")
        if (cleanTarget.isEmpty() || text.isBlank()) return false
        if (!isBroadAmbiguousUsername(cleanTarget)) {
            return referencesTargetUsername(text, cleanTarget)
        }

        val escaped = Regex.escape(cleanTarget)
        val explicitMention = Regex("""(?<![A-Za-z0-9_])@$escaped(?![A-Za-z0-9_])""", RegexOption.IGNORE_CASE)
        if (explicitMention.containsMatchIn(text)) return true

        val contextualPattern = Regex(
            pattern = """(?:曝光|挂人|挂|骗子|避雷|避坑|实锤|拉黑|举报|用户|卖家|买家|坛友|老哥|\bid\b|\buid\b|账号|用户名)\s*(?:[:：是为叫]\s*)?@?$escaped(?![A-Za-z0-9_])(?!\s*(?:商家|主机|服务器|小鸡|母鸡|线路|机房|套餐|节点|独服|年付|月付|优惠|促销|面板))""",
            option = RegexOption.IGNORE_CASE,
        )
        return contextualPattern.containsMatchIn(text)
    }

    private fun isAsciiIdentifierChar(ch: Char): Boolean =
        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_'

    /**
     * Extracts the highest-priority exposure term (`骗子` / `跑路` / `挂人` / `曝光`) from the
     * topic's title, snippet, or `expose` board category. Returns `null` if the thread appears
     * to be unrelated search noise with no exposure/scam context.
     */
    fun detectMatchedExposureTerm(
        topic: TopicSummary,
        snippet: String? = null,
        queryTermHint: String? = null,
    ): String? {
        val combined = buildString {
            append(topic.title)
            if (!snippet.isNullOrBlank()) {
                append(' ')
                append(snippet)
            }
        }

        // Exact canonical terms first (highest severity wins)
        if (combined.contains(TERM_SCAMMER)) return TERM_SCAMMER
        if (combined.contains(TERM_RUNAWAY)) return TERM_RUNAWAY
        if (combined.contains(TERM_CALLOUT)) return TERM_CALLOUT
        if (combined.contains(TERM_EXPOSE)) return TERM_EXPOSE

        // Strong Chinese scam/exposure synonyms
        if (SCAMMER_SYNONYMS.any { combined.contains(it) }) return TERM_SCAMMER
        if (RUNAWAY_SYNONYMS.any { combined.contains(it) }) return TERM_RUNAWAY
        if (CALLOUT_SYNONYMS.any { combined.contains(it) }) return TERM_CALLOUT
        if (EXPOSE_SYNONYMS.any { combined.contains(it) }) return TERM_EXPOSE

        // Thread is inside the official "曝光" (`expose`) board
        val isExposeBoard = topic.categorySlug.equals("expose", ignoreCase = true) ||
            topic.categoryName.contains(TERM_EXPOSE)
        if (isExposeBoard) {
            return when (queryTermHint?.trim()) {
                TERM_SCAMMER, TERM_RUNAWAY, TERM_CALLOUT, TERM_EXPOSE -> queryTermHint.trim()
                else -> TERM_EXPOSE
            }
        }

        return null
    }

    /**
     * Convenience overload filtering plain [TopicSummary] lists into deduplicated [ExposureHitTopic]s.
     */
    fun filterExposureTopics(
        topics: List<TopicSummary>,
        targetUid: Long,
        targetUsername: String,
        queryTermHint: String? = null,
    ): List<ExposureHitTopic> = filterExposureHits(
        candidates = topics.map { ExposureCandidate(topic = it, queryTermHint = queryTermHint) },
        targetUid = targetUid,
        targetUsername = targetUsername,
    )

    /**
     * Filters candidate search results to genuine exposure threads mentioning [targetUsername],
     * excluding self-authored threads and unrelated noise, and deduplicates by `topicId`.
     */
    fun filterExposureHits(
        candidates: List<ExposureCandidate>,
        targetUid: Long,
        targetUsername: String,
    ): List<ExposureHitTopic> {
        val cleanTarget = targetUsername.trim()
        if (cleanTarget.isEmpty() || candidates.isEmpty()) return emptyList()

        val deduplicated = LinkedHashMap<Long, ExposureHitTopic>()

        for (candidate in candidates) {
            val topic = candidate.topic
            if (topic.id <= 0L) continue

            // Avoid false positive when the seller themselves posted an exposure thread about someone else
            val isAuthoredByTarget = (targetUid > 0L && topic.authorId == targetUid) ||
                (topic.authorName.isNotBlank() && topic.authorName.equals(cleanTarget, ignoreCase = true))
            if (isAuthoredByTarget) continue

            val mentionsTargetInTitle = referencesTargetUsernameForExposure(topic.title, cleanTarget)
            val mentionsTargetInSnippet = !candidate.snippet.isNullOrBlank() &&
                referencesTargetUsernameForExposure(candidate.snippet, cleanTarget)
            if (!mentionsTargetInTitle && !mentionsTargetInSnippet) continue

            val matchedTerm = detectMatchedExposureTerm(
                topic = topic,
                snippet = candidate.snippet,
                queryTermHint = candidate.queryTermHint,
            ) ?: continue

            val hit = ExposureHitTopic(
                topicId = topic.id,
                title = topic.title.trim(),
                categoryName = topic.categoryName.trim().ifEmpty {
                    if (topic.categorySlug.equals("expose", ignoreCase = true)) TERM_EXPOSE else "站内"
                },
                authorName = topic.authorName.trim(),
                publishedAtText = topic.lastActivityIso.trim(),
                matchedTerm = matchedTerm,
                categorySlug = topic.categorySlug.trim(),
            )

            val existing = deduplicated[topic.id]
            if (existing == null || termSeverity(hit.matchedTerm) > termSeverity(existing.matchedTerm)) {
                deduplicated[topic.id] = hit
            }
        }

        return deduplicated.values.sortedWith(
            compareByDescending<ExposureHitTopic> { termSeverity(it.matchedTerm) }
                .thenByDescending { it.topicId },
        )
    }

    private fun termSeverity(term: String): Int = when (term) {
        TERM_SCAMMER -> 4
        TERM_RUNAWAY -> 3
        TERM_CALLOUT -> 2
        TERM_EXPOSE -> 1
        else -> 0
    }

    /**
     * Evaluates all seller trust signals into a structured [TrustRiskAssessment].
     *
     * Rules:
     * - [TrustRiskLevel.HIGH_RISK_RED]: `exposureSearchHits.isNotEmpty()` OR `localNote?.tag == TrustTag.RISK_BLACKLIST`
     * - [TrustRiskLevel.CAUTION_AMBER]: `localNote?.tag == TrustTag.CAUTION` OR `registrationDays < 30`
     *   OR (`rankLevel <= 1` and `historicalTradeTopicCount == 0`) OR `!searchSucceeded` OR `!profileSucceeded`
     * - [TrustRiskLevel.NORMAL_CLEAR]: none of the above triggered.
     */
    fun evaluate(
        rankLevel: Int,
        registrationDays: Int?,
        historicalTradeTopicCount: Int,
        exposureSearchHits: List<ExposureHitTopic>,
        searchSucceeded: Boolean,
        localNote: LocalSellerNote? = null,
        profileSucceeded: Boolean = true,
        registrationDurationLabel: String = "",
        exposureCheckScope: ExposureCheckScope = ExposureCheckScope.BOARD_SNAPSHOT,
        scannedTopicsCount: Int = 0,
        hasMoreTopicPages: Boolean = false,
        topicsSucceeded: Boolean = true,
    ): TrustRiskAssessment {
        val reasons = mutableListOf<String>()
        var isHighRisk = false
        var isCaution = false
        val scopeLabel = when {
            scannedTopicsCount > 0 && !hasMoreTopicPages -> "全部 ${scannedTopicsCount} 篇公开主题"
            scannedTopicsCount > 0 -> "近 ${scannedTopicsCount} 篇公开主题"
            else -> "公开主题"
        }

        // ── High-risk RED triggers ──
        if (exposureSearchHits.isNotEmpty()) {
            isHighRisk = true
            val distinctTerms = exposureSearchHits.map { it.matchedTerm }.distinct().joinToString("/")
            reasons += "站内搜索命中 ${exposureSearchHits.size} 条关联曝光帖（关键词：$distinctTerms），请务必先点开核实"
        }

        if (localNote?.tag == TrustTag.RISK_BLACKLIST) {
            isHighRisk = true
            val noteHint = localNote.noteText.trim().take(28)
            reasons += if (noteHint.isNotEmpty()) {
                "您已将其标记为「高危避雷」（备注：$noteHint）"
            } else {
                "您已将该卖家标记为「高危避雷」"
            }
        }

        // ── Caution AMBER triggers ──
        if (localNote?.tag == TrustTag.CAUTION) {
            isCaution = true
            reasons += "您已将该卖家标记为「需谨慎/走中介」"
        }

        if (registrationDays != null && registrationDays < NEW_ACCOUNT_THRESHOLD_DAYS) {
            isCaution = true
            reasons += if (registrationDays <= 0) {
                "账号为今日新注册（注册不足 30 天），建议谨慎交易"
            } else {
                "账号注册仅 ${registrationDays} 天（不足 30 天新号），建议提高警惕"
            }
        }

        val clampedRank = rankLevel.coerceIn(0, 6)
        if (profileSucceeded && topicsSucceeded && clampedRank <= LOW_RANK_THRESHOLD && historicalTradeTopicCount <= 0) {
            isCaution = true
            reasons += "会员等级较低（Lv.${clampedRank}）且${scopeLabel}暂无历史交易帖记录"
        }

        if (!searchSucceeded) {
            isCaution = true
            reasons += "站内曝光搜索未完整完成（可能未登录或触发限流），核验结果不完整"
        }

        if (!profileSucceeded) {
            isCaution = true
            reasons += "卖家档案接口加载失败，部分等级与注册信息暂缺"
        }

        if (!topicsSucceeded) {
            isCaution = true
            reasons += "卖家历史主题列表加载失败，历史交易帖统计暂缺"
        }

        val level = when {
            isHighRisk -> TrustRiskLevel.HIGH_RISK_RED
            isCaution -> TrustRiskLevel.CAUTION_AMBER
            else -> TrustRiskLevel.NORMAL_CLEAR
        }

        if (level == TrustRiskLevel.NORMAL_CLEAR) {
            if (localNote?.tag == TrustTag.TRUSTED) {
                reasons += "您已将该卖家标记为「靠谱卖家」"
            }
            if (registrationDays != null && registrationDays >= 180 && registrationDurationLabel.isNotBlank()) {
                reasons += "注册时长 ${registrationDurationLabel}，社区存续时间较稳定"
            }
            if (historicalTradeTopicCount > 0) {
                val countDisplay = if (hasMoreTopicPages) "${historicalTradeTopicCount}+" else historicalTradeTopicCount.toString()
                reasons += "${scopeLabel}中检出 $countDisplay 篇历史交易帖可供参考"
            }
            if (reasons.isEmpty()) {
                reasons += if (exposureCheckScope == ExposureCheckScope.DEEP_SEARCH) {
                    "已完成站内历史曝光深搜，未命中曝光/骗子关联帖"
                } else {
                    "近期「曝光」板块动态未见关联异常，大额交易可点下方「历史深搜」复核"
                }
            }
        }

        val headline = when (level) {
            TrustRiskLevel.HIGH_RISK_RED -> {
                if (exposureSearchHits.isNotEmpty()) {
                    "命中 ${exposureSearchHits.size} 条站内曝光/骗子关联帖，请高度警惕！"
                } else {
                    "该卖家在您的本地避雷名单中，请勿轻易交易！"
                }
            }
            TrustRiskLevel.CAUTION_AMBER -> {
                if (!searchSucceeded && exposureSearchHits.isEmpty() && reasons.size == 1) {
                    "曝光检索未完成，建议登录或刷新后再交易"
                } else {
                    "存在交易风险提示，建议谨慎核验并走官方中介"
                }
            }
            TrustRiskLevel.NORMAL_CLEAR -> {
                if (localNote?.tag == TrustTag.TRUSTED) {
                    "未命中曝光异常 · 已标记为靠谱卖家"
                } else if (exposureCheckScope == ExposureCheckScope.DEEP_SEARCH) {
                    "全站深搜未命中曝光记录，大额交易请走官方中介"
                } else {
                    "近期未见曝光异常，大额交易请走官方中介"
                }
            }
        }

        return TrustRiskAssessment(
            level = level,
            headline = headline,
            reasons = reasons,
            safetyTip = TrustRiskAssessment.DEFAULT_SAFETY_TIP,
        )
    }
}
