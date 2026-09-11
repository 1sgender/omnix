package com.jarvis.assistant.presentation.state

/**
 * Holds a freshly produced [OmnixPhase.Success] on screen for a short fixed
 * window, then settles to [OmnixPhase.Idle].
 *
 * Why a latch: after TTS finishes, the orchestrator keeps deriving Success
 * from its parked signals for the whole follow-up window (up to 8 s), but the
 * confirmation must be visible for ~1 second — not for the whole window and
 * not zero frames. The latch opens a window on the first Success, keeps
 * returning it until [DISPLAY_MS] lapses, then sticks to Idle until the
 * underlying phase actually changes (sticky expiry: an expired Success never
 * resurrects from unchanged signals).
 *
 * Only Success is latched. Failures ([OmnixPhase.Error]) persist — an error
 * deserves to stay visible until the user moves on. Everything else passes
 * through untouched, which also resets the latch.
 *
 * Pure logic with an injectable [clock]: fully unit-testable without Android.
 */
class OmnixSuccessLatch(
    private val displayMs: Long = DISPLAY_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        /** How long Success stays visible before settling to Idle. */
        const val DISPLAY_MS: Long = 1_200L
    }

    private var activeKey: Int? = null
    private var expiresAtMs: Long = 0L
    private var lastWasSuccess = false

    fun resolve(base: OmnixPhase): OmnixPhase {
        if (base is OmnixPhase.Success) {
            val key = base.hashCode()
            if (!lastWasSuccess || key != activeKey) {
                activeKey = key
                expiresAtMs = clock() + displayMs
            }
            lastWasSuccess = true
            return if (clock() >= expiresAtMs) OmnixPhase.Idle else base
        }
        lastWasSuccess = false
        activeKey = null
        return base
    }
}
