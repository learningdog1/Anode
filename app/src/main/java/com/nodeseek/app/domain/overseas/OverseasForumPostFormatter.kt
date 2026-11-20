package com.nodeseek.app.domain.overseas

/**
 * Generates a NodeSeek-ready forum post draft (`一键搬运发帖`) directly from [OverseasRepostContent]
 * returned by `GET /api/v1/offers/{id}/repost` (main OP post information only, excluding comments).
 */
object OverseasForumPostFormatter {

    fun format(
        repost: OverseasRepostContent,
        fallbackOffer: OverseasDealOffer? = null,
    ): SuggestedForumPost {
        val preferredHeadline = repost.titleCn.trim()
            .ifBlank { fallbackOffer?.title?.trim().orEmpty() }
            .ifBlank { repost.title.trim() }

        val suggestedTitle = "[海外情报/LET] ${preferredHeadline.take(86)}".trim()

        val md = StringBuilder()
        val originalTitle = repost.title.trim()
            .ifBlank { fallbackOffer?.originalTitle?.trim().orEmpty() }
        val merchantOrAuthor = repost.merchant.trim()
            .ifBlank { repost.author.trim() }
            .ifBlank { fallbackOffer?.merchant?.trim().orEmpty() }
        val sourceUrl = repost.sourceLink.trim()
            .ifBlank { fallbackOffer?.sourceLink?.trim().orEmpty() }

        if (originalTitle.isNotBlank() && !originalTitle.equals(preferredHeadline, ignoreCase = true)) {
            md.appendLine("> **原帖标题**：$originalTitle")
        }
        if (merchantOrAuthor.isNotBlank()) {
            md.appendLine("> **商家 / 作者**：$merchantOrAuthor")
        }
        if (repost.publishedAtIso.isNotBlank()) {
            md.appendLine("> **发布时间**：${repost.publishedAtIso.trim()}")
        }
        if (md.isNotEmpty()) {
            md.appendLine()
        }

        val mainBody = resolveMainPostMarkdown(repost)
        if (mainBody.isNotBlank()) {
            md.appendLine(mainBody)
            md.appendLine()
        }

        // Append any OP images or links not already referenced inside bodyMarkdown
        val missingImages = repost.images.filter { img ->
            img.url.isNotBlank() && !mainBody.contains(img.url)
        }
        if (missingImages.isNotEmpty()) {
            missingImages.forEach { img ->
                val alt = img.alt.trim().ifBlank { "促销配图" }
                md.appendLine("![$alt](${img.url.trim()})")
            }
            md.appendLine()
        }

        val missingLinks = repost.links.filter { link ->
            link.url.isNotBlank() && !mainBody.contains(link.url) && link.url != sourceUrl
        }
        if (missingLinks.isNotEmpty()) {
            md.appendLine("### 相关链接")
            md.appendLine()
            missingLinks.forEachIndexed { idx, link ->
                val label = link.text.trim().ifBlank { "链接 ${idx + 1}" }
                md.appendLine("- [$label](${link.url.trim()})")
            }
            md.appendLine()
        }

        md.appendLine("---")
        if (sourceUrl.isNotBlank()) {
            md.appendLine("**来源致谢**：情报搬运自 [LowEndTalk 原帖]($sourceUrl) 主楼发布内容（不含评论），最终条款归原帖作者及商家所有。")
        } else {
            md.appendLine("**来源致谢**：情报搬运自 LowEndTalk 原帖主楼发布内容（不含评论），最终条款归原帖作者及商家所有。")
        }

        return SuggestedForumPost(
            title = suggestedTitle,
            markdownBody = md.toString().trimEnd(),
            suggestedCategorySlug = repost.suggestedCategorySlug.ifBlank { "info" },
        )
    }

    private fun resolveMainPostMarkdown(repost: OverseasRepostContent): String {
        val markdown = repost.bodyMarkdown.trim()
        if (markdown.isNotBlank()) return markdown

        val plain = repost.bodyText.trim()
        if (plain.isNotBlank()) return plain

        return repost.bodyHtml
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</p>""", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("""<[^>]+>"""), "")
            .trim()
    }
}
