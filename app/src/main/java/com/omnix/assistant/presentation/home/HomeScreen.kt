package com.omnix.assistant.presentation.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.OmnixMicLineIcon
import com.omnix.assistant.presentation.components.OmnixOnDeviceLineIcon
import com.omnix.assistant.presentation.components.SystemStateView
import com.omnix.assistant.presentation.components.clipDotColor
import com.omnix.assistant.presentation.components.clipLabel
import com.omnix.assistant.presentation.components.omnixPressScale
import com.omnix.assistant.presentation.design.OmnixMonoLabelStyle
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.firstrun.OmnixRingMark
import com.omnix.assistant.presentation.state.ActionPhrase
import com.omnix.assistant.presentation.state.AssistantPower
import com.omnix.assistant.presentation.state.AssistantPowerError
import com.omnix.assistant.presentation.state.ClipCapability
import com.omnix.assistant.presentation.state.ClipState
import com.omnix.assistant.presentation.state.OmnixPhase
import com.omnix.assistant.presentation.state.OmnixUiState

/**
 * Home — the OMNIX logo is the assistant's power switch.
 *
 * OFF: the logo, one line saying OMNIX is off and how to turn it on.
 * Nothing else competes with it.
 *
 * ON: the logo stays the centre, now lit; around it only what talking to
 * the assistant needs — the headphones it hears through, what it is doing,
 * a "Speak" button for a request without the wake word, and the offline
 * note when the phone has no network.
 *
 * The switch shows the real state of the voice service (from the
 * orchestrator), not the state of a button: while Android starts or stops
 * the service the logo says so, and a failed start is explained with the
 * action that fixes it.
 */
@Composable
fun HomeScreen(
    state: OmnixUiState,
    modifier: Modifier = Modifier,
    onLogoTap: () -> Unit = {},
    onTalkTap: () -> Unit = {},
    onClipTap: () -> Unit = {},
    onPowerErrorAction: (AssistantPowerError) -> Unit = {},
    onPowerErrorDismiss: () -> Unit = {},
    onSystemStateAction: () -> Unit = {},
    microphoneSettingsNeeded: Boolean = false
) {
    val on = state.assistantPower == AssistantPower.ON

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // The logo gives up size on short screens before anything else.
        val logoSize = (maxHeight * 0.34f).coerceIn(LOGO_MIN, LOGO_MAX)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = SCREEN_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ON-only top row: the headphones the assistant hears through.
            AnimatedVisibility(
                visible = on,
                enter = fadeIn(tween(OmnixTheme.motion.contentFadeMs)),
                exit = fadeOut(tween(OmnixTheme.motion.contentFadeMs))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = SCREEN_PADDING),
                    horizontalArrangement = Arrangement.End
                ) {
                    ClipPill(clip = state.clip, onClick = onClipTap)
                }
            }

            Spacer(Modifier.weight(1f))

            PowerLogo(state = state, size = logoSize, onTap = onLogoTap)

            Spacer(Modifier.height(32.dp))

            StatusText(state = state)

            state.assistantPowerError?.let { error ->
                Spacer(Modifier.height(20.dp))
                PowerErrorCard(
                    error = error,
                    microphoneSettingsNeeded = microphoneSettingsNeeded,
                    onAction = { onPowerErrorAction(error) },
                    onDismiss = onPowerErrorDismiss
                )
            }

            AnimatedVisibility(
                visible = on,
                enter = fadeIn(tween(OmnixTheme.motion.contentFadeMs)) + expandVertically(),
                exit = fadeOut(tween(OmnixTheme.motion.contentFadeMs)) + shrinkVertically()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(24.dp))
                    TalkButton(listening = state.phase.isHearing, onClick = onTalkTap)
                    if (!state.isOnline) {
                        Spacer(Modifier.height(16.dp))
                        OnDevicePill()
                    }
                    state.systemState?.let { systemState ->
                        Spacer(Modifier.height(16.dp))
                        SystemStateView(type = systemState, onAction = onSystemStateAction)
                    }
                }
            }

            Spacer(Modifier.weight(1.2f))
        }
    }
}

private val OmnixPhase.isHearing: Boolean
    get() = this is OmnixPhase.Listening || this is OmnixPhase.Recognizing

/**
 * The logo as a switch: a circle with the OMNIX mark. Off — a quiet outline
 * and paper mark. On — a lime ring and mark with a slow breath; while OMNIX
 * hears a request the ring follows the real microphone level. Starting and
 * stopping turn a lime arc around the ring until the service answers.
 */
@Composable
internal fun PowerLogo(state: OmnixUiState, size: Dp, onTap: () -> Unit) {
    val colors = OmnixTheme.colors
    val motion = OmnixTheme.motion
    val reduced = OmnixTheme.reducedMotion
    val power = state.assistantPower
    val lit = power == AssistantPower.ON || power == AssistantPower.STARTING

    val ringColor by animateColorAsState(
        targetValue = if (lit) colors.accentBrand else colors.textPrimary.copy(alpha = 0.10f),
        animationSpec = tween(motion.stateTransitionMs),
        label = "logo_ring"
    )
    val markColor by animateColorAsState(
        targetValue = if (lit) colors.accentBrand else colors.textPrimary,
        animationSpec = tween(motion.stateTransitionMs),
        label = "logo_mark"
    )
    val ringWidth by animateFloatAsState(
        targetValue = if (power == AssistantPower.ON) 2.5f else 1.5f,
        animationSpec = tween(motion.stateTransitionMs),
        label = "logo_ring_width"
    )

    // Breath while ON and waiting; the real microphone level while hearing.
    val transition = rememberInfiniteTransition(label = "logo")
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BREATH_MS, easing = LinearEasing), RepeatMode.Reverse),
        label = "logo_breath"
    )
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(SWEEP_MS, easing = LinearEasing)),
        label = "logo_sweep"
    )
    val busy = power == AssistantPower.STARTING || power == AssistantPower.STOPPING
    val pulse = when {
        reduced || power != AssistantPower.ON -> 1f
        state.phase.isHearing -> 1f + 0.04f * state.audioLevel.coerceIn(0f, 1f)
        else -> 1f + 0.015f * breath
    }

    val onLabel = stringResource(R.string.omnix_home_power_on_state)
    val offLabel = stringResource(R.string.omnix_home_power_off_state)
    val switchLabel = stringResource(R.string.omnix_home_power_a11y)
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(size)
            .scale(pulse)
            .omnixPressScale(interaction)
            .clip(CircleShape)
            .background(lerp(colors.background, colors.surface, 0.5f))
            .clickable(interactionSource = interaction, indication = null, enabled = !busy, onClick = onTap)
            .clearAndSetSemantics {
                contentDescription = switchLabel
                stateDescription = if (power == AssistantPower.ON) onLabel else offLabel
                role = Role.Switch
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = ringWidth.dp.toPx()
            val inset = stroke / 2f
            drawCircle(
                color = ringColor,
                radius = this.size.minDimension / 2f - inset,
                style = Stroke(width = stroke)
            )
            if (busy && !reduced) {
                drawArc(
                    color = colors.accentBrand,
                    startAngle = sweep,
                    sweepAngle = 70f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(this.size.width - stroke, this.size.height - stroke),
                    style = Stroke(width = stroke * 1.6f, cap = StrokeCap.Round)
                )
            }
        }
        Box(
            modifier = Modifier
                .size(size * INNER_RATIO)
                .border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.06f)), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            OmnixRingMark(
                color = markColor,
                width = size * MARK_RATIO,
                height = size * MARK_RATIO * 80f / 104f,
                strokeUnits = 2.6f
            )
        }
    }
}

/** Title and hint under the logo; they cross-fade as the state changes. */
@Composable
private fun StatusText(state: OmnixUiState) {
    val colors = OmnixTheme.colors
    val title: String
    val hint: String?
    when (state.assistantPower) {
        AssistantPower.OFF -> {
            title = stringResource(R.string.omnix_home_off_title)
            hint = stringResource(R.string.omnix_home_off_hint)
        }
        AssistantPower.STARTING -> {
            title = stringResource(R.string.omnix_home_starting)
            hint = null
        }
        AssistantPower.STOPPING -> {
            title = stringResource(R.string.omnix_home_stopping)
            hint = null
        }
        AssistantPower.ON -> {
            title = stateLabel(state)
            hint = when (state.phase) {
                is OmnixPhase.Listening, is OmnixPhase.Recognizing -> stringResource(R.string.omnix_home_hint_listening)
                is OmnixPhase.Idle, is OmnixPhase.Error -> stringResource(R.string.omnix_home_on_hint)
                else -> null
            }
        }
    }

    AnimatedContent(
        targetState = title to hint,
        transitionSpec = {
            fadeIn(tween(OmnixTheme.motion.contentFadeMs)) togetherWith fadeOut(tween(OmnixTheme.motion.contentFadeMs))
        },
        label = "home_status"
    ) { (shownTitle, shownHint) ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = shownTitle,
                style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 32.sp),
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
            if (shownHint != null) {
                Text(
                    text = shownHint,
                    style = TextStyle(fontFamily = OmnixSans, fontSize = 15.sp, lineHeight = 22.sp),
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 280.dp)
                )
            }
        }
    }
}

/** "Speak": a request without the wake word; "Stop" while OMNIX is hearing one. */
@Composable
private fun TalkButton(listening: Boolean, onClick: () -> Unit) {
    val colors = OmnixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .heightIn(min = 52.dp)
            .omnixPressScale(interaction)
            .clip(RoundedCornerShape(26.dp))
            .background(if (listening) colors.textPrimary else colors.surface)
            .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(26.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = OmnixMicLineIcon,
            contentDescription = null,
            tint = if (listening) colors.background else colors.accentBrand,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = stringResource(if (listening) R.string.omnix_home_talk_stop else R.string.omnix_home_talk),
            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 15.sp),
            color = if (listening) colors.background else colors.textPrimary
        )
    }
}

/** Why OMNIX did not turn on, with the one action that fixes it. */
@Composable
private fun PowerErrorCard(
    error: AssistantPowerError,
    microphoneSettingsNeeded: Boolean,
    onAction: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = OmnixTheme.colors
    val message = stringResource(
        when (error) {
            AssistantPowerError.MICROPHONE_PERMISSION -> R.string.omnix_home_error_mic
            AssistantPowerError.START_REJECTED -> R.string.omnix_home_error_rejected
            AssistantPowerError.START_TIMEOUT -> R.string.omnix_home_error_timeout
        }
    )
    val action = stringResource(
        when {
            error != AssistantPowerError.MICROPHONE_PERMISSION -> R.string.omnix_home_error_retry
            microphoneSettingsNeeded -> R.string.omnix_home_error_mic_settings
            else -> R.string.omnix_home_error_mic_action
        }
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = message,
            style = TextStyle(fontFamily = OmnixSans, fontSize = 15.sp, lineHeight = 22.sp),
            color = colors.textPrimary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(text = action, filled = true, onClick = onAction)
            PillButton(text = stringResource(R.string.omnix_home_error_dismiss), filled = false, onClick = onDismiss)
        }
    }
}

@Composable
private fun PillButton(text: String, filled: Boolean, onClick: () -> Unit) {
    val colors = OmnixTheme.colors
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (filled) colors.accentBrand else colors.background)
            .border(BorderStroke(1.dp, if (filled) colors.accentBrand else colors.textPrimary.copy(alpha = 0.12f)), RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 14.sp),
            color = if (filled) colors.background else colors.textPrimary
        )
    }
}

/**
 * The headphones pill. The headset reports one battery value, so the pill
 * shows that one value — never an invented second number.
 */
@Composable
internal fun ClipPill(clip: ClipState, onClick: () -> Unit) {
    val colors = OmnixTheme.colors
    val battery = when (clip) {
        is ClipState.Connected -> (clip.battery as? ClipCapability.Available)?.value
        is ClipState.BatteryLow -> clip.percent
        else -> null
    }
    val text = battery?.let { stringResource(R.string.omnix_home_clip_battery, it) } ?: clipLabel(clip)
    val description = battery?.let { stringResource(R.string.omnix_home_clip_a11y_battery, it) }
        ?: clipLabel(clip)
    val dot = if (clip is ClipState.Connected) colors.accentBrand else clipDotColor(clip)

    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(PillShape)
            .background(colors.surface)
            .border(BorderStroke(1.dp, colors.border), PillShape)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(dot, CircleShape)
        )
        Text(text = text, style = OmnixMonoLabelStyle, color = colors.textPrimary, maxLines = 1)
    }
}

@Composable
private fun OnDevicePill() {
    val colors = OmnixTheme.colors
    Row(
        modifier = Modifier
            .height(28.dp)
            .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = OmnixOnDeviceLineIcon,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = stringResource(R.string.omnix_home_offline_pill),
            style = OmnixMonoLabelStyle.copy(fontSize = 11.sp, letterSpacing = 0.04.em),
            color = colors.textSecondary
        )
    }
}

/** Resolves the current phase into human copy. */
@Composable
fun stateLabel(state: OmnixUiState): String = when (val phase = state.phase) {
    is OmnixPhase.Idle -> stringResource(R.string.omnix_state_ready)
    is OmnixPhase.Listening -> stringResource(R.string.omnix_state_listening)
    // While recognising, the partial transcript is the feedback: showing the
    // user's own words is more reassuring than the word "Recognizing" (§17).
    is OmnixPhase.Recognizing -> phase.partialTranscript.ifBlank {
        stringResource(R.string.omnix_state_listening)
    }
    is OmnixPhase.Thinking -> stringResource(R.string.omnix_state_thinking)
    is OmnixPhase.Executing -> ActionPhrase.of(phase.action)
    is OmnixPhase.Speaking -> phase.text.ifBlank {
        stringResource(R.string.omnix_state_speaking)
    }
    is OmnixPhase.Success -> phase.message.ifBlank {
        stringResource(R.string.omnix_result_done)
    }
    is OmnixPhase.Error -> stringResource(R.string.omnix_state_ready)
}

private val SCREEN_PADDING = 20.dp
private val LOGO_MIN = 180.dp
private val LOGO_MAX = 264.dp
private const val INNER_RATIO = 188f / 248f
private const val MARK_RATIO = 104f / 248f
private const val BREATH_MS = 2_400
private const val SWEEP_MS = 1_100
private val PillShape = RoundedCornerShape(18.dp)
