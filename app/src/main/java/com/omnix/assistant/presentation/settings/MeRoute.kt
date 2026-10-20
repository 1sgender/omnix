package com.omnix.assistant.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnix.assistant.BuildConfig
import com.omnix.assistant.R
import com.omnix.assistant.agent.localai.LocalModelState
import com.omnix.assistant.presentation.design.OmnixAppearance
import com.omnix.assistant.presentation.navigation.OmnixDestination
import com.omnix.assistant.presentation.state.ClipState
import java.util.Locale

/**
 * Feeds [MeScreen] with real values: the user's name and licence, the
 * connected headset, the automation count, the local model state and the
 * appearance in use.
 */
@Composable
fun MeRoute(
    clip: ClipState,
    onOpen: (OmnixDestination) -> Unit,
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    appearanceViewModel: AppearanceViewModel = hiltViewModel()
) {
    val settings by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val model by settingsViewModel.localModelState.collectAsStateWithLifecycle()
    val appearance by appearanceViewModel.uiState.collectAsStateWithLifecycle()

    val license = settings.licenseInfo?.takeIf { it.isActivated && !it.isExpired }
    val headsetName = when (clip) {
        is ClipState.Connected -> clip.deviceName
        is ClipState.BatteryLow -> clip.deviceName
        else -> ""
    }
    val modelValue = when (model) {
        is LocalModelState.Ready -> stringResource(R.string.omnix_me_model_ready)
        is LocalModelState.Downloading -> stringResource(R.string.omnix_me_model_downloading)
        is LocalModelState.NotInstalled -> stringResource(R.string.omnix_me_model_missing)
        else -> ""
    }
    val appearanceValue = stringResource(
        when (appearance.appearance) {
            OmnixAppearance.System -> R.string.omnix_appearance_system
            OmnixAppearance.Dark -> R.string.omnix_appearance_dark
            OmnixAppearance.Light -> R.string.omnix_appearance_light
        }
    )

    MeScreen(
        userName = settings.userName,
        licenseActive = license != null,
        plan = license?.planId.orEmpty().replaceFirstChar { it.titlecase(Locale.getDefault()) },
        versionName = BuildConfig.VERSION_NAME,
        groupA = listOf(
            MeRow(
                glyph = stringResource(R.string.omnix_me_glyph_headphones),
                label = stringResource(R.string.omnix_nav_headphones),
                value = headsetName,
                destination = OmnixDestination.Devices
            ),
            MeRow(
                glyph = stringResource(R.string.omnix_me_glyph_voice),
                label = stringResource(R.string.omnix_me_row_voice),
                value = "",
                destination = OmnixDestination.SettingsSection(SECTION_VOICE)
            ),
            MeRow(
                glyph = stringResource(R.string.omnix_me_glyph_translator),
                label = stringResource(R.string.omnix_translator_title),
                value = "",
                destination = OmnixDestination.Translator
            ),
            MeRow(
                glyph = stringResource(R.string.omnix_me_glyph_automations),
                label = stringResource(R.string.omnix_home_card_automations),
                value = settings.automations.size.takeIf { it > 0 }?.toString().orEmpty(),
                destination = OmnixDestination.Automations
            )
        ),
        groupB = listOf(
            MeRow(
                glyph = stringResource(R.string.omnix_me_glyph_model),
                label = stringResource(R.string.omnix_me_row_model),
                value = modelValue,
                destination = OmnixDestination.SettingsSection(SECTION_AI)
            ),
            MeRow(
                glyph = stringResource(R.string.omnix_me_glyph_memory),
                label = stringResource(R.string.omnix_me_row_memory),
                value = "",
                destination = OmnixDestination.Privacy
            ),
            MeRow(
                glyph = stringResource(R.string.omnix_me_glyph_appearance),
                label = stringResource(R.string.omnix_settings_appearance),
                value = appearanceValue,
                destination = OmnixDestination.SettingsSection(SECTION_APPEARANCE)
            )
        ),
        modifier = modifier,
        onOpen = onOpen,
        onVersionTap = { onOpen(OmnixDestination.SettingsSection(SECTION_ABOUT)) },
        onVersionLongPress = { onOpen(OmnixDestination.SettingsSection(SECTION_DIAGNOSTICS)) }
    )
}
