package com.omnix.assistant.domain.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-model «этот ответ ассистента пришёл из облака» для подписи
 * «Ответ из облака» под ним в чате (design v2).
 *
 * Промежуточное решение без смены Room-схемы: история чата ([Message][com.omnix.assistant.domain.models.Message])
 * не хранит источник выполнения. Пока — ин-мемори множество id сообщений,
 * помеченных при сохранении: [SendPromptUseCase][com.omnix.assistant.domain.usecases.SendPromptUseCase]
 * знает и id (результат insert), и [ExecutionType][com.omnix.assistant.agent.decision.ExecutionType].
 *
 * Ограничения (осознанные): метки живут в рамках процесса — после перезапуска
 * подпись пропадает у старых сообщений; без пометки подписи нет, так что
 * ответ никогда не назван облачным по ошибке.
 */
@Singleton
class ChatAnswerOriginStore @Inject constructor() {

    private val _cloudMessageIds = MutableStateFlow<Set<Long>>(emptySet())

    /** id сообщений ассистента, полученных из облачной модели (CLOUD_AI). */
    val cloudMessageIds: StateFlow<Set<Long>> = _cloudMessageIds.asStateFlow()

    /**
     * Пометить сообщение как облачный ответ. Невалидные id (0 = insert не
     * вернул строку) игнорируются — молча, это телеметрия отображения, а не
     * источник правды.
     */
    fun markCloud(messageId: Long) {
        if (messageId <= 0L) return
        _cloudMessageIds.update { ids -> ids + messageId }
    }
}
