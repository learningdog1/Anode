package com.nodeseek.app.data.local

import androidx.room.Entity
import androidx.room.Index
import com.nodeseek.app.domain.model.Category

@Entity(
    tableName = "categories",
    primaryKeys = ["accountUid", "slug"],
    indices = [Index(value = ["accountUid", "sortOrder"])],
)
data class CategoryEntity(
    val accountUid: Long,
    val slug: String,
    val name: String,
    val description: String,
    val sortOrder: Int,
    val isCustomizable: Boolean,
    val visibleOnHome: Boolean,
    val isAdminOnly: Boolean,
    val topicCoinCost: Int,
    val supportsRss: Boolean,
    val syncedAtEpochMillis: Long,
)

fun CategoryEntity.toDomain(): Category = Category(
    slug = slug,
    name = name,
    isCustomizable = isCustomizable,
    visibleOnHome = visibleOnHome,
    isAdminOnly = isAdminOnly,
    topicCoinCost = topicCoinCost,
    description = description.ifBlank { Category.defaultDescriptionFor(slug) },
    supportsRss = supportsRss,
)

fun Category.toEntity(
    accountUid: Long = 0L,
    sortOrder: Int = 0,
    syncedAtEpochMillis: Long = System.currentTimeMillis(),
): CategoryEntity = CategoryEntity(
    accountUid = accountUid,
    slug = slug,
    name = name,
    description = description,
    sortOrder = sortOrder,
    isCustomizable = isCustomizable,
    visibleOnHome = visibleOnHome,
    isAdminOnly = isAdminOnly,
    topicCoinCost = topicCoinCost,
    supportsRss = supportsRss,
    syncedAtEpochMillis = syncedAtEpochMillis,
)
