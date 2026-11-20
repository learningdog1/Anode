package com.nodeseek.app.domain.ssh

import androidx.compose.runtime.Immutable

/**
 * Single sanitized terminal line with a monotonically assigned [id] that remains stable across
 * ring-buffer evictions at the head and incremental appends at the tail.
 */
@Immutable
data class SanitizedTerminalLine(
    val id: Long,
    val ansiText: String,
)

/**
 * Structural snapshot of [AnsiStreamSanitizer]'s ring buffer.
 *
 * Performance guarantees:
 * - [completedLines] is shared by reference across `\r` progress-bar updates when no `\n` commit
 *   occurred (`O(1)` zero-copy).
 * - [fullAnsiText] is materialized lazily ONLY if a caller requests the concatenated transcript
 *   (e.g. upon run completion, cancellation, or Markdown export), avoiding `O(N²)` string copying
 *   during high-frequency streaming.
 * - [tailText] scans only the trailing lines needed by [SshStageDetector] (`O(tail)`).
 */
@Immutable
class SanitizedBufferSnapshot(
    val version: Long,
    val droppedLineCount: Int,
    val completedLines: List<SanitizedTerminalLine>,
    val activeLine: SanitizedTerminalLine?,
    val hasTrailingNewline: Boolean,
) {
    val isEmpty: Boolean
        get() = completedLines.isEmpty() && activeLine == null && droppedLineCount == 0

    val fullAnsiText: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        renderFullText()
    }

    /**
     * Returns up to the last [maxChars] characters of sanitized ANSI text by walking backwards
     * from [activeLine] and the tail of [completedLines], without concatenating the full buffer.
     */
    fun tailText(maxChars: Int = 3_500): String {
        if (isEmpty || maxChars <= 0) return ""
        var accumulated = activeLine?.ansiText?.length ?: 0
        var startIdx = completedLines.size
        while (startIdx > 0 && accumulated < maxChars) {
            startIdx--
            accumulated += completedLines[startIdx].ansiText.length + 1
        }
        val sb = StringBuilder(accumulated.coerceAtLeast(16))
        var first = true
        for (i in startIdx until completedLines.size) {
            if (!first) sb.append('\n')
            sb.append(completedLines[i].ansiText)
            first = false
        }
        if (activeLine != null) {
            if (!first) sb.append('\n')
            sb.append(activeLine.ansiText)
        } else if (hasTrailingNewline && completedLines.isNotEmpty()) {
            sb.append('\n')
        }
        return if (sb.length <= maxChars) {
            sb.toString()
        } else {
            sb.substring(sb.length - maxChars)
        }
    }

    private fun renderFullText(): String {
        val totalLines = completedLines.size + if (activeLine != null) 1 else 0
        if (totalLines == 0 && droppedLineCount == 0) return ""

        var estimated = (activeLine?.ansiText?.length ?: 0) + 64
        for (i in completedLines.indices) {
            estimated += completedLines[i].ansiText.length + 1
        }
        val out = StringBuilder(estimated)
        if (droppedLineCount > 0) {
            out.append(AnsiStreamSanitizer.formatDroppedLinesNotice(droppedLineCount)).append('\n')
        }
        var first = true
        for (i in completedLines.indices) {
            if (!first) out.append('\n')
            out.append(completedLines[i].ansiText)
            first = false
        }
        if (activeLine != null) {
            if (!first) out.append('\n')
            out.append(activeLine.ansiText)
        } else if (hasTrailingNewline && completedLines.isNotEmpty()) {
            out.append('\n')
        }
        return out.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SanitizedBufferSnapshot) return false
        return version == other.version &&
            droppedLineCount == other.droppedLineCount &&
            hasTrailingNewline == other.hasTrailingNewline &&
            activeLine == other.activeLine &&
            completedLines == other.completedLines
    }

    override fun hashCode(): Int {
        var result = version.hashCode()
        result = 31 * result + droppedLineCount
        result = 31 * result + hasTrailingNewline.hashCode()
        result = 31 * result + (activeLine?.hashCode() ?: 0)
        result = 31 * result + completedLines.size
        return result
    }

    companion object {
        val EMPTY = SanitizedBufferSnapshot(
            version = 0L,
            droppedLineCount = 0,
            completedLines = emptyList(),
            activeLine = null,
            hasTrailingNewline = false,
        )

        fun fromSanitizedText(
            sanitizedText: String,
            maxLines: Int = AnsiStreamSanitizer.DEFAULT_MAX_LINES,
        ): SanitizedBufferSnapshot {
            if (sanitizedText.isEmpty()) return EMPTY
            val trailingNl = sanitizedText.endsWith('\n')
            val body = if (trailingNl) sanitizedText.substring(0, sanitizedText.length - 1) else sanitizedText
            val rawParts = body.split('\n')
            val dropExcess = (rawParts.size - maxLines).coerceAtLeast(0)
            val keptParts = if (dropExcess > 0) rawParts.subList(dropExcess, rawParts.size) else rawParts
            var nextId = 1L
            return if (trailingNl) {
                val committed = ArrayList<SanitizedTerminalLine>(keptParts.size)
                for (part in keptParts) {
                    committed.add(SanitizedTerminalLine(id = nextId++, ansiText = part))
                }
                SanitizedBufferSnapshot(
                    version = nextId,
                    droppedLineCount = dropExcess,
                    completedLines = committed,
                    activeLine = null,
                    hasTrailingNewline = true,
                )
            } else {
                val committedCount = (keptParts.size - 1).coerceAtLeast(0)
                val committed = ArrayList<SanitizedTerminalLine>(committedCount)
                for (i in 0 until committedCount) {
                    committed.add(SanitizedTerminalLine(id = nextId++, ansiText = keptParts[i]))
                }
                val lastPart = keptParts.lastOrNull().orEmpty()
                val active = if (lastPart.isNotEmpty()) {
                    SanitizedTerminalLine(id = nextId, ansiText = lastPart)
                } else {
                    null
                }
                SanitizedBufferSnapshot(
                    version = nextId,
                    droppedLineCount = dropExcess,
                    completedLines = committed,
                    activeLine = active,
                    hasTrailingNewline = false,
                )
            }
        }
    }
}

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
 * - Bounds total buffered lines via [maxLines] and single-line length via [maxLineChars] to protect
 *   mobile memory and layout performance on runaway outputs.
 */
class AnsiStreamSanitizer @JvmOverloads constructor(
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxLineChars: Int = DEFAULT_MAX_LINE_CHARS,
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
    private val completedLines = ArrayDeque<SanitizedTerminalLine>()
    private var cachedCompletedSnapshot: List<SanitizedTerminalLine>? = emptyList()
    private val currentLine = StringBuilder()
    private var currentLineVisibleChars: Int = 0
    private val csiBuffer = StringBuilder()
    private val pendingSgrAfterCr = StringBuilder()
    private var pendingCarriageReturn: Boolean = false
    private var oscCharsConsumed: Int = 0
    private var droppedLineCount: Int = 0
    private var hasTrailingNewline: Boolean = false
    private var nextLineId: Long = 1L
    private var mutationVersion: Long = 0L

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
     * Returns an O(1)-friendly structural line snapshot suitable for incremental UI rendering.
     */
    fun lineSnapshot(): SanitizedBufferSnapshot {
        val committed = cachedCompletedSnapshot ?: ArrayList<SanitizedTerminalLine>(completedLines.size).apply {
            addAll(completedLines)
        }.also {
            cachedCompletedSnapshot = it
        }
        val active = if (currentLine.isNotEmpty()) {
            val activeText = if (pendingSgrAfterCr.isNotEmpty()) {
                StringBuilder(currentLine.length + pendingSgrAfterCr.length)
                    .append(currentLine)
                    .append(pendingSgrAfterCr)
                    .toString()
            } else {
                currentLine.toString()
            }
            SanitizedTerminalLine(id = nextLineId, ansiText = activeText)
        } else {
            null
        }
        if (committed.isEmpty() && active == null && droppedLineCount == 0) {
            return SanitizedBufferSnapshot.EMPTY
        }
        return SanitizedBufferSnapshot(
            version = mutationVersion,
            droppedLineCount = droppedLineCount,
            completedLines = committed,
            activeLine = active,
            hasTrailingNewline = hasTrailingNewline,
        )
    }

    /**
     * Returns the current sanitized ANSI text snapshot, including the active in-progress line.
     */
    fun snapshot(): String = lineSnapshot().fullAnsiText

    /**
     * Resets all internal state so the sanitizer can be reused for a new run.
     */
    fun reset() {
        state = ParserState.NORMAL
        completedLines.clear()
        cachedCompletedSnapshot = emptyList()
        currentLine.clear()
        currentLineVisibleChars = 0
        csiBuffer.clear()
        pendingSgrAfterCr.clear()
        pendingCarriageReturn = false
        oscCharsConsumed = 0
        droppedLineCount = 0
        hasTrailingNewline = false
        nextLineId = 1L
        mutationVersion = 0L
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
                    if (currentLine.length < maxLineChars * 2) {
                        currentLine.append(pendingSgrAfterCr)
                    }
                    pendingSgrAfterCr.clear()
                }
                pendingCarriageReturn = false
                commitCurrentLine()
                hasTrailingNewline = true
                mutationVersion++
            }
            '\b' -> {
                if (!pendingCarriageReturn) {
                    deleteLastVisibleCharFromCurrentLine()
                    mutationVersion++
                }
            }
            '\t' -> {
                applyPendingCarriageReturnOverwrite()
                if (currentLineVisibleChars < maxLineChars) {
                    currentLine.append('\t')
                    currentLineVisibleChars++
                    mutationVersion++
                }
                hasTrailingNewline = false
            }
            else -> {
                val code = ch.code
                // Skip non-printable C0 control bytes and DEL
                if (code < 0x20 || code == 0x7F) return
                applyPendingCarriageReturnOverwrite()
                if (currentLineVisibleChars < maxLineChars) {
                    currentLine.append(ch)
                    currentLineVisibleChars++
                    mutationVersion++
                }
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
                    val normalizedParams = if (params.indexOf(':') >= 0) params.replace(':', ';') else params
                    val seq = "\u001B[${normalizedParams}m"
                    if (pendingCarriageReturn) {
                        if (pendingSgrAfterCr.length < MAX_PENDING_SGR_CHARS) {
                            pendingSgrAfterCr.append(seq)
                            mutationVersion++
                        }
                    } else if (currentLine.length < maxLineChars * 2) {
                        currentLine.append(seq)
                        mutationVersion++
                    }
                }
            }
            'K' -> {
                // Erase-in-line: \u001B[2K clears whole line; \r\u001B[K or \r\u001B[0K clears from col 0
                val trimmed = params.trim()
                if (trimmed == "2" || (pendingCarriageReturn && (trimmed.isEmpty() || trimmed == "0"))) {
                    currentLine.clear()
                    currentLineVisibleChars = 0
                    pendingCarriageReturn = false
                    if (pendingSgrAfterCr.isNotEmpty()) {
                        currentLine.append(pendingSgrAfterCr)
                        pendingSgrAfterCr.clear()
                    }
                    mutationVersion++
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
        currentLineVisibleChars = 0
        if (pendingSgrAfterCr.isNotEmpty()) {
            currentLine.append(pendingSgrAfterCr)
            pendingSgrAfterCr.clear()
        }
        pendingCarriageReturn = false
        mutationVersion++
    }

    private fun commitCurrentLine() {
        val lineText = currentLine.toString().trimEnd(' ')
        currentLine.clear()
        currentLineVisibleChars = 0
        if (completedLines.size >= maxLines) {
            completedLines.removeFirst()
            droppedLineCount++
        }
        completedLines.addLast(SanitizedTerminalLine(id = nextLineId++, ansiText = lineText))
        cachedCompletedSnapshot = null
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
            if (currentLineVisibleChars > 0) {
                currentLineVisibleChars--
            }
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

    companion object {
        private const val ESC: Char = '\u001B'
        private const val BEL: Char = '\u0007'
        private const val MAX_CSI_PARAM_LEN: Int = 64
        private const val MAX_OSC_LEN: Int = 2_048
        private const val MAX_PENDING_SGR_CHARS: Int = 256
        const val DEFAULT_MAX_LINES: Int = 8_000
        const val DEFAULT_MAX_LINE_CHARS: Int = 4_096

        fun formatDroppedLinesNotice(droppedLineCount: Int): String =
            "\u001B[2m... [已省略早期 $droppedLineCount 行终端输出] ...\u001B[0m"

        /**
         * Stateless convenience helper that sanitizes a complete raw terminal transcript.
         */
        @JvmOverloads
        fun sanitize(
            raw: String,
            maxLines: Int = DEFAULT_MAX_LINES,
            maxLineChars: Int = DEFAULT_MAX_LINE_CHARS,
        ): String {
            if (raw.isEmpty()) return ""
            return AnsiStreamSanitizer(maxLines = maxLines, maxLineChars = maxLineChars).apply {
                append(raw)
            }.snapshot()
        }
    }
}
