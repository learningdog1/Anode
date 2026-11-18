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

    override suspend fun getAllDrafts(): List<EditorDraft> =
        draftDao.getAllDrafts().map(DraftEntity::toDomain)

    override suspend fun restoreDrafts(
        items: List<EditorDraft>,
        replaceExisting: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val deduped = LinkedHashMap<Pair<Long, String>, EditorDraft>()
        for (raw in items) {
            val cleanKey = raw.draftKey.trim()
            if (cleanKey.isEmpty()) continue
            if (raw.title.isBlank() && raw.markdownContent.isBlank()) continue
            val normalized = raw.copy(
                draftKey = cleanKey,
                categorySlug = raw.categorySlug.trim().ifBlank { "daily" },
                updatedAtMillis = if (raw.updatedAtMillis > 0L) raw.updatedAtMillis else now,
            )
            val mapKey = normalized.accountUid to normalized.draftKey
            val prev = deduped[mapKey]
            if (prev == null || normalized.updatedAtMillis >= prev.updatedAtMillis) {
                deduped[mapKey] = normalized
            }
        }

        if (replaceExisting) {
            draftDao.replaceAllDrafts(deduped.values.map(EditorDraft::toEntity))
            return
        }

        val toUpsert = buildList {
            for (draft in deduped.values) {
                val existing = draftDao.getDraft(
                    accountUid = draft.accountUid,
                    draftKey = draft.draftKey,
                )
                if (existing == null || draft.updatedAtMillis >= existing.updatedAtMillis) {
                    add(draft.toEntity())
                }
            }
        }
        if (toUpsert.isNotEmpty()) {
            draftDao.upsertAll(toUpsert)
        }
    }
}
