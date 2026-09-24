package com.omnix.assistant.update

import android.app.DownloadManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Снимок хода OTA-загрузки для «окружающей» дуги на ядре.
 * totalBytes = 0 — размер ещё неизвестен (дуга скрывается).
 */
data class OtaDownloadSnapshot(
    val downloadId: Long,
    val versionCode: Long,
    val bytesSoFar: Long,
    val totalBytes: Long
)

/**
 * Живой ход OTA-загрузки — источник прогресса для дуги ядра (дизайн-матрица:
 * «дуга OTA»). Опрашивает DownloadManager независимо от видимости диалога:
 * пользователь скрыл окно загрузки — дуга продолжает показывать ход.
 *
 * Диалог [AppUpdateDialog] вызывает [begin] на старте/возобновлении загрузки и
 * [end] на терминальных исходах; [OmnixViewModel] читает [snapshot]. Источник
 * прогресса подаётся лямбдой — монитор остаётся чистым Kotlin для JVM-тестов.
 *
 * DownloadManager не шлёт промежуточных событий — только опрос (как и в
 * диалоге); константы статусов compile-time inline, JVM-безопасны.
 */
@Singleton
class OtaDownloadMonitor internal constructor(private val pollMs: Long) {

    @Inject
    constructor() : this(DEFAULT_POLL_MS)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _snapshot = MutableStateFlow<OtaDownloadSnapshot?>(null)

    /** Ход активной загрузки; null — загрузки нет (дуга скрыта). */
    val snapshot: StateFlow<OtaDownloadSnapshot?> = _snapshot

    private var pollJob: Job? = null

    /**
     * Начать (или заменить) слежение за загрузкой [downloadId]. [poll] возвращает
     * снимок DownloadManager либо null, если запись исчезла (тогда слежение
     * завершается — загрузку снесли вне приложения).
     */
    fun begin(
        downloadId: Long,
        versionCode: Long,
        poll: suspend () -> DownloadProgress?
    ) {
        pollJob?.cancel()
        // Первичный снимок до первого опроса: размер неизвестен → дуги ещё нет.
        _snapshot.value = OtaDownloadSnapshot(downloadId, versionCode, 0L, 0L)
        pollJob = scope.launch {
            while (true) {
                val progress = poll()
                if (progress == null) {
                    end()
                    break
                }
                _snapshot.value = OtaDownloadSnapshot(
                    downloadId = downloadId,
                    versionCode = versionCode,
                    bytesSoFar = progress.bytesSoFar,
                    totalBytes = progress.totalBytes
                )
                when (progress.status) {
                    DownloadManager.STATUS_SUCCESSFUL,
                    DownloadManager.STATUS_FAILED -> {
                        end()
                        break
                    }

                    else -> delay(pollMs)
                }
            }
        }
    }

    /** Прекратить слежение и погасить дугу (успех, ошибка, сброс). */
    fun end() {
        pollJob?.cancel()
        pollJob = null
        _snapshot.value = null
    }

    companion object {
        /** Как в диалоге: 1 Гц достаточно и для полосы, и для дуги. */
        const val DEFAULT_POLL_MS = 1_000L
    }
}
