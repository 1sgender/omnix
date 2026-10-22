package com.omnix.assistant.data.repository

import com.omnix.assistant.core.dispatcher.CoroutineDispatchers
import com.omnix.assistant.data.local.dao.MessageDao
import com.omnix.assistant.data.local.entity.toDomain
import com.omnix.assistant.data.local.entity.toEntity
import com.omnix.assistant.domain.chat.ChatConversationStore
import com.omnix.assistant.domain.models.ConversationSummary
import com.omnix.assistant.domain.models.Message
import com.omnix.assistant.domain.repository.MessageRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val dispatchers: CoroutineDispatchers,
    private val conversations: ChatConversationStore
) : MessageRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getMessagesStream(): Flow<List<Message>> {
        return conversations.currentId
            .flatMapLatest { id -> messageDao.getConversationStream(id) }
            .map { list -> list.map { it.toDomain() } }
            .flowOn(dispatchers.io)
    }

    // Новый чат не видит прошлых разговоров: в контекст ИИ идёт только
    // текущий (долговременная память — отдельно, OmniMemoryManager).
    override suspend fun getRecentMessages(limit: Int): List<Message> {
        return withContext(dispatchers.io) {
            messageDao.getRecentMessagesInConversation(conversations.currentId.value, limit)
                .reversed()
                .map { it.toDomain() }
        }
    }

    override suspend fun getLastMessage(): Message? {
        return withContext(dispatchers.io) {
            messageDao.getLastMessage()?.toDomain()
        }
    }

    override suspend fun insertMessage(message: Message): Long {
        val stamped = if (message.conversationId == ChatConversationStore.LEGACY_CONVERSATION_ID) {
            message.copy(conversationId = conversations.currentId.value)
        } else {
            message
        }
        return withContext(dispatchers.io) {
            messageDao.insertMessage(stamped.toEntity())
        }
    }

    override fun getConversations(): Flow<List<ConversationSummary>> {
        return messageDao.getConversationSummaries()
            .map { rows ->
                rows.map { row ->
                    ConversationSummary(
                        id = row.conversationId,
                        title = row.title,
                        startedAt = row.startedAt,
                        lastMessageAt = row.lastMessageAt,
                        messageCount = row.messageCount
                    )
                }
            }
            .flowOn(dispatchers.io)
    }

    override suspend fun deleteConversation(conversationId: Long) {
        withContext(dispatchers.io) {
            messageDao.deleteConversation(conversationId)
        }
    }

    override suspend fun clearHistory() {
        withContext(dispatchers.io) {
            messageDao.clearAllMessages()
        }
    }

    override suspend fun deleteMessageById(id: Long) {
        withContext(dispatchers.io) {
            messageDao.deleteMessageById(id)
        }
    }
}
