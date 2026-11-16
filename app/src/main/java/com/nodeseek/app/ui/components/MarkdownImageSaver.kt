package com.nodeseek.app.ui.components

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.nodeseek.app.NodePulseApp
import com.nodeseek.app.data.network.CoilHttpClientFactory
import com.nodeseek.app.data.network.NodeSeekUrlNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class DetectedImageFormat(
    val mimeType: String,
    val extension: String,
    val isSvg: Boolean = false,
)

private data class RetrievedImagePayload(
    val bytes: ByteArray,
    val contentTypeHeader: String?,
)

/**
 * Downloads (or reads from Coil's disk cache) post/reply/lightbox images and saves them
 * into the public system gallery (`Pictures/Anode`) via [MediaStore].
 *
 * - Raster & animated formats (PNG/JPEG/GIF/WEBP/AVIF/HEIC/BMP) are written byte-for-byte
 *   so animated stickers/GIFs keep animation and photos suffer zero recompression.
 * - SVG reports (such as NodeQuality / Check.Place / IPQuality) are rasterized to high-res PNG
 *   because Android Gallery apps cannot index raw `.svg` files in `MediaStore.Images`.
 */
object MarkdownImageSaver {

    private const val GALLERY_SUBDIR = "Anode"
    private val saverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightUrls = Collections.synchronizedSet(HashSet<String>())

    private val fallbackOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20L, TimeUnit.SECONDS)
            .readTimeout(30L, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun saveImageToGallery(
        context: Context,
        rawImageUrl: String,
        altText: String = "",
    ) {
        val appContext = context.applicationContext ?: context
        val resolvedUrl = normalizeMarkdownImageUrl(rawImageUrl)
        if (resolvedUrl.isBlank()) {
            postToast(appContext, "保存失败：图片地址无效")
            return
        }
        if (!inFlightUrls.add(resolvedUrl)) {
            postToast(appContext, "图片正在保存中…")
            return
        }

        saverScope.launch {
            try {
                val retrieved = loadImageBytes(appContext, resolvedUrl)
                val detected = detectImageFormat(
                    bytes = retrieved.bytes,
                    contentTypeHeader = retrieved.contentTypeHeader,
                    url = resolvedUrl,
                )

                val contentTypeLower = retrieved.contentTypeHeader?.lowercase().orEmpty()
                if (contentTypeLower.contains("text/html") && !detected.isSvg) {
                    throw IllegalStateException("图片源返回了网页而非图片")
                }

                val (finalBytes, finalMime, finalExt) = if (detected.isSvg) {
                    val pngBytes = rasterizeSvgToPng(appContext, resolvedUrl)
                        ?: throw IllegalStateException("SVG 图片转换失败")
                    Triple(pngBytes, "image/png", "png")
                } else {
                    Triple(retrieved.bytes, detected.mimeType, detected.extension)
                }

                val fileName = buildSavedImageFileName(
                    altText = altText,
                    url = resolvedUrl,
                    extension = finalExt,
                    nowMillis = System.currentTimeMillis(),
                )
                writeToPicturesDirectory(
                    context = appContext,
                    fileName = fileName,
                    mimeType = finalMime,
                    bytes = finalBytes,
                )
                postToast(appContext, "已保存到相册 (Pictures/$GALLERY_SUBDIR)")
            } catch (t: Throwable) {
                val reason = t.message?.takeIf { it.isNotBlank() } ?: "请检查网络或存储权限"
                postToast(appContext, "保存图片失败：$reason")
            } finally {
                inFlightUrls.remove(resolvedUrl)
            }
        }
    }

    private suspend fun loadImageBytes(
        appContext: Context,
        resolvedUrl: String,
    ): RetrievedImagePayload {
        val lower = resolvedUrl.lowercase()
        if (lower.startsWith("data:")) {
            val (bytes, mime) = decodeDataUriImage(resolvedUrl)
                ?: throw IllegalArgumentException("无法解析内嵌图片数据")
            return RetrievedImagePayload(bytes = bytes, contentTypeHeader = mime)
        }

        if (lower.startsWith("content:") || lower.startsWith("file:")) {
            val uri = Uri.parse(resolvedUrl)
            val mime = appContext.contentResolver.getType(uri)
            val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("无法读取本地图片")
            if (bytes.isEmpty()) throw IllegalStateException("图片内容为空")
            return RetrievedImagePayload(bytes = bytes, contentTypeHeader = mime)
        }

        // Fast path: check Coil 3 disk cache first before making a network call.
        val cachedBytes = runCatching {
            val imageLoader = SingletonImageLoader.get(appContext)
            imageLoader.diskCache?.openSnapshot(resolvedUrl)?.use { snapshot ->
                val cacheFile = File(snapshot.data.toString())
                if (cacheFile.isFile && cacheFile.length() > 0L) {
                    cacheFile.readBytes()
                } else {
                    null
                }
            }
        }.getOrNull()

        if (cachedBytes != null && cachedBytes.isNotEmpty()) {
            return RetrievedImagePayload(bytes = cachedBytes, contentTypeHeader = null)
        }

        postToast(appContext, "正在下载原图…")

        val client = (appContext as? NodePulseApp)?.container?.coilOkHttpClient ?: fallbackOkHttpClient
        val requestBuilder = Request.Builder()
            .url(resolvedUrl)
            .get()
        if (client === fallbackOkHttpClient) {
            requestBuilder
                .header("Referer", "${NodeSeekUrlNormalizer.ORIGIN}/")
                .header("Accept", CoilHttpClientFactory.IMAGE_ACCEPT_HEADER)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("响应内容为空")
            val contentType = body.contentType()?.let { "${it.type}/${it.subtype}" }
                ?: response.header("Content-Type")
            val bytes = body.bytes()
            if (bytes.isEmpty()) {
                throw IllegalStateException("图片数据为空")
            }
            return RetrievedImagePayload(bytes = bytes, contentTypeHeader = contentType)
        }
    }

    private suspend fun rasterizeSvgToPng(
        appContext: Context,
        resolvedUrl: String,
    ): ByteArray? {
        val imageLoader = SingletonImageLoader.get(appContext)
        val request = ImageRequest.Builder(appContext)
            .data(resolvedUrl)
            .size(1800, 4096)
            .allowHardware(false)
            .build()
        val result = imageLoader.execute(request) as? SuccessResult ?: return null
        val bitmap = result.image.toBitmap()
        return ByteArrayOutputStream().use { out ->
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                out.toByteArray()
            } else {
                null
            }
        }
    }

    private fun writeToPicturesDirectory(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$GALLERY_SUBDIR",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val itemUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建相册文件")
            try {
                resolver.openOutputStream(itemUri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                } ?: throw IllegalStateException("无法写入相册流")

                val publishValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(itemUri, publishValues, null, null)
            } catch (t: Throwable) {
                runCatching { resolver.delete(itemUri, null, null) }
                throw t
            }
        } else {
            @Suppress("DEPRECATION")
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val targetDir = File(picturesDir, GALLERY_SUBDIR)
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                throw IllegalStateException("无法创建图片目录")
            }
            val outFile = File(targetDir, fileName)
            outFile.writeBytes(bytes)
            MediaScannerConnection.scanFile(
                context,
                arrayOf(outFile.absolutePath),
                arrayOf(mimeType),
                null,
            )
        }
    }

    private fun postToast(context: Context, message: String) {
        saverScope.launch(Dispatchers.Main.immediate) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}

internal fun detectImageFormat(
    bytes: ByteArray,
    contentTypeHeader: String?,
    url: String,
): DetectedImageFormat {
    // 1. Inspect binary magic bytes first so CDNs serving WebP/AVIF/PNG under .jpg paths save accurately.
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
        return DetectedImageFormat(mimeType = "image/png", extension = "png")
    }

    if (bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte()
    ) {
        return DetectedImageFormat(mimeType = "image/jpeg", extension = "jpg")
    }

    if (bytes.size >= 6) {
        val header6 = String(bytes, 0, 6, StandardCharsets.US_ASCII)
        if (header6 == "GIF87a" || header6 == "GIF89a") {
            return DetectedImageFormat(mimeType = "image/gif", extension = "gif")
        }
    }

    if (bytes.size >= 12) {
        val riff = String(bytes, 0, 4, StandardCharsets.US_ASCII)
        val webp = String(bytes, 8, 4, StandardCharsets.US_ASCII)
        if (riff == "RIFF" && webp == "WEBP") {
            return DetectedImageFormat(mimeType = "image/webp", extension = "webp")
        }
    }

    if (bytes.size >= 2 && bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte()) {
        return DetectedImageFormat(mimeType = "image/bmp", extension = "bmp")
    }

    if (bytes.size >= 12 && String(bytes, 4, 4, StandardCharsets.US_ASCII) == "ftyp") {
        val brand = String(bytes, 8, 4, StandardCharsets.US_ASCII).lowercase()
        when (brand) {
            "avif", "avis" -> return DetectedImageFormat(mimeType = "image/avif", extension = "avif")
            "heic", "heix", "hevc", "mif1" -> return DetectedImageFormat(mimeType = "image/heic", extension = "heic")
        }
    }

    // 2. Inspect XML/SVG text signature or SVG Content-Type header.
    val peekLen = bytes.size.coerceAtMost(512)
    val headText = if (peekLen > 0) {
        String(bytes, 0, peekLen, StandardCharsets.UTF_8)
            .removePrefix("\uFEFF")
            .trimStart()
            .lowercase()
    } else {
        ""
    }
    val looksLikeSvgMarkup = headText.startsWith("<svg") ||
        ((headText.startsWith("<?xml") || headText.startsWith("<!doctype") || headText.startsWith("<!--")) &&
            headText.contains("<svg"))
    val normalizedMime = contentTypeHeader
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        .orEmpty()

    if (looksLikeSvgMarkup || normalizedMime == "image/svg+xml" || normalizedMime.contains("svg")) {
        return DetectedImageFormat(mimeType = "image/svg+xml", extension = "svg", isSvg = true)
    }

    // 3. Fallback to Content-Type header.
    when (normalizedMime) {
        "image/png" -> return DetectedImageFormat(mimeType = "image/png", extension = "png")
        "image/jpeg", "image/jpg" -> return DetectedImageFormat(mimeType = "image/jpeg", extension = "jpg")
        "image/gif" -> return DetectedImageFormat(mimeType = "image/gif", extension = "gif")
        "image/webp" -> return DetectedImageFormat(mimeType = "image/webp", extension = "webp")
        "image/avif" -> return DetectedImageFormat(mimeType = "image/avif", extension = "avif")
        "image/heic" -> return DetectedImageFormat(mimeType = "image/heic", extension = "heic")
        "image/bmp" -> return DetectedImageFormat(mimeType = "image/bmp", extension = "bmp")
    }

    // 4. Fallback to URL path extension (ignoring ?query and #fragment).
    val cleanPath = url.substringBefore('?').substringBefore('#')
    val lastSegment = cleanPath.substringAfterLast('/', "")
    val ext = lastSegment.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png" -> DetectedImageFormat(mimeType = "image/png", extension = "png")
        "jpg", "jpeg" -> DetectedImageFormat(mimeType = "image/jpeg", extension = "jpg")
        "gif" -> DetectedImageFormat(mimeType = "image/gif", extension = "gif")
        "webp" -> DetectedImageFormat(mimeType = "image/webp", extension = "webp")
        "avif" -> DetectedImageFormat(mimeType = "image/avif", extension = "avif")
        "heic" -> DetectedImageFormat(mimeType = "image/heic", extension = "heic")
        "bmp" -> DetectedImageFormat(mimeType = "image/bmp", extension = "bmp")
        "svg" -> DetectedImageFormat(mimeType = "image/svg+xml", extension = "svg", isSvg = true)
        else -> DetectedImageFormat(mimeType = "image/jpeg", extension = "jpg")
    }
}

internal fun decodeDataUriImage(dataUri: String): Pair<ByteArray, String?>? {
    if (!dataUri.startsWith("data:", ignoreCase = true)) return null
    val commaIdx = dataUri.indexOf(',')
    if (commaIdx <= 4 || commaIdx >= dataUri.length - 1) return null

    val metadata = dataUri.substring(5, commaIdx)
    val payload = dataUri.substring(commaIdx + 1)
    if (payload.isEmpty()) return null

    val parts = metadata.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    val isBase64 = parts.any { it.equals("base64", ignoreCase = true) }
    val mimeType = parts.firstOrNull { it.contains('/') }?.lowercase()

    val bytes = if (isBase64) {
        val cleaned = payload.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) return null
        runCatching { Base64.getDecoder().decode(cleaned) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(cleaned) }.getOrNull()
            ?: return null
    } else {
        val safePlus = payload.replace("+", "%2B")
        val decoded = runCatching {
            URLDecoder.decode(safePlus, StandardCharsets.UTF_8.name())
        }.getOrNull() ?: return null
        decoded.toByteArray(StandardCharsets.UTF_8)
    }

    return bytes.takeIf { it.isNotEmpty() }?.let { it to mimeType }
}

internal fun buildSavedImageFileName(
    altText: String,
    url: String,
    extension: String,
    nowMillis: Long,
): String {
    val cleanExt = extension
        .lowercase()
        .filter { it in 'a'..'z' || it in '0'..'9' }
        .ifBlank { "jpg" }
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMillis))
    val hashHex = (url + "|" + altText)
        .hashCode()
        .toUInt()
        .toString(16)
        .padStart(8, '0')
        .take(6)
    return "Anode_${timestamp}_$hashHex.$cleanExt"
}
