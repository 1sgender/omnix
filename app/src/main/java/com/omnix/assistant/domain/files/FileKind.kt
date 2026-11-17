package com.omnix.assistant.domain.files

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Что за файл приложил пользователь. Определяется по содержимому, а не по расширению. */
enum class FileKind(val wire: String) {
    PDF("pdf"),
    DOCX("docx"),
    DOC("doc"),
    XLSX("xlsx"),
    PPTX("pptx"),
    SPREADSHEET_TEXT("csv"),
    TEXT("text"),
    IMAGE("image"),
    AUDIO("audio"),
    UNKNOWN("file");

    companion object {
        fun fromWire(value: String?): FileKind? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Определение типа файла по первым байтам (и по списку файлов внутри zip для
 * Office). Расширение только уточняет там, где сигнатуры нет (текст, csv) или
 * она общая (OLE у старых .doc/.xls/.ppt).
 */
object FileKindDetector {

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "json", "xml", "html", "htm", "log", "yaml", "yml",
        "ini", "cfg", "conf", "properties", "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx",
        "sql", "srt", "vtt", "rtf", "tex", "sh", "bat", "gradle", "toml", "c", "h", "cpp", "hpp",
        "cs", "go", "rs", "swift", "rb", "php", "css", "scss", "dart", "lua", "r", "m", "pl", "env"
    )
    private val CSV_EXTENSIONS = setOf("csv", "tsv")
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "3gp", "webm", "mka", "mp4", "wma"
    )
    private val DOC_EXTENSIONS = setOf("doc", "dot")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun detect(name: String, bytes: ByteArray): FileKind {
        val ext = extension(name)
        if (bytes.isEmpty()) return if (ext in TEXT_EXTENSIONS || ext in CSV_EXTENSIONS) FileKind.TEXT else FileKind.UNKNOWN
        return when {
            startsWith(bytes, "%PDF") -> FileKind.PDF
            ChatImageRules.detectMime(bytes.copyOf(minOf(bytes.size, 16))) != null -> FileKind.IMAGE
            isZip(bytes) -> officeKind(bytes)
            isOle(bytes) -> if (ext in DOC_EXTENSIONS) FileKind.DOC else FileKind.UNKNOWN
            isAudio(bytes, ext) -> FileKind.AUDIO
            looksLikeText(bytes) -> if (ext in CSV_EXTENSIONS) FileKind.SPREADSHEET_TEXT else FileKind.TEXT
            else -> FileKind.UNKNOWN
        }
    }

    private fun startsWith(bytes: ByteArray, ascii: String, offset: Int = 0): Boolean {
        if (bytes.size < offset + ascii.length) return false
        return ascii.indices.all { bytes[offset + it] == ascii[it].code.toByte() }
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    private val OLE_SIGNATURE = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
    )

    private fun isOle(bytes: ByteArray): Boolean =
        bytes.size >= 8 && OLE_SIGNATURE.indices.all { bytes[it] == OLE_SIGNATURE[it] }

    /** docx/xlsx/pptx — это zip; различаем по главному XML внутри. */
    private fun officeKind(bytes: ByteArray): FileKind = try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var kind = FileKind.UNKNOWN
            var entry = zip.nextEntry
            var seen = 0
            while (entry != null && seen < 2_000) {
                when (entry.name) {
                    "word/document.xml" -> kind = FileKind.DOCX
                    "xl/workbook.xml" -> kind = FileKind.XLSX
                    "ppt/presentation.xml" -> kind = FileKind.PPTX
                }
                if (kind != FileKind.UNKNOWN) break
                seen++
                entry = zip.nextEntry
            }
            kind
        }
    } catch (_: java.io.IOException) {
        FileKind.UNKNOWN
    } catch (_: IllegalArgumentException) {
        FileKind.UNKNOWN
    }

    private fun isAudio(bytes: ByteArray, ext: String): Boolean {
        if (startsWith(bytes, "ID3") || startsWith(bytes, "OggS") || startsWith(bytes, "fLaC") ||
            startsWith(bytes, "#!AMR")
        ) return true
        if (startsWith(bytes, "RIFF") && startsWith(bytes, "WAVE", 8)) return true
        // mp4/m4a/3gp: «ftyp» на 4-м байте. Видео тоже подходит: расшифруем звуковую дорожку.
        if (startsWith(bytes, "ftyp", 4) && ext in AUDIO_EXTENSIONS) return true
        // Matroska/WebM.
        if (bytes.size >= 4 && bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte() && ext in AUDIO_EXTENSIONS
        ) return true
        // MP3/AAC без тегов: синхрослово кадра. Бывает и у случайных данных — верим только с расширением.
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0 &&
            ext in AUDIO_EXTENSIONS
        ) return true
        return false
    }

    /** Текст: нет нулевых байтов (кроме UTF-16 с BOM) и почти всё читается как UTF-8. */
    fun looksLikeText(bytes: ByteArray): Boolean {
        if (hasUtf16Bom(bytes)) return true
        val sample = bytes.copyOf(minOf(bytes.size, 64 * 1024))
        if (sample.any { it.toInt() == 0 }) return false
        val decoded = String(sample, Charsets.UTF_8)
        // Последний символ мог разрезаться границей выборки — его не считаем.
        val bad = decoded.dropLast(1).count { it == '�' }
        val control = decoded.count { it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' && it != '\u000C' }
        return bad <= decoded.length / 100 && control <= decoded.length / 100
    }

    fun hasUtf16Bom(bytes: ByteArray): Boolean =
        bytes.size >= 2 && (
            (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
                (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            )
}
