package com.nodeseek.app.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.nodeseek.app.data.network.CoilHttpClientFactory
import com.nodeseek.app.data.network.NodeSeekUrlNormalizer
import com.nodeseek.app.ui.components.MdBlock
import com.nodeseek.app.ui.components.MdInlineToken
import com.nodeseek.app.ui.components.NodeSeekStickerCatalog
import com.nodeseek.app.ui.components.normalizeMarkdownImageUrl
import com.nodeseek.app.ui.components.parseNodeSeekMarkdownDocument
import com.nodeseek.app.ui.components.tokenizeMarkdownInline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

class TopicLongImageAssets(
    val bitmaps: Map<String, Bitmap> = emptyMap(),
) : AutoCloseable {
    fun get(url: String): Bitmap? = bitmaps[url]

    override fun close() {
        val seen = HashSet<Bitmap>()
        bitmaps.values.forEach { bmp ->
            if (seen.add(bmp) && !bmp.isRecycled) {
                bmp.recycle()
            }
        }
    }
}

/**
 * Prefetches avatars, inline stickers (`staticPreviewUrl`), and embedded Markdown images
 * for [TopicLongImageRenderer].
 *
 * Security & Resilience:
 * - Fast-paths Coil disk cache when `context` is available.
 * - Attaches `Referer: https://www.nodeseek.com/` for anti-hotlink CDNs.
 * - Strictly strips forum cookies & `Authorization` headers on non-NodeSeek hosts.
 * - Bounds concurrency (`Semaphore(4)`), item count (`24`), and response size (`8 MB`),
 *   gracefully skipping failed or oversized downloads without failing the share export.
 */
object TopicLongImageAssetFetcher {

    private const val MAX_PREFETCH_URLS = 24
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val TARGET_MAX_IMAGE_WIDTH_PX = 920
    private const val MAX_CONCURRENCY = 4

    private val defaultCookieFreeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .connectTimeout(12L, TimeUnit.SECONDS)
            .readTimeout(20L, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun collectRequiredAssetUrls(request: TopicLongImageRequest): List<String> {
        val urls = LinkedHashSet<String>()

        fun collectFromFloor(markdown: String, avatarUrl: String?, signatureMarkdown: String?, includeSig: Boolean) {
            avatarUrl?.let(::normalizeMarkdownImageUrl)
                ?.takeIf { it.isNotBlank() }
                ?.let { urls += it }

            val doc = parseNodeSeekMarkdownDocument(markdown)
            fun walkTokens(tokens: List<MdInlineToken>) {
                for (token in tokens) {
                    when (token) {
                        is MdInlineToken.Sticker -> urls += token.sticker.staticPreviewUrl
                        is MdInlineToken.Image -> {
                            val normalized = normalizeMarkdownImageUrl(token.url)
                            if (normalized.isNotBlank()) {
                                val sticker = NodeSeekStickerCatalog.resolveFromAssetUrl(normalized)
                                urls += sticker?.staticPreviewUrl ?: normalized
                            }
                        }
                        is MdInlineToken.Link -> walkTokens(token.labelTokens)
                        is MdInlineToken.Styled -> walkTokens(token.children)
                        else -> Unit
                    }
                }
            }

            fun walkBlocks(blocks: List<MdBlock>) {
                for (block in blocks) {
                    when (block) {
                        is MdBlock.Heading -> walkTokens(tokenizeMarkdownInline(block.text, doc.referenceLinks))
                        is MdBlock.Paragraph -> walkTokens(tokenizeMarkdownInline(block.text, doc.referenceLinks))
                        is MdBlock.Blockquote -> walkBlocks(block.children)
                        is MdBlock.ImageCard -> {
                            val normalized = normalizeMarkdownImageUrl(block.url)
                            if (normalized.isNotBlank()) {
                                val sticker = NodeSeekStickerCatalog.resolveFromAssetUrl(normalized)
                                urls += sticker?.staticPreviewUrl ?: normalized
                            }
                        }
                        is MdBlock.ImageRow -> {
                            block.items.forEach { item ->
                                val normalized = normalizeMarkdownImageUrl(item.imageUrl)
                                if (normalized.isNotBlank()) {
                                    val sticker = NodeSeekStickerCatalog.resolveFromAssetUrl(normalized)
                                    urls += sticker?.staticPreviewUrl ?: normalized
                                }
                            }
                        }
                        is MdBlock.ListContainer -> {
                            block.items.forEach { li ->
                                walkTokens(tokenizeMarkdownInline(li.text, doc.referenceLinks))
                            }
                        }
                        is MdBlock.Table -> {
                            block.headers.forEach { walkTokens(tokenizeMarkdownInline(it, doc.referenceLinks)) }
                            block.rows.forEach { row ->
                                row.forEach { walkTokens(tokenizeMarkdownInline(it, doc.referenceLinks)) }
                            }
                        }
                        is MdBlock.TabsContainer -> block.tabs.forEach { walkBlocks(it.children) }
                        is MdBlock.DetailsSpoiler -> {
                            walkTokens(tokenizeMarkdownInline(block.summary, doc.referenceLinks))
                            walkBlocks(block.children)
                        }
                        is MdBlock.CodeFence,
                        is MdBlock.SpecialWidget,
                        MdBlock.HorizontalRule,
                        -> Unit
                    }
                }
            }
            walkBlocks(doc.blocks)

            if (includeSig && !signatureMarkdown.isNullOrBlank()) {
                val sigDoc = parseNodeSeekMarkdownDocument(signatureMarkdown)
                walkBlocks(sigDoc.blocks)
            }
        }

        collectFromFloor(
            markdown = request.targetFloor.markdownBody,
            avatarUrl = request.targetFloor.author.avatarUrl,
            signatureMarkdown = request.targetFloor.signatureMarkdown,
            includeSig = request.includeSignature,
        )
        request.highlightReply?.let { reply ->
            collectFromFloor(
                markdown = reply.markdownBody,
                avatarUrl = reply.author.avatarUrl,
                signatureMarkdown = null,
                includeSig = false,
            )
        }

        return urls.filter { url ->
            val lower = url.lowercase()
            lower.startsWith("https://") || lower.startsWith("http://")
        }.take(MAX_PREFETCH_URLS)
    }

    suspend fun prefetch(
        context: Context,
        request: TopicLongImageRequest,
        coilOkHttpClient: OkHttpClient? = null,
    ): TopicLongImageAssets = withContext(Dispatchers.IO) {
        val urls = collectRequiredAssetUrls(request)
        if (urls.isEmpty()) return@withContext TopicLongImageAssets()

        val hardenedClient = buildScopedImageClient(coilOkHttpClient ?: defaultCookieFreeClient)
        val semaphore = Semaphore(MAX_CONCURRENCY)
        val loaded = coroutineScope {
            urls.map { url ->
                async {
                    semaphore.withPermit {
                        val bmp = runCatching {
                            loadSingleBitmap(context = context, url = url, client = hardenedClient)
                        }.getOrNull()
                        if (bmp != null) url to bmp else null
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
        TopicLongImageAssets(loaded)
    }

    /**
     * Pure network fetch path (for JVM tests or non-Context callers using [MockWebServer]).
     * Still enforces `Referer` and strips forum cookies on external hosts.
     */
    internal suspend fun fetchBytesForUrl(
        url: String,
        coilOkHttpClient: OkHttpClient? = null,
        maxBytes: Int = MAX_IMAGE_BYTES,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val parsed = url.toHttpUrlOrNull() ?: return@withContext null
        val scheme = parsed.scheme.lowercase()
        if (scheme != "https" && scheme != "http") return@withContext null

        val hardenedClient = buildScopedImageClient(coilOkHttpClient ?: defaultCookieFreeClient)
        val req = Request.Builder()
            .url(parsed)
            .get()
            .header("Referer", "${NodeSeekUrlNormalizer.ORIGIN}/")
            .header("Accept", CoilHttpClientFactory.IMAGE_ACCEPT_HEADER)
            .build()

        hardenedClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body ?: return@withContext null
            val declaredLen = body.contentLength()
            if (declaredLen > maxBytes) return@withContext null
            readBoundedBytes(body.byteStream(), maxBytes)
        }
    }

    private fun loadSingleBitmap(
        context: Context,
        url: String,
        client: OkHttpClient,
    ): Bitmap? {
        // 1. Check Coil 3 disk cache first
        val diskHit = runCatching {
            val loader = SingletonImageLoader.get(context)
            loader.diskCache?.openSnapshot(url)?.use { snapshot ->
                val file = File(snapshot.data.toString())
                if (file.isFile && file.length() in 1L..MAX_IMAGE_BYTES.toLong()) {
                    file.readBytes()
                } else {
                    null
                }
            }
        }.getOrNull()

        val rawBytes = diskHit ?: run {
            val parsed = url.toHttpUrlOrNull() ?: return null
            val req = Request.Builder()
                .url(parsed)
                .get()
                .header("Referer", "${NodeSeekUrlNormalizer.ORIGIN}/")
                .header("Accept", CoilHttpClientFactory.IMAGE_ACCEPT_HEADER)
                .build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                if (body.contentLength() > MAX_IMAGE_BYTES) return null
                val contentType = body.contentType()?.toString().orEmpty()
                val loadedBytes = readBoundedBytes(body.byteStream(), MAX_IMAGE_BYTES) ?: return null
                if (contentType.contains("svg", ignoreCase = true) ||
                    url.substringBefore('?').endsWith(".svg", ignoreCase = true)
                ) {
                    return rasterizeSvgViaCoil(context, url)
                }
                loadedBytes
            }
        }

        if (rawBytes.isEmpty()) return null
        val head = String(rawBytes, 0, minOf(rawBytes.size, 256), Charsets.UTF_8).trimStart()
        if (head.startsWith("<svg", ignoreCase = true) ||
            (head.startsWith("<?xml", ignoreCase = true) && head.contains("<svg", ignoreCase = true))
        ) {
            return rasterizeSvgViaCoil(context, url)
        }
        return decodeSoftwareScaledBitmap(rawBytes, TARGET_MAX_IMAGE_WIDTH_PX)
    }

    private fun rasterizeSvgViaCoil(context: Context, url: String): Bitmap? {
        return runCatching {
            val loader = SingletonImageLoader.get(context)
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(TARGET_MAX_IMAGE_WIDTH_PX, TARGET_MAX_IMAGE_WIDTH_PX * 2)
                .allowHardware(false)
                .build()
            val result = kotlinx.coroutines.runBlocking { loader.execute(request) } as? SuccessResult
                ?: return null
            result.image.toBitmap()
        }.getOrNull()
    }

    private fun decodeSoftwareScaledBitmap(bytes: ByteArray, maxWidthPx: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    val longest = max(w, h)
                    if (longest > maxWidthPx && maxWidthPx > 0) {
                        val scale = maxWidthPx.toFloat() / longest.toFloat()
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
        while (bounds.outWidth / sampleSize > maxWidthPx && maxWidthPx > 0) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    internal fun buildScopedImageClient(baseClient: OkHttpClient): OkHttpClient {
        val delegateJar = baseClient.cookieJar
        val scopedCookieJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                if (NodeSeekUrlNormalizer.isNodeSeekHost(url.host)) {
                    delegateJar.saveFromResponse(url, cookies)
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return if (NodeSeekUrlNormalizer.isNodeSeekHost(url.host)) {
                    delegateJar.loadForRequest(url)
                } else {
                    emptyList()
                }
            }
        }
        val isolationInterceptor = Interceptor { chain ->
            val original = chain.request()
            val isNodeSeek = NodeSeekUrlNormalizer.isNodeSeekHost(original.url.host)
            val builder = original.newBuilder()
                .header("Referer", "${NodeSeekUrlNormalizer.ORIGIN}/")
                .header("Accept", CoilHttpClientFactory.IMAGE_ACCEPT_HEADER)
            if (!isNodeSeek) {
                builder.removeHeader("Cookie")
                builder.removeHeader("Authorization")
            }
            chain.proceed(builder.build())
        }
        return baseClient.newBuilder()
            .cookieJar(scopedCookieJar)
            .addInterceptor(isolationInterceptor)
            .build()
    }

    private fun readBoundedBytes(stream: java.io.InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream(8192)
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
