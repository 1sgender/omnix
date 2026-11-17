package com.omnix.assistant.presentation.localmodel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.omnix.assistant.R

/**
 * Вопрос при запуске: скачать офлайн-режим (локальный ИИ, голос, распознавание).
 *
 * Решение владельца 2026-11-12: спрашиваем при каждом входе, пока пакет не на
 * телефоне. «Позже» закрывает вопрос только до следующего запуска.
 *
 * Три кнопки стоят одной колонкой по правому краю: в узком диалоге их
 * длинные подписи иначе разъезжаются по разным рядам без выравнивания.
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = onDownloadWifi) {
                    Text(stringResource(R.string.omnix_local_model_download_wifi))
                }
                TextButton(onClick = onDownloadAny) {
                    Text(stringResource(R.string.omnix_local_model_download_now))
                }
                TextButton(onClick = onLater) {
                    Text(stringResource(R.string.omnix_local_model_later))
                }
            }
        }
    )
}
