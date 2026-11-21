package com.nodeseek.app.ui.topic

import com.nodeseek.app.domain.model.TopicDetailPage

/**
 * Pure helpers that decide which floor the reader is "currently reading" in the topic detail
 * LazyColumn, and how many floors the topic has in total. Kept free of Compose types so the
 * logic can be unit tested.
 */

/** Minimal projection of a `LazyListItemInfo` (key, main-axis offset, main-axis size). */
internal data class ReadingListItem(
    val key: Any?,
    val offset: Int,
    val size: Int,
)

/**
 * Fraction of the viewport (from its top edge) used as the "reading line". The floor whose
 * content crosses this line (or is the first one entirely below it) is the one being read.
 */
internal const val READING_LINE_FRACTION = 0.25f

/**
 * Maps a topic-detail LazyColumn item key to the real floor number it renders, or `null` for
 * non-floor chrome (banners, section bars, load-more bars, footer).
 *
 * Keys: `article_header` / `floor_0` → 0 (楼主), replies → `floor_<floorNumber>_<commentId>`.
 */
internal fun floorNumberForListKey(key: Any?): Int? {
    val k = key as? String ?: return null
    return when {
        k == "article_header" || k == "floor_0" -> 0
        k.startsWith("floor_") -> k.removePrefix("floor_").substringBefore('_').toIntOrNull()
        else -> null
    }
}

/**
 * Picks the floor the user is reading from the visible items of the list.
 *
 * Rules:
 * - Only items that map to a real floor (see [floorNumberForListKey]) are considered; the
 *   reply's own floor number from the data is used, never the list index.
 * - The floor being read is the topmost floor item whose top edge is on screen, or that still
 *   covers the reading line (top + [READING_LINE_FRACTION] of the viewport). So a reply that has
 *   scrolled almost completely off the top no longer counts, a short reply that a jump placed at
 *   the very top does count, and a reply that only peeks in at the bottom of the screen does not
 *   (the old behaviour used the bottom-most visible item).
 * - When the list cannot scroll further down, the last fully visible floor wins, so reaching the
 *   end of the thread shows the last floor instead of getting stuck a few floors short.
 * - Works the same in reverse order because it is purely positional.
 *
 * @return the floor number, or `null` when no floor item is visible.
 */
internal fun computeReadingFloor(
    visibleItems: List<ReadingListItem>,
    viewportStart: Int,
    viewportEnd: Int,
    canScrollForward: Boolean,
): Int? {
    val floorItems = visibleItems
        .mapNotNull { item -> floorNumberForListKey(item.key)?.let { floor -> item to floor } }
        .filter { (item, _) -> item.offset + item.size > viewportStart && item.offset < viewportEnd }
        .sortedBy { (item, _) -> item.offset }
    if (floorItems.isEmpty()) return null

    if (!canScrollForward) {
        val lastFullyVisible = floorItems.lastOrNull { (item, _) ->
            item.offset >= viewportStart && item.offset + item.size <= viewportEnd
        }
        if (lastFullyVisible != null) return lastFullyVisible.second
    }

    val viewportSize = (viewportEnd - viewportStart).coerceAtLeast(0)
    val readingLine = viewportStart + (viewportSize * READING_LINE_FRACTION).toInt()
    val reading = floorItems.firstOrNull { (item, _) ->
        // Its beginning is on screen (e.g. right after a jump scrolled it to the top), or it
        // still covers the reading line.
        item.offset >= viewportStart || item.offset + item.size > readingLine
    } ?: floorItems.last()
    return reading.second
}

/**
 * Total floor count shown next to the current floor (e.g. `37/52`).
 *
 * When the real last page is loaded, its highest reply floor is exact and wins. Otherwise
 * falls back to the per-page estimate ([TopicDetailPage.estimatedTotalFloors]) but never below
 * the highest floor actually seen. Previously the maximum over all cached page estimates was
 * used, so a stale `totalPages * 10` guess from page 1 could override the exact last-page value.
 */
internal fun resolveTotalFloors(pages: Collection<TopicDetailPage>, totalPages: Int): Int {
    if (pages.isEmpty()) return 0
    val maxSeenFloor = pages.maxOf { page -> page.replies.maxOfOrNull { it.floorNumber } ?: 0 }
    val lastPageFloor = pages
        .filter { it.currentPage >= totalPages && it.replies.isNotEmpty() }
        .maxOfOrNull { page -> page.replies.maxOf { it.floorNumber } }
    if (lastPageFloor != null) return maxOf(lastPageFloor, maxSeenFloor)
    val estimate = pages.maxOf { it.estimatedTotalFloors }
    return maxOf(estimate, maxSeenFloor)
}
