package com.omnix.assistant.presentation.firstrun

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.OmnixPrimaryButton
import com.omnix.assistant.presentation.components.omnixPressScale
import com.omnix.assistant.presentation.components.OmnixSpokenExample
import com.omnix.assistant.presentation.components.OmnixTextButton
import com.omnix.assistant.presentation.components.SystemStateView
import com.omnix.assistant.presentation.core.CoreState
import com.omnix.assistant.presentation.core.OmnixCore
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.design.OmnixWordmarkStyle
import com.omnix.assistant.presentation.state.ClipState
import com.omnix.assistant.presentation.state.OmnixUiState
import com.omnix.assistant.presentation.state.SystemStateType

/**
 * First run (§34, §67).
 *
 * The Core is present from the very first screen and stays in place across
 * every step — the user meets one object and watches it react, rather than
 * paging through a carousel of illustrations (§30).
 *
 * Композиция мока Welcome (2026-09-24), распространённая на ВСЕ шаги:
 * заголовок и подзаголовок — над кольцом, в одну композицию (блок 82% ширины,
 * логичный перенос); под кольцом — только действия. Кольцо между шагами не
 * двигается, меняется лишь его состояние; заголовок обновляется на месте.
 */
@Composable
fun FirstRunScreen(
    step: FirstRunStep,
    state: OmnixUiState,
    microphoneGranted: Boolean,
    modifier: Modifier = Modifier,
    onAdvance: () -> Unit,
    onSkipDevice: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onEnterActivationCode: () -> Unit,
    onSearchAgain: () -> Unit
) {
    val spacing = OmnixTheme.spacing
    val colors = OmnixTheme.colors

    // The durations are read here, in composable scope: `transitionSpec`
    // runs outside it and cannot touch the theme.
    val enterMs = OmnixTheme.motion.screenEnterMs
    val exitMs = OmnixTheme.motion.screenExitMs

    // Мок «подключение Clip» (2026-09-24): флоу подключения живёт в своей
    // эстетике — маленький знак-кольцо без надписи вместо логотипа-текста,
    // тонкое кольцо вместо ядра; остальные шаги — в прежней композиции.
    val isClipFlow = step == FirstRunStep.DeviceDetection || step == FirstRunStep.ClipPairing

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = spacing.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(spacing.xxxl))

        if (isClipFlow) {
            OmnixRingMark()
        } else {
            Text(
                text = stringResource(R.string.omnix_wordmark),
                style = OmnixWordmarkStyle,
                color = colors.textSecondary
            )

            // Мок 2026-09-24: точки прогресса вместо «непонятного подчёркивания» —
            // сразу видно, что это шаг 1 из 3 вех онбординга.
            val progressIndex = step.progressIndex
            if (progressIndex != null) {
                Spacer(Modifier.height(spacing.sm))
                OnboardingProgressDots(
                    total = step.progressTotal,
                    active = progressIndex
                )
            }
        }

        // Заголовок шага — НАД кольцом (мок): шаги сменяются, композиция
        // остаётся на месте, кольцо не прыгает.
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                fadeIn(tween(enterMs)) togetherWith fadeOut(tween(exitMs))
            },
            label = "first-run-heading"
        ) { current ->
            val clipFlow =
                current == FirstRunStep.DeviceDetection || current == FirstRunStep.ClipPairing
            StepHeading(clipFlow = clipFlow) { HeadingOf(current, state, microphoneGranted) }
        }

        Spacer(Modifier.weight(1f))

        if (isClipFlow) {
            // Мок: тонкое кольцо с бегущей дугой — весь смысл экрана в нём.
            ClipPairingRing(phase = clipRingPhase(state.clip))
        } else {
            // Мок: кольцо Welcome несёт смысл — брендовый синий (в тон лого) и
            // волна «слушающих» столбиков внутри; остальные шаги говорят
            // состоянием ядра (монохром, §30).
            Box(contentAlignment = Alignment.Center) {
                OmnixCore(
                    state = coreStateFor(step, state, microphoneGranted),
                    size = OmnixTheme.coreSizes.home,
                    audioLevel = state.audioLevel,
                    ringColor = if (step == FirstRunStep.Welcome) {
                        colors.accentBrand
                    } else {
                        null
                    }
                )
                if (step == FirstRunStep.Welcome) {
                    OnboardingWaveform()
                }
            }
        }

        Spacer(Modifier.height(spacing.xxl))

        // Под кольцом — только действия шага.
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                fadeIn(tween(enterMs)) togetherWith fadeOut(tween(exitMs))
            },
            label = "first-run-actions"
        ) { current ->
            when (current) {
                FirstRunStep.Welcome -> WelcomeActions(onAdvance)

                FirstRunStep.DeviceDetection -> DeviceDetectionActions(
                    clip = state.clip,
                    onAdvance = onAdvance,
                    onSkip = onSkipDevice,
                    onEnterCode = onEnterActivationCode,
                    onSearchAgain = onSearchAgain
                )

                FirstRunStep.ClipPairing -> ClipPairingActions(
                    clip = state.clip,
                    onAdvance = onAdvance
                )

                FirstRunStep.Microphone -> MicrophoneActions(
                    granted = microphoneGranted,
                    onRequest = onRequestMicrophone,
                    onOpenSettings = onOpenSystemSettings,
                    onAdvance = onAdvance
                )

                FirstRunStep.FirstCommand -> FirstCommandActions(
                    state = state,
                    onAdvance = onAdvance
                )

                FirstRunStep.Complete -> Box(Modifier.fillMaxWidth())
            }
        }

        Spacer(Modifier.weight(1f))
        // Мок: кнопка не прилипает к жест-бару — запас сверх systemBarsPadding.
        Spacer(Modifier.height(spacing.xxxl))
    }
}

/**
 * The Core reflects the step, so progress is felt rather than counted. There
 * is no progress bar and no "Step 2 of 5" (§30, §84).
 */
private fun coreStateFor(
    step: FirstRunStep,
    state: OmnixUiState,
    microphoneGranted: Boolean
): CoreState = when (step) {
    FirstRunStep.Welcome -> CoreState.IDLE
    FirstRunStep.DeviceDetection -> when (state.clip) {
        is ClipState.Connected -> CoreState.SUCCESS
        is ClipState.Connecting, ClipState.Searching -> CoreState.THINKING
        else -> CoreState.IDLE
    }
    FirstRunStep.ClipPairing ->
        if (state.clip is ClipState.Connected) CoreState.SUCCESS else CoreState.EXECUTING
    FirstRunStep.Microphone -> if (microphoneGranted) CoreState.SUCCESS else CoreState.IDLE
    // During the first command the Core does what it will always do.
    FirstRunStep.FirstCommand -> state.coreState
    FirstRunStep.Complete -> CoreState.IDLE
}

/**
 * Заголовок шага в композиции мока: блок 82% ширины, центрированные тексты,
 * сверху ритмический отступ. [content] отдаёт пару «заголовок/подзаголовок»
 * шага — composables из-за stringResource.
 */
@Composable
private fun StepHeading(clipFlow: Boolean, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(if (clipFlow) 1f else 0.82f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OmnixTheme.spacing.sm)
    ) {
        Spacer(Modifier.height(OmnixTheme.spacing.xxl))
        content()
    }
}

@Composable
private fun HeadingOf(
    step: FirstRunStep,
    state: OmnixUiState,
    microphoneGranted: Boolean
) {
    when (step) {
        FirstRunStep.Welcome -> Heading(
            title = stringResource(R.string.omnix_welcome_headline),
            body = stringResource(R.string.omnix_welcome_body)
        )

        FirstRunStep.DeviceDetection, FirstRunStep.ClipPairing -> {
            // Мок подключения Clip: копия зависит от фазы кольца, а не от шага.
            when (clipRingPhase(state.clip)) {
                ClipRingPhase.SEARCH -> Heading(
                    title = stringResource(R.string.omnix_pairing_title),
                    body = stringResource(R.string.omnix_pairing_searching),
                    clip = true
                )

                ClipRingPhase.LOST -> Heading(
                    title = stringResource(R.string.omnix_pairing_not_found_title),
                    body = stringResource(R.string.omnix_pairing_not_found_body),
                    clip = true
                )

                ClipRingPhase.FOUND -> Heading(
                    title = stringResource(R.string.omnix_pairing_found_title),
                    body = stringResource(R.string.omnix_pairing_found_body),
                    clip = true
                )
            }
        }

        FirstRunStep.Microphone ->
            if (microphoneGranted) {
                Heading(
                    title = stringResource(R.string.omnix_mic_ready),
                    body = null
                )
            } else {
                Heading(
                    title = stringResource(R.string.omnix_mic_title),
                    body = stringResource(R.string.omnix_mic_body)
                )
            }

        FirstRunStep.FirstCommand ->
            if (state.lastInteraction != null) {
                Heading(
                    title = stringResource(R.string.omnix_first_success_title),
                    body = stringResource(R.string.omnix_first_success_body)
                )
            } else {
                Heading(
                    title = stringResource(R.string.omnix_clip_ready_title),
                    body = stringResource(R.string.omnix_clip_ready_say)
                )
            }

        FirstRunStep.Complete -> Unit
    }
}

/**
 * Пара «заголовок + подзаголовок». Обычные шаги — типографика темы; флоу
 * подключения Clip — типографика мока: 28sp/600 с плотным трекингом и 17sp
 * серым, подзаголовок с минимальной высотой (фазы сменяются без прыжков).
 */
@Composable
private fun Heading(title: String, body: String?, clip: Boolean = false) {
    val colors = OmnixTheme.colors
    Text(
        text = title,
        style = if (clip) ClipTitleStyle else OmnixTheme.typography.display,
        color = colors.textPrimary,
        textAlign = TextAlign.Center
    )
    body?.let {
        Text(
            text = it,
            style = if (clip) ClipBodyStyle else OmnixTheme.typography.body,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = if (clip) {
                Modifier.heightIn(min = CLIP_BODY_MIN_HEIGHT)
            } else {
                Modifier
            }
        )
    }
}

/** Типографика мока подключения Clip: заголовок 28sp/600, трекинг −0.022em. */
private val ClipTitleStyle = TextStyle(
    fontSize = 28.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = (-0.62).sp
)

/** Подзаголовок мока: 17sp, трекинг −0.01em, интерлиньяж 1.35. */
private val ClipBodyStyle = TextStyle(
    fontSize = 17.sp,
    letterSpacing = (-0.17).sp,
    lineHeight = 23.sp
)

/** Тихая кнопка мока: 15sp. */
private val ClipQuietStyle = TextStyle(fontSize = 15.sp)

private val ACTION_GAP = 4.dp
private val LINK_HEIGHT = 44.dp
private val QUIET_HEIGHT = 40.dp
private val CLIP_BODY_MIN_HEIGHT = 46.dp

// ---- Действия шагов (под кольцом) ----

@Composable
private fun WelcomeActions(onAdvance: () -> Unit) {
    OmnixPrimaryButton(
        text = stringResource(R.string.omnix_welcome_cta),
        onClick = onAdvance
    )
}

@Composable
private fun DeviceDetectionActions(
    clip: ClipState,
    onAdvance: () -> Unit,
    onSkip: () -> Unit,
    onEnterCode: () -> Unit,
    onSearchAgain: () -> Unit
) {
    when (clipRingPhase(clip)) {
        // Мок прячет кнопки на время поиска, но §34: путь вперёд всегда
        // открыт — остаётся тихое «Пропустить» (отклонение зафиксировано).
        ClipRingPhase.SEARCH ->
            ClipQuietButton(stringResource(R.string.omnix_pairing_skip), onSkip)

        ClipRingPhase.LOST -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ACTION_GAP)
        ) {
            OmnixPrimaryButton(stringResource(R.string.omnix_pairing_retry), onSearchAgain)
            ClipLinkButton(stringResource(R.string.omnix_pairing_code_link), onEnterCode)
            ClipQuietButton(stringResource(R.string.omnix_pairing_skip), onSkip)
        }

        ClipRingPhase.FOUND ->
            OmnixPrimaryButton(stringResource(R.string.omnix_continue), onAdvance)
    }
}

@Composable
private fun ClipPairingActions(clip: ClipState, onAdvance: () -> Unit) {
    // Соединение ещё идёт — просто ждём у кольца; подключено — продолжаем.
    if (clipRingPhase(clip) == ClipRingPhase.FOUND) {
        OmnixPrimaryButton(stringResource(R.string.omnix_clip_continue), onAdvance)
    }
}

/**
 * Ссылка мока: синий текст без рамки, 17sp, рост 44dp.
 */
@Composable
private fun ClipLinkButton(text: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .omnixPressScale(interactionSource, pressedScale = 0.98f)
            .defaultMinSize(minHeight = LINK_HEIGHT),
        colors = ButtonDefaults.textButtonColors(
            contentColor = OmnixTheme.colors.accentBrand
        ),
        contentPadding = PaddingValues(horizontal = OmnixTheme.spacing.md, vertical = OmnixTheme.spacing.xs)
    ) {
        Text(text = text, style = ClipBodyStyle)
    }
}

/**
 * Тихая кнопка мока: серый мелкий текст, 15sp, рост 40dp.
 */
@Composable
private fun ClipQuietButton(text: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .omnixPressScale(interactionSource, pressedScale = 0.98f)
            .defaultMinSize(minHeight = QUIET_HEIGHT),
        colors = ButtonDefaults.textButtonColors(
            contentColor = OmnixTheme.colors.textSecondary
        ),
        contentPadding = PaddingValues(horizontal = OmnixTheme.spacing.md, vertical = OmnixTheme.spacing.xs)
    ) {
        Text(text = text, style = ClipQuietStyle)
    }
}

/**
 * The microphone request (§38, §50): WHAT / WHY / ACTION. The WHAT-WHY pair
 * живёт в заголовке над кольцом; здесь — единственное действие. The Android
 * permission identifier is never shown.
 */
@Composable
private fun MicrophoneActions(
    granted: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onAdvance: () -> Unit
) {
    if (granted) {
        OmnixPrimaryButton(stringResource(R.string.omnix_mic_continue), onAdvance)
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OmnixTheme.spacing.xs)
        ) {
            OmnixPrimaryButton(stringResource(R.string.omnix_error_mic_action), onRequest)
            OmnixTextButton(stringResource(R.string.omnix_mic_open_settings), onOpenSettings)
        }
    }
}

/**
 * The first real command. Success here is a genuine interaction, not a
 * simulated one — the state comes from the live pipeline (§34).
 */
@Composable
private fun FirstCommandActions(state: OmnixUiState, onAdvance: () -> Unit) {
    val spacing = OmnixTheme.spacing

    if (state.lastInteraction != null) {
        OmnixPrimaryButton(stringResource(R.string.omnix_first_success_cta), onAdvance)
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            OmnixSpokenExample(stringResource(R.string.omnix_home_example_time))
            if (state.systemState == SystemStateType.MICROPHONE_DENIED) {
                SystemStateView(type = SystemStateType.MICROPHONE_DENIED)
            }
            OmnixTextButton(stringResource(R.string.omnix_skip_for_now), onAdvance)
        }
    }
}
