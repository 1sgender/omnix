package com.omnix.assistant.presentation.voice

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.OmnixCloseLineIcon
import com.omnix.assistant.presentation.components.OmnixMicLineIcon
import com.omnix.assistant.presentation.components.OmnixPauseLineIcon
import com.omnix.assistant.presentation.components.OmnixPrimaryButton
import com.omnix.assistant.presentation.design.LocalReducedMotion
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme

/** Маршрут голосового режима: включает ассистента, просит микрофон, закрывается назад. */
@Composable
fun VoiceModeRoute(onClose: () -> Unit, viewModel: VoiceModeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.enter()
    }
    LaunchedEffect(Unit) { viewModel.enter() }
    val close = {
        viewModel.leave()
        onClose()
    }
    BackHandler(onBack = close)
    VoiceModeScreen(
        state = state,
        onToggleMic = viewModel::toggleMic,
        onClose = close,
        onGrantMicrophone = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
        onRetry = viewModel::enter
    )
}

/**
 * Голосовой режим (макет 2026-10-21): на весь экран, лаймовый круг дышит в такт
 * голосу и меняется по фазе, под ним — что OMNIX услышал и что ответил. Внизу
 * две кнопки: микрофон (говорить / перестать слушать) и выход.
 */
@Composable
fun VoiceModeScreen(
    state: VoiceModeUiState,
    onToggleMic: () -> Unit,
    onClose: () -> Unit,
    onGrantMicrophone: () -> Unit,
    onRetry: () -> Unit
) {
    val colors = OmnixTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.omnix_chat_title),
            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 15.sp),
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 8.dp)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            VoiceOrb(phase = state.phase, level = state.level)
            Spacer(Modifier.height(32.dp))
            Text(
                text = statusText(state),
                style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 18.sp),
                color = if (state.problem != null || state.assistantError != null) colors.stateError else colors.textPrimary,
                textAlign = TextAlign.Center
            )
            val caption = when (state.phase) {
                VoicePhase.SPEAKING, VoicePhase.IDLE -> state.answer
                else -> state.heard
            }
            if (state.problem == null && caption.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = caption,
                    style = TextStyle(fontFamily = OmnixSans, fontSize = 15.sp, lineHeight = 22.sp),
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            when (state.problem) {
                VoiceModeProblem.MICROPHONE_PERMISSION -> {
                    Spacer(Modifier.height(24.dp))
                    OmnixPrimaryButton(text = stringResource(R.string.omnix_voice_mic_grant), onClick = onGrantMicrophone)
                }
                VoiceModeProblem.START_FAILED -> {
                    Spacer(Modifier.height(24.dp))
                    OmnixPrimaryButton(text = stringResource(R.string.omnix_voice_retry), onClick = onRetry)
                }
                null -> Unit
            }
        }

        Row(
            modifier = Modifier.padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp)
        ) {
            val listening = state.phase == VoicePhase.LISTENING
            RoundButton(
                icon = if (listening) OmnixPauseLineIcon else OmnixMicLineIcon,
                label = stringResource(if (listening) R.string.omnix_voice_stop_listening else R.string.omnix_voice_talk),
                enabled = state.problem == null && state.phase != VoicePhase.STARTING,
                onClick = onToggleMic
            )
            RoundButton(
                icon = OmnixCloseLineIcon,
                label = stringResource(R.string.omnix_voice_close),
                enabled = true,
                onClick = onClose
            )
        }
    }
}

@Composable
private fun statusText(state: VoiceModeUiState): String = when {
    state.problem == VoiceModeProblem.MICROPHONE_PERMISSION -> stringResource(R.string.omnix_voice_mic_needed)
    state.problem == VoiceModeProblem.START_FAILED -> stringResource(R.string.omnix_voice_start_failed)
    state.assistantError != null -> state.assistantError
    else -> stringResource(
        when (state.phase) {
            VoicePhase.STARTING -> R.string.omnix_voice_starting
            VoicePhase.IDLE -> R.string.omnix_voice_idle
            VoicePhase.LISTENING -> R.string.omnix_voice_listening
            VoicePhase.THINKING -> R.string.omnix_voice_thinking
            VoicePhase.SPEAKING -> R.string.omnix_voice_speaking
            VoicePhase.CONFIRMING -> R.string.omnix_voice_confirming
        }
    )
}

/**
 * Лаймовый круг: растёт с громкостью голоса, пока OMNIX слушает; дышит, пока
 * думает или говорит; спокоен в ожидании. Без анимации при «уменьшить движение».
 */
@Composable
private fun VoiceOrb(phase: VoicePhase, level: Float) {
    val colors = OmnixTheme.colors
    val reduced = LocalReducedMotion.current
    val breathing = !reduced && (phase == VoicePhase.THINKING || phase == VoicePhase.SPEAKING)
    val transition = rememberInfiniteTransition(label = "voiceOrb")
    val breath by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (breathing) 1.08f else 1f,
        animationSpec = infiniteRepeatable(tween(BREATH_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "voiceOrbBreath"
    )
    val voice by animateFloatAsState(
        targetValue = if (reduced) 1f else 1f + level * VOICE_GROWTH,
        animationSpec = tween(VOICE_MS),
        label = "voiceOrbLevel"
    )
    val dimmed = phase == VoicePhase.IDLE || phase == VoicePhase.STARTING
    Box(
        modifier = Modifier
            .size(ORB_DP.dp)
            .scale(breath * voice)
            .clip(CircleShape)
            .background(if (dimmed) colors.accentBrand.copy(alpha = 0.45f) else colors.accentBrand),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size((ORB_DP - 28).dp)
                .border(2.dp, colors.background.copy(alpha = 0.35f), CircleShape)
        )
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = OmnixTheme.colors
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(colors.surfaceElevated)
            .clickable(enabled = enabled, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) colors.textPrimary else colors.textDisabled,
            modifier = Modifier.size(26.dp)
        )
    }
}

private const val ORB_DP = 180
private const val BREATH_MS = 900
private const val VOICE_MS = 120
private const val VOICE_GROWTH = 0.22f
