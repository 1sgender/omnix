package com.omnix.assistant.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnix.assistant.BuildConfig
import com.omnix.assistant.R
import com.omnix.assistant.agent.decision.AiModelChoice
import com.omnix.assistant.presentation.design.OmnixAppearance
import com.omnix.assistant.presentation.navigation.OmnixDestination
import java.util.Locale

/**
 * Feeds [MeScreen]. Two groups, each a different kind of thing:
 *
 *  - **Features** — what OMNIX does: the translator, automations, memory
 *    and privacy.
 *  - **Settings** — how it behaves: voice and the wake word, the AI model,
 *    appearance, language, notifications.
 *
 * The headphones live in their own tab and are not repeated here. Support
 * pages are behind the version at the bottom (tap — About, long press —
 * Diagnostics). Values are real: the automation count, the chosen AI model,
 * the appearance in use.
 */
@Composable
fun MeRoute(
    onOpen: (OmnixDestination) -> Unit,
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    appearanceViewModel: AppearanceViewModel = hiltViewModel(),
    aiModelViewModel: AiModelViewModel = hiltViewModel()
) {
    val settings by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val appearance by appearanceViewModel.uiState.collectAsStateWithLifecycle()
    val aiChoice by aiModelViewModel.choice.collectAsStateWithLifecycle()

    val license = settings.licenseInfo?.takeIf { it.isActivated && !it.isExpired }
    val appearanceValue = stringResource(
        when (appearance.appearance) {
            OmnixAppearance.System -> R.string.omnix_appearance_system
            OmnixAppearance.Dark -> R.string.omnix_appearance_dark
            OmnixAppearance.Light -> R.string.omnix_appearance_light
        }
    )
    val aiValue = stringResource(
        when (aiChoice) {
            AiModelChoice.AUTO -> R.string.omnix_ai_model_auto
            AiModelChoice.ON_DEVICE -> R.string.omnix_ai_model_on_device
            AiModelChoice.CLOUD -> R.string.omnix_ai_model_cloud
        }
    )

    MeScreen(
        userName = settings.userName,
        licenseActive = license != null,
        plan = license?.planId.orEmpty().replaceFirstChar { it.titlecase(Locale.getDefault()) },
        versionName = BuildConfig.VERSION_NAME,
        groups = listOf(
            MeGroup(
                title = stringResource(R.string.omnix_me_section_features),
                rows = listOf(
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
                    ),
                    MeRow(
                        glyph = stringResource(R.string.omnix_me_glyph_memory),
                        label = stringResource(R.string.omnix_me_row_memory),
                        value = "",
                        destination = OmnixDestination.Privacy
                    )
                )
            ),
            MeGroup(
                title = stringResource(R.string.omnix_me_section_settings),
                rows = listOf(
                    MeRow(
                        glyph = stringResource(R.string.omnix_me_glyph_voice),
                        label = stringResource(R.string.omnix_me_row_voice),
                        value = "",
                        destination = OmnixDestination.SettingsSection(SECTION_VOICE)
                    ),
                    MeRow(
                        glyph = stringResource(R.string.omnix_me_glyph_model),
                        label = stringResource(R.string.omnix_ai_title),
                        value = aiValue,
                        destination = OmnixDestination.SettingsSection(SECTION_AI)
                    ),
                    MeRow(
                        glyph = stringResource(R.string.omnix_me_glyph_appearance),
                        label = stringResource(R.string.omnix_settings_appearance),
                        value = appearanceValue,
                        destination = OmnixDestination.SettingsSection(SECTION_APPEARANCE)
                    ),
                    MeRow(
                        glyph = stringResource(R.string.omnix_me_glyph_language),
                        label = stringResource(R.string.omnix_me_row_language),
                        value = "",
                        destination = OmnixDestination.SettingsSection(SECTION_LANGUAGE)
                    ),
                    MeRow(
                        glyph = stringResource(R.string.omnix_me_glyph_notifications),
                        label = stringResource(R.string.omnix_me_row_notifications),
                        value = "",
                        destination = OmnixDestination.SettingsSection(SECTION_NOTIFICATIONS)
                    )
                )
            )
        ),
        modifier = modifier,
        onOpen = onOpen,
        onVersionTap = { onOpen(OmnixDestination.SettingsSection(SECTION_ABOUT)) },
        onVersionLongPress = { onOpen(OmnixDestination.SettingsSection(SECTION_DIAGNOSTICS)) }
    )
}
