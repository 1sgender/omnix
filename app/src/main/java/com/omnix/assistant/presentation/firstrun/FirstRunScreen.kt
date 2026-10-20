package com.omnix.assistant.presentation.firstrun

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.OmnixMicLineIcon
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.home.ClipPill
import com.omnix.assistant.presentation.home.PowerLogo
import com.omnix.assistant.presentation.state.AssistantPower
import com.omnix.assistant.presentation.state.AssistantPowerError
import com.omnix.assistant.presentation.state.ClipState
import com.omnix.assistant.presentation.state.OmnixPhase
import com.omnix.assistant.presentation.state.OmnixUiState

/**
 * First run — three steps in the Home language: one large visual in the
 * middle, one title, one short explanation, one main action at the bottom
 * within thumb reach. Progress dots at the top say where the user is.
 *
 *  - **Welcome** — the mark, what OMNIX is, "Start"; "I have an activation
 *    code" opens the real code entry without leaving the flow.
 *  - **Access** — the microphone, asked for in plain words (notifications
 *    and Bluetooth ride along in the same system prompt). Three honest
 *    states: never asked, denied (then the system page), granted (the step
 *    moves on by itself).
 *  - **Turn on** — the same logo switch as on Home, so the first thing the
 *    user learns is how OMNIX is really turned on; then one spoken request.
 *    The headphones are shown as they are, with the Bluetooth page a tap
 *    away.
 */
@Composable
fun FirstRunScreen(
    step: FirstRunStep,
    state: OmnixUiState,
    microphoneGranted: Boolean,
    microphonePrompted: Boolean,
    modifier: Modifier = Modifier,
    onAdvance: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onEnterActivationCode: () -> Unit,
    onLogoTap: () -> Unit,
    onOpenBluetooth: () -> Unit
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        val visualSize = (maxHeight * 0.30f).coerceIn(160.dp, 240.dp)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            step.progressIndex?.let { OnboardingProgressDots(total = step.progressTotal, active = it) }

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    fadeIn(tween(OmnixTheme.motion.screenEnterMs)) togetherWith fadeOut(tween(OmnixTheme.motion.screenExitMs))
                },
                modifier = Modifier.weight(1f),
                label = "first_run_step"
            ) { shown ->
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (shown) {
                        FirstRunStep.Welcome -> WelcomeStep(visualSize, onAdvance, onEnterActivationCode)
                        FirstRunStep.Access -> AccessStep(
                            visualSize = visualSize,
                            state = microphoneVisualState(microphoneGranted, microphonePrompted),
                            onAllow = onRequestMicrophone,
                            onOpenSettings = onOpenSystemSettings,
                            onNext = onAdvance
                        )
                        FirstRunStep.TurnOn -> TurnOnStep(
                            visualSize = visualSize,
                            state = state,
                            onLogoTap = onLogoTap,
                            onOpenBluetooth = onOpenBluetooth,
                            onFinish = onAdvance
                        )
                        FirstRunStep.Complete -> Unit
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Welcome

@Composable
private fun WelcomeStep(visualSize: Dp, onStart: () -> Unit, onEnterCode: () -> Unit) {
    val colors = OmnixTheme.colors
    StepBody(
        visual = {
            Box(
                modifier = Modifier
                    .size(visualSize)
                    .clip(CircleShape)
                    .background(lerp(colors.background, colors.surface, 0.5f))
                    .border(BorderStroke(1.5.dp, colors.accentBrand), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                OmnixRingMark(
                    color = colors.accentBrand,
                    width = visualSize * 0.42f,
                    height = visualSize * 0.42f * 80f / 104f,
                    strokeUnits = 2.6f
                )
            }
        },
        title = stringResource(R.string.omnix_setup_welcome_title),
        body = stringResource(R.string.omnix_setup_welcome_body),
        actions = {
            SetupButton(text = stringResource(R.string.omnix_setup_start), filled = true, onClick = onStart)
            SetupLink(text = stringResource(R.string.omnix_setup_have_code), onClick = onEnterCode)
        }
    )
}

// ----------------------------------------------------------------- Access

@Composable
private fun AccessStep(
    visualSize: Dp,
    state: MicrophoneVisualState,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    onNext: () -> Unit
) {
    val colors = OmnixTheme.colors
    val tint by animateColorAsState(
        targetValue = when (state) {
            MicrophoneVisualState.Granted -> colors.accentBrand
            MicrophoneVisualState.Denied -> colors.stateWarning
            MicrophoneVisualState.Request -> colors.textPrimary
        },
        animationSpec = tween(OmnixTheme.motion.stateTransitionMs),
        label = "access_tint"
    )
    StepBody(
        visual = {
            Box(
                modifier = Modifier
                    .size(visualSize)
                    .clip(CircleShape)
                    .background(lerp(colors.background, colors.surface, 0.5f))
                    .border(BorderStroke(1.5.dp, tint.copy(alpha = if (state == MicrophoneVisualState.Request) 0.10f else 1f)), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(OmnixMicLineIcon, contentDescription = null, tint = tint, modifier = Modifier.size(visualSize * 0.26f))
            }
        },
        title = stringResource(
            if (state == MicrophoneVisualState.Granted) R.string.omnix_setup_access_granted else R.string.omnix_setup_access_title
        ),
        body = stringResource(
            if (state == MicrophoneVisualState.Denied) R.string.omnix_setup_access_denied else R.string.omnix_setup_access_body
        ),
        actions = {
            when (state) {
                MicrophoneVisualState.Request -> {
                    SetupButton(text = stringResource(R.string.omnix_setup_allow), filled = true, onClick = onAllow)
                    SetupLink(text = stringResource(R.string.omnix_setup_later), onClick = onNext)
                }
                MicrophoneVisualState.Denied -> {
                    SetupButton(text = stringResource(R.string.omnix_setup_open_settings), filled = true, onClick = onOpenSettings)
                    SetupLink(text = stringResource(R.string.omnix_setup_later), onClick = onNext)
                }
                MicrophoneVisualState.Granted ->
                    SetupButton(text = stringResource(R.string.omnix_setup_next), filled = true, onClick = onNext)
            }
        }
    )
}

// ---------------------------------------------------------------- Turn on

@Composable
private fun TurnOnStep(
    visualSize: Dp,
    state: OmnixUiState,
    onLogoTap: () -> Unit,
    onOpenBluetooth: () -> Unit,
    onFinish: () -> Unit
) {
    val succeeded = state.lastInteraction != null
    val title = when {
        succeeded -> stringResource(R.string.omnix_setup_success)
        state.assistantPower == AssistantPower.STARTING -> stringResource(R.string.omnix_home_starting)
        state.assistantPower == AssistantPower.ON && (state.phase is OmnixPhase.Listening || state.phase is OmnixPhase.Recognizing) ->
            stringResource(R.string.omnix_state_listening)
        state.assistantPower == AssistantPower.ON -> stringResource(R.string.omnix_setup_on_title)
        else -> stringResource(R.string.omnix_setup_turn_on_title)
    }
    val body = when {
        succeeded -> stringResource(R.string.omnix_setup_success_body)
        state.assistantPowerError != null -> stringResource(errorText(state.assistantPowerError))
        state.assistantPower == AssistantPower.ON -> stringResource(R.string.omnix_setup_try_hint)
        else -> stringResource(R.string.omnix_setup_turn_on_hint)
    }
    StepBody(
        visual = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PowerLogo(state = state, size = visualSize, onTap = onLogoTap)
                Spacer(Modifier.height(16.dp))
                HeadphonesLine(clip = state.clip, onOpenBluetooth = onOpenBluetooth)
            }
        },
        title = title,
        body = body,
        actions = {
            if (succeeded || state.assistantPower == AssistantPower.ON) {
                SetupButton(text = stringResource(R.string.omnix_setup_done), filled = true, onClick = onFinish)
            } else {
                SetupLink(text = stringResource(R.string.omnix_setup_skip), onClick = onFinish)
            }
        }
    )
}

/** The headphones as they are; not connected — a tap opens Bluetooth. */
@Composable
private fun HeadphonesLine(clip: ClipState, onOpenBluetooth: () -> Unit) {
    val connected = clip is ClipState.Connected || clip is ClipState.BatteryLow
    if (connected) {
        ClipPill(clip = clip, onClick = {})
    } else {
        SetupLink(text = stringResource(R.string.omnix_setup_connect_headphones), onClick = onOpenBluetooth)
    }
}

private fun errorText(error: AssistantPowerError): Int = when (error) {
    AssistantPowerError.MICROPHONE_PERMISSION -> R.string.omnix_home_error_mic
    AssistantPowerError.START_REJECTED -> R.string.omnix_home_error_rejected
    AssistantPowerError.START_TIMEOUT -> R.string.omnix_home_error_timeout
}

// ----------------------------------------------------------------- shared

/** Visual in the middle, title and body under it, actions at the bottom. */
@Composable
private fun StepBody(
    visual: @Composable () -> Unit,
    title: String,
    body: String,
    actions: @Composable () -> Unit
) {
    val colors = OmnixTheme.colors
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        visual()
        Spacer(Modifier.height(32.dp))
        Text(
            text = title,
            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 32.sp),
            color = colors.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = body,
            style = TextStyle(fontFamily = OmnixSans, fontSize = 15.sp, lineHeight = 22.sp),
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp)
        )
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            actions()
        }
    }
}

@Composable
private fun SetupButton(text: String, filled: Boolean, onClick: () -> Unit) {
    val colors = OmnixTheme.colors
    Box(
        modifier = Modifier
            .widthIn(max = 400.dp)
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (filled) colors.accentBrand else colors.background)
            .border(
                BorderStroke(1.dp, if (filled) colors.accentBrand else colors.textPrimary.copy(alpha = 0.12f)),
                RoundedCornerShape(16.dp)
            )
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 16.sp),
            color = if (filled) colors.background else colors.textPrimary
        )
    }
}

@Composable
private fun SetupLink(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 15.sp),
            color = OmnixTheme.colors.textSecondary
        )
    }
}

/**
 * The three visual states of the microphone step.
 *
 * Request and Denied both mean "the permission is not granted"; the
 * difference is whether the system prompt was actually shown. Only after a
 * real denial do we point at Settings — a settings link before the first
 * prompt is confusing, because there is nothing to open yet.
 */
internal enum class MicrophoneVisualState {
    Request,
    Denied,
    Granted
}

/**
 * Derives the step's visual state from two real signals: the permission
 * itself and whether the system prompt has already been shown. "Denied"
 * cannot exist before a prompt.
 */
internal fun microphoneVisualState(granted: Boolean, prompted: Boolean): MicrophoneVisualState =
    when {
        granted -> MicrophoneVisualState.Granted
        prompted -> MicrophoneVisualState.Denied
        else -> MicrophoneVisualState.Request
    }

/**
 * Auto-advance is only for a grant that happened on this screen. If the
 * user arrives at the step already granted (back gesture from the next
 * step), the step shows its way forward instead of bouncing them ahead.
 */
internal fun microphoneShouldAutoAdvance(granted: Boolean, grantedOnEntry: Boolean): Boolean =
    granted && !grantedOnEntry
