package com.omnix.assistant.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.OmnixStatusDot
import com.omnix.assistant.presentation.components.clipDotColor
import com.omnix.assistant.presentation.components.clipLabel
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.navigation.OmnixDestination
import com.omnix.assistant.presentation.state.ClipState

/**
 * "Me" — the third primary destination (§20, §44).
 *
 * It is an index of human concepts, not a preference dump: Voice, Privacy,
 * Devices, AI, Language, Notifications, Appearance, About. Each opens a focused
 * page. Advanced is present but visually last, because it is not part of
 * everyday use (§42).
 *
 * Stage 3: the flat divider list became iOS grouped cards — one rounded
 * surface per concept cluster, hairline insets between rows, chevrons only
 * where a row opens another page. The Clip's state leads the page: it is the
 * one piece of live status worth surfacing here, the thing that determines
 * whether OMNIX can hear the user.
 */
@Composable
fun MeScreen(
    clip: ClipState,
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    onOpenSection: (OmnixDestination) -> Unit = {}
) {
    val spacing = OmnixTheme.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screenHorizontal)
    ) {
        Spacer(Modifier.height(spacing.lg))

        Text(
            text = stringResource(R.string.omnix_settings_title),
            style = OmnixTheme.typography.screenTitle,
            color = OmnixTheme.colors.textPrimary
        )

        Spacer(Modifier.height(spacing.md))

        val clipStatus = clipLabel(clip)
        OmnixSettingsGroup {
            OmnixSettingRow(
                title = clipStatus,
                value = if (isOnline) {
                    null
                } else {
                    stringResource(R.string.omnix_status_offline)
                },
                contentDescription = stringResource(
                    R.string.omnix_a11y_clip_status,
                    clipStatus
                ),
                leading = { OmnixStatusDot(color = clipDotColor(clip)) },
                inset = true,
                chevron = true,
                onClick = { onOpenSection(OmnixDestination.Devices) }
            )
        }

        // Modes come before preferences: they are things OMNIX can do, not
        // things to configure. Translation is a mode, not an app (§25), and
        // typing is the quiet alternative to speaking (§24).
        OmnixSettingsSectionHeader(
            text = stringResource(R.string.omnix_settings_section_modes)
        )
        OmnixSettingsGroup {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_translator_title),
                contentDescription = stringResource(R.string.omnix_a11y_open_translator),
                inset = true,
                chevron = true,
                onClick = { onOpenSection(OmnixDestination.Translator) }
            )
            OmnixGroupDivider()
            OmnixSettingRow(
                title = stringResource(R.string.omnix_chat_title),
                contentDescription = stringResource(R.string.omnix_a11y_open_chat),
                inset = true,
                chevron = true,
                onClick = { onOpenSection(OmnixDestination.Chat) }
            )
        }

        Spacer(Modifier.height(spacing.lg))
        OmnixSettingsGroup {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_settings_voice),
                inset = true,
                chevron = true,
                onClick = {
                    onOpenSection(OmnixDestination.SettingsSection(SECTION_VOICE))
                }
            )
            OmnixGroupDivider()
            OmnixSettingRow(
                title = stringResource(R.string.omnix_settings_privacy),
                inset = true,
                chevron = true,
                onClick = { onOpenSection(OmnixDestination.Privacy) }
            )
            OmnixGroupDivider()
            OmnixSettingRow(
                title = stringResource(R.string.omnix_settings_devices),
                contentDescription = stringResource(R.string.omnix_a11y_open_devices),
                inset = true,
                chevron = true,
                onClick = { onOpenSection(OmnixDestination.Devices) }
            )
            OmnixGroupDivider()
            OmnixSettingRow(
                title = stringResource(R.string.omnix_settings_ai),
                inset = true,
                chevron = true,
                onClick = {
                    onOpenSection(OmnixDestination.SettingsSection(SECTION_AI))
                }
            )
            OmnixGroupDivider()
            OmnixSettingRow(
                title = stringResource(R.string.omnix_settings_language),
                inset = true,
                chevron = true,
                onClick = {
                    onOpenSection(OmnixDestination.SettingsSection(SECTION_LANGUAGE))
                }
            )
            OmnixGroupDivider()
            OmnixSettingRow(
                title = stringResource(R.string.omnix_settings_notifications),
                inset = true,
                chevron = true,
                onClick = {
                    onOpenSection(OmnixDestination.SettingsSection(SECTION_NOTIFICATIONS))
                }
            )
            OmnixGroupDivider()
            OmnixSettingRow(
                title = stringResource(R.string.omnix_settings_appearance),
                inset = true,
                chevron = true,
                onClick = {
                    onOpenSection(OmnixDestination.SettingsSection(SECTION_APPEARANCE))
                }
            )
        }

        Spacer(Modifier.height(spacing.lg))
        OmnixSettingsGroup {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_settings_about),
                inset = true,
                chevron = true,
                onClick = {
                    onOpenSection(OmnixDestination.SettingsSection(SECTION_ABOUT))
                }
            )
            OmnixGroupDivider()
            OmnixSettingRow(
                title = stringResource(R.string.omnix_settings_diagnostics),
                inset = true,
                chevron = true,
                onClick = {
                    onOpenSection(OmnixDestination.SettingsSection(SECTION_DIAGNOSTICS))
                }
            )
        }

        Spacer(Modifier.height(spacing.lg))
        OmnixSettingsGroup {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_settings_advanced),
                subtitle = stringResource(R.string.omnix_advanced_body),
                inset = true,
                chevron = true,
                onClick = {
                    onOpenSection(OmnixDestination.SettingsSection(SECTION_ADVANCED))
                }
            )
        }

        Spacer(Modifier.height(spacing.xl))
    }
}


const val SECTION_VOICE = "voice"
const val SECTION_AI = "ai"
const val SECTION_LANGUAGE = "language"
const val SECTION_NOTIFICATIONS = "notifications"
const val SECTION_APPEARANCE = "appearance"
const val SECTION_ABOUT = "about"
const val SECTION_ADVANCED = "advanced"
const val SECTION_DIAGNOSTICS = "diagnostics"
