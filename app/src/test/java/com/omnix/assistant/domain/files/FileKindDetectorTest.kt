package com.omnix.assistant.domain.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileKindDetectorTest {

    @Test
    fun `pdf is recognised by signature even with a wrong extension`() {
        assertEquals(FileKind.PDF, FileKindDetector.detect("report.txt", "%PDF-1.7 ...".toByteArray()))
    }

    @Test
    fun `office files are told apart by their content`() {
        assertEquals(FileKind.DOCX, FileKindDetector.detect("a.bin", zipOf("word/document.xml" to "<w/>")))
        assertEquals(FileKind.XLSX, FileKindDetector.detect("a.bin", zipOf("xl/workbook.xml" to "<w/>")))
        assertEquals(FileKind.PPTX, FileKindDetector.detect("a.bin", zipOf("ppt/presentation.xml" to "<w/>")))
        assertEquals(FileKind.UNKNOWN, FileKindDetector.detect("a.zip", zipOf("readme.txt" to "hi")))
    }

    @Test
    fun `old word needs the doc extension`() {
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(), 0, 0)
        assertEquals(FileKind.DOC, FileKindDetector.detect("old.doc", ole))
        assertEquals(FileKind.UNKNOWN, FileKindDetector.detect("old.xls", ole))
    }

    @Test
    fun `audio by signature`() {
        assertEquals(FileKind.AUDIO, FileKindDetector.detect("a.mp3", "ID3\u0004\u0000\u0000".toByteArray()))
        assertEquals(FileKind.AUDIO, FileKindDetector.detect("a.ogg", "OggS\u0000\u0002".toByteArray()))
        val wav = "RIFF....WAVEfmt ".toByteArray()
        assertEquals(FileKind.AUDIO, FileKindDetector.detect("a.wav", wav))
        val m4a = byteArrayOf(0, 0, 0, 0x20) + "ftypM4A ".toByteArray()
        assertEquals(FileKind.AUDIO, FileKindDetector.detect("a.m4a", m4a))
    }

    @Test
    fun `png is an image`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
        assertEquals(FileKind.IMAGE, FileKindDetector.detect("x.png", png))
    }

    @Test
    fun `text and csv`() {
        assertEquals(FileKind.TEXT, FileKindDetector.detect("Main.kt", "fun main() {}".toByteArray()))
        assertEquals(FileKind.SPREADSHEET_TEXT, FileKindDetector.detect("t.csv", "a,b\n1,2".toByteArray()))
    }

    @Test
    fun `binary junk is unknown`() {
        assertEquals(FileKind.UNKNOWN, FileKindDetector.detect("x.dat", byteArrayOf(1, 0, 2, 0, 3, 0, 4)))
    }

    @Test
    fun `wire names round trip`() {
        FileKind.entries.forEach { assertEquals(it, FileKind.fromWire(it.wire)) }
        assertFalse(FileKind.fromWire("nope") != null)
        assertTrue(FileKind.fromWire(null) == null)
    }
}
