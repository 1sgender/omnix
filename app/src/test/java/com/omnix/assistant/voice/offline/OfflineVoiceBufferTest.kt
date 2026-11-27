package com.omnix.assistant.voice.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineVoiceBufferTest {

    @Test
    fun `russian voice rate gives a buffer android accepts`() {
        // 22050 Гц: раньше буфер был 22050 байт (не кратно 4) — AudioTrack «Invalid audio buffer size».
        val bytes = OfflineVoice.trackBufferBytes(minBuffer = 7_104, sampleRate = 22_050)
        assertEquals(0, bytes % 4)
        assertTrue(bytes >= 22_050)
    }

    @Test
    fun `system minimum is respected`() {
        assertEquals(40_000, OfflineVoice.trackBufferBytes(minBuffer = 40_000, sampleRate = 16_000))
    }

    @Test
    fun `turkmen voice rate is unchanged`() {
        assertEquals(16_000, OfflineVoice.trackBufferBytes(minBuffer = 3_000, sampleRate = 16_000))
    }
}
