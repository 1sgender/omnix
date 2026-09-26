package com.omnix.assistant.presentation.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * OMNIX colour tokens — the single source of colour for the whole frontend.
 *
 * Specification §3–§5, §54. Rules enforced here:
 *  - the background is never pure `#000000`; it is a felt material;
 *  - text is never pure white, it uses four opacity steps;
 *  - the Core's states are MONOCHROME: white/graphite only, told apart by
 *    brightness, halo intensity and motion tempo — never by hue. Colour is
 *    reserved for the single critical exception, ERROR, which stays an
 *    unmistakable alarm red so "something is wrong" reads instantly against
 *    the monochrome field (design decision, 2026-09-23);
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
     * Muted brand blue of the translation ring (mock 2026-09-25): the calm
     * outline the Перевод screen asks for — no halo, no glow, closer to a
     * system icon than to the Core's emitted light. Quieter than
     * `accentBrand`, which stays reserved for logo moments.
     */
    val accentRing: Color,

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

private val TextPrimaryToken = Color(0xF0FFFFFF)      // 0.94
private val TextSecondaryToken = Color(0x9EFFFFFF)    // 0.62
private val TextTertiaryToken = Color(0x61FFFFFF)     // 0.38
private val TextDisabledToken = Color(0x38FFFFFF)     // 0.22

/** Default dark scheme — the primary OMNIX appearance (§3). */
val OmnixDarkColors = OmnixColorScheme(
    // Sampled from the approved reference: the ground is a very dark blue,
    // not a neutral grey. The blue cast is what makes the coloured Core states
    // read as emitted light instead of as painted strokes.
    background = Color(0xFF01090F),
    surface = Color(0xFF0A1015),
    surfaceElevated = Color(0xFF121A20),
    surfaceFilled = Color(0xFF2C2C2E),
    border = Color(0x14FFFFFF),                        // rgba(255,255,255,0.08)
    scrim = Color(0xB3000000),

    textPrimary = TextPrimaryToken,
    textSecondary = TextSecondaryToken,
    textTertiary = TextTertiaryToken,
    textDisabled = TextDisabledToken,

    // Monochrome state ladder: idle is a dim graphite presence, every working
    // state glows the same bright white. The states are told apart by halo
    // intensity and the tempo of their motion (see CoreMotion), not by hue.
    // The former cyan/green/yellow/blue/purple per-state hues are gone by
    // design; ERROR keeps the alarm red as the only coloured state.
    stateIdle = Color(0xFF8F989F),
    stateListening = Color(0xFFEDF1F4),
    stateRecognizing = Color(0xFFEDF1F4),
    stateThinking = Color(0xFFEDF1F4),
    stateExecuting = Color(0xFFEDF1F4),
    stateSpeaking = Color(0xFFEDF1F4),
    stateSuccess = Color(0xFFEDF1F4),
    stateError = Color(0xFFFF5A3C),
    stateWarning = Color(0xFFF5C451),

    // Muted steel blue (mock 2026-09-25): the ring must not carry the logo's
    // saturated brand blue — it is chrome, not a brand moment.
    accentRing = Color(0xFF3E6C9F),

    // Bubbles sampled from the approved mock (2026-09-26).
    bubbleUser = Color(0xFFF5F5F7),
    onBubbleUser = Color(0xFF0A1118),
    bubbleAi = Color(0xFF1C1C1E),
    onBubbleAi = TextPrimaryToken,
    errorBubble = Color(0xFF241412),

    actionPrimary = TextPrimaryToken,
    onActionPrimary = Color(0xFF01090F),
    actionSecondaryBorder = Color(0x2EFFFFFF),          // 0.18

    actionLink = Color(0xFF0A84FF),

    // Синий в тон логотипа (мок онбординга 2026-09-24, калибровка по
    // повторному рендеру): единственный цветной момент вне монохрома
    // ядра и красного ERROR.
    accentBrand = Color(0xFF3D7ECB),
    accentBrandSoft = Color(0xFF8FB8E6),

    isNight = false
)

/**
 * Night scheme (§54): minimum brightness, low contrast, no bright surfaces.
 * Same tokens, dimmer values — never a second design language.
 */
val OmnixNightColors = OmnixDarkColors.copy(
    background = Color(0xFF000508),
    surface = Color(0xFF060B10),
    surfaceElevated = Color(0xFF0C1318),
    surfaceFilled = Color(0xFF1C1C1E),
    border = Color(0x0FFFFFFF),

    textPrimary = Color(0xD6FFFFFF),
    textSecondary = Color(0x80FFFFFF),
    textTertiary = Color(0x4DFFFFFF),
    textDisabled = Color(0x2BFFFFFF),

    stateIdle = Color(0xFF6E767C),
    stateListening = Color(0xFFB7BEC3),
    stateRecognizing = Color(0xFFB7BEC3),
    stateThinking = Color(0xFFB7BEC3),
    stateExecuting = Color(0xFFB7BEC3),
    stateSpeaking = Color(0xFFB7BEC3),
    stateSuccess = Color(0xFFB7BEC3),
    stateError = Color(0xFFD1615B),
    stateWarning = Color(0xFFD1A94E),

    accentRing = Color(0xFF2C4C72),

    // Same mock, dimmed for the night ground.
    bubbleUser = Color(0xFFC9C9CE),
    onBubbleUser = Color(0xFF0A1118),
    bubbleAi = Color(0xFF141B21),
    onBubbleAi = Color(0xD6FFFFFF),
    errorBubble = Color(0xFF1D100E),

    actionPrimary = Color(0xD6FFFFFF),
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

    accentRing = Color(0xFF33629A),

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
