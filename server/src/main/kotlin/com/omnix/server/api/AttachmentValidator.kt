package com.omnix.server.api

import com.omnix.server.config.ValidationConfig
import java.util.Base64

/** Проверка приложенных файлов: тексты (лимит длины) и PDF (тип, сигнатура, размер). */
object AttachmentValidator {

    const val PDF_MIME = "application/pdf"
    private const val MAX_NAME_CHARS = 200

    fun validate(
        attachments: List<AttachmentDto>,
        documents: List<DocumentDto>,
        validation: ValidationConfig
    ): ApiErrorCode? {
        if (attachments.size > validation.maxAttachmentsPerRequest) return ApiErrorCode.INVALID_REQUEST
        for (a in attachments) {
            if (a.name.isBlank() || a.name.length > MAX_NAME_CHARS) return ApiErrorCode.INVALID_REQUEST
            if (a.text.isBlank()) return ApiErrorCode.INVALID_REQUEST
            if (a.text.length > validation.maxAttachmentChars) return ApiErrorCode.PAYLOAD_TOO_LARGE
        }
        if (documents.size > validation.maxDocumentsPerRequest) return ApiErrorCode.INVALID_REQUEST
        for (d in documents) {
            if (d.mimeType.trim().lowercase() != PDF_MIME) return ApiErrorCode.INVALID_REQUEST
            if (d.name.isBlank() || d.name.length > MAX_NAME_CHARS) return ApiErrorCode.INVALID_REQUEST
            if (d.fallbackText.length > validation.maxAttachmentChars) return ApiErrorCode.PAYLOAD_TOO_LARGE
            if (d.data.isEmpty()) return ApiErrorCode.INVALID_REQUEST
            if (ImageValidator.estimatedDecodedSize(d.data) > validation.maxDocumentBytes) {
                return ApiErrorCode.PAYLOAD_TOO_LARGE
            }
            val bytes = try {
                Base64.getDecoder().decode(d.data)
            } catch (_: IllegalArgumentException) {
                return ApiErrorCode.INVALID_REQUEST
            }
            if (bytes.size > validation.maxDocumentBytes) return ApiErrorCode.PAYLOAD_TOO_LARGE
            if (!isPdf(bytes)) return ApiErrorCode.INVALID_REQUEST
        }
        return null
    }

    fun isPdf(bytes: ByteArray): Boolean =
        bytes.size >= 5 && String(bytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-"
}
