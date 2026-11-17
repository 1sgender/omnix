package com.omnix.assistant.agent.media

import java.net.URLEncoder

/**
 * Порядок, в котором OMNIX ищет песню. Чистая логика без Android-классов:
 * [MusicSearchRunner] перебирает шаги, пока какое-то приложение не откроется.
 *
 * Это запасной вариант: основной путь, плеер внутри OMNIX с треками из
 * Яндекс Музыки, живёт в [com.omnix.assistant.agent.media.ymusic.YandexMusicPlayer].
 * Сюда доходим, когда внутри сыграть не вышло (нет сети, нет трека). Решение
 * владельца (YouTube в Туркменистане заблокирован): только приложение Яндекс
 * Музыка, затем её страница в браузере. YouTube и YouTube Music исключены.
 */
enum class MusicSearchTarget(val label: String) {
    YANDEX_MUSIC_APP("Яндекс Музыке"),
    YANDEX_MUSIC_WEB("Яндекс Музыке (в браузере)")
}

enum class MusicIntentKind { VIEW }

/** Один способ открыть поиск: вид намерения, пакет (если нужен) и ссылка (для VIEW). */
data class MusicSearchStep(
    val target: MusicSearchTarget,
    val kind: MusicIntentKind,
    val packageName: String?,
    val uri: String?,
    val query: String
)

object MusicSearchPlan {
    const val PKG_YANDEX_MUSIC = "ru.yandex.music"

    fun encode(query: String): String = URLEncoder.encode(query, "UTF-8")

    fun yandexWebUrl(query: String): String = "https://music.yandex.ru/search?text=${encode(query)}"

    fun yandexDeepLink(query: String): String = "yandexmusic://search?text=${encode(query)}"

    /** Подготовка запроса: пробелы, длина. null — искать нечего. */
    fun sanitize(raw: String?): String? =
        raw?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_QUERY_CHARS)?.takeIf { it.isNotEmpty() }

    fun steps(query: String): List<MusicSearchStep> = listOf(
        MusicSearchStep(
            MusicSearchTarget.YANDEX_MUSIC_APP, MusicIntentKind.VIEW, PKG_YANDEX_MUSIC, yandexDeepLink(query), query
        ),
        // Если приложение не разбирает свою схему, пробуем обычную ссылку, закреплённую за ним.
        MusicSearchStep(
            MusicSearchTarget.YANDEX_MUSIC_APP, MusicIntentKind.VIEW, PKG_YANDEX_MUSIC, yandexWebUrl(query), query
        ),
        MusicSearchStep(
            MusicSearchTarget.YANDEX_MUSIC_WEB, MusicIntentKind.VIEW, null, yandexWebUrl(query), query
        )
    )

    private const val MAX_QUERY_CHARS = 200
}

/** Запуск одного шага; Android-реализация в [com.omnix.assistant.agent.tools.media.MusicSearchTool]. */
fun interface MusicStepLauncher {
    /** true — нашлось приложение и система приняла намерение. */
    fun launch(step: MusicSearchStep): Boolean
}

sealed interface MusicSearchOutcome {
    data class Opened(val target: MusicSearchTarget, val step: MusicSearchStep) : MusicSearchOutcome
    data object NothingInstalled : MusicSearchOutcome
}

object MusicSearchRunner {
    fun run(query: String, launcher: MusicStepLauncher): MusicSearchOutcome {
        for (step in MusicSearchPlan.steps(query)) {
            val opened = try {
                launcher.launch(step)
            } catch (_: RuntimeException) {
                false
            }
            if (opened) return MusicSearchOutcome.Opened(step.target, step)
        }
        return MusicSearchOutcome.NothingInstalled
    }
}
