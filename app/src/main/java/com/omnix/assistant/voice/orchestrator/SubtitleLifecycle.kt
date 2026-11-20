package com.omnix.assistant.voice.orchestrator

/**
 * Правила «не зависать на экране»: чистая логика без Android.
 *
 * Субтитры голосового режима берутся из состояния ассистента (Recognizing /
 * Speaking). Они сбрасываются только сменой этого состояния, а смену
 * запускает событие синтезатора «Done». Если событие не пришло (стрим TTS
 * дозвучал ещё до того, как режим стал TTS_SPEAKING, или синтезатор прерван
 * системой), состояние оставалось Speaking навсегда. Страж ниже находит такой
 * случай и подсказывает, что пора вернуть ассистента в слушающий режим.
 */
object SubtitleLifecycle {

    /** Сколько ждать запуска озвучки, прежде чем считать, что она не идёт. */
    const val SPEECH_START_GRACE_MS = 6_000L

    /** Период опроса стража. */
    const val WATCHDOG_POLL_MS = 1_000L

    private const val BASE_SPEECH_MS = 8_000L
    private const val PER_CHAR_MS = 90L
    private const val MAX_SPEECH_MS = 120_000L

    /** Верхняя граница показа ответа: даже если синтезатор всё ещё «говорит». */
    fun speakingHardCapMs(text: String): Long =
        (BASE_SPEECH_MS + text.length * PER_CHAR_MS).coerceAtMost(MAX_SPEECH_MS)

    /**
     * Режим всё ещё «озвучивание ответа», но озвучки уже нет (или время вышло):
     * надо вернуть ассистента в слушающий режим и убрать субтитры.
     */
    fun isSpeakingStuck(elapsedMs: Long, ttsSpeaking: Boolean, text: String): Boolean {
        if (elapsedMs >= speakingHardCapMs(text)) return true
        return !ttsSpeaking && elapsedMs >= SPEECH_START_GRACE_MS
    }

    /**
     * Фраза распознана, но после очистки и снятия имени ассистента текста нет:
     * ждать нечего, субтитр «Recognizing» нужно сбросить в «Слушаю».
     */
    fun shouldDropEmptyRecognition(cleaned: String): Boolean = cleaned.isBlank()
}
