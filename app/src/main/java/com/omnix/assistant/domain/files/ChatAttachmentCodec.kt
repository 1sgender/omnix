package com.omnix.assistant.domain.files

/**
 * Файл, приложенный к сообщению, хранится прямо в тексте сообщения между
 * служебными маркерами. Так не нужна миграция БД, а следующие вопросы по файлу
 * видят его содержимое в истории. Лента чата прячет блок и показывает карточку
 * файла (значок, имя, размер) и сам вопрос.
 *
 * Формат: `<<OMNIX_FILE name="a.pdf" kind="pdf" size="1234" doc="id">>`, текст,
 * `<<END_OMNIX_FILE>>`, вопрос. Атрибуты после name необязательны: старые
 * сообщения (только name) читаются как раньше.
 */
object ChatAttachmentCodec {
    private const val OPEN = "<<OMNIX_FILE name=\""
    private const val CLOSE = "<<END_OMNIX_FILE>>"

    private val BLOCK = Regex(
        Regex.escape(OPEN) + "([^\"\\n]*)\"((?: [a-z]+=\"[^\"\\n]*\")*)>>\\n(.*?)\\n" + Regex.escape(CLOSE) + "\\n?",
        RegexOption.DOT_MATCHES_ALL
    )
    private val ATTR = Regex(" ([a-z]+)=\"([^\"\\n]*)\"")

    /** Что показать в ленте и что отправить модели. */
    data class Decoded(
        val fileName: String?,
        val question: String,
        val content: String? = null,
        val kind: FileKind? = null,
        val sizeBytes: Long? = null,
        /** PDF, сохранённый на устройстве для отправки модели целиком. */
        val documentId: String? = null
    )

    fun encode(
        fileName: String,
        content: String,
        question: String,
        kind: FileKind? = null,
        sizeBytes: Long? = null,
        documentId: String? = null
    ): String {
        val safeName = sanitizeName(fileName)
        // Маркер закрытия внутри файла не должен обрывать блок раньше времени.
        val safeContent = content.replace(CLOSE, "<<END_OMNIX_FILE >>")
        return buildString {
            append(OPEN).append(safeName).append('"')
            kind?.let { append(" kind=\"").append(it.wire).append('"') }
            sizeBytes?.takeIf { it >= 0 }?.let { append(" size=\"").append(it).append('"') }
            documentId?.takeIf(ChatImageCodec::isValidId)?.let { append(" doc=\"").append(it).append('"') }
            append(">>\n")
            append(safeContent).append('\n')
            append(CLOSE).append('\n')
            append(question.trim())
        }.trim()
    }

    fun decode(text: String): Decoded {
        val match = BLOCK.find(text) ?: return Decoded(null, text)
        val attrs = ATTR.findAll(match.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
        val question = text.removeRange(match.range).trim()
        return Decoded(
            fileName = match.groupValues[1],
            question = question,
            content = match.groupValues[3],
            kind = FileKind.fromWire(attrs["kind"]),
            sizeBytes = attrs["size"]?.toLongOrNull(),
            documentId = attrs["doc"]?.takeIf(ChatImageCodec::isValidId)
        )
    }

    /**
     * Реплика для истории диалога: текст файла укорочен. Полный текст уходит
     * только с тем сообщением, к которому файл приложен; в истории хватает начала,
     * а длинная история не упирается в лимит сервера на одно сообщение.
     */
    fun forHistory(text: String, maxContentChars: Int = HISTORY_CONTENT_CHARS): String {
        val decoded = decode(text)
        val content = decoded.content ?: return text
        val name = decoded.fileName ?: return text
        if (content.length <= maxContentChars) return text
        val short = content.take(maxContentChars).trimEnd() + "\n[…]"
        return encode(name, short, decoded.question, decoded.kind, decoded.sizeBytes, null)
    }

    const val HISTORY_CONTENT_CHARS = 4_000

    fun sanitizeName(name: String): String =
        name.replace(Regex("[\\r\\n\"<>]"), " ").trim().ifEmpty { "file" }.take(80)
}
