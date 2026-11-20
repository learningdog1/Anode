package com.nodeseek.app.ui.overseas

import androidx.compose.runtime.Immutable
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasDisplayMode
import com.nodeseek.app.domain.overseas.VpsGlossaryTranslator
import com.nodeseek.app.ui.components.formatRelativeTimeChinese
import java.util.Locale

@Immutable
data class OverseasPriceBadge(
    val amountText: String,
    val cycleLabel: String,
) {
    val displayText: String
        get() = if (cycleLabel.isNotBlank()) "$amountText$cycleLabel" else amountText
}

enum class OfferTagTone {
    PROMO,
    PERK,
    WARNING,
}

@Immutable
data class OverseasOfferTag(
    val label: String,
    val tone: OfferTagTone,
)

@Immutable
data class OverseasOfferCardUiModel(
    val offer: OverseasDealOffer,
    val id: String,
    val merchantLabel: String,
    val merchantInitial: String,
    val sourceBadge: String,
    val relativeTimeText: String,
    val headlineTitle: String,
    val secondaryTitle: String?,
    val isEnglishSourceTitle: Boolean,
    val priceBadge: OverseasPriceBadge?,
    val promoCode: String?,
    val promoTags: List<OverseasOfferTag>,
    val regionTags: List<String>,
    val specChips: List<String>,
    val cleanSummary: String?,
)

/**
 * Pure presentation parser that transforms raw [OverseasDealOffer] records into precomputed
 * [OverseasOfferCardUiModel] instances outside Compose composition so `LazyColumn` scrolling
 * incurs zero per-frame regex or allocation overhead.
 */
object OverseasOfferVisualParser {

    private val NOISY_MERCHANTS = setOf(
        "black friday",
        "cyber monday",
        "flash sale",
        "flash deal",
        "giveaway",
        "trending now",
        "11.11",
        "11.11 sale",
        "double 11",
        "special",
        "special offer",
        "special offers",
        "deals",
        "deal",
        "offers",
        "offer",
        "promo",
        "promotion",
        "vps",
        "hosting",
        "unknown",
    )

    private val BRACKET_PREFIX_REGEX = Regex("""^\s*\[([^\[\]]{2,32})\]\s*""")
    private val DASH_PREFIX_REGEX = Regex("""^\s*([A-Za-z0-9][A-Za-z0-9.\-_ ]{1,26}?)\s+[\-–—|:]\s+""")

    private val CURRENCY_SYMBOL_PRICE_REGEX = Regex(
        pattern = """([$€£¥])\s*(\d{1,4}(?:\.\d{1,2})?)\s*(?:/|per\s+)?(yr|year|annually|annual|mo|month|monthly|qtr|quarter|quarterly|semi-annually|biennial|triennial|m|年|月|季)?(?:\b|(?=[/\s,;)\]|]|$))""",
        option = RegexOption.IGNORE_CASE,
    )

    private val CURRENCY_CODE_PRICE_REGEX = Regex(
        pattern = """\b(\d{1,4}(?:\.\d{1,2})?)\s*(USD|EUR|GBP|CAD|AUD|CNY|RMB)\s*(?:/|per\s+)?(yr|year|annually|annual|mo|month|monthly|qtr|quarter|quarterly|年|月|季)?\b""",
        option = RegexOption.IGNORE_CASE,
    )

    private val NON_PLAN_PRICE_FOLLOWERS = Regex(
        pattern = """^\s*(?:credit|bonus|off\b|discount|cashback|rebate|save|voucher|礼品|代金券|优惠券|抵扣)""",
        option = RegexOption.IGNORE_CASE,
    )

    private val COUPON_PATTERNS = listOf(
        Regex("""(?i)(?:promo(?:tion)?|coupon|discount|voucher)\s*(?:code)?\s*[:：=]\s*["'`*]?([A-Z0-9][A-Z0-9_\-]{2,24})"""),
        Regex("""(?i)(?:优惠码|折扣码)\s*[:：]?\s*["'`*]?([A-Z0-9][A-Z0-9_\-]{2,24})"""),
        Regex("""(?i)\buse\s+code\s*[:：]?\s*["'`*]?([A-Z0-9][A-Z0-9_\-]{2,24})"""),
    )

    private val COUPON_STOPWORDS = setOf(
        "NONE", "AUTO", "FREE", "VPS", "KVM", "LXC", "NVME", "SSD", "HDD", "RAM", "CPU",
        "VCPU", "USD", "EUR", "GBP", "CAD", "AUD", "CNY", "RMB", "YEAR", "MONTH", "DEAL",
        "OFFER", "PROMO", "CODE", "BLACK", "FRIDAY", "SALE", "FLASH", "CPANEL", "HOSTING",
    )

    private val VCPU_REGEX = Regex("""(?i)(?:\b(\d{1,2})\s*(?:x\s*)?v?cpus?\b|\b(\d{1,2})\s*v?cores?\b|(\d{1,2})\s*核)""")
    private val RAM_REGEX = Regex("""(?i)\b(\d{1,4}(?:\.\d+)?)\s*(GB|MB|G|M)\s*(?:DDR[345]\s*)?(?:ECC\s*)?(?:RAM|Memory|内存)\b|(\d{1,4}(?:\.\d+)?)\s*(GB|MB|G|M)\s*内存""")
    private val COMPACT_SPEC_REGEX = Regex("""(?i)\b(\d{1,2})\s*[C核]\s*[/\-·\s]\s*(\d{1,3})\s*[GM](?:B)?\b""")
    private val STORAGE_REGEX = Regex("""(?i)\b(\d{1,4}(?:\.\d+)?)\s*(TB|GB|G)\s*(?:RAID[\-\s]?10\s*)?(NVMe|SSD|HDD|SAS|SATA)\b|(\d{1,4}(?:\.\d+)?)\s*(TB|GB|G)\s*(?:固态|硬盘|存储)""")
    private val BANDWIDTH_REGEX = Regex("""(?i)\b(\d{1,3}(?:\.\d+)?)\s*(TB|GB)\s*(?:@|Bandwidth|Traffic|Transfer|月流量|流量)""")
    private val PORT_SPEED_REGEX = Regex("""(?i)(?:@|\b)(\d{1,2}(?:\.\d+)?)\s*(Gbps|Gbit|Mbps)\b""")

    private val SPECIAL_SPEC_TAGS: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)\bCN2\s*GIA\b""") to "CN2 GIA",
        Regex("""(?i)\bCMIN2\b""") to "CMIN2",
        Regex("""(?i)\b(?:AS)?9929\b""") to "AS9929",
        Regex("""(?i)\bAMD\s+EPYC\b|\bEPYC\b""") to "EPYC",
        Regex("""(?i)\bAMD\s+Ryzen\b|\bRyzen\b""") to "Ryzen",
        Regex("""(?i)\bKVM\b""") to "KVM",
        Regex("""(?i)\bcPanel\b""") to "cPanel",
        Regex("""(?i)\bStorage\s+VPS\b|大盘鸡""") to "大盘鸡",
    )

    private val CITY_PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex("""\b(?i:Los\s+Angeles)\b|\bLAX\b|洛杉矶""") to "洛杉矶",
        Regex("""\b(?i:San\s+Jose)\b|\bSJC\b|圣何塞""") to "圣何塞",
        Regex("""\b(?i:Seattle)\b|\bSEA\b|西雅图""") to "西雅图",
        Regex("""\b(?i:Dallas)\b|\bDAL\b|达拉斯""") to "达拉斯",
        Regex("""\b(?i:New\s+York|New\s+Jersey)\b|\bNYC\b|纽约""") to "纽约",
        Regex("""\b(?i:Chicago)\b|芝加哥""") to "芝加哥",
        Regex("""\b(?i:Miami)\b|迈阿密""") to "迈阿密",
        Regex("""\b(?i:Ashburn)\b|阿什本""") to "阿什本",
        Regex("""\b(?i:Tokyo)\b|\b(?:TYO|NRT)\b|东京""") to "东京",
        Regex("""\b(?i:Osaka)\b|大阪""") to "大阪",
        Regex("""\b(?i:Hong\s+Kong)\b|\bHKG?\b|香港""") to "香港",
        Regex("""\b(?i:Singapore)\b|\bSGP?\b|新加坡""") to "新加坡",
        Regex("""\b(?i:Seoul)\b|首尔""") to "首尔",
        Regex("""\b(?i:Taipei)\b|台北""") to "台北",
        Regex("""\b(?i:Frankfurt)\b|\bFRA\b|法兰克福""") to "法兰克福",
        Regex("""\b(?i:Amsterdam)\b|\bAMS\b|阿姆斯特丹""") to "阿姆斯特丹",
        Regex("""\b(?i:London)\b|\bLON\b|伦敦""") to "伦敦",
        Regex("""\b(?i:Paris)\b|巴黎""") to "巴黎",
        Regex("""\b(?i:Falkenstein)\b|法尔肯施泰因""") to "法尔肯施泰因",
        Regex("""\b(?i:Nuremberg)\b|纽伦堡""") to "纽伦堡",
        Regex("""\b(?i:Helsinki)\b|赫尔辛基""") to "赫尔辛基",
        Regex("""\b(?i:Sydney)\b|悉尼""") to "悉尼",
        Regex("""\b(?i:Toronto)\b|多伦多""") to "多伦多",
        Regex("""\b(?i:Montreal)\b|蒙特利尔""") to "蒙特利尔",
    )

    private val BROAD_REGION_PATTERNS: List<Triple<Regex, String, Set<String>>> = listOf(
        Triple(Regex("""\b(?i:United\s+States)\b|\bUSA?\b|美国"""), "美国", setOf("洛杉矶", "圣何塞", "西雅图", "达拉斯", "纽约", "芝加哥", "迈阿密", "阿什本")),
        Triple(Regex("""北美"""), "北美", setOf("洛杉矶", "圣何塞", "西雅图", "达拉斯", "纽约", "芝加哥", "迈阿密", "阿什本", "美国", "加拿大", "多伦多", "蒙特利尔")),
        Triple(Regex("""\b(?i:Canada)\b|\bCA\b|加拿大"""), "加拿大", setOf("多伦多", "蒙特利尔")),
        Triple(Regex("""\b(?i:Germany)\b|\bDE\b|德国"""), "德国", setOf("法兰克福", "法尔肯施泰因", "纽伦堡")),
        Triple(Regex("""\b(?i:Netherlands)\b|\bNL\b|荷兰"""), "荷兰", setOf("阿姆斯特丹")),
        Triple(Regex("""\b(?i:Japan)\b|\bJP\b|日本"""), "日本", setOf("东京", "大阪")),
        Triple(Regex("""\b(?i:United\s+Kingdom)\b|\bUK\b|英国"""), "英国", setOf("伦敦")),
        Triple(Regex("""\b(?i:Europe)\b|\bEU\b|欧洲"""), "欧洲", setOf("法兰克福", "阿姆斯特丹", "伦敦", "巴黎", "法尔肯施泰因", "纽伦堡", "赫尔辛基", "德国", "荷兰", "英国")),
    )

    private val OOS_REGEX = Regex("""(?i)(\bout\s+of\s+stock\b|\bsold\s+out\b|已缺货|已售罄|已过期|无货)""")
    private val BLACK_FRIDAY_REGEX = Regex("""(?i)\bblack\s*friday\b|\bbf\s*(?:20\d\d|deal|sale)\b|黑五""")
    private val CYBER_MONDAY_REGEX = Regex("""(?i)\bcyber\s*monday\b|网一""")
    private val FLASH_SALE_REGEX = Regex("""(?i)\bflash\s*(?:sale|deal)s?\b|\blimited\s*(?:time|stock|quantity)\b|闪购|限时""")
    private val DOUBLE_11_REGEX = Regex("""(?i)\b(?:11\.11|double\s*11)\b|双\s*11|双十一""")
    private val RECURRING_REGEX = Regex("""(?i)\brecurring\b|续费同价""")
    private val UNMETERED_REGEX = Regex("""(?i)\b(?:unmetered|unlimited)\s*(?:bandwidth|traffic|transfer)?\b|不限流量""")
    private val DOUBLE_BW_REGEX = Regex("""(?i)\b(?:double|2x)\s*(?:bandwidth|traffic)\b|双倍流量""")
    private val FREE_DOMAIN_REGEX = Regex("""(?i)\bfree\s+domain\b|免费域名""")

    private val GENERIC_SUMMARY_REGEX = Regex(
        pattern = """^来自\s+.+\s+的\s+LowEndTalk\s+海外服务器与\s+VPS\s+促销情报""",
    )

    fun containsCjk(text: String): Boolean {
        for (i in text.indices) {
            val ch = text[i]
            if (ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF') {
                return true
            }
        }
        return false
    }

    fun parseCardModel(
        offer: OverseasDealOffer,
        displayMode: OverseasDisplayMode,
        translatedTitle: String? = null,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): OverseasOfferCardUiModel {
        val cleanedRawTitle = cleanDisplayTitle(offer.title)
        val cleanedOriginalTitle = cleanDisplayTitle(offer.originalTitle.ifBlank { offer.title })
        val titleHasCjk = containsCjk(cleanedRawTitle)

        val (headline, secondary) = resolveTitles(
            cleanedTitle = cleanedRawTitle,
            cleanedOriginal = cleanedOriginalTitle,
            titleHasCjk = titleHasCjk,
            displayMode = displayMode,
            translatedTitle = translatedTitle?.let(::cleanDisplayTitle),
        )

        val primarySearchBlob = listOf(offer.originalTitle, offer.title)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val fullSearchBlob = listOf(offer.originalTitle, offer.title, offer.summary)
            .filter { it.isNotBlank() }
            .joinToString("\n")

        val merchant = resolveMerchant(offer)
        val merchantInitial = merchant
            .firstOrNull { it.isLetterOrDigit() || containsCjk(it.toString()) }
            ?.uppercaseChar()
            ?.toString()
            ?: "L"

        val sourceBadge = if (offer.sourceLink.contains("lowendbox.com", ignoreCase = true)) {
            "LEB"
        } else {
            "LET"
        }

        val timeRaw = when {
            offer.publishedAtEpochMillis > 0L -> offer.publishedAtEpochMillis.toString()
            offer.publishedAtIso.isNotBlank() -> offer.publishedAtIso
            else -> ""
        }
        val relativeTimeText = if (timeRaw.isNotEmpty()) {
            formatRelativeTimeChinese(timeRaw, nowEpochMillis = nowEpochMillis)
        } else {
            ""
        }

        val priceBadge = extractPrimaryPrice(primarySearchBlob)
            ?: extractPrimaryPrice(offer.summary)

        val promoCode = extractPromoCode(fullSearchBlob)
        val promoTags = extractPromoTags(fullSearchBlob)
        val regionTags = extractRegions(fullSearchBlob)
        val specChips = extractSpecs(fullSearchBlob)
        val cleanSummary = resolveCleanSummary(offer.summary)

        return OverseasOfferCardUiModel(
            offer = offer,
            id = offer.id,
            merchantLabel = merchant,
            merchantInitial = merchantInitial,
            sourceBadge = sourceBadge,
            relativeTimeText = relativeTimeText,
            headlineTitle = headline,
            secondaryTitle = secondary,
            isEnglishSourceTitle = !titleHasCjk,
            priceBadge = priceBadge,
            promoCode = promoCode,
            promoTags = promoTags,
            regionTags = regionTags,
            specChips = specChips,
            cleanSummary = cleanSummary,
        )
    }

    private fun resolveTitles(
        cleanedTitle: String,
        cleanedOriginal: String,
        titleHasCjk: Boolean,
        displayMode: OverseasDisplayMode,
        translatedTitle: String?,
    ): Pair<String, String?> {
        if (titleHasCjk) {
            val secondary = if (
                displayMode == OverseasDisplayMode.BILINGUAL &&
                cleanedOriginal.isNotBlank() &&
                !cleanedOriginal.equals(cleanedTitle, ignoreCase = true)
            ) {
                cleanedOriginal
            } else {
                null
            }
            return cleanedTitle to secondary
        }

        val chineseCandidate = translatedTitle?.takeIf { it.isNotBlank() }
            ?: cleanDisplayTitle(VpsGlossaryTranslator.translateTitleOffline(cleanedTitle))

        return when (displayMode) {
            OverseasDisplayMode.ORIGINAL -> cleanedTitle to null
            OverseasDisplayMode.CHINESE -> chineseCandidate.ifBlank { cleanedTitle } to null
            OverseasDisplayMode.BILINGUAL -> {
                val head = chineseCandidate.ifBlank { cleanedTitle }
                val subSource = cleanedOriginal.ifBlank { cleanedTitle }
                val sub = if (!head.equals(subSource, ignoreCase = true)) subSource else null
                head to sub
            }
        }
    }

    internal fun cleanDisplayTitle(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(Regex("""\s*[–—\-]\s*·\s*"""), " · ")
            .replace(Regex("""(?:\s*·\s*){2,}"""), " · ")
            .replace(Regex("""\s*\|\s*\|+\s*"""), " | ")
            .replace(Regex("""\s*\|\s*$"""), "")
            .replace(Regex("""^\s*\|\s*"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }

    internal fun resolveMerchant(offer: OverseasDealOffer): String {
        val rawMerchant = offer.merchant.trim()
        if (rawMerchant.isNotBlank() && !isNoisyMerchant(rawMerchant)) {
            return rawMerchant
        }

        val candidates = listOf(offer.originalTitle, offer.title)
        for (title in candidates) {
            val bracket = BRACKET_PREFIX_REGEX.find(title)?.groupValues?.getOrNull(1)?.trim()
            if (!bracket.isNullOrBlank() && !isNoisyMerchant(bracket)) {
                return bracket
            }
            val dash = DASH_PREFIX_REGEX.find(title)?.groupValues?.getOrNull(1)?.trim()
            if (!dash.isNullOrBlank() && !isNoisyMerchant(dash)) {
                return dash
            }
        }
        return "LowEndTalk"
    }

    private fun isNoisyMerchant(value: String): Boolean {
        val normalized = value.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return true
        if (normalized in NOISY_MERCHANTS) return true
        return normalized.startsWith("black friday") ||
            normalized.startsWith("flash sale") ||
            normalized.startsWith("giveaway")
    }

    internal fun extractPrimaryPrice(text: String): OverseasPriceBadge? {
        if (text.isBlank()) return null

        CURRENCY_SYMBOL_PRICE_REGEX.findAll(text).forEach { match ->
            val symbol = match.groupValues[1]
            val amountStr = match.groupValues[2]
            val amount = amountStr.toDoubleOrNull() ?: return@forEach
            if (amount <= 0.0 || amount >= 5000.0) return@forEach

            val tail = text.substring(match.range.last + 1)
            if (NON_PLAN_PRICE_FOLLOWERS.containsMatchIn(tail)) return@forEach

            val cycle = normalizeCycle(match.groupValues.getOrNull(3).orEmpty())
            return OverseasPriceBadge(
                amountText = "$symbol$amountStr",
                cycleLabel = cycle,
            )
        }

        CURRENCY_CODE_PRICE_REGEX.findAll(text).forEach { match ->
            val amountStr = match.groupValues[1]
            val amount = amountStr.toDoubleOrNull() ?: return@forEach
            if (amount <= 0.0 || amount >= 5000.0) return@forEach

            val tail = text.substring(match.range.last + 1)
            if (NON_PLAN_PRICE_FOLLOWERS.containsMatchIn(tail)) return@forEach

            val symbol = when (match.groupValues[2].uppercase(Locale.ROOT)) {
                "EUR" -> "€"
                "GBP" -> "£"
                "CNY", "RMB" -> "¥"
                else -> "$"
            }
            val cycle = normalizeCycle(match.groupValues.getOrNull(3).orEmpty())
            return OverseasPriceBadge(
                amountText = "$symbol$amountStr",
                cycleLabel = cycle,
            )
        }

        return null
    }

    private fun normalizeCycle(raw: String): String {
        return when (raw.trim().lowercase(Locale.ROOT)) {
            "yr", "year", "annual", "annually", "年" -> "/年"
            "mo", "month", "monthly", "m", "月" -> "/月"
            "qtr", "quarter", "quarterly", "季" -> "/季"
            "semi-annually" -> "/半年"
            "biennial" -> "/2年"
            "triennial" -> "/3年"
            else -> ""
        }
    }

    private fun extractPromoCode(text: String): String? {
        if (text.isBlank()) return null
        for (regex in COUPON_PATTERNS) {
            regex.findAll(text).forEach { match ->
                val code = match.groupValues.getOrNull(1)?.trim()?.uppercase(Locale.ROOT).orEmpty()
                if (code.length in 3..24 && code !in COUPON_STOPWORDS && code.any { it.isLetter() }) {
                    return code
                }
            }
        }
        return null
    }

    private fun extractPromoTags(text: String): List<OverseasOfferTag> {
        if (text.isBlank()) return emptyList()
        val tags = ArrayList<OverseasOfferTag>(3)

        if (OOS_REGEX.containsMatchIn(text)) {
            tags += OverseasOfferTag("疑似缺货", OfferTagTone.WARNING)
        }

        when {
            BLACK_FRIDAY_REGEX.containsMatchIn(text) ->
                tags += OverseasOfferTag("黑五特惠", OfferTagTone.PROMO)
            CYBER_MONDAY_REGEX.containsMatchIn(text) ->
                tags += OverseasOfferTag("网一大促", OfferTagTone.PROMO)
            DOUBLE_11_REGEX.containsMatchIn(text) ->
                tags += OverseasOfferTag("双11", OfferTagTone.PROMO)
            FLASH_SALE_REGEX.containsMatchIn(text) ->
                tags += OverseasOfferTag("限时闪购", OfferTagTone.PROMO)
        }

        if (RECURRING_REGEX.containsMatchIn(text)) {
            tags += OverseasOfferTag("续费同价", OfferTagTone.PERK)
        }
        if (tags.size < 3 && UNMETERED_REGEX.containsMatchIn(text)) {
            tags += OverseasOfferTag("不限流量", OfferTagTone.PERK)
        } else if (tags.size < 3 && DOUBLE_BW_REGEX.containsMatchIn(text)) {
            tags += OverseasOfferTag("双倍流量", OfferTagTone.PERK)
        }
        if (tags.size < 3 && FREE_DOMAIN_REGEX.containsMatchIn(text)) {
            tags += OverseasOfferTag("赠域名", OfferTagTone.PERK)
        }

        return tags.take(3)
    }

    internal fun extractRegions(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val found = LinkedHashSet<String>()

        CITY_PATTERNS.forEach { (regex, label) ->
            if (regex.containsMatchIn(text)) {
                found += label
            }
        }

        BROAD_REGION_PATTERNS.forEach { (regex, label, coveredBy) ->
            if (regex.containsMatchIn(text) && found.none { it in coveredBy }) {
                found += label
            }
        }

        return found.take(4)
    }

    internal fun extractSpecs(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val specs = LinkedHashSet<String>()

        val vcpuMatch = VCPU_REGEX.find(text)
        val vcpuVal = vcpuMatch?.let { m ->
            m.groupValues[1].ifBlank { m.groupValues[2] }.ifBlank { m.groupValues[3] }
        }
        if (!vcpuVal.isNullOrBlank()) {
            specs += "$vcpuVal vCPU"
        }

        val ramMatch = RAM_REGEX.find(text)
        if (ramMatch != null) {
            val num = ramMatch.groupValues[1].ifBlank { ramMatch.groupValues[3] }
            val unitRaw = ramMatch.groupValues[2].ifBlank { ramMatch.groupValues[4] }
            val unit = when (unitRaw.uppercase(Locale.ROOT)) {
                "G" -> "GB"
                "M" -> "MB"
                else -> unitRaw.uppercase(Locale.ROOT)
            }
            if (num.isNotBlank()) {
                specs += "$num$unit RAM"
            }
        }

        if (vcpuVal.isNullOrBlank() || ramMatch == null) {
            COMPACT_SPEC_REGEX.find(text)?.let { compact ->
                if (vcpuVal.isNullOrBlank()) specs += "${compact.groupValues[1]} vCPU"
                if (ramMatch == null) specs += "${compact.groupValues[2]}GB RAM"
            }
        }

        STORAGE_REGEX.find(text)?.let { storage ->
            val num = storage.groupValues[1].ifBlank { storage.groupValues[4] }
            val unitRaw = storage.groupValues[2].ifBlank { storage.groupValues[5] }
            val unit = if (unitRaw.equals("G", ignoreCase = true)) "GB" else unitRaw.uppercase(Locale.ROOT)
            val diskKind = storage.groupValues[3].ifBlank {
                if (text.contains("NVMe", ignoreCase = true)) "NVMe"
                else if (text.contains("SSD", ignoreCase = true) || text.contains("固态")) "SSD"
                else "硬盘"
            }.let { kind ->
                when (kind.uppercase(Locale.ROOT)) {
                    "NVME" -> "NVMe"
                    else -> kind.uppercase(Locale.ROOT)
                }
            }
            if (num.isNotBlank()) {
                specs += "$num$unit $diskKind"
            }
        }

        BANDWIDTH_REGEX.find(text)?.let { bw ->
            val valStr = bw.groupValues[1]
            val unit = bw.groupValues[2].uppercase(Locale.ROOT)
            if (valStr.isNotBlank()) {
                specs += "$valStr$unit 流量"
            }
        }

        PORT_SPEED_REGEX.find(text)?.let { port ->
            val speed = port.groupValues[1]
            val unit = port.groupValues[2]
            val normalizedUnit = when {
                unit.equals("gbps", ignoreCase = true) || unit.equals("gbit", ignoreCase = true) -> "Gbps"
                else -> "Mbps"
            }
            if (speed.isNotBlank()) {
                specs += "$speed$normalizedUnit"
            }
        }

        SPECIAL_SPEC_TAGS.forEach { (regex, label) ->
            if (specs.size < 5 && regex.containsMatchIn(text)) {
                specs += label
            }
        }

        if (specs.size < 5 &&
            specs.none { it.contains("NVMe", ignoreCase = true) } &&
            Regex("""(?i)\bNVMe\b""").containsMatchIn(text)
        ) {
            specs += "NVMe"
        }

        return specs.take(5)
    }

    private fun resolveCleanSummary(rawSummary: String): String? {
        val trimmed = rawSummary.trim()
        if (trimmed.isEmpty()) return null
        if (GENERIC_SUMMARY_REGEX.containsMatchIn(trimmed)) return null
        return trimmed
    }
}
