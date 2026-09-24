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
 * Волна внутри кольца на Welcome-шаге онбординга (мок 2026-09-24, калибровка
 * по повторному рендеру): кольцо «несёт смысл» — столбики слушают, это про
 * голос, а не абстрактный круг.
 *
 * Калибровка по рендеру: 4 тонких столбика (ширина ~0.4 слота), волна занимает
 * 30% диаметра кольца по ширине и 40% по высоте; крайние столбики приглушены
 * до ~88% яркости — огибающая красит не только высоту, но и цвет.
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
    barCount: Int = 4
) {
    val color = OmnixTheme.colors.accentBrandSoft
    val reduced = OmnixTheme.reducedMotion

    // Пропорции от размера кольца (мок): 30% ширины, 40% высоты.
    val ringSize = OmnixTheme.coreSizes.home
    val waveWidth = (ringSize.value * WIDTH_RATIO).dp
    val waveHeight = (ringSize.value * HEIGHT_RATIO).dp

    val transition = rememberInfiniteTransition(label = "welcome_wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = WAVE_PERIOD_MS, easing = LinearEasing)
        ),
        label = "welcome_wave_phase"
    )

    Canvas(
        modifier = modifier.size(width = waveWidth, height = waveHeight)
    ) {
        val barSlot = size.width / (barCount * 2 - 1)
        val barWidth = barSlot * BAR_WIDTH_RATIO
        for (i in 0 until barCount) {
            // Огибающая: крайние столбики ниже И тусклее (в моке — до ~88%
            // яркости края), центр выше и ярче — силуэт волны.
            val envelope = sin(PI * (i + 1) / (barCount + 1)).toFloat()
            // Считаем в Double (kotlin.math.sin), во Float — один раз на выходе.
            val oscillation = if (reduced) {
                REDUCED_LEVEL.toDouble()
            } else {
                0.30 + 0.44 * (0.5 + 0.5 * sin(phase * 2.0 * PI + i * PHASE_STEP.toDouble()))
            }
            val barHeight = (size.height * oscillation.toFloat() * (0.45f + 0.55f * envelope))
                .coerceIn(0f, size.height)
            drawRoundRect(
                color = color.copy(alpha = EDGE_ALPHA_BASE + EDGE_ALPHA_SPAN * envelope),
                topLeft = Offset(
                    x = i * 2f * barSlot + (barSlot - barWidth) / 2f,
                    y = (size.height - barHeight) / 2f
                ),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    }
}

private const val WIDTH_RATIO = 0.30f
private const val HEIGHT_RATIO = 0.40f
private const val BAR_WIDTH_RATIO = 0.40f
private const val WAVE_PERIOD_MS = 2_400
private const val PHASE_STEP = 0.9f
private const val REDUCED_LEVEL = 0.55f

/** Крайний столбик ≈ 0.88 яркости центрального (замер по рендеру). */
private const val EDGE_ALPHA_BASE = 0.72f
private const val EDGE_ALPHA_SPAN = 0.28f
