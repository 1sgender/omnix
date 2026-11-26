package com.omnix.assistant.agent.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveChunkerTest {

    @Test
    fun `words are sent only after they settle in two partials`() {
        val c = LiveChunker(chunkWords = 3)
        assertNull(c.onPartial("I want to"))
        // «to» ещё могло поменяться; совпали три слова — кусок готов.
        assertEquals("I want to", c.onPartial("I want to buy"))
    }

    @Test
    fun `changing last words are not sent`() {
        val c = LiveChunker(chunkWords = 3)
        assertNull(c.onPartial("I want to by"))
        assertNull(c.onPartial("I want two"))
        assertNull(c.onPartial("I want to buy a"))
        assertEquals("I want to buy a", c.onPartial("I want to buy a ticket"))
    }

    @Test
    fun `already sent words are not repeated`() {
        val c = LiveChunker(chunkWords = 2)
        c.onPartial("we can meet")
        assertEquals("we can meet", c.onPartial("we can meet tomorrow"))
        assertNull(c.onPartial("we can meet tomorrow at"))
        assertEquals("tomorrow at", c.onPartial("we can meet tomorrow at noon"))
    }

    @Test
    fun `pause flushes what was heard`() {
        val c = LiveChunker(chunkWords = 4)
        assertNull(c.onPartial("hello there"))
        assertEquals("hello there", c.onPause())
        assertNull(c.onPause())
    }

    @Test
    fun `final sends the rest and starts a new phrase`() {
        val c = LiveChunker(chunkWords = 2)
        c.onPartial("thank you very")
        assertEquals("thank you very", c.onPartial("thank you very much"))
        assertEquals("much for coming", c.onFinal("Thank you very much for coming"))
        assertNull(c.onPartial("next"))
        assertEquals("next phrase", c.onFinal("next phrase"))
    }

    @Test
    fun `punctuation and case do not break agreement`() {
        val c = LiveChunker(chunkWords = 2)
        c.onPartial("Hello, how are")
        assertEquals("hello how are", c.onPartial("hello how are you"))
    }

    @Test
    fun `recognizer shortening the phrase does not crash`() {
        val c = LiveChunker(chunkWords = 1)
        c.onPartial("one two three")
        c.onPartial("one two three")
        assertNull(c.onPartial("one"))
        assertEquals("four", c.onFinal("one two three four"))
    }
}
