package com.omnix.assistant.domain.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Какой разговор сейчас открыт в чате.
 *
 * Чат начинается заново при каждом запуске (решение владельца 2026-10-21:
 * «каждый раз новый, историю можно просматривать»): процесс стартует с новым
 * разговором, [MainActivity][com.omnix.assistant.presentation.MainActivity]
 * начинает новый при каждом свежем открытии, «Новый чат» — тоже. Из истории
 * можно открыть прошлый разговор и продолжить его.
 *
 * id разговора — момент его начала (мс). Сообщения, сохранённые до появления
 * разговоров, лежат в разговоре [LEGACY_CONVERSATION_ID].
 */
@Singleton
class ChatConversationStore @Inject constructor() {

    private val _currentId = MutableStateFlow(System.currentTimeMillis())

    /** Разговор, в который пишутся новые сообщения и из которого берётся контекст для ИИ. */
    val currentId: StateFlow<Long> = _currentId.asStateFlow()

    /** Самый поздний id, выданный новому разговору: открытый из истории может быть старше. */
    private var lastStartedId = _currentId.value

    /**
     * Новый пустой разговор; id больше всех выданных, даже в ту же миллисекунду.
     * Сравнение только с текущим не годится: после «открыть прошлый чат» текущий
     * старше, и новый id мог совпасть с уже выданным — новый чат показал бы чужие сообщения.
     */
    @Synchronized
    fun startNew() {
        lastStartedId = maxOf(System.currentTimeMillis(), lastStartedId + 1, _currentId.value + 1)
        _currentId.value = lastStartedId
    }

    /** Продолжить разговор из истории. */
    fun open(conversationId: Long) {
        _currentId.value = conversationId
    }

    companion object {
        /** Сообщения, сохранённые до разделения чата на разговоры (миграция 5 → 6). */
        const val LEGACY_CONVERSATION_ID = 0L
    }
}
