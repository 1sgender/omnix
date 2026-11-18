package com.omnix.assistant.domain.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPcmTest {

    @Test
    fun `stereo is averaged to mono`() {
        val mono = AudioPcm.toMono(shortArrayOf(100, 200, -100, -300), 2)
        assertArrayEquals(shortArrayOf(150, -200), mono)
    }

    @Test
    fun `mono input is returned unchanged`() {
        val data = shortArrayOf(1, 2, 3)
        assertArrayEquals(data, AudioPcm.toMono(data, 1))
    }

    @Test
    fun `resampling 48k to 16k keeps one third of the samples`() {
        val out = AudioPcm.resample(ShortArray(48_000) { 1000 }, 48_000)
        assertEquals(16_000, out.size)
        assertEquals(1000, out[8_000].toInt())
    }

    @Test
    fun `same rate is a no-op and zero rate is empty`() {
        val data = ShortArray(10)
        assertEquals(10, AudioPcm.resample(data, AudioPcm.TARGET_RATE).size)
        assertEquals(0, AudioPcm.resample(data, 0).size)
    }

    @Test
    fun `audio is split into chunks and a tiny tail is dropped`() {
        val rate = AudioPcm.TARGET_RATE
        val seconds = AudioPcm.CHUNK_SECONDS
        val full = ShortArray(rate * seconds * 2 + rate / 10)
        val chunks = AudioPcm.chunks(full)
        assertEquals(2, chunks.size)
        assertEquals(rate * seconds, chunks[0].size)
        val withTail = AudioPcm.chunks(ShortArray(rate * seconds + rate * 2))
        assertEquals(2, withTail.size)
        assertEquals(rate * 2, withTail[1].size)
    }

    @Test
    fun `transcripts are joined without empty parts`() {
        assertEquals("раз два три", AudioPcm.joinTranscripts(listOf(" раз ", "", "два", "  ", "три")))
    }
}
