package com.nodeseek.app.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.NodeSeekApiException
import com.nodeseek.app.domain.repository.ImageHostRepository
import com.nodeseek.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Detected image container format resolved from Magic Bytes -> MIME type -> filename extension.
 */
enum class DetectedImageFormat(
    val mimeType: String,
    val extension: String,
    val displayName: String,
    val isNativeForUpload: Boolean,
) {
    JPEG(mimeType = "image/jpeg", extension = "jpg", displayName = "JPEG", isNativeForUpload = true),
    PNG(mimeType = "image/png", extension = "png", displayName = "PNG", isNativeForUpload = true),
    WEBP(mimeType = "image/webp", extension = "webp", displayName = "WEBP", isNativeForUpload = true),
    GIF(mimeType = "image/gif", extension = "gif", displayName = "GIF", isNativeForUpload = true),
    SVG(mimeType = "image/svg+xml", extension = "svg", displayName = "SVG", isNativeForUpload = true),
    BMP(mimeType = "image/bmp", extension = "bmp", displayName = "BMP", isNativeForUpload = false),
    HEIC(mimeType = "image/heic", extension = "heic", displayName = "HEIC", isNativeForUpload = false),
    HEIF(mimeType = "image/heif", extension = "heif", displayName = "HEIF", isNativeForUpload = false),
    AVIF(mimeType = "image/avif", extension = "avif", displayName = "AVIF", isNativeForUpload = false),
    TIFF(mimeType = "image/tiff", extension = "tiff", displayName = "TIFF", isNativeForUpload = false),
    ICO(mimeType = "image/x-icon", extension = "ico", displayName = "ICO", isNativeForUpload = false),
}

data class SniffedImageInfo(
    val format: DetectedImageFormat,
    val isAnimated: Boolean,
    val detectedByMagic: Boolean,
)

data class PreparedImageUpload(
    val bytes: ByteArray,
    val mimeType: String,
    val fileName: String,
)

interface ImageUploadTranscoder {
    fun transcodeToFit(
        sourceBytes: ByteArray,
        sourceFormat: DetectedImageFormat?,
        maxBytes: Int,
        maxDimensionPx: Int,
    ): PreparedImageUpload?
}

/**
 * Pure-Kotlin image signature inspector supporting Magic Bytes, ContentResolver MIME,
 * and filename extension triple verification, including animated GIF/WEBP/APNG detection.
 */
object ImageUploadFormatInspector {

    fun inspect(
        bytes: ByteArray,
        declaredMimeType: String? = null,
        fileName: String? = null,
    ): SniffedImageInfo? {
        sniffMagicBytes(bytes)?.let { return it }
        fromMimeType(declaredMimeType)?.let { fmt ->
            return SniffedImageInfo(
                format = fmt,
                isAnimated = fmt == DetectedImageFormat.GIF,
                detectedByMagic = false,
            )
        }
        fromFileName(fileName)?.let { fmt ->
            return SniffedImageInfo(
                format = fmt,
                isAnimated = fmt == DetectedImageFormat.GIF,
                detectedByMagic = false,
            )
        }
        return null
    }

    fun sniffMagicBytes(bytes: ByteArray): SniffedImageInfo? {
        if (bytes.isEmpty()) return null

        // JPEG: FF D8 FF
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return SniffedImageInfo(
                format = DetectedImageFormat.JPEG,
                isAnimated = false,
                detectedByMagic = true,
            )
        }

        // PNG / APNG: 89 50 4E 47 0D 0A 1A 0A
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
            return SniffedImageInfo(
                format = DetectedImageFormat.PNG,
                isAnimated = isAnimatedPng(bytes),
                detectedByMagic = true,
            )
        }

        // GIF: GIF87a or GIF89a (always treat GIF as animated/frame-preserved)
        if (bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == '8'.code.toByte() &&
            (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
            bytes[5] == 'a'.code.toByte()
        ) {
            return SniffedImageInfo(
                format = DetectedImageFormat.GIF,
                isAnimated = true,
                detectedByMagic = true,
            )
        }

        // WEBP: RIFF....WEBP
        if (bytes.size >= 12 &&
            matchesAscii(bytes, 0, "RIFF") &&
            matchesAscii(bytes, 8, "WEBP")
        ) {
            return SniffedImageInfo(
                format = DetectedImageFormat.WEBP,
                isAnimated = isAnimatedWebp(bytes),
                detectedByMagic = true,
            )
        }

        // BMP: BM
        if (bytes.size >= 2 &&
            bytes[0] == 'B'.code.toByte() &&
            bytes[1] == 'M'.code.toByte()
        ) {
            return SniffedImageInfo(
                format = DetectedImageFormat.BMP,
                isAnimated = false,
                detectedByMagic = true,
            )
        }

        // ISO-BMFF (HEIC / HEIF / AVIF): [4-byte box size] + "ftyp" + [major brand + compatible brands]
        if (bytes.size >= 12 && matchesAscii(bytes, 4, "ftyp")) {
            detectIsoBmffImageFormat(bytes)?.let { isoFmt ->
                return SniffedImageInfo(
                    format = isoFmt,
                    isAnimated = false,
                    detectedByMagic = true,
                )
            }
        }

        // TIFF: II*\0 or MM\0*
        if (bytes.size >= 4 && (
                (bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x2A.toByte() && bytes[3] == 0x00.toByte()) ||
                    (bytes[0] == 0x4D.toByte() && bytes[1] == 0x4D.toByte() && bytes[2] == 0x00.toByte() && bytes[3] == 0x2A.toByte())
                )
        ) {
            return SniffedImageInfo(
                format = DetectedImageFormat.TIFF,
                isAnimated = false,
                detectedByMagic = true,
            )
        }

        // ICO: 00 00 01 00
        if (bytes.size >= 4 &&
            bytes[0] == 0x00.toByte() &&
            bytes[1] == 0x00.toByte() &&
            bytes[2] == 0x01.toByte() &&
            bytes[3] == 0x00.toByte()
        ) {
            return SniffedImageInfo(
                format = DetectedImageFormat.ICO,
                isAnimated = false,
                detectedByMagic = true,
            )
        }

        // SVG text sniff
        val headLimit = minOf(bytes.size, 512)
        val headText = String(bytes, 0, headLimit, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (headText.startsWith("<svg", ignoreCase = true) ||
            (headText.startsWith("<?xml", ignoreCase = true) && headText.contains("<svg", ignoreCase = true))
        ) {
            return SniffedImageInfo(
                format = DetectedImageFormat.SVG,
                isAnimated = false,
                detectedByMagic = true,
            )
        }

        return null
    }

    fun fromMimeType(mimeType: String?): DetectedImageFormat? {
        val clean = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (clean.isEmpty() || clean == "application/octet-stream" || clean == "image/*" || clean == "*/*") {
            return null
        }
        return when (clean) {
            "image/jpeg", "image/jpg", "image/pjpeg" -> DetectedImageFormat.JPEG
            "image/png", "image/apng" -> DetectedImageFormat.PNG
            "image/webp" -> DetectedImageFormat.WEBP
            "image/gif" -> DetectedImageFormat.GIF
            "image/svg+xml" -> DetectedImageFormat.SVG
            "image/bmp", "image/x-ms-bmp", "image/x-bmp" -> DetectedImageFormat.BMP
            "image/heic", "image/heic-sequence" -> DetectedImageFormat.HEIC
            "image/heif", "image/heif-sequence" -> DetectedImageFormat.HEIF
            "image/avif", "image/avif-sequence" -> DetectedImageFormat.AVIF
            "image/tiff", "image/x-tiff" -> DetectedImageFormat.TIFF
            "image/x-icon", "image/vnd.microsoft.icon" -> DetectedImageFormat.ICO
            else -> null
        }
    }

    fun fromFileName(fileName: String?): DetectedImageFormat? {
        val ext = fileName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.substringAfterLast('.', "")
            ?.trim()
            ?.lowercase()
            .orEmpty()
        return when (ext) {
            "jpg", "jpeg", "jpe", "jfif" -> DetectedImageFormat.JPEG
            "png", "apng" -> DetectedImageFormat.PNG
            "webp" -> DetectedImageFormat.WEBP
            "gif" -> DetectedImageFormat.GIF
            "svg" -> DetectedImageFormat.SVG
            "bmp", "dib" -> DetectedImageFormat.BMP
            "heic", "heics" -> DetectedImageFormat.HEIC
            "heif", "heifs", "hif" -> DetectedImageFormat.HEIF
            "avif", "avifs" -> DetectedImageFormat.AVIF
            "tif", "tiff" -> DetectedImageFormat.TIFF
            "ico" -> DetectedImageFormat.ICO
            else -> null
        }
    }

    fun rewriteFileNameExtension(rawFileName: String?, targetExtension: String): String {
        val leaf = rawFileName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            .orEmpty()
        val stem = (if (leaf.contains('.')) leaf.substringBeforeLast('.') else leaf)
            .replace(Regex("""[^a-zA-Z0-9_-]+"""), "_")
            .trim('_')
            .take(48)
            .ifEmpty { "image" }
        return "$stem.$targetExtension"
    }

    private fun isAnimatedPng(bytes: ByteArray): Boolean {
        var offset = 8
        while (offset + 12 <= bytes.size) {
            val chunkLen = readInt32BigEndian(bytes, offset)
            if (chunkLen < 0 || offset + 12L + chunkLen > bytes.size) break
            if (matchesAscii(bytes, offset + 4, "acTL")) return true
            if (matchesAscii(bytes, offset + 4, "IDAT") || matchesAscii(bytes, offset + 4, "IEND")) {
                return false
            }
            offset += 12 + chunkLen
        }
        return false
    }

    private fun isAnimatedWebp(bytes: ByteArray): Boolean {
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkLen = readInt32LittleEndian(bytes, offset + 4)
            if (chunkLen < 0) break
            val paddedLen = chunkLen + (chunkLen and 1)
            if (matchesAscii(bytes, offset, "VP8X") && offset + 9 <= bytes.size) {
                val flags = bytes[offset + 8].toInt() and 0xFF
                if ((flags and 0x02) != 0) return true
            }
            if (matchesAscii(bytes, offset, "ANIM") || matchesAscii(bytes, offset, "ANMF")) {
                return true
            }
            val next = offset.toLong() + 8L + paddedLen.toLong()
            if (next <= offset || next > bytes.size) break
            offset = next.toInt()
        }
        return false
    }

    private fun detectIsoBmffImageFormat(bytes: ByteArray): DetectedImageFormat? {
        val boxSize = readInt32BigEndian(bytes, 0)
        val scanEnd = when {
            boxSize in 12..bytes.size -> boxSize
            else -> minOf(bytes.size, 128)
        }
        val brands = buildList {
            if (bytes.size >= 12) {
                add(ascii4(bytes, 8))
            }
            var offset = 16
            while (offset + 4 <= scanEnd) {
                add(ascii4(bytes, offset))
                offset += 4
            }
        }
        for (brand in brands) {
            if (brand in AVIF_BRANDS) return DetectedImageFormat.AVIF
        }
        for (brand in brands) {
            if (brand in HEIC_BRANDS) return DetectedImageFormat.HEIC
        }
        for (brand in brands) {
            if (brand in HEIF_BRANDS) return DetectedImageFormat.HEIF
        }
        return null
    }

    private fun ascii4(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, Charsets.US_ASCII).lowercase()

    private fun matchesAscii(bytes: ByteArray, offset: Int, expected: String): Boolean {
        if (offset < 0 || offset + expected.length > bytes.size) return false
        for (i in expected.indices) {
            if (bytes[offset + i] != expected[i].code.toByte()) return false
        }
        return true
    }

    private fun readInt32BigEndian(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readInt32LittleEndian(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private val AVIF_BRANDS = setOf("avif", "avis", "avio")
    private val HEIC_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs")
    private val HEIF_BRANDS = setOf("mif1", "msf1")
}

/**
 * Android [ImageDecoder] (API 28+) / [BitmapFactory] (API 26-27) transcoder that:
 * - Downsamples massive photos cleanly before full allocation
 * - Uses software allocator so `hasAlpha()` & `compress()` always succeed
 * - Preserves alpha transparency (PNG -> WEBP fallback) vs opaque JPEG compression
 */
object AndroidImageUploadTranscoder : ImageUploadTranscoder {

    override fun transcodeToFit(
        sourceBytes: ByteArray,
        sourceFormat: DetectedImageFormat?,
        maxBytes: Int,
        maxDimensionPx: Int,
    ): PreparedImageUpload? {
        val decoded = decodeScaledBitmap(sourceBytes, maxDimensionPx) ?: return null
        return try {
            compressBitmapToFit(bitmap = decoded, maxBytes = maxBytes, baseFileName = null)
        } finally {
            if (!decoded.isRecycled) {
                decoded.recycle()
            }
        }
    }

    private fun decodeScaledBitmap(bytes: ByteArray, maxDimensionPx: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    // Hardware bitmaps cannot read pixels or compress on Android.
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    val longest = max(w, h)
                    if (longest > maxDimensionPx && maxDimensionPx > 0) {
                        val scale = maxDimensionPx.toFloat() / longest.toFloat()
                        decoder.setTargetSize(
                            (w * scale).roundToInt().coerceAtLeast(1),
                            (h * scale).roundToInt().coerceAtLeast(1),
                        )
                    }
                }
            }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        var longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / sampleSize > maxDimensionPx && maxDimensionPx > 0) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null
        return applyExifOrientationIfNeeded(decoded, bytes)
    }

    private fun applyExifOrientationIfNeeded(bitmap: Bitmap, rawBytes: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(rawBytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }

        val transformed = runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull() ?: return bitmap
        if (transformed !== bitmap && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        return transformed
    }

    private fun compressBitmapToFit(
        bitmap: Bitmap,
        maxBytes: Int,
        baseFileName: String?,
    ): PreparedImageUpload? {
        val hasAlpha = bitmap.hasAlpha()
        var workingBitmap = bitmap
        var ownsWorking = false
        try {
            if (hasAlpha) {
                // First try lossless PNG if within limit so alpha UI screenshots stay crisp.
                val pngBytes = compressOnce(workingBitmap, Bitmap.CompressFormat.PNG, 100)
                if (pngBytes != null && pngBytes.size <= maxBytes) {
                    return PreparedImageUpload(
                        bytes = pngBytes,
                        mimeType = DetectedImageFormat.PNG.mimeType,
                        fileName = ImageUploadFormatInspector.rewriteFileNameExtension(baseFileName, DetectedImageFormat.PNG.extension),
                    )
                }

                // Otherwise encode as alpha-preserving WEBP stepping quality and scale.
                val webpFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                for (scaleStep in 0 until 4) {
                    for (quality in listOf(90, 80, 68)) {
                        val encoded = compressOnce(workingBitmap, webpFormat, quality)
                        if (encoded != null && encoded.size <= maxBytes) {
                            return PreparedImageUpload(
                                bytes = encoded,
                                mimeType = DetectedImageFormat.WEBP.mimeType,
                                fileName = ImageUploadFormatInspector.rewriteFileNameExtension(baseFileName, DetectedImageFormat.WEBP.extension),
                            )
                        }
                    }
                    val scaled = downscaleBitmap(workingBitmap, 0.75f) ?: break
                    if (ownsWorking && !workingBitmap.isRecycled) workingBitmap.recycle()
                    workingBitmap = scaled
                    ownsWorking = true
                }
                val fallbackPng = compressOnce(workingBitmap, Bitmap.CompressFormat.PNG, 100)
                if (fallbackPng != null && fallbackPng.size <= maxBytes) {
                    return PreparedImageUpload(
                        bytes = fallbackPng,
                        mimeType = DetectedImageFormat.PNG.mimeType,
                        fileName = ImageUploadFormatInspector.rewriteFileNameExtension(baseFileName, DetectedImageFormat.PNG.extension),
                    )
                }
                return null
            } else {
                for (scaleStep in 0 until 4) {
                    val qualities = if (scaleStep == 0) listOf(92, 84, 74) else listOf(84, 72)
                    for (quality in qualities) {
                        val encoded = compressOnce(workingBitmap, Bitmap.CompressFormat.JPEG, quality)
                        if (encoded != null && encoded.size <= maxBytes) {
                            return PreparedImageUpload(
                                bytes = encoded,
                                mimeType = DetectedImageFormat.JPEG.mimeType,
                                fileName = ImageUploadFormatInspector.rewriteFileNameExtension(baseFileName, DetectedImageFormat.JPEG.extension),
                            )
                        }
                    }
                    val scaled = downscaleBitmap(workingBitmap, 0.75f) ?: break
                    if (ownsWorking && !workingBitmap.isRecycled) workingBitmap.recycle()
                    workingBitmap = scaled
                    ownsWorking = true
                }
                return null
            }
        } finally {
            if (ownsWorking && !workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
        }
    }

    private fun compressOnce(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray? {
        val stream = ByteArrayOutputStream()
        val ok = bitmap.compress(format, quality, stream)
        if (!ok) return null
        val result = stream.toByteArray()
        return result.takeIf { it.isNotEmpty() }
    }

    private fun downscaleBitmap(bitmap: Bitmap, factor: Float): Bitmap? {
        val nextW = (bitmap.width * factor).roundToInt()
        val nextH = (bitmap.height * factor).roundToInt()
        if (nextW < 64 || nextH < 64) return null
        return Bitmap.createScaledBitmap(bitmap, nextW, nextH, true)
    }
}

/**
 * Concrete [ImageHostRepository] uploading editor images to `https://api.nodeimage.com`
 * using the account's `X-API-Key` configured in [SettingsRepository] without leaking forum cookies.
 *
 * Performs multi-format validation & preparation:
 * 1. Triple detection: Magic Bytes -> MIME type -> filename extension.
 * 2. Native pass-through for JPEG/PNG/WEBP/GIF/SVG (never re-encoding animated GIF/WEBP/APNG).
 * 3. Automatic transcoding for non-native formats (HEIC/HEIF/BMP/TIFF/ICO/AVIF) and oversized
 *    static images, preserving alpha transparency (PNG/WEBP) or opaque high-quality JPEG.
 */
class NodeImageHostRepositoryImpl(
    private val apiClientProvider: () -> NodeSeekApiClient,
    private val settingsRepository: SettingsRepository,
    private val sessionRepository: SessionRepositoryImpl,
    private val imageTranscoder: ImageUploadTranscoder = AndroidImageUploadTranscoder,
    private val maxStaticUploadBytes: Int = DEFAULT_MAX_STATIC_UPLOAD_BYTES,
    private val maxAnimatedUploadBytes: Int = DEFAULT_MAX_ANIMATED_UPLOAD_BYTES,
    private val maxDimensionPx: Int = DEFAULT_MAX_DIMENSION_PX,
) : ImageHostRepository {

    override suspend fun verifyApiKey(apiKey: String): Result<Unit> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            val trimmed = apiKey.trim()
            if (trimmed.isEmpty()) {
                throw NodeSeekApiException("请先填写 NodeImage 图床 API Key。")
            }
            apiClientProvider().verifyNodeImageApiKey(trimmed)
        }

    override suspend fun uploadImage(
        bytes: ByteArray,
        mimeType: String,
        deleteAfterDays: Int?,
        fileName: String?,
    ): Result<String> =
        runDomainCatching(onChallengeDetected = sessionRepository::reportChallengeRequired) {
            if (bytes.isEmpty()) {
                throw NodeSeekApiException("所选图片文件为空，请重新选择。")
            }
            val apiKey = settingsRepository.settings.value.nodeImageApiKey.trim()
            if (apiKey.isEmpty()) {
                throw NodeSeekApiException("请先在「我的 -> 设置」中填写 NodeImage 图床 API Key。")
            }

            val prepared = withContext(Dispatchers.Default) {
                prepareImageForUpload(
                    bytes = bytes,
                    declaredMimeType = mimeType,
                    fileName = fileName,
                )
            }

            apiClientProvider().uploadImageToNodeImage(
                imageBytes = prepared.bytes,
                apiKey = apiKey,
                mimeType = prepared.mimeType,
                deleteAfterDays = deleteAfterDays,
                fileName = prepared.fileName,
            )
        }

    internal fun prepareImageForUpload(
        bytes: ByteArray,
        declaredMimeType: String,
        fileName: String?,
    ): PreparedImageUpload {
        val sniffed = ImageUploadFormatInspector.inspect(
            bytes = bytes,
            declaredMimeType = declaredMimeType,
            fileName = fileName,
        )

        if (sniffed == null) {
            val transcodedUnknown = tryTranscodeOrNull(
                bytes = bytes,
                sourceFormat = null,
                targetFileName = fileName,
            )
            if (transcodedUnknown != null) {
                return transcodedUnknown
            }
            throw NodeSeekApiException("不支持的图片格式或文件已损坏，请选择 JPG、PNG、WEBP、GIF、HEIC 或 BMP 图片。")
        }

        val format = sniffed.format

        // Animated GIF / WEBP / APNG must never pass through static Bitmap decoding.
        if (sniffed.isAnimated) {
            if (bytes.size > maxAnimatedUploadBytes) {
                val limitMb = maxAnimatedUploadBytes / (1024 * 1024)
                throw NodeSeekApiException("动图体积超过图床限制（${limitMb} MB），请压缩或裁剪后再上传。")
            }
            return PreparedImageUpload(
                bytes = bytes,
                mimeType = format.mimeType,
                fileName = ImageUploadFormatInspector.rewriteFileNameExtension(fileName, format.extension),
            )
        }

        // Vector SVG pass-through
        if (format == DetectedImageFormat.SVG) {
            if (bytes.size > maxStaticUploadBytes) {
                throw NodeSeekApiException("SVG 文件体积过大，请压缩后再上传。")
            }
            return PreparedImageUpload(
                bytes = bytes,
                mimeType = format.mimeType,
                fileName = ImageUploadFormatInspector.rewriteFileNameExtension(fileName, format.extension),
            )
        }

        // Native static formats (JPEG / PNG / static WEBP) within size limit upload losslessly with corrected MIME/ext.
        if (format.isNativeForUpload && bytes.size <= maxStaticUploadBytes) {
            return PreparedImageUpload(
                bytes = bytes,
                mimeType = format.mimeType,
                fileName = ImageUploadFormatInspector.rewriteFileNameExtension(fileName, format.extension),
            )
        }

        // Non-native format (HEIC/HEIF/BMP/AVIF/TIFF/ICO) OR oversized native static image -> decode & transcode.
        val transcoded = tryTranscodeOrNull(
            bytes = bytes,
            sourceFormat = format,
            targetFileName = fileName,
        )
        if (transcoded != null) {
            return transcoded
        }

        // If AVIF could not be decoded on Android < 12 (API < 31) and fits size limit, upload as native AVIF.
        if (format == DetectedImageFormat.AVIF && bytes.size <= maxStaticUploadBytes) {
            return PreparedImageUpload(
                bytes = bytes,
                mimeType = DetectedImageFormat.AVIF.mimeType,
                fileName = ImageUploadFormatInspector.rewriteFileNameExtension(fileName, DetectedImageFormat.AVIF.extension),
            )
        }

        if (format.isNativeForUpload && bytes.size > maxStaticUploadBytes) {
            val limitMb = maxStaticUploadBytes / (1024 * 1024)
            throw NodeSeekApiException("图片体积超过图床限制（${limitMb} MB）且自动压缩失败，请裁剪后重试。")
        }

        throw NodeSeekApiException(
            "无法转码当前 ${format.displayName} 图片，请转换为 PNG、JPG 或 WEBP 后重试。",
        )
    }

    private fun tryTranscodeOrNull(
        bytes: ByteArray,
        sourceFormat: DetectedImageFormat?,
        targetFileName: String?,
    ): PreparedImageUpload? {
        return try {
            val result = imageTranscoder.transcodeToFit(
                sourceBytes = bytes,
                sourceFormat = sourceFormat,
                maxBytes = maxStaticUploadBytes,
                maxDimensionPx = maxDimensionPx,
            ) ?: return null
            val outExt = ImageUploadFormatInspector.fromMimeType(result.mimeType)?.extension
                ?: result.fileName.substringAfterLast('.', "png")
            result.copy(
                fileName = ImageUploadFormatInspector.rewriteFileNameExtension(
                    rawFileName = targetFileName ?: result.fileName,
                    targetExtension = outExt,
                ),
            )
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        const val DEFAULT_MAX_STATIC_UPLOAD_BYTES = 5 * 1024 * 1024 // 5 MB
        const val DEFAULT_MAX_ANIMATED_UPLOAD_BYTES = 10 * 1024 * 1024 // 10 MB
        const val DEFAULT_MAX_DIMENSION_PX = 2560
    }
}
