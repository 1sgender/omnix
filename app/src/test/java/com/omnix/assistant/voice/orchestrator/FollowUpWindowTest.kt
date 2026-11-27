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

    @Test
    fun `questions commands and continuations are for the assistant`() {
        listOf(
            "сколько времени",
            "позвони маме",
            "а завтра какая погода",
            "ещё напомни про хлеб",
            "открой ютуб",
            "what time is it",
            "and tomorrow",
            "call mom",
            "näçe sagat boldy",
            "jaň et ejeme",
            "hava nasıl",
            "Ты меня слышишь?"
        ).forEach { assertTrue(it, FollowUpAddressee.isForAssistant(it)) }
    }

    @Test
    fun `talk to other people is ignored`() {
        listOf(
            "я сейчас приду",
            "передай соль",
            "спасибо",
            "ладно давай",
            "yes I'll be there in five minutes",
            "men häzir gelýärin",
            ""
        ).forEach { assertFalse(it, FollowUpAddressee.isForAssistant(it)) }
    }
}
