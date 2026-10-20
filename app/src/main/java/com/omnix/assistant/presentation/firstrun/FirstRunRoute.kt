package com.omnix.assistant.presentation.firstrun

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.omnix.assistant.presentation.activation.ActivationScreen
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.state.OmnixViewModel
import com.omnix.assistant.presentation.state.SystemStateType
import kotlinx.coroutines.delay

/**
 * Drives the first-run sequence with real state (§34, §67).
 *
 * The steps move on genuine events — a permission actually granted, the
 * assistant actually running, a request actually answered — never by a
 * timer. Completion is persisted by the caller, so the flow is shown once.
 */
@Composable
fun FirstRunRoute(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OmnixViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    var step by remember { mutableStateOf(FirstRunStep.Welcome) }

    // "I have an activation code" opens the real code entry: onboarding runs
    // before the licence gate, so the code from the box activates right here.
    var showActivation by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Re-read the real permission state rather than trusting the map.
        viewModel.refreshPermissions()
    }

    val microphoneGranted = state.systemState != SystemStateType.MICROPHONE_DENIED
    val microphonePrompted by viewModel.microphonePrompted.collectAsState()

    // The user may grant the microphone in the system settings: re-read on
    // every return to the foreground so "denied" turns into "granted".
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refreshPermissions()
        }
    }

    // A grant made on the Access step moves on by itself, after a beat so
    // the "granted" state is seen; arriving already granted does not bounce.
    var grantedOnEntry by remember { mutableStateOf(false) }
    LaunchedEffect(step) {
        if (step == FirstRunStep.Access) grantedOnEntry = microphoneGranted
    }
    LaunchedEffect(step, microphoneGranted) {
        if (step == FirstRunStep.Access && microphoneShouldAutoAdvance(microphoneGranted, grantedOnEntry)) {
            delay(ACCESS_ADVANCE_DELAY_MS)
            step = FirstRunStep.TurnOn
        }
    }

    LaunchedEffect(step) {
        if (step == FirstRunStep.Complete) onFinished()
    }

    BackHandler(enabled = !showActivation && step.previous() != null) {
        step.previous()?.let { step = it }
    }

    Crossfade(
        targetState = showActivation,
        animationSpec = tween(OmnixTheme.motion.screenEnterMs),
        label = "first-run-activation"
    ) { showingActivation ->
        if (showingActivation) {
            BackHandler { showActivation = false }
            ActivationScreen(
                onActivationSuccess = {
                    showActivation = false
                    step = FirstRunStep.Access
                }
            )
        } else {
            FirstRunScreen(
                step = step,
                state = state,
                microphoneGranted = microphoneGranted,
                microphonePrompted = microphonePrompted,
                modifier = modifier,
                onAdvance = { step = step.next() },
                onRequestMicrophone = {
                    viewModel.markMicrophonePrompted()
                    val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissions += Manifest.permission.POST_NOTIFICATIONS
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        permissions += Manifest.permission.BLUETOOTH_CONNECT
                    }
                    permissionLauncher.launch(permissions.toTypedArray())
                },
                onOpenSystemSettings = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
                onEnterActivationCode = { showActivation = true },
                onLogoTap = viewModel::onLogoTap,
                onOpenBluetooth = {
                    context.startActivity(
                        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            )
        }
    }
}

/** Long enough to read "Microphone is on" before the next step slides in. */
private const val ACCESS_ADVANCE_DELAY_MS = 1_200L
