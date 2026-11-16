package com.nodeseek.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Query(
        """
        SELECT * FROM categories
        WHERE accountUid = :accountUid
        ORDER BY sortOrder ASC
        """
    )
    fun observeCategories(accountUid: Long): Flow<List<CategoryEntity>>

    @Query(
        """
        SELECT * FROM categories
        WHERE accountUid = :accountUid
        ORDER BY sortOrder ASC
        """
    )
    suspend fun getCategories(accountUid: Long): List<CategoryEntity>

    @Query("SELECT MAX(syncedAtEpochMillis) FROM categories WHERE accountUid = :accountUid")
    suspend fun getLastSyncedAtMillis(accountUid: Long): Long?

    @Upsert
    suspend fun upsertAll(entities: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE accountUid = :accountUid")
    suspend fun clearForAccount(accountUid: Long)

    @Transaction
    suspend fun replaceForAccount(accountUid: Long, entities: List<CategoryEntity>) {
        clearForAccount(accountUid)
        if (entities.isNotEmpty()) {
            upsertAll(entities)
        }
    }
}
