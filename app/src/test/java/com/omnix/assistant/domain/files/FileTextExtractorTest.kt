package com.omnix.assistant.domain.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTextExtractorTest {

    @Test
    fun `plain text file is returned as is`() {
        val result = FileTextExtractor.extract("notes.txt", "Привет\r\nмир".toByteArray())
        val ok = result as FileTextExtractor.Result.Success
        assertEquals("Привет\nмир", ok.text)
        assertFalse(ok.truncated)
    }

    @Test
    fun `csv and json are accepted by extension even without mime`() {
        assertTrue(FileTextExtractor.extract("t.csv", "a,b\n1,2".toByteArray()) is FileTextExtractor.Result.Success)
        assertTrue(FileTextExtractor.extract("d.json", "{}".toByteArray()) is FileTextExtractor.Result.Success)
    }

    @Test
    fun `long text is cut and marked truncated`() {
        val big = (1..20_000).joinToString("\n") { "строка $it" }
        val ok = FileTextExtractor.extract("big.txt", big.toByteArray()) as FileTextExtractor.Result.Success
        assertTrue(ok.truncated)
        assertTrue(ok.text.contains("[truncated:"))
        assertTrue(TextBudget.estimateTokens(ok.text) < FileTextExtractor.MAX_TEXT_TOKENS + 50)
    }

    @Test
    fun `binary content with a text extension is unsupported`() {
        val bytes = byteArrayOf(1, 2, 0, 3, 4)
        val failure = FileTextExtractor.extract("fake.txt", bytes) as FileTextExtractor.Result.Failure
        assertEquals(FileTextExtractor.Reason.UNSUPPORTED, failure.reason)
    }

    @Test
    fun `images and audio are handed over as media`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
        val image = FileTextExtractor.extract("a.png", png) as FileTextExtractor.Result.Media
        assertEquals(FileKind.IMAGE, image.kind)
        val audio = FileTextExtractor.extract("a.mp3", "ID3\u0004\u0000\u0000".toByteArray()) as FileTextExtractor.Result.Media
        assertEquals(FileKind.AUDIO, audio.kind)
    }

    @Test
    fun `unknown binary format is reported honestly`() {
        val failure = FileTextExtractor.extract("tool.exe", byteArrayOf(0x4D, 0x5A, 0, 1, 2, 0, 3)) as FileTextExtractor.Result.Failure
        assertEquals(FileTextExtractor.Reason.UNSUPPORTED, failure.reason)
    }

    @Test
    fun `file over the size limit is rejected`() {
        val big = ByteArray(FileTextExtractor.MAX_FILE_BYTES + 1) { 'a'.code.toByte() }
        val failure = FileTextExtractor.extract("big.txt", big) as FileTextExtractor.Result.Failure
        assertEquals(FileTextExtractor.Reason.TOO_LARGE, failure.reason)
    }

    @Test
    fun `pdf text is extracted and the kind is reported`() {
        val pdf = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n" +
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n" +
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>\nendobj\n" +
            "4 0 obj\n<< /Length 40 >>\nstream\nBT /F1 12 Tf (Contract text) Tj ET\nendstream\nendobj\n"
        val ok = FileTextExtractor.extract("c.pdf", pdf.toByteArray()) as FileTextExtractor.Result.Success
        assertEquals(FileKind.PDF, ok.kind)
        assertTrue(ok.text.contains("Contract text"))
    }

    @Test
    fun `xlsx and pptx go through the office readers`() {
        val sheet = "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Итого</t></is></c><c r=\"B1\"><v>42</v></c></row></sheetData></worksheet>"
        val xlsx = zipOf("xl/workbook.xml" to "<workbook/>", "xl/worksheets/sheet1.xml" to sheet)
        val table = FileTextExtractor.extract("t.xlsx", xlsx) as FileTextExtractor.Result.Success
        assertEquals(FileKind.XLSX, table.kind)
        assertTrue(table.text.contains("Итого\t42"))
        val pptx = zipOf("ppt/presentation.xml" to "<p/>", "ppt/slides/slide1.xml" to "<p:sld><a:p><a:r><a:t>Привет</a:t></a:r></a:p></p:sld>")
        val slides = FileTextExtractor.extract("s.pptx", pptx) as FileTextExtractor.Result.Success
        assertEquals(FileKind.PPTX, slides.kind)
        assertTrue(slides.text.contains("Привет"))
    }

    @Test
    fun `empty file is reported as empty`() {
        val failure = FileTextExtractor.extract("e.txt", ByteArray(0)) as FileTextExtractor.Result.Failure
        assertEquals(FileTextExtractor.Reason.EMPTY, failure.reason)
    }

    @Test
    fun `docx paragraphs become lines`() {
        val xml = "<w:document><w:body><w:p><w:r><w:t>Первый &amp; абзац</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>Второй</w:t></w:r></w:p></w:body></w:document>"
        val ok = FileTextExtractor.extract("doc.docx", zipOf("word/document.xml" to xml)) as FileTextExtractor.Result.Success
        assertEquals("Первый & абзац\nВторой", ok.text)
    }

    @Test
    fun `broken docx is unreadable`() {
        val failure = FileTextExtractor.extract("bad.docx", byteArrayOf(1, 2, 3, 4)) as FileTextExtractor.Result.Failure
        assertEquals(FileTextExtractor.Reason.UNREADABLE, failure.reason)
    }
}
