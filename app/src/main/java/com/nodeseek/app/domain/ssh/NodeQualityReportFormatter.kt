package com.nodeseek.app.domain.ssh

import androidx.compose.runtime.Immutable

/**
 * Canonical section identifiers matching upstream `nodequality.com` (`xTermTabs` in
 * `LloydAsp/NodeQuality` web report viewer).
 */
enum class NodeQualitySectionKind(
    val tabTitle: String,
    val plainTitle: String,
) {
    BASIC_INFO(tabTitle = "💻基本信息", plainTitle = "💻基本信息"),
    IP_QUALITY(tabTitle = "🎬IP质量", plainTitle = "🎬IP质量"),
    NET_QUALITY(tabTitle = "🌐网络质量", plainTitle = "🌐网络质量"),
    BACKROUTE_TRACE(tabTitle = "📍回程路由", plainTitle = "📍回程路由"),
}

@Immutable
data class NodeQualityParsedSection(
    val kind: NodeQualitySectionKind,
    val content: String,
    val isMarkdownBlock: Boolean = false,
)

@Immutable
data class ParsedNodeQualityReport(
    val sections: List<NodeQualityParsedSection>,
    val shareUrl: String?,
    val fallbackBody: String,
) {
    val hasStructuredSections: Boolean
        get() = sections.isNotEmpty()
}

/**
 * Parses and formats `NodeQuality` (`bash <(curl -sL https://run.NodeQuality.com)`) SSH transcripts
 * to match the official `nodequality.com/r/<token>` 1-click export layouts:
 *
 * - **Official NodeSeek 1-click export (`exportToNs` / "复制为NodeSeek格式")**:
 *   ```markdown
 *   :::: tabs
 *   ::: tab-item 💻基本信息
 *   ```ansi
 *   ...
 *   ```
 *   :::
 *   ::: tab-item 🎬IP质量
 *   ```ansi
 *   ...
 *   ```
 *   :::
 *   ::: tab-item 🌐网络质量
 *   ```ansi
 *   ...
 *   ```
 *   :::
 *   ::: tab-item 📍回程路由
 *   ```ansi
 *   ...
 *   ```
 *   :::
 *   ::::
 *
 *   [NodeQuality链接](https://nodequality.com/r/<token>)
 *   ```
 * - **Official Plain-Text 1-click copy (`copyText` / "复制文本")**:
 *   Clean ANSI-stripped terminal sections followed by `[NodeQuality链接](https://nodequality.com/r/<token>)`.
 * - **Embedded Section Layout (for merging inside an outer multi-script `:::: tabs` post)**:
 *   Uses `### 💻基本信息`, `### 🎬IP质量`, `### 🌐网络质量`, `### 📍回程路由` headings + independent
 *   ```` ```ansi ```` blocks + `[NodeQuality链接](...)`, avoiding nested `:::: tabs` collisions.
 *
 * Performance guarantee:
 * - Processes multi-megabyte raw transcripts (with `\r` progress-bar storms and `\u001B[1A\u001B[2K`
 *   TrueColor sponsor banners) in a single linear pass with independent per-section bounded buffers.
 */
object NodeQualityReportFormatter {
    const val MAX_LINES_PER_SECTION: Int = 450
    const val MAX_CHARS_PER_SECTION: Int = 45_000
    private const val MAX_FALLBACK_LINES: Int = 600
    private const val MAX_FALLBACK_CHARS: Int = 64_000

    fun isNodeQualityRun(run: SshExecutionRecord): Boolean {
        if (run.presetId.equals(SshPresetCatalog.PRESET_NODE_QUALITY, ignoreCase = true)) return true
        if (run.command.contains("nodequality", ignoreCase = true)) return true
        if (run.presetName.contains("NodeQuality", ignoreCase = true)) return true
        if (run.tabTitle.contains("NodeQuality", ignoreCase = true)) return true
        return looksLikeNodeQualityOutput(run.sanitizedAnsiOutput)
    }

    fun looksLikeNodeQualityOutput(output: String): Boolean {
        if (output.isEmpty()) return false
        if (extractShareUrlFast(output) != null) return true
        if (output.contains("LloydAsp/NodeQuality", ignoreCase = true)) return true
        var stageHits = 0
        if (output.contains("正在运行硬件质量测试") || output.contains("Running Hardware Quality Test")) stageHits++
        if (output.contains("正在运行 IP 质量测试") || output.contains("Running IP Quality Test")) stageHits++
        if (output.contains("正在运行网络质量测试") || output.contains("Running Network Quality Test")) stageHits++
        if (output.contains("正在运行回程路由追踪") || output.contains("Running Backroute Trace")) stageHits++
        return stageHits >= 2
    }

    /**
     * Parses [rawOutput] in a single streaming pass into structured NodeQuality sections and share URL.
     */
    fun parse(
        rawOutput: String,
        maskIp: Boolean,
        knownServerHost: String? = null,
    ): ParsedNodeQualityReport {
        if (rawOutput.isBlank()) {
            return ParsedNodeQualityReport(
                sections = emptyList(),
                shareUrl = null,
                fallbackBody = "",
            )
        }

        val sectionSanitizer = AnsiStreamSanitizer(
            maxLines = MAX_LINES_PER_SECTION + 80,
            maxLineChars = 2_048,
            maxTotalChars = MAX_CHARS_PER_SECTION * 2,
        )
        val fallbackSanitizer = AnsiStreamSanitizer(
            maxLines = MAX_FALLBACK_LINES,
            maxLineChars = 2_048,
            maxTotalChars = MAX_FALLBACK_CHARS * 2,
        )

        val collectedByKind = LinkedHashMap<NodeQualitySectionKind, MutableList<String>>(4)
        var currentSection: NodeQualitySectionKind? = null
        var detectedShareUrl: String? = null
        var stoppedForCleanup = false
        var lastPendingBorderLine: String? = null

        fun flushSanitizerIntoSection(target: NodeQualitySectionKind?, excludeLastCommitted: Boolean = false) {
            val snap = sectionSanitizer.lineSnapshot()
            val committed = snap.completedLines
            val endIdx = if (excludeLastCommitted && committed.isNotEmpty()) committed.size - 1 else committed.size
            if (target != null && endIdx > 0) {
                val dest = collectedByKind.getOrPut(target) { ArrayList() }
                for (i in 0 until endIdx) {
                    if (dest.size < MAX_LINES_PER_SECTION + 80) {
                        dest.add(committed[i].ansiText)
                    }
                }
            }
            if (target != null && !excludeLastCommitted && snap.activeLine != null) {
                val dest = collectedByKind.getOrPut(target) { ArrayList() }
                if (dest.size < MAX_LINES_PER_SECTION + 80) {
                    dest.add(snap.activeLine.ansiText)
                }
            }
            sectionSanitizer.reset()
        }

        // Stream physical lines `\n` by `\n` so even 2MB of `\r` progress frames on one line stay O(1) in RAM
        var cursor = 0
        val len = rawOutput.length
        while (cursor < len) {
            val nlIdx = rawOutput.indexOf('\n', startIndex = cursor)
            val sliceEnd = if (nlIdx >= 0) nlIdx + 1 else len
            val rawLineSlice = rawOutput.substring(cursor, sliceEnd)
            cursor = sliceEnd

            fallbackSanitizer.append(rawLineSlice)
            sectionSanitizer.append(rawLineSlice)

            val snap = sectionSanitizer.lineSnapshot()
            val latestAnsiLine = if (nlIdx >= 0) {
                snap.completedLines.lastOrNull()?.ansiText
            } else {
                snap.activeLine?.ansiText ?: snap.completedLines.lastOrNull()?.ansiText
            } ?: continue

            val plainLine = stripAnsiSgrFast(latestAnsiLine).trim()
            if (plainLine.isEmpty()) continue

            extractShareUrlFromLine(plainLine)?.let { url ->
                detectedShareUrl = url
            }

            if (isPostCleanupLine(plainLine)) {
                if (!stoppedForCleanup) {
                    flushSanitizerIntoSection(currentSection, excludeLastCommitted = nlIdx >= 0)
                    stoppedForCleanup = true
                }
                sectionSanitizer.reset()
                continue
            }
            if (stoppedForCleanup) {
                sectionSanitizer.reset()
                continue
            }

            // Explicit `NodeQuality.sh` stage banners (`正在运行硬件质量测试...`, etc.)
            val explicitStage = detectExplicitStageMarker(plainLine)
            if (explicitStage != null) {
                flushSanitizerIntoSection(currentSection, excludeLastCommitted = nlIdx >= 0)
                currentSection = explicitStage
                lastPendingBorderLine = null
                continue
            }

            // Fallback detection when transcript starts directly with section headings or Check.Place headers
            val headerStage = detectReportHeaderSection(plainLine, currentSection)
            if (headerStage != null && headerStage != currentSection) {
                // Keep preceding `+++` / `###` / `***` top border line with the newly started report!
                val borderToCarry = if (nlIdx >= 0 && snap.completedLines.size >= 2) {
                    val prevAnsi = snap.completedLines[snap.completedLines.size - 2].ansiText
                    val prevPlain = stripAnsiSgrFast(prevAnsi).trim()
                    if (isReportTopBorderLine(prevPlain)) prevAnsi else null
                } else {
                    lastPendingBorderLine
                }

                if (currentSection != null) {
                    val snapLines = snap.completedLines
                    val cutCount = when {
                        nlIdx >= 0 && borderToCarry != null && snapLines.size >= 2 -> snapLines.size - 2
                        nlIdx >= 0 -> snapLines.size - 1
                        else -> snapLines.size
                    }
                    if (cutCount > 0) {
                        val dest = collectedByKind.getOrPut(currentSection) { ArrayList() }
                        for (i in 0 until cutCount) {
                            if (dest.size < MAX_LINES_PER_SECTION + 80) {
                                dest.add(snapLines[i].ansiText)
                            }
                        }
                    }
                }
                sectionSanitizer.reset()
                currentSection = headerStage
                if (borderToCarry != null) {
                    sectionSanitizer.append(borderToCarry + "\n")
                }
                sectionSanitizer.append(latestAnsiLine + if (nlIdx >= 0) "\n" else "")
                lastPendingBorderLine = null
                continue
            }

            lastPendingBorderLine = if (isReportTopBorderLine(plainLine)) latestAnsiLine else null
        }

        if (!stoppedForCleanup) {
            flushSanitizerIntoSection(currentSection, excludeLastCommitted = false)
        }

        val finalSections = ArrayList<NodeQualityParsedSection>(4)
        for (kind in NodeQualitySectionKind.entries) {
            val rawLines = collectedByKind[kind] ?: continue
            val cleaned = cleanSectionLines(
                kind = kind,
                lines = rawLines,
                shareUrl = detectedShareUrl,
                maskIp = maskIp,
                knownServerHost = knownServerHost,
            )
            if (cleaned != null && cleaned.content.isNotBlank()) {
                finalSections.add(cleaned)
            }
        }

        val fallbackText = if (finalSections.isEmpty()) {
            val rawFallback = fallbackSanitizer.snapshot()
            cleanFallbackOutput(
                sanitized = rawFallback,
                shareUrl = detectedShareUrl,
                maskIp = maskIp,
                knownServerHost = knownServerHost,
            )
        } else {
            ""
        }

        return ParsedNodeQualityReport(
            sections = finalSections,
            shareUrl = detectedShareUrl,
            fallbackBody = fallbackText,
        )
    }

    /**
     * Formats [run] using the official `nodequality.com` NodeSeek 1-click export structure (`:::: tabs`).
     */
    fun formatStandaloneNodeSeekPost(
        run: SshExecutionRecord,
        maskIp: Boolean = true,
    ): Pair<String, ParsedNodeQualityReport> {
        val parsed = parse(
            rawOutput = run.sanitizedAnsiOutput,
            maskIp = maskIp,
            knownServerHost = run.serverHost,
        )
        return renderStandaloneFromParsed(parsed) to parsed
    }

    internal fun renderStandaloneFromParsed(parsed: ParsedNodeQualityReport): String {
        if (!parsed.hasStructuredSections) {
            return buildString {
                if (parsed.fallbackBody.isNotBlank()) {
                    appendAnsiFence(parsed.fallbackBody)
                }
                if (!parsed.shareUrl.isNullOrBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append("[NodeQuality链接](").append(parsed.shareUrl).append(')')
                }
            }
        }

        return buildString {
            append(":::: tabs\n")
            for (section in parsed.sections) {
                append("::: tab-item ").append(section.kind.tabTitle).append('\n')
                if (section.isMarkdownBlock) {
                    append(section.content.trim())
                    append("\n\n:::\n")
                } else {
                    appendAnsiFence(section.content)
                    append("\n:::\n")
                }
            }
            append("::::")
            if (!parsed.shareUrl.isNullOrBlank()) {
                append("\n\n[NodeQuality链接](").append(parsed.shareUrl).append(')')
            }
        }
    }

    /**
     * Formats [run] for embedding inside an outer multi-script `::: tab-item` (using `###` section
     * headings so there is no nested `:::: tabs` collision).
     */
    fun formatForOuterTabItem(
        run: SshExecutionRecord,
        maskIp: Boolean = true,
    ): Pair<String, ParsedNodeQualityReport> {
        val parsed = parse(
            rawOutput = run.sanitizedAnsiOutput,
            maskIp = maskIp,
            knownServerHost = run.serverHost,
        )
        if (!parsed.hasStructuredSections) {
            val body = buildString {
                if (parsed.fallbackBody.isNotBlank()) {
                    appendAnsiFence(parsed.fallbackBody)
                }
                if (!parsed.shareUrl.isNullOrBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append("[NodeQuality链接](").append(parsed.shareUrl).append(')')
                }
            }
            return body to parsed
        }

        val md = buildString {
            parsed.sections.forEachIndexed { idx, section ->
                if (idx > 0) append("\n\n")
                append("### ").append(section.kind.tabTitle).append('\n')
                if (section.isMarkdownBlock) {
                    append(section.content.trim())
                } else {
                    appendAnsiFence(section.content)
                }
            }
            if (!parsed.shareUrl.isNullOrBlank()) {
                append("\n\n[NodeQuality链接](").append(parsed.shareUrl).append(')')
            }
        }
        return md to parsed
    }

    /**
     * Formats [run] as clean ANSI-stripped plain text matching `nodequality.com`'s `复制文本` button.
     */
    fun formatPlainTextCopy(
        run: SshExecutionRecord,
        maskIp: Boolean = true,
    ): String {
        val parsed = parse(
            rawOutput = run.sanitizedAnsiOutput,
            maskIp = maskIp,
            knownServerHost = run.serverHost,
        )
        return buildString {
            if (parsed.hasStructuredSections) {
                parsed.sections.forEachIndexed { idx, section ->
                    if (idx > 0) append("\n\n")
                    append(section.kind.plainTitle).append('\n')
                    append(stripAnsiSgrFast(section.content).trim())
                }
            } else if (parsed.fallbackBody.isNotBlank()) {
                append(stripAnsiSgrFast(parsed.fallbackBody).trim())
            }
            if (!parsed.shareUrl.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("[NodeQuality链接](").append(parsed.shareUrl).append(')')
            }
        }
    }

    private fun detectExplicitStageMarker(plainLine: String): NodeQualitySectionKind? {
        return when {
            plainLine.startsWith("正在运行硬件质量测试") ||
                plainLine.startsWith("Running Hardware Quality Test") ||
                plainLine.startsWith("Running Basic Info Test") ||
                plainLine == "💻基本信息" ||
                plainLine == "# 💻基本信息" ||
                plainLine == "## 💻基本信息" ||
                plainLine == "### 💻基本信息" ->
                NodeQualitySectionKind.BASIC_INFO

            plainLine.startsWith("正在运行 IP 质量测试") ||
                plainLine.startsWith("Running IP Quality Test") ||
                plainLine == "🎬IP质量" ||
                plainLine == "# 🎬IP质量" ||
                plainLine == "## 🎬IP质量" ||
                plainLine == "### 🎬IP质量" ->
                NodeQualitySectionKind.IP_QUALITY

            plainLine.startsWith("正在运行网络质量测试") ||
                plainLine.startsWith("Running Network Quality Test") ||
                plainLine == "🌐网络质量" ||
                plainLine == "# 🌐网络质量" ||
                plainLine == "## 🌐网络质量" ||
                plainLine == "### 🌐网络质量" ->
                NodeQualitySectionKind.NET_QUALITY

            plainLine.startsWith("正在运行回程路由追踪") ||
                plainLine.startsWith("Running Backroute Trace") ||
                plainLine == "📍回程路由" ||
                plainLine == "# 📍回程路由" ||
                plainLine == "## 📍回程路由" ||
                plainLine == "### 📍回程路由" ->
                NodeQualitySectionKind.BACKROUTE_TRACE

            else -> null
        }
    }

    private fun detectReportHeaderSection(
        plainLine: String,
        currentSection: NodeQualitySectionKind?,
    ): NodeQualitySectionKind? {
        return when {
            plainLine.contains("硬件质量体检报告") ||
                plainLine.contains("HARDWARE QUALITY CHECK REPORT") ||
                plainLine.startsWith("Basic System Information:") -> {
                if (currentSection == NodeQualitySectionKind.BASIC_INFO) null else NodeQualitySectionKind.BASIC_INFO
            }
            plainLine.contains("IP质量体检报告") ||
                plainLine.contains("IP QUALITY CHECK REPORT") -> {
                // Stay in IP_QUALITY across dual-stack IPv4 + IPv6 reports
                if (currentSection == NodeQualitySectionKind.IP_QUALITY) null else NodeQualitySectionKind.IP_QUALITY
            }
            plainLine.contains("网络质量体检报告") ||
                plainLine.contains("NET QUALITY CHECK REPORT") ||
                plainLine.contains("NETWORK QUALITY CHECK REPORT") -> {
                // Note: Net.Check.Place -R (Backroute Trace) uses the same show_head title;
                // never revert BACKROUTE_TRACE back to NET_QUALITY!
                if (currentSection == NodeQualitySectionKind.NET_QUALITY ||
                    currentSection == NodeQualitySectionKind.BACKROUTE_TRACE
                ) {
                    null
                } else {
                    NodeQualitySectionKind.NET_QUALITY
                }
            }
            else -> null
        }
    }

    private fun isReportTopBorderLine(plainLine: String): Boolean {
        if (plainLine.length < 32) return false
        val first = plainLine[0]
        if (first != '+' && first != '#' && first != '*') return false
        for (i in 1 until plainLine.length) {
            if (plainLine[i] != first) return false
        }
        return true
    }

    private fun isPostCleanupLine(plainLine: String): Boolean =
        plainLine == "安装后清理" ||
            plainLine == "Clean Up after Installation" ||
            plainLine == "清理中，请稍后。" ||
            plainLine == "Cleaning, please wait a moment."

    private fun isLeakedProgressOrNoiseLine(plainLine: String): Boolean {
        if (plainLine.isEmpty()) return false
        if (isPostCleanupLine(plainLine)) return true
        // Check.Place stderr spinner line: `[IP]# 正在获取... .... ⠋⠙ 42%`
        if (plainLine.startsWith("[") && plainLine.contains("]# ") && plainLine.endsWith("%")) {
            return true
        }
        // Raw share URL line at the end of Backroute Trace (already emitted in `[NodeQuality链接](...)`)
        val maybeUrl = extractShareUrlFromLine(plainLine)
        if (maybeUrl != null &&
            (plainLine == maybeUrl ||
                plainLine.startsWith("Report Link:", ignoreCase = true) && plainLine.contains("nodequality.com/r/", ignoreCase = true) ||
                plainLine.startsWith("NodeQuality", ignoreCase = true) && plainLine.contains("nodequality.com/r/", ignoreCase = true))
        ) {
            return true
        }
        return false
    }

    private fun cleanSectionLines(
        kind: NodeQualitySectionKind,
        lines: List<String>,
        shareUrl: String?,
        maskIp: Boolean,
        knownServerHost: String?,
    ): NodeQualityParsedSection? {
        if (lines.isEmpty()) return null

        // If Check.Place report header exists in this section, trim any pre-header stderr/ad/apt noise
        var startIdx = 0
        for (idx in lines.indices) {
            val plain = stripAnsiSgrFast(lines[idx]).trim()
            val matchesReportStart = when (kind) {
                NodeQualitySectionKind.BASIC_INFO ->
                    plain.contains("硬件质量体检报告") ||
                        plain.contains("HARDWARE QUALITY CHECK REPORT") ||
                        plain.startsWith("Basic System Information:")
                NodeQualitySectionKind.IP_QUALITY ->
                    plain.contains("IP质量体检报告") ||
                        plain.contains("IP QUALITY CHECK REPORT")
                NodeQualitySectionKind.NET_QUALITY,
                NodeQualitySectionKind.BACKROUTE_TRACE,
                ->
                    plain.contains("网络质量体检报告") ||
                        plain.contains("NET QUALITY CHECK REPORT") ||
                        plain.contains("NETWORK QUALITY CHECK REPORT")
            }
            if (matchesReportStart) {
                startIdx = if (idx > 0 && isReportTopBorderLine(stripAnsiSgrFast(lines[idx - 1]).trim())) {
                    idx - 1
                } else {
                    idx
                }
                break
            }
        }

        val kept = ArrayList<String>(lines.size - startIdx)
        var charCount = 0
        var truncatedLines = 0
        for (idx in startIdx until lines.size) {
            val rawLine = lines[idx]
            val plain = stripAnsiSgrFast(rawLine).trim()
            if (isLeakedProgressOrNoiseLine(plain)) continue
            if (shareUrl != null && plain == shareUrl) continue

            if (kept.size >= MAX_LINES_PER_SECTION || charCount + rawLine.length > MAX_CHARS_PER_SECTION) {
                truncatedLines++
                continue
            }
            kept.add(rawLine)
            charCount += rawLine.length + 1
        }

        // Trim leading and trailing blank lines
        while (kept.isNotEmpty() && stripAnsiSgrFast(kept.first()).isBlank()) {
            kept.removeAt(0)
        }
        while (kept.isNotEmpty() && stripAnsiSgrFast(kept.last()).isBlank()) {
            kept.removeAt(kept.lastIndex)
        }
        if (kept.isEmpty()) return null

        if (truncatedLines > 0) {
            kept.add("\u001B[2m... [本节其余 $truncatedLines 行已精简] ...\u001B[0m")
        }

        val singlePlain = if (kept.size == 1) stripAnsiSgrFast(kept[0]).trim() else null
        val isStandaloneMdImage = singlePlain != null &&
            (singlePlain.startsWith("![") || singlePlain.startsWith("[![")) &&
            singlePlain.endsWith(")")

        val joined = if (isStandaloneMdImage) singlePlain!! else kept.joinToString("\n")
        val masked = if (maskIp) {
            IpPrivacyMasker.mask(joined, knownServerHost = knownServerHost)
        } else {
            joined
        }
        return NodeQualityParsedSection(
            kind = kind,
            content = masked,
            isMarkdownBlock = isStandaloneMdImage,
        )
    }

    private fun cleanFallbackOutput(
        sanitized: String,
        shareUrl: String?,
        maskIp: Boolean,
        knownServerHost: String?,
    ): String {
        val rawLines = sanitized.trimEnd('\n', '\r').split('\n')
        val kept = ArrayList<String>(rawLines.size.coerceAtMost(MAX_FALLBACK_LINES))
        var chars = 0
        for (line in rawLines) {
            val plain = stripAnsiSgrFast(line).trim()
            if (isPostCleanupLine(plain)) continue
            if (shareUrl != null && plain == shareUrl) continue
            if (kept.size >= MAX_FALLBACK_LINES || chars + line.length > MAX_FALLBACK_CHARS) break
            kept.add(line)
            chars += line.length + 1
        }
        while (kept.isNotEmpty() && stripAnsiSgrFast(kept.first()).isBlank()) kept.removeAt(0)
        while (kept.isNotEmpty() && stripAnsiSgrFast(kept.last()).isBlank()) kept.removeAt(kept.lastIndex)
        val joined = kept.joinToString("\n")
        return if (maskIp) IpPrivacyMasker.mask(joined, knownServerHost = knownServerHost) else joined
    }

    internal fun extractShareUrlFast(text: String): String? {
        val needle = "nodequality.com/r/"
        val idx = text.indexOf(needle, ignoreCase = true)
        if (idx < 0) return null
        val tokenStart = idx + needle.length
        var tokenEnd = tokenStart
        while (tokenEnd < text.length) {
            val ch = text[tokenEnd]
            if (ch.isLetterOrDigit() || ch == '_' || ch == '-') {
                tokenEnd++
            } else {
                break
            }
        }
        if (tokenEnd <= tokenStart) return null
        val token = text.substring(tokenStart, tokenEnd)
        return "https://nodequality.com/r/$token"
    }

    private fun extractShareUrlFromLine(plainLine: String): String? =
        extractShareUrlFast(plainLine)

    internal fun stripAnsiSgrFast(text: String): String {
        val firstEsc = text.indexOf("\u001B[")
        if (firstEsc < 0) return text
        val sb = StringBuilder(text.length)
        var cursor = 0
        var searchFrom = firstEsc
        while (searchFrom >= 0 && searchFrom < text.length) {
            var i = searchFrom + 2
            var endIdx = -1
            while (i < text.length && i <= searchFrom + 66) {
                val ch = text[i]
                if (ch == 'm') {
                    endIdx = i
                    break
                }
                if (ch !in '0'..'9' && ch != ';' && ch != ':') break
                i++
            }
            if (endIdx >= 0) {
                if (searchFrom > cursor) sb.append(text, cursor, searchFrom)
                cursor = endIdx + 1
                searchFrom = text.indexOf("\u001B[", startIndex = cursor)
            } else {
                searchFrom = text.indexOf("\u001B[", startIndex = searchFrom + 2)
            }
        }
        if (cursor == 0) return text
        if (cursor < text.length) sb.append(text, cursor, text.length)
        return sb.toString()
    }

    private fun StringBuilder.appendAnsiFence(content: String) {
        val fence = SshBenchmarkPostFormatter.chooseSafeBacktickFence(content)
        append(fence).append("ansi\n")
        append(content)
        if (!content.endsWith('\n')) append('\n')
        append(fence)
    }
}
