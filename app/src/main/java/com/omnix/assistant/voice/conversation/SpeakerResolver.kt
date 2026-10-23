package com.omnix.assistant.voice.conversation

/** Кто сказал фразу в разговоре. */
enum class Speaker { ME, THEM }

/**
 * Решает по языку фразы, кто говорит: владелец (язык [mine]) или собеседник
 * ([theirs]). Язык собеседника можно не задавать: первая фраза не на моём
 * языке его и определяет (отзыв владельца на v344: «OMNIX сам должен понять,
 * кто на каком языке говорит»).
 *
 * Whisper на коротких фразах путает близкие языки: туркменский слышит как
 * турецкий или азербайджанский, казахский — как киргизский. Если в паре есть
 * язык из той же семьи, фраза относится к нему.
 */
class SpeakerResolver(val mine: String, theirs: String? = null) {

    var theirs: String? = theirs
        private set

    data class Resolution(val speaker: Speaker, val sourceLang: String, val targetLang: String)

    /** null — фразу не к кому отнести (язык ещё неизвестен или чужой третий). */
    fun resolve(detected: String?): Resolution? {
        val lang = detected?.lowercase() ?: return null
        val other = theirs
        val mineMatch = lang == mine || sameFamily(lang, mine)
        val theirsMatch = other != null && (lang == other || sameFamily(lang, other))

        return when {
            // Точное совпадение важнее семьи: ru/uk, tk/tr бывают в одной паре.
            lang == mine -> other?.let { Resolution(Speaker.ME, mine, it) }
            other != null && lang == other -> Resolution(Speaker.THEM, other, mine)
            mineMatch && !theirsMatch -> other?.let { Resolution(Speaker.ME, mine, it) }
            theirsMatch && !mineMatch -> Resolution(Speaker.THEM, other!!, mine)
            mineMatch && theirsMatch -> null
            other == null -> {
                // Первая фраза собеседника задаёт его язык.
                theirs = lang
                Resolution(Speaker.THEM, lang, mine)
            }
            else -> null
        }
    }

    /** Ещё не знаем язык собеседника — моя фраза не переводится, но не теряется. */
    val waitingForThem: Boolean get() = theirs == null

    private fun sameFamily(a: String, b: String): Boolean =
        FAMILIES.any { a in it && b in it }

    private companion object {
        val FAMILIES = listOf(
            setOf("tk", "tr", "az"),
            setOf("kk", "ky", "tt"),
            setOf("ru", "uk", "be")
        )
    }
}
