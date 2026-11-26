package com.omnix.assistant.voice.orchestrator

import android.speech.SpeechRecognizer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowUpWindowTest {

    @Test
    fun `silence and noise keep the window open`() {
        assertTrue(FollowUpWindow.isQuietError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertTrue(FollowUpWindow.isQuietError(SpeechRecognizer.ERROR_NO_MATCH))
    }

    @Test
    fun `real failures close the window`() {
        assertFalse(FollowUpWindow.isQuietError(SpeechRecognizer.ERROR_NETWORK))
        assertFalse(FollowUpWindow.isQuietError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertFalse(FollowUpWindow.isQuietError(SpeechRecognizer.ERROR_AUDIO))
    }
}
