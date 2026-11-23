package com.omnix.assistant.agent.tools.communication

import java.text.Normalizer
import java.util.Locale

/**
 * Транслитерация и нормализация имён для поиска контактов. Чистый Kotlin, без Android.
 *
 * Идея: имя в любом алфавите (русский, туркменский, турецкий, узбекский, казахский,
 * латиница) приводится к «ключу сравнения» — грубому фонетическому скелету. Если у двух
 * написаний («Осман» и «Osman», «Юсуф», «Yusuf», «Jusuf», «Iusuf») совпал хотя бы один
 * ключ, это одно и то же слово. Неоднозначные буквы (ä, j, c) дают несколько ключей,
 * поэтому ключей у слова — множество, а сравнение идёт по пересечению.
 */
object NameTranslit {

    private const val MAX_VARIANTS = 16
    private const val MIN_STEM_LEN = 3

    private const val VOWELS = "aeiouä"
    private const val FRONT_VOWELS = "eiyä"
    private const val APOSTROPHES = "'‘’ʻʼ`´"

    private val CYR_TO_LAT: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e",
        'ё' to "yo", 'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k",
        'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts",
        'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "",
        'э' to "e", 'ю' to "yu", 'я' to "ya",
        // туркменский / казахский / узбекский / украинский кириллицей
        'ә' to "ä", 'ғ' to "g", 'қ' to "q", 'ң' to "n", 'ө' to "o", 'ү' to "u",
        'ұ' to "u", 'һ' to "h", 'і' to "i", 'ў' to "o", 'ҳ' to "h", 'җ' to "dzh",
        'є' to "ye", 'ї' to "yi", 'ґ' to "g", 'ѳ' to "f"
    )

    private val LATIN_SPECIAL: Map<Char, String> = mapOf(
        'ä' to "ä", 'æ' to "ä", 'ə' to "ä", 'ö' to "o", 'ø' to "o", 'ü' to "u",
        'ç' to "ch", 'č' to "ch", 'ş' to "sh", 'š' to "sh", 'ğ' to "g", 'ı' to "i",
        'ž' to "zh", 'ň' to "n", 'ý' to "y", 'ß' to "ss", 'ł' to "l", 'đ' to "d"
    )

    /** Многобуквенные сочетания латиницы → маркер класса звука (длинные идут первыми). */
    private val DIGRAPHS: List<Pair<String, String>> = listOf(
        "shch" to "S", "dzh" to "J", "sch" to "S", "sh" to "S", "ch" to "C",
        "zh" to "Z", "kh" to "h", "gh" to "g", "ph" to "f", "th" to "t",
        "ts" to "T", "tz" to "T", "dj" to "J", "ck" to "k"
    )

    private val CASE_ENDINGS = listOf(
        "ой", "ей", "ою", "ею", "ом", "ем", "ём", "ам", "ям", "ға", "ге", "ғе", "қа", "ке",
        "а", "я", "у", "ю", "е", "ы", "и", "о"
    )

    /** Тюркские падежные суффиксы в латинской записи: «Osman'a», «Osmanga», «Osmana». */
    private val LATIN_ENDINGS = listOf("ga", "ka", "qa", "na", "ne", "ya", "ye", "da", "de", "a", "e")

    /** Слова, которые не являются частью имени: титулы и обращения. */
    private val TITLE_KEYS: Set<String> by lazy {
        listOf(
            "доктор", "dr", "doktor", "doctor", "врач", "ака", "ага", "эже", "hoca", "hoja",
            "ходжа", "bey", "бей", "hanım", "hanim", "ханым", "ханум", "hanym", "hanum",
            "khanum", "apa", "апа", "abla", "абла", "abi", "аби", "mr", "mrs", "ms",
            "господин", "госпожа", "дядя", "тётя", "тетя", "uncle"
        ).flatMap { keys(it) }.toSet()
    }

    /** Разбивает строку на слова-имена: эмодзи, скобки и знаки отбрасываются, регистр понижен. */
    fun tokenize(text: String): List<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                tokens += current.toString()
                current.clear()
            }
        }
        for (ch in normalized) {
            when {
                ch in APOSTROPHES -> Unit
                Character.isLetter(ch) -> current.append(ch)
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> Unit
                else -> flush()
            }
        }
        flush()
        return tokens
    }

    fun isTitle(token: String): Boolean = stemKeys(token).any { it in TITLE_KEYS }

    /** Ключи сравнения слова (одно или несколько, если написание неоднозначно). */
    fun keys(token: String): Set<String> = fold(toLatin(token))

    /**
     * Кандидаты основы для падежей: «осману» → «осману», «осман»; «osmanga» → «osman».
     * Основа не короче трёх букв.
     */
    fun caseStems(token: String): List<String> {
        val stems = mutableListOf(token)
        val endings = if (token.any { CYR_TO_LAT.containsKey(it) }) CASE_ENDINGS else LATIN_ENDINGS
        for (ending in endings) {
            if (token.endsWith(ending) && token.length - ending.length >= MIN_STEM_LEN) {
                stems += token.dropLast(ending.length)
            }
        }
        return stems.distinct()
    }

    /** Ключи слова вместе с ключами всех его падежных основ. */
    fun stemKeys(token: String): Set<String> = caseStems(token).flatMap { keys(it) }.toSet()

    /** Совпадают ли два слова независимо от алфавита. */
    fun sameWord(a: String, b: String): Boolean {
        val ka = keys(a)
        return keys(b).any { it in ka }
    }

    /** Основное латинское написание слова (кириллица → латиница), для показа и запросов. */
    fun toLatinDisplay(word: String): String = capitalize(toLatin(word.lowercase(Locale.ROOT)).replace('ä', 'a'))

    /** Несколько вариантов латинского написания слова (Юсуф → Yusuf, Iusuf, Jusuf). */
    fun latinVariants(word: String): List<String> {
        val lower = word.lowercase(Locale.ROOT)
        if (lower.none { CYR_TO_LAT.containsKey(it) }) return listOf(capitalize(lower))
        val primary = toLatin(lower).replace('ä', 'a')
        val gost = lower.map { ch ->
            when (ch) {
                'й' -> "i"
                'ю' -> "iu"
                'я' -> "ia"
                'ё' -> "e"
                'х' -> "kh"
                'ъ', 'ь' -> ""
                else -> CYR_TO_LAT[ch] ?: ch.toString()
            }
        }.joinToString("").replace('ä', 'a')
        val simple = primary.replace("kh", "h").replace("ph", "f")
        val jVariant = primary.replace(Regex("^y(?=[aeiou])"), "j")
        return listOf(primary, gost, simple, jVariant).map { capitalize(it) }.distinct()
    }

    /** Несколько вариантов кириллического написания латинского слова (Osman → Осман). */
    fun cyrillicVariants(word: String): List<String> {
        val lower = Normalizer.normalize(word, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        if (lower.none { it in 'a'..'z' || it in LATIN_SPECIAL.keys }) return listOf(capitalize(lower))
        var partials: List<String> = listOf("")
        var i = 0
        while (i < lower.length) {
            val rest = lower.substring(i)
            val (consumed, options) = latinToCyr(rest, prevWasVowel(partials))
            partials = partials.flatMap { p -> options.map { p + it } }.distinct().take(MAX_VARIANTS)
            i += consumed
        }
        return partials.map { capitalize(it) }.take(MAX_VARIANTS)
    }

    private fun prevWasVowel(partials: List<String>): Boolean =
        partials.firstOrNull()?.lastOrNull()?.let { it in "аеёиоуыэюя" } == true

    private val LAT_TO_CYR_DIGRAPHS = listOf(
        "shch" to listOf("щ"), "dzh" to listOf("дж"), "sh" to listOf("ш"), "ch" to listOf("ч"),
        "zh" to listOf("ж"), "kh" to listOf("х"), "ts" to listOf("ц"), "yu" to listOf("ю"),
        "ya" to listOf("я"), "yo" to listOf("ё", "ьо"), "ye" to listOf("е"), "ph" to listOf("ф"),
        "th" to listOf("т"), "ck" to listOf("к")
    )

    private val LAT_TO_CYR_SINGLE: Map<Char, List<String>> = mapOf(
        'a' to listOf("а"), 'b' to listOf("б"), 'c' to listOf("к", "с"), 'd' to listOf("д"),
        'e' to listOf("е"), 'f' to listOf("ф"), 'g' to listOf("г"), 'h' to listOf("х"),
        'i' to listOf("и"), 'j' to listOf("дж", "й", "ж"), 'k' to listOf("к"), 'l' to listOf("л"),
        'm' to listOf("м"), 'n' to listOf("н"), 'o' to listOf("о"), 'p' to listOf("п"),
        'q' to listOf("к"), 'r' to listOf("р"), 's' to listOf("с"), 't' to listOf("т"),
        'u' to listOf("у"), 'v' to listOf("в"), 'w' to listOf("в"), 'x' to listOf("кс", "х"),
        'z' to listOf("з"), 'ä' to listOf("е", "а"), 'ö' to listOf("о"), 'ü' to listOf("у"),
        'ç' to listOf("ч"), 'ş' to listOf("ш"), 'ğ' to listOf("г"), 'ı' to listOf("ы"),
        'ž' to listOf("ж"), 'ň' to listOf("н"), 'š' to listOf("ш"), 'č' to listOf("ч")
    )

    private fun latinToCyr(rest: String, prevVowel: Boolean): Pair<Int, List<String>> {
        for ((lat, cyr) in LAT_TO_CYR_DIGRAPHS) {
            if (rest.startsWith(lat)) return lat.length to cyr
        }
        val c = rest[0]
        if (c == 'y') return 1 to (if (prevVowel) listOf("й") else listOf("ы", "и", "й"))
        if (c in APOSTROPHES) return 1 to listOf("")
        return 1 to (LAT_TO_CYR_SINGLE[c] ?: listOf(c.toString()))
    }

    private fun capitalize(s: String): String =
        if (s.isEmpty()) s else s.substring(0, 1).uppercase(Locale.ROOT) + s.substring(1)

    /** Любое слово → строка латиницей в нижнем регистре (с маркером ä для ә/ä). */
    private fun toLatin(token: String): String {
        val sb = StringBuilder()
        for (raw in Normalizer.normalize(token, Normalizer.Form.NFC).lowercase(Locale.ROOT)) {
            val cyr = CYR_TO_LAT[raw]
            when {
                cyr != null -> sb.append(cyr)
                raw in APOSTROPHES -> Unit
                LATIN_SPECIAL.containsKey(raw) -> sb.append(LATIN_SPECIAL.getValue(raw))
                raw.code in 0xC0..0x24F || raw.code in 0x1E00..0x1EFF -> sb.append(stripMarks(raw))
                raw in 'a'..'z' -> sb.append(raw)
                Character.isLetter(raw) -> sb.append(raw)
                else -> Unit
            }
        }
        return sb.toString()
    }

    private fun stripMarks(ch: Char): String =
        Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    private fun toUnits(latin: String): List<String> {
        val units = mutableListOf<String>()
        var i = 0
        while (i < latin.length) {
            val digraph = DIGRAPHS.firstOrNull { latin.startsWith(it.first, i) }
            if (digraph != null) {
                units += digraph.second
                i += digraph.first.length
            } else {
                units += latin[i].toString()
                i++
            }
        }
        return units
    }

    private fun isVowelUnit(unit: String?): Boolean = unit != null && unit.length == 1 && unit[0] in VOWELS

    private fun options(unit: String, idx: Int, prev: String?, next: String?): List<String> = when (unit) {
        "ä" -> listOf("a", "e")
        // j: звук «й» (Jusuf), «дж» (John) или «ж» (туркменское/французское)
        "j" -> listOf("", "J", "Z")
        "c" -> if (next != null && next.length == 1 && next[0] in FRONT_VOWELS) listOf("T", "J") else listOf("k")
        "x" -> if (idx == 0) listOf("h") else listOf("ks")
        "q" -> listOf("k")
        "w" -> listOf("v")
        // h после гласной перед согласной/концом немой или слабый: «John» = «Джон», «Ahmet» = «Ахмед»
        "h" -> if (idx > 0 && isVowelUnit(prev) && !isVowelUnit(next)) listOf("") else listOf("h")
        "y" -> if (isVowelUnit(prev) || isVowelUnit(next)) listOf("") else listOf("i")
        "i" -> if (isVowelUnit(prev) || (prev == null && isVowelUnit(next))) listOf("") else listOf("i")
        else -> listOf(unit)
    }

    private fun fold(latin: String): Set<String> {
        val units = toUnits(latin)
        var partials: List<String> = listOf("")
        for (idx in units.indices) {
            val opts = options(units[idx], idx, units.getOrNull(idx - 1), units.getOrNull(idx + 1))
            partials = if (opts.size == 1) {
                partials.map { it + opts[0] }
            } else {
                partials.flatMap { p -> opts.map { p + it } }.distinct().take(MAX_VARIANTS)
            }
        }
        return partials.map { collapse(it) }.filter { it.isNotEmpty() }.map { devoiceFinal(it) }.toSet()
    }

    /** Конечные d/b/g оглушаются: «Ahmed» = «Ahmet» = «Ахмед». */
    private fun devoiceFinal(s: String): String = when (s.last()) {
        'd' -> s.dropLast(1) + 't'
        'b' -> s.dropLast(1) + 'p'
        'g' -> s.dropLast(1) + 'k'
        else -> s
    }

    private fun collapse(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            if (sb.isEmpty() || sb.last() != ch) sb.append(ch)
        }
        return sb.toString()
    }
}
