package com.omnix.assistant.core.locale

import java.util.Locale

/**
 * Языки OMNIX (решение владельца 2026-10-22): интерфейс и речь. Язык
 * приложения выбирается в «Я → Язык» (per-app language Android 13+) или
 * берётся из системы; [current] читает то, что действует сейчас.
 */
enum class AppLanguage(val code: String, val speechTag: String, val nativeName: String, val promptName: String) {
    TK("tk", "tk-TM", "Türkmençe", "туркменский"),
    RU("ru", "ru-RU", "Русский", "русский"),
    EN("en", "en-US", "English", "английский"),
    TR("tr", "tr-TR", "Türkçe", "турецкий"),
    UZ("uz", "uz-UZ", "Oʻzbekcha", "узбекский"),
    KK("kk", "kk-KZ", "Қазақша", "казахский");

    val locale: Locale get() = Locale.forLanguageTag(speechTag)

    companion object {
        /** Язык процесса: per-app язык Android меняет Locale.getDefault(). */
        fun current(): AppLanguage = fromLocale(Locale.getDefault())

        fun fromLocale(locale: Locale): AppLanguage = fromCode(locale.language) ?: RU

        fun fromCode(code: String?): AppLanguage? {
            val c = code?.lowercase() ?: return null
            // Старый код туркменского в некоторых системах — «tuk».
            return entries.firstOrNull { it.code == c } ?: if (c == "tuk") TK else null
        }
    }
}

/** Языки распознавания и озвучки: у системных движков туркменского нет. */
object SpeechLanguage {

    /** Код языка для серверного распознавания (Whisper). */
    const val SERVER_HINT_TK = "tk"

    /** Туркменский распознаёт сервер: у Android-распознавателя его нет. */
    fun usesServerRecognition(language: AppLanguage = AppLanguage.current()): Boolean = language == AppLanguage.TK

    /** Распознаватель Android: туркменского нет — слушаем по-русски (запасной тег). */
    fun recognitionTag(language: AppLanguage = AppLanguage.current()): String =
        if (language == AppLanguage.TK) AppLanguage.RU.speechTag else language.speechTag

    /**
     * Голос: язык интерфейса, если движок его знает, иначе русский, иначе
     * английский. Туркменский голос появится вместе с собственным голосом OMNIX.
     */
    fun ttsLocale(language: AppLanguage = AppLanguage.current(), isAvailable: (Locale) -> Boolean): Locale =
        sequenceOf(language.locale, AppLanguage.RU.locale, Locale.US).firstOrNull(isAvailable) ?: Locale.US

    /**
     * Строка для системного промпта: на каком языке отвечать. Пользователь
     * может заговорить на другом — тогда отвечаем на его языке.
     */
    fun answerHint(language: AppLanguage = AppLanguage.current()): String =
        "Язык интерфейса пользователя — ${language.promptName}. Отвечай на том языке, на котором " +
            "пользователь к тебе обратился; если язык непонятен — на ${language.promptName}."
}
