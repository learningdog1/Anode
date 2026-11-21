package com.nodeseek.app.domain.ssh

import androidx.compose.runtime.Immutable

/**
 * Multi-run merge presentation style when combining 2+ SSH benchmark results into a single post.
 */
enum class SshMergeLayout(val label: String) {
    /** NodeSeek interactive `:::: tabs` / `::: tab-item <Title>` container (default). */
    TABS("多标签页 (:::: tabs)"),

    /** Independent top-level Markdown sections (`## <Title>`) separated by horizontal dividers. */
    SECTIONS("分段独立标题 (## 标题)"),
}

/**
 * Formatted NodeSeek review post payload generated from one or more SSH script runs.
 */
@Immutable
data class SshFormattedPost(
    val suggestedTitle: String,
    val markdownContent: String,
    val categorySlug: String = SshBenchmarkPostFormatter.REVIEW_CATEGORY_SLUG,
    val runCount: Int,
    val usesTabsLayout: Boolean,
    val ipMasked: Boolean,
    val mergeLayout: SshMergeLayout = SshMergeLayout.TABS,
    val plainTextCopy: String? = null,
    val nodeQualityShareUrl: String? = null,
)

/**
 * Converts completed SSH benchmark runs into NodeSeek-native Markdown:
 * - **Single NodeQuality (NQ) run**: matches official `nodequality.com/r/<token>` 1-click NodeSeek
 *   export (`:::: tabs` with `💻基本信息` / `🎬IP质量` / `🌐网络质量` / `📍回程路由` + `[NodeQuality链接]`),
 *   plus plain-text copy (`复制文本`) support.
 * - **Single non-NQ run**: sanitized, bounded, IP-masked fenced ```` ```ansi ```` code block.
 * - **Multiple runs (e.g. NQ + YABS + 融合怪 + NextTrace)**: merges all selected runs into one
 *   cohesive review post with clear independent per-script titles (either interactive `:::: tabs`
 *   with `### 💻基本信息` sub-sections for NQ to avoid nested tabs, or top-level `## <Title>`
 *   sections where NQ retains its full `:::: tabs`), while enforcing [IpPrivacyMasker] across all
 *   runs and bounding output size so preview & composer never stall on multi-MB logs.
 */
object SshBenchmarkPostFormatter {
    const val REVIEW_CATEGORY_SLUG: String = "review"
    const val MAX_LINES_PER_RUN: Int = 1_000
    const val MAX_CHARS_PER_RUN: Int = 80_000

    /**
     * Formats [runs] into ready-to-publish Markdown and a suggested `review` post title.
     */
    @JvmOverloads
    fun format(
        runs: List<SshExecutionRecord>,
        maskIp: Boolean = true,
        includeSummaryHeader: Boolean = false,
        serverAliasOverride: String? = null,
        mergeLayout: SshMergeLayout = SshMergeLayout.TABS,
    ): SshFormattedPost {
        val validRuns = runs.filter { it.sanitizedAnsiOutput.isNotBlank() }
        if (validRuns.isEmpty()) {
            return SshFormattedPost(
                suggestedTitle = "",
                markdownContent = "",
                runCount = 0,
                usesTabsLayout = false,
                ipMasked = maskIp,
                mergeLayout = mergeLayout,
            )
        }

        val serverAlias = serverAliasOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: validRuns.first().serverAlias.trim().ifBlank { "VPS" }
        val safeAlias = if (maskIp) IpPrivacyMasker.mask(serverAlias, validRuns.first().serverHost) else serverAlias
        val suggestedTitle = suggestPostTitle(serverAlias = safeAlias, runs = validRuns)

        if (validRuns.size == 1) {
            val run = validRuns.single()
            if (NodeQualityReportFormatter.isNodeQualityRun(run)) {
                val (nqMarkdown, parsedNq) = NodeQualityReportFormatter.formatStandaloneNodeSeekPost(
                    run = run,
                    maskIp = maskIp,
                )
                val fullMarkdown = buildString {
                    if (includeSummaryHeader) {
                        append("### ").append(safeAlias).append(" 实测报告\n\n")
                    }
                    append(nqMarkdown)
                }
                val plainCopy = NodeQualityReportFormatter.formatPlainTextCopy(
                    run = run,
                    maskIp = maskIp,
                )
                return SshFormattedPost(
                    suggestedTitle = suggestedTitle,
                    markdownContent = fullMarkdown,
                    categorySlug = REVIEW_CATEGORY_SLUG,
                    runCount = 1,
                    usesTabsLayout = parsedNq.hasStructuredSections,
                    ipMasked = maskIp,
                    mergeLayout = mergeLayout,
                    plainTextCopy = plainCopy,
                    nodeQualityShareUrl = parsedNq.shareUrl,
                )
            }

            val body = prepareStandardRunOutput(run, maskIp)
            val markdown = buildString {
                if (includeSummaryHeader) {
                    append("### ").append(safeAlias).append(" 实测报告\n\n")
                }
                appendAnsiCodeFence(body)
            }
            return SshFormattedPost(
                suggestedTitle = suggestedTitle,
                markdownContent = markdown,
                categorySlug = REVIEW_CATEGORY_SLUG,
                runCount = 1,
                usesTabsLayout = false,
                ipMasked = maskIp,
                mergeLayout = mergeLayout,
            )
        }

        // Multiple runs merged into a single post
        val distinctServers = validRuns
            .map { it.serverAlias.trim().ifBlank { it.serverHost.trim() } }
            .filter { it.isNotEmpty() }
            .distinct()
        val includeServerPrefixInSectionTitle = distinctServers.size > 1
        val uniqueTitles = buildUniqueRunTitles(
            runs = validRuns,
            includeServerPrefix = includeServerPrefixInSectionTitle,
            maskIp = maskIp,
        )

        var firstShareUrl: String? = null
        val markdown = buildString {
            if (includeSummaryHeader) {
                append("### ").append(safeAlias).append(" 实测报告\n\n")
            }

            when (mergeLayout) {
                SshMergeLayout.TABS -> {
                    append(":::: tabs\n")
                    validRuns.forEachIndexed { index, run ->
                        val title = uniqueTitles[index]
                        append("::: tab-item ").append(title).append('\n')
                        if (NodeQualityReportFormatter.isNodeQualityRun(run)) {
                            val (nqTabBody, parsedNq) = NodeQualityReportFormatter.formatForOuterTabItem(
                                run = run,
                                maskIp = maskIp,
                            )
                            if (firstShareUrl == null) firstShareUrl = parsedNq.shareUrl
                            append(nqTabBody)
                            append("\n:::\n")
                        } else {
                            appendAnsiCodeFence(prepareStandardRunOutput(run, maskIp))
                            append("\n:::\n")
                        }
                    }
                    append("::::")
                }

                SshMergeLayout.SECTIONS -> {
                    validRuns.forEachIndexed { index, run ->
                        if (index > 0) {
                            append("\n\n---\n\n")
                        }
                        val title = uniqueTitles[index]
                        append("## ").append(title).append("\n\n")
                        if (NodeQualityReportFormatter.isNodeQualityRun(run)) {
                            val (nqSectionBody, parsedNq) = NodeQualityReportFormatter.formatStandaloneNodeSeekPost(
                                run = run,
                                maskIp = maskIp,
                            )
                            if (firstShareUrl == null) firstShareUrl = parsedNq.shareUrl
                            append(nqSectionBody)
                        } else {
                            appendAnsiCodeFence(prepareStandardRunOutput(run, maskIp))
                        }
                    }
                }
            }
        }

        return SshFormattedPost(
            suggestedTitle = suggestedTitle,
            markdownContent = markdown,
            categorySlug = REVIEW_CATEGORY_SLUG,
            runCount = validRuns.size,
            usesTabsLayout = mergeLayout == SshMergeLayout.TABS || validRuns.any(NodeQualityReportFormatter::isNodeQualityRun),
            ipMasked = maskIp,
            mergeLayout = mergeLayout,
            nodeQualityShareUrl = firstShareUrl,
        )
    }

    /**
     * Generates a concise Chinese `review` (测评) title summarizing the server and executed scripts.
     */
    fun suggestPostTitle(
        serverAlias: String,
        runs: List<SshExecutionRecord>,
    ): String {
        val alias = serverAlias.trim().ifBlank { "VPS" }
        val distinctNames = runs
            .map { it.presetName.trim().ifBlank { it.tabTitle.trim() } }
            .filter { it.isNotEmpty() }
            .distinct()
        return if (distinctNames.isEmpty()) {
            "[测评] $alias 实测记录"
        } else {
            val summary = distinctNames.take(3).joinToString(" / ") +
                if (distinctNames.size > 3) " 等 ${distinctNames.size} 项" else ""
            "[测评] $alias 实测（$summary）"
        }
    }

    private fun buildUniqueRunTitles(
        runs: List<SshExecutionRecord>,
        includeServerPrefix: Boolean,
        maskIp: Boolean,
    ): List<String> {
        val usedTitles = mutableMapOf<String, Int>()
        return runs.mapIndexed { index, run ->
            val rawBase = run.tabTitle.trim()
                .ifBlank { run.presetName.trim() }
                .ifBlank { "测试 ${index + 1}" }
                .replace('\n', ' ')
                .replace('\r', ' ')
            val prefixed = if (includeServerPrefix) {
                val rawSrv = run.serverAlias.trim().ifBlank { "VPS" }
                    .replace('\n', ' ')
                    .replace('\r', ' ')
                val safeSrv = if (maskIp) IpPrivacyMasker.mask(rawSrv, run.serverHost) else rawSrv
                "$safeSrv · $rawBase"
            } else {
                if (maskIp) IpPrivacyMasker.mask(rawBase, run.serverHost) else rawBase
            }
            val seenCount = (usedTitles[prefixed] ?: 0) + 1
            usedTitles[prefixed] = seenCount
            if (seenCount == 1) prefixed else "$prefixed #$seenCount"
        }
    }

    private fun prepareStandardRunOutput(run: SshExecutionRecord, maskIp: Boolean): String {
        val raw = run.sanitizedAnsiOutput
        val sanitizedSnap = SanitizedBufferSnapshot.fromSanitizedText(
            sanitizedText = raw,
            maxLines = MAX_LINES_PER_RUN,
            maxLineChars = AnsiStreamSanitizer.DEFAULT_MAX_LINE_CHARS,
        )
        var bounded = sanitizedSnap.fullAnsiText.trimEnd('\n', '\r')
        if (bounded.length > MAX_CHARS_PER_RUN) {
            // Keep the tail where benchmark summaries, scores, and route conclusions live
            val tailSlice = bounded.substring(bounded.length - MAX_CHARS_PER_RUN)
            val firstNl = tailSlice.indexOf('\n')
            val cleanTail = if (firstNl in 0 until tailSlice.length - 1) {
                tailSlice.substring(firstNl + 1)
            } else {
                tailSlice
            }
            bounded = "\u001B[2m... [前序终端日志已精简] ...\u001B[0m\n$cleanTail"
        }
        return if (maskIp) {
            IpPrivacyMasker.mask(bounded, knownServerHost = run.serverHost)
        } else {
            bounded
        }
    }

    private fun StringBuilder.appendAnsiCodeFence(content: String) {
        val fence = chooseSafeBacktickFence(content)
        append(fence).append("ansi\n")
        append(content)
        if (!content.endsWith('\n')) {
            append('\n')
        }
        append(fence)
    }

    internal fun chooseSafeBacktickFence(content: String): String {
        var maxRun = 0
        var currentRun = 0
        for (ch in content) {
            if (ch == '`') {
                currentRun++
                if (currentRun > maxRun) maxRun = currentRun
            } else {
                currentRun = 0
            }
        }
        val length = (maxRun + 1).coerceAtLeast(3)
        return "`".repeat(length)
    }
}
