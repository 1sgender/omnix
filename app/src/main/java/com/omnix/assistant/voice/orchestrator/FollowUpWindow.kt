package com.omnix.assistant.voice.orchestrator

import android.speech.SpeechRecognizer

/**
 * Окно продолжения разговора: после ответа OMNIX ещё слушает без «Омни».
 *
 * Распознаватель Android закрывает сессию сам, если несколько секунд тихо
 * ([SpeechRecognizer.ERROR_SPEECH_TIMEOUT]) или услышан только шум
 * ([SpeechRecognizer.ERROR_NO_MATCH]). Внутри окна это не повод замолкать — слушаем снова.
 * Остальные ошибки (сеть, микрофон, разрешения) окно закрывают.
 */
object FollowUpWindow {
    fun isQuietError(errorCode: Int): Boolean =
        errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || errorCode == SpeechRecognizer.ERROR_NO_MATCH
}
