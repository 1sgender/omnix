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

    @Test
    fun `offline synthesis in progress counts as engaged before onStart`() {
        // Состояние синтезатора ещё Ready, но фраза уже в очереди/синтезе.
        assertTrue(SubtitleLifecycle.isTtsEngaged(stateSpeaking = false, utterancesPending = true))
        assertTrue(SubtitleLifecycle.isTtsEngaged(stateSpeaking = true, utterancesPending = false))
        assertFalse(SubtitleLifecycle.isTtsEngaged(stateSpeaking = false, utterancesPending = false))
    }

    @Test
    fun `long offline synthesis is not released after the start grace`() {
        val text = "a".repeat(400)
        val engaged = SubtitleLifecycle.isTtsEngaged(stateSpeaking = false, utterancesPending = true)
        assertFalse(
            SubtitleLifecycle.isSpeakingStuck(SubtitleLifecycle.SPEECH_START_GRACE_MS + 1_000, engaged, text)
        )
        // Без учёта очереди прежний страж вернул бы слушание уже здесь.
        assertTrue(
            SubtitleLifecycle.isSpeakingStuck(SubtitleLifecycle.SPEECH_START_GRACE_MS + 1_000, false, text)
        )
    }

    @Test
    fun `engaged synthesizer is stopped before listening returns`() {
        assertTrue(SubtitleLifecycle.shouldStopTtsBeforeListening(ttsEngaged = true))
        assertFalse(SubtitleLifecycle.shouldStopTtsBeforeListening(ttsEngaged = false))
    }
}
