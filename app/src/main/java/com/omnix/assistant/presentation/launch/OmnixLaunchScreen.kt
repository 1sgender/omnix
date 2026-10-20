package com.omnix.assistant.presentation.launch

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.omnix.assistant.R
import com.omnix.assistant.presentation.design.OmnixMonoLabelStyle
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.firstrun.OmnixRingMark
import kotlinx.coroutines.delay

/** What the launch screen is waiting for. */
sealed interface LaunchStatus {
    /** Licence check with the OMNIX server on a cold start. */
    data object CheckingLicence : LaunchStatus

    /** The local model is being loaded into memory. */
    data object LoadingModel : LaunchStatus

    /**
     * The server did not answer and there is no recent successful check
     * on the phone: nothing proves the licence either way.
     */
    data object ServerUnreachable : LaunchStatus
}

/**
 * The one launch screen of OMNIX: the mark inside a lime ring that draws
 * itself, the OMNIX letters entering one by one, and one honest line about
 * what is happening. The Android 12 system splash shows the same mark on
 * the same graphite, so the hand-over is seamless.
 *
 * Waiting says why after a moment, never a bare spinner. When the server
 * does not answer, the screen says so and offers to try again or to enter
 * an activation code instead of sending the user to activation silently.
 *
 * Reduced motion: a still 3/4 ring, letters and caption appear at once.
 */
@Composable
fun OmnixLaunchScreen(
    status: LaunchStatus,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    onEnterCode: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        SelfDrawingRing(still = status == LaunchStatus.ServerUnreachable)
        Spacer(Modifier.height(32.dp))
        StaggeredWordmark()
        Spacer(Modifier.height(16.dp))
        AnimatedContent(
            targetState = status,
            transitionSpec = {
                fadeIn(tween(OmnixTheme.motion.contentFadeMs)) togetherWith fadeOut(tween(OmnixTheme.motion.contentFadeMs))
            },
            label = "launch_status"
        ) { shown ->
            when (shown) {
                LaunchStatus.CheckingLicence -> DelayedCaption(stringResource(R.string.omnix_launch_checking))
                LaunchStatus.LoadingModel -> DelayedCaption(stringResource(R.string.omnix_local_model_loading_screen))
                LaunchStatus.ServerUnreachable -> Unreachable(onRetry = onRetry, onEnterCode = onEnterCode)
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

// ----------------------------------------------------------- the ring

private val RING_SIZE = 132.dp
private val RING_STROKE = 2.dp
private const val RING_DRAW_MS = 1500L
private const val RING_HOLD_MS = 300L
private const val RING_FADE_OUT_MS = 200L
private const val RING_REST_MS = 450L
private const val RING_STATIC_TRIM = 0.75f

/**
 * One cycle, ~2.4 s: the lime arc draws itself around (slow start, slow
 * close), holds closed for a moment, softens into the faint ground circle
 * and starts again from stillness, so the loop never reads as a restart.
 */
@Composable
private fun SelfDrawingRing(still: Boolean) {
    val colors = OmnixTheme.colors
    val frozen = OmnixTheme.reducedMotion || still
    val trim = remember { Animatable(0f) }
    val arcAlpha = remember { Animatable(1f) }

    LaunchedEffect(frozen) {
        if (frozen) {
            trim.snapTo(if (still) 1f else RING_STATIC_TRIM)
            arcAlpha.snapTo(if (still) 0f else 1f)
            return@LaunchedEffect
        }
        while (true) {
            trim.snapTo(0f)
            arcAlpha.snapTo(1f)
            trim.animateTo(1f, tween(RING_DRAW_MS.toInt(), easing = FastOutSlowInEasing))
            delay(RING_HOLD_MS)
            arcAlpha.animateTo(0f, tween(RING_FADE_OUT_MS.toInt(), easing = LinearEasing))
            trim.snapTo(0f)
            delay(RING_REST_MS)
        }
    }

    Box(modifier = Modifier.size(RING_SIZE), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = RING_STROKE.toPx()
            val radius = size.minDimension / 2f - stroke / 2f
            drawCircle(
                color = colors.textPrimary.copy(alpha = 0.10f),
                radius = radius,
                style = Stroke(width = stroke)
            )
            if (trim.value > 0.002f && arcAlpha.value > 0.004f) {
                drawArc(
                    color = colors.accentBrand.copy(alpha = arcAlpha.value),
                    startAngle = -90f,
                    sweepAngle = 360f * trim.value,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(radius * 2f, radius * 2f)
                )
            }
        }
        OmnixRingMark(color = colors.textPrimary, width = 56.dp, height = 43.dp, strokeUnits = 2.6f)
    }
}

// ----------------------------------------------------------- the wordmark

private const val WORDMARK = "OMNIX"
private const val WORDMARK_START_DELAY_MS = 250L
private const val LETTER_STAGGER_MS = 80L
private val LETTER_RISE = 12.dp

@Composable
private fun StaggeredWordmark() {
    val reducedMotion = OmnixTheme.reducedMotion
    var visibleLetters by remember { mutableIntStateOf(if (reducedMotion) WORDMARK.length else 0) }

    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        delay(WORDMARK_START_DELAY_MS)
        WORDMARK.indices.forEach { index ->
            visibleLetters = index + 1
            if (index < WORDMARK.lastIndex) delay(LETTER_STAGGER_MS)
        }
    }

    Row {
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
        style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = 0.32.em),
        color = OmnixTheme.colors.textPrimary,
        modifier = Modifier
            .alpha(alpha)
            .offset(y = offsetY)
    )
}

// ----------------------------------------------------------- the captions

private const val CAPTION_DELAY_MS = 1_200L

/** Says why OMNIX is waiting, after a beat — a fast start shows no text at all. */
@Composable
private fun DelayedCaption(text: String) {
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
        label = "launch-caption-alpha"
    )
    Text(
        text = text,
        style = OmnixMonoLabelStyle.copy(letterSpacing = 0.04.em),
        color = OmnixTheme.colors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.alpha(alpha)
    )
}

@Composable
private fun Unreachable(onRetry: () -> Unit, onEnterCode: () -> Unit) {
    val colors = OmnixTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.omnix_launch_unreachable_title),
            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 20.sp),
            color = colors.textPrimary,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.omnix_launch_unreachable_body),
            style = TextStyle(fontFamily = OmnixSans, fontSize = 15.sp, lineHeight = 22.sp),
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp)
        )
        Spacer(Modifier.height(14.dp))
        LaunchButton(text = stringResource(R.string.omnix_launch_retry), filled = true, onClick = onRetry)
        LaunchButton(text = stringResource(R.string.omnix_launch_enter_code), filled = false, onClick = onEnterCode)
    }
}

@Composable
private fun LaunchButton(text: String, filled: Boolean, onClick: () -> Unit) {
    val colors = OmnixTheme.colors
    Box(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (filled) colors.accentBrand else colors.background)
            .border(
                BorderStroke(1.dp, if (filled) colors.accentBrand else colors.textPrimary.copy(alpha = 0.12f)),
                RoundedCornerShape(16.dp)
            )
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 15.sp),
            color = if (filled) colors.background else colors.textPrimary
        )
    }
}
