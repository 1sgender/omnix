package com.omnix.assistant.voice.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrContextHintsTest {

    @Test
    fun `без контактов остаются слово вызова и функции приложения`() {
        val hints = AsrContextHints.biasingStrings(emptyList())
        assertEquals(AsrContextHints.WAKE_NAME, hints.first())
        assertTrue(hints.contains("напомни"))
    }

    @Test
    fun `имена контактов идут после слова вызова, с первым словом отдельно`() {
        val hints = AsrContextHints.biasingStrings(listOf("Иван Петров", "Мама"))
        assertEquals(listOf("Омни", "Иван Петров", "Иван", "Мама"), hints.take(4))
    }

    @Test
    fun `повторы без учёта регистра и слишком длинные имена отбрасываются`() {
        val long = "а".repeat(AsrContextHints.MAX_NAME_LENGTH + 1)
        val hints = AsrContextHints.biasingStrings(listOf("мама", "Мама", long, "  "))
        assertEquals(1, hints.count { it.equals("мама", ignoreCase = true) })
        assertFalse(hints.contains(long))
    }

    @Test
    fun `число подсказок ограничено`() {
        val many = (1..500).map { "Контакт$it" }
        assertEquals(AsrContextHints.MAX_BIASING_STRINGS, AsrContextHints.biasingStrings(many).size)
    }

    @Test
    fun `серверная подсказка короткая и без личных данных`() {
        val prompt = AsrContextHints.serverPrompt()
        assertTrue(prompt.startsWith(AsrContextHints.WAKE_NAME))
        assertTrue(prompt.length <= AsrContextHints.MAX_PROMPT_CHARS)
    }
}
