package com.omnix.assistant.voice.stt

/**
 * Пост-коррекция текста распознавания: чистая функция без Android.
 *
 * Движки (Android SpeechRecognizer, Whisper на сервере, sherpa на телефоне) отдают
 * текст с типичными огрехами. Здесь исправляется только то, что можно исправить
 * надёжно и что ломает команды:
 *  - слова-паразиты и заикание в начале фразы («эээ ну позвони позвони маме»);
 *  - слово вызова «Оми/Омме/омни» → «Омни»;
 *  - «позвони маме» → «позвони мама» (имя контакта в телефонной книге в именительном падеже);
 *  - время и числа словами → цифры для напоминаний («в шесть вечера» → «в 6 вечера»,
 *    «восемнадцать ноль ноль» → «18:00», «через двадцать минут» → «через 20 минут»);
 *    туркменский: «sagat alty otuz» → «sagat 6:30».
 *
 * Функция идемпотентна: повторный вызов на своём же результате ничего не меняет.
 */
object AsrPostProcessor {

    private data class Word(val core: String, val tail: String = "") {
        val lower: String get() = core.lowercase()
        fun text(): String = core + tail
    }

    /** Слова числа: единицы (и 10..19), десятки, слова «ноль». */
    private class Lexicon(
        val units: Map<String, Int>,
        val tens: Map<String, Int>,
        val zeros: Set<String>,
        /** Слова-предлоги, после которых идёт время («в», «sagat»). */
        val clockPreps: Set<String>
    )

    private val RU = Lexicon(
        units = mapOf(
            "один" to 1, "одну" to 1, "одна" to 1, "одно" to 1,
            "два" to 2, "две" to 2, "три" to 3, "четыре" to 4, "пять" to 5,
            "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10,
            "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13, "четырнадцать" to 14,
            "пятнадцать" to 15, "шестнадцать" to 16, "семнадцать" to 17, "восемнадцать" to 18,
            "девятнадцать" to 19
        ),
        tens = mapOf(
            "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50,
            "шестьдесят" to 60, "семьдесят" to 70, "восемьдесят" to 80, "девяносто" to 90
        ),
        zeros = setOf("ноль", "нуль"),
        clockPreps = setOf("в", "к", "до", "после", "около", "ровно", "на")
    )

    private val TK = Lexicon(
        units = mapOf(
            "bir" to 1, "iki" to 2, "üç" to 3, "dört" to 4, "bäş" to 5,
            "alty" to 6, "ýedi" to 7, "sekiz" to 8, "dokuz" to 9
        ),
        tens = mapOf(
            "on" to 10, "ýigrimi" to 20, "otuz" to 30, "kyrk" to 40, "elli" to 50, "altmyş" to 60
        ),
        zeros = setOf("nol"),
        clockPreps = setOf("sagat", "saat")
    )

    private val LEXICONS = listOf(RU, TK)

    private val FILLER = Regex("^(?:э+|э+м+|м+|ну+|вот|а{3,}|ы{2,}|эх|e{2,}|eh|hm+|ýagny)$")

    private val WAKE_VARIANTS = setOf("омни", "оми", "омми", "омме", "омнии", "омней", "омнэ", "омние")
    private const val WAKE_CANONICAL = "Омни"

    private val CALL_VERBS = setOf("позвони", "позвонить", "набери", "вызови", "звони", "дозвонись")

    private val KIN_TO_NOMINATIVE = mapOf(
        "маме" to "мама", "маму" to "мама", "мамы" to "мама",
        "папе" to "папа", "папу" to "папа", "папы" to "папа",
        "бабушке" to "бабушка", "бабушку" to "бабушка",
        "дедушке" to "дедушка", "дедушку" to "дедушка",
        "брату" to "брат", "брата" to "брат",
        "сестре" to "сестра", "сестру" to "сестра",
        "жене" to "жена", "жену" to "жена",
        "мужу" to "муж", "мужа" to "муж",
        "сыну" to "сын", "сына" to "сын",
        "дочке" to "дочка", "дочку" to "дочка", "дочери" to "дочка",
        "тёте" to "тётя", "тете" to "тётя", "тётю" to "тётя",
        "дяде" to "дядя", "дядю" to "дядя"
    )

    /** После числа это единица времени: число превращается в цифры в любом контексте. */
    private val UNIT_WORDS = setOf(
        "час", "часа", "часов", "минуту", "минуты", "минут", "секунду", "секунды", "секунд"
    )
    private val TK_UNIT_PREFIXES = listOf("minut", "sagat", "sekunt")
    private val DAY_PARTS = setOf("утра", "дня", "вечера", "ночи")
    private val NOT_A_CLOCK = setOf("раз", "раза", "штук", "процент", "процента", "процентов")
    private val RU_CLOCK_SINGLE_PREPS = setOf("в", "к", "до", "после", "около", "ровно")
    private val REMINDER_STEMS = listOf("напомн", "будильник", "разбуд")

    private const val MAX_HOUR = 23
    private const val MAX_CLOCK_HOUR = 24
    private const val MAX_MINUTE = 59
    private const val MIN_TWO_DIGIT = 10
    private const val LEADING_WINDOW = 3

    private val WHITESPACE = Regex("\\s+")
    private val TAIL_PUNCTUATION = Regex("^(.*?)([,.!?…;:]+)$")

    fun process(raw: String): String {
        val collapsed = raw.trim().replace(WHITESPACE, " ")
        if (collapsed.isEmpty()) return collapsed
        var words = collapsed.split(' ').map(::toWord)

        words = dropLeadingFillers(words, 0)
        words = normalizeWakeWord(words)
        words = dropLeadingFillers(words, wakeLength(words))
        words = collapseLeadingRepeats(words)
        words = fixCallPhrases(words)
        words = convertNumbers(words)

        if (words.isEmpty()) return collapsed
        val result = words.joinToString(" ") { it.text() }
        return if (collapsed.first().isUpperCase()) result.replaceFirstChar { it.uppercaseChar() } else result
    }

    private fun toWord(token: String): Word {
        val match = TAIL_PUNCTUATION.matchEntire(token)
        return if (match != null && match.groupValues[1].isNotEmpty()) {
            Word(match.groupValues[1], match.groupValues[2])
        } else {
            Word(token)
        }
    }

    private fun wakeLength(words: List<Word>): Int =
        if (words.firstOrNull()?.core == WAKE_CANONICAL) 1 else 0

    /** Выбрасывает слова-паразиты, начиная с позиции [from]; фраза из одних паразитов остаётся как есть. */
    private fun dropLeadingFillers(words: List<Word>, from: Int): List<Word> {
        var index = from
        while (index < words.size && FILLER.matches(words[index].lower)) index++
        if (index == from || index >= words.size) return words
        return words.subList(0, from) + words.subList(index, words.size)
    }

    private fun normalizeWakeWord(words: List<Word>): List<Word> {
        val first = words.firstOrNull() ?: return words
        if (first.lower !in WAKE_VARIANTS) return words
        return listOf(first.copy(core = WAKE_CANONICAL)) + words.drop(1)
    }

    /** «позвони позвони маме» → «позвони маме»: повтор подряд в первых словах. */
    private fun collapseLeadingRepeats(words: List<Word>): List<Word> {
        val out = ArrayList<Word>(words.size)
        for ((index, word) in words.withIndex()) {
            val previous = out.lastOrNull()
            val repeat = index < LEADING_WINDOW && previous != null &&
                word.core.length > 1 && word.core.none { it.isDigit() } &&
                previous.lower == word.lower
            if (repeat) out[out.size - 1] = word else out += word
        }
        return out
    }

    private fun fixCallPhrases(words: List<Word>): List<Word> {
        val start = wakeLength(words)
        val verb = words.getOrNull(start)?.lower ?: return words
        // Туркменский: «jan et» → «jaň et».
        if (verb == "jan" && words.getOrNull(start + 1)?.lower == "et") {
            return words.mapIndexed { i, w -> if (i == start) w.copy(core = restoreCase(w.core, "jaň")) else w }
        }
        if (verb !in CALL_VERBS || words.size != start + 2) return words
        val target = words[start + 1]
        val nominative = KIN_TO_NOMINATIVE[target.lower] ?: return words
        return words.dropLast(1) + target.copy(core = restoreCase(target.core, nominative))
    }

    private fun restoreCase(original: String, replacement: String): String =
        if (original.first().isUpperCase()) replacement.replaceFirstChar { it.uppercaseChar() } else replacement

    // ------------------------------------------------------------------- числа

    private fun convertNumbers(words: List<Word>): List<Word> {
        val reminderContext = words.any { w -> REMINDER_STEMS.any { w.lower.startsWith(it) } }
        val out = ArrayList<Word>(words.size)
        var i = 0
        while (i < words.size) {
            val converted = convertAt(words, i, out.lastOrNull(), reminderContext)
            if (converted == null) {
                out += words[i]
                i++
            } else {
                out += converted.first
                i += converted.second
            }
        }
        return out
    }

    /** Замена слов числа в позиции [i]: слово и сколько слов съедено, либо null. */
    private fun convertAt(words: List<Word>, i: Int, previous: Word?, reminderContext: Boolean): Pair<Word, Int>? {
        val lexicon = LEXICONS.firstOrNull { isNumberStart(it, words[i].lower) } ?: return null
        val prepOk = previous != null && previous.tail.isEmpty() && previous.lower in lexicon.clockPreps

        clockPair(words, i, lexicon, prepOk)?.let { return it }

        val number = parseNumber(words, i, lexicon) ?: return null
        val value = number.first
        val count = number.second
        val last = words[i + count - 1]
        val next = words.getOrNull(i + count)?.lower.orEmpty()
        val prevLower = previous?.lower.orEmpty()

        val isUnitNext = next.isNotEmpty() &&
            (if (lexicon === RU) next in UNIT_WORDS else TK_UNIT_PREFIXES.any { next.startsWith(it) })
        val isDayPartClock = lexicon === RU && prepOk && prevLower in RU_CLOCK_SINGLE_PREPS &&
            next in DAY_PARTS && value <= MAX_CLOCK_HOUR
        val isBareClock = if (lexicon === RU) {
            prepOk && prevLower in RU_CLOCK_SINGLE_PREPS && reminderContext &&
                value in 1..MAX_CLOCK_HOUR && next !in NOT_A_CLOCK
        } else {
            prepOk && value <= MAX_CLOCK_HOUR
        }
        if (!isUnitNext && !isDayPartClock && !isBareClock) return null
        return Word(value.toString(), last.tail) to count
    }

    /** «шесть тридцать», «восемнадцать ноль ноль» → «6:30», «18:00». */
    private fun clockPair(words: List<Word>, i: Int, lexicon: Lexicon, prepOk: Boolean): Pair<Word, Int>? {
        val hourWord = words[i]
        val hour: Int
        val hourCount: Int
        if (hourWord.lower in lexicon.zeros) {
            hour = 0
            hourCount = 1
        } else {
            val parsed = parseNumber(words, i, lexicon) ?: return null
            hour = parsed.first
            hourCount = parsed.second
        }
        if (hour > MAX_HOUR || words[i + hourCount - 1].tail.isNotEmpty()) return null
        val j = i + hourCount
        val minuteWord = words.getOrNull(j) ?: return null

        val minute: Int
        val minuteCount: Int
        val zeroLed = minuteWord.lower in lexicon.zeros
        if (zeroLed) {
            val third = words.getOrNull(j + 1) ?: return null
            if (third.lower in lexicon.zeros) {
                minute = 0
            } else {
                val digit = parseNumber(words, j + 1, lexicon) ?: return null
                if (digit.second != 1 || digit.first >= MIN_TWO_DIGIT) return null
                minute = digit.first
            }
            minuteCount = 2
        } else {
            val parsed = parseNumber(words, j, lexicon) ?: return null
            if (parsed.first !in MIN_TWO_DIGIT..MAX_MINUTE) return null
            minute = parsed.first
            minuteCount = parsed.second
        }
        if (!prepOk && !zeroLed) return null
        val last = words[j + minuteCount - 1]
        val text = hour.toString() + ":" + minute.toString().padStart(2, '0')
        return Word(text, last.tail) to (hourCount + minuteCount)
    }

    private fun isNumberStart(lexicon: Lexicon, word: String): Boolean =
        word in lexicon.units || word in lexicon.tens || word in lexicon.zeros

    /** Число 1..99 словами с позиции [i]: значение и число слов. «ноль» сюда не входит. */
    private fun parseNumber(words: List<Word>, i: Int, lexicon: Lexicon): Pair<Int, Int>? {
        val word = words[i]
        val ten = lexicon.tens[word.lower]
        if (ten != null) {
            val unit = words.getOrNull(i + 1)
                ?.takeIf { word.tail.isEmpty() }
                ?.let { lexicon.units[it.lower] }
            if (unit != null && unit in 1..9) return (ten + unit) to 2
            return ten to 1
        }
        val unit = lexicon.units[word.lower] ?: return null
        return unit to 1
    }
}
