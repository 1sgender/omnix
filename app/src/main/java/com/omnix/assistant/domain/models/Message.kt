package com.omnix.assistant.domain.models

data class Message(
    val id: Long = 0L,
    val role: MessageRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * Разговор сообщения. 0 при создании — репозиторий запишет его в текущий
     * разговор ([ChatConversationStore][com.omnix.assistant.domain.chat.ChatConversationStore]).
     */
    val conversationId: Long = 0L
)

/** Прошлый разговор в списке «История» чата. */
data class ConversationSummary(
    val id: Long,
    /** Первое сообщение пользователя; null — разговор без его сообщений. */
    val title: String?,
    val startedAt: Long,
    val lastMessageAt: Long,
    val messageCount: Int
)
