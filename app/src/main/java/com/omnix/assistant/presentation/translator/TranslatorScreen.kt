package com.omnix.assistant.presentation.translator

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.OmnixBackIcon
import com.omnix.assistant.presentation.components.OmnixIconButton
import com.omnix.assistant.presentation.components.OmnixPrimaryButton
import com.omnix.assistant.presentation.components.OmnixSecondaryButton
import com.omnix.assistant.presentation.components.OmnixSwapIcon
import com.omnix.assistant.presentation.components.omnixPressScale
import com.omnix.assistant.presentation.core.AmplitudeRing
import com.omnix.assistant.presentation.design.OmnixTheme
import kotlinx.coroutines.delay

/**
 * Translation — a mode of OMNIX, not a separate app (§24, §43).
 *
 * The state model stays the Core's: the route feeds the real listening flag
 * and the live microphone level, and the transcript/translation pair flows
 * in exactly as before. The mock of 2026-09-25 changes the MARK, not the
 * model — the halo'd Core is replaced by the calm translation ring
 * (muted-blue outline, white waveform) the owner's mock asks for: closer to
 * a system icon, no cyan glow, no halo. Listening reads as the live
 * waveform; thinking reads as the settled result text.
 *
 * Composition (mock): a compact back chevron at the edge and the title
 * centred across the width (title2 — the app-wide title weight), the
 * language pair as one interactive pill (the Apple Translate reading), the
 * ring, the prompt — or the live transcript with the title2 translation —
 * and the full-width action. Two weight slots centre the block, so the
 * screen stops having a "hole" in the middle.
 */
@Composable
fun TranslatorScreen(
    active: Boolean,
    audioLevel: Float,
    sourceLanguage: String,
    targetLanguage: String,
    transcript: String,
    translation: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onStart: () -> Unit = {},
    onStop: () -> Unit = {},
    onSwap: () -> Unit = {}
) {
    val spacing = OmnixTheme.spacing
    val colors = OmnixTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = spacing.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(spacing.md))

        // iOS reading (mock 2026-09-25): the title is centred across the
        // full width, the compact back chevron stays at the edge.
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.omnix_translator_title),
                style = OmnixTheme.typography.title2,
                color = colors.textPrimary
            )
            OmnixIconButton(
                onClick = onBack,
                contentDescription = stringResource(R.string.omnix_nav_back),
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                OmnixBackIcon(color = colors.textSecondary)
            }
        }

        Spacer(Modifier.height(spacing.sm))

        LanguagePairPill(
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            onSwap = onSwap
        )

        Spacer(Modifier.weight(1f))

        TranslateRing(active = active, audioLevel = audioLevel)

        Spacer(Modifier.height(spacing.xl))

        // Live transcript above, translation below. The translation is the
        // primary text because it is the thing the user is waiting for.
        if (transcript.isNotBlank() || translation.isNotBlank()) {
            Text(
                text = transcript,
                style = OmnixTheme.typography.subheadline,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(spacing.xs))
            Text(
                text = translation,
                style = OmnixTheme.typography.title2,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                text = stringResource(R.string.omnix_translator_empty_body),
                style = OmnixTheme.typography.subheadline,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.weight(1f))

        if (active) {
            OmnixSecondaryButton(
                text = stringResource(R.string.omnix_translator_stop),
                onClick = onStop,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            OmnixPrimaryButton(
                text = stringResource(R.string.omnix_translator_start),
                onClick = onStart,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(spacing.xl))
    }
}

/**
 * The language pair as ONE interactive pill (mock 2026-09-25, the Apple
 * Translate reading): "Русский → English" with the swap glyph inside a
 * single control, instead of a loose caption plus a separate text button.
 * Its one action is swapping, so the whole pill is the button — and says
 * exactly that to the screen reader.
 */
@Composable
private fun LanguagePairPill(
    sourceLanguage: String,
    targetLanguage: String,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = OmnixTheme.spacing
    val colors = OmnixTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val swapLabel = stringResource(R.string.omnix_translator_swap)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(OmnixTheme.radius.pill))
            .background(colors.surfaceElevated)
            .omnixPressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onSwap
            )
            .clearAndSetSemantics {
                this[Role] = Role.Button
                this.contentDescription = swapLabel
            }
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(
                R.string.omnix_translator_direction,
                sourceLanguage,
                targetLanguage
            ),
            style = OmnixTheme.typography.headline,
            color = colors.textPrimary
        )
        OmnixSwapIcon(color = colors.textSecondary)
    }
}

/**
 * The translation ring (mock 2026-09-25): a muted-blue outline with a white
 * waveform inside — no halo, no glow; the calm system-icon reading the mock
 * asks for. At rest it is the mock's static glyph. While the microphone is
 * live the six bars follow the REAL level through the same [AmplitudeRing]
 * the Core's amplitude ticks use (§33 — never a timer), the newest sample on
 * the right; when listening ends they cross-fade back to the rest glyph.
 * Under reduced motion the rest glyph is always kept.
 *
 * Decorative for screen readers: the button and the prompt below carry the
 * state.
 */
@Composable
private fun TranslateRing(
    active: Boolean,
    audioLevel: Float,
    modifier: Modifier = Modifier,
    size: Dp = OmnixTheme.coreSizes.focus
) {
    val colors = OmnixTheme.colors
    val motion = OmnixTheme.motion
    val reduced = OmnixTheme.reducedMotion
    val live = active && !reduced

    val currentLevel by rememberUpdatedState(audioLevel)
    val history = remember { AmplitudeRing(capacity = WAVEFORM_BAR_COUNT) }
    LaunchedEffect(live) {
        if (live) {
            while (true) {
                history.push(currentLevel)
                delay(motion.amplitudeSampleMs.coerceAtLeast(10).toLong())
            }
        } else {
            history.reset()
        }
    }

    // 1 while the microphone is live, 0 at rest: how far the level bars
    // replace the rest glyph. Animated so state changes morph, not swap.
    val liveness by animateFloatAsState(
        targetValue = if (live) 1f else 0f,
        animationSpec = tween(motion.stateTransitionMs, easing = motion.standard),
        label = "translate_ring_liveness"
    )

    Canvas(
        modifier = modifier.size(size),
        onDraw = {
            val dim = this.size.minDimension
            val u = dim / WAVEFORM_BOX
            val center = Offset(dim / 2f, dim / 2f)

            // The muted outline: thin, continuous, unbroken.
            val ringStroke = dim * RING_STROKE_RATIO
            drawCircle(
                color = colors.accentRing,
                radius = (dim - ringStroke) / 2f,
                center = center,
                style = Stroke(width = ringStroke)
            )

            val barWidth = WAVEFORM_BAR_WIDTH_U * u
            for (i in 0 until WAVEFORM_BAR_COUNT) {
                val level = history.newestFirst(WAVEFORM_BAR_COUNT - 1 - i)
                val barHeight = waveformBarHeight(WAVEFORM_REST_U[i], level, liveness) * u
                val x = (WAVEFORM_BAR_FIRST_X_U + i * WAVEFORM_BAR_STEP_U) * u
                drawLine(
                    color = colors.textPrimary,
                    start = Offset(x, center.y - barHeight / 2f),
                    end = Offset(x, center.y + barHeight / 2f),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round
                )
            }
        }
    )
}

/**
 * One waveform bar's height in 24-box units: the rest glyph blended toward
 * the live level by [liveness]. Silence while listening holds the bar at 20
 * % of its rest height — the microphone is open, the user is quiet; nothing
 * is invented. Pure, JVM-tested.
 */
internal fun waveformBarHeight(restU: Float, level: Float, liveness: Float): Float {
    val liveU = restU * (0.20f + 0.80f * level.coerceIn(0f, 1f))
    val t = liveness.coerceIn(0f, 1f)
    return restU + (liveU - restU) * t
}

/** 24-unit design box for the waveform (the icon-set convention). */
private const val WAVEFORM_BOX = 24f

/** The outline's share of the diameter: a thin, calm stroke. */
private const val RING_STROKE_RATIO = 0.01f

/** The mock's waveform: six bars. */
private const val WAVEFORM_BAR_COUNT = 6

/** Bar width, in box units. */
private const val WAVEFORM_BAR_WIDTH_U = 0.8f

/** Centre-to-centre spacing, in box units. */
private const val WAVEFORM_BAR_STEP_U = 2.2f

/** The first bar's centre x, in box units (the row is centred on 12). */
private const val WAVEFORM_BAR_FIRST_X_U = 6.5f

/** The rest glyph: the mock's bar heights, in box units. */
private val WAVEFORM_REST_U = floatArrayOf(4.0f, 6.5f, 8.8f, 8.8f, 6.5f, 4.0f)
