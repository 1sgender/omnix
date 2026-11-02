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

// Design «Трафарет» (2026-11-02): strict monochrome to match the stencil mark.
// No hue carries brand meaning any more — emphasis is inversion (paper on
// ink, ink on paper). Only ERROR keeps a red, so a failure still reads at a
// glance.
private val TextPrimaryToken = Color(0xFFF2F2F0)
private val TextSecondaryToken = Color(0xFFA3A3A3)
private val TextTertiaryToken = Color(0xFF7A7A7A)
private val TextDisabledToken = Color(0x47F2F2F0)     // 0.28

/** Paper: the one "accent" of the dark scheme — the primary fill and the lit mark. */
private val Paper = Color(0xFFF2F2F0)
private val Ink = Color(0xFF0A0A0A)

/** Default dark scheme — the primary OMNIX appearance (§3). */
val OmnixDarkColors = OmnixColorScheme(
    background = Ink,
    surface = Color(0xFF141414),
    surfaceElevated = Color(0xFF1C1C1C),
    surfaceFilled = Color(0xFF262626),
    border = Color(0x14FFFFFF),                        // rgba(255,255,255,0.08)
    scrim = Color(0xB3000000),

    textPrimary = TextPrimaryToken,
    textSecondary = TextSecondaryToken,
    textTertiary = TextTertiaryToken,
    textDisabled = TextDisabledToken,

    // Idle is a dim grey presence; every working state lights the same paper
    // white. The states are told apart by the mark's motion (see CoreMotion
    // and the home mark animations), never by a hue per state.
    stateIdle = TextTertiaryToken,
    stateListening = Paper,
    stateRecognizing = Paper,
    stateThinking = Paper,
    stateExecuting = Paper,
    stateSpeaking = Paper,
    stateSuccess = Paper,
    stateError = Color(0xFFFF6B5A),
    stateWarning = TextSecondaryToken,

    errorBubble = Color(0xFF1F1513),

    // The primary action is paper with ink text: inversion is the emphasis.
    actionPrimary = Paper,
    onActionPrimary = Ink,
    actionSecondaryBorder = Color(0x2EFFFFFF),          // 0.18

    actionLink = Paper,

    accentBrand = Paper,
    accentBrandSoft = TextSecondaryToken,

    isNight = false
)

/**
 * Night scheme (§54): minimum brightness, low contrast, no bright surfaces.
 * Same tokens, dimmer values — never a second design language.
 */
val OmnixNightColors = OmnixDarkColors.copy(
    background = Color(0xFF050505),
    surface = Color(0xFF0F0F0F),
    surfaceElevated = Color(0xFF161616),
    surfaceFilled = Color(0xFF1F1F1F),
    border = Color(0x0DFFFFFF),

    textPrimary = Color(0xD6F2F2F0),
    textSecondary = Color(0xFF858585),
    textTertiary = Color(0xFF6B6B6B),
    textDisabled = Color(0x33F2F2F0),

    stateIdle = Color(0xFF6B6B6B),
    stateListening = Color(0xFFCFCFCD),
    stateRecognizing = Color(0xFFCFCFCD),
    stateThinking = Color(0xFFCFCFCD),
    stateExecuting = Color(0xFFCFCFCD),
    stateSpeaking = Color(0xFFCFCFCD),
    stateSuccess = Color(0xFFCFCFCD),
    stateError = Color(0xFFD1615B),
    stateWarning = Color(0xFF858585),

    errorBubble = Color(0xFF17100F),

    actionPrimary = Color(0xFFCFCFCD),
    onActionPrimary = Color(0xFF050505),
    actionLink = Color(0xFFCFCFCD),
    accentBrand = Color(0xFFCFCFCD),
    accentBrandSoft = Color(0xFF858585),
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
    background = Color(0xFFF5F5F3),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFEDEDEA),
    surfaceFilled = Color(0xFFE4E4E1),
    border = Color(0x14000000),                        // 8 % black hairline
    scrim = Color(0x59000000),

    textPrimary = Color(0xFF0A0A0A),
    textSecondary = Color(0xFF5C5C5C),
    textTertiary = Color(0xFF707070),
    textDisabled = Color(0x360A0A0A),                  // 0.21

    // Light scheme inverts the ladder: grey idle, ink working states.
    stateIdle = Color(0xFF707070),
    stateListening = Color(0xFF0A0A0A),
    stateRecognizing = Color(0xFF0A0A0A),
    stateThinking = Color(0xFF0A0A0A),
    stateExecuting = Color(0xFF0A0A0A),
    stateSpeaking = Color(0xFF0A0A0A),
    stateSuccess = Color(0xFF0A0A0A),
    stateError = Color(0xFFC4291C),
    stateWarning = Color(0xFF5C5C5C),

    errorBubble = Color(0xFFF7ECEA),

    actionPrimary = Color(0xFF0A0A0A),
    onActionPrimary = Color(0xFFF5F5F3),
    actionSecondaryBorder = Color(0x26000000),

    actionLink = Color(0xFF0A0A0A),

    accentBrand = Color(0xFF0A0A0A),
    accentBrandSoft = Color(0xFF5C5C5C),

    isNight = false
)
