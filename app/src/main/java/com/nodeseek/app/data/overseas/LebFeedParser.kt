package com.nodeseek.app.data.overseas

import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasPriceConverter
import com.nodeseek.app.domain.overseas.OverseasSource
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.security.MessageDigest

/**
 * Parses LowEndBox (LEB) RSS 2.0 XML (`https://lowendbox.com/feed/`),
 * extracting article title, summary, HTML offer spec tables/lists, coupon codes,
 * direct order links, and real-time converted CNY prices.
 */
object LebFeedParser {

    const val LEB_BASE_URL = "https://lowendbox.com/"
    const val LEB_RSS_URL = "https://lowendbox.com/feed/"

    private val WP_POST_ID_REGEX = Regex("""[?&]p=(\d+)""")

    fun parseRss(
        xmlContent: String,
        fxRatesState: FxRatesState,
        baseUrl: String = LEB_BASE_URL,
    ): List<OverseasDealOffer> {
        if (xmlContent.isBlank()) return emptyList()
        val doc = Jsoup.parse(xmlContent, baseUrl, Parser.xmlParser())
        val seenIds = HashSet<String>()

        return doc.select("channel > item, item").mapNotNull { item ->
            val rawTitle = item.selectFirst("title")?.text()?.trim().orEmpty()
            if (rawTitle.isEmpty()) return@mapNotNull null
            val title = Jsoup.parse(rawTitle).text().trim()

            val link = item.selectFirst("link")?.text()?.trim()
                ?.ifEmpty { item.selectFirst("guid")?.text()?.trim().orEmpty() }
                .orEmpty()
            val guid = item.selectFirst("guid")?.text()?.trim().orEmpty()
            val postId = WP_POST_ID_REGEX.find(guid)?.groupValues?.getOrNull(1)
                ?: WP_POST_ID_REGEX.find(link)?.groupValues?.getOrNull(1)
                ?: shortSha(link.ifEmpty { title })

            val offerId = "leb-$postId"
            if (!seenIds.add(offerId)) return@mapNotNull null

            val creator = item.getElementsByTag("dc:creator").firstOrNull()?.text()?.trim()
                ?: item.selectFirst("creator, author")?.text()?.trim()
                ?: "LowEndBox"

            val pubDate = item.selectFirst("pubDate")?.text()?.trim().orEmpty()
            val publishedMillis = OverseasOfferExtractor.parseTimestampMillis(pubDate)

            val contentEncodedHtml = item.getElementsByTag("content:encoded").firstOrNull()?.text().orEmpty()
            val descriptionHtml = item.selectFirst("description")?.text().orEmpty()
            val fullHtml = contentEncodedHtml.ifBlank { descriptionHtml }

            val fullReadable = OverseasOfferExtractor.htmlToReadableText(fullHtml)
            val descriptionReadable = OverseasOfferExtractor.htmlToReadableText(descriptionHtml)

            // Prefer structured spec lines from LEB offer tables/lists if present, paired with concise description
            val specTableLines = extractLebTableAndListHighlights(fullHtml)
            val summarySource = when {
                specTableLines.isNotBlank() && descriptionReadable.isNotBlank() ->
                    "$specTableLines\n$descriptionReadable"
                specTableLines.isNotBlank() -> specTableLines
                fullReadable.isNotBlank() -> fullReadable
                else -> descriptionReadable
            }
            val summary = OverseasOfferExtractor.buildSummary(summarySource, maxChars = 560)

            val orderLinks = OverseasOfferExtractor.extractOrderLinks(
                rawHtml = fullHtml,
                plainText = fullReadable,
                baseUrl = baseUrl,
            )
            val couponCodes = OverseasOfferExtractor.extractCouponCodes(
                title = title,
                bodyText = fullReadable,
                orderLinks = orderLinks,
            )
            val priceMatches = OverseasPriceConverter.extractAndConvert(
                title = title,
                body = fullReadable,
                fxRatesState = fxRatesState,
            )
            val specHighlights = OverseasOfferExtractor.extractSpecHighlights(
                title = title,
                bodyText = fullReadable,
            )

            val categories = item.select("category").map { it.text().trim() }.filter { it.isNotEmpty() }
            val providerLabel = inferProviderOrAuthor(title = title, categories = categories, fallbackCreator = creator)

            OverseasDealOffer(
                id = offerId,
                source = OverseasSource.LEB,
                originalTitle = title,
                originalSummary = summary,
                authorOrProvider = providerLabel,
                sourceLink = normalizeLebLink(link, baseUrl),
                orderLinks = orderLinks,
                couponCodes = couponCodes,
                priceMatches = priceMatches,
                specHighlights = specHighlights,
                publishedAtEpochMillis = publishedMillis,
                isFlashOrBlackFriday = OverseasOfferExtractor.isFlashOrBlackFriday(
                    title = "$title ${categories.joinToString(" ")}",
                    bodyText = fullReadable,
                ),
                isOutOfStockHint = OverseasOfferExtractor.isOutOfStockHint(title, fullReadable),
            )
        }
    }

    /**
     * Extracts compact structured spec rows from LEB HTML `<table>` and `<ul>` offer blocks.
     */
    private fun extractLebTableAndListHighlights(html: String): String {
        if (html.isBlank()) return ""
        val doc = Jsoup.parseBodyFragment(html)
        val lines = LinkedHashSet<String>()

        doc.select("table tr").forEach { row ->
            val cells = row.select("th, td")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
            if (cells.isNotEmpty()) {
                val joined = cells.joinToString(" | ")
                if (joined.length in 4..180) {
                    lines += joined
                }
            }
        }

        if (lines.size < 4) {
            doc.select("ul li").forEach { li ->
                val text = li.text().trim()
                if (text.length in 3..140 && SPEC_OR_PRICE_HINT_REGEX.containsMatchIn(text)) {
                    lines += "• $text"
                }
            }
        }

        return lines.take(6).joinToString("\n")
    }

    private fun inferProviderOrAuthor(
        title: String,
        categories: List<String>,
        fallbackCreator: String,
    ): String {
        // Common LEB title pattern: "ProviderName – 2GB KVM VPS for $14.99/yr in LA!"
        val titlePrefix = title.split("–", "—", " - ", ":", "|")
            .firstOrNull()
            ?.trim()
            ?.takeIf { it.length in 2..28 && !it.startsWith("$") && !it.contains("Black Friday", ignoreCase = true) }

        if (titlePrefix != null) return titlePrefix

        val nonGenericCategory = categories.firstOrNull { cat ->
            cat.lowercase() !in GENERIC_LEB_CATEGORIES
        }
        return nonGenericCategory ?: fallbackCreator.ifBlank { "LowEndBox" }
    }

    private fun normalizeLebLink(link: String, baseUrl: String): String {
        val trimmed = link.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        val cleanBase = baseUrl.trimEnd('/')
        return if (trimmed.startsWith("/")) "$cleanBase$trimmed" else "$cleanBase/$trimmed"
    }

    private fun shortSha(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    private val SPEC_OR_PRICE_HINT_REGEX = Regex(
        pattern = """(?i)(cpu|core|ram|memory|nvme|ssd|hdd|bandwidth|traffic|gbps|mbps|ipv4|ipv6|coupon|promo|code|location|[$€£¥]\s*\d)""",
    )

    private val GENERIC_LEB_CATEGORIES = setOf(
        "offers", "vps", "kvm", "dedicated", "black friday", "cyber monday", "news", "tutorials", "uncategorized",
    )
}
