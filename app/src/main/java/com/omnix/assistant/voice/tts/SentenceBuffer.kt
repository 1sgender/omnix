package com.omnix.assistant.voice.tts

/**
 * Буферизация потоковых токенов LLM до границы предложения (§13 ТЗ).
 *
 * Чистый класс без Android-зависимостей: токены скармливаются через [push],
 * готовые предложения уходят в [onSentence] сразу, не дожидаясь конца
 * генерации. Остаток без терминатора отдаётся через [flush] в конце.
 *
 * Защита от ложных границ:
 * - сокращения (RU/EN, включая многоточечные «т.е.» / «e.g.»);
 * - инициалы («А. Пушкин»);
 * - десятичные числа («3.14», «версия 1.0»).
 *
 * Контракт [onSentence]: вызывается синхронно из [push]/[flush], обязан не
 * бросать исключений (TTS-приёмник — fire-and-forget). Пустые предложения
 * не эмиттятся. Класс НЕ потокобезопасен: токены одного инференса идут
 * строго последовательно из одного потока генерации.
 */
class SentenceBuffer(
    private val onSentence: (String) -> Unit,
    private val maxBufferedChars: Int = 600
) {
    private val buf = StringBuilder()

    private companion object {
        /** Сокращения со строчной буквы + точка (сверяются в нижнем регистре). */
        val ABBREVIATIONS = setOf(
            // Русские.
            "т.е.", "т.д.", "т.к.", "т.н.", "напр.", "др.", "пр.", "см.",
            "г.", "ул.", "д.", "кв.", "стр.", "эт.", "обл.", "р-н",
            "тыс.", "млн.", "млрд.", "руб.", "коп.", "шт.", "экз.",
            "акад.", "проф.", "доц.", "инж.", "тов.",
            // Английские.
            "mr.", "mrs.", "ms.", "dr.", "st.", "vs.", "etc.", "e.g.", "i.e.",
            "jr.", "sr.", "no.", "fig.", "pp.", "p.", "ex.", "approx.",
            "jan.", "feb.", "mar.", "apr.", "jun.", "jul.", "aug.",
            "sept.", "sep.", "oct.", "nov.", "dec.",
            "mon.", "tue.", "tues.", "wed.", "thu.", "thur.", "fri.", "sat.", "sun."
        )

        /** Закрывающие символы, прилипающие к концу предложения. */
        const val CLOSERS = "\"\"»')]}›」』"

        /** Максимальная длина токена сокращения для обратного сканирования. */
        const val MAX_ABBR_LOOKBACK = 8
    }

    /** Скормить очередной токен; готовые предложения уйдут в [onSentence]. */
    fun push(token: String) {
        if (token.isEmpty()) return
        buf.append(token)
        drain()
    }

    /** Отдать накопленный остаток (вызывать в конце генерации). */
    fun flush() {
        val rest = buf.toString().trim()
        buf.clear()
        if (rest.isNotEmpty()) onSentence(rest)
    }

    /**
     * Сбросить неозвученный остаток БЕЗ эмита (barge-in/отмена генерации):
     * недосказанное не должно договариваться позднее.
     */
    fun reset() {
        buf.clear()
    }

    /** Текущий неосвобождённый остаток (для диагностики, не для UI). */
    fun bufferedLength(): Int = buf.length

    private fun drain() {
        while (true) {
            val end = findBoundary() ?: break
            val sentence = buf.substring(0, end).trim()
            buf.delete(0, end)
            trimLeadingWhitespace()
            if (sentence.isNotEmpty()) onSentence(sentence)
        }
        // Аварийный клапан: списки/код без терминаторов не должны растить
        // буфер бесконечно — отдаём как есть, TTS прочитает куском.
        if (buf.length > maxBufferedChars) {
            val forced = buf.toString().trim()
            buf.clear()
            if (forced.isNotEmpty()) onSentence(forced)
        }
    }

    /**
     * Индекс конца первого готового предложения (exclusive) или null.
     * Многоточие «...» и «…» считаются одним терминатором.
     */
    private fun findBoundary(): Int? {
        var i = 0
        while (i < buf.length) {
            val c = buf[i]
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                // Поглощаем серии точек («...», «?!», «!!») как один терминатор.
                var j = i + 1
                while (j < buf.length && (buf[j] == '.' || buf[j] == '!' || buf[j] == '?')) j++
                // Прилипшие закрывающие кавычки/скобки — часть предложения.
                while (j < buf.length && CLOSERS.contains(buf[j])) j++
                val atEnd = j >= buf.length
                val followedBySpace = !atEnd && buf[j].isWhitespace()
                if ((atEnd || followedBySpace) && !isFalseBoundary(i)) {
                    return j
                }
                i = j
                continue
            }
            i++
        }
        return null
    }

    /**
     * Терминатор в позиции [dotIndex] — ложный (сокращение/инициал/число)?
     * Проверяется только для точки: «!», «?» и «…» ложными не бывают.
     */
    private fun isFalseBoundary(dotIndex: Int): Boolean {
        if (buf[dotIndex] != '.') return false
        // Десятичное число: цифра с обеих сторон («3.14»).
        val prev = if (dotIndex > 0) buf[dotIndex - 1] else ' '
        val next = if (dotIndex + 1 < buf.length) buf[dotIndex + 1] else ' '
        if (prev.isDigit() && next.isDigit()) return true
        // Слово перед точкой (назад до пробела, максимум MAX_ABBR_LOOKBACK).
        var start = dotIndex - 1
        var scanned = 0
        while (start >= 0 && !buf[start].isWhitespace() && scanned < MAX_ABBR_LOOKBACK) {
            start--
            scanned++
        }
        val word = buf.substring(start + 1, dotIndex + 1).lowercase()
        if (word in ABBREVIATIONS) return true
        // Инициал: одиночная буква + точка («А. Пушкин», «J. Smith»).
        if (word.length == 2 && word[0].isLetter()) return true
        return false
    }

    private fun trimLeadingWhitespace() {
        var i = 0
        while (i < buf.length && buf[i].isWhitespace()) i++
        if (i > 0) buf.delete(0, i)
    }
}
