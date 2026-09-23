package com.omnix.assistant.presentation.core

/**
 * Rolling window of recent microphone levels for the Core's amplitude
 * ticks (design matrix, 2026-09-23).
 *
 * Eleven ticks are wrapped around the ring perimeter — newest sample at
 * the top — so the circle reads as the last ~0.55 s of real audio history
 * scrolling clockwise. Values are the RAW normalised 0..1 levels pushed
 * at a fixed cadence by the Core; nothing is synthesised (§33: real audio
 * source, never a timer-driven fake).
 *
 * Pure class — covered by JVM tests.
 */
internal class AmplitudeRing(
    private val capacity: Int = TICK_COUNT,
    private val samples: FloatArray = FloatArray(capacity)
) {

    private var cursor = 0
    private var filled = 0

    /** Adds the newest level, clamped to 0..1, evicting the oldest. */
    fun push(level: Float) {
        samples[cursor] = level.coerceIn(0f, 1f)
        cursor = (cursor + 1) % capacity
        if (filled < capacity) filled++
    }

    /**
     * The sample [stepsBack] steps into the past: 0 is the newest, growing
     * clockwise around the tick ring. Unfilled history reads as silence.
     */
    fun newestFirst(stepsBack: Int): Float {
        if (stepsBack < 0 || stepsBack >= capacity) return 0f
        if (stepsBack >= filled) return 0f
        val index = ((cursor - 1 - stepsBack) % capacity + capacity) % capacity
        return samples[index]
    }

    /** Clears the history (used when the audio-reactive mode re-enters). */
    fun reset() {
        samples.fill(0f)
        cursor = 0
        filled = 0
    }

    companion object {
        /** Ticks around the Core perimeter — matches the approved mockup. */
        const val TICK_COUNT = 11
    }
}
