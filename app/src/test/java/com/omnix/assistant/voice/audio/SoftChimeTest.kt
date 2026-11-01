package com.omnix.assistant.voice.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SoftChimeTest {

    @Test
    fun `wake chime is short, audible and never loud`() {
        val samples = SoftChime.wake()
        val seconds = samples.size.toDouble() / SoftChime.SAMPLE_RATE
        val peak = samples.maxOf { abs(it.toInt()) }
        assertTrue("short: $seconds", seconds in 0.2..0.6)
        assertTrue("audible: $peak", peak > 1_500)
        assertTrue("soft: $peak", peak < Short.MAX_VALUE / 2)
    }

    @Test
    fun `chime starts and ends without a click`() {
        for (samples in listOf(SoftChime.wake(), SoftChime.cancel())) {
            assertTrue(abs(samples.first().toInt()) < 400)
            assertTrue(abs(samples.last().toInt()) < 400)
        }
    }
}
