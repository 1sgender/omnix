package com.omnix.assistant.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.omnix.assistant.R
import java.io.File

/** Ранее запущенная загрузка: переживает перезапуск приложения. */
data class PendingUpdate(
    val downloadId: Long,
    val versionCode: Long,
    val status: Int
)

/**
 * Загрузка и установка APK обновления.
 *
 * Канал — системный DownloadManager (докачка, ретраи и видимый прогресс
 * из коробки, переживает смерть процесса). Установка — через FileProvider:
 * Android 7+ запрещает отдавать установщику file:// URI.
 *
 * Файл лежит в app-specific external storage — разрешение на хранилище
 * не требуется. Для установки нужен REQUEST_INSTALL_PACKAGES (manifest)
 * и разовое согласие пользователя в системных настройках.
 */
class AppUpdateManager(private val context: Context) {

    companion object {
        const val APK_FILE_NAME = "omnix-update.apk"
        private const val PREFS = "omnix_app_update"
        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val KEY_VERSION = "version_code"
    }

    private val downloadManager: DownloadManager
        get() = context.getSystemService(DownloadManager::class.java)

    private fun prefs() =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun currentVersionCode(): Long {
        val packageManager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(context.packageName, 0)
        }
        return PackageInfoCompat.getLongVersionCode(info)
    }

    fun updateFile(): File = File(
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
        APK_FILE_NAME
    )

    /** Ставит загрузку в очередь, стирая предыдущую. Возвращает downloadId. */
    fun enqueueDownload(info: AppLatestInfo): Long {
        clearPending()
        val request = DownloadManager.Request(Uri.parse(info.url))
            .setTitle(context.getString(R.string.omnix_update_download_title))
            .setDescription(context.getString(R.string.omnix_update_download_desc))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                APK_FILE_NAME
            )
            .setAllowedOverMetered(true)
            .setMimeType("application/vnd.android.package-archive")
        val id = downloadManager.enqueue(request)
        prefs().edit()
            .putLong(KEY_DOWNLOAD_ID, id)
            .putLong(KEY_VERSION, info.versionCode)
            .apply()
        return id
    }

    /**
     * Текущее состояние запомненной загрузки (статус перечитывается из
     * DownloadManager при каждом вызове). null — запоминать нечего.
     */
    fun pendingDownload(): PendingUpdate? {
        val stored = prefs()
        if (!stored.contains(KEY_DOWNLOAD_ID)) return null
        val id = stored.getLong(KEY_DOWNLOAD_ID, -1)
        val version = stored.getLong(KEY_VERSION, 0)
        val status = queryStatus(id)
        if (id < 0 || status == null) {
            clearPending()
            return null
        }
        return PendingUpdate(downloadId = id, versionCode = version, status = status)
    }

    /** Забывает загрузку и вычищает её из DownloadManager. */
    fun clearPending() {
        val stored = prefs()
        val id = stored.getLong(KEY_DOWNLOAD_ID, -1)
        stored.edit().clear().apply()
        if (id >= 0) {
            try {
                downloadManager.remove(id)
            } catch (_: Exception) {
                // Записи уже нет — нечего чистить.
            }
        }
    }

    fun canInstallUnknownApps(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openUnknownSourcesSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Отправляет скачанный APK системному установщику. */
    fun installApk(): Boolean {
        val file = updateFile()
        if (!file.exists()) return false
        return try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.update-provider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun queryStatus(id: Long): Int? {
        if (id < 0) return null
        val cursor: Cursor = try {
            downloadManager.query(DownloadManager.Query().setFilterById(id))
        } catch (_: Exception) {
            return null
        }
        cursor.use {
            if (!it.moveToFirst()) return null
            return it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
    }
}
