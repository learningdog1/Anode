package com.nodeseek.app.ui.ssh

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.nodeseek.app.domain.ssh.SshPtySize
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Font sizing strategy for the lightweight SSH ANSI terminal viewport.
 */
enum class TerminalFitMode {
    /** Automatically scales font size down (until [TerminalFontSpec.minAutoFitFontSizeSp]) so wide tables fit without wrapping. */
    AUTO_FIT,

    /** Uses the user's manual font scale (via two-finger pinch or +/- zoom controls). */
    FIXED,
}

/**
 * Configurable font bounds and line-height ratio used by [SshTerminalAutoFit].
 */
@Immutable
data class TerminalFontSpec(
    val baseFontSizeSp: Float = DEFAULT_BASE_FONT_SIZE_SP,
    val minAutoFitFontSizeSp: Float = DEFAULT_MIN_AUTO_FIT_FONT_SIZE_SP,
    val minManualFontSizeSp: Float = DEFAULT_MIN_MANUAL_FONT_SIZE_SP,
    val maxManualFontSizeSp: Float = DEFAULT_MAX_MANUAL_FONT_SIZE_SP,
    val lineHeightRatio: Float = DEFAULT_LINE_HEIGHT_RATIO,
) {
    val minManualScale: Float
        get() = minManualFontSizeSp / baseFontSizeSp

    val maxManualScale: Float
        get() = maxManualFontSizeSp / baseFontSizeSp

    companion object {
        const val DEFAULT_BASE_FONT_SIZE_SP: Float = 11.5f
        const val DEFAULT_MIN_AUTO_FIT_FONT_SIZE_SP: Float = 6.0f
        const val DEFAULT_MIN_MANUAL_FONT_SIZE_SP: Float = 5.75f // 0.50x
        const val DEFAULT_MAX_MANUAL_FONT_SIZE_SP: Float = 20.7f // 1.80x
        const val DEFAULT_LINE_HEIGHT_RATIO: Float = 16.5f / 11.5f
    }
}

/**
 * Resolved typography, scrollability, and remote PTY geometry for a terminal viewport snapshot.
 */
@Immutable
data class TerminalLayoutSolution(
    val fitMode: TerminalFitMode,
    val effectiveFontSizeSp: Float,
    val effectiveLineHeightSp: Float,
    val effectiveFontScale: Float,
    val maxLineColumns: Int,
    val visibleColumnsAtFont: Int,
    val visibleRowsAtFont: Int,
    val requiresHorizontalScroll: Boolean,
    val hitMinReadableFloor: Boolean,
    val ptySize: SshPtySize,
)

/**
 * Pure terminal display-width measurement, auto-fit font solver, and PTY dimension calculator.
 *
 * All core functions are pure Kotlin (no Android `Context` or Compose runtime dependency) so they
 * can be unit-tested deterministically across phone, foldable, tablet, and landscape profiles.
 */
object SshTerminalAutoFit {
    const val DEFAULT_TAB_WIDTH: Int = 8
    private const val AUTO_FIT_COLUMN_SAFETY_SLACK: Float = 0.5f

    /**
     * Measures monospace display columns of a line whose ANSI escape sequences have already been
     * stripped (e.g. `AnnotatedString.text` from [SshTerminalConsoleBuffer]).
     */
    fun measurePlainLineColumns(
        plainLine: String,
        tabWidth: Int = DEFAULT_TAB_WIDTH,
    ): Int = measureColumnsInternal(
        text = plainLine,
        stripAnsi = false,
        tabWidth = tabWidth,
    )

    /**
     * Measures monospace display columns of a single line, ignoring ANSI CSI / OSC / ESC control
     * sequences and accurately accounting for CJK fullwidth glyphs, emoji clusters, and tabs.
     */
    fun measureTerminalLineColumns(
        ansiLine: String,
        tabWidth: Int = DEFAULT_TAB_WIDTH,
    ): Int = measureColumnsInternal(
        text = ansiLine,
        stripAnsi = true,
        tabWidth = tabWidth,
    )

    /**
     * Computes the maximum display column width across all newline-delimited lines of [ansiText].
     */
    fun measureMaxTerminalColumns(
        ansiText: String,
        tabWidth: Int = DEFAULT_TAB_WIDTH,
    ): Int {
        if (ansiText.isEmpty()) return 0
        var maxCols = 0
        var lineStart = 0
        val len = ansiText.length
        for (i in 0 until len) {
            if (ansiText[i] == '\n') {
                val cols = measureColumnsInternal(
                    text = ansiText,
                    startIndex = lineStart,
                    endIndex = i,
                    stripAnsi = true,
                    tabWidth = tabWidth,
                )
                if (cols > maxCols) maxCols = cols
                lineStart = i + 1
            }
        }
        if (lineStart < len) {
            val cols = measureColumnsInternal(
                text = ansiText,
                startIndex = lineStart,
                endIndex = len,
                stripAnsi = true,
                tabWidth = tabWidth,
            )
            if (cols > maxCols) maxCols = cols
        }
        return maxCols
    }

    /**
     * Solves optimal readable font size, horizontal-scroll necessity, and remote PTY geometry
     * (`cols`, `rows`, `widthPx`, `heightPx`) for the given viewport metrics and [maxLineColumns].
     */
    fun solveLayout(
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        charWidthPxPerSp: Float,
        lineHeightPxPerSp: Float,
        maxLineColumns: Int,
        fitMode: TerminalFitMode,
        manualFontScale: Float,
        fontSpec: TerminalFontSpec = TerminalFontSpec(),
    ): TerminalLayoutSolution {
        val safeBaseSp = fontSpec.baseFontSizeSp.coerceAtLeast(1f)
        val safeMinAutoSp = fontSpec.minAutoFitFontSizeSp.coerceIn(1f, safeBaseSp)
        val safeCharPerSp = charWidthPxPerSp.takeIf { it.isFinite() && it > 0.01f } ?: (0.6f * 3f)
        val safeLinePerSp = lineHeightPxPerSp.takeIf { it.isFinite() && it > 0.01f }
            ?: (fontSpec.lineHeightRatio * 3f)
        val safeColsTarget = maxLineColumns.coerceAtLeast(0)

        if (!viewportWidthPx.isFinite() || viewportWidthPx <= 1f ||
            !viewportHeightPx.isFinite() || viewportHeightPx <= 1f
        ) {
            val fallbackScale = if (fitMode == TerminalFitMode.FIXED) {
                manualFontScale.coerceIn(fontSpec.minManualScale, fontSpec.maxManualScale)
            } else {
                1.0f
            }
            val fallbackSp = safeBaseSp * fallbackScale
            return TerminalLayoutSolution(
                fitMode = fitMode,
                effectiveFontSizeSp = fallbackSp,
                effectiveLineHeightSp = fallbackSp * fontSpec.lineHeightRatio,
                effectiveFontScale = fallbackScale,
                maxLineColumns = safeColsTarget,
                visibleColumnsAtFont = SshPtySize.MIN_PTY_COLS,
                visibleRowsAtFont = SshPtySize.MIN_PTY_ROWS,
                requiresHorizontalScroll = false,
                hitMinReadableFloor = false,
                ptySize = SshPtySize(
                    cols = com.nodeseek.app.domain.ssh.SshConnectionRequest.DEFAULT_PTY_COLS,
                    rows = com.nodeseek.app.domain.ssh.SshConnectionRequest.DEFAULT_PTY_ROWS,
                ),
            )
        }

        val effectiveFontSizeSp: Float
        val hitFloor: Boolean

        when (fitMode) {
            TerminalFitMode.FIXED -> {
                val clampedScale = manualFontScale.coerceIn(fontSpec.minManualScale, fontSpec.maxManualScale)
                effectiveFontSizeSp = ((safeBaseSp * clampedScale) * 100f).roundToInt() / 100f
                hitFloor = effectiveFontSizeSp <= safeMinAutoSp + 0.01f
            }
            TerminalFitMode.AUTO_FIT -> {
                if (safeColsTarget <= 0) {
                    effectiveFontSizeSp = safeBaseSp
                    hitFloor = false
                } else {
                    val requiredWidthAtBase = (safeColsTarget + AUTO_FIT_COLUMN_SAFETY_SLACK) *
                        safeBaseSp * safeCharPerSp
                    if (requiredWidthAtBase <= viewportWidthPx) {
                        effectiveFontSizeSp = safeBaseSp
                        hitFloor = false
                    } else {
                        val idealSp = viewportWidthPx / ((safeColsTarget + AUTO_FIT_COLUMN_SAFETY_SLACK) * safeCharPerSp)
                        // Quantize downward in 0.1sp steps so rounding never causes 1px right-edge clipping
                        val quantizedDownSp = floor(idealSp * 10f) / 10f
                        if (quantizedDownSp < safeMinAutoSp) {
                            effectiveFontSizeSp = safeMinAutoSp
                            hitFloor = true
                        } else {
                            effectiveFontSizeSp = quantizedDownSp.coerceAtMost(safeBaseSp)
                            hitFloor = false
                        }
                    }
                }
            }
        }

        val cellWidthPx = effectiveFontSizeSp * safeCharPerSp
        val cellHeightPx = (effectiveFontSizeSp * safeLinePerSp).coerceAtLeast(cellWidthPx)
        val visibleCols = floor(viewportWidthPx / cellWidthPx).toInt().coerceAtLeast(1)
        val visibleRows = floor(viewportHeightPx / cellHeightPx).toInt().coerceAtLeast(1)
        val requiresHorizontalScroll = safeColsTarget > visibleCols
        val ptySize = calculatePtySize(
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            cellWidthPx = cellWidthPx,
            lineHeightPx = cellHeightPx,
        )

        return TerminalLayoutSolution(
            fitMode = fitMode,
            effectiveFontSizeSp = effectiveFontSizeSp,
            effectiveLineHeightSp = effectiveFontSizeSp * fontSpec.lineHeightRatio,
            effectiveFontScale = effectiveFontSizeSp / safeBaseSp,
            maxLineColumns = safeColsTarget,
            visibleColumnsAtFont = visibleCols,
            visibleRowsAtFont = visibleRows,
            requiresHorizontalScroll = requiresHorizontalScroll,
            hitMinReadableFloor = hitFloor,
            ptySize = ptySize,
        )
    }

    /**
     * Computes clamped SSH PTY dimensions (`cols`, `rows`, `widthPx`, `heightPx`) for the active
     * viewport pixel bounds and rendered cell dimensions.
     */
    fun calculatePtySize(
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        cellWidthPx: Float,
        lineHeightPx: Float,
    ): SshPtySize {
        if (!viewportWidthPx.isFinite() || viewportWidthPx <= 0f ||
            !viewportHeightPx.isFinite() || viewportHeightPx <= 0f ||
            !cellWidthPx.isFinite() || cellWidthPx <= 0.1f ||
            !lineHeightPx.isFinite() || lineHeightPx <= 0.1f
        ) {
            return SshPtySize(
                cols = com.nodeseek.app.domain.ssh.SshConnectionRequest.DEFAULT_PTY_COLS,
                rows = com.nodeseek.app.domain.ssh.SshConnectionRequest.DEFAULT_PTY_ROWS,
            )
        }
        val rawCols = floor(viewportWidthPx / cellWidthPx).toInt()
        val rawRows = floor(viewportHeightPx / lineHeightPx).toInt()
        return SshPtySize(
            cols = rawCols,
            rows = rawRows,
            widthPx = viewportWidthPx.roundToInt(),
            heightPx = viewportHeightPx.roundToInt(),
        ).normalized()
    }

    /**
     * Returns monospace display column width for an isolated Unicode code point (`0`, `1`, or `2`).
     */
    fun codePointDisplayWidth(cp: Int): Int {
        if (cp < 0x20 || cp in 0x7F..0x9F) return 0
        if (isZeroWidthCodePoint(cp)) return 0
        return if (isWideEastAsianOrEmoji(cp)) 2 else 1
    }

    private fun measureColumnsInternal(
        text: String,
        startIndex: Int = 0,
        endIndex: Int = text.length,
        stripAnsi: Boolean,
        tabWidth: Int,
    ): Int {
        val effectiveTab = tabWidth.coerceAtLeast(1)
        var cols = 0
        var i = startIndex
        var afterZwj = false
        var pendingRegionalIndicator = false
        var prevBaseWidth = 0
        var prevBaseCodePoint = 0

        while (i < endIndex) {
            val ch = text[i]
            if (stripAnsi && ch == '\u001B') {
                i = skipEscapeSequence(text, i, endIndex)
                continue
            }

            // ASCII printable fast path
            if (ch.code in 0x20..0x7E) {
                if (afterZwj) {
                    afterZwj = false
                } else {
                    cols += 1
                    prevBaseWidth = 1
                    prevBaseCodePoint = ch.code
                }
                pendingRegionalIndicator = false
                i++
                continue
            }

            when (ch) {
                '\r', '\n' -> {
                    afterZwj = false
                    pendingRegionalIndicator = false
                    prevBaseWidth = 0
                    prevBaseCodePoint = 0
                    i++
                    continue
                }
                '\t' -> {
                    val advance = effectiveTab - (cols % effectiveTab)
                    cols += advance
                    afterZwj = false
                    pendingRegionalIndicator = false
                    prevBaseWidth = 1
                    prevBaseCodePoint = '\t'.code
                    i++
                    continue
                }
            }

            val cp = Character.codePointAt(text, i)
            i += Character.charCount(cp)

            if (cp < 0x20 || cp in 0x7F..0x9F) {
                continue
            }
            if (cp == 0x200D) {
                // Zero-Width Joiner merges the subsequent emoji into the active grapheme cluster
                afterZwj = true
                continue
            }
            if (cp == 0xFE0E) {
                // Text presentation selector
                afterZwj = false
                pendingRegionalIndicator = false
                continue
            }
            if (cp == 0xFE0F) {
                // Emoji presentation selector upgrades eligible symbol glyphs from 1 -> 2 columns
                if (!afterZwj && prevBaseWidth == 1 && isEmojiPresentationUpgradeable(prevBaseCodePoint)) {
                    cols += 1
                    prevBaseWidth = 2
                }
                afterZwj = false
                continue
            }
            if (cp == 0x20E3) {
                // Combining enclosing keycap (e.g. 1️⃣, #️⃣, *️⃣)
                if (prevBaseWidth == 1 &&
                    (prevBaseCodePoint in '0'.code..'9'.code || prevBaseCodePoint == '#'.code || prevBaseCodePoint == '*'.code)
                ) {
                    cols += 1
                    prevBaseWidth = 2
                }
                afterZwj = false
                pendingRegionalIndicator = false
                continue
            }
            if (cp in 0x1F3FB..0x1F3FF) {
                // Emoji skin-tone modifier combines with preceding base emoji
                afterZwj = false
                continue
            }
            if (cp in 0x1F1E6..0x1F1FF) {
                // Regional indicator flag pairs (e.g. 🇨🇳, 🇺🇸) form a single 2-column glyph
                if (pendingRegionalIndicator) {
                    pendingRegionalIndicator = false
                } else {
                    if (!afterZwj) {
                        cols += 2
                        prevBaseWidth = 2
                        prevBaseCodePoint = cp
                    }
                    pendingRegionalIndicator = true
                }
                afterZwj = false
                continue
            }
            if (isZeroWidthCodePoint(cp)) {
                continue
            }

            pendingRegionalIndicator = false
            val w = if (isWideEastAsianOrEmoji(cp)) 2 else 1
            if (afterZwj) {
                afterZwj = false
            } else {
                cols += w
                prevBaseWidth = w
                prevBaseCodePoint = cp
            }
        }
        return cols
    }

    private fun skipEscapeSequence(text: String, escIndex: Int, endIndex: Int): Int {
        if (escIndex + 1 >= endIndex) return endIndex
        return when (text[escIndex + 1]) {
            '[' -> {
                var idx = escIndex + 2
                while (idx < endIndex && text[idx].code in 0x20..0x3F) {
                    idx++
                }
                if (idx < endIndex && text[idx].code in 0x40..0x7E) {
                    idx + 1
                } else {
                    idx
                }
            }
            ']', 'P', 'X', '^', '_' -> {
                var idx = escIndex + 2
                while (idx < endIndex) {
                    val c = text[idx]
                    if (c == '\u0007') {
                        return idx + 1
                    }
                    if (c == '\u001B' && idx + 1 < endIndex && text[idx + 1] == '\\') {
                        return idx + 2
                    }
                    idx++
                }
                endIndex
            }
            '(', ')', '*', '+', '#', ' ', '%' -> (escIndex + 3).coerceAtMost(endIndex)
            else -> (escIndex + 2).coerceAtMost(endIndex)
        }
    }

    private fun isZeroWidthCodePoint(cp: Int): Boolean {
        if (cp in 0x0300..0x036F ||
            cp in 0x1AB0..0x1AFF ||
            cp in 0x1DC0..0x1DFF ||
            cp in 0x20D0..0x20FF ||
            cp in 0xFE00..0xFE0F ||
            cp in 0xFE20..0xFE2F ||
            cp in 0xE0100..0xE01EF
        ) {
            return true
        }
        if (cp in 0x200B..0x200F ||
            cp in 0x202A..0x202E ||
            cp in 0x2060..0x206F ||
            cp == 0xFEFF
        ) {
            return true
        }
        return when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK,
            Character.ENCLOSING_MARK,
            Character.FORMAT,
            -> true
            else -> false
        }
    }

    private fun isEmojiPresentationUpgradeable(cp: Int): Boolean {
        if (cp == 0x00A9 || cp == 0x00AE) return true
        // Box drawing (2500..257F) and block elements (2580..259F) always remain single-column
        if (cp in 0x2500..0x259F) return false
        return cp in 0x203C..0x3299
    }

    private fun isWideEastAsianOrEmoji(cp: Int): Boolean {
        // Explicit narrow check for Box Drawing & Block Elements (U+2500..U+259F) and common symbols
        if (cp in 0x2500..0x259F) return false
        return cp in 0x1100..0x115F ||
            cp == 0x231A || cp == 0x231B ||
            cp == 0x2329 || cp == 0x232A ||
            cp in 0x23E9..0x23EC || cp == 0x23F0 || cp == 0x23F3 ||
            cp in 0x25FD..0x25FE ||
            cp in 0x2614..0x2615 ||
            cp in 0x2648..0x2653 ||
            cp == 0x267F || cp == 0x2693 || cp == 0x26A1 ||
            cp in 0x26AA..0x26AB ||
            cp in 0x26BD..0x26BE ||
            cp in 0x26C4..0x26C5 ||
            cp == 0x26CE || cp == 0x26D4 || cp == 0x26EA ||
            cp in 0x26F2..0x26F3 || cp == 0x26F5 || cp == 0x26FA || cp == 0x26FD ||
            cp == 0x2705 || cp in 0x270A..0x270B || cp == 0x2728 ||
            cp == 0x274C || cp == 0x274E || cp in 0x2753..0x2755 || cp == 0x2757 ||
            cp in 0x2795..0x2797 || cp == 0x27B0 || cp == 0x27BF ||
            cp in 0x2B1B..0x2B1C || cp == 0x2B50 || cp == 0x2B55 ||
            cp in 0x2E80..0x303E ||
            cp in 0x3040..0x30FF ||
            cp in 0x3100..0x32FF ||
            cp in 0x3300..0x4DBF ||
            cp in 0x4E00..0xA4CF ||
            cp in 0xA960..0xA97F ||
            cp in 0xAC00..0xD7A3 ||
            cp in 0xF900..0xFAFF ||
            cp in 0xFE10..0xFE19 ||
            cp in 0xFE30..0xFE6F ||
            cp in 0xFF00..0xFF60 ||
            cp in 0xFFE0..0xFFE6 ||
            cp in 0x16FE0..0x18D08 ||
            cp in 0x1B000..0x1B2FF ||
            cp == 0x1F004 || cp == 0x1F0CF || cp == 0x1F18E ||
            cp in 0x1F191..0x1F19A ||
            cp in 0x1F200..0x1F2FF ||
            cp in 0x1F300..0x1FAFF ||
            cp in 0x20000..0x2FFFD ||
            cp in 0x30000..0x3FFFD
    }
}

/**
 * Measures actual monospace character width & line height in pixels per `1.sp` on the current
 * device font/density configuration using [TextMeasurer].
 */
internal fun measureTerminalFontUnitMetricsPx(
    textMeasurer: TextMeasurer,
    baseStyle: TextStyle,
    density: Density,
    lineHeightRatio: Float = TerminalFontSpec.DEFAULT_LINE_HEIGHT_RATIO,
): Pair<Float, Float> {
    val refSp = 100f
    val sample = "MMMMMMMMMM0123456789"
    val layout = runCatching {
        textMeasurer.measure(
            text = sample,
            style = baseStyle.copy(
                fontSize = refSp.sp,
                lineHeight = (refSp * lineHeightRatio).sp,
            ),
            softWrap = false,
            maxLines = 1,
        )
    }.getOrNull()

    val spToPxFallback = density.density * density.fontScale
    val charWidthPxPerSp = if (layout != null && layout.size.width > 0) {
        (layout.size.width.toFloat() / sample.length) / refSp
    } else {
        (0.60f * spToPxFallback).coerceAtLeast(0.5f)
    }
    val lineHeightPxPerSp = if (layout != null && layout.size.height > 0) {
        layout.size.height.toFloat() / refSp
    } else {
        (lineHeightRatio * spToPxFallback).coerceAtLeast(1.0f)
    }
    return charWidthPxPerSp to lineHeightPxPerSp
}

/**
 * Detects two-finger pinch gestures on [PointerEventPass.Initial] without intercepting
 * single-finger vertical or horizontal scrolling.
 */
internal fun Modifier.terminalPinchToZoom(
    onZoomFactor: (Float) -> Unit,
): Modifier = composed {
    val currentOnZoomFactor by rememberUpdatedState(onZoomFactor)
    pointerInput(Unit) {
        val touchSlop = viewConfiguration.touchSlop
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var pastTouchSlop = false
            var zoomAcc = 1f
            do {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                val pressedCount = event.changes.count { it.pressed }
                if (pressedCount >= 2) {
                    val zoom = event.calculateZoom()
                    if (!pastTouchSlop && zoom.isFinite() && zoom > 0f) {
                        zoomAcc *= zoom
                        val centroidSize = event.calculateCentroidSize(useCurrent = false)
                        val zoomMotion = abs(1f - zoomAcc) * centroidSize
                        if (zoomMotion > touchSlop || abs(1f - zoomAcc) > 0.04f) {
                            pastTouchSlop = true
                        }
                    }
                    if (pastTouchSlop) {
                        if (zoom.isFinite() && zoom > 0f && abs(zoom - 1f) > 0.002f) {
                            currentOnZoomFactor(zoom)
                        }
                        event.changes.forEach { it.consume() }
                    }
                } else if (pastTouchSlop) {
                    // Consume remaining finger lift so ending a pinch never triggers a scroll fling
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
