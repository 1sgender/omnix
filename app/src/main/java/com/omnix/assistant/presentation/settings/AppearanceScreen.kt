package com.omnix.assistant.presentation.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnix.assistant.R
import com.omnix.assistant.presentation.design.OmnixAppearance
import com.omnix.assistant.presentation.design.OmnixColorScheme
import com.omnix.assistant.presentation.design.OmnixDarkColors
import com.omnix.assistant.presentation.design.OmnixLightColors
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.firstrun.OmnixRingMark

/**
 * Appearance (§47, §54, §56).
 *
 * Three real settings, all of which the app actually honours: the light/dark
 * choice, night dimming, and a reduced-motion override that can follow the
 * system or force animation off.
 *
 * Stage 5: grouped cards, and the selected option carries a checkmark with
 * real `selected` semantics — the bullet dot was easy to miss and announced
 * nothing to TalkBack.
 */
@Composable
fun AppearanceScreen(
    appearance: OmnixAppearance,
    nightDimming: Boolean,
    reduceMotionOverride: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onAppearanceChange: (OmnixAppearance) -> Unit = {},
    onNightDimmingChange: (Boolean) -> Unit = {},
    onReduceMotionChange: (String) -> Unit = {}
) {
    val spacing = OmnixTheme.spacing

    SectionScaffold(stringResource(R.string.omnix_appearance_title), modifier, onBack) {
        // «Трафарет»: три превью темы рядом — светлая, тёмная, как в системе.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ThemePreview(
                label = stringResource(R.string.omnix_appearance_light),
                selected = appearance == OmnixAppearance.Light,
                onSelect = { onAppearanceChange(OmnixAppearance.Light) },
                schemes = listOf(OmnixLightColors),
                modifier = Modifier.weight(1f)
            )
            ThemePreview(
                label = stringResource(R.string.omnix_appearance_dark),
                selected = appearance == OmnixAppearance.Dark,
                onSelect = { onAppearanceChange(OmnixAppearance.Dark) },
                schemes = listOf(OmnixDarkColors),
                modifier = Modifier.weight(1f)
            )
            ThemePreview(
                label = stringResource(R.string.omnix_appearance_system),
                selected = appearance == OmnixAppearance.System,
                onSelect = { onAppearanceChange(OmnixAppearance.System) },
                schemes = listOf(OmnixLightColors, OmnixDarkColors),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(spacing.lg))

        OmnixSettingsGroup {
            OmnixToggleRow(
                title = stringResource(R.string.omnix_appearance_night),
                subtitle = stringResource(R.string.omnix_appearance_night_body),
                checked = nightDimming,
                onCheckedChange = onNightDimmingChange,
                inset = true
            )
            OmnixGroupDivider()
            OmnixToggleRow(
                title = stringResource(R.string.omnix_appearance_reduce_motion),
                subtitle = stringResource(R.string.omnix_appearance_reduce_motion_body),
                // "system" defers to the OS; "on" forces motion off in-app.
                checked = reduceMotionOverride == REDUCE_MOTION_ON,
                onCheckedChange = { forced ->
                    onReduceMotionChange(if (forced) REDUCE_MOTION_ON else REDUCE_MOTION_SYSTEM)
                },
                inset = true
            )
        }
    }
}

/**
 * A miniature of the theme: its ground with the mark on a disc. Two schemes
 * split the card down the middle ("as in the system"). The chosen card has a
 * bold outline and a bold label — the same fact the `selected` semantics say.
 */
@Composable
private fun ThemePreview(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    schemes: List<OmnixColorScheme>,
    modifier: Modifier = Modifier
) {
    val colors = OmnixTheme.colors
    Column(
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PREVIEW_HEIGHT)
                .clip(PreviewShape)
                .border(
                    BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.textPrimary else colors.border),
                    PreviewShape
                )
        ) {
            schemes.forEach { scheme ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(scheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (schemes.size == 1) 46.dp else 34.dp)
                            .background(scheme.surface, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        OmnixRingMark(color = scheme.textPrimary, width = 22.dp, height = 22.dp)
                    }
                }
            }
        }
        Text(
            text = label,
            style = TextStyle(
                fontFamily = OmnixSans,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 13.sp
            ),
            color = if (selected) colors.textPrimary else colors.textSecondary,
            maxLines = 1
        )
    }
}

private val PREVIEW_HEIGHT = 140.dp
private val PreviewShape = RoundedCornerShape(18.dp)

const val REDUCE_MOTION_SYSTEM = "system"
const val REDUCE_MOTION_ON = "on"

/**
 * Explicitly opts back into motion even when the OS asks to reduce it.
 * Rare, but a user who turned the OS setting on for one bad app should not
 * be locked out of OMNIX's motion forever.
 */
const val REDUCE_MOTION_OFF = "off"
