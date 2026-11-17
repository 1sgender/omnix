package com.omnix.assistant.agent.media

/**
 * Разбор фраз «найди песню X», «включи песню X», «поставь X», «play song X»
 * (ru, en, tk, tr, kk, uz) в поисковый запрос для инструмента media.play_search.
 *
 * Два уровня уверенности:
 *  - [parseExplicit]: в фразе есть слово «песня/трек/song/şarkı/aýdym/ән/qo'shiq»
 *    или «включи музыку X» — это поиск музыки, перехватываем рано;
 *  - [parseBare]: «включи X» / «поставь X» / «play X» без существительного.
 *    Такое легко спутать с «включи фонарик», «поставь будильник», поэтому
 *    роутер зовёт его ПОСЛЕ всех остальных маршрутов, а здесь действует
 *    список слов, которые песней быть не могут.
 *
 * «Включи музыку» / «следующий трек» / «громче» без названия сюда не попадают:
 * это media.control.
 */
object MusicRequestParser {

    private const val MAX_QUERY_CHARS = 200

    private val VERBS = listOf(
        // ru
        "хочу послушать", "дай послушать", "послушать", "найди", "найти", "поищи", "ищи",
        "включи", "включить", "вруби", "запусти", "поставь", "сыграй", "проиграй", "играй",
        // en
        "search for", "listen to", "put on", "find", "search", "play",
        // tr
        "ara", "bul", "çal", "aç",
        // tk
        "gözle", "tap", "goý", "aç", "çal",
        // kk
        "тап", "қос", "ойнат", "іздеу", "ізде",
        // uz
        "qidir", "top", "qo.y", "ochib ber", "qo.yib ber"
    ).sortedByDescending { it.length }.joinToString("|")

    private const val WORD_TAIL = "[\\p{L}'\u2019\u02bb`]*"

    private val NOUNS = listOf(
        "песню", "песня", "песни", "трек", "композицию", "музыку", "музыка",
        "songs?", "tracks?", "music",
        "şarkı$WORD_TAIL", "müzik$WORD_TAIL", "parça$WORD_TAIL",
        "aýdym$WORD_TAIL", "saz$WORD_TAIL",
        "ән$WORD_TAIL",
        "qo.shi$WORD_TAIL", "musiqa$WORD_TAIL"
    ).joinToString("|")

    private const val FILLER = "(?:мне\\s+|для\\s+меня\\s+|me\\s+|the\\s+|a\\s+|bana\\s+|maňa\\s+|маған\\s+|menga\\s+)*"

    /** «найди песню X», «play the song X», «хочу послушать музыку X». */
    private val VERB_NOUN_QUERY = Regex("^(?:$VERBS)\\s+$FILLER(?:$NOUNS)\\s+(.+)$")

    /** «şarkı çal X», «aýdym tap X», «ән қос X», «qo'shiq qo'y X». */
    private val NOUN_VERB_QUERY = Regex("^(?:$NOUNS)\\s+(?:$VERBS)\\s+(.+)$")

    /** «X şarkısını çal», «X aýdymyny goý», «X әнін қос», «X qo'shig'ini qo'y». */
    private val QUERY_NOUN_VERB = Regex("^(.+?)\\s+(?:$NOUNS)\\s+(?:$VERBS)(?:\\s+(?:ber|et))?$")

    /** «включи X», «поставь X», «play X» — только как последний шанс. */
    private val BARE = Regex("^(?:включи|вруби|поставь|сыграй|проиграй|play)\\s+(.+)$")

    private val TRAILING_POLITE = Regex("\\s*(?:пожалуйста|please|lütfen|haýyş|өтінемін)\\s*$")

    /** Если запрос начинается с этого, это команда плеера или устройства, а не название. */
    private val NOT_A_TITLE_PREFIXES = listOf(
        // управление плеером и звуком
        "музык", "music", "трек", "следующ", "предыдущ", "дальше", "назад", "пауз", "стоп", "громч", "тише",
        "погромче", "потише", "звук", "громкост", "next", "previous", "pause", "stop", "resume", "louder",
        "quieter", "volume", "продолж", "возобнов",
        // устройство и системные функции
        "фонар", "вспышк", "свет", "яркост", "экран", "wi", "вай", "блютуз", "bluetooth", "интернет",
        "режим", "не беспокоить", "dnd", "авиа", "полёт", "полет", "мобильн", "данные", "геолокац", "gps",
        // приложения и дела
        "будильник", "таймер", "напоминани", "заметк", "календар", "встреч", "alarm", "timer", "reminder",
        "телеграм", "telegram", "ватсап", "whatsapp", "ютуб", "youtube", "камер", "карт", "настройк",
        "калькулятор", "навигатор", "chrome", "хром", "браузер", "спотифай", "spotify",
        // кино и разное
        "фильм", "видео", "сериал", "мультик", "movie", "video", "game", "игр"
    )

    /** Слова системных режимов: «включи ночной режим» не песня, пусть решает планировщик. */
    private val NOT_A_TITLE_ANYWHERE = listOf(
        "режим", "ночн", "тёмн", "темн", "заряд", "эконом", "wi-fi", "wifi", "уведомлен", "mode"
    )

    /** Явный запрос музыки (есть слово «песня/трек/song…»). Возвращает чистое название или null. */
    fun parseExplicit(rawPhrase: String): String? {
        val q = normalize(rawPhrase)
        if (q.isEmpty()) return null
        val query = (VERB_NOUN_QUERY.find(q) ?: NOUN_VERB_QUERY.find(q) ?: QUERY_NOUN_VERB.find(q))
            ?.groupValues?.get(1)
            ?: return null
        return clean(query)?.takeUnless { isPlayerCommand(it) }
    }

    /** «включи X» / «поставь X» без слова «песня»; звать после остальных маршрутов. */
    fun parseBare(rawPhrase: String): String? {
        val q = normalize(rawPhrase)
        val query = BARE.find(q)?.groupValues?.get(1) ?: return null
        val cleaned = clean(query) ?: return null
        if (NOT_A_TITLE_PREFIXES.any { cleaned.startsWith(it) }) return null
        if (NOT_A_TITLE_ANYWHERE.any { cleaned.contains(it) }) return null
        if (cleaned.startsWith("на ") || cleaned.startsWith("в ") || cleaned.startsWith("по ")) return null
        return cleaned
    }

    /** Команда плеера, а не название: «включи музыку громче», «поставь песню на паузу». */
    private fun isPlayerCommand(query: String): Boolean =
        query in PLAYER_COMMANDS || query.startsWith("громч") || query.startsWith("тише")

    private val PLAYER_COMMANDS = setOf(
        "громче", "тише", "погромче", "потише", "дальше", "следующий", "следующую", "предыдущий",
        "предыдущую", "назад", "пауза", "паузу", "на паузу", "стоп", "продолжи", "продолжай",
        "next", "previous", "pause", "stop", "resume", "continue", "louder", "quieter", "volume up",
        "volume down"
    )

    private fun normalize(raw: String): String =
        raw.lowercase().trim().replace(Regex("\\s+"), " ")
            .replace(TRAILING_POLITE, "").trim()

    /** «включи музыку найди X»: второй глагол и слово «песню» в начале названием не являются. */
    private val LEADING_COMMAND = Regex("^(?:(?:найди|найти|поищи|ищи|включи|поставь|сыграй|find|search|play)\\s+)+(?:(?:песню|песня|трек|музыку|song|track)\\s+)?")

    private fun clean(query: String): String? {
        val trimmed = query.trim().trim('"', '«', '»', '“', '”', '.', ',', '!', '?').trim()
            .replace(Regex("\\s+"), " ")
        val stripped = trimmed.replace(LEADING_COMMAND, "").takeIf { it.length >= 2 } ?: trimmed
        return stripped.take(MAX_QUERY_CHARS).takeIf { it.length >= 2 }
    }
}
