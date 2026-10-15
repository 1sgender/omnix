package com.omnix.assistant.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The OMNIX icon set (§8).
 *
 * Deliberately tiny: drawn as thin outlines matching the
 * reference poster. They are geometry rather than a font or a drawable set,
 * so they inherit the Core's line weight exactly and cannot drift from it.
 *
 * New icons are not added casually — every one of them competes with the Core
 * for attention.
 */
object OmnixIcons {

    /** Default optical size for navigation icons. */
    val NavSize: Dp = 22.dp
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
