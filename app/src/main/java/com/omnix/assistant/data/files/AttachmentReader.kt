package com.omnix.assistant.data.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.omnix.assistant.domain.files.FileKind
import com.omnix.assistant.domain.files.FileTextExtractor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Читает файл, выбранный пользователем в системном окне, и достаёт из него
 * текст. PDF до 6 МБ дополнительно сохраняется, чтобы уйти модели целиком.
 * Картинки и аудио возвращаются как [FileTextExtractor.Result.Media] — их
 * обрабатывают ChatImageStore и расшифровка речи.
 */
@Singleton
class AttachmentReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentStore: ChatDocumentStore = ChatDocumentStore(context)
) {
    /** Имя и размер файла по данным системы; размер -1, если неизвестен. */
    data class FileInfo(val name: String, val sizeBytes: Long)

    fun info(uri: Uri): FileInfo {
        var name = "file"
        var size = -1L
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(0)?.let { name = it }
                        if (!cursor.isNull(1)) size = cursor.getLong(1)
                    }
                }
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
        return FileInfo(name, size)
    }

    suspend fun read(uri: Uri): FileTextExtractor.Result = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val info = info(uri)
        val name = info.name
        if (info.sizeBytes > FileTextExtractor.MAX_FILE_BYTES) {
            // Аудио большого размера всё равно можно расшифровать (первые минуты).
            if (isAudioByName(name)) return@withContext FileTextExtractor.Result.Media(name, FileKind.AUDIO)
            return@withContext FileTextExtractor.Result.Failure(name, FileTextExtractor.Reason.TOO_LARGE)
        }
        val bytes = try {
            resolver.openInputStream(uri)?.use { readBounded(it) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return@withContext FileTextExtractor.Result.Failure(name, FileTextExtractor.Reason.UNREADABLE)
        if (bytes.size > FileTextExtractor.MAX_FILE_BYTES) {
            if (isAudioByName(name)) return@withContext FileTextExtractor.Result.Media(name, FileKind.AUDIO)
            return@withContext FileTextExtractor.Result.Failure(name, FileTextExtractor.Reason.TOO_LARGE)
        }
        val result = try {
            FileTextExtractor.extract(name, bytes)
        } catch (_: OutOfMemoryError) {
            FileTextExtractor.Result.Failure(name, FileTextExtractor.Reason.TOO_LARGE)
        }
        if (result is FileTextExtractor.Result.Success && result.kind == FileKind.PDF) {
            // PDF до 6 МБ уходит модели целиком: она прочтёт и сканы, и таблицы.
            val documentId = documentStore.savePdf(bytes)
            if (documentId == null && result.text.isBlank()) {
                return@withContext FileTextExtractor.Result.Failure(name, FileTextExtractor.Reason.EMPTY)
            }
            return@withContext result.copy(documentId = documentId)
        }
        result
    }

    private fun isAudioByName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr")

    /** Читает не больше лимита плюс один байт, чтобы заметить превышение. */
    private fun readBounded(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        val limit = FileTextExtractor.MAX_FILE_BYTES + 1
        while (out.size() < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
