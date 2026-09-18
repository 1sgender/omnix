package com.omnix.assistant.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.omnix.assistant.R
import com.omnix.assistant.agent.localai.LocalModelState

/**
 * Статус и управление локальной моделью в AI-разделе настроек.
 *
 * Состояния честно различаются: «не скачана» (две явные кнопки — сейчас
 * или по Wi-Fi), «качается» (процент + отмена), «скачана» (включится
 * лениво), «готова». Никаких скрытых смыслов у тапа нет: каждая кнопка
 * делает ровно то, что написано.
 */
@Composable
fun LocalModelSettingsBlock(
    state: LocalModelState,
    onDownloadAny: () -> Unit,
    onDownloadWifi: () -> Unit,
    onCancelDownload: () -> Unit
) {
    when (state) {
        is LocalModelState.NotInstalled -> {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_title),
                subtitle = stringResource(R.string.omnix_local_model_not_installed_body),
                value = stringResource(R.string.omnix_local_model_not_installed)
            )
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_download_now),
                onClick = onDownloadAny
            )
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_download_wifi),
                onClick = onDownloadWifi
            )
        }

        is LocalModelState.Downloading -> {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_title),
                subtitle = stringResource(
                    R.string.omnix_local_model_downloading,
                    state.progressPercent
                )
            )
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_cancel),
                onClick = onCancelDownload
            )
        }

        is LocalModelState.DownloadFailed -> {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_title),
                subtitle = stringResource(
                    R.string.omnix_local_model_download_failed,
                    state.reason
                )
            )
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_download_now),
                onClick = onDownloadAny
            )
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_download_wifi),
                onClick = onDownloadWifi
            )
        }

        is LocalModelState.NotInitialized -> {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_title),
                subtitle = stringResource(R.string.omnix_local_model_downloaded)
            )
        }

        is LocalModelState.Loading -> {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_title),
                subtitle = stringResource(R.string.omnix_local_model_loading)
            )
        }

        is LocalModelState.Ready -> {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_title),
                subtitle = stringResource(R.string.omnix_local_model_ready)
            )
        }

        is LocalModelState.Failed -> {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_title),
                subtitle = stringResource(
                    R.string.omnix_local_model_failed,
                    state.reason
                )
            )
        }

        is LocalModelState.InsufficientMemory -> {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_title),
                subtitle = stringResource(
                    R.string.omnix_local_model_low_memory,
                    state.requiredMb
                )
            )
        }
    }
}
