package com.nodeseek.app.ui.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ImageUploadPayloadReaderTest {

    @Test
    fun `readBoundedBytes caps stream at limit and flags oversized payloads`() {
        val within = ByteArray(1024) { 7 }
        val readOk = readBoundedBytes(ByteArrayInputStream(within), maxBytes = 1024)
        assertFalse(isOversizedReadResult(readOk))
        assertEquals(1024, readOk.size)

        val over = ByteArray(1025) { 9 }
        val readOver = readBoundedBytes(ByteArrayInputStream(over), maxBytes = 1024)
        assertTrue(isOversizedReadResult(readOver))
    }

    @Test
    fun `detectImageMimeType identifies magic headers and falls back cleanly`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00)
        assertEquals("image/png", detectImageMimeType(png, null, "photo.bin"))

        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals("image/jpeg", detectImageMimeType(jpeg, null, null))

        val gif = "GIF89a1234".toByteArray(Charsets.US_ASCII)
        assertEquals("image/gif", detectImageMimeType(gif, null, null))

        val webp = "RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray(Charsets.US_ASCII)
        assertEquals("image/webp", detectImageMimeType(webp, null, null))

        val avif = "\u0000\u0000\u0000\u0018ftypavif".toByteArray(Charsets.US_ASCII)
        assertEquals("image/avif", detectImageMimeType(avif, null, null))

        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".toByteArray(Charsets.UTF_8)
        assertEquals("image/svg+xml", detectImageMimeType(svg, null, null))

        val unknown = byteArrayOf(1, 2, 3, 4)
        assertEquals("image/webp", detectImageMimeType(unknown, "image/webp", "pic.bin"))
        assertEquals("image/png", detectImageMimeType(unknown, "image/*", "shot.PNG"))
        assertEquals("image/jpeg", detectImageMimeType(unknown, null, null))
    }
}
