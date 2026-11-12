package com.omnix.assistant.presentation.localmodel

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.omnix.assistant.R

/**
 * Вопрос при запуске: скачать офлайн-режим (локальный ИИ, голос, распознавание).
 *
 * Решение владельца 2026-11-12: спрашиваем при каждом входе, пока пакет не на
 * телефоне. «Позже» закрывает вопрос только до следующего запуска.
 */
@Composable
fun OfflinePackDialog(
    totalSizeMb: Int,
    onDownloadAny: () -> Unit,
    onDownloadWifi: () -> Unit,
    onLater: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.omnix_offline_prompt_title)) },
        text = { Text(stringResource(R.string.omnix_offline_prompt_body, totalSizeMb)) },
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
