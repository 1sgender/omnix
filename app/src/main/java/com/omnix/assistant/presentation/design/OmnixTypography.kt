package com.omnix.assistant.presentation.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.omnix.assistant.R

/**
 * OMNIX typography tokens — the Apple HIG type ladder on Onest (§6; design v2).
 *
 * "Typography should disappear into the product": one family, few weights,
 * a complete ladder from Large Title down to Caption 2. Sizes are declared in
 * `sp` so system font scaling keeps working (§30). Screens must not declare
 * `fontSize = 15.sp` locally.
 *
 * Design v2 (2026-10-13): Onest for all text and IBM Plex Mono for technical
 * labels (battery, times, status chips), both bundled under the SIL OFL 1.1
 * (see NOTICE) so OEM fonts cannot change the look. Dynamic Type still works —
 * `sp` and `FontWeight` map onto the user's font-scale setting.
 */
@Immutable
data class OmnixTypographyTokens(
    /** Large Title — first-run statements, the product wordmark moment. */
    val display: TextStyle,
    /** Splash wordmark: the large OMNIX on the model-loading screen. */
    val splashWordmark: TextStyle,
    /** Title 1 — screen titles: Settings, Devices, History. */
    val screenTitle: TextStyle,
    /** Title 2 — prominent section titles, translator panes. */
    val title2: TextStyle,
    /** Title 3 — section and card headings. */
    val heading: TextStyle,
    /** Headline — row titles, emphasised labels (17 semibold). */
    val headline: TextStyle,
    /** Body — default reading text and primary state labels (17). */
    val body: TextStyle,
    /** Callout — secondary reading text, sheet copy (16). */
    val callout: TextStyle,
    /** Subheadline — supporting lines in rows (15). */
    val subheadline: TextStyle,
    /** Footnote — guidance, secondary explanations, examples (13). */
    val caption: TextStyle,
    /** Caption 2 — the smallest permitted text (11), timestamps only. */
    val caption2: TextStyle,
    /** Status line: "Listening…", "Clip Connected" (13 medium). */
    val status: TextStyle,
    /** Small overline labels: "TODAY", "YOU", "OMNIX". */
    val overline: TextStyle
)

/**
 * Onest, one variable font (wght axis) from res/font (OFL 1.1, NOTICE); each
 * weight selects its point on the axis.
 */
@OptIn(ExperimentalTextApi::class)
val OmnixSans: FontFamily = FontFamily(
    onestWeight(FontWeight.Normal),
    onestWeight(FontWeight.Medium),
    onestWeight(FontWeight.SemiBold),
    onestWeight(FontWeight.Bold)
)

@OptIn(ExperimentalTextApi::class)
private fun onestWeight(weight: FontWeight) = Font(
    R.font.onest_variable,
    weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

/** IBM Plex Mono for technical labels: battery, times, status chips (OFL 1.1). */
val OmnixMono: FontFamily = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium)
)

/** Mono label as drawn in the design v2 canvas: 12sp, slight tracking. */
val OmnixMonoLabelStyle: TextStyle = TextStyle(
    fontFamily = OmnixMono,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.48.sp
)

val OmnixTypography = OmnixTypographyTokens(
    display = TextStyle(
        fontFamily = OmnixSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = (-0.4).sp
    ),
    splashWordmark = TextStyle(
        fontFamily = OmnixSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 46.sp
    ),
    screenTitle = TextStyle(
        fontFamily = OmnixSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.35).sp
    ),
    title2 = TextStyle(
        fontFamily = OmnixSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.25).sp
    ),
    heading = TextStyle(
        fontFamily = OmnixSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.2).sp
    ),
    headline = TextStyle(
        fontFamily = OmnixSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.2).sp
    ),
    body = TextStyle(
        fontFamily = OmnixSans,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.2).sp
    ),
    callout = TextStyle(
        fontFamily = OmnixSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.1).sp
    ),
    subheadline = TextStyle(
        fontFamily = OmnixSans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.1).sp
    ),
    caption = TextStyle(
        fontFamily = OmnixSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    caption2 = TextStyle(
        fontFamily = OmnixSans,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.06.sp
    ),
    status = TextStyle(
        fontFamily = OmnixSans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    overline = TextStyle(
        fontFamily = OmnixSans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.8.sp
    )
)

/** The OMNIX wordmark: the only place with wide tracking (§23, §79). */
val OmnixWordmarkStyle: TextStyle = TextStyle(
    fontFamily = OmnixSans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    letterSpacing = 2.8.sp
)
