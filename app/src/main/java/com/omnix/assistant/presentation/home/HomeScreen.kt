package com.omnix.assistant.presentation.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.ClipStatusBar
import com.omnix.assistant.presentation.components.OmnixHairline
import com.omnix.assistant.presentation.components.OmnixSpokenExample
import com.omnix.assistant.presentation.components.SystemStateView
import com.omnix.assistant.presentation.core.CoreBadge
import com.omnix.assistant.presentation.core.CoreState
import com.omnix.assistant.presentation.core.OmnixAudioBars
import com.omnix.assistant.presentation.core.OmnixCore
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.design.OmnixWordmarkStyle
import com.omnix.assistant.presentation.state.ActionPhrase
import com.omnix.assistant.presentation.state.GuidanceLevel
import com.omnix.assistant.presentation.state.OmnixPhase
import com.omnix.assistant.presentation.state.OmnixUiState
import kotlinx.coroutines.delay

/**
 * Home — presence and orientation, not a dashboard (§9, §21, §22).
 *
 * The screen answers exactly four questions:
 *  1. Is OMNIX here?          → the wordmark and the Core
 *  2. Is my Clip connected?   → [ClipStatusBar]
 *  3. What is it doing?       → the state line under the Core
 *  4. What can I say?         → guidance, which fades as the user learns
 *
 * The Core, its state and guidance are optically centred in the space beneath
 * the device status. This makes idle feel intentional instead of leaving a
 * large accidental void above or below the main interaction.
 *
 * There is no CPU load, no provider name, no latency, no token count and no
 * command counter anywhere on this screen (§9, §31, §84).
 */
@Composable
fun HomeScreen(
    state: OmnixUiState,
    modifier: Modifier = Modifier,
    onClipTap: () -> Unit = {},
    onSystemStateAction: () -> Unit = {}
) {
    val spacing = OmnixTheme.spacing
    val colors = OmnixTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = spacing.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(spacing.xxl))

        Text(
            text = stringResource(R.string.omnix_wordmark),
            style = OmnixWordmarkStyle,
            color = colors.textSecondary
        )

        // A restrained ice-cyan brand accent gives the otherwise quiet header
        // a point of recognition without turning it into a control.
        Spacer(Modifier.height(spacing.xs))
        Box(
            modifier = Modifier
                .width(spacing.xxl)
                .height(OmnixHairline)
                .background(colors.stateIdle.copy(alpha = 0.72f))
        )
        Spacer(Modifier.height(spacing.xs))

        ClipStatusBar(
            clip = state.clip,
            isOnline = state.isOnline,
            onClick = onClipTap
        )

        // The available area, rather than a fixed spacer, owns the main
        // composition. The Core bundle remains balanced on different display
        // heights and when the navigation bar consumes system insets.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // The Core, flanked by the audio bars while it is hearing or
                // speaking. The slots are always reserved so it never shifts.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    AudioBarsSlot(state = state, mirrored = true)

                    OmnixCore(
                        state = state.coreState,
                        size = OmnixTheme.coreSizes.home,
                        audioLevel = state.audioLevel,
                        // Matrix: the offline badge docks onto the IDLE core
                        // so "why is nothing answering" reads before the
                        // first word is spoken.
                        badge = if (state.coreState == CoreState.IDLE && !state.isOnline) {
                            CoreBadge.WIFI_OFF
                        } else {
                            null
                        },
                        contentDescription = stringResource(
                            R.string.omnix_a11y_core_state,
                            stateLabel(state)
                        )
                    )

                    AudioBarsSlot(state = state, mirrored = false)
                }

                Spacer(Modifier.height(spacing.xxl))

                StateLine(state = state)

                Spacer(Modifier.height(spacing.md))

                // Guidance is progressive: it is driven by real stored
                // signals, not by a timer or a hardcoded new-user flag.
                Guidance(state = state)

                state.systemState?.let { systemState ->
                    Spacer(Modifier.height(spacing.xl))
                    SystemStateView(
                        type = systemState,
                        onAction = onSystemStateAction
                    )
                }
            }
        }
    }
}

/**
 * A fixed-width slot for the audio bars.
 *
 * The slot is always present so the Core never shifts horizontally when
 * listening starts; only the bars inside it appear (§46).
 */
@Composable
private fun AudioBarsSlot(state: OmnixUiState, mirrored: Boolean) {
    val visible = state.coreState.isAudioReactive
    Box(modifier = Modifier.size(width = 34.dp, height = 34.dp)) {
        if (visible) {
            OmnixAudioBars(
                level = state.audioLevel,
                color = OmnixTheme.colors.let { colors ->
                    when (state.coreState) {
                        CoreState.SPEAKING -> colors.stateSpeaking
                        else -> colors.stateListening
                    }
                },
                mirrored = mirrored
            )
        }
    }
}

/**
 * The single line of text under the Core. It always says what OMNIX is doing
 * in the user's language — "Calling Alex…", never "EXECUTING" (§15, §19).
 */
@Composable
private fun StateLine(state: OmnixUiState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            // Reserved height keeps the Core from shifting as the label
            // changes length between states (§46).
            .height(56.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Text(
            text = stateLabel(state),
            style = OmnixTheme.typography.title2,
            color = OmnixTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp)
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

/**
 * Progressive disclosure (§10, §24–§26, §82).
 *
 * New user  → wake word plus a rotating set of concrete examples
 * Familiar  → wake word only
 * Minimal   → nothing; presence is enough
 */
@Composable
private fun Guidance(state: OmnixUiState, modifier: Modifier = Modifier) {
    val spacing = OmnixTheme.spacing
    val visible = state.phase is OmnixPhase.Idle && state.guidance != GuidanceLevel.Minimal

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(OmnixTheme.motion.screenEnterMs)),
        exit = fadeOut(tween(OmnixTheme.motion.screenExitMs)),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Text(
                text = stringResource(
                    R.string.omnix_home_hint_say_omni_wake,
                    stringResource(R.string.omnix_wake_word_quoted)
                ),
                style = OmnixTheme.typography.subheadline,
                color = OmnixTheme.colors.textSecondary,
                textAlign = TextAlign.Center
            )
            if (state.guidance == GuidanceLevel.New) {
                RotatingSpokenExample()
            }
            if (!state.isOnline) {
                Text(
                    text = stringResource(R.string.omnix_home_offline_partial),
                    style = OmnixTheme.typography.caption,
                    color = OmnixTheme.colors.textTertiary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Cycles through compact, real commands without claiming that OMNIX supports
 * a feature it does not. Animation is stopped when reduced motion is enabled.
 */
@Composable
private fun RotatingSpokenExample(modifier: Modifier = Modifier) {
    val examples = listOf(
        stringResource(R.string.omnix_home_example_time),
        stringResource(R.string.omnix_home_example_weather),
        stringResource(R.string.omnix_home_example_music)
    )
    val reducedMotion = OmnixTheme.reducedMotion
    var exampleIndex by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(reducedMotion) {
        if (!reducedMotion) {
            while (true) {
                delay(EXAMPLE_ROTATION_MS)
                exampleIndex = (exampleIndex + 1) % examples.size
            }
        }
    }

    Crossfade(
        targetState = examples[exampleIndex],
        animationSpec = tween(OmnixTheme.motion.contentFadeMs),
        label = "home_command_example"
    ) { example ->
        OmnixSpokenExample(text = example, modifier = modifier)
    }
}

private const val EXAMPLE_ROTATION_MS = 5_500L
