package com.omnix.assistant.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The OMNIX icon set (§8).
 *
 * Deliberately tiny: History and Me, drawn as thin outlines matching the
 * reference poster. They are geometry rather than a font or a drawable set,
 * so they inherit the Core's line weight exactly and cannot drift from it.
 *
 * New icons are not added casually — every one of them competes with the Core
 * for attention.
 */
object OmnixIcons {

    /** Default optical size for navigation icons. */
    val NavSize: Dp = 22.dp

    /** Default size for the settings grid glyphs (mock 2026-09-25). */
    val SettingSize: Dp = 15.dp
}

/** A clock: the History destination. */
@Composable
fun OmnixHistoryIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = OmnixIcons.NavSize
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.085f
        val r = s * 0.42f
        val c = Offset(this.size.width / 2f, this.size.height / 2f)

        drawCircle(color = color, radius = r, center = c, style = Stroke(width = w))

        // Hands at roughly 10:10, the conventional resting pose.
        drawLine(
            color = color,
            start = c,
            end = Offset(c.x, c.y - r * 0.52f),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = c,
            end = Offset(c.x + r * 0.40f, c.y),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
    }
}

/** A person: the Me destination. */
@Composable
fun OmnixMeIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = OmnixIcons.NavSize
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.085f
        val cx = this.size.width / 2f

        drawCircle(
            color = color,
            radius = s * 0.19f,
            center = Offset(cx, s * 0.30f),
            style = Stroke(width = w)
        )

        // Shoulders: a half-round, open at the bottom.
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(cx - s * 0.32f, s * 0.56f),
            size = Size(s * 0.64f, s * 0.56f),
            style = Stroke(width = w, cap = StrokeCap.Round)
        )
    }
}

/** A microphone: used by the permission step and the Chat dictation control. */
@Composable
fun OmnixMicIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = OmnixIcons.NavSize
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.085f
        val cx = this.size.width / 2f
        val capsuleW = s * 0.30f
        val capsuleTop = s * 0.14f
        val capsuleH = s * 0.44f

        drawRoundRectOutline(color, cx, capsuleTop, capsuleW, capsuleH, w)

        // The cradle arc plus the stem.
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(cx - s * 0.26f, s * 0.40f),
            size = Size(s * 0.52f, s * 0.40f),
            style = Stroke(width = w, cap = StrokeCap.Round)
        )
        drawLine(
            color = color,
            start = Offset(cx, s * 0.60f + s * 0.20f),
            end = Offset(cx, s * 0.88f),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawRoundRectOutline(
    color: Color,
    centerX: Float,
    top: Float,
    width: Float,
    height: Float,
    strokeWidth: Float
) {
    val radius = width / 2f
    val left = centerX - radius
    val bottom = top + height

    drawArc(
        color = color,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(left, top),
        size = Size(width, width),
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )
    drawArc(
        color = color,
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(left, bottom - width),
        size = Size(width, width),
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )
    drawLine(
        color = color,
        start = Offset(left, top + radius),
        end = Offset(left, bottom - radius),
        strokeWidth = strokeWidth
    )
    drawLine(
        color = color,
        start = Offset(left + width, top + radius),
        end = Offset(left + width, bottom - radius),
        strokeWidth = strokeWidth
    )
}

/** A left chevron: returning from a sub-screen. */
@Composable
fun OmnixBackIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = OmnixIcons.NavSize
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.085f
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val dx = s * 0.16f
        val dy = s * 0.22f

        drawLine(
            color = color,
            start = Offset(cx + dx, cy - dy),
            end = Offset(cx - dx, cy),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(cx - dx, cy),
            end = Offset(cx + dx, cy + dy),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
    }
}

/**
 * An upward arrow: the send affordance in the Chat composer. Drawn as a plain
 * stem with a chevron so it reads at 16 dp inside the filled circle.
 */
@Composable
fun OmnixArrowUpIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.11f
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val stem = s * 0.30f
        val head = s * 0.17f

        drawLine(
            color = color,
            start = Offset(cx, cy + stem),
            end = Offset(cx, cy - stem),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(cx - head, cy - stem + head),
            end = Offset(cx, cy - stem),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(cx, cy - stem),
            end = Offset(cx + head, cy - stem + head),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
    }
}

/**
 * A padlock: the privacy classification badge in Chat. The message is held on
 * the device — the closed shackle is the whole point, so the arc is closed.
 */
@Composable
fun OmnixLockIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = OmnixIcons.NavSize
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.085f
        val cx = this.size.width / 2f
        val bodyWidth = s * 0.46f
        val bodyHeight = s * 0.34f
        val bodyTop = s * 0.48f

        drawRoundRectOutline(
            color,
            cx,
            bodyTop,
            bodyWidth,
            bodyHeight,
            w
        )

        // The shackle: a semicircle resting on the body, not touching its
        // top edge — a hairline gap keeps the glyph readable at small sizes.
        val r = s * 0.125f
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(cx - r, bodyTop - s * 0.04f - r),
            size = Size(2f * r, 2f * r),
            style = Stroke(width = w, cap = StrokeCap.Round)
        )
    }
}

/**
 * A right chevron: the affordance of a row that opens another page. It is
 * opt-in per row — action rows and choice rows never show it, because a
 * chevron promises navigation.
 */
@Composable
fun OmnixChevronRightIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.085f
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val dx = s * 0.16f
        val dy = s * 0.22f

        drawLine(
            color = color,
            start = Offset(cx - dx, cy - dy),
            end = Offset(cx + dx, cy),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(cx + dx, cy),
            end = Offset(cx - dx, cy + dy),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
    }
}

/**
 * A checkmark: the selected option in a choice group. Mirrors the chevron's
 * weight so the two row affordances — choose and open — stay in one family.
 */
@Composable
fun OmnixCheckIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.09f
        drawLine(
            color = color,
            start = Offset(s * 0.28f, s * 0.53f),
            end = Offset(s * 0.44f, s * 0.70f),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(s * 0.44f, s * 0.70f),
            end = Offset(s * 0.74f, s * 0.33f),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
    }
}

// ---- Settings grid icons (mock 2026-09-25) ----
//
// The Me screen's rows carry a thin linear glyph in a grey tile, the iOS
// Settings reading. 24-unit design box, 1.8-unit stroke at the default
// 15 dp, round caps — the same line language as the navigation set.

@Composable
fun OmnixTranslateIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = OmnixIcons.SettingSize
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val w = this.size.minDimension * 0.12f
        fun p(v: Float) = v * u
        drawLine(color, Offset(p(5f), p(8f)), Offset(p(19f), p(16f)), w, StrokeCap.Round)
        drawLine(color, Offset(p(19f), p(8f)), Offset(p(5f), p(16f)), w, StrokeCap.Round)
    }
}

@Composable
fun OmnixChatLinesIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = OmnixIcons.SettingSize
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val w = this.size.minDimension * 0.12f
        fun p(v: Float) = v * u
        drawLine(color, Offset(p(4f), p(6f)), Offset(p(15f), p(6f)), w, StrokeCap.Round)
        drawLine(color, Offset(p(4f), p(12f)), Offset(p(20f), p(12f)), w, StrokeCap.Round)
        drawLine(color, Offset(p(4f), p(18f)), Offset(p(12f), p(18f)), w, StrokeCap.Round)
    }
}

/** Sound source: stem with two radiating lower arcs (the mock's voice glyph). */
@Composable
fun OmnixVoiceWaveIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = OmnixIcons.SettingSize
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val w = this.size.minDimension * 0.12f
        fun p(v: Float) = v * u
        val stroke = Stroke(width = w, cap = StrokeCap.Round)
        drawLine(color, Offset(p(12f), p(3f)), Offset(p(12f), p(15f)), w, StrokeCap.Round)
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(p(8f), p(5f)),
            size = Size(8f * u, 8f * u),
            style = stroke
        )
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(p(6f), p(6f)),
            size = Size(12f * u, 12f * u),
            style = stroke
        )
    }
}

@Composable
fun OmnixPrivacyIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = OmnixIcons.SettingSize
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val w = this.size.minDimension * 0.12f
        fun p(v: Float) = v * u
        val stroke = Stroke(width = w, cap = StrokeCap.Round)
        drawRoundRect(
            topLeft = Offset(p(4f), p(9f)),
            size = Size(16f * u, 10f * u),
            cornerRadius = CornerRadius(2f * u, 2f * u),
            color = color,
            style = stroke
        )
        drawLine(color, Offset(p(8f), p(9f)), Offset(p(8f), p(6f)), w, StrokeCap.Round)
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(p(8f), p(2f)),
            size = Size(8f * u, 8f * u),
            style = stroke
        )
        drawLine(color, Offset(p(16f), p(6f)), Offset(p(16f), p(9f)), w, StrokeCap.Round)
    }
}

@Composable
fun OmnixPhoneIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = OmnixIcons.SettingSize
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val w = this.size.minDimension * 0.12f
        fun p(v: Float) = v * u
        val stroke = Stroke(width = w, cap = StrokeCap.Round)
        drawRoundRect(
            topLeft = Offset(p(4f), p(3f)),
            size = Size(16f * u, 18f * u),
            cornerRadius = CornerRadius(2f * u, 2f * u),
            color = color,
            style = stroke
        )
        drawLine(color, Offset(p(9f), p(21f)), Offset(p(15f), p(21f)), w, StrokeCap.Round)
    }
}

/** Atom: a nucleus circle crossed by one tilted orbit. */
@Composable
fun OmnixAtomIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = OmnixIcons.SettingSize
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val w = this.size.minDimension * 0.12f
        fun p(v: Float) = v * u
        val stroke = Stroke(width = w, cap = StrokeCap.Round)
        val c = Offset(p(12f), p(12f))
        drawCircle(color = color, radius = 7f * u, center = c, style = stroke)
        rotate(-20f, pivot = c) {
            drawOval(
                color = color,
                topLeft = Offset(p(2f), p(9.2f)),
                size = Size(20f * u, 5.6f * u),
                style = stroke
            )
        }
    }
}

@Composable
fun OmnixGlobeIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = OmnixIcons.SettingSize
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val w = this.size.minDimension * 0.12f
        fun p(v: Float) = v * u
        val stroke = Stroke(width = w, cap = StrokeCap.Round)
        val c = Offset(p(12f), p(12f))
        drawCircle(color = color, radius = 9f * u, center = c, style = stroke)
        drawLine(color, Offset(p(3f), p(12f)), Offset(p(21f), p(12f)), w, StrokeCap.Round)
        val meridian = Path().apply {
            moveTo(p(12f), p(3f))
            cubicTo(p(14.5f), p(5.7f), p(14.5f), p(18.3f), p(12f), p(21f))
            moveTo(p(12f), p(3f))
            cubicTo(p(9.5f), p(5.7f), p(9.5f), p(18.3f), p(12f), p(21f))
        }
        drawPath(meridian, color = color, style = stroke)
    }
}

@Composable
fun OmnixBellIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = OmnixIcons.SettingSize
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val w = this.size.minDimension * 0.12f
        fun p(v: Float) = v * u
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Dome: left rim (180°) over the top to the right rim.
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(p(7f), p(4f)),
            size = Size(10f * u, 10f * u),
            style = stroke
        )
        val bell = Path().apply {
            moveTo(p(17f), p(9f))
            lineTo(p(17f), p(12f))
            lineTo(p(19f), p(16f))
            lineTo(p(5f), p(16f))
            lineTo(p(7f), p(12f))
            lineTo(p(7f), p(9f))
        }
        drawPath(bell, color = color, style = stroke)
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(p(10f), p(18f)),
            size = Size(4f * u, 4f * u),
            style = stroke
        )
    }
}

@Composable
fun OmnixSunIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = OmnixIcons.SettingSize
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val w = this.size.minDimension * 0.12f
        fun p(v: Float) = v * u
        val stroke = Stroke(width = w, cap = StrokeCap.Round)
        val c = Offset(p(12f), p(12f))
        drawCircle(color = color, radius = 3f * u, center = c, style = stroke)
        drawLine(color, Offset(p(12f), p(3f)), Offset(p(12f), p(6f)), w, StrokeCap.Round)
        drawLine(color, Offset(p(12f), p(18f)), Offset(p(12f), p(21f)), w, StrokeCap.Round)
        drawLine(color, Offset(p(3f), p(12f)), Offset(p(6f), p(12f)), w, StrokeCap.Round)
        drawLine(color, Offset(p(18f), p(12f)), Offset(p(21f), p(12f)), w, StrokeCap.Round)
        drawLine(color, Offset(p(6f), p(6f)), Offset(p(8f), p(8f)), w, StrokeCap.Round)
        drawLine(color, Offset(p(16f), p(16f)), Offset(p(18f), p(18f)), w, StrokeCap.Round)
        drawLine(color, Offset(p(6f), p(18f)), Offset(p(8f), p(16f)), w, StrokeCap.Round)
        drawLine(color, Offset(p(16f), p(8f)), Offset(p(18f), p(6f)), w, StrokeCap.Round)
    }
}

@Composable
fun OmnixInfoIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = OmnixIcons.SettingSize
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val w = this.size.minDimension * 0.12f
        fun p(v: Float) = v * u
        val stroke = Stroke(width = w, cap = StrokeCap.Round)
        val c = Offset(p(12f), p(12f))
        drawCircle(color = color, radius = 9f * u, center = c, style = stroke)
        drawLine(color, Offset(p(12f), p(8f)), Offset(p(12f), p(13f)), w, StrokeCap.Round)
        // The round cap of a hairline makes the dot.
        drawLine(color, Offset(p(12f), p(15.75f)), Offset(p(12f), p(16.25f)), w, StrokeCap.Round)
    }
}

@Composable
fun OmnixStarIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = OmnixIcons.SettingSize
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val w = this.size.minDimension * 0.12f
        fun p(v: Float) = v * u
        val star = Path().apply {
            moveTo(p(12f), p(3f))
            lineTo(p(14f), p(8f))
            lineTo(p(19f), p(8.8f))
            lineTo(p(15.4f), p(12.3f))
            lineTo(p(16.3f), p(17.5f))
            lineTo(p(12f), p(15f))
            lineTo(p(7.7f), p(17.5f))
            lineTo(p(8.6f), p(12.3f))
            lineTo(p(5f), p(8.8f))
            lineTo(p(10f), p(8f))
            close()
        }
        drawPath(
            star,
            color = color,
            style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun OmnixCodeIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = OmnixIcons.SettingSize
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val w = this.size.minDimension * 0.12f
        fun p(v: Float) = v * u
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val brackets = Path().apply {
            moveTo(p(8f), p(9f))
            lineTo(p(4f), p(12f))
            lineTo(p(8f), p(15f))
            moveTo(p(16f), p(9f))
            lineTo(p(20f), p(12f))
            lineTo(p(16f), p(15f))
        }
        drawPath(brackets, color = color, style = stroke)
        drawLine(color, Offset(p(13f), p(6f)), Offset(p(11f), p(18f)), w, StrokeCap.Round)
    }
}

/**
 * Two opposing horizontal arrows: swapping the language pair inside the
 * translator's pill (mock 2026-09-25, the iOS "swap" reading).
 */
@Composable
fun OmnixSwapIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val w = this.size.minDimension * 0.12f
        fun p(v: Float) = v * u
        // Top arrow, pointing right.
        drawLine(color, Offset(p(4.5f), p(8.5f)), Offset(p(19.5f), p(8.5f)), w, StrokeCap.Round)
        drawLine(color, Offset(p(15.5f), p(4.5f)), Offset(p(19.5f), p(8.5f)), w, StrokeCap.Round)
        drawLine(color, Offset(p(15.5f), p(12.5f)), Offset(p(19.5f), p(8.5f)), w, StrokeCap.Round)
        // Bottom arrow, pointing left.
        drawLine(color, Offset(p(19.5f), p(15.5f)), Offset(p(4.5f), p(15.5f)), w, StrokeCap.Round)
        drawLine(color, Offset(p(8.5f), p(11.5f)), Offset(p(4.5f), p(15.5f)), w, StrokeCap.Round)
        drawLine(color, Offset(p(8.5f), p(19.5f)), Offset(p(4.5f), p(15.5f)), w, StrokeCap.Round)
    }
}

/**
 * Warning glyph (mock 2026-09-26): the circle-and-exclamation mark that leads
 * an error bubble in the chat. 24-box units, standard icon stroke.
 */
@Composable
fun OmnixAlertIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val w = this.size.minDimension * 0.12f
        fun p(v: Float) = v * u
        val stroke = Stroke(width = w, cap = StrokeCap.Round)
        drawCircle(
            color = color,
            radius = 9f * u,
            center = Offset(p(12f), p(12f)),
            style = stroke
        )
        drawLine(
            color = color,
            start = Offset(p(12f), p(8f)),
            end = Offset(p(12f), p(13f)),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
        drawCircle(
            color = color,
            radius = w * 0.55f,
            center = Offset(p(12f), p(16f))
        )
    }
}
