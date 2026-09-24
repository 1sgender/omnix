package com.omnix.assistant.presentation.firstrun

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.omnix.assistant.presentation.design.OmnixTheme
import kotlin.math.PI
import kotlin.math.sin

/**
 * Волна внутри кольца на Welcome-шаге онбординга (мок 2026-09-24): кольцо
 * «несёт смысл» — столбики слушают, это про голос, а не абстрактный круг.
 *
 * Это ИЛЛЮСТРАЦИЯ, не слой данных: микрофон на этом шаге ещё не запрошен,
 * реального audioLevel не существует, поэтому анимация детерминированная
 * (фазовые синусы, без рандома) и никогда не притворяется измерением —
 * контраст с амплитудным слоем ядра, который питается только реальным
 * сигналом (§33). При reduced motion столбики замирают.
 */
@Composable
internal fun OnboardingWaveform(
    modifier: Modifier = Modifier,
    barCount: Int = 7
) {
    val color = OmnixTheme.colors.accentBrandSoft
    val reduced = OmnixTheme.reducedMotion

    val transition = rememberInfiniteTransition(label = "welcome_wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = WAVE_PERIOD_MS, easing = LinearEasing)
        ),
        label = "welcome_wave_phase"
    )

    // Пропорции мока: ~46% ширины кольца, ~31% его диаметра.
    Canvas(
        modifier = modifier.size(width = WAVE_WIDTH_DP, height = WAVE_HEIGHT_DP)
    ) {
        val barSlot = size.width / (barCount * 2 - 1)
        val barWidth = barSlot
        for (i in 0 until barCount) {
            // Огибающая: крайние столбики ниже, центр выше — силуэт волны.
            val envelope = sin(PI * (i + 1) / (barCount + 1)).toFloat()
            val level = if (reduced) {
                REDUCED_LEVEL
            } else {
                0.30f + 0.44f * (0.5f + 0.5f * sin(phase * 2f * PI + i * PHASE_STEP))
            }
            val barHeight = size.height * level * (0.45f + 0.55f * envelope)
            drawRoundRect(
                color = color,
                topLeft = Offset(
                    x = i * 2f * barSlot,
                    y = (size.height - barHeight) / 2f
                ),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    }
}

private val WAVE_WIDTH_DP = 68.dp
private val WAVE_HEIGHT_DP = 46.dp
private const val WAVE_PERIOD_MS = 2_400
private const val PHASE_STEP = 0.9f
private const val REDUCED_LEVEL = 0.55f
