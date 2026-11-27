package com.nodeseek.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class NodeImageItem(
    val imageId: String,
    val fileName: String,
    val url: String,
    val thumbnailUrl: String = url,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
    val createdAt: String = "",
    val deleteAfterDays: Int? = null,
)

@Serializable
data class NodeImageGalleryPage(
    val items: List<NodeImageItem> = emptyList(),
    val page: Int = 1,
    val totalCount: Int? = null,
    val hasMore: Boolean = false,
)
