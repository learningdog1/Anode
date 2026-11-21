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

        @JvmOverloads
        fun fromSanitizedText(
            sanitizedText: String,
            maxLines: Int = AnsiStreamSanitizer.DEFAULT_MAX_LINES,
            maxLineChars: Int = AnsiStreamSanitizer.DEFAULT_MAX_LINE_CHARS,
        ): SanitizedBufferSnapshot {
            if (sanitizedText.isEmpty() || maxLines <= 0) return EMPTY

            // Guard against unsanitized transcripts (e.g. raw \r progress storms, \b, or non-SGR escapes)
            if (AnsiStreamSanitizer.containsUnsanitizedControlOrEscape(sanitizedText)) {
                return AnsiStreamSanitizer(
                    maxLines = maxLines,
                    maxLineChars = maxLineChars,
                ).apply {
                    append(sanitizedText)
                }.lineSnapshot()
            }

            val trailingNl = sanitizedText.endsWith('\n')
            val endExclusive = if (trailingNl) sanitizedText.length - 1 else sanitizedText.length
            val maxRawLineCap = (maxLineChars * 2).coerceAtLeast(maxLineChars)

            // Walk backwards to locate at most `maxLines` trailing lines without splitting the entire string
            val lineStarts = IntArray(maxLines)
            val lineEnds = IntArray(maxLines)
            var keptCount = 0
            var lineEnd = endExclusive
            var cursor = endExclusive - 1
            while (keptCount < maxLines) {
                val nlIdx = if (cursor >= 0) sanitizedText.lastIndexOf('\n', startIndex = cursor) else -1
                val lineStart = nlIdx + 1
                lineStarts[keptCount] = lineStart
                lineEnds[keptCount] = lineEnd
                keptCount++
                if (nlIdx < 0) {
                    cursor = -1
                    break
                }
                lineEnd = nlIdx
                cursor = nlIdx - 1
            }

            // Count dropped lines prior to the earliest kept line in O(droppedChars) with zero allocations
            var dropExcess = 0
            if (cursor >= 0) {
                dropExcess = 1 // The newline right before lineStarts[keptCount - 1]
                for (i in 0..cursor) {
                    if (sanitizedText[i] == '\n') dropExcess++
                }
            }

            fun sliceBoundedLine(start: Int, end: Int): String {
                val len = end - start
                if (len <= 0) return ""
                return if (len <= maxRawLineCap) {
                    sanitizedText.substring(start, end)
                } else {
                    sanitizedText.substring(start, start + maxRawLineCap) + "\u001B[0m"
                }
            }

            var nextId = 1L
            return if (trailingNl) {
                val committed = ArrayList<SanitizedTerminalLine>(keptCount)
                for (rev in (keptCount - 1) downTo 0) {
                    val part = sliceBoundedLine(lineStarts[rev], lineEnds[rev])
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
                val committedCount = (keptCount - 1).coerceAtLeast(0)
                val committed = ArrayList<SanitizedTerminalLine>(committedCount)
                for (rev in (keptCount - 1) downTo 1) {
                    val part = sliceBoundedLine(lineStarts[rev], lineEnds[rev])
                    committed.add(SanitizedTerminalLine(id = nextId++, ansiText = part))
                }
                val lastPart = if (keptCount > 0) sliceBoundedLine(lineStarts[0], lineEnds[0]) else ""
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
 * - Collapses carriage-return (`\r`) progress bar & spinner overwrites (`curl`, `dd`, `fio`, `yabs`,
 *   `NodeQuality` / `Check.Place`) into a single clean line instead of thousands of duplicate lines.
 * - Honors multi-line cursor-up + erase-line sequences (`\u001B[1A\u001B[2K`, `\u001B[nA\u001B[0J`)
 *   used by `NodeQuality` (`Check.Place` `ADLines`) to clear temporary TrueColor sponsor banners
 *   before printing the final benchmark report.
 * - Preserves standard ANSI SGR color/style sequences (`\u001B[...m`) verbatim for
 *   `AnsiAndCodeFormatter.parseAnsiTerminalText`.
 * - Strips non-SGR CSI cursor/screen control sequences (`\u001B[?25l`, `\u001B[2J`, `\u001B[K`,
 *   etc.), OSC title/hyperlink sequences (`\u001B]...\u0007`), charset switches, and unprintable
 *   control bytes — even when escape sequences span across TCP/PTY chunk boundaries.
 * - Bounds total buffered lines via [maxLines], single-line length via [maxLineChars], and total
 *   retained characters via [maxTotalChars] to protect mobile memory and layout performance.
 */
class AnsiStreamSanitizer @JvmOverloads constructor(
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxLineChars: Int = DEFAULT_MAX_LINE_CHARS,
    private val maxTotalChars: Int = DEFAULT_MAX_TOTAL_CHARS,
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
    private var completedTotalChars: Int = 0
    private var cachedCompletedSnapshot: List<SanitizedTerminalLine>? = emptyList()
    private val currentLine = StringBuilder()
    private var currentLineVisibleChars: Int = 0
    private val csiBuffer = StringBuilder()
    private val pendingSgrAfterCr = StringBuilder()
    private var pendingCarriageReturn: Boolean = false
    private var cursorRewindLines: Int = 0
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
        completedTotalChars = 0
        cachedCompletedSnapshot = emptyList()
        currentLine.clear()
        currentLineVisibleChars = 0
        csiBuffer.clear()
        pendingSgrAfterCr.clear()
        pendingCarriageReturn = false
        cursorRewindLines = 0
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
                cursorRewindLines = 0
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
                cursorRewindLines = 0
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
                cursorRewindLines = 0
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
            handleCsiCommand(params = csiBuffer, finalByte = ch)
            csiBuffer.clear()
            state = ParserState.NORMAL
            return
        }
        // Malformed CSI sequence: abort and re-process byte in NORMAL state
        csiBuffer.clear()
        state = ParserState.NORMAL
        processNormalChar(ch)
    }

    private fun handleCsiCommand(params: CharSequence, finalByte: Char) {
        when (finalByte) {
            'm' -> {
                // Preserve SGR style/color codes compatible with AnsiAndCodeFormatter without String allocations
                if (isStandardSgrParams(params)) {
                    val isReset = params.isEmpty() || (params.length == 1 && params[0] == '0')
                    val seqLen = params.length + 3
                    if (pendingCarriageReturn) {
                        if (isReset) {
                            pendingSgrAfterCr.clear()
                        }
                        if (pendingSgrAfterCr.length + seqLen <= MAX_PENDING_SGR_CHARS) {
                            appendNormalizedSgrSequence(pendingSgrAfterCr, params)
                            mutationVersion++
                        }
                    } else if (currentLine.length + seqLen <= maxLineChars * 2 || isReset) {
                        appendNormalizedSgrSequence(currentLine, params)
                        mutationVersion++
                    }
                }
            }
            'A', 'F' -> {
                // Cursor Up (`\u001B[<n>A`) / Cursor Previous Line (`\u001B[<n>F`)
                val step = parsePositiveIntParam(params, defaultValue = 1)
                if (finalByte == 'F') {
                    pendingCarriageReturn = true
                }
                val maxRewind = completedLines.size.coerceAtMost(MAX_CURSOR_REWIND_LINES)
                cursorRewindLines = (cursorRewindLines + step).coerceAtMost(maxRewind)
            }
            'B', 'E' -> {
                // Cursor Down (`\u001B[<n>B`) / Cursor Next Line (`\u001B[<n>E`)
                val step = parsePositiveIntParam(params, defaultValue = 1)
                if (finalByte == 'E') {
                    pendingCarriageReturn = true
                }
                cursorRewindLines = (cursorRewindLines - step).coerceAtLeast(0)
            }
            'K' -> {
                // Erase-in-line: \u001B[2K clears whole line; \r\u001B[K or \r\u001B[0K clears from col 0
                val eraseMode = parseEraseModeParam(params)
                if (eraseMode == 2 || ((pendingCarriageReturn || currentLine.isEmpty()) && (eraseMode == -1 || eraseMode == 0))) {
                    if ((currentLine.isEmpty() || pendingCarriageReturn) &&
                        cursorRewindLines == 1 &&
                        completedLines.isNotEmpty()
                    ) {
                        currentLine.clear()
                        currentLineVisibleChars = 0
                        pendingCarriageReturn = false
                        removeLastCompletedLine()
                        cursorRewindLines = 0
                        if (pendingSgrAfterCr.isNotEmpty()) {
                            currentLine.append(pendingSgrAfterCr)
                            pendingSgrAfterCr.clear()
                        }
                        mutationVersion++
                    } else {
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
            }
            'J' -> {
                // Erase-in-display from cursor to end (`\u001B[J` / `\u001B[0J`) after cursor-up rewind
                val eraseMode = parseEraseModeParam(params)
                if ((eraseMode == -1 || eraseMode == 0) && cursorRewindLines > 0) {
                    repeat(cursorRewindLines.coerceAtMost(completedLines.size)) {
                        removeLastCompletedLine()
                    }
                    cursorRewindLines = 0
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
                // Strip other cursor movement, hide/show cursor (?25l/?25h), clear screen (2J/3J), alt buffer, etc.
            }
        }
    }

    private fun appendNormalizedSgrSequence(out: StringBuilder, params: CharSequence) {
        out.append(ESC).append('[')
        for (i in 0 until params.length) {
            val ch = params[i]
            out.append(if (ch == ':') ';' else ch)
        }
        out.append('m')
    }

    private fun parsePositiveIntParam(params: CharSequence, defaultValue: Int): Int {
        var start = 0
        var end = params.length
        while (start < end && params[start] == ' ') start++
        while (end > start && params[end - 1] == ' ') end--
        if (start >= end) return defaultValue
        var acc = 0
        for (i in start until end) {
            val ch = params[i]
            if (ch !in '0'..'9') return defaultValue
            acc = if (acc > 10_000) 10_000 else acc * 10 + (ch - '0')
        }
        return if (acc > 0) acc else defaultValue
    }

    private fun parseEraseModeParam(params: CharSequence): Int {
        var start = 0
        var end = params.length
        while (start < end && params[start] == ' ') start++
        while (end > start && params[end - 1] == ' ') end--
        if (start >= end) return -1
        if (end - start == 1 && params[start] in '0'..'3') {
            return params[start] - '0'
        }
        return -2
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
        while (completedLines.size >= maxLines ||
            (maxTotalChars > 0 && completedLines.isNotEmpty() && completedTotalChars + lineText.length > maxTotalChars)
        ) {
            removeFirstCompletedLine()
            droppedLineCount++
        }
        completedLines.addLast(SanitizedTerminalLine(id = nextLineId++, ansiText = lineText))
        completedTotalChars += lineText.length
        cachedCompletedSnapshot = null
    }

    private fun removeFirstCompletedLine() {
        val removed = completedLines.removeFirst()
        completedTotalChars = (completedTotalChars - removed.ansiText.length).coerceAtLeast(0)
        cachedCompletedSnapshot = null
    }

    private fun removeLastCompletedLine() {
        val removed = completedLines.removeLast()
        completedTotalChars = (completedTotalChars - removed.ansiText.length).coerceAtLeast(0)
        cachedCompletedSnapshot = null
    }

    private fun deleteLastVisibleCharFromCurrentLine() {
        if (currentLine.isEmpty()) return
        // Avoid slicing through a trailing SGR sequence `\u001B[...m`
        var end = currentLine.length
        while (end > 0) {
            if (currentLine[end - 1] == 'm') {
                val escIdx = currentLine.lastIndexOf("\u001B[", startIndex = end - 1)
                if (escIdx >= 0 && isStandardSgrParamsRange(currentLine, escIdx + 2, end - 1)) {
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

    private fun isStandardSgrParams(params: CharSequence): Boolean =
        isStandardSgrParamsRange(params, 0, params.length)

    private fun isStandardSgrParamsRange(cs: CharSequence, start: Int, endExclusive: Int): Boolean {
        for (i in start until endExclusive) {
            val c = cs[i]
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
        private const val MAX_CURSOR_REWIND_LINES: Int = 256
        const val DEFAULT_MAX_LINES: Int = 8_000
        const val DEFAULT_MAX_LINE_CHARS: Int = 4_096
        const val DEFAULT_MAX_TOTAL_CHARS: Int = 512_000

        fun formatDroppedLinesNotice(droppedLineCount: Int): String =
            "\u001B[2m... [已省略早期 $droppedLineCount 行终端输出] ...\u001B[0m"

        /**
         * Fast linear scan returning true if [text] contains carriage-return overwrites (`\r`),
         * backspaces (`\b`), or non-SGR escape sequences that require state-machine sanitization.
         */
        internal fun containsUnsanitizedControlOrEscape(text: String): Boolean {
            var i = 0
            val n = text.length
            while (i < n) {
                when (val ch = text[i]) {
                    '\r', '\b', BEL -> return true
                    ESC -> {
                        if (i + 1 >= n || text[i + 1] != '[') return true
                        var j = i + 2
                        var validSgr = false
                        while (j < n && j <= i + MAX_CSI_PARAM_LEN + 2) {
                            val c = text[j]
                            if (c == 'm') {
                                validSgr = true
                                i = j
                                break
                            }
                            if (c !in '0'..'9' && c != ';') return true
                            j++
                        }
                        if (!validSgr) return true
                    }
                    else -> {
                        if (ch != '\n' && ch != '\t' && (ch.code < 0x20 || ch.code == 0x7F)) {
                            return true
                        }
                    }
                }
                i++
            }
            return false
        }

        /**
         * Stateless convenience helper that sanitizes a complete raw terminal transcript.
         */
        @JvmOverloads
        fun sanitize(
            raw: String,
            maxLines: Int = DEFAULT_MAX_LINES,
            maxLineChars: Int = DEFAULT_MAX_LINE_CHARS,
            maxTotalChars: Int = DEFAULT_MAX_TOTAL_CHARS,
        ): String {
            if (raw.isEmpty()) return ""
            return AnsiStreamSanitizer(
                maxLines = maxLines,
                maxLineChars = maxLineChars,
                maxTotalChars = maxTotalChars,
            ).apply {
                append(raw)
            }.snapshot()
        }
    }
}
