package com.omnix.assistant.data.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.omnix.assistant.domain.files.FileTextExtractor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Читает файл, выбранный пользователем в системном окне, и достаёт из него текст. */
@Singleton
class AttachmentReader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun read(uri: Uri): FileTextExtractor.Result = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "file"
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(0)?.let { name = it }
                        if (!cursor.isNull(1)) size = cursor.getLong(1)
                    }
                }
        } catch (_: SecurityException) {
            return@withContext FileTextExtractor.Result.Failure(name, FileTextExtractor.Reason.UNREADABLE)
        }
        if (size > FileTextExtractor.MAX_FILE_BYTES) {
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
            return@withContext FileTextExtractor.Result.Failure(name, FileTextExtractor.Reason.TOO_LARGE)
        }
        FileTextExtractor.extract(name, resolver.getType(uri), bytes)
    }

    /** Читает не больше лимита плюс один байт, чтобы заметить превышение. */
    private fun readBounded(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        val limit = FileTextExtractor.MAX_FILE_BYTES + 1
        while (out.size() < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
