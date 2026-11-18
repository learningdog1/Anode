package com.nodeseek.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftDao {

    @Query(
        """
        SELECT * FROM editor_drafts
        WHERE accountUid = :accountUid AND draftKey = :draftKey
        LIMIT 1
        """
    )
    suspend fun getDraft(accountUid: Long, draftKey: String): DraftEntity?

    @Query(
        """
        SELECT * FROM editor_drafts
        WHERE accountUid = :accountUid AND draftKey = :draftKey
        LIMIT 1
        """
    )
    fun observeDraft(accountUid: Long, draftKey: String): Flow<DraftEntity?>

    @Query(
        """
        SELECT * FROM editor_drafts
        WHERE accountUid = :accountUid
        ORDER BY updatedAtMillis DESC
        """
    )
    fun observeDrafts(accountUid: Long): Flow<List<DraftEntity>>

    @Upsert
    suspend fun upsert(entity: DraftEntity)

    @Query("DELETE FROM editor_drafts WHERE accountUid = :accountUid AND draftKey = :draftKey")
    suspend fun deleteDraft(accountUid: Long, draftKey: String)

    @Query("DELETE FROM editor_drafts WHERE accountUid = :accountUid")
    suspend fun clearForAccount(accountUid: Long)

    @Query("SELECT * FROM editor_drafts ORDER BY updatedAtMillis DESC")
    suspend fun getAllDrafts(): List<DraftEntity>

    @Upsert
    suspend fun upsertAll(entities: List<DraftEntity>)

    @Query("DELETE FROM editor_drafts")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAllDrafts(entities: List<DraftEntity>) {
        clearAll()
        if (entities.isNotEmpty()) {
            upsertAll(entities)
        }
    }
}
