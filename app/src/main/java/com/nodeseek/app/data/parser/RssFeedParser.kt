package com.nodeseek.app.data.parser

import com.nodeseek.app.data.network.NodeSeekUrlNormalizer
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Parses NodeSeek's public RSS 2.0 XML stream (`https://rss.nodeseek.com/`) as a fast,
 * guest-friendly / Cloudflare-free fallback feed source.
 */
object RssFeedParser {

    private val POST_ID_REGEX = Regex("""/post-(\d+)(?:-\d+)?""")

    fun parse(xmlContent: String): ParsedFeedPageDto {
        if (xmlContent.isBlank()) {
            return ParsedFeedPageDto(
                pinnedTopics = emptyList(),
                topics = emptyList(),
                currentPage = 1,
                hasNextPage = false,
                config = null,
                isFromRssFallback = true,
            )
        }

        val doc = Jsoup.parse(xmlContent, NodeSeekUrlNormalizer.RSS_URL, Parser.xmlParser())
        val seenIds = HashSet<Long>()

        val items = doc.select("channel > item, item").mapNotNull { item ->
            val title = item.selectFirst("title")?.text()?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null

            val linkText = item.selectFirst("link")?.text()?.trim()
                ?.ifEmpty { item.selectFirst("guid")?.text()?.trim().orEmpty() }
                .orEmpty()
            val guidText = item.selectFirst("guid")?.text()?.trim().orEmpty()

            val postId = POST_ID_REGEX.find(linkText)?.groupValues?.getOrNull(1)?.toLongOrNull()
                ?: POST_ID_REGEX.find(guidText)?.groupValues?.getOrNull(1)?.toLongOrNull()
                ?: guidText.toLongOrNull()
                ?: return@mapNotNull null

            if (!seenIds.add(postId)) return@mapNotNull null

            val rawCategory = item.selectFirst("category")?.text()?.trim().orEmpty()
            val resolvedCategoryKey = resolveCategorySlug(rawCategory)
            val resolvedCategoryName = NodeSeekHtmlParser.DEFAULT_CATEGORY_NAMES[resolvedCategoryKey]
                ?: rawCategory

            val creator = item.getElementsByTag("dc:creator").firstOrNull()?.text()?.trim()
                ?: item.selectFirst("creator, author")?.text()?.trim()
                ?: ""

            val rawPubDate = item.selectFirst("pubDate")?.text()?.trim().orEmpty()
            val isoDate = normalizeRssDate(rawPubDate)

            val rawDescription = item.selectFirst("description")?.text()
                ?: item.getElementsByTag("content:encoded").firstOrNull()?.text()
                ?: ""
            val excerpt = NodeSeekHtmlParser.cleanCommentHtmlToExcerpt(rawDescription)
                .takeIf { it.isNotEmpty() }

            FeedTopicDto(
                id = postId,
                title = title,
                author = creator,
                authorId = null,
                avatarUrl = "",
                categoryName = resolvedCategoryName,
                categoryKey = resolvedCategoryKey,
                views = 0,
                replies = 0,
                activityDate = isoDate,
                pinned = false,
                requiredRank = null,
                excerpt = excerpt,
            )
        }

        return ParsedFeedPageDto(
            pinnedTopics = emptyList(),
            topics = items,
            currentPage = 1,
            hasNextPage = false,
            config = null,
            isFromRssFallback = true,
        )
    }

    internal fun resolveCategorySlug(rawCategory: String): String {
        val trimmed = rawCategory.trim()
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase(Locale.ROOT)
        if (NodeSeekHtmlParser.DEFAULT_CATEGORY_NAMES.containsKey(lower)) {
            return lower
        }
        return NodeSeekHtmlParser.CATEGORY_NAME_TO_KEY[lower] ?: lower
    }

    internal fun normalizeRssDate(rawPubDate: String): String {
        if (rawPubDate.isBlank()) return ""
        return runCatching {
            ZonedDateTime.parse(rawPubDate.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toString()
        }.getOrElse { rawPubDate.trim() }
    }
}
