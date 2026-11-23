package com.nodeseek.app.data.parser

import com.nodeseek.app.data.network.CloudflareChallengeException
import com.nodeseek.app.data.network.NodeSeekUrlNormalizer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.IOException

/**
 * Structured post-access restriction failures detected on `/post-{id}-{page}` HTML pages,
 * parsed from NodeSeek forum error containers.
 */
sealed class NodeSeekPostAccessException(message: String) : IOException(message) {
    class SignInRequired(
        message: String = "需要注册用户才能查看本帖。",
    ) : NodeSeekPostAccessException(message) {
        override fun equals(other: Any?): Boolean = other is SignInRequired
        override fun hashCode(): Int = javaClass.hashCode()
    }

    class PrivatePost(
        message: String = "本帖已被用户设为私有。",
    ) : NodeSeekPostAccessException(message) {
        override fun equals(other: Any?): Boolean = other is PrivatePost
        override fun hashCode(): Int = javaClass.hashCode()
    }

    data class LevelRequired(
        val minimumLevel: Int?,
    ) : NodeSeekPostAccessException(
        if (minimumLevel != null) "查看本帖需要 Lv $minimumLevel。" else "权限不足，请升级用户等级。",
    )
}

data class FeedTopicDto(
    val id: Long,
    val title: String,
    val author: String,
    val authorId: Long?,
    val avatarUrl: String,
    val categoryName: String,
    val categoryKey: String,
    val views: Int,
    val replies: Int,
    val activityDate: String,
    val pinned: Boolean,
    val requiredRank: Int? = null,
    val excerpt: String? = null,
    val likes: Int = 0,
)

data class ParsedFeedPageDto(
    val pinnedTopics: List<FeedTopicDto>,
    val topics: List<FeedTopicDto>,
    val currentPage: Int,
    val hasNextPage: Boolean,
    val config: ConfigPayloadDto? = null,
    val isFromRssFallback: Boolean = false,
)

data class ParsedThreadPageDto(
    val postData: PostDataDto,
    val openingComment: PostCommentDto?,
    val replies: List<PostCommentDto>,
    val config: ConfigPayloadDto,
)

data class DirectoryItemDto(
    val title: String,
    val url: String,
    val imageUrl: String?,
)

/**
 * Jsoup HTML parser for NodeSeek feed lists, thread pages, comment excerpts, and directory pages.
 */
object NodeSeekHtmlParser {

    private val POST_HREF_REGEX = Regex("""^/post-(\d+)-\d+""")
    private val SPACE_HREF_REGEX = Regex("""/space/(\d+)""")
    private val AVATAR_SRC_UID_REGEX = Regex("""/avatar/(\d+)""")
    private val PAGE_PATH_REGEX = Regex("""page-(\d+)""")
    private val DIGITS_REGEX = Regex("""\d+""")
    private val VIEW_RANK_REGEX = Regex("""查看本帖需要\s*Lv\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val MULTI_NEWLINE_REGEX = Regex("""\n{3,}""")
    private val FLOOR_HASH_REGEX = Regex("""#(\d+)""")
    private val HTML_TAG_PRESENCE_REGEX = Regex(
        """</?(?:h[1-6]|a|img|video|br|p|div|span|strong|b|em|i|del|s|strike|u|ins|kbd|code|pre|hr|details|summary|center|ul|ol|li|blockquote|font|small|mark|sub|sup|section|article)\b[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val STRAY_HEADING_TAG_REGEX = Regex("""</?h[1-6]\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val PREVIEW_LINKED_IMAGE_REGEX = Regex("""\[!\[[^\]]*]\([^)\n]+\)]\([^)\n]+\)""")
    private val PREVIEW_IMAGE_REGEX = Regex("""!\[([^\]]*)]\([^)\n]+\)""")
    private val PREVIEW_LINK_REGEX = Regex("""\[([^\]]+)]\([^)\n]+\)""")
    private val PREVIEW_STICKER_REGEX = Regex(""":([a-zA-Z]{2,5}\d{2,4}):""")
    private val STICKER_VIDEO_SOURCE_REGEX = Regex(
        """/static/image/sticker/(ac|yct|xhj|emoji)/(\d{2,4})\.(?:webm|mov|mp4|png|gif|webp)(?:[?#].*)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val PREVIEW_HTML_TAG_REGEX = Regex(
        """</?(?:h[1-6]|a|img|video|source|br|p|div|span|strong|b|em|i|del|s|strike|u|ins|kbd|code|pre|hr|details|summary|center|ul|ol|li|blockquote|font|small|mark|sub|sup|section|article|table|thead|tbody|tr|th|td|figure|figcaption|picture|iframe|abbr|cite|q|dl|dt|dd)\b[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val PREVIEW_BLOCKQUOTE_REGEX = Regex("""(?m)^\s*>+\s?""")
    private val PREVIEW_DECORATION_REGEX = Regex("""[`*~#]+""")

    val DEFAULT_CATEGORY_NAMES: Map<String, String> = mapOf(
        "" to "全部",
        "daily" to "日常",
        "tech" to "技术",
        "info" to "情报",
        "review" to "测评",
        "trade" to "交易",
        "carpool" to "拼车",
        "promotion" to "推广",
        "life" to "生活",
        "dev" to "Dev",
        "photo-share" to "贴图",
        "expose" to "曝光",
        "inside" to "内版",
        "meaningless" to "无意义",
        "sandbox" to "沙盒",
    )

    val CATEGORY_NAME_TO_KEY: Map<String, String> =
        DEFAULT_CATEGORY_NAMES.entries
            .filter { it.key.isNotEmpty() }
            .associate { (k, v) -> v.lowercase() to k }

    /**
     * Parses a feed, category, curated (`/award`), or search (`/search`) HTML page.
     */
    fun parseFeedPage(
        html: String,
        pageUrl: String = NodeSeekUrlNormalizer.ORIGIN,
        includeBlocked: Boolean = false,
    ): ParsedFeedPageDto {
        val document = Jsoup.parse(html, pageUrl)
        val config = TempScriptParser.extractFromDocument(document, requireAllCategory = true)
            ?: throw CloudflareChallengeException(url = pageUrl)

        val parsedUrl = pageUrl.toHttpUrlOrNull()
        val path = parsedUrl?.encodedPath ?: "/"
        val currentPage = extractPageNumber(path, parsedUrl?.queryParameter("page"))

        val categoryLookup = buildMap {
            putAll(DEFAULT_CATEGORY_NAMES)
            config.allCategory?.forEach { dto ->
                if (dto.key.isNotBlank() && dto.cnText.isNotBlank()) {
                    put(dto.key, dto.cnText)
                }
            }
        }

        // Page 1 of `/` or `/categories/*` merges `window.__config__.rotateTopics` into the pinned strip.
        val isFeedPath = path == "/" || path.startsWith("/categories/")
        val showsCarousel = isFeedPath && currentPage == 1 && !config.rotateTopics.isNullOrEmpty()
        val carouselSeenIds = HashSet<Long>()
        val carouselPosts = if (showsCarousel) {
            config.rotateTopics.orEmpty().mapNotNull { topic ->
                val id = topic.postId
                val title = topic.titleText.trim()
                if (id <= 0L || title.isEmpty() || carouselSeenIds.contains(id)) {
                    null
                } else if (!includeBlocked && topic.blocked) {
                    null
                } else {
                    carouselSeenIds.add(id)
                    val catKey = topic.category
                    val authorId = topic.op?.resolvedUid?.takeIf { it > 0L }
                    val rawAvatar = topic.op?.avatar?.takeIf { it.isNotBlank() }
                        ?: authorId?.let { "/avatar/$it.png" }.orEmpty()
                    FeedTopicDto(
                        id = id,
                        title = title,
                        author = topic.op?.name.orEmpty(),
                        authorId = authorId,
                        avatarUrl = if (rawAvatar.isEmpty()) "" else NodeSeekUrlNormalizer.normalizeNodeSeekUrl(rawAvatar),
                        categoryName = topic.categoryWord?.takeIf { it.isNotBlank() }
                            ?: categoryLookup[catKey].orEmpty(),
                        categoryKey = catKey,
                        views = topic.views,
                        replies = topic.comments,
                        activityDate = topic.lastCommentDate,
                        pinned = true,
                        requiredRank = topic.rank?.takeIf { it > 0 },
                        likes = topic.likeCount,
                    )
                }
            }
        } else {
            emptyList()
        }

        val itemSelector = if (includeBlocked) {
            ".post-list-item:not(.topic-carousel-item)"
        } else {
            ".post-list-item:not(.topic-carousel-item):not(.blocked-post)"
        }

        val seenListIds = HashSet<Long>()
        val listPosts = document.select(itemSelector).mapNotNull { item ->
            val link = item.selectFirst(".post-title a[href^=/post-]") ?: return@mapNotNull null
            val href = link.attr("href").trim()
            val id = POST_HREF_REGEX.find(href)?.groupValues?.getOrNull(1)?.toLongOrNull()
                ?: return@mapNotNull null
            if (carouselSeenIds.contains(id) || !seenListIds.add(id)) {
                return@mapNotNull null
            }

            val categoryEl = item.selectFirst(".post-category, a[href^=/categories/]")
            val catHref = categoryEl?.attr("href")?.trim().orEmpty()
            val categoryKey = catHref.substringAfterLast('/', missingDelimiterValue = "")
                .ifBlank { item.attr("data-category").trim() }
            val categoryName = categoryEl?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: categoryLookup[categoryKey].orEmpty()

            val lockEl = item.selectFirst(".post-title use[href=#lock]")
            val lockContainerText = lockEl?.closest("span")?.text()?.trim().orEmpty()
            val requiredRank = DIGITS_REGEX.find(lockContainerText)?.value?.toIntOrNull()?.takeIf { it > 0 }

            val authorEl = item.selectFirst(".info-author a")
            val authorName = authorEl?.text()?.trim().orEmpty()
            val authorHref = authorEl?.attr("href").orEmpty()
            val rawAvatarSrc = item.selectFirst(".avatar-normal")?.attr("src")?.trim()?.takeIf { it.isNotEmpty() }
            val authorId = SPACE_HREF_REGEX.find(authorHref)?.groupValues?.getOrNull(1)?.toLongOrNull()
                ?: rawAvatarSrc?.let { AVATAR_SRC_UID_REGEX.find(it)?.groupValues?.getOrNull(1)?.toLongOrNull() }

            val avatarSrc = rawAvatarSrc
                ?: authorId?.let { "/avatar/$it.png" }.orEmpty()

            val views = parseCount(item.selectFirst(".info-views span")?.text())
            val replies = parseCount(item.selectFirst(".info-comments-count span")?.text())
            val likes = parseCount(
                item.selectFirst(".info-likes span, .info-likes-count span, .info-drumsticks span")?.text()
                    ?: item.attr("data-likes").takeIf { it.isNotBlank() },
            )
            val timeEl = item.selectFirst("time")
            val activityDate = timeEl?.attr("datetime")?.trim()?.takeIf { it.isNotEmpty() }
                ?: timeEl?.attr("title")?.trim()?.takeIf { it.isNotEmpty() }
                ?: timeEl?.attr("data-time")?.trim()?.takeIf { it.isNotEmpty() }
                .orEmpty()
            val isPinned = item.selectFirst("[title=置顶]") != null

            FeedTopicDto(
                id = id,
                title = link.text().trim(),
                author = authorName,
                authorId = authorId,
                avatarUrl = if (avatarSrc.isEmpty()) "" else NodeSeekUrlNormalizer.normalizeNodeSeekUrl(avatarSrc),
                categoryName = categoryName,
                categoryKey = categoryKey,
                views = views,
                replies = replies,
                activityDate = activityDate,
                pinned = isPinned,
                requiredRank = requiredRank,
                likes = likes,
            )
        }

        // Pinned merge rule: carousel items first, then `[title="置顶"]` items, deduplicated by postId,
        // and all pinned postIds excluded from regular `topics`.
        val pinnedIds = HashSet<Long>()
        val mergedPinned = (carouselPosts + listPosts.filter { it.pinned }).filter { pinnedIds.add(it.id) }
        val normalTopics = listPosts.filter { !pinnedIds.contains(it.id) }

        val hasNextByLinks = document.select("[aria-label=pagination] a[href], .nsk-pager a[href]").any { anchor ->
            if (!anchor.isEnabledPaginationAnchor()) return@any false
            val rawHref = anchor.attr("href").trim()
            val resolved = parsedUrl?.resolve(rawHref)
                ?: NodeSeekUrlNormalizer.normalizeNodeSeekUrl(rawHref).toHttpUrlOrNull()
            val targetPage = extractPageNumber(
                path = resolved?.encodedPath ?: rawHref,
                queryPage = resolved?.queryParameter("page"),
            )
            targetPage > currentPage
        }
        val hasEnabledNextAnchor = document
            .select("a.pager-next[href], a[rel=next][href]")
            .any { it.isEnabledPaginationAnchor() }
        val hasNextPage = hasNextByLinks || hasEnabledNextAnchor

        return ParsedFeedPageDto(
            pinnedTopics = mergedPinned,
            topics = normalTopics,
            currentPage = currentPage,
            hasNextPage = hasNextPage,
            config = config,
            isFromRssFallback = false,
        )
    }

    /**
     * Parses a `/post-{id}-{page}` HTML document, checking access restrictions first and ordering
     * floor replies as `pined == true` -> `hot == true` -> server floor order.
     */
    fun parseThreadPage(
        html: String,
        pageUrl: String = NodeSeekUrlNormalizer.ORIGIN,
        includeBlocked: Boolean = false,
    ): ParsedThreadPageDto {
        val document = Jsoup.parse(html, pageUrl)
        detectPostAccessFailure(document)?.let { throw it }

        val isForumPage = document.selectFirst("#nsk-body, #nsk-body-left") != null
        val config = TempScriptParser.extractFromDocument(document, requireAllCategory = true)
        if (config == null) {
            if (isForumPage) {
                throw IOException("官网没有返回可读取的帖子内容。")
            }
            throw CloudflareChallengeException(url = pageUrl)
        }

        val postData = config.postData
            ?: throw IOException("没有找到帖子内容")

        val domSignaturesByFloor = extractDomSignaturesByFloor(document)
        val domContentMarkdownByFloor = extractDomPostMarkdownByFloor(document)
        val domAuthorsByFloor = extractDomAuthorsByFloor(document)

        val visibleComments = (if (includeBlocked) {
            postData.comments
        } else {
            postData.comments.filter { !it.blocked }
        }).map { comment ->
            val hasStructuredSignature = !comment.signatureMarkdown.isNullOrBlank() ||
                !comment.signature.isNullOrBlank() ||
                !comment.poster.signatureMarkdown.isNullOrBlank() ||
                !comment.poster.signature.isNullOrBlank()
            val fallbackSig = domSignaturesByFloor[comment.floorIndex]
            val fallbackBody = if (comment.markdown.isBlank()) domContentMarkdownByFloor[comment.floorIndex] else null
            val domAuthor = domAuthorsByFloor[comment.floorIndex]
            val enrichedPoster = if (comment.poster.resolvedUid <= 0L && domAuthor != null && domAuthor.resolvedUid > 0L) {
                comment.poster.copy(
                    uid = domAuthor.resolvedUid,
                    name = comment.poster.name.ifBlank { domAuthor.name },
                    avatar = comment.poster.avatar ?: domAuthor.avatar,
                )
            } else {
                comment.poster
            }
            comment.copy(
                poster = enrichedPoster,
                markdown = fallbackBody ?: comment.markdown,
                signature = if (!hasStructuredSignature && !fallbackSig.isNullOrBlank()) fallbackSig else comment.signature,
            )
        }

        val openingComment = visibleComments.firstOrNull { it.floorIndex == 0 }
        val seenCommentIds = HashSet<Long>()
        val rawReplies = visibleComments.filter { comment ->
            comment.floorIndex > 0 && seenCommentIds.add(comment.commentId)
        }

        val resolvedPostData = if (postData.op == null || postData.op.resolvedUid <= 0L) {
            val opReplyPoster = rawReplies.firstOrNull { it.poster.isOp == true }?.poster
            val domOp = domAuthorsByFloor[0] ?: extractDomHeaderOpAuthor(document)
            val fallbackUid = openingComment?.poster?.resolvedUid?.takeIf { it > 0L }
                ?: opReplyPoster?.resolvedUid?.takeIf { it > 0L }
                ?: domOp?.resolvedUid?.takeIf { it > 0L }
                ?: 0L
            val fallbackName = postData.op?.name?.takeIf { it.isNotBlank() }
                ?: openingComment?.poster?.name?.takeIf { it.isNotBlank() }
                ?: opReplyPoster?.name?.takeIf { it.isNotBlank() }
                ?: domOp?.name.orEmpty()
            val fallbackAvatar = postData.op?.avatar
                ?: openingComment?.poster?.avatar
                ?: opReplyPoster?.avatar
                ?: domOp?.avatar
            if (fallbackUid > 0L || fallbackName.isNotBlank()) {
                postData.copy(
                    op = TopicAuthorDto(
                        uid = fallbackUid,
                        name = fallbackName,
                        avatar = fallbackAvatar,
                    ),
                )
            } else {
                postData
            }
        } else {
            postData
        }

        // Reply display ordering: pinned (`pined == true`) -> hot (`hot == true`) -> chronological floor order.
        val pinnedReplies = rawReplies.filter { it.pined == true }
        val hotReplies = rawReplies.filter { it.pined != true && it.hot == true }
        val regularReplies = rawReplies.filter { it.pined != true && it.hot != true }

        return ParsedThreadPageDto(
            postData = resolvedPostData,
            openingComment = openingComment,
            replies = pinnedReplies + hotReplies + regularReplies,
            config = config,
        )
    }

    /**
     * Inspects `#nsk-body-left` / `#nsk-body` when `.nsk-post, .post-content` is absent to classify
     * private posts, insufficient Lv rank, or unauthenticated access.
     */
    fun detectPostAccessFailure(document: Document): NodeSeekPostAccessException? {
        val forumBody = document.selectFirst("#nsk-body-left")
            ?: document.selectFirst("#nsk-body")
            ?: return null

        if (forumBody.selectFirst(".nsk-post, .post-content") != null) {
            return null
        }

        val message = forumBody.text().replace(WHITESPACE_REGEX, " ").trim()
        val rankMatch = VIEW_RANK_REGEX.find(message)
        val requiredLevelStr = rankMatch?.groupValues?.getOrNull(1)

        if (requiredLevelStr == "255" || message.contains("本帖已经被用户设为私有")) {
            return NodeSeekPostAccessException.PrivatePost()
        }
        if (rankMatch != null || (message.contains("权限不足") && message.contains("升级您的用户等级"))) {
            return NodeSeekPostAccessException.LevelRequired(requiredLevelStr?.toIntOrNull())
        }
        if (message.contains("需要注册用户才能查看")) {
            return NodeSeekPostAccessException.SignInRequired()
        }
        return null
    }

    /**
     * Converts HTML comment excerpts returned by `/api/content/list-comments` into clean plain text,
     * stripping scripts/styles and replacing inline images with readable placeholders.
     */
    fun cleanCommentHtmlToExcerpt(rawHtml: String): String {
        if (rawHtml.isBlank()) return ""
        val doc = Jsoup.parseBodyFragment(rawHtml)
        doc.select("script, style").remove()
        for (img in doc.select("img")) {
            val altText = img.attr("alt").trim().ifEmpty { "[图片]" }
            img.replaceWith(TextNode(altText))
        }
        for (video in doc.select("video")) {
            val isSticker = video.hasClass("sticker") || extractStickerCodeFromVideoElement(video) != null
            video.replaceWith(TextNode(if (isSticker) "[表情]" else "[视频]"))
        }
        for (block in doc.select("br, p, div, li")) {
            block.appendText(" ")
        }
        return doc.body().wholeText().replace(WHITESPACE_REGEX, " ").trim()
    }

    /**
     * Normalizes an author signature string (from `signature`, `signatureMarkdown`, or DOM `.signature`)
     * into clean Markdown, converting embedded HTML tags such as `<h2>...</h2>`, `<p>...</p>`, `<br>`,
     * and `<a><img></a>` banners while preserving native Markdown syntax.
     */
    fun normalizeSignatureToMarkdown(rawSignature: String?): String? {
        val trimmed = rawSignature?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val converted = htmlFragmentToMarkdown(trimmed)
            .replace(STRAY_HEADING_TAG_REGEX, "")
            .trim()
        return converted.takeIf { it.isNotEmpty() }
    }

    /**
     * Converts an HTML fragment (such as a `.signature` DOM block or server HTML string) into
     * clean Markdown while keeping already-Markdown plain text intact.
     */
    fun htmlFragmentToMarkdown(rawHtml: String): String {
        val trimmed = rawHtml.trim()
        if (trimmed.isEmpty()) return ""
        if (!HTML_TAG_PRESENCE_REGEX.containsMatchIn(trimmed)) {
            return trimmed
        }
        val doc = Jsoup.parseBodyFragment(trimmed, NodeSeekUrlNormalizer.ORIGIN)
        return htmlFragmentToMarkdown(doc.body())
    }

    /**
     * Converts a Jsoup [Element] subtree into normalized Markdown, turning `<a><img></a>` banners
     * into `[![alt](img)](href)` and resolving relative NodeSeek URLs.
     */
    fun htmlFragmentToMarkdown(element: Element): String {
        val raw = buildString {
            for (child in element.childNodes()) {
                appendMarkdownFromNode(child, this)
            }
        }
        return raw.replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(MULTI_NEWLINE_REGEX, "\n\n")
            .trim()
    }

    /**
     * Converts Markdown/HTML rich text into a clean single-line preview for Inbox / DM thread lists.
     */
    fun stripMarkdownToPlainPreview(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val normalized = if (HTML_TAG_PRESENCE_REGEX.containsMatchIn(trimmed)) {
            htmlFragmentToMarkdown(trimmed)
        } else {
            trimmed
        }
        var text = normalized
            .replace(PREVIEW_LINKED_IMAGE_REGEX, "[图片]")
            .replace(PREVIEW_IMAGE_REGEX) { m ->
                val alt = m.groupValues[1].trim()
                if (alt.isNotEmpty() && alt != "image") "[$alt]" else "[图片]"
            }
            .replace(PREVIEW_LINK_REGEX) { m -> m.groupValues[1] }
            .replace(PREVIEW_STICKER_REGEX, "[表情]")
            .replace(PREVIEW_HTML_TAG_REGEX, " ")
            .replace(PREVIEW_BLOCKQUOTE_REGEX, "")
            .replace(PREVIEW_DECORATION_REGEX, "")
        text = Parser.unescapeEntities(text, false).replace('\u00A0', ' ')
        text = text.replace(WHITESPACE_REGEX, " ").trim()
        return text
    }

    private fun extractDomSignaturesByFloor(document: Document): Map<Int, String> {
        val signatureElements = document.select(".signature")
        if (signatureElements.isEmpty()) return emptyMap()

        val result = LinkedHashMap<Int, String>()
        for (sigEl in signatureElements) {
            val floor = resolveFloorNumberForContainer(sigEl.closest(".content-item, .comment-item, .nsk-post")) ?: continue
            if (result.containsKey(floor)) continue
            val md = htmlFragmentToMarkdown(sigEl)
            if (md.isNotBlank()) {
                result[floor] = md
            }
        }
        return result
    }

    private fun extractDomPostMarkdownByFloor(document: Document): Map<Int, String> {
        val postElements = document.select(".nsk-post article.post-content, .content-item article.post-content, .comment-item article.post-content")
        if (postElements.isEmpty()) return emptyMap()

        val result = LinkedHashMap<Int, String>()
        for (contentEl in postElements) {
            val floor = resolveFloorNumberForContainer(contentEl.closest(".content-item, .comment-item, .nsk-post")) ?: continue
            if (result.containsKey(floor)) continue
            val cloned = contentEl.clone()
            cloned.select(".signature, .user-card, script, style").remove()
            val md = htmlFragmentToMarkdown(cloned)
            if (md.isNotBlank()) {
                result[floor] = md
            }
        }
        return result
    }

    private fun extractDomAuthorsByFloor(document: Document): Map<Int, TopicAuthorDto> {
        val containers = document.select(".nsk-post, .content-item, .comment-item")
        if (containers.isEmpty()) return emptyMap()

        val result = LinkedHashMap<Int, TopicAuthorDto>()
        for (container in containers) {
            val floor = resolveFloorNumberForContainer(container) ?: continue
            if (result.containsKey(floor)) continue
            extractAuthorFromContainer(container)?.let { result[floor] = it }
        }
        return result
    }

    private fun extractDomHeaderOpAuthor(document: Document): TopicAuthorDto? {
        val headerContainer = document.selectFirst(".nsk-post, #nsk-body-left .nsk-content-meta-info")
            ?: return null
        return extractAuthorFromContainer(headerContainer)
    }

    private fun extractAuthorFromContainer(container: Element): TopicAuthorDto? {
        val spaceAnchors = container.select("a[href*=/space/]")
            .filter { it.closest("article.post-content, .signature") == null }
        val uidFromLink = spaceAnchors.firstNotNullOfOrNull { anchor ->
            SPACE_HREF_REGEX.find(anchor.attr("href"))?.groupValues?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
        }
        val nameFromLink = spaceAnchors.firstNotNullOfOrNull { anchor ->
            anchor.text().trim().takeIf { it.isNotEmpty() }
        }.orEmpty()
        val avatarEl = container.select("img[src*=/avatar/], img.avatar-normal")
            .firstOrNull { it.closest("article.post-content, .signature") == null }
        val avatarSrc = avatarEl?.attr("src")?.trim()?.takeIf { it.isNotEmpty() }
        val uidFromAvatar = avatarSrc?.let { AVATAR_SRC_UID_REGEX.find(it)?.groupValues?.getOrNull(1)?.toLongOrNull() }
        val resolvedUid = uidFromLink ?: uidFromAvatar ?: 0L
        if (resolvedUid <= 0L && nameFromLink.isEmpty()) return null
        return TopicAuthorDto(
            uid = resolvedUid,
            name = nameFromLink,
            avatar = avatarSrc,
        )
    }

    private fun resolveFloorNumberForContainer(container: Element?): Int? {
        if (container == null) return null
        container.id().trim().toIntOrNull()?.let { if (it >= 0) return it }
        container.attr("data-floor").trim().toIntOrNull()?.let { if (it >= 0) return it }
        if (container.hasClass("nsk-post") && container.closest("#comment-list, .comments") == null) {
            return 0
        }
        val floorText = container.selectFirst(".floor-link, .nsk-content-meta-info a[href*=#], a.floor")?.text()
        if (!floorText.isNullOrBlank()) {
            FLOOR_HASH_REGEX.find(floorText)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
                return it
            }
        }
        return null
    }

    private fun appendMarkdownFromNode(node: Node, out: StringBuilder) {
        when (node) {
            is TextNode -> out.append(node.wholeText)
            is Element -> {
                val tag = node.tagName().lowercase()
                when (tag) {
                    "script", "style" -> Unit
                    "br" -> out.append("\n")
                    "hr" -> out.append("\n---\n")
                    "img" -> {
                        val src = resolveImageElementUrl(node)
                        val alt = node.attr("alt").trim().replace("[", "\\[").replace("]", "\\]")
                        if (src.isNotBlank()) {
                            out.append("![").append(alt).append("](").append(src).append(')')
                        } else if (alt.isNotBlank()) {
                            out.append(alt)
                        }
                    }
                    "video" -> {
                        val stickerCode = extractStickerCodeFromVideoElement(node)
                        if (stickerCode != null) {
                            out.append(':').append(stickerCode).append(':')
                        }
                    }
                    "a" -> {
                        val href = resolveElementUrl(node, "href")
                        val imgs = node.select("img")
                        val textOutsideImg = node.ownText().trim()
                        if (imgs.size == 1 && textOutsideImg.isEmpty()) {
                            val img = imgs.first()!!
                            val src = resolveImageElementUrl(img)
                            val alt = img.attr("alt").trim().replace("[", "\\[").replace("]", "\\]")
                            if (src.isNotBlank() && href.isNotBlank()) {
                                out.append("[![").append(alt).append("](").append(src).append(")](").append(href).append(')')
                            } else if (src.isNotBlank()) {
                                out.append("![").append(alt).append("](").append(src).append(')')
                            }
                        } else {
                            val inner = buildString {
                                for (c in node.childNodes()) appendMarkdownFromNode(c, this)
                            }.trim()
                            if (href.isNotBlank() && inner.isNotBlank()) {
                                out.append('[').append(inner).append("](").append(href).append(')')
                            } else if (href.isNotBlank()) {
                                out.append(href)
                            } else {
                                out.append(inner)
                            }
                        }
                    }
                    "strong", "b" -> {
                        val inner = renderChildren(node)
                        if (inner.isNotBlank()) out.append("**").append(inner.trim()).append("**")
                    }
                    "em", "i" -> {
                        val inner = renderChildren(node)
                        if (inner.isNotBlank()) out.append("*").append(inner.trim()).append("*")
                    }
                    "del", "s", "strike" -> {
                        val inner = renderChildren(node)
                        if (inner.isNotBlank()) out.append("~~").append(inner.trim()).append("~~")
                    }
                    "u", "ins" -> {
                        val inner = renderChildren(node)
                        if (inner.isNotBlank()) out.append("<u>").append(inner.trim()).append("</u>")
                    }
                    "kbd" -> {
                        val inner = renderChildren(node)
                        if (inner.isNotBlank()) out.append("<kbd>").append(inner.trim()).append("</kbd>")
                    }
                    "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        val level = (tag[1] - '0').coerceIn(1, 6)
                        val inner = renderChildren(node).replace(WHITESPACE_REGEX, " ").trim()
                        if (inner.isNotEmpty()) {
                            if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                            out.append("#".repeat(level)).append(' ').append(inner).append('\n')
                        }
                    }
                    "details" -> {
                        val summaryEl = node.children().firstOrNull { it.tagName().equals("summary", ignoreCase = true) }
                        val summaryText = summaryEl?.let { renderChildren(it).replace(WHITESPACE_REGEX, " ").trim() }
                            ?.takeIf { it.isNotEmpty() } ?: "展开详情"
                        val bodyContent = buildString {
                            for (c in node.childNodes()) {
                                if (c === summaryEl) continue
                                appendMarkdownFromNode(c, this)
                            }
                        }.trim()
                        if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                        out.append("<details><summary>").append(summaryText).append("</summary>\n")
                        if (bodyContent.isNotEmpty()) {
                            out.append(bodyContent).append('\n')
                        }
                        out.append("</details>\n")
                    }
                    "code" -> {
                        if (node.parent()?.tagName()?.equals("pre", ignoreCase = true) == true) {
                            out.append(node.wholeText())
                        } else {
                            val inner = node.text().trim()
                            if (inner.isNotEmpty()) out.append('`').append(inner).append('`')
                        }
                    }
                    "pre" -> {
                        val code = node.wholeText().trim('\n', '\r')
                        if (code.isNotBlank()) {
                            out.append("\n```\n").append(code).append("\n```\n")
                        }
                    }
                    "p", "div", "center", "section", "article" -> {
                        val inner = renderChildren(node).trim()
                        if (inner.isNotEmpty()) {
                            if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                            out.append(inner).append('\n')
                        }
                    }
                    "ul" -> {
                        val inner = renderChildren(node).trim()
                        if (inner.isNotEmpty()) {
                            if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                            out.append(inner).append('\n')
                        }
                    }
                    "ol" -> {
                        var idx = 1
                        if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                        for (child in node.children()) {
                            if (child.tagName().equals("li", ignoreCase = true)) {
                                val itemText = renderChildren(child).trim()
                                if (itemText.isNotEmpty()) {
                                    out.append(idx).append(". ").append(itemText).append('\n')
                                    idx++
                                }
                            } else {
                                appendMarkdownFromNode(child, out)
                            }
                        }
                    }
                    "blockquote" -> {
                        val inner = renderChildren(node).trim()
                        if (inner.isNotEmpty()) {
                            if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                            inner.lines().forEach { line ->
                                out.append("> ").append(line).append('\n')
                            }
                        }
                    }
                    "li" -> {
                        val inner = renderChildren(node).trim()
                        if (inner.isNotEmpty()) {
                            if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                            out.append("- ").append(inner).append('\n')
                        }
                    }
                    else -> {
                        for (c in node.childNodes()) appendMarkdownFromNode(c, out)
                    }
                }
            }
        }
    }

    private fun renderChildren(element: Element): String = buildString {
        for (c in element.childNodes()) {
            appendMarkdownFromNode(c, this)
        }
    }

    private fun extractStickerCodeFromVideoElement(video: Element): String? {
        val candidateUrls = buildList {
            video.attr("src").trim().takeIf { it.isNotEmpty() }?.let(::add)
            for (source in video.select("source")) {
                source.attr("src").trim().takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
        for (url in candidateUrls) {
            val match = STICKER_VIDEO_SOURCE_REGEX.find(url) ?: continue
            val group = match.groupValues[1].lowercase()
            val num = match.groupValues[2]
            return "$group$num"
        }
        return null
    }

    private fun resolveElementUrl(element: Element, attrName: String): String {
        val raw = element.attr(attrName).trim()
        if (raw.isEmpty()) return ""
        val schemeEnd = raw.indexOf(':')
        if (schemeEnd > 0 && !raw.startsWith("//")) {
            val schemePrefix = raw.substring(0, schemeEnd)
            if (schemePrefix.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) {
                if (!schemePrefix.equals("http", ignoreCase = true) &&
                    !schemePrefix.equals("https", ignoreCase = true)
                ) {
                    return ""
                }
            }
        }
        val abs = element.absUrl(attrName).trim()
        if (abs.startsWith("http://", ignoreCase = true) || abs.startsWith("https://", ignoreCase = true)) {
            return abs
        }
        val normalized = NodeSeekUrlNormalizer.normalizeNodeSeekUrl(raw)
        return if (normalized.startsWith("http://", ignoreCase = true) ||
            normalized.startsWith("https://", ignoreCase = true)
        ) {
            normalized
        } else {
            ""
        }
    }

    private fun resolveImageElementUrl(element: Element): String {
        val lazyAttrs = listOf(
            "data-src",
            "data-original",
            "data-lazy-src",
            "data-url",
            "data-actualsrc",
        )
        for (attr in lazyAttrs) {
            val raw = element.attr(attr).trim()
            if (raw.isNotEmpty() && !raw.startsWith("data:image/", ignoreCase = true)) {
                val resolved = resolveElementUrl(element, attr)
                if (resolved.isNotBlank()) return resolved
            }
        }
        val rawSrc = element.attr("src").trim()
        if (rawSrc.isNotEmpty() && !rawSrc.startsWith("data:image/", ignoreCase = true)) {
            val resolved = resolveElementUrl(element, "src")
            if (resolved.isNotBlank()) return resolved
        }
        val srcset = element.attr("data-srcset").ifBlank { element.attr("srcset") }.trim()
        if (srcset.isNotEmpty()) {
            val candidate = srcset.substringBefore(',').trim().substringBefore(' ').trim()
            if (candidate.isNotEmpty() &&
                !candidate.startsWith("javascript:", ignoreCase = true) &&
                !candidate.startsWith("data:image/", ignoreCase = true)
            ) {
                return NodeSeekUrlNormalizer.normalizeNodeSeekUrl(candidate)
            }
        }
        return if (rawSrc.startsWith("data:image/", ignoreCase = true)) rawSrc else ""
    }

    /**
     * Parses `/providers` and `/friends` directory HTML (`DirectoryScripts.read`).
     */
    fun parseDirectoryPage(
        html: String,
        pageUrl: String = "${NodeSeekUrlNormalizer.ORIGIN}/providers",
    ): List<DirectoryItemDto> {
        val document = Jsoup.parse(html, pageUrl)
        val parsedPageUrl = pageUrl.toHttpUrlOrNull()
        val path = parsedPageUrl?.encodedPath ?: "/providers"
        val selector = if (path == "/providers") ".provider a[href]" else "a[title][href]"
        val seenUrls = HashSet<String>()

        return document.select(selector).mapNotNull { anchor ->
            val img = anchor.selectFirst("img") ?: return@mapNotNull null
            if (anchor.closest("footer, header") != null) return@mapNotNull null

            val title = anchor.attr("title").trim().ifEmpty { anchor.text().trim() }
            if (title.isEmpty()) return@mapNotNull null

            val resolvedHref = anchor.absUrl("href").ifBlank {
                NodeSeekUrlNormalizer.normalizeNodeSeekUrl(anchor.attr("href"))
            }
            val targetUrl = resolvedHref.toHttpUrlOrNull() ?: return@mapNotNull null
            if (targetUrl.scheme != "http" && targetUrl.scheme != "https") return@mapNotNull null

            if (path == "/friends" && NodeSeekUrlNormalizer.isNodeSeekHost(targetUrl.host)) {
                return@mapNotNull null
            }

            val canonicalUrl = targetUrl.toString()
            if (!seenUrls.add(canonicalUrl)) return@mapNotNull null

            val imageUrl = resolveImageElementUrl(img).takeIf { it.isNotBlank() }

            DirectoryItemDto(
                title = title,
                url = canonicalUrl,
                imageUrl = imageUrl,
            )
        }
    }

    /**
     * Parses static article pages (`/about`, `/termsofservice`) into Markdown (`SiteScripts.article`).
     */
    fun parseStaticArticle(
        html: String,
        pageUrl: String = NodeSeekUrlNormalizer.ORIGIN,
    ): String {
        val document = Jsoup.parse(html, pageUrl)
        val config = runCatching {
            TempScriptParser.extractFromDocument(document, requireAllCategory = false)
        }.getOrNull()
        val opMarkdown = config?.postData?.comments
            ?.firstOrNull { it.floorIndex == 0 }
            ?.markdown
            ?.trim()
        if (!opMarkdown.isNullOrEmpty()) {
            return opMarkdown
        }

        val root = document.selectFirst("article.post-content")
            ?: document.selectFirst("article")
            ?: document.selectFirst("#nsk-body-left-block, #nsk-frame-block")
            ?: throw IOException("官网暂未返回页面内容")

        root.select("script, style, .user-card").remove()
        for (anchor in root.select("a[href]")) {
            val resolved = anchor.absUrl("href").toHttpUrlOrNull()
            if (resolved != null && (resolved.scheme == "http" || resolved.scheme == "https")) {
                anchor.replaceWith(TextNode("[${anchor.text().trim()}]($resolved)"))
            }
        }

        val paragraphs = root.select("h1, h2, h3, p, li").mapNotNull { el ->
            if (el.tagName().equals("p", ignoreCase = true) && el.closest("li") != null) {
                return@mapNotNull null
            }
            val text = el.text().trim()
            if (text.isEmpty()) {
                null
            } else {
                val tag = el.tagName().uppercase()
                when {
                    tag.startsWith("H") -> "## $text"
                    tag == "LI" -> "- $text"
                    else -> text
                }
            }
        }

        if (paragraphs.isEmpty()) {
            throw IOException("官网暂未返回页面内容")
        }
        return paragraphs.joinToString("\n\n")
    }

    private fun Element.isEnabledPaginationAnchor(): Boolean {
        if (hasClass("disabled") || hasAttr("disabled") || attr("aria-disabled").equals("true", ignoreCase = true)) {
            return false
        }
        val rawHref = attr("href").trim()
        return rawHref.isNotEmpty() &&
            !rawHref.startsWith("#") &&
            !rawHref.startsWith("javascript:", ignoreCase = true)
    }

    private fun extractPageNumber(path: String, queryPage: String?): Int {
        val fromPath = PAGE_PATH_REGEX.find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val fromQuery = queryPage?.toIntOrNull()
        return (fromPath ?: fromQuery ?: 1).coerceAtLeast(1)
    }

    private fun parseCount(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        return raw.replace(",", "").trim().toIntOrNull() ?: 0
    }
}
