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
 *
 * Блок живёт внутри OmnixSettingsGroup, поэтому строки — inset, а между
 * ними инсетный hairline.
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
                value = stringResource(R.string.omnix_local_model_not_installed),
                inset = true
            )
            OmnixGroupDivider()
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_download_now),
                inset = true,
                onClick = onDownloadAny
            )
            OmnixGroupDivider()
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_download_wifi),
                inset = true,
                onClick = onDownloadWifi
            )
        }

        is LocalModelState.Downloading -> {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_title),
                subtitle = stringResource(
                    R.string.omnix_local_model_downloading,
                    state.progressPercent
                ),
                inset = true
            )
            OmnixGroupDivider()
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_cancel),
                inset = true,
                onClick = onCancelDownload
            )
        }

        is LocalModelState.DownloadFailed -> {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_title),
                subtitle = stringResource(
                    R.string.omnix_local_model_download_failed,
                    state.reason
                ),
                inset = true
            )
            OmnixGroupDivider()
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_download_now),
                inset = true,
                onClick = onDownloadAny
            )
            OmnixGroupDivider()
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_download_wifi),
                inset = true,
                onClick = onDownloadWifi
            )
        }

        is LocalModelState.NotInitialized -> {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_title),
                subtitle = stringResource(R.string.omnix_local_model_downloaded),
                inset = true
            )
        }

        is LocalModelState.Loading -> {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_title),
                subtitle = stringResource(R.string.omnix_local_model_loading),
                inset = true
            )
        }

        is LocalModelState.Ready -> {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_title),
                subtitle = stringResource(R.string.omnix_local_model_ready),
                inset = true
            )
        }

        is LocalModelState.Failed -> {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_title),
                subtitle = stringResource(
                    R.string.omnix_local_model_failed,
                    state.reason
                ),
                inset = true
            )
        }

        is LocalModelState.InsufficientMemory -> {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_local_model_title),
                subtitle = stringResource(
                    R.string.omnix_local_model_low_memory,
                    state.requiredMb
                ),
                inset = true
            )
        }
    }
}
