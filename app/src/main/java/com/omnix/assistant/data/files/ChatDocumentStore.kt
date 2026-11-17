package com.omnix.assistant.data.files

import android.content.Context
import android.util.Base64
import com.omnix.assistant.domain.files.ChatDocumentSource
import com.omnix.assistant.domain.files.ChatImageCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PDF из чата для отправки модели целиком (Gemini и Claude читают PDF сами,
 * включая сканы и таблицы). В сообщении хранится только id файла.
 */
@Singleton
class ChatDocumentStore @Inject constructor(
    @ApplicationContext private val context: Context
) : ChatDocumentSource {

    private val dir: File
        get() = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** Сохраняет PDF; null — не удалось записать. */
    fun savePdf(bytes: ByteArray): String? {
        if (bytes.size > MAX_NATIVE_PDF_BYTES) return null
        val id = UUID.randomUUID().toString().replace("-", "")
        return try {
            File(dir, "$id.pdf").writeBytes(bytes)
            id
        } catch (_: IOException) {
            null
        }
    }

    private fun fileFor(id: String): File? =
        if (ChatImageCodec.isValidId(id)) File(dir, "$id.pdf").takeIf { it.isFile } else null

    override fun base64Pdf(id: String): String? {
        val file = fileFor(id) ?: return null
        return try {
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        } catch (_: IOException) {
            null
        }
    }

    fun delete(id: String) {
        fileFor(id)?.delete()
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val DIR_NAME = "chat_documents"

        /** Сервер принимает PDF до 6 МБ; больше — модели уходит только текст. */
        const val MAX_NATIVE_PDF_BYTES = 6_000_000
    }
}
