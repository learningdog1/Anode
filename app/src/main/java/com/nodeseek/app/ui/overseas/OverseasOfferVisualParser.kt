package com.nodeseek.app.ui.overseas

import androidx.compose.runtime.Immutable
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasForumPostFormatter
import com.nodeseek.app.domain.overseas.VpsGlossaryTranslator
import com.nodeseek.app.ui.components.formatRelativeTimeChinese
import java.util.Locale

@Immutable
data class OverseasOfferCardUiModel(
    val offer: OverseasDealOffer,
    val id: String,
    val merchantLabel: String,
    val merchantInitial: String,
    val relativeTimeText: String,
    val headlineTitle: String,
    val cleanSummary: String?,
)

/**
 * Pure presentation parser that transforms raw [OverseasDealOffer] records into precomputed,
 * tag-free [OverseasOfferCardUiModel] instances outside Compose composition.
 *
 * Intentionally performs NO title translation (online or offline) and extracts NO price, spec,
 * region, or discount chips—keeping the overseas feed crisp and faithful to the source item.
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

    private val GENERIC_SUMMARY_REGEX = Regex(
        pattern = """^来自\s+.+\s+的\s+LowEndTalk\s+海外服务器与\s+VPS\s+促销情报""",
    )

    fun containsCjk(text: String): Boolean = VpsGlossaryTranslator.containsCjk(text)

    fun parseCardModel(
        offer: OverseasDealOffer,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): OverseasOfferCardUiModel {
        val headline = cleanDisplayTitle(offer.title.ifBlank { offer.originalTitle })

        val merchant = resolveMerchant(offer)
        val merchantInitial = merchant
            .firstOrNull { it.isLetterOrDigit() || containsCjk(it.toString()) }
            ?.uppercaseChar()
            ?.toString()
            ?: "L"

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

        val cleanSummary = resolveCleanSummary(offer.summary)

        return OverseasOfferCardUiModel(
            offer = offer,
            id = offer.id,
            merchantLabel = merchant,
            merchantInitial = merchantInitial,
            relativeTimeText = relativeTimeText,
            headlineTitle = headline,
            cleanSummary = cleanSummary,
        )
    }

    internal fun cleanDisplayTitle(raw: String): String {
        if (raw.isBlank()) return ""
        return OverseasForumPostFormatter.decodeHtmlEntities(raw)
            .replace('\u00A0', ' ')
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

    private fun resolveCleanSummary(rawSummary: String): String? {
        val trimmed = OverseasForumPostFormatter.decodeHtmlEntities(rawSummary)
            .replace('\u00A0', ' ')
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
        if (trimmed.isEmpty()) return null
        if (GENERIC_SUMMARY_REGEX.containsMatchIn(trimmed)) return null
        return trimmed
    }
}
