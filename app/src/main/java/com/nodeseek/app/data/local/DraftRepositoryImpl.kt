package com.nodeseek.app.data.local

import com.nodeseek.app.domain.model.EditorDraft
import com.nodeseek.app.domain.repository.DraftRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DraftRepositoryImpl(
    private val draftDao: DraftDao,
) : DraftRepository {

    override suspend fun getDraft(accountUid: Long, draftKey: String): EditorDraft? =
        draftDao.getDraft(accountUid = accountUid, draftKey = draftKey)?.toDomain()

    override suspend fun saveDraft(draft: EditorDraft) {
        if (draft.title.isBlank() && draft.markdownContent.isBlank()) {
            draftDao.deleteDraft(accountUid = draft.accountUid, draftKey = draft.draftKey)
            return
        }
        val stamped = if (draft.updatedAtMillis > 0L) {
            draft
        } else {
            draft.copy(updatedAtMillis = System.currentTimeMillis())
        }
        draftDao.upsert(stamped.toEntity())
    }

    override suspend fun deleteDraft(accountUid: Long, draftKey: String) {
        draftDao.deleteDraft(accountUid = accountUid, draftKey = draftKey)
    }

    override fun observeDrafts(accountUid: Long): Flow<List<EditorDraft>> =
        draftDao.observeDrafts(accountUid = accountUid)
            .map { entities -> entities.map(DraftEntity::toDomain) }

    override fun observeDraft(accountUid: Long, draftKey: String): Flow<EditorDraft?> =
        draftDao.observeDraft(accountUid = accountUid, draftKey = draftKey)
            .map { entity -> entity?.toDomain() }
}
