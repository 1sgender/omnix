package com.omnix.assistant.presentation.localmodel

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.omnix.assistant.R
import com.omnix.assistant.presentation.design.OmnixTheme
import kotlinx.coroutines.delay

/**
 * The local model loading screen — the one moment in OMNIX where waiting
 * gets its own space.
 *
 * Motion is calm by design (owner decision, 2026-09-24): there is no
 * rotating "planetary" arc. A thin ring draws itself around — like a
 * compass finding north — stays closed for a moment, softens, and starts
 * the next cycle. The OMNIX letters enter one by one: a staggered fade-in
 * from bottom to top, never all at once. Quiet, premium, no spinner feel.
 *
 * Reduced motion: the ring is a static 3/4 arc; letters and the caption
 * appear immediately.
 *
 * Monochrome by the design matrix: graphite ground ring (stateIdle) and a
 * bright white arc (textPrimary). No hue, no second design language.
 */
@Composable
fun LocalModelLoadingScreen(modifier: Modifier = Modifier) {
    val spacing = OmnixTheme.spacing

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        SelfDrawingRing()
        Spacer(Modifier.height(spacing.xxl))
        StaggeredWordmark()
        Spacer(Modifier.height(spacing.md))
        LoadingCaption()
    }
}

// ----------------------------------------------------------- the ring

private val RING_SIZE = 112.dp
private val RING_STROKE = 2.dp
private val RING_CYCLE_MS = 2400
private const val RING_STATIC_TRIM = 0.75f

/**
 * One cycle: ~1.5 s of drawing (ease-in-out), a closed moment, then a soft
 * fade while the arc resets — the new cycle starts from the same still
 * ground, so the loop never reads as a bouncing restart.
 */
private val ringDrawSpec = infiniteRepeatable(
    animation = keyframes<Float> {
        durationMillis = RING_CYCLE_MS
        0f at 0
        1f at 1500 with FastOutSlowInEasing
        1f at 2050
        0f at 2051
        0f at RING_CYCLE_MS
    },
    repeatMode = RepeatMode.Restart
)

private val ringFadeSpec = infiniteRepeatable(
    animation = keyframes<Float> {
        durationMillis = RING_CYCLE_MS
        1f at 0
        1f at 1850
        0f at 2050 with LinearEasing
        1f at 2250 with LinearOutSlowInEasing
        1f at RING_CYCLE_MS
    },
    repeatMode = RepeatMode.Restart
)

@Composable
private fun SelfDrawingRing() {
    val colors = OmnixTheme.colors
    val (trim, arcAlpha) = ringProgress()

    Canvas(modifier = Modifier.size(RING_SIZE)) {
        val stroke = RING_STROKE.toPx()
        val radius = size.minDimension / 2f - stroke / 2f
        val center = Offset(size.minDimension / 2f, size.minDimension / 2f)
        val topLeft = Offset(stroke / 2f, stroke / 2f)

        // The faint full circle never moves: the bright arc resets into it.
        drawCircle(
            color = colors.stateIdle,
            center = center,
            radius = radius,
            style = Stroke(width = stroke)
        )

        if (trim > 0.002f && arcAlpha > 0.004f) {
            drawArc(
                color = colors.textPrimary.copy(alpha = colors.textPrimary.alpha * arcAlpha),
                startAngle = -90f,
                sweepAngle = 360f * trim,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Butt),
                topLeft = topLeft,
                size = Size(radius * 2f, radius * 2f)
            )
        }
    }
}

@Composable
private fun ringProgress(): Pair<Float, Float> {
    if (OmnixTheme.reducedMotion) return RING_STATIC_TRIM to 1f

    val infinite = rememberInfiniteTransition()
    val trim by infinite.animateFloat(0f, 1f, ringDrawSpec)
    val arcAlpha by infinite.animateFloat(1f, 0f, ringFadeSpec)
    return trim to arcAlpha
}

// ----------------------------------------------------------- the wordmark

private const val WORDMARK = "OMNIX"
private const val WORDMARK_START_DELAY_MS = 250L
private const val LETTER_STAGGER_MS = 80L
private val OMNIX_LETTER_GAP = 16.dp
private val LETTER_RISE = 16.dp

@Composable
private fun StaggeredWordmark() {
    val reducedMotion = OmnixTheme.reducedMotion
    var visibleLetters by remember {
        mutableIntStateOf(if (reducedMotion) WORDMARK.length else 0)
    }

    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        delay(WORDMARK_START_DELAY_MS)
        WORDMARK.indices.forEach { index ->
            visibleLetters = index + 1
            if (index < WORDMARK.lastIndex) delay(LETTER_STAGGER_MS)
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(OMNIX_LETTER_GAP)) {
        WORDMARK.forEachIndexed { index, char ->
            WordmarkLetter(char = char, visible = index < visibleLetters)
        }
    }
}

@Composable
private fun WordmarkLetter(char: Char, visible: Boolean) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(520, easing = LinearOutSlowInEasing),
        label = "wordmark-letter-alpha"
    )
    val offsetY by animateDpAsState(
        targetValue = if (visible) 0.dp else LETTER_RISE,
        animationSpec = tween(520, easing = LinearOutSlowInEasing),
        label = "wordmark-letter-rise"
    )

    Text(
        text = char.toString(),
        style = OmnixTheme.typography.splashWordmark,
        color = OmnixTheme.colors.textPrimary,
        modifier = Modifier
            .alpha(alpha)
            .offset(y = offsetY)
    )
}

// ----------------------------------------------------------- the caption

private const val CAPTION_DELAY_MS = 950L

@Composable
private fun LoadingCaption() {
    val reducedMotion = OmnixTheme.reducedMotion
    var visible by remember { mutableStateOf(reducedMotion) }

    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        delay(CAPTION_DELAY_MS)
        visible = true
    }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(400, easing = LinearOutSlowInEasing),
        label = "loading-caption-alpha"
    )

    Text(
        text = stringResource(R.string.omnix_local_model_loading),
        style = OmnixTheme.typography.caption,
        color = OmnixTheme.colors.textSecondary,
        modifier = Modifier.alpha(alpha)
    )
}
