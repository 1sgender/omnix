package com.omnix.assistant.data.files

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import com.omnix.assistant.domain.files.ChatImageCodec
import com.omnix.assistant.domain.files.ChatImageRules
import com.omnix.assistant.domain.files.ChatImageSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Картинки чата: берёт снимок из галереи или камеры, уменьшает (длинная сторона
 * до [ChatImageRules.MAX_SIDE_PX]), сжимает в JPEG и кладёт в память приложения.
 * Ссылка на файл (id) попадает в текст сообщения, см. [ChatImageCodec].
 */
// minSdk 29: android.media.ExifInterface читает EXIF из потока без лишней зависимости.
@SuppressLint("ExifInterface")
@Singleton
class ChatImageStore @Inject constructor(
    @ApplicationContext private val context: Context
) : ChatImageSource {

    sealed interface ImportResult {
        data class Success(val id: String) : ImportResult
        data class Failure(val reason: ChatImageRules.Reject) : ImportResult
    }

    private val dir: File
        get() = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** Читает выбранную картинку, сжимает и сохраняет. */
    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            importBlocking(uri)
        } catch (_: OutOfMemoryError) {
            ImportResult.Failure(ChatImageRules.Reject.TOO_LARGE)
        } catch (_: IOException) {
            ImportResult.Failure(ChatImageRules.Reject.UNREADABLE)
        } catch (_: SecurityException) {
            ImportResult.Failure(ChatImageRules.Reject.UNREADABLE)
        }
    }

    private fun importBlocking(uri: Uri): ImportResult {
        val resolver = context.contentResolver

        val header = ByteArray(HEADER_BYTES)
        val headerStream = resolver.openInputStream(uri)
            ?: return ImportResult.Failure(ChatImageRules.Reject.UNREADABLE)
        val headerRead = headerStream.use { it.read(header) }
        if (headerRead <= 0) return ImportResult.Failure(ChatImageRules.Reject.UNREADABLE)
        val mime = ChatImageRules.detectMime(header.copyOf(headerRead))
        ChatImageRules.validateSource(mime, querySize(uri))
            ?.let { return ImportResult.Failure(it) }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(uri)
            ?: return ImportResult.Failure(ChatImageRules.Reject.UNREADABLE)
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        ChatImageRules.validatePixels(bounds.outWidth, bounds.outHeight)
            ?.let { return ImportResult.Failure(it) }

        val options = BitmapFactory.Options().apply {
            inSampleSize = ChatImageRules.sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decodeStream = resolver.openInputStream(uri)
            ?: return ImportResult.Failure(ChatImageRules.Reject.UNREADABLE)
        val decoded = decodeStream.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return ImportResult.Failure(ChatImageRules.Reject.UNREADABLE)

        val rotation = if (mime == "image/jpeg") readRotation(uri) else 0
        val prepared = prepare(decoded, rotation)
        val bytes: ByteArray? = try {
            encode(prepared)
        } finally {
            prepared.recycle()
        }
        if (bytes == null) return ImportResult.Failure(ChatImageRules.Reject.TOO_LARGE)

        val id = UUID.randomUUID().toString().replace("-", "")
        File(dir, "$id.jpg").writeBytes(bytes)
        return ImportResult.Success(id)
    }

    /** Уменьшает до лимита, поворачивает по EXIF и заливает прозрачность белым (JPEG её не умеет). */
    private fun prepare(source: Bitmap, rotationDegrees: Int): Bitmap {
        val (targetW, targetH) = ChatImageRules.targetSize(source.width, source.height)
        val matrix = Matrix()
        if (targetW != source.width || targetH != source.height) {
            matrix.postScale(targetW.toFloat() / source.width, targetH.toFloat() / source.height)
        }
        if (rotationDegrees != 0) matrix.postRotate(rotationDegrees.toFloat())
        var result = source
        if (!matrix.isIdentity) {
            result = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            if (result !== source) source.recycle()
        }
        if (result.hasAlpha()) {
            val flat = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(flat)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(result, 0f, 0f, null)
            result.recycle()
            result = flat
        }
        return result
    }

    /** JPEG ~80%; если выходит крупнее лимита, качество снижается. null: уложиться не удалось. */
    private fun encode(bitmap: Bitmap): ByteArray? {
        for (quality in ChatImageRules.QUALITY_STEPS) {
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
            if (out.size() <= ChatImageRules.MAX_ENCODED_BYTES) return out.toByteArray()
        }
        return null
    }

    private fun readRotation(uri: Uri): Int {
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return 0
        return try {
            stream.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (_: IOException) {
            0
        }
    }

    private fun querySize(uri: Uri): Long = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L
    } catch (_: SecurityException) {
        -1L
    } catch (_: IllegalArgumentException) {
        -1L
    }

    private fun fileFor(id: String): File? =
        if (ChatImageCodec.isValidId(id)) File(dir, "$id.jpg").takeIf { it.isFile } else null

    /** Готовый JPEG в base64 для отправки в облако. */
    override fun base64Jpeg(id: String): String? {
        val file = fileFor(id) ?: return null
        return try {
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        } catch (_: IOException) {
            null
        }
    }

    /** Миниатюра для ленты и для превью над полем ввода. */
    fun thumbnail(id: String, maxSidePx: Int): Bitmap? {
        val file = fileFor(id) ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = ChatImageRules.sampleSize(bounds.outWidth, bounds.outHeight, maxSidePx)
            }
            BitmapFactory.decodeFile(file.path, options)
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    fun delete(id: String) {
        fileFor(id)?.delete()
    }

    /** Стирает все картинки чата (очистка истории). */
    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Убирает временные снимки камеры: в чат уже попала сжатая копия. */
    fun clearCameraCaptures() {
        File(context.cacheDir, CAMERA_DIR).listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val DIR_NAME = "chat_images"
        private const val CAMERA_DIR = "shared/camera"
        private const val HEADER_BYTES = 16

        /** Куда камера сложит снимок: файл в cache/shared, отдаём через FileProvider приложения. */
        fun newCameraUri(context: Context): Uri {
            val dir = File(context.cacheDir, CAMERA_DIR).apply { mkdirs() }
            val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
            return FileProvider.getUriForFile(context, "${context.packageName}.files-provider", file)
        }
    }
}
