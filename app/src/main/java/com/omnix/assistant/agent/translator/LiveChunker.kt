package com.omnix.assistant.agent.translator

/**
 * Режет речь собеседника на куски для синхронного перевода, пока он ещё говорит.
 *
 * Распознаватель присылает промежуточный текст всей фразы и может менять последние слова
 * («I want to by» → «I want to buy a»). Слово считается устоявшимся, когда оно совпало в двух
 * промежуточных результатах подряд (общий префикс). Как только устоявшихся и ещё не отданных
 * слов набирается [chunkWords], они уходят в перевод. Пауза собеседника ([onPause]) и конец
 * фразы ([onFinal]) отдают остаток.
 *
 * Уже отданные слова не переводятся повторно, даже если распознаватель потом их поправит:
 * в ухо лучше небольшая неточность, чем повтор.
 */
class LiveChunker(private val chunkWords: Int = DEFAULT_CHUNK_WORDS) {

    init {
        require(chunkWords >= 1) { "chunkWords must be positive" }
    }

    /** Слов текущей фразы, уже отданных в перевод. */
    private var committed = 0

    /** Последний промежуточный результат, по словам. */
    private var lastWords: List<String> = emptyList()

    /** Промежуточный текст фразы. Возвращает кусок для перевода или null. */
    fun onPartial(text: String): String? {
        val words = tokenize(text)
        val stable = commonPrefix(lastWords, words)
        lastWords = words
        val ready = stable - committed
        if (ready < chunkWords) return null
        return take(words, stable)
    }

    /** Собеседник замолчал, а фраза ещё не закрыта: отдать всё, что услышано. */
    fun onPause(): String? {
        if (lastWords.size <= committed) return null
        return take(lastWords, lastWords.size)
    }

    /** Финальный текст фразы: отдать остаток и начать новую фразу. */
    fun onFinal(text: String): String? {
        val words = tokenize(text)
        val rest = if (words.size > committed) words.subList(committed, words.size).joinToString(" ") else null
        reset()
        return rest?.takeIf { it.isNotBlank() }
    }

    fun reset() {
        committed = 0
        lastWords = emptyList()
    }

    private fun take(words: List<String>, until: Int): String? {
        val piece = words.subList(committed, until).joinToString(" ")
        committed = until
        return piece.takeIf { it.isNotBlank() }
    }

    private fun commonPrefix(a: List<String>, b: List<String>): Int {
        val n = minOf(a.size, b.size)
        var i = 0
        while (i < n && normalize(a[i]) == normalize(b[i])) i++
        return i
    }

    companion object {
        const val DEFAULT_CHUNK_WORDS = 4

        private val SPACE = Regex("\\s+")
        private val EDGE_PUNCT = Regex("^[\\p{P}\\p{S}]+|[\\p{P}\\p{S}]+$")

        fun tokenize(text: String): List<String> =
            text.trim().split(SPACE).filter { it.isNotBlank() }

        private fun normalize(word: String): String = word.lowercase().replace(EDGE_PUNCT, "")
    }
}
