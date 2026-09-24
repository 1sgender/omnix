package com.omnix.assistant.presentation.firstrun

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.delay

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
    microphonePrompted: Boolean,
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
            StepHeading(clipFlow = clipFlow) { HeadingOf(current, state, microphoneGranted, microphonePrompted) }
        }

        Spacer(Modifier.weight(1f))

        if (isClipFlow) {
            // Мок: тонкое кольцо с бегущей дугой — весь смысл экрана в нём.
            ClipPairingRing(phase = clipRingPhase(state.clip))
        } else {
            // Мок: кольцо Welcome несёт смысл — брендовый синий (в тон лого) и
            // волна «слушающих» столбиков внутри; остальные шаги говорят
            // состоянием ядра (монохром, §30). Шум микрофона — своё тонкое
            // кольцо (мок 2026-09-24): три состояния «запрос / отказ / готово».
            Box(contentAlignment = Alignment.Center) {
                if (step == FirstRunStep.Microphone) {
                    MicrophoneRing(microphoneVisualState(microphoneGranted, microphonePrompted))
                } else {
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
                    prompted = microphonePrompted,
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
    microphoneGranted: Boolean,
    microphonePrompted: Boolean
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

        FirstRunStep.Microphone -> when (
            microphoneVisualState(microphoneGranted, microphonePrompted)
        ) {
            MicrophoneVisualState.Request -> Heading(
                title = stringResource(R.string.omnix_mic_request_title),
                body = stringResource(R.string.omnix_mic_request_body)
            )

            MicrophoneVisualState.Denied -> Heading(
                title = stringResource(R.string.omnix_mic_denied_title),
                body = stringResource(R.string.omnix_mic_denied_body)
            )

            MicrophoneVisualState.Granted -> Heading(
                title = stringResource(R.string.omnix_mic_ready),
                body = stringResource(R.string.omnix_mic_connected)
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
 * The three visual states of the microphone step (mock 2026-09-24).
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
 * user arrives at the step already granted (back gesture from the first
 * command), the step shows its way forward instead of bouncing them ahead.
 */
internal fun microphoneShouldAutoAdvance(granted: Boolean, grantedOnEntry: Boolean): Boolean =
    granted && !grantedOnEntry

// Мок 2026-09-24, геометрия: кольцо 132 px, трек/дуга 2 px, микрофон 32 px,
// галочка 44 px — пропорции сохранены на размере ядра 150 dp.
private const val MIC_RING_ARC_MS = 600L
private const val MIC_MIC_FADE_MS = 250L
private const val MIC_CHECK_FADE_MS = 300L
private const val MIC_GLYPH_RATIO = 0.24f
private const val MIC_CHECK_GLYPH_RATIO = 0.33f

// Дуга закрывается на 600 мс, микрофон гаснет на 850, галочка проявляется к
// 1150 мс — сверху добавлена пауза, чтобы текст «Готово» успели прочитать.
private const val MIC_ADVANCE_DELAY_MS = 1600L

/**
 * The microphone actions (§38, §50) under the ring — three honest states
 * (mock 2026-09-24); the WHAT-WHY heading lives above the ring in
 * [HeadingOf].
 *
 *  - **Request** — the prompt has never been shown: "Allow" and
 *    "Skip for now". No settings link yet — there is nothing to open.
 *  - **Denied** — the prompt was shown and the microphone is still off, so
 *    the system Settings screen becomes the primary button.
 *  - **Granted** — the step advances on its own, because the thing it asked
 *    for actually happened. On a revisit (back gesture) a Continue button
 *    is shown instead.
 *
 * The Android permission identifier is never shown.
 */
@Composable
private fun MicrophoneActions(
    granted: Boolean,
    prompted: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onAdvance: () -> Unit
) {
    // Captured on entry to the step: true when the permission was already
    // granted before this composition (back gesture from the first command).
    val grantedOnEntry = remember { granted }
    val autoAdvance = microphoneShouldAutoAdvance(granted, grantedOnEntry)

    when (microphoneVisualState(granted, prompted)) {
        MicrophoneVisualState.Request -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OmnixTheme.spacing.xs)
        ) {
            OmnixPrimaryButton(stringResource(R.string.omnix_error_mic_action), onRequest)
            OmnixTextButton(stringResource(R.string.omnix_skip_for_now), onAdvance)
        }

        MicrophoneVisualState.Denied -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OmnixTheme.spacing.xs)
        ) {
            OmnixPrimaryButton(stringResource(R.string.omnix_mic_open_settings), onOpenSettings)
            OmnixTextButton(stringResource(R.string.omnix_skip_for_now), onAdvance)
        }

        MicrophoneVisualState.Granted -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OmnixTheme.spacing.xs)
        ) {
            if (!autoAdvance) {
                // Revisit of an already completed step — one honest way on.
                OmnixPrimaryButton(stringResource(R.string.omnix_mic_continue), onAdvance)
            }
        }
    }

    // A genuine grant is its own confirmation: let the ring close and the
    // check land (1150 ms), give the copy a beat, then move on — no button
    // needed for something that already happened.
    LaunchedEffect(granted, grantedOnEntry) {
        if (autoAdvance) {
            delay(MIC_ADVANCE_DELAY_MS)
            onAdvance()
        }
    }
}

/**
 * The microphone step's ring (mock 2026-09-24): a thin 2 dp track in the
 * border tone (the mock's #2c2c2e) with the mic glyph inside.
 *
 *  - Request — the dim circle, the mic in primary ink.
 *  - Denied — the mic dimmed and crossed with a diagonal slash.
 *  - Granted — an ink circle draws itself over the track from the top
 *    (600 ms, ease-out), the mic fades out (250 ms), and a check fades in
 *    (300 ms). Reduced motion — everything instant.
 */
@Composable
private fun MicrophoneRing(state: MicrophoneVisualState) {
    val colors = OmnixTheme.colors
    val reduced = OmnixTheme.reducedMotion

    val arc = remember { Animatable(0f) }
    val micAlpha = remember { Animatable(1f) }
    val checkAlpha = remember { Animatable(0f) }

    LaunchedEffect(state, reduced) {
        if (state != MicrophoneVisualState.Granted) {
            arc.snapTo(0f)
            micAlpha.snapTo(1f)
            checkAlpha.snapTo(0f)
            return@LaunchedEffect
        }
        if (reduced) {
            arc.snapTo(1f)
            micAlpha.snapTo(0f)
            checkAlpha.snapTo(1f)
            return@LaunchedEffect
        }
        arc.animateTo(1f, tween(MIC_RING_ARC_MS.toInt(), easing = FastOutSlowInEasing))
        micAlpha.animateTo(0f, tween(MIC_MIC_FADE_MS.toInt(), easing = LinearEasing))
        checkAlpha.animateTo(1f, tween(MIC_CHECK_FADE_MS.toInt(), easing = LinearEasing))
    }

    Box(modifier = Modifier.size(OmnixTheme.coreSizes.home)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 2.dp.toPx()
            // The track is always present — the mock's faint full circle.
            drawCircle(color = colors.border, style = Stroke(strokeWidth))
            if (arc.value > 0f) {
                drawArc(
                    color = colors.textPrimary,
                    startAngle = -90f,
                    sweepAngle = 360f * arc.value,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
            if (micAlpha.value > 0f) {
                val micColor = if (state == MicrophoneVisualState.Denied) {
                    colors.textTertiary
                } else {
                    colors.textPrimary
                }
                drawMicGlyph(
                    sizePx = size.minDimension * MIC_GLYPH_RATIO,
                    color = micColor.copy(alpha = micAlpha.value),
                    slashed = state == MicrophoneVisualState.Denied
                )
            }
            if (checkAlpha.value > 0f) {
                drawCheckGlyph(
                    sizePx = size.minDimension * MIC_CHECK_GLYPH_RATIO,
                    color = colors.textPrimary.copy(alpha = checkAlpha.value)
                )
            }
        }
    }
}

/**
 * Mic glyph on a 24 dp design box (capsule, holder arc, stem, base),
 * centered in the canvas; 2 dp round-capped stroke, the mock's 1.8 px at
 * 32 px. [slashed] draws the denied state's diagonal over it.
 */
private fun DrawScope.drawMicGlyph(sizePx: Float, color: Color, slashed: Boolean) {
    val u = sizePx / 24f
    val origin = size.minDimension / 2f - sizePx / 2f
    fun p(v: Float) = v * u + origin
    val strokeWidth = 2.dp.toPx()
    drawRoundRect(
        topLeft = Offset(p(9f), p(3f)),
        size = Size(6f * u, 11f * u),
        cornerRadius = CornerRadius(3f * u, 3f * u),
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )
    drawArc(
        topLeft = Offset(p(5.5f), p(4f)),
        size = Size(13f * u, 13f * u),
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = false,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )
    drawLine(color, Offset(p(12f), p(17f)), Offset(p(12f), p(21f)), strokeWidth, StrokeCap.Round)
    drawLine(color, Offset(p(9f), p(21f)), Offset(p(15f), p(21f)), strokeWidth, StrokeCap.Round)
    if (slashed) {
        drawLine(color, Offset(p(4.5f), p(19.5f)), Offset(p(19.5f), p(4.5f)), strokeWidth, StrokeCap.Round)
    }
}

/** Check mark for the granted state — one stroke, round caps. */
private fun DrawScope.drawCheckGlyph(sizePx: Float, color: Color) {
    val u = sizePx / 24f
    val origin = size.minDimension / 2f - sizePx / 2f
    fun p(v: Float) = v * u + origin
    val strokeWidth = 2.dp.toPx()
    drawLine(color, Offset(p(5.5f), p(13f)), Offset(p(10.5f), p(17.5f)), strokeWidth, StrokeCap.Round)
    drawLine(color, Offset(p(10.5f), p(17.5f)), Offset(p(18.5f), p(7f)), strokeWidth, StrokeCap.Round)
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
