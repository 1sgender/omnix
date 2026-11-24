package com.omnix.assistant.voice.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-02 regression: extractQuery должен вернуть null, если после вырезания
 * wake-word осталась пустая строка (только имя ассистента). Старое поведение
 * возвращало raw-строку и провоцировало двойную обработку.
 */
class WakeWordExtractorTest {

    @Test
    fun `Джарвис возвращает null`() {
        assertNull(WakeWordExtractor.extractQuery("Джарвис"))
    }

    @Test
    fun `Джарвис с пробелами возвращает null`() {
        assertNull(WakeWordExtractor.extractQuery("  Джарвис  "))
        assertNull(WakeWordExtractor.extractQuery("omnix"))
        assertNull(WakeWordExtractor.extractQuery("Жарвис"))
        assertNull(WakeWordExtractor.extractQuery("джей"))
    }

    @Test
    fun `Джарвис с запятой и запросом вырезает wake-word`() {
        assertEquals("сколько времени", WakeWordExtractor.extractQuery("Джарвис, сколько времени"))
    }

    @Test
    fun `Джарвис стоп вырезает wake-word`() {
        assertEquals("стоп", WakeWordExtractor.extractQuery("Джарвис стоп"))
    }

    @Test
    fun `запрос с wake-word посреди строки — containsWakeWord не даёт ложных срабатываний`() {
        // CR-02: containsWakeWord должен смотреть на НАЧАЛО строки (с точностью
        // до пунктуации/пробелов), иначе фразы вроде «привет, ...» или
        // слова, содержащие подстроку «жар»/«джар», дадут false-positive.
        assertFalse(WakeWordExtractor.containsWakeWord("привет Джарвис сколько времени"))
        assertFalse(WakeWordExtractor.containsWakeWord("какая погода сегодня жарко"))
        // extractQuery при этом по-прежнему вырезает wake-word если он есть в начале.
        assertEquals("сколько времени", WakeWordExtractor.extractQuery("привет Джарвис сколько времени"))
    }

    @Test
    fun `пунктуация после и перед wake-word обрезается`() {
        assertEquals("сколько времени", WakeWordExtractor.extractQuery("Джарвис, сколько времени"))
        assertEquals("сколько времени", WakeWordExtractor.extractQuery("omnix! сколько времени"))
        assertEquals("сколько времени", WakeWordExtractor.extractQuery("Джарвис — сколько времени"))
        assertEquals("сколько времени", WakeWordExtractor.extractQuery("...Джарвис, сколько времени"))
    }

    @Test
    fun `пустая и blank строка возвращает null`() {
        assertNull(WakeWordExtractor.extractQuery(""))
        assertNull(WakeWordExtractor.extractQuery("   "))
        assertNull(WakeWordExtractor.extractQuery("   !!!,,,   "))
    }

    @Test
    fun `строка без wake-word возвращается как есть, с пробелами по бокам`() {
        assertEquals("какая погода", WakeWordExtractor.extractQuery("  какая погода  "))
    }

    @Test
    fun `containsWakeWord детектирует все стандартные варианты в начале строки`() {
        listOf("Джарвис", "omnix", "Жарвис", "Дарвис", "Джей", "диджей", "джар",
               "  Джарвис", "...Джарвис", "Джарвис,", "эй джарвис").forEach {
            assertTrue("containsWakeWord должен распознать '$it'", WakeWordExtractor.containsWakeWord(it))
        }
        assertFalse(WakeWordExtractor.containsWakeWord("какая погода"))
        assertFalse(WakeWordExtractor.containsWakeWord("сегодня жарко"))
    }

    @Test
    fun `Омни вырезается в начале фразы`() {
        assertEquals("позвони маме", WakeWordExtractor.extractQuery("Омни, позвони маме"))
        assertEquals("позвони маме", WakeWordExtractor.extractQuery("омни позвони маме"))
        assertEquals("сколько времени", WakeWordExtractor.extractQuery("ОМНИ — сколько времени"))
    }

    @Test
    fun `окей Омни с пунктуацией вырезается целиком`() {
        assertEquals("открой ютуб", WakeWordExtractor.extractQuery("Окей, Омни! открой ютуб"))
        assertEquals("открой ютуб", WakeWordExtractor.extractQuery("окей омни открой ютуб"))
        assertEquals("открой ютуб", WakeWordExtractor.extractQuery("Эй, Омни, открой ютуб"))
    }

    @Test
    fun `одно слово вызова без команды даёт null`() {
        listOf("омни", "Омни!", "Окей, Омни", "okay omni", "Omnix", "омникс.").forEach {
            assertNull("'$it' должно дать null", WakeWordExtractor.extractQuery(it))
        }
    }

    @Test
    fun `составные слова с омни через дефис не срезаются`() {
        listOf(
            "омни-канальная стратегия продаж", "Омни-канальный подход", "omni-channel strategy",
            "омни‐канальная"
        ).forEach {
            assertEquals(it, WakeWordExtractor.extractQuery(it))
            assertFalse("'$it' не слово вызова", WakeWordExtractor.containsWakeWord(it))
        }
    }

    @Test
    fun `обращение через запятую или тире по-прежнему срезается`() {
        assertEquals("позвони маме", WakeWordExtractor.extractQuery("омни, позвони маме"))
        assertEquals("позвони маме", WakeWordExtractor.extractQuery("омни - позвони маме"))
        assertEquals("позвони маме", WakeWordExtractor.extractQuery("омни — позвони маме"))
    }

    @Test
    fun `обычные слова не срезаются`() {
        listOf(
            "помни про встречу", "омлет на завтрак", "омар в ресторане", "амнистия объявлена",
            "омнибус приехал", "умни голову", "омикрон опасен", "окей, умный дом включи свет"
        ).forEach {
            assertEquals(it, WakeWordExtractor.extractQuery(it))
            assertFalse("'$it' не слово вызова", WakeWordExtractor.containsWakeWord(it))
        }
    }

    @Test
    fun `слово вызова в середине фразы Омни не вырезается`() {
        assertEquals("скажи омни привет", WakeWordExtractor.extractQuery("скажи омни привет"))
        assertFalse(WakeWordExtractor.containsWakeWord("скажи омни привет"))
    }

    @Test
    fun `английские фразы вызова`() {
        assertEquals("what time is it", WakeWordExtractor.extractQuery("hey omni what time is it"))
        assertEquals("what time is it", WakeWordExtractor.extractQuery("Okay, Omni, what time is it"))
        assertEquals("what time is it", WakeWordExtractor.extractQuery("OK Omni what time is it"))
        assertEquals("call mom", WakeWordExtractor.extractQuery("Omnix, call mom"))
    }

    @Test
    fun `ошибки распознавателя и варианты других языков`() {
        mapOf(
            "Оми, позвони маме" to "позвони маме",
            "Омме позвони маме" to "позвони маме",
            "Оммни, позвони маме" to "позвони маме",
            "Омни\u0301, позвони маме" to "позвони маме",
            "Омникс, позвони маме" to "позвони маме",
            "Окей, умни, позвони маме" to "позвони маме",
            "Ömni, call mom" to "call mom",
            "Tamam omni, call mom" to "call mom",
            "Оумни, позвони маме" to "позвони маме"
        ).forEach { (raw, expected) ->
            assertEquals(raw, expected, WakeWordExtractor.extractQuery(raw))
            assertTrue(raw, WakeWordExtractor.containsWakeWord(raw))
        }
    }

    @Test
    fun `containsWakeWord для семейства Омни`() {
        listOf("Омни", "омни, позвони", "Окей, Омни", "hey omni", "Эй омни", "Omnix", "  ...омни")
            .forEach { assertTrue("'$it'", WakeWordExtractor.containsWakeWord(it)) }
    }

    @Test
    fun `старое поведение для Джарвис не изменилось рядом с Омни`() {
        assertEquals("включи свет", WakeWordExtractor.extractQuery("Джарвис включи свет"))
        assertEquals("сколько времени", WakeWordExtractor.extractQuery("привет Джарвис сколько времени"))
    }
}

