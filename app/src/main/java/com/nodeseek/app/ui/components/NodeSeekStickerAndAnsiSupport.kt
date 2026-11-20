package com.nodeseek.app.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import java.net.URLDecoder

private const val STICKER_BASE_URL = "https://www.nodeseek.com/static/image/sticker"

@Immutable
data class NodeSeekSticker(
    val code: String,
    val group: String,
    val filename: String,
    val isVideoEmoji: Boolean = false,
) {
    val imageUrl: String
        get() = if (isVideoEmoji) {
            "$STICKER_BASE_URL/$group/$filename.webm"
        } else {
            "$STICKER_BASE_URL/$group/$filename"
        }
}

/**
 * Official NodeSeek sticker catalog (`:ac01:`, `:yct001:`, `:xhj001:`, `:emoji00:`),
 * usable both inline in `MarkdownRenderer` and inside future reply/topic sticker pickers.
 */
object NodeSeekStickerCatalog {
    private val acFiles: List<String> = buildList {
        for (i in 1..54) add("%02d.png".format(i))
        for (i in 1001..1040) add("$i.png")
        for (i in 2001..2055) add("$i.png")
    }

    private val yctFiles: List<String> = (1..22).map { "%03d.gif".format(it) }

    private val xhjFiles: List<String> = listOf(
        "001.png", "002.png", "003.png", "004.gif", "005.png", "006.png", "007.png", "008.gif",
        "009.gif", "010.gif", "011.png", "012.gif", "013.gif", "014.gif", "015.gif", "016.gif",
        "017.gif", "018.gif", "019.gif", "020.gif", "021.gif", "022.png", "023.gif", "024.png",
        "025.png", "026.gif", "027.gif", "028.gif", "029.gif", "030.gif", "031.png", "032.png",
    )

    private val emojiFiles: List<String> = (0..48).map { "%02d".format(it) }

    private val lookupMap: Map<String, NodeSeekSticker> by lazy {
        buildMap {
            acFiles.forEach { file ->
                val stem = file.substringBefore('.')
                val code = "ac$stem"
                put(code, NodeSeekSticker(code = code, group = "ac", filename = file))
            }
            yctFiles.forEach { file ->
                val stem = file.substringBefore('.')
                val code = "yct$stem"
                put(code, NodeSeekSticker(code = code, group = "yct", filename = file))
            }
            xhjFiles.forEach { file ->
                val stem = file.substringBefore('.')
                val code = "xhj$stem"
                put(code, NodeSeekSticker(code = code, group = "xhj", filename = file))
            }
            emojiFiles.forEach { stem ->
                val code = "emoji$stem"
                put(code, NodeSeekSticker(code = code, group = "emoji", filename = stem, isVideoEmoji = true))
            }
        }
    }

    fun resolve(codeWithoutColons: String): NodeSeekSticker? =
        lookupMap[codeWithoutColons.trim().lowercase()]

    fun stickersForGroup(group: String): List<NodeSeekSticker> =
        lookupMap.values.filter { it.group == group }
}

/**
 * Unwraps NodeSeek redirect links (`https://www.nodeseek.com/jump?to=https%3A%2F%2F...`)
 * to their direct target HTTP/HTTPS URL.
 */
fun unwrapNodeSeekJumpUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (!trimmed.contains("/jump?", ignoreCase = true)) return trimmed
    val queryPart = trimmed.substringAfter('?', missingDelimiterValue = "")
    if (queryPart.isEmpty()) return trimmed

    for (param in queryPart.split('&')) {
        val eqIdx = param.indexOf('=')
        if (eqIdx <= 0) continue
        val key = param.substring(0, eqIdx)
        if (key.equals("to", ignoreCase = true)) {
            val encodedValue = param.substring(eqIdx + 1)
            val decoded = runCatching { URLDecoder.decode(encodedValue, "UTF-8") }
                .getOrDefault(encodedValue)
                .trim()
            if (decoded.startsWith("https://", ignoreCase = true) ||
                decoded.startsWith("http://", ignoreCase = true)
            ) {
                return decoded
            }
        }
    }
    return trimmed
}

/**
 * Immutable ANSI SGR style state carried across lines during incremental terminal rendering.
 */
@Immutable
data class AnsiSgrState(
    val fg: Color? = null,
    val bg: Color? = null,
    val isBold: Boolean = false,
    val isDim: Boolean = false,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
) {
    val isDefault: Boolean
        get() = fg == null && bg == null && !isBold && !isDim && !isItalic && !isUnderline

    fun toSpanStyle(defaultForeground: Color): SpanStyle {
        val resolvedFg = fg ?: defaultForeground
        return SpanStyle(
            color = if (isDim) resolvedFg.copy(alpha = 0.62f) else resolvedFg,
            background = bg ?: Color.Unspecified,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (isUnderline) TextDecoration.Underline else TextDecoration.None,
        )
    }

    companion object {
        val Default = AnsiSgrState()
    }
}

/**
 * ANSI SGR terminal escape sequence parser for VPS benchmark outputs (YABS, NodeQuality, etc.)
 * and lightweight source syntax highlighter for standard code blocks.
 */
object AnsiAndCodeFormatter {
    fun containsAnsiEscapes(text: String): Boolean = text.indexOf("\u001B[") >= 0

    fun stripAnsiEscapes(text: String): String {
        val firstEsc = text.indexOf("\u001B[")
        if (firstEsc < 0) return text
        val sb = StringBuilder(text.length)
        var cursor = 0
        var searchFrom = firstEsc
        while (searchFrom >= 0 && searchFrom < text.length) {
            val sgrEnd = findSgrEndIndex(text, searchFrom)
            if (sgrEnd >= 0) {
                if (searchFrom > cursor) {
                    sb.append(text, cursor, searchFrom)
                }
                cursor = sgrEnd + 1
                searchFrom = text.indexOf("\u001B[", startIndex = cursor)
            } else {
                searchFrom = text.indexOf("\u001B[", startIndex = searchFrom + 2)
            }
        }
        if (cursor == 0) return text
        if (cursor < text.length) {
            sb.append(text, cursor, text.length)
        }
        return sb.toString()
    }

    fun parseAnsiTerminalText(
        rawText: String,
        defaultForeground: Color = Color(0xFFE6EDF3),
    ): AnnotatedString = parseAnsiLine(
        rawText = rawText,
        initialState = AnsiSgrState.Default,
        defaultForeground = defaultForeground,
    ).first

    /**
     * Incrementally parses a single line (or multi-line block) starting from [initialState] and
     * returns both the styled [AnnotatedString] and the resulting [AnsiSgrState] for the next line.
     *
     * Uses a zero-regex scanner and stack-allocated integer parsing buffer to eliminate per-token
     * `MatchResult` and `split(';')` allocations on multi-thousand-line terminal outputs.
     */
    fun parseAnsiLine(
        rawText: String,
        initialState: AnsiSgrState = AnsiSgrState.Default,
        defaultForeground: Color = Color(0xFFE6EDF3),
    ): Pair<AnnotatedString, AnsiSgrState> {
        if (rawText.isEmpty()) {
            return AnnotatedString("") to initialState
        }

        val firstEsc = rawText.indexOf("\u001B[")
        if (firstEsc < 0) {
            val styled = buildAnnotatedString {
                append(rawText)
                addStyle(initialState.toSpanStyle(defaultForeground), 0, rawText.length)
            }
            return styled to initialState
        }

        var currentFg: Color? = initialState.fg
        var currentBg: Color? = initialState.bg
        var isBold: Boolean = initialState.isBold
        var isDim: Boolean = initialState.isDim
        var isItalic: Boolean = initialState.isItalic
        var isUnderline: Boolean = initialState.isUnderline

        var codesBuf = IntArray(16)

        val annotated = buildAnnotatedString {
            var cursor = 0
            var searchFrom = firstEsc
            while (searchFrom >= 0 && searchFrom < rawText.length) {
                val sgrEnd = findSgrEndIndex(rawText, searchFrom)
                if (sgrEnd < 0) {
                    searchFrom = rawText.indexOf("\u001B[", startIndex = searchFrom + 2)
                    continue
                }

                if (searchFrom > cursor) {
                    val start = length
                    append(rawText, cursor, searchFrom)
                    val fg = currentFg ?: defaultForeground
                    addStyle(
                        style = SpanStyle(
                            color = if (isDim) fg.copy(alpha = 0.62f) else fg,
                            background = currentBg ?: Color.Unspecified,
                            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal,
                            textDecoration = if (isUnderline) TextDecoration.Underline else TextDecoration.None,
                        ),
                        start = start,
                        end = length,
                    )
                }

                // Parse semicolon-delimited SGR integer parameters in rawText[searchFrom+2 .. sgrEnd-1]
                var codeCount = 0
                val paramsStart = searchFrom + 2
                if (paramsStart == sgrEnd) {
                    codesBuf[0] = 0
                    codeCount = 1
                } else {
                    var acc = 0
                    var hasDigits = false
                    var p = paramsStart
                    while (p < sgrEnd) {
                        val ch = rawText[p]
                        if (ch == ';') {
                            if (codeCount >= codesBuf.size) {
                                codesBuf = codesBuf.copyOf(codesBuf.size * 2)
                            }
                            codesBuf[codeCount++] = if (hasDigits) acc else 0
                            acc = 0
                            hasDigits = false
                        } else {
                            // Guard overflow on malformed giant numbers
                            acc = if (acc > 100_000) 100_000 else acc * 10 + (ch - '0')
                            hasDigits = true
                        }
                        p++
                    }
                    if (codeCount >= codesBuf.size) {
                        codesBuf = codesBuf.copyOf(codesBuf.size * 2)
                    }
                    codesBuf[codeCount++] = if (hasDigits) acc else 0
                }

                var i = 0
                while (i < codeCount) {
                    when (val code = codesBuf[i]) {
                        0 -> {
                            currentFg = null
                            currentBg = null
                            isBold = false
                            isDim = false
                            isItalic = false
                            isUnderline = false
                        }
                        1 -> isBold = true
                        2 -> isDim = true
                        3 -> isItalic = true
                        4 -> isUnderline = true
                        22 -> {
                            isBold = false
                            isDim = false
                        }
                        23 -> isItalic = false
                        24 -> isUnderline = false
                        in 30..37 -> currentFg = ansi16Color(code - 30, bright = false)
                        39 -> currentFg = null
                        in 40..47 -> currentBg = ansi16Color(code - 40, bright = false).copy(alpha = 0.35f)
                        49 -> currentBg = null
                        in 90..97 -> currentFg = ansi16Color(code - 90, bright = true)
                        in 100..107 -> currentBg = ansi16Color(code - 100, bright = true).copy(alpha = 0.35f)
                        38, 48 -> {
                            val isForeground = code == 38
                            val mode = if (i + 1 < codeCount) codesBuf[i + 1] else -1
                            if (mode == 5 && i + 2 < codeCount) {
                                val c = xterm256Color(codesBuf[i + 2])
                                if (isForeground) currentFg = c else currentBg = c.copy(alpha = 0.35f)
                                i += 2
                            } else if (mode == 2 && i + 4 < codeCount) {
                                val r = codesBuf[i + 2].coerceIn(0, 255)
                                val g = codesBuf[i + 3].coerceIn(0, 255)
                                val b = codesBuf[i + 4].coerceIn(0, 255)
                                val c = Color(red = r, green = g, blue = b)
                                if (isForeground) currentFg = c else currentBg = c.copy(alpha = 0.35f)
                                i += 4
                            }
                        }
                    }
                    i++
                }

                cursor = sgrEnd + 1
                searchFrom = rawText.indexOf("\u001B[", startIndex = cursor)
            }

            if (cursor < rawText.length) {
                val start = length
                append(rawText, cursor, rawText.length)
                val fg = currentFg ?: defaultForeground
                addStyle(
                    style = SpanStyle(
                        color = if (isDim) fg.copy(alpha = 0.62f) else fg,
                        background = currentBg ?: Color.Unspecified,
                        fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal,
                        textDecoration = if (isUnderline) TextDecoration.Underline else TextDecoration.None,
                    ),
                    start = start,
                    end = length,
                )
            }
        }

        val exitState = AnsiSgrState(
            fg = currentFg,
            bg = currentBg,
            isBold = isBold,
            isDim = isDim,
            isItalic = isItalic,
            isUnderline = isUnderline,
        )
        return annotated to exitState
    }

    private fun findSgrEndIndex(text: String, escBracketIndex: Int): Int {
        var i = escBracketIndex + 2
        while (i < text.length) {
            val ch = text[i]
            if (ch == 'm') return i
            if (ch !in '0'..'9' && ch != ';') return -1
            i++
        }
        return -1
    }

    private val CODE_TOKEN_REGEX = Regex(
        pattern = """(//[^\n]*|#[^\n]*)|("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')|\b(fun|val|var|class|object|interface|if|else|when|for|while|return|import|package|suspend|async|await|def|from|const|let|function|export|default|public|private|struct|enum|impl|fn|sudo|curl|wget|docker|apt|yum|systemctl|bash|sh|echo|grep|awk|sed|true|false|null|None)\b|(--?[a-zA-Z0-9_-]+)|\b(\d+(?:\.\d+)?(?:GiB|MiB|KiB|GB|MB|KB|Gbps|Mbps|ms|%)?)\b""",
    )

    fun highlightSourceCode(
        code: String,
        defaultForeground: Color = Color(0xFFE6EDF3),
    ): AnnotatedString = buildAnnotatedString {
        append(code)
        addStyle(SpanStyle(color = defaultForeground), 0, code.length)

        for (match in CODE_TOKEN_REGEX.findAll(code)) {
            val range = match.range
            val style = when {
                match.groups[1] != null -> SpanStyle(
                    color = Color(0xFF7D8590),
                    fontStyle = FontStyle.Italic,
                )
                match.groups[2] != null -> SpanStyle(color = Color(0xFFA5D6FF))
                match.groups[3] != null -> SpanStyle(
                    color = Color(0xFFFF7B72),
                    fontWeight = FontWeight.SemiBold,
                )
                match.groups[4] != null -> SpanStyle(color = Color(0xFF79C0FF))
                match.groups[5] != null -> SpanStyle(color = Color(0xFFD2A8FF))
                else -> null
            }
            if (style != null) {
                addStyle(style, range.first, range.last + 1)
            }
        }
    }

    private fun ansi16Color(index: Int, bright: Boolean): Color = when (index) {
        0 -> if (bright) Color(0xFF6E7681) else Color(0xFF484F58)
        1 -> if (bright) Color(0xFFFF7B72) else Color(0xFFFA4549)
        2 -> if (bright) Color(0xFF56D364) else Color(0xFF3FB950)
        3 -> if (bright) Color(0xFFE3B341) else Color(0xFFD29922)
        4 -> if (bright) Color(0xFF79C0FF) else Color(0xFF58A6FF)
        5 -> if (bright) Color(0xFFD2A8FF) else Color(0xFFBC8CFF)
        6 -> if (bright) Color(0xFF56D4DD) else Color(0xFF39C5CF)
        7 -> if (bright) Color(0xFFFFFFFF) else Color(0xFFC9D1D9)
        else -> Color(0xFFE6EDF3)
    }

    private fun xterm256Color(code: Int): Color {
        val c = code.coerceIn(0, 255)
        if (c < 8) return ansi16Color(c, bright = false)
        if (c < 16) return ansi16Color(c - 8, bright = true)
        if (c in 16..231) {
            val idx = c - 16
            val levels = intArrayOf(0, 95, 135, 175, 215, 255)
            val r = levels[(idx / 36) % 6]
            val g = levels[(idx / 6) % 6]
            val b = levels[idx % 6]
            return Color(red = r, green = g, blue = b)
        }
        val gray = (8 + (c - 232) * 10).coerceIn(0, 255)
        return Color(red = gray, green = gray, blue = gray)
    }
}
