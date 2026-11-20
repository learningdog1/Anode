package com.nodeseek.app.domain.overseas

/**
 * Classification reason returned when [OverseasOfferQualityFilter] evaluates an offer.
 */
enum class OfferFilterReason {
    NONE,
    INSUFFICIENT_TITLE,
    BLOCKED_MERCHANT,
    BLOCKED_KEYWORD,
    NON_SERVER_AD,
    GENERIC_BUZZ_SPAM,
    INSUFFICIENT_SIGNAL,
}

/**
 * Result of evaluating a single [OverseasDealOffer] against [OverseasOfferFilterConfig].
 */
data class OfferFilterVerdict(
    val accepted: Boolean,
    val reason: OfferFilterReason = OfferFilterReason.NONE,
)

/**
 * Configurable domain-layer defense-in-depth filter for overseas VPS promotion feeds.
 *
 * Designed with high precision so legitimate VPS/dedicated/colocation promotions that omit prices
 * in the title (e.g. RackNerd Black Friday, CubePath Hetzner comparison, HostHatch anniversary)
 * are preserved while filtering generic marketing spam (e.g. "99.9% Uptime High powered Cloud..."),
 * unrelated software/gateway promos, and user help/WTB chatter.
 */
object OverseasOfferQualityFilter {

    private val GENERIC_SUMMARY_REGEX = Regex(
        """^(?:来自\s+.+?\s+的\s+)?LowEndTalk\s+海外服务器与\s*VPS\s*促销情报""",
    )

    private val BUZZ_SPAM_PHRASE_REGEX = Regex(
        """(?:99\.9\d*%\s*uptime|high[\s\-]+powered\s+(?:cloud|dedicated|vps|servers?)|\d+\+\s*locations?\s+world\s*wide|\b3\s*day\s+trial\b|instant\s+activation\s+24/7)""",
        RegexOption.IGNORE_CASE,
    )

    private val NON_SERVER_AD_REGEX = Regex(
        """(?:crypto\s+payment\s+gateway|conversion\s+to\s+usdt|nobody\s+needs\s+another\s+ssh\s+client|web\s+hosting\s+control\s+panel|\bcontrol\s+panel\b.*\bfree\s+up\s+to\b|unlock\s+netflix.*via\s+dns|\bjoin\s+the\s+reseller\s+program\b|^\s*(?:\[?(?:wtb|help|question|req|request)\]?|looking\s+for\s+(?:a\s+)?(?:cheap\s+|reliable\s+)?vps\b|need\s+help\b))""",
        RegexOption.IGNORE_CASE,
    )

    private val CONCRETE_PRICE_OR_DISCOUNT_REGEX = Regex(
        """(?:\$\s*\d|€\s*\d|£\s*\d|¥\s*\d|\d+(?:[.,]\d+)?\s*(?:€|\$|£|usd|eur|gbp|rmb|元)|/\s*(?:mo|yr|year|month|hour|hr)\b|\b\d+%\s*off\b|\bsave\s+\d+%|\b\d+%\s*(?:recurring|discount))""",
        RegexOption.IGNORE_CASE,
    )

    private val CONCRETE_SPEC_REGEX = Regex(
        """(?:\b\d+\s*(?:v?cpu|cores?|gb\s*(?:ram|ddr\d?|ssd|nvme|hdd)|tb\s*(?:ssd|hdd|nvme|bw|bandwidth|traffic)|gbps|mbps)\b|\bryzen\s*\d|\bepyc\s*\d|\bxeon\s*(?:e\d|gold|silver)|\bv100\b|\bmi250\b)""",
        RegexOption.IGNORE_CASE,
    )

    private val HOSTING_OR_PROMO_IDENTITY_REGEX = Regex(
        """(?:\bvps\b|\bkvm\b|\blxc\b|\bdedicated(?:\s+server)?\b|\bbare\s+metal\b|\bcolocation\b|\bcolos?\b|\bhosting\b|\bcloud\b|\bserver|\bipv[46]\b|\bepyc\b|\bryzen\b|\bxeon\b|\bi[3579]\b|\bgpu\b|\bnvme\b|\bssd\b|\bstorage\b|\bunmetered\b|\bdeals?\b|\bspecial\s*offers?\b|\boffers?\b|\bspecial\b|\bpromo\b|\bsales?\b|\bflash\s*sales?\b|\bblack\s*friday\b|\bcyber\s*monday\b|\bgiveaways?\b|\brestock\b|\b独服\b|\b云主机\b|促销|特惠|大促|闪购|补货|优惠)""",
        RegexOption.IGNORE_CASE,
    )

    private val MEANINGFUL_CHAR_REGEX = Regex("""[\p{L}\p{N}]""")

    fun evaluate(
        offer: OverseasDealOffer,
        config: OverseasOfferFilterConfig = OverseasOfferFilterConfig(),
    ): OfferFilterVerdict {
        if (!config.enabled) {
            return OfferFilterVerdict(accepted = true)
        }

        val title = offer.title.trim()
        val orig = offer.originalTitle.trim()
        val summary = offer.summary.trim()
        val merchant = offer.merchant.trim()
        val combinedHeadline = "$title $orig".trim()

        if (combinedHeadline.isBlank() || MEANINGFUL_CHAR_REGEX.findAll(combinedHeadline).count() < 4) {
            return OfferFilterVerdict(accepted = false, reason = OfferFilterReason.INSUFFICIENT_TITLE)
        }

        if (config.blockedMerchants.isNotEmpty() && merchant.isNotEmpty()) {
            val blocked = config.blockedMerchants.any { blockedName ->
                val target = blockedName.trim()
                target.isNotEmpty() && merchant.equals(target, ignoreCase = true)
            }
            if (blocked) {
                return OfferFilterVerdict(accepted = false, reason = OfferFilterReason.BLOCKED_MERCHANT)
            }
        }

        if (config.extraBlockedKeywords.isNotEmpty()) {
            val haystack = "$combinedHeadline $summary $merchant"
            val hitKeyword = config.extraBlockedKeywords.any { kw ->
                val needle = kw.trim()
                needle.isNotEmpty() && haystack.contains(needle, ignoreCase = true)
            }
            if (hitKeyword) {
                return OfferFilterVerdict(accepted = false, reason = OfferFilterReason.BLOCKED_KEYWORD)
            }
        }

        val hasRichAiSummary = summary.isNotEmpty() && !GENERIC_SUMMARY_REGEX.containsMatchIn(summary)
        val hasPriceOrDiscount = CONCRETE_PRICE_OR_DISCOUNT_REGEX.containsMatchIn(combinedHeadline)
        val hasConcreteSpec = CONCRETE_SPEC_REGEX.containsMatchIn(combinedHeadline)

        if (config.excludeNonServerServices &&
            NON_SERVER_AD_REGEX.containsMatchIn(combinedHeadline) &&
            !(hasPriceOrDiscount && hasConcreteSpec)
        ) {
            return OfferFilterVerdict(accepted = false, reason = OfferFilterReason.NON_SERVER_AD)
        }

        if (!hasRichAiSummary && !hasPriceOrDiscount && !hasConcreteSpec) {
            if (config.excludeGenericMarketingBuzz) {
                val buzzHits = BUZZ_SPAM_PHRASE_REGEX.findAll(combinedHeadline).count()
                val lowerHeadline = combinedHeadline.lowercase()
                val uptimeBuzzPair = lowerHeadline.contains("99.9%") &&
                    (lowerHeadline.contains("high powered") || lowerHeadline.contains("locations world wide"))
                if (buzzHits >= 2 || uptimeBuzzPair) {
                    return OfferFilterVerdict(accepted = false, reason = OfferFilterReason.GENERIC_BUZZ_SPAM)
                }
            }

            if (config.requireMinimumHostingSignal &&
                !HOSTING_OR_PROMO_IDENTITY_REGEX.containsMatchIn(combinedHeadline)
            ) {
                return OfferFilterVerdict(accepted = false, reason = OfferFilterReason.INSUFFICIENT_SIGNAL)
            }
        }

        return OfferFilterVerdict(accepted = true)
    }

    fun isLowQualityOrSpam(
        offer: OverseasDealOffer,
        config: OverseasOfferFilterConfig = OverseasOfferFilterConfig(),
    ): Boolean = !evaluate(offer, config).accepted

    fun filter(
        offers: List<OverseasDealOffer>,
        config: OverseasOfferFilterConfig = OverseasOfferFilterConfig(),
    ): List<OverseasDealOffer> {
        if (!config.enabled || offers.isEmpty()) return offers
        return offers.filter { evaluate(it, config).accepted }
    }
}
