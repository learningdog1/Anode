package com.nodeseek.app.ui.components

import com.nodeseek.app.data.network.CoilHttpClientFactory
import com.nodeseek.app.data.parser.NodeSeekHtmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class MarkdownParserTest {

    @Test
    fun `linked badge row unwraps NodeSeek jump URL and groups consecutive badges into ImageRow`() {
        val md = """
            [![Badge](https://img.shields.io/badge/test-ok-blue.svg)](https://www.nodeseek.com/jump?to=https%3A%2F%2Fexample.com)
            [![Status](https://badgen.net/badge/uptime/99.9%25/green)](https://status.example.com)
        """.trimIndent()

        val blocks = parseNodeSeekMarkdownBlocks(md)
        assertEquals(1, blocks.size)

        val row = blocks.single() as MdBlock.ImageRow
        assertEquals(2, row.items.size)
        assertEquals(
            MdImageItem(
                imageUrl = "https://img.shields.io/badge/test-ok-blue.svg",
                alt = "Badge",
                linkUrl = "https://example.com",
            ),
            row.items[0],
        )
        assertEquals("Status", row.items[1].alt)
        assertEquals("https://status.example.com", row.items[1].linkUrl)
    }

    @Test
    fun `inline HTML linked image and br tags tokenize cleanly`() {
        val input = """<a href="https://example.com"><img src="https://img.example.com/b.png" alt="B" /></a><br><b>探针在线</b>"""

        val tokens = tokenizeMarkdownInline(input)
        assertEquals(3, tokens.size)
        assertEquals(
            MdInlineToken.Image(
                alt = "B",
                url = "https://img.example.com/b.png",
                linkUrl = "https://example.com",
            ),
            tokens[0],
        )
        assertEquals(MdInlineToken.LineBreak, tokens[1])
        assertEquals(
            MdInlineToken.Styled(
                children = listOf(MdInlineToken.PlainText("探针在线")),
                bold = true,
            ),
            tokens[2],
        )
    }

    @Test
    fun `details spoiler block extracts summary and inner blocks`() {
        val md = """
            <details><summary>测速详情</summary>

            三网回程 CN2 GIA
            </details>
        """.trimIndent()

        val blocks = parseNodeSeekMarkdownBlocks(md)
        assertEquals(1, blocks.size)

        val spoiler = blocks.single() as MdBlock.DetailsSpoiler
        assertEquals("测速详情", spoiler.summary)
        assertEquals(listOf(MdBlock.Paragraph("三网回程 CN2 GIA")), spoiler.children)
    }

    @Test
    fun `balanced parentheses URLs, reference links, multi-backtick code spans, and intraword underscores`() {
        val md = """
            查看 [Wiki](https://en.wikipedia.org/wiki/Markdown_(markup)) 与 [官网][ref]，代码 ``a `b` c``，变量 foo_bar_baz 不应斜体。

            [ref]: https://www.nodeseek.com
        """.trimIndent()

        val doc = parseNodeSeekMarkdownDocument(md)
        assertEquals(1, doc.blocks.size)
        val paragraph = doc.blocks.single() as MdBlock.Paragraph

        val tokens = tokenizeMarkdownInline(paragraph.text, doc.referenceLinks)
        val links = tokens.filterIsInstance<MdInlineToken.Link>()
        assertEquals(2, links.size)
        assertEquals("https://en.wikipedia.org/wiki/Markdown_(markup)", links[0].url)
        assertEquals("https://www.nodeseek.com", links[1].url)

        val codeSpan = tokens.filterIsInstance<MdInlineToken.CodeSpan>().single()
        assertEquals(MdInlineToken.CodeSpan("a `b` c"), codeSpan)

        assertTrue(tokens.none { it is MdInlineToken.Styled && it.italic })
        val joinedPlainText = tokens.filterIsInstance<MdInlineToken.PlainText>().joinToString("") { it.text }
        assertTrue(joinedPlainText.contains("foo_bar_baz"))
    }

    @Test
    fun `stripMarkdownToPlainPreview produces clean single-line text for inbox and DM previews`() {
        val input = """[![Badge](https://img.shields.io/badge/ok.svg)](https://example.com) **你好** [节点测评](https://www.nodeseek.com/post-1-1) :ac01: <br> `code`"""

        val preview = NodeSeekHtmlParser.stripMarkdownToPlainPreview(input)
        assertEquals("[图片] 你好 节点测评 [表情] code", preview)
    }

    @Test
    fun `HTML h1 to h6 tags parse cleanly in block, multiline, inline, and malformed signature markdown`() {
        val blockHtmlMd = """
            <h2 class="sig-title">个人探针与服务器签名</h2>
            <p>香港 CN2 / 东京软银</p>
        """.trimIndent()
        val blocks = parseNodeSeekMarkdownBlocks(blockHtmlMd)
        assertEquals(
            listOf(
                MdBlock.Heading(level = 2, text = "个人探针与服务器签名"),
                MdBlock.Paragraph("香港 CN2 / 东京软银"),
            ),
            blocks,
        )

        val inlineHtmlTokens = tokenizeMarkdownInline("联系我：<h2>TG @nodeseek</h2> 谢谢<h2>")
        fun flattenTokens(tokens: List<MdInlineToken>): String = tokens.joinToString("") { token ->
            when (token) {
                is MdInlineToken.PlainText -> token.text
                is MdInlineToken.Mention -> "@${token.username}"
                is MdInlineToken.Styled -> flattenTokens(token.children)
                else -> ""
            }
        }
        val renderedPlainAndStyled = flattenTokens(inlineHtmlTokens)
        assertEquals("联系我：TG @nodeseek 谢谢", renderedPlainAndStyled)
        assertTrue(inlineHtmlTokens.any { it is MdInlineToken.Styled && it.bold })
    }

    @Test
    fun `lazy HTML img attributes, placeholder src override, angle-bracket URLs, and URL normalization resolve cleanly`() {
        val lazyHtmlTokens = tokenizeMarkdownInline(
            """<img src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7" data-src="//img.nodeimage.com/i/screen shot.png" alt="测速图">""",
        )
        assertEquals(
            listOf(
                MdInlineToken.Image(
                    alt = "测速图",
                    url = "https://img.nodeimage.com/i/screen shot.png",
                    linkUrl = null,
                ),
            ),
            lazyHtmlTokens,
        )

        val domMd = NodeSeekHtmlParser.htmlFragmentToMarkdown(
            """<p><img src="data:image/png;base64,iVBOR" data-original="//cdn.nodeimage.com/a/1.webp" alt="" /></p>""",
        )
        assertEquals("![](https://cdn.nodeimage.com/a/1.webp)", domMd.trim())

        val mdWithEmptyAltAndSpaces = """
            ![](https://img.nodeimage.com/i/just_uploaded.png)

            ![评测截图](<https://img.nodeimage.com/i/my screen.webp>)
        """.trimIndent()
        val blocks = parseNodeSeekMarkdownBlocks(mdWithEmptyAltAndSpaces)
        assertEquals(2, blocks.size)
        val firstCard = blocks[0] as MdBlock.ImageCard
        val secondCard = blocks[1] as MdBlock.ImageCard
        assertEquals("", firstCard.alt)
        assertEquals("https://img.nodeimage.com/i/just_uploaded.png", firstCard.url)
        assertEquals("评测截图", secondCard.alt)
        assertEquals("https://img.nodeimage.com/i/my%20screen.webp", secondCard.url)

        assertEquals("https://img.nodeimage.com/a.jpg", normalizeMarkdownImageUrl("HTTP://img.nodeimage.com/a.jpg"))
        assertEquals("https://img.nodeimage.com/a.png", normalizeMarkdownImageUrl("//img.nodeimage.com/a.png"))
        assertEquals("http://localhost:8080/a.png", normalizeMarkdownImageUrl("http://localhost:8080/a.png"))
        assertEquals("data:image/png;base64,AAAA", normalizeMarkdownImageUrl("data:image/png;base64,AAAA"))
    }

    @Test
    fun `NodeQuality svg reports and linked images promote to ImageCard while shields stay badges`() {
        assertFalse(isBadgeOrSmallGraphicUrl("https://Report.Check.Place/hardware/abc123.svg"))
        assertFalse(isBadgeOrSmallGraphicUrl("https://Report.Check.Place/ip/xyz987.svg"))
        assertTrue(isBadgeOrSmallGraphicUrl("https://img.shields.io/badge/build-passing-green.svg"))
        assertTrue(isBadgeOrSmallGraphicUrl("https://hits.xykt.de/ip.svg"))
        assertTrue(isBadgeOrSmallGraphicUrl("https://star.xykt.de/xykt/IPQuality.svg"))

        val md = """
            ![基本信息](https://Report.Check.Place/hardware/abc123.svg)
            [![IP质量](https://Report.Check.Place/ip/xyz987.svg)](https://Report.Check.Place/ip/xyz987)
        """.trimIndent()

        val blocks = parseNodeSeekMarkdownBlocks(md)
        assertEquals(2, blocks.size)

        val basicCard = blocks[0] as MdBlock.ImageCard
        assertEquals("基本信息", basicCard.alt)
        assertEquals("https://Report.Check.Place/hardware/abc123.svg", basicCard.url)
        assertNull(basicCard.linkUrl)

        val ipCard = blocks[1] as MdBlock.ImageCard
        assertEquals("IP质量", ipCard.alt)
        assertEquals("https://Report.Check.Place/ip/xyz987.svg", ipCard.url)
        assertEquals("https://Report.Check.Place/ip/xyz987", ipCard.linkUrl)
    }

    @Test
    fun `NodeQuality tabs document preserves ANSI terminal blocks and report images`() {
        val nqMarkdown = """
            :::: tabs
            ::: tab-item 💻基本信息
            ```ansi
            ╭────────────────────────────────────────────────────────────────────────╮
            │ [1mBasic System Info[0m                                               │
            ╰────────────────────────────────────────────────────────────────────────╯
            ```
            :::
            ::: tab-item 🎬IP质量
            ```ansi
            ${'\u001B'}[36m自治系统号：AS152672    风险评分：低风险${'\u001B'}[0m
            ```
            :::
            ::: tab-item 🌐网络质量
            ![image](https://i.111666.best/image/net_quality.webp)
            :::
            ::: tab-item 📍回程路由
            ![image](https://i.111666.best/image/backroute.webp)
            :::
            ::::
        """.trimIndent()

        val blocks = parseNodeSeekMarkdownBlocks(nqMarkdown)
        assertEquals(1, blocks.size)
        val tabs = (blocks.single() as MdBlock.TabsContainer).tabs
        assertEquals(4, tabs.size)
        assertEquals("💻基本信息", tabs[0].title)
        assertEquals("🎬IP质量", tabs[1].title)
        assertEquals("🌐网络质量", tabs[2].title)
        assertEquals("📍回程路由", tabs[3].title)

        val basicFence = tabs[0].children.single() as MdBlock.CodeFence
        assertEquals("ansi", basicFence.language)
        assertTrue(isTerminalReportCodeBlock(basicFence.language, basicFence.code, hasAnsiEscapes = false))

        val ipFence = tabs[1].children.single() as MdBlock.CodeFence
        assertTrue(AnsiAndCodeFormatter.containsAnsiEscapes(ipFence.code))
        assertTrue(
            isTerminalReportCodeBlock(
                ipFence.language,
                ipFence.code,
                AnsiAndCodeFormatter.containsAnsiEscapes(ipFence.code),
            ),
        )

        val netImg = tabs[2].children.single() as MdBlock.ImageCard
        assertEquals("https://i.111666.best/image/net_quality.webp", netImg.url)

        val routeImg = tabs[3].children.single() as MdBlock.ImageCard
        assertEquals("https://i.111666.best/image/backroute.webp", routeImg.url)

        assertTrue(
            isTerminalReportCodeBlock(
                language = "",
                code = "┌───────┬───────┐\n│ Host  │ Ping  │\n└───────┴───────┘",
                hasAnsiEscapes = false,
            ),
        )
        assertFalse(
            isTerminalReportCodeBlock(
                language = "kotlin",
                code = "val box = \"┌─│─┐\"",
                hasAnsiEscapes = false,
            ),
        )
    }

    @Test
    fun `extractImageTableCardsOrNull unwraps benchmark image tables and ignores data tables`() {
        val sideBySideTableMd = """
            | 基本信息 | IP质量 |
            | --- | --- |
            | ![基本信息](https://i.111666.best/image/basic.png) | [![IP质量](https://Report.Check.Place/ip/test.svg)](https://Report.Check.Place/ip/test) |
            | 网络质量 | 回程路由 |
            | ![网络质量](https://i.111666.best/image/net.png) | ![回程路由](https://i.111666.best/image/route.png) |
        """.trimIndent()

        val sideBySideTable = parseNodeSeekMarkdownBlocks(sideBySideTableMd).single() as MdBlock.Table
        val unwrapped = extractImageTableCardsOrNull(sideBySideTable, emptyMap())
        assertNotNull(unwrapped)
        assertEquals(4, unwrapped!!.size)
        assertEquals("基本信息", unwrapped[0].label)
        assertEquals("https://i.111666.best/image/basic.png", unwrapped[0].image.imageUrl)
        assertEquals("IP质量", unwrapped[1].label)
        assertEquals("https://Report.Check.Place/ip/test.svg", unwrapped[1].image.imageUrl)
        assertEquals("https://Report.Check.Place/ip/test", unwrapped[1].image.linkUrl)
        assertEquals("网络质量", unwrapped[2].label)
        assertEquals("回程路由", unwrapped[3].label)

        val singleColHeaderImageTableMd = """
            | ![基本信息](https://i.111666.best/image/basic.png) |
            | --- |
            | ![IP质量](https://i.111666.best/image/ip.png) |
        """.trimIndent()
        val singleColTable = parseNodeSeekMarkdownBlocks(singleColHeaderImageTableMd).single() as MdBlock.Table
        val singleColCards = extractImageTableCardsOrNull(singleColTable, emptyMap())
        assertNotNull(singleColCards)
        assertEquals(2, singleColCards!!.size)
        assertNull(singleColCards[0].label)
        assertEquals("https://i.111666.best/image/basic.png", singleColCards[0].image.imageUrl)
        assertEquals("https://i.111666.best/image/ip.png", singleColCards[1].image.imageUrl)

        val normalDataTableMd = """
            | 项目 | 结果 |
            | --- | --- |
            | CPU | EPYC 9654 |
            | 内存 | 4 GB |
        """.trimIndent()
        val dataTable = parseNodeSeekMarkdownBlocks(normalDataTableMd).single() as MdBlock.Table
        assertNull(extractImageTableCardsOrNull(dataTable, emptyMap()))
    }

    @Test
    fun `normalizeSvgForAndroidSvg converts unsupported ch units and injects viewBox`() {
        val rawIpQualitySvg = """
            <svg width="74ch" height="49em" xmlns="http://www.w3.org/2000/svg" xml:space="preserve">
                <style>
                    * { font-size: 14px; }
                </style>
                <rect x="11ch" y="40em" width="6ch" height="1em" class="ba2"/>
                <text x="0ch" y="0.5em"><tspan>IP质量体检报告</tspan></text>
            </svg>
        """.trimIndent()

        val normalized = CoilHttpClientFactory.normalizeSvgForAndroidSvg(rawIpQualitySvg)
        assertFalse(normalized.contains("ch\""))
        assertTrue(normalized.contains("""width="621.6px""""))
        assertTrue(normalized.contains("""height="686px""""))
        assertTrue(normalized.contains("""viewBox="0 0 621.6 686""""))
        assertTrue(normalized.contains("""x="92.4px""""))
        assertTrue(normalized.contains("""width="50.4px""""))

        val standardSvg = """<svg viewBox="0 0 120 20" width="120" height="20"><rect width="120" height="20"/></svg>"""
        assertEquals(standardSvg, CoilHttpClientFactory.normalizeSvgForAndroidSvg(standardSvg))
    }

    @Test
    fun `detectImageFormat identifies raster magic bytes, svg markup, and header or url fallbacks`() {
        val pngBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00,
        )
        assertEquals(
            DetectedImageFormat("image/png", "png", isSvg = false),
            detectImageFormat(pngBytes, "image/jpeg", "https://img.example.com/photo.jpg"),
        )

        val jpgBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals(
            DetectedImageFormat("image/jpeg", "jpg", isSvg = false),
            detectImageFormat(jpgBytes, null, "https://img.example.com/raw"),
        )

        val gifBytes = "GIF89a...".toByteArray(StandardCharsets.US_ASCII)
        assertEquals(
            DetectedImageFormat("image/gif", "gif", isSvg = false),
            detectImageFormat(gifBytes, null, "https://img.example.com/sticker"),
        )

        val webpBytes = "RIFF1234WEBP".toByteArray(StandardCharsets.US_ASCII)
        assertEquals(
            DetectedImageFormat("image/webp", "webp", isSvg = false),
            detectImageFormat(webpBytes, null, "https://img.example.com/photo.jpg?fmt=webp"),
        )

        val bmpBytes = byteArrayOf(0x42, 0x4D, 0x10, 0x00)
        assertEquals(
            DetectedImageFormat("image/bmp", "bmp", isSvg = false),
            detectImageFormat(bmpBytes, null, "https://img.example.com/raw"),
        )

        val avifBytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x1C,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'a'.code.toByte(), 'v'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(),
        )
        assertEquals(
            DetectedImageFormat("image/avif", "avif", isSvg = false),
            detectImageFormat(avifBytes, null, "https://img.example.com/raw"),
        )

        val directSvg = """<svg xmlns="http://www.w3.org/2000/svg"></svg>""".toByteArray(StandardCharsets.UTF_8)
        assertEquals(
            DetectedImageFormat("image/svg+xml", "svg", isSvg = true),
            detectImageFormat(directSvg, null, "https://Report.Check.Place/ip/xyz"),
        )

        val xmlSvg = """<?xml version="1.0" encoding="UTF-8"?>  <svg viewBox="0 0 10 10"></svg>"""
            .toByteArray(StandardCharsets.UTF_8)
        assertEquals(
            DetectedImageFormat("image/svg+xml", "svg", isSvg = true),
            detectImageFormat(xmlSvg, null, "https://Report.Check.Place/hardware/abc"),
        )

        val unknownBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        assertEquals(
            DetectedImageFormat("image/webp", "webp", isSvg = false),
            detectImageFormat(unknownBytes, "image/webp; charset=binary", "https://img.example.com/file"),
        )
        assertEquals(
            DetectedImageFormat("image/png", "png", isSvg = false),
            detectImageFormat(unknownBytes, null, "https://img.example.com/pic.png?size=large#top"),
        )
    }

    @Test
    fun `decodeDataUriImage and buildSavedImageFileName handle data uris and safe filenames`() {
        val pngPayload = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val b64 = Base64.getEncoder().encodeToString(pngPayload)
        val decoded = decodeDataUriImage("data:image/png;base64,$b64")
        assertNotNull(decoded)
        assertEquals("image/png", decoded!!.second)
        assertEquals(pngPayload.toList(), decoded.first.toList())

        val decodedSvg = decodeDataUriImage("data:image/svg+xml,%3Csvg%3E%3C%2Fsvg%3E")
        assertNotNull(decodedSvg)
        assertEquals("image/svg+xml", decodedSvg!!.second)
        assertEquals("<svg></svg>", String(decodedSvg.first, StandardCharsets.UTF_8))

        assertNull(decodeDataUriImage("https://img.example.com/a.png"))
        assertNull(decodeDataUriImage("data:image/png;base64,"))
        assertNull(decodeDataUriImage("data:image/png;base64,!!!not-base64!!!"))

        val fileName = buildSavedImageFileName(
            altText = "测速/截图:Report\\2026?",
            url = "https://img.nodeimage.com/i/test.webp?v=1",
            extension = "WEBP",
            nowMillis = 1_760_000_000_000L,
        )
        assertTrue("Unexpected filename: $fileName", Regex("""^Anode_\d{8}_\d{6}_[0-9a-f]{6}\.webp$""").matches(fileName))
    }

    @Test
    fun `isTerminalReportDisplayMode strips header chrome for terminal reports while preserving normal code cards`() {
        // ANSI & box-art terminal blocks inside or outside tabs enter clean terminal report display mode
        assertTrue(
            isTerminalReportDisplayMode(
                language = "ansi",
                code = "Host: NodeSeek-VPS",
                hasAnsiEscapes = false,
                insideTabsContainer = true,
            ),
        )
        assertTrue(
            isTerminalReportDisplayMode(
                language = "ansi",
                code = "Host: NodeSeek-VPS",
                hasAnsiEscapes = false,
                insideTabsContainer = false,
            ),
        )
        assertTrue(
            isTerminalReportDisplayMode(
                language = "text",
                code = "AS152672  Low Risk",
                hasAnsiEscapes = false,
                insideTabsContainer = true,
            ),
        )
        assertTrue(
            isTerminalReportDisplayMode(
                language = "",
                code = "Plain tab summary",
                hasAnsiEscapes = false,
                insideTabsContainer = true,
            ),
        )
        assertTrue(
            isTerminalReportDisplayMode(
                language = "",
                code = "╭──────╮\n│ Info │\n╰──────╯",
                hasAnsiEscapes = false,
                insideTabsContainer = false,
            ),
        )
        assertTrue(
            isTerminalReportDisplayMode(
                language = "bash",
                code = "\u001B[32mPASS\u001B[0m",
                hasAnsiEscapes = true,
                insideTabsContainer = false,
            ),
        )

        // Standard source code blocks keep normal code card header (dots, language badge, copy button)
        assertFalse(
            isTerminalReportDisplayMode(
                language = "kotlin",
                code = "val x = 1",
                hasAnsiEscapes = false,
                insideTabsContainer = true,
            ),
        )
        assertFalse(
            isTerminalReportDisplayMode(
                language = "bash",
                code = "curl -sL https://run.NodeQuality.com | bash",
                hasAnsiEscapes = false,
                insideTabsContainer = true,
            ),
        )
        assertFalse(
            isTerminalReportDisplayMode(
                language = "json",
                code = "{\"ok\": true}",
                hasAnsiEscapes = false,
                insideTabsContainer = false,
            ),
        )
        assertFalse(
            isTerminalReportDisplayMode(
                language = "",
                code = "server { listen 80; }",
                hasAnsiEscapes = false,
                insideTabsContainer = false,
            ),
        )
    }

    @Test
    fun `extractMarkdownImagePrefetchTargets collects and normalizes all tab images for preloading`() {
        val md = """
            :::: tabs
            ::: tab-item 💻基本信息
            ```ansi
            ╭────────────────────╮
            │ Basic System Info  │
            ╰────────────────────╯
            ```
            :::
            ::: tab-item 🌐网络质量
            ![](//i.111666.best/image/net_quality.webp)
            :::
            ::: tab-item 📍回程路由
            路由详情：[![回程](http://img.nodeimage.com/route.png)](https://example.com) ![延迟][lat_ref] :ac01:
            :::
            ::: tab-item 📦其他
            | 节点A | 节点B |
            | --- | --- |
            | ![A](https://i.111666.best/image/a.png) | ![B](https://Report.Check.Place/ip/b.svg) |

            <details><summary>更多截图</summary>

            ![详情](https://i.111666.best/image/detail.png)
            </details>

            > ![引用图](https://i.111666.best/image/net_quality.webp)
            :::
            ::::

            [lat_ref]: https://i.111666.best/image/latency.svg
        """.trimIndent()

        val doc = parseNodeSeekMarkdownDocument(md)
        val tabs = (doc.blocks.single() as MdBlock.TabsContainer).tabs
        val targets = extractMarkdownImagePrefetchTargets(tabs, doc.referenceLinks)

        assertEquals(
            listOf(
                MarkdownImagePrefetchTarget("https://i.111666.best/image/net_quality.webp", useFullCardWidth = true),
                MarkdownImagePrefetchTarget("https://img.nodeimage.com/route.png", useFullCardWidth = true),
                MarkdownImagePrefetchTarget("https://i.111666.best/image/latency.svg", useFullCardWidth = true),
                MarkdownImagePrefetchTarget("https://www.nodeseek.com/static/image/sticker/ac/01.png", useFullCardWidth = false),
                MarkdownImagePrefetchTarget("https://i.111666.best/image/a.png", useFullCardWidth = true),
                MarkdownImagePrefetchTarget("https://Report.Check.Place/ip/b.svg", useFullCardWidth = true),
                MarkdownImagePrefetchTarget("https://i.111666.best/image/detail.png", useFullCardWidth = true),
            ),
            targets,
        )
        assertEquals(7, extractMarkdownImageUrls(tabs, doc.referenceLinks).size)
    }

    @Test
    fun `computeImageLayoutMeta preserves report aspect ratios and flags tiny rasters only`() {
        val (portraitRatio, portraitTiny) = computeImageLayoutMeta(width = 1000f, height = 2000f, isSvgUrl = false)
        assertEquals(0.5f, portraitRatio!!, 0.0001f)
        assertFalse(portraitTiny)

        val (clampedTallRatio, _) = computeImageLayoutMeta(width = 100f, height = 2000f, isSvgUrl = false)
        assertEquals(0.12f, clampedTallRatio!!, 0.0001f)

        val (clampedWideRatio, _) = computeImageLayoutMeta(width = 1200f, height = 100f, isSvgUrl = false)
        assertEquals(6.0f, clampedWideRatio!!, 0.0001f)

        val (tinyRatio, isTiny) = computeImageLayoutMeta(width = 64f, height = 64f, isSvgUrl = false)
        assertNull(tinyRatio)
        assertTrue(isTiny)

        val (svgSmallRatio, svgTiny) = computeImageLayoutMeta(width = 80f, height = 60f, isSvgUrl = true)
        assertEquals(80f / 60f, svgSmallRatio!!, 0.0001f)
        assertFalse(svgTiny)

        assertEquals(null to false, computeImageLayoutMeta(width = 0f, height = 100f, isSvgUrl = false))
    }
}
