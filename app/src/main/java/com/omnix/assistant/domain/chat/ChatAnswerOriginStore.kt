package com.omnix.assistant.domain.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-model «этот ответ ассистента обработан на устройстве» для UI.
 *
 * Промежуточное решение без смены Room-схемы: история чата ([Message][com.omnix.assistant.domain.models.Message])
 * не хранит источник выполнения, а миграция v5→v6 (колонка + экспорт схемы +
 * MigrationTestHelper) зарезервирована под отдельный PR. Пока — ин-мемори
 * множество id сообщений, помеченных при сохранении: [SendPromptUseCase][com.omnix.assistant.domain.usecases.SendPromptUseCase]
 * знает и id (результат insert), и [ExecutionType][com.omnix.assistant.agent.decision.ExecutionType].
 *
 * Ограничения (осознанные): метки живут в рамках процесса — после перезапуска
 * бейдж пропадает у старых сообщений (cloud-ответы бейджа и не имеют по
 * дизайну: облако = «норма»). Пометка НЕ деградирует: ровно один writer
 * (use case) и один читатель (чат-стрим) при сериализованных отправках.
 */
@Singleton
class ChatAnswerOriginStore @Inject constructor() {

    private val _onDeviceMessageIds = MutableStateFlow<Set<Long>>(emptySet())

    /** id сообщений ассистента, обработанных локально (DEVICE_TOOL / LOCAL_AI). */
    val onDeviceMessageIds: StateFlow<Set<Long>> = _onDeviceMessageIds.asStateFlow()

    /**
     * Пометить сообщение как обработанное на устройстве. Невалидные id
     * (0 = insert не вернул строку) игнорируются — молча, это телеметрия
     * отображения, а не источник правды.
     */
    fun markOnDevice(messageId: Long) {
        if (messageId <= 0L) return
        _onDeviceMessageIds.update { ids -> ids + messageId }
    }
}
