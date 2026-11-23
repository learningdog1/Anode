package com.nodeseek.app.ui.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateDialogChangelogParserTest {

    @Test
    fun parseReleaseChangelogBlocks_handlesHeadingsListsQuotesAndCode() {
        val markdown = """
            <!-- auto-generated -->
            ## 本次更新
            - 新增 **深色模式** 优化
              - 支持 `AMOLED` 纯黑对比
            - [x] 修复更新弹窗字体排版
            1. 第一项说明
            2. 第二项说明

            > 提示：建议及时升级

            ---

            ```
            sha256: abcdef
            ```
        """.trimIndent()

        val blocks = parseReleaseChangelogBlocks(markdown)
        assertEquals(5, blocks.size)

        val heading = blocks[0] as ReleaseChangelogBlock.Heading
        assertEquals(2, heading.level)
        assertEquals("本次更新", heading.text)

        val listBlock = blocks[1] as ReleaseChangelogBlock.ListBlock
        assertEquals(5, listBlock.items.size)
        assertEquals(0, listBlock.items[0].indentLevel)
        assertEquals("新增 **深色模式** 优化", listBlock.items[0].text)
        assertEquals(1, listBlock.items[1].indentLevel)
        assertEquals("支持 `AMOLED` 纯黑对比", listBlock.items[1].text)
        assertEquals(true, listBlock.items[2].checked)
        assertEquals(1, listBlock.items[3].orderNumber)
        assertEquals(2, listBlock.items[4].orderNumber)

        val quote = blocks[2] as ReleaseChangelogBlock.Quote
        assertEquals("提示：建议及时升级", quote.text)

        assertTrue(blocks[3] is ReleaseChangelogBlock.Divider)

        val codeBlock = blocks[4] as ReleaseChangelogBlock.CodeBlock
        assertEquals("sha256: abcdef", codeBlock.text)
    }

    @Test
    fun parseReleaseChangelogBlocks_preservesLineBreaksInPlainParagraphs() {
        val markdown = "修复已知问题\n优化设置页布局"
        val blocks = parseReleaseChangelogBlocks(markdown)
        assertEquals(1, blocks.size)
        assertEquals(
            ReleaseChangelogBlock.Paragraph("修复已知问题\n优化设置页布局"),
            blocks[0],
        )
    }

    @Test
    fun formatUpdatePublishedLabel_formatsRelativeAndAbsoluteLabelsNaturally() {
        assertEquals("", formatUpdatePublishedLabel(""))
        assertEquals("发布于 2025-11-20", formatUpdatePublishedLabel("2025-11-20"))
        assertEquals("2 小时前发布", formatUpdatePublishedLabel("2 小时前"))
        assertEquals("昨天发布", formatUpdatePublishedLabel("昨天"))
    }
}
