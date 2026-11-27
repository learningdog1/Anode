package com.nodeseek.app.ui.composer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.nodeseek.app.data.repository.DetectedImageFormat
import com.nodeseek.app.data.repository.ImageUploadFormatInspector
import com.nodeseek.app.data.repository.NodeImageHostRepositoryImpl
import com.nodeseek.app.data.repository.PreparedImageUpload
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

sealed interface MosaicSupportStatus {
    data class SupportedStatic(val format: DetectedImageFormat) : MosaicSupportStatus
    data class UnsupportedAnimatedOrVector(
        val format: DetectedImageFormat,
        val reason: String,
    ) : MosaicSupportStatus
    data class Unreadable(val reason: String) : MosaicSupportStatus
}

object ImagePrivacyBitmapCodec {

    fun inspectMosaicSupport(
        bytes: ByteArray,
        mimeType: String,
        fileName: String? = null,
    ): MosaicSupportStatus {
        if (bytes.isEmpty()) {
            return MosaicSupportStatus.Unreadable("所选图片内容为空")
        }
        val sniffed = ImageUploadFormatInspector.inspect(
            bytes = bytes,
            declaredMimeType = mimeType,
            fileName = fileName,
        ) ?: return MosaicSupportStatus.Unreadable("无法识别当前图片格式，将尝试直接上传")

        if (sniffed.isAnimated) {
            return MosaicSupportStatus.UnsupportedAnimatedOrVector(
                format = sniffed.format,
                reason = "检测到${sniffed.format.displayName}动图，已跳过涂鸦打码并保留完整动画帧",
            )
        }
        if (sniffed.format == DetectedImageFormat.SVG) {
            return MosaicSupportStatus.UnsupportedAnimatedOrVector(
                format = sniffed.format,
                reason = "检测到 SVG 矢量图，已跳过像素打码直接上传",
            )
        }
        return MosaicSupportStatus.SupportedStatic(sniffed.format)
    }

    fun decodeEditableBitmap(
        bytes: ByteArray,
        maxDimensionPx: Int = NodeImageHostRepositoryImpl.DEFAULT_MAX_DIMENSION_PX,
    ): Bitmap? {
        if (bytes.isEmpty()) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decoded = runCatching {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
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
            }.getOrNull()
            if (decoded != null) {
                return ensureMutableArgb8888(decoded)
            }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        val longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / sampleSize > maxDimensionPx && maxDimensionPx > 0) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val oriented = applyExifOrientationIfNeeded(rawBitmap, bytes)
        return ensureMutableArgb8888(oriented)
    }

    fun encodeEditedBitmap(
        bitmap: Bitmap,
        originalFileName: String? = null,
        maxBytes: Int = NodeImageHostRepositoryImpl.DEFAULT_MAX_STATIC_UPLOAD_BYTES,
    ): PreparedImageUpload? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val hasAlpha = bitmap.hasAlpha()
        var working = bitmap
        var ownsWorking = false
        try {
            if (hasAlpha) {
                val pngBytes = compressOnce(working, Bitmap.CompressFormat.PNG, 100)
                if (pngBytes != null && pngBytes.size <= maxBytes) {
                    return PreparedImageUpload(
                        bytes = pngBytes,
                        mimeType = DetectedImageFormat.PNG.mimeType,
                        fileName = ImageUploadFormatInspector.rewriteFileNameExtension(
                            rawFileName = originalFileName,
                            targetExtension = DetectedImageFormat.PNG.extension,
                        ),
                    )
                }
                val webpFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                for (scaleStep in 0 until 4) {
                    for (quality in listOf(90, 80, 68)) {
                        val encoded = compressOnce(working, webpFormat, quality)
                        if (encoded != null && encoded.size <= maxBytes) {
                            return PreparedImageUpload(
                                bytes = encoded,
                                mimeType = DetectedImageFormat.WEBP.mimeType,
                                fileName = ImageUploadFormatInspector.rewriteFileNameExtension(
                                    rawFileName = originalFileName,
                                    targetExtension = DetectedImageFormat.WEBP.extension,
                                ),
                            )
                        }
                    }
                    val scaled = downscaleBitmap(working, 0.75f) ?: break
                    if (ownsWorking && !working.isRecycled) working.recycle()
                    working = scaled
                    ownsWorking = true
                }
                val fallbackPng = compressOnce(working, Bitmap.CompressFormat.PNG, 100)
                if (fallbackPng != null && fallbackPng.size <= maxBytes) {
                    return PreparedImageUpload(
                        bytes = fallbackPng,
                        mimeType = DetectedImageFormat.PNG.mimeType,
                        fileName = ImageUploadFormatInspector.rewriteFileNameExtension(
                            rawFileName = originalFileName,
                            targetExtension = DetectedImageFormat.PNG.extension,
                        ),
                    )
                }
                return null
            } else {
                for (scaleStep in 0 until 4) {
                    val qualities = if (scaleStep == 0) listOf(92, 84, 74) else listOf(84, 72)
                    for (quality in qualities) {
                        val encoded = compressOnce(working, Bitmap.CompressFormat.JPEG, quality)
                        if (encoded != null && encoded.size <= maxBytes) {
                            return PreparedImageUpload(
                                bytes = encoded,
                                mimeType = DetectedImageFormat.JPEG.mimeType,
                                fileName = ImageUploadFormatInspector.rewriteFileNameExtension(
                                    rawFileName = originalFileName,
                                    targetExtension = DetectedImageFormat.JPEG.extension,
                                ),
                            )
                        }
                    }
                    val scaled = downscaleBitmap(working, 0.75f) ?: break
                    if (ownsWorking && !working.isRecycled) working.recycle()
                    working = scaled
                    ownsWorking = true
                }
                return null
            }
        } finally {
            if (ownsWorking && !working.isRecycled) {
                working.recycle()
            }
        }
    }

    private fun ensureMutableArgb8888(bitmap: Bitmap): Bitmap {
        if (bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888) {
            return bitmap
        }
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (copy != null) {
            if (copy !== bitmap && !bitmap.isRecycled) {
                bitmap.recycle()
            }
            return copy
        }
        return bitmap
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

    private fun compressOnce(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray? {
        val stream = ByteArrayOutputStream()
        val ok = bitmap.compress(format, quality, stream)
        if (!ok) return null
        val bytes = stream.toByteArray()
        return bytes.takeIf { it.isNotEmpty() }
    }

    private fun downscaleBitmap(bitmap: Bitmap, factor: Float): Bitmap? {
        val nextW = (bitmap.width * factor).roundToInt()
        val nextH = (bitmap.height * factor).roundToInt()
        if (nextW < 64 || nextH < 64) return null
        return Bitmap.createScaledBitmap(bitmap, nextW, nextH, true)
    }
}
