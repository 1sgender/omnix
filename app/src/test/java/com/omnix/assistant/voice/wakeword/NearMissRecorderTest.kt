package com.omnix.assistant.voice.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Near-miss захват (owner review 2026-09-26, п.1): WAV-формат, дебаунс,
 * кап хранилища, манифест без метаданных пользователя.
 */
class NearMissRecorderTest {

    @Test
    fun wavHeader_hasRiffPcm16MonoLayout() {
        val file = File.createTempFile("nm-test", ".wav").apply { deleteOnExit() }
        val samples = shortArrayOf(1, -1, 32767, -32768)
        val size = NearMissRecorder(0f).writeWav(file, samples, 16000)

        assertEquals(44L + 8L, size)
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + 8, buf.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals(16, buf.getInt(16))
        assertEquals(1, buf.getShort(20).toInt()) // PCM
        assertEquals(1, buf.getShort(22).toInt()) // моно
        assertEquals(16000, buf.getInt(24))
        assertEquals(32000, buf.getInt(28))
        assertEquals(16, buf.getShort(34).toInt())
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(8, buf.getInt(40))
        // PCM16 little-endian: сэмпл 0 на 44, сэмпл 3 (крайний int16) на 50.
        assertEquals(1, buf.getShort(44).toInt())
        assertEquals(32767, buf.getShort(48).toInt())
        assertEquals(-32768, buf.getShort(50).toInt())
    }

    @Test
    fun wavWithEmptySamples_hasZeroDataChunk() {
        val file = File.createTempFile("nm-empty", ".wav").apply { deleteOnExit() }
        assertEquals(44L, NearMissRecorder(0f).writeWav(file, ShortArray(0), 16000))
    }

    @Test
    fun decision_firedFramesAreNotCaptured() {
        val r = NearMissRecorder(0.25f)
        assertFalse(r.shouldCapture(0.9f, fired = true, nowMs = 0).capture)
    }

    @Test
    fun decision_belowMinScoreIsNotCaptured() {
        val r = NearMissRecorder(0.25f)
        assertFalse(r.shouldCapture(0.24f, fired = false, nowMs = 0).capture)
    }

    @Test
    fun decision_debouncesWithinInterval() {
        val r = NearMissRecorder(0.25f, minIntervalMs = 30_000)
        assertTrue(r.shouldCapture(0.3f, fired = false, nowMs = 0).capture)
        val second = r.shouldCapture(0.9f, fired = false, nowMs = 29_999)
        assertFalse("дебаунс 30 с", second.capture)
        assertEquals("debounce", second.reason)
        assertTrue(r.shouldCapture(0.9f, fired = false, nowMs = 30_000).capture)
    }

    @Test
    fun resetAllowsImmediateCapture() {
        val r = NearMissRecorder(0.25f, minIntervalMs = 30_000)
        assertTrue(r.shouldCapture(0.3f, fired = false, nowMs = 0).capture)
        r.reset()
        assertTrue(r.shouldCapture(0.3f, fired = false, nowMs = 1000).capture)
    }

    private fun tempDir(): File {
        return File.createTempFile("nm-cap", "dir").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
    }

    @Test
    fun capDeletionRemovesOldestFirst() {
        val dir = tempDir()
        // Имя = время: лексический порядок = хронологический.
        val oldest = File(dir, "nm-0000-score0.300.wav").apply { writeBytes(ByteArray(50)) }
        val middle = File(dir, "nm-1111-score0.350.wav").apply { writeBytes(ByteArray(50)) }
        val newest = File(dir, "nm-2222-score0.400.wav").apply { writeBytes(ByteArray(50)) }
        // Лимит 100 из 150 байт: уходит ровно самый старый (100 ≤ 100).
        val toDelete = NearMissRecorder(0f, maxTotalBytes = 100L)
            .filesToDelete(listOf(oldest, middle, newest))
        assertEquals(listOf(oldest), toDelete)
    }

    @Test
    fun capDeletionRemovesMultipleWhenFarOverLimit() {
        val dir = tempDir()
        val oldest = File(dir, "nm-0000-score0.300.wav").apply { writeBytes(ByteArray(50)) }
        val middle = File(dir, "nm-1111-score0.350.wav").apply { writeBytes(ByteArray(50)) }
        val newest = File(dir, "nm-2222-score0.400.wav").apply { writeBytes(ByteArray(50)) }
        // Лимит 60 из 150: уходят два самых старых (осталось 50 ≤ 60).
        val toDelete = NearMissRecorder(0f, maxTotalBytes = 60L)
            .filesToDelete(listOf(oldest, middle, newest))
        assertEquals(listOf(oldest, middle), toDelete)
    }

    @Test
    fun capDeletionIsNoOpUnderLimit() {
        val dir = tempDir()
        val f = File(dir, "nm-0000-score0.300.wav").apply { writeBytes(ByteArray(50)) }
        assertTrue(
            NearMissRecorder(0f, maxTotalBytes = 100L).filesToDelete(listOf(f)).isEmpty()
        )
    }

    @Test
    fun manifestLine_hasNoUserMetadata() {
        val line = NearMissRecorder(0f).manifestLine(
            epochMs = 1758864000000L,
            score = 0.31f,
            model = "omni_v0.1.onnx",
            threshold = 0.35f,
            snrDb = 6.2f
        )
        assertTrue(line.contains("\"epochMs\":1758864000000"))
        assertTrue(line.contains("\"score\":0.31"))
        assertTrue(line.contains("\"model\":\"omni_v0.1.onnx\""))
        assertTrue(line.contains("\"snrDb\":6.2"))
        val nullLine = NearMissRecorder(0f).manifestLine(1L, 0.3f, "m", 0.35f, null)
        assertTrue(nullLine.contains("\"snrDb\":null"))
    }
}
