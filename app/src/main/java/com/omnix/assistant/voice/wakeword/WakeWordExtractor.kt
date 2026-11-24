package com.omnix.assistant.voice.wakeword

/**
 * Выделение пользовательского запроса из voice-строки, содержащей wake-word.
 *
 * CR-02: после удаления wake-word пустой результат НЕ должен возвращаться
 * как исходная строка — иначе «Джарвис» попадает в processUserQuery() и
 * провоцирует двойную обработку / паразитные AI-вызовы / неожиданные TTS.
 *
 * Ранее реализация была приватным методом `cleanWakeWord()` в
 * VoiceInteractionOrchestrator и возвращала `result.ifEmpty { raw }`, что
 * было корнем бага. Теперь:
 *  - `stripWakeWord(raw)` возвращает очищенный текст ИЛИ null, если после
 *    вырезания wake-word ничего не осталось (с учётом пунктуации);
 *  - список wake-words разделён на конфигурацию и логику, что удобно для
 *    тестирования.
 */
object WakeWordExtractor {

    /**
     * Шум вокруг wake-word после STT: пробелы и пунктуация, включая
     * Unicode-тире и кавычки, которые не покрывает ASCII-класс `\p{Punct}`
     * (баг: «Джарвис — сколько времени» оставлял «—» в запросе).
     */
    private const val NOISE = "[\\s\\p{Punct}—–…«»„“”‘’]"

    /** Стандартные wake-words для русского/английского активации. */
    val DEFAULT_WAKE_WORDS: List<String> = listOf(
        "омни", "omni", "омникс",
        "джарвис", "omnix", "жарвис", "дарвис", "джей", "диджей", "джар"
    )

    /**
     * Семейство слова вызова «Омни». Эти записи списка обрабатываются не
     * «любой префикс + подстрока», как старые, а строго по началу фразы с
     * границей слова (см. [OMNI_START]): «помни», «омлет», «омнибус»,
     * «амнистия» не должны срезаться.
     */
    private val OMNI_KEYS = setOf("омни", "omni", "омникс")

    /**
     * Слово вызова и типичные огрехи распознавателей на 6 языках: русский
     * «омни/омникс/оми/омме/омми/оммни/омние/омнии/омней/омнэ/омнис», латиница
     * «omni/omnix/omny/omnee/omnii», туркменское/турецкое/узбекское «ömni/omnı»,
     * казахское/узбекское кириллическое «оумни». Более длинные варианты
     * стоят раньше коротких. Ударение U+0301 («омни́») убирается заранее.
     */
    private const val OMNI_BODY =
        "(?:омникс|омнис|омнии|омние|омней|омнэ|омни|оммни|омми|омме|оми|оумни|" +
            "omnix|ömnix|omnii|omnee|omny|omni|ömni|omnı)"

    /** Междометие/«окей» перед словом вызова: «окей омни», «okay omni», «hey omni», «эй омни». */
    private const val OMNI_LEAD =
        "(?:окей|окэй|океи|оки|ок|okay|okey|ok|hey|hi|эй|хэй|хей|ей|ey|hej|tamam)"

    /**
     * Дефис сразу после слова вызова: «омни-канальная стратегия» это одно составное
     * слово, а не обращение к ассистенту («омни, — привет» с пробелом/запятой
     * по-прежнему обращение).
     */
    private const val HYPHEN = "[-\\u2010\\u2011]"

    /**
     * Начало фразы: [NOISE]* [«окей» NOISE+] слово вызова, затем граница слова.
     * «умни» допустимо только после «окей»: русская модель слышит «okay omni»
     * как «окей умни(й)», а сам по себе «умни» это не слово вызова.
     */
    private val OMNI_START = Regex(
        "(?iu)^$NOISE*(?:(?:$OMNI_LEAD)$NOISE+(?:$OMNI_BODY|умни)|$OMNI_BODY)(?![\\p{L}\\p{N}]|$HYPHEN\\p{L})$NOISE*"
    )

    private const val ACUTE = "\u0301"
    private const val OMNI_REPEAT_LIMIT = 2

    /** Строка состоит только из шума (пунктуация/пробелы) — команды нет. */
    private val noiseOnly = Regex("^$NOISE*$")

    /**
     * Удаляет wake-word из начала строки.
     *
     * @return очищенный запрос или null, если после вырезания wake-word
     *         осталась только пунктуация/пробелы (т.е. распознано ТОЛЬКО
     *         имя ассистента без команды).
     */
    fun extractQuery(
        raw: String,
        wakeWords: List<String> = DEFAULT_WAKE_WORDS
    ): String? {
        val cleaned = strip(raw, wakeWords)
        // CR-02: если после вырезания wake-word остались только пунктуация и
        // пробелы — команды нет: null вместо исходной строки/мусора.
        return cleaned.takeUnless { noiseOnly.matches(it) }
    }

    /**
     * @return true, если строка начинается (с точностью до пунктуации/пробелов
     * и одного короткого междометия из STT вроде «эй, джарвис») с любого из
     * wake-words.
     */
    fun containsWakeWord(
        text: String,
        wakeWords: List<String> = DEFAULT_WAKE_WORDS
    ): Boolean {
        val lower = text.replace(ACUTE, "").lowercase().trim()
        if (lower.isEmpty()) return false
        if (omniEnabled(wakeWords) && OMNI_START.containsMatchIn(lower)) return true
        val legacy = legacyWords(wakeWords)
        if (legacy.isEmpty()) return false
        val alternatives = legacy.joinToString("|") { Regex.escape(it) }
        // CR-02: prefix-only match — иначе ложные срабатывания на слова вроде
        // «жарко» (содержит «жар») или wake-word в середине фразы
        // («привет Джарвис…»). Междометие-допуск ограничен 1–2 буквами: «эй»,
        // «ну», «а» — реальные перекрикивания распознавателя; длинные слова
        // («привет», «как») междометиями не считаются и не активируют ассистента.
        return Regex("^$NOISE*(?:[a-zа-яё]{1,2}$NOISE+)?(?:$alternatives)")
            .containsMatchIn(lower)
    }

    private fun omniEnabled(wakeWords: List<String>): Boolean =
        wakeWords.any { it.lowercase() in OMNI_KEYS }

    /** Старые слова вызова: без записей семейства «Омни», у них своя строгая логика. */
    private fun legacyWords(wakeWords: List<String>): List<String> =
        wakeWords.filter { it.lowercase() !in OMNI_KEYS }

    /**
     * «Омни, позвони маме» → «позвони маме»; «Окей, Омни! открой ютуб» →
     * «открой ютуб». Только в начале фразы и только целое слово. null, если
     * слова вызова в начале нет.
     */
    private fun stripOmni(raw: String): String? {
        var result = raw.replace(ACUTE, "")
        var matched = false
        repeat(OMNI_REPEAT_LIMIT) {
            val m = OMNI_START.find(result)
            if (m != null) {
                result = result.substring(m.range.last + 1).trim()
                matched = true
            }
        }
        return if (matched) result else null
    }

    private fun strip(raw: String, wakeWords: List<String>): String {
        if (omniEnabled(wakeWords)) stripOmni(raw)?.let { return it }
        var result = raw
        for (kw in legacyWords(wakeWords)) {
            // Удаляем wake-word в начале строки вместе с прилегающей пунктуацией
            // и пробелами («Джарвис, сколько времени» → «сколько времени»,
            // «Джарвис стоп» → «стоп»). Для совместимости с существующей логикой
            // допускаем любой не-greedy префикс перед kw (пыль из распознавателя:
            // «привет Джарвис …»), как в старом cleanWakeWord().
            // (?iu): ASCII-only (?i) не сворачивает регистр кириллицы, из-за
            // чего «Жарвис»/«Дарвис»/«Джей» вообще не распознавались.
            result = result
                .replace(Regex("(?iu)^.*?${Regex.escape(kw)}$NOISE*"), "")
                .trim()
        }
        return result
    }
}
