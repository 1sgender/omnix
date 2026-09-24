package com.omnix.assistant.presentation.firstrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Точки прогресса онбординга (мок 2026-09-24): три видимые вехи, Complete
 * точки скрывает. JVM-тест чистого маппинга.
 */
class FirstRunStepProgressTest {

    @Test
    fun `total is three milestones`() {
        assertEquals(3, ONBOARDING_PROGRESS_TOTAL)
        FirstRunStep.entries.forEach { step ->
            assertEquals(3, step.progressTotal)
        }
    }

    @Test
    fun `welcome is the first milestone`() {
        assertEquals(0, FirstRunStep.Welcome.progressIndex)
    }

    @Test
    fun `setup steps share the second milestone`() {
        // Устройство, пейринг и микрофон — одна веха «настройка»: пейринг
        // условен (клип может не найтись), точек больше трёх быть не должно.
        assertEquals(1, FirstRunStep.DeviceDetection.progressIndex)
        assertEquals(1, FirstRunStep.ClipPairing.progressIndex)
        assertEquals(1, FirstRunStep.Microphone.progressIndex)
    }

    @Test
    fun `first command is the third milestone`() {
        assertEquals(2, FirstRunStep.FirstCommand.progressIndex)
    }

    @Test
    fun `complete hides the dots`() {
        assertNull(FirstRunStep.Complete.progressIndex)
    }
}
