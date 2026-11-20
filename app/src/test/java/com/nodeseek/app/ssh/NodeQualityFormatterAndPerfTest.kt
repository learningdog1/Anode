package com.nodeseek.app.ssh

import androidx.compose.ui.graphics.Color
import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.domain.ssh.AnsiStreamSanitizer
import com.nodeseek.app.domain.ssh.InMemoryTestCipher
import com.nodeseek.app.domain.ssh.IpPrivacyMasker
import com.nodeseek.app.domain.ssh.LiteSshExecutor
import com.nodeseek.app.domain.ssh.NodeQualityReportFormatter
import com.nodeseek.app.domain.ssh.NodeQualitySectionKind
import com.nodeseek.app.domain.ssh.SanitizedBufferSnapshot
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.ssh.SshBenchmarkPostFormatter
import com.nodeseek.app.domain.ssh.SshConnectionRequest
import com.nodeseek.app.domain.ssh.SshExecutionRecord
import com.nodeseek.app.domain.ssh.SshMergeLayout
import com.nodeseek.app.domain.ssh.SshPresetCatalog
import com.nodeseek.app.domain.ssh.SshRunStatus
import com.nodeseek.app.domain.ssh.SshServerDraft
import com.nodeseek.app.domain.ssh.SshStageDetector
import com.nodeseek.app.domain.ssh.SshStreamEvent
import com.nodeseek.app.domain.ssh.SshTransport
import com.nodeseek.app.ui.components.AnsiAndCodeFormatter
import com.nodeseek.app.ui.components.MdBlock
import com.nodeseek.app.ui.components.parseNodeSeekMarkdownBlocks
import com.nodeseek.app.ui.components.parseNodeSeekMarkdownDocument
import com.nodeseek.app.ui.components.tokenizeMarkdownInline
import com.nodeseek.app.ui.ssh.LiteSshViewModel
import com.nodeseek.app.ui.ssh.SshTerminalConsoleBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class NodeQualityFormatterAndPerfTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `AnsiStreamSanitizer erases CheckPlace multi-line ADLines via ESC 1A ESC 2K while keeping reports intact`() {
        val raw = buildString {
            append("\u001B[1;32m正在运行硬件质量测试...\u001B[0m\n")
            append("\u001B[H\u001B[2J\u001B[3J")
            // 4 lines of temporary TrueColor sponsor ad printed to PTY stderr
            for (row in 1..4) {
                append("\u001B[38;2;255;120;0m\u001B[48;2;10;20;30mSPONSOR_AD_ROW_$row\u001B[0m\n")
            }
            // Background progress bar updates on active line
            append("\r\u001B[36m\u001B[1m[203.0.113.88]# 正在获取CPU信息 .... ⠋ 25%\u001B[0m")
            append("\r\u001B[36m\u001B[1m[203.0.113.88]# Sysbench CPU测试 .. ⠙ 75%\u001B[0m")
            // Check.Place erases active progress bar + 4 ad lines using \033[2K + 4 * (\033[1A\033[2K)
            append("\u001B[2K")
            repeat(4) {
                append("\u001B[1A\u001B[2K")
            }
            append("\r++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++\n")
            append("                       \u001B[1m硬件质量体检报告：\u001B[36m203.0.113.88\u001B[0m\n")
            append("CPU 型号: AMD EPYC 9654\n")
        }

        val sanitized = AnsiStreamSanitizer.sanitize(raw)
        assertFalse("Sponsor ad rows must be erased by ESC[1A ESC[2K", sanitized.contains("SPONSOR_AD_ROW"))
        assertFalse("Progress bar spinner must be erased", sanitized.contains("Sysbench CPU测试"))
        assertTrue(sanitized.contains("正在运行硬件质量测试..."))
        assertTrue(sanitized.contains("硬件质量体检报告："))
        assertTrue(sanitized.contains("CPU 型号: AMD EPYC 9654"))
    }

    @Test
    fun `NodeQuality standalone post matches official nodequality_com exportToNs and copyText formats`() {
        val rawNqTranscript = buildRealisticNqTranscript(
            serverIp = "203.0.113.88",
            ipv6 = "2a01:4f8:c012:abcd::1",
            shareToken = "Nq987654Token",
            progressFramesPerStage = 20,
        )

        val nqRecord = SshExecutionRecord(
            id = "nq-1",
            serverId = "srv-tyo",
            serverAlias = "东京软银 203.0.113.88",
            serverHost = "203.0.113.88",
            presetId = SshPresetCatalog.PRESET_NODE_QUALITY,
            presetName = "NodeQuality (NQ) 质量体检",
            tabTitle = "NodeQuality",
            command = SshPresetCatalog.findBuiltIn(SshPresetCatalog.PRESET_NODE_QUALITY)!!.buildCommand(),
            sanitizedAnsiOutput = rawNqTranscript,
            exitCode = 1, // Upstream NodeQuality.sh post_cleanup exits with 1
            status = SshRunStatus.FAILED,
            startedAtEpochMillis = 1_750_000_000_000L,
            durationMillis = 185_000L,
        )

        val post = SshBenchmarkPostFormatter.format(listOf(nqRecord), maskIp = true)
        assertEquals(1, post.runCount)
        assertTrue(post.usesTabsLayout)
        assertEquals("https://nodequality.com/r/Nq987654Token", post.nodeQualityShareUrl)
        assertTrue(post.suggestedTitle.contains("203.0.*.*"))
        assertFalse(post.suggestedTitle.contains("203.0.113.88"))

        val md = post.markdownContent
        // Official NodeQuality NodeSeek 1-click export structure (`exportToNs`)
        assertTrue(md.startsWith(":::: tabs\n"))
        assertTrue(md.contains("::: tab-item 💻基本信息\n"))
        assertTrue(md.contains("::: tab-item 🎬IP质量\n"))
        assertTrue(md.contains("::: tab-item 🌐网络质量\n"))
        assertTrue(md.contains("::: tab-item 📍回程路由\n"))
        assertTrue(md.endsWith("[NodeQuality链接](https://nodequality.com/r/Nq987654Token)"))

        // Pre-test BenchOS/prompt noise and post-cleanup lines must NOT pollute formatted post
        assertFalse(md.contains("加载 BenchOs"))
        assertFalse(md.contains("安装前清理"))
        assertFalse(md.contains("安装后清理"))
        assertFalse(md.contains("清理中，请稍后"))
        assertFalse(md.contains("SPONSOR_AD"))

        // Privacy masking must hide IPv4 and IPv6 host suffixes across all 4 tabs while keeping URLs intact
        assertTrue(md.contains("203.0.*.*"))
        assertTrue(md.contains("2a01:4f8:*:*"))
        assertFalse(md.contains("203.0.113.88"))
        assertFalse(md.contains("2a01:4f8:c012:abcd::1"))
        assertTrue(md.contains("https://Report.Check.Place/hardware/hw123"))

        // Verify Anode MarkdownRenderer parses the generated NQ tabs + share link cleanly
        val blocks = parseNodeSeekMarkdownBlocks(md)
        assertEquals(2, blocks.size)
        val tabs = (blocks[0] as MdBlock.TabsContainer).tabs
        assertEquals(
            listOf("💻基本信息", "🎬IP质量", "🌐网络质量", "📍回程路由"),
            tabs.map { it.title },
        )
        assertTrue("Backroute tab must retain CN2 GIA route lines", (tabs[3].children.single() as MdBlock.CodeFence).code.contains("59.43.*.*"))
        val linkPara = blocks[1] as MdBlock.Paragraph
        assertEquals("[NodeQuality链接](https://nodequality.com/r/Nq987654Token)", linkPara.text)

        // Official NodeQuality plain-text copy (`复制文本` / `copyText`)
        val plain = post.plainTextCopy
        assertNotNull(plain)
        assertFalse("Plain text copy must strip all ANSI SGR codes", plain!!.contains("\u001B["))
        assertTrue(plain.contains("💻基本信息"))
        assertTrue(plain.contains("🎬IP质量"))
        assertTrue(plain.contains("🌐网络质量"))
        assertTrue(plain.contains("📍回程路由"))
        assertTrue(plain.endsWith("[NodeQuality链接](https://nodequality.com/r/Nq987654Token)"))
    }

    @Test
    fun `NodeQuality English mode and partial sections format cleanly`() {
        val englishOutput = """
            Running Hardware Quality Test...
            ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
                              HARDWARE QUALITY CHECK REPORT: 198.51.100.42
            ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
            Processor: Intel Xeon Gold 6138
            Running IP Quality Test...
            ########################################################################
                              IP QUALITY CHECK REPORT: 198.51.100.42
            ########################################################################
            Netflix: Yes (US)
            Clean Up after Installation
        """.trimIndent()

        val parsed = NodeQualityReportFormatter.parse(
            rawOutput = englishOutput,
            maskIp = true,
            knownServerHost = "198.51.100.42",
        )
        assertEquals(
            listOf(NodeQualitySectionKind.BASIC_INFO, NodeQualitySectionKind.IP_QUALITY),
            parsed.sections.map { it.kind },
        )
        assertEquals(null, parsed.shareUrl)
        assertTrue(parsed.sections[0].content.contains("198.51.*.*"))
        assertFalse(parsed.sections[0].content.contains("198.51.100.42"))
    }

    @Test
    fun `multi-run merge combines NQ, YABS, and GoECS cleanly in both TABS and SECTIONS layouts with IP masking`() = runTest {
        val nqRun = SshExecutionRecord(
            id = "run-nq",
            serverId = "srv-1",
            serverAlias = "瓦工 DC6",
            serverHost = "198.51.100.25",
            presetId = SshPresetCatalog.PRESET_NODE_QUALITY,
            presetName = "NodeQuality (NQ) 质量体检",
            tabTitle = "NodeQuality",
            command = "bash <(curl -sL https://run.NodeQuality.com)",
            sanitizedAnsiOutput = buildRealisticNqTranscript(
                serverIp = "198.51.100.25",
                ipv6 = "2607:fcd0:100:200::8",
                shareToken = "MergeTok77",
                progressFramesPerStage = 5,
            ),
            exitCode = 0,
            status = SshRunStatus.SUCCEEDED,
            startedAtEpochMillis = 1_000L,
            durationMillis = 120_000L,
        )
        val yabsRun = SshExecutionRecord(
            id = "run-yabs",
            serverId = "srv-1",
            serverAlias = "瓦工 DC6",
            serverHost = "198.51.100.25",
            presetId = SshPresetCatalog.PRESET_YABS,
            presetName = "YABS 综合跑分",
            tabTitle = "YABS 跑分",
            command = "curl -sL https://yabs.sh | bash -s -- -5",
            sanitizedAnsiOutput = "\u001B[1mBasic System Information:\u001B[0m\nIPv4: 198.51.100.25\nGeekbench 5: 1480 / 5920\n",
            exitCode = 0,
            status = SshRunStatus.SUCCEEDED,
            startedAtEpochMillis = 2_000L,
            durationMillis = 95_000L,
        )
        val ecsRun = SshExecutionRecord(
            id = "run-ecs",
            serverId = "srv-1",
            serverAlias = "瓦工 DC6",
            serverHost = "198.51.100.25",
            presetId = SshPresetCatalog.PRESET_ECS,
            presetName = "融合怪 (GoECS) 全能体检",
            tabTitle = "融合怪测评",
            command = "goecs -menu=false",
            sanitizedAnsiOutput = "\u001B[36m=== 融合怪测评报告 ===\u001B[0m\n本机 IP: 198.51.100.25 / 2607:fcd0:100:200::8\n三网回程: CN2 GIA 59.43.180.9\n",
            exitCode = 0,
            status = SshRunStatus.SUCCEEDED,
            startedAtEpochMillis = 3_000L,
            durationMillis = 210_000L,
        )

        // 1. Default TABS merge layout: outer `:::: tabs` with NO nested `:::: tabs` collision inside NQ tab
        val tabsMerged = SshBenchmarkPostFormatter.format(
            runs = listOf(nqRun, yabsRun, ecsRun),
            maskIp = true,
            mergeLayout = SshMergeLayout.TABS,
        )
        assertEquals(3, tabsMerged.runCount)
        assertTrue(tabsMerged.usesTabsLayout)
        assertTrue(tabsMerged.suggestedTitle.contains("瓦工 DC6"))
        assertTrue(tabsMerged.suggestedTitle.contains("NodeQuality"))
        assertTrue(tabsMerged.suggestedTitle.contains("YABS"))
        assertTrue(tabsMerged.suggestedTitle.contains("融合怪"))
        assertFalse(tabsMerged.markdownContent.contains("198.51.100.25"))
        assertFalse(tabsMerged.markdownContent.contains("2607:fcd0:100:200::8"))
        assertFalse(tabsMerged.markdownContent.contains("59.43.180.9"))
        assertTrue(tabsMerged.markdownContent.contains("198.51.*.*"))
        assertTrue(tabsMerged.markdownContent.contains("2607:fcd0:*:*"))
        assertTrue(tabsMerged.markdownContent.contains("59.43.*.*"))
        assertTrue(tabsMerged.markdownContent.contains("[NodeQuality链接](https://nodequality.com/r/MergeTok77)"))

        val parsedTabsBlocks = parseNodeSeekMarkdownBlocks(tabsMerged.markdownContent)
        assertEquals("Outer TABS merge must parse into a single intact TabsContainer", 1, parsedTabsBlocks.size)
        val outerTabs = (parsedTabsBlocks.single() as MdBlock.TabsContainer).tabs
        assertEquals(listOf("NodeQuality", "YABS 跑分", "融合怪测评"), outerTabs.map { it.title })
        // NQ tab contains Headings (`### 💻基本信息`..`### 📍回程路由`) + CodeFences + Share Link Paragraph
        assertTrue(outerTabs[0].children.any { it is MdBlock.Heading && it.text == "💻基本信息" })
        assertTrue(outerTabs[0].children.any { it is MdBlock.Heading && it.text == "📍回程路由" })
        assertTrue(outerTabs[1].children.single() is MdBlock.CodeFence)
        assertTrue(outerTabs[2].children.single() is MdBlock.CodeFence)

        // 2. SECTIONS merge layout: independent `## <Title>` headings where NQ retains its full `:::: tabs`
        val sectionsMerged = SshBenchmarkPostFormatter.format(
            runs = listOf(nqRun, yabsRun, ecsRun),
            maskIp = true,
            mergeLayout = SshMergeLayout.SECTIONS,
        )
        assertEquals(3, sectionsMerged.runCount)
        assertTrue(sectionsMerged.markdownContent.contains("## NodeQuality"))
        assertTrue(sectionsMerged.markdownContent.contains("## YABS 跑分"))
        assertTrue(sectionsMerged.markdownContent.contains("## 融合怪测评"))
        val parsedSectionBlocks = parseNodeSeekMarkdownBlocks(sectionsMerged.markdownContent)
        assertTrue(parsedSectionBlocks.any { it is MdBlock.Heading && it.level == 2 && it.text == "NodeQuality" })
        assertTrue(parsedSectionBlocks.any { it is MdBlock.TabsContainer && it.tabs.size == 4 })
        assertEquals(2, parsedSectionBlocks.count { it is MdBlock.HorizontalRule })

        // 3. Verify ViewModel async export + candidate history merge
        val vault = SshServerVault(
            vaultFile = File(tempFolder.root, "merge_vault.json"),
            cipher = InMemoryTestCipher(),
            ioDispatcher = testDispatcher,
        )
        vault.saveServer(
            SshServerDraft(
                id = "srv-1",
                alias = "瓦工 DC6",
                host = "198.51.100.25",
                authMethod = SshAuthMethod.PASSWORD,
                secret = "pw",
            ),
        )
        vault.saveRunRecord(nqRun)
        vault.saveRunRecord(yabsRun)

        val noopTransport = object : SshTransport {
            override fun execute(request: SshConnectionRequest, command: String): Flow<SshStreamEvent> = flow {
                emit(SshStreamEvent.Connected)
                emit(SshStreamEvent.OutputChunk(ecsRun.sanitizedAnsiOutput))
                emit(SshStreamEvent.Completed(exitCode = 0))
            }
        }
        val vm = LiteSshViewModel(
            vaultRepository = vault,
            executor = LiteSshExecutor(transport = noopTransport),
            enableElapsedTicker = false,
        )
        vm.selectPreset(SshPresetCatalog.PRESET_ECS)
        vm.runSelectedPreset()
        // Merge historical NQ + YABS runs into active session via toggleCandidateRunForExport
        vm.toggleCandidateRunForExport(nqRun)
        vm.toggleCandidateRunForExport(yabsRun)

        val asyncPost = vm.buildExportPayloadAsync()
        assertNotNull(asyncPost)
        assertEquals(3, asyncPost!!.runCount)
        assertFalse(asyncPost.markdownContent.contains("198.51.100.25"))
    }

    @Test
    fun `2MB NodeQuality output with ANSI SGR and carriage-return storms completes view, preview, and publish pipeline rapidly`() {
        // Construct a >2.1 MB raw NodeQuality transcript with heavy ANSI TrueColor, \r progress storms,
        // \033[1A\033[2K sponsor banners, and thousands of lines across all 4 stages.
        val raw2MbNq = buildLarge2MbNqTranscript()
        assertTrue("Expected >= 2,000,000 chars, got ${raw2MbNq.length}", raw2MbNq.length >= 2_000_000)

        // 1. View path (`查看`): SanitizedBufferSnapshot.fromSanitizedText + SshTerminalConsoleBuffer
        val tViewStart = System.nanoTime()
        val staticRendered = SshTerminalConsoleBuffer.renderStaticTranscript(
            sourceKey = "nq_2mb_view",
            sanitizedAnsiText = raw2MbNq,
            maskIp = true,
            knownServerHost = "203.0.113.199",
            defaultForeground = Color(0xFFE6EDF3),
        )
        val viewElapsedMs = (System.nanoTime() - tViewStart) / 1_000_000.0
        assertTrue("Console static view render must be bounded and non-empty", staticRendered.isNotEmpty())
        assertTrue(
            "Console static view lines must be capped (got ${staticRendered.size})",
            staticRendered.size <= SshTerminalConsoleBuffer.DEFAULT_MAX_DISPLAY_LINES + 2,
        )
        assertTrue("2MB view pipeline took ${viewElapsedMs}ms (expected < 1500ms)", viewElapsedMs < 1_500.0)

        // 2. Stage detector on 2MB raw transcript
        val tStageStart = System.nanoTime()
        val stageStatus = SshStageDetector.detect(
            presetId = SshPresetCatalog.PRESET_NODE_QUALITY,
            command = "bash <(curl -sL https://run.NodeQuality.com)",
            sanitizedOutput = raw2MbNq,
            isConnecting = false,
            startedAtEpochMillis = 1_000L,
            lastOutputEpochMillis = 5_000L,
            nowEpochMillis = 6_000L,
        )
        val stageElapsedMs = (System.nanoTime() - tStageStart) / 1_000_000.0
        assertEquals("报告上传与沙箱清理阶段", stageStatus.currentStageLabel)
        assertTrue("Stage detection on 2MB took ${stageElapsedMs}ms (expected < 200ms)", stageElapsedMs < 200.0)

        // 3. Preview & Publish path (`预览` / `发布`): Formatter + Markdown AST + Chunked ANSI Code Renderer
        val record2Mb = SshExecutionRecord(
            id = "nq-2mb",
            serverId = "srv-2mb",
            serverAlias = "HKG Large Node",
            serverHost = "203.0.113.199",
            presetId = SshPresetCatalog.PRESET_NODE_QUALITY,
            presetName = "NodeQuality (NQ) 质量体检",
            tabTitle = "NodeQuality",
            command = "bash <(curl -sL https://run.NodeQuality.com)",
            sanitizedAnsiOutput = raw2MbNq,
            exitCode = 0,
            status = SshRunStatus.SUCCEEDED,
            startedAtEpochMillis = 1_000L,
            durationMillis = 240_000L,
        )

        val tFormatStart = System.nanoTime()
        val formatted = SshBenchmarkPostFormatter.format(listOf(record2Mb), maskIp = true)
        val formatElapsedMs = (System.nanoTime() - tFormatStart) / 1_000_000.0

        assertTrue("2MB NQ formatting took ${formatElapsedMs}ms (expected < 1500ms)", formatElapsedMs < 1_500.0)
        assertEquals("https://nodequality.com/r/Big2MbToken88", formatted.nodeQualityShareUrl)
        assertTrue(formatted.markdownContent.length < 220_000)
        assertFalse(formatted.markdownContent.contains("203.0.113.199"))
        assertTrue(formatted.markdownContent.contains("203.0.*.*"))

        val tPreviewStart = System.nanoTime()
        val parsedDoc = parseNodeSeekMarkdownDocument(formatted.markdownContent)
        val tabs = (parsedDoc.blocks.first() as MdBlock.TabsContainer).tabs
        assertEquals(4, tabs.size)
        var totalChunks = 0
        for (tab in tabs) {
            val fence = tab.children.first() as MdBlock.CodeFence
            val chunked = AnsiAndCodeFormatter.buildStyledCodeChunks(
                code = fence.code,
                hasAnsi = true,
                isTerminalReport = true,
            )
            totalChunks += chunked.chunks.size
        }
        val previewElapsedMs = (System.nanoTime() - tPreviewStart) / 1_000_000.0
        assertTrue(totalChunks >= 4)
        assertTrue("Markdown preview parse + chunk build took ${previewElapsedMs}ms (expected < 1000ms)", previewElapsedMs < 1_000.0)

        println(
            "=== 2MB NQ BENCHMARK (${raw2MbNq.length} chars) ===\n" +
                "View (StaticTranscript): %.2f ms\n".format(viewElapsedMs) +
                "StageDetector: %.2f ms\n".format(stageElapsedMs) +
                "PostFormatter: %.2f ms (output ${formatted.markdownContent.length} chars)\n".format(formatElapsedMs) +
                "Markdown Preview + Chunked ANSI: %.2f ms ($totalChunks chunks)".format(previewElapsedMs),
        )
    }

    @Test
    fun `regex and inline markdown parsers resist catastrophic backtracking and StackOverflow on adversarial inputs`() {
        // 1. Escaped quotes + unclosed apostrophes that previously caused StackOverflow / multiline scan in CODE_TOKEN_REGEX
        val adversarialCode = buildString {
            append("echo 'unclosed single quote at line 1\n")
            append("val escaped = \"")
            repeat(5_000) { append("\\\"x") }
            append("\"\n")
            repeat(1_000) { i -> append("Line $i doesn't backtrack 128.5 MB/s --flag-$i\n") }
        }
        val tCodeStart = System.nanoTime()
        val highlighted = AnsiAndCodeFormatter.highlightSourceCode(adversarialCode)
        val codeMs = (System.nanoTime() - tCodeStart) / 1_000_000.0
        assertEquals(adversarialCode.length, highlighted.length)
        assertTrue("highlightSourceCode took ${codeMs}ms (expected < 300ms)", codeMs < 300.0)

        // 2. Thousands of unmatched '[', '`', '*', '_', '~', '<', '#' in a single paragraph
        val adversarialParagraph = buildString {
            repeat(3_000) { append("[unclosed_bracket_$it *star _under `tick ~~tilde <tag #hash ") }
        }
        val tInlineStart = System.nanoTime()
        val tokens = tokenizeMarkdownInline(adversarialParagraph)
        val inlineMs = (System.nanoTime() - tInlineStart) / 1_000_000.0
        assertTrue(tokens.isNotEmpty())
        assertTrue("Adversarial inline tokenize took ${inlineMs}ms (expected < 300ms)", inlineMs < 300.0)

        // 3. SanitizedBufferSnapshot.fromSanitizedText on a single 500KB runaway line without newlines
        val runawayLine = "A".repeat(500_000)
        val snap = SanitizedBufferSnapshot.fromSanitizedText(runawayLine, maxLines = 50, maxLineChars = 256)
        assertTrue((snap.activeLine?.ansiText?.length ?: 0) <= 520)
    }

    private fun buildRealisticNqTranscript(
        serverIp: String,
        ipv6: String,
        shareToken: String,
        progressFramesPerStage: Int,
    ): String = buildString {
        append("\u001B[1;36m███╗   ██╗ ██████╗ ██████╗ ███████╗\n")
        append("作者：Lloyd@nodeseek.com\n仓库：github.com/LloydAsp/NodeQuality\u001B[0m\n")
        append("\u001B[1;33m运行 HardwareQuality 测试？[y/f/v/n]：\u001B[0my\n")
        append("\u001B[1;32m安装前清理\u001B[0m\n")
        append("\u001B[1;32m加载 BenchOs\u001B[0m\n")
        for (p in 1..progressFramesPerStage) {
            append("\r######################################## ${(p * 100) / progressFramesPerStage}.0%")
        }
        append("\n\u001B[1;32m硬件信息\u001B[0m\n")

        // Stage 1: Hardware Quality
        append("\u001B[1;32m正在运行硬件质量测试...\u001B[0m\n")
        append("\u001B[H\u001B[2J\u001B[3J")
        append("SPONSOR_AD_LINE_1\nSPONSOR_AD_LINE_2\n")
        for (p in 1..progressFramesPerStage) {
            append("\r\u001B[36m\u001B[1m[$serverIp]# 正在获取CPU信息 .... ⠋ $p%\u001B[0m")
        }
        append("\u001B[2K\u001B[1A\u001B[2K\u001B[1A\u001B[2K")
        append("\r++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++\n")
        append("                       \u001B[1m硬件质量体检报告：\u001B[36m$serverIp\u001B[0m\n")
        append("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++\n")
        append("\u001B[1m1. 基础信息\u001B[0m\n")
        append("处理器：               AMD EPYC 7B13 2 Cores @ 2450 MHz\n")
        append("内存 / 硬盘：          2.0 GiB / 40.0 GiB NVMe\n")
        append("Geekbench 5 跑分：     单核 1380 | 多核 2650\n")
        append("================================================================================\n")
        append("Report Link: https://Report.Check.Place/hardware/hw123\n\n")

        // Stage 2: IP Quality
        append("\u001B[1;32m正在运行 IP 质量测试...\u001B[0m\n")
        for (p in 1..progressFramesPerStage) {
            append("\r\u001B[36m\u001B[1m[$serverIp]# 正在检测流媒体 .... ⠙ $p%\u001B[0m")
        }
        append("\u001B[2K")
        append("\r########################################################################\n")
        append("                   \u001B[1mIP质量体检报告：\u001B[36m$serverIp\u001B[0m\n")
        append("########################################################################\n")
        append("自治系统号：            AS2516 KDDI / SoftBank ($serverIp / $ipv6)\n")
        append("风险评分：              \u001B[32m0 - 极低风险 (原生IP)\u001B[0m\n")
        append("Netflix / ChatGPT：     \u001B[32m原生解锁 (JP)\u001B[0m / \u001B[32m解锁\u001B[0m\n")
        append("========================================================================\n\n")

        // Stage 3: Net Quality
        append("\u001B[1;32m正在运行网络质量测试...\u001B[0m\n")
        for (p in 1..progressFramesPerStage) {
            append("\r\u001B[36m\u001B[1m[$serverIp]# 三网测速中 .... ⠹ $p%\u001B[0m")
        }
        append("\u001B[2K")
        append("\r********************************************************************************\n")
        append("                       \u001B[1m网络质量体检报告：\u001B[36m$serverIp\u001B[0m\n")
        append("********************************************************************************\n")
        append("电信上海：              下载 820 Mbps  上传 650 Mbps  延迟 34 ms\n")
        append("联通北京：              下载 910 Mbps  上传 780 Mbps  延迟 38 ms\n")
        append("移动广州：              下载 880 Mbps  上传 700 Mbps  延迟 45 ms\n")
        append("================================================================================\n\n")

        // Stage 4: Backroute Trace (note: Net.Check.Place -R also emits 网络质量体检报告 header!)
        append("\u001B[1;32m正在运行回程路由追踪...\u001B[0m\n")
        append("********************************************************************************\n")
        append("                       \u001B[1m网络质量体检报告：\u001B[36m$serverIp\u001B[0m\n")
        append("********************************************************************************\n")
        append("北京电信回程：          59.43.130.11 -> 202.97.33.1 [CN2 GIA 直连]\n")
        append("上海联通回程：          219.158.10.22 [CUII AS9929 精品网]\n")
        append("广州移动回程：          223.120.2.88 [CMIN2 AS58807]\n")
        append("================================================================================\n\n")

        // Upload link + cleanup
        append("https://nodequality.com/r/$shareToken\n")
        append("\u001B[1;32m安装后清理\u001B[0m\n")
        append("\u001B[1;32m清理中，请稍后。\u001B[0m\n")
    }

    private fun buildLarge2MbNqTranscript(): String {
        val sb = StringBuilder(2_250_000)
        val ip = "203.0.113.199"
        sb.append("\u001B[1;32m安装前清理\u001B[0m\n\u001B[1;32m加载 BenchOs\u001B[0m\n")
        // 5,000 \r curl download progress frames (~450 KB)
        for (i in 1..5_000) {
            sb.append("\r\u001B[33m################################################## ${(i % 100)}.4% BenchOs.tar.gz\u001B[0m")
        }
        sb.append("\n\u001B[1;32m正在运行硬件质量测试...\u001B[0m\n")
        // 24 lines of dense 24-bit TrueColor pixel-art ads (~120 KB), then erased via \033[1A\033[2K
        for (row in 1..24) {
            for (col in 1..120) {
                sb.append("\u001B[38;2;200;100;50m\u001B[48;2;20;30;40m▀")
            }
            sb.append("\u001B[0m\n")
        }
        for (i in 1..4_000) {
            sb.append("\r\u001B[36m\u001B[1m[$ip]# Fio 磁盘压测进行中 ...................... ⠋ ${i % 100}% \u001B[0m")
        }
        sb.append("\u001B[2K")
        repeat(24) { sb.append("\u001B[1A\u001B[2K") }
        sb.append("\r++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++\n")
        sb.append("                       \u001B[1m硬件质量体检报告：\u001B[36m$ip\u001B[0m\n")
        sb.append("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++\n")
        for (row in 1..300) {
            sb.append("\u001B[1;36m[HW-$row]\u001B[0m Host $ip · NVMe 4K IOPS ${row * 120} · \u001B[32m1.84 GB/s\u001B[0m\n")
        }

        sb.append("\u001B[1;32m正在运行 IP 质量测试...\u001B[0m\n")
        for (i in 1..4_000) {
            sb.append("\r\u001B[36m\u001B[1m[$ip]# 流媒体并发检测中 ...................... ⠙ ${i % 100}% \u001B[0m")
        }
        sb.append("\u001B[2K\r########################################################################\n")
        sb.append("                   \u001B[1mIP质量体检报告：\u001B[36m$ip\u001B[0m\n")
        sb.append("########################################################################\n")
        for (row in 1..300) {
            sb.append("\u001B[32m✓\u001B[0m Region-$row ($ip / 2a01:4f8:c012:9999::1): Native Unlock · Low Risk\n")
        }

        sb.append("\u001B[1;32m正在运行网络质量测试...\u001B[0m\n")
        for (i in 1..4_000) {
            sb.append("\r\u001B[36m\u001B[1m[$ip]# 全球与三网节点测速 .................... ⠹ ${i % 100}% \u001B[0m")
        }
        sb.append("\u001B[2K\r********************************************************************************\n")
        sb.append("                       \u001B[1m网络质量体检报告：\u001B[36m$ip\u001B[0m\n")
        sb.append("********************************************************************************\n")
        for (row in 1..600) {
            sb.append("Node-$row  \u001B[36m985.4 Mbps\u001B[0m / \u001B[32m860.2 Mbps\u001B[0m  31.5 ms  Peer 59.43.130.${(row % 200) + 1}\n")
        }

        sb.append("\u001B[1;32m正在运行回程路由追踪...\u001B[0m\n")
        sb.append("********************************************************************************\n")
        sb.append("                       \u001B[1m网络质量体检报告：\u001B[36m$ip\u001B[0m\n")
        sb.append("********************************************************************************\n")
        while (sb.length < 2_100_000) {
            sb.append("Hop 59.43.180.12 -> 202.97.12.34  \u001B[32mCN2 GIA 32.1 ms\u001B[0m  AS4809 China Telecom\n")
        }
        sb.append("https://nodequality.com/r/Big2MbToken88\n")
        sb.append("\u001B[1;32m安装后清理\u001B[0m\n\u001B[1;32m清理中，请稍后。\u001B[0m\n")
        return sb.toString()
    }
}
