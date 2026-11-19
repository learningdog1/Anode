package com.nodeseek.app.data.overseas

import org.jsoup.Jsoup
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Shared HTML/text extraction utilities for LowEndTalk and LowEndBox deals:
 * - Readable summary extraction preserving spec tables & lists
 * - Direct merchant cart / order link extraction (WHMCS, HostBill, Blesta, billing portals)
 * - Promo / coupon code detection
 * - VPS hardware, bandwidth, datacenter city, and route highlight chips
 * - Black Friday / Flash sale & Out-of-Stock heuristics
 * - Multi-format timestamp parsing
 */
object OverseasOfferExtractor {

    private const val LINE_BREAK_SENTINEL = "\u0007"
    private const val CELL_SEP_SENTINEL = " \u0008 "

    private val SPACE_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val BLOCKED_ORDER_HOST_SUFFIXES = setOf(
        "lowendtalk.com",
        "lowendbox.com",
        "lowendspirit.com",
        "twitter.com",
        "x.com",
        "facebook.com",
        "instagram.com",
        "linkedin.com",
        "youtube.com",
        "youtu.be",
        "imgur.com",
        "gravatar.com",
        "wp.com",
        "wordpress.org",
        "trustpilot.com",
        "speedtest.net",
        "github.com",
        "githubusercontent.com",
        "t.me",
        "telegram.me",
        "discord.gg",
        "discord.com",
        "reddit.com",
        "cloudflare.com",
        "w3.org",
    )

    private val IGNORED_MEDIA_EXTENSIONS = setOf(
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".bmp", ".mp4", ".webm", ".css", ".js",
    )

    private val STRONG_ORDER_URL_REGEX = Regex(
        pattern = """(?i)(cart\.php|aff\.php|order\.php|clientarea\.php|index\.php\?/cart|cmd=cart|[?&](?:a=add|pid=|promocode=|coupon=|gid=)|/(?:order|cart|checkout|billing|plans?|specials?|black-?friday|deals?|store|vps)(?:/|\?|$))""",
    )

    private val ORDER_SUBDOMAIN_REGEX = Regex(
        pattern = """(?i)^(?:billing|my|client|clients|portal|cp|panel|order|store|shop|whmcs|manage|cloud|app|secure)\.""",
    )

    private val ORDER_ANCHOR_TEXT_REGEX = Regex(
        pattern = """(?i)\b(order(?:\s+now|\s+here)?|buy(?:\s+now)?|purchase|get\s+(?:deal|offer|started)|deploy|configure|checkout|direct\s+link|click\s+here\s+to\s+order|grab\s+(?:it|now)|sign\s*up|claim|shop\s+now)\b""",
    )

    private val RAW_URL_REGEX = Regex("""https?://[^\s<>"')\]}]+""")

    private val COUPON_PATTERNS: List<Regex> = listOf(
        Regex("""(?i)\b(?:coupon|promo(?:tion)?|discount|voucher)(?:\s+code)?\s*(?:is\s+)?[:：=\-]\s*["'`*]*([A-Z0-9][A-Z0-9_\-]{2,29})["'`*]*"""),
        Regex("""(?i)\buse\s+(?:promo\s+|coupon\s+|discount\s+)?code\s*[:：]?\s*["'`*]*([A-Z0-9][A-Z0-9_\-]{2,29})["'`*]*"""),
        Regex("""(?i)\bcode\s*[:：]\s*["'`*]*([A-Z0-9][A-Z0-9_\-]{2,29})["'`*]*"""),
        Regex("""(?i)[?&](?:promocode|coupon)=([A-Z0-9][A-Z0-9_\-]{2,29})"""),
    )

    private val COUPON_STOPWORDS = setOf(
        "NONE", "AUTO", "AUTOMATIC", "AUTOMATICALLY", "APPLIED", "NO", "NOT", "N/A", "NA",
        "OPTIONAL", "REQUIRED", "INCLUDED", "DIRECT", "ORDER", "HERE", "LINK", "CLICK",
        "BELOW", "ABOVE", "YES", "FREE", "VALID", "EXPIRED", "LIMITED", "STOCK", "ONLY",
        "USD", "EUR", "GBP", "CAD", "AUD", "SGD", "HKD", "JPY", "CNY", "RMB",
        "VPS", "KVM", "LXC", "NVME", "SSD", "HDD", "RAM", "CPU", "VCPU", "IPV4", "IPV6",
        "HTTP", "HTTPS", "WWW", "COM", "NET", "ORG", "PHP", "HTML", "YEAR", "MONTH",
        "MONTHLY", "YEARLY", "ANNUAL", "ANNUALLY", "RECURRING", "LIFETIME", "DEAL", "OFFER",
    )

    // Hardware & spec extractors
    private val VCPU_REGEX = Regex("""(?i)\b(\d{1,2})\s*(?:x\s*)?(?:v?cpus?|v?cores?|cores?)\b""")
    private val RAM_REGEX = Regex("""(?i)\b(\d{1,4}(?:\.\d+)?)\s*(GB|MB|G|M)\s*(?:DDR[345]\s*)?(?:ECC\s*)?(?:RAM|Memory)\b""")
    private val COMPACT_SPEC_REGEX = Regex("""(?i)\b(\d{1,2})C\s*[/\-·]?\s*(\d{1,3})G\b""")
    private val STORAGE_REGEX = Regex("""(?i)\b(\d{1,4}(?:\.\d+)?)\s*(TB|GB|G)\s*(?:RAID[\-\s]?10\s*)?(NVMe(?:\s+SSD)?|SSD|HDD|SAS|SATA|Storage|Disk)\b""")
    private val BANDWIDTH_REGEX = Regex("""(?i)\b(\d{1,3}(?:\.\d+)?\s*(?:TB|GB))\s*(?:Bandwidth|Traffic|Transfer|Monthly\s+Traffic)\b""")
    private val UNMETERED_REGEX = Regex("""(?i)\b(?:unmetered|unlimited)\s*(?:bandwidth|traffic|transfer)?\b""")
    private val PORT_SPEED_REGEX = Regex("""(?i)\b(\d{1,2}(?:\.\d+)?\s*(?:Gbps|Gbit|Mbps|Mbit))\b""")

    private val FLASH_BLACK_FRIDAY_REGEX = Regex(
        pattern = """(?i)\b(black\s*friday|bf\s*20\d\d|bf\s+deal|bf\s+sale|cyber\s*monday|cyber\s*week|flash\s*sale|flash\s*deal|limited\s+stock|limited\s+quantity|double\s+11|11\.11|singles\s+day|halloween|thanksgiving|holiday\s+sale|let\s+special|leb\s+exclusive)\b""",
    )

    private val OUT_OF_STOCK_REGEX = Regex(
        pattern = """(?i)(\bout\s+of\s+stock\b|\bsold\s+out\b|\[(?:expired|oos|sold\s*out)\]|\ball\s+gone\b|\bno\s+more\s+stock\b)""",
    )

    private val CPU_TAGS: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)\bRyzen(?:\s+\d+)?\b""") to "Ryzen",
        Regex("""(?i)\bEPYC\b""") to "EPYC",
        Regex("""(?i)\bXeon\b""") to "Xeon",
    )

    private val ROUTE_TAGS: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)\bCN2\s*GIA\b""") to "CN2 GIA",
        Regex("""(?i)\bCMIN2\b""") to "CMIN2",
        Regex("""(?i)\b(?:AS)?9929\b""") to "AS9929",
        Regex("""(?i)\bIIJ\b""") to "IIJ",
        Regex("""(?i)\bSoftBank\b""") to "SoftBank",
        Regex("""(?i)\bBGP(?:\s+session)?\b""") to "BGP",
    )

    private val LOCATION_TAGS: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)\b(?:Los\s+Angeles|LAX)\b""") to "洛杉矶",
        Regex("""(?i)\b(?:San\s+Jose|SJC)\b""") to "圣何塞",
        Regex("""(?i)\b(?:Seattle|SEA)\b""") to "西雅图",
        Regex("""(?i)\b(?:Dallas|DAL)\b""") to "达拉斯",
        Regex("""(?i)\b(?:New\s+York|New\s+Jersey|NYC)\b""") to "纽约",
        Regex("""(?i)\bMiami\b""") to "迈阿密",
        Regex("""(?i)\bChicago\b""") to "芝加哥",
        Regex("""(?i)\bAshburn\b""") to "阿什本",
        Regex("""(?i)\bTokyo\b""") to "东京",
        Regex("""(?i)\bOsaka\b""") to "大阪",
        Regex("""(?i)\bHong\s+Kong\b""") to "香港",
        Regex("""(?i)\bSingapore\b""") to "新加坡",
        Regex("""(?i)\b(?:Frankfurt|FRA)\b""") to "法兰克福",
        Regex("""(?i)\b(?:Amsterdam|AMS)\b""") to "阿姆斯特丹",
        Regex("""(?i)\bLondon\b""") to "伦敦",
        Regex("""(?i)\bZurich\b""") to "苏黎世",
        Regex("""(?i)\bVienna\b""") to "维也纳",
        Regex("""(?i)\bParis\b""") to "巴黎",
        Regex("""(?i)\bStockholm\b""") to "斯德哥尔摩",
        Regex("""(?i)\bWarsaw\b""") to "华沙",
        Regex("""(?i)\bSydney\b""") to "悉尼",
        Regex("""(?i)\bToronto\b""") to "多伦多",
        Regex("""(?i)\bMontreal\b""") to "蒙特利尔",
        Regex("""(?i)\bSeoul\b""") to "首尔",
        Regex("""(?i)\bTaipei\b""") to "台北",
        Regex("""(?i)\bGermany\b""") to "德国",
        Regex("""(?i)\bNetherlands\b""") to "荷兰",
        Regex("""(?i)\bJapan\b""") to "日本",
    )

    /**
     * Converts HTML content (including LEB/LET spec tables and `<ul>/<li>` offer lists)
     * into clean, line-separated readable plain text.
     */
    fun htmlToReadableText(rawHtml: String): String {
        if (rawHtml.isBlank()) return ""
        val doc = Jsoup.parseBodyFragment(rawHtml)
        doc.select("script, style, noscript, iframe").remove()

        doc.select("td, th").forEach { cell ->
            cell.appendText(CELL_SEP_SENTINEL)
        }
        doc.select("br").forEach { br ->
            br.after(LINE_BREAK_SENTINEL)
        }
        doc.select("p, tr, li, div, h1, h2, h3, h4, h5, blockquote, pre").forEach { block ->
            block.prependText(LINE_BREAK_SENTINEL)
            block.appendText(LINE_BREAK_SENTINEL)
        }

        val rawText = doc.body().text()
        return rawText
            .split(LINE_BREAK_SENTINEL)
            .map { line ->
                line.replace(CELL_SEP_SENTINEL.trim(), " | ")
                    .replace(Regex("""\s*\|\s*(?:\|\s*)+"""), " | ")
                    .replace(Regex("""^\|\s*|\s*\|$"""), "")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
            }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /**
     * Builds a concise plain-text summary (preserving offer tables & key spec lines).
     */
    fun buildSummary(readableText: String, maxChars: Int = 520): String {
        if (readableText.isBlank()) return ""
        val normalizedLines = readableText
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (normalizedLines.isEmpty()) return ""

        val joined = normalizedLines.joinToString("\n")
        if (joined.length <= maxChars) return joined
        return joined.take(maxChars - 1).trimEnd() + "…"
    }

    /**
     * Extracts merchant cart/order URLs from HTML anchors and plain-text body,
     * ranking WHMCS / HostBill / checkout links first and filtering out community/social noise.
     */
    fun extractOrderLinks(
        rawHtml: String,
        plainText: String = "",
        baseUrl: String = "",
        maxLinks: Int = 6,
    ): List<String> {
        val strongLinks = LinkedHashSet<String>()
        val secondaryLinks = LinkedHashSet<String>()

        if (rawHtml.isNotBlank()) {
            val doc = Jsoup.parseBodyFragment(rawHtml, baseUrl)
            doc.select("a[href]").forEach { anchor ->
                val rawHref = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
                val normalized = sanitizeUrl(rawHref) ?: return@forEach
                if (!isEligibleMerchantUrl(normalized)) return@forEach

                val anchorText = anchor.text().trim()
                val isStrong = isStrongOrderUrl(normalized) || ORDER_ANCHOR_TEXT_REGEX.containsMatchIn(anchorText)
                if (isStrong) {
                    strongLinks += normalized
                } else {
                    secondaryLinks += normalized
                }
            }
        }

        if (plainText.isNotBlank()) {
            RAW_URL_REGEX.findAll(plainText).forEach { match ->
                val normalized = sanitizeUrl(match.value) ?: return@forEach
                if (!isEligibleMerchantUrl(normalized)) return@forEach
                if (isStrongOrderUrl(normalized)) {
                    strongLinks += normalized
                } else {
                    secondaryLinks += normalized
                }
            }
        }

        val combined = LinkedHashSet<String>()
        combined.addAll(strongLinks)
        if (combined.size < maxLinks) {
            combined.addAll(secondaryLinks)
        }
        return combined.take(maxLinks)
    }

    /**
     * Extracts promo / coupon codes from title, cleaned body, and order URLs.
     */
    fun extractCouponCodes(
        title: String,
        bodyText: String,
        orderLinks: List<String> = emptyList(),
        maxCodes: Int = 4,
    ): List<String> {
        val found = LinkedHashSet<String>()
        val searchBlob = buildString {
            append(title)
            append('\n')
            append(bodyText)
            if (orderLinks.isNotEmpty()) {
                append('\n')
                append(orderLinks.joinToString("\n"))
            }
        }

        COUPON_PATTERNS.forEach { regex ->
            regex.findAll(searchBlob).forEach { match ->
                val candidate = match.groupValues.getOrNull(1)?.trim()?.uppercase(Locale.ROOT).orEmpty()
                if (isValidCouponCandidate(candidate)) {
                    found += candidate
                }
            }
        }
        return found.take(maxCodes)
    }

    /**
     * Extracts concise spec & location chips (e.g. `2 vCPU`, `4GB RAM`, `40GB NVMe`, `洛杉矶`, `1Gbps`).
     */
    fun extractSpecHighlights(
        title: String,
        bodyText: String,
        maxHighlights: Int = 8,
    ): List<String> {
        val combined = "$title\n$bodyText"
        val chips = LinkedHashSet<String>()

        VCPU_REGEX.find(combined)?.let { match ->
            val count = match.groupValues[1]
            chips += "$count vCPU"
        }

        val ramMatch = RAM_REGEX.find(combined)
        if (ramMatch != null) {
            val qty = ramMatch.groupValues[1]
            val unit = ramMatch.groupValues[2].uppercase(Locale.ROOT).let {
                when (it) {
                    "G" -> "GB"
                    "M" -> "MB"
                    else -> it
                }
            }
            chips += "${qty}${unit} RAM"
        } else {
            COMPACT_SPEC_REGEX.find(combined)?.let { compact ->
                if (chips.none { it.contains("vCPU") }) {
                    chips += "${compact.groupValues[1]} vCPU"
                }
                chips += "${compact.groupValues[2]}GB RAM"
            }
        }

        STORAGE_REGEX.find(combined)?.let { match ->
            val qty = match.groupValues[1]
            val unit = match.groupValues[2].uppercase(Locale.ROOT).let { if (it == "G") "GB" else it }
            val kind = match.groupValues[3].trim().let { raw ->
                when {
                    raw.contains("nvme", ignoreCase = true) -> "NVMe"
                    raw.contains("ssd", ignoreCase = true) -> "SSD"
                    raw.contains("hdd", ignoreCase = true) -> "HDD"
                    else -> "硬盘"
                }
            }
            chips += "${qty}${unit} $kind"
        }

        if (UNMETERED_REGEX.containsMatchIn(combined)) {
            chips += "不限流量"
        } else {
            BANDWIDTH_REGEX.find(combined)?.let { match ->
                val qty = match.groupValues[1].replace(Regex("""\s+"""), "").uppercase(Locale.ROOT)
                chips += "$qty 流量"
            }
        }

        PORT_SPEED_REGEX.find(combined)?.let { match ->
            val speed = match.groupValues[1].replace(Regex("""\s+"""), "")
            chips += speed
        }

        CPU_TAGS.forEach { (regex, label) ->
            if (regex.containsMatchIn(combined)) chips += label
        }

        ROUTE_TAGS.forEach { (regex, label) ->
            if (regex.containsMatchIn(combined)) chips += label
        }

        var locationCount = 0
        LOCATION_TAGS.forEach { (regex, label) ->
            if (locationCount < 2 && regex.containsMatchIn(combined)) {
                chips += label
                locationCount++
            }
        }

        return chips.take(maxHighlights)
    }

    fun isFlashOrBlackFriday(title: String, bodyText: String): Boolean =
        FLASH_BLACK_FRIDAY_REGEX.containsMatchIn(title) ||
            FLASH_BLACK_FRIDAY_REGEX.containsMatchIn(bodyText.take(600))

    fun isOutOfStockHint(title: String, bodyText: String): Boolean =
        OUT_OF_STOCK_REGEX.containsMatchIn(title) ||
            OUT_OF_STOCK_REGEX.containsMatchIn(bodyText.take(300))

    /**
     * Parses RFC-1123 RSS dates, ISO-8601 dates, Vanilla `yyyy-MM-dd HH:mm:ss`, or epoch seconds/millis.
     */
    fun parseTimestampMillis(raw: String?): Long {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return 0L

        runCatching { return Instant.parse(value).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        runCatching {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        }
        runCatching {
            return LocalDateTime.parse(value, SPACE_DATE_TIME_FORMATTER)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        }
        value.toLongOrNull()?.let { numeric ->
            return if (numeric < 10_000_000_000L) numeric * 1000L else numeric
        }
        return 0L
    }

    internal fun sanitizeUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
            .trimEnd('.', ',', ';', ')', ']', '}', '!', '?')
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return runCatching {
            val uri = URI(trimmed)
            val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
            if (host == "localhost" || host == "127.0.0.1") return null
            trimmed
        }.getOrNull()
    }

    private fun isEligibleMerchantUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        val path = uri.path?.lowercase(Locale.ROOT).orEmpty()

        if (BLOCKED_ORDER_HOST_SUFFIXES.any { blocked -> host == blocked || host.endsWith(".$blocked") }) {
            return false
        }
        if (IGNORED_MEDIA_EXTENSIONS.any { ext -> path.endsWith(ext) }) {
            return false
        }
        return true
    }

    private fun isStrongOrderUrl(url: String): Boolean {
        if (STRONG_ORDER_URL_REGEX.containsMatchIn(url)) return true
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        return ORDER_SUBDOMAIN_REGEX.containsMatchIn(host)
    }

    private fun isValidCouponCandidate(candidate: String): Boolean {
        if (candidate.length !in 3..30) return false
        if (candidate in COUPON_STOPWORDS) return false
        if (candidate.all { it.isDigit() } && candidate.length < 4) return false
        // Require at least one letter so bare numbers/years like "2026" alone aren't falsely flagged unless mixed
        if (candidate.none { it in 'A'..'Z' }) return false
        return true
    }
}
