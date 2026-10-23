package com.omnix.assistant.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnix.assistant.BuildConfig
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.OmnixAppearanceLineIcon
import com.omnix.assistant.presentation.components.OmnixBellLineIcon
import com.omnix.assistant.presentation.components.OmnixBoltLineIcon
import com.omnix.assistant.presentation.components.OmnixGlobeLineIcon
import com.omnix.assistant.presentation.components.OmnixHeadphonesLineIcon
import com.omnix.assistant.presentation.components.OmnixMemoryLineIcon
import com.omnix.assistant.presentation.components.OmnixSparkLineIcon
import com.omnix.assistant.presentation.components.OmnixTranslatorLineIcon
import com.omnix.assistant.presentation.components.OmnixVoiceLineIcon
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
                        icon = OmnixTranslatorLineIcon,
                        label = stringResource(R.string.omnix_translator_title),
                        value = "",
                        destination = OmnixDestination.Translator
                    ),
                    MeRow(
                        icon = OmnixBoltLineIcon,
                        label = stringResource(R.string.omnix_home_card_automations),
                        value = settings.automations.size.takeIf { it > 0 }?.toString().orEmpty(),
                        destination = OmnixDestination.Automations
                    ),
                    MeRow(
                        icon = OmnixMemoryLineIcon,
                        label = stringResource(R.string.omnix_me_row_memory),
                        value = "",
                        destination = OmnixDestination.Privacy
                    )
                )
            ),
            MeGroup(
                title = stringResource(R.string.omnix_me_section_settings),
                rows = listOf(
                    // Наушники — строкой здесь, а не вкладкой (решение владельца
                    // 2026-10-21: три вкладки); их статус виден и на «Главной».
                    MeRow(
                        icon = OmnixHeadphonesLineIcon,
                        label = stringResource(R.string.omnix_nav_headphones),
                        value = "",
                        destination = OmnixDestination.Devices
                    ),
                    MeRow(
                        icon = OmnixVoiceLineIcon,
                        label = stringResource(R.string.omnix_me_row_voice),
                        value = "",
                        destination = OmnixDestination.SettingsSection(SECTION_VOICE)
                    ),
                    MeRow(
                        icon = OmnixSparkLineIcon,
                        label = stringResource(R.string.omnix_ai_title),
                        value = aiValue,
                        destination = OmnixDestination.SettingsSection(SECTION_AI)
                    ),
                    MeRow(
                        icon = OmnixAppearanceLineIcon,
                        label = stringResource(R.string.omnix_settings_appearance),
                        value = appearanceValue,
                        destination = OmnixDestination.SettingsSection(SECTION_APPEARANCE)
                    ),
                    MeRow(
                        icon = OmnixGlobeLineIcon,
                        label = stringResource(R.string.omnix_me_row_language),
                        value = com.omnix.assistant.core.locale.AppLanguage.current().nativeName,
                        destination = OmnixDestination.SettingsSection(SECTION_LANGUAGE)
                    ),
                    MeRow(
                        icon = OmnixBellLineIcon,
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
