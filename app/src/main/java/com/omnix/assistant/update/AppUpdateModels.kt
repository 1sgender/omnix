package com.omnix.assistant.update

import android.app.DownloadManager
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Зеркало серверного ответа GET /v1/app/latest. */
@Serializable
data class AppLatestInfo(
    @SerialName("success") val success: Boolean = false,
    @SerialName("channel") val channel: String = "",
    @SerialName("versionCode") val versionCode: Long = 0L,
    @SerialName("url") val url: String = "",
    @SerialName("sizeBytes") val sizeBytes: Long = 0L,
    @SerialName("sha") val sha: String = "",
    @SerialName("publishedAt") val publishedAt: String = "",
    @SerialName("stale") val stale: Boolean = false
)

private val appLatestJson = Json { ignoreUnknownKeys = true }

/** tolerant-парсинг ответа сервера: мусор превращается в null, а не в краш. */
fun parseAppLatest(body: String): AppLatestInfo? = try {
    appLatestJson.decodeFromString(AppLatestInfo.serializer(), body)
} catch (_: Exception) {
    null
}

/**
 * Чистое решение «обновляться ли»: только успешный ответ с https-ссылкой
 * и СТРОГО большим versionCode. Равный или меньший код — не обновление:
 * защита от даунгрейда через stale-кэш и от повторного предложения
 * после переустановки той же сборки.
 */
fun isUpdateAvailable(latest: AppLatestInfo?, currentVersionCode: Long): Boolean {
    if (latest == null || !latest.success) return false
    if (latest.versionCode <= currentVersionCode) return false
    return latest.url.startsWith("https://")
}

/** Решение по незавершённой загрузке из прошлой сессии. */
enum class ResumeAction {
    /** Файл цел и новее текущего — предложить установку. */
    INSTALL,
    /** Загрузка ещё идёт — ждать завершения молча. */
    WAIT_DOWNLOAD,
    /** Мусор (устарело/провалено/файла нет) — вычистить и проверить заново. */
    DISCARD
}

/**
 * Чистое решение по возобновлению. Константы статусов DownloadManager —
 * compile-time inline, поэтому функция безопасна для JVM-тестов.
 */
fun decideResume(
    pending: PendingUpdate?,
    fileExists: Boolean,
    currentVersionCode: Long
): ResumeAction {
    if (pending == null) return ResumeAction.DISCARD
    if (pending.versionCode <= currentVersionCode) return ResumeAction.DISCARD
    return when (pending.status) {
        DownloadManager.STATUS_SUCCESSFUL ->
            if (fileExists) ResumeAction.INSTALL else ResumeAction.DISCARD
        DownloadManager.STATUS_RUNNING,
        DownloadManager.STATUS_PAUSED,
        DownloadManager.STATUS_PENDING -> ResumeAction.WAIT_DOWNLOAD
        else -> ResumeAction.DISCARD
    }
}
