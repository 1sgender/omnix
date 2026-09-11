package com.jarvis.assistant.agent.localai.downloader

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Статус загрузки файла модели — снимок одного опроса DownloadManager.
 */
enum class ModelDownloadStatus {
    RUNNING,
    PAUSED,
    SUCCESS,
    FAILED
}

/**
 * Наблюдение за загрузкой.
 *
 * @param reason системный код причины (DownloadManager.COLUMN_REASON).
 * Человеческий текст — только через [ModelDownloadPolicy.reasonText].
 */
data class ModelDownloadObservation(
    val status: ModelDownloadStatus,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val reason: Int = 0
)

/**
 * Загрузка файла модели через системный DownloadManager.
 *
 * Вынесена за интерфейс, чтобы менеджер модели не зависел от Android-фреймворка
 * напрямую и решения тестировались на JVM с фейком. Реализация переживает
 * смерть процесса: загрузка принадлежит системе, а не приложению.
 */
interface ModelDownloader {

    /**
     * Ставит файл в очередь. [destFile] обязан лежать в приватном каталоге
     * приложения (filesDir) — тогда не нужны storage-разрешения.
     *
     * @return системный downloadId для [observe] и [cancel].
     */
    fun enqueue(
        url: String,
        destFile: File,
        title: String,
        allowedOverMetered: Boolean
    ): Long

    /** Снимок состояния или null, если загрузка с таким id неизвестна системе. */
    fun observe(downloadId: Long): ModelDownloadObservation?

    /** Отменяет загрузку и удаляет недокачанный файл. */
    fun cancel(downloadId: Long)
}

/**
 * Чистые решения вокруг загрузки — без Android-зависимостей, покрыты JVM-тестами.
 */
object ModelDownloadPolicy {

    /** Одноразовое согласие пользователя на загрузку ~521 МБ. */
    const val CONSENT_UNASKED = "unasked"
    const val CONSENT_ANY_NETWORK = "any"
    const val CONSENT_WIFI_ONLY = "wifi"
    const val CONSENT_LATER = "later"

    /** downloadId отсутствует (ничего не ставилось в очередь). */
    const val NO_DOWNLOAD_ID = -1L

    /** Как часто опрашиваем DownloadManager, пока идёт загрузка. */
    const val POLL_INTERVAL_MS = 1000L

    /** Можно ли стартовать загрузку при таком согласии. */
    fun mayDownload(consent: String): Boolean =
        consent == CONSENT_ANY_NETWORK || consent == CONSENT_WIFI_ONLY

    /** Разрешён ли мобильный интернет при таком согласии. */
    fun allowedOverMetered(consent: String): Boolean = consent == CONSENT_ANY_NETWORK

    /**
     * Процент прогресса 0..100. При неизвестном размере (total <= 0)
     * честно возвращает 0, а не выдуманное число.
     */
    fun progressPercent(downloadedBytes: Long, totalBytes: Long): Int {
        if (totalBytes <= 0 || downloadedBytes <= 0) return 0
        return ((downloadedBytes * 100) / totalBytes).coerceIn(0L, 100L).toInt()
    }

    /**
     * Человеческий текст причины провала.
     *
     * Коды — DownloadManager.ERROR_* (1..1006). Неизвестный код не
     * притворяется понятным: возвращаем его как есть для диагностики.
     */
    fun reasonText(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE ->
            "Недостаточно места на устройстве"
        DownloadManager.ERROR_DEVICE_NOT_FOUND ->
            "Внутреннее хранилище недоступно"
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_UNHANDLED_HTTP_CODE ->
            "Сервер оборвал загрузку (HTTP $reason)"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS ->
            "Слишком много перенаправлений"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS ->
            "Файл уже существует"
        DownloadManager.ERROR_CANNOT_RESUME ->
            "Не удалось продолжить загрузку"
        DownloadManager.ERROR_FILE_ERROR ->
            "Ошибка записи файла"
        else -> "Загрузка не удалась (код $reason)"
    }
}

/**
 * Реализация поверх системного DownloadManager.
 *
 * Почему DownloadManager, а не свой OkHttp-цикл: система сама переживает
 * смерть процесса и перезагрузку, сама ждёт Wi-Fi при allowedOverMetered=false,
 * сама докачивает и показывает прогресс в шторке. Нам остаётся только
 * опрашивать статус и сверить размер файла в конце.
 */
@Singleton
class DownloadManagerModelDownloader @Inject constructor(
    @ApplicationContext private val context: Context
) : ModelDownloader {

    override fun enqueue(
        url: String,
        destFile: File,
        title: String,
        allowedOverMetered: Boolean
    ): Long {
        // DownloadManager упадёт с FILE_ALREADY_EXISTS, если файл уже есть, —
        // недокачанный остаток удаляем заранее (целостность всё равно
        // проверяется точным размером после финиша).
        if (destFile.exists()) destFile.delete()
        destFile.parentFile?.mkdirs()

        val manager = context.getSystemService(DownloadManager::class.java)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDescription("Локальная модель JARVIS")
            .setDestinationUri(Uri.fromFile(destFile))
            .setAllowedOverMetered(allowedOverMetered)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
        return manager.enqueue(request)
    }

    override fun observe(downloadId: Long): ModelDownloadObservation? {
        val manager = context.getSystemService(DownloadManager::class.java)
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
            ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val statusIdx = it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val bytesIdx = it.getColumnIndexOrThrow(
                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
            )
            val totalIdx = it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val reasonIdx = it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
            val status = when (it.getInt(statusIdx)) {
                DownloadManager.STATUS_SUCCESSFUL -> ModelDownloadStatus.SUCCESS
                DownloadManager.STATUS_FAILED -> ModelDownloadStatus.FAILED
                DownloadManager.STATUS_PAUSED -> ModelDownloadStatus.PAUSED
                else -> ModelDownloadStatus.RUNNING
            }
            return ModelDownloadObservation(
                status = status,
                downloadedBytes = it.getLong(bytesIdx),
                totalBytes = it.getLong(totalIdx),
                reason = it.getInt(reasonIdx)
            )
        }
    }

    override fun cancel(downloadId: Long) {
        context.getSystemService(DownloadManager::class.java).remove(downloadId)
    }
}
