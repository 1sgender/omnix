package com.omnix.assistant.agent.memory.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Экран «Приватность» владельца (2026-10-21) показал имя «Как меня зовут»:
 * вопрос записывался как факт. Вопросы — не факты о пользователе.
 */
class AutonomousMemoryExtractorQuestionTest {

    private val extractor = AutonomousMemoryExtractor()

    @Test
    fun `a question is never remembered as a fact`() {
        listOf(
            "как меня зовут?",
            "Как меня зовут",
            "какое моё имя?",
            "скажи, как меня зовут",
            "где я живу?"
        ).forEach { question ->
            assertNull(question, extractor.extractFromTurn(question))
        }
    }

    @Test
    fun `a stated name is still remembered`() {
        val memory = extractor.extractFromTurn("меня зовут Сердар")

        assertTrue(memory?.shouldRemember == true)
        assertEquals("user.name", memory?.key)
        assertEquals("Сердар", memory?.value)
    }

    @Test
    fun `forget commands still work`() {
        val memory = extractor.extractFromTurn("забудь, что я живу в Ашхабаде")

        assertEquals("DELETE_FORGET", memory?.governanceAction)
    }
}
