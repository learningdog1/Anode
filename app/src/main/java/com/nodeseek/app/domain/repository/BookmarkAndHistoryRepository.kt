package com.nodeseek.app.domain.repository

import com.nodeseek.app.domain.model.BookmarkedTopic
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface BookmarkAndHistoryRepository {
    // Server-synced collections + local offline cache
    suspend fun setTopicBookmarked(topicId: Long, bookmarked: Boolean): Result<Unit>
    suspend fun getServerBookmarks(page: Int = 1): Result<Pair<List<TopicSummary>, Boolean>>

    // Offline local bookmark snapshot helpers (backed by Room BookmarkDao)
    fun observeLocalBookmarks(): Flow<List<BookmarkedTopic>> = emptyFlow()
    fun observeBookmarkedIds(): Flow<Set<Long>> = emptyFlow()
    suspend fun saveOfflineBookmark(
        summary: TopicSummary,
        detailSnapshot: TopicDetailPage? = null,
        syncedWithServer: Boolean = false,
    ) = Unit
    suspend fun getOfflineBookmarkSnapshot(topicId: Long): TopicDetailPage? = null
    suspend fun removeLocalBookmark(topicId: Long) = Unit

    // Local Room-backed read history
    fun observeReadHistory(): Flow<List<ReadHistoryEntry>>
    fun observeReadStateMap(topicIds: Collection<Long>): Flow<Map<Long, ReadHistoryEntry>>
    suspend fun recordTopicVisit(entry: ReadHistoryEntry)
    suspend fun clearReadHistory()
}
