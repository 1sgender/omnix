package com.omnix.assistant.presentation.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.OmnixTextButton
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.voice.offline.OfflineModelState
import com.omnix.assistant.voice.offline.OfflineSpeechModel
import com.omnix.assistant.voice.offline.OfflineSpeechModelStore
import com.omnix.assistant.voice.offline.OfflineSpeechModels
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Раздел «Я → Офлайн-голос»: скачивание моделей речи, которые работают без интернета. */
const val SECTION_OFFLINE_SPEECH = "offline_speech"

@HiltViewModel
class OfflineSpeechViewModel @Inject constructor(
    private val store: OfflineSpeechModelStore
) : ViewModel() {

    val states: StateFlow<Map<String, OfflineModelState>> = store.states

    fun refresh() = store.refresh()

    fun download(model: OfflineSpeechModel) = store.download(model)

    fun remove(model: OfflineSpeechModel) = store.remove(model)
}

@Composable
internal fun OfflineSpeechScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: OfflineSpeechViewModel = hiltViewModel()
) {
    val states by viewModel.states.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }

    SectionScaffold(stringResource(R.string.omnix_offline_title), modifier, onBack) {
        Text(
            text = stringResource(R.string.omnix_offline_hint),
            style = OmnixTheme.typography.caption,
            color = OmnixTheme.colors.textSecondary
        )
        Spacer(Modifier.height(OmnixTheme.spacing.sm))
        OmnixSettingsGroup {
            ModelRow(
                title = stringResource(R.string.omnix_offline_tk_voice),
                model = OfflineSpeechModels.TK_VOICE,
                state = states[OfflineSpeechModels.TK_VOICE.id],
                viewModel = viewModel
            )
            OmnixGroupDivider()
            ModelRow(
                title = stringResource(R.string.omnix_offline_tk_asr),
                model = OfflineSpeechModels.TK_ASR,
                state = states[OfflineSpeechModels.TK_ASR.id],
                viewModel = viewModel
            )
            OmnixGroupDivider()
            OmnixSettingRow(
                title = stringResource(R.string.omnix_offline_ru_asr),
                subtitle = stringResource(R.string.omnix_offline_state_builtin),
                inset = true
            )
        }
    }
}

@Composable
private fun ModelRow(
    title: String,
    model: OfflineSpeechModel,
    state: OfflineModelState?,
    viewModel: OfflineSpeechViewModel
) {
    val current = state ?: OfflineModelState.ComingSoon
    val subtitle = when (current) {
        OfflineModelState.ComingSoon -> stringResource(R.string.omnix_offline_state_soon)
        OfflineModelState.NotInstalled -> stringResource(R.string.omnix_offline_state_not_installed, model.approxSizeMb)
        is OfflineModelState.Downloading -> stringResource(R.string.omnix_offline_state_downloading, current.progressPercent)
        OfflineModelState.Installing -> stringResource(R.string.omnix_offline_state_installing)
        OfflineModelState.Installed -> stringResource(R.string.omnix_offline_state_installed)
        is OfflineModelState.Failed -> stringResource(R.string.omnix_offline_state_failed, current.reason)
    }
    OmnixSettingRow(
        title = title,
        subtitle = subtitle,
        inset = true,
        trailing = {
            when (current) {
                OfflineModelState.NotInstalled, is OfflineModelState.Failed ->
                    OmnixTextButton(
                        text = stringResource(R.string.omnix_offline_action_download),
                        onClick = { viewModel.download(model) }
                    )
                OfflineModelState.Installed ->
                    OmnixTextButton(
                        text = stringResource(R.string.omnix_offline_action_remove),
                        onClick = { viewModel.remove(model) }
                    )
                else -> Unit
            }
        }
    )
}
