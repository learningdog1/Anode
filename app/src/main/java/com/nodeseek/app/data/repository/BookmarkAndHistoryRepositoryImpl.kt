@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nodeseek.app.data.repository

import com.nodeseek.app.data.local.BookmarkDao
import com.nodeseek.app.data.local.BookmarkEntity
import com.nodeseek.app.data.local.ReadHistoryDao
import com.nodeseek.app.data.local.ReadHistoryEntity
import com.nodeseek.app.data.local.toDomain
import com.nodeseek.app.data.local.toEntity
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.domain.model.BookmarkedTopic
import com.nodeseek.app.domain.model.ReadHistoryEntry
import com.nodeseek.app.domain.model.TopicDetailPage
import com.nodeseek.app.domain.model.TopicSummary
import com.nodeseek.app.domain.repository.BookmarkAndHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/**
 * Concrete [BookmarkAndHistoryRepository] combining server-synced NodeSeek collections
 * (`/api/statistics/collection` & `/api/statistics/list-collection`) with Room-persisted
 * offline bookmark snapshots and local read-history tracking.
 */
class BookmarkAndHistoryRepositoryImpl(
    private val apiClientProvider: () -> NodeSeekApiClient,
    private val sessionRepository: SessionRepositoryImpl,
    private val bookmarkDao: BookmarkDao,
    private val readHistoryDao: ReadHistoryDao,
) : BookmarkAndHistoryRepository {

    private val recentSummaryCache = ConcurrentHashMap<Long, TopicSummary>()

    private val currentAccountUidFlow: Flow<Long> =
        sessionRepository.sessionState
            .map { it?.uid ?: 0L }
            .distinctUntilChanged()

    private val currentAccountUid: Long
        get() = sessionRepository.sessionState.value?.uid ?: 0L

    fun cacheTopicSummaries(summaries: Iterable<TopicSummary>) {
        if (recentSummaryCache.size > MAX_RECENT_SUMMARY_CACHE) {
            recentSummaryCache.clear()
        }
        for (summary in summaries) {
            recentSummaryCache[summary.id] = summary
        }
    }

    override suspend fun setTopicBookmarked(
        topicId: Long,
        bookmarked: Boolean,
    ): Result<Unit> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val uid = currentAccountUid
            val isSignedIn = uid > 0L

            if (isSignedIn) {
                apiClientProvider().setCollection(postId = topicId, collected = bookmarked)
            }

            if (bookmarked) {
                val existing = bookmarkDao.getBookmark(accountUid = uid, topicId = topicId)
                if (existing != null) {
                    bookmarkDao.upsert(existing.copy(isSyncedWithServer = isSignedIn))
                } else {
                    val summary = recentSummaryCache[topicId] ?: TopicSummary(
                        id = topicId,
                        title = "主题 #$topicId",
                        categorySlug = "",
                        categoryName = "",
                        authorName = "",
                        isBookmarked = true,
                    )
                    bookmarkDao.upsert(
                        BookmarkEntity.fromSummary(
                            accountUid = uid,
                            summary = summary.copy(isBookmarked = true),
                            detailSnapshot = null,
                            isSyncedWithServer = isSignedIn,
                        ),
                    )
                }
            } else {
                bookmarkDao.deleteBookmark(accountUid = uid, topicId = topicId)
            }
        }

    override suspend fun getServerBookmarks(page: Int): Result<Pair<List<TopicSummary>, Boolean>> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val safePage = page.coerceAtLeast(1)
            val uid = currentAccountUid
            val response = apiClientProvider().listCollections(safePage)
            val summaries = response.collections.map { row ->
                val cached = recentSummaryCache[row.postId]
                TopicSummary(
                    id = row.postId,
                    title = row.title,
                    categorySlug = cached?.categorySlug.orEmpty(),
                    categoryName = cached?.categoryName.orEmpty(),
                    authorId = cached?.authorId,
                    authorName = cached?.authorName.orEmpty(),
                    authorAvatarUrl = cached?.authorAvatarUrl.orEmpty(),
                    viewCount = cached?.viewCount ?: 0,
                    replyCount = cached?.replyCount ?: 0,
                    lastActivityIso = cached?.lastActivityIso.orEmpty(),
                    requiredViewLevel = row.rank,
                    isBookmarked = true,
                )
            }

            if (summaries.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val entities = summaries.mapIndexed { idx, summary ->
                    val existing = bookmarkDao.getBookmark(accountUid = uid, topicId = summary.id)
                    BookmarkEntity(
                        accountUid = uid,
                        topicId = summary.id,
                        title = summary.title,
                        categorySlug = summary.categorySlug.ifBlank { existing?.categorySlug.orEmpty() },
                        categoryName = summary.categoryName.ifBlank { existing?.categoryName.orEmpty() },
                        authorId = summary.authorId ?: existing?.authorId,
                        authorName = summary.authorName.ifBlank { existing?.authorName.orEmpty() },
                        authorAvatarUrl = summary.authorAvatarUrl.ifBlank { existing?.authorAvatarUrl.orEmpty() },
                        authorLevel = summary.authorLevel ?: existing?.authorLevel,
                        viewCount = if (summary.viewCount > 0) summary.viewCount else (existing?.viewCount ?: 0),
                        replyCount = if (summary.replyCount > 0) summary.replyCount else (existing?.replyCount ?: 0),
                        lastActivityIso = summary.lastActivityIso.ifBlank { existing?.lastActivityIso.orEmpty() },
                        requiredViewLevel = summary.requiredViewLevel ?: existing?.requiredViewLevel,
                        isLocked = existing?.isLocked ?: false,
                        offlineDetailJson = existing?.offlineDetailJson,
                        isSyncedWithServer = true,
                        savedAtEpochMillis = existing?.savedAtEpochMillis ?: (now - idx),
                    )
                }
                bookmarkDao.upsertAll(entities)
            }

            Pair(summaries, response.collections.size == SERVER_COLLECTION_PAGE_SIZE)
        }

    override fun observeLocalBookmarks(): Flow<List<BookmarkedTopic>> =
        currentAccountUidFlow.flatMapLatest { uid ->
            bookmarkDao.observeBookmarks(uid)
                .map { rows -> rows.map(BookmarkEntity::toDomain) }
        }

    override fun observeBookmarkedIds(): Flow<Set<Long>> =
        currentAccountUidFlow.flatMapLatest { uid ->
            bookmarkDao.observeBookmarkedTopicIds(uid)
                .map { ids -> ids.toSet() }
        }

    override suspend fun saveOfflineBookmark(
        summary: TopicSummary,
        detailSnapshot: TopicDetailPage?,
        syncedWithServer: Boolean,
    ) {
        val uid = currentAccountUid
        val existing = bookmarkDao.getBookmark(accountUid = uid, topicId = summary.id)
        val encodedSnapshot = detailSnapshot?.let { BookmarkEntity.encodeOfflineDetail(it) }
            ?: existing?.offlineDetailJson
        val entity = BookmarkEntity(
            accountUid = uid,
            topicId = summary.id,
            title = summary.title,
            categorySlug = summary.categorySlug,
            categoryName = summary.categoryName,
            authorId = summary.authorId,
            authorName = summary.authorName,
            authorAvatarUrl = summary.authorAvatarUrl,
            authorLevel = summary.authorLevel,
            viewCount = summary.viewCount,
            replyCount = summary.replyCount,
            lastActivityIso = summary.lastActivityIso,
            requiredViewLevel = summary.requiredViewLevel,
            isLocked = summary.isLocked,
            offlineDetailJson = encodedSnapshot,
            isSyncedWithServer = syncedWithServer || (existing?.isSyncedWithServer == true),
            savedAtEpochMillis = existing?.savedAtEpochMillis ?: System.currentTimeMillis(),
        )
        bookmarkDao.upsert(entity)
    }

    override suspend fun getOfflineBookmarkSnapshot(topicId: Long): TopicDetailPage? {
        val uid = currentAccountUid
        val primary = bookmarkDao.getBookmark(accountUid = uid, topicId = topicId)?.decodeOfflineDetail()
        if (primary != null) return primary
        return if (uid != 0L) {
            bookmarkDao.getBookmark(accountUid = 0L, topicId = topicId)?.decodeOfflineDetail()
        } else {
            null
        }
    }

    override suspend fun removeLocalBookmark(topicId: Long) {
        bookmarkDao.deleteBookmark(accountUid = currentAccountUid, topicId = topicId)
    }

    override fun observeReadHistory(): Flow<List<ReadHistoryEntry>> =
        currentAccountUidFlow.flatMapLatest { uid ->
            readHistoryDao.observeHistory(accountUid = uid)
                .map { rows -> rows.map(ReadHistoryEntity::toDomain) }
        }

    override fun observeReadStateMap(topicIds: Collection<Long>): Flow<Map<Long, ReadHistoryEntry>> {
        val boundedIds = topicIds.distinct().take(900)
        if (boundedIds.isEmpty()) {
            return flowOf(emptyMap())
        }
        return currentAccountUidFlow.flatMapLatest { uid ->
            readHistoryDao.observeByTopicIds(accountUid = uid, topicIds = boundedIds)
                .map { rows ->
                    rows.associate { entity -> entity.topicId to entity.toDomain() }
                }
        }
    }

    override suspend fun recordTopicVisit(entry: ReadHistoryEntry) {
        val uid = if (entry.accountUid != 0L) entry.accountUid else currentAccountUid
        val existing = readHistoryDao.getByTopicId(accountUid = uid, topicId = entry.topicId)
        val mergedFloor = maxOf(entry.lastReadFloor, existing?.lastReadFloor ?: 0)
        val mergedPage = maxOf(entry.lastReadPage, existing?.lastReadPage ?: 1)
        val updated = entry.copy(
            lastReadFloor = mergedFloor,
            lastReadPage = mergedPage,
            visitedAtEpochMillis = if (entry.visitedAtEpochMillis > 0L) {
                entry.visitedAtEpochMillis
            } else {
                System.currentTimeMillis()
            },
        )
        readHistoryDao.upsert(updated.toEntity(accountUidOverride = uid))
    }

    override suspend fun clearReadHistory() {
        readHistoryDao.clearForAccount(accountUid = currentAccountUid)
    }

    companion object {
        private const val SERVER_COLLECTION_PAGE_SIZE = 15
        private const val MAX_RECENT_SUMMARY_CACHE = 400
    }
}
