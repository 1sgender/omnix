package com.omnix.assistant.presentation.localmodel

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
private const val RING_DRAW_MS = 1500L
private const val RING_HOLD_MS = 300L
private const val RING_FADE_OUT_MS = 200L
private const val RING_REST_MS = 450L
/** The self-drawing arc is a static 3/4 ring when motion is reduced. */
private const val RING_STATIC_TRIM = 0.75f

/**
 * One cycle, ~2.4 s: ~1.5 s of drawing (ease-in-out, compass-like), a
 * closed moment, then a soft fade into the still ground circle while the
 * arc resets — the new cycle starts from the same still ground, so the
 * loop never reads as a bouncing restart.
 */
@Composable
private fun SelfDrawingRing() {
    val colors = OmnixTheme.colors
    val reducedMotion = OmnixTheme.reducedMotion
    // Start empty: the first frame is the still ground circle, then the
    // loop (or the reduced-motion snap) takes over without any pop.
    val trim = remember { Animatable(0f) }
    val arcAlpha = remember { Animatable(1f) }

    LaunchedEffect(reducedMotion) {
        if (reducedMotion) {
            trim.snapTo(RING_STATIC_TRIM)
            arcAlpha.snapTo(1f)
            return@LaunchedEffect
        }
        while (true) {
            trim.snapTo(0f)
            arcAlpha.snapTo(1f)
            // Draw itself around: slow start, slow close.
            trim.animateTo(1f, tween(RING_DRAW_MS.toInt(), easing = FastOutSlowInEasing))
            // The closed moment.
            delay(RING_HOLD_MS)
            // Soften into the ground ring, reset while invisible...
            arcAlpha.animateTo(0f, tween(RING_FADE_OUT_MS.toInt(), easing = LinearEasing))
            trim.snapTo(0f)
            // ...then a still beat before the next cycle rises.
            delay(RING_REST_MS)
        }
    }

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

        if (trim.value > 0.002f && arcAlpha.value > 0.004f) {
            drawArc(
                color = colors.textPrimary.copy(alpha = colors.textPrimary.alpha * arcAlpha.value),
                startAngle = -90f,
                sweepAngle = 360f * trim.value,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Butt),
                topLeft = topLeft,
                size = Size(radius * 2f, radius * 2f)
            )
        }
    }
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

    // A truthful, self-contained statement of the state this screen covers
    // (the model is being loaded into memory - not being downloaded; in the
    // ru locale the download state already reads "Загружается… N%").
    Text(
        text = stringResource(R.string.omnix_local_model_loading_screen),
        style = OmnixTheme.typography.caption,
        color = OmnixTheme.colors.textSecondary,
        modifier = Modifier.alpha(alpha)
    )
}
