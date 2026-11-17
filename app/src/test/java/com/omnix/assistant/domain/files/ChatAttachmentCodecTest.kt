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
    fun `kind size and document id survive a round trip`() {
        val encoded = ChatAttachmentCodec.encode("a.pdf", "текст", "вопрос", FileKind.PDF, 2048, "abcdef123456")
        val decoded = ChatAttachmentCodec.decode(encoded)
        assertEquals("a.pdf", decoded.fileName)
        assertEquals(FileKind.PDF, decoded.kind)
        assertEquals(2048L, decoded.sizeBytes)
        assertEquals("abcdef123456", decoded.documentId)
        assertEquals("текст", decoded.content)
        assertEquals("вопрос", decoded.question)
    }

    @Test
    fun `old format without attributes still decodes`() {
        val old = "<<OMNIX_FILE name=\"old.txt\">>\nтело\n<<END_OMNIX_FILE>>\nчто тут?"
        val decoded = ChatAttachmentCodec.decode(old)
        assertEquals("old.txt", decoded.fileName)
        assertNull(decoded.kind)
        assertNull(decoded.documentId)
        assertEquals("что тут?", decoded.question)
    }

    @Test
    fun `history copy shortens long files and drops the document id`() {
        val encoded = ChatAttachmentCodec.encode("a.pdf", "x".repeat(10_000), "q", FileKind.PDF, 1, "abcdef123456")
        val short = ChatAttachmentCodec.forHistory(encoded)
        val decoded = ChatAttachmentCodec.decode(short)
        assertTrue(decoded.content!!.length < 4_100)
        assertNull(decoded.documentId)
        assertEquals("q", decoded.question)
    }

    @Test
    fun `quotes and line breaks in the name are removed`() {
        val name = ChatAttachmentCodec.sanitizeName("a\"b\nc<d>.txt")
        assertEquals("a b c d .txt", name)
    }
}
