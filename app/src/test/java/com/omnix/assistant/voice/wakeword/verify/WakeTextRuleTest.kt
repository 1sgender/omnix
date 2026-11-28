package com.omnix.assistant.voice.wakeword.verify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeTextRuleTest {

    @Test
    fun `how GigaAM writes the owner's Omni is accepted`() {
        listOf("омни", "Омни, какая погода", "омнэ", "омня", "амни", "оми", "омми", "ну омни", "помни", "умни")
            .forEach { assertTrue(it, WakeTextRule.matches(it)) }
    }

    @Test
    fun `film speech in the owner's room is rejected`() {
        // Окна-кандидаты из звука комнаты владельца 2026-11-28.
        listOf(
            "они уже уехали а", "оне уехал", "фонарик такси", "везжай такси", "так", "",
            "давайдавай быстрее мы сейчас его упустим", "ани", "он не знает", "мне нужно домой", "омск", "омлет"
        ).forEach { assertFalse(it, WakeTextRule.matches(it)) }
    }

    @Test
    fun `strict rule takes only omni-like words`() {
        assertTrue(WakeTextRule.matches("омни", strict = true))
        assertTrue(WakeTextRule.matches("амни", strict = true))
        assertFalse(WakeTextRule.matches("помни", strict = true))
        assertFalse(WakeTextRule.matches("умни", strict = true))
    }

    @Test
    fun `omni inside a longer word does not count`() {
        assertFalse(WakeTextRule.matches("напомни"))
        assertFalse(WakeTextRule.matches("вспомни"))
    }
}
