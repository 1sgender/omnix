package com.omnix.assistant.voice.offline

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePostFilterTest {

    private fun tone(freq: Double, rate: Int, seconds: Double): FloatArray =
        FloatArray((rate * seconds).toInt()) { i -> sin(2.0 * PI * freq * i / rate).toFloat() * 0.5f }

    private fun rms(x: FloatArray, from: Int): Double {
        var sum = 0.0
        for (i in from until x.size) sum += x[i].toDouble() * x[i]
        return Math.sqrt(sum / (x.size - from))
    }

    @Test
    fun `speech band passes almost unchanged`() {
        val input = tone(1000.0, RATE, 1.0)
        val out = VoicePostFilter(RATE).process(input)
        assertEquals(rms(input, RATE / 2), rms(out, RATE / 2), 0.02)
    }

    @Test
    fun `low rumble is cut`() {
        val input = tone(20.0, RATE, 2.0)
        val out = VoicePostFilter(RATE).process(input)
        assertTrue(rms(out, RATE) < rms(input, RATE) * 0.2)
    }

    @Test
    fun `constant offset is removed`() {
        val input = FloatArray(RATE) { 0.3f }
        val out = VoicePostFilter(RATE).process(input)
        assertTrue(abs(out.last()) < 0.01f)
    }

    @Test
    fun `input array is not modified and state carries across chunks`() {
        val input = tone(500.0, RATE, 0.5)
        val copy = input.copyOf()
        val whole = VoicePostFilter(RATE).process(input)
        assertTrue(input.contentEquals(copy))

        val filter = VoicePostFilter(RATE)
        val half = input.size / 2
        val parts = filter.process(input.copyOfRange(0, half)) + filter.process(input.copyOfRange(half, input.size))
        for (i in whole.indices) assertEquals(whole[i].toDouble(), parts[i].toDouble(), 1e-6)
    }

    private companion object {
        const val RATE = 22_050
    }
}
