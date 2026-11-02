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
 * Знак логотипа без надписи: кольцо «O», рассечённое горизонтальной щелью,
 * верхняя половина сдвинута влево, нижняя вправо («трафарет»). Тот же знак,
 * что в иконке и на заставке (drawable/ic_launcher_foreground.xml).
 * Геометрия в сетке 54×60: радиус 19.5, толщина 9, концы дуг лежат на 3
 * выше/ниже центра каждой половины, половины разнесены по горизонтали на 6.
 * Цвет по умолчанию — основной текст темы, поэтому знак следует светлой/тёмной теме.
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
        val unit = minOf(size.width / GRID_WIDTH, size.height / GRID_HEIGHT)
        val radius = RING_RADIUS * unit
        val arcSize = Size(width = radius * 2f, height = radius * 2f)
        val stroke = Stroke(width = RING_STROKE * unit)
        val centerX = size.width * 0.5f
        val centerY = size.height * 0.5f

        // Верхняя половина: дуга через верх, сдвинута влево.
        drawArc(
            color = color,
            startAngle = TOP_START,
            sweepAngle = HALF_SWEEP,
            useCenter = false,
            topLeft = Offset(
                x = centerX - SHIFT_X * unit - radius,
                y = centerY - SHIFT_Y * unit - radius
            ),
            size = arcSize,
            style = stroke
        )
        // Нижняя половина: дуга через низ, сдвинута вправо.
        drawArc(
            color = color,
            startAngle = BOTTOM_START,
            sweepAngle = HALF_SWEEP,
            useCenter = false,
            topLeft = Offset(
                x = centerX + SHIFT_X * unit - radius,
                y = centerY + SHIFT_Y * unit - radius
            ),
            size = arcSize,
            style = stroke
        )
    }
}

private val MARK_WIDTH = 28.dp
private val MARK_HEIGHT = 22.dp

private const val GRID_WIDTH = 54f
private const val GRID_HEIGHT = 60f
private const val RING_RADIUS = 19.5f
private const val RING_STROKE = 9f
private const val SHIFT_X = 3f
private const val SHIFT_Y = 6f

// Концы дуг отстоят на 3 от центра половины: asin(3 / 19.5) ≈ 8.85°.
private const val END_TILT = 8.85f
private const val HALF_SWEEP = 180f + 2f * END_TILT
private const val TOP_START = 180f - END_TILT
private const val BOTTOM_START = -END_TILT
