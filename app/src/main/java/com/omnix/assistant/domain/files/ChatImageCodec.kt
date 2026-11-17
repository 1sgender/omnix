package com.omnix.assistant.domain.files

/**
 * Картинка сообщения хранится файлом в памяти приложения, а в тексте сообщения
 * лежит только короткая ссылка-маркер. Так в базе нет мегабайт base64 и не нужна
 * миграция. Лента чата прячет маркер и показывает миниатюру; при отправке
 * в облако клиент подставляет сам файл.
 */
object ChatImageCodec {
    private const val OPEN = "<<OMNIX_IMAGE id=\""
    private const val CLOSE = "\">>"

    /** В историю для модели вместо маркера идёт такая пометка. */
    const val HISTORY_PLACEHOLDER = "[image attached]"

    private val MARKER = Regex(Regex.escape(OPEN) + "([A-Za-z0-9]{8,64})" + Regex.escape(CLOSE) + "\\n?")
    private val ID = Regex("[A-Za-z0-9]{8,64}")

    /** Что показать в ленте: id картинок и текст вопроса без маркеров. */
    data class Decoded(val imageIds: List<String>, val question: String)

    fun isValidId(id: String): Boolean = ID.matches(id)

    fun encode(imageId: String, question: String): String {
        require(isValidId(imageId)) { "bad image id" }
        return (OPEN + imageId + CLOSE + "\n" + question.trim()).trim()
    }

    fun decode(text: String): Decoded {
        val ids = MARKER.findAll(text).map { it.groupValues[1] }.toList()
        if (ids.isEmpty()) return Decoded(emptyList(), text)
        return Decoded(ids, MARKER.replace(text, "").trim())
    }

    fun hasImage(text: String): Boolean = text.contains(OPEN)

    /** Текст реплики для истории диалога: маркер заменён пометкой, сам файл в историю не идёт. */
    fun forHistory(text: String): String {
        val decoded = decode(text)
        if (decoded.imageIds.isEmpty()) return text
        return (HISTORY_PLACEHOLDER + " " + decoded.question).trim()
    }
}
