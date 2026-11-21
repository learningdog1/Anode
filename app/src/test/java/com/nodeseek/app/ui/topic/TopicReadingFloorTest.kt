package com.nodeseek.app.ui.topic

import com.nodeseek.app.domain.model.FloorAuthor
import com.nodeseek.app.domain.model.FloorComment
import com.nodeseek.app.domain.model.TopicDetailPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopicReadingFloorTest {

    private fun reply(floor: Int, offset: Int, size: Int, commentId: Long = floor * 100L) =
        ReadingListItem(key = "floor_${floor}_$commentId", offset = offset, size = size)

    @Test
    fun `list keys map to real floor numbers and chrome is ignored`() {
        assertEquals(0, floorNumberForListKey("article_header"))
        assertEquals(0, floorNumberForListKey("floor_0"))
        assertEquals(37, floorNumberForListKey("floor_37_123456"))
        assertEquals(12, floorNumberForListKey("floor_12_-3"))
        assertNull(floorNumberForListKey("replies_section_bar"))
        assertNull(floorNumberForListKey("earlier_pages_bar"))
        assertNull(floorNumberForListKey("stream_footer"))
        assertNull(floorNumberForListKey("offline_banner"))
        assertNull(floorNumberForListKey(42))
        assertNull(floorNumberForListKey(null))
    }

    @Test
    fun `top of topic reports OP`() {
        val items = listOf(
            ReadingListItem("article_header", 0, 600),
            ReadingListItem("floor_0", 600, 900),
            ReadingListItem("replies_section_bar", 1500, 100),
        )
        assertEquals(0, computeReadingFloor(items, 0, 2000, canScrollForward = true))
    }

    @Test
    fun `uses topmost reply being read instead of bottom-most visible one`() {
        // Viewport 0..2000, reading line at 500.
        val items = listOf(
            reply(11, -100, 800),  // bottom at 700 > 500 -> being read
            reply(12, 700, 600),
            reply(13, 1300, 600),
            reply(14, 1900, 600),  // just peeking in at the bottom
        )
        assertEquals(11, computeReadingFloor(items, 0, 2000, canScrollForward = true))
    }

    @Test
    fun `reply mostly scrolled off the top no longer counts`() {
        val items = listOf(
            reply(11, -700, 800),  // only 100px left visible, above reading line
            reply(12, 100, 600),
            reply(13, 700, 600),
        )
        assertEquals(12, computeReadingFloor(items, 0, 2000, canScrollForward = true))
    }

    @Test
    fun `short reply scrolled to the top by a jump is reported`() {
        val items = listOf(
            reply(37, 0, 150),     // one-liner, entirely above the reading line
            reply(38, 150, 600),
            reply(39, 750, 600),
        )
        assertEquals(37, computeReadingFloor(items, 0, 2000, canScrollForward = true))
        // Once it starts scrolling off the top it hands over to the next floor.
        val scrolled = items.map { it.copy(offset = it.offset - 20) }
        assertEquals(38, computeReadingFloor(scrolled, 0, 2000, canScrollForward = true))
    }

    @Test
    fun `reply taller than the viewport is reported while it fills the screen`() {
        val items = listOf(reply(5, -3000, 6000))
        assertEquals(5, computeReadingFloor(items, 0, 2000, canScrollForward = true))
    }

    @Test
    fun `uses data floor numbers so deleted floors and pagination do not shift the value`() {
        // Floors 21, 22 deleted; page 3 starts at 23 in a multi-page window.
        val items = listOf(
            reply(20, -650, 600),
            ReadingListItem("earlier_pages_bar", -50, 100),
            reply(23, 50, 600),
            reply(24, 650, 600),
        )
        assertEquals(23, computeReadingFloor(items, 0, 2000, canScrollForward = true))
    }

    @Test
    fun `section bar on the reading line resolves to the next reply`() {
        val items = listOf(
            ReadingListItem("floor_0", -1000, 1100),   // bottom at 100, above reading line 500
            ReadingListItem("replies_section_bar", 100, 450),
            reply(1, 550, 600),
        )
        assertEquals(1, computeReadingFloor(items, 0, 2000, canScrollForward = true))
    }

    @Test
    fun `reverse order is purely positional`() {
        val items = listOf(
            reply(50, -100, 800),
            reply(49, 700, 600),
            reply(48, 1300, 600),
        )
        assertEquals(50, computeReadingFloor(items, 0, 2000, canScrollForward = true))
    }

    @Test
    fun `end of list reports the last fully visible floor`() {
        val items = listOf(
            reply(47, -100, 800),
            reply(48, 700, 400),
            reply(49, 1100, 400),
            reply(50, 1500, 300),
            ReadingListItem("stream_footer", 1800, 200),
        )
        assertEquals(50, computeReadingFloor(items, 0, 2000, canScrollForward = false))
        assertEquals(47, computeReadingFloor(items, 0, 2000, canScrollForward = true))
    }

    @Test
    fun `no floor items visible returns null`() {
        val items = listOf(ReadingListItem("stream_footer", 0, 200))
        assertNull(computeReadingFloor(items, 0, 2000, canScrollForward = true))
        assertNull(computeReadingFloor(emptyList(), 0, 2000, canScrollForward = true))
    }

    // ── Total floors ───────────────────────────────────────────────────────────

    private val author = FloorAuthor(uid = 1L, username = "op", avatarUrl = "")

    private fun page(currentPage: Int, totalPages: Int, floors: IntRange) = TopicDetailPage(
        topicId = 1L,
        title = "t",
        categorySlug = "tech",
        categoryName = "tech",
        author = author,
        currentPage = currentPage,
        totalPages = totalPages,
        replies = floors.map { f ->
            FloorComment(commentId = f.toLong(), floorNumber = f, author = author, createdAtIso = "", markdownBody = "")
        },
    )

    @Test
    fun `exact last page floor beats stale page estimate`() {
        val pages = listOf(page(1, 3, 1..10), page(3, 3, 21..23))
        assertEquals(23, resolveTotalFloors(pages, totalPages = 3))
    }

    @Test
    fun `falls back to estimate when last page not loaded`() {
        val pages = listOf(page(1, 3, 1..10))
        assertEquals(30, resolveTotalFloors(pages, totalPages = 3))
    }

    @Test
    fun `stale last page is not treated as last once more pages exist`() {
        val pages = listOf(page(3, 3, 21..30), page(4, 4, 31..32))
        assertEquals(32, resolveTotalFloors(pages, totalPages = 4))
    }

    @Test
    fun `single page topic uses highest floor even with deleted floors`() {
        val pages = listOf(page(1, 1, 1..5).let { p -> p.copy(replies = p.replies.filter { it.floorNumber != 3 }) })
        assertEquals(5, resolveTotalFloors(pages, totalPages = 1))
        assertEquals(0, resolveTotalFloors(emptyList(), totalPages = 1))
    }
}
