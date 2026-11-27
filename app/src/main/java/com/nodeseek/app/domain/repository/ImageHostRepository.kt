package com.nodeseek.app.domain.repository

import com.nodeseek.app.domain.model.NodeImageGalleryPage

interface ImageHostRepository {
    suspend fun uploadImage(
        bytes: ByteArray,
        mimeType: String,
        deleteAfterDays: Int? = null,
        fileName: String? = null,
    ): Result<String>

    suspend fun verifyApiKey(apiKey: String): Result<Unit> = Result.success(Unit)

    suspend fun listImages(page: Int = 1, limit: Int? = null): Result<NodeImageGalleryPage> =
        Result.success(NodeImageGalleryPage())

    suspend fun deleteImage(imageId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("NodeImage delete not supported"))
}
