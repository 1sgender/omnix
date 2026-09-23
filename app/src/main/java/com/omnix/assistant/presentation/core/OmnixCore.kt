package com.omnix.assistant.presentation.core

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnix.assistant.presentation.design.OmnixTheme
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * **OmnixCore** — brand, state indicator and interaction feedback in one
 * geometry (§7, §10, §91).
 *
 * One component. Eight states. Four scales. The Core transforms between
 * states; it is never unmounted and remounted, and there is never a separate
 * "listening core" or "thinking core" component.
 *
 * The visual is a soft halo around one ring. When OMNIX is ready, the ring is
 * continuous and gently breathes; its stable form reads as presence rather
 * than loading. The signature three breaks and directional motion appear only
 * while OMNIX is interpreting or working. The halo is what makes the Core
 * read as light rather than as a drawn outline.
 *
 * The palette is monochrome: every state is white or graphite, told apart by
 * halo brightness and the TEMPO of the motion — idle breathes slowly and dim,
 * thinking pulses visibly faster and brighter. Colour exists for exactly one
 * exception: an error lands as a sharp flash, a jerk of the shape and an
 * alarm red that is unmistakable against the monochrome field.
 *
 * The Core is **not a button** (§8). It exposes no click handling; a caller
 * that genuinely needs a tap target wraps it explicitly and must also provide
 * a non-visual affordance.
 *
 * Accessibility: the Core is decorative for screen readers by default — the
 * state is always announced by the accompanying label (§55). Pass
 * [contentDescription] only where no label exists (for example the navigation
 * bar item).
 *
 * @param state       current state, the single source of the visual form
 * @param size        rendering size; use `OmnixTheme.coreSizes.*` (§14)
 * @param audioLevel  normalised live amplitude 0..1, used only by
 *                    LISTENING and SPEAKING; must come from a real audio
 *                    source, never from a timer (§33)
 * @param intensity   global damping of the visual weight, 0..1
 * @param progress    measurable task progress 0..1 (OTA download, command
 *                    execution). Only IDLE and EXECUTING may turn the ring
 *                    into an arc with a centred digit (CoreLayers); THINKING
 *                    never does — an LLM has no honest percentage
 * @param badge       orthogonal status badge docked onto the ring (cloud
 *                    processing, no connectivity). Coexists with every
 *                    layer; never changes the Core's shape
 */
@Composable
fun OmnixCore(
    state: CoreState,
    modifier: Modifier = Modifier,
    size: Dp = OmnixTheme.coreSizes.home,
    audioLevel: Float = 0f,
    intensity: Float = 1f,
    contentDescription: String? = null,
    progress: Float? = null,
    badge: CoreBadge? = null
) {
    val colors = OmnixTheme.colors
    val motion = OmnixTheme.motion
    val reduced = OmnixTheme.reducedMotion

    val targetColor = when (state) {
        CoreState.IDLE -> colors.stateIdle
        CoreState.LISTENING -> colors.stateListening
        CoreState.RECOGNIZING -> colors.stateRecognizing
        CoreState.THINKING -> colors.stateThinking
        CoreState.EXECUTING -> colors.stateExecuting
        CoreState.SPEAKING -> colors.stateSpeaking
        CoreState.SUCCESS -> colors.stateSuccess
        CoreState.ERROR -> colors.stateError
    }

    // The colour cross-fades; the object itself persists (§59).
    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(motion.colorTransitionMs, easing = motion.standard),
        label = "core_color"
    )

    val base = CoreMotion.baseShape(state)
    val drivers = CoreMotion.rememberDrivers(state, motion)

    // Brightness is a state signal now that the palette is monochrome: idle
    // is a dim presence, the working states glow brighter, terminal states
    // settle in between. Animated so a state change morphs, not swaps.
    val haloStrength by animateFloatAsState(
        targetValue = when (state) {
            CoreState.IDLE -> 0.55f
            CoreState.SUCCESS -> 0.85f
            CoreState.ERROR -> 0.90f
            else -> 1.0f
        },
        animationSpec = tween(motion.stateTransitionMs, easing = motion.standard),
        label = "core_halo"
    )

    // One-shot error emphasis: a sharp brightness flash plus a scale jerk of
    // the whole shape, decaying quickly. With reduced motion neither runs —
    // the alarm red, the closed ring and the glyph still carry the state.
    val errorFlash = remember { Animatable(0f) }
    LaunchedEffect(state) {
        if (state == CoreState.ERROR && !reduced && motion.errorFlashMs > 0) {
            errorFlash.snapTo(1f)
            errorFlash.animateTo(0f, tween(motion.errorFlashMs, easing = motion.gentle))
        } else {
            // Leaving ERROR mid-flash must not freeze the boost in place.
            errorFlash.animateTo(0f, tween(motion.stateTransitionMs, easing = motion.gentle))
        }
    }
    val flash = errorFlash.value

    // The flash pushes the stroke towards the theme's brightest ink so the
    // jerk reads on both dark and light surfaces.
    val flashColor = lerp(color, colors.textPrimary, flash * 0.4f)

    // Layer rules (design matrix 2026-09-23): the ring carries exactly one
    // primary signal. The progress arc is allowed only in IDLE (a background
    // OTA download) and EXECUTING, and while active it crossfades the state
    // ring out — the object still morphs, nothing is remounted.
    val arcActive = CoreLayers.showArc(state, progress)
    val arcAlpha by animateFloatAsState(
        targetValue = if (arcActive) 1f else 0f,
        animationSpec = tween(motion.stateTransitionMs, easing = motion.standard),
        label = "core_arc"
    )
    val arcFraction by animateFloatAsState(
        targetValue = CoreLayers.arcFraction(progress),
        animationSpec = tween(motion.arcStepMs.coerceAtLeast(1), easing = motion.standard),
        label = "core_arc_fraction"
    )

    // Every scalar is animated, so a state change is a transformation of one
    // object rather than a swap between two.
    val stroke by animateFloatAsState(
        targetValue = base.stroke,
        animationSpec = tween(motion.stateTransitionMs, easing = motion.standard),
        label = "core_stroke"
    )
    val pressure by animateFloatAsState(
        targetValue = base.pressure,
        animationSpec = tween(motion.stateTransitionMs, easing = motion.standard),
        label = "core_pressure"
    )
    val h1 by animateFloatAsState(
        targetValue = base.h1,
        animationSpec = tween(motion.stateTransitionMs, easing = motion.standard),
        label = "core_h1"
    )
    val h2 by animateFloatAsState(
        targetValue = base.h2,
        animationSpec = tween(motion.stateTransitionMs, easing = motion.standard),
        label = "core_h2"
    )
    val opacity by animateFloatAsState(
        targetValue = base.opacity * intensity.coerceIn(0f, 1f),
        animationSpec = tween(motion.stateTransitionMs, easing = motion.standard),
        label = "core_opacity"
    )

    /**
     * How closed the ring is: 0 keeps the three signature breaks, 1 seals it
     * into a continuous circle. Only the terminal states seal it, and because
     * the value is animated the gaps visibly close rather than disappearing.
     */
    val closure by animateFloatAsState(
        targetValue = if (base.gaps.isEmpty()) 1f else 0f,
        animationSpec = tween(motion.stateTransitionMs, easing = motion.standard),
        label = "core_closure"
    )

    // Live amplitude, smoothed. Zero unless the state is audio reactive, so a
    // stale RMS value can never animate an idle Core.
    val reactiveTarget = if (state.isAudioReactive && !reduced) {
        audioLevel.coerceIn(0f, 1f) * if (state == CoreState.LISTENING) 0.030f else 0.022f
    } else {
        0f
    }
    val reactive by animateFloatAsState(
        targetValue = reactiveTarget,
        animationSpec = tween(
            durationMillis = if (reactiveTarget > 0f) {
                motion.audioAttackMs.coerceAtLeast(1)
            } else {
                motion.audioReleaseMs.coerceAtLeast(1)
            },
            easing = motion.gentle
        ),
        label = "core_reactive"
    )

    val glyphProgress by animateFloatAsState(
        targetValue = if (state.isTerminal) 1f else 0f,
        animationSpec = tween(motion.stateTransitionMs, easing = motion.standard),
        label = "core_glyph"
    )

    // Dots fade in and out rather than appearing abruptly, and stay hidden
    // entirely when motion is reduced — a rotating element is exactly what
    // that setting exists to suppress.
    val orbitAlpha by animateFloatAsState(
        targetValue = if (
            !reduced && (state == CoreState.RECOGNIZING || state == CoreState.THINKING)
        ) 1f else 0f,
        animationSpec = tween(motion.stateTransitionMs, easing = motion.standard),
        label = "core_orbit"
    )

    val innerSweep by animateFloatAsState(
        targetValue = CoreMotion.innerArcSweep(state),
        animationSpec = tween(motion.stateTransitionMs, easing = motion.standard),
        label = "core_inner"
    )

    // A ready Core breathes as emitted light, not as a loader. The ring's
    // movement stays almost imperceptible while the halo does most of the
    // breathing, so the idle state reads as calm presence. While thinking,
    // the same mechanism pulses ~4x faster: the TEMPO, not the colour, is
    // how "present" and "working" are told apart in a monochrome palette.
    // An error adds a one-shot jerk of the whole shape.
    val readyBreath = if (state == CoreState.IDLE) drivers.breathing else 0f
    val thinkingPulse = if (state == CoreState.THINKING) drivers.thinkingPulse else 0f
    val breathScale = 1f +
        motion.breathingAmplitude * readyBreath +
        motion.thinkingPulseAmplitude * thinkingPulse +
        motion.errorJerkAmplitude * flash
    val haloOpacity = (
        haloStrength +
            readyBreath * 0.20f +
            thinkingPulse * 0.15f +
            flash * 0.90f
        ).coerceIn(0f, 1.6f)
    val haloScale = 1f + readyBreath * 0.08f + thinkingPulse * 0.05f + flash * 0.06f

    // The gaps narrow towards zero as the ring closes.
    val gaps = if (closure >= 0.999f) {
        emptyList()
    } else {
        CoreGeometry.DEFAULT_GAPS.map { it.copy(sweep = it.sweep * (1f - closure)) }
    }

    val shape = base.copy(
        gaps = gaps,
        h1 = h1,
        h2 = h2,
        stroke = stroke,
        pressure = pressure,
        reactive = reactive,
        phase = drivers.recognizingDrift,
        scale = breathScale,
        opacity = opacity
    )

    val innerRotation = when (state) {
        CoreState.THINKING -> drivers.thinkingAngle
        CoreState.EXECUTING -> drivers.executingAngle
        CoreState.RECOGNIZING -> drivers.recognizingDrift
        else -> 0f
    }

    val glyph = CoreMotion.glyphOf(state)
    val semantics = Modifier.clearAndSetSemantics {
        contentDescription?.let { this.contentDescription = it }
    }

    Box(
        modifier = modifier.size(size).then(semantics),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val sizePx = this.size.minDimension
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val baseRadius = sizePx * CoreGeometry.RADIUS_RATIO

            drawHalo(
                center = center,
                baseRadius = baseRadius * haloScale,
                color = flashColor,
                alpha = shape.opacity * haloOpacity
            )

            // While the progress-arc layer is active the state ring fades
            // out — the arc replaces it (one primary signal per ring).
            val ringFade = 1f - arcAlpha
            if (ringFade > 0.01f) {
                drawRing(shape, center, baseRadius, flashColor, shape.opacity * ringFade)
            }

            if (innerSweep > 0.01f && ringFade > 0.01f) {
                drawInnerArc(
                    center = center,
                    radius = baseRadius * 0.58f,
                    startAngle = innerRotation - 1.571f,
                    sweep = innerSweep,
                    color = flashColor,
                    widthPx = CoreGeometry.STROKE_RATIO * baseRadius * 0.45f,
                    alpha = shape.opacity * 0.45f * ringFade
                )
            }

            // The progress arc: a dim full-circle track plus a bright fill
            // sweeping from the top, clockwise (design matrix 2026-09-23).
            if (arcAlpha > 0.01f) {
                drawProgressArc(
                    center = center,
                    baseRadius = baseRadius,
                    color = flashColor,
                    fraction = arcFraction,
                    alpha = shape.opacity * arcAlpha
                )
            }

            // Orbiting dots mark the two states where OMNIX is working on
            // something the user cannot see: interpreting speech, and
            // reasoning. They are the poster's substitute for a spinner —
            // three small points on the ring, not a rotating arc (§29).
            if (orbitAlpha > 0.01f && ringFade > 0.01f) {
                drawOrbitDots(
                    center = center,
                    radius = baseRadius,
                    angle = innerRotation,
                    color = flashColor,
                    dotRadius = CoreGeometry.STROKE_RATIO * baseRadius * 0.62f,
                    alpha = shape.opacity * orbitAlpha * ringFade
                )
            }

            if (glyphProgress > 0.01f) {
                when (glyph) {
                    CoreGlyph.CHECK -> drawCheckMark(
                        center = center,
                        unit = baseRadius * 0.42f,
                        color = flashColor,
                        widthPx = CoreGeometry.STROKE_RATIO * baseRadius * 0.85f,
                        progress = glyphProgress
                    )

                    CoreGlyph.ALERT -> drawAlertMark(
                        center = center,
                        unit = baseRadius * 0.46f,
                        color = flashColor,
                        widthPx = CoreGeometry.STROKE_RATIO * baseRadius * 0.85f,
                        progress = glyphProgress
                    )

                    CoreGlyph.NONE -> Unit
                }
            }
        }

        // The progress digit: centred, only while the arc is the active
        // layer. The centre carries the digit OR the terminal glyph — never
        // both (their states are disjoint by construction, CoreLayers).
        // Font size scales with the Core's size: the digit is part of the
        // drawing, the caller's contentDescription carries the semantics.
        if (arcActive) {
            Text(
                text = "${(arcFraction * 100).roundToInt()}%",
                color = colors.textPrimary,
                fontSize = (size.value * 0.22f).sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Orthogonal status badge, docked onto the ring at 45°. Coexists
        // with every layer; decorative for screen readers because the
        // Core-level contentDescription already narrates the state.
        badge?.let {
            CoreBadgeDock(
                badge = it,
                coreSize = size,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }
}

/**
 * The docked status badge (design matrix 2026-09-23): a small circle with a
 * thin outline icon, sitting ON the ring's top-right arc. Monochrome by
 * design — colour still belongs to ERROR alone.
 */
@Composable
private fun CoreBadgeDock(
    badge: CoreBadge,
    coreSize: Dp,
    modifier: Modifier = Modifier
) {
    val colors = OmnixTheme.colors
    val badgeSize = coreSize * 0.28f
    // Pulls the TopEnd-aligned badge onto the ring circumference at 45°
    // (ring radius = 0.36 of the core, RADIUS_RATIO).
    val dock = coreSize * 0.106f
    val icon = when (badge) {
        CoreBadge.CLOUD -> Icons.Outlined.Cloud
        CoreBadge.WIFI_OFF -> Icons.Outlined.WifiOff
    }
    Box(
        modifier = modifier
            .absoluteOffset(x = -dock, y = dock)
            .size(badgeSize)
            .background(colors.background, CircleShape)
            .border(1.dp, colors.actionSecondaryBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.textPrimary,
            modifier = Modifier.size(badgeSize * 0.62f)
        )
    }
}

/**
 * The halo. A radial gradient centred on the ring, fading to transparent just
 * outside it — this is what gives the Core its sense of emitted light (§11).
 *
 * It is drawn first so the ring always sits crisply on top of it.
 */
private fun DrawScope.drawHalo(
    center: Offset,
    baseRadius: Float,
    color: Color,
    alpha: Float
) {
    val haloRadius = baseRadius * 2.1f
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.00f to color.copy(alpha = 0f),
                0.42f to color.copy(alpha = 0.10f * alpha),
                0.52f to color.copy(alpha = 0.20f * alpha),
                0.62f to color.copy(alpha = 0.07f * alpha),
                1.00f to color.copy(alpha = 0f)
            ),
            center = center,
            radius = haloRadius
        ),
        radius = haloRadius,
        center = center
    )
}

/**
 * Draws the ring as a chain of pressure-varying segments.
 *
 * Segments rather than `drawCircle` are what allow the stroke pressure and the
 * organic radius to exist at all. Each drawn arc gets rounded caps at its two
 * ends, which is what makes the breaks look cut rather than erased.
 */
private fun DrawScope.drawRing(
    shape: CoreGeometry.Shape,
    center: Offset,
    baseRadius: Float,
    color: Color,
    alpha: Float
) {
    val segments = CoreGeometry.SEGMENTS
    for (i in 0 until segments) {
        val t0 = CoreGeometry.TAU * i / segments
        val t1 = CoreGeometry.TAU * (i + 1) / segments
        if (CoreGeometry.isHidden(shape, t0) || CoreGeometry.isHidden(shape, t1)) continue

        val p0 = CoreGeometry.pointAt(shape, center, baseRadius, t0)
        val p1 = CoreGeometry.pointAt(shape, center, baseRadius, t1)
        val width = CoreGeometry.strokeAt(shape, (t0 + t1) / 2f, baseRadius)

        drawLine(
            color = color,
            start = p0,
            end = p1,
            strokeWidth = width,
            cap = StrokeCap.Round,
            alpha = alpha
        )
    }
}

private fun DrawScope.drawInnerArc(
    center: Offset,
    radius: Float,
    startAngle: Float,
    sweep: Float,
    color: Color,
    widthPx: Float,
    alpha: Float
) {
    val steps = 48
    var previous = Offset(
        center.x + radius * cos(startAngle),
        center.y + radius * sin(startAngle)
    )
    for (i in 1..steps) {
        val t = startAngle + sweep * i / steps
        val point = Offset(center.x + radius * cos(t), center.y + radius * sin(t))
        drawLine(
            color = color,
            start = previous,
            end = point,
            strokeWidth = widthPx,
            cap = StrokeCap.Round,
            alpha = alpha
        )
        previous = point
    }
}

/** SUCCESS: a check mark, drawn stroke by stroke (§22). */
private fun DrawScope.drawCheckMark(
    center: Offset,
    unit: Float,
    color: Color,
    widthPx: Float,
    progress: Float
) {
    val a = Offset(center.x - unit, center.y + unit * 0.05f)
    val b = Offset(center.x - unit * 0.22f, center.y + unit * 0.68f)
    val c = Offset(center.x + unit, center.y - unit * 0.62f)

    val first = (progress / 0.45f).coerceIn(0f, 1f)
    val second = ((progress - 0.45f) / 0.55f).coerceIn(0f, 1f)

    drawLine(
        color = color,
        start = a,
        end = Offset(a.x + (b.x - a.x) * first, a.y + (b.y - a.y) * first),
        strokeWidth = widthPx,
        cap = StrokeCap.Round,
        alpha = progress
    )
    if (second > 0f) {
        drawLine(
            color = color,
            start = b,
            end = Offset(b.x + (c.x - b.x) * second, b.y + (c.y - b.y) * second),
            strokeWidth = widthPx,
            cap = StrokeCap.Round,
            alpha = progress
        )
    }
}

/**
 * ERROR: an exclamation mark — a stem and a dot.
 *
 * It is deliberately calm: no shake, no flash. The specification asks for
 * errors that are clear, not alarming (§18, §50).
 */
private fun DrawScope.drawAlertMark(
    center: Offset,
    unit: Float,
    color: Color,
    widthPx: Float,
    progress: Float
) {
    val top = Offset(center.x, center.y - unit * 0.70f)
    val bottom = Offset(center.x, center.y + unit * 0.18f)

    drawLine(
        color = color,
        start = top,
        end = Offset(top.x, top.y + (bottom.y - top.y) * progress),
        strokeWidth = widthPx,
        cap = StrokeCap.Round,
        alpha = progress
    )
    drawCircle(
        color = color,
        radius = widthPx * 0.55f,
        center = Offset(center.x, center.y + unit * 0.62f),
        alpha = progress
    )
}

/**
 * Three dots riding the ring, 120° apart.
 *
 * They sit exactly on the ring's radius so they read as part of the Core
 * rather than as orbiting satellites, and they are small enough that at the
 * navigation-bar size they simply disappear instead of turning into noise.
 */
private fun DrawScope.drawOrbitDots(
    center: Offset,
    radius: Float,
    angle: Float,
    color: Color,
    dotRadius: Float,
    alpha: Float
) {
    if (dotRadius <= 0.35f) return

    repeat(ORBIT_DOT_COUNT) { index ->
        val theta = angle + index * ORBIT_STEP_RADIANS
        // Trailing dots are dimmer, which is what gives the group a
        // direction of travel without any motion blur.
        val falloff = 1f - index * 0.28f
        drawCircle(
            color = color,
            radius = dotRadius,
            center = Offset(
                x = center.x + radius * cos(theta),
                y = center.y + radius * sin(theta)
            ),
            alpha = (alpha * falloff).coerceIn(0f, 1f)
        )
    }
}

private const val ORBIT_DOT_COUNT = 3

/** 120° in radians. */
private const val ORBIT_STEP_RADIANS = 2.0944f

/**
 * The progress arc: a dim full-circle track plus a bright fill sweeping
 * from the top clockwise (design matrix 2026-09-23). Drawn with the same
 * segment technique as the rest of the Core so the stroke caps and weight
 * match the ring exactly.
 */
private fun DrawScope.drawProgressArc(
    center: Offset,
    baseRadius: Float,
    color: Color,
    fraction: Float,
    alpha: Float
) {
    val steps = 96
    val stroke = CoreGeometry.STROKE_RATIO * baseRadius
    val top = -CoreGeometry.TAU / 4f

    // Track: the unfilled remainder reads as remaining work.
    var previous = arcPointAt(center, baseRadius, top)
    for (i in 1..steps) {
        val t = top + CoreGeometry.TAU * i / steps
        val point = arcPointAt(center, baseRadius, t)
        drawLine(
            color = color,
            start = previous,
            end = point,
            strokeWidth = stroke * 0.6f,
            cap = StrokeCap.Butt,
            alpha = alpha * 0.20f
        )
        previous = point
    }

    if (fraction <= 0f) return
    // Fill: bright, from the top, clockwise, with rounded ends.
    val fillSteps = (steps * fraction).toInt().coerceIn(1, steps)
    previous = arcPointAt(center, baseRadius, top)
    for (i in 1..fillSteps) {
        val t = top + CoreGeometry.TAU * fraction * i / fillSteps
        val point = arcPointAt(center, baseRadius, t)
        drawLine(
            color = color,
            start = previous,
            end = point,
            strokeWidth = stroke,
            cap = StrokeCap.Round,
            alpha = alpha
        )
        previous = point
    }
}

private fun arcPointAt(center: Offset, radius: Float, angle: Float): Offset =
    Offset(center.x + radius * cos(angle), center.y + radius * sin(angle))
