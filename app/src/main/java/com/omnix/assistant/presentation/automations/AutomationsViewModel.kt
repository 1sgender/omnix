package com.omnix.assistant.presentation.automations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnix.assistant.agent.automation.dao.AutomationDao
import com.omnix.assistant.agent.automation.engine.AutomationDeletionStore
import com.omnix.assistant.agent.automation.entity.AutomationEntity
import com.omnix.assistant.agent.automation.model.AutomationDescriptions
import com.omnix.assistant.agent.automation.model.AutomationTriggerLabel
import com.omnix.assistant.agent.automation.model.LabeledAction
import com.omnix.assistant.agent.automation.scheduler.AutomationScheduleManager
import com.omnix.assistant.agent.model.ToolExecutionResult
import com.omnix.assistant.agent.tools.productivity.EarBriefingTool
import com.omnix.assistant.voice.tts.TextToSpeechManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject

/**
 * One rule as the screen shows it (§3: every field comes from the real
 * entity — the UI never invents state). Trigger and actions are label
 * tokens; the screen maps them to string resources so the three locales
 * stay consistent with the rest of the app.
 */
data class AutomationUiModel(
    val id: Long,
    val ruleId: String,
    val name: String,
    val trigger: AutomationTriggerLabel,
    val triggerParam: String?,
    val actions: List<LabeledAction>,
    val lastTriggeredAt: Long?,
    val triggerCount: Int,
    val isEnabled: Boolean
)

data class AutomationsUiState(
    val rules: List<AutomationUiModel> = emptyList(),
    val briefingRunning: Boolean = false,
    /** Honest outcome of the last "run briefing now" tap; null = none yet. */
    val briefingOutcome: String? = null,
    val briefingSucceeded: Boolean? = null
)

/**
 * Automations screen state and actions (gap report 2026-09-26, block 1).
 *
 * Everything maps 1:1 onto the existing DAO: the list is the same live Room
 * stream the settings counter reads; toggling and deleting go through the
 * same queries the engine matches against, so a disabled or deleted rule
 * stops firing because the matcher reads the same rows — not because the UI
 * hides it. TIME_SCHEDULE changes additionally re-reconcile the single
 * AlarmManager slot.
 */
@HiltViewModel
class AutomationsViewModel @Inject constructor(
    private val automationDao: AutomationDao,
    private val scheduleManager: AutomationScheduleManager,
    private val deletionStore: AutomationDeletionStore,
    private val earBriefingTool: EarBriefingTool,
    private val textToSpeechManager: TextToSpeechManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AutomationsUiState())
    val uiState: StateFlow<AutomationsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            automationDao.getAllAutomationsStream().collect { rules ->
                _uiState.update { state ->
                    state.copy(rules = rules.map { it.toUiModel() })
                }
            }
        }
    }

    fun toggleRule(ruleId: String, enabled: Boolean) {
        viewModelScope.launch {
            automationDao.toggleEnabled(ruleId, enabled)
            // Отключение/включение TIME_SCHEDULE меняет набор слотов будильника.
            scheduleManager.reconcile()
        }
    }

    fun deleteRule(ruleId: String) {
        viewModelScope.launch {
            automationDao.deleteAutomation(ruleId)
            // Дефолтные правила иначе воскреснут при следующей инициализации.
            deletionStore.markDeleted(ruleId)
            scheduleManager.reconcile()
        }
    }

    /**
     * «Брифинг сейчас» — тот же инструмент, что и голосовой путь
     * (productivity.ear_briefing): генерация + тарифная квота в одном месте.
     * Успех озвучивается через общий TTS; провал показывается честным текстом
     * результата (например, исчерпан лимит брифингов) и НЕ произносится.
     */
    fun runBriefingNow() {
        if (_uiState.value.briefingRunning) return
        _uiState.update { it.copy(briefingRunning = true, briefingOutcome = null, briefingSucceeded = null) }
        viewModelScope.launch {
            val result: ToolExecutionResult = earBriefingTool.execute(buildJsonObject { })
            if (result.isSuccess) {
                textToSpeechManager.speak(result.summary)
            }
            _uiState.update {
                it.copy(
                    briefingRunning = false,
                    briefingOutcome = result.summary,
                    briefingSucceeded = result.isSuccess
                )
            }
        }
    }

    private fun com.omnix.assistant.agent.automation.entity.AutomationEntity.toUiModel() =
        AutomationUiModel(
            id = id,
            ruleId = ruleId,
            name = name,
            trigger = AutomationDescriptions.triggerLabel(triggerType, triggerParam).first,
            triggerParam = AutomationDescriptions.triggerLabel(triggerType, triggerParam).second,
            actions = AutomationDescriptions.actionLabels(actionsJson),
            lastTriggeredAt = lastTriggeredAt.takeIf { it > 0L },
            triggerCount = triggerCount,
            isEnabled = isEnabled
        )
}
