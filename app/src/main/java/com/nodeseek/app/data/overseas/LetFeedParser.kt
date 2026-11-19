package com.nodeseek.app.data.overseas

import com.nodeseek.app.data.valuation.FxRatesState
import com.nodeseek.app.domain.overseas.OverseasDealOffer
import com.nodeseek.app.domain.overseas.OverseasPriceConverter
import com.nodeseek.app.domain.overseas.OverseasSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.security.MessageDigest

/**
 * Parses LowEndTalk (LET) Offers from both:
 * 1. LET Offers RSS 2.0 XML (`https://lowendtalk.com/categories/offers/feed.rss`)
 * 2. Vanilla Forums JSON endpoint fallback (`https://lowendtalk.com/categories/offers.json` / `discussions.json`)
 */
object LetFeedParser {

    const val LET_BASE_URL = "https://lowendtalk.com/"
    const val LET_RSS_URL = "https://lowendtalk.com/categories/offers/feed.rss"
    const val LET_JSON_URL = "https://lowendtalk.com/categories/offers.json"

    private val DISCUSSION_ID_REGEX = Regex("""/discussion/(\d+)""")
    private val json = Json { ignoreUnknownKeys = true }

    fun parseRss(
        xmlContent: String,
        fxRatesState: FxRatesState,
        baseUrl: String = LET_BASE_URL,
    ): List<OverseasDealOffer> {
        if (xmlContent.isBlank()) return emptyList()
        val doc = Jsoup.parse(xmlContent, baseUrl, Parser.xmlParser())
        val seenIds = HashSet<String>()

        return doc.select("channel > item, item").mapNotNull { item ->
            val rawTitle = item.selectFirst("title")?.text()?.trim().orEmpty()
            if (rawTitle.isEmpty()) return@mapNotNull null

            val link = item.selectFirst("link")?.text()?.trim()
                ?.ifEmpty { item.selectFirst("guid")?.text()?.trim().orEmpty() }
                .orEmpty()
            val guid = item.selectFirst("guid")?.text()?.trim().orEmpty()
            val discussionId = extractDiscussionId(link)
                ?: extractDiscussionId(guid)
                ?: shortSha(link.ifEmpty { rawTitle })
            val offerId = "let-$discussionId"
            if (!seenIds.add(offerId)) return@mapNotNull null

            val author = item.getElementsByTag("dc:creator").firstOrNull()?.text()?.trim()
                ?: item.selectFirst("creator, author")?.text()?.trim()
                ?: "LowEndTalk"

            val pubDate = item.selectFirst("pubDate")?.text()?.trim().orEmpty()
            val publishedMillis = OverseasOfferExtractor.parseTimestampMillis(pubDate)

            val rawHtmlBody = item.getElementsByTag("content:encoded").firstOrNull()?.text()
                ?.takeIf { it.isNotBlank() }
                ?: item.selectFirst("description")?.text().orEmpty()

            buildOffer(
                offerId = offerId,
                title = decodeHtmlTitle(rawTitle),
                author = author.ifBlank { "LowEndTalk" },
                sourceLink = normalizeLetLink(link, baseUrl),
                rawHtmlBody = rawHtmlBody,
                publishedAtEpochMillis = publishedMillis,
                fxRatesState = fxRatesState,
                baseUrl = baseUrl,
            )
        }
    }

    /**
     * Parses Vanilla Forums JSON (`categories/offers.json` object with `Discussions`, or Vanilla API v2 JSON array).
     */
    fun parseVanillaJson(
        jsonContent: String,
        fxRatesState: FxRatesState,
        baseUrl: String = LET_BASE_URL,
    ): List<OverseasDealOffer> {
        if (jsonContent.isBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(jsonContent) }.getOrNull() ?: return emptyList()
        val discussionElements: List<JsonElement> = when (root) {
            is JsonArray -> root.toList()
            is JsonObject -> {
                root["Discussions"]?.jsonArray?.toList()
                    ?: root["discussions"]?.jsonArray?.toList()
                    ?: root["Announcements"]?.jsonArray?.toList()
                    ?: emptyList()
            }
            else -> emptyList()
        }

        val seenIds = HashSet<String>()
        return discussionElements.mapNotNull { elem ->
            val obj = runCatching { elem.jsonObject }.getOrNull() ?: return@mapNotNull null
            val rawTitle = obj.stringField("Name", "name", "Title", "title").orEmpty().trim()
            if (rawTitle.isEmpty()) return@mapNotNull null

            val rawId = obj.stringField("DiscussionID", "discussionID", "discussionId", "id")
            val rawUrl = obj.stringField("Url", "url", "CanonicalUrl", "canonicalUrl").orEmpty()
            val resolvedLink = normalizeLetLink(
                rawUrl.ifBlank {
                    if (!rawId.isNullOrBlank()) "${baseUrl.trimEnd('/')}/discussion/$rawId" else baseUrl
                },
                baseUrl,
            )

            val discussionId = rawId?.takeIf { it.isNotBlank() }
                ?: extractDiscussionId(resolvedLink)
                ?: shortSha(resolvedLink + rawTitle)
            val offerId = "let-$discussionId"
            if (!seenIds.add(offerId)) return@mapNotNull null

            val insertUserObj = (obj["insertUser"] ?: obj["InsertUser"])?.let {
                runCatching { it.jsonObject }.getOrNull()
            }
            val author = obj.stringField("InsertName", "insertName", "FirstName", "author")
                ?: insertUserObj?.stringField("name", "Name")
                ?: "LowEndTalk"

            val rawBody = obj.stringField("Body", "body", "Excerpt", "excerpt").orEmpty()
            val rawDate = obj.stringField(
                "DateInserted", "dateInserted", "DateCreated", "dateCreated", "DateLastComment", "dateLastComment",
            )
            val publishedMillis = OverseasOfferExtractor.parseTimestampMillis(rawDate)

            buildOffer(
                offerId = offerId,
                title = decodeHtmlTitle(rawTitle),
                author = author.ifBlank { "LowEndTalk" },
                sourceLink = resolvedLink,
                rawHtmlBody = rawBody,
                publishedAtEpochMillis = publishedMillis,
                fxRatesState = fxRatesState,
                baseUrl = baseUrl,
            )
        }
    }

    private fun buildOffer(
        offerId: String,
        title: String,
        author: String,
        sourceLink: String,
        rawHtmlBody: String,
        publishedAtEpochMillis: Long,
        fxRatesState: FxRatesState,
        baseUrl: String,
    ): OverseasDealOffer {
        val readableBody = OverseasOfferExtractor.htmlToReadableText(rawHtmlBody)
        val summary = OverseasOfferExtractor.buildSummary(readableBody)
        val orderLinks = OverseasOfferExtractor.extractOrderLinks(
            rawHtml = rawHtmlBody,
            plainText = readableBody,
            baseUrl = baseUrl,
        )
        val couponCodes = OverseasOfferExtractor.extractCouponCodes(
            title = title,
            bodyText = readableBody,
            orderLinks = orderLinks,
        )
        val priceMatches = OverseasPriceConverter.extractAndConvert(
            title = title,
            body = readableBody,
            fxRatesState = fxRatesState,
        )
        val specHighlights = OverseasOfferExtractor.extractSpecHighlights(
            title = title,
            bodyText = readableBody,
        )

        return OverseasDealOffer(
            id = offerId,
            source = OverseasSource.LET,
            originalTitle = title,
            originalSummary = summary,
            authorOrProvider = author,
            sourceLink = sourceLink,
            orderLinks = orderLinks,
            couponCodes = couponCodes,
            priceMatches = priceMatches,
            specHighlights = specHighlights,
            publishedAtEpochMillis = publishedAtEpochMillis,
            isFlashOrBlackFriday = OverseasOfferExtractor.isFlashOrBlackFriday(title, readableBody),
            isOutOfStockHint = OverseasOfferExtractor.isOutOfStockHint(title, readableBody),
        )
    }

    private fun extractDiscussionId(url: String): String? =
        DISCUSSION_ID_REGEX.find(url)?.groupValues?.getOrNull(1)

    private fun normalizeLetLink(link: String, baseUrl: String): String {
        val trimmed = link.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        val cleanBase = baseUrl.trimEnd('/')
        return if (trimmed.startsWith("/")) "$cleanBase$trimmed" else "$cleanBase/$trimmed"
    }

    private fun decodeHtmlTitle(raw: String): String =
        Jsoup.parse(raw).text().trim()

    private fun JsonObject.stringField(vararg keys: String): String? {
        for (key in keys) {
            val value = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun shortSha(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }
}
