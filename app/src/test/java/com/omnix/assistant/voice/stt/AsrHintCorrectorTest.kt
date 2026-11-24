package com.omnix.assistant.voice.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AsrHintCorrectorTest {

    private val names = listOf("Усман Каримов", "Алишер")

    @Test
    fun `имя после позвони исправляется по телефонной книге`() {
        assertEquals("позвони усман", AsrHintCorrector.correct("позвони осман", names))
    }

    @Test
    fun `знак препинания после имени сохраняется`() {
        assertEquals("позвони усман,", AsrHintCorrector.correct("позвони осман,", names))
    }

    @Test
    fun `имя после смс исправляется, текст сообщения не трогается`() {
        assertEquals("смс алишер привет", AsrHintCorrector.correct("смс алишир привет", names))
    }

    @Test
    fun `слово уже совпадает с контактом - строка не меняется`() {
        val text = "позвони усману"
        assertSame(text, AsrHintCorrector.correct(text, names))
    }

    @Test
    fun `вне слота имени слова не исправляются`() {
        val text = "купи осман"
        assertSame(text, AsrHintCorrector.correct(text, names))
    }

    @Test
    fun `два одинаково похожих контакта - не угадываем`() {
        val text = "позвони асман"
        assertSame(text, AsrHintCorrector.correct(text, listOf("Усман", "Осман")))
    }

    @Test
    fun `далёкое слово не превращается в имя`() {
        val text = "позвони завтра"
        assertSame(text, AsrHintCorrector.correct(text, names))
    }

    @Test
    fun `без подсказок строка возвращается как есть`() {
        val text = "позвони осман"
        assertSame(text, AsrHintCorrector.correct(text, emptyList()))
    }

    @Test
    fun `название приложения после открой исправляется`() {
        assertEquals(
            "открой telegram",
            AsrHintCorrector.correct("открой telegran", emptyList(), AsrContextHints.APP_WORDS)
        )
    }

    @Test
    fun `имена контактов не попадают в подсказки приложений`() {
        val text = "открой осман"
        assertSame(text, AsrHintCorrector.correct(text, names, AsrContextHints.APP_WORDS))
    }
}
