package com.omnix.assistant.presentation.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnix.assistant.domain.models.VoiceAssistantState
import com.omnix.assistant.voice.orchestrator.OrchestratorMode
import com.omnix.assistant.voice.orchestrator.VoiceInteractionOrchestrator
import com.omnix.assistant.voice.service.OmnixVoiceService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Что сейчас делает OMNIX — по этому живёт круг и подпись голосового режима. */
enum class VoicePhase { STARTING, IDLE, LISTENING, THINKING, SPEAKING, CONFIRMING }

/** Почему голосовой режим не может работать. */
enum class VoiceModeProblem { MICROPHONE_PERMISSION, START_FAILED }

data class VoiceModeUiState(
    val phase: VoicePhase = VoicePhase.STARTING,
    /** Что OMNIX услышал (или слышит сейчас). */
    val heard: String = "",
    /** Последний ответ. */
    val answer: String = "",
    /** Громкость голоса 0..1, пока OMNIX слушает. */
    val level: Float = 0f,
    val problem: VoiceModeProblem? = null,
    /** Ошибка ассистента его же словами (например, «Голосовая активация недоступна»). */
    val assistantError: String? = null
)

/**
 * Режим ассистента → фаза голосового экрана. Чистая функция (JVM-тест).
 * Пока служба не поднялась — [VoicePhase.STARTING].
 */
internal fun voicePhaseOf(serviceActive: Boolean, mode: OrchestratorMode): VoicePhase = when {
    !serviceActive -> VoicePhase.STARTING
    else -> when (mode) {
        OrchestratorMode.LISTENING_USER_QUERY,
        OrchestratorMode.CONTINUOUS_CONVERSATION -> VoicePhase.LISTENING
        OrchestratorMode.AI_THINKING -> VoicePhase.THINKING
        OrchestratorMode.TTS_SPEAKING -> VoicePhase.SPEAKING
        OrchestratorMode.AWAITING_CONFIRMATION,
        OrchestratorMode.AWAITING_PRIVACY_CONSENT -> VoicePhase.CONFIRMING
        OrchestratorMode.STANDBY_WAKE_WORD,
        OrchestratorMode.LIVE_EAR_INTERPRETER,
        OrchestratorMode.PAUSED_CALL_OR_SLEEP -> VoicePhase.IDLE
    }
}

/**
 * Голосовой режим на весь экран (макет владельца 2026-10-21): открыл —
 * OMNIX сразу слушает, без слова «Omni». Если ассистент был выключен, режим
 * включает его сам и выключает при выходе; включённый пользователем ассистент
 * остаётся включённым. Разговор идёт в текущий чат (его видно в истории).
 */
@HiltViewModel
class VoiceModeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val orchestrator: VoiceInteractionOrchestrator
) : ViewModel() {

    private val problem = MutableStateFlow<VoiceModeProblem?>(null)
    private var entered = false
    private var startedHere = false

    val uiState: StateFlow<VoiceModeUiState> = combine(
        combine(orchestrator.serviceActive, orchestrator.currentMode) { active, mode -> active to mode },
        orchestrator.assistantState,
        combine(orchestrator.lastQuery, orchestrator.lastAnswer) { query, answer -> query to answer },
        orchestrator.audioLevel,
        problem
    ) { (active, mode), assistant, (query, answer), level, startProblem ->
        val phase = voicePhaseOf(active, mode)
        VoiceModeUiState(
            phase = phase,
            heard = (assistant as? VoiceAssistantState.Recognizing)?.partialText?.takeIf { it.isNotBlank() } ?: query,
            answer = answer,
            level = if (phase == VoicePhase.LISTENING) level.coerceIn(0f, 1f) else 0f,
            problem = startProblem,
            assistantError = (assistant as? VoiceAssistantState.Error)?.userFriendlyMessage
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), VoiceModeUiState())

    /** Экран открылся (или выдали микрофон): включить ассистента при нужде и сразу слушать. */
    fun enter() {
        if (entered) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            problem.value = VoiceModeProblem.MICROPHONE_PERMISSION
            return
        }
        problem.value = null
        entered = true
        viewModelScope.launch {
            if (!orchestrator.serviceActive.value) {
                if (!OmnixVoiceService.start(context)) {
                    problem.value = VoiceModeProblem.START_FAILED
                    return@launch
                }
                startedHere = true
                val up = withTimeoutOrNull(START_TIMEOUT_MS) { orchestrator.serviceActive.first { it } }
                if (up == null) {
                    problem.value = VoiceModeProblem.START_FAILED
                    return@launch
                }
            }
            // Слушать сразу, без «Omni»: из режима ожидания.
            val ready = withTimeoutOrNull(START_TIMEOUT_MS) {
                orchestrator.currentMode.first { it == OrchestratorMode.STANDBY_WAKE_WORD }
            }
            if (ready != null) orchestrator.toggleListeningFromUi()
        }
    }

    /** Кнопка микрофона: начать говорить или перестать слушать. */
    fun toggleMic() {
        orchestrator.toggleListeningFromUi()
    }

    /** Выход из режима: перестать слушать; выключить ассистента, если его включил этот режим. */
    fun leave() {
        if (!entered) return
        entered = false
        if (orchestrator.currentMode.value == OrchestratorMode.LISTENING_USER_QUERY) {
            orchestrator.toggleListeningFromUi()
        }
        if (startedHere) {
            startedHere = false
            OmnixVoiceService.stop(context)
        }
    }

    override fun onCleared() {
        leave()
        super.onCleared()
    }

    private companion object {
        const val START_TIMEOUT_MS = 8_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
