package com.omnix.assistant.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.omnix.assistant.BuildConfig
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.OmnixPrimaryButton
import com.omnix.assistant.presentation.components.OmnixTextButton
import com.omnix.assistant.presentation.components.OmnixScreenHeader
import com.omnix.assistant.presentation.design.OmnixTheme

/**
 * Resolves a settings section key to its screen and wires it to the real
 * view models (§42).
 *
 * Keeping the mapping in one place means a section cannot exist in navigation
 * without a screen, or vice versa.
 */
@Composable
fun SettingsSectionRoute(
    section: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onOpenAutomations: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    appearanceViewModel: AppearanceViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val appearanceState by appearanceViewModel.uiState.collectAsState()

    when (section) {
        SECTION_VOICE -> VoiceSettingsScreen(
            modifier = modifier,
            onBack = onBack,
            speechRate = state.speechRate,
            speechPitch = state.speechPitch,
            wakeSensitivity = state.wakeWordSensitivity,
            wakeWordEnabled = state.wakeWordEnabled,
            headsetOnly = state.isHeadsetOnlyMode,
            // Listening is owned by the foreground service, not by settings
            // state; until that is surfaced, the toggle mirrors headset mode
            // rather than pretending to know.
            listeningActive = true,
            voiceFeedback = appearanceState.voiceFeedback,
            haptics = appearanceState.haptics,
            automationCount = state.automations.size,
            onSpeechRateChange = viewModel::onSpeechRateChanged,
            onSpeechPitchChange = viewModel::onSpeechPitchChanged,
            onWakeSensitivityChange = viewModel::onWakeWordSensitivityChanged,
            onWakeWordEnabledChange = viewModel::onWakeWordEnabledChanged,
            onHeadsetOnlyChange = viewModel::onHeadsetOnlyModeChanged,
            onVoiceFeedbackChange = appearanceViewModel::setVoiceFeedback,
            onHapticsChange = appearanceViewModel::setHaptics,
            onCommitChanges = viewModel::saveAllSettings,
            onOpenAutomations = onOpenAutomations
        )

        SECTION_AI -> {
            val modelState by viewModel.localModelState.collectAsState(
                initial = com.omnix.assistant.agent.localai.LocalModelState.NotInitialized
            )
            val aiModelViewModel: AiModelViewModel = hiltViewModel()
            val aiChoice by aiModelViewModel.choice.collectAsState()
            val aiApplying by aiModelViewModel.applying.collectAsState()
            val aiFailure by aiModelViewModel.failure.collectAsState()
            val adminModels by aiModelViewModel.admin.collectAsState()
            val wakeListening by aiModelViewModel.wakeListening.collectAsState()
            AiSettingsScreen(
                modifier = modifier,
                onBack = onBack,
                modelChoice = {
                    AiModelSection(
                        choice = aiChoice,
                        applying = aiApplying,
                        failure = aiFailure,
                        localModelState = modelState,
                        onSelect = aiModelViewModel::select
                    )
                    if (adminModels.isAdmin) {
                        AdminModelSection(
                            state = adminModels,
                            onPin = aiModelViewModel::pinAdminModel,
                            modifier = Modifier.padding(top = 20.dp)
                        )
                        AdminWakeSection(
                            current = wakeListening,
                            onSelect = aiModelViewModel::setWakeListening,
                            modifier = Modifier.padding(top = 20.dp)
                        )
                    }
                },
                systemPrompt = state.systemPrompt,
                onSystemPromptChange = viewModel::onSystemPromptChanged,
                onSave = viewModel::saveAllSettings,
                saved = state.isSavedSuccess,
                modelState = modelState,
                onDownloadAny = { viewModel.downloadLocalModel(overMetered = true) },
                onDownloadWifi = { viewModel.downloadLocalModel(overMetered = false) },
                onCancelDownload = viewModel::cancelLocalModelDownload,
                onDeleteAndRedownload = viewModel::deleteAndRedownloadLocalModel
            )
        }

        SECTION_APPEARANCE -> AppearanceScreen(
            modifier = modifier,
            onBack = onBack,
            appearance = appearanceState.appearance,
            nightDimming = appearanceState.nightDimming,
            reduceMotionOverride = appearanceState.reduceMotionOverride,
            onAppearanceChange = appearanceViewModel::setAppearance,
            onNightDimmingChange = appearanceViewModel::setNightDimming,
            onReduceMotionChange = appearanceViewModel::setReduceMotionOverride
        )

        SECTION_ABOUT -> {
            // Long-press по версии открывает dev-секцию метрик в Diagnostics
            // (план пересборки фронта 2026-09-26, блок 8).
            val aboutViewModel: AboutViewModel = hiltViewModel()
            AboutScreen(
                modifier = modifier,
                onBack = onBack,
                accessState = when {
                    state.licenseInfo?.isExpired == true -> AccessDisplayState.Expired
                    state.licenseInfo?.isActivated == true -> AccessDisplayState.Active
                    else -> AccessDisplayState.NotConfigured
                },
                versionName = BuildConfig.VERSION_NAME,
                deviceCode = null,
                onVersionLongPress = aboutViewModel::revealDevMetrics
            )
        }

        SECTION_LANGUAGE -> LanguageSettingsScreen(modifier = modifier, onBack = onBack)

        SECTION_NOTIFICATIONS -> NotificationsSettingsScreen(
            modifier = modifier,
            onBack = onBack,
            state = appearanceState,
            onRoutinesChange = appearanceViewModel::setNotifyRoutines,
            onOpenSystemSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        )

        SECTION_ADVANCED -> AdvancedSettingsScreen(
            modifier = modifier,
            onBack = onBack,
            accessToken = state.accessToken,
            tokenHidden = state.isAccessTokenHidden,
            tokenInvalid = state.isAccessTokenInvalid,
            saved = state.isSavedSuccess,
            onTokenChange = viewModel::onAccessTokenChanged,
            onToggleVisibility = viewModel::toggleAccessTokenVisibility,
            onSave = viewModel::saveAllSettings,
            onOpenAccessibilitySettings = {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            // Near-miss захват (данные v0.2) — только dev/staging: в prod
            // запись аудио не существует (BuildConfig-гейт, PR-протокол).
            nearMissCaptureEnabled = BuildConfig.NEAR_MISS_CAPTURE_ENABLED,
            nearMissCapture = state.nearMissCapture,
            onNearMissCaptureChange = viewModel::onNearMissCaptureChanged,
            onClearNearMissCaptures = viewModel::clearNearMissCaptures
        )

        SECTION_DIAGNOSTICS -> DiagnosticsScreen(
            modifier = modifier,
            onBack = onBack
        )

        else -> SectionScaffold(stringResource(R.string.omnix_settings_title), modifier, onBack) {}
    }
}

/** Shared page frame: title, then rows. */
@Composable
internal fun SectionScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val spacing = OmnixTheme.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screenHorizontal)
    ) {
        Spacer(Modifier.height(spacing.lg))
        OmnixScreenHeader(title = title, onBack = onBack)
        Spacer(Modifier.height(spacing.sm))
        content()
        Spacer(Modifier.height(spacing.xl))
    }
}

/**
 * Уведомления. В v344 здесь было три переключателя, которые сохранялись, но
 * ничего не меняли. Теперь только правда: разрешены ли уведомления системой
 * (без них Android может выключить прослушивание в фоне; каналы — в системных
 * настройках) и озвучивать ли автоматизации — его читает движок правил.
 */
@Composable
private fun NotificationsSettingsScreen(
    state: AppearanceUiState,
    onRoutinesChange: (Boolean) -> Unit,
    onOpenSystemSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    // Пересчитывается при возврате из системных настроек.
    var systemEnabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                systemEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SectionScaffold(stringResource(R.string.omnix_notifications_settings_title), modifier, onBack) {
        OmnixSettingsGroup {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_notifications_system),
                subtitle = stringResource(R.string.omnix_notifications_system_body),
                value = stringResource(if (systemEnabled) R.string.omnix_notifications_on else R.string.omnix_notifications_off),
                titleColor = if (systemEnabled) null else OmnixTheme.colors.stateWarning,
                inset = true,
                chevron = true,
                onClick = onOpenSystemSettings
            )
            OmnixGroupDivider()
            OmnixToggleRow(
                title = stringResource(R.string.omnix_notifications_routines),
                subtitle = stringResource(R.string.omnix_notifications_routines_body),
                checked = state.notifyRoutines,
                onCheckedChange = onRoutinesChange,
                inset = true
            )
        }
    }
}

@Composable
private fun AiSettingsScreen(
    modelChoice: @Composable () -> Unit,
    systemPrompt: String,
    onSystemPromptChange: (String) -> Unit,
    onSave: () -> Unit,
    saved: Boolean,
    modelState: com.omnix.assistant.agent.localai.LocalModelState,
    onDownloadAny: () -> Unit,
    onDownloadWifi: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteAndRedownload: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    SectionScaffold(stringResource(R.string.omnix_ai_title), modifier, onBack) {
        val spacing = OmnixTheme.spacing
        // The user picks what answers (Auto / on the phone / OMNIX cloud).
        // The cloud provider stays server-managed: no provider names, no
        // temperature, no keys on the device.
        modelChoice()

        Spacer(Modifier.height(spacing.lg))

        OmnixTextFieldRow(
            title = stringResource(R.string.omnix_ai_personality),
            subtitle = stringResource(R.string.omnix_ai_personality_body),
            value = systemPrompt,
            onValueChange = onSystemPromptChange,
            singleLine = false
        )

        Spacer(Modifier.height(spacing.lg))

        OmnixSettingsGroup {
            LocalModelSettingsBlock(
                state = modelState,
                onDownloadAny = onDownloadAny,
                onDownloadWifi = onDownloadWifi,
                onCancelDownload = onCancelDownload,
                onDeleteAndRedownload = onDeleteAndRedownload
            )
        }

        SaveRow(saved = saved, onSave = onSave)
    }
}

/** Save control shared by the editable sections. */
@Composable
private fun SaveRow(saved: Boolean, onSave: () -> Unit) {
    val spacing = OmnixTheme.spacing
    Spacer(Modifier.height(spacing.sm))
    OmnixPrimaryButton(
        text = stringResource(
            if (saved) R.string.omnix_advanced_saved else R.string.omnix_advanced_save
        ),
        onClick = onSave,
        enabled = !saved,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun AdvancedSettingsScreen(
    accessToken: String,
    tokenHidden: Boolean,
    tokenInvalid: Boolean,
    saved: Boolean,
    onTokenChange: (String) -> Unit,
    onToggleVisibility: () -> Unit,
    onSave: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    // Near-miss захват (данные v0.2) — dev/staging только: в prod запись
    // аудио не существует (BuildConfig-гейт, протокол COLLECTING.md).
    nearMissCaptureEnabled: Boolean = false,
    nearMissCapture: Boolean = false,
    onNearMissCaptureChange: (Boolean) -> Unit = {},
    onClearNearMissCaptures: () -> Unit = {}
) {
    SectionScaffold(stringResource(R.string.omnix_advanced_title), modifier, onBack) {
        val spacing = OmnixTheme.spacing

        Text(
            text = stringResource(R.string.omnix_advanced_body),
            style = OmnixTheme.typography.caption,
            color = OmnixTheme.colors.textTertiary
        )

        Spacer(Modifier.height(spacing.sm))

        // The token is editable here because a user re-entering it is the
        // documented recovery path when access stops working.
        OmnixTextFieldRow(
            title = stringResource(R.string.omnix_advanced_access_token),
            subtitle = stringResource(R.string.omnix_advanced_access_token_body),
            value = accessToken,
            onValueChange = onTokenChange,
            placeholder = stringResource(R.string.omnix_not_configured),
            isError = tokenInvalid,
            errorText = stringResource(R.string.omnix_advanced_token_invalid),
            visualTransformation = if (tokenHidden) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            trailing = {
                OmnixTextButton(
                    text = stringResource(R.string.omnix_advanced_show_hide),
                    onClick = onToggleVisibility
                )
            }
        )

        SaveRow(saved = saved, onSave = onSave)

        OmnixSettingsGroup {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_advanced_accessibility),
                subtitle = stringResource(R.string.omnix_advanced_accessibility_body),
                value = stringResource(R.string.omnix_advanced_accessibility_action),
                inset = true,
                chevron = true,
                onClick = onOpenAccessibilitySettings
            )
        }

        // Near-miss захват (данные v0.2, protocol training/COLLECTING.md §5):
        // явное опциональное включение, запись только локальная.
        if (nearMissCaptureEnabled) {
            Spacer(Modifier.height(spacing.md))
            OmnixSettingsGroup {
                OmnixToggleRow(
                    title = stringResource(R.string.omnix_advanced_nearmiss_title),
                    subtitle = stringResource(R.string.omnix_advanced_nearmiss_body),
                    checked = nearMissCapture,
                    onCheckedChange = onNearMissCaptureChange,
                    inset = true
                )
                if (nearMissCapture) {
                    OmnixGroupDivider()
                    Row(Modifier.padding(horizontal = spacing.md)) {
                        OmnixTextButton(
                            text = stringResource(R.string.omnix_advanced_nearmiss_clear),
                            onClick = onClearNearMissCaptures
                        )
                    }
                }
            }
        }
    }
}
