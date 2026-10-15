package com.omnix.assistant.presentation.home

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.OmnixAlarmLineIcon
import com.omnix.assistant.presentation.components.OmnixBoltLineIcon
import com.omnix.assistant.presentation.components.OmnixMemoryLineIcon
import com.omnix.assistant.presentation.components.OmnixOnDeviceLineIcon
import com.omnix.assistant.presentation.components.OmnixTranslatorLineIcon
import com.omnix.assistant.presentation.components.SystemStateView
import com.omnix.assistant.presentation.components.clipDotColor
import com.omnix.assistant.presentation.components.clipLabel
import com.omnix.assistant.presentation.components.omnixPressScale
import com.omnix.assistant.presentation.design.OmnixMonoLabelStyle
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.firstrun.OmnixRingMark
import com.omnix.assistant.presentation.state.ActionPhrase
import com.omnix.assistant.presentation.state.ClipCapability
import com.omnix.assistant.presentation.state.ClipState
import com.omnix.assistant.presentation.state.OmnixPhase
import com.omnix.assistant.presentation.state.OmnixUiState

/**
 * Home — design v2 canvas, artboard Main.
 *
 * Top to bottom, exactly as the canvas draws it: the mark with the wordmark
 * and the headphones pill; the Core between the L and R labels; the state
 * title, the hint and the on-device pill; the 2×2 quick actions.
 *
 * Every value comes from real state: the pill shows the battery the
 * headphones report (or their honest status), the title is the phase, the
 * on-device pill appears only while OMNIX is actually offline, and a tap on
 * the Core starts or stops listening through the same path as the wake word.
 */
@Composable
fun HomeScreen(
    state: OmnixUiState,
    modifier: Modifier = Modifier,
    onClipTap: () -> Unit = {},
    onCoreTap: () -> Unit = {},
    onTranslatorTap: () -> Unit = {},
    onMemoryTap: () -> Unit = {},
    onRemindersTap: () -> Unit = {},
    onAutomationsTap: () -> Unit = {},
    onSystemStateAction: () -> Unit = {}
) {
    Column(modifier = modifier.fillMaxSize()) {
        HomeHeader(clip = state.clip, onClipTap = onClipTap)

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = SCREEN_PADDING)
        ) {
            // The canvas is drawn for a 390×844 phone. On a shorter screen
            // the Core gives up height first, so the quick actions always
            // keep their place at the bottom.
            val deficit = (CANVAS_MAIN_HEIGHT - maxHeight).coerceAtLeast(0.dp)
            val coreSize = (CORE_SIZE - deficit).coerceAtLeast(CORE_MIN_SIZE)
            val coreTop = (CORE_TOP - (deficit - (CORE_SIZE - coreSize)))
                .coerceIn(SCREEN_PADDING, CORE_TOP)

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(coreTop))

                CoreRow(state = state, coreSize = coreSize, onCoreTap = onCoreTap)

                Spacer(Modifier.height(32.dp))

                StatusBlock(state = state)

                state.systemState?.let { systemState ->
                    Spacer(Modifier.height(OmnixTheme.spacing.md))
                    SystemStateView(type = systemState, onAction = onSystemStateAction)
                }

                Spacer(Modifier.weight(1f))

                QuickActions(
                    onTranslatorTap = onTranslatorTap,
                    onMemoryTap = onMemoryTap,
                    onRemindersTap = onRemindersTap,
                    onAutomationsTap = onAutomationsTap
                )

                Spacer(Modifier.height(SCREEN_PADDING))
            }
        }
    }
}

/** The mark, the wordmark and the headphones pill. */
@Composable
private fun HomeHeader(clip: ClipState, onClipTap: () -> Unit) {
    val colors = OmnixTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SCREEN_PADDING, top = SCREEN_PADDING, end = SCREEN_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OmnixRingMark(
                color = colors.textPrimary,
                width = 26.dp,
                height = 22.dp,
                modifier = Modifier.clearAndSetSemantics {}
            )
            Text(
                text = stringResource(R.string.omnix_wordmark),
                style = TextStyle(
                    fontFamily = OmnixSans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    letterSpacing = 0.12.em
                ),
                color = colors.textPrimary
            )
        }

        ClipPill(clip = clip, onClick = onClipTap)
    }
}

/**
 * The headphones pill. The canvas shows "L 82 · R 79"; the headset reports
 * one battery value, so the pill shows that one value — never an invented
 * second number.
 */
@Composable
private fun ClipPill(clip: ClipState, onClick: () -> Unit) {
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

/** The Core between the L and R labels. */
@Composable
private fun CoreRow(state: OmnixUiState, coreSize: Dp, onCoreTap: () -> Unit) {
    val colors = OmnixTheme.colors
    val listening = state.phase is OmnixPhase.Listening || state.phase is OmnixPhase.Recognizing
    val active = state.phase !is OmnixPhase.Idle && state.phase !is OmnixPhase.Error
    val ring = if (active) colors.accentBrand else colors.textPrimary.copy(alpha = 0.10f)
    val mark = if (active) colors.accentBrand else colors.textPrimary
    val coreLabel = stringResource(
        if (listening) R.string.omnix_home_core_stop else R.string.omnix_home_core_start
    )
    val interaction = remember { MutableInteractionSource() }
    val scale = coreSize / CORE_SIZE

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SideLabel("L")
        Box(
            modifier = Modifier
                .size(coreSize)
                .omnixPressScale(interaction)
                .clip(CircleShape)
                // #141310 on the canvas: halfway between the ground and a card.
                .background(lerp(colors.background, colors.surface, 0.5f))
                .border(BorderStroke(1.5.dp, ring), CircleShape)
                .clickable(interactionSource = interaction, indication = null, onClick = onCoreTap)
                .clearAndSetSemantics {
                    contentDescription = coreLabel
                    role = Role.Button
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(188.dp * scale)
                    .border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.06f)), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // The canvas draws the big mark with a 2.6 stroke on the 34-unit grid.
                OmnixRingMark(color = mark, width = 104.dp * scale, height = 80.dp * scale, strokeUnits = 2.6f)
            }
        }
        SideLabel("R")
    }
}

@Composable
private fun SideLabel(text: String) {
    Text(
        text = text,
        style = OmnixMonoLabelStyle.copy(fontSize = 13.sp, letterSpacing = 0.04.em),
        color = OmnixTheme.colors.textTertiary,
        modifier = Modifier.clearAndSetSemantics {}
    )
}

/** Title, hint and the on-device pill. */
@Composable
private fun StatusBlock(state: OmnixUiState) {
    val colors = OmnixTheme.colors
    val hint = when (state.phase) {
        is OmnixPhase.Listening, is OmnixPhase.Recognizing -> stringResource(R.string.omnix_home_hint_listening)
        is OmnixPhase.Idle, is OmnixPhase.Error -> stringResource(R.string.omnix_home_hint_idle)
        else -> null
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stateLabel(state),
            style = TextStyle(
                fontFamily = OmnixSans,
                fontWeight = FontWeight.Medium,
                fontSize = 28.sp,
                lineHeight = 32.sp
            ),
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
        hint?.let {
            Text(
                text = it,
                style = TextStyle(fontFamily = OmnixSans, fontSize = 15.sp, lineHeight = 22.sp),
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 280.dp)
            )
        }
        if (!state.isOnline) {
            OnDevicePill(modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun OnDevicePill(modifier: Modifier = Modifier) {
    val colors = OmnixTheme.colors
    Row(
        modifier = modifier
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
    onMemoryTap: () -> Unit,
    onRemindersTap: () -> Unit,
    onAutomationsTap: () -> Unit
) {
    val colors = OmnixTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickActionCard(
                icon = OmnixTranslatorLineIcon,
                iconTint = colors.accentBrand,
                title = stringResource(R.string.omnix_home_card_translator),
                subtitle = stringResource(R.string.omnix_home_card_translator_sub),
                onClick = onTranslatorTap,
                modifier = Modifier.weight(1f)
            )
            QuickActionCard(
                icon = OmnixMemoryLineIcon,
                iconTint = colors.textPrimary,
                title = stringResource(R.string.omnix_home_card_memory),
                subtitle = stringResource(R.string.omnix_home_card_memory_sub),
                onClick = onMemoryTap,
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickActionCard(
                icon = OmnixAlarmLineIcon,
                iconTint = colors.textPrimary,
                title = stringResource(R.string.omnix_home_card_reminders),
                subtitle = stringResource(R.string.omnix_home_card_reminders_sub),
                onClick = onRemindersTap,
                modifier = Modifier.weight(1f)
            )
            QuickActionCard(
                icon = OmnixBoltLineIcon,
                iconTint = colors.textPrimary,
                title = stringResource(R.string.omnix_home_card_automations),
                subtitle = stringResource(R.string.omnix_home_card_automations_sub),
                onClick = onAutomationsTap,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

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
private val CORE_SIZE = 248.dp
private val CORE_MIN_SIZE = 168.dp
private val CORE_TOP = 56.dp

/** Height of the canvas' main area: 844 minus the header and the tab bar. */
private val CANVAS_MAIN_HEIGHT = 708.dp

private val PillShape = RoundedCornerShape(18.dp)
private val CardShape = RoundedCornerShape(18.dp)
