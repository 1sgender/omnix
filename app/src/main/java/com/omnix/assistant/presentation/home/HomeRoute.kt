package com.omnix.assistant.presentation.home

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnix.assistant.presentation.navigation.OmnixDestination
import com.omnix.assistant.presentation.state.AssistantPowerError
import com.omnix.assistant.presentation.state.OmnixUiState
import com.omnix.assistant.presentation.state.OmnixViewModel

/**
 * Wires [HomeScreen] to the assistant: the logo turns the voice service on
 * and off through [OmnixViewModel.onLogoTap], and a missing microphone grant
 * is asked for right here — after the answer the switch is tried again, so
 * one tap on "Allow" is enough to turn OMNIX on.
 */
@Composable
fun HomeRoute(
    state: OmnixUiState,
    viewModel: OmnixViewModel,
    onClipTap: () -> Unit,
    onOpen: (OmnixDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val microphonePrompted by viewModel.microphonePrompted.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.refreshPermissions()
        if (results[Manifest.permission.RECORD_AUDIO] == true) viewModel.onLogoTap()
    }

    // After a denial Android stops showing the prompt; only the app's
    // system page can grant the microphone then.
    val settingsNeeded = microphonePrompted &&
        (context as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false

    HomeScreen(
        state = state,
        modifier = modifier,
        onLogoTap = viewModel::onLogoTap,
        onTalkTap = viewModel::onTalkTap,
        onClipTap = onClipTap,
        onTranslatorTap = { onOpen(OmnixDestination.Translator) },
        onMemoryTap = { onOpen(OmnixDestination.Privacy) },
        onRemindersTap = { onOpen(OmnixDestination.Chat) },
        onAutomationsTap = { onOpen(OmnixDestination.Automations) },
        microphoneSettingsNeeded = settingsNeeded,
        onPowerErrorAction = { error ->
            when {
                error != AssistantPowerError.MICROPHONE_PERMISSION -> viewModel.onLogoTap()
                settingsNeeded -> context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                else -> {
                    viewModel.markMicrophonePrompted()
                    // The notification is how the running assistant is seen
                    // and stopped from outside the app (Android 13+ asks).
                    val permissions = buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    permissionLauncher.launch(permissions.toTypedArray())
                }
            }
        },
        onPowerErrorDismiss = viewModel::dismissPowerError,
        onSystemStateAction = viewModel::refreshPermissions
    )
}
