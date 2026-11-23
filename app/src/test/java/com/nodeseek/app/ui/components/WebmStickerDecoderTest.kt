package com.nodeseek.app.ui.components

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class WebmStickerDecoderTest {

    @Test
    fun `WebmDemuxer parses VP9 video track with AlphaMode and BlockAdditions frames`() {
        val colorFrame0 = byteArrayOf(0x82.toByte(), 0x49, 0x83.toByte(), 0x42, 0x00)
        val alphaFrame0 = byteArrayOf(0x82.toByte(), 0x49, 0x83.toByte(), 0x42, 0x11)
        val colorFrame1 = byteArrayOf(0x86.toByte(), 0x01, 0x02)
        val alphaFrame1 = byteArrayOf(0x86.toByte(), 0x0A, 0x0B)

        val webmBytes = buildSyntheticWebm(
            width = 100,
            height = 100,
            codecId = "V_VP9",
            alphaMode = 1,
            defaultDurationNs = 83_333_333L,
            useUnknownSizes = false,
            clusterBody = concat(
                elemUint(0xE7, 0L), // Cluster Timestamp = 0
                // Frame 0 (keyframe: no ReferenceBlock)
                elem(
                    0xA0, // BlockGroup
                    concat(
                        elem(0xA1, buildBlock(trackNum = 1, relTs = 0, flags = 0x00, payload = colorFrame0)),
                        elem(
                            0x75A1, // BlockAdditions
                            elem(
                                0xA6, // BlockMore
                                concat(
                                    elemUint(0xEE, 1L), // BlockAddID = 1
                                    elem(0xA5, alphaFrame0), // BlockAdditional
                                ),
                            ),
                        ),
                    ),
                ),
                // Frame 1 (inter-frame: includes ReferenceBlock 0xFB and omitted BlockAddID defaulting to 1)
                elem(
                    0xA0, // BlockGroup
                    concat(
                        elem(0xA1, buildBlock(trackNum = 1, relTs = 83, flags = 0x00, payload = colorFrame1)),
                        elem(
                            0x75A1,
                            elem(
                                0xA6,
                                elem(0xA5, alphaFrame1),
                            ),
                        ),
                        elem(0xFB, byteArrayOf((-83).toByte())), // ReferenceBlock
                    ),
                ),
            ),
        )

        val parsed = WebmDemuxer.parse(webmBytes)
        assertNotNull(parsed)
        parsed!!
        assertEquals(100, parsed.width)
        assertEquals(100, parsed.height)
        assertEquals("V_VP9", parsed.codecId)
        assertTrue(parsed.hasAlpha)
        assertEquals(2, parsed.frames.size)

        assertEquals(0L, parsed.frames[0].ptsUs)
        assertEquals(83, parsed.frames[0].durationMs)
        assertTrue(parsed.frames[0].isKeyFrame)
        assertArrayEquals(colorFrame0, parsed.frames[0].colorPayload)
        assertArrayEquals(alphaFrame0, parsed.frames[0].alphaPayload)

        assertEquals(83_000L, parsed.frames[1].ptsUs)
        assertEquals(83, parsed.frames[1].durationMs)
        assertFalse(parsed.frames[1].isKeyFrame)
        assertArrayEquals(colorFrame1, parsed.frames[1].colorPayload)
        assertArrayEquals(alphaFrame1, parsed.frames[1].alphaPayload)
    }

    @Test
    fun `WebmDemuxer supports unknown-size Segment and Cluster with SimpleBlock frames`() {
        val frame0 = byteArrayOf(1, 2, 3, 4)
        val frame1 = byteArrayOf(5, 6, 7)

        val webmBytes = buildSyntheticWebm(
            width = 64,
            height = 64,
            codecId = "V_VP9",
            alphaMode = 0,
            defaultDurationNs = 50_000_000L,
            useUnknownSizes = true,
            clusterBody = concat(
                elemUint(0xE7, 100L),
                elem(0xA3, buildBlock(trackNum = 1, relTs = 0, flags = 0x80, payload = frame0)),
                elem(0xA3, buildBlock(trackNum = 1, relTs = 50, flags = 0x00, payload = frame1)),
            ),
        )

        val parsed = WebmDemuxer.parse(webmBytes)
        assertNotNull(parsed)
        parsed!!
        assertEquals(64, parsed.width)
        assertEquals(64, parsed.height)
        assertFalse(parsed.hasAlpha)
        assertEquals(2, parsed.frames.size)
        assertEquals(100_000L, parsed.frames[0].ptsUs)
        assertEquals(50, parsed.frames[0].durationMs)
        assertTrue(parsed.frames[0].isKeyFrame)
        assertEquals(150_000L, parsed.frames[1].ptsUs)
        assertEquals(50, parsed.frames[1].durationMs)
        assertFalse(parsed.frames[1].isKeyFrame)
        assertNull(parsed.frames[0].alphaPayload)
    }

    @Test
    fun `WebmDemuxer rejects non-EBML payloads and oversized non-sticker dimensions`() {
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
        assertNull(WebmDemuxer.parse(pngHeader))
        assertNull(WebmDemuxer.parse(byteArrayOf()))

        val hugeVideo = buildSyntheticWebm(
            width = 1920,
            height = 1080,
            codecId = "V_VP9",
            alphaMode = 0,
            defaultDurationNs = 33_333_333L,
            useUnknownSizes = false,
            clusterBody = concat(
                elemUint(0xE7, 0L),
                elem(0xA3, buildBlock(trackNum = 1, relTs = 0, flags = 0x80, payload = byteArrayOf(1, 2))),
            ),
        )
        assertNull(WebmDemuxer.parse(hugeVideo))
    }

    @Test
    fun `YuvAlphaConverter composites BT601 YUV420 and alpha with corner noise clamping and non-unit strides`() {
        // 2x2 image inside a 4x2 buffer (rowStride=4, cropLeft=1) with interleaved UV (pixelStride=2)
        val yBuf = ByteBuffer.wrap(
            byteArrayOf(
                0, 235.toByte(), 16, 0, // row 0: crop pixel (0,0)=white(Y=235), (1,0)=black(Y=16)
                0, 126, 235.toByte(), 0, // row 1: crop pixel (0,1)=mid-gray(Y=126), (1,1)=transparent corner
            ),
        )
        // Neutral chroma (U=128, V=128) stored with pixelStride=2
        val uBuf = ByteBuffer.wrap(byteArrayOf(128.toByte(), 0, 128.toByte(), 0))
        val vBuf = ByteBuffer.wrap(byteArrayOf(128.toByte(), 0, 128.toByte(), 0))
        val alpha = byteArrayOf(
            255.toByte(), // (0,0) fully opaque white
            128.toByte(), // (1,0) 50% transparent black
            200.toByte(), // (0,1) translucent mid-gray
            1, // (1,1) VP9 quantization noise <= 2 -> clamped to 0 (fully transparent)
        )

        val pixels = YuvAlphaConverter.yuv420ToArgb(
            yBuffer = yBuf,
            yRowStride = 4,
            yPixelStride = 1,
            uBuffer = uBuf,
            uRowStride = 4,
            uPixelStride = 2,
            vBuffer = vBuf,
            vRowStride = 4,
            vPixelStride = 2,
            cropLeft = 1,
            cropTop = 0,
            width = 2,
            height = 2,
            alphaPlane = alpha,
        )

        assertEquals(4, pixels.size)
        // (0,0): opaque white
        assertEquals(0xFFFFFFFF.toInt(), pixels[0])
        // (1,0): semi-transparent black (A=128, R=G=B=0)
        assertEquals(0x80000000.toInt(), pixels[1])
        // (0,1): alpha=200, mid-gray RGB around 128
        assertEquals(200, (pixels[2] ushr 24) and 0xFF)
        val r = (pixels[2] ushr 16) and 0xFF
        val g = (pixels[2] ushr 8) and 0xFF
        val b = pixels[2] and 0xFF
        assertTrue("Expected near-128 gray, got ($r,$g,$b)", r in 124..132 && g in 124..132 && b in 124..132)
        // (1,1): alpha=1 noise clamped cleanly to 0x00000000
        assertEquals(0, pixels[3])
    }

    // ── Synthetic EBML builder helpers ────────────────────────────────────────

    private fun buildSyntheticWebm(
        width: Int,
        height: Int,
        codecId: String,
        alphaMode: Int,
        defaultDurationNs: Long,
        useUnknownSizes: Boolean,
        clusterBody: ByteArray,
    ): ByteArray {
        val ebml = elem(
            0x1A45DFA3,
            elem(0x4282, "webm".toByteArray(Charsets.US_ASCII)),
        )
        val info = elem(
            0x1549A966,
            elemUint(0x2AD7B1, 1_000_000L),
        )
        val video = elem(
            0xE0,
            concat(
                elemUint(0xB0, width.toLong()),
                elemUint(0xBA, height.toLong()),
                if (alphaMode > 0) elemUint(0x53C0, alphaMode.toLong()) else ByteArray(0),
            ),
        )
        val trackEntry = elem(
            0xAE,
            concat(
                elemUint(0xD7, 1L),
                elemUint(0x83, 1L),
                elem(0x86, codecId.toByteArray(Charsets.US_ASCII)),
                elemUint(0x23E383, defaultDurationNs),
                video,
            ),
        )
        val tracks = elem(0x1654AE6B, trackEntry)
        val cluster = if (useUnknownSizes) {
            concat(encodeId(0x1F43B675), byteArrayOf(0xFF.toByte()), clusterBody)
        } else {
            elem(0x1F43B675, clusterBody)
        }
        val segmentBody = concat(info, tracks, cluster)
        val segment = if (useUnknownSizes) {
            concat(encodeId(0x18538067), byteArrayOf(0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), segmentBody)
        } else {
            elem(0x18538067, segmentBody)
        }
        return concat(ebml, segment)
    }

    private fun buildBlock(trackNum: Int, relTs: Int, flags: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x80 or trackNum) // 1-byte VINT track number
        out.write((relTs ushr 8) and 0xFF)
        out.write(relTs and 0xFF)
        out.write(flags and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    private fun elem(id: Long, body: ByteArray): ByteArray =
        concat(encodeId(id), encodeSize(body.size), body)

    private fun elemUint(id: Long, value: Long): ByteArray {
        val bytes = when {
            value <= 0xFFL -> byteArrayOf(value.toByte())
            value <= 0xFFFFL -> byteArrayOf((value ushr 8).toByte(), value.toByte())
            value <= 0xFFFFFFL -> byteArrayOf((value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
            else -> byteArrayOf(
                (value ushr 24).toByte(),
                (value ushr 16).toByte(),
                (value ushr 8).toByte(),
                value.toByte(),
            )
        }
        return elem(id, bytes)
    }

    private fun encodeId(id: Long): ByteArray = when {
        id <= 0xFFL -> byteArrayOf(id.toByte())
        id <= 0xFFFFL -> byteArrayOf((id ushr 8).toByte(), id.toByte())
        id <= 0xFFFFFFL -> byteArrayOf((id ushr 16).toByte(), (id ushr 8).toByte(), id.toByte())
        else -> byteArrayOf(
            (id ushr 24).toByte(),
            (id ushr 16).toByte(),
            (id ushr 8).toByte(),
            id.toByte(),
        )
    }

    private fun encodeSize(size: Int): ByteArray = when {
        size < 0x7F -> byteArrayOf((0x80 or size).toByte())
        size < 0x3FFF -> byteArrayOf((0x40 or (size ushr 8)).toByte(), (size and 0xFF).toByte())
        else -> byteArrayOf(
            (0x20 or (size ushr 16)).toByte(),
            ((size ushr 8) and 0xFF).toByte(),
            (size and 0xFF).toByte(),
        )
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) out.write(p)
        return out.toByteArray()
    }
}
