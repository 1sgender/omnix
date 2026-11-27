package com.omnix.assistant.voice.offline

/**
 * Текст модели Whisper на телефоне → нормальный текст.
 *
 * 1. sherpa-onnx теряет куски слова, которые модель выдаёт половинками UTF-8 символа (туркменская
 *    «ň» = C5 88 часто приходит двумя токенами), и из «gürrüň» получалось «gürrü». Поэтому в
 *    tokens.txt пакета каждый такой байт записан символом U+E000+байт; здесь подряд идущие
 *    символы этого диапазона собираются обратно в байты и декодируются.
 * 2. Whisper иногда зацикливается на шуме («we we we we …») — повтор слова или пары слов больше
 *    [MAX_REPEATS] раз подряд обрезается.
 */
object WhisperText {

    private const val BYTE_BASE = 0xE000
    private const val BYTE_LAST = 0xE0FF
    const val MAX_REPEATS = 3
    private const val MAX_PHRASE_WORDS = 3

    fun clean(raw: String): String = collapseRepeats(restoreBytes(raw)).trim()

    fun restoreBytes(raw: String): String {
        if (raw.none { it.code in BYTE_BASE..BYTE_LAST }) return raw
        val out = StringBuilder(raw.length)
        val bytes = java.io.ByteArrayOutputStream()
        fun flush() {
            if (bytes.size() == 0) return
            out.append(String(bytes.toByteArray(), Charsets.UTF_8).replace("�", ""))
            bytes.reset()
        }
        for (c in raw) {
            if (c.code in BYTE_BASE..BYTE_LAST) {
                bytes.write(c.code - BYTE_BASE)
            } else {
                flush()
                out.append(c)
            }
        }
        flush()
        return out.toString()
    }

    /** Убирает зацикливание: группа из 1–3 слов, повторённая больше [MAX_REPEATS] раз подряд. */
    fun collapseRepeats(text: String): String {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.size <= MAX_REPEATS) return text
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            var skipped = false
            for (n in 1..MAX_PHRASE_WORDS) {
                if (i + n > words.size) break
                val group = words.subList(i, i + n)
                var reps = 1
                while (i + (reps + 1) * n <= words.size &&
                    words.subList(i + reps * n, i + (reps + 1) * n) == group
                ) reps++
                if (reps > MAX_REPEATS) {
                    out.addAll(group)
                    i += reps * n
                    skipped = true
                    break
                }
            }
            if (!skipped) {
                out.add(words[i])
                i++
            }
        }
        return out.joinToString(" ")
    }
}
