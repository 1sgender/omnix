package com.omnix.assistant.voice.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleLifecycleTest {

    @Test
    fun `speaking within grace period is not stuck`() {
        assertFalse(SubtitleLifecycle.isSpeakingStuck(500, ttsSpeaking = false, text = "Открываю YouTube."))
    }

    @Test
    fun `no speech after grace period is stuck`() {
        assertTrue(
            SubtitleLifecycle.isSpeakingStuck(
                SubtitleLifecycle.SPEECH_START_GRACE_MS,
                ttsSpeaking = false,
                text = "Открываю YouTube."
            )
        )
    }

    @Test
    fun `active speech is not stuck before hard cap`() {
        assertFalse(SubtitleLifecycle.isSpeakingStuck(10_000, ttsSpeaking = true, text = "x".repeat(300)))
    }

    @Test
    fun `hard cap releases even if synthesizer claims to speak`() {
        val text = "Короткий ответ."
        assertTrue(SubtitleLifecycle.isSpeakingStuck(SubtitleLifecycle.speakingHardCapMs(text), true, text))
    }

    @Test
    fun `hard cap grows with text and is bounded`() {
        assertTrue(SubtitleLifecycle.speakingHardCapMs("a".repeat(500)) > SubtitleLifecycle.speakingHardCapMs("a"))
        assertEquals(120_000L, SubtitleLifecycle.speakingHardCapMs("a".repeat(100_000)))
    }

    @Test
    fun `blank cleaned recognition is dropped`() {
        assertTrue(SubtitleLifecycle.shouldDropEmptyRecognition("  "))
        assertFalse(SubtitleLifecycle.shouldDropEmptyRecognition("позвони маме"))
    }
}
