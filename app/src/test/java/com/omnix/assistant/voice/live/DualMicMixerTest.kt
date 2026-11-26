package com.omnix.assistant.voice.live

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DualMicMixerTest {

    private fun tone(amplitude: Double, n: Int = 320): ShortArray =
        ShortArray(n) { i -> (amplitude * sin(2.0 * PI * 300.0 * i / 16_000)).toInt().toShort() }

    private fun isSilent(frame: ShortArray) = frame.all { it.toInt() == 0 }

    @Test
    fun `owner voice loud only in earbud is cut`() {
        val mixer = DualMicMixer()
        val out = mixer.mix(phone = tone(300.0), earbud = tone(8_000.0))
        assertTrue(isSilent(out))
        assertEquals(1L, mixer.gatedFrames)
    }

    @Test
    fun `partner heard by both mics passes`() {
        val mixer = DualMicMixer()
        val out = mixer.mix(phone = tone(3_000.0), earbud = tone(2_500.0))
        assertTrue(DualMicMixer.levelDb(out) > -30f)
    }

    @Test
    fun `while translation plays the gate is stricter`() {
        val loose = DualMicMixer()
        val strict = DualMicMixer().apply { ttsActive = true }
        // Наушник громче на ~8 дБ: без перевода пропускаем, во время перевода — эхо.
        val phone = tone(1_000.0)
        val earbud = tone(2_500.0)
        assertTrue(!isSilent(loose.mix(phone, earbud)))
        assertTrue(isSilent(strict.mix(phone, earbud)))
    }

    @Test
    fun `gate holds a few frames after the owner stops`() {
        val mixer = DualMicMixer(hangoverFrames = 2)
        mixer.mix(tone(300.0), tone(8_000.0))
        assertTrue(isSilent(mixer.mix(tone(3_000.0), tone(3_000.0))))
        assertTrue(isSilent(mixer.mix(tone(3_000.0), tone(3_000.0))))
        assertTrue(!isSilent(mixer.mix(tone(3_000.0), tone(3_000.0))))
    }

    @Test
    fun `quiet earbud noise does not trigger the gate`() {
        val mixer = DualMicMixer()
        val out = mixer.mix(phone = ShortArray(320), earbud = tone(20.0))
        assertEquals(0L, mixer.gatedFrames)
        assertTrue(out.size == 320)
    }
}
