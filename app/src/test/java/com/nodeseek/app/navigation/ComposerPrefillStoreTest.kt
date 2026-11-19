package com.nodeseek.app.navigation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ComposerPrefillStoreTest {

    @Before
    fun setUp() {
        ComposerPrefillStore.clearForTests()
    }

    @After
    fun tearDown() {
        ComposerPrefillStore.clearForTests()
    }

    @Test
    fun `stores and resolves large ANSI markdown payload without truncation`() {
        val largeAnsiReport = buildString(200_000) {
            append(":::: tabs\n::: tab-item YABS\n```ansi\n")
            repeat(4_000) { i ->
                append("\u001B[32mLine $i: Geekbench / fio / iperf3 benchmark output\u001B[0m\n")
            }
            append("```\n:::\n::::")
        }

        val token = ComposerPrefillStore.put(
            title = "[测评] 东京节点 YABS + NextTrace 跑分",
            markdown = largeAnsiReport,
        )

        val resolved = ComposerPrefillStore.get(token)
        assertEquals("[测评] 东京节点 YABS + NextTrace 跑分", resolved?.title)
        assertEquals(largeAnsiReport, resolved?.markdown)

        ComposerPrefillStore.remove(token)
        assertNull(ComposerPrefillStore.get(token))
    }

    @Test
    fun `evicts oldest entries beyond bounded capacity of 8`() {
        val tokens = (1..10).map { idx ->
            ComposerPrefillStore.put(title = "Title $idx", markdown = "Body $idx")
        }

        assertNull(ComposerPrefillStore.get(tokens[0]))
        assertNull(ComposerPrefillStore.get(tokens[1]))
        for (i in 2 until 10) {
            assertEquals("Title ${i + 1}", ComposerPrefillStore.get(tokens[i])?.title)
        }
    }

    @Test
    fun `blank token safely returns null`() {
        assertNull(ComposerPrefillStore.get(""))
        assertNull(ComposerPrefillStore.get("   "))
    }
}
