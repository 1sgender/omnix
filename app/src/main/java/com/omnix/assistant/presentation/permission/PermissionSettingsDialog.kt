package com.omnix.assistant.presentation.permission

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.omnix.assistant.R

/**
 * Пользователь запретил доступ насовсем: системный диалог больше не появится.
 * Объясняем, зачем доступ, и ведём в настройки приложения.
 */
@Composable
fun PermissionSettingsDialog(
    accessLabel: String,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.omnix_perm_settings_title)) },
        text = { Text(stringResource(R.string.omnix_perm_settings_body, accessLabel)) },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.omnix_perm_settings_open))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.omnix_perm_settings_later))
            }
        }
    )
}
