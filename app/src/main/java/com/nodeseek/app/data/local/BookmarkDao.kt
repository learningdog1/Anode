package com.nodeseek.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query(
        """
        SELECT * FROM bookmarks
        WHERE accountUid = :accountUid
        ORDER BY savedAtEpochMillis DESC
        """
    )
    fun observeBookmarks(accountUid: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT topicId FROM bookmarks WHERE accountUid = :accountUid")
    fun observeBookmarkedTopicIds(accountUid: Long): Flow<List<Long>>

    @Query(
        """
        SELECT * FROM bookmarks
        WHERE accountUid = :accountUid AND topicId = :topicId
        LIMIT 1
        """
    )
    suspend fun getBookmark(accountUid: Long, topicId: Long): BookmarkEntity?

    @Upsert
    suspend fun upsert(entity: BookmarkEntity)

    @Upsert
    suspend fun upsertAll(entities: List<BookmarkEntity>)

    @Query(
        """
        UPDATE bookmarks
        SET offlineDetailJson = :offlineDetailJson
        WHERE accountUid = :accountUid AND topicId = :topicId
        """
    )
    suspend fun updateOfflineSnapshot(
        accountUid: Long,
        topicId: Long,
        offlineDetailJson: String?,
    )

    @Query("DELETE FROM bookmarks WHERE accountUid = :accountUid AND topicId = :topicId")
    suspend fun deleteBookmark(accountUid: Long, topicId: Long)

    @Query("DELETE FROM bookmarks WHERE accountUid = :accountUid")
    suspend fun clearForAccount(accountUid: Long)
}
