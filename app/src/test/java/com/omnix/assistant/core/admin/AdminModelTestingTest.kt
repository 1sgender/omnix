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
            AdminModelPin.Cloud("GEMINI"),
            AdminModelPin.Venice("venice-uncensored-1-2")
        ).forEach { pin ->
            assertEquals(pin, AdminModelPin.fromStorageKey(pin.storageKey))
        }
    }

    @Test
    fun `unknown or empty storage values mean no pin`() {
        listOf(null, "", "phone:", "cloud:", "venice:", "groq", "tablet:x").forEach { raw ->
            assertEquals(AdminModelPin.None, AdminModelPin.fromStorageKey(raw))
        }
    }

    @Test
    fun `pinned model forces its route`() {
        assertNull(AdminModelPin.None.routing)
        assertEquals(AiModelChoice.ON_DEVICE, AdminModelPin.Phone("qwen2.5-0.5b-instruct-q8").routing)
        assertEquals(AiModelChoice.CLOUD, AdminModelPin.Cloud("GROQ").routing)
        assertEquals(AiModelChoice.CLOUD, AdminModelPin.Venice("llama-3.3-70b").routing)
    }

    @Test
    fun `only cloud pins put a provider into the request, venice as venice-colon-model`() {
        assertNull(AdminModelPin.None.cloudRequestValue)
        assertNull(AdminModelPin.Phone("qwen2.5-0.5b-instruct-q8").cloudRequestValue)
        assertEquals("GROQ", AdminModelPin.Cloud("GROQ").cloudRequestValue)
        assertEquals("venice:llama-3.3-70b", AdminModelPin.Venice("llama-3.3-70b").cloudRequestValue)
    }

    @Test
    fun `venice answer is labelled as venice with its model`() {
        assertEquals("Venice · venice-uncensored-1-2", cloudModelLabel("VENICE", "venice-uncensored-1-2"))
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
