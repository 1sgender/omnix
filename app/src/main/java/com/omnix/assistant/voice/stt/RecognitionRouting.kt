package com.omnix.assistant.voice.stt

/**
 * На каком языке распознавать фразу и можно ли доверять ответу модели на телефоне.
 *
 * Язык интерфейса — только подсказка: человек может при русском интерфейсе сказать фразу
 * по-туркменски или по-английски, и тогда русская модель выдаст мусор. Язык фразы определяет
 * встроенная модель (Whisper tiny) — [effectiveLanguage] решает, верить ли ей.
 */
object RecognitionRouting {

    /** Языки OMNIX, на которые разрешено переключиться по определению языка. */
    val SUPPORTED = setOf("ru", "tk", "en", "tr", "uz", "kk")

    /** Маленькая модель часто путает туркменский с соседними тюркскими языками. */
    private val TURKMEN_LOOKALIKES = setOf("tk", "tr", "az", "uz", "tt", "ba")

    /** …а русский — с близкими славянскими. */
    private val RUSSIAN_LOOKALIKES = setOf("ru", "uk", "be", "bg", "sr", "mk")

    /** Не меньше стольких букв в секунду речи; меньше — модель на телефоне, похоже, не расслышала. */
    private const val MIN_LETTERS_PER_SECOND = 4

    /** Короткие фразы («да», «стоп») проверке на полноту не подлежат. */
    private const val MIN_CHECK_MS = 1_500L

    /**
     * @param appHint язык интерфейса («ru», «tk»…).
     * @param detected язык, определённый по звуку; null — не определён (короткая фраза, нет модели).
     */
    fun effectiveLanguage(appHint: String, detected: String?): String {
        val d = detected?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return appHint
        if (appHint == "tk" && d in TURKMEN_LOOKALIKES) return "tk"
        if (appHint == "ru" && d in RUSSIAN_LOOKALIKES) return "ru"
        return if (d in SUPPORTED) d else appHint
    }

    /** Текст подозрительно короткий для такой длинной фразы — стоит переспросить у сервера. */
    fun looksIncomplete(text: String, durationMs: Long): Boolean {
        if (durationMs < MIN_CHECK_MS) return false
        val letters = text.count { it.isLetter() }
        return letters < durationMs * MIN_LETTERS_PER_SECOND / 1000
    }
}
