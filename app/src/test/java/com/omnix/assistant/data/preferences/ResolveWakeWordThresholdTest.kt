package com.omnix.assistant.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM-тест чистой функции резолва порога wake-word. Порог хранится под
 * версионированным ключом ([WAKEWORD_THRESHOLD_KEY_NAME]): значения, подобранные
 * под omni_v0.1 (старый ключ wakeword.threshold, чаще всего 0.35), модель
 * omni_v0.2fp не наследует и стартует с дефолта 0.5.
 */
class ResolveWakeWordThresholdTest {

    @Test
    fun `явно записанный порог побеждает`() {
        assertEquals(0.7f, resolveWakeWordThreshold(0.7f), 0f)
    }

    @Test
    fun `нет значения под новым ключом — дефолт под omni_v0_2fp`() {
        assertEquals(DEFAULT_WAKE_WORD_THRESHOLD, resolveWakeWordThreshold(null), 0f)
        assertEquals(0.8f, DEFAULT_WAKE_WORD_THRESHOLD, 0f)
    }

    @Test
    fun `старый ключ порога v0_1 не читается — ключ версионирован`() {
        // Новое имя ключа не должно совпадать со старым: иначе у пользователей с
        // порогом 0.35 (рабочая точка omni_v0.1) модель v0.2fp срабатывала бы
        // на лишние слова.
        assertEquals("wakeword.threshold.v02fp.default08", WAKEWORD_THRESHOLD_KEY_NAME)
    }

    @Test
    fun `края клампятся, а не вырождают детекцию`() {
        assertEquals(MIN_WAKE_WORD_THRESHOLD, resolveWakeWordThreshold(0f), 0f)
        assertEquals(MAX_WAKE_WORD_THRESHOLD, resolveWakeWordThreshold(1f), 0f)
    }
}
