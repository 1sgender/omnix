package com.omnix.assistant.voice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class SpeechEnhancerTest {

    private val rate = SpeechEnhancer.SAMPLE_RATE

    private fun tone(hz: Double, amplitude: Double, seconds: Double = 1.0, dc: Double = 0.0): ShortArray =
        ShortArray((rate * seconds).toInt()) { i ->
            (dc + amplitude * sin(2.0 * PI * hz * i / rate)).toInt().coerceIn(-32768, 32767).toShort()
        }

    private fun rms(s: ShortArray, skip: Int = 0): Double {
        var sum = 0.0
        for (i in skip until s.size) sum += s[i].toDouble() * s[i]
        return sqrt(sum / (s.size - skip))
    }

    private fun mean(s: ShortArray, skip: Int = 0): Double = (skip until s.size).sumOf { s[it].toDouble() } / (s.size - skip)

    @Test
    fun `high pass removes DC offset`() {
        val out = SpeechEnhancer.highPass(tone(1_000.0, 3_000.0, dc = 4_000.0))
        assertTrue("mean ${mean(out, rate / 2)}", abs(mean(out, rate / 2)) < 50)
    }

    @Test
    fun `high pass starts without a click on constant offset`() {
        val out = SpeechEnhancer.highPass(ShortArray(2_000) { 5_000 })
        assertTrue(out.all { abs(it.toInt()) < 10 })
    }

    @Test
    fun `high pass cuts 20 Hz rumble and keeps 1 kHz speech band`() {
        val skip = rate / 4
        val rumble = SpeechEnhancer.highPass(tone(20.0, 8_000.0))
        val voice = SpeechEnhancer.highPass(tone(1_000.0, 8_000.0))
        assertTrue("rumble ${rms(rumble, skip)}", rms(rumble, skip) < 0.35 * 8_000 / sqrt(2.0))
        assertTrue("voice ${rms(voice, skip)}", rms(voice, skip) > 0.98 * 8_000 / sqrt(2.0))
    }

    @Test
    fun `high pass attenuates about 3 dB near cutoff`() {
        val out = SpeechEnhancer.highPass(tone(80.0, 8_000.0, seconds = 2.0))
        val ratio = rms(out, rate) / (8_000 / sqrt(2.0))
        assertEquals(0.707, ratio, 0.05)
    }

    @Test
    fun `quiet speech is raised towards target`() {
        val out = SpeechEnhancer.process(tone(500.0, 400.0))
        assertTrue("rms ${rms(out)}", rms(out) > 2.0 * rms(tone(500.0, 400.0)))
    }

    @Test
    fun `gain is capped so noise is not blown up`() {
        assertEquals(SpeechEnhancer.MAX_GAIN, SpeechEnhancer.levelingGain(150.0), 1e-9)
    }

    @Test
    fun `loud speech is only gently reduced`() {
        assertEquals(SpeechEnhancer.MIN_GAIN, SpeechEnhancer.levelingGain(20_000.0), 1e-9)
        val g = SpeechEnhancer.levelingGain(4_500.0)
        assertTrue(g < 1.0 && g > 0.5)
    }

    @Test
    fun `silence and digital zero are left alone`() {
        assertEquals(1.0, SpeechEnhancer.levelingGain(0.0), 0.0)
        val zero = ShortArray(3_200)
        assertTrue(SpeechEnhancer.process(zero).all { it.toInt() == 0 })
        assertTrue(SpeechEnhancer.process(ShortArray(0)).isEmpty())
    }

    @Test
    fun `limiter never clips even with huge gain`() {
        val loud = tone(300.0, 30_000.0)
        val out = SpeechEnhancer.applyGainWithLimiter(loud, 6.0)
        assertTrue(out.all { it in -32767..32767 })
        for (i in loud.indices) {
            assertTrue(loud[i].toInt() == 0 || (loud[i] > 0) == (out[i] > 0))
        }
    }

    @Test
    fun `limiter is transparent below the knee and monotonic above`() {
        val src = ShortArray(5) { (it * 1_000).toShort() }
        assertTrue(SpeechEnhancer.applyGainWithLimiter(src, 1.0).contentEquals(src))
        var prev = -1
        for (v in 27_000..32_767 step 500) {
            val o = SpeechEnhancer.applyGainWithLimiter(shortArrayOf(v.toShort()), 1.5)[0].toInt()
            assertTrue(o >= prev)
            prev = o
        }
    }

    @Test
    fun `pauses inside a phrase do not inflate gain`() {
        val speech = tone(500.0, 1_000.0, seconds = 0.5)
        val phrase = speech + ShortArray(rate) + speech
        assertEquals(rms(speech), SpeechEnhancer.speechRms(phrase), rms(speech) * 0.1)
    }

    @Test
    fun `quiet and loud input end up at similar loudness`() {
        val quiet = rms(SpeechEnhancer.process(tone(500.0, 500.0)))
        val loud = rms(SpeechEnhancer.process(tone(500.0, 12_000.0)))
        assertTrue(abs(quiet - loud) < 2_500)
    }
}
