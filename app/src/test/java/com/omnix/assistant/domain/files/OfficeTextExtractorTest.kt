package com.omnix.assistant.domain.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun zipOf(vararg entries: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, body) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(body.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

class OfficeTextExtractorTest {

    @Test
    fun `docx keeps paragraphs tabs and entities`() {
        val xml = "<w:document><w:body>" +
            "<w:p><w:r><w:t>Первый &amp; абзац</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>Имя</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>Значение</w:t></w:r></w:p>" +
            "</w:body></w:document>"
        val text = OfficeTextExtractor.docx(zipOf("word/document.xml" to xml))
        assertEquals("Первый & абзац\nИмя\tЗначение", text)
    }

    @Test
    fun `docx without the main part is null`() {
        assertNull(OfficeTextExtractor.docx(zipOf("other.xml" to "<a/>")))
    }

    @Test
    fun `xlsx resolves shared strings numbers and skipped cells`() {
        val shared = "<sst><si><t>Товар</t></si><si><t>Цена</t></si><si><t>Яблоко</t></si></sst>"
        val sheet = "<worksheet><sheetData>" +
            "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>" +
            "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"C2\"><v>12.5</v></c></row>" +
            "</sheetData></worksheet>"
        val workbook = "<workbook><sheets><sheet name=\"Склад\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
        val rels = "<Relationships><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>"
        val text = OfficeTextExtractor.xlsx(
            zipOf(
                "xl/workbook.xml" to workbook,
                "xl/_rels/workbook.xml.rels" to rels,
                "xl/sharedStrings.xml" to shared,
                "xl/worksheets/sheet1.xml" to sheet
            )
        )
        assertEquals("[Sheet: Склад]\nТовар\tЦена\nЯблоко\t\t12.5", text)
    }

    @Test
    fun `xlsx without sheets is null`() {
        assertNull(OfficeTextExtractor.xlsx(zipOf("xl/sharedStrings.xml" to "<sst/>")))
    }

    @Test
    fun `pptx lists slides in numeric order`() {
        val slide1 = "<p:sld><a:p><a:r><a:t>Заголовок</a:t></a:r></a:p><a:p><a:r><a:t>Пункт &lt;1&gt;</a:t></a:r></a:p></p:sld>"
        val slide2 = "<p:sld><a:p><a:r><a:t>Второй слайд</a:t></a:r></a:p></p:sld>"
        val slide10 = "<p:sld><a:p><a:r><a:t>Десятый</a:t></a:r></a:p></p:sld>"
        val text = OfficeTextExtractor.pptx(
            zipOf(
                "ppt/slides/slide10.xml" to slide10,
                "ppt/slides/slide2.xml" to slide2,
                "ppt/slides/slide1.xml" to slide1
            )
        )
        assertEquals(
            "[Slide 1]\nЗаголовок\nПункт <1>\n\n[Slide 2]\nВторой слайд\n\n[Slide 3]\nДесятый",
            text
        )
    }

    @Test
    fun `corrupt archive gives null instead of crashing`() {
        assertNull(OfficeTextExtractor.docx(byteArrayOf(1, 2, 3, 4, 5)))
        assertNull(OfficeTextExtractor.pptx(ByteArray(0)))
    }

    @Test
    fun `column letters become indexes`() {
        assertEquals(0, OfficeTextExtractor.columnIndex("A1"))
        assertEquals(27, OfficeTextExtractor.columnIndex("AB7"))
        assertNull(OfficeTextExtractor.columnIndex("12"))
    }

    @Test
    fun `numeric character references are decoded`() {
        assertTrue(XmlText.unescape("&#1055;&#x44;&quot;").startsWith("ПD"))
    }
}
