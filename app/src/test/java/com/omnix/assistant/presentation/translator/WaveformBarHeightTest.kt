package com.omnix.assistant.presentation.translator

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Высота столбика волны перевода: статичный глиф покоя сглаживается в
 * live-уровень по liveness. Чистая функция — JVM-тест.
 */
class WaveformBarHeightTest {

    @Test
    fun `rest glyph is untouched when not live`() {
        assertEquals(8.8f, waveformBarHeight(8.8f, 0.5f, 0f), 1e-6f)
    }

    @Test
    fun `full level at full liveness keeps the rest height`() {
        assertEquals(8.8f, waveformBarHeight(8.8f, 1f, 1f), 1e-6f)
    }

    @Test
    fun `silence while listening holds twenty percent of the rest height`() {
        assertEquals(1.76f, waveformBarHeight(8.8f, 0f, 1f), 1e-6f)
    }

    @Test
    fun `half level is sixty percent of the rest height`() {
        assertEquals(5.28f, waveformBarHeight(8.8f, 0.5f, 1f), 1e-6f)
    }

    @Test
    fun `half liveness blends rest and live in the middle`() {
        assertEquals((8.8f + 1.76f) / 2f, waveformBarHeight(8.8f, 0f, 0.5f), 1e-6f)
    }

    @Test
    fun `out of range inputs are clamped not echoed`() {
        assertEquals(8.8f, waveformBarHeight(8.8f, 3f, 1f), 1e-6f)
        assertEquals(8.8f, waveformBarHeight(8.8f, -1f, 0.5f), 1e-6f)
        assertEquals(1.76f, waveformBarHeight(8.8f, 0f, 4f), 1e-6f)
    }
}
