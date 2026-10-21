package com.omnix.assistant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.omnix.assistant.data.local.entity.ConversationSummaryRow
import com.omnix.assistant.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessagesStream(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(limit: Int): List<MessageEntity>

    /** Один разговор по порядку — то, что показывает чат. */
    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp ASC")
    fun getConversationStream(conversationId: Long): Flow<List<MessageEntity>>

    /** Последние сообщения разговора, новые первыми — контекст для ИИ. */
    @Query(
        "SELECT * FROM messages WHERE conversation_id = :conversationId " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun getRecentMessagesInConversation(conversationId: Long, limit: Int): List<MessageEntity>

    /**
     * Разговоры для «Истории», свежие первыми. Заголовок — первое сообщение
     * пользователя в разговоре.
     */
    @Query(
        "SELECT m.conversation_id AS conversationId, MIN(m.timestamp) AS startedAt, " +
            "MAX(m.timestamp) AS lastMessageAt, COUNT(*) AS messageCount, " +
            "(SELECT u.text FROM messages u WHERE u.conversation_id = m.conversation_id " +
            "AND u.role = 'user' ORDER BY u.timestamp ASC LIMIT 1) AS title " +
            "FROM messages m GROUP BY m.conversation_id ORDER BY lastMessageAt DESC"
    )
    fun getConversationSummaries(): Flow<List<ConversationSummaryRow>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessage(): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("DELETE FROM messages")
    suspend fun clearAllMessages()

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteConversation(conversationId: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessageById(id: Long)
}
