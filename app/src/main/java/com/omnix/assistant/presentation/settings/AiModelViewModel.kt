package com.omnix.assistant.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnix.assistant.agent.decision.AiModelActivation
import com.omnix.assistant.agent.decision.AiModelSwitch
import com.omnix.assistant.agent.decision.AiModelChoice
import com.omnix.assistant.agent.localai.LocalModelManager
import com.omnix.assistant.agent.localai.LocalModelState
import com.omnix.assistant.core.admin.AdminModelPin
import com.omnix.assistant.core.admin.AdminModelState
import com.omnix.assistant.core.admin.AdminModelStore
import com.omnix.assistant.agent.decision.AiProAccess
import com.omnix.assistant.core.license.AiQuotaCalculator
import com.omnix.assistant.core.license.AiQuotaRemaining
import com.omnix.assistant.core.license.AiTierLedger
import com.omnix.assistant.core.license.LicenseInfo
import com.omnix.assistant.core.license.LicenseManager
import com.omnix.assistant.core.license.LocalAnswer
import com.omnix.assistant.core.license.QuotaExceeded
import com.omnix.assistant.data.remote.VeniceAdminApi
import com.omnix.assistant.data.remote.VeniceModelInfo
import com.omnix.assistant.data.remote.VeniceModelsResult
import kotlinx.coroutines.flow.combine
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
import javax.inject.Inject

/** Список моделей Venice для админа: приходит с сервера, не хранится в приложении. */
sealed interface VeniceUiState {
    data object Idle : VeniceUiState
    data object Loading : VeniceUiState
    data class Loaded(val models: List<VeniceModelInfo>) : VeniceUiState
    data object Unavailable : VeniceUiState
}

/**
 * Выбор модели ИИ (лист у поля ввода в чате) и раздел «ИИ-модель» в «Я»:
 * текущий выбор, состояние модели на телефоне, остаток на сегодня, смена
 * выбора с проверкой. Пока идёт проверка, другие варианты недоступны;
 * не включилась — прежний выбор остаётся, причина показывается.
 */
@HiltViewModel
class AiModelViewModel @Inject constructor(
    store: AiModelChoiceStore,
    private val switcher: AiModelSwitch,
    localModelManager: LocalModelManager,
    private val adminModels: AdminModelStore,
    private val settings: SettingsDataStore,
    private val licenseManager: LicenseManager,
    private val tierLedger: AiTierLedger,
    private val veniceApi: VeniceAdminApi
) : ViewModel() {

    val choice: StateFlow<AiModelChoice> = store.choice
    val localModelState: StateFlow<LocalModelState> = localModelManager.stateFlow

    private fun proInPlanNow(): Boolean = AiProAccess.isAvailable(licenseManager.getLicenseInfo().entitlements)

    /** Pro есть в тарифе (daily_pro_ai > 0); нет — вариант с замком «Доступно в Pro и Max». */
    val proAvailable: StateFlow<Boolean> = licenseManager.licenseFlow
        .map { AiProAccess.isAvailable(it.entitlements) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), proInPlanNow())

    private fun remainingOf(info: LicenseInfo, answers: List<LocalAnswer>, exhausted: List<QuotaExceeded>) =
        AiQuotaCalculator.remaining(
            entitlements = info.entitlements,
            usage = info.aiUsage,
            usageAtMs = info.verifiedAt,
            localAnswers = answers,
            exhausted = exhausted,
            nowMs = System.currentTimeMillis()
        )

    /** «Сегодня осталось»: снимок сервера из validate плюс ответы после него. */
    val remaining: StateFlow<AiQuotaRemaining> = combine(
        licenseManager.licenseFlow,
        tierLedger.answers,
        tierLedger.exhausted
    ) { info, answers, exhausted -> remainingOf(info, answers, exhausted) }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        remainingOf(licenseManager.getLicenseInfo(), tierLedger.answers.value, tierLedger.exhausted.value)
    )

    private val _applying = MutableStateFlow<AiModelChoice?>(null)

    /** Вариант, который сейчас проверяется; null — проверок нет. */
    val applying: StateFlow<AiModelChoice?> = _applying.asStateFlow()

    private val _failure = MutableStateFlow<AiModelActivation.Failed?>(null)

    /** Почему последний выбор не применился. */
    val failure: StateFlow<AiModelActivation.Failed?> = _failure.asStateFlow()

    /** Сменить модель (лист выбора в чате); проверка и сохранение — в [AiModelSwitch]. */
    fun select(target: AiModelChoice) {
        if (target == choice.value || _applying.value != null) return
        _applying.value = target
        _failure.value = null
        viewModelScope.launch {
            val result = switcher.switchTo(target)
            if (result is AiModelActivation.Failed) _failure.value = result
            _applying.value = null
        }
    }

    /** Тест моделей: меню видно только админу (флаг из validate лицензии). */
    val admin: StateFlow<AdminModelState> = adminModels.state

    fun pinAdminModel(pin: AdminModelPin) = adminModels.setPin(pin)

    private val _venice = MutableStateFlow<VeniceUiState>(VeniceUiState.Idle)

    /** Модели Venice (только админ): без админ-флага список не запрашивается вовсе. */
    val venice: StateFlow<VeniceUiState> = _venice.asStateFlow()

    fun loadVeniceModels() {
        if (!adminModels.state.value.isAdmin || _venice.value == VeniceUiState.Loading) return
        _venice.value = VeniceUiState.Loading
        viewModelScope.launch {
            _venice.value = when (val result = veniceApi.models()) {
                is VeniceModelsResult.Loaded -> VeniceUiState.Loaded(result.models)
                VeniceModelsResult.Unavailable -> VeniceUiState.Unavailable
            }
        }
    }

    /** Тест фразы вызова (админ): чем и что слушать сейчас. */
    val wakeListening: StateFlow<Pair<WakeEngineKind, WakePhrase>> = settings.wakeWordConfig
        .map { it.engine to it.phrase }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WakeEngineKind.PHRASE to WakePhrase.ALL)

    fun setWakeListening(engine: WakeEngineKind, phrase: WakePhrase) {
        viewModelScope.launch { settings.setWakeListening(engine, phrase) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
