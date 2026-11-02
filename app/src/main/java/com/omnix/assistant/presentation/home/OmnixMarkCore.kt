package com.omnix.assistant.presentation.home

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What the mark is doing; each mode has its own motion, never its own colour. */
enum class MarkMode { LISTEN, THINK, SPEAK, IDLE }

/**
 * The home mark of design «Трафарет»: the stencil "O" on a disc, with one
 * motion per state, drawn every frame and cross-faded between states.
 *
 * - LISTEN: 96 thin rays around the disc, a radial equaliser driven by the
 *   real microphone [level]; the mark's halves part slightly on loud parts.
 * - THINK: a wave of brightness runs round a ring of 60 dots while the
 *   halves breathe apart and together along the slit.
 * - SPEAK: three thin liquid rings swell on syllables; the halves move like lips.
 * - IDLE: the mark dims, a faint ring barely breathes.
 *
 * Geometry is in the canvas's own 340-unit space (disc radius 92, ring 108),
 * scaled to the composable's size. With [reducedMotion] nothing moves.
 */
@Composable
fun OmnixMarkCore(
    mode: MarkMode,
    level: Float,
    markColor: Color,
    discColor: Color,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false
) {
    val currentMode by rememberUpdatedState(mode)
    val currentLevel by rememberUpdatedState(level)
    var time by remember { mutableFloatStateOf(0f) }
    val weights = remember { FloatArray(MarkMode.entries.size).also { it[mode.ordinal] = 1f } }
    var smoothed by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                time = (now - start) / NANOS_PER_SECOND
                for (m in MarkMode.entries) {
                    val target = if (m == currentMode) 1f else 0f
                    weights[m.ordinal] += (target - weights[m.ordinal]) * CROSSFADE
                }
                smoothed += (currentLevel.coerceIn(0f, 1f) - smoothed) * LEVEL_SMOOTHING
            }
        }
    }

    Canvas(modifier) {
        val t = time
        val unit = size.minDimension / CANVAS_UNITS
        val w = if (reducedMotion) {
            FloatArray(MarkMode.entries.size).also { it[mode.ordinal] = 1f }
        } else {
            weights
        }
        val lv = if (reducedMotion) 0f else smoothed
        translate(size.width / 2f, size.height / 2f) {
            drawListen(w[MarkMode.LISTEN.ordinal], lv, t, unit, markColor)
            drawThink(w[MarkMode.THINK.ordinal], t, unit, markColor)
            drawSpeak(w[MarkMode.SPEAK.ordinal], t, unit, markColor)
            drawIdle(w[MarkMode.IDLE.ordinal], t, unit, markColor)
            drawCircle(discColor, radius = DISC_RADIUS * unit, center = Offset.Zero)

            val speech = syllable(t)
            val sep = (1f + 3f * lv) * w[MarkMode.LISTEN.ordinal]
            val gap = (2f + 4f * (0.5f + 0.5f * sin(t * 2.2f))) * w[MarkMode.THINK.ordinal]
            val mouth = 5f * speech * w[MarkMode.SPEAK.ordinal]
            val dim = w[MarkMode.IDLE.ordinal]
            val ink = markColor.copy(alpha = markColor.alpha * (1f - IDLE_DIM * dim))
            drawMark(ink, unit, sep + gap * 0.4f, mouth + gap * 0.6f)
        }
    }
}

private fun syllable(t: Float): Float = max(0f, sin(t * 8.1f)) * (0.6f + 0.4f * sin(t * 1.7f))

private fun DrawScope.drawListen(weight: Float, level: Float, t: Float, unit: Float, color: Color) {
    if (weight < VISIBLE) return
    for (i in 0 until RAY_COUNT) {
        val angle = (i.toFloat() / RAY_COUNT) * 2f * PI.toFloat() - PI.toFloat() / 2f
        val f = (0.5f + 0.5f * sin(i * 0.31f + t * 2.6f)) * (0.55f + 0.45f * sin(i * 0.11f - t * 1.4f))
        val len = (3f + 30f * level * f) * weight
        val alpha = (0.18f + 0.82f * min(1f, len / 22f)) * weight
        val r0 = RING_RADIUS
        drawLine(
            color = color.copy(alpha = color.alpha * alpha),
            start = Offset(cos(angle) * r0 * unit, sin(angle) * r0 * unit),
            end = Offset(cos(angle) * (r0 + len) * unit, sin(angle) * (r0 + len) * unit),
            strokeWidth = 1.6f * unit,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawThink(weight: Float, t: Float, unit: Float, color: Color) {
    if (weight < VISIBLE) return
    val head = (t * 0.55f) % 1f
    for (i in 0 until DOT_COUNT) {
        val angle = (i.toFloat() / DOT_COUNT) * 2f * PI.toFloat() - PI.toFloat() / 2f
        val d = ((i.toFloat() / DOT_COUNT) - head + 1f) % 1f
        val b = max(0f, 1f - d * 4f)
        val alpha = (0.12f + 0.88f * b * b) * weight
        drawCircle(
            color = color.copy(alpha = color.alpha * alpha),
            radius = (1.6f + 1.6f * b) * unit,
            center = Offset(cos(angle) * DOT_RING * unit, sin(angle) * DOT_RING * unit)
        )
    }
}

private fun DrawScope.drawSpeak(weight: Float, t: Float, unit: Float, color: Color) {
    if (weight < VISIBLE) return
    val speech = syllable(t)
    val layers = floatArrayOf(0.95f, 0.45f, 0.2f)
    layers.forEachIndexed { index, layerAlpha ->
        val phase = index * 1.3f
        val path = Path()
        for (i in 0..RING_POINTS) {
            val th = (i.toFloat() / RING_POINTS) * 2f * PI.toFloat()
            val m = sin(3 * th + t * 2f + phase) * 0.5f +
                sin(5 * th - t * 3.1f + phase * 2f) * 0.3f +
                sin(7 * th + t * 4.3f) * 0.2f
            val r = (112f + (4f + 16f * speech) * m * weight + phase * 3f) * unit
            val x = cos(th) * r
            val y = sin(th) * r
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color.copy(alpha = color.alpha * layerAlpha * weight), style = Stroke(width = 1.5f * unit))
    }
}

private fun DrawScope.drawIdle(weight: Float, t: Float, unit: Float, color: Color) {
    if (weight < VISIBLE) return
    val alpha = (0.08f + 0.05f * sin(t * 0.8f)) * weight
    drawCircle(
        color = color.copy(alpha = color.alpha * alpha),
        radius = RING_RADIUS * unit,
        center = Offset.Zero,
        style = Stroke(width = unit)
    )
}

/**
 * The stencil mark in its 108-unit logo grid, centred: the top half shifted
 * up-left by [sep], the bottom down-right, both opened vertically by [mouth].
 */
private fun DrawScope.drawMark(color: Color, unit: Float, sep: Float, mouth: Float) {
    val stroke = Stroke(width = MARK_STROKE * unit, cap = StrokeCap.Butt)
    val arcSize = Size(MARK_RADIUS * 2f * unit, MARK_RADIUS * 2f * unit)
    translate(-sep * unit, (-sep * 1.2f - mouth) * unit) {
        drawArc(
            color = color,
            startAngle = 180f - END_TILT,
            sweepAngle = 180f + 2f * END_TILT,
            useCenter = false,
            topLeft = Offset((-3f - MARK_RADIUS) * unit, (-6f - MARK_RADIUS) * unit),
            size = arcSize,
            style = stroke
        )
    }
    translate(sep * unit, (sep * 1.2f + mouth) * unit) {
        drawArc(
            color = color,
            startAngle = -END_TILT,
            sweepAngle = 180f + 2f * END_TILT,
            useCenter = false,
            topLeft = Offset((3f - MARK_RADIUS) * unit, (6f - MARK_RADIUS) * unit),
            size = arcSize,
            style = stroke
        )
    }
}

private const val NANOS_PER_SECOND = 1_000_000_000f
private const val CANVAS_UNITS = 340f
private const val CROSSFADE = 0.07f
private const val LEVEL_SMOOTHING = 0.18f
private const val VISIBLE = 0.01f
private const val IDLE_DIM = 0.6f
private const val DISC_RADIUS = 92f
private const val RING_RADIUS = 108f
private const val DOT_RING = 112f
private const val RAY_COUNT = 96
private const val DOT_COUNT = 60
private const val RING_POINTS = 180
private const val MARK_RADIUS = 19.5f
private const val MARK_STROKE = 9f
private const val END_TILT = 8.85f
