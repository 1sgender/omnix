package com.omnix.assistant.presentation.firstrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Три состояния шага микрофона (мок 2026-09-24): «отказано» существует только
 * после реального показа системного диалога, автопереход — только для
 * разрешения, полученного прямо на этом экране. JVM-тест чистого маппинга.
 */
class MicrophoneStepStateTest {

    @Test
    fun `granted permission is the granted state regardless of the prompt`() {
        assertEquals(MicrophoneVisualState.Granted, microphoneVisualState(true, true))
        assertEquals(MicrophoneVisualState.Granted, microphoneVisualState(true, false))
    }

    @Test
    fun `denied is only visible after the prompt was shown`() {
        assertEquals(MicrophoneVisualState.Denied, microphoneVisualState(false, true))
    }

    @Test
    fun `before the first prompt the step asks and does not deny`() {
        // Ссылка «Открыть настройки» до первого системного диалога запрещена
        // моком — состояние может быть только «запрос».
        assertEquals(MicrophoneVisualState.Request, microphoneVisualState(false, false))
    }

    @Test
    fun `a fresh grant advances by itself`() {
        assertTrue(microphoneShouldAutoAdvance(granted = true, grantedOnEntry = false))
    }

    @Test
    fun `an already granted step does not bounce the user forward`() {
        // Возврат жестом назад с первой команды: шаг показан с кнопкой
        // «Продолжить», автоперехода нет.
        assertFalse(microphoneShouldAutoAdvance(granted = true, grantedOnEntry = true))
    }

    @Test
    fun `no advance without a grant`() {
        assertFalse(microphoneShouldAutoAdvance(granted = false, grantedOnEntry = false))
        assertFalse(microphoneShouldAutoAdvance(granted = false, grantedOnEntry = true))
    }
}
