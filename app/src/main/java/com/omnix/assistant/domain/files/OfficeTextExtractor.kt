package com.omnix.assistant.domain.files

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Текст из Office-файлов нового формата (docx, xlsx, pptx). Это zip с XML
 * внутри, поэтому хватает стандартного zip и регулярных выражений: без тяжёлых
 * библиотек вроде Apache POI (десятки мегабайт в APK).
 */
object OfficeTextExtractor {

    /** Один XML внутри архива читаем не больше этого (защита от zip-бомб). */
    private const val MAX_ENTRY_BYTES = 24 * 1024 * 1024

    /** Сумма распакованного по всему архиву. */
    private const val MAX_TOTAL_BYTES = 64 * 1024 * 1024

    // ------------------------------------------------------------------ docx

    fun docx(bytes: ByteArray): String? {
        val xml = readEntries(bytes) { it == "word/document.xml" }["word/document.xml"] ?: return null
        return docxXmlToText(xml)
    }

    fun docxXmlToText(xml: String): String {
        // Переводы строк самого XML — разметка, а не текст. Свои метки ставим
        // служебными символами и потом превращаем в табуляцию и перенос.
        val marked = xml
            .replace(Regex("[\r\n\t]"), " ")
            .replace(Regex("<w:tab\\b[^>]*/>"), TAB_MARK)
            .replace(Regex("<w:br\\b[^>]*/>"), LINE_MARK)
            .replace(Regex("<w:cr\\b[^>]*/>"), LINE_MARK)
            .replace("</w:tc>", TAB_MARK)
            .replace("</w:tr>", LINE_MARK)
            .replace("</w:p>", LINE_MARK)
            // Абзац в конце ячейки не должен ломать строку таблицы.
            .replace(LINE_MARK + TAB_MARK, TAB_MARK)
        // Текст только из <w:t>, иначе в выдачу попали бы служебные значения атрибутов.
        val out = StringBuilder()
        val token = Regex("<w:t(?:\\s[^>]*)?>(.*?)</w:t>|([$TAB_MARK$LINE_MARK])", RegexOption.DOT_MATCHES_ALL)
        for (m in token.findAll(marked)) {
            when (m.groupValues[2]) {
                TAB_MARK -> out.append('\t')
                LINE_MARK -> out.append('\n')
                else -> out.append(XmlText.unescape(m.groupValues[1]))
            }
        }
        return tidy(out.toString())
    }

    private const val TAB_MARK = "\u0001"
    private const val LINE_MARK = "\u0002"

    // ------------------------------------------------------------------ xlsx

    fun xlsx(bytes: ByteArray): String? {
        val entries = readEntries(bytes) { name ->
            name == "xl/sharedStrings.xml" || name == "xl/workbook.xml" ||
                name == "xl/_rels/workbook.xml.rels" ||
                (name.startsWith("xl/worksheets/") && name.endsWith(".xml") && !name.contains("/_rels/"))
        }
        if (entries.isEmpty()) return null
        val shared = sharedStrings(entries["xl/sharedStrings.xml"])
        val sheets = orderedSheets(entries)
        if (sheets.isEmpty()) return null
        val out = StringBuilder()
        for ((title, xml) in sheets) {
            val rows = sheetRows(xml, shared)
            if (rows.isEmpty()) continue
            if (out.isNotEmpty()) out.append("\n\n")
            out.append("[Sheet: ").append(title).append("]\n")
            rows.joinTo(out, "\n")
        }
        return tidy(out.toString())
    }

    private fun sharedStrings(xml: String?): List<String> {
        if (xml == null) return emptyList()
        val si = Regex("<si\\b[^>]*>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
        val t = Regex("<t(?:\\s[^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
        return si.findAll(xml).map { item ->
            // Фонетические подсказки (<rPh>) к тексту ячейки не относятся.
            val body = item.groupValues[1].replace(Regex("<rPh\\b.*?</rPh>", RegexOption.DOT_MATCHES_ALL), "")
            t.findAll(body).joinToString("") { XmlText.unescape(it.groupValues[1]) }
        }.toList()
    }

    /** Листы в порядке книги: workbook.xml → r:id → путь из rels. Если не вышло — по номеру файла. */
    private fun orderedSheets(entries: Map<String, String>): List<Pair<String, String>> {
        val files = entries.filterKeys { it.startsWith("xl/worksheets/") }
        val workbook = entries["xl/workbook.xml"]
        val rels = entries["xl/_rels/workbook.xml.rels"]
        if (workbook != null && rels != null) {
            val targets = Regex("<Relationship\\b([^>]*)/?>").findAll(rels).mapNotNull { m ->
                val id = attr(m.groupValues[1], "Id") ?: return@mapNotNull null
                val target = attr(m.groupValues[1], "Target") ?: return@mapNotNull null
                id to target
            }.toMap()
            val ordered = Regex("<sheet\\b([^>]*)/?>").findAll(workbook).mapNotNull { m ->
                val name = attr(m.groupValues[1], "name")?.let(XmlText::unescape) ?: return@mapNotNull null
                val rid = attr(m.groupValues[1], "r:id") ?: return@mapNotNull null
                val target = targets[rid] ?: return@mapNotNull null
                val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target.removePrefix("./")
                files[path]?.let { name to it }
            }.toList()
            if (ordered.isNotEmpty()) return ordered
        }
        return files.entries
            .sortedBy { numberIn(it.key) }
            .mapIndexed { i, e -> "Sheet${i + 1}" to e.value }
    }

    private fun sheetRows(xml: String, shared: List<String>): List<String> {
        val row = Regex("<row\\b[^>]*?(?:/>|>(.*?)</row>)", RegexOption.DOT_MATCHES_ALL)
        val cell = Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
        val value = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
        val inline = Regex("<t(?:\\s[^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
        val rows = ArrayList<String>()
        for (r in row.findAll(xml)) {
            val cells = ArrayList<String>()
            for (c in cell.findAll(r.groupValues[1])) {
                val attrs = c.groupValues[1]
                val body = c.groupValues[2]
                val type = attr(attrs, "t")
                val text = when (type) {
                    "s" -> value.find(body)?.groupValues?.get(1)?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> inline.findAll(body).joinToString("") { XmlText.unescape(it.groupValues[1]) }
                    "b" -> when (value.find(body)?.groupValues?.get(1)) { "1" -> "TRUE"; "0" -> "FALSE"; else -> "" }
                    else -> value.find(body)?.groupValues?.get(1)?.let(XmlText::unescape).orEmpty()
                }
                // Пропущенные пустые ячейки восстанавливаем по адресу, чтобы колонки не съезжали.
                val column = attr(attrs, "r")?.let(::columnIndex)
                if (column != null && column > cells.size && column - cells.size <= 50) {
                    while (cells.size < column) cells += ""
                }
                cells += text.replace('\n', ' ').replace('\t', ' ')
            }
            while (cells.isNotEmpty() && cells.last().isEmpty()) cells.removeAt(cells.lastIndex)
            if (cells.isNotEmpty()) rows += cells.joinToString("\t")
        }
        return rows
    }

    /** "C12" → 2 (A = 0). */
    fun columnIndex(ref: String): Int? {
        val letters = ref.takeWhile { it in 'A'..'Z' }
        if (letters.isEmpty() || letters.length > 3) return null
        var n = 0
        for (ch in letters) n = n * 26 + (ch - 'A' + 1)
        return n - 1
    }

    // ------------------------------------------------------------------ pptx

    fun pptx(bytes: ByteArray): String? {
        val slideName = Regex("ppt/slides/slide(\\d+)\\.xml")
        val entries = readEntries(bytes) { slideName.matches(it) }
        if (entries.isEmpty()) return null
        val paragraph = Regex("<a:p\\b[^>]*?(?:/>|>(.*?)</a:p>)", RegexOption.DOT_MATCHES_ALL)
        val run = Regex("<a:t(?:\\s[^>]*)?>(.*?)</a:t>|<a:br\\b[^>]*/>", RegexOption.DOT_MATCHES_ALL)
        val out = StringBuilder()
        entries.entries.sortedBy { numberIn(it.key) }.forEachIndexed { i, (_, xml) ->
            val lines = paragraph.findAll(xml).map { p ->
                run.findAll(p.groupValues[1]).joinToString("") { m ->
                    if (m.value.startsWith("<a:br")) "\n" else XmlText.unescape(m.groupValues[1])
                }.trim()
            }.filter { it.isNotEmpty() }.toList()
            if (out.isNotEmpty()) out.append("\n\n")
            out.append("[Slide ").append(i + 1).append("]")
            if (lines.isNotEmpty()) out.append('\n').append(lines.joinToString("\n"))
        }
        return tidy(out.toString())
    }

    // ------------------------------------------------------------------ общее

    private fun attr(attrs: String, name: String): String? =
        Regex("(?:^|\\s)" + Regex.escape(name) + "=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)

    private fun numberIn(path: String): Int =
        Regex("(\\d+)\\.xml$").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

    private fun tidy(text: String): String = text
        .replace(Regex("[ \t]+\n"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** Нужные XML из архива; null-значения нет: нечитаемый архив даёт пустую карту. */
    private fun readEntries(bytes: ByteArray, wanted: (String) -> Boolean): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var total = 0L
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && wanted(entry.name)) {
                        val data = readBounded(zip, MAX_ENTRY_BYTES) ?: return result
                        total += data.size
                        if (total > MAX_TOTAL_BYTES) return result
                        result[entry.name] = String(data, Charsets.UTF_8)
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (_: java.io.IOException) {
            return result
        } catch (_: IllegalArgumentException) {
            return result
        }
        return result
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

/** Разэкранирование XML-сущностей (именованных и числовых). */
object XmlText {
    private val ENTITY = Regex("&(#x[0-9A-Fa-f]+|#[0-9]+|amp|lt|gt|quot|apos);")

    fun unescape(text: String): String {
        if ('&' !in text) return text
        return ENTITY.replace(text) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val code = if (e.startsWith("#x")) e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                    if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else m.value
                }
            }
        }
    }
}
