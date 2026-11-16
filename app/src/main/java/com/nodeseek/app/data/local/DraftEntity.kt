package com.nodeseek.app.data.local

import androidx.room.Entity
import androidx.room.Index
import com.nodeseek.app.domain.model.EditorDraft

@Entity(
    tableName = "editor_drafts",
    primaryKeys = ["accountUid", "draftKey"],
    indices = [Index(value = ["accountUid", "updatedAtMillis"])],
)
data class DraftEntity(
    val accountUid: Long,
    val draftKey: String,
    val title: String,
    val markdownContent: String,
    val categorySlug: String,
    val updatedAtMillis: Long,
)

fun DraftEntity.toDomain(): EditorDraft = EditorDraft(
    draftKey = draftKey,
    accountUid = accountUid,
    title = title,
    markdownContent = markdownContent,
    categorySlug = categorySlug,
    updatedAtMillis = updatedAtMillis,
)

fun EditorDraft.toEntity(): DraftEntity = DraftEntity(
    accountUid = accountUid,
    draftKey = draftKey,
    title = title,
    markdownContent = markdownContent,
    categorySlug = categorySlug,
    updatedAtMillis = updatedAtMillis,
)
