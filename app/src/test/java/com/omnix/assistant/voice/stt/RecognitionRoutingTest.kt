package com.omnix.assistant.voice.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionRoutingTest {

    @Test
    fun `speaking another OMNIX language switches recognition`() {
        assertEquals("tk", RecognitionRouting.effectiveLanguage("ru", "tk"))
        assertEquals("ru", RecognitionRouting.effectiveLanguage("tk", "ru"))
        assertEquals("en", RecognitionRouting.effectiveLanguage("ru", "en"))
        assertEquals("kk", RecognitionRouting.effectiveLanguage("ru", "kk"))
    }

    @Test
    fun `lookalike languages keep the interface language`() {
        assertEquals("tk", RecognitionRouting.effectiveLanguage("tk", "tr"))
        assertEquals("tk", RecognitionRouting.effectiveLanguage("tk", "az"))
        assertEquals("ru", RecognitionRouting.effectiveLanguage("ru", "uk"))
    }

    @Test
    fun `unknown or missing detection keeps the interface language`() {
        assertEquals("ru", RecognitionRouting.effectiveLanguage("ru", null))
        assertEquals("ru", RecognitionRouting.effectiveLanguage("ru", ""))
        assertEquals("ru", RecognitionRouting.effectiveLanguage("ru", "ja"))
    }

    @Test
    fun `server never overrides turkmen recognized on the phone`() {
        assertFalse(RecognitionRouting.serverCanImprove("tk"))
        assertTrue(RecognitionRouting.serverCanImprove("ru"))
    }

    @Test
    fun `too little text for a long phrase looks incomplete`() {
        assertTrue(RecognitionRouting.looksIncomplete("да", 3_000))
        assertFalse(RecognitionRouting.looksIncomplete("позвони маме и скажи что я опоздаю", 3_000))
        assertFalse(RecognitionRouting.looksIncomplete("да", 800))
    }
}
