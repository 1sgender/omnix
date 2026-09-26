package com.omnix.assistant.voice.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Мёртвый» микрофон (owner review 2026-09-26, п.5): константный поток
 * детектится, живая тишина с ADC-джиттером — нет.
 */
class DeadSignalDetectorTest {

    private val chunk = 1280

    private fun constantChunk(value: Short): ShortArray {
        return ShortArray(chunk) { value }
    }

    /** Живая тишина: джиттер ±8 LSB (реальный ADC-шум, размах 16 > порога 4). */
    private fun liveSilenceChunk(seed: Int): ShortArray {
        val out = ShortArray(chunk)
        for (i in 0 until chunk) {
            out[i] = ((seed + i) % 17 - 8).toShort()
        }
        return out
    }

    /** Живая речь-заглушка: размах ~32000 LSB. */
    private fun liveSpeechChunk(): ShortArray {
        val out = ShortArray(chunk)
        for (i in 0 until chunk) {
            out[i] = (((i * 235) % 32000) - 16000).toShort()
        }
        return out
    }

    @Test
    fun constantStream_isDetectedAfterFullWindow() {
        val detector = DeadSignalDetector(framesWindow = 50, maxSpanLsb = 4)
        var firedAt = -1
        repeat(50) { i ->
            if (detector.observe(constantChunk(Short.MIN_VALUE)) && firedAt < 0) firedAt = i
        }
        assertEquals("срабатывание ровно по завершении окна", 49, firedAt)
    }

    @Test
    fun liveSilenceWithJitter_isNeverDead() {
        val detector = DeadSignalDetector(framesWindow = 50, maxSpanLsb = 4)
        repeat(200) { i ->
            assertFalse(
                "тишина с ADC-джиттером не мёртвая (кадр $i)",
                detector.observe(liveSilenceChunk(i))
            )
        }
    }

    @Test
    fun oneRealChunkResetsWindow() {
        val detector = DeadSignalDetector(framesWindow = 50, maxSpanLsb = 4)
        repeat(49) { detector.observe(constantChunk(1000)) }
        // 50-й кадр живой: размах окна ~32000 LSB — не мёртвый.
        assertFalse(detector.observe(liveSpeechChunk()))
        // Живой кадр ещё в окне (окно = 49 констант + 1 живой):
        // 49 констант после него детекта не дают.
        repeat(49) { detector.observe(constantChunk(1000)) }
        // 100-м кадром живой выталкивается из окна — чистое константное окно.
        assertTrue(detector.observe(constantChunk(1000)))
    }

    @Test
    fun resetClearsWindow() {
        val detector = DeadSignalDetector(framesWindow = 10, maxSpanLsb = 4)
        repeat(9) { detector.observe(constantChunk(0)) }
        detector.reset()
        repeat(8) { detector.observe(constantChunk(0)) }
        // 9 < 10: окно после reset ещё не набралось.
        assertFalse("после reset окно набирается заново", detector.observe(constantChunk(0)))
        // 10-й константный кадр — полное окно.
        assertTrue(detector.observe(constantChunk(0)))
    }

    @Test
    fun dcOffsetWithNoNoise_isDead() {
        // Прошивка-баг: поток зажат на DC-смещении, шума нет.
        val detector = DeadSignalDetector(framesWindow = 20, maxSpanLsb = 4)
        var fired = false
        repeat(20) { fired = detector.observe(constantChunk(20_000)) }
        assertTrue(fired)
    }
}
