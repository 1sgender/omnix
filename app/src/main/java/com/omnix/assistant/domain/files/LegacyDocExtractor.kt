package com.omnix.assistant.domain.files

/**
 * Старый Word (.doc, двоичный OLE). Полный разбор формата требует Apache POI;
 * здесь — приближение: Word 97+ хранит текст документа в UTF-16LE или в
 * однобайтовой кодировке, поэтому берём длинные непрерывные участки читаемых
 * символов. Служебные строки (имена шрифтов, стилей) короткие и отсеиваются.
 */
object LegacyDocExtractor {

    private const val MIN_RUN_CHARS = 12

    fun extract(bytes: ByteArray): String? {
        val utf16 = utf16Runs(bytes)
        val text = if (utf16.length >= 40) utf16 else singleByteRuns(bytes)
        return text.trim().ifEmpty { null }
    }

    private fun isReadable(c: Char): Boolean =
        c.isLetterOrDigit() || c == ' ' || c in ".,;:!?-–—()«»\"'%/№+=*&@#[]\t" || c == '\r' || c == '\n'

    private fun utf16Runs(bytes: ByteArray): String {
        // Пробуем оба выравнивания: текст может начинаться с нечётного байта.
        val best = (0..1).map { offset ->
            val runs = StringBuilder()
            val current = StringBuilder()
            var i = offset
            while (i + 1 < bytes.size) {
                val c = ((bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)).toChar()
                if (isReadable(c)) {
                    current.append(c)
                } else {
                    flush(current, runs)
                }
                i += 2
            }
            flush(current, runs)
            runs.toString()
        }.maxByOrNull { it.length }.orEmpty()
        return normalize(best)
    }

    private fun singleByteRuns(bytes: ByteArray): String {
        val text = String(bytes, charset("windows-1251").takeIf { looksCyrillic(bytes) } ?: Charsets.ISO_8859_1)
        val runs = StringBuilder()
        val current = StringBuilder()
        for (c in text) {
            if (isReadable(c)) current.append(c) else flush(current, runs)
        }
        flush(current, runs)
        return normalize(runs.toString())
    }

    /** Много байтов 0xC0–0xFF подряд с пробелами — похоже на кириллицу в cp1251. */
    private fun looksCyrillic(bytes: ByteArray): Boolean {
        val high = bytes.count { (it.toInt() and 0xFF) in 0xC0..0xFF }
        return high > bytes.size / 50
    }

    private fun flush(current: StringBuilder, runs: StringBuilder) {
        val run = current.toString()
        val letters = run.count { it.isLetter() }
        if (run.length >= MIN_RUN_CHARS && letters * 2 >= run.length && run.contains(' ')) {
            runs.append(run).append('\n')
        }
        current.setLength(0)
    }

    private fun normalize(text: String): String = text
        .replace('\r', '\n')
        .replace(Regex("[ \t]+\n"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
}
