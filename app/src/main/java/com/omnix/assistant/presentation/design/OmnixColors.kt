package com.omnix.assistant.presentation.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * OMNIX colour tokens — the single source of colour for the whole frontend.
 *
 * Specification §3–§5, §54. Rules enforced here:
 *  - the background is never pure `#000000`; it is a warm graphite material;
 *  - text is never pure white, it is warm "paper" ink in four steps;
 *  - one brand colour, signal lime (design v2, 2026-10-13): the Core's
 *    working states share it and are told apart by halo intensity and motion
 *    tempo — never by a hue per state. ERROR stays an unmistakable alarm red
 *    so "something is wrong" reads instantly (design decision, 2026-09-23);
 *  - screens must never declare `Color(0x...)` locally.
 */
@Immutable
data class OmnixColorScheme(
    // Surfaces
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,

    // Уровень заливки (iOS fill): заметно, но тише карточки — выключенные
    // кнопки, подчёркивание ячеек ввода (моки 2026-09-25: --faint
    // #2C2C2E/#E5E5EA).
    val surfaceFilled: Color,

    val border: Color,
    val scrim: Color,

    // Text
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,

    // Semantic Core / voice states
    val stateIdle: Color,
    val stateListening: Color,
    val stateRecognizing: Color,
    val stateThinking: Color,
    val stateExecuting: Color,
    val stateSpeaking: Color,
    val stateSuccess: Color,
    val stateError: Color,
    /**
     * Attention, not failure (mock 2026-09-25): the amber used when a live
     * status needs a look — Bluetooth off in the Me screen. ERROR stays the
     * only alarm red; warning is the only other hue in the monochrome field.
     */
    val stateWarning: Color,


    /**
     * Chat bubble surfaces (mock 2026-09-26): both sides of the dialogue
     * speak in bubbles — the user's in a light bubble on the right, OMNIX's
     * in a dark grey bubble on the left. The light scheme flips the user
     * bubble to system blue, exactly as the mock does.
     */
    val bubbleUser: Color,
    val onBubbleUser: Color,
    val bubbleAi: Color,
    val onBubbleAi: Color,

    /**
     * Failed round trips (mock 2026-09-26): a dim red-tinted bubble ground.
     * The ink stays the existing `stateError` alarm red — the background is
     * what makes it read as "something is off" without shouting.
     */
    val errorBubble: Color,

    // Interactive
    val actionPrimary: Color,
    val onActionPrimary: Color,
    val actionSecondaryBorder: Color,

    // Текстовые ссылки — системный синий, темнее на светлой схеме
    // (мок «подключение Clip», пересмотр 2026-09-25: #0A84FF/#0071E3).
    val actionLink: Color,

    // Brand accent (onboarding welcome, mock 2026-09-24)
    val accentBrand: Color,
    val accentBrandSoft: Color,

    /** True for the dimmed night variant (§54). */
    val isNight: Boolean
)

// Design v2 (2026-10-13): warm "paper" ink on a warm graphite ground. A cold
// blue-grey reads as a generic AI app; the warm pair reads as a device.
private val TextPrimaryToken = Color(0xFFF2EEE6)
private val TextSecondaryToken = Color(0xFF9C968C)
private val TextTertiaryToken = Color(0xFF857F76)
private val TextDisabledToken = Color(0x47F2EEE6)     // 0.28

/**
 * The OMNIX signal lime (design v2, 2026-10-13). It is the single brand
 * colour and is used sparingly: the Core while it works, the primary action,
 * links, switches that are on and the active tab — never as decoration.
 */
private val SignalLime = Color(0xFFC8FF4D)
private val SignalLimeSoft = Color(0xFFE3FFA6)
private val WarmGraphite = Color(0xFF0F0E0C)

/** Default dark scheme — the primary OMNIX appearance (§3). */
val OmnixDarkColors = OmnixColorScheme(
    // Warm graphite, never pure black: the ground is a material, and its warm
    // cast is what separates OMNIX from cold blue-grey assistant apps.
    background = WarmGraphite,
    surface = Color(0xFF1A1815),
    surfaceElevated = Color(0xFF25221E),
    surfaceFilled = Color(0xFF2E2B26),
    border = Color(0x0DFFFFFF),                        // rgba(255,255,255,0.05)
    scrim = Color(0xB3000000),

    textPrimary = TextPrimaryToken,
    textSecondary = TextSecondaryToken,
    textTertiary = TextTertiaryToken,
    textDisabled = TextDisabledToken,

    // Idle is a dim warm-grey presence; every working state lights the same
    // signal lime. The states stay told apart by halo intensity and the tempo
    // of their motion (see CoreMotion), not by a hue per state. ERROR keeps
    // the alarm red, the only other colour in the field.
    stateIdle = TextSecondaryToken,
    stateListening = SignalLime,
    stateRecognizing = SignalLime,
    stateThinking = SignalLime,
    stateExecuting = SignalLime,
    stateSpeaking = SignalLime,
    stateSuccess = SignalLime,
    stateError = Color(0xFFFF5A3C),
    stateWarning = Color(0xFFF5C451),


    // The user's bubble is paper on graphite, OMNIX's a raised surface.
    bubbleUser = TextPrimaryToken,
    onBubbleUser = WarmGraphite,
    bubbleAi = Color(0xFF1A1815),
    onBubbleAi = TextPrimaryToken,
    errorBubble = Color(0xFF2A1612),

    // The primary action is lime with graphite ink: the one place the brand
    // colour is a fill rather than a line.
    actionPrimary = SignalLime,
    onActionPrimary = WarmGraphite,
    actionSecondaryBorder = Color(0x2EFFFFFF),          // 0.18

    actionLink = SignalLime,

    accentBrand = SignalLime,
    accentBrandSoft = SignalLimeSoft,

    isNight = false
)

/**
 * Night scheme (§54): minimum brightness, low contrast, no bright surfaces.
 * Same tokens, dimmer values — never a second design language.
 */
val OmnixNightColors = OmnixDarkColors.copy(
    background = Color(0xFF0A0908),
    surface = Color(0xFF13110F),
    surfaceElevated = Color(0xFF1C1A17),
    surfaceFilled = Color(0xFF24211D),
    border = Color(0x0AFFFFFF),

    textPrimary = Color(0xD6F2EEE6),
    textSecondary = Color(0xFF857F76),
    textTertiary = Color(0xFF6E6961),
    textDisabled = Color(0x33F2EEE6),

    // The same lime, dimmed so it does not glare in the dark.
    stateIdle = Color(0xFF6E6961),
    stateListening = Color(0xFF9FCC3D),
    stateRecognizing = Color(0xFF9FCC3D),
    stateThinking = Color(0xFF9FCC3D),
    stateExecuting = Color(0xFF9FCC3D),
    stateSpeaking = Color(0xFF9FCC3D),
    stateSuccess = Color(0xFF9FCC3D),
    stateError = Color(0xFFD1615B),
    stateWarning = Color(0xFFD1A94E),


    // Same mock, dimmed for the night ground.
    bubbleUser = Color(0xFFCFCBC3),
    onBubbleUser = Color(0xFF0A0908),
    bubbleAi = Color(0xFF13110F),
    onBubbleAi = Color(0xD6F2EEE6),
    errorBubble = Color(0xFF1D100E),

    actionPrimary = Color(0xFF9FCC3D),
    onActionPrimary = Color(0xFF0A0908),
    actionLink = Color(0xFF9FCC3D),
    accentBrand = Color(0xFF9FCC3D),
    isNight = true
)

/**
 * Light scheme — the Apple-grade variant (rebuild v2).
 *
 * The product is voice-first and designed dark; light must still be a real
 * citizen, not an afterthought: grouped-table surfaces, 6 % black hairlines,
 * near-black text with the same four-step opacity ladder as dark.
 */
val OmnixLightColors = OmnixColorScheme(
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFF0F2F5),
    surfaceFilled = Color(0xFFE5E5EA),
    border = Color(0x0F000000),                        // 6 % black hairline
    scrim = Color(0x59000000),

    textPrimary = Color(0xF00C0D10),                   // 0.94
    textSecondary = Color(0x9E0C0D10),                 // 0.62
    textTertiary = Color(0x520C0D10),                  // 0.32
    textDisabled = Color(0x360C0D10),                  // 0.21

    // Light scheme inverts the ladder: graphite idle, near-black working
    // states — bright white would vanish on a light surface.
    stateIdle = Color(0xFF697077),
    stateListening = Color(0xFF20262B),
    stateRecognizing = Color(0xFF20262B),
    stateThinking = Color(0xFF20262B),
    stateExecuting = Color(0xFF20262B),
    stateSpeaking = Color(0xFF20262B),
    stateSuccess = Color(0xFF20262B),
    stateError = Color(0xFFD70015),
    stateWarning = Color(0xFF8A6400),


    // Light scheme: the user bubble is system blue, the mock's exact choice.
    bubbleUser = Color(0xFF0A84FF),
    onBubbleUser = Color(0xFFFFFFFF),
    bubbleAi = Color(0xFFE9E9EB),
    onBubbleAi = Color(0xF00C0D10),
    errorBubble = Color(0xFFFDECEA),

    actionPrimary = Color(0xFF0C0D10),
    onActionPrimary = Color(0xFFF7F8FA),
    actionSecondaryBorder = Color(0x1F000000),

    actionLink = Color(0xFF0071E3),

    // Брендовые константы: не зависят от схемы (ночь наследует тёмные).
    accentBrand = Color(0xFF3D7ECB),
    accentBrandSoft = Color(0xFF8FB8E6),

    isNight = false
)
