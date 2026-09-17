package com.omnix.assistant.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM-тест чистой функции миграции/резолва порога wake-word —
 * регрессия аудита: слайдер чувствительности раньше писал legacy-ключ,
 * который движок не читал (настройка не влияла на детекцию).
 */
class ResolveWakeWordThresholdTest {

    @Test
    fun `явно записанный порог побеждает — миграция не перекрывает выбор пользователя`() {
        assertEquals(0.7f, resolveWakeWordThreshold(0.7f, 0.9f), 0f)
    }

    @Test
    fun `нет ничего — дефолт`() {
        assertEquals(DEFAULT_WAKE_WORD_THRESHOLD, resolveWakeWordThreshold(null, null), 0f)
    }

    @Test
    fun `только legacy-чувствительность — миграция через инверсию`() {
        // Дефолтная чувствительность 0.65 → порог 0.35 (совпадает с дефолтом).
        assertEquals(0.35f, resolveWakeWordThreshold(null, 0.65f), 1e-4f)
        // Пользователь старой версии поднял чувствительность до 0.8 → порог 0.2.
        assertEquals(0.2f, resolveWakeWordThreshold(null, 0.8f), 1e-4f)
    }

    @Test
    fun `края legacy-чувствительности клампятся, а не вырождают детекцию`() {
        // sensitivity=1 (всё срабатывает) → порог не 0, а минимум 0.05.
        assertEquals(MIN_WAKE_WORD_THRESHOLD, resolveWakeWordThreshold(null, 1f), 0f)
        // sensitivity=0 (никогда не срабатывает) → порог не 1, а максимум 0.95.
        assertEquals(MAX_WAKE_WORD_THRESHOLD, resolveWakeWordThreshold(null, 0f), 0f)
    }
}
