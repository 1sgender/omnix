package com.omnix.assistant.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.omnix.assistant.BuildConfig
import com.omnix.assistant.R
import com.omnix.assistant.presentation.design.OmnixTheme
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Период опроса хода загрузки: DownloadManager не шлёт промежуточных событий. */
private const val DOWNLOAD_POLL_MS = 1_000L

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
fun AppUpdatePrompt() {
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

    suspend fun syncWithPending(): Boolean {
        val pending =
            withContext(Dispatchers.IO) { manager.pendingDownload() }
        val current =
            withContext(Dispatchers.IO) { manager.currentVersionCode() }
        val fileExists =
            withContext(Dispatchers.IO) { manager.updateFile().exists() }
        return when (decideResume(pending, fileExists, current)) {
            ResumeAction.INSTALL -> {
                state = UpdateUiState.Ready(pending!!.versionCode)
                true
            }
            ResumeAction.WAIT_DOWNLOAD -> {
                state = UpdateUiState.Downloading(
                    downloadId = pending!!.downloadId,
                    versionCode = pending.versionCode,
                    expectedBytes = pending.expectedBytes
                )
                true
            }
            ResumeAction.DISCARD -> {
                withContext(Dispatchers.IO) { manager.clearPending() }
                false
            }
        }
    }

    LaunchedEffect(checkKey) {
        if (syncWithPending()) return@LaunchedEffect
        val current = withContext(Dispatchers.IO) { manager.currentVersionCode() }
        val latest = checker.check(channel)
        if (latest != null && isUpdateAvailable(latest, current)) {
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
                }
            },
            onLater = { state = UpdateUiState.Idle }
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
                    if (!resumed) state = UpdateUiState.Failed(current.versionCode)
                }
            },
            onFailed = { state = UpdateUiState.Failed(current.versionCode) },
            onHide = { state = UpdateUiState.Idle }
        )
        is UpdateUiState.Ready -> AlertDialog(
            onDismissRequest = { state = UpdateUiState.Idle },
            title = { Text(stringResource(R.string.omnix_update_ready_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.omnix_update_ready_body,
                        current.versionCode
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val started = manager.installApk()
                    state = if (started) {
                        UpdateUiState.Idle
                    } else {
                        UpdateUiState.Failed(current.versionCode)
                    }
                }) {
                    Text(stringResource(R.string.omnix_update_action_install))
                }
            },
            dismissButton = {
                TextButton(onClick = { state = UpdateUiState.Idle }) {
                    Text(stringResource(R.string.omnix_update_action_later))
                }
            }
        )
        is UpdateUiState.Failed -> AlertDialog(
            onDismissRequest = { state = UpdateUiState.Idle },
            title = { Text(stringResource(R.string.omnix_update_failed_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.omnix_update_failed_body,
                        current.versionCode
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    state = UpdateUiState.Idle
                    checkKey += 1
                }) {
                    Text(stringResource(R.string.omnix_update_action_retry))
                }
            },
            dismissButton = {
                TextButton(onClick = { state = UpdateUiState.Idle }) {
                    Text(stringResource(R.string.omnix_update_action_later))
                }
            }
        )
    }
}

@Composable
private fun AvailableDialog(
    info: AppLatestInfo,
    canInstall: Boolean,
    onUpdate: () -> Unit,
    onLater: () -> Unit
) {
    val body = stringResource(R.string.omnix_update_available_body, info.versionCode)
    val hint = stringResource(R.string.omnix_update_sources_hint)
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.omnix_update_available_title)) },
        text = { Text(if (canInstall) body else "$body\n\n$hint") },
        confirmButton = {
            TextButton(onClick = onUpdate) {
                Text(stringResource(R.string.omnix_update_action_update))
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text(stringResource(R.string.omnix_update_action_later))
            }
        }
    )
}

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
    AlertDialog(
        onDismissRequest = onHide,
        title = { Text(stringResource(R.string.omnix_update_downloading_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.omnix_update_downloading_body,
                        versionCode
                    )
                )
                Spacer(Modifier.height(12.dp))
                // DownloadManager не сразу знает размер: страховка — sizeBytes
                // из ответа сервера, сохранённый при старте загрузки.
                val total = snapshot?.totalBytes?.takeIf { it > 0 } ?: expectedBytes
                if (snapshot != null && total > 0) {
                    val percent = progressPercent(snapshot.bytesSoFar, total)
                    LinearProgressIndicator(
                        progress = { snapshot.bytesSoFar.toFloat() / total },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(
                            R.string.omnix_update_progress_caption,
                            formatMegaBytes(
                                snapshot.bytesSoFar,
                                Locale.getDefault(),
                                unitMb
                            ),
                            formatMegaBytes(total, Locale.getDefault(), unitMb),
                            stringResource(R.string.omnix_percent, percent)
                        ),
                        style = OmnixTheme.typography.caption
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (snapshot != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(
                                R.string.omnix_update_progress_no_total,
                                formatMegaBytes(
                                    snapshot.bytesSoFar,
                                    Locale.getDefault(),
                                    unitMb
                                )
                            ),
                            style = OmnixTheme.typography.caption
                        )
                    }
                }
                if (snapshot?.status == DownloadManager.STATUS_PAUSED) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.omnix_update_paused_body),
                        style = OmnixTheme.typography.caption
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onHide) {
                Text(stringResource(R.string.omnix_update_action_hide))
            }
        }
    )
}
