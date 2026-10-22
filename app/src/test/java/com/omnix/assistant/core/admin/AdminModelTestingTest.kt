package com.omnix.assistant.core.admin

import com.omnix.assistant.agent.decision.AiModelChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Тест моделей админом: хранение закрепления, маршрут и подписи моделей. */
class AdminModelTestingTest {

    @Test
    fun `pin survives storage round trip`() {
        listOf(
            AdminModelPin.None,
            AdminModelPin.Phone("qwen2.5-1.5b-instruct-q8"),
            AdminModelPin.Cloud("GEMINI")
        ).forEach { pin ->
            assertEquals(pin, AdminModelPin.fromStorageKey(pin.storageKey))
        }
    }

    @Test
    fun `unknown or empty storage values mean no pin`() {
        listOf(null, "", "phone:", "cloud:", "groq", "tablet:x").forEach { raw ->
            assertEquals(AdminModelPin.None, AdminModelPin.fromStorageKey(raw))
        }
    }

    @Test
    fun `pinned model forces its route`() {
        assertNull(AdminModelPin.None.routing)
        assertEquals(AiModelChoice.ON_DEVICE, AdminModelPin.Phone("qwen2.5-0.5b-instruct-q8").routing)
        assertEquals(AiModelChoice.CLOUD, AdminModelPin.Cloud("GROQ").routing)
    }

    @Test
    fun `cloud label names provider and model`() {
        assertEquals("Groq · openai/gpt-oss-120b", cloudModelLabel("GROQ", "openai/gpt-oss-120b"))
        assertEquals("OpenRouter", cloudModelLabel("OPENROUTER", null))
        assertEquals("Gemini", cloudModelLabel("GEMINI", " "))
        assertEquals("MISTRAL · m", cloudModelLabel("MISTRAL", "m"))
    }

    @Test
    fun `testing is off by default`() {
        val off = AdminModelTesting.OFF
        assertEquals(AdminModelPin.None, off.pin())
        assertNull(off.answerLabel(com.omnix.assistant.agent.decision.ExecutionType.CLOUD_AI))
    }
}
