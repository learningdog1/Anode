package com.nodeseek.app.domain.image

import com.nodeseek.app.data.repository.DetectedImageFormat
import com.nodeseek.app.ui.composer.ImagePrivacyBitmapCodec
import com.nodeseek.app.ui.composer.MosaicSupportStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class BitmapMosaicProcessorTest {

    @Test
    fun `applyMosaicRectPx replaces touched grid blocks with exact block average and leaves outside pixels identical`() {
        val width = 8
        val height = 8
        // Fill grid with distinct colors per pixel
        val source = IntArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            (0xFF shl 24) or ((x * 20 + 10) shl 16) or ((y * 20 + 10) shl 8) or ((x + y) * 10)
        }
        val pixels = source.copyOf()

        // Rect [1, 1]..[3, 3] with blockSizePx = 4 touches only top-left block [0..4) x [0..4)
        BitmapMosaicProcessor.applyMosaicRectPx(
            pixels = pixels,
            width = width,
            height = height,
            left = 1,
            top = 1,
            right = 3,
            bottom = 3,
            blockSizePx = 4,
        )

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                val c = source[y * width + x]
                sumR += (c ushr 16) and 0xFF
                sumG += (c ushr 8) and 0xFF
                sumB += c and 0xFF
            }
        }
        val expectedBlockColor = (0xFF shl 24) or
            (((sumR / 16).toInt() and 0xFF) shl 16) or
            (((sumG / 16).toInt() and 0xFF) shl 8) or
            ((sumB / 16).toInt() and 0xFF)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (x < 4 && y < 4) {
                    assertEquals("Pixel ($x,$y) inside touched block", expectedBlockColor, pixels[idx])
                } else {
                    assertEquals("Pixel ($x,$y) outside touched block must remain untouched", source[idx], pixels[idx])
                }
            }
        }
    }

    @Test
    fun `applyMosaicStrokePx interpolates across fast diagonal strokes and clamps safely at boundaries`() {
        val width = 24
        val height = 24
        val source = IntArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            (0xFF shl 24) or ((x * 9) shl 16) or ((y * 9) shl 8) or 0x40
        }
        val pixels = source.copyOf()

        // Fast stroke from out-of-bounds (-5,-5) to (28,28) should interpolate every block along diagonal
        BitmapMosaicProcessor.applyMosaicStrokePx(
            pixels = pixels,
            width = width,
            height = height,
            pointsPx = listOf(-5f to -5f, 28f to 28f),
            radiusPx = 2.5f,
            blockSizePx = 4,
        )

        // Blocks along the main diagonal (0,0), (4,4), (8,8), (12,12), (16,16), (20,20) must all be uniform
        for (diag in listOf(0, 4, 8, 12, 16, 20)) {
            val topLeft = pixels[diag * width + diag]
            for (dy in 0 until 4) {
                for (dx in 0 until 4) {
                    assertEquals(
                        "Diagonal block at ($diag,$diag) must be uniformly pixelated",
                        topLeft,
                        pixels[(diag + dy) * width + (diag + dx)],
                    )
                }
            }
        }

        // Far corner (20, 0) untouched by diagonal stroke
        assertEquals(source[0 * width + 20], pixels[0 * width + 20])
        assertNotEquals(source[0 * width + 23], pixels[0 * width + 20])
    }

    @Test
    fun `applyMosaicEdits supports lossless undo and reset determinism from sourcePixels`() {
        val width = 16
        val height = 16
        val source = IntArray(width * height) { idx ->
            (0xFF shl 24) or (((idx * 17) and 0xFF) shl 16) or (((idx * 31) and 0xFF) shl 8) or ((idx * 7) and 0xFF)
        }
        val edit1 = MosaicEdit.BoxRegion(
            rect = MosaicRect(0.05f, 0.05f, 0.40f, 0.40f),
            blockSizeNorm = 0.25f,
        )
        val edit2 = MosaicEdit.BrushStroke(
            points = listOf(MosaicPoint(0.6f, 0.6f), MosaicPoint(0.9f, 0.9f)),
            radiusNorm = 0.15f,
            blockSizeNorm = 0.25f,
        )

        val afterOne = BitmapMosaicProcessor.applyMosaicEdits(source, width, height, listOf(edit1))
        val afterTwo = BitmapMosaicProcessor.applyMosaicEdits(source, width, height, listOf(edit1, edit2))
        val afterUndo = BitmapMosaicProcessor.applyMosaicEdits(source, width, height, listOf(edit1, edit2).dropLast(1))
        val afterReset = BitmapMosaicProcessor.applyMosaicEdits(source, width, height, emptyList())

        assertArrayEquals(afterOne, afterUndo)
        assertArrayEquals(source, afterReset)
        assertTrue(!afterOne.contentEquals(afterTwo))
    }

    @Test
    fun `inspectMosaicSupport separates static rasters from animated and SVG assets`() {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
        ) + ByteArray(17)
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)
        val gif = "GIF89a".toByteArray(Charsets.US_ASCII) + ByteArray(10)
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"></svg>""".toByteArray(Charsets.UTF_8)

        val apngOut = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            write(byteArrayOf(0, 0, 0, 13))
            write("IHDR".toByteArray(Charsets.US_ASCII))
            write(ByteArray(17))
            write(byteArrayOf(0, 0, 0, 8))
            write("acTL".toByteArray(Charsets.US_ASCII))
            write(ByteArray(12))
        }.toByteArray()

        val animWebp = "RIFF".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x30, 0x00, 0x00, 0x00) +
            "WEBPVP8X".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x0A, 0x00, 0x00, 0x00, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0)

        assertEquals(
            MosaicSupportStatus.SupportedStatic(DetectedImageFormat.PNG),
            ImagePrivacyBitmapCodec.inspectMosaicSupport(png, "image/png", "shot.png"),
        )
        assertEquals(
            MosaicSupportStatus.SupportedStatic(DetectedImageFormat.JPEG),
            ImagePrivacyBitmapCodec.inspectMosaicSupport(jpg, "image/jpeg", "photo.jpg"),
        )
        assertTrue(ImagePrivacyBitmapCodec.inspectMosaicSupport(gif, "image/gif", "anim.gif") is MosaicSupportStatus.UnsupportedAnimatedOrVector)
        assertTrue(ImagePrivacyBitmapCodec.inspectMosaicSupport(apngOut, "image/png", "anim.png") is MosaicSupportStatus.UnsupportedAnimatedOrVector)
        assertTrue(ImagePrivacyBitmapCodec.inspectMosaicSupport(animWebp, "image/webp", "anim.webp") is MosaicSupportStatus.UnsupportedAnimatedOrVector)
        assertTrue(ImagePrivacyBitmapCodec.inspectMosaicSupport(svg, "image/svg+xml", "icon.svg") is MosaicSupportStatus.UnsupportedAnimatedOrVector)
        assertTrue(ImagePrivacyBitmapCodec.inspectMosaicSupport(ByteArray(0), "image/png", "empty.png") is MosaicSupportStatus.Unreadable)
    }

    @Test
    fun `applyMosaicEdits handles 1x1, 1x10, and 10x1 edge dimensions safely with BrushStroke and BoxRegion`() {
        val edits = listOf(
            MosaicEdit.BoxRegion(rect = MosaicRect(0f, 0f, 1f, 1f)),
            MosaicEdit.BrushStroke(
                points = listOf(MosaicPoint(0f, 0f), MosaicPoint(0.5f, 0.5f), MosaicPoint(1f, 1f)),
                radiusNorm = 0.25f,
            ),
        )

        // 1x1 must not throw coerceIn(2, 1) and must return pixel copy unchanged
        val source1x1 = intArrayOf(0xFF112233.toInt())
        val out1x1 = BitmapMosaicProcessor.applyMosaicEdits(source1x1, 1, 1, edits)
        assertArrayEquals(source1x1, out1x1)

        // 1x10 vertical strip must pixelate along height without throwing
        val source1x10 = IntArray(10) { y -> (0xFF shl 24) or (y * 25 shl 16) or 0x4040 }
        val out1x10 = BitmapMosaicProcessor.applyMosaicEdits(source1x10, 1, 10, edits)
        assertEquals(10, out1x10.size)
        assertEquals(out1x10[0], out1x10[1])

        // 10x1 horizontal strip must pixelate along width without throwing
        val source10x1 = IntArray(10) { x -> (0xFF shl 24) or (x * 25 shl 8) or 0x2020 }
        val out10x1 = BitmapMosaicProcessor.applyMosaicEdits(source10x1, 10, 1, edits)
        assertEquals(10, out10x1.size)
        assertEquals(out10x1[0], out10x1[1])
    }

    @Test
    fun `applyMosaicEdits and applyMosaicStrokePx ignore or sanitize NaN and Infinity floats without crashing`() {
        val width = 12
        val height = 12
        val source = IntArray(width * height) { idx ->
            (0xFF shl 24) or ((idx * 13 and 0xFF) shl 16) or ((idx * 7 and 0xFF) shl 8) or (idx and 0xFF)
        }
        val nonFiniteValues = listOf(
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
        )

        for (bad in nonFiniteValues) {
            // Non-finite blockSizeNorm on valid BoxRegion falls back cleanly and pixelates
            val validBoxBadBlock = listOf(
                MosaicEdit.BoxRegion(
                    rect = MosaicRect(0f, 0f, 1f, 1f),
                    blockSizeNorm = bad,
                ),
            )
            val resBoxBadBlock = BitmapMosaicProcessor.applyMosaicEdits(source, width, height, validBoxBadBlock)
            assertEquals(source.size, resBoxBadBlock.size)
            assertEquals(resBoxBadBlock[0], resBoxBadBlock[1])

            // Non-finite coordinates in MosaicRect are skipped cleanly
            val badBoxes = listOf(
                MosaicEdit.BoxRegion(MosaicRect(bad, 0f, 1f, 1f), blockSizeNorm = bad),
                MosaicEdit.BoxRegion(MosaicRect(0f, bad, 1f, 1f)),
                MosaicEdit.BoxRegion(MosaicRect(0f, 0f, bad, 1f)),
                MosaicEdit.BoxRegion(MosaicRect(0f, 0f, 1f, bad)),
            )
            assertArrayEquals(source, BitmapMosaicProcessor.applyMosaicEdits(source, width, height, badBoxes))

            // Non-finite radiusNorm skips stroke cleanly
            val badRadiusStroke = listOf(
                MosaicEdit.BrushStroke(
                    points = listOf(MosaicPoint(0.2f, 0.2f), MosaicPoint(0.8f, 0.8f)),
                    radiusNorm = bad,
                    blockSizeNorm = bad,
                ),
            )
            assertArrayEquals(source, BitmapMosaicProcessor.applyMosaicEdits(source, width, height, badRadiusStroke))

            // Mixed finite + non-finite MosaicPoint keeps finite points and skips non-finite points
            val mixedStroke = listOf(
                MosaicEdit.BrushStroke(
                    points = listOf(
                        MosaicPoint(bad, 0.5f),
                        MosaicPoint(0.5f, bad),
                        MosaicPoint(0.25f, 0.25f),
                    ),
                    radiusNorm = 0.25f,
                    blockSizeNorm = bad,
                ),
            )
            val resMixed = BitmapMosaicProcessor.applyMosaicEdits(source, width, height, mixedStroke)
            assertEquals(source.size, resMixed.size)

            // Direct applyMosaicStrokePx with non-finite radius or points never throws
            val directPixels = source.copyOf()
            BitmapMosaicProcessor.applyMosaicStrokePx(
                pixels = directPixels,
                width = width,
                height = height,
                pointsPx = listOf(bad to 2f, 2f to bad, 4f to 4f),
                radiusPx = 2f,
                blockSizePx = 2,
            )
            BitmapMosaicProcessor.applyMosaicStrokePx(
                pixels = directPixels,
                width = width,
                height = height,
                pointsPx = listOf(1f to 1f, 8f to 8f),
                radiusPx = bad,
                blockSizePx = 2,
            )
        }
    }

    @Test
    fun `applyMosaicRectPx and applyMosaicStrokePx clamp Int_MAX_VALUE blockSizePx without integer overflow`() {
        val width = 8
        val height = 8
        val source = IntArray(width * height) { idx ->
            (0xFF shl 24) or ((idx * 17 and 0xFF) shl 16) or ((idx * 11 and 0xFF) shl 8) or 0x55
        }

        val rectPixels = source.copyOf()
        BitmapMosaicProcessor.applyMosaicRectPx(
            pixels = rectPixels,
            width = width,
            height = height,
            left = 0,
            top = 0,
            right = width,
            bottom = height,
            blockSizePx = Int.MAX_VALUE,
        )
        assertEquals(rectPixels[0], rectPixels[width * height - 1])

        val strokePixels = source.copyOf()
        BitmapMosaicProcessor.applyMosaicStrokePx(
            pixels = strokePixels,
            width = width,
            height = height,
            pointsPx = listOf(4f to 4f),
            radiusPx = 4f,
            blockSizePx = Int.MAX_VALUE,
        )
        assertEquals(strokePixels[0], strokePixels[width * height - 1])
    }
}
