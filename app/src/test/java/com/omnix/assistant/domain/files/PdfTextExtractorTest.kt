package com.omnix.assistant.domain.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class PdfTextExtractorTest {

    private fun pdf(vararg objects: String): ByteArray {
        val sb = StringBuilder("%PDF-1.4\n")
        objects.forEachIndexed { i, body -> sb.append(i + 1).append(" 0 obj\n").append(body).append("\nendobj\n") }
        sb.append("trailer\n<< /Root 1 0 R >>\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun stream(dict: String, data: String) = "<< $dict /Length ${data.length} >>\nstream\n$data\nendstream"

    private fun deflate(text: String): ByteArray {
        val deflater = Deflater()
        deflater.setInput(text.toByteArray(Charsets.ISO_8859_1))
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    @Test
    fun `plain latin text in a simple pdf`() {
        val content = "BT /F1 12 Tf 72 700 Td (Hello PDF world) Tj 0 -14 Td (Second line) Tj ET"
        val bytes = pdf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            stream("", content),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        )
        val text = PdfTextExtractor.extract(bytes)
        assertNotNull(text)
        assertTrue(text!!.contains("Hello PDF world"))
        assertTrue(text.contains("Second line"))
        assertTrue(text.startsWith("[Page 1]"))
    }

    @Test
    fun `compressed stream with ToUnicode map gives cyrillic`() {
        val cmap = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n" +
            "1 begincodespacerange <0000> <FFFF> endcodespacerange\n" +
            "2 beginbfchar <0001> <041F> <0002> <0440> endbfchar\n" +
            "1 beginbfrange <0003> <0004> <0438> endbfrange\n" +
            "endcmap end end"
        val content = "BT /F1 12 Tf <0001000200030004> Tj ET"
        val zContent = String(deflate(content), Charsets.ISO_8859_1)
        val zCmap = String(deflate(cmap), Charsets.ISO_8859_1)
        val bytes = pdf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            stream("/Filter /FlateDecode", zContent),
            "<< /Type /Font /Subtype /Type0 /BaseFont /X /ToUnicode 6 0 R >>",
            stream("/Filter /FlateDecode", zCmap)
        )
        val text = PdfTextExtractor.extract(bytes)
        assertNotNull(text)
        assertTrue(text!!, text.contains("Прий"))
    }

    @Test
    fun `page without text is an empty string`() {
        val bytes = pdf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>",
            stream("", "q 100 0 0 100 0 0 cm /Im1 Do Q")
        )
        assertEquals("", PdfTextExtractor.extract(bytes))
    }

    @Test
    fun `encrypted pdf is not read`() {
        val bytes = "%PDF-1.5\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Encrypt 9 0 R >>\n".toByteArray()
        assertNull(PdfTextExtractor.extract(bytes))
    }

    @Test
    fun `not a pdf and garbage do not crash`() {
        assertNull(PdfTextExtractor.extract("hello".toByteArray()))
        assertNull(PdfTextExtractor.extract(byteArrayOf()))
        PdfTextExtractor.extract("%PDF-1.4\n1 0 obj << /Kids [1 0 R] >> endobj \n((((".toByteArray())
    }
}
