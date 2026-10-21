package com.omnix.assistant.presentation.localmodel

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.omnix.assistant.agent.localai.LocalModelSelection
import com.omnix.assistant.agent.localai.LocalModelSpec

/** Модель на телефоне для этого устройства — та же, что качает приложение. */
@Composable
fun rememberLocalModelSpec(): LocalModelSpec {
    val context = LocalContext.current.applicationContext
    return remember(context) { LocalModelSelection.forDevice(context) }
}

/** Размер файла модели в единицах системы: «1,6 ГБ», «547 МБ». */
@Composable
fun localModelSizeLabel(spec: LocalModelSpec): String {
    val context = LocalContext.current
    return remember(spec) { Formatter.formatShortFileSize(context, spec.expectedSizeBytes) }
}
