package com.omnix.assistant.domain.files

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Достаёт текст из файла, который пользователь приложил к сообщению. */
object FileTextExtractor {

    /** Больше этого размера файл не читаем целиком. */
    const val MAX_FILE_BYTES = 2 * 1024 * 1024

    /** В модель уходит не больше такого числа символов. */
    const val MAX_TEXT_CHARS = 30_000

    enum class Reason { TOO_LARGE, UNSUPPORTED, EMPTY, UNREADABLE }

    sealed interface Result {
        data class Success(val name: String, val text: String, val truncated: Boolean) : Result
        data class Failure(val name: String, val reason: Reason) : Result
    }

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "log", "yaml", "yml",
        "ini", "cfg", "conf", "properties", "kt", "java", "py", "js", "ts", "sql", "srt", "vtt",
        "rtf", "tex", "sh", "bat", "gradle", "toml"
    )

    fun extract(name: String, mime: String?, bytes: ByteArray): Result {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (bytes.isEmpty()) return Result.Failure(name, Reason.EMPTY)
        val raw = when {
            ext == "docx" -> docxText(bytes) ?: return Result.Failure(name, Reason.UNREADABLE)
            isTextLike(ext, mime) -> {
                if (looksBinary(bytes)) return Result.Failure(name, Reason.UNSUPPORTED)
                String(bytes, Charsets.UTF_8)
            }
            else -> return Result.Failure(name, Reason.UNSUPPORTED)
        }
        val text = raw.replace("\r\n", "\n").trim()
        if (text.isEmpty()) return Result.Failure(name, Reason.EMPTY)
        val truncated = text.length > MAX_TEXT_CHARS
        return Result.Success(name, if (truncated) text.take(MAX_TEXT_CHARS) else text, truncated)
    }

    private fun isTextLike(ext: String, mime: String?): Boolean =
        ext in TEXT_EXTENSIONS ||
            mime?.startsWith("text/") == true ||
            mime == "application/json" ||
            mime == "application/xml"

    private fun looksBinary(bytes: ByteArray): Boolean =
        bytes.take(1024).any { it.toInt() == 0 }

    /** Текст абзацев из word/document.xml внутри .docx; null, если архив не читается. */
    private fun docxText(bytes: ByteArray): String? = try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            var xml: String? = null
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    xml = String(zip.readBytes(), Charsets.UTF_8)
                    break
                }
                entry = zip.nextEntry
            }
            xml?.let(::stripDocxXml)
        }
    } catch (_: java.io.IOException) {
        null
    }

    private fun stripDocxXml(xml: String): String = xml
        .replace("</w:p>", "\n")
        .replace("<w:tab/>", "\t")
        .replace("<w:br/>", "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
}
