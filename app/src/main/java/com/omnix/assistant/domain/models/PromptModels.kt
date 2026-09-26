package com.omnix.assistant.domain.models

import com.omnix.assistant.agent.decision.ExecutionType
import com.omnix.assistant.agent.model.ToolCall

/**
 * Типобезопасный результат обработки запроса в OMNIX Core
 */
sealed interface PromptExecutionResult {
    data class DirectAnswer(
        val text: String,
        /** Accessibility Lockdown: текст прочитан с экрана — в историю идёт placeholder. */
        val containsScreenContent: Boolean = false,
        /**
         * Локальная модель не стартовала, ответ пришёл из облака — UI
         * показывает пользователю сообщение про офлайн-версию
         * (решение владельца 2026-09-21).
         */
        val localFallbackReason: String? = null,
        /**
         * Каким путём конвейер обработал запрос (audit 2026-09-26, блок
         * «on-device бейдж»): UI помечает ответ «обработано на устройстве»
         * для [ExecutionType.DEVICE_TOOL] и [ExecutionType.LOCAL_AI]; облако
         * (CLOUD_AI/AGENT) — «норма», без бейджа. Null = путь неизвестен
         * (например, clarification-обёртка без выполнения).
         */
        val executionType: ExecutionType? = null
    ) : PromptExecutionResult

    data class ConfirmationRequired(
        val toolCall: ToolCall,
        val promptMessage: String
    ) : PromptExecutionResult
}

/**
 * Считать ли ответ «обработанным на устройстве» для бейджа в чате:
 * локальная команда инструмента и офлайн-слой (on-device модель +
 * процедурная память) — да; облако и агент — нет (облако = норма,
 * бейдж не рисуется). Null — путь неизвестен, тоже без бейджа.
 */
val ExecutionType?.handledOnDevice: Boolean
    get() = this == ExecutionType.DEVICE_TOOL || this == ExecutionType.LOCAL_AI
