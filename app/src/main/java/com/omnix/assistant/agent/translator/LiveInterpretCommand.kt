package com.omnix.assistant.agent.translator

/**
 * Голосовая команда синхронного перевода в ухо: «переводи с английского», «переводчик»,
 * «синхронный перевод», «translate from English», «iňlis dilinden terjime et».
 * Возвращает код языка собеседника (по умолчанию английский) или null, если это не команда.
 */
object LiveInterpretCommand {

    private val TRIGGERS = listOf(
        "переводчик", "синхронный перевод", "переводи собеседника", "переводи с ", "переводи меня с ",
        "слушай английск", "режим перевода",
        "interpreter", "live translat", "translate from ",
        "terjimeçi", "dilinden terjime", "terjime et"
    )

    /** Корни названий языков на ru/en/tk → код языка распознавания. */
    private val LANGUAGES = listOf(
        "английск" to "en", "english" to "en", "iňlis" to "en", "inlis" to "en",
        "турецк" to "tr", "turkish" to "tr", "türk dil" to "tr",
        "немецк" to "de", "german" to "de", "nemes" to "de",
        "китайск" to "zh", "chinese" to "zh", "hytaý" to "zh",
        "арабск" to "ar", "arabic" to "ar", "arap" to "ar",
        "русск" to "ru", "russian" to "ru", "rus dil" to "ru"
    )

    const val DEFAULT_SOURCE = "en"

    fun parse(text: String): String? {
        val q = text.lowercase().trim()
        if (q.isEmpty()) return null
        if (TRIGGERS.none { q.contains(it) }) return null
        // «переведи фразу…» — разовый перевод, не режим.
        if (q.startsWith("переведи ")) return null
        return LANGUAGES.firstOrNull { (root, _) -> q.contains(root) }?.second ?: DEFAULT_SOURCE
    }

    /**
     * Микрофон, названный в команде: «переводчик через наушник», «через телефон»,
     * «обоими микрофонами», «with both mics», «gulakçyn bilen». null — не назван (берётся настройка).
     * «Переводи в наушник» — это про звук перевода, а не про микрофон, поэтому нужен предлог/слово «микрофон».
     */
    fun parseMic(text: String): com.omnix.assistant.voice.live.LiveMicMode? {
        val q = text.lowercase()
        return when {
            BOTH_MIC.any { q.contains(it) } -> com.omnix.assistant.voice.live.LiveMicMode.BOTH
            EARBUD_MIC.any { q.contains(it) } -> com.omnix.assistant.voice.live.LiveMicMode.EARBUD
            PHONE_MIC.any { q.contains(it) } -> com.omnix.assistant.voice.live.LiveMicMode.PHONE
            else -> null
        }
    }

    private val BOTH_MIC = listOf(
        "оба микрофона", "обоими", "два микрофона", "двумя микрофонами", "both mic", "two mic",
        "ikisi bilen", "iki mikrofon"
    )
    private val EARBUD_MIC = listOf(
        "через наушник", "микрофон наушника", "микрофоном наушника", "earbud mic", "through the earbud",
        "with the earbud", "gulakçyn bilen", "gulakçynyň mikrofony"
    )
    private val PHONE_MIC = listOf(
        "через телефон", "микрофон телефона", "микрофоном телефона", "phone mic", "through the phone",
        "with the phone", "telefon bilen", "telefonyň mikrofony"
    )

    /** Слова выхода из режима на языке собеседника и на своём. */
    private val STOP_WORDS = setOf(
        "стоп", "хватит", "отмена", "выйти", "стоп перевод", "stop", "stop translating", "stop translation",
        "durdur", "bes", "boldy"
    )

    fun isStop(text: String): Boolean = text.lowercase().trim().trimEnd('.', '!', '?') in STOP_WORDS
}
