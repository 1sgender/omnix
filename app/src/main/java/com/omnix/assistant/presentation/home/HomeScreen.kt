package com.omnix.assistant.presentation.home

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
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
import com.omnix.assistant.presentation.components.OmnixAlarmLineIcon
import com.omnix.assistant.presentation.components.OmnixBoltLineIcon
import com.omnix.assistant.presentation.components.OmnixMicLineIcon
import com.omnix.assistant.presentation.components.OmnixTranslatorLineIcon
import com.omnix.assistant.presentation.components.OmnixOnDeviceLineIcon
import com.omnix.assistant.presentation.components.SystemStateView
import com.omnix.assistant.presentation.components.clipDotColor
import com.omnix.assistant.presentation.components.clipLabel
import com.omnix.assistant.presentation.components.omnixPressScale
import com.omnix.assistant.presentation.design.OmnixMonoLabelStyle
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.state.ActionPhrase
import com.omnix.assistant.presentation.state.AssistantPower
import com.omnix.assistant.presentation.state.AssistantPowerError
import com.omnix.assistant.presentation.state.ClipCapability
import com.omnix.assistant.presentation.state.ClipState
import com.omnix.assistant.presentation.state.OmnixPhase
import com.omnix.assistant.presentation.state.OmnixUiState
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
    onTranslatorTap: () -> Unit = {},
    onRemindersTap: () -> Unit = {},
    onAutomationsTap: () -> Unit = {},
    onSystemStateAction: () -> Unit = {},
    microphoneSettingsNeeded: Boolean = false
) {
    val on = state.assistantPower == AssistantPower.ON
    // Карточки видны, пока OMNIX выключен, и уходят с первого нажатия на
    // логотип: при работающем ассистенте экран — только для разговора.
    val showCards = state.assistantPower == AssistantPower.OFF || state.assistantPower == AssistantPower.STOPPING

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // The logo gives up size on short screens before anything else.
        val logoSize = (maxHeight * 0.36f).coerceIn(LOGO_MIN, LOGO_MAX)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = SCREEN_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HomeHeader(on = on, clip = state.clip, onClipTap = onClipTap)

            Spacer(Modifier.weight(1f))

            PowerLogo(state = state, size = logoSize, onTap = onLogoTap)

            Spacer(Modifier.height(12.dp))

            StatusText(state = state)

            AnimatedVisibility(
                visible = on && state.phase is OmnixPhase.Idle,
                enter = fadeIn(tween(OmnixTheme.motion.contentFadeMs)),
                exit = fadeOut(tween(OmnixTheme.motion.contentFadeMs))
            ) {
                ExamplePhrases()
            }

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

            AnimatedVisibility(
                visible = showCards,
                enter = fadeIn(tween(OmnixTheme.motion.contentFadeMs)) + expandVertically(),
                exit = fadeOut(tween(OmnixTheme.motion.contentFadeMs)) + shrinkVertically()
            ) {
                Column {
                    QuickActions(
                        onTranslatorTap = onTranslatorTap,
                        onRemindersTap = onRemindersTap,
                        onAutomationsTap = onAutomationsTap
                    )
                    Spacer(Modifier.height(SCREEN_PADDING))
                }
            }
        }
    }
}

/** Date in mono and a greeting by the hour; the headphones pill while OMNIX is on. */
@Composable
private fun HomeHeader(on: Boolean, clip: ClipState, onClipTap: () -> Unit) {
    val colors = OmnixTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val now = remember { LocalDateTime.now() }
    val date = remember(locale) {
        now.format(DateTimeFormatter.ofPattern("EEE · d MMMM", locale)).uppercase(locale)
    }
    val greeting = stringResource(
        when (now.hour) {
            in 5..11 -> R.string.omnix_home_greeting_morning
            in 12..17 -> R.string.omnix_home_greeting_day
            in 18..22 -> R.string.omnix_home_greeting_evening
            else -> R.string.omnix_home_greeting_night
        }
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = SCREEN_PADDING),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = date, style = OmnixMonoLabelStyle, color = colors.textTertiary, maxLines = 1)
            Text(
                text = greeting,
                style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
                color = colors.textPrimary,
                maxLines = 1
            )
        }
        AnimatedVisibility(
            visible = on,
            enter = fadeIn(tween(OmnixTheme.motion.contentFadeMs)),
            exit = fadeOut(tween(OmnixTheme.motion.contentFadeMs))
        ) {
            ClipPill(clip = clip, onClick = onClipTap)
        }
    }
}

/** What to say after «Омни»: two quiet examples while OMNIX waits. */
@Composable
private fun ExamplePhrases() {
    val colors = OmnixTheme.colors
    Row(
        modifier = Modifier.padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(R.string.omnix_home_example_weather, R.string.omnix_home_example_remind).forEach { res ->
            Text(
                text = stringResource(res),
                style = TextStyle(fontFamily = OmnixSans, fontSize = 13.sp),
                color = colors.textPrimary,
                maxLines = 1,
                modifier = Modifier
                    .border(BorderStroke(1.dp, colors.border), PillShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

private val OmnixPhase.isHearing: Boolean
    get() = this is OmnixPhase.Listening || this is OmnixPhase.Recognizing

/**
 * The logo as a switch: the animated stencil mark on a disc ([OmnixMarkCore]).
 * Off — a dimmed, still mark. On — the mark plays the motion of what OMNIX
 * is doing: rays on the real microphone level while it listens, a wave of
 * dots and a breathing slit while it thinks or starts, liquid rings while it
 * speaks.
 */
@Composable
internal fun PowerLogo(state: OmnixUiState, size: Dp, onTap: () -> Unit) {
    val colors = OmnixTheme.colors
    val motion = OmnixTheme.motion
    val reduced = OmnixTheme.reducedMotion
    val power = state.assistantPower
    val lit = power == AssistantPower.ON || power == AssistantPower.STARTING

    val markColor by animateColorAsState(
        targetValue = if (lit) colors.textPrimary else colors.textTertiary,
        animationSpec = tween(motion.stateTransitionMs),
        label = "logo_mark"
    )
    val busy = power == AssistantPower.STARTING || power == AssistantPower.STOPPING
    val mode = markMode(power, state.phase)
    val level = if (state.phase.isHearing) state.audioLevel else 0f

    val onLabel = stringResource(R.string.omnix_home_power_on_state)
    val offLabel = stringResource(R.string.omnix_home_power_off_state)
    val switchLabel = stringResource(R.string.omnix_home_power_a11y)
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(size)
            .omnixPressScale(interaction)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = !busy, onClick = onTap)
            .clearAndSetSemantics {
                contentDescription = switchLabel
                stateDescription = if (power == AssistantPower.ON) onLabel else offLabel
                role = Role.Switch
            },
        contentAlignment = Alignment.Center
    ) {
        OmnixMarkCore(
            mode = mode,
            level = level,
            markColor = markColor,
            discColor = colors.surface,
            modifier = Modifier.fillMaxSize(),
            reducedMotion = reduced
        )
    }
}

/** Which motion the home mark plays for the voice service state and phase. */
private fun markMode(power: AssistantPower, phase: OmnixPhase): MarkMode = when (power) {
    AssistantPower.OFF, AssistantPower.STOPPING -> MarkMode.IDLE
    AssistantPower.STARTING -> MarkMode.THINK
    AssistantPower.ON -> when (phase) {
        is OmnixPhase.Thinking, is OmnixPhase.Executing -> MarkMode.THINK
        is OmnixPhase.Speaking -> MarkMode.SPEAK
        else -> MarkMode.LISTEN
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

    // transitionSpec runs outside composition: read the theme here.
    val fadeMs = OmnixTheme.motion.contentFadeMs
    AnimatedContent(
        targetState = title to hint,
        transitionSpec = { fadeIn(tween(fadeMs)) togetherWith fadeOut(tween(fadeMs)) },
        label = "home_status"
    ) { (shownTitle, shownHint) ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = shownTitle,
                style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
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

/** The 2×2 quick actions. */
@Composable
private fun QuickActions(
    onTranslatorTap: () -> Unit,
    onRemindersTap: () -> Unit,
    onAutomationsTap: () -> Unit
) {
    val colors = OmnixTheme.colors
    val cards = buildList {
        add(QuickAction(OmnixTranslatorLineIcon, colors.accentBrand, R.string.omnix_home_card_translator, R.string.omnix_home_card_translator_sub, onTranslatorTap))
        add(QuickAction(OmnixAlarmLineIcon, colors.textPrimary, R.string.omnix_home_card_reminders, R.string.omnix_home_card_reminders_sub, onRemindersTap))
        add(QuickAction(OmnixBoltLineIcon, colors.textPrimary, R.string.omnix_home_card_automations, R.string.omnix_home_card_automations_sub, onAutomationsTap))
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        cards.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { card ->
                    QuickActionCard(
                        icon = card.icon,
                        iconTint = card.tint,
                        title = stringResource(card.title),
                        subtitle = stringResource(card.subtitle),
                        onClick = card.onClick,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private class QuickAction(
    val icon: ImageVector,
    val tint: Color,
    @StringRes val title: Int,
    @StringRes val subtitle: Int,
    val onClick: () -> Unit
)

@Composable
private fun QuickActionCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OmnixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .omnixPressScale(interaction)
            .clip(CardShape)
            .background(colors.surface)
            .border(BorderStroke(1.dp, colors.border), CardShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 15.sp),
                color = colors.textPrimary,
                maxLines = 1
            )
            Text(
                text = subtitle,
                style = TextStyle(fontFamily = OmnixSans, fontSize = 12.sp),
                color = colors.textSecondary,
                maxLines = 1
            )
        }
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
private val LOGO_MIN = 200.dp
private val LOGO_MAX = 300.dp
private val PillShape = RoundedCornerShape(18.dp)
private val CardShape = RoundedCornerShape(18.dp)
