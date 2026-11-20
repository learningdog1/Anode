package com.nodeseek.app.ui.ssh

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.nodeseek.app.domain.ssh.AnsiStreamSanitizer
import com.nodeseek.app.domain.ssh.IpPrivacyMasker
import com.nodeseek.app.domain.ssh.SanitizedBufferSnapshot
import com.nodeseek.app.ui.components.AnsiAndCodeFormatter
import com.nodeseek.app.ui.components.AnsiSgrState
import kotlinx.coroutines.flow.StateFlow

/**
 * Single pre-parsed ANSI line ready for virtualized `LazyColumn` rendering.
 *
 * [id] is monotonically assigned by [AnsiStreamSanitizer] (`>= 1L` for content lines, `0L` for the
 * dropped-lines notice row) so Compose reuses item slots without recomposing unchanged rows.
 */
@Immutable
data class RenderedTerminalLine(
    val id: Long,
    val styledText: AnnotatedString,
)

/**
 * Combined output of [SshTerminalConsoleBuffer]: virtualized styled lines paired with the
 * incrementally maintained maximum display column width across buffered content lines.
 */
@Immutable
data class TerminalRenderOutput(
    val lines: List<RenderedTerminalLine> = emptyList(),
    val maxLineColumns: Int = 0,
)

/**
 * High-frequency terminal stream payload isolated from [LiteSshUiState] so off-screen sections of
 * `LiteSshScreen` and root navigation never recompose when SSH output chunks arrive.
 */
@Immutable
data class SshConsoleStreamState(
    val runGeneration: Long = 0L,
    val bufferSnapshot: SanitizedBufferSnapshot = SanitizedBufferSnapshot.EMPTY,
    val serverHost: String? = null,
) {
    val isEmpty: Boolean
        get() = bufferSnapshot.isEmpty
}

/**
 * `@Stable` wrapper around [StateFlow] so Compose marks `AnsiTerminalConsoleCard` and
 * `AnsiTerminalViewport` as fully skippable across 1Hz elapsed-clock updates.
 */
@Stable
class SshConsoleStreamHandle(
    val flow: StateFlow<SshConsoleStreamState>,
)

/**
 * Lightweight O(1) metadata describing export readiness for `LiteSshExportDock` without eagerly
 * running [IpPrivacyMasker] or `SshBenchmarkPostFormatter` on every recomposition.
 */
@Immutable
data class SshExportSummaryMeta(
    val runCount: Int,
    val usesTabsLayout: Boolean,
)

/**
 * Incremental ring-buffered ANSI + IP-masking line formatter.
 *
 * Guarantees:
 * - Committed terminal lines are masked and parsed into [AnnotatedString]s **at most once** per
 *   `(maskIp, knownServerHost, defaultForeground)` configuration.
 * - Preserves multi-line [AnsiSgrState] color/weight continuity across line breaks.
 * - Carriage-return (`\r`) progress bar updates re-parse **only** the single active trailing line.
 * - Bounds total rendered rows to [maxDisplayLines] with a stable notice row when truncated.
 */
class SshTerminalConsoleBuffer(
    private val maxDisplayLines: Int = DEFAULT_MAX_DISPLAY_LINES,
) {
    private data class CachedCommittedLine(
        val id: Long,
        val styledText: AnnotatedString,
        val exitSgrState: AnsiSgrState,
        val displayColumns: Int,
    )

    private data class LineColumnEntry(
        val id: Long,
        val columns: Int,
    )

    private val lock = Any()
    private val committedDeque = ArrayDeque<CachedCommittedLine>()
    // Monotonic decreasing deque tracking the sliding-window maximum displayColumns in O(1)
    private val maxColsDeque = ArrayDeque<LineColumnEntry>()

    private var lastRunGeneration: Long = -1L
    private var lastSnapshotVersion: Long = -1L
    private var lastMaskIp: Boolean = true
    private var lastNormalizedHost: String? = null
    private var lastDefaultForeground: Color = Color.Unspecified

    private var lastActiveId: Long = -1L
    private var lastActiveRaw: String? = null
    private var lastActiveCarry: AnsiSgrState? = null
    private var lastActiveRendered: RenderedTerminalLine? = null
    private var lastActiveColumns: Int = 0

    private var lastDroppedCount: Int = -1
    private var lastDroppedNoticeLine: RenderedTerminalLine? = null
    private var lastResultLines: List<RenderedTerminalLine> = emptyList()
    private var lastRenderOutput: TerminalRenderOutput = TerminalRenderOutput()

    fun renderSnapshot(
        runGeneration: Long,
        snapshot: SanitizedBufferSnapshot,
        maskIp: Boolean,
        knownServerHost: String?,
        defaultForeground: Color,
    ): List<RenderedTerminalLine> = renderSnapshotWithMetrics(
        runGeneration = runGeneration,
        snapshot = snapshot,
        maskIp = maskIp,
        knownServerHost = knownServerHost,
        defaultForeground = defaultForeground,
    ).lines

    fun renderSnapshotWithMetrics(
        runGeneration: Long,
        snapshot: SanitizedBufferSnapshot,
        maskIp: Boolean,
        knownServerHost: String?,
        defaultForeground: Color,
    ): TerminalRenderOutput = synchronized(lock) {
        if (snapshot.isEmpty) {
            clearLocked()
            return TerminalRenderOutput()
        }

        val normalizedHost = knownServerHost?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val configChanged = runGeneration != lastRunGeneration ||
            maskIp != lastMaskIp ||
            normalizedHost != lastNormalizedHost ||
            defaultForeground != lastDefaultForeground

        if (!configChanged && snapshot.version == lastSnapshotVersion) {
            return lastRenderOutput
        }

        if (configChanged) {
            clearCommittedLocked()
            lastActiveRendered = null
            lastActiveRaw = null
            lastActiveCarry = null
            lastActiveColumns = 0
            lastDroppedCount = -1
            lastDroppedNoticeLine = null
            lastRunGeneration = runGeneration
            lastMaskIp = maskIp
            lastNormalizedHost = normalizedHost
            lastDefaultForeground = defaultForeground
        }

        val srcCommitted = snapshot.completedLines
        val windowDrop = (srcCommitted.size - maxDisplayLines).coerceAtLeast(0)
        val effectiveDroppedCount = snapshot.droppedLineCount + windowDrop
        val windowStart = windowDrop

        if (windowStart >= srcCommitted.size) {
            clearCommittedLocked()
        } else {
            val firstWantedId = srcCommitted[windowStart].id
            while (committedDeque.isNotEmpty() && committedDeque.first().id < firstWantedId) {
                evictHeadCommittedLocked()
            }

            val expectedTailIndex = windowStart + committedDeque.size
            val contiguous = if (committedDeque.isEmpty()) {
                true
            } else if (committedDeque.first().id != firstWantedId || expectedTailIndex > srcCommitted.size) {
                false
            } else if (expectedTailIndex < srcCommitted.size) {
                srcCommitted[expectedTailIndex].id == committedDeque.last().id + 1L
            } else {
                srcCommitted.last().id == committedDeque.last().id
            }

            if (!contiguous) {
                clearCommittedLocked()
            }

            var carryState = committedDeque.lastOrNull()?.exitSgrState ?: AnsiSgrState.Default
            for (idx in (windowStart + committedDeque.size) until srcCommitted.size) {
                val rawLine = srcCommitted[idx]
                val displayAnsi = if (maskIp) {
                    IpPrivacyMasker.mask(rawLine.ansiText, normalizedHost)
                } else {
                    rawLine.ansiText
                }
                val (styled, exitState) = AnsiAndCodeFormatter.parseAnsiLine(
                    rawText = displayAnsi,
                    initialState = carryState,
                    defaultForeground = defaultForeground,
                )
                val cols = SshTerminalAutoFit.measurePlainLineColumns(styled.text)
                pushCommittedLineLocked(
                    CachedCommittedLine(
                        id = rawLine.id,
                        styledText = styled,
                        exitSgrState = exitState,
                        displayColumns = cols,
                    ),
                )
                carryState = exitState
            }
        }

        val active = snapshot.activeLine
        val renderedActive: RenderedTerminalLine? = if (active != null) {
            val carryState = committedDeque.lastOrNull()?.exitSgrState ?: AnsiSgrState.Default
            val cached = lastActiveRendered
            if (cached != null &&
                lastActiveId == active.id &&
                lastActiveRaw == active.ansiText &&
                lastActiveCarry == carryState
            ) {
                cached
            } else {
                val displayAnsi = if (maskIp) {
                    IpPrivacyMasker.mask(active.ansiText, normalizedHost)
                } else {
                    active.ansiText
                }
                val (styled, _) = AnsiAndCodeFormatter.parseAnsiLine(
                    rawText = displayAnsi,
                    initialState = carryState,
                    defaultForeground = defaultForeground,
                )
                val cols = SshTerminalAutoFit.measurePlainLineColumns(styled.text)
                RenderedTerminalLine(id = active.id, styledText = styled).also {
                    lastActiveId = active.id
                    lastActiveRaw = active.ansiText
                    lastActiveCarry = carryState
                    lastActiveRendered = it
                    lastActiveColumns = cols
                }
            }
        } else {
            lastActiveRendered = null
            lastActiveRaw = null
            lastActiveCarry = null
            lastActiveColumns = 0
            null
        }

        val noticeLine: RenderedTerminalLine? = if (effectiveDroppedCount > 0) {
            if (effectiveDroppedCount == lastDroppedCount && lastDroppedNoticeLine != null) {
                lastDroppedNoticeLine
            } else {
                val noticeAnsi = AnsiStreamSanitizer.formatDroppedLinesNotice(effectiveDroppedCount)
                val (styledNotice, _) = AnsiAndCodeFormatter.parseAnsiLine(
                    rawText = noticeAnsi,
                    initialState = AnsiSgrState.Default,
                    defaultForeground = defaultForeground,
                )
                RenderedTerminalLine(
                    id = DROPPED_NOTICE_LINE_ID,
                    styledText = styledNotice,
                ).also {
                    lastDroppedCount = effectiveDroppedCount
                    lastDroppedNoticeLine = it
                }
            }
        } else {
            lastDroppedCount = 0
            lastDroppedNoticeLine = null
            null
        }

        val totalSize = (if (noticeLine != null) 1 else 0) +
            committedDeque.size +
            (if (renderedActive != null) 1 else 0)
        val out = ArrayList<RenderedTerminalLine>(totalSize)
        if (noticeLine != null) {
            out.add(noticeLine)
        }
        for (entry in committedDeque) {
            out.add(RenderedTerminalLine(id = entry.id, styledText = entry.styledText))
        }
        if (renderedActive != null) {
            out.add(renderedActive)
        }

        val maxCols = maxOf(maxColsDeque.firstOrNull()?.columns ?: 0, lastActiveColumns)
        lastSnapshotVersion = snapshot.version
        lastResultLines = out
        return TerminalRenderOutput(lines = out, maxLineColumns = maxCols).also {
            lastRenderOutput = it
        }
    }

    /**
     * Estimates how many committed lines would need freshly masked + ANSI-parsed if [renderSnapshot]
     * were called right now, allowing UI callers to offload cold multi-thousand-line backfills to
     * `Dispatchers.Default` while rendering small streaming deltas inline in `< 0.2ms`.
     */
    fun estimatedUncachedWork(
        runGeneration: Long,
        snapshot: SanitizedBufferSnapshot,
        maskIp: Boolean,
        knownServerHost: String?,
        defaultForeground: Color,
    ): Int = synchronized(lock) {
        if (snapshot.isEmpty) return 0
        val normalizedHost = knownServerHost?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val configChanged = runGeneration != lastRunGeneration ||
            maskIp != lastMaskIp ||
            normalizedHost != lastNormalizedHost ||
            defaultForeground != lastDefaultForeground
        if (!configChanged && snapshot.version == lastSnapshotVersion) return 0
        val totalWindow = snapshot.completedLines.size.coerceAtMost(maxDisplayLines)
        if (configChanged || committedDeque.isEmpty()) return totalWindow
        val lastCachedId = committedDeque.last().id
        val newestId = snapshot.completedLines.lastOrNull()?.id ?: return 0
        val delta = (newestId - lastCachedId).coerceAtLeast(0L)
        return delta.coerceAtMost(totalWindow.toLong()).toInt()
    }

    fun reset() = synchronized(lock) {
        clearLocked()
    }

    private fun pushCommittedLineLocked(entry: CachedCommittedLine) {
        committedDeque.addLast(entry)
        while (maxColsDeque.isNotEmpty() && maxColsDeque.last().columns <= entry.displayColumns) {
            maxColsDeque.removeLast()
        }
        maxColsDeque.addLast(LineColumnEntry(id = entry.id, columns = entry.displayColumns))
    }

    private fun evictHeadCommittedLocked() {
        val removed = committedDeque.removeFirst()
        if (maxColsDeque.firstOrNull()?.id == removed.id) {
            maxColsDeque.removeFirst()
        }
    }

    private fun clearCommittedLocked() {
        committedDeque.clear()
        maxColsDeque.clear()
    }

    private fun clearLocked() {
        clearCommittedLocked()
        lastSnapshotVersion = -1L
        lastActiveRendered = null
        lastActiveRaw = null
        lastActiveCarry = null
        lastActiveColumns = 0
        lastDroppedCount = -1
        lastDroppedNoticeLine = null
        lastResultLines = emptyList()
        lastRenderOutput = TerminalRenderOutput()
    }

    companion object {
        const val DEFAULT_MAX_DISPLAY_LINES: Int = 4_000
        const val DROPPED_NOTICE_LINE_ID: Long = 0L

        private data class StaticCacheKey(
            val sourceKey: String,
            val textLength: Int,
            val textHash: Int,
            val maskIp: Boolean,
            val normalizedHost: String?,
            val defaultForeground: Color,
            val maxLines: Int,
        )

        private val staticCacheLock = Any()
        private val staticLruCache = object : LinkedHashMap<StaticCacheKey, TerminalRenderOutput>(12, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<StaticCacheKey, TerminalRenderOutput>?,
            ): Boolean = size > MAX_STATIC_CACHE_ENTRIES
        }

        private const val MAX_STATIC_CACHE_ENTRIES: Int = 8

        fun renderStaticTranscript(
            sourceKey: String,
            sanitizedAnsiText: String,
            maskIp: Boolean,
            knownServerHost: String?,
            defaultForeground: Color,
            maxLines: Int = DEFAULT_MAX_DISPLAY_LINES,
        ): List<RenderedTerminalLine> = renderStaticTranscriptWithMetrics(
            sourceKey = sourceKey,
            sanitizedAnsiText = sanitizedAnsiText,
            maskIp = maskIp,
            knownServerHost = knownServerHost,
            defaultForeground = defaultForeground,
            maxLines = maxLines,
        ).lines

        /**
         * Parses a static/completed sanitized ANSI transcript into [TerminalRenderOutput] with
         * bounded LRU memoization so switching between session tabs or reopening the screen is O(1).
         */
        fun renderStaticTranscriptWithMetrics(
            sourceKey: String,
            sanitizedAnsiText: String,
            maskIp: Boolean,
            knownServerHost: String?,
            defaultForeground: Color,
            maxLines: Int = DEFAULT_MAX_DISPLAY_LINES,
        ): TerminalRenderOutput {
            if (sanitizedAnsiText.isEmpty()) return TerminalRenderOutput()
            val normalizedHost = knownServerHost?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            val key = StaticCacheKey(
                sourceKey = sourceKey,
                textLength = sanitizedAnsiText.length,
                textHash = sanitizedAnsiText.hashCode(),
                maskIp = maskIp,
                normalizedHost = normalizedHost,
                defaultForeground = defaultForeground,
                maxLines = maxLines,
            )
            synchronized(staticCacheLock) {
                staticLruCache[key]?.let { return it }
            }

            val snap = SanitizedBufferSnapshot.fromSanitizedText(
                sanitizedText = sanitizedAnsiText,
                maxLines = maxLines,
            )
            val rendered = SshTerminalConsoleBuffer(maxDisplayLines = maxLines).renderSnapshotWithMetrics(
                runGeneration = 1L,
                snapshot = snap,
                maskIp = maskIp,
                knownServerHost = normalizedHost,
                defaultForeground = defaultForeground,
            )
            synchronized(staticCacheLock) {
                staticLruCache[key] = rendered
            }
            return rendered
        }

        fun renderQuickTailPreview(
            snapshot: SanitizedBufferSnapshot,
            maskIp: Boolean,
            knownServerHost: String?,
            defaultForeground: Color,
            tailLines: Int = 48,
        ): List<RenderedTerminalLine> = renderQuickTailPreviewWithMetrics(
            snapshot = snapshot,
            maskIp = maskIp,
            knownServerHost = knownServerHost,
            defaultForeground = defaultForeground,
            tailLines = tailLines,
        ).lines

        /**
         * Fast `< 0.3ms` tail-only slice used for the very first frame when scrolling into a console
         * that already accumulated thousands of lines in the background while `Dispatchers.Default`
         * completes full scrollback formatting.
         */
        fun renderQuickTailPreviewWithMetrics(
            snapshot: SanitizedBufferSnapshot,
            maskIp: Boolean,
            knownServerHost: String?,
            defaultForeground: Color,
            tailLines: Int = 48,
        ): TerminalRenderOutput {
            if (snapshot.isEmpty) return TerminalRenderOutput()
            val src = snapshot.completedLines
            val start = (src.size - tailLines).coerceAtLeast(0)
            val tailSlice = if (start > 0) src.subList(start, src.size) else src
            val miniSnapshot = SanitizedBufferSnapshot(
                version = snapshot.version,
                droppedLineCount = snapshot.droppedLineCount + start,
                completedLines = tailSlice,
                activeLine = snapshot.activeLine,
                hasTrailingNewline = snapshot.hasTrailingNewline,
            )
            return SshTerminalConsoleBuffer(maxDisplayLines = tailLines + 2).renderSnapshotWithMetrics(
                runGeneration = 1L,
                snapshot = miniSnapshot,
                maskIp = maskIp,
                knownServerHost = knownServerHost,
                defaultForeground = defaultForeground,
            )
        }
    }
}
