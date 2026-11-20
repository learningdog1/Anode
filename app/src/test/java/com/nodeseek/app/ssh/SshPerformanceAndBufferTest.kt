package com.nodeseek.app.ssh

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.domain.ssh.AnsiStreamSanitizer
import com.nodeseek.app.domain.ssh.InMemoryTestCipher
import com.nodeseek.app.domain.ssh.IpPrivacyMasker
import com.nodeseek.app.domain.ssh.LiteSshExecutionEvent
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshAutoRespondConfig
import com.nodeseek.app.domain.ssh.SshBenchmarkPostFormatter
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshPresetCatalog
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.domain.ssh.SshTransport
import com.nodeseek.app.ui.components.AnsiAndCodeFormatter
import com.nodeseek.app.ui.ssh.LiteSshGlobalBarState
import com.nodeseek.app.ui.ssh.LiteSshSessionManager
import com.nodeseek.app.ui.ssh.SshForegroundServiceController
import com.nodeseek.app.ui.ssh.SshTerminalConsoleBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class SshPerformanceAndBufferTest {

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

    @Test
    fun `LiteSshExecutor coalesces high-frequency PTY chunks with zero-latency leading edge and flushes on completion`() = runTest(testDispatcher) {
        val virtualClock = AtomicLong(1_750_000_000_000L)

        // 500 chunks spaced 4ms apart (= 2,000ms total): simulates 250 Hz PTY output & \r progress bar storm
        val rapidTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                emit(SshStreamEvent.Connected)
                for (i in 1..500) {
                    if (i % 5 == 0) {
                        emit(SshStreamEvent.OutputChunk("Line ${i / 5}: 203.0.113.42 OK\r\n"))
                    } else {
                        emit(SshStreamEvent.OutputChunk("Progress ${i % 100}%\r"))
                    }
                    delay(4L)
                    virtualClock.addAndGet(4L)
                }
                emit(SshStreamEvent.Completed(exitCode = 0))
            }
        }

        val executor = LiteSshExecutor(
            transport = rapidTransport,
            clock = virtualClock::get,
            outputThrottleMillis = 64L,
        )

        val outputEvents = mutableListOf<LiteSshExecutionEvent.OutputUpdated>()
        var completedEvent: LiteSshExecutionEvent.Completed? = null

        val collectJob = launch {
            executor.runCommand(
                request = SshConnectionRequest(host = "203.0.113.42"),
                command = "curl -sL https://yabs.sh | bash -s -- -5",
                autoRespondConfig = SshAutoRespondConfig(enabled = true),
            ).collect { ev ->
                when (ev) {
                    is LiteSshExecutionEvent.OutputUpdated -> outputEvents += ev
                    is LiteSshExecutionEvent.Completed -> completedEvent = ev
                    else -> Unit
                }
            }
        }

        // At t = 0ms (before first 4ms delay), chunk #1 MUST already be emitted via leading-edge fast path
        runCurrent()
        assertEquals("First chunk must emit with 0ms leading-edge latency", 1, outputEvents.size)

        // Run entire 2,000ms stream to completion
        advanceUntilIdle()
        collectJob.join()

        assertNotNull(completedEvent)
        assertEquals(0, completedEvent!!.exitCode)
        // 500 chunks over 2,000ms at 64ms throttle -> ~31-33 OutputUpdated events (vs 500 unthrottled = >93% reduction)
        assertTrue(
            "Expected coalesced OutputUpdated count in 20..40, got ${outputEvents.size}",
            outputEvents.size in 20..40,
        )
        // Verify all 100 committed lines survived intact in final output
        assertEquals(100, completedEvent!!.bufferSnapshot.completedLines.size)
        assertTrue(completedEvent!!.sanitizedAnsiOutput.contains("Line 1: 203.0.113.42 OK"))
        assertTrue(completedEvent!!.sanitizedAnsiOutput.contains("Line 100: 203.0.113.42 OK"))
        assertFalse(completedEvent!!.sanitizedAnsiOutput.contains("Progress "))
    }

    @Test
    fun `AnsiStreamSanitizer shares completedLines across carriage-return storms and enforces maxLines and maxLineChars`() {
        val sanitizer = AnsiStreamSanitizer(maxLines = 10, maxLineChars = 64)

        sanitizer.append("Header 1\nHeader 2\n")
        val snapBeforeSpinner = sanitizer.lineSnapshot()
        assertEquals(2, snapBeforeSpinner.completedLines.size)

        // 200 carriage-return progress overwrites without '\n' must reuse the exact same completedLines List instance
        for (pct in 1..200) {
            sanitizer.append("Downloading fio $pct%\r")
        }
        val snapDuringSpinner = sanitizer.lineSnapshot()
        assertSame(
            "Carriage-return progress updates must share completedLines list reference in O(1)",
            snapBeforeSpinner.completedLines,
            snapDuringSpinner.completedLines,
        )
        assertEquals("Downloading fio 200%", snapDuringSpinner.activeLine?.ansiText)

        // Pathological single line without \r/\n exceeding maxLineChars = 64 is safely bounded
        val runawayLine = "X".repeat(5_000) + "\u001B[0m\n"
        sanitizer.append("\r" + runawayLine)
        val snapAfterRunaway = sanitizer.lineSnapshot()
        val cappedLine = snapAfterRunaway.completedLines.last().ansiText
        assertTrue("Runaway single line should be bounded near maxLineChars", cappedLine.length <= 80)
        assertTrue("Trailing SGR reset should still be preserved", cappedLine.endsWith("\u001B[0m"))

        // Push past maxLines = 10 and verify monotonic stable line IDs and tailText accuracy
        for (i in 4..25) {
            sanitizer.append("Row $i\n")
        }
        val cappedSnap = sanitizer.lineSnapshot()
        assertEquals(10, cappedSnap.completedLines.size)
        assertTrue(cappedSnap.droppedLineCount > 0)
        // Line IDs must be strictly increasing and > 0 (0L is reserved for dropped notice row)
        val ids = cappedSnap.completedLines.map { it.id }
        assertEquals(ids.sorted(), ids)
        assertEquals(10, ids.distinct().size)
        assertTrue(ids.first() > 10L)

        val full = cappedSnap.fullAnsiText
        assertEquals(full.takeLast(40), cappedSnap.tailText(maxChars = 40))
    }

    @Test
    fun `SshTerminalConsoleBuffer preserves multi-line ANSI SGR styles, masks IPs incrementally, and evicts cleanly`() {
        val defaultFg = Color(0xFFE6EDF3)
        val sanitizer = AnsiStreamSanitizer(maxLines = 100)
        val consoleBuffer = SshTerminalConsoleBuffer(maxDisplayLines = 5)

        // Multi-line SGR block where bold+green starts on line 1 and closes on line 2
        sanitizer.append("\u001B[1;32mBanner Top 203.0.113.77\nBanner Bottom 198.51.100.9\u001B[0m\nPlain Tail\n")
        val snap1 = sanitizer.lineSnapshot()

        val rendered1 = consoleBuffer.renderSnapshot(
            runGeneration = 1L,
            snapshot = snap1,
            maskIp = true,
            knownServerHost = "203.0.113.77",
            defaultForeground = defaultFg,
        )
        assertEquals(3, rendered1.size)
        assertEquals("Banner Top 203.0.*.*", rendered1[0].styledText.text)
        assertEquals("Banner Bottom 198.51.*.*", rendered1[1].styledText.text)
        assertEquals("Plain Tail", rendered1[2].styledText.text)

        // Line 2 MUST inherit Bold + Green from Line 1 even though Line 2 had no leading \u001B[1;32m!
        val line1Style = rendered1[0].styledText.spanStyles.first().item
        val line2Style = rendered1[1].styledText.spanStyles.first().item
        val line3Style = rendered1[2].styledText.spanStyles.first().item
        assertEquals(FontWeight.Bold, line1Style.fontWeight)
        assertEquals(FontWeight.Bold, line2Style.fontWeight)
        assertEquals(line1Style.color, line2Style.color)
        assertEquals(FontWeight.Normal, line3Style.fontWeight)
        assertEquals(defaultFg, line3Style.color)

        // Appending 1 new line should report estimatedUncachedWork == 1 and reuse prior AnnotatedStrings
        sanitizer.append("Incremental Line 4\n")
        val snap2 = sanitizer.lineSnapshot()
        assertEquals(
            1,
            consoleBuffer.estimatedUncachedWork(
                runGeneration = 1L,
                snapshot = snap2,
                maskIp = true,
                knownServerHost = "203.0.113.77",
                defaultForeground = defaultFg,
            ),
        )
        val rendered2 = consoleBuffer.renderSnapshot(
            runGeneration = 1L,
            snapshot = snap2,
            maskIp = true,
            knownServerHost = "203.0.113.77",
            defaultForeground = defaultFg,
        )
        assertEquals(4, rendered2.size)
        assertSame(
            "Committed AnnotatedString instances must be reused without re-parsing",
            rendered1[0].styledText,
            rendered2[0].styledText,
        )
        assertSame(rendered1[1].styledText, rendered2[1].styledText)

        // Appending 4 more lines exceeds maxDisplayLines = 5 -> row 0 becomes stable notice line (id = 0L)
        sanitizer.append("L5\nL6\nL7\nL8\n")
        val snap3 = sanitizer.lineSnapshot()
        val rendered3 = consoleBuffer.renderSnapshot(
            runGeneration = 1L,
            snapshot = snap3,
            maskIp = true,
            knownServerHost = "203.0.113.77",
            defaultForeground = defaultFg,
        )
        assertEquals(6, rendered3.size) // 1 notice row + 5 display lines
        assertEquals(SshTerminalConsoleBuffer.DROPPED_NOTICE_LINE_ID, rendered3[0].id)
        assertTrue(rendered3[0].styledText.text.contains("已省略早期 3 行"))
        assertEquals("L8", rendered3.last().styledText.text)

        // Toggling maskIp = false reveals raw IPs immediately
        val unmasked = consoleBuffer.renderSnapshot(
            runGeneration = 1L,
            snapshot = snap1,
            maskIp = false,
            knownServerHost = "203.0.113.77",
            defaultForeground = defaultFg,
        )
        assertEquals("Banner Top 203.0.113.77", unmasked[0].styledText.text)
    }

    @Test
    fun `LiteSshSessionManager isolates globalBarState and uiState from high-rate stream and pauses background ticks`() = runTest(testDispatcher) {
        val nowMillis = AtomicLong(1_750_000_000_000L)
        val vault = SshServerVault(
            vaultFile = File(tempFolder.root, "perf_session_${System.nanoTime()}.json"),
            cipher = InMemoryTestCipher(),
            ioDispatcher = testDispatcher,
            clock = nowMillis::get,
        )
        vault.saveServer(
            SshServerDraft(
                alias = "HKG Perf Node",
                host = "203.0.113.99",
                port = 22,
                username = "root",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "pw",
            ),
        )

        val finishGate = CompletableDeferred<Unit>()
        val streamingTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                emit(SshStreamEvent.Connected)
                // 150 chunks in 600ms (within the first second, same stage)
                for (i in 1..150) {
                    emit(SshStreamEvent.OutputChunk("fio 4k progress $i% 203.0.113.99\r"))
                    if (i % 15 == 0) {
                        emit(SshStreamEvent.OutputChunk("\nBasic System Information row ${i / 15}\n"))
                    }
                    delay(4L)
                    nowMillis.addAndGet(4L)
                }
                finishGate.await()
                emit(SshStreamEvent.Completed(exitCode = 0))
            }
        }

        var fgsNotifications = 0
        val fakeFgs = object : SshForegroundServiceController {
            override fun onSessionRunning(serverAlias: String, presetName: String, statusLine: String) {
                fgsNotifications++
            }
            override fun onSessionFinished() = Unit
        }

        val managerScope = CoroutineScope(SupervisorJob() + testDispatcher)
        try {
            val manager = LiteSshSessionManager(
                vaultRepository = vault,
                executor = LiteSshExecutor(
                    transport = streamingTransport,
                    clock = nowMillis::get,
                    outputThrottleMillis = 64L,
                ),
                externalScope = managerScope,
                foregroundServiceController = fakeFgs,
                clock = nowMillis::get,
                enableElapsedTicker = true,
                silenceThresholdMillis = 8_000L,
            )

            runCurrent()
            manager.selectPreset(SshPresetCatalog.PRESET_YABS)

            // Attach globalBarState & uiState collectors (simulating user browsing app / viewing LiteSshScreen chrome)
            val globalBarEmissions = mutableListOf<LiteSshGlobalBarState>()
            val uiChromeEmissions = mutableListOf<Int>()
            val barJob = launch {
                manager.globalBarState.collect { globalBarEmissions += it }
            }
            val uiJob = launch {
                manager.uiState.collect { uiChromeEmissions += 1 }
            }
            runCurrent()
            val baselineUiCount = uiChromeEmissions.size
            val baselineBarCount = globalBarEmissions.size

            manager.runSelectedPreset()
            // Advance through all 150 chunks (600ms)
            advanceTimeBy(650L)
            runCurrent()

            val uiUpdatesDuringBurst = uiChromeEmissions.size - baselineUiCount
            val barUpdatesDuringBurst = globalBarEmissions.size - baselineBarCount
            // Despite 150 PTY chunks, uiState and globalBarState only emit on initial connect/stage transitions (<= 5)
            assertTrue(
                "uiState should not emit per chunk during 150-chunk burst, got $uiUpdatesDuringBurst",
                uiUpdatesDuringBurst <= 5,
            )
            assertTrue(
                "globalBarState should not emit per chunk during 150-chunk burst, got $barUpdatesDuringBurst",
                barUpdatesDuringBurst <= 4,
            )
            // O(1) export summary metadata works without touching fullAnsiText, while live output remains accessible
            assertNotNull(manager.uiState.value.computeExportSummaryMeta())
            assertTrue(manager.uiState.value.displayedConsoleOutput.contains("Basic System Information row 10"))

            // Now simulate app going to background (all UI collectors unsubscribe via repeatOnLifecycle)
            barJob.cancel()
            uiJob.cancel()
            runCurrent()

            val stateSnapshotBeforeBg = manager.uiState.value
            val fgsBeforeBg = fgsNotifications
            // Advance 12 seconds in background silence -> ticker must NOT churn uiState while 0 UI observers exist,
            // YET must still update foreground service notification for long-silence reassurance!
            nowMillis.addAndGet(12_000L)
            advanceTimeBy(12_000L)
            runCurrent()

            assertSame(
                "uiState must not churn while app is in background with 0 UI subscribers",
                stateSnapshotBeforeBg,
                manager.uiState.value,
            )
            assertTrue(
                "Foreground service notification must still update during background long silence",
                fgsNotifications > fgsBeforeBg,
            )

            // Re-attaching a UI collector wakes ticker immediately and refreshes liveProgress!
            val resumedJob = launch {
                manager.uiState.collect { }
            }
            runCurrent()
            assertTrue(manager.uiState.value.liveProgress.isSilentLong)
            resumedJob.cancel()

            finishGate.complete(Unit)
            advanceUntilIdle()
            assertFalse(manager.uiState.value.isRunning)
        } finally {
            managerScope.cancel()
        }
    }

    @Test
    fun `quantify before vs after terminal rendering pipeline metrics`() = runTest(testDispatcher) {
        val defaultFg = Color(0xFFE6EDF3)
        val clock = AtomicLong(1_750_000_000_000L)

        // Dataset: 2,200 PTY chunks (1,200 ANSI benchmark lines + 1,000 \r progress bar updates = ~80.5 KB sanitized)
        val chunks = ArrayList<String>(2_200)
        for (i in 1..1_200) {
            chunks += "\u001B[1;36m[Stage ${i % 6}]\u001B[0m Node $i 203.0.113.${(i % 250) + 1} -> 59.43.${(i % 200) + 10}.88 \u001B[32m128.4 MB/s\u001B[0m\r\n"
            if (i % 12 == 0) {
                for (pct in 1..10) {
                    chunks += "\u001B[33mProgress ${pct * 10}% [====>     ] 203.0.113.55\u001B[0m\r"
                }
            }
        }

        // Legacy per-emission cost on 1,200-line output (what LiteSshScreen ran on EVERY recomposition before fix):
        val fullSanitized = AnsiStreamSanitizer.sanitize(chunks.joinToString(""))
        val legacySingleFrameStartNs = System.nanoTime()
        val legacyExport = SshBenchmarkPostFormatter.format(
            runs = listOf(
                com.nodeseek.app.domain.ssh.SshExecutionRecord(
                    id = "live",
                    serverId = "s1",
                    serverAlias = "VPS",
                    serverHost = "203.0.113.55",
                    presetId = "yabs",
                    presetName = "YABS",
                    tabTitle = "YABS",
                    command = "yabs",
                    sanitizedAnsiOutput = fullSanitized,
                    exitCode = null,
                    status = com.nodeseek.app.domain.ssh.SshRunStatus.RUNNING,
                    startedAtEpochMillis = 1L,
                    durationMillis = 1000L,
                ),
            ),
            maskIp = true,
        )
        val legacyDisplayed = IpPrivacyMasker.mask(fullSanitized, "203.0.113.55")
        val legacyAnnotated = AnsiAndCodeFormatter.parseAnsiTerminalText(legacyDisplayed, defaultFg)
        val legacySingleFrameMs = (System.nanoTime() - legacySingleFrameStartNs) / 1_000_000.0
        assertTrue(legacyExport.markdownContent.isNotEmpty() && legacyAnnotated.length > 0)

        // New pipeline: timed 2,200 chunks spaced 2ms apart (= 4.4s stream) through throttled Executor + incremental ConsoleBuffer
        val timedTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                emit(SshStreamEvent.Connected)
                for (chunk in chunks) {
                    emit(SshStreamEvent.OutputChunk(chunk))
                    delay(2L)
                    clock.addAndGet(2L)
                }
                emit(SshStreamEvent.Completed(exitCode = 0))
            }
        }

        val executor = LiteSshExecutor(
            transport = timedTransport,
            clock = clock::get,
            outputThrottleMillis = 64L,
        )
        val incrementalBuffer = SshTerminalConsoleBuffer()
        var coalescedFrames = 0
        var totalIncrementalRenderNs = 0L
        var maxSingleFrameRenderNs = 0L
        var finalLines: List<com.nodeseek.app.ui.ssh.RenderedTerminalLine> = emptyList()

        val job = launch {
            executor.runCommand(
                request = SshConnectionRequest(host = "203.0.113.55"),
                command = "curl -sL https://yabs.sh | bash -s -- -5",
            ).collect { ev ->
                if (ev is LiteSshExecutionEvent.OutputUpdated) {
                    coalescedFrames++
                    val t0 = System.nanoTime()
                    finalLines = incrementalBuffer.renderSnapshot(
                        runGeneration = 1L,
                        snapshot = ev.bufferSnapshot,
                        maskIp = true,
                        knownServerHost = "203.0.113.55",
                        defaultForeground = defaultFg,
                    )
                    val dt = System.nanoTime() - t0
                    totalIncrementalRenderNs += dt
                    if (dt > maxSingleFrameRenderNs) maxSingleFrameRenderNs = dt
                } else if (ev is LiteSshExecutionEvent.Completed) {
                    val t0 = System.nanoTime()
                    finalLines = incrementalBuffer.renderSnapshot(
                        runGeneration = 1L,
                        snapshot = ev.bufferSnapshot,
                        maskIp = true,
                        knownServerHost = "203.0.113.55",
                        defaultForeground = defaultFg,
                    )
                    totalIncrementalRenderNs += (System.nanoTime() - t0)
                }
            }
        }
        advanceUntilIdle()
        job.join()

        // Also measure first-frame quick tail preview cost when scrolling into an already-populated 1,200-line console
        val finalSnapshot = AnsiStreamSanitizer().apply { chunks.forEach(::append) }.lineSnapshot()
        val coldQuickPreviewStartNs = System.nanoTime()
        val quickTail = SshTerminalConsoleBuffer.renderQuickTailPreview(
            snapshot = finalSnapshot,
            maskIp = true,
            knownServerHost = "203.0.113.55",
            defaultForeground = defaultFg,
        )
        val coldQuickPreviewMs = (System.nanoTime() - coldQuickPreviewStartNs) / 1_000_000.0

        val avgFrameMs = (totalIncrementalRenderNs / 1_000_000.0) / coalescedFrames.coerceAtLeast(1)
        val maxFrameMs = maxSingleFrameRenderNs / 1_000_000.0

        println(
            "=== OPTIMIZED PIPELINE METRICS ===\n" +
                "Input PTY chunks: ${chunks.size} (1200 lines + 1000 \\r overwrites)\n" +
                "Coalesced UI stream frames (64ms window over 4.4s): $coalescedFrames (vs 2200 before = %.1f%% reduction)\n".format(
                    (1.0 - coalescedFrames / 2200.0) * 100.0,
                ) +
                "Incremental console render total CPU across ALL $coalescedFrames frames: %.2f ms (avg %.3f ms/frame, max %.3f ms/frame)\n".format(
                    totalIncrementalRenderNs / 1_000_000.0,
                    avgFrameMs,
                    maxFrameMs,
                ) +
                "Cold scroll-into-terminal Frame-1 quick tail preview (${quickTail.size} rows): %.3f ms (vs %.2f ms full parse + 1200-line monolithic Text before)".format(
                    coldQuickPreviewMs,
                    legacySingleFrameMs,
                ),
        )

        // 1,200 committed lines + 1 trailing active progress line (from i = 1,200 % 12 == 0)
        assertEquals(1_201, finalLines.size)
        assertTrue("Average incremental frame render must be sub-2ms", avgFrameMs < 2.0)
        assertTrue("Cold quick preview must be sub-5ms", coldQuickPreviewMs < 5.0)
    }
}
