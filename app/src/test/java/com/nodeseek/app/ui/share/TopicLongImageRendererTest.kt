package com.nodeseek.app.ui.share

import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.FloorComment
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicLongImageRendererTest {

    private fun sampleOpeningFloor(): FloorComment = FloorComment(
        commentId = 1001L,
        floorNumber = 0,
        author = FloorAuthor(
            uid = 888L,
            username = "net_artisan",
            avatarUrl = "https://www.nodeseek.com/avatar/888.png",
            isTopicAuthor = true,
            level = 5,
        ),
        createdAtIso = "2026-11-26T10:00:00Z",
        markdownBody = """
            # DMIT LAX Pro 晚高峰实测
            
            三网回程表现稳定 :ac01: :emoji00:
            
            :::: tabs
            ::: tab-item 基本信息
            | 项目 | 数值 |
            | --- | --- |
            | CPU | 2 vCPU |
            | 线路 | CN2 GIA |
            :::
            ::: tab-item 终端跑分
            ```ansi
            ${'\u001B'}[32mCN2 GIA 延迟 138ms 0% Loss${'\u001B'}[0m
            ```
            :::
            ::::
            
            > 实测晚高峰不丢包
            
            ![测速图](https://img.example.com/bench.png)
            
            [点击参与投票](nsapp://vote/42)
        """.trimIndent(),
        signatureMarkdown = "常驻美西优化机测评",
        drumstickCount = 12,
    )

    private fun sampleHighlightReply(): FloorComment = FloorComment(
        commentId = 2012L,
        floorNumber = 12,
        author = FloorAuthor(
            uid = 999L,
            username = "bench_reviewer",
            level = 3,
        ),
        createdAtIso = "2026-11-26T10:15:00Z",
        markdownBody = "同款机器实测联通 9929 也很稳，感谢分享！",
        isPinned = true,
    )

    @Test
    fun `QR generator creates valid square finder-pattern matrix and rejects blank content`() {
        val url = "https://www.nodeseek.com/post-12345-1"
        val matrix = TopicLongImageQrGenerator.generateQrMatrix(url)
        assertEquals(matrix.width, matrix.height)
        assertTrue("QR width must be >= 21, was ${matrix.width}", matrix.width >= 21)
        assertTrue("QR must contain dark modules", matrix.darkModuleCount() > 50)

        var threw = false
        try {
            TopicLongImageQrGenerator.generateQrMatrix("   ")
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("Blank QR content must throw IllegalArgumentException", threw)
    }

    @Test
    fun `presentation plan and rendered text extraction cover all themes, ANSI, stickers, tables, tabs and highlight reply`() {
        val op = sampleOpeningFloor()
        val reply = sampleHighlightReply()

        for (theme in LongImageThemeStyle.entries) {
            val request = TopicLongImageRequest(
                topicId = 12345L,
                title = "DMIT LAX Pro 晚高峰三网回程测评",
                categorySlug = "review",
                categoryName = "测评",
                targetFloor = op,
                highlightReply = reply,
                topicUrl = "https://www.nodeseek.com/post-12345-1",
                viewCount = 1280,
                bookmarkCount = 64,
                themeStyle = theme,
                includeSignature = true,
                includeQrCode = true,
            )

            val plan = TopicLongImageRenderer.buildPresentationPlan(request)
            assertNotNull("QR matrix must be generated for $theme", plan.qrMatrix)
            assertTrue(plan.nodes.any { it is LongImageRenderNode.CodeBlock && it.isAnsi })
            assertTrue(plan.nodes.any { it is LongImageRenderNode.TableBlock })
            assertTrue(plan.nodes.any { it is LongImageRenderNode.SectionPill && it.isTab })
            assertTrue(plan.nodes.any { it is LongImageRenderNode.SpecialWidgetBlock })
            assertTrue(plan.nodes.any { it is LongImageRenderNode.SignatureSection })
            assertTrue(plan.nodes.any { it is LongImageRenderNode.HighlightReplyHeader && it.floorNumber == 12 })

            val drawn = TopicLongImageRenderer.extractRenderedTexts(request).joinToString("\n")
            assertTrue(drawn.contains("DMIT LAX Pro 晚高峰三网回程测评"))
            assertTrue(drawn.contains("net_artisan"))
            assertTrue(drawn.contains("Lv.5"))
            assertTrue(drawn.contains("CN2 GIA 延迟 138ms 0% Loss"))
            assertFalse("Raw ANSI escape must be stripped", drawn.contains("\u001B["))
            assertTrue(drawn.contains(":ac01:"))
            assertTrue(drawn.contains(":emoji00:"))
            assertTrue(drawn.contains("常驻美西优化机测评"))
            assertTrue(drawn.contains("bench_reviewer"))
            assertTrue(drawn.contains("#12 置顶回复"))
            assertTrue(drawn.contains("同款机器实测联通 9929 也很稳"))
            assertTrue(drawn.contains("https://www.nodeseek.com/post-12345-1"))
        }
    }

    @Test
    fun `planPages slices giant threads into multiple bounded pages with QR footer on final page`() {
        val giantMarkdown = buildString {
            for (i in 1..160) {
                appendLine("## 章节 $i — 线路测试与路由分析")
                appendLine("第 $i 段详细压测说明：晚高峰延迟稳定在 135ms 左右，连续丢包率为 0%。")
                appendLine()
            }
        }
        val request = TopicLongImageRequest(
            topicId = 999L,
            title = "超长测评汇总帖",
            categorySlug = "review",
            categoryName = "测评",
            targetFloor = sampleOpeningFloor().copy(markdownBody = giantMarkdown),
            topicUrl = "https://www.nodeseek.com/post-999-1",
            includeQrCode = true,
        )

        val deterministicMeasurer = object : LongImageTextMeasurer {
            override fun measureTextHeightDp(
                text: String,
                widthDp: Float,
                textSp: Float,
                bold: Boolean,
                monospace: Boolean,
                lineSpacingMult: Float,
            ): Float = estimateJvmTextHeightDp(text, widthDp, textSp, lineSpacingMult)
        }

        val slices = TopicLongImageRenderer.planPages(
            request = request,
            measurer = deterministicMeasurer,
            maxPageHeightDp = TopicLongImageRenderer.MAX_PAGE_HEIGHT_DP,
        )

        assertTrue("Giant post must slice into multiple pages, got ${slices.size}", slices.size >= 2)
        slices.forEachIndexed { idx, slice ->
            assertEquals(idx + 1, slice.pageIndex)
            assertEquals(slices.size, slice.totalPages)
            assertTrue(
                "Page ${slice.pageIndex} height ${slice.totalLogicalHeightDp} must not exceed cap",
                slice.totalLogicalHeightDp <= TopicLongImageRenderer.MAX_PAGE_HEIGHT_DP,
            )
            assertEquals(idx == slices.lastIndex, slice.includeQrFooter)
        }
    }

    @Test
    fun `planPages splits oversized 250-row table and 300-item list without silent clipping or footer overflow`() {
        val markdown = buildString {
            appendLine("# 超大表格与超长列表分页验证")
            appendLine()
            appendLine("| 编号 | 节点 | 延迟 |")
            appendLine("| --- | --- | --- |")
            for (r in 1..250) {
                appendLine("| R$r | Node-$r | ${100 + r % 50}ms |")
            }
            appendLine()
            for (i in 1..300) {
                appendLine("- 列表项 #$i：测试超长列表跨页切分不裁剪不越界")
            }
            appendLine()
            append("> ")
            repeat(650) { idx ->
                append("超长引用段落文本分段校验${idx}，确保单个超长引用块也不会超出单页最大高度限制。")
            }
            appendLine()
        }

        val deterministicMeasurer = object : LongImageTextMeasurer {
            override fun measureTextHeightDp(
                text: String,
                widthDp: Float,
                textSp: Float,
                bold: Boolean,
                monospace: Boolean,
                lineSpacingMult: Float,
            ): Float = estimateJvmTextHeightDp(text, widthDp, textSp, lineSpacingMult)
        }

        for (includeQr in listOf(true, false)) {
            val request = TopicLongImageRequest(
                topicId = 888L,
                title = "250行表格与300项列表分页压力测试",
                categorySlug = "tech",
                categoryName = "技术",
                targetFloor = sampleOpeningFloor().copy(markdownBody = markdown),
                topicUrl = "https://www.nodeseek.com/post-888-1",
                includeQrCode = includeQr,
            )

            val slices = TopicLongImageRenderer.planPages(
                request = request,
                measurer = deterministicMeasurer,
                maxPageHeightDp = TopicLongImageRenderer.MAX_PAGE_HEIGHT_DP,
            )

            assertTrue("Expected multi-page slicing for 250-row table + 300-item list, got ${slices.size}", slices.size >= 4)

            val tableSlices = slices.flatMap { it.blocks }.map { it.node }.filterIsInstance<LongImageRenderNode.TableBlock>()
            assertTrue("250-row table must be split into multiple TableBlock slices", tableSlices.size >= 2)
            assertEquals("All 250 table rows must be preserved across slices", 250, tableSlices.sumOf { it.rows.size })
            assertTrue(
                "Every continuation TableBlock slice must repeat the table header",
                tableSlices.all { it.headers == listOf("编号", "节点", "延迟") },
            )

            val listSlices = slices.flatMap { it.blocks }.map { it.node }.filterIsInstance<LongImageRenderNode.ListBlock>()
            assertTrue("300-item list must be split into multiple ListBlock slices", listSlices.size >= 2)
            assertEquals("All 300 list items must be preserved across slices", 300, listSlices.sumOf { it.items.size })

            for (slice in slices) {
                val chromeOverhead = 10f * 2f + 20f * 2f + if (slice.pageIndex > 1) 44f else 0f
                val footerHeight = if (slice.includeQrFooter) 124f else 44f
                val availableBodyHeight = TopicLongImageRenderer.MAX_PAGE_HEIGHT_DP - chromeOverhead - footerHeight

                assertTrue(
                    "Page ${slice.pageIndex} totalLogicalHeightDp=${slice.totalLogicalHeightDp} must not exceed MAX_PAGE_HEIGHT_DP",
                    slice.totalLogicalHeightDp <= TopicLongImageRenderer.MAX_PAGE_HEIGHT_DP,
                )
                assertTrue(
                    "Page ${slice.pageIndex} contentHeightDp=${slice.contentHeightDp} exceeded availableBodyHeight=$availableBodyHeight",
                    slice.contentHeightDp <= availableBodyHeight,
                )
            }
        }
    }

    @Test
    fun `asset fetcher resolves emoji staticPreviewUrl, attaches Referer, and strips cookies on external hosts`() = runBlocking {
        val op = sampleOpeningFloor()
        val request = TopicLongImageRequest(
            topicId = 55L,
            title = "Asset Check",
            categorySlug = "tech",
            categoryName = "技术",
            targetFloor = op,
            topicUrl = "https://www.nodeseek.com/post-55-1",
        )
        val collectedUrls = TopicLongImageAssetFetcher.collectRequiredAssetUrls(request)
        assertTrue(collectedUrls.contains("https://www.nodeseek.com/static/image/sticker/ac/01.png"))
        assertTrue(
            "Video emoji must resolve to static PNG preview",
            collectedUrls.contains("https://www.nodeseek.com/static/image/sticker/emoji/00.png"),
        )
        assertTrue(collectedUrls.contains("https://img.example.com/bench.png"))

        MockWebServer().use { externalServer ->
            externalServer.start()
            externalServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("PNG_BYTES"),
            )
            externalServer.enqueue(MockResponse().setResponseCode(404))

            val leakingCookieJar = object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                override fun loadForRequest(url: HttpUrl): List<Cookie> = listOf(
                    Cookie.Builder()
                        .name("ns_session")
                        .value("secret_forum_cookie")
                        .domain(url.host)
                        .build(),
                )
            }
            val baseClient = OkHttpClient.Builder().cookieJar(leakingCookieJar).build()

            val okUrl = externalServer.url("/pic.png").toString()
            val bytes = TopicLongImageAssetFetcher.fetchBytesForUrl(okUrl, coilOkHttpClient = baseClient)
            assertNotNull(bytes)
            assertEquals("PNG_BYTES", bytes!!.toString(Charsets.UTF_8))

            val recorded = externalServer.takeRequest()
            assertEquals("https://www.nodeseek.com/", recorded.getHeader("Referer"))
            assertNull("Forum cookies MUST NOT leak to external image host", recorded.getHeader("Cookie"))

            val failUrl = externalServer.url("/missing.png").toString()
            val failedBytes = TopicLongImageAssetFetcher.fetchBytesForUrl(failUrl, coilOkHttpClient = baseClient)
            assertNull("404 must degrade cleanly to null", failedBytes)
        }
    }

    @Test
    fun `planPages splits oversized single Link, oversized Heading, and oversized TopicHeroHeader within MAX_PAGE_HEIGHT_DP`() {
        val deterministicMeasurer = object : LongImageTextMeasurer {
            override fun measureTextHeightDp(
                text: String,
                widthDp: Float,
                textSp: Float,
                bold: Boolean,
                monospace: Boolean,
                lineSpacingMult: Float,
            ): Float = estimateJvmTextHeightDp(text, widthDp, textSp, lineSpacingMult)
        }

        val hugeLabel = "超长链接文本分段验证".repeat(450)
        val hugeHeading = "超长标题分片检查".repeat(500)
        val hugeHeroTitle = "超长主帖标题分片检查".repeat(450)
        val md = buildString {
            appendLine("# $hugeHeading")
            appendLine()
            appendLine("[$hugeLabel](https://example.com/very-long-target)")
        }

        val request = TopicLongImageRequest(
            topicId = 999L,
            title = hugeHeroTitle,
            categorySlug = "tech",
            categoryName = "技术",
            targetFloor = sampleOpeningFloor().copy(markdownBody = md),
            topicUrl = "https://www.nodeseek.com/post-999-1",
            includeQrCode = false,
        )

        val slices = TopicLongImageRenderer.planPages(
            request = request,
            measurer = deterministicMeasurer,
            maxPageHeightDp = TopicLongImageRenderer.MAX_PAGE_HEIGHT_DP,
        )

        assertTrue("Expected multi-page split for huge title + heading + link, got ${slices.size}", slices.size >= 4)
        for (slice in slices) {
            val chromeOverhead = 10f * 2f + 20f * 2f + if (slice.pageIndex > 1) 44f else 0f
            val footerHeight = if (slice.includeQrFooter) 124f else 44f
            val availableBodyHeight = TopicLongImageRenderer.MAX_PAGE_HEIGHT_DP - chromeOverhead - footerHeight
            assertTrue(
                "Page ${slice.pageIndex} contentHeightDp=${slice.contentHeightDp} exceeded availableBodyHeight=$availableBodyHeight",
                slice.contentHeightDp <= availableBodyHeight,
            )
            assertTrue(
                "Page ${slice.pageIndex} totalLogicalHeightDp=${slice.totalLogicalHeightDp} exceeded MAX_PAGE_HEIGHT_DP",
                slice.totalLogicalHeightDp <= TopicLongImageRenderer.MAX_PAGE_HEIGHT_DP,
            )
        }
    }
}
