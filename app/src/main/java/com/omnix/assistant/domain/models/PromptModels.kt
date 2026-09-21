package com.omnix.assistant.domain.models

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
        val localFallbackReason: String? = null
    ) : PromptExecutionResult

    data class ConfirmationRequired(
        val toolCall: ToolCall,
        val promptMessage: String
    ) : PromptExecutionResult
}
