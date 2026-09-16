package com.omnix.assistant.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.omnix.assistant.BuildConfig
import com.omnix.assistant.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data class Available(val info: AppLatestInfo) : UpdateUiState
    data class Downloading(val versionCode: Long) : UpdateUiState
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
 * На dev/prod-флейворах молча ничего не делает: dev собирается локально,
 * prod обновляется через Play. Staging проверяется раз за холодный старт;
 * «Позже» откладывает вопрос до следующего запуска.
 */
@Composable
fun AppUpdatePrompt() {
    if (BuildConfig.FLAVOR != "staging") return
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
                state = UpdateUiState.Downloading(pending!!.versionCode)
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
        val latest = checker.check(BuildConfig.FLAVOR)
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
                    withContext(Dispatchers.IO) { manager.enqueueDownload(current.info) }
                    state = UpdateUiState.Downloading(current.info.versionCode)
                }
            },
            onLater = { state = UpdateUiState.Idle }
        )
        is UpdateUiState.Downloading -> AlertDialog(
            onDismissRequest = { state = UpdateUiState.Idle },
            title = { Text(stringResource(R.string.omnix_update_downloading_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.omnix_update_downloading_body,
                        current.versionCode
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { state = UpdateUiState.Idle }) {
                    Text(stringResource(R.string.omnix_update_action_hide))
                }
            }
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
