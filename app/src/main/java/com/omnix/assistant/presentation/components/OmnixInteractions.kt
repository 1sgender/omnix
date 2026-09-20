package com.omnix.assistant.presentation.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import com.omnix.assistant.presentation.design.LocalReducedMotion

/**
 * Apple-style press feedback (§29: response, not decor).
 *
 * Content settles to `pressedScale` on touch-down and springs back with a
 * small overshoot on release — the "fluid" iOS button feel. Collapses to a
 * no-op under reduced motion.
 */
fun Modifier.omnixPressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.97f
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduced) pressedScale else 1f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMedium,
            dampingRatio = Spring.DampingRatioLowBouncy
        ),
        label = "omnixPressScale"
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
