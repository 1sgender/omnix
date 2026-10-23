package com.omnix.assistant.presentation.translator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnix.assistant.data.preferences.SettingsDataStore
import com.omnix.assistant.voice.conversation.ConversationInterpreter
import com.omnix.assistant.voice.conversation.ConversationState
import com.omnix.assistant.voice.orchestrator.OrchestratorMode
import com.omnix.assistant.voice.orchestrator.VoiceInteractionOrchestrator
import com.omnix.assistant.voice.wakeword.WakeWordEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Переводчик «по наушнику на каждого» (отзыв владельца на v344): один наушник
 * у владельца, второй у собеседника, OMNIX сам понимает, кто на каком языке
 * говорит, и переводит в ухо другого. Вся работа — в [ConversationInterpreter];
 * здесь микрофон, звонки и язык.
 */
@HiltViewModel
class LiveInterpreterViewModel @Inject constructor(
    private val interpreter: ConversationInterpreter,
    private val wakeWordEngine: WakeWordEngine,
    private val orchestrator: VoiceInteractionOrchestrator,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    val state: StateFlow<ConversationState> = interpreter.state

    /** Перевод вслух на телефоне, а не в наушники (общая настройка с «Наушниками»). */
    val outputToPhone: StateFlow<Boolean> = settingsDataStore.translatorToHeadphonesFlow
        .map { !it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * EAR-MODE (microphone): true, пока переводчик держит микрофон, отобрав его
     * у wake-word AudioRecord. Микрофон возвращается движку wake-word при
     * выходе, только если сервис стоит в STANDBY.
     */
    private var micTakenFromWakeWord = false

    /** EAR-MODE (phone call): звонок приостановил разговор — продолжить после. */
    @Volatile
    private var pausedByPhoneCall = false

    init {
        observeOrchestratorMode()
        // «Перевод в наушники» на экране «Наушники»: выключен — вслух на телефоне.
        viewModelScope.launch {
            settingsDataStore.translatorToHeadphonesFlow.collect { interpreter.setSplitEars(it) }
        }
    }

    /**
     * EAR-MODE (phone call): звонок забирает микрофон и звук. Разговор
     * останавливается и продолжается сам, когда сервис выходит из паузы.
     */
    private fun observeOrchestratorMode() {
        viewModelScope.launch {
            orchestrator.currentMode.collectLatest { mode ->
                if (mode == OrchestratorMode.PAUSED_CALL_OR_SLEEP) {
                    if (state.value.running) {
                        pausedByPhoneCall = true
                        interpreter.stop()
                    }
                } else if (pausedByPhoneCall) {
                    pausedByPhoneCall = false
                    // Сервис после паузы перезапускает wake-word — даём ему
                    // закончить и снова забираем микрофон.
                    delay(200)
                    if (orchestrator.currentMode.value != OrchestratorMode.PAUSED_CALL_OR_SLEEP) start()
                }
            }
        }
    }

    fun setMyLanguage(code: String) {
        val current = state.value
        interpreter.configure(code, current.theirLanguage?.takeIf { it != code }, current.theirEar)
    }

    /** null — «определю сам» по первой фразе собеседника. */
    fun setTheirLanguage(code: String?) {
        val current = state.value
        interpreter.configure(current.myLanguage, code?.takeIf { it != current.myLanguage }, current.theirEar)
    }

    fun swapEars() = interpreter.swapEars()

    fun setOutputToPhone(toPhone: Boolean) {
        viewModelScope.launch { settingsDataStore.setTranslatorToHeadphones(!toPhone) }
    }

    fun start() {
        if (state.value.running) return
        // EAR-MODE (microphone): с Android 10 микрофон по-настоящему получает
        // один захватчик — пока wake-движок держит AudioRecord, разговор
        // слышал бы тишину.
        wakeWordEngine.stop()
        micTakenFromWakeWord = true
        interpreter.start(viewModelScope)
    }

    fun stop() {
        interpreter.stop()
        restoreWakeWordIfNeeded()
    }

    /** «Завершить разговор»: остановить и очистить расшифровку. */
    fun endConversation() {
        stop()
        interpreter.clear()
    }

    private fun restoreWakeWordIfNeeded() {
        if (!micTakenFromWakeWord) return
        micTakenFromWakeWord = false
        if (orchestrator.currentMode.value == OrchestratorMode.STANDBY_WAKE_WORD) {
            wakeWordEngine.start()
        }
    }

    override fun onCleared() {
        super.onCleared()
        interpreter.stop()
        restoreWakeWordIfNeeded()
    }
}
