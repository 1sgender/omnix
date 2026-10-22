package com.omnix.assistant.data.preferences

import com.omnix.assistant.agent.decision.AiModelChoice
import com.omnix.assistant.agent.decision.AiModelChoiceSource
import com.omnix.assistant.core.admin.AdminModelTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Выбор ИИ-модели, сохранённый в DataStore (переживает перезапуск).
 *
 * Движок решений читает [current] на каждый запрос без suspend: значение
 * держится в памяти и обновляется при каждой записи. До первого чтения
 * DataStore (доли секунды после холодного старта) действует «Авто» —
 * прежнее поведение, без ухода в непредусмотренный маршрут.
 */
@Singleton
class AiModelChoiceStore @Inject constructor(
    private val settings: SettingsDataStore,
    private val adminModels: AdminModelTesting
) : AiModelChoiceSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val choice: StateFlow<AiModelChoice> = settings.aiModelChoiceFlow
        .map(AiModelChoice::fromStorageKey)
        .stateIn(scope, SharingStarted.Eagerly, AiModelChoice.AUTO)

    /** Модель, закреплённая админом для теста, требует своего маршрута. */
    override fun current(): AiModelChoice = adminModels.pin().routing ?: choice.value

    suspend fun set(choice: AiModelChoice) {
        settings.setAiModelChoice(choice.storageKey)
    }
}
