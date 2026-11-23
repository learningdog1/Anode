package com.nodeseek.app.ui.composer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

internal const val MAX_UPLOAD_IMAGE_BYTES: Int = 20 * 1024 * 1024

internal sealed interface ImagePayloadReadResult {
    data class Success(
        val bytes: ByteArray,
        val mimeType: String,
        val fileName: String?,
    ) : ImagePayloadReadResult

    data class TooLarge(
        val limitBytes: Int = MAX_UPLOAD_IMAGE_BYTES,
    ) : ImagePayloadReadResult {
        val message: String
            get() = "所选图片超过 ${limitBytes / (1024 * 1024)} MB 上限，请压缩后再上传"
    }

    data class ReadError(
        val message: String,
    ) : ImagePayloadReadResult
}

internal fun readPickedImagePayloadBounded(
    context: Context,
    uri: Uri,
    maxBytes: Int = MAX_UPLOAD_IMAGE_BYTES,
): ImagePayloadReadResult = runCatching {
    val resolver = context.contentResolver
    val meta = queryPickedImageMeta(resolver = resolver, uri = uri)
    if (meta.sizeBytes != null && meta.sizeBytes > maxBytes.toLong()) {
        return@runCatching ImagePayloadReadResult.TooLarge(maxBytes)
    }

    val bytes = resolver.openInputStream(uri)?.use { stream ->
        readBoundedBytes(stream, maxBytes)
    } ?: return@runCatching ImagePayloadReadResult.ReadError("无法读取所选图片，请确认文件权限或更换格式")

    if (bytes === OVERSIZED_SENTINEL) {
        return@runCatching ImagePayloadReadResult.TooLarge(maxBytes)
    }
    if (bytes.isEmpty()) {
        return@runCatching ImagePayloadReadResult.ReadError("所选图片内容为空，请重新选择")
    }

    val resolvedMime = detectImageMimeType(
        bytes = bytes,
        resolverMimeType = resolver.getType(uri),
        fileName = meta.displayName,
    )
    ImagePayloadReadResult.Success(
        bytes = bytes,
        mimeType = resolvedMime,
        fileName = meta.displayName,
    )
}.getOrElse {
    ImagePayloadReadResult.ReadError("无法读取所选图片，请确认文件权限或更换格式")
}

private val OVERSIZED_SENTINEL = ByteArray(0)

internal fun readBoundedBytes(
    input: InputStream,
    maxBytes: Int = MAX_UPLOAD_IMAGE_BYTES,
): ByteArray {
    val limit = maxBytes.coerceAtLeast(1)
    val buffer = ByteArray(16 * 1024)
    val out = ByteArrayOutputStream(minOf(64 * 1024, limit))
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        total += read
        if (total > limit) {
            return OVERSIZED_SENTINEL
        }
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

internal fun isOversizedReadResult(bytes: ByteArray): Boolean = bytes === OVERSIZED_SENTINEL

internal fun detectImageMimeType(
    bytes: ByteArray,
    resolverMimeType: String?,
    fileName: String?,
): String {
    detectMimeFromMagicBytes(bytes)?.let { return it }

    val cleanResolver = resolverMimeType?.trim()?.lowercase(Locale.US).orEmpty()
    if (cleanResolver.startsWith("image/") && cleanResolver != "image/*") {
        return cleanResolver
    }

    val ext = fileName?.substringAfterLast('.', "")?.trim()?.lowercase(Locale.US).orEmpty()
    return when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg", "jpe" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "avif" -> "image/avif"
        "heic", "heif" -> "image/heic"
        "svg" -> "image/svg+xml"
        else -> "image/jpeg"
    }
}

private fun detectMimeFromMagicBytes(bytes: ByteArray): String? {
    if (bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() &&
        bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() &&
        bytes[3] == 0x47.toByte() &&
        bytes[4] == 0x0D.toByte() &&
        bytes[5] == 0x0A.toByte() &&
        bytes[6] == 0x1A.toByte() &&
        bytes[7] == 0x0A.toByte()
    ) {
        return "image/png"
    }
    if (bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte()
    ) {
        return "image/jpeg"
    }
    if (bytes.size >= 6 &&
        bytes[0] == 'G'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() &&
        bytes[3] == '8'.code.toByte() &&
        (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
        bytes[5] == 'a'.code.toByte()
    ) {
        return "image/gif"
    }
    if (bytes.size >= 12 &&
        bytes[0] == 'R'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() &&
        bytes[3] == 'F'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() &&
        bytes[9] == 'E'.code.toByte() &&
        bytes[10] == 'B'.code.toByte() &&
        bytes[11] == 'P'.code.toByte()
    ) {
        return "image/webp"
    }
    if (bytes.size >= 2 &&
        bytes[0] == 'B'.code.toByte() &&
        bytes[1] == 'M'.code.toByte()
    ) {
        return "image/bmp"
    }
    if (bytes.size >= 12 &&
        bytes[4] == 'f'.code.toByte() &&
        bytes[5] == 't'.code.toByte() &&
        bytes[6] == 'y'.code.toByte() &&
        bytes[7] == 'p'.code.toByte()
    ) {
        val brand = String(bytes, 8, 4, Charsets.US_ASCII).lowercase(Locale.US)
        when {
            brand.startsWith("avif") || brand.startsWith("avis") -> return "image/avif"
            brand.startsWith("heic") || brand.startsWith("heix") ||
                brand.startsWith("hevc") || brand.startsWith("mif1") -> return "image/heic"
        }
    }
    val head = String(bytes, 0, minOf(bytes.size, 128), Charsets.UTF_8).trimStart()
    if (head.startsWith("<svg", ignoreCase = true) ||
        (head.startsWith("<?xml", ignoreCase = true) && head.contains("<svg", ignoreCase = true))
    ) {
        return "image/svg+xml"
    }
    return null
}

private data class PickedImageMeta(
    val displayName: String?,
    val sizeBytes: Long?,
)

private fun queryPickedImageMeta(
    resolver: ContentResolver,
    uri: Uri,
): PickedImageMeta {
    var displayName: String? = null
    var sizeBytes: Long? = null
    runCatching {
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIdx >= 0) {
                displayName = cursor.getString(nameIdx)?.trim()?.takeIf { it.isNotEmpty() }
            }
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                val raw = cursor.getLong(sizeIdx)
                if (raw >= 0L) sizeBytes = raw
            }
        }
    }
    if (displayName == null) {
        displayName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
    return PickedImageMeta(displayName = displayName, sizeBytes = sizeBytes)
}
