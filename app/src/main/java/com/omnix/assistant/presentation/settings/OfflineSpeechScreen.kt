package com.omnix.assistant.presentation.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.omnix.assistant.R
import com.omnix.assistant.agent.localai.LocalModelManager
import com.omnix.assistant.agent.localai.LocalModelState
import com.omnix.assistant.presentation.components.OmnixPrimaryButton
import com.omnix.assistant.presentation.components.OmnixTextButton
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.voice.offline.OfflineModelState
import com.omnix.assistant.voice.offline.OfflinePack
import com.omnix.assistant.voice.offline.OfflineSpeechModel
import com.omnix.assistant.voice.offline.OfflineSpeechModelStore
import com.omnix.assistant.voice.offline.OfflineSpeechModels
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Раздел «Я → Офлайн-режим»: локальный ИИ, голос и распознавание одним пакетом. */
const val SECTION_OFFLINE_SPEECH = "offline_speech"

@HiltViewModel
class OfflineSpeechViewModel @Inject constructor(
    private val store: OfflineSpeechModelStore,
    private val pack: OfflinePack,
    private val localModel: LocalModelManager
) : ViewModel() {

    val states: StateFlow<Map<String, OfflineModelState>> = store.states
    val localState: StateFlow<LocalModelState> = localModel.stateFlow

    private val _localInstalled = MutableStateFlow(false)

    /** Файл локального ИИ на телефоне (состояние NotInitialized этого не говорит). */
    val localInstalled: StateFlow<Boolean> = _localInstalled.asStateFlow()

    val totalSizeMb: Int get() = pack.totalSizeMb

    fun refresh() {
        store.refresh()
        viewModelScope.launch { _localInstalled.value = localModel.isModelInstalled() }
    }

    fun downloadAll(overMetered: Boolean) {
        viewModelScope.launch { pack.downloadAll(overMetered) }
    }

    fun download(model: OfflineSpeechModel) = store.download(model)

    fun remove(model: OfflineSpeechModel) = store.remove(model)

    fun removeLocal() {
        viewModelScope.launch {
            localModel.deleteModel()
            _localInstalled.value = false
        }
    }
}

@Composable
internal fun OfflineSpeechScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: OfflineSpeechViewModel = hiltViewModel()
) {
    val states by viewModel.states.collectAsStateWithLifecycle()
    val localState by viewModel.localState.collectAsStateWithLifecycle()
    val localInstalled by viewModel.localInstalled.collectAsStateWithLifecycle()
    // Пересчитываем при каждой смене состояния локального ИИ: загрузка могла завершиться.
    LaunchedEffect(localState) { viewModel.refresh() }

    val speechParts = listOf(OfflineSpeechModels.TK_VOICE, OfflineSpeechModels.TK_ASR)
    val allInstalled = localInstalled &&
        speechParts.filter { it.isPublished }.all { states[it.id] == OfflineModelState.Installed }
    val busy = localState is LocalModelState.Downloading ||
        speechParts.any { states[it.id] is OfflineModelState.Downloading || states[it.id] == OfflineModelState.Installing }

    SectionScaffold(stringResource(R.string.omnix_offline_title), modifier, onBack) {
        Text(
            text = stringResource(R.string.omnix_offline_hint),
            style = OmnixTheme.typography.caption,
            color = OmnixTheme.colors.textSecondary
        )
        Spacer(Modifier.height(OmnixTheme.spacing.sm))
        if (!allInstalled && !busy) {
            OmnixPrimaryButton(
                text = stringResource(R.string.omnix_offline_download_all, viewModel.totalSizeMb),
                onClick = { viewModel.downloadAll(overMetered = true) },
                modifier = Modifier.fillMaxWidth()
            )
            OmnixTextButton(
                text = stringResource(R.string.omnix_offline_download_wifi),
                onClick = { viewModel.downloadAll(overMetered = false) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(OmnixTheme.spacing.sm))
        }
        OmnixSettingsGroup {
            LocalAiRow(localState, localInstalled, viewModel)
            OmnixGroupDivider()
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
private fun LocalAiRow(state: LocalModelState, installed: Boolean, viewModel: OfflineSpeechViewModel) {
    val subtitle = when {
        state is LocalModelState.Downloading ->
            stringResource(R.string.omnix_offline_state_downloading, state.progressPercent)
        state is LocalModelState.InsufficientMemory -> stringResource(R.string.omnix_ai_model_low_memory)
        state is LocalModelState.DownloadFailed -> stringResource(R.string.omnix_offline_state_failed, state.reason)
        installed -> stringResource(R.string.omnix_offline_state_installed)
        else -> stringResource(R.string.omnix_offline_state_not_installed, viewModel.totalSizeMb - speechMb())
    }
    OmnixSettingRow(
        title = stringResource(R.string.omnix_offline_local_ai),
        subtitle = subtitle,
        inset = true,
        trailing = {
            if (installed && state !is LocalModelState.Downloading) {
                OmnixTextButton(
                    text = stringResource(R.string.omnix_offline_action_remove),
                    onClick = { viewModel.removeLocal() }
                )
            }
        }
    )
}

private fun speechMb(): Int =
    listOf(OfflineSpeechModels.TK_VOICE, OfflineSpeechModels.TK_ASR).filter { it.isPublished }.sumOf { it.approxSizeMb }

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
