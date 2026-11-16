package com.omnix.assistant.agent.tools.intelligence

/** Один результат веб-поиска, пришедший с сервера OMNIX. */
data class ServerSearchResult(val title: String, val url: String, val snippet: String)

/**
 * Итог обращения к серверному поиску (/v1/search). Всё, кроме [Found], значит
 * «сервер не помог» (поиск не настроен, сеть, лимит, нет активации), и
 * WebSearchTool уходит на прежний путь: DuckDuckGo, затем Википедия.
 */
sealed interface ServerSearchOutcome {
    data class Found(val answer: String?, val results: List<ServerSearchResult>) : ServerSearchOutcome
    data class Unavailable(val reason: String) : ServerSearchOutcome
}

/** Серверный веб-поиск; реализует OmnixApiClient (токен клиента и таймауты там). */
fun interface ServerWebSearch {
    suspend fun search(query: String, lang: String): ServerSearchOutcome

    companion object {
        /** Без сервера: сразу запасной путь (тесты и старые сборки). */
        val NONE = ServerWebSearch { _, _ -> ServerSearchOutcome.Unavailable("NOT_CONFIGURED") }
    }
}

/** Краткий голосовой ответ из результатов сервера. */
object ServerSearchSummary {
    private const val MAX_CHARS = 500
    private const val MAX_RESULTS = 3

    fun compose(found: ServerSearchOutcome.Found): String? {
        val answer = found.answer?.trim().orEmpty()
        val text = if (answer.isNotEmpty()) {
            answer
        } else {
            found.results.take(MAX_RESULTS).mapNotNull { r ->
                val body = r.snippet.trim().ifEmpty { r.title.trim() }
                body.takeIf { it.isNotEmpty() }
            }.joinToString(". ")
        }
        if (text.isBlank()) return null
        return if (text.length > MAX_CHARS) text.take(MAX_CHARS).trimEnd() + "..." else text
    }
}
