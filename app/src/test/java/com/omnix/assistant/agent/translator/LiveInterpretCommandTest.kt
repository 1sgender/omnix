package com.omnix.assistant.agent.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.omnix.assistant.voice.live.LiveMicMode
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
    fun `microphone is taken from the command`() {
        assertEquals(LiveMicMode.EARBUD, LiveInterpretCommand.parseMic("переводчик через наушник"))
        assertEquals(LiveMicMode.PHONE, LiveInterpretCommand.parseMic("переводи с английского через телефон"))
        assertEquals(LiveMicMode.BOTH, LiveInterpretCommand.parseMic("переводчик обоими микрофонами"))
        assertEquals(LiveMicMode.BOTH, LiveInterpretCommand.parseMic("translate from English with both mics"))
        // «в наушник» — про звук перевода, микрофон не назван.
        assertNull(LiveInterpretCommand.parseMic("переводи в наушник"))
        assertNull(LiveInterpretCommand.parseMic("переводчик"))
    }

    @Test
    fun `stop words exit the mode`() {
        assertTrue(LiveInterpretCommand.isStop("Stop."))
        assertTrue(LiveInterpretCommand.isStop("стоп"))
        assertFalse(LiveInterpretCommand.isStop("stop the car please"))
    }
}
