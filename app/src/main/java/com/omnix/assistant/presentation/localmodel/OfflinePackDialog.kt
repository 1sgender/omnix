package com.omnix.assistant.presentation.localmodel

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.OmnixPrimaryButton
import com.omnix.assistant.presentation.components.OmnixSecondaryButton
import com.omnix.assistant.presentation.components.OmnixSheet
import com.omnix.assistant.presentation.components.OmnixTextButton

/**
 * Вопрос при запуске: скачать офлайн-режим (локальный ИИ, голос, распознавание).
 *
 * Решение владельца 2026-11-12: спрашиваем при каждом входе, пока пакет не на
 * телефоне. «Позже» закрывает вопрос только до следующего запуска.
 *
 * Нижняя шторка OMNIX: главное действие — пилюля во всю ширину, второе —
 * контурная пилюля, «Позже» — лёгкая текстовая кнопка.
 */
@Composable
fun OfflinePackDialog(
    totalSizeMb: Int,
    onDownloadAny: () -> Unit,
    onDownloadWifi: () -> Unit,
    onLater: () -> Unit
) {
    OmnixSheet(
        onDismiss = onLater,
        title = stringResource(R.string.omnix_offline_prompt_title),
        message = stringResource(R.string.omnix_offline_prompt_body, totalSizeMb)
    ) {
        OmnixPrimaryButton(
            text = stringResource(R.string.omnix_local_model_download_wifi),
            onClick = onDownloadWifi,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OmnixSecondaryButton(
            text = stringResource(R.string.omnix_local_model_download_now),
            onClick = onDownloadAny,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        OmnixTextButton(
            text = stringResource(R.string.omnix_local_model_later),
            onClick = onLater,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
