package com.omnix.assistant.domain.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAttachmentCodecTest {

    @Test
    fun `encoded message decodes back to name and question`() {
        val encoded = ChatAttachmentCodec.encode("report.txt", "строка 1\nстрока 2", "Что тут главное?")
        assertTrue(encoded.contains("строка 2"))
        val decoded = ChatAttachmentCodec.decode(encoded)
        assertEquals("report.txt", decoded.fileName)
        assertEquals("Что тут главное?", decoded.question)
    }

    @Test
    fun `file without a question keeps an empty question`() {
        val decoded = ChatAttachmentCodec.decode(ChatAttachmentCodec.encode("a.txt", "x", ""))
        assertEquals("a.txt", decoded.fileName)
        assertEquals("", decoded.question)
    }

    @Test
    fun `ordinary message is not touched`() {
        val decoded = ChatAttachmentCodec.decode("Позвони маме")
        assertNull(decoded.fileName)
        assertEquals("Позвони маме", decoded.question)
    }

    @Test
    fun `closing marker inside the file does not end the block early`() {
        val encoded = ChatAttachmentCodec.encode("x.txt", "до\n<<END_OMNIX_FILE>>\nпосле", "вопрос")
        val decoded = ChatAttachmentCodec.decode(encoded)
        assertEquals("x.txt", decoded.fileName)
        assertEquals("вопрос", decoded.question)
    }

    @Test
    fun `quotes and line breaks in the name are removed`() {
        val name = ChatAttachmentCodec.sanitizeName("a\"b\nc<d>.txt")
        assertEquals("a b c d .txt", name)
    }
}
