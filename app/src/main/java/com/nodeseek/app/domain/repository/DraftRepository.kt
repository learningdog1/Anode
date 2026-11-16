package com.nodeseek.app.domain.repository

import com.nodeseek.app.domain.model.EditorDraft
import kotlinx.coroutines.flow.Flow

interface DraftRepository {
    suspend fun getDraft(accountUid: Long, draftKey: String): EditorDraft?
    suspend fun saveDraft(draft: EditorDraft)
    suspend fun deleteDraft(accountUid: Long, draftKey: String)
    fun observeDrafts(accountUid: Long): Flow<List<EditorDraft>>
    fun observeDraft(accountUid: Long, draftKey: String): Flow<EditorDraft?>
}
