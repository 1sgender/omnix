package com.omnix.assistant.presentation.firstrun

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.omnix.assistant.R
import com.omnix.assistant.presentation.design.OmnixTheme

/**
 * Маленький знак логотипа (мок подключения Clip, 2026-09-24): кольцо-эллипс
 * и наклонная орбита, без надписи. SVG мока: viewBox 34×26, эллипс
 * (17,13 r=11) stroke 3 + эллипс (17,14 rx=16 ry=4.6) stroke 1.4,
 * повёрнутый на −20°. Знак доступен скринридеру как «OMNIX» — как в моке
 * (role="img" aria-label="OMNIX").
 */
@Composable
internal fun OmnixRingMark(
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = OmnixTheme.colors.textPrimary,
    // Моки задают разные размеры знака: клип-флоу 28×22, активация 26×20.
    width: Dp = MARK_WIDTH,
    height: Dp = MARK_HEIGHT
) {
    val markLabel = stringResource(R.string.omnix_wordmark)
    Canvas(
        modifier = modifier
            .size(width = width, height = height)
            .semantics { contentDescription = markLabel }
    ) {
        val ink = color
        // Кольцо: центр (17,13), r=11 из 34×26.
        val ringCenter = Offset(x = size.width * (17f / 34f), y = size.height * (13f / 26f))
        val ringRadius = size.width * (11f / 34f)
        drawCircle(
            color = ink,
            radius = ringRadius,
            center = ringCenter,
            style = Stroke(width = size.width * (3f / 34f))
        )
        // Орбита: эллипс (17,14 rx=16 ry=4.6), поворот −20°.
        val orbitCenter = Offset(x = size.width * (17f / 34f), y = size.height * (14f / 26f))
        rotate(degrees = -20f, pivot = orbitCenter) {
            drawOval(
                color = ink,
                topLeft = Offset(orbitCenter.x - size.width * (16f / 34f), orbitCenter.y - size.height * (4.6f / 26f)),
                size = androidx.compose.ui.geometry.Size(
                    width = size.width * (32f / 34f),
                    height = size.height * (9.2f / 26f)
                ),
                style = Stroke(width = size.width * (1.4f / 34f))
            )
        }
    }
}

private val MARK_WIDTH = 28.dp
private val MARK_HEIGHT = 22.dp
