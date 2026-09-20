package com.omnix.assistant.presentation.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.OmnixCheckIcon
import com.omnix.assistant.presentation.design.OmnixAppearance
import com.omnix.assistant.presentation.design.OmnixTheme

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
        OmnixSettingsGroup {
            Column(modifier = Modifier.selectableGroup()) {
                AppearanceOption(
                    label = stringResource(R.string.omnix_appearance_system),
                    selected = appearance == OmnixAppearance.System,
                    onSelect = { onAppearanceChange(OmnixAppearance.System) }
                )
                OmnixGroupDivider()
                AppearanceOption(
                    label = stringResource(R.string.omnix_appearance_dark),
                    selected = appearance == OmnixAppearance.Dark,
                    onSelect = { onAppearanceChange(OmnixAppearance.Dark) }
                )
                OmnixGroupDivider()
                AppearanceOption(
                    label = stringResource(R.string.omnix_appearance_light),
                    selected = appearance == OmnixAppearance.Light,
                    onSelect = { onAppearanceChange(OmnixAppearance.Light) }
                )
            }
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

@Composable
private fun AppearanceOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    OmnixSettingRow(
        title = label,
        inset = true,
        onClick = onSelect,
        // The visible checkmark and the spoken "selected" state are the same
        // fact, stated twice (§55).
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
    ) {
        // Trailing slot: a check appears only on the chosen option.
        if (selected) {
            OmnixCheckIcon(color = OmnixTheme.colors.textPrimary)
        }
    }
}

const val REDUCE_MOTION_SYSTEM = "system"
const val REDUCE_MOTION_ON = "on"

/**
 * Explicitly opts back into motion even when the OS asks to reduce it.
 * Rare, but a user who turned the OS setting on for one bad app should not
 * be locked out of OMNIX's motion forever.
 */
const val REDUCE_MOTION_OFF = "off"
