package com.omnix.server.api

import com.omnix.server.config.ValidationConfig
import java.util.Base64

/**
 * Проверка картинок во входящем запросе: количество, тип, размер и
 * соответствие содержимого заявленному типу (по первым байтам).
 * Сервер не доверяет клиенту: заявленный mimeType сверяется с сигнатурой.
 */
object ImageValidator {

    val ALLOWED_MIME_TYPES: Set<String> = setOf("image/jpeg", "image/png", "image/webp")

    /** @return null, если всё в порядке; иначе код ошибки запроса. */
    fun validate(images: List<ImageDto>, validation: ValidationConfig): ApiErrorCode? {
        if (images.isEmpty()) return null
        if (images.size > validation.maxImagesPerRequest) return ApiErrorCode.INVALID_REQUEST

        var total = 0L
        for (image in images) {
            val mime = image.mimeType.trim().lowercase()
            if (mime !in ALLOWED_MIME_TYPES) return ApiErrorCode.INVALID_REQUEST
            if (image.data.isEmpty()) return ApiErrorCode.INVALID_REQUEST
            // Оценка по длине строки до декодирования: огромную строку не декодируем.
            val estimated = estimatedDecodedSize(image.data)
            if (estimated > validation.maxImageBytes) return ApiErrorCode.PAYLOAD_TOO_LARGE
            val bytes = try {
                Base64.getDecoder().decode(image.data)
            } catch (_: IllegalArgumentException) {
                return ApiErrorCode.INVALID_REQUEST
            }
            if (bytes.size > validation.maxImageBytes) return ApiErrorCode.PAYLOAD_TOO_LARGE
            if (!matchesSignature(mime, bytes)) return ApiErrorCode.INVALID_REQUEST
            total += bytes.size
            if (total > validation.maxImagesTotalBytes) return ApiErrorCode.PAYLOAD_TOO_LARGE
        }
        return null
    }

    /** Размер после декодирования base64 по длине строки (верхняя оценка). */
    fun estimatedDecodedSize(base64: String): Long = base64.length.toLong() / 4 * 3

    /** Совпадает ли начало файла с сигнатурой заявленного формата. */
    fun matchesSignature(mime: String, bytes: ByteArray): Boolean = when (mime) {
        "image/jpeg" -> bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        "image/png" -> bytes.size >= 8 && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }
        "image/webp" -> bytes.size >= 12 &&
            String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP"
        else -> false
    }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )
}
