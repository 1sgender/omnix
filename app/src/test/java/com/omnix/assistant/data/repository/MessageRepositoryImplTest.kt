package com.omnix.assistant.data.repository

import com.omnix.assistant.data.local.dao.MessageDao
import com.omnix.assistant.data.local.entity.ConversationSummaryRow
import com.omnix.assistant.data.local.entity.MessageEntity
import com.omnix.assistant.domain.chat.ChatConversationStore
import com.omnix.assistant.domain.models.Message
import com.omnix.assistant.domain.models.MessageRole
import com.omnix.assistant.testing.ImmediateTestDispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageRepositoryImplTest {
    private val store = ChatConversationStore()

    private fun repository(dao: MessageDao) = MessageRepositoryImpl(dao, ImmediateTestDispatchers(), store)

    @Test
    fun `stream maps the current conversation to domain messages`() = runTest {
        val dao = FakeMessageDao()
        val current = store.currentId.value
        dao.messages.value = listOf(
            MessageEntity(1, "USER", "hello", 10, current),
            MessageEntity(2, "assistant", "hi", 20, current),
            MessageEntity(3, "user", "another chat", 30, current - 1)
        )

        val result = repository(dao).getMessagesStream().first()

        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), result.map(Message::role))
        assertEquals(listOf("hello", "hi"), result.map(Message::text))
    }

    @Test
    fun `stream follows the conversation that is open`() = runTest {
        val dao = FakeMessageDao()
        val first = store.currentId.value
        store.startNew()
        val second = store.currentId.value
        dao.messages.value = listOf(
            MessageEntity(1, "user", "old", 10, first),
            MessageEntity(2, "user", "new", 20, second)
        )
        val repository = repository(dao)

        assertEquals(listOf("new"), repository.getMessagesStream().first().map(Message::text))
        store.open(first)
        assertEquals(listOf("old"), repository.getMessagesStream().first().map(Message::text))
    }

    @Test
    fun `recent messages come only from the current conversation, in order`() = runTest {
        val dao = FakeMessageDao().apply {
            recent = listOf(
                MessageEntity(3, "assistant", "third", 30),
                MessageEntity(2, "user", "second", 20)
            )
        }

        val result = repository(dao).getRecentMessages(2)

        assertEquals(2, dao.lastLimit)
        assertEquals(store.currentId.value, dao.lastConversationId)
        assertEquals(listOf("second", "third"), result.map(Message::text))
    }

    @Test
    fun `a new message is written to the current conversation`() = runTest {
        val dao = FakeMessageDao().apply { nextInsertId = 42 }
        val message = Message(7, MessageRole.SYSTEM, "system", 123)

        assertEquals(42L, repository(dao).insertMessage(message))

        assertEquals(MessageEntity(7, "system", "system", 123, store.currentId.value), dao.inserted)
    }

    @Test
    fun `a message that names its conversation keeps it`() = runTest {
        val dao = FakeMessageDao()

        repository(dao).insertMessage(Message(role = MessageRole.USER, text = "x", timestamp = 1, conversationId = 5))

        assertEquals(5L, dao.inserted?.conversationId)
    }

    @Test
    fun `clear, delete message and delete conversation delegate exact values`() = runTest {
        val dao = FakeMessageDao()
        val repository = repository(dao)

        repository.deleteMessageById(7)
        repository.deleteConversation(9)
        repository.clearHistory()

        assertEquals(7L, dao.deletedId)
        assertEquals(9L, dao.deletedConversation)
        assertEquals(1, dao.clearCalls)
    }

    @Test
    fun `conversation rows map to summaries`() = runTest {
        val dao = FakeMessageDao().apply {
            summaries.value = listOf(ConversationSummaryRow(5, 10, 20, 3, "Привет"))
        }

        val result = repository(dao).getConversations().first().single()

        assertEquals(5L, result.id)
        assertEquals("Привет", result.title)
        assertEquals(10L, result.startedAt)
        assertEquals(20L, result.lastMessageAt)
        assertEquals(3, result.messageCount)
    }

    @Test
    fun `missing last message remains null and DAO failures propagate`() = runTest {
        val dao = FakeMessageDao()
        val repository = repository(dao)
        assertNull(repository.getLastMessage())

        dao.failure = IllegalStateException("database unavailable")
        var thrown: IllegalStateException? = null
        try {
            repository.getRecentMessages(10)
        } catch (failure: IllegalStateException) {
            thrown = failure
        }
        assertEquals("database unavailable", thrown?.message)
    }

    private class FakeMessageDao : MessageDao {
        val messages = MutableStateFlow<List<MessageEntity>>(emptyList())
        val summaries = MutableStateFlow<List<ConversationSummaryRow>>(emptyList())
        var recent: List<MessageEntity> = emptyList()
        var last: MessageEntity? = null
        var inserted: MessageEntity? = null
        var nextInsertId: Long = 1
        var deletedId: Long? = null
        var deletedConversation: Long? = null
        var clearCalls = 0
        var lastLimit: Int? = null
        var lastConversationId: Long? = null
        var failure: RuntimeException? = null

        override fun getAllMessagesStream(): Flow<List<MessageEntity>> = messages
        override suspend fun getRecentMessages(limit: Int): List<MessageEntity> = recent
        override fun getConversationStream(conversationId: Long): Flow<List<MessageEntity>> =
            messages.map { list -> list.filter { it.conversationId == conversationId } }
        override suspend fun getRecentMessagesInConversation(conversationId: Long, limit: Int): List<MessageEntity> {
            failure?.let { throw it }
            lastLimit = limit
            lastConversationId = conversationId
            return recent
        }
        override fun getConversationSummaries(): Flow<List<ConversationSummaryRow>> = summaries
        override suspend fun getLastMessage(): MessageEntity? = last
        override suspend fun insertMessage(message: MessageEntity): Long {
            inserted = message
            return nextInsertId
        }
        override suspend fun clearAllMessages() {
            clearCalls++
        }
        override suspend fun deleteConversation(conversationId: Long) {
            deletedConversation = conversationId
        }
        override suspend fun deleteMessageById(id: Long) {
            deletedId = id
        }
    }
}
