package com.omnix.assistant.presentation.firstrun

/** Три видимые вехи онбординга (мок 2026-09-24). */
const val ONBOARDING_PROGRESS_TOTAL = 3

/**
 * The first-run sequence (§34, §67).
 *
 * ```
 * Welcome → Device detection → Clip pairing → Microphone → First command → Home
 * ```
 *
 * Each step asks for exactly one thing and explains it in human language. The
 * words "SDK", "service", "permission identifier", "STT" and "provider" do not
 * appear anywhere in this flow (§4, §35).
 */
enum class FirstRunStep {
    /** "Voice in your ear." Presence before instruction. */
    Welcome,

    /**
     * Looking for a Clip. This step is skippable: OMNIX is usable with the
     * phone alone, and blocking here would strand a user without hardware.
     */
    DeviceDetection,

    /** Confirming the Clip that was found and connecting to it. */
    ClipPairing,

    /** The microphone request, framed as What / Why / Action (§38, §50). */
    Microphone,

    /** One real spoken command, so the first success happens here (§34). */
    FirstCommand,

    /** Finished — Home takes over. */
    Complete;

    /** Сколько точек прогресса показывает онбординг (мок: три вехи). */
    val progressTotal: Int get() = ONBOARDING_PROGRESS_TOTAL

    val isFirst: Boolean get() = this == Welcome

    /**
     * Индекс видимой точки прогресса онбординга (мок 2026-09-24: «шаг 1 из
     * 3»). Три пользовательские вехи: знакомство → настройка (устройство и
     * микрофон) → первая команда. Complete — не точка: флоу уходит на Home.
     * null = точки скрыты.
     */
    val progressIndex: Int?
        get() = when (this) {
            Welcome -> 0
            DeviceDetection, ClipPairing, Microphone -> 1
            FirstCommand -> 2
            Complete -> null
        }

    /** The step that follows, given whether a Clip was actually found. */
    fun next(clipFound: Boolean): FirstRunStep = when (this) {
        Welcome -> DeviceDetection
        DeviceDetection -> if (clipFound) ClipPairing else Microphone
        ClipPairing -> Microphone
        Microphone -> FirstCommand
        FirstCommand -> Complete
        Complete -> Complete
    }

    /** The step before, used by the system back gesture (§46). */
    fun previous(): FirstRunStep? = when (this) {
        Welcome -> null
        DeviceDetection -> Welcome
        ClipPairing -> DeviceDetection
        Microphone -> DeviceDetection
        FirstCommand -> Microphone
        Complete -> null
    }
}
