package com.omnix.assistant.presentation.firstrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Онбординг: три шага — три точки прогресса, Complete точки скрывает,
 * порядок вперёд и назад без пропусков. JVM-тест чистого маппинга.
 */
class FirstRunStepProgressTest {

    @Test
    fun `total is three milestones`() {
        assertEquals(3, ONBOARDING_PROGRESS_TOTAL)
        FirstRunStep.entries.forEach { step -> assertEquals(3, step.progressTotal) }
    }

    @Test
    fun `each step is its own milestone`() {
        assertEquals(0, FirstRunStep.Welcome.progressIndex)
        assertEquals(1, FirstRunStep.Access.progressIndex)
        assertEquals(2, FirstRunStep.TurnOn.progressIndex)
    }

    @Test
    fun `complete hides the dots`() {
        assertNull(FirstRunStep.Complete.progressIndex)
    }

    @Test
    fun `steps go forward and back in order`() {
        assertEquals(FirstRunStep.Access, FirstRunStep.Welcome.next())
        assertEquals(FirstRunStep.TurnOn, FirstRunStep.Access.next())
        assertEquals(FirstRunStep.Complete, FirstRunStep.TurnOn.next())
        assertEquals(FirstRunStep.Access, FirstRunStep.TurnOn.previous())
        assertEquals(FirstRunStep.Welcome, FirstRunStep.Access.previous())
        assertNull(FirstRunStep.Welcome.previous())
    }
}
