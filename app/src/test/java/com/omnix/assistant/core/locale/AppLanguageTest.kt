package com.omnix.assistant.core.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AppLanguageTest {

    @Test
    fun `six languages, Russian when the system language is not one of them`() {
        assertEquals(6, AppLanguage.entries.size)
        assertEquals(AppLanguage.TK, AppLanguage.fromLocale(Locale.forLanguageTag("tk-TM")))
        assertEquals(AppLanguage.KK, AppLanguage.fromLocale(Locale.forLanguageTag("kk")))
        assertEquals(AppLanguage.RU, AppLanguage.fromLocale(Locale.GERMANY))
        assertEquals(AppLanguage.TK, AppLanguage.fromCode("tuk"))
        assertNull(AppLanguage.fromCode("xx"))
    }

    @Test
    fun `turkmen is recognised in Russian until there is a turkmen recogniser`() {
        assertEquals("ru-RU", SpeechLanguage.recognitionTag(AppLanguage.TK))
        assertEquals("uz-UZ", SpeechLanguage.recognitionTag(AppLanguage.UZ))
    }

    @Test
    fun `voice falls back to Russian, then English`() {
        val onlyRu = SpeechLanguage.ttsLocale(AppLanguage.TK) { it.language == "ru" }
        assertEquals("ru", onlyRu.language)
        val nothing = SpeechLanguage.ttsLocale(AppLanguage.KK) { false }
        assertEquals(Locale.US, nothing)
        val kk = SpeechLanguage.ttsLocale(AppLanguage.KK) { true }
        assertEquals("kk", kk.language)
    }

    @Test
    fun `answer hint names the interface language`() {
        assertTrue(SpeechLanguage.answerHint(AppLanguage.TR).contains("турецкий"))
    }
}
