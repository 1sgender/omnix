package com.jarvis.assistant.presentation.localmodel

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.jarvis.assistant.R

/**
 * Одноразовый вопрос после активации: качать ли локальную модель (521 МБ).
 *
 * Показывается один раз — дальше выбор хранится в настройках, а управление
 * переезжает в AI-раздел (повтор, отмена, докачка). «Позже» ничего не
 * запускает и больше не спрашивает: пользователь сам зайдёт в настройки.
 */
@Composable
fun LocalModelConsentDialog(
    onDownloadAny: () -> Unit,
    onDownloadWifi: () -> Unit,
    onLater: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.omnix_local_model_consent_title)) },
        text = { Text(stringResource(R.string.omnix_local_model_consent_body)) },
        confirmButton = {
            Row {
                TextButton(onClick = onDownloadWifi) {
                    Text(stringResource(R.string.omnix_local_model_download_wifi))
                }
                TextButton(onClick = onDownloadAny) {
                    Text(stringResource(R.string.omnix_local_model_download_now))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text(stringResource(R.string.omnix_local_model_later))
            }
        }
    )
}
