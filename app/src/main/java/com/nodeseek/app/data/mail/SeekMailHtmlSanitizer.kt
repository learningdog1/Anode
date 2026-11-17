package com.nodeseek.app.data.mail

import com.nodeseek.app.domain.mail.SeekMailAuthTelemetry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.util.Locale

/**
 * HTML sanitizer, themed document wrapper, plain-text fallback extractor, and auth telemetry parser
 * for Seek Mail (`seek.li`) messages.
 */
object SeekMailHtmlSanitizer {

    data class SanitizedEmailHtml(
        val bodyFragmentHtml: String,
        val hasRemoteImages: Boolean,
    )

    private val DANGEROUS_TAGS_SELECTOR = listOf(
        "script",
        "iframe",
        "object",
        "embed",
        "applet",
        "form",
        "input",
        "button",
        "select",
        "textarea",
        "base",
        "link",
        "meta",
        "frame",
        "frameset",
    ).joinToString(separator = ", ")

    private val URI_ATTRIBUTES = setOf(
        "href",
        "src",
        "action",
        "formaction",
        "xlink:href",
        "poster",
        "background",
    )

    private val CSS_COMMENT_REGEX = Regex("""/\*[\s\S]*?\*/""")
    private val CSS_URL_REGEX = Regex(
        pattern = """url\(\s*(?:(['"])([\s\S]*?)\1|([^)\s]+))\s*\)""",
        option = RegexOption.IGNORE_CASE,
    )
    private val DANGEROUS_CSS_PATTERN = Regex(
        pattern = """(?:expression\s*\(|javascript\s*:|vbscript\s*:|file\s*:|intent\s*:|content\s*:|-moz-binding|behavior\s*:|@import)""",
        option = RegexOption.IGNORE_CASE,
    )
    private val SAFE_HEX_COLOR_REGEX = Regex("""^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$""")
    private val MULTI_SPACE_REGEX = Regex("""[ \t\u000B\u000C\r]+""")
    private val MULTI_NEWLINE_REGEX = Regex("""\n{3,}""")

    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "header", "footer", "aside", "main",
        "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "ul", "ol",
        "li", "tr", "table", "hr",
    )

    private val scoreJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Serializable
    private data class ScoreDetailsDto(
        val auth: ScoreAuthDto? = null,
        val final: ScoreFinalDto? = null,
    )

    @Serializable
    private data class ScoreAuthDto(
        val spf: String = "",
        val dkim: String = "",
        val dmarc: String = "",
        val fromMismatch: Boolean = false,
        val total: Int = 0,
    )

    @Serializable
    private data class ScoreFinalDto(
        val score: Int = 0,
        val decision: String = "",
        val aiUpgrade: Boolean = false,
    )

    fun sanitize(rawHtml: String): SanitizedEmailHtml {
        if (rawHtml.isBlank()) {
            return SanitizedEmailHtml(
                bodyFragmentHtml = "",
                hasRemoteImages = false,
            )
        }

        val document: Document = Jsoup.parse(rawHtml)
        document.outputSettings().prettyPrint(false)

        // Remove active, form, framing, and metadata elements globally (head + body).
        document.select(DANGEROUS_TAGS_SELECTOR).remove()

        var hasRemoteImages = false
        val body = document.body()

        for (element in body.allElements) {
            if (element === body) continue

            val tagLower = element.tagName().lowercase(Locale.ROOT)
            val attrKeys = element.attributes().map { it.key }

            for (key in attrKeys) {
                val keyLower = key.trim().lowercase(Locale.ROOT)

                // Strip all inline event handlers and iframe srcdoc payloads.
                if (keyLower.startsWith("on") || keyLower == "srcdoc") {
                    element.removeAttr(key)
                    continue
                }

                if (keyLower == "srcset") {
                    val rawSrcset = element.attr(key)
                    val sanitizedSrcset = sanitizeSrcset(rawSrcset)
                    if (sanitizedSrcset == null) {
                        element.removeAttr(key)
                    } else {
                        if (sanitizedSrcset.value != rawSrcset) {
                            element.attr(key, sanitizedSrcset.value)
                        }
                        if (sanitizedSrcset.hasRemoteHttp && (tagLower == "source" || tagLower == "img")) {
                            hasRemoteImages = true
                        }
                    }
                    continue
                }

                if (keyLower in URI_ATTRIBUTES) {
                    val rawUri = element.attr(key).trim()
                    if (!isAllowedUri(rawUri)) {
                        element.removeAttr(key)
                    } else if (isRemoteHttpUri(rawUri)) {
                        val isRemoteImageAttr = when (keyLower) {
                            "src" -> tagLower == "img" || tagLower == "source" || tagLower == "video"
                            "background", "poster" -> true
                            "href", "xlink:href" -> tagLower == "image"
                            else -> false
                        }
                        if (isRemoteImageAttr) {
                            hasRemoteImages = true
                        }
                    }
                    continue
                }

                if (keyLower == "style") {
                    val rawStyle = element.attr(key)
                    val styleResult = sanitizeInlineStyle(rawStyle)
                    if (styleResult.sanitized == null) {
                        element.removeAttr(key)
                    } else {
                        if (styleResult.sanitized != rawStyle) {
                            element.attr(key, styleResult.sanitized)
                        }
                        if (styleResult.hasRemoteUrl) {
                            hasRemoteImages = true
                        }
                    }
                }
            }

            if (tagLower == "style") {
                val cssText = element.data()
                val cssResult = sanitizeStyleBlock(cssText)
                if (cssResult.hasRemoteUrl) {
                    hasRemoteImages = true
                }
                if (cssResult.unsafe) {
                    element.remove()
                }
            }
        }

        return SanitizedEmailHtml(
            bodyFragmentHtml = body.html().trim(),
            hasRemoteImages = hasRemoteImages,
        )
    }

    fun wrapInThemedDocument(
        sanitizedFragmentHtml: String,
        isDark: Boolean,
        purePitchAmoled: Boolean,
        fontScale: Float = 1.0f,
        backgroundHex: String,
        onSurfaceHex: String,
        primaryHex: String,
        outlineHex: String,
        surfaceContainerHex: String,
    ): String {
        val clampedScale = fontScale.coerceIn(0.75f, 2.0f)
        val bodyFontSizePx = String.format(Locale.US, "%.2f", 15f * clampedScale)

        val safeBackground = sanitizeHexColor(
            candidate = if (isDark && purePitchAmoled) "#000000" else backgroundHex,
            fallback = if (isDark) "#101417" else "#F7FAFF",
        )
        val safeOnSurface = sanitizeHexColor(
            candidate = onSurfaceHex,
            fallback = if (isDark) "#E6E9F0" else "#1A1B1E",
        )
        val safePrimary = sanitizeHexColor(
            candidate = primaryHex,
            fallback = if (isDark) "#6BA4FF" else "#1E5EFF",
        )
        val safeOutline = sanitizeHexColor(
            candidate = outlineHex,
            fallback = if (isDark) "#2B3140" else "#DDD7CC",
        )
        val safeSurfaceContainer = sanitizeHexColor(
            candidate = surfaceContainerHex,
            fallback = if (isDark) "#111520" else "#F2EDE3",
        )
        val colorScheme = if (isDark) "dark" else "light"

        return buildString(sanitizedFragmentHtml.length + 1024) {
            append("<!DOCTYPE html>")
            append("<html>")
            append("<head>")
            append("<meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
            append("<meta name=\"color-scheme\" content=\"").append(colorScheme).append("\">")
            append("<style>")
            append(":root { color-scheme: ").append(colorScheme).append("; }\n")
            append("html, body { ")
            append("margin: 0; padding: 0; ")
            append("background-color: ").append(safeBackground).append("; ")
            append("color: ").append(safeOnSurface).append("; ")
            append("font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif; ")
            append("font-size: ").append(bodyFontSizePx).append("px; ")
            append("line-height: 1.65; ")
            append("-webkit-text-size-adjust: 100%; ")
            append("overflow-wrap: anywhere; ")
            append("word-break: break-word; ")
            append("}\n")
            append("body { padding: 12px 14px 24px 14px; box-sizing: border-box; }\n")
            append("img, table, pre { max-width: 100%; }\n")
            append("img, svg, video { max-width: 100%; height: auto; }\n")
            append("table { border-collapse: collapse; }\n")
            append("th, td { border-color: ").append(safeOutline).append("; overflow-wrap: anywhere; }\n")
            append("a { color: ").append(safePrimary).append("; text-decoration: underline; overflow-wrap: anywhere; word-break: break-word; }\n")
            append("blockquote { ")
            append("margin: 10px 0; padding: 4px 12px; ")
            append("border-left: 3px solid ").append(safeOutline).append("; ")
            append("background-color: ").append(safeSurfaceContainer).append("; ")
            append("border-radius: 0 6px 6px 0; ")
            append("opacity: 0.92; ")
            append("}\n")
            append("pre, code { ")
            append("font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; ")
            append("background-color: ").append(safeSurfaceContainer).append("; ")
            append("border-radius: 6px; ")
            append("}\n")
            append("code { padding: 1px 5px; font-size: 0.9em; }\n")
            append("pre { padding: 10px 12px; border: 1px solid ").append(safeOutline).append("; overflow-x: auto; white-space: pre-wrap; word-break: break-word; }\n")
            append("pre code { background: transparent; padding: 0; }\n")
            append("hr { border: none; border-top: 1px solid ").append(safeOutline).append("; margin: 14px 0; }\n")
            append("</style>")
            append("</head>")
            append("<body>")
            append(sanitizedFragmentHtml)
            append("</body>")
            append("</html>")
        }
    }

    fun extractPlainTextFallback(rawHtml: String): String {
        if (rawHtml.isBlank()) return ""
        val document = Jsoup.parse(rawHtml)
        document.select("script, style, head").remove()
        val body = document.body()

        val sb = StringBuilder()
        NodeTraversor.traverse(
            object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    when (node) {
                        is TextNode -> {
                            val collapsed = node.wholeText.replace(MULTI_SPACE_REGEX, " ")
                            if (collapsed.isNotBlank()) {
                                sb.append(collapsed)
                            } else if (sb.isNotEmpty() && !sb.last().isWhitespace()) {
                                sb.append(' ')
                            }
                        }
                        is Element -> {
                            val name = node.tagName().lowercase(Locale.ROOT)
                            when {
                                name == "br" -> sb.append('\n')
                                name == "li" -> {
                                    ensureNewline(sb)
                                    sb.append("• ")
                                }
                                name in BLOCK_TAGS -> ensureNewline(sb)
                            }
                        }
                    }
                }

                override fun tail(node: Node, depth: Int) {
                    if (node is Element) {
                        val name = node.tagName().lowercase(Locale.ROOT)
                        when {
                            name == "td" || name == "th" -> {
                                if (sb.isNotEmpty() && !sb.last().isWhitespace()) {
                                    sb.append(' ')
                                }
                            }
                            name in BLOCK_TAGS -> ensureNewline(sb)
                        }
                    }
                }
            },
            body,
        )

        return sb.toString()
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(MULTI_NEWLINE_REGEX, "\n\n")
            .trim()
    }

    fun parseScoreDetails(rawScoreDetails: String): SeekMailAuthTelemetry? {
        val trimmed = rawScoreDetails.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val element = scoreJson.parseToJsonElement(trimmed)
            if (element !is JsonObject) return@runCatching null
            val dto = scoreJson.decodeFromJsonElement(ScoreDetailsDto.serializer(), element)
            if (dto.auth == null && dto.final == null) return@runCatching null
            SeekMailAuthTelemetry(
                spf = dto.auth?.spf.orEmpty(),
                dkim = dto.auth?.dkim.orEmpty(),
                dmarc = dto.auth?.dmarc.orEmpty(),
                fromMismatch = dto.auth?.fromMismatch ?: false,
                authTotalScore = dto.auth?.total ?: 0,
                finalScore = dto.final?.score ?: 0,
                decision = dto.final?.decision.orEmpty(),
                aiUpgrade = dto.final?.aiUpgrade ?: false,
            )
        }.getOrNull()
    }

    private fun ensureNewline(sb: StringBuilder) {
        if (sb.isNotEmpty() && sb.last() != '\n') {
            sb.append('\n')
        }
    }

    private fun isAllowedUri(rawUri: String): Boolean {
        val trimmed = rawUri.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("#")) return true

        val normalized = trimmed
            .filterNot { it.isWhitespace() || it.isISOControl() }
            .lowercase(Locale.ROOT)

        return normalized.startsWith("http://") ||
            normalized.startsWith("https://") ||
            normalized.startsWith("mailto:") ||
            normalized.startsWith("cid:") ||
            normalized.startsWith("data:image/")
    }

    private fun isRemoteHttpUri(rawUri: String): Boolean {
        val normalized = rawUri
            .trim()
            .filterNot { it.isWhitespace() || it.isISOControl() }
            .lowercase(Locale.ROOT)
        return normalized.startsWith("http://") || normalized.startsWith("https://")
    }

    private data class SrcsetResult(
        val value: String,
        val hasRemoteHttp: Boolean,
    )

    private fun sanitizeSrcset(rawSrcset: String): SrcsetResult? {
        val trimmed = rawSrcset.trim()
        if (trimmed.isEmpty()) return null
        val normalized = trimmed
            .filterNot { it.isWhitespace() || it.isISOControl() }
            .lowercase(Locale.ROOT)
        if (normalized.startsWith("data:")) {
            return if (normalized.startsWith("data:image/")) {
                SrcsetResult(value = trimmed, hasRemoteHttp = false)
            } else {
                null
            }
        }

        val kept = mutableListOf<String>()
        var hasRemote = false
        for (part in trimmed.split(',')) {
            val candidate = part.trim()
            if (candidate.isEmpty()) continue
            val firstToken = candidate.split(Regex("""\s+"""), limit = 2).firstOrNull().orEmpty()
            if (isAllowedUri(firstToken)) {
                kept.add(candidate)
                if (isRemoteHttpUri(firstToken)) {
                    hasRemote = true
                }
            }
        }
        return if (kept.isEmpty()) null else SrcsetResult(kept.joinToString(", "), hasRemote)
    }

    private data class StyleSanitizeResult(
        val sanitized: String?,
        val hasRemoteUrl: Boolean,
    )

    private fun sanitizeInlineStyle(rawStyle: String): StyleSanitizeResult {
        val strippedComments = rawStyle.replace(CSS_COMMENT_REGEX, "")
        val collapsed = strippedComments.filterNot { it.isWhitespace() || it.isISOControl() }
        if (DANGEROUS_CSS_PATTERN.containsMatchIn(collapsed)) {
            return StyleSanitizeResult(sanitized = null, hasRemoteUrl = false)
        }

        var hasRemoteUrl = false
        var hasUnsafeUrl = false
        for (match in CSS_URL_REGEX.findAll(strippedComments)) {
            val urlValue = (match.groupValues[2].ifEmpty { match.groupValues[3] }).trim()
            if (!isAllowedUri(urlValue)) {
                hasUnsafeUrl = true
            } else if (isRemoteHttpUri(urlValue)) {
                hasRemoteUrl = true
            }
        }
        if (hasUnsafeUrl) {
            return StyleSanitizeResult(sanitized = null, hasRemoteUrl = false)
        }
        return StyleSanitizeResult(sanitized = rawStyle, hasRemoteUrl = hasRemoteUrl)
    }

    private data class StyleBlockResult(
        val unsafe: Boolean,
        val hasRemoteUrl: Boolean,
    )

    private fun sanitizeStyleBlock(cssText: String): StyleBlockResult {
        val strippedComments = cssText.replace(CSS_COMMENT_REGEX, "")
        val collapsed = strippedComments.filterNot { it.isWhitespace() || it.isISOControl() }
        val unsafe = DANGEROUS_CSS_PATTERN.containsMatchIn(collapsed)
        var hasRemoteUrl = false
        for (match in CSS_URL_REGEX.findAll(strippedComments)) {
            val urlValue = (match.groupValues[2].ifEmpty { match.groupValues[3] }).trim()
            if (isRemoteHttpUri(urlValue)) {
                hasRemoteUrl = true
            } else if (!isAllowedUri(urlValue)) {
                return StyleBlockResult(unsafe = true, hasRemoteUrl = false)
            }
        }
        return StyleBlockResult(unsafe = unsafe, hasRemoteUrl = hasRemoteUrl)
    }

    private fun sanitizeHexColor(candidate: String, fallback: String): String {
        val trimmed = candidate.trim()
        return if (SAFE_HEX_COLOR_REGEX.matches(trimmed)) trimmed else fallback
    }
}
