package com.nodeseek.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadHistoryDao {

    @Query(
        """
        SELECT * FROM read_history
        WHERE accountUid = :accountUid
        ORDER BY visitedAtEpochMillis DESC
        LIMIT :limit
        """
    )
    fun observeHistory(accountUid: Long, limit: Int = 500): Flow<List<ReadHistoryEntity>>

    @Query(
        """
        SELECT * FROM read_history
        WHERE accountUid = :accountUid AND topicId IN (:topicIds)
        """
    )
    fun observeByTopicIds(accountUid: Long, topicIds: Collection<Long>): Flow<List<ReadHistoryEntity>>

    @Query(
        """
        SELECT * FROM read_history
        WHERE accountUid = :accountUid AND topicId IN (:topicIds)
        """
    )
    suspend fun getByTopicIds(accountUid: Long, topicIds: Collection<Long>): List<ReadHistoryEntity>

    @Query(
        """
        SELECT * FROM read_history
        WHERE accountUid = :accountUid AND topicId = :topicId
        LIMIT 1
        """
    )
    suspend fun getByTopicId(accountUid: Long, topicId: Long): ReadHistoryEntity?

    @Upsert
    suspend fun upsert(entity: ReadHistoryEntity)

    @Query("DELETE FROM read_history WHERE accountUid = :accountUid AND topicId = :topicId")
    suspend fun deleteByTopicId(accountUid: Long, topicId: Long)

    @Query("DELETE FROM read_history WHERE accountUid = :accountUid")
    suspend fun clearForAccount(accountUid: Long)

    @Query("DELETE FROM read_history")
    suspend fun clearAll()
}
