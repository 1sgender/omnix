package com.omnix.assistant.presentation.core

/**
 * The eight visual states of the Core (§7, §84).
 *
 * There is exactly one Core component. These are transformations of the same
 * object, never separate components and never separate screens.
 */
enum class CoreState {
    IDLE,
    LISTENING,
    RECOGNIZING,
    THINKING,
    EXECUTING,
    SPEAKING,
    SUCCESS,
    ERROR;

    /** True while the Core reacts to live amplitude (§16, §20). */
    val isAudioReactive: Boolean
        get() = this == LISTENING || this == SPEAKING

    /** True while OMNIX is working and the user is expected to wait. */
    val isBusy: Boolean
        get() = this == THINKING || this == EXECUTING

    /** Terminal states that settle back into [IDLE] (§21, §22). */
    val isTerminal: Boolean
        get() = this == SUCCESS || this == ERROR
}

/**
 * The glyph shown inside the ring (§22).
 *
 * Only the terminal states carry one. Every other state is communicated by
 * the ring alone — adding icons elsewhere would turn the Core into a status
 * badge.
 */
enum class CoreGlyph {
    NONE,

    /** SUCCESS: a check mark. */
    CHECK,

    /** ERROR: an exclamation mark. */
    ALERT
}

/**
 * Orthogonal status badges docked onto the Core ring (design matrix
 * 2026-09-23).
 *
 * Badges never change the Core's shape or motion — they coexist with any
 * layer (e.g. amplitude dashes + CLOUD during speech recognition) and follow
 * the monochrome palette. Colour still belongs to ERROR alone.
 */
enum class CoreBadge {

    /** The answer is being processed in the cloud. */
    CLOUD,

    /** No network connectivity. */
    WIFI_OFF
}
