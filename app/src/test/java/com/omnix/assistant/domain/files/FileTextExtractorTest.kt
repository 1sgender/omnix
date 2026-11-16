package com.omnix.assistant.domain.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileTextExtractorTest {

    @Test
    fun `plain text file is returned as is`() {
        val result = FileTextExtractor.extract("notes.txt", "text/plain", "Привет\r\nмир".toByteArray())
        val ok = result as FileTextExtractor.Result.Success
        assertEquals("Привет\nмир", ok.text)
        assertFalse(ok.truncated)
    }

    @Test
    fun `csv and json are accepted by extension even without mime`() {
        assertTrue(FileTextExtractor.extract("t.csv", null, "a,b\n1,2".toByteArray()) is FileTextExtractor.Result.Success)
        assertTrue(FileTextExtractor.extract("d.json", null, "{}".toByteArray()) is FileTextExtractor.Result.Success)
    }

    @Test
    fun `long text is cut and marked truncated`() {
        val big = "a".repeat(FileTextExtractor.MAX_TEXT_CHARS + 500)
        val ok = FileTextExtractor.extract("big.txt", "text/plain", big.toByteArray()) as FileTextExtractor.Result.Success
        assertEquals(FileTextExtractor.MAX_TEXT_CHARS, ok.text.length)
        assertTrue(ok.truncated)
    }

    @Test
    fun `binary content with a text extension is unsupported`() {
        val bytes = byteArrayOf(1, 2, 0, 3, 4)
        val failure = FileTextExtractor.extract("fake.txt", "text/plain", bytes) as FileTextExtractor.Result.Failure
        assertEquals(FileTextExtractor.Reason.UNSUPPORTED, failure.reason)
    }

    @Test
    fun `pdf and images are not supported yet`() {
        val pdf = FileTextExtractor.extract("a.pdf", "application/pdf", byteArrayOf(37, 80, 68, 70)) as FileTextExtractor.Result.Failure
        val png = FileTextExtractor.extract("a.png", "image/png", byteArrayOf(1, 2, 3)) as FileTextExtractor.Result.Failure
        assertEquals(FileTextExtractor.Reason.UNSUPPORTED, pdf.reason)
        assertEquals(FileTextExtractor.Reason.UNSUPPORTED, png.reason)
    }

    @Test
    fun `empty file is reported as empty`() {
        val failure = FileTextExtractor.extract("e.txt", "text/plain", ByteArray(0)) as FileTextExtractor.Result.Failure
        assertEquals(FileTextExtractor.Reason.EMPTY, failure.reason)
    }

    @Test
    fun `docx paragraphs become lines`() {
        val xml = "<w:document><w:body><w:p><w:r><w:t>Первый &amp; абзац</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>Второй</w:t></w:r></w:p></w:body></w:document>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(xml.toByteArray())
            zip.closeEntry()
        }
        val ok = FileTextExtractor.extract("doc.docx", null, out.toByteArray()) as FileTextExtractor.Result.Success
        assertEquals("Первый & абзац\nВторой", ok.text)
    }

    @Test
    fun `broken docx is unreadable`() {
        val failure = FileTextExtractor.extract("bad.docx", null, byteArrayOf(1, 2, 3, 4)) as FileTextExtractor.Result.Failure
        assertEquals(FileTextExtractor.Reason.UNREADABLE, failure.reason)
    }
}
