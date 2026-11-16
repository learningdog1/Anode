package com.nodeseek.app.domain.repository

interface ImageHostRepository {
    suspend fun uploadImage(
        bytes: ByteArray,
        mimeType: String,
        deleteAfterDays: Int? = null,
        fileName: String? = null,
    ): Result<String>
}
