package com.omnix.assistant.presentation.firstrun

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.omnix.assistant.R
import com.omnix.assistant.presentation.design.OmnixTheme

/**
 * Маленький знак логотипа без надписи: кольцо «O», разрезанное на две
 * половины-чаши (левая сдвинута вверх, правая вниз) — образ двух наушников.
 * Геометрия задана в сетке 34×26: центр (17,13), радиус 8, толщина 3.4,
 * левая половина смещена на (−1.1, −1.6), правая на (+1.1, +1.6).
 * Знак доступен скринридеру как «OMNIX» (role="img", aria-label="OMNIX").
 */
@Composable
internal fun OmnixRingMark(
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = OmnixTheme.colors.textPrimary,
    // Разные экраны задают разные размеры знака: клип-флоу 28×22, активация 26×20.
    width: Dp = MARK_WIDTH,
    height: Dp = MARK_HEIGHT
) {
    val markLabel = stringResource(R.string.omnix_wordmark)
    Canvas(
        modifier = modifier
            .size(width = width, height = height)
            .semantics { contentDescription = markLabel }
    ) {
        val unit = size.width / GRID_WIDTH
        val radius = RING_RADIUS * unit
        val cupSize = Size(width = radius * 2f, height = radius * 2f)
        val stroke = Stroke(width = RING_STROKE * unit)
        val centerX = size.width * 0.5f
        val centerY = size.height * 0.5f

        // Левая чаша: от низа через левый край к верху, смещена вверх-влево.
        drawArc(
            color = color,
            startAngle = LEFT_CUP_START,
            sweepAngle = HALF_TURN,
            useCenter = false,
            topLeft = Offset(
                x = centerX - radius - CUP_SHIFT_X * unit,
                y = centerY - radius - CUP_SHIFT_Y * unit
            ),
            size = cupSize,
            style = stroke
        )
        // Правая чаша: от верха через правый край к низу, смещена вниз-вправо.
        drawArc(
            color = color,
            startAngle = RIGHT_CUP_START,
            sweepAngle = HALF_TURN,
            useCenter = false,
            topLeft = Offset(
                x = centerX - radius + CUP_SHIFT_X * unit,
                y = centerY - radius + CUP_SHIFT_Y * unit
            ),
            size = cupSize,
            style = stroke
        )
    }
}

private val MARK_WIDTH = 28.dp
private val MARK_HEIGHT = 22.dp

private const val GRID_WIDTH = 34f
private const val RING_RADIUS = 8f
private const val RING_STROKE = 3.4f
private const val CUP_SHIFT_X = 1.1f
private const val CUP_SHIFT_Y = 1.6f
private const val HALF_TURN = 180f
private const val LEFT_CUP_START = 90f
private const val RIGHT_CUP_START = -90f
