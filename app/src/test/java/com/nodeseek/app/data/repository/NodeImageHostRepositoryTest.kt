package com.nodeseek.app.data.repository

import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.network.UserAgentProvider
import com.nodeseek.app.data.network.WebViewCookieBridge
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

class NodeImageHostRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var apiClient: NodeSeekApiClient
    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var sessionRepository: SessionRepositoryImpl

    private val testApiKey = "0123456789abcdef0123456789abcdef"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/")
        val cookieBridge = WebViewCookieBridge(forceInMemory = true)
        val okHttp = NodeSeekApiClient.createDefaultHttpClient(
            cookieBridge = cookieBridge,
            userAgentProvider = UserAgentProvider("NodeSeekImageTest/1.0"),
            additionalTargetHost = baseUrl.host,
        )
        apiClient = NodeSeekApiClient(
            httpClient = okHttp,
            baseOrigin = baseUrl,
            rssUrl = server.url("/rss.xml"),
            nodeImageOrigin = baseUrl,
        )
        settingsRepository = FakeSettingsRepository().apply {
            runBlocking { setNodeImageApiKey(testApiKey) }
        }
        sessionRepository = SessionRepositoryImpl(
            apiClientProvider = { apiClient },
            cookieBridge = cookieBridge,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `format inspector prioritizes magic bytes over wrong mime and extension across all formats`() {
        val pngBytes = makePngBytes()
        val sniffPng = ImageUploadFormatInspector.inspect(
            bytes = pngBytes,
            declaredMimeType = "image/jpeg",
            fileName = "wrong_extension.jpg",
        )
        assertNotNull(sniffPng)
        assertEquals(DetectedImageFormat.PNG, sniffPng!!.format)
        assertFalse(sniffPng.isAnimated)
        assertTrue(sniffPng.detectedByMagic)

        val apng = ImageUploadFormatInspector.inspect(makeAnimatedPngBytes())
        assertEquals(DetectedImageFormat.PNG, apng?.format)
        assertTrue(apng?.isAnimated == true)

        val jpg = ImageUploadFormatInspector.inspect(makeJpegBytes())
        assertEquals(DetectedImageFormat.JPEG, jpg?.format)
        assertFalse(jpg!!.isAnimated)

        val gif = ImageUploadFormatInspector.inspect(makeGifBytes())
        assertEquals(DetectedImageFormat.GIF, gif?.format)
        assertTrue(gif!!.isAnimated)

        val staticWebp = ImageUploadFormatInspector.inspect(makeStaticWebpBytes())
        assertEquals(DetectedImageFormat.WEBP, staticWebp?.format)
        assertFalse(staticWebp!!.isAnimated)

        val animWebp = ImageUploadFormatInspector.inspect(makeAnimatedWebpBytes())
        assertEquals(DetectedImageFormat.WEBP, animWebp?.format)
        assertTrue(animWebp!!.isAnimated)

        val bmp = ImageUploadFormatInspector.inspect(makeBmpBytes())
        assertEquals(DetectedImageFormat.BMP, bmp?.format)
        assertFalse(bmp!!.format.isNativeForUpload)

        val heic = ImageUploadFormatInspector.inspect(makeFtypBytes("heic"))
        assertEquals(DetectedImageFormat.HEIC, heic?.format)

        val heif = ImageUploadFormatInspector.inspect(makeFtypBytes("mif1", "heif"))
        assertEquals(DetectedImageFormat.HEIF, heif?.format)

        val avif = ImageUploadFormatInspector.inspect(makeFtypBytes("avif"))
        assertEquals(DetectedImageFormat.AVIF, avif?.format)

        val svg = ImageUploadFormatInspector.inspect("""<svg xmlns="http://www.w3.org/2000/svg"></svg>""".toByteArray())
        assertEquals(DetectedImageFormat.SVG, svg?.format)
    }

    @Test
    fun `format inspector falls back to declared mime or filename when bytes lack binary header`() {
        val opaqueBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)

        val byMime = ImageUploadFormatInspector.inspect(
            bytes = opaqueBytes,
            declaredMimeType = "image/webp",
            fileName = "document_without_ext",
        )
        assertEquals(DetectedImageFormat.WEBP, byMime?.format)
        assertFalse(byMime!!.detectedByMagic)

        val byExt = ImageUploadFormatInspector.inspect(
            bytes = opaqueBytes,
            declaredMimeType = "application/octet-stream",
            fileName = "/storage/emulated/0/Download/screenshot.png",
        )
        assertEquals(DetectedImageFormat.PNG, byExt?.format)
        assertFalse(byExt!!.detectedByMagic)
    }

    @Test
    fun `native PNG with wrong jpg metadata uploads raw PNG bytes with corrected png filename and mime`() = runBlocking {
        var transcoderInvoked = false
        val repo = NodeImageHostRepositoryImpl(
            apiClientProvider = { apiClient },
            settingsRepository = settingsRepository,
            sessionRepository = sessionRepository,
            imageTranscoder = object : ImageUploadTranscoder {
                override fun transcodeToFit(
                    sourceBytes: ByteArray,
                    sourceFormat: DetectedImageFormat?,
                    maxBytes: Int,
                    maxDimensionPx: Int,
                ): PreparedImageUpload? {
                    transcoderInvoked = true
                    return null
                }
            },
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"links":{"direct":"https://img.nodeimage.com/i/png_ok.png"}}"""),
        )

        val rawPng = makePngBytes()
        val result = repo.uploadImage(
            bytes = rawPng,
            mimeType = "image/jpeg",
            fileName = "alpha_badge.jpg",
        )

        assertTrue(result.isSuccess)
        assertEquals("https://img.nodeimage.com/i/png_ok.png", result.getOrThrow())
        assertFalse("Native PNG under size limit must not be re-encoded", transcoderInvoked)

        val request = server.takeRequest()
        assertEquals("/api/upload", request.path)
        assertNull(request.getHeader("Cookie"))
        assertEquals(testApiKey, request.getHeader("X-API-Key"))
        val bodyText = request.body.readUtf8()
        assertTrue(bodyText.contains("""filename="alpha_badge.png""""))
        assertTrue(bodyText.contains("Content-Type: image/png"))
    }

    @Test
    fun `animated GIF and animated WEBP bypass bitmap transcoder even above static size limit`() = runBlocking {
        var transcoderInvoked = false
        val repo = NodeImageHostRepositoryImpl(
            apiClientProvider = { apiClient },
            settingsRepository = settingsRepository,
            sessionRepository = sessionRepository,
            imageTranscoder = object : ImageUploadTranscoder {
                override fun transcodeToFit(
                    sourceBytes: ByteArray,
                    sourceFormat: DetectedImageFormat?,
                    maxBytes: Int,
                    maxDimensionPx: Int,
                ): PreparedImageUpload? {
                    transcoderInvoked = true
                    return null
                }
            },
            maxStaticUploadBytes = 16,
            maxAnimatedUploadBytes = 4096,
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"links":{"direct":"https://img.nodeimage.com/i/anim.gif"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"links":{"direct":"https://img.nodeimage.com/i/anim.webp"}}"""),
        )

        val gifBytes = makeGifBytes(extraPayloadSize = 64)
        val gifResult = repo.uploadImage(bytes = gifBytes, mimeType = "", fileName = "reaction")
        assertEquals("https://img.nodeimage.com/i/anim.gif", gifResult.getOrThrow())

        val webpBytes = makeAnimatedWebpBytes(extraPayloadSize = 64)
        val webpResult = repo.uploadImage(bytes = webpBytes, mimeType = "image/webp", fileName = "sticker.webp")
        assertEquals("https://img.nodeimage.com/i/anim.webp", webpResult.getOrThrow())
        assertFalse("Animated GIF/WEBP must never go through static transcoder", transcoderInvoked)

        val gifBody = server.takeRequest().body.readUtf8()
        assertTrue(gifBody.contains("""filename="reaction.gif""""))
        assertTrue(gifBody.contains("Content-Type: image/gif"))

        val webpBody = server.takeRequest().body.readUtf8()
        assertTrue(webpBody.contains("""filename="sticker.webp""""))
        assertTrue(webpBody.contains("Content-Type: image/webp"))
    }

    @Test
    fun `animated GIF exceeding animated size limit fails cleanly without network call`() = runBlocking {
        val repo = NodeImageHostRepositoryImpl(
            apiClientProvider = { apiClient },
            settingsRepository = settingsRepository,
            sessionRepository = sessionRepository,
            maxStaticUploadBytes = 16,
            maxAnimatedUploadBytes = 32,
        )

        val oversizedGif = makeGifBytes(extraPayloadSize = 128)
        val result = repo.uploadImage(bytes = oversizedGif, mimeType = "image/gif", fileName = "huge.gif")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("动图体积超过图床限制"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `HEIC and oversized static PNG invoke transcoder and align multipart extension and mime`() = runBlocking {
        val capturedFormats = mutableListOf<DetectedImageFormat?>()
        val repo = NodeImageHostRepositoryImpl(
            apiClientProvider = { apiClient },
            settingsRepository = settingsRepository,
            sessionRepository = sessionRepository,
            imageTranscoder = object : ImageUploadTranscoder {
                override fun transcodeToFit(
                    sourceBytes: ByteArray,
                    sourceFormat: DetectedImageFormat?,
                    maxBytes: Int,
                    maxDimensionPx: Int,
                ): PreparedImageUpload {
                    capturedFormats += sourceFormat
                    return if (sourceFormat == DetectedImageFormat.PNG) {
                        PreparedImageUpload(
                            bytes = makeStaticWebpBytes(),
                            mimeType = "image/webp",
                            fileName = "converted.webp",
                        )
                    } else {
                        PreparedImageUpload(
                            bytes = makeJpegBytes(),
                            mimeType = "image/jpeg",
                            fileName = "converted.jpg",
                        )
                    }
                }
            },
            maxStaticUploadBytes = 64,
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"links":{"markdown":"![photo](http://img.nodeimage.com/i/from_heic.jpg)"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"links":{"direct":"//img.nodeimage.com/i/compressed.webp"}}"""),
        )

        val heicResult = repo.uploadImage(
            bytes = makeFtypBytes("heic"),
            mimeType = "image/heic",
            fileName = "IMG_2026.HEIC",
        )
        assertEquals("https://img.nodeimage.com/i/from_heic.jpg", heicResult.getOrThrow())

        val largePngBytes = makePngBytes(extraPayloadSize = 128)
        val pngResult = repo.uploadImage(
            bytes = largePngBytes,
            mimeType = "image/png",
            fileName = "transparent_shot.png",
        )
        assertEquals("https://img.nodeimage.com/i/compressed.webp", pngResult.getOrThrow())

        assertEquals(listOf(DetectedImageFormat.HEIC, DetectedImageFormat.PNG), capturedFormats)

        val heicReq = server.takeRequest()
        val heicBodyText = heicReq.body.readUtf8()
        assertTrue(heicBodyText.contains("""filename="IMG_2026.jpg""""))
        assertTrue(heicBodyText.contains("Content-Type: image/jpeg"))

        val pngReq = server.takeRequest()
        val pngBodyText = pngReq.body.readUtf8()
        assertTrue(pngBodyText.contains("""filename="transparent_shot.webp""""))
        assertTrue(pngBodyText.contains("Content-Type: image/webp"))
    }

    @Test
    fun `prepareImageForUpload falls back to direct AVIF upload when transcoder cannot decode it`() {
        val avifBytes = makeFtypBytes("avif")
        val repo = NodeImageHostRepositoryImpl(
            apiClientProvider = { apiClient },
            settingsRepository = settingsRepository,
            sessionRepository = sessionRepository,
            imageTranscoder = object : ImageUploadTranscoder {
                override fun transcodeToFit(
                    sourceBytes: ByteArray,
                    sourceFormat: DetectedImageFormat?,
                    maxBytes: Int,
                    maxDimensionPx: Int,
                ): PreparedImageUpload? = null
            },
        )

        val prepared = repo.prepareImageForUpload(
            bytes = avifBytes,
            declaredMimeType = "",
            fileName = "sample",
        )
        assertArrayEquals(avifBytes, prepared.bytes)
        assertEquals("image/avif", prepared.mimeType)
        assertEquals("sample.avif", prepared.fileName)
    }

    @Test
    fun `uploadImage fails cleanly on plaintext http NodeImage origin and cross-host redirect without leaking key`() = runBlocking {
        val insecureApiClient = NodeSeekApiClient(
            httpClient = OkHttpClient(),
            nodeImageOrigin = "http://api.nodeimage.com".toHttpUrl(),
        )
        val insecureRepo = NodeImageHostRepositoryImpl(
            apiClientProvider = { insecureApiClient },
            settingsRepository = settingsRepository,
            sessionRepository = sessionRepository,
        )
        val httpFailure = insecureRepo.uploadImage(
            bytes = makePngBytes(),
            mimeType = "image/png",
            fileName = "secret.png",
        )
        assertTrue(httpFailure.isFailure)
        assertTrue(httpFailure.exceptionOrNull()?.message.orEmpty().contains("HTTPS"))
        assertEquals(0, server.requestCount)

        MockWebServer().use { evilServer ->
            evilServer.start()
            evilServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
            server.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .addHeader("Location", "http://evil.example:${evilServer.port}/steal"),
            )

            val repo = NodeImageHostRepositoryImpl(
                apiClientProvider = { apiClient },
                settingsRepository = settingsRepository,
                sessionRepository = sessionRepository,
            )
            val redirectFailure = repo.uploadImage(
                bytes = makePngBytes(),
                mimeType = "image/png",
                fileName = "secret.png",
            )
            assertTrue(redirectFailure.isFailure)
            assertTrue(redirectFailure.exceptionOrNull()?.message.orEmpty().contains("跨源"))
            assertEquals(0, evilServer.requestCount)
        }
    }

    private fun makeJpegBytes(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)

    private fun makePngBytes(extraPayloadSize: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(pngChunk("IHDR", ByteArray(13)))
        if (extraPayloadSize > 0) {
            out.write(pngChunk("IDAT", ByteArray(extraPayloadSize)))
        }
        return out.toByteArray()
    }

    private fun makeAnimatedPngBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(pngChunk("IHDR", ByteArray(13)))
        out.write(pngChunk("acTL", ByteArray(8)))
        return out.toByteArray()
    }

    private fun pngChunk(type: String, payload: ByteArray): ByteArray {
        val len = payload.size
        val out = ByteArrayOutputStream()
        out.write((len ushr 24) and 0xFF)
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(payload)
        out.write(ByteArray(4))
        return out.toByteArray()
    }

    private fun makeGifBytes(extraPayloadSize: Int = 0): ByteArray =
        "GIF89a".toByteArray(Charsets.US_ASCII) + ByteArray(extraPayloadSize)

    private fun makeStaticWebpBytes(): ByteArray =
        "RIFF".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x20, 0x00, 0x00, 0x00) +
            "WEBPVP8 ".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x04, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04)

    private fun makeAnimatedWebpBytes(extraPayloadSize: Int = 0): ByteArray {
        val header = "RIFF".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x30, 0x00, 0x00, 0x00) +
            "WEBPVP8X".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x0A, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        return if (extraPayloadSize > 0) header + ByteArray(extraPayloadSize) else header
    }

    private fun makeBmpBytes(): ByteArray =
        byteArrayOf('B'.code.toByte(), 'M'.code.toByte(), 0x00, 0x00, 0x00, 0x00)

    private fun makeFtypBytes(majorBrand: String, compatibleBrand: String? = null): ByteArray {
        val brands = buildString {
            append(majorBrand.padEnd(4, ' ').take(4))
            if (compatibleBrand != null) {
                append(compatibleBrand.padEnd(4, ' ').take(4))
            }
        }
        val boxLen = 12 + brands.length
        return byteArrayOf(
            ((boxLen ushr 24) and 0xFF).toByte(),
            ((boxLen ushr 16) and 0xFF).toByte(),
            ((boxLen ushr 8) and 0xFF).toByte(),
            (boxLen and 0xFF).toByte(),
        ) + "ftyp".toByteArray(Charsets.US_ASCII) +
            brands.take(4).toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 0, 0) +
            brands.drop(4).toByteArray(Charsets.US_ASCII)
    }
}
