package com.omnix.assistant.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.omnix.assistant.BuildConfig
import com.omnix.assistant.R
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Период опроса хода загрузки: DownloadManager не шлёт промежуточных событий. */
private const val DOWNLOAD_POLL_MS = 1_000L

/** Повторная проверка обновления при возврате в приложение — не чаще. */
private const val RECHECK_INTERVAL_MS = 30 * 60 * 1_000L

/** Нет отложенной версии. */
private const val NO_VERSION = -1L

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data class Available(val info: AppLatestInfo) : UpdateUiState
    data class Downloading(
        val downloadId: Long,
        val versionCode: Long,
        val expectedBytes: Long
    ) : UpdateUiState
    data class Ready(val versionCode: Long) : UpdateUiState
    data class Failed(val versionCode: Long) : UpdateUiState
}

/**
 * Точка входа самообновления: вызывать из MainActivity вне ветвления
 * экранов — на всех этапах, включая активацию и онбординг.
 *
 * Проверка до активации обязательна: иначе баг, ломающий активацию,
 * можно исправить только ручной переустановкой APK.
 *
 * Dev-сборки исключены: они могут быть подписаны временным debug-ключом и
 * не способны безопасно заменять опубликованный APK. Подписанные staging и
 * prod сборки проверяют свой канал раз за холодный старт; «Позже» откладывает
 * вопрос до следующего запуска.
 *
 * Android всё равно требует системного подтверждения установки для обычного
 * приложения. Пользователь больше не ищет и не скачивает APK вручную: OMNIX
 * сам находит и загружает совместимое обновление.
 */
@Composable
fun AppUpdatePrompt(otaMonitor: OtaDownloadMonitor) {
    val channel = directUpdateChannel(
        flavor = BuildConfig.FLAVOR,
        buildType = BuildConfig.BUILD_TYPE
    ) ?: return
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val manager = remember(appContext) { AppUpdateManager(appContext) }
    val checker = remember { AppUpdateChecker() }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var checkKey by remember { mutableIntStateOf(0) }
    var lastCheckAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    // «Позже» откладывает эту версию до следующего запуска, а не до следующего возврата.
    var postponedVersion by remember { mutableLongStateOf(NO_VERSION) }

    // Проверка не только при холодном старте: процесс живёт долго (служба
    // ассистента), и новая версия не появлялась, пока приложение не убить
    // (владелец 2026-10-21). При возврате в приложение — не чаще раза в 30 мин.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                state is UpdateUiState.Idle &&
                SystemClock.elapsedRealtime() - lastCheckAt >= RECHECK_INTERVAL_MS
            ) {
                checkKey += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    suspend fun syncWithPending(): Boolean {
        val pending =
            withContext(Dispatchers.IO) { manager.pendingDownload() }
        val current =
            withContext(Dispatchers.IO) { manager.currentVersionCode() }
        val fileExists =
            withContext(Dispatchers.IO) { manager.updateFile().exists() }
        return when (decideResume(pending, fileExists, current)) {
            ResumeAction.INSTALL -> {
                // Загрузка завершена — дугу ядра гасим.
                otaMonitor.end()
                state = UpdateUiState.Ready(pending!!.versionCode)
                true
            }
            ResumeAction.WAIT_DOWNLOAD -> {
                state = UpdateUiState.Downloading(
                    downloadId = pending!!.downloadId,
                    versionCode = pending.versionCode,
                    expectedBytes = pending.expectedBytes
                )
                // Дуга ядра живёт независимо от видимости этого диалога.
                watchDownload(otaMonitor, manager, pending.downloadId, pending.versionCode)
                true
            }
            ResumeAction.DISCARD -> {
                withContext(Dispatchers.IO) { manager.clearPending() }
                otaMonitor.end()
                false
            }
        }
    }

    LaunchedEffect(checkKey) {
        lastCheckAt = SystemClock.elapsedRealtime()
        if (syncWithPending()) return@LaunchedEffect
        val current = withContext(Dispatchers.IO) { manager.currentVersionCode() }
        val latest = checker.check(channel)
        if (latest != null && isUpdateAvailable(latest, current) && latest.versionCode != postponedVersion) {
            state = UpdateUiState.Available(latest)
        }
    }

    val downloadReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val finishedId =
                    intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                scope.launch {
                    val pending =
                        withContext(Dispatchers.IO) { manager.pendingDownload() }
                    if (pending == null || pending.downloadId != finishedId) return@launch
                    if (pending.status == DownloadManager.STATUS_FAILED) {
                        val failedVersion = pending.versionCode
                        withContext(Dispatchers.IO) { manager.clearPending() }
                        otaMonitor.end()
                        state = UpdateUiState.Failed(failedVersion)
                    } else {
                        syncWithPending()
                    }
                }
            }
        }
    }
    DisposableEffect(appContext) {
        ContextCompat.registerReceiver(
            appContext,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { appContext.unregisterReceiver(downloadReceiver) }
    }

    when (val current = state) {
        is UpdateUiState.Idle -> Unit
        is UpdateUiState.Available -> AvailableDialog(
            info = current.info,
            canInstall = remember { manager.canInstallUnknownApps() },
            onUpdate = {
                if (!manager.canInstallUnknownApps()) {
                    manager.openUnknownSourcesSettings()
                    return@AvailableDialog
                }
                scope.launch {
                    val id = withContext(Dispatchers.IO) {
                        manager.enqueueDownload(current.info)
                    }
                    state = UpdateUiState.Downloading(
                        downloadId = id,
                        versionCode = current.info.versionCode,
                        expectedBytes = current.info.sizeBytes
                    )
                    watchDownload(otaMonitor, manager, id, current.info.versionCode)
                }
            },
            onLater = {
                postponedVersion = current.info.versionCode
                state = UpdateUiState.Idle
            }
        )
        is UpdateUiState.Downloading -> DownloadingDialog(
            downloadId = current.downloadId,
            versionCode = current.versionCode,
            expectedBytes = current.expectedBytes,
            manager = manager,
            onCompleted = {
                scope.launch {
                    // SUCCESSFUL в опросе: свериться с файлом и показать «Готово».
                    val resumed = syncWithPending()
                    if (!resumed) {
                        otaMonitor.end()
                        state = UpdateUiState.Failed(current.versionCode)
                    }
                }
            },
            onFailed = {
                otaMonitor.end()
                state = UpdateUiState.Failed(current.versionCode)
            },
            onHide = { state = UpdateUiState.Idle }
        )
        is UpdateUiState.Ready -> UpdateScreen(
            stage = UpdateStage.READY,
            title = stringResource(R.string.omnix_update_ready_title),
            onDismiss = { state = UpdateUiState.Idle },
            chip = stringResource(R.string.omnix_update_version_only, current.versionCode),
            body = stringResource(R.string.omnix_update_ready_body),
            primary = UpdateAction(stringResource(R.string.omnix_update_action_install)) {
                val started = manager.installApk()
                state = if (started) {
                    UpdateUiState.Idle
                } else {
                    UpdateUiState.Failed(current.versionCode)
                }
            },
            secondary = UpdateAction(stringResource(R.string.omnix_update_action_later)) {
                state = UpdateUiState.Idle
            }
        )
        is UpdateUiState.Failed -> UpdateScreen(
            stage = UpdateStage.FAILED,
            title = stringResource(R.string.omnix_update_failed_title),
            onDismiss = { state = UpdateUiState.Idle },
            chip = stringResource(R.string.omnix_update_version_only, current.versionCode),
            body = stringResource(R.string.omnix_update_failed_body, current.versionCode),
            primary = UpdateAction(stringResource(R.string.omnix_update_action_retry)) {
                state = UpdateUiState.Idle
                checkKey += 1
            },
            secondary = UpdateAction(stringResource(R.string.omnix_update_action_later)) {
                state = UpdateUiState.Idle
            }
        )
    }
}

/**
 * Подключить монитор к загрузке: он опрашивает DownloadManager параллельно
 * диалогу (запрос дешёвый — локальный content provider) и переживает скрытие
 * диалога, питая дугу ядра до терминального исхода.
 */
private fun watchDownload(
    otaMonitor: OtaDownloadMonitor,
    manager: AppUpdateManager,
    downloadId: Long,
    versionCode: Long
) {
    otaMonitor.begin(downloadId, versionCode) {
        withContext(Dispatchers.IO) { manager.queryProgress(downloadId) }
    }
}

@Composable
private fun AvailableDialog(
    info: AppLatestInfo,
    canInstall: Boolean,
    onUpdate: () -> Unit,
    onLater: () -> Unit
) {
    val context = LocalContext.current
    val size = remember(info.sizeBytes) {
        info.sizeBytes.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) }
    }
    UpdateScreen(
        stage = UpdateStage.AVAILABLE,
        title = stringResource(R.string.omnix_update_available_title),
        onDismiss = onLater,
        chip = if (size != null) {
            stringResource(R.string.omnix_update_version_chip, info.versionCode, size)
        } else {
            stringResource(R.string.omnix_update_version_only, info.versionCode)
        },
        // Сервер с «что нового» — список; без него — общая строка.
        body = if (info.notes.isEmpty()) stringResource(R.string.omnix_update_available_body) else null,
        notes = info.notes.filter { it.isNotBlank() }.take(MAX_NOTES),
        hint = if (canInstall) null else stringResource(R.string.omnix_update_sources_hint),
        primary = UpdateAction(stringResource(R.string.omnix_update_action_update), onUpdate),
        secondary = UpdateAction(stringResource(R.string.omnix_update_action_later), onLater)
    )
}

/** Сколько строк «что нового» помещается без прокрутки. */
private const val MAX_NOTES = 6

/**
 * Диалог загрузки с живым прогрессом: раз в секунду опрашивает
 * DownloadManager и показывает полосу, мегабайты и процент. Опрос —
 * единственный способ увидеть ход: broadcast приходит только по завершению.
 */
@Composable
private fun DownloadingDialog(
    downloadId: Long,
    versionCode: Long,
    expectedBytes: Long,
    manager: AppUpdateManager,
    onCompleted: () -> Unit,
    onFailed: () -> Unit,
    onHide: () -> Unit
) {
    var progress by remember { mutableStateOf<DownloadProgress?>(null) }

    LaunchedEffect(downloadId) {
        while (true) {
            val snapshot = withContext(Dispatchers.IO) {
                manager.queryProgress(downloadId)
            }
            // Запись исчезла — загрузку снесли вне приложения; предложить retry.
            if (snapshot == null) {
                onFailed()
                break
            }
            progress = snapshot
            when (snapshot.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    onCompleted()
                    break
                }
                DownloadManager.STATUS_FAILED -> {
                    onFailed()
                    break
                }
                else -> delay(DOWNLOAD_POLL_MS)
            }
        }
    }

    val snapshot = progress
    val unitMb = stringResource(R.string.omnix_update_unit_mb)
    // DownloadManager не сразу знает размер: страховка — sizeBytes из ответа
    // сервера, сохранённый при старте загрузки.
    val total = snapshot?.totalBytes?.takeIf { it > 0 } ?: expectedBytes
    val soFar = snapshot?.bytesSoFar
    val known = soFar != null && total > 0
    val caption = when {
        snapshot == null -> null
        known -> stringResource(
            R.string.omnix_update_progress_caption,
            formatMegaBytes(snapshot.bytesSoFar, Locale.getDefault(), unitMb),
            formatMegaBytes(total, Locale.getDefault(), unitMb)
        )
        else -> stringResource(
            R.string.omnix_update_progress_no_total,
            formatMegaBytes(snapshot.bytesSoFar, Locale.getDefault(), unitMb)
        )
    }
    UpdateScreen(
        stage = UpdateStage.DOWNLOADING,
        title = stringResource(R.string.omnix_update_downloading_title),
        onDismiss = onHide,
        progress = if (soFar != null && total > 0) soFar.toFloat() / total else null,
        centerLabel = if (soFar != null && total > 0) {
            stringResource(R.string.omnix_percent, progressPercent(soFar, total))
        } else {
            null
        },
        chip = stringResource(R.string.omnix_update_version_only, versionCode),
        body = caption,
        hint = stringResource(
            if (snapshot?.status == DownloadManager.STATUS_PAUSED) {
                R.string.omnix_update_paused_body
            } else {
                R.string.omnix_update_downloading_body
            }
        ),
        secondary = UpdateAction(stringResource(R.string.omnix_update_action_hide), onHide),
        secondaryOutlined = true
    )
}
