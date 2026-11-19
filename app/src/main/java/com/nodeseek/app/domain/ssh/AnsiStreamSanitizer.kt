package com.nodeseek.app.domain.ssh

/**
 * Stateful streaming terminal sanitizer designed for headless SSH PTY sessions (`xterm-256color`).
 *
 * Responsibilities:
 * - Collapses carriage-return (`\r`) progress bar & spinner overwrites (`curl`, `dd`, `fio`, `yabs`)
 *   into a single clean line instead of thousands of duplicate lines.
 * - Preserves standard ANSI SGR color/style sequences (`\u001B[...m`) verbatim for
 *   `AnsiAndCodeFormatter.parseAnsiTerminalText`.
 * - Strips non-SGR CSI cursor/screen control sequences (`\u001B[?25l`, `\u001B[2J`, `\u001B[K`,
 *   `\u001B[1A`, etc.), OSC title/hyperlink sequences (`\u001B]...\u0007`), charset switches, and
 *   unprintable control bytes — even when escape sequences span across TCP/PTY chunk boundaries.
 * - Bounds total buffered lines via [maxLines] to protect mobile memory on runaway outputs.
 */
class AnsiStreamSanitizer(
    private val maxLines: Int = DEFAULT_MAX_LINES,
) {
    private enum class ParserState {
        NORMAL,
        ESC,
        ESC_INTERMEDIATE,
        CSI,
        OSC,
        OSC_ESC,
    }

    private var state: ParserState = ParserState.NORMAL
    private val completedLines = ArrayDeque<String>()
    private val currentLine = StringBuilder()
    private val csiBuffer = StringBuilder()
    private val pendingSgrAfterCr = StringBuilder()
    private var pendingCarriageReturn: Boolean = false
    private var oscCharsConsumed: Int = 0
    private var droppedLineCount: Int = 0
    private var hasTrailingNewline: Boolean = false

    /**
     * Feeds a raw chunk of UTF-8 PTY output into the sanitizer state machine.
     */
    fun append(rawChunk: String) {
        if (rawChunk.isEmpty()) return
        for (idx in rawChunk.indices) {
            processChar(rawChunk[idx])
        }
    }

    /**
     * Returns the current sanitized ANSI text snapshot, including the active in-progress line.
     */
    fun snapshot(): String {
        val totalLines = completedLines.size + if (currentLine.isNotEmpty()) 1 else 0
        if (totalLines == 0 && droppedLineCount == 0) return ""

        val out = StringBuilder(estimateCapacity())
        if (droppedLineCount > 0) {
            out.append("\u001B[2m... [已省略早期 ").append(droppedLineCount).append(" 行终端输出] ...\u001B[0m\n")
        }
        var first = true
        for (line in completedLines) {
            if (!first) out.append('\n')
            out.append(line)
            first = false
        }
        if (currentLine.isNotEmpty()) {
            if (!first) out.append('\n')
            out.append(currentLine)
            if (pendingSgrAfterCr.isNotEmpty()) {
                out.append(pendingSgrAfterCr)
            }
        } else if (hasTrailingNewline && completedLines.isNotEmpty()) {
            out.append('\n')
        }
        return out.toString()
    }

    /**
     * Resets all internal state so the sanitizer can be reused for a new run.
     */
    fun reset() {
        state = ParserState.NORMAL
        completedLines.clear()
        currentLine.clear()
        csiBuffer.clear()
        pendingSgrAfterCr.clear()
        pendingCarriageReturn = false
        oscCharsConsumed = 0
        droppedLineCount = 0
        hasTrailingNewline = false
    }

    private fun processChar(ch: Char) {
        when (state) {
            ParserState.NORMAL -> processNormalChar(ch)
            ParserState.ESC -> processEscChar(ch)
            ParserState.ESC_INTERMEDIATE -> {
                // Consumes single trailing charset/designator byte (e.g. ESC ( B)
                state = ParserState.NORMAL
            }
            ParserState.CSI -> processCsiChar(ch)
            ParserState.OSC -> processOscChar(ch)
            ParserState.OSC_ESC -> processOscEscChar(ch)
        }
    }

    private fun processNormalChar(ch: Char) {
        when (ch) {
            ESC -> {
                state = ParserState.ESC
            }
            '\r' -> {
                pendingCarriageReturn = true
            }
            '\n' -> {
                if (pendingSgrAfterCr.isNotEmpty()) {
                    currentLine.append(pendingSgrAfterCr)
                    pendingSgrAfterCr.clear()
                }
                pendingCarriageReturn = false
                commitCurrentLine()
                hasTrailingNewline = true
            }
            '\b' -> {
                if (!pendingCarriageReturn) {
                    deleteLastVisibleCharFromCurrentLine()
                }
            }
            '\t' -> {
                applyPendingCarriageReturnOverwrite()
                currentLine.append('\t')
                hasTrailingNewline = false
            }
            else -> {
                val code = ch.code
                // Skip non-printable C0 control bytes and DEL
                if (code < 0x20 || code == 0x7F) return
                applyPendingCarriageReturnOverwrite()
                currentLine.append(ch)
                hasTrailingNewline = false
            }
        }
    }

    private fun processEscChar(ch: Char) {
        when (ch) {
            '[' -> {
                csiBuffer.clear()
                state = ParserState.CSI
            }
            ']', 'P', 'X', '^', '_' -> {
                oscCharsConsumed = 0
                state = ParserState.OSC
            }
            '(', ')', '*', '+', '#', ' ', '%' -> {
                state = ParserState.ESC_INTERMEDIATE
            }
            else -> {
                // 2-byte ESC sequences such as ESC =, ESC >, ESC 7, ESC 8, ESC M
                state = ParserState.NORMAL
            }
        }
    }

    private fun processCsiChar(ch: Char) {
        val code = ch.code
        if (code in 0x20..0x3F) {
            if (csiBuffer.length < MAX_CSI_PARAM_LEN) {
                csiBuffer.append(ch)
            }
            return
        }
        if (code in 0x40..0x7E) {
            handleCsiCommand(params = csiBuffer.toString(), finalByte = ch)
            csiBuffer.clear()
            state = ParserState.NORMAL
            return
        }
        // Malformed CSI sequence: abort and re-process byte in NORMAL state
        csiBuffer.clear()
        state = ParserState.NORMAL
        processNormalChar(ch)
    }

    private fun handleCsiCommand(params: String, finalByte: Char) {
        when (finalByte) {
            'm' -> {
                // Preserve SGR style/color codes compatible with AnsiAndCodeFormatter
                if (isStandardSgrParams(params)) {
                    val normalizedParams = if (params.contains(':')) params.replace(':', ';') else params
                    val seq = "\u001B[${normalizedParams}m"
                    if (pendingCarriageReturn) {
                        pendingSgrAfterCr.append(seq)
                    } else {
                        currentLine.append(seq)
                    }
                }
            }
            'K' -> {
                // Erase-in-line: \u001B[2K clears whole line; \r\u001B[K or \r\u001B[0K clears from col 0
                val trimmed = params.trim()
                if (trimmed == "2" || (pendingCarriageReturn && (trimmed.isEmpty() || trimmed == "0"))) {
                    currentLine.clear()
                    pendingCarriageReturn = false
                    if (pendingSgrAfterCr.isNotEmpty()) {
                        currentLine.append(pendingSgrAfterCr)
                        pendingSgrAfterCr.clear()
                    }
                }
            }
            else -> {
                // Strip cursor movement, hide/show cursor (?25l/?25h), clear screen (2J), alt buffer, etc.
            }
        }
    }

    private fun processOscChar(ch: Char) {
        oscCharsConsumed++
        when {
            ch == BEL -> state = ParserState.NORMAL
            ch == ESC -> state = ParserState.OSC_ESC
            oscCharsConsumed >= MAX_OSC_LEN -> state = ParserState.NORMAL
        }
    }

    private fun processOscEscChar(ch: Char) {
        when (ch) {
            '\\' -> state = ParserState.NORMAL
            '[' -> {
                // Recovery if an unterminated OSC was followed immediately by a CSI sequence
                csiBuffer.clear()
                state = ParserState.CSI
            }
            else -> state = ParserState.OSC
        }
    }

    private fun applyPendingCarriageReturnOverwrite() {
        if (!pendingCarriageReturn) return
        currentLine.clear()
        if (pendingSgrAfterCr.isNotEmpty()) {
            currentLine.append(pendingSgrAfterCr)
            pendingSgrAfterCr.clear()
        }
        pendingCarriageReturn = false
    }

    private fun commitCurrentLine() {
        val lineText = currentLine.toString().trimEnd(' ')
        currentLine.clear()
        if (completedLines.size >= maxLines) {
            completedLines.removeFirst()
            droppedLineCount++
        }
        completedLines.addLast(lineText)
    }

    private fun deleteLastVisibleCharFromCurrentLine() {
        if (currentLine.isEmpty()) return
        // Avoid slicing through a trailing SGR sequence `\u001B[...m`
        var end = currentLine.length
        while (end > 0) {
            if (currentLine[end - 1] == 'm') {
                val escIdx = currentLine.lastIndexOf("\u001B[", startIndex = end - 1)
                if (escIdx >= 0 && isStandardSgrParams(currentLine.substring(escIdx + 2, end - 1))) {
                    end = escIdx
                    continue
                }
            }
            currentLine.deleteCharAt(end - 1)
            return
        }
    }

    private fun isStandardSgrParams(params: String): Boolean {
        for (i in params.indices) {
            val c = params[i]
            if (c !in '0'..'9' && c != ';' && c != ':') return false
        }
        return true
    }

    private fun estimateCapacity(): Int {
        var chars = currentLine.length + 32
        for (line in completedLines) {
            chars += line.length + 1
        }
        return chars
    }

    companion object {
        private const val ESC: Char = '\u001B'
        private const val BEL: Char = '\u0007'
        private const val MAX_CSI_PARAM_LEN: Int = 64
        private const val MAX_OSC_LEN: Int = 2_048
        const val DEFAULT_MAX_LINES: Int = 8_000

        /**
         * Stateless convenience helper that sanitizes a complete raw terminal transcript.
         */
        fun sanitize(raw: String, maxLines: Int = DEFAULT_MAX_LINES): String {
            if (raw.isEmpty()) return ""
            return AnsiStreamSanitizer(maxLines = maxLines).apply {
                append(raw)
            }.snapshot()
        }
    }
}
