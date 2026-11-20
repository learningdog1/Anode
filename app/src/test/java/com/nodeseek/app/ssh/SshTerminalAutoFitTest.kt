package com.nodeseek.app.ssh

import androidx.compose.ui.graphics.Color
import com.nodeseek.app.data.ssh.JSchSshTransport
import com.nodeseek.app.data.ssh.ManagedExecChannel
import com.nodeseek.app.data.ssh.ManagedSshSession
import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.data.ssh.SshSessionConnector
import com.nodeseek.app.domain.ssh.AnsiStreamSanitizer
import com.nodeseek.app.domain.ssh.HostKeyVerificationResult
import com.nodeseek.app.domain.ssh.InMemoryTestCipher
import com.nodeseek.app.domain.ssh.LiteSshExecutionEvent
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshPtyResizeCallbackContext
import com.nodeseek.app.domain.ssh.SshPtySize
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.domain.ssh.SshTransport
import com.nodeseek.app.ui.ssh.LiteSshUiState
import com.nodeseek.app.ui.ssh.LiteSshViewModel
import com.nodeseek.app.ui.ssh.SshTerminalAutoFit
import com.nodeseek.app.ui.ssh.SshTerminalConsoleBuffer
import com.nodeseek.app.ui.ssh.TerminalFitMode
import com.nodeseek.app.ui.ssh.TerminalFontSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
class SshTerminalAutoFitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── 1. Display Column Width Calculation ─────────────────────────────────────────────────────────

    @Test
    fun `measureTerminalLineColumns strips ANSI and counts CJK, box drawing, tabs, combining marks, and emoji clusters accurately`() {
        // Pure ASCII
        assertEquals(0, SshTerminalAutoFit.measureTerminalLineColumns(""))
        assertEquals(11, SshTerminalAutoFit.measureTerminalLineColumns("Hello World"))

        // ANSI SGR + 24-bit TrueColor + OSC-8 hyperlink + cursor controls take 0 columns
        val ansiStyled = "\u001B[?25l\u001B[1;36m=== \u001B[38;2;120;200;255mNodeQuality\u001B[0m ===\u001B]8;;https://example.com\u001B\\Link\u001B]8;;\u001B\\\u001B[K"
        assertEquals("=== NodeQuality ===Link".length, SshTerminalAutoFit.measureTerminalLineColumns(ansiStyled))

        // CJK fullwidth characters & fullwidth punctuation = 2 cols each; box-drawing / block chars = 1 col each
        val cjkBoxRow = "│ 节点地区：日本东京（Tokyo） │"
        // '│' (1) + ' ' (1) + "节点地区：日本东京（" (10*2=20) + "Tokyo" (5) + "）" (2) + ' ' (1) + '│' (1) = 31
        assertEquals(31, SshTerminalAutoFit.measureTerminalLineColumns(cjkBoxRow))
        assertEquals(10, SshTerminalAutoFit.measurePlainLineColumns("╭────────╮"))
        assertEquals(8, SshTerminalAutoFit.measurePlainLineColumns("██▓▒░ ✓✗"))

        // Japanese Kana & Korean Hangul = 2 cols each
        assertEquals(8, SshTerminalAutoFit.measurePlainLineColumns("東京서울"))

        // Tabs expand to 8-col tab stops
        assertEquals(10, SshTerminalAutoFit.measurePlainLineColumns("\tAB")) // 8 + 2
        assertEquals(10, SshTerminalAutoFit.measurePlainLineColumns("123\tAB")) // 8 + 2
        assertEquals(17, SshTerminalAutoFit.measurePlainLineColumns("12345678\tA")) // 16 + 1

        // Combining diacritics = 0 extra width
        assertEquals(4, SshTerminalAutoFit.measurePlainLineColumns("cafe\u0301"))

        // Emojis: standard 2-col emoji, VS16 upgraded symbol, keycap, skin tone, ZWJ family, regional flag pairs
        assertEquals(2, SshTerminalAutoFit.measurePlainLineColumns("✅"))
        assertEquals(2, SshTerminalAutoFit.measurePlainLineColumns("❌"))
        assertEquals(2, SshTerminalAutoFit.measurePlainLineColumns("🚀"))
        assertEquals(2, SshTerminalAutoFit.measurePlainLineColumns("⚠️")) // U+26A0 + U+FE0F
        assertEquals(2, SshTerminalAutoFit.measurePlainLineColumns("1️⃣")) // '1' + U+FE0F + U+20E3
        assertEquals(2, SshTerminalAutoFit.measurePlainLineColumns("👍🏽")) // U+1F44D + U+1F3FD
        assertEquals(2, SshTerminalAutoFit.measurePlainLineColumns("👨‍👩‍👧")) // ZWJ cluster
        assertEquals(4, SshTerminalAutoFit.measurePlainLineColumns("🇨🇳🇺🇸")) // 2 flag pairs = 4 cols

        // Multi-line max column detection across realistic NQ / YABS / Bench.sh snippet
        val sampleMultiLine = """
            |${"\u001B[1;36m"}----------------------------------------------------------------------${"\u001B[0m"}
            | Node Name        Upload Speed      Download Speed      Latency
            | 电信 CN2 GIA     520.42 Mbps       980.11 Mbps         128.4 ms   🚀
            |${"\u001B[32m"}----------------------------------------------------------------------${"\u001B[0m"}
        """.trimMargin()
        val expectedLine1 = 70
        val expectedLine3 = SshTerminalAutoFit.measurePlainLineColumns(" 电信 CN2 GIA     520.42 Mbps       980.11 Mbps         128.4 ms   🚀")
        assertEquals(maxOf(expectedLine1, expectedLine3), SshTerminalAutoFit.measureMaxTerminalColumns(sampleMultiLine))
    }

    // ── 2. Auto-Fit Font Solver & Horizontal Scroll Floor ───────────────────────────────────────────

    @Test
    fun `solveLayout keeps base font size on short output and shrinks smoothly to fit wide tables without wrapping`() {
        val density = 3.0f
        val charWidthPerSp = 0.60f * density // 1.8 px/sp
        val lineHeightPerSp = TerminalFontSpec.DEFAULT_LINE_HEIGHT_RATIO * density
        // Standard 412dp phone minus 32dp screen margin & 24dp text padding = 356dp = 1068px
        val widthPx = 356f * density
        val heightPx = 396f * density

        // Case 1: Empty / short lines (<= 40 cols) never zoom above 11.5sp base
        val shortSolved = SshTerminalAutoFit.solveLayout(
            viewportWidthPx = widthPx,
            viewportHeightPx = heightPx,
            charWidthPxPerSp = charWidthPerSp,
            lineHeightPxPerSp = lineHeightPerSp,
            maxLineColumns = 32,
            fitMode = TerminalFitMode.AUTO_FIT,
            manualFontScale = 1.0f,
        )
        assertEquals(TerminalFontSpec.DEFAULT_BASE_FONT_SIZE_SP, shortSolved.effectiveFontSizeSp, 0.001f)
        assertFalse(shortSolved.requiresHorizontalScroll)
        assertFalse(shortSolved.hitMinReadableFloor)

        // Case 2: 80-col benchmark output on 412dp phone shrinks cleanly above 6.0sp floor and fits 100% on one screen
        val cols80Solved = SshTerminalAutoFit.solveLayout(
            viewportWidthPx = widthPx,
            viewportHeightPx = heightPx,
            charWidthPxPerSp = charWidthPerSp,
            lineHeightPxPerSp = lineHeightPerSp,
            maxLineColumns = 80,
            fitMode = TerminalFitMode.AUTO_FIT,
            manualFontScale = 1.0f,
        )
        assertTrue(
            "Expected fontSize in [6.0, 11.5), got ${cols80Solved.effectiveFontSizeSp}",
            cols80Solved.effectiveFontSizeSp in 6.0f..11.49f,
        )
        assertTrue(cols80Solved.visibleColumnsAtFont >= 80)
        assertFalse(cols80Solved.requiresHorizontalScroll)
        assertFalse(cols80Solved.hitMinReadableFloor)
        // PTY cols must stay capped at 80 (not 81) so SIGWINCH redraws never cause shrink-loop
        assertEquals(80, cols80Solved.ptySize.cols)

        // Feeding ptySize.cols back as maxLineColumns must converge cleanly without shrinking font
        val reEvaluated80 = SshTerminalAutoFit.solveLayout(
            viewportWidthPx = widthPx,
            viewportHeightPx = heightPx,
            charWidthPxPerSp = charWidthPerSp,
            lineHeightPxPerSp = lineHeightPerSp,
            maxLineColumns = maxOf(80, cols80Solved.ptySize.cols),
            fitMode = TerminalFitMode.AUTO_FIT,
            manualFontScale = 1.0f,
        )
        assertEquals(cols80Solved.effectiveFontSizeSp, reEvaluated80.effectiveFontSizeSp, 0.001f)
        assertEquals(cols80Solved.ptySize.cols, reEvaluated80.ptySize.cols)

        // Case 3: Ultra-wide 160-col line clamps at 6.0sp readable floor and enables horizontal scroll
        val ultraWideSolved = SshTerminalAutoFit.solveLayout(
            viewportWidthPx = widthPx,
            viewportHeightPx = heightPx,
            charWidthPxPerSp = charWidthPerSp,
            lineHeightPxPerSp = lineHeightPerSp,
            maxLineColumns = 160,
            fitMode = TerminalFitMode.AUTO_FIT,
            manualFontScale = 1.0f,
        )
        assertEquals(TerminalFontSpec.DEFAULT_MIN_AUTO_FIT_FONT_SIZE_SP, ultraWideSolved.effectiveFontSizeSp, 0.001f)
        assertTrue(ultraWideSolved.hitMinReadableFloor)
        assertTrue(ultraWideSolved.requiresHorizontalScroll)
        assertTrue(ultraWideSolved.ptySize.cols in 90..110)

        // Case 4: FIXED mode ignores maxLineColumns for font size and reports horizontal scroll when needed
        val fixedZoomedIn = SshTerminalAutoFit.solveLayout(
            viewportWidthPx = widthPx,
            viewportHeightPx = heightPx,
            charWidthPxPerSp = charWidthPerSp,
            lineHeightPxPerSp = lineHeightPerSp,
            maxLineColumns = 80,
            fitMode = TerminalFitMode.FIXED,
            manualFontScale = 1.30f,
        )
        assertEquals(11.5f * 1.30f, fixedZoomedIn.effectiveFontSizeSp, 0.02f)
        assertTrue(fixedZoomedIn.requiresHorizontalScroll)
        assertTrue(fixedZoomedIn.ptySize.cols < 50)
    }

    @Test
    fun `solveLayout AUTO_FIT PTY feedback loop never shrinks font across diverse viewport widths and line lengths`() {
        val density = 3.0f
        val charWidthPerSp = 0.60f * density
        val lineHeightPerSp = TerminalFontSpec.DEFAULT_LINE_HEIGHT_RATIO * density
        val widthsDp = listOf(304f, 320f, 352f, 356f, 400f, 740f)
        val initialMaxColsList = listOf(0, 32, 50, 51, 72, 80, 95, 120, 160)

        for (wDp in widthsDp) {
            val wPx = wDp * density
            val hPx = 396f * density
            for (initialCols in initialMaxColsList) {
                val pass1 = SshTerminalAutoFit.solveLayout(
                    viewportWidthPx = wPx,
                    viewportHeightPx = hPx,
                    charWidthPxPerSp = charWidthPerSp,
                    lineHeightPxPerSp = lineHeightPerSp,
                    maxLineColumns = initialCols,
                    fitMode = TerminalFitMode.AUTO_FIT,
                    manualFontScale = 1.0f,
                )
                val nextMaxCols = maxOf(initialCols, pass1.ptySize.cols)
                val pass2 = SshTerminalAutoFit.solveLayout(
                    viewportWidthPx = wPx,
                    viewportHeightPx = hPx,
                    charWidthPxPerSp = charWidthPerSp,
                    lineHeightPxPerSp = lineHeightPerSp,
                    maxLineColumns = nextMaxCols,
                    fitMode = TerminalFitMode.AUTO_FIT,
                    manualFontScale = 1.0f,
                )
                assertEquals(
                    "Font size must not ratchet down on SIGWINCH feedback (wDp=$wDp, initialCols=$initialCols, ptyCols=${pass1.ptySize.cols})",
                    pass1.effectiveFontSizeSp,
                    pass2.effectiveFontSizeSp,
                    0.001f,
                )
                assertEquals(pass1.ptySize.cols, pass2.ptySize.cols)
            }
        }
    }

    @Test
    fun `solveLayout recalculates font size and PTY dimensions across portrait, landscape, and foldable width changes`() {
        val density = 3.0f
        val charWidthPerSp = 0.60f * density
        val lineHeightPerSp = TerminalFontSpec.DEFAULT_LINE_HEIGHT_RATIO * density
        val maxOutputCols = 96

        // Compact phone portrait (304dp inner width, 396dp inner height)
        val portrait = SshTerminalAutoFit.solveLayout(
            viewportWidthPx = 304f * density,
            viewportHeightPx = 396f * density,
            charWidthPxPerSp = charWidthPerSp,
            lineHeightPxPerSp = lineHeightPerSp,
            maxLineColumns = maxOutputCols,
            fitMode = TerminalFitMode.AUTO_FIT,
            manualFontScale = 1.0f,
        )
        // 96 cols requires ~5.25sp on 304dp -> hits 6.0sp floor with horizontal scroll
        assertEquals(6.0f, portrait.effectiveFontSizeSp, 0.01f)
        assertTrue(portrait.requiresHorizontalScroll)

        // Rotate to landscape (740dp inner width, 200dp inner height)
        val landscape = SshTerminalAutoFit.solveLayout(
            viewportWidthPx = 740f * density,
            viewportHeightPx = 200f * density,
            charWidthPxPerSp = charWidthPerSp,
            lineHeightPxPerSp = lineHeightPerSp,
            maxLineColumns = maxOutputCols,
            fitMode = TerminalFitMode.AUTO_FIT,
            manualFontScale = 1.0f,
        )
        // 740dp easily fits 96 cols at full 11.5sp with zero horizontal scroll!
        assertEquals(11.5f, landscape.effectiveFontSizeSp, 0.01f)
        assertFalse(landscape.requiresHorizontalScroll)
        assertTrue(landscape.ptySize.cols > portrait.ptySize.cols)
        assertTrue(landscape.ptySize.rows < portrait.ptySize.rows)
    }

    // ── 3. PTY Size Calculation & Clamping ──────────────────────────────────────────────────────────

    @Test
    fun `calculatePtySize computes accurate cols and rows and clamps extreme bounds`() {
        // Normal 356dp x 396dp at 3x density, 11.5sp
        val normal = SshTerminalAutoFit.calculatePtySize(
            viewportWidthPx = 356f * 3f,
            viewportHeightPx = 396f * 3f,
            cellWidthPx = 11.5f * 0.60f * 3f,
            lineHeightPx = 16.5f * 3f,
        )
        assertEquals(51, normal.cols)
        assertEquals(24, normal.rows)
        assertEquals(1068, normal.widthPx)
        assertEquals(1188, normal.heightPx)

        // Tiny / degenerate viewport clamps to safe minimums
        val tiny = SshTerminalAutoFit.calculatePtySize(
            viewportWidthPx = 10f,
            viewportHeightPx = 10f,
            cellWidthPx = 20f,
            lineHeightPx = 30f,
        )
        assertEquals(SshPtySize.MIN_PTY_COLS, tiny.cols)
        assertEquals(SshPtySize.MIN_PTY_ROWS, tiny.rows)

        // Huge ultra-wide / tiny-font viewport clamps to safe maximums
        val huge = SshTerminalAutoFit.calculatePtySize(
            viewportWidthPx = 10_000f,
            viewportHeightPx = 10_000f,
            cellWidthPx = 5f,
            lineHeightPx = 10f,
        )
        assertEquals(SshPtySize.MAX_PTY_COLS, huge.cols)
        assertEquals(SshPtySize.MAX_PTY_ROWS, huge.rows)
    }

    // ── 4. Incremental Buffer Max Column Maintenance ────────────────────────────────────────────────

    @Test
    fun `SshTerminalConsoleBuffer incrementally maintains sliding-window maxLineColumns across CR updates, eviction, and IP masking`() {
        val defaultFg = Color(0xFFE6EDF3)
        val sanitizer = AnsiStreamSanitizer(maxLines = 100)
        val consoleBuffer = SshTerminalConsoleBuffer(maxDisplayLines = 3)

        // Line 1: 20 cols; Line 2: wide CJK+ANSI table line; Line 3: 15 cols
        sanitizer.append("Short Line 123456789\n")
        sanitizer.append("\u001B[36m┌────────中文表头────────┐\u001B[0m\n")
        sanitizer.append("Trailing Line 3\n")

        val out1 = consoleBuffer.renderSnapshotWithMetrics(
            runGeneration = 1L,
            snapshot = sanitizer.lineSnapshot(),
            maskIp = true,
            knownServerHost = null,
            defaultForeground = defaultFg,
        )
        // "┌────────中文表头────────┐" = 1 + 8 + 8 + 8 + 1 = 26 cols
        assertEquals(26, out1.maxLineColumns)

        // Active line via \r grows to 40 cols, then overwrites down to 12 cols
        sanitizer.append("Downloading [=========================>] 50%\r")
        val outDuringWideCr = consoleBuffer.renderSnapshotWithMetrics(
            runGeneration = 1L,
            snapshot = sanitizer.lineSnapshot(),
            maskIp = true,
            knownServerHost = null,
            defaultForeground = defaultFg,
        )
        assertEquals(44, outDuringWideCr.maxLineColumns)

        sanitizer.append("Done 100%\r\n")
        val outAfterShortCommit = consoleBuffer.renderSnapshotWithMetrics(
            runGeneration = 1L,
            snapshot = sanitizer.lineSnapshot(),
            maskIp = true,
            knownServerHost = null,
            defaultForeground = defaultFg,
        )
        // Sliding window of size 3 now holds: [Line 2 (26), Line 3 (15), Done 100% (9)] -> max 26
        assertEquals(26, outAfterShortCommit.maxLineColumns)

        // Append 2 more short lines -> Line 2 (26 cols) is evicted from the 3-line display window!
        // Monotonic deque MUST drop maxLineColumns down to 15 without scanning evicted history.
        sanitizer.append("Row A\nRow B\n")
        val outAfterEviction = consoleBuffer.renderSnapshotWithMetrics(
            runGeneration = 1L,
            snapshot = sanitizer.lineSnapshot(),
            maskIp = true,
            knownServerHost = null,
            defaultForeground = defaultFg,
        )
        assertEquals(9, outAfterEviction.maxLineColumns) // [Done 100% (9), Row A (5), Row B (5)]

        // IP masking shortens "203.0.113.200" (13) -> "203.0.*.*" (9) and updates maxLineColumns
        val ipSanitizer = AnsiStreamSanitizer(maxLines = 20).apply {
            append("IP: 203.0.113.200\n")
        }
        val ipBuffer = SshTerminalConsoleBuffer()
        val maskedOut = ipBuffer.renderSnapshotWithMetrics(
            runGeneration = 1L,
            snapshot = ipSanitizer.lineSnapshot(),
            maskIp = true,
            knownServerHost = "203.0.113.200",
            defaultForeground = defaultFg,
        )
        val unmaskedOut = ipBuffer.renderSnapshotWithMetrics(
            runGeneration = 1L,
            snapshot = ipSanitizer.lineSnapshot(),
            maskIp = false,
            knownServerHost = "203.0.113.200",
            defaultForeground = defaultFg,
        )
        assertEquals("IP: 203.0.*.*".length, maskedOut.maxLineColumns)
        assertEquals("IP: 203.0.113.200".length, unmaskedOut.maxLineColumns)

        // Static LRU renderStaticTranscriptWithMetrics returns accurate maxLineColumns on cache hit too
        val static1 = SshTerminalConsoleBuffer.renderStaticTranscriptWithMetrics(
            sourceKey = "run_test_fit",
            sanitizedAnsiText = "Header\n\u001B[32m测试宽行 1234567890\u001B[0m\nEnd\n",
            maskIp = false,
            knownServerHost = null,
            defaultForeground = defaultFg,
        )
        val static2 = SshTerminalConsoleBuffer.renderStaticTranscriptWithMetrics(
            sourceKey = "run_test_fit",
            sanitizedAnsiText = "Header\n\u001B[32m测试宽行 1234567890\u001B[0m\nEnd\n",
            maskIp = false,
            knownServerHost = null,
            defaultForeground = defaultFg,
        )
        assertEquals(19, static1.maxLineColumns) // "测试宽行 " (4*2+1=9) + 10 digits = 19
        assertEquals(static1, static2)
    }

    // ── 5. JSchSshTransport PTY Initial Size & Live window-change Propagation ───────────────────────

    @Test
    fun `JSchSshTransport applies initial PTY size and dispatches deduplicated live window-change via SshPtyResizeCallbackContext`() = runTest(testDispatcher) {
        val vault = SshServerVault(
            vaultFile = File(tempFolder.root, "pty_vault_${System.nanoTime()}.json"),
            cipher = InMemoryTestCipher(),
            ioDispatcher = testDispatcher,
            clock = { 1_750_000_000_000L },
        )

        val ptyEvents = Collections.synchronizedList(mutableListOf<String>())
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut)
        val channelConnected = CompletableDeferred<Unit>()
        val ptySizeApplied = CompletableDeferred<Unit>()

        val connector = SshSessionConnector {
            object : ManagedSshSession {
                override val lastTunnelError: String? = null
                override fun connect(connectTimeoutMillis: Int): HostKeyVerificationResult? = null
                override fun setPortForwardingL(bindAddress: String, localPort: Int, targetHost: String, targetPort: Int, connectTimeoutMillis: Int): Int = 0
                override fun delPortForwardingL(bindAddress: String, localPort: Int) = Unit
                override fun openExecChannel(): ManagedExecChannel = object : ManagedExecChannel {
                    override val inputStream: InputStream = pipedIn
                    override var isClosed: Boolean = false
                    override val exitStatus: Int = 0

                    override fun setPtyType(ptyType: String, col: Int, row: Int, wp: Int, hp: Int) {
                        ptyEvents.add("setPtyType:$ptyType:$col:$row:$wp:$hp")
                    }

                    override fun setPtySize(col: Int, row: Int, wp: Int, hp: Int) {
                        ptyEvents.add("setPtySize:$col:$row:$wp:$hp")
                        ptySizeApplied.complete(Unit)
                    }

                    override fun connect(connectTimeoutMillis: Int) {
                        channelConnected.complete(Unit)
                    }

                    override fun disconnect() {
                        isClosed = true
                        runCatching { pipedOut.close() }
                    }
                }
                override fun disconnect() = Unit
            }
        }

        val transport = JSchSshTransport(
            ioDispatcher = Dispatchers.IO,
            vaultRepository = vault,
            sessionConnector = connector,
        )
        val executor = LiteSshExecutor(
            transport = transport,
            processingDispatcher = Dispatchers.Default,
        )

        var capturedResizer: com.nodeseek.app.domain.ssh.SshPtyResizer? = null
        val resizerReady = CompletableDeferred<Unit>()
        val request = SshConnectionRequest(
            host = "203.0.113.50",
            ptyCols = 78,
            ptyRows = 28,
            ptyWidthPx = 960,
            ptyHeightPx = 1100,
        )

        val collected = Collections.synchronizedList(mutableListOf<LiteSshExecutionEvent>())
        val collectJob = launch {
            executor.runCommand(
                request = request,
                command = "bash yabs.sh",
            ).flowOn(
                SshPtyResizeCallbackContext { resizer ->
                    capturedResizer = resizer
                    resizerReady.complete(Unit)
                },
            ).collect {
                collected.add(it)
            }
        }

        channelConnected.await()
        resizerReady.await()

        // Duplicate resize identical to initial size must be deduplicated
        capturedResizer!!.resize(SshPtySize(cols = 78, rows = 28, widthPx = 960, heightPx = 1100))
        // New landscape size triggers window-change (setPtySize)
        capturedResizer!!.resize(SshPtySize(cols = 116, rows = 18, widthPx = 2100, heightPx = 680))

        // Wait for IO coroutine to process conflated queue
        ptySizeApplied.await()

        pipedOut.write("OK\n".toByteArray(StandardCharsets.UTF_8))
        pipedOut.close()
        collectJob.join()

        assertEquals(
            listOf(
                "setPtyType:xterm-256color:78:28:960:1100",
                "setPtySize:116:18:2100:680",
            ),
            ptyEvents,
        )
    }

    // ── 6. ViewModel & SessionManager Auto-Fit / Fixed / Pinch / Reset / PTY Forwarding ────────────

    @Test
    fun `LiteSshViewModel manages auto-fit toggle, pinch zoom from auto-fit scale, reset, and PTY size forwarding`() = runTest(testDispatcher) {
        val vault = SshServerVault(
            vaultFile = File(tempFolder.root, "vm_fit_vault_${System.nanoTime()}.json"),
            cipher = InMemoryTestCipher(),
            ioDispatcher = testDispatcher,
            clock = { 1_750_000_000_000L },
        )
        vault.saveServer(
            SshServerDraft(
                alias = "JP Tokyo",
                host = "203.0.113.66",
                port = 22,
                username = "root",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "pw",
            ),
        )

        val observedRequests = mutableListOf<SshConnectionRequest>()
        val liveResizes = mutableListOf<SshPtySize>()
        val streamGate = CompletableDeferred<Unit>()

        val mockTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                observedRequests += request
                val resizeCtx = kotlin.coroutines.coroutineContext[SshPtyResizeCallbackContext]
                resizeCtx?.onPtyResizerReady?.invoke { size -> liveResizes += size }
                emit(SshStreamEvent.Connected)
                emit(SshStreamEvent.OutputChunk("Wide table running\n"))
                streamGate.await()
                emit(SshStreamEvent.Completed(exitCode = 0))
            }
        }

        val viewModel = LiteSshViewModel(
            vaultRepository = vault,
            executor = LiteSshExecutor(transport = mockTransport),
            enableElapsedTicker = false,
        )
        runCurrent()

        // Default is Auto-Fit ON at 1.0x
        assertTrue(viewModel.uiState.value.terminalAutoFitEnabled)
        assertEquals(LiteSshUiState.DEFAULT_CONSOLE_FONT_SCALE, viewModel.uiState.value.consoleFontScale, 0.001f)

        // Viewport measures 84x30 before starting run -> request inherits measured PTY dimensions
        viewModel.updateTerminalPtySize(SshPtySize(cols = 84, rows = 30, widthPx = 1000, heightPx = 1200))
        viewModel.runSelectedPreset()
        runCurrent()

        assertEquals(1, observedRequests.size)
        assertEquals(84, observedRequests.single().ptyCols)
        assertEquals(30, observedRequests.single().ptyRows)

        // Orientation changes while running -> live PTY window-change is forwarded
        viewModel.updateTerminalPtySize(SshPtySize(cols = 132, rows = 16, widthPx = 2100, heightPx = 620))
        assertTrue(liveResizes.contains(SshPtySize(cols = 132, rows = 16, widthPx = 2100, heightPx = 620)))

        // Pinch gesture while in Auto-Fit starts smoothly from current effective auto-fit scale (e.g. 0.65f) -> switches to FIXED
        viewModel.pinchConsoleFontScale(zoomFactor = 1.20f, autoFitStartScale = 0.65f)
        assertFalse(viewModel.uiState.value.terminalAutoFitEnabled)
        assertEquals(0.78f, viewModel.uiState.value.consoleFontScale, 0.005f)

        // Button zoom in adds +0.10f in FIXED mode
        viewModel.adjustConsoleFontScale(0.10f)
        assertFalse(viewModel.uiState.value.terminalAutoFitEnabled)
        assertEquals(0.88f, viewModel.uiState.value.consoleFontScale, 0.005f)

        // Reset restores Auto-Fit ON and 1.00f scale
        viewModel.resetConsoleFontScale()
        assertTrue(viewModel.uiState.value.terminalAutoFitEnabled)
        assertEquals(1.00f, viewModel.uiState.value.consoleFontScale, 0.001f)

        // Manual toggle back to FIXED keeps manual scale
        viewModel.setTerminalAutoFitEnabled(false)
        assertFalse(viewModel.uiState.value.terminalAutoFitEnabled)

        streamGate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `incremental maxLineColumns tracking over 3000 mixed CJK and ANSI lines stays O(1) per frame`() {
        val defaultFg = Color(0xFFE6EDF3)
        val sanitizer = AnsiStreamSanitizer(maxLines = 4_000)
        val buffer = SshTerminalConsoleBuffer(maxDisplayLines = 4_000)

        var totalRenderNs = 0L
        var frames = 0
        var lastMaxCols = 0

        for (i in 1..3_000) {
            val line = if (i % 500 == 0) {
                "\u001B[1;36m│ 节点 #$i │ 三网回程路由测试宽行数据延迟 123.4ms │ 丢包率 0.0% │ 🚀 │\u001B[0m\n"
            } else {
                "\u001B[32m[Row $i]\u001B[0m 203.0.113.${(i % 250) + 1} -> 45.67 MB/s\n"
            }
            sanitizer.append(line)
            if (i % 25 == 0) {
                val snap = sanitizer.lineSnapshot()
                val t0 = System.nanoTime()
                val out = buffer.renderSnapshotWithMetrics(
                    runGeneration = 1L,
                    snapshot = snap,
                    maskIp = true,
                    knownServerHost = null,
                    defaultForeground = defaultFg,
                )
                totalRenderNs += (System.nanoTime() - t0)
                frames++
                lastMaxCols = out.maxLineColumns
            }
        }

        val avgMs = (totalRenderNs / 1_000_000.0) / frames.coerceAtLeast(1)
        assertTrue("Expected wide CJK row width >= 60, got $lastMaxCols", lastMaxCols >= 60)
        assertTrue("Expected sub-5ms incremental render average, got $avgMs ms", avgMs < 5.0)
    }
}
