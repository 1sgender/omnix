package com.omnix.assistant.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.omnix.assistant.domain.models.Message
import com.omnix.assistant.domain.models.MessageRole

@Entity(
    tableName = "messages",
    indices = [Index(value = ["timestamp"]), Index(value = ["conversation_id"])]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "role")
    val role: String,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    /**
     * Разговор, к которому относится сообщение (v6). Старые сообщения после
     * миграции 5 → 6 получают 0 — общий разговор «Ранее».
     */
    @ColumnInfo(name = "conversation_id", defaultValue = "0")
    val conversationId: Long = 0L
)

/** Строка списка «История»: один разговор. */
data class ConversationSummaryRow(
    val conversationId: Long,
    val startedAt: Long,
    val lastMessageAt: Long,
    val messageCount: Int,
    /** Первое сообщение пользователя; null, если он ещё ничего не написал. */
    val title: String?
)

fun MessageEntity.toDomain(): Message {
    return Message(
        id = id,
        role = MessageRole.fromString(role),
        text = text,
        timestamp = timestamp,
        conversationId = conversationId
    )
}

fun Message.toEntity(): MessageEntity {
    return MessageEntity(
        id = id,
        role = role.value,
        text = text,
        timestamp = timestamp,
        conversationId = conversationId
    )
}
