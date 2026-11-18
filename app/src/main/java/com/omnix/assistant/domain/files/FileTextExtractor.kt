package com.omnix.assistant.domain.files

/**
 * Достаёт текст из файла, который пользователь приложил к сообщению.
 * Тип определяется по содержимому ([FileKindDetector]); картинки и аудио этот
 * модуль не читает — для них [Result.Media], их обрабатывают отдельно.
 */
object FileTextExtractor {

    /** Больше этого размера файл не принимаем. */
    const val MAX_FILE_BYTES = 20 * 1024 * 1024

    /** Сколько текста файла (в токенах, оценка) уходит модели; дальше обрезка с пометкой. */
    const val MAX_TEXT_TOKENS = 12_000

    enum class Reason { TOO_LARGE, UNSUPPORTED, EMPTY, UNREADABLE, TRANSCRIPTION_FAILED }

    sealed interface Result {
        data class Success(
            val name: String,
            val text: String,
            val truncated: Boolean,
            val kind: FileKind = FileKind.TEXT,
            val sizeBytes: Long = 0,
            /** PDF, сохранённый для отправки модели целиком (id в ChatFileStore). */
            val documentId: String? = null
        ) : Result

        data class Failure(val name: String, val reason: Reason) : Result

        /** Картинка или аудио: их читают ChatImageStore и расшифровка речи. */
        data class Media(val name: String, val kind: FileKind) : Result
    }

    private val OFFICE_EXTENSIONS = setOf("docx", "xlsx", "pptx", "docm", "xlsm", "pptm")

    fun extract(name: String, bytes: ByteArray): Result {
        if (bytes.isEmpty()) return Result.Failure(name, Reason.EMPTY)
        if (bytes.size > MAX_FILE_BYTES) return Result.Failure(name, Reason.TOO_LARGE)
        val kind = FileKindDetector.detect(name, bytes)
        val raw: String = when (kind) {
            FileKind.PDF -> PdfTextExtractor.extract(bytes) ?: return Result.Failure(name, Reason.UNREADABLE)
            FileKind.DOCX -> OfficeTextExtractor.docx(bytes) ?: return Result.Failure(name, Reason.UNREADABLE)
            FileKind.XLSX -> OfficeTextExtractor.xlsx(bytes) ?: return Result.Failure(name, Reason.UNREADABLE)
            FileKind.PPTX -> OfficeTextExtractor.pptx(bytes) ?: return Result.Failure(name, Reason.UNREADABLE)
            FileKind.DOC -> LegacyDocExtractor.extract(bytes) ?: return Result.Failure(name, Reason.EMPTY)
            FileKind.TEXT, FileKind.SPREADSHEET_TEXT -> decodeText(bytes)
            FileKind.IMAGE, FileKind.AUDIO -> return Result.Media(name, kind)
            FileKind.UNKNOWN -> {
                // Архив с расширением Office, но без нужного содержимого — повреждён.
                val reason = if (FileKindDetector.extension(name) in OFFICE_EXTENSIONS) Reason.UNREADABLE else Reason.UNSUPPORTED
                return Result.Failure(name, reason)
            }
        }
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        // PDF без текста (скан) ещё может уйти модели целиком — решает вызывающий.
        if (text.isEmpty() && kind != FileKind.PDF) return Result.Failure(name, Reason.EMPTY)
        val fitted = TextBudget.fit(text, MAX_TEXT_TOKENS)
        return Result.Success(name, fitted.text, fitted.truncated, kind, bytes.size.toLong())
    }

    /** UTF-8 (с BOM или без) и UTF-16 по BOM. */
    fun decodeText(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> String(bytes, Charsets.UTF_8)
    }
}

/** Обрезка текста по оценке токенов с понятной модели пометкой. */
object TextBudget {

    /** Грубая оценка: ~3 символа на токен (кириллица дробится сильнее латиницы). */
    const val CHARS_PER_TOKEN = 3

    fun estimateTokens(text: String): Int = (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    data class Fitted(val text: String, val truncated: Boolean, val shownTokens: Int, val totalTokens: Int)

    fun fit(text: String, maxTokens: Int): Fitted {
        val total = estimateTokens(text)
        if (total <= maxTokens) return Fitted(text, false, total, total)
        val maxChars = maxTokens * CHARS_PER_TOKEN
        val minCut = (maxChars * 0.8).toInt()
        // Режем по границе строки или слова, чтобы не оборвать посреди слова.
        val cut = text.lastIndexOf('\n', maxChars).takeIf { it >= minCut }
            ?: text.lastIndexOf(' ', maxChars).takeIf { it >= minCut }
            ?: maxChars
        val head = text.substring(0, cut).trimEnd()
        val shown = estimateTokens(head)
        return Fitted("$head\n\n[truncated: showing about $shown of $total tokens]", true, shown, total)
    }
}
