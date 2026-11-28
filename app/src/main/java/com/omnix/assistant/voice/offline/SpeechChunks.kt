package com.omnix.assistant.voice.offline

/**
 * Делит ответ на куски для синтеза своим голосом: предложение за предложением, чтобы первое
 * зазвучало, пока остальные ещё не синтезированы. Слишком длинное предложение режется по запятым
 * (или по словам), слишком короткие куски («Да.») склеиваются со следующим.
 */
object SpeechChunks {

    const val MAX_CHARS = 220
    private const val MIN_CHARS = 12

    private val SENTENCE_END = Regex("(?<=[.!?…;:])\\s+|\\n+")
    private val COMMA = Regex("(?<=[,—])\\s+")

    fun split(text: String): List<String> {
        val sentences = text.trim().split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }
        val pieces = sentences.flatMap { splitLong(it) }
        val out = ArrayList<String>(pieces.size)
        var pending = ""
        for (piece in pieces) {
            pending = if (pending.isEmpty()) piece else "$pending $piece"
            if (pending.length >= MIN_CHARS) {
                out.add(pending)
                pending = ""
            }
        }
        if (pending.isNotEmpty()) {
            if (out.isNotEmpty() && out.last().length + pending.length < MAX_CHARS) {
                out[out.lastIndex] = out.last() + " " + pending
            } else {
                out.add(pending)
            }
        }
        return out
    }

    private fun splitLong(sentence: String): List<String> {
        if (sentence.length <= MAX_CHARS) return listOf(sentence)
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (part in sentence.split(COMMA).flatMap { byWords(it) }) {
            if (current.isNotEmpty() && current.length + 1 + part.length > MAX_CHARS) {
                out.add(current.toString())
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(part)
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    private fun byWords(part: String): List<String> {
        if (part.length <= MAX_CHARS) return listOf(part)
        return part.split(' ').filter { it.isNotEmpty() }
    }
}
