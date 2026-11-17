package com.omnix.assistant.agent.media.ymusic

/** Что делать с просьбой «включи песню X» при наличии или отсутствии токена. */
enum class MusicStart { PLAY_INSIDE_OMNIX, ASK_FOR_TOKEN }

/** Что делать, когда внутри OMNIX сыграть не вышло. */
enum class MusicFallback {
    /** Токен не принят: просим обновить его в настройках, внешний поиск не поможет. */
    OPEN_TOKEN_SETTINGS,

    /** Сеть, пустая выдача, нет ссылки: открываем поиск в приложении Яндекс Музыка / на сайте (без YouTube). */
    OPEN_EXTERNAL_SEARCH
}

enum class MusicFailure { UNAUTHORIZED, NO_RESULTS, UNAVAILABLE }

object YandexMusicDecision {
    fun start(hasToken: Boolean): MusicStart =
        if (hasToken) MusicStart.PLAY_INSIDE_OMNIX else MusicStart.ASK_FOR_TOKEN

    fun fallback(failure: MusicFailure): MusicFallback = when (failure) {
        MusicFailure.UNAUTHORIZED -> MusicFallback.OPEN_TOKEN_SETTINGS
        MusicFailure.NO_RESULTS, MusicFailure.UNAVAILABLE -> MusicFallback.OPEN_EXTERNAL_SEARCH
    }

    /** Токен хранится как есть, но пробелы и кавычки при вставке срезаем; слишком короткое отбрасываем. */
    fun normalizeToken(raw: String?): String? =
        raw?.trim()?.trim('"', '\'')?.trim()
            ?.takeIf { it.length >= MIN_TOKEN_CHARS && it.none { c -> c.isWhitespace() } }

    private const val MIN_TOKEN_CHARS = 20
}
