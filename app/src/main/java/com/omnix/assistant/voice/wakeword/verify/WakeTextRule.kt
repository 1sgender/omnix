package com.omnix.assistant.voice.wakeword.verify

/**
 * Вторая ступень слова вызова: есть ли «Омни» в тексте, который GigaAM распознал в окне звука
 * вокруг срабатывания детектора.
 *
 * Как GigaAM пишет «Омни» владельца (замер 2026-11-28, 241 + 96 фраз): «омни» чаще всего, затем
 * «помни», «умни», «омнэ», «омня», «амни», «оми». Обычное правило принимает их; «они», «ани», «он не»
 * не принимает — с ними на фильме в комнате владельца шло 8,8 ложных в час («они уже уехали»).
 * Строгое правило (когда играет медиа или подряд было много ложных) берёт только «омни»-подобные.
 */
object WakeTextRule {

    private const val OMNI_LIKE = "[ао]м+н[иыеэяёу][а-яё]{0,2}|ом\\s?ни|оо?мм?и|хомни"
    private const val LOOKALIKE = "п[ао]мни|умн[иеэ]"

    private val NORMAL = Regex("(?:^| )(?:$OMNI_LIKE|$LOOKALIKE)(?: |$)")
    private val STRICT = Regex("(?:^| )(?:$OMNI_LIKE)(?: |$)")
    private val NOT_LETTERS = Regex("[^а-яёa-z ]+")
    private val SPACES = Regex("\\s+")

    fun normalize(text: String): String =
        text.lowercase().replace(NOT_LETTERS, " ").replace(SPACES, " ").trim()

    /** [strict] — только «омни»-подобные написания. */
    fun matches(text: String, strict: Boolean = false): Boolean {
        val t = normalize(text)
        if (t.isEmpty()) return false
        return (if (strict) STRICT else NORMAL).containsMatchIn(t)
    }
}
