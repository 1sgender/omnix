package com.omnix.assistant.presentation.state

import org.junit.Assert.assertEquals
import org.junit.Test

/** Переключатель на логотипе показывает факт службы, а не нажатие. */
class AssistantPowerTest {

    @Test
    fun `no transition shows the real service state`() {
        assertEquals(AssistantPower.ON, resolveAssistantPower(serviceActive = true, transition = null))
        assertEquals(AssistantPower.OFF, resolveAssistantPower(serviceActive = false, transition = null))
    }

    @Test
    fun `starting is shown until the service is really up`() {
        assertEquals(AssistantPower.STARTING, resolveAssistantPower(false, AssistantPower.STARTING))
        assertEquals(AssistantPower.ON, resolveAssistantPower(true, AssistantPower.STARTING))
    }

    @Test
    fun `stopping is shown until the service is really down`() {
        assertEquals(AssistantPower.STOPPING, resolveAssistantPower(true, AssistantPower.STOPPING))
        assertEquals(AssistantPower.OFF, resolveAssistantPower(false, AssistantPower.STOPPING))
    }
}
