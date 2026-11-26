package com.omnix.assistant.agent.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveInterpretCommandTest {

    @Test
    fun `english is the default source`() {
        assertEquals("en", LiveInterpretCommand.parse("переводчик"))
        assertEquals("en", LiveInterpretCommand.parse("включи синхронный перевод"))
        assertEquals("en", LiveInterpretCommand.parse("переводи собеседника"))
    }

    @Test
    fun `source language is taken from the command`() {
        assertEquals("en", LiveInterpretCommand.parse("переводи с английского"))
        assertEquals("tr", LiveInterpretCommand.parse("переводи с турецкого"))
        assertEquals("de", LiveInterpretCommand.parse("translate from German"))
        assertEquals("en", LiveInterpretCommand.parse("iňlis dilinden terjime et"))
    }

    @Test
    fun `one-off translation and other phrases are not the mode`() {
        assertNull(LiveInterpretCommand.parse("переведи фразу доброе утро на английский"))
        assertNull(LiveInterpretCommand.parse("какая погода"))
        assertNull(LiveInterpretCommand.parse(""))
    }

    @Test
    fun `stop words exit the mode`() {
        assertTrue(LiveInterpretCommand.isStop("Stop."))
        assertTrue(LiveInterpretCommand.isStop("стоп"))
        assertFalse(LiveInterpretCommand.isStop("stop the car please"))
    }
}
