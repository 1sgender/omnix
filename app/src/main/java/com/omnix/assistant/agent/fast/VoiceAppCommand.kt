package com.omnix.assistant.agent.fast

/**
 * Разбор голосовой команды запуска приложения: чистая функция без Android.
 *
 * Распознавание речи пишет одно и то же слово по-разному: «ютуб», «ютюб»,
 * «ютьюб», «ю туб», «you tube», «YouTube». Раньше роутер знал только «ютуб» и
 * «youtube», остальные варианты уходили мимо команды, и голосом приложение не
 * открывалось, хотя набранный текст работал.
 */
object VoiceAppCommand {

    private val LAUNCH_VERBS = listOf(
        "открой", "открыть", "запусти", "запустить", "включи", "включить",
        "перейди в", "перейди на", "зайди в", "зайди на", "вруби"
    )

    /** Слова-обёртки перед глаголом: «ну открой ютуб», «пожалуйста, запусти…». */
    private val LEADING_FILLERS = setOf("ну", "а", "и", "так", "пожалуйста", "давай", "слушай", "омни", "будь", "добр")

    private val WORD_SEPARATORS = Regex("[\\s,.!?;:]+")

    private val YOUTUBE_FORMS = listOf(
        "youtube", "you tube", "ютуб", "ютюб", "ютьюб", "ютубе", "ютубчик", "ютьуб", "ю туб", "ю тюб", "ютуп", "ютубу"
    )

    /** Фраза начинается с глагола запуска (после слов-обёрток). */
    fun hasLaunchVerb(text: String): Boolean {
        val words = text.lowercase().trim().split(WORD_SEPARATORS).filter { it.isNotEmpty() }
        val start = words.indexOfFirst { it !in LEADING_FILLERS }
        if (start < 0) return false
        val tail = words.drop(start).joinToString(" ")
        return LAUNCH_VERBS.any { tail == it || tail.startsWith("$it ") }
    }

    /** В фразе названо приложение YouTube в любом из написаний. */
    fun mentionsYoutube(text: String): Boolean {
        val q = text.lowercase()
        return YOUTUBE_FORMS.any { q.contains(it) }
    }
}
