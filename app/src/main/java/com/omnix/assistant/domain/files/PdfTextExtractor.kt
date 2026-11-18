package com.omnix.assistant.domain.files

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Текст из PDF без сторонних библиотек: объекты, потоки FlateDecode, потоки
 * объектов (ObjStm), шрифты с таблицей ToUnicode (так в PDF хранится кириллица)
 * и операторы вывода текста Tj/TJ/'/". Сканы (только картинки) текста не дают —
 * для них есть нативная отправка PDF модели.
 *
 * Это не полноценный PDF-движок: порядок строк берётся из потока страницы,
 * текст внутри форм (XObject) и аннотаций пропускается.
 */
object PdfTextExtractor {

    private const val MAX_DECODED_BYTES = 64L * 1024 * 1024
    private const val MAX_OUTPUT_CHARS = 2_000_000
    private const val MAX_PAGES = 2_000

    private val WIN_ANSI: Charset = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

    /** null — не PDF или зашифрован; пустая строка — текста нет (скан). */
    fun extract(bytes: ByteArray): String? {
        if (bytes.size < 5 || String(bytes, 0, 5, Charsets.ISO_8859_1) != "%PDF-") return null
        return try {
            Document(bytes).text()
        } catch (_: StackOverflowError) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    // ================================================================ значения

    sealed interface Value
    data class PDict(val map: Map<String, Value>) : Value
    data class PArray(val items: List<Value>) : Value
    data class PName(val name: String) : Value
    data class PNum(val value: Double) : Value
    data class PRef(val num: Int) : Value
    class PStr(val bytes: ByteArray) : Value
    data class PKeyword(val word: String) : Value

    /** Разбор синтаксиса PDF над строкой ISO-8859-1 (байт = символ). */
    class Parser(private val s: String, var pos: Int = 0, private val end: Int = s.length) {

        fun atEnd(): Boolean {
            skipSpace()
            return pos >= end
        }

        private fun skipSpace() {
            while (pos < end) {
                val c = s[pos]
                if (c == '%') {
                    while (pos < end && s[pos] != '\n' && s[pos] != '\r') pos++
                } else if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\u000C' || c == '\u0000') {
                    pos++
                } else {
                    return
                }
            }
        }

        fun value(depth: Int = 0): Value? {
            if (depth > 64) throw IllegalStateException("too deep")
            skipSpace()
            if (pos >= end) return null
            val c = s[pos]
            return when {
                c == '<' && pos + 1 < end && s[pos + 1] == '<' -> dict(depth)
                c == '<' -> hexString()
                c == '(' -> literalString()
                c == '[' -> array(depth)
                c == '/' -> name()
                c == '+' || c == '-' || c == '.' || c.isDigit() -> numberOrRef()
                c == ']' || c == '>' || c == ')' || c == '{' || c == '}' -> { pos++; PKeyword(c.toString()) }
                else -> keyword()
            }
        }

        private fun dict(depth: Int): Value {
            pos += 2
            val map = LinkedHashMap<String, Value>()
            while (true) {
                skipSpace()
                if (pos >= end) break
                if (s.startsWith(">>", pos)) { pos += 2; break }
                val key = value(depth + 1) ?: break
                if (key !is PName) continue
                val v = value(depth + 1) ?: break
                map[key.name] = v
            }
            return PDict(map)
        }

        private fun array(depth: Int): Value {
            pos++
            val items = ArrayList<Value>()
            while (true) {
                skipSpace()
                if (pos >= end) break
                if (s[pos] == ']') { pos++; break }
                items += value(depth + 1) ?: break
            }
            return PArray(items)
        }

        private fun name(): Value {
            pos++
            val start = pos
            while (pos < end && !isDelimiter(s[pos])) pos++
            val raw = s.substring(start, pos)
            // #xx — экранированный байт в имени.
            val decoded = Regex("#([0-9A-Fa-f]{2})").replace(raw) { it.groupValues[1].toInt(16).toChar().toString() }
            return PName(decoded)
        }

        private fun numberOrRef(): Value {
            val start = pos
            pos++
            while (pos < end && (s[pos].isDigit() || s[pos] == '.')) pos++
            val text = s.substring(start, pos)
            val number = text.toDoubleOrNull() ?: return PKeyword(text)
            // «12 0 R» — ссылка на объект.
            if (text.all { it.isDigit() }) {
                var p = pos
                val ws = { while (p < end && (s[p] == ' ' || s[p] == '\n' || s[p] == '\r' || s[p] == '\t')) p++ }
                ws()
                val genStart = p
                while (p < end && s[p].isDigit()) p++
                if (p > genStart) {
                    ws()
                    if (p < end && s[p] == 'R' && (p + 1 >= end || isDelimiter(s[p + 1]))) {
                        pos = p + 1
                        return PRef(text.toIntOrNull() ?: return PNum(number))
                    }
                }
            }
            return PNum(number)
        }

        private fun keyword(): Value {
            val start = pos
            while (pos < end && !isDelimiter(s[pos])) pos++
            if (pos == start) pos++
            return PKeyword(s.substring(start, pos))
        }

        private fun hexString(): Value {
            pos++
            val hex = StringBuilder()
            while (pos < end && s[pos] != '>') {
                val c = s[pos]
                if (c.isLetterOrDigit()) hex.append(c)
                pos++
            }
            pos++
            if (hex.length % 2 == 1) hex.append('0')
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                out[i] = (hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: 0).toByte()
            }
            return PStr(out)
        }

        private fun literalString(): Value {
            pos++
            val out = ByteArrayOutputStream()
            var level = 1
            while (pos < end) {
                val c = s[pos]
                when (c) {
                    '\\' -> {
                        pos++
                        if (pos >= end) break
                        when (val e = s[pos]) {
                            'n' -> out.write('\n'.code)
                            'r' -> out.write('\r'.code)
                            't' -> out.write('\t'.code)
                            'b' -> out.write(8)
                            'f' -> out.write(12)
                            '\r' -> if (pos + 1 < end && s[pos + 1] == '\n') pos++
                            '\n' -> Unit
                            in '0'..'7' -> {
                                var n = 0
                                var k = 0
                                while (k < 3 && pos < end && s[pos] in '0'..'7') {
                                    n = n * 8 + (s[pos] - '0')
                                    pos++
                                    k++
                                }
                                pos--
                                out.write(n and 0xFF)
                            }
                            else -> out.write(e.code and 0xFF)
                        }
                    }
                    '(' -> { level++; out.write(c.code) }
                    ')' -> {
                        level--
                        if (level == 0) { pos++; return PStr(out.toByteArray()) }
                        out.write(c.code)
                    }
                    else -> out.write(c.code and 0xFF)
                }
                pos++
            }
            return PStr(out.toByteArray())
        }

        private fun isDelimiter(c: Char): Boolean =
            c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\u000C' || c == '\u0000' ||
                c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']' ||
                c == '{' || c == '}' || c == '/' || c == '%'
    }

    // ================================================================ документ

    private class RawObject(val dictStart: Int, val dictEnd: Int, val streamStart: Int?, val streamEnd: Int?)

    private class Document(private val bytes: ByteArray) {
        private val s = String(bytes, Charsets.ISO_8859_1)
        private val raw = HashMap<Int, RawObject>()
        private val embedded = HashMap<Int, String>()
        private val parsed = HashMap<Int, Value?>()
        private var decodedTotal = 0L

        init {
            scanObjects()
            loadObjectStreams()
        }

        private fun scanObjects() {
            val header = Regex("(\\d+)\\s+(\\d+)\\s+obj\\b")
            var from = 0
            while (true) {
                val m = header.find(s, from) ?: break
                val num = m.groupValues[1].toIntOrNull()
                val bodyStart = m.range.last + 1
                val endObj = s.indexOf("endobj", bodyStart).let { if (it < 0) s.length else it }
                val streamKw = s.indexOf("stream", bodyStart)
                if (streamKw in bodyStart until endObj && !s.startsWith("endstream", streamKw - 3)) {
                    var dataStart = streamKw + "stream".length
                    if (dataStart < s.length && s[dataStart] == '\r') dataStart++
                    if (dataStart < s.length && s[dataStart] == '\n') dataStart++
                    val dict = Parser(s, bodyStart, streamKw).value() as? PDict
                    val declared = (dict?.map?.get("Length") as? PNum)?.value?.toInt()
                    var dataEnd = if (declared != null && declared >= 0 && dataStart + declared <= s.length &&
                        s.indexOf("endstream", dataStart + declared).let { it >= 0 && it - (dataStart + declared) <= 4 }
                    ) {
                        dataStart + declared
                    } else {
                        s.indexOf("endstream", dataStart).let { if (it < 0) s.length else it }
                    }
                    // Перевод строки перед endstream в данные не входит.
                    if (declared == null || dataEnd != dataStart + declared) {
                        if (dataEnd > dataStart && s[dataEnd - 1] == '\n') dataEnd--
                        if (dataEnd > dataStart && s[dataEnd - 1] == '\r') dataEnd--
                    }
                    if (num != null) raw[num] = RawObject(bodyStart, streamKw, dataStart, dataEnd)
                    val afterStream = s.indexOf("endobj", dataEnd)
                    from = if (afterStream < 0) s.length else afterStream + 6
                } else {
                    if (num != null) raw[num] = RawObject(bodyStart, endObj, null, null)
                    from = if (endObj >= s.length) s.length else endObj + 6
                }
                if (from >= s.length) break
            }
        }

        /** Сжатые потоки объектов: шрифты и словари страниц часто лежат там. */
        private fun loadObjectStreams() {
            for ((_, obj) in raw.entries.toList()) {
                if (obj.streamStart == null) continue
                val dict = Parser(s, obj.dictStart, obj.dictEnd).value() as? PDict ?: continue
                if ((dict.map["Type"] as? PName)?.name != "ObjStm") continue
                val n = (dict.map["N"] as? PNum)?.value?.toInt() ?: continue
                val first = (dict.map["First"] as? PNum)?.value?.toInt() ?: continue
                val data = streamData(dict, obj) ?: continue
                val text = String(data, Charsets.ISO_8859_1)
                val head = Parser(text, 0, first.coerceAtMost(text.length))
                val pairs = ArrayList<Pair<Int, Int>>()
                repeat(n) {
                    val num = (head.value() as? PNum)?.value?.toInt() ?: return@repeat
                    val off = (head.value() as? PNum)?.value?.toInt() ?: return@repeat
                    pairs += num to off
                }
                pairs.forEachIndexed { i, (num, off) ->
                    val start = first + off
                    val stop = if (i + 1 < pairs.size) first + pairs[i + 1].second else text.length
                    if (start in 0..text.length && stop in start..text.length && num !in raw) {
                        embedded[num] = text.substring(start, stop)
                    }
                }
            }
        }

        fun obj(num: Int): Value? {
            if (parsed.containsKey(num)) return parsed[num]
            parsed[num] = null
            val value = raw[num]?.let { Parser(s, it.dictStart, it.dictEnd).value() }
                ?: embedded[num]?.let { Parser(it).value() }
            parsed[num] = value
            return value
        }

        fun resolve(v: Value?, depth: Int = 0): Value? =
            if (v is PRef && depth < 16) resolve(obj(v.num), depth + 1) else v

        fun dictOf(v: Value?): PDict? = resolve(v) as? PDict

        fun streamOf(v: Value?): ByteArray? {
            val ref = v as? PRef ?: return null
            val o = raw[ref.num] ?: return null
            if (o.streamStart == null) return null
            val dict = dictOf(ref) ?: return null
            return streamData(dict, o)
        }

        private fun streamData(dict: PDict, o: RawObject): ByteArray? {
            val start = o.streamStart ?: return null
            val stop = o.streamEnd ?: return null
            if (stop < start) return null
            val data = bytes.copyOfRange(start, stop)
            val filters = when (val f = resolve(dict.map["Filter"])) {
                null -> emptyList()
                is PName -> listOf(f.name)
                is PArray -> f.items.mapNotNull { (resolve(it) as? PName)?.name }
                else -> return null
            }
            var out = data
            for (f in filters) {
                out = when (f) {
                    "FlateDecode", "Fl" -> inflate(out) ?: return null
                    else -> return null
                }
            }
            val parms = dictOf(dict.map["DecodeParms"])
            val predictor = (parms?.map?.get("Predictor") as? PNum)?.value?.toInt() ?: 1
            if (predictor >= 10) {
                val columns = (parms?.map?.get("Columns") as? PNum)?.value?.toInt() ?: 1
                out = unpredictPng(out, columns) ?: return null
            }
            return out
        }

        private fun inflate(data: ByteArray): ByteArray? {
            val inflater = Inflater()
            return try {
                inflater.setInput(data)
                val out = ByteArrayOutputStream(data.size * 3)
                val buf = ByteArray(32 * 1024)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    if (n == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                    }
                    out.write(buf, 0, n)
                    decodedTotal += n
                    if (decodedTotal > MAX_DECODED_BYTES) return null
                }
                out.toByteArray()
            } catch (_: DataFormatException) {
                null
            } finally {
                inflater.end()
            }
        }

        /** PNG-предикторы (Up и др.) у потоков объектов. */
        private fun unpredictPng(data: ByteArray, columns: Int): ByteArray? {
            if (columns <= 0) return null
            val row = columns + 1
            val out = ByteArrayOutputStream(data.size)
            val prev = ByteArray(columns)
            var i = 0
            while (i + row <= data.size) {
                val type = data[i].toInt() and 0xFF
                val cur = ByteArray(columns)
                for (x in 0 until columns) {
                    val raw = data[i + 1 + x].toInt() and 0xFF
                    val left = if (x > 0) cur[x - 1].toInt() and 0xFF else 0
                    val up = prev[x].toInt() and 0xFF
                    val upLeft = if (x > 0) prev[x - 1].toInt() and 0xFF else 0
                    val v = when (type) {
                        0 -> raw
                        1 -> raw + left
                        2 -> raw + up
                        3 -> raw + (left + up) / 2
                        4 -> raw + paeth(left, up, upLeft)
                        else -> raw
                    }
                    cur[x] = v.toByte()
                }
                out.write(cur)
                cur.copyInto(prev)
                i += row
            }
            return out.toByteArray()
        }

        private fun paeth(a: Int, b: Int, c: Int): Int {
            val p = a + b - c
            val pa = kotlin.math.abs(p - a)
            val pb = kotlin.math.abs(p - b)
            val pc = kotlin.math.abs(p - c)
            return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
        }

        // ------------------------------------------------------------ страницы

        private class PageRef(val dict: PDict, val resources: PDict?)

        private fun pages(): List<PageRef> {
            val result = ArrayList<PageRef>()
            val catalogNum = raw.keys.plus(embedded.keys).sorted().firstOrNull { n ->
                (obj(n) as? PDict)?.map?.get("Type").let { it is PName && it.name == "Catalog" }
            }
            val root = catalogNum?.let { dictOf(dictOf(PRef(it))?.map?.get("Pages")) }
            if (root != null) {
                walk(root, null, result, java.util.Collections.newSetFromMap(java.util.IdentityHashMap()), 0)
            }
            if (result.isEmpty()) {
                for (n in raw.keys.plus(embedded.keys).sorted()) {
                    val d = obj(n) as? PDict ?: continue
                    if ((d.map["Type"] as? PName)?.name == "Page") result += PageRef(d, dictOf(d.map["Resources"]))
                    if (result.size >= MAX_PAGES) break
                }
            }
            return result
        }

        private fun walk(node: PDict, inherited: PDict?, out: MutableList<PageRef>, seen: MutableSet<PDict>, depth: Int) {
            if (depth > 32 || out.size >= MAX_PAGES || !seen.add(node)) return
            val resources = dictOf(node.map["Resources"]) ?: inherited
            val kids = resolve(node.map["Kids"]) as? PArray
            if (kids != null) {
                for (kid in kids.items) dictOf(kid)?.let { walk(it, resources, out, seen, depth + 1) }
            } else if ((node.map["Type"] as? PName)?.name != "Pages") {
                out += PageRef(node, resources)
            }
        }

        fun text(): String {
            val trailerEncrypt = Regex("/Encrypt\\s").containsMatchIn(s)
            if (trailerEncrypt) throw IllegalStateException("encrypted")
            val out = StringBuilder()
            var pageNo = 0
            for (page in pages()) {
                pageNo++
                val content = contentOf(page.dict) ?: continue
                val fonts = fontsOf(page.resources)
                val pageText = ContentInterpreter(fonts).run(content).trim()
                if (pageText.isEmpty()) continue
                if (out.isNotEmpty()) out.append("\n\n")
                out.append("[Page ").append(pageNo).append("]\n").append(pageText)
                if (out.length > MAX_OUTPUT_CHARS) break
            }
            return out.toString()
                .replace(Regex("[ \\t]+\\n"), "\n")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
        }

        private fun contentOf(page: PDict): String? {
            val c = page.map["Contents"] ?: return null
            val parts = when (val r = resolve(c)) {
                is PArray -> r.items.mapNotNull { streamOf(it) }
                else -> listOfNotNull(streamOf(c))
            }
            if (parts.isEmpty()) return null
            return parts.joinToString("\n") { String(it, Charsets.ISO_8859_1) }
        }

        private fun fontsOf(resources: PDict?): Map<String, Font> {
            val fontDict = dictOf(resources?.map?.get("Font")) ?: return emptyMap()
            return fontDict.map.mapValues { (_, ref) ->
                val font = dictOf(ref)
                val twoByte = (font?.map?.get("Subtype") as? PName)?.name == "Type0"
                val cmap = streamOf(font?.map?.get("ToUnicode"))?.let { CMap.parse(String(it, Charsets.ISO_8859_1)) }
                Font(cmap, twoByte)
            }
        }
    }

    // ================================================================ шрифты

    class Font(val cmap: CMap?, val twoByte: Boolean) {
        fun decode(bytes: ByteArray): String {
            val width = cmap?.codeBytes ?: if (twoByte) 2 else 1
            if (cmap == null) {
                // Составной шрифт без таблицы: коды глифов не переводятся в буквы.
                return if (twoByte) "" else String(bytes, WIN_ANSI)
            }
            val out = StringBuilder()
            var i = 0
            while (i + width <= bytes.size) {
                var code = 0
                for (k in 0 until width) code = (code shl 8) or (bytes[i + k].toInt() and 0xFF)
                cmap.map[code]?.let(out::append)
                i += width
            }
            return out.toString()
        }
    }

    class CMap(val map: Map<Int, String>, val codeBytes: Int) {
        companion object {
            fun parse(text: String): CMap {
                val map = HashMap<Int, String>()
                val space = Regex("begincodespacerange(.*?)endcodespacerange", RegexOption.DOT_MATCHES_ALL).find(text)
                val firstCode = space?.let { Regex("<([0-9A-Fa-f]+)>").find(it.groupValues[1])?.groupValues?.get(1) }
                var codeBytes = firstCode?.let { (it.length + 1) / 2 }?.coerceIn(1, 4) ?: 0

                for (block in Regex("beginbfchar(.*?)endbfchar", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
                    val pairs = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]*)>").findAll(block.groupValues[1])
                    for (p in pairs) {
                        if (codeBytes == 0) codeBytes = (p.groupValues[1].length + 1) / 2
                        val code = p.groupValues[1].toLongOrNull(16)?.toInt() ?: continue
                        map[code] = utf16(p.groupValues[2])
                    }
                }
                for (block in Regex("beginbfrange(.*?)endbfrange", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
                    val body = block.groupValues[1]
                    val range = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*(<[0-9A-Fa-f]*>|\\[[^\\]]*\\])")
                    for (r in range.findAll(body)) {
                        if (codeBytes == 0) codeBytes = (r.groupValues[1].length + 1) / 2
                        val lo = r.groupValues[1].toLongOrNull(16)?.toInt() ?: continue
                        val hi = r.groupValues[2].toLongOrNull(16)?.toInt() ?: continue
                        if (hi < lo || hi - lo > 65_536) continue
                        val dst = r.groupValues[3]
                        if (dst.startsWith("[")) {
                            val items = Regex("<([0-9A-Fa-f]*)>").findAll(dst).map { utf16(it.groupValues[1]) }.toList()
                            for (k in 0..(hi - lo)) items.getOrNull(k)?.let { map[lo + k] = it }
                        } else {
                            val baseHex = dst.trim('<', '>')
                            val base = utf16(baseHex)
                            if (base.isEmpty()) continue
                            // Увеличивается последний символ назначения.
                            val prefix = base.dropLast(1)
                            val last = base.last().code
                            for (k in 0..(hi - lo)) {
                                val cp = last + k
                                if (cp in 0..0xFFFF) map[lo + k] = prefix + cp.toChar()
                            }
                        }
                    }
                }
                return CMap(map, if (codeBytes == 0) 1 else codeBytes)
            }

            private fun utf16(hex: String): String {
                if (hex.isEmpty()) return ""
                val padded = if (hex.length % 4 == 0) hex else hex.padStart((hex.length + 3) / 4 * 4, '0')
                val bytes = ByteArray(padded.length / 2) { padded.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                return String(bytes, Charsets.UTF_16BE)
            }
        }
    }

    // ================================================================ поток страницы

    private class ContentInterpreter(private val fonts: Map<String, Font>) {
        private val out = StringBuilder()
        private var font: Font? = null

        fun run(content: String): String {
            val parser = Parser(content)
            val operands = ArrayList<Value>()
            var guard = 0
            while (!parser.atEnd() && guard++ < 5_000_000) {
                val v = parser.value() ?: break
                if (v is PKeyword) {
                    operator(v.word, operands, parser, content)
                    operands.clear()
                } else {
                    operands += v
                    if (operands.size > 64) operands.removeAt(0)
                }
                if (out.length > MAX_OUTPUT_CHARS) break
            }
            return out.toString()
        }

        private fun operator(op: String, args: List<Value>, parser: Parser, content: String) {
            when (op) {
                "Tf" -> font = (args.firstOrNull() as? PName)?.let { fonts[it.name] }
                "Tj" -> (args.lastOrNull() as? PStr)?.let { show(it) }
                "'", "\"" -> { newLine(); (args.lastOrNull() as? PStr)?.let { show(it) } }
                "TJ" -> (args.lastOrNull() as? PArray)?.items?.forEach { item ->
                    if (item is PStr) {
                        show(item)
                    } else if (item is PNum && item.value < -180) {
                        space()
                    }
                }
                "T*" -> newLine()
                "Td", "TD" -> {
                    val ty = (args.getOrNull(1) as? PNum)?.value ?: 0.0
                    val tx = (args.getOrNull(0) as? PNum)?.value ?: 0.0
                    if (kotlin.math.abs(ty) > 0.01) newLine() else if (tx > 0.01) space()
                }
                "Tm" -> newLine()
                "ET" -> space()
                // Встроенная картинка: двоичные данные до EI пропускаем.
                "ID" -> {
                    val endImage = Regex("\\sEI(?=\\s|$)").find(content, parser.pos)
                    parser.pos = endImage?.range?.last?.plus(1) ?: content.length
                }
            }
        }

        private fun show(str: PStr) {
            val f = font
            val text = f?.decode(str.bytes) ?: String(str.bytes, WIN_ANSI)
            out.append(text.replace('\u0000', ' '))
        }

        private fun space() {
            if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
        }

        private fun newLine() {
            if (out.isNotEmpty() && out.last() != '\n') {
                while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
                out.append('\n')
            }
        }
    }
}
