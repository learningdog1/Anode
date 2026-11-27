package com.nodeseek.app.e2e.m5

import com.nodeseek.app.data.network.NodeSeekApiClient
import com.nodeseek.app.data.parser.TempScriptParser
import com.nodeseek.app.data.repository.DetectedImageFormat
import com.nodeseek.app.data.repository.ImageUploadTranscoder
import com.nodeseek.app.data.repository.NodeImageHostRepositoryImpl
import com.nodeseek.app.data.repository.PreparedImageUpload
import com.nodeseek.app.domain.image.BitmapMosaicProcessor
import com.nodeseek.app.domain.image.MosaicEdit
import com.nodeseek.app.domain.image.MosaicPoint
import com.nodeseek.app.domain.image.MosaicRect
import com.nodeseek.app.e2e.E2eFixtures
import com.nodeseek.app.e2e.E2eMainDispatcherRule
import com.nodeseek.app.e2e.E2eTestHarness
import com.nodeseek.app.e2e.fixtures.E2eBitmapAndTlsFixtures
import com.nodeseek.app.ui.composer.ImagePrivacyBitmapCodec
import com.nodeseek.app.ui.composer.MosaicSupportStatus
import com.nodeseek.app.ui.composer.TopicComposerUiEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class R13ImagePrivacyAndGalleryE2eTest {

    @get:Rule
    val mainDispatcherRule = E2eMainDispatcherRule()

    private val validKey = "0123456789abcdef0123456789abcdef"

    private fun animatedGifBytes(): ByteArray =
        "GIF89a\u0001\u0000\u0001\u0000\u0000\u0000\u0000;".toByteArray(Charsets.ISO_8859_1)

    private fun apngBytes(): ByteArray {
        val base = E2eBitmapAndTlsFixtures.minimalPngBytes()
        // Insert an acTL chunk right after IHDR (offset 8 + 25 = 33)
        val actlData = byteArrayOf(0, 0, 0, 2, 0, 0, 0, 0)
        val out = ByteArrayOutputStream()
        out.write(base, 0, 33)
        out.write(byteArrayOf(0, 0, 0, 8))
        out.write("acTL".toByteArray(Charsets.US_ASCII))
        out.write(actlData)
        out.write(byteArrayOf(0, 0, 0, 0)) // dummy CRC (inspector checks chunk headers)
        out.write(base, 33, base.size - 33)
        return out.toByteArray()
    }

    private fun animatedWebpBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(22, 0, 0, 0)) // size
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write("VP8X".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(10, 0, 0, 0)) // VP8X chunk size
        out.write(byteArrayOf(0x02, 0, 0, 0)) // animation flag 0x02
        out.write(byteArrayOf(0, 0, 0, 0, 0, 0)) // canvas w/h
        return out.toByteArray()
    }

    private fun svgBytes(): ByteArray =
        """<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16"><rect width="16" height="16"/></svg>"""
            .toByteArray(Charsets.UTF_8)

    private fun heicHeaderBytes(): ByteArray =
        byteArrayOf(0, 0, 0, 24) +
            "ftypheic".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 0, 0) +
            "mif1heic".toByteArray(Charsets.US_ASCII)

    private fun avifHeaderBytes(): ByteArray =
        byteArrayOf(0, 0, 0, 24) +
            "ftypavif".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 0, 0) +
            "mif1avif".toByteArray(Charsets.US_ASCII)

    @Test
    fun `E2E-T1-R13-1 BitmapMosaicProcessor performs grid-aligned pixel wipe with lossless Undo and Reset`() {
        val width = 16
        val height = 16
        val source = IntArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            val r = (x * 16) and 0xFF
            val g = (y * 16) and 0xFF
            val b = ((x + y) * 8) and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        // Box covering pixels x in 0..3, y in 0..3 (normalized 0.0..0.25) with 4x4 grid blocks (blockSizeNorm=0.25 -> 4px)
        val boxEdit = MosaicEdit.BoxRegion(
            rect = MosaicRect(leftNorm = 0.02f, topNorm = 0.02f, rightNorm = 0.24f, bottomNorm = 0.24f),
            blockSizeNorm = 0.25f,
        )
        val strokeEdit = MosaicEdit.BrushStroke(
            points = listOf(MosaicPoint(0.55f, 0.55f), MosaicPoint(0.60f, 0.60f)),
            radiusNorm = 0.0625f, // 1px radius
            blockSizeNorm = 0.25f,
        )

        val singleEdit = BitmapMosaicProcessor.applyMosaicEdits(source, width, height, listOf(boxEdit))
        val twoEdits = BitmapMosaicProcessor.applyMosaicEdits(source, width, height, listOf(boxEdit, strokeEdit))

        // Verify exact ARGB average for top-left 4x4 cell (0..3, 0..3)
        var sumA = 0L
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                val p = source[y * width + x]
                sumA += (p ushr 24) and 0xFF
                sumR += (p ushr 16) and 0xFF
                sumG += (p ushr 8) and 0xFF
                sumB += p and 0xFF
            }
        }
        val expectedBlockColor = ((sumA / 16).toInt() shl 24) or
            ((sumR / 16).toInt() shl 16) or
            ((sumG / 16).toInt() shl 8) or
            (sumB / 16).toInt()

        for (y in 0 until 4) {
            for (x in 0 until 4) {
                assertEquals(expectedBlockColor, singleEdit[y * width + x])
                assertEquals(expectedBlockColor, twoEdits[y * width + x])
            }
        }
        // Distinct original values inside the cell were erased into a single uniform value
        assertNotEquals(source[0], singleEdit[0])
        assertNotEquals(source[3 * width + 3], singleEdit[3 * width + 3])

        // Untouched corner cell (12..15, 12..15) remains bit-identical
        for (y in 12 until 16) {
            for (x in 12 until 16) {
                assertEquals(source[y * width + x], singleEdit[y * width + x])
                assertEquals(source[y * width + x], twoEdits[y * width + x])
            }
        }

        // BrushStroke modified pixels near (8..11, 8..11)
        assertFalse(singleEdit.contentEquals(twoEdits))

        // Lossless Undo (dropLast(1)) matches singleEdit bit-for-bit; Reset (emptyList) matches source bit-for-bit
        val afterUndo = BitmapMosaicProcessor.applyMosaicEdits(source, width, height, listOf(boxEdit, strokeEdit).dropLast(1))
        assertArrayEquals(singleEdit, afterUndo)

        val afterReset = BitmapMosaicProcessor.applyMosaicEdits(source, width, height, emptyList())
        assertArrayEquals(source, afterReset)
    }

    @Test
    fun `E2E-T1-R13-2 TopicComposerViewModel enforces pre-upload privacy gate from missing API key through confirm skip and dismiss`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101")
            val nodeImageJson = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeimage/nodeimage_responses.json"),
            ).jsonObject
            val uploadSuccess = nodeImageJson.getValue("uploadSuccess").toString()

            val receivedUploads = CopyOnWriteArrayList<ByteArray>()
            harness.externalDispatcher.register("POST", "/api/upload") { req ->
                receivedUploads.add(req.body.readByteArray())
                MockResponse().setResponseCode(200).setBody(uploadSuccess)
            }

            val vm = harness.createTopicComposerViewModel(initialCategorySlug = "tech")
            val events = CopyOnWriteArrayList<TopicComposerUiEvent>()
            val stateJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.uiState.collect {} }
            val eventJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) {
                vm.events.collect { events.add(it) }
            }

            val originalPng = E2eBitmapAndTlsFixtures.solidRgbaPngBytes(12, 12, 0xFF112233.toInt())
            val redactedPng = E2eBitmapAndTlsFixtures.solidRgbaPngBytes(12, 12, 0xFF445566.toInt())

            // 1. Missing API key opens API key dialog without opening privacy editor or hitting network
            vm.onPhotoBytesSelected(originalPng, "image/png", "mtr_secret.png")
            val dialogState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.showApiKeyDialog } }
            }
            assertFalse(dialogState.showPrivacyEditorSheet)
            assertTrue(harness.externalDispatcher.recordedRequests().isEmpty())

            // 2. Save API key -> persists key, closes API key dialog, opens privacy editor sheet (never skips!)
            vm.saveApiKeyAndUpload(validKey)
            val privacyState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    vm.uiState.first {
                        !it.showApiKeyDialog &&
                            it.showPrivacyEditorSheet &&
                            it.settings.nodeImageApiKey == validKey
                    }
                }
            }
            assertEquals("mtr_secret.png", privacyState.pendingPrivacyImage?.fileName)
            assertTrue(harness.externalDispatcher.recordedRequests().isEmpty())

            // 3. Confirm privacy-redacted upload -> uploads redacted bytes with X-API-Key & zero cookies
            vm.confirmPrivacyImageUpload(redactedPng, "image/png", "mtr_secret.png")
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (events.none { it is TopicComposerUiEvent.InsertUploadedMarkdown }) {
                        kotlinx.coroutines.yield()
                    }
                }
            }
            val inserted = events.filterIsInstance<TopicComposerUiEvent.InsertUploadedMarkdown>().single()
            assertEquals("\n![image](https://img.nodeimage.com/i/img_e2e_01.png)\n", inserted.snippet)
            assertEquals(1, receivedUploads.size)
            assertTrue(receivedUploads[0].toString(Charsets.ISO_8859_1).contains("IHDR"))
            assertEquals(validKey, harness.externalDispatcher.recordedRequests().single().getHeader("X-API-Key"))

            // 4. Dismiss cancels without uploading
            vm.onPhotoBytesSelected(originalPng, "image/png", "cancel.png")
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.showPrivacyEditorSheet } }
            }
            vm.dismissPrivacyEditor()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { !it.showPrivacyEditorSheet } }
            }
            assertEquals(1, harness.externalDispatcher.recordedRequests().size)

            // 5. Skip uploads original bytes directly
            vm.onPhotoBytesSelected(originalPng, "image/png", "skip.png")
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { vm.uiState.first { it.showPrivacyEditorSheet } }
            }
            vm.skipPrivacyEditAndUpload()
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    while (harness.externalDispatcher.recordedRequests().size < 2) {
                        kotlinx.coroutines.yield()
                    }
                }
            }
            assertEquals(2, harness.externalDispatcher.recordedRequests().size)
            harness.assertNoCookiesLeakedToExternalServer()
            stateJob.cancel()
            eventJob.cancel()
        }
    }

    @Test
    fun `E2E-T1-R13-3 TopicDetailViewModel reply photo privacy gate and animated or vector bypass preserve raw assets`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, username = "alice101")
            harness.forumDispatcher.register("GET", "/post-123-1") {
                MockResponse().setResponseCode(200).setBody(E2eFixtures.loadText("nodeseek/topic_trade_op_123.html"))
            }
            val nodeImageJson = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeimage/nodeimage_responses.json"),
            ).jsonObject
            val uploadSuccess = nodeImageJson.getValue("uploadSuccess").toString()
            harness.externalDispatcher.register("POST", "/api/upload") {
                MockResponse().setResponseCode(200).setBody(uploadSuccess)
            }

            val detailVm = harness.createTopicDetailViewModel(topicId = 123L)
            val detailJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { detailVm.uiState.collect {} }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { detailVm.uiState.first { it.pageData != null } }
            }

            val staticPng = E2eBitmapAndTlsFixtures.solidRgbaPngBytes(8, 8)
            detailVm.onPhotoSelectedForReply(staticPng, "image/png", "reply.png")
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { detailVm.uiState.first { it.replyComposer.showApiKeyDialog } }
            }
            detailVm.saveNodeImageApiKeyAndContinue(validKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    detailVm.uiState.first {
                        !it.replyComposer.showApiKeyDialog &&
                            it.replyComposer.pendingPrivacyImage != null &&
                            it.settings.nodeImageApiKey == validKey
                    }
                }
            }
            detailVm.confirmReplyPrivacyImageUpload(staticPng, "image/png", "reply.png")
            val updatedDraftState = withContext(Dispatchers.Default) {
                withTimeout(5_000L) {
                    detailVm.uiState.first {
                        !it.replyComposer.isUploadingImage &&
                            it.replyComposer.draftMarkdown.contains("![image](https://img.nodeimage.com/i/img_e2e_01.png)")
                    }
                }
            }
            assertTrue(updatedDraftState.replyComposer.draftMarkdown.contains("https://img.nodeimage.com/i/img_e2e_01.png"))

            // Verify animated GIF, APNG, animated WEBP, and SVG bypass privacy editor cleanly
            val bypassCases = listOf(
                Triple(animatedGifBytes(), "image/gif", "anim.gif") to DetectedImageFormat.GIF,
                Triple(apngBytes(), "image/png", "anim.png") to DetectedImageFormat.PNG,
                Triple(animatedWebpBytes(), "image/webp", "anim.webp") to DetectedImageFormat.WEBP,
                Triple(svgBytes(), "image/svg+xml", "vector.svg") to DetectedImageFormat.SVG,
            )
            val composerVm = harness.createTopicComposerViewModel(initialCategorySlug = "tech")
            val composerJob = harness.backgroundScope.launch(mainDispatcherRule.testDispatcher) { composerVm.uiState.collect {} }
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { composerVm.uiState.first { it.settings.nodeImageApiKey == validKey } }
            }

            val initialReqCount = harness.externalDispatcher.recordedRequests().size
            bypassCases.forEachIndexed { idx, (input, expectedFmt) ->
                val (bytes, mime, name) = input
                val support = ImagePrivacyBitmapCodec.inspectMosaicSupport(bytes, mime, name)
                assertTrue("Expected UnsupportedAnimatedOrVector for $name, got $support", support is MosaicSupportStatus.UnsupportedAnimatedOrVector)
                assertEquals(expectedFmt, (support as MosaicSupportStatus.UnsupportedAnimatedOrVector).format)

                composerVm.onPhotoBytesSelected(bytes, mime, name)
                assertFalse(composerVm.uiState.value.showPrivacyEditorSheet)
                withContext(Dispatchers.Default) {
                    withTimeout(5_000L) {
                        while (harness.externalDispatcher.recordedRequests().size < initialReqCount + idx + 1) {
                            kotlinx.coroutines.yield()
                        }
                    }
                }
            }
            harness.assertNoCookiesLeakedToExternalServer()
            detailJob.cancel()
            composerJob.cancel()
        }
    }

    @Test
    fun `E2E-T1-R13-4 NodeImage gallery listing parses pagination nested data list alias top-level array and protocol normalization`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, sessionCookie = "r13_gallery_sess")
            harness.settingsRepository.setNodeImageApiKey(validKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { harness.settingsRepository.settings.first { it.nodeImageApiKey == validKey } }
            }

            val nodeImageJson = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeimage/nodeimage_responses.json"),
            ).jsonObject

            harness.externalDispatcher.enqueueNext(
                "GET",
                "/api/v1/images",
                MockResponse().setResponseCode(200).setBody(nodeImageJson.getValue("imagesPage1NestedData").toString()),
            )
            harness.externalDispatcher.enqueueNext(
                "GET",
                "/api/v1/images",
                MockResponse().setResponseCode(200).setBody(nodeImageJson.getValue("imagesPage2NestedData").toString()),
            )
            harness.externalDispatcher.enqueueNext(
                "GET",
                "/api/v1/images",
                MockResponse().setResponseCode(200).setBody(nodeImageJson.getValue("imagesListAlias").toString()),
            )
            harness.externalDispatcher.enqueueNext(
                "GET",
                "/api/v1/images",
                MockResponse().setResponseCode(200).setBody(nodeImageJson.getValue("imagesTopLevelArray").toString()),
            )

            val page1 = harness.imageHostRepository.listImages(page = 1, limit = 2).getOrThrow()
            assertEquals(1, page1.page)
            assertEquals(3, page1.totalCount)
            assertTrue(page1.hasMore)
            assertEquals(2, page1.items.size)
            assertEquals("https://img.nodeimage.com/i/img_page1_01.png", page1.items[0].url)
            assertTrue(page1.items[0].thumbnailUrl.endsWith("/thumb/img_page1_01.png"))
            assertEquals("https://img.nodeimage.com/i/img_page1_02.jpg", page1.items[1].url)
            assertEquals(24512L, page1.items[0].sizeBytes)

            val page2 = harness.imageHostRepository.listImages(page = 2, limit = 2).getOrThrow()
            assertEquals(2, page2.page)
            assertEquals(3, page2.totalCount)
            assertFalse(page2.hasMore)
            assertEquals("img_page2_03", page2.items.single().imageId)

            val aliasPage = harness.imageHostRepository.listImages(page = 1, limit = 10).getOrThrow()
            assertEquals("img_alias_03", aliasPage.items.single().imageId)
            assertEquals("mtr_route.jpg", aliasPage.items.single().fileName)
            assertEquals("https://img.nodeimage.com/i/mtr_route.jpg", aliasPage.items.single().url)

            val arrayPage = harness.imageHostRepository.listImages(page = 1, limit = 10).getOrThrow()
            assertEquals("img_arr_04", arrayPage.items.single().imageId)
            assertEquals("ping_loss.png", arrayPage.items.single().fileName)
            assertEquals(9216L, arrayPage.items.single().sizeBytes)

            val reqs = harness.externalDispatcher.recordedRequests()
            assertEquals(4, reqs.size)
            assertTrue(reqs.all { it.getHeader("X-API-Key") == validKey })
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T1-R13-5 NodeImage gallery deletion executes DELETE with X-API-Key and updates subsequent listImages`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, sessionCookie = "r13_del_sess")
            harness.settingsRepository.setNodeImageApiKey(validKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { harness.settingsRepository.settings.first { it.nodeImageApiKey == validKey } }
            }

            val nodeImageJson = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeimage/nodeimage_responses.json"),
            ).jsonObject
            harness.externalDispatcher.register("DELETE", "/api/v1/delete/img_e2e_01") {
                MockResponse().setResponseCode(200).setBody(nodeImageJson.getValue("deleteSuccess").toString())
            }
            harness.externalDispatcher.register("GET", "/api/v1/images") {
                MockResponse().setResponseCode(200).setBody(nodeImageJson.getValue("imagesPage2NestedData").toString())
            }

            val delRes = harness.imageHostRepository.deleteImage("img_e2e_01")
            assertTrue(delRes.isSuccess)
            val refreshed = harness.imageHostRepository.listImages(page = 2, limit = 2).getOrThrow()
            assertTrue(refreshed.items.none { it.imageId == "img_e2e_01" })

            val reqs = harness.externalDispatcher.recordedRequests()
            assertEquals(2, reqs.size)
            val delReq = reqs[0]
            assertEquals("DELETE", delReq.method)
            assertEquals("/api/v1/delete/img_e2e_01", delReq.path)
            assertEquals(validKey, delReq.getHeader("X-API-Key"))
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T2-R13-1 BitmapMosaicProcessor handles degenerate dimensions and NaN or Infinity floats safely`() {
        // 1x1, 1x10, 10x1, 0x0, short buffer
        val singlePx = intArrayOf(0xFF112233.toInt())
        val out1x1 = BitmapMosaicProcessor.applyMosaicEdits(
            singlePx,
            1,
            1,
            listOf(MosaicEdit.BoxRegion(MosaicRect(0f, 0f, 1f, 1f), blockSizeNorm = 0.5f)),
        )
        assertArrayEquals(singlePx, out1x1)

        val row1x10 = IntArray(10) { i -> (0xFF shl 24) or (i * 20) }
        val out1x10 = BitmapMosaicProcessor.applyMosaicEdits(
            row1x10,
            10,
            1,
            listOf(MosaicEdit.BoxRegion(MosaicRect(0f, 0f, 1f, 1f), blockSizeNorm = 0.5f)),
        )
        assertEquals(10, out1x10.size)

        val col10x1 = IntArray(10) { i -> (0xFF shl 24) or (i * 20) }
        val out10x1 = BitmapMosaicProcessor.applyMosaicEdits(
            col10x1,
            1,
            10,
            listOf(MosaicEdit.BrushStroke(listOf(MosaicPoint(0f, 0f), MosaicPoint(0f, 1f)), radiusNorm = 0.2f)),
        )
        assertEquals(10, out10x1.size)

        val emptyOut = BitmapMosaicProcessor.applyMosaicEdits(IntArray(0), 0, 0, listOf(MosaicEdit.BoxRegion(MosaicRect(0f, 0f, 1f, 1f))))
        assertEquals(0, emptyOut.size)

        val shortBuf = intArrayOf(1, 2, 3)
        assertArrayEquals(shortBuf, BitmapMosaicProcessor.applyMosaicEdits(shortBuf, 4, 4, listOf(MosaicEdit.BoxRegion(MosaicRect(0f, 0f, 1f, 1f)))))

        // NaN / +/-Infinity floats are sanitized or skipped without crashing or corrupting valid edits
        val grid = IntArray(64) { i -> (0xFF shl 24) or ((i * 3) shl 16) or 0x2244 }
        val adversarialEdits = listOf(
            MosaicEdit.BoxRegion(MosaicRect(Float.NaN, 0f, 1f, 1f), blockSizeNorm = Float.POSITIVE_INFINITY),
            MosaicEdit.BrushStroke(
                points = listOf(MosaicPoint(Float.NEGATIVE_INFINITY, Float.NaN)),
                radiusNorm = Float.NaN,
                blockSizeNorm = Float.NEGATIVE_INFINITY,
            ),
            MosaicEdit.BoxRegion(MosaicRect(0f, 0f, 0.5f, 0.5f), blockSizeNorm = Float.NaN),
        )
        val sanitized = BitmapMosaicProcessor.applyMosaicEdits(grid, 8, 8, adversarialEdits)
        assertEquals(64, sanitized.size)
        // Valid 3rd edit still applied despite NaN blockSizeNorm falling back to DEFAULT_BLOCK_SIZE_NORM
        assertEquals(sanitized[0], sanitized[1])
    }

    @Test
    fun `E2E-T2-R13-2 API key format validation short-circuits locally and 401 403 or 200 success=false surface clean errors`() = runBlocking {
        E2eTestHarness().use { harness ->
            val badKeys = listOf("", "   ", "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz", "0123456789abcdef", "a".repeat(129), "0123456789abcdef\n0123456789abcdef")
            for (badKey in badKeys) {
                assertTrue(harness.imageHostRepository.verifyApiKey(badKey).isFailure)
                harness.settingsRepository.setNodeImageApiKey(badKey)
                withContext(Dispatchers.Default) {
                    withTimeout(5_000L) { harness.settingsRepository.settings.first { it.nodeImageApiKey == badKey.trim() || it.nodeImageApiKey == badKey } }
                }
                assertTrue(harness.imageHostRepository.listImages().isFailure)
                assertTrue(harness.imageHostRepository.deleteImage("img_1").isFailure)
                assertTrue(harness.imageHostRepository.uploadImage(E2eFixtures.minimalPngBytes(), "image/png", fileName = "a.png").isFailure)
            }
            assertTrue(harness.externalDispatcher.recordedRequests().isEmpty())

            // Valid key -> HTTP 401 / 403 / 200 success=false
            harness.settingsRepository.setNodeImageApiKey(validKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { harness.settingsRepository.settings.first { it.nodeImageApiKey == validKey } }
            }
            val nodeImageJson = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeimage/nodeimage_responses.json"),
            ).jsonObject
            harness.externalDispatcher.enqueueNext("GET", "/api/v1/images", MockResponse().setResponseCode(401).setBody(nodeImageJson.getValue("unauthorized").toString()))
            harness.externalDispatcher.enqueueNext("GET", "/api/v1/images", MockResponse().setResponseCode(403).setBody("""{"success":false,"error":"Forbidden"}"""))
            harness.externalDispatcher.enqueueNext("GET", "/api/v1/images", MockResponse().setResponseCode(200).setBody("""{"success":false,"error":"Account suspended"}"""))

            val r401 = harness.imageHostRepository.listImages()
            val r403 = harness.imageHostRepository.listImages()
            val r200Fail = harness.imageHostRepository.listImages()
            assertTrue(r401.isFailure)
            assertTrue(r403.isFailure)
            assertTrue(r200Fail.isFailure)
            assertTrue(r200Fail.exceptionOrNull()?.message?.contains("Account suspended") == true)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T2-R13-3 deleteImage blocks path traversal and malformed IDs locally and propagates deleteFailure200`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.settingsRepository.setNodeImageApiKey(validKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { harness.settingsRepository.settings.first { it.nodeImageApiKey == validKey } }
            }

            val invalidIds = listOf("", "   ", "../etc/passwd", "img?x=1", "a/b", "bad id", "id#frag")
            for (badId in invalidIds) {
                val res = harness.imageHostRepository.deleteImage(badId)
                assertTrue("Expected local failure for imageId='$badId'", res.isFailure)
            }
            assertTrue(harness.externalDispatcher.recordedRequests().isEmpty())

            val nodeImageJson = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeimage/nodeimage_responses.json"),
            ).jsonObject
            harness.externalDispatcher.register("DELETE", "/api/v1/delete/img_ok-123") {
                MockResponse().setResponseCode(200).setBody(nodeImageJson.getValue("deleteFailure200").toString())
            }
            val delFailure = harness.imageHostRepository.deleteImage("img_ok-123")
            assertTrue(delFailure.isFailure)
            assertTrue(delFailure.exceptionOrNull()?.message?.contains("Image locked") == true)
            assertEquals(1, harness.externalDispatcher.recordedRequests().size)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T2-R13-4 NodeImage transport enforces HTTPS origin, blocks cross-origin and downgrade redirects, and enforces 307 or 308 for mutations`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.seedAuthenticatedSession(uid = 101L, sessionCookie = "r13_sec_cookie")

            // 1. Plaintext non-loopback origin blocked locally with 0 network calls
            val plaintextClient = NodeSeekApiClient(
                httpClient = harness.forumOkHttp,
                baseOrigin = harness.forumServer.url("/"),
                nodeImageOrigin = "http://api.nodeimage.com".toHttpUrl(),
            )
            val plaintextErr = runCatching { plaintextClient.verifyNodeImageApiKey(validKey) }.exceptionOrNull()
            assertNotNull(plaintextErr)
            assertTrue(harness.externalDispatcher.recordedRequests().isEmpty())

            // 2. Cross-origin 307 redirect blocked locally with 0 requests to evilServer
            val evilServer = harness.track(MockWebServer()).apply {
                enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))
                start()
            }
            harness.externalDispatcher.register("GET", "/api/v1/images") {
                MockResponse()
                    .setResponseCode(307)
                    .setHeader("Location", evilServer.url("/steal").toString())
            }
            val crossOriginErr = runCatching { harness.apiClient.listNodeImages(validKey) }.exceptionOrNull()
            assertNotNull(crossOriginErr)
            assertEquals(0, evilServer.requestCount)

            // 3. HTTPS-to-HTTP downgrade redirect blocked
            val tlsFixture = E2eBitmapAndTlsFixtures.createSelfSignedTlsFixture(
                commonName = "localhost",
                sanDnsNames = listOf("localhost"),
                sanIps = listOf("127.0.0.1"),
            )
            val loopCount = AtomicInteger(0)
            val redirectLoopMode = AtomicBoolean(false)
            val httpsServer = harness.track(MockWebServer()).apply {
                useHttps(tlsFixture.sslContext.socketFactory, false)
                dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        return when {
                            request.path == "/api/v1/images" && redirectLoopMode.get() -> {
                                val n = loopCount.getAndIncrement()
                                MockResponse()
                                    .setResponseCode(302)
                                    .setHeader("Location", "/loop-$n")
                            }
                            request.path == "/api/v1/images" -> MockResponse()
                                .setResponseCode(302)
                                .setHeader("Location", "http://127.0.0.1:${request.requestUrl?.port}/downgraded")
                            request.path?.startsWith("/loop-") == true -> {
                                loopCount.getAndIncrement()
                                val hop = request.path!!.removePrefix("/loop-").toIntOrNull() ?: 0
                                MockResponse()
                                    .setResponseCode(302)
                                    .setHeader("Location", "/loop-${hop + 1}")
                            }
                            else -> MockResponse().setResponseCode(200).setBody("""{"success":true,"images":[]}""")
                        }
                    }
                }
                start()
            }
            val httpsOkHttp = OkHttpClient.Builder()
                .sslSocketFactory(tlsFixture.sslContext.socketFactory, tlsFixture.trustManager)
                .build()
            val httpsApiClient = NodeSeekApiClient(
                httpClient = httpsOkHttp,
                baseOrigin = harness.forumServer.url("/"),
                nodeImageOrigin = "https://localhost:${httpsServer.port}/".toHttpUrl(),
            )
            val downgradeErr = runCatching { httpsApiClient.listNodeImages(validKey) }.exceptionOrNull()
            assertNotNull(downgradeErr)
            assertTrue(downgradeErr?.message?.contains("HTTPS") == true)

            // 4. >5 redirect hop loop blocked
            redirectLoopMode.set(true)
            val loopErr = runCatching { httpsApiClient.listNodeImages(validKey) }.exceptionOrNull()
            assertNotNull(loopErr)
            assertEquals(6, loopCount.get()) // initial + 5 hops -> fails on 6th redirect

            // 5. 302 on DELETE & POST rejected, while same-origin 302 on GET succeeds with zero forum cookies
            harness.externalDispatcher.register("DELETE", "/api/v1/delete/img_302") {
                MockResponse().setResponseCode(302).setHeader("Location", "/api/v1/delete/img_302_next")
            }
            harness.externalDispatcher.register("POST", "/api/upload") {
                MockResponse().setResponseCode(302).setHeader("Location", "/api/upload_next")
            }
            assertNotNull(runCatching { harness.apiClient.deleteNodeImage(validKey, "img_302") }.exceptionOrNull())
            assertNotNull(runCatching { harness.apiClient.uploadImageToNodeImage(E2eFixtures.minimalPngBytes(), validKey) }.exceptionOrNull())

            val nodeImageJson = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeimage/nodeimage_responses.json"),
            ).jsonObject
            harness.externalDispatcher.register("GET", "/api/v1/images") {
                MockResponse().setResponseCode(302).setHeader("Location", "/api/v1/images_v2")
            }
            harness.externalDispatcher.register("GET", "/api/v1/images_v2") {
                MockResponse().setResponseCode(200).setBody(nodeImageJson.getValue("imagesList").toString())
            }
            val getRedirected = harness.apiClient.listNodeImages(validKey)
            assertEquals(2, getRedirected.items.size)
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }

    @Test
    fun `E2E-T2-R13-5 NodeImageHostRepositoryImpl handles empty payload oversized animated GIF transcoder HEIC and AVIF fallback`() = runBlocking {
        E2eTestHarness().use { harness ->
            harness.settingsRepository.setNodeImageApiKey(validKey)
            withContext(Dispatchers.Default) {
                withTimeout(5_000L) { harness.settingsRepository.settings.first { it.nodeImageApiKey == validKey } }
            }

            // 1. Empty payload -> Unreadable & local upload failure
            assertTrue(ImagePrivacyBitmapCodec.inspectMosaicSupport(ByteArray(0), "image/png") is MosaicSupportStatus.Unreadable)
            assertTrue(harness.imageHostRepository.uploadImage(ByteArray(0), "image/png", fileName = "empty.png").isFailure)
            assertTrue(harness.externalDispatcher.recordedRequests().isEmpty())

            // 2. Oversized animated GIF (> maxAnimatedUploadBytes) fails locally without network call
            val smallLimitRepo = NodeImageHostRepositoryImpl(
                apiClientProvider = { harness.apiClient },
                settingsRepository = harness.settingsRepository,
                sessionRepository = harness.sessionRepository,
                imageTranscoder = object : ImageUploadTranscoder {
                    override fun transcodeToFit(
                        sourceBytes: ByteArray,
                        sourceFormat: DetectedImageFormat?,
                        maxBytes: Int,
                        maxDimensionPx: Int,
                    ): PreparedImageUpload? = null
                },
                maxStaticUploadBytes = 64,
                maxAnimatedUploadBytes = 10,
            )
            val oversizedGifRes = smallLimitRepo.uploadImage(animatedGifBytes(), "image/gif", fileName = "big.gif")
            assertTrue(oversizedGifRes.isFailure)
            assertTrue(harness.externalDispatcher.recordedRequests().isEmpty())

            // 3. Native AVIF fallback when transcoder returns null and payload <= maxStaticUploadBytes
            val nodeImageJson = TempScriptParser.json.parseToJsonElement(
                E2eFixtures.loadText("nodeimage/nodeimage_responses.json"),
            ).jsonObject
            val uploadSuccess = nodeImageJson.getValue("uploadSuccess").toString()
            val uploadedMultipartBodies = CopyOnWriteArrayList<String>()
            harness.externalDispatcher.register("POST", "/api/upload") { req ->
                uploadedMultipartBodies.add(req.body.readUtf8())
                MockResponse().setResponseCode(200).setBody(uploadSuccess)
            }
            val avifFallbackRes = smallLimitRepo.uploadImage(avifHeaderBytes(), "image/avif", fileName = "photo.avif")
            assertTrue(avifFallbackRes.isSuccess)
            assertTrue(uploadedMultipartBodies.single().contains("""filename="photo.avif""""))

            // 4. Non-native HEIC & oversized PNG routed through ImageUploadTranscoder
            val transcodedPng = E2eFixtures.minimalPngBytes()
            val transcodingRepo = NodeImageHostRepositoryImpl(
                apiClientProvider = { harness.apiClient },
                settingsRepository = harness.settingsRepository,
                sessionRepository = harness.sessionRepository,
                imageTranscoder = object : ImageUploadTranscoder {
                    override fun transcodeToFit(
                        sourceBytes: ByteArray,
                        sourceFormat: DetectedImageFormat?,
                        maxBytes: Int,
                        maxDimensionPx: Int,
                    ): PreparedImageUpload = PreparedImageUpload(
                        bytes = transcodedPng,
                        mimeType = "image/jpeg",
                        fileName = "converted.jpg",
                    )
                },
                maxStaticUploadBytes = 32,
            )
            val heicRes = transcodingRepo.uploadImage(heicHeaderBytes(), "image/heic", fileName = "shot.HEIC")
            assertTrue(heicRes.isSuccess)
            assertTrue(uploadedMultipartBodies.last().contains("""filename="shot.jpg""""))

            val bigPng = E2eBitmapAndTlsFixtures.solidRgbaPngBytes(24, 24)
            assertTrue(bigPng.size > 32)
            val oversizedPngRes = transcodingRepo.uploadImage(bigPng, "image/png", fileName = "large.png")
            assertTrue(oversizedPngRes.isSuccess)
            assertTrue(uploadedMultipartBodies.last().contains("""filename="large.jpg""""))
            harness.assertNoCookiesLeakedToExternalServer()
        }
    }
}
