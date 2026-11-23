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
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.util.Locale

/**
 * Jsoup Safelist-based HTML sanitizer, plain-text fallback extractor, and auth telemetry parser
 * for Seek Mail (`seek.li`) messages.
 */
object SeekMailHtmlSanitizer {

    data class SanitizedEmailHtml(
        val bodyFragmentHtml: String,
        val hasRemoteImages: Boolean,
    )

    private val EMAIL_SAFELIST: Safelist = Safelist.relaxed()
        .preserveRelativeLinks(true)
        .addTags(
            "section", "article", "header", "footer", "aside", "main", "nav",
            "center", "font", "hr", "s", "del", "ins", "mark", "abbr", "time",
            "address", "figure", "figcaption", "picture", "source", "big",
        )
        .addAttributes(":all", "style", "class", "dir", "lang", "title", "align", "valign")
        .addAttributes(
            "table",
            "width", "height", "border", "cellpadding", "cellspacing", "bgcolor", "background", "role",
        )
        .addAttributes("tr", "bgcolor", "height", "background")
        .addAttributes("td", "width", "height", "colspan", "rowspan", "bgcolor", "background", "nowrap")
        .addAttributes("th", "width", "height", "colspan", "rowspan", "bgcolor", "background", "scope", "nowrap")
        .addAttributes("col", "span", "width")
        .addAttributes("colgroup", "span", "width")
        .addAttributes("img", "src", "srcset", "alt", "width", "height", "border", "title")
        .addAttributes("source", "src", "srcset", "media", "type")
        .addAttributes("a", "href", "name", "target", "rel")
        .addAttributes("font", "color", "face", "size")
        .addAttributes("div", "bgcolor", "background")
        .addAttributes("p", "bgcolor")
        .addProtocols("a", "href", "http", "https", "mailto")
        .addProtocols("img", "src", "http", "https", "cid", "data")
        .addProtocols("source", "src", "http", "https", "cid", "data")

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
    private val CSS_HEX_ESCAPE_REGEX = Regex("""\\([0-9a-fA-F]{1,6})\s?""")
    private val CSS_URL_REGEX = Regex(
        pattern = """url\(\s*(?:(['"])([\s\S]*?)\1|([^)\s]+))\s*\)""",
        option = RegexOption.IGNORE_CASE,
    )
    private val DANGEROUS_CSS_PATTERN = Regex(
        pattern = """(?:expression\s*\(|javascript\s*:|vbscript\s*:|file\s*:|intent\s*:|content\s*://|-moz-binding|behavior\s*:|@import|position\s*:\s*fixed)""",
        option = RegexOption.IGNORE_CASE,
    )
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

        val parsedDoc: Document = Jsoup.parse(rawHtml)
        // Strict Jsoup Safelist whitelist removes all non-whitelisted tags (<script>, <iframe>,
        // <style>, <svg>, <audio>, <video>, <form>, <dialog>, <marquee>, etc.) and attributes.
        val document: Document = Cleaner(EMAIL_SAFELIST).clean(parsedDoc)
        document.outputSettings().prettyPrint(false)

        var hasRemoteImages = false
        val body = document.body()

        for (element in body.allElements) {
            if (element === body) continue

            val tagLower = element.tagName().lowercase(Locale.ROOT)
            val attrKeys = element.attributes().map { it.key }

            for (key in attrKeys) {
                val keyLower = key.trim().lowercase(Locale.ROOT)

                // Defense-in-depth: strip any inline event handlers or srcdoc payloads.
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
                            "src" -> tagLower == "img" || tagLower == "source" || tagLower == "video" || tagLower == "audio"
                            "background", "poster" -> true
                            "href", "xlink:href" -> tagLower == "image" || tagLower == "use"
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
        }

        return SanitizedEmailHtml(
            bodyFragmentHtml = body.html().trim(),
            hasRemoteImages = hasRemoteImages,
        )
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

    private fun decodeCssEscapes(css: String): String {
        if (!css.contains('\\')) return css
        return CSS_HEX_ESCAPE_REGEX.replace(css) { match ->
            val codePoint = match.groupValues[1].toIntOrNull(16)
            if (codePoint != null && codePoint in 1..0x10FFFF && !Character.isSurrogate(codePoint.toChar())) {
                String(Character.toChars(codePoint))
            } else {
                ""
            }
        }
    }

    private fun sanitizeInlineStyle(rawStyle: String): StyleSanitizeResult {
        val strippedComments = decodeCssEscapes(rawStyle.replace(CSS_COMMENT_REGEX, ""))
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
        return StyleSanitizeResult(sanitized = strippedComments, hasRemoteUrl = hasRemoteUrl)
    }
}
