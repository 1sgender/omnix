package com.omnix.assistant.voice.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageVerdictsTest {

    @Test
    fun `no verdicts means nothing is filtered`() {
        assertFalse(LanguageVerdicts().isForeign("en", nowMs = 10_000))
    }

    @Test
    fun `only russian heard while partner speaks english is foreign`() {
        val v = LanguageVerdicts()
        v.record("ru", atMs = 9_000)
        v.record("ru", atMs = 10_000)
        assertTrue(v.isForeign("en", nowMs = 10_200))
    }

    @Test
    fun `any english verdict lets the piece through`() {
        val v = LanguageVerdicts()
        v.record("ru", atMs = 9_000)
        v.record("en", atMs = 10_000)
        assertFalse(v.isForeign("en", nowMs = 10_200))
    }

    @Test
    fun `old verdicts are ignored`() {
        val v = LanguageVerdicts(lookbackMs = 2_500)
        v.record("ru", atMs = 1_000)
        assertFalse(v.isForeign("en", nowMs = 10_000))
    }

    @Test
    fun `other wrong languages also count as foreign`() {
        val v = LanguageVerdicts()
        v.record("pl", atMs = 10_000)
        assertTrue(v.isForeign("en", nowMs = 10_100))
    }
}
