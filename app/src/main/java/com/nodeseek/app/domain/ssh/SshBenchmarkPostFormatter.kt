package com.nodeseek.app.domain.ssh

import androidx.compose.runtime.Immutable

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
)

/**
 * Converts completed SSH benchmark runs into NodeSeek-native Markdown:
 * - **Single run**: fenced ```` ```ansi ```` code block compatible with `AnsiAndCodeFormatter`.
 * - **Multiple runs**: NodeSeek / Anode native interactive `:::: tabs` / `::: tab-item <Title>`
 *   benchmark report tabs so readers can switch cleanly between YABS, NodeQuality, NextTrace, etc.
 */
object SshBenchmarkPostFormatter {
    const val REVIEW_CATEGORY_SLUG: String = "review"

    /**
     * Formats [runs] into ready-to-publish Markdown and a suggested `review` post title.
     */
    fun format(
        runs: List<SshExecutionRecord>,
        maskIp: Boolean = true,
        includeSummaryHeader: Boolean = false,
        serverAliasOverride: String? = null,
    ): SshFormattedPost {
        val validRuns = runs.filter { it.sanitizedAnsiOutput.isNotBlank() }
        if (validRuns.isEmpty()) {
            return SshFormattedPost(
                suggestedTitle = "",
                markdownContent = "",
                runCount = 0,
                usesTabsLayout = false,
                ipMasked = maskIp,
            )
        }

        val serverAlias = serverAliasOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: validRuns.first().serverAlias.trim().ifBlank { "VPS" }
        val safeAlias = if (maskIp) IpPrivacyMasker.mask(serverAlias, validRuns.first().serverHost) else serverAlias
        val suggestedTitle = suggestPostTitle(serverAlias = safeAlias, runs = validRuns)

        val markdown = buildString {
            if (includeSummaryHeader) {
                append("### ").append(safeAlias).append(" 实测报告\n\n")
            }

            if (validRuns.size == 1) {
                val run = validRuns.single()
                val body = prepareOutput(run, maskIp)
                appendAnsiCodeFence(body)
            } else {
                append(":::: tabs\n")
                val usedTitles = mutableMapOf<String, Int>()
                validRuns.forEachIndexed { index, run ->
                    val baseTitle = run.tabTitle.trim()
                        .ifBlank { run.presetName.trim() }
                        .ifBlank { "测试 ${index + 1}" }
                        .replace('\n', ' ')
                        .replace('\r', ' ')
                    val seenCount = (usedTitles[baseTitle] ?: 0) + 1
                    usedTitles[baseTitle] = seenCount
                    val uniqueTitle = if (seenCount == 1) baseTitle else "$baseTitle #$seenCount"

                    append("::: tab-item ").append(uniqueTitle).append('\n')
                    appendAnsiCodeFence(prepareOutput(run, maskIp))
                    append("\n:::\n")
                }
                append("::::")
            }
        }

        return SshFormattedPost(
            suggestedTitle = suggestedTitle,
            markdownContent = markdown,
            categorySlug = REVIEW_CATEGORY_SLUG,
            runCount = validRuns.size,
            usesTabsLayout = validRuns.size >= 2,
            ipMasked = maskIp,
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

    private fun prepareOutput(run: SshExecutionRecord, maskIp: Boolean): String {
        val trimmed = run.sanitizedAnsiOutput.trimEnd('\n', '\r')
        return if (maskIp) {
            IpPrivacyMasker.mask(trimmed, knownServerHost = run.serverHost)
        } else {
            trimmed
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

    private fun chooseSafeBacktickFence(content: String): String {
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
