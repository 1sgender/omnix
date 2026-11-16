package com.omnix.assistant.domain.files

/**
 * Файл, приложенный к сообщению, хранится прямо в тексте сообщения между
 * служебными маркерами. Так не нужна миграция БД, а следующие вопросы по файлу
 * видят его содержимое в истории. Лента чата прячет блок и показывает чип с
 * именем файла и сам вопрос.
 */
object ChatAttachmentCodec {
    private const val OPEN = "<<OMNIX_FILE name=\""
    private const val OPEN_END = "\">>"
    private const val CLOSE = "<<END_OMNIX_FILE>>"

    private val BLOCK = Regex(
        Regex.escape(OPEN) + "([^\"\\n]*)" + Regex.escape(OPEN_END) + "\\n(.*?)\\n" + Regex.escape(CLOSE) + "\\n?",
        RegexOption.DOT_MATCHES_ALL
    )

    /** Что показать в ленте: имя файла (если есть) и текст вопроса без блока файла. */
    data class Decoded(val fileName: String?, val question: String)

    fun encode(fileName: String, content: String, question: String): String {
        val safeName = sanitizeName(fileName)
        // Маркер закрытия внутри файла не должен обрывать блок раньше времени.
        val safeContent = content.replace(CLOSE, "<<END_OMNIX_FILE >>")
        return buildString {
            append(OPEN).append(safeName).append(OPEN_END).append('\n')
            append(safeContent).append('\n')
            append(CLOSE).append('\n')
            append(question.trim())
        }.trim()
    }

    fun decode(text: String): Decoded {
        val match = BLOCK.find(text) ?: return Decoded(null, text)
        val question = text.removeRange(match.range).trim()
        return Decoded(fileName = match.groupValues[1], question = question)
    }

    fun sanitizeName(name: String): String =
        name.replace(Regex("[\\r\\n\"<>]"), " ").trim().ifEmpty { "file" }.take(80)
}
