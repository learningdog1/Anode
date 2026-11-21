package com.nodeseek.app.domain.overseas

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Generates a high-readability, NodeSeek-ready forum post draft (`一键搬贴`) from [OverseasRepostContent]
 * returned by `GET /api/v1/offers/{id}/repost` (main OP post information only, excluding comments).
 */
object OverseasForumPostFormatter {

    private val REDUNDANT_TITLE_PREFIX_REGEX = Regex(
        """^(?:\s*(?:\[(?:海外情报(?:/LET)?|LET|LEB|LowEndTalk)\]|【(?:海外情报|LET)】)\s*)+""",
        RegexOption.IGNORE_CASE,
    )

    private val BARE_COUPON_HIGHLIGHT_REGEX = Regex(
        """(?i)((?:promo(?:tion)?\s*code|coupon(?:\s*code)?|discount\s*code|voucher(?:\s*code)?|use\s+code|优惠码|折扣码)\s*[:：=]\s*)(?!`)([A-Z0-9][A-Z0-9_\-]{2,28})\b""",
    )

    private val COUPON_FALSE_POSITIVES = setOf(
        "NONE", "AUTO", "FREE", "NULL", "NA", "NO", "YES", "HERE", "BELOW", "ABOVE",
    )

    private val UTC_DISPLAY_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

    fun format(
        repost: OverseasRepostContent,
        fallbackOffer: OverseasDealOffer? = null,
        translateOfflineIfNeeded: Boolean = true,
    ): SuggestedForumPost {
        val rawOriginalTitle = decodeHtmlEntities(
            repost.title.trim().ifBlank { fallbackOffer?.originalTitle?.trim().orEmpty() },
        ).replace(Regex("""\s{2,}"""), " ").trim()

        val resolvedHeadline = resolveChineseHeadline(
            repost = repost,
            fallbackOffer = fallbackOffer,
            rawOriginalTitle = rawOriginalTitle,
            translateOfflineIfNeeded = translateOfflineIfNeeded,
        )

        val strippedHeadline = resolvedHeadline
            .replace(REDUNDANT_TITLE_PREFIX_REGEX, "")
            .trim()
            .ifBlank { "海外 VPS 促销情报" }

        val suggestedTitle = "[海外情报] ${strippedHeadline.take(86).trim()}"

        val merchant = decodeHtmlEntities(
            repost.merchant.trim().ifBlank { fallbackOffer?.merchant?.trim().orEmpty() },
        )
        val author = decodeHtmlEntities(repost.author.trim())
        val merchantAuthorLabel = when {
            merchant.isNotBlank() && author.isNotBlank() && !merchant.equals(author, ignoreCase = true) ->
                "$merchant（@$author）"
            merchant.isNotBlank() -> merchant
            author.isNotBlank() -> "@$author"
            else -> ""
        }

        val sourceUrl = repost.sourceLink.trim()
            .ifBlank { fallbackOffer?.sourceLink?.trim().orEmpty() }

        val formattedTime = formatPublishTime(
            epochMillis = repost.publishedAtEpochMillis.takeIf { it > 0L }
                ?: fallbackOffer?.publishedAtEpochMillis ?: 0L,
            isoString = repost.publishedAtIso.ifBlank { fallbackOffer?.publishedAtIso.orEmpty() },
        )

        val md = StringBuilder()

        // 1. Top Briefing Card (Markdown blockquote)
        val headerLines = ArrayList<String>(4)
        if (merchantAuthorLabel.isNotBlank()) {
            headerLines += "> **商家 / 作者**：$merchantAuthorLabel"
        }
        if (formattedTime.isNotBlank()) {
            headerLines += "> **发布时间**：$formattedTime"
        }
        if (rawOriginalTitle.isNotBlank() && !rawOriginalTitle.equals(strippedHeadline, ignoreCase = true)) {
            headerLines += "> **原帖标题**：$rawOriginalTitle"
        }
        if (sourceUrl.isNotBlank()) {
            headerLines += "> **直达原帖**：[LowEndTalk 原帖主楼]($sourceUrl)"
        }
        if (headerLines.isNotEmpty()) {
            headerLines.forEach { md.appendLine(it) }
            md.appendLine()
        }

        // 2. Structured Chinese OP Main Body
        var mainBody = cleanAndNormalizeMarkdown(extractRawMainBody(repost))
        if (translateOfflineIfNeeded &&
            mainBody.isNotBlank() &&
            !VpsGlossaryTranslator.containsCjk(mainBody) &&
            VpsGlossaryTranslator.containsAsciiWords(mainBody)
        ) {
            mainBody = cleanAndNormalizeMarkdown(VpsGlossaryTranslator.translateMarkdownOffline(mainBody))
        }

        if (mainBody.isNotBlank()) {
            md.appendLine(mainBody)
            md.appendLine()
        }

        // 3. Unembedded Links & Images neatly categorized
        val missingLinks = repost.links.filter { link ->
            val url = link.url.trim()
            url.isNotBlank() &&
                !url.equals(sourceUrl, ignoreCase = true) &&
                !containsUrl(mainBody, url)
        }.distinctBy { it.url.trim() }

        if (missingLinks.isNotEmpty()) {
            md.appendLine("### 相关链接")
            md.appendLine()
            missingLinks.forEachIndexed { idx, link ->
                val rawLabel = decodeHtmlEntities(link.text.trim())
                val label = when {
                    rawLabel.isBlank() -> "直达链接 ${idx + 1}"
                    translateOfflineIfNeeded && !VpsGlossaryTranslator.containsCjk(rawLabel) &&
                        !rawLabel.startsWith("http", ignoreCase = true) ->
                        VpsGlossaryTranslator.translateTitleOffline(rawLabel).ifBlank { rawLabel }
                    else -> rawLabel
                }
                md.appendLine("- [$label](${link.url.trim()})")
            }
            md.appendLine()
        }

        val missingImages = repost.images.filter { img ->
            val url = img.url.trim()
            url.isNotBlank() && !containsUrl(mainBody, url)
        }.distinctBy { it.url.trim() }

        if (missingImages.isNotEmpty()) {
            md.appendLine("### 活动配图")
            md.appendLine()
            missingImages.forEach { img ->
                val alt = decodeHtmlEntities(img.alt.trim()).ifBlank { "活动配图" }
                md.appendLine("![$alt](${img.url.trim()})")
                md.appendLine()
            }
        }

        // 4. Clean Source Attribution Footer
        md.appendLine("---")
        if (sourceUrl.isNotBlank()) {
            md.appendLine(
                "*来源说明：本文整理译自 [LowEndTalk 原帖]($sourceUrl) 主楼发布内容（不含评论），具体库存、配置与服务条款以商家及原帖为准。*",
            )
        } else {
            md.appendLine(
                "*来源说明：本文整理译自 LowEndTalk 原帖主楼发布内容（不含评论），具体库存、配置与服务条款以商家及原帖为准。*",
            )
        }

        return SuggestedForumPost(
            title = suggestedTitle,
            markdownBody = md.toString().trimEnd(),
            suggestedCategorySlug = repost.suggestedCategorySlug.ifBlank { "info" },
        )
    }

    fun extractRawMainBody(repost: OverseasRepostContent): String {
        val markdown = repost.bodyMarkdown.trim()
        if (markdown.isNotBlank()) return markdown

        val plain = repost.bodyText.trim()
        if (plain.isNotBlank()) return plain

        if (repost.bodyHtml.isBlank()) return ""
        return convertHtmlToMarkdown(repost.bodyHtml)
    }

    internal fun cleanAndNormalizeMarkdown(rawMarkdown: String): String {
        if (rawMarkdown.isBlank()) return ""

        val normalizedNewlines = rawMarkdown
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val rawLines = normalizedNewlines.lines()
        val processedLines = ArrayList<String>(rawLines.size + 8)
        var insideFence = false

        for (line in rawLines) {
            val trimmedStart = line.trimStart()
            if (trimmedStart.startsWith("```") || trimmedStart.startsWith("~~~")) {
                if (!insideFence && processedLines.isNotEmpty() && processedLines.last().isNotBlank()) {
                    processedLines += ""
                }
                insideFence = !insideFence
                processedLines += line.trimEnd()
                continue
            }

            if (insideFence) {
                processedLines += line.trimEnd()
                continue
            }

            var cleanedLine = decodeHtmlEntities(line)
                .replace('\u00A0', ' ')
                .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                .trimEnd()

            cleanedLine = highlightPromoCodesOnLine(cleanedLine)

            // Ensure a blank line precedes Markdown headings for clean forum rendering
            if (cleanedLine.trimStart().startsWith("#") &&
                processedLines.isNotEmpty() &&
                processedLines.last().isNotBlank()
            ) {
                processedLines += ""
            }

            processedLines += cleanedLine
        }

        return processedLines
            .joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    internal fun highlightPromoCodesOnLine(line: String): String {
        if (line.isBlank() || line.contains('`')) return line
        return BARE_COUPON_HIGHLIGHT_REGEX.replace(line) { match ->
            val prefix = match.groupValues[1]
            val code = match.groupValues[2]
            if (code.uppercase() in COUPON_FALSE_POSITIVES || code.none { it.isLetterOrDigit() }) {
                match.value
            } else {
                "$prefix`$code`"
            }
        }
    }

    internal fun decodeHtmlEntities(input: String): String {
        if (input.isEmpty() || !input.contains('&')) return input
        var text = input
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'")
            .replace("&apos;", "'", ignoreCase = true)
            .replace("&ndash;", "–", ignoreCase = true)
            .replace("&mdash;", "—", ignoreCase = true)
            .replace("&euro;", "€", ignoreCase = true)
            .replace("&pound;", "£", ignoreCase = true)
            .replace("&copy;", "©", ignoreCase = true)

        text = Regex("""&#(\d{1,6});""").replace(text) { m ->
            val codePoint = m.groupValues[1].toIntOrNull()
            if (codePoint != null && codePoint in 32..0x10FFFF) {
                String(Character.toChars(codePoint))
            } else {
                m.value
            }
        }
        text = Regex("""&#x([0-9a-fA-F]{1,6});""").replace(text) { m ->
            val codePoint = m.groupValues[1].toIntOrNull(16)
            if (codePoint != null && codePoint in 32..0x10FFFF) {
                String(Character.toChars(codePoint))
            } else {
                m.value
            }
        }
        return text
    }

    private fun convertHtmlToMarkdown(html: String): String {
        val converted = html
            .replace(Regex("""<pre[^>]*>\s*<code[^>]*>([\s\S]*?)</code>\s*</pre>""", RegexOption.IGNORE_CASE)) { m ->
                "\n```\n${decodeHtmlEntities(m.groupValues[1]).trim()}\n```\n"
            }
            .replace(Regex("""<img[^>]*src=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)) { m ->
                val alt = Regex("""alt=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
                    .find(m.value)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                "\n![${alt.ifBlank { "配图" }}](${m.groupValues[1].trim()})\n"
            }
            .replace(Regex("""<a\s+[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)) { m ->
                val label = m.groupValues[2].replace(Regex("""<[^>]+>"""), "").trim()
                val url = m.groupValues[1].trim()
                "[${label.ifBlank { url }}]($url)"
            }
            .replace(Regex("""<h([1-4])[^>]*>([\s\S]*?)</h\1>""", RegexOption.IGNORE_CASE)) { m ->
                val hashes = "#".repeat((m.groupValues[1].toIntOrNull() ?: 3).coerceIn(2, 4))
                val heading = m.groupValues[2].replace(Regex("""<[^>]+>"""), "").trim()
                "\n\n$hashes $heading\n\n"
            }
            .replace(Regex("""<(?:b|strong)[^>]*>(.*?)</(?:b|strong)>""", RegexOption.IGNORE_CASE), "**$1**")
            .replace(Regex("""<(?:i|em)[^>]*>(.*?)</(?:i|em)>""", RegexOption.IGNORE_CASE), "*$1*")
            .replace(Regex("""<code[^>]*>(.*?)</code>""", RegexOption.IGNORE_CASE), "`$1`")
            .replace(Regex("""<li[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE)) { m ->
                "\n- ${m.groupValues[1].replace(Regex("""<[^>]+>"""), "").trim()}"
            }
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</p>""", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("""<[^>]+>"""), "")
        return decodeHtmlEntities(converted).trim()
    }

    private fun resolveChineseHeadline(
        repost: OverseasRepostContent,
        fallbackOffer: OverseasDealOffer?,
        rawOriginalTitle: String,
        translateOfflineIfNeeded: Boolean,
    ): String {
        val titleCn = decodeHtmlEntities(repost.titleCn.trim())
        if (titleCn.isNotBlank() && VpsGlossaryTranslator.containsCjk(titleCn)) {
            return titleCn
        }

        val fallbackTitle = decodeHtmlEntities(fallbackOffer?.title?.trim().orEmpty())
        if (fallbackTitle.isNotBlank() && VpsGlossaryTranslator.containsCjk(fallbackTitle)) {
            return fallbackTitle
        }

        val candidate = titleCn
            .ifBlank { rawOriginalTitle }
            .ifBlank { fallbackTitle }
        if (candidate.isBlank()) return ""

        return if (translateOfflineIfNeeded && !VpsGlossaryTranslator.containsCjk(candidate)) {
            VpsGlossaryTranslator.translateTitleOffline(candidate).ifBlank { candidate }
        } else {
            candidate
        }
    }

    private fun formatPublishTime(epochMillis: Long, isoString: String): String {
        val trimmedIso = isoString.trim()
        if (epochMillis > 10_000_000_000L) {
            return UTC_DISPLAY_FORMATTER.format(Instant.ofEpochMilli(epochMillis))
        }
        if (trimmedIso.isEmpty()) return ""
        val parsed = runCatching { Instant.parse(trimmedIso) }
            .recoverCatching { OffsetDateTime.parse(trimmedIso).toInstant() }
            .getOrNull()
        return if (parsed != null) {
            UTC_DISPLAY_FORMATTER.format(parsed)
        } else {
            trimmedIso
        }
    }

    private fun containsUrl(markdown: String, url: String): Boolean {
        if (markdown.contains(url, ignoreCase = true)) return true
        val strippedSlash = url.trimEnd('/')
        return strippedSlash.isNotEmpty() && markdown.contains(strippedSlash, ignoreCase = true)
    }
}
