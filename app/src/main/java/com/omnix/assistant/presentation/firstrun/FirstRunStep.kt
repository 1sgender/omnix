package com.omnix.assistant.presentation.firstrun

/** Три видимые вехи онбординга. */
const val ONBOARDING_PROGRESS_TOTAL = 3

/**
 * The first-run sequence (§34, §67).
 *
 * ```
 * Welcome → Access → Turn on → Home
 * ```
 *
 * Each step asks for exactly one thing. Access covers what the assistant
 * cannot work without (the microphone; notifications and Bluetooth come in
 * the same system prompt). Turn on is the real switch — the same logo as on
 * Home — and the first spoken request; the headphones are shown there as
 * they are, with a way to the system Bluetooth page.
 *
 * Earlier versions searched for and paired an "OMNIX Clip"; that hardware
 * does not exist yet, so those two steps are gone. Any Bluetooth headset
 * works.
 */
enum class FirstRunStep {
    /** Presence before instruction, plus "I have an activation code". */
    Welcome,

    /** The microphone request, framed as What / Why / Action (§38, §50). */
    Access,

    /** Turn the assistant on and say a first request. */
    TurnOn,

    /** Finished — Home takes over. */
    Complete;

    val progressTotal: Int get() = ONBOARDING_PROGRESS_TOTAL

    /** Index of the visible progress dot; Complete hides the dots. */
    val progressIndex: Int?
        get() = when (this) {
            Welcome -> 0
            Access -> 1
            TurnOn -> 2
            Complete -> null
        }

    fun next(): FirstRunStep = when (this) {
        Welcome -> Access
        Access -> TurnOn
        TurnOn -> Complete
        Complete -> Complete
    }

    /** The step before, used by the system back gesture (§46). */
    fun previous(): FirstRunStep? = when (this) {
        Welcome -> null
        Access -> Welcome
        TurnOn -> Access
        Complete -> null
    }
}
