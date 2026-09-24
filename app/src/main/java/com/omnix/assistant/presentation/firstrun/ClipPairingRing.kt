package com.omnix.assistant.presentation.firstrun

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.state.ClipState

/**
 * Фаза кольца подключения Clip (HTML-мок «OMNIX — подключение Clip»,
 * 2026-09-24): декор убран, одно тонкое кольцо и много воздуха.
 */
enum class ClipRingPhase {
    /** Поиск: по кольцу бежит короткая дуга (22%), как индикатор загрузки iOS. */
    SEARCH,

    /** Не найден: дуга исчезает, в центре тихий серый знак «!». */
    LOST,

    /** Подключён: кольцо замыкается, в центре галочка. */
    FOUND
}

/**
 * Чистое отображение состояния Clip в фазу кольца. Подключение тоже «работа»
 * (дуга бежит); низкий заряд — всё ещё «подключён»; отказ и Bluetooth-off —
 * «не найден»: пользователь видит то, что может исправить.
 */
fun clipRingPhase(clip: ClipState): ClipRingPhase = when (clip) {
    ClipState.Searching, ClipState.Connecting -> ClipRingPhase.SEARCH
    ClipState.Connected, ClipState.BatteryLow -> ClipRingPhase.FOUND
    ClipState.Unknown,
    ClipState.BluetoothOff,
    ClipState.Disconnected,
    ClipState.ConnectionFailed -> ClipRingPhase.LOST
}

/**
 * Тонкое кольцо подключения из мока: трек (faint, 2dp) + дуга (ink, 2dp,
 * круглая кромка). Переходы — dasharray 0.6s cubic-bezier(.2,.8,.2,1) и
 * opacity 0.4s; поиск вращает дугу 1.4s linear от −90°. Знаки «!» (mute) и
 * галочка (ink) проявляются с задержкой 0.25s — «кольцо замкнулось, потом
 * галочка». Reduced motion останавливает вращение и переходы.
 */
@Composable
internal fun ClipPairingRing(
    phase: ClipRingPhase,
    modifier: Modifier = Modifier
) {
    val colors = OmnixTheme.colors
    val reduced = OmnixTheme.reducedMotion

    // CSS: stroke-dasharray 22/100 (search), 0/100 (lost), 100/0 (found).
    val dashFraction by animateFloatAsState(
        targetValue = when (phase) {
            ClipRingPhase.SEARCH -> 0.22f
            ClipRingPhase.LOST -> 0f
            ClipRingPhase.FOUND -> 1f
        },
        animationSpec = tween(
            durationMillis = if (reduced) 0 else 600,
            easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
        ),
        label = "clip_arc_dash"
    )
    // CSS: .s-lost .arc { opacity: 0 }.
    val arcAlpha by animateFloatAsState(
        targetValue = if (phase == ClipRingPhase.LOST) 0f else 1f,
        animationSpec = tween(durationMillis = if (reduced) 0 else 400),
        label = "clip_arc_alpha"
    )
    // CSS: @keyframes spin { to { rotate(270deg) } } от -90°, 1.4s linear —
    // полный оборот за цикл.
    val transition = rememberInfiniteTransition(label = "clip_arc_spin")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1_400, easing = LinearEasing)),
        label = "clip_arc_spin_angle"
    )
    val rotation = if (phase == ClipRingPhase.SEARCH && !reduced) spin else 0f

    // CSS: .mark { transition: opacity .35s .25s }.
    val markSpec = tween<Float>(
        durationMillis = if (reduced) 0 else 350,
        delayMillis = if (reduced) 0 else 250
    )
    val lostMarkAlpha by animateFloatAsState(
        targetValue = if (phase == ClipRingPhase.LOST) 1f else 0f,
        animationSpec = markSpec,
        label = "clip_lost_mark"
    )
    val okMarkAlpha by animateFloatAsState(
        targetValue = if (phase == ClipRingPhase.FOUND) 1f else 0f,
        animationSpec = markSpec,
        label = "clip_ok_mark"
    )

    Canvas(modifier = modifier.size(RING_SIZE)) {
        val strokePx = STROKE.toPx()
        val radius = (size.minDimension - strokePx) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        // Трек: полное кольцо faint.
        drawCircle(
            color = colors.border,
            radius = radius,
            center = center,
            style = Stroke(width = strokePx)
        )

        // Дуга: 22% окружности при поиске, 100% при успехе.
        if (arcAlpha > 0.01f && dashFraction > 0.001f) {
            rotate(degrees = rotation - 90f, pivot = center) {
                drawArc(
                    color = colors.textPrimary.copy(alpha = arcAlpha),
                    startAngle = 0f,
                    sweepAngle = dashFraction * 360f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
            }
        }

        // Знаки в центре вписаны в MARK_SIZE, нормализация по viewBox 40×40
        // (как SVG-пути мока).
        val w = MARK_SIZE.toPx()
        fun p(nx: Float, ny: Float) = Offset(
            center.x + (nx - 0.5f) * w,
            center.y + (ny - 0.5f) * w
        )

        // «!» — вертикальный штрих с точкой (path M20 10v14 M20 30v.5).
        if (lostMarkAlpha > 0.01f) {
            val color = colors.textSecondary.copy(alpha = lostMarkAlpha)
            drawLine(
                color = color,
                start = p(20f / 40f, 10f / 40f),
                end = p(20f / 40f, 24f / 40f),
                strokeWidth = MARK_STROKE.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(
                color = color,
                radius = MARK_STROKE.toPx() / 2f,
                center = p(20f / 40f, 30f / 40f)
            )
        }

        // Галочка (path M10 21l7 7 13-15).
        if (okMarkAlpha > 0.01f) {
            val color = colors.textPrimary.copy(alpha = okMarkAlpha)
            drawLine(
                color = color,
                start = p(10f / 40f, 21f / 40f),
                end = p(17f / 40f, 28f / 40f),
                strokeWidth = MARK_STROKE.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = color,
                start = p(17f / 40f, 28f / 40f),
                end = p(30f / 40f, 13f / 40f),
                strokeWidth = MARK_STROKE.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

private val RING_SIZE = 132.dp
private val STROKE = 2.dp
private val MARK_SIZE = 40.dp
private val MARK_STROKE = 2.dp
