package com.omnix.assistant.presentation.core

/**
 * Layer visibility rules for the Core (design matrix, 2026-09-23).
 *
 * The Core carries exactly ONE primary ring signal at a time — form/motion,
 * progress arc, or amplitude — resolved by state, never by compromise. The
 * arc and the amplitude dashes share the ring geometry, so their state sets
 * are disjoint BY CONSTRUCTION:
 *
 *  - arc:        IDLE (a background OTA download) and EXECUTING (measurable
 *                task progress). THINKING never shows an arc: an LLM has no
 *                honest percentage, and a fake one is worse than the pulse.
 *  - amplitude:  LISTENING and SPEAKING (plus the fading tail in
 *                RECOGNIZING, implemented with the dash rendering).
 *
 * Badges ([CoreBadge]) are orthogonal to all layers and coexist with
 * anything. The centre carries the progress digit OR the terminal glyph —
 * never both (the states are disjoint, so the rule holds automatically).
 *
 * Pure object — covered by JVM tests.
 */
internal object CoreLayers {

    /**
     * True when the ring should render as a progress arc. A null progress
     * means "no measurable progress" — the ring keeps its state form.
     */
    fun showArc(state: CoreState, progress: Float?): Boolean =
        progress != null &&
            (state == CoreState.IDLE || state == CoreState.EXECUTING)

    /** Progress clamped to 0..1 (null counts as zero). */
    fun arcFraction(progress: Float?): Float = (progress ?: 0f).coerceIn(0f, 1f)
}
