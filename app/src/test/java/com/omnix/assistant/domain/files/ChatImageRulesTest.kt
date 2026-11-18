package com.omnix.assistant.domain.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImageRulesTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)
    private val webp = "RIFF".toByteArray() + byteArrayOf(1, 0, 0, 0) + "WEBPVP8 ".toByteArray()

    @Test
    fun `detects jpeg png and webp by content`() {
        assertEquals("image/jpeg", ChatImageRules.detectMime(jpeg))
        assertEquals("image/png", ChatImageRules.detectMime(png))
        assertEquals("image/webp", ChatImageRules.detectMime(webp))
    }

    @Test
    fun `other formats are not detected`() {
        assertNull(ChatImageRules.detectMime("GIF89a......".toByteArray()))
        assertNull(ChatImageRules.detectMime("%PDF-1.7......".toByteArray()))
        assertNull(ChatImageRules.detectMime("PK\u0003\u0004........".toByteArray()))
        assertNull(ChatImageRules.detectMime(byteArrayOf()))
        // RIFF без WEBP (например, WAV) не картинка.
        assertNull(ChatImageRules.detectMime("RIFF".toByteArray() + byteArrayOf(1, 0, 0, 0) + "WAVEfmt ".toByteArray()))
    }

    @Test
    fun `source validation`() {
        assertNull(ChatImageRules.validateSource("image/jpeg", 5_000_000))
        assertNull(ChatImageRules.validateSource("image/png", -1))
        assertEquals(ChatImageRules.Reject.UNSUPPORTED, ChatImageRules.validateSource(null, 10))
        assertEquals(
            ChatImageRules.Reject.TOO_LARGE,
            ChatImageRules.validateSource("image/jpeg", ChatImageRules.MAX_SOURCE_BYTES + 1)
        )
    }

    @Test
    fun `pixel validation`() {
        assertNull(ChatImageRules.validatePixels(4000, 3000))
        assertEquals(ChatImageRules.Reject.UNREADABLE, ChatImageRules.validatePixels(0, 100))
        assertEquals(ChatImageRules.Reject.UNREADABLE, ChatImageRules.validatePixels(100, -1))
        assertEquals(ChatImageRules.Reject.TOO_LARGE, ChatImageRules.validatePixels(20_000, 20_000))
    }

    @Test
    fun `long side is limited to 1536 keeping ratio`() {
        assertEquals(1536 to 1152, ChatImageRules.targetSize(4000, 3000))
        assertEquals(1152 to 1536, ChatImageRules.targetSize(3000, 4000))
        assertEquals(1536 to 1536, ChatImageRules.targetSize(5000, 5000))
        assertEquals(1536 to 864, ChatImageRules.targetSize(1920, 1080))
    }

    @Test
    fun `small images are not enlarged`() {
        assertEquals(800 to 600, ChatImageRules.targetSize(800, 600))
        assertEquals(1536 to 1000, ChatImageRules.targetSize(1536, 1000))
    }

    @Test
    fun `extreme aspect ratio keeps at least one pixel`() {
        val (w, h) = ChatImageRules.targetSize(100_000, 10)
        assertEquals(1536, w)
        assertTrue(h >= 1)
    }

    @Test
    fun `sample size is a power of two and keeps the long side above the limit`() {
        assertEquals(1, ChatImageRules.sampleSize(1600, 1200))
        assertEquals(1, ChatImageRules.sampleSize(3000, 2000))
        assertEquals(2, ChatImageRules.sampleSize(4000, 3000))
        assertEquals(4, ChatImageRules.sampleSize(8000, 6000))
        for (side in listOf(1000, 1536, 2500, 4032, 6000, 12000)) {
            val s = ChatImageRules.sampleSize(side, side / 2)
            assertTrue(s and (s - 1) == 0)
            assertTrue(side / s >= minOf(side, ChatImageRules.MAX_SIDE_PX))
        }
    }

    private fun jpegWithOrientation(value: Int, little: Boolean): ByteArray {
        fun u16(v: Int) = if (little) byteArrayOf(v.toByte(), (v shr 8).toByte()) else byteArrayOf((v shr 8).toByte(), v.toByte())
        fun u32(v: Int) = if (little) u16(v and 0xFFFF) + u16(v ushr 16) else u16(v ushr 16) + u16(v and 0xFFFF)
        val tiff = (if (little) "II".toByteArray() else "MM".toByteArray()) + u16(42) + u32(8) +
            u16(1) + u16(0x0112) + u16(3) + u32(1) + u16(value) + u16(0) + u32(0)
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) +
            payload + byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2)
    }

    @Test
    fun `exif orientation becomes rotation in both byte orders`() {
        assertEquals(90, ChatImageRules.exifRotation(jpegWithOrientation(6, little = true)))
        assertEquals(180, ChatImageRules.exifRotation(jpegWithOrientation(3, little = false)))
        assertEquals(270, ChatImageRules.exifRotation(jpegWithOrientation(8, little = true)))
        assertEquals(0, ChatImageRules.exifRotation(jpegWithOrientation(1, little = false)))
    }

    @Test
    fun `missing or broken exif means no rotation`() {
        assertEquals(0, ChatImageRules.exifRotation(jpeg))
        assertEquals(0, ChatImageRules.exifRotation(png))
        assertEquals(0, ChatImageRules.exifRotation(jpegWithOrientation(6, true).copyOf(12)))
    }

    @Test
    fun `quality starts at 80 and only goes down`() {
        assertEquals(80, ChatImageRules.JPEG_QUALITY)
        assertEquals(80, ChatImageRules.QUALITY_STEPS.first())
        assertTrue(ChatImageRules.QUALITY_STEPS.toList().zipWithNext().all { (a, b) -> a > b })
    }
}
