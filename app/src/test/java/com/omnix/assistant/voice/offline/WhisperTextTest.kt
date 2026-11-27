package com.omnix.assistant.voice.offline

import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperTextTest {

    private fun bytes(vararg b: Int) = b.joinToString("") { (0xE000 + it).toChar().toString() }

    @Test
    fun `split turkmen letter is joined back`() {
        // «ň» = C5 88, пришла двумя токенами.
        assertEquals("gürrüň beresim", WhisperText.clean("gürrü" + bytes(0xC5, 0x88) + " beresim"))
    }

    @Test
    fun `plain text is unchanged`() {
        assertEquals("kanuny hemde aňsat ýollary", WhisperText.clean("kanuny hemde aňsat ýollary"))
    }

    @Test
    fun `broken byte tail is dropped`() {
        assertEquals("öz", WhisperText.clean("öz" + bytes(0xC5)))
    }

    @Test
    fun `looping word is cut`() {
        assertEquals("bank we", WhisperText.clean("bank we we we we we we"))
    }

    @Test
    fun `looping pair of words is cut`() {
        assertEquals("salam bar ýok", WhisperText.clean("salam bar ýok bar ýok bar ýok bar ýok bar ýok"))
    }

    @Test
    fun `short natural repetition is kept`() {
        assertEquals("hawa hawa hawa", WhisperText.clean("hawa hawa hawa"))
    }
}
