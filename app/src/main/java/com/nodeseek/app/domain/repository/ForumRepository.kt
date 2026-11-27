package com.nodeseek.app.domain.repository

import com.nodeseek.app.domain.model.Category
import com.nodeseek.app.domain.model.FeedPageResult
import com.nodeseek.app.domain.model.FeedSort
import com.nodeseek.app.domain.model.ReactionResult
import com.nodeseek.app.domain.model.ReactionType
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
import kotlinx.coroutines.flow.Flow

interface ForumRepository {
    fun observeCategories(): Flow<List<Category>>
    suspend fun refreshCategories(force: Boolean = false): Result<List<Category>>
    suspend fun syncHomepageCategories(): Result<List<Category>> = Result.success(emptyList())
    suspend fun saveHomepageCategories(
        orderedCategories: List<Category>,
        syncToServer: Boolean = true,
    ): Result<Unit> = Result.success(Unit)

    fun getCachedFeed(
        categorySlug: String = "",
        sort: FeedSort = FeedSort.LATEST_REPLY,
    ): FeedPageResult? = null

    fun isFeedFresh(
        categorySlug: String = "",
        sort: FeedSort = FeedSort.LATEST_REPLY,
        maxAgeMillis: Long = 90_000L,
    ): Boolean = false

    fun clearRssFallbackCache() {}

    /**
     * Resolves known [TopicSummary] metadata (such as authoritative `categorySlug` and `categoryName`)
     * from local in-memory/disk feed caches, bookmarks, and read history without making network requests.
     */
    suspend fun resolveKnownTopics(topicIds: Collection<Long>): Map<Long, TopicSummary> = emptyMap()

    /**
     * Returns locally cached [TopicSummary] items for [categorySlug] from in-memory & disk feed
     * snapshots without performing any network requests.
     */
    suspend fun getLocalCachedTopics(categorySlug: String): List<TopicSummary> = emptyList()

    suspend fun getFeed(
        categorySlug: String = "",
        sort: FeedSort = FeedSort.LATEST_REPLY,
        page: Int = 1,
        includeBlocked: Boolean = false,
        forceRefresh: Boolean = false,
    ): Result<FeedPageResult>

    suspend fun getCuratedFeed(
        page: Int = 1,
        forceRefresh: Boolean = false,
    ): Result<FeedPageResult>

    suspend fun searchTopics(
        query: String,
        categorySlug: String = "",
        sort: FeedSort = FeedSort.LATEST_REPLY,
        page: Int = 1,
    ): Result<FeedPageResult>

    suspend fun getTopicDetail(
        topicId: Long,
        page: Int = 1,
        includeBlocked: Boolean = false,
    ): Result<TopicDetailPage>

    suspend fun createTopic(
        categorySlug: String,
        title: String,
        markdownContent: String,
        requiredViewLevel: Int = 0,
    ): Result<Unit>

    suspend fun createTopicWithResult(
        categorySlug: String,
        title: String,
        markdownContent: String,
        requiredViewLevel: Int = 0,
    ): Result<Long?> = createTopic(
        categorySlug = categorySlug,
        title = title,
        markdownContent = markdownContent,
        requiredViewLevel = requiredViewLevel,
    ).map { null }

    suspend fun editTopic(
        topicId: Long,
        title: String,
        markdownContent: String,
        requiredViewLevel: Int = 0,
    ): Result<Unit>

    suspend fun postReply(topicId: Long, markdownContent: String): Result<Unit>
    suspend fun editReply(commentId: Long, markdownContent: String): Result<Unit>
    suspend fun setReplyPinned(commentId: Long, pinned: Boolean): Result<Unit>

    suspend fun getFreeDrumstickCost(): Result<Int>
    suspend fun reactToComment(
        topicId: Long,
        commentId: Long,
        reaction: ReactionType,
    ): Result<ReactionResult>
}
