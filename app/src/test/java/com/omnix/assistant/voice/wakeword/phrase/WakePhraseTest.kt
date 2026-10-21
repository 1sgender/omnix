package com.omnix.assistant.voice.wakeword.phrase

import com.omnix.assistant.voice.wakeword.WakeWordConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Фраза вызова: значения по умолчанию, хранение и формат ключевых слов sherpa-onnx. */
class WakePhraseTest {

    @Test
    fun `new installs listen for Okay Omni on the phrase engine`() {
        val config = WakeWordConfig()
        assertEquals(WakeEngineKind.PHRASE, config.engine)
        assertEquals(WakePhrase.OKAY_OMNI, config.phrase)
    }

    @Test
    fun `stored choices survive a round trip and unknown values fall back to defaults`() {
        WakeEngineKind.entries.forEach { assertEquals(it, WakeEngineKind.fromStorageKey(it.storageKey)) }
        WakePhrase.entries.forEach { assertEquals(it, WakePhrase.fromStorageKey(it.storageKey)) }
        assertEquals(WakeEngineKind.PHRASE, WakeEngineKind.fromStorageKey(null))
        assertEquals(WakeEngineKind.PHRASE, WakeEngineKind.fromStorageKey("jarvis"))
        assertEquals(WakePhrase.OKAY_OMNI, WakePhrase.fromStorageKey(null))
        assertEquals(WakePhrase.OKAY_OMNI, WakePhrase.fromStorageKey("hey_omni"))
    }

    @Test
    fun `keywords are space separated tokens with a label, variants split by slash`() {
        WakePhrase.entries.forEach { phrase ->
            phrase.keywords.split("/").forEach { keyword ->
                val parts = keyword.trim().split(" ")
                assertTrue("label missing: $keyword", parts.last().startsWith("@"))
                assertTrue("no tokens: $keyword", parts.size > 1)
                assertTrue("word must start with ▁: $keyword", parts.first().startsWith("▁"))
                parts.dropLast(1).forEach { token -> assertTrue("empty token: $keyword", token.isNotBlank()) }
            }
        }
    }

    @Test
    fun `default keywords file lists the default phrase`() {
        val file = File("src/main/assets", PhraseFrameDetector.KEYWORDS)
        assertTrue("missing ${file.path}", file.isFile)
        val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(listOf(WakePhrase.OKAY_OMNI.keywords.split("/").first()), lines)
    }
}
