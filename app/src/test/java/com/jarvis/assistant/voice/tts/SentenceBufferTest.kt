package com.jarvis.assistant.voice.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Границы предложений для TTS-стриминга (§13 ТЗ).
 *
 * Чистый JVM-тест: SentenceBuffer не зависит от Android.
 */
class SentenceBufferTest {

    private fun collect(block: SentenceBuffer.() -> Unit): List<String> {
        val out = mutableListOf<String>()
        SentenceBuffer(onSentence = out::add).block()
        return out
    }

    @Test
    fun `two sentences emitted incrementally without flush`() {
        val out = mutableListOf<String>()
        val buf = SentenceBuffer(onSentence = out::add)

        buf.push("Привет. ")
        assertEquals(listOf("Привет."), out)

        buf.push("Как дела?")
        // «?» в конце буфера — граница видна сразу (atEnd).
        assertEquals(listOf("Привет.", "Как дела?"), out)
        assertEquals(0, buf.bufferedLength())
    }

    @Test
    fun `token fragments assemble into one sentence`() {
        val out = collect {
            push("Доб")
            push("рый ")
            push("день")
            push("!")
            flush()
        }
        assertEquals(listOf("Добрый день!"), out)
    }

    @Test
    fun `russian abbreviations do not split`() {
        val out = collect {
            push("Это т.е. пример. Второе предложение.")
            flush()
        }
        assertEquals(listOf("Это т.е. пример.", "Второе предложение."), out)
    }

    @Test
    fun `english abbreviations and initials do not split`() {
        val out = collect {
            push("Mr. Smith met J. Doe, e.g. yesterday. Then they left.")
            flush()
        }
        assertEquals(
            listOf("Mr. Smith met J. Doe, e.g. yesterday.", "Then they left."),
            out
        )
    }

    @Test
    fun `decimal numbers do not split`() {
        val out = collect {
            push("Пи равно 3.14. Версия 1.0 вышла.")
            flush()
        }
        assertEquals(listOf("Пи равно 3.14.", "Версия 1.0 вышла."), out)
    }

    @Test
    fun `ellipsis and multiple marks are one boundary`() {
        val out = collect {
            push("Ну... возможно… Точно?! Да.")
            flush()
        }
        assertEquals(listOf("Ну...", "возможно…", "Точно?!", "Да."), out)
    }

    @Test
    fun `closing quotes stay with sentence`() {
        val out = collect {
            push("Он сказал «привет». Она ушла.")
            flush()
        }
        assertEquals(listOf("Он сказал «привет».", "Она ушла."), out)
    }

    @Test
    fun `flush emits remainder without terminator`() {
        val out = collect {
            push("Первое. Хвост без точки")
            flush()
        }
        assertEquals(listOf("Первое.", "Хвост без точки"), out)
    }

    @Test
    fun `empty tokens and whitespace-only remainder emit nothing`() {
        val out = collect {
            push("")
            push("   ")
            push("Ок.")
            push("  ")
            flush()
        }
        assertEquals(listOf("Ок."), out)
    }

    @Test
    fun `long text without boundary is force-emitted by guard`() {
        val out = mutableListOf<String>()
        val buf = SentenceBuffer(onSentence = out::add, maxBufferedChars = 20)
        buf.push("один два три четыре пять шесть семь")
        assertEquals(1, out.size)
        assertTrue(out[0].startsWith("один два"))
        buf.flush()
        assertEquals(1, out.size)
    }

    @Test
    fun `exclamation and question always split`() {
        val out = collect {
            push("Стой! Ты уверен? Да!")
            flush()
        }
        assertEquals(listOf("Стой!", "Ты уверен?", "Да!"), out)
    }
}
