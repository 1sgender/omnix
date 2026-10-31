package com.omnix.assistant.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnix.assistant.agent.decision.AiModelActivation
import com.omnix.assistant.agent.decision.AiModelActivator
import com.omnix.assistant.agent.decision.AiModelChoice
import com.omnix.assistant.agent.localai.LocalModelManager
import com.omnix.assistant.agent.localai.LocalModelState
import com.omnix.assistant.core.admin.AdminModelPin
import com.omnix.assistant.core.admin.AdminModelState
import com.omnix.assistant.core.admin.AdminModelStore
import com.omnix.assistant.core.network.ServerHealthProbe
import com.omnix.assistant.data.preferences.AiModelChoiceStore
import com.omnix.assistant.data.preferences.SettingsDataStore
import com.omnix.assistant.voice.wakeword.phrase.WakeEngineKind
import com.omnix.assistant.voice.wakeword.phrase.WakePhrase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Экран «ИИ-модель»: текущий выбор, состояние модели на телефоне, смена
 * выбора с проверкой. Пока идёт проверка, другие варианты недоступны;
 * не включилась — прежний выбор остаётся, причина показывается.
 */
@HiltViewModel
class AiModelViewModel @Inject constructor(
    private val store: AiModelChoiceStore,
    private val localModelManager: LocalModelManager,
    private val serverHealth: ServerHealthProbe,
    private val adminModels: AdminModelStore,
    private val settings: SettingsDataStore
) : ViewModel() {

    val choice: StateFlow<AiModelChoice> = store.choice
    val localModelState: StateFlow<LocalModelState> = localModelManager.stateFlow

    private val _applying = MutableStateFlow<AiModelChoice?>(null)

    /** Вариант, который сейчас проверяется; null — проверок нет. */
    val applying: StateFlow<AiModelChoice?> = _applying.asStateFlow()

    private val _failure = MutableStateFlow<AiModelActivation.Failed?>(null)

    /** Почему последний выбор не применился. */
    val failure: StateFlow<AiModelActivation.Failed?> = _failure.asStateFlow()

    private val activator = AiModelActivator(
        loadLocalModel = {
            withTimeoutOrNull(LOCAL_LOAD_TIMEOUT_MS) { localModelManager.initialize() }
                ?: LocalModelState.Failed("timeout")
        },
        serverReachable = { serverHealth.isReachable() },
        save = store::set
    )

    fun select(target: AiModelChoice) {
        if (target == choice.value || _applying.value != null) return
        _applying.value = target
        _failure.value = null
        viewModelScope.launch {
            val result = activator.activate(target)
            if (result is AiModelActivation.Failed) _failure.value = result
            _applying.value = null
        }
    }

    /** Тест моделей: меню видно только админу (флаг из validate лицензии). */
    val admin: StateFlow<AdminModelState> = adminModels.state

    fun pinAdminModel(pin: AdminModelPin) = adminModels.setPin(pin)

    /** Тест фразы вызова (админ): чем и что слушать сейчас. */
    val wakeListening: StateFlow<Pair<WakeEngineKind, WakePhrase>> = settings.wakeWordConfig
        .map { it.engine to it.phrase }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WakeEngineKind.PHRASE to WakePhrase.ALL)

    fun setWakeListening(engine: WakeEngineKind, phrase: WakePhrase) {
        viewModelScope.launch { settings.setWakeListening(engine, phrase) }
    }

    fun dismissFailure() {
        _failure.value = null
    }

    private companion object {
        /** Загрузка модели в память на медленном телефоне занимает десятки секунд. */
        const val LOCAL_LOAD_TIMEOUT_MS = 90_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
