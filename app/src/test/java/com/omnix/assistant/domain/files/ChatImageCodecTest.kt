package com.omnix.assistant.domain.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImageCodecTest {

    private val id = "a1b2c3d4e5f60718293a4b5c6d7e8f90"

    @Test
    fun `encode then decode returns id and question`() {
        val text = ChatImageCodec.encode(id, "Что на фото?")
        val decoded = ChatImageCodec.decode(text)
        assertEquals(listOf(id), decoded.imageIds)
        assertEquals("Что на фото?", decoded.question)
        assertTrue(ChatImageCodec.hasImage(text))
    }

    @Test
    fun `plain text has no image`() {
        val decoded = ChatImageCodec.decode("привет")
        assertTrue(decoded.imageIds.isEmpty())
        assertEquals("привет", decoded.question)
        assertFalse(ChatImageCodec.hasImage("привет"))
    }

    @Test
    fun `history replaces marker with a placeholder`() {
        val text = ChatImageCodec.encode(id, "Что тут написано?")
        assertEquals("[image attached] Что тут написано?", ChatImageCodec.forHistory(text))
        assertFalse(ChatImageCodec.forHistory(text).contains(id))
        assertEquals("обычная реплика", ChatImageCodec.forHistory("обычная реплика"))
    }

    @Test
    fun `invalid id is refused`() {
        assertFalse(ChatImageCodec.isValidId("../../etc/passwd"))
        assertFalse(ChatImageCodec.isValidId("short"))
        assertFalse(ChatImageCodec.isValidId("a".repeat(65)))
        assertTrue(ChatImageCodec.isValidId(id))
        try {
            ChatImageCodec.encode("../x", "q")
            throw AssertionError("must throw")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `marker with a bad id is not decoded as an image`() {
        val decoded = ChatImageCodec.decode("<<OMNIX_IMAGE id=\"../../x\">>\nвопрос")
        assertTrue(decoded.imageIds.isEmpty())
    }

    @Test
    fun `image marker does not disturb text file attachment codec`() {
        val file = ChatAttachmentCodec.encode("a.txt", "содержимое", "вопрос")
        val decoded = ChatImageCodec.decode(file)
        assertTrue(decoded.imageIds.isEmpty())
        assertEquals("a.txt", ChatAttachmentCodec.decode(decoded.question).fileName)
    }
}
