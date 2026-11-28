package com.omnix.assistant.voice.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunksTest {

    @Test
    fun `answer is split into sentences`() {
        assertEquals(
            listOf("Я умею звонить и писать сообщения.", "Ещё ставлю будильники и напоминания!", "Что сделать?"),
            SpeechChunks.split("Я умею звонить и писать сообщения. Ещё ставлю будильники и напоминания! Что сделать?")
        )
    }

    @Test
    fun `very short sentence joins the next one`() {
        assertEquals(listOf("Да. Звоню маме прямо сейчас."), SpeechChunks.split("Да. Звоню маме прямо сейчас."))
    }

    @Test
    fun `short tail joins the previous sentence`() {
        assertEquals(listOf("Будильник поставлен на семь утра. Готово."), SpeechChunks.split("Будильник поставлен на семь утра. Готово."))
    }

    @Test
    fun `long sentence is cut by commas and nothing is lost`() {
        val long = (1..30).joinToString(", ") { "пункт номер $it" } + "."
        val chunks = SpeechChunks.split(long)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= SpeechChunks.MAX_CHARS })
        assertEquals(long.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
    }

    @Test
    fun `blank text gives nothing`() {
        assertEquals(emptyList<String>(), SpeechChunks.split("   "))
    }
}
